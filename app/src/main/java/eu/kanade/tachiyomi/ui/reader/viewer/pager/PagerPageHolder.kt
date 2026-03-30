package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.util.TypedValue
import android.widget.TextView
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import android.graphics.BitmapFactory
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.min
import eu.kanade.tachiyomi.util.system.isNightMode

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    private var extraPage: ReaderPage? = null,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page to extraPage

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val extraScope = MainScope()

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to its status.
     */
    private var loadJob: Job? = null

    /**
     * Job for loading the extra page and processing changes to its status.
     */
    private var extraLoadJob: Job? = null
    private var extraEnhancementWatchJob: Job? = null
    private var extraEnhancementState: String? = null
    private var halfStatusJob: Job? = null

    private val readerPreferences by lazy { Injekt.get<ReaderPreferences>() }
    private val leftStatusView by lazy { createHalfStatusView(Gravity.BOTTOM or Gravity.START) }
    private val rightStatusView by lazy { createHalfStatusView(Gravity.BOTTOM or Gravity.END) }

    init {
        // Set page index for enhancement priority tracking
        pageIndex = page.index
        mangaId = viewer.activity.viewModel.manga?.id ?: -1L
        chapterId = page.chapter.chapter.id ?: -1L
        refreshEnhancementTargets()
        readerPage = page
        suppressDefaultStatus = extraPage != null
        if (usesTransformedEnhancedDisplay()) {
            enhancedImageSourceFactory = { buildEnhancedDisplaySource(it) }
        }
        loadJob = scope.launch { loadPageAndProcessStatus() }
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        extraLoadJob?.cancel()
        extraLoadJob = null
        extraEnhancementWatchJob?.cancel()
        extraEnhancementWatchJob = null
        halfStatusJob?.cancel()
        halfStatusJob = null
    }

    private fun createHalfStatusView(gravity: Int): TextView {
        return TextView(context).apply {
            layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                this.gravity = gravity
                setMargins(20, 0, 20, 20)
            }
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            isVisible = false
            this@PagerPageHolder.addView(this)
        }
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return

        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            extraPage?.let { extraPage ->
                extraLoadJob = extraScope.launch {
                    launchIO {
                        loader.loadPage(extraPage)
                    }
                }
            }

            val statusFlow = if (extraPage == null) {
                page.statusFlow
            } else {
                kotlinx.coroutines.flow.combine(page.statusFlow, extraPage!!.statusFlow) { s1, s2 -> s1 to s2 }
            }

            statusFlow.collectLatest { pairOrStatus ->
                val (s1, s2) = if (pairOrStatus is Pair<*, *>) {
                    pairOrStatus.first as Page.State to pairOrStatus.second as Page.State
                } else {
                    pairOrStatus as Page.State to Page.State.Ready
                }

                when {
                    s1 is Page.State.Error -> setError(s1.error)
                    s2 is Page.State.Error -> setError(s2.error)
                    s1 == Page.State.Queue || s2 == Page.State.Queue -> setQueued()
                    s1 == Page.State.LoadPage || s2 == Page.State.LoadPage -> setLoading()
                    s1 == Page.State.DownloadImage || s2 == Page.State.DownloadImage -> {
                        setDownloading()
                        // TODO: Implement progress combining if needed
                    }
                    s1 == Page.State.Ready && s2 == Page.State.Ready -> setImage()
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)
        refreshEnhancementTargets()

        val streamFn = page.stream ?: return
        val streamFn2 = extraPage?.stream

        try {
            val (source, isAnimated, background) = withIOContext {
                val source = currentDisplayStream(page)?.use { s1 ->
                    if (extraPage != null) {
                        currentDisplayStream(extraPage!!)?.use { s2 ->
                            mergeOrSplitPages(Buffer().readFrom(s1), Buffer().readFrom(s2))
                        }
                    } else {
                        process(item, Buffer().readFrom(s1))
                    }
                }!!
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                Triple(source, isAnimated, background)
            }
            withUIContext {
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                    ),
                    streamFn.takeUnless { usesTransformedEnhancedDisplay() },
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
                startExtraEnhancementWatcherIfNeeded()
                startHalfStatusWatcherIfNeeded()
            }
        } catch (_: CancellationException) {
            return
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun usesTransformedEnhancedDisplay(): Boolean {
        return extraPage != null || viewer.config.dualPageRotateToFit
    }

    private fun refreshEnhancementTargets() {
        enhancementVariantOverride = enhancementVariantFor(page)
        enhancementStreamOverride = enhancementStreamFor(page)
    }

    private fun enhancementVariantFor(targetPage: ReaderPage): String {
        return ""
    }

    private fun enhancementStreamFor(targetPage: ReaderPage): (() -> java.io.InputStream)? {
        return null
    }

    private fun buildSplitEnhancementStream(targetPage: ReaderPage): (() -> java.io.InputStream)? {
        return {
            val originalStream = targetPage.stream ?: error("Missing source stream for split enhancement")
            val source = Buffer().readFrom(originalStream())
            if (!viewer.config.dualPageSplit || !isWideImage(source)) {
                source.inputStream()
            } else {
                ImageUtil.splitInHalf(source, splitSideFor(targetPage)).inputStream()
            }
        }
    }

    private fun currentDisplayStream(targetPage: ReaderPage, preferredEnhancedFile: java.io.File? = null): java.io.InputStream? {
        val enhancedFile = preferredEnhancedFile ?: currentEnhancedFile(targetPage)
        return try {
            when {
                enhancedFile != null -> enhancedFile.inputStream()
                targetPage is InsertPage && enhancementStreamFor(targetPage) != null -> enhancementStreamFor(targetPage)?.invoke()
                else -> targetPage.stream?.invoke()
            }
        } catch (_: Throwable) {
            targetPage.stream?.invoke()
        }
    }

    private fun currentEnhancedFile(targetPage: ReaderPage): java.io.File? {
        if (!readerPreferences.realCuganEnabled().get()) return null
        val mangaId = viewer.activity.viewModel.manga?.id ?: return null
        val chapterId = targetPage.chapter.chapter.id ?: return null
        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(
            readerPreferences.realCuganNoiseLevel().get(),
            readerPreferences.realCuganScale().get(),
            readerPreferences.realCuganInputScale().get(),
            readerPreferences.realCuganModel().get(),
            readerPreferences.realCuganMaxSizeWidth().get(),
            readerPreferences.realCuganMaxSizeHeight().get(),
            true,
        )
        return ImageEnhancementCache.getCachedImage(mangaId, chapterId, targetPage.index, configHash, enhancementVariantFor(targetPage))
    }

    private fun currentEnhancementState(targetPage: ReaderPage): String {
        if (!readerPreferences.realCuganEnabled().get()) return "disabled"
        val mangaId = viewer.activity.viewModel.manga?.id ?: return "missing-manga"
        val chapterId = targetPage.chapter.chapter.id ?: return "missing-chapter"
        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(
            readerPreferences.realCuganNoiseLevel().get(),
            readerPreferences.realCuganScale().get(),
            readerPreferences.realCuganInputScale().get(),
            readerPreferences.realCuganModel().get(),
            readerPreferences.realCuganMaxSizeWidth().get(),
            readerPreferences.realCuganMaxSizeHeight().get(),
            true,
        )
        return when {
            ImageEnhancementCache.isSkipped(mangaId, chapterId, targetPage.index, configHash, enhancementVariantFor(targetPage)) -> "skipped"
            ImageEnhancementCache.isCached(mangaId, chapterId, targetPage.index, configHash, enhancementVariantFor(targetPage)) -> "cached"
            else -> "raw"
        }
    }

    private fun buildEnhancedDisplaySource(primaryEnhancedFile: java.io.File): BufferedSource? {
        val primaryStream = currentDisplayStream(page, primaryEnhancedFile) ?: return null
        return primaryStream.use { s1 ->
            if (extraPage != null) {
                val extraStream = currentDisplayStream(extraPage!!) ?: return null
                extraStream.use { s2 ->
                    mergeOrSplitPages(Buffer().readFrom(s1), Buffer().readFrom(s2))
                }
            } else {
                process(item, Buffer().readFrom(s1))
            }
        }
    }

    private fun setTransitionTargetPage(targetPage: ReaderPage?) {
        if (extraPage == null || targetPage == null) {
            processedTransitionStartFraction = 0f
            processedTransitionEndFraction = 1f
            return
        }

        val leftPage = if (viewer is R2LPagerViewer) extraPage else page
        val isLeftHalf = targetPage == leftPage
        if (isLeftHalf) {
            processedTransitionStartFraction = 0f
            processedTransitionEndFraction = 0.5f
        } else {
            processedTransitionStartFraction = 0.5f
            processedTransitionEndFraction = 1f
        }
    }

    private fun smoothRefreshCombinedDisplay() {
        val secondaryPage = extraPage ?: return
        val primaryEnhancedFile = currentEnhancedFile(page)
        val secondaryEnhancedFile = currentEnhancedFile(secondaryPage)
        val anchorFile = primaryEnhancedFile ?: secondaryEnhancedFile ?: return

        val transformedSource =
            if (primaryEnhancedFile != null) {
                buildEnhancedDisplaySource(primaryEnhancedFile)
            } else {
                val primaryStream = currentDisplayStream(page) ?: return
                primaryStream.use { s1 ->
                    val extraStream = currentDisplayStream(secondaryPage) ?: return
                    extraStream.use { s2 ->
                        mergeOrSplitPages(Buffer().readFrom(s1), Buffer().readFrom(s2))
                    }
                }
            } ?: return
        setTransitionTargetPage(secondaryPage)
        setProcessedSource(anchorFile, transformedSource = transformedSource)
    }

    private fun startExtraEnhancementWatcherIfNeeded() {
        val targetPage = extraPage ?: return
        if (!usesTransformedEnhancedDisplay() || !readerPreferences.realCuganEnabled().get()) return

        val initialState = currentEnhancementState(targetPage)
        if (extraEnhancementWatchJob != null && extraEnhancementState == initialState) return

        extraEnhancementWatchJob?.cancel()
        extraEnhancementState = initialState
        extraEnhancementWatchJob = scope.launch {
            while (isActive) {
                val newState = currentEnhancementState(targetPage)
                if (newState != extraEnhancementState) {
                    val previousState = extraEnhancementState
                    extraEnhancementState = newState
                    if (
                        newState == "cached" &&
                        previousState != "cached" &&
                        page.status == Page.State.Ready &&
                        targetPage.status == Page.State.Ready
                    ) {
                        withUIContext {
                            smoothRefreshCombinedDisplay()
                        }
                    }
                }
                delay(500)
            }
        }
    }

    private fun startHalfStatusWatcherIfNeeded() {
        if (extraPage == null) {
            leftStatusView.isVisible = false
            rightStatusView.isVisible = false
            halfStatusJob?.cancel()
            halfStatusJob = null
            return
        }

        suppressDefaultStatus = true
        halfStatusJob?.cancel()
        halfStatusJob = scope.launch {
            while (isActive) {
                withUIContext {
                    if (!readerPreferences.realCuganEnabled().get() || !readerPreferences.realCuganShowStatus().get()) {
                        leftStatusView.isVisible = false
                        rightStatusView.isVisible = false
                    } else {
                        val leftPage = if (viewer is R2LPagerViewer) extraPage else page
                        val rightPage = if (viewer is R2LPagerViewer) page else extraPage
                        updateHalfStatusView(leftStatusView, leftPage)
                        updateHalfStatusView(rightStatusView, rightPage)
                    }
                }
                delay(300)
            }
        }
    }

    private fun updateHalfStatusView(view: TextView, targetPage: ReaderPage?) {
        val label = enhancementStatusLabel(targetPage)
        view.text = label
        view.isVisible = !label.isNullOrEmpty()
        if (view.isVisible) {
            view.bringToFront()
        }
    }

    private fun enhancementStatusLabel(targetPage: ReaderPage?): String? {
        targetPage ?: return null
        if (!readerPreferences.realCuganEnabled().get()) return null

        val mangaId = viewer.activity.viewModel.manga?.id ?: return null
        val chapterId = targetPage.chapter.chapter.id ?: return null
        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(
            readerPreferences.realCuganNoiseLevel().get(),
            readerPreferences.realCuganScale().get(),
            readerPreferences.realCuganInputScale().get(),
            readerPreferences.realCuganModel().get(),
            readerPreferences.realCuganMaxSizeWidth().get(),
            readerPreferences.realCuganMaxSizeHeight().get(),
            true,
        )

        return when {
            ImageEnhancementCache.isCached(mangaId, chapterId, targetPage.index, configHash, enhancementVariantFor(targetPage)) ->
                context.stringResource(MR.strings.reader_status_processed)
            ImageEnhancementCache.isSkipped(mangaId, chapterId, targetPage.index, configHash, enhancementVariantFor(targetPage)) ->
                context.stringResource(MR.strings.reader_status_raw)
            ImageEnhancer.isActivelyProcessing(mangaId, chapterId, targetPage.index, enhancementVariantFor(targetPage)) -> {
                val rawProgress = eu.kanade.tachiyomi.util.waifu2x.Waifu2x.getProgressPercent()
                if (rawProgress in 0..100) {
                    context.stringResource(MR.strings.reader_status_enhancing_progress, rawProgress)
                } else {
                    context.stringResource(MR.strings.reader_status_enhancing)
                }
            }
            ImageEnhancer.hasRequest(mangaId, chapterId, targetPage.index, enhancementVariantFor(targetPage)) ->
                context.stringResource(MR.strings.reader_status_queued)
            else -> context.stringResource(MR.strings.reader_status_raw)
        }
    }

    private fun mergeOrSplitPages(imageSource: BufferedSource, imageSource2: BufferedSource?): BufferedSource? {
        if (imageSource2 == null) {
            return process(item, imageSource)
        }

        if (viewer.config.doublePages) {
            val primaryWide = isWideImage(imageSource)
            if (primaryWide) {
                viewer.onWidePageDetected(page)
                return imageSource
            }

            val secondaryPage = extraPage
            val secondaryWide = secondaryPage != null && isWideImage(imageSource2)
            if (secondaryWide) {
                viewer.onWidePageDetected(secondaryPage!!)
                return imageSource2
            }
        }

        val bitmap1 = BitmapFactory.decodeStream(imageSource.inputStream())
        val bitmap2 = BitmapFactory.decodeStream(imageSource2.inputStream())

        if (bitmap1 == null || bitmap2 == null) {
            return imageSource
        }

        val isLTR = viewer !is R2LPagerViewer
        val grayBackground = Color.rgb(0x20, 0x21, 0x25)
        val background =
            when (viewer.config.readerTheme) {
                0 -> Color.WHITE
                2 -> grayBackground
                3 -> if (context.isNightMode()) grayBackground else Color.WHITE
                else -> Color.BLACK
            }

        val mergedSource = mergeBitmapsPerHalf(bitmap1, bitmap2, isLTR, background)

        // Clean up old bitmaps
        bitmap1.recycle()
        bitmap2.recycle()

        return mergedSource
    }

    private fun mergeBitmapsPerHalf(
        firstBitmap: Bitmap,
        secondBitmap: Bitmap,
        isLTR: Boolean,
        background: Int,
    ): BufferedSource {
        val metrics = context.resources.displayMetrics
        val viewportWidth =
            when {
                width > 0 -> width
                viewer.pager.width > 0 -> viewer.pager.width
                else -> metrics.widthPixels
            }.coerceAtLeast(2)
        val viewportHeight =
            when {
                height > 0 -> height
                viewer.pager.height > 0 -> viewer.pager.height
                else -> metrics.heightPixels
            }.coerceAtLeast(1)

        val adjustedHingeGap = viewer.config.hingeGapSize.coerceAtLeast(0)
        val cellWidth = ((viewportWidth - adjustedHingeGap).coerceAtLeast(2)) / 2
        val result = createBitmap((cellWidth * 2) + adjustedHingeGap, viewportHeight)

        result.applyCanvas {
            drawColor(background)

            fun drawFitted(bitmap: Bitmap, cellLeft: Int, alignToCenter: Boolean) {
                val scale = min(
                    cellWidth / bitmap.width.toFloat(),
                    viewportHeight / bitmap.height.toFloat(),
                )
                val destWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val destHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val left =
                    if (alignToCenter) {
                        cellLeft + (cellWidth - destWidth)
                    } else {
                        cellLeft
                    }
                val top = (viewportHeight - destHeight) / 2
                val dest = Rect(left, top, left + destWidth, top + destHeight)
                drawBitmap(bitmap, null, dest, null)
            }

            val leftCellStart = 0
            val rightCellStart = cellWidth + adjustedHingeGap
            if (isLTR) {
                drawFitted(firstBitmap, leftCellStart, alignToCenter = true)
                drawFitted(secondBitmap, rightCellStart, alignToCenter = false)
            } else {
                drawFitted(secondBitmap, leftCellStart, alignToCenter = true)
                drawFitted(firstBitmap, rightCellStart, alignToCenter = false)
            }
        }

        val output = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())
        return output
    }

    private fun process(item: Any, imageSource: BufferedSource): BufferedSource {
        val page = if (item is Pair<*, *>) item.first as ReaderPage else item as ReaderPage
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.doublePages) {
            return imageSource
        }

        if (isWideImage(imageSource)) {
            viewer.onWidePageDetected(page)
        }
        return imageSource
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun splitInHalf(imageSource: BufferedSource, targetPage: ReaderPage = page): BufferedSource {
        return ImageUtil.splitInHalf(imageSource, splitSideFor(targetPage))
    }

    private fun splitSideFor(targetPage: ReaderPage): ImageUtil.Side {
        var side = when {
            viewer is L2RPagerViewer && targetPage is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && targetPage is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && targetPage !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && targetPage !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return side
    }

    private fun isWideImage(imageSource: BufferedSource): Boolean {
        return ImageUtil.isWideImage(imageSource.peek())
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    override fun onPageSelected(forward: Boolean) {
        setTransitionTargetPage(page)
        super.onPageSelected(forward)
        ImageEnhancer.reprioritizeAround(
            pageIndex = page.index,
            pageVariant = enhancementVariantFor(page),
            secondaryPageIndex = extraPage?.index,
            secondaryPageVariant = extraPage?.let(::enhancementVariantFor).orEmpty(),
        )
        ensureVisibleExtraPageEnhancement()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }

    private fun ensureVisibleExtraPageEnhancement() {
        val targetPage = extraPage ?: return
        if (!readerPreferences.realCuganEnabled().get()) return

        val mangaId = viewer.activity.viewModel.manga?.id ?: return
        val chapterId = targetPage.chapter.chapter.id ?: return
        targetPage.stream ?: return

        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(
            readerPreferences.realCuganNoiseLevel().get(),
            readerPreferences.realCuganScale().get(),
            readerPreferences.realCuganInputScale().get(),
            readerPreferences.realCuganModel().get(),
            readerPreferences.realCuganMaxSizeWidth().get(),
            readerPreferences.realCuganMaxSizeHeight().get(),
            true,
        )

        val pageVariant = enhancementVariantFor(targetPage)
        if (ImageEnhancementCache.isCached(mangaId, chapterId, targetPage.index, configHash, pageVariant)) {
            return
        }

        if (ImageEnhancementCache.isSkipped(mangaId, chapterId, targetPage.index, configHash, pageVariant)) {
            ImageEnhancementCache.removeSkipMarker(mangaId, chapterId, targetPage.index, configHash, pageVariant)
        }

        logcat(LogPriority.DEBUG) {
            "PagerPageHolder: Prioritizing visible extra page ${targetPage.index}/$pageVariant"
        }
        val triggerData = enhancementStreamFor(targetPage)?.let { streamFn ->
            try {
                Buffer().readFrom(streamFn())
            } catch (_: Exception) {
                null
            }
        } ?: targetPage.stream?.let { streamFn ->
            try {
                Buffer().readFrom(streamFn())
            } catch (_: Exception) {
                null
            }
        } ?: targetPage.imageUrl ?: return
        ImageEnhancer.enhance(
            context.applicationContext,
            mangaId,
            chapterId,
            targetPage.index,
            triggerData,
            true,
            pageVariant,
        )
    }
}
