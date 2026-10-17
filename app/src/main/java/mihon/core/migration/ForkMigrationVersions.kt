package mihon.core.migration

/** The fork imported upstream version 16, then advanced its APK version independently. */
internal object ForkMigrationVersions {
    fun startVersion(
        previousForkVersion: Int,
        previousUpstreamVersion: Int,
        currentForkVersion: Int,
        upstreamVersion: Int,
    ): Int {
        val previous = when {
            previousUpstreamVersion > 0 -> previousUpstreamVersion
            previousForkVersion > 0 -> minOf(previousForkVersion, 16)
            else -> 0
        }
        // A fork-only upgrade still runs ALWAYS jobs, without replaying older upstream migrations.
        return if (previous == upstreamVersion && previousForkVersion != currentForkVersion) previous - 1 else previous
    }
}
