package com.uniteduone.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * gtv 线的顶栏(spec §4):左起药丸组(设置 / 屏保,左缘钉在 [GtvLayout.CONTENT_KEYLINE])——
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
    settingsFocusRequester: FocusRequester,
    screensaverFocusRequester: FocusRequester,
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
    onSettings: () -> Unit,
    onScreensaver: () -> Unit,
    /** (col, got):col 0 = 设置、1 = 屏保。**两个按钮必须报不同的 col**——HomeScreen 用它去重
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
        PillGroup(
            settingsFocusRequester = settingsFocusRequester,
            screensaverFocusRequester = screensaverFocusRequester,
            canFocus = canFocus,
            rowsEmpty = rowsEmpty,
            downTarget = downTarget,
            onSettings = onSettings,
            onScreensaver = onScreensaver,
            onFocusChange = onFocusChange,
            modifier = Modifier.alpha(pillAlpha),
        )
        // Google 在这条留白里放搜索 / Home / Apps 三个 tab;我们没有对应功能,整组省略(spec §9)。
        Spacer(Modifier.weight(1f))
        ClockWordmark(showDate = showDate, modifier = Modifier.alpha(clockAlpha))
    }
}

/**
 * 药丸组本体:换皮自旧的 `TopPills` 组件(该文件已删,见本文件顶部 KDoc)——底色改 [GtvTokens.PillTrack]、
 * 尺寸改读 [GtvLayout],焦点画法(库默认聚焦反白 + 1.1 倍、未聚焦色 = accent)不变。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PillGroup(
    settingsFocusRequester: FocusRequester,
    screensaverFocusRequester: FocusRequester,
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
            .height(GtvLayout.TOP_BAR_HEIGHT.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(GtvTokens.PillTrack)
            // 轨道内缘到图标的留白:spec 没给专门 token,借用 TOP_BAR_ICON_GAP(与组内图标间距同一个值)。
            .padding(horizontal = GtvLayout.TOP_BAR_ICON_GAP.dp)
            .focusProperties { this.canFocus = canFocus },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarIconButton(
            icon = Icons.Filled.Settings,
            descriptionRes = R.string.menu_settings_title,
            onClick = onSettings,
            onFocusChange = { onFocusChange(0, it) },
            modifier = Modifier
                .focusRequester(settingsFocusRequester)
                .focusProperties { up = FocusRequester.Cancel; left = FocusRequester.Cancel; this.down = down },
        )
        Spacer(Modifier.width(GtvLayout.TOP_BAR_ICON_GAP.dp))
        TopBarIconButton(
            icon = Icons.Filled.Slideshow,
            descriptionRes = R.string.home_screensaver_button,
            onClick = onScreensaver,
            onFocusChange = { onFocusChange(1, it) },
            modifier = Modifier
                .focusRequester(screensaverFocusRequester)
                .focusProperties { up = FocusRequester.Cancel; right = FocusRequester.Cancel; this.down = down },
        )
    }
}

@Composable
private fun TopBarIconButton(
    icon: ImageVector,
    descriptionRes: Int,
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
    var focused by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val animSpec = { ms: Int -> tween<Color>(durationMillis = ms, easing = Theme.MotionEasing) }
    val containerColor by animateColorAsState(
        targetValue = if (focused) scheme.onSurface else Color.Transparent,
        animationSpec = animSpec(if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS),
        label = "topBarIconContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (focused) scheme.inverseOnSurface else accent,
        animationSpec = animSpec(if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS),
        label = "topBarIconContent",
    )
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(GtvLayout.TOP_BAR_ICON.dp)
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
            modifier = Modifier.size(GtvLayout.TOP_BAR_ICON.dp),
        )
    }
}

/**
 * 右侧时钟 + 「UnitedU」字标,20 sp,单行(spec §4:「日期跟在时钟后面,同字号」)。
 * 复用 [clockPatterns] 的格式化规则与 [rememberClockState] 的跳变监听(Clock.kt,2026-09-20 放宽到
 * internal 供这里调用)——不复用 84 sp 的 `HeroClock`,那是 hero / 屏保场景的大字排版,不适合这里的单行小字。
 * 「UnitedU」是品牌字标,不走 strings.xml(不需要本地化,与 Google TV 字标同理)。
 */
@Composable
private fun ClockWordmark(showDate: Boolean, modifier: Modifier = Modifier) {
    val accent = LocalThemeColors.current.accent
    val state = rememberClockState()
    val locale = AppLocale.current ?: Locale.getDefault()
    val (timePattern, datePattern) = clockPatterns(state.is24Hour)
    // SimpleDateFormat 出生时把时区绑死:tzTick 变(换时区 / 校时 / 改 12-24 开关)就重建,不能只看 pattern。
    val timeFmt = remember(state.tzTick, timePattern, locale) { SimpleDateFormat(timePattern, locale) }
    val dateFmt = remember(state.tzTick, datePattern, locale) { SimpleDateFormat(datePattern, locale) }
    val text = buildString {
        append(timeFmt.format(state.now))
        if (showDate) {
            append(' ')
            append(dateFmt.format(state.now))
        }
        append(" | UnitedU")
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(fontFamily = Theme.Sans, fontSize = GtvLayout.TOP_BAR_CLOCK_TEXT.sp, color = accent),
    )
}
