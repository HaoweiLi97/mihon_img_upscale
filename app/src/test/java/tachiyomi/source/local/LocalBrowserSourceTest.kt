package tachiyomi.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.chapter.ChapterSanitizer.sanitize
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem

class LocalBrowserSourceTest {
    private val context = mockk<Context>()
    private val fileSystem = mockk<LocalSourceFileSystem>()
    private val coverManager = mockk<LocalCoverManager>()

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        every { context.stringResource(any()) } returns "Local"
    }

    @AfterEach
    fun tearDown() = unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")

    @Test
    fun `a nested image folder is one chapter and resolves directly to directory reader`() = runTest {
        val url = "local-file:content://provider/tree/books/document/books%2FSeries%2FChapter%201"
        val file = mockk<UniFile> {
            every { isDirectory } returns true
            every { name } returns "Chapter 1"
            every { lastModified() } returns 42L
        }
        every { fileSystem.resolveBook(url) } returns file
        val manga = SManga.create().apply {
            this.url = url
            title = "Chapter 1"
        }
        val source = LocalSource(context, fileSystem, coverManager)
        val chapter = source.getChapterList(manga).single()
        assertEquals(url, chapter.url)
        assertEquals("Local", chapter.name.sanitize(manga.title))
        assertEquals(42L, chapter.date_upload)
        assertSame(file, (source.getFormat(chapter) as Format.Directory).file)
        verify(exactly = 0) { fileSystem.getFilesInMangaDirectory(any()) }
    }

    @Test
    fun `standalone root archives and epub resolve without a two level manga layout`() = runTest {
        for (extension in listOf("CBZ", "ZIP", "rar", "cbr", "7z", "cb7", "tar", "cbt", "EPUB")) {
            val url = "local-file:file:///books/漫画.$extension"
            val file = mockk<UniFile> {
                every { isDirectory } returns false
                every { name } returns "漫画.$extension"
                every { lastModified() } returns 1L
            }
            every { fileSystem.resolveBook(url) } returns file
            val manga = SManga.create().apply {
                this.url = url
                title = "漫画"
            }
            val source = LocalSource(context, fileSystem, coverManager)
            assertEquals(url, source.getChapterList(manga).single().url)
            val format = source.getFormat(SChapter.create().apply { this.url = url })
            if (extension == "EPUB") {
                assertSame(file, (format as Format.Epub).file)
            } else {
                assertSame(file, (format as Format.Archive).file)
            }
        }
    }
}
