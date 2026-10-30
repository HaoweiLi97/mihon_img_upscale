package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter

@OptIn(ExperimentalCoroutinesApi::class)
class HttpPageLoaderTest {
    @Test
    fun `offscreen holders cannot cancel the first visible download or start ahead of it`() = runTest {
        val fixture = Fixture()
        val loader = fixture.loader(StandardTestDispatcher(testScheduler), backgroundScope)
        try {
            val pages = loader.getPages()
            fixture.chapter.state = ReaderChapter.State.Loaded(pages)
            runCurrent()
            assertEquals(listOf(0), fixture.started)

            // ViewPager creates the next two holders before the first image finishes downloading.
            loader.loadPage(pages[1])
            loader.loadPage(pages[2])
            runCurrent()
            assertFalse(0 in fixture.cancelled, "An offscreen holder cancelled the visible image")
            assertEquals(listOf(0), fixture.started)
        } finally {
            loader.recycle()
            runCurrent()
        }
    }

    @Test
    fun `opening a resumed chapter starts at the requested page instead of page zero`() = runTest {
        val fixture = Fixture().apply { chapter.requestedPage = 6 }
        val loader = fixture.loader(StandardTestDispatcher(testScheduler), backgroundScope)
        try {
            loader.getPages()
            runCurrent()
            assertEquals(listOf(6), fixture.started)
        } finally {
            loader.recycle()
            runCurrent()
        }
    }

    @Test
    fun `selecting a distant page interrupts old downloads and ignores late offscreen subscriptions`() = runTest {
        val fixture = Fixture()
        val loader = fixture.loader(StandardTestDispatcher(testScheduler), backgroundScope)
        try {
            val pages = loader.getPages()
            fixture.chapter.state = ReaderChapter.State.Loaded(pages)
            runCurrent()

            loader.onPageSelected(pages[9])
            loader.loadPage(pages[2])
            runCurrent()

            assertEquals(listOf(0, 9), fixture.started)
            assertTrue(0 in fixture.cancelled)
            assertEquals(Page.State.Queue, pages[0].status)
            assertEquals(Page.State.DownloadImage, pages[9].status)
        } finally {
            loader.recycle()
            runCurrent()
        }
    }

    @Test
    fun `adjacent pages preload after the visible image finishes without downloading it twice`() = runTest {
        val fixture = Fixture()
        val loader = fixture.loader(StandardTestDispatcher(testScheduler), backgroundScope)
        try {
            val pages = loader.getPages()
            fixture.chapter.state = ReaderChapter.State.Loaded(pages)
            loader.onPageSelected(pages[0])
            loader.loadPage(pages[0])
            runCurrent()
            assertEquals(listOf(0), fixture.started)

            fixture.downloads[0].complete(Unit)
            runCurrent()
            assertEquals(Page.State.Ready, pages[0].status)
            assertEquals(listOf(0, 1, 2), fixture.started)
        } finally {
            loader.recycle()
            runCurrent()
        }
    }

    private class Fixture {
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 1))
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val downloads = List(12) { CompletableDeferred<Unit>() }
        private val source = mockk<HttpSource>()
        private val cache = mockk<ChapterCache>(relaxed = true)
        private val preferences = ReaderPreferences(InMemoryPreferenceStore())

        init {
            every { cache.getPageListFromCache(any()) } returns List(12) { index ->
                Page(index, imageUrl = "https://images.example/$index.jpg")
            }
            every { cache.isImageInCache(any()) } returns false
            coEvery { source.getImage(any(), any()) } coAnswers {
                val index = firstArg<Page>().index
                started += index
                try {
                    downloads[index].await()
                    mockk(relaxed = true)
                } finally {
                    if (!downloads[index].isCompleted) cancelled += index
                }
            }
        }

        fun loader(
            dispatcher: kotlinx.coroutines.CoroutineDispatcher,
            appScope: kotlinx.coroutines.CoroutineScope,
        ) = HttpPageLoader(chapter, source, cache, appScope, { true }, preferences, dispatcher)
    }
}
