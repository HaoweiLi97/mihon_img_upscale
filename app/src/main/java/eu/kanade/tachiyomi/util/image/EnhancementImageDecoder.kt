package eu.kanade.tachiyomi.util.image

import android.graphics.Bitmap
import ca.mpreg.imagedecoder.ImageDecoder
import java.io.InputStream

/** Bitmap bridge for enhancement callers that share the upstream native decoder. */
internal class EnhancementImageDecoder private constructor(private val decoder: ImageDecoder) {
    val width: Int get() = decoder.width
    val height: Int get() = decoder.height

    fun decode(sampleSize: Int = 1): Bitmap {
        // Sampling happens inside the codec, without allocating a full native frame
        // and then a second full bitmap before resizing it.
        var bitmap = decoder.decodeToBitmap(null, sampleSize.coerceAtLeast(1))
        try {
            // Enhancement JNI consumes 8-bit RGBA; the regular reader keeps the upstream HDR path.
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                val converted = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                bitmap.recycle()
                bitmap = converted
            }
            return bitmap
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
