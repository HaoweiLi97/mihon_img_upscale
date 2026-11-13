package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.util.Log
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupCreateWorker
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.source.sourcePreferences
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.plusAssign
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.CloudSyncStorage
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class PreferenceRestorer(
    private val context: Context,
    private val getCategories: GetCategories,
    private val preferenceStore: PreferenceStore,
    private val downloadPreferences: DownloadPreferences,
) {
    suspend fun restoreApp(
        preferences: List<BackupPreference>,
        backupCategories: List<BackupCategory>?,
        restoringOneDriveLogin: Boolean,
    ) {
        val preferencesByKey = preferences.associateBy { it.key }
        val oneDriveId = downloadPreferences.cloudSyncOneDriveId().get()
        val fingerprintKey = downloadPreferences.cloudSyncOneDriveAccountFingerprint().key()
        val backupFingerprint = (preferencesByKey[fingerprintKey]?.value as? StringPreferenceValue)?.value
        val currentFingerprint = downloadPreferences.cloudSyncTargetId(CloudSyncStorage.OneDrive, oneDriveId, "/", "")
        val keepOneDriveTarget = !restoringOneDriveLogin && currentFingerprint != null &&
            backupFingerprint != currentFingerprint
        val webDavUrl = downloadPreferences.cloudSyncUrl()
        val webDavUsername = downloadPreferences.cloudSyncUsername()
        val webDavPassword = downloadPreferences.cloudSyncPassword()
        val webDavEnabled = downloadPreferences.cloudSyncEnabled()
        val backupUrl = (preferencesByKey[webDavUrl.key()]?.value as? StringPreferenceValue)?.value
        val backupUsername = (preferencesByKey[webDavUsername.key()]?.value as? StringPreferenceValue)?.value
        val backupPassword = (preferencesByKey[webDavPassword.key()]?.value as? StringPreferenceValue)?.value
        val hasWebDavLogin = !backupUrl.isNullOrBlank() && !backupUsername.isNullOrBlank() &&
            !backupPassword.isNullOrBlank()
        val keepWebDavTarget = !hasWebDavLogin && webDavPassword.get().isNotBlank() &&
            (
                backupUrl?.trimEnd('/') != webDavUrl.get().trimEnd('/') ||
                    backupUsername != webDavUsername.get()
                )
        val excludedKeys = buildSet {
            if (keepOneDriveTarget || restoringOneDriveLogin) {
                add(fingerprintKey)
                add(downloadPreferences.cloudSyncDestination(CloudSyncStorage.OneDrive).key())
            }
            if (keepWebDavTarget) {
                add(webDavUrl.key())
                addAll(DownloadPreferences.cloudStorageLoginPreferenceKeys)
                add(downloadPreferences.cloudSyncDestination(CloudSyncStorage.WebDav).key())
            }
            add(webDavEnabled.key())
            add(downloadPreferences.cloudSyncOneDriveId().key())
        }
        val backupWebDavEnabled = (preferencesByKey[webDavEnabled.key()]?.value as? BooleanPreferenceValue)?.value
        val wasWebDavEnabled = webDavEnabled.get()
        if (hasWebDavLogin && wasWebDavEnabled) webDavEnabled.set(false)
        restorePreferences(
            preferences.filterNot { it.key in excludedKeys },
            preferenceStore,
            backupCategories,
        )
        if (!keepWebDavTarget && (backupWebDavEnabled != null || hasWebDavLogin)) {
            webDavEnabled.set(backupWebDavEnabled ?: wasWebDavEnabled)
        }

        LibraryUpdateWorker.setupTask(context)
        BackupCreateWorker.setupTask(context)
    }

    suspend fun restoreSource(preferences: List<BackupSourcePreferences>) {
        preferences.forEach {
            val sourcePrefs = AndroidPreferenceStore(sourcePreferences(it.sourceKey))
            restorePreferences(it.prefs, sourcePrefs)
        }
    }

    private suspend fun restorePreferences(
        toRestore: List<BackupPreference>,
        preferenceStore: PreferenceStore,
        backupCategories: List<BackupCategory>? = null,
    ) {
        val allCategories = if (backupCategories != null) getCategories.await() else emptyList()
        val categoriesByName = allCategories.associateBy { it.name }
        val backupCategoriesById = backupCategories?.associateBy { it.id.toString() }.orEmpty()
        val prefs = preferenceStore.getAll()
        toRestore.forEach { (key, value) ->
            if (key in DownloadPreferences.cloudSyncRecordPreferenceKeys) return@forEach
            try {
                when (value) {
                    is IntPreferenceValue -> {
                        if (prefs[key] is Int?) {
                            val newValue = if (key == LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY) {
                                backupCategoriesById[value.value.toString()]
                                    ?.let { categoriesByName[it.name]?.id?.toInt() }
                            } else {
                                value.value
                            }

                            newValue?.let { preferenceStore.getInt(key).set(it) }
                        }
                    }
                    is LongPreferenceValue -> {
                        if (prefs[key] is Long?) {
                            preferenceStore.getLong(key).set(value.value)
                        }
                    }
                    is FloatPreferenceValue -> {
                        if (prefs[key] is Float?) {
                            preferenceStore.getFloat(key).set(value.value)
                        }
                    }
                    is StringPreferenceValue -> {
                        if (prefs[key] is String?) {
                            preferenceStore.getString(key).set(value.value)
                        }
                    }
                    is BooleanPreferenceValue -> {
                        if (prefs[key] is Boolean?) {
                            preferenceStore.getBoolean(key).set(value.value)
                        }
                    }
                    is StringSetPreferenceValue -> {
                        if (prefs[key] is Set<*>?) {
                            val restored = restoreCategoriesPreference(
                                key,
                                value.value,
                                preferenceStore,
                                backupCategoriesById,
                                categoriesByName,
                            )
                            if (!restored) preferenceStore.getStringSet(key).set(value.value)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PreferenceRestorer", "Failed to restore preference <$key>", e)
            }
        }
    }

    private fun restoreCategoriesPreference(
        key: String,
        value: Set<String>,
        preferenceStore: PreferenceStore,
        backupCategoriesById: Map<String, BackupCategory>,
        categoriesByName: Map<String, Category>,
    ): Boolean {
        val categoryPreferences = LibraryPreferences.categoryPreferenceKeys + DownloadPreferences.categoryPreferenceKeys
        if (key !in categoryPreferences) return false

        val ids = value.mapNotNull {
            if (it == Category.UNCATEGORIZED_ID.toString() &&
                key in DownloadPreferences.cloudSyncCategoryPreferenceKeys
            ) {
                it
            } else {
                backupCategoriesById[it]?.name?.let { name ->
                    categoriesByName[name]?.id?.toString()
                }
            }
        }

        if (ids.isNotEmpty() || (value.isEmpty() && key in DownloadPreferences.cloudSyncCategoryPreferenceKeys)) {
            preferenceStore.getStringSet(key) += ids
        }
        return true
    }
}
