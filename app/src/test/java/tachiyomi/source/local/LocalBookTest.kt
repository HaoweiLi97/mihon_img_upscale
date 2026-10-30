package tachiyomi.source.local

import android.net.Uri
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.source.local.io.LocalBook

class LocalBookTest {
    private fun file(name: String, directory: Boolean = false) = mockk<UniFile> {
        every { this@mockk.name } returns name
        every { isDirectory } returns directory
    }

    @Test
    fun `browser filters metadata hidden entries and unsupported reader formats`() {
        val files = listOf(
            file("ComicInfo.xml"), file("notes.txt"), file("novel.pdf"), file(".hidden", true),
            file(".page.jpg"), file("Comic.CBZ"), file("comic.rar"), file("comic.CBR"),
            file("comic.7z"), file("comic.cb7"), file("comic.tar"), file("comic.cbt"),
            file("comic.ZIP"), file("comic.EPUB"), file("001.JPG"), file("002.JPEG"),
            file("003.avif"), file("004.jxl"), file("005.HEIC"), file("Series", true),
        )
        val result = LocalBook.visibleFiles(files)
        assertEquals(15, result.size)
        assertEquals("Series", result.first().name)
        assertFalse(result.any { it.name.orEmpty().startsWith('.') })
        assertFalse(result.any { it.name?.endsWith("pdf") == true })
    }

    @Test
    fun `folders are first and numbered names and pages use natural order`() {
        val files =
            listOf(file("10.jpg"), file("Series 10", true), file("2.JPG"), file("Series 2", true), file("1.png"))
        assertEquals(
            listOf("Series 2", "Series 10", "1.png", "2.JPG", "10.jpg"),
            LocalBook.visibleFiles(files).map { it.name },
        )
        assertEquals(listOf("1.png", "2.JPG", "10.jpg"), LocalBook.images(files).map { it.name })
    }

    @Test
    fun `book identity includes full uri so same names in different roots remain distinct`() {
        fun book(uri: String): String {
            val androidUri = mockk<Uri> { every { this@mockk.toString() } returns uri }
            val file = mockk<UniFile> { every { this@mockk.uri } returns androidUri }
            return LocalBook.url(file)
        }
        val first = book("content://provider/tree/rootA/document/rootA%2F中文.cbz")
        val second = book("content://provider/tree/rootB/document/rootB%2F中文.cbz")
        assertTrue(LocalBook.isBook(first))
        assertFalse(LocalBook.isBook("Series/Chapter 1"))
        assertFalse(first == second)
        assertEquals("content://provider/tree/rootA/document/rootA%2F中文.cbz", first.removePrefix(LocalBook.PREFIX))
    }
}
