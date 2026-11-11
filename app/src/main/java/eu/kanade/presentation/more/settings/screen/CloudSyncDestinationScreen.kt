@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.CloudSyncConfig
import eu.kanade.tachiyomi.data.download.CloudSyncDirectory
import eu.kanade.tachiyomi.data.download.CloudSyncService
import eu.kanade.tachiyomi.data.download.normalizePath
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CloudSyncDestinationScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val downloadPreferences = remember { Injekt.get<DownloadPreferences>() }
        val cloudSyncService = remember { Injekt.get<CloudSyncService>() }

        val url by downloadPreferences.cloudSyncUrl().collectAsState()
        val username by downloadPreferences.cloudSyncUsername().collectAsState()
        val password by downloadPreferences.cloudSyncPassword().collectAsState()
        val savedDestination by downloadPreferences.cloudSyncDestination().collectAsState()
        val config = remember(url, username, password) {
            CloudSyncConfig(
                url = url,
                username = username,
                password = password,
            )
        }

        key(config) {
            val scope = rememberCoroutineScope()
            var selectedPath by remember(savedDestination) {
                mutableStateOf(savedDestination.ifBlank { "/" })
            }
            var expandedPaths by remember { mutableStateOf(setOf("/")) }
            var loadingPaths by remember { mutableStateOf(setOf<String>()) }
            var childrenByPath by remember { mutableStateOf(emptyMap<String, List<CloudSyncDirectory>>()) }

            fun load(path: String) {
                val normalizedPath = normalizePath(path)
                if (!config.isValid || normalizedPath in loadingPaths) return
                loadingPaths += normalizedPath
                scope.launch {
                    try {
                        childrenByPath += normalizedPath to cloudSyncService.listDirectories(config, normalizedPath)
                        expandedPaths += normalizedPath
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        error.message?.let { context.toast(it) }
                    } finally {
                        loadingPaths -= normalizedPath
                    }
                }
            }

            LaunchedEffect(config) {
                load("/")
            }

            Scaffold(
                topBar = {
                    AppBar(
                        title = stringResource(MR.strings.cloud_sync_destination),
                        navigateUp = navigator::pop,
                        actions = {
                            TextButton(
                                enabled = selectedPath.isNotBlank(),
                                onClick = {
                                    downloadPreferences.cloudSyncDestination().set(normalizePath(selectedPath))
                                    navigator.pop()
                                },
                            ) {
                                Text(text = stringResource(MR.strings.action_select))
                            }
                        },
                        scrollBehavior = it,
                    )
                },
            ) { contentPadding ->
                if (!config.isValid) {
                    Text(
                        text = stringResource(MR.strings.cloud_sync_not_configured),
                        modifier = Modifier.padding(contentPadding).padding(16.dp),
                    )
                    return@Scaffold
                }

                val rows = remember(childrenByPath, expandedPaths, loadingPaths) {
                    buildDirectoryRows(
                        root = CloudSyncDirectory(name = "/", path = "/"),
                        childrenByPath = childrenByPath,
                        expandedPaths = expandedPaths,
                        loadingPaths = loadingPaths,
                    )
                }

                LazyColumn(
                    contentPadding = PaddingValues(
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                ) {
                    items(rows, key = { it.directory.path }) { row ->
                        CloudSyncDirectoryListRow(
                            row = row,
                            selected = normalizePath(selectedPath) == row.directory.path,
                            expanded = row.loading || row.directory.path in expandedPaths,
                            onToggle = {
                                val path = row.directory.path
                                if (path in childrenByPath) {
                                    expandedPaths = if (path in expandedPaths) {
                                        expandedPaths - path
                                    } else {
                                        expandedPaths + path
                                    }
                                } else {
                                    load(path)
                                }
                            },
                            onSelect = {
                                selectedPath = row.directory.path
                                if (row.directory.path !in childrenByPath) {
                                    load(row.directory.path)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudSyncDirectoryListRow(
    row: CloudSyncDirectoryRow,
    selected: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(start = (row.depth * 24).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp).height(48.dp), contentAlignment = Alignment.Center) {
            if (row.loading) {
                LinearProgressIndicator(modifier = Modifier.width(24.dp).height(2.dp))
            } else {
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (expanded) MaterialSymbols.Rounded.KeyboardArrowDown else MaterialSymbols.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                    )
                }
            }
        }
        Icon(imageVector = MaterialSymbols.Rounded.Folder, contentDescription = null)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = row.directory.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(imageVector = MaterialSymbols.Rounded.Check, contentDescription = null)
        }
    }
}

private data class CloudSyncDirectoryRow(
    val directory: CloudSyncDirectory,
    val depth: Int,
    val loading: Boolean,
)

private fun buildDirectoryRows(
    root: CloudSyncDirectory,
    childrenByPath: Map<String, List<CloudSyncDirectory>>,
    expandedPaths: Set<String>,
    loadingPaths: Set<String>,
): List<CloudSyncDirectoryRow> {
    fun addRows(
        directory: CloudSyncDirectory,
        depth: Int,
        rows: MutableList<CloudSyncDirectoryRow>,
    ) {
        rows += CloudSyncDirectoryRow(
            directory = directory,
            depth = depth,
            loading = directory.path in loadingPaths,
        )
        if (directory.path !in expandedPaths) return

        val children = childrenByPath[directory.path].orEmpty()
        children.forEach { child ->
            addRows(child, depth + 1, rows)
        }
    }

    return buildList {
        addRows(root, 0, this)
    }
}
