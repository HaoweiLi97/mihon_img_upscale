@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okio.Buffer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Manages disk cache for Real-CUGAN enhanced images to reduce memory usage.
 */
object ImageEnhancementCache {
    private const val CACHE_DIR_NAME = "realcugan_cache"

    // Input decoding now preserves source resolution before enhancement.
    private const val ENHANCEMENT_INPUT_DECODE_REVISION = 1
    private const val REAL_CUGAN_TEXTURE_OCCUPANCY_REVISION = 3
    private const val MONOCHROME_INPUT_NORMALIZATION_REVISION = 3
    private const val REAL_ESRGAN_PHOTO_NCNN_REVISION = 2
    private const val MAX_CACHE_SIZE = 3L * 1024 * 1024 * 1024 // 3GB
    private var cacheDir: File? = null

    // A model change clears this directory while completed pages can still be
    // waiting in the background writer. Saving holds a shared lock for its
    // lifetime, so clear() cannot remove a file below an active writer. Cache
    // lookups also use the shared lock and must never wait for WebP compression.
    private val cacheLock = ReentrantReadWriteLock()
    private var lastTrimTime = 0L
    private val cacheGeneration = AtomicInteger(0)
    private val pendingSaveKeys = ConcurrentHashMap<CacheKey, SaveRequest>()
    // Large enhanced bitmaps must not pile up behind the encoder. Hand each one
    // directly to the writer before allowing the next enhancement to advance.
    private val saveQueue = Channel<SaveRequest>(capacity = Channel.RENDEZVOUS, onUndeliveredElement = ::releaseSaveRequest)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class CacheKey(
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val configHash: String,
        val pageVariant: String,
    )

    sealed interface CacheEvent {
        val key: CacheKey?

        data class Saved(override val key: CacheKey) : CacheEvent
        data class Skipped(override val key: CacheKey) : CacheEvent
        data class Removed(override val key: CacheKey) : CacheEvent
        data object Cleared : CacheEvent {
            override val key: CacheKey? = null
        }

        fun affects(
            mangaId: Long,
            chapterId: Long,
            pageIndex: Int,
            configHash: String,
            pageVariant: String,
        ): Boolean {
            val eventKey = key ?: return true
            return eventKey.mangaId == mangaId &&
                eventKey.chapterId == chapterId &&
                eventKey.pageIndex == pageIndex &&
                eventKey.configHash == configHash &&
                eventKey.pageVariant == pageVariant
        }
    }

    // A small replay window closes the gap between an initial cache lookup and Flow
    // subscription without bringing back per-page filesystem polling.
    private val _events = MutableSharedFlow<CacheEvent>(
        replay = 32,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()

    private data class SaveRequest(
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val configHash: String,
        val bitmap: Bitmap,
        val pageVariant: String,
        val generation: Int,
    )

    init {
        saveScope.launch {
            for (request in saveQueue) {
                try {
                    if (isCurrentSave(request)) {
                        val file = writeToCache(request)
                        if (file != null) {
                            _events.tryEmit(CacheEvent.Saved(request.cacheKey))
                            android.util.Log.d(
                                "ImageEnhancementCache",
                                "Saved page ${request.pageIndex}/${request.pageVariant} to ${file.absolutePath}",
                            )
                        } else {
                            android.util.Log.e(
                                "ImageEnhancementCache",
                                "Failed to save page ${request.pageIndex}/${request.pageVariant}",
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImageEnhancementCache", "Cache writer failed for page ${request.pageIndex}", e)
                } finally {
                    releaseSaveRequest(request)
                }
            }
        }
    }

    fun init(context: Context) {
        cacheLock.read {
            cacheDir?.let { directory ->
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                return
            }
        }
        cacheLock.write {
            val directory = cacheDir ?: File(context.cacheDir, CACHE_DIR_NAME).also { cacheDir = it }
            // The system may clear the app cache while this process is alive.
            // Do not rely only on cacheDir being non-null.
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
    }

    /**
     * Get the cache directory for a specific manga and chapter
     */
    private fun getChapterDir(mangaId: Long, chapterId: Long): File {
        return cacheLock.read {
            val root = checkNotNull(cacheDir) { "Image enhancement cache is not initialized" }
            if (!root.exists()) root.mkdirs()
            val mangaDir = File(root, mangaId.toString())
            if (!mangaDir.exists()) mangaDir.mkdirs()
            val chapterDir = File(mangaDir, chapterId.toString())
            if (!chapterDir.exists()) chapterDir.mkdirs()
            chapterDir
        }
    }

    /**
     * Get cached file if it exists
     */
    fun getCachedImage(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String = "",
    ): File? {
        val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant))
        return if (file.isFile) file else null
    }

    /**
     * Read a stable snapshot before clear()/model changes can delete the backing file.
     * A small decode rejects corrupt old entries without allocating a full enhanced bitmap.
     */
    fun readCachedImage(file: File): Buffer? {
        val source = cacheLock.read {
            try {
                if (!file.isFile) return@read null
                file.inputStream().use { Buffer().readFrom(it) }
            } catch (_: Exception) {
                null
            }
        } ?: return null

        val valid = try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 32
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val preview = BitmapFactory.decodeStream(source.peek().inputStream(), null, options)
            try {
                preview != null && isDisplayable(preview)
            } finally {
                preview?.recycle()
            }
        } catch (_: Exception) {
            false
        }
        if (valid) return source

        source.close()
        discardCachedImage(file, source)
        return null
    }

    fun decodeCachedImage(file: File): Bitmap? {
        val source = readCachedImage(file) ?: return null
        val snapshot = source.clone()
        val bitmap = try {
            source.use { BitmapFactory.decodeStream(it.inputStream()) }
        } catch (_: Exception) {
            null
        }
        if (bitmap != null && isDisplayable(bitmap)) return bitmap
        bitmap?.recycle()
        discardCachedImage(file, snapshot)
        return null
    }

    fun discardCachedImage(file: File, expectedSource: Buffer? = null) {
        val removed = cacheLock.write {
            if (!file.isFile) return@write false
            if (expectedSource != null) {
                val current = try {
                    file.inputStream().use { Buffer().readFrom(it) }
                } catch (_: Exception) {
                    return@write false
                }
                if (current != expectedSource) return@write false
            }
            file.delete()
        }
        if (removed) {
            _events.tryEmit(CacheEvent.Cleared)
            android.util.Log.w("ImageEnhancementCache", "Discarded invalid enhanced image: ${file.name}")
        }
    }

    /**
     * Check if a file is already cached (helper for UI checks)
     */
    fun isCached(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String = "",
    ): Boolean {
        return getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant) != null
    }

    /**
     * Remove a cached enhanced image and its temporary file for the same page/config.
     */
    fun removeCachedImage(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String = "",
    ): Boolean {
        return cacheLock.write {
            try {
                pendingSaveKeys.remove(cacheKey(mangaId, chapterId, pageIndex, configHash, pageVariant))
                val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant))
                val tempFile = File(file.parent, "${file.name}.tmp")
                val removedFile = !file.exists() || file.delete()
                val removedTemp = !tempFile.exists() || tempFile.delete()
                (removedFile && removedTemp).also { removed ->
                    if (removed) {
                        _events.tryEmit(
                            CacheEvent.Removed(cacheKey(mangaId, chapterId, pageIndex, configHash, pageVariant)),
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ImageEnhancementCache", "Failed to remove cached image for page $pageIndex", e)
                false
            }
        }
    }

    fun removeSkipMarker(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return try {
            val file =
                File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant) + ".skip")
            (!file.exists() || file.delete()).also { removed ->
                if (removed) {
                    _events.tryEmit(
                        CacheEvent.Removed(cacheKey(mangaId, chapterId, pageIndex, configHash, pageVariant)),
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to remove skip marker for page $pageIndex", e)
            false
        }
    }

    /**
     * Transfers ownership of [bitmap] to the cache pipeline, including when the request is rejected.
     */
    suspend fun enqueueSaveToCache(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        bitmap: Bitmap,
        pageVariant: String = "",
    ): Boolean {
        if (cacheDir == null || !isDisplayable(bitmap)) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return false
        }
        val generation = cacheGeneration.get()

        val request = SaveRequest(
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = pageIndex,
            configHash = configHash,
            bitmap = bitmap,
            pageVariant = pageVariant,
            generation = generation,
        )
        if (pendingSaveKeys.putIfAbsent(request.cacheKey, request) != null) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return false
        }
        // Channel owns cleanup once send starts. Prompt cancellation can throw
        // even after delivery; recycling in a catch would race the cache writer.
        saveQueue.send(request)
        return true
    }

    fun isSavePending(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = "", configHash: String? = null): Boolean {
        return if (configHash != null) {
            pendingSaveKeys.containsKey(cacheKey(mangaId, chapterId, pageIndex, configHash, pageVariant))
        } else {
            pendingSaveKeys.keys.any {
                it.mangaId == mangaId && it.chapterId == chapterId && it.pageIndex == pageIndex &&
                    it.pageVariant == pageVariant
            }
        }
    }

    private fun isCurrentSave(request: SaveRequest): Boolean =
        request.generation == cacheGeneration.get() && pendingSaveKeys[request.cacheKey] === request

    private fun releaseSaveRequest(request: SaveRequest) {
        pendingSaveKeys.remove(request.cacheKey, request)
        if (!request.bitmap.isRecycled) request.bitmap.recycle()
    }

    private fun writeToCache(request: SaveRequest): File? {
        return cacheLock.read {
            // clear() may have run after this request was dequeued.
            if (!isCurrentSave(request) || cacheDir == null) {
                return@read null
            }
            val bitmap = request.bitmap
            if (!isDisplayable(bitmap)) {
                android.util.Log.e(
                    "ImageEnhancementCache",
                    "Refusing to cache nearly transparent enhanced image for page ${request.pageIndex}",
                )
                return@read null
            }

            try {
                val file = File(
                    getChapterDir(request.mangaId, request.chapterId),
                    getFilename(request.pageIndex, request.configHash, request.pageVariant),
                )
                val tempFile = File(file.parent, "${file.name}.tmp")

                val compressed = FileOutputStream(tempFile).use { out ->
                    val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                    }
                    out.flush()
                    success
                }

                if (!compressed || tempFile.length() == 0L || request.generation != cacheGeneration.get()) {
                    tempFile.delete()
                    return@read null
                }

                if (tempFile.renameTo(file)) {
                    file
                } else {
                    tempFile.delete()
                    null
                }
            } catch (t: Throwable) {
                android.util.Log.e("ImageEnhancementCache", "Failed to save to cache for page ${request.pageIndex}", t)
                null
            }
        }
    }

    fun isDisplayable(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        if (!bitmap.hasAlpha()) return true

        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var total = 0
        var visible = 0
        var alphaSum = 0L

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val alpha = bitmap.getPixel(x, y) ushr 24
                if (alpha > 16) visible++
                alphaSum += alpha.toLong()
                total++
                x += stepX
            }
            y += stepY
        }

        if (total == 0) return false
        return visible > total / 20 || alphaSum / total > 32
    }

    /**
     * Mark a page as skipped (too large to process) in the cache
     */
    fun saveSkippedToCache(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String = "",
    ) {
        try {
            val file =
                File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant) + ".skip")
            if (!file.exists()) {
                file.createNewFile()
            }
            _events.tryEmit(CacheEvent.Skipped(cacheKey(mangaId, chapterId, pageIndex, configHash, pageVariant)))
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to save skip marker", e)
        }
    }

    /**
     * Check if a page was marked as skipped in the cache
     */
    fun isSkipped(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String = "",
    ): Boolean {
        return File(
            getChapterDir(mangaId, chapterId),
            getFilename(pageIndex, configHash, pageVariant) + ".skip",
        ).exists()
    }

    /**
     * Clear old cache files including skip markers
     */
    fun clearOldCache(mangaId: Long, chapterId: Long, currentPage: Int, keepRange: Int = 5) {
        cacheLock.write {
            pendingSaveKeys.keys.removeIf {
                it.mangaId == mangaId && it.chapterId == chapterId &&
                    kotlin.math.abs(it.pageIndex - currentPage) > keepRange
            }
            var removedAny = false
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
                                removedAny = file.delete() || removedAny
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
            }
            if (removedAny) {
                _events.tryEmit(CacheEvent.Cleared)
            }
        }
    }

    /**
     * Delete all cache files
     */
    fun clear(context: Context) {
        init(context)
        cacheLock.write {
            cacheGeneration.incrementAndGet()
            pendingSaveKeys.clear()
            cacheDir?.deleteRecursively()
            cacheDir?.mkdirs()
        }
        _events.tryEmit(CacheEvent.Cleared)
    }

    private val SaveRequest.cacheKey: CacheKey
        get() = cacheKey(mangaId, chapterId, pageIndex, configHash, pageVariant)

    private fun cacheKey(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String,
    ) = CacheKey(mangaId, chapterId, pageIndex, configHash, pageVariant)

    private fun getFilename(pageIndex: Int, configHash: String, pageVariant: String = ""): String {
        return buildString {
            append(pageIndex)
            append('_')
            append(configHash)
            if (pageVariant.isNotEmpty()) {
                append('_')
                append(pageVariant)
            }
            append(".webp")
        }
    }

    /**
     * Generate a unique hash string based on current settings
     */
    fun getConfigHash(preferences: ReaderPreferences): String = getConfigHash(
        noise = preferences.realCuganNoiseLevel().get(),
        scale = preferences.realCuganScale().get(),
        model = preferences.realCuganModel().get(),
        realEsrganStyle = preferences.realEsrganStyle().get(),
        maxWidth = preferences.realCuganMaxSizeWidth().get(),
        maxHeight = preferences.realCuganMaxSizeHeight().get(),
        skipMaxWidth = preferences.realCuganSkipMaxSizeWidth().get(),
        skipMaxHeight = preferences.realCuganSkipMaxSizeHeight().get(),
        tileSize = preferences.realCuganTileSize().get(),
        precision = preferences.realCuganPrecision().get(),
        fp16Arithmetic = preferences.realCuganFp16Arithmetic().get(),
        processingBackend = preferences.realCuganProcessingBackend().get(),
        inkFilter = preferences.inkFilter().get(),
        inkBleeding = preferences.inkBleedingIntensity().get(),
        inkBump = preferences.inkBumpIntensity().get(),
        inkOriginal = preferences.inkOriginalIntensity().get(),
    )

    fun getConfigHash(
        noise: Int,
        scale: Int,
        model: Int = 0,
        realEsrganStyle: Int = Waifu2x.REAL_ESRGAN_STYLE_ANIME,
        maxWidth: Int = 0,
        maxHeight: Int = 0,
        skipMaxWidth: Int = 0,
        skipMaxHeight: Int = 0,
        tileSize: Int = 128,
        precision: Int = 0,
        fp16Arithmetic: Boolean = false,
        processingBackend: Int = Waifu2x.PROCESSING_BACKEND_VULKAN,
        inkFilter: Boolean = false,
        inkBleeding: Int = 0,
        inkBump: Int = 0,
        inkOriginal: Int = 0,
    ): String {
        val effectiveScale = getEffectiveScale(model, scale, realEsrganStyle)
        val resolvedBackend = Waifu2x.resolveProcessingBackend(processingBackend, model, effectiveScale)
        val resolvedPrecision = Waifu2x.resolvePrecision(precision, resolvedBackend, model, effectiveScale, realEsrganStyle)
        val modelRevision = buildString {
            if (
                model == Waifu2x.MODEL_REAL_ESRGAN_ANIME &&
                realEsrganStyle == Waifu2x.REAL_ESRGAN_STYLE_PHOTO &&
                resolvedBackend == Waifu2x.PROCESSING_BACKEND_VULKAN
            ) {
                append("_rp$REAL_ESRGAN_PHOTO_NCNN_REVISION")
            }
            if (resolvedBackend == Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU) {
                append("_qv${Waifu2x.QNN_CONTEXT_CACHE_VERSION}_nf1")
            }
            if (resolvedBackend == Waifu2x.PROCESSING_BACKEND_MEDIATEK_NPU) {
                append("_mtv${eu.kanade.tachiyomi.util.mediatek.MediaTekNpu.CACHE_VERSION}")
            }
            if (model == 1 || (model == 0 && noise == 3)) {
                append("_tv$REAL_CUGAN_TEXTURE_OCCUPANCY_REVISION")
            }
        }
        return "${noise}x${effectiveScale}_m${model}_rs${realEsrganStyle}_w${maxWidth}_h${maxHeight}_sw${skipMaxWidth}_sh${skipMaxHeight}_t${tileSize}_p${resolvedPrecision}_fa${if (fp16Arithmetic) 1 else 0}_b${resolvedBackend}${modelRevision}_dv$ENHANCEMENT_INPUT_DECODE_REVISION" +
            "_nv$MONOCHROME_INPUT_NORMALIZATION_REVISION" +
            if (inkFilter) "_ink${inkBleeding}_${inkBump}_$inkOriginal" else "_ink0"
    }

    fun getEffectiveScale(model: Int, scale: Int, realEsrganStyle: Int = Waifu2x.REAL_ESRGAN_STYLE_ANIME): Int {
        Waifu2x.w2xExScaleFor(model)?.let { return it }
        return when (model) {
            3, 4, 5 -> 2
            Waifu2x.MODEL_REAL_ESRGAN_ANIME -> if (realEsrganStyle == Waifu2x.REAL_ESRGAN_STYLE_PHOTO) 2 else scale
            else -> scale
        }
    }

    /**
     * Clear all cache files for a specific chapter
     */
    fun clearChapterCache(mangaId: Long, chapterId: Long) {
        cacheLock.write {
            try {
                pendingSaveKeys.keys.removeIf { it.mangaId == mangaId && it.chapterId == chapterId }
                val chapterDir = getChapterDir(mangaId, chapterId)
                if (chapterDir.exists()) {
                    chapterDir.deleteRecursively()
                    android.util.Log.d("ImageEnhancementCache", "Cleared cache for manga $mangaId, chapter $chapterId")
                }
            } catch (e: Exception) {
                android.util.Log.e("ImageEnhancementCache", "Failed to clear chapter cache", e)
            }
        }
        _events.tryEmit(CacheEvent.Cleared)
    }

    /**
     * Check cache size and trim if it exceeds limit (3GB)
     * Should be called from background thread
     */
    fun checkAndTrim(context: Context) {
        init(context)
        cacheLock.write {
            // Debounce: only check once every 10 minutes
            if (System.currentTimeMillis() - lastTrimTime < 10 * 60 * 1000) return
            lastTrimTime = System.currentTimeMillis()

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
}
