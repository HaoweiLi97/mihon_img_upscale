package eu.kanade.tachiyomi.data.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class CstrArchiveTest {
    @Test
    fun distinguishesCollectedVolumesFromInstallments() {
        for ((title, type, expected) in listOf(
            Triple("第２卷", "volume", 2.0),
            Triple("Vol. 4", "volume", 4.0),
            Triple("第2卷 第12.5話", "chapter", 12.5),
            Triple("Vol. 2 Ch. 12", "chapter", 12.0),
        )) {
            val entry = JSONObject().put("chapterNumber", 99)
            writeCstrEntryType(entry, Chapter.create().copy(name = title, chapterNumber = 99.0))
            assertEquals(type, entry.getString("type"))
            assertEquals(expected, entry.getDouble(if (type == "volume") "volumeNumber" else "chapterNumber"), 0.0)
            assertTrue(entry.has("volumeNumber") != entry.has("chapterNumber"))
        }
    }

    @Test
    fun readsPagesBeforeThumbnailData() {
        withArchive(archiveBytes()) { archive ->
            assertEquals(2, archive.pageCount)
            for (index in 0 until archive.pageCount) {
                assertEquals("webp", archive.pageExtension(index))
                val bitmap = archive.openPage(index).use { checkNotNull(BitmapFactory.decodeStream(it)) }
                try {
                    assertEquals(2, bitmap.width)
                    assertEquals(2, bitmap.height)
                    val pixel = bitmap.getPixel(0, 0)
                    assertTrue(if (index == 0) Color.red(pixel) > 200 else Color.blue(pixel) > 200)
                } finally {
                    bitmap.recycle()
                }
                val preview = checkNotNull(archive.readPreview(index))
                try {
                    assertEquals(2, preview.width)
                    assertEquals(2, preview.height)
                    val pixel = preview.getPixel(0, 0)
                    assertTrue(if (index == 0) Color.red(pixel) > 200 else Color.blue(pixel) > 200)
                } finally {
                    preview.recycle()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun preservesOriginalPageBytesAndEmbedsPreviews() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = kotlin.io.path.createTempDirectory(cache.toPath(), "cstr-originals-").toFile()
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        try {
            val jpeg = File(directory, "01.jpg")
            val png = File(directory, "02.png")
            val webp = File(directory, "03.webp")
            val avif = File(directory, "04.avif").apply { writeBytes(avifBytes()) }
            val rotated = File(directory, "05.jpg")
            jpeg.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            png.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            webp.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP, 85, it)) }
            jpeg.copyTo(rotated)
            ExifInterface(rotated.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }

            val originals = listOf(jpeg, png, webp, avif)
            val packed = File(directory, "result.cstr")
            assertTrue(packed.createNewFile())
            val destination = checkNotNull(UniFile.fromFile(packed))
            val pages = (originals + rotated).map { checkNotNull(UniFile.fromFile(it)) }
            writeCstrArchive(cache, destination, pages, JSONObject().put("title", "Originals"), null)

            val bytes = packed.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(3, header.getShort(6).toInt())
            assertEquals(48L, header.getLong(16))
            val coverOffset = 48 + header.getInt(24) + header.getInt(28)
            assertEquals(coverOffset.toLong(), header.getLong(32))
            val coverSize = header.getInt(40)
            assertTrue(coverSize in 1..128 * 1024)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, coverOffset, coverSize, options)
            assertEquals("image/webp", options.outMimeType)
            assertEquals(4, options.outWidth)
            assertEquals(3, options.outHeight)
            assertEquals(options.outWidth, header.getShort(44).toInt())
            assertEquals(options.outHeight, header.getShort(46).toInt())
            val archive = CstrArchive(destination)
            assertEquals(listOf("jpg", "png", "webp", "avif", "webp"), (0 until archive.pageCount).map(archive::pageExtension))
            originals.forEachIndexed { index, original ->
                assertArrayEquals(original.readBytes(), archive.openPage(index).use { it.readBytes() })
            }
            val rotationPreview = checkNotNull(archive.readPreview(4))
            try {
                assertEquals(3, rotationPreview.width)
                assertEquals(4, rotationPreview.height)
            } finally {
                rotationPreview.recycle()
            }
        } finally {
            bitmap.recycle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsAnInvalidThumbnailOffsetOrLength() {
        val wrongOffset = archiveBytes().apply { this[120 + 8] = 0 }
        val truncated = archiveBytes().let { it.copyOf(it.size - 1) }
        val trailing = archiveBytes() + byteArrayOf(0)
        for (bytes in listOf(wrongOffset, truncated, trailing)) {
            assertThrows(IOException::class.java) { withArchive(bytes) {} }
        }
    }

    private fun withArchive(bytes: ByteArray, read: (CstrArchive) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("cstr-previews-", ".cstr", cache)
        try {
            file.writeBytes(bytes)
            read(CstrArchive(checkNotNull(UniFile.fromFile(file))))
        } finally {
            file.delete()
        }
    }

    // Two 2 × 2 PNG inputs packed by ComicStream with --thumbnails --reading-direction rtl.
    private fun archiveBytes(): ByteArray = Base64.getMimeDecoder().decode(
        """
        Q1NUUgEAAQACAAAAAgAAACAAAAAAAAAAaAAAABoAAAACAAAAAgAAAAAAAAABAAAAAgAAAAIAAAABAAAAAQAAAAIA
        AAABAAAAAAAAAAAAAABIAAAAAgAAAAEAAABIAAAAAAAAAEQAAAACAAIARAAAAAAAAAAAAAAAAgACAEAAAABEAAAA
        AAAAAHsicmVhZGluZ0RpcmVjdGlvbiI6InJ0bCJ9UklGRkAAAABXRUJQVlA4IDQAAADwAQCdASoCAAIAAQAcJaAC
        dLoB+AAETAAA/vW4f/6aR40jxpHxcP/ugT90CfugT/3NoAAAUklGRjwAAABXRUJQVlA4IDAAAACwAQCdASoCAAIA
        AQAcJaACdLoABDAAAP78uEv/3QJ/90Cf/dAn/ugT/FtI6FJ4AABSSUZGPAAAAFdFQlBWUDggMAAAANABAJ0BKgIA
        AgACADYloAJ0ugH4AAOwAP7wm0P/ILlhdcjX/yA/5Af8gP/48gAAAFJJRkY4AAAAV0VCUFZQOCAsAAAAkAEAnQEq
        AgACAAIANiWgAnS6AAOYAP75Hdv/kB//kB//kB//ID/iF3sgMAA=
        """.trimIndent(),
    )

    private fun avifBytes(): ByteArray = Base64.getMimeDecoder().decode(
        """
        AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAADrbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAcGljdAAA
        AAAAAAAAAAAAAAAAAAAOcGl0bQAAAAAAAQAAAB5pbG9jAAAAAEQAAAEAAQAAAAEAAAETAAAAKQAAAChpaW5mAAAA
        AAABAAAAGmluZmUCAAAAAAEAAGF2MDFDb2xvcgAAAABqaXBycAAAAEtpcGNvAAAAFGlzcGUAAAAAAAAABAAAAAMA
        AAAQcGl4aQAAAAADCAgIAAAADGF2MUOBAAwAAAAAE2NvbHJuY2x4AAEADQAGgAAAABdpcG1hAAAAAAAAAAEAAQQB
        AoMEAAAAMW1kYXQSAAoIGAR5ogIaDQgyGxTHh4ZlAgggnlAAAABIWtlc1jYHZKV4P/yoyA==
        """.trimIndent(),
    )
}
