package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/**
 * Loader used to load chapters from an online source.
 */
internal class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache,
    private val appScope: CoroutineScope,
    private val canLoadAhead: () -> Boolean,
    private val preferences: ReaderPreferences = Injekt.get(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PageLoader() {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val scheduleSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var readerPages: List<ReaderPage> = emptyList()
    private val downloadJobs = ConcurrentHashMap<Int, Job>()
    private val downloadJobsLock = Any()
    private val pageLoadLocks = ConcurrentHashMap<Int, Mutex>()

    @Volatile
    private var selectedPageIndex = 0

    override var isLocal: Boolean = false

    init {
        scope.launch {
            for (signal in scheduleSignal) scheduleLoads()
        }
    }

    /**
     * Returns the page list for a chapter. It tries to return the page list from the local cache,
     * otherwise fallbacks to network.
     */
    override suspend fun getPages(): List<ReaderPage> {
        val pages = try {
            chapterCache.getPageListFromCache(chapter.chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            source.getPageList(chapter.chapter.toSChapter())
        }
        return pages.mapIndexed { index, page ->
            // Don't trust sources and use our own indexing
            ReaderPage(index, page.url, page.imageUrl).also {
                // Loading starts below, before ChapterLoader gets to set this, and chapter is a lateinit
                it.chapter = chapter
            }
        }
            .also {
                readerPages = it
                selectedPageIndex = chapter.requestedPage.coerceIn(0, it.lastIndex.coerceAtLeast(0))
                scheduleSignal.trySend(Unit)
            }
    }

    override fun onPageSelected(page: ReaderPage) {
        val selected = readerPages.getOrNull(page.index) ?: return
        selectedPageIndex = selected.index
        scope.launch { loadPage(selected) }
    }

    /**
     * Loads a page through the queue. Handles re-enqueueing pages if they were evicted from the cache.
     */
    override suspend fun loadPage(page: ReaderPage) = withContext(ioDispatcher) {
        val target = readerPages.getOrNull(page.index) ?: return@withContext
        // Offscreen holders may prepare their own images without changing which page has priority.
        if (target.index !in preloadWindow()) return@withContext
        val imageUrl = target.imageUrl
        if (target.status == Page.State.Ready && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
            target.status = Page.State.Queue
        }
        if (target.index == selectedPageIndex && target.status is Page.State.Error) {
            target.status = Page.State.Queue
        }
        scheduleSignal.trySend(Unit)
    }

    private fun preloadWindow(): IntRange {
        val ahead = if (canLoadAhead()) PAGES_AHEAD else LIMITED_PAGES_AHEAD
        return (selectedPageIndex - PAGES_BEHIND)..(selectedPageIndex + ahead)
    }

    private fun scheduleLoads() = synchronized(downloadJobsLock) {
        val selected = readerPages.getOrNull(selectedPageIndex) ?: return@synchronized
        val window = preloadWindow()
        // Keep nearby downloads alive across normal page turns. Only distant jumps prune work.
        downloadJobs.filterKeys { it !in window }.values.forEach { it.cancel() }
        launchLoadOnce(selected)

        // The visible page has its own slot. Slow downloads never prevent its neighbours from
        // starting, and each completed preload immediately makes room for the next queued page.
        val plan = buildList {
            readerPages.getOrNull(selected.index + 1)?.let(::add)
            readerPages.getOrNull(selected.index - 1)?.let(::add)
            readerPages.getOrNull(selected.index + 2)?.let(::add)
            readerPages.getOrNull(selected.index - 2)?.let(::add)
            for (index in (selected.index + 3)..window.last) readerPages.getOrNull(index)?.let(::add)
        }
        for (page in plan) {
            val activePreloads = downloadJobs.count { (index, job) -> index != selected.index && job.isActive }
            if (activePreloads >= CONCURRENT_PRELOADS) break
            if (downloadJobs[page.index]?.isActive == true) continue
            launchLoadOnce(page)
        }
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        launchLoadOnce(page, force = true)
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
        downloadJobs.clear()
        scheduleSignal.close()

        // Cache current page list progress for online chapters to allow a faster reopen
        chapter.pages?.let { pages ->
            appScope.launch(Dispatchers.IO) {
                try {
                    // Convert to pages without reader information
                    val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
                    chapterCache.putPageListToCache(chapter.chapter, pagesToSave)
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Starts loading [page] unless it's already loaded or a load is already in flight for it. Claiming the slot and
     * starting the job has to be atomic, otherwise concurrent requests can both launch the same page.
     */
    private fun launchLoadOnce(page: ReaderPage, force: Boolean = false): Job? = synchronized(downloadJobsLock) {
        val pageIndex = page.index
        // Reuse the existing slot while a download is still running.
        val previous = downloadJobs[pageIndex]
        if (!force && previous != null && !previous.isCompleted && !previous.isCancelled) {
            return@synchronized previous
        }
        if (!force && (page.status == Page.State.Ready || page.status is Page.State.Error)) return@synchronized null
        if (force) previous?.cancel()

        // Cancellation does not wait for a source/response writer to unwind. Do not let a replacement
        // edit the same cache entry, or publish Ready, until the old attempt has reset its state.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            pageLoadLocks.getOrPut(pageIndex) { Mutex() }.withLock {
                internalLoadPage(page, force)
            }
        }
        downloadJobs[pageIndex] = job
        job.invokeOnCompletion {
            synchronized(downloadJobsLock) { downloadJobs.remove(pageIndex, job) }
            scheduleSignal.trySend(Unit)
        }
        job.start()
        job
    }

    /**
     * Loads the page, retrieving the image URL and downloading the image if necessary.
     * Downloaded images are stored in the chapter cache.
     *
     * @param page the page whose source image has to be downloaded.
     */
    private suspend fun internalLoadPage(page: ReaderPage, force: Boolean = false) {
        try {
            if (page.imageUrl.isNullOrEmpty()) {
                page.status = Page.State.LoadPage
                page.imageUrl = source.getImageUrl(page)
            }
            val imageUrl = page.imageUrl!!

            if (force || !chapterCache.isImageInCache(imageUrl)) {
                val live = page.downloadStream
                page.progress = 0
                page.status = Page.State.DownloadImage
                try {
                    val imageResponse = source.getImage(page)
                    chapterCache.putImageToCache(imageUrl, imageResponse, live)
                    live?.finish()
                } catch (e: Throwable) {
                    live?.finish(e)
                    throw e
                }
            }

            page.downloadStream = null
            // Start with original stream
            val streamSource = { chapterCache.getImageFile(imageUrl).inputStream() }
            // Keep a raw source available when pager wide-page splitting needs to enhance each
            // half independently, even if a full-page cache was created earlier.
            page.enhancementStream = streamSource

            // Download readiness describes the original, not model preparation.
            // Always publish a stable original stream before optional enhancement
            // work can wait on caches, runtime initialization, or the queue.
            page.stream = streamSource
            page.progress = 100
            page.status = Page.State.Ready

            if (preferences.realCuganEnabled().get() && !preferences.automaticSplitsPage().get()) {
                scope.launch {
                    try {
                        val context = Injekt.get<android.app.Application>()
                        ImageEnhancer.enhance(context, page, false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "HttpPageLoader: Unable to queue optional enhancement" }
                    }
                }
            }
        } catch (e: Throwable) {
            page.downloadStream = null
            if (e is CancellationException) {
                // A distant page jump or retry can cancel this attempt. Put it back in the queue
                // so returning to the page can load it again.
                page.status = Page.State.Queue
                throw e
            }
            page.status = Page.State.Error(e)
        }
    }
}

private const val LIMITED_PAGES_AHEAD = 4
private const val PAGES_AHEAD = 8
private const val PAGES_BEHIND = 2
private const val CONCURRENT_PRELOADS = 2
