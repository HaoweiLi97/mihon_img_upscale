package tachiyomi.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem

class LocalSourceUpdateTest {

    private val context = mockk<Context>()
    private val fileSystem = mockk<LocalSourceFileSystem>()
    private val coverManager = mockk<LocalCoverManager>()

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        every { context.stringResource(any()) } returns "Local"
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @ParameterizedTest
    @CsvSource("true, true", "true, false", "false, true", "false, false")
    fun `combined update reads local files only for requested fields`(
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) = runTest {
        val manga = SManga.create().apply {
            title = "Sample"
            url = "Sample"
            thumbnail_url = "existing-cover"
        }
        val existingChapters = listOf(SChapter.create().apply { name = "Old chapter" })
        val mangaDirectory = mockk<UniFile>()
        val noXmlFile = mockk<UniFile>()
        val chapterDirectory = mockk<UniFile>()

        every { coverManager.find(manga.url) } returns null
        every { fileSystem.getMangaDirectory(manga.url) } returns mangaDirectory
        every { mangaDirectory.listFiles() } returns arrayOf(noXmlFile)
        every { noXmlFile.name } returns ".noxml"
        every { fileSystem.getFilesInMangaDirectory(manga.url) } returns listOf(chapterDirectory)
        every { chapterDirectory.name } returns "Chapter 1"
        every { chapterDirectory.isDirectory } returns true
        every { chapterDirectory.lastModified() } returns 123L
        every { chapterDirectory.findFile("ComicInfo.xml") } returns null

        // Exercise the same Source entry point as opening/refreshing the manga details screen.
        val source: Source = LocalSource(context, fileSystem, coverManager)
        val update = source.getMangaUpdate(manga, existingChapters, fetchDetails, fetchChapters)

        assertSame(manga, update.manga)
        if (fetchChapters) {
            assertEquals(listOf("Sample/Chapter 1"), update.chapters.map { it.url })
            assertEquals(listOf("Chapter 1"), update.chapters.map { it.name })
            assertEquals(123L, update.chapters.single().date_upload)
        } else {
            assertSame(existingChapters, update.chapters)
        }
        verify(exactly = if (fetchDetails) 1 else 0) { fileSystem.getMangaDirectory(manga.url) }
        verify(exactly = if (fetchChapters) 1 else 0) { fileSystem.getFilesInMangaDirectory(manga.url) }
    }
}
