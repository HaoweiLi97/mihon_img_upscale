package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.getComicInfo
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import eu.kanade.tachiyomi.util.storage.saveTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its queue contains the list of chapters to download.
 */
class Downloader(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager = Injekt.get(),
    private val chapterCache: ChapterCache = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val cloudSyncService: CloudSyncService = Injekt.get(),
    private val xml: XML = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
) {

    /**
     * Store for persisting downloads across restarts.
     */
    private val store = DownloadStore(context)

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy { DownloadNotifier(context) }
    private val json by lazy {
        Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloaderJob: Job? = null

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = false

    init {
        launchNow {
            val chapters = async { store.restore() }
            addAllToQueue(chapters.await())
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Download.State.DOWNLOADED }
        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }

        isPaused = false

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING || it.status == Download.State.UPLOADING }
            .forEach { it.status = Download.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        isPaused = false

        DownloadJob.stop(context)
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING || it.status == Download.State.UPLOADING }
            .forEach { it.status = Download.State.QUEUE }
        isPaused = true
    }

    /**
     * Removes everything from the queue.
     */
    fun clearQueue() {
        cancelDownloaderJob()

        internalClearQueue()
        notifier.dismissProgress()
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun launchDownloaderJob() {
        if (isRunning) return

        downloaderJob = scope.launch {
            val activeDownloadsFlow = combine(
                queueState,
                downloadPreferences.parallelSourceLimit().changes(),
            ) { a, b -> a to b }.transformLatest { (queue, parallelCount) ->
                while (true) {
                    val activeDownloads = queue.asSequence()
                        // Ignore completed downloads, leave them in the queue
                        .filter { it.status.value <= Download.State.UPLOADING.value }
                        .groupBy { it.source }
                        .toList()
                        .take(parallelCount)
                        .map { (_, downloads) -> downloads.first() }
                    emit(activeDownloads)

                    if (activeDownloads.isEmpty()) break
                    // Suspend until a download enters the ERROR state
                    val activeDownloadsErroredFlow =
                        combine(activeDownloads.map(Download::statusFlow)) { states ->
                            states.contains(Download.State.ERROR)
                        }.filter { it }
                    activeDownloadsErroredFlow.first()
                }
            }
                .distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val downloadJobs = mutableMapOf<Download, Job>()

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val downloadJobsToStop = downloadJobs.filter { it.key !in activeDownloads }
                    downloadJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        downloadJobs.remove(download)
                    }

                    val downloadsToStart = activeDownloads.filter { it !in downloadJobs }
                    downloadsToStart.forEach { download ->
                        downloadJobs[download] = launchDownloadJob(download)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchDownloadJob(download: Download) = launchIO {
        try {
            downloadChapter(download)

            // Remove successful download from queue
            if (download.status == Download.State.DOWNLOADED) {
                removeFromQueue(download)
            }
            if (areAllDownloadsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            notifier.onError(e.message)
            stop()
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        downloaderJob?.cancel()
        downloaderJob = null
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
        if (chapters.isEmpty()) return

        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val wasEmpty = queueState.value.isEmpty()
        val chaptersToQueue = chapters.asSequence()
            // Filter out those already downloaded.
            .filter { provider.findChapterDir(it.name, it.scanlator, it.url, manga.title, source) == null }
            // Add chapters to queue from the start.
            .sortedByDescending { it.sourceOrder }
            // Filter out those already enqueued.
            .filter { chapter -> queueState.value.none { it.chapter.id == chapter.id } }
            // Create a download for each one.
            .map { Download(source, manga, it) }
            .toList()

        if (chaptersToQueue.isNotEmpty()) {
            addAllToQueue(chaptersToQueue)

            // Start downloader if needed
            if (autoStart && wasEmpty) {
                val queuedDownloads = queueState.value.count { it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size }
                    ?: 0
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    notifier.onWarning(
                        context.stringResource(
                            MR.strings.download_queue_size_warning,
                            context.stringResource(MR.strings.app_name),
                        ),
                        WARNING_NOTIF_TIMEOUT_MS,
                        NotificationHandler.openUrl(context, LibraryUpdateNotifier.HELP_WARNING_URL),
                    )
                }
                DownloadJob.start(context)
            }
        }
    }

    /**
     * Downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     */
    private suspend fun downloadChapter(download: Download) {
        val mangaDir = provider.getMangaDir(download.manga.title, download.source).getOrElse { e ->
            download.status = Download.State.ERROR
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
            return
        }

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.State.ERROR
            notifier.onError(
                context.stringResource(MR.strings.download_insufficient_space),
                download.chapter.name,
                download.manga.title,
                download.manga.id,
            )
            return
        }

        val metaInfoFile = createMetaInfoFile(mangaDir, download.manga, download.source)
        maybeUploadMetaInfo(download, mangaDir, metaInfoFile)

        val chapterDirname = provider.getChapterDirName(
            download.chapter.name,
            download.chapter.scanlator,
            download.chapter.url,
        )
        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)!!

        try {
            // If the page list already exists, start from the file
            val pageList = download.pages ?: run {
                // Otherwise, pull page list from network and add them to download object
                val pages = download.source.getPageList(download.chapter.toSChapter())

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }
                // Don't trust index from source
                val reIndexedPages = pages.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
                download.pages = reIndexedPages
                reIndexedPages
            }

            // Delete all temporary (unfinished) files
            tmpDir.listFiles()
                ?.filter { it.extension == "tmp" }
                ?.forEach { it.delete() }

            download.status = Download.State.DOWNLOADING

            // Start downloading images, consider we can have downloaded images already
            pageList.asFlow().flatMapMerge(concurrency = downloadPreferences.parallelPageLimit().get()) { page ->
                flow {
                    // Fetch image URL if necessary
                    if (page.imageUrl.isNullOrEmpty()) {
                        page.status = Page.State.LoadPage
                        try {
                            page.imageUrl = download.source.getImageUrl(page)
                        } catch (e: Throwable) {
                            page.status = Page.State.Error(e)
                        }
                    }

                    withIOContext { getOrDownloadImage(page, download, tmpDir) }
                    emit(page)
                }
                    .flowOn(Dispatchers.IO)
            }
                .collect {
                    // Do when page is downloaded.
                    notifier.onProgressChange(download)
                }

            // Do after download completes

            if (!isDownloadSuccessful(download, tmpDir)) {
                download.status = Download.State.ERROR
                return
            }

            createComicInfoFile(
                tmpDir,
                download.manga,
                download.chapter,
                download.source,
            )

            // Only rename the directory if it's downloaded
            val downloadedChapter = if (downloadPreferences.saveChaptersAsCBZ().get()) {
                archiveChapter(mangaDir, chapterDirname, tmpDir)
            } else {
                tmpDir.renameTo(chapterDirname)
                mangaDir.findFile(chapterDirname)
            }
            cache.addChapter(chapterDirname, mangaDir, download.manga)

            DiskUtil.createNoMediaFile(tmpDir, context)

            if (downloadedChapter != null) {
                val removedAfterUpload = syncDownloadedChapter(download, downloadedChapter)
                if (removedAfterUpload) {
                    return
                }
            }

            download.status = Download.State.DOWNLOADED
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // If the page list threw, it will resume here
            logcat(LogPriority.ERROR, error)
            download.status = Download.State.ERROR
            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Gets the image from the filesystem if it exists or downloads it otherwise.
     *
     * @param page the page to download.
     * @param download the download of the page.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadImage(page: Page, download: Download, tmpDir: UniFile) {
        // If the image URL is empty, do nothing
        if (page.imageUrl == null) {
            return
        }

        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)
        val tmpFile = tmpDir.findFile("$filename.tmp")

        // Delete temp file if it exists
        tmpFile?.delete()

        // Try to find the image file
        val imageFile = tmpDir.listFiles()?.firstOrNull {
            it.name!!.startsWith("$filename.") || it.name!!.startsWith("${filename}__001")
        }

        try {
            // If the image is already downloaded, do nothing. Otherwise download from network
            val file = when {
                imageFile != null -> imageFile
                chapterCache.isImageInCache(
                    page.imageUrl!!,
                ) -> copyImageFromCache(chapterCache.getImageFile(page.imageUrl!!), tmpDir, filename)
                else -> downloadImage(page, download.source, tmpDir, filename)
            }

            // When the page is ready, set page path, progress (just in case) and status
            splitTallImageIfNeeded(page, tmpDir)

            page.uri = file.uri
            page.progress = 100
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Mark this page as error and allow to download the remaining
            page.progress = 0
            page.status = Page.State.Error(e)
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Downloads the image from network to a file in tmpDir.
     *
     * @param page the page to download.
     * @param source the source of the page.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private suspend fun downloadImage(page: Page, source: HttpSource, tmpDir: UniFile, filename: String): UniFile {
        page.status = Page.State.DownloadImage
        page.progress = 0
        return flow {
            val response = source.getImage(page)
            val file = tmpDir.createFile("$filename.tmp")!!
            try {
                response.body.source().saveTo(file.openOutputStream())
                val extension = getImageExtension(response, file)
                file.renameTo("$filename.$extension")
            } catch (e: Exception) {
                response.close()
                file.delete()
                throw e
            }
            emit(file)
        }
            // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
            .retryWhen { _, attempt ->
                if (attempt < 3) {
                    delay((2L shl attempt.toInt()) * 1000)
                    true
                } else {
                    false
                }
            }
            .first()
    }

    /**
     * Copies the image from cache to file in tmpDir.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private fun copyImageFromCache(cacheFile: File, tmpDir: UniFile, filename: String): UniFile {
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: return tmpFile
        tmpFile.renameTo("$filename.${extension.extension}")
        cacheFile.delete()
        return tmpFile
    }

    /**
     * Returns the extension of the downloaded image from the network response, or if it's null,
     * analyze the file. If everything fails, assume it's a jpg.
     *
     * @param response the network response of the image.
     * @param file the file where the image is already downloaded.
     */
    private fun getImageExtension(response: Response, file: UniFile): String {
        val mime = response.body.contentType()?.run { if (type == "image") "image/$subtype" else null }
        return ImageUtil.getExtensionFromMimeType(mime) { file.openInputStream() }
    }

    private fun splitTallImageIfNeeded(page: Page, tmpDir: UniFile) {
        if (!downloadPreferences.splitTallImages().get()) return

        try {
            val filenamePrefix = "%03d".format(Locale.ENGLISH, page.number)
            val imageFile = tmpDir.listFiles()?.firstOrNull { it.name.orEmpty().startsWith(filenamePrefix) }
                ?: error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))

            // If the original page was previously split, then skip
            if (imageFile.name.orEmpty().startsWith("${filenamePrefix}__")) return

            ImageUtil.splitTallImage(tmpDir, imageFile, filenamePrefix)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    private fun isDownloadSuccessful(
        download: Download,
        tmpDir: UniFile,
    ): Boolean {
        // Page list hasn't been initialized
        val downloadPageCount = download.pages?.size ?: return false

        // Ensure that all pages have been downloaded
        if (download.downloadedImages != downloadPageCount) {
            return false
        }

        // Ensure that the chapter folder has all the pages
        val downloadedImagesCount = tmpDir.listFiles().orEmpty().count {
            val fileName = it.name.orEmpty()
            when {
                fileName in listOf(COMIC_INFO_FILE, NOMEDIA_FILE) -> false
                fileName.endsWith(".tmp") -> false
                // Only count the first split page and not the others
                fileName.contains("__") && !fileName.endsWith("__001.jpg") -> false
                else -> true
            }
        }
        return downloadedImagesCount == downloadPageCount
    }

    /**
     * Archive the chapter pages as a CBZ.
     */
    private fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
    ): UniFile? {
        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")!!
        ZipWriter(context, zip).use { writer ->
            tmpDir.listFiles()?.forEach { file ->
                writer.write(file)
            }
        }
        zip.renameTo("$dirname.cbz")
        tmpDir.delete()
        return mangaDir.findFile("$dirname.cbz")
    }

    private suspend fun maybeUploadMetaInfo(
        download: Download,
        mangaDir: UniFile,
        metaInfoFile: UniFile,
    ) {
        if (!downloadPreferences.cloudSyncEnabled().get()) return

        val config = CloudSyncConfig(
            url = downloadPreferences.cloudSyncUrl().get(),
            username = downloadPreferences.cloudSyncUsername().get(),
            password = downloadPreferences.cloudSyncPassword().get(),
        )
        val destination = downloadPreferences.cloudSyncDestination().get()
        if (!config.isValid || destination.isBlank()) return

        val contentHash = metaInfoFile.sha256()
        if (downloadPreferences.uploadedMetaInfoHash(download.manga.id) == contentHash) {
            return
        }

        val remoteDirectory = combineRemotePath(
            destination,
            provider.getSourceDirName(download.source),
            provider.getMangaDirName(download.manga.title),
        )

        runCatching {
            cloudSyncService.uploadFile(
                config = config,
                remoteDirectory = remoteDirectory,
                file = metaInfoFile,
                overwrite = true,
                onProgress = {},
            )
        }.onSuccess {
            downloadPreferences.markMetaInfoUploadedToCloud(download.manga.id, contentHash)
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Failed to upload meta.info for ${download.manga.title}" }
        }
    }

    private suspend fun syncDownloadedChapter(
        download: Download,
        chapterFile: UniFile,
    ): Boolean {
        if (!downloadPreferences.saveChaptersAsCBZ().get()) return false
        if (!downloadPreferences.cloudSyncEnabled().get()) return false

        val config = CloudSyncConfig(
            url = downloadPreferences.cloudSyncUrl().get(),
            username = downloadPreferences.cloudSyncUsername().get(),
            password = downloadPreferences.cloudSyncPassword().get(),
        )
        val destination = downloadPreferences.cloudSyncDestination().get()
        if (!config.isValid || destination.isBlank()) return false

        download.uploadProgress = 0
        download.status = Download.State.UPLOADING

        val remoteDirectory = combineRemotePath(
            destination,
            provider.getSourceDirName(download.source),
            provider.getMangaDirName(download.manga.title),
        )
        cloudSyncService.uploadCbz(
            config = config,
            remoteDirectory = remoteDirectory,
            file = chapterFile,
            onProgress = { download.uploadProgress = it },
        )

        if (downloadPreferences.cloudSyncDeleteAfterUpload().get()) {
            chapterFile.delete()
            cache.removeChapter(download.chapter, download.manga)
        }

        downloadPreferences.markChapterUploadedToCloud(download.chapter.id)
        download.status = Download.State.DOWNLOADED
        removeFromQueue(download)
        return true
    }

    /**
     * Creates a ComicInfo.xml file inside the given directory.
     */
    private suspend fun createComicInfoFile(
        dir: UniFile,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ) {
        val categories = getCategories.await(manga.id).map { it.name.trim() }.takeUnless { it.isEmpty() }
        val urls = getTracks.await(manga.id)
            .mapNotNull { track ->
                track.remoteUrl.takeUnless { url -> url.isBlank() }?.trim()
            }
            .plus(source.getChapterUrl(chapter.toSChapter()).trim())
            .distinct()

        val comicInfo = getComicInfo(
            manga,
            chapter,
            urls,
            categories,
            source.name,
        )

        // Remove the old file
        dir.findFile(COMIC_INFO_FILE)?.delete()
        dir.createFile(COMIC_INFO_FILE)!!.openOutputStream().use {
            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
            it.write(comicInfoString.toByteArray())
        }
    }

    private suspend fun createMetaInfoFile(
        mangaDir: UniFile,
        manga: Manga,
        source: HttpSource,
    ): UniFile {
        val chapters = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false)
            .sortedWith(getChapterSort(manga))
        val coverAsset = resolveMetaInfoCoverAsset(manga, source)
        val manifest = MetaInfoManifest(
            schemaVersion = 1,
            manga = MetaInfoManga(
                id = manga.id,
                title = manga.title,
                author = manga.author,
                artist = manga.artist,
                status = manga.status.toMetaInfoStatus(),
                description = manga.description,
                tags = manga.genre.orEmpty(),
                source = source.name,
                url = source.getMangaUrl(manga.toSManga()).trim(),
                cover = coverAsset?.entryName,
            ),
            chapters = chapters.map { chapter ->
                MetaInfoChapter(
                    id = chapter.id,
                    title = chapter.name,
                    chapterNumber = chapter.chapterNumber.takeIf { it >= 0 },
                    scanlator = chapter.scanlator,
                    url = source.getChapterUrl(chapter.toSChapter()).trim(),
                    sourceOrder = chapter.sourceOrder,
                    file = provider.chapterOutputName(chapter, downloadPreferences.saveChaptersAsCBZ().get()),
                )
            },
        )

        val tempDirName = META_INFO_FILE_NAME + TMP_DIR_SUFFIX
        mangaDir.findFile(tempDirName)?.delete()
        val tempDir = mangaDir.createDirectory(tempDirName) ?: error("Failed to create meta.info temp directory")
        val manifestFile = tempDir.createFile(META_INFO_MANIFEST_FILE_NAME) ?: error("Failed to create manifest.json")
        manifestFile.openOutputStream().use { output ->
            output.write(json.encodeToString(manifest).toByteArray())
        }

        coverAsset?.let { asset ->
            tempDir.findFile(asset.entryName)?.delete()
            tempDir.createFile(asset.entryName)?.openOutputStream()?.use { output ->
                asset.openStream().use { input -> input.copyTo(output) }
            }
        }

        mangaDir.findFile(META_INFO_TEMP_FILE_NAME)?.delete()
        mangaDir.findFile(META_INFO_FILE_NAME)?.delete()
        val tempArchive = mangaDir.createFile(META_INFO_TEMP_FILE_NAME) ?: error("Failed to create meta.info archive")
        ZipWriter(context, tempArchive).use { writer ->
            writer.write(manifestFile)
            coverAsset?.let { asset ->
                tempDir.findFile(asset.entryName)?.let(writer::write)
            }
        }
        tempArchive.renameTo(META_INFO_FILE_NAME)
        tempDir.delete()

        return mangaDir.findFile(META_INFO_FILE_NAME) ?: error("Failed to finalize meta.info")
    }

    private suspend fun resolveMetaInfoCoverAsset(
        manga: Manga,
        source: HttpSource,
    ): MetaInfoCoverAsset? {
        val customCoverFile = coverCache.getCustomCoverFile(manga.id)
        if (customCoverFile.exists()) {
            val extension = ImageUtil.findImageType(customCoverFile.inputStream())?.extension ?: "jpg"
            return MetaInfoCoverAsset("cover.$extension") { customCoverFile.inputStream() }
        }

        val cachedCoverFile = coverCache.getCoverFile(manga.thumbnailUrl)
        if (cachedCoverFile?.exists() == true) {
            val extension = ImageUtil.findImageType(cachedCoverFile.inputStream())?.extension ?: "jpg"
            return MetaInfoCoverAsset("cover.$extension") { cachedCoverFile.inputStream() }
        }

        val coverUrl = manga.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            source.client.newCall(GET(coverUrl, source.headers)).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body.bytes()
                val extension = ImageUtil.getExtensionFromMimeType(response.body.contentType()?.toString()) { bytes.inputStream() }
                MetaInfoCoverAsset("cover.$extension") { bytes.inputStream() }
            }
        }.getOrElse { error ->
            logcat(LogPriority.WARN, error) { "Failed to fetch cover for meta.info: ${manga.title}" }
            null
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Download.State.UPLOADING.value }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update {
            downloads.forEach { download ->
                download.status = Download.State.QUEUE
            }
            store.addAll(downloads)
            it + downloads
        }
    }

    private fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (
                download.status == Download.State.DOWNLOADING ||
                download.status == Download.State.UPLOADING ||
                download.status == Download.State.QUEUE
            ) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueIf(predicate: (Download) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (
                    download.status == Download.State.DOWNLOADING ||
                    download.status == Download.State.UPLOADING ||
                    download.status == Download.State.QUEUE
                ) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            queue - downloads
        }
    }

    fun removeFromQueue(chapters: List<Chapter>) {
        val chapterIds = chapters.map { it.id }
        removeFromQueueIf { it.chapter.id in chapterIds }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (
                    download.status == Download.State.DOWNLOADING ||
                    download.status == Download.State.UPLOADING ||
                    download.status == Download.State.QUEUE
                ) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    fun updateQueue(downloads: List<Download>) {
        val wasRunning = isRunning

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        pause()
        internalClearQueue()
        addAllToQueue(downloads)

        if (wasRunning) {
            start()
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30
        private const val META_INFO_FILE_NAME = "meta.info"
        private const val META_INFO_TEMP_FILE_NAME = "meta.info.tmp"
        private const val META_INFO_MANIFEST_FILE_NAME = "manifest.json"
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024

private fun combineRemotePath(vararg parts: String): String {
    return normalizePath(parts.joinToString("/") { it.trim('/') })
}

private fun UniFile.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    openInputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun Long.toMetaInfoStatus(): String = when (toInt()) {
    SManga.ONGOING -> "ongoing"
    SManga.COMPLETED -> "completed"
    SManga.LICENSED -> "licensed"
    SManga.PUBLISHING_FINISHED -> "publishing_finished"
    SManga.CANCELLED -> "cancelled"
    SManga.ON_HIATUS -> "on_hiatus"
    else -> "unknown"
}

@Serializable
private data class MetaInfoManifest(
    val schemaVersion: Int,
    val manga: MetaInfoManga,
    val chapters: List<MetaInfoChapter>,
)

@Serializable
private data class MetaInfoManga(
    val id: Long,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val status: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val source: String,
    val url: String,
    val cover: String? = null,
)

@Serializable
private data class MetaInfoChapter(
    val id: Long,
    val title: String,
    val chapterNumber: Double? = null,
    val scanlator: String? = null,
    val url: String,
    val sourceOrder: Long,
    val file: String,
)

private data class MetaInfoCoverAsset(
    val entryName: String,
    val openStream: () -> java.io.InputStream,
)
