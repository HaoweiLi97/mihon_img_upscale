package eu.kanade.tachiyomi.ui.reader.viewer.pager

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sin

class PageSpreadScorerTest {

    @Test
    fun `recognizes two exact halves of one image`() {
        val (left, right) = split(createTexturedImage(seed = 3))

        val result = PageSpreadScorer.score(left, right, isR2L = false)

        assertTrue(result.isSpread, result.toString())
    }

    @Test
    fun `recognizes a small vertical offset and color shift`() {
        val (left, right) = split(createTexturedImage(seed = 7))
        val shiftedAndTintedRight = transform(right, verticalOffset = 6, colorShift = 7)

        val result = PageSpreadScorer.score(left, shiftedAndTintedRight, isR2L = false)

        assertTrue(result.isSpread, result.toString())
    }

    @Test
    fun `recognizes the opposite seam in R2L`() {
        val (left, right) = split(createTexturedImage(seed = 11))

        val result = PageSpreadScorer.score(right, left, isR2L = true)

        assertTrue(result.isSpread, result.toString())
    }

    @Test
    fun `rejects unrelated textured images`() {
        val (left, _) = split(createTexturedImage(seed = 13))
        val (_, unrelatedRight) = split(createTexturedImage(seed = 97))

        val result = PageSpreadScorer.score(left, unrelatedRight, isR2L = false)

        assertFalse(result.isSpread, result.toString())
    }

    @Test
    fun `rejects flat pages even when their edge colors match`() {
        val left = solidImage(180)
        val right = solidImage(180)

        val result = PageSpreadScorer.score(left, right, isR2L = false)

        assertFalse(result.isSpread, result.toString())
    }

    @Test
    fun `rejects a smooth photographic seam without horizontal structure`() {
        val (left, right) = split(createSmoothVerticalDetailImage(seed = 5))

        val result = PageSpreadScorer.score(left, right, isR2L = false)

        assertFalse(result.isSpread, result.toString())
    }

    @Test
    fun `recognizes a photographic seam with strong vertical continuity`() {
        val (left, right) = split(createStrongSmoothVerticalDetailImage(seed = 5))

        val result = PageSpreadScorer.score(left, right, isR2L = false)

        assertTrue(result.isSpread, result.toString())
    }

    @Test
    fun `rejects smooth pages with unrelated vertical detail`() {
        val (left, _) = split(createSmoothVerticalDetailImage(seed = 5))
        val (_, unrelatedRight) = split(createSmoothVerticalDetailImage(seed = 29))

        val result = PageSpreadScorer.score(left, unrelatedRight, isR2L = false)

        assertFalse(result.isSpread, result.toString())
    }

    private fun createTexturedImage(seed: Int): PageSpreadScorer.Image {
        val width = 320
        val height = 240
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val wave = sin((x + seed * 7) * 0.27) * 34 +
                sin((y - seed * 3) * 0.21) * 31 +
                sin((x + y + seed) * 0.11) * 24
            color(
                red = (128 + wave + sin(x * 0.43) * 22).toInt().coerceIn(0, 255),
                green = (128 + wave * 0.8 + sin(y * 0.39) * 25).toInt().coerceIn(0, 255),
                blue = (128 + wave * 0.7 + sin((x - y) * 0.31) * 28).toInt().coerceIn(0, 255),
            )
        }
        return PageSpreadScorer.Image(width, height, pixels)
    }

    private fun createSmoothVerticalDetailImage(seed: Int): PageSpreadScorer.Image {
        val width = 320
        val height = 240
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val verticalDetail = sin((y + seed * 11) * 0.17) * 11 +
                sin((y - seed * 3) * 0.047) * 17 +
                sin((y + seed) * 0.61) * 5
            // Keep across-seam changes below the horizontal texture threshold while preserving
            // a distinctive vertical profile, as in a softly lit photographic backdrop.
            val horizontalGradient = x * 0.012
            color(
                red = (168 + verticalDetail + horizontalGradient).toInt().coerceIn(0, 255),
                green = (181 + verticalDetail * 0.82 + horizontalGradient).toInt().coerceIn(0, 255),
                blue = (191 + verticalDetail * 0.68 + horizontalGradient).toInt().coerceIn(0, 255),
            )
        }
        return PageSpreadScorer.Image(width, height, pixels)
    }

    private fun createStrongSmoothVerticalDetailImage(seed: Int): PageSpreadScorer.Image {
        val width = 320
        val height = 240
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val verticalDetail = sin((y + seed * 11) * 0.17) * 28 +
                sin((y - seed * 3) * 0.047) * 22 +
                sin((y + seed) * 0.61) * 9
            val horizontalGradient = x * 0.012
            color(
                red = (168 + verticalDetail + horizontalGradient).toInt().coerceIn(0, 255),
                green = (181 + verticalDetail * 0.82 + horizontalGradient).toInt().coerceIn(0, 255),
                blue = (191 + verticalDetail * 0.68 + horizontalGradient).toInt().coerceIn(0, 255),
            )
        }
        return PageSpreadScorer.Image(width, height, pixels)
    }

    private fun split(image: PageSpreadScorer.Image): Pair<PageSpreadScorer.Image, PageSpreadScorer.Image> {
        val halfWidth = image.width / 2
        fun half(startX: Int): PageSpreadScorer.Image {
            return PageSpreadScorer.Image(
                halfWidth,
                image.height,
                IntArray(halfWidth * image.height) { index ->
                    val x = index % halfWidth
                    val y = index / halfWidth
                    image.pixel(startX + x, y)
                },
            )
        }
        return half(0) to half(halfWidth)
    }

    private fun transform(
        image: PageSpreadScorer.Image,
        verticalOffset: Int,
        colorShift: Int,
    ): PageSpreadScorer.Image {
        return PageSpreadScorer.Image(
            image.width,
            image.height,
            IntArray(image.pixels.size) { index ->
                val x = index % image.width
                val y = index / image.width
                val sourceY = (y - verticalOffset).coerceIn(0, image.height - 1)
                val source = image.pixel(x, sourceY)
                color(
                    red = ((source shr 16 and 0xff) + colorShift).coerceIn(0, 255),
                    green = ((source shr 8 and 0xff) + colorShift).coerceIn(0, 255),
                    blue = ((source and 0xff) + colorShift).coerceIn(0, 255),
                )
            },
        )
    }

    private fun solidImage(value: Int): PageSpreadScorer.Image {
        return PageSpreadScorer.Image(160, 240, IntArray(160 * 240) { color(value, value, value) })
    }

    private fun color(red: Int, green: Int, blue: Int): Int = red shl 16 or (green shl 8) or blue
}
