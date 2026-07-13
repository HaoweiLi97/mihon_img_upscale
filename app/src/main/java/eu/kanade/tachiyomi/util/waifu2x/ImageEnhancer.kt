package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.data.coil.enhanced
import eu.kanade.tachiyomi.data.coil.mangaId
import eu.kanade.tachiyomi.data.coil.chapterId
import eu.kanade.tachiyomi.data.coil.pageIndex
import eu.kanade.tachiyomi.data.coil.pageVariant
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat
import eu.kanade.tachiyomi.data.coil.customDecoder
import logcat.LogPriority
import java.util.concurrent.PriorityBlockingQueue
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import coil3.request.CachePolicy

object ImageEnhancer {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingRequests = ConcurrentHashMap<String, Int>()
    
    // Priority Queue order:
    // 1. Current visible primary page
    // 2. Current visible secondary page in double-page mode
    // 3. Other promoted/high-priority requests
    // 4. Normal preload requests
    // Then Distance from Target ASC, Seq ASC
    private val queue = PriorityBlockingQueue<EnhanceRequest>()
    private val seqGenerator = AtomicInteger(0)
    private val generation = AtomicInteger(0)

    @Volatile
    private var lastResetTime = 0L

    @Volatile
    private var isFirstRequestAfterReset = false

    @Volatile
    private var initialTargetEnqueued = false

    @Volatile
    private var activeMangaId = -1L

    @Volatile
    private var activeChapterId = -1L

    @Volatile
    private var activePageIndex = -1

    @Volatile
    private var activePageVariant = ""

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var nativeResetJob: Job? = null

    // Current page the user is viewing. Used to prioritize requests closest to this page.
    @Volatile
    var targetPageIndex: Int = 0

    @Volatile
    private var targetPageVariant: String = ""

    @Volatile
    private var targetSecondaryPageIndex: Int = -1

    @Volatile
    private var targetSecondaryPageVariant: String = ""

    data class EnhanceRequest(
        val context: Context,
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val pageVariant: String,
        val data: Any,
        val priority: Int, // 1 = promoted/high priority, 0 = preload
        val generation: Int,
        val seq: Int = 0
    ) : Comparable<EnhanceRequest> {
        private fun effectivePriority(): Int {
            return when {
                pageIndex == targetPageIndex && pageVariant == targetPageVariant -> 3
                pageIndex == targetSecondaryPageIndex && pageVariant == targetSecondaryPageVariant -> 2
                priority > 0 -> 1
                else -> 0
            }
        }

        override fun compareTo(other: EnhanceRequest): Int {
            // 1. Effective priority based on current visible spread and promotion state.
            val p = other.effectivePriority().compareTo(effectivePriority()) // Descending
            if (p != 0) return p
            
            // 2. Distance from Target Page (Closer > Farther)
            // Even if multiple pages are "High Priority", the one closest to user focus wins.
            val currentTarget = targetPageIndex
            val dist1 = kotlin.math.abs(pageIndex - currentTarget)
            val dist2 = kotlin.math.abs(other.pageIndex - currentTarget)
            
            val d = dist1.compareTo(dist2) // Ascending (0 distance is best)
            if (d != 0) return d

            // 3. Fallback: FIFO (Older seq first)
            return seq.compareTo(other.seq)
        }
    }

    init {
        // Worker Loop
        scope.launch {
            while (true) {
                try {
                    if (isFirstRequestAfterReset) {
                        val elapsed = System.currentTimeMillis() - lastResetTime
                        if (elapsed < 700) {
                            kotlinx.coroutines.delay(700 - elapsed)
                        }
                        isFirstRequestAfterReset = false
                    }

                    val req = runInterruptible { queue.take() }
                    if (req.generation == generation.get()) {
                        processRequest(req)
                    } else {
                        pendingRequests.remove(req.key, req.generation)
                    }
                } catch (e: Exception) {
                    if (e !is InterruptedException && e !is CancellationException) {
                        logcat(LogPriority.ERROR, e) { "ImageEnhancer: Worker loop error" }
                    }
                }
            }
        }

    }

    fun enhance(context: Context, page: ReaderPage, highPriority: Boolean = false) {
        val mangaId = page.chapter.chapter.manga_id ?: -1L
        val chapterId = page.chapter.chapter.id ?: -1L
        
        if (mangaId == -1L || chapterId == -1L) return

        // Prioritize stream over imageUrl. For online manga, imageUrl can be a placeholder
        // (e.g., https://127.0.0.1/...) while the actual image data is in the stream.
        val data: Any = page.enhancementStream?.let { streamFn ->
             try {
                 okio.Buffer().readFrom(streamFn())
             } catch (e: Exception) {
                 null
             }
        } ?: page.stream?.let { streamFn ->
             try {
                 okio.Buffer().readFrom(streamFn())
             } catch (e: Exception) {
                 null
             }
        } ?: page.imageUrl ?: return

        enhance(context, mangaId, chapterId, page.index, data, highPriority, page.enhancementKeySuffix)
    }

    fun enhance(context: Context, mangaId: Long, chapterId: Long, pageIndex: Int, data: Any, highPriority: Boolean, pageVariant: String = "") {
        val isInitialTargetRequest = !initialTargetEnqueued && pageIndex == targetPageIndex
        if (!highPriority && !initialTargetEnqueued && !isInitialTargetRequest) {
            logcat(LogPriority.DEBUG) {
                "ImageEnhancer: Deferring page $pageIndex/$pageVariant until initial target $targetPageIndex starts"
            }
            return
        }

        val effectiveHighPriority = highPriority || isInitialTargetRequest
        val requestKey = requestKey(mangaId, chapterId, pageIndex, pageVariant)
        val requestGeneration = generation.get()
        
        val existingGeneration = pendingRequests[requestKey]
        if (existingGeneration != null) {
            if (effectiveHighPriority) {
                 // Upgrade priority: Remove existing (likely Low) and re-add as High
                 val removed = queue.removeIf { 
                     it.mangaId == mangaId &&
                         it.chapterId == chapterId &&
                         it.pageIndex == pageIndex &&
                         it.pageVariant == pageVariant &&
                         it.generation == existingGeneration
                 }
                 if (removed) {
                     logcat(LogPriority.DEBUG) { "ImageEnhancer: Upgrading page $pageIndex/$pageVariant to High Priority" }
                     pendingRequests.remove(requestKey, existingGeneration)
                     // Proceed to add below
                 } else {
                     // Already processing or failed to remove, skip
                     return
                 }
            } else {
                // Already pending and we are Low priority, so skip
                return
            }
        }

        if (pendingRequests.putIfAbsent(requestKey, requestGeneration) != null) return

        if (isInitialTargetRequest) {
            initialTargetEnqueued = true
        }

        val priorityLevel = if (effectiveHighPriority) 1 else 0
        val req = EnhanceRequest(context, mangaId, chapterId, pageIndex, pageVariant, data, priorityLevel, requestGeneration, seqGenerator.getAndIncrement())
        queue.offer(req)
        
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Enqueued page $pageIndex/$pageVariant (priority=$priorityLevel)" }
    }

    fun reset(initialPageIndex: Int = 0) {
        cancelAll(reason = "reset")
        queue.clear()
        pendingRequests.clear()
        targetPageIndex = initialPageIndex
        targetPageVariant = ""
        targetSecondaryPageIndex = -1
        targetSecondaryPageVariant = ""
        seqGenerator.set(0)
        lastResetTime = System.currentTimeMillis()
        isFirstRequestAfterReset = true
        initialTargetEnqueued = false
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Resetting state to page $initialPageIndex" }
    }

    fun cancelAll(reason: String = "cancelAll", resetNative: Boolean = true) {
        generation.incrementAndGet()
        queue.clear()
        pendingRequests.clear()
        activeJob?.cancel(CancellationException("Image enhancement cancelled: $reason"))
        activeJob = null
        activeMangaId = -1L
        activeChapterId = -1L
        activePageIndex = -1
        activePageVariant = ""
        initialTargetEnqueued = false
        if (resetNative) {
            try {
                Waifu2x.abortProcessing()
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "ImageEnhancer: Failed to signal native abort" }
            }
            if (nativeResetJob?.isActive != true) {
                nativeResetJob = scope.launch {
                    try {
                        Waifu2x.resetRealCugan()
                    } catch (t: Throwable) {
                        logcat(LogPriority.ERROR, t) { "ImageEnhancer: Failed to reset native upscaler" }
                    }
                }
            }
        }
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Cancelled all enhancement work (reason=$reason)" }
    }

    fun reprioritizeAround(
        pageIndex: Int,
        pageVariant: String = "",
        secondaryPageIndex: Int? = null,
        secondaryPageVariant: String = "",
    ) {
        targetPageIndex = pageIndex
        targetPageVariant = pageVariant
        targetSecondaryPageIndex = secondaryPageIndex ?: -1
        targetSecondaryPageVariant = if (secondaryPageIndex != null) secondaryPageVariant else ""
        val snapshot = mutableListOf<EnhanceRequest>()
        queue.drainTo(snapshot)
        if (snapshot.isNotEmpty()) {
            queue.addAll(snapshot)
            logcat(LogPriority.DEBUG) {
                "ImageEnhancer: Reprioritized ${snapshot.size} queued pages around target=$pageIndex/$pageVariant secondary=${targetSecondaryPageIndex}/${targetSecondaryPageVariant}"
            }
        }
    }


    fun hasRequest(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean {
        return pendingRequests.containsKey(requestKey(mangaId, chapterId, pageIndex, pageVariant))
    }

    fun isFocusedTarget(pageIndex: Int, pageVariant: String = ""): Boolean {
        return (pageIndex == targetPageIndex && pageVariant == targetPageVariant) ||
            (pageIndex == targetSecondaryPageIndex && pageVariant == targetSecondaryPageVariant)
    }

    fun isActivelyProcessing(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean {
        return activeMangaId == mangaId &&
            activeChapterId == chapterId &&
            activePageIndex == pageIndex &&
            activePageVariant == pageVariant
    }

    fun cancel(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = "") {
        val requestKey = requestKey(mangaId, chapterId, pageIndex, pageVariant)
        if (pendingRequests.remove(requestKey) != null) {
             val removed = queue.removeIf { 
                 it.mangaId == mangaId && it.chapterId == chapterId && it.pageIndex == pageIndex && it.pageVariant == pageVariant
             }
             if (removed) {
                 logcat(LogPriority.DEBUG) { "ImageEnhancer: Cancelled page $pageIndex/$pageVariant" }
             }
        }
    }

    fun cancelRequestsLessThan(context: Context, mangaId: Long, chapterId: Long, thresholdPageIndex: Int) {
        queue.removeIf { req ->
            if (req.mangaId == mangaId && req.chapterId == chapterId && req.pageIndex < thresholdPageIndex) {
                pendingRequests.remove(req.key, req.generation)
                logcat(LogPriority.DEBUG) { "ImageEnhancer: Pruned page ${req.pageIndex}/${req.pageVariant} (reason: < $thresholdPageIndex)" }
                true
            } else {
                false
            }
        }
    }

    fun cancelRequestsGreaterThan(context: Context, mangaId: Long, chapterId: Long, thresholdPageIndex: Int) {
        queue.removeIf { req ->
            if (req.mangaId == mangaId && req.chapterId == chapterId && req.pageIndex > thresholdPageIndex) {
                pendingRequests.remove(req.key, req.generation)
                logcat(LogPriority.DEBUG) { "ImageEnhancer: Pruned page ${req.pageIndex}/${req.pageVariant} (reason: > $thresholdPageIndex)" }
                true
            } else {
                false
            }
        }
    }

    private suspend fun processRequest(req: EnhanceRequest) {
        try {
            if (req.generation != generation.get()) return
            activeMangaId = req.mangaId
            activeChapterId = req.chapterId
            activePageIndex = req.pageIndex
            activePageVariant = req.pageVariant
            logcat(LogPriority.DEBUG) { "ImageEnhancer: Processing page ${req.pageIndex}/${req.pageVariant} (priority=${req.priority})" }
            val request = ImageRequest.Builder(req.context)
                .data(req.data)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .customDecoder(true)
                .enhanced(true)
                .mangaId(req.mangaId)
                .chapterId(req.chapterId)
                .pageIndex(req.pageIndex)
                .pageVariant(req.pageVariant)
                .build()
            
            val disposable = SingletonImageLoader.get(req.context).enqueue(request)
            activeJob = disposable.job
            disposable.job.await()
        } finally {
            activeMangaId = -1L
            activeChapterId = -1L
            activePageIndex = -1
            activePageVariant = ""
            activeJob = null
            pendingRequests.remove(req.key, req.generation)
        }
    }

    private val EnhanceRequest.key: String
        get() = requestKey(mangaId, chapterId, pageIndex, pageVariant)

    private fun requestKey(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String): String {
        return "${mangaId}_${chapterId}_${pageIndex}_${pageVariant}"
    }
}
