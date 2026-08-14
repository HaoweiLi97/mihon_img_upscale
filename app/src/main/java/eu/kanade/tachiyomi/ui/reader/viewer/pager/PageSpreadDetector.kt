package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okio.BufferedSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Decodes page sources and delegates conservative seam analysis to [PageSpreadScorer]. */
internal object PageSpreadDetector {

    data class Result(
        val confidence: Float,
        val isSpread: Boolean,
        val matchingBlocks: Int = 0,
        val informativeBlocks: Int = 0,
        val totalBlocks: Int = 0,
        val matchingBands: Int = 0,
        val spatialMatchingBlocks: Int = 0,
    )

    fun detect(
        firstSource: BufferedSource,
        secondSource: BufferedSource,
        isR2L: Boolean,
    ): Result {
        val first = decodeForComparison(firstSource) ?: return Result(0f, false)
        val second = decodeForComparison(secondSource) ?: return Result(0f, false)
        try {
            if (!hasCompatibleDimensions(first, second)) return Result(0f, false)
            val result = PageSpreadScorer.score(toImage(first), toImage(second), isR2L)
            return Result(
                confidence = result.confidence,
                isSpread = result.isSpread,
                matchingBlocks = result.matchingBlocks,
                informativeBlocks = result.informativeBlocks,
                totalBlocks = result.totalBlocks,
                matchingBands = result.matchingBands,
                spatialMatchingBlocks = result.spatialMatchingBlocks,
            )
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    private fun decodeForComparison(source: BufferedSource): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.peek().inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = max(bounds.outWidth, bounds.outHeight) / MAX_DECODE_SIZE
        return source.peek().inputStream().use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = max(1, Integer.highestOneBit(sample))
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }
    }

    private fun hasCompatibleDimensions(first: Bitmap, second: Bitmap): Boolean {
        val firstRatio = first.width.toFloat() / first.height
        val secondRatio = second.width.toFloat() / second.height
        val heightRatio = first.height.toFloat() / second.height
        if (firstRatio > MAX_HALF_ASPECT_RATIO || secondRatio > MAX_HALF_ASPECT_RATIO) return false
        if (heightRatio !in MIN_SIZE_RATIO..MAX_SIZE_RATIO) return false
        return abs(firstRatio - secondRatio) <= MAX_ASPECT_RATIO_DELTA
    }

    private fun toImage(bitmap: Bitmap): PageSpreadScorer.Image {
        val height = minOf(SCORING_HEIGHT, bitmap.height).coerceAtLeast(MIN_SCORING_HEIGHT)
        val width = (bitmap.width * height.toFloat() / bitmap.height)
            .roundToInt()
            .coerceAtLeast(MIN_SCORING_WIDTH)
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        return PageSpreadScorer.Image(width, height, IntArray(width * height).also {
            scaled.getPixels(it, 0, width, 0, 0, width, height)
            if (scaled !== bitmap) scaled.recycle()
        })
    }

    private const val MAX_DECODE_SIZE = 1024
    private const val SCORING_HEIGHT = 384
    private const val MIN_SCORING_HEIGHT = 96
    private const val MIN_SCORING_WIDTH = 48
    private const val MAX_HALF_ASPECT_RATIO = 1.7f
    private const val MIN_SIZE_RATIO = 0.75f
    private const val MAX_SIZE_RATIO = 1.33f
    private const val MAX_ASPECT_RATIO_DELTA = 0.35f
}
