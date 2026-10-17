package eu.kanade.tachiyomi.ui.reader.viewer.pager

import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BookPageImageSourceTest {
    @Test
    fun `picture metadata survives image probes and source consumption`() {
        val pictures = listOf(BookPageBounds(5f, 10f, 50f, 100f))
        val source = BookPageImageSource(Buffer().writeUtf8("image bytes"), pictures)
        source.readByte()
        assertEquals(pictures, BookPageImageSource.pictureBounds(source))
    }

    @Test
    fun `equal image bytes do not share layout geometry between sources`() {
        val first = Buffer().writeUtf8("same image")
        val second = Buffer().writeUtf8("same image")
        BookPageImageSource(first, listOf(BookPageBounds(5f, 10f, 50f, 100f)))
        assertNull(BookPageImageSource.pictureBounds(second))
    }
}
