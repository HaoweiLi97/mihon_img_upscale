package eu.kanade.tachiyomi.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

enum class PageLayout(
    val value: Int,
    val webtoonValue: Int,
    val stringRes: StringResource,
    private val _fullStringRes: StringResource? = null,
) {
    SINGLE_PAGE(0, 0, MR.strings.page_layout_single),
    DOUBLE_PAGES(1, 2, MR.strings.page_layout_double),
    AUTOMATIC(2, 3, MR.strings.page_layout_automatic),
    SPLIT_PAGES(3, 1, MR.strings.page_layout_split),
    ;

    val fullStringRes = _fullStringRes ?: stringRes

    companion object {
        fun fromPreference(preference: Int): PageLayout =
            entries.find { it.value == preference } ?: SINGLE_PAGE
    }
}
