package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupCloudChapterFile
import eu.kanade.tachiyomi.data.backup.models.BackupCloudSyncState
import eu.kanade.tachiyomi.data.backup.models.cloudSyncTargetPattern
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences

internal class CloudSyncBackupRecords(private val preferences: DownloadPreferences) {
    val currentTarget = preferences.cloudSyncTargetId()
    private val targetsByChapter = preferences.cloudUploadedChapterIds().get()
        .mapNotNull { record ->
            val target = record.substringBefore(':')
            val id = record.substringAfter(':', "").toLongOrNull()
            if (id == null || !cloudSyncTargetPattern.matches(target)) null else id to target
        }
        .groupBy({ it.first }, { it.second })
    private val hashesByManga = preferences.cloudUploadedMetaInfoHashes().get().byManga()
    private val foldersByManga = preferences.remoteSyncMangaFolders().get().byManga()

    fun forManga(mangaId: Long, chapters: List<Chapter>): List<BackupCloudSyncState> {
        val hashes = hashesByManga[mangaId].orEmpty()
        val folders = foldersByManga[mangaId].orEmpty()
        val chaptersByTarget = mutableMapOf<String, MutableList<Chapter>>()
        chapters.forEach { chapter ->
            targetsByChapter[chapter.id].orEmpty().forEach { target ->
                chaptersByTarget.getOrPut(target, ::mutableListOf).add(chapter)
            }
        }
        val targets = chaptersByTarget.keys + hashes.map { it.first } + folders.map { it.first }
        return targets.map { target ->
            val uploadedChapters = chaptersByTarget[target].orEmpty()
            val uploadedFiles = if (uploadedChapters.isEmpty()) {
                emptyMap()
            } else {
                preferences.cloudChapterUploads(mangaId, target)
            }
            BackupCloudSyncState(
                targetId = target,
                chapterUrls = uploadedChapters.map { it.url },
                metaInfoHash = hashes.firstOrNull { it.first == target }?.second,
                mangaFolders = folders.filter { it.first == target }.map { it.second },
                chapterFiles = uploadedChapters.mapNotNull { chapter ->
                    uploadedFiles[chapter.id]?.let { upload ->
                        BackupCloudChapterFile(chapter.url, upload.directory, upload.fileName, upload.size)
                    }
                },
            )
        }
    }

    private fun Set<String>.byManga(): Map<Long, List<Pair<String, String>>> {
        return mapNotNull { record ->
            val parts = record.split(':', limit = 3)
            if (parts.size != 3 || !cloudSyncTargetPattern.matches(parts[0]) || parts[2].isBlank()) {
                return@mapNotNull null
            }
            val mangaId = parts[1].toLongOrNull() ?: return@mapNotNull null
            mangaId to (parts[0] to parts[2])
        }.groupBy({ it.first }, { it.second })
    }
}
