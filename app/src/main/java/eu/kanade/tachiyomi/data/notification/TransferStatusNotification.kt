package eu.kanade.tachiyomi.data.notification

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.CloudUploadWorker
import eu.kanade.tachiyomi.data.download.DownloadWorker
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Context.observeIdleTransferNotification(scope: CoroutineScope, refresh: Flow<Int>) {
    val graph = appGraph
    val status = graph.downloadPreferences.showIdleTransferNotification.changes().flatMapLatest { enabled ->
        if (!enabled) return@flatMapLatest flowOf(null)
        val manager = graph.downloadManager
        combine(
            DownloadWorker.isRunningFlow(this),
            CloudUploadWorker.isRunningFlow(this),
            manager.downloadTaskCounts(),
            manager.uploadTaskCounts(),
        ) { downloading, uploading, downloadCounts, uploadCounts ->
            if (downloading || uploading) null else downloadCounts.total to uploadCounts.total
        }.distinctUntilChanged()
    }
    combine(status, refresh) { counts, _ -> counts }
        .onEach { counts ->
            if (counts == null) {
                cancelNotification(Notifications.ID_TRANSFER_STATUS)
            } else {
                val title = stringResource(MR.strings.transfer_status_queue_count, counts.first, counts.second)
                notify(
                    Notifications.ID_TRANSFER_STATUS,
                    transferStatusNotification(Notifications.CHANNEL_TRANSFER_STATUS, title),
                )
            }
        }
        .launchIn(scope)
}

internal fun Context.transferStatusNotification(
    channel: String,
    title: String,
): Notification {
    val openQueue = NotificationHandler.openDownloadManagerPendingActivity(this)
    return notificationBuilder(channel) {
        setSmallIcon(R.drawable.ic_mihon)
        setContentTitle(title)
        setContentIntent(openQueue)
        setCategory(NotificationCompat.CATEGORY_STATUS)
        setShowWhen(false)
        setOnlyAlertOnce(true)
        setOngoing(true)
        setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }.build()
}
