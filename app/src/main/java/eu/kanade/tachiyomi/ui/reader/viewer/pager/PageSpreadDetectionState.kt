package eu.kanade.tachiyomi.ui.reader.viewer.pager

/** Keeps in-flight and completed page-spread decisions separate. */
internal class PageSpreadDetectionState<K> {

    private val checking = mutableSetOf<K>()
    private val completed = mutableSetOf<K>()
    private val detected = mutableSetOf<K>()

    /** Returns false when this pair already has an in-flight or completed decision. */
    fun begin(key: K): Boolean = key !in completed && checking.add(key)

    /** Releases an in-flight pair when its source page was split or reloaded. */
    fun defer(key: K) {
        checking.remove(key)
    }

    /** Records a final decision and returns whether this call newly detected a spread. */
    fun complete(key: K, isSpread: Boolean): Boolean {
        checking.remove(key)
        completed.add(key)
        return isSpread && detected.add(key)
    }

    fun isDetected(key: K): Boolean = key in detected

    fun reset() {
        checking.clear()
        completed.clear()
        detected.clear()
    }
}
