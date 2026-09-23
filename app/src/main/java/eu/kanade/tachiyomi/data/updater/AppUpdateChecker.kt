package eu.kanade.tachiyomi.data.updater

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease
import uy.kohesive.injekt.injectLazy

class AppUpdateChecker {

    private val getApplicationRelease: GetApplicationRelease by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()

    private val dismissedUpdateVersion: Preference<String> by lazy {
        preferenceStore.getString(Preference.appStateKey("dismissed_app_update_version"), "")
    }

    /**
     * Checks for an application update.
     *
     * Automatic checks suppress only the exact release the user chose to skip. Manual checks
     * deliberately bypass that suppression so the user can still update to it from Settings.
     */
    suspend fun checkForUpdate(
        context: Context,
        forceCheck: Boolean = false,
        includeDismissedUpdate: Boolean = false,
    ): GetApplicationRelease.Result {
        // Disable app update checks for older Android versions that we're going to drop support for
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        //     return GetApplicationRelease.Result.OsTooOld
        // }

        return withIOContext {
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFossBuildType,
                    isPreviewBuildType,
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                    forceCheck,
                ),
            )

            if (
                result is GetApplicationRelease.Result.NewUpdate &&
                !includeDismissedUpdate &&
                result.release.version == dismissedUpdateVersion.get()
            ) {
                return@withIOContext GetApplicationRelease.Result.NoNewUpdate
            }

            when (result) {
                is GetApplicationRelease.Result.NewUpdate -> AppUpdateNotifier(context).promptUpdate(result.release)
                else -> {}
            }

            result
        }
    }

    fun dismissUpdate(context: Context, version: String) {
        dismissedUpdateVersion.set(version)
        AppUpdateNotifier(context).cancel()
    }
}

const val GITHUB_REPO = "HaoweiLi97/mihon_img_upscale"

val RELEASE_TAG: String by lazy {
    if (isPreviewBuildType) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }
}

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
