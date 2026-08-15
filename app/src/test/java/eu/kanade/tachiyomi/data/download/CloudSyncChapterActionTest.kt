package eu.kanade.tachiyomi.data.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CloudSyncChapterActionTest {

    @Test
    fun `cloud directory includes category when category layout is enabled`() {
        val path = cloudRemoteDirectoryPath(
            destination = "/Mihon/",
            categoryDirName = "Favorites",
            sourceDirName = "Test source",
            mangaDirName = "Test manga",
        )

        assertEquals("/Mihon/Favorites/Test manga", path)
    }

    @Test
    fun `cloud directory keeps legacy layout when category layout is disabled`() {
        val path = cloudRemoteDirectoryPath(
            destination = "/Mihon/",
            categoryDirName = null,
            sourceDirName = "Test source",
            mangaDirName = "Test manga",
        )

        assertEquals("/Mihon/Test source/Test manga", path)
    }

    @Test
    fun `already uploaded chapter is skipped`() {
        val action = cloudSyncChapterAction(
            isUploaded = true,
            hasLocalDownload = false,
            isLocalCbz = false,
        )

        assertEquals(CloudSyncChapterAction.Skip, action)
    }

    @Test
    fun `missing local chapter is downloaded`() {
        val action = cloudSyncChapterAction(
            isUploaded = false,
            hasLocalDownload = false,
            isLocalCbz = false,
        )

        assertEquals(CloudSyncChapterAction.Download, action)
    }

    @Test
    fun `existing local cbz is uploaded directly`() {
        val action = cloudSyncChapterAction(
            isUploaded = false,
            hasLocalDownload = true,
            isLocalCbz = true,
        )

        assertEquals(CloudSyncChapterAction.Upload, action)
    }

    @Test
    fun `existing non cbz chapter cannot be uploaded`() {
        val action = cloudSyncChapterAction(
            isUploaded = false,
            hasLocalDownload = true,
            isLocalCbz = false,
        )

        assertEquals(CloudSyncChapterAction.UnsupportedLocalDownload, action)
    }
}
