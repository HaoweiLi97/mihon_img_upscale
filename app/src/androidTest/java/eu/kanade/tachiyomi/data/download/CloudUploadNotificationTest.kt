package eu.kanade.tachiyomi.data.download

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

@RunWith(AndroidJUnit4::class)
class CloudUploadNotificationTest {
    @Test
    fun foregroundUploadShowsTaskCountsAndDisappearsWhenWorkFinishes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notifications = checkNotNull(context.getSystemService<NotificationManager>())
        assumeTrue("Mihon must have notification permission", notifications.areNotificationsEnabled())
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<CloudUploadNotificationTestWorker>().build()
        val activeUpload = flow {
            while (true) {
                emit(
                    notifications.activeNotifications.firstOrNull {
                        it.notification.channelId == Notifications.CHANNEL_CLOUD_SYNC_PROGRESS
                    }?.notification,
                )
                delay(50)
            }
        }

        CloudUploadNotificationTestWorker.stage.value = UploadStage.STARTING
        val idlePreference = context.appGraph.downloadPreferences.showIdleTransferNotification
        val previousIdle = idlePreference.get().takeIf { idlePreference.isSet() }
        idlePreference.set(false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.ready = true }
                try {
                    workManager.enqueue(request)
                    val starting = withTimeout(15_000) { checkNotNull(activeUpload.first { it != null }) }
                    assertEquals(
                        NotificationHandler.openDownloadManagerPendingActivity(context),
                        starting.contentIntent,
                    )
                    verifyTransferCounts(starting, 0, 0)

                    CloudUploadNotificationTestWorker.stage.value = UploadStage.UPLOADING
                    val active = awaitCounts(context, activeUpload, 4, 2)
                    assertTrue(active.flags and Notification.FLAG_ONGOING_EVENT != 0)
                    verifyTransferCounts(active, 4, 2)
                    captureTransferNotificationShadeIfRequested("cloud-upload-notification.png", 4, 2)

                    scenario.moveToState(Lifecycle.State.CREATED)
                    withTimeout(15_000) {
                        ProcessLifecycleOwner.get().lifecycle.currentStateFlow.first {
                            !it.isAtLeast(Lifecycle.State.STARTED)
                        }
                    }
                    CloudUploadNotificationTestWorker.stage.value = UploadStage.BACKGROUND
                    verifyTransferCounts(awaitCounts(context, activeUpload, 4, 3), 4, 3)

                    CloudUploadNotificationTestWorker.stage.value = UploadStage.FINISHED
                    val work = withTimeout(15_000) {
                        workManager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }
                    }
                    assertEquals(WorkInfo.State.SUCCEEDED, work?.state)
                    withTimeout(15_000) { activeUpload.first { it == null } }
                    Unit
                } finally {
                    CloudUploadNotificationTestWorker.stage.value = UploadStage.FINISHED
                    workManager.cancelWorkById(request.id)
                }
            }
        } finally {
            if (previousIdle == null) idlePreference.delete() else idlePreference.set(previousIdle)
        }
    }

    private suspend fun awaitCounts(
        context: Context,
        notifications: Flow<Notification?>,
        total: Int,
        active: Int,
    ): Notification = withTimeout(15_000) {
        val text = context.stringResource(MR.strings.transfer_status_count_summary, total, active)
        checkNotNull(notifications.first { it?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == text })
    }
}

internal enum class UploadStage { STARTING, UPLOADING, BACKGROUND, FINISHED }

class CloudUploadNotificationTestWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(
    context,
    parameters,
) {
    private val cloudUploadWorker = CloudUploadWorker(context, parameters)

    override suspend fun doWork(): Result {
        setForeground(cloudUploadWorker.getForegroundInfo())
        if (stage.first { it != UploadStage.STARTING } == UploadStage.UPLOADING) {
            setForeground(cloudUploadWorker.progressInfo(totalTasks = 4, activeTasks = 2))
        }
        if (stage.first { it >= UploadStage.BACKGROUND } == UploadStage.BACKGROUND) {
            setForeground(cloudUploadWorker.progressInfo(totalTasks = 4, activeTasks = 3))
        }
        stage.first { it == UploadStage.FINISHED }
        return Result.success()
    }

    companion object {
        internal val stage = MutableStateFlow(UploadStage.STARTING)
    }
}
