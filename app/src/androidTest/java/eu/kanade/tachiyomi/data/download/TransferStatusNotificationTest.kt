package eu.kanade.tachiyomi.data.download

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.File

@RunWith(AndroidJUnit4::class)
class TransferStatusNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = checkNotNull(context.getSystemService<NotificationManager>())

    @Test
    fun idleCardRemainsPostedInBackgroundUntilDisabled() = runBlocking {
        assumeTrue("Mihon must have notification permission", manager.areNotificationsEnabled())
        val preference = context.appGraph.downloadPreferences.showIdleTransferNotification
        val original = preference.get().takeIf { preference.isSet() }
        preference.set(false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.ready = true }
                awaitCard(visible = false)

                preference.set(true)
                val idleCard = checkNotNull(awaitCard(visible = true))
                assertEquals(Notifications.CHANNEL_TRANSFER_STATUS, idleCard.channelId)
                assertTrue(idleCard.flags and Notification.FLAG_ONGOING_EVENT != 0)
                assertEquals(NotificationHandler.openDownloadManagerPendingActivity(context), idleCard.contentIntent)
                val title = context.stringResource(MR.strings.transfer_status_queue_count, 0, 0)
                verifyTransferCounts(idleCard, title)
                captureTransferNotificationShadeIfRequested("transfer-status-idle.png", title)
            }

            withTimeout(15_000) {
                ProcessLifecycleOwner.get().lifecycle.currentStateFlow.first {
                    !it.isAtLeast(Lifecycle.State.STARTED)
                }
            }
            assertNotNull(findCard())
            preference.set(false)
            awaitCard(visible = false)
        } finally {
            if (original == null) preference.delete() else preference.set(original)
        }
        Unit
    }

    private fun findCard(): Notification? = manager.activeNotifications.firstOrNull {
        it.id == Notifications.ID_TRANSFER_STATUS
    }?.notification

    private suspend fun awaitCard(visible: Boolean): Notification? = withTimeout(15_000) {
        var notification = findCard()
        while ((notification != null) != visible) {
            delay(50)
            notification = findCard()
        }
        notification
    }
}

@Suppress("DEPRECATION")
internal fun verifyTransferCounts(notification: Notification, expectedTitle: String) {
    assertEquals(
        expectedTitle,
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
    )
    assertNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
    assertEquals(0, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    assertTrue(notification.actions.isNullOrEmpty())
    assertNull(notification.contentView)
    assertNull(notification.bigContentView)
}

internal suspend fun captureTransferNotificationShadeIfRequested(fileName: String, expectedTitle: String) {
    if (InstrumentationRegistry.getArguments().getString("notificationCapture") != "true") return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val automation = instrumentation.uiAutomation
    fun statusBar(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cmd statusbar $command")).use {
            it.readBytes()
        }
    }
    statusBar("expand-notifications")
    try {
        withTimeout(10_000) {
            while (automation.rootInActiveWindow?.containsText(expectedTitle) != true) {
                delay(50)
            }
        }
        val image = checkNotNull(automation.takeScreenshot())
        try {
            File(context.getExternalFilesDir(null), fileName).outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    } finally {
        statusBar("collapse")
    }
}

private fun AccessibilityNodeInfo.containsText(expected: String): Boolean =
    text?.toString() == expected || (0 until childCount).any { getChild(it)?.containsText(expected) == true }
