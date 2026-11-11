package eu.kanade.tachiyomi.util.qnn

import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import io.mockk.every
import io.mockk.mockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QnnPrecisionPolicyTest {
    @Test
    fun `risky models retain FP16 without an INT8 asset or option`() {
        val models = buildList {
            add("realesrgan-general-x4v3-x2")
            add("span-nomosuni-x2")
            add("w2xex-photo-small-x2")
            for (noise in listOf("no-denoise", "denoise1x", "denoise2x", "denoise3x", "conservative")) {
                add("realcugan-se-x2-$noise")
            }
            for (scale in listOf(2, 3)) {
                for (noise in listOf("no-denoise", "denoise3x", "conservative")) {
                    add("realcugan-pro-x$scale-$noise")
                }
            }
        }
        assertEquals(14, models.size)
        for (model in models) {
            assertEquals(listOf(0), QnnModelContexts.supportedPrecisions(model))
            assertEquals(model, QnnModelContexts.assetName(model, 0))
            assertThrows(IllegalArgumentException::class.java) { QnnModelContexts.assetName(model, 2) }
        }
        assertEquals(listOf(0), QnnModelContexts.supportedPrecisions(null))
        assertEquals(listOf(0), QnnModelContexts.supportedPrecisions("unknown"))
    }

    @Test
    fun `Anime exposes a separate W8A8 asset and preparation state`() {
        val model = "realesrgan-animevideov3-x2"
        assertEquals(listOf(0, 2), QnnModelContexts.supportedPrecisions(model))
        assertEquals("$model-w8a8", QnnModelContexts.assetName(model, 2))
        assertNotEquals(QnnModelContexts.stateKey(model, 0), QnnModelContexts.stateKey(model, 2))
    }

    @Test
    fun `model style and backend gate INT8 including retired preferences`() {
        assertEquals(2, Waifu2x.resolvePrecision(2, 1, 2, 2, 0))
        assertEquals(2, Waifu2x.resolvePrecision(4, 1, 2, 2, 0))
        for (precision in listOf(2, 4)) {
            assertEquals(0, Waifu2x.resolvePrecision(precision, 1, 2, 2, 1))
            assertEquals(0, Waifu2x.resolvePrecision(precision, 1, 2, 3, 0))
            for (model in listOf(0, 1, 9, 19)) {
                assertEquals(0, Waifu2x.resolvePrecision(precision, 1, model, 2))
            }
            assertEquals(0, Waifu2x.resolvePrecision(precision, 0, 2, 2))
            assertEquals(0, Waifu2x.resolvePrecision(precision, 2, 2, 2))
        }
        assertEquals(1, Waifu2x.resolvePrecision(1, 0, 2, 2))
        assertEquals(3, Waifu2x.resolvePrecision(3, 0, 2, 2))
    }

    @Test
    fun `image cache distinguishes Anime INT8 but normalizes Photo to FP16`() {
        assertNotEquals(hash(0, 0), hash(0, 2))
        assertEquals(hash(0, 2), hash(0, 4))
        assertEquals(hash(1, 0), hash(1, 2))
        assertEquals(hash(1, 0), hash(1, 4))
    }

    @Test
    fun `reader still accepts the saved page after cold QNN fallback`() {
        val fp16Supported = MutableStateFlow<Boolean?>(null)
        mockkObject(QnnModelContexts) {
            every { QnnModelContexts.fp16Supported } returns fp16Supported

            val saveKey = ImageEnhancementCache.CacheKey(1L, 2L, 0, hash(0, 0), "")
            val saved = ImageEnhancementCache.CacheEvent.Saved(saveKey)
            fp16Supported.value = false

            assertTrue(saved.affects(1L, 2L, 0, hash(0, 0), ""))
        }
    }

    @Test
    fun `QNN Anime drops legacy INT8 results and legacy FP16 skip markers`() {
        val legacyKey = "0x2_m2_rs0_w0_h0_sw0_sh0_t128_p%s_fa0_b1_qv22_nf1_dv1_nv3_ink0"
        assertNotEquals(legacyKey.format(2), hash(0, 2))
        assertNotEquals(legacyKey.format(0), hash(0, 0))
        assertEquals("0x2_m2_rs1_w0_h0_sw0_sh0_t128_p0_fa0_b1_qv22_nf1_dv1_nv3_ink0", hash(1, 0))
    }

    private fun hash(style: Int, precision: Int) = ImageEnhancementCache.getConfigHash(
        noise = 0,
        scale = 2,
        model = Waifu2x.MODEL_REAL_ESRGAN_ANIME,
        realEsrganStyle = style,
        precision = precision,
        processingBackend = Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU,
    )
}
