package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SplitPageAnchorTest {

    @Test
    fun `maps stale split anchor to recreated insert page`() {
        val oldParent = readerPage(index = 7, chapterId = 42)
        val newParent = readerPage(index = 7, chapterId = 42)
        val staleAnchor = InsertPage(oldParent)
        val currentInsertPage = InsertPage(newParent)

        val result = findCurrentInsertPage(listOf(newParent, currentInsertPage), staleAnchor)

        assertSame(currentInsertPage, result)
    }

    @Test
    fun `does not map split anchor to another source page`() {
        val anchor = InsertPage(readerPage(index = 7, chapterId = 42))
        val otherInsertPage = InsertPage(readerPage(index = 8, chapterId = 42))

        assertNull(findCurrentInsertPage(listOf(otherInsertPage), anchor))
    }

    @Test
    fun `does not treat parent and inserted half as the same displayed page`() {
        val parent = readerPage(index = 7, chapterId = 42)
        val inserted = InsertPage(parent)

        assertFalse(isSameDisplayedPage(parent, inserted))
        assertTrue(isSameDisplayedPage(inserted, InsertPage(readerPage(index = 7, chapterId = 42))))
    }

    private fun readerPage(index: Int, chapterId: Long): ReaderPage {
        val chapter = ChapterImpl().apply {
            id = chapterId
            url = "chapter-$chapterId"
            name = "Chapter $chapterId"
        }
        return ReaderPage(index).apply {
            this.chapter = ReaderChapter(chapter)
        }
    }
}
