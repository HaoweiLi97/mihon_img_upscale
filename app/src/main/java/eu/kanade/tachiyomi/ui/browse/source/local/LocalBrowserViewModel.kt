package eu.kanade.tachiyomi.ui.browse.source.local

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalThumbnailLoader
import tachiyomi.source.local.io.LocalBook
import tachiyomi.source.local.io.LocalSourceFileSystem
import kotlin.coroutines.coroutineContext

@AssistedInject
class LocalBrowserViewModel(
    @Assisted private val savedState: SavedStateHandle,
    private val fileSystem: LocalSourceFileSystem,
    val preferences: StoragePreferences,
    val thumbnails: LocalThumbnailLoader,
    private val localSource: LocalSource,
    private val mangaRepository: MangaRepository,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val getChapters: GetChaptersByMangaId,
) : ViewModel() {
    @AssistedFactory
    @ViewModelAssistedFactoryKey(LocalBrowserViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): LocalBrowserViewModel = create(extras.createSavedStateHandle())

        fun create(@Assisted savedState: SavedStateHandle): LocalBrowserViewModel
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var loadJob: Job? = null
    private var path = savedState.get<ArrayList<String>>("local_browser_path")?.toList().orEmpty()

    init {
        viewModelScope.launch {
            combine(
                preferences.localComicDirectory.changes(),
                preferences.baseStorageDirectory.changes(),
            ) { local, base -> local.ifBlank { base } }.collect { identity ->
                if (savedState.get<String>("local_browser_root") != identity) path = emptyList()
                savedState["local_browser_root"] = identity
                refresh()
            }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        mutableState.update { it.copy(loading = true, error = false, entries = emptyList()) }
        val requestedPath = path.toList()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = fileSystem.getBrowserDirectory()
                    ?.takeIf { it.isDirectory && it.exists() && it.canRead() }
                    ?: error("Local directory is unavailable")
                var directory = base
                for (name in requestedPath) {
                    directory = directory.findFile(name)?.takeIf { it.isDirectory && it.canRead() }
                        ?: error("Local subdirectory is unavailable")
                }
                val files = directory.listFiles()?.toList() ?: error("Cannot list local directory")
                val entries = LocalBook.visibleFiles(files).map { file ->
                    Entry(
                        file = file,
                        name = file.name.orEmpty(),
                        directory = file.isDirectory,
                        image = LocalBook.isImage(file),
                        modified = file.lastModified(),
                        key = file.uri.toString(),
                    )
                }
                coroutineContext.ensureActive()
                withContext(Dispatchers.Main) { savedState["local_browser_path"] = ArrayList(requestedPath) }
                mutableState.update {
                    it.copy(
                        loading = false,
                        error = false,
                        directory = directory,
                        rootName = base.name.orEmpty(),
                        rootKey = base.uri.toString(),
                        path = requestedPath,
                        entries = entries,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = true, path = requestedPath) }
            }
        }
    }

    fun enter(entry: Entry) {
        if (!entry.directory || state.value.loading) return
        path = path + entry.name
        refresh()
    }

    fun up() = goTo((path.size - 1).coerceAtLeast(0))

    fun goTo(depth: Int) {
        path = path.take(depth.coerceAtLeast(0))
        refresh()
    }

    fun open(entry: Entry? = null, details: Boolean = false) {
        val current = state.value
        if (current.opening || current.loading) return
        val file = when {
            entry == null || entry.image -> current.directory
            else -> entry.file
        } ?: return
        // The full image list, independent of the search/sort UI, defines reader page indices.
        val imageIndex = if (entry?.image == true) {
            current.entries.filter { it.image }.indexOfFirst { it.key == entry.key }.takeIf { it >= 0 }
        } else {
            null
        }
        mutableState.update { it.copy(opening = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val remote = SManga.create().apply {
                    url = LocalBook.url(file)
                    title = (if (file.isDirectory) file.name else file.nameWithoutExtension).orEmpty()
                }
                val manga = mangaRepository.insertNetworkManga(listOf(remote.toDomainManga(LocalSource.ID))).single()
                if (details) {
                    eventChannel.send(Event.Details(manga.id))
                } else {
                    updateMangaFromRemote(localSource, manga, fetchDetails = true, fetchChapters = true).getOrThrow()
                    val chapter = getChapters.await(manga.id).single()
                    eventChannel.send(Event.Read(manga.id, chapter.id, imageIndex))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                eventChannel.send(Event.OpenError)
            } finally {
                mutableState.update { it.copy(opening = false) }
            }
        }
    }

    data class Entry(
        val file: UniFile,
        val name: String,
        val directory: Boolean,
        val image: Boolean,
        val modified: Long,
        val key: String,
    )

    data class State(
        val loading: Boolean = true,
        val opening: Boolean = false,
        val error: Boolean = false,
        val directory: UniFile? = null,
        val rootName: String = "",
        val rootKey: String = "",
        val path: List<String> = emptyList(),
        val entries: List<Entry> = emptyList(),
    )

    sealed interface Event {
        data class Read(val mangaId: Long, val chapterId: Long, val pageIndex: Int?) : Event
        data class Details(val mangaId: Long) : Event
        data object OpenError : Event
    }
}
