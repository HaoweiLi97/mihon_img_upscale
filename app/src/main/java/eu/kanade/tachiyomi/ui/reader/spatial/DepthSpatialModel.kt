package eu.kanade.tachiyomi.ui.reader.spatial

import android.content.Context
import eu.kanade.tachiyomi.util.qnn.QualcommHtp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

class DepthSpatialModel(context: Context) {
    private val applicationContext = context.applicationContext
    val directory = File(context.filesDir, "spatial-depth/da3-v0.61.0")
    val dlcFile = File(directory, DLC_NAME)

    val htpArchitecture: Int?
        get() = QualcommHtp.architecture(applicationContext)

    val isReady: Boolean
        get() = dlcFile.isFile && dlcFile.length() == DLC_SIZE

    fun contextFile(): File {
        val htpArchitecture = htpArchitecture ?: UNKNOWN_HTP_ARCHITECTURE
        return File(directory, "compiled/depth-anything-v3-htp-v$htpArchitecture-qnn249.bin")
    }

    suspend fun download(onProgress: suspend (downloaded: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            installModel(
                output = dlcFile,
                dlcName = DLC_NAME,
                dlcSize = DLC_SIZE,
                dlcSha256 = DLC_SHA256,
                archiveName = ARCHIVE_NAME,
                archiveSize = DOWNLOAD_SIZE,
                archiveSha256 = ARCHIVE_SHA256,
                url = DOWNLOAD_URL,
                progressOffset = 0L,
                onProgress = onProgress,
            )
        }

    private suspend fun installModel(
        output: File,
        dlcName: String,
        dlcSize: Long,
        dlcSha256: String,
        archiveName: String,
        archiveSize: Long,
        archiveSha256: String,
        url: String,
        progressOffset: Long,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        if (output.isFile && output.length() == dlcSize && output.sha256 == dlcSha256) {
            reportProgress(onProgress, progressOffset + archiveSize, TOTAL_DOWNLOAD_SIZE)
            return
        }
        val archive = File(directory, archiveName)
        val partialArchive = File(directory, "$archiveName.partial")
        if (!archive.isFile || archive.length() != archiveSize || archive.sha256 != archiveSha256) {
            archive.delete()
            downloadArchive(partialArchive, archiveSize, url, progressOffset, onProgress)
            check(partialArchive.length() == archiveSize) {
                "Incomplete model download: ${partialArchive.length()} of $archiveSize bytes"
            }
            check(partialArchive.sha256 == archiveSha256) { "Model archive checksum mismatch" }
            check(partialArchive.renameTo(archive)) { "Unable to install the model archive" }
        }
        val partialDlc = File(directory, "$dlcName.partial")
        partialDlc.delete()
        ZipInputStream(archive.inputStream().buffered(DOWNLOAD_BUFFER_SIZE)).use { zip ->
            var found = false
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == dlcName) {
                    FileOutputStream(partialDlc).buffered(DOWNLOAD_BUFFER_SIZE).use(zip::copyTo)
                    found = true
                    break
                }
            }
            check(found) { "$dlcName is missing from the downloaded archive" }
        }
        check(partialDlc.length() == dlcSize) { "Installed model has an unexpected size" }
        check(partialDlc.sha256 == dlcSha256) { "Installed model checksum mismatch" }
        output.delete()
        check(partialDlc.renameTo(output)) { "Unable to install $dlcName" }
        archive.delete()
    }

    private suspend fun downloadArchive(
        partial: File,
        expectedSize: Long,
        url: String,
        progressOffset: Long,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        var existingBytes = partial.length().takeIf { it in 1 until expectedSize } ?: 0L
        if (existingBytes == 0L) partial.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }
        connection.connect()
        val append = existingBytes > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (connection.responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            val code = connection.responseCode
            connection.disconnect()
            error("HTTP $code while downloading spatial models")
        }
        if (!append) existingBytes = 0L
        var currentBytes = existingBytes
        BufferedInputStream(connection.inputStream, DOWNLOAD_BUFFER_SIZE).use { input ->
            FileOutputStream(partial, append).buffered(DOWNLOAD_BUFFER_SIZE).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    currentBytes += count
                    reportProgress(onProgress, progressOffset + currentBytes, TOTAL_DOWNLOAD_SIZE)
                }
            }
        }
        connection.disconnect()
    }

    private var lastProgressUpdateAt = 0L

    private suspend fun reportProgress(
        callback: suspend (downloaded: Long, total: Long) -> Unit,
        downloaded: Long,
        total: Long,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (downloaded < total && now - lastProgressUpdateAt < PROGRESS_INTERVAL_MS) return
        lastProgressUpdateAt = now
        withContext(Dispatchers.Main.immediate) { callback(downloaded, total) }
    }

    private companion object {
        const val DLC_NAME = "depth_anything_v3.dlc"
        const val ARCHIVE_NAME = "depth_anything_v3-qnn_dlc-float.zip"
        const val DOWNLOAD_SIZE = 105_850_545L
        const val TOTAL_DOWNLOAD_SIZE = DOWNLOAD_SIZE
        const val DLC_SIZE = 150_067_148L
        const val ARCHIVE_SHA256 = "76276b3f19bc847c94449461c6812da395d4aeee78cd6c0af8ad73143429b73e"
        const val DLC_SHA256 = "9a8bc97170fa1bc7388c71cf11dd57e8080d56709e9e77309c612e26bc110870"
        const val DOWNLOAD_URL =
            "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/" +
                "depth_anything_v3/releases/v0.61.0/depth_anything_v3-qnn_dlc-float.zip"
        const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
        const val PROGRESS_INTERVAL_MS = 100L
        const val UNKNOWN_HTP_ARCHITECTURE = 0
    }
}

private val File.sha256: String
    get() {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
