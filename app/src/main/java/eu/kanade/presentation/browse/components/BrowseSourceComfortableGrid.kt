package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceComfortableGrid(
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    selectedMangaIds: Set<Long>,
    onMangaClick: (Int, Manga) -> Unit,
    onMangaLongClick: (Int, Manga) -> Unit,
    onVisibleMangaChanged: (Long) -> Unit,
) {
    val lazyGridState = rememberLazyGridState()

    LaunchedEffect(lazyGridState, mangaList) {
        var prependWasLoading = false
        var anchorMangaId: Long? = null
        var anchorScrollOffset = 0
        snapshotFlow { mangaList.loadState.prepend }
            .distinctUntilChanged()
            .collect { prependState ->
                val prependStarted = !prependWasLoading && prependState is LoadState.Loading
                val prependCompleted = prependWasLoading && prependState is LoadState.NotLoading
                if (prependStarted) {
                    anchorMangaId = lazyGridState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? Long
                    anchorScrollOffset = lazyGridState.firstVisibleItemScrollOffset
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
                    lazyGridState.scrollToItem(restoredIndex, anchorScrollOffset)
                    anchorMangaId = null
                }
            }
    }

    LaunchedEffect(lazyGridState) {
        snapshotFlow { lazyGridState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? Long }
            .filterNotNull()
            .distinctUntilChanged()
            .collect(onVisibleMangaChanged)
    }

    LazyVerticalGrid(
        state = lazyGridState,
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(
            count = mangaList.itemCount,
            key = mangaList.itemKey { it.value.id },
        ) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            BrowseSourceComfortableGridItem(
                manga = manga,
                selected = manga.id in selectedMangaIds,
                onClick = { onMangaClick(index, manga) },
                onLongClick = { onMangaLongClick(index, manga) },
            )
        }

        if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
            item(
                key = "browse_source_append",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceComfortableGridItem(
    manga: Manga,
    selected: Boolean,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    MangaComfortableGridItem(
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
        coverBadgeStart = {
            InLibraryBadge(enabled = manga.favorite)
        },
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
