package eu.kanade.tachiyomi.util.qnn

import android.content.Context
import android.util.Base64
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/** Downloads only authenticated encrypted calibration crops, never prequantized models. */
object QnnCalibrationPacks {
    private val downloadMutex = Mutex()
    private const val BUFFER_SIZE = 256 * 1024
    private const val SAMPLE_SIZE = 256 * 256 * 3

    fun isConfigured(): Boolean = BuildConfig.QNN_CALIBRATION_KEY.isNotBlank()

    fun domain(model: String): String = when (model) {
        "realesrgan-general-x4v3-x2", "span-nomosuni-x2", "w2xex-photo-small-x2" -> "photo"
        else -> "manga"
    }

    private fun catalog(context: Context): JSONObject = JSONObject(
        context.assets.open("qnn-calibration/catalog.json").bufferedReader().use { it.readText() },
    )

    fun identity(context: Context, model: String): String =
        catalog(context).getJSONObject("packs").getJSONObject(domain(model)).getString("sha256")

    suspend fun <T> withSamples(
        context: Context,
        model: String,
        onProgress: (Long, Long) -> Unit,
        block: suspend (File) -> T,
    ): T = downloadMutex.withLock {
        check(isConfigured()) { "This build has no calibration decryption key" }
        val metadata = catalog(context)
        val pack = metadata.getJSONObject("packs").getJSONObject(domain(model))
        val key = Base64.decode(BuildConfig.QNN_CALIBRATION_KEY, Base64.DEFAULT)
        check(key.size == 32 && sha256(key).take(16) == metadata.getString("keyId")) {
            "Calibration key does not match this release"
        }
        val root = File(context.noBackupFilesDir, "qnn-calibration")
        check(root.isDirectory || root.mkdirs()) { "Unable to create calibration directory" }
        // Recover plaintext workspaces left by a killed process. The mutex
        // guarantees none of these belong to another active preparation.
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("temporary-") }?.forEach {
            check(it.canonicalFile.parentFile == root.canonicalFile)
            check(it.deleteRecursively()) { "Unable to clean an interrupted calibration workspace" }
        }
        val encrypted = File(root, pack.getString("sha256") + ".aes")
        val expectedSize = pack.getLong("bytes")
        if (!encrypted.isFile || encrypted.length() != expectedSize || sha256(encrypted) != pack.getString("sha256")) {
            val partial = File(root, encrypted.name + ".partial")
            download(pack.getString("url"), partial, expectedSize, onProgress)
            check(partial.length() == expectedSize && sha256(partial) == pack.getString("sha256")) {
                "Calibration download checksum mismatch"
            }
            check(partial.renameTo(encrypted)) { "Unable to commit calibration download" }
        }
        val temporary = File(root, "temporary-${UUID.randomUUID()}")
        check(temporary.mkdir()) { "Unable to create private calibration workspace" }
        try {
            coroutineContext.ensureActive()
            val archive = File(temporary, "calibration.zip")
            decrypt(encrypted, archive, key, pack.getString("aad"))
            val samples = File(temporary, "samples")
            check(samples.mkdir())
            extract(archive, samples, pack, metadata.getString("version"), domain(model))
            archive.delete()
            block(samples)
        } finally {
            key.fill(0)
            // Plaintext stays in this exclusively owned private workspace.
            check(temporary.canonicalFile.parentFile == root.canonicalFile)
            temporary.deleteRecursively()
        }
    }

    private suspend fun download(url: String, partial: File, expectedSize: Long, progress: (Long, Long) -> Unit) {
        require(URL(url).protocol == "https")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Calibration download HTTP ${connection.responseCode}"
            }
            require(connection.url.protocol == "https")
            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                partial.outputStream().buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    progress(0, expectedSize)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        check(downloaded <= expectedSize) { "Calibration download exceeds expected size" }
                        output.write(buffer, 0, count)
                        progress(downloaded, expectedSize)
                    }
                    check(downloaded == expectedSize) { "Incomplete calibration download" }
                }
            }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    internal suspend fun decrypt(encrypted: File, archive: File, key: ByteArray, aad: String) {
        encrypted.inputStream().buffered(BUFFER_SIZE).use { input ->
            val header = ByteArray(20)
            var headerBytes = 0
            while (headerBytes < header.size) {
                val count = input.read(header, headerBytes, header.size - headerBytes)
                check(count > 0) { "Truncated encrypted calibration header" }
                headerBytes += count
            }
            check(header.copyOfRange(0, 8).contentEquals("MIHQCAL1".toByteArray())) {
                "Invalid encrypted calibration format"
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, header.copyOfRange(8, 20)),
            )
            cipher.updateAAD(aad.toByteArray())
            archive.outputStream().buffered(BUFFER_SIZE).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    val plain = cipher.update(buffer, 0, count)
                    if (plain != null) {
                        output.write(plain)
                        written += plain.size
                    }
                    check(written <= 96L * 1024 * 1024) { "Calibration archive exceeds size limit" }
                }
                // No archive contents are opened until the GCM authentication tag succeeds.
                output.write(cipher.doFinal())
            }
        }
    }

    private suspend fun extract(archive: File, directory: File, pack: JSONObject, version: String, domain: String) {
        val allowed =
            Regex("(?:input-|verify-)\\d{3}\\.rgb|manifest\\.json|ATTRIBUTION\\.txt|LICENSE\\.OpenMantra\\.txt")
        val names = mutableSetOf<String>()
        ZipInputStream(archive.inputStream().buffered(BUFFER_SIZE)).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                check(!entry.isDirectory && entry.name.matches(allowed) && names.add(entry.name)) {
                    "Unexpected calibration archive entry"
                }
                val destination = File(directory, entry.name)
                check(destination.canonicalFile.parentFile == directory.canonicalFile)
                val limit = if (entry.name.endsWith(".rgb")) SAMPLE_SIZE else 1024 * 1024
                destination.outputStream().buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = zip.read(buffer)
                        if (count < 0) break
                        written += count
                        check(written <= limit) { "Calibration entry exceeds size limit" }
                        output.write(buffer, 0, count)
                    }
                }
                check(names.size <= 276) { "Too many calibration samples" }
            }
        }
        val manifest = JSONObject(File(directory, "manifest.json").readText())
        check(
            manifest.getInt("schema") == 1 && manifest.getString("version") == version &&
                manifest.getString("domain") == domain,
        )
        check(
            manifest.getInt("tileSize") == 256 && manifest.getString("layout") == "NHWC" &&
                manifest.getString("format") == "RGB_U8",
        )
        val files = manifest.getJSONArray("files")
        val listed = mutableSetOf<String>()
        var calibration = 0
        var validation = 0
        for (i in 0 until files.length()) {
            coroutineContext.ensureActive()
            val item = files.getJSONObject(i)
            val name = item.getString("file")
            check(name.matches(Regex("(?:input-|verify-)\\d{3}\\.rgb")) && listed.add(name) && name in names)
            val file = File(directory, name)
            check(
                item.getInt("bytes") == SAMPLE_SIZE && file.length() == SAMPLE_SIZE.toLong() &&
                    sha256(file) == item.getString("sha256"),
            ) {
                "Calibration sample checksum mismatch"
            }
            when (item.getString("role")) {
                "calibration" -> {
                    check(name.startsWith("input-"))
                    calibration++
                }
                "validation" -> {
                    check(name.startsWith("verify-"))
                    validation++
                }
                else -> error("Invalid calibration sample role")
            }
        }
        check(calibration == pack.getInt("calibrationSamples") && validation == pack.getInt("validationSamples"))
        check(names.filter { it.endsWith(".rgb") }.toSet() == listed)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
