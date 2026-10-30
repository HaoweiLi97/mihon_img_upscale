package tachiyomi.source.local.io

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences

@Inject
@SingleIn(AppScope::class)
class LocalSourceFileSystem(
    private val storageManager: StorageManager,
    private val storagePreferences: StoragePreferences,
    private val context: Context,
) {

    fun getBrowserDirectory(): UniFile? {
        val uri = storagePreferences.localComicDirectory.get()
        return if (uri.isBlank()) getBaseDirectory() else UniFile.fromUri(context, uri.toUri())
    }

    fun resolveBook(url: String): UniFile? {
        if (!LocalBook.isBook(url)) return null
        return UniFile.fromUri(context, url.removePrefix(LocalBook.PREFIX).toUri())
            ?.takeIf { it.exists() && it.canRead() }
    }

    fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
    }

    fun getMangaDirectory(name: String): UniFile? {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
    }

    fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getMangaDirectory(name)?.listFiles().orEmpty().toList()
    }
}
