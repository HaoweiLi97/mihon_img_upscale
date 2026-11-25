package eu.kanade.tachiyomi.data.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import com.hippo.unifile.UniFile
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

private const val HEADER_SIZE = 32
private const val PAGE_SIZE = 16
private const val CHUNK_SIZE = 20
private const val MAX_INDEX_SIZE = 8 * 1024 * 1024
private const val MAX_METADATA_SIZE = 256 * 1024
private const val MAX_CHUNK_SIZE = 32 * 1024 * 1024
private const val MAX_CHUNK_PIXELS = 32 * 1024 * 1024
private const val WEBP_MAX_DIMENSION = 16383
private const val WEBP_CODEC = 1
private const val WEBP_QUALITY = 85
private val MAGIC = byteArrayOf(0x43, 0x53, 0x54, 0x52)

private data class CstrPage(val width: Int, val height: Int, val firstChunk: Int, val chunkCount: Int)
private data class CstrChunk(val height: Int, val codec: Int, val offset: Long, val size: Int)

internal suspend fun writeCstrArchive(
    cacheDir: File,
    destination: UniFile,
    images: List<UniFile>,
    title: String,
    comicInfo: UniFile?,
) {
    requireCstr(images.isNotEmpty() && images.size.toLong() * (PAGE_SIZE + CHUNK_SIZE) <= MAX_INDEX_SIZE)
    val metadata = JSONObject().put("title", title).apply {
        comicInfo?.openInputStream()?.bufferedReader()?.use { put("comicInfo", it.readText()) }
    }.toString().toByteArray(Charsets.UTF_8)
        .takeIf { it.size <= MAX_METADATA_SIZE } ?: ByteArray(0)
    val pages = mutableListOf<CstrPage>()
    val chunks = mutableListOf<CstrChunk>()
    var offset = 0L
    val payload = File.createTempFile("cstr-", ".tmp", cacheDir)
    try {
        payload.outputStream().buffered().use { output ->
            for (image in images) {
                coroutineContext.ensureActive()
                val firstChunk = chunks.size
                val (width, height) = encodeCstrImage(image) { strip, bytes ->
                    requireCstr(images.size.toLong() * PAGE_SIZE + (chunks.size + 1L) * CHUNK_SIZE <= MAX_INDEX_SIZE)
                    chunks += CstrChunk(strip, WEBP_CODEC, offset, bytes.size)
                    output.write(bytes)
                    offset += bytes.size
                }
                pages += CstrPage(width, height, firstChunk, chunks.size - firstChunk)
            }
        }

        val indexSize = pages.size * PAGE_SIZE + chunks.size * CHUNK_SIZE
        val index = ByteBuffer.allocate(HEADER_SIZE + indexSize).order(ByteOrder.LITTLE_ENDIAN)
        index.put(MAGIC).putShort(1).putShort(0)
            .putInt(pages.size).putInt(chunks.size).putLong(HEADER_SIZE.toLong())
            .putInt(indexSize).putInt(metadata.size)
        pages.forEach { page ->
            index.putInt(page.width).putInt(page.height).putInt(page.firstChunk).putInt(page.chunkCount)
        }
        chunks.forEach { chunk ->
            index.putInt(chunk.height).putInt(chunk.codec).putLong(chunk.offset).putInt(chunk.size)
        }
        destination.openOutputStream().buffered().use { output ->
            output.write(index.array())
            output.write(metadata)
            payload.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    } finally {
        payload.delete()
    }
}

@Suppress("DEPRECATION")
private suspend fun encodeCstrImage(image: UniFile, writeChunk: (Int, ByteArray) -> Unit): Pair<Int, Int> {
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
                ?: throw IOException("Unable to decode CSTR page ${image.name}")
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
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
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
                    ByteArrayOutputStream().apply {
                        if (!strip.compress(format, WEBP_QUALITY, this)) throw IOException("Unable to encode CSTR WebP")
                    }.toByteArray()
                } finally {
                    if (strip !== bitmap) strip.recycle()
                }
                if (bytes.size > MAX_CHUNK_SIZE && stripHeight > 1) {
                    step = stripHeight / 2
                    continue
                }
                requireCstr(bytes.size in 1..MAX_CHUNK_SIZE)
                writeChunk(stripHeight, bytes)
                y += stripHeight
            }
            width to height
        } finally {
            decoder?.recycle()
            bitmap?.recycle()
        }
    }
}

internal class CstrArchive(private val file: UniFile) {
    private val pages: List<CstrPage>
    private val chunks: List<CstrChunk>
    private val payloadStart: Long

    val pageCount: Int get() = pages.size

    init {
        val fileSize = file.length()
        DataInputStream(file.openInputStream().buffered()).use { input ->
            val header = input.readBuffer(HEADER_SIZE)
            requireCstr(MAGIC.indices.all { header.get() == MAGIC[it] })
            requireCstr(header.short.toInt() == 1 && header.short.toInt() == 0)
            val pageCount = header.int
            val chunkCount = header.int
            val indexOffset = header.long
            val indexSize = header.int
            val metaSize = header.int.toLong() and 0xFFFFFFFFL
            requireCstr(
                pageCount > 0 && chunkCount >= pageCount && indexOffset == HEADER_SIZE.toLong() &&
                    indexSize in 1..MAX_INDEX_SIZE &&
                    indexSize.toLong() == pageCount.toLong() * PAGE_SIZE + chunkCount.toLong() * CHUNK_SIZE,
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
                val chunk = CstrChunk(index.int, index.int, index.long, index.int)
                requireCstr(
                    chunk.height > 0 && chunk.codec in 1..3 && chunk.offset == nextOffset &&
                        chunk.size in 1..MAX_CHUNK_SIZE,
                )
                nextOffset += chunk.size
                chunk
            }
            requireCstr(nextOffset == fileSize - payloadStart)
            pages.forEach { page ->
                val end = page.firstChunk + page.chunkCount
                val pageChunks = chunks.subList(page.firstChunk, end)
                requireCstr(pageChunks.sumOf { it.height.toLong() } == page.height.toLong())
                requireCstr(pageChunks.all { it.height.toLong() * page.width <= MAX_CHUNK_PIXELS })
            }
        }
    }

    fun pageExtension(index: Int): String {
        val page = pages[index]
        return if (page.chunkCount > 1) {
            "png"
        } else {
            when (chunks[page.firstChunk].codec) {
                WEBP_CODEC -> "webp"
                2 -> "avif"
                else -> "png"
            }
        }
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
                requireCstr(options.outWidth == page.width && options.outHeight == chunk.height)
                val strip = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("Unable to decode CSTR WebP")
                try {
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
