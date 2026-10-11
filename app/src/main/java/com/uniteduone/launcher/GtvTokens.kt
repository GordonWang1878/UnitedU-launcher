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
    /** R164:频道海报没图 / 加载失败 / 还没进加载范围时的卡底(深灰,上面写节目标题;照 Google TV 无图卡)。 */
    val PosterFallback = Color(0xFF26282C)
    /** 菜单项药丸,未聚焦(Task 8)。聚焦态改用 LocalThemeColors.current.accent,不是固定色。 */
    val MenuItemIdle = Color(0xFF161718)
    /**
     * [MenuItemIdle] 的半透明版(R144,2026-09-30「视觉高级感」):白 9/255。铺在纯 MenuBg 上与 [MenuItemIdle] 差不到一级,
     * 铺在整屏页的氛围底(R142)上会透出底下的冷暖——未聚焦的胶囊、图标格、输入框、信息块不再是一块块中性灰。
     * [MenuItemIdle] 本身留着给对比度单测当「最坏的底」用。
     */
    val SurfaceIdle = Color(0x09FFFFFF)
    /** 「＋」格与缩略图占位的底,半透明版(R144):白 39/255,在纯 MenuBg 上 ≈ #333333(原 ThumbPlaceholderBackground)。 */
    val SurfacePlaceholder = Color(0x27FFFFFF)
    /** **Ruling R46(2026-09-22)**:首页行标题的两态色——Google 浏览态实测(`#46` 帧,1 dp = 1 px):
     *  焦点行标题近白(字芯峰值 ≈ RGB(230,243,255)),其余行灰(字芯峰值 ≈ RGB(145,152,160))。
     *  **不随主题 accent 变**(R43 用 accent + alpha 0.7,owner 看后否决)。取值方法:我们的模拟器截图
     *  按 BOX 缩到同尺度 960×540 后,字芯峰值与 Google 帧对齐(先试 0xFFBDC1C6,峰值 ≈ 215 明显偏亮,
     *  0xFF9AA0A6 仍略亮,定 0xFF959BA3);对照图
     *  见 `docs/screenshots/gtv-r46-vs-google-row-title.jpg`。
     *
     *  **Ruling R48(2026-09-22)**:首页不再画行标题,这两色改给**行图标**当焦点提示(焦点行近白、
     *  其余行灰),改名 `RowTitleFocused`/`RowTitleIdle` → 现名,数值不变。 */
    val RowIconFocused = Color(0xFFE6F2FD)
    val RowIconIdle = Color(0xFF959BA3)
    /** 浮层压暗。真机验收再调。 */
    val ScrimOverlay = Color(0xA6000000)
    // (R39 的 SettingsScrim 随两栏设置页一起删掉:R69 / R73 的设置外壳自己铺不透明 MenuBg。)

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
    /** R84:首页从上往下加速压暗到黑(试做)。 */
    const val HOME_FADE_ENABLED = true
    /**
     * R85(2026-09-27 owner 在自己电视壁纸的 6 种模拟里选定,取代 R84 的 t³):三段曲线——
     * 屏高 [HOME_FADE_START] 以上完全不压暗;从那里到卡片行附近 [HOME_FADE_KNEE] 按 2 次方加速压到
     * [HOME_FADE_KNEE_ALPHA];再往下线性压到底边 1.0(全黑)。R84 的 t³ 在 owner 那张下半部本来就暗的壁纸上看不出来。
     */
    const val HOME_FADE_START = 0.30f
    const val HOME_FADE_KNEE = 0.80f
    const val HOME_FADE_KNEE_ALPHA = 0.85f
    /** 逼近曲线用的等分 stop 数(相邻 stop 之间线性插值);20 等分让 0.30 / 0.80 两个拐点正好落在 stop 上。 */
    const val HOME_FADE_STOPS = 20
    /** 屏高分数 t(0 = 顶,1 = 底)处的压暗透明度。 */
    fun homeFadeAlpha(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when {
            x <= HOME_FADE_START -> 0f
            x <= HOME_FADE_KNEE -> {
                val u = (x - HOME_FADE_START) / (HOME_FADE_KNEE - HOME_FADE_START)
                u * u * HOME_FADE_KNEE_ALPHA
            }
            else -> HOME_FADE_KNEE_ALPHA + (x - HOME_FADE_KNEE) / (1f - HOME_FADE_KNEE) * (1f - HOME_FADE_KNEE_ALPHA)
        }
    }

    /** R82:首页背景衰减(下面这组 HeroGradient*)的总开关。2026-09-24 owner 暂时拿掉,等新策略。 */
    const val HERO_GRADIENT_ENABLED = false
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
