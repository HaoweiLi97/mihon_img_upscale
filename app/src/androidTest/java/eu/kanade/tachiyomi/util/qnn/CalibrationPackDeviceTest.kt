package eu.kanade.tachiyomi.util.qnn

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
class CalibrationPackDeviceTest {
    @Test
    fun encryptedPacksAuthenticateValidateAndCleanPlaintext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.getExternalFilesDir(null), "calibration-test")
        assumeTrue("Push the encrypted release fixtures before running this integration test", source.isDirectory)
        val catalog =
            JSONObject(context.assets.open("qnn-calibration/catalog.json").bufferedReader().use { it.readText() })
        val cache = File(context.noBackupFilesDir, "qnn-calibration").apply { mkdirs() }
        for ((domain, model) in listOf("manga" to "realcugan-se-x2-no-denoise", "photo" to "span-nomosuni-x2")) {
            val pack = catalog.getJSONObject("packs").getJSONObject(domain)
            val encrypted = File(source, pack.getString("file"))
            assertTrue("Missing encrypted fixture $encrypted", encrypted.isFile)
            encrypted.copyTo(File(cache, pack.getString("sha256") + ".aes"), overwrite = true)
            QnnCalibrationPacks.withSamples(context, model, { _, _ ->
                error("Cached pack unexpectedly redownloaded")
            }) { samples ->
                assertEquals(
                    pack.getInt("calibrationSamples"),
                    samples.listFiles().orEmpty().count {
                        it.name.startsWith("input-")
                    },
                )
                assertEquals(
                    pack.getInt("validationSamples"),
                    samples.listFiles().orEmpty().count {
                        it.name.startsWith("verify-")
                    },
                )
                assertTrue(
                    samples.listFiles().orEmpty().filter {
                        it.extension == "rgb"
                    }.all { it.length() == 196608L },
                )
            }
            assertFalse(cache.listFiles().orEmpty().any { it.name.startsWith("temporary-") })
            try {
                QnnCalibrationPacks.withSamples(context, model, { _, _ ->
                }) { throw CancellationException("test cancellation") }
                error("Cancellation was swallowed")
            } catch (_: CancellationException) {
                assertFalse(cache.listFiles().orEmpty().any { it.name.startsWith("temporary-") })
            }
            val tampered = File(context.cacheDir, "calibration-tampered.aes")
            val output = File(context.cacheDir, "calibration-unauthenticated.zip")
            val key = Base64.decode(BuildConfig.QNN_CALIBRATION_KEY, Base64.DEFAULT)
            try {
                encrypted.copyTo(tampered, overwrite = true)
                RandomAccessFile(tampered, "rw").use {
                    it.seek(it.length() - 1)
                    val original = it.readByte().toInt()
                    it.seek(it.length() - 1)
                    it.writeByte(original xor 1)
                }
                val badTag = runCatching {
                    QnnCalibrationPacks.decrypt(tampered, output, key, pack.getString("aad"))
                }.exceptionOrNull()
                assertTrue("Modified ciphertext passed authentication: $badTag", badTag is AEADBadTagException)
                val badKey = runCatching {
                    QnnCalibrationPacks.decrypt(encrypted, output, ByteArray(32), pack.getString("aad"))
                }.exceptionOrNull()
                assertTrue("Wrong key passed authentication: $badKey", badKey is AEADBadTagException)
            } finally {
                key.fill(0)
                tampered.delete()
                output.delete()
            }
        }
    }
}
