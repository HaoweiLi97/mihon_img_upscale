package eu.kanade.tachiyomi.util.qnn

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualcommHtpDeviceTest {
    @Test
    fun detectsArchitectureAndPackagesUnpreparedFp16Models() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val architecture = QualcommHtp.architecture()

        assertNotNull("No supported HTP architecture was detected", architecture)
        context.assets.open("qnn-models/realesrgan-animevideov3-x2.dlc").use { input ->
            assertTrue(input.available() > 0)
        }
        assertTrue(context.assets.list("qnn-contexts").isNullOrEmpty())
        assertFalse(context.assets.list("qnn-models").orEmpty().any { "int8" in it })
        Log.i("QualcommHtpDeviceTest", "Detected HTP v$architecture; FP16 DLC bundled without offline contexts")
    }

    @Test
    fun initializesEnhancementAndReusesPersistedContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = "realesrgan-animevideov3-x2"
        val file = QnnModelContexts.contextFile(context, model)
        val previousModified = file.lastModified()

        assertTrue(
            Waifu2x.initRealESRGAN(
                context = context,
                scale = 2,
                processingBackend = Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU,
            ),
        )
        assertTrue("Device-compiled QNN enhancement context did not initialize", Waifu2x.isQnnActive())
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/"))
        assertTrue(file.length() > 0)
        if (previousModified > 0) {
            assertEquals("A previous process's context should be reused", previousModified, file.lastModified())
        }
        val modified = file.lastModified()
        assertEquals(file, QnnModelContexts.prepare(context, model))
        assertEquals("Cached contexts should not be regenerated", modified, file.lastModified())
    }

    @Test
    fun corruptContextIsRebuiltAndUnknownModelsAreRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = "realesrgan-animevideov3-x2"
        val original = requireNotNull(QnnModelContexts.prepare(context, model))
        val bytes = original.length()
        original.writeText("incomplete context")
        val repaired = requireNotNull(QnnModelContexts.prepare(context, model))
        assertEquals(bytes, repaired.length())
        assertTrue(QnnModelContexts.prepare(context, "../unknown") == null)
    }

    @Test
    fun allFp16ModelsCompileAndExecute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setPixel(x, y, Color.rgb(x * 7, y * 5, (x + y) * 3))
                }
            }
        }
        fun verify(name: String, initialized: Boolean, scale: Int, process: () -> Bitmap?) {
            assertTrue("Failed to initialize $name", initialized)
            assertTrue("$name silently fell back from QNN", Waifu2x.isQnnActive())
            val output = process()
            assertNotNull("$name produced no image", output)
            try {
                assertEquals(input.width * scale, output!!.width)
                assertEquals(input.height * scale, output.height)
                val pixels = IntArray(output.width * output.height)
                output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
                assertTrue("$name produced a constant image", pixels.toSet().size > 10)
                Log.i("QualcommHtpDeviceTest", "FP16 on-device compile + production inference passed: $name")
            } finally {
                output?.recycle()
            }
        }
        try {
            for (isPro in listOf(false, true)) {
                for (scale in if (isPro) listOf(2, 3) else listOf(2)) {
                    for (noise in if (isPro) listOf(0, 3, 4) else listOf(0, 1, 2, 3, 4)) {
                        val name = requireNotNull(Waifu2x.qnnModelName(if (isPro) 1 else 0, scale, noise))
                        verify(
                            name,
                            Waifu2x.initRealCugan(context, noise, scale, isPro = isPro, processingBackend = 1),
                            scale,
                        ) { Waifu2x.processRealCugan(input) }
                    }
                }
            }
            for (style in listOf(0, 1)) {
                verify(
                    requireNotNull(Waifu2x.qnnModelName(2, 2, 0, style)),
                    Waifu2x.initRealESRGAN(context, 2, style = style, processingBackend = 1),
                    2,
                ) { Waifu2x.processRealESRGAN(input) }
            }
            for (model in listOf(Waifu2x.MODEL_W2XEX_PHOTO_SMALL, Waifu2x.MODEL_SPAN_NOMOSUNI_PHOTO)) {
                verify(
                    requireNotNull(Waifu2x.qnnModelName(model, 2, 0)),
                    Waifu2x.initW2xEx(context, model, processingBackend = 1),
                    2,
                ) { Waifu2x.processW2xEx(input) }
            }
            assertEquals(0, Waifu2x.resolvePrecision(2, 1, 1, 2))
            assertEquals(0, Waifu2x.resolvePrecision(2, 0, 1, 2))
        } finally {
            input.recycle()
            Waifu2x.resetRealCugan()
        }
    }
}
