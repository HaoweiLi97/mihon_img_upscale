package eu.kanade.tachiyomi.data.download

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import com.hippo.unifile.UniFile
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import eu.kanade.core.preference.asState
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.library.components.LibrarySyncActions
import eu.kanade.presentation.library.components.LibraryTabs
import eu.kanade.presentation.manga.MangaCloudSyncActions
import eu.kanade.presentation.manga.components.LocalMangaCoverSyncStatuses
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaToolbar
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.crash.CrashActivity
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.download.DownloadAdapter
import eu.kanade.tachiyomi.ui.download.DownloadHeaderItem
import eu.kanade.tachiyomi.ui.download.DownloadItem
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueViewModel
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.lang.Hash.md5
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.app.di.AppGraph
import mihon.app.di.MihonViewModelFactory
import mihon.core.metro.metroGraph
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.i18n.MR
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory

@RunWith(AndroidJUnit4::class)
class WebDavDownloadIntegrationTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = app.metroGraph<AppGraph>()
    private val onboarding = graph.basePreferences.shownOnboardingFlow
    private val previousOnboarding = onboarding.get().takeIf { onboarding.isSet() }
    private val root = createTempDirectory(app.cacheDir.toPath(), "dav-download-test").toFile()
    private val context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("${root.name}-$name", mode)
    }
    private val preferences = AndroidPreferenceStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
    private val downloads = DownloadPreferences(preferences)
    private val server = DavTestServer()
    private val source = TestImageSource(server.url)
    private val manga = Manga.create().copy(id = 42001, source = source.id, title = "DAV fixture", url = "/manga")
    private val chapters = (1L..2).map { number ->
        Chapter.create().copy(
            id = 42010 + number,
            mangaId = manga.id,
            url = "/chapter-$number",
            name = "Chapter $number",
            sourceOrder = -number,
        )
    }
    private val sourceManager = object : SourceManager {
        override val sources = flowOf<List<Source>>(listOf(source))
        override suspend fun get(sourceKey: Long) = source.takeIf { it.id == sourceKey }
        override suspend fun getOrStub(sourceKey: Long) = checkNotNull(get(sourceKey))
        override suspend fun getAll() = listOf(source)
        override suspend fun getOnlineSources() = listOf(source)
        override suspend fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
    }
    private val chapterRepo = testRepository<ChapterRepository> { method, args ->
        when (method) {
            "getChapterById" -> chapters.firstOrNull { it.id == args[0] }
            "getChapterByMangaId" -> chapters
            else -> error("Unexpected repository method: $method")
        }
    }
    private val mangaRepo = testRepository<MangaRepository> { method, args ->
        when (method) {
            "getMangaById" -> manga.takeIf { it.id == args[0] }
            "getDuplicateLibraryManga" -> emptyList<Any>()
            "getLibraryMangaAsFlow" -> flowOf(
                listOf(
                    LibraryManga(
                        manga = manga,
                        categories = listOf(5L, 8L),
                        totalChapters = chapters.size.toLong(),
                        readCount = 0,
                        bookmarkCount = 0,
                        latestUpload = null,
                        chapterFetchedAt = null,
                        lastRead = null,
                    ),
                ),
            )
            else -> error("Unexpected repository method: $method")
        }
    }
    private val tracks = GetTracks(testRepository<TrackRepository> { _, _ -> emptyList<Any>() })
    private val storage = StoragePreferences(
        object : FolderProvider {
            override fun directory() = root
            override fun path() = root.toURI().toString()
        },
        preferences,
    )
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setup() {
        onboarding.set(true)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    @After
    fun cleanup() = runBlocking {
        if (previousOnboarding == null) onboarding.delete() else onboarding.set(previousOnboarding)
        server.close()
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        listOf("settings", "active_downloads", "active_cloud_uploads").forEach {
            app.deleteSharedPreferences("${root.name}-$it")
        }
        root.deleteRecursively()
        Unit
    }

    @Test
    fun crashReporterOpensWithoutStartingLibrarySync() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val title = app.stringResource(MR.strings.crash_screen_title)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val intent = Intent(activity, CrashActivity::class.java)
                    .putExtra("Throwable", "\"WebDAV startup regression fixture\"")
                activity.startActivity(intent)
            }
            try {
                await("The crash reporter must open in its separate process") { findText(title) != null }
            } finally {
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
    }

    @Test
    fun concurrentChapterAndMetadataUploadsWaitForDirectoryCreation() = runBlocking {
        val config = WebDavConfig(server.url, "user", "pass")
        val file = checkNotNull(UniFile.fromFile(File(root, "upload").apply { writeText("contents") }))
        val firstCreation = CountDownLatch(1)
        val finishCreation = CountDownLatch(1)
        val creating = AtomicInteger()
        val conflicts = AtomicInteger()
        server.beforeMkcol = { path ->
            if (path == "/target/new/") {
                when (creating.incrementAndGet()) {
                    1 -> {
                        firstCreation.countDown()
                        check(finishCreation.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        201
                    }
                    else -> if (finishCreation.count == 0L) 201 else {
                        conflicts.incrementAndGet()
                        423
                    }
                }
            } else {
                201
            }
        }
        val chapter = async { graph.cloudSyncService.storage(config).uploadFile("/target/new", file, "chapter.cbz", false) {} }
        await { firstCreation.count == 0L }
        val metadata = async { graph.cloudSyncService.storage(config).uploadFile("/target/new", file, "meta.info", false) {} }
        try {
            withTimeoutOrNull(500) { metadata.join() }
        } finally {
            finishCreation.countDown()
        }
        assertTrue(chapter.await())
        assertTrue(metadata.await())
        assertEquals(0, conflicts.get())
        assertEquals(2, server.conditionedPuts.get())
    }

    @Test
    fun checksForExistingFilesBeforeSkippingOrOverwriting() = runBlocking {
        val config = WebDavConfig(server.url, "user", "pass")
        val service = graph.cloudSyncService.storage(config)
        val local = "local contents".toByteArray()
        val original = "existing contents".toByteArray()
        val file = checkNotNull(UniFile.fromFile(File(root, "new.cbz").apply { writeBytes(local) }))
        server.beforeHead = { 404 }
        server.files["/target/chapter.cbz"] = original
        assertFalse(service.uploadFile("/target", file, "chapter.cbz", overwrite = false) {})
        assertArrayEquals(original, server.files["/target/chapter.cbz"])
        server.files["/target/meta.info"] = original
        assertFalse(service.uploadFile("/target", file, "meta.info", overwrite = false) {})
        assertArrayEquals(original, server.files["/target/meta.info"])
        assertEquals(0, server.conditionedPuts.get())

        assertTrue(service.uploadFile("/target", file, "chapter.cbz", overwrite = true) {})
        assertArrayEquals(local, server.files["/target/chapter.cbz"])
        assertTrue(service.uploadFile("/target", file, "meta.info", overwrite = true) {})
        assertArrayEquals(local, server.files["/target/meta.info"])
        assertTrue(service.uploadFile("/target", file, "missing.info", overwrite = false) {})
        assertArrayEquals(local, server.files["/target/missing.info"])
        assertEquals(3, server.conditionedPuts.get())
    }

    @Test
    fun skippedChapterKeepsLocalArchiveAndCanBeExplicitlyOverwritten() = runBlocking {
        val fixture = createDownloader()
        val chapter = chapters.first()
        fixture.downloader.queueChapters(manga, listOf(chapter), autoStart = false)
        assertTrue(fixture.downloader.start())
        await { fixture.downloader.queueState.value.isEmpty() && !fixture.downloader.isRunning }
        val archive = root.walkTopDown().single { it.extension == "cbz" }
        val local = archive.readBytes()
        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        downloads.cloudSyncDeleteAfterUpload().set(true)
        val target = checkNotNull(downloads.cloudSyncTargetId())

        suspend fun queueUpload() = fixture.downloader.queueChaptersForCloudSync(manga, listOf(chapter), false)
        assertEquals(1, queueUpload().uploadsQueued)
        val record = DownloadStore(context, sourceManager, graph.json, GetManga(mangaRepo), GetChapter(chapterRepo))
            .restoreUploads().single().first
        val path = URI(null, null, "${record.remoteDirectory}/${record.fileName}", null).toASCIIString()
        val original = "server archive".toByteArray()
        server.files[path] = original
        assertFalse(fixture.downloader.runCloudUploads(networkAllowed = { true }))
        assertEquals(0, server.conditionedPuts.get())
        assertArrayEquals(original, server.files[path])
        assertTrue(archive.isFile)
        assertTrue(downloads.isChapterUploadedToCloud(chapter.id, target))

        downloads.cloudSyncOverwriteUploads().set(true)
        assertEquals(1, queueUpload().uploadsQueued)
        assertFalse(fixture.downloader.runCloudUploads(networkAllowed = { true }))
        assertEquals(1, server.conditionedPuts.get())
        assertArrayEquals(local, server.files[path])
        assertFalse(archive.exists())
    }

    @Test
    fun lowPowerModeSerializesPageDownloads() = runBlocking {
        assertTrue(downloads.lowPowerMode.get())
        source.pagesPerChapter = 2
        val firstImage = server.blockNextImage()
        val firstMetadata = DavTestServer.BlockedRequest()
        source.beforeFirstMetadata = {
            firstMetadata.started.countDown()
            firstMetadata.release.await()
        }

        try {
            val (_, downloader) = createDownloader()
            downloader.queueChapters(manga, chapters, autoStart = false)
            assertTrue(downloader.start())

            await { firstImage.started.count == 0L }
            delay(500)
            assertEquals("A second page must wait for the first one", 1, server.images.get())
            firstImage.release.countDown()

            await { firstMetadata.started.count == 0L }
            delay(500)
            assertEquals("The second chapter must wait for the first one to finish", 2, server.images.get())
            firstMetadata.release.countDown()

            await { downloader.queueState.value.isEmpty() && !downloader.isRunning }
            assertEquals(4, server.images.get())
            val archives = root.walkTopDown().filter { it.extension == "cbz" }.toList()
            assertEquals(2, archives.size)
            archives.forEach { archive ->
                ZipFile(archive).use {
                    assertTrue(it.getEntry("001.png") != null)
                    assertTrue(it.getEntry("002.png") != null)
                }
            }
        } finally {
            firstImage.release.countDown()
            firstMetadata.release.countDown()
        }
    }

    @Test
    fun lowPowerModeLimitsCloudUploads() = runBlocking {
        downloads.lowPowerMode.set(true)
        downloads.parallelCloudUploadLimit().set(3)
        val uploadChapters = chapters +
            chapters.last().copy(id = 42013L, url = "/chapter-3", name = "Chapter 3", sourceOrder = -3)
        val (_, downloader) = createDownloader(processUploads = true)
        downloader.queueChapters(manga, uploadChapters, autoStart = false)
        assertTrue(downloader.start())
        await { downloader.queueState.value.isEmpty() && !downloader.isRunning }
        assertEquals(3, root.walkTopDown().count { it.extension == "cbz" })

        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        val target = checkNotNull(downloads.cloudSyncTargetId())
        val blockedUploads = List(2) { server.blockNextUpload(commitOnRelease = true) }
        try {
            val queued = downloader.queueChaptersForCloudSync(manga, uploadChapters, autoStart = false)
            assertEquals(3, queued.uploadsQueued)
            await { blockedUploads.all { it.started.count == 0L } }
            assertEquals(
                listOf(Download.State.UPLOADING, Download.State.UPLOADING, Download.State.QUEUE),
                downloader.uploadQueueState.value.map(Download::status),
            )
            val queuedChapter = uploadChapters.last()

            downloads.lowPowerMode.set(false)
            await { downloads.isChapterUploadedToCloud(queuedChapter.id, target) }
            assertTrue(uploadChapters.dropLast(1).none { downloads.isChapterUploadedToCloud(it.id, target) })
            blockedUploads.forEach { it.release.countDown() }

            await {
                uploadChapters.all { downloads.isChapterUploadedToCloud(it.id, target) } &&
                    downloader.uploadQueueState.value.isEmpty()
            }
        } finally {
            blockedUploads.forEach { it.release.countDown() }
        }
    }

    @Test
    fun normalDownloadAndPausedAndInterruptedUploadsSurviveQueueReconstruction() = runBlocking {
        downloads.lowPowerMode.set(false)
        var (scope, downloader) = createDownloader(processUploads = true)
        // This fixture rejects meta.info generation. Both ordinary chapters must still complete.
        downloader.queueChapters(manga, chapters, autoStart = false)
        assertTrue(downloader.start())
        await { downloader.queueState.value.isEmpty() }
        val archives = root.walkTopDown().filter { it.extension == "cbz" }.sortedBy { it.name }.toList()
        assertEquals(2, archives.size)
        assertEquals(2, server.images.get())
        assertEquals(2, source.metaInfoFailures.get())
        archives.forEach { archive ->
            ZipFile(archive).use { assertTrue(it.getEntry("001.png") != null) }
        }
        assertTrue(downloader.uploadQueueState.value.isEmpty())

        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        downloads.parallelCloudUploadLimit().set(1)
        val originalTarget = downloads.cloudSyncTargetId()

        val first = chapters.first()
        val second = chapters.last()
        val blocked = server.blockNextUpload()
        assertEquals(1, downloader.queueChaptersForCloudSync(manga, listOf(first), autoStart = false).uploadsQueued)
        await { blocked.started.count == 0L }
        assertEquals(Download.State.UPLOADING, downloader.uploadQueueState.value.single().status)
        assertEquals(1, downloader.queueChaptersForCloudSync(manga, listOf(second), autoStart = false).uploadsQueued)
        assertEquals(Download.State.QUEUE, downloader.uploadQueueState.value.last().status)
        downloader.cancelCloudUpload(second.id)
        downloader.pauseCloudUpload(first.id)
        scope.coroutineContext.job.cancelAndJoin()
        blocked.release.countDown()

        val restored = createDownloader(processUploads = true)
        scope = restored.scope
        downloader = restored.downloader
        await { downloader.uploadQueueState.value.singleOrNull()?.status == Download.State.PAUSED }
        assertEquals(1, server.conditionedPuts.get())
        downloads.cloudSyncDestination().set("/different-target")
        downloader.resumeCloudUpload(first.id)
        assertEquals(Download.State.PAUSED, downloader.uploadQueueState.value.single().status)
        downloads.cloudSyncDestination().set("/target")
        server.failNextPut = true
        downloader.resumeCloudUpload(first.id)
        await { downloader.uploadQueueState.value.singleOrNull()?.status == Download.State.ERROR }
        assertEquals(2, server.conditionedPuts.get())
        assertFalse(downloads.isChapterUploadedToCloud(first.id, originalTarget))
        downloader.resumeCloudUpload(first.id)
        await {
            downloads.isChapterUploadedToCloud(first.id, originalTarget) && downloader.uploadQueueState.value.isEmpty()
        }
        assertEquals(3, server.conditionedPuts.get())
        val firstRemoteFile = server.files.entries.single()
        assertArrayEquals(archives.first().readBytes(), firstRemoteFile.value)

        val interrupted = server.blockNextUpload()
        assertEquals(1, downloader.queueChaptersForCloudSync(manga, listOf(second), autoStart = false).uploadsQueued)
        await { interrupted.started.count == 0L }
        scope.coroutineContext.job.cancelAndJoin()
        interrupted.release.countDown()
        downloader = createDownloader(processUploads = true).downloader
        await {
            downloads.isChapterUploadedToCloud(second.id, originalTarget) && downloader.uploadQueueState.value.isEmpty()
        }
        assertTrue(server.files.values.any { it.contentEquals(archives.last().readBytes()) })

        downloads.cloudSyncDestination().set("/new-target")
        val newTarget = downloads.cloudSyncTargetId()
        assertFalse(downloads.isChapterUploadedToCloud(first.id, newTarget))
        val conflictPath = firstRemoteFile.key.replaceFirst("/target/", "/new-target/")
        val existing = "Different contents".toByteArray()
        server.files[conflictPath] = existing
        assertEquals(1, downloader.queueChaptersForCloudSync(manga, listOf(first), autoStart = false).uploadsQueued)
        await { downloads.isChapterUploadedToCloud(first.id, newTarget) && downloader.uploadQueueState.value.isEmpty() }
        assertArrayEquals(existing, server.files[conflictPath])
    }

    @Test
    fun syncedChaptersPreferWebDavAndFallBackToTheSource() = runBlocking {
        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        downloads.cloudSyncDeleteAfterUpload().set(true)
        assertTrue(downloads.cloudSyncPreferRemoteDownloads().get())

        val target = checkNotNull(downloads.cloudSyncTargetId())
        val chapter = chapters.first()
        val (_, downloader) = createDownloader()

        suspend fun downloadChapter() {
            downloader.queueChapters(manga, listOf(chapter), autoStart = false)
            assertTrue(downloader.start())
            await { downloader.queueState.value.isEmpty() && !downloader.isRunning }
        }

        fun localArchives() = root.walkTopDown().filter { it.extension == "cbz" }.toList()

        downloadChapter()
        assertFalse(downloader.runCloudUploads(networkAllowed = { true }))
        await {
            downloads.isChapterUploadedToCloud(chapter.id, target) && downloader.uploadQueueState.value.isEmpty()
        }
        assertTrue(localArchives().isEmpty())
        assertEquals(1, server.images.get())
        assertTrue(server.fileGets.isEmpty())

        val (originalPath, remoteBytes) = server.files.entries.single()
        val oldDirectory = "/target/Previous folder"
        val oldPath = "/target/Previous%20folder/${originalPath.substringAfterLast('/')}"
        server.files.remove(originalPath)
        server.files[oldPath] = remoteBytes
        downloads.markRemoteSyncMangaFolder(manga.id, target, oldDirectory)

        server.rejectImages = true
        downloadChapter()
        assertArrayEquals(remoteBytes, localArchives().single().readBytes())
        assertEquals(listOf(originalPath, oldPath), server.fileGets.toList())
        assertEquals(1, server.images.get())
        assertTrue(localArchives().single().delete())

        server.rejectImages = false
        server.fileGets.clear()
        downloads.cloudSyncPreferRemoteDownloads().set(false)
        downloadChapter()
        assertTrue(server.fileGets.isEmpty())
        assertEquals(2, server.images.get())
        assertTrue(localArchives().single().delete())

        downloads.cloudSyncPreferRemoteDownloads().set(true)
        server.files[oldPath] = "Invalid archive".toByteArray()
        downloadChapter()
        assertTrue(oldPath in server.fileGets)
        assertEquals(3, server.images.get())
        ZipFile(localArchives().single()).use { assertTrue(it.getEntry("001.png") != null) }
        assertEquals(1, server.conditionedPuts.get())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".cbz_tmp") })
    }

    @Test
    fun automaticScopePausesQueuedDownloadsAndUploadsAcrossRestart() = runBlocking {
        var fixture = createDownloader()
        var downloader = fixture.downloader
        // Simulate an older chapter saved as a directory before CBZ was enabled.
        downloads.saveChaptersAsCBZ.set(false)
        downloader.queueChapters(manga, listOf(chapters.first()), autoStart = false)
        assertTrue(downloader.start())
        await { downloader.queueState.value.isEmpty() && !downloader.isRunning }
        val legacyPage = root.walkTopDown().single { it.name == "001.png" }

        downloads.saveChaptersAsCBZ.set(true)
        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        downloads.parallelCloudUploadLimit().set(1)
        downloads.organizeDownloadsByCategory().set(true)
        downloads.remoteSyncCategories().set(setOf("9"))
        val target = checkNotNull(downloads.cloudSyncTargetId())
        await { fixture.sync.selection.value?.rules?.categories == setOf(9L) }
        assertFalse(fixture.sync.canTransfer(manga.id, target))
        assertEquals(
            0,
            downloader.queueChaptersForCloudSync(manga, chapters, false, target).downloadsQueued,
        )
        downloads.remoteSyncCategories().set(setOf("5", "8"))
        fixture.sync.setCategoryPaused(8L, true)
        await {
            val rules = fixture.sync.selection.value?.rules
            rules?.categories == setOf(5L, 8L) && rules.pausedCategories == setOf(8L)
        }
        assertTrue(fixture.sync.canTransfer(manga.id, target))
        downloads.remoteSyncCategories().set(setOf("8"))
        await { !fixture.sync.canTransfer(manga.id, target) }
        fixture.sync.setCategoryPaused(8L, false)
        await { fixture.sync.canTransfer(manga.id, target) }

        val blocked = server.blockNextUpload()
        val queued = downloader.queueChaptersForCloudSync(manga, chapters, autoStart = false, automaticTarget = target)
        assertEquals(1, queued.uploadsQueued)
        assertEquals(1, queued.downloadsQueued)
        val firstUploadRun = async { downloader.runCloudUploads(networkAllowed = { true }) }
        await { blocked.started.count == 0L }
        fixture.sync.setCategoryPaused(8L, true)
        await {
            downloader.uploadQueueState.value.singleOrNull()?.status == Download.State.PAUSED &&
                downloader.queueState.value.size == 1
        }
        assertFalse(downloader.start())
        assertEquals(1, server.images.get())
        assertEquals(1, server.conditionedPuts.get())
        assertEquals(mapOf(8L to CloudSyncStatus.Paused), fixture.statuses())

        fixture.scope.coroutineContext.job.cancelAndJoin()
        blocked.release.countDown()
        firstUploadRun.await()
        fixture = createDownloader()
        downloader = fixture.downloader
        await {
            downloader.uploadQueueState.value.singleOrNull()?.status == Download.State.PAUSED &&
                downloader.queueState.value.size == 1
        }
        assertEquals(chapters.last().id, downloader.queueState.value.single().chapter.id)
        fixture.sync.setCategoryPaused(8L, false)
        await { fixture.sync.canTransfer(manga.id, target) }
        assertTrue(downloader.start())
        awaitCloudUploads(downloader) {
            chapters.all { downloads.isChapterUploadedToCloud(it.id, target) } &&
                downloader.queueState.value.isEmpty() && downloader.uploadQueueState.value.isEmpty()
        }
        assertTrue(legacyPage.isFile)
        assertEquals(2, server.images.get())
        assertEquals(mapOf(8L to CloudSyncStatus.Complete), fixture.statuses())

        val originalArchive = root.walkTopDown().single { it.extension == "cbz" }
        val remoteChapterTwo = server.files.keys.single { URI(it).path.substringAfterLast('/') == originalArchive.name }
        val bytes = checkNotNull(server.files.remove(remoteChapterTwo))
        val chapter = chapters.last()
        val plainName = "${chapter.name}.cbz"
        val alternateName = if (originalArchive.name == plainName) {
            "${chapter.name}_${md5(chapter.url).take(6)}.cbz"
        } else {
            plainName
        }
        val localArchive = File(originalArchive.parentFile, alternateName)
        assertTrue(originalArchive.renameTo(localArchive))
        val uploadedDirectory = URI(remoteChapterTwo).path.substringBeforeLast('/')
        val conflictingAlias = URI(null, null, "$uploadedDirectory/$alternateName", null).rawPath
        server.files[conflictingAlias] = bytes

        downloads.organizeDownloadsByCategory().set(false)
        server.releaseLookups.clear()
        assertEquals(
            CloudStorageReleaseResult(
                released = 1,
                kept = 1,
                reasons = mapOf(CloudStorageReleaseReason.RemoteMissing to 1),
            ),
            downloader.releaseCloudStorage(manga, target),
        )
        assertEquals(listOf("PROPFIND"), server.releaseLookups.toList())
        assertFalse(legacyPage.exists())
        assertTrue(localArchive.isFile)
        server.files[remoteChapterTwo] = byteArrayOf(1)
        assertEquals(
            CloudStorageReleaseResult(kept = 1, reasons = mapOf(CloudStorageReleaseReason.RemoteMismatch to 1)),
            downloader.releaseCloudStorage(manga, target),
        )
        assertTrue(localArchive.isFile)
        server.files[remoteChapterTwo] = bytes
        assertEquals(CloudStorageReleaseResult(released = 1), downloader.releaseCloudStorage(manga, target))
        assertFalse(localArchive.exists())
        assertArrayEquals(bytes, server.files[remoteChapterTwo])

        preferences.getStringSet("remote_sync_sources", emptySet()).set(setOf(manga.source.toString()))
        assertEquals(setOf(8L), fixture.sync.currentRules().categories)
        assertEquals(mapOf(8L to CloudSyncStatus.Complete), fixture.statuses())

        fixture.sync.setCategoryPaused(8L, true)
        fixture.sync.setParticipatingCategories(setOf("5", "8"))
        await { fixture.sync.selection.value?.rules?.categories == setOf(5L, 8L) }
        assertEquals(mapOf(5L to CloudSyncStatus.Complete, 8L to CloudSyncStatus.Paused), fixture.statuses())
        assertTrue(fixture.sync.canTransfer(manga.id, target))
        fixture.sync.setParticipatingCategories(setOf("8"))
        await { fixture.sync.selection.value?.rules?.categories == setOf(8L) }
        assertEquals(mapOf(8L to CloudSyncStatus.Paused), fixture.statuses())
        assertFalse(fixture.sync.canTransfer(manga.id, target))
        fixture.sync.setParticipatingCategories(emptySet())
        await { fixture.sync.selection.value?.rules?.hasScope == false }
        assertFalse(fixture.sync.canTransfer(manga.id, target))
        assertTrue(fixture.sync.currentRules().pausedCategories.isEmpty())
        assertTrue(fixture.sync.statuses(downloader.queueState, downloader.uploadQueueState).first().isEmpty())

        val remoteFolder = remoteChapterTwo.substringBeforeLast('/') + "/"
        server.files["${remoteFolder}nested/extra.cbz"] = byteArrayOf(1, 2)
        server.files["/target/another-manga/keep.cbz"] = byteArrayOf(3)
        var removedFromLibrary = false
        downloader.deleteRemoteManga(manga, emptyList()) { removedFromLibrary = true }
        assertTrue(removedFromLibrary)
        assertEquals(listOf(remoteFolder), server.deletedCollections.toList())
        assertEquals(setOf("/target/another-manga/keep.cbz"), server.files.keys)
        assertTrue(chapters.none { downloads.isChapterUploadedToCloud(it.id, target) })
    }

    @Test
    fun releaseStorageQueriesWebDavFallbacksConcurrentlyAndReportsFailures() = runBlocking {
        val (_, downloader) = createDownloader(processUploads = true)
        downloader.queueChapters(manga, chapters, autoStart = false)
        assertTrue(downloader.start())
        await { downloader.queueState.value.isEmpty() && !downloader.isRunning }
        val archives = root.walkTopDown().filter { it.extension == "cbz" }.sortedBy { it.name }.toList()
        assertEquals(2, archives.size)
        val (releasableArchive, retainedArchive) = archives

        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        val target = checkNotNull(downloads.cloudSyncTargetId())
        assertEquals(2, downloader.queueChaptersForCloudSync(manga, chapters, autoStart = false).uploadsQueued)
        await {
            chapters.all { downloads.isChapterUploadedToCloud(it.id, target) } &&
                downloader.uploadQueueState.value.isEmpty()
        }

        server.releaseLookups.clear()
        server.fileListingFailure = 405
        val bothHeadsStarted = CountDownLatch(2)
        val releaseHeads = CountDownLatch(1)
        server.beforeHead = { path ->
            bothHeadsStarted.countDown()
            releaseHeads.await()
            if (URI(path).path.substringAfterLast('/') == retainedArchive.name) 503 else null
        }
        val release = async { downloader.releaseCloudStorage(manga, target) }
        try {
            await("The two HEAD requests should overlap") { bothHeadsStarted.count == 0L }
        } finally {
            releaseHeads.countDown()
        }

        assertEquals(
            CloudStorageReleaseResult(
                released = 1,
                kept = 1,
                reasons = mapOf(CloudStorageReleaseReason.RemoteUnavailable to 1),
            ),
            release.await(),
        )
        assertEquals(listOf("PROPFIND", "HEAD", "HEAD"), server.releaseLookups.toList())
        assertFalse(releasableArchive.exists())
        assertTrue(retainedArchive.isFile)
    }

    @Test
    fun disconnectPreservesCloudTransfersAcrossRestart() = runBlocking {
        var fixture = createDownloader(processUploads = true)
        var downloader = fixture.downloader
        val uploadChapter = chapters.first()
        val pendingChapter = chapters.last()
        downloader.queueChapters(manga, listOf(uploadChapter), autoStart = false)
        assertTrue(downloader.start())
        await { downloader.queueState.value.isEmpty() && !downloader.isRunning }
        val localArchive = root.walkTopDown().single { it.extension == "cbz" }

        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncDeleteAfterUpload().set(true)
        downloads.remoteSyncCategories().set(setOf("8"))
        downloads.cloudSyncEnabled().set(true)
        val target = checkNotNull(downloads.cloudSyncTargetId())
        await { fixture.sync.canTransfer(manga.id, target) }

        val blockedUpload = server.blockNextUpload(commitOnRelease = true)
        downloader.queueChaptersForCloudSync(manga, listOf(uploadChapter), autoStart = false)
        await { blockedUpload.started.count == 0L }
        downloader.queueChaptersForCloudSync(manga, listOf(pendingChapter), false, target)
        assertEquals(pendingChapter.id, downloader.queueState.value.single().chapter.id)

        downloads.cloudSyncEnabled().set(false)
        await {
            !fixture.sync.canTransfer(manga.id, target) &&
                downloader.uploadQueueState.value.singleOrNull()?.status == Download.State.PAUSED
        }
        blockedUpload.release.countDown()
        await { server.files.isNotEmpty() }
        assertFalse(downloader.start())
        assertTrue(localArchive.isFile)
        assertFalse(downloads.isChapterUploadedToCloud(uploadChapter.id, target))

        fixture.scope.coroutineContext.job.cancelAndJoin()
        fixture = createDownloader()
        downloader = fixture.downloader
        await {
            downloader.queueState.value.size == 1 &&
                downloader.uploadQueueState.value.singleOrNull()?.status == Download.State.PAUSED
        }
        assertEquals(pendingChapter.id, downloader.queueState.value.single().chapter.id)
        assertEquals(1, server.conditionedPuts.get())
        assertTrue(localArchive.isFile)

        downloads.cloudSyncEnabled().set(true)
        await { fixture.sync.canTransfer(manga.id, target) }
        assertTrue(downloader.start())
        awaitCloudUploads(downloader) {
            chapters.all { downloads.isChapterUploadedToCloud(it.id, target) } &&
                downloader.queueState.value.isEmpty() && downloader.uploadQueueState.value.isEmpty()
        }
        assertTrue(localArchive.isFile)
    }

    @Test
    fun automaticUploadsWaitForNetworkAndRecoverAfterQueueReconstruction() = runBlocking {
        var fixture = createDownloader()
        val chapter = chapters.first()
        fixture.downloader.queueChapters(manga, listOf(chapter), autoStart = false)
        assertTrue(fixture.downloader.start())
        await { fixture.downloader.queueState.value.isEmpty() && !fixture.downloader.isRunning }
        val archive = root.walkTopDown().single { it.extension == "cbz" }
        val contents = archive.readBytes()

        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        downloads.remoteSyncCategories().set(setOf("8"))
        val target = checkNotNull(downloads.cloudSyncTargetId())
        await { fixture.sync.canTransfer(manga.id, target) }
        assertEquals(
            1,
            fixture.downloader.queueChaptersForCloudSync(
                manga,
                listOf(chapter),
                autoStart = false,
                automaticTarget = target,
            ).uploadsQueued,
        )

        var networkAllowed = false
        var clock = System.currentTimeMillis()
        suspend fun runUploadWorker() = withTimeout(20_000) {
            fixture.downloader.runCloudUploads(networkAllowed = { networkAllowed }, now = { clock })
        }

        assertTrue(runUploadWorker())
        assertEquals(0, server.conditionedPuts.get())
        assertFalse(downloads.isChapterUploadedToCloud(chapter.id, target))

        networkAllowed = true
        server.disconnectNextPut = true
        assertTrue(runUploadWorker())
        assertEquals(1, server.conditionedPuts.get())
        assertFalse(downloads.isChapterUploadedToCloud(chapter.id, target))
        assertTrue(archive.isFile)

        fixture.scope.coroutineContext.job.cancelAndJoin()
        fixture = createDownloader()
        await { fixture.sync.canTransfer(manga.id, target) }
        await { fixture.downloader.uploadQueueState.value.singleOrNull()?.chapter?.id == chapter.id }
        assertTrue(runUploadWorker())
        assertEquals(1, server.conditionedPuts.get())

        clock += 60_000
        server.failNextPut = true
        assertTrue(runUploadWorker())
        assertEquals(2, server.conditionedPuts.get())
        assertFalse(downloads.isChapterUploadedToCloud(chapter.id, target))

        clock += 60_000
        assertFalse(runUploadWorker())
        assertEquals(3, server.conditionedPuts.get())
        assertTrue(downloads.isChapterUploadedToCloud(chapter.id, target))
        assertTrue(fixture.downloader.uploadQueueState.value.isEmpty())
        assertArrayEquals(contents, server.files.values.single())
    }

    @Test
    fun manualUploadRetriesAfterConnectionDrops() = runBlocking {
        val scheduling = Channel<Boolean>(Channel.UNLIMITED)
        var fixture = createDownloader(onUploadScheduled = { scheduling.trySend(it) })
        val chapter = chapters.first()
        fixture.downloader.queueChapters(manga, listOf(chapter), autoStart = false)
        assertTrue(fixture.downloader.start())
        await { fixture.downloader.queueState.value.isEmpty() && !fixture.downloader.isRunning }
        val archive = root.walkTopDown().single { it.extension == "cbz" }
        val contents = archive.readBytes()

        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        val target = checkNotNull(downloads.cloudSyncTargetId())
        assertEquals(
            1,
            fixture.downloader.queueChaptersForCloudSync(manga, listOf(chapter), autoStart = false).uploadsQueued,
        )

        var clock = System.currentTimeMillis()
        suspend fun runUploadWorker() = withTimeout(20_000) {
            fixture.downloader.runCloudUploads(networkAllowed = { true }, now = { clock })
        }

        server.disconnectNextPut = true
        assertTrue(runUploadWorker())
        assertEquals(1, server.conditionedPuts.get())
        assertEquals(Download.State.QUEUE, fixture.downloader.uploadQueueState.value.single().status)
        assertFalse(downloads.isChapterUploadedToCloud(chapter.id, target))
        assertTrue(archive.isFile)
        assertFalse(withTimeout(5_000) { scheduling.receive() })

        fixture.scope.coroutineContext.job.cancelAndJoin()
        fixture = createDownloader(onUploadScheduled = { scheduling.trySend(it) })
        assertTrue(runUploadWorker())
        val restored = fixture.downloader.uploadQueueState.value.single()
        assertEquals(chapter.id, restored.chapter.id)
        assertEquals(Download.State.QUEUE, restored.status)
        assertEquals(1, server.conditionedPuts.get())

        assertFalse(withTimeout(5_000) { scheduling.receive() })
        fixture.downloader.resumeCloudUploads()
        assertTrue(withTimeout(5_000) { scheduling.receive() })
        assertFalse(runUploadWorker())
        assertEquals(2, server.conditionedPuts.get())
        assertTrue(downloads.isChapterUploadedToCloud(chapter.id, target))
        assertTrue(fixture.downloader.uploadQueueState.value.isEmpty())
        assertArrayEquals(contents, server.files.values.single())
    }

    @Test
    fun interruptedAutomaticUploadReusesACloudCopyCommittedBeforeCancellation() = runBlocking {
        var fixture = createDownloader()
        val chapter = chapters.first()
        fixture.downloader.queueChapters(manga, listOf(chapter), autoStart = false)
        assertTrue(fixture.downloader.start())
        await { fixture.downloader.queueState.value.isEmpty() && !fixture.downloader.isRunning }
        val archive = root.walkTopDown().single { it.extension == "cbz" }
        val contents = archive.readBytes()

        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        downloads.remoteSyncCategories().set(setOf("8"))
        val target = checkNotNull(downloads.cloudSyncTargetId())
        await { fixture.sync.canTransfer(manga.id, target) }
        fixture.downloader.queueChaptersForCloudSync(
            manga,
            listOf(chapter),
            autoStart = false,
            automaticTarget = target,
        )
        val blocked = server.blockNextUpload(commitOnRelease = true)
        val worker = async { fixture.downloader.runCloudUploads(networkAllowed = { true }) }
        try {
            await { blocked.started.count == 0L }
            worker.cancelAndJoin()
        } finally {
            blocked.release.countDown()
        }

        await { server.files.isNotEmpty() }
        assertTrue(archive.isFile)
        assertFalse(downloads.isChapterUploadedToCloud(chapter.id, target))
        assertArrayEquals(contents, server.files.values.single())
        fixture.scope.coroutineContext.job.cancelAndJoin()

        fixture = createDownloader()
        await { fixture.sync.canTransfer(manga.id, target) }
        assertFalse(fixture.downloader.runCloudUploads(networkAllowed = { true }))
        assertTrue(downloads.isChapterUploadedToCloud(chapter.id, target))
        assertTrue(fixture.downloader.uploadQueueState.value.isEmpty())
        assertEquals(1, server.conditionedPuts.get())
        assertTrue(archive.isFile)
        assertArrayEquals(contents, server.files.values.single())
    }

    @Test
    fun pageListFailureRemainsFailedAfterRestartUntilManualRetry() = runBlocking {
        var fixture = createDownloader()
        val chapter = chapters.first()
        source.failPageList = true
        fixture.downloader.queueChapters(manga, listOf(chapter), autoStart = false)
        assertTrue(fixture.downloader.start())
        await {
            !fixture.downloader.isRunning &&
                fixture.downloader.queueState.value.singleOrNull()?.status == Download.State.ERROR
        }
        assertNull(fixture.downloader.queueState.value.single().pages)
        fixture.scope.coroutineContext.job.cancelAndJoin()

        fixture = createDownloader()
        await { fixture.downloader.queueState.value.singleOrNull()?.status == Download.State.ERROR }
        assertFalse(fixture.downloader.start())
        source.failPageList = false
        assertTrue(fixture.downloader.start(retryFailed = true))
        await { fixture.downloader.queueState.value.isEmpty() && !fixture.downloader.isRunning }
        assertEquals(1, server.images.get())
    }

    @Test
    fun failedRemoteDownloadStaysFailedUntilExplicitRetry() = runBlocking {
        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        downloads.remoteSyncCategories().set(setOf("8"))
        val target = checkNotNull(downloads.cloudSyncTargetId())
        val chapter = chapters.first()
        var fixture = createDownloader()
        await { fixture.sync.canTransfer(manga.id, target) }
        server.rejectImages = true
        fixture.downloader.queueChaptersForCloudSync(manga, listOf(chapter), false, target)
        assertTrue(fixture.downloader.start())
        await {
            !fixture.downloader.isRunning &&
                fixture.downloader.queueState.value.singleOrNull()?.status == Download.State.ERROR
        }
        assertEquals(1, server.images.get())
        assertFalse(fixture.downloader.start())
        fixture.scope.coroutineContext.job.cancelAndJoin()

        fixture = createDownloader()
        await {
            fixture.sync.canTransfer(manga.id, target) &&
                fixture.downloader.queueState.value.singleOrNull()?.status == Download.State.ERROR
        }
        fixture.downloader.queueChaptersForCloudSync(manga, listOf(chapter), false, target)
        assertFalse(fixture.downloader.start())
        assertEquals(1, server.images.get())
        server.rejectImages = false
        assertTrue(fixture.downloader.start(retryFailed = true))
        awaitCloudUploads(fixture.downloader) { downloads.isChapterUploadedToCloud(chapter.id, target) }
        assertEquals(2, server.images.get())
    }

    @Test
    fun bulkQueueActionsAffectOnlyTheirTransferDirection() = runBlocking {
        downloads.lowPowerMode.set(false)
        val fixture = createDownloader(processUploads = true)
        val downloader = fixture.downloader
        val uploadChapters = listOf(
            chapters.first(),
            chapters.first().copy(id = 42013L, url = "/chapter-3", name = "Chapter 3", sourceOrder = -3),
        )
        val downloadChapter = chapters.last()
        downloader.queueChapters(manga, uploadChapters, autoStart = false)
        assertTrue(downloader.start())
        await { downloader.queueState.value.isEmpty() && !downloader.isRunning }
        val localArchives = root.walkTopDown().filter { it.extension == "cbz" }.toList()
        assertEquals(2, localArchives.size)

        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        downloads.parallelCloudUploadLimit().set(1)
        val target = checkNotNull(downloads.cloudSyncTargetId())

        fun uploadStates() = downloader.uploadQueueState.value.map(Download::status)
        val activeUploads = listOf(Download.State.UPLOADING, Download.State.QUEUE)
        val firstUpload = server.blockNextUpload()
        val queued = downloader.queueChaptersForCloudSync(manga, uploadChapters, autoStart = false)
        assertEquals(2, queued.uploadsQueued)
        await { firstUpload.started.count == 0L }

        suspend fun startBlockedDownload(): DavTestServer.BlockedRequest {
            val request = server.blockNextImage()
            assertTrue(downloader.start())
            await { request.started.count == 0L }
            return request
        }

        downloader.queueChapters(manga, listOf(downloadChapter), autoStart = false)
        val firstDownload = startBlockedDownload()
        assertEquals(TransferTaskCounts(1, 1), fixture.manager.downloadTaskCounts().first())
        assertEquals(TransferTaskCounts(2, 1), fixture.manager.uploadTaskCounts().first())
        downloader.pause()
        firstDownload.release.countDown()
        assertFalse(downloader.isRunning)
        assertEquals(Download.State.QUEUE, downloader.queueState.value.single().status)
        assertEquals(activeUploads, uploadStates())
        assertEquals(TransferTaskCounts(1, 0), fixture.manager.downloadTaskCounts().first())
        assertEquals(TransferTaskCounts(2, 1), fixture.manager.uploadTaskCounts().first())

        val secondDownload = startBlockedDownload()
        downloader.pauseCloudUploads()
        firstUpload.release.countDown()
        assertEquals(List(2) { Download.State.PAUSED }, uploadStates())
        assertTrue(downloader.isRunning)
        assertEquals(Download.State.DOWNLOADING, downloader.queueState.value.single().status)
        assertEquals(TransferTaskCounts(1, 1), fixture.manager.downloadTaskCounts().first())
        assertEquals(TransferTaskCounts(2, 0), fixture.manager.uploadTaskCounts().first())

        val secondUpload = server.blockNextUpload()
        downloader.resumeCloudUploads()
        await { secondUpload.started.count == 0L }
        assertEquals(activeUploads, uploadStates())
        assertEquals(Download.State.DOWNLOADING, downloader.queueState.value.single().status)

        downloader.clearQueue()
        secondDownload.release.countDown()
        assertTrue(downloader.queueState.value.isEmpty())
        assertEquals(activeUploads, uploadStates())

        downloader.queueChapters(manga, listOf(downloadChapter), autoStart = false)
        val thirdDownload = startBlockedDownload()
        downloader.cancelCloudUploads()
        secondUpload.release.countDown()
        assertTrue(downloader.uploadQueueState.value.isEmpty())
        assertTrue(downloader.isRunning)
        assertEquals(Download.State.DOWNLOADING, downloader.queueState.value.single().status)
        thirdDownload.release.countDown()

        await { downloader.queueState.value.isEmpty() && !downloader.isRunning }
        assertTrue(localArchives.all(File::isFile))
        assertEquals(3, root.walkTopDown().count { it.extension == "cbz" })
        assertTrue(uploadChapters.none { downloads.isChapterUploadedToCloud(it.id, target) })
    }

    @Test
    fun cancelAllClearsBothQueuesAndAllowsEnabledSyncToResubmit() = runBlocking {
        var fixture = createDownloader()
        fixture.downloader.queueChapters(manga, listOf(chapters.first()), autoStart = false)
        assertTrue(fixture.downloader.start())
        await { fixture.downloader.queueState.value.isEmpty() && !fixture.downloader.isRunning }
        val localArchive = root.walkTopDown().single { it.extension == "cbz" }

        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        downloads.remoteSyncCategories().set(setOf("8"))
        val target = checkNotNull(downloads.cloudSyncTargetId())
        await { fixture.sync.canTransfer(manga.id, target) }

        val blocked = server.blockNextUpload()
        val queued = fixture.downloader.queueChaptersForCloudSync(manga, chapters, false, target)
        assertEquals(1, queued.uploadsQueued)
        assertEquals(1, queued.downloadsQueued)
        val firstUploadRun = async { fixture.downloader.runCloudUploads(networkAllowed = { true }) }
        await { blocked.started.count == 0L }
        fixture.downloader.clearAllTransfers()
        blocked.release.countDown()
        firstUploadRun.await()
        assertTrue(fixture.downloader.queueState.value.isEmpty())
        assertTrue(fixture.downloader.uploadQueueState.value.isEmpty())
        assertTrue(localArchive.isFile)
        assertTrue(chapters.none { downloads.isChapterUploadedToCloud(it.id, target) })

        fixture.scope.coroutineContext.job.cancelAndJoin()
        fixture = createDownloader()
        await { fixture.sync.canTransfer(manga.id, target) }
        val resubmitted = fixture.downloader.queueChaptersForCloudSync(manga, chapters, false, target)
        assertEquals(1, resubmitted.uploadsQueued)
        assertEquals(1, resubmitted.downloadsQueued)
        assertTrue(fixture.downloader.start())
        awaitCloudUploads(fixture.downloader) {
            chapters.all { downloads.isChapterUploadedToCloud(it.id, target) }
        }
    }

    @Test
    fun destinationListingKeepsDirectChildPathsStable() = runBlocking {
        val names = listOf("/dav/target/", "/dav/target/A+B/", "Nested%252Fname/", "/dav/target/grand/child/")
        server.directoryListing = names.joinToString(
            prefix = """<d:multistatus xmlns:d="DAV:">""",
            postfix = "</d:multistatus>",
        ) { href ->
            "<d:response><d:href>$href</d:href><d:resourcetype><d:collection/></d:resourcetype></d:response>"
        }
        val config = WebDavConfig("${server.url}/dav", "user", "pass")
        val directories = graph.cloudSyncService.storage(config).listDirectories("/target")
        assertEquals(setOf("/target/A+B", "/target/Nested%2Fname"), directories.map { it.path }.toSet())
    }

    @Test
    fun automaticScanRecognizesExistingWebDavChapterArchives() = runBlocking {
        val libraryPreferences = LibraryPreferences(preferences)
        libraryPreferences.enableChapterNameHash.set(true)
        val (_, downloader, sync) = createDownloader(libraryPreferences)
        val first = chapters.first()
        downloader.queueChapters(manga, listOf(first), autoStart = false)
        assertTrue(downloader.start())
        await { downloader.queueState.value.isEmpty() && !downloader.isRunning }

        val archive = root.walkTopDown().single { it.extension == "cbz" }
        assertEquals("Chapter 1.cbz", archive.name)
        val bytes = archive.readBytes()
        val remoteFolder = cloudRemoteDirectoryPath("/target", null, source.toString(), manga.title)
        val legacyArchiveName = archive.nameWithoutExtension + "_" + md5(first.url).take(6) + ".cbz"
        val remotePath = URI(null, null, "$remoteFolder/$legacyArchiveName", null).rawPath
        server.files[remotePath] = bytes
        server.files[URI(null, null, "$remoteFolder/Chapter 2.cbz", null).rawPath] = byteArrayOf()
        assertTrue(archive.delete())

        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        downloads.remoteSyncCategories().set(setOf("5"))
        val target = checkNotNull(downloads.cloudSyncTargetId())
        await { sync.canTransfer(manga.id, target) }

        server.fileListingFailure = 503
        val failure = runCatching {
            downloader.queueChaptersForCloudSync(manga, chapters, autoStart = false, automaticTarget = target)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(downloads.isChapterUploadedToCloud(first.id, target))
        assertTrue(downloader.queueState.value.isEmpty())
        server.fileListingFailure = null

        val result = downloader.queueChaptersForCloudSync(manga, chapters, autoStart = false, automaticTarget = target)
        assertEquals(CloudSyncQueueResult(downloadsQueued = 1, skipped = 1), result)
        assertEquals(chapters.last().id, downloader.queueState.value.single().chapter.id)
        assertTrue(downloads.isChapterUploadedToCloud(first.id, target))
        assertFalse(downloads.isChapterUploadedToCloud(chapters.last().id, target))
        assertEquals(setOf("$target:${manga.id}:$remoteFolder"), downloads.remoteSyncMangaFolders().get())
        assertEquals(listOf("PROPFIND", "PROPFIND"), server.releaseLookups.toList())
        assertTrue(server.fileGets.isEmpty())
        assertEquals(0, server.conditionedPuts.get())

        downloader.clearQueue()
        server.rejectImages = true
        downloader.queueChapters(manga, listOf(first), autoStart = false)
        assertTrue(downloader.start())
        await { downloader.queueState.value.isEmpty() && !downloader.isRunning }
        assertArrayEquals(bytes, root.walkTopDown().single { it.extension == "cbz" }.readBytes())
        assertEquals(1, server.images.get())
        assertEquals(0, server.conditionedPuts.get())
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun libraryTabsDisplaySyncStates() = runBlocking {
        val statuses = listOf(
            CloudSyncStatus.Error,
            CloudSyncStatus.Paused,
            CloudSyncStatus.Syncing,
            CloudSyncStatus.Complete,
        )
        val categories = listOf("Archive", "Favorites", "New chapters", "Finished").mapIndexed { index, name ->
            Category(index + 1L, name, index.toLong(), 0)
        }
        val activeTab = mutableIntStateOf(0)
        val pausedTabs = mutableStateOf(emptySet<Long>())
        val released = AtomicInteger()
        val clickedTab = AtomicInteger(-1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.ready = true
                activity.setContentView(
                    ComposeView(activity).apply {
                        setContent {
                            TachiyomiPreviewTheme {
                                Surface {
                                    Column(Modifier.statusBarsPadding()) {
                                        val pagerState = rememberPagerState { categories.size }
                                        LaunchedEffect(activeTab.intValue) {
                                            pagerState.scrollToPage(activeTab.intValue)
                                        }
                                        LibraryTabs(
                                            categories = categories,
                                            pagerState = pagerState,
                                            getItemCountForCategory = { 3 },
                                            syncStatuses = categories.map { it.id }.zip(statuses).toMap(),
                                            onTabItemClick = { clickedTab.set(it) },
                                            syncActions = LibrarySyncActions(
                                                pausedCategories = pausedTabs.value,
                                                enabled = true,
                                                releasingStorage = false,
                                                onPauseChanged = { id, paused ->
                                                    pausedTabs.value = if (paused) {
                                                        pausedTabs.value + id
                                                    } else {
                                                        pausedTabs.value - id
                                                    }
                                                },
                                                onReleaseStorage = { released.set(it.toInt()) },
                                            ),
                                        )
                                        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) {}
                                    }
                                }
                            }
                        }
                    },
                )
            }
            val labels = listOf(
                MR.strings.cloud_sync_status_error,
                MR.strings.cloud_sync_status_paused,
                MR.strings.cloud_sync_status_syncing,
                MR.strings.cloud_sync_status_complete,
            ).map { app.stringResource(it) }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            val firstTab = { automation.rootInActiveWindow?.findWithStateDescription(labels.first()) }
            await { firstTab() != null }
            val tabBounds = Rect().also { checkNotNull(firstTab()).getBoundsInScreen(it) }
            instrumentation.tap(tabBounds)
            await { clickedTab.get() == 0 }
            clickedTab.set(-1)
            val pause = app.stringResource(MR.strings.cloud_sync_pause_category)
            val resume = app.stringResource(MR.strings.cloud_sync_resume_category)
            val release = app.stringResource(MR.strings.cloud_sync_release_storage)
            suspend fun tapMenuItem(label: String) {
                val bounds = Rect()
                val density = app.resources.displayMetrics.density
                await("The menu item must reach its full touch size before tapping") {
                    val item = generateSequence(findText(label)) { it.parent }.firstOrNull { it.isClickable }
                        ?: return@await false
                    item.getBoundsInScreen(bounds)
                    bounds.width() >= (192 * density).toInt() && bounds.height() >= (56 * density).toInt()
                }
                bounds.left = bounds.right - (24 * density).toInt()
                instrumentation.tap(bounds)
            }
            suspend fun openManagement() {
                await("The previous management popup must dismiss before reopening") { findText(release) == null }
                val start = SystemClock.uptimeMillis()
                instrumentation.sendTouch(tabBounds, start, MotionEvent.ACTION_DOWN)
                try {
                    delay(ViewConfiguration.getLongPressTimeout().toLong() + 100)
                } finally {
                    instrumentation.sendTouch(tabBounds, start, MotionEvent.ACTION_UP)
                }
                await("The management popup must remain open after the long press") { findText(release) != null }
                assertEquals(-1, clickedTab.get())
            }
            openManagement()
            delay(200)
            captureQueueIfRequested("library-sync-management")
            tapMenuItem(pause)
            await { pausedTabs.value == setOf(1L) }
            openManagement()
            delay(200)
            captureQueueIfRequested("library-sync-management-paused")
            tapMenuItem(resume)
            await { pausedTabs.value.isEmpty() }
            openManagement()
            tapMenuItem(release)
            await { released.get() == 1 }

            labels.forEachIndexed { index, label ->
                scenario.onActivity { activeTab.intValue = index }
                await { label in automation.rootInActiveWindow?.stateDescriptions().orEmpty() }
                if (index == 0) captureQueueIfRequested("library-sync-tabs")
            }
        }
    }

    @Test
    fun mangaCoverBadgeAppearsOnlyInParticipatingCategory() = runBlocking {
        val libraryManga = GetLibraryManga(mangaRepo).subscribe().first().single()
        val categories = listOf(Category(5, "Synced", 0, 0), Category(8, "Not participating", 1, 0))
        val item = LibraryItem(libraryManga, 0, 0, false, "", "", LibraryItem.Badges(0, 0, false, ""))
        val coverStatuses = mapOf(manga.id to CloudCoverStatus.Unsynced)
        val libraryPreferences = LibraryPreferences(preferences)
        libraryPreferences.displayMode.set(LibraryDisplayMode.List)
        val visiblePage = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val badgeLabel = app.stringResource(MR.strings.remote_sync_cover_unsynced)
        fun badge() = instrumentation.uiAutomation.rootInActiveWindow?.findNode {
            it.isVisibleToUser && it.contentDescription?.contains(badgeLabel) == true
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.ready = true
                activity.setContentView(
                    ComposeView(activity).apply {
                        setContent {
                            TachiyomiPreviewTheme {
                                Surface(Modifier.statusBarsPadding()) {
                                    val scope = rememberCoroutineScope()
                                    val displayMode = remember { libraryPreferences.displayMode.asState(scope) }
                                    CompositionLocalProvider(LocalMangaCoverSyncStatuses provides coverStatuses) {
                                        LibraryContent(
                                            categories = categories,
                                            searchQuery = null,
                                            selection = emptySet(),
                                            contentPadding = PaddingValues(),
                                            currentPage = 0,
                                            hasActiveFilters = false,
                                            showPageTabs = true,
                                            syncCategoryIds = setOf(5L),
                                            syncStatuses = mapOf(5L to CloudSyncStatus.Paused),
                                            syncActions = LibrarySyncActions(
                                                pausedCategories = setOf(5L),
                                                enabled = true,
                                                releasingStorage = false,
                                                onPauseChanged = { _, _ -> },
                                                onReleaseStorage = {},
                                            ),
                                            onChangeCurrentPage = visiblePage::set,
                                            onClickManga = {},
                                            onContinueReadingClicked = null,
                                            onToggleSelection = { _, _ -> },
                                            onToggleRangeSelection = { _, _ -> },
                                            onRefresh = { false },
                                            onGlobalSearchClicked = {},
                                            getItemCountForCategory = { 1 },
                                            getDisplayMode = { displayMode },
                                            getColumnsForOrientation = { error("Expected list layout") },
                                            getItemsForCategory = { listOf(item) },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
            await("Manga in the participating category should have a sync badge") { badge() != null }
            val tab = checkNotNull(findText(categories[1].name))
            instrumentation.tap(Rect().also(tab::getBoundsInScreen))
            await { visiblePage.get() == 1 }
            instrumentation.waitForIdleSync()
            await("The manga should remain visible after switching categories") {
                findText(manga.title)?.isVisibleToUser == true
            }
            assertNull("The same manga should not show a badge in the nonparticipating category", badge())
        }
    }

    @Test
    fun mangaShowsCloudActionsAndChapterStatus() = runBlocking {
        val releases = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.ready = true
                activity.setContentView(
                    ComposeView(activity).apply {
                        setContent {
                            TachiyomiPreviewTheme {
                                Column {
                                    MangaToolbar(
                                        title = "DAV fixture",
                                        hasFilters = false,
                                        navigateUp = {},
                                        onClickFilter = {},
                                        onClickShare = null,
                                        onClickDownload = null,
                                        cloudSync = MangaCloudSyncActions(
                                            status = CloudSyncStatus.Syncing,
                                            chapterStatuses = emptyMap(),
                                            onSync = {},
                                            onSyncChapter = {},
                                            onReleaseStorage = { releases.incrementAndGet() },
                                        ),
                                        onClickEditCategory = null,
                                        onClickRefresh = {},
                                        onClickMigrate = null,
                                        onClickEditNotes = {},
                                        actionModeCounter = 0,
                                        onCancelActionMode = {},
                                        onSelectAll = {},
                                        onInvertSelection = {},
                                        titleAlphaProvider = { 1f },
                                        backgroundAlphaProvider = { 1f },
                                    )
                                    MangaChapterListItem(
                                        title = "Chapter 1",
                                        date = null,
                                        readProgress = null,
                                        scanlator = null,
                                        read = false,
                                        bookmark = false,
                                        selected = false,
                                        downloadIndicatorEnabled = true,
                                        cloudStatus = CloudSyncStatus.Complete,
                                        downloadStateProvider = { Download.State.DOWNLOADED },
                                        downloadProgressProvider = { 0 },
                                        chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                                        chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                                        onLongClick = {},
                                        onClick = {},
                                        onDownloadClick = null,
                                        onCloudSyncClick = null,
                                        onChapterSwipe = {},
                                    )
                                }
                            }
                        }
                    },
                )
            }
            val automation = instrumentation.uiAutomation
            fun findDescription(label: String): AccessibilityNodeInfo? = automation.rootInActiveWindow
                ?.findNode { it.contentDescription?.contains(label) == true }

            val syncing = app.stringResource(MR.strings.cloud_sync_status_syncing)
            val complete = app.stringResource(MR.strings.cloud_sync_status_complete)
            await { findDescription(syncing) != null && findDescription(complete) != null }
            captureQueueIfRequested("manga-sync-icons")
            val overflow = app.stringResource(MR.strings.action_menu_overflow_description)
            instrumentation.tap(Rect().also { checkNotNull(findDescription(overflow)).getBoundsInScreen(it) })
            val release = app.stringResource(MR.strings.cloud_sync_release_storage)
            await("The manga overflow menu must expose the storage action") { findText(release) != null }
            instrumentation.tap(Rect().also { checkNotNull(findText(release)).getBoundsInScreen(it) })
            await { releases.get() == 1 }
        }
    }

    @Test
    fun transferSectionsCollapseIndependentlyAndKeepTheirActions() = runBlocking {
        val fixture = createDownloader(processUploads = true)
        val downloader = fixture.downloader
        val (uploadChapter, downloadChapter) = chapters
        downloader.queueChapters(manga, listOf(uploadChapter), autoStart = false)
        assertTrue(downloader.start())
        await { downloader.queueState.value.isEmpty() && !downloader.isRunning }

        downloads.cloudSyncUrl().set(server.url)
        downloads.cloudSyncUsername().set("user")
        downloads.cloudSyncPassword().set("pass")
        downloads.cloudSyncDestination().set("/target")
        downloads.cloudSyncEnabled().set(true)
        val blocked = server.blockNextUpload()
        val queued = downloader.queueChaptersForCloudSync(manga, chapters, autoStart = false)
        assertEquals(1, queued.uploadsQueued)
        assertEquals(1, queued.downloadsQueued)
        await { blocked.started.count == 0L }
        downloader.pauseCloudUploads()
        blocked.release.countDown()

        val factory = MihonViewModelFactory(
            viewModelProviders = mapOf(DownloadQueueViewModel::class to { DownloadQueueViewModel(fixture.manager) }),
            assistedFactoryProviders = emptyMap(),
            manualAssistedFactoryProviders = emptyMap(),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.ready = true
                activity.setContentView(
                    ComposeView(activity).apply {
                        setContent {
                            TachiyomiPreviewTheme {
                                CompositionLocalProvider(LocalMetroViewModelFactory provides factory) {
                                    Navigator(DownloadQueueScreen)
                                }
                            }
                        }
                    },
                )
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            val downloadHeader = app.stringResource(MR.strings.pref_category_downloads)
            val uploadHeader = app.stringResource(MR.strings.transfer_queue_uploads)
            val uploadActions = app.stringResource(MR.strings.transfer_queue_upload_actions)
            val downloadActions = app.stringResource(MR.strings.transfer_queue_download_actions)
            val cancelAll = app.stringResource(MR.strings.action_cancel_all)
            val sort = app.stringResource(MR.strings.action_sort)
            val byChapter = app.stringResource(MR.strings.action_order_by_chapter_number)
            val ascending = app.stringResource(MR.strings.action_asc)
            fun findAction(label: String) = automation.rootInActiveWindow?.findNode {
                it.contentDescription?.toString() == label
            }
            fun tap(node: AccessibilityNodeInfo?) {
                val bounds = Rect().also { checkNotNull(node).getBoundsInScreen(it) }
                instrumentation.tap(bounds)
            }

            await("Both chapters must be visible when the screen opens") {
                findText(downloadChapter.name) != null && findText(uploadChapter.name) != null
            }
            await("Both sections must expose their own actions") {
                findAction(downloadActions) != null && findAction(uploadActions) != null
            }
            captureQueueIfRequested("transfer-sections")
            tap(findText(uploadHeader))
            await("Collapsing uploads must leave downloads visible") {
                findText(uploadChapter.name) == null && findText(downloadChapter.name) != null
            }
            assertTrue(findAction(uploadActions) != null)
            tap(findAction(uploadActions))
            await("Upload actions must open while uploads are collapsed") { findText(cancelAll) != null }
            assertNull(findText(sort))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("The upload actions menu must close") { findText(cancelAll) == null }

            tap(findText(downloadHeader))
            await("Collapsing downloads must leave both sections collapsed") {
                findText(downloadChapter.name) == null && findText(uploadChapter.name) == null
            }
            assertTrue(findAction(downloadActions) != null)
            captureQueueIfRequested("transfer-sections-collapsed")
            tap(findAction(downloadActions))
            await("Download actions must include sort while downloads are collapsed") {
                findText(cancelAll) != null && findText(sort) != null
            }
            tap(findText(sort))
            await("Sort must include sorting by chapter number") { findText(byChapter) != null }
            tap(findText(byChapter))
            await("Chapter number must have an ascending sort option") { findText(ascending) != null }
            tap(findText(ascending))
            await("Selecting ascending sort must close the menu") { findText(ascending) == null }

            tap(findText(uploadHeader))
            await("Reopening uploads must leave downloads collapsed") {
                findText(uploadChapter.name) != null && findText(downloadChapter.name) == null
            }
            tap(findText(downloadHeader))
            await("Reopening downloads must make both chapters visible") {
                findText(downloadChapter.name) != null && findText(uploadChapter.name) != null
            }
        }
    }

    @Test
    fun uploadRowsShowDistinctStatesAndContextualActions() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val selected = AtomicInteger()
        val states = listOf(
            Download.State.QUEUE,
            Download.State.UPLOADING,
            Download.State.PAUSED,
            Download.State.ERROR,
        )
        val rows = states.mapIndexed { index, state ->
            val chapter = chapters.first().copy(id = 43001L + index, name = "Chapter ${index + 1}")
            Download(source, manga, chapter).apply {
                status = state
                uploadProgress = 37
            }
        }
        val listener = object : DownloadAdapter.DownloadItemListener {
            override fun onItemReleased(position: Int) = Unit
            override fun onMenuItemClick(position: Int, menuItem: MenuItem) {
                selected.set(menuItem.itemId)
            }
        }
        val adapter = DownloadAdapter(listener)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var list: RecyclerView
            scenario.onActivity { activity ->
                activity.ready = true
                list = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    this.adapter = adapter
                }
                activity.setContentView(list)
                val header = DownloadHeaderItem(source.id, source.name, rows.size)
                header.addSubItems(0, rows.map { DownloadItem(it, header, isUpload = true) })
                adapter.updateDataSet(listOf(header))
            }
            val uiContext = list.context
            val labels = listOf(
                uiContext.stringResource(MR.strings.cloud_sync_upload_queued),
                uiContext.stringResource(MR.strings.cloud_sync_uploading_progress, 37),
                uiContext.stringResource(MR.strings.cloud_sync_upload_paused),
                uiContext.stringResource(MR.strings.cloud_sync_upload_failed),
            )
            await { labels.all { findText(it) != null } }
            captureQueueIfRequested("queue")

            val actions = listOf(
                MR.strings.action_pause,
                MR.strings.action_pause,
                MR.strings.action_resume,
                MR.strings.action_retry,
            )
            rows.zip(actions).forEach { (download, action) ->
                selected.set(0)
                scenario.onActivity {
                    val id = DownloadItem.stableId(download.chapter.id, isUpload = true)
                    val holder = checkNotNull(list.findViewHolderForItemId(id))
                    val progress = holder.itemView.findViewById<View>(R.id.download_progress)
                    assertEquals(
                        if (download.status == Download.State.UPLOADING) View.VISIBLE else View.INVISIBLE,
                        progress.visibility,
                    )
                    holder.itemView.findViewById<View>(R.id.menu).performClick()
                }
                val label = uiContext.stringResource(action)
                await { findText(label) != null }
                if (action == MR.strings.action_retry) captureQueueIfRequested("retry")
                val bounds = Rect().also { checkNotNull(findText(label)).getBoundsInScreen(it) }
                val downTime = SystemClock.uptimeMillis()
                for (event in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val pointer = MotionEvent.obtain(
                        downTime,
                        SystemClock.uptimeMillis(),
                        event,
                        bounds.exactCenterX(),
                        bounds.exactCenterY(),
                        0,
                    )
                    instrumentation.sendPointerSync(pointer)
                    pointer.recycle()
                }
                val expectedId = if (action == MR.strings.action_pause) R.id.pause_upload else R.id.resume_upload
                await { selected.get() == expectedId }
                instrumentation.waitForIdleSync()
            }
        }
    }

    private fun AccessibilityNodeInfo.stateDescriptions(): List<String> = buildList {
        stateDescription?.toString()?.let(::add)
        for (index in 0 until childCount) {
            getChild(index)?.stateDescriptions()?.let(::addAll)
        }
    }

    private fun AccessibilityNodeInfo.findWithStateDescription(label: String): AccessibilityNodeInfo? =
        findNode { it.stateDescription?.toString() == label }

    private fun AccessibilityNodeInfo.findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        if (predicate(this)) this else (0 until childCount).firstNotNullOfOrNull { getChild(it)?.findNode(predicate) }

    private fun android.app.Instrumentation.tap(bounds: Rect) {
        val start = SystemClock.uptimeMillis()
        sendTouch(bounds, start, MotionEvent.ACTION_DOWN)
        sendTouch(bounds, start, MotionEvent.ACTION_UP)
    }

    private fun android.app.Instrumentation.sendTouch(bounds: Rect, start: Long, action: Int) {
        val event = MotionEvent.obtain(
            start,
            SystemClock.uptimeMillis(),
            action,
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            0,
        )
        try {
            sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private fun findText(label: String): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
        return roots.firstNotNullOfOrNull { root -> root.findNode { it.text?.toString() == label } }
    }

    private fun captureQueueIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("queueCapture") != "true") return
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(app.getExternalFilesDir(null), "webdav-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    private data class DownloaderFixture(
        val scope: CoroutineScope,
        val downloader: Downloader,
        val sync: LibraryCloudSync,
        val manager: DownloadManager,
    ) {
        suspend fun statuses() = withTimeout(20_000) {
            sync.statuses(downloader.queueState, downloader.uploadQueueState).first { it.isNotEmpty() }
        }
    }

    private fun createDownloader(
        libraryPreferences: LibraryPreferences = graph.libraryPreferences,
        processUploads: Boolean = false,
        onUploadScheduled: (Boolean) -> Unit = {},
    ): DownloaderFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val uploadRequests = if (processUploads) Channel<Unit>(Channel.CONFLATED) else null
        val manager = StorageManager(scope, context, storage)
        val provider = DownloadProvider(
            context,
            manager,
            libraryPreferences,
            downloads,
            graph.getCategories,
            GetChaptersByMangaId(chapterRepo),
        )
        val cache = DownloadCache(scope, context, provider, sourceManager, manager)
        val getManga = GetManga(mangaRepo)
        val getChapter = GetChapter(chapterRepo)
        val store = DownloadStore(context, sourceManager, graph.json, getManga, getChapter)
        val sync = LibraryCloudSync(
            scope,
            context,
            downloads,
            GetLibraryManga(mangaRepo),
            GetChaptersByMangaId(chapterRepo),
        )
        val downloader = Downloader(
            scope,
            context,
            provider,
            cache,
            sourceManager,
            ChapterCache(context, graph.json),
            graph.coverCache,
            graph.cloudSyncService,
            GetChaptersByMangaId(chapterRepo),
            downloads,
            graph.xml,
            graph.getCategories,
            tracks,
            store,
            DownloadNotifier(context, graph.securityPreferences),
            sync,
            GetDuplicateLibraryManga(mangaRepo),
            CloudUploadScheduler { _, replaceWaitingWork ->
                onUploadScheduled(replaceWaitingWork)
                uploadRequests?.trySend(Unit)
            },
        )
        if (uploadRequests != null) {
            scope.launch {
                uploadRequests.consumeAsFlow().collect { downloader.runCloudUploads(networkAllowed = { true }) }
            }
        }
        val downloadManager = DownloadManager(
            context,
            provider,
            cache,
            graph.getCategories,
            getManga,
            getChapter,
            sourceManager,
            downloads,
            downloader,
            DownloadPendingDeleter(context, graph.json),
        )
        return DownloaderFixture(scope, downloader, sync, downloadManager)
    }

    private suspend fun await(
        description: String = "Timed out waiting for the expected state",
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(20_000) {
                while (!condition()) delay(25)
            }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError(description, error)
        }
    }

    private suspend fun awaitCloudUploads(downloader: Downloader, condition: () -> Boolean) {
        withTimeout(20_000) {
            while (!condition()) {
                downloader.runCloudUploads(networkAllowed = { true })
                if (!condition()) delay(25)
            }
        }
    }

    private inline fun <reified T> testRepository(crossinline handle: (String, Array<out Any?>) -> Any?): T =
        T::class.java.cast(
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                handle(method.name, args.orEmpty())
            },
        )!!

    @Suppress("OVERRIDE_DEPRECATION")
    private class TestImageSource(override val baseUrl: String) : HttpSource() {
        override val name = "DAV test source"
        override val lang = "en"
        override val supportsLatest = false
        val metaInfoFailures = AtomicInteger()
        var pagesPerChapter = 1
        var failPageList = false
        var beforeFirstMetadata: (() -> Unit)? = null

        override fun getMangaUrl(manga: SManga): String {
            if (metaInfoFailures.incrementAndGet() == 1) beforeFirstMetadata?.invoke()
            error("Fixture metadata failure")
        }

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            check(!failPageList) { "HTTP error 404" }
            return List(pagesPerChapter) { index ->
                val pagePath = if (pagesPerChapter == 1) chapter.url else "${chapter.url}/$index"
                Page(index = index, imageUrl = "$baseUrl/image$pagePath")
            }
        }

        override fun popularMangaRequest(page: Int): Request = error("Unexpected request")
        override fun popularMangaParse(response: Response): MangasPage = error("Unexpected response")
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
            error("Unexpected request")
        override fun searchMangaParse(response: Response): MangasPage = error("Unexpected response")
        override fun latestUpdatesRequest(page: Int): Request = error("Unexpected request")
        override fun latestUpdatesParse(response: Response): MangasPage = error("Unexpected response")
        override fun mangaDetailsParse(response: Response): SManga = error("Unexpected response")
        override fun chapterListParse(response: Response): List<SChapter> = error("Unexpected response")
        override fun chapterPageParse(response: Response): SChapter = error("Unexpected response")
        override fun pageListParse(response: Response): List<Page> = error("Unexpected response")
        override fun imageUrlParse(response: Response): String = error("Unexpected response")
    }
}

private class DavTestServer : AutoCloseable {
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val handlers = Executors.newCachedThreadPool()
    val url = "http://127.0.0.1:${server.localPort}"
    val files = ConcurrentHashMap<String, ByteArray>()
    val images = AtomicInteger()
    val fileGets = java.util.concurrent.ConcurrentLinkedQueue<String>()
    val conditionedPuts = AtomicInteger()
    val deletedCollections = java.util.concurrent.ConcurrentLinkedQueue<String>()
    val releaseLookups = java.util.concurrent.ConcurrentLinkedQueue<String>()


    @Volatile var failNextPut = false

    @Volatile var disconnectNextPut = false

    @Volatile var rejectImages = false

    @Volatile var fileListingFailure: Int? = null

    @Volatile var beforeHead: ((String) -> Int?)? = null

    @Volatile var beforeMkcol: ((String) -> Int)? = null

    @Volatile var directoryListing: String? = null

    private val blockedUploads = ConcurrentLinkedQueue<BlockedRequest>()

    @Volatile private var blockedImage: BlockedRequest? = null

    private val listener = thread(name = "WebDAV fixture") {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            handlers.execute { socket.use { runCatching { handle(it) } } }
        }
    }

    fun blockNextUpload(commitOnRelease: Boolean = false) =
        BlockedRequest(commitOnRelease).also { blockedUploads.add(it) }

    fun blockNextImage() = BlockedRequest().also { blockedImage = it }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().source().buffer()
        val (method, path) = input.readUtf8LineStrict().split(' ')
        val headers = buildMap {
            while (true) {
                val line = input.readUtf8LineStrict()
                if (line.isEmpty()) break
                put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
            }
        }
        val body = input.readByteArray(headers["content-length"]?.toLong() ?: 0)
        if (path.startsWith("/image/")) {
            images.incrementAndGet()
            blockedImage?.let { blocked ->
                blockedImage = null
                blocked.started.countDown()
                blocked.release.await()
            }
            if (rejectImages) {
                respond(socket, 503)
                return
            }
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream().apply {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)
            }.toByteArray()
            bitmap.recycle()
            respond(socket, 200, png, "image/png")
            return
        }
        check(headers["authorization"] == "Basic dXNlcjpwYXNz")
        when (method) {
            "GET" -> {
                fileGets += path
                files[path]?.let { respond(socket, 200, it) } ?: respond(socket, 404)
            }
            "HEAD" -> {
                releaseLookups += method
                beforeHead?.invoke(path)?.let { code ->
                    respond(socket, code)
                    return
                }
                files[path]?.let { respond(socket, 200, it, headOnly = true) } ?: respond(socket, 404)
            }
            "MKCOL" -> respond(socket, beforeMkcol?.invoke(path) ?: 201)
            "PROPFIND" -> {
                if (headers["depth"] == "0" && path == "/target/new/") {
                    respond(socket, 404)
                    return
                }
                if (headers["depth"] == "0" && !path.endsWith('/')) {
                    if (!files.containsKey(path)) {
                        respond(socket, 404)
                    } else {
                        val xml = """
                            <d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href>
                            <d:propstat><d:prop><d:resourcetype/></d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
                        """.trimIndent()
                        respond(socket, 207, xml.toByteArray(), "application/xml")
                    }
                    return
                }
                val listing = if ("getcontentlength" in body.decodeToString()) {
                    check(headers["depth"] == "1")
                    releaseLookups += method
                    fileListingFailure?.let { failure ->
                        respond(socket, failure)
                        return
                    }
                    files.entries.filter { (file, _) -> file.substringBeforeLast('/') + "/" == path }
                        .joinToString(
                            separator = "",
                            prefix = """<d:multistatus xmlns:d="DAV:">""",
                            postfix = "</d:multistatus>",
                        ) { (file, bytes) ->
                            """
                            <d:response>
                                <d:href>$file</d:href>
                                <d:propstat>
                                    <d:prop>
                                        <d:getcontentlength>${bytes.size}</d:getcontentlength>
                                        <d:resourcetype/>
                                    </d:prop>
                                    <d:status>HTTP/1.1 200 OK</d:status>
                                </d:propstat>
                            </d:response>
                            """.trimIndent()
                        }
                } else {
                    checkNotNull(directoryListing)
                }
                respond(socket, 207, listing.toByteArray(), "application/xml")
            }
            "DELETE" -> {
                if (path.endsWith("/")) {
                    check(headers["depth"] == "infinity")
                    deletedCollections += path
                    files.keys.removeIf { it.startsWith(path) }
                } else {
                    files.remove(path)
                }
                respond(socket, 204)
            }
            "PUT" -> {
                check(headers["if-none-match"] == "*")
                conditionedPuts.incrementAndGet()
                val blocked = blockedUploads.poll()
                if (blocked != null) {
                    blocked.started.countDown()
                    blocked.release.await()
                    val code = if (blocked.commitOnRelease) {
                        if (files.putIfAbsent(path, body) == null) 201 else 412
                    } else {
                        503
                    }
                    respond(socket, code)
                } else if (disconnectNextPut) {
                    disconnectNextPut = false
                } else if (failNextPut) {
                    failNextPut = false
                    respond(socket, 503)
                } else {
                    respond(socket, if (files.putIfAbsent(path, body) == null) 201 else 412)
                }
            }
            else -> error("Unexpected method: $method")
        }
    }

    private fun respond(
        socket: Socket,
        code: Int,
        bytes: ByteArray = byteArrayOf(),
        mediaType: String = "application/octet-stream",
        headOnly: Boolean = false,
    ) {
        val output = socket.getOutputStream()
        val headers = listOf(
            "HTTP/1.1 $code Test",
            "Content-Length: ${bytes.size}",
            "Content-Type: $mediaType",
            "Connection: close",
        ).joinToString(separator = "\r\n", postfix = "\r\n\r\n")
        output.write(headers.toByteArray())
        if (!headOnly) output.write(bytes)
        output.flush()
    }

    override fun close() {
        server.close()
        listener.join()
        handlers.shutdownNow()
    }

    class BlockedRequest(val commitOnRelease: Boolean = false) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
}
