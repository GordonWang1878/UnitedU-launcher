package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 关于页唯一那颗按钮在每个状态下该做什么(忙碌态必须吞掉点击,防止重复下载)。 */
class AboutStateTest {

    private val info = LatestInfo(3, "1.0.1", "", "https://example.com/a.apk", "0".repeat(64), 28)

    @Test fun checkStatesOfferACheck() {
        listOf(AboutState.Idle, AboutState.Latest, AboutState.Failed(CheckFailure.NETWORK), AboutState.Failed(CheckFailure.BAD_JSON))
            .forEach { assertEquals(it.toString(), AboutAction.CHECK, it.action) }
    }

    @Test fun busyStatesIgnoreClicks() {
        listOf(AboutState.Checking, AboutState.Downloading(info, 40), AboutState.Downloading(info, null), AboutState.Verifying(info))
            .forEach { assertEquals(it.toString(), AboutAction.NONE, it.action) }
    }

    @Test fun statesWithAnUpdateOfferADownload() {
        listOf(
            AboutState.Found(info),
            AboutState.VerifyFailed(info),
            AboutState.DownloadFailed(info),
            AboutState.NeedsPermission(info),
            AboutState.Installing(info),
            AboutState.InstallFailed(info),
        ).forEach {
            assertEquals(it.toString(), AboutAction.DOWNLOAD, it.action)
            assertEquals(it.toString(), info, it.info)
        }
    }

    @Test fun readyToInstallWaitsForAPressAndIsNotCancellable() {
        // review Important 2:校验结束时 Activity 不在前台 → 停在这里,等用户按键;返回键 = 关页(关页会删文件)。
        val ready = AboutState.ReadyToInstall(info, java.io.File("update-x.apk"))
        assertEquals(AboutAction.INSTALL, ready.action)
        assertEquals(false, ready.cancellable)
        assertEquals(info, ready.info)
    }

    @Test fun checkStatesCarryNoUpdate() {
        listOf(AboutState.Idle, AboutState.Checking, AboutState.Latest, AboutState.Failed(CheckFailure.NETWORK))
            .forEach { assertNull(it.toString(), it.info) }
    }

    @Test fun onlyDownloadAndVerifyAreCancellable() {
        assertEquals(true, AboutState.Downloading(info, 1).cancellable)
        assertEquals(true, AboutState.Verifying(info).cancellable)
        listOf(
            AboutState.Idle, AboutState.Checking, AboutState.Latest, AboutState.Failed(CheckFailure.NETWORK),
            AboutState.Found(info), AboutState.VerifyFailed(info), AboutState.DownloadFailed(info),
            AboutState.NeedsPermission(info), AboutState.Installing(info), AboutState.InstallFailed(info),
        ).forEach { assertEquals(it.toString(), false, it.cancellable) }
    }
}
