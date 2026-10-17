package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import android.widget.Scroller
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BookPageTurnRenderTest {
    @Test
    fun backgroundlessReaderPageDrawsItsTransformWhenRenderedByItsParent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val parent = FrameLayout(context)
            val page = ReaderPageImageView(context)
            page.background = null
            assertFalse("A backgroundless page must retain its custom drawing pass", page.willNotDraw())
            page.addView(View(context).apply { setBackgroundColor(Color.RED) }, FrameLayout.LayoutParams(360, 220))
            parent.addView(page, FrameLayout.LayoutParams(360, 220))
            val exactWidth = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
            val exactHeight = View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY)
            parent.measure(exactWidth, exactHeight)
            parent.layout(0, 0, 360, 220)
            val bitmap = Bitmap.createBitmap(360, 220, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.GRAY)
            page.bookPageTurn = bookPageTurn(0, 0, -0.75f, false, false, false)
            parent.draw(canvas)
            assertEquals("The outgoing page back face must be hidden", Color.GRAY, bitmap.getPixel(80, 100))
            page.bookPageTurn = null
            parent.draw(canvas)
            assertEquals("Normal page content must return after the turn", Color.RED, bitmap.getPixel(80, 100))
            bitmap.recycle()
        }
    }

    @Test
    fun enhancementIndicatorsStayInTheViewportWhileThePageTurns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val root = FrameLayout(context)
            val pager = Pager(context)
            root.addView(pager, FrameLayout.LayoutParams(360, 220))
            val page = object : ReaderPageImageView(context) {
                fun addIndicator(left: Int, color: Int) {
                    addEnhancementStatusView(
                        View(context).apply {
                            setBackgroundColor(color)
                            layoutParams = FrameLayout.LayoutParams(20, 20).apply {
                                leftMargin = left
                                topMargin = 180
                            }
                        },
                    )
                }
            }
            page.addView(View(context).apply { setBackgroundColor(Color.RED) })
            page.addIndicator(10, Color.BLUE)
            page.addIndicator(330, Color.GREEN)
            pager.addView(page)
            val width = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY)
            root.measure(width, height)
            root.layout(0, 0, 360, 220)
            page.measure(width, height)
            page.layout(0, 0, 360, 220)
            page.getChildAt(0).layout(0, 0, 360, 220)
            pager.bookTurnPage = { page }
            for (scroll in listOf(0, 360)) {
                pager.scrollTo(scroll, 0)
                page.layout(scroll, 0, scroll + 360, 220)
                for (spread in listOf(false, true)) {
                    for (progress in listOf(0.25f, 0.75f)) {
                        page.bookPageTurn = bookPageTurn(0, 0, -progress, false, spread, false)
                        val bitmap = Bitmap.createBitmap(360, 220, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        assertEquals(
                            "Left status must stay fixed at $progress/$scroll",
                            Color.BLUE,
                            bitmap.getPixel(20, 190),
                        )
                        assertEquals(
                            "Right status must stay fixed at $progress/$scroll",
                            Color.GREEN,
                            bitmap.getPixel(340, 190),
                        )
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun pagerUses440MillisAndRestoresItsOriginalScroller() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val pager = Pager(instrumentation.targetContext)
            val field = generateSequence(pager.javaClass as Class<*>?) { it.superclass }
                .mapNotNull { type -> runCatching { type.getDeclaredField("mScroller") }.getOrNull() }
                .first().apply { isAccessible = true }
            val original = field.get(pager)
            pager.setBookTurnTiming(true)
            val replacement = field.get(pager) as Scroller
            replacement.startScroll(0, 0, 300, 0, 200)
            assertEquals(440, replacement.duration)
            pager.setBookTurnTiming(false)
            assertSame(original, field.get(pager))
        }
    }

    @Test
    fun nativeCanvasKeepsStationaryPagesVisibleInBothDirections() {
        for (step in listOf(-1, 1)) {
            val early = render(step, 0.25f)
            val late = render(step, 0.75f)
            if (step > 0) {
                assertEquals(OLD_LEFT, early.getPixel(20, 100))
                assertEquals(NEW_RIGHT, early.getPixel(340, 100))
                assertEquals(NEW_RIGHT, late.getPixel(340, 100))
            } else {
                assertEquals(NEW_LEFT, early.getPixel(20, 100))
                assertEquals(OLD_RIGHT, early.getPixel(340, 100))
                assertEquals(NEW_LEFT, late.getPixel(20, 100))
            }
            assertTrue(early.sameAs(late).not())
            early.recycle()
            late.recycle()
        }
        val sheet = Bitmap.createBitmap(1080, 810, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.DKGRAY)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
        }
        val frames = listOf(0.01f, 0.25f, 0.49f, 0.51f, 0.75f, 0.99f)
        frames.forEachIndexed { i, progress ->
            val image = render(1, progress)
            val x = (i % 3) * 360f
            val y = (i / 3) * 270f
            canvas.drawBitmap(image, x, y + 35f, null)
            canvas.drawText("Forward $progress", x + 10, y + 25, label)
            image.recycle()
        }
        listOf(-1, -1, 1).forEachIndexed { i, step ->
            val progress = if (i == 0) 0.25f else 0.75f
            val image = render(step, progress, spread = i != 2)
            canvas.drawBitmap(image, i * 360f, 575f, null)
            canvas.drawText(if (i == 2) "Single page" else "Backward $progress", i * 360f + 10, 565f, label)
            image.recycle()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "book-page-turn-preview.png").outputStream().use {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        sheet.recycle()
    }

    private fun render(step: Int, progress: Float, spread: Boolean = true): Bitmap {
        val bitmap = Bitmap.createBitmap(360, 220, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.DKGRAY)
        val renderer = BookPageTurnDrawing()
        val frames = listOf(0, step).map { index ->
            bookPageTurn(index, 0, index.toFloat() - step * progress, false, spread, false)!!
        }.sortedBy { it.elevation }
        for (turn in frames) {
            renderer.draw(canvas, 360, 220, turn) { page ->
                val paint = Paint().apply { color = if (turn.current) OLD_LEFT else NEW_LEFT }
                page.drawRect(0f, 0f, 180f, 220f, paint)
                paint.color = if (turn.current) OLD_RIGHT else NEW_RIGHT
                page.drawRect(180f, 0f, 360f, 220f, paint)
                paint.color = Color.WHITE
                paint.textSize = 30f
                page.drawText(if (turn.current) "A" else "C", 65f, 110f, paint)
                page.drawText(if (turn.current) "B" else "D", 245f, 110f, paint)
            }
        }
        return bitmap
    }

    companion object {
        private val OLD_LEFT = Color.rgb(160, 45, 55)
        private val OLD_RIGHT = Color.rgb(35, 70, 160)
        private val NEW_LEFT = Color.rgb(35, 125, 85)
        private val NEW_RIGHT = Color.rgb(190, 130, 25)
    }
}
