package eu.kanade.tachiyomi.util.image

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import ca.mpreg.imagedecoder.ImageDecoder
import java.io.InputStream

/** Bitmap bridge for enhancement callers that share the upstream native decoder. */
internal class EnhancementImageDecoder private constructor(private val decoder: ImageDecoder) {
    val width: Int get() = decoder.width
    val height: Int get() = decoder.height

    fun decode(sampleSize: Int = 1): Bitmap = decoder.decodeNext().use { frame ->
        val config = if (decoder.isHdr) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
        var bitmap = createBitmap(frame.width, frame.height, config)
        try {
            frame.image.rewind()
            bitmap.copyPixelsFromBuffer(frame.image)
            // Enhancement JNI consumes 8-bit RGBA; the regular reader keeps the upstream HDR path.
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                val converted = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                bitmap.recycle()
                bitmap = converted
            }
            if (sampleSize <= 1) {
                bitmap
            } else {
                bitmap.scale((frame.width / sampleSize).coerceAtLeast(1), (frame.height / sampleSize).coerceAtLeast(1))
                    .also { if (it !== bitmap) bitmap.recycle() }
            }
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    fun recycle() = decoder.close()

    companion object {
        fun open(stream: InputStream): EnhancementImageDecoder = EnhancementImageDecoder(ImageDecoder.open(stream))
    }
}
