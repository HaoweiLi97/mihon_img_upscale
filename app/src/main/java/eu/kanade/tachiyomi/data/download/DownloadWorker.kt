package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.notification.transferStatusNotification
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.R
import kotlin.time.Duration.Companion.seconds

/**
 * This worker owns the lifecycle of the downloader: it starts the downloader and stops it when the
 * worker is stopped by the system or there's no suitable network available.
 */
class DownloadWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var downloader: Downloader

    @Inject private lateinit var downloadPreferences: DownloadPreferences

    init {
        graph.inject(this)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(graph.downloadManager.downloadTaskCounts().first())

    override suspend fun doWork(): Result = coroutineScope {
        var networkIssue = networkIssue()
        if (networkIssue != null) {
            logcat(LogPriority.WARN) { "Download worker stopped before starting: $networkIssue" }
            downloader.stop(networkIssue)
            return@coroutineScope Result.failure()
        }

        if (!downloader.start(retryFailed = inputData.getBoolean(RETRY_FAILED, false))) {
            logcat(LogPriority.DEBUG) { "No new download run started" }
            return@coroutineScope Result.failure()
        }

        try {
            val initialForeground = getForegroundInfo()
            val isForeground = updateForeground(initialForeground)
            val notifications = launch {
                var canUpdateForeground = isForeground
                graph.downloadManager.downloadTaskCounts().collect { counts ->
                    val info = foregroundInfo(counts)
                    if (canUpdateForeground) canUpdateForeground = updateForeground(info)
                    if (!canUpdateForeground) applicationContext.notify(info.notificationId, info.notification)
                }
            }
            try {
                // Give WorkManager's service time to enter the foreground before a short download finishes.
                if (isForeground) delay(0.5.seconds)
                while (networkIssue == null && downloader.isRunning) {
                    delay(1.seconds)
                    networkIssue = networkIssue()
                }
            } finally {
                notifications.cancel()
                if (!isForeground) applicationContext.cancelNotification(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS)
            }
        } finally {
            // Stopped by WorkManager means already canceled, and taking the lock would then throw instead of stopping
            if (downloader.isRunning && (networkIssue != null || isStopped || !isActive)) {
                withContext(NonCancellable) { downloader.stop(networkIssue) }
            }
        }

        Result.success()
    }

    private fun foregroundInfo(counts: TransferTaskCounts) = ForegroundInfo(
        Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS,
        applicationContext.transferStatusNotification(
            Notifications.CHANNEL_DOWNLOADER_PROGRESS,
            applicationContext.stringResource(MR.strings.transfer_status_download_count, counts.total, counts.active),
        ),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private suspend fun updateForeground(info: ForegroundInfo): Boolean {
        return try {
            setForeground(info)
            true
        } catch (error: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            logcat(LogPriority.WARN, error) { "Could not move the downloader to the foreground" }
            false
        }
    }

    private fun networkIssue(): String? {
        val state = applicationContext.activeNetworkState()
        return when {
            !state.isOnline -> applicationContext.getString(R.string.download_notifier_no_network)
            downloadPreferences.downloadOnlyOverWifi.get() && !state.isWifi ->
                applicationContext.getString(R.string.download_notifier_text_only_wifi)
            else -> null
        }
    }

    companion object {
        private const val TAG = "Downloader"
        private const val RETRY_FAILED = "retry_failed"

        fun start(
            context: Context,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
            retryFailed: Boolean = false,
        ) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .addTag(TAG)
                .setInputData(workDataOf(RETRY_FAILED to retryFailed))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, policy, request)
        }

        fun isRunningFlow(context: Context): Flow<Boolean> = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(TAG)
            .map { list -> list.any { it.state == WorkInfo.State.RUNNING } }
            .distinctUntilChanged()
    }
}
