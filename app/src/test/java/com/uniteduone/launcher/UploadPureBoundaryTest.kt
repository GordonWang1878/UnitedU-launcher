package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传规则的边界(UploadPureTest 之外):文件名清洗遇到中文 / emoji / 路径穿越 / 控制字符 / 点开头 / 超长多字节名,
 * 扩展名的大小写与双扩展名,大小上限正好卡在线上与多 1 字节。
 */
class UploadPureBoundaryTest {

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                i += 2
                continue
            }
            if (c.isLowSurrogate()) return true
            i++
        }
        return false
    }

    // ---- 文件名清洗 ----

    @Test fun chineseEmojiAndInnerSpacesArePreserved() {
        assertEquals("海边 日落 🌅.JPG", sanitizeUploadName("海边 日落 🌅.JPG"))
        assertEquals("👨‍👩‍👧 全家福.png", sanitizeUploadName("  👨‍👩‍👧 全家福.png  "))
        assertEquals("a  b.jpg", sanitizeUploadName("a  b.jpg"))
    }

    @Test fun traversalAndAbsolutePathsKeepOnlyTheLastSegment() {
        assertEquals("passwd", sanitizeUploadName("../../etc/passwd"))
        assertEquals("a.jpg", sanitizeUploadName("..\\..\\Windows\\a.jpg"))
        assertEquals("a.jpg", sanitizeUploadName("/a.jpg"))
        assertEquals("a.jpg", sanitizeUploadName("C:\\fakepath\\a.jpg"))
        assertEquals("c.jpg", sanitizeUploadName("a/b\\c.jpg"))
        assertEquals("c.jpg", sanitizeUploadName("a\\b/c.jpg"))
        assertEquals("海.jpg", sanitizeUploadName("../相册/海.jpg"))
    }

    @Test fun pathsEndingInDotsOrSeparatorsAreRejected() {
        assertNull(sanitizeUploadName("../"))
        assertNull(sanitizeUploadName("..\\"))
        assertNull(sanitizeUploadName("a/.."))
        assertNull(sanitizeUploadName("a\\.."))
        assertNull(sanitizeUploadName("a/."))
        assertNull(sanitizeUploadName("/"))
        assertNull(sanitizeUploadName("\\"))
    }

    @Test fun controlCharactersAnywhereAreDroppedNotReplaced() {
        assertEquals("a.jpg", sanitizeUploadName("\u0000\u0001a\u001F.jpg\u007F"))
        assertEquals("ab.jpg", sanitizeUploadName("a\nb.jpg"))
        assertEquals("ab.jpg", sanitizeUploadName("a\tb.jpg"))
        assertEquals("ab.jpg", sanitizeUploadName("a\r\nb.jpg"))
        assertNull(sanitizeUploadName("\u0000\u0001\u001F\u007F"))
        assertNull(sanitizeUploadName(" \t \n "))
    }

    @Test fun nulInjectionCannotSmuggleAnImageExtension() {
        // 经典的「a.jpg\0.php」:NUL 被删掉后扩展名是 php,在类型那道闸被拒,不会当成 jpg 收下
        val clean = sanitizeUploadName("a.jpg\u0000.php")
        assertEquals("a.jpg.php", clean)
        assertEquals("type", ScreensaverMedia.uploadRejection("wallpapers", clean, 1, null))
    }

    @Test fun namesThatStartWithADotAfterCleaningAreRejected() {
        assertNull(sanitizeUploadName("\u0000.jpg"))       // 删掉控制字符后是点开头
        assertNull(sanitizeUploadName("   .jpg"))          // trim 之后是点开头
        assertNull(sanitizeUploadName("dir/.jpg"))
        assertNull(sanitizeUploadName("dir\\.seeded"))
        assertNull(sanitizeUploadName(". a.jpg"))
        assertNull(sanitizeUploadName("..jpg"))
        assertNull(sanitizeUploadName("...."))
    }

    @Test fun onlyExtensionNamesAreRejected() {
        for (n in listOf(".jpg", ".JPG", ".png", ".webp", ".mp4", ".jpeg")) assertNull(n, sanitizeUploadName(n))
    }

    @Test fun exactlyOneHundredCharsIsKeptVerbatimAndOneMoreIsCut() {
        val hundred = "x".repeat(96) + ".jpg"
        assertEquals(hundred, sanitizeUploadName(hundred))
        val cut = sanitizeUploadName("x".repeat(97) + ".jpg")!!
        assertEquals(100, cut.length)
        assertEquals("x".repeat(96) + ".jpg", cut)
    }

    @Test fun longNameWithoutExtensionIsCappedAtOneHundred() {
        assertEquals("x".repeat(100), sanitizeUploadName("x".repeat(300)))
    }

    @Test fun longNameEndingInADotKeepsTheDotAndStaysRejectable() {
        val out = sanitizeUploadName("x".repeat(150) + ".")!!
        assertEquals(100, out.length)
        assertTrue(out.endsWith("."))
        assertEquals("", extensionOf(out))
        assertEquals("type", ScreensaverMedia.uploadRejection("wallpapers", out, 1, null))
    }

    @Test fun truncationNeverSplitsASurrogatePair() {
        // 'a' + 150 个 emoji:主名按 UTF-16 单元截到 96 时正好落在一个 emoji 的两半之间;
        // 留下半个代理对,写进文件名 / JSON 会变成「?」或 U+FFFD(与 truncateTitle 同一条规矩)
        for (prefix in listOf("", "a", "ab")) {
            val out = sanitizeUploadName(prefix + "🌊".repeat(150) + ".jpg")!!
            assertTrue(out, out.endsWith(".jpg"))
            assertTrue(out, out.length <= 100)
            assertFalse("前缀「$prefix」截出了孤立代理项", hasLoneSurrogate(out))
            assertEquals(out, String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
    }

    @Test fun aStemOfOneEmojiBeforeAHugeExtensionIsKeptWhole() {
        // 扩展名本身超过 100 字符时主名只留一个码点:一个 emoji 是两个 UTF-16 单元,也得整个留下,
        // 否则只剩半个代理对,或者主名被截空、名字变成点开头
        val out = sanitizeUploadName("🌊." + "x".repeat(150))!!
        assertTrue(out, out.startsWith("🌊."))
        assertFalse(hasLoneSurrogate(out))
    }

    @Test fun longChineseNameFitsTheFilesystemNameLimit() {
        // ext4 / f2fs 一个文件名最多 255 **字节**:只按 100 个字符截,96 个汉字 + .jpg 仍有 292 字节,
        // saveIntoLibrary 的 rename 与复制回落都建不出这个文件,整张图以「write」被拒
        for (raw in listOf("海".repeat(300) + ".jpg", "海".repeat(99) + ".jpeg", "海".repeat(84) + ".jpg", "a" + "海".repeat(200) + ".webp")) {
            val out = sanitizeUploadName(raw)!!
            val ext = raw.substring(raw.lastIndexOf('.'))
            assertTrue(out, out.endsWith(ext))
            assertTrue(out, out.length > ext.length)
            assertFalse(hasLoneSurrogate(out))
            assertTrue("${utf8(out)} 字节:$out", utf8(out) <= 255)
        }
        assertEquals("海".repeat(83) + ".jpg", sanitizeUploadName("海".repeat(84) + ".jpg"))
        // 4 字节的 emoji 与 3 字节的汉字混排:截完既不超 255 字节,也不留半个 emoji
        val mixed = sanitizeUploadName("🌊海".repeat(40) + ".png")!!
        assertTrue(utf8(mixed) <= 255)
        assertFalse(hasLoneSurrogate(mixed))
    }

    @Test fun namesThatAlreadyFitOnDiskPassUnchanged() {
        // 删除 / 缩略图接口拿同一个函数清洗图库里已有的文件名再去找文件:≤ 255 字节、≤ 100 字符的名字必须原样通过
        for (n in listOf("海".repeat(80) + ".jpg", "海".repeat(83) + ".jpg", "🌊".repeat(48) + ".jpg", "x".repeat(96) + ".jpg")) {
            assertEquals(n, sanitizeUploadName(n))
        }
    }

    @Test fun shortMultiByteNamesAreNotTouched() {
        val n = "海".repeat(30) + ".jpg"
        assertEquals(n, sanitizeUploadName(n))
        val e = "🌊".repeat(20) + ".png"
        assertEquals(e, sanitizeUploadName(e))
    }

    @Test fun sanitizingIsIdempotent() {
        val inputs = listOf(
            "a.jpg", "  a b .jpg ", "x".repeat(150) + ".jpeg", "海".repeat(300) + ".jpg",
            "a" + "🌊".repeat(150) + ".jpg", "x".repeat(300), "C:\\fakepath\\海边.png", "a\u0000b.webp",
        )
        for (raw in inputs) {
            val once = sanitizeUploadName(raw)!!
            assertEquals(raw, once, sanitizeUploadName(once))
        }
    }

    // ---- 扩展名 ----

    @Test fun uppercaseAndMixedCaseExtensionsAreAccepted() {
        assertEquals("jpg", extensionOf("A.JPG"))
        assertEquals("jpeg", extensionOf("a.JpEg"))
        assertEquals("webp", extensionOf("海.WEBP"))
        for (n in listOf("A.JPG", "a.JpEg", "a.PNG", "a.WebP")) {
            assertNull(n, ScreensaverMedia.uploadRejection("wallpapers", n, 1, null))
        }
    }

    @Test fun onlyTheLastExtensionCounts() {
        assertEquals("jpg", extensionOf("a.php.jpg"))
        assertNull(ScreensaverMedia.uploadRejection("cards", "a.php.jpg", 1, null))
        assertEquals("type", ScreensaverMedia.uploadRejection("cards", "a.jpg.php", 1, null))
        assertEquals("type", ScreensaverMedia.uploadRejection("cards", "a.jpg.", 1, null))
        assertEquals("type", ScreensaverMedia.uploadRejection("cards", "jpg", 1, null))
        assertEquals("", extensionOf("a.jpg."))
    }

    // ---- 大小上限 ----

    @Test fun photoAtExactlyTheLimitPassesAndOneByteMoreIsRejected() {
        for (type in LIBRARY_TYPES) {
            for (name in listOf("a.jpg", "a.PNG", "a.webp")) {
                assertNull("$type/$name", ScreensaverMedia.uploadRejection(type, name, MAX_UPLOAD_BYTES, null))
                assertEquals("$type/$name", "size", ScreensaverMedia.uploadRejection(type, name, MAX_UPLOAD_BYTES + 1, null))
            }
        }
    }

    @Test fun emptyPhotoPassesTheSizeGate() {
        // 0 字节不在大小这道闸拒:由后面的「能不能解码」拒(decode)
        assertNull(ScreensaverMedia.uploadRejection("wallpapers", "a.jpg", 0, null))
    }

    @Test fun videoLimitAppliesOnlyToScreensaversAndOnlyToVideos() {
        val big = ScreensaverMedia.MAX_VIDEO_BYTES
        assertNull(ScreensaverMedia.uploadRejection("screensavers", "a.MP4", big, null))
        assertEquals("video_size", ScreensaverMedia.uploadRejection("screensavers", "a.MOV", big + 1, null))
        // 照片进屏保分类仍按 30 MB 算,不因为分类收视频就放宽
        assertEquals("size", ScreensaverMedia.uploadRejection("screensavers", "a.jpg", MAX_UPLOAD_BYTES + 1, null))
        // 壁纸 / 卡片图:视频在类型那道闸就被拒,不看大小
        assertEquals("type", ScreensaverMedia.uploadRejection("wallpapers", "a.mp4", big + 1, null))
    }

    // ---- 重名 ----

    @Test fun uniqueNameFillsTheFirstGapAndOnlySplitsTheLastExtension() {
        assertEquals("a-1.jpg", uniqueName(setOf("a.jpg", "a-2.jpg"), "a.jpg"))
        assertEquals("a.tar-1.gz", uniqueName(setOf("a.tar.gz"), "a.tar.gz"))
        assertEquals("海边-1.jpg", uniqueName(setOf("海边.jpg"), "海边.jpg"))
        assertEquals("a-1-1.jpg", uniqueName(setOf("a-1.jpg"), "a-1.jpg"))
        assertEquals("a-1.", uniqueName(setOf("a."), "a."))
    }

    // ---- JSON ----

    @Test fun jsonStrEscapesEveryC0Control() {
        for (code in 0 until 0x20) {
            val out = jsonStr(code.toChar().toString())
            assertTrue("U+" + Integer.toHexString(code), out.drop(1).dropLast(1).all { it.code >= 0x20 })
        }
        assertEquals("\"\\u0008\\u000c\"", jsonStr("\b\u000C"))
        assertEquals("\"\\r\\t\"", jsonStr("\r\t"))
    }

    @Test fun jsonStrKeepsUnicodeAsIs() {
        assertEquals("\"海边🌅.jpg\"", jsonStr("海边🌅.jpg"))
        assertEquals("\"\"", jsonStr(""))
    }

    // ---- multipart 字段 ----

    @Test fun uploadKeysZeroSuffixSortsRightAfterTheBareKey() {
        assertEquals(
            listOf("files", "files0", "files1"),
            uploadKeys(setOf("files1", "files0", "files"), emptySet()),
        )
        assertEquals(emptyList<String>(), uploadKeys(setOf("file", "apk"), setOf("type")))
    }
}
