package com.uniteduone.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `cacheDir/apk/` 的进程内登记簿(review Important 2):每次尝试一个独占文件;
 * 清扫只删**没登记**的更新文件,正在用的(下载中 / 等待安装 / 已交给安装器)一个都不碰。
 */
class UpdateFilesTest {

    private val dir: File = Files.createTempDirectory("apk").toFile()

    @After fun cleanup() {
        dir.deleteRecursively()
    }

    @Test fun reserveGivesDistinctNamesWithoutTouchingDisk() {
        val files = UpdateFiles(dir)
        val a = files.reserve(".apk")
        val b = files.reserve(".apk")
        assertNotEquals(a.name, b.name)
        assertEquals(dir, a.parentFile)
        assertTrue(isUpdateFileName(a.name))
        assertTrue(a.name.endsWith(".apk"))
        assertFalse(a.exists())
        assertTrue(files.isInUse(a))
        assertTrue(files.isInUse(b))
    }

    @Test fun sweepDeletesOnlyUnregisteredUpdateFiles() {
        val files = UpdateFiles(dir)
        val mine = files.reserve(".apk").apply { writeText("mine") }
        val myPart = files.reserve(".part").apply { writeText("part") }
        val staleApk = File(dir, "update-old.apk").apply { writeText("stale") }
        val stalePart = File(dir, "update-old.part").apply { writeText("stale") }
        val legacy = File(dir, "update.apk").apply { writeText("legacy") }
        val upload = File(dir, "upload.apk").apply { writeText("m6") }

        assertEquals(3, files.sweep())

        assertTrue(mine.exists())
        assertTrue(myPart.exists())
        assertTrue(upload.exists())
        assertFalse(staleApk.exists())
        assertFalse(stalePart.exists())
        assertFalse(legacy.exists())
    }

    @Test fun anotherRegistryInstanceWouldSweepEverything() {
        // 说明为什么登记簿必须进程内唯一(Update.files):各自一本账,就会互删。
        val first = UpdateFiles(dir)
        val inFlight = first.reserve(".part").apply { writeText("downloading") }
        assertEquals(1, UpdateFiles(dir).sweep())
        assertFalse(inFlight.exists())
    }

    @Test fun promoteRenamesPartOntoTheReservedTarget() {
        val files = UpdateFiles(dir)
        val dst = files.reserve(".apk")
        val part = files.reserve(".part").apply { writeText("payload") }

        assertTrue(files.promote(part, dst))

        assertFalse(part.exists())
        assertFalse(files.isInUse(part))
        assertEquals("payload", dst.readText())
        assertTrue(files.isInUse(dst))
        assertEquals(0, files.sweep())
        assertTrue(dst.exists())
    }

    @Test fun failedPromoteKeepsThePartRegistered() {
        val files = UpdateFiles(dir)
        val dst = File(File(dir, "missing-subdir"), "update-x.apk")
        val part = files.reserve(".part").apply { writeText("payload") }
        assertFalse(files.promote(part, dst))
        assertTrue(part.exists())
        assertTrue(files.isInUse(part))
    }

    @Test fun releaseDeletesAndUnregisters() {
        val files = UpdateFiles(dir)
        val f = files.reserve(".apk").apply { writeText("x") }

        assertTrue(files.release(f))

        assertFalse(f.exists())
        assertFalse(files.isInUse(f))
        // 同名文件若再出现(不该发生),已不受保护,清扫会删掉它
        f.writeText("again")
        assertEquals(1, files.sweep())
        assertFalse(f.exists())
    }

    @Test fun releasingAReservationThatNeverHitTheDiskIsFine() {
        val files = UpdateFiles(dir)
        val f = files.reserve(".apk")
        assertTrue(files.release(f))
        assertFalse(files.isInUse(f))
    }

    @Test fun sweepOfAMissingDirectoryIsANoOp() {
        assertEquals(0, UpdateFiles(File(dir, "nope")).sweep())
    }
}
