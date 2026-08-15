package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class BrowseSourcePaginationTest {

    @Test
    fun `tracking source reports refresh and prepended source pages`() = runTest {
        val loadedMangaPages = mutableListOf<Pair<Long, Int>>()
        val pagingSource = PageTrackingPagingSource(
            delegate = FakeSourcePagingSource(),
            onPageLoaded = { page, mangas ->
                loadedMangaPages += mangas.map { it.id to page }
            },
        )

        pagingSource.load(PagingSource.LoadParams.Refresh(19L, 50, false))
        pagingSource.load(PagingSource.LoadParams.Prepend(18L, 50, false))

        assertEquals(listOf(19L to 19, 18L to 18), loadedMangaPages)
    }

    private class FakeSourcePagingSource : PagingSource<Long, Manga>() {
        override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
            val page = params.key ?: 1
            return LoadResult.Page(
                data = listOf(Manga.create().copy(id = page)),
                prevKey = page - 1,
                nextKey = page + 1,
            )
        }

        override fun getRefreshKey(state: PagingState<Long, Manga>): Long? = null
    }
}
