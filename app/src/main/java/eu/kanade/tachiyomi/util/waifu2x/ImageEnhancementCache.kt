package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Manages disk cache for Real-CUGAN enhanced images to reduce memory usage.
 */
object ImageEnhancementCache {
    private const val CACHE_DIR_NAME = "realcugan_cache"
    private const val MAX_CACHE_SIZE = 3L * 1024 * 1024 * 1024 // 3GB
    private var cacheDir: File? = null
    private var lastTrimTime = 0L

    fun init(context: Context) {
        if (cacheDir == null) {
            cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }
        }
    }

    /**
     * Get the cache directory for a specific manga and chapter
     */
    private fun getChapterDir(mangaId: Long, chapterId: Long): File {
        val mangaDir = File(cacheDir, mangaId.toString())
        if (!mangaDir.exists()) mangaDir.mkdirs()
        val chapterDir = File(mangaDir, chapterId.toString())
        if (!chapterDir.exists()) chapterDir.mkdirs()
        return chapterDir
    }

    /**
     * Get cached file if it exists
     */
    fun getCachedImage(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String): File? {
        val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash))
        return if (file.exists()) file else null
    }

    /**
     * Save bitmap to disk cache
     */
    fun saveToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, bitmap: Bitmap): File? {
        val currentCacheDir = cacheDir ?: return null
        
        try {
            val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash))
            
            FileOutputStream(file).use { out ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                }
            }
            return file
        } catch (t: Throwable) {
            android.util.Log.e("ImageEnhancementCache", "Failed to save to cache for page $pageIndex", t)
            return null
        }
    }

    /**
     * Clear cache files that are far from the current reading page for a specific chapter
     */
    fun clearOldCache(mangaId: Long, chapterId: Long, currentPage: Int, keepRange: Int = 5) {
        getChapterDir(mangaId, chapterId).listFiles()?.forEach { file ->
            try {
                // filename format: pageIndex_configHash.webp
                val name = file.name
                val parts = name.split("_")
                if (parts.isNotEmpty()) {
                    val pageIndex = parts[0].toIntOrNull()
                    if (pageIndex != null) {
                        // Delete if page is too far behind or ahead
                        if (kotlin.math.abs(pageIndex - currentPage) > keepRange) {
                            file.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }
    
    /**
     * Delete all cache files
     */
    fun clear(context: Context) {
        init(context)
        cacheDir?.deleteRecursively()
        cacheDir?.mkdirs()
    }

    private fun getFilename(pageIndex: Int, configHash: String): String {
        return "${pageIndex}_${configHash}.webp"
    }

    /**
     * Generate a unique hash string based on current settings
     */
    fun getConfigHash(noise: Int, scale: Int, inputScale: Int): String {
        return "${noise}x${scale}x${inputScale}"
    }
    
    /**
     * Clear all cache files for a specific chapter
     */
    fun clearChapterCache(mangaId: Long, chapterId: Long) {
        try {
            val chapterDir = getChapterDir(mangaId, chapterId)
            if (chapterDir.exists()) {
                chapterDir.deleteRecursively()
                android.util.Log.d("ImageEnhancementCache", "Cleared cache for manga $mangaId, chapter $chapterId")
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to clear chapter cache", e)
        }
    }

    /**
     * Check cache size and trim if it exceeds limit (3GB)
     * Should be called from background thread
     */
    fun checkAndTrim(context: Context) {
        // Debounce: only check once every 10 minutes
        if (System.currentTimeMillis() - lastTrimTime < 10 * 60 * 1000) return
        lastTrimTime = System.currentTimeMillis()

        init(context)
        val dir = cacheDir ?: return
        
        try {
            var size = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            if (size > MAX_CACHE_SIZE) {
                android.util.Log.d("ImageEnhancementCache", "Cache size ${size / 1024 / 1024}MB > 3GB, trimming...")
                
                // Get all files sorted by last modified (oldest first)
                val files = dir.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.lastModified() }
                    .iterator()
                
                while (files.hasNext() && size > MAX_CACHE_SIZE * 0.9) { // Trim to 90%
                    val file = files.next()
                    val len = file.length()
                    if (file.delete()) {
                        size -= len
                    }
                }
                android.util.Log.d("ImageEnhancementCache", "Trim complete, new size: ${size / 1024 / 1024}MB")
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to trim cache", e)
        }
    }
}
