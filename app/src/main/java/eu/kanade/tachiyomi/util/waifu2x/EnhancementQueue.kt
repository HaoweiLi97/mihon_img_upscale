package eu.kanade.tachiyomi.util.waifu2x

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.image.ImageFilter

/**
 * Manages Real-CUGAN image enhancement processing with priority-based scheduling.
 * If processing falls behind the reading progress, it jumps to the current page
 * and continues processing forward from there.
 */
object EnhancementQueue {
    private const val TAG = "EnhancementQueue"
    
    // Current reading page index (updated when user navigates)
    private val _currentReadingPage = AtomicInteger(0)
    val currentReadingPage: Int
        get() = _currentReadingPage.get()
    
    // Track which pages are being processed or already processed
    private val processingPages = ConcurrentHashMap<Int, Job>()
    private val completedPages = ConcurrentHashMap<Int, Boolean>()
    
    // Processing scope
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val PRELOAD_AHEAD: Int
        get() = Injekt.get<ReaderPreferences>().realCuganPreloadSize().get()

    private data class Task(
        val pageIndex: Int,
        val bitmap: Bitmap,
        val model: Int,
        val noise: Int,
        val scale: Int,
        val mangaId: Long,
        val chapterId: Long,
        val configHash: String,
        val deferred: CompletableDeferred<Bitmap?>
    )

    private val tasks = Collections.synchronizedList(ArrayList<Task>())
    // Track tasks by pageIndex for deduplication and status check
    private val activeTasks = ConcurrentHashMap<Int, Task>()
    
    private enum class TaskState { QUEUED, PROCESSING, COMPLETED }
    private val taskStates = ConcurrentHashMap<Int, TaskState>()

    // Worker job
    private var workerJob: Job? = null

    private fun checkWorker() {
        synchronized(this) {
            if (workerJob?.isActive == true) return
            
            workerJob = scope.launch {
                while (isActive) {
                    var taskToRun: Task? = null
                    
                    synchronized(tasks) {
                        if (tasks.isEmpty()) {
                            workerJob = null
                            return@launch
                        }
                        
                        val current = _currentReadingPage.get()
                        
                        // Find the highest priority task strictly
                        // 1. Current page has absolute top priority
                        // 2. Then future pages (ascending)
                        // 3. Then history pages (closest to current first)
                        taskToRun = tasks.minWithOrNull(Comparator { t1, t2 ->
                            val p1 = t1.pageIndex
                            val p2 = t2.pageIndex
                            
                            val d1 = p1 - current
                            val d2 = p2 - current
                            
                            when {
                                // Both are future or current
                                d1 >= 0 && d2 >= 0 -> d1.compareTo(d2)
                                // Both are history
                                d1 < 0 && d2 < 0 -> abs(d1).compareTo(abs(d2))
                                // d1 is future, d2 is history -> future wins
                                d1 >= 0 -> -1
                                // d1 is history, d2 is future -> future wins
                                else -> 1
                            }
                        })
                        
                        if (taskToRun != null) {
                            tasks.remove(taskToRun)
                        }
                    }
                    
                    if (taskToRun != null) {
                        try {
                            processTask(taskToRun!!)
                        } catch (e: Exception) {
                            Log.e(TAG, "Worker failed to process task", e)
                        }
                    } else {
                        delay(50)
                    }
                }
            }
        }
    }

    // Listeners for page changes (used for memory optimization in webtoon mode)
    private val pageChangeListeners = mutableListOf<(Int) -> Unit>()
    
    fun addPageChangeListener(listener: (Int) -> Unit) {
        synchronized(pageChangeListeners) {
            pageChangeListeners.add(listener)
        }
    }
    
    fun removePageChangeListener(listener: (Int) -> Unit) {
        synchronized(pageChangeListeners) {
            pageChangeListeners.remove(listener)
        }
    }
    // ...
    // Call this from UI to queue an image
    suspend fun process(
        pageIndex: Int, 
        bitmap: Bitmap, 
        model: Int, 
        noise: Int, 
        scale: Int,
        mangaId: Long = -1L,
        chapterId: Long = -1L,
        configHash: String = ""
    ): Bitmap? {
        // If already processing or completed, we might want to wait for it
        val existingTask = activeTasks[pageIndex]
        if (existingTask != null) {
            // Discard our copy since one is already in queue. 
            // We assume the caller gave us a copy we own.
            try { bitmap.recycle() } catch (e: Exception) {}
            return existingTask.deferred.await()
        }

        val deferred = CompletableDeferred<Bitmap?>()
        val task = Task(pageIndex, bitmap, model, noise, scale, mangaId, chapterId, configHash, deferred)
        
        activeTasks[pageIndex] = task
        taskStates[pageIndex] = TaskState.QUEUED
        
        synchronized(tasks) { tasks.add(task) }
        
        // Ensure worker is running to pick up this task
        checkWorker()
        
        try {
            return deferred.await()
        } finally {
            // Task remains in activeTasks until processTask finishes it
        }
    }


    private fun processTask(task: Task) {
        taskStates[task.pageIndex] = TaskState.PROCESSING
        
        if (task.bitmap.isRecycled) {
            Log.w(TAG, "Skipping task for page ${task.pageIndex} - bitmap already recycled")
            task.deferred.complete(null)
            activeTasks.remove(task.pageIndex)
            taskStates[task.pageIndex] = TaskState.COMPLETED
            return
        }
        
        try {
            val processed = when (task.model) {
                0 -> Waifu2x.processRealCugan(task.bitmap, task.pageIndex) // SE
                1 -> Waifu2x.processRealCugan(task.bitmap, task.pageIndex) // Pro
                2 -> Waifu2x.processRealESRGAN(task.bitmap, task.pageIndex)
                3 -> Waifu2x.processNose(task.bitmap, task.pageIndex)
                4 -> Waifu2x.processWaifu2x(task.bitmap, task.pageIndex)
                else -> Waifu2x.processRealCugan(task.bitmap, task.pageIndex)
            }
            
            // Apply filters (e.g. Ink Effect) after enhancement
            val filtered = if (processed != null) {
                ImageFilter.applyInkFilterIfEnabled(processed, Injekt.get())
            } else {
                null
            }

            // Immediately notify UI so it can display the image
            task.deferred.complete(filtered)

            // Recycle input bitmap
            try {
                if (!task.bitmap.isRecycled) {
                    task.bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recycle input bitmap for page ${task.pageIndex}", e)
            }
            
            // Launch background cache saving (truly async, doesn't block next task)
            if (filtered != null && task.mangaId != -1L) {
                scope.launch(Dispatchers.IO) {
                    try {
                        if (!filtered.isRecycled) {
                            Log.d(TAG, "Async saving page ${task.pageIndex} to cache...")
                            ImageEnhancementCache.saveToCache(task.mangaId, task.chapterId, task.pageIndex, task.configHash, filtered)
                            if (task.pageIndex % 5 == 0) {
                                ImageEnhancementCache.clearOldCache(task.mangaId, task.chapterId, task.pageIndex)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Async cache save failed for page ${task.pageIndex}", t)
                    }
                }
            }

            // Note: GPU performance mode throttling removed because:
            // 1. Delay-based throttling doesn't actually reduce GPU load during processing
            // 2. It causes poor UX (perceived lag) without saving power
            // If GPU load reduction is needed, adjust tilesize or scale parameters instead
        } catch (t: Throwable) {
            Log.e(TAG, "Task failed for page ${task.pageIndex}", t)
            task.deferred.complete(null)
            // Recycle input bitmap on error
            try {
                if (!task.bitmap.isRecycled) {
                    task.bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recycle input bitmap for page ${task.pageIndex}", e)
            }
        } finally {
            activeTasks.remove(task.pageIndex)
            taskStates[task.pageIndex] = TaskState.COMPLETED
        }
    }
    
    /**
     * Check if a page has been marked as completed
     */
    fun isCompleted(pageIndex: Int): Boolean {
        return taskStates[pageIndex] == TaskState.COMPLETED
    }
    
    /**
     * Check if a page is currently being processed or queued
     */
    fun isProcessing(pageIndex: Int): Boolean {
        val state = taskStates[pageIndex]
        return state == TaskState.PROCESSING || state == TaskState.QUEUED
    }
    
    /**
     * Check if a page is queued for processing
     */
    fun isQueued(pageIndex: Int): Boolean {
        return taskStates[pageIndex] == TaskState.QUEUED
    }
    
    /**
     * Mark a page as queued for processing
     */
    fun markQueued(pageIndex: Int) {
        taskStates[pageIndex] = TaskState.QUEUED
        Log.d(TAG, "Page $pageIndex marked as queued")
    }

    /**
     * Mark a page as cancelled (removed from tracking)
     */
    fun markCancelled(pageIndex: Int) {
        taskStates.remove(pageIndex)
        Log.d(TAG, "Page $pageIndex marked as cancelled")
    }
    
    fun onPageChanged(pageIndex: Int) {
        val oldPage = _currentReadingPage.getAndSet(pageIndex)
        
        Log.d(TAG, "Page changed: $oldPage -> $pageIndex")
        
        if (pageIndex > oldPage) {
            // Cancel processing for pages that are now too far behind
            cancelBehindPages(pageIndex - 2)
        }
        
        // Notify listeners to restore original images for pages far from current position
        // This helps save memory in webtoon mode where enhanced images can be 4x larger
        pageChangeListeners.forEach { it(pageIndex) }
        
        // Check if current page needs processing and prioritize it
        val state = taskStates[pageIndex]
        if (state != TaskState.PROCESSING && state != TaskState.COMPLETED) {
            // Wake up worker to re-evaluate priorities immediately
            checkWorker()
        }
    }
    
    /**
     * Cancel all pages behind the threshold
     */
    private fun cancelBehindPages(threshold: Int) {
        processingPages.entries.removeIf { (page, job) ->
            if (page < threshold && job.isActive) {
                Log.d(TAG, "Cancelling behind page: $page (threshold: $threshold)")
                job.cancel()
                true
            } else {
                false
            }
        }
    }
    
    /**
     * Check if this page should be processed now or skipped
     */
    fun shouldProcess(pageIndex: Int): Boolean {
        val currentPage = _currentReadingPage.get()
        
        // Process if:
        // 1. This is the current page
        // 2. This is within PRELOAD_AHEAD pages of current
        // 3. We do NOT check completedPages here anymore, because if the View was detached
        //    and reattached (recycled), it needs to be processed again.
        //    The View itself handles preventing recursive loops via flags.
        val shouldProcess = pageIndex >= currentPage && 
                           pageIndex <= currentPage + PRELOAD_AHEAD
        
        if (!shouldProcess && pageIndex < currentPage) {
            Log.d(TAG, "Skipping page $pageIndex (current: $currentPage)")
        }
        
        return shouldProcess
    }
    
    /**
     * Mark a page as completed
     */
    fun markCompleted(pageIndex: Int) {
        completedPages[pageIndex] = true
        processingPages.remove(pageIndex)
        Log.d(TAG, "Page $pageIndex completed")
    }

    /**
     * Remove page from tracking (call when View is detached)
     */
    fun removePage(pageIndex: Int) {
        if (completedPages.containsKey(pageIndex)) {
            Log.d(TAG, "Removing page $pageIndex from completed list (View detached)")
            completedPages.remove(pageIndex)
        }
        val job = processingPages.remove(pageIndex)
        job?.cancel()
    }
    
    /**
     * Register a processing job for a page
     */
    fun registerJob(pageIndex: Int, job: Job) {
        processingPages[pageIndex] = job
    }
    
    private var resetJob: Job? = null

    /**
     * Clear all tracking (call when chapter changes)
     */
    fun reset() {
        Log.d(TAG, "Resetting queue")
        processingPages.values.forEach { it.cancel() }
        processingPages.clear()
        completedPages.clear()
        synchronized(tasks) {
            activeTasks.values.forEach { it.deferred.cancel() }
            activeTasks.clear()
            // Recycle bitmaps that were never processed
            tasks.forEach { 
                try { it.bitmap.recycle() } catch (e: Exception) {}
            }
            tasks.clear()
        }
        taskStates.clear()
        _currentReadingPage.set(0)
        
        // Note: We do NOT reset native state here anymore.
        // Waifu2x.initRealCugan() already handles config changes efficiently.
        // Keeping the model loaded allows for instant processing when switching chapters/manga.
        resetJob?.cancel()
    }

    /**
     * Restart processing from current position (call when settings change)
     */
    fun restart(resetModel: Boolean = true) {
        Log.d(TAG, "Restarting queue from page ${_currentReadingPage.get()}")
        processingPages.values.forEach { it.cancel() }
        processingPages.clear()
        completedPages.clear()
        synchronized(tasks) {
            activeTasks.values.forEach { it.deferred.cancel() }
            activeTasks.clear()
            // Recycle bitmaps that were never processed
            tasks.forEach { 
                try { it.bitmap.recycle() } catch (e: Exception) {}
            }
            tasks.clear()
        }
        taskStates.clear()
        // Do NOT reset currentReadingPage so we continue from where we are
        
        // Note: Model reset removed for performance. Waifu2x handles config changes.
    }
    
    /**
     * Get priority for a page (lower = higher priority)
     */
    fun getPriority(pageIndex: Int): Int {
        val currentPage = _currentReadingPage.get()
        return if (pageIndex >= currentPage) {
            pageIndex - currentPage  // Pages ahead get priority based on distance
        } else {
            Int.MAX_VALUE  // Pages behind get lowest priority
        }
    }
    
    /**
     * Clear all cache for a specific manga (called when exiting chapter)
     */
    fun clearCacheForManga(mangaId: Long, chapterId: Long) {
        Log.d(TAG, "Clearing cache for manga $mangaId, chapter $chapterId")
        try {
            val context = Injekt.get<android.app.Application>()
            ImageEnhancementCache.init(context)
            ImageEnhancementCache.clearChapterCache(mangaId, chapterId)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to clear cache for manga $mangaId", t)
        }
    }
}
