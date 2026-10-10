package eu.kanade.tachiyomi.util.waifu2x

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeModelStartupTest {

    @Test
    fun switchingModelWeightsAndPrecisionKeepsNativeProcessingAlive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setPixel(x, y, Color.rgb(x * 7, y * 5, (x + y) * 3))
                }
            }
        }
        try {
            repeat(2) {
                for (isPro in listOf(false, true)) {
                    for (precision in listOf(0, 1)) {
                        assertTrue(
                            Waifu2x.initRealCugan(
                                context = context,
                                noiseLevel = 3,
                                scale = 2,
                                isPro = isPro,
                                precision = precision,
                                processingBackend = Waifu2x.PROCESSING_BACKEND_VULKAN,
                            ),
                        )
                        val output = Waifu2x.processRealCugan(input)
                        assertNotNull("Native model must produce an image after switching", output)
                        try {
                            assertEquals(64, output!!.width)
                            assertEquals(96, output.height)
                            assertTrue(ImageEnhancementCache.isDisplayable(output))
                        } finally {
                            output?.recycle()
                        }
                    }
                }
            }
        } finally {
            input.recycle()
            Waifu2x.resetRealCugan()
        }
    }
}
