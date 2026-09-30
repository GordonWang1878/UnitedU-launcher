package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-30 测试轮 B-05:撞名加 `-N` 之后仍要放得进一个文件名(ext4 / f2fs 上限 255 **字节**),
 * 且截短主名时不劈开代理对。
 */
class UploadUniqueNameLengthTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test fun longestAsciiNameStillFitsAfterSuffix() {
        val name = "a".repeat(250) + ".jpg"            // 254 字节,本身放得下
        val out = uniqueName(setOf(name), name)
        assertNotEquals(name, out)
        assertTrue("${bytes(out)} 字节", bytes(out) <= 255)
        assertTrue(out.endsWith("-1.jpg"))
    }

    @Test fun longestChineseNameStillFitsAfterSuffix() {
        val name = "长".repeat(83) + ".jpg"            // 253 字节
        val out = uniqueName(setOf(name), name)
        assertTrue("${bytes(out)} 字节", bytes(out) <= 255)
        assertTrue(out.endsWith("-1.jpg"))
        assertTrue(out.startsWith("长"))
    }

    @Test fun trimmingNeverSplitsAnEmoji() {
        val name = "ab" + "😀".repeat(62) + ".jpg"     // 2 + 248 + 4 = 254 字节;加 -1 后主名要从 250 截到 ≤ 249
        val out = uniqueName(setOf(name), name)
        assertTrue("${bytes(out)} 字节", bytes(out) <= 255)
        assertEquals("ab" + "😀".repeat(61) + "-1.jpg", out)
        val lone = out.indices.any { i ->
            (Character.isHighSurrogate(out[i]) && (i + 1 >= out.length || !Character.isLowSurrogate(out[i + 1]))) ||
                (Character.isLowSurrogate(out[i]) && (i == 0 || !Character.isHighSurrogate(out[i - 1])))
        }
        assertFalse("不能留下落单的代理", lone)
    }

    @Test fun trimmedCandidatesKeepProbingPastCollisions() {
        val name = "b".repeat(250) + ".png"
        val first = uniqueName(setOf(name), name)
        val second = uniqueName(setOf(name, first), name)
        assertNotEquals(first, second)
        assertTrue(second.endsWith("-2.png"))
        assertTrue(bytes(second) <= 255)
    }

    @Test fun shortNamesAreUnchanged() {
        assertEquals("a-1.jpg", uniqueName(setOf("a.jpg"), "a.jpg"))
        assertEquals("海边-2.jpg", uniqueName(setOf("海边.jpg", "海边-1.jpg"), "海边.jpg"))
    }
}
