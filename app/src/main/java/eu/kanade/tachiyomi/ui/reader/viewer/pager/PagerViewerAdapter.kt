package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.model.*
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.max

/**
 * Pager adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 */
class PagerViewerAdapter(private val viewer: PagerViewer) : ViewPagerAdapter() {

    /**
     * Paired list of currently set items.
     */
    var joinedItems: MutableList<Pair<ReaderItem, ReaderItem?>> = mutableListOf()
        private set

    /** Single list of items */
    private var subItems: MutableList<ReaderItem> = mutableListOf()

    /**
     * Holds preprocessed items so they don't get removed when changing chapter
     */
    private var preprocessed: MutableMap<Int, InsertPage> = mutableMapOf()

    var nextTransition: ChapterTransition.Next? = null
        private set

    var currentChapter: ReaderChapter? = null

    var restoredPosition = false
        private set

    /**
     * Context that has been wrapped to use the correct theme values based on the
     * current app theme and reader background color
     */
    private var readerThemedContext = viewer.activity.createReaderThemeContext()


    /** Page used to start the shifted pages */
    var pageToShift: ReaderPage? = null

    /** Varibles used to check if config of the pages have changed */
    private var shifted = viewer.config.shiftDoublePage
    private var doubledUp = viewer.config.doublePages

    var forceTransition = false

    private val pageSpreadDetectionState = PageSpreadDetectionState<PageSpreadKey>()
    // Splitting a page changes the neighbour used by the preceding source page. ViewPager would
    // otherwise retain that holder because its displayed item is still the same page instance.
    private val pagesNeedingSpreadRefresh = Collections.newSetFromMap(IdentityHashMap<ReaderPage, Boolean>())

    data class PageSpreadCandidate(
        val first: ReaderPage,
        val second: ReaderPage,
    )

    private data class PageSpreadKey(
        val chapterId: Long,
        val firstIndex: Int,
        val firstIsInserted: Boolean,
        val secondIndex: Int,
        val secondIsInserted: Boolean,
    )

    private data class SplitPageKey(
        val chapterId: Long,
        val pageIndex: Int,
    )

    private val displayReaderPages: List<ReaderPage>
        get() = subItems.filterIsInstance<ReaderPage>().filterNot { it is InsertPage }

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions and inverting the pages if the viewer
     * has R2L direction.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean, anchorPage: ReaderPage? = null) {
        restoredPosition = false
        val newItems = mutableListOf<ReaderItem>()
        // Preference changes rebuild this list. Retain existing split halves while wide-page
        // splitting is still active so the current page does not temporarily lose its right half.
        val existingSplitPages = subItems.filterIsInstance<InsertPage>()
            .mapNotNull { splitPageKey(it.parent) }
            .toSet()

        // Forces chapter transition if there is missing chapters
        val prevHasMissingChapters = calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
        val nextHasMissingChapters = calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0

        this.forceTransition = forceTransition
        // Add previous chapter pages and transition.
        if (chapters.prevChapter != null) {
            // We only need to add the last few pages of the previous chapter, because it'll be
            // selected as the current chapter when one of those pages is selected.
            val prevPages = chapters.prevChapter.pages
            if (prevPages != null) {
                newItems.addAll(prevPages.takeLast(2))
            }
        }

        // Skip transition page if the chapter is loaded & current page is not a transition page
        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }

        // Add current chapter.
        val currPages = chapters.currChapter.pages
        if (currPages != null) {
            val pages = currPages.toMutableList()

            // Insert preprocessed pages into current page list
            preprocessed.keys.sortedDescending()
                .forEach { key ->
                    preprocessed[key]?.let { pages.add(key + 1, it) }
                }

            newItems.addAll(pages)
        }

        currentChapter = chapters.currChapter

        // Add next chapter transition and pages.
        nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter)
            .also {
                if (
                    nextHasMissingChapters ||
                    forceTransition ||
                    chapters.nextChapter?.state !is ReaderChapter.State.Loaded
                ) {
                    newItems.add(it)
                }
            }

        if (chapters.nextChapter != null) {
            // Add at most two pages, because this chapter will be selected before the user can
            // swap more pages.
            val nextPages = chapters.nextChapter.pages
            if (nextPages != null) {
                newItems.addAll(nextPages.take(2))
            }
        }

        subItems = newItems
        preprocessed = mutableMapOf()
        restoreSplitPages(existingSplitPages)
        
        var useSecondPage = false
        val enteringDoublePages = !doubledUp && viewer.config.doublePages
        if (shifted != viewer.config.shiftDoublePage || (doubledUp != viewer.config.doublePages && doubledUp)) {
            if (shifted && (doubledUp == viewer.config.doublePages)) {
                useSecondPage = true
            }
            shifted = viewer.config.shiftDoublePage
        }
        doubledUp = viewer.config.doublePages
        restoredPosition = setJoinedItems(
            useSecondPage = useSecondPage,
            preferCurrentAsFirstPage = enteringDoublePages,
            anchorPage = anchorPage,
        )
    }

    private fun setJoinedItems(
        useSecondPage: Boolean = false,
        preferCurrentAsFirstPage: Boolean = false,
        anchorPage: ReaderPage? = null,
    ): Boolean {
        val oldCurrent = joinedItems.getOrNull(viewer.pager.currentItem)
        if (!viewer.config.doublePages) {
            // If not in double mode, set up items like before
            subItems.forEach {
                (it as? ReaderPage)?.apply {
                    shiftedPage = false
                    firstHalf = null
                    endPageConfidence = null
                    startPageConfidence = null
                }
            }
            if (viewer.config.autoDetectPageSpreads) {
                this.joinedItems = joinDetectedPageSpreads()
            } else {
                this.joinedItems = subItems.map { it to null }.toMutableList()
            }
            if (viewer is R2LPagerViewer) {
                joinedItems.reverse()
            }
        } else {
            val pagedItems = mutableListOf<MutableList<ReaderPage?>>()
            val otherItems = mutableListOf<ReaderItem>()
            pagedItems.add(mutableListOf())
            // Step 1: segment the pages and transition pages
            subItems.forEach {
                if (it is ReaderPage) {
                    if (pagedItems.last().lastOrNull() != null &&
                        pagedItems.last().last()?.chapter?.chapter?.id != it.chapter.chapter.id
                    ) {
                        pagedItems.add(mutableListOf())
                    }
                    pagedItems.last().add(it)
                } else {
                    otherItems.add(it)
                    pagedItems.add(mutableListOf())
                }
            }
            var pagedIndex = 0
            val subJoinedItems = mutableListOf<Pair<ReaderItem, ReaderItem?>>()
            // Step 2: run through each set of pages
            pagedItems.forEach { items ->

                items.forEach {
                    it?.shiftedPage = false
                    it?.firstHalf = null
                }
                // Step 3: If pages have been shifted,
                if (viewer.config.doublePages && viewer.config.shiftDoublePage) {
                    run loop@{
                        var index = items.indexOf(pageToShift)
                        if (pageToShift?.fullPage == true) {
                            index = max(0, index - 1)
                        }
                        // Go from the current page and work your way back to the first page,
                        // or the first page that's a full page.
                        // This is done in case user tries to shift a page after a full page
                        val fullPageBeforeIndex = max(
                            0,
                            if (index > -1) {
                                items.take(index).indexOfLast { it?.fullPage == true }
                            } else {
                                -1
                            },
                        )
                        // Add a shifted page to the first place there isnt a full page
                        (fullPageBeforeIndex until items.size).forEach {
                            if (items[it]?.fullPage != true) {
                                items[it]?.shiftedPage = true
                                return@loop
                            }
                        }
                    }
                }

                // Step 4: Add blanks for chunking
                var itemIndex = 0
                while (itemIndex < items.size) {
                    items[itemIndex]?.isolatedPage = false
                    if (
                        items[itemIndex]?.fullPage == true ||
                        items[itemIndex]?.shiftedPage == true ||
                        items[itemIndex] is InsertPage
                    ) {
                        // Add a 'blank' page after each full page. It will be used when chunked to solo a page
                        items.add(itemIndex + 1, null)
                        if (items[itemIndex]?.fullPage == true && itemIndex > 0 &&
                            items[itemIndex - 1] != null && (itemIndex - 1) % 2 == 0
                        ) {
                            // If a page is a full page, check if the previous page needs to be isolated
                            // we should check if it's an even or odd page, since even pages need shifting
                            // For example if Page 1 is full, Page 0 needs to be isolated
                            items[itemIndex - 1]?.isolatedPage = true
                            items.add(itemIndex, null)
                            itemIndex++
                        }
                        itemIndex++
                    }
                    itemIndex++
                }

                // Step 5: chunk em
                if (items.isNotEmpty()) {
                    subJoinedItems.addAll(
                        items.chunked(2).map { it.first()!! to it.getOrNull(1) },
                    )
                }
                otherItems.getOrNull(pagedIndex)?.let {
                    val lastPage = subJoinedItems.lastOrNull()?.first as? ReaderPage
                    if (lastPage == null || (
                        if (it is ChapterTransition.Next) {
                            it.from.chapter.id == lastPage.chapter.chapter.id
                        } else {
                            true
                        }
                        )
                    ) {
                        subJoinedItems.add(it to null)
                        pagedIndex++
                    }
                }
            }
            if (viewer is R2LPagerViewer) {
                subJoinedItems.reverse()
            }

            this.joinedItems = subJoinedItems
        }
        notifyDataSetChanged()

        val requestedPage = currentChapter?.pages
            ?.getOrNull(currentChapter?.requestedPage ?: -1)
            ?.let(::findReaderPage)

        val desiredPage = findReaderPage(anchorPage)
            ?: requestedPage
            ?: resolveFallbackPage(oldCurrent, useSecondPage)

        val desiredPosition = when {
            desiredPage != null -> getPositionForPage(desiredPage, preferCurrentAsFirstPage)
            oldCurrent?.first is ChapterTransition -> getPositionForTransition(oldCurrent.first as ChapterTransition)
            else -> -1
        }

        logcat {
            "PagerViewerAdapter.setJoinedItems: doublePages=${viewer.config.doublePages}, " +
                "requested=${requestedPage?.number}/${requestedPage?.index}, " +
                "anchor=${anchorPage?.number}/${anchorPage?.index}, " +
                "desired=${desiredPage?.number}/${desiredPage?.index}, " +
                "desiredPosition=$desiredPosition, currentItem=${viewer.pager.currentItem}"
        }

        if (desiredPosition > -1) {
            viewer.setPendingSelectedPage(desiredPage)
            viewer.pager.setCurrentItem(desiredPosition, false)
            return true
        }

        return false
    }

    private fun resolveFallbackPage(
        oldCurrent: Pair<ReaderItem, ReaderItem?>?,
        useSecondPage: Boolean,
    ): ReaderPage? {
        val oldPages = listOfNotNull(oldCurrent?.first as? ReaderPage, oldCurrent?.second as? ReaderPage)
        val preferredPage = when {
            useSecondPage -> oldPages.lastOrNull()
            else -> oldPages.maxByOrNull { it.index }
        }
        return findReaderPage(preferredPage)
    }

    private fun findReaderPage(page: ReaderPage?): ReaderPage? {
        page ?: return null
        if (page is InsertPage) return page
        return displayReaderPages.firstOrNull { candidate -> candidate.isFromSamePage(page) }
    }

    fun getPositionForPage(page: ReaderPage, preferCurrentAsFirstPage: Boolean = false): Int {
        if (page is InsertPage) {
            return joinedItems.indexOfFirst { pair ->
                pair.first === page || pair.second === page
            }
        }
        var index = if (preferCurrentAsFirstPage) {
            joinedItems.indexOfFirst {
                val firstPage = it.first as? ReaderPage
                firstPage !is InsertPage && firstPage?.isFromSamePage(page) == true
            }
        } else {
            -1
        }

        if (index == -1) {
            index = joinedItems.indexOfFirst { pair ->
                visibleReaderPages(pair).any { it.isFromSamePage(page) }
            }
        }

        return index
    }

    private fun getPositionForTransition(transition: ChapterTransition): Int {
        var index = joinedItems.indexOfFirst { it.first == transition }
        if (index != -1 || forceTransition) {
            return index
        }

        val fallbackPage = if (transition is ChapterTransition.Next) {
            joinedItems.asSequence()
                .flatMap { visibleReaderPages(it).asSequence() }
                .filter { it.chapter == transition.to }
                .minByOrNull { it.index }
        } else {
            joinedItems.asSequence()
                .flatMap { visibleReaderPages(it).asSequence() }
                .filter { it.chapter == transition.to }
                .maxByOrNull { it.index }
        }

        return fallbackPage?.let(::getPositionForPage) ?: -1
    }

    private fun visibleReaderPages(item: Pair<ReaderItem, ReaderItem?>): List<ReaderPage> {
        return listOfNotNull(item.first as? ReaderPage, item.second as? ReaderPage)
            .filterNot { it is InsertPage }
    }

    fun getActiveReaderPage(position: Int): ReaderPage? {
        return joinedItems.getOrNull(position)
            ?.let(::visibleReaderPages)
            ?.let { pages ->
                // The pair keeps source order even for R2L (the renderer swaps the
                // halves). In R2L the page on the right is read first, so it is the
                // active page for progress, restore and chapter preloading.
                if (viewer is R2LPagerViewer) pages.lastOrNull() else pages.firstOrNull()
            }
    }

    fun getPageSpreadCandidate(page: ReaderPage): PageSpreadCandidate? {
        if (
            !viewer.config.autoDetectPageSpreads ||
            viewer.config.doublePages
        ) {
            logcat(LogPriority.INFO) {
                "PageSpread: skip page ${page.index}; auto=${viewer.config.autoDetectPageSpreads}, double=${viewer.config.doublePages}"
            }
            return null
        }

        // InsertPage has the same source index as its parent. Use identity so each half of a
        // split wide image can be compared with its actual neighbouring half.
        val pageIndex = subItems.indexOfFirst { it === page }
        if (pageIndex == -1) return null

        val adjacentIndex = pageIndex + 1
        val adjacentPage = subItems.getOrNull(adjacentIndex) as? ReaderPage ?: return null
        if (adjacentPage.chapter != page.chapter) return null

        val first = page
        val second = adjacentPage
        val key = pageSpreadKey(first, second) ?: return null
        if (!pageSpreadDetectionState.begin(key)) {
            logcat(LogPriority.INFO) { "PageSpread: page pair ${first.index}/${second.index} already checked or pending" }
            return null
        }

        return PageSpreadCandidate(first, second)
    }

    fun onPageSpreadDeferred(candidate: PageSpreadCandidate) {
        pageSpreadKey(candidate.first, candidate.second)?.let(pageSpreadDetectionState::defer)
    }

    fun hasSplitPage(page: ReaderPage): Boolean {
        return page is InsertPage || subItems.any { item ->
            item is InsertPage && item.parent === page
        }
    }

    fun resetPageSpreadDetection() {
        pageSpreadDetectionState.reset()
    }

    fun onPageSpreadChecked(candidate: PageSpreadCandidate, isSpread: Boolean) {
        val key = pageSpreadKey(candidate.first, candidate.second) ?: return
        if (pageSpreadDetectionState.complete(key, isSpread)) {
            logcat(LogPriority.INFO) { "PageSpread: joining pages ${candidate.first.index}/${candidate.second.index}" }
            restoredPosition = setJoinedItems(anchorPage = viewer.getCurrentReaderPage() ?: candidate.first)
        }
    }

    private fun joinDetectedPageSpreads(): MutableList<Pair<ReaderItem, ReaderItem?>> {
        val items = mutableListOf<Pair<ReaderItem, ReaderItem?>>()
        var index = 0
        while (index < subItems.size) {
            val first = subItems[index]
            val second = subItems.getOrNull(index + 1)
            val key = pageSpreadKey(first as? ReaderPage, second as? ReaderPage)
            if (key != null && pageSpreadDetectionState.isDetected(key)) {
                items.add(first to second)
                index += 2
            } else {
                items.add(first to null)
                index++
            }
        }
        return items
    }

    private fun pageSpreadKey(first: ReaderPage?, second: ReaderPage?): PageSpreadKey? {
        if (first == null || second == null) return null
        // The two halves of one source page are already a split image, not an auto-detected
        // spread. Only compare across adjacent source pages.
        if (first.isFromSamePage(second)) return null
        if (first.chapter != second.chapter || second.index != first.index + 1) return null
        val chapterId = first.chapter.chapter.id ?: return null
        return PageSpreadKey(
            chapterId = chapterId,
            firstIndex = first.index,
            firstIsInserted = first is InsertPage,
            secondIndex = second.index,
            secondIsInserted = second is InsertPage,
        )
    }

    private fun splitPageKey(page: ReaderPage?): SplitPageKey? {
        page ?: return null
        val chapterId = page.chapter.chapter.id ?: return null
        return SplitPageKey(chapterId, page.index)
    }

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getCount(): Int {
        return joinedItems.size
    }

    /**
     * Creates a new view for the item at the given [position].
     */
    override fun createView(container: ViewGroup, position: Int): View {
        val (item, extraPage) = joinedItems[position]
        return when (item) {
            is ReaderPage -> PagerPageHolder(readerThemedContext, viewer, item, extraPage as? ReaderPage)
            is ChapterTransition -> PagerTransitionHolder(readerThemedContext, viewer, item)
            else -> throw NotImplementedError("Holder for ${item.javaClass} not implemented")
        }
    }

    /**
     * Returns the current position of the given [view] on the adapter.
     */
    override fun getItemPosition(view: Any): Int {
        if (view is PositionableView) {
            if (view is PagerPageHolder && pagesNeedingSpreadRefresh.remove(view.page)) {
                logcat(LogPriority.INFO) { "PageSpread: recreating page ${view.page.index} after a neighbour split" }
                return POSITION_NONE
            }
            val position = joinedItems.indexOfFirst { it.first == view.item || (it.first to it.second) == view.item }
            if (position != -1) {
                return position
            }
        }
        return POSITION_NONE
    }

    fun onPageSplit(currentPage: Any?, newPage: InsertPage) {
        if (currentPage !is ReaderPage) return

        val currentIndex = subItems.indexOf(currentPage)
        if (currentIndex == -1) return

        // The earlier page's old holder has already deferred its candidate because this page was
        // wide. Recreate it after insertion so it reads the correct new half. In R2L the original
        // source page also acquires a new following neighbour after the inserted first half.
        (subItems.getOrNull(currentIndex - 1) as? ReaderPage)?.let(pagesNeedingSpreadRefresh::add)
        if (viewer is R2LPagerViewer) {
            pagesNeedingSpreadRefresh.add(currentPage)
        }

        // Put aside preprocessed pages for next chapter so they don't get removed when changing chapter
        if (currentPage.chapter.chapter.id != currentChapter?.chapter?.id) {
            preprocessed[newPage.index] = newPage
            return
        }

        val placeAtIndex = when (viewer) {
            is L2RPagerViewer,
            is VerticalPagerViewer,
            -> currentIndex + 1
            else -> currentIndex
        }

        // It will enter a endless cycle of insert pages
        if (viewer is R2LPagerViewer && placeAtIndex - 1 >= 0 && subItems[placeAtIndex - 1] is InsertPage) {
            return
        }

        // Same here it will enter a endless cycle of insert pages
        if (placeAtIndex < subItems.size && subItems[placeAtIndex] is InsertPage) {
            return
        }

        val currentReaderPage = viewer.getCurrentReaderPage()
        subItems.add(placeAtIndex, newPage)

        // In R2L the inserted half is the right half and is the first page the reader should
        // remain on. The parent page represents the left half, so using it as the anchor would
        // make a split jump backwards after the adapter is rebuilt.
        val anchorPage = if (
            viewer is R2LPagerViewer &&
                currentReaderPage?.isFromSamePage(currentPage) == true
        ) {
            newPage
        } else {
            currentReaderPage
        }
        restoredPosition = setJoinedItems(anchorPage = anchorPage)
    }

    private fun restoreSplitPages(splitPages: Set<SplitPageKey>) {
        if (!shouldKeepSplitPages()) return

        splitPages.forEach { key ->
            val parentIndex = subItems.indexOfFirst { item ->
                val page = item as? ReaderPage
                page !is InsertPage && splitPageKey(page) == key
            }
            if (parentIndex == -1) return@forEach

            val parent = subItems[parentIndex] as ReaderPage
            if (subItems.any { item -> item is InsertPage && item.parent === parent }) return@forEach

            val insertIndex = when (viewer) {
                is L2RPagerViewer,
                is VerticalPagerViewer,
                -> parentIndex + 1
                else -> parentIndex
            }
            subItems.add(insertIndex, InsertPage(parent))
        }
    }

    private fun shouldKeepSplitPages(): Boolean {
        return !viewer.config.doublePages && viewer.config.autoSplitPages
    }

    fun onWidePageDetected(page: ReaderPage) {
        if (page.fullPage == true) return
        page.fullPage = true
        restoredPosition = setJoinedItems(anchorPage = viewer.getCurrentReaderPage() ?: page)
    }

    fun cleanupPageSplit() {
        val insertPages = subItems.filterIsInstance(InsertPage::class.java)
        subItems.removeAll(insertPages)
        subItems.filterIsInstance<ReaderPage>().forEach {
            it.fullPage = null
            it.isolatedPage = false
            it.shiftedPage = false
            it.firstHalf = null
        }
        restoredPosition = setJoinedItems(anchorPage = viewer.getCurrentReaderPage())
    }

    fun getDisplayPage(index: Int): ReaderPage? {
        return displayReaderPages.getOrNull(index)
    }

    fun getDisplayPageCount(): Int {
        return displayReaderPages.size
    }

    fun getDisplayPageNumber(page: ReaderPage): Int {
        return displayReaderPages.indexOfFirst { item ->
            item === page || item.isFromSamePage(page)
        }.let { if (it >= 0) it + 1 else page.number }
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }
}
