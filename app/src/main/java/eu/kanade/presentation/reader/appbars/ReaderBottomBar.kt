package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderBottomBar(
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    onClickPageLayout: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    imageEnhancementEnabled: Boolean,
    onClickImageEnhancement: () -> Unit,
    spatialSceneActive: Boolean,
    spatialSceneBusy: Boolean,
    onClickSpatialScene: () -> Unit,
    onClickSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconTint = MaterialTheme.colorScheme.onSurface
    val inactiveTint = iconTint.copy(alpha = 0.6f)

    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClickReadingMode) {
            Icon(
                painter = painterResource(readingMode.iconRes),
                contentDescription = stringResource(MR.strings.viewer),
            )
        }

        IconButton(onClick = onClickPageLayout) {
            Icon(
                painter = painterResource(R.drawable.ic_page_24dp),
                contentDescription = stringResource(MR.strings.pref_page_layout),
            )
        }

        IconButton(onClick = onClickOrientation) {
            Icon(
                imageVector = orientation.icon,
                contentDescription = stringResource(MR.strings.rotation_type),
            )
        }

        IconButton(onClick = onClickCropBorder) {
            Icon(
                painter = painterResource(if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp),
                contentDescription = stringResource(MR.strings.pref_crop_borders),
            )
        }

        IconButton(onClick = onClickImageEnhancement) {
            Icon(
                painter = painterResource(R.drawable.ic_photo_24dp),
                contentDescription = stringResource(MR.strings.reader_image_enhancement),
                tint = if (imageEnhancementEnabled) iconTint else inactiveTint,
            )
        }

        IconButton(onClick = onClickSpatialScene, enabled = !spatialSceneBusy) {
            Icon(
                painter = painterResource(R.drawable.ic_view_in_ar_24dp),
                contentDescription = stringResource(MR.strings.reader_spatial_scene),
                tint = if (spatialSceneActive || spatialSceneBusy) iconTint else inactiveTint,
            )
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
    }
}
