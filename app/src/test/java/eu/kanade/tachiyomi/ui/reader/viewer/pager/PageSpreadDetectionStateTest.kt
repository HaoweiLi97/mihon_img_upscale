package eu.kanade.tachiyomi.ui.reader.viewer.pager

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageSpreadDetectionStateTest {

    @Test
    fun `deferred candidates are not cached as completed`() {
        val state = PageSpreadDetectionState<String>()

        assertTrue(state.begin("half-1:half-2"))
        state.defer("half-1:half-2")

        assertTrue(state.begin("half-1:half-2"))
    }

    @Test
    fun `completed rejection is cached until configuration resets`() {
        val state = PageSpreadDetectionState<String>()

        assertTrue(state.begin("page-1:page-2"))
        assertFalse(state.complete("page-1:page-2", isSpread = false))
        assertFalse(state.begin("page-1:page-2"))

        state.reset()

        assertTrue(state.begin("page-1:page-2"))
    }

    @Test
    fun `completed detection is retained for layout rebuilding`() {
        val state = PageSpreadDetectionState<String>()

        assertTrue(state.begin("right-half:left-half"))
        assertTrue(state.complete("right-half:left-half", isSpread = true))

        assertTrue(state.isDetected("right-half:left-half"))
        assertFalse(state.complete("right-half:left-half", isSpread = true))
    }
}
