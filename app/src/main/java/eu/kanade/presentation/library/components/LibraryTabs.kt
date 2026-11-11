package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.components.CloudSyncIcon
import eu.kanade.presentation.components.label
import eu.kanade.tachiyomi.data.download.CloudSyncStatus
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun LibraryTabs(
    categories: List<Category>,
    pagerState: PagerState,
    getItemCountForCategory: (Category) -> Int?,
    syncStatuses: Map<Long, CloudSyncStatus>,
    onTabItemClick: (Int) -> Unit,
    syncActions: LibrarySyncActions? = null,
) {
    val currentPageIndex = pagerState.currentPage.coerceAtMost(categories.lastIndex)
    PrimaryScrollableTabRow(
        selectedTabIndex = currentPageIndex,
        edgePadding = 0.dp,
    ) {
        categories.forEachIndexed { index, category ->
            val status = syncStatuses[category.id]
            val statusLabel = status?.let { stringResource(it.label) }
            val manageLabel = stringResource(MR.strings.cloud_sync_manage)
            val actions = syncActions?.takeIf { it.enabled || status != null }
            val haptics = LocalHapticFeedback.current
            var menuExpanded by remember(category.id) { mutableStateOf(false) }
            val menuColor = MaterialTheme.colorScheme.surfaceContainer
            Box(propagateMinConstraints = true) {
                Tab(
                    selected = currentPageIndex == index,
                    onClick = { onTabItemClick(index) },
                    modifier = Modifier
                        .then(if (menuExpanded) Modifier.background(menuColor) else Modifier)
                        .semantics {
                            if (statusLabel != null) stateDescription = statusLabel
                            if (actions != null) {
                                onLongClick(label = manageLabel) {
                                    menuExpanded = true
                                    true
                                }
                            }
                        }
                        .then(
                            if (actions == null) {
                                Modifier
                            } else {
                                Modifier.pointerInput(category.id) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (awaitLongPressOrCancellation(down.id) != null) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuExpanded = true
                                            // Keep the tab's ordinary tap handling outside long presses.
                                            do {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                event.changes.forEach { it.consume() }
                                            } while (event.changes.any { it.pressed })
                                        }
                                    }
                                }
                            },
                        ),
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TabText(
                                text = category.visualName,
                                badgeCount = getItemCountForCategory(category),
                            )
                            if (status != null) CloudSyncIcon(status)
                        }
                    },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                )
                if (actions != null) {
                    LibrarySyncMenu(
                        categoryId = category.id,
                        showPauseAction = status != null,
                        actions = actions,
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySyncMenu(
    categoryId: Long,
    showPauseAction: Boolean,
    actions: LibrarySyncActions,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    val paused = categoryId in actions.pausedCategories
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 192.dp),
        shape = RoundedCornerShape(8.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(MR.strings.cloud_sync_release_storage)) },
            modifier = Modifier.heightIn(min = 56.dp),
            enabled = actions.enabled && !actions.releasingStorage,
            onClick = {
                onDismiss()
                actions.onReleaseStorage(categoryId)
            },
        )
        if (showPauseAction) {
            val label = if (paused) MR.strings.cloud_sync_resume_category else MR.strings.cloud_sync_pause_category
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                modifier = Modifier.heightIn(min = 56.dp),
                enabled = actions.enabled,
                onClick = {
                    onDismiss()
                    actions.onPauseChanged(categoryId, !paused)
                },
            )
        }
    }
}

internal data class LibrarySyncActions(
    val pausedCategories: Set<Long>,
    val enabled: Boolean,
    val releasingStorage: Boolean,
    val onPauseChanged: (Long, Boolean) -> Unit,
    val onReleaseStorage: (Long) -> Unit,
)
