package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseSourcePageDialog
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource
import tachiyomi.core.common.i18n.stringResource as contextStringResource

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel { BrowseSourceScreenModel(sourceId, listingQuery) }
        val state by screenModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> screenModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        if (screenModel.source is StubSource) {
            MissingSourceScreen(
                source = screenModel.source,
                navigateUp = navigateUp,
            )
            return
        }

        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }
        val mangaList = screenModel.mangaPagerFlowFlow.collectAsLazyPagingItems()
        val loadedMangas = mangaList.itemSnapshotList.items.map { it.value }
        val loadedMangaIds = loadedMangas.map { it.id }
        val selectedMangas = loadedMangas.filter { it.id in state.selectedMangaIds }
        var showDownloadConfirmation by rememberSaveable { mutableStateOf(false) }
        var showCloudSyncConfirmation by rememberSaveable { mutableStateOf(false) }
        var showPageDialog by rememberSaveable { mutableStateOf(false) }

        BackHandler(enabled = state.selectionMode) {
            screenModel.clearSelection()
        }

        if (showDownloadConfirmation) {
            AlertDialog(
                onDismissRequest = { showDownloadConfirmation = false },
                title = { Text(stringResource(MR.strings.action_download)) },
                text = {
                    Text(
                        stringResource(
                            MR.strings.download_selected_manga_confirmation,
                            selectedMangas.size,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDownloadConfirmation = false
                            scope.launch {
                                val result = screenModel.downloadMangas(selectedMangas)
                                snackbarHostState.showSnackbar(
                                    message = context.contextStringResource(
                                        MR.strings.download_selected_manga_result,
                                        result.successful,
                                        result.failed,
                                    ),
                                )
                                screenModel.clearSelection()
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.action_download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDownloadConfirmation = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        if (showCloudSyncConfirmation) {
            AlertDialog(
                onDismissRequest = { showCloudSyncConfirmation = false },
                title = { Text(stringResource(MR.strings.cloud_sync)) },
                text = {
                    Text(
                        stringResource(
                            MR.strings.cloud_sync_selected_manga_confirmation,
                            selectedMangas.size,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCloudSyncConfirmation = false
                            scope.launch {
                                val result = screenModel.cloudSyncMangas(selectedMangas)
                                snackbarHostState.showSnackbar(
                                    message = context.contextStringResource(
                                        MR.strings.cloud_sync_selected_manga_result,
                                        result.queued,
                                        result.skipped,
                                        result.failed,
                                    ),
                                )
                                screenModel.clearSelection()
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.cloud_sync))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCloudSyncConfirmation = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        if (showPageDialog) {
            BrowseSourcePageDialog(
                currentPage = state.browsePage,
                onDismissRequest = { showPageDialog = false },
                onConfirm = screenModel::jumpToPage,
            )
        }

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        val onWebViewClick = f@{
            val source = screenModel.source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = source.baseUrl,
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        LaunchedEffect(screenModel.source) {
            assistUrl = (screenModel.source as? HttpSource)?.baseUrl
        }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    BrowseSourceToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = screenModel::setToolbarQuery,
                        source = screenModel.source,
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                        onSearch = screenModel::search,
                        selectionMode = state.selectionMode,
                        selectionCount = state.selectedMangaIds.size,
                        onEnterSelectionMode = screenModel::enterSelectionMode,
                        onCancelSelectionMode = screenModel::clearSelection,
                        onSelectAll = { screenModel.selectAll(loadedMangaIds) },
                        onInvertSelection = { screenModel.invertSelection(loadedMangaIds) },
                    )

                    if (!state.selectionMode) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = MaterialTheme.padding.small),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                            FilterChip(
                                selected = state.listing == Listing.Popular,
                                onClick = {
                                    screenModel.resetFilters()
                                    screenModel.setListing(Listing.Popular)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Favorite,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.popular))
                                },
                            )
                            if ((screenModel.source as CatalogueSource).supportsLatest) {
                                FilterChip(
                                    selected = state.listing == Listing.Latest,
                                    onClick = {
                                        screenModel.resetFilters()
                                        screenModel.setListing(Listing.Latest)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.NewReleases,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    label = {
                                        Text(text = stringResource(MR.strings.latest))
                                    },
                                )
                            }
                            if (state.filters.isNotEmpty()) {
                                FilterChip(
                                    selected = state.listing is Listing.Search,
                                    onClick = screenModel::openFilterSheet,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.FilterList,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    label = {
                                        Text(text = stringResource(MR.strings.action_filter))
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                }
            },
            bottomBar = {
                BrowseSourceSelectionBottomBar(
                    visible = state.selectionMode,
                    canAdd = selectedMangas.any { !it.favorite },
                    canDownload = selectedMangas.isNotEmpty() && screenModel.source is HttpSource,
                    canCloudSync = selectedMangas.isNotEmpty() && screenModel.isCloudSyncAvailable,
                    onAdd = { screenModel.addFavorites(selectedMangas) },
                    onDownload = { showDownloadConfirmation = true },
                    onCloudSync = { showCloudSyncConfirmation = true },
                )
            },
            floatingActionButton = {
                if (!state.selectionMode && screenModel.source !is LocalSource) {
                    SmallExtendedFloatingActionButton(
                        text = {
                            Text(
                                text = stringResource(
                                    MR.strings.browse_page_number,
                                    state.browsePage,
                                ),
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Numbers,
                                contentDescription = null,
                            )
                        },
                        onClick = { showPageDialog = true },
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            BrowseSourceContent(
                source = screenModel.source,
                mangaList = mangaList,
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = screenModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = onHelpClick,
                selectedMangaIds = state.selectedMangaIds,
                onMangaClick = { index, manga ->
                    if (state.selectionMode) {
                        screenModel.toggleSelection(index, manga.id)
                    } else {
                        navigator.push(MangaScreen(manga.id, true))
                    }
                },
                onMangaLongClick = { index, manga ->
                    if (state.selectionMode) {
                        screenModel.selectRange(index, loadedMangaIds)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else {
                        scope.launchIO {
                            val duplicates = screenModel.getDuplicateLibraryManga(manga)
                            when {
                                manga.favorite -> screenModel.setDialog(
                                    BrowseSourceScreenModel.Dialog.RemoveManga(manga),
                                )
                                duplicates.isNotEmpty() -> screenModel.setDialog(
                                    BrowseSourceScreenModel.Dialog.AddDuplicateManga(manga, duplicates),
                                )
                                else -> screenModel.addFavorite(manga)
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
            )
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                )
            }
            is BrowseSourceScreenModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.setDialog(BrowseSourceScreenModel.Dialog.Migrate(dialog.manga, it)) },
                )
            }

            is BrowseSourceScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceScreenModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
                )
            }
            is BrowseSourceScreenModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.changeMangaFavorite(dialog.manga)
                        screenModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }
            is BrowseSourceScreenModel.Dialog.ChangeMangaCategories -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.addFavorites(dialog.mangas, include)
                    },
                )
            }
            else -> {}
        }

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> screenModel.searchGenre(it.txt)
                        is SearchType.Text -> screenModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}

@Composable
private fun BrowseSourceSelectionBottomBar(
    visible: Boolean,
    canAdd: Boolean,
    canDownload: Boolean,
    canCloudSync: Boolean,
    onAdd: () -> Unit,
    onDownload: () -> Unit,
    onCloudSync: () -> Unit,
) {
    if (!visible) return

    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SelectionActionButton(
                title = stringResource(MR.strings.add_to_library),
                icon = Icons.Outlined.LibraryAdd,
                enabled = canAdd,
                onClick = onAdd,
            )
            SelectionActionButton(
                title = stringResource(MR.strings.action_download),
                icon = Icons.Outlined.Download,
                enabled = canDownload,
                onClick = onDownload,
            )
            SelectionActionButton(
                title = stringResource(MR.strings.cloud_sync),
                icon = Icons.Outlined.CloudUpload,
                enabled = canCloudSync,
                onClick = onCloudSync,
            )
        }
    }
}

@Composable
private fun RowScope.SelectionActionButton(
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null)
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
