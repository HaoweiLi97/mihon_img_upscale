package eu.kanade.tachiyomi.ui.reader.viewer.pager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookPageTurnTest {
    private fun turn(current: Boolean, step: Int, progress: Float, rtl: Boolean = false, spread: Boolean = true) =
        bookPageTurn(
            if (current) 3 else 3 + step,
            3,
            (if (current) 0 else step).toFloat() - step * progress,
            rtl,
            spread,
            false,
        )!!

    @Test
    fun `restored chapter index and backwards first page keep the book animation`() {
        val state = BookPageTurnState()
        state.restore(93)
        state.onScroll(92, 0.7f)
        val forward = state.frame(93, rtl = true, spread = false, vertical = false)!!
        assertTrue(forward.forward)
        assertEquals(0.3f, forward.progress, 0.001f)
        state.restore(92)
        state.onScroll(92, 0.3f)
        val backward = state.frame(93, rtl = true, spread = false, vertical = false)!!
        assertFalse(backward.forward)
        assertEquals(0.3f, backward.progress, 0.001f)
    }

    @Test
    fun `rapid turns rebase onto the visible pair instead of falling back to sliding`() {
        val state = BookPageTurnState()
        state.restore(0)
        state.onScroll(0, 0.8f)
        assertTrue(state.frame(0, false, true, false) != null)
        state.onScroll(1, 0.2f)
        assertEquals(1, state.startIndex)
        assertTrue(state.frame(1, false, true, false) != null)
        assertTrue(state.frame(2, false, true, false) != null)
        assertEquals(0.2f, state.frame(1, false, true, false)!!.progress, 0.001f)
    }

    @Test
    fun `late first layout and regrouped page indices repair an old animation anchor`() {
        val state = BookPageTurnState()
        state.onScroll(93, 0f)
        state.onScroll(92, 0.5f)
        assertTrue(state.frame(93, true, false, false) != null)
        state.restore(47)
        state.onScroll(93, 0.5f)
        assertTrue(state.frame(93, false, true, false) != null)
        assertTrue(state.frame(94, false, true, false) != null)
    }

    @Test
    fun `reversing a drag unfolds the same leaf without changing anchors`() {
        val state = BookPageTurnState()
        state.restore(0)
        state.onScroll(0, 0.7f)
        val first = state.frame(0, false, true, false)!!
        state.onScroll(0, 0.3f)
        val reversed = state.frame(0, false, true, false)!!
        assertEquals(first.step, reversed.step)
        assertEquals(0, state.startIndex)
        assertTrue(kotlin.math.abs(reversed.leaf(1).degrees) < kotlin.math.abs(first.leaf(1).degrees))
    }

    @Test
    fun `both directions exchange readable faces at the spine without mirroring`() {
        for (step in listOf(-1, 1)) {
            val outgoing = if (step > 0) 1 else 0
            val incoming = 1 - outgoing
            val first = turn(true, step, 0.25f)
            val next = turn(false, step, 0.25f)
            assertTrue(first.leaf(outgoing).turning)
            assertTrue(first.leaf(outgoing).visible)
            assertFalse(next.leaf(incoming).visible)
            assertTrue(first.elevation > next.elevation)
            val later = turn(false, step, 0.75f)
            assertTrue(later.leaf(incoming).visible)
            assertFalse(turn(true, step, 0.75f).leaf(outgoing).visible)
            assertTrue(later.elevation > first.elevation)
            assertEquals(outgoing == 1, first.leaf(outgoing).hingeAtStart)
        }
    }

    @Test
    fun `single page keeps one binding edge for forward and backward navigation`() {
        for (rtl in listOf(false, true)) {
            val step = if (rtl) -1 else 1
            val forward = turn(true, step, 0.25f, rtl, false)
            val backward = turn(false, -step, 0.75f, rtl, false)
            assertTrue(forward.leaf(0).turning)
            assertTrue(backward.leaf(0).turning)
            assertEquals(!rtl, forward.leaf(0).hingeAtStart)
            assertEquals(forward.leaf(0), backward.leaf(0))
        }
    }

    @Test
    fun `idle cancelled invalid and nonadjacent frames leave the normal page intact`() {
        for (position in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY, -2f)) {
            assertNull(bookPageTurn(3, 3, position, false, true, false))
        }
        assertNull(bookPageTurn(8, 3, 4.5f, false, true, false))
        val drag = turn(true, 1, 0.2f)
        assertEquals(0.2f, drag.progress, 0.0001f)
        assertEquals(440, BOOK_PAGE_TURN_MILLIS)
    }

    @Test
    fun `vertical reading turns around the top binding independently of rtl`() {
        val frame = bookPageTurn(3, 3, -0.4f, true, true, true)!!
        assertFalse(frame.spread)
        assertTrue(frame.forward)
        assertTrue(frame.bindingAtStart)
        assertTrue(frame.vertical)
    }
}
