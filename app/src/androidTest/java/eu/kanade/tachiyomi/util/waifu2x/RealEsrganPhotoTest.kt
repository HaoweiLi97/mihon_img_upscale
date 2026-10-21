package eu.kanade.tachiyomi.util.waifu2x

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RealEsrganPhotoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun photoRefreshesOldModelAndProducesColorAcrossTilesAndPrecisions() {
        val directory = File(context.cacheDir, "realesrgan-models/v3-general").apply { mkdirs() }
        // Reproduce an installation that has already extracted the broken graph.
        File(directory, ".bundled-model-version").writeText("16")
        File(directory, "x4.param").writeText("stale model")
        val input = gradient()
        try {
            for ((precision, arithmetic) in listOf(0 to false, 1 to false, 0 to true)) {
                Waifu2x.resetRealCugan()
                assertTrue(initialize(precision, arithmetic))
                assertTrue(File(directory, "x4.param").readText().contains("1=4.0 2=4.0"))
                val output = Waifu2x.processRealESRGAN(input)
                assertNotNull("Photo precision=$precision arithmetic=$arithmetic failed", output)
                try {
                    requireNotNull(output)
                    assertEquals(146, output.width)
                    assertEquals(102, output.height)
                    val pixels = IntArray(output.width * output.height)
                    output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
                    val mean = pixels.sumOf {
                        (Color.red(it) + Color.green(it) + Color.blue(it)).toLong()
                    }.toDouble() / (pixels.size * 3)
                    assertTrue("Output is black or clipped: $mean", mean in 80.0..180.0)
                    assertTrue("Output lost image detail", pixels.toSet().size > 100)
                    assertTrue("Output lost opacity", pixels.all { Color.alpha(it) == 255 })
                    val center = output.getPixel(output.width / 2, output.height / 2)
                    assertTrue("RGB channels are reversed", Color.blue(center) > Color.red(center))
                    var error = 0L
                    var samples = 0
                    for (y in 4 until output.height - 4) {
                        for (x in 4 until output.width - 4) {
                            val actual = output.getPixel(x, y)
                            val source = input.getPixel(x / 2, y / 2)
                            error += kotlin.math.abs(Color.red(actual) - Color.red(source))
                            error += kotlin.math.abs(Color.green(actual) - Color.green(source))
                            error += kotlin.math.abs(Color.blue(actual) - Color.blue(source))
                            samples += 3
                        }
                    }
                    val meanError = error.toDouble() / samples
                    assertTrue("Gradient or tile seams are damaged: MAE=$meanError", meanError < 12)
                    val file = File(context.getExternalFilesDir(null), "photo-ncnn-$precision-$arithmetic.png")
                    file.outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Log.i("RealEsrganPhotoTest", "precision=$precision arithmetic=$arithmetic mean=$mean MAE=$meanError saved=$file")
                } finally {
                    output?.recycle()
                }
            }
        } finally {
            input.recycle()
            Waifu2x.resetRealCugan()
        }
    }

    @Test
    fun failedResizeReturnsNullInsteadOfCachingABlackImage() {
        val input = gradient()
        val file = File(context.cacheDir, "realesrgan-models/v3-general/x4.param")
        var original: ByteArray? = null
        try {
            Waifu2x.resetRealCugan()
            assertTrue(initialize(0, false))
            original = file.readBytes()
            Waifu2x.resetRealCugan()
            file.writeText(original.toString(Charsets.UTF_8).replace("1=4.0 2=4.0", "1=4 2=4"))
            assertTrue(initialize(0, false))
            val output = Waifu2x.processRealESRGAN(input)
            try {
                assertNull("Failed inference must not return a displayable black bitmap", output)
            } finally {
                output?.recycle()
            }
        } finally {
            Waifu2x.resetRealCugan()
            original?.let(file::writeBytes)
            input.recycle()
        }
    }

    private fun initialize(precision: Int, arithmetic: Boolean) = Waifu2x.initRealESRGAN(
        context = context,
        scale = 2,
        style = Waifu2x.REAL_ESRGAN_STYLE_PHOTO,
        tileSize = 32,
        precision = precision,
        fp16Arithmetic = arithmetic,
        processingBackend = Waifu2x.PROCESSING_BACKEND_VULKAN,
    )

    private fun gradient() = Bitmap.createBitmap(73, 51, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) {
            for (x in 0 until width) {
                setPixel(x, y, Color.rgb(40 + x * 2, 40 + y * 3, 70 + x + y))
            }
        }
    }
}
