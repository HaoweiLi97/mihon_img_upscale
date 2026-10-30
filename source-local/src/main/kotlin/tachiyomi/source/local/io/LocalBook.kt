package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.ImageUtil

/** URI based identities keep saved books stable when the browser's root is changed. */
object LocalBook {
    const val PREFIX = "local-file:"

    fun isBook(url: String): Boolean = url.startsWith(PREFIX)

    fun url(file: UniFile): String = PREFIX + file.uri.toString()

    fun isImage(file: UniFile): Boolean = !file.isDirectory && isImageName(file.name.orEmpty())

    fun isImageName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in imageExtensions
    }

    fun isSupported(file: UniFile): Boolean = file.isDirectory || isImage(file) || isComic(file)

    fun isComic(file: UniFile): Boolean = !file.isDirectory &&
        (Archive.isSupported(file) || file.extension.equals("epub", true))

    fun visibleFiles(files: List<UniFile>): List<UniFile> = files
        .filter { !it.name.orEmpty().startsWith('.') && isSupported(it) }
        .sortedWith { first, second ->
            when {
                first.isDirectory != second.isDirectory -> if (first.isDirectory) -1 else 1
                else -> first.name.orEmpty().compareToCaseInsensitiveNaturalOrder(second.name.orEmpty())
            }
        }

    fun images(files: List<UniFile>): List<UniFile> = visibleFiles(files).filter(::isImage)

    private val imageExtensions = ImageUtil.ImageType.entries.map { it.extension }.toSet() +
        setOf("jpeg", "jpe", "heic")
}
