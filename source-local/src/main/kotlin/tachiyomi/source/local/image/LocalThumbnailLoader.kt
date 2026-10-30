package tachiyomi.source.local.image

import android.content.Context
import android.graphics.Bitmap
import ca.mpreg.imagedecoder.ImageDecoder
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.source.local.io.LocalBook
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Only visible tiles request covers. Serialize decoding to bound archive handles and bitmap memory. */
@Inject
@SingleIn(AppScope::class)
class LocalThumbnailLoader(private val context: Context) {
    private val directory by lazy { File(context.cacheDir, "local-thumbnails").apply { mkdirs() } }

    @Synchronized
    fun load(file: UniFile): File? = try {
        val source = findCover(file, depth = 0)
        if (source == null) {
            null
        } else {
            val identity = "${source.uri}:${source.lastModified()}:${source.length()}"
            val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val target = File(directory, "$key.jpg")
            if (target.length() > 0) {
                target.setLastModified(System.currentTimeMillis())
                target
            } else {
                val bitmap = readCover(source) ?: return null
                try {
                    val temporary = File(directory, "$key.tmp")
                    try {
                        temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                        check(temporary.renameTo(target))
                    } finally {
                        temporary.delete()
                    }
                } finally {
                    bitmap.recycle()
                }
                trimCache()
                target
            }
        }
    } catch (e: Exception) {
        logcat(throwable = e) { "Unable to load local thumbnail for ${file.name}" }
        null
    }

    private fun findCover(file: UniFile, depth: Int): UniFile? {
        if (!file.isDirectory) return file
        val children = LocalBook.visibleFiles(file.listFiles()?.toList().orEmpty())
        return children.firstOrNull { LocalBook.isImage(it) && it.nameWithoutExtension.equals("cover", true) }
            ?: children.firstOrNull(LocalBook::isImage)
            ?: children.firstOrNull(LocalBook::isComic)
            ?: if (depth < 2) {
                children.filter { it.isDirectory }.take(8).firstNotNullOfOrNull { findCover(it, depth + 1) }
            } else {
                null
            }
    }

    private fun readCover(file: UniFile): Bitmap? = when {
        LocalBook.isImage(file) -> file.openInputStream().use(::decode)
        file.extension.equals("epub", true) -> file.epubReader(context).use { epub ->
            epub.getImagesFromPages().firstOrNull()?.let { epub.getInputStream(it)?.use(::decode) }
        }
        else -> file.archiveReader(context).use { reader ->
            val entry = reader.useEntries { entries ->
                entries.filter { it.isFile && LocalBook.isImageName(it.name) }
                    .sortedWith { first, second -> first.name.compareToCaseInsensitiveNaturalOrder(second.name) }
                    .firstOrNull()
            }
            entry?.let { reader.getInputStream(it.name)?.use(::decode) }
        }
    }

    private fun decode(stream: InputStream): Bitmap = ImageDecoder.open(stream).use { decoder ->
        // Decode at thumbnail resolution, including the reader's AVIF/JXL/HEIF codecs.
        var sample = 1
        while (maxOf(decoder.width, decoder.height) / sample > 640) sample *= 2
        decoder.decodeToBitmap(null, sample)
    }

    private fun trimCache() {
        val files = directory.listFiles().orEmpty().sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= 64L * 1024 * 1024) break
            val length = file.length()
            if (file.delete()) size -= length
        }
    }
}
