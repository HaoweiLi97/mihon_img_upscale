package eu.kanade.tachiyomi.data.download

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR

/**
 * This class is used to manage chapter downloads in the application. It must be instantiated once
 * and retrieved through dependency injection. You can use this class to queue new chapters or query
 * downloaded chapters.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadManager(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val getCategories: GetCategories,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val sourceManager: SourceManager,
    private val downloadPreferences: DownloadPreferences,
    private val downloader: Downloader,
    private val pendingDeleter: DownloadPendingDeleter,
) {

    val queueState
        get() = downloader.queueState

    val uploadQueueState
        get() = downloader.uploadQueueState

    suspend fun downloaderStart() = downloader.start()
    suspend fun downloaderStop(reason: String? = null) = downloader.stop(reason)
    val isDownloaderRunning
        get() = DownloadWorker.isRunningFlow(context)

    /**
     * Starts the download worker, which runs the downloader.
     */
    fun startDownloads() {
        if (downloader.isRunning) return

        DownloadWorker.start(context)
    }

    /**
     * Tells the downloader to pause downloads.
     */
    suspend fun pauseDownloads() {
        downloader.pause()
    }

    /**
     * Empties the download queue.
     */
    suspend fun clearQueue() {
        downloader.clearQueue()
        downloader.stop()
    }

    /**
     * Returns the download from queue if the chapter is queued for download
     * else it will return null which means that the chapter is not queued for download
     *
     * @param chapterId the chapter to check.
     */
    fun getQueuedDownloadOrNull(chapterId: Long): Download? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    /**
     * Returns the queued downloads by chapter id, for looking up many chapters at once.
     */
    fun getQueuedDownloadsByChapterId(): Map<Long, Download> {
        return queueState.value.associateBy { it.chapter.id }
    }

    suspend fun startDownloadNow(chapterId: Long) {
        // If not in queue try to start a new download
        val toAdd = getQueuedDownloadOrNull(chapterId) ?: downloadFromChapterId(chapterId) ?: return
        downloader.updateQueue { queue ->
            val (existing, others) = queue.partition { it.chapter.id == chapterId }
            listOf(existing.firstOrNull() ?: toAdd) + others
        }
        startDownloads()
    }

    private suspend fun downloadFromChapterId(chapterId: Long): Download? {
        val chapter = getChapter.await(chapterId) ?: return null
        val manga = getManga.await(chapter.mangaId) ?: return null
        val source = sourceManager.get(manga.source) as? HttpSource ?: return null

        return Download(source, manga, chapter)
    }

    /**
     * Reorders the download queue.
     *
     * @param downloads the queue in its new order. Downloads that left the queue since are dropped, and ones
     * that joined it are kept at the end.
     */
    suspend fun reorderQueue(downloads: List<Download>) {
        downloader.updateQueue { queue ->
            val queueById = queue.associateBy { it.chapter.id }
            val orderedIds = downloads.mapTo(HashSet()) { it.chapter.id }
            downloads.mapNotNull { queueById[it.chapter.id] } + queue.filter { it.chapter.id !in orderedIds }
        }
    }

    /**
     * Tells the downloader to enqueue the given list of chapters.
     *
     * @param manga the manga of the chapters.
     * @param chapters the list of chapters to enqueue.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    suspend fun downloadChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean = true) {
        if (downloader.queueChapters(manga, chapters, autoStart)) {
            startDownloads()
        }
    }

    fun isCloudSyncAvailable(): Boolean = downloader.isCloudSyncAvailable()

    internal suspend fun cloudSyncChapters(
        manga: Manga,
        chapters: List<Chapter>,
        autoStart: Boolean = true,
    ): CloudSyncQueueResult {
        return downloader.queueChaptersForCloudSync(manga, chapters, autoStart)
    }

    /**
     * Tells the downloader to enqueue the given list of downloads at the start of the queue.
     *
     * @param downloads the list of downloads to enqueue.
     */
    suspend fun addDownloadsToStartOfQueue(downloads: List<Download>) {
        if (downloads.isEmpty()) return
        downloader.updateQueue { queue ->
            val chapterIds = downloads.mapTo(HashSet()) { it.chapter.id }
            downloads + queue.filter { it.chapter.id !in chapterIds }
        }
        startDownloads()
    }

    /**
     * Builds the page list of a downloaded chapter.
     *
     * @param source the source of the chapter.
     * @param manga the manga of the chapter.
     * @param chapter the downloaded chapter.
     * @return the list of pages from the chapter.
     */
    suspend fun buildPageList(source: Source, manga: Manga, chapter: Chapter): List<Page> {
        val chapterDir = provider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
        val files = withContext(Dispatchers.IO) {
            chapterDir?.listFiles().orEmpty()
                .filter { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } }
        }

        if (files.isEmpty()) {
            throw Exception(context.stringResource(MR.strings.page_list_empty_error))
        }

        return files.sortedBy { it.name }
            .mapIndexed { i, file ->
                Page(i, uri = file.uri).apply { status = Page.State.Ready }
            }
    }

    /**
     * Returns the ids of the downloaded chapters among the given chapters of the manga.
     *
     * @param chapters the chapters to query, all of [manga].
     * @param manga the manga of the chapters.
     */
    fun getDownloadedChapterIds(chapters: List<Chapter>, manga: Manga): Set<Long> {
        return cache.getDownloadedChapterIds(chapters, manga.title, manga.source)
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean {
        return cache.isChapterDownloaded(chapterName, chapterScanlator, chapterUrl, mangaTitle, sourceId)
    }

    /**
     * Returns true if the chapter is present on disk, bypassing the directory cache.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the chapter.
     */
    suspend fun isChapterDownloadedOnDisk(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        source: Source,
    ): Boolean {
        return provider.findChapterDir(chapterName, chapterScanlator, chapterUrl, mangaTitle, source) != null
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getDownloadCount(): Int {
        return cache.getTotalDownloadCount()
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        return cache.getDownloadCount(manga)
    }

    /**
     * Returns the amount of downloaded chapters for each of the given manga, keyed by manga id.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCounts(manga: List<Manga>): Map<Long, Int> {
        return cache.getDownloadCounts(manga)
    }

    suspend fun cancelQueuedDownloads(downloads: List<Download>) {
        downloader.dequeue(downloads.map { it.chapter })
    }

    fun resumeCloudUpload(chapterId: Long) {
        downloader.resumeCloudUpload(chapterId)
    }

    fun pauseCloudUpload(chapterId: Long) {
        downloader.pauseCloudUpload(chapterId)
    }

    fun cancelCloudUpload(chapterId: Long) {
        downloader.cancelCloudUpload(chapterId)
    }

    /**
     * Deletes the directories of a list of downloaded chapters.
     *
     * @param chapters the list of chapters to delete.
     * @param manga the manga of the chapters.
     * @param source the source of the chapters.
     */
    suspend fun deleteChapters(chapters: List<Chapter>, manga: Manga, source: Source) {
        withContext(Dispatchers.IO) {
            val filteredChapters = getChaptersToDelete(chapters, manga)
            if (filteredChapters.isEmpty()) {
                return@withContext
            }

            downloader.dequeue(filteredChapters)

            val (mangaDirs, chapterDirs) = provider.findChapterDirs(filteredChapters, manga, source)
            chapterDirs.forEach { it.delete() }
            cache.removeChapters(filteredChapters, manga)

            // Delete manga directories if empty
            mangaDirs.filter { it.listFiles()?.isEmpty() == true }.forEach { it.delete() }
            if (mangaDirs.isNotEmpty()) {
                deleteEmptySourceDirectories(source)
            }
        }
    }

    /**
     * Deletes the directory of a downloaded manga.
     *
     * @param manga the manga to delete.
     * @param source the source of the manga.
     * @param removeQueued whether to also remove queued downloads.
     */
    suspend fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        withContext(Dispatchers.IO) {
            if (removeQueued) {
                downloader.removeFromQueue(manga)
            }
            provider.findMangaDirs(manga.title, source).forEach { it.delete() }
            cache.removeManga(manga)
            deleteEmptySourceDirectories(source)
        }
    }

    private suspend fun deleteEmptySourceDirectories(source: Source) {
        val sourceDirs = provider.findSourceDirs(source)
        if (sourceDirs.isNotEmpty() && sourceDirs.all { it.listFiles()?.isEmpty() == true }) {
            sourceDirs.forEach { it.delete() }
            cache.removeSource(source)
        }
    }

    /**
     * Adds a list of chapters to be deleted later.
     *
     * @param chapters the list of chapters to delete.
     * @param manga the manga of the chapters.
     */
    suspend fun enqueueChaptersToDelete(chapters: List<Chapter>, manga: Manga) {
        pendingDeleter.addChapters(getChaptersToDelete(chapters, manga), manga)
    }

    /**
     * Triggers the execution of the deletion of pending chapters.
     */
    suspend fun deletePendingChapters() {
        val pendingChapters = pendingDeleter.getPendingChapters()
        for ((manga, chapters) in pendingChapters) {
            val source = sourceManager.get(manga.source) ?: continue
            deleteChapters(chapters, manga, source)
        }
    }

    /**
     * Renames source download folder
     *
     * @param oldSource the old source.
     * @param newSource the new source.
     */
    suspend fun renameSource(oldSource: Source, newSource: Source) = withContext(Dispatchers.IO) {
        val newName = provider.getSourceDirName(newSource)
        if (provider.getSourceDirName(oldSource) == newName) return@withContext

        var renamed = false

        provider.findSourceDirs(oldSource).forEach { oldFolder ->
            if (oldFolder.name == newName) return@forEach

            val capitalizationChanged = oldFolder.name.equals(newName, ignoreCase = true)
            if (capitalizationChanged) {
                val tempName = newName + Downloader.TMP_DIR_SUFFIX
                if (!oldFolder.renameTo(tempName)) {
                    logcat(LogPriority.ERROR) { "Failed to rename source download folder: ${oldFolder.name}" }
                    return@forEach
                }
            }

            if (!oldFolder.renameTo(newName)) {
                logcat(LogPriority.ERROR) { "Failed to rename source download folder: ${oldFolder.name}" }
            } else {
                renamed = true
            }
        }

        if (renamed) {
            cache.invalidateCache()
        }
    }

    /**
     * Renames manga download folder
     *
     * @param manga the manga
     * @param newTitle the new manga title.
     */
    suspend fun renameManga(manga: Manga, newTitle: String) = withContext(Dispatchers.IO) {
        val source = sourceManager.getOrStub(manga.source)
        val oldFolders = provider.findMangaDirs(manga.title, source)
        if (oldFolders.isEmpty()) return@withContext
        val newName = provider.getMangaDirName(newTitle)

        // just to be safe, don't allow downloads for this manga while renaming it
        downloader.removeFromQueue(manga)

        oldFolders.forEach { oldFolder ->
            if (oldFolder.name == newName) return@forEach

            val capitalizationChanged = oldFolder.name.equals(newName, ignoreCase = true)
            if (capitalizationChanged) {
                val tempName = newName + Downloader.TMP_DIR_SUFFIX
                if (!oldFolder.renameTo(tempName)) {
                    logcat(LogPriority.ERROR) { "Failed to rename manga download folder: ${oldFolder.name}" }
                    return@forEach
                }
            }

            if (!oldFolder.renameTo(newName)) {
                logcat(LogPriority.ERROR) { "Failed to rename manga download folder: ${oldFolder.name}" }
            }
        }
        cache.invalidateCache()
    }

    /**
     * Renames an already downloaded chapter
     *
     * @param source the source of the manga.
     * @param manga the manga of the chapter.
     * @param oldChapter the existing chapter with the old name.
     * @param newChapter the target chapter with the new name.
     */
    suspend fun renameChapter(source: Source, manga: Manga, oldChapter: Chapter, newChapter: Chapter) {
        val oldNames = provider.getValidChapterDirNames(oldChapter.name, oldChapter.scanlator, oldChapter.url)
        // Assume there's only 1 version of the chapter name formats present
        val mangaDirAndDownload = provider.findMangaDirs(manga.title, source).asSequence()
            .mapNotNull { mangaDir ->
                oldNames.asSequence()
                    .mapNotNull { mangaDir.findFile(it) }
                    .firstOrNull()
                    ?.let { mangaDir to it }
            }
            .firstOrNull() ?: return
        val (mangaDir, oldDownload) = mangaDirAndDownload

        var newName = provider.getChapterDirName(newChapter.name, newChapter.scanlator, newChapter.url)
        if (oldDownload.isFile && oldDownload.extension == "cbz") {
            newName += ".cbz"
        }

        if (oldDownload.name == newName) return

        if (oldDownload.renameTo(newName)) {
            cache.removeChapter(oldChapter, manga)
            cache.addChapter(newName, mangaDir, manga)
        } else {
            logcat(LogPriority.ERROR) { "Could not rename downloaded chapter: ${oldNames.joinToString()}" }
        }
    }

    private suspend fun getChaptersToDelete(chapters: List<Chapter>, manga: Manga): List<Chapter> {
        // Retrieve the categories that are set to exclude from being deleted on read
        val categoriesToExclude = downloadPreferences.removeExcludeCategories.get().map(String::toLong)

        val categoriesForManga = getCategories.await(manga.id)
            .map { it.id }
            .ifEmpty { listOf(0) }
        val filteredCategoryManga = if (categoriesForManga.intersect(categoriesToExclude).isNotEmpty()) {
            chapters.filterNot { it.read }
        } else {
            chapters
        }

        return if (!downloadPreferences.removeBookmarkedChapters.get()) {
            filteredCategoryManga.filterNot { it.bookmark }
        } else {
            filteredCategoryManga
        }
    }

    fun statusFlow(): Flow<Download> = kotlinx.coroutines.flow.combine(queueState, uploadQueueState) {
            downloads,
            uploads,
        ->
        downloads + uploads
    }
        .flatMapLatest { queue ->
            queue
                .map { download ->
                    download.statusFlow.drop(1).map { download }.onStart { emit(download) }
                }
                .merge()
        }
        .onStart {
            emitAll(
                (queueState.value + uploadQueueState.value).filter { download ->
                    download.status == Download.State.DOWNLOADING || download.status == Download.State.UPLOADING
                }.asFlow(),
            )
        }

    fun progressFlow(): Flow<Download> = kotlinx.coroutines.flow.combine(queueState, uploadQueueState) {
            downloads,
            uploads,
        ->
        downloads + uploads
    }
        .flatMapLatest { queue ->
            queue
                .map { download ->
                    download.progressFlow.drop(1).map { download }
                }
                .merge()
        }
        .onStart {
            emitAll(
                (queueState.value + uploadQueueState.value).filter { download ->
                    download.status == Download.State.DOWNLOADING || download.status == Download.State.UPLOADING
                }
                    .asFlow(),
            )
        }
}

internal data class CloudSyncQueueResult(
    val downloadsQueued: Int = 0,
    val uploadsQueued: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
) {
    val queued: Int get() = downloadsQueued + uploadsQueued
}
