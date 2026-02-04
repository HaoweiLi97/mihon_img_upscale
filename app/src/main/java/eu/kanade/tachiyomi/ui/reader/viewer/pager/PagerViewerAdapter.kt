package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.model.*
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import tachiyomi.core.common.util.system.logcat
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

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions and inverting the pages if the viewer
     * has R2L direction.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        val newItems = mutableListOf<ReaderItem>()

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
        
        var useSecondPage = false
        if (shifted != viewer.config.shiftDoublePage || (doubledUp != viewer.config.doublePages && doubledUp)) {
            if (shifted && (doubledUp == viewer.config.doublePages)) {
                useSecondPage = true
            }
            shifted = viewer.config.shiftDoublePage
        }
        doubledUp = viewer.config.doublePages
        setJoinedItems(useSecondPage)
    }

    private fun setJoinedItems(useSecondPage: Boolean = false) {
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
            if (viewer.config.splitPages) {
                val pagedItems = mutableListOf<ReaderItem>()
                subItems.forEach { page ->
                    // Mihon doesn't have longPage property yet, but we can check if it's wide
                    // For now, let's assume it should be split if it was already marked as split
                    // This logic is simplified compared to Yokai
                    pagedItems.add(page)
                }

                this.joinedItems = pagedItems.map {
                    it to (if ((it as? ReaderPage)?.fullPage == true && it.firstHalf == true) SplitPage else null)
                }.toMutableList()
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
                if (viewer.config.shiftDoublePage) {
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
                    if (items[itemIndex]?.fullPage == true || items[itemIndex]?.shiftedPage == true) {
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

        // Step 6: Move back to our previous page or transition page
        val newPage =
            when {
                (oldCurrent?.first as? ReaderPage)?.chapter != currentChapter &&
                    (oldCurrent?.first as? ChapterTransition)?.from != currentChapter -> subItems.find { (it as? ReaderPage)?.chapter == currentChapter }
                useSecondPage && oldCurrent?.second is ReaderPage -> (oldCurrent.second ?: oldCurrent.first)
                else -> oldCurrent?.first ?: return
            }
        var index = joinedItems.indexOfFirst {
            val readerPage = it.first as? ReaderPage
            val readerPage2 = it.second as? ReaderPage
            val newReaderPage = newPage as? ReaderPage
            it.first == newPage || it.second == newPage ||
                (
                    readerPage != null && newReaderPage != null &&
                        (
                            readerPage.isFromSamePage(newReaderPage) ||
                                readerPage2?.isFromSamePage(newReaderPage) == true
                            ) &&
                        (readerPage.firstHalf == !useSecondPage || readerPage.firstHalf == null)
                    )
        }
        if (newPage is ChapterTransition && index == -1 && !forceTransition) {
            val newerPage = if (newPage is ChapterTransition.Next) {
                joinedItems.filter {
                    (it.first as? ReaderPage)?.chapter == newPage.to
                }.minByOrNull { (it.first as? ReaderPage)?.index ?: Int.MAX_VALUE }?.first
            } else {
                joinedItems.filter {
                    (it.first as? ReaderPage)?.chapter == newPage.to
                }.maxByOrNull { (it.first as? ReaderPage)?.index ?: Int.MIN_VALUE }?.first
            }
            index = joinedItems.indexOfFirst { it.first == newerPage || it.second == newerPage }
        }
        if (index > -1) {
            viewer.pager.setCurrentItem(index, false)
        }
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
            val position = joinedItems.indexOfFirst { it.first == view.item || (it.first to it.second) == view.item }
            if (position != -1) {
                return position
            } else {
                logcat { "Position for ${view.item} not found" }
            }
        }
        return POSITION_NONE
    }

    fun onPageSplit(currentPage: Any?, newPage: InsertPage) {
        if (currentPage !is ReaderPage) return

        val currentIndex = subItems.indexOf(currentPage)
        if (currentIndex == -1) return

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

        subItems.add(placeAtIndex, newPage)
        setJoinedItems()
    }

    fun cleanupPageSplit() {
        val insertPages = subItems.filterIsInstance(InsertPage::class.java)
        subItems.removeAll(insertPages)
        setJoinedItems()
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }
}
