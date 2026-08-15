package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.backupChapterMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.source.service.SourceManager

class CloudSyncBackupTest {

    @Test
    fun `cloud sync settings are backed up without raw database id records`() {
        val preferenceStore = mockk<PreferenceStore>()
        every { preferenceStore.getAll() } returns mapOf(
            "cloud_sync_enabled" to true,
            "cloud_sync_url" to "https://example.com/dav",
            "cloud_sync_username" to "user",
            "cloud_sync_password" to "password",
            "cloud_sync_destination" to "Manga",
            "cloud_uploaded_chapter_ids" to setOf("42"),
            "cloud_uploaded_meta_info_hashes" to setOf("7:hash"),
        )

        val preferences = PreferenceBackupCreator(
            sourceManager = mockk<SourceManager>(),
            preferenceStore = preferenceStore,
        ).createApp(includePrivatePreferences = true)
        val keys = preferences.mapTo(mutableSetOf()) { it.key }

        assertTrue("cloud_sync_enabled" in keys)
        assertTrue("cloud_sync_url" in keys)
        assertTrue("cloud_sync_username" in keys)
        assertTrue("cloud_sync_password" in keys)
        assertTrue("cloud_sync_destination" in keys)
        assertFalse("cloud_uploaded_chapter_ids" in keys)
        assertFalse("cloud_uploaded_meta_info_hashes" in keys)
    }

    @Test
    fun `chapter backup uses stable cloud sync marker`() {
        val mapper = backupChapterMapper(setOf("42"))

        val synced = mapper(
            42,
            7,
            "/chapter-1",
            "Chapter 1",
            null,
            false,
            false,
            0,
            1.0,
            0,
            0,
            0,
            0,
            0,
            0,
        )
        val notSynced = mapper(
            43,
            7,
            "/chapter-2",
            "Chapter 2",
            null,
            false,
            false,
            0,
            2.0,
            0,
            0,
            0,
            0,
            0,
            0,
        )

        assertTrue(synced.cloudSynced)
        assertFalse(notSynced.cloudSynced)
    }
}
