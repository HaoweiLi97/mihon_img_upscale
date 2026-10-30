package eu.kanade.tachiyomi.ui.browse.source.local

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.Image
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.source.local.image.LocalThumbnailLoader
import java.io.File

class LocalBrowserScreen : Screen() {
    @Composable
    override fun Content() {
        val viewModel = metroViewModel<LocalBrowserViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val thumbnails by viewModel.preferences.localBrowserThumbnails.collectAsState()
        val columns by viewModel.preferences.localBrowserColumns.collectAsState()
        val sort by viewModel.preferences.localBrowserSort.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val snackbar = remember { SnackbarHostState() }
        var query by rememberSaveable(state.rootKey, state.path) { mutableStateOf("") }
        val scrollStates = rememberSaveableStateHolder()
        val entries = remember(state.entries, query, sort) {
            val matching = state.entries.filter { it.name.contains(query, ignoreCase = true) }
            if (sort == 1) matching.sortedWith(compareBy({ !it.directory }, { -it.modified })) else matching
        }
        val images = state.entries.count { it.image }
        val openError = stringResource(MR.strings.local_browser_open_error)

        LaunchedEffect(viewModel) {
            viewModel.events.collect { event ->
                when (event) {
                    is LocalBrowserViewModel.Event.Read -> context.startActivity(
                        ReaderActivity.newIntent(context, event.mangaId, event.chapterId, event.pageIndex),
                    )
                    is LocalBrowserViewModel.Event.Details -> navigator.push(MangaScreen(event.mangaId, true))
                    LocalBrowserViewModel.Event.OpenError -> snackbar.showSnackbar(openError)
                }
            }
        }

        BackHandler(enabled = state.path.isNotEmpty()) { viewModel.up() }

        Scaffold(
            topBar = {
                Column {
                    AppBar(
                        title = stringResource(MR.strings.local_source),
                        subtitle = state.path.lastOrNull() ?: state.rootName,
                        navigateUp = { if (state.path.isNotEmpty()) viewModel.up() else navigator.pop() },
                        actions = {
                            IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                                Icon(MaterialSymbols.Rounded.Refresh, stringResource(MR.strings.local_browser_refresh))
                            }
                            IconButton(onClick = { navigator.push(LocalBrowserSettingsScreen()) }) {
                                Icon(MaterialSymbols.Rounded.Settings, stringResource(MR.strings.action_settings))
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = state.path.isEmpty(),
                            onClick = { viewModel.goTo(0) },
                            label = { Text(state.rootName.ifBlank { stringResource(MR.strings.local_browser_root) }) },
                        )
                        state.path.forEachIndexed { index, name ->
                            Text("/")
                            FilterChip(
                                selected = index == state.path.lastIndex,
                                onClick = { viewModel.goTo(index + 1) },
                                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(MR.strings.local_browser_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    if (images > 0 && !state.loading && !state.error) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(MR.strings.local_browser_image_count, images))
                            TextButton(onClick = { viewModel.open() }, enabled = !state.opening) {
                                Text(stringResource(MR.strings.local_browser_read_images))
                            }
                        }
                    }
                    if (state.opening) LinearProgressIndicator(Modifier.fillMaxWidth())
                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val gridColumns = if (columns == 0) maxOf(3, (maxWidth / 180.dp).toInt()) else columns.coerceIn(2, 6)
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(MR.strings.local_browser_directory_error))
                        Button(onClick = { navigator.push(LocalBrowserSettingsScreen()) }) {
                            Text(stringResource(MR.strings.local_browser_directory))
                        }
                        if (state.path.isNotEmpty()) {
                            TextButton(onClick = viewModel::up) {
                                Text(stringResource(MR.strings.local_browser_parent))
                            }
                        }
                        TextButton(onClick = viewModel::refresh) { Text(stringResource(MR.strings.action_retry)) }
                    }
                    else -> {
                        scrollStates.SaveableStateProvider(state.rootKey + "/" + state.path.joinToString("/")) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(gridColumns),
                                state = rememberLazyGridState(),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                if (state.path.isNotEmpty()) {
                                    item(key = "parent") {
                                        LocalTile(
                                            name = stringResource(MR.strings.local_browser_parent),
                                            kind = stringResource(MR.strings.local_browser_parent_hint),
                                            icon = {
                                                Icon(MaterialSymbols.Rounded.ArrowUpward, null, Modifier.size(48.dp))
                                            },
                                            onClick = viewModel::up,
                                        )
                                    }
                                }
                                items(entries, key = { it.key }) { entry ->
                                    val kind = when {
                                        entry.directory -> stringResource(MR.strings.local_browser_folder)
                                        entry.image -> stringResource(MR.strings.local_browser_image)
                                        else -> entry.name.substringAfterLast('.').uppercase()
                                    }
                                    LocalTile(
                                        name = entry.name,
                                        kind = kind,
                                        icon = {
                                            Icon(
                                                when {
                                                    entry.directory -> MaterialSymbols.Rounded.Folder
                                                    entry.image -> MaterialSymbols.Rounded.Image
                                                    else -> MaterialSymbols.Rounded.LocalLibrary
                                                },
                                                null,
                                                Modifier.size(48.dp),
                                            )
                                        },
                                        thumbnail = {
                                            if (thumbnails) LocalThumbnail(entry, viewModel.thumbnails)
                                        },
                                        onClick = {
                                            if (entry.directory) viewModel.enter(entry) else viewModel.open(entry)
                                        },
                                        onLongClick = {
                                            if (entry.directory) {
                                                viewModel.enter(
                                                    entry,
                                                )
                                            } else {
                                                viewModel.open(entry, details = true)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        if (entries.isEmpty()) {
                            Text(
                                text = stringResource(
                                    if (query.isBlank()) {
                                        MR.strings.local_browser_empty
                                    } else {
                                        MR.strings.local_browser_no_results
                                    },
                                ),
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalThumbnail(entry: LocalBrowserViewModel.Entry, loader: LocalThumbnailLoader) {
    val thumbnail by produceState<File?>(null, entry.key, entry.modified) {
        value = withContext(Dispatchers.IO) { loader.load(entry.file) }
    }
    thumbnail?.let {
        AsyncImage(
            model = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun LocalTile(
    name: String,
    kind: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    thumbnail: @Composable () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.clip(MaterialTheme.shapes.medium).combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Card {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                icon()
                thumbnail()
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            text = kind,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
