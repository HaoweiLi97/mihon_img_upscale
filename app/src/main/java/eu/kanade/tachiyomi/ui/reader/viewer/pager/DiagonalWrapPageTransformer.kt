package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

class DiagonalWrapPageTransformer(
    private val viewer: PagerViewer,
) : ViewPager.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        if (page.width == 0 || page.height == 0) return

        page.translationY = 0f
        page.rotation = 0f
        page.rotationY = 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.alpha = 1f
        page.clipBounds = null

        if (position <= -1f || position >= 1f) {
            page.translationX = 0f
            page.alpha = 0f
            return
        }

        val direction = if (viewer is R2LPagerViewer) 1f else -1f
        val progress = abs(position)
        val easedProgress = progress * progress * (3f - 2f * progress)
        val shift = page.width * 0.04f

        page.translationX = when {
            position <= 0f -> -direction * easedProgress * shift
            else -> direction * easedProgress * shift
        }
        page.scaleX = 1f - easedProgress * 0.01f
        page.scaleY = 1f - easedProgress * 0.015f
        page.alpha = 1f - easedProgress * 0.12f
    }
}
