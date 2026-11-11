package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.MangaCloudSyncActions
import eu.kanade.presentation.manga.MangaScreen
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.manga.components.LocalMangaCoverSyncStatuses
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.manga.components.SetIntervalDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.data.download.CloudSyncStatus
import eu.kanade.tachiyomi.data.download.cloudCoverStatus
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackInfoDialogHomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.feature.migration.dialog.MigrateMangaDialog
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

class MangaScreen(
    private val mangaId: Long,
    val fromSource: Boolean = false,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val viewModel =
            assistedMetroViewModel<MangaViewModel, MangaViewModel.Factory> {
                create(mangaId = mangaId, isFromSource = fromSource)
            }

        val state by viewModel.state.collectAsStateWithLifecycle()

        if (state is MangaViewModel.State.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as MangaViewModel.State.Success
        val isHttpSource = successState.source is HttpSource
        val cloudSyncAvailable by viewModel.cloudSyncAvailable.collectAsStateWithLifecycle(initialValue = false)
        val canSyncChapters = isHttpSource && cloudSyncAvailable
        val cloudTransfers by viewModel.cloudTransfers.collectAsStateWithLifecycle(initialValue = emptyMap())
        val cloudChapterStatuses = remember(cloudTransfers) {
            cloudTransfers.mapNotNull { (id, state) ->
                val status = when (state) {
                    Download.State.QUEUE -> CloudSyncStatus.Waiting
                    Download.State.DOWNLOADING, Download.State.UPLOADING ->
                        CloudSyncStatus.Syncing
                    Download.State.ERROR -> CloudSyncStatus.Error
                    Download.State.PAUSED -> CloudSyncStatus.Paused
                    Download.State.NOT_DOWNLOADED, Download.State.DOWNLOADED -> null
                }
                status?.let { id to it }
            }.toMap()
        }
        val allChaptersSynced = successState.chapters.isNotEmpty() && successState.chapters.all { it.isUploadedToCloud }
        val cloudStatus = if (allChaptersSynced) {
            CloudSyncStatus.Complete
        } else {
            cloudChapterStatuses.values.minByOrNull { it.ordinal }
        }
        var showCloudSyncConfirmation by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(successState.manga, viewModel.source) {
            if (isHttpSource) {
                try {
                    withContext(Dispatchers.IO) {
                        assistUrl = getMangaUrl(viewModel.manga, viewModel.source)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to get manga URL" }
                }
            }
        }

        val coverStatuses = if (canSyncChapters) {
            mapOf(
                successState.manga.id to cloudCoverStatus(
                    synced = allChaptersSynced,
                    local = successState.chapters.any { it.downloadState == Download.State.DOWNLOADED },
                    running = cloudChapterStatuses.containsValue(CloudSyncStatus.Syncing),
                ),
            )
        } else {
            emptyMap()
        }
        CompositionLocalProvider(LocalMangaCoverSyncStatuses provides coverStatuses) {
            MangaScreen(
                state = successState,
                snackbarHostState = viewModel.snackbarHostState,
                nextUpdate = successState.manga.expectedNextUpdate,
                isTabletUi = isTabletUi(),
                chapterSwipeStartAction = viewModel.chapterSwipeStartAction,
                chapterSwipeEndAction = viewModel.chapterSwipeEndAction,
                navigateUp = navigator::pop,
                onChapterClicked = { openChapter(context, it) },
                onDownloadChapter = viewModel::runChapterDownloadActions.takeIf {
                    !successState.source.isLocalOrStub()
                },
                downloadProgressProvider = viewModel::getDownloadProgress,
                onAddToLibraryClicked = {
                    if (successState.manga.favorite) {
                        viewModel.showRemoveFromLibraryDialog()
                    } else {
                        viewModel.toggleFavorite()
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onWebViewClicked = {
                    openMangaInWebView(
                        navigator,
                        viewModel.manga,
                        viewModel.source,
                    )
                }.takeIf { isHttpSource },
                onWebViewLongClicked = {
                    copyMangaUrl(
                        context,
                        viewModel.manga,
                        viewModel.source,
                    )
                }.takeIf { isHttpSource },
                onTrackingClicked = {
                    if (!successState.hasLoggedInTrackers) {
                        navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                    } else {
                        viewModel.showTrackDialog()
                    }
                },
                onTagSearch = { scope.launch { performGenreSearch(navigator, it, viewModel.source!!) } },
                onFilterButtonClicked = viewModel::showSettingsDialog,
                onRefresh = viewModel::fetchAllFromSource,
                onContinueReading = { continueReading(context, viewModel.getNextUnreadChapter()) },
                onSearch = { query, global -> scope.launch { performSearch(navigator, query, global) } },
                onCoverClicked = viewModel::showCoverDialog,
                onShareClicked = { shareManga(context, viewModel.manga, viewModel.source) }.takeIf { isHttpSource },
                onDownloadActionClicked = viewModel::runDownloadAction.takeIf { !successState.source.isLocalOrStub() },
                cloudSync = if (canSyncChapters) {
                    MangaCloudSyncActions(
                        status = cloudStatus,
                        chapterStatuses = cloudChapterStatuses,
                        onSync = { showCloudSyncConfirmation = true },
                        onSyncChapter = { viewModel.runCloudSyncAction(listOf(it.chapter)) },
                        onSyncSelected = { selected ->
                            viewModel.runCloudSyncAction(selected.map { it.chapter })
                            viewModel.toggleAllSelection(false)
                        },
                        onReleaseStorage = viewModel::releaseCloudStorage,
                    )
                } else {
                    null
                },
                onEditCategoryClicked = viewModel::showChangeCategoryDialog.takeIf { successState.manga.favorite },
                onEditFetchIntervalClicked = viewModel::showSetFetchIntervalDialog.takeIf {
                    successState.manga.favorite
                },
                onMigrateClicked = {
                    navigator.push(MigrationConfigScreen(successState.manga.id))
                }.takeIf { successState.manga.favorite },
                onEditNotesClicked = { navigator.push(MangaNotesScreen(manga = successState.manga)) },
                onMultiBookmarkClicked = viewModel::bookmarkChapters,
                onMultiMarkAsReadClicked = viewModel::markChaptersRead,
                onMarkPreviousAsReadClicked = viewModel::markPreviousChapterRead,
                onMultiDeleteClicked = viewModel::showDeleteChapterDialog,
                onChapterSwipe = viewModel::chapterSwipe,
                onChapterSelected = viewModel::toggleSelection,
                onAllChapterSelected = viewModel::toggleAllSelection,
                onInvertSelection = viewModel::invertSelection,
            )
        }

        if (canSyncChapters && showCloudSyncConfirmation) {
            AlertDialog(
                onDismissRequest = { showCloudSyncConfirmation = false },
                title = { Text(stringResource(MR.strings.cloud_sync)) },
                text = { Text(stringResource(MR.strings.cloud_sync_manga_confirmation)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCloudSyncConfirmation = false
                            viewModel.runCloudSyncAction()
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

        var showScanlatorsDialog by remember { mutableStateOf(false) }

        val onDismissRequest = { viewModel.dismissDialog() }
        when (val dialog = successState.dialog) {
            null -> {}
            MangaViewModel.Dialog.RemoveFromLibrary -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        if (successState.manga.favorite) viewModel.toggleFavorite()
                    },
                    mangaToRemove = successState.manga,
                    onConfirmCloudDelete = viewModel::removeFromLibraryAndRemote.takeIf { canSyncChapters },
                )
            }
            is MangaViewModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        viewModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                    },
                )
            }
            is MangaViewModel.Dialog.DeleteChapters -> {
                DeleteChaptersDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.toggleAllSelection(false)
                        viewModel.deleteChapters(dialog.chapters)
                    },
                )
            }

            is MangaViewModel.Dialog.DuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.showMigrateDialog(it) },
                )
            }

            is MangaViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            MangaViewModel.Dialog.SettingsSheet -> ChapterSettingsDialog(
                onDismissRequest = onDismissRequest,
                manga = successState.manga,
                onDownloadFilterChanged = viewModel::setDownloadedFilter,
                onUnreadFilterChanged = viewModel::setUnreadFilter,
                onBookmarkedFilterChanged = viewModel::setBookmarkedFilter,
                onSortModeChanged = viewModel::setSorting,
                onDisplayModeChanged = viewModel::setDisplayMode,
                onSetAsDefault = viewModel::setCurrentSettingsAsDefault,
                onResetToDefault = viewModel::resetToDefaultSettings,
                scanlatorFilterActive = successState.scanlatorFilterActive,
                onScanlatorFilterClicked = { showScanlatorsDialog = true },
            )
            MangaViewModel.Dialog.TrackSheet -> {
                NavigatorAdaptiveSheet(
                    screen = TrackInfoDialogHomeScreen(
                        mangaId = successState.manga.id,
                        mangaTitle = successState.manga.title,
                        sourceId = successState.source.id,
                    ),
                    enableSwipeDismiss = { it.lastItem is TrackInfoDialogHomeScreen },
                    onDismissRequest = onDismissRequest,
                )
            }
            MangaViewModel.Dialog.FullCover -> {
                val vm =
                    assistedMetroViewModel<MangaCoverViewModel, MangaCoverViewModel.Factory> {
                        create(mangaId = mangaId)
                    }
                val manga by vm.state.collectAsStateWithLifecycle()
                if (manga != null) {
                    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
                        if (it == null) return@rememberLauncherForActivityResult
                        vm.editCover(context, it)
                    }
                    MangaCoverDialog(
                        manga = manga!!,
                        snackbarHostState = vm.snackbarHostState,
                        isCustomCover = remember(manga) { manga!!.hasCustomCover() },
                        onShareClick = { vm.shareCover(context) },
                        onSaveClick = { vm.saveCover(context) },
                        onEditClick = {
                            when (it) {
                                EditCoverAction.EDIT -> getContent.launch("image/*")
                                EditCoverAction.DELETE -> vm.deleteCustomCover(context)
                                EditCoverAction.ADD_TO_LIBRARY -> vm.addToLibraryWarning(context)
                            }
                        },
                        onDismissRequest = onDismissRequest,
                    )
                } else {
                    LoadingScreen(Modifier.systemBarsPadding())
                }
            }
            is MangaViewModel.Dialog.SetFetchInterval -> {
                SetIntervalDialog(
                    interval = dialog.manga.fetchInterval,
                    nextUpdate = dialog.manga.expectedNextUpdate,
                    onDismissRequest = onDismissRequest,
                    onValueChanged = { interval: Int -> viewModel.setFetchInterval(dialog.manga, interval) }
                        .takeIf { viewModel.isUpdateIntervalEnabled },
                )
            }
        }

        if (showScanlatorsDialog) {
            ScanlatorFilterDialog(
                availableScanlators = successState.availableScanlators,
                excludedScanlators = successState.excludedScanlators,
                onDismissRequest = { showScanlatorsDialog = false },
                onConfirm = viewModel::setExcludedScanlators,
            )
        }
    }

    private fun continueReading(context: Context, unreadChapter: Chapter?) {
        if (unreadChapter != null) openChapter(context, unreadChapter)
    }

    private fun openChapter(context: Context, chapter: Chapter) {
        context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
    }

    private fun getMangaUrl(manga_: Manga?, source_: Source?): String? {
        val manga = manga_ ?: return null
        val source = source_ as? HttpSource ?: return null

        return try {
            source.getMangaUrl(manga.toSManga())
        } catch (e: Exception) {
            null
        }
    }

    private fun openMangaInWebView(navigator: Navigator, manga_: Manga?, source_: Source?) {
        getMangaUrl(manga_, source_)?.let { url ->
            navigator.push(
                WebViewScreen(
                    url = url,
                    initialTitle = manga_?.title,
                    sourceId = source_?.id,
                ),
            )
        }
    }

    private fun shareManga(context: Context, manga_: Manga?, source_: Source?) {
        try {
            getMangaUrl(manga_, source_)?.let { url ->
                val intent = url.toUri().toShareIntent(context, type = "text/plain")
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    private suspend fun performSearch(navigator: Navigator, query: String, global: Boolean) {
        if (global) {
            navigator.push(GlobalSearchScreen(query))
            return
        }

        if (navigator.size < 2) {
            return
        }

        when (val previousController = navigator.items[navigator.size - 2]) {
            is HomeScreen -> {
                navigator.pop()
                previousController.search(query)
            }
            is BrowseSourceScreen -> {
                navigator.pop()
                previousController.search(query)
            }
        }
    }

    /**
     * Performs a genre search using the provided genre name.
     *
     * @param genreName the search genre to the parent controller
     */
    private suspend fun performGenreSearch(navigator: Navigator, genreName: String, source: Source) {
        if (navigator.size < 2) {
            return
        }

        val previousController = navigator.items[navigator.size - 2]
        if (previousController is BrowseSourceScreen && source is HttpSource) {
            navigator.pop()
            previousController.searchGenre(genreName)
        } else {
            performSearch(navigator, genreName, global = false)
        }
    }

    /**
     * Copy Manga URL to Clipboard
     */
    private fun copyMangaUrl(context: Context, manga_: Manga?, source_: Source?) {
        val manga = manga_ ?: return
        val source = source_ as? HttpSource ?: return
        val url = source.getMangaUrl(manga.toSManga())
        context.copyToClipboard(url, url)
    }
}
