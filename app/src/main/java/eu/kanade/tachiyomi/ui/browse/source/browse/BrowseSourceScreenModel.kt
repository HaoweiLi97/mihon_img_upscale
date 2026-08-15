package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

class BrowseSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    sourceManager: SourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
) : StateScreenModel<BrowseSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode().asState(screenModelScope)

    val source = sourceManager.getOrStub(sourceId)
    val isCloudSyncAvailable: Boolean
        get() = source is HttpSource && downloadManager.isCloudSyncAvailable()

    init {
        if (source is CatalogueSource) {
            mutableState.update {
                var query: String? = null
                var listing = it.listing

                if (listing is Listing.Search) {
                    query = listing.query
                    listing = Listing.Search(query, source.getFilterList())
                }

                it.copy(
                    listing = listing,
                    filters = source.getFilterList(),
                    toolbarQuery = query,
                )
            }
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource().set(source.id)
        }
    }

    /**
     * Flow of Pager flow tied to the current listing and requested source page.
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems().get()
    val mangaPagerFlowFlow = state.map {
        MangaPageRequest(it.listing, it.browseStartPage, it.pageRequestId)
    }
        .distinctUntilChanged()
        .map { request ->
            Pager(
                config = PagingConfig(
                    pageSize = SOURCE_PAGE_SIZE,
                    prefetchDistance = 5,
                    enablePlaceholders = false,
                ),
                initialKey = request.page.toLong(),
            ) {
                getRemoteManga(sourceId, request.listing.query ?: "", request.listing.filters)
            }.flow.map { pagingData ->
                pagingData.map { manga ->
                    getManga.subscribe(manga.url, manga.source)
                        .map { it ?: manga }
                        .stateIn(ioCoroutineScope)
                }
                    .filter { !hideInLibraryItems || !it.value.favorite }
            }
                .cachedIn(ioCoroutineScope)
        }
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, emptyFlow())

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns()
        } else {
            libraryPreferences.portraitColumns()
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    fun resetFilters() {
        if (source !is CatalogueSource) return

        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        selectionAnchor = null
        mutableState.update {
            it.copy(
                listing = listing,
                toolbarQuery = null,
                browsePage = 1,
                browseStartPage = 1,
                browseFirstLoadedPage = 1,
                selectionMode = false,
                selectedMangaIds = emptySet(),
            )
        }
    }

    fun setFilters(filters: FilterList) {
        if (source !is CatalogueSource) return

        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        if (source !is CatalogueSource) return

        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source.getFilterList())

        selectionAnchor = null
        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
                browsePage = 1,
                browseStartPage = 1,
                browseFirstLoadedPage = 1,
                selectionMode = false,
                selectedMangaIds = emptySet(),
            )
        }
    }

    fun searchGenre(genreName: String) {
        if (source !is CatalogueSource) return

        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        mutableState.update {
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
                browsePage = 1,
                browseStartPage = 1,
                browseFirstLoadedPage = 1,
                selectionMode = false,
                selectedMangaIds = emptySet(),
            )
        }
    }

    fun jumpToPage(page: Int) {
        if (page < 1) return

        selectionAnchor = null
        mutableState.update {
            it.copy(
                browsePage = page,
                browseStartPage = page,
                browseFirstLoadedPage = page,
                pageRequestId = it.pageRequestId + 1,
                selectionMode = false,
                selectedMangaIds = emptySet(),
            )
        }
    }

    fun onPreviousPageLoaded() {
        mutableState.update { state ->
            if (state.browseFirstLoadedPage <= 1) {
                state
            } else {
                state.copy(browseFirstLoadedPage = state.browseFirstLoadedPage - 1)
            }
        }
    }

    fun updateVisibleMangaIndex(index: Int) {
        if (index < 0) return

        mutableState.update { state ->
            val visiblePage = calculateBrowsePage(state.browseFirstLoadedPage, index)
            if (visiblePage == state.browsePage) state else state.copy(browsePage = visiblePage)
        }
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: Manga) {
        screenModelScope.launch {
            var new = manga.copy(
                favorite = !manga.favorite,
                dateAdded = when (manga.favorite) {
                    true -> 0
                    false -> Instant.now().toEpochMilli()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                setMangaDefaultChapterFlags.await(manga)
                addTracks.bindEnhancedTrackers(manga, source)
            }

            updateManga.await(new.toMangaUpdate())
        }
    }

    fun addFavorite(manga: Manga) {
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveMangaToCategories(manga, defaultCategory)

                    changeMangaFavorite(manga)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveMangaToCategories(manga)

                    changeMangaFavorite(manga)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                    setDialog(
                        Dialog.ChangeMangaCategory(
                            manga,
                            categories.mapAsCheckboxState { it.id in preselectedIds }.toImmutableList(),
                        ),
                    )
                }
            }
        }
    }

    fun addFavorites(mangas: List<Manga>, categoryIds: List<Long>? = null) {
        val candidates = mangas.filterNot { it.favorite }.distinctBy { it.id }
        if (candidates.isEmpty()) {
            clearSelection()
            return
        }

        screenModelScope.launch {
            if (categoryIds != null) {
                addFavoritesToCategories(candidates, categoryIds)
                clearSelection()
                return@launch
            }

            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }
            when {
                defaultCategory != null -> {
                    addFavoritesToCategories(candidates, listOf(defaultCategory.id))
                    clearSelection()
                }
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    addFavoritesToCategories(candidates, emptyList())
                    clearSelection()
                }
                else -> {
                    setDialog(
                        Dialog.ChangeMangaCategories(
                            mangas = candidates,
                            initialSelection = categories.mapAsCheckboxState { false }.toImmutableList(),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun addFavoritesToCategories(mangas: List<Manga>, categoryIds: List<Long>) {
        mangas.forEach { manga ->
            setMangaCategories.await(manga.id, categoryIds.filterNot { it == 0L })
            setMangaDefaultChapterFlags.await(manga)
            addTracks.bindEnhancedTrackers(manga, source)
            updateManga.await(
                manga.copy(
                    favorite = true,
                    dateAdded = Instant.now().toEpochMilli(),
                ).toMangaUpdate(),
            )
        }
    }

    suspend fun downloadMangas(mangas: List<Manga>): BatchDownloadResult = withIOContext {
        val httpSource = source as? HttpSource ?: return@withIOContext BatchDownloadResult(0, mangas.size)
        var successful = 0
        var failed = 0

        mangas.distinctBy { it.id }.forEach { manga ->
            try {
                val sourceChapters = httpSource.getChapterList(manga.toSManga())
                syncChaptersWithSource.await(sourceChapters, manga, httpSource, manualFetch = true)
                val chapters = getChaptersByMangaId.await(manga.id)
                downloadManager.downloadChapters(manga, chapters, autoStart = false)
                successful++
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                failed++
            }
        }

        if (successful > 0) downloadManager.startDownloads()
        BatchDownloadResult(successful, failed)
    }

    suspend fun cloudSyncMangas(mangas: List<Manga>): BatchCloudSyncResult = withIOContext {
        val httpSource = source as? HttpSource
            ?: return@withIOContext BatchCloudSyncResult(queued = 0, skipped = 0, failed = mangas.size)
        var downloadsQueued = 0
        var queued = 0
        var skipped = 0
        var failed = 0

        mangas.distinctBy { it.id }.forEach { manga ->
            try {
                val sourceChapters = httpSource.getChapterList(manga.toSManga())
                syncChaptersWithSource.await(sourceChapters, manga, httpSource, manualFetch = true)
                val chapters = getChaptersByMangaId.await(manga.id)
                val result = downloadManager.cloudSyncChapters(manga, chapters, autoStart = false)
                downloadsQueued += result.downloadsQueued
                queued += result.queued
                skipped += result.skipped
                failed += result.failed
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                failed++
            }
        }

        if (downloadsQueued > 0) downloadManager.startDownloads()
        BatchCloudSyncResult(
            queued = queued,
            skipped = skipped,
            failed = failed,
        )
    }

    private var selectionAnchor: Int? = null

    fun enterSelectionMode() {
        selectionAnchor = null
        mutableState.update { it.copy(selectionMode = true) }
    }

    fun clearSelection() {
        selectionAnchor = null
        mutableState.update { it.copy(selectionMode = false, selectedMangaIds = emptySet()) }
    }

    fun toggleSelection(index: Int, mangaId: Long) {
        selectionAnchor = index
        mutableState.update { state ->
            state.copy(
                selectedMangaIds = state.selectedMangaIds.toMutableSet().apply {
                    if (!remove(mangaId)) add(mangaId)
                },
            )
        }
    }

    fun selectRange(index: Int, loadedMangaIds: List<Long>) {
        val result = selectRange(
            selectedIds = state.value.selectedMangaIds,
            loadedIds = loadedMangaIds,
            anchorIndex = selectionAnchor,
            selectedIndex = index,
        )
        selectionAnchor = result.anchorIndex
        mutableState.update { it.copy(selectedMangaIds = result.selectedIds) }
    }

    fun selectAll(loadedMangaIds: List<Long>) {
        selectionAnchor = null
        mutableState.update { it.copy(selectedMangaIds = loadedMangaIds.toSet()) }
    }

    fun invertSelection(loadedMangaIds: List<Long>) {
        selectionAnchor = null
        mutableState.update { state ->
            state.copy(selectedMangaIds = loadedMangaIds.filterNot { it in state.selectedMangaIds }.toSet())
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): List<MangaWithChapterCount> {
        return getDuplicateLibraryManga.invoke(manga)
    }

    private fun moveMangaToCategories(manga: Manga, vararg categories: Category) {
        moveMangaToCategories(manga, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setMangaCategories.await(
                mangaId = manga.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog
        data class ChangeMangaCategories(
            val mangas: List<Manga>,
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
        val browsePage: Int = 1,
        val browseStartPage: Int = 1,
        val browseFirstLoadedPage: Int = 1,
        val pageRequestId: Int = 0,
        val selectionMode: Boolean = false,
        val selectedMangaIds: Set<Long> = emptySet(),
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }

    data class BatchDownloadResult(val successful: Int, val failed: Int)
    data class BatchCloudSyncResult(val queued: Int, val skipped: Int, val failed: Int)

    private data class MangaPageRequest(
        val listing: Listing,
        val page: Int,
        val requestId: Int,
    )
}

private const val SOURCE_PAGE_SIZE = 50

internal fun calculateBrowsePage(firstLoadedPage: Int, visibleMangaIndex: Int): Int {
    return firstLoadedPage + visibleMangaIndex.coerceAtLeast(0) / SOURCE_PAGE_SIZE
}

internal data class RangeSelectionResult(
    val selectedIds: Set<Long>,
    val anchorIndex: Int,
)

internal fun selectRange(
    selectedIds: Set<Long>,
    loadedIds: List<Long>,
    anchorIndex: Int?,
    selectedIndex: Int,
): RangeSelectionResult {
    if (selectedIndex !in loadedIds.indices) {
        return RangeSelectionResult(selectedIds, anchorIndex ?: selectedIndex)
    }
    val validAnchor = anchorIndex?.takeIf { it in loadedIds.indices } ?: selectedIndex
    val range = if (validAnchor <= selectedIndex) validAnchor..selectedIndex else selectedIndex..validAnchor
    return RangeSelectionResult(
        selectedIds = selectedIds + range.map(loadedIds::get),
        anchorIndex = selectedIndex,
    )
}
