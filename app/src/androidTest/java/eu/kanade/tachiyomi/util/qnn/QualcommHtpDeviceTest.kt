package eu.kanade.tachiyomi.util.qnn

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualcommHtpDeviceTest {
    @Test
    fun detectsArchitectureWithoutSocModelAndPackagesMatchingContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val architecture = QualcommHtp.architecture()

        assertNotNull("No supported HTP architecture was detected", architecture)
        context.assets.open("qnn-contexts/realesrgan-animevideov3-x2.v$architecture.bin").use { input ->
            assertTrue(input.available() > 0)
        }
        Log.i("QualcommHtpDeviceTest", "Detected and verified packaged HTP v$architecture context")
    }

    @Test
    fun initializesEnhancementFromVersionedContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertTrue(
            Waifu2x.initRealESRGAN(
                context = context,
                scale = 2,
                processingBackend = Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU,
            ),
        )
        assertTrue("Versioned QNN enhancement context did not initialize", Waifu2x.isQnnActive())
    }
}
