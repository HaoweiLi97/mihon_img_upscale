package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null), ReaderItem {

    open lateinit var chapter: ReaderChapter

    var fullPage: Boolean? = null
    var isolatedPage: Boolean = false
    var shiftedPage: Boolean = false
    var firstHalf: Boolean? = null

    var endPageConfidence: Int? = null
    var startPageConfidence: Int? = null

    fun isFromSamePage(other: ReaderPage): Boolean {
        return index == other.index && chapter.chapter.id == other.chapter.chapter.id
    }
}
