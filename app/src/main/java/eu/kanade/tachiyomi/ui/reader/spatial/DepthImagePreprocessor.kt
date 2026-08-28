package eu.kanade.tachiyomi.ui.reader.spatial

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import tachiyomi.decoder.ImageDecoder
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Prepares the square NHWC RGB input used by Qualcomm's Depth Anything V3 DLC. */
internal object DepthImagePreprocessor {
    const val IMAGE_SIZE = 518

    data class DetailTile(
        val input: FloatArray,
        val sourceLeft: Int,
        val sourceTop: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    data class Result(
        val input: FloatArray,
        val originalWidth: Int,
        val originalHeight: Int,
        val contentLeft: Int,
        val contentTop: Int,
        val contentWidth: Int,
        val contentHeight: Int,
        val renderRgb: ByteArray,
        val renderWidth: Int,
        val renderHeight: Int,
        val detailTiles: List<DetailTile>,
    )

    fun prepare(encodedImage: InputStream): Result {
        val source = decodeSource(encodedImage)

        try {
            check(source.width > 1 && source.height > 1) {
                "Depth estimation requires an image larger than one pixel"
            }
            val scale = minOf(
                IMAGE_SIZE.toFloat() / source.width,
                IMAGE_SIZE.toFloat() / source.height,
            )
            val contentWidth = (source.width * scale).roundToInt().coerceIn(1, IMAGE_SIZE)
            val contentHeight = (source.height * scale).roundToInt().coerceIn(1, IMAGE_SIZE)
            val contentLeft = (IMAGE_SIZE - contentWidth) / 2
            val contentTop = (IMAGE_SIZE - contentHeight) / 2
            val prepared = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
            try {
                Canvas(prepared).apply {
                    drawColor(Color.BLACK)
                    drawBitmap(
                        source,
                        null,
                        Rect(
                            contentLeft,
                            contentTop,
                            contentLeft + contentWidth,
                            contentTop + contentHeight,
                        ),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                    )
                }
                val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
                prepared.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
                val input = FloatArray(pixels.size * 3)
                pixels.forEachIndexed { index, color ->
                    val base = index * 3
                    input[base] = Color.red(color) / 255f
                    input[base + 1] = Color.green(color) / 255f
                    input[base + 2] = Color.blue(color) / 255f
                }
                val renderScale = minOf(
                    1f,
                    RENDER_MAX_SIZE.toFloat() / maxOf(source.width, source.height),
                )
                val renderWidth = (source.width * renderScale).roundToInt().coerceAtLeast(1)
                val renderHeight = (source.height * renderScale).roundToInt().coerceAtLeast(1)
                val renderBitmap = Bitmap.createScaledBitmap(source, renderWidth, renderHeight, true)
                val renderPixels = IntArray(renderWidth * renderHeight)
                try {
                    renderBitmap.getPixels(renderPixels, 0, renderWidth, 0, 0, renderWidth, renderHeight)
                } finally {
                    if (renderBitmap !== source) renderBitmap.recycle()
                }
                val renderRgb = ByteArray(renderPixels.size * 3)
                renderPixels.forEachIndexed { index, color ->
                    val base = index * 3
                    renderRgb[base] = Color.red(color).toByte()
                    renderRgb[base + 1] = Color.green(color).toByte()
                    renderRgb[base + 2] = Color.blue(color).toByte()
                }
                val detailTiles = createDetailTiles(source)
                return Result(
                    input = input,
                    originalWidth = source.width,
                    originalHeight = source.height,
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    contentWidth = contentWidth,
                    contentHeight = contentHeight,
                    renderRgb = renderRgb,
                    renderWidth = renderWidth,
                    renderHeight = renderHeight,
                    detailTiles = detailTiles,
                )
            } finally {
                prepared.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun decodeSource(encodedImage: InputStream): Bitmap {
        // BitmapFactory does not support every format accepted by Mihon's reader. Keep a
        // replayable copy so AVIF, JPEG XL and HEIF can fall back to the bundled decoder
        // after BitmapFactory has inspected the same input.
        val encoded = encodedImage.readBytes()
        check(encoded.isNotEmpty()) { "The current page image is empty" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        val bitmapFactorySample = if (bounds.outWidth > 1 && bounds.outHeight > 1) {
            decodeSampleSize(bounds.outWidth, bounds.outHeight)
        } else {
            1
        }
        if (bitmapFactorySample > 1) {
            Log.i(
                TAG,
                "Sampling large spatial source ${bounds.outWidth}x${bounds.outHeight} at 1/$bitmapFactorySample",
            )
        }
        BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inSampleSize = bitmapFactorySample
            },
        )?.let { return it }

        val decoder = try {
            ImageDecoder.newInstance(
                encoded.inputStream(),
                false,
                TachiyomiImageDecoder.displayProfile,
            )
        } catch (_: Exception) {
            null
        } ?: error("The current page uses an unsupported or damaged image format")
        return try {
            check(decoder.width > 1 && decoder.height > 1) {
                "The current page has invalid image dimensions"
            }
            val decoderSample = decodeSampleSize(decoder.width, decoder.height)
            if (decoderSample > 1) {
                Log.i(
                    TAG,
                    "Sampling large bundled-decoder source ${decoder.width}x${decoder.height} at 1/$decoderSample",
                )
            }
            decoder.decode(sampleSize = decoderSample)
                ?: error("The bundled image decoder could not decode the current page")
        } finally {
            decoder.recycle()
        }
    }

    /**
     * Bounds the temporary ARGB source bitmap before any model or surfel buffers are
     * allocated. A power-of-two sample works consistently in both Android's and the bundled
     * image decoders and still leaves substantially more detail than the 1536 px render grid.
     */
    internal fun decodeSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (sample < MAX_DECODE_SAMPLE) {
            val sampledWidth = (width.toLong() + sample - 1L) / sample
            val sampledHeight = (height.toLong() + sample - 1L) / sample
            if (
                sampledWidth <= MAX_SOURCE_DIMENSION &&
                sampledHeight <= MAX_SOURCE_DIMENSION &&
                sampledWidth * sampledHeight <= MAX_SOURCE_PIXELS
            ) {
                break
            }
            sample *= 2
        }
        return sample
    }

    private fun createDetailTiles(source: Bitmap): List<DetailTile> {
        val shortSide = minOf(source.width, source.height)
        val longSide = maxOf(source.width, source.height)
        if (longSide.toFloat() / shortSide < MIN_DETAIL_TILE_ASPECT) return emptyList()
        val tileCount = ceil(longSide.toFloat() / shortSide).toInt().coerceIn(2, MAX_DETAIL_TILES)
        val travel = longSide - shortSide
        return List(tileCount) { index ->
            val longOffset = if (tileCount == 1) 0 else
                (travel * index.toFloat() / (tileCount - 1)).roundToInt()
            val sourceLeft = if (source.width >= source.height) longOffset else 0
            val sourceTop = if (source.height > source.width) longOffset else 0
            val input = createModelInput(
                source = source,
                sourceRect = Rect(
                    sourceLeft,
                    sourceTop,
                    sourceLeft + shortSide,
                    sourceTop + shortSide,
                ),
            )
            DetailTile(
                input = input,
                sourceLeft = sourceLeft,
                sourceTop = sourceTop,
                sourceWidth = shortSide,
                sourceHeight = shortSide,
            )
        }
    }

    private fun createModelInput(source: Bitmap, sourceRect: Rect): FloatArray {
        val tile = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(tile).drawBitmap(
                source,
                sourceRect,
                Rect(0, 0, IMAGE_SIZE, IMAGE_SIZE),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            tile.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
            FloatArray(pixels.size * 3).also { input ->
                pixels.forEachIndexed { index, color ->
                    val base = index * 3
                    input[base] = Color.red(color) / 255f
                    input[base + 1] = Color.green(color) / 255f
                    input[base + 2] = Color.blue(color) / 255f
                }
            }
        } finally {
            tile.recycle()
        }
    }

    // Keep QNN depth inference at the model's fixed 518 px input, but retain a denser
    // full-color surfel grid for the final spatial rendering.
    private const val TAG = "DepthPreprocessor"
    private const val MAX_SOURCE_DIMENSION = 4096L
    private const val MAX_SOURCE_PIXELS = 8_000_000L
    private const val MAX_DECODE_SAMPLE = 32
    private const val RENDER_MAX_SIZE = 1536
    private const val MIN_DETAIL_TILE_ASPECT = 1.2f
    private const val MAX_DETAIL_TILES = 3
}
