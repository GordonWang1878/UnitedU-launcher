package com.uniteduone.launcher

import android.view.KeyEvent as AKey
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
}

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
 * ≤ 0 时整张不画,null 时不裁。只是绘制:不改布局、不影响可聚焦性。
 */
private fun Modifier.clipPastShelfEnd(rightDp: () -> Float?): Modifier = drawWithContent {
    val rd = rightDp() ?: return@drawWithContent drawContent()
    val r = rd.dp.toPx()
    if (r <= 0f) return@drawWithContent
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
) {
    val ctx = LocalContext.current
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
    var rows by remember { mutableStateOf(Layout.read(ctx)) }
    var overlay by remember { mutableStateOf<EditOverlay?>(null) }
    val overlayOpen = overlay != null
    var carry by remember { mutableStateOf<EditCarry?>(null) }
    val needed = remember(rows) { rows.flatMap { it.apps }.toSet() }
    // 与旧编辑页同一做法:null 区分「还在加载」与「加载失败」;连同「这份数据是给哪套 needed 算的」一起存(R67,见 editCardShown)
    val loadedFor by produceState<Pair<Set<String>, Map<String, AppEntry>>?>(initialValue = null, needed, revision) {
        value = needed to withContext(Dispatchers.IO) {
            // 必须兜底:produceState 里抛出会终结 Recomposer,应用当场崩溃
            runCatching { Apps.load(ctx, needed, withBitmaps = needed, withLabels = needed) }.getOrDefault(emptyMap())
        }
    }
    val all = loadedFor?.second
    fun shownNow(): (String) -> Boolean {
        val lf = loadedFor
        val found = lf?.second?.keys.orEmpty()
        return { pkg -> editCardShown(pkg, lf?.first, found) }
    }
    /** 看得见的那份(R67);回调里一律现调,不用组合期的 viewRows(拿起的方向键可能在两次组合之间连着来)。 */
    fun view(): List<LayoutRow> = visibleRows(rows, shownNow())
    fun shelvesNow(): List<Shelf> = shelvesOf(view())
    fun applyView(edited: List<LayoutRow>, base: List<LayoutRow> = rows) {
        rows = withVisibleEdits(base, edited, shownNow())
    }
    val viewRows = view()
    val shelves = shelvesOf(viewRows)

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
    fun retarget(s: ShelfSpot) {
        retargetTick++
        target = s
    }
    fun report(s: ShelfSpot, got: Boolean) {
        if (got) {
            holder = s
            if (s.zone == ShelfZone.CARDS) shelfCol[s.shelf] = s.index
            // 读的是活的状态(不是组合期的 val):重定位 / 暂停 / 拿起中,Compose 抢先给出的焦点事件改不动目标
            if (retargetTick == retargetDone && !paused && carry == null) target = s
        } else if (holder == s) holder = null
    }

    // ---------------- 写盘 ----------------
    val scope = rememberCoroutineScope()
    val knownOnDisk = remember { java.util.concurrent.atomic.AtomicReference(rows.flatMapTo(HashSet()) { it.apps }.toSet()) }
    fun persist() {
        val snapshot = rows
        scope.launch {
            // layoutWrites 串行:较早的快照不会后落盘;rewrite 整段在 Layout 的锁里(落盘铁律)
            val ok = withContext(layoutWrites) {
                var written: List<LayoutRow>? = null
                val landed = Layout.rewrite(ctx) { disk ->
                    dropRemovedElsewhere(snapshot, disk, knownOnDisk.get()) { Apps.isInstalled(ctx, it) }.also { written = it }
                }
                if (landed) written?.let { w -> knownOnDisk.set(knownAfterWrite(knownOnDisk.get(), w)) }
                landed
            }
            if (!ok) showToast(ctx.getString(R.string.edit_toast_order_not_saved), true)
        }
    }

    // ---------------- 拿起 ----------------
    fun startCarry(row: Int, col: Int) {
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
        applyView(next, base = c.original)
        carry = c.copy(pos = pos)
        retarget(ShelfSpot(pos.row, ShelfZone.CARDS, pos.col))
    }
    /** 放下:没动过不写盘;显式再重定位一次(被搬的应用途中被卸载时,节点会被摘掉,账本冻结期间没人上报)。 */
    fun dropCarry() {
        val c = carry ?: return
        carry = null
        if (rows != c.original) persist()
        retarget(ShelfSpot(c.pos.row, ShelfZone.CARDS, c.pos.col))
    }
    /** 取消:整份放回、不写盘、焦点回出发格。入口:返回键、ON_PAUSE、任何浮层要打开。 */
    fun cancelCarry() {
        val c = carry ?: return
        carry = null
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
                if (apps.isEmpty()) deleteRowAt(si) else overlay = EditOverlay.Confirm(si, r.icon, apps.size)
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
    val resumeNow by rememberUpdatedState { paused = false; retarget(target) }
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
        // 目标格若是注定被整格替换的加载占位,先等它换完(旧编辑页 §0-16 的根因:焦点落在占位上,数据一到占位被真卡替换,
        // 焦点随旧节点消失、Compose 从左上角往下找)。只等目标格自己;加载失败也会对上,不会卡住
        snapshotFlow {
            val sh = shelvesOf(view())
            val s = clampSpot(sh, wanted)
            val pkg = (sh.getOrNull(s.shelf) as? Shelf.AppShelf)?.takeIf { s.zone == ShelfZone.CARDS }?.apps?.getOrNull(s.index)
            pkg == null || loadedFor?.first == rows.flatMap { it.apps }.toSet() || loadedFor?.second?.containsKey(pkg) == true
        }.first { it }
        val want = clampSpot(shelvesOf(view()), wanted)
        target = want
        var frames = 0
        while (frames < 60 && holder != want) {   // 退出判据:目标自报(铁律 2),不信 requestFocus() 的返回
            withFrameNanos { }
            runCatching { req(want).requestFocus() }
            frames++
        }
        // 到 60 帧上限还没落下:账本改成焦点此刻真正所在的那一格,下次 ON_RESUME 不再按过期的目标重定位。
        // 守卫同 report():暂停中 / 拿起中焦点停在 Compose 随手派的地方(多半是第一张卡),不能写进目标(铁律 5)
        if (holder != want && !paused && carry == null) holder?.let { target = it }
        retargetDone = retargetTick
    }

    // ---------------- 看门狗(铁律 3 / 6):守卫里的每个量都在 key 里 ----------------
    val noHolder = holder == null
    LaunchedEffect(focusNonce, all, rows, noHolder, overlayOpen, retargeting, paused, ghost) {
        if (ghost || retargeting || overlayOpen || paused || !noHolder) return@LaunchedEffect
        if (all == null && rows.any { it.apps.isNotEmpty() }) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        if (holder != null) return@LaunchedEffect
        var frames = 0
        while (holder == null && frames < 60) {
            withFrameNanos { }
            runCatching { req(clampSpot(shelvesOf(view()), target)).requestFocus() }
            frames++
        }
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

    // ---------------- 纵向位移(焦点线,spec §2.1)----------------
    var viewportPx by remember { mutableStateOf(0) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    // 焦点层:平时 = 此刻持有焦点的那一层(没人持有才看目标)——非焦点层的胶囊透明但可聚焦(Task 7),焦点一落上去
    // (拿起中 report 不改目标也一样)这一层同一帧就变成焦点层、胶囊随之淡入。**重定位中 / 暂停时只看目标**:那几帧 Compose
    // 会把焦点短暂派给别处(ON_RESUME、焦点节点被删行 / 卸载 / 拿起离开源行摘掉,铁律 5),跟着持有者走整页会朝错的层动一下;
    // 重定位到 60 帧上限放弃后 retargeting 即为假,回到跟着持有者走
    val activeShelf = (if (retargeting || paused) target else holder ?: target).shelf.coerceIn(0, shelves.lastIndex)
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
    val arrows = carry?.let { carryArrows(viewRows, it.pos) } ?: emptySet()

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
                    val measure = Modifier.onSizeChanged { heights[si] = it.height }
                    val place: (ShelfSpot, Float) -> Unit = { s, x -> centers[s] = x }
                    when (shelf) {
                        is Shelf.AppShelf -> AppShelfView(
                            shelf = shelf,
                            chips = shelfChips(shelves, si),
                            focus = { focusAnim.value },
                            active = si == activeShelf,
                            all = all,
                            metrics = metrics,
                            col = shelfCol[si] ?: 0,
                            carriedCol = carry?.pos?.takeIf { it.row == si }?.col,
                            arrows = arrows,
                            arrowColor = highlight,
                            accent = accent,
                            screenW = screenW,
                            req = ::req,
                            report = ::report,
                            place = place,
                            onChip = { onChip(si, it) },
                            onAddTile = { if (!ghost && carry == null && overlay == null) openPicker(si, ShelfSpot(si, ShelfZone.CARDS, 0)) },
                            // 卡片的 onClick 只可能来自指针 / 无障碍(确定键在根上就被截走):给卡片菜单
                            onCardClick = { ci -> if (!ghost && carry == null && overlay == null) openCardMenu(si, ci) },
                            modifier = measure,
                        )
                        Shelf.NewRowShelf -> NewRowShelfView(
                            shelfIndex = si,
                            shelves = shelves,
                            focus = { focusAnim.value },
                            active = si == activeShelf,
                            accent = accent,
                            req = ::req,
                            report = ::report,
                            place = place,
                            onChoice = ::onChoice,
                            modifier = measure,
                        )
                    }
                }
            }
        }

        EditTopBar(counts = editCounts(viewRows), hints = editHintSet(holder?.zone ?: target.zone, carry != null))

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
            }
        }
    }
}

/** 页头(spec §2.1):左「编辑桌面」+ 概况,右按键提示;下面一条渐隐遮罩盖住滚上来的架子(c2)。纯展示、不可聚焦。 */
@Composable
private fun EditTopBar(counts: EditCounts, hints: EditHintSet) {
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
        BasicText("$rowsText · $appsText", style = Type.caption)
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
    col: Int,
    carriedCol: Int?,
    arrows: Set<MoveDir>,
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
    val si = shelf.row
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
            Row(horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CHIP_GAP.dp), verticalAlignment = Alignment.CenterVertically) {
                chips.forEachIndexed { ci, chip ->
                    val spot = ShelfSpot(si, ShelfZone.CHIPS, ci)
                    ShelfChipPill(
                        chip = chip,
                        visible = focus,
                        onClick = { onChip(chip) },
                        onFocusChange = { report(spot, it) },
                        isFirst = ci == 0,
                        isLast = ci == chips.lastIndex,
                        modifier = Modifier.focusRequester(req(spot)).onGloballyPositioned { place(spot, it.boundsInRoot().center.x) },
                    )
                }
            }
        }
        // 当前那张夹到这一层的卡片数:卸载 / 移出让行变短时,不按过期的列号多滑一截
        val cur = col.coerceIn(0, (shelf.apps.size - 1).coerceAtLeast(0))
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
                            .zIndex(if (ci == cur) 1f else 0f)
                            // rowShiftX 保证当前那张(焦点卡 / 被拿起的卡)停下时完整落在架子里:那时不裁,柔光照常画出架子(owner 裁定);
                            // 横向位移还在动、它还没整张进架子时照样裁在架子右端(Task 10:否则新焦点卡会整张画进架子外的留白)
                            .clipPastShelfEnd {
                                shelfCardClipRight(shelfW - (ShelfLayout.PAD_START + ci * pitch + dx.value.value), metrics.cardWidth.value, ci == cur)
                            }
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
                val choices = NewRowChoice.entries
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
            stringResource(R.string.edit_new_row_caption, MAX_ROWS),
            style = Type.caption,
            modifier = Modifier.padding(start = ShelfLayout.PAD_START.dp, top = ShelfLayout.NEW_ROW_CAPTION_GAP.dp),
        )
    }
}
