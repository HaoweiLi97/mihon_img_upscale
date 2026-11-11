package eu.kanade.tachiyomi.data.download

import android.content.Context
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * This class is used to persist active downloads across application restarts.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadStore(
    context: Context,
    private val sourceManager: SourceManager,
    private val json: Json,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
) {

    /**
     * Preference file where active downloads are stored.
     */
    private val preferences by lazy { context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE) }
    private val uploads by lazy { uploadPreferences(context) }

    internal fun saveUpload(record: CloudUploadRecord) {
        check(uploads.edit().putString(record.key, json.encodeToString(record)).commit())
    }

    internal fun removeUpload(key: String) {
        check(uploads.edit().remove(key).commit())
    }

    internal fun clearUploads() {
        check(uploads.edit().clear().commit())
    }

    internal suspend fun restoreUploads(): List<Pair<CloudUploadRecord, Download>> {
        return uploads.all.values.mapNotNull { value ->
            val record =
                (value as? String)?.let { runCatching { json.decodeFromString<CloudUploadRecord>(it) }.getOrNull() }
                    ?: return@mapNotNull null
            val manga = getManga.await(record.mangaId) ?: return@mapNotNull null
            val chapter =
                getChapter.await(record.chapterId)?.takeIf { it.mangaId == manga.id } ?: return@mapNotNull null
            val source = sourceManager.get(manga.source) as? HttpSource ?: return@mapNotNull null
            record to Download(
                source,
                manga,
                chapter,
                automaticSyncTarget = record.targetId.takeIf { record.automatic },
            )
        }.sortedBy { (record, _) -> record.order }
    }

    /**
     * Counter used to keep the queue order.
     */
    private var counter = 0

    /**
     * Adds a list of downloads to the store.
     *
     * @param downloads the list of downloads to add.
     */
    fun addAll(downloads: List<Download>) {
        preferences.edit {
            downloads.forEach { putString(getKey(it), serialize(it)) }
        }
    }

    internal fun setFailed(download: Download, failed: Boolean) {
        val key = getKey(download)
        val record = preferences.getString(key, null)?.let(::deserialize) ?: return
        preferences.edit { putString(key, json.encodeToString(record.copy(failed = failed))) }
    }

    /**
     * Removes a download from the store.
     *
     * @param download the download to remove.
     */
    fun remove(download: Download) {
        preferences.edit {
            remove(getKey(download))
        }
    }

    /**
     * Removes a list of downloads from the store.
     *
     * @param downloads the download to remove.
     */
    fun removeAll(downloads: List<Download>) {
        preferences.edit {
            downloads.forEach { remove(getKey(it)) }
        }
    }

    /**
     * Removes all the downloads from the store.
     */
    fun clear() {
        preferences.edit {
            clear()
        }
    }

    /**
     * Returns the preference's key for the given download.
     *
     * @param download the download.
     */
    private fun getKey(download: Download): String {
        return download.chapter.id.toString()
    }

    /**
     * Returns the list of downloads to restore. It should be called in a background thread.
     */
    suspend fun restore(): List<Download> {
        val objs = preferences.all
            .mapNotNull { it.value as? String }
            .mapNotNull { deserialize(it) }
            .sortedBy { it.order }

        val downloads = mutableListOf<Download>()
        if (objs.isNotEmpty()) {
            val cachedManga = mutableMapOf<Long, Manga?>()
            for (obj in objs) {
                val (mangaId, chapterId) = obj
                val manga = cachedManga.getOrPut(mangaId) {
                    getManga.await(mangaId)
                } ?: continue
                val source = sourceManager.get(manga.source) as? HttpSource ?: continue
                val chapter = getChapter.await(chapterId) ?: continue
                val download = Download(source, manga, chapter, obj.automaticSyncTarget, obj.explicitCloudSync)
                if (obj.failed && download.isCloudSync) download.status = Download.State.ERROR
                downloads.add(download)
            }
        }

        // Clear the store, downloads will be added again immediately.
        clear()
        return downloads
    }

    /**
     * Converts a download to a string.
     *
     * @param download the download to serialize.
     */
    private fun serialize(download: Download): String {
        val obj = DownloadObject(
            download.manga.id,
            download.chapter.id,
            counter++,
            download.automaticSyncTarget,
            download.explicitCloudSync,
            failed = download.isCloudSync && download.status == Download.State.ERROR,
        )
        return json.encodeToString(obj)
    }

    /**
     * Restore a download from a string.
     *
     * @param string the download as string.
     */
    private fun deserialize(string: String): DownloadObject? {
        return try {
            json.decodeFromString<DownloadObject>(string)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        internal fun hasCloudUploads(context: Context): Boolean = uploadPreferences(context).all.isNotEmpty()

        private fun uploadPreferences(context: Context) =
            context.getSharedPreferences("active_cloud_uploads", Context.MODE_PRIVATE)
    }
}

/**
 * Class used for download serialization
 *
 * @param mangaId the id of the manga.
 * @param chapterId the id of the chapter.
 * @param order the order of the download in the queue.
 */
@Serializable
private data class DownloadObject(
    val mangaId: Long,
    val chapterId: Long,
    val order: Int,
    val automaticSyncTarget: String? = null,
    val explicitCloudSync: Boolean = false,
    val failed: Boolean = false,
)

@Serializable
internal data class CloudUploadRecord(
    val mangaId: Long,
    val chapterId: Long,
    val targetId: String,
    val remoteDirectory: String,
    val fileUri: String,
    val fileName: String,
    val deleteAfterUpload: Boolean = false,
    val contentHash: String? = null,
    val paused: Boolean = false,
    val order: Long = 0,
    val failed: Boolean = false,
    val automatic: Boolean = false,
    val temporaryArchive: Boolean = false,
) {
    val isChapter: Boolean get() = contentHash == null
    val key: String get() = if (isChapter) "chapter:$chapterId" else "meta:$targetId:$mangaId:$remoteDirectory"
}
