package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 2026-09-30 测试轮 B-03:图库目录在不区分大小写的外置存储上(Android 11+ casefold),
 * 只差大小写的上传不能被当成新名字——否则 rename 覆盖掉用户已有的那张。
 */
class UploadCaseFoldTest {

    @Test fun nameThatDiffersOnlyInCaseGetsASuffix() {
        assertEquals("BEACH-1.JPG", uniqueName(setOf("Beach.jpg"), "BEACH.JPG"))
        assertEquals("beach-1.jpg", uniqueName(setOf("BEACH.JPG"), "beach.jpg"))
        assertEquals("Img_0001-1.jpeg", uniqueName(setOf("img_0001.JPEG"), "Img_0001.jpeg"))
    }

    @Test fun suffixProbingIsCaseInsensitiveToo() {
        // a-1.jpg 只以大写形式存在时,下一个空位是 -2,不能回到会撞车的 -1
        assertEquals("a-2.jpg", uniqueName(setOf("A.JPG", "A-1.JPG"), "a.jpg"))
    }

    @Test fun foldKeyTreatsCaseAndCompositionAlike() {
        assertEquals(nameFoldKey("Straße.jpg"), nameFoldKey("STRASSE.JPG"))
        // é 预组合(U+00E9)与 e + U+0301 组合在文件系统眼里是同一个名字
        assertEquals(nameFoldKey("café.jpg"), nameFoldKey("café.jpg"))
        assertEquals(nameFoldKey("海边.jpg"), nameFoldKey("海边.JPG"))
    }

    @Test fun distinctNamesStayUntouched() {
        assertEquals("b.jpg", uniqueName(setOf("a.jpg", "A-1.jpg"), "b.jpg"))
        assertEquals("海边.jpg", uniqueName(setOf("山.jpg"), "海边.jpg"))
    }

    @Test fun saveIntoLibraryKeepsTheExistingFileWhenOnlyCaseDiffers() {
        val dir = Files.createTempDirectory("lib").toFile()
        try {
            File(dir, "Beach.jpg").writeText("old")
            val src = File(dir, "upload.part").apply { writeText("new") }
            // 模拟 casefold 文件系统:目标名与已有文件只差大小写时,rename 会覆盖已有的那个
            val caseFoldRename: (File, File) -> Boolean = { a, b ->
                val clash = dir.listFiles()?.firstOrNull { it.name.equals(b.name, ignoreCase = true) && it != a }
                a.renameTo(clash ?: b)
            }
            val saved = saveIntoLibrary(src, dir, "BEACH.JPG", caseFoldRename)
            assertEquals("BEACH-1.JPG", saved)
            assertEquals("old", File(dir, "Beach.jpg").readText())
            assertTrue(File(dir, "BEACH-1.JPG").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
