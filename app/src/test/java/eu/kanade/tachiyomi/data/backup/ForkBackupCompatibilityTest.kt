package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.core.common.extensions.toByteArray
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class ForkBackupCompatibilityTest {
    @Serializable
    private data class LegacyChapter(
        @ProtoNumber(1) val url: String,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(4) val read: Boolean = true,
        @ProtoNumber(6) val lastPageRead: Long = 12,
        @ProtoNumber(13) val cloudSynced: Boolean = true,
    )

    @Serializable
    private data class LegacyManga(
        @ProtoNumber(1) val source: Long = 1,
        @ProtoNumber(2) val url: String = "/comic/sample/",
        @ProtoNumber(16) val chapters: List<LegacyChapter>,
        @ProtoNumber(112) val hash: String,
    )

    @Test
    fun `previous fork upload markers do not collide with upstream metadata`() {
        val hash = "abc01234".repeat(8)
        val legacy = LegacyManga(chapters = listOf(LegacyChapter("chapter-1", "Chapter 1")), hash = hash)
        val parser = ProtoBuf { encodeDefaults = true }
        val data = parser.encodeToByteArray(LegacyManga.serializer(), legacy)
        val restored = read(data)
        assertEquals(hash, restored.cloudSyncMetaInfoHash)
        assertTrue(restored.chapters.single().cloudSynced)
        assertTrue(restored.chapters.single().read)
        assertEquals(12, restored.chapters.single().lastPageRead)
        assertTrue(restored.chapters.single().toChapterImpl().memo.isEmpty())
        assertTrue(restored.getMangaImpl().memo.isEmpty())
    }

    @Test
    fun `upstream memo and new fork flags roundtrip through the streaming reader`() {
        val memo = buildJsonObject { put("mangaPath", "/comic/sample/") }
        val entry = BackupManga(1, "/comic/sample/", memo = memo.toByteArray()).apply {
            cloudSyncMetaInfoHash = "ab".repeat(32)
            chapters = listOf(BackupChapter("chapter-1", "Chapter 1", memo = memo.toByteArray(), cloudSynced = true))
        }
        val bytes = ProtoBuf.encodeToByteArray(BackupManga.serializer(), entry)
        assertArrayEquals(bytes, normalizeForkBackupEntry(bytes))
        val restored = read(bytes)
        assertEquals(memo, restored.getMangaImpl().memo)
        assertEquals(memo, restored.chapters.single().toChapterImpl().memo)
        assertTrue(restored.chapters.single().cloudSynced)
        assertEquals(entry.cloudSyncMetaInfoHash, restored.cloudSyncMetaInfoHash)
    }

    @Test
    fun `truncated or invalid legacy metadata is rejected`() {
        assertThrows<IOException> { normalizeForkBackupEntry(byteArrayOf(0)) }
        assertThrows<IOException> { normalizeForkBackupEntry(byteArrayOf(0x12, 8, 1)) }
    }

    private fun read(entry: ByteArray): BackupManga {
        val buffer = Buffer().writeByte(10)
        var length = entry.size
        while (length > 127) {
            buffer.writeByte((length and 127) or 128)
            length = length ushr 7
        }
        buffer.writeByte(length).write(entry)
        val reader = BackupReader(buffer, ProtoBuf)
        assertTrue(reader.nextEntry())
        return reader.readEntry()
    }
}
