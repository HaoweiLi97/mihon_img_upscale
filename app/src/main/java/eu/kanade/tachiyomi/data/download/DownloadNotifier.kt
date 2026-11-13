package eu.kanade.tachiyomi.data.download

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import mihon.icons.materialsymbols.R as MaterialSymbolsR

/**
 * DownloadNotifier is used to show notifications when downloading one or multiple chapters.
 *
 * @param context context of application
 */
@Inject
class DownloadNotifier(
    private val context: Context,
    private val preferences: SecurityPreferences,
) {

    /**
     * Dismiss the downloader's notification. Downloader error notifications use a different id, so
     * those can only be dismissed by the user.
     */
    @Synchronized
    fun dismissProgress() {
        context.cancelNotification(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS)
        context.cancelNotification(Notifications.ID_DOWNLOAD_CHAPTER_PAUSED)
    }

    /**
     * Show notification when download is paused.
     */
    @Synchronized
    fun onPaused() {
        dismissProgress()
        context.notify(
            Notifications.ID_DOWNLOAD_CHAPTER_PAUSED,
            Notifications.CHANNEL_DOWNLOADER_PROGRESS,
        ) {
            setContentTitle(context.stringResource(MR.strings.chapter_paused))
            setContentText(context.stringResource(MR.strings.download_notifier_download_paused))
            setSmallIcon(MaterialSymbolsR.drawable.rounded_filled_pause)
            setOnlyAlertOnce(true)
            // Open download manager when clicked
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            // Resume action
            addAction(
                MaterialSymbolsR.drawable.rounded_filled_play_arrow,
                context.stringResource(MR.strings.action_resume),
                NotificationReceiver.resumeDownloadsPendingBroadcast(context),
            )
            // Clear action
            addAction(
                MaterialSymbolsR.drawable.rounded_close,
                context.stringResource(MR.strings.action_cancel_all),
                NotificationReceiver.clearDownloadsPendingBroadcast(context),
            )
        }
    }

    /**
     * Resets the state once downloads are completed.
     */
    fun onComplete() = dismissProgress()

    /**
     * Called when the downloader receives a warning.
     *
     * @param reason the text to show.
     * @param timeout duration after which to automatically dismiss the notification.
     * @param mangaId the id of the entry being warned about
     * Only works on Android 8+.
     */
    fun onWarning(reason: String, timeout: Long? = null, contentIntent: PendingIntent? = null, mangaId: Long? = null) {
        context.notify(
            Notifications.ID_DOWNLOAD_CHAPTER_ERROR,
            Notifications.CHANNEL_DOWNLOADER_ERROR,
        ) {
            setContentTitle(context.stringResource(MR.strings.download_notifier_downloader_title))
            setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            setSmallIcon(MaterialSymbolsR.drawable.rounded_filled_warning)
            setAutoCancel(true)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            if (mangaId != null) {
                addAction(
                    MaterialSymbolsR.drawable.rounded_book,
                    context.stringResource(MR.strings.action_show_manga),
                    NotificationReceiver.openEntryPendingActivity(context, mangaId),
                )
            }
            timeout?.let { setTimeoutAfter(it) }
            contentIntent?.let { setContentIntent(it) }
        }
    }

    /**
     * Called when the downloader receives an error. It's shown as a separate notification to avoid
     * being overwritten.
     *
     * @param error string containing error information.
     * @param chapter string containing chapter title.
     * @param mangaId the id of the entry that the error occurred on
     */
    fun onError(error: String? = null, chapter: String? = null, mangaTitle: String? = null, mangaId: Long? = null) {
        // Create notification
        context.notify(
            Notifications.ID_DOWNLOAD_CHAPTER_ERROR,
            Notifications.CHANNEL_DOWNLOADER_ERROR,
        ) {
            setContentTitle(
                if (mangaTitle != null && !preferences.hideNotificationContent.get()) {
                    "$mangaTitle: $chapter"
                } else {
                    context.stringResource(MR.strings.download_notifier_downloader_title)
                },
            )
            setContentText(error ?: context.stringResource(MR.strings.download_notifier_unknown_error))
            setSmallIcon(MaterialSymbolsR.drawable.rounded_filled_warning)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            if (mangaId != null) {
                addAction(
                    MaterialSymbolsR.drawable.rounded_book,
                    context.stringResource(MR.strings.action_show_manga),
                    NotificationReceiver.openEntryPendingActivity(context, mangaId),
                )
            }
        }
    }
}
