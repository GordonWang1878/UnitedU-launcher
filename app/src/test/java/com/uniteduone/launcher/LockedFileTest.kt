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

    // ---- load:四个状态文件共用的「正式文件 → .bad 留证 → .prev → 默认」口径 ----

    private fun strictInt(t: String): Int = t.trim().toInt()

    @Test fun loadReadsTheFileWhenItParses() {
        val dir = tempDir()
        val store = LockedFile("s.json")
        store.write(dir, "1")
        store.write(dir, "2")
        assertEquals(LockedFile.Load.Ok(2, restored = false), store.load(dir, parse = ::strictInt))
    }

    @Test fun loadFallsBackToPrevWhenTheFileIsGone() {
        val dir = tempDir()
        val store = LockedFile("s.json")
        store.write(dir, "1")
        store.write(dir, "2")
        assertTrue(store.file(dir).delete())
        assertEquals(LockedFile.Load.Ok(1, restored = true), store.load(dir, parse = ::strictInt))
    }

    @Test fun loadRenamesACorruptFileToBadAndFallsBackToPrev() {
        val dir = tempDir()
        val store = LockedFile("s.json")
        store.write(dir, "1")
        store.write(dir, "2")
        store.file(dir).writeText("{半截")
        assertEquals(LockedFile.Load.Ok(1, restored = true), store.load(dir, parse = ::strictInt))
        assertFalse(store.file(dir).exists())
        assertEquals("{半截", store.bad(dir).readText())
    }

    @Test fun loadIsCorruptWhenNothingParses() {
        val dir = tempDir()
        val store = LockedFile("s.json")
        store.file(dir).writeText("x")
        assertEquals(LockedFile.Load.Corrupt, store.load(dir, parse = ::strictInt))
        assertTrue(store.bad(dir).exists())
        // 只有一份坏的 .prev(正式文件不在)也是 Corrupt,不是 Missing:调用方要写回默认,不是当首次运行
        val dir2 = tempDir()
        store.prev(dir2).writeText("y")
        assertEquals(LockedFile.Load.Corrupt, store.load(dir2, parse = ::strictInt))
    }

    @Test fun loadIsMissingOnlyWhenNeitherFileExists() {
        assertEquals(LockedFile.Load.Missing, LockedFile("s.json").load(tempDir(), parse = ::strictInt))
    }

    @Test fun loadTreatsAnOversizedFileAsCorruptWithoutReadingIt() {
        val dir = tempDir()
        val store = LockedFile("s.json")
        store.file(dir).writeText("12345")
        var parsed = false
        val got = store.load(dir, maxBytes = 4) { parsed = true; strictInt(it) }
        assertEquals(LockedFile.Load.Corrupt, got)
        assertFalse(parsed)
    }

    /** 事故形状的回归:并发的「读改写」与「整份写」交错,正式文件在任何时刻被读都在(load 从不回 Missing)。 */
    @Test fun concurrentWritersNeverMakeLoadSeeAMissingFile() {
        val dir = tempDir()
        val store = LockedFile("s.json")
        store.write(dir, "0")
        val start = CountDownLatch(1)
        val missing = AtomicInteger(0)
        val workers = (1..8).map { t ->
            Thread {
                start.await()
                repeat(100) {
                    if (t % 2 == 0) store.update(dir) { (it!!.toInt() + 1).toString() }
                    else if (store.load(dir, parse = ::strictInt) !is LockedFile.Load.Ok) missing.incrementAndGet()
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(0, missing.get())
        assertEquals("400", store.file(dir).readText())
    }

    // ---- writeFileAtomically:二进制文件的原子替换 ----

    @Test fun atomicWriteReplacesTheFileAndLeavesNoTemp() {
        val dir = tempDir()
        val dst = File(dir, "a.png")
        dst.writeText("old")
        assertTrue(writeFileAtomically(dst) { it.write("new".toByteArray()) })
        assertEquals("new", dst.readText())
        assertEquals(listOf("a.png"), dir.list()!!.toList())
    }

    @Test fun atomicWriteKeepsTheOldFileWhenVerifyFails() {
        val dir = tempDir()
        val dst = File(dir, "a.png")
        dst.writeText("old")
        assertFalse(writeFileAtomically(dst, verify = { false }) { it.write("broken".toByteArray()) })
        assertEquals("old", dst.readText())
        assertEquals(listOf("a.png"), dir.list()!!.toList())
    }

    @Test fun atomicWriteKeepsTheOldFileWhenTheWriterThrows() {
        val dir = tempDir()
        val dst = File(dir, "a.png")
        dst.writeText("old")
        val r = runCatching { writeFileAtomically(dst) { it.write("hal".toByteArray()); error("boom") } }
        assertTrue(r.isFailure)
        assertEquals("old", dst.readText())
        assertEquals(listOf("a.png"), dir.list()!!.toList())
    }

    /** 旧写法共用 `<名>.tmp`:并发写者交错写出混合内容,输的一方还会删掉正式文件。现在每份都完整、文件一直在。 */
    @Test fun concurrentAtomicWritersNeverInterleaveOrDeleteTheFile() {
        val dir = tempDir()
        val dst = File(dir, "cache.jpg")
        val payloads = (0 until 8).map { t -> ByteArray(64 * 1024) { t.toByte() } }
        writeFileAtomically(dst) { it.write(payloads[0]) }
        val start = CountDownLatch(1)
        val bad = AtomicInteger(0)
        val workers = payloads.map { p ->
            Thread {
                start.await()
                repeat(20) {
                    writeFileAtomically(dst) { out -> out.write(p) }
                    val now = runCatching { dst.readBytes() }.getOrNull()
                    if (now == null || now.size != p.size || now.any { it != now[0] }) bad.incrementAndGet()
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals("读到过缺失或交错的内容", 0, bad.get())
        assertEquals(listOf("cache.jpg"), dir.list()!!.toList())
    }
}
