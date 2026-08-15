package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaListItem
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceList(
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    contentPadding: PaddingValues,
    selectedMangaIds: Set<Long>,
    onMangaClick: (Int, Manga) -> Unit,
    onMangaLongClick: (Int, Manga) -> Unit,
    onPreviousPageLoaded: () -> Unit,
    onVisibleMangaIndexChanged: (Int) -> Unit,
) {
    val lazyListState = rememberLazyListState()

    LaunchedEffect(lazyListState, mangaList) {
        var prependWasLoading = false
        var anchorMangaId: Long? = null
        var anchorScrollOffset = 0
        snapshotFlow { mangaList.loadState.prepend to lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { (prependState, index) ->
                val prependStarted = !prependWasLoading && prependState is LoadState.Loading
                val prependCompleted = prependWasLoading && prependState is LoadState.NotLoading
                if (prependStarted) {
                    anchorMangaId = mangaList.itemSnapshotList.items.getOrNull(index)?.value?.id
                    anchorScrollOffset = lazyListState.firstVisibleItemScrollOffset
                }
                if (prependCompleted) {
                    onPreviousPageLoaded()
                }
                prependWasLoading = prependState is LoadState.Loading

                val restoredIndex = if (prependCompleted) {
                    anchorMangaId?.let { anchorId ->
                        mangaList.itemSnapshotList.items.indexOfFirst { it.value.id == anchorId }
                            .takeIf { it >= 0 }
                    }
                } else {
                    null
                }
                if (restoredIndex != null) {
                    lazyListState.scrollToItem(restoredIndex, anchorScrollOffset)
                    onVisibleMangaIndexChanged(restoredIndex)
                    anchorMangaId = null
                } else if (!prependCompleted) {
                    onVisibleMangaIndexChanged(index)
                }
            }
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        items(
            count = mangaList.itemCount,
            key = mangaList.itemKey { it.value.id },
        ) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            BrowseSourceListItem(
                manga = manga,
                selected = manga.id in selectedMangaIds,
                onClick = { onMangaClick(index, manga) },
                onLongClick = { onMangaLongClick(index, manga) },
            )
        }

        item(key = "browse_source_append") {
            if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceListItem(
    manga: Manga,
    selected: Boolean,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    MangaListItem(
        title = manga.title,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        isSelected = selected,
        badge = {
            InLibraryBadge(enabled = manga.favorite)
        },
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
