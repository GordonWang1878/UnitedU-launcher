package com.uniteduone.launcher

import android.view.KeyEvent as AKey
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 拿起(搬运)的状态(spec §2.3,沿用 M4b §0-18 的 carry):被搬的卡此刻在哪一格(同时就是焦点目标)、出发那一格(取消后回这里)、
 * 拿起那一刻的整份行(取消时原样放回)。三者总是一起变,理由同首页的 [MoveState]。拿起中 `rows` 实时跟着改、**不写盘**,放下才 persist。
 */
private data class EditCarry(val pos: MovePos, val from: MovePos, val original: List<LayoutRow>)

/**
 * 编辑页上叠着的那一层(R136 / R165,给 [OverlayStack] 当状态)。每一种带上自己要显示的东西:关掉之后残影还要画一个淡出时长,
 * 那时 `rows` 可能已经变了。[Pick.from] = 打开它的那一格(胶囊「添加应用」或空架子的方块),返回落回那里。
 * **新加的浮层并进这里就自动并进 `overlayOpen`**(铁律 6:它同时是看门狗的 key 与守卫)。
 */
private sealed interface EditOverlay {
    val layer: String
    class Card(val acting: EditActing, val title: String, val app: AppEntry?) : EditOverlay {
        override val layer get() = "card:${acting.row}:${acting.pkg}"
    }
    class Icon(val row: Int, val icon: String) : EditOverlay {
        override val layer get() = "icon:$row"
    }
    class Confirm(val row: Int, val icon: String, val apps: Int) : EditOverlay {
        override val layer get() = "confirm:$row"
    }
    class Pick(val row: Int, val icon: String, val exclude: Set<String>, val from: ShelfSpot) : EditOverlay {
        override val layer get() = "pick:$row"
    }
    /** R164 选频道页。[onLayout] 打开那一刻布局里已有的频道(残影画关掉前那一份,不现查);[from] = 打开它的「频道」卡,返回落回那里。 */
    class ChannelPick(val onLayout: List<ChannelRef>, val from: ShelfSpot) : EditOverlay {
        override val layer get() = "channels"
    }
}

/** R167:编辑页传给各层的回调,整页只建一份;字段都转调「最新那一份」实现(rememberUpdatedState),组合多少次都是同一个实例(报告 #5)。 */
@Stable
private class EditShelfCallbacks(
    val req: (ShelfSpot) -> FocusRequester,
    val report: (ShelfSpot, Boolean) -> Unit,
    val place: (ShelfSpot, Float) -> Unit,
    val onChip: (Int, ShelfChip) -> Unit,
    val onChoice: (NewRowChoice) -> Unit,
    val onAddTile: (Int) -> Unit,
    val onCardClick: (Int, Int) -> Unit,
)

private fun dirOf(code: Int): MoveDir? = when (code) {
    AKey.KEYCODE_DPAD_LEFT -> MoveDir.LEFT
    AKey.KEYCODE_DPAD_RIGHT -> MoveDir.RIGHT
    AKey.KEYCODE_DPAD_UP -> MoveDir.UP
    AKey.KEYCODE_DPAD_DOWN -> MoveDir.DOWN
    else -> null
}

private fun isOkKey(code: Int): Boolean =
    code == AKey.KEYCODE_DPAD_CENTER || code == AKey.KEYCODE_ENTER || code == AKey.KEYCODE_NUMPAD_ENTER

/**
 * 卡片条的横向裁切(owner 裁定,R165 计划复审):**只裁越过架子右端的卡**——行多于一屏时右边露出的那一截不画到架子外。
 * 焦点卡(放大 + 描边 + 60 dp 柔光)整张进了架子就不裁,柔光可以画出架子(还在滑入时照样裁,见 [shelfCardClipRight]);**左边一律不裁**(第 1 张卡的柔光画到架子外,默认焦点就落在那里),
 * 滑出左边的卡只由屏幕边缘裁(同首页)。上下不裁。[rightDp] 在绘制阶段读:这张卡左缘到架子右边缘的距离(dp,随横向位移每帧变),
 * ≤ 0 时整张不画,null 时不裁。只是绘制:不改布局、不影响可聚焦性。[shiftPx] = 裁切之内的绘制平移(R167 拿起换位的滑动),
 * 「整张不画」按内容此刻画在哪判;缺省 0 = 改前行为。
 */
private fun Modifier.clipPastShelfEnd(shiftPx: () -> Float = { 0f }, rightDp: () -> Float?): Modifier = drawWithContent {
    val rd = rightDp() ?: return@drawWithContent drawContent()
    val r = rd.dp.toPx()
    // 内容此刻画在本地 x ∈ [shiftPx, shiftPx + 宽](R167 拿起换位的平移在裁切之内);左缘都过了裁切线才整张不画
    if (r <= shiftPx()) return@drawWithContent
    clipRect(left = -size.width * 4f, top = -size.height * 4f, right = r, bottom = size.height * 5f) { this@drawWithContent.drawContent() }
}

/** 页头下的渐隐遮罩色(c2:rgba(12,13,16,.97))。 */
private val EditScrim = Color(0xF70C0D10)

/**
 * **编辑桌面「货架」**(R165,spec §2)。每一行是一层玻璃架子:顶部是行图标 +「N 个应用」+ 操作胶囊(添加应用 / 换图标 / 上移 /
 * 下移 / 删除),下面是这一行的应用卡(固定中档);最后一层「新的一行」里选「应用行」。确定键在卡片上 = 拿起(左右换位、上下搬到相邻
 * 应用架子、确定放下、返回取消);长按或菜单键 = 卡片菜单(换卡片图 / 移出这一行);返回 = 完成。
 *
 * **焦点账本**(七条铁律逐条落实,见 CLAUDE.md 焦点表「编辑页」一行):
 * - 目标 [target](`ShelfSpot`)与持有者 [holder] 永远是两个量(铁律 5);`report()` 只在不重定位、没暂停、不在拿起中时让目标跟着走;
 * - `ON_PAUSE` 起 `paused` 冻结目标,`ON_RESUME` 按目标重定位;
 * - 每个可聚焦节点按 `ShelfSpot` 逐项挂 requester(`req()`,表只增不换,铁律 3 的实现约束);
 * - 显式重定位只信目标自报 `holder == want`(铁律 2);`holder == null` 看门狗守卫与 key 同一组量(铁律 6);没有布尔闩(铁律 7);
 * - 上下键由根节点 `onPreviewKeyEvent` 按 [verticalStep] 走固定顺序,不交给 Compose 的二维搜索;不用可滚动容器(铁律 1)。
 */
@Composable
fun EditScreen(
    /**
     * 「换卡片图」:(layout.json 行号, 包名)。**选择器会替换本页**(M7 终审 C1):关掉后整页重建,调用方把这两个值原样当
     * [initialTarget] 喂回来,焦点回到这张卡。返回选择器是否真的打开了(存储没就绪时打不开)。打开了的话本页不收菜单——本页随即
     * 变成淡出的残影(R138),菜单原样留在画面上,选择器在它上面淡入。
     */
    onPickIcon: (row: Int, pkg: String) -> Boolean,
    onExit: () -> Unit,
    focusNonce: Int = 0,
    revision: Int = 0,
    /** 当前壁纸的选中值(`Settings.wallpaperFile`),给模糊底用(spec §2.5)。 */
    wallpaperFile: String = "",
    /** 「换卡片图」的选择器关掉之后带进来的 (layout.json 行号, 包名);null = 正常进入,落第 1 层第 1 张卡(空架子落「添加应用」方块)。 */
    initialTarget: Pair<Int, String>? = null,
    /** R167:MainActivity 进页前在 IO 线程读好的 layout.json;null(换卡片图回来重建)时照旧当场读。 */
    initialRows: List<LayoutRow>? = null,
    /** R167:写盘落地一次回调一次(MainActivity 让底下常驻的首页按新布局重读,Task 3 接上)。 */
    onLayoutWritten: () -> Unit = {},
    /** R167 复审:发起一次写盘就回调一次(在主线程上、排进 layoutWrites 之前,同步调)。MainActivity 据此判断「这一轮编辑改没改过布局」,
     *  不等写盘落地——删 / 移一行后立刻按返回,写盘还在路上也算改过。 */
    onLayoutWriteIssued: () -> Unit = {},
) {
    val ctx = LocalContext.current
    SideEffect { EditPerf.log("page") }
    val showToast = LocalToast.current
    /** R138 / R147:本页也会以残影出现(打开换卡片图、退出编辑页时)。残影里不可聚焦、两个效果让路、返回键不收、不回调。 */
    val ghost = LocalPageGhost.current
    val metrics = Theme.gtvCardMetrics(GtvCardSize.MEDIUM)   // spec §2.1:固定中档,不跟「卡片大小」设置
    val accent = LocalThemeColors.current.accent
    val highlight = LocalThemeColors.current.highlight
    val density = LocalDensity.current
    val screenW = LocalConfiguration.current.screenWidthDp.toFloat()
    val hostView = LocalView.current

    val titles by produceState(emptyMap<String, String>(), revision) {
        value = withContext(Dispatchers.IO) { Titles.read(ctx) }
    }
    // 模糊底:壁纸变了才算一次(IO 线程),之后每帧只画位图(spec §2.5);上次算好的当初值
    val backdrop by produceState(cachedEditBackdrop(), wallpaperFile) {
        value = withContext(Dispatchers.IO) { runCatching { buildEditBackdrop(ctx, wallpaperFile) }.getOrNull() }
    }
    var rows by remember { mutableStateOf(initialRows ?: Layout.read(ctx)) }
    var overlay by remember { mutableStateOf<EditOverlay?>(null) }
    val overlayOpen = overlay != null
    var carry by remember { mutableStateOf<EditCarry?>(null) }
    /** R167:最近一次跨层搬入 / 跨层取消放回(只给滑入方向用,不进任何焦点账本;下一次跨层时整个换掉)。 */
    var arrival by remember { mutableStateOf<CarryArrival?>(null) }
    /**
     * R167:取消搬运后正滑回出发格的那张卡(层、包)。焦点一直在它身上,所以滑回途中照样画成聚焦、不重放进焦动画;
     * 焦点真的落回出发格(holder 自报)就清掉。只是绘制,不进任何焦点账本(下面的效果只读 holder)。
     */
    var settling by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // R167 Task 12:整页「在搬运 / 滑回中」以一个稳定的 lambda 往下传,只有真的读到它的卡(焦点格、或记下的应用已不是现在这个)才在它翻转时重组。
    // 原来是 Boolean 参数:拿起 / 放下 / 取消那一下所有应用架子、每一张卡都重组、离屏层全部重画(D 段 trace:47 个卡片图层一帧重画、新建 26 张纹理)。
    val carryingState = remember { derivedStateOf { carry != null || settling != null } }
    val carryingNow: () -> Boolean = remember { { carryingState.value } }
    val needed = remember(rows) { rows.flatMap { it.apps }.toSet() }
    // 与旧编辑页同一做法:null 区分「还在加载」与「加载失败」;连同「这份数据是给哪套 needed 算的」一起存(R67,见 editCardShown)
    // R167(报告 #2 / #9):首页那份卡片数据(同一 revision、覆盖本页所需)直接当初值——进页第一帧就是真卡,
    // 重定位效果不必等「占位换真卡」,焦点放大与页面淡入同时开始。没命中(进页前刚 revision++)照旧在 IO 线程读,读完发布。
    val cachedAtEntry = remember { AppEntryCache.lookup(revision, needed)?.let { needed to it } }
    val loadedFor by produceState<Pair<Set<String>, Map<String, AppEntry>>?>(initialValue = cachedAtEntry, needed, revision) {
        val hit = AppEntryCache.lookup(revision, needed)
        value = needed to (hit ?: withContext(Dispatchers.IO) {
            // 必须兜底:produceState 里抛出会终结 Recomposer,应用当场崩溃
            runCatching { loadAppEntries(ctx, revision, needed) }.getOrDefault(emptyMap())
        })
    }
    val all = loadedFor?.second
    fun shownNow(): (String) -> Boolean {
        val lf = loadedFor
        val found = lf?.second?.keys.orEmpty()
        return { pkg -> editCardShown(pkg, lf?.first, found) }
    }
    /** 看得见的那份(R67);回调里一律现调,不用组合期的 viewRows(拿起的方向键可能在两次组合之间连着来)。 */
    fun view(): List<LayoutRow> = visibleRows(rows, shownNow())
    // R164 + owner 裁定(2026-10-08):频道架子的内容与应用名来自进程级 ChannelCache(MainActivity 按 channelsRevision 刷新),
    // 本页不查 TvProvider;海报预览不可聚焦,换内容不拆任何焦点节点。
    val channelEnv = LocalChannelEnv.current
    val channelSnap by ChannelCache.data.collectAsState()
    val channelRefs = remember(rows) { rows.mapNotNull { it.channel } }
    val channelContentNow by rememberUpdatedState(remember(channelSnap, channelRefs) { channelContentsFrom(channelSnap, channelRefs) })
    // 快照里的应用名只覆盖此刻有频道的发布方:没授权、频道被删(Missing)时那里没有它,另从 PackageManager 查一次(Task 13 复审)
    val publisherLabels by produceState(emptyMap<String, String>(), channelRefs) {
        value = withContext(Dispatchers.IO) { channelRefs.map { it.pkg }.distinct().associateWith { Apps.labelOf(ctx, it) } }
    }
    val channelLabelsNow by rememberUpdatedState(
        remember(channelSnap, publisherLabels) { mergeChannelLabels(channelSnap?.labels.orEmpty(), publisherLabels) },
    )
    /**
     * 本页的货架一律经这里算(组合期、按键回调、重定位的 snapshotFlow、看门狗)。频道架子的胶囊表取决于授权状态(「重新授权」那颗),
     * 有一处漏传内容,那一处算出的胶囊下标就与画出来的对不上。读的是 State(rememberUpdatedState),回调与 snapshotFlow 读到的都是最新的。
     */
    // R169:进一次编辑页判断一次(本页随退出离开组合,下次进来重判)
    val channelsOffered = remember { hasPlayServices(ctx) }
    fun shelvesFor(v: List<LayoutRow>): List<Shelf> = shelvesOf(v, channelLabelsNow, channelContentNow, channelsOffered)
    fun shelvesNow(): List<Shelf> = shelvesFor(view())
    fun applyView(edited: List<LayoutRow>, base: List<LayoutRow> = rows) {
        rows = withVisibleEdits(base, edited, shownNow())
    }
    val viewRows = remember(rows, loadedFor) { view() }
    // R167(Task 7):内容没变的那一层沿用上一份的实例——参数按实例比(strong skipping),拿起搬一步 / 数据重读时
    // 没变的层整个跳过重组,只有真的换了卡的一两层重组。只在组合阶段读写的普通数组,不是状态
    val shelfMemo = remember { arrayOfNulls<List<Shelf>>(1) }
    val shelves = remember(viewRows, channelLabelsNow, channelContentNow) {
        reuseEqual(shelfMemo[0], shelvesFor(viewRows)).also { shelfMemo[0] = it }
    }
    /** 每层的胶囊表(组合期一份,同一份 shelves 下是同一个实例,传给各层不会让它们不可跳过;内容没变的层沿用上一份的实例)。 */
    val chipsMemo = remember { arrayOfNulls<List<List<ShelfChip>>>(1) }
    val chipsByShelf = remember(shelves) {
        reuseEqual(chipsMemo[0], shelves.indices.map { shelfChips(shelves, it) }).also { chipsMemo[0] = it }
    }

    // ---------------- 焦点账本 ----------------
    /** 目标:回来落哪一格。三个分量在一个不可变值里一起写,与 [holder] 永远是两个量(铁律 5)。带种子进页时第一帧就在种子那一层。 */
    var target by remember { mutableStateOf(initialEditSpot(rows, initialTarget)) }
    /** 此刻持有焦点的那一格,只信控件自报(铁律 2 / 4);null = 本页没有焦点。 */
    var holder by remember { mutableStateOf<ShelfSpot?>(null) }
    // 「安排了几次 / 完成了几次」的比对(铁律 7)。tick 从 1 起:一进页就处在一次待办的重定位里,目标 = 初始格,从第一帧起冻结
    var retargetTick by remember { mutableStateOf(1) }
    var retargetDone by remember { mutableStateOf(0) }
    val retargeting = retargetTick != retargetDone
    /** ON_PAUSE 起为真,ON_RESUME 清(spec §2.2「ON_PAUSE 起冻结」):期间焦点上报改不动目标,看门狗让路。 */
    var paused by remember { mutableStateOf(false) }
    // 逐项 requester,按格子身份取,表只增不换:不会因为行数 / 胶囊数变了整表换新、协程里捏着旧表(铁律 3 的实现约束)
    val requesters = remember { HashMap<ShelfSpot, FocusRequester>() }
    fun req(s: ShelfSpot): FocusRequester = requesters.getOrPut(s) { FocusRequester() }
    /** 每一格此刻的水平中心(px,根坐标),给 [verticalStep] 取最近;只在按键回调里读,不是 Compose 状态。 */
    val centers = remember { HashMap<ShelfSpot, Float>() }
    /** 每层卡片条**当前**聚焦到第几张(只算横向位移,跟着真实焦点走;不是目标)。 */
    val shelfCol = remember { mutableStateMapOf<Int, Int>() }

    /** 安排一次重定位:冻结(tick++)与写目标在同一个同步动作里(旧编辑页 2026-09-11 的实测教训)。所有动作都走这里。 */
    /**
     * 目标在胶囊条上时,设目标那一刻那一格是哪颗胶囊(Task 13 复审 2):胶囊表之后变了(授权回来「重新授权」没了),
     * [resolveEditTarget] 按身份落,而不是按位置夹到旁边那颗(「删除」)。与 [target] 永远一起写([setTarget])。
     */
    var targetChip by remember { mutableStateOf<ShelfChip?>(null) }
    fun chipAt(s: ShelfSpot): ShelfChip? =
        if (s.zone == ShelfZone.CHIPS) shelfChips(shelvesNow(), s.shelf).getOrNull(s.index) else null
    fun setTarget(s: ShelfSpot, chip: ShelfChip? = chipAt(s)) {
        target = s
        targetChip = chip
    }
    fun retarget(s: ShelfSpot, chip: ShelfChip? = chipAt(s)) {
        retargetTick++
        setTarget(s, chip)
    }
    fun report(s: ShelfSpot, got: Boolean) {
        if (got) {
            holder = s
            if (s.zone == ShelfZone.CARDS) shelfCol[s.shelf] = s.index
            // 读的是活的状态(不是组合期的 val):重定位 / 暂停 / 拿起中,Compose 抢先给出的焦点事件改不动目标
            if (retargetTick == retargetDone && !paused && carry == null) setTarget(s)
        } else if (holder == s) holder = null
    }

    // R167:三个只在「换了层 / 换了区域 / 有无持有者」时才变的派生值;层内左右移动焦点时它们不变,整页不重组(报告 #5)
    val activeShelfRaw by remember { derivedStateOf { (if (retargetTick != retargetDone || paused) target else holder ?: target).shelf } }
    val noHolder by remember { derivedStateOf { holder == null } }
    val hintZone by remember { derivedStateOf { holder?.zone ?: target.zone } }

    // **组合阶段发现目标那一层的胶囊表变了 → 同步冻结并按身份重定位**(Task 13 复审 2;SettingsShell 条件行同一手法)。
    // 授权回来 ChannelCache 重读,「重新授权」那颗被摘掉的那一帧,Compose 当场把焦点派给左上角那张卡(模拟器实测 5 ms 内),
    // 那次上报若看到「没在重定位」就把目标改写成第一张卡、看门狗也看不到「没人持有」。所以必须在**组合阶段**(节点被摘之前)
    // 就置上重定位;落点由 resolveEditTarget 按身份算(那颗还在 → 跟着它;是「重新授权」→ 这一层第一颗,不落到「删除」)。
    // 显式落点(删行 / 上下移等)在同一个快照里连 targetChip 一起写好,这里按身份解析出的是同一格,只是多一次无害的重定位。
    // 普通数组,只在组合阶段读写,不引起重组。拿起中不动(目标是被搬的卡,不在胶囊条上)。
    // R167:按层记上一份胶囊表,只在胶囊表真的换了一份(= shelves 变了)时比对;读 target / carry 不登记依赖——
    // 否则每次焦点移动(report → setTarget)都会让整页重组(报告 #5)。
    val lastChips = remember { arrayOfNulls<List<List<ShelfChip>>>(1) }
    val prevAll = lastChips[0]
    if (prevAll !== chipsByShelf) {
        lastChips[0] = chipsByShelf
        if (prevAll != null) Snapshot.withoutReadObservation {
            val t = target
            val before = prevAll.getOrNull(t.shelf)
            if (t.zone == ShelfZone.CHIPS && carry == null && before != null && before != chipsByShelf.getOrNull(t.shelf)) {
                val to = resolveEditTarget(shelves, t, targetChip)
                retarget(to, shelfChips(shelves, to.shelf).getOrNull(to.index))
            }
        }
    }

    // ---------------- 写盘 ----------------
    val scope = rememberCoroutineScope()
    val knownOnDisk = remember { java.util.concurrent.atomic.AtomicReference(layoutPackages(rows).toSet()) }
    val layoutWritten by rememberUpdatedState(onLayoutWritten)
    val layoutWriteIssued by rememberUpdatedState(onLayoutWriteIssued)
    fun persist() {
        val snapshot = rows
        layoutWriteIssued()
        scope.launch {
            // R167:NonCancellable——退出编辑页后本页残影 EDIT_SWAP_MS 就离开组合、scope 被取消;写盘与「首页重读」的通知不能跟着丢
            // (Review Focus 5)。回调在块内调:withContext 返回时若外层已取消会直接抛,块外的代码不一定跑得到。
            val ok = withContext(NonCancellable) {
                // layoutWrites 串行:较早的快照不会后落盘;rewrite 整段在 Layout 的锁里(落盘铁律)
                val landed = withContext(layoutWrites) {
                    var written: List<LayoutRow>? = null
                    val l = Layout.rewrite(ctx) { disk ->
                        dropRemovedElsewhere(snapshot, disk, knownOnDisk.get()) { Apps.isInstalled(ctx, it) }.also { written = it }
                    }
                    if (l) written?.let { w -> knownOnDisk.set(knownAfterWrite(knownOnDisk.get(), w)) }
                    l
                }
                if (landed) layoutWritten()
                landed
            }
            if (!ok) showToast(ctx.getString(R.string.edit_toast_order_not_saved), true)
        }
    }

    // ---------------- 拿起 ----------------
    fun startCarry(row: Int, col: Int) {
        settling = null
        val at = MovePos(row, col)
        carry = EditCarry(pos = at, from = at, original = rows)
        retarget(ShelfSpot(row, ShelfZone.CARDS, col))
    }
    /** 搬一步:在看得见的那份上搬,以拿起那一刻的整份为底合回(看不见的包始终按出发时的下标摆,R67)。上下到头 / 相邻行已有它:不动。 */
    fun stepCarry(dir: MoveDir) {
        val c = carry ?: return
        val v = view()
        val (next, pos) = moveInLayout(v, c.pos, dir)
        if (next === v) return
        // R167:跨层时记下被搬的卡从哪边来,目标层据此竖直滑入(EditCarrySlide.kt);只是绘制,不进焦点账本
        if (pos.row != c.pos.row) v.getOrNull(c.pos.row)?.apps?.getOrNull(c.pos.col)?.let { arrival = CarryArrival(pos.row, it, arrivalSign(c.pos.row, pos.row)) }
        applyView(next, base = c.original)
        carry = c.copy(pos = pos)
        retarget(ShelfSpot(pos.row, ShelfZone.CARDS, pos.col))
    }
    /** 放下:没动过不写盘;显式再重定位一次(被搬的应用途中被卸载时,节点会被摘掉,账本冻结期间没人上报)。 */
    fun dropCarry() {
        val c = carry ?: return
        carry = null
        arrival = null   // R167:这次搬运的跨层来向到此作废
        if (rows != c.original) persist()
        retarget(ShelfSpot(c.pos.row, ShelfZone.CARDS, c.pos.col))
    }
    /** 取消:整份放回、不写盘、焦点回出发格。入口:返回键、ON_PAUSE、任何浮层要打开。 */
    fun cancelCarry() {
        val c = carry ?: return
        carry = null
        // R167:跨层搬过的卡放回出发那一层时,从它此刻所在的那一层的方向滑入
        if (c.pos.row != c.from.row) view().getOrNull(c.pos.row)?.apps?.getOrNull(c.pos.col)?.let { arrival = CarryArrival(c.from.row, it, arrivalSign(c.pos.row, c.from.row)) }
        view().getOrNull(c.pos.row)?.apps?.getOrNull(c.pos.col)?.let { settling = c.from.row to it }
        rows = c.original
        retarget(ShelfSpot(c.from.row, ShelfZone.CARDS, c.from.col))
    }

    // ---------------- 动作 ----------------
    fun cardSpot(a: EditActing): ShelfSpot = landingOnCard(shelvesNow(), a.row, editActingCol(a, view()) ?: a.col)
    fun returnSpot(o: EditOverlay): ShelfSpot = when (o) {
        is EditOverlay.Card -> cardSpot(o.acting)
        is EditOverlay.Icon -> landingOnChip(shelvesNow(), o.row, ShelfChip.ICON)
        is EditOverlay.Confirm -> landingOnChip(shelvesNow(), o.row, ShelfChip.DELETE)
        is EditOverlay.Pick -> clampSpot(shelvesNow(), o.from)
        is EditOverlay.ChannelPick -> clampSpot(shelvesNow(), o.from)
    }
    fun closeOverlay(o: EditOverlay) {
        if (overlay !== o) return
        overlay = null
        retarget(returnSpot(o))
    }
    fun openCardMenu(row: Int, col: Int) {
        val pkg = view().getOrNull(row)?.apps?.getOrNull(col) ?: return
        overlay = EditOverlay.Card(EditActing(row, col, pkg), titles[pkg] ?: all?.get(pkg)?.label ?: pkg, all?.get(pkg))
    }
    fun openPicker(row: Int, from: ShelfSpot) {
        val r = rows.getOrNull(row) ?: return
        overlay = EditOverlay.Pick(row, r.icon, rows.flatMap { it.apps }.toSet(), from)
    }
    /** 两层对调时,两层的「当前横向位置」跟着层走(不然横向位移会套到别的层上)。 */
    fun swapCols(a: Int, b: Int) {
        val x = shelfCol[a]; val y = shelfCol[b]
        if (y != null) shelfCol[a] = y else shelfCol.remove(a)
        if (x != null) shelfCol[b] = x else shelfCol.remove(b)
    }
    fun dropColAfterDelete(ri: Int) {
        val old = shelfCol.toMap()
        shelfCol.clear()
        old.forEach { (k, v) -> if (k < ri) shelfCol[k] = v else if (k > ri) shelfCol[k - 1] = v }
    }
    /** 删第 [ri] 层:落上一层第一颗胶囊(删的是第一层落新的第一层)。纯函数挡住了(只剩一行 / 越界)→ 回「删除」胶囊。 */
    fun deleteRowAt(ri: Int) {
        val before = rows
        rows = deleteRow(rows, ri)
        if (rows === before) { retarget(landingOnChip(shelvesNow(), ri, ShelfChip.DELETE)); return }
        dropColAfterDelete(ri)
        persist()
        retarget(landingAfterDelete(shelvesNow(), ri))
    }
    fun onChip(si: Int, chip: ShelfChip) {
        if (carry != null || ghost || overlay != null) return
        val r = rows.getOrNull(si) ?: return
        val here = landingOnChip(shelvesNow(), si, chip)
        when (chip) {
            ShelfChip.ADD_APP -> openPicker(si, here)
            ShelfChip.ICON -> overlay = EditOverlay.Icon(si, r.icon)
            ShelfChip.UP, ShelfChip.DOWN -> {
                val to = if (chip == ShelfChip.UP) si - 1 else si + 1
                val next = swapRows(rows, si, to)
                if (next === rows) return
                rows = next
                swapCols(si, to)
                persist()
                retarget(landingAfterSwap(shelvesNow(), to, chip))
            }
            ShelfChip.DELETE -> {
                // 按看得见的算(R67):只剩看不见的包(被停用的)的行,在用户眼里就是空行 → 直接删
                val apps = view().getOrNull(si)?.apps.orEmpty()
                // 频道行的 apps 恒空 → 直接删、不弹确认(spec §2.1);deleteRow 对频道行不设下限,应用行只剩 1 行时挡住
                if (apps.isEmpty()) deleteRowAt(si) else overlay = EditOverlay.Confirm(si, r.icon, apps.size)
            }
            ShelfChip.REAUTHORIZE -> channelEnv.requestPermission { result ->
                // 授权窗盖上来时本页 ON_PAUSE 冻结焦点、ON_RESUME 按目标重定位;授权回来 channelsRevision++ → ChannelCache 重读 →
                // 「重新授权」那颗消失,目标记着的是它(targetChip)→ resolveEditTarget 落这一层第一颗(不是位置上的「删除」,
                // controller 裁定 Task 13 复审 2)。owner 裁定(2026-10-09):只有永久拒绝
                // (系统没弹窗,见 permissionResult)才自动去系统设置;点了拒绝 / 返回关窗 → 留在这颗胶囊上,再按一次再问。
                if (result == PermissionResult.DENIED_PERMANENTLY) channelEnv.openPermissionSettings()
            }
        }
    }
    fun onChoice(choice: NewRowChoice) {
        if (carry != null || ghost || overlay != null) return
        when (choice) {
            NewRowChoice.APP_ROW -> {
                val next = appendAppRow(rows)
                if (next === rows) return   // 已满 5 行:确定不响应(spec §2.4)
                rows = next
                persist()
                retarget(landingAfterAppend(shelvesNow()))
            }
            NewRowChoice.CHANNEL -> {
                if (!channelsOffered) return   // R169:没有这张卡,不该走到这里
                if (choiceFull(shelvesNow(), NewRowChoice.CHANNEL)) return   // 已满 5 行:确定不响应(spec §2.4)
                // 状态并进 overlay = 自动并进 overlayOpen(看门狗 / 定位效果让路,铁律 6);ON_PAUSE 不清它(授权窗回来选页要还在)
                overlay = EditOverlay.ChannelPick(
                    rows.mapNotNull { it.channel },
                    from = ShelfSpot(shelvesNow().lastIndex, ShelfZone.NEW, newRowChoices(channelsOffered).indexOf(NewRowChoice.CHANNEL)),
                )
            }
        }
    }

    // ---------------- 进页种子 / 生命周期 ----------------
    // 带着 (layout 行号, 包名) 进来(换卡片图的选择器关掉、本页重建):数据到位后定位一次。按「已应用的目标」比对,不用闩(铁律 7)
    var appliedTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(initialTarget, all) {
        val t = initialTarget ?: return@LaunchedEffect
        if (all == null || appliedTarget == t) return@LaunchedEffect
        appliedTarget = t
        val ci = view().getOrNull(t.first)?.apps?.indexOf(t.second) ?: -1
        retarget(landingOnCard(shelvesNow(), t.first, ci.coerceAtLeast(0)))
    }
    // ON_PAUSE:拿起取消 + 冻结目标;ON_RESUME:解冻并按目标重定位(从系统设置侧板 / 别的应用回来时 Compose 会抢先给第一张卡)。
    // 经 rememberUpdatedState 调:观察者只建一次,直接捕获的话读到的是第一次组合的函数
    val pauseNow by rememberUpdatedState { cancelCarry(); paused = true }
    // 原样带上记住的那颗胶囊:回来时胶囊表可能已经变了(授权窗里点了允许),不能按此刻那一格重新认
    val resumeNow by rememberUpdatedState { paused = false; retarget(target, targetChip) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> pauseNow()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> resumeNow()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // ---------------- 显式重定位(与看门狗分开:看门狗第一行是「已有焦点就不管」,会吞掉明确的请求)----------------
    LaunchedEffect(retargetTick, ghost) {
        if (ghost) return@LaunchedEffect
        val wanted = target
        val wantedChip = targetChip
        // 目标格若是注定被整格替换的加载占位,先等它换完(旧编辑页 §0-16 的根因:焦点落在占位上,数据一到占位被真卡替换,
        // 焦点随旧节点消失、Compose 从左上角往下找)。只等目标格自己;加载失败也会对上,不会卡住
        snapshotFlow {
            val sh = shelvesFor(view())
            val s = clampSpot(sh, wanted)
            val pkg = (sh.getOrNull(s.shelf) as? Shelf.AppShelf)?.takeIf { s.zone == ShelfZone.CARDS }?.apps?.getOrNull(s.index)
            pkg == null || loadedFor?.first == rows.flatMap { it.apps }.toSet() || loadedFor?.second?.containsKey(pkg) == true
        }.first { it }
        var want = resolveEditTarget(shelvesFor(view()), wanted, wantedChip)
        setTarget(want, if (want == wanted) wantedChip else chipAt(want))
        var frames = 0
        while (frames < 60 && holder != want) {   // 退出判据:目标自报(铁律 2),不信 requestFocus() 的返回
            withFrameNanos { }
            // 每帧重新解析:循环跑着的时候胶囊表可能变了(授权窗关掉 → ON_RESUME 重定位到「重新授权」→ 几帧后 ChannelCache
            // 重读、那颗被摘掉)。只算一次的话会对着已经没了的节点请求满 60 帧,再把 Compose 随手派的第一张卡认作目标
            // (Task 13 复审 2 模拟器实测)。按身份解析,那颗没了就落这一层第一颗
            want = resolveEditTarget(shelvesFor(view()), wanted, wantedChip)
            runCatching { req(want).requestFocus() }
            frames++
        }
        if (holder == want) setTarget(want)
        // 到 60 帧上限还没落下:账本改成焦点此刻真正所在的那一格,下次 ON_RESUME 不再按过期的目标重定位。
        // 守卫同 report():暂停中 / 拿起中焦点停在 Compose 随手派的地方(多半是第一张卡),不能写进目标(铁律 5)
        if (holder != want && !paused && carry == null) holder?.let { setTarget(it) }
        retargetDone = retargetTick
    }

    // ---------------- 看门狗(铁律 3 / 6):守卫里的每个量都在 key 里 ----------------
    // noHolder 是上面的派生值(R167),仍是 key 也是守卫
    LaunchedEffect(focusNonce, all, rows, noHolder, overlayOpen, retargeting, paused, ghost) {
        if (ghost || retargeting || overlayOpen || paused || !noHolder) return@LaunchedEffect
        if (all == null && rows.any { it.apps.isNotEmpty() }) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        if (holder != null) return@LaunchedEffect
        var frames = 0
        while (holder == null && frames < 60) {
            withFrameNanos { }
            runCatching { req(resolveEditTarget(shelvesFor(view()), target, targetChip)).requestFocus() }
            frames++
        }
    }

    // R167:焦点落回出发格(或 2 s 还没落回)就收掉「滑回中」的画法;在效果里读 holder,不让整页随焦点重组(报告 #5)
    val settlingNow = settling
    LaunchedEffect(settlingNow) {
        val s = settlingNow ?: return@LaunchedEffect
        withTimeoutOrNull(2000) {
            snapshotFlow { holder }.first { h ->
                h != null && h.zone == ShelfZone.CARDS && h.shelf == s.first &&
                    (shelvesNow().getOrNull(h.shelf) as? Shelf.AppShelf)?.apps?.getOrNull(h.index) == s.second
            }
        }
        if (settling === s) settling = null
    }
    // 任何浮层要打开 = 拿起取消(结构上的兜底;按键路径上开不出浮层)。守卫的两个量都是 key(铁律 6)
    val carrying = carry != null
    LaunchedEffect(overlayOpen, carrying) {
        if (overlayOpen && carrying) cancelCarry()
    }
    // 浮层指向的东西没了(卡片被卸载、行被删):收掉,看门狗随 overlayOpen 翻回 false 接回焦点。组合期只负责不渲染
    val staleOverlay = when (val o = overlay) {
        is EditOverlay.Card -> editActingCol(o.acting, viewRows) == null
        is EditOverlay.Icon -> o.row !in rows.indices
        is EditOverlay.Confirm -> o.row !in rows.indices
        is EditOverlay.Pick -> o.row !in rows.indices
        // 不指向某一行:行被删不影响它
        is EditOverlay.ChannelPick -> false
        null -> false
    }
    LaunchedEffect(staleOverlay) { if (staleOverlay) overlay = null }

    // ---------------- 返回键(走 OnBackPressedDispatcher:预测式返回下 BACK 不作为按键事件下发)----------------
    androidx.activity.compose.BackHandler(enabled = !ghost) {
        val o = overlay
        when {
            o != null -> closeOverlay(o)
            carry != null -> cancelCarry()
            else -> onExit()
        }
    }
    // 拿起中的返回 = 取消。只在拿起中存在,组合得更晚 → 先接管
    if (carry != null && !ghost) androidx.activity.compose.BackHandler { cancelCarry() }

    // ---------------- 按键截获(根节点 onPreviewKeyEvent:焦点在本页任何一格、包括浮层里时,先到这里)----------------
    val press = remember { OkPress() }
    fun cardAt(s: ShelfSpot): String? =
        (shelvesNow().getOrNull(s.shelf) as? Shelf.AppShelf)?.takeIf { s.zone == ShelfZone.CARDS }?.apps?.getOrNull(s.index)
    fun onEditKey(e: AKey): Boolean {
        if (ghost) return false
        val code = e.keyCode
        val ok = isOkKey(code)
        // 我们按下时接手的那一下:UP 一律吞掉,不论中间开了什么浮层(Review Focus 1)
        if (ok && e.action == AKey.ACTION_UP && press.owns(e.downTime)) {
            if (press.onUp(e.downTime, e.isCanceled) == OkOutcome.SHORT_PRESS) {
                if (carry != null) dropCarry()
                else holder?.let { h -> if (overlay == null && cardAt(h) != null) startCarry(h.shelf, h.index) }
            }
            return true
        }
        val o = overlay
        if (o != null) {
            if (code == AKey.KEYCODE_MENU) {
                // 卡片菜单开着时菜单键 = 收掉它(同所有应用页);别的浮层上什么都不做
                if (e.action == AKey.ACTION_DOWN && e.repeatCount == 0 && o is EditOverlay.Card) closeOverlay(o)
                return true
            }
            // 确定键的重复事件一律吞掉:长按开出菜单之后那一下还在重复(别让菜单胶囊连点);浮层里按住确定也不连点——
            // MainActivity 在编辑页里把确定键整下原样交过来(Step 5 (d)),它原来那条「重复事件全吞」对编辑页不再生效,由这里补上
            if (ok && e.action == AKey.ACTION_DOWN && e.repeatCount > 0) return true
            return false
        }
        if (carry != null) {
            if (code == AKey.KEYCODE_BACK || code in MOVE_PASSTHROUGH_KEYS) return false
            if (e.action == AKey.ACTION_DOWN) {
                if (ok) press.onDown(e.downTime, e.eventTime, e.repeatCount)
                else dirOf(code)?.let { stepCarry(it) }
            }
            return true   // 拿起中其余键一律吞掉(含菜单键)
        }
        val h = holder
        if (code == AKey.KEYCODE_DPAD_UP || code == AKey.KEYCODE_DPAD_DOWN) {
            if (h == null) return false   // 本页没焦点:交给 Compose 默认搜索,随后由上报 / 看门狗接管
            if (e.action == AKey.ACTION_DOWN) {
                val next = verticalStep(shelvesNow(), h, down = code == AKey.KEYCODE_DPAD_DOWN) { centers[it] }
                if (next != null) runCatching { req(next).requestFocus() }
            }
            return true   // 到头也吞掉 = Cancel
        }
        if (code == AKey.KEYCODE_MENU) {
            if (e.action == AKey.ACTION_DOWN && e.repeatCount == 0 && h != null && cardAt(h) != null) {
                hostView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                openCardMenu(h.shelf, h.index)
            }
            return true   // 焦点不在卡片上:菜单键什么都不做(spec §2.3)
        }
        if (ok && h != null && cardAt(h) != null) {
            if (e.action == AKey.ACTION_DOWN && press.onDown(e.downTime, e.eventTime, e.repeatCount) == OkOutcome.LONG_PRESS) {
                hostView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                openCardMenu(h.shelf, h.index)
            }
            return true   // 卡片上的确定键整下归这里:tv-material 的卡在 UP 时直接 onClick,不能放过去
        }
        // 胶囊 / 选择卡 / 「添加应用」方块:交给它们的 clickable;按住的重复事件吞掉,不连点
        return ok && e.action == AKey.ACTION_DOWN && e.repeatCount > 0
    }

    val ghostNow by rememberUpdatedState(ghost)
    val onChipNow by rememberUpdatedState<(Int, ShelfChip) -> Unit>(::onChip)
    val onChoiceNow by rememberUpdatedState<(NewRowChoice) -> Unit>(::onChoice)
    val reportNow by rememberUpdatedState<(ShelfSpot, Boolean) -> Unit>(::report)
    val openPickerNow by rememberUpdatedState<(Int, ShelfSpot) -> Unit>(::openPicker)
    val openCardMenuNow by rememberUpdatedState<(Int, Int) -> Unit>(::openCardMenu)
    val cb = remember {
        EditShelfCallbacks(
            req = { s -> requesters.getOrPut(s) { FocusRequester() } },
            report = { s, got -> reportNow(s, got) },
            place = { s, x -> centers[s] = x },
            onChip = { si, chip -> onChipNow(si, chip) },
            onChoice = { c -> onChoiceNow(c) },
            onAddTile = { si -> if (!ghostNow && carry == null && overlay == null) openPickerNow(si, ShelfSpot(si, ShelfZone.CARDS, 0)) },
            // 卡片的 onClick 只可能来自指针 / 无障碍(确定键在根上就被截走):给卡片菜单
            onCardClick = { si, ci -> if (!ghostNow && carry == null && overlay == null) openCardMenuNow(si, ci) },
        )
    }

    // ---------------- 纵向位移(焦点线,spec §2.1)----------------
    var viewportPx by remember { mutableStateOf(0) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    // 焦点层:平时 = 此刻持有焦点的那一层(没人持有才看目标)——非焦点层的胶囊透明但可聚焦(Task 7),焦点一落上去
    // (拿起中 report 不改目标也一样)这一层同一帧就变成焦点层、胶囊随之淡入。**重定位中 / 暂停时只看目标**:那几帧 Compose
    // 会把焦点短暂派给别处(ON_RESUME、焦点节点被删行 / 卸载 / 拿起离开源行摘掉,铁律 5),跟着持有者走整页会朝错的层动一下;
    // 重定位到 60 帧上限放弃后 retargeting 即为假,回到跟着持有者走
    val activeShelf = activeShelfRaw.coerceIn(0, shelves.lastIndex)
    val shiftPx = with(density) {
        shelfScroll(
            heights = shelves.indices.map { heights[it] ?: 0 },
            gap = ShelfLayout.GAP.dp.roundToPx(),
            focused = activeShelf,
            top = ShelfLayout.TOP.dp.roundToPx(),
            focusLine = ShelfLayout.FOCUS_LINE.dp.roundToPx(),
            viewport = viewportPx,
            bottomPad = ShelfLayout.BOTTOM_PAD.dp.roundToPx(),
        )
    }
    // 各层高度第一次量齐之前位移直接跳到位(snap):带种子进页时首帧按 0 高度算出的位移不能再「滑」到真实位置(Task 10);
    // 量齐之后的下一帧起才走动画。只是动画规格,不进任何焦点效果的 key / 守卫
    val measured = viewportPx > 0 && shelves.indices.all { heights[it] != null }
    var shiftSettled by remember { mutableStateOf(false) }
    LaunchedEffect(measured, shiftSettled) {
        if (measured && !shiftSettled) { withFrameNanos { }; shiftSettled = true }
    }
    // spec §2.5:换层时整页位移与架子亮度同走 200 ms FastOutSlowIn(R29 的弹簧只留给行内横向位移)
    val shift = animateDpAsState(
        targetValue = with(density) { shiftPx.toDp() },
        animationSpec = if (shiftSettled) tween(ShelfLayout.SHIFT_MS, easing = FastOutSlowInEasing) else snap(),
        label = "shelfShift",
    )
    val arrows = remember(viewRows, carry) { carry?.let { carryArrows(viewRows, it.pos) } ?: emptySet() }

    Box(
        Modifier
            .fillMaxSize()
            .editBackdrop(backdrop)
            .onSizeChanged { viewportPx = it.height }
            .onPreviewKeyEvent { onEditKey(it.nativeKeyEvent) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // **必须 unbounded**:不放开测量,超出视窗的层会被压扁,offset 发生在测量之后救不回来(铁律 1)
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .offset { IntOffset(0, (ShelfLayout.TOP.dp - shift.value).roundToPx()) }
                .focusProperties { canFocus = !overlayOpen && !ghost }
                .padding(horizontal = ShelfLayout.SIDE.dp),
            verticalArrangement = Arrangement.spacedBy(ShelfLayout.GAP.dp),
        ) {
            shelves.forEachIndexed { si, shelf ->
                key(si) {
                    val focusAnim = animateFloatAsState(
                        targetValue = if (si == activeShelf) 1f else 0f,
                        animationSpec = tween(ShelfLayout.SHIFT_MS, easing = FastOutSlowInEasing),
                        label = "shelfFocus",
                    )
                    val measure = remember(si) { Modifier.onSizeChanged { heights[si] = it.height } }
                    when (shelf) {
                        is Shelf.AppShelf -> AppShelfView(
                            shelf = shelf,
                            chips = chipsByShelf[si],
                            focus = { focusAnim.value },
                            active = si == activeShelf,
                            all = all,
                            metrics = metrics,
                            col = { shelfCol[si] ?: 0 },
                            carriedCol = carry?.pos?.takeIf { it.row == si }?.col,
                            settlingPkg = settling?.takeIf { it.first == si }?.second,
                            carrying = carryingNow,
                            // 箭头只给被搬的卡所在那一层;别的层拿同一个空集合,搬一步时不因它重组(Task 7)
                            arrows = if (carry?.pos?.row == si) arrows else emptySet(),
                            arrival = arrival?.takeIf { it.row == si },
                            arrowColor = highlight,
                            accent = accent,
                            screenW = screenW,
                            req = cb.req,
                            report = cb.report,
                            place = cb.place,
                            onChip = { cb.onChip(si, it) },
                            onAddTile = { cb.onAddTile(si) },
                            onCardClick = { ci -> cb.onCardClick(si, ci) },
                            modifier = measure,
                        )
                        is Shelf.ChannelShelf -> ChannelShelfView(
                            shelf = shelf,
                            chips = chipsByShelf[si],
                            focus = { focusAnim.value },
                            active = si == activeShelf,
                            accent = accent,
                            loadPosters = loadsPosters(si, activeShelf),
                            req = cb.req,
                            report = cb.report,
                            place = cb.place,
                            onChip = { cb.onChip(si, it) },
                            modifier = measure,
                        )
                        is Shelf.NewRowShelf -> NewRowShelfView(
                            choices = shelf.choices,
                            shelfIndex = si,
                            shelves = shelves,
                            focus = { focusAnim.value },
                            active = si == activeShelf,
                            accent = accent,
                            req = cb.req,
                            report = cb.report,
                            place = cb.place,
                            onChoice = cb.onChoice,
                            modifier = measure,
                        )
                    }
                }
            }
        }

        EditTopBar(counts = remember(viewRows) { editCounts(viewRows) }, showChannels = channelsOffered, hints = editHintSet(hintZone, carry != null, newRowChoices(channelsOffered).size))

        // **R136:编辑页的浮层是一摞,淡入淡出**(OverlayStack)。残影画关掉前那一份,回调只有活着的那一层会调到(`overlay === ov` 判一次)
        OverlayStack(state = overlay?.takeIf { !staleOverlay }, layerKey = { it.layer }) { ov ->
            when (ov) {
                is EditOverlay.Card -> {
                    val a = ov.acting
                    GearMenu(
                        items = listOf(
                            MenuItem(stringResource(R.string.edit_change_image), stringResource(R.string.edit_change_image_desc)) {
                                if (overlay !== ov) return@MenuItem
                                // 打开了:本页随即被选择器替换(残影淡出),回来时由调用方把 (行, 包名) 喂回来;没打开:收菜单、回这张卡
                                if (!onPickIcon(a.row, a.pkg)) closeOverlay(ov)
                            },
                            MenuItem(stringResource(R.string.edit_remove), stringResource(R.string.edit_remove_desc)) {
                                if (overlay !== ov) return@MenuItem
                                val col = editActingCol(a, view()) ?: a.col
                                // 按包名移出(R67:列号是看得见的那份里的,不是 layout.json 下标)
                                rows = rows.mapIndexed { i, r -> if (i == a.row) r.copy(apps = r.apps - a.pkg) else r }
                                persist()
                                overlay = null
                                retarget(landingOnCard(shelvesNow(), a.row, col - 1))
                            },
                        ),
                        onDismiss = { closeOverlay(ov) },
                        nonce = focusNonce,
                        title = ov.title,
                        app = ov.app,
                    )
                }
                is EditOverlay.Icon -> RowIconPicker(
                    current = ov.icon,
                    nonce = focusNonce,
                    onPick = { id ->
                        if (overlay === ov) {
                            overlay = null
                            rows = setRowIcon(rows, ov.row, id)
                            persist()
                            retarget(landingOnChip(shelvesNow(), ov.row, ShelfChip.ICON))
                        }
                    },
                    onDismiss = { closeOverlay(ov) },
                )
                is EditOverlay.Confirm -> ConfirmDialog(
                    title = stringResource(R.string.edit_row_delete_confirm_title),
                    body = pluralStringResource(R.plurals.edit_row_delete_confirm_body, ov.apps, ov.apps),
                    okLabel = stringResource(R.string.edit_row_delete_ok),
                    cancelLabel = stringResource(R.string.dialog_cancel),
                    nonce = focusNonce,
                    icon = ov.icon,
                    onOk = { if (overlay === ov) { overlay = null; deleteRowAt(ov.row) } },
                    onCancel = { closeOverlay(ov) },
                )
                is EditOverlay.Pick -> AppPicker(
                    nonce = focusNonce,
                    ctx = ctx,
                    rowIcon = ov.icon,
                    exclude = ov.exclude,
                    onPick = { pkg ->
                        if (overlay === ov) {
                            rows = rows.mapIndexed { i, r -> if (i == ov.row && pkg !in r.apps) r.copy(apps = r.apps + pkg) else r }
                            persist()
                            overlay = null
                            // 刚加进来的那张(还没查过,画成占位)就是新卡;重定位效果会等它换成真卡再落
                            retarget(landingOnCard(shelvesNow(), ov.row, view().getOrNull(ov.row)?.apps?.indexOf(pkg) ?: 0))
                        }
                    },
                    // AppPicker 没有自己的 BackHandler:返回走上面那个,落回打开它的那一格(ov.from)
                )
                // 返回键不另接:编辑页唯一的 BackHandler 对任何浮层都是 closeOverlay(o),落回 ov.from(「频道」卡)
                is EditOverlay.ChannelPick -> ChannelPicker(
                    nonce = focusNonce,
                    onLayout = ov.onLayout,
                    onPick = { ref ->
                        if (overlay === ov) {
                            val next = appendChannelRow(rows, ref)
                            overlay = null
                            if (next !== rows) {
                                rows = next
                                persist()
                                // §2.2:加频道 → 新频道架子的第一颗胶囊(新行一律在最后)
                                retarget(landingAfterAppendChannel(shelvesNow()))
                            } else {
                                // 满了 / 重复(选页开着时别处改过布局)→ 回「频道」卡
                                retarget(clampSpot(shelvesNow(), ov.from))
                            }
                        }
                    },
                    onBack = { closeOverlay(ov) },
                )
            }
        }
    }
}

/** 页头(spec §2.1):左「编辑桌面」+ 概况,右按键提示;下面一条渐隐遮罩盖住滚上来的架子(c2)。纯展示、不可聚焦。 */
@Composable
private fun EditTopBar(counts: EditCounts, showChannels: Boolean, hints: EditHintSet) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(ShelfLayout.SCRIM_HEIGHT.dp)
            .background(Brush.verticalGradient(0f to EditScrim, 0.55f to EditScrim, 1f to EditScrim.copy(alpha = 0f))),
    )
    Row(
        Modifier.fillMaxWidth().padding(start = ShelfLayout.HEADER_LEFT.dp, end = ShelfLayout.HEADER_LEFT.dp, top = ShelfLayout.HEADER_Y.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(stringResource(R.string.edit_title), style = Type.headline)
        Spacer(Modifier.width(14.dp))
        val rowsText = pluralStringResource(R.plurals.edit_summary_rows, counts.rows, counts.rows)
        val appsText = pluralStringResource(R.plurals.edit_apps_count, counts.apps, counts.apps)
        val channelsText = pluralStringResource(R.plurals.edit_channels_count, counts.channels, counts.channels)
        // R169:没有「频道」入口的电视上不写「0 个频道」;已经加过频道行的照写
        BasicText(if (showChannels || counts.channels > 0) "$rowsText · $appsText · $channelsText" else "$rowsText · $appsText", style = Type.caption)
        Spacer(Modifier.weight(1f))
        EditKeyHints(hints)
    }
}

/**
 * 一层应用架子(spec §2.1):顶行(行图标 + 「N 个应用」+ 胶囊)、卡片行(固定中档;空架子 = 「添加应用」方块)。
 * 每个可聚焦节点:逐项 requester、得失都上报、量水平中心。卡片行横向位移 = 首页同一个 [GtvLayout.rowShiftX]
 * (卡片起点就在 CONTENT_KEYLINE 上,单测钉过),配 `wrapContentWidth(unbounded)`(铁律 1)。横向只裁越过架子右端的非焦点卡
 * ([clipPastShelfEnd],owner 裁定:焦点卡与左边缘一律不裁);当前那张([col])`zIndex` 1,放大 + 柔光盖在邻卡上面(同首页)——
 * 每张卡外面包了一层 Box(抬起阴影 / 箭头 / 裁切),AppCard 自己的 zIndex 只在它那层 Box 里生效,管不到邻卡。
 */
@Composable
private fun AppShelfView(
    shelf: Shelf.AppShelf,
    chips: List<ShelfChip>,
    focus: () -> Float,
    active: Boolean,
    all: Map<String, AppEntry>?,
    metrics: CardMetrics,
    col: () -> Int,
    carriedCol: Int?,
    settlingPkg: String?,
    carrying: () -> Boolean,
    arrows: Set<MoveDir>,
    arrival: CarryArrival?,
    arrowColor: Color,
    accent: Color,
    screenW: Float,
    req: (ShelfSpot) -> FocusRequester,
    report: (ShelfSpot, Boolean) -> Unit,
    place: (ShelfSpot, Float) -> Unit,
    onChip: (ShelfChip) -> Unit,
    onAddTile: () -> Unit,
    onCardClick: (Int) -> Unit,
    modifier: Modifier,
) {
    SideEffect { EditPerf.log("shelf ${shelf.row}") }
    val si = shelf.row
    // R167 拿起换位的滑动(见 EditCarrySlide.kt)。SideEffect 在本帧绘制之前跑:第一帧就画在起点,不会先闪到终点再滑
    val slide = remember { ShelfSlide() }
    val slideScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pitchPx = with(density) { (metrics.cardWidth.value + ShelfLayout.CARD_GAP).dp.toPx() }
    val apps = shelf.apps
    SideEffect {
        val sign = arrival?.sign ?: 0
        val arriveDyPx = sign * with(density) { carryArriveDp(sign).dp.toPx() }
        // 卡没变(含内容相同的新实例):不打断正在跑的滑动
        if (slide.lastApps == apps) { slide.lastApps = apps; return@SideEffect }
        slide.job?.cancel()
        if (slide.begin(apps, arrival, pitchPx, arriveDyPx)) {
            slide.job = slideScope.launch {
                animate(0f, 1f, animationSpec = tween(ShelfLayout.SHIFT_MS, easing = FastOutSlowInEasing)) { v, _ -> slide.progress.floatValue = v }
            }
        }
    }
    ShelfFrame(focus = focus, dashed = false, modifier = modifier.fillMaxWidth().height(ShelfLayout.APP_SHELF_HEIGHT.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = ShelfLayout.PAD_START.dp, end = ShelfLayout.PAD_END.dp, top = ShelfLayout.HEADER_TOP.dp)
                .height(ShelfLayout.HEADER_HEIGHT.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(shelf.icon, tint = { if (active) accent else Ink.Secondary }, boxSize = ShelfLayout.ICON.dp)
            Spacer(Modifier.width(ShelfLayout.ICON_GAP.dp))
            BasicText(
                pluralStringResource(R.plurals.edit_apps_count, shelf.apps.size, shelf.apps.size),
                style = Type.body.copy(color = Ink.Label),
            )
            Spacer(Modifier.weight(1f))
            ShelfChipRow(si, chips, focus, req, report, place, onChip)
        }
        // 当前那张夹到这一层的卡片数:卸载 / 移出让行变短时,不按过期的列号多滑一截
        val cur = col().coerceIn(0, (shelf.apps.size - 1).coerceAtLeast(0))
        val dx = animateDpAsState(
            targetValue = GtvLayout.rowShiftX(cur, GtvCardSize.MEDIUM, screenW).dp,
            animationSpec = Theme.browseShiftSpec(),
            label = "shelfRowX",
        )
        // 架子内宽(dp):Column 左右各留 SIDE;第 ci 张卡的左缘 = PAD_START + ci × (卡宽 + 间距) + dx
        val shelfW = screenW - 2 * ShelfLayout.SIDE
        val pitch = metrics.cardWidth.value + ShelfLayout.CARD_GAP
        Box(Modifier.fillMaxWidth().padding(top = ShelfLayout.CARDS_TOP.dp)) {
            Row(
                Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset { IntOffset(dx.value.roundToPx(), 0) }
                    .padding(start = ShelfLayout.PAD_START.dp),
                horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CARD_GAP.dp),
            ) {
                if (shelf.apps.isEmpty()) {
                    val spot = ShelfSpot(si, ShelfZone.CARDS, 0)
                    AddAppTile(
                        metrics = metrics,
                        onClick = onAddTile,
                        onFocusChange = { report(spot, it) },
                        modifier = Modifier.focusRequester(req(spot)).onGloballyPositioned { place(spot, it.boundsInRoot().center.x) },
                    )
                } else shelf.apps.forEachIndexed { ci, pkg ->
                    val spot = ShelfSpot(si, ShelfZone.CARDS, ci)
                    val carried = carriedCol == ci
                    val lift = animateFloatAsState(
                        targetValue = if (carried) 1f else 0f,
                        animationSpec = tween(ShelfLayout.LIFT_MS, easing = FastOutSlowInEasing),
                        label = "carryLift",
                    )
                    val cell = Modifier.focusRequester(req(spot)).onGloballyPositioned { place(spot, it.boundsInRoot().center.x) }
                    Box(
                        Modifier
                            // 被搬的卡在滑动途中也盖住邻卡(R167)
                            .zIndex(if (ci == cur || carried) 1f else 0f)
                            // rowShiftX 保证当前那张(焦点卡 / 被拿起的卡)停下时完整落在架子里:那时不裁,柔光照常画出架子(owner 裁定);
                            // 横向位移还在动、它还没整张进架子时照样裁在架子右端(Task 10:否则新焦点卡会整张画进架子外的留白)。
                            // 裁切边界仍按布局位置算——R167 的滑动平移放在裁切之内,裁切线停在架子右端、不跟着卡走;
                            // 「整张都在架子外就不画」那条捷径按**画在哪**判(被挤出架子的卡滑动途中还画在架子里)
                            .clipPastShelfEnd(shiftPx = { slide.dx(ci) }) {
                                shelfCardClipRight(shelfW - (ShelfLayout.PAD_START + ci * pitch + dx.value.value), metrics.cardWidth.value, ci == cur)
                            }
                            // R167:拿起换位的滑动,只在绘制阶段平移(不改布局、不改 onGloballyPositioned 量到的位置 → centers 不受影响)
                            .drawWithContent { translate(slide.dx(ci), slide.dy(ci)) { this@drawWithContent.drawContent() } }
                            .graphicsLayer {
                                shadowElevation = lift.value * ShelfLayout.CARRY_SHADOW.dp.toPx()
                                shape = RoundedCornerShape(metrics.cardCorner)
                            }
                            .then(if (carried) Modifier.carryArrows(arrows, arrowColor) else Modifier),
                    ) {
                        val app = all?.get(pkg)
                        if (app != null) {
                            AppCard(
                                app = app,
                                onClick = { onCardClick(ci) },
                                metrics = metrics,
                                modifier = cell,
                                onFocusChange = { report(spot, it) },
                                isRowStart = ci == 0,
                                isRowEnd = ci == shelf.apps.lastIndex,
                                fallbackColor = app.fallbackColor?.let { Color(it) },
                                moving = carried,
                                focusFollowsApp = true,
                                settling = pkg == settlingPkg,
                                carrying = carrying,
                            )
                        } else {
                            ShelfPendingCard(
                                pkg = pkg,
                                metrics = metrics,
                                modifier = cell,
                                onFocusChange = { report(spot, it) },
                                isRowStart = ci == 0,
                                isRowEnd = ci == shelf.apps.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「新的一行」(spec §2.4,c2):虚线架子 + 选择卡,下面一行说明。 */
@Composable
private fun NewRowShelfView(
    choices: List<NewRowChoice>,
    shelfIndex: Int,
    shelves: List<Shelf>,
    focus: () -> Float,
    active: Boolean,
    accent: Color,
    req: (ShelfSpot) -> FocusRequester,
    report: (ShelfSpot, Boolean) -> Unit,
    place: (ShelfSpot, Float) -> Unit,
    onChoice: (NewRowChoice) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        ShelfFrame(focus = focus, dashed = true, modifier = Modifier.fillMaxWidth().height(ShelfLayout.NEW_ROW_HEIGHT.dp)) {
            Row(
                Modifier.padding(start = ShelfLayout.PAD_START.dp, top = ShelfLayout.HEADER_TOP.dp).height(ShelfLayout.HEADER_HEIGHT.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShelfIcon(Icons.Rounded.Add, if (active) accent else Ink.Secondary, ShelfLayout.ICON.dp)
                Spacer(Modifier.width(ShelfLayout.ICON_GAP.dp))
                BasicText(stringResource(R.string.edit_new_row), style = Type.body.copy(color = Ink.Label))
            }
            Row(
                Modifier.padding(start = ShelfLayout.PAD_START.dp, top = ShelfLayout.CHOICE_TOP.dp),
                horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CHOICE_GAP.dp),
            ) {
                choices.forEachIndexed { i, c ->
                    val spot = ShelfSpot(shelfIndex, ShelfZone.NEW, i)
                    ChoiceCard(
                        choice = c,
                        full = choiceFull(shelves, c),
                        onClick = { onChoice(c) },
                        onFocusChange = { report(spot, it) },
                        isFirst = i == 0,
                        isLast = i == choices.lastIndex,
                        modifier = Modifier.focusRequester(req(spot)).onGloballyPositioned { place(spot, it.boundsInRoot().center.x) },
                    )
                }
            }
        }
        BasicText(
            if (NewRowChoice.CHANNEL in choices) stringResource(R.string.edit_new_row_caption, choiceMax(NewRowChoice.APP_ROW), choiceMax(NewRowChoice.CHANNEL))
            else stringResource(R.string.edit_new_row_caption_apps, choiceMax(NewRowChoice.APP_ROW)),
            style = Type.caption,
            modifier = Modifier.padding(start = ShelfLayout.PAD_START.dp, top = ShelfLayout.NEW_ROW_CAPTION_GAP.dp),
        )
    }
}

/**
 * R167:编辑页重组探针。`adb shell setprop log.tag.UnitedUPerf D` 后冷启动,每次整页 / 每层重组在 logcat 打一行;平时 isLoggable 为假,不打。
 * 量「焦点移动时谁在重组」用(报告 #5 的验收),不影响任何行为。
 */
internal object EditPerf {
    const val TAG = "UnitedUPerf"
    private val on: Boolean by lazy { android.util.Log.isLoggable(TAG, android.util.Log.DEBUG) }
    fun log(what: String) { if (on) android.util.Log.d(TAG, what) }
}
