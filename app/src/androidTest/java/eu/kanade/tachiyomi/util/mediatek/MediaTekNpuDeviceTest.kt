package eu.kanade.tachiyomi.util.mediatek

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTekNpuDeviceTest {
    private fun selectBackend(backend: Int) {
        Waifu2x::class.java.getDeclaredMethod("nativeSetProcessingBackend", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(Waifu2x, backend)
    }

    @Test
    fun unavailableNpuKeepsChoiceAndSkipsEnhancement() {
        assumeFalse(MediaTekNpu.isDeviceSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        try {
            assertEquals(2, Waifu2x.resolveProcessingBackend(2, 1, 2))
            assertFalse(Waifu2x.initRealCugan(context, 3, 2, isPro = true, processingBackend = 2))
            assertFalse(MediaTekNpu.isActive())
            assertNull("Failed NPU must not execute Vulkan", Waifu2x.processRealCugan(input))
            assertEquals(0, Waifu2x.resolvePrecision(1, 2, 1, 2))
        } finally {
            input.recycle()
            Waifu2x.resetRealCugan()
        }
    }

    @Test
    fun failedQnnLoadDoesNotRunAnAvailableVulkanModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(Waifu2x.initRealCugan(context, 3, 2, isPro = true, processingBackend = 0))
            val method = Waifu2x::class.java.getDeclaredMethod(
                "nativeInitQnn",
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            assertFalse(
                method.invoke(Waifu2x, "/missing/context.bin", context.applicationInfo.nativeLibraryDir, 18) as Boolean,
            )
            selectBackend(1)
            assertNull("QNN load failure must not execute the loaded Vulkan model", Waifu2x.processRealCugan(input))
        } finally {
            input.recycle()
            Waifu2x.resetRealCugan()
        }
    }

    @Test
    fun allLiteRtModelsExecuteThroughAndroidCAbiAndTiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest =
            JSONObject(context.assets.open("litert-models/manifest.json").bufferedReader().use { it.readText() })
        assertEquals(15, manifest.length())
        val input = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setPixel(x, y, Color.argb(128 + (x % 128), x * 7, y * 5, (x + y) * 3))
                }
            }
        }
        try {
            for (name in manifest.keys().asSequence().sorted()) {
                val scale = if ("-x3-" in name) 3 else 2
                val isPro = name.startsWith("realcugan-pro")
                // NCNN supplies the common output metadata. Execution is explicitly switched to LiteRT below.
                assertTrue(Waifu2x.initRealCugan(context, 3, scale, isPro = isPro))
                val file = MediaTekNpu.prepareForCpuValidation(context, name)
                assertNotNull("Android LiteRT C ABI failed: $name", file)
                assertFalse("CPU validation must never be reported as NPU", MediaTekNpu.isActive())
                selectBackend(2)
                val output = Waifu2x.processRealCugan(input)
                assertNotNull("LiteRT tile inference failed: $name", output)
                try {
                    assertEquals(input.width * scale, output!!.width)
                    assertEquals(input.height * scale, output.height)
                    val colors = IntArray(output.width * output.height)
                    output.getPixels(colors, 0, output.width, 0, 0, output.width, output.height)
                    assertTrue("Constant output: $name", colors.toSet().size > 10)
                    assertEquals(Color.alpha(input.getPixel(4, 7)), Color.alpha(output.getPixel(4 * scale, 7 * scale)))
                    Log.i("MediaTekNpuDeviceTest", "Android C ABI + tiles passed (CPU validation): $name")
                } finally {
                    output?.recycle()
                }
            }
        } finally {
            input.recycle()
            Waifu2x.resetRealCugan()
        }
    }
}
