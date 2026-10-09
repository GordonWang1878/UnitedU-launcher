package com.uniteduone.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 「添加应用」列表(R83 起每项是小卡片 + 名字)。R165 前住在 EditScreen.kt 里,货架重写时原样搬来、改成 internal;
 * 焦点账本(逐项 requester + (nonce, candidates) 初始循环 + 正反都报的 focusedItem)一字未动。
 * 这是全应用唯一获准的可滚动容器(单列 LazyColumn,见 CLAUDE.md 焦点表「添加应用列表」一行)。
 */

/**
 * 选一个应用加进某行(R83 起:一列小应用卡片 + 名字)。列出机器上的应用,已在桌面上的不再重复列出;
 * 应用在上,电视设置与厂商系统工具在底部「系统工具」分组,只有裸 MAIN 的系统组件不列(分组规则见 PickerGroups.kt)。
 */
@Composable
internal fun AppPicker(
    nonce: Int,
    ctx: Context,
    exclude: Set<String>,
    onPick: (String) -> Unit,
    /** 加到哪一行(页名上方画这一行的图标,R163 起行没有名字);null = 不画。 */
    rowIcon: String? = null,
) {
    // 淡出中的残影(R108 的约定):不再请求焦点、每一项不可聚焦、点击不回调。
    val ghost = LocalPageGhost.current
    // 打开列表那一刻的基线:本次列表按打开前的时间戳标「新」,同时把时间戳推到现在(先算后写)。
    val seenAtBefore = remember { SettingsStore.read(ctx).newAppsSeenAt }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            SettingsStore.update(ctx) { it.copy(newAppsSeenAt = System.currentTimeMillis()) }
        }
    }
    // 候选只读名字 + 分组,不解码位图(卡片图由每一项上屏时按需读,见 PickerRow)。
    // null = 还在读。用 emptyList 当初值时,弹出的框第一眼就写着「没有可添加的应用了」,
    // 几百毫秒后才刷出列表 —— 看到这句话的人会直接按返回,认定功能坏了。
    // (按已安装包全量枚举要多做几十次包查询,这个窗口更明显。)
    val candidates by produceState<List<PickerCandidate>?>(initialValue = null, exclude) {
        value = withContext(Dispatchers.IO) {
            runCatching { Apps.pickerCandidates(ctx, exclude) }.getOrDefault(emptyList())
        }
    }
    // 同 HomeScreen:requestFocus 返回 void,只有目标自报 isFocused 才算真的落下
    // **每项一个 requester**:只挂在第 0 项时,LazyColumn 会在滚过十几项后把它回收,
    // requester 变成没有任何节点的悬空引用,requestFocus 抛异常被 runCatching 静默吞掉,
    // 循环空转 60 帧后放弃 —— 而这个浮层开着时看门狗必须让路,没有人会捞回来。
    // 丢焦点本身不会滚动列表,所以「上次拿到焦点的那一项」一定还在组合窗口里,挂它才安全。
    val rowFocus = remember(candidates?.size) {
        List((candidates?.size ?: 1).coerceAtLeast(1)) { FocusRequester() }
    }
    var focusedIdx by remember { mutableStateOf(0) }
    // 正反都报(铁律 4)。只报「得到」时,nonce 跳变而焦点根本没丢的情形下
    // requestFocus 落在已 Active 的节点上不会重新派发事件,循环空转 60 帧,
    // 期间每帧把焦点拽回原项 —— **恰好吞掉用户的一次方向键**。
    var focusedItem by remember { mutableStateOf<Int?>(null) }
    // **这个浮层必须自己负责焦点恢复**:编辑界面的看门狗第一行就是「浮层开着就返回」
    // (它必须让路,否则会去抢焦点),所以列表开着时丢了焦点没有别人会捞。
    // key 接 nonce —— 触发情形是真实发生过的那一种:列表开着时电视进系统屏保、
    // 或切到别的应用再回来。判据用 focusedItem(正反都报),已经有焦点时一次都不跑。
    LaunchedEffect(nonce, candidates, ghost) {
        if (ghost || candidates.isNullOrEmpty()) return@LaunchedEffect
        val i = focusedIdx.coerceIn(0, rowFocus.lastIndex)
        var frames = 0
        while (focusedItem == null && frames < 60) {
            withFrameNanos { }
            runCatching { rowFocus[i].requestFocus() }
            frames++
        }
    }
    val list = candidates.orEmpty()
    // 「系统工具」分组标题画在该组第一项里(不可聚焦,见 PickerRow 的 header)。
    val firstTool = list.indexOfFirst { it.group == PickerGroup.SYSTEM_TOOLS }

    // R135 换皮:与设置各层同一个版式——整屏 MenuBg,左边页名(上方画加到哪一行的图标,R163 前是一行小字写行名;下方写「正在读取 / 没有可添加的」),
    // 右边一列应用。此前是屏幕中间 460 dp 的小面板、标题 16 sp。列表本身(LazyColumn + 逐项 requester)未动。
    Box(
        Modifier
            .fillMaxSize()
            .focusGroup()   // 同 GearMenu:不圈起来焦点会跑到底下那一层
            .pageBackdrop(),   // R142
    ) {
        val status = when {
            candidates == null -> stringResource(R.string.edit_loading_apps)
            list.isEmpty() -> stringResource(R.string.edit_no_more_apps)
            else -> null
        }
        ShellScaffold(
            left = {
                ShellTitle(
                    path = null,
                    icon = rowIcon,
                    title = stringResource(R.string.edit_add_app_title),
                    extra = status?.let { { ShellBody(it) } },
                )
            },
            right = {
                // 仍是 LazyColumn(2026-09-11 起真机验证过的写法:逐项 requester + 四向锁边界),R83 只换每一项的画法。
                // **聚焦放大不被裁**:LazyColumn 在纵向上按自身边界硬裁,而 bringIntoView 只保证「聚焦节点的布局框」
                // 完整可见——所以聚焦节点是整行(不是卡片本身),行内上下各留一个聚焦溢出量(PickerRow 的 padV):
                // 放大 + 描边后的卡片永远落在行的布局框里,行被带进视窗时它也就完整可见。横向 LazyColumn 本来就外扩 15dp
                // 再裁,行内左右同样各留一个溢出量。代价同图片网格:R28 的柔光(纯绘制、60dp)在列表上下边被硬切。
                // 项数少于一屏时整列竖直居中(与胶囊列一样坐在右半屏正中);多于一屏时照常从头滚。
                LazyColumn(
                    modifier = Modifier.width(GtvLayout.PICKER_LIST_WIDTH.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = GtvLayout.PICKER_LIST_PAD_V.dp),
                ) {
                    itemsIndexed(list, key = { _, c -> c.app.packageName }) { i, c ->
                        PickerRow(
                            app = c.app,
                            header = if (i == firstTool) stringResource(R.string.edit_picker_system_tools) else null,
                            modifier = Modifier
                                .focusRequester(rowFocus[i.coerceIn(0, rowFocus.lastIndex)])
                                .focusProperties { if (ghost) canFocus = false },
                            onFocusChange = { got ->
                                if (got) { focusedItem = i; focusedIdx = i }
                                else if (focusedItem == i) focusedItem = null
                            },
                            isFirst = i == 0,
                            isLast = i == list.lastIndex,
                            // 候选本来就不在桌面上(pickerCandidates 已经把 layout.json 里的包 exclude 掉了)。
                            isNew = isNewApp(c.app.firstInstallTime, seenAtBefore, onLayout = false),
                            onClick = { if (!ghost) onPick(c.app.packageName) },
                        )
                    }
                }
            },
        )
    }
}

/** 「添加应用」列表的小卡片几何(R83):宽 [GtvLayout.PICKER_CARD_WIDTH]、16:9,圆角与首页卡片同。 */
private val PickerCardMetrics = CardMetrics(
    cardWidth = GtvLayout.PICKER_CARD_WIDTH.dp,
    cardHeight = (GtvLayout.PICKER_CARD_WIDTH * 9f / 16f).dp,
    cardCorner = GtvLayout.CARD_CORNER.dp,
    cardSpacing = 0.dp,
    rowVerticalPad = 0.dp,
    titleGap = 0.dp,
    titleLine = 0.dp,
    titleSize = Type.BODY.sp,
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PickerRow(
    app: AppEntry,
    /** 非 null = 这一项是「系统工具」组的第一项,标题画在它上方(同一个聚焦节点里,见下)。 */
    header: String?,
    modifier: Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    isFirst: Boolean = false,
    isLast: Boolean = false,
    isNew: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val highlight = LocalThemeColors.current.highlight
    val accent = LocalThemeColors.current.accent
    val ctx = LocalContext.current
    val metrics = PickerCardMetrics
    // 卡片图:与首页同一套选图(Apps.pickerCard → entryOf),IO 线程读、按包名的 LRU;初值取缓存,
    // 滚回来的项首帧就有图。null = 还在读(只画底色,不先闪一下文字回落);读不到则用无图的 app → 文字回落。
    val face by produceState(Apps.cachedPickerCard(app.packageName), app.packageName) {
        value = withContext(Dispatchers.IO) { runCatching { Apps.pickerCard(ctx, app.packageName) }.getOrNull() } ?: app
    }
    // 行内上下 / 左右各留一个聚焦溢出量:聚焦节点是整行,放大 + 描边后的卡片必须落在行的布局框里(见 AppPicker 的注释)。
    val padV = kotlin.math.ceil(GtvLayout.appFocusOverflow(metrics.cardHeight.value)).dp
    val padH = kotlin.math.ceil(GtvLayout.appFocusOverflow(metrics.cardWidth.value)).dp
    Column(
        modifier = modifier
            // 聚焦行浮到邻居上面:柔光向下铺开时不被下一项的卡片盖住(同首页 AppCard 的 zIndex)。
            .zIndex(if (focused) 1f else 0f)
            .fillMaxWidth()
            // 四向显式锁住边界。
            // **注意:这不是在修一个已复现的故障。**静态复审推断「这里按左右或越界会整棵树失焦」,
            // 2026-09-11 真机实测**四个方向全部原地停住**,推断没有成立——大概率是外层的
            // LazyColumn(可滚动容器)挡住了越界的焦点搜索,也就是铁律第 1 条里那个「挡住纵向
            // 外出」的性质在这里反而起了保护作用。锁保留,理由改为:
            // **不要把正确性寄托在某个容器的副作用上**,尤其这个副作用正是别处的故障源。
            // 真要出事时代价很高:编辑界面的看门狗第一行是「浮层开着就返回」,
            // 而选择器自己的初始焦点循环落地后被 landed 闩死、永不重试 —— 没有人会把焦点捞回来。
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                if (isFirst) up = FocusRequester.Cancel
                if (isLast) down = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            // 聚焦反馈全部交给卡片(放大 + 描边 + 柔光)与名字变色,不要 clickable 默认的整行灰色蒙层。
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        // 分组标题放在该组第一项的聚焦节点里:从下往上翻回这一项时,bringIntoView 带进视窗的是整行,
        // 标题跟着露出来;单独做成一项的话,它会停在视窗上边外面,系统工具看上去没有标题。
        if (header != null) {
            BasicText(
                header,
                modifier = Modifier.padding(start = padH, top = 14.dp, bottom = 4.dp),
                style = Type.section,
            )
        }
        Row(
            Modifier.padding(horizontal = padH, vertical = padV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GtvLayout.PICKER_CARD_NAME_GAP.dp),
        ) {
            val shown = face
            Box(
                Modifier
                    // 与首页卡片同一套聚焦画法与淡化(R49/R70),顺序由 gtvFocusFrameOverFade 固定
                    .gtvFocusFrameOverFade(focused, accent, metrics.cardCorner, fade = LocalCardFade.current)
                    .size(metrics.cardWidth, metrics.cardHeight)
                    .clip(RoundedCornerShape(metrics.cardCorner))
                    .background(
                        if (shown == null) androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant
                        else appCardContainer(shown, shown.fallbackColor?.let { Color(it) }),
                    ),
            ) {
                if (shown != null) AppCardImage(shown.copy(label = app.label), metrics)
            }
            BasicText(
                text = app.label.ifBlank { app.packageName },
                // weight(fill = false):名字很长时先挤自己(省略号),不把「新」标推出对话框右边缘;
                // fill = false 保证短名字仍然紧挨着标,不会中间空一大段。
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                // 未聚焦的名字压成次要灰、聚焦行提到主题 highlight:光靠近白与近白的差别,
                // 模拟器截图上认不出哪一行是焦点行(卡片放大在左边,读名字的人眼睛在右边)。
                style = Type.label.copy(
                    color = if (focused) highlight else Ink.Label,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                ),
            )
            if (isNew) Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(highlight.copy(alpha = 0.22f)).padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                BasicText(text = stringResource(R.string.edit_badge_new), style = Type.micro.copy(color = highlight, fontWeight = FontWeight.Medium))
            }
        }
    }
}
