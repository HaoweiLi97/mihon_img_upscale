package eu.kanade.tachiyomi.data.download

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudStorageReleaseWorkDataTest {
    @Test
    fun releaseResultKeepsCountsAndReasonsWhenRestoredFromWorkManager() {
        val reasons = CloudStorageReleaseReason.entries.associateWith { it.ordinal + 1 }
        val result = CloudStorageReleaseResult(released = 12, kept = reasons.values.sum(), reasons = reasons)

        val persistedData = result.toWorkData().toByteArray()

        assertEquals(result, Data.fromByteArray(persistedData).toCloudStorageReleaseResult())
    }

    @Test
    fun releaseWorkNamesSeparateCategoriesFromMangaWithTheSameId() {
        val mangaWork = cloudStorageReleaseWorkName(mangaId = 12)
        val categoryWork = cloudStorageReleaseWorkName(categoryId = 12)

        assertNotEquals(mangaWork, categoryWork)
        assertEquals(mangaWork, cloudStorageReleaseWorkName(mangaId = 12))
        assertNotEquals(mangaWork, cloudStorageReleaseWorkName(mangaId = 13))
    }

    @Test
    fun enqueuedReleaseKeepsItsResultWithoutAnyScreenObservingIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val categoryId = Long.MAX_VALUE - SystemClock.elapsedRealtime()
        val name = cloudStorageReleaseWorkName(categoryId = categoryId)
        val manager = WorkManager.getInstance(context)
        try {
            CloudStorageReleaseWorker.enqueue(context, categoryId = categoryId, targetId = "test-release-target")
            val jobs = withTimeout(30_000) {
                manager.getWorkInfosForUniqueWorkFlow(name).first { works -> works.any { it.state.isFinished } }
            }
            val job = jobs.single()

            assertEquals(WorkInfo.State.SUCCEEDED, job.state)
            assertEquals(CloudStorageReleaseResult(), job.outputData.toCloudStorageReleaseResult())
            val releaseJobs = CloudStorageReleaseWorker.workInfos(context).first { allJobs -> job in allJobs }
            assertTrue(job in CloudStorageReleaseWorker.unacknowledged(context, releaseJobs))
            CloudStorageReleaseWorker.acknowledge(context, job.id, releaseJobs)
            assertFalse(job in CloudStorageReleaseWorker.unacknowledged(context, releaseJobs))
        } finally {
            manager.cancelUniqueWork(name)
        }
    }
}
