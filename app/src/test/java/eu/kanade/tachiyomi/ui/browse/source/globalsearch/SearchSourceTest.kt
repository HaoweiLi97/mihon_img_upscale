package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import rx.Observable
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class SearchSourceTest {

    @Test
    fun `legacy Rx source linkage failure does not cancel other source searches`() = runTest {
        val error = NoSuchMethodError("runBlockingK\$default")
        val sources = listOf(
            LegacySource { throw error },
            LegacySource { MangasPage(listOf(manga("/working", "Working")), false) },
        )
        val dispatcher = StandardTestDispatcher(testScheduler)

        val results = sources.map { source ->
            async { searchSource(source, "query", dispatcher) { it } }
        }.awaitAll()

        assertFailure(error, assertInstanceOf(SearchItemResult.Error::class.java, results[0]).throwable)
        assertEquals(
            "Working",
            assertInstanceOf(SearchItemResult.Success::class.java, results[1]).result.single().title,
        )
    }

    @Test
    fun `missing API during filter creation is reported for that source`() = runTest {
        val error = AbstractMethodError("getFilterList")
        val source = object : CatalogueSource by LegacySource({ MangasPage(emptyList(), false) }) {
            override fun getFilterList(): FilterList = throw error
        }

        val result = searchSource(source, "query", StandardTestDispatcher(testScheduler)) { it }

        assertFailure(error, assertInstanceOf(SearchItemResult.Error::class.java, result).throwable)
    }

    @Test
    fun `network failures are reported for that source`() = runTest {
        val error = IOException("network unavailable")
        val result = searchSource(LegacySource { throw error }, "query", StandardTestDispatcher(testScheduler)) { it }

        assertFailure(error, assertInstanceOf(SearchItemResult.Error::class.java, result).throwable)
    }

    @Test
    fun `cancellation is propagated instead of displayed as a source failure`() = runTest {
        val cancellation = CancellationException("new query")
        var caught: CancellationException? = null
        try {
            searchSource(LegacySource { throw cancellation }, "query", StandardTestDispatcher(testScheduler)) { it }
        } catch (e: CancellationException) {
            caught = e
        }

        assertFailure(cancellation, caught)
    }

    @Test
    fun `fatal VM errors are propagated`() = runTest {
        val error = OutOfMemoryError("fatal")
        var caught: OutOfMemoryError? = null
        try {
            searchSource(LegacySource { throw error }, "query", StandardTestDispatcher(testScheduler)) { it }
        } catch (e: OutOfMemoryError) {
            caught = e
        }

        assertFailure(error, caught)
    }

    @Test
    fun `successful results are deduplicated before being saved`() = runTest {
        val source = LegacySource {
            MangasPage(listOf(manga("/one", "First"), manga("/one", "Duplicate"), manga("/two", "Second")), false)
        }
        val result = searchSource(source, "query", StandardTestDispatcher(testScheduler)) { mangas ->
            assertEquals(listOf("/one", "/two"), mangas.map { it.url })
            mangas.mapIndexed { index, manga -> manga.copy(id = index.toLong() + 10) }
        }
        val mangas = assertInstanceOf(SearchItemResult.Success::class.java, result).result

        assertEquals(listOf(10L, 11L), mangas.map { it.id })
        assertEquals(listOf("First", "Second"), mangas.map { it.title })
        assertEquals(listOf(source.id, source.id), mangas.map { it.source })
    }

    @Test
    fun `host provides both legacy and new extension runBlocking entry points`() {
        val builders = Class.forName("kotlinx.coroutines.BuildersKt")
        for (methodName in listOf("runBlocking\$default", "runBlockingK\$default")) {
            val method = builders.getMethod(
                methodName,
                CoroutineContext::class.java,
                Function2::class.java,
                Int::class.javaPrimitiveType,
                Any::class.java,
            )
            val block: suspend kotlinx.coroutines.CoroutineScope.() -> String = { "compatible" }

            assertEquals("compatible", method.invoke(null, EmptyCoroutineContext, block, 1, null))
        }
    }

    private fun assertFailure(expected: Throwable, actual: Throwable?) {
        // Coroutine stack-trace recovery may copy exceptions across dispatcher boundaries.
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
    }

    private class LegacySource(private val search: () -> MangasPage) : CatalogueSource {
        override val id = 1L
        override val name = "Test source"
        override val lang = "en"
        override val supportsLatest = false

        override fun getFilterList() = FilterList()

        @Suppress("OVERRIDE_DEPRECATION")
        override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
            Observable.fromCallable {
                assertEquals(1, page)
                assertEquals("query", query)
                search()
            }
    }

    private fun manga(url: String, title: String) = SManga.create().apply {
        this.url = url
        this.title = title
    }
}
