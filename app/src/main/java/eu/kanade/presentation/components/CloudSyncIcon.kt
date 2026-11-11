package eu.kanade.presentation.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.download.CloudStorageReleaseResult
import eu.kanade.tachiyomi.data.download.CloudSyncStatus
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Cloud
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.rounded.Sync
import mihon.icons.materialsymbols.roundedfilled.Pause
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

internal val CloudSyncStatus.label
    get() = when (this) {
        CloudSyncStatus.Syncing -> MR.strings.cloud_sync_status_syncing
        CloudSyncStatus.Waiting -> MR.strings.remote_sync_waiting
        CloudSyncStatus.Paused -> MR.strings.cloud_sync_status_paused
        CloudSyncStatus.Complete -> MR.strings.cloud_sync_status_complete
        CloudSyncStatus.Error -> MR.strings.cloud_sync_status_error
    }

internal fun CloudStorageReleaseResult.message(context: Context): String = when {
    kept > 0 -> context.stringResource(MR.strings.cloud_sync_release_storage_partial, released, kept)
    released > 0 -> context.stringResource(MR.strings.cloud_sync_release_storage_result, released)
    else -> context.stringResource(MR.strings.cloud_sync_release_storage_empty)
}

@Composable
internal fun CloudSyncIcon(status: CloudSyncStatus?, description: String? = null) {
    val colors = MaterialTheme.colorScheme
    val (badgeBackground, badgeTint) = when (status) {
        CloudSyncStatus.Syncing -> colors.primary to colors.onPrimary
        CloudSyncStatus.Paused, CloudSyncStatus.Waiting, null -> colors.secondary to colors.onSecondary
        CloudSyncStatus.Complete -> colors.tertiary to colors.onTertiary
        CloudSyncStatus.Error -> colors.error to colors.onError
    }
    Box(
        Modifier.size(width = 25.dp, height = 24.dp).semantics {
            if (description != null) contentDescription = description
        },
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Cloud,
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopStart).size(22.dp),
            tint = colors.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(14.dp)
                .background(badgeBackground, CircleShape)
                .border(1.dp, colors.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (status) {
                    null -> MaterialSymbols.Rounded.ArrowUpward
                    CloudSyncStatus.Syncing -> MaterialSymbols.Rounded.Sync
                    CloudSyncStatus.Waiting -> MaterialSymbols.Rounded.Schedule
                    CloudSyncStatus.Paused -> MaterialSymbols.RoundedFilled.Pause
                    CloudSyncStatus.Complete -> MaterialSymbols.Rounded.Check
                    CloudSyncStatus.Error -> MaterialSymbols.Rounded.Error
                },
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = badgeTint,
            )
        }
    }
}
