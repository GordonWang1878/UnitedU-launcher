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

    @Test fun uploadKeysOrdersFilesThenNumericSuffixes() {
        assertEquals(
            listOf("files", "files1", "files2"),
            uploadKeys(setOf("files", "files2", "files1"), setOf("files", "files1", "files2")),
        )
    }

    @Test fun uploadKeysToleratesGaps() {
        // 某个 part 没带逐段 Content-Type 时 NanoHTTPD 会跳号(files、files2 之间没有 files1)——
        // 按固定步长探测会在空位截断,按实际 key 枚举不会。
        assertEquals(listOf("files", "files2"), uploadKeys(setOf("files", "files2"), setOf("files", "files2")))
    }

    @Test fun uploadKeysSortsNumericallyNotLexically() {
        assertEquals(
            listOf("files", "files2", "files10"),
            uploadKeys(setOf("files", "files10", "files2"), setOf("files", "files10", "files2")),
        )
    }

    @Test fun uploadKeysPutsUnparsableSuffixesLast() {
        // 非数字后缀与溢出 Int 的数字后缀都排最后,不抛异常、不挡住同批正常文件。
        assertEquals(
            listOf("files", "files2", "files99999999999999", "filesX"),
            uploadKeys(
                setOf("filesX", "files2", "files", "files99999999999999"),
                setOf("filesX", "files2", "files", "files99999999999999"),
            ),
        )
    }

    @Test fun uploadKeysUnionsBothMaps() {
        // 只出现在一张表里的 key 也要进来(两张表各自可能缺项);非 files* 的参数(type)不进来。
        assertEquals(listOf("files", "files1"), uploadKeys(setOf("files"), setOf("files1", "type")))
        assertEquals(emptyList<String>(), uploadKeys(emptySet(), setOf("type", "apk")))
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

    @Test fun jsonEscapesLessThanForScriptSafety() {
        assertEquals("\"\\u003c/script>\"", jsonStr("</script>"))
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

    @Test fun utf8MultipartContentTypeOnlyPatchesCharsetlessMultipart() {
        assertEquals(
            "multipart/form-data; boundary=----WebKitFormBoundaryabc; charset=UTF-8",
            utf8MultipartContentType("multipart/form-data; boundary=----WebKitFormBoundaryabc"),
        )
        assertEquals(null, utf8MultipartContentType("multipart/form-data; boundary=x; charset=utf-8"))
        assertEquals(null, utf8MultipartContentType("application/json"))
        assertEquals(null, utf8MultipartContentType(null))
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

    // ---- R63:手机网页默认分页 ----

    @Test fun defaultTabIsInjectedAsAJsStringForLibraryTypes() {
        assertEquals("\"screensavers\"", defaultTabJs("screensavers"))
        assertEquals("\"wallpapers\"", defaultTabJs("wallpapers"))
        assertEquals("\"cards\"", defaultTabJs("cards"))
    }

    @Test fun defaultTabIsNullForTheGeneralEntryOrJunk() {
        assertEquals("null", defaultTabJs(null))
        assertEquals("null", defaultTabJs("apk"))
        assertEquals("null", defaultTabJs("\"</script><script>alert(1)"))
    }

    // ---- saveIntoLibrary(2026-09-23 落盘排查)----

    private fun tmpDir(): java.io.File = java.nio.file.Files.createTempDirectory("lib").toFile()

    /** 两批同名上传并发落盘:旧写法各自看到同一个空位、后到的 rename 静默覆盖先到的。现在一张都不丢。 */
    @Test fun concurrentSameNameUploadsNeverOverwriteEachOther() {
        val src = tmpDir()
        val lib = tmpDir()
        val n = 24
        val files = (0 until n).map { i -> java.io.File(src, "up$i").apply { writeText("img$i") } }
        val start = java.util.concurrent.CountDownLatch(1)
        val names = java.util.Collections.synchronizedList(ArrayList<String?>())
        val workers = files.map { f ->
            Thread { start.await(); names += saveIntoLibrary(f, lib, "a.jpg") }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(n, names.filterNotNull().toSet().size)
        assertEquals((0 until n).map { "img$it" }.toSet(), lib.listFiles()!!.map { it.readText() }.toSet())
    }

    /** 跨卷 rename 失败 → 复制回落:落盘名里是完整内容,源删掉,图库里不留临时文件。 */
    @Test fun crossVolumeFallbackCopiesCompletelyAndCleansUp() {
        val src = java.io.File(tmpDir(), "upload").apply { writeText("pixels") }
        val lib = tmpDir()
        java.io.File(lib, "a.jpg").writeText("existing")
        val name = saveIntoLibrary(src, lib, "a.jpg", rename = { _, _ -> false })
        assertEquals("a-1.jpg", name)
        assertEquals("pixels", java.io.File(lib, "a-1.jpg").readText())
        assertEquals("existing", java.io.File(lib, "a.jpg").readText())
        assertFalse(src.exists())
        assertEquals(setOf("a.jpg", "a-1.jpg"), lib.list()!!.toSet())
    }

    @Test fun staleLibraryTempNamesAreRecognisedButImagesAreNot() {
        assertTrue(isStaleLibraryTemp(".a.jpg.123456.tmp"))
        assertFalse(isStaleLibraryTemp("a.jpg"))
        assertFalse(isStaleLibraryTemp(".seeded"))
        assertFalse(extensionOf(".a.jpg.123456.tmp") in UPLOAD_IMAGE_EXTS)
    }
}
