package eu.kanade.tachiyomi.util.qnn

import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalQuantizationPrecisionTest {
    @Test
    fun `local W8A16 is preserved only for the Qualcomm backend`() {
        assertEquals(4, Waifu2x.resolvePrecision(4, 1, 0, 2))
        assertEquals(0, Waifu2x.resolvePrecision(4, 0, 0, 2))
        assertEquals(0, Waifu2x.resolvePrecision(4, 2, 0, 2))
        assertEquals(0, Waifu2x.resolvePrecision(2, 1, 0, 2))
    }

    @Test
    fun `calibration dataset follows model input domain`() {
        assertEquals("manga", QnnCalibrationPacks.domain("realcugan-pro-x3-denoise3x"))
        assertEquals("manga", QnnCalibrationPacks.domain("realesrgan-animevideov3-x2"))
        assertEquals("photo", QnnCalibrationPacks.domain("span-nomosuni-x2"))
        assertEquals("photo", QnnCalibrationPacks.domain("realesrgan-general-x4v3-x2"))
        assertEquals("photo", QnnCalibrationPacks.domain("w2xex-photo-small-x2"))
    }

    @Test
    fun `precision preparation states are separate`() {
        assertEquals("model", QnnModelContexts.stateKey("model", 0))
        assertEquals("model:w8a16", QnnModelContexts.stateKey("model", 4))
    }
}
