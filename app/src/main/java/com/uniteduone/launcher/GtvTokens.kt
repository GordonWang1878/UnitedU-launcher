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
    /** 浮层压暗。真机验收再调。 */
    val ScrimOverlay = Color(0xA6000000)
    /**
     * **Ruling R39(2026-09-22,owner 真机反馈 Round 10)**:UnitedU 设置页(`SettingsScreen`,两栏整屏)
     * 底下铺的压暗层——**黑 α 0.55**(0x8C)。owner 原话:「整个版面完全没有背景色,直接悬浮在首页之上,
     * 显得乱;加一层悬浮阴影,不用太深,让用户分清这是在桌面之上悬浮了一层。」B4 裁定浮层压暗照 Google
     * (快捷设置那张 `docs/screenshots/gtv/15-quick-settings-panel.jpg` 是重度压暗、接近 [ScrimOverlay]
     * 的 0.65),owner 说不用太深,取 0.55,另立一个常量不动 [ScrimOverlay]。
     *
     * **取代**此前设置页根节点上那条「左 0.60 → 右 0.25」的水平渐变(spec §3.1 的「右侧实时预览」):
     * 渐变右端只压 25%,底下的卡片行与设置页的文字叠在一起,正是 owner 看到的「乱」;均匀 0.55 之下
     * 首页仍看得见(实时预览还在,只是暗了),但整页读成「桌面之上的一层」。
     * 进出设置页时由 `MainActivity` 用 150 ms tween 淡入淡出(设置页本身没有转场,scrim 单独动)。
     */
    val SettingsScrim = Color(0x8C000000)

    /**
     * Ruling R24(终审 2026-09-21,owner 真机走查 Round 3 后补):2D 背景衰减(取代 R22 的纯横向
     * 渐变 + Round 2 的行位移竖直 scrim 两套机制)。owner 指出 Google 的暗色区域是「右上角一块图,
     * 其余整块黑底」,不是「只从右到左压暗、上下不变」——量参考截图 `docs/screenshots/gtv/01-home-default.jpg`
     * 的 7×8 亮度网格(y 行 × x 列,详见 `docs/WORKLOG.md` 2026-09-21 R24 条目)证实:左列自上而下
     * 恒为 15(近黑);x=88% 处的亮度在 y≤28% 是满强度,y=40% 降到约 0.7×,y=50% 约 0.55×,
     * y=57% 约 0.4×,**y=85% 起整行(所有 x)都是 15–19**——形状是「横向衰减 × 纵向衰减」的**乘积**,
     * 不是单一方向的线性渐变。
     *
     * 实现上仍是两条独立的 1D 渐变(`HomeScreen.kt` 里横向一层、纵向一层,各自纯黑、透明度分别
     * 由本对象的常量决定),而不是一个自定义 2D shader——两层纯黑半透明图层按 Compose 默认的
     * over 合成叠在一起,数学上就是「(1−水平α)×(1−纵向α)」的透光率乘积,与网格量出的形状
     * 完全对应,不需要手写 shader。
     *
     * **HeroGradientNear 现在是 0.96(不再是 R22 的 0.78,也不是 Round 2 A/B 对比里的「A」)**:
     * Round 2 出的 A(0.78)/B(0.96)对比图 `docs/screenshots/gtv-gradient-variants-*.jpg` 里,
     * owner 选择了更接近 Google「近纯黑」读法的 B,Item 4 就此定案,不再是待选项。
     *
     * **owner 反馈 Round 4(2026-09-21)§6:底色从 `Color.Black` 换成 [MenuBg]**:R24 两道衰减
     * 叠满时纯灰壁纸的亮度只压到约 5(128×0.04²);controller 量参考截图左列亮度恒为 15、
     * 长按菜单底 `rgb(14,14,15)`——Google 的「黑」其实是它的 surface 色,亮度 ≈15,不是数学纯黑
     * 0。改用 [MenuBg](`0xFF0E0E0F`,与长按菜单底同一个值)当衰减终值的颜色,透明度不变
     * (仍是 0.96/0),纯灰壁纸的地板亮度因此从 ~5 抬到 ~14–18(两道衰减都没叠满的边缘区域更亮,
     * 都叠满的角落最接近 14——见 `docs/WORKLOG.md` 2026-09-21 Round 4 条目的推导)。
     */
    val HeroGradientNear: Color = MenuBg.copy(alpha = 0.96f)
    val HeroGradientFar: Color = MenuBg.copy(alpha = 0f)
    /** 横向衰减在这个屏宽分数之前维持 [HeroGradientNear],不提前淡出。 */
    const val HeroGradientHPlateau = 0.42f
    /** 横向衰减到这个屏宽分数完全淡成 [HeroGradientFar];再往右壁纸完全透出,不再压暗。 */
    const val HeroGradientHFadeEnd = 0.88f
    /**
     * 纵向衰减(R24 新增)在这个屏高分数之前维持 [HeroGradientFar](全透明,不提前压暗)——
     * 对应网格里 y≤28% 处图像仍是满强度那一段。**固定在屏幕坐标上,不跟着行位移走**
     * (与 Round 2 那版「scrimTop 随 activeRow 变」的行为相反):Google 的暗色窗口本身不随
     * 内容行的焦点滚动而移动,详见 R24 裁定与 `HomeScreen.kt` 里这条渐变 Box 上的注释。
     */
    const val HeroGradientVFadeStart = 0.30f
    /** 纵向衰减到这个屏高分数完全淡成 [HeroGradientNear];再往下维持 Near,不再变化——
     *  对应网格里 y≥57%~85% 这一段迅速转黑、85% 起整行趋近纯黑的读数。 */
    const val HeroGradientVPlateau = 0.66f
}
