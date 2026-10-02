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

/**
 * 菜单里的一项。[hint] 是 R69 之前的第二行说明,**R69 起不画**(单行药丸);[icon] 非 null 时例外(R163「加到桌面…」选一行):
 * 药丸画这个行图标(id 见 RowIcons.kt),[label] 可以是空串,小字写 [hint]。
 */
data class MenuItem(val label: String, val hint: String, val icon: String? = null, val action: () -> Unit)

/**
 * 菜单浮层。编辑页条目菜单、首页长按卡片菜单、编辑页行菜单共用这一份(齿轮菜单 R69 起换成设置页外壳)。
 *
 * **gtv 线 Task 8 换皮**(spec §6 / B9):整屏 [GtvTokens.MenuBg] 底,不再是 `Theme.DialogSurface`
 * 的小面板;左半是该应用的 banner 图 + 名字,右半是一列整宽药丸([GtvLayout.MENU_ITEM_WIDTH] ×
 * [GtvLayout.MENU_ITEM_HEIGHT] 起,全圆角)。菜单项内容(打开/卸载/修改标题/换卡片图/移动位置/
 * 从这一行移出)与焦点机制都原样不动——下面两个 `LaunchedEffect` 与
 * `BackHandler` 逐字保留自换皮前:nonce 初始循环退出判据是目标自报 `holder != null`(铁律 2,
 * 不信 `requestFocus()` 的返回值),`holder == null` 看门狗守卫与 key 同一表达式(铁律 6)、
 * 每次再丢焦点自动重新武装(铁律 7)。见 CLAUDE.md 焦点责任表「齿轮菜单 / 长按卡片菜单」一行。
 *
 * **R69(2026-09-23 设置页胶囊外壳)**:齿轮菜单第一层退役,换成设置页外壳的第一层(`SettingsShell`);
 * 这里只剩长按卡片菜单与编辑页的两个菜单三个调用点,都是单行药丸、不带说明(R17 的 `showHints` 随齿轮菜单
 * 一起搬去外壳:第一层 6 颗两行胶囊)。药丸本身抽到 `ShellCapsule.kt` 的 [MenuPill],外壳每一层共用同一颗。
 *
 * @param title 标题;null = 「设置」。长按菜单传该卡的显示名,行菜单传「分栏管理」。
 *   [app] 为 null 时(编辑页行菜单——没有对应单个应用)左半退化成只显示这个标题,
 *   不画 banner。
 * @param app 左半 banner 的取图来源;只取 [AppEntry.card] / [AppEntry.isWide] / [AppEntry.fallbackColor]
 *   三个字段,取图判断逻辑与 [AppCard] 一致(有横幅铺满 / 方图标居中留边 / 都没有回落纯色底)。
 *   名字仍由 [title] 给——调用方那份已经处理过改名覆盖、查不到时退回包名的兜底,这里不重复一遍。
 * @param eyebrow 页名上方的一行小字(R135):这页属于谁(「编辑桌面」「屏保图库」);只在没有 banner 时画。
 * @param body 页名下方的说明(R135):确认页写后果(「这一行的 3 个应用会从桌面移除,不会卸载」);只在没有 banner 时画。
 * @param icon 页名上方画的行图标 id(R163):这一页属于哪一行——行没有名字了,用它的图标认;只在没有 banner 时画。
 *
 * **R135(2026-09-30 外观轮)**:两按钮确认框 [ConfirmDialog] 也画成这一页(左边问题 + 后果,右边「取消 / 删除」两颗胶囊),
 * 与设置里「恢复默认」的确认层(R74)同一个样子;原来是屏幕中间的小面板 + 带描边的方按钮。
 * **淡出中的残影**([LocalPageGhost],R108 的约定):两个焦点循环让路、每颗胶囊 `canFocus = false`、不收返回键、点击不回调。
 */
@Composable
fun GearMenu(
    items: List<MenuItem>,
    onDismiss: () -> Unit,
    nonce: Int = 0,
    title: String? = null,
    app: AppEntry? = null,
    eyebrow: String? = null,
    body: String? = null,
    icon: String? = null,
) {
    val ghost = LocalPageGhost.current
    val rowFocus = remember(items.size) { List(items.size.coerceAtLeast(1)) { FocusRequester() } }
    // 下面两个循环都在协程里跑,读的必须是**当前**这一份 requester:items.size 一变 remember 就换新表,
    // 捕获启动时那一份的话,旧表挂不上任何节点,requestFocus 次次抛、被 runCatching 吞掉,循环空转。
    val requesters by rememberUpdatedState(rowFocus)
    var focusedIdx by remember { mutableStateOf(0) }
    /** 现在持有焦点的那一项(只信控件自报,铁律 4);null = 菜单里没有。与 focusedIdx(回来落哪)分开(铁律 5)。 */
    var holder by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(nonce, ghost) {
        if (ghost) return@LaunchedEffect
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
    LaunchedEffect(holder == null, ghost) {
        if (ghost || holder != null) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }   // 换项时 lost / got 可能分属相邻两帧,中间那一帧的 null 不算丢
        var frames = 0
        while (holder == null && frames < 60) {
            runCatching { requesters[focusedIdx.coerceIn(0, requesters.lastIndex)].requestFocus() }
            withFrameNanos { }
            frames++
        }
    }

    androidx.activity.compose.BackHandler(enabled = !ghost) { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            // R142:整屏页的氛围底(原来是纯色 MenuBg,下面还垫着一层半透明黑的 scrim)。现在菜单总在 OverlayStack 里,
            // 由那一层铺底(LocalBackdropProvided),这里不再画;scrim 也去掉了——它原来被不透明的底盖着看不见,
            // 底不画了它就会把垫底压暗一截。
            .pageBackdrop(),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                val name = title ?: stringResource(R.string.menu_settings_title)
                // 没有 banner(行菜单、确认页):与设置外壳没有预览的页同一个画法——路径小字、页名、说明。
                if (app == null) ShellTitle(path = eyebrow, title = name, icon = icon, extra = body?.let { { ShellBody(it) } })
                else MenuBanner(app = app, name = name)
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(GtvLayout.MENU_ITEM_GAP.dp)) {
                    items.forEachIndexed { i, item ->
                        MenuPill(
                            label = item.label,
                            onClick = if (ghost) ({}) else item.action,
                            modifier = Modifier.focusRequester(rowFocus[i]).focusProperties { if (ghost) canFocus = false },
                            onFocusChange = { got ->
                                // 得失顺序保护(同 HomeScreen.report):只有「本项仍是持有者」时 lost 才作废
                                if (got) { holder = i; focusedIdx = i } else if (holder == i) holder = null
                            },
                            isFirst = i == 0,
                            isLast = i == items.lastIndex,
                            // R163:带行图标的药丸(「加到桌面…」选一行)用 hint 当小字;其余项的 hint 是 R69 前的说明,不画
                            icon = item.icon,
                            hint = if (item.icon != null) item.hint else null,
                        )
                    }
                }
            }
        }
    }
}

/** 左半:banner + 应用名(长按卡片菜单、应用页菜单)。没有 banner 的页走 [ShellTitle],不经这里。 */
@Composable
private fun MenuBanner(app: AppEntry, name: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            fallback != null && bmp != null -> Color(fallback)  // R107:同 appCardContainer
            bmp != null -> Color.Transparent
            else -> scheme.surfaceVariant
        }
        Box(
            modifier = Modifier
                .size(bannerWidth, bannerHeight)
                .clip(RoundedCornerShape(GtvLayout.CARD_CORNER.dp))
                // R49:banner 与首页卡片同一档淡化(B4),同一张卡在首页与长按菜单里因此颜色一致
                // (「主题化卡片」2026-09-23 删掉之后没有例外了,gtv spec R58)。
                .gtvCardFade(LocalCardFade.current)
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
        // 12 sp 是 Google 长按菜单里**应用 banner 的注脚**(docs/screenshots/gtv/16-app-longpress-menu.png),
        // 字小是因为上面有一整张图(Round 5 实测);没有图的页走 ShellTitle 的 31 sp 页名(R44 → R134)。
        BasicText(
            text = name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = Type.caption.copy(
                fontWeight = FontWeight.Medium,
                color = LocalThemeColors.current.highlight,
                letterSpacing = GtvLayout.MENU_BANNER_NAME_LETTER_SPACING.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
