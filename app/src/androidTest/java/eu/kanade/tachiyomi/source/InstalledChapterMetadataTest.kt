package eu.kanade.tachiyomi.source

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.toBackupChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.dalvik.DelegateLastClassLoaderCompat
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter

/** Uses the installed extension without network access or changing its preferences. */
@RunWith(AndroidJUnit4::class)
class InstalledChapterMetadataTest {
    @Test
    fun installedExtensionCanBuildChapterUrlAfterMetadataBackupRoundtrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appInfo = runCatching {
            context.packageManager.getApplicationInfo(
                "eu.kanade.tachiyomi.extension.en.allporncomic",
                PackageManager.GET_META_DATA,
            )
        }.getOrNull()
        assumeNotNull(appInfo)
        val info = checkNotNull(appInfo)
        val classLoader = DelegateLastClassLoaderCompat(info.sourceDir, null, context.classLoader)
        val name = checkNotNull(info.metaData.getString("tachiyomi.extension.class"))
        val instance = Class.forName(name, false, classLoader).getDeclaredConstructor().newInstance()
        val sources = when (instance) {
            is SourceFactory -> instance.createSources()
            is Source -> listOf(instance)
            else -> error("Unknown installed source factory")
        }
        val source = sources.filterIsInstance<HttpSource>().first { it.lang == "en" }
        val chapter = Chapter.create().copy(
            url = "123",
            name = "Chapter 1",
            memo = buildJsonObject { put("mangaPath", "/comic/sample/") },
        )
        try {
            source.getChapterUrl(chapter.copy(memo = buildJsonObject {}).toSChapter())
            fail("The installed extension should require chapter metadata")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Refresh the chapter list"))
        }
        val bytes = ProtoBuf.encodeToByteArray(BackupChapter.serializer(), chapter.toBackupChapter())
        val restored = ProtoBuf.decodeFromByteArray(BackupChapter.serializer(), bytes).toChapterImpl()
        assertTrue(source.getChapterUrl(restored.toSChapter()).contains("sample"))
    }
}
