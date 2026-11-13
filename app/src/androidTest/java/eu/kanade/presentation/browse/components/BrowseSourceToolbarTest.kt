package eu.kanade.presentation.browse.components

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR

@RunWith(AndroidJUnit4::class)
class BrowseSourceToolbarTest {
    @Test
    fun onlineSourceCanEnterAndExitSelectionFromToolbar() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val select = context.stringResource(MR.strings.action_select)
        val selectAll = context.stringResource(MR.strings.action_select_all)
        val selectInverse = context.stringResource(MR.strings.action_select_inverse)
        val cancel = context.stringResource(MR.strings.action_cancel)
        val source = object : Source {
            override val id = 1L
            override val name = "Online source"
        }

        fun findAction(label: String) = instrumentation.uiAutomation.rootInActiveWindow?.findAction(label)

        suspend fun awaitAction(label: String): AccessibilityNodeInfo = withTimeoutOrNull(10_000) {
            var action = findAction(label)
            while (action == null) {
                delay(50)
                action = findAction(label)
            }
            action
        } ?: throw AssertionError("Toolbar action '$label' did not appear")

        suspend fun clickAction(label: String) {
            val button = generateSequence(awaitAction(label)) { it.parent }.firstOrNull { it.isClickable }
            assertTrue(
                "Toolbar action '$label' could not be clicked",
                button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true,
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.ready = true
                activity.setComposeContent {
                    var selectionMode by remember { mutableStateOf(false) }
                    BrowseSourceToolbar(
                        searchQuery = null,
                        onSearchQueryChange = {},
                        source = source,
                        displayMode = LibraryDisplayMode.CompactGrid,
                        onDisplayModeChange = {},
                        navigateUp = {},
                        onWebViewClick = {},
                        onHelpClick = {},
                        onSettingsClick = {},
                        onSearch = {},
                        selectionMode = selectionMode,
                        selectionCount = 0,
                        onEnterSelectionMode = { selectionMode = true },
                        onCancelSelectionMode = { selectionMode = false },
                        onSelectAll = {},
                        onInvertSelection = {},
                    )
                }
            }

            clickAction(select)
            awaitAction(selectAll)
            awaitAction(selectInverse)
            clickAction(cancel)
            awaitAction(select)
            assertNull(findAction(selectAll))
        }
    }

    private fun AccessibilityNodeInfo.findAction(label: String): AccessibilityNodeInfo? =
        if (isVisibleToUser && contentDescription?.toString() == label) {
            this
        } else {
            (0 until childCount).firstNotNullOfOrNull { getChild(it)?.findAction(label) }
        }
}
