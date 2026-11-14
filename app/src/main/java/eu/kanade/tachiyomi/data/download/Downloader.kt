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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import mihon.core.archive.archiveReader
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
import tachiyomi.domain.download.service.CloudChapterUpload
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ProtocolException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.coroutineContext
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
    private val libraryCloudSync: LibraryCloudSync,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val cloudUploadScheduler: CloudUploadScheduler,
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
    private val releaseLookupSlots = Semaphore(4)
    private val cloudUploadTasks = linkedMapOf<String, CloudUploadTask>()
    private val activeCloudUploadJobs = mutableMapOf<String, Job>()
    private val cloudUploadRunnerLock = Mutex()
    private val uploadQueueChanges = MutableStateFlow(0L)
    private var cloudUploadWorkScheduled = false
    private val deletingRemoteMangas = ConcurrentHashMap.newKeySet<Long>()
    private val metaInfoLock = Mutex()
    private val chapterArchiveLock = Mutex()
    private var cloudUploadOrder = 0L
    private val retriesRequested = MutableStateFlow(0)
    private val runnableQueue = combine(queueState, libraryCloudSync.selection, retriesRequested) { queue, _, _ ->
        queue.filter(::canTransfer)
    }.distinctUntilChanged()

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

    private val restoreDownloadsJob = scope.launch(Dispatchers.Main, CoroutineStart.UNDISPATCHED) {
        addAllToQueue(withContext(Dispatchers.IO) { store.restore() })
    }

    init {
        scope.launch(Dispatchers.IO) {
            restoreCloudUploadsJob.join()
            downloadPreferences.lowPowerMode.changes().distinctUntilChanged().collect {
                uploadLock.withLock { startCloudUploadsLocked() }
            }
        }

        scope.launch(Dispatchers.IO) {
            libraryCloudSync.selection.filterNotNull()
                .distinctUntilChanged { old, new -> old.rules == new.rules && old.mangaIds == new.mangaIds }
                .collect { selected ->
                    restoreCloudUploadsJob.join()
                    uploadLock.withLock {
                        if (libraryCloudSync.currentRules() != selected.rules) return@withLock
                        cloudUploadTasks.values.toList().forEach { task ->
                            val record = task.record
                            if (!canUpload(task, selected.rules.target)) {
                                activeCloudUploadJobs[record.key]?.cancel()
                                if (record.automatic && !selected.retains(task.download)) {
                                    removeCloudUploadLocked(task)
                                } else if (record.isChapter && !record.paused) {
                                    task.download.uploadProgress = 0
                                    task.download.status = Download.State.PAUSED
                                }
                            } else if (record.isChapter && !record.paused && record.key !in activeCloudUploadJobs) {
                                task.download.status = Download.State.QUEUE
                            }
                        }
                        publishCloudUploadQueue()
                        startCloudUploadsLocked()
                    }

                    restoreDownloadsJob.join()
                    lock.withLock {
                        if (libraryCloudSync.currentRules() != selected.rules) return@withLock
                        val shouldRestart = isRunning && queueState.value.any { !canTransfer(it) }
                        if (shouldRestart) pauseLocked()
                        removeFromQueueIf { it.automaticSyncTarget != null && !selected.retains(it) }
                        queueState.value.filter { it.isCloudSync }.forEach { download ->
                            val transferable = canTransfer(download)
                            if (!transferable && download.status == Download.State.QUEUE) {
                                download.status = Download.State.PAUSED
                            } else if (transferable && download.status == Download.State.PAUSED) {
                                download.status = Download.State.QUEUE
                            }
                        }
                        retriesRequested.update { it + 1 }
                        if (shouldRestart) {
                            isPaused = false
                            if (!startLocked()) stopLocked()
                        }
                    }
                }
        }
    }

    private fun canTransfer(download: Download): Boolean {
        if (download.isCloudSync && download.manga.id in deletingRemoteMangas) return false
        download.automaticSyncTarget?.let { return libraryCloudSync.canTransfer(download.manga.id, it) }
        if (!download.explicitCloudSync) return true
        val target = cloudSyncTargetOrNull()?.id ?: return false
        return download.explicitSyncTarget == null || download.explicitSyncTarget == target
    }

    private fun CloudSyncSelection.retains(download: Download): Boolean =
        (rules.target == null || rules.target == download.automaticSyncTarget) && download.manga.id in mangaIds

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    suspend fun start(retryFailed: Boolean = false): Boolean {
        restoreDownloadsJob.join()
        if (retryFailed) libraryCloudSync.selection.filterNotNull().first()
        return lock.withLock {
            if (retryFailed) {
                val failed = queueState.value.filter { it.status == Download.State.ERROR && canTransfer(it) }
                failed.forEach(::retryDownload)
                if (failed.isNotEmpty()) retriesRequested.update { it + 1 }
            }
            startLocked()
        }
    }

    /**
     * Stops the downloader.
     */
    suspend fun stop(reason: String? = null) = lock.withLock { stopLocked(reason) }

    // Download jobs check these under the lock: by the time they get it, chapters may have been queued or the
    // downloader stopped and started again, and that newer run must not be stopped
    private suspend fun stopIfFinished(owner: Job) = lock.withLock { stopIfFinishedLocked(owner) }

    private fun stopIfFinishedLocked(owner: Job) {
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
    suspend fun clearQueue() {
        restoreDownloadsJob.join()
        val interruptedDownload = lock.withLock {
            val interrupted = downloaderJob
            cancelDownloaderJob()
            internalClearQueue()
            isPaused = false
            interrupted
        }
        withContext(NonCancellable) {
            interruptedDownload?.join()
            lock.withLock {
                if (downloaderJob === interruptedDownload) notifier.onComplete()
            }
        }
    }

    internal suspend fun clearAllTransfers() = withContext(Dispatchers.IO) {
        restoreDownloadsJob.join()
        restoreCloudUploadsJob.join()
        val (jobs, temporaryFiles) = lock.withLock {
            uploadLock.withLock {
                val uploads = cloudUploadTasks.values.map { it.record }
                val interruptedDownload = downloaderJob
                cancelDownloaderJob()
                internalClearQueue()
                isPaused = false
                notifier.dismissProgress()

                val active = activeCloudUploadJobs.values.toList()
                active.forEach { it.cancel() }
                store.clearUploads()
                cloudUploadTasks.clear()
                publishCloudUploadQueue()
                (active + listOfNotNull(interruptedDownload)) to
                    uploads.filter { !it.isChapter || it.temporaryArchive }.map { it.fileUri }
            }
        }
        withContext(NonCancellable) {
            jobs.forEach { it.join() }
            temporaryFiles.forEach { uri -> UniFile.fromUri(context, uri.toUri())?.delete() }
        }
    }

    private fun startLocked(): Boolean {
        val queue = queueState.value
        if (isRunning || queue.isEmpty()) {
            return false
        }

        val pending = queue.filter {
            it.status != Download.State.DOWNLOADED && canTransfer(it) &&
                it.status != Download.State.ERROR
        }
        if (pending.isEmpty()) {
            logcat(LogPriority.INFO) {
                val failed = queue.count { it.status == Download.State.ERROR }
                val blocked = queue.count { !canTransfer(it) }
                "No eligible downloads in queue: ${queue.size} total, $failed failed, $blocked outside the active sync selection"
            }
            return false
        }
        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }

        isPaused = false
        notifier.dismissProgress()

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    private fun stopLocked(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING || it.status == Download.State.UPLOADING }
            .forEach { it.status = Download.State.QUEUE }

        if (reason != null) {
            notifier.dismissProgress()
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

            var appliedLowPowerMode: Boolean? = null
            downloadPreferences.lowPowerMode.changes().distinctUntilChanged().collectLatest { lowPowerMode ->
                if (appliedLowPowerMode != null) {
                    // The previous workers have stopped. Keep their partial files and return their chapters to the queue.
                    lock.withLock {
                        queueState.value.filter { it.status == Download.State.DOWNLOADING }
                            .forEach { it.status = Download.State.QUEUE }
                    }
                }
                appliedLowPowerMode = lowPowerMode
                runDownloads(owner, lowPowerMode)
            }
        }
    }

    private suspend fun runDownloads(owner: Job, lowPowerMode: Boolean) {
        val activeDownloadsFlow = combine(
            runnableQueue,
            downloadPreferences.parallelSourceLimit.changes(),
            retriesRequested,
        ) { queue, limit, retry -> Triple(queue, limit, retry) }
            .transformLatest { (queue, parallelCount) ->
                while (true) {
                    val activeDownloads = queue.asSequence()
                        // Ignore completed downloads, leave them in the queue
                        .filter { it.status.value <= Download.State.UPLOADING.value }
                        .groupBy { it.source }
                        .toList()
                        .take(if (lowPowerMode) 1 else parallelCount)
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

        supervisorScope {
            val sourceJobs = mutableMapOf<HttpSource, Job>()
            val downloadJobs = ConcurrentHashMap<Download, Job>()

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
                if (lowPowerMode && sourceJobsToStop.isNotEmpty()) {
                    // Blocking file writes from the old source must finish before starting the next source.
                    withContext(NonCancellable) { sourceJobsToStop.values.forEach { it.join() } }
                    currentCoroutineContext().ensureActive()
                }

                val sourcesToStart = activeSources.filter { it !in sourceJobs }
                sourcesToStart.forEach { source ->
                    sourceJobs[source] = launchSourceJob(source, downloadJobs, owner, lowPowerMode)
                }
            }
        }
    }

    /**
     * Downloads chapters in queue order with shared page slots. In low power mode each chapter finishes before
     * the next starts. Otherwise, the next chapter starts once every page of the current one has a slot.
     */
    private fun CoroutineScope.launchSourceJob(
        source: HttpSource,
        downloadJobs: MutableMap<Download, Job>,
        owner: Job,
        lowPowerMode: Boolean,
    ) = launch(Dispatchers.IO) {
        val slots = Semaphore(if (lowPowerMode) 1 else downloadPreferences.parallelPageLimit.get())
        while (true) {
            // A canceled job can mark a chapter as downloading after it's been reset, so that counts as waiting too
            val download = combine(runnableQueue, retriesRequested) { queue, _ ->
                queue.firstOrNull {
                    it.source == source &&
                        canTransfer(it) &&
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
            if (lowPowerMode) job.join() else pagesStarted.await()
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
            if (download !in queueState.value || !canTransfer(download)) return@launch

            downloadChapter(download, slots, pagesStarted, owner)
            stopIfFinished(owner)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            notifier.onError(e.message)
            failDownload(download)
            if (download.isCloudSync) {
                stopIfFinished(owner)
            } else {
                stopIfCurrent(owner)
            }
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

    private suspend fun failDownload(download: Download) = lock.withLock {
        coroutineContext.ensureActive()
        if (queueState.value.none { it === download }) return@withLock
        if (!canTransfer(download)) {
            download.status = Download.State.QUEUE
            return@withLock
        }
        store.setFailed(download, true)
        download.status = Download.State.ERROR
    }

    private fun retryDownload(download: Download) {
        store.setFailed(download, false)
        download.pages = null
        download.status = Download.State.QUEUE
    }

    internal suspend fun retryCloudTransfers(matches: (Download) -> Boolean): Int {
        restoreDownloadsJob.join()
        restoreCloudUploadsJob.join()
        val retried = lock.withLock {
            val downloads = queueState.value.filter {
                it.isCloudSync && it.status == Download.State.ERROR && canTransfer(it) && matches(it)
            }
            downloads.forEach(::retryDownload)
            if (downloads.isNotEmpty()) retriesRequested.update { it + 1 }
            downloads.size
        }
        uploadLock.withLock {
            val target = cloudSyncTargetOrNull()?.id ?: return@withLock
            cloudUploadTasks.values
                .filter {
                    it.record.failed && it.record.targetId == target && canTransfer(it.download) && matches(it.download)
                }
                .forEach { setCloudUploadPaused(it, false) }
            startCloudUploadsLocked()
        }
        return retried
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     * @return true if the downloader should be started.
     */
    suspend fun queueChapters(
        manga: Manga,
        chapters: List<Chapter>,
        autoStart: Boolean,
        automaticTarget: String? = null,
        explicitCloudSync: Boolean = false,
        explicitTarget: String? = null,
    ): Boolean {
        if (chapters.isEmpty()) return false

        val source = sourceManager.get(manga.source) as? HttpSource ?: return false
        restoreDownloadsJob.join()
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
                .map { Download(source, manga, it, automaticTarget, explicitCloudSync, explicitTarget) }
                .toList()
        }

        if (chaptersToQueue.isNotEmpty()) {
            lock.withLock {
                addAllToQueue(chaptersToQueue.filter(::canTransfer))
            }

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

    internal suspend fun deleteRemoteManga(
        manga: Manga,
        otherMangas: List<Manga>,
        onDeleted: suspend () -> Unit,
    ) {
        val target = cloudSyncTargetOrNull() ?: error("Cloud storage is not configured")
        val source = sourceManager.getOrStub(manga.source)
        check(deletingRemoteMangas.add(manga.id)) { "This manga is already being removed" }
        try {
            restoreDownloadsJob.join()
            restoreCloudUploadsJob.join()
            val prefix = "${target.id}:${manga.id}:"
            val recordedFolders = downloadPreferences.remoteSyncMangaFolders().get()
            val queued = uploadLock.withLock {
                cloudUploadTasks.values.map { it.record }.filter { it.mangaId == manga.id && it.targetId == target.id }
            }
            val folders = (
                recordedFolders.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) } +
                    queued.map { it.remoteDirectory }
                ).toSet().ifEmpty {
                setOf(cloudRemoteDirectory(manga, source, target))
            }
            val otherRecordedFolders = recordedFolders.filter {
                it.startsWith("${target.id}:") && !it.startsWith(prefix)
            }.map { it.substringAfter(':').substringAfter(':') }
            val title = provider.getMangaDirName(manga.title)
            val otherFolders = otherMangas.filter {
                provider.getMangaDirName(it.title).equals(title, ignoreCase = true)
            }.map { cloudRemoteDirectory(it, sourceManager.getOrStub(it.source), target) }
            val root = normalizePath(target.destination).trimEnd('/')
            check(
                folders.all { folder ->
                    folder.startsWith("$root/") && folder != "$root/" &&
                        folder.split('/').none { it == "." || it == ".." } &&
                        (otherRecordedFolders + otherFolders).none { it.equals(folder, ignoreCase = true) }
                },
            ) { "The cloud manga folder is shared or outside the sync destination" }

            var interruptedDownload: Job? = null
            editQueue {
                interruptedDownload = downloaderJob
                removeFromQueueIf { it.manga.id == manga.id }
            }
            interruptedDownload?.join()
            val jobs = uploadLock.withLock {
                queued.mapNotNull { activeCloudUploadJobs[it.key]?.also(Job::cancel) }
            }
            jobs.forEach { it.join() }
            folders.forEach { folder ->
                check(cloudSyncTargetOrNull()?.id == target.id) { "Cloud storage settings changed" }
                cloudSyncService.storage(target.config).deleteMangaCollection(folder)
            }

            withContext(NonCancellable) {
                uploadLock.withLock {
                    cloudUploadTasks.values.filter { it.record.mangaId == manga.id && it.record.targetId == target.id }
                        .forEach(::removeCloudUploadLocked)
                    publishCloudUploadQueue()
                }
                val chapterIds = getChaptersByMangaId.await(manga.id).map { it.id } + queued.map { it.chapterId }
                downloadPreferences.clearRemoteSyncManga(manga.id, target.id, chapterIds)
                onDeleted()
            }
        } finally {
            withContext(NonCancellable) {
                deletingRemoteMangas.remove(manga.id)
                uploadLock.withLock { startCloudUploadsLocked() }
            }
        }
    }

    internal suspend fun releaseCloudStorage(manga: Manga, targetId: String?): CloudStorageReleaseResult {
        if (targetId == null) return CloudStorageReleaseResult()
        val target = cloudSyncTargetOrNull()?.takeIf { it.id == targetId }
        val source = sourceManager.get(manga.source) as? HttpSource ?: return CloudStorageReleaseResult()
        val uploaded = downloadPreferences.cloudUploadedChapterIds().get()
        val allChapters = getChaptersByMangaId.await(manga.id)
        val chapters = allChapters.filter { "$targetId:${it.id}" in uploaded }
        if (chapters.isEmpty()) return CloudStorageReleaseResult()

        restoreDownloadsJob.join()
        restoreCloudUploadsJob.join()
        val mangaDirs = provider.findMangaDirs(manga.title, source)
        val files = provider.findDownloadedChapters(chapters, mangaDirs)
        val candidates = chapters.mapNotNull { chapter ->
            val file = files[chapter.id] ?: return@mapNotNull null
            val name = file.name ?: return@mapNotNull null
            val isDirectory = file.isDirectory
            CloudStorageCandidate(
                chapter,
                file,
                if (isDirectory) "$name.cbz" else name,
                isDirectory,
                file.length(),
                file.lastModified(),
            )
        }
        if (candidates.isEmpty()) return CloudStorageReleaseResult()

        if (target == null || cloudSyncTargetOrNull() != target) {
            return CloudStorageReleaseResult(
                kept = candidates.size,
                reasons = mapOf(CloudStorageReleaseReason.TargetChanged to candidates.size),
            )
        }

        val queuedIds = lock.withLock {
            uploadLock.withLock {
                (
                    queueState.value.map { it.chapter.id } +
                        cloudUploadTasks.values.filter { it.record.isChapter }.map { it.record.chapterId }
                    ).toSet()
            }
        }
        val reasons = mutableMapOf<CloudStorageReleaseReason, Int>()
        val pending = candidates.filterNot {
            it.chapter.id in queuedIds || manga.id in deletingRemoteMangas
        }
        val busyCount = candidates.size - pending.size
        if (busyCount > 0) reasons[CloudStorageReleaseReason.InTransfer] = busyCount
        val remoteChecks = checkReleaseCopies(manga, source, target, allChapters, pending)
        val released = mutableListOf<Chapter>()
        try {
            for (candidate in pending) {
                val (chapter, file, _, isDirectory, size, modified) = candidate
                val remoteReason = remoteChecks.getValue(chapter.id)
                val reason = if (cloudSyncTargetOrNull() != target) CloudStorageReleaseReason.TargetChanged else remoteReason
                val finalReason = reason ?: try {
                    lock.withLock {
                        uploadLock.withLock {
                            when {
                                cloudSyncTargetOrNull() != target -> CloudStorageReleaseReason.TargetChanged
                                manga.id in deletingRemoteMangas ||
                                    queueState.value.any { it.chapter.id == chapter.id } ||
                                    "chapter:${chapter.id}" in cloudUploadTasks -> CloudStorageReleaseReason.InTransfer
                                !downloadPreferences.isChapterUploadedToCloud(chapter.id, target.id) ||
                                    file.isDirectory != isDirectory || file.length() != size ||
                                    file.lastModified() != modified -> CloudStorageReleaseReason.LocalChanged
                                !file.delete() -> CloudStorageReleaseReason.LocalDeleteFailed
                                else -> null
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    logcat(LogPriority.WARN, error) { "Could not release storage for chapter ${chapter.id}" }
                    CloudStorageReleaseReason.LocalDeleteFailed
                }
                if (finalReason == null) {
                    released += chapter
                } else {
                    reasons[finalReason] = reasons.getOrDefault(finalReason, 0) + 1
                }
            }
        } finally {
            if (released.isNotEmpty()) {
                withContext(NonCancellable) {
                    // A chapter can still have a local copy in another download layout.
                    val remainingIds = provider.findDownloadedChapters(released, mangaDirs).keys
                    val removed = released.filterNot { it.id in remainingIds }
                    if (removed.isNotEmpty()) cache.removeChapters(removed, manga)
                }
            }
        }
        return CloudStorageReleaseResult(released.size, candidates.size - released.size, reasons)
    }

    private suspend fun checkReleaseCopies(
        manga: Manga,
        source: HttpSource,
        target: CloudSyncTarget,
        chapters: List<Chapter>,
        candidates: List<CloudStorageCandidate>,
    ): Map<Long, CloudStorageReleaseReason?> {
        if (candidates.isEmpty()) return emptyMap()

        val uploads = downloadPreferences.cloudChapterUploads(manga.id, target.id)
        val folders = downloadPreferences.remoteSyncMangaFolders().get()
        val prefix = "${target.id}:${manga.id}:"
        val historicalDirectories = folders.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        val legacy = candidates.filterNot { it.chapter.id in uploads }
        val legacyDirectories = if (legacy.isEmpty()) {
            emptyList()
        } else {
            historicalDirectories + cloudRemoteDirectory(manga, source, target)
        }
        val directories = (candidates.mapNotNull { uploads[it.chapter.id]?.directory } + legacyDirectories).distinct()
        val legacyNames = if (legacy.isEmpty()) {
            emptyMap()
        } else {
            provider.getChapterLookupNames(legacy.map { it.chapter }, chapters)
        }
        val storage = cloudSyncService.storage(target.config)
        val results = mutableMapOf<Long, CloudStorageReleaseReason?>()
        candidates.forEach { results[it.chapter.id] = CloudStorageReleaseReason.RemoteMissing }

        for (directory in directories) {
            if (cloudSyncTargetOrNull() != target) {
                results.replaceAll { _, reason -> reason?.let { CloudStorageReleaseReason.TargetChanged } }
                break
            }
            val checks = candidates.mapNotNull { candidate ->
                val chapterId = candidate.chapter.id
                if (results[chapterId] == null) return@mapNotNull null
                val upload = uploads[chapterId]
                if (upload?.directory != directory && (upload != null || directory !in legacyDirectories)) {
                    return@mapNotNull null
                }
                val name = upload?.fileName ?: candidate.remoteName
                if (!isCloudArchiveName(name) || (upload == null && name !in legacyNames.getValue(chapterId))) {
                    if (results[chapterId] == CloudStorageReleaseReason.RemoteMissing) {
                        results[chapterId] = CloudStorageReleaseReason.RemoteMismatch
                    }
                    return@mapNotNull null
                }
                CloudReleaseLookup(candidate, name, upload?.size)
            }
            if (checks.isEmpty()) continue
            if (!isInSyncDestination(directory, target) ||
                isRemoteMangaFolderShared(directory, target.id, prefix, folders)
            ) {
                checks.forEach {
                    val id = it.candidate.chapter.id
                    if (results[id] == CloudStorageReleaseReason.RemoteMissing) {
                        results[id] = CloudStorageReleaseReason.RemoteMismatch
                    }
                }
                continue
            }
            for ((id, reason) in lookupReleaseDirectory(storage, directory, checks)) {
                if (reason != CloudStorageReleaseReason.RemoteMissing &&
                    (reason != CloudStorageReleaseReason.RemoteMismatch ||
                        results[id] != CloudStorageReleaseReason.RemoteUnavailable)
                ) {
                    results[id] = reason
                }
            }
        }
        return results
    }

    private suspend fun lookupReleaseDirectory(
        storage: CloudStorage,
        directory: String,
        checks: List<CloudReleaseLookup>,
    ): List<Pair<Long, CloudStorageReleaseReason?>> {
        val names = checks.mapTo(HashSet()) { it.name }
        val listing = try {
            if (names.size == 1) {
                null
            } else {
                releaseLookup { storage.listRemoteFileSizes(directory, names) }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            logcat(LogPriority.WARN, error) { "Could not list cloud files for storage release" }
            return checks.map { it.candidate.chapter.id to CloudStorageReleaseReason.RemoteUnavailable }
        }

        return coroutineScope {
            checks.map { check ->
                async {
                    val id = check.candidate.chapter.id
                    if (listing != null && check.name !in listing) {
                        return@async id to CloudStorageReleaseReason.RemoteMissing
                    }
                    val size = try {
                        listing?.get(check.name) ?: releaseLookup { storage.lookupRemoteFileSize(directory, check.name) }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        logcat(LogPriority.WARN, error) { "Could not verify cloud file for chapter $id" }
                        return@async id to CloudStorageReleaseReason.RemoteUnavailable
                    }
                    id to when {
                        size == null -> CloudStorageReleaseReason.RemoteMissing
                        size <= 0 || (check.uploadedSize != null && size != check.uploadedSize) ||
                            (!check.candidate.isDirectory && size != check.candidate.size) -> {
                            CloudStorageReleaseReason.RemoteMismatch
                        }
                        else -> null
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun <T> releaseLookup(block: suspend () -> T): T = releaseLookupSlots.withPermit {
        val response = withTimeoutOrNull(15.seconds) { CloudReleaseResponse(block()) }
            ?: throw IOException("Cloud metadata request timed out")
        response.value
    }

    internal suspend fun queueChaptersForCloudSync(
        manga: Manga,
        chapters: List<Chapter>,
        autoStart: Boolean,
        automaticTarget: String? = null,
    ): CloudSyncQueueResult {
        val uniqueChapters = chapters.distinctBy { it.id }
        if (uniqueChapters.isEmpty()) return CloudSyncQueueResult()

        val source = sourceManager.get(manga.source) as? HttpSource
            ?: return CloudSyncQueueResult(failed = uniqueChapters.size)
        val target = cloudSyncTargetOrNull() ?: return CloudSyncQueueResult(failed = uniqueChapters.size)
        if (automaticTarget != null &&
            (automaticTarget != target.id || !libraryCloudSync.canTransfer(manga.id, target.id))
        ) {
            return CloudSyncQueueResult()
        }
        val retriedDownloads = if (automaticTarget == null) {
            val ids = uniqueChapters.mapTo(HashSet()) { it.id }
            retryCloudTransfers { it.manga.id == manga.id && it.chapter.id in ids }
        } else {
            0
        }
        restoreCloudUploadsJob.join()
        val initialPending = if (automaticTarget == null) {
            queueState.value.filter { download ->
                download.manga.id == manga.id && download.explicitCloudSync && canTransfer(download) &&
                    (download.status == Download.State.QUEUE || download.status == Download.State.PAUSED)
            }.mapTo(HashSet()) { it.chapter.id }
        } else {
            emptySet()
        }
        val uploaded = downloadPreferences.cloudUploadedChapterIds().get()
        val alreadyUploading = if (automaticTarget != null) {
            uploadLock.withLock {
                cloudUploadTasks.values.asSequence().map { it.record }
                    .filter { it.isChapter && it.targetId == target.id }
                    .map { it.chapterId }
                    .toSet()
            }
        } else {
            emptySet()
        }
        val overwrite = automaticTarget == null && downloadPreferences.cloudSyncOverwriteUploads().get()
        val pending = uniqueChapters.filter {
            (overwrite || "${target.id}:${it.id}" !in uploaded) && it.id !in alreadyUploading
        }
        if (pending.isEmpty()) {
            if (autoStart && retriedDownloads > 0 && !isRunning) DownloadWorker.start(context)
            return CloudSyncQueueResult(downloadsQueued = retriedDownloads, skipped = uniqueChapters.size)
        }
        val mangaDirs = provider.findMangaDirs(manga.title, source)
        val localFiles = provider.findDownloadedChapters(pending, mangaDirs)
        if (target.config is WebDavConfig) {
            try {
                // Local files go through the upload worker's conflict policy.
                discoverWebDavChapters(
                    manga,
                    source,
                    target,
                    uniqueChapters,
                    pending.filterNot { it.id in localFiles },
                    automaticTarget != null,
                )
            } catch (error: IOException) {
                if (automaticTarget != null) throw error
                logcat(LogPriority.WARN, error) { "Could not scan WebDAV for manga ${manga.id}" }
                return CloudSyncQueueResult(failed = pending.size, skipped = uniqueChapters.size - pending.size)
            }
        }
        if (cloudSyncTargetOrNull() != target) return CloudSyncQueueResult()

        var uploadsQueued = 0
        var skipped = uniqueChapters.size - pending.size
        var failed = 0
        var directUploadDownload: Download? = null
        val chaptersToDownload = mutableListOf<Chapter>()

        pending.forEach { chapter ->
            val download = Download(
                source,
                manga,
                chapter,
                automaticTarget,
                explicitCloudSync = automaticTarget == null,
                explicitSyncTarget = target.id.takeIf { automaticTarget == null },
            )
            if (!canTransfer(download)) return@forEach
            val downloadedChapter = localFiles[chapter.id]
            when (
                cloudSyncChapterAction(
                    isUploaded = (!overwrite || downloadedChapter == null) &&
                        downloadPreferences.isChapterUploadedToCloud(chapter.id, target.id),
                    hasLocalDownload = downloadedChapter != null,
                    isLocalCbz =
                    downloadedChapter?.isFile == true &&
                        downloadedChapter.name?.endsWith(".cbz", ignoreCase = true) == true,
                )
            ) {
                CloudSyncChapterAction.Skip -> skipped++
                CloudSyncChapterAction.Download -> chaptersToDownload += chapter
                CloudSyncChapterAction.Upload -> {
                    if (enqueueDownloadedChapterUpload(download, downloadedChapter!!, target)) {
                        uploadsQueued++
                        directUploadDownload = directUploadDownload ?: download
                    } else {
                        failed++
                    }
                }
                CloudSyncChapterAction.UnsupportedLocalDownload -> {
                    val mangaDir = mangaDirs.firstOrNull()
                    if (automaticTarget != null && downloadedChapter?.isDirectory == true && mangaDir != null &&
                        enqueueChapterDirectoryUpload(download, downloadedChapter, mangaDir, target)
                    ) {
                        uploadsQueued++
                        directUploadDownload = directUploadDownload ?: download
                    } else {
                        failed++
                    }
                }
            }
        }

        val shouldStart = queueChapters(
            manga,
            chaptersToDownload,
            autoStart,
            automaticTarget,
            explicitCloudSync = automaticTarget == null,
            explicitTarget = target.id.takeIf { automaticTarget == null },
        )
        val queuedChapterIds = queueState.value.mapTo(mutableSetOf()) { it.chapter.id }
        val downloadsQueued = chaptersToDownload.count { it.id in queuedChapterIds }
        val unqueued = chaptersToDownload.size - downloadsQueued
        if (automaticTarget != null) {
            skipped += unqueued
        } else {
            failed += unqueued
        }
        if (shouldStart ||
            (autoStart && !isRunning && (retriedDownloads > 0 || chaptersToDownload.any { it.id in initialPending }))
        ) {
            DownloadWorker.start(context)
        }

        directUploadDownload?.let { download ->
            mangaDirs.firstOrNull()?.let { updateMetaInfo(download, it, target) }
        }

        return CloudSyncQueueResult(
            downloadsQueued = downloadsQueued,
            uploadsQueued = uploadsQueued,
            skipped = skipped,
            failed = failed,
        )
    }

    private suspend fun discoverWebDavChapters(
        manga: Manga,
        source: HttpSource,
        target: CloudSyncTarget,
        chapters: List<Chapter>,
        missing: List<Chapter>,
        automatic: Boolean,
    ) {
        if (missing.isEmpty() || manga.id in deletingRemoteMangas) return

        val allChapters = chapters + getChaptersByMangaId.await(manga.id)
        val candidates = provider.getChapterLookupNames(missing, allChapters)
            .mapValues { (_, names) -> names.filter { it.endsWith(".cbz") } }
            .filterValues { it.isNotEmpty() }.toMutableMap()
        if (candidates.isEmpty()) return

        val prefix = "${target.id}:${manga.id}:"
        val currentDirectory = cloudRemoteDirectory(manga, source, target)
        val recordedFolders = downloadPreferences.remoteSyncMangaFolders().get()
        val directories = buildSet {
            add(currentDirectory)
            recordedFolders.filter { it.startsWith(prefix) }.forEach { add(it.removePrefix(prefix)) }
        }.filter { folder ->
            isInSyncDestination(folder, target) &&
                !isRemoteMangaFolderShared(folder, target.id, prefix, recordedFolders)
        }
        if (directories.isEmpty()) return

        val storage = cloudSyncService.storage(target.config)
        val discovered = buildMap {
            for (directory in directories) {
                val names = candidates.values.flatten().toSet()
                val listing = storage.listRemoteFileSizes(directory, names) ?: continue
                val files = listing.filter { (name, size) ->
                    (size ?: storage.remoteFileSize(directory, name))?.let { it > 0 } == true
                }.keys
                val matches = candidates.filterValues { names -> names.any { it in files } }.keys
                if (matches.isEmpty()) continue
                put(directory, matches)
                candidates.keys.removeAll(matches)
                if (candidates.isEmpty()) break
            }
        }
        if (discovered.isEmpty()) return

        val sharedDirectories = getDuplicateLibraryManga(manga).map { it.manga }.filter { other ->
            val title = provider.getMangaDirName(other.title)
            discovered.keys.any { it.substringAfterLast('/').equals(title, ignoreCase = true) }
        }.map { other -> cloudRemoteDirectory(other, sourceManager.getOrStub(other.source), target) }
        uploadLock.withLock {
            if (manga.id in deletingRemoteMangas || cloudSyncTargetOrNull() != target ||
                (automatic && !libraryCloudSync.canTransfer(manga.id, target.id))
            ) {
                return@withLock
            }
            val latestFolders = downloadPreferences.remoteSyncMangaFolders().get()
            for ((folder, ids) in discovered) {
                if (sharedDirectories.any { it.equals(folder, ignoreCase = true) }) continue
                if (isRemoteMangaFolderShared(folder, target.id, prefix, latestFolders)) continue
                downloadPreferences.markRemoteSyncMangaFolder(manga.id, target.id, folder)
                downloadPreferences.markChaptersUploadedToCloud(ids, target.id)
            }
        }
    }

    private fun isRemoteMangaFolderShared(folder: String, target: String, ownPrefix: String, folders: Set<String>) =
        folders.any {
            it.startsWith("$target:") && !it.startsWith(ownPrefix) &&
                it.substringAfter(':').substringAfter(':').equals(folder, ignoreCase = true)
        }

    private fun isInSyncDestination(folder: String, target: CloudSyncTarget): Boolean {
        val root = normalizePath(target.destination).trimEnd('/')
        return folder.startsWith("$root/") && folder != "$root/" &&
            folder.split('/').none { it == "." || it == ".." } && normalizePath(folder) == folder
    }

    /**
     * Downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     * @param slots the page download slots of the chapter's source.
     * @param pagesStarted completed once every page has a slot.
     * @param owner the downloader job that owns this chapter.
     */
    private suspend fun downloadChapter(
        download: Download,
        slots: Semaphore,
        pagesStarted: CompletableDeferred<Unit>,
        owner: Job,
    ) {
        val mangaDir = provider.getMangaDir(download.manga, download.source).getOrElse { e ->
            failDownload(download)
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
            return
        }

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            failDownload(download)
            notifier.onError(
                context.stringResource(MR.strings.download_insufficient_space),
                download.chapter.name,
                download.manga.title,
                download.manga.id,
            )
            return
        }

        val asCbz = downloadPreferences.saveChaptersAsCBZ.get()
        val outputName = provider.chapterOutputName(download.chapter, asCbz)
        val chapterDirname = if (asCbz) outputName.removeSuffix(".cbz") else outputName

        // Being stopped after the download is moved into place but before it's indexed leaves a finished download
        // on disk that is still queued, and downloading it again would save a second copy next to it
        val existingChapter = provider.getChapterLookupNames(download.chapter).firstNotNullOfOrNull(mangaDir::findFile)
        if (existingChapter != null) {
            mangaDir.findFile(chapterDirname + TMP_DIR_SUFFIX)?.delete()
            existingChapter.name?.let { name ->
                cache.addChapter(
                    if (existingChapter.isFile) name.removeSuffix(".cbz") else name,
                    mangaDir,
                    download.manga,
                )
            }
            finishChapterDownload(download, mangaDir, existingChapter, owner)
            return
        }

        try {
            if (asCbz && mangaDir.findFile(outputName) != null) {
                throw IOException("Chapter download target already exists: $outputName")
            }
            download.status = Download.State.DOWNLOADING
            val syncedArchive = downloadSyncedChapter(download, mangaDir, "$chapterDirname.cbz")
            if (syncedArchive != null) {
                mangaDir.findFile(chapterDirname + TMP_DIR_SUFFIX)?.delete()
                cache.addChapter(chapterDirname, mangaDir, download.manga)
                finishChapterDownload(download, mangaDir, syncedArchive, owner)
                return
            }

            val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)!!
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
                    }.invokeOnCompletion { slots.release() }
                }
                pagesStarted.complete(Unit)
            }

            // Do after download completes

            if (!isDownloadSuccessful(download, tmpDir)) {
                failDownload(download)
                return
            }

            createComicInfoFile(
                tmpDir,
                download.manga,
                download.chapter,
                download.source,
            )

            // Only rename the directory if it's downloaded
            val downloadedChapter = if (asCbz) {
                archiveChapter(mangaDir, chapterDirname, tmpDir)
            } else {
                tmpDir.renameTo(chapterDirname)
                mangaDir.findFile(chapterDirname) ?: error("Unable to finalize downloaded chapter: $chapterDirname")
            }
            cache.addChapter(chapterDirname, mangaDir, download.manga)

            DiskUtil.createNoMediaFile(tmpDir, context)

            finishChapterDownload(download, mangaDir, downloadedChapter, owner)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // If the page list threw, it will resume here
            logcat(LogPriority.ERROR, error)
            failDownload(download)
            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    private suspend fun downloadSyncedChapter(download: Download, mangaDir: UniFile, fileName: String): UniFile? {
        if (!downloadPreferences.cloudSyncPreferRemoteDownloads().get()) return null
        val target = cloudSyncTargetOrNull() ?: return null
        if (download.cloudSyncTarget != null && download.cloudSyncTarget != target.id) return null
        val chapter = download.chapter
        if (!downloadPreferences.isChapterUploadedToCloud(chapter.id, target.id)) return null

        val tempName = fileName + TMP_DIR_SUFFIX
        mangaDir.findFile(tempName)?.delete()
        val tempFile = mangaDir.createFile(tempName) ?: return null
        try {
            val downloaded = try {
                val prefix = "${target.id}:${download.manga.id}:"
                val recordedFolders = downloadPreferences.remoteSyncMangaFolders().get()
                val directories = buildSet {
                    add(cloudRemoteDirectory(download, target))
                    recordedFolders
                        .filter { it.startsWith(prefix) }
                        .forEach { add(it.removePrefix(prefix)) }
                }.filter { folder ->
                    isInSyncDestination(folder, target) &&
                        !isRemoteMangaFolderShared(folder, target.id, prefix, recordedFolders)
                }
                val names = provider.getChapterLookupNames(chapter).filter(::isCloudArchiveName)
                val storage = cloudSyncService.storage(target.config)
                val upload = downloadPreferences.cloudChapterUploads(download.manga.id, target.id)[chapter.id]
                val foundAtUploadPath = upload != null && isInSyncDestination(upload.directory, target) &&
                    !isRemoteMangaFolderShared(upload.directory, target.id, prefix, recordedFolders) &&
                    isCloudArchiveName(upload.fileName) &&
                    storage.downloadCbz(upload.directory, upload.fileName, tempFile)
                val found = foundAtUploadPath || names.any { name ->
                    directories.any { directory ->
                        (directory != upload?.directory || name != upload.fileName) &&
                            storage.downloadCbz(directory, name, tempFile)
                    }
                }
                found && tempFile.archiveReader(context).use { reader ->
                    reader.useEntries { entries -> entries.any { it.isFile && ImageUtil.isImage(it.name) } }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                logcat(LogPriority.WARN, error) { "Cloud download failed for chapter ${chapter.id}" }
                false
            }
            coroutineContext.ensureActive()
            if (!downloaded || cloudSyncTargetOrNull() != target || !canTransfer(download)) return null

            return finalizeDownloadFile(tempFile, mangaDir, fileName)
        } finally {
            mangaDir.findFile(tempName)?.delete()
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
                else -> downloadImage(page, download.source, tmpDir, filename, retry = !download.isCloudSync)
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
    private suspend fun downloadImage(
        page: Page,
        source: HttpSource,
        tmpDir: UniFile,
        filename: String,
        retry: Boolean,
    ): UniFile {
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
                if (retry && attempt < 3) {
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
    private suspend fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
    ): UniFile = withChapterArchiveLock {
        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")!!
        writeChapterArchive(zip, tmpDir)
        val archive = finalizeDownloadFile(zip, mangaDir, "$dirname.cbz")
        tmpDir.delete()
        archive
    }

    private suspend inline fun <T> withChapterArchiveLock(action: () -> T): T =
        if (downloadPreferences.lowPowerMode.get()) chapterArchiveLock.withLock(action = action) else action()

    private fun writeChapterArchive(zip: UniFile, chapterDir: UniFile) {
        ZipWriter(context, zip).use { writer ->
            // Download jobs complete concurrently, and a document provider
            // does not guarantee that listFiles() preserves either creation
            // or display order. ZIP writes are append-only, so keep physical
            // entry offsets in the same natural order used by the reader.
            // This makes sequential Range buffering of remote CBZ files
            // practical instead of scattering consecutive pages throughout
            // the archive.
            chapterDir.listFiles()
                ?.filter { it.isFile && it.name != NOMEDIA_FILE }
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
    }

    private suspend fun enqueueChapterDirectoryUpload(
        download: Download,
        chapterDir: UniFile,
        mangaDir: UniFile,
        target: CloudSyncTarget,
    ): Boolean {
        val name = chapterDir.name ?: return false
        val archive = mangaDir.createFile("$name.sync.${UUID.randomUUID()}$TMP_DIR_SUFFIX") ?: return false
        var queued = false
        try {
            withChapterArchiveLock { writeChapterArchive(archive, chapterDir) }
            queued = enqueueDownloadedChapterUpload(download, archive, target, "$name.cbz", temporaryArchive = true)
            return queued
        } finally {
            if (!queued) archive.delete()
        }
    }

    private suspend fun finishChapterDownload(download: Download, mangaDir: UniFile, chapterFile: UniFile, owner: Job) {
        if (!canTransfer(download)) return
        val scopedUploads = libraryCloudSync.hasConfiguredScope() && !download.explicitCloudSync
        val target = cloudSyncTargetOrNull()?.takeIf {
            (download.cloudSyncTarget == null || download.cloudSyncTarget == it.id) &&
                (!scopedUploads || libraryCloudSync.canTransfer(download.manga.id, it.id))
        }
        val upload = if (target != null && scopedUploads) {
            download.copy(automaticSyncTarget = target.id)
        } else {
            download
        }
        if (target != null && chapterFile.isFile &&
            !downloadPreferences.isChapterUploadedToCloud(download.chapter.id, target.id)
        ) {
            try {
                enqueueDownloadedChapterUpload(upload, chapterFile, target)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportCloudUploadError(download, error)
            }
        }
        updateMetaInfo(upload, mangaDir, target)
        completeDownload(download, owner)
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
                            automatic = download.automaticSyncTarget != null,
                            background = !download.explicitCloudSync,
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
        fileName: String = chapterFile.name.orEmpty(),
        temporaryArchive: Boolean = false,
    ): Boolean {
        if (!fileName.endsWith(".cbz", ignoreCase = true)) return false
        return enqueueCloudUpload(
            CloudUploadRecord(
                mangaId = download.manga.id,
                chapterId = download.chapter.id,
                targetId = target.id,
                remoteDirectory = cloudRemoteDirectory(download, target),
                fileUri = chapterFile.uri.toString(),
                fileName = fileName,
                deleteAfterUpload = !temporaryArchive && downloadPreferences.cloudSyncDeleteAfterUpload().get(),
                automatic = download.automaticSyncTarget != null,
                temporaryArchive = temporaryArchive,
                background = !download.explicitCloudSync,
            ),
            download.copy(),
        )
    }

    private suspend fun cloudRemoteDirectory(
        download: Download,
        cloudSync: CloudSyncTarget,
    ): String = cloudRemoteDirectory(download.manga, download.source, cloudSync)

    private suspend fun cloudRemoteDirectory(
        manga: Manga,
        source: eu.kanade.tachiyomi.source.Source,
        cloudSync: CloudSyncTarget,
    ): String {
        val categoryDirName = if (downloadPreferences.organizeDownloadsByCategory().get()) {
            provider.getMangaCategoryDirName(manga)
        } else {
            null
        }
        return cloudRemoteDirectoryPath(
            destination = cloudSync.destination,
            categoryDirName = categoryDirName,
            sourceDirName = provider.getSourceDirName(source),
            mangaDirName = provider.getMangaDirName(manga.title),
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

    private fun cloudSyncTargetOrNull() = downloadPreferences.activeCloudSyncTarget()

    private suspend fun enqueueCloudUpload(record: CloudUploadRecord, download: Download): Boolean {
        restoreCloudUploadsJob.join()
        return uploadLock.withLock {
            coroutineContext.ensureActive()
            if (record.mangaId in deletingRemoteMangas ||
                cloudSyncTargetOrNull()?.id != record.targetId || !canTransfer(download)
            ) {
                return@withLock false
            }
            val existing = cloudUploadTasks[record.key]
            if (existing != null) {
                // A paused upload stays in the queue until explicitly resumed or canceled.
                return@withLock !record.temporaryArchive && record.isChapter &&
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
        uploadQueueChanges.update { it + 1 }
    }

    private fun canUpload(task: CloudUploadTask, target: String?): Boolean =
        task.record.targetId == target && task.record.mangaId !in deletingRemoteMangas &&
            canTransfer(task.download) &&
            (!task.record.background || !downloadPreferences.cloudSyncAutomaticPaused().get()) &&
            (!task.record.background || task.record.automatic || !libraryCloudSync.hasConfiguredScope() ||
                libraryCloudSync.canTransfer(task.record.mangaId, task.record.targetId))

    internal suspend fun runCloudUploads(
        networkAllowed: () -> Boolean,
        now: () -> Long = System::currentTimeMillis,
    ): Boolean {
        restoreCloudUploadsJob.join()
        libraryCloudSync.selection.filterNotNull().first()
        return cloudUploadRunnerLock.withLock {
            coroutineScope {
                val owner = SupervisorJob(coroutineContext.job)
                val workerScope = CoroutineScope(coroutineContext + owner)
                try {
                    while (true) {
                        val revision = uploadLock.withLock {
                            cloudUploadWorkScheduled = true
                            val target = cloudSyncTargetOrNull()?.id
                            val pending = cloudUploadTasks.values.filter {
                                !it.record.paused && canUpload(it, target)
                            }
                            if (pending.isEmpty()) {
                                cloudUploadWorkScheduled = false
                                return@coroutineScope false
                            }
                            if (!networkAllowed()) return@coroutineScope true

                            pending.asSequence()
                                .filter {
                                    it.record.retryAfterMillis <= now() && it.record.key !in activeCloudUploadJobs
                                }
                                .take(availableCloudUploadSlots())
                                .forEach { startCloudUploadLocked(it, workerScope, now, networkAllowed) }

                            if (activeCloudUploadJobs.isEmpty()) return@coroutineScope true
                            uploadQueueChanges.value
                        }
                        // Wake on a completed upload, a queue edit, or a change in the limit/scope.
                        uploadQueueChanges.first { it != revision }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                } finally {
                    withContext(NonCancellable) {
                        owner.cancel()
                        owner.join()
                    }
                }
            }
        }
    }

    private fun startCloudUploadsLocked(replaceWaitingWork: Boolean = false) {
        if (!scope.isActive) return
        val target = cloudSyncTargetOrNull()?.id
        val hasPendingUploads = cloudUploadTasks.values.any { !it.record.paused && canUpload(it, target) }
        if (hasPendingUploads && (!cloudUploadWorkScheduled || replaceWaitingWork)) {
            cloudUploadWorkScheduled = true
            cloudUploadScheduler.enqueue(downloadPreferences.downloadOnlyOverWifi.get(), replaceWaitingWork)
        }
        cloudUploadTasks.values.filter {
            it.record.isChapter && !it.record.paused && !canUpload(it, target)
        }.forEach {
            it.download.uploadProgress = 0
            it.download.status = Download.State.PAUSED
        }
        uploadQueueChanges.update { it + 1 }
    }

    private fun availableCloudUploadSlots(): Int {
        val limit = if (downloadPreferences.lowPowerMode.get()) {
            DownloadPreferences.LOW_POWER_CLOUD_UPLOAD_LIMIT
        } else {
            downloadPreferences.parallelCloudUploadLimit().get().coerceIn(1, MAX_PARALLEL_CLOUD_UPLOADS)
        }
        return (limit - activeCloudUploadJobs.size).coerceAtLeast(0)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startCloudUploadLocked(
        task: CloudUploadTask,
        owner: CoroutineScope,
        now: () -> Long,
        networkAllowed: () -> Boolean = { true },
    ) {
        if (task.record.isChapter) task.download.status = Download.State.UPLOADING
        // A canceled child must still release its slot even if it never begins transferring.
        val job = owner.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            processCloudUploadTask(task, now, networkAllowed)
        }
        activeCloudUploadJobs[task.record.key] = job
    }

    suspend fun resumeCloudUpload(chapterId: Long) = resumeCloudUploads(chapterId)

    internal suspend fun resumeCloudUploads(chapterId: Long? = null) = withContext(Dispatchers.IO) {
        restoreCloudUploadsJob.join()
        uploadLock.withLock {
            val target = cloudSyncTargetOrNull()?.id
            val (resumable, otherTarget) = chapterUploadsLocked(chapterId)
                .filter { it.record.key !in activeCloudUploadJobs || it.record.paused }
                .partition { it.record.targetId == target }
            otherTarget.firstOrNull()?.let { task ->
                reportCloudUploadError(task.download, CloudUploadTargetChangedException())
            }
            val waitingMetadata = if (chapterId == null) {
                cloudUploadTasks.values.filter {
                    !it.record.isChapter && it.record.retryAfterMillis > 0 && canUpload(it, target) &&
                        it.record.key !in activeCloudUploadJobs
                }
            } else {
                emptyList()
            }
            if (resumable.isEmpty() && waitingMetadata.isEmpty()) return@withLock
            val waiting = resumable.filter { it.record.paused || it.record.retryAfterMillis > 0 } + waitingMetadata
            if (waiting.isNotEmpty()) setCloudUploadsPaused(waiting, false)
            // An existing WorkManager retry has its own backoff even after a chapter is ready again.
            startCloudUploadsLocked(replaceWaitingWork = activeCloudUploadJobs.isEmpty())
        }
    }

    suspend fun pauseCloudUpload(chapterId: Long) = pauseCloudUploads(chapterId)

    internal suspend fun pauseCloudUploads(chapterId: Long? = null) = withContext(Dispatchers.IO) {
        restoreCloudUploadsJob.join()
        uploadLock.withLock {
            val tasks = chapterUploadsLocked(chapterId).filterNot { it.record.paused }
            if (tasks.isEmpty()) return@withLock
            setCloudUploadsPaused(tasks, true)
            tasks.forEach { activeCloudUploadJobs[it.record.key]?.cancel() }
        }
    }

    suspend fun cancelCloudUpload(chapterId: Long) = cancelCloudUploads(chapterId)

    internal suspend fun cancelCloudUploads(chapterId: Long? = null) = withContext(Dispatchers.IO) {
        restoreCloudUploadsJob.join()
        val (jobs, temporaryFiles) = uploadLock.withLock {
            val records = chapterUploadsLocked(chapterId).map { it.record }
            if (records.isEmpty()) return@withContext
            store.removeUploads(records.map { it.key })
            val active = records.mapNotNull { activeCloudUploadJobs[it.key]?.also(Job::cancel) }
            records.forEach { cloudUploadTasks.remove(it.key) }
            publishCloudUploadQueue()
            active to records.filter { it.temporaryArchive }.map { it.fileUri }
        }
        withContext(NonCancellable) {
            jobs.forEach { it.join() }
            temporaryFiles.forEach { uri -> UniFile.fromUri(context, uri.toUri())?.delete() }
        }
    }

    private fun chapterUploadsLocked(chapterId: Long?): List<CloudUploadTask> {
        if (chapterId != null) return listOfNotNull(cloudUploadTasks["chapter:$chapterId"])
        // The upload queue only exposes chapter transfers. Metadata uploads run in the background.
        return cloudUploadTasks.values.filter { it.record.isChapter }
    }

    private fun removeCloudUploadLocked(task: CloudUploadTask) {
        val record = task.record
        store.removeUpload(record.key)
        cloudUploadTasks.remove(record.key)
        if (!record.isChapter || record.temporaryArchive) {
            UniFile.fromUri(context, record.fileUri.toUri())?.delete()
        }
    }

    private fun setCloudUploadPaused(task: CloudUploadTask, paused: Boolean, failed: Boolean = false) {
        val record = task.record.copy(paused = paused, failed = failed, retryAttempts = 0, retryAfterMillis = 0)
        store.saveUpload(record)
        task.updateRecord(record)
    }

    private fun setCloudUploadsPaused(tasks: List<CloudUploadTask>, paused: Boolean) {
        val records = tasks.map { it.record.copy(paused = paused, failed = false, retryAttempts = 0, retryAfterMillis = 0) }
        store.saveUploads(records)
        tasks.zip(records).forEach { (task, record) -> task.updateRecord(record) }
    }

    private suspend fun processCloudUploadTask(task: CloudUploadTask, now: () -> Long, networkAllowed: () -> Boolean) {
        val owner = coroutineContext.job
        val record = task.record
        val target = cloudSyncTargetOrNull()?.takeIf { it.id == record.targetId }
        var file: UniFile? = null
        var failure: Exception? = null
        var attempted = false
        var uploaded = false
        try {
            owner.ensureActive()
            if (!networkAllowed()) return
            val config = target?.config ?: return
            attempted = true
            file = UniFile.fromUri(context, record.fileUri.toUri())?.takeIf { it.isFile }
                ?: error("Local upload file is unavailable: ${record.fileName}")
            val uploadFile = file
            val storage = cloudSyncService.storage(config)
            uploaded = storage.uploadFile(
                record.remoteDirectory,
                uploadFile,
                record.fileName,
                downloadPreferences.cloudSyncOverwriteUploads().get(),
            ) { progress ->
                if (record.isChapter && owner.isActive) task.download.uploadProgress = progress
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure = error
        } finally {
            withContext(NonCancellable) {
                uploadLock.withLock {
                    activeCloudUploadJobs.remove(record.key)
                    val currentTarget = cloudSyncTargetOrNull()
                    if (attempted && owner.isActive && cloudUploadTasks[record.key] === task && !task.record.paused &&
                        (failure == null || networkAllowed()) &&
                        target == currentTarget && canUpload(task, currentTarget?.id)
                    ) {
                        try {
                            finishCloudUpload(task, file, uploaded, failure, now)
                        } catch (error: Exception) {
                            task.updateRecord(task.record.copy(paused = true, failed = true))
                            reportCloudUploadError(task.download, error)
                        }
                    } else if (task.record.isChapter && cloudUploadTasks[record.key] === task && !task.record.paused) {
                        task.updateRecord(task.record)
                    }
                    publishCloudUploadQueue()
                    startCloudUploadsLocked()
                }
            }
        }
    }

    private suspend fun finishCloudUpload(
        task: CloudUploadTask,
        file: UniFile?,
        uploaded: Boolean,
        failure: Exception?,
        now: () -> Long,
    ) {
        val record = task.record
        val download = task.download
        if (failure != null) {
            if ((record.background || failure is IOException) && failure.isRetryableCloudUploadFailure()) {
                val attempts = (record.retryAttempts + 1).coerceAtMost(8)
                val backoff = (10L shl (attempts - 1)).coerceAtMost(15 * 60)
                val requested = (failure as? CloudStorageHttpException)?.retryAfterSeconds ?: 0
                val waiting = record.copy(
                    retryAttempts = attempts,
                    retryAfterMillis = now() + maxOf(backoff, requested.coerceIn(0, 24 * 60 * 60)) * 1000,
                )
                store.saveUpload(waiting)
                task.updateRecord(waiting)
                logcat(LogPriority.WARN, failure) { "Cloud upload will retry for ${download.manga.title}" }
                return
            }
            reportCloudUploadError(download, failure)
            if (record.isChapter) {
                setCloudUploadPaused(task, paused = true, failed = true)
                return
            }
        } else if (record.isChapter) {
            downloadPreferences.recordCloudChapterUpload(
                record.mangaId,
                record.chapterId,
                record.targetId,
                CloudChapterUpload(
                    record.remoteDirectory,
                    record.fileName,
                    file?.length()?.takeIf { uploaded && it > 0 },
                ),
            )
            downloadPreferences.markChaptersUploadedToCloud(listOf(record.chapterId), record.targetId)
            downloadPreferences.markRemoteSyncMangaFolder(record.mangaId, record.targetId, record.remoteDirectory)
        } else if (uploaded) {
            downloadPreferences.markMetaInfoUploadedToCloud(
                record.mangaId,
                checkNotNull(record.contentHash),
                record.targetId,
            )
        }
        store.removeUpload(record.key)
        cloudUploadTasks.remove(record.key)
        if (!record.isChapter || record.temporaryArchive || (uploaded && record.deleteAfterUpload)) {
            try {
                if (file?.delete() == true && record.deleteAfterUpload) {
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
        val outputNames = provider.chapterOutputNames(chapters, downloadPreferences.saveChaptersAsCBZ.get())
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
                    file = outputNames.getValue(chapter.id),
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
        return queueState.value.none { it.status.value <= Download.State.UPLOADING.value && canTransfer(it) }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update { queue ->
            // Callers filter off the queue's thread, so a chapter may have been queued in the meantime
            val queuedChapterIds = queue.mapTo(HashSet()) { it.chapter.id }
            val toAdd = downloads.filter { it.chapter.id !in queuedChapterIds }
            toAdd.forEach { download ->
                if (download.status != Download.State.ERROR) {
                    download.status = Download.State.QUEUE
                }
            }
            store.addAll(toAdd)
            queue + toAdd
        }
    }

    private suspend fun completeDownload(download: Download, owner: Job) = lock.withLock {
        coroutineContext.ensureActive()
        if (queueState.value.none { it === download }) return@withLock
        if (!canTransfer(download)) {
            download.status = Download.State.QUEUE
            return@withLock
        }
        download.status = Download.State.DOWNLOADED
        store.remove(download)
        _queueState.update { it - download }
        stopIfFinishedLocked(owner)
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
    suspend fun updateQueue(retryChapterId: Long? = null, transform: (List<Download>) -> List<Download>) = editQueue {
        // One update, since chapters are queued without the lock
        _queueState.update { queue ->
            val downloads = transform(queue).distinctBy { it.chapter.id }
            val kept = downloads.mapTo(HashSet()) { it.chapter.id }
            queue.forEach { download ->
                if (download.chapter.id !in kept &&
                    (
                        download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE ||
                            download.status == Download.State.PAUSED
                        )
                ) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            downloads.forEach {
                if (it.chapter.id == retryChapterId && it.status == Download.State.ERROR && canTransfer(it)) {
                    retryDownload(it)
                } else if (it.status != Download.State.ERROR) {
                    it.status = if (canTransfer(it)) Download.State.QUEUE else Download.State.PAUSED
                }
            }
            store.replaceAll(downloads)
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

private data class CloudStorageCandidate(
    val chapter: Chapter,
    val file: UniFile,
    val remoteName: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

private data class CloudReleaseLookup(
    val candidate: CloudStorageCandidate,
    val name: String,
    val uploadedSize: Long?,
)

private data class CloudReleaseResponse<T>(val value: T)

private fun isCloudArchiveName(name: String): Boolean =
    name.endsWith(".cbz", ignoreCase = true) && '/' !in name && '\\' !in name

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
    IllegalStateException("Switch back to the original cloud storage and destination to resume this upload")

private fun Exception.isRetryableCloudUploadFailure(): Boolean = when (this) {
    is CloudStorageHttpException -> status == 408 || status == 429 || status in 500..599
    is FileNotFoundException, is ProtocolException, is SSLPeerUnverifiedException -> false
    is IOException -> true
    else -> false
}

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
