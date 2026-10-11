package com.uniteduone.launcher

/** 一次确定键按压的结论:还没有结论 / 按满了长按 / 短按松开。 */
internal enum class OkOutcome { NOTHING, LONG_PRESS, SHORT_PRESS }

/**
 * **编辑页卡片上的确定键**(R165 §2.3):短按松开 = 拿起(拿起中 = 放下),按满 [longPressMs] = 卡片菜单。
 * 按 downTime 认一次按压(同首页移动态 / 旧搬运的 `CarryPresses`):[onDown] 在首个 DOWN(repeatCount 0)记下它,
 * 重复事件按满时长出一次 [OkOutcome.LONG_PRESS] 并记为「按住过」;[onUp] 只有「我们见过它的 DOWN、没按住过、没被取消」
 * 才是 [OkOutcome.SHORT_PRESS]。[owns] = 这一下是不是我们接手的——**接手的那一下,UP 一律吞掉**(哪怕中间开了菜单),
 * 否则 UP 会落到菜单第一颗胶囊上被当成一次点击(Review Focus 1)。不是 Compose 状态:只在按键回调里读写。
 */
internal class OkPress(private val longPressMs: Long = LONG_PRESS_MS) {
    private var down = -1L
    private var held = -1L

    fun owns(downTime: Long): Boolean = downTime == down

    fun onDown(downTime: Long, eventTime: Long, repeatCount: Int): OkOutcome {
        if (repeatCount == 0) {
            down = downTime
            return OkOutcome.NOTHING
        }
        if (downTime != down || held == downTime) return OkOutcome.NOTHING
        if (eventTime - downTime >= longPressMs) {
            held = downTime
            return OkOutcome.LONG_PRESS
        }
        return OkOutcome.NOTHING
    }

    fun onUp(downTime: Long, canceled: Boolean): OkOutcome =
        if (downTime == down && downTime != held && !canceled) OkOutcome.SHORT_PRESS else OkOutcome.NOTHING
}
