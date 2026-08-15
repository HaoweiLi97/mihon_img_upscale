package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

/**
 * This class is used to provide the directories where the downloads should be saved.
 * It uses one of the following path schemes:
 * /<root downloads dir>/<source name>/<manga>/<chapter>
 * /<root downloads dir>/<category>/<source name>/<manga>/<chapter>
 *
 * @param context the application context.
 */
class DownloadProvider(
    private val context: Context,
    private val storageManager: StorageManager = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
) {

    private val downloadsDir: UniFile?
        get() = storageManager.getDownloadsDirectory()

    /**
     * Returns the download directory for a manga. For internal use only.
     *
     * @param manga the manga to query.
     * @param source the source of the manga.
     */
    internal suspend fun getMangaDir(manga: Manga, source: Source): Result<UniFile> {
        val downloadsDir = downloadsDir
        if (downloadsDir == null) {
            logcat(LogPriority.ERROR) { "Failed to create download directory" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_download_directory)),
            )
        }

        val parentDir = if (downloadPreferences.organizeDownloadsByCategory().get()) {
            val categoryDirName = getMangaCategoryDirName(manga)
            downloadsDir.createDirectory(categoryDirName) ?: return Result.failure(
                createDirectoryException(downloadsDir, categoryDirName, "category"),
            )
        } else {
            downloadsDir
        }

        val sourceDirName = getSourceDirName(source)
        val sourceDir = parentDir.createDirectory(sourceDirName)
        if (sourceDir == null) {
            val displayablePath = parentDir.displayablePath + "/$sourceDirName"
            logcat(LogPriority.ERROR) { "Failed to create source download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        val mangaDirName = getMangaDirName(manga.title)
        val mangaDir = sourceDir.createDirectory(mangaDirName)
        if (mangaDir == null) {
            val displayablePath = sourceDir.displayablePath + "/$mangaDirName"
            logcat(LogPriority.ERROR) { "Failed to create manga download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        return Result.success(mangaDir)
    }

    suspend fun getMangaCategoryDirName(manga: Manga): String {
        val categoryName = getCategories.await(manga.id)
            .minWithOrNull(compareBy({ it.order }, { it.id }))
            ?.name
            ?: context.stringResource(MR.strings.label_default)
        return getCategoryDirName(categoryName)
    }

    private fun createDirectoryException(parent: UniFile, name: String, type: String): IOException {
        val displayablePath = parent.displayablePath + "/$name"
        logcat(LogPriority.ERROR) { "Failed to create $type download directory: $displayablePath" }
        return IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath))
    }

    /**
     * Returns the download directory for a source if it exists.
     *
     * @param source the source to query.
     */
    fun findSourceDir(source: Source): UniFile? {
        return findSourceDirs(source).firstOrNull()
    }

    /**
     * Returns all source directories from both the legacy and category-based layouts.
     */
    fun findSourceDirs(source: Source): List<UniFile> {
        val downloadsDir = downloadsDir ?: return emptyList()
        val sourceDirName = getSourceDirName(source)
        return buildList {
            downloadsDir.findFile(sourceDirName)?.takeIf { it.isDirectory }?.let(::add)
            downloadsDir.listFiles().orEmpty()
                .asSequence()
                .filter { it.isDirectory }
                .mapNotNull { it.findFile(sourceDirName) }
                .filter { it.isDirectory }
                .forEach(::add)
        }.distinctBy { it.uri }
    }

    /**
     * Returns the download directory for a manga if it exists.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    fun findMangaDir(mangaTitle: String, source: Source): UniFile? {
        return findMangaDirs(mangaTitle, source).firstOrNull()
    }

    /**
     * Returns all manga directories from both the legacy and category-based layouts.
     */
    fun findMangaDirs(mangaTitle: String, source: Source): List<UniFile> {
        val mangaDirName = getMangaDirName(mangaTitle)
        return findSourceDirs(source)
            .mapNotNull { it.findFile(mangaDirName) }
            .filter { it.isDirectory }
            .distinctBy { it.uri }
    }

    /**
     * Returns the download directory for a chapter if it exists.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the chapter.
     */
    fun findChapterDir(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        source: Source,
    ): UniFile? {
        return findMangaDirs(mangaTitle, source).asSequence()
            .flatMap { mangaDir ->
                getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl).asSequence()
                    .mapNotNull { mangaDir.findFile(it) }
            }
            .firstOrNull()
    }

    /**
     * Returns a list of downloaded directories for the chapters that exist.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun findChapterDirs(chapters: List<Chapter>, manga: Manga, source: Source): Pair<List<UniFile>, List<UniFile>> {
        val mangaDirs = findMangaDirs(manga.title, source)
        val chapterDirs = mangaDirs.flatMap { mangaDir ->
            chapters.mapNotNull { chapter ->
                getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).asSequence()
                    .mapNotNull { mangaDir.findFile(it) }
                    .firstOrNull()
            }
        }
        return mangaDirs to chapterDirs
    }

    fun getCategoryDirName(categoryName: String): String {
        return DiskUtil.buildValidFilename(
            categoryName,
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames().get(),
        )
    }

    /**
     * Returns the download directory name for a source.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: Source): String {
        return DiskUtil.buildValidFilename(
            source.toString(),
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames().get(),
        )
    }

    /**
     * Returns the download directory name for a manga.
     *
     * @param mangaTitle the title of the manga to query.
     */
    fun getMangaDirName(mangaTitle: String): String {
        return DiskUtil.buildValidFilename(
            mangaTitle,
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames().get(),
        )
    }

    /**
     * Returns the chapter directory name for a chapter.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query.
     * @param chapterUrl url of the chapter to query.
     */
    fun getChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAsciiFilenames: Boolean = libraryPreferences.disallowNonAsciiFilenames().get(),
    ): String {
        return buildChapterDirName(
            chapterName = chapterName,
            chapterScanlator = chapterScanlator,
            disallowNonAsciiFilenames = disallowNonAsciiFilenames,
            reserveBytes = 4,
        )
    }

    /**
     * Returns list of names that might have been previously used as
     * the directory name for a chapter.
     * Add to this list if naming pattern ever changes.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query.
     * @param chapterUrl url of the chapter to query.
     */
    private fun getLegacyChapterDirNames(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
    ): List<String> {
        val chapterNameV1 = buildChapterDirName(
            chapterName = chapterName,
            chapterScanlator = chapterScanlator,
            disallowNonAsciiFilenames = false,
        )
        val hashedChapterDirName = buildHashedChapterDirName(
            chapterName = chapterName,
            chapterScanlator = chapterScanlator,
            chapterUrl = chapterUrl,
            disallowNonAsciiFilenames = libraryPreferences.disallowNonAsciiFilenames().get(),
        )

        // Get the filename that would be generated if the user were
        // using the other value for the disallow non-ASCII
        // filenames setting. This ensures that chapters downloaded
        // before the user changed the setting can still be found.
        val otherChapterDirName =
            getChapterDirName(
                chapterName,
                chapterScanlator,
                chapterUrl,
                !libraryPreferences.disallowNonAsciiFilenames().get(),
            )
        val otherHashedChapterDirName = buildHashedChapterDirName(
            chapterName = chapterName,
            chapterScanlator = chapterScanlator,
            chapterUrl = chapterUrl,
            disallowNonAsciiFilenames = !libraryPreferences.disallowNonAsciiFilenames().get(),
        )

        return buildList(4) {
            add(chapterNameV1)
            add(otherChapterDirName)
            add(hashedChapterDirName)
            add(otherHashedChapterDirName)
        }
    }

    private fun buildChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        disallowNonAsciiFilenames: Boolean,
        reserveBytes: Int = 0,
    ): String {
        var dirName = sanitizeChapterName(chapterName)
        if (!chapterScanlator.isNullOrBlank()) {
            dirName = chapterScanlator + "_" + dirName
        }
        return DiskUtil.buildValidFilename(
            dirName,
            DiskUtil.MAX_FILE_NAME_BYTES - reserveBytes,
            disallowNonAsciiFilenames,
        )
    }

    private fun buildHashedChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAsciiFilenames: Boolean,
    ): String {
        val baseName = buildChapterDirName(
            chapterName = chapterName,
            chapterScanlator = chapterScanlator,
            disallowNonAsciiFilenames = disallowNonAsciiFilenames,
            reserveBytes = 11,
        )
        return "${baseName}_${md5(chapterUrl).take(6)}"
    }

    /**
     * Return the new name for the chapter (in case it's empty or blank)
     *
     * @param chapterName the name of the chapter
     */
    private fun sanitizeChapterName(chapterName: String): String {
        return chapterName.ifBlank {
            "Chapter"
        }
    }

    fun isChapterDirNameChanged(oldChapter: Chapter, newChapter: Chapter): Boolean {
        return getChapterDirName(oldChapter.name, oldChapter.scanlator, oldChapter.url) !=
            getChapterDirName(newChapter.name, newChapter.scanlator, newChapter.url)
    }

    /**
     * Returns valid downloaded chapter directory names.
     *
     * @param chapter the domain chapter object.
     */
    fun getValidChapterDirNames(chapterName: String, chapterScanlator: String?, chapterUrl: String): List<String> {
        val chapterDirName = getChapterDirName(chapterName, chapterScanlator, chapterUrl)
        val legacyChapterDirNames = getLegacyChapterDirNames(chapterName, chapterScanlator, chapterUrl)

        return buildList {
            // Folder of images
            add(chapterDirName)
            // Archived chapters
            add("$chapterDirName.cbz")

            // any legacy names
            legacyChapterDirNames.forEach {
                add(it)
                add("$it.cbz")
            }
        }
    }

    fun chapterOutputName(chapter: Chapter, saveAsCbz: Boolean): String {
        val baseName = getChapterDirName(chapter.name, chapter.scanlator, chapter.url)
        return if (saveAsCbz) "$baseName.cbz" else baseName
    }
}
