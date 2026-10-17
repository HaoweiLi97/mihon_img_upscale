package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

class ChapterMemoSyncTest {

    @Test
    fun `refresh repairs missing metadata on an existing chapter and preserves reading progress`() = runTest {
        val memo = buildJsonObject { put("mangaPath", "/comic/sample/") }
        val manga = Manga.create().copy(id = 3, title = "Sample")
        val existing = Chapter.create().copy(
            id = 7,
            mangaId = manga.id,
            url = "chapter-1",
            name = "Chapter 1",
            chapterNumber = 1.0,
            dateUpload = Instant.fromEpochMilliseconds(100),
            read = true,
            bookmark = true,
            lastPageRead = 12,
        )
        val repository = mockk<ChapterRepository>(relaxed = true)
        val source = mockk<HttpSource>(relaxed = true)
        val libraryPreferences = mockk<LibraryPreferences>()
        val excluded = mockk<GetExcludedScanlators>()
        val updates = slot<List<ChapterRemoteUpdate>>()
        coEvery { repository.getChapterByMangaId(manga.id, false) } returns listOf(existing)
        coEvery { repository.updateFromRemote(emptyList(), emptyList(), capture(updates)) } returns emptyList()
        coEvery { excluded.await(manga.id) } returns emptySet()
        every { libraryPreferences.markDuplicateReadChapterAsRead.get() } returns emptySet()
        every { source.prepareNewChapter(any(), any()) } answers {
            assertEquals(memo, firstArg<eu.kanade.tachiyomi.source.model.SChapter>().memo)
        }
        val sync = SyncChaptersWithSource(
            downloadManager = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            chapterRepository = repository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = mockk<UpdateManga>(relaxed = true),
            getExcludedScanlators = excluded,
            libraryPreferences = libraryPreferences,
        )

        sync.await(listOf(existing.copy(memo = memo).toSChapter()), manga, source, manualFetch = true)

        val updated = updates.captured.single()
        assertEquals(existing.id, updated.id)
        assertEquals(memo, updated.memo)
        // Source updates carry metadata only; reading state cannot be overwritten by this operation.
        coVerify(exactly = 1) { repository.updateFromRemote(emptyList(), emptyList(), any()) }
        coVerify(exactly = 0) { repository.updateAll(any()) }
    }
}
