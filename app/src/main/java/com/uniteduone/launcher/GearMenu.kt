package com.uniteduone.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme

data class MenuItem(val label: String, val hint: String, val action: () -> Unit)

/**
 * 菜单浮层。齿轮菜单、编辑页条目菜单、首页长按卡片菜单、编辑页行菜单共用这一份。
 *
 * **gtv 线 Task 8 换皮**(spec §6 / B9):整屏 [GtvTokens.MenuBg] 底,不再是 `Theme.DialogSurface`
 * 的小面板;左半是该应用的 banner 图 + 名字,右半是一列整宽药丸([GtvLayout.MENU_ITEM_WIDTH] ×
 * [GtvLayout.MENU_ITEM_HEIGHT] 起,全圆角)。菜单项内容(打开/卸载/修改标题/更改图标/移动位置/
 * 从当前分类移除)与焦点机制都原样不动——下面两个 `LaunchedEffect` 与
 * `BackHandler` 逐字保留自换皮前:nonce 初始循环退出判据是目标自报 `holder != null`(铁律 2,
 * 不信 `requestFocus()` 的返回值),`holder == null` 看门狗守卫与 key 同一表达式(铁律 6)、
 * 每次再丢焦点自动重新武装(铁律 7)。见 CLAUDE.md 焦点责任表「齿轮菜单 / 长按卡片菜单」一行。
 *
 * **Ruling R17(终审 2026-09-20)**:[showHints] 是这次换皮唯一补回的差异——[MenuItem.hint] 换皮后
 * 一度全线丢失(4 个调用点全部传 `false`/默认值,行为不变);现在只有齿轮设置菜单(4 项:编辑分栏/
 * UnitedU 设置/系统设置/关于)传 `true`,在药丸里加一行说明。长按卡片菜单、编辑页的两个菜单
 * (卡片操作、行操作)保持不传——它们的动作词(打开/卸载/移动位置……)足够自解释,这与 Google
 * 卡片菜单没有说明文字是同一个判断,不是漏改。两个菜单从此**刻意不同**,不是不小心不同。
 *
 * @param title 标题;null = 沿用齿轮菜单的「设置」。长按菜单传该卡的显示名,行菜单传行名。
 *   [app] 为 null 时(齿轮设置菜单、编辑页行菜单——都没有对应单个应用)左半退化成只显示这个标题,
 *   不画 banner。
 * @param app 左半 banner 的取图来源;只取 [AppEntry.card] / [AppEntry.isWide] / [AppEntry.fallbackColor]
 *   三个字段,取图判断逻辑与 [AppCard] 一致(有横幅铺满 / 方图标居中留边 / 都没有回落纯色底)。
 *   名字仍由 [title] 给——调用方那份已经处理过改名覆盖、查不到时退回包名的兜底,这里不重复一遍。
 * @param showHints 见上面 Ruling R17。默认 false(长按 / 行 / 卡片菜单的既有行为不变)。
 */
@Composable
fun GearMenu(
    items: List<MenuItem>,
    onDismiss: () -> Unit,
    nonce: Int = 0,
    title: String? = null,
    app: AppEntry? = null,
    showHints: Boolean = false,
) {
    val rowFocus = remember(items.size) { List(items.size.coerceAtLeast(1)) { FocusRequester() } }
    // 下面两个循环都在协程里跑,读的必须是**当前**这一份 requester:items.size 一变 remember 就换新表,
    // 捕获启动时那一份的话,旧表挂不上任何节点,requestFocus 次次抛、被 runCatching 吞掉,循环空转。
    val requesters by rememberUpdatedState(rowFocus)
    var focusedIdx by remember { mutableStateOf(0) }
    /** 现在持有焦点的那一项(只信控件自报,铁律 4);null = 菜单里没有。与 focusedIdx(回来落哪)分开(铁律 5)。 */
    var holder by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(nonce) {
        val i = focusedIdx.coerceIn(0, requesters.lastIndex)
        var frames = 0
        while (holder == null && frames < 60) {
            withFrameNanos { }
            runCatching { requesters[i].requestFocus() }
            frames++
        }
    }
    // **看门狗**(铁律 3):初始循环只管「打开 / nonce 变」那一拍,落地之后焦点再被清掉时它早已退出,
    // 没有人会再请求。守卫与 key 都是 holder == null(铁律 6)。每轮最多 60 帧:落地后再丢
    // (holder 由非 null 变回 null,key 翻转)自然重新武装,不是闩(铁律 7);也不会在请求注定
    // 落空时每帧空转到菜单关掉为止 —— 下面这种情况就是注定落空:
    // **触摸模式下两个循环都落不下**(2026-09-19 模拟器实测,M7「装包后约 4 s 按 MENU,菜单开着
    // 但焦点数为 0,第一下 DOWN 才落到第 1 项」最可能的根因:复现脚本非触摸模式下 0/47 次复现,
    // 触摸模式下 13/13 次复现)。菜单项用的是 foundation 的 clickable,它自带
    // FocusableInNonTouchMode(canFocus = inputMode != Touch);之前的指针事件(触摸屏、鼠标都算)
    // 让窗口进了触摸模式,这个状态跨冷启动带进新窗口,而 MENU 既不是导航键也不是打字键,不会让窗口
    // 离开触摸模式。第一下方向键才让框架退出触摸模式、把默认焦点给最上面那项,并吃掉这一下。
    LaunchedEffect(holder == null) {
        if (holder != null) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }   // 换项时 lost / got 可能分属相邻两帧,中间那一帧的 null 不算丢
        var frames = 0
        while (holder == null && frames < 60) {
            runCatching { requesters[focusedIdx.coerceIn(0, requesters.lastIndex)].requestFocus() }
            withFrameNanos { }
            frames++
        }
    }

    androidx.activity.compose.BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            // Step 3(spec §6 末条):浮层在场时压暗底下的首页。下面紧接着的 MenuBg 是不透明的整屏底,
            // 视觉上会完全盖住这层 scrim——两层都留着是为了跟其它浮层(设置页、选择器等)同一条规则
            // 对齐,并且这一层才是「首页被压暗」这件事真正的责任方,不依赖 MenuBg 恰好不透明这个细节。
            .background(GtvTokens.ScrimOverlay)
            .background(GtvTokens.MenuBg),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                MenuBanner(app = app, name = title ?: stringResource(R.string.menu_settings_title))
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(GtvLayout.MENU_ITEM_GAP.dp)) {
                    items.forEachIndexed { i, item ->
                        MenuPill(
                            item = item,
                            modifier = Modifier.focusRequester(rowFocus[i]),
                            onFocusChange = { got ->
                                // 得失顺序保护(同 HomeScreen.report):只有「本项仍是持有者」时 lost 才作废
                                if (got) { holder = i; focusedIdx = i } else if (holder == i) holder = null
                            },
                            isFirst = i == 0,
                            isLast = i == items.lastIndex,
                            showHint = showHints,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 左半:banner + 应用名。[app] 为 null(齿轮设置菜单、编辑页行菜单)时没有具体应用,只画 [name],
 * 而且它此时是整页标题(R44:32 sp 的 [GtvLayout.SETTINGS_TITLE_TEXT]),不是 12 sp 的图片注脚。
 */
@Composable
private fun MenuBanner(app: AppEntry?, name: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (app != null) {
            // banner 尺寸:参考图像素量测约 196×110dp,取 192×108dp。R59 之前直接借 LARGE 卡片档位(当时正是 192);
            // R59 把大档改成 153 之后改读自己的常量,菜单观感不随首页档位变(见 GtvLayout.MENU_BANNER_WIDTH)。
            val bannerWidth = GtvLayout.MENU_BANNER_WIDTH.dp
            val bannerHeight = (GtvLayout.MENU_BANNER_WIDTH * 9f / 16f).dp
            val bmp = app.card
            val fallback = app.fallbackColor
            val scheme = MaterialTheme.colorScheme
            // 取图逻辑复用自 AppCard.kt 的 Box 内容分支:有横幅铺满卡、方图标居中留边、
            // 都没有就回落纯色底——**这里不重复画文字**,应用名已经在下面单独一行。
            val container = when {
                fallback != null && bmp != null && !app.isWide -> Color(fallback)
                bmp != null -> Color.Transparent
                else -> scheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .size(bannerWidth, bannerHeight)
                    .clip(RoundedCornerShape(GtvLayout.CARD_CORNER.dp))
                    // R49:banner 与首页卡片同一档淡化(B4),同一张卡在首页与长按菜单里因此颜色一致
                    // (「主题化卡片」2026-09-23 删掉之后没有例外了,gtv spec R58)。
                    .gtvCardFade()
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null && app.isWide) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(bannerWidth, bannerHeight),
                    )
                } else if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(bannerHeight),
                    )
                }
            }
            Spacer(Modifier.height(GtvLayout.MENU_BANNER_NAME_GAP.dp))
        }
        // Ruling R44(owner 真机反馈 Round 10):「点击齿轮设置按钮进来后……左侧中文'设置'这两个字过于小了」。
        // 12 sp 是 Google 长按菜单里**应用 banner 的注脚**(docs/screenshots/gtv/16-app-longpress-menu.png),
        // 字小是因为上面有一整张图;没有图时(齿轮设置菜单、编辑页行菜单)这行字就是整页唯一的标题,
        // 改用与「UnitedU 设置」页大标题同一个常量 SETTINGS_TITLE_TEXT(32 sp,Google 二级页大标题)、
        // 同字重同颜色。有图的长按菜单保持 12 sp 注脚不变。
        val isPageTitle = app == null
        BasicText(
            text = name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = if (isPageTitle) {
                TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = Theme.EmphasisText,
                    fontSize = GtvLayout.SETTINGS_TITLE_TEXT.sp,
                    lineHeight = (GtvLayout.SETTINGS_TITLE_TEXT * 1.2f).sp,
                    textAlign = TextAlign.Center,
                )
            } else {
                TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = LocalThemeColors.current.highlight,
                    fontSize = GtvLayout.MENU_BANNER_NAME_TEXT.sp,
                    letterSpacing = GtvLayout.MENU_BANNER_NAME_LETTER_SPACING.sp,
                    textAlign = TextAlign.Center,
                )
            },
        )
    }
}

/**
 * 右半的一颗药丸。宽 [GtvLayout.MENU_ITEM_WIDTH],高至少 [GtvLayout.MENU_ITEM_HEIGHT](两行说明
 * 文字时按内容撑高,见 [showHint]),全圆角;聚焦填主题 accent、文字按亮度取对比色(不写死浅蓝——
 * accent 是用户选的,白/黑两个预设亮度几乎在两端,写死一种会在另一端不可读);未聚焦填
 * [GtvTokens.MenuItemIdle]。焦点边界(上下 Cancel 在首末项、左右恒 Cancel)与得失上报原样保留
 * 自换皮前的 MenuRow。
 *
 * @param showHint Ruling R17(终审 2026-09-20):true 时在标题下加一行 [MenuItem.hint],字号更小、
 *   颜色更淡(同一个文字色再乘一层透明度,聚焦/未聚焦两态都仍然可辨,不需要单独取色)。
 *   单行(false)时高度精确等于 [GtvLayout.MENU_ITEM_HEIGHT]——`heightIn(min = ...)` 换成两行前后
 *   数学上不改变单行的居中位置:内容 + 上下 padding 之和本来就小于这个下限,`contentAlignment`
 *   会在下限高度内居中,与原来固定 `.height(...)` 时完全一致,所以三个不传 [showHint] 的调用点
 *   (长按卡片菜单、编辑页的两个菜单)像素级零回归。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MenuPill(
    item: MenuItem,
    modifier: Modifier = Modifier,
    /** 得到 / 失去都报(铁律 4):GearMenu 的看门狗靠「失去」知道菜单里已经没有焦点。 */
    onFocusChange: (Boolean) -> Unit = {},
    isFirst: Boolean = false,
    isLast: Boolean = false,
    showHint: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    // Fix 3(owner 反馈 R2,2026-09-20):填色焦点(spec §0「四种焦点画法」之一)原来是瞬间切换——
    // `if (focused) accent else MenuItemIdle` 直接喂给 background,没有过渡。改用
    // animateColorAsState,时长与 gtvFocusStroke/gtvAppFocusFrame 共用同一对常量(owner 反馈
    // Round 4 起是 Google 实测的 card_focus/card_unfocus 150ms,见 GtvLayout.FOCUS_FADE_IN_MS
    // 的 KDoc——菜单项本身没有独立测量,是为了整条线焦点手感统一而借用同一个数字与同一条曲线
    // Theme.AppFocusEasing,如实记录不是又量到了菜单项专属的值)。textColor 不在这次修复范围
    // 内——它只在聚焦/未聚焦两态之间瞬时切换黑白对比色,文字本身不适合做透明度过渡(会有一瞬间
    // 对比度不够的中间态),这里只 animate 底色。
    val fill by animateColorAsState(
        targetValue = if (focused) accent else GtvTokens.MenuItemIdle,
        animationSpec = tween(
            durationMillis = if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS,
            easing = Theme.AppFocusEasing,
        ),
        label = "menuPillFill",
    )
    val textColor = if (focused) contrastingTextColor(accent) else Theme.MenuItemText
    // clickable() 默认的 indication 会在聚焦时叠一层持续的状态层(实测约 10% 黑,Material 的标准
    // focus state-layer opacity),把 accent 拉暗成另一个颜色——这块药丸的填色本身已经是完整的
    // 聚焦指示(未聚焦 MenuItemIdle → 聚焦纯 accent),不需要再叠一层,关掉才是真正的「填 accent」。
    val interactionSource = remember { MutableInteractionSource() }
    val hasHint = showHint && item.hint.isNotBlank()
    Box(
        modifier = modifier
            .width(GtvLayout.MENU_ITEM_WIDTH.dp)
            .heightIn(min = GtvLayout.MENU_ITEM_HEIGHT.dp)
            .focusProperties {
                if (isFirst) up = FocusRequester.Cancel
                if (isLast) down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .clip(RoundedCornerShape(percent = 50))
            .background(fill)
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(interactionSource = interactionSource, indication = null, onClick = item.action)
            .padding(horizontal = GtvLayout.MENU_ITEM_PADDING_H.dp, vertical = GtvLayout.MENU_ITEM_PADDING_V.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (hasHint) {
            Column(verticalArrangement = Arrangement.spacedBy(GtvLayout.MENU_ITEM_HINT_GAP.dp)) {
                MenuPillLabel(item.label, focused, textColor)
                BasicText(
                    text = item.hint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        fontWeight = FontWeight.Normal,
                        // 从属于标题的次要文字(Ruling R17「视觉上明确从属」):同一个文字色减透明度,
                        // 聚焦态(黑/白对比色)与未聚焦态(MenuItemText 灰)都天然算得出一个更淡的版本,
                        // 不需要再按亮度分别取一种「更淡的对比色」。
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = GtvLayout.MENU_ITEM_HINT_TEXT.sp,
                    ),
                )
            }
        } else {
            MenuPillLabel(item.label, focused, textColor)
        }
    }
}

/** [MenuPill] 的标题行,单行/两行两种布局共用,避免样式在两处漂移。 */
@Composable
private fun MenuPillLabel(label: String, focused: Boolean, textColor: Color) {
    BasicText(
        text = label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontFamily = Theme.Sans,
            fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
            fontSize = GtvLayout.MENU_ITEM_TEXT.sp,
        ),
    )
}

/** 按 WCAG 相对亮度选深/浅文字色,不写死一种——见 [MenuPill] 上的说明。 */
private fun contrastingTextColor(fill: Color): Color =
    if (fill.luminance() > 0.5f) Color.Black else Color.White
