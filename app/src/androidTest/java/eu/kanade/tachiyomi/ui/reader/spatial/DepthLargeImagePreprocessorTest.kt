package eu.kanade.tachiyomi.ui.reader.spatial

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DepthLargeImagePreprocessorTest {

    @Test
    fun cachedLargePageIsSampledBeforeSpatialPreparation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cachedSource = File(context.cacheDir, "chapter_disk_cache")
            .listFiles()
            ?.filter { it.isFile && it.length() >= 10L * 1024L * 1024L }
            ?.maxByOrNull(File::length)
        assumeNotNull(cachedSource)
        val source = checkNotNull(cachedSource)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        assertTrue(bounds.outWidth.toLong() * bounds.outHeight > 8_000_000L)

        val result = source.inputStream().use(DepthImagePreprocessor::prepare)

        assertTrue(result.originalWidth <= 4096)
        assertTrue(result.originalHeight <= 4096)
        assertTrue(result.originalWidth.toLong() * result.originalHeight <= 8_000_000L)
        assertEquals(DepthImagePreprocessor.IMAGE_SIZE * DepthImagePreprocessor.IMAGE_SIZE * 3, result.input.size)
        assertTrue(maxOf(result.renderWidth, result.renderHeight) <= 1536)
        Log.i(
            "DepthLargeImageTest",
            "Prepared ${bounds.outWidth}x${bounds.outHeight} as " +
                "${result.originalWidth}x${result.originalHeight}, render=" +
                "${result.renderWidth}x${result.renderHeight}",
        )
    }
}
