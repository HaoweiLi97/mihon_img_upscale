package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.download.CloudCoverStatus
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Cloud
import mihon.icons.materialsymbols.rounded.CloudOff
import mihon.icons.materialsymbols.rounded.Sync
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

internal val LocalMangaCoverSyncStatuses = compositionLocalOf<Map<Long, CloudCoverStatus>> { emptyMap() }

@Composable
internal fun MangaCoverSyncBadge(status: CloudCoverStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (icon, label, tint) = when (status) {
        CloudCoverStatus.Unsynced -> Triple(
            MaterialSymbols.Rounded.CloudOff,
            MR.strings.remote_sync_cover_unsynced,
            colors.onSurfaceVariant,
        )
        CloudCoverStatus.CloudOnly -> Triple(
            MaterialSymbols.Rounded.Cloud,
            MR.strings.remote_sync_cover_cloud_only,
            colors.primary,
        )
        CloudCoverStatus.LocalCopy -> Triple(
            MaterialSymbols.RoundedFilled.CheckCircle,
            MR.strings.remote_sync_cover_local_copy,
            colors.tertiary,
        )
        CloudCoverStatus.Syncing -> Triple(
            MaterialSymbols.Rounded.Sync,
            MR.strings.cloud_sync_status_syncing,
            colors.primary,
        )
    }
    Surface(modifier = modifier, shape = CircleShape, color = colors.surface) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(label),
            tint = tint,
            modifier = Modifier.padding(3.dp).size(15.dp),
        )
    }
}
