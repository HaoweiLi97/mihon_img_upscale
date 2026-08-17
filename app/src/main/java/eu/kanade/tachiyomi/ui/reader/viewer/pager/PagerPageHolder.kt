package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Paint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.util.TypedValue
import android.widget.TextView
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.view.doOnLayout
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
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
import kotlin.math.max
import kotlin.math.min
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
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

    private data class RenderedPage(
        val source: BufferedSource,
        val isAnimated: Boolean,
        val background: Drawable?,
        val splitPages: Set<ReaderPage>,
    )

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
    private var pageSpreadRetryJob: Job? = null

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
        val pageInset = viewer.config.verticalPaddingDp.dpToPx
        setPadding(pageInset, pageInset, pageInset, pageInset)
        suppressDefaultStatus = extraPage != null
        if (usesEnhancedDisplayTransform()) {
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
        pageSpreadRetryJob?.cancel()
        pageSpreadRetryJob = null
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
        awaitCombinedPageLayoutIfNeeded()

        val streamFn = page.stream ?: return
        val streamFn2 = extraPage?.stream

        try {
            val renderedPage = withIOContext {
                val splitPages = mutableSetOf<ReaderPage>()
                val source = currentDisplayStream(page)?.use { s1 ->
                    val primarySource = Buffer().readFrom(s1)
                    if (extraPage != null) {
                        currentDisplayStream(extraPage!!)?.use { s2 ->
                            mergeOrSplitPages(
                                pageSourceForCombinedDisplay(page, primarySource),
                                pageSourceForCombinedDisplay(extraPage!!, Buffer().readFrom(s2)),
                            )
                        }
                    } else {
                        splitPages += detectPageSpread(primarySource)
                        if (requiresSplitBeforeSpreadDetection(page, primarySource)) {
                            splitPages += page
                        }
                        process(item, primarySource)
                    }
                }!!
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                RenderedPage(source, isAnimated, background, splitPages)
            }
            withUIContext {
                setImage(
                    renderedPage.source,
                    renderedPage.isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                    ),
                    streamFn.takeUnless { usesTransformedEnhancedDisplay() },
                )
                if (!renderedPage.isAnimated) {
                    pageBackground = renderedPage.background
                }
                removeErrorLayout()
                startExtraEnhancementWatcherIfNeeded()
                startHalfStatusWatcherIfNeeded()
                // The current half is now visible. Inserting its sibling before this point can
                // detach this holder and cancel its image load, which briefly shows a black page.
                insertSplitPagesAfterDisplay(renderedPage.splitPages)
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

    private suspend fun awaitCombinedPageLayoutIfNeeded() {
        if (extraPage == null || hasCombinedViewportSize()) {
            return
        }

        suspendCancellableCoroutine { continuation ->
            doOnLayout {
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    private fun hasCombinedViewportSize(): Boolean {
        return width > 0 && height > 0
    }

    private fun detectPageSpread(primarySource: BufferedSource): Set<ReaderPage> {
        val candidate = viewer.getPageSpreadCandidate(page) ?: run {
            logcat(LogPriority.INFO) {
                "PageSpread: no candidate for page ${page.index}; auto=${viewer.config.autoDetectPageSpreads}, double=${viewer.config.doublePages}"
            }
            return emptySet()
        }
        logcat(LogPriority.INFO) {
            "PageSpread: checking pages ${candidate.first.index} and ${candidate.second.index}"
        }
        if (requiresSplitBeforeSpreadDetection(page, primarySource)) {
            logcat(LogPriority.INFO) { "PageSpread: deferring page ${page.index} until its wide source is split" }
            viewer.onPageSpreadDeferred(candidate)
            return setOf(page)
        }

        val adjacentPage = if (candidate.first === page) candidate.second else candidate.first
        val adjacentSource = currentDisplayStream(adjacentPage)?.use { Buffer().readFrom(it) }
        if (adjacentSource == null) {
            logcat(LogPriority.INFO) {
                "PageSpread: waiting for adjacent page ${adjacentPage.index}; state=${adjacentPage.status}"
            }
            deferPageSpreadUntilAdjacentIsReady(candidate, adjacentPage)
            return emptySet()
        }

        if (requiresSplitBeforeSpreadDetection(adjacentPage, adjacentSource)) {
            logcat(LogPriority.INFO) { "PageSpread: deferring until adjacent wide page ${adjacentPage.index} is split" }
            viewer.onPageSpreadDeferred(candidate)
            return setOf(adjacentPage)
        }

        val firstSource = if (candidate.first === page) primarySource else adjacentSource
        val secondSource = if (candidate.second === page) primarySource else adjacentSource
        val result = PageSpreadDetector.detect(
            firstSource = pageSourceForCombinedDisplay(candidate.first, firstSource),
            secondSource = pageSourceForCombinedDisplay(candidate.second, secondSource),
            isR2L = viewer is R2LPagerViewer,
        )
        logcat(LogPriority.INFO) {
            "PageSpread: pages ${candidate.first.index}/${candidate.second.index} confidence=${result.confidence}, detected=${result.isSpread}, " +
                "blocks=${result.matchingBlocks}/${result.informativeBlocks}/${result.totalBlocks}, bands=${result.matchingBands}, " +
                "spatial=${result.spatialMatchingBlocks}"
        }
        viewer.onPageSpreadChecked(candidate, result.isSpread)
        return emptySet()
    }

    private fun deferPageSpreadUntilAdjacentIsReady(
        candidate: PagerViewerAdapter.PageSpreadCandidate,
        adjacentPage: ReaderPage,
    ) {
        viewer.onPageSpreadDeferred(candidate)
        if (pageSpreadRetryJob?.isActive == true) return

        pageSpreadRetryJob = scope.launch {
            adjacentPage.statusFlow.first { it == Page.State.Ready }
            logcat(LogPriority.INFO) { "PageSpread: adjacent page ${adjacentPage.index} is ready; retrying" }
            // Release this retry before checking again so a transient stream failure can defer a
            // new retry instead of being treated as an already-active job.
            pageSpreadRetryJob = null
            withIOContext {
                currentDisplayStream(page)?.use { source ->
                    val splitPages = detectPageSpread(Buffer().readFrom(source))
                    withUIContext {
                        insertSplitPagesAfterDisplay(splitPages)
                    }
                }
            }
        }
    }

    private fun requiresSplitBeforeSpreadDetection(targetPage: ReaderPage, source: BufferedSource): Boolean {
        return targetPage !is InsertPage &&
            !viewer.hasSplitPage(targetPage) &&
            shouldSplitWidePagesInSinglePageMode() &&
            isWideImage(source)
    }

    private fun pageSourceForCombinedDisplay(targetPage: ReaderPage, source: BufferedSource): BufferedSource {
        return if (shouldSplitWidePagesInSinglePageMode() && isWideImage(source)) {
            ImageUtil.splitInHalf(source.peek(), splitSideFor(targetPage))
        } else {
            source
        }
    }

    private fun usesTransformedEnhancedDisplay(): Boolean {
        return extraPage != null || viewer.config.dualPageRotateToFit
    }

    private fun usesEnhancedDisplayTransform(): Boolean {
        return usesTransformedEnhancedDisplay() || shouldSplitWidePagesInSinglePageMode()
    }

    private fun shouldSplitWidePagesInSinglePageMode(): Boolean {
        return !viewer.config.doublePages && viewer.config.autoSplitPages
    }

    private fun insertSplitPagesAfterDisplay(pages: Set<ReaderPage>) {
        if (!shouldSplitWidePagesInSinglePageMode()) return

        pages.forEach { targetPage ->
            if (targetPage !is InsertPage && !viewer.hasSplitPage(targetPage)) {
                onPageSplit(targetPage)
            }
        }
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
            noise = readerPreferences.realCuganNoiseLevel().get(),
            scale = readerPreferences.realCuganScale().get(),
            model = readerPreferences.realCuganModel().get(),
            maxWidth = readerPreferences.realCuganMaxSizeWidth().get(),
            maxHeight = readerPreferences.realCuganMaxSizeHeight().get(),
            skipMaxWidth = readerPreferences.realCuganSkipMaxSizeWidth().get(),
            skipMaxHeight = readerPreferences.realCuganSkipMaxSizeHeight().get(),
            tileSize = readerPreferences.realCuganTileSize().get(),
            precision = readerPreferences.realCuganPrecision().get(),
            fp16Arithmetic = readerPreferences.realCuganFp16Arithmetic().get(),
            processingBackend = readerPreferences.realCuganProcessingBackend().get(),
        )
        return ImageEnhancementCache.getCachedImage(mangaId, chapterId, targetPage.index, configHash, enhancementVariantFor(targetPage))
    }

    private fun currentEnhancementState(targetPage: ReaderPage): String {
        if (!readerPreferences.realCuganEnabled().get()) return "disabled"
        val mangaId = viewer.activity.viewModel.manga?.id ?: return "missing-manga"
        val chapterId = targetPage.chapter.chapter.id ?: return "missing-chapter"
        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(
            noise = readerPreferences.realCuganNoiseLevel().get(),
            scale = readerPreferences.realCuganScale().get(),
            model = readerPreferences.realCuganModel().get(),
            maxWidth = readerPreferences.realCuganMaxSizeWidth().get(),
            maxHeight = readerPreferences.realCuganMaxSizeHeight().get(),
            skipMaxWidth = readerPreferences.realCuganSkipMaxSizeWidth().get(),
            skipMaxHeight = readerPreferences.realCuganSkipMaxSizeHeight().get(),
            tileSize = readerPreferences.realCuganTileSize().get(),
            precision = readerPreferences.realCuganPrecision().get(),
            fp16Arithmetic = readerPreferences.realCuganFp16Arithmetic().get(),
            processingBackend = readerPreferences.realCuganProcessingBackend().get(),
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
                    mergeOrSplitPages(
                        pageSourceForCombinedDisplay(page, Buffer().readFrom(s1)),
                        pageSourceForCombinedDisplay(extraPage!!, Buffer().readFrom(s2)),
                    )
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
                        mergeOrSplitPages(
                            pageSourceForCombinedDisplay(page, Buffer().readFrom(s1)),
                            pageSourceForCombinedDisplay(secondaryPage, Buffer().readFrom(s2)),
                        )
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
            noise = readerPreferences.realCuganNoiseLevel().get(),
            scale = readerPreferences.realCuganScale().get(),
            model = readerPreferences.realCuganModel().get(),
            maxWidth = readerPreferences.realCuganMaxSizeWidth().get(),
            maxHeight = readerPreferences.realCuganMaxSizeHeight().get(),
            skipMaxWidth = readerPreferences.realCuganSkipMaxSizeWidth().get(),
            skipMaxHeight = readerPreferences.realCuganSkipMaxSizeHeight().get(),
            tileSize = readerPreferences.realCuganTileSize().get(),
            precision = readerPreferences.realCuganPrecision().get(),
            fp16Arithmetic = readerPreferences.realCuganFp16Arithmetic().get(),
            processingBackend = readerPreferences.realCuganProcessingBackend().get(),
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
        val fittedFirstBitmap = trimBitmapBorders(firstBitmap)
        val fittedSecondBitmap = trimBitmapBorders(secondBitmap)
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
        val bitmapPaint =
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
                isFilterBitmap = true
                isDither = true
            }

        result.applyCanvas {
            drawColor(background)

            fun drawFitted(bitmap: Bitmap, cellLeft: Int, alignToCenter: Boolean) {
                val horizontalInset = if (bitmap.width > 8) 1 else 0
                val sourceRect = Rect(horizontalInset, 0, bitmap.width - horizontalInset, bitmap.height)
                val scale = min(
                    cellWidth / sourceRect.width().toFloat(),
                    viewportHeight / sourceRect.height().toFloat(),
                )
                val destWidth = (sourceRect.width() * scale).toInt().coerceAtLeast(1)
                val destHeight = (sourceRect.height() * scale).toInt().coerceAtLeast(1)
                val left =
                    if (alignToCenter) {
                        cellLeft + (cellWidth - destWidth)
                    } else {
                        cellLeft
                    }
                val top = (viewportHeight - destHeight) / 2
                val dest = Rect(left, top, left + destWidth, top + destHeight)
                drawBitmap(bitmap, sourceRect, dest, bitmapPaint)
            }

            val leftCellStart = 0
            val rightCellStart = cellWidth + adjustedHingeGap
            if (isLTR) {
                drawFitted(fittedFirstBitmap, leftCellStart, alignToCenter = true)
                drawFitted(fittedSecondBitmap, rightCellStart, alignToCenter = false)
            } else {
                drawFitted(fittedSecondBitmap, leftCellStart, alignToCenter = true)
                drawFitted(fittedFirstBitmap, rightCellStart, alignToCenter = false)
            }
        }

        if (fittedFirstBitmap !== firstBitmap) {
            fittedFirstBitmap.recycle()
        }
        if (fittedSecondBitmap !== secondBitmap) {
            fittedSecondBitmap.recycle()
        }

        val output = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())
        return output
    }

    private fun trimBitmapBorders(bitmap: Bitmap): Bitmap {
        if (!viewer.config.imageCropBorders || bitmap.width <= 2 || bitmap.height <= 2) {
            return bitmap
        }

        val cropRect = detectContentRect(bitmap)
        if (
            cropRect.left <= 0 &&
            cropRect.top <= 0 &&
            cropRect.right >= bitmap.width &&
            cropRect.bottom >= bitmap.height
        ) {
            return bitmap
        }

        val croppedWidth = (cropRect.right - cropRect.left).coerceAtLeast(1)
        val croppedHeight = (cropRect.bottom - cropRect.top).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, croppedWidth, croppedHeight)
    }

    private fun detectContentRect(bitmap: Bitmap): Rect {
        val width = bitmap.width
        val height = bitmap.height

        fun colorsAreSimilar(first: Int, second: Int, tolerance: Int = 24): Boolean {
            return kotlin.math.abs(Color.red(first) - Color.red(second)) <= tolerance &&
                kotlin.math.abs(Color.green(first) - Color.green(second)) <= tolerance &&
                kotlin.math.abs(Color.blue(first) - Color.blue(second)) <= tolerance
        }

        val corners = listOf(
            bitmap.getPixel(0, 0),
            bitmap.getPixel(width - 1, 0),
            bitmap.getPixel(0, height - 1),
            bitmap.getPixel(width - 1, height - 1),
        )
        val backgroundColor =
            corners.maxByOrNull { candidate ->
                corners.count { colorsAreSimilar(candidate, it) }
            } ?: corners.first()

        val sampleStepY = max(1, height / 160)
        val sampleStepX = max(1, width / 160)
        val maxColumnMismatches = max(2, ((height / sampleStepY) * 0.08f).toInt())
        val maxRowMismatches = max(2, ((width / sampleStepX) * 0.08f).toInt())

        fun isBackgroundColumn(x: Int): Boolean {
            var mismatches = 0
            for (y in 0 until height step sampleStepY) {
                if (!colorsAreSimilar(bitmap.getPixel(x, y), backgroundColor)) {
                    mismatches++
                    if (mismatches > maxColumnMismatches) return false
                }
            }
            return true
        }

        fun isBackgroundRow(y: Int): Boolean {
            var mismatches = 0
            for (x in 0 until width step sampleStepX) {
                if (!colorsAreSimilar(bitmap.getPixel(x, y), backgroundColor)) {
                    mismatches++
                    if (mismatches > maxRowMismatches) return false
                }
            }
            return true
        }

        var left = 0
        while (left < width - 1 && isBackgroundColumn(left)) {
            left++
        }

        var right = width - 1
        while (right > left && isBackgroundColumn(right)) {
            right--
        }

        var top = 0
        while (top < height - 1 && isBackgroundRow(top)) {
            top++
        }

        var bottom = height - 1
        while (bottom > top && isBackgroundRow(bottom)) {
            bottom--
        }

        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun process(item: Any, imageSource: BufferedSource): BufferedSource {
        val page = if (item is Pair<*, *>) item.first as ReaderPage else item as ReaderPage
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (shouldSplitWidePagesInSinglePageMode() && isWideImage(imageSource)) {
            return splitInHalf(imageSource, page)
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
            viewer is R2LPagerViewer && targetPage is InsertPage -> ImageUtil.Side.RIGHT
            viewer is R2LPagerViewer && targetPage !is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && targetPage is InsertPage -> ImageUtil.Side.RIGHT
            viewer is L2RPagerViewer && targetPage !is InsertPage -> ImageUtil.Side.LEFT
            targetPage is InsertPage -> ImageUtil.Side.RIGHT
            targetPage !is InsertPage -> ImageUtil.Side.LEFT
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
            noise = readerPreferences.realCuganNoiseLevel().get(),
            scale = readerPreferences.realCuganScale().get(),
            model = readerPreferences.realCuganModel().get(),
            maxWidth = readerPreferences.realCuganMaxSizeWidth().get(),
            maxHeight = readerPreferences.realCuganMaxSizeHeight().get(),
            skipMaxWidth = readerPreferences.realCuganSkipMaxSizeWidth().get(),
            skipMaxHeight = readerPreferences.realCuganSkipMaxSizeHeight().get(),
            tileSize = readerPreferences.realCuganTileSize().get(),
            precision = readerPreferences.realCuganPrecision().get(),
            fp16Arithmetic = readerPreferences.realCuganFp16Arithmetic().get(),
            processingBackend = readerPreferences.realCuganProcessingBackend().get(),
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
