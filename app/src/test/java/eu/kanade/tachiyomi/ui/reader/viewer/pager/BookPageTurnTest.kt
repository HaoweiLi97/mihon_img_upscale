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
