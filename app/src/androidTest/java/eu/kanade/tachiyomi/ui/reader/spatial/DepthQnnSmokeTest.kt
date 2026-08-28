package eu.kanade.tachiyomi.ui.reader.spatial

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DepthQnnSmokeTest {
    @Test
    fun depthAnythingV3RunsOnQnn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = DepthSpatialModel(context)
        assertTrue("Depth Anything V3 DLC is not installed", model.isReady)
        val size = DepthImagePreprocessor.IMAGE_SIZE
        val input = FloatArray(size * size * 3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val base = (y * size + x) * 3
                input[base] = x.toFloat() / (size - 1)
                input[base + 1] = y.toFloat() / (size - 1)
                input[base + 2] = (x + y).toFloat() / (2 * size - 2)
            }
        }
        val compiledContext = model.contextFile().apply { parentFile?.mkdirs() }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val output = DepthQnnBridge.inferDepth(
            input = input,
            modelPath = model.dlcFile.absolutePath,
            contextPath = compiledContext.absolutePath,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        )
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
        assertNotNull(DepthQnnBridge.lastError(), output)
        assertEquals(size * size, output!!.size)
        assertTrue("Depth output contains non-finite values", output.all(Float::isFinite))
        Log.i("DepthQnnSmokeTest", "DA3 QNN compile/inference completed in ${elapsedMs}ms")
    }
}
