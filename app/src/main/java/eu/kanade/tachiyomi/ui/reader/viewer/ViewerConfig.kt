package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.core.common.preference.Preference

/**
 * Common configuration for all viewers.
 */
abstract class ViewerConfig(
    readerPreferences: ReaderPreferences,
    private val scope: CoroutineScope,
    private val isLandscape: () -> Boolean = { false },
) {

    var imagePropertyChangedListener: (() -> Unit)? = null

    var navigationModeChangedListener: (() -> Unit)? = null

    var reloadChapterListener: ((Boolean) -> Unit)? = null

    var tappingInverted = ReaderPreferences.TappingInvertMode.NONE
    var longTapEnabled = true
    var usePageTransitions = false
    var doubleTapAnimDuration = 500
    var volumeKeysEnabled = false
    var volumeKeysInverted = false
    var alwaysShowChapterTransition = true
    var navigationMode = 0
        protected set

    var forceNavigationOverlay = false

    var navigationOverlayOnStart = false

    var dualPageSplit = false
        protected set

    var dualPageInvert = false
        protected set

    var dualPageRotateToFit = false
        protected set

    var dualPageRotateToFitInvert = false
        protected set

    var readerTheme = 1

    var hingeGapSize = 0

    var shiftDoublePage = false

    var doublePages = false
        set(value) {
            field = value
            if (!value) {
                shiftDoublePage = false
            }
        }

    var invertDoublePages = false

    var autoDoublePages = false

    var splitPages = false
    var autoSplitPages = false

    private var pageLayoutPreference = readerPreferences.pageLayout().get()

    abstract var navigator: ViewerNavigation
        protected set

    init {
        readerPreferences.readWithLongTap()
            .register({ longTapEnabled = it })

        readerPreferences.pageTransitions()
            .register({ usePageTransitions = it })

        readerPreferences.doubleTapAnimSpeed()
            .register({ doubleTapAnimDuration = it })

        readerPreferences.readWithVolumeKeys()
            .register({ volumeKeysEnabled = it })

        readerPreferences.readWithVolumeKeysInverted()
            .register({ volumeKeysInverted = it })

        readerPreferences.alwaysShowChapterTransition()
            .register({ alwaysShowChapterTransition = it })

        forceNavigationOverlay = readerPreferences.showNavigationOverlayNewUser().get()
        if (forceNavigationOverlay) {
            readerPreferences.showNavigationOverlayNewUser().set(false)
        }

        readerPreferences.showNavigationOverlayOnStart()
            .register({ navigationOverlayOnStart = it })

        readerPreferences.invertDoublePages()
            .register({ invertDoublePages = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.pageLayout()
            .register({
                pageLayoutPreference = it
                autoDoublePages = it == PageLayout.AUTOMATIC.value
                splitPages = it == PageLayout.SPLIT_PAGES.value
                doublePages = when (it) {
                    PageLayout.DOUBLE_PAGES.value -> true
                    PageLayout.AUTOMATIC.value -> isLandscape()
                    else -> false
                }
            }, {
                reloadChapterListener?.invoke(doublePages)
            })

        readerPreferences.automaticSplitsPage()
            .register({ autoSplitPages = it }, {
                reloadChapterListener?.invoke(doublePages)
            })

        readerPreferences.readerTheme()
            .register({ readerTheme = it })

        readerPreferences.hingeGapSize()
            .register({ hingeGapSize = it }, { imagePropertyChangedListener?.invoke() })
    }

    fun refreshAutomaticPageLayout() {
        if (pageLayoutPreference != PageLayout.AUTOMATIC.value) return
        val newDoublePages = isLandscape()
        if (doublePages != newDoublePages) {
            doublePages = newDoublePages
            reloadChapterListener?.invoke(doublePages)
        }
    }

    protected abstract fun defaultNavigation(): ViewerNavigation

    abstract fun updateNavigation(navigationMode: Int)

    fun <T> Preference<T>.register(
        valueAssignment: (T) -> Unit,
        onChanged: (T) -> Unit = {},
    ) {
        changes()
            .onEach { valueAssignment(it) }
            .distinctUntilChanged()
            .onEach { onChanged(it) }
            .launchIn(scope)
    }
}
