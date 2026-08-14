package eu.kanade.tachiyomi.ui.reader.viewer.pager

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Scores the continuity of the prospective seam between two page images.
 *
 * This intentionally operates on image pixels rather than Android [Bitmap] so its behaviour can
 * be covered by JVM tests. It is designed for the common case where a wide source image was cut
 * into two images without retaining an overlap.
 */
internal object PageSpreadScorer {

    data class Image(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    ) {
        init {
            require(width > 0 && height > 0)
            require(pixels.size == width * height)
        }

        fun pixel(x: Int, y: Int): Int = pixels[y * width + x]
    }

    data class Result(
        val confidence: Float,
        val isSpread: Boolean,
        val verticalOffset: Int,
        val matchingBlocks: Int,
        val informativeBlocks: Int,
        val totalBlocks: Int,
        val matchingBands: Int,
        val spatialMatchingBlocks: Int,
    )

    fun score(first: Image, second: Image, isR2L: Boolean): Result {
        val firstEdgeOnRight = !isR2L
        val secondEdgeOnLeft = !isR2L
        val firstStart = first.height * VERTICAL_MARGIN_PERCENT / 100
        val firstEnd = first.height - firstStart
        val maxOffset = max(2, min(first.height, second.height) * MAX_VERTICAL_OFFSET_PERCENT / 100)
        var best = Result(0f, false, 0, 0, 0, 0, 0, 0)

        // Include zero regardless of whether maxOffset is odd; the old stepped range could skip
        // the exact alignment entirely (for example -9, -7, ..., 9).
        for (offset in -maxOffset..maxOffset) {
            var matchingBlocks = 0
            var informativeBlocks = 0
            var totalBlocks = 0
            var matchingBands = 0
            var spatialMatchingBlocks = 0
            var previousBand = -1
            var ratioTotal = 0f

            for (firstY in firstStart until firstEnd step BLOCK_HEIGHT) {
                val secondY = mapY(firstY, first.height, second.height) + offset
                if (secondY < 0 || secondY + BLOCK_HEIGHT >= second.height) continue

                val block = scoreBlock(
                    first = first,
                    second = second,
                    firstY = firstY,
                    secondY = secondY,
                    firstEdgeOnRight = firstEdgeOnRight,
                    secondEdgeOnLeft = secondEdgeOnLeft,
                )
                totalBlocks++
                if (!block.informative) continue

                informativeBlocks++
                ratioTotal += block.ratio
                if (block.matches) {
                    matchingBlocks++
                    if (block.hasHorizontalStructure) spatialMatchingBlocks++
                    val band = ((firstY - firstStart) * VERTICAL_BANDS / max(1, firstEnd - firstStart))
                        .coerceIn(0, VERTICAL_BANDS - 1)
                    if (band != previousBand) {
                        matchingBands++
                        previousBand = band
                    }
                }
            }

            val confidence = confidence(
                matchingBlocks = matchingBlocks,
                informativeBlocks = informativeBlocks,
                totalBlocks = totalBlocks,
                matchingBands = matchingBands,
                averageRatio = ratioTotal / max(1, informativeBlocks),
            )
            val result = Result(
                confidence = confidence,
                isSpread = isSpread(
                    confidence = confidence,
                    matchingBlocks = matchingBlocks,
                    informativeBlocks = informativeBlocks,
                    totalBlocks = totalBlocks,
                    matchingBands = matchingBands,
                    spatialMatchingBlocks = spatialMatchingBlocks,
                ),
                verticalOffset = offset,
                matchingBlocks = matchingBlocks,
                informativeBlocks = informativeBlocks,
                totalBlocks = totalBlocks,
                matchingBands = matchingBands,
                spatialMatchingBlocks = spatialMatchingBlocks,
            )
            if (result.confidence > best.confidence) best = result
        }
        return best
    }

    private fun scoreBlock(
        first: Image,
        second: Image,
        firstY: Int,
        secondY: Int,
        firstEdgeOnRight: Boolean,
        secondEdgeOnLeft: Boolean,
    ): BlockScore {
        var seamDifference = 0f
        var localDifference = 0f
        var shuffledDifference = 0f
        var horizontalTexture = 0f
        var verticalTexture = 0f
        var verticalProfileDifference = 0f
        var samples = 0

        for (row in 0 until BLOCK_HEIGHT) {
            val y1 = firstY + row
            val y2 = secondY + row
            val firstEdge = first.pixel(edgeX(first, firstEdgeOnRight, 0), y1)
            val secondEdge = second.pixel(edgeX(second, secondEdgeOnLeft, 0), y2)
            val firstInner = first.pixel(edgeX(first, firstEdgeOnRight, 1), y1)
            val secondInner = second.pixel(edgeX(second, secondEdgeOnLeft, 1), y2)

            seamDifference += colorDifference(firstEdge, secondEdge)
            val firstLocal = colorDifference(firstEdge, firstInner)
            val secondLocal = colorDifference(secondEdge, secondInner)
            localDifference += (firstLocal + secondLocal) / 2f
            horizontalTexture += firstLocal + secondLocal

            if (row < BLOCK_HEIGHT - 1) {
                val nextFirstEdge = first.pixel(edgeX(first, firstEdgeOnRight, 0), y1 + 1)
                val nextSecondEdge = second.pixel(edgeX(second, secondEdgeOnLeft, 0), y2 + 1)
                verticalTexture +=
                    colorDifference(firstEdge, nextFirstEdge) + colorDifference(secondEdge, nextSecondEdge)
                verticalProfileDifference += colorDeltaDifference(
                    first = firstEdge,
                    firstNext = nextFirstEdge,
                    second = secondEdge,
                    secondNext = nextSecondEdge,
                )
            }

            // A real seam should be better than the same pixels aligned to an unrelated row.
            val shuffledY = (y2 + NEGATIVE_ROW_SHIFT).let {
                if (it < second.height) it else it - second.height
            }
            shuffledDifference += colorDifference(
                firstEdge,
                second.pixel(edgeX(second, secondEdgeOnLeft, 0), shuffledY),
            )
            samples++
        }

        if (samples == 0) return BlockScore(false, false, false, Float.POSITIVE_INFINITY)
        val local = localDifference / samples
        val seam = seamDifference / samples
        val shuffled = shuffledDifference / samples
        val averageHorizontalTexture = horizontalTexture / samples
        val averageVerticalTexture = verticalTexture / max(1, samples - 1)
        val averageVerticalProfileDifference = verticalProfileDifference / max(1, samples - 1)
        val ratio = seam / (local + LOCAL_SLACK)
        // A spread can have a smooth horizontal seam, especially in photographs. In that case,
        // retain evidence only when the colour changes along the seam follow the same profile on
        // both pages. Flat white/solid backgrounds have neither horizontal nor vertical evidence.
        val verticalProfileMatches =
            averageVerticalTexture >= MIN_BLOCK_VERTICAL_TEXTURE &&
                averageVerticalProfileDifference <=
                    averageVerticalTexture / 2f * MAX_VERTICAL_PROFILE_DIFFERENCE_RATIO + VERTICAL_PROFILE_SLACK
        val hasHorizontalStructure = averageHorizontalTexture >= MIN_BLOCK_TEXTURE
        // Photographic spreads often place a soft subject or background exactly at the seam,
        // so adjacent pixels can be continuous without producing a strong horizontal gradient.
        // Keep this as a separate, stricter path: absolute continuity must be clear, it must beat
        // a row-shifted control by a useful margin, and the seam must contain real vertical detail.
        val strongSeamContinuity =
            seam <= MAX_ABSOLUTE_SEAM_DIFFERENCE &&
                shuffled - seam >= MIN_NEGATIVE_CONTROL_MARGIN &&
                averageVerticalTexture >= MIN_CONTINUITY_VERTICAL_TEXTURE &&
                averageVerticalProfileDifference <=
                    averageVerticalTexture * MAX_CONTINUITY_PROFILE_RATIO + CONTINUITY_PROFILE_SLACK
        val informative = hasHorizontalStructure || verticalProfileMatches || strongSeamContinuity
        val matches = informative &&
            ratio <= MAX_SEAM_RATIO &&
            seam <= shuffled * MAX_NEGATIVE_CONTROL_RATIO
        return BlockScore(matches, informative, hasHorizontalStructure || strongSeamContinuity, ratio)
    }

    private fun confidence(
        matchingBlocks: Int,
        informativeBlocks: Int,
        totalBlocks: Int,
        matchingBands: Int,
        averageRatio: Float,
    ): Float {
        if (informativeBlocks == 0 || totalBlocks == 0) return 0f
        val matchRatio = matchingBlocks.toFloat() / informativeBlocks
        val informationCoverage = min(1f, informativeBlocks.toFloat() / (totalBlocks * MIN_INFORMATION_COVERAGE))
        val bandCoverage = matchingBands.toFloat() / VERTICAL_BANDS
        val seamQuality = (1f - averageRatio / MAX_SEAM_RATIO).coerceIn(0f, 1f)
        return matchRatio * 0.5f + informationCoverage * 0.15f + bandCoverage * 0.2f + seamQuality * 0.15f
    }

    private fun isSpread(
        confidence: Float,
        matchingBlocks: Int,
        informativeBlocks: Int,
        totalBlocks: Int,
        matchingBands: Int,
        spatialMatchingBlocks: Int,
    ): Boolean {
        val hasRegularCoverage = informativeBlocks * 100 >= totalBlocks * MIN_INFORMATIVE_BLOCKS_PERCENT
        // Rounded block counts make a 20% target require six blocks for a 27-block image. A
        // photographic subject can leave only five informative blocks while still providing a
        // strong, multi-band seam signal. Keep this exception narrower than the normal rule.
        val hasHighConfidenceSparseEvidence =
            matchingBlocks >= MIN_SPARSE_MATCHING_BLOCKS &&
                matchingBands >= MIN_MATCHING_BANDS &&
                confidence >= MIN_CONFIDENCE
        return totalBlocks >= MIN_TOTAL_BLOCKS &&
            (hasRegularCoverage || hasHighConfidenceSparseEvidence) &&
            matchingBlocks * 100 >= informativeBlocks * MIN_MATCHING_BLOCKS_PERCENT &&
            matchingBands >= MIN_MATCHING_BANDS &&
            spatialMatchingBlocks >= MIN_SPATIAL_MATCHING_BLOCKS &&
            confidence >= MIN_CONFIDENCE
    }

    private fun edgeX(image: Image, edgeOnRight: Boolean, depth: Int): Int {
        return if (edgeOnRight) image.width - 1 - depth else depth
    }

    private fun mapY(y: Int, fromHeight: Int, toHeight: Int): Int = y * toHeight / fromHeight

    private fun colorDifference(first: Int, second: Int): Float {
        return (
            abs((first shr 16 and 0xff) - (second shr 16 and 0xff)) +
                abs((first shr 8 and 0xff) - (second shr 8 and 0xff)) +
                abs((first and 0xff) - (second and 0xff))
            ) / 3f
    }

    private fun colorDeltaDifference(
        first: Int,
        firstNext: Int,
        second: Int,
        secondNext: Int,
    ): Float {
        fun channel(value: Int, shift: Int): Int = value shr shift and 0xff
        return (
            abs((channel(firstNext, 16) - channel(first, 16)) - (channel(secondNext, 16) - channel(second, 16))) +
                abs((channel(firstNext, 8) - channel(first, 8)) - (channel(secondNext, 8) - channel(second, 8))) +
                abs((channel(firstNext, 0) - channel(first, 0)) - (channel(secondNext, 0) - channel(second, 0)))
            ) / 3f
    }

    private data class BlockScore(
        val matches: Boolean,
        val informative: Boolean,
        val hasHorizontalStructure: Boolean,
        val ratio: Float,
    )

    private const val VERTICAL_MARGIN_PERCENT = 8
    private const val MAX_VERTICAL_OFFSET_PERCENT = 4
    private const val BLOCK_HEIGHT = 12
    private const val NEGATIVE_ROW_SHIFT = 61
    private const val VERTICAL_BANDS = 4
    // This is measured after resizing to the scoring resolution. JPEG decoding and downsampling
    // flatten a real image's single-pixel gradients substantially, so use the negative control
    // and multi-band coverage for false-positive protection instead of a high texture cutoff.
    private const val MIN_BLOCK_TEXTURE = 8f
    private const val MIN_BLOCK_VERTICAL_TEXTURE = 2.4f
    private const val MIN_CONTINUITY_VERTICAL_TEXTURE = 4.5f
    private const val MAX_ABSOLUTE_SEAM_DIFFERENCE = 6f
    private const val MIN_NEGATIVE_CONTROL_MARGIN = 8f
    private const val MAX_CONTINUITY_PROFILE_RATIO = 0.8f
    private const val CONTINUITY_PROFILE_SLACK = 1.8f
    private const val MAX_VERTICAL_PROFILE_DIFFERENCE_RATIO = 1.1f
    private const val VERTICAL_PROFILE_SLACK = 1.4f
    private const val LOCAL_SLACK = 10f
    private const val MAX_SEAM_RATIO = 1.6f
    private const val MAX_NEGATIVE_CONTROL_RATIO = 1.05f
    private const val MIN_INFORMATION_COVERAGE = 0.45f
    private const val MIN_TOTAL_BLOCKS = 12
    // A photographic spread can provide detailed seam evidence only across its subject, while
    // large areas of a shared backdrop remain intentionally uninformative. The other checks still
    // require matches to span three bands and significantly outperform a row-shifted control.
    private const val MIN_INFORMATIVE_BLOCKS_PERCENT = 20
    private const val MIN_MATCHING_BLOCKS_PERCENT = 50
    private const val MIN_MATCHING_BANDS = 3
    // Vertical changes alone (wall panels, door frames, skies) are common in independent photos.
    // Require structure that also continues across the seam before joining automatically.
    private const val MIN_SPATIAL_MATCHING_BLOCKS = 2
    private const val MIN_SPARSE_MATCHING_BLOCKS = 5
    private const val MIN_CONFIDENCE = 0.64f
}
