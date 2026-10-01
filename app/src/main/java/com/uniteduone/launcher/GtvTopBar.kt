package com.uniteduone.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * gtv 线的顶栏(spec §4):左起药丸组(**R89 起:设置 / 应用 / 输入源**,原来是设置 / 屏保;左缘钉在 [GtvLayout.CONTENT_KEYLINE])——
 * 大片留白(Google 在这里放搜索 / Home / Apps,spec §9 裁定我们没有对应功能,省略)——
 * 右侧时钟 + 「UnitedU」字标。是旧的 `TopPills` 组件的换皮 + 搬迁(该文件已随 Fix 5〔终审
 * 2026-09-20〕删除——零调用点,`GtvTopBar` 是唯一实现,不再留两份互相漂移):药丸组的焦点契约
 * 原样保留——调用方仍要传 `row = -1` 给 HomeScreen 的 `report()`(见 [onFocusChange] 的 KDoc),
 * 这是首页焦点账本识别「顶栏」的唯一依据,不能变。
 *
 * **Ruling R21(终审 2026-09-20,owner 真机走查后推翻 Task 9):顶栏不折叠,永远可见。**
 * 原设计是焦点进入应用行后整条顶栏淡出、居中叠一个向上箭头收起(Task 9,照搬 Google——Google
 * 折叠是为了给它的内容行腾地方)。但我们的 hero 区(B3 裁定「留给壁纸」)本来就什么都不放,
 * 没有地方可腾,折叠只会多露一截壁纸;箭头反而让人误以为「上面还有一行没显示」。这是本分支
 * 第二处「照搬了 Google 的形式、没照搬 Google 的内容模型」——第一处是空的搜索/Apps 药丸组,
 * spec §9 已经删掉。现在药丸组 + 时钟字标这一整条 `Row` 永远画在同一层,不再有第二层折叠
 * 内容、不再有 `collapsed` 参数,`TOP_BAR_COLLAPSE_MS` 常量也一并删除。
 * **待机时药丸组仍然会淡出,时钟 + 字标不一定跟着淡——那是调用方(`HomeScreen`)传进来的
 * [pillAlpha]/[clockAlpha] 两个透明度各管一半(Ruling R23,终审 2026-09-20),与这里删掉的
 * 折叠是两套不同的机制**:折叠曾经只淡顶栏一处、是「腾地方」,我们没有要腾的地方,所以整个
 * 删掉,不是调小时长或换个触发条件;待机该显示什么由 HomeScreen 按 `IdleContent` 决定,
 * 这里只负责把它算好的两个数字分别贴到药丸组与时钟字标上,不自己判断待机状态。
 */
@Composable
fun GtvTopBar(
    /** 三颗胶囊各自的 requester,下标 = [onFocusChange] 的 col(0 设置 / 1 应用 / 2 输入源,R89)。 */
    pillFocusRequesters: List<FocusRequester>,
    /** 浮层开着 / 首页被盖住时为 false(与 `TopPills` 同名同义)。 */
    canFocus: Boolean,
    /** 桌面一张卡都没有时,下键锁 Cancel(与 `TopPills` 同名同义)。 */
    rowsEmpty: Boolean,
    /** 下键落点:通常是首页记住的那一行(与 `TopPills` 同名同义)。 */
    downTarget: FocusRequester?,
    /** 药丸组透明度(HomeScreen 的 `contentAlpha`)——待机随卡片行一起淡出的那一半。 */
    pillAlpha: Float,
    /** 时钟 + 字标透明度(HomeScreen 的 `topBarClockAlpha`)。**Ruling R23**:待机且档位是
     *  `IdleContent.CLOCK_ONLY` 时钉 1,不跟药丸组一起淡出——这一档留住的正是这行小字,
     *  其余情形(BLACK / 非待机 / 自定义屏保)与 [pillAlpha] 取值相同。 */
    clockAlpha: Float,
    /** 时钟旁是否带日期(design §2,设置页开关透传)。 */
    showDate: Boolean,
    /** R149:日期前面带不带星期。 */
    showWeekday: Boolean = false,
    /** 时钟字标紧贴深阴影([ClockWordmark] 的 `strongShadow`)的不透明度,0 = 不画。ui-pending #8:HomeScreen
     *  传 `1 − contentAlpha`——待机时两层压暗渐变随 contentAlpha 淡掉,壁纸原样露出,亮壁纸上 accent 小字
     *  对比度只有 1.4:1;阴影随渐变同步淡入淡出,不瞬切。 */
    clockShadowAlpha: Float = 0f,
    onSettings: () -> Unit,
    /** R89:「应用」胶囊 = 打开所有应用页(AppsPage)。 */
    onApps: () -> Unit,
    /** R89:「输入源」胶囊 = 打开输入源页(InputsPage)。 */
    onInputs: () -> Unit,
    /** (col, got):col 0 = 设置、1 = 应用、2 = 输入源(R89 前 1 = 屏保)。**每颗按钮必须报不同的 col**——HomeScreen 用它去重
     *  (见 HomeScreen.report 的 KDoc),报成同一个值会让「药丸组内部切焦点」被误判成
     *  「整体失焦」,看门狗趁虚而入把焦点抢到别处。 */
    onFocusChange: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ruling R21:不再有折叠/展开两层内容互相淡入淡出,顶栏永远是这一条 Row。
    // Ruling R23:待机时的淡出不再是外层套一份 contentAlpha 一起淡——药丸组与时钟字标现在
    // 各自读 pillAlpha/clockAlpha,分别贴在下面两个子节点上(理由见本文件顶部 KDoc)。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GtvLayout.CONTENT_KEYLINE.dp,
                end = GtvLayout.CONTENT_KEYLINE.dp,
                top = GtvLayout.TOP_BAR_TOP.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // R133(2026-09-30 新手可读性):三颗都是纯图标,第一次用的人认不全(尤其「所有应用」「输入源」)——
        // 焦点落在哪一颗,就淡入它的名字,离开顶栏淡出。R146(Gordon:「不需要胶囊样式,字体小一点」「直接放在按钮的正下方」):
        // 名字写在那颗按钮正下方,不垫底板,见 [PillLabels]。
        // 只是本地显示状态:焦点账本照旧只经 onFocusChange 上报,这里不读不写任何焦点目标。
        var focusedPill by remember { mutableStateOf<Int?>(null) }
        Box {
            PillGroup(
                pillFocusRequesters = pillFocusRequesters,
                canFocus = canFocus,
                rowsEmpty = rowsEmpty,
                downTarget = downTarget,
                onSettings = onSettings,
                onApps = onApps,
                onInputs = onInputs,
                onFocusChange = { col, got ->
                    focusedPill = nextFocusedPill(focusedPill, col, got)
                    onFocusChange(col, got)
                },
                modifier = Modifier.alpha(pillAlpha),
            )
            PillLabels(focusedPill = focusedPill, pillAlpha = pillAlpha)
        }
        // Google 在这条留白里放搜索 / Home / Apps 三个 tab;我们没有搜索与 Home tab(spec §9),
        // 「应用」R89 起做成药丸组里的一颗(打开所有应用页),不另起一组 tab。
        Spacer(Modifier.weight(1f))
        ClockWordmark(
            showDate = showDate,
            showWeekday = showWeekday,
            shadow = clockShadowAlpha > 0f,
            strongShadow = true,
            shadowAlpha = clockShadowAlpha.coerceIn(0f, 1f),
            modifier = Modifier.alpha(clockAlpha),
        )
    }
}

/**
 * 药丸组本体:换皮自旧的 `TopPills` 组件(该文件已删,见本文件顶部 KDoc)——底色改 [GtvTokens.PillTrack]、
 * 尺寸改读 [GtvLayout],焦点画法(库默认聚焦反白 + 1.1 倍、未聚焦色 = accent)不变。
 */
/** 药丸组的三颗(R89,Gordon 2026-09-27 定顺序:设置、应用、输入源)。下标 = 焦点账本里的 col。 */
private data class TopPill(val icon: ImageVector, val descriptionRes: Int, val glyph: Float)

private val TOP_PILLS = listOf(
    TopPill(Icons.Filled.Settings, R.string.menu_settings_title, GtvLayout.TOP_BAR_GEAR_GLYPH),
    TopPill(Icons.Filled.Apps, R.string.apps_page_title, GtvLayout.TOP_BAR_APPS_GLYPH),
    TopPill(Icons.Filled.Input, R.string.inputs_page_title, GtvLayout.TOP_BAR_INPUTS_GLYPH),
)

/** 顶栏胶囊个数(焦点账本里 row = -1 那组的 col 取值 0 until 它)。 */
const val TOP_PILL_COUNT = 3

/**
 * 「哪一颗在焦点」的显示状态,只跟 onFocusChange 的上报走:得到就是它,失去只清掉仍是它的那一颗
 * (颗与颗之间换焦点时新旧两条上报先后不定,迟到的「失去」不能清掉新的)。只喂顶栏名字([PillLabels]);纯显示,不是焦点目标。
 */
fun nextFocusedPill(current: Int?, col: Int, got: Boolean): Int? =
    if (got) col else if (current == col) null else current

/**
 * 焦点所在那颗的名字,写在它**正下方**(R146,2026-09-30 Gordon;R133 时是药丸组右边一颗小胶囊)。每颗一个名字、
 * 各自淡入淡出(换颗时旧的在原处淡出、新的在新按钮下淡入,不滑动);字号 `Type.body`,比时钟小一档,不垫底板。
 * 顶栏左边没有压暗(R84 的渐变从上往下、顶上基本是原壁纸),亮壁纸上浅色字靠两层阴影剥出来:底层同位置一份黑字 + 16 px 晕
 * (被上层的字盖住,只剩一圈深色晕),上层 accent 字 + 4 px 紧贴阴影——只有一层 4 px 时,在「夏日数码门」的青色光圈上几乎看不清(模拟器截图)。
 * **不占布局**:整层按 0 × 0 上报,名字画在药丸组下缘之外;顶栏那一行的高度、右边时钟的位置都不受影响。
 * 透明度在图层里读(R140 复审同一规则:组合期读会让淡入淡出的每一帧重组顶栏)。
 */
@Composable
private fun PillLabels(focusedPill: Int?, pillAlpha: Float) {
    val accent = LocalThemeColors.current.accent
    val base = Type.body.copy(lineHeight = TextUnit.Unspecified)
    Layout(
        content = {
            TOP_PILLS.forEachIndexed { col, pill ->
                val on = focusedPill == col
                val a by animateFloatAsState(
                    targetValue = if (on) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = if (on) GtvLayout.TOP_NAV_FADE_IN_MS else GtvLayout.TOP_NAV_FADE_OUT_MS,
                        easing = Theme.AppFocusEasing,
                    ),
                    label = "topBarPillName$col",
                )
                val name = stringResource(pill.descriptionRes)
                // 只是画面:按钮自己的 contentDescription 就是这个名字,不再进无障碍树(读屏不会念两遍,脚本也不会多一个同名节点)
                Box(Modifier.graphicsLayer { alpha = a * pillAlpha }.clearAndSetSemantics { }) {
                    BasicText(
                        text = name,
                        maxLines = 1,
                        style = base.copy(color = Color.Black, shadow = Shadow(Color.Black, Offset(0f, 0f), blurRadius = 16f)),
                    )
                    BasicText(
                        text = name,
                        maxLines = 1,
                        style = base.copy(color = accent, shadow = Shadow(Color.Black, Offset(0f, 0f), blurRadius = 4f)),
                    )
                }
            }
        },
    ) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val top = (GtvLayout.TOP_BAR_HEIGHT + GtvLayout.TOP_BAR_LABEL_GAP).dp.roundToPx()
        layout(0, 0) {
            placeables.forEachIndexed { col, p ->
                // 与 PillGroup 的排法同一套数:内缘留白 ICON_GAP,每颗 ICON_BOX,颗与颗之间 ICON_GAP
                val center = (GtvLayout.TOP_BAR_ICON_GAP + col * (GtvLayout.TOP_BAR_ICON_BOX + GtvLayout.TOP_BAR_ICON_GAP) +
                    GtvLayout.TOP_BAR_ICON_BOX / 2).dp.roundToPx()
                p.place(center - p.width / 2, top)
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PillGroup(
    pillFocusRequesters: List<FocusRequester>,
    canFocus: Boolean,
    rowsEmpty: Boolean,
    downTarget: FocusRequester?,
    onSettings: () -> Unit,
    onApps: () -> Unit,
    onInputs: () -> Unit,
    onFocusChange: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val down = if (rowsEmpty) FocusRequester.Cancel else (downTarget ?: FocusRequester.Default)
    val actions = listOf(onSettings, onApps, onInputs)
    Row(
        modifier = modifier
            .height(GtvLayout.TOP_BAR_HEIGHT.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(GtvTokens.PillTrack)
            // 轨道内缘到图标的留白:spec 没给专门 token,借用 TOP_BAR_ICON_GAP(与组内图标间距同一个值)。
            .padding(horizontal = GtvLayout.TOP_BAR_ICON_GAP.dp)
            .focusProperties { this.canFocus = canFocus },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TOP_PILLS.forEachIndexed { col, pill ->
            if (col > 0) Spacer(Modifier.width(GtvLayout.TOP_BAR_ICON_GAP.dp))
            TopBarIconButton(
                icon = pill.icon,
                descriptionRes = pill.descriptionRes,
                glyphSize = pill.glyph.dp,
                onClick = actions[col],
                onFocusChange = { onFocusChange(col, it) },
                modifier = Modifier
                    .focusRequester(pillFocusRequesters[col])
                    .focusProperties {
                        up = FocusRequester.Cancel
                        // 组内左右交给几何搜索;两端锁死,焦点出不了药丸组(与 R89 前两颗时同一规则)。
                        if (col == 0) left = FocusRequester.Cancel
                        if (col == TOP_PILLS.lastIndex) right = FocusRequester.Cancel
                        this.down = down
                    },
            )
        }
    }
}

@Composable
private fun TopBarIconButton(
    icon: ImageVector,
    descriptionRes: Int,
    /** 图形本身的绘制大小,与按钮的触控/焦点框([GtvLayout.TOP_BAR_ICON_BOX],下面写死在
     *  `.size()` 里)分开传入——owner 反馈 Round 6 起两者不再共用一个常量,理由见
     *  [GtvLayout.TOP_BAR_GEAR_GLYPH]/[GtvLayout.TOP_BAR_APPS_GLYPH]/[GtvLayout.TOP_BAR_INPUTS_GLYPH] 的 KDoc。 */
    glyphSize: Dp,
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalThemeColors.current.accent
    // Fix 3(owner 反馈 R2,2026-09-20):这颗填色焦点(spec §0「四种焦点画法」之一)原来完全依赖
    // tv-material3 库默认的 focused* 颜色——**反编译确认该库的 Surface/Button/IconButton 都不
    // animate 容器色/内容色**(只有 RadioButton 用了 animateColorAsState;Card/Button/IconButton
    // 的 containerColor 在聚焦帧瞬间切换,animateFloatAsState 只用在 SurfaceScale 那条缩放上)。
    // 所以这里不能指望换个 colors() 参数就自动有过渡——改成自己维护 focused 状态、自己
    // animateColorAsState,把动画后的同一个值**同时**喂给 containerColor 与 focusedContainerColor
    // (content 同理):无论库内部怎么在这两个状态间切换,切换前后读到的都是我们这一帧算好的
    // 同一个颜色,不会有它自己的瞬时跳变。目标色沿用原来的库默认值(容器 onSurface 反白、
    // 图标 inverseOnSurface,与旧的 TopPills 一致,这里只是把「瞬间到达」换成「动画到达」)。
    // **owner 反馈 Round 4(2026-09-21)**:时长换成 Google 实测的顶栏专属值——
    // `integer/top_nav_animation_duration_focus = 100`/`_unfocus = 200`
    // (`GtvLayout.TOP_NAV_FADE_IN_MS`/`TOP_NAV_FADE_OUT_MS`),不再是 app 卡片那一对
    // FOCUS_FADE_IN_MS/OUT_MS。曲线用 `Theme.AppFocusEasing`——该资源本身没有单独核实
    // interpolator,按同一份 APK 里其它焦点动画一致沿用 AccelerateDecelerate,不引入第三条曲线。
    var focused by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val animSpec = { ms: Int -> tween<Color>(durationMillis = ms, easing = Theme.AppFocusEasing) }
    val containerColor by animateColorAsState(
        targetValue = if (focused) scheme.onSurface else Color.Transparent,
        animationSpec = animSpec(if (focused) GtvLayout.TOP_NAV_FADE_IN_MS else GtvLayout.TOP_NAV_FADE_OUT_MS),
        label = "topBarIconContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (focused) scheme.inverseOnSurface else accent,
        animationSpec = animSpec(if (focused) GtvLayout.TOP_NAV_FADE_IN_MS else GtvLayout.TOP_NAV_FADE_OUT_MS),
        label = "topBarIconContent",
    )
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(GtvLayout.TOP_BAR_ICON_BOX.dp)
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) },
        colors = IconButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = containerColor,
            focusedContentColor = contentColor,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(descriptionRes),
            modifier = Modifier.size(glyphSize),
        )
    }
}

/**
 * 右侧时钟 + 「UnitedU」字标,单行(spec §4:「日期跟在时钟后面,同字号」)。
 * 复用 [clockPatterns] 的格式化规则与 [rememberClockState] 的跳变监听(Clock.kt,2026-09-20 放宽到
 * internal 供这里调用)——不复用 84 sp 的 `HeroClock`,那是旧线 hero 的大字排版,gtv 线上已无人调用。
 * 「UnitedU」是品牌字标,不走 strings.xml(不需要本地化,与 Google TV 字标同理)。
 *
 * **Ruling R26(2026-09-21,推翻 R9)**:放宽到 internal,系统屏保 [UnitedUDream] 也画这一份。
 * R9 当初让系统屏保留着 84 sp `HeroClock`,是在 Gordon 下达「Google 原生怎么样,我们就做成怎么样」
 * 之前定的;Google TV 的 ambient 屏保没有大字时钟,而且他在真机上看到那口大钟后明确报为问题。
 * 现在三处待机画面(首页待机 / 桌面自定义屏保 / 系统屏保)留下的都是同一行小字,不再各长各的。
 *
 * @param shadow 照片可能很亮时为真(与 `HeroClock` 同一条规则):加一层淡阴影,不做描边、不做底板。
 * @param strongShadow [shadow] 加深一档(ui-pending #8,首页待机用):紧贴字形的深阴影
 *   (纯黑、无偏移、模糊 4 px;原来那档是黑 α 0.55、下偏 2 px、模糊 16 px)。原来那档在米白壁纸
 *   (#F2EAD8)上实测反而更糊:accent 小字(相对亮度 0.57)比米白(0.83)暗,一层铺得很开的淡灰只是把
 *   字边的背景往字的亮度拉近(字 : 字边 1–3 px 背景 1.42 → 1.11)。紧贴的深阴影在字形外压出一圈比字更暗的边
 *   (字边 0.40,边 : 米白 1.9:1),靠这圈暗边把字从亮底上剥出来——字 : 字边的 WCAG 数值仍只有 1.36,
 *   不到 3:1,彻底解决要换字色或待机保留顶部压暗(docs/ui-pending.md #8)。系统屏保照片上仍用原来那档。
 * @param shadowAlpha 只作用于 [strongShadow] 那档:阴影颜色的 alpha(首页传 `1 − contentAlpha`,随待机渐变)。
 */
@Composable
internal fun ClockWordmark(
    showDate: Boolean,
    /** R149:日期前面带不带星期(只在 [showDate] 为真时有意义)。 */
    showWeekday: Boolean = false,
    shadow: Boolean = false,
    modifier: Modifier = Modifier,
    strongShadow: Boolean = false,
    shadowAlpha: Float = 1f,
) {
    val accent = LocalThemeColors.current.accent
    val state = rememberClockState()
    val locale = AppLocale.current ?: Locale.getDefault()
    val (timePattern, datePattern) = clockPatterns(state.is24Hour, weekday = showWeekday)
    // SimpleDateFormat 出生时把时区绑死:tzTick 变(换时区 / 校时 / 改 12-24 开关)就重建,不能只看 pattern。
    val timeFmt = remember(state.tzTick, timePattern, locale) { SimpleDateFormat(timePattern, locale) }
    val dateFmt = remember(state.tzTick, datePattern, locale) { SimpleDateFormat(datePattern, locale) }
    val text = buildString {
        append(timeFmt.format(state.now))
        if (showDate) {
            append(' ')
            append(dateFmt.format(state.now))
        }
        // R148(2026-09-30 Gordon:「用户可能不太喜欢 unitedu 一直显示在那里」):不再跟「| UnitedU」字标,只剩时间与日期;
        // 品牌名挪到设置第一层页名上方(SettingsShell 的 ROOT)。函数名沿用。
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = Type.clock.copy(
            color = accent,
            shadow = if (shadow && strongShadow) {
                Shadow(color = Color.Black.copy(alpha = shadowAlpha), offset = Offset(0f, 0f), blurRadius = 4f)
            } else if (shadow) {
                Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 2f), blurRadius = 16f)
            } else {
                null
            },
        ),
    )
}
