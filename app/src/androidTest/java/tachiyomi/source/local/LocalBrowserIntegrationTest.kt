package tachiyomi.source.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.image.LocalThumbnailLoader
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalBook
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LocalBrowserIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File
    private lateinit var preferences: StoragePreferences
    private lateinit var fileSystem: LocalSourceFileSystem
    private lateinit var thumbnails: LocalThumbnailLoader
    private lateinit var source: LocalSource
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        workspace = File(context.cacheDir, "local-browser-test-${System.nanoTime()}").apply { mkdirs() }
        val provider = object : FolderProvider {
            override fun directory() = workspace
            override fun path() = Uri.fromFile(workspace).toString()
        }
        preferences = StoragePreferences(provider, InMemoryPreferenceStore())
        fileSystem = LocalSourceFileSystem(StorageManager(scope, context, preferences), preferences, context)
        thumbnails = LocalThumbnailLoader(context)
        source = LocalSource(context, fileSystem, LocalCoverManager(context, fileSystem, thumbnails))
    }

    @After
    fun tearDown() {
        scope.cancel()
        workspace.deleteRecursively()
    }

    private fun uni(file: File): UniFile = checkNotNull(UniFile.fromFile(file))

    private fun png(color: Int, width: Int = 64, height: Int = 96): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun archive(file: File, entries: Map<String, ByteArray>): UniFile {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return uni(file)
    }

    private fun coverColor(file: UniFile): Int {
        val thumbnail = thumbnails.load(file)
        assertNotNull(thumbnail)
        val bitmap = BitmapFactory.decodeFile(thumbnail!!.path)
        return try {
            bitmap.getPixel(0, 0)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertBlue(color: Int) {
        assertTrue(Color.blue(color) >= 250 && Color.red(color) <= 5 && Color.green(color) <= 5)
    }

    @Test
    fun imagesArchivesAndFoldersHaveSampledCachedThumbnails() {
        val folder = File(workspace, "Series").apply { mkdirs() }
        File(folder, "2.PNG").writeBytes(png(Color.BLUE))
        File(folder, "10.png").writeBytes(png(Color.RED))
        val packed = archive(
            File(workspace, "漫画.CBZ"),
            linkedMapOf(
                "10.png" to png(Color.RED),
                "2.PNG" to png(Color.BLUE),
                "ComicInfo.xml" to "metadata".toByteArray(),
            ),
        )
        val folderThumbnail = thumbnails.load(uni(folder))!!
        val cached = thumbnails.load(uni(folder))!!
        assertEquals(folderThumbnail.path, cached.path)
        assertBlue(coverColor(packed))
        assertBlue(coverColor(uni(folder)))
        val large = File(workspace, "large.png").apply { writeBytes(png(Color.GREEN, 2048, 3072)) }
        BitmapFactory.decodeFile(thumbnails.load(uni(large))!!.path).let {
            assertTrue(maxOf(it.width, it.height) <= 640)
            it.recycle()
        }
        val broken = File(workspace, "broken.cbz").apply { writeText("not a zip") }
        assertNull(thumbnails.load(uni(broken)))
    }

    @Test
    fun epubCoverFollowsTheSameSpineAsTheReader() {
        val epub = archive(
            File(workspace, "book.epub"),
            linkedMapOf(
                "META-INF/container.xml" to """
                    <container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>
                """.trimIndent().toByteArray(),
                "OEBPS/content.opf" to """
                    <package><manifest>
                    <item id="page" href="page.xhtml" media-type="application/xhtml+xml"/>
                    </manifest><spine><itemref idref="page"/></spine></package>
                """.trimIndent().toByteArray(),
                "OEBPS/page.xhtml" to """<html><body><img src="cover.png"/></body></html>""".toByteArray(),
                "OEBPS/cover.png" to png(Color.BLUE),
            ),
        )
        assertBlue(coverColor(epub))
    }

    @Test
    fun customRootNestedBooksAndReaderPageOrderWorkWithoutChangingLegacyStorage() = runBlocking {
        val custom = File(workspace, "漫画").apply { mkdirs() }
        val nested = File(custom, "Series/Chapter 1").apply { mkdirs() }
        File(nested, "10.png").writeBytes(png(Color.RED))
        File(nested, "2.JPG").writeBytes(png(Color.BLUE))
        File(nested, ".hidden.png").writeBytes(png(Color.GREEN))
        File(nested, "ComicInfo.xml").writeText("metadata")
        preferences.localComicDirectory.set(Uri.fromFile(custom).toString())
        assertEquals(uni(custom).uri, fileSystem.getBrowserDirectory()!!.uri)
        assertEquals("local", fileSystem.getBaseDirectory()!!.name)
        val url = LocalBook.url(uni(nested))
        val manga = SManga.create().apply {
            this.url = url
            title = "Chapter 1"
        }
        val chapter = source.getChapterList(manga).single()
        assertTrue(source.getFormat(chapter) is Format.Directory)
        val details = source.getMangaDetails(manga)
        assertNotNull(details.thumbnail_url)
        val persistedCover = File(Uri.parse(details.thumbnail_url).path!!)
        assertTrue(persistedCover.isFile)
        val pages = DirectoryPageLoader(uni(nested), browserBook = true).getPages()
        assertEquals(2, pages.size)
        val first = pages.first().stream!!.invoke().use { BitmapFactory.decodeStream(it) }
        assertEquals(Color.BLUE, first.getPixel(0, 0))
        first.recycle()
        preferences.localComicDirectory.set(Uri.fromFile(workspace).toString())
        assertEquals(uni(nested).uri, fileSystem.resolveBook(url)!!.uri)
        assertNotEquals(fileSystem.getBaseDirectory()!!.uri, fileSystem.getBrowserDirectory()!!.uri)
        val intent = ReaderActivity.newIntent(context, 10L, 20L, 1)
        assertEquals(1, intent.getIntExtra("page_index", -1))
        assertFalse(ReaderActivity.newIntent(context, 10L, 20L).hasExtra("page_index"))
    }
}
