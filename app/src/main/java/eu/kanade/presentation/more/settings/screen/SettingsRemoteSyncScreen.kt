package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.components.CloudSyncIcon
import eu.kanade.presentation.components.label
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.MultiSelectListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TrailingWidgetBuffer
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.download.CloudSyncStatus
import eu.kanade.tachiyomi.data.download.WebDavConfig
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.CloudSyncStorage
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.core.common.i18n.stringResource as contextStringResource

object SettingsRemoteSyncScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.remote_sync

    @Composable
    override fun getPreferences(): List<Preference> {
        val graph = LocalContext.current.appGraph
        val categories by graph.getCategories.subscribe().collectAsState(initial = emptyList())
        val participatingIds by graph.downloadPreferences.remoteSyncCategories().collectAsState()
        val saveAsCbz by graph.downloadPreferences.saveChaptersAsCBZ.collectAsState()
        return listOf(
            getCloudSyncGroup(graph.downloadPreferences, saveAsCbz, categories, participatingIds),
            getCategorySyncGroup(categories.filter { it.id.toString() in participatingIds }),
        )
    }

    @Composable
    private fun getCategorySyncGroup(categories: List<Category>): Preference.PreferenceGroup {
        val context = LocalContext.current
        val graph = context.appGraph
        val cloudSync = graph.libraryCloudSync
        val selection by cloudSync.selection.collectAsState()
        val statuses by cloudSync.categoryStatuses.collectAsState()
        val scope = rememberCoroutineScope()
        var retrying by remember { mutableStateOf(emptySet<Long>()) }
        val rules = selection?.rules
        val canSync = rules?.target != null
        val globallyPaused = rules?.paused == true
        val groupTitle = stringResource(MR.strings.remote_sync_categories)

        if (categories.isEmpty()) {
            return Preference.PreferenceGroup(
                title = groupTitle,
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.remote_sync_no_categories),
                    ),
                ),
            )
        }

        return Preference.PreferenceGroup(
            title = groupTitle,
            preferenceItems = categories.map { category ->
                val title = category.visualName
                Preference.PreferenceItem.CustomPreference(title = title) {
                    val categoryPaused = category.id in rules?.pausedCategories.orEmpty()
                    val status = if (categoryPaused || globallyPaused) {
                        CloudSyncStatus.Paused
                    } else {
                        statuses[category.id]?.takeUnless { it == CloudSyncStatus.Paused } ?: CloudSyncStatus.Waiting
                    }
                    TextPreferenceWidget(
                        title = title,
                        subtitle = stringResource(if (canSync) status.label else MR.strings.remote_sync_setup_required),
                        widget = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (canSync) CloudSyncIcon(status)
                                if (canSync && status == CloudSyncStatus.Error) {
                                    TextButton(
                                        enabled = category.id !in retrying,
                                        onClick = {
                                            retrying += category.id
                                            scope.launch {
                                                try {
                                                    cloudSync.retryCategory(category.id)
                                                } catch (error: CancellationException) {
                                                    throw error
                                                } catch (_: Exception) {
                                                    context.toast(MR.strings.cloud_sync_status_error)
                                                } finally {
                                                    retrying -= category.id
                                                }
                                            }
                                        },
                                    ) {
                                        Text(stringResource(MR.strings.action_retry))
                                    }
                                }
                                if (canSync && !globallyPaused) {
                                    val action = if (categoryPaused) {
                                        MR.strings.cloud_sync_resume_category
                                    } else {
                                        MR.strings.cloud_sync_pause_category
                                    }
                                    TextButton(onClick = {
                                        cloudSync.setCategoryPaused(category.id, !categoryPaused)
                                    }) {
                                        Text(stringResource(action))
                                    }
                                }
                            }
                        },
                    )
                }
            },
        )
    }

    @Composable
    private fun getCloudSyncGroup(
        downloadPreferences: DownloadPreferences,
        saveChaptersAsCBZ: Boolean,
        categories: List<Category>,
        participatingIds: Set<String>,
    ): Preference.PreferenceGroup {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val graph = context.appGraph
        val oneDriveAuth = graph.oneDriveAuth

        val storage by downloadPreferences.cloudSyncStorage().collectAsState()
        val cloudSyncEnabled by downloadPreferences.cloudSyncEnabled().collectAsState()
        val cloudSyncUrl by downloadPreferences.cloudSyncUrl().collectAsState()
        val cloudSyncUsername by downloadPreferences.cloudSyncUsername().collectAsState()
        val cloudSyncPassword by downloadPreferences.cloudSyncPassword().collectAsState()
        val oneDriveId by downloadPreferences.cloudSyncOneDriveId().collectAsState()
        val cloudSyncDestination by key(storage) { downloadPreferences.cloudSyncDestination(storage).collectAsState() }
        val deleteAfterUpload by downloadPreferences.cloudSyncDeleteAfterUpload().collectAsState()

        var showLoginDialog by rememberSaveable(storage) { mutableStateOf(false) }
        var testingConnection by remember { mutableStateOf(false) }
        val oneDriveConnected = oneDriveId.isNotBlank()

        fun signInToOneDrive() {
            testingConnection = true
            scope.launch {
                try {
                    val url = oneDriveAuth.authorizationUrl(BuildConfig.ONEDRIVE_CLIENT_ID)
                    showLoginDialog = false
                    context.openInBrowser(url, forceDefaultBrowser = true)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    context.toast(MR.strings.cloud_sync_onedrive_login_failed)
                } finally {
                    testingConnection = false
                }
            }
        }

        if (showLoginDialog && storage == CloudSyncStorage.WebDav) {
            CloudSyncLoginDialog(
                initialUrl = cloudSyncUrl,
                initialUsername = cloudSyncUsername,
                initialPassword = cloudSyncPassword,
                testingConnection = testingConnection,
                onDismissRequest = { if (!testingConnection) showLoginDialog = false },
                onConfirm = { url, username, password ->
                    testingConnection = true
                    scope.launch {
                        try {
                            val config = WebDavConfig(url.trim(), username.trim(), password)
                            graph.cloudSyncService.storage(config).listDirectories("/")
                            downloadPreferences.cloudSyncUrl().set(config.url)
                            downloadPreferences.cloudSyncUsername().set(config.username)
                            downloadPreferences.cloudSyncPassword().set(config.password)
                            downloadPreferences.cloudSyncEnabled().set(true)
                            showLoginDialog = false
                            context.toast(MR.strings.cloud_sync_connection_success)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            context.toast(
                                error.message ?: context.contextStringResource(MR.strings.cloud_sync_connection_failed),
                            )
                        } finally {
                            testingConnection = false
                        }
                    }
                },
            )
        }
        if (showLoginDialog && storage == CloudSyncStorage.OneDrive && oneDriveConnected) {
            OneDriveAccountDialog(
                busy = testingConnection,
                onDismiss = { if (!testingConnection) showLoginDialog = false },
                onSignIn = ::signInToOneDrive,
                onDisconnect = {
                    testingConnection = true
                    scope.launch {
                        try {
                            oneDriveAuth.disconnect()
                            showLoginDialog = false
                        } finally {
                            testingConnection = false
                        }
                    }
                },
            )
        }

        val cloudSettingsAvailable = saveChaptersAsCBZ
        val connected = when (storage) {
            CloudSyncStorage.WebDav ->
                cloudSyncEnabled &&
                    WebDavConfig(cloudSyncUrl, cloudSyncUsername, cloudSyncPassword).isValid
            CloudSyncStorage.OneDrive -> oneDriveConnected
        }
        val destinationAvailable = cloudSettingsAvailable && connected
        val autoSyncAvailable = destinationAvailable && cloudSyncDestination.isNotBlank()
        val libraryCloudSync = graph.libraryCloudSync
        val categoryEntries = categories.associate { it.id.toString() to it.visualName }
        val categoryPickerTitle = stringResource(MR.strings.cloud_sync_auto_categories)
        val connectionTitle = stringResource(
            when (storage) {
                CloudSyncStorage.WebDav -> MR.strings.cloud_sync_webdav_connection
                CloudSyncStorage.OneDrive -> MR.strings.cloud_sync_onedrive_connection
            },
        )

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.cloud_sync),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = downloadPreferences.cloudSyncStorage(),
                    entries = mapOf(CloudSyncStorage.WebDav to "WebDAV", CloudSyncStorage.OneDrive to "OneDrive"),
                    title = stringResource(MR.strings.cloud_sync_storage),
                ),
                Preference.PreferenceItem.CustomPreference(title = connectionTitle) {
                    CloudSyncTextPreference(
                        title = connectionTitle,
                        subtitle = when {
                            !saveChaptersAsCBZ -> stringResource(MR.strings.cloud_sync_requires_cbz)
                            connected && storage == CloudSyncStorage.WebDav ->
                                stringResource(MR.strings.cloud_sync_connected, cloudSyncUsername)
                            connected -> stringResource(MR.strings.cloud_sync_connection_success)
                            storage == CloudSyncStorage.OneDrive -> stringResource(
                                MR.strings.cloud_sync_onedrive_sign_in,
                            )
                            else -> stringResource(MR.strings.cloud_sync_not_configured)
                        },
                        enabled = cloudSettingsAvailable && !testingConnection,
                        onClick = {
                            if (storage == CloudSyncStorage.OneDrive && !oneDriveConnected) {
                                signInToOneDrive()
                            } else {
                                showLoginDialog = true
                            }
                        },
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
                Preference.PreferenceItem.CustomPreference(title = categoryPickerTitle) {
                    val names = categoryEntries.filterKeys { it in participatingIds }.values
                    MultiSelectListPreferenceWidget(
                        values = participatingIds,
                        title = categoryPickerTitle,
                        subtitle = names.takeIf { it.isNotEmpty() }?.joinToString() ?: stringResource(MR.strings.none),
                        icon = null,
                        entries = categoryEntries,
                        onValuesChange = libraryCloudSync::setParticipatingCategories,
                    )
                },
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadPreferences.cloudSyncAutomaticPaused(),
                    title = stringResource(MR.strings.cloud_sync_auto_pause),
                    subtitle = stringResource(MR.strings.cloud_sync_auto_pause_summary),
                    visible = autoSyncAvailable && participatingIds.isNotEmpty(),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadPreferences.cloudSyncPreferRemoteDownloads(),
                    title = stringResource(MR.strings.cloud_sync_prefer_remote_downloads),
                    subtitle = stringResource(MR.strings.cloud_sync_prefer_remote_downloads_summary),
                ),
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
                Preference.PreferenceItem.SliderPreference(
                    preference = downloadPreferences.parallelCloudUploadLimit(),
                    valueRange = 1..5,
                    title = stringResource(MR.strings.pref_cloud_sync_concurrent_uploads),
                    visible = destinationAvailable,
                ),
            ),
        )
    }

    @Composable
    private fun OneDriveAccountDialog(
        busy: Boolean,
        onDismiss: () -> Unit,
        onSignIn: () -> Unit,
        onDisconnect: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(MR.strings.cloud_sync_onedrive_connection)) },
            text = { Text(stringResource(MR.strings.cloud_sync_connection_success)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = onSignIn) {
                    Text(stringResource(MR.strings.cloud_sync_onedrive_reconnect))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = onDisconnect) {
                    Text(stringResource(MR.strings.cloud_sync_onedrive_disconnect))
                }
            },
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
            modifier = Modifier.alpha(if (enabled) 1f else DISABLED_PREFERENCE_ALPHA),
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
            modifier = Modifier.alpha(if (enabled) 1f else DISABLED_PREFERENCE_ALPHA),
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
}

private const val DISABLED_PREFERENCE_ALPHA = 0.38f
