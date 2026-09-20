package com.uniteduone.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * **折叠(Task 9,spec §4「焦点进入应用行时收起成向上箭头」)**:[collapsed] 为真时,药丸组 +
 * 时钟字标那一整条 `Row` 淡出到 alpha 0,同一位置叠一个居中的向上箭头淡入——**两层都是常驻节点,
 * 谁都不会被移除**。这是本文件改这份界面前必须知道的第 1/2 条铁律的直接推论:`PillGroup` 里
 * `settingsFocusRequester`/`screensaverFocusRequester` 若在折叠时被移出组合,挂在它们身上的焦点
 * 会被销毁,HomeScreen 那句「UP 从第 0 行回来」的 `gearFocus.requestFocus()` 就成了在跟一个悬空
 * 引用打交道——静默失败,遥控器看着没反应。`canFocus` 继续只读 `covered`(浮层是否盖住首页),
 * 和 `collapsed`(是否折叠)是两个独立的量:折叠只改看不看得见,不改能不能拿到焦点。
 * `collapsed` 的真值来自 HomeScreen 的 `activeRow >= 0`——`activeRow` 原本只用于纵向锚定位移,
 * 从不为负;这次顺着它「焦点真的落在某一行才更新、瞬时丢焦点不动它」的粘滞写法(与看门狗账本
 * 同一原则:只信目标自报,不信过程中的空档)对称补了一路——药丸组拿到焦点时置 -1,天然不抖动。
 * **200ms 的折叠时长是占位值,Google 真实曲线未实测**(见 [TOP_BAR_COLLAPSE_MS] 的注释)。
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
    /** 折叠态(Task 9):true = 焦点已进入某一应用行,整条顶栏淡出、居中向上箭头淡入。
     *  调用方传 `HomeScreen` 的 `activeRow >= 0`。不影响 [canFocus]——折叠时药丸组依旧可以
     *  被程序化 `requestFocus()` 命中,只是暂时看不见(见本文件顶部 KDoc)。 */
    collapsed: Boolean,
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
    // 占位值:Google 的折叠/展开曲线实测报告 §11 列为未量项,真机验收量出实数后改这一个常量。
    val barAlpha by animateFloatAsState(
        targetValue = if (collapsed) 0f else 1f,
        animationSpec = tween(TOP_BAR_COLLAPSE_MS, easing = Theme.MotionEasing),
        label = "gtvTopBarCollapse",
    )
    Box(modifier = modifier.fillMaxWidth()) {
        // 展开态内容:两层都常驻组合(铁律 1/2 的推论,见本文件顶部 KDoc)——折叠只把它淡出到
        // alpha 0,PillGroup 里的 FocusRequester 依旧挂着、依旧能被命中。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(barAlpha)
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
            )
            // Google 在这条留白里放搜索 / Home / Apps 三个 tab;我们没有对应功能,整组省略(spec §9)。
            Spacer(Modifier.weight(1f))
            ClockWordmark(showDate = showDate)
        }
        // 折叠态内容:与上面那层反向淡入淡出(1 - barAlpha),占同一块地方——高度与展开行
        // 的量测结果相同(TOP_BAR_TOP 顶部留白 + TOP_BAR_HEIGHT 一整行),不会让下面「有 N 个
        // 新应用」那行提示跟着上下跳。纯装饰,不接受焦点/点击:真机上按的是 UP 键,由 HomeScreen
        // 已有的 gearFocus.requestFocus() 命中依旧挂着的药丸组,这一个箭头只负责「看得见的那半」。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 顺序要紧:先 padding(外层)腾出顶部留白,height(内层)再在剩下的空间里量出
                // 一整行——反过来的话 height 会先把总高钉死在 36dp,padding 从里面再抠掉 34dp,
                // 图标只剩 2dp 可画(2026-09-20 模拟器实测复现:箭头缩成一个几乎看不见的小点)。
                .padding(top = GtvLayout.TOP_BAR_TOP.dp)
                .height(GtvLayout.TOP_BAR_HEIGHT.dp)
                .alpha(1f - barAlpha),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                // 装饰性指示,不代表一个可操作控件(真正接收焦点的是隐藏起来的药丸组)。
                contentDescription = null,
                tint = LocalThemeColors.current.accent,
                modifier = Modifier.size(GtvLayout.TOP_BAR_ICON.dp),
            )
        }
    }
}

/** 顶栏折叠/展开动效时长,ms——**占位值,Google 真实曲线未实测**
 *  (docs/research/2026-09-20-google-tv-launcherx-measurements.md §11「顶栏折叠/展开的触发点与
 *  动画曲线」列为实测未量项)。真机验收量出实数后改这一处,不要在别的地方另建一个数字。 */
private const val TOP_BAR_COLLAPSE_MS = 200

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
) {
    val down = if (rowsEmpty) FocusRequester.Cancel else (downTarget ?: FocusRequester.Default)
    Row(
        modifier = Modifier
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
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(GtvLayout.TOP_BAR_ICON.dp)
            .onFocusChanged { onFocusChange(it.isFocused) },
        colors = IconButtonDefaults.colors(containerColor = Color.Transparent, contentColor = accent),
        // focused* 用库默认:容器 onSurface 反白、图标 inverseOnSurface(与 TopPills 同)。
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
private fun ClockWordmark(showDate: Boolean) {
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
        style = TextStyle(fontFamily = Theme.Sans, fontSize = GtvLayout.TOP_BAR_CLOCK_TEXT.sp, color = accent),
    )
}
