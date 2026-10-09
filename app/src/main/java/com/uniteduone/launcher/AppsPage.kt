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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * **所有应用页**(R90,Gordon 2026-09-27 定;照 Google TV 的「应用」页):顶栏「应用」胶囊打开。整屏 `MenuBg` 底,
 * 标题「应用」,下面一张网格放「添加应用」列表同一口径([Apps.pickerCandidates] / [pickerGroupOf])的全部应用——
 * 「应用」分组在上,「系统工具」分组放最后并带分组标题;两组各按名字排(改过名的按改过的名字)。**已在桌面上的也列出**。
 * 卡片就是首页的 [AppCard](卡片图口径 R88:自定义图 → 横幅 → 图标 + 边缘色底),122 dp 卡(R121 起借中档,此前小档)、一行 [AppsPageLayout.COLUMNS] 张、带名字。
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
 * - **数据来自进程级缓存 [AppsPageCache]**(R105):打开时有缓存就同一帧画出来;后台刷新回来内容不同才替换,
 *   包的清单变了(装 / 卸)时目标按包名重算([appsPageSwap] / [appsPageRetarget]),被卸载的那张 → 同组原位置补上来的下一张。
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
    /**
     * 一行 6 张 122 dp 卡:6 × 122 + 5 × 20 = 832 dp,左 58 dp 基准线起,右缘 890,聚焦放大 + 描边后仍在 960 dp 屏内。
     * **R121(2026-09-28)**:三档改成「一行正好 5 / 6 / 8 张」后小档变成 86 dp、122 成了中档——这一页借**中档**,
     * 卡宽与版式逐像素不变;中档的定义本来就是「一行 6 张不平移」,与这里的 [COLUMNS] 同一个数。
     */
    const val COLUMNS = 6
    val CARD_SIZE = GtvCardSize.MEDIUM
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
 * 包变动 / 刷新之后,原来的焦点目标 [target](旧表里的下标)在新表里落哪(纯函数,R105):
 * - 那个包还在 → 它的新下标(焦点按包名跟着走,铁律 5:目标与当前位置分开);
 * - 没了(卸载)→ **同一组里**原位置之后第一张还在的(= 网格上补进那一格的那张)→ 同组之前最近一张 → 0(页首)。
 *   按组找:「应用」组最后一张被卸载时,平铺表的下一张是「系统工具」第一张,但它在网格上不是那一格,落上一张才对。
 */
internal fun appsPageRetarget(old: AppsPageData, new: AppsPageData, target: Int): Int {
    if (new.items.isEmpty()) return 0
    val oldPkgs = old.items.map { it.app.packageName }
    val newIdx = new.items.withIndex().associate { (i, c) -> c.app.packageName to i }
    val t = oldPkgs.getOrNull(target) ?: return target.coerceIn(0, new.items.lastIndex)
    newIdx[t]?.let { return it }
    val group = if (target < old.appCount) 0 until old.appCount else old.appCount until old.items.size
    for (k in target + 1 until group.last + 1) newIdx[oldPkgs[k]]?.let { return it }
    for (k in target - 1 downTo group.first) newIdx[oldPkgs[k]]?.let { return it }
    return 0
}

/** 一次替换:新数据、新目标;[reposition] = 包的清单变了(不只是改了名),要按新目标重新落焦点。 */
internal data class AppsPageSwap(val data: AppsPageData, val target: Int, val reposition: Boolean)

/**
 * 缓存刷新回来的 [fresh] 要不要替换正在画的 [shown](纯函数,R105 stale-while-revalidate):
 * 没数据 / 内容相同 → null(不替换,不重组、焦点不动);不同 → 替换,目标按包名重算([appsPageRetarget])。
 */
internal fun appsPageSwap(shown: AppsPageData?, fresh: AppsPageData?, target: Int): AppsPageSwap? {
    if (fresh == null || fresh == shown) return null
    if (shown == null) return AppsPageSwap(fresh, target.coerceIn(0, (fresh.items.size - 1).coerceAtLeast(0)), reposition = true)
    val samePkgs = shown.items.map { it.app.packageName } == fresh.items.map { it.app.packageName }
    return AppsPageSwap(fresh, if (samePkgs) target else appsPageRetarget(shown, fresh, target), reposition = !samePkgs)
}

/**
 * **应用页的进程级缓存**(R105,Gordon 2026-09-27 真机:点「应用」卡一下、页面弹出慢)。原来每次打开都在 IO 线程重跑
 * [Apps.pickerCandidates](查全部包的启动入口、标签、系统标记),数据回来前网格是空的——模拟器实测按下到网格首帧
 * 1.4–1.8 s。现在:
 * - 桌面起来、主线程第一次空闲后在后台**预热**一次([prewarm]:列表 + 前 [PREWARM_CARDS] 张卡片图进 [Apps.pickerCard] 的 LRU);
 * - 此后 `revision` 每变一次(装 / 卸 / 更新应用、改名、换卡片图……)MainActivity 在后台 [refresh];
 * - 打开页面时**有缓存就同一帧画出来**,再在后台重读一次核对,内容不同才替换([appsPageSwap])。
 * 读写串行(一把锁),读的时候又有人要刷新的,跑完再读一遍;更早的请求已被更晚开始的一次读覆盖的,直接跳过。
 */
object AppsPageCache {
    /** 预热时顺带解码的卡片图张数:前三行(一行 [AppsPageLayout.COLUMNS] 张),打开时第一屏就有图。 */
    const val PREWARM_CARDS = 3 * AppsPageLayout.COLUMNS
    /** 打开页面画出缓存之后,等这么久再在后台核对(列表与每张已缓存的卡片图),不跟开页那几帧抢 CPU。 */
    const val REVALIDATE_DELAY_MS = 600L

    private val state = kotlinx.coroutines.flow.MutableStateFlow<AppsPageData?>(null)
    /** 最近一次读到的列表;null = 还没读过。StateFlow 自己按 equals 去重:内容相同的刷新不会通知任何人。 */
    val data: kotlinx.coroutines.flow.StateFlow<AppsPageData?> = state

    private val lock = kotlinx.coroutines.sync.Mutex()
    private val requested = java.util.concurrent.atomic.AtomicLong(0)
    /** 已完成的那次读开始时,请求计数是多少——不小于某个请求的号,说明那次读是在这个请求之后开始的,已经覆盖它。 */
    @Volatile private var covered = 0L

    /** 后台重读一次(IO 线程跑,任意线程调)。 */
    suspend fun refresh(ctx: Context) {
        val ticket = requested.incrementAndGet()
        lock.withLock {
            if (covered >= ticket) return
            val start = requested.get()
            val t0 = android.os.SystemClock.uptimeMillis()
            val fresh = withContext(Dispatchers.IO) { loadAppsPage(ctx.applicationContext) }
            android.util.Log.i("UnitedU", "appsPage list loaded ${fresh.items.size} items in ${android.os.SystemClock.uptimeMillis() - t0} ms")
            covered = start
            state.value = fresh
        }
    }

    /** 预热:读列表,再把前 [PREWARM_CARDS] 张卡片图解码进 LRU(已在缓存里、版本戳对得上的不重解)。 */
    suspend fun prewarm(ctx: Context) {
        refresh(ctx)
        val app = ctx.applicationContext
        val pkgs = state.value?.items.orEmpty().take(PREWARM_CARDS).map { it.app.packageName }
        withContext(Dispatchers.IO) { for (p in pkgs) runCatching { Apps.pickerCard(app, p) } }
    }
}

/** 应用页长按 / MENU 菜单第一层的项(R90 起「打开 / 加到桌面…」,R106 在「打开」下面加「卸载」)。 */
enum class AppsMenuAction { OPEN, UNINSTALL, ADD_TO_HOME }

/** 第一层菜单(纯函数):卸载不了的(系统预装、没更新过)不列「卸载应用」。 */
fun appsMenuActions(canUninstall: Boolean): List<AppsMenuAction> =
    if (canUninstall) listOf(AppsMenuAction.OPEN, AppsMenuAction.UNINSTALL, AppsMenuAction.ADD_TO_HOME)
    else listOf(AppsMenuAction.OPEN, AppsMenuAction.ADD_TO_HOME)

/**
 * 能不能走系统卸载(纯函数):非系统应用能;系统预装但更新过的也能(系统卸载页给的是「卸载更新」);
 * 系统预装、没更新过的不能——`ACTION_DELETE` 对它只会报错或什么都不做。
 */
fun canUninstall(isSystem: Boolean, isUpdatedSystem: Boolean): Boolean = !isSystem || isUpdatedSystem

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
    onFocusedApp: (PickerCandidate?) -> Unit,
    onBack: () -> Unit,
) {
    // R108:淡出中的残影(见 SettingsFade.kt)当作被盖住——定位效果与看门狗让路(守卫与 key 同一个合并后的量,铁律 6);
    // 每张卡 canFocus = false;不收返回键;不再上报焦点(关掉又马上打开时,新页的上报不会被残影的 null 盖掉)。
    val ghost = LocalPageGhost.current
    @Suppress("NAME_SHADOWING")
    val covered = covered || ghost
    val ghostNow by rememberUpdatedState(ghost)
    val ctx = LocalContext.current
    // R105:有缓存就第一帧画缓存(StateFlow 的当前值同步可读),打开时后台再读一次核对;
    // 之后 revision 变了由 MainActivity 在后台刷新缓存,这里只接结果。
    val fresh by AppsPageCache.data.collectAsState()
    var data by remember { mutableStateOf(AppsPageCache.data.value) }
    // 核对放在开页之后:有缓存时先让带卡片的头几帧画出去,再在后台重读(IO 线程与主线程抢 CPU,A95L 只有几个慢核);
    // 没有缓存(预热还没跑完)时立刻读,和原来一样。
    LaunchedEffect(Unit) {
        if (data != null) { withFrameNanos { }; withFrameNanos { }; kotlinx.coroutines.delay(AppsPageCache.REVALIDATE_DELAY_MS) }
        AppsPageCache.refresh(ctx)
    }
    androidx.activity.compose.BackHandler(enabled = !ghost) { onBack() }
    val items = data?.items.orEmpty()
    val lines = remember(data) { data?.let { appsPageLines(it.appCount, it.items.size - it.appCount) } ?: listOf(AppsLine.Title) }

    // requester 表按**包的清单**换(不是按张数):同样张数、换了应用(卸一个装一个)也要换表,定位效果据此重跑、按包名落到目标。
    val pkgs = remember(items) { items.map { it.app.packageName } }
    val reqs = remember(pkgs) { List(items.size) { FocusRequester() } }
    val requesters by rememberUpdatedState(reqs)
    /** 目标:回来时落哪(铁律 5,与 [holder] 分开)。只在不还原时跟着自报的焦点走;换数据时按包名重算。 */
    var focusedIdx by remember { mutableStateOf(0) }
    /** 现在持有焦点的那一张(只信控件自报,铁律 4);null = 网格里没有。 */
    var holder by remember { mutableStateOf<Int?>(null) }
    // 初值 true:第一帧 Compose 若自己把焦点给了某一张,那次上报不能改写目标;定位效果落地后放开。
    var restoring by remember { mutableStateOf(true) }
    // **换数据**(R105):内容不同才换。包的清单变了时先冻结目标(restoring)再换表——被卸载的那一格节点一拆,
    // 系统当场把焦点派给别的卡,那次上报不能改写刚按包名算好的目标(铁律 5);定位效果随 requester 表换新重跑、落地后放开。
    LaunchedEffect(fresh) {
        val swap = appsPageSwap(data, fresh, focusedIdx) ?: return@LaunchedEffect
        if (swap.reposition) restoring = true
        focusedIdx = swap.target
        data = swap.data
    }
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
        if (ghostNow) return@LaunchedEffect
        onFocusedApp(holder?.let { items.getOrNull(it) }?.let { c ->
            c.copy(app = Apps.cachedPickerCard(c.app.packageName)?.copy(label = c.app.label) ?: c.app)
        })
    }
    DisposableEffect(Unit) { onDispose { if (!ghostNow) onFocusedApp(null) } }
    // 打点:带着卡片的那一次组合之后的下一帧开始时,那一帧已经画出去了(R105 量打开耗时)。
    LaunchedEffect(items.isNotEmpty()) {
        if (items.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        AppsPagePerf.gridShown(items.size)
    }

    // R134:卡片名与首页卡片标题同一个字号(14,Type.BODY);R109 时这一页单独小了 1 sp(13),同一张卡在两页上名字大小不一。
    val metrics = Theme.gtvCardMetrics(AppsPageLayout.CARD_SIZE)
    Box(Modifier.fillMaxSize().pageBackdrop()) {
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
                        PageHeader(stringResource(R.string.apps_page_title), stringResource(R.string.apps_page_hint))
                    }
                    AppsLine.ToolsHeader -> Box(
                        Modifier.height(AppsPageLayout.HEADER_LINE.dp).padding(start = GtvLayout.CONTENT_KEYLINE.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        BasicText(
                            stringResource(R.string.edit_picker_system_tools),
                            style = Type.section,
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
                                modifier = Modifier.focusRequester(reqs[i]).focusProperties { if (ghost) canFocus = false },
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
    // **图的状态按包名记**(R105):卡片是按格子位置组合的(没有 key,焦点节点才稳),列表一变(卸掉一个、后面整体前移)
    // 同一格就换了应用。原来用 produceState——它的值不随 key 重置,新应用的图读回来之前这一格一直画着上一个应用的图,
    // 读不到(null)就永远画着(模拟器实测:卸掉 United UI 后 YouTube 那一格显示成前一格 UnitedU GTV 的横幅)。
    // revision:换了卡片图 / 应用更新后重读(pickerCard 按版本戳自己判断要不要真的重解码)。
    // 已有缓存图的格子晚一点再核对版本戳(同上,不跟开页那几帧抢 CPU);没有图的立刻读。
    var card by remember(pkg) { mutableStateOf(Apps.cachedPickerCard(pkg)) }
    LaunchedEffect(pkg, revision) {
        if (card != null) kotlinx.coroutines.delay(AppsPageCache.REVALIDATE_DELAY_MS)
        card = withContext(Dispatchers.IO) { Apps.pickerCard(ctx, pkg) } ?: card
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

/**
 * 打开耗时打点(R105):顶栏「应用」按下(`openApps`)到网格第一次带着卡片画出来的那一帧,写一行 logcat
 * (`adb logcat -s UnitedU`)。只记每次打开的第一帧,之后的刷新不再报。主线程读写。
 */
internal object AppsPagePerf {
    private var openedAt = 0L
    fun opened() { openedAt = android.os.SystemClock.uptimeMillis() }
    fun gridShown(items: Int) {
        val t = openedAt
        if (t == 0L) return
        openedAt = 0L
        android.util.Log.i("UnitedU", "appsPage grid shown +${android.os.SystemClock.uptimeMillis() - t} ms ($items items)")
    }
}

/**
 * 应用页的长按 / MENU 菜单状态(R90):[rows] == null 是第一层,非 null 是「加到哪一行」那一层(layout.json 的行)。
 * [canUninstall]:第一层列不列「卸载应用」(R106,[appsMenuActions])。
 * [rowApps](R163)与 [rows] 一一对应:每一行**现有应用的显示名**(行内顺序、只含装着的)。行没有名字了,第二层的药丸
 * 画「行图标 + 小字应用名」认行(两行图标相同时靠小字分),见 [rowNamesSummary]。
 */
data class AppsMenu(
    val app: AppEntry,
    val canUninstall: Boolean = false,
    val rows: List<LayoutRow>? = null,
    val rowApps: List<List<String>> = emptyList(),
)

/** 「加到桌面…」第二层小字里最多列几个应用名(R163);再多的写成「 等 N 个」。 */
internal const val ROW_SUMMARY_MAX_NAMES = 2

/**
 * 「加到桌面…」第二层每颗药丸的标签(R163,纯函数;2026-10-02 Gordon:11 sp 小字太丑 → 改进 15 sp 标签、只列两个):该行现有应用的显示名,
 * 用 [sep] 连起来,最多前 [ROW_SUMMARY_MAX_NAMES] 个;超过的写成 [more](已连好的前几个, 这一行一共几个)——「A、B（5）」(括号里是这一行一共几个);一个都没有写 [empty](「空」)。
 * 分隔符与「等 N 个」的措辞跟界面语言走,由调用方按资源给。
 */
internal fun rowNamesSummary(
    names: List<String>,
    sep: String,
    empty: String,
    more: (joined: String, total: Int) -> String,
): String = when {
    names.isEmpty() -> empty
    names.size <= ROW_SUMMARY_MAX_NAMES -> names.joinToString(sep)
    else -> more(names.take(ROW_SUMMARY_MAX_NAMES).joinToString(sep), names.size)
}

/**
 * 每一行现有应用的显示名(R163,给 [AppsMenu.rowApps]):行内顺序、只含**装着且能启动**的包(与首页同口径);显示名取
 * 自定义标题,没有就用应用名,应用名读不到用包名。IO 线程调用(枚举应用 + 读标签 + 读 titles.json,不解码任何位图)。
 * R164:频道行在这里是空列表(它没有应用),第二层本来就不列它。
 */
@androidx.annotation.WorkerThread
internal fun rowAppNames(ctx: Context, layout: List<LayoutRow>): List<List<String>> {
    val needed = layout.flatMap { it.apps }.toSet()
    val entries = runCatching { Apps.load(ctx, needed, withBitmaps = emptySet(), withLabels = needed) }.getOrDefault(emptyMap())
    val titles = Titles.read(ctx)
    return layout.map { row ->
        row.apps.mapNotNull { pkg -> entries[pkg]?.let { e -> titles[pkg] ?: e.label.ifBlank { pkg } } }
    }
}
