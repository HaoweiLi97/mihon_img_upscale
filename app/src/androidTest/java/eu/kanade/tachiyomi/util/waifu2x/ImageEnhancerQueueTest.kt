package eu.kanade.tachiyomi.util.waifu2x

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ImageEnhancerQueueTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val mangaId = 9_000_001L
    private val chapterId = System.nanoTime()
    private val gates = mutableListOf<CountDownLatch>()

    @Before
    fun setUp() {
        ImageEnhancementCache.init(context)
        ImageEnhancer.cancelAll(resetNative = false)
        ImageEnhancer.reprioritizeAround(0)
    }

    @After
    fun tearDown() {
        ImageEnhancer.cancelAll(resetNative = false)
        gates.forEach(CountDownLatch::countDown)
        ImageEnhancementCache.clearChapterCache(mangaId, chapterId)
    }

    @Test
    fun completingCancelledAttemptDoesNotForgetItsReplacement() {
        val firstStarted = CountDownLatch(1)
        val firstGate = gate()
        enqueue(0) {
            firstStarted.countDown()
            await(firstGate)
        }
        await(firstStarted)
        ImageEnhancer.cancel(mangaId, chapterId, 0)

        val replacementStarted = CountDownLatch(1)
        val replacementGate = gate()
        enqueue(0) {
            replacementStarted.countDown()
            await(replacementGate)
        }
        firstGate.countDown()
        await(replacementStarted)
        assertTrue("The old attempt must not remove the new pending request", hasRequest(0))
        replacementGate.countDown()
        drain()
    }

    @Test
    fun pruningRetainsTheVisibleSecondaryPage() {
        val started = CountDownLatch(1)
        val blocked = gate()
        enqueue(0) {
            started.countDown()
            await(blocked)
        }
        await(started)
        ImageEnhancer.reprioritizeAround(5, secondaryPageIndex = 4)
        enqueue(4)
        ImageEnhancer.cancelRequestsLessThan(context, mangaId, chapterId, 5)
        assertTrue("Visible secondary page must survive preload pruning", hasRequest(4))
        blocked.countDown()
        drain()
    }

    @Test
    fun queuedPagesFollowVisibleSpreadThenPromotionThenDistance() {
        val started = CountDownLatch(1)
        val blocked = gate()
        enqueue(0) {
            started.countDown()
            await(blocked)
        }
        await(started)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val done = CountDownLatch(5)
        for (page in listOf(20, 8, 11, 12, 13)) {
            enqueue(page, highPriority = page == 8) {
                order += page
                done.countDown()
            }
        }
        ImageEnhancer.reprioritizeAround(12, secondaryPageIndex = 11)
        blocked.countDown()
        await(done)
        assertEquals(listOf(12, 11, 8, 13, 20), order.toList())
        drain()
    }

    @Test
    fun aFailedSourceDoesNotStopTheWorker() {
        val attempted = CountDownLatch(1)
        enqueue(0) {
            attempted.countDown()
            error("Source unavailable")
        }
        await(attempted)
        drain()
    }

    @Test
    fun anOutOfMemorySourceKeepsTheWorkerAliveAndStopsAutomaticRetries() {
        val attempted = CountDownLatch(1)
        enqueue(0) {
            attempted.countDown()
            throw OutOfMemoryError("Synthetic enhancement allocation failure")
        }
        await(attempted)
        drain()
        val preferences = Injekt.get<ReaderPreferences>()
        assertTrue(
            "A failed optional enhancement must not be re-enqueued by polling",
            ImageEnhancementCache.isSkipped(mangaId, chapterId, 0, ImageEnhancementCache.getConfigHash(preferences)),
        )
    }

    private fun gate() = CountDownLatch(1).also { gates += it }

    private fun enqueue(page: Int, highPriority: Boolean = false, action: () -> Unit = {}) {
        ImageEnhancer.enhanceLazy(context, mangaId, chapterId, page, highPriority) {
            action()
            null // Queue tests do not start image decoding or GPU inference.
        }
    }

    private fun hasRequest(page: Int) = ImageEnhancer.hasRequest(mangaId, chapterId, page)

    private fun drain() {
        val drained = CountDownLatch(1)
        enqueue(1000) { drained.countDown() }
        await(drained)
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Timed out waiting for enhancement worker", latch.await(10, TimeUnit.SECONDS))
    }
}
