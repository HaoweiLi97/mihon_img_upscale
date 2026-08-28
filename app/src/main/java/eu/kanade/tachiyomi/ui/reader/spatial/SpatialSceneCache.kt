package eu.kanade.tachiyomi.ui.reader.spatial

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.File

class SpatialSceneCache(context: Context) {
    private val root = File(context.cacheDir, "depth-spatial-scenes/v27-hologram-edges")

    fun sceneFile(page: ReaderPage): File {
        val chapterId = page.chapter.chapter.id
        val mangaId = page.chapter.chapter.manga_id
        val variant = page.enhancementKeySuffix
            .ifBlank { "full" }
            .replace(UNSAFE_FILE_CHARS, "_")
        return File(root, "$mangaId/$chapterId/${page.index}-$variant.d3ds")
    }

    fun cachedScene(page: ReaderPage): File? = sceneFile(page).takeIf { it.isFile && it.length() > 1024L }

    fun prepare(page: ReaderPage) {
        sceneFile(page).parentFile?.mkdirs()
    }

    private companion object {
        val UNSAFE_FILE_CHARS = Regex("[^a-zA-Z0-9._-]")
    }
}
