package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 2026-09-23 A95L 真机事故:卸载一个应用时,MainActivity 的动态接收器与清单里的 PackageRemovedReceiver
 * 对同一次卸载各跑一遍 read → 改 → write,共用同一个 `layout.json.tmp`;输的一方 rename 失败走兜底
 * `dst.delete()`,正式文件没了,下一次 read 把内置默认布局写了回去——用户排好的整个首页被换掉。
 */
class LockedFileTest {
    private fun tempDir(): File = Files.createTempDirectory("lockedfile").toFile()

    @Test fun concurrentReadModifyWriteNeverLosesTheFileOrAnUpdate() {
        val dir = tempDir()
        val store = LockedFile("counter.txt")
        store.write(dir, "0")
        val threads = 16
        val perThread = 50
        val start = CountDownLatch(1)
        val missing = AtomicInteger(0)
        val workers = (1..threads).map {
            Thread {
                start.await()
                repeat(perThread) {
                    store.update(dir) { text ->
                        if (text == null) { missing.incrementAndGet(); "0" } else (text.toInt() + 1).toString()
                    }
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals("读到过「文件不存在」", 0, missing.get())
        assertEquals((threads * perThread).toString(), File(dir, "counter.txt").readText())
        assertFalse("临时文件不该残留", dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun writeKeepsThePreviousVersionAsPrev() {
        val dir = tempDir()
        val store = LockedFile("layout.json")
        store.write(dir, "v1")
        store.write(dir, "v2")
        assertEquals("v2", File(dir, "layout.json").readText())
        assertEquals("v1", store.prev(dir).readText())
    }

    @Test fun readTextFallsBackToPrevWhenTheFileIsGone() {
        val dir = tempDir()
        val store = LockedFile("layout.json")
        store.write(dir, "v1")
        store.write(dir, "v2")
        assertTrue(File(dir, "layout.json").delete())
        assertEquals(LockedFile.Read("v1", fromPrev = true), store.readText(dir))
    }

    @Test fun readTextIsNullWhenNeitherExists() {
        assertEquals(null, LockedFile("layout.json").readText(tempDir()))
    }
}
