package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
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
import androidx.core.animation.doOnEnd
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import kotlin.math.roundToInt

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

    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        get() = true

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
    private var currentLoadedUri: String? = null
    private var lastStatusText: String? = null

    var enhancedImageSourceFactory: ((java.io.File) -> BufferedSource?)? = null
    var suppressDefaultStatus = false

    private var config: Config? = null
    
    private var processingJob: Job? = null
    private var enhancedBitmap: Bitmap? = null
    private var processedSwapView: SubsamplingScaleImageView? = null

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    // Helper properties for Enhancement logic
    var pageIndex: Int = -1
    var mangaId: Long = -1L
    var chapterId: Long = -1L
    var readerPage: ReaderPage? = null
    var enhancementVariantOverride: String? = null
    var enhancementStreamOverride: (() -> java.io.InputStream)? = null
    var processedTransitionStartFraction: Float = 0f
    var processedTransitionEndFraction: Float = 1f
    var controlsCurrentPageSelection: Boolean = true

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

    private val transitionDivider: View by lazy {
        View(context).apply {
            val scannerHeight = (context.resources.displayMetrics.density * 28).toInt().coerceAtLeast(12)
            layoutParams = LayoutParams(MATCH_PARENT, scannerHeight)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    0x00D8D8D8,
                    0x16DCDCDC,
                    0x33E6E6E6,
                    0x16DCDCDC,
                    0x00D8D8D8,
                ),
            )
            alpha = 0.45f
            elevation = 100f
            isVisible = false
            this@ReaderPageImageView.addView(this)
        }
    }
    
    init {
        // Listen for performance mode changes to update native throttling immediately
        // Use launchIO to avoid blocking UI thread waiting for native lock if processing is active
        viewScope.launchIO {
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

        viewScope.launchIO {
            preferences.realCuganShowStatus().changes()
                .collect { enabled ->
                    withUIContext {
                        if (!enabled) {
                            statusView.isVisible = false
                        } else if (lastStatusText != null) {
                            statusView.text = lastStatusText
                            statusView.isVisible = true
                            statusView.bringToFront()
                        }
                    }
                }
        }
    }

    private fun updateStatus(text: String?) {
        lastStatusText = text
        post {
            if (suppressDefaultStatus) {
                statusView.isVisible = false
                return@post
            }
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
        
        // Keep the processed preview fully covering the page until the replacement image is ready,
        // then switch instantly to avoid exposing SSIV's blank loading state.
        if (isSettingProcessedImage) {
            pageView?.alpha = 1f
            enhancedOverlay.animate().cancel()
            enhancedOverlay.alpha = 0f
            enhancedOverlay.isVisible = false
            enhancedOverlay.setImageBitmap(null)
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
            pageView?.alpha = 1f
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
        
        // If zooming, dismiss the static overlay immediately to show the zoomable image
        if (newScale != 1f && isSettingProcessedImage) {
            enhancedOverlay.animate().cancel()
            enhancedOverlay.isVisible = false
            enhancedOverlay.setImageBitmap(null)
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
            pageView?.alpha = 1f
        }
    }

    private fun decodeEnhancedBitmap(file: java.io.File): Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun createSubsamplingPageView(): SubsamplingScaleImageView {
        return if (isWebtoon) {
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

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) = Unit
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
    }

    private fun clearProcessedSwapView() {
        processedSwapView?.let { swapView ->
            swapView.recycle()
            removeView(swapView)
        }
        processedSwapView = null
    }

    private fun displayedImageRect(view: SubsamplingScaleImageView): RectF? {
        val center = view.center ?: return null
        val scale = view.scale
        val sourceWidth = view.sWidth
        val sourceHeight = view.sHeight
        if (scale <= 0f || sourceWidth <= 0 || sourceHeight <= 0 || view.width <= 0 || view.height <= 0) {
            return null
        }

        val left = view.width / 2f - center.x * scale
        val top = view.height / 2f - center.y * scale
        return RectF(
            left,
            top,
            left + sourceWidth * scale,
            top + sourceHeight * scale,
        )
    }

    private fun displayedImageRect(view: ImageView): RectF? {
        val drawable = view.drawable ?: return null
        val drawableWidth = drawable.intrinsicWidth
        val drawableHeight = drawable.intrinsicHeight
        if (drawableWidth <= 0 || drawableHeight <= 0 || view.width <= 0 || view.height <= 0) {
            return null
        }

        return RectF(0f, 0f, drawableWidth.toFloat(), drawableHeight.toFloat()).apply {
            view.imageMatrix.mapRect(this)
        }
    }

    private fun animateProcessedSwap(
        activeView: SubsamplingScaleImageView,
        targetConfig: Config?,
        setImageBlock: SubsamplingScaleImageView.() -> Unit,
    ) {
        clearProcessedSwapView()

        val targetScale = activeView.scale
        val targetCenter = activeView.center?.let { PointF(it.x, it.y) }
        val targetMinScale = activeView.minScale
        val targetWidth = activeView.sWidth
        val targetHeight = activeView.sHeight
        val swapView = createSubsamplingPageView().apply {
            alpha = 0f
            isVisible = true
            setDoubleTapZoomDuration((targetConfig?.zoomDuration ?: 500).getSystemScaledDuration())
            setMinimumScaleType(targetConfig?.minimumScaleType ?: SCALE_TYPE_CENTER_INSIDE)
            setMinimumDpi(1)
            setCropBorders(targetConfig?.cropBorders ?: false)
            setOnImageEventListener(
                object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        setupZoom(targetConfig)

                        val wasZoomed =
                            targetScale > 0f &&
                                targetCenter != null &&
                                targetMinScale > 0f &&
                                targetWidth > 0 &&
                                targetHeight > 0 &&
                                targetScale > targetMinScale + 0.01f

                        if (wasZoomed && targetCenter != null) {
                            val zoomFactor = targetScale / targetMinScale
                            val mappedCenter = PointF(
                                (targetCenter.x / targetWidth) * sWidth,
                                (targetCenter.y / targetHeight) * sHeight,
                            )
                            val mappedScale = (minScale * zoomFactor).coerceIn(minScale, maxScale)
                            setScaleAndCenter(mappedScale, mappedCenter)
                        } else if (isVisibleOnScreen()) {
                            landscapeZoom(true)
                        }

                        bringToFront()
                        statusView.bringToFront()
                        pageView = this@apply
                        alpha = 1f
                        val revealStart = processedTransitionStartFraction.coerceIn(0f, 1f)
                        val revealEnd = processedTransitionEndFraction.coerceIn(revealStart, 1f)
                        val clipLeft = (width * revealStart).toInt()
                        val clipRight = (width * revealEnd).toInt().coerceAtLeast(clipLeft + 1)
                        clipBounds = Rect(clipLeft, 0, clipRight, 0)
                        val imageRect = displayedImageRect(this@apply)
                        val dividerLayoutParams = transitionDivider.layoutParams as LayoutParams
                        if (imageRect != null) {
                            val contentLeft =
                                imageRect.left + imageRect.width() * revealStart
                            val contentRight =
                                imageRect.left + imageRect.width() * revealEnd
                            dividerLayoutParams.width =
                                (contentRight - contentLeft).toInt().coerceAtLeast(1)
                            dividerLayoutParams.leftMargin = contentLeft.toInt()
                        } else {
                            dividerLayoutParams.width = (clipRight - clipLeft).coerceAtLeast(1)
                            dividerLayoutParams.leftMargin = clipLeft
                        }
                        transitionDivider.layoutParams = dividerLayoutParams
                        transitionDivider.bringToFront()
                        statusView.bringToFront()
                        transitionDivider.translationY = imageRect?.top ?: 0f
                        transitionDivider.isVisible = true
                        ValueAnimator.ofInt(0, height).apply {
                            duration = 800
                            addUpdateListener { animator ->
                                val scannerBottom = animator.animatedValue as Int
                                val scannerHeight = transitionDivider.height.coerceAtLeast(1)
                                val revealedHeight = (scannerBottom - scannerHeight / 2).coerceAtLeast(0)
                                clipBounds = Rect(clipLeft, 0, clipRight, revealedHeight)
                                val baseTop = imageRect?.top ?: 0f
                                val bottom = imageRect?.bottom ?: height.toFloat()
                                transitionDivider.translationY =
                                    (baseTop + scannerBottom - transitionDivider.height)
                                        .coerceIn(baseTop, bottom - transitionDivider.height.toFloat())
                            }
                            doOnEnd {
                                clipBounds = null
                                transitionDivider.isVisible = false
                                activeView.recycle()
                                removeView(activeView)
                                processedSwapView = null
                                this@ReaderPageImageView.onImageLoaded()
                            }
                            start()
                        }
                    }

                    override fun onImageLoadError(e: Exception) {
                        clearProcessedSwapView()
                        this@ReaderPageImageView.onImageLoadError(e)
                    }
                },
            )
        }
        processedSwapView = swapView
        addView(swapView, 0, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        swapView.setImageBlock()
    }

    protected fun setProcessedSource(
        file: java.io.File,
        bitmap: Bitmap? = null,
        transformedSource: BufferedSource? = null,
    ) {
        val uriString = file.toURI().toString()
        updateStatus(context.stringResource(MR.strings.reader_status_processed))

        if (transformedSource != null) {
            val activeView = pageView as? SubsamplingScaleImageView
            if (activeView != null) {
                animateProcessedSwap(activeView, config) {
                    setImage(ImageSource.inputStream(transformedSource.inputStream()))
                }
            } else {
                val previewBitmap = try {
                    android.graphics.BitmapFactory.decodeStream(transformedSource.peek().inputStream())
                } catch (_: Exception) {
                    null
                }

                if (previewBitmap != null) {
                    enhancedOverlay.scaleType = ImageView.ScaleType.FIT_CENTER
                    enhancedOverlay.bringToFront()
                    enhancedOverlay.setImageBitmap(previewBitmap)
                    enhancedOverlay.alpha = 1f
                    enhancedOverlay.isVisible = true
                    statusView.bringToFront()
                    enhancedBitmap = previewBitmap
                    isSettingProcessedImage = true
                    pageView?.alpha = 0f
                } else {
                    pageView?.alpha = 1f
                    enhancedOverlay.animate().cancel()
                    enhancedOverlay.setImageBitmap(null)
                    enhancedOverlay.isVisible = false
                    enhancedBitmap?.recycle()
                    enhancedBitmap = null
                    isSettingProcessedImage = false
                }
            }
            currentLoadedUri = uriString
            isVisible = true
            return
        }

        if (bitmap != null) {
            val activeView = pageView as? SubsamplingScaleImageView
            if (activeView != null) {
                animateProcessedSwap(activeView, config) {
                    val uri = android.net.Uri.fromFile(file)
                    setImage(ImageSource.uri(context, uri))
                }
                currentLoadedUri = uriString
                isVisible = true
                return
            }
            enhancedOverlay.scaleType = ImageView.ScaleType.FIT_CENTER
            enhancedOverlay.bringToFront()
            enhancedOverlay.setImageBitmap(bitmap)
            enhancedOverlay.alpha = 1f
            enhancedOverlay.isVisible = true
            statusView.bringToFront()
            enhancedBitmap = bitmap
            isSettingProcessedImage = true
            pageView?.alpha = 0f
        }

        val uri = android.net.Uri.fromFile(file)
        (pageView as? SubsamplingScaleImageView)?.setImage(ImageSource.uri(context, uri))
        currentLoadedUri = uriString
        isVisible = true
    }

    private fun enhancementVariant(): String {
        return enhancementVariantOverride ?: readerPage?.enhancementKeySuffix.orEmpty()
    }

    private fun buildEnhancementData(
        streamFn: (() -> java.io.InputStream)? = null,
        originalData: Any? = null,
    ): Any? {
        fun buffered(stream: (() -> java.io.InputStream)?): Any? {
            return stream?.let {
                try {
                    Buffer().readFrom(it())
                } catch (_: Exception) {
                    null
                }
            }
        }

        enhancementStreamOverride?.let { enhancedStream ->
            return buffered(enhancedStream)
        }

        readerPage?.let { page ->
            return buffered(page.stream) ?: page.imageUrl
        }

        return when (originalData) {
            is ReaderPage -> buffered(originalData.stream) ?: originalData.imageUrl
            else -> buffered(streamFn) ?: originalData
        }
    }

    private fun enqueueEnhancement(
        mId: Long,
        cId: Long,
        pIdx: Int,
        highPriority: Boolean,
        streamFn: (() -> java.io.InputStream)? = null,
        originalData: Any? = null,
    ) {
        val data = buildEnhancementData(streamFn, originalData) ?: return
        ImageEnhancer.enhance(
            context.applicationContext,
            mId,
            cId,
            pIdx,
            data,
            highPriority,
            enhancementVariant(),
        )
    }

    fun promoteEnhancementRequest(highPriority: Boolean = true) {
        if (!realCuganEnabled) return

        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex
        if (pIdx < 0 || mId == -1L || cId == -1L) return

        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(
            realCuganNoiseLevel,
            realCuganScale,
            realCuganInputScale,
            realCuganModel,
            realCuganMaxSizeWidth,
            realCuganMaxSizeHeight,
            realCuganResizeLargeImage,
        )
        val pageVariant = enhancementVariant()

        if (ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant) != null) return
        if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) return

        enqueueEnhancement(mId, cId, pIdx, highPriority)
    }

    private fun requeueEnhancement(
        mId: Long,
        cId: Long,
        pIdx: Int,
        triggerData: Any?,
        streamFn: (() -> java.io.InputStream)? = null,
        forceCurrentPage: Boolean = false,
    ) {
        val data = triggerData ?: buildEnhancementData(streamFn, triggerData)

        if (data == null) {
            logcat(LogPriority.WARN) {
                "ReaderPageImageView: Unable to re-enqueue page $pIdx because source data is unavailable"
            }
            return
        }

        val isCurrent = forceCurrentPage || pIdx == currentGlobalPageIndex || pIdx == ImageEnhancer.targetPageIndex
        logcat(LogPriority.WARN) {
            "ReaderPageImageView: Re-enqueueing page $pIdx after invalid enhanced cache (current=$isCurrent)"
        }

        ImageEnhancer.enhance(context.applicationContext, mId, cId, pIdx, data, isCurrent, enhancementVariant())
    }

    private suspend fun healInvalidEnhancedCache(
        mId: Long,
        cId: Long,
        pIdx: Int,
        configHash: String,
        triggerData: Any?,
        streamFn: (() -> java.io.InputStream)? = null,
        forceCurrentPage: Boolean = false,
    ) {
        val pageVariant = enhancementVariant()
        val removed = ImageEnhancementCache.removeCachedImage(mId, cId, pIdx, configHash, pageVariant)
        logcat(if (removed) LogPriority.WARN else LogPriority.ERROR) {
            "ReaderPageImageView: Invalid enhanced cache for page $pIdx/$pageVariant removed=$removed"
        }

        withUIContext {
            pageView?.alpha = 1f
            enhancedOverlay.setImageBitmap(null)
            enhancedOverlay.isVisible = false
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
            updateStatus(context.stringResource(MR.strings.reader_status_processing))
        }

        requeueEnhancement(mId, cId, pIdx, triggerData, streamFn, forceCurrentPage)
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
        if (controlsCurrentPageSelection && pIdx >= 0) {
            currentGlobalPageIndex = pIdx
            ImageEnhancer.reprioritizeAround(pIdx, enhancementVariant())
        }

        if (pIdx >= 0 && mId != -1L && cId != -1L) {
             // 1. Check cache status and update UI
             if (realCuganEnabled) {
                 ImageEnhancementCache.init(context)
                val configHash = ImageEnhancementCache.getConfigHash(
                     realCuganNoiseLevel, realCuganScale, realCuganInputScale,
                     realCuganModel, realCuganMaxSizeWidth, realCuganMaxSizeHeight, realCuganResizeLargeImage
                 )
                 val pageVariant = enhancementVariant()
                 
                 val cachedFile = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
                 if (cachedFile != null) {
                     // Already processed - update status and load enhanced image
                     logcat(LogPriority.DEBUG) { "ReaderPageImageView: onPageSelected - Page $pIdx found in cache" }
                     val uriString = cachedFile.toURI().toString()
                     if (currentLoadedUri != uriString) {
                         // Load the enhanced image if not already loaded or if source changed
                         viewScope.launchIO {
                             val transformedSource = enhancedImageSourceFactory?.invoke(cachedFile)
                             if (transformedSource != null) {
                                 withUIContext {
                                     setProcessedSource(cachedFile, transformedSource = transformedSource)
                                 }
                                 return@launchIO
                             }

                             val bitmap = decodeEnhancedBitmap(cachedFile)
                             
                             if (bitmap != null) {
                                 withUIContext {
                                     setProcessedSource(cachedFile, bitmap = bitmap)
                                 }
                             } else {
                                 healInvalidEnhancedCache(mId, cId, pIdx, configHash, readerPage, forceCurrentPage = true)
                                 startEnhancementPolling(mId, cId, pIdx, configHash, readerPage)
                             }
                         }
                     } else {
                         updateStatus(context.stringResource(MR.strings.reader_status_processed))
                     }
                 } else if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {

                     updateStatus(context.stringResource(MR.strings.reader_status_raw))
                } else {
                    // Not in cache, not skipped - ensure it's being processed with high priority
                    updateStatus(context.stringResource(MR.strings.reader_status_processing))
                     enqueueEnhancement(mId, cId, pIdx, highPriority = true)
                     
                     // Start/restart polling if not already running
                     startEnhancementPolling(mId, cId, pIdx, configHash)
                 }
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
                            this@ReaderPageImageView.onImageLoadError(e)
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
        currentLoadedUri = null
        invalidate()
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
        clearProcessedSwapView()
        removeView(pageView)

        pageView = createSubsamplingPageView()
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
            updateStatus(if (realCuganEnabled) null else context.stringResource(MR.strings.reader_status_raw))
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
            realCuganMaxSizeHeight,
            realCuganResizeLargeImage
        )
        val pageVariant = enhancementVariant()

        val cachedFile = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
        if (cachedFile != null) {
            logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx found in cache on first check: ${cachedFile.absolutePath}" }
            val transformedSource = enhancedImageSourceFactory?.invoke(cachedFile)
            if (transformedSource != null) {
                setProcessedSource(cachedFile, transformedSource = transformedSource)
            } else {
                val bitmap = decodeEnhancedBitmap(cachedFile)
                if (bitmap != null) {
                    val uri = android.net.Uri.fromFile(cachedFile)
                    setImage(ImageSource.uri(context, uri))
                    currentLoadedUri = cachedFile.toURI().toString()
                    isVisible = true
                    updateStatus(context.stringResource(MR.strings.reader_status_processed))
                } else {
                    viewScope.launchIO {
                        healInvalidEnhancedCache(
                            mId = mId,
                            cId = cId,
                            pIdx = pIdx,
                            configHash = configHash,
                            triggerData = originalData,
                            streamFn = streamFn,
                            forceCurrentPage = pIdx == currentGlobalPageIndex,
                        )
                        startEnhancementPolling(mId, cId, pIdx, configHash, originalData, streamFn)
                    }
                }
            }
            return
        }

        
        if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
            logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx marked as skipped, showing RAW" }
            updateStatus(context.stringResource(MR.strings.reader_status_raw))
            return
        }

        logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx NOT in cache, starting monitoring (m=$mId, c=$cId, config=$configHash)" }
        
        // Trigger enhancement if it's not already in progress
        val triggerData = buildEnhancementData(streamFn, originalData)
        
        
        if (triggerData != null) {
            // Use High Priority if this is the current target page (the one user is viewing)
            // This ensures re-queued pages are processed immediately, not stuck in low priority
            val isCurrentPage = pIdx == ImageEnhancer.targetPageIndex
            val isInitialTargetPage = currentGlobalPageIndex < 0 && isCurrentPage
            val canEnqueueNow = currentGlobalPageIndex >= 0 || isInitialTargetPage
            
            if (canEnqueueNow) {
                logcat(LogPriority.DEBUG) { "ReaderPageImageView: Triggering enhancement for page $pIdx. isCurrentPage=$isCurrentPage (target=${ImageEnhancer.targetPageIndex})" }

                ImageEnhancer.enhance(context.applicationContext, mId, cId, pIdx, triggerData, isCurrentPage, pageVariant)
            } else {
                logcat(LogPriority.DEBUG) {
                    "ReaderPageImageView: Delaying enhancement enqueue for page $pIdx until initial target page ${ImageEnhancer.targetPageIndex} starts"
                }
            }
        }
        
        // Simplified polling for the enhanced image in cache
        // Simplified polling for the enhanced image in cache
        startEnhancementPolling(mId, cId, pIdx, configHash, originalData, streamFn)
    }

    /**
     * Start or restart the polling job to monitor enhancement progress and update status.
     */
    private fun startEnhancementPolling(
        mId: Long, 
        cId: Long, 
        pIdx: Int, 
        configHash: String, 
        originalData: Any? = null,
        streamFn: (() -> java.io.InputStream)? = null
    ) {
        processingJob?.cancel()
        processingJob = viewScope.launchIO {
            try {
                val pageVariant = enhancementVariant()
                var attempts = 0
                var wasEnhancing = false
                while (attempts < 120 && isActive) {
                    if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
                        withUIContext { updateStatus(context.stringResource(MR.strings.reader_status_raw)) }
                        return@launchIO
                    }
                    val file = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
                    if (file != null) {
                        logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx/$pageVariant found in cache during polling: ${file.absolutePath}" }
                        val transformedSource = enhancedImageSourceFactory?.invoke(file)
                        if (transformedSource != null) {
                            withUIContext {
                                setProcessedSource(file, transformedSource = transformedSource)
                            }
                            return@launchIO
                        }

                        val bitmap = decodeEnhancedBitmap(file)

                        if (bitmap != null) {
                            withUIContext {
                                setProcessedSource(file, bitmap = bitmap)
                            }
                        } else {
                            healInvalidEnhancedCache(
                                mId = mId,
                                cId = cId,
                                pIdx = pIdx,
                                configHash = configHash,
                                triggerData = originalData,
                                streamFn = streamFn,
                                forceCurrentPage = pIdx == currentGlobalPageIndex,
                            )
                            delay(200)
                            continue
                        }

                        return@launchIO
                    }
                    
                    // Check progress status
                    val pid = eu.kanade.tachiyomi.util.waifu2x.Waifu2x.getProgressId()
                    if (pid == pIdx) {
                        wasEnhancing = true
                        val rawProgress = eu.kanade.tachiyomi.util.waifu2x.Waifu2x.getProgressPercent()
                        if (rawProgress in 0..100) {
                            updateStatus(context.stringResource(MR.strings.reader_status_enhancing_progress, rawProgress))
                        } else {
                            val dots = (rawProgress % 3).let { if (it < 0) -it else it } + 1
                            updateStatus(context.stringResource(MR.strings.reader_status_enhancing) + ".".repeat(dots))
                        }
                    } else if (ImageEnhancer.hasRequest(mId, cId, pIdx, pageVariant)) {
                        if (!wasEnhancing) {
                            updateStatus(context.stringResource(MR.strings.reader_status_queued))
                        } else {
                            updateStatus(context.stringResource(MR.strings.reader_status_finishing))
                        }
                    } else {
                        // Not in queue, not cached - might need to re-enqueue
                        val current = currentGlobalPageIndex
                        val shouldHeal = pIdx >= current && pIdx <= current + preloadSize
                        if (shouldHeal && !ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
                            logcat(LogPriority.WARN) { "ReaderPageImageView: Polling re-enqueue page $pIdx/$pageVariant (cur=$current)" }
                            val isCurrent = pIdx == current
                            enqueueEnhancement(mId, cId, pIdx, highPriority = isCurrent, originalData = originalData, streamFn = streamFn)
                        } else {
                            updateStatus(context.stringResource(MR.strings.reader_status_raw))
                            delay(2000)
                            return@launchIO
                        }
                    }
                    
                    delay(500)
                    attempts++
                }
            } catch (_: CancellationException) {
                return@launchIO
            } catch (e: Exception) {
                android.util.Log.e("ReaderPageImageView", "Error in polling for page $pIdx", e)
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        clearProcessedSwapView()
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
        processingJob?.cancel()
        processingJob = null
        
        // Cancel enhancement if this view is detached/recycled
        // Use readerPage as primary source, falling back to properties
        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex

        val pageVariant = enhancementVariant()
        if (
            mId != -1L &&
            cId != -1L &&
            pIdx >= 0 &&
            !ImageEnhancer.isFocusedTarget(pIdx, pageVariant)
        ) {
             ImageEnhancer.cancel(mId, cId, pIdx, pageVariant)
        }

        enhancedOverlay.setImageBitmap(null)
        enhancedBitmap?.recycle()
        enhancedBitmap = null
        isSettingProcessedImage = false
        currentLoadedUri = null
        clearProcessedSwapView()
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
