package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class DownloadFileRenamerTest {

    @Test
    fun `returns source when provider supports rename`() {
        val source = mockk<UniFile>()
        val parent = mockk<UniFile>()
        every { source.renameTo("001.jpg") } returns true

        val result = finalizeDownloadFile(source, parent, "001.jpg")

        assertSame(source, result)
        verify(exactly = 0) { parent.createFile(any()) }
    }

    @Test
    fun `copies and removes temporary file when provider rejects rename`() {
        val content = "downloaded image".toByteArray()
        val output = ByteArrayOutputStream()
        val source = mockk<UniFile>()
        val parent = mockk<UniFile>()
        val target = mockk<UniFile>()
        every { source.renameTo("001.jpg") } returns false
        every { parent.findFile("001.jpg") } returns null
        every { parent.createFile("001.jpg") } returns target
        every { source.openInputStream() } returns ByteArrayInputStream(content)
        every { target.openOutputStream() } returns output
        every { source.delete() } returns true

        val result = finalizeDownloadFile(source, parent, "001.jpg")

        assertSame(target, result)
        assertArrayEquals(content, output.toByteArray())
        verifyOrder {
            source.renameTo("001.jpg")
            parent.findFile("001.jpg")
            parent.createFile("001.jpg")
            source.openInputStream()
            target.openOutputStream()
            source.delete()
        }
    }

    @Test
    fun `uses copy fallback when rename throws`() {
        val source = mockk<UniFile>()
        val parent = mockk<UniFile>()
        val target = mockk<UniFile>()
        every { source.renameTo("chapter.cbz") } throws UnsupportedOperationException()
        every { parent.findFile("chapter.cbz") } returns null
        every { parent.createFile("chapter.cbz") } returns target
        every { source.openInputStream() } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        every { target.openOutputStream() } returns ByteArrayOutputStream()
        every { source.delete() } returns true

        assertSame(target, finalizeDownloadFile(source, parent, "chapter.cbz"))
    }

    @Test
    fun `removes incomplete target and preserves source when copy fails`() {
        val source = mockk<UniFile>()
        val parent = mockk<UniFile>()
        val target = mockk<UniFile>()
        every { source.renameTo("001.jpg") } returns false
        every { parent.findFile("001.jpg") } returns null
        every { parent.createFile("001.jpg") } returns target
        every { source.openInputStream() } throws IOException("read failed")
        every { target.delete() } returns true

        assertThrows(IOException::class.java) {
            finalizeDownloadFile(source, parent, "001.jpg")
        }
        verify { target.delete() }
        verify(exactly = 0) { source.delete() }
    }
}
