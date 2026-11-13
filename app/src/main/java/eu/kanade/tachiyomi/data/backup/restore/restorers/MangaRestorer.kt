package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCloudSyncState
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.cloudSyncTargetPattern
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.backup.model.RestoredHistory
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.download.service.CloudChapterUpload
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import kotlin.time.Clock

@Inject
class MangaRestorer(
    private val restoreRepository: RestoreRepository,
    private val getCategories: GetCategories,
    private val fetchInterval: FetchInterval,
    private val downloadPreferences: DownloadPreferences,
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    private val timeZone = TimeZone.currentSystemDefault()
    private val now = Clock.System.now().toLocalDateTime(timeZone)
    private val currentFetchWindow = fetchInterval.getWindow(now.date, timeZone)

    /**
     * Returns whether an entry was already in the database at the time of this call.
     */
    suspend fun getExistingMatcher(): (BackupManga) -> Boolean {
        val urlsBySource = restoreRepository.getMangaUrlsBySourceId()

        return { it.url in urlsBySource[it.source].orEmpty() }
    }

    /**
     * Restores [backupMangas] all together, so either every one of them is restored or none is.
     */
    suspend fun restore(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) {
        val dbCategoriesByName = getCategories.await().associateBy { it.name }
        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val entries = backupMangas.map { backupManga ->
            RestoredManga(
                manga = backupManga.getMangaImpl(),
                chapters = backupManga.chapters.map { it.toChapterImpl() },
                categoryIds = backupManga.categories.mapNotNull { backupCategoryOrder ->
                    backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                        dbCategoriesByName[backupCategory.name]?.id
                    }
                },
                history = backupManga.history.map {
                    val history = it.getHistoryImpl()
                    RestoredHistory(it.url, history.readAt, history.readDuration)
                },
                tracks = backupManga.tracking.map { it.getTrackImpl() },
                excludedScanlators = backupManga.excludedScanlators,
            )
        }

        restoreRepository.restoreManga(entries) { manga ->
            fetchInterval.withFetchInterval(manga, now, timeZone, currentFetchWindow).apply {
                // A next update still ahead was worked out with the chapters the backup came from
                manga.nextUpdate
                    ?.takeIf { it >= currentFetchWindow.start }
                    ?.let { nextUpdate = it }
            }
        }
        backupMangas.forEach { backup ->
            restoreCloudSyncState(backup)
        }
    }

    private suspend fun restoreCloudSyncState(backup: BackupManga) {
        val states = backup.cloudSyncStates.ifEmpty {
            val target = backup.cloudSyncTargetId ?: return
            val urls = backup.chapters.filter { it.cloudSynced }.map { it.url }
            listOf(BackupCloudSyncState(target, urls, backup.cloudSyncMetaInfoHash))
        }.filter { cloudSyncTargetPattern.matches(it.targetId) }
        if (states.isEmpty()) return

        val manga = getMangaByUrlAndSourceId.await(backup.url, backup.source) ?: return
        val chapterIds = if (states.any { it.chapterUrls.isNotEmpty() }) {
            getChaptersByMangaId.await(manga.id).associate { it.url to it.id }
        } else {
            emptyMap()
        }
        states.forEach { state ->
            downloadPreferences.markChaptersUploadedToCloud(
                state.chapterUrls.mapNotNull(chapterIds::get),
                state.targetId,
            )
            state.metaInfoHash?.takeIf { it.isNotBlank() }?.let {
                downloadPreferences.markMetaInfoUploadedToCloud(manga.id, it, state.targetId)
            }
            state.mangaFolders.filter(::isSafeCloudFolder).forEach { folder ->
                downloadPreferences.markRemoteSyncMangaFolder(manga.id, state.targetId, folder)
            }
            state.chapterFiles.forEach { file ->
                val chapterId = chapterIds[file.chapterUrl]
                if (chapterId != null && isSafeCloudFolder(file.directory) &&
                    file.fileName.endsWith(".cbz", ignoreCase = true) && '/' !in file.fileName && '\\' !in file.fileName
                ) {
                    downloadPreferences.recordCloudChapterUpload(
                        manga.id,
                        chapterId,
                        state.targetId,
                        CloudChapterUpload(file.directory, file.fileName, file.size?.takeIf { it > 0 }),
                    )
                }
            }
        }
    }

    private fun isSafeCloudFolder(folder: String) =
        folder.startsWith('/') && folder.drop(1).split('/').all {
            it.isNotBlank() && it != "." && it != ".." && '\\' !in it
        }
}
