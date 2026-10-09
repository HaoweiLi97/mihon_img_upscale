package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.coroutines.Continuation

class ExtensionSharedApiTest {

    @Test
    fun `source API retains 1_6 search and filter methods`() {
        Source::class.java.getMethod(
            "getSearchManga",
            Int::class.javaPrimitiveType,
            String::class.java,
            FilterList::class.java,
            Continuation::class.java,
        )
        assertEquals(FilterList::class.java, Source::class.java.getMethod("getFilterList").returnType)
    }

    @Test
    fun `Kotlin interface bridges used by older extensions remain available`() {
        Class.forName("eu.kanade.tachiyomi.source.Source\$DefaultImpls")
            .getMethod("getFilterList", Source::class.java)
        val catalogueBridges = Class.forName("eu.kanade.tachiyomi.source.CatalogueSource\$DefaultImpls")
        catalogueBridges.getMethod("getSupportsRelatedMangas", CatalogueSource::class.java)
        catalogueBridges.getMethod(
            "fetchRelatedMangaList",
            CatalogueSource::class.java,
            SManga::class.java,
            Continuation::class.java,
        )
    }

    @Test
    fun `context parameter migration preserves JSON helper JVM signature`() {
        Class.forName("eu.kanade.tachiyomi.network.OkHttpExtensionsKt").getMethod(
            "decodeFromJsonResponse",
            Json::class.java,
            DeserializationStrategy::class.java,
            Response::class.java,
        )
    }
}
