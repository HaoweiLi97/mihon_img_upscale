package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BookPagePictureBoundsTest {
    @Test
    fun backgroundAndSpreadPaddingStayStillBehindBothPictureLayers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val root = FrameLayout(context)
            val pager = Pager(context)
            root.addView(pager, FrameLayout.LayoutParams(360, 220))
            for (spread in listOf(false, true)) {
                pager.removeAllViews()
                val pictures = if (spread) {
                    listOf(BookPageBounds(50f, 30f, 170f, 190f), BookPageBounds(190f, 50f, 310f, 170f))
                } else {
                    listOf(BookPageBounds(70f, 30f, 290f, 190f))
                }
                fun picturePage(color: Int, backgroundColor: Int): ReaderPageImageView =
                    object : ReaderPageImageView(context) {
                        override fun bookPageImageBounds() = pictures
                    }.apply {
                        setBackgroundColor(backgroundColor)
                        addView(
                            object : View(context) {
                                override fun onDraw(canvas: Canvas) {
                                    canvas.drawColor(Color.BLACK) // The old composed image's layout matte.
                                    val paint = Paint().apply { this.color = color }
                                    pictures.forEach { canvas.drawRect(it.left, it.top, it.right, it.bottom, paint) }
                                }
                            },
                            FrameLayout.LayoutParams(360, 220),
                        )
                    }
                val current = picturePage(Color.RED, Color.CYAN)
                val next = picturePage(Color.BLUE, Color.GREEN)
                pager.addView(current)
                pager.addView(next)
                pager.bookTurnPage = { current }
                layout(root)
                for (page in listOf(current, next)) {
                    page.measure(exact(360), exact(220))
                    page.layout(0, 0, 360, 220)
                }
                for (vertical in listOf(false, true)) {
                    for (step in listOf(-1, 1)) {
                        for (progress in listOf(0.25f, 0.75f)) {
                            current.bookPageTurn = bookPageTurn(0, 0, -step * progress, false, spread, vertical)
                            next.bookPageTurn = bookPageTurn(step, 0, step - step * progress, false, spread, vertical)
                            current.translationZ = current.bookPageTurn!!.elevation
                            next.translationZ = next.bookPageTurn!!.elevation
                            val bitmap = Bitmap.createBitmap(360, 220, Bitmap.Config.ARGB_8888)
                            root.draw(Canvas(bitmap))
                            for ((x, y) in listOf(5 to 5, 350 to 5, 5 to 210, 350 to 210)) {
                                assertEquals(
                                    "Only pictures should turn: $spread/$vertical/$step/$progress",
                                    Color.CYAN,
                                    bitmap.getPixel(x, y),
                                )
                            }
                            if (spread && !vertical && step == 1 && progress < 0.5f) {
                                assertEquals(
                                    "Incoming background must not cover the fixed old picture",
                                    Color.RED,
                                    bitmap.getPixel(80, 100),
                                )
                                assertEquals(Color.BLUE, bitmap.getPixel(300, 100))
                            }
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun coverAndSpreadTransitionsKeepTheOuterMatteOutOfTheTurningFaces() {
        val cover = listOf(BookPageBounds(100f, 20f, 260f, 200f))
        val spread = listOf(BookPageBounds(40f, 30f, 170f, 190f), BookPageBounds(190f, 30f, 320f, 190f))
        for (reverse in listOf(false, true)) {
            val from = if (reverse) spread else cover
            val to = if (reverse) cover else spread
            for (step in listOf(-1, 1)) {
                for (progress in listOf(0.25f, 0.75f)) {
                    val bitmap = Bitmap.createBitmap(360, 220, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.CYAN)
                    val renderer = BookPageTurnDrawing()
                    val frames = listOf(0 to from, step to to).map { (index, pictures) ->
                        bookPageTurn(index, 0, index - step * progress, false, pictures.size == 2, false)!!.copy(
                            layoutTransition = true,
                            imageBounds = pictures,
                            hingeShift = if (reverse) -80f else 80f,
                        )
                    }.sortedBy { it.elevation }
                    frames.forEach { turn ->
                        renderer.draw(canvas, 360, 220, turn) {
                            it.drawColor(Color.MAGENTA)
                            val paint = Paint().apply { color = Color.RED }
                            turn.imageBounds!!.forEach { picture ->
                                it.drawRect(picture.left, picture.top, picture.right, picture.bottom, paint)
                            }
                        }
                    }
                    for ((x, y) in listOf(5 to 5, 350 to 5, 5 to 210, 350 to 210)) {
                        assertEquals(Color.CYAN, bitmap.getPixel(x, y))
                    }
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun composedPictureBoundsSurviveNativeDecodeAndBorderCropSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        lateinit var root: FrameLayout
        lateinit var page: ReaderPageImageView
        val pictures = listOf(BookPageBounds(50f, 30f, 170f, 190f), BookPageBounds(190f, 50f, 310f, 170f))
        instrumentation.runOnMainSync {
            root = FrameLayout(instrumentation.targetContext)
            page = ReaderPageImageView(instrumentation.targetContext)
            root.addView(page, FrameLayout.LayoutParams(360, 220))
            val source = Bitmap.createBitmap(360, 220, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(source)
            canvas.drawColor(Color.BLACK)
            val paint = Paint().apply { color = Color.RED }
            pictures.forEach { canvas.drawRect(it.left, it.top, it.right, it.bottom, paint) }
            val bytes = Buffer()
            source.compress(Bitmap.CompressFormat.PNG, 100, bytes.outputStream())
            source.recycle()
            page.onImageLoaded = { ready.countDown() }
            page.setImage(
                BookPageImageSource(bytes, pictures),
                false,
                ReaderPageImageView.Config(zoomDuration = 0, cropBorders = true),
            )
            layout(root)
        }
        val frame = Bitmap.createBitmap(360, 220, Bitmap.Config.ARGB_8888)
        repeat(50) {
            if (ready.count == 0L) return@repeat
            instrumentation.runOnMainSync { root.draw(Canvas(frame)) }
            ready.await(100, TimeUnit.MILLISECONDS)
        }
        assertTrue("Composed source should finish native decoding", ready.count == 0L)
        instrumentation.runOnMainSync {
            val actual = page.bookPageImageBounds()
            assertEquals(2, actual.size)
            pictures.zip(actual).forEach { (expected, observed) ->
                assertEquals(expected.left, observed.left, 1f)
                assertEquals(expected.top, observed.top, 1f)
                assertEquals(expected.right, observed.right, 1f)
                assertEquals(expected.bottom, observed.bottom, 1f)
            }
        }
        frame.recycle()
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun layout(view: View) {
        view.measure(exact(360), exact(220))
        view.layout(0, 0, 360, 220)
    }
}
