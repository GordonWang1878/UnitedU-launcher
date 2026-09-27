package com.uniteduone.launcher

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **所有应用页**(R90,Gordon 2026-09-27 定;照 Google TV 的「应用」页):顶栏「应用」胶囊打开。整屏 `MenuBg` 底,
 * 标题「应用」,下面一张网格放「添加应用」列表同一口径([Apps.pickerCandidates] / [pickerGroupOf])的全部应用——
 * 「应用」分组在上,「系统工具」分组放最后并带分组标题;两组各按名字排(改过名的按改过的名字)。**已在桌面上的也列出**。
 * 卡片就是首页的 [AppCard](卡片图口径 R88:自定义图 → 横幅 → 图标 + 边缘色底),小档、一行 [AppsPageLayout.COLUMNS] 张、带名字。
 * 确定 = 打开;长按 / MENU = 「打开 / 加到桌面…」菜单(MainActivity 识别,同首页长按)。
 *
 * **铁律 1**:网格不用任何可滚动容器——纵向位移自己算([appsPageScroll]),`wrapContentHeight(unbounded)` + `offset`,
 * 与首页 / 图片网格同一招;所有卡片都组合(应用有限,A95L 约 40 个)。
 *
 * **焦点账本**(铁律 2–7,CLAUDE.md 焦点责任表「所有应用页」一行):
 * - 逐项 requester;**目标** [focusedIdx] 与**持有者** `holder` 分开(铁律 5),目标只在「不在还原中」时跟着自报的焦点走;
 * - `restoring` 从 ON_PAUSE 起冻结(打开一个应用再按返回回来时,Compose 会抢先把焦点给第一张卡),由定位效果在前台落地后放开;
 * - 定位效果 key `(nonce, covered, requesters)`,退出判据是目标自报(铁律 2);`holder == null` 看门狗(3 帧宽限、60 帧封顶,
 *   守卫 `covered` / `restoring` / `holder == null` 全在 key 里,铁律 6);
 * - [covered]:长按菜单盖在上面时让路,菜单关掉 `focusNonce++` 后接回同一张卡。
 */

/** 网格里的一行。标题 / 分组标题不可聚焦,只占高度。 */
sealed interface AppsLine {
    data object Title : AppsLine
    data object ToolsHeader : AppsLine
    /** 一行卡片:平铺列表里从 [first] 起的 [count] 张。 */
    data class Cards(val first: Int, val count: Int) : AppsLine
}

/** 所有应用页的几何(dp,纯数值,JVM 可测)。 */
object AppsPageLayout {
    /** 一行 6 张小档卡:6 × 122 + 5 × 20 = 832 dp,左 58 dp 基准线起,右缘 890,聚焦放大 + 描边后仍在 960 dp 屏内。 */
    const val COLUMNS = 6
    val CARD_SIZE = GtvCardSize.SMALL
    /** 标题行顶到屏幕顶。 */
    const val PAGE_TOP = 36f
    /** 标题行高(32 sp 页名 + 下方留白)。 */
    const val TITLE_LINE = 60f
    /** 「系统工具」分组标题行高(18 sp + 上方留白,与上一组隔开)。 */
    const val HEADER_LINE = 44f
    /** 行与行之间的间距(卡片行自己上下还各有 [GtvLayout.FOCUS_OUTSET] + [GtvLayout.FOCUS_STROKE] 的聚焦留白)。 */
    const val LINE_GAP = 6f
    /** 焦点行离屏幕上 / 下边至少留这么多(翻页后)。 */
    const val EDGE_MARGIN = 24f

    /** 卡片行上下各留的聚焦留白(与首页 `Theme.gtvCardMetrics` 的 rowVerticalPad 同一个数)。 */
    val rowPad: Float get() = GtvLayout.FOCUS_OUTSET + GtvLayout.FOCUS_STROKE

    /** 卡片行高 = 上下留白 + 卡高 + 名字(标题间距 + 一行字)。 */
    fun cardRowHeight(): Float = 2 * rowPad + GtvLayout.cardHeight(CARD_SIZE) + GtvLayout.titleHeight(CARD_SIZE, true)

    fun heightOf(line: AppsLine): Float = when (line) {
        AppsLine.Title -> TITLE_LINE
        AppsLine.ToolsHeader -> HEADER_LINE
        is AppsLine.Cards -> cardRowHeight()
    }
}

/**
 * 网格的行(纯函数):标题 → 「应用」组每 [columns] 张一行 → 有系统工具时「系统工具」分组标题 + 每 [columns] 张一行。
 * 平铺下标:应用组在前 `0 until apps`,系统工具接在后面。
 */
internal fun appsPageLines(apps: Int, tools: Int, columns: Int = AppsPageLayout.COLUMNS): List<AppsLine> {
    val c = columns.coerceAtLeast(1)
    val out = mutableListOf<AppsLine>(AppsLine.Title)
    for (start in 0 until apps step c) out += AppsLine.Cards(start, minOf(c, apps - start))
    if (tools > 0) {
        out += AppsLine.ToolsHeader
        for (start in 0 until tools step c) out += AppsLine.Cards(apps + start, minOf(c, tools - start))
    }
    return out
}

/** 每一行的顶边(dp,相对内容顶)。 */
internal fun appsLineTops(lines: List<AppsLine>): List<Float> {
    var y = AppsPageLayout.PAGE_TOP
    return lines.map { l -> y.also { y += AppsPageLayout.heightOf(l) + AppsPageLayout.LINE_GAP } }
}

/** 第 [item] 张卡在哪一行;不在任何卡片行里 → -1。 */
internal fun appsLineOf(lines: List<AppsLine>, item: Int): Int =
    lines.indexOfFirst { it is AppsLine.Cards && item >= it.first && item < it.first + it.count }

/**
 * 纵向位移(纯函数,dp,≥ 0 = 内容上移多少)。焦点行已经完整可见(上下各留 [AppsPageLayout.EDGE_MARGIN])就不动;
 * 往上出界 → 焦点行贴上边;往下出界 → 焦点行贴下边。**焦点行上面紧挨着标题 / 分组标题时连它一起露出**
 * (走到第一行 = 回到顶;走到系统工具第一行 = 看得见「系统工具」四个字)。夹在 0 与「最后一行贴底」之间。
 */
internal fun appsPageScroll(lines: List<AppsLine>, focusedLine: Int, current: Float, screenHeight: Float): Float {
    if (focusedLine !in lines.indices) return current
    val tops = appsLineTops(lines)
    val m = AppsPageLayout.EDGE_MARGIN
    val startLine = if (focusedLine > 0 && lines[focusedLine - 1] !is AppsLine.Cards) focusedLine - 1 else focusedLine
    val blockTop = if (startLine == 0) 0f else tops[startLine] - m
    val blockBottom = tops[focusedLine] + AppsPageLayout.heightOf(lines[focusedLine]) + m
    val total = tops.last() + AppsPageLayout.heightOf(lines.last()) + m
    val maxScroll = (total - screenHeight).coerceAtLeast(0f)
    val next = when {
        blockTop < current -> blockTop
        blockBottom > current + screenHeight -> blockBottom - screenHeight
        else -> current
    }
    return next.coerceIn(0f, maxScroll)
}

/** 页面数据:平铺的全部候选(应用组在前、系统工具在后,各按显示名排)与应用组张数。 */
data class AppsPageData(val items: List<PickerCandidate>, val appCount: Int)

/**
 * 候选 → 页面数据(纯函数):显示名换成 titles.json 里改过的名字(与首页卡片标题同一份),再按显示名排序
 * ([orderPickerCandidates]:应用组在上、系统工具在下)。
 */
internal fun appsPageData(candidates: List<PickerCandidate>, titles: Map<String, String>): AppsPageData {
    val named = candidates.map { c ->
        titles[c.app.packageName]?.takeIf { it.isNotBlank() }?.let { c.copy(app = c.app.copy(label = it)) } ?: c
    }
    val ordered = orderPickerCandidates(named)
    return AppsPageData(ordered, ordered.count { it.group == PickerGroup.APPS })
}

/** IO 线程:全部候选(不排除桌面上已有的)+ 自定义标题。 */
internal fun loadAppsPage(ctx: Context): AppsPageData = appsPageData(
    runCatching { Apps.pickerCandidates(ctx, exclude = emptySet()) }.getOrDefault(emptyList()),
    runCatching { Titles.read(ctx) }.getOrDefault(emptyMap()),
)

/**
 * @param onFocusedApp 现在持有焦点的那一张(得到报、失去报 null;离开组合报 null)——MainActivity 长按 / MENU 据此弹菜单。
 *   报的卡片图取进程内缓存([Apps.cachedPickerCard]),菜单左半 banner 用。
 */
@Composable
fun AppsPage(
    nonce: Int,
    covered: Boolean,
    revision: Int,
    onOpen: (AppEntry) -> Unit,
    onFocusedApp: (AppEntry?) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val data by produceState<AppsPageData?>(null, revision) {
        value = withContext(Dispatchers.IO) { loadAppsPage(ctx) }
    }
    androidx.activity.compose.BackHandler { onBack() }
    val items = data?.items.orEmpty()
    val lines = remember(data) { data?.let { appsPageLines(it.appCount, it.items.size - it.appCount) } ?: listOf(AppsLine.Title) }

    val reqs = remember(items.size) { List(items.size) { FocusRequester() } }
    val requesters by rememberUpdatedState(reqs)
    /** 目标:回来时落哪(铁律 5,与 [holder] 分开)。只在不还原时跟着自报的焦点走。 */
    var focusedIdx by remember { mutableStateOf(0) }
    /** 现在持有焦点的那一张(只信控件自报,铁律 4);null = 网格里没有。 */
    var holder by remember { mutableStateOf<Int?>(null) }
    // 初值 true:第一帧 Compose 若自己把焦点给了某一张,那次上报不能改写目标;定位效果落地后放开。
    var restoring by remember { mutableStateOf(true) }
    val target = focusedIdx.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val targetNow by rememberUpdatedState(target)

    val screenH = LocalConfiguration.current.screenHeightDp.toFloat()
    var scroll by remember { mutableStateOf(0f) }
    fun follow(i: Int) { scroll = appsPageScroll(lines, appsLineOf(lines, i), scroll, screenH) }
    // 翻页与首页换行同一根 browse 弹簧(R29 / R38),不另起一条曲线。
    val shown by animateDpAsState(scroll.dp, animationSpec = Theme.browseShiftSpec(), label = "appsPageScroll")

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) restoring = true }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // **定位效果**:进页 / 菜单关掉(covered 落下)/ 回到前台(nonce)/ 列表重读换了表(requesters)时,把焦点送到目标。
    LaunchedEffect(nonce, covered, reqs) {
        if (covered) { restoring = true; return@LaunchedEffect }
        if (reqs.isEmpty()) return@LaunchedEffect
        restoring = true
        var frames = 0
        while (frames < 60 && holder != targetNow) {
            withFrameNanos { }
            runCatching { requesters[targetNow].requestFocus() }
            frames++
        }
        holder?.let { follow(it) }
        restoring = !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    // **看门狗**(铁律 3):焦点莫名其妙没了时送回目标。守卫全在 key 里(铁律 6);每轮 60 帧封顶,再丢一次 key 翻转重新武装(铁律 7)。
    LaunchedEffect(holder == null, covered, restoring, nonce) {
        if (covered || restoring || holder != null || requesters.isEmpty()) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        var frames = 0
        while (holder == null && frames < 60) {
            runCatching { requesters[targetNow.coerceIn(0, requesters.lastIndex)].requestFocus() }
            withFrameNanos { }
            frames++
        }
    }
    // 上报只派生、不缓存(同首页 onFocusedCard):列表重读后同一格换了应用、没有焦点事件,items 变 → 再报一次。
    LaunchedEffect(holder, items) {
        onFocusedApp(holder?.let { items.getOrNull(it) }?.app?.let { a -> Apps.cachedPickerCard(a.packageName)?.copy(label = a.label) ?: a })
    }
    DisposableEffect(Unit) { onDispose { onFocusedApp(null) } }

    val metrics = Theme.gtvCardMetrics(AppsPageLayout.CARD_SIZE)
    val titleStyle = TextStyle(
        fontFamily = Theme.Sans, fontWeight = FontWeight.Medium, color = Theme.EmphasisText,
        fontSize = GtvLayout.SETTINGS_TITLE_TEXT.sp,
    )
    Box(Modifier.fillMaxSize().background(GtvTokens.MenuBg)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // **必须 unbounded**(铁律 1 的另一半):内容比屏幕高,不放开测量的话屏幕外的行被量成 0 高、永远聚焦不到。
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .offset(y = -shown)
                .padding(top = AppsPageLayout.PAGE_TOP.dp),
            verticalArrangement = Arrangement.spacedBy(AppsPageLayout.LINE_GAP.dp),
        ) {
            val lastCards = lines.indexOfLast { it is AppsLine.Cards }
            val firstCards = lines.indexOfFirst { it is AppsLine.Cards }
            lines.forEachIndexed { li, line ->
                when (line) {
                    AppsLine.Title -> Row(
                        Modifier.height(AppsPageLayout.TITLE_LINE.dp).padding(start = GtvLayout.CONTENT_KEYLINE.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        BasicText(stringResource(R.string.apps_page_title), style = titleStyle)
                        BasicText(
                            stringResource(R.string.apps_page_hint),
                            style = shellBodyStyle,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp),
                        )
                    }
                    AppsLine.ToolsHeader -> Box(
                        Modifier.height(AppsPageLayout.HEADER_LINE.dp).padding(start = GtvLayout.CONTENT_KEYLINE.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        BasicText(
                            stringResource(R.string.edit_picker_system_tools),
                            style = TextStyle(fontFamily = Theme.Sans, fontWeight = FontWeight.Medium, color = Theme.SecondaryText, fontSize = 18.sp),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    is AppsLine.Cards -> Row(
                        horizontalArrangement = Arrangement.spacedBy(metrics.cardSpacing),
                        modifier = Modifier
                            .height(AppsPageLayout.cardRowHeight().dp)
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .padding(start = GtvLayout.CONTENT_KEYLINE.dp, top = metrics.rowVerticalPad, bottom = metrics.rowVerticalPad),
                    ) {
                        for (k in 0 until line.count) {
                            val i = line.first + k
                            val c = items.getOrNull(i) ?: continue
                            AppsPageCard(
                                candidate = c,
                                revision = revision,
                                metrics = metrics,
                                onClick = { onOpen(c.app) },
                                modifier = Modifier.focusRequester(reqs[i]),
                                onFocusChange = { got ->
                                    if (got) {
                                        holder = i
                                        if (!restoring) focusedIdx = i
                                        follow(i)
                                    } else if (holder == i) holder = null
                                },
                                isRowStart = k == 0,
                                isRowEnd = k == line.count - 1,
                                isFirstRow = li == firstCards,
                                isLastRow = li == lastCards,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一张卡:首页 [AppCard] 原样,卡片图按需读(同「添加应用」列表的小卡片:进程内 LRU,首帧先用缓存)。 */
@Composable
private fun AppsPageCard(
    candidate: PickerCandidate,
    revision: Int,
    metrics: CardMetrics,
    onClick: () -> Unit,
    modifier: Modifier,
    onFocusChange: (Boolean) -> Unit,
    isRowStart: Boolean,
    isRowEnd: Boolean,
    isFirstRow: Boolean,
    isLastRow: Boolean,
) {
    val ctx = LocalContext.current
    val pkg = candidate.app.packageName
    // revision:换了卡片图 / 应用更新后重读(pickerCard 按版本戳自己判断要不要真的重解码)。
    val card by produceState(Apps.cachedPickerCard(pkg), pkg, revision) {
        value = withContext(Dispatchers.IO) { Apps.pickerCard(ctx, pkg) } ?: value
    }
    val app = card?.copy(label = candidate.app.label) ?: candidate.app
    AppCard(
        app = app,
        onClick = onClick,
        modifier = modifier,
        metrics = metrics,
        onFocusChange = onFocusChange,
        isRowStart = isRowStart,
        isRowEnd = isRowEnd,
        isFirstRow = isFirstRow,
        isLastRow = isLastRow,
        title = candidate.app.label.ifBlank { pkg },
        fallbackColor = app.fallbackColor?.let { androidx.compose.ui.graphics.Color(it) },
    )
}

/** 应用页的长按 / MENU 菜单状态(R90):[rows] == null 是第一层,非 null 是「加到哪一行」那一层(layout.json 的行)。 */
data class AppsMenu(val app: AppEntry, val rows: List<LayoutRow>? = null)
