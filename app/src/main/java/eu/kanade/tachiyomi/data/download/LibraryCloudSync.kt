package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
class LibraryCloudSync(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val preferences: DownloadPreferences,
    private val getLibraryManga: GetLibraryManga,
    private val getChapters: GetChaptersByMangaId,
) {
    init {
        preferences.migrateRemoteSyncScope()
    }

    private val rules = merge(
        preferences.remoteSyncCategories().changes(),
        preferences.cloudSyncAutomaticPaused().changes(),
        preferences.cloudSyncPausedCategories().changes(),
        preferences.saveChaptersAsCBZ.changes(),
        preferences.cloudSyncEnabled().changes(),
        preferences.cloudSyncUrl().changes(),
        preferences.cloudSyncUsername().changes(),
        preferences.cloudSyncPassword().changes(),
        preferences.cloudSyncDestination().changes(),
    ).map { currentRules() }.distinctUntilChanged()

    internal val selection = rules.flatMapLatest { rules ->
        if (!rules.hasScope) {
            flowOf(CloudSyncSelection(rules, emptyList()))
        } else {
            getLibraryManga.subscribe()
                .map { CloudSyncSelection(rules, it.filter(rules::includes)) }
        }
    }.distinctUntilChangedBy { it.rules to it.entries.map(LibraryManga::chapterVersion) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val scanLock = Mutex()

    internal val categoryStatuses by lazy {
        val manager = context.appGraph.downloadManager
        statuses(manager.queueState, manager.uploadQueueState)
            .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 60_000), emptyMap())
    }

    internal fun currentRules(): CloudSyncRules {
        val target = preferences.cloudSyncTargetId().takeIf {
            preferences.saveChaptersAsCBZ.get() && preferences.cloudSyncEnabled().get() &&
                CloudSyncConfig(
                    preferences.cloudSyncUrl().get(),
                    preferences.cloudSyncUsername().get(),
                    preferences.cloudSyncPassword().get(),
                ).isValid
        }
        return CloudSyncRules(
            categories = preferences.remoteSyncCategories().get().mapNotNull(String::toLongOrNull).toSet(),
            target = target,
            paused = preferences.cloudSyncAutomaticPaused().get(),
            pausedCategories = preferences.cloudSyncPausedCategories().get().mapNotNull(String::toLongOrNull).toSet(),
        )
    }

    internal fun setCategoryPaused(categoryId: Long, paused: Boolean) {
        val id = categoryId.toString()
        if (id !in preferences.remoteSyncCategories().get()) return
        preferences.cloudSyncPausedCategories().getAndSet { if (paused) it + id else it - id }
    }

    internal fun setParticipatingCategories(ids: Set<String>) {
        val categories = preferences.remoteSyncCategories()
        val previous = categories.get()
        val added = ids - previous
        val removed = previous - ids
        val paused = preferences.cloudSyncPausedCategories()
        if (added.isNotEmpty()) paused.getAndSet { it - added }
        categories.set(ids)
        if (removed.isNotEmpty()) paused.getAndSet { it - removed }
    }

    internal suspend fun releaseCategoryStorage(
        categoryId: Long,
    ): CloudStorageReleaseResult = withContext(Dispatchers.IO) {
        val rules = currentRules()
        val target = rules.target ?: return@withContext CloudStorageReleaseResult()
        val manager = context.appGraph.downloadManager
        getLibraryManga.await()
            .filter { categoryId in it.categories && !it.manga.isLocal() }
            .fold(CloudStorageReleaseResult()) { result, entry ->
                result + manager.releaseCloudStorage(entry.manga, target)
            }
    }

    internal fun hasConfiguredScope(): Boolean = preferences.remoteSyncCategories().isSet()

    internal suspend fun retryCategory(categoryId: Long) = withContext(Dispatchers.IO) {
        val graph = context.appGraph
        scanLock.withLock {
            val rules = currentRules()
            val target = rules.target ?: return@withLock
            if (!rules.active || categoryId !in rules.categories ||
                categoryId in rules.pausedCategories
            ) {
                return@withLock
            }
            val mangaIds = getLibraryManga.await().filter {
                categoryId in it.categories && rules.includes(it) && rules.isUnpaused(it)
            }.mapTo(HashSet()) { it.id }
            if (mangaIds.isEmpty()) return@withLock

            preferences.remoteSyncScanFailures().getAndSet { failures ->
                failures - mangaIds.map { "$target:$it" }.toSet()
            }
            graph.downloadManager.retryAutomaticCloudSync(mangaIds, target)
            scheduleScan(
                WorkManager.getInstance(context),
                networkConstraints(preferences.downloadOnlyOverWifi.get()),
                ExistingWorkPolicy.REPLACE,
            )
        }
    }

    internal suspend fun deleteRemoteManga(manga: Manga, onDeleted: suspend () -> Unit) = withContext(Dispatchers.IO) {
        scanLock.withLock {
            val target = currentRules().target
            val others = getLibraryManga.await().map { it.manga }.filterNot { it.id == manga.id || it.isLocal() }
            context.appGraph.downloadManager.deleteRemoteManga(manga, others) {
                preferences.remoteSyncScanFailures().getAndSet { it - "$target:${manga.id}" }
                onDeleted()
            }
        }
    }

    internal fun canTransfer(mangaId: Long, target: String): Boolean {
        if (preferences.cloudSyncAutomaticPaused().get()) return false
        val selected = selection.value ?: return false
        return selected.rules.target == target && !selected.rules.paused && mangaId in selected.activeMangaIds
    }

    fun start() {
        val workManager = WorkManager.getInstance(context)
        var previousSettings: Pair<CloudSyncRules, Boolean>? = null
        combine(selection.filterNotNull(), preferences.downloadOnlyOverWifi.changes()) { selected, wifiOnly ->
            selected.takeIf { it.rules.active }?.let {
                Triple(it.rules, it.entries.filter(it.rules::isUnpaused).map(LibraryManga::chapterVersion), wifiOnly)
            }
        }
            .distinctUntilChanged()
            .debounce(500.milliseconds)
            .onEach { request ->
                if (request == null) {
                    previousSettings = null
                    workManager.cancelUniqueWork(SCAN)
                    workManager.cancelUniqueWork(PERIODIC_SCAN)
                    return@onEach
                }

                val (rules, _, wifiOnly) = request
                val settings = rules to wifiOnly
                val previous = previousSettings
                val startImmediately = previous == null || previous.first.target != rules.target ||
                    previous.second != wifiOnly || !previous.first.activeCategories.containsAll(rules.activeCategories)
                val constraints = networkConstraints(wifiOnly)
                val policy = if (previous != null && previous != settings) {
                    ExistingWorkPolicy.REPLACE
                } else {
                    ExistingWorkPolicy.KEEP
                }
                scheduleScan(
                    workManager,
                    constraints,
                    policy,
                    delaySeconds = if (startImmediately) 0 else CHAPTER_CHANGE_SCAN_DELAY_SECONDS,
                )
                if (previous?.second != wifiOnly) {
                    workManager.enqueueUniquePeriodicWork(
                        PERIODIC_SCAN,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        PeriodicWorkRequestBuilder<LibraryCloudSyncWorker>(1, TimeUnit.HOURS)
                            .setInitialDelay(1, TimeUnit.HOURS)
                            .setConstraints(constraints)
                            .build(),
                    )
                }
                previousSettings = settings
            }
            .launchIn(scope)
    }

    internal suspend fun syncLibrary(): Unit = scanLock.withLock {
        val selected = selection.filterNotNull().first()
        val rules = selected.rules
        val target = rules.target ?: return@withLock
        if (!rules.active || currentRules() != rules) return@withLock
        val graph = context.appGraph
        val downloader = graph.downloadManager
        val entries = getLibraryManga.await().filter { rules.includes(it) && rules.isUnpaused(it) }
        val failures = preferences.remoteSyncScanFailures()
        for (entry in entries) {
            if (currentRules() != rules) return@withLock
            val manga = entry.manga
            val failureKey = "$target:${manga.id}"
            if (failureKey in failures.get()) continue
            try {
                val source = graph.sourceManager.get(manga.source) as? HttpSource
                if (source == null) {
                    failures.getAndSet { it + failureKey }
                    continue
                }
                var chapters = getChapters.await(manga.id, applyScanlatorFilter = true)
                if (chapters.isEmpty() && entry.chapterFetchedAt == null && getChapters.await(manga.id).isEmpty()) {
                    graph.updateMangaFromRemote(
                        source = source,
                        manga = manga,
                        fetchDetails = !manga.initialized,
                        fetchChapters = true,
                    ).getOrThrow()
                    chapters = getChapters.await(manga.id, applyScanlatorFilter = true)
                }
                val result = downloader.cloudSyncChapters(manga, chapters, autoStart = false, automaticTarget = target)
                if (result.failed > 0 && currentRules() == rules) failures.getAndSet { it + failureKey }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures.getAndSet { it + failureKey }
                logcat(LogPriority.WARN, error) { "Could not sync library manga ${manga.id}" }
            }
        }
        if (currentRules() == rules) {
            val hasDownloads = downloader.queueState.value.any {
                it.automaticSyncTarget == target && it.status == Download.State.QUEUE &&
                    canTransfer(it.manga.id, target)
            }
            if (hasDownloads) {
                downloader.startAutomaticDownloads()
            }
            val latest = getLibraryManga.await().filter { rules.includes(it) && rules.isUnpaused(it) }
                .map(LibraryManga::chapterVersion)
            if (latest != entries.map(LibraryManga::chapterVersion)) {
                scheduleScan(
                    WorkManager.getInstance(context),
                    networkConstraints(preferences.downloadOnlyOverWifi.get()),
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    delaySeconds = CHAPTER_CHANGE_SCAN_DELAY_SECONDS,
                )
            }
        }
    }

    internal fun statuses(
        downloads: StateFlow<List<Download>>,
        uploads: StateFlow<List<Download>>,
    ) = selection.flatMapLatest { selected ->
        if (selected == null || !selected.rules.hasScope) return@flatMapLatest flowOf(emptyMap())
        val rules = selected.rules
        val target = rules.target
        val categories = rules.categories
        if (rules.paused || target == null) {
            return@flatMapLatest flowOf(categories.associateWith { CloudSyncStatus.Paused })
        }
        val chapters = selected.entries.associate { entry ->
            val ids = getChapters.await(entry.id, applyScanlatorFilter = true).map { it.id }
            val waitingForChapterList = ids.isEmpty() && getChapters.await(entry.id).isEmpty()
            entry.id to (ids to waitingForChapterList)
        }
        val queues = combine(downloads, uploads) { pendingDownloads, pendingUploads ->
            pendingDownloads + pendingUploads
        }
            .flatMapLatest { queue ->
                if (queue.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(queue.map { item -> item.statusFlow.map { item.chapter.id to it } }) { states ->
                        states.toList().groupBy({ it.first }, { it.second })
                    }
                }
            }
        combine(
            preferences.cloudUploadedChapterIds().changes(),
            preferences.remoteSyncScanFailures().changes(),
            queues,
        ) { uploaded, failures, states ->
            val mangaStatuses = chapters.mapValues { (id, chapterList) ->
                val (ids, waitingForChapterList) = chapterList
                val pending = ids.filter { "$target:$it" !in uploaded }
                val blocked = pending.flatMap { states[it].orEmpty() }
                when {
                    (waitingForChapterList || pending.isNotEmpty()) && "$target:$id" in failures ->
                        CloudSyncStatus.Error
                    Download.State.ERROR in blocked ->
                        CloudSyncStatus.Error
                    Download.State.DOWNLOADING in blocked || Download.State.UPLOADING in blocked ->
                        CloudSyncStatus.Syncing
                    waitingForChapterList || pending.isNotEmpty() -> CloudSyncStatus.Waiting
                    else -> CloudSyncStatus.Complete
                }
            }
            val entriesByCategory = selected.entries.flatMap { entry ->
                rules.categoriesFor(entry).map { category -> category to mangaStatuses.getValue(entry.id) }
            }.groupBy({ it.first }, { it.second })
            categories.associateWith { category ->
                if (category in rules.pausedCategories) {
                    CloudSyncStatus.Paused
                } else {
                    entriesByCategory[category]?.minByOrNull { it.ordinal } ?: CloudSyncStatus.Complete
                }
            }
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    internal fun coverStatuses(manager: DownloadManager, cache: DownloadCache) = selection
        .map { it?.rules?.target }
        .distinctUntilChanged()
        .flatMapLatest { target ->
            if (target == null) return@flatMapLatest flowOf(emptyMap())
            val chaptersByManga = getLibraryManga.subscribe()
                .map { library -> library.filterNot { it.manga.isLocal() } }
                .distinctUntilChangedBy { it.map(LibraryManga::chapterVersion) }
                .map { library ->
                    library.associate { entry ->
                        entry.manga to getChapters.await(entry.id, applyScanlatorFilter = true).map { it.id }
                    }
                }
            val running = combine(manager.queueState, manager.uploadQueueState) { downloads, uploads ->
                downloads.filter { it.automaticSyncTarget == target || it.explicitCloudSync } + uploads
            }.flatMapLatest { transfers ->
                if (transfers.isEmpty()) {
                    flowOf(emptySet())
                } else {
                    combine(
                        transfers.map { download ->
                            download.statusFlow.map { state ->
                                download.manga.id.takeIf {
                                    state == Download.State.DOWNLOADING || state == Download.State.UPLOADING
                                }
                            }
                        },
                    ) { states -> states.filterNotNull().toSet() }
                }
            }.distinctUntilChanged()
            val localChapters = combine(
                chaptersByManga,
                cache.changes.onStart { emit(Unit) },
            ) { chapters, _ ->
                chapters to manager.getDownloadCounts(chapters.keys.toList())
            }
            combine(
                localChapters,
                preferences.cloudUploadedChapterIds().changes(),
                running,
            ) { (chapters, localCounts), uploaded, active ->
                chapters.entries.associate { (manga, ids) ->
                    manga.id to cloudCoverStatus(
                        synced = ids.isNotEmpty() && ids.all { "$target:$it" in uploaded },
                        local = (localCounts[manga.id] ?: 0) > 0,
                        running = manga.id in active,
                    )
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    private fun networkConstraints(wifiOnly: Boolean): Constraints {
        val builder = Constraints.Builder()
        if (wifiOnly) {
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            builder.setRequiredNetworkRequest(request, NetworkType.CONNECTED)
        } else {
            builder.setRequiredNetworkType(NetworkType.CONNECTED)
        }
        return builder.build()
    }

    private fun scheduleScan(
        workManager: WorkManager,
        constraints: Constraints,
        policy: ExistingWorkPolicy,
        delaySeconds: Long = 0,
    ) {
        workManager.enqueueUniqueWork(
            SCAN,
            policy,
            OneTimeWorkRequestBuilder<LibraryCloudSyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .build(),
        )
    }

    companion object {
        private const val SCAN = "library-webdav-sync"
        private const val PERIODIC_SCAN = "library-webdav-sync-periodic"
        private const val CHAPTER_CHANGE_SCAN_DELAY_SECONDS = 60L
    }
}

private data class LibraryChapterVersion(
    val id: Long,
    val categories: List<Long>,
    val total: Long,
    val fetchedAt: Instant?,
)

private fun LibraryManga.chapterVersion() = LibraryChapterVersion(id, categories, totalChapters, chapterFetchedAt)

internal data class CloudSyncRules(
    val categories: Set<Long>,
    val target: String?,
    val paused: Boolean,
    val pausedCategories: Set<Long>,
) {
    val activeCategories = categories - pausedCategories
    val hasScope get() = categories.isNotEmpty()
    val active get() = target != null && !paused && activeCategories.isNotEmpty()

    fun includes(entry: LibraryManga): Boolean {
        if (entry.manga.isLocal()) return false
        return categoriesFor(entry).isNotEmpty()
    }

    fun categoriesFor(entry: LibraryManga): List<Long> = entry.categories.filter { it in categories }

    fun isUnpaused(entry: LibraryManga): Boolean = entry.categories.any { it in activeCategories }
}

internal data class CloudSyncSelection(val rules: CloudSyncRules, val entries: List<LibraryManga>) {
    val mangaIds = entries.mapTo(HashSet()) { it.id }
    val activeMangaIds = entries.filter(rules::isUnpaused).mapTo(HashSet()) { it.id }
}

internal enum class CloudSyncStatus { Error, Paused, Syncing, Waiting, Complete }

enum class CloudCoverStatus { Unsynced, CloudOnly, LocalCopy, Syncing }

internal fun cloudCoverStatus(synced: Boolean, local: Boolean, running: Boolean): CloudCoverStatus = when {
    running -> CloudCoverStatus.Syncing
    !synced -> CloudCoverStatus.Unsynced
    local -> CloudCoverStatus.LocalCopy
    else -> CloudCoverStatus.CloudOnly
}

internal data class CloudStorageReleaseResult(val released: Int = 0, val kept: Int = 0) {
    operator fun plus(other: CloudStorageReleaseResult) =
        CloudStorageReleaseResult(released + other.released, kept + other.kept)
}

class LibraryCloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        applicationContext.appGraph.libraryCloudSync.syncLibrary()
        Result.success()
    }
}
