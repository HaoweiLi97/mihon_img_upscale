package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.notification.TransferTaskCounts
import eu.kanade.tachiyomi.data.notification.transferStatusNotification
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.networkStateFlow
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

fun interface CloudUploadScheduler {
    fun enqueue(wifiOnly: Boolean)
}

class CloudUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = coroutineScope {
        val context = applicationContext
        val graph = context.appGraph
        val wifiPreference = graph.downloadPreferences.downloadOnlyOverWifi
        val scheduledWifiOnly = inputData.getBoolean(WIFI_ONLY, true)
        if (wifiPreference.get() != scheduledWifiOnly) {
            enqueue(context, wifiPreference.get())
            return@coroutineScope Result.success()
        }
        val canUseNetwork = {
            val wifiOnly = wifiPreference.get()
            wifiOnly == scheduledWifiOnly && networkAllowed(context, wifiOnly)
        }
        if (!canUseNetwork()) return@coroutineScope Result.retry()
        val foreground = setForegroundSafely()
        val manager = graph.downloadManager
        if (!foreground) context.notify(notificationId, getForegroundInfo().notification)

        val notificationUpdates = launch {
            manager.transferTaskCounts().collect { counts ->
                val info = progressInfo(counts.total, counts.active)
                if (foreground) {
                    try {
                        setForeground(info)
                    } catch (error: IllegalStateException) {
                        logcat(LogPriority.WARN, error) { "Could not update the cloud upload notification" }
                    }
                } else {
                    context.notify(info.notificationId, info.notification)
                }
            }
        }
        val networkLost = async(start = CoroutineStart.UNDISPATCHED) {
            combine(
                context.uploadNetworkStates(),
                wifiPreference.changes(),
            ) { state, wifiOnly ->
                wifiOnly != scheduledWifiOnly ||
                    !automaticUploadNetworkAllowed(state.isConnected, state.isWifi, wifiOnly)
            }.first { it }
        }
        val uploads = async { manager.runCloudUploads(canUseNetwork) }
        try {
            select {
                networkLost.onAwait {
                    uploads.cancelAndJoin()
                    if (wifiPreference.get() != scheduledWifiOnly) {
                        enqueue(context, wifiPreference.get())
                        Result.success()
                    } else {
                        Result.retry()
                    }
                }
                uploads.onAwait { retry ->
                    networkLost.cancelAndJoin()
                    if (retry) Result.retry() else Result.success()
                }
            }
        } finally {
            notificationUpdates.cancel()
            if (!foreground) context.cancelNotification(notificationId)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val counts = applicationContext.appGraph.downloadManager.transferTaskCounts().first()
        return progressInfo(counts.total, counts.active)
    }

    private val notificationId: Int
        get() = if (inputData.getBoolean(WIFI_ONLY, true)) -204 else -206

    internal fun progressInfo(totalTasks: Int, activeTasks: Int): ForegroundInfo {
        val context = applicationContext
        return ForegroundInfo(
            notificationId,
            context.transferStatusNotification(
                Notifications.CHANNEL_CLOUD_SYNC_PROGRESS,
                TransferTaskCounts(totalTasks, activeTasks),
            ),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    companion object {
        private const val WIFI_ONLY = "wifi_only"

        private fun name(wifiOnly: Boolean) = if (wifiOnly) "cloud-uploads-wifi" else "cloud-uploads"

        internal fun isRunningFlow(context: Context): Flow<Boolean> = context.workManager
            .getWorkInfosByTagFlow(CloudUploadWorker::class.java.name)
            .map { work -> work.any { it.state == WorkInfo.State.RUNNING } }
            .distinctUntilChanged()

        internal fun schedule(context: Context, wifiOnly: Boolean) {
            // A new upload may arrive while a drained worker is still returning success.
            enqueue(context, wifiOnly, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        internal fun enqueue(context: Context, wifiOnly: Boolean, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            if (!WorkManager.isInitialized()) return
            val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
                .setInputData(workDataOf(WIFI_ONLY to wifiOnly))
                .setConstraints(constraints(wifiOnly))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(wifiOnly), policy, request)
        }

        internal fun updateNetworkConstraint(context: Context, wifiOnly: Boolean) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(name(!wifiOnly))
            if (DownloadStore.hasCloudUploads(context)) enqueue(context, wifiOnly)
        }

        internal fun networkAllowed(context: Context, wifiOnly: Boolean): Boolean {
            val state = context.activeNetworkState()
            return automaticUploadNetworkAllowed(state.isConnected, state.isWifi, wifiOnly)
        }

        internal fun constraints(wifiOnly: Boolean): Constraints {
            val builder = Constraints.Builder()
            if (wifiOnly) {
                val request = NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                builder.setRequiredNetworkRequest(request, NetworkType.CONNECTED)
            } else {
                builder.setRequiredNetworkType(NetworkType.CONNECTED)
            }
            return builder.build()
        }
    }
}

internal fun automaticUploadNetworkAllowed(connected: Boolean, isWifi: Boolean, wifiOnly: Boolean): Boolean =
    connected && (!wifiOnly || isWifi)

private fun Context.uploadNetworkStates() = merge(
    networkStateFlow(),
    flow {
        // Recheck after handovers where a callback arrives before the new default route is visible.
        while (true) {
            delay(1.seconds)
            emit(activeNetworkState())
        }
    },
).onStart { emit(activeNetworkState()) }
