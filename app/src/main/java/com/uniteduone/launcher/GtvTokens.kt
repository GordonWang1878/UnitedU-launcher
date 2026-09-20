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

    /**
     * Ruling R22(终审 2026-09-20,owner 真机走查后补):hero 从左到右的暗色渐变。装机走查
     * 发现壁纸整块没有任何压暗,贴在 [GtvLayout.CONTENT_KEYLINE] 左基准线上的顶栏药丸组与
     * 每一行的行标题,都直接落在壁纸上——亮壁纸下几乎看不清。decision B3(见
     * `docs/research/2026-09-20-gtv-vs-unitedu-comparison.md`)只裁定了「hero 留给壁纸」,
     * 没人决定要不要渐变,照搬时被漏掉了。
     *
     * 数值来自对参考截图 `docs/screenshots/gtv/01-home-default.jpg` 的取样(像素级采样脚本见
     * `docs/WORKLOG.md` 2026-09-20 条目,不是官方量测文档),不是精确曲线:左侧到约 42% 屏宽
     * 仍接近纯黑,42%–88% 屏宽之间线性淡出,88% 之后完全透出壁纸。真机验收如果觉得太陡或太浅,
     * 改这三个数,不要在 HomeScreen.kt 里另开一份字面量。
     */
    val HeroGradientNear: Color = Color.Black.copy(alpha = 0.78f)
    val HeroGradientFar: Color = Color.Black.copy(alpha = 0f)
    /** 渐变在这个屏宽分数之前维持 [HeroGradientNear],不提前淡出。 */
    const val HeroGradientPlateau = 0.42f
    /** 渐变到这个屏宽分数完全淡成 [HeroGradientFar];再往右壁纸完全透出,不再压暗。 */
    const val HeroGradientFadeEnd = 0.88f
}
