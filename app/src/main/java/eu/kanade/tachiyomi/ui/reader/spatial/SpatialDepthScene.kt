package eu.kanade.tachiyomi.ui.reader.spatial

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max

data class SpatialDepthScene(
    val vertices: FloatArray,
    val pointCount: Int,
    val focalLengthPx: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val focusDepth: Float,
)

internal object SpatialDepthSceneBuilder {
    private const val FLOATS_PER_POINT = 8

    private data class AlignedDetailTile(
        val metadata: DepthImagePreprocessor.DetailTile,
        val depth: FloatArray,
    )

    fun build(
        prepared: DepthImagePreprocessor.Result,
        depth: FloatArray,
        detailDepths: List<FloatArray>,
    ): SpatialDepthScene {
        val size = DepthImagePreprocessor.IMAGE_SIZE
        require(depth.size == size * size) { "Depth Anything V3 returned ${depth.size} values" }
        require(detailDepths.size == prepared.detailTiles.size)
        require(detailDepths.all { it.size == size * size })

        val validDepths = FloatArray(prepared.contentWidth * prepared.contentHeight)
        var validCount = 0
        for (y in prepared.contentTop until prepared.contentTop + prepared.contentHeight) {
            for (x in prepared.contentLeft until prepared.contentLeft + prepared.contentWidth) {
                depth[y * size + x].takeIf { it.isFinite() }?.let { validDepths[validCount++] = it }
            }
        }
        check(validCount > validDepths.size / 2) { "Depth Anything V3 produced too few valid values" }
        val sorted = validDepths.copyOf(validCount).apply { sort() }
        val near = percentile(sorted, 0.02f)
        val far = percentile(sorted, 0.98f)
        check(far - near > 1e-6f) { "Depth Anything V3 produced a flat depth map" }

        val normalized = FloatArray(depth.size)
        depth.forEachIndexed { index, value ->
            normalized[index] = if (value.isFinite()) ((value - near) / (far - near)).coerceIn(0f, 1f) else 0.5f
        }

        val filteredDepth = gaussianDepth(
            depth = normalized,
            rgb = prepared.input,
            left = prepared.contentLeft,
            right = prepared.contentLeft + prepared.contentWidth - 1,
            top = prepared.contentTop,
            bottom = prepared.contentTop + prepared.contentHeight - 1,
        )
        val alignedDetailTiles = prepared.detailTiles.zip(detailDepths) { metadata, tileDepth ->
            alignDetailTile(
                metadata = metadata,
                tileDepth = tileDepth,
                globalDepth = depth,
                prepared = prepared,
                near = near,
                far = far,
            )
        }

        val pointCount = prepared.renderWidth * prepared.renderHeight
        val vertices = FloatArray(pointCount * FLOATS_PER_POINT)
        val focal = max(prepared.originalWidth, prepared.originalHeight) * 1.2f
        val basePointSize = max(
            prepared.originalWidth.toFloat() / prepared.renderWidth,
            prepared.originalHeight.toFloat() / prepared.renderHeight,
        ) * 1.95f
        var point = 0
        for (y in 0 until prepared.renderHeight) {
            for (x in 0 until prepared.renderWidth) {
                val imageX = (x + 0.5f) / prepared.renderWidth * prepared.originalWidth
                val imageY = (y + 0.5f) / prepared.renderHeight * prepared.originalHeight
                val normalizedDepth = sampleCombinedDepth(
                    filteredDepth,
                    alignedDetailTiles,
                    imageX,
                    imageY,
                    prepared,
                )
                val depthStepX = prepared.originalWidth.toFloat() / prepared.contentWidth
                val depthStepY = prepared.originalHeight.toFloat() / prepared.contentHeight
                val horizontalDepthDelta = abs(
                    sampleCombinedDepth(
                        filteredDepth,
                        alignedDetailTiles,
                        imageX + depthStepX,
                        imageY,
                        prepared,
                    ) -
                        sampleCombinedDepth(
                            filteredDepth,
                            alignedDetailTiles,
                            imageX - depthStepX,
                            imageY,
                            prepared,
                        ),
                )
                val verticalDepthDelta = abs(
                    sampleCombinedDepth(
                        filteredDepth,
                        alignedDetailTiles,
                        imageX,
                        imageY + depthStepY,
                        prepared,
                    ) -
                        sampleCombinedDepth(
                            filteredDepth,
                            alignedDetailTiles,
                            imageX,
                            imageY - depthStepY,
                            prepared,
                        ),
                )
                // Rotation separates foreground and background most strongly at depth
                // discontinuities. Expand only those surfels so their sampling rows do not
                // become a visible black contour/moire pattern.
                // Let depth cuts dissolve into compact particles instead of stretching a
                // source pixel into a long rectangular smear during side-to-side parallax.
                val edgeCoverage = (1f + max(horizontalDepthDelta, verticalDepthDelta) * 4f)
                    .coerceIn(1f, 1.65f)
                val z = 0.66f + normalizedDepth * 0.72f
                val source = (y * prepared.renderWidth + x) * 3
                val target = point * FLOATS_PER_POINT
                vertices[target] = (imageX - prepared.originalWidth * 0.5f) / focal * z
                vertices[target + 1] = (imageY - prepared.originalHeight * 0.5f) / focal * z
                vertices[target + 2] = z
                vertices[target + 3] = basePointSize * edgeCoverage
                vertices[target + 4] = (prepared.renderRgb[source].toInt() and 0xff) / 255f
                vertices[target + 5] = (prepared.renderRgb[source + 1].toInt() and 0xff) / 255f
                vertices[target + 6] = (prepared.renderRgb[source + 2].toInt() and 0xff) / 255f
                // Alpha stores an edge-particle factor for the renderer. Interior samples
                // remain solid and only true depth cuts dissolve into round particles.
                vertices[target + 7] = ((edgeCoverage - 1f) / 0.65f).coerceIn(0f, 1f)
                point++
            }
        }
        val focusDepth = 0.66f + percentile(sorted, 0.5f)
            .let { ((it - near) / (far - near)).coerceIn(0f, 1f) } * 0.72f
        return SpatialDepthScene(
            vertices = vertices,
            pointCount = pointCount,
            focalLengthPx = focal,
            imageWidth = prepared.originalWidth,
            imageHeight = prepared.originalHeight,
            gridWidth = prepared.renderWidth,
            gridHeight = prepared.renderHeight,
            focusDepth = focusDepth,
        )
    }

    private fun alignDetailTile(
        metadata: DepthImagePreprocessor.DetailTile,
        tileDepth: FloatArray,
        globalDepth: FloatArray,
        prepared: DepthImagePreprocessor.Result,
        near: Float,
        far: Float,
    ): AlignedDetailTile {
        val size = DepthImagePreprocessor.IMAGE_SIZE
        var sampleCount = 0
        var tileSum = 0.0
        var globalSum = 0.0
        var tileSquaredSum = 0.0
        var productSum = 0.0
        var tileY = ALIGNMENT_SAMPLE_STEP / 2
        while (tileY < size) {
            var tileX = ALIGNMENT_SAMPLE_STEP / 2
            while (tileX < size) {
                val sourceX = metadata.sourceLeft +
                    (tileX + 0.5f) / size * metadata.sourceWidth
                val sourceY = metadata.sourceTop +
                    (tileY + 0.5f) / size * metadata.sourceHeight
                val globalX = prepared.contentLeft +
                    sourceX / prepared.originalWidth * prepared.contentWidth - 0.5f
                val globalY = prepared.contentTop +
                    sourceY / prepared.originalHeight * prepared.contentHeight - 0.5f
                val tileValue = tileDepth[tileY * size + tileX]
                val globalValue = sampleDepth(
                    globalDepth,
                    globalX,
                    globalY,
                    prepared.contentLeft,
                    prepared.contentLeft + prepared.contentWidth - 1,
                    prepared.contentTop,
                    prepared.contentTop + prepared.contentHeight - 1,
                )
                if (tileValue.isFinite() && globalValue.isFinite()) {
                    val tileDouble = tileValue.toDouble()
                    val globalDouble = globalValue.toDouble()
                    sampleCount++
                    tileSum += tileDouble
                    globalSum += globalDouble
                    tileSquaredSum += tileDouble * tileDouble
                    productSum += tileDouble * globalDouble
                }
                tileX += ALIGNMENT_SAMPLE_STEP
            }
            tileY += ALIGNMENT_SAMPLE_STEP
        }
        check(sampleCount >= 64) { "Depth tile produced too few alignment samples" }
        val covariance = productSum - tileSum * globalSum / sampleCount
        val variance = tileSquaredSum - tileSum * tileSum / sampleCount
        val rawScale = if (variance > 1e-9) covariance / variance else 1.0
        val scale = rawScale.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(MIN_TILE_DEPTH_SCALE.toDouble(), MAX_TILE_DEPTH_SCALE.toDouble())
            ?: 1.0
        val offset = globalSum / sampleCount - scale * tileSum / sampleCount
        val depthRange = far - near
        val normalized = FloatArray(tileDepth.size) { index ->
            val value = tileDepth[index]
            if (value.isFinite()) {
                ((((value.toDouble() * scale + offset).toFloat()) - near) / depthRange)
                    .coerceIn(0f, 1f)
            } else {
                0.5f
            }
        }
        return AlignedDetailTile(
            metadata = metadata,
            depth = gaussianDepth(
                depth = normalized,
                rgb = metadata.input,
                left = 0,
                right = size - 1,
                top = 0,
                bottom = size - 1,
            ),
        )
    }

    private fun sampleCombinedDepth(
        globalDepth: FloatArray,
        tiles: List<AlignedDetailTile>,
        imageX: Float,
        imageY: Float,
        prepared: DepthImagePreprocessor.Result,
    ): Float {
        val size = DepthImagePreprocessor.IMAGE_SIZE
        val globalX = prepared.contentLeft +
            imageX / prepared.originalWidth * prepared.contentWidth - 0.5f
        val globalY = prepared.contentTop +
            imageY / prepared.originalHeight * prepared.contentHeight - 0.5f
        val globalValue = sampleDepth(
            globalDepth,
            globalX,
            globalY,
            prepared.contentLeft,
            prepared.contentLeft + prepared.contentWidth - 1,
            prepared.contentTop,
            prepared.contentTop + prepared.contentHeight - 1,
        )
        if (tiles.isEmpty()) return globalValue

        var weightedDepth = globalValue * GLOBAL_DEPTH_FUSION_WEIGHT
        var totalWeight = GLOBAL_DEPTH_FUSION_WEIGHT
        tiles.forEach { tile ->
            val metadata = tile.metadata
            val u = (imageX - metadata.sourceLeft) / metadata.sourceWidth
            val v = (imageY - metadata.sourceTop) / metadata.sourceHeight
            if (u !in 0f..1f || v !in 0f..1f) return@forEach
            val edgeDistance = minOf(u, 1f - u, v, 1f - v)
            val feather = (edgeDistance / TILE_FEATHER_FRACTION).coerceIn(0f, 1f)
                .let { it * it * (3f - 2f * it) }
            if (feather <= 0f) return@forEach
            val tileValue = sampleDepth(
                tile.depth,
                u * size - 0.5f,
                v * size - 0.5f,
                0,
                size - 1,
                0,
                size - 1,
            )
            weightedDepth += tileValue * feather
            totalWeight += feather
        }
        return weightedDepth / totalWeight
    }

    private fun gaussianDepth(
        depth: FloatArray,
        rgb: FloatArray,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): FloatArray {
        val size = DepthImagePreprocessor.IMAGE_SIZE
        var source = depth.copyOf()
        var horizontal = depth.copyOf()
        var vertical = depth.copyOf()
        var detailReference: FloatArray? = null
        repeat(GAUSSIAN_PASSES) { pass ->
            for (y in top..bottom) {
                val row = y * size
                for (x in left..right) {
                    var weightedDepth = 0f
                    for (offset in -GAUSSIAN_RADIUS..GAUSSIAN_RADIUS) {
                        val sampleX = (x + offset).coerceIn(left, right)
                        weightedDepth += source[row + sampleX] *
                            GAUSSIAN_WEIGHTS[offset + GAUSSIAN_RADIUS]
                    }
                    horizontal[row + x] = weightedDepth / GAUSSIAN_WEIGHT_SUM
                }
            }
            for (y in top..bottom) {
                for (x in left..right) {
                    var weightedDepth = 0f
                    for (offset in -GAUSSIAN_RADIUS..GAUSSIAN_RADIUS) {
                        val sampleY = (y + offset).coerceIn(top, bottom)
                        weightedDepth += horizontal[sampleY * size + x] *
                            GAUSSIAN_WEIGHTS[offset + GAUSSIAN_RADIUS]
                    }
                    vertical[y * size + x] = weightedDepth / GAUSSIAN_WEIGHT_SUM
                }
            }
            val previousSource = source
            source = vertical
            vertical = previousSource
            if (pass == 0) detailReference = source.copyOf()
        }
        val mediumDetail = checkNotNull(detailReference)
        val bodyScaleReference = broadDepthReference(
            depth = source,
            left = left,
            right = right,
            top = top,
            bottom = bottom,
        )
        val result = source.copyOf()
        for (y in top..bottom) {
            for (x in left..right) {
                val index = y * size + x
                val x0 = (x - DETAIL_EDGE_RADIUS).coerceAtLeast(left)
                val x1 = (x + DETAIL_EDGE_RADIUS).coerceAtMost(right)
                val y0 = (y - DETAIL_EDGE_RADIUS).coerceAtLeast(top)
                val y1 = (y + DETAIL_EDGE_RADIUS).coerceAtMost(bottom)
                val localDepthEdge = max(
                    abs(mediumDetail[y * size + x1] - mediumDetail[y * size + x0]),
                    abs(mediumDetail[y1 * size + x] - mediumDetail[y0 * size + x]),
                )
                val horizontalColorEdge = colorDistance(rgb, y * size + x0, y * size + x1)
                val verticalColorEdge = colorDistance(rgb, y0 * size + x, y1 * size + x)
                val localColorEdge = max(horizontalColorEdge, verticalColorEdge)
                // Preserve the stable three-pass Gaussian base, then restore bounded
                // mid-frequency depth that carries cheeks, nose, eyes and body curvature.
                // Restore boundary detail only when RGB and depth agree on the discontinuity;
                // the edge-cover renderer handles the resulting wider parallax gap.
                val smoothSurfaceWeight = (1f - localDepthEdge / DETAIL_EDGE_CUTOFF)
                    .coerceIn(0f, 1f)
                val confirmedBoundaryWeight = minOf(
                    (localDepthEdge / CONFIRMED_DEPTH_EDGE).coerceIn(0f, 1f),
                    (localColorEdge / CONFIRMED_COLOR_EDGE).coerceIn(0f, 1f),
                ) * BOUNDARY_DETAIL_RETENTION
                val detailWeight = max(smoothSurfaceWeight, confirmedBoundaryWeight)
                val detail = (mediumDetail[index] - source[index])
                    .coerceIn(-MAX_DETAIL_DELTA, MAX_DETAIL_DELTA)
                // Face and hair detail lives close to the model pixel scale, while chest,
                // shoulders and torso curvature spans a much wider area. Restore that
                // low-frequency relief separately. The depth-aware broad reference does
                // not average across a strong foreground/background discontinuity, so it
                // strengthens body volume without drawing a halo around the silhouette.
                // Smaller z is nearer the viewer. DA3 occasionally predicts the smooth
                // center of a cheek or chest as farther than its surrounding body surface,
                // producing an unmistakable dent during parallax. Reflect that low-confidence
                // recess toward the viewer, but only on RGB-smooth regions. Real creases,
                // clothing boundaries, eyes and lips retain their original signed structure.
                val bodyResidual = (source[index] - bodyScaleReference[index])
                    .coerceIn(-MAX_BODY_DETAIL_DELTA, MAX_BODY_DETAIL_DELTA)
                val recessSmoothness = (1f - localColorEdge / BODY_RECESS_COLOR_EDGE)
                    .coerceIn(0f, 1f)
                val bodyDetail = if (bodyResidual <= 0f) {
                    bodyResidual
                } else {
                    -bodyResidual * BODY_RECESS_INVERSION * recessSmoothness
                }
                val bodyDetailWeight = (1f - localDepthEdge / BODY_DETAIL_EDGE_CUTOFF)
                    .coerceIn(0f, 1f)
                result[index] = (
                    source[index] +
                        detail * DETAIL_GAIN * detailWeight +
                        bodyDetail * BODY_DETAIL_GAIN * bodyDetailWeight
                    )
                    .coerceIn(0f, 1f)
            }
        }
        return result
    }

    private fun broadDepthReference(
        depth: FloatArray,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): FloatArray {
        val size = DepthImagePreprocessor.IMAGE_SIZE
        val horizontal = depth.copyOf()
        val result = depth.copyOf()
        for (y in top..bottom) {
            val row = y * size
            for (x in left..right) {
                val center = depth[row + x]
                var weightedDepth = 0f
                var totalWeight = 0f
                for (offset in -BODY_DETAIL_RADIUS..BODY_DETAIL_RADIUS) {
                    val sample = depth[row + (x + offset).coerceIn(left, right)]
                    val distance = abs(sample - center) / BODY_DETAIL_RANGE
                    val rangeWeight = 1f / (1f + distance * distance * 4f)
                    val spatialWeight = BODY_DETAIL_RADIUS + 1f - abs(offset)
                    val weight = rangeWeight * spatialWeight
                    weightedDepth += sample * weight
                    totalWeight += weight
                }
                horizontal[row + x] = weightedDepth / totalWeight
            }
        }
        for (y in top..bottom) {
            for (x in left..right) {
                val center = horizontal[y * size + x]
                var weightedDepth = 0f
                var totalWeight = 0f
                for (offset in -BODY_DETAIL_RADIUS..BODY_DETAIL_RADIUS) {
                    val sample = horizontal[(y + offset).coerceIn(top, bottom) * size + x]
                    val distance = abs(sample - center) / BODY_DETAIL_RANGE
                    val rangeWeight = 1f / (1f + distance * distance * 4f)
                    val spatialWeight = BODY_DETAIL_RADIUS + 1f - abs(offset)
                    val weight = rangeWeight * spatialWeight
                    weightedDepth += sample * weight
                    totalWeight += weight
                }
                result[y * size + x] = weightedDepth / totalWeight
            }
        }
        return result
    }

    private fun colorDistance(rgb: FloatArray, firstPixel: Int, secondPixel: Int): Float {
        val first = firstPixel * 3
        val second = secondPixel * 3
        val red = rgb[first] - rgb[second]
        val green = rgb[first + 1] - rgb[second + 1]
        val blue = rgb[first + 2] - rgb[second + 2]
        return kotlin.math.sqrt(red * red + green * green + blue * blue)
    }

    private fun sampleDepth(
        depth: FloatArray,
        x: Float,
        y: Float,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): Float {
        val size = DepthImagePreprocessor.IMAGE_SIZE
        val x0 = x.toInt().coerceIn(left, right)
        val y0 = y.toInt().coerceIn(top, bottom)
        val x1 = minOf(x0 + 1, right)
        val y1 = minOf(y0 + 1, bottom)
        val dx = (x - x0).coerceIn(0f, 1f)
        val dy = (y - y0).coerceIn(0f, 1f)
        val d00 = depth[y0 * size + x0]
        val d10 = depth[y0 * size + x1]
        val d01 = depth[y1 * size + x0]
        val d11 = depth[y1 * size + x1]
        val top = d00 * (1f - dx) + d10 * dx
        val bottom = d01 * (1f - dx) + d11 * dx
        return top * (1f - dy) + bottom * dy
    }

    private fun percentile(sorted: FloatArray, fraction: Float): Float {
        val position = fraction.coerceIn(0f, 1f) * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val amount = position - lower
        return sorted[lower] * (1f - amount) + sorted[upper] * amount
    }

    private val GAUSSIAN_WEIGHTS = floatArrayOf(1f, 4f, 6f, 4f, 1f)
    private const val GAUSSIAN_WEIGHT_SUM = 16f
    private const val GAUSSIAN_RADIUS = 2
    private const val GAUSSIAN_PASSES = 3
    private const val DETAIL_EDGE_RADIUS = 2
    private const val DETAIL_EDGE_CUTOFF = 0.075f
    private const val MAX_DETAIL_DELTA = 0.045f
    private const val DETAIL_GAIN = 1.80f
    private const val BODY_DETAIL_RADIUS = 42
    private const val BODY_DETAIL_RANGE = 0.075f
    private const val BODY_DETAIL_EDGE_CUTOFF = 0.055f
    private const val BODY_RECESS_COLOR_EDGE = 0.18f
    private const val BODY_RECESS_INVERSION = 0.85f
    private const val MAX_BODY_DETAIL_DELTA = 0.045f
    private const val BODY_DETAIL_GAIN = 1.75f
    private const val CONFIRMED_DEPTH_EDGE = 0.025f
    private const val CONFIRMED_COLOR_EDGE = 0.20f
    private const val BOUNDARY_DETAIL_RETENTION = 0.78f
    private const val ALIGNMENT_SAMPLE_STEP = 12
    private const val MIN_TILE_DEPTH_SCALE = 0.25f
    private const val MAX_TILE_DEPTH_SCALE = 4f
    private const val GLOBAL_DEPTH_FUSION_WEIGHT = 0.08f
    private const val TILE_FEATHER_FRACTION = 0.12f
}

object SpatialDepthSceneIO {
    private const val MAGIC = 0x44334453
    private const val VERSION = 5
    private const val FLOATS_PER_POINT = 8
    private const val HEADER_BYTES = 7 * Int.SIZE_BYTES + 2 * Float.SIZE_BYTES

    fun read(file: File): SpatialDepthScene {
        require(file.isFile) { "Spatial scene does not exist: ${file.absolutePath}" }
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= HEADER_BYTES) { "Spatial scene is truncated" }
            val header = input.channel.map(FileChannel.MapMode.READ_ONLY, 0, HEADER_BYTES.toLong())
                .order(ByteOrder.LITTLE_ENDIAN)
            require(header.int == MAGIC && header.int == VERSION) { "Unsupported spatial scene cache" }
            val imageWidth = header.int
            val imageHeight = header.int
            val pointCount = header.int
            val gridWidth = header.int
            val gridHeight = header.int
            val focal = header.float
            val focus = header.float
            require(
                imageWidth > 0 && imageHeight > 0 &&
                    gridWidth in 2..1536 && gridHeight in 2..1536 &&
                    pointCount == gridWidth * gridHeight,
            ) { "Invalid spatial scene metadata" }
            val valueCount = pointCount.toLong() * FLOATS_PER_POINT
            val dataBytes = valueCount * Float.SIZE_BYTES
            require(input.length() == HEADER_BYTES + dataBytes) { "Spatial scene has an unexpected size" }
            val mapped = input.channel.map(FileChannel.MapMode.READ_ONLY, HEADER_BYTES.toLong(), dataBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
            val vertices = FloatArray(valueCount.toInt())
            val values = mapped.asFloatBuffer()
            values.get(vertices)
            return SpatialDepthScene(
                vertices = vertices,
                pointCount = pointCount,
                focalLengthPx = focal,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                gridWidth = gridWidth,
                gridHeight = gridHeight,
                focusDepth = focus,
            )
        }
    }

    fun write(file: File, scene: SpatialDepthScene) {
        require(scene.vertices.size == scene.pointCount * FLOATS_PER_POINT)
        file.parentFile?.mkdirs()
        FileOutputStream(file).channel.use { channel ->
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(MAGIC)
            header.putInt(VERSION)
            header.putInt(scene.imageWidth)
            header.putInt(scene.imageHeight)
            header.putInt(scene.pointCount)
            header.putInt(scene.gridWidth)
            header.putInt(scene.gridHeight)
            header.putFloat(scene.focalLengthPx)
            header.putFloat(scene.focusDepth)
            header.flip()
            channel.write(header)

            val buffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            fun write(values: FloatArray) {
                values.forEach { value ->
                    if (buffer.remaining() < Float.SIZE_BYTES) {
                        buffer.flip()
                        while (buffer.hasRemaining()) channel.write(buffer)
                        buffer.clear()
                    }
                    buffer.putFloat(value)
                }
            }
            write(scene.vertices)
            buffer.flip()
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }
}
