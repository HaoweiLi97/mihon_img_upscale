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
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.decoder.ImageDecoder
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import eu.kanade.tachiyomi.util.image.ImageFilter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 * It also handles on-the-fly image enhancement via Waifu2x models.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    override suspend fun decode(): DecodeResult? {
        return resources.source().use { source ->
            decodeSemaphore.withPermit {
                try {
                    var bitmap: Bitmap? = null
                    var sampleSize = 1

                    // 1. Attempt decoding with native ImageDecoder (for AVIF/JXL/HEIF)
                    val nativeDecoder = try {
                        ImageDecoder.newInstance(source.inputStream(), options.cropBorders, displayProfile)
                    } catch (e: Exception) {
                        null
                    }

                    if (nativeDecoder != null && nativeDecoder.width > 0 && nativeDecoder.height > 0) {
                        try {
                            val srcWidth = nativeDecoder.width
                            val srcHeight = nativeDecoder.height
                            val dstWidth = options.size.widthPx(options.scale) { srcWidth }
                            val dstHeight = options.size.heightPx(options.scale) { srcHeight }

                            sampleSize = DecodeUtils.calculateInSampleSize(
                                srcWidth = srcWidth,
                                srcHeight = srcHeight,
                                dstWidth = dstWidth,
                                dstHeight = dstHeight,
                                scale = options.scale,
                            )
                            bitmap = nativeDecoder.decode(sampleSize = sampleSize)
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

                                sampleSize = DecodeUtils.calculateInSampleSize(
                                    srcWidth = srcWidth,
                                    srcHeight = srcHeight,
                                    dstWidth = dstWidth,
                                    dstHeight = dstHeight,
                                    scale = options.scale,
                                )

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
                        return@withPermit null
                    }

                    // --- Enhancement Integration ---
                    if (options.enhanced) {
                        val preferences = Injekt.get<ReaderPreferences>()
                        if (preferences.realCuganEnabled().get()) {
                            val mangaId = options.mangaId
                            val chapterId = options.chapterId
                            val pageIndex = options.pageIndex

                            logcat(LogPriority.DEBUG) { "TachiyomiImageDecoder: Page $pageIndex enhanced=true, manga=$mangaId, chapter=$chapterId" }

                            if (mangaId != -1L && chapterId != -1L && pageIndex != -1) {
                                val context = Injekt.get<android.app.Application>()
                                ImageEnhancementCache.init(context)

                                val configHash = ImageEnhancementCache.getConfigHash(
                                    preferences.realCuganNoiseLevel().get(),
                                    preferences.realCuganScale().get(),
                                    preferences.realCuganInputScale().get(),
                                    preferences.realCuganModel().get(),
                                    preferences.realCuganMaxSizeWidth().get(),
                                    preferences.realCuganMaxSizeHeight().get()
                                )
                                logcat(LogPriority.DEBUG) { "TachiyomiImageDecoder: Page $pageIndex configHash=$configHash" }

                                // Check cache first
                                val cachedFile = ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash)
                                if (cachedFile != null) {
                                    logcat(LogPriority.DEBUG) { "TachiyomiImageDecoder: Page $pageIndex found in cache: ${cachedFile.absolutePath}" }
                                    try {
                                        val cachedBitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                                        if (cachedBitmap != null) {
                                            bitmap.recycle()
                                            bitmap = cachedBitmap
                                        }
                                    } catch (e: Exception) {
                                        logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Failed to decode cached enhanced image" }
                                    }
                                } else {
                                    logcat(LogPriority.DEBUG) { "TachiyomiImageDecoder: Page $pageIndex NOT in cache, processing..." }
                                    // Not in cache, perform enhancement on-the-fly
                                    try {
                                        val model = preferences.realCuganModel().get()
                                        val noise = preferences.realCuganNoiseLevel().get()
                                        var scale = preferences.realCuganScale().get()

                                        // --- Smart Downscaling ---
                                        val maxWidth = preferences.realCuganMaxSizeWidth().get()
                                        val maxHeight = preferences.realCuganMaxSizeHeight().get()
                                        val shouldResize = preferences.realCuganResizeLargeImage().get()

                                        if ((maxWidth > 0 || maxHeight > 0) && shouldResize) {
                                            // FIX: User clarified that MaxSize is for INPUT resolution, not output.
                                            // So we do NOT divide by scale. We map input > MaxSize down to MaxSize.
                                            val targetWidth = if (maxWidth > 0) maxWidth else Int.MAX_VALUE
                                            val targetHeight = if (maxHeight > 0) maxHeight else Int.MAX_VALUE

                                            if (bitmap.width > targetWidth || bitmap.height > targetHeight) {
                                                // Calculate scaling ratio to fit within bounds while maintaining aspect ratio
                                                val widthRatio = targetWidth.toFloat() / bitmap.width
                                                val heightRatio = targetHeight.toFloat() / bitmap.height
                                                val ratio = Math.min(widthRatio, heightRatio)
                                                
                                                val newWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                                                val newHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)

                                                logcat(LogPriority.DEBUG) { "TachiyomiImageDecoder: Smart Downscaling page $pageIndex: ${bitmap.width}x${bitmap.height} -> ${newWidth}x${newHeight} (Input Limit: ${maxWidth}x${maxHeight})" }
                                                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                                                if (scaledBitmap != bitmap) {
                                                    bitmap.recycle()
                                                    bitmap = scaledBitmap
                                                }
                                            }
                                        }
                                        // --- End Smart Downscaling ---
                                        
                                        // --- Performance Mode ---
                                        val perfMode = preferences.realCuganPerformanceMode().get()
                                        val tileSleepMs = when (perfMode) {
                                            1, 2 -> 15
                                            else -> 0
                                        }
                                        val tileSize = when (perfMode) {
                                            1 -> 96
                                            2 -> 64
                                            else -> 128
                                        }

                                        val initialized = when (model) {
                                            0 -> Waifu2x.initRealCugan(context, noise, scale, isPro = false, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                            1 -> Waifu2x.initRealCugan(context, noise, scale, isPro = true, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                            2 -> Waifu2x.initRealESRGAN(context, scale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                            3 -> Waifu2x.initNose(context, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                            4 -> Waifu2x.initWaifu2x(context, noise, scale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                            5 -> Waifu2x.initWaifu2xUpconv7(context, noise, scale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                            else -> Waifu2x.initRealCugan(context, noise, scale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                        }
                                        
                                        if (initialized) {
                                            val processed = when (model) {
                                                0, 1 -> Waifu2x.processRealCugan(bitmap, pageIndex)
                                                2 -> Waifu2x.processRealESRGAN(bitmap, pageIndex)
                                                3 -> Waifu2x.processNose(bitmap, pageIndex)
                                                4, 5 -> Waifu2x.processWaifu2x(bitmap, pageIndex)
                                                else -> Waifu2x.processRealCugan(bitmap, pageIndex)
                                            }
                                            
                                            if (processed != null) {
                                                val filtered = ImageFilter.applyInkFilterIfEnabled(processed, Injekt.get())
                                                val savedFile = ImageEnhancementCache.saveToCache(mangaId, chapterId, pageIndex, configHash, filtered)
                                                if (savedFile != null) {
                                                    logcat(LogPriority.DEBUG) { "TachiyomiImageDecoder: Page $pageIndex saved to cache: ${savedFile.absolutePath}" }
                                                } else {
                                                    logcat(LogPriority.ERROR) { "TachiyomiImageDecoder: Page $pageIndex FAILED to save to cache" }
                                                }
                                                if (bitmap != filtered) bitmap.recycle()
                                                bitmap = filtered
                                            }
                                        }
                                    } catch (e: Exception) {
                                        logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Failed to enhance image on-the-fly" }
                                    }
                                }
                            }
                        }
                    }
                    // --- End Enhancement Integration ---

                    if (options.bitmapConfig == Bitmap.Config.HARDWARE && ImageUtil.canUseHardwareBitmap(bitmap)) {
                        val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
                        if (hwBitmap != null) {
                            bitmap.recycle()
                            bitmap = hwBitmap
                        }
                    }

                    DecodeResult(
                        image = bitmap.asImage(),
                        isSampled = sampleSize > 1,
                    )
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Critical failure during decode" }
                    null
                }
            }
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.customDecoder || isApplicable(result.source)) {
                TachiyomiImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: ImageSource): Boolean {
            val type = try {
                source.source().peek().inputStream().use { ImageUtil.findImageType(it) }
            } catch (e: Exception) {
                null
            }
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL, ImageUtil.ImageType.HEIF -> true
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory
        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        var displayProfile: ByteArray? = null
        private val decodeSemaphore = Semaphore(1)
    }
}
