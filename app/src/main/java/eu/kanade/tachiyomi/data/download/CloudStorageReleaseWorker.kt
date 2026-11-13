package eu.kanade.tachiyomi.data.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import eu.kanade.presentation.components.message
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.coroutines.coroutineContext

class CloudStorageReleaseWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = releaseLock.withLock {
        val targetId = inputData.getString(TARGET) ?: return@withLock Result.failure()
        val graph = applicationContext.appGraph
        setForegroundSafely()

        try {
            val mangaId = inputData.getLong(MANGA_ID, -1L)
            val result = if (mangaId >= 0) {
                val manga = graph.getManga.await(mangaId)
                coroutineContext.ensureActive()
                if (manga == null) return@withLock complete(null)
                graph.downloadManager.releaseCloudStorage(manga, targetId)
            } else {
                graph.libraryCloudSync.releaseCategoryStorage(inputData.getLong(CATEGORY_ID, 0), targetId)
            }
            complete(result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Could not release cloud-backed local storage" }
            complete(null)
        }
    }

    private fun complete(result: CloudStorageReleaseResult?): Result {
        val context = applicationContext
        if (!context.appGraph.securityPreferences.hideNotificationContent.get()) {
            try {
                val title = context.stringResource(MR.strings.cloud_sync_release_storage)
                val summary = result?.message(context)
                    ?: context.stringResource(MR.strings.cloud_sync_release_storage_failed)
                val openApp = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                context.notify(completionNotificationId(id), Notifications.CHANNEL_COMMON) {
                    setSmallIcon(R.drawable.ic_mihon)
                    setContentTitle(title)
                    setContentText(summary)
                    setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                    setContentIntent(openApp)
                    setAutoCancel(true)
                }
            } catch (error: RuntimeException) {
                logcat(LogPriority.WARN, error) { "Could not post the storage release result notification" }
            }
        }
        return if (result == null) Result.failure() else Result.success(result.toWorkData())
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(applicationContext.stringResource(MR.strings.cloud_sync_release_storage))
            setProgress(0, 0, true)
            setOnlyAlertOnce(true)
            setOngoing(true)
        }.build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "cloud-storage-release"
        private const val TARGET = "target"
        private const val MANGA_ID = "manga"
        private const val CATEGORY_ID = "category"
        private const val PROGRESS_NOTIFICATION_ID = -205
        private val releaseLock = Mutex()

        internal fun enqueue(
            context: Context,
            mangaId: Long? = null,
            categoryId: Long? = null,
            targetId: String? = context.appGraph.downloadPreferences.activeCloudSyncTarget()?.id,
        ) {
            val name = cloudStorageReleaseWorkName(mangaId, categoryId)
            val target = targetId ?: return
            val input = Data.Builder().putString(TARGET, target)
            if (mangaId != null) {
                input.putLong(MANGA_ID, mangaId)
            } else {
                input.putLong(CATEGORY_ID, checkNotNull(categoryId))
            }
            val request = OneTimeWorkRequestBuilder<CloudStorageReleaseWorker>()
                .setInputData(input.build())
                .keepResultsForAtLeast(7, TimeUnit.DAYS)
                .addTag(TAG)
                .addTag(name)
                .build()
            context.workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        }

        internal fun activeCategories(context: Context): Flow<Set<Long>> = workInfos(context)
            .map { jobs ->
                jobs.asSequence().filterNot { it.state.isFinished }
                    .flatMap { it.tags.asSequence() }
                    .mapNotNull { tag -> tag.substringAfter(CATEGORY_WORK_PREFIX, "").toLongOrNull() }
                    .toSet()
            }
            .distinctUntilChanged()

        internal fun workInfos(context: Context): Flow<List<WorkInfo>> = context.workManager.getWorkInfosByTagFlow(TAG)

        internal fun unacknowledged(context: Context, jobs: List<WorkInfo>): List<WorkInfo> {
            val preferences = context.releaseResultPreferences
            val acknowledged = preferences.getStringSet("acknowledged", emptySet()).orEmpty()
            return jobs.filter {
                (it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED) &&
                    it.id.toString() !in acknowledged
            }
        }

        internal fun acknowledge(context: Context, id: UUID, jobs: List<WorkInfo>) {
            val preferences = context.releaseResultPreferences
            val acknowledged = preferences.getStringSet("acknowledged", emptySet()).orEmpty()
            val availableIds = jobs.mapTo(HashSet()) { it.id.toString() }
            preferences.edit().putStringSet("acknowledged", acknowledged.intersect(availableIds) + id.toString()).apply()
            context.cancelNotification(completionNotificationId(id))
        }

        private fun completionNotificationId(id: UUID): Int = id.hashCode() and Int.MAX_VALUE
    }
}

private val Context.releaseResultPreferences
    get() = getSharedPreferences("cloud_storage_release_results", Context.MODE_PRIVATE)

private const val CATEGORY_WORK_PREFIX = "cloud-storage-release-category:"
private const val MANGA_WORK_PREFIX = "cloud-storage-release-manga:"

internal fun cloudStorageReleaseWorkName(mangaId: Long? = null, categoryId: Long? = null): String = when {
    mangaId != null && categoryId == null -> "$MANGA_WORK_PREFIX$mangaId"
    mangaId == null && categoryId != null -> "$CATEGORY_WORK_PREFIX$categoryId"
    else -> error("A storage release requires exactly one manga or category")
}

internal fun CloudStorageReleaseResult.toWorkData(): Data = Data.Builder()
    .putInt("released", released)
    .putInt("kept", kept)
    .apply { reasons.forEach { (reason, count) -> putInt(reason.name, count) } }
    .build()

internal fun Data.toCloudStorageReleaseResult(): CloudStorageReleaseResult = CloudStorageReleaseResult(
    released = getInt("released", 0),
    kept = getInt("kept", 0),
    reasons = CloudStorageReleaseReason.entries.mapNotNull { reason ->
        getInt(reason.name, 0).takeIf { it > 0 }?.let { reason to it }
    }.toMap(),
)
