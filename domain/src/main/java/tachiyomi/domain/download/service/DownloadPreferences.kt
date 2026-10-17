package tachiyomi.domain.download.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class DownloadPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val downloadOnlyOverWifi: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_download_only_over_wifi_key",
        true,
    )

    val saveChaptersAsCBZ: Preference<Boolean> = preferenceStore.getBoolean("save_chapter_as_cbz", true)

    fun organizeDownloadsByCategory() = preferenceStore.getBoolean("organize_downloads_by_category", false)

    fun addRandomNonceToCBZ() = preferenceStore.getBoolean("add_random_nonce_to_cbz", false)

    fun cloudSyncEnabled() = preferenceStore.getBoolean("cloud_sync_enabled", false)

    fun cloudSyncUrl() = preferenceStore.getString("cloud_sync_url", "")

    fun cloudSyncUsername() = preferenceStore.getString("cloud_sync_username", "")

    fun cloudSyncPassword() = preferenceStore.getString("cloud_sync_password", "")

    fun cloudSyncDestination() = preferenceStore.getString("cloud_sync_destination", "")

    fun cloudSyncDeleteAfterUpload() = preferenceStore.getBoolean("cloud_sync_delete_after_upload", false)

    fun parallelCloudUploadLimit() = preferenceStore.getInt("cloud_sync_parallel_upload_limit", 5)

    fun cloudUploadedChapterIds() = preferenceStore.getStringSet(CLOUD_UPLOADED_CHAPTER_IDS_PREF_KEY, emptySet())
    fun cloudUploadedMetaInfoHashes() = preferenceStore.getStringSet(
        CLOUD_UPLOADED_META_INFO_HASHES_PREF_KEY,
        emptySet(),
    )

    fun isChapterUploadedToCloud(chapterId: Long): Boolean {
        return chapterId.toString() in cloudUploadedChapterIds().get()
    }

    fun markChapterUploadedToCloud(chapterId: Long) {
        markChaptersUploadedToCloud(listOf(chapterId))
    }

    fun markChaptersUploadedToCloud(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        cloudUploadedChapterIds().set(cloudUploadedChapterIds().get() + chapterIds.map { it.toString() })
    }

    fun uploadedMetaInfoHash(mangaId: Long): String? {
        return cloudUploadedMetaInfoHashes().get()
            .firstOrNull { it.substringBefore(':') == mangaId.toString() }
            ?.substringAfter(':', "")
            ?.takeIf { it.isNotBlank() }
    }

    fun markMetaInfoUploadedToCloud(mangaId: Long, contentHash: String) {
        val prefix = "$mangaId:"
        val updated = cloudUploadedMetaInfoHashes().get()
            .filterNot { it.startsWith(prefix) }
            .toSet() + "$mangaId:$contentHash"
        cloudUploadedMetaInfoHashes().set(updated)
    }

    val splitTallImages: Preference<Boolean> = preferenceStore.getBoolean("split_tall_images", true)

    val autoDownloadWhileReading: Preference<Int> = preferenceStore.getInt("auto_download_while_reading", 0)

    val removeAfterReadSlots: Preference<Int> = preferenceStore.getInt("remove_after_read_slots", -1)

    val removeAfterMarkedAsRead: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_remove_after_marked_as_read_key",
        false,
    )

    val removeBookmarkedChapters: Preference<Boolean> = preferenceStore.getBoolean("pref_remove_bookmarked", false)

    val removeExcludeCategories: Preference<Set<String>> = preferenceStore.getStringSet(
        REMOVE_EXCLUDE_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    val downloadNewChapters: Preference<Boolean> = preferenceStore.getBoolean("download_new", false)

    val downloadNewChapterCategories: Preference<Set<String>> = preferenceStore.getStringSet(
        DOWNLOAD_NEW_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    val downloadNewChapterCategoriesExclude: Preference<Set<String>> = preferenceStore.getStringSet(
        DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY,
        emptySet(),
    )

    val downloadNewUnreadChaptersOnly: Preference<Boolean> = preferenceStore.getBoolean(
        "download_new_unread_chapters_only",
        false,
    )

    val parallelSourceLimit: Preference<Int> = preferenceStore.getInt("download_parallel_source_limit", 5)

    val parallelPageLimit: Preference<Int> = preferenceStore.getInt("download_parallel_page_limit", 5)

    companion object {
        private const val REMOVE_EXCLUDE_CATEGORIES_PREF_KEY = "remove_exclude_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_PREF_KEY = "download_new_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY = "download_new_categories_exclude"
        private const val CLOUD_UPLOADED_CHAPTER_IDS_PREF_KEY = "cloud_uploaded_chapter_ids"
        private const val CLOUD_UPLOADED_META_INFO_HASHES_PREF_KEY = "cloud_uploaded_meta_info_hashes"
        val cloudSyncRecordPreferenceKeys = setOf(
            CLOUD_UPLOADED_CHAPTER_IDS_PREF_KEY,
            CLOUD_UPLOADED_META_INFO_HASHES_PREF_KEY,
        )
        val categoryPreferenceKeys = setOf(
            REMOVE_EXCLUDE_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY,
        )
    }
}
