package eu.kanade.tachiyomi.ui.reader.viewer.pager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.util.system.ImageUtil

@RunWith(AndroidJUnit4::class)
class PageTransformDecoderTest {
    private fun avif(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets
        .open("page-transform.avif").use { it.readBytes() }

    @Test
    fun avifDecodesWithoutConsumingTheOriginalPage() {
        val bytes = avif()
        val source = Buffer().write(bytes)
        val bitmap = ImageUtil.decodeBitmap(source)
        assertNotNull(bitmap)
        assertEquals(128, bitmap!!.width)
        assertEquals(64, bitmap.height)
        bitmap.recycle()
        assertArrayEquals(bytes, source.readByteArray())
    }

    @Test
    fun avifDimensionsSplittingAndRotationUseReaderCodecSupport() {
        val bytes = avif()
        val source = Buffer().write(bytes)
        assertEquals(true, ImageUtil.isWideImage(source))
        val half = ImageUtil.decodeBitmap(ImageUtil.splitInHalf(source, ImageUtil.Side.LEFT))!!
        assertEquals(64, half.width)
        assertEquals(64, half.height)
        half.recycle()
        val rotated = ImageUtil.decodeBitmap(ImageUtil.rotateImage(source, 90f))!!
        assertEquals(64, rotated.width)
        assertEquals(128, rotated.height)
        rotated.recycle()
        assertArrayEquals(bytes, source.readByteArray())
    }

    @Test
    fun failedTransformationDecodeLeavesOriginalBytesAvailableForFallback() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source = Buffer().write(bytes)
        assertNull(ImageUtil.decodeBitmap(source))
        assertArrayEquals(bytes, source.readByteArray())
    }
}
