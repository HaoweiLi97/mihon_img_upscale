package tachiyomi.domain.download.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.security.MessageDigest

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

    fun cloudSyncPassword() = preferenceStore.getString(CLOUD_SYNC_PASSWORD_PREF_KEY, "")

    fun cloudSyncDestination() = preferenceStore.getString("cloud_sync_destination", "")

    fun cloudSyncDeleteAfterUpload() = preferenceStore.getBoolean("cloud_sync_delete_after_upload", false)

    fun parallelCloudUploadLimit() = preferenceStore.getInt("cloud_sync_parallel_upload_limit", 5)

    fun cloudUploadedChapterIds() = preferenceStore.getStringSet(CLOUD_UPLOADED_CHAPTER_IDS_PREF_KEY, emptySet())
    fun cloudUploadedMetaInfoHashes() = preferenceStore.getStringSet(
        CLOUD_UPLOADED_META_INFO_HASHES_PREF_KEY,
        emptySet(),
    )

    fun cloudSyncTargetId(
        url: String = cloudSyncUrl().get(),
        username: String = cloudSyncUsername().get(),
        destination: String = cloudSyncDestination().get(),
    ): String? {
        val endpoint = url.trim().trimEnd('/')
        if (endpoint.isBlank() || username.isBlank() || destination.isBlank()) return null
        val path = destination.replace('\\', '/').split('/').filter { it.isNotBlank() }.joinToString("/")
        return MessageDigest.getInstance("SHA-256")
            .digest("$endpoint\u0000$username\u0000$path".toByteArray())
            .toHexString()
    }

    fun isChapterUploadedToCloud(chapterId: Long, targetId: String?): Boolean {
        return targetId != null && "$targetId:$chapterId" in cloudUploadedChapterIds().get()
    }

    @Synchronized
    fun markChaptersUploadedToCloud(chapterIds: Collection<Long>, targetId: String) {
        if (chapterIds.isEmpty()) return
        cloudUploadedChapterIds().set(cloudUploadedChapterIds().get() + chapterIds.map { "$targetId:$it" })
    }

    fun uploadedMetaInfoHash(mangaId: Long, targetId: String): String? {
        val prefix = "$targetId:$mangaId:"
        return cloudUploadedMetaInfoHashes().get()
            .firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun markMetaInfoUploadedToCloud(mangaId: Long, contentHash: String, targetId: String) {
        val prefix = "$targetId:$mangaId:"
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
        const val CLOUD_SYNC_PASSWORD_PREF_KEY = "cloud_sync_password"
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
