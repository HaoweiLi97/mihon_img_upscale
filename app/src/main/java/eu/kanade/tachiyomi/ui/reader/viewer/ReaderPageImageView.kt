package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.view.Gravity
import android.graphics.Color
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import uy.kohesive.injekt.injectLazy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tachiyomi.core.common.util.lang.launchUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

import kotlinx.coroutines.flow.collect

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private var isSettingProcessedImage = false // Flag to prevent recursive processing
    private val alwaysDecodeLongStripWithSSIV by lazy {
        Injekt.get<BasePreferences>().alwaysDecodeLongStripWithSSIV().get()
    }

    private val preferences: ReaderPreferences by injectLazy()

    private val realCuganEnabled: Boolean
        get() = preferences.realCuganEnabled().get()

    private val realCuganNoiseLevel: Int
        get() = preferences.realCuganNoiseLevel().get()

    private val realCuganScale: Int
        get() = preferences.realCuganScale().get()

    private val preloadSize: Int
        get() = if (preferences.realCuganEnabled().get()) preferences.realCuganPreloadSize().get() else 4
    private val realCuganInputScale: Int
        get() = preferences.realCuganInputScale().get()

    private val realCuganModel: Int
        get() = preferences.realCuganModel().get()

    private val realCuganMaxSizeWidth: Int
        get() = preferences.realCuganMaxSizeWidth().get()

    private val realCuganMaxSizeHeight: Int
        get() = preferences.realCuganMaxSizeHeight().get()
        
    private val realCuganResizeLargeImage: Boolean
        get() = preferences.realCuganResizeLargeImage().get()

    private val realCuganShowStatus: Boolean
        get() = preferences.realCuganShowStatus().get()

    // Performance mode: 0=90% (0ms sleep), 1=50% (2ms), 2=30% (5ms)
    private val realCuganPerformanceMode: Int
        get() = preferences.realCuganPerformanceMode().get()
    
    private val tileSleepMs: Int
        get() = when (realCuganPerformanceMode) {
            0 -> 0    // 性能模式 90% - 全速
            1 -> 15   // 平衡模式 50% - 强力降温 (15ms/tile)
            2 -> 15   // 节能模式 30% - 极致发热控制 (15ms/tile)
            else -> 0
        }

    private val tileSize: Int
        get() = when (realCuganPerformanceMode) {
            0 -> 128  // 性能模式 - 大块处理
            1 -> 96   // 平衡模式 - 中等块，增加调度频率
            2 -> 64   // 节能模式 - 小块处理，更频繁的 sleep
            else -> 128
        }

    private var pageView: View? = null

    private var config: Config? = null
    
    private var processingJob: Job? = null
    private var enhancedBitmap: Bitmap? = null

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    private val statusView: TextView by lazy {
        TextView(context).apply {
            layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(20, 0, 0, 20)
            }
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            isVisible = false
            this@ReaderPageImageView.addView(this)
        }
    }

    private val enhancedOverlay: AppCompatImageView by lazy {
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isVisible = false
            this@ReaderPageImageView.addView(this)
        }
    }
    
    init {
        // Listen for performance mode changes to update native throttling immediately
        // Use launchIO to avoid blocking UI thread waiting for native lock if processing is active
        launchIO {
            // Check cache size and trim if needed (debounced to run at most once every 10 mins)
            eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache.checkAndTrim(context)

            preferences.realCuganPerformanceMode().changes()
                .collect { mode ->
                    // Need to recalculate sleep ms based on new mode
                    val sleepMs = when (mode) {
                        0 -> 0 
                        1 -> 15 
                        2 -> 15 
                        else -> 0
                    }
                    val size = when (mode) {
                        0 -> 128
                        1 -> 96
                        2 -> 64
                        else -> 128
                    }
                    eu.kanade.tachiyomi.util.waifu2x.Waifu2x.updatePerformance(sleepMs, size)
                }
        }
    }

    private fun updateStatus(text: String?) {
        if (!realCuganShowStatus || text == null) {
            statusView.isVisible = false
            return
        }
        statusView.text = text
        statusView.isVisible = true
        statusView.bringToFront()
    }

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
        
        // Hide overlay and recycle temporary bitmap once main view is ready with the new image
        if (isSettingProcessedImage) {
            enhancedOverlay.setImageBitmap(null)
            enhancedOverlay.isVisible = false
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
        }
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
        
        // Hide overlay and recycle temporary bitmap if enhanced image load failed
        if (isSettingProcessedImage) {
            enhancedOverlay.setImageBitmap(null)
            enhancedOverlay.isVisible = false
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
        }
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        // Check if this page needs enhancement when selected (user might have skipped it during fast scrolling)
        checkAndTriggerEnhancement()
        
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }
    
    /**
     * Check if this page needs enhancement and trigger it if necessary.
     * This handles the case where the user scrolled past a page before it was processed,
     * and then scrolled back to it.
     * Also handles webtoon mode where enhanced image was restored to original to save memory.
     */
    private fun checkAndTriggerEnhancement() {
        if (!realCuganEnabled || pageIndex < 0 || mangaId == -1L) {
            return
        }
        
        // If the page is marked completed AND we are holding a valid enhanced bitmap,
        // we assume we are correctly showing the enhanced image.
        if (eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.isCompleted(pageIndex) && 
            enhancedBitmap != null && !enhancedBitmap!!.isRecycled) {
            return
        }
        
        // Check if currently processing
        if (eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.isProcessing(pageIndex)) {
            return
        }
        
        android.util.Log.d("ReaderPageImageView", "Page $pageIndex selected, checking if needs enhancement or cache reload...")
        
        // Initialize cache
        eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache.init(context)
        val configHash = eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache.getConfigHash(
            realCuganNoiseLevel, 
            realCuganScale, 
            realCuganInputScale
        ) + "_m${realCuganModel}_w${realCuganMaxSizeWidth}_h${realCuganMaxSizeHeight}"
        
        // Check if enhanced version exists in cache
        val cachedFile = if (pageIndex >= 0 && mangaId != -1L && chapterId != -1L) {
            eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash)
        } else null
        
        
        if (cachedFile != null && config != null) {
            android.util.Log.d("ReaderPageImageView", "Found cached enhanced image for page $pageIndex, loading...")
            
            // Load cached image with overlay transition to prevent flicker
            launchIO {
                try {
                    // Decode the cached file to bitmap for overlay
                    val cachedBitmap = android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath)
                    
                    if (cachedBitmap != null && !cachedBitmap.isRecycled) {
                        withUIContext {
                            // 1. Show overlay immediately to prevent flicker
                            enhancedBitmap = cachedBitmap
                            enhancedOverlay.setImageBitmap(cachedBitmap)
                            enhancedOverlay.isVisible = true
                            enhancedOverlay.bringToFront()
                            statusView.bringToFront()
                            
                            // 2. Update background view
                            android.util.Log.d("ReaderPageImageView", "Switching main view to cached enhanced source for page $pageIndex")
                            isSettingProcessedImage = true
                            (pageView as? SubsamplingScaleImageView)?.setImage(ImageSource.uri(context, android.net.Uri.fromFile(cachedFile)))
                            eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.markCompleted(pageIndex)
                            updateStatus("PROCESSED")
                        }
                    } else {
                        android.util.Log.w("ReaderPageImageView", "Failed to decode cached file for page $pageIndex")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ReaderPageImageView", "Error loading cached image for page $pageIndex", e)
                }
            }
            return
        }
        
        // No cache - need to trigger processing
        // But we don't hold originalBitmap anymore. We must wait for the page to be loaded naturally.
        // If image is already loaded, processImageHelper would have triggered processing.
        // If it was cancelled/recycled, we wait for reload.
        android.util.Log.d("ReaderPageImageView", "Page $pageIndex needs processing, waiting for load/reload")
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        if (
            config != null &&
            config!!.landscapeZoom &&
            config!!.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config!!.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                animateScaleAndCenter(targetScale, point)!!
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun recycle() = pageView?.let {
        processingJob?.cancel()
        processingJob = null
        
        when (it) {
            is SubsamplingScaleImageView -> it.recycle()
            is AppCompatImageView -> it.dispose()
        }
        it.isVisible = false
        enhancedOverlay.setImageBitmap(null)
        enhancedOverlay.isVisible = false
        enhancedBitmap?.recycle()
        enhancedBitmap = null
        isSettingProcessedImage = false
    }

    /**
     * Restore original image to save memory (for webtoon mode)
     */
    fun restoreOriginalImage() {
        // No-op: We let the system handle memory.
    }

    /**
     * Restore enhanced image from cache if available (for webtoon mode when scrolling back)
     */
    fun restoreEnhancedFromCache() {
        checkAndTriggerEnhancement()
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // Not used
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                val bitmapSource = data.bitmap
                processImageHelper(bitmapSource)
            }
            is BufferedSource -> {
                // If enhancement is enabled, we MUST skip this optimization and go through the ImageRequest flow below,
                // because we need the Bitmap to process it.
                if (!realCuganEnabled && (!isWebtoon || alwaysDecodeLongStripWithSSIV)) {
                    setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            processImageHelper(image.bitmap)
                        },
                    )
                    .listener(
                        onError = { _, result ->
                            onImageLoadError(result.throwable)
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    // Current page index for priority processing
    var pageIndex: Int = -1
    var mangaId: Long = -1L
    var chapterId: Long = -1L

    private fun SubsamplingScaleImageView.processImageHelper(bitmap: Bitmap) {
        android.util.Log.d("ReaderPageImageView", "processImageHelper called. realCugan=$realCuganEnabled, pageIndex=$pageIndex")
        
        if (isSettingProcessedImage) {
            android.util.Log.d("ReaderPageImageView", "Skipping processImageHelper - setting processed image")
            isSettingProcessedImage = false
            return
        }
        
        // Check if bitmap is recycled before trying to use it
        if (bitmap.isRecycled) {
            android.util.Log.d("ReaderPageImageView", "Skipping processImageHelper - bitmap is recycled for page $pageIndex")
            return
        }

        if (!realCuganEnabled) {
            // If enhancement is disabled, just show the original image
            setImage(ImageSource.bitmap(bitmap))
            isVisible = true
            updateStatus("RAW")
            android.util.Log.d("ReaderPageImageView", "Real-CUGAN not enabled, skipping enhancement")
            return
        }

        // Initialize cache
        eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache.init(context)
        // Include model type and max size in hash. Note: model 0=SE, 1=Pro
        val configHash = eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache.getConfigHash(realCuganNoiseLevel, realCuganScale, realCuganInputScale) + "_m${realCuganModel}_w${realCuganMaxSizeWidth}_h${realCuganMaxSizeHeight}"

        // Check cache first - BEFORE loading original image
        if (pageIndex >= 0 && mangaId != -1L && chapterId != -1L) {
            val cachedFile = eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash)
            if (cachedFile != null) {
                android.util.Log.d("ReaderPageImageView", "Cache hit for page $pageIndex (manga $mangaId), loading cached version directly")
                
                // Load cached image in background to avoid blocking
                launchIO {
                    try {
                        // Decode the cached file to bitmap for overlay
                        val cachedBitmap = android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath)
                        
                        if (cachedBitmap != null && !cachedBitmap.isRecycled) {
                            withUIContext {
                                // 1. Show overlay immediately to prevent flicker
                                enhancedBitmap = cachedBitmap
                                enhancedOverlay.setImageBitmap(cachedBitmap)
                                enhancedOverlay.isVisible = true
                                enhancedOverlay.bringToFront()
                                statusView.bringToFront()
                                
                                // 2. Update background view
                                android.util.Log.d("ReaderPageImageView", "Switching main view to cached enhanced source for page $pageIndex")
                                isSettingProcessedImage = true
                                setImage(ImageSource.uri(context, android.net.Uri.fromFile(cachedFile)))
                                isVisible = true
                                eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.markCompleted(pageIndex)
                                updateStatus("PROCESSED")
                            }
                        } else {
                            android.util.Log.w("ReaderPageImageView", "Failed to decode cached file for page $pageIndex, falling back to original")
                            // Fallback to original image if cache decode fails
                            withUIContext {
                                setImage(ImageSource.bitmap(bitmap))
                                isVisible = true
                                updateStatus("RAW")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderPageImageView", "Error loading cached image for page $pageIndex, falling back to original", e)
                        // Fallback to original image on error
                        withUIContext {
                            setImage(ImageSource.bitmap(bitmap))
                            isVisible = true
                            updateStatus("RAW")
                        }
                    }
                }
                
                return
            }
        }

        // No cache hit - show original image first, then process
        setImage(ImageSource.bitmap(bitmap))
        isVisible = true
        updateStatus("RAW")
        
        // Mark this page as queued for processing (so checkAndTriggerEnhancement knows it's being handled)
        if (pageIndex >= 0) {
            eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.markQueued(pageIndex)
        }

        processingJob?.cancel()
        processingJob = launchIO {
            try {
                // Lower thread priority to avoid blocking UI
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                
                // Wait until this page is within processing range
                while (pageIndex >= 0 && isActive) {
                    val currentPage = eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.currentReadingPage
                    
                    // Cancel if we're too far behind
                    // We allow up to 5 pages back to handle scroll back better
                    // This prevents wasting resources on pages the user has scrolled past
                    if (pageIndex < currentPage - 5) {
                        android.util.Log.d("ReaderPageImageView", "Cancelling page $pageIndex - too far behind ($currentPage)")
                        eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.markCancelled(pageIndex)
                        return@launchIO
                    }
                    
                    // Process if we're within range (current page ± 5, or ahead within preload range)
                    if (pageIndex >= currentPage - 5 && pageIndex <= currentPage + preloadSize) {
                        break
                    }
                    
                    // Too far ahead, wait a bit
                    delay(500)
                }

                android.util.Log.d("ReaderPageImageView", "Real-CUGAN starting processing page $pageIndex (inputScale=$realCuganInputScale%)...")
                
                // Check if bitmap was recycled before we could use it
                if (bitmap.isRecycled) {
                    android.util.Log.d("ReaderPageImageView", "Enhancement cancelled for page $pageIndex - bitmap was recycled")
                    eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.markCancelled(pageIndex)
                    return@launchIO
                }
                
                // First copy HARDWARE bitmap to software bitmap AND ensure we own it (copy) 
                // because EnhancementQueue might outlive this View/Bitmap
                var inputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

                // Apply max size limit if enabled
                if (inputBitmap.width > realCuganMaxSizeWidth || inputBitmap.height > realCuganMaxSizeHeight) {
                    if (realCuganResizeLargeImage) {
                        val ratio = kotlin.math.min(realCuganMaxSizeWidth.toFloat() / inputBitmap.width, realCuganMaxSizeHeight.toFloat() / inputBitmap.height)
                        val newWidth = (inputBitmap.width * ratio).toInt()
                        val newHeight = (inputBitmap.height * ratio).toInt()
                        android.util.Log.d("ReaderPageImageView", "Resizing large image: ${inputBitmap.width}x${inputBitmap.height} -> ${newWidth}x${newHeight}")
                        val scaled = Bitmap.createScaledBitmap(inputBitmap, newWidth, newHeight, true)
                        if (inputBitmap != bitmap) {
                            inputBitmap.recycle()
                        }
                        inputBitmap = scaled
                    } else {
                        android.util.Log.d("ReaderPageImageView", "Skipping processing: Image too large (${inputBitmap.width}x${inputBitmap.height} > max ${realCuganMaxSizeWidth}x${realCuganMaxSizeHeight})")
                        if (inputBitmap != bitmap) {
                            inputBitmap.recycle()
                        }
                        return@launchIO
                    }
                }

                // Pre-scale image if inputScale < 100 for faster processing
                if (realCuganInputScale < 100) {
                    val scale = realCuganInputScale / 100f
                    val newWidth = (inputBitmap.width * scale).toInt()
                    val newHeight = (inputBitmap.height * scale).toInt()
                    android.util.Log.d("ReaderPageImageView", "Pre-scaling (InputScale): ${inputBitmap.width}x${inputBitmap.height} -> ${newWidth}x${newHeight}")
                    val scaled = Bitmap.createScaledBitmap(inputBitmap, newWidth, newHeight, true)
                    if (inputBitmap != bitmap) {
                        inputBitmap.recycle()
                    }
                    inputBitmap = scaled
                }
                
                val initialized = when (realCuganModel) {
                    0 -> eu.kanade.tachiyomi.util.waifu2x.Waifu2x.initRealCugan(context, realCuganNoiseLevel, realCuganScale, false, tileSleepMs, tileSize)
                    1 -> eu.kanade.tachiyomi.util.waifu2x.Waifu2x.initRealCugan(context, realCuganNoiseLevel, realCuganScale, true, tileSleepMs, tileSize)
                    2 -> eu.kanade.tachiyomi.util.waifu2x.Waifu2x.initRealESRGAN(context, realCuganScale, tileSleepMs, tileSize)
                    3 -> eu.kanade.tachiyomi.util.waifu2x.Waifu2x.initNose(context, tileSleepMs, tileSize)
                    4 -> eu.kanade.tachiyomi.util.waifu2x.Waifu2x.initWaifu2x(context, realCuganNoiseLevel, 2, tileSleepMs, tileSize)
                    else -> eu.kanade.tachiyomi.util.waifu2x.Waifu2x.initRealCugan(context, realCuganNoiseLevel, realCuganScale, false, tileSleepMs, tileSize)
                }

                val progressJob = launchUI {
                    while (isActive) {
                        val packed = eu.kanade.tachiyomi.util.waifu2x.Waifu2x.getProgress()
                        val id = (packed shr 32).toInt()
                        val p = (packed and 0xFFFFFFFF).toInt()
                        
                        if (id == pageIndex) {
                            updateStatus("PROCESSING: $p%")
                        } else {
                            updateStatus("QUEUED")
                        }
                        delay(100)
                    }
                }

                val processed: Bitmap? = try {
                    if (initialized) {
                        eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.process(
                            pageIndex, 
                            inputBitmap, 
                            realCuganModel, 
                            realCuganNoiseLevel, 
                            if (realCuganModel == 3 || realCuganModel == 4) 2 else realCuganScale,
                            mangaId,
                            chapterId,
                            configHash
                        )
                    } else {
                        android.util.Log.e("ReaderPageImageView", "Waifu2x not initialized!")
                        null
                    }
                } finally {
                    progressJob.cancelAndJoin()
                }
                
                // Ownership of inputBitmap is now transferred to EnhancementQueue.
                // It will be recycled by the worker once processing is done.

                if (processed != null) {
                    android.util.Log.d("ReaderPageImageView", "Real-CUGAN processing complete for page $pageIndex, applying...")
                    
                    if (pageIndex >= 0) {
                        eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.markCompleted(pageIndex)
                    }

                    // Manually save to cache since we are processing locally
                    if (pageIndex >= 0 && mangaId != -1L && chapterId != -1L) {
                        eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache.saveToCache(
                            mangaId, chapterId, pageIndex, configHash, processed
                        )
                    }

                    // Get the file from cache
                    val savedFile = if (pageIndex >= 0 && mangaId != -1L && chapterId != -1L) {
                        eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash)
                    } else null

                    withUIContext {
                        // 1. Show overlay immediately to prevent flicker
                        enhancedBitmap = processed
                        enhancedOverlay.setImageBitmap(processed)
                        enhancedOverlay.isVisible = true
                        enhancedOverlay.bringToFront()
                        statusView.bringToFront()
                        
                        // 2. Update background view
                        android.util.Log.d("ReaderPageImageView", "Switching main view to enhanced source for page $pageIndex")
                        isSettingProcessedImage = true
                        if (savedFile != null) {
                            setImage(ImageSource.uri(context, android.net.Uri.fromFile(savedFile)))
                        } else {
                            // If not in cache and large, SSIV might fail with ImageSource.bitmap
                            if (processed.width > 4000 || processed.height > 4000) {
                                android.util.Log.w("ReaderPageImageView", "Large processed bitmap not in cache, saving to temp file to avoid black screen")
                                try {
                                    val tempFile = java.io.File(context.cacheDir, "enhanced_temp_${System.currentTimeMillis()}.png")
                                    java.io.FileOutputStream(tempFile).use { out ->
                                        processed.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    setImage(ImageSource.uri(context, android.net.Uri.fromFile(tempFile)))
                                    // Delete temp file after view is recycled or image is replaced? 
                                    // For now, let it be in cacheDir
                                } catch (e: Exception) {
                                    android.util.Log.e("ReaderPageImageView", "Failed to save temp file, falling back to bitmap source", e)
                                    setImage(ImageSource.bitmap(processed))
                                }
                            } else {
                                setImage(ImageSource.bitmap(processed))
                            }
                        }
                        
                        updateStatus("PROCESSED")
                        android.util.Log.d("ReaderPageImageView", "Processed update complete for page $pageIndex!")
                    }
                } else {
                    android.util.Log.e("ReaderPageImageView", "Real-CUGAN process returned null for page $pageIndex")
                    updateStatus("RAW")
                }
            } catch (e: Exception) {
                eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.markCancelled(pageIndex)
                if (e is kotlinx.coroutines.CancellationException) {
                    android.util.Log.d("ReaderPageImageView", "Enhancement cancelled for page $pageIndex")
                    // If cancelled, it might be because it was scrolled away or pre-empted
                    // We don't update status here as it might be recycled
                } else {
                    android.util.Log.e("ReaderPageImageView", "Enhancement error for page $pageIndex", e)
                    updateStatus("ERROR")
                }
            }
        }

        // Register job for potential cancellation
        if (pageIndex >= 0) {
            eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.registerJob(pageIndex, processingJob!!)
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (pageIndex >= 0) {
            eu.kanade.tachiyomi.util.waifu2x.EnhancementQueue.removePage(pageIndex)
        }
        enhancedOverlay.setImageBitmap(null)
        enhancedBitmap?.recycle()
        enhancedBitmap = null
        isSettingProcessedImage = false
    }
}

private const val MAX_ZOOM_SCALE = 5F

