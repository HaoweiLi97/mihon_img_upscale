package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.data.coil.enhanced
import eu.kanade.tachiyomi.data.coil.mangaId
import eu.kanade.tachiyomi.data.coil.chapterId
import eu.kanade.tachiyomi.data.coil.pageIndex
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val pendingRequests = ConcurrentHashMap<String, Unit>()
    
    // Priority Queue: Higher priority (1) processed before Lower (0)
    // Order: Priority DESC, Distance from Target ASC, Seq ASC
    private val queue = PriorityBlockingQueue<EnhanceRequest>()
    private val seqGenerator = AtomicInteger(0)

    // Current page the user is viewing. Used to prioritize requests closest to this page.
    @Volatile
    var targetPageIndex: Int = 0

    data class EnhanceRequest(
        val context: Context,
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val data: Any,
        val priority: Int, // 1 = High (Current Page), 0 = Low (Preload)
        val seq: Int = 0
    ) : Comparable<EnhanceRequest> {
        override fun compareTo(other: EnhanceRequest): Int {
            // 1. Priority (High > Low)
            val p = other.priority.compareTo(priority) // Descending
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
                    val req = runInterruptible { queue.take() }
                    processRequest(req)
                } catch (e: Exception) {
                    if (e !is InterruptedException) {
                        logcat(LogPriority.ERROR, e) { "ImageEnhancer: Worker loop error" }
                    }
                }
            }
        }
    }

    fun enhance(context: Context, page: ReaderPage, highPriority: Boolean = true) {
        val mangaId = page.chapter.chapter.manga_id ?: -1L
        val chapterId = page.chapter.chapter.id ?: -1L
        
        if (mangaId == -1L || chapterId == -1L) return

        val data: Any = page.imageUrl ?: page.stream?.let { it ->
             try {
                 okio.Buffer().readFrom(it())
             } catch (e: Exception) {
                 return@let null
             }
        } ?: return

        enhance(context, mangaId, chapterId, page.index, data, highPriority)
    }

    fun enhance(context: Context, mangaId: Long, chapterId: Long, pageIndex: Int, data: Any, highPriority: Boolean) {
        val requestKey = "${mangaId}_${chapterId}_$pageIndex"
        
        if (pendingRequests.containsKey(requestKey)) {
            if (highPriority) {
                 // Upgrade priority: Remove existing (likely Low) and re-add as High
                 val removed = queue.removeIf { 
                     it.mangaId == mangaId && it.chapterId == chapterId && it.pageIndex == pageIndex 
                 }
                 if (removed) {
                     logcat(LogPriority.DEBUG) { "ImageEnhancer: Upgrading page $pageIndex to High Priority" }
                     pendingRequests.remove(requestKey)
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

        if (pendingRequests.putIfAbsent(requestKey, Unit) != null) return

        val priorityLevel = if (highPriority) 1 else 0
        val req = EnhanceRequest(context, mangaId, chapterId, pageIndex, data, priorityLevel, seqGenerator.getAndIncrement())
        queue.offer(req)
        
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Enqueued page $pageIndex (priority=$priorityLevel)" }
    }

    fun reset(initialPageIndex: Int = 0) {
        queue.clear()
        pendingRequests.clear()
        targetPageIndex = initialPageIndex
        seqGenerator.set(0)
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Resetting state to page $initialPageIndex" }
    }

    fun hasRequest(mangaId: Long, chapterId: Long, pageIndex: Int): Boolean {
        return pendingRequests.containsKey("${mangaId}_${chapterId}_$pageIndex")
    }

    fun cancel(mangaId: Long, chapterId: Long, pageIndex: Int) {
        val requestKey = "${mangaId}_${chapterId}_$pageIndex"
        if (pendingRequests.remove(requestKey) != null) {
             val removed = queue.removeIf { 
                 it.mangaId == mangaId && it.chapterId == chapterId && it.pageIndex == pageIndex 
             }
             if (removed) {
                 logcat(LogPriority.DEBUG) { "ImageEnhancer: Cancelled page $pageIndex" }
             }
        }
    }

    fun cancelRequestsLessThan(context: Context, mangaId: Long, chapterId: Long, thresholdPageIndex: Int) {
        queue.removeIf { req ->
            if (req.mangaId == mangaId && req.chapterId == chapterId && req.pageIndex < thresholdPageIndex) {
                pendingRequests.remove("${req.mangaId}_${req.chapterId}_${req.pageIndex}")
                logcat(LogPriority.DEBUG) { "ImageEnhancer: Pruned page ${req.pageIndex} (reason: < $thresholdPageIndex)" }
                true
            } else {
                false
            }
        }
    }

    fun cancelRequestsGreaterThan(context: Context, mangaId: Long, chapterId: Long, thresholdPageIndex: Int) {
        queue.removeIf { req ->
            if (req.mangaId == mangaId && req.chapterId == chapterId && req.pageIndex > thresholdPageIndex) {
                pendingRequests.remove("${req.mangaId}_${req.chapterId}_${req.pageIndex}")
                logcat(LogPriority.DEBUG) { "ImageEnhancer: Pruned page ${req.pageIndex} (reason: > $thresholdPageIndex)" }
                true
            } else {
                false
            }
        }
    }

    private suspend fun processRequest(req: EnhanceRequest) {
        try {
            logcat(LogPriority.DEBUG) { "ImageEnhancer: Processing page ${req.pageIndex} (priority=${req.priority})" }
            val request = ImageRequest.Builder(req.context)
                .data(req.data)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .customDecoder(true)
                .enhanced(true)
                .mangaId(req.mangaId)
                .chapterId(req.chapterId)
                .pageIndex(req.pageIndex)
                .build()
            
            SingletonImageLoader.get(req.context).enqueue(request).job.await()
        } finally {
            pendingRequests.remove("${req.mangaId}_${req.chapterId}_${req.pageIndex}")
        }
    }
}
