package eu.kanade.domain.chapter.model

import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.toChapterUpdate

class ChapterMemoTest {

    private val memo = buildJsonObject {
        put("mangaPath", "/comic/sample/")
        put("nested", buildJsonObject { put("token", "source-token") })
    }

    @Test
    fun `chapter metadata reaches the source through both reader and update conversions`() {
        val sourceChapter = SChapter.create().apply {
            url = "chapter-1"
            name = "Chapter 1"
            memo = this@ChapterMemoTest.memo
        }
        val domain = Chapter.create().copyFromSChapter(sourceChapter).copy(id = 7, mangaId = 3)

        assertEquals(memo, domain.memo)
        assertEquals(memo, domain.toSChapter().memo)
        assertEquals(memo, domain.toDbChapter().memo)
        assertEquals(memo, domain.toDbChapter().toDomainChapter()!!.memo)
        assertEquals(memo, domain.toChapterUpdate().memo)
        assertEquals(memo, Chapter.create().copyFrom(domain).memo)
    }

    @Test
    fun `chapter metadata survives backup serialization and legacy backups default to empty metadata`() {
        val backup = BackupChapter("chapter-1", "Chapter 1", memo = memo.toString())
        val restored = ProtoBuf.decodeFromByteArray(
            BackupChapter.serializer(),
            ProtoBuf.encodeToByteArray(BackupChapter.serializer(), backup),
        ).toChapterImpl()

        assertEquals(memo, restored.memo)
        assertEquals(Json.parseToJsonElement("{}").jsonObject, BackupChapter("old", "Old").toChapterImpl().memo)
    }
}
