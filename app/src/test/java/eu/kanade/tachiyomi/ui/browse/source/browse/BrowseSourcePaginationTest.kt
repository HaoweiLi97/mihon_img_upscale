package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrowseSourcePaginationTest {

    @Test
    fun `page changes after fifty visible manga`() {
        assertEquals(1, calculateBrowsePage(firstLoadedPage = 1, visibleMangaIndex = 0))
        assertEquals(1, calculateBrowsePage(firstLoadedPage = 1, visibleMangaIndex = 49))
        assertEquals(2, calculateBrowsePage(firstLoadedPage = 1, visibleMangaIndex = 50))
    }

    @Test
    fun `prepended page maps back from a direct jump`() {
        assertEquals(9, calculateBrowsePage(firstLoadedPage = 9, visibleMangaIndex = 0))
        assertEquals(10, calculateBrowsePage(firstLoadedPage = 9, visibleMangaIndex = 50))
    }
}
