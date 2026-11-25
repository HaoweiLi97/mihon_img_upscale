package eu.kanade.tachiyomi.data.download

import ca.mpreg.imagedecoder.ImageDecoder
import com.hippo.unifile.UniFile
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class CstrCodec(val id: Int, val extension: String) {
    WEBP(1, "webp"),
    AVIF(2, "avif"),
    PNG(3, "png"),
    JPEG(4, "jpg"),
}

internal data class CstrOriginalImage(val codec: CstrCodec, val bytes: ByteArray)

internal fun UniFile.readCstrOriginal(width: Int, height: Int): CstrOriginalImage? {
    val size = length()
    if (size !in 1..CSTR_MAX_CHUNK_SIZE.toLong() || !fitsCstrImage(width, height)) return null
    val type = openInputStream().use { input ->
        try {
            ImageDecoder.open(input).use { decoder ->
                if (decoder.width != width || decoder.height != height) return null
                val format = decoder.format
                if (decoder.pages > 1) throw IOException("CSTR requires a single-frame image: $name")
                format
            }
        } catch (error: RuntimeException) {
            return null
        }
    }
    val codec = when (type) {
        ImageDecoder.Format.WEBP -> CstrCodec.WEBP
        ImageDecoder.Format.AVIF -> CstrCodec.AVIF
        ImageDecoder.Format.PNG -> CstrCodec.PNG
        ImageDecoder.Format.JPEG -> CstrCodec.JPEG
        else -> return null
    }
    val bytes = ByteArray(size.toInt())
    DataInputStream(openInputStream()).use { input ->
        input.readFully(bytes)
        if (input.read() != -1) throw IOException("CSTR input size changed: $name")
    }
    val data = ByteBuffer.wrap(bytes)
    val canKeepOriginal = when (codec) {
        CstrCodec.WEBP -> data.limit() > 20 &&
            data.order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong() + 8 == size &&
            !(bytes.copyOfRange(12, 16).decodeToString() == "VP8X" && bytes[20].toInt() and 2 != 0)
        CstrCodec.AVIF -> data.canKeepAvif()
        CstrCodec.PNG -> data.isStillPng()
        CstrCodec.JPEG -> true
    }
    return CstrOriginalImage(codec, bytes).takeIf { canKeepOriginal }
}

private fun ByteBuffer.isStillPng(): Boolean {
    var offset = 8
    while (limit() - offset >= 12) {
        val length = getInt(offset)
        if (length < 0 || length > limit() - offset - 12) return false
        when (getInt(offset + 4)) {
            pngAnimationControl -> return false
            pngImageData -> return true
        }
        offset += length + 12
    }
    return false
}

private fun ByteBuffer.canKeepAvif(): Boolean {
    var stillBrand = false
    var hasExtents = false
    return visitBoxes(0, limit()) { type, start, end ->
        when (type) {
            avifFileType -> {
                if (end - start < 8 || (end - start) % 4 != 0) return@visitBoxes false
                stillBrand = getInt(start) == avifBrand || (start + 8 until end step 4).any { getInt(it) == avifBrand }
                true
            }
            avifMovie -> false
            avifMeta -> end - start >= 4 && visitBoxes(start + 4, end) { metaType, child, childEnd ->
                metaType != avifItemProperties || visitBoxes(child, childEnd) { itemType, container, containerEnd ->
                    itemType != avifPropertyContainer || visitBoxes(container, containerEnd) { property, begin, limit ->
                        when (property) {
                            avifCleanAperture -> false
                            avifSpatialExtents -> {
                                val valid = limit - begin == 12 && getInt(begin) == 0 &&
                                    fitsCstrImage(getInt(begin + 4), getInt(begin + 8))
                                hasExtents = hasExtents || valid
                                valid
                            }
                            else -> true
                        }
                    }
                }
            }
            else -> true
        }
    } && stillBrand && hasExtents
}

// ISO BMFF boxes may use a 32-bit size, a 64-bit extended size, or extend to the parent boundary.
private inline fun ByteBuffer.visitBoxes(start: Int, end: Int, visit: (Int, Int, Int) -> Boolean): Boolean {
    var offset = start
    while (end - offset >= 8) {
        val sizeField = getInt(offset).toLong() and 0xFFFFFFFFL
        val headerSize = if (sizeField == 1L) 16 else 8
        if (end - offset < headerSize) return false
        val size = when (sizeField) {
            0L -> (end - offset).toLong()
            1L -> getLong(offset + 8)
            else -> sizeField
        }
        if (size < headerSize || size > end - offset) return false
        val next = offset + size.toInt()
        if (!visit(getInt(offset + 4), offset + headerSize, next)) return false
        offset = next
    }
    return offset == end
}

private fun fourCc(value: String) = ByteBuffer.wrap(value.encodeToByteArray()).int

private val pngAnimationControl = fourCc("acTL")
private val pngImageData = fourCc("IDAT")
private val avifFileType = fourCc("ftyp")
private val avifBrand = fourCc("avif")
private val avifMovie = fourCc("moov")
private val avifMeta = fourCc("meta")
private val avifItemProperties = fourCc("iprp")
private val avifPropertyContainer = fourCc("ipco")
private val avifCleanAperture = fourCc("clap")
private val avifSpatialExtents = fourCc("ispe")
