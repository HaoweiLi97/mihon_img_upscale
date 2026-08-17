package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import java.io.IOException

internal fun finalizeDownloadFile(source: UniFile, parent: UniFile, targetName: String): UniFile {
    val renamed = try {
        source.renameTo(targetName)
    } catch (_: Exception) {
        false
    }
    if (renamed) return source

    val existingTarget = parent.findFile(targetName)
    if (existingTarget != null) {
        if (!source.exists()) return existingTarget
        throw IOException("Download target already exists after rename failed: $targetName")
    }

    val target = parent.createFile(targetName)
        ?: throw IOException("Unable to create download target: $targetName")
    try {
        source.openInputStream().use { input ->
            target.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (!source.delete()) {
            target.delete()
            throw IOException("Unable to remove temporary download after copying: ${source.name}")
        }
    } catch (error: Exception) {
        target.delete()
        if (error is IOException) throw error
        throw IOException("Unable to copy download to: $targetName", error)
    }
    return target
}
