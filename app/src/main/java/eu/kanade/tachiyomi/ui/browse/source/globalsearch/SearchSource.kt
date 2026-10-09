package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.model.Manga

internal suspend fun searchSource(
    source: CatalogueSource,
    query: String,
    dispatcher: CoroutineDispatcher,
    networkToLocalManga: suspend (List<Manga>) -> List<Manga>,
): SearchItemResult {
    return try {
        val page = withContext(dispatcher) {
            source.getSearchManga(1, query, source.getFilterList())
        }
        val titles = page.mangas
            .map { it.toDomainManga(source.id) }
            .distinctBy { it.url }
            .let { networkToLocalManga(it) }
        SearchItemResult.Success(titles)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SearchItemResult.Error(e)
    } catch (e: LinkageError) {
        // Extensions are compiled separately; a missing shared API must only fail this source.
        SearchItemResult.Error(e)
    }
}
