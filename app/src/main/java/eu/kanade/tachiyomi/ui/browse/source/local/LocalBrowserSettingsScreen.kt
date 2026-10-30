package eu.kanade.tachiyomi.ui.browse.source.local

import android.content.ActivityNotFoundException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

class LocalBrowserSettingsScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val preferences = remember { context.appGraph.storagePreferences }
        val location by preferences.localComicDirectory.collectAsState()
        val picker = SettingsDataScreen.storageLocationPicker(preferences.localComicDirectory)
        PreferenceScaffold(
            titleRes = MR.strings.local_browser_settings,
            onBackPressed = navigator::pop,
        ) {
            listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.local_browser_directory),
                    subtitle = if (location.isBlank()) {
                        stringResource(MR.strings.local_browser_default_directory)
                    } else {
                        SettingsDataScreen.storageLocationText(preferences.localComicDirectory)
                    },
                    onClick = {
                        try {
                            picker.launch(null)
                        } catch (_: ActivityNotFoundException) {
                            context.toast(MR.strings.file_picker_error)
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.local_browser_use_default),
                    onClick = { preferences.localComicDirectory.set("") },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = preferences.localBrowserThumbnails,
                    title = stringResource(MR.strings.local_browser_thumbnails),
                    subtitle = stringResource(MR.strings.local_browser_thumbnails_summary),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.localBrowserColumns,
                    title = stringResource(MR.strings.local_browser_columns),
                    entries = mapOf(0 to stringResource(MR.strings.local_browser_auto)) +
                        (2..6).associateWith(Int::toString),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.localBrowserSort,
                    title = stringResource(MR.strings.action_sort),
                    entries = mapOf(
                        0 to stringResource(MR.strings.local_browser_sort_name),
                        1 to stringResource(MR.strings.local_browser_sort_newest),
                    ),
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.local_browser_formats)),
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.local_browser_hint)),
            )
        }
    }
}
