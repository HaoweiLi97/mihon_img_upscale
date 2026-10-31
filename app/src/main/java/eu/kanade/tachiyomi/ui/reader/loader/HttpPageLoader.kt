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
import kotlinx.coroutines.joinAll
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
    private val chunkProcessLock = Any()
    private var chunkProcessJob: Job? = null
    private val downloadJobs = ConcurrentHashMap<Int, Job>()
    private val downloadJobsLock = Any()
    private val pageLoadLocks = ConcurrentHashMap<Int, Mutex>()

    @Volatile
    private var selectedPageIndex = 0

    override var isLocal: Boolean = false

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
                selectedPageIndex = chapter.requestedPage.coerceIn(0, it.lastIndex.coerceAtLeast(0))
                it.loadByRollingChunked(selectedPageIndex, 1, 1)
            }
    }

    override fun onPageSelected(page: ReaderPage) {
        val selected = chapter.pages?.getOrNull(page.index) ?: return
        selectedPageIndex = selected.index
        scope.launch { loadPage(selected) }
    }

    /**
     * Loads a page through the queue. Handles re-enqueueing pages if they were evicted from the cache.
     */
    override suspend fun loadPage(page: ReaderPage) = withContext(ioDispatcher) {
        // ViewPager and Webtoon also create offscreen holders. Their subscriptions must not restart
        // the download plan or cancel the image the user is still waiting to see.
        if (page.index != selectedPageIndex) return@withContext
        val imageUrl = page.imageUrl

        // Check if the image has been deleted
        if (page.status == Page.State.Ready && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
            page.status = Page.State.Queue
        }

        // Automatically retry failed pages when subscribed to this page
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }

        // Start the page being read before anything else. Planning the chunks walks the whole chapter and takes
        // chunkProcessLock, and the chain only reaches this page once it has, so don't make it wait on either.
        launchLoadOnce(page)

        val pages = page.chapter.pages.orEmpty()
        if (page.index !in pages.indices) return@withContext
        // Finish the selected image before allowing adjacent downloads to compete for bandwidth.
        pages.loadByRollingChunked(page.index, 1, 5) { chunks ->
            val continueLoading = chunks.take(2).flatten().map { it.index }.toSet()
            synchronized(downloadJobsLock) {
                downloadJobs.filterKeys { it !in continueLoading }.values.forEach { it.cancel() }
            }
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
     * starting the job has to be atomic, otherwise two chunk chains can both launch the same page.
     */
    private fun launchLoadOnce(page: ReaderPage, force: Boolean = false): Job? = synchronized(downloadJobsLock) {
        val pageIndex = page.index
        // A page another chain is already loading still counts towards this chunk, otherwise the chain moves on
        // and starts the next chunk while it's in flight
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
        }
        job.start()
        job
    }

    private fun List<ReaderPage>.loadByRollingChunked(
        index: Int,
        chunkStartSize: Int,
        chunkEndSize: Int,
        onNewChunks: (List<List<ReaderPage>>) -> Unit = {},
    ) {
        val items = this
        // When loading ahead isn't allowed, only the pages around the one being read load
        val window = if (canLoadAhead()) items.indices else (index - 1)..(index + LIMITED_PAGES_AHEAD)
        val chunks = buildList {
            items.getOrNull(index)?.let(::add)
            items.getOrNull(index - 1)?.let(::add)
            items.getOrNull(index + 1)?.let(::add)
            // `items.lastIndex`, not the list being built, whose lastIndex is at most 2 here
            if (index < items.lastIndex - 1) {
                items.subList(index + 2, items.size).let(::addAll)
            }
            if (index > 1) {
                items.subList(0, index - 1).reversed().let(::addAll)
            }
        }
            .filter { it.index in window }
            .rollingChunked(chunkStartSize, chunkEndSize)

        // A new selection may arrive while an older plan is being built.
        synchronized(chunkProcessLock) {
            if (index != selectedPageIndex) return
            onNewChunks(chunks)
            chunkProcessJob?.cancel()
            chunkProcessJob = scope.launch {
                for (chunk in chunks) {
                    val jobs = chunk.mapNotNull { page -> launchLoadOnce(page) }
                    jobs.joinAll()
                    // A manual retry can replace a job while this chain is awaiting it. Keep the
                    // visible chunk ahead of prefetch until its replacement has finished too.
                    while (true) {
                        val replacements = chunk.mapNotNull { downloadJobs[it.index] }
                        if (replacements.isEmpty()) break
                        replacements.joinAll()
                    }
                }
            }
        }
    }

    /**
     * Splits the list into chunks with rolling sizes between a starting size and an ending size.
     *
     * The chunking process starts with the given `startSize` and increases by 1 until it reaches
     * the `endSize`, at which point it continues with the `endSize` size for the remaining items.
     */
    private fun <T> List<T>.rollingChunked(startSize: Int, endSize: Int): List<List<T>> {
        // A start size of 0 never advances the index
        require(startSize >= 1) { "startSize must be at least 1, was $startSize" }
        val thisSize = this.size
        val result = ArrayList<List<T>>()
        var chunkSize = startSize
        var index = 0
        while (index < thisSize) {
            val localChunkSize = chunkSize.coerceAtMost(thisSize - index)
            result.add(List(localChunkSize) { this[it + index] })
            index += chunkSize
            if (chunkSize < endSize) chunkSize += 1
        }
        return result
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
                // Chunk chains get cancelled on every page turn, so a cancelled page isn't an error. Put it back in
                // the queue, otherwise it's stuck in a state no later pass will pick up.
                page.status = Page.State.Queue
                throw e
            }
            page.status = Page.State.Error(e)
        }
    }
}

private const val LIMITED_PAGES_AHEAD = 4
