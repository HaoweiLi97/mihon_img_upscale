package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager

class DownloadProviderTest {

    private val context = mockk<Context>(relaxed = true)
    private val storageManager = mockk<StorageManager>()
    private val libraryPreferences = mockk<LibraryPreferences>()
    private val downloadPreferences = mockk<DownloadPreferences>()
    private val getCategories = mockk<GetCategories>()
    private val disallowNonAscii = mockk<Preference<Boolean>>()
    private val organizeByCategory = mockk<Preference<Boolean>>()
    private val source = mockk<Source>()

    init {
        every { libraryPreferences.disallowNonAsciiFilenames() } returns disallowNonAscii
        every { disallowNonAscii.get() } returns false
        every { downloadPreferences.organizeDownloadsByCategory() } returns organizeByCategory
        every { source.toString() } returns "Test source"
    }

    @Test
    fun `category layout uses first category by user order`() = runTest {
        val rootDir = mockk<UniFile>()
        val categoryDir = mockk<UniFile>()
        val sourceDir = mockk<UniFile>()
        val mangaDir = mockk<UniFile>()
        val manga = Manga.create().copy(id = 7, title = "Test manga")

        every { organizeByCategory.get() } returns true
        every { storageManager.getDownloadsDirectory() } returns rootDir
        coEvery { getCategories.await(manga.id) } returns listOf(
            Category(id = 2, name = "Later", order = 20, flags = 0),
            Category(id = 1, name = "First/category", order = 10, flags = 0),
        )
        every { rootDir.createDirectory("First_category") } returns categoryDir
        every { categoryDir.createDirectory("Test source") } returns sourceDir
        every { sourceDir.createDirectory("Test manga") } returns mangaDir

        val result = createProvider().getMangaDir(manga, source).getOrThrow()

        assertSame(mangaDir, result)
        verifyOrder {
            rootDir.createDirectory("First_category")
            categoryDir.createDirectory("Test source")
            sourceDir.createDirectory("Test manga")
        }
    }

    @Test
    fun `legacy layout remains unchanged when category layout is disabled`() = runTest {
        val rootDir = mockk<UniFile>()
        val sourceDir = mockk<UniFile>()
        val mangaDir = mockk<UniFile>()
        val manga = Manga.create().copy(id = 7, title = "Test manga")

        every { organizeByCategory.get() } returns false
        every { storageManager.getDownloadsDirectory() } returns rootDir
        every { rootDir.createDirectory("Test source") } returns sourceDir
        every { sourceDir.createDirectory("Test manga") } returns mangaDir

        val result = createProvider().getMangaDir(manga, source).getOrThrow()

        assertSame(mangaDir, result)
        verifyOrder {
            rootDir.createDirectory("Test source")
            sourceDir.createDirectory("Test manga")
        }
    }

    @Test
    fun `finds manga in legacy and category layouts`() {
        val rootDir = mockk<UniFile>()
        val legacySourceDir = mockk<UniFile>()
        val categoryDir = mockk<UniFile>()
        val categorySourceDir = mockk<UniFile>()
        val legacyMangaDir = mockk<UniFile>()
        val categoryMangaDir = mockk<UniFile>()

        every { storageManager.getDownloadsDirectory() } returns rootDir
        every { rootDir.findFile("Test source") } returns legacySourceDir
        every { rootDir.listFiles() } returns arrayOf(legacySourceDir, categoryDir)
        every { legacySourceDir.isDirectory } returns true
        every { categoryDir.isDirectory } returns true
        every { legacySourceDir.findFile("Test source") } returns null
        every { categoryDir.findFile("Test source") } returns categorySourceDir
        every { categorySourceDir.isDirectory } returns true
        every { legacySourceDir.uri } returns mockk<Uri>()
        every { categorySourceDir.uri } returns mockk<Uri>()
        every { legacySourceDir.findFile("Test manga") } returns legacyMangaDir
        every { categorySourceDir.findFile("Test manga") } returns categoryMangaDir
        every { legacyMangaDir.isDirectory } returns true
        every { categoryMangaDir.isDirectory } returns true
        every { legacyMangaDir.uri } returns mockk<Uri>()
        every { categoryMangaDir.uri } returns mockk<Uri>()

        val result = createProvider().findMangaDirs("Test manga", source)

        assertEquals(listOf(legacyMangaDir, categoryMangaDir), result)
    }

    private fun createProvider() = DownloadProvider(
        context = context,
        storageManager = storageManager,
        libraryPreferences = libraryPreferences,
        downloadPreferences = downloadPreferences,
        getCategories = getCategories,
    )
}
