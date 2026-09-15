package com.uniteduone.launcher

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPureTest {

    @Test fun sanitizeStripsPathsAndControlChars() {
        assertEquals("a.jpg", sanitizeUploadName("/tmp/x/a.jpg"))
        assertEquals("a.jpg", sanitizeUploadName("C:\\Users\\me\\a.jpg"))
        assertEquals("a b.jpg", sanitizeUploadName("  a\u0000 b.jpg\u007f "))
        assertEquals("海边.jpg", sanitizeUploadName("海边.jpg"))
    }

    @Test fun sanitizeRejectsDotsHiddenAndEmpty() {
        assertNull(sanitizeUploadName(null))
        assertNull(sanitizeUploadName(""))
        assertNull(sanitizeUploadName("."))
        assertNull(sanitizeUploadName(".."))
        assertNull(sanitizeUploadName(".seeded"))
        assertNull(sanitizeUploadName("dir/"))
    }

    @Test fun sanitizeCapsLengthKeepingExtension() {
        val out = sanitizeUploadName("x".repeat(150) + ".jpeg")!!
        assertEquals(100, out.length)
        assertTrue(out.endsWith(".jpeg"))
    }

    @Test fun uniqueNameAppendsCounterBeforeExtension() {
        assertEquals("a.jpg", uniqueName(emptySet(), "a.jpg"))
        assertEquals("a-1.jpg", uniqueName(setOf("a.jpg"), "a.jpg"))
        assertEquals("a-2.jpg", uniqueName(setOf("a.jpg", "a-1.jpg"), "a.jpg"))
        assertEquals("noext-1", uniqueName(setOf("noext"), "noext"))
    }

    @Test fun pickAddressPrefersWlanEthEnThenAny() {
        assertEquals("192.168.1.9", pickAddress(listOf("dummy0" to "10.0.0.1", "wlan0" to "192.168.1.9")))
        assertEquals("192.168.1.9", pickAddress(listOf("dummy0" to "10.0.0.1", "eth0" to "192.168.1.9")))
        assertEquals("10.0.0.1", pickAddress(listOf("dummy0" to "10.0.0.1")))
        assertNull(pickAddress(emptyList()))
    }

    @Test fun jsonEscapesAndBuildsPayloads() {
        assertEquals("\"a\\\"b\\\\c\\n\"", jsonStr("a\"b\\c\n"))
        assertEquals(
            """{"type":"cards","files":[{"name":"a.jpg","size":1,"mtime":2}]}""",
            jsonFileList("cards", listOf(FileEntry("a.jpg", 1, 2))),
        )
        assertEquals("""{"type":"cards","files":[]}""", jsonFileList("cards", emptyList()))
        assertEquals(
            """{"saved":["a.jpg"],"rejected":[{"name":"x.gif","reason":"type"}]}""",
            jsonUploadResult(listOf("a.jpg"), listOf("x.gif" to "type")),
        )
        assertEquals("""{"ok":true}""", jsonOk())
        assertEquals("""{"ok":true,"package":"p"}""", jsonOk("\"package\":\"p\""))
        assertEquals("""{"ok":false,"reason":"invalid"}""", jsonFail("invalid"))
    }

    @Test fun typeTableIsClosed() {
        assertTrue(isValidType("wallpapers"))
        assertTrue(isValidType("cards"))
        assertTrue(isValidType("screensavers"))
        assertFalse(isValidType("icons"))
        assertFalse(isValidType(null))
        assertFalse(isValidType("../wallpapers"))
        assertEquals("jpg", extensionOf("A.JPG"))
        assertEquals("", extensionOf("noext"))
    }

    @Test fun qrMatrixRoundTripsThroughZxingReader() {
        val url = "http://192.168.1.22:8090/"
        val m = qrMatrix(url, 200)
        val px = IntArray(m.width * m.height) { i ->
            if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, px)))
        assertEquals(url, QRCodeReader().decode(bitmap).text)
    }
}
