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
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    onPreviousPageLoaded: () -> Unit,
    onVisibleMangaIndexChanged: (Int) -> Unit,
) {
    val lazyGridState = rememberLazyGridState()

    LaunchedEffect(lazyGridState, mangaList) {
        var prependWasLoading = false
        snapshotFlow { mangaList.loadState.prepend to lazyGridState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { (prependState, index) ->
                if (prependWasLoading && prependState is LoadState.NotLoading) {
                    onPreviousPageLoaded()
                }
                prependWasLoading = prependState is LoadState.Loading

                val prependOffset = if (prependState is LoadState.Loading) 1 else 0
                onVisibleMangaIndexChanged((index - prependOffset).coerceAtLeast(0))
            }
    }

    LazyVerticalGrid(
        state = lazyGridState,
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (mangaList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            BrowseSourceComfortableGridItem(
                manga = manga,
                selected = manga.id in selectedMangaIds,
                onClick = { onMangaClick(index, manga) },
                onLongClick = { onMangaLongClick(index, manga) },
            )
        }

        if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
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
