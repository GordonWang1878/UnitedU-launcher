package com.uniteduone.launcher

import androidx.compose.ui.graphics.Color

/** gtv 线的颜色常量。数值来自实测取样(报告 §5/§6/§7)。 */
object GtvTokens {
    /** 顶栏药丸组的深灰轨道。 */
    val PillTrack = Color(0xFF282A2C)
    /** 快捷设置面板底(右侧浮出 sheet,spec §6;sheet 本体留给后续任务接线,这里先把 token 备好)。 */
    val PanelBg = Color(0xFF171A1F)
    /** 长按 / 齿轮菜单整屏底(Task 8)。 */
    val MenuBg = Color(0xFF0E0E0F)
    /** 菜单项药丸,未聚焦(Task 8)。聚焦态改用 LocalThemeColors.current.accent,不是固定色。 */
    val MenuItemIdle = Color(0xFF161718)
    /** 顶栏 tab:聚焦但未选中(留给顶栏折叠/tab 任务接线)。 */
    val TabFocusedUnselected = Color(0xFF585E64)
    /** 浮层压暗。真机验收再调。 */
    val ScrimOverlay = Color(0xA6000000)
}
