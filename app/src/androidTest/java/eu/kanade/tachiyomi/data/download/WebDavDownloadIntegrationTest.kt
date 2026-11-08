package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
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
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
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
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
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
        check(method == "getMangaById")
        manga.takeIf { it.id == args[0] }
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

    @After
    fun cleanup() = runBlocking {
        server.close()
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        listOf("settings", "active_downloads", "active_cloud_uploads").forEach {
            app.deleteSharedPreferences("${root.name}-$it")
        }
        root.deleteRecursively()
        Unit
    }

    @Test
    fun remoteConflictsAndFailedIndexReplacementPreserveExistingBytes() = runBlocking {
        val config = CloudSyncConfig(server.url, "user", "pass")
        val service = graph.cloudSyncService
        val local = "local contents".toByteArray()
        val original = "existing contents".toByteArray()
        val file = checkNotNull(UniFile.fromFile(File(root, "new.cbz").apply { writeBytes(local) }))
        server.files["/target/chapter.cbz"] = original
        val failure = runCatching { service.uploadCbz(config, "/target", file, "chapter.cbz") {} }.exceptionOrNull()
        assertTrue(failure is CloudUploadConflictException)
        assertArrayEquals(original, server.files["/target/chapter.cbz"])
        server.files["/target/chapter.cbz"] = local
        service.uploadCbz(config, "/target", file, "chapter.cbz") {}
        assertEquals(2, server.conditionedPuts.get())

        server.files["/target/meta.info"] = original
        server.rejectMove = true
        assertTrue(runCatching { service.replaceFile(config, "/target", file, "meta.info") }.isFailure)
        assertArrayEquals(original, server.files["/target/meta.info"])
        assertFalse(server.files.keys.any { it.endsWith(".tmp") })
        server.rejectMove = false
        service.replaceFile(config, "/target", file, "meta.info")
        assertArrayEquals(local, server.files["/target/meta.info"])
    }

    @Test
    fun normalDownloadAndPausedAndInterruptedUploadsSurviveQueueReconstruction() = runBlocking {
        var (scope, downloader) = createDownloader()
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

        val restored = createDownloader()
        scope = restored.first
        downloader = restored.second
        await { downloader.uploadQueueState.value.singleOrNull()?.status == Download.State.PAUSED }
        assertEquals(1, server.conditionedPuts.get())
        downloads.cloudSyncDestination().set("/different-target")
        downloader.resumeCloudUpload(first.id)
        assertEquals(Download.State.PAUSED, downloader.uploadQueueState.value.single().status)
        downloads.cloudSyncDestination().set("/target")
        server.failNextPut = true
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
        downloader = createDownloader().second
        await {
            downloads.isChapterUploadedToCloud(second.id, originalTarget) && downloader.uploadQueueState.value.isEmpty()
        }
        assertTrue(server.files.values.any { it.contentEquals(archives.last().readBytes()) })

        downloads.cloudSyncDestination().set("/new-target")
        val newTarget = downloads.cloudSyncTargetId()
        assertFalse(downloads.isChapterUploadedToCloud(first.id, newTarget))
        val conflictPath = firstRemoteFile.key.replaceFirst("/target/", "/new-target/")
        server.files[conflictPath] = "Different contents".toByteArray()
        assertEquals(1, downloader.queueChaptersForCloudSync(manga, listOf(first), autoStart = false).uploadsQueued)
        await { downloader.uploadQueueState.value.singleOrNull()?.status == Download.State.ERROR }
        scopes.last().coroutineContext.job.cancelAndJoin()
        downloader = createDownloader().second
        await { downloader.uploadQueueState.value.singleOrNull()?.status == Download.State.ERROR }
        server.files.remove(conflictPath)
        downloader.resumeCloudUpload(first.id)
        await { downloads.isChapterUploadedToCloud(first.id, newTarget) && downloader.uploadQueueState.value.isEmpty() }
        assertTrue(server.files.keys.any { it.startsWith("/new-target/") })
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
                    val id = DownloadItem.stableId(download.chapter.id, isUpload = true).toLong()
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
                        downTime, SystemClock.uptimeMillis(), event, bounds.exactCenterX(), bounds.exactCenterY(), 0,
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

    private fun findText(label: String): AccessibilityNodeInfo? =
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(label)
            ?.firstOrNull { it.text?.toString() == label }

    private fun captureQueueIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("queueCapture") != "true") return
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(app.getExternalFilesDir(null), "webdav-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    private fun createDownloader(): Pair<CoroutineScope, Downloader> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val manager = StorageManager(scope, context, storage)
        val provider = DownloadProvider(context, manager, graph.libraryPreferences, downloads, graph.getCategories)
        val store = DownloadStore(context, sourceManager, graph.json, GetManga(mangaRepo), GetChapter(chapterRepo))
        return scope to Downloader(
            scope,
            context,
            provider,
            DownloadCache(scope, context, provider, sourceManager, manager),
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
        )
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(20_000) {
        while (!condition()) delay(25)
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

        override fun getMangaUrl(manga: SManga): String {
            metaInfoFailures.incrementAndGet()
            error("Fixture metadata failure")
        }
        override suspend fun getPageList(chapter: SChapter): List<Page> =
            listOf(Page(index = 0, imageUrl = "$baseUrl/image${chapter.url}"))

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
    val conditionedPuts = AtomicInteger()

    @Volatile var rejectMove = false

    @Volatile var failNextPut = false

    @Volatile private var blockedUpload: BlockedUpload? = null

    private val listener = thread(name = "WebDAV fixture") {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            handlers.execute { socket.use { runCatching { handle(it) } } }
        }
    }

    fun blockNextUpload() = BlockedUpload().also { blockedUpload = it }

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
            "GET" -> files[path]?.let { respond(socket, 200, it) } ?: respond(socket, 404)
            "MKCOL" -> respond(socket, 201)
            "DELETE" -> {
                files.remove(path)
                respond(socket, 204)
            }
            "PUT" -> {
                check(headers["if-none-match"] == "*")
                conditionedPuts.incrementAndGet()
                val blocked = blockedUpload
                if (blocked != null) {
                    blockedUpload = null
                    blocked.started.countDown()
                    blocked.release.await()
                    respond(socket, 503)
                } else if (failNextPut) {
                    failNextPut = false
                    respond(socket, 503)
                } else {
                    respond(socket, if (files.putIfAbsent(path, body) == null) 201 else 412)
                }
            }
            "MOVE" -> {
                check(headers["overwrite"] == "T")
                if (rejectMove) {
                    respond(socket, 503)
                } else {
                    val destination = URI(checkNotNull(headers["destination"])).rawPath
                    files[destination] = checkNotNull(files.remove(path))
                    respond(socket, 204)
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
    ) {
        val output = socket.getOutputStream()
        val headers = listOf(
            "HTTP/1.1 $code Test",
            "Content-Length: ${bytes.size}",
            "Content-Type: $mediaType",
            "Connection: close",
        ).joinToString(separator = "\r\n", postfix = "\r\n\r\n")
        output.write(headers.toByteArray())
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        server.close()
        listener.join()
        handlers.shutdownNow()
    }

    class BlockedUpload {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
}
