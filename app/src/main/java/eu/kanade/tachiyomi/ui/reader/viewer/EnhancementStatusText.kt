package eu.kanade.tachiyomi.ui.reader.viewer

import android.text.TextUtils
import android.widget.TextView
import androidx.core.view.isVisible

/** Progress polling must not re-layout or reorder a label whose contents have not changed. */
internal fun TextView.updateEnhancementStatus(label: String?) {
    if (!label.isNullOrEmpty() && !TextUtils.equals(text, label)) {
        text = label
    }
    val show = !label.isNullOrEmpty()
    if (isVisible != show) isVisible = show
}
