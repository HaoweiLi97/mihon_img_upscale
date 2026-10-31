package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun ReaderPage.downloadProgressFlow(extraPage: ReaderPage? = null): Flow<Int> {
    fun ReaderPage.progress(): Flow<Int> = combine(statusFlow, progressFlow) { state, value ->
        if (state == Page.State.Ready) 100 else value.coerceIn(0, 100)
    }

    val primary = progress()
    return if (extraPage == null) {
        primary
    } else {
        combine(primary, extraPage.progress()) { first, second -> (first + second) / 2 }
    }.distinctUntilChanged()
}
