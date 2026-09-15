package com.uniteduone.launcher

import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 卡片档位:大/中/小 → 每行张数(门 1 Gordon 定 8/6/5;大=5,中=6,小=8)。
private val CARDS_PER_ROW_BY_SIZE = intArrayOf(5, 6, 8)
// 待机时长档位(与 Settings.VALID_IDLE_AFTER_MS 同集,顺序即左右键顺序)。
private val IDLE_AFTER_OPTIONS = longArrayOf(0L, 60_000L, 180_000L, 300_000L, 600_000L)

// 固定行高 —— **渲染高度必须和这里逐一相等**,否则下面自算的纵向位移会算错、
// 焦点行可能被推出屏幕(HomeScreen 用 Theme 里的实测尺寸算位移,同一招)。
private val H_TITLE = 56.dp
private val H_HEADER = 30.dp
private val H_CTRL = 46.dp
// 焦点行底部至少离屏幕底这么远,不够就整体上移(与 HomeScreen.BottomKeepout 同义)。
private val BOTTOM_KEEPOUT = 22.dp

private const val LOG_TAG = "UnitedU"

private enum class CtrlKind { SEGMENTED, TOGGLE, SWATCH }

/** 一个可聚焦控件行的描述:标签、类型、选项显示文案、当前选中项、以及「选中第 i 项」的动作。 */
private class Ctrl(
    val labelRes: Int,
    val kind: CtrlKind,
    val options: List<String>,   // SWATCH 时为空,选项来自 ThemePresets
    val count: Int,              // 选项个数(SWATCH = 预设数)
    val selected: Int,
    val onSelect: (Int) -> Unit,
)

/** 纵向布局的一格:标题 / 分组标题(不可聚焦)/ 控件(可聚焦,ctrlIndex 指向 controls)。 */
private sealed interface Elem { val height: Dp }
private data object TitleElem : Elem { override val height = H_TITLE }
private data class HeaderElem(val titleRes: Int) : Elem { override val height = H_HEADER }
private data class ControlElem(val ctrlIndex: Int) : Elem { override val height = H_CTRL }

/**
 * UnitedU 设置页:从齿轮菜单打开的全屏浮层,分组带标题(布局 / 主题 / 时钟 / 待机)。
 * 读写走 [SettingsStore],改一下存一下(门 4:M2 只做 UI + 读写,让设置真正生效是 D–I)。
 *
 * 焦点账本照 [HomeScreen] 的看门狗:焦点是否落下只信控件自报 `isFocused`(铁律 2、4);
 * 焦点丢了由看门狗兜底、不靠「猜时刻补请求」(铁律 3);看门狗的守卫与 key 成对(铁律 6);
 * 用「记住的当前行」自愈式恢复,不用一次性布尔闩(铁律 7)。左右键改值全程**消费掉**,
 * 焦点一格都不横向移动 —— 这是「按左右后焦点消失、遥控器全死」那类回归的正解(铁律 1 同源)。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(onExit: () -> Unit, focusNonce: Int = 0) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(SettingsStore.read(ctx)) }

    // 改一下存一下,同步落盘——照抄 [Layout.write] 的姿势,不像早期版本那样丢去
    // scope.launch { withContext(Dispatchers.IO) {...} } 异步写。文件是几百字节的扁平
    // JSON,主线程写是亚毫秒级,不会卡;换成同步是为了堵 Task G 复审揪出的一个真实竞态:
    // 「改完立刻按返回」时,leaveSettings() → revision++ → SettingsStore.read() 在主线程
    // 同步跑,如果这边写盘还在后台协程里排队,首页读到的就是改之前的旧值。write 返回
    // false 是存储故障,只 Log、不崩(任务要求);下次开机会读回旧值,但当前会话内的
    // 改动仍在内存里的 s 生效。
    fun update(newS: Settings) {
        s = newS
        val ok = SettingsStore.write(ctx, newS)
        if (!ok) Log.w(LOG_TAG, "settings.json 写入失败,改动只留在内存里")
    }

    // ---- 7 个可聚焦控件的描述,顺序即 ctrlIndex(0..6),看门狗/位移都按它索引 ----
    val onLabels = listOf(stringResource(R.string.settings_off), stringResource(R.string.settings_on))
    val cardSizeLabels = listOf(
        stringResource(R.string.settings_card_large),
        stringResource(R.string.settings_card_medium),
        stringResource(R.string.settings_card_small),
    )
    val idleAfterLabels = listOf(
        stringResource(R.string.settings_idle_off),
        stringResource(R.string.settings_idle_minutes, 1),
        stringResource(R.string.settings_idle_minutes, 3),
        stringResource(R.string.settings_idle_minutes, 5),
        stringResource(R.string.settings_idle_minutes, 10),
    )
    val idleContentLabels = listOf(
        stringResource(R.string.settings_idle_clock),
        stringResource(R.string.settings_idle_black),
        stringResource(R.string.settings_idle_nofade),
    )

    val controls: List<Ctrl> = listOf(
        // 0 卡片大小 大/中/小 → 5/6/8
        Ctrl(R.string.settings_card_size, CtrlKind.SEGMENTED,
            options = cardSizeLabels, count = 3,
            selected = CARDS_PER_ROW_BY_SIZE.indexOf(s.cardsPerRow).let { if (it < 0) 1 else it },
            onSelect = { i -> update(s.copy(cardsPerRow = CARDS_PER_ROW_BY_SIZE[i])) }),
        // 1 卡片标题
        Ctrl(R.string.settings_show_titles, CtrlKind.TOGGLE,
            options = onLabels, count = 2,
            selected = if (s.showTitles) 1 else 0,
            onSelect = { i -> update(s.copy(showTitles = i == 1)) }),
        // 2 主题色 swatch
        Ctrl(R.string.settings_theme_color, CtrlKind.SWATCH,
            options = emptyList(), count = ThemePresets.all.size,
            selected = ThemePresets.indexOf(s.themePresetId),
            onSelect = { i -> update(s.copy(themePresetId = ThemePresets.all[i].id)) }),
        // 3 跟随壁纸主色
        Ctrl(R.string.settings_follow_wallpaper, CtrlKind.TOGGLE,
            options = onLabels, count = 2,
            selected = if (s.followWallpaperColor) 1 else 0,
            onSelect = { i -> update(s.copy(followWallpaperColor = i == 1)) }),
        // 4 显示日期
        Ctrl(R.string.settings_show_date, CtrlKind.TOGGLE,
            options = onLabels, count = 2,
            selected = if (s.showDate) 1 else 0,
            onSelect = { i -> update(s.copy(showDate = i == 1)) }),
        // 5 待机时长 关/1/3/5/10 分
        Ctrl(R.string.settings_idle_after, CtrlKind.SEGMENTED,
            options = idleAfterLabels, count = 5,
            selected = IDLE_AFTER_OPTIONS.indexOf(s.idleAfterMs).let { if (it < 0) 2 else it },
            onSelect = { i -> update(s.copy(idleAfterMs = IDLE_AFTER_OPTIONS[i])) }),
        // 6 待机显示 时钟/全黑/不淡出
        Ctrl(R.string.settings_idle_content, CtrlKind.SEGMENTED,
            options = idleContentLabels, count = 3,
            selected = IdleContent.entries.indexOf(s.idleContent).coerceAtLeast(0),
            onSelect = { i -> update(s.copy(idleContent = IdleContent.entries[i])) }),
    )
    val ctrlCount = controls.size

    // 纵向布局(渲染 + 位移共用这一份,避免两处漂移)。分组标题不可聚焦、夹在控件之间,
    // 上下键靠下面的 up/down 显式落点跨过它们。
    val order: List<Elem> = remember {
        listOf(
            TitleElem,
            HeaderElem(R.string.settings_group_layout),
            ControlElem(0), ControlElem(1),
            HeaderElem(R.string.settings_group_theme),
            ControlElem(2), ControlElem(3),
            HeaderElem(R.string.settings_group_clock),
            ControlElem(4),
            HeaderElem(R.string.settings_group_standby),
            ControlElem(5), ControlElem(6),
        )
    }
    // 每个控件的顶部 Y(dp),从 order 折出来 —— 与渲染同源。
    val controlTop: List<Dp> = remember {
        val tops = arrayOfNulls<Dp>(ctrlCount)
        var y = 0.dp
        for (e in order) {
            if (e is ControlElem) tops[e.ctrlIndex] = y
            y += e.height
        }
        tops.map { it ?: 0.dp }
    }

    // 每行一个 requester,挂在对应控件上(铁律 3:逐项挂,恢复才有地方落)。
    val rowFocus = remember { List(ctrlCount) { FocusRequester() } }
    // **谁持有焦点,只信控件自报**(铁律 2、4)。null = 整棵树没有任何控件持有焦点。
    var focusedCell by remember { mutableStateOf<Int?>(null) }
    // 「当前行」跟着真实焦点走 —— 焦点丢了时看门狗把它送回这一行。
    var focusedRow by remember { mutableStateOf(0) }
    fun report(idx: Int, got: Boolean) {
        if (got) { focusedCell = idx; focusedRow = idx }
        else if (focusedCell == idx) focusedCell = null
    }

    // **焦点看门狗**(与 HomeScreen 同一套判据,单轴版)。
    // 覆盖三种情形:①初次进入(focusedCell 从来是 null → 落到第 0 行);
    // ②导航/重组途中某控件节点被销毁导致焦点没了 → 送回「记住的当前行」;
    // ③从别的应用返回(onResume 里 focusNonce++,实测那时整棵树没有焦点)→ 同样送回当前行。
    // 守卫 `focusedCell != null` 本身就是 key(铁律 6);focusNonce 也是 key。
    LaunchedEffect(focusNonce, focusedCell) {
        if (focusedCell != null) return@LaunchedEffect
        // D-pad 换行时,旧控件先报 null、新控件下一帧才报 got —— 中间那一帧的 null 不算「丢了」。
        // 等几帧,别在间隙里抢焦点(HomeScreen 同一处理,防「按方向键时高亮闪一下」)。
        repeat(3) { withFrameNanos {} }
        if (focusedCell != null) return@LaunchedEffect
        val target = rowFocus[focusedRow.coerceIn(0, rowFocus.lastIndex)]
        var frames = 0
        // requestFocus() 返回 Unit,只有一个节点都没挂上才抛;所以**不能**靠它判断落没落下,
        // 只有目标自报 isFocused(focusedCell 变非 null)才算成功(铁律 2)。
        while (focusedCell == null && frames < 60) {
            withFrameNanos { }
            runCatching { target.requestFocus() }
            frames++
        }
    }

    // 返回键关闭浮层。走 Compose 的 BackHandler(与各选择器浮层一致):它比 MainActivity 那个
    // 常开回调后注册,浮层在时优先接管;关闭后 MainActivity 会 focusNonce++ 让首页重新拿回焦点。
    androidx.activity.compose.BackHandler { onExit() }

    // 内容可能高于屏幕(7 控件 + 4 标题 + 标题栏 ≈ 498dp)。**绝不加滚动容器**,
    // 自己算纵向位移:焦点行底部快贴屏幕底时,整体上移刚好让它留在可视区(HomeScreen 同一招)。
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val fRow = focusedRow.coerceIn(0, ctrlCount - 1)
    val focusedBottom = controlTop[fRow] + H_CTRL
    val overflow = focusedBottom + BOTTOM_KEEPOUT - screenH
    val shift by animateDpAsState(
        targetValue = if (overflow > 0.dp) -overflow else 0.dp,
        label = "settingsShift",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.EditScreenBackground),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(640.dp)
                // unbounded:允许内容高于屏幕(理由同 HomeScreen 那个 Column);位移自己加。
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .offset(y = shift)
                .padding(horizontal = 20.dp),
        ) {
            order.forEach { e ->
                when (e) {
                    is TitleElem -> Box(
                        Modifier.fillMaxWidth().height(H_TITLE),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column {
                            BasicText(
                                text = stringResource(R.string.settings_title),
                                style = TextStyle(
                                    fontFamily = Theme.Sans,
                                    fontWeight = FontWeight.Medium,
                                    color = Theme.EmphasisText,
                                    fontSize = 22.sp,
                                ),
                            )
                            Spacer(Modifier.height(4.dp))
                            BasicText(
                                text = stringResource(R.string.menu_back_to_close),
                                style = TextStyle(
                                    fontFamily = Theme.Sans,
                                    color = Theme.FooterHintText,
                                    fontSize = 11.sp,
                                ),
                            )
                        }
                    }

                    is HeaderElem -> Box(
                        Modifier.fillMaxWidth().height(H_HEADER),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        BasicText(
                            text = stringResource(e.titleRes),
                            style = TextStyle(
                                fontFamily = Theme.Sans,
                                fontWeight = FontWeight.Medium,
                                color = Theme.ChampagneGold,
                                fontSize = 12.sp,
                                letterSpacing = 1.5.sp,
                            ),
                            modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
                        )
                    }

                    is ControlElem -> {
                        val k = e.ctrlIndex
                        SettingRow(
                            ctrl = controls[k],
                            focusRequester = rowFocus[k],
                            upReq = if (k > 0) rowFocus[k - 1] else null,
                            downReq = if (k < ctrlCount - 1) rowFocus[k + 1] else null,
                            onFocusChange = { got -> report(k, got) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一个可聚焦控件行。焦点账本的原子单位:
 * - `focusRequester` 挂在这一格上,看门狗/上下导航都靠它落点;
 * - 上下键交给焦点系统(显式 up/down 落点跨过分组标题;到边界锁 Cancel,防整棵树失焦);
 * - **左右键在 onKeyEvent 里全部消费**(返回 true),只在 KeyDown 那一下改值 —— 焦点一格不横移。
 *   到边界(clamp 不再变化)也照样消费:少了这一下,边界处左右键会落到焦点系统、
 *   找不到候选就把整棵树的焦点丢掉(「按左右后遥控器全死」)。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SettingRow(
    ctrl: Ctrl,
    focusRequester: FocusRequester,
    upReq: FocusRequester?,
    downReq: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    fun step(delta: Int) {
        val next = (ctrl.selected + delta).coerceIn(0, ctrl.count - 1)
        if (next != ctrl.selected) ctrl.onSelect(next)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(H_CTRL)
            .focusRequester(focusRequester)
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                up = upReq ?: FocusRequester.Cancel
                down = downReq ?: FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .onKeyEvent { ke ->
                when (ke.key) {
                    Key.DirectionLeft -> { if (ke.type == KeyEventType.KeyDown) step(-1); true }
                    Key.DirectionRight -> { if (ke.type == KeyEventType.KeyDown) step(+1); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (focused) Brush.horizontalGradient(
                        listOf(Theme.Champagne.copy(alpha = 0.12f), Color.Transparent),
                    ) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)),
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 聚焦时左侧竖条(与齿轮菜单一致,让焦点更显眼)。
            Box(
                Modifier
                    .width(2.5.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (focused) Theme.Champagne else Color.Transparent),
            )
            Spacer(Modifier.width(12.dp))
            BasicText(
                text = stringResource(ctrl.labelRes),
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                    color = if (focused) Theme.EmphasisText else Theme.MenuItemText,
                    fontSize = 15.sp,
                ),
                modifier = Modifier.width(150.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                when (ctrl.kind) {
                    CtrlKind.SWATCH -> SwatchControl(selected = ctrl.selected, rowFocused = focused)
                    else -> SegmentedControl(
                        options = ctrl.options, selected = ctrl.selected, rowFocused = focused,
                    )
                }
            }
        }
    }
}

/** 分段选择器 / 开关(开关就是两段「关|开」)。选中项高亮;行聚焦时选中项更亮(香槟底)。 */
@Composable
private fun SegmentedControl(options: List<String>, selected: Int, rowFocused: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, opt ->
            val isSel = i == selected
            val bg = when {
                isSel && rowFocused -> Theme.Champagne
                isSel -> Theme.Champagne.copy(alpha = 0.22f)
                else -> Theme.UnfocusedSurface
            }
            val fg = when {
                isSel && rowFocused -> Theme.Background   // 亮香槟底上用深色字
                isSel -> Theme.EmphasisText
                else -> Theme.SecondaryText
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(bg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = opt,
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        fontWeight = if (isSel) FontWeight.Medium else FontWeight.Normal,
                        color = fg,
                        fontSize = 13.sp,
                    ),
                )
            }
        }
    }
}

/** 主题色 swatch:6 个色点,选中项加环;行聚焦时环变香槟色(焦点 + 选中都清楚)。 */
@Composable
private fun SwatchControl(selected: Int, rowFocused: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemePresets.all.forEachIndexed { i, preset ->
            val isSel = i == selected
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(if (isSel) 26.dp else 21.dp)
                        .clip(CircleShape)
                        .background(preset.color)
                        .then(
                            if (isSel) Modifier.border(
                                width = if (rowFocused) 3.dp else 2.dp,
                                color = if (rowFocused) Theme.Champagne else Theme.EmphasisText,
                                shape = CircleShape,
                            ) else Modifier,
                        ),
                )
            }
        }
    }
}
