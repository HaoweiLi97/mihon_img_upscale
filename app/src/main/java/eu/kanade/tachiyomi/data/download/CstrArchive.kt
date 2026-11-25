package eu.kanade.tachiyomi.data.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import ca.mpreg.imagedecoder.ImageDecoder
import com.hippo.unifile.UniFile
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

private const val HEADER_SIZE = 32
private const val PAGE_SIZE = 16
private const val CHUNK_SIZE = 20
private const val THUMBNAIL_RECORD_SIZE = 16
private const val FLAG_THUMBNAILS = 1
private const val MAX_THUMBNAIL_DIMENSION = 512
private const val MAX_THUMBNAIL_SIZE = 256 * 1024
private const val MAX_INDEX_SIZE = 8 * 1024 * 1024
private const val MAX_METADATA_SIZE = 256 * 1024
internal const val CSTR_MAX_CHUNK_SIZE = 32 * 1024 * 1024
private const val MAX_CHUNK_PIXELS = 32 * 1024 * 1024
private const val WEBP_MAX_DIMENSION = 16383
private const val WEBP_QUALITY = 85
private const val THUMBNAIL_QUALITY = 72
private val MAGIC = byteArrayOf(0x43, 0x53, 0x54, 0x52)

private data class CstrPage(val width: Int, val height: Int, val firstChunk: Int, val chunkCount: Int)
private data class CstrChunk(val height: Int, val codec: CstrCodec, val offset: Long, val size: Int)
private data class CstrThumbnail(val width: Int, val height: Int, val offset: Long, val size: Int)
private data class EncodedThumbnail(val width: Int, val height: Int, val bytes: ByteArray)

internal fun fitsCstrImage(width: Int, height: Int): Boolean =
    width in 1..WEBP_MAX_DIMENSION && height in 1..WEBP_MAX_DIMENSION && width.toLong() * height <= MAX_CHUNK_PIXELS

internal suspend fun writeCstrArchive(
    cacheDir: File,
    destination: UniFile,
    images: List<UniFile>,
    title: String,
    comicInfo: UniFile?,
) {
    val pageTablesSize = images.size.toLong() * (PAGE_SIZE + THUMBNAIL_RECORD_SIZE)
    requireCstr(images.isNotEmpty() && pageTablesSize + images.size.toLong() * CHUNK_SIZE <= MAX_INDEX_SIZE)
    val metadata = JSONObject().put("title", title).apply {
        comicInfo?.openInputStream()?.bufferedReader()?.use { put("comicInfo", it.readText()) }
    }.toString().toByteArray(Charsets.UTF_8)
        .takeIf { it.size <= MAX_METADATA_SIZE } ?: ByteArray(0)
    val pages = mutableListOf<CstrPage>()
    val chunks = mutableListOf<CstrChunk>()
    val thumbnails = mutableListOf<CstrThumbnail>()
    var offset = 0L
    var thumbnailOffset = 0L
    val payload = File.createTempFile("cstr-body-", ".tmp", cacheDir)
    var thumbnailFile: File? = null
    try {
        val previews = File.createTempFile("cstr-thumbnails-", ".tmp", cacheDir).also { thumbnailFile = it }
        payload.outputStream().buffered().use { output ->
            previews.outputStream().buffered().use { previewOutput ->
                for (image in images) {
                    coroutineContext.ensureActive()
                    val firstChunk = chunks.size
                    val (width, height) = encodeCstrImage(
                        image,
                        writeThumbnail = { thumbnail ->
                            thumbnails += CstrThumbnail(thumbnail.width, thumbnail.height, thumbnailOffset, thumbnail.bytes.size)
                            previewOutput.write(thumbnail.bytes)
                            thumbnailOffset += thumbnail.bytes.size
                        },
                        writeChunk = { height, codec, bytes ->
                            requireCstr(pageTablesSize + (chunks.size + 1L) * CHUNK_SIZE <= MAX_INDEX_SIZE)
                            chunks += CstrChunk(height, codec, offset, bytes.size)
                            output.write(bytes)
                            offset += bytes.size
                        },
                    )
                    pages += CstrPage(width, height, firstChunk, chunks.size - firstChunk)
                }
            }
        }

        val indexSize = pageTablesSize.toInt() + chunks.size * CHUNK_SIZE
        val index = ByteBuffer.allocate(HEADER_SIZE + indexSize).order(ByteOrder.LITTLE_ENDIAN)
        index.put(MAGIC).putShort(1).putShort(FLAG_THUMBNAILS.toShort())
            .putInt(pages.size).putInt(chunks.size).putLong(HEADER_SIZE.toLong())
            .putInt(indexSize).putInt(metadata.size)
        pages.forEach { page ->
            index.putInt(page.width).putInt(page.height).putInt(page.firstChunk).putInt(page.chunkCount)
        }
        chunks.forEach { chunk ->
            index.putInt(chunk.height).putInt(chunk.codec.id).putLong(chunk.offset).putInt(chunk.size)
        }
        thumbnails.forEach { thumbnail ->
            index.putShort(thumbnail.width.toShort()).putShort(thumbnail.height.toShort())
                .putInt(thumbnail.size).putLong(thumbnail.offset)
        }
        destination.openOutputStream().buffered().use { output ->
            output.write(index.array())
            output.write(metadata)
            payload.copyCstrDataTo(output)
            previews.copyCstrDataTo(output)
        }
    } finally {
        payload.delete()
        thumbnailFile?.delete()
    }
}

private suspend fun File.copyCstrDataTo(output: OutputStream) {
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }
}

@Suppress("DEPRECATION")
private suspend fun encodeCstrImage(
    image: UniFile,
    writeThumbnail: (EncodedThumbnail) -> Unit,
    writeChunk: (Int, CstrCodec, ByteArray) -> Unit,
): Pair<Int, Int> {
    val orientation = try {
        image.openInputStream().use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val transform = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    return image.openInputStream().use { input ->
        val decoder = if (transform.isIdentity) {
            try {
                BitmapRegionDecoder.newInstance(input, false)
            } catch (_: IOException) {
                null
            }
        } else {
            null
        }
        val bitmap = if (decoder == null) {
            val original = image.openInputStream().use { BitmapFactory.decodeStream(it) }
                ?: image.openInputStream().use { ImageDecoder.open(it).use { decoder -> decoder.decodeToBitmap() } }
            try {
                Bitmap.createBitmap(original, 0, 0, original.width, original.height, transform, true).also {
                    if (it !== original) original.recycle()
                }
            } catch (error: Throwable) {
                original.recycle()
                throw error
            }
        } else {
            null
        }

        try {
            val width = decoder?.width ?: checkNotNull(bitmap).width
            val height = decoder?.height ?: checkNotNull(bitmap).height
            requireCstr(width in 1..WEBP_MAX_DIMENSION && height > 0)
            writeThumbnail(encodeThumbnail(width, height, decoder, bitmap))
            // Android reports an undefined orientation for images without an EXIF orientation tag.
            val original = if (transform.isIdentity) image.readCstrOriginal(width, height) else null
            if (original != null) {
                writeChunk(height, original.codec, original.bytes)
                return@use width to height
            }
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var step = minOf(WEBP_MAX_DIMENSION, MAX_CHUNK_PIXELS / width)
            var y = 0
            while (y < height) {
                coroutineContext.ensureActive()
                val stripHeight = minOf(step, height - y)
                val strip = if (decoder != null) {
                    decoder.decodeRegion(Rect(0, y, width, y + stripHeight), options)
                        ?: throw IOException("Unable to decode CSTR page region ${image.name}")
                } else {
                    Bitmap.createBitmap(checkNotNull(bitmap), 0, y, width, stripHeight)
                }
                val bytes = try {
                    strip.encodeWebp(WEBP_QUALITY)
                } finally {
                    if (strip !== bitmap) strip.recycle()
                }
                if (bytes.size > CSTR_MAX_CHUNK_SIZE && stripHeight > 1) {
                    step = stripHeight / 2
                    continue
                }
                requireCstr(bytes.size in 1..CSTR_MAX_CHUNK_SIZE)
                writeChunk(stripHeight, CstrCodec.WEBP, bytes)
                y += stripHeight
            }
            width to height
        } finally {
            decoder?.recycle()
            bitmap?.recycle()
        }
    }
}

@Suppress("DEPRECATION")
private fun encodeThumbnail(
    width: Int,
    height: Int,
    decoder: BitmapRegionDecoder?,
    bitmap: Bitmap?,
): EncodedThumbnail {
    val cropWidth = minOf(width.toLong(), height * 2L).toInt()
    val cropHeight = minOf(height.toLong(), width * 2L).toInt()
    val left = (width - cropWidth) / 2
    val scale = minOf(1.0, maxOf(160.0 / cropWidth, 224.0 / cropHeight))
    val targetWidth = ceil(cropWidth * scale).toInt()
    val targetHeight = ceil(cropHeight * scale).toInt()
    val crop = if (decoder != null) {
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit(minOf(cropWidth / targetWidth, cropHeight / targetHeight))
        }
        decoder.decodeRegion(Rect(left, 0, left + cropWidth, cropHeight), options)
            ?: throw IOException("Unable to decode CSTR thumbnail region")
    } else {
        Bitmap.createBitmap(checkNotNull(bitmap), left, 0, cropWidth, cropHeight)
    }
    try {
        val thumbnail = Bitmap.createScaledBitmap(crop, targetWidth, targetHeight, true)
        try {
            val bytes = thumbnail.encodeWebp(THUMBNAIL_QUALITY)
            requireCstr(bytes.size in 1..MAX_THUMBNAIL_SIZE)
            return EncodedThumbnail(targetWidth, targetHeight, bytes)
        } finally {
            if (thumbnail !== crop) thumbnail.recycle()
        }
    } finally {
        if (crop !== bitmap) crop.recycle()
    }
}

@Suppress("DEPRECATION")
private fun Bitmap.encodeWebp(quality: Int): ByteArray {
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.WEBP
    }
    return ByteArrayOutputStream().apply {
        if (!compress(format, quality, this)) throw IOException("Unable to encode CSTR WebP")
    }.toByteArray()
}

internal class CstrArchive(private val file: UniFile) {
    private val pages: List<CstrPage>
    private val chunks: List<CstrChunk>
    private val payloadStart: Long
    private val thumbnails: List<CstrThumbnail>

    val pageCount: Int get() = pages.size

    init {
        val fileSize = file.length()
        DataInputStream(file.openInputStream().buffered()).use { input ->
            val header = input.readBuffer(HEADER_SIZE)
            requireCstr(MAGIC.indices.all { header.get() == MAGIC[it] })
            requireCstr(header.short.toInt() == 1)
            val flags = header.short.toInt()
            requireCstr(flags and FLAG_THUMBNAILS.inv() == 0)
            val hasThumbnails = flags and FLAG_THUMBNAILS != 0
            val pageCount = header.int
            val chunkCount = header.int
            val indexOffset = header.long
            val indexSize = header.int
            val metaSize = header.int.toLong() and 0xFFFFFFFFL
            val pageIndexSize = PAGE_SIZE + if (hasThumbnails) THUMBNAIL_RECORD_SIZE else 0
            requireCstr(
                pageCount > 0 && chunkCount >= pageCount && indexOffset == HEADER_SIZE.toLong() &&
                    indexSize in 1..MAX_INDEX_SIZE &&
                    indexSize.toLong() == pageCount.toLong() * pageIndexSize + chunkCount.toLong() * CHUNK_SIZE,
            )
            payloadStart = indexOffset + indexSize + metaSize
            requireCstr(payloadStart < fileSize)
            val index = input.readBuffer(indexSize)
            var nextChunk = 0
            pages = List(pageCount) {
                val page = CstrPage(index.int, index.int, index.int, index.int)
                requireCstr(
                    page.width > 0 && page.height > 0 && page.firstChunk == nextChunk &&
                        page.chunkCount in 1..(chunkCount - nextChunk),
                )
                nextChunk += page.chunkCount
                page
            }
            requireCstr(nextChunk == chunkCount)
            var nextOffset = 0L
            chunks = List(chunkCount) {
                val height = index.int
                val codecId = index.int
                val codec = CstrCodec.entries.getOrNull(codecId - 1)
                    ?: throw IOException("Unsupported CSTR image codec: $codecId")
                val chunk = CstrChunk(height, codec, index.long, index.int)
                requireCstr(
                    chunk.height > 0 && chunk.offset == nextOffset &&
                        chunk.size in 1..CSTR_MAX_CHUNK_SIZE,
                )
                nextOffset += chunk.size
                chunk
            }
            val payloadSize = fileSize - payloadStart
            requireCstr(nextOffset <= payloadSize)
            if (hasThumbnails) {
                thumbnails = readThumbnailTable(index, pageCount, payloadStart + nextOffset, payloadSize - nextOffset)
            } else {
                requireCstr(nextOffset == payloadSize)
                thumbnails = emptyList()
            }
            pages.forEach { page ->
                val end = page.firstChunk + page.chunkCount
                val pageChunks = chunks.subList(page.firstChunk, end)
                requireCstr(pageChunks.sumOf { it.height.toLong() } == page.height.toLong())
                requireCstr(pageChunks.all { fitsCstrImage(page.width, it.height) })
            }
        }
    }

    fun pageExtension(index: Int): String {
        val page = pages[index]
        return if (page.chunkCount > 1) "png" else chunks[page.firstChunk].codec.extension
    }

    fun readPreview(index: Int): Bitmap? {
        val bytes = thumbnails.getOrNull(index)?.let { thumbnail ->
            readImageBytes(thumbnail.offset, thumbnail.size).takeIf {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                bounds.outMimeType == "image/webp" && bounds.outWidth == thumbnail.width && bounds.outHeight == thumbnail.height
            }
        }
        if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }

        val page = pages[index]
        val first = chunks[page.firstChunk]
        val firstChunk = readImageBytes(payloadStart + first.offset, first.size)
        val options = BitmapFactory.Options().apply {
            val ratio = maxOf(page.width, first.height) / MAX_THUMBNAIL_DIMENSION
            inSampleSize = Integer.highestOneBit(ratio).coerceAtLeast(1)
        }
        return BitmapFactory.decodeByteArray(firstChunk, 0, firstChunk.size, options)
    }

    private fun readImageBytes(offset: Long, size: Int): ByteArray =
        DataInputStream(file.openInputStream()).use { input ->
            input.skipFully(offset)
            input.readBytes(size)
        }

    fun openPage(index: Int): InputStream {
        val page = pages[index]
        val first = chunks[page.firstChunk]
        return DataInputStream(file.openInputStream()).use { input ->
            input.skipFully(payloadStart + first.offset)
            if (page.chunkCount == 1) {
                input.readBytes(first.size).inputStream()
            } else {
                readChunkedPage(input, page)
            }
        }
    }

    private fun readChunkedPage(input: DataInputStream, page: CstrPage): InputStream {
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            var y = 0
            repeat(page.chunkCount) { index ->
                val chunk = chunks[page.firstChunk + index]
                val bytes = input.readBytes(chunk.size)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                val strip = if (options.outWidth > 0) {
                    requireCstr(options.outWidth == page.width && options.outHeight == chunk.height)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Unable to decode CSTR chunk")
                } else {
                    bytes.inputStream().use {
                        ImageDecoder.open(it).use { decoder ->
                            requireCstr(decoder.width == page.width && decoder.height == chunk.height)
                            decoder.decodeToBitmap()
                        }
                    }
                }
                try {
                    requireCstr(strip.width == page.width && strip.height == chunk.height)
                    canvas.drawBitmap(strip, null, Rect(0, y, page.width, y + chunk.height), null)
                } finally {
                    strip.recycle()
                }
                y += chunk.height
            }
            return ByteArrayOutputStream().apply {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) throw IOException("Unable to read CSTR page")
            }.toByteArray().inputStream()
        } finally {
            bitmap.recycle()
        }
    }
}

private fun readThumbnailTable(index: ByteBuffer, pageCount: Int, start: Long, size: Long): List<CstrThumbnail> {
    var nextOffset = 0L
    val thumbnails = List(pageCount) {
        val width = index.short.toInt() and 0xFFFF
        val height = index.short.toInt() and 0xFFFF
        val length = index.int
        val offset = index.long
        requireCstr(
            width in 1..MAX_THUMBNAIL_DIMENSION && height in 1..MAX_THUMBNAIL_DIMENSION &&
                length in 1..MAX_THUMBNAIL_SIZE && offset == nextOffset && length <= size - nextOffset,
        )
        nextOffset += length
        CstrThumbnail(width, height, start + offset, length)
    }
    requireCstr(nextOffset == size)
    return thumbnails
}

private fun DataInputStream.readBytes(size: Int) = ByteArray(size).also { readFully(it) }

private fun DataInputStream.readBuffer(size: Int) = ByteBuffer.wrap(readBytes(size)).order(ByteOrder.LITTLE_ENDIAN)

private fun InputStream.skipFully(size: Long) {
    var remaining = size
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            if (read() == -1) throw IOException("Truncated CSTR archive")
            remaining--
        }
    }
}

private fun requireCstr(valid: Boolean) {
    if (!valid) throw IOException("Invalid or unsupported CSTR archive")
}
