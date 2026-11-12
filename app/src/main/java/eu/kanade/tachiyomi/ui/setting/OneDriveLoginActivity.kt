package eu.kanade.tachiyomi.ui.setting

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen

class OneDriveLoginActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = intent.data ?: run {
            finish()
            return
        }
        setComposeContent { LoadingScreen() }
        lifecycleScope.launch {
            val connected = try {
                appGraph.oneDriveAuth.completeLogin(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logcat(LogPriority.WARN) { "OneDrive sign-in could not complete (${error.javaClass.simpleName})" }
                false
            }
            val message = if (connected) {
                MR.strings.cloud_sync_connection_success
            } else {
                MR.strings.cloud_sync_onedrive_login_failed
            }
            toast(message)
            finish()
            if (connected) {
                startActivity(
                    Intent(this@OneDriveLoginActivity, MainActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        ),
                )
            }
        }
    }
}
