package tachiyomi.source.local

import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalBrowserScreenTest {
    @Test
    fun openingLocalSourceRendersTheGalleryWithoutCatalogueSourceCast() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preference = context.appGraph.storagePreferences.localComicDirectory
        val previousRoot = preference.get()
        val directory = File(context.cacheDir, "local-gallery-screen-${System.nanoTime()}").apply { mkdirs() }
        try {
            preference.set(Uri.fromFile(directory).toString())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.ready = true
                    activity.setComposeContent {
                        // Exercise the same entry point used by the sources list, including its toolbar.
                        Navigator(BrowseSourceScreen(LocalSource.ID, null))
                    }
                }
                val emptyMessage = context.stringResource(MR.strings.local_browser_empty)
                withTimeout(10_000) {
                    while (instrumentation.uiAutomation.rootInActiveWindow
                            ?.containsText(emptyMessage) != true
                    ) {
                        delay(100)
                    }
                }
                instrumentation.waitForIdleSync()
            }
        } finally {
            preference.set(previousRoot)
            directory.delete()
        }
    }

    private fun AccessibilityNodeInfo.containsText(expected: String): Boolean =
        text?.toString() == expected || (0 until childCount).any { getChild(it)?.containsText(expected) == true }
}
