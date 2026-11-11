package eu.kanade.tachiyomi.ui.reader.spatial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DepthImagePreprocessorTest {

    @Test
    fun `large portrait source is sampled before decode`() {
        assertEquals(2, DepthImagePreprocessor.decodeSampleSize(3970, 5970))
    }

    @Test
    fun `large landscape source is sampled before decode`() {
        assertEquals(2, DepthImagePreprocessor.decodeSampleSize(5970, 3970))
    }

    @Test
    fun `normal source retains full decode resolution`() {
        assertEquals(1, DepthImagePreprocessor.decodeSampleSize(2000, 3000))
        assertEquals(1, DepthImagePreprocessor.decodeSampleSize(1280, 1920))
    }

    @Test
    fun `extreme strip remains within dimension budget`() {
        assertEquals(8, DepthImagePreprocessor.decodeSampleSize(1200, 20_000))
    }
}
