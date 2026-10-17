package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 */
interface Source {

    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Whether the source has support for latest updates.
     *
     * Part of the TachiyomiX 1.6 source ABI. It remains optional here so older
     * sources and non-catalogue sources keep working.
     */
    val supportsLatest: Boolean
        get() = false

    /** Part of the TachiyomiX 1.6 source ABI. */
    fun getFilterList(): FilterList = FilterList()

    /** Part of the TachiyomiX 1.6 source ABI. */
    suspend fun getPopularManga(page: Int): MangasPage =
        throw UnsupportedOperationException("Popular manga is not supported")

    /** Part of the TachiyomiX 1.6 source ABI. */
    suspend fun getLatestUpdates(page: Int): MangasPage =
        throw UnsupportedOperationException("Latest updates are not supported")

    /** Part of the TachiyomiX 1.6 source ABI. */
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        throw UnsupportedOperationException("Search is not supported")

    /**
     * Combined manga/chapters update API introduced by TachiyomiX 1.6.
     * Dispatch through suspend methods, falling back to RxJava only in their defaults.
     * New 1.6 extensions override this method directly.
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(LegacyMangaUpdateContext(this)) {
        val updatedManga = if (fetchDetails) getMangaDetails(manga) else manga
        val updatedChapters = if (fetchChapters) getChapterList(updatedManga) else chapters
        SMangaUpdate(updatedManga, updatedChapters)
    }

    /**
     * Get the updated details for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the updated manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga {
        return if (currentCoroutineContext()[LegacyMangaUpdateContext]?.source === this) {
            fetchMangaDetails(manga).awaitSingle()
        } else {
            getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
        }
    }

    /**
     * Get all the available chapters for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the chapters for the manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga): List<SChapter> {
        return if (currentCoroutineContext()[LegacyMangaUpdateContext]?.source === this) {
            fetchChapterList(manga).awaitSingle()
        } else {
            getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true).chapters
        }
    }

    /**
     * Get the list of pages a chapter has. Pages should be returned
     * in the expected order; the index is ignored.
     *
     * @since extensions-lib 1.5
     * @param chapter the chapter.
     * @return the pages for the chapter.
     */
    @Suppress("DEPRECATION")
    suspend fun getPageList(chapter: SChapter): List<Page> {
        return fetchPageList(chapter).awaitSingle()
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getMangaDetails"),
    )
    fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getChapterList"),
    )
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPageList"),
    )
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        throw IllegalStateException("Not used")
}

/**
 * Prevents default individual/combined APIs from calling each other recursively.
 * Coroutine scope keeps concurrent requests independent and restores dispatch after an update.
 */
private class LegacyMangaUpdateContext(val source: Source) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LegacyMangaUpdateContext>
}
