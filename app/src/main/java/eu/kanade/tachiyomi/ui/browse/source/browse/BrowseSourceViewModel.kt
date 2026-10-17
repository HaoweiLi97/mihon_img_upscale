package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.asState
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

@AssistedInject
class BrowseSourceViewModel(
    @Assisted private val sourceId: Long,
    @Assisted listingQuery: String?,
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val sourceManager: SourceManager,
    sourcePreferences: SourcePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val coverCache: CoverCache,
    private val getRemoteManga: GetRemoteManga,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val getManga: GetManga,
    private val updateManga: UpdateManga,
    private val addTracks: AddTracks,
    private val downloadManager: DownloadManager,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateMangaFromRemote: mihon.domain.source.interactor.UpdateMangaFromRemote,
    getIncognitoState: GetIncognitoState,
) : ViewModel() {

    val state: StateFlow<BrowseSourceViewModel.State>
        field = MutableStateFlow<BrowseSourceViewModel.State>(State(Listing.valueOf(listingQuery)))

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: Long, listingQuery: String?): BrowseSourceViewModel
    }

    var displayMode by sourcePreferences.sourceDisplayMode.asState(viewModelScope)

    val source: Source? get() = state.value.source
    val isCloudSyncAvailable: Boolean
        get() = source is HttpSource && downloadManager.isCloudSyncAvailable()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val source = sourceManager.getOrStub(sourceId)

            state.update {
                var query: String? = null
                var listing = it.listing

                if (listing is Listing.Search) {
                    query = listing.query
                    listing = Listing.Search(query, source.getFilterList())
                }

                it.copy(
                    source = source,
                    listing = listing,
                    filters = source.getFilterList(),
                    toolbarQuery = query,
                )
            }

            if (!getIncognitoState.await(source.id)) {
                sourcePreferences.lastUsedSource.set(source.id)
            }
        }
    }

    /**
     * Flow of Pager flow tied to the current listing and requested source page.
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    private val pagingGeneration = AtomicLong()
    private val mangaSourcePages = ConcurrentHashMap<Long, SourcePage>()
    val mangaPagerFlowFlow = state.filter { it.source != null }.map {
        MangaPageRequest(it.listing, it.browseStartPage, it.pageRequestId)
    }
        .distinctUntilChanged()
        .map { request ->
            val generation = pagingGeneration.incrementAndGet()
            mangaSourcePages.clear()
            Pager(
                config = PagingConfig(
                    pageSize = SOURCE_PAGE_SIZE,
                    prefetchDistance = 5,
                    enablePlaceholders = false,
                ),
                initialKey = request.page.toLong(),
            ) {
                PageTrackingPagingSource(
                    delegate = getRemoteManga(sourceId, request.listing.query ?: "", request.listing.filters),
                    onPageLoaded = { page, mangas ->
                        if (pagingGeneration.get() == generation) {
                            mangas.forEach { manga ->
                                mangaSourcePages[manga.id] = SourcePage(generation, page)
                            }
                        }
                    },
                )
            }.flow.map { pagingData ->
                pagingData.map { manga ->
                    getManga.subscribe(manga.url, manga.source)
                        .map { it ?: manga }
                        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), manga)
                }
                    .filter { !hideInLibraryItems || !it.value.favorite }
            }
                .cachedIn(viewModelScope)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyFlow())

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    fun resetFilters() {
        val source = source ?: return
        state.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        selectionAnchor = null
        state.update {
            it.copy(
                listing = listing,
                toolbarQuery = null,
                browsePage = 1,
                browseStartPage = 1,
                selectionMode = false,
                selectedMangaIds = emptySet(),
            )
        }
    }

    fun setFilters(filters: FilterList) {
        state.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source?.getFilterList() ?: FilterList())

        selectionAnchor = null
        state.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
                browsePage = 1,
                browseStartPage = 1,
                selectionMode = false,
                selectedMangaIds = emptySet(),
            )
        }
    }

    fun searchGenre(genreName: String) {
        val defaultFilters = source?.getFilterList() ?: return
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

        state.update {
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
                selectionMode = false,
                selectedMangaIds = emptySet(),
            )
        }
    }

    fun jumpToPage(page: Int) {
        if (page < 1) return

        selectionAnchor = null
        state.update {
            it.copy(
                browsePage = page,
                browseStartPage = page,
                pageRequestId = it.pageRequestId + 1,
                selectionMode = false,
                selectedMangaIds = emptySet(),
            )
        }
    }

    fun updateVisibleManga(mangaId: Long) {
        val sourcePage = mangaSourcePages[mangaId]
            ?.takeIf { it.generation == pagingGeneration.get() }
            ?.page
            ?: return
        state.update { state ->
            if (sourcePage == state.browsePage) state else state.copy(browsePage = sourcePage)
        }
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: Manga) {
        appScope.launch {
            val update = if (manga.favorite) {
                val coverLastModified = manga.removeCovers(coverCache).coverLastModified
                MangaUpdate(manga.id) {
                    favoriteAt = null
                    if (coverLastModified != manga.coverLastModified) this.coverLastModified = coverLastModified
                }
            } else {
                setMangaDefaultChapterFlags.await(manga)
                addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))
                MangaUpdate(manga.id) { favoriteAt = Clock.System.now() }
            }

            val updated = updateManga.await(update)
            if (updated && !manga.favorite) initializeAddedManga(manga)
        }
    }

    fun addFavorite(manga: Manga) {
        viewModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
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
                            categories.mapAsCheckboxState { it.id in preselectedIds },
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

        viewModelScope.launch {
            if (categoryIds != null) {
                addFavoritesToCategories(candidates, categoryIds)
                return@launch
            }

            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }
            when {
                defaultCategory != null -> {
                    addFavoritesToCategories(candidates, listOf(defaultCategory.id))
                }
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    addFavoritesToCategories(candidates, emptyList())
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
        val addedManga = mutableListOf<Manga>()
        mangas.forEach { manga ->
            setMangaCategories.await(manga.id, categoryIds.filterNot { it == 0L })
            setMangaDefaultChapterFlags.await(manga)
            addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))
            if (updateManga.await(MangaUpdate(manga.id) { favoriteAt = Clock.System.now() })) {
                addedManga += manga
            }
        }

        clearSelection()
        addedManga.forEach { initializeAddedManga(it) }
    }

    private suspend fun initializeAddedManga(manga: Manga) {
        val latest = getManga.await(manga.id)?.takeIf { it.favorite } ?: return
        updateMangaFromRemote(
            source = sourceManager.getOrStub(latest.source),
            manga = latest,
            fetchDetails = true,
            fetchChapters = true,
        ).getOrThrow()
    }

    suspend fun downloadMangas(mangas: List<Manga>): BatchDownloadResult = withIOContext {
        val httpSource = source as? HttpSource ?: return@withIOContext BatchDownloadResult(0, mangas.size)
        var successful = 0
        var failed = 0

        mangas.distinctBy { it.id }.forEach { manga ->
            try {
                updateMangaFromRemote(source = httpSource, manga = manga, fetchDetails = false, fetchChapters = true)
                    .getOrThrow()
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
                updateMangaFromRemote(source = httpSource, manga = manga, fetchDetails = false, fetchChapters = true)
                    .getOrThrow()
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
        state.update { it.copy(selectionMode = true) }
    }

    fun clearSelection() {
        selectionAnchor = null
        state.update { it.copy(selectionMode = false, selectedMangaIds = emptySet()) }
    }

    fun toggleSelection(index: Int, mangaId: Long) {
        selectionAnchor = index
        state.update { state ->
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
        state.update { it.copy(selectedMangaIds = result.selectedIds) }
    }

    fun selectAll(loadedMangaIds: List<Long>) {
        selectionAnchor = null
        state.update { it.copy(selectedMangaIds = loadedMangaIds.toSet()) }
    }

    fun invertSelection(loadedMangaIds: List<Long>) {
        selectionAnchor = null
        state.update { state ->
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
        viewModelScope.launch(Dispatchers.IO) {
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
        state.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        state.update { it.copy(toolbarQuery = query) }
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
            val initialSelection: List<CheckboxState.State<Category>>,
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
        val source: Source? = null,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
        val browsePage: Int = 1,
        val browseStartPage: Int = 1,
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

internal class PageTrackingPagingSource(
    private val delegate: PagingSource<Long, Manga>,
    private val onPageLoaded: (Int, List<Manga>) -> Unit,
) : PagingSource<Long, Manga>() {

    init {
        delegate.registerInvalidatedCallback(::invalidate)
        registerInvalidatedCallback(delegate::invalidate)
    }

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
        return delegate.load(params).also { result ->
            if (result is LoadResult.Page) {
                val page = params.key
                    ?: result.prevKey?.plus(1)
                    ?: result.nextKey?.minus(1)
                    ?: 1
                onPageLoaded(page.toInt(), result.data)
            }
        }
    }

    override fun getRefreshKey(state: PagingState<Long, Manga>): Long? {
        return delegate.getRefreshKey(state)
    }
}

private data class SourcePage(val generation: Long, val page: Int)

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
