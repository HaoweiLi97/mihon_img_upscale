package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.download.service.CloudSyncStorage
import tachiyomi.domain.download.service.DownloadPreferences
import kotlin.time.Duration.Companion.minutes

@Inject
@SingleIn(AppScope::class)
class CloudSyncService(
    private val networkHelper: NetworkHelper,
    private val oneDriveAuth: OneDriveAuth,
) {
    private val webDavDirectoryLock = Mutex()

    private val uploadClient by lazy {
        networkHelper.client.newBuilder()
            .readTimeout(15.minutes)
            .writeTimeout(15.minutes)
            .callTimeout(15.minutes)
            .retryOnConnectionFailure(false)
            .build()
    }

    internal fun storage(config: CloudStorageConfig): CloudStorage = when (config) {
        is WebDavConfig -> WebDavStorage(config, networkHelper.client, uploadClient, webDavDirectoryLock)
        is OneDriveConfig -> OneDriveStorage(config.driveId) { rejected ->
            oneDriveAuth.accessToken(config.driveId, rejected)
        }
    }
}

internal interface CloudStorage {
    suspend fun listDirectories(path: String): List<CloudSyncDirectory>
    /** Returns true if bytes were uploaded, or false if an existing file was kept. */
    suspend fun uploadFile(
        remoteDirectory: String,
        file: UniFile,
        fileName: String,
        overwrite: Boolean,
        onProgress: (Int) -> Unit,
    ): Boolean
    suspend fun downloadCbz(remoteDirectory: String, fileName: String, file: UniFile): Boolean
    suspend fun remoteFileSize(remoteDirectory: String, fileName: String): Long?

    /** Returns null for a missing file. A failed lookup or an unknown size throws. */
    suspend fun lookupRemoteFileSize(remoteDirectory: String, fileName: String): Long?

    /** A null listing or size requires a fallback lookup. An absent key means no matching file was listed. */
    suspend fun listRemoteFileSizes(remoteDirectory: String, fileNames: Set<String>): Map<String, Long?>?
    suspend fun deleteMangaCollection(directory: String)
}

internal sealed interface CloudStorageConfig

internal data class WebDavConfig(
    val url: String,
    val username: String,
    val password: String,
) : CloudStorageConfig {
    val isValid: Boolean
        get() = url.toHttpUrlOrNull() != null && username.isNotBlank() && password.isNotBlank()
}

internal data class OneDriveConfig(val driveId: String) : CloudStorageConfig

internal data class CloudSyncTarget(
    val config: CloudStorageConfig,
    val destination: String,
    val id: String,
)

internal fun DownloadPreferences.cloudStorageConfig(storage: CloudSyncStorage): CloudStorageConfig? = when (storage) {
    CloudSyncStorage.WebDav -> if (cloudSyncEnabled().get()) {
        WebDavConfig(cloudSyncUrl().get(), cloudSyncUsername().get(), cloudSyncPassword().get()).takeIf { it.isValid }
    } else {
        null
    }
    CloudSyncStorage.OneDrive -> cloudSyncOneDriveId().get().takeIf { it.isNotBlank() }?.let(::OneDriveConfig)
}

internal fun DownloadPreferences.activeCloudSyncTarget(): CloudSyncTarget? {
    if (!saveChaptersAsCBZ.get()) return null
    val storage = cloudSyncStorage().get()
    val config = cloudStorageConfig(storage) ?: return null
    val destination = cloudSyncDestination(storage).get()
    val id = when (config) {
        is WebDavConfig -> cloudSyncTargetId(storage, config.username, destination, config.url)
        is OneDriveConfig -> cloudSyncTargetId(storage, config.driveId, destination)
    } ?: return null
    return CloudSyncTarget(config, destination, id)
}

internal fun DownloadPreferences.cloudSyncSettingsChanges() = merge(
    saveChaptersAsCBZ.changes(),
    cloudSyncStorage().changes(),
    cloudSyncEnabled().changes(),
    cloudSyncUrl().changes(),
    cloudSyncUsername().changes(),
    cloudSyncPassword().changes(),
    cloudSyncOneDriveId().changes(),
    cloudSyncDestination(CloudSyncStorage.WebDav).changes(),
    cloudSyncDestination(CloudSyncStorage.OneDrive).changes(),
).map { Unit }

internal data class CloudSyncDirectory(
    val name: String,
    val path: String,
)

internal class CloudStorageHttpException(
    val status: Int,
    message: String,
    val retryAfterSeconds: Long? = null,
) : IllegalStateException(message)

fun normalizePath(path: String): String {
    val normalized = path
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString(separator = "/", prefix = "/")
    return normalized.takeUnless { it.isBlank() } ?: "/"
}
