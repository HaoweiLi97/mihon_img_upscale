package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.preferenceKey
import eu.kanade.tachiyomi.source.sourcePreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.download.service.CloudSyncStorage
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.source.service.SourceManager

@Inject
class PreferenceBackupCreator(
    private val sourceManager: SourceManager,
    private val preferenceStore: PreferenceStore,
    private val downloadPreferences: DownloadPreferences,
) {

    fun createApp(includePrivatePreferences: Boolean, includeCloudStorageLogin: Boolean): List<BackupPreference> {
        val preferences = preferenceStore.getAll()
        val fingerprintPreference = downloadPreferences.cloudSyncOneDriveAccountFingerprint()
        val driveId = preferences[downloadPreferences.cloudSyncOneDriveId().key()] as? String
        val fingerprint = driveId?.let { downloadPreferences.cloudSyncTargetId(CloudSyncStorage.OneDrive, it, "/", "") }
        val appPreferences = if (fingerprint ==
            null
        ) {
            preferences
        } else {
            preferences + (fingerprintPreference.key() to fingerprint)
        }
        return appPreferences
            .filter { (key, value) ->
                includeCloudStorageLogin || when (key) {
                    in DownloadPreferences.cloudStorageLoginPreferenceKeys -> false
                    DownloadPreferences.CLOUD_SYNC_URL_PREF_KEY -> {
                        val url = (value as? String)?.toHttpUrlOrNull()
                        url != null && url.username.isEmpty() && url.password.isEmpty() && url.query == null &&
                            url.fragment == null
                    }
                    else -> true
                }
            }
            .toBackupPreferences()
            .withPrivatePreferences(includePrivatePreferences)
    }

    suspend fun createSource(includePrivatePreferences: Boolean): List<BackupSourcePreferences> {
        return sourceManager.getAll()
            .filterIsInstance<ConfigurableSource>()
            .map {
                BackupSourcePreferences(
                    it.preferenceKey(),
                    it.sourcePreferences().all.toBackupPreferences()
                        .withPrivatePreferences(includePrivatePreferences),
                )
            }
            .filter { it.prefs.isNotEmpty() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, *>.toBackupPreferences(): List<BackupPreference> {
        return this
            .filterKeys {
                !Preference.isAppState(it) && it !in DownloadPreferences.cloudSyncRecordPreferenceKeys
            }
            .mapNotNull { (key, value) ->
                when (value) {
                    is Int -> BackupPreference(key, IntPreferenceValue(value))
                    is Long -> BackupPreference(key, LongPreferenceValue(value))
                    is Float -> BackupPreference(key, FloatPreferenceValue(value))
                    is String -> BackupPreference(key, StringPreferenceValue(value))
                    is Boolean -> BackupPreference(key, BooleanPreferenceValue(value))
                    is Set<*> -> (value as? Set<String>)?.let {
                        BackupPreference(key, StringSetPreferenceValue(it))
                    }
                    else -> null
                }
            }
    }

    private fun List<BackupPreference>.withPrivatePreferences(include: Boolean) =
        if (include) {
            this
        } else {
            this.filterNot { Preference.isPrivate(it.key) }
        }
}
