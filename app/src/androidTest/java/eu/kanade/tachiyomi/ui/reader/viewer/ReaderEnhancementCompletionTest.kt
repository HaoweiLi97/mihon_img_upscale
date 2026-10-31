package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReaderEnhancementCompletionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun visibleOriginalIsEnhancedAndTheReaderShowsCompleted() = withReader { scenario, view, mangaId, chapterId, hash ->
        awaitLabel(scenario, view, context.stringResource(MR.strings.reader_status_processed))
        val file = ImageEnhancementCache.getCachedImage(mangaId, chapterId, 0, hash)
        assertNotNull("The completion label must represent a saved enhancement", file)
        val bitmap = ImageEnhancementCache.decodeCachedImage(requireNotNull(file))
        try {
            assertNotNull(bitmap)
            assertEquals(96, bitmap!!.width)
            assertEquals(128, bitmap.height)
            assertTrue(ImageEnhancementCache.isDisplayable(bitmap))
        } finally {
            bitmap?.recycle()
        }
    }

    @Test
    fun completedCacheWithoutASavedNotificationCannotLeaveTheReaderAtZeroPercent() {
        val sourceGate = CountDownLatch(1)
        try {
            withReader(beforeDisplay = { mangaId, chapterId ->
                ImageEnhancer.enhanceLazy(context, mangaId, chapterId, 0, highPriority = true) {
                    check(sourceGate.await(30, TimeUnit.SECONDS))
                    null
                }
            }) { scenario, view, mangaId, chapterId, hash ->
                awaitLabel(scenario, view, context.stringResource(MR.strings.reader_status_preparing))
                // Reproduce a dropped Saved notification: commit valid cache bytes directly.
                val file = File(context.cacheDir, "realcugan_cache/$mangaId/$chapterId/0_$hash.webp")
                file.parentFile!!.mkdirs()
                val cached = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                try {
                    file.outputStream().use { assertTrue(cached.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    cached.recycle()
                }
                sourceGate.countDown()
                awaitLabel(scenario, view, context.stringResource(MR.strings.reader_status_processed))
            }
        } finally {
            sourceGate.countDown()
        }
    }

    private fun withReader(
        beforeDisplay: (Long, Long) -> Unit = { _, _ -> },
        verify: (ActivityScenario<MainActivity>, ReaderPageImageView, Long, Long, String) -> Unit,
    ) {
        val preferences = Injekt.get<ReaderPreferences>()
        val restore = mutableListOf<() -> Unit>()
        fun <T> set(preference: Preference<T>, value: T) {
            val old = preference.get()
            restore += { preference.set(old) }
            preference.set(value)
        }
        val mangaId = 9_000_040L
        val chapterId = System.nanoTime()
        var view: ReaderPageImageView? = null
        try {
            set(preferences.realCuganEnabled(), true)
            set(preferences.realCuganShowStatus(), true)
            set(preferences.realCuganProcessingBackend(), Waifu2x.PROCESSING_BACKEND_VULKAN)
            set(preferences.realCuganModel(), 0)
            set(preferences.realCuganNoiseLevel(), 0)
            set(preferences.realCuganScale(), 2)
            set(preferences.realCuganPrecision(), 1)
            set(preferences.realCuganTileSize(), 32)
            set(preferences.realCuganSkipMaxSizeWidth(), 0)
            set(preferences.realCuganSkipMaxSizeHeight(), 0)
            set(preferences.inkFilter(), false)
            ImageEnhancementCache.init(context)
            val hash = ImageEnhancementCache.getConfigHash(preferences)
            ImageEnhancer.reset(0)
            beforeDisplay(mangaId, chapterId)
            val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until height) {
                    for (x in 0 until width) setPixel(x, y, Color.rgb(40 + x * 3, 50 + y * 2, 90 + x))
                }
            }
            val bytes = try {
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            } finally {
                bitmap.recycle()
            }
            val page = ReaderPage(0, stream = { bytes.inputStream() }).apply {
                chapter = ReaderChapter(Chapter.create().copy(id = chapterId, mangaId = mangaId))
                status = Page.State.Ready
            }
            val targetMangaId = mangaId
            val targetChapterId = chapterId
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val loaded = CountDownLatch(1)
                scenario.onActivity { activity ->
                    view = ReaderPageImageView(activity).apply {
                        this.mangaId = targetMangaId
                        this.chapterId = targetChapterId
                        pageIndex = 0
                        readerPage = page
                        onImageLoaded = { loaded.countDown() }
                    }
                    activity.setContentView(view)
                    view!!.setImage(
                        Buffer().write(bytes),
                        false,
                        ReaderPageImageView.Config(zoomDuration = 1),
                        page.stream,
                    )
                    view!!.onPageSelected(true)
                }
                assertTrue(
                    "The original page must become readable before enhancement",
                    loaded.await(15, TimeUnit.SECONDS),
                )
                verify(scenario, requireNotNull(view), mangaId, chapterId, hash)
                scenario.onActivity { view?.recycle() }
            }
        } finally {
            ImageEnhancer.cancelAll("reader completion test finished")
            restore.asReversed().forEach { it() }
            ImageEnhancementCache.clearChapterCache(mangaId, chapterId)
        }
    }

    private fun awaitLabel(
        scenario: ActivityScenario<MainActivity>,
        view: ReaderPageImageView,
        expected: String,
    ) = runBlocking {
        withTimeout(90_000) {
            while (true) {
                var actual: String? = null
                scenario.onActivity {
                    actual = (0 until view.childCount).mapNotNull { view.getChildAt(it) as? TextView }
                        .firstOrNull { it.isShown }?.text?.toString()
                }
                if (actual == expected) break
                delay(100)
            }
        }
    }
}
