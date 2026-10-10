package com.uniteduone.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R167 进编辑页的判据:预读 layout.json 回来时,请求号还是最新的、而且还在前台,才真的进。 */
class EditEntryTest {
    @Test fun latestRequestWhileResumedEnters() = assertTrue(shouldEnterEdit(requested = 5, current = 5, resumed = true, blocked = false))
    /** 预读期间按了 HOME / 离开了编辑页(请求号被加过):不进(Review Focus 3) */
    @Test fun supersededRequestDoesNotEnter() = assertFalse(shouldEnterEdit(requested = 5, current = 6, resumed = true, blocked = false))
    /** 预读期间退到后台:不进 */
    @Test fun backgroundDoesNotEnter() = assertFalse(shouldEnterEdit(requested = 5, current = 5, resumed = false, blocked = false))
    /** 预读期间别的整屏页(选图页 / 关于页 / 应用页 / 输入源页 / 引导)开了:不进,否则编辑页会把那一页吞进自己那一层 */
    @Test fun otherOverlayDoesNotEnter() = assertFalse(shouldEnterEdit(requested = 5, current = 5, resumed = true, blocked = true))

    // R167 报告 #4:编辑页淡入走完(再多等 EDIT_COVER_SLACK_MS)之后首页才停画
    @Test fun homeStillDrawnWhileEditFadesIn() = assertFalse(editCoversHome(editing = true, elapsedMs = GtvLayout.EDIT_SWAP_MS.toLong()))
    @Test fun homeHiddenAfterFadeInPlusSlack() = assertTrue(editCoversHome(editing = true, elapsedMs = GtvLayout.EDIT_SWAP_MS + EDIT_COVER_SLACK_MS))
    /** 退出编辑页当场就画:编辑页残影从 1 开始淡出,底下的首页必须已经在 */
    @Test fun notEditingAlwaysDrawsHome() = assertFalse(editCoversHome(editing = false, elapsedMs = 10_000))
}
