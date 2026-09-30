package com.uniteduone.launcher

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 编辑页内容上下的留白(原来 verticalScroll 内容里的 padding(top/bottom = 40dp),自算位移按它留边)。 */
private val EditEdgePad = 40.dp

/** 编辑页搬运模式的状态(M4b spec §0-18):被搬的卡在哪一格、出发那一格、进入时的整份行。见 EditScreen 里 `carry` 的注释。 */
private data class EditCarry(val pos: MovePos, val from: MovePos, val original: List<LayoutRow>)

/**
 * 搬运中按下的那一下的 downTime([down]),与其中按满长按时长的那一下确定键([held])。与首页移动态
 * (MainActivity 的 moveDownTime / moveHeldDownTime)同一手法:按 downTime 认,每一下按压天然不同,不需要清(铁律 7)。
 * 不是 Compose 状态:只在按键回调里读写,不参与组合。
 */
private class CarryPresses {
    var down = -1L
    var held = -1L
}

/**
 * 编辑页视窗的首行(铁律 1:M4b 起不用 verticalScroll,纵向位移自己算)。与图片网格同一条规则
 * [keepInView],多一层:首行为 0 时标题区还在屏上,放得下的行少一些([visibleWithHeader]);
 * 首行 > 0 时标题区整个移出视窗([visibleBelow])。结果再夹到「最后一行刚好露全」为止,
 * 删行之后末尾不会留一截空白。
 *
 * 焦点行在结果下**一定完整可见**:夹之前 [keepInView] 已保证([visibleBelow] 按 ≥ [visibleWithHeader] 用,
 * 标题区移出只会多放行);夹只会往回收,收到的位置仍然露得全最后一行,而焦点行不会在它后面。
 * 量出来之前调用方把两个可见行数都传成总行数(同 PickerGrid 的约定)——视窗不动。
 */
internal fun editFirstRow(
    focusedRow: Int,
    first: Int,
    rowCount: Int,
    visibleWithHeader: Int,
    visibleBelow: Int,
): Int {
    val withHeader = visibleWithHeader.coerceAtLeast(1)
    val below = visibleBelow.coerceAtLeast(withHeader)
    val f = keepInView(focusedRow, first, if (first == 0) withHeader else below)
    val last = if (rowCount <= withHeader) 0 else (rowCount - below).coerceAtLeast(1)
    return f.coerceIn(0, last)
}

/**
 * 编辑分栏(原「编辑桌面」)。范围按设计文档 Q5:应用的**进出与排序**、换卡片图;
 * M4b 起还管**行本身**:每行行尾的「+」打开行菜单——添加应用 / 重命名此行 / 更换此行图标 /
 * 此行上移 / 此行下移 / 在下方新建一行 / 删除此行(不适用的不列,M4b spec §0-2..8)。
 * 卡片菜单的「移动位置」进入**搬运模式**(M4b spec §0-18):与首页原地移动同一套键位,可以跨行,空行也是落点。
 */
@Composable
fun EditScreen(
    /**
     * 「换卡片图」:(layout.json 行号, 包名)。**选择器会替换本页**(M7 终审 C1):选择器开着时本页不在组合里,
     * 关掉后整页重建、所有 `remember` 归零——调用方必须把这两个值原样当 [initialTarget] 喂回来,
     * 焦点才回得到这张卡。行号就是本页 `rows` 的下标(本页连空行都画,行号与 layout.json 一致;列号不一致,见 [initialTarget])。
     * 返回选择器是否真的打开了(存储没就绪时打不开)。**R138**:打开了的话本页不收菜单——本页随即变成淡出的残影
     * (MainActivity 的 FadeSwitch),菜单原样留在画面上,选择器在它上面淡入,是一次真正的交叉淡化;
     * 残影只多画一个淡入时长就离开组合,菜单开着这件事不会被任何人看到「活」的一面。
     */
    onPickIcon: (row: Int, pkg: String) -> Boolean,
    onExit: () -> Unit,
    focusNonce: Int = 0,
    revision: Int = 0,
    cardsPerRow: Int = 6,
    /** 卡片标题全局开关(design §2):编辑页与首页共用同一份 titles.json,标题同样显示——
     *  编辑时看得见名字更好认。 */
    showTitles: Boolean = false,
    /**
     * 本页「换卡片图」的选择器关掉之后(见 [onPickIcon])带进来的 **(layout.json 行号, 包名)**
     * (M4b 起首页长按「移动位置」改为首页原地移动,不再从这里进来);null = 正常进入,
     * 落在第 1 行第 1 张卡(第 1 行空则落它的「+」)。
     *
     * 用包名而不是列号:本页只画已装、可启动的包(Ruling R67,与首页 `buildRows` 同口径),
     * 看得见的列号 ≠ layout.json 下标(盘上可能还有看不见的包:被停用的、还没清掉的已卸载包)。
     * 包名在一行里唯一(`Layout.read` 做过 distinct),在**看得见的那份**里按它查,才落在同一张卡上。
     */
    initialTarget: Pair<Int, String>? = null,
    /**
     * 搬运模式开始 / 结束时各报一次(M4b spec §0-18),本页离开组合时补报 false。MainActivity 据此在搬运中不让 MENU
     * 退出编辑页、把确定键的按下 / 重复 / 松开原样交给本页(平时它先吞掉确定键的重复事件,本页就认不出长按)。
     */
    onCarryingChange: (Boolean) -> Unit = {},
) {
    val ctx = LocalContext.current
    val showToast = LocalToast.current   // R139:应用内提示条
    /**
     * **R138:本页也会以残影出现**——打开「换卡片图」时 MainActivity 让它多留一个淡入时长、跟着淡出(选择器在上面淡入)。
     * 残影里:整棵卡片子树不可聚焦、看门狗与重定位让路(守卫与 key 同一个量,铁律 6)、两个返回键回调关掉、
     * 不再向 MainActivity 报搬运状态。菜单这些浮层自己读同一个量让路(GearMenu 等)。
     */
    val ghost = LocalPageGhost.current
    // 与首页同一套卡片档位尺寸,编辑页的卡片才会和首页一样大。
    // Ruling R18(终审 2026-09-20):这里原来读 Theme.cardMetrics(cardsPerRow)(HomeLayout 那一套,
    // 6 张时 124×69.75dp),首页早已换成 gtv 三档(当时中档 153×86dp;R59 起 122 / 137 / 153),编辑页里的同一个应用因此比首页
    // 小了一整圈——B5-a 裁定的三档固定尺寸是首页专用的新模型,HomeLayout 那套按张数反推宽度的
    // 公式已经作废(decision table B5),编辑页当年漏改。见 Theme.gtvCardMetrics / cardsPerRowToGtvSize。
    val metrics = Theme.gtvCardMetrics(cardsPerRowToGtvSize(cardsPerRow))
    // 自定义标题表,revision 变化(改过标题)时重读;与首页同一份数据源。
    val titles by produceState(emptyMap<String, String>(), revision) {
        value = withContext(Dispatchers.IO) { Titles.read(ctx) }
    }
    var rows by remember { mutableStateOf(Layout.read(ctx)) }
    var picking by remember { mutableStateOf<Int?>(null) }        // 正在给第几行加应用
    var acting by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (行, 位置) 的操作菜单
    // M4b 行管理的四层浮层,都记「第几行」(= layout.json 行号 = 本页 rows 下标)。
    // **坐标口径(Ruling R67)**:`rows` 是整份(盘上的原样,含看不见的包);凡是「第几格」(acting 的列、
    // 搬运位置、焦点目标)一律是**看得见的那份**(view(),与首页同口径)里的列号。行内改动在 view() 上算,
    // 经 applyView() 合回整份(看不见的包留在原位,见 withVisibleEdits);行级操作(增删 / 交换 / 改名 / 图标)直接作用在整份上。
    var rowMenu by remember { mutableStateOf<Int?>(null) }          // 行菜单(按行尾「+」打开)
    var renamingRow by remember { mutableStateOf<Int?>(null) }      // 改行名对话框
    var iconRow by remember { mutableStateOf<Int?>(null) }          // 行图标选择器
    var confirmDeleteRow by remember { mutableStateOf<Int?>(null) } // 删非空行之前的确认框
    /**
     * 有任何一层浮层开着。开着时卡片子树不可聚焦(`canFocus = !overlayOpen`),看门狗与重定位让路——
     * 焦点归那一层自己管(铁律 3)。**新加的浮层必须并进这里**:它同时是看门狗的 key 与守卫(铁律 6),
     * 漏一个,关掉那一层时看门狗不会重启(`picking` 当年就是这样让整个界面只剩返回键能用)。
     */
    val overlayOpen = picking != null || acting != null ||
        rowMenu != null || renamingRow != null || iconRow != null || confirmDeleteRow != null
    /**
     * **搬运模式**(M4b spec §0-18,真机验收后补定):卡片菜单「移动位置」进入,键位与首页原地移动同一套。null = 不在搬运。
     * 一个值装三样,理由同首页的 [MoveState]——三者总是一起变,拆成几个可空量就会有「只清了一半」的中间态:
     * `pos` = 被搬的卡现在在哪一格(按位置追踪,同一个包可以在两行里),同时就是焦点目标;`from` = 出发那一格
     * (取消后焦点回这里);`original` = 进入那一刻的整份 `rows`(取消时原样放回)。
     * 搬运中 `rows` 实时跟着改(画面跟着动),**不写盘**,只有放下才 persist()。
     * **不是浮层**:不并入 [overlayOpen],卡片照常可聚焦,焦点始终在被搬的卡上,每一步都经 retarget() 送过去;
     * 看门狗照常兜底(它回的 focusRow 与挂点 focusTarget 在搬运中只由 retarget() 写,焦点上报改不动,见 mark())。
     */
    var carry by remember { mutableStateOf<EditCarry?>(null) }
    val needed = remember(rows) { rows.flatMap { it.apps }.toSet() }
    // 和 HomeScreen 一样挪到 IO:同步解码 11 张 banner 会让进编辑界面卡一下
    // 用 null 区分「还在加载」和「加载失败/真的空」—— 与首页同一做法。
    // 用 emptyMap 当初值时,失败结果与初值**结构相等**,mutableStateOf 不触发重组、
    // 看门狗不重启,而它的守卫正是 `all.isEmpty()`:于是初始焦点一次都不会请求,
    // 进编辑界面后按什么都没反应(而进编辑界面恰恰是首页空掉后的自救动作)。
    // 连同「这份数据是给哪套 needed 算的」一起存:onPick 之后 needed 先变、all 还是旧值,
    // 新包在旧 map 里查不到 —— 不带这个的话它会被当成没装、直接藏掉,像是没加上。
    // 只有**查过**的包「查不到」才真的等于「没装」(见 editCardShown)。
    val loadedFor by produceState<Pair<Set<String>, Map<String, AppEntry>>?>(
        initialValue = null, needed, revision,
    ) {
        value = needed to withContext(Dispatchers.IO) {
            // 必须兜底:produceState 里抛出会终结 Recomposer,应用当场崩溃。
            // 而进编辑界面恰恰是首页空掉之后的自救动作,不兜底就是反复进、反复崩。
            runCatching { Apps.load(ctx, needed, withBitmaps = needed, withLabels = needed) }.getOrDefault(emptyMap())
        }
    }
    val all = loadedFor?.second
    /**
     * 这一格画不画(R67,[editCardShown]):查过没查到的包不画——不再有暗红的「未安装」占位。
     * **函数而不是组合期的 val**:搬运的方向键可能在两次组合之间连着来,读的必须是此刻的 loadedFor。
     */
    fun shownNow(): (String) -> Boolean {
        val lf = loadedFor
        val found = lf?.second?.keys.orEmpty()
        return { pkg -> editCardShown(pkg, lf?.first, found) }
    }
    /** 看得见的那份(R67):行与 rows 一一对应,只留要画的包。所有列号按它算。同上,每次现算。 */
    fun view(): List<LayoutRow> = visibleRows(rows, shownNow())
    /** 把对 view() 的行内改动合回整份 rows([withVisibleEdits]:看不见的包留在原来的下标上)。 */
    fun applyView(edited: List<LayoutRow>, base: List<LayoutRow> = rows) {
        rows = withVisibleEdits(base, edited, shownNow())
    }
    /** 这一次组合画的那份(= 此刻的 view());组合期读,回调里一律现调 view()。 */
    val viewRows = view()
    // 每个新界面都必须显式给初始焦点,否则遥控器进来后按什么都没反应
    // (2026-09-10 实测:编辑界面漏了这一步,后续所有按键全部落空)。
    // 每行一个 focus requester:改完某一行后把焦点还给那一行,
    // 否则列表重组会让焦点掉回第一行,连着操作同一行时很别扭。
    val rowFocus = remember(rows.size) { List(rows.size.coerceAtLeast(1)) { FocusRequester() } }
    // 看门狗与重定位都在协程里跑,读的必须是**当前**这一份 requester(同 GearMenu):
    // 新建 / 删行让 rows.size 变,上面那张表整张换新,捕获启动时那一份的话请求全打在已经摘掉的旧 requester 上。
    val requesters by rememberUpdatedState(rowFocus)
    // 「当前行」跟着真实焦点走,给看门狗用(焦点没了时回到最后待过的那一行)。
    var focusRow by remember { mutableStateOf(0) }
    // 「目标行」只由动作与取消路径设置,**绝不跟着焦点事件走**。
    // 上一版让 focusRow 一身二职,结果:onDismiss 明明设了目标行 1,
    // Compose 抢先把焦点给了 (0,0),那次上报把 focusRow 改回 0,
    // 重定位于是去还原第 0 行的第 0 格 —— 焦点静默跳到 VIDEO 行,
    // 而下一步「移出」就打在别的行上(复审因此误删了一个应用)。
    // 这是列号那边早就拆过的同一个坑,行号这边漏了。
    var retargetRow by remember { mutableStateOf(0) }
    // 「目标列」同理另存一份(M4b):新建 / 删行让 rows.size 变,下面两张按行的表(remember(rows.size))
    // 整张换新、全部归零,retarget() 同步写进旧表的那一格随之作废——删行后焦点本该落上一行的「+」,
    // 会落到它的第 1 张卡。重定位效果开跑时把它写进换新之后的那张表。
    var retargetCol by remember { mutableStateOf(0) }
    // 与首页的 restoring 同构:重定位期间冻结目标,平时让目标跟着导航走。
    // 没有它的话 focusTarget 不敢跟导航,看门狗就只能把焦点还到「上次菜单动作留下的那一格」。
    // 用「安排了几次 / 完成了几次」的比对表达,而不是一次性布尔闩(铁律 7):
    // 闩只有一条路写回 false,新增任何早退分支就会永久卡住,而它是看门狗的第一道守卫。
    // 派生量没人需要去清,而且守卫读的量本身就是 key。
    // **tick 从 1 起、done 从 0 起**(M4b):本页一出现就处在一次待办的重定位里,目标 (0, 0) ——
    // 这就是初始焦点(第 1 行第 1 张卡;第 1 行空时 (0, 0) 就是它的「+」),而且从第一帧起就是冻结的,
    // 卡片数据到位之前的任何焦点事件都改写不了它。根因见下面重定位效果里「先等数据」那段。
    var retargetTick by remember { mutableStateOf(1) }
    var retargetDone by remember { mutableStateOf(0) }
    val retargeting = retargetTick != retargetDone
    // 每行**当前**聚焦到第几格(含行尾加号),只用来算这一行的横向位移,跟着真实焦点走。
    val rowFocused = remember(rows.size) { mutableStateListOf(*Array(rows.size.coerceAtLeast(1)) { 0 }) }
    // 每行**要把焦点送到**第几格。**必须与上面那个分开**:浮层关闭时 Compose 会先把焦点
    // 还给第一张卡,若用同一个量,那次焦点事件会立刻把目标改写成 0,请求就跟着跑偏 ——
    // 实测症状是「往右移」之后焦点回到行首而不是跟着那张卡。
    val focusTarget = remember(rows.size) { mutableStateListOf(*Array(rows.size.coerceAtLeast(1)) { 0 }) }
    // **焦点看门狗**(与 HomeScreen 同一套判据)。这里比首页更必要:浮层一开,
    // 下面那个 `canFocus = !overlayOpen` 会让 Compose 对正处于 Active 的卡片 clearFocus;
    // 浮层关闭时 canFocus 恢复,但**没有任何人重新请求焦点** —— 菜单项里的动作都记得
    // 安排重定位,唯独「按返回取消」这条路原本没有,于是菜单收了、遥控器全死,
    // 只剩返回键能用(再按一次直接退出编辑界面)。看门狗把这一类全兜住,
    // 不用再逐个 onDismiss 补,也不会漏下一个。
    /**
     * 安排一次重定位。**冻结标志必须和写目标在同一个同步动作里置位**,不能等到效果体里再置。
     * 实测(2026-09-11,只在第一行复现):浮层被移除的那次重组发生在效果体**之前**,
     * Compose 的默认恢复把焦点给了整棵树第一个可聚焦节点 = (0,0),它的 onFocusChanged
     * 此时看到 retargeting 还是 false,于是把 focusTarget[0] 改写成 0。
     * ri != 0 时被改写的是别人那一格、不影响;**ri == 0 时改写的正是效果要读的那一格**,
     * 目标变成 (0,0) 而焦点已经在 (0,0),循环一次都不跑 —— 还原静默失效。
     * 症状是「在 VIDEO 行做完任何操作,焦点都掉回第 1 张卡」,而下一次确定就打在错的应用上。
     * 所有安排重定位的地方都必须走这个函数,别再各写一遍。
     */
    fun retarget(ri: Int, col: Int) {
        retargetTick++
        focusRow = ri
        retargetRow = ri
        retargetCol = col
        focusTarget[ri.coerceIn(0, focusTarget.lastIndex)] = col
    }
    /** 这一行的「+」(行尾加号那一格 = 看得见的应用数)。行浮层关掉之后焦点都回这里。 */
    fun toRowEnd(ri: Int) {
        val r = ri.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
        retarget(r, view().getOrNull(r)?.apps?.size ?: 0)
    }

    // 写盘与搬运的四个动作放在一起,排在下面的生命周期观察者之前(它要调 cancelCarry)。
    val scope = rememberCoroutineScope()
    // 上次确知在盘上的包(进页时读到的 / 上次写下的),只在 layoutWrites 这条串行 IO 上读写。见 dropRemovedElsewhere。
    val knownOnDisk = remember { java.util.concurrent.atomic.AtomicReference(rows.flatMapTo(HashSet()) { it.apps }.toSet()) }
    fun persist() {
        val snapshot = rows
        scope.launch {
            // layoutWrites:两次 persist 按提交顺序落盘(各自上 Dispatchers.IO 的话,较早的快照可能最后落盘、盖掉较新的)。
            // rewrite:读盘上最新 → 滤掉这期间被卸载清理掉的包 → 写回,整段在 Layout 的锁里。
            val ok = withContext(layoutWrites) {
                var written: List<LayoutRow>? = null
                val landed = Layout.rewrite(ctx) { disk ->
                    dropRemovedElsewhere(snapshot, disk, knownOnDisk.get()) { Apps.isInstalled(ctx, it) }
                        .also { written = it }
                }
                // 只增不减(knownAfterWrite):换成「这次写下的」会把刚滤掉的已卸载包从集合里丢掉,下一次写盘又写回去。
                if (landed) written?.let { w -> knownOnDisk.set(knownAfterWrite(knownOnDisk.get(), w)) }
                landed
            }
            if (!ok) showToast(ctx.getString(R.string.edit_toast_order_not_saved), true)
        }
    }
    /** 进入搬运(卡片菜单「移动位置」,菜单已收):记下整份 rows 与出发格,焦点目标 = 这张卡。 */
    fun startCarry(ri: Int, pi: Int) {
        val at = MovePos(ri, pi)
        carry = EditCarry(pos = at, from = at, original = rows)
        retarget(ri, pi)
    }
    /**
     * 搬一步。到头 / 那个方向没有行 / 相邻行已有同一个应用:moveInLayout 原样返回同一个 list,什么都不动。
     * 在看得见的那份上搬(R67:不会和一个看不见的包换位、白按一下),合回时以**进入搬运那一刻的整份**为底
     * (`base = c.original`):看不见的包始终按出发时的下标摆,搬出去再搬回来,整份原样复原,放下时不会误判成「动过」。
     */
    fun stepCarry(dir: MoveDir) {
        val c = carry ?: return
        val v = view()
        val (next, pos) = moveInLayout(v, c.pos, dir)
        if (next === v) return
        applyView(next, base = c.original)
        carry = c.copy(pos = pos)
        retarget(pos.row, pos.col)
    }
    /**
     * 放下(确定键短按松开)。没动过就不写盘(同首页)。显式再调一次 `retarget(c.pos.row, c.pos.col)`,
     * 不能只信「焦点已经在那儿」:多数时候确实已经在,循环一次就追平(铁律 2 的判据当场成立,
     * 代价一次多余的重组);但被搬的应用如果在搬运途中被卸载/禁用,松开这一刻的重载会把这一格的
     * AppCard 整个摘掉(R67 起看不见的包不画)——节点没了,系统把焦点收去 (0,0),而账本(focusRow/focusTarget)
     * 搬运期间全程冻结、没人上报丢焦点,看门狗见着「有节点在报」也就不出手,焦点会停在 (0,0) 出不来。
     */
    fun dropCarry() {
        val c = carry ?: return
        carry = null
        if (rows != c.original) persist()
        retarget(c.pos.row, c.pos.col)
    }
    /**
     * 取消:整份 rows 原样放回、不写盘,焦点回出发格。入口:返回键(搬运专用的 BackHandler,本页那个兜底)、
     * ON_PAUSE(退到后台 / 系统设置侧板盖上来)、任何浮层要打开(下面的 overlayOpen 效果)。
     * 离开编辑页(HOME、leaveEdit)不必调它:本页整个离开组合,搬运随之作废,而搬运中从没写过盘。
     * 行数不变(moveInLayout 不增删行),remember(rows.size) 的几张表不换新,retarget 写进的就是当前那张。
     */
    fun cancelCarry() {
        val c = carry ?: return
        carry = null
        rows = c.original
        retarget(c.from.row, c.from.col)
    }

    // 带着 (layout 行号, 包名) 进来(「换卡片图」的选择器关掉、本页重建),数据到位后定位一次。
    // 用「已应用的目标」比对,不用一次性布尔闩(铁律 7):同一个 initialTarget 只应用一次,
    // 换了新值自然再应用。本页被「换卡片图」的选择器替换、关掉后重建时,appliedTarget 随整页
    // 归零,同一颗种子会再应用一次——这正是要的:焦点回到刚才换图的那张卡(M7 终审 C1)。
    var appliedTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(initialTarget, all) {
        val t = initialTarget ?: return@LaunchedEffect
        if (all == null || appliedTarget == t) return@LaunchedEffect
        appliedTarget = t
        val ri = t.first.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
        // **按包名查列号**,在看得见的那份里查(R67,见 initialTarget 的 KDoc)。
        // 查不到(那一行刚被别处改过)就退到行首,至少落在正确的那一行上,绝不乱指一张卡。
        val ci = view().getOrNull(ri)?.apps?.indexOf(t.second) ?: -1
        if (ci >= 0) retarget(ri, ci) else retarget(ri, 0)
    }

    // ON_PAUSE 与 ON_RESUME 做同一件事:把「当前这一格」重新安排成一次重定位(M4b)。
    // 编辑界面原本只靠看门狗,而看门狗第一行是「已经有焦点就返回」——从 X-plore 换完图回来时
    // Compose 已经抢先把焦点给了 (0,0),恢复被自己的守卫吞掉;ON_RESUME 这一次显式重定位就是为它补的。
    // **它实际做到的冻结只有重定位效果跑一轮那么长**(通常一帧:焦点本来就在那一格时一次请求都不发、当场追平),
    // 不是首页 `restoring` 那种从 ON_PAUSE 一直冻到回来(HomeScreen 的生命周期观察者)——这一轮之后
    // Compose 再动焦点,目标照样跟着走,ON_RESUME 还原的是那时记下的格。只有需要真的请求、而 Activity 已 STOPPED
    // (帧时钟暂停)时,这一轮才会挂到回前台。本轮不加新的冻结机制(复审 minor 2)。
    // 原来 ON_PAUSE 只 retargetTick++:重定位效果随即按**上一次**显式重定位的行号跑一遍——半透明的系统设置面板
    // 盖着时帧照常走,焦点会被拽回那一行。
    // 本页出现时 Activity 已是 RESUMED,addObserver 会当场补发 ON_CREATE/ON_START/ON_RESUME;那一刻初始重定位
    // 还没追平,下面的守卫让它什么都不做。**已有待办重定位时一律不插手**(复审 minor 1):待办自带行列,
    // 在这里按 focusTarget 重写一遍会把还没写进新表的目标列(retargetCol)冲掉。
    // **经 rememberUpdatedState 调**:观察者只在进页时建一次,直接写在里面的话捕获的是第一次组合的
    // focusTarget 与 retarget;新建 / 删行让那张表整张换新之后,读到的是旧表里的列号——2026-09-19 模拟器实测:
    // 删一行后停在 Kids 第 3 格,开一下系统设置面板再返回,焦点落回 Kids 第 1 格。
    val holdHere by rememberUpdatedState {
        if (!retargeting) retarget(focusRow, focusTarget.getOrElse(focusRow) { 0 })
    }
    // 退到后台 = 搬运取消(M4b spec §0-18,同首页 §0-9)。**先取消、再 holdHere**:两次调用同步挤在
    // 这一个回调里,中间不会插进一次组合。holdHere 的判据 `retargeting` 是行 206 那个普通 val,
    // 只在组合时重算一次,认不出 cancelCarry() 里 retarget() 刚做的 retargetTick++(那是对活的 State
    // 的写入;这个 val 要等下一次组合才追得上)——holdHere 读到的其实是这一轮回调开始前、上一次组合
    // 算出的旧值。真正接住的是 focusRow / focusTarget 本身:它们是 `by remember { mutableStateOf }`,
    // cancelCarry() 已经把它们同步写成出发格,holdHere 紧接着照这两个活的量再调一次 retarget(),
    // 原样落回同一格——只是多一次 tick、重定位效果空转一轮就追平,不是「看见已有待办就不插手」。
    // 同样经 rememberUpdatedState 调(理由见 holdHere)。
    val pauseCarry by rememberUpdatedState { cancelCarry() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { pauseCarry(); holdHere() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> holdHere()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // 焦点实际落在哪一格,由控件自己上报(与 HomeScreen 同一套)。
    // 用它同时替掉两处判据:看门狗的「有没有焦点」和重定位的「到位没有」。
    var focusedCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    fun report(ri: Int, pi: Int, got: Boolean) {
        if (got) focusedCell = ri to pi
        else if (focusedCell == ri to pi) focusedCell = null
    }
    // 必须把 all 也算进依赖:应用数据是异步来的,数据到位前卡片渲染成占位节点,
    // 数据到位后卡片重建 —— focusRequester 换了新节点,焦点不会自己跟过去。
    // 浮层**既要当 key 也要当 guard**,与首页的 menuOpen 对齐。原来两者都漏:
    // 按加号 → canFocus 让卡片子树失活、焦点被清 → childFocused 变 false 唤醒看门狗 →
    // 它去请求失活子树里的节点,必然失败、空转 60 帧 → 若选择器里一个可聚焦节点都没有
    // (「没有可添加的应用了」),childFocused 全程 false;按返回关掉浮层时
    // picking 不是 key、focusedCell 没变、也没安排重定位 —— **几个 key 一个都没变,
    // 看门狗不会重启**,编辑界面里只剩返回键能用。M4b 起所有浮层合成一个 overlayOpen(见其 KDoc)。
    LaunchedEffect(focusNonce, all, focusedCell, overlayOpen, retargeting, ghost) {
        if (ghost) return@LaunchedEffect
        if (retargeting) return@LaunchedEffect
        if (overlayOpen) return@LaunchedEffect
        if (focusedCell != null) return@LaunchedEffect
        if (all == null && rows.any { it.apps.isNotEmpty() }) return@LaunchedEffect
        repeat(3) { withFrameNanos {} }
        if (focusedCell != null) return@LaunchedEffect
        var frames = 0
        while (focusedCell == null && frames < 60) {
            withFrameNanos { }
            requesters.getOrNull(focusRow.coerceIn(0, requesters.lastIndex))
                ?.let { fr -> runCatching { fr.requestFocus() } }
            frames++
        }
    }

    // **显式重定位,与上面的看门狗分开。**看门狗第一行是「已经有焦点就什么都不做」,
    // 而浮层关闭时 Compose 会先把焦点还给第一张卡 —— 于是「把焦点送到第 3 格」这类
    // 明确的请求会被自己的守卫吞掉,表现为「往右移之后焦点回到行首」。
    // 退出条件必须**同时**要求「焦点真的落下了」和「落在目标格上」。
    // 只判「当前格 == 目标格」时,凡是两者本来就相同的路径(往非首行添加应用 ——
    // 加号已把当前格记成 pkgs.size,而目标恒等于新的 lastIndex;移出该行第一张 ——
    // 目标算出来是 0,当前本来就是 0)**一次请求都不会发**,焦点被交给 Compose 的默认恢复,
    // 落到第一行第一张。而跳转是静默的,下一步操作会打在别的行上 ——
    // 复审就因此误删了 VIDEO 行的一个应用。HomeScreen 那份早就是双条件,这里漏了。
    LaunchedEffect(retargetTick, ghost) {
        if (ghost) return@LaunchedEffect
        val ri = retargetRow.coerceIn(0, requesters.lastIndex)
        val wantCol = retargetCol
        // **目标格若是注定被整格替换的加载占位,先等它换完**(M4b spec §0-16 的根因,2026-09-19 模拟器实测):
        // 数据没到时,旧 map 里查不到的包渲染成加载占位 PendingCard。焦点若先落在占位上,数据一到占位被真卡
        // **整格替换**(不同的 composable = 新节点),焦点随旧节点消失;Android 的 View.clearFocus 当场
        // rootViewRequestFocus → AndroidComposeView.requestFocus(FOCUS_DOWN),Compose 从根的左上角
        // 往下找——那一刻新卡还没摆好,唯一幸存的可聚焦节点是各行的「+」,于是落在第 1 行的「+」。
        // 那次上报又发生在重定位完成之后(retargeting 已经是 false),把 focusTarget[0] 改写成「+」,
        // 看门狗看到「有焦点」也不管——进编辑页焦点就停在「+」上。
        // **只等目标格自己**(fix round 1,复审 Important #1):目标是「+」(不会被替换)、或者它的包在旧 map 里
        // (渲染成真卡,重载后原地重组、不换节点)就不等。原来是「等整份数据与 rows 对上」:移出一张卡 /
        // 删一个非空行让 needed 变小、整份重载,落到邻卡 / 上一行「+」要白等一次整轮 Apps.load——这段时间
        // 页面冻结、看门狗让路,焦点停在浮层关掉时 Compose 给的地方(第一张卡),此时按确定就打在别的应用上。
        // 仍要等的:进页(数据还没有)、刚添加的应用——它们此刻都是还没查过的包,画成占位(editCardShown)。
        // 等待期间 retargeting 为真,冻结着目标;加载失败也会对上(空 map → 这些包查过没查到、不画),不会卡住。
        // 列号是看得见的那份(view())里的,R67。
        snapshotFlow {
            val apps = view().getOrNull(ri)?.apps.orEmpty()
            val pkg = apps.getOrNull(wantCol.coerceIn(0, apps.size))
            val need = rows.flatMap { it.apps }.toSet()
            pkg == null || loadedFor?.first == need || loadedFor?.second?.containsKey(pkg) == true
        }.first { it }
        // **等完再夹**:数据一到,查过没查到的包从 view() 里消失,这一行可能变短。
        // 夹到 pkgs.size(**含行尾加号那一格**),与下面的挂点用同一个夹法。
        // 少了这一致性:目标被设成加号那一格,而挂点只夹到 lastIndex、加号只在空行时接 requester,
        // 于是焦点只能送到最后一张卡,判据恒不成立、循环跑满 60 帧,每帧把焦点拽回去。
        val col = wantCol.coerceIn(0, view().getOrNull(ri)?.apps?.size ?: 0)
        // 写进**当前**这张表(见 retargetCol 的注释);挂点随之重组到这一格。
        if (ri <= focusTarget.lastIndex) focusTarget[ri] = col
        val want = ri to col
        // retargeting 是派生量(retargetTick != retargetDone),由 retarget() 递增 tick、
        // 本效果末尾把 done 追平。没有需要清除的闩,新增早退分支也不会把它卡住 —— 最多晚一拍追平。
        var frames = 0
        while (frames < 60 && focusedCell != want) {
            withFrameNanos { }
            runCatching { requesters[ri.coerceIn(0, requesters.lastIndex)].requestFocus() }
            frames++
        }
        retargetDone = retargetTick
    }

    // **纵向位移自己算**(铁律 1,M4b spec §0-15):原来的 verticalScroll 换成「裁剪视窗 + 整块位移」,
    // 与图片网格(PickerGrid)同一招。视窗顶上是第几行由焦点行决定(见 [editFirstRow]):
    // focusRow 只在「没在重定位」时跟着自报的 got 走、重定位时由 retarget() 直接设成目标行(铁律 5:
    // 冻结期间 Compose 抢先给出的焦点事件带不动视窗)。量出来的尺寸变了(换卡片档位、标题开关)也重算一次。
    val density = LocalDensity.current
    var viewportPx by remember { mutableStateOf(0) }
    var headerPx by remember { mutableStateOf(0) }
    val rowHeights = remember { mutableStateMapOf<Int, Int>() }
    var firstVisibleRow by remember { mutableStateOf(0) }
    val edgePx = with(density) { EditEdgePad.roundToPx() }
    val gapPx = with(density) { Theme.EditRowSpacing.roundToPx() }
    // 行高取各行实测的最大值(每行都含「标题行 + 卡片行 + 行距」;开了卡片标题时,只有「+」的空行比别的行矮一截标题)
    val pitchPx = (0 until rows.size).maxOfOrNull { rowHeights[it] ?: 0 } ?: 0
    /** 扣掉 [reservedPx] 之后视窗里放得下几整行;末行的行距不必露出来。量到之前 = 全部。 */
    fun rowsThatFit(reservedPx: Int): Int =
        if (pitchPx <= 0 || viewportPx <= 0) rows.size.coerceAtLeast(1)
        else ((viewportPx - 2 * edgePx - reservedPx + gapPx) / pitchPx).coerceAtLeast(1)
    val visibleWithHeader = rowsThatFit(headerPx)
    val visibleBelow = rowsThatFit(0)
    LaunchedEffect(focusRow, rows.size, visibleWithHeader, visibleBelow) {
        firstVisibleRow = editFirstRow(focusRow, firstVisibleRow, rows.size, visibleWithHeader, visibleBelow)
    }
    // 渲染时再夹一次(删行之后首行可能越界);焦点行传首行本身 = 只夹不推。
    val firstRow = editFirstRow(firstVisibleRow, firstVisibleRow, rows.size, visibleWithHeader, visibleBelow)
    // 首行 > 0:标题区与前面各行按**实测高度**累加移出视窗,首行顶在上留白处;首行 = 0 时不动。
    val shiftPx = if (firstRow == 0) 0 else headerPx + (0 until firstRow).sumOf { rowHeights[it] ?: pitchPx }
    val yShift by animateDpAsState(
        targetValue = with(density) { (-shiftPx).toDp() },
        // Ruling R29(owner 反馈 Round 8,承 R27):编辑页的纵向平移与首页换行是同一件事——焦点在
        // 网格里移动、内容跟着平移,属 browse 手势,同样走 Theme.browseShiftSpec 的临界阻尼弹簧。
        animationSpec = Theme.browseShiftSpec(),
        label = "editYShift",
    )
    /**
     * **ui-pending #10(2026-09-23,首页 R30/R47 的编辑页版)**:最近一次落焦有没有让纵向位移的目标变。
     * 在焦点回调里与 AppCard 自己的 focused 同一个事件写入,放大据此等 [GtvLayout.FOCUS_AFTER_SHIFT_DELAY_MS]。
     * 判定只读位移的现有输入:落到第 landingRow 行后 [editFirstRow] 算出的首行,和现在画的首行比——与上面
     * `LaunchedEffect(focusRow, …)` 推进 `firstVisibleRow` 用同一个纯函数、同一组量,所以两者一致。
     * 旁路信号:不进任何效果的 key 或守卫、不改 focusRow / focusTarget,也不参与重定位。
     * 显式重定位(retarget:菜单动作、搬运每一步)先写 focusRow、位移先走,落焦时这里判出「不变」,不延迟。
     */
    var landedWithShift by remember { mutableStateOf(false) }
    fun shiftsOnLanding(landingRow: Int): Boolean =
        editFirstRow(landingRow, firstVisibleRow, rows.size, visibleWithHeader, visibleBelow) != firstRow

    /** 行交换时,两行各自的「当前格 / 目标格」跟着行走(不交换的话横向位移会套到别的行上)。 */
    fun swapRowState(a: Int, b: Int) {
        for (list in listOf(rowFocused, focusTarget)) {
            if (a in list.indices && b in list.indices) { val t = list[a]; list[a] = list[b]; list[b] = t }
        }
    }
    /** 删第 [ri] 行:焦点落上一行的「+」;删的是第 1 行则落新的第 1 行的「+」(M4b spec §0-4)。 */
    fun deleteRowAt(ri: Int) {
        val before = rows
        rows = deleteRow(rows, ri)
        if (rows === before) { toRowEnd(ri); return }   // 纯函数挡住了(只剩 1 行 / 越界):什么都没删
        persist()
        toRowEnd((ri - 1).coerceAtLeast(0))
    }

    // 返回键走 OnBackPressedDispatcher。用 onKeyEvent 有两个问题:预测式返回启用后
    // BACK 不再作为按键事件下发;而且焦点不在浮层里时(比如「没有可添加的应用了」——
    // 那个对话框里一个可聚焦节点都没有)按返回会**一步退出整个编辑界面**。
    androidx.activity.compose.BackHandler(enabled = !ghost) {
        // 取消 = 什么都没做,焦点必须留在原来那张卡上。这三条路原本根本没有安排重定位,
        // 于是 Compose 的默认恢复把焦点丢到第一行第一张。
        val a = acting; val p = picking
        // 行菜单 / 改名 / 图标 / 删行确认各自带 BackHandler(组合得更晚、先接管),这里是同一种兜底:
        // 万一没接住,只收掉那一层、焦点回该行的「+」,绝不一步退出整个编辑页。
        val r = rowMenu ?: renamingRow ?: iconRow ?: confirmDeleteRow
        // 搬运中:下面那个搬运专用的 BackHandler 组合得更晚、先接管;这里是同一种兜底,绝不一步退出编辑页
        if (carry != null) {
            cancelCarry()
        } else if (a != null || p != null) {
            val ri = a?.first ?: p ?: 0
            picking = null; acting = null
            retarget(ri, a?.second ?: (view().getOrNull(ri)?.apps?.size ?: 0))
        } else if (r != null) {
            rowMenu = null; renamingRow = null; iconRow = null; confirmDeleteRow = null
            toRowEnd(r)
        } else onExit()
    }
    // **搬运中的返回键 = 取消**(M4b spec §0-18)。只在搬运中存在,组合得比上面那个晚 → 先接管(OnBackPressedDispatcher
    // 先问最后加进来的)。返回键不在下面的按键截获里处理:没开预测式返回时它照常经 onKeyUp → onBackPressed 到这里,
    // 开了之后根本不作为按键事件下发——走 BackHandler 两种情况都接得住。
    if (carry != null && !ghost) androidx.activity.compose.BackHandler { cancelCarry() }

    // 搬运中那一下按压的 downTime 与其中按满长按的那一下(见 CarryPresses)。
    val presses = remember { CarryPresses() }
    val view = androidx.compose.ui.platform.LocalView.current
    /**
     * **搬运中的按键**(M4b spec §0-18,键位与首页移动态相同,见 MainActivity.onMoveKey / onMoveKeyUp),挂在本页根节点的
     * onPreviewKeyEvent 上:焦点在本页任何一格时,按键都先到这里、再到被聚焦的卡——方向键进不了 Compose 的焦点搜索,
     * 确定键落不到卡片的点击上。方向键按下(含按住的重复)= 搬一步;确定键**松开**才放下,按满 [LONG_PRESS_MS] 的
     * 那一下松开什么都不做(长按判据同首页:看重复事件,不看 UP 的时间戳——`input keyevent --longpress` 注入的 UP
     * 沿用 DOWN 的时间);返回交给上面的 BackHandler;音量照常(同首页 [MOVE_PASSTHROUGH_KEYS]);其余一律吞掉。
     * 确定键的按下 / 重复 / 松开,MainActivity 在搬运中原样交过来(见它的 editCarrying):平时它会先吞掉重复事件。
     * 放下那一声由这里出(首页移动态同样是松开放下时出一声);方向键的音照旧由 MainActivity 出。
     */
    fun onCarryKey(e: android.view.KeyEvent): Boolean {
        if (carry == null) {
            // 搬运刚结束(取消)时还按着的那一下:它的 UP 照样吞掉。放过去的话确定键的 UP 会落到卡片上——
            // tv-material 的卡在 UP 时直接触发 onClick(不看之前有没有收到过 DOWN),凭空弹出卡片菜单。
            return e.action == android.view.KeyEvent.ACTION_UP && e.downTime == presses.down
        }
        if (e.keyCode == android.view.KeyEvent.KEYCODE_BACK || e.keyCode in MOVE_PASSTHROUGH_KEYS) return false
        val ok = e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            e.keyCode == android.view.KeyEvent.KEYCODE_ENTER || e.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        when (e.action) {
            android.view.KeyEvent.ACTION_DOWN -> {
                presses.down = e.downTime
                val dir = when (e.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> MoveDir.LEFT
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> MoveDir.RIGHT
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> MoveDir.UP
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> MoveDir.DOWN
                    else -> null
                }
                if (dir != null) stepCarry(dir)
                else if (ok && e.repeatCount > 0 && e.eventTime - e.downTime >= LONG_PRESS_MS) presses.held = e.downTime
            }
            android.view.KeyEvent.ACTION_UP ->
                if (ok && e.downTime == presses.down && e.downTime != presses.held && !e.isCanceled) {
                    view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    dropCarry()
                }
        }
        return true
    }
    // 把「正在搬运」报给 MainActivity(它据此在搬运中不让 MENU 退出编辑页、把确定键原样交过来)。
    // 唯一的写入方:以它为 key,每次变化报一次;本页离开组合(HOME、换卡片图的选择器替换本页)时补报 false——不是闩(铁律 7)。
    val carrying = carry != null
    val reportCarrying by rememberUpdatedState(onCarryingChange)
    // R138:残影不报——它离开组合晚一个淡入时长,那时新的一页可能已经在搬运了,补报的 false 会把它盖掉。
    DisposableEffect(carrying, ghost) {
        if (!ghost) reportCarrying(carrying)
        onDispose { if (!ghost) reportCarrying(false) }
    }
    // **任何浮层要打开 = 搬运取消**(M4b spec §0-18,同首页 §0-9)。在这一处收口,不去每个浮层的入口各判一次。
    // 按键路径上开不出浮层(确定键在根上就被截走),卡片与「+」的点击在搬运中也一律不理(只可能来自指针 / 无障碍,
    // 而搬运里画面上的格子与出发时不是同一批,照点击处开菜单可能指到别的应用)——这里是结构上的兜底。
    // 守卫的两个量都是 key(铁律 6)。
    LaunchedEffect(overlayOpen, carrying) {
        if (overlayOpen && carrying) cancelCarry()
    }

    Box(
        Modifier
            .fillMaxSize()
            .pageBackdrop()   // R135:与所有整屏页同一个底(原来是 #0A0A0A);R142 起是氛围底
            // 搬运中的按键截获(见 onCarryKey)。不在搬运时它只吞「搬运里按下、结束后才松开」的那一下 UP,其余原样放行
            .onPreviewKeyEvent { onCarryKey(it.nativeKeyEvent) },
    ) {
        // 视窗:裁剪 + 整块自算位移。三行按首页的尺寸合计就高于屏幕,5 行 × 大档更高——
        // 当年正是因为「不滚的话 Column 会把最后一行压扁(实测 MUSIC 行的卡片被压成一条)」才上了 verticalScroll;
        // 现在用 wrapContentHeight(unbounded = true) 放开测量解决同一件事(铁律 1 的另一半)。
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { viewportPx = it.height },
        ) {
        Column(
            Modifier
                .fillMaxWidth()
                // **必须 unbounded**:不放开测量,超出视窗的行会被压扁 / 量成 0 高,offset 发生在测量之后救不回来
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .offset(y = yShift)
                .focusProperties { canFocus = !overlayOpen && !ghost }
                .padding(top = EditEdgePad, bottom = EditEdgePad)
        ) {
            // R135:页头与所有应用页同一个样子——31 sp 页名 + 14 sp 说明(原来是 20 sp 主题色页名 + 12 sp 说明)。
            // 说明限宽 640 dp,两句话折成两行,不再是贴着屏幕右缘的一长条。
            Column(Modifier.onSizeChanged { headerPx = it.height }) {
                BasicText(
                    text = stringResource(R.string.edit_title),
                    modifier = Modifier.padding(start = Theme.SidePadding, bottom = 6.dp),
                    style = Type.title,
                )
                BasicText(
                    text = stringResource(R.string.edit_hint),
                    modifier = Modifier.padding(start = Theme.SidePadding, bottom = 20.dp).widthIn(max = 640.dp),
                    style = Type.body,
                )
            }
            viewRows.forEachIndexed { ri, row ->
                val name = row.name
                val pkgs = row.apps
                Column(
                    Modifier
                        // 量在行距之前:量到的是「标题行 + 卡片行 + 行距」,正好是一个行距(pitch)
                        .onSizeChanged { rowHeights[ri] = it.height }
                        .padding(bottom = Theme.EditRowSpacing),
                    verticalArrangement = Arrangement.spacedBy(Theme.EditRowTitleGap),
                ) {
                    // 行名前画这一行的图标(M4b spec §2):`RowIcon` 固定尺寸的那个重载,方框 = 行盒高
                    // GtvLayout.ROW_TITLE_LINE(20dp),accent 色。**与首页不是同一套画法**:R48 起首页不画行名,
                    // 行图标走另一个重载(26dp、放在左边距、焦点行近白 / 其余灰)。行高固定、竖直居中,
                    // 中英文名字的行高差不会让各行高低不一。
                    Row(
                        // Ruling R18:行标题行高改读 GtvLayout,不再是 HomeLayout.ROW_TITLE_LINE(24dp,main 线
                        // titleMedium 的默认行高反推值)——编辑页用的是 gtv 三档卡片,行标题理应对齐同一条 gtv 几何。
                        // (R18 时首页 CategoryRow 也读这个值;R48 起首页没有行标题,这里是它仅剩的行盒读者。)
                        modifier = Modifier.padding(start = Theme.SidePadding).height(GtvLayout.ROW_TITLE_LINE.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Theme.EditRowIconGap),
                    ) {
                        RowIcon(name, row.icon, tint = LocalThemeColors.current.accent)
                        BasicText(text = name, maxLines = 1, style = Type.label.copy(color = Ink.Primary))
                    }
                    // 同首页:**不能用 LazyRow**,可滚动容器会挡住纵向焦点外出,
                    // 表现为「进编辑界面后按下键焦点就没了,之后按什么都没反应」。
                    // 横向位移自己算(把行尾的加号也算成一格)。
                    val fi = rowFocused.getOrElse(ri) { 0 }.coerceIn(0, pkgs.size)
                    // 整枝审查 B(2026-09-22):判据用**视觉**右缘——加上聚焦缩放 + 贴边描边的溢出
                    // (GtvLayout.appFocusOverflow,与首页 rowShiftX 的 Round 4 §5 同一规则),否则
                    // 「布局右缘刚好没超、缩放后的描边已经超」时不挪行,最右那格的描边被屏缘裁掉。
                    // AddCard / AppCard 两种格子都走 gtvAppFocusFrame,溢出量相同(R67 起没有「未安装」卡)。
                    val right = Theme.SidePadding + metrics.cardWidth * (fi + 1) + metrics.cardSpacing * fi +
                        GtvLayout.appFocusOverflow(metrics.cardWidth.value).dp
                    val over = right + Theme.SidePadding - LocalConfiguration.current.screenWidthDp.dp
                    val dx by animateDpAsState(
                        targetValue = if (over > 0.dp) -over else 0.dp,
                        // Ruling R29(承 R27 / 整枝审查 B):编辑页的横向位移与纵向位移、首页的 x/y 位移是
                        // 同一个 browse 手势,四处同读 Theme.browseShiftSpec(此前是 R27 的 tween;
                        // 再之前漏在默认 spring 上——默认 spring 的 stiffness 是 1500,不是现在这根)。
                        animationSpec = Theme.browseShiftSpec(),
                        label = "editRowX",
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(metrics.cardSpacing),
                        modifier = Modifier
                            // 同首页:父容器按屏幕宽给约束,Modifier.size 会被 constrain,
                            // 空间用完之后的条目被量成 0 宽且永远聚焦不到。
                            // 编辑界面更要命——第 7 个应用之后行尾的「＋」就是那个 0 宽条目,
                            // 它一消失,这一行 UI 内再也加不进任何应用。
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .offset(x = dx)
                            .padding(start = Theme.SidePadding, top = metrics.rowVerticalPad, bottom = metrics.rowVerticalPad),
                    ) {
                        pkgs.forEachIndexed { pi, pkg ->
                            val app = all?.get(pkg)
                            // 搬运中被搬的那张:highlight 色 2dp 描边,画在聚焦描边外侧、跟着缩放后边缘走
                            // (gtvAppFocusFrame 的 moving 分支,与首页移动态同一样式,聚焦与否都画)
                            val carried = carry?.pos?.let { it.row == ri && it.col == pi } == true
                            // requester 挂在**这一行当前聚焦的那一格**上,不是永远挂在第 0 格:
                            // 否则「往右移一位」之后焦点回到行首,把一张卡挪三位要重走三遍
                            // (当年卡片菜单里的「往右移」;M4b 补丁起搬运模式的每一步同样靠它把焦点送到被搬的卡上)。
                            // 目标越过末卡(= 指向行尾加号)时,requester 归加号,见下面 AddCard
                            val want = focusTarget.getOrElse(ri) { 0 }.coerceIn(0, pkgs.size)
                            val fm = if (pi == want) Modifier.focusRequester(rowFocus[ri]) else Modifier
                            // focusRow 也要跟着方向键走:看门狗与显式重定位都拿它当「回哪一行」,
                            // 只在菜单动作里写的话,在第三行按返回取消会跳回第一行、视窗也弹回顶部。
                            val mark = {
                                // 这个必须跟着真实焦点,算横向位移用
                                rowFocused[ri] = pi
                                // **行号也要冻结**:看门狗拿 focusRow 当「回哪一行」,
                                // 而它原本写在冻结判断之外 —— 从别的应用回来时 Compose 抢先把焦点
                                // 给了 (0,0),这一下就把行号改成 0,恢复于是回到 VIDEO 行第 1 张,
                                // 下一次确定打在别的应用上。铁律 5 说的「每个分量都要拆」,行号这一半漏了。
                                // **搬运中同样冻结**(M4b §0-18):目标归被搬的卡(carry.pos),只由 retarget() 写。
                                // 搬一步时被搬的卡若是源行最后一张,它的节点随之摘掉,系统当场把焦点给左上角那张卡——
                                // 那次上报若改了目标,看门狗与视窗都会跟着跑到第 1 行去。
                                if (!retargeting && carry == null) { focusRow = ri; focusTarget[ri] = pi }
                            }
                            val tell = { got: Boolean ->
                                if (got) landedWithShift = shiftsOnLanding(ri)   // ui-pending #10,放大等位移
                                report(ri, pi, got); if (got) mark()
                            }
                            if (app != null) {
                                AppCard(
                                    app = app,
                                    metrics = metrics,
                                    title = if (showTitles) (titles[pkg] ?: app.label) else null,
                                    fallbackColor = app.fallbackColor?.let { Color(it) },
                                    // 搬运中点击一律不理(确定键在根上就被截走,能到这里的只有指针 / 无障碍,见 overlayOpen 那个取消效果)
                                    onClick = { if (carry == null) acting = ri to pi },
                                    modifier = fm,
                                    onFocusChange = tell,
                                    isRowStart = pi == 0,
                                    // 编辑界面的行尾之后**还有一个加号**,不能在这里锁右键,
                                    // 否则同一行里永远到不了它。锁移到 AddCard 上。
                                    isRowEnd = false,
                                    isLastRow = ri == rows.lastIndex,
                                    isFirstRow = ri == 0,
                                    moving = carried,
                                    focusAfterShift = landedWithShift,
                                )
                            } else {
                                // 还没查过的包(进页数据没到、刚加进来的):中性占位。查过没查到的根本不在 viewRows 里
                                // (R67:已卸载的应用不占位、不画「未安装」)
                                PendingCard(pkg, metrics, fm, onFocusChange = tell,
                                    isRowStart = pi == 0, isLastRow = ri == rows.lastIndex,
                                    isFirstRow = ri == 0)
                            }
                        }
                        AddCard(
                            metrics = metrics,
                            // 该行被清空时,rowFocus 没有卡片可挂,焦点会一个都落不下——
                            // 而这时唯一能自救的控件正是加号,所以让它接住。
                            modifier = if (pkgs.isEmpty() ||
                                focusTarget.getOrElse(ri) { 0 } >= pkgs.size
                            ) Modifier.focusRequester(rowFocus[ri]) else Modifier,
                            focusAfterShift = landedWithShift,
                            onFocusChange = { got ->
                                if (got) landedWithShift = shiftsOnLanding(ri)   // ui-pending #10
                                report(ri, pkgs.size, got)
                                if (got) {
                                    rowFocused[ri] = pkgs.size
                                    // 搬运中冻结,同 mark()(第 1 行是空行时,系统兜底给的正是它的「+」)
                                    if (!retargeting && carry == null) { focusRow = ri; focusTarget[ri] = pkgs.size }
                                }
                            },
                            isRowStart = pkgs.isEmpty(),
                            isLastRow = ri == rows.lastIndex,
                            isFirstRow = ri == 0,
                        ) { if (carry == null) rowMenu = ri }   // M4b:行尾「+」= 行菜单(「添加应用」是其中第一项);搬运中不理,同卡片
                        }
                }
            }
        }
        }

        // 搬运中的底部提示(M4b spec §0-18:视觉只复用首页移动态那两样——被搬卡的 accent 描边与这一行)。
        // 字样、垫底与首页那条一致(HomeScreen 的 hintStyle 与移动态提示):onSurface α0.75 / 15sp,
        // 垫一层 surface α0.8 的胶囊底——下面一行的行标题可能正好露在屏幕底部。**位置不同**:编辑页仍距底
        // PILL_TOP;首页那条 2026-09-23 起挪到顶栏下方(GtvLayout.MOVE_HINT_TOP,R52 焦点线让焦点行卡底压到了
        // 原来的贴底位置),编辑页不走 R52 焦点线,这里没跟着挪。
        if (carry != null) {
            val scheme = androidx.tv.material3.MaterialTheme.colorScheme
            BasicText(
                text = stringResource(R.string.home_move_hint),
                style = Type.label.copy(color = scheme.onSurface.copy(alpha = 0.75f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = HomeLayout.PILL_TOP.dp)
                    .background(scheme.surface.copy(alpha = 0.8f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // acting 指向的卡片可能已经不在了(比如它所在的行被别处改短)。
        // **不在组合期写状态**:清空动作放进 LaunchedEffect,组合期只负责不渲染。
        val actingPkg = acting?.let { (ri, pi) -> viewRows.getOrNull(ri)?.apps?.getOrNull(pi) }
        LaunchedEffect(acting, actingPkg) { if (acting != null && actingPkg == null) acting = null }
        // 行浮层同理(M4b):指向的行不在了就收掉,组合期只负责不渲染——否则 overlayOpen 一直为真、
        // 看门狗永远让路,而屏幕上什么浮层都没有。收掉之后看门狗重启,把焦点接回 focusRow。
        val rowOverlayStale = listOfNotNull(rowMenu, renamingRow, iconRow, confirmDeleteRow).any { it !in rows.indices }
        LaunchedEffect(rowOverlayStale) {
            if (rowOverlayStale) { rowMenu = null; renamingRow = null; iconRow = null; confirmDeleteRow = null }
        }
        // **R136:编辑页的六种浮层是一摞,淡入淡出**([OverlayStack]):打开 / 关掉淡入淡出,换一层(行菜单 → 改名页 /
        // 图标页 / 确认页 / 添加应用)交叉淡化。残影画的是关掉前最后那一份——[EditOverlay] 把要显示的字(行名、卡片名、
        // 应用数)与 banner 都带上,不读此刻可能已经变了的 rows(删完一行之后同一个行号已经是另一行)。
        // 残影里的菜单项点不动、不可聚焦(各页面照 LocalPageGhost 让路),下面这些回调只有活着的那一层会调到。
        val editTitle = stringResource(R.string.edit_title)
        val overlay: EditOverlay? = run {
            val a = acting
            val rm = rowMenu; val rn = renamingRow; val ic = iconRow; val cd = confirmDeleteRow; val pk = picking
            when {
                a != null && actingPkg != null -> EditOverlay.Card(
                    a.first, a.second, actingPkg,
                    // 标题用这张卡的显示名(M4b-R13,终审 Important #2)——与首页长按卡片菜单同一套取法:
                    // 自定义标题优先,查不到就用应用名,再查不到用包名兜底。
                    title = titles[actingPkg] ?: all?.get(actingPkg)?.label ?: actingPkg,
                    // gtv 线 Task 8:左半 banner。all 就是这份数据本来的来源,按 pkg 查。
                    app = all?.get(actingPkg),
                )
                rm != null && rm in rows.indices -> EditOverlay.RowMenu(rm, rows[rm].name, rows.size)
                rn != null && rn in rows.indices -> EditOverlay.Rename(rn, rows[rn].name)
                ic != null && ic in rows.indices ->
                    EditOverlay.Icon(ic, rows[ic].name, effectiveRowIconId(rows[ic].name, rows[ic].icon))
                cd != null && cd in rows.indices ->
                    EditOverlay.Confirm(cd, rows[cd].name, viewRows.getOrNull(cd)?.apps?.size ?: 0)
                pk != null -> EditOverlay.Pick(pk, rows.getOrNull(pk)?.name, rows.flatMap { it.apps }.toSet())
                else -> null
            }
        }
        OverlayStack(state = overlay, layerKey = { it.layer }) { ov ->
            when (ov) {
                is EditOverlay.Card -> {
                    val ri = ov.row; val pi = ov.col; val pkg = ov.pkg
                    GearMenu(
                        items = buildList {
                            // 「移动位置」= 进入搬运(M4b spec §0-18,取代原来的「往左移 / 往右移」):先收菜单,再进搬运,
                            // 同一个回调里写完——overlayOpen 与 carry 在同一次重组里一关一开,「浮层开着就取消」的效果不会误触。
                            add(MenuItem(stringResource(R.string.card_menu_move), stringResource(R.string.edit_move_desc)) {
                                acting = null
                                startCarry(ri, pi)
                            })
                            add(MenuItem(stringResource(R.string.edit_change_image), stringResource(R.string.edit_change_image_desc)) {
                                // 打开了:本页随即被选择器替换(先以残影淡出,R138),回来时由调用方把 (ri, pkg) 当 initialTarget 喂回来;
                                // 菜单留着不收,残影里它原样画着,选择器在它上面淡入。
                                // 没打开(存储没就绪、已提示):本页留在原地,收菜单、焦点回这张卡。
                                if (!onPickIcon(ri, pkg)) { acting = null; retarget(ri, pi) }
                            })
                            add(MenuItem(stringResource(R.string.edit_remove), stringResource(R.string.edit_remove_desc)) {
                                // **按包名移出**(R67):pi 是看得见的列号,不是 layout.json 下标;包名在一行里唯一
                                rows = rows.mapIndexed { i, r -> if (i == ri) r.copy(apps = r.apps - pkg) else r }
                                // 移出之后那一格没了,焦点落到它原来位置的前一格(行空了就是加号)
                                persist(); acting = null; retarget(ri, (pi - 1).coerceAtLeast(0))
                            })
                        },
                        onDismiss = {
                            acting = null; retarget(ri, pi)
                        },
                        nonce = focusNonce,
                        title = ov.title,
                        app = ov.app,
                    )
                }

                // **行菜单**(M4b spec §0-2):行尾「+」打开。与卡片的 acting 菜单同一个浮层机制、同一套让路;
                // 焦点归 GearMenu 自己(初始循环 + 看门狗)。每个动作先收菜单再改数据,然后经 retarget 落焦点——
                // 改名 / 换图标 / 添加应用是「换一层浮层」,焦点交给下一层,那一层关掉时再落回本行的「+」。
                is EditOverlay.RowMenu -> {
                    val ri = ov.row
                    val newRowName = stringResource(R.string.edit_new_row_name)
                    GearMenu(
                        items = buildList {
                            add(MenuItem(stringResource(R.string.edit_row_add_app), stringResource(R.string.edit_row_add_app_desc)) {
                                rowMenu = null; picking = ri
                            })
                            add(MenuItem(stringResource(R.string.edit_row_rename), stringResource(R.string.edit_row_rename_desc)) {
                                rowMenu = null; renamingRow = ri
                            })
                            add(MenuItem(stringResource(R.string.edit_row_icon), stringResource(R.string.edit_row_icon_desc)) {
                                rowMenu = null; iconRow = ri
                            })
                            // 焦点跟着这一行走,落在它的「+」(M4b spec §0-8)
                            if (ri > 0) add(MenuItem(stringResource(R.string.edit_row_up), stringResource(R.string.edit_row_up_desc)) {
                                rowMenu = null
                                rows = swapRows(rows, ri, ri - 1); swapRowState(ri, ri - 1); persist()
                                toRowEnd(ri - 1)
                            })
                            if (ri < ov.rowCount - 1) add(MenuItem(stringResource(R.string.edit_row_down), stringResource(R.string.edit_row_down_desc)) {
                                rowMenu = null
                                rows = swapRows(rows, ri, ri + 1); swapRowState(ri, ri + 1); persist()
                                toRowEnd(ri + 1)
                            })
                            // 新行是空行,(ri + 1, 0) 就是它的「+」(M4b spec §0-3)
                            if (ov.rowCount < MAX_ROWS) add(MenuItem(stringResource(R.string.edit_row_new), stringResource(R.string.edit_row_new_desc)) {
                                rowMenu = null
                                rows = addRowBelow(rows, ri, newRowName); persist()
                                retarget(ri + 1, 0)
                            })
                            // 空行直接删;非空行先确认(M4b spec §0-4)
                            if (ov.rowCount > MIN_ROWS) add(MenuItem(stringResource(R.string.edit_row_delete), stringResource(R.string.edit_row_delete_desc)) {
                                rowMenu = null
                                // 按看得见的算(R67):只剩看不见的包(被停用的)的行,在用户眼里就是空行
                                if (view().getOrNull(ri)?.apps.isNullOrEmpty()) deleteRowAt(ri) else confirmDeleteRow = ri
                            })
                        },
                        onDismiss = { rowMenu = null; toRowEnd(ri) },
                        nonce = focusNonce,
                        title = ov.name,
                        eyebrow = editTitle,
                    )
                }

                // **改行名**(M4b spec §0-7):通用化后的 TitleDialog;清空 = 不改(renameRow 挡住空名)。
                // 以「这一层还开着、而且是这一行」当守卫:IME 的 Done 与确定键在极端时序下可能各触发一次,第二次直接忽略。
                is EditOverlay.Rename -> {
                    val ri = ov.row
                    TitleDialog(
                        key = "row-$ri",
                        current = ov.name,
                        heading = stringResource(R.string.edit_row_rename_heading),
                        hint = stringResource(R.string.edit_row_rename_hint),
                        onSave = { text ->
                            if (renamingRow == ri) {
                                renamingRow = null
                                val renamed = renameRow(rows, ri, text)
                                if (renamed !== rows) { rows = renamed; persist() }
                                toRowEnd(ri)
                            }
                        },
                        onCancel = { renamingRow = null; toRowEnd(ri) },
                        nonce = focusNonce,
                        subtitle = ov.name,
                        // 清空 = 不改名:空着时把现在的行名淡淡地垫在输入框里
                        placeholder = ov.name,
                    )
                }

                // **行图标选择器**(M4b spec §0-6):当前图标(没存 id 的旧行按名字回落)预先聚焦,焦点归它自己。
                is EditOverlay.Icon -> {
                    val ri = ov.row
                    RowIconPicker(
                        current = ov.current,
                        nonce = focusNonce,
                        rowName = ov.name,
                        onPick = { id ->
                            if (iconRow == ri) {
                                iconRow = null
                                rows = setRowIcon(rows, ri, id); persist()
                                toRowEnd(ri)
                            }
                        },
                        onDismiss = { iconRow = null; toRowEnd(ri) },
                    )
                }

                // **删非空行的确认页**(M4b spec §0-4):焦点默认在「取消」。
                is EditOverlay.Confirm -> {
                    val ri = ov.row
                    ConfirmDialog(
                        title = stringResource(R.string.edit_row_delete_confirm_title, ov.name),
                        body = androidx.compose.ui.res.pluralStringResource(R.plurals.edit_row_delete_confirm_body, ov.apps, ov.apps),
                        okLabel = stringResource(R.string.edit_row_delete_ok),
                        cancelLabel = stringResource(R.string.dialog_cancel),
                        nonce = focusNonce,
                        eyebrow = editTitle,
                        onOk = { if (confirmDeleteRow == ri) { confirmDeleteRow = null; deleteRowAt(ri) } },
                        onCancel = { confirmDeleteRow = null; toRowEnd(ri) },
                    )
                }

                is EditOverlay.Pick -> {
                    val ri = ov.row
                    AppPicker(
                        nonce = focusNonce,
                        ctx = ctx,
                        rowName = ov.name,
                        exclude = ov.exclude,
                        onPick = { pkg ->
                            rows = rows.mapIndexed { i, r ->
                                if (i == ri) r.copy(apps = r.apps.toMutableList().also { it.add(pkg) }) else r
                            }
                            // 刚加进来的那张卡就是新的行尾(看得见的那份里也是:它还没查过,画成占位),焦点落到它身上
                            persist(); picking = null
                            retarget(ri, (view().getOrNull(ri)?.apps?.lastIndex ?: 0).coerceAtLeast(0))
                        },
                        // 注:AppPicker 自己没有 BackHandler,取消走的是本文件上方那个 —— 目标也在那里设。
                    )
                }
            }
        }
    }
}

/**
 * 编辑页上叠着的那一层(R136,给 [OverlayStack] 当状态)。每一种都带上自己要显示的东西:关掉之后残影还要画一个淡出时长,
 * 那时 `rows` 可能已经变了(删完一行,同一个行号指到了下一行)。[layer] 区分是哪一层(换层 = 交叉淡化)。
 */
private sealed interface EditOverlay {
    val layer: String
    class Card(val row: Int, val col: Int, val pkg: String, val title: String, val app: AppEntry?) : EditOverlay {
        override val layer get() = "card:$row:$pkg"
    }
    class RowMenu(val row: Int, val name: String, val rowCount: Int) : EditOverlay {
        override val layer get() = "rowMenu:$row"
    }
    class Rename(val row: Int, val name: String) : EditOverlay {
        override val layer get() = "rename:$row"
    }
    class Icon(val row: Int, val name: String, val current: String) : EditOverlay {
        override val layer get() = "icon:$row"
    }
    class Confirm(val row: Int, val name: String, val apps: Int) : EditOverlay {
        override val layer get() = "confirm:$row"
    }
    class Pick(val row: Int, val name: String?, val exclude: Set<String>) : EditOverlay {
        override val layer get() = "pick:$row"
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AddCard(
    metrics: CardMetrics,
    modifier: Modifier = Modifier,
    /** ui-pending #10:这次落焦带着纵向位移 → 放大等 [GtvLayout.FOCUS_AFTER_SHIFT_DELAY_MS](同 AppCard)。 */
    focusAfterShift: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    isRowStart: Boolean = false,
    isLastRow: Boolean = false,
    isFirstRow: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val highlight = LocalThemeColors.current.highlight
    val accent = LocalThemeColors.current.accent
    // Ruling R18(终审 2026-09-20):Decision B1 把「聚焦放大 1.1 倍」的画法整体作废,不只管首页——
    // 这里原来用 1.12 倍缩放 + 换底色补偿(这条注释本身就是当年为什么加缩放的解释:「加号原来只
    // 换个底色,暗背景下看不出『我选中的是它』」),后来跟着 AppCard 改成过外扩描边、不缩放。
    // **owner 反馈 Round 4 起又跟 AppCard 一起改回缩放**——这次不是走当年 R18 作废的那条路
    // (库默认 1.1x + 换底色),是 Google app tile 的真实处理(gtvAppFocusFrame:缩放
    // GtvLayout.APP_FOCUS_SCALE 倍 + 描边贴缩放后边缘),底色仍然不随聚焦变化。
    Box(
        modifier = modifier
            .gtvAppFocusFrame(focused, accent, metrics.cardCorner, afterShift = focusAfterShift)
            .size(metrics.cardWidth, metrics.cardHeight)
            .clip(RoundedCornerShape(metrics.cardCorner))
            .background(GtvTokens.SurfacePlaceholder)   // R144:半透明,透出氛围底(原 Theme.AddCardBackground)
            .focusProperties {
                right = FocusRequester.Cancel          // 行尾锁在这里,别跳到下一行
                if (isRowStart) left = FocusRequester.Cancel   // 空行时它就是行首
                if (isLastRow) down = FocusRequester.Cancel
                if (isFirstRow) up = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PlusGlyph(color = highlight)
    }
}

/** 数据还在加载时的中性占位 —— 与「未安装」的红底卡区分开。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PendingCard(
    pkg: String,
    metrics: CardMetrics,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    isRowStart: Boolean = false,
    isLastRow: Boolean = false,
    isFirstRow: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(metrics.cardWidth, metrics.cardHeight)
            .clip(RoundedCornerShape(metrics.cardCorner))
            .background(if (focused) Theme.PendingCardFocusedBackground else Theme.PendingCardBackground)
            .focusProperties {
                if (isRowStart) left = FocusRequester.Cancel
                if (isLastRow) down = FocusRequester.Cancel
                if (isFirstRow) up = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .focusable(interactionSource = remember { MutableInteractionSource() }),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = pkg.substringAfterLast('.'),
            style = Type.micro.copy(color = Ink.Secondary, textAlign = TextAlign.Center),
            modifier = Modifier.padding(6.dp),
        )
    }
}

/**
 * 选一个应用加进某行(R83 起:一列小应用卡片 + 名字)。列出机器上的应用,已在桌面上的不再重复列出;
 * 应用在上,电视设置与厂商系统工具在底部「系统工具」分组,只有裸 MAIN 的系统组件不列(分组规则见 PickerGroups.kt)。
 */
@Composable
private fun AppPicker(
    nonce: Int,
    ctx: Context,
    exclude: Set<String>,
    onPick: (String) -> Unit,
    /** 加到哪一行(页名上方的小字);null = 不画。 */
    rowName: String? = null,
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

    // R135 换皮:与设置各层同一个版式——整屏 MenuBg,左边页名(上方一行小字写加到哪一行,下方写「正在读取 / 没有可添加的」),
    // 右边一列应用。此前是屏幕中间 460 dp 的小面板、标题 16 sp。列表本身(LazyColumn + 逐项 requester)未动。
    Box(
        Modifier
            .fillMaxSize()
            .focusGroup()   // 同 GearMenu:不圈起来焦点会跑到底下那一层
            .background(GtvTokens.ScrimOverlay)
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
                    path = rowName,
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
