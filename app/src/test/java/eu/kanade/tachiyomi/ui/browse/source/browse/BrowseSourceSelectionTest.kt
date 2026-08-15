package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrowseSourceSelectionTest {

    private val loadedIds = listOf(10L, 20L, 30L, 40L, 50L)

    @Test
    fun `first range selection selects current manga`() {
        val result = selectRange(emptySet(), loadedIds, anchorIndex = null, selectedIndex = 2)

        assertEquals(setOf(30L), result.selectedIds)
        assertEquals(2, result.anchorIndex)
    }

    @Test
    fun `range selection works forward`() {
        val result = selectRange(setOf(20L), loadedIds, anchorIndex = 1, selectedIndex = 4)

        assertEquals(setOf(20L, 30L, 40L, 50L), result.selectedIds)
        assertEquals(4, result.anchorIndex)
    }

    @Test
    fun `range selection works in reverse`() {
        val result = selectRange(setOf(50L), loadedIds, anchorIndex = 4, selectedIndex = 1)

        assertEquals(setOf(20L, 30L, 40L, 50L), result.selectedIds)
        assertEquals(1, result.anchorIndex)
    }

    @Test
    fun `range selection preserves existing selection`() {
        val result = selectRange(setOf(10L), loadedIds, anchorIndex = 2, selectedIndex = 4)

        assertEquals(setOf(10L, 30L, 40L, 50L), result.selectedIds)
    }

    @Test
    fun `invalid anchor falls back to current manga`() {
        val result = selectRange(emptySet(), loadedIds, anchorIndex = 9, selectedIndex = 3)

        assertEquals(setOf(40L), result.selectedIds)
        assertEquals(3, result.anchorIndex)
    }
}
