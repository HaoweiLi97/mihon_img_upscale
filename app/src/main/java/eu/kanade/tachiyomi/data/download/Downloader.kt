package eu.kanade.tachiyomi.data.download

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.getComicInfo
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import eu.kanade.tachiyomi.util.storage.saveTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import mihon.core.metro.AppCoroutineScope
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its queue contains the list of chapters to download.
 */
@Inject
@SingleIn(AppScope::class)
class Downloader(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager,
    private val chapterCache: ChapterCache,
    private val coverCache: CoverCache,
    private val cloudSyncService: CloudSyncService,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val downloadPreferences: DownloadPreferences,
    private val xml: XML,
    private val getCategories: GetCategories,
    private val getTracks: GetTracks,
    private val store: DownloadStore,
    private val notifier: DownloadNotifier,
) {
    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val _uploadQueueState = MutableStateFlow<List<Download>>(emptyList())
    val uploadQueueState = _uploadQueueState.asStateFlow()

    private val json by lazy {
        Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }

    // Held by every start, stop and queue edit, which come from the main thread, the download worker and the
    // deletion jobs, so a check of isRunning can't interleave with another caller's start or stop
    private val lock = Mutex()

    // Kept when canceled, so the next job can wait for it to finish
    @Volatile
    private var downloaderJob: Job? = null
    private val uploadLock = Mutex()
    private val cloudUploadTasks = linkedMapOf<String, CloudUploadTask>()
    private val activeCloudUploadJobs = mutableMapOf<String, Job>()
    private val metaInfoLock = Mutex()
    private var cloudUploadOrder = 0L
    private val restoreCloudUploadsJob = scope.launch(Dispatchers.IO) {
        uploadLock.withLock {
            store.restoreUploads().forEach { (record, download) ->
                cloudUploadTasks[record.key] = CloudUploadTask(record, download)
                cloudUploadOrder = maxOf(cloudUploadOrder, record.order)
            }
            publishCloudUploadQueue()
            startCloudUploadsLocked()
        }
    }

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = false

    init {
        scope.launch(Dispatchers.Main, CoroutineStart.UNDISPATCHED) {
            addAllToQueue(withContext(Dispatchers.IO) { store.restore() })
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    suspend fun start(): Boolean = lock.withLock { startLocked() }

    /**
     * Stops the downloader.
     */
    suspend fun stop(reason: String? = null) = lock.withLock { stopLocked(reason) }

    // Download jobs check these under the lock: by the time they get it, chapters may have been queued or the
    // downloader stopped and started again, and that newer run must not be stopped
    private suspend fun stopIfFinished(owner: Job) = lock.withLock {
        if (downloaderJob === owner && owner.isActive && areAllDownloadsFinished()) stopLocked()
    }

    private suspend fun stopIfCurrent(owner: Job) = lock.withLock {
        if (downloaderJob === owner && owner.isActive) stopLocked()
    }

    /**
     * Pauses and stops the downloader, keeping the queue for a later start
     */
    suspend fun pause() = lock.withLock {
        pauseLocked()
        stopLocked()
    }

    /**
     * Removes everything from the queue.
     */
    suspend fun clearQueue() = lock.withLock {
        cancelDownloaderJob()

        internalClearQueue()
        notifier.dismissProgress()
    }

    private fun startLocked(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Download.State.DOWNLOADED }
        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }

        isPaused = false

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    private fun stopLocked(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING || it.status == Download.State.UPLOADING }
            .forEach { it.status = Download.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        isPaused = false
    }

    private fun pauseLocked() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING || it.status == Download.State.UPLOADING }
            .forEach { it.status = Download.State.QUEUE }
        isPaused = true
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun launchDownloaderJob() {
        if (isRunning) return

        val previousJob = downloaderJob
        downloaderJob = scope.launch {
            // Page writes block, so a canceled job can still be writing the pages this one would start on. Not
            // cancelable, so a job canceled while waiting still finishes after the one before it.
            withContext(NonCancellable) { previousJob?.join() }
            val owner = coroutineContext.job

            val activeDownloadsFlow = combine(
                queueState,
                downloadPreferences.parallelSourceLimit.changes(),
            ) { a, b -> a to b }.transformLatest { (queue, parallelCount) ->
                while (true) {
                    val activeDownloads = queue.asSequence()
                        // Ignore completed downloads, leave them in the queue
                        .filter { it.status.value <= Download.State.UPLOADING.value }
                        .groupBy { it.source }
                        .toList()
                        .take(parallelCount)
                        .map { (_, downloads) -> downloads.first() }
                    emit(activeDownloads)

                    if (activeDownloads.isEmpty()) break
                    // Suspend until a download enters the ERROR state
                    val activeDownloadsErroredFlow =
                        combine(activeDownloads.map(Download::statusFlow)) { states ->
                            states.contains(Download.State.ERROR)
                        }.filter { it }
                    activeDownloadsErroredFlow.first()
                }
            }
                .distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val sourceJobs = mutableMapOf<HttpSource, Job>()
                val downloadJobs = ConcurrentHashMap<Download, Job>()

                // A source runs several of its chapters at once, so a removed one is stopped by itself
                launch {
                    queueState.collect { queue ->
                        downloadJobs.keys.filter { it !in queue }.forEach { downloadJobs.remove(it)?.cancel() }
                    }
                }

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val activeSources = activeDownloads.map { it.source }
                    val sourceJobsToStop = sourceJobs.filter { it.key !in activeSources }
                    sourceJobsToStop.forEach { (source, job) ->
                        job.cancel()
                        sourceJobs.remove(source)
                    }

                    val sourcesToStart = activeSources.filter { it !in sourceJobs }
                    sourcesToStart.forEach { source ->
                        sourceJobs[source] = launchSourceJob(source, downloadJobs, owner)
                    }
                }
            }
        }
    }

    /**
     * Downloads the queued chapters of [source] in queue order. They share the source's page slots, and the next
     * chapter starts once every page of the current one has a slot, so slots don't sit idle while a chapter's last
     * pages finish and it's archived.
     */
    private fun CoroutineScope.launchSourceJob(
        source: HttpSource,
        downloadJobs: MutableMap<Download, Job>,
        owner: Job,
    ) = launch(Dispatchers.IO) {
        val slots = Semaphore(downloadPreferences.parallelPageLimit.get())
        while (true) {
            // A canceled job can mark a chapter as downloading after it's been reset, so that counts as waiting too
            val download = queueState
                .map { queue ->
                    queue.firstOrNull {
                        it.source == source &&
                            (it.status == Download.State.QUEUE || it.status == Download.State.DOWNLOADING) &&
                            it !in downloadJobs
                    }
                }
                .filterNotNull()
                .first()
            val pagesStarted = CompletableDeferred<Unit>()
            val job = launchDownloadJob(download, slots, pagesStarted, owner)
            downloadJobs[download] = job
            job.invokeOnCompletion {
                downloadJobs.remove(download, job)
                // A job canceled before it starts never reaches its finally block
                pagesStarted.complete(Unit)
            }
            pagesStarted.await()
        }
    }

    private fun CoroutineScope.launchDownloadJob(
        download: Download,
        slots: Semaphore,
        pagesStarted: CompletableDeferred<Unit>,
        owner: Job,
    ) = launch(Dispatchers.IO) {
        try {
            // Removed from the queue between being picked and starting
            if (download !in queueState.value) return@launch

            downloadChapter(download, slots, pagesStarted)

            // Remove successful download from queue
            if (download.status == Download.State.DOWNLOADED) {
                removeFromQueue(download)
            }
            stopIfFinished(owner)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            notifier.onError(e.message)
            stopIfCurrent(owner)
        } finally {
            // Also when the chapter fails before its pages, so the source moves on to the next one
            pagesStarted.complete(Unit)
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        downloaderJob?.cancel()
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     * @return true if the downloader should be started.
     */
    suspend fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean): Boolean {
        if (chapters.isEmpty()) return false

        val source = sourceManager.get(manga.source) as? HttpSource ?: return false
        val wasEmpty = queueState.value.isEmpty()
        val chaptersToQueue = withContext(Dispatchers.IO) {
            val downloadedChapterIds = provider.findDownloadedChapterIds(chapters, manga, source)
            val queuedChapterIds = queueState.value.mapTo(HashSet()) { it.chapter.id }
            chapters
                // Filter out those already downloaded.
                .filter { it.id !in downloadedChapterIds }
                .asSequence()
                // Add chapters to queue from the start.
                .sortedByDescending { it.sourceOrder }
                // Filter out those already enqueued.
                .filter { it.id !in queuedChapterIds }
                // Create a download for each one.
                .map { Download(source, manga, it) }
                .toList()
        }

        if (chaptersToQueue.isNotEmpty()) {
            addAllToQueue(chaptersToQueue)

            // Start downloader if needed
            if (autoStart && wasEmpty) {
                val queuedDownloads = queueState.value.count { it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size }
                    ?: 0
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    notifier.onWarning(
                        context.stringResource(
                            MR.strings.download_queue_size_warning,
                            context.stringResource(MR.strings.app_name),
                        ),
                        WARNING_NOTIF_TIMEOUT_MS,
                        NotificationHandler.openUrl(context, LibraryUpdateNotifier.HELP_WARNING_URL),
                    )
                }
                return true
            }
        }
        return false
    }

    fun isCloudSyncAvailable(): Boolean {
        return cloudSyncTargetOrNull() != null
    }

    internal suspend fun queueChaptersForCloudSync(
        manga: Manga,
        chapters: List<Chapter>,
        autoStart: Boolean,
    ): CloudSyncQueueResult {
        val uniqueChapters = chapters.distinctBy { it.id }
        if (uniqueChapters.isEmpty()) return CloudSyncQueueResult()

        val source = sourceManager.get(manga.source) as? HttpSource
            ?: return CloudSyncQueueResult(failed = uniqueChapters.size)
        val target = cloudSyncTargetOrNull() ?: return CloudSyncQueueResult(failed = uniqueChapters.size)

        var uploadsQueued = 0
        var skipped = 0
        var failed = 0
        var directUploadDownload: Download? = null
        val chaptersToDownload = mutableListOf<Chapter>()

        uniqueChapters.forEach { chapter ->
            val downloadedChapter = provider.findChapterDir(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                manga.title,
                source,
            )
            when (
                cloudSyncChapterAction(
                    isUploaded = downloadPreferences.isChapterUploadedToCloud(chapter.id, target.id),
                    hasLocalDownload = downloadedChapter != null,
                    isLocalCbz =
                    downloadedChapter?.isFile == true &&
                        downloadedChapter.name?.endsWith(".cbz", ignoreCase = true) == true,
                )
            ) {
                CloudSyncChapterAction.Skip -> skipped++
                CloudSyncChapterAction.Download -> chaptersToDownload += chapter
                CloudSyncChapterAction.Upload -> {
                    val download = Download(source, manga, chapter)
                    if (enqueueDownloadedChapterUpload(download, downloadedChapter!!, target)) {
                        uploadsQueued++
                        directUploadDownload = directUploadDownload ?: download
                    } else {
                        failed++
                    }
                }
                CloudSyncChapterAction.UnsupportedLocalDownload -> failed++
            }
        }

        val shouldStart = queueChapters(manga, chaptersToDownload, autoStart)
        val queuedChapterIds = queueState.value.mapTo(mutableSetOf()) { it.chapter.id }
        val downloadsQueued = chaptersToDownload.count { it.id in queuedChapterIds }
        failed += chaptersToDownload.size - downloadsQueued
        if (shouldStart) DownloadWorker.start(context)

        directUploadDownload?.let { download ->
            provider.findMangaDirs(manga.title, source)
                .firstOrNull()
                ?.let { updateMetaInfo(download, it, target) }
        }

        return CloudSyncQueueResult(
            downloadsQueued = downloadsQueued,
            uploadsQueued = uploadsQueued,
            skipped = skipped,
            failed = failed,
        )
    }

    /**
     * Downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     * @param slots the page download slots of the chapter's source.
     * @param pagesStarted completed once every page has a slot.
     */
    private suspend fun downloadChapter(download: Download, slots: Semaphore, pagesStarted: CompletableDeferred<Unit>) {
        val mangaDir = provider.getMangaDir(download.manga, download.source).getOrElse { e ->
            download.status = Download.State.ERROR
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
            return
        }

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.State.ERROR
            notifier.onError(
                context.stringResource(MR.strings.download_insufficient_space),
                download.chapter.name,
                download.manga.title,
                download.manga.id,
            )
            return
        }

        val chapterDirname = provider.getChapterDirName(
            download.chapter.name,
            download.chapter.scanlator,
            download.chapter.url,
        )

        // Being stopped after the download is moved into place but before it's indexed leaves a finished download
        // on disk that is still queued, and downloading it again would save a second copy next to it
        val existingChapter = mangaDir.findFile("$chapterDirname.cbz") ?: mangaDir.findFile(chapterDirname)
        if (existingChapter != null) {
            mangaDir.findFile(chapterDirname + TMP_DIR_SUFFIX)?.delete()
            cache.addChapter(chapterDirname, mangaDir, download.manga)
            finishChapterDownload(download, mangaDir, existingChapter)
            return
        }

        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)!!

        try {
            // If the page list already exists, start from the file
            val pageList = download.pages ?: run {
                // Otherwise, pull page list from network and add them to download object
                val pages = download.source.getPageList(download.chapter.toSChapter())

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }
                // Don't trust index from source
                val reIndexedPages = pages.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
                download.pages = reIndexedPages
                reIndexedPages
            }

            download.status = Download.State.DOWNLOADING

            // Start downloading images, consider we can have downloaded images already
            coroutineScope {
                pageList.forEach { page ->
                    slots.acquire()
                    // Released on completion, since a page canceled before it starts never runs its body
                    launch(Dispatchers.IO) {
                        // Fetch image URL if necessary
                        if (page.imageUrl.isNullOrEmpty()) {
                            page.status = Page.State.LoadPage
                            try {
                                page.imageUrl = download.source.getImageUrl(page)
                            } catch (e: Throwable) {
                                page.status = Page.State.Error(e)
                            }
                        }

                        getOrDownloadImage(page, download, tmpDir)
                        notifier.onProgressChange(download)
                    }.invokeOnCompletion { slots.release() }
                }
                pagesStarted.complete(Unit)
            }

            // Do after download completes

            if (!isDownloadSuccessful(download, tmpDir)) {
                download.status = Download.State.ERROR
                return
            }

            createComicInfoFile(
                tmpDir,
                download.manga,
                download.chapter,
                download.source,
            )

            // Only rename the directory if it's downloaded
            val downloadedChapter = if (downloadPreferences.saveChaptersAsCBZ.get()) {
                archiveChapter(mangaDir, chapterDirname, tmpDir)
            } else {
                tmpDir.renameTo(chapterDirname)
                mangaDir.findFile(chapterDirname) ?: error("Unable to finalize downloaded chapter: $chapterDirname")
            }
            cache.addChapter(chapterDirname, mangaDir, download.manga)

            DiskUtil.createNoMediaFile(tmpDir, context)

            finishChapterDownload(download, mangaDir, downloadedChapter)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // If the page list threw, it will resume here
            logcat(LogPriority.ERROR, error)
            download.status = Download.State.ERROR
            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Gets the image from the filesystem if it exists or downloads it otherwise.
     *
     * @param page the page to download.
     * @param download the download of the page.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadImage(page: Page, download: Download, tmpDir: UniFile) {
        // If the image URL is empty, do nothing
        if (page.imageUrl == null) {
            return
        }

        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)

        // Try to find the image file
        val imageFile = tmpDir.listFiles()?.firstOrNull {
            isDownloadedPageImage(it.name ?: return@firstOrNull false, filename)
        }

        try {
            // If the image is already downloaded, do nothing. Otherwise download from network
            val file = when {
                imageFile != null -> imageFile
                chapterCache.isImageInCache(
                    page.imageUrl!!,
                ) -> copyImageFromCache(chapterCache.getImageFile(page.imageUrl!!), tmpDir, filename)
                else -> downloadImage(page, download.source, tmpDir, filename)
            }

            // When the page is ready, set page path, progress (just in case) and status
            splitTallImageIfNeeded(page, tmpDir)

            page.uri = file.uri
            page.progress = 100
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Mark this page as error and allow to download the remaining
            page.progress = 0
            page.status = Page.State.Error(e)
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Downloads the image from network to a file in tmpDir.
     *
     * @param page the page to download.
     * @param source the source of the page.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private suspend fun downloadImage(page: Page, source: HttpSource, tmpDir: UniFile, filename: String): UniFile {
        page.status = Page.State.DownloadImage
        page.progress = 0
        return flow {
            val file = tmpDir.findFile("$filename.tmp")
                ?: tmpDir.createFile("$filename.tmp")!!
            try {
                source.getImage(page, file.length()).use { response ->
                    response.body.source().saveTo(file.openOutputStream(response.code == 206))
                    val extension = getImageExtension(response, file)
                    emit(finalizeDownloadFile(file, tmpDir, "$filename.$extension"))
                }
            } catch (e: HttpException) {
                if (e.code == 416) file.delete()
                throw e
            }
        }
            // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
            .retryWhen { _, attempt ->
                if (attempt < 3) {
                    delay((2L shl attempt.toInt()).seconds)
                    true
                } else {
                    false
                }
            }
            .first()
    }

    /**
     * Copies the image from cache to file in tmpDir.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private fun copyImageFromCache(cacheFile: File, tmpDir: UniFile, filename: String): UniFile {
        // Delete temp file if it exists
        tmpDir.findFile("$filename.tmp")?.delete()
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: return tmpFile
        val imageFile = finalizeDownloadFile(tmpFile, tmpDir, "$filename.${extension.extension}")
        cacheFile.delete()
        return imageFile
    }

    /**
     * Returns the extension of the downloaded image from the network response, or if it's null,
     * analyze the file. If everything fails, assume it's a jpg.
     *
     * @param response the network response of the image.
     * @param file the file where the image is already downloaded.
     */
    private fun getImageExtension(response: Response, file: UniFile): String {
        val mime = response.body.contentType()?.run { if (type == "image") "image/$subtype" else null }
        return ImageUtil.getExtensionFromMimeType(mime) { file.openInputStream() }
    }

    private fun splitTallImageIfNeeded(page: Page, tmpDir: UniFile) {
        if (!downloadPreferences.splitTallImages.get()) return

        try {
            val filenamePrefix = "%03d".format(Locale.ENGLISH, page.number)
            val imageFile = tmpDir.listFiles()?.firstOrNull { it.name.orEmpty().startsWith(filenamePrefix) }
                ?: error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))

            // If the original page was previously split, then skip
            if (imageFile.name.orEmpty().startsWith("${filenamePrefix}__")) return

            ImageUtil.splitTallImage(tmpDir, imageFile, filenamePrefix)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    private fun isDownloadSuccessful(
        download: Download,
        tmpDir: UniFile,
    ): Boolean {
        // Page list hasn't been initialized
        val downloadPageCount = download.pages?.size ?: return false

        // Ensure that all pages have been downloaded
        if (download.downloadedImages != downloadPageCount) {
            return false
        }

        // Ensure that the chapter folder has all the pages
        val downloadedImagesCount = tmpDir.listFiles().orEmpty().count {
            val fileName = it.name.orEmpty()
            when {
                fileName in listOf(COMIC_INFO_FILE, NOMEDIA_FILE) -> false
                fileName.endsWith(".tmp") -> false
                // Only count the first split page and not the others
                fileName.contains("__") && !fileName.endsWith("__001.jpg") -> false
                else -> true
            }
        }
        return downloadedImagesCount == downloadPageCount
    }

    /**
     * Checks if the file name matches a downloaded page image.
     *
     * @param fileName Name of the file to check
     * @param pagePrefix Expected page prefix (e.g., "001")
     */
    private fun isDownloadedPageImage(fileName: String, pagePrefix: String): Boolean =
        !fileName.endsWith(".tmp") && (
            fileName.startsWith("$pagePrefix.") ||
                fileName.startsWith("${pagePrefix}__001.")
            )

    /**
     * Archive the chapter pages as a CBZ.
     */
    private fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
    ): UniFile {
        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")!!
        ZipWriter(context, zip).use { writer ->
            // Download jobs complete concurrently, and a document provider
            // does not guarantee that listFiles() preserves either creation
            // or display order. ZIP writes are append-only, so keep physical
            // entry offsets in the same natural order used by the reader.
            // This makes sequential Range buffering of remote CBZ files
            // practical instead of scattering consecutive pages throughout
            // the archive.
            tmpDir.listFiles()
                ?.sortedWith { first, second ->
                    first.name.orEmpty().compareToCaseInsensitiveNaturalOrder(second.name.orEmpty())
                }
                ?.forEach { file ->
                    writer.write(file)
                }
            if (downloadPreferences.addRandomNonceToCBZ().get()) {
                writer.writeBytes(
                    CBZ_RANDOM_NONCE_FILE_NAME,
                    UUID.randomUUID().toString().toByteArray(Charsets.UTF_8),
                )
            }
        }
        val archive = finalizeDownloadFile(zip, mangaDir, "$dirname.cbz")
        tmpDir.delete()
        return archive
    }

    private suspend fun finishChapterDownload(download: Download, mangaDir: UniFile, chapterFile: UniFile) {
        val target = cloudSyncTargetOrNull()
        if (target != null && chapterFile.isFile &&
            !downloadPreferences.isChapterUploadedToCloud(download.chapter.id, target.id)
        ) {
            try {
                enqueueDownloadedChapterUpload(download, chapterFile, target)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportCloudUploadError(download, error)
            }
        }
        updateMetaInfo(download, mangaDir, target)
        download.status = Download.State.DOWNLOADED
    }

    private suspend fun updateMetaInfo(download: Download, mangaDir: UniFile, target: CloudSyncTarget?) {
        try {
            // Chapters from one manga can finish together. Generate and snapshot its index as one operation.
            metaInfoLock.withLock {
                val metaInfoFile = createMetaInfoFile(mangaDir, download.manga, download.source)
                if (target == null) return
                val snapshot = snapshotMetaInfoFile(mangaDir, metaInfoFile) ?: return
                var queued = false
                try {
                    queued = enqueueCloudUpload(
                        CloudUploadRecord(
                            mangaId = download.manga.id,
                            chapterId = download.chapter.id,
                            targetId = target.id,
                            remoteDirectory = cloudRemoteDirectory(download, target),
                            fileUri = snapshot.uri.toString(),
                            fileName = META_INFO_FILE_NAME,
                            contentHash = snapshot.sha256(),
                        ),
                        download,
                    )
                } finally {
                    if (!queued) snapshot.delete()
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            logcat(LogPriority.WARN, error) { "Failed to prepare meta.info for ${download.manga.title}" }
        }
    }

    private suspend fun enqueueDownloadedChapterUpload(
        download: Download,
        chapterFile: UniFile,
        target: CloudSyncTarget,
    ): Boolean {
        val fileName = chapterFile.name ?: return false
        if (!fileName.endsWith(".cbz", ignoreCase = true)) return false
        return enqueueCloudUpload(
            CloudUploadRecord(
                mangaId = download.manga.id,
                chapterId = download.chapter.id,
                targetId = target.id,
                remoteDirectory = cloudRemoteDirectory(download, target),
                fileUri = chapterFile.uri.toString(),
                fileName = fileName,
                deleteAfterUpload = downloadPreferences.cloudSyncDeleteAfterUpload().get(),
            ),
            Download(download.source, download.manga, download.chapter),
        )
    }

    private suspend fun cloudRemoteDirectory(
        download: Download,
        cloudSync: CloudSyncTarget,
    ): String {
        val categoryDirName = if (downloadPreferences.organizeDownloadsByCategory().get()) {
            provider.getMangaCategoryDirName(download.manga)
        } else {
            null
        }
        return cloudRemoteDirectoryPath(
            destination = cloudSync.destination,
            categoryDirName = categoryDirName,
            sourceDirName = provider.getSourceDirName(download.source),
            mangaDirName = provider.getMangaDirName(download.manga.title),
        )
    }

    private fun snapshotMetaInfoFile(mangaDir: UniFile, metaInfoFile: UniFile): UniFile? {
        val snapshot =
            mangaDir.createFile("$META_INFO_FILE_NAME.upload.${UUID.randomUUID()}$TMP_DIR_SUFFIX") ?: return null
        try {
            metaInfoFile.openInputStream().use { input ->
                snapshot.openOutputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (error: Exception) {
            snapshot.delete()
            throw error
        }
        return snapshot
    }

    private fun cloudSyncTargetOrNull(): CloudSyncTarget? {
        if (!downloadPreferences.saveChaptersAsCBZ.get() || !downloadPreferences.cloudSyncEnabled().get()) return null

        val config = CloudSyncConfig(
            url = downloadPreferences.cloudSyncUrl().get(),
            username = downloadPreferences.cloudSyncUsername().get(),
            password = downloadPreferences.cloudSyncPassword().get(),
        )
        val destination = downloadPreferences.cloudSyncDestination().get()
        if (!config.isValid) return null
        val id = downloadPreferences.cloudSyncTargetId(config.url, config.username, destination) ?: return null

        return CloudSyncTarget(config, destination, id)
    }

    private suspend fun enqueueCloudUpload(record: CloudUploadRecord, download: Download): Boolean {
        restoreCloudUploadsJob.join()
        return uploadLock.withLock {
            val existing = cloudUploadTasks[record.key]
            if (existing != null) {
                // A paused upload stays in the queue until explicitly resumed or canceled.
                return@withLock record.isChapter &&
                    existing.record.targetId == record.targetId &&
                    existing.record.remoteDirectory == record.remoteDirectory
            }
            val queued = record.copy(order = ++cloudUploadOrder)
            store.saveUpload(queued)
            cloudUploadTasks[record.key] = CloudUploadTask(queued, download)
            publishCloudUploadQueue()
            startCloudUploadsLocked()
            true
        }
    }

    private fun publishCloudUploadQueue() {
        _uploadQueueState.value = cloudUploadTasks.values.filter { it.record.isChapter }.map { it.download }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startCloudUploadsLocked() {
        if (!scope.isActive) return
        val limit = downloadPreferences.parallelCloudUploadLimit().get().coerceIn(1, MAX_PARALLEL_CLOUD_UPLOADS)
        cloudUploadTasks.values.asSequence()
            .filter { !it.record.paused && it.record.key !in activeCloudUploadJobs }
            .take((limit - activeCloudUploadJobs.size).coerceAtLeast(0))
            .forEach { task ->
                if (task.record.isChapter) task.download.status = Download.State.UPLOADING
                // Even a task canceled before dispatch must release its slot in the worker's finally block.
                val job = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) { processCloudUploadTask(task) }
                activeCloudUploadJobs[task.record.key] = job
                job.start()
            }
    }

    suspend fun resumeCloudUpload(chapterId: Long) = withContext(Dispatchers.IO) {
        uploadLock.withLock {
            val task = cloudUploadTasks["chapter:$chapterId"] ?: return@withLock
            if (!task.record.paused) return@withLock
            if (cloudSyncTargetOrNull()?.id != task.record.targetId) {
                reportCloudUploadError(task.download, CloudUploadTargetChangedException())
                return@withLock
            }
            setCloudUploadPaused(task, false)
            startCloudUploadsLocked()
        }
    }

    suspend fun pauseCloudUpload(chapterId: Long) = withContext(Dispatchers.IO) {
        uploadLock.withLock {
            val task = cloudUploadTasks["chapter:$chapterId"] ?: return@withLock
            if (task.record.paused) return@withLock
            setCloudUploadPaused(task, true)
            activeCloudUploadJobs[task.record.key]?.cancel()
        }
    }

    suspend fun cancelCloudUpload(chapterId: Long) = withContext(Dispatchers.IO) {
        uploadLock.withLock {
            val task = cloudUploadTasks["chapter:$chapterId"] ?: return@withLock
            store.removeUpload(task.record.key)
            cloudUploadTasks.remove(task.record.key)
            activeCloudUploadJobs[task.record.key]?.cancel()
            publishCloudUploadQueue()
        }
    }

    private fun setCloudUploadPaused(task: CloudUploadTask, paused: Boolean, failed: Boolean = false) {
        val record = task.record.copy(paused = paused, failed = failed)
        store.saveUpload(record)
        task.updateRecord(record)
    }

    private suspend fun processCloudUploadTask(task: CloudUploadTask) {
        val owner = coroutineContext.job
        val record = task.record
        var file: UniFile? = null
        var failure: Exception? = null
        try {
            owner.ensureActive()
            file = UniFile.fromUri(context, record.fileUri.toUri())?.takeIf { it.isFile }
                ?: error("Local upload file is unavailable: ${record.fileName}")
            val uploadFile = file
            retryCloudUpload {
                val config = cloudSyncTargetOrNull()?.takeIf { it.id == record.targetId }?.config
                    ?: throw CloudUploadTargetChangedException()
                if (record.isChapter) {
                    cloudSyncService.uploadCbz(
                        config,
                        record.remoteDirectory,
                        uploadFile,
                        record.fileName,
                    ) { progress ->
                        if (owner.isActive) task.download.uploadProgress = progress
                    }
                } else {
                    val remoteHash = cloudSyncService.remoteFileSha256OrNull(
                        config,
                        record.remoteDirectory,
                        record.fileName,
                    )
                    if (remoteHash != record.contentHash) {
                        cloudSyncService.replaceFile(config, record.remoteDirectory, uploadFile, record.fileName)
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure = error
        } finally {
            withContext(NonCancellable) {
                uploadLock.withLock {
                    activeCloudUploadJobs.remove(record.key)
                    if (owner.isActive && cloudUploadTasks[record.key] === task && !task.record.paused) {
                        try {
                            finishCloudUpload(task, file, failure)
                        } catch (error: Exception) {
                            task.updateRecord(task.record.copy(paused = true, failed = true))
                            reportCloudUploadError(task.download, error)
                        }
                    }
                    publishCloudUploadQueue()
                    startCloudUploadsLocked()
                }
            }
        }
    }

    private suspend fun finishCloudUpload(task: CloudUploadTask, file: UniFile?, failure: Exception?) {
        val record = task.record
        val download = task.download
        if (failure != null) {
            reportCloudUploadError(download, failure)
            if (record.isChapter) {
                setCloudUploadPaused(task, paused = true, failed = true)
                return
            }
        } else if (record.isChapter) {
            downloadPreferences.markChaptersUploadedToCloud(listOf(record.chapterId), record.targetId)
        } else {
            downloadPreferences.markMetaInfoUploadedToCloud(
                record.mangaId,
                checkNotNull(record.contentHash),
                record.targetId,
            )
        }
        store.removeUpload(record.key)
        cloudUploadTasks.remove(record.key)
        if (!record.isChapter || record.deleteAfterUpload) {
            try {
                if (file?.delete() == true && record.isChapter) {
                    cache.removeChapter(download.chapter, download.manga)
                }
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Failed to remove uploaded file ${record.fileName}" }
            }
        }
        if (record.isChapter) download.status = Download.State.DOWNLOADED
    }

    private fun reportCloudUploadError(download: Download, error: Exception) {
        logcat(LogPriority.WARN, error) { "Cloud upload failed for ${download.manga.title}" }
        notifier.onWarning(error.message ?: "Cloud sync upload failed", mangaId = download.manga.id)
    }

    private suspend fun retryCloudUpload(block: suspend () -> Unit) {
        repeat(CLOUD_UPLOAD_MAX_ATTEMPTS) { attempt ->
            try {
                block()
                return
            } catch (error: Exception) {
                if (error is CancellationException || error is CloudUploadConflictException ||
                    error is CloudUploadTargetChangedException || attempt == CLOUD_UPLOAD_MAX_ATTEMPTS - 1
                ) {
                    throw error
                }
                delay((2L shl attempt) * 1000 + Random.nextLong(0, 1000))
            }
        }
    }

    /**
     * Creates a ComicInfo.xml file inside the given directory.
     */
    private suspend fun createComicInfoFile(
        dir: UniFile,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ) {
        val categories = getCategories.await(manga.id).map { it.name.trim() }.takeUnless { it.isEmpty() }
        val urls = getTracks.await(manga.id)
            .mapNotNull { track ->
                track.remoteUrl.takeUnless { url -> url.isBlank() }?.trim()
            }
            .plus(source.getChapterUrl(chapter.toSChapter()).trim())
            .distinct()

        val comicInfo = getComicInfo(
            manga,
            chapter,
            urls,
            categories,
            source.name,
        )

        // Remove the old file
        dir.findFile(COMIC_INFO_FILE)?.delete()
        dir.createFile(COMIC_INFO_FILE)!!.openOutputStream().use {
            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
            it.write(comicInfoString.toByteArray())
        }
    }

    private suspend fun createMetaInfoFile(
        mangaDir: UniFile,
        manga: Manga,
        source: HttpSource,
    ): UniFile {
        val chapters = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false)
            .sortedWith(getChapterSort(manga))
        val coverAsset = resolveMetaInfoCoverAsset(manga, source)
        val manifest = MetaInfoManifest(
            schemaVersion = 1,
            manga = MetaInfoManga(
                id = manga.id,
                title = manga.title,
                author = manga.author,
                artist = manga.artist,
                status = manga.status.toMetaInfoStatus(),
                description = manga.description,
                tags = manga.genre.orEmpty(),
                source = source.name,
                url = source.getMangaUrl(manga.toSManga()).trim(),
                cover = coverAsset?.entryName,
            ),
            chapters = chapters.map { chapter ->
                MetaInfoChapter(
                    id = chapter.id,
                    title = chapter.name,
                    chapterNumber = chapter.chapterNumber.takeIf { it >= 0 },
                    scanlator = chapter.scanlator,
                    url = source.getChapterUrl(chapter.toSChapter()).trim(),
                    sourceOrder = chapter.sourceOrder,
                    file = provider.chapterOutputName(chapter, downloadPreferences.saveChaptersAsCBZ.get()),
                )
            },
        )

        val tempDirName = META_INFO_FILE_NAME + TMP_DIR_SUFFIX
        mangaDir.findFile(tempDirName)?.delete()
        val tempDir = mangaDir.createDirectory(tempDirName) ?: error("Failed to create meta.info temp directory")
        val manifestFile = tempDir.createFile(META_INFO_MANIFEST_FILE_NAME) ?: error("Failed to create manifest.json")
        manifestFile.openOutputStream().use { output ->
            output.write(json.encodeToString(manifest).toByteArray())
        }

        coverAsset?.let { asset ->
            tempDir.findFile(asset.entryName)?.delete()
            tempDir.createFile(asset.entryName)?.openOutputStream()?.use { output ->
                asset.openStream().use { input -> input.copyTo(output) }
            }
        }

        mangaDir.findFile(META_INFO_TEMP_FILE_NAME)?.delete()
        val tempArchive = mangaDir.createFile(META_INFO_TEMP_FILE_NAME) ?: error("Failed to create meta.info archive")
        ZipWriter(context, tempArchive).use { writer ->
            writer.write(manifestFile)
            coverAsset?.let { asset ->
                tempDir.findFile(asset.entryName)?.let(writer::write)
            }
        }
        mangaDir.findFile(META_INFO_FILE_NAME)?.delete()
        val metaInfoFile = finalizeDownloadFile(tempArchive, mangaDir, META_INFO_FILE_NAME)
        tempDir.delete()

        return metaInfoFile
    }

    private suspend fun resolveMetaInfoCoverAsset(
        manga: Manga,
        source: HttpSource,
    ): MetaInfoCoverAsset? {
        val customCoverFile = coverCache.getCustomCoverFile(manga.id)
        if (customCoverFile.exists()) {
            val extension = ImageUtil.findImageType(customCoverFile.inputStream())?.extension ?: "jpg"
            return MetaInfoCoverAsset("cover.$extension") { customCoverFile.inputStream() }
        }

        val cachedCoverFile = coverCache.getCoverFile(manga.thumbnailUrl)
        if (cachedCoverFile?.exists() == true) {
            val extension = ImageUtil.findImageType(cachedCoverFile.inputStream())?.extension ?: "jpg"
            return MetaInfoCoverAsset("cover.$extension") { cachedCoverFile.inputStream() }
        }

        val coverUrl = manga.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            source.client.newCall(GET(coverUrl, source.headers)).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body.bytes()
                val extension = ImageUtil.getExtensionFromMimeType(response.body.contentType()?.toString()) {
                    bytes.inputStream()
                }
                MetaInfoCoverAsset("cover.$extension") { bytes.inputStream() }
            }
        }.getOrElse { error ->
            logcat(LogPriority.WARN, error) { "Failed to fetch cover for meta.info: ${manga.title}" }
            null
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Download.State.UPLOADING.value }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update { queue ->
            // Callers filter off the queue's thread, so a chapter may have been queued in the meantime
            val queuedChapterIds = queue.mapTo(HashSet()) { it.chapter.id }
            val toAdd = downloads.filter { it.chapter.id !in queuedChapterIds }
            toAdd.forEach { download ->
                download.status = Download.State.QUEUE
            }
            store.addAll(toAdd)
            queue + toAdd
        }
    }

    private fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (
                download.status == Download.State.DOWNLOADING ||
                download.status == Download.State.UPLOADING ||
                download.status == Download.State.QUEUE
            ) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueIf(predicate: (Download) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (
                    download.status == Download.State.DOWNLOADING ||
                    download.status == Download.State.UPLOADING ||
                    download.status == Download.State.QUEUE
                ) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            queue - downloads
        }
    }

    private fun removeFromQueue(chapters: List<Chapter>) {
        val chapterIds = chapters.map { it.id }
        removeFromQueueIf { it.chapter.id in chapterIds }
    }

    suspend fun removeFromQueue(manga: Manga) = lock.withLock {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (
                    download.status == Download.State.DOWNLOADING ||
                    download.status == Download.State.UPLOADING ||
                    download.status == Download.State.QUEUE
                ) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    /**
     * Replaces the queue with [transform] applied to the current queue. It runs under the lock, so downloads
     * queued or removed meanwhile aren't lost to a stale copy of the queue.
     */
    suspend fun updateQueue(transform: (List<Download>) -> List<Download>) = editQueue {
        // One update, since chapters are queued without the lock
        _queueState.update { queue ->
            val downloads = transform(queue).distinctBy { it.chapter.id }
            val kept = downloads.mapTo(HashSet()) { it.chapter.id }
            queue.forEach { download ->
                if (download.chapter.id !in kept &&
                    (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE)
                ) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            downloads.forEach { it.status = Download.State.QUEUE }
            store.clear()
            store.addAll(downloads)
            downloads
        }
    }

    /**
     * Removes [chapters] from the queue, keeping the state of the downloads that stay.
     */
    suspend fun dequeue(chapters: List<Chapter>) = editQueue {
        removeFromQueue(chapters)
    }

    private suspend inline fun editQueue(edit: () -> Unit) = lock.withLock {
        val wasRunning = isRunning
        if (wasRunning) pauseLocked()

        edit()

        if (wasRunning) {
            if (queueState.value.isEmpty()) stopLocked() else startLocked()
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30
        private const val META_INFO_FILE_NAME = "meta.info"
        private const val META_INFO_TEMP_FILE_NAME = "meta.info.tmp"
        private const val META_INFO_MANIFEST_FILE_NAME = "manifest.json"
        private const val CBZ_RANDOM_NONCE_FILE_NAME = ".mihon-random-nonce"
        private const val MAX_PARALLEL_CLOUD_UPLOADS = 5
        private const val CLOUD_UPLOAD_MAX_RETRIES = 3
        private const val CLOUD_UPLOAD_MAX_ATTEMPTS = CLOUD_UPLOAD_MAX_RETRIES + 1
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024

private fun combineRemotePath(vararg parts: String): String {
    return normalizePath(parts.joinToString("/") { it.trim('/') })
}

internal fun cloudRemoteDirectoryPath(
    destination: String,
    categoryDirName: String?,
    sourceDirName: String,
    mangaDirName: String,
): String {
    val parentDirName = categoryDirName ?: sourceDirName
    return combineRemotePath(destination, parentDirName, mangaDirName)
}

internal fun UniFile.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    openInputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun Long.toMetaInfoStatus(): String = when (toInt()) {
    SManga.ONGOING -> "ongoing"
    SManga.COMPLETED -> "completed"
    SManga.LICENSED -> "licensed"
    SManga.PUBLISHING_FINISHED -> "publishing_finished"
    SManga.CANCELLED -> "cancelled"
    SManga.ON_HIATUS -> "on_hiatus"
    else -> "unknown"
}

private data class CloudSyncTarget(
    val config: CloudSyncConfig,
    val destination: String,
    val id: String,
)

internal enum class CloudSyncChapterAction {
    Skip,
    Download,
    Upload,
    UnsupportedLocalDownload,
}

internal fun cloudSyncChapterAction(
    isUploaded: Boolean,
    hasLocalDownload: Boolean,
    isLocalCbz: Boolean,
): CloudSyncChapterAction {
    return when {
        isUploaded -> CloudSyncChapterAction.Skip
        !hasLocalDownload -> CloudSyncChapterAction.Download
        isLocalCbz -> CloudSyncChapterAction.Upload
        else -> CloudSyncChapterAction.UnsupportedLocalDownload
    }
}

private class CloudUploadTask(record: CloudUploadRecord, val download: Download) {
    var record = record
        private set

    init {
        updateRecord(record)
    }

    fun updateRecord(record: CloudUploadRecord) {
        this.record = record
        if (!record.isChapter) return
        download.uploadProgress = 0
        download.status = when {
            record.failed -> Download.State.ERROR
            record.paused -> Download.State.PAUSED
            else -> Download.State.QUEUE
        }
    }
}

private class CloudUploadTargetChangedException :
    IllegalStateException("Restore the original WebDAV settings to resume this upload")

@Serializable
private data class MetaInfoManifest(
    val schemaVersion: Int,
    val manga: MetaInfoManga,
    val chapters: List<MetaInfoChapter>,
)

@Serializable
private data class MetaInfoManga(
    val id: Long,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val status: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val source: String,
    val url: String,
    val cover: String? = null,
)

@Serializable
private data class MetaInfoChapter(
    val id: Long,
    val title: String,
    val chapterNumber: Double? = null,
    val scanlator: String? = null,
    val url: String,
    val sourceOrder: Long,
    val file: String,
)

private data class MetaInfoCoverAsset(
    val entryName: String,
    val openStream: () -> java.io.InputStream,
)
