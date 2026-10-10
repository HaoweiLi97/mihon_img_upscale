package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import rx.Observable
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.domain.source.model.StubSource

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class SourceMangaUpdateTest {

    private val manga = SManga.create().apply {
        url = "/manga"
        title = "Sample"
    }
    private val updatedManga = SManga.create().apply {
        url = "/manga"
        title = "Updated"
    }
    private val chapters = listOf(SChapter.create().apply { name = "Existing chapter" })
    private val updatedChapters = listOf(SChapter.create().apply { name = "New chapter" })
    private var detailsCalls = 0
    private var chapterCalls = 0

    @ParameterizedTest
    @CsvSource(
        "legacy-source,true,true",
        "legacy-source,true,false",
        "legacy-source,false,true",
        "legacy-source,false,false",
        "suspend-source,true,true",
        "suspend-source,true,false",
        "suspend-source,false,true",
        "suspend-source,false,false",
        "legacy-catalogue,true,true",
        "legacy-catalogue,true,false",
        "legacy-catalogue,false,true",
        "legacy-catalogue,false,false",
        "suspend-catalogue,true,true",
        "suspend-catalogue,true,false",
        "suspend-catalogue,false,true",
        "suspend-catalogue,false,false",
        "legacy-http,true,true",
        "legacy-http,true,false",
        "legacy-http,false,true",
        "legacy-http,false,false",
        "suspend-http,true,true",
        "suspend-http,true,false",
        "suspend-http,false,true",
        "suspend-http,false,false",
        "mixed-http,true,true",
        "mixed-http,true,false",
        "mixed-http,false,true",
        "mixed-http,false,false",
    )
    fun `combined updates honor legacy and suspend implementations`(
        kind: String,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) = runTest {
        val update = createSource(kind).getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
        assertSame(if (fetchDetails) updatedManga else manga, update.manga)
        assertSame(if (fetchChapters) updatedChapters else chapters, update.chapters)
        assertEquals(if (fetchDetails) 1 else 0, detailsCalls)
        assertEquals(if (fetchChapters) 1 else 0, chapterCalls)
    }

    @ParameterizedTest
    @CsvSource("source", "catalogue", "http")
    fun `individual requests reach a combined-only source`(kind: String) = runTest {
        val source = combinedSource(kind) { inputManga, inputChapters, fetchDetails, fetchChapters ->
            if (fetchDetails) detailsCalls++
            if (fetchChapters) chapterCalls++
            SMangaUpdate(
                if (fetchDetails) updatedManga else inputManga,
                if (fetchChapters) updatedChapters else inputChapters,
            )
        }
        assertSame(updatedManga, source.getMangaDetails(manga))
        assertSame(updatedChapters, source.getChapterList(manga))
        assertEquals(1, detailsCalls)
        assertEquals(1, chapterCalls)
    }

    @ParameterizedTest
    @CsvSource("source", "catalogue", "http")
    fun `combined source errors are preserved`(kind: String) {
        val failure = UnsupportedOperationException("Source does not support this manga")
        val source = combinedSource(kind) { _, _, _, _ -> throw failure }
        assertSame(
            failure,
            assertThrows(UnsupportedOperationException::class.java) {
                runTest { source.getMangaDetails(manga) }
            },
        )
        assertSame(
            failure,
            assertThrows(UnsupportedOperationException::class.java) {
                runTest { source.getChapterList(manga) }
            },
        )
    }

    @Test
    fun `missing extensions keep the source-not-installed error during combined updates`() {
        val source = StubSource(99, "en", "Missing source")
        assertThrows(SourceNotInstalledException::class.java) {
            runTest { source.getMangaUpdate(manga, chapters, true, true) }
        }
    }

    @Test
    fun `individual legacy requests still work after a combined update`() = runTest {
        val source = createSource("legacy-http")
        source.getMangaUpdate(manga, chapters, true, true)
        assertSame(updatedManga, source.getMangaDetails(manga))
        assertSame(updatedChapters, source.getChapterList(manga))
        assertEquals(2, detailsCalls)
        assertEquals(2, chapterCalls)
    }

    @Test
    fun `bridge dispatch is scoped to each coroutine`() = runTest {
        val enteredChapterRequest = CompletableDeferred<Unit>()
        val finishChapterRequest = CompletableDeferred<Unit>()
        var combinedCalls = 0
        val source = object : BasicSource() {
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ): SMangaUpdate {
                combinedCalls++
                return super.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
            }

            override fun fetchMangaDetails(manga: SManga) = Observable.just(details())

            override suspend fun getChapterList(manga: SManga): List<SChapter> {
                enteredChapterRequest.complete(Unit)
                finishChapterRequest.await()
                return chapterList()
            }
        }
        val chapterUpdate = async { source.getMangaUpdate(manga, chapters, false, true) }
        enteredChapterRequest.await()
        assertSame(updatedManga, source.getMangaDetails(manga))
        assertEquals(2, combinedCalls)
        finishChapterRequest.complete(Unit)
        assertSame(updatedChapters, chapterUpdate.await().chapters)
        assertSame(updatedManga, source.getMangaDetails(manga))
        assertEquals(3, combinedCalls)
    }

    @Test
    fun `chapter request receives freshly fetched manga details`() = runTest {
        val source = object : BasicCatalogue() {
            override suspend fun getMangaDetails(manga: SManga) = updatedManga
            override suspend fun getChapterList(manga: SManga): List<SChapter> {
                assertSame(updatedManga, manga)
                return updatedChapters
            }
        }
        assertSame(updatedChapters, source.getMangaUpdate(manga, chapters, true, true).chapters)
    }

    private fun details(): SManga = updatedManga.also { detailsCalls++ }
    private fun chapterList(): List<SChapter> = updatedChapters.also { chapterCalls++ }

    private fun createSource(kind: String): Source = when (kind) {
        "legacy-source" -> object : BasicSource() {
            override fun fetchMangaDetails(manga: SManga) = Observable.just(details())
            override fun fetchChapterList(manga: SManga) = Observable.just(chapterList())
        }
        "suspend-source" -> object : BasicSource() {
            override suspend fun getMangaDetails(manga: SManga) = details()
            override suspend fun getChapterList(manga: SManga) = chapterList()
        }
        "legacy-catalogue" -> object : BasicCatalogue() {
            override fun fetchMangaDetails(manga: SManga) = Observable.just(details())
            override fun fetchChapterList(manga: SManga) = Observable.just(chapterList())
        }
        "suspend-catalogue" -> object : BasicCatalogue() {
            override suspend fun getMangaDetails(manga: SManga) = details()
            override suspend fun getChapterList(manga: SManga) = chapterList()
        }
        "legacy-http" -> object : BasicHttp() {
            override fun fetchMangaDetails(manga: SManga) = Observable.just(details())
            override fun fetchChapterList(manga: SManga) = Observable.just(chapterList())
        }
        "suspend-http" -> object : BasicHttp() {
            override suspend fun getMangaDetails(manga: SManga) = details()
            override suspend fun getChapterList(manga: SManga) = chapterList()
        }
        "mixed-http" -> object : BasicHttp() {
            override suspend fun getMangaDetails(manga: SManga) = details()
            override fun fetchChapterList(manga: SManga) = Observable.just(chapterList())
        }
        else -> error("Unknown source kind: $kind")
    }

    private fun combinedSource(
        kind: String,
        update: (SManga, List<SChapter>, Boolean, Boolean) -> SMangaUpdate,
    ): Source = when (kind) {
        "source" -> object : BasicSource() {
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ) = update(manga, chapters, fetchDetails, fetchChapters)
        }
        "catalogue" -> object : BasicCatalogue() {
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ) = update(manga, chapters, fetchDetails, fetchChapters)
        }
        "http" -> object : BasicHttp() {
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ) = update(manga, chapters, fetchDetails, fetchChapters)
        }
        else -> error("Unknown source kind: $kind")
    }

    private abstract class BasicSource : Source {
        override val id = 1L
        override val name = "Test source"
    }

    private abstract class BasicCatalogue : BasicSource(), CatalogueSource {
        override val lang = "en"
        override val supportsLatest = false
        override fun getFilterList() = FilterList()
    }

    private abstract class BasicHttp : HttpSource() {
        override val name = "Test HTTP source"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "https://example.invalid"
        override fun popularMangaRequest(page: Int): Request = error("Unexpected network request")
        override fun popularMangaParse(response: Response): MangasPage = error("Unexpected network response")
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
            error("Unexpected network request")
        override fun searchMangaParse(response: Response): MangasPage = error("Unexpected network response")
        override fun latestUpdatesRequest(page: Int): Request = error("Unexpected network request")
        override fun latestUpdatesParse(response: Response): MangasPage = error("Unexpected network response")
        override fun mangaDetailsParse(response: Response): SManga = error("Unexpected legacy details request")
        override fun chapterListParse(response: Response): List<SChapter> = error("Unexpected legacy chapter request")
        override fun chapterPageParse(response: Response): SChapter = error("Unexpected network response")
        override fun pageListParse(response: Response): List<Page> = error("Unexpected network response")
        override fun imageUrlParse(response: Response): String = error("Unexpected network response")
    }
}
