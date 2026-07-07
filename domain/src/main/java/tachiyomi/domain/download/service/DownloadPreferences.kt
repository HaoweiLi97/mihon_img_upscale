package tachiyomi.domain.download.service

import tachiyomi.core.common.preference.PreferenceStore

class DownloadPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun downloadOnlyOverWifi() = preferenceStore.getBoolean(
        "pref_download_only_over_wifi_key",
        true,
    )

    fun saveChaptersAsCBZ() = preferenceStore.getBoolean("save_chapter_as_cbz", true)

    fun cloudSyncEnabled() = preferenceStore.getBoolean("cloud_sync_enabled", false)

    fun cloudSyncUrl() = preferenceStore.getString("cloud_sync_url", "")

    fun cloudSyncUsername() = preferenceStore.getString("cloud_sync_username", "")

    fun cloudSyncPassword() = preferenceStore.getString("cloud_sync_password", "")

    fun cloudSyncDestination() = preferenceStore.getString("cloud_sync_destination", "")

    fun cloudSyncDeleteAfterUpload() = preferenceStore.getBoolean("cloud_sync_delete_after_upload", false)

    fun cloudUploadedChapterIds() = preferenceStore.getStringSet(CLOUD_UPLOADED_CHAPTER_IDS_PREF_KEY, emptySet())
    fun cloudUploadedMetaInfoHashes() = preferenceStore.getStringSet(CLOUD_UPLOADED_META_INFO_HASHES_PREF_KEY, emptySet())

    fun isChapterUploadedToCloud(chapterId: Long): Boolean {
        return chapterId.toString() in cloudUploadedChapterIds().get()
    }

    fun markChapterUploadedToCloud(chapterId: Long) {
        cloudUploadedChapterIds().set(cloudUploadedChapterIds().get() + chapterId.toString())
    }

    fun uploadedMetaInfoHash(mangaId: Long): String? {
        return cloudUploadedMetaInfoHashes().get()
            .firstOrNull { it.substringBefore(':') == mangaId.toString() }
            ?.substringAfter(':', "")
            ?.takeIf { it.isNotBlank() }
    }

    fun markMetaInfoUploadedToCloud(mangaId: Long, contentHash: String) {
        val prefix = "${mangaId}:"
        val updated = cloudUploadedMetaInfoHashes().get()
            .filterNot { it.startsWith(prefix) }
            .toSet() + "${mangaId}:${contentHash}"
        cloudUploadedMetaInfoHashes().set(updated)
    }

    fun splitTallImages() = preferenceStore.getBoolean("split_tall_images", true)

    fun autoDownloadWhileReading() = preferenceStore.getInt("auto_download_while_reading", 0)

    fun removeAfterReadSlots() = preferenceStore.getInt("remove_after_read_slots", -1)

    fun removeAfterMarkedAsRead() = preferenceStore.getBoolean(
        "pref_remove_after_marked_as_read_key",
        false,
    )

    fun removeBookmarkedChapters() = preferenceStore.getBoolean("pref_remove_bookmarked", false)

    fun removeExcludeCategories() = preferenceStore.getStringSet(REMOVE_EXCLUDE_CATEGORIES_PREF_KEY, emptySet())

    fun downloadNewChapters() = preferenceStore.getBoolean("download_new", false)

    fun downloadNewChapterCategories() = preferenceStore.getStringSet(DOWNLOAD_NEW_CATEGORIES_PREF_KEY, emptySet())

    fun downloadNewChapterCategoriesExclude() =
        preferenceStore.getStringSet(DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY, emptySet())

    fun downloadNewUnreadChaptersOnly() = preferenceStore.getBoolean("download_new_unread_chapters_only", false)

    fun parallelSourceLimit() = preferenceStore.getInt("download_parallel_source_limit", 5)

    fun parallelPageLimit() = preferenceStore.getInt("download_parallel_page_limit", 5)

    companion object {
        private const val REMOVE_EXCLUDE_CATEGORIES_PREF_KEY = "remove_exclude_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_PREF_KEY = "download_new_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY = "download_new_categories_exclude"
        private const val CLOUD_UPLOADED_CHAPTER_IDS_PREF_KEY = "cloud_uploaded_chapter_ids"
        private const val CLOUD_UPLOADED_META_INFO_HASHES_PREF_KEY = "cloud_uploaded_meta_info_hashes"
        val categoryPreferenceKeys = setOf(
            REMOVE_EXCLUDE_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY,
        )
    }
}
