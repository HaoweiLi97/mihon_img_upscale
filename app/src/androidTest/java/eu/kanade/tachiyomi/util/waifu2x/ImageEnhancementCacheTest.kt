package eu.kanade.tachiyomi.util.waifu2x

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import eu.kanade.tachiyomi.util.image.EnhancementImageDecoder as ImageDecoder

@RunWith(AndroidJUnit4::class)
class ImageEnhancementCacheTest {

    private lateinit var directory: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "enhanced-cache-regression-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun snapshotRemainsDecodableWhenSwitchingModelsDeletesTheFile() {
        val file = writeImage("old-model.webp")
        val snapshot = ImageEnhancementCache.readCachedImage(file)
        assertNotNull(snapshot)
        assertTrue(file.delete())
        val decoder = ImageDecoder.open(snapshot!!.inputStream())
        assertNotNull("Reader native decoder must accept the snapshot", decoder)
        try {
            val bitmap = decoder!!.decode(sampleSize = 1)
            assertNotNull(bitmap)
            bitmap?.recycle()
        } finally {
            decoder?.recycle()
        }
    }

    @Test
    fun emptyAndCorruptCachesAreDiscarded() {
        for ((index, bytes) in listOf(byteArrayOf(), "not an image".toByteArray()).withIndex()) {
            val file = File(directory, "corrupt-$index.webp").apply { writeBytes(bytes) }
            assertNull(ImageEnhancementCache.readCachedImage(file))
            assertFalse(file.exists())
        }
    }

    @Test
    fun truncatedCachedImageIsDiscarded() {
        val file = writeImage("truncated.webp")
        file.writeBytes(file.readBytes().copyOf(16))
        assertNull(ImageEnhancementCache.readCachedImage(file))
        assertFalse(file.exists())
    }

    @Test
    fun transparentCacheCannotReplaceAnOriginalPage() {
        val file = writeImage("transparent.webp", Color.TRANSPARENT)
        assertNull(ImageEnhancementCache.readCachedImage(file))
        assertFalse(file.exists())
    }

    @Test
    fun aModelSwitchBeforeTheReadIsACacheMiss() {
        val file = writeImage("removed.webp")
        assertTrue(file.delete())
        assertNull(ImageEnhancementCache.readCachedImage(file))
    }

    @Test
    fun rejectingAnOldSnapshotDoesNotDeleteItsNewReplacement() {
        val file = File(directory, "replacement.webp").apply { writeBytes("invalid".toByteArray()) }
        val oldSnapshot = Buffer().write(file.readBytes())
        writeImage(file.name)
        val replacement = file.readBytes()
        ImageEnhancementCache.discardCachedImage(file, oldSnapshot)
        assertArrayEquals(replacement, file.readBytes())
    }

    @Test
    fun validCacheCanBeDecodedAtFullResolution() {
        val file = writeImage("valid.webp")
        val bitmap = ImageEnhancementCache.decodeCachedImage(file)
        assertNotNull(bitmap)
        assertTrue(bitmap!!.width == 64 && bitmap.height == 96)
        bitmap.recycle()
        assertTrue(file.exists())
    }

    @Test
    fun inkFilterSettingsProduceDistinctCacheEntries() {
        val raw = ImageEnhancementCache.getConfigHash(noise = 3, scale = 2)
        val ink = ImageEnhancementCache.getConfigHash(noise = 3, scale = 2, inkFilter = true, inkBleeding = 20)
        val strongerInk = ImageEnhancementCache.getConfigHash(noise = 3, scale = 2, inkFilter = true, inkBleeding = 40)
        assertNotEquals(raw, ink)
        assertNotEquals(ink, strongerInk)
    }

    @Test
    fun cancellingBlockedSaveReleasesItsBitmapAndClearingChapterRejectsQueuedSaves() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ImageEnhancementCache.init(context)
        val mangaId = 9_000_002L
        val chapterId = System.nanoTime()
        // Hold the disk writer before it can commit, making queue backpressure
        // deterministic without timing-dependent large-image compression.
        val lockField = ImageEnhancementCache::class.java.getDeclaredField("cacheLock").apply { isAccessible = true }
        val lock = (lockField.get(ImageEnhancementCache) as ReentrantReadWriteLock).writeLock()
        val bitmaps = List(3) { Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) } }
        lock.lock()
        try {
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                ImageEnhancementCache.enqueueSaveToCache(mangaId, chapterId, 0, "regression", bitmaps[0])
            }
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                ImageEnhancementCache.enqueueSaveToCache(mangaId, chapterId, 1, "regression", bitmaps[1])
            }
            withTimeout(5000) {
                first.join()
                second.join()
            }
            val blocked = launch(start = CoroutineStart.UNDISPATCHED) {
                ImageEnhancementCache.enqueueSaveToCache(mangaId, chapterId, 2, "regression", bitmaps[2])
            }
            assertFalse(blocked.isCompleted)
            blocked.cancelAndJoin()
            assertTrue(bitmaps[2].isRecycled)
            assertFalse(ImageEnhancementCache.isSavePending(mangaId, chapterId, 2))
        } finally {
            ImageEnhancementCache.clearChapterCache(mangaId, chapterId)
            lock.unlock()
        }
        withTimeout(5000) {
            while (bitmaps.any { !it.isRecycled }) delay(10)
        }
        for (page in 0..2) {
            assertNull(ImageEnhancementCache.getCachedImage(mangaId, chapterId, page, "regression"))
        }
        ImageEnhancementCache.clearChapterCache(mangaId, chapterId)
    }

    private fun writeImage(name: String, color: Int = Color.WHITE): File {
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val file = File(directory, name)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
