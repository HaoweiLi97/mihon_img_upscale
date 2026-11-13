package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.CloudSyncBackupRecords
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupCloudChapterFile
import eu.kanade.tachiyomi.data.backup.models.BackupCloudSyncState
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupOneDriveSession
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.CloudChapterUpload
import tachiyomi.domain.download.service.CloudSyncStorage
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

class CloudSyncBackupTest {

    @Test
    fun `cloud sync settings are backed up without raw database id records`() {
        val preferenceStore = mockk<PreferenceStore>(relaxed = true)
        every { preferenceStore.getAll() } returns mapOf(
            "cloud_sync_enabled" to true,
            "cloud_sync_url" to "https://example.com/dav",
            "cloud_sync_username" to "user",
            "cloud_sync_password" to "password",
            "cloud_sync_destination" to "Manga",
            "cloud_uploaded_chapter_ids" to setOf("42"),
            "cloud_uploaded_meta_info_hashes" to setOf("7:hash"),
            Preference.appStateKey("cloud_sync_onedrive_id") to "drive-id",
        )
        every { preferenceStore.getString(any(), any()) } answers {
            val key = firstArg<String>()
            mockk { every { key() } returns key }
        }
        val creator = PreferenceBackupCreator(
            sourceManager = mockk<SourceManager>(),
            preferenceStore = preferenceStore,
            downloadPreferences = DownloadPreferences(preferenceStore),
        )
        val preferences = creator.createApp(includePrivatePreferences = false, includeCloudStorageLogin = true)
        val keys = preferences.mapTo(mutableSetOf()) { it.key }

        assertTrue("cloud_sync_enabled" in keys)
        assertTrue("cloud_sync_url" in keys)
        assertTrue("cloud_sync_username" in keys)
        assertTrue("cloud_sync_password" in keys)
        assertTrue("cloud_sync_destination" in keys)
        assertTrue("cloud_sync_onedrive_account_fingerprint" in keys)
        assertFalse(Preference.appStateKey("cloud_sync_onedrive_id") in keys)
        assertFalse("cloud_uploaded_chapter_ids" in keys)
        assertFalse("cloud_uploaded_meta_info_hashes" in keys)
        val publicKeys = creator.createApp(includePrivatePreferences = true, includeCloudStorageLogin = false).map {
            it.key
        }
        assertFalse("cloud_sync_password" in publicKeys)
        assertFalse("cloud_sync_username" in publicKeys)
        assertTrue("cloud_sync_url" in publicKeys)
    }

    @Test
    fun `cloud login backup option round trips and defaults off for older jobs`() {
        val options = BackupOptions(cloudStorageLogin = true)
        assertEquals(options, BackupOptions.fromBooleanArray(options.asBooleanArray()))
        assertFalse(BackupOptions.fromBooleanArray(options.asBooleanArray().copyOf(10)).cloudStorageLogin)
        assertFalse(BackupOptions().cloudStorageLogin)
    }

    @Test
    fun `chapter backup uses stable cloud sync marker`() {
        val backup = BackupChapter("/chapter-1", "Chapter 1", cloudSynced = true)
        val restored = ProtoBuf.decodeFromByteArray(
            BackupChapter.serializer(),
            ProtoBuf.encodeToByteArray(BackupChapter.serializer(), backup),
        )
        assertTrue(restored.cloudSynced)
        assertFalse(BackupChapter("/chapter-2", "Chapter 2").cloudSynced)
    }

    @Test
    fun `cloud sync target survives backup encoding`() {
        val manga = BackupManga(source = 1, url = "/manga", cloudSyncTargetId = "target")
        val restored = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), manga),
        )
        assertTrue(restored.cloudSyncTargetId == "target")
    }

    @Test
    fun `backup records target folders and uploaded chapter URLs while disconnected`() {
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        val preferences = mockk<DownloadPreferences>()
        every { preferences.cloudSyncTargetId(any(), any(), any(), any()) } returns null
        every { preferences.cloudSyncStorage().get() } returns CloudSyncStorage.WebDav
        every { preferences.cloudSyncUsername().get() } returns ""
        every { preferences.cloudSyncDestination(any()).get() } returns ""
        every { preferences.cloudSyncUrl().get() } returns ""
        every { preferences.cloudUploadedChapterIds().get() } returns setOf("$first:42", "$second:43", "$second:999")
        every { preferences.cloudUploadedMetaInfoHashes().get() } returns setOf("$first:7:hash", "7:legacy-hash")
        every { preferences.remoteSyncMangaFolders().get() } returns
            setOf("$second:7:/Manga/Old title", "$first:8:/Other")
        every { preferences.cloudChapterUploads(7, first) } returns emptyMap()
        every { preferences.cloudChapterUploads(7, second) } returns
            mapOf(43L to CloudChapterUpload("/Manga/Old title", "Chapter 2.cbz", 512))
        val records = CloudSyncBackupRecords(preferences)
        val chapters = listOf(
            Chapter.create().copy(id = 42, url = "/chapter-1"),
            Chapter.create().copy(id = 43, url = "/chapter-2"),
        )
        val states = records.forManga(7, chapters)
        assertEquals(
            listOf(
                BackupCloudSyncState(first, listOf("/chapter-1"), "hash"),
                BackupCloudSyncState(
                    second,
                    listOf("/chapter-2"),
                    mangaFolders = listOf("/Manga/Old title"),
                    chapterFiles = listOf(
                        BackupCloudChapterFile("/chapter-2", "/Manga/Old title", "Chapter 2.cbz", 512),
                    ),
                ),
            ),
            states,
        )

        val buffer = Buffer()
        val writer = BackupWriter(buffer, ProtoBuf)
        writer.writeEntry(BackupManga(source = 1, url = "/manga", cloudSyncStates = states))
        writer.writeMetadata(Backup(emptyList(), oneDriveSession = BackupOneDriveSession("drive", "client", "token")))
        val reader = BackupReader(buffer, ProtoBuf)
        assertTrue(reader.nextEntry())
        assertEquals(states, reader.readEntry().cloudSyncStates)
        assertEquals("token", reader.readMetadata().oneDriveSession?.refreshToken)
    }

    @Test
    fun `restore binds cloud records to current database IDs and reads legacy markers`() = runTest {
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        val preferences = mockk<DownloadPreferences>(relaxUnitFun = true)
        val categories = mockk<GetCategories>()
        coEvery { categories.await() } returns emptyList()
        val manga = mockk<GetMangaByUrlAndSourceId>()
        coEvery { manga.await("/manga", 1) } returns Manga.create().copy(id = 77)
        coEvery { manga.await("/legacy", 1) } returns Manga.create().copy(id = 78)
        val chapters = mockk<GetChaptersByMangaId>()
        coEvery { chapters.await(77) } returns listOf(Chapter.create().copy(id = 100, url = "/chapter"))
        coEvery { chapters.await(78) } returns listOf(Chapter.create().copy(id = 101, url = "/legacy-chapter"))
        val restorer = MangaRestorer(
            restoreRepository = mockk(relaxUnitFun = true),
            getCategories = categories,
            fetchInterval = FetchInterval(chapters),
            downloadPreferences = preferences,
            getMangaByUrlAndSourceId = manga,
            getChaptersByMangaId = chapters,
        )
        restorer.restore(
            listOf(
                BackupManga(
                    source = 1,
                    url = "/manga",
                    cloudSyncStates = listOf(
                        BackupCloudSyncState(
                            first,
                            listOf("/chapter", "/missing"),
                            "hash",
                            listOf("/Manga/Old", "/Manga/../Outside"),
                            listOf(BackupCloudChapterFile("/chapter", "/Manga/Old", "Chapter.cbz", 256)),
                        ),
                    ),
                ),
                BackupManga(
                    source = 1,
                    url = "/legacy",
                    chapters = listOf(BackupChapter("/legacy-chapter", "Chapter", cloudSynced = true)),
                    cloudSyncTargetId = second,
                ),
            ),
            emptyList(),
        )
        verify(exactly = 1) { preferences.markChaptersUploadedToCloud(listOf(100L), first) }
        verify(exactly = 1) { preferences.markChaptersUploadedToCloud(listOf(101L), second) }
        verify(exactly = 1) { preferences.markMetaInfoUploadedToCloud(77, "hash", first) }
        verify(exactly = 1) { preferences.markRemoteSyncMangaFolder(77, first, "/Manga/Old") }
        verify(exactly = 0) { preferences.markRemoteSyncMangaFolder(77, first, "/Manga/../Outside") }
        verify(exactly = 1) {
            preferences.recordCloudChapterUpload(77, 100, first, CloudChapterUpload("/Manga/Old", "Chapter.cbz", 256))
        }
    }
}
