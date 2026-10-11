package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * owner 裁定(2026-10-09):授权申请回来是拒绝时,只有「永久拒绝」(系统这次没弹窗)才自动跳系统设置;
 * 用户在窗里点了拒绝、或按返回 / 主页键关掉窗,都留在原地。判据 = 申请前后两次 shouldShowRequestPermissionRationale。
 */
class ChannelPermissionTest {
    private val G = PermissionResult.GRANTED
    private val D = PermissionResult.DENIED
    private val P = PermissionResult.DENIED_PERMANENTLY

    @Test fun grantedWinsWhateverTheRationale() {
        assertEquals(G, permissionResult(granted = true, rationaleBefore = false, rationaleAfter = false, deniedBefore = true))
        assertEquals(G, permissionResult(true, true, false, false))
    }

    @Test fun theDialogWasShownSoStay() {
        assertEquals("第一次点拒绝:申请后 rationale 变真", D, permissionResult(false, false, true, false))
        assertEquals("拒绝过一次、这次按返回关窗:前后都真", D, permissionResult(false, true, true, true))
        assertEquals("第二次点拒绝(弹了窗,Android 11+ 此后不再询问):前真后假", D, permissionResult(false, true, false, true))
    }

    @Test fun neverDeniedAndDismissedIsNotPermanent() {
        // 从没拒绝过时按返回 / 主页键关掉窗,前后都是 false,与「系统没弹窗」长得一样——靠 deniedBefore 分开
        assertEquals(D, permissionResult(false, false, false, deniedBefore = false))
    }

    @Test fun deniedBeforeAndNoDialogIsPermanent() {
        assertEquals(P, permissionResult(false, false, false, deniedBefore = true))
    }
}
