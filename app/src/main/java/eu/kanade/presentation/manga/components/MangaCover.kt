package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.rememberResourceBitmapPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.CloudCoverStatus

enum class MangaCover(val ratio: Float) {
    Square(1f / 1f),
    Book(2f / 3f),
    ;

    @Composable
    operator fun invoke(
        data: Any?,
        modifier: Modifier = Modifier,
        contentDescription: String = "",
        shape: Shape = MaterialTheme.shapes.extraSmall,
        syncStatus: CloudCoverStatus? = null,
        onClick: (() -> Unit)? = null,
    ) {
        val coverModifier = modifier.aspectRatio(ratio).clip(shape).then(
            if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
        )
        val image: @Composable (Modifier) -> Unit = { imageModifier ->
            AsyncImage(
                model = data,
                placeholder = ColorPainter(CoverPlaceholderColor),
                error = rememberResourceBitmapPainter(id = R.drawable.cover_error),
                contentDescription = contentDescription,
                modifier = imageModifier,
                contentScale = ContentScale.Crop,
            )
        }
        if (syncStatus == null) {
            image(coverModifier)
        } else {
            Box(coverModifier) {
                image(Modifier.matchParentSize())
                MangaCoverSyncBadge(syncStatus, Modifier.align(Alignment.TopEnd).padding(3.dp))
            }
        }
    }
}

private val CoverPlaceholderColor = Color(0x1F888888)
