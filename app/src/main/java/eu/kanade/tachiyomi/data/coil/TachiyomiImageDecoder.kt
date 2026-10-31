@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.image.ImageFilter
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import eu.kanade.tachiyomi.util.image.EnhancementImageDecoder as ImageDecoder

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 * It also handles on-the-fly image enhancement via Waifu2x models.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    override suspend fun decode(): DecodeResult? {
        decodeCachedEnhancedImage()?.let { cachedBitmap ->
            return DecodeResult(
                image = prepareForDisplay(cachedBitmap).asImage(),
                isSampled = false,
            )
        }

        return resources.source().use { source ->
            var bitmap: Bitmap? = null
            try {
                var sampleSize = 1
                // Enhancement must see the source resolution. Sampling to the reader's
                // display size first permanently discards detail before the model runs.
                val decodeAtSourceResolution = options.enhanced &&
                    options.mangaId != -1L &&
                    options.chapterId != -1L &&
                    options.pageIndex != -1 &&
                    runCatching { Injekt.get<ReaderPreferences>().realCuganEnabled().get() }
                        .getOrDefault(false)

                // 1. Attempt decoding with native ImageDecoder (for AVIF/JXL/HEIF)
                bitmap = decodeSemaphore.withPermit {
                    val nativeDecoder = try {
                        // Keep the original unread so a failed native decode can use BitmapFactory.
                        ImageDecoder.open(source.peek().inputStream())
                    } catch (e: Exception) {
                        null
                    }

                    if (nativeDecoder == null || nativeDecoder.width <= 0 || nativeDecoder.height <= 0) {
                        nativeDecoder?.recycle()
                        return@withPermit null
                    }

                    try {
                        val srcWidth = nativeDecoder.width
                        val srcHeight = nativeDecoder.height
                        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
                        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

                        sampleSize = if (decodeAtSourceResolution) {
                            1
                        } else {
                            DecodeUtils.calculateInSampleSize(
                                srcWidth = srcWidth,
                                srcHeight = srcHeight,
                                dstWidth = dstWidth,
                                dstHeight = dstHeight,
                                scale = options.scale,
                            )
                        }
                        nativeDecoder.decode(sampleSize = sampleSize)
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) {
                            "TachiyomiImageDecoder: Native decode failed, trying BitmapFactory"
                        }
                        null
                    } finally {
                        nativeDecoder.recycle()
                    }
                }

                // 2. Fallback to BitmapFactory for system-supported formats (JPG, PNG, WEBP, etc.)
                if (bitmap == null) {
                    try {
                        val byteBuf = source.peek().readByteArray()
                        val ops = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(byteBuf, 0, byteBuf.size, ops)

                        if (ops.outWidth > 0 && ops.outHeight > 0) {
                            val srcWidth = ops.outWidth
                            val srcHeight = ops.outHeight
                            val dstWidth = options.size.widthPx(options.scale) { srcWidth }
                            val dstHeight = options.size.heightPx(options.scale) { srcHeight }

                            sampleSize = if (decodeAtSourceResolution) {
                                1
                            } else {
                                DecodeUtils.calculateInSampleSize(
                                    srcWidth = srcWidth,
                                    srcHeight = srcHeight,
                                    dstWidth = dstWidth,
                                    dstHeight = dstHeight,
                                    scale = options.scale,
                                )
                            }

                            val decodeOps = BitmapFactory.Options().apply {
                                inSampleSize = sampleSize
                                inPreferredConfig = if (options.bitmapConfig == Bitmap.Config.HARDWARE) {
                                    Bitmap.Config.ARGB_8888 // Decode to software first
                                } else {
                                    options.bitmapConfig
                                }
                            }
                            bitmap = BitmapFactory.decodeByteArray(byteBuf, 0, byteBuf.size, decodeOps)
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: BitmapFactory fallback failed" }
                    }
                }

                if (bitmap == null) {
                    logcat(LogPriority.ERROR) { "TachiyomiImageDecoder: Failed to decode bitmap via all methods" }
                    return@use null
                }

                logcat(LogPriority.DEBUG) {
                    "TachiyomiImageDecoder: decoded ${bitmap.width}x${bitmap.height}, " +
                        "sampleSize=$sampleSize, sourceResolution=$decodeAtSourceResolution"
                }

                // --- Enhancement Integration ---
                if (options.enhanced) {
                    val preferences = Injekt.get<ReaderPreferences>()
                    if (preferences.realCuganEnabled().get()) {
                        val mangaId = options.mangaId
                        val chapterId = options.chapterId
                        val pageIndex = options.pageIndex
                        val pageVariant = options.pageVariant

                        logcat(LogPriority.DEBUG) {
                            "TachiyomiImageDecoder: Page $pageIndex/$pageVariant enhanced=true, manga=$mangaId, chapter=$chapterId"
                        }

                        if (mangaId != -1L && chapterId != -1L && pageIndex != -1) {
                            val context = Injekt.get<android.app.Application>()
                            ImageEnhancementCache.init(context)

                            val configHash = ImageEnhancementCache.getConfigHash(preferences)
                            logcat(LogPriority.DEBUG) {
                                "TachiyomiImageDecoder: Page $pageIndex/$pageVariant configHash=$configHash"
                            }

                            // Check cache first
                            var usedCache = false
                            val cachedFile = ImageEnhancementCache.getCachedImage(
                                mangaId,
                                chapterId,
                                pageIndex,
                                configHash,
                                pageVariant,
                            )
                            if (cachedFile != null) {
                                logcat(LogPriority.DEBUG) {
                                    "TachiyomiImageDecoder: Page $pageIndex/$pageVariant found in cache: ${cachedFile.absolutePath}"
                                }
                                try {
                                    val cachedBitmap = ImageEnhancementCache.decodeCachedImage(cachedFile)
                                    if (cachedBitmap != null && ImageEnhancementCache.isDisplayable(cachedBitmap)) {
                                        bitmap.recycle()
                                        bitmap = cachedBitmap
                                        usedCache = true
                                    } else {
                                        cachedBitmap?.recycle()
                                        ImageEnhancementCache.removeCachedImage(
                                            mangaId,
                                            chapterId,
                                            pageIndex,
                                            configHash,
                                            pageVariant,
                                        )
                                        logcat(LogPriority.WARN) {
                                            "TachiyomiImageDecoder: Removed invalid enhanced cache for page $pageIndex/$pageVariant"
                                        }
                                    }
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) {
                                        "TachiyomiImageDecoder: Failed to decode cached enhanced image"
                                    }
                                }
                            }

                            if (!usedCache) {
                                logcat(LogPriority.DEBUG) {
                                    "TachiyomiImageDecoder: Page $pageIndex/$pageVariant NOT in cache or decode failed, processing..."
                                }
                                // Not in cache or decode failed, perform enhancement on-the-fly
                                try {
                                    val model = preferences.realCuganModel().get()
                                    val realEsrganStyle = preferences.realEsrganStyle().get()
                                    val noise = preferences.realCuganNoiseLevel().get()
                                    val scale = preferences.realCuganScale().get()

                                    // --- Resolution Limits / Prescale ---
                                    val processMaxWidth = preferences.realCuganMaxSizeWidth().get()
                                    val processMaxHeight = preferences.realCuganMaxSizeHeight().get()
                                    val skipMaxWidth = preferences.realCuganSkipMaxSizeWidth().get()
                                    val skipMaxHeight = preferences.realCuganSkipMaxSizeHeight().get()
                                    var shouldSkipEnhancement = false

                                    val exceedsSkipLimit =
                                        (skipMaxWidth > 0 && bitmap.width > skipMaxWidth) ||
                                            (skipMaxHeight > 0 && bitmap.height > skipMaxHeight)

                                    if (exceedsSkipLimit) {
                                        logcat(LogPriority.DEBUG) {
                                            "TachiyomiImageDecoder: Skipping enhancement for page $pageIndex - source ${bitmap.width}x${bitmap.height} exceeds max resolution ${skipMaxWidth}x$skipMaxHeight"
                                        }
                                        ImageEnhancementCache.saveSkippedToCache(
                                            mangaId,
                                            chapterId,
                                            pageIndex,
                                            configHash,
                                            pageVariant,
                                        )
                                        shouldSkipEnhancement = true
                                    }

                                    // --- Performance Mode ---
                                    val perfMode = preferences.realCuganPerformanceMode().get()
                                    val tileSleepMs = when (perfMode) {
                                        1 -> 5
                                        2 -> 15
                                        else -> 0
                                    }
                                    val tileSize = preferences.realCuganTileSize().get().coerceAtLeast(32)
                                    val precision =
                                        preferences.realCuganPrecision().get().takeIf { it == 0 || it == 1 || it == 3 }
                                            ?: 0
                                    val fp16Arithmetic = preferences.realCuganFp16Arithmetic().get()

                                    // Validate scale based on model capabilities
                                    val effectiveScale = ImageEnhancementCache.getEffectiveScale(
                                        model,
                                        scale,
                                        realEsrganStyle,
                                    )
                                    val processingBackend = Waifu2x.resolveProcessingBackend(
                                        preferences.realCuganProcessingBackend().get(),
                                        model,
                                        effectiveScale,
                                    )
                                    if (processingBackend != Waifu2x.PROCESSING_BACKEND_VULKAN &&
                                        !Waifu2x.isQualcommNpuModelSupported(model, effectiveScale)
                                    ) {
                                        shouldSkipEnhancement = true
                                    }
                                    if (effectiveScale != scale) {
                                        logcat(LogPriority.DEBUG) {
                                            "TachiyomiImageDecoder: Model $model only supports ${effectiveScale}x, clamping from ${scale}x"
                                        }
                                    }

                                    var enhancementInput = bitmap
                                    try {
                                        if (!shouldSkipEnhancement) {
                                            val hasProcessMaxResolution = processMaxWidth > 0 || processMaxHeight > 0
                                            val widthRatio = if (processMaxWidth > 0) {
                                                processMaxWidth / bitmap.width.toFloat()
                                            } else {
                                                Float.POSITIVE_INFINITY
                                            }
                                            val heightRatio = if (processMaxHeight > 0) {
                                                processMaxHeight / bitmap.height.toFloat()
                                            } else {
                                                Float.POSITIVE_INFINITY
                                            }
                                            val maxResolutionRatio = if (hasProcessMaxResolution) {
                                                min(widthRatio, heightRatio)
                                            } else {
                                                1f
                                            }
                                            val ratio = maxResolutionRatio

                                            if (ratio in 0f..<1f) {
                                                val newWidth = max(1, (bitmap.width * ratio).roundToInt())
                                                val newHeight = max(1, (bitmap.height * ratio).roundToInt())
                                                logcat(LogPriority.DEBUG) {
                                                    "TachiyomiImageDecoder: Prescaling page $pageIndex input with native scaling ${bitmap.width}x${bitmap.height} -> ${newWidth}x$newHeight, processingMax=${processMaxWidth}x$processMaxHeight, outputScale=${effectiveScale}x"
                                                }
                                                val scaledBitmap = nativeScaleBitmap(bitmap, newWidth, newHeight)
                                                if (scaledBitmap != bitmap) {
                                                    enhancementInput = scaledBitmap
                                                }
                                            }
                                        }
                                        // --- End Resolution Limits / Prescale ---

                                        if (shouldSkipEnhancement) {
                                            markEnhancementSkipped()
                                        } else {
                                            currentCoroutineContext().ensureActive()
                                            val initialized = when (model) {
                                                0 -> Waifu2x.initRealCugan(context, noise, effectiveScale, isPro = false, tileSleepMs = tileSleepMs, tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic, processingBackend = processingBackend)
                                                1 -> Waifu2x.initRealCugan(context, noise, effectiveScale, isPro = true, tileSleepMs = tileSleepMs, tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic, processingBackend = processingBackend)
                                                Waifu2x.MODEL_REAL_ESRGAN_ANIME -> Waifu2x.initRealESRGAN(
                                                    context,
                                                    effectiveScale,
                                                    style = realEsrganStyle,
                                                    tileSleepMs = tileSleepMs,
                                                    tileSize = tileSize,
                                                    precision = precision,
                                                    fp16Arithmetic = fp16Arithmetic,
                                                    processingBackend = processingBackend,
                                                )
                                                3 -> Waifu2x.initNose(
                                                    context,
                                                    tileSleepMs = tileSleepMs,
                                                    tileSize = tileSize,
                                                    precision = precision,
                                                    fp16Arithmetic = fp16Arithmetic,
                                                )
                                                4 -> Waifu2x.initWaifu2x(
                                                    context,
                                                    noise,
                                                    effectiveScale,
                                                    tileSleepMs = tileSleepMs,
                                                    tileSize = tileSize,
                                                    precision = precision,
                                                    fp16Arithmetic = fp16Arithmetic,
                                                )
                                                5 -> Waifu2x.initWaifu2xUpconv7(
                                                    context,
                                                    noise,
                                                    effectiveScale,
                                                    tileSleepMs = tileSleepMs,
                                                    tileSize = tileSize,
                                                    precision = precision,
                                                    fp16Arithmetic = fp16Arithmetic,
                                                )
                                                else -> if (Waifu2x.isW2xExModel(model)) {
                                                    Waifu2x.initW2xEx(
                                                        context,
                                                        model,
                                                        scale = effectiveScale,
                                                        tileSleepMs = tileSleepMs,
                                                        tileSize = tileSize,
                                                        precision = precision,
                                                        fp16Arithmetic = fp16Arithmetic,
                                                        processingBackend = processingBackend,
                                                    )
                                                } else {
                                                    Waifu2x.initRealCugan(
                                                        context,
                                                        noise,
                                                        effectiveScale,
                                                        tileSleepMs = tileSleepMs,
                                                        tileSize = tileSize,
                                                        precision = precision,
                                                        fp16Arithmetic = fp16Arithmetic,
                                                        processingBackend = processingBackend,
                                                    )
                                                }
                                            }
                                            val processed = if (initialized) {
                                                when (model) {
                                                    0, 1 -> Waifu2x.processRealCugan(enhancementInput, pageIndex)
                                                    Waifu2x.MODEL_REAL_ESRGAN_ANIME -> Waifu2x.processRealESRGAN(
                                                        enhancementInput,
                                                        pageIndex,
                                                    )
                                                    3 -> Waifu2x.processNose(enhancementInput, pageIndex)
                                                    4, 5 -> Waifu2x.processWaifu2x(enhancementInput, pageIndex)
                                                    else -> if (Waifu2x.isW2xExModel(model)) {
                                                        Waifu2x.processW2xEx(enhancementInput, pageIndex)
                                                    } else {
                                                        Waifu2x.processRealCugan(enhancementInput, pageIndex)
                                                    }
                                                }
                                            } else {
                                                null
                                            }

                                            if (processed != null) {
                                                var result: Bitmap = processed
                                                var ownsResult = true
                                                try {
                                                    currentCoroutineContext().ensureActive()
                                                    result =
                                                        ImageFilter.applyInkFilterIfEnabled(processed, Injekt.get())
                                                    if (result !== processed && !processed.isRecycled) {
                                                        processed.recycle()
                                                    }

                                                    // --- Output Resolution Limit (prevent Canvas errors) ---
                                                    val textureLimit = eu.kanade.tachiyomi.util.system.GLUtil.DEVICE_TEXTURE_LIMIT
                                                    logcat(LogPriority.DEBUG) {
                                                        "TachiyomiImageDecoder: Page $pageIndex enhanced result: ${result.width}x${result.height}, DEVICE_TEXTURE_LIMIT=$textureLimit"
                                                    }

                                                    if (result.width > textureLimit || result.height > textureLimit) {
                                                        val widthRatio = textureLimit.toFloat() / result.width
                                                        val heightRatio = textureLimit.toFloat() / result.height
                                                        val ratio = Math.min(widthRatio, heightRatio)

                                                        val newWidth = (result.width * ratio).toInt().coerceAtLeast(1)
                                                        val newHeight = (result.height * ratio).toInt().coerceAtLeast(1)

                                                        logcat(LogPriority.DEBUG) {
                                                            "TachiyomiImageDecoder: Output downscale page $pageIndex: ${result.width}x${result.height} -> ${newWidth}x$newHeight (Texture Limit: $textureLimit)"
                                                        }
                                                        val downscaled = nativeScaleBitmap(result, newWidth, newHeight)
                                                        if (downscaled != result) {
                                                            result.recycle()
                                                            result = downscaled
                                                        }
                                                    }
                                                    // --- End Output Resolution Limit ---

                                                    if (ImageEnhancementCache.isDisplayable(result)) {
                                                        // enqueueSaveToCache owns the bitmap once invoked, even if suspended or rejected.
                                                        ownsResult = false
                                                        val queued = ImageEnhancementCache.enqueueSaveToCache(
                                                            mangaId,
                                                            chapterId,
                                                            pageIndex,
                                                            configHash,
                                                            result,
                                                            pageVariant,
                                                        )
                                                        if (queued) {
                                                            logcat(LogPriority.DEBUG) {
                                                                "TachiyomiImageDecoder: Page $pageIndex/$pageVariant queued for cache encoding"
                                                            }
                                                        } else {
                                                            logcat(LogPriority.WARN) {
                                                                "TachiyomiImageDecoder: Page $pageIndex/$pageVariant cache encoding already pending or rejected"
                                                            }
                                                        }
                                                    } else {
                                                        logcat(LogPriority.ERROR) {
                                                            "TachiyomiImageDecoder: Page $pageIndex/$pageVariant produced a nearly transparent result, keeping original image"
                                                        }
                                                    }
                                                } finally {
                                                    if (ownsResult && result !== bitmap &&
                                                        !result.isRecycled
                                                    ) {
                                                        result.recycle()
                                                    }
                                                }
                                            } else {
                                                // A cancelled native call also returns null. Only remember a real
                                                // initialization/inference failure, so polling cannot retry it forever.
                                                currentCoroutineContext().ensureActive()
                                                markEnhancementSkipped()
                                                logcat(LogPriority.WARN) {
                                                    "TachiyomiImageDecoder: Enhancement unavailable for page $pageIndex/$pageVariant; keeping original"
                                                }
                                            }
                                        } // end else (shouldSkipEnhancement)
                                    } finally {
                                        if (enhancementInput !== bitmap &&
                                            !enhancementInput.isRecycled
                                        ) {
                                            enhancementInput.recycle()
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    markEnhancementSkipped()
                                    logcat(LogPriority.ERROR, e) {
                                        "TachiyomiImageDecoder: Failed to enhance image on-the-fly"
                                    }
                                }
                            }
                        }
                    }
                }
                // --- End Enhancement Integration ---

                bitmap = prepareForDisplay(bitmap)

                DecodeResult(
                    image = bitmap.asImage(),
                    isSampled = sampleSize > 1,
                ).also { bitmap = null } // Ownership passes to Coil.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Critical failure during decode" }
                null
            } catch (e: OutOfMemoryError) {
                markEnhancementSkipped()
                logcat(LogPriority.ERROR, e) {
                    "TachiyomiImageDecoder: Not enough memory; keeping original reader image"
                }
                null
            } finally {
                bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    private fun markEnhancementSkipped() {
        if (!options.enhanced || options.mangaId == -1L || options.chapterId == -1L || options.pageIndex < 0) return
        val preferences = Injekt.get<ReaderPreferences>()
        ImageEnhancementCache.saveSkippedToCache(
            options.mangaId,
            options.chapterId,
            options.pageIndex,
            ImageEnhancementCache.getConfigHash(preferences),
            options.pageVariant,
        )
    }

    private fun decodeCachedEnhancedImage(): Bitmap? {
        if (!options.enhanced) return null

        val mangaId = options.mangaId
        val chapterId = options.chapterId
        val pageIndex = options.pageIndex
        if (mangaId == -1L || chapterId == -1L || pageIndex == -1) return null

        val preferences = Injekt.get<ReaderPreferences>()
        if (!preferences.realCuganEnabled().get()) return null

        val context = Injekt.get<android.app.Application>()
        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(preferences)
        val cachedFile = ImageEnhancementCache.getCachedImage(
            mangaId,
            chapterId,
            pageIndex,
            configHash,
            options.pageVariant,
        ) ?: return null

        val cachedBitmap = ImageEnhancementCache.decodeCachedImage(cachedFile)
        if (cachedBitmap != null && ImageEnhancementCache.isDisplayable(cachedBitmap)) {
            logcat(LogPriority.DEBUG) {
                "TachiyomiImageDecoder: Page $pageIndex/${options.pageVariant} served from enhanced cache before source decode"
            }
            return cachedBitmap
        }

        cachedBitmap?.recycle()
        ImageEnhancementCache.removeCachedImage(
            mangaId,
            chapterId,
            pageIndex,
            configHash,
            options.pageVariant,
        )
        return null
    }

    private fun prepareForDisplay(source: Bitmap): Bitmap {
        if (options.bitmapConfig != Bitmap.Config.HARDWARE || !ImageUtil.canUseHardwareBitmap(source)) {
            return source
        }

        val hardwareBitmap = source.copy(Bitmap.Config.HARDWARE, false) ?: return source
        source.recycle()
        return hardwareBitmap
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (!options.newDecoder && !options.webtoonStrips && (options.customDecoder || options.enhanced)) {
                TachiyomiImageDecoder(result.source, options)
            } else {
                null
            }
        }

        override fun equals(other: Any?) = other is Factory
        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        private val decodeSemaphore = Semaphore(1)
    }
}

private fun nativeScaleBitmap(
    source: Bitmap,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap {
    if (source.width == targetWidth && source.height == targetHeight) return source
    return Waifu2x.scaleBitmapNative(
        source,
        max(1, targetWidth),
        max(1, targetHeight),
    ) ?: Bitmap.createScaledBitmap(
        source,
        max(1, targetWidth),
        max(1, targetHeight),
        true,
    )
}
