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
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import okio.Buffer
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import uy.kohesive.injekt.injectLazy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.util.lang.launchUI
import kotlinx.coroutines.CoroutineScope
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

    // Helper properties for Enhancement logic
    var pageIndex: Int = -1
    var mangaId: Long = -1L
    var chapterId: Long = -1L
    var readerPage: ReaderPage? = null

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
        post {
            if (!realCuganShowStatus || text == null) {
                statusView.isVisible = false
                return@post
            }
            statusView.text = text
            statusView.isVisible = true
            statusView.bringToFront()
        }
    }

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
        
        // Synchronized Fade-out: Only hide overlay after the new image is actually rendered
        if (isSettingProcessedImage) {
            enhancedOverlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    enhancedOverlay.isVisible = false
                    enhancedOverlay.setImageBitmap(null)
                    enhancedBitmap?.recycle()
                    enhancedBitmap = null
                    isSettingProcessedImage = false
                }
                .start()
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
        // Pruning Logic: Triggered only when this page is actually selected/viewed
        // Pruning Logic: Triggered only when this page is actually selected/viewed
        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex

        // Update global current page index to help other instances decide if they should self-heal
        if (pIdx >= 0) {
             currentGlobalPageIndex = pIdx
             ImageEnhancer.targetPageIndex = pIdx
        }

        if (pIdx >= 0 && mId != -1L && cId != -1L) {
             // 1. Ensure this page is being processed (Restore if pruned, Upgrade if Low)
             val page = readerPage
             if (page != null) {
                 ImageEnhancer.enhance(context.applicationContext, page, true)
             }
             
             // 2. Prune others
             ImageEnhancer.cancelRequestsLessThan(context.applicationContext, mId, cId, pIdx)
             ImageEnhancer.cancelRequestsGreaterThan(context.applicationContext, mId, cId, pIdx + preloadSize)
        }

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

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config, streamFn: (() -> java.io.InputStream)? = null) {
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config, streamFn)
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
        streamFn: (() -> java.io.InputStream)? = null,
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
                setImage(ImageSource.bitmap(data.bitmap))
                processImageHelper(originalData = data.bitmap)
            }
            is BufferedSource -> {
                // Determine hardware config and load stream for SSIV display
                setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                setImage(ImageSource.inputStream(data.inputStream()))
                isVisible = true

                // Enhancement Logic for Stream Sources
                if (realCuganEnabled && pageIndex >= 0 && mangaId != -1L) {
                     processImageHelper(streamFn = streamFn)
                }
                return@apply
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun SubsamplingScaleImageView.processImageHelper(
        streamFn: (() -> java.io.InputStream)? = null,
        originalData: Any? = null
    ) {
        if (isSettingProcessedImage || !realCuganEnabled) {
            updateStatus(if (realCuganEnabled) null else "RAW")
            return
        }

        // Initialize IDs from readerPage if available, otherwise use properties
        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex

        if (pIdx < 0 || mId == -1L || cId == -1L) {
            logcat(LogPriority.DEBUG) { "ReaderPageImageView: Skipping enhancement, invalid IDs (m=$mId, c=$cId, p=$pIdx)" }
            return
        }

        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(
            realCuganNoiseLevel, 
            realCuganScale, 
            realCuganInputScale,
            realCuganModel,
            realCuganMaxSizeWidth,
            realCuganMaxSizeHeight
        )

        val cachedFile = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash)
        if (cachedFile != null) {
            logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx found in cache on first check: ${cachedFile.absolutePath}" }
            setImage(ImageSource.uri(context, android.net.Uri.fromFile(cachedFile)))
            isVisible = true
            updateStatus("PROCESSED")
            return
        }

        logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx NOT in cache, starting monitoring (m=$mId, c=$cId, config=$configHash)" }
        
        // Trigger enhancement if it's not already in progress
        val triggerData = readerPage ?: streamFn?.let {
            try {
                okio.Buffer().readFrom(it())
            } catch (e: Exception) {
                null
            }
        } ?: originalData
        
        
        if (triggerData != null) {
            // Default to Low Priority for preloads. onPageSelected will upgrade to High.
            if (triggerData is ReaderPage) {
                ImageEnhancer.enhance(context.applicationContext, triggerData, false)
            } else {
                ImageEnhancer.enhance(context.applicationContext, mId, cId, pIdx, triggerData, false)
            }
        }
        
        // Simplified polling for the enhanced image in cache
        processingJob?.cancel()
        processingJob = launchIO {
            try {
                // Poll every 500ms for up to 60 seconds if it's the current page
                var attempts = 0
                var wasEnhancing = false
                while (attempts < 120 && isActive) {
                    val file = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash)
                    if (file != null) {
                        logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx found in cache during polling: ${file.absolutePath}" }
                        // Fix Flicker: "Instant Swap" Strategy with Pre-decoded Bitmap
                        // Decode bitmap in IO thread to avoid UI thread lag/flash
                        val bitmap = try {
                            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        } catch (e: Exception) { null }

                        if (bitmap != null) {
                            withUIContext {
                                // 1. Show enhanced image on overlay immediately (opaque)
                                enhancedOverlay.bringToFront()
                                enhancedOverlay.setImageBitmap(bitmap)
                                enhancedOverlay.alpha = 1f
                                enhancedOverlay.isVisible = true
                                updateStatus("PROCESSED")

                                // 2. Mark state transition
                                isSettingProcessedImage = true
                                enhancedBitmap = bitmap
                                
                                // 3. Swap the underlying view - onImageLoaded() will handle the fade-out
                                if (context != null) {
                                    setImage(ImageSource.uri(context, android.net.Uri.fromFile(file)))
                                    isVisible = true
                                }
                            }
                        } else {
                           // Fail-safe
                           withUIContext {
                               setImage(ImageSource.uri(context, android.net.Uri.fromFile(file)))
                               isVisible = true
                               updateStatus("PROCESSED")
                           }
                        }
                        return@launchIO
                    }
                    
                    // Check status every 500ms
                    val pid = eu.kanade.tachiyomi.util.waifu2x.Waifu2x.processingId
                    if (pid == pIdx) {
                        wasEnhancing = true
                        val rawProgress = eu.kanade.tachiyomi.util.waifu2x.Waifu2x.getProgress()
                        if (rawProgress in 0..100) {
                            updateStatus("ENHANCING... $rawProgress%")
                        } else {
                            val dots = (rawProgress % 3).toInt().let { if (it < 0) -it else it } + 1
                            updateStatus("ENHANCING" + ".".repeat(dots))
                        }
                    } else if (ImageEnhancer.hasRequest(mId, cId, pIdx)) {
                        // Prevent reverting to QUEUED if we were just enhancing (race condition at finish)
                        if (!wasEnhancing) {
                            updateStatus("QUEUED")
                        } else {
                            updateStatus("FINISHING...")
                        }
                    }
                    
                    if (attempts % 10 == 0) { // Log every 5 seconds
                         logcat(LogPriority.DEBUG) { "ReaderPageImageView: Polling for page $pIdx cache (attempt $attempts)..." }
                         
                         // Self-Healing: Check if request is still active
                         if (!ImageEnhancer.hasRequest(mId, cId, pIdx)) {
                             // Only restart if:
                             // 1. This page is ahead of or equal to the current reading page
                             // 2. AND this page is within the preload range
                             val current = currentGlobalPageIndex
                             val shouldHeal = pIdx >= current && pIdx <= current + preloadSize
                             
                             if (shouldHeal) {
                                 logcat(LogPriority.WARN) { "ReaderPageImageView: Request for page $pIdx (cur=$current) missing, restarting enhancement..." }
                                 
                                 val page = readerPage
                                 if (page != null) {
                                     ImageEnhancer.enhance(context.applicationContext, page, true)
                                 } else {
                                     val retryData = streamFn?.let { 
                                         try { okio.Buffer().readFrom(it()) } catch (e: Exception) { null } 
                                     } ?: originalData
                                     
                                     if (retryData != null) {
                                         ImageEnhancer.enhance(context.applicationContext, mId, cId, pIdx, retryData, true)
                                     }
                                 }
                             }
                         }
                    }

                    delay(500)
                    attempts++
                    
                    // Update status
                    // Status updated above inside loop

                }
            } catch (e: Exception) {
                android.util.Log.e("ReaderPageImageView", "Error monitoring enhancement for page $pageIndex", e)
            }
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
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
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
        
        // Cancel enhancement if this view is detached/recycled
        // Use readerPage as primary source, falling back to properties
        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex

        if (mId != -1L && cId != -1L && pIdx >= 0) {
             ImageEnhancer.cancel(mId, cId, pIdx)
        }

        enhancedOverlay.setImageBitmap(null)
        enhancedBitmap?.recycle()
        enhancedBitmap = null
        isSettingProcessedImage = false
    } 

    companion object {
        var currentGlobalPageIndex: Int = -1
        
        /**
         * Global semaphore to serialize image decoding.
         * Native HEIF decoders on some devices are not thread-safe and can SIGSEGV if used concurrently.
         */
        val decodeSemaphore = Semaphore(1) // Make public if needed, or keep private if only used here
    }
}

private const val MAX_ZOOM_SCALE = 5F
