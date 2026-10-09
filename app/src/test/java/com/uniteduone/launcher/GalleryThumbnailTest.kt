package com.uniteduone.launcher

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.nio.ByteBuffer

class GalleryThumbnailTest {
    /** Build a real high-compression PNG by streaming scanlines, without allocating its full pixels. */
    private fun png(width: Int, height: Int): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { out ->
            val row = ByteArray(1 + width * 3)
            repeat(height) { out.write(row) }
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                val name = type.toByteArray(Charsets.US_ASCII)
                out.writeInt(data.size); out.write(name); out.write(data)
                val crc = CRC32(); crc.update(name); crc.update(data); out.writeInt(crc.value.toInt())
            }
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { h -> h.writeInt(width); h.writeInt(height); h.write(byteArrayOf(8, 2, 0, 0, 0)) }
            chunk("IHDR", header.toByteArray()); chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
        }
        return bytes.toByteArray()
    }
    @Test fun legalSmallCompressedFileHasBoundedDecodedPixelAllocation() {
        val encoded = png(8192, 8192)
        assertTrue("Fixture must pass the 512 KiB transport limit", encoded.size < 512 * 1024)
        val header = ByteBuffer.wrap(encoded)
        val width = header.getInt(16); val height = header.getInt(20)
        val plan = galleryThumbnailPlan(width, height, 480, 270)
        assertEquals("Power-of-two native sampling must bound both pixel axes", 32, plan.sampleSize)
        assertEquals(8192, plan.sourceWidth); assertEquals(8192, plan.sourceHeight)
        // Actual BitmapFactory dimensions/allocation are independently exercised by the Android probe.
    }

    @Test fun bannerClassificationUsesActualSourceBoundsAfterSampling() {
        val plan = galleryThumbnailPlan(7680, 4320, 480, 270)
        assertTrue(plan.sampleSize > 1)
        assertTrue(fitsAsBanner(plan.sourceWidth, plan.sourceHeight, 1f))
        assertFalse(fitsAsBanner(0, 0, 1f))
        assertFalse(fitsAsBanner(4320, 7680, 1f))
    }

    @Test fun narrowOddImagesAreStillBoundedAndInvalidBoundsAreRejected() {
        assertEquals(32, galleryThumbnailPlan(1, 8191, 480, 270).sampleSize)
        assertEquals(1, galleryThumbnailPlan(320, 180, 480, 270).sampleSize)
        for ((w, h) in listOf(0 to 10, 10 to -1, 8193 to 1)) {
            assertTrue(runCatching { galleryThumbnailPlan(w, h, 480, 270) }.isFailure)
        }
    }
}
