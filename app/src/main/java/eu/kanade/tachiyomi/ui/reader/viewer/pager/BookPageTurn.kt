package eu.kanade.tachiyomi.ui.reader.viewer.pager

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// Geometry and timing follow ScrapeFun's comic-reader/bookPageTurn.ts.
internal const val BOOK_PAGE_TURN_MILLIS = 440

internal data class BookPageTurn(
    val progress: Float,
    val current: Boolean,
    val step: Int,
    val forward: Boolean,
    val bindingAtStart: Boolean,
    val spread: Boolean,
    val vertical: Boolean,
    val layoutTransition: Boolean = false,
    val bounds: BookPageBounds? = null,
    val hingeShift: Float = 0f,
    val crossShift: Float = 0f,
    val targetScale: Float = 1f,
) {
    val elevation: Float
        get() = if (current) {
            2f
        } else if (progress >= 0.5f || (!spread && !layoutTransition && !forward)) {
            3f
        } else {
            1f
        }

    fun leaf(index: Int): BookLeaf {
        if (!spread && !layoutTransition) {
            val turning = current == forward
            val angle = (if (bindingAtStart) -180f else 180f) *
                (if (forward) progress else 1f - progress)
            return BookLeaf(turning, angle, bindingAtStart)
        }
        val outgoingIndex = if (step > 0) 1 else 0
        val side = if (spread) {
            index
        } else if (current) {
            outgoingIndex
        } else {
            1 - outgoingIndex
        }
        val turning = !spread || if (current) side == outgoingIndex else side != outgoingIndex
        val angle = if (current) -step * 180f * progress else step * 180f * (1f - progress)
        return BookLeaf(turning, angle, side == 1)
    }
}

internal data class BookLeaf(val turning: Boolean, val degrees: Float, val hingeAtStart: Boolean) {
    val visible: Boolean get() = !turning || abs(degrees) < 90f
}

internal fun bookPageTurn(
    pageIndex: Int,
    startIndex: Int,
    position: Float,
    rtl: Boolean,
    spread: Boolean,
    vertical: Boolean,
): BookPageTurn? {
    val offset = pageIndex - startIndex - position
    if (!offset.isFinite() || abs(offset) < 0.0001f || abs(offset) > 1.001f) return null
    val step = if (offset > 0f) 1 else -1
    if (pageIndex != startIndex && pageIndex != startIndex + step) return null
    return BookPageTurn(
        progress = abs(offset).coerceIn(0f, 1f),
        current = pageIndex == startIndex,
        step = step,
        forward = step == if (rtl && !vertical) -1 else 1,
        bindingAtStart = !rtl || vertical,
        spread = spread && !vertical,
        vertical = vertical,
    )
}

internal data class BookPageBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Keeps the turn anchored to the visible pair when pages are restored, regrouped or skipped quickly. */
internal class BookPageTurnState {
    var startIndex: Int = 0
        private set
    private var scrollPosition = 0f

    fun restore(index: Int) {
        startIndex = index
        scrollPosition = index.toFloat()
    }

    fun onScroll(position: Int, offset: Float) {
        val next = position + offset
        if (!next.isFinite()) return
        if (abs(next - startIndex) > 1.001f) {
            startIndex = if (next > startIndex) floor(next).toInt() else ceil(next).toInt()
        }
        scrollPosition = next
    }

    fun frame(pageIndex: Int, rtl: Boolean, spread: Boolean, vertical: Boolean): BookPageTurn? =
        bookPageTurn(pageIndex, startIndex, pageIndex - scrollPosition, rtl, spread, vertical)
}
