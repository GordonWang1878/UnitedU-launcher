# M8 首页视觉重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把首页换成「壁纸 hero + 大字时钟 + 锚定在下三分之一的 Material 卡片行 + 右上 pill 组」,几何、焦点、动效、色阶照搬 tv-material 1.0.0 默认值,字族保留 DM Sans。

**Architecture:** 新增纯 Kotlin `HomeLayout`(全部首页几何,dp 用 Float,可 JVM 单测),`Theme.cardMetrics` 退化为它的 Dp 包装;`AppCard` 换成 tv-material `Card`(放大 / 描边 / 动效由库出),自己只画横幅与回落底;`HomeScreen` 的纵向位移从「溢出才上移」改为「焦点行锚定」,scrim 与 hero 时钟随之;右上角齿轮 + 小时钟换成 `TopPills`(两个 `IconButton`)。焦点账本、看门狗、长按识别(`MainActivity.dispatchKeyEvent`)**一律不动**。

**Tech Stack:** Kotlin, Jetpack Compose(BOM 2024.10.01 = 1.7.x), `androidx.tv:tv-material:1.0.0`(新增), JUnit 4, Android 14 TV AVD `unitedu-tv`.

**Spec:** `docs/superpowers/specs/2026-09-17-m8-home-visual-refresh-design.md`(§0 决策表是唯一权威;本 plan 的数字全部来自它)

**基线:** main `39ec10c`(M7 已并入)。工作分支 `m8-visual`,worktree `.claude/worktrees/m8-visual`(M7 惯例)。

## Global Constraints

- **铁律 1–7**(项目 `CLAUDE.md`)全程有效:不用任何可滚动容器(含 tv-material 的 `ImmersiveList` / `Carousel` / `TvLazyRow`);焦点落地只信控件自报;看门狗与浮层让路逻辑不改;守卫与 key 成对;不写一次性布尔闩。
- **tv-material 只用叶子组件与 token**:`Card`、`IconButton`、`Icon`、`MaterialTheme` / `darkColorScheme` / `Typography`。版本**钉 1.0.0**(1.1.0 要 Compose 1.10)。首次构建需联网。
- **长按不动**:`MainActivity.dispatchKeyEvent` 的 `LONG_PRESS_MS = 600L` 分支一字不改;`Card` 传 `onLongClick = null`。
- **字族**:所有文字 `fontFamily = Theme.Sans`(DM Sans);字号照 tv-material `Typography` 默认(titleMedium 16sp / bodySmall 12sp / labelSmall 11sp),大字时钟 84sp、日期 24sp。
- **几何**(dp,960×540 基准):边距 58、卡间距 20、行间距 20、圆角 8、锚点 = 屏高 × 2/3、三档卡宽 (844 − 20×(N−1))/N。
- **颜色**:中性色阶用 `MaterialTheme.colorScheme`(dark 默认);accent 只落行标题、行图标、大字时钟、pill 图标;卡片容器 / 描边 / scrim 不染 accent(M7 §10.5 不变量延续)。
- **命令**:构建 `source scripts/env.sh && gradle --no-daemon assembleRelease`;单测 `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest`;模拟器操作按项目 `CLAUDE.md`「模拟器」一节(`am start -n` 拉起,不用 HOME 键;`Default Home` 对话框按两次 BACK)。
- **提交**:每个任务结束一个 commit,信息用英文 conventional 前缀(`feat(m8): …` / `refactor(m8): …` / `docs(m8): …`),结尾加 `Co-Authored-By: Claude <模型名> <noreply@anthropic.com>`(写实际作者模型;2026-09-18 R8)。
- **二级界面不动**(设置页、编辑页、选择器、菜单、对话框):只允许改它们对已删常量的引用,不改观感。

---

### Task 1: tv-material 依赖 + 主题壳 `UnitedUTheme`

**Files:**
- Modify: `app/build.gradle.kts:65,104-122`
- Create: `app/src/main/java/com/uniteduone/launcher/UnitedUTheme.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt:389`(`CompositionLocalProvider(LocalThemeColors provides themeColors) {` 所在行)

**Interfaces:**
- Produces: `@Composable fun UnitedUTheme(colors: ThemeColors, content: @Composable () -> Unit)` —— 后续所有任务里 `MaterialTheme.colorScheme` / `MaterialTheme.typography` 都由它提供;`colorScheme.primary == colors.accent`。

- [ ] **Step 1: 加依赖、改注释**

`app/build.gradle.kts` 第 65 行的注释
```kotlin
    // 刻意保持最小依赖:不引 tv-material / material3,焦点与动画用标准 Compose 自己控。
```
改为
```kotlin
    // tv-material 只用叶子组件与 token(Card / IconButton / MaterialTheme);滚动容器一律不用(铁律 1)。
    // 钉 1.0.0:1.1.0 依赖 Compose 1.10,超出本 BOM。M8 2026-09-17。
```
在 `implementation("androidx.compose.foundation:foundation")` 之后加一行:
```kotlin
    implementation("androidx.tv:tv-material:1.0.0")
```

- [ ] **Step 2: 写主题壳**

`UnitedUTheme.kt`:
```kotlin
package com.uniteduone.launcher

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * M8:tv-material 的主题壳。中性色阶 = 库的 dark 默认(surface #1C1B1F、surfaceVariant #49454F、
 * border #938F99、onSurface #E6E1E5……),primary = 当前预设 / 壁纸取色的 accent;
 * 字阶数值 = 库默认(15 档),字族全部换成 DM Sans(spec §0「字体」决策)。
 * 只在 MainActivity 顶层用一次,包在 LocalThemeColors 外面。
 */
@Composable
fun UnitedUTheme(colors: ThemeColors, content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.dm(): TextStyle = copy(fontFamily = Theme.Sans)
    val typography = Typography(
        displayLarge = base.displayLarge.dm(),
        displayMedium = base.displayMedium.dm(),
        displaySmall = base.displaySmall.dm(),
        headlineLarge = base.headlineLarge.dm(),
        headlineMedium = base.headlineMedium.dm(),
        headlineSmall = base.headlineSmall.dm(),
        titleLarge = base.titleLarge.dm(),
        titleMedium = base.titleMedium.dm(),
        titleSmall = base.titleSmall.dm(),
        bodyLarge = base.bodyLarge.dm(),
        bodyMedium = base.bodyMedium.dm(),
        bodySmall = base.bodySmall.dm(),
        labelLarge = base.labelLarge.dm(),
        labelMedium = base.labelMedium.dm(),
        labelSmall = base.labelSmall.dm(),
    )
    MaterialTheme(
        colorScheme = darkColorScheme(primary = colors.accent),
        typography = typography,
        content = content,
    )
}
```

- [ ] **Step 3: MainActivity 顶层包一层**

`MainActivity.kt` 里
```kotlin
            CompositionLocalProvider(LocalThemeColors provides themeColors) {
```
改为
```kotlin
            UnitedUTheme(themeColors) {
            CompositionLocalProvider(LocalThemeColors provides themeColors) {
```
并在这个 `CompositionLocalProvider` 块的配对右花括号之后再补一个 `}`(用编译器定位:少一个括号编译会在 setContent 结尾报错)。

- [ ] **Step 4: 构建 + 模拟器零回归**

Run: `source scripts/env.sh && gradle --no-daemon assembleRelease`
Expected: BUILD SUCCESSFUL(首次会联网下载 androidx.tv)。
然后按 CLAUDE.md 装到模拟器、`am start -n` 拉起、截图 `/tmp/m8-t1.png`。Expected: 与改前首页肉眼一致(壳不换任何组件)。

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/uniteduone/launcher/UnitedUTheme.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt
git commit -m "feat(m8): add tv-material 1.0.0 and UnitedUTheme shell (dark scheme, primary = accent, DM Sans typography)"
```

---

### Task 2: `HomeLayout` 纯几何(TDD)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/HomeLayout.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/HomeLayoutTest.kt`

**Interfaces:**
- Produces(全部 `Float` dp,无 Compose 类型):
  - `HomeLayout.SIDE_PADDING = 58f`, `CARD_SPACING = 20f`, `CARD_CORNER = 8f`, `FOCUS_SCALE = 1.1f`, `FOCUS_BORDER = 3f`, `ROW_GAP = 20f`, `ROW_TITLE_LINE = 24f`, `ROW_TITLE_GAP = 8f`, `CARD_TITLE_GAP = 4f`, `CARD_TITLE_LINE = 16f`, `SCRIM_LEAD = 60f`, `HERO_TOP = 150f`, `PILL_TOP = 28f`
  - `fun span(screenW: Float = 960f): Float`
  - `fun cardWidth(cardsPerRow: Int, screenW: Float = 960f): Float`
  - `fun cardHeight(cardsPerRow: Int, screenW: Float = 960f): Float`
  - `fun rowVerticalPad(cardsPerRow: Int, screenW: Float = 960f): Float`
  - `fun titleHeight(showTitles: Boolean): Float`
  - `fun rowPitch(cardsPerRow: Int, showTitles: Boolean, screenW: Float = 960f): Float`
  - `fun shift(activeRow: Int, cardsPerRow: Int, showTitles: Boolean, screenW: Float = 960f): Float`
  - `fun heroAlpha(activeRow: Int): Float`
  - `fun anchorTop(screenH: Float): Float`
  - `fun scrimHeight(screenH: Float): Float`

- [ ] **Step 1: 写失败的测试**

`HomeLayoutTest.kt`:
```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    private fun eq(expected: Float, actual: Float) = assertEquals(expected, actual, 0.01f)

    @Test fun cardWidthsFollowGoogleGrid() {
        // spec §1.1:(844 − 20×(N−1)) / N
        eq(152.8f, HomeLayout.cardWidth(5))
        eq(124f, HomeLayout.cardWidth(6))
        eq(88f, HomeLayout.cardWidth(8))
    }

    @Test fun cardHeightsAre16x9() {
        eq(85.95f, HomeLayout.cardHeight(5))
        eq(69.75f, HomeLayout.cardHeight(6))
        eq(49.5f, HomeLayout.cardHeight(8))
    }

    @Test fun sixCardsFillTheSpanExactly() {
        eq(844f, HomeLayout.cardWidth(6) * 6 + HomeLayout.CARD_SPACING * 5)
        eq(844f, HomeLayout.span())
    }

    @Test fun rowVerticalPadCoversFocusGrowthPlusBorder() {
        // 放大 10% 的溢出一半 + 3dp 描边
        eq(69.75f * 0.05f + 3f, HomeLayout.rowVerticalPad(6))
    }

    @Test fun rowPitchIsTitleGapPadsCardTitleAndGap() {
        val pad = 69.75f * 0.05f + 3f
        eq(24f + 8f + 2f * pad + 69.75f + 20f, HomeLayout.rowPitch(6, showTitles = false))
        eq(HomeLayout.rowPitch(6, false) + 20f, HomeLayout.rowPitch(6, showTitles = true))
    }

    @Test fun shiftAnchorsTheFocusedRow() {
        val pitch = HomeLayout.rowPitch(6, false)
        eq(0f, HomeLayout.shift(0, 6, false))
        eq(-pitch, HomeLayout.shift(1, 6, false))
        eq(-2f * pitch, HomeLayout.shift(2, 6, false))
        eq(0f, HomeLayout.shift(-1, 6, false))   // 越界夹到第 0 行
    }

    @Test fun heroHiddenFromSecondRowOn() {
        eq(1f, HomeLayout.heroAlpha(0))
        eq(0f, HomeLayout.heroAlpha(1))
        eq(0f, HomeLayout.heroAlpha(4))
    }

    @Test fun anchorIsTwoThirdsAndScrimReachesSixtyAbove() {
        eq(360f, HomeLayout.anchorTop(540f))
        eq(240f, HomeLayout.scrimHeight(540f))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.HomeLayoutTest'`
Expected: 编译失败,`Unresolved reference: HomeLayout`。

- [ ] **Step 3: 实现**

`HomeLayout.kt`:
```kotlin
package com.uniteduone.launcher

/**
 * M8 首页几何,单位一律 dp(Float),**不含任何 Compose 类型**,便于 JVM 单测。
 * 数值出处:spec §1.1(Google 网格:边距 58、间距 20、圆角 8、1.1 倍、3dp 描边)与 §2.1(锚点 = 屏高 × 2/3)。
 * `Theme.cardMetrics` 只是这里的 Dp 包装;HomeScreen 的纵向位移直接读这里。
 */
object HomeLayout {
    const val SIDE_PADDING = 58f
    const val CARD_SPACING = 20f
    const val CARD_CORNER = 8f
    const val FOCUS_SCALE = 1.1f
    const val FOCUS_BORDER = 3f
    const val ROW_GAP = 20f
    /** 行标题行高 = titleMedium 16sp 的行高 24。 */
    const val ROW_TITLE_LINE = 24f
    const val ROW_TITLE_GAP = 8f
    /** 卡片标题(开关开时):卡底到文字 4、行高 = bodySmall 12sp 的行高 16。 */
    const val CARD_TITLE_GAP = 4f
    const val CARD_TITLE_LINE = 16f
    /** scrim 顶边在锚点上方多少。 */
    const val SCRIM_LEAD = 60f
    /** hero 大字时钟块顶。 */
    const val HERO_TOP = 150f
    const val PILL_TOP = 28f

    fun span(screenW: Float = 960f): Float = screenW - 2f * SIDE_PADDING

    fun cardWidth(cardsPerRow: Int, screenW: Float = 960f): Float =
        (span(screenW) - CARD_SPACING * (cardsPerRow - 1)) / cardsPerRow

    fun cardHeight(cardsPerRow: Int, screenW: Float = 960f): Float = cardWidth(cardsPerRow, screenW) * 9f / 16f

    /** 行内上下留白:放大 10% 的溢出一半 + 描边,刚好放得下聚焦卡。 */
    fun rowVerticalPad(cardsPerRow: Int, screenW: Float = 960f): Float =
        cardHeight(cardsPerRow, screenW) * (FOCUS_SCALE - 1f) / 2f + FOCUS_BORDER

    fun titleHeight(showTitles: Boolean): Float = if (showTitles) CARD_TITLE_GAP + CARD_TITLE_LINE else 0f

    /** 行标题顶到下一行行标题顶。 */
    fun rowPitch(cardsPerRow: Int, showTitles: Boolean, screenW: Float = 960f): Float =
        ROW_TITLE_LINE + ROW_TITLE_GAP + 2f * rowVerticalPad(cardsPerRow, screenW) +
            cardHeight(cardsPerRow, screenW) + titleHeight(showTitles) + ROW_GAP

    /** 焦点行锚定:内容整块上移 activeRow 个行距(spec §2.2)。 */
    fun shift(activeRow: Int, cardsPerRow: Int, showTitles: Boolean, screenW: Float = 960f): Float =
        -activeRow.coerceAtLeast(0) * rowPitch(cardsPerRow, showTitles, screenW)

    /** hero 主体第 1 行起隐藏(spec §2.3)。 */
    fun heroAlpha(activeRow: Int): Float = if (activeRow <= 0) 1f else 0f

    fun anchorTop(screenH: Float): Float = screenH * 2f / 3f

    fun scrimHeight(screenH: Float): Float = screenH - anchorTop(screenH) + SCRIM_LEAD
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.HomeLayoutTest'`
Expected: 8 tests PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/HomeLayout.kt app/src/test/java/com/uniteduone/launcher/HomeLayoutTest.kt
git commit -m "feat(m8): HomeLayout pure geometry (Google grid, anchored rows) with unit tests"
```

---

### Task 3: `AppCard` 换成 tv-material `Card`

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/AppCard.kt`(整个文件重写)

**Interfaces:**
- Consumes: `CardMetrics.cardWidth / cardHeight / cardCorner / titleGap / titleLine / titleSize`(Task 4 之前仍是旧值,本任务不关心数值)。
- Produces: `AppCard(...)` 签名**不变**(HomeScreen / EditScreen 的调用点零改动)。

- [ ] **Step 1: 重写 AppCard.kt**

```kotlin
package com.uniteduone.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme

/**
 * 16:9 卡片(M8:tv-material `Card`)。放大 1.1、3dp `colorScheme.border` 描边、进 300 / 出 500 / 按下 120ms
 * 全是库默认(spec §1.2 ★),这里不再自己画光晕 / 投影 / 缩放。
 * 焦点上报仍挂在传给 Card 的 modifier 上:它排在库内部 `focusable` 之前,能观察到同一个焦点目标(铁律 2 / 4)。
 * 长按由 MainActivity.dispatchKeyEvent 按 600ms 判(M4),所以 `onLongClick = null`;
 * Activity 吞掉重复事件后库只看到「短按 DOWN → UP」= 点击。
 */
@Composable
fun AppCard(
    app: AppEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 当前卡片档位的尺寸。默认中档。 */
    metrics: CardMetrics = Theme.cardMetrics(6),
    onFocusChange: (Boolean) -> Unit = {},
    /** 行首/行末:到边界后左右键不再跳到别的行。 */
    isRowStart: Boolean = false,
    isRowEnd: Boolean = false,
    /** 末行:下面没有任何可聚焦节点,不锁的话按「下」焦点会整棵树消失。 */
    isLastRow: Boolean = false,
    /** 首行锁「上」(编辑页用;首页上方有 pill 组接住,默认 false)。 */
    isFirstRow: Boolean = false,
    /** 上下移动的显式落点:相邻行「记住的那一格」。 */
    upTarget: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    /** 卡片下方一行小字;null = 不显示。 */
    title: String? = null,
    /** 无横幅回落卡的底色(图标边缘色);null 或有横幅时不铺。 */
    fallbackColor: Color? = null,
    /** 主题化卡片:去色→染 accent。 */
    themed: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    val scheme = MaterialTheme.colorScheme
    val cardTint = if (themed) ColorFilter.colorMatrix(ColorMatrix(cardTintMatrix(accent.toArgb() and 0xFFFFFF))) else null
    // 容器色:有图的卡透明(横幅铺满,库的 clip 裁圆角);主题化统一铺深 accent 底;图标回落卡铺边缘色;
    // 连图都没有(文字回落)用库的 surfaceVariant #49454F。accent 不进卡片中间(M7 §10.5)——只有主题化开关是用户主动要的例外。
    val container = when {
        themed && app.card != null -> accent.copy(alpha = 0.20f)
        fallbackColor != null && app.card != null && !app.isWide -> fallbackColor
        app.card != null -> Color.Transparent
        else -> scheme.surfaceVariant
    }
    val shape = RoundedCornerShape(metrics.cardCorner)
    Column(
        // 聚焦卡浮到邻居上面(3dp 描边不被右邻居盖住),标题一起放大——与库的 graphicsLayer 缩放同一个节点树
        modifier = Modifier.zIndex(if (focused) 1f else 0f).width(metrics.cardWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            onClick = onClick,
            onLongClick = null,
            modifier = modifier
                .size(metrics.cardWidth, metrics.cardHeight)
                .focusProperties {
                    if (isRowStart) left = FocusRequester.Cancel
                    if (isRowEnd) right = FocusRequester.Cancel
                    if (isLastRow) down = FocusRequester.Cancel else downTarget?.let { down = it }
                    if (isFirstRow) up = FocusRequester.Cancel else upTarget?.let { up = it }
                }
                .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) },
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(
                containerColor = container,
                contentColor = scheme.onSurface,
                focusedContainerColor = container,
                pressedContainerColor = container,
            ),
            // scale / border / glow 用库默认:1.1、3dp colorScheme.border、Glow.None
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val bmp = app.card
                if (bmp != null && app.isWide) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = app.label,
                        contentScale = ContentScale.Fit,
                        colorFilter = cardTint,
                        modifier = Modifier.size(metrics.cardWidth, metrics.cardHeight),
                    )
                } else if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = app.label,
                        colorFilter = cardTint,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(metrics.cardHeight),
                    )
                } else {
                    BasicText(
                        text = app.label,
                        style = TextStyle(
                            fontFamily = Theme.Sans, color = scheme.onSurface,
                            fontSize = 15.sp, textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }
        if (title != null) {
            // 库的 CardDefaults.SubtitleAlpha = 0.6,字号 bodySmall 12sp(metrics.titleSize)
            BasicText(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    color = scheme.onSurface.copy(alpha = 0.6f),
                    fontSize = metrics.titleSize,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.padding(top = metrics.titleGap).width(metrics.cardWidth).height(metrics.titleLine),
            )
        }
    }
}
```

- [ ] **Step 2: 删掉 Theme.kt 里只被旧 AppCard 用的常量**

`Theme.kt` 删除:`FocusScale`、`GlowRadius`、`GlowPeriodMs`(含注释)、`ShadowRadius`、`ShadowDx`、`ShadowDy`、`ShadowColor`(含那段注释)。`CardMetrics.glowRadius` 字段与 `cardMetrics` 里两处 `glowRadius = …` 一并删。

- [ ] **Step 3: 构建 + 模拟器看焦点**

Run: `source scripts/env.sh && gradle --no-daemon assembleRelease`
Expected: BUILD SUCCESSFUL,且 `grep -rn "GlowRadius\|FocusScale\|Shadow" app/src/main/java` 只剩 0 处。
装到模拟器,截图聚焦卡:Expected 放大 1.1(卡宽 255px → 约 281px)、一圈 6px #938F99 描边、无光晕、无投影;按右键一次:放大动画约 0.3s、旧卡回缩约 0.5s(`settings put global animator_duration_scale 10` 放慢看,测完 `settings delete global animator_duration_scale`)。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/AppCard.kt app/src/main/java/com/uniteduone/launcher/Theme.kt
git commit -m "feat(m8): AppCard on tv-material Card — library scale/border/motion, no glow or shadow"
```

---

### Task 4: 首页几何切到 `HomeLayout`(锚定行、scrim、删压暗)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Theme.kt`(`CardMetrics`、`cardMetrics`、常量)
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt:274-286, 398-484, 577-693`
- Modify: `app/src/main/java/com/uniteduone/launcher/EditScreen.kt:296-297, 320`

**Interfaces:**
- Consumes: `HomeLayout.*`(Task 2)。
- Produces:
  - `data class CardMetrics(cardWidth: Dp, cardHeight: Dp, cardCorner: Dp, cardSpacing: Dp, rowVerticalPad: Dp, rowPitch: Dp, titleHeight: Dp, titleGap: Dp, titleLine: Dp, titleSize: TextUnit)`
  - `Theme.cardMetrics(cardsPerRow: Int, showTitles: Boolean = false): CardMetrics`
  - `Theme.SidePadding = 58.dp`、`Theme.MotionEasing: Easing`、`Theme.MotionInMs = 300`、`Theme.EditRowSpacing = 25.4.dp`、`Theme.EditRowTitleGap = 2.3.dp`(编辑页专用,M7/M8 后续换皮时删)
  - HomeScreen 内部状态 `heroAlpha: Float`(Task 6 的 HeroClock 读它)

- [ ] **Step 1: Theme.kt——CardMetrics 与 cardMetrics 改成 HomeLayout 的 Dp 包装**

替换 `CardMetrics` 定义与整个 `cardMetrics` 函数(含其 KDoc)为:
```kotlin
/** 一档卡片布局的尺寸(Dp)。全部由 [HomeLayout] 推导,这里只做单位包装,不要在这里写任何数字。 */
data class CardMetrics(
    val cardWidth: Dp,
    val cardHeight: Dp,
    val cardCorner: Dp,
    val cardSpacing: Dp,
    /** 行内上下留白:放大 10% 的溢出一半 + 描边。 */
    val rowVerticalPad: Dp,
    /** 行标题顶到下一行行标题顶。 */
    val rowPitch: Dp,
    /** 标题行占的高度(开关关 = 0),已计入 rowPitch。 */
    val titleHeight: Dp,
    val titleGap: Dp,
    val titleLine: Dp,
    val titleSize: TextUnit,
)
```
`object Theme` 里:
```kotlin
    /** 每行张数 → 一档尺寸。三档同一公式(spec §1.1),不再有「中档零回归锚点」;非法值按中档 6。 */
    fun cardMetrics(cardsPerRow: Int, showTitles: Boolean = false): CardMetrics {
        val n = if (cardsPerRow in VALID_CARDS_PER_ROW) cardsPerRow else 6
        return CardMetrics(
            cardWidth = HomeLayout.cardWidth(n).dp,
            cardHeight = HomeLayout.cardHeight(n).dp,
            cardCorner = HomeLayout.CARD_CORNER.dp,
            cardSpacing = HomeLayout.CARD_SPACING.dp,
            rowVerticalPad = HomeLayout.rowVerticalPad(n).dp,
            rowPitch = HomeLayout.rowPitch(n, showTitles).dp,
            titleHeight = HomeLayout.titleHeight(showTitles).dp,
            titleGap = HomeLayout.CARD_TITLE_GAP.dp,
            titleLine = HomeLayout.CARD_TITLE_LINE.dp,
            titleSize = 12.sp,   // bodySmall
        )
    }

    val SidePadding = HomeLayout.SIDE_PADDING.dp
    /** 焦点 / 位移动效:tv-material SurfaceScaleTokens 同一条减速曲线与进焦时长。 */
    val MotionEasing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.2f, 1f)
    const val MotionInMs = 300
    /** 编辑页专用(观感不动,M8 不碰二级界面);随二级界面换皮时删。 */
    val EditRowSpacing = 25.4.dp
    val EditRowTitleGap = 2.3.dp
```
删除:`CardWidth`、`CardHeight`、`CardCorner`、`CardSpacing`、`RowSpacing`、`RowTitleGap`、`CardTitleGap`、`CardTitleLine`、`CardTitleSize`、`TopPadding`、`RowVerticalPad`、`InactiveRowAlpha`、`FirstCardTop`、`RowPitch`、`BottomKeepout` 以及它们上面那两大段「v4 截图像素级实测」注释。`Champagne` / `ChampagneGold` / `RowTitle` / chrome palette / `IdleAfterMs` / `Screensaver*` / `WallpaperCrossfadeMs` / `Sans` 保留。

- [ ] **Step 2: EditScreen 改引用(只改引用,不改观感)**

`EditScreen.kt`:`Theme.RowSpacing` → `Theme.EditRowSpacing`;`Theme.RowTitleGap` → `Theme.EditRowTitleGap`;第 320 行 `top = Theme.RowVerticalPad, bottom = Theme.RowVerticalPad` → `top = metrics.rowVerticalPad, bottom = metrics.rowVerticalPad`。

- [ ] **Step 3: HomeScreen——纵向位移改锚定**

把第 274–286 行(从 `// 垂直位置自己算` 到 `shift` 的 `animateDpAsState` 结束)替换为:
```kotlin
    // 垂直位置自己算,不用 verticalScroll(铁律 1)。M8:焦点行**锚定**在下三分之一(spec §2.2)——
    // 内容整块上移 activeRow 个行距,第 0 行时 hero 完整;不再是「溢出才上移」。
    val screenH = LocalConfiguration.current.screenHeightDp.toFloat()
    val activeRowSafe = activeRow.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val anchorTop = HomeLayout.anchorTop(screenH).dp
    val shift by animateDpAsState(
        targetValue = HomeLayout.shift(activeRowSafe, cardsPerRow, showTitles).dp,
        animationSpec = tween(Theme.MotionInMs, easing = Theme.MotionEasing),
        label = "rowShift",
    )
    // hero 主体第 1 行起淡出(spec §2.3);待机时无条件回到 1(spec §2.4)——Task 6 的 HeroClock 读它。
    val heroAlpha by animateFloatAsState(
        targetValue = if (idle || demoIdle != null) 1f else HomeLayout.heroAlpha(activeRowSafe),
        animationSpec = tween(Theme.MotionInMs, easing = Theme.MotionEasing),
        label = "heroAlpha",
    )
```

- [ ] **Step 4: HomeScreen——scrim + 行区 Column**

在第 423 行 `Column(` 之前插入 scrim(`Box(Modifier.fillMaxSize())` 的第一个子节点之后、`Column` 之前):
```kotlin
        // scrim(spec §2.1):#1C1B1F α0 → α0.8;顶边 = 锚点上方 60dp 再加 shift,底边固定屏底——行往上推时它变高,
        // 下方新露出的行始终在暗层里。待机时随内容一起淡出。
        val scrimTop = anchorTop - HomeLayout.SCRIM_LEAD.dp + shift
        val surface = androidx.tv.material3.MaterialTheme.colorScheme.surface
        Box(
            Modifier
                .fillMaxWidth()
                .offset(y = scrimTop)
                .height((screenH.dp - scrimTop).coerceAtLeast(0.dp))
                .alpha(contentAlpha)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to surface.copy(alpha = 0f), 1f to surface.copy(alpha = 0.8f),
                    ),
                ),
        )
```
行区 `Column` 的 modifier 里 `.padding(top = Theme.TopPadding)` → `.padding(top = anchorTop)`;`verticalArrangement = Arrangement.spacedBy(Theme.RowSpacing)` → `Arrangement.spacedBy(HomeLayout.ROW_GAP.dp)`。
空桌面提示的 `color = Theme.RowTitle.copy(alpha = 0.75f)` → `color = androidx.tv.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)`。
`CategoryRow(...)` 调用里删掉 `active = rowIndex == activeRowSafe,` 这一行。

- [ ] **Step 5: HomeScreen——CategoryRow 内部**

`CategoryRow` 签名删掉 `active: Boolean,`;函数体里删除 `rowAlpha` 的 `animateFloatAsState` 与 `Modifier.alpha(rowAlpha)`(邻行压暗停用,spec §0)。外层 `Column` 改为:
```kotlin
    Column(verticalArrangement = Arrangement.spacedBy(HomeLayout.ROW_TITLE_GAP.dp)) {
        Row(
            modifier = Modifier.padding(start = Theme.SidePadding).height(HomeLayout.ROW_TITLE_LINE.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RowIcon(row.name, row.kind, tint = accent)
            BasicText(
                text = row.name,
                // 行标题 = titleMedium 16sp Medium(spec §1.4),颜色 accent(spec §0「accent 落点」)
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium.copy(color = accent),
            )
        }
```
卡片那个 `Row` 的 `.padding(start = Theme.SidePadding, top = Theme.RowVerticalPad, bottom = Theme.RowVerticalPad)` → `.padding(start = Theme.SidePadding, top = metrics.rowVerticalPad, bottom = metrics.rowVerticalPad)`。
`xShift` 的 `animateDpAsState` 补 `animationSpec = tween(Theme.MotionInMs, easing = Theme.MotionEasing),`。

- [ ] **Step 6: 构建 + 单测 + 模拟器量位置**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: 全绿;`grep -rn "TopPadding\|RowSpacing\b\|RowVerticalPad\|InactiveRowAlpha\|FirstCardTop\|BottomKeepout\|RowPitch\b" app/src/main/java` 为 0 处(`EditRowSpacing` 不算)。
模拟器(默认设置:3 行、中档):截图 `/tmp/m8-t4-row0.png`;按「下」两次各截一张。用下面脚本量第一行行标题的顶边与聚焦卡左边缘:
```bash
python3 - <<'PY'
from PIL import Image
im = Image.open('/tmp/m8-t4-row0.png').convert('RGB')
# 行标题顶:x=250 这一列从 y=600 往下第一处亮像素(标题文字为 accent 色,亮度 > 90)
col = [sum(im.getpixel((250, y)))/3 for y in range(600, 1080)]
print('title top y =', 600 + next(i for i, v in enumerate(col) if v > 90))
# 聚焦卡左边缘:y=830 这一行从 x=60 往右第一处描边灰 (#938F99 附近)
row = [im.getpixel((x, 830)) for x in range(60, 400)]
print('card left x =', 60 + next(i for i, p in enumerate(row) if abs(p[0]-0x93) < 25 and abs(p[2]-0x99) < 25))
PY
```
Expected:`title top y` ≈ 720 ± 4(锚点 360dp × 2);`card left x` ≈ 116 − 0.05×248 ≈ 104 ± 4(边距 116px,减放大 1.1 的单侧溢出 12.4px;描边画在卡片轮廓内侧);按「下」后第二张截图里第二行标题顶同样 ≈ 720,第一行被推入上方 scrim 区;回到第一行后复原。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/Theme.kt app/src/main/java/com/uniteduone/launcher/HomeScreen.kt app/src/main/java/com/uniteduone/launcher/EditScreen.kt
git commit -m "feat(m8): anchored rows on HomeLayout geometry, bottom scrim, no neighbour-row dimming"
```

---

### Task 5: 「Material 紫」预设并设默认

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/ThemePresets.kt:98-110`
- Modify: `app/src/main/java/com/uniteduone/launcher/Settings.kt:24`
- Modify: `app/src/main/res/values/strings.xml:195`, `values-en/strings.xml:200`, `values-zh-rTW/strings.xml:198`(各加一行)
- Modify: `app/src/main/java/com/uniteduone/launcher/SettingsScreen.kt:713`(注释「6 个色点」→「7 个」)
- Test: `app/src/test/java/com/uniteduone/launcher/ThemePresetsTest.kt`(新建)、`SettingsTest.kt`(加两条)

**Interfaces:**
- Produces: `ThemePresets.DEFAULT_ID == "material"`;`ThemePresets.all.first().id == "material"`,`color = #D0BCFF`,`highlight = #E8DCFF`;`Settings().themePresetId == "material"`。

- [ ] **Step 1: 写失败的测试**

`ThemePresetsTest.kt`:
```kotlin
package com.uniteduone.launcher

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePresetsTest {
    @Test fun materialPurpleIsFirstAndDefault() {
        assertEquals("material", ThemePresets.DEFAULT_ID)
        assertEquals("material", ThemePresets.all.first().id)
        assertEquals(Color(0xFFD0BCFF), ThemePresets.all.first().color)
        assertEquals(Color(0xFFE8DCFF), ThemePresets.all.first().highlight)
    }

    @Test fun oldPresetsStillResolve() {
        assertEquals("gold", ThemePresets.byId("gold").id)
        assertEquals(Color(0xFFC0A73A), ThemePresets.byId("gold").color)
        assertEquals(7, ThemePresets.all.size)
    }

    @Test fun unknownIdFallsBackToMaterial() {
        assertEquals("material", ThemePresets.byId("nope").id)
        assertEquals(0, ThemePresets.indexOf("nope"))
    }
}
```
`SettingsTest.kt` 末尾加:
```kotlin
    @Test fun defaultThemePresetIsMaterial() {
        assertEquals("material", Settings().themePresetId)
        assertEquals("material", parseSettings("{}").themePresetId)
    }

    @Test fun legacyGoldPresetIdIsKept() {
        assertEquals("gold", parseSettings("""{"themePresetId": "gold"}""").themePresetId)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.ThemePresetsTest' --tests 'com.uniteduone.launcher.SettingsTest'`
Expected: `materialPurpleIsFirstAndDefault`、`unknownIdFallsBackToMaterial`、`defaultThemePresetIsMaterial` FAIL(默认仍是 gold)。

- [ ] **Step 3: 实现**

`ThemePresets.kt`:`const val DEFAULT_ID = "gold"` → `const val DEFAULT_ID = "material"`;`val all` 列表最前面插入:
```kotlin
        // Material 紫:tv-material darkColorScheme 的 primary #D0BCFF 原值(spec §0);highlight = 混白 55%。
        // 放最前 = swatch 最左 = 默认在最左(左右键方向随之,有意的)。
        ThemePreset("material", R.string.preset_material, Color(0xFFD0BCFF), Color(0xFFE8DCFF)),
```
文件头 KDoc「6 个主题色预设」改「7 个主题色预设(M8 加 Material 紫为默认)」。
`Settings.kt` 第 24 行:`val themePresetId: String = "gold",` → `val themePresetId: String = "material",`。
strings:`values/strings.xml` 在 `preset_gold` 前加 `<string name="preset_material">Material 紫</string>`;`values-en` 加 `<string name="preset_material">Material Purple</string>`;`values-zh-rTW` 加 `<string name="preset_material">Material 紫</string>`。
`SettingsScreen.kt` 第 713 行注释 `6 个色点` → `7 个色点`(代码本就遍历 `ThemePresets.all`,不改)。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: 全部 PASS。

- [ ] **Step 5: 模拟器看 swatch**

装 APK,删掉模拟器上的 settings.json 让默认生效(`adb shell rm /sdcard/Android/data/com.uniteduone.launcher/files/settings.json` 后 `am force-stop` 再 `am start -n`):行标题 / 行图标 / 描边外的一切 accent 处应为紫 #D0BCFF;进设置页主题行,swatch 7 个、最左选中。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/ThemePresets.kt app/src/main/java/com/uniteduone/launcher/Settings.kt app/src/main/java/com/uniteduone/launcher/SettingsScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/test/java/com/uniteduone/launcher/ThemePresetsTest.kt app/src/test/java/com/uniteduone/launcher/SettingsTest.kt
git commit -m "feat(m8): Material Purple preset (#D0BCFF) as default; six legacy presets kept"
```

---

### Task 6: `HeroClock`(大字时钟 + 日期)取代右上角小时钟

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Clock.kt`(重写:拆出 `rememberClockState` 与纯函数 `clockPatterns`,`Clock` → `HeroClock`)
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt:486-537`(顶栏 Row)与根 `Box` 内
- Test: `app/src/test/java/com/uniteduone/launcher/ClockPatternsTest.kt`(新建)

**Interfaces:**
- Produces:
  - `fun clockPatterns(is24Hour: Boolean): Pair<String, String>` —— (时间格式, 日期格式)
  - `@Composable fun HeroClock(modifier: Modifier = Modifier, showDate: Boolean = true)`
- Consumes: HomeScreen 的 `heroAlpha`(Task 4)与 `clockAlpha`。

- [ ] **Step 1: 写失败的测试**

`ClockPatternsTest.kt`:
```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockPatternsTest {
    @Test fun twentyFourHour() {
        assertEquals("HH:mm" to "EEE yyyy/M/d", clockPatterns(is24Hour = true))
    }

    @Test fun twelveHourCarriesAmPm() {
        // 12 小时制必须带 a:凌晨 2 点和下午 2 点否则长得一样(2026-09-15 复审)
        assertEquals("h:mm a" to "EEE yyyy/M/d", clockPatterns(is24Hour = false))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.ClockPatternsTest'`
Expected: 编译失败,`Unresolved reference: clockPatterns`。

- [ ] **Step 3: 重写 Clock.kt**

```kotlin
package com.uniteduone.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** (时间格式, 日期格式)。12 小时制必须带 a(AM/PM),否则凌晨 2 点和下午 2 点长得一样。日期格式 = design §3 的 `EEE yyyy/M/d`。 */
fun clockPatterns(is24Hour: Boolean): Pair<String, String> =
    (if (is24Hour) "HH:mm" else "h:mm a") to "EEE yyyy/M/d"

/** 当前时刻 + 是否 24 小时制。整分钟对齐刷新;监听 TIME/TIMEZONE 广播接住跳变(校时、换时区、改 12/24 开关)。 */
@Composable
private fun rememberClockState(): Pair<Date, Boolean> {
    var now by remember { mutableStateOf(Date()) }
    var tzTick by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    DisposableEffect(ctx) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { now = Date(); tzTick++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        runCatching { ctx.registerReceiver(r, filter) }
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    LaunchedEffect(tzTick) {
        while (true) {
            now = Date()
            val msIntoMinute = System.currentTimeMillis() % 60_000L
            delay(60_000L - msIntoMinute)
        }
    }
    val is24Hour = remember(tzTick) { android.text.format.DateFormat.is24HourFormat(ctx) }
    return now to is24Hour
}

/**
 * M8 hero 主体:大字时钟 84sp Medium + 日期 24sp(spec §1.4),颜色 accent(spec §0「accent 落点」)。
 * 位置由调用方给(HomeScreen:左对齐 SidePadding、顶 HomeLayout.HERO_TOP);不可聚焦。
 */
@Composable
fun HeroClock(modifier: Modifier = Modifier, showDate: Boolean = true) {
    val accent = LocalThemeColors.current.accent
    val (now, is24Hour) = rememberClockState()
    val locale = AppLocale.current ?: Locale.getDefault()
    val (timePattern, datePattern) = clockPatterns(is24Hour)
    // SimpleDateFormat 出生时绑死时区,now/is24Hour 变了就重建(它们随 tzTick 变)
    val timeFmt = remember(timePattern, locale, is24Hour) { SimpleDateFormat(timePattern, locale) }
    val dateFmt = remember(datePattern, locale, is24Hour) { SimpleDateFormat(datePattern, locale) }
    Column(modifier) {
        BasicText(
            text = timeFmt.format(now),
            style = TextStyle(
                fontFamily = Theme.Sans, fontWeight = FontWeight.Medium,
                fontSize = 84.sp, lineHeight = 84.sp, color = accent,
            ),
        )
        if (showDate) {
            BasicText(
                text = dateFmt.format(now),
                modifier = Modifier.padding(top = 8.dp),
                style = TextStyle(fontFamily = Theme.Sans, fontSize = 24.sp, color = accent.copy(alpha = 0.85f)),
            )
        }
    }
}
```
(旧的 `Clock` composable 删除。)

- [ ] **Step 4: HomeScreen 接 HeroClock**

顶栏 `Row` 里删掉 `Clock(modifier = Modifier.alpha(clockAlpha), showDate = showDate)` 那一行。在根 `Box(Modifier.fillMaxSize())` 里、scrim 之后、行区 `Column` 之前加:
```kotlin
        // hero 主体(spec §2.1 第 3 层):不随 shift 走;第 1 行起淡出、待机时回到 1(heroAlpha),BLACK 待机再随 clockAlpha 淡出。
        HeroClock(
            showDate = showDate,
            modifier = Modifier
                .padding(start = Theme.SidePadding, top = HomeLayout.HERO_TOP.dp)
                .alpha(heroAlpha * clockAlpha),
        )
```

- [ ] **Step 5: 跑测试 + 构建 + 模拟器对照效果图 A**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: 全绿。模拟器截图与 `docs/screenshots/m8-mock-A-big-clock.png` 并排看:时钟左边缘 x ≈ 116px、时钟块顶 ≈ 300px、字高约 168px;按「下」一次时钟淡出、回第一行恢复;`settings put secure … ` 不需要——待机用设置页把待机时长改 1 分钟等它,时钟应留在原位、行淡出。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/Clock.kt app/src/main/java/com/uniteduone/launcher/HomeScreen.kt app/src/test/java/com/uniteduone/launcher/ClockPatternsTest.kt
git commit -m "feat(m8): HeroClock (84sp time + 24sp date) as the hero subject; fades from row 1, stays in idle"
```

---

### Task 7: `TopPills`(设置 + 屏保两个 IconButton)取代齿轮

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/TopPills.kt`
- Delete: `app/src/main/java/com/uniteduone/launcher/GearButton.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt`(签名加 `onScreensaver`;顶栏 Row 整段换掉)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt:470-495`(HomeScreen 调用加一行)
- Modify: 三个 `strings.xml`(加 `home_screensaver_button`)

**Interfaces:**
- Produces:
  ```kotlin
  @Composable fun TopPills(
      gearFocus: FocusRequester, canFocus: Boolean, rowsEmpty: Boolean, downTarget: FocusRequester?,
      onSettings: () -> Unit, onScreensaver: () -> Unit,
      onFocusChange: (col: Int, got: Boolean) -> Unit,   // col 0 = 设置,1 = 屏保
      modifier: Modifier = Modifier,
  )
  ```
  HomeScreen 新参数 `onScreensaver: () -> Unit = {}`。
- Consumes: HomeScreen 的 `gearFocus`、`covered`、`rows`、`rowFocus`、`tgtRow`、`report(-1, col, got)`。焦点账本语义不变:`row == -1` 就是「在顶栏」,`tgtGear` 仍按 `row == -1` 判;还原目标仍是 `gearFocus`(设置按钮)。

- [ ] **Step 1: strings**

`values/strings.xml` 加 `<string name="home_screensaver_button">屏保</string>`;`values-en` 加 `<string name="home_screensaver_button">Screensaver</string>`;`values-zh-rTW` 加 `<string name="home_screensaver_button">螢幕保護</string>`。

- [ ] **Step 2: 写 TopPills.kt**

```kotlin
package com.uniteduone.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme

/**
 * 右上 pill 组(spec §1.5):胶囊底 surface α0.65,内含两个 tv-material IconButton(Medium 40dp,图标 20dp,库默认),
 * 聚焦反白 + 1.1 倍由库出。未聚焦图标色 = accent(spec §0「accent 落点」)。
 * 焦点:两个按钮互为左右;上 / 外侧锁 Cancel;下 = 记住的那一格(rowsEmpty 时锁)。
 * 上报 `onFocusChange(col, got)`,col 0 = 设置(挂 gearFocus,还原目标),1 = 屏保。
 */
@Composable
fun TopPills(
    gearFocus: FocusRequester,
    canFocus: Boolean,
    rowsEmpty: Boolean,
    downTarget: FocusRequester?,
    onSettings: () -> Unit,
    onScreensaver: () -> Unit,
    onFocusChange: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val down = if (rowsEmpty) FocusRequester.Cancel else (downTarget ?: FocusRequester.Default)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            .padding(4.dp)
            .focusProperties { this.canFocus = canFocus },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PillButton(
            icon = Icons.Filled.Settings,
            descriptionRes = R.string.menu_settings_title,
            onClick = onSettings,
            onFocusChange = { onFocusChange(0, it) },
            modifier = Modifier
                .focusRequester(gearFocus)
                .focusProperties { up = FocusRequester.Cancel; left = FocusRequester.Cancel; this.down = down },
        )
        PillButton(
            icon = Icons.Filled.Slideshow,
            descriptionRes = R.string.home_screensaver_button,
            onClick = onScreensaver,
            onFocusChange = { onFocusChange(1, it) },
            modifier = Modifier
                .focusProperties { up = FocusRequester.Cancel; right = FocusRequester.Cancel; this.down = down },
        )
    }
}

@Composable
private fun PillButton(
    icon: ImageVector,
    descriptionRes: Int,
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalThemeColors.current.accent
    IconButton(
        onClick = onClick,
        modifier = modifier.onFocusChanged { onFocusChange(it.isFocused) },
        colors = IconButtonDefaults.colors(containerColor = Color.Transparent, contentColor = accent),
        // focused* 用库默认:容器 onSurface 反白、图标 inverseOnSurface
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(descriptionRes),
            modifier = Modifier.size(IconButtonDefaults.MediumIconSize),
        )
    }
}
```
如果 `Icons.Filled.Slideshow` 不存在(material-icons-extended 版本差异),换 `Icons.Filled.Wallpaper`。

- [ ] **Step 3: HomeScreen 接线**

签名里 `onMenuOpenChange: (Boolean) -> Unit,` 之后加 `onScreensaver: () -> Unit = {},`。
把第 486–537 行整个顶栏 `Row(...) { … GearButton(...) … Clock(...) }`(Task 6 后已无 Clock)替换为:
```kotlin
        // 顶栏(spec §1.5):右上 pill 组 + 其下的「有 N 个新应用」。不随 shift 走;待机随内容淡出。
        // 节点只淡出不移除:移除会连带销毁停在按钮上的焦点,醒来第一下按键落空。
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = HomeLayout.PILL_TOP.dp, end = Theme.SidePadding)
                .alpha(contentAlpha),
            horizontalAlignment = Alignment.End,
        ) {
            TopPills(
                gearFocus = gearFocus,
                canFocus = !covered,
                rowsEmpty = rows.isEmpty(),
                downTarget = rowFocus.getOrNull(tgtRow.coerceIn(0, rowFocus.lastIndex)),
                onSettings = { onMenuOpenChange(true) },
                onScreensaver = onScreensaver,
                onFocusChange = { col, got -> report(-1, col, got) },
            )
            val newCount = loaded?.third ?: 0
            if (newCount > 0) {
                BasicText(
                    text = stringResource(R.string.home_new_apps, newCount),
                    modifier = Modifier.padding(top = 6.dp),
                    style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(
                        color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
```
`report()` 与看门狗里所有 `focusedCell != (-1 to -1)` / `focusedCell == (-1 to -1)` 的比对改为 `focusedCell?.first != -1` / `focusedCell?.first == -1`(两个按钮都是「在顶栏」);`cardAt()` 已经按 `row < 0` 返回 null,不用改。
删除文件 `GearButton.kt`。

- [ ] **Step 4: MainActivity**

HomeScreen 调用里 `onMenuOpenChange = …` 之后加:
```kotlin
                    // 屏保按钮 = 立即进入待机(spec §1.5):走与超时同一条路,下一次按键由 dispatchKeyEvent 当唤醒吞掉。
                    onScreensaver = { idle = true },
```

- [ ] **Step 5: 构建 + 模拟器焦点验证**

Run: `source scripts/env.sh && gradle --no-daemon assembleRelease`
Expected: BUILD SUCCESSFUL,`GearButton` 无引用。
模拟器:焦点在第一张卡按「上」→ 落到设置按钮(反白圆);按「右」→ 屏保按钮;再按「右」/「上」焦点不消失(`dumpsys activity top | grep -i focus` 或看反白仍在);按「下」→ 回到离开前那一格;在屏保按钮按确定 → 行、scrim、pill 淡出,时钟留着;再按任意键 → 全部回来且焦点仍在屏保按钮上(被吞的那一下不改焦点)。齿轮按钮按确定 → 齿轮菜单照常,关掉后焦点回齿轮。空桌面(临时把 layout.json 里的包全改成不存在的)时焦点落齿轮、按下不消失。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/TopPills.kt app/src/main/java/com/uniteduone/launcher/HomeScreen.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git rm app/src/main/java/com/uniteduone/launcher/GearButton.kt
git commit -m "feat(m8): TopPills (settings + screensaver IconButtons) replace the gear; screensaver button enters idle"
```

---

### Task 8: 待机 / 预览 / 长按 / 三档 / 七预设复验(模拟器)

**Files:** 无代码改动(发现问题就在本任务内修,单独 commit)。

- [ ] **Step 1: 待机三种内容**

设置页把待机时长改 1 分钟,分别设 CLOCK_ONLY / BLACK / NO_FADE 等待:CLOCK_ONLY 行 + scrim + pill 淡出、hero 时钟在(即使当时焦点在第 2 行也要回到 1);BLACK 整屏黑;NO_FADE 什么都不动。设置页「待机内容」行左右切换的实时演示同上。

- [ ] **Step 2: 设置页透明叠加预览**

打开设置页:底下的新首页透过渐变遮罩可见;改「卡片大小」三档当场变(卡宽 306 / 248 / 176px,行尾整齐无露头);改主题色七个预设当场变(行标题 / 行图标 / 时钟 / pill 图标变色,卡片容器与描边不变)。

- [ ] **Step 3: 长按(M4 验收项)**

```bash
source scripts/env.sh
adb shell settings put secure long_press_timeout 700
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER     # 出长按菜单
adb shell input keyevent KEYCODE_BACK
adb shell settings put secure long_press_timeout 400
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER     # 不出菜单(400 < 600),且不启动应用
adb shell input keyevent KEYCODE_DPAD_CENTER                 # 短按启动应用
```
Expected 同注释;长按菜单关掉后聚焦卡仍是 1.1 放大(按压态已释放)。

- [ ] **Step 4: 铁律 2–7 复验**

- 菜单开合(齿轮 / MENU 键 / 长按)后焦点回原位;
- 退后台再回:`adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.uniteduone.launcher/.MainActivity` 之前先打开别的应用,回来按 BACK,焦点回离开前那一格(CLAUDE.md 模拟器坑第 7 条);
- 包更新缩行:焦点停在第一行第 2 张,`adb shell pm uninstall --user 0 <第一行第 3 张的包>`,焦点留在同行;
- 空桌面:焦点在设置按钮,按下不消失;
- 从别的行按「上」到 pill 组再按「下」,回记忆格。

- [ ] **Step 5: 记录**

把 Step 1–4 的结果与截图路径写进 `docs/WORKLOG.md` 新一节「M8 模拟器复验」;有修复的一并记。Commit:
```bash
git add docs/WORKLOG.md
git commit -m "docs(m8): emulator re-verification of idle, preview, long-press and focus rules"
```

---

### Task 9: 文档与截图

**Files:**
- Modify: `docs/DESIGN-unitedu-open-source.md` §2 首页、§3 壁纸主题时钟
- Modify: `CLAUDE.md` 铁律 3 的焦点恢复表(「首页卡片/齿轮」→「首页卡片/pill 组」)
- Create: `docs/screenshots/m8-after-home.png`、`m8-after-row2.png`、`m8-after-idle.png`、`m8-after-settings-preview.png`

- [ ] **Step 1: DESIGN §2**

「卡片:16:9,圆角 40%」→「卡片:16:9,圆角 8dp(tv-material Card 默认);聚焦 1.1 倍 + 3dp 描边,无光晕」;「大小三档 = 每行 8 / 6 / 5 张,卡片宽由张数算出」后加「(M8:(844 − 20×(N−1))/N,边距 58、间距 20,`HomeLayout`)」;首页结构加一段:「壁纸全屏 + 大字时钟(左上,84sp)+ 应用行从下三分之一起、焦点行锚定、底部 scrim;右上 pill 组(设置 / 屏保);无邻行压暗。」

- [ ] **Step 2: DESIGN §3**

「主题色:5–6 个预设(金 / 香槟 / 蓝 / 紫 / 石墨 / 绿…)」→「7 个预设,默认 Material 紫 #D0BCFF(M8),其余六个保留」;时钟一节加「M8 起是 hero 大字时钟,不在顶栏」。

- [ ] **Step 3: CLAUDE.md 焦点表**

表首行「首页卡片/齿轮 | HomeScreen 看门狗 + 还原效果(…)」的「齿轮」改为「pill 组(设置 / 屏保两个按钮,账本里都是 row = -1)」,其余不动。

- [ ] **Step 4: 截图归档**

模拟器默认设置各截一张到 `docs/screenshots/m8-after-*.png`(命令见 CLAUDE.md;设置页预览那张进设置页主题行截)。

- [ ] **Step 5: Commit**

```bash
git add docs/DESIGN-unitedu-open-source.md CLAUDE.md docs/screenshots/m8-after-home.png docs/screenshots/m8-after-row2.png docs/screenshots/m8-after-idle.png docs/screenshots/m8-after-settings-preview.png
git commit -m "docs(m8): design doc, focus-ownership table and after-screenshots for the home visual refresh"
```

---

### Task 10: 终验与真机清单

- [ ] **Step 1: 全量**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: 全部单测绿(原 74 + 新 13),APK 生成。`grep -rn "androidx.tv.material3.\(ImmersiveList\|Carousel\)\|TvLazy" app/src/main/java` 为 0。

- [ ] **Step 2: 与效果图 A 对照**

`docs/screenshots/m8-after-home.png` vs `docs/screenshots/m8-mock-A-big-clock.png`:边距、卡宽、锚点、pill 位置误差 ≤ 4px(Task 4 Step 6 的脚本再跑一遍)。

- [ ] **Step 3: 给 Gordon 的 A95L 验收清单(写进 WORKLOG,不在这里执行)**

1. 中档 248px 横幅烧字清晰度(不接受 → 设置页改大档 306px,再看);
2. 长按 600ms 出菜单、短按启动;
3. 七个预设切换;
4. 待机三种内容 + 屏保按钮;
5. 从别的应用回来焦点位置;
6. DM Sans 大字时钟观感。

- [ ] **Step 4: 收尾**

按 `superpowers:finishing-a-development-branch` 决定并入 main 的方式(本项目惯例:本地 `merge --no-ff` 到 main,不推远程,Gordon 说「推」再推)。

---

## Self-Review

- **Spec 覆盖**:§1.0 库边界 → T1/T3/T7;§1.1 几何 → T2/T4;§1.2 焦点 → T3(库默认)+ T4(压暗删除);§1.3 颜色 → T1(色阶)+ T5(预设)+ T3/T4/T7(落点);§1.4 字阶 → T1(Typography)+ T4(行标题)+ T6(时钟);§1.5 pill → T7;§2.1–2.5 结构 → T4/T6/T7;§3 三档 → T2/T4;§4 文案与删除 → T5/T7/T3/T4;§5 验收 → T8/T10;§6 不变量 → T3 容器色、T7 不引入新浮层;§7 不做 → Global Constraints。
- **占位符**:无 TBD / "similar to"。
- **类型一致**:`CardMetrics` 字段在 T3(先用旧字段名 `cardCorner/titleGap/titleLine/titleSize`,T4 保留同名)与 T4 一致;`HomeLayout` 函数名在 T2/T4/T6/T7 一致;`report(-1, col, got)` 与 T7 的 `onFocusChange(Int, Boolean)` 一致;`onScreensaver` 在 T7 的 HomeScreen 与 MainActivity 一致。
