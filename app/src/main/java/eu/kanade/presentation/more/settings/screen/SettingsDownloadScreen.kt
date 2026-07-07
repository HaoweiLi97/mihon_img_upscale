package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TrailingWidgetBuffer
import eu.kanade.tachiyomi.data.download.CloudSyncConfig
import eu.kanade.tachiyomi.data.download.CloudSyncService
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.core.common.i18n.stringResource as contextStringResource
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsDownloadScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_downloads

    @Composable
    override fun getPreferences(): List<Preference> {
        val getCategories = remember { Injekt.get<GetCategories>() }
        val allCategories by getCategories.subscribe().collectAsState(initial = emptyList())

        val downloadPreferences = remember { Injekt.get<DownloadPreferences>() }
        val parallelSourceLimit by downloadPreferences.parallelSourceLimit().collectAsState()
        val parallelPageLimit by downloadPreferences.parallelPageLimit().collectAsState()
        val saveChaptersAsCBZ by downloadPreferences.saveChaptersAsCBZ().collectAsState()
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.downloadOnlyOverWifi(),
                title = stringResource(MR.strings.connected_to_wifi),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.saveChaptersAsCBZ(),
                title = stringResource(MR.strings.save_chapter_as_cbz),
            ),
            getCloudSyncGroup(
                downloadPreferences = downloadPreferences,
                saveChaptersAsCBZ = saveChaptersAsCBZ,
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.splitTallImages(),
                title = stringResource(MR.strings.split_tall_images),
                subtitle = stringResource(MR.strings.split_tall_images_summary),
            ),
            Preference.PreferenceItem.SliderPreference(
                value = parallelSourceLimit,
                valueRange = 1..10,
                title = stringResource(MR.strings.pref_download_concurrent_sources),
                onValueChanged = { downloadPreferences.parallelSourceLimit().set(it) },
            ),
            Preference.PreferenceItem.SliderPreference(
                value = parallelPageLimit,
                valueRange = 1..15,
                title = stringResource(MR.strings.pref_download_concurrent_pages),
                subtitle = stringResource(MR.strings.pref_download_concurrent_pages_summary),
                onValueChanged = { downloadPreferences.parallelPageLimit().set(it) },
            ),
            getDeleteChaptersGroup(
                downloadPreferences = downloadPreferences,
                categories = allCategories,
            ),
            getAutoDownloadGroup(
                downloadPreferences = downloadPreferences,
                allCategories = allCategories,
            ),
            getDownloadAheadGroup(downloadPreferences = downloadPreferences),
        )
    }

    @Composable
    private fun getCloudSyncGroup(
        downloadPreferences: DownloadPreferences,
        saveChaptersAsCBZ: Boolean,
    ): Preference.PreferenceGroup {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val cloudSyncService = remember { Injekt.get<CloudSyncService>() }

        val cloudSyncEnabled by downloadPreferences.cloudSyncEnabled().collectAsState()
        val cloudSyncUrl by downloadPreferences.cloudSyncUrl().collectAsState()
        val cloudSyncUsername by downloadPreferences.cloudSyncUsername().collectAsState()
        val cloudSyncPassword by downloadPreferences.cloudSyncPassword().collectAsState()
        val cloudSyncDestination by downloadPreferences.cloudSyncDestination().collectAsState()
        val deleteAfterUpload by downloadPreferences.cloudSyncDeleteAfterUpload().collectAsState()

        var showLoginDialog by rememberSaveable { mutableStateOf(false) }
        var testingConnection by rememberSaveable { mutableStateOf(false) }

        if (showLoginDialog) {
            CloudSyncLoginDialog(
                initialUrl = cloudSyncUrl,
                initialUsername = cloudSyncUsername,
                initialPassword = cloudSyncPassword,
                testingConnection = testingConnection,
                onDismissRequest = { if (!testingConnection) showLoginDialog = false },
                onConfirm = { url, username, password ->
                    testingConnection = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                cloudSyncService.testConnection(
                                    CloudSyncConfig(
                                        url = url.trim(),
                                        username = username.trim(),
                                        password = password,
                                    ),
                                )
                            }
                        }
                        testingConnection = false
                        result
                            .onSuccess {
                                downloadPreferences.cloudSyncUrl().set(url.trim())
                                downloadPreferences.cloudSyncUsername().set(username.trim())
                                downloadPreferences.cloudSyncPassword().set(password)
                                downloadPreferences.cloudSyncEnabled().set(true)
                                showLoginDialog = false
                                context.toast(MR.strings.cloud_sync_connection_success)
                            }
                            .onFailure {
                                context.toast(it.message ?: context.contextStringResource(MR.strings.cloud_sync_connection_failed))
                            }
                    }
                },
            )
        }

        val cloudSettingsAvailable = saveChaptersAsCBZ
        val destinationAvailable = cloudSettingsAvailable && cloudSyncEnabled

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.cloud_sync),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.cloud_sync),
                ) {
                    CloudSyncTextPreference(
                        title = stringResource(MR.strings.cloud_sync),
                        subtitle = when {
                            !saveChaptersAsCBZ -> stringResource(MR.strings.cloud_sync_requires_cbz)
                            cloudSyncEnabled -> stringResource(MR.strings.cloud_sync_connected, cloudSyncUsername)
                            else -> stringResource(MR.strings.cloud_sync_not_configured)
                        },
                        enabled = cloudSettingsAvailable,
                        onClick = { showLoginDialog = true },
                    )
                },
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.cloud_sync_destination),
                ) {
                    CloudSyncTextPreference(
                        title = stringResource(MR.strings.cloud_sync_destination),
                        subtitle = cloudSyncDestination.takeIf { it.isNotBlank() }
                            ?: stringResource(MR.strings.cloud_sync_no_destination),
                        enabled = destinationAvailable,
                        onClick = { navigator.push(CloudSyncDestinationScreen()) },
                    )
                },
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.cloud_sync_delete_after_upload),
                ) {
                    CloudSyncSwitchPreference(
                        title = stringResource(MR.strings.cloud_sync_delete_after_upload),
                        checked = deleteAfterUpload,
                        enabled = destinationAvailable,
                        onCheckedChanged = { downloadPreferences.cloudSyncDeleteAfterUpload().set(it) },
                    )
                },
            ),
        )
    }

    @Composable
    private fun CloudSyncTextPreference(
        title: String,
        subtitle: String?,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        TextPreferenceWidget(
            modifier = Modifier.alpha(if (enabled) 1f else DisabledPreferenceAlpha),
            title = title,
            subtitle = subtitle,
            onPreferenceClick = onClick.takeIf { enabled },
        )
    }

    @Composable
    private fun CloudSyncSwitchPreference(
        title: String,
        checked: Boolean,
        enabled: Boolean,
        onCheckedChanged: (Boolean) -> Unit,
    ) {
        TextPreferenceWidget(
            modifier = Modifier.alpha(if (enabled) 1f else DisabledPreferenceAlpha),
            title = title,
            widget = {
                Switch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = null,
                    modifier = Modifier.padding(start = TrailingWidgetBuffer),
                )
            },
            onPreferenceClick = if (enabled) {
                { onCheckedChanged(!checked) }
            } else {
                null
            },
        )
    }

    @Composable
    private fun CloudSyncLoginDialog(
        initialUrl: String,
        initialUsername: String,
        initialPassword: String,
        testingConnection: Boolean,
        onDismissRequest: () -> Unit,
        onConfirm: (String, String, String) -> Unit,
    ) {
        var url by rememberSaveable { mutableStateOf(initialUrl) }
        var username by rememberSaveable { mutableStateOf(initialUsername) }
        var password by rememberSaveable { mutableStateOf(initialPassword) }
        val canConfirm = url.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !testingConnection

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = stringResource(MR.strings.cloud_sync_login)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(text = stringResource(MR.strings.cloud_sync_webdav_url)) },
                        singleLine = true,
                        enabled = !testingConnection,
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(text = stringResource(MR.strings.username)) },
                        singleLine = true,
                        enabled = !testingConnection,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(text = stringResource(MR.strings.password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !testingConnection,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canConfirm,
                    onClick = { onConfirm(url, username, password) },
                ) {
                    if (testingConnection) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(text = stringResource(MR.strings.cloud_sync_test_connection))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !testingConnection,
                    onClick = onDismissRequest,
                ) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    @Composable
    private fun getDeleteChaptersGroup(
        downloadPreferences: DownloadPreferences,
        categories: List<Category>,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_delete_chapters),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadPreferences.removeAfterMarkedAsRead(),
                    title = stringResource(MR.strings.pref_remove_after_marked_as_read),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = downloadPreferences.removeAfterReadSlots(),
                    entries = persistentMapOf(
                        -1 to stringResource(MR.strings.disabled),
                        0 to stringResource(MR.strings.last_read_chapter),
                        1 to stringResource(MR.strings.second_to_last),
                        2 to stringResource(MR.strings.third_to_last),
                        3 to stringResource(MR.strings.fourth_to_last),
                        4 to stringResource(MR.strings.fifth_to_last),
                    ),
                    title = stringResource(MR.strings.pref_remove_after_read),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadPreferences.removeBookmarkedChapters(),
                    title = stringResource(MR.strings.pref_remove_bookmarked_chapters),
                ),
                getExcludedCategoriesPreference(
                    downloadPreferences = downloadPreferences,
                    categories = { categories },
                ),
            ),
        )
    }

    @Composable
    private fun getExcludedCategoriesPreference(
        downloadPreferences: DownloadPreferences,
        categories: () -> List<Category>,
    ): Preference.PreferenceItem.MultiSelectListPreference {
        return Preference.PreferenceItem.MultiSelectListPreference(
            preference = downloadPreferences.removeExcludeCategories(),
            entries = categories()
                .associate { it.id.toString() to it.visualName }
                .toImmutableMap(),
            title = stringResource(MR.strings.pref_remove_exclude_categories),
        )
    }

    @Composable
    private fun getAutoDownloadGroup(
        downloadPreferences: DownloadPreferences,
        allCategories: List<Category>,
    ): Preference.PreferenceGroup {
        val downloadNewChaptersPref = downloadPreferences.downloadNewChapters()
        val downloadNewUnreadChaptersOnlyPref = downloadPreferences.downloadNewUnreadChaptersOnly()
        val downloadNewChapterCategoriesPref = downloadPreferences.downloadNewChapterCategories()
        val downloadNewChapterCategoriesExcludePref = downloadPreferences.downloadNewChapterCategoriesExclude()

        val downloadNewChapters by downloadNewChaptersPref.collectAsState()

        val included by downloadNewChapterCategoriesPref.collectAsState()
        val excluded by downloadNewChapterCategoriesExcludePref.collectAsState()
        var showDialog by rememberSaveable { mutableStateOf(false) }
        if (showDialog) {
            TriStateListDialog(
                title = stringResource(MR.strings.categories),
                message = stringResource(MR.strings.pref_download_new_categories_details),
                items = allCategories,
                initialChecked = included.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                initialInversed = excluded.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                itemLabel = { it.visualName },
                onDismissRequest = { showDialog = false },
                onValueChanged = { newIncluded, newExcluded ->
                    downloadNewChapterCategoriesPref.set(newIncluded.fastMap { it.id.toString() }.toSet())
                    downloadNewChapterCategoriesExcludePref.set(newExcluded.fastMap { it.id.toString() }.toSet())
                    showDialog = false
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_auto_download),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadNewChaptersPref,
                    title = stringResource(MR.strings.pref_download_new),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadNewUnreadChaptersOnlyPref,
                    title = stringResource(MR.strings.pref_download_new_unread_chapters_only),
                    enabled = downloadNewChapters,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.categories),
                    subtitle = getCategoriesLabel(
                        allCategories = allCategories,
                        included = included,
                        excluded = excluded,
                    ),
                    enabled = downloadNewChapters,
                    onClick = { showDialog = true },
                ),
            ),
        )
    }

    @Composable
    private fun getDownloadAheadGroup(
        downloadPreferences: DownloadPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.download_ahead),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = downloadPreferences.autoDownloadWhileReading(),
                    entries = listOf(0, 2, 3, 5, 10)
                        .associateWith {
                            if (it == 0) {
                                stringResource(MR.strings.disabled)
                            } else {
                                pluralStringResource(MR.plurals.next_unread_chapters, count = it, it)
                            }
                        }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.auto_download_while_reading),
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.download_ahead_info)),
            ),
        )
    }
}

private const val DisabledPreferenceAlpha = 0.38f
