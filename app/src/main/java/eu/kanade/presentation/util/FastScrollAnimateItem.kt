package eu.kanade.presentation.util

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier

// https://issuetracker.google.com/352584409
context(scope: LazyItemScope)
fun Modifier.animateItemFastScroll() = with(scope) { animateItem(fadeInSpec = null, fadeOutSpec = null) }
