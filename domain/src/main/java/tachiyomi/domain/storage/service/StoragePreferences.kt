package tachiyomi.domain.storage.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.FolderProvider

@Inject
@SingleIn(AppScope::class)
class StoragePreferences(
    folderProvider: FolderProvider,
    private val preferenceStore: PreferenceStore,
) {

    val baseStorageDirectory: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("storage_dir"),
        folderProvider.path(),
    )

    // Empty uses the existing Mihon/local directory. This does not move downloads or backups.
    val localComicDirectory: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("local_comic_directory"),
        "",
    )

    val localBrowserThumbnails = preferenceStore.getBoolean("local_browser_thumbnails", true)
    val localBrowserColumns = preferenceStore.getInt("local_browser_columns", 0)
    val localBrowserSort = preferenceStore.getInt("local_browser_sort", 0)
}
