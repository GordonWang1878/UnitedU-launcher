package com.uniteduone.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首页:壁纸层 + 三行卡片 + 右上角时钟。
 * 待机由 [MainActivity] 通过 [idle] 传进来,内容由 [idleContent] 定(Task 3):
 * [IdleContent.CLOCK_ONLY](默认)卡片/行标题淡出、时钟留着;[IdleContent.BLACK] 同上但
 * 时钟也淡出(配合 MainActivity 叠加的黑屏,整屏全黑);[IdleContent.NO_FADE] 这里的
 * `contentAlpha` 恒为 1、什么都不淡出。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    idle: Boolean,
    /** 待机时屏幕上显示什么(design 待机 §,Task 3);默认与老行为一致。 */
    idleContent: IdleContent = IdleContent.CLOCK_ONLY,
    menuItems: List<MenuItem>,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    focusNonce: Int,
    revision: Int = 0,
    menuFromGear: Boolean = true,
    showDate: Boolean = true,
    cardsPerRow: Int = 6,
    /** 卡片标题全局开关(design §2)。开着时卡片下方多一行标题,行高随之增加
     *  (见 Theme.cardMetrics 的 titleHeight),纵向位移沿用同一套自算逻辑。 */
    showTitles: Boolean = false,
    /** 输入源行开关(design §2,默认关)。开着且真机枚举到硬件输入时,在应用行**上方**
     *  多渲染一行输入源;它以普通行的身份加进纵向焦点账本,种类差异只影响点击行为与行图标。 */
    showInputRow: Boolean = false,
    /** 主题化卡片开关(design 2026-09-16):开着时所有卡片去色→染主题 accent。 */
    themedCards: Boolean = false,
    /** 上次打开「添加应用」列表的时刻(design §4);默认「什么都不算新」,未接线的调用点零回归。 */
    newAppsSeenAt: Long = Long.MAX_VALUE,
    /** 当前聚焦的卡(得到时上报,失去时报 null)——MainActivity 长按时据此弹菜单。 */
    onFocusedCard: (CardRef?) -> Unit = {},
    /** 长按菜单:非空时在首页内嵌一层 GearMenu(不替换首页,焦点记忆不丢)。 */
    cardMenu: CardRef? = null,
    cardMenuItems: List<MenuItem> = emptyList(),
    onCardMenuDismiss: () -> Unit = {},
    /** 「修改标题」对话框(Task 5):非空时在首页内嵌一层 TitleDialog(不替换首页,焦点记忆不丢)。 */
    renameTarget: CardRef? = null,
    onRenameSave: (CardRef, String) -> Unit = { _, _ -> },
    onRenameCancel: () -> Unit = {},
    /**
     * 首次组合时把焦点记忆**种**在这张卡上 (渲染行, 列);null = 照旧从 (0,0) 起。
     *
     * 为什么需要:图片选择器这类浮层住在 MainActivity 的 if/else 链上,开着时首页整棵树
     * 被移除,`tgtRow`/`tgtIdx` 跟着 `remember` 一起没了 —— 换完图回来焦点落回第一张卡,
     * 而用户明明是站在第三行操作的。种子只影响**初值**,之后照旧由导航更新,
     * 不是闩:同一个值种一次,换了新值下一次组合自然按新值种(铁律 7)。
     */
    initialTarget: Pair<Int, Int>? = null,
    /** initialTarget 落地后的回调(T4 review item A):种子只该生效一次,消费完立刻告诉
     *  MainActivity 清掉,否则下一次从别的浮层(换壁纸、屏保、设置、导入)回来会误种这颗旧值——
     *  那几条路焦点原本在齿轮上,不该被 CHANGE_ICON 留下的坐标带偏。 */
    onInitialTargetConsumed: () -> Unit = {},
) {
    val ctx = LocalContext.current
    // 卡片档位尺寸:6=当前标定常量原样(零回归),5/8 按跨度守恒推导。见 Theme.cardMetrics。
    val metrics = Theme.cardMetrics(cardsPerRow, showTitles)
    // **「有没有浮层」只此一个判据。**首页上能盖住卡片的现在有三层(齿轮菜单、长按卡片菜单、
    // 修改标题对话框),它们对下面四处的要求完全相同:卡片与齿轮不可聚焦、还原与看门狗让路。
    // 分开写四遍 `menuOpen ||` 迟早漏掉一处,而漏掉的那一处就是「菜单开着时看门狗每帧抢焦点,
    // 菜单里一项都不高亮」(铁律 4 的推论)。合成一个量之后,它同时是那两个效果的 key 与守卫(铁律 6)。
    // **例外:`gearNonce` 那个 LaunchedEffect 仍然只看 menuOpen** —— 它专管「齿轮菜单关了回齿轮」,
    // 长按菜单关掉后焦点应该回到那张卡,不是齿轮。
    val anyOverlay = menuOpen || cardMenu != null || renameTarget != null
    // 枚举应用 + 解码全部横幅是重活,放到 IO 线程,别拖慢首帧
    // (冷启动实测 2.0–2.3s,Projectivy 是 1.45s)。
    // 用 null 区分「还在加载」和「真的空」,否则每次冷启动和每次退出编辑都会闪一句求救文案
    // revision 变化(换了卡片图、装/卸了应用)时重跑,但 produceState 的 remember 不带 key,
    // 新数据到达前**旧画面原样留着**——不会像 key(revision) 那样先黑一下再重建。
    // showInputRow 也作 key:设置页改了这个开关后 leaveSettings() 会 revision++,
    // 这里本就会重跑;带上它是白纸黑字,不依赖「revision 一定跟着变」这条间接约束。
    // titles.json 与 rows 同一趟 IO 读出,配成一对:标题开关关着时 titles 仍会被读到但不渲染
    // (显示与否只由 showTitles 决定,不进 key——开关切换不必重读数据,只是换一种渲不渲染)。
    // 「手上这份数据是为哪个 revision 算的」。**移除 / 卸载后焦点能不能留在同一行,全靠它**:
    // revision++ 之后新的行数据要过几百毫秒才到,数据落地的那一帧焦点卡的节点被销毁,
    // Compose 会立刻把焦点塞给整棵树第一个可聚焦节点 (0,0) —— 那次上报若不冻结就会把
    // tgtRow/tgtIdx 改写成 0,记忆在被用到之前就没了(铁律 5),焦点静默跳到第一行。
    // 与 EditScreen 的 allFresh 同构:数据不新鲜时冻结目标,新鲜之后再由还原效果送回去。
    var loadedRevision by remember { mutableStateOf(-1) }
    val loaded by produceState<Triple<List<Row>, Map<String, String>, Int>?>(
        initialValue = null, ctx, revision, showInputRow, newAppsSeenAt,
    ) {
        value = withContext(Dispatchers.IO) {
            val appRows = runCatching { buildRows(ctx) }.getOrDefault(emptyList())
            // 输入源行放**最上面**:design §2 把「输入源」当独立顶层类目,置顶与之相符;
            // 且置顶后应用行的相对次序、以及「开机焦点落在最上一行」的直觉都不变。
            // 枚举为空(非电视 / 没有硬件输入)时返回 null,这一行干脆不存在 —— 焦点账本
            // 只认非空行,不会挂空 requester(见 buildRows 结尾那条不变量)。
            val inputRow = if (showInputRow) runCatching { buildInputRow(ctx) }.getOrNull() else null
            val rows = if (inputRow != null) listOf(inputRow) + appRows else appRows
            val titles = runCatching { Titles.read(ctx) }.getOrDefault(emptyMap())
            // 「新应用」计数:与首页同一趟 IO 算(应用已经枚举过一次),onLayout 只看应用行
            // ——输入源行的 packageName 存的是输入 id,不是真的包名。
            // 基线还没建立(newAppsSeenAt == 0:onCreate 那次基线写盘失败,比如外置存储开机时还没挂上)
            // 就什么都不算新——否则 countNew(ctx, 0, …) 会把整机几十个应用全算成「新」,整个会话都挂着计数
            // (终审 Minor #5)。isNewApp 的纯语义不动(仍是「装机时间 > seenAt 且不在桌面上」),只是不喂 0 进去。
            val newCount = if (newAppsSeenAt == 0L) 0 else runCatching {
                Apps.countNew(ctx, newAppsSeenAt, appRows.flatMap { r -> r.apps.map { it.packageName } }.toSet())
            }.getOrDefault(0)
            Triple(rows, titles, newCount)
        }
        // **紧跟在 value 之后、同一次恢复里写**:中间没有挂起点,两次快照写入会被同一帧的
        // 重组一起看到,不会出现「新数据已到但还标着不新鲜」的中间态。
        loadedRevision = revision
    }
    val rows = loaded?.first.orEmpty()
    val titles = loaded?.second.orEmpty()
    /** 数据还没跟上当前 revision(重读在途)。冻结目标用,见 loadedRevision 的注释。 */
    val stale = loadedRevision != revision
    // 开机后焦点要自己落到第一张卡片上,否则方向键第一下没有反应。
    val firstCard = remember { FocusRequester() }
    // 每行一个 requester,挂在「这一行的目标格」上 —— 用来把焦点**还原到离开前那张卡**。
    val rowFocus = remember(rows.size) { List(rows.size.coerceAtLeast(1)) { FocusRequester() } }
    // 「目标格」只由用户的主动导航更新,还原过程中不更新 ——
    // 否则 Compose 抢先把焦点给了第一张卡,目标就被改写成 0 了。
    // (横向位移由 CategoryRow 自己的 focusedIndex 算,不在这里。)
    // initialTarget 只作**初值**(见它的 KDoc):首帧的初始焦点请求打的就是
    // rowFocus[tgtRow] @ tgtIdx[tgtRow],种在这里等于「第一次落点就是那张卡」。
    // 越界不必在这里挡:取用处(还原效果、看门狗、requester 挂点)全都 coerceIn 过。
    // 种子只在这个组合实例创建时读一次,此后不再跟随活参数变化(T5 review Critical)。
    // 不冻的话会踩一次时序竞争:首次合成时 rows 还是空的(loaded 没落地),`loaded != null`
    // 前 `rows.size == 0`,tgtIdx 的 remember(rows.size) 第一次落在 key=0;紧接着下面的
    // LaunchedEffect(Unit) 立刻把 homeInitialTarget 消费成 null(那是活参数,不是这里),
    // 等 loaded 真正到达、rows.size 从 0 变成 N,tgtIdx 的初始化器随 key 变化重跑——
    // 这次重跑读到的 initialTarget 早已是 null,列号被种成 0,种子形同虚设。
    // tgtRow 用的是不带 key 的 remember,天然躲过了这个坑(下面这行只是把它也接到 seedTarget
    // 上,两处必须读同一份冻结值);tgtIdx 必须显式冻一份才能对齐。
    val seedTarget = remember { initialTarget }
    val tgtIdx = remember(rows.size) {
        mutableStateListOf(*Array(rows.size.coerceAtLeast(1)) { i ->
            if (seedTarget != null && i == seedTarget.first) seedTarget.second else 0
        })
    }
    var tgtRow by remember { mutableStateOf(seedTarget?.first ?: 0) }
    // 种子只消费一次(T4 review item A):落地当帧就告诉 MainActivity 清掉 homeInitialTarget,
    // 不然下一次从「换壁纸/屏保/设置/导入」这类焦点原本在齿轮上的浮层回来,会被这颗旧坐标误种。
    // Unit key = 只在这个组合实例首次进场时跑一次,和 tgtRow/tgtIdx 的初值是同一次落地。
    // 守卫读 seedTarget(冻结值)而不是 initialTarget(活参数):这一帧之后活参数就可能已经
    // 被消费成 null,守卫要反映「这个实例到底种没种」,不是参数此刻的值。
    LaunchedEffect(Unit) {
        if (seedTarget != null) onInitialTargetConsumed()
    }
    var restoring by remember { mutableStateOf(false) }
    // 关菜单后焦点该还给齿轮。**用 nonce 比对而不是布尔闩**:布尔闩只有「看门狗跑完整个循环」
    // 这一条窄路能清掉,任何一次早退(菜单又开了、restoring 被 ON_PAUSE 置位、
    // 或 Compose 自己把焦点还给了第一张卡)都会把它留在 true —— 一旦闩住,
    // 「从应用返回还原到离开前那张卡」就永久失效,此后任何一次丢焦点都被送到齿轮。
    // nonce 比对天然自愈:来了新的 focusNonce,比对自然不成立。
    // 而且守卫读的量(focusNonce)本身就是 key,满足铁律 6。
    var gearNonce by remember { mutableStateOf(-1) }
    // **谁持有焦点,只信控件自己的上报。**根节点的 onFocusChanged 在「退到后台再回来」
    // 这条路上不会重发,`hasFocus` 会停在过期的 true —— 实测日志说有焦点,截图里
    // 卡片却没有放大也没有光晕(上边缘 777→812、光晕峰值 142→66)。
    // (-1, -1) 表示焦点在齿轮上。
    var focusedCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // **从 onPause 就开始冻结目标**,而不是等还原效果开始时才冻。实测:从别的应用回来时
    // Compose 会抢在还原效果之前把焦点给第一张卡,那次焦点事件会把目标改写成 (0,0),
    // 等还原效果跑起来时它要还原的已经是「第一张卡」了 —— 记忆在被用到之前就没了。
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) restoring = true
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    /**
     * 「现在站在哪张卡上」**只派生、不缓存**(终审 Important #1)。曾经在焦点事件时缓存一份 CardRef、
     * 只在下一次焦点事件才重报,有两条路会让它过期:
     * (i) **重载而没有焦点事件**——CategoryRow 按位置组合卡片(没有 key()),移除第一行第一张后
     *     节点 (0,0) 原地换成了原来的 (0,1),焦点没动、没有事件,缓存里仍是被移除的那张:长按弹出的是
     *     「幽灵」的菜单(打开会启动它、卸载会卸它、移动位置 indexOf(pkg) = -1)。后台 PACKAGE_REMOVED
     *     让焦点卡左边任一张消失,同一形态。
     * (ii) **卡→齿轮且回调顺序是「新先旧后」**——齿轮 got 把 focusedCell 写成 (-1,-1)(不上报),
     *     随后卡片的 lost 看到 focusedCell != null 就不清,齿轮上长按弹出上一张卡的菜单。
     * 派生之后两条路都自愈:上报值永远等于 cardAt(focusedCell) 对**当前** rows 的求值;
     * 数据重载由下面那个 LaunchedEffect(loaded, focusedCell) 再算一次(rows 变 → key 变,没有闩)。
     * layoutRow **直接取 Row 自己带的那个**(buildRows 在 filter 之前按 layout.json 定的),
     * 不由渲染下标推导 —— 装不到的包会让某一行消失,推导出来的行号就会偏移,
     * 「移除」会删到别人那一行(见 Row.layoutRow 的 KDoc)。
     */
    fun cardAt(cell: Pair<Int, Int>?): CardRef? {
        val (row, idx) = cell ?: return null
        if (row < 0) return null                       // (-1,-1) = 齿轮:它不是卡,长按不该出菜单
        val r = rows.getOrNull(row) ?: return null
        val app = r.apps.getOrNull(idx) ?: return null
        return CardRef(row, idx, r.layoutRow, r.kind, app.packageName, app.label)
    }
    fun report(row: Int, idx: Int, got: Boolean) {
        // 齿轮真的拿到焦点 = 这次「关菜单回齿轮」的意图已经兑现,比对立刻作废。
        // 不作废的话它会一直成立到下一次 nonce 递增,**窗口里每一次丢焦点都被送到齿轮**
        // (比如后台某个应用自动更新让某行短一格、焦点所在节点被销毁),
        // 人正站在第三行却突然瞬移到右上角。
        if (got && row == -1) gearNonce = -1
        // focusedCell 自己的得失顺序保护留着:只有「本格仍是持有者」才作废。导航时若两张卡的
        // 得失顺序颠倒(新卡先报 got、旧卡后报 lost),旧卡那次 lost 不会把新卡抹掉。
        if (got) focusedCell = row to idx
        else if (focusedCell == row to idx) focusedCell = null
        // 上报**无条件**按 focusedCell 派生,不再看这次事件是谁:齿轮拿到焦点 → 派生为 null,
        // 卡片拿到 → 派生为那张卡;颠倒顺序下旧卡的 lost 派生出来的仍是新卡。
        onFocusedCard(cardAt(focusedCell))
    }
    // 数据重载(移除、卸载、后台 PACKAGE_*)之后节点原地换卡、没有任何焦点事件——这里按新 rows 再派生一次。
    // 两个 key 都只是被读的量,没有守卫,不存在铁律 6 那种「守卫不在 key 里」的洞;
    // 也没有闩(铁律 7):每次 rows 或 focusedCell 变化都是一次全新求值。
    LaunchedEffect(loaded, focusedCell) { onFocusedCard(cardAt(focusedCell)) }
    // 配置里的包一个都装不到时,卡片一张都没有,焦点无处可落;而这时唯一能自救的
    // 控件正是齿轮。不能指望框架的隐式 focus-enter——这份代码在别处恰恰拒绝依赖它。
    val gearFocus = remember { FocusRequester() }
    // 哪一行是「当前行」——决定其它行压暗;跟着焦点走。
    var activeRow by remember { mutableStateOf(0) }

    // 垂直位置自己算,不用 verticalScroll:实测系统的 bringIntoView 会在**水平**移动焦点时
    // 也带动垂直滚动(按一次右键整体上移 158px),把 v4 的顶部留白吃掉。
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    // 同理:整行被 filter 摘掉后 activeRow 会越界,内容会整块多上移一个 RowPitch
    val activeRowSafe = activeRow.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    // 标题开着时卡片下面还挂一行字(titleHeight),焦点行的「底」要连这行字一起算,
    // 否则标题开关打开时,焦点落在最后一行会让标题的放大后半截探出屏幕底边(见 M4 Task 2 复审)。
    val overflow = metrics.firstCardTop + metrics.rowPitch * activeRowSafe +
        metrics.cardHeight + metrics.titleHeight + Theme.BottomKeepout - screenH
    val shift by animateDpAsState(
        targetValue = if (overflow > 0.dp) -overflow else 0.dp,
        label = "rowShift",
    )
    // **焦点看门狗。**判据取自真机日志:根节点的 onFocusChanged 里
    //   hasFocus=true && !isFocused  → 某个子节点持有焦点(正常)
    //   hasFocus=true &&  isFocused  → 焦点停在根上,即**没有任何卡片持有**(要补)
    //   hasFocus=false               → 整棵树都没有焦点(要补)
    // 这比原来「在菜单关闭/退出编辑/onResume 这几个时刻盲目补请求」可靠得多:
    // 卡片节点被销毁(某个应用后台更新触发 PACKAGE_* → 那一行短一格)时焦点也会没,
    // 而那一刻不在任何一张「猜得到的时刻」清单里,遥控器就此全死、按 HOME 也回不来。
    // 菜单可以从齿轮或遥控器三条杠键打开,关掉后焦点应该回到打开前的位置:
    // 齿轮打开 → 回齿轮;三条杠打开(焦点在卡片上)→ 回那张卡。
    var menuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) {
        if (!menuOpen && menuWasOpen && menuFromGear) gearNonce = focusNonce
        menuWasOpen = menuOpen
    }
    LaunchedEffect(focusNonce, rows.size, rows.isEmpty(), anyOverlay, stale) {
        // 除「浮层开着」外的每条分支都要把 restoring 放掉,否则用户自己的导航从此更新不了目标。
        // **浮层开着时反过来要把它按住**(`restoring = anyOverlay` 而不是恒 false):
        // 浮层关掉的那一帧,canFocus 从 false 回到 true,Compose 的默认恢复会抢在本效果重启之前
        // 把焦点给整棵树第一个可聚焦节点 = (0,0);那次上报此时看到 restoring 还是 false,
        // 于是把 tgtRow/tgtIdx 改写成 (0,0) —— **记忆在被用到之前就没了**(铁律 5),
        // 本效果随后读到的目标已经是第一张卡,循环一次都不跑,焦点静默留在 (0,0)。
        // 2026-09-16 实测:长按菜单与三条杠键打开的齿轮菜单都复现,而「从别的应用回来」这条路
        // 不复现 —— 差别正是后者在 ON_PAUSE 就冻结了。冻结点必须早于那次默认恢复,
        // 而浮层**打开**时冻结留有整整一个浮层的时间,足够早。
        // 写成派生于 anyOverlay 而不是一次性布尔闩(铁律 7):浮层一关它自然放开,没有要清的闩;
        // 而守卫读的 anyOverlay 本身就是 key(铁律 6)。
        // **`stale` 同理,而且它是「移除 / 卸载后还站在同一行」的关键**:那两个动作先关菜单
        // (nonce++)、再 revision++,新的行数据要几百毫秒才到。此刻若放开冻结,数据落地那一帧
        // 焦点卡的节点被销毁、Compose 把焦点塞给 (0,0),那次上报就把目标改写成第一行第一张,
        // 随后的还原只会把焦点送回那里。冻到数据新鲜为止,还原效果再按夹过的列号把焦点送到
        // 同行邻卡(design §1 的「行变短时索引夹取」)。
        if (focusNonce == 0 || rows.isEmpty() || anyOverlay || stale || focusNonce == gearNonce) {
            restoring = anyOverlay || stale; return@LaunchedEffect
        }
        val r = tgtRow.coerceIn(0, rowFocus.lastIndex)
        restoring = true
        var frames = 0
        // 退出条件必须**同时**满足「树里真的有焦点」和「落在目标格上」:
        // 只看「当前格 == 目标格」的话,丢焦点时没人把「当前」作废,条件一开始就成立、
        // 循环一次都不跑;只看「有没有焦点」的话,Compose 抢先给了第一张卡就会提前退出。
        // 判据必须与 requester 的挂点用**同一个夹过的坐标**:挂点夹过、判据没夹的话,
        // 行变短后「目标 = 第 5 格」而焦点只可能落到第 4 格,条件恒不成立,
        // 循环会跑满 60 帧、期间每帧把焦点拽回同一格,用户按的方向键当场被撤销。
        val cap = rows.getOrNull(r)?.apps?.lastIndex?.coerceAtLeast(0) ?: 0
        val want = r to (tgtIdx.getOrNull(r) ?: 0).coerceIn(0, cap)
        while (frames < 60 && focusedCell != want) {
            withFrameNanos { }
            runCatching { rowFocus[r].requestFocus() }
            frames++
        }
        restoring = false
    }
    LaunchedEffect(rows.isEmpty(), loaded != null, focusNonce, focusedCell, anyOverlay, restoring, gearNonce) {
        // **任何浮层开着时让路。**focusedCell 只记录卡片与齿轮,不认识菜单项 ——
        // 浮层一开它就变成 null,看门狗会误判「树里没焦点」并每帧抢着请求,
        // 把浮层自己刚拿到的焦点搅掉,症状是「打开菜单后一项都没高亮、按什么都没反应」。
        // 让路的前提是浮层自己负责焦点恢复(铁律 3 的推论):GearMenu 自带 nonce 驱动的初始焦点循环。
        if (anyOverlay) return@LaunchedEffect
        // 还原效果正在把焦点送回离开前那一格时也要让路:否则两者同挤一帧,
        // 中间必然有一帧落在 (0,0),那次上报会把 activeRow 改成 0、其余行当场压暗再弹回 —— 闪一下。
        if (restoring) return@LaunchedEffect
        if (focusedCell != null) return@LaunchedEffect
        // D-pad 导航时 unfocus 和 focus 分属相邻两帧:旧控件先报 focusedCell=null,
        // 新控件下一帧才报 focusedCell=(x,y)。不等的话看门狗会在间隙里抢焦点,
        // 症状是「按上到齿轮时焦点闪一下弹回卡片」(2026-09-11 真机复现)。
        repeat(3) { withFrameNanos {} }
        if (focusedCell != null) return@LaunchedEffect
        val useGear = focusNonce == gearNonce
        val target = when {
            useGear && loaded != null -> gearFocus
            // 落点用「那一行记住的那一格」而不是第一行第一张 —— rowFocus 正好挂在那里
            // (upTarget/downTarget 用的就是它)。冷启动时两者是同一个节点,不构成回归。
            rows.isNotEmpty() ->
                rowFocus.getOrNull(tgtRow.coerceIn(0, rowFocus.lastIndex)) ?: firstCard
            loaded != null -> gearFocus     // 空桌面:焦点给齿轮
            else -> return@LaunchedEffect   // 还在加载,什么都还没建出来
        }
        var frames = 0
        // requestFocus() 返回 Unit,只有 requester 一个节点都没挂上时才抛,
        // 所以**不能**用它有没有抛异常来判断;只有 childFocused 变 true 才算落下。
        while (focusedCell == null && frames < 60) {
            withFrameNanos { }
            runCatching { target.requestFocus() }
            frames++
        }
    }


    // 背景与壁纸都在 MainActivity 那一层,这里保持透明
    Box(Modifier.fillMaxSize()) {

        // 待机用 alpha 淡出,不用 AnimatedVisibility——后者自带裁剪,会把超出屏幕的
        // 第三行整块切掉(实测 MUSIC 行因此始终不可见)。
        // NO_FADE(Task 3):恒 1,卡片/行标题/齿轮都不淡出——M5 之前的行为,什么都不发生。
        val contentAlpha by animateFloatAsState(
            targetValue = if (idle && idleContent != IdleContent.NO_FADE) 0f else 1f,
            animationSpec = tween(if (idle) 1200 else 400),
            label = "contentAlpha",
        )
        // 时钟默认待机也留着(CLOCK_ONLY/NO_FADE);只有 BLACK 时钟才跟着淡出,
        // 配合 MainActivity 在 Screensaver 之上叠的黑色蒙版,整屏才会真正全黑。
        val clockAlpha by animateFloatAsState(
            targetValue = if (idle && idleContent == IdleContent.BLACK) 0f else 1f,
            animationSpec = tween(if (idle) 1200 else 400),
            label = "clockAlpha",
        )
        // 待机用 alpha 淡出而**不移除节点**:移除会连带销毁焦点,醒来后按键落空。
        // 同理也不能用 canFocus 把它们关掉,理由见下面 focusProperties 那段。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // unbounded:允许内容高于屏幕。三行按 v4 的尺寸本来就放不下
                // (v4 里 MUSIC 行也在屏幕外),不放开的话第三行会被父容器裁掉。
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .alpha(contentAlpha)
                // 菜单开着时后面的卡片不能被聚焦,否则「焦点没进菜单」时它会原地
                // 留在蒙版后的卡片上,按确定直接启动应用(focusGroup 只约束组内发起的搜索)。
                //
                // **待机不放在这里**:canFocus 由 true 转 false 会让 Compose 对处于 Active 的
                // 节点调 clearFocus(force=true),整棵树的焦点当场消失,而 DPAD_CENTER 不参与
                // 框架的焦点恢复 —— 症状是「醒来后按确定永远没反应」。待机的唤醒改由
                // MainActivity.dispatchKeyEvent 吞掉第一下按键来实现,焦点全程不动。
                .focusProperties { canFocus = !anyOverlay }
                .offset(y = shift)
                .padding(top = Theme.TopPadding),
            verticalArrangement = Arrangement.spacedBy(Theme.RowSpacing),
        ) {
            // 配置里的应用一个都装不到时,屏幕上只剩时钟和齿轮,看着像坏了。
            // 给一句话告诉用户怎么自救(实测:此时齿轮菜单仍可用)。
            if (loaded != null && rows.isEmpty()) {
                BasicText(
                    text = stringResource(R.string.home_empty_apps_hint),
                    modifier = Modifier.padding(start = Theme.SidePadding),
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        color = Theme.RowTitle.copy(alpha = 0.75f),
                        fontSize = 15.sp,
                    ),
                )
            }
            rows.forEachIndexed { rowIndex, row ->
                CategoryRow(
                    row = row,
                    metrics = metrics,
                    showTitles = showTitles,
                    themedCards = themedCards,
                    titles = titles,
                    firstCard = if (rowIndex == 0) firstCard else null,
                    active = rowIndex == activeRowSafe,
                    rowRequester = rowFocus.getOrNull(rowIndex),
                    isLastRow = rowIndex == rows.lastIndex,
                    // 上下移动落到相邻行「记住的那一格」——每行的 requester 就挂在那一格上
                    upTarget = if (rowIndex > 0) rowFocus.getOrNull(rowIndex - 1) else gearFocus,
                    downTarget = if (rowIndex < rows.lastIndex) rowFocus.getOrNull(rowIndex + 1) else null,
                    targetIndex = tgtIdx.getOrElse(rowIndex) { 0 },
                    onFocusChange = { idx, got ->
                        report(rowIndex, idx, got)
                        if (got) {
                            activeRow = rowIndex
                            // 还原过程中不更新目标:否则 Compose 抢先把焦点给了第一张卡,
                            // 这次焦点事件就把目标改写成 0,还原当场失效。
                            if (!restoring) { tgtRow = rowIndex; tgtIdx[rowIndex] = idx }
                        }
                    },
                )
            }
        }

        // 时钟常驻,待机时也留着
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                // 右边距经三轮复审确认正确(1790.6 vs 1789.3)。顶部:复审实测参考里齿轮中心
                // 93.63px、时钟中心 93.15px,我们是 99.5/99.0,整条状态栏低了约 6px。
                .padding(top = 32.dp, end = 64.dp)
                // 菜单开着时齿轮也不能被聚焦。GearMenu 的 focusGroup 只保证「组内优先」,
                // 找不到候选会冒泡到根继续找;卡片那一列已经被 canFocus 关掉,
                // 但齿轮不在那一列里 —— 于是菜单里按右键焦点会落到蒙版后面的齿轮上,
                // 高亮消失、上下左右都没反应,而这个菜单里装着「切回 Projectivy」这条退路。
                .focusProperties { canFocus = !anyOverlay },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),   // 复审实测参考 36.2px,17dp 给出 42.2px
        ) {
            // 「有 N 个新应用」:状态栏里、齿轮左边的小字(design §4 的最终落位)。
            // **住在这一行是为了永不被卡片盖住**:左上角那版会在焦点落到最后一行、
            // 内容整块上移(`offset(y = shift)`)时被升上来的第一行盖掉半截(2026-09-16 实测);
            // 左下角那版会撞底行的卡片标题。右上角这条带子是屏幕上唯一永远没有卡片的地方。
            // 只是一行字,不可聚焦 —— 外层那个 canFocus 管的是齿轮,与它无关。
            val newCount = loaded?.third ?: 0
            if (newCount > 0) {
                BasicText(
                    text = stringResource(R.string.home_new_apps, newCount),
                    modifier = Modifier.alpha(contentAlpha),
                    style = TextStyle(fontFamily = Theme.Sans, color = Theme.FooterHintText, fontSize = 12.sp),
                )
            }
            // 齿轮同样用 alpha 而不是 AnimatedVisibility:待机时若把节点移除,
            // 恰好停在齿轮上的焦点会被销毁,醒来第一下按键落空。
            GearButton(
                onClick = { onMenuOpenChange(true) },
                // 齿轮的上、左、右都是空的(时钟不可聚焦),不锁的话按这三个方向焦点会整棵树消失
                modifier = Modifier
                    .alpha(contentAlpha)
                    .focusRequester(gearFocus)
                    .focusProperties {
                        up = FocusRequester.Cancel
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        // 空桌面时下方一张卡都没有,不锁的话按下键焦点会消失再被看门狗捞回来,
                        // 高亮闪一下 —— 而这正是唯一的自救界面。
                        if (rows.isEmpty()) down = FocusRequester.Cancel
                        // 非空时按下回到「记住的那一格」。走几何搜索的话,齿轮在右上角,
                        // 最近的永远是第一行最右那张 —— 与上下行的列记忆不一致。
                        else rowFocus.getOrNull(tgtRow.coerceIn(0, rowFocus.lastIndex))
                            ?.let { down = it }
                    },
                onFocusChange = { got -> report(-1, -1, got) },
            )
            Clock(modifier = Modifier.alpha(clockAlpha), showDate = showDate)
        }

        if (menuOpen) {
            GearMenu(
                items = menuItems.map { item ->
                    // 点任何一项都先收菜单,免得回来时还盖在上面
                    item.copy(action = { onMenuOpenChange(false); item.action() })
                },
                onDismiss = { onMenuOpenChange(false) },
                nonce = focusNonce,
            )
        }

        // 长按卡片菜单。**嵌在首页里而不是替换首页**:替换掉的话整棵卡片树被销毁,
        // tgtRow/tgtIdx 这些「记住的那一格」跟着 remember 一起没了,关菜单后焦点回到第一张卡。
        // 标题用该卡的显示名;取不到(极端情况下 label 为空)退回包名,绝不留一行空标题。
        val cm = cardMenu
        if (cm != null) {
            GearMenu(
                items = cardMenuItems,
                onDismiss = onCardMenuDismiss,
                nonce = focusNonce,
                title = cm.label.ifBlank { cm.pkg },
            )
        }

        // 「修改标题」对话框(Task 5,spec §3)。同样嵌在首页里而不是替换首页,理由同上。
        val rt = renameTarget
        if (rt != null) {
            TitleDialog(
                ref = rt,
                current = titles[rt.pkg] ?: "",
                onSave = { onRenameSave(rt, it) },
                onCancel = onRenameCancel,
                nonce = focusNonce,
            )
        }
    }
}

@Composable
private fun CategoryRow(
    row: Row,
    metrics: CardMetrics,
    /** 卡片标题全局开关 + 自定义标题表(design §2);输入源行不受它影响,见下方 AppCard 调用。 */
    showTitles: Boolean,
    themedCards: Boolean,
    titles: Map<String, String>,
    firstCard: FocusRequester?,
    active: Boolean,
    rowRequester: FocusRequester?,
    isLastRow: Boolean,
    upTarget: FocusRequester?,
    downTarget: FocusRequester?,
    targetIndex: Int,
    onFocusChange: (Int, Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    // 行标题文字 + 行图标 = 主题 **accent**(与齿轮同一个饱和色),换预设时和齿轮一起明显变色。
    // 早先用的是 highlight(accent 混 55% 白后近白),六个预设的近白值肉眼几乎无差,看着「换了预设也没变」
    // (2026-09-16 Gordon 真机指出);卡片聚焦的呼吸光晕仍读 highlight,那处要浅色不刺眼。
    val accent = LocalThemeColors.current.accent
    // 记住聚焦在第几张,用来算这一行的横向位移(超出右边界就整行左移)
    var focusedIndex by remember { mutableStateOf(0) }
    val rowAlpha by animateFloatAsState(
        targetValue = if (active) 1f else Theme.InactiveRowAlpha,
        animationSpec = tween(180),
        label = "rowAlpha",
    )
    Column(
        modifier = Modifier.alpha(rowAlpha),
        verticalArrangement = Arrangement.spacedBy(Theme.RowTitleGap),
    ) {
        Row(
            modifier = Modifier.padding(start = Theme.SidePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RowIcon(row.name, row.kind, tint = accent)
            BasicText(
                text = row.name,
                style = TextStyle(
                fontFamily = Theme.Sans,
                    // 行标题跟主题 accent 走(与齿轮同色)。
                    color = accent, fontSize = 15.5.sp, fontWeight = FontWeight.Medium,
                ),
            )
        }
        // **绝不能用 LazyRow / horizontalScroll**:任何可滚动容器都会挡住纵向焦点外出。
        // 2026-09-11 真机实测:按上/下时 Compose 找不到候选,平台的 View 级焦点导航接手,
        // 把整棵树的焦点清空(日志里是 ROOT hasFocus=false → 再 isFocused=true),
        // 之后按什么都没反应 —— 三行的桌面实际退化成只有第一行能用。
        // LazyRow 加 focusGroup、普通 Row 套 horizontalScroll,两种都试过,同样断。
        // 所以横向位移和上面纵向那段一样自己算:只有「不可滚动的 Row」不挡焦点。
        // 行可能变短(卸载了应用),索引留在旧值上会让整行多左移
        val focused = focusedIndex.coerceIn(0, row.apps.lastIndex.coerceAtLeast(0))
        val focusRight = Theme.SidePadding + metrics.cardWidth * (focused + 1) + metrics.cardSpacing * focused
        val overRight = focusRight + Theme.SidePadding - LocalConfiguration.current.screenWidthDp.dp
        val xShift by animateDpAsState(
            targetValue = if (overRight > 0.dp) -overRight else 0.dp,
            label = "rowXShift",
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(metrics.cardSpacing),
            modifier = Modifier
                // **必须 unbounded**:父 Column 是 fillMaxWidth,子 Row 拿到的 maxWidth 就是屏幕宽,
                // 而 Modifier.size() 会被 constrain —— 空间用完之后的卡片直接被量成 0 宽,
                // 而且因为焦点搜索的右向判据是 `focused.right < cand.right`(严格小于),
                // 这些 0 宽卡片的 right 全部相等,**永远聚焦不到**。
                // 实测边界:第 7 格只剩 43% 宽,第 8 格起为 0。
                // xShift 那套自算位移救不了它 —— offset 发生在测量**之后**,
                // 内容早在测量阶段就被砍掉了尾巴。纵向的 wrapContentHeight 是同一招。
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .offset(x = xShift)
                .padding(start = Theme.SidePadding, top = Theme.RowVerticalPad, bottom = Theme.RowVerticalPad),
        ) {
            row.apps.forEachIndexed { index, app ->
                AppCard(
                    app = app,
                    metrics = metrics,
                    // 标题开关为全局(design §2.2):输入源行不显示,自定义标题也一样受它约束。
                    title = if (showTitles && row.kind == RowKind.APPS) (titles[app.packageName] ?: app.label) else null,
                    fallbackColor = app.fallbackColor?.let { Color(it) },
                    themed = themedCards,
                    onClick = {
                        // 唯一按种类分流的地方:应用行启动包,输入源行切信号源
                        //(packageName 里存的是输入 id)。其余焦点/渲染全部与种类无关。
                        val ok = when (row.kind) {
                            RowKind.INPUTS -> Inputs.launch(ctx, app.packageName)
                            RowKind.APPS -> Apps.launch(ctx, app.packageName)
                        }
                        if (!ok) {
                            android.widget.Toast.makeText(
                                ctx, ctx.getString(R.string.toast_cant_open_app, app.label), android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    modifier = Modifier
                        .let { m ->
                            // 本行的 requester 挂在「目标格」上,不是永远挂在第 0 格
                            val t = targetIndex.coerceIn(0, row.apps.lastIndex.coerceAtLeast(0))
                            if (rowRequester != null && index == t) m.focusRequester(rowRequester) else m
                        }
                        .let { m ->
                            if (index == 0 && firstCard != null) m.focusRequester(firstCard) else m
                        },
                    onFocusChange = { got -> onFocusChange(index, got); if (got) focusedIndex = index },
                    isRowStart = index == 0,
                    isRowEnd = index == row.apps.lastIndex,
                    isLastRow = isLastRow,
                    upTarget = upTarget,
                    downTarget = downTarget,
                )
            }
        }
    }
}

private val SCREENSAVER_IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

/**
 * 屏保层。从 library/screensavers/ 读取全部图片,待机时轮播:
 * 交叉淡入(Crossfade)切换 + Ken Burns 缓慢缩放。
 * 图库为空时什么都不画,待机行为不变(只是首页内容淡出)。
 * HDR 通路:每张图用 RGBA_F16 解码,保留 Ultra HDR gain map。
 */
@Composable
fun Screensaver(ctx: Context, idle: Boolean) {
    // 图库只靠 adb push 改动,应用收不到任何通知,所以进入待机那一刻重扫一遍。
    // 用进程启动时的快照会把已删的文件也排进轮播:解不出来 → 那 30 秒只剩壁纸。
    // 唤醒时不重扫(保留上一份),淡出期间才有图可画。
    val imageFiles by produceState<List<java.io.File>>(emptyList(), ctx, idle) {
        if (!idle) return@produceState
        value = withContext(Dispatchers.IO) {
            migrateOldScreensaver(ctx)
            Paths.screensaverLibrary(ctx).listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in SCREENSAVER_IMAGE_EXTS }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
    }

    // 空图库并进目标值而不是提前 return:动画状态从一开始就在组合里,
    // 首次待机才有 0→1 的淡入(animateFloatAsState 首次组合会直接落在目标值上)。
    val layerAlpha by animateFloatAsState(
        targetValue = if (idle && imageFiles.isNotEmpty()) 1f else 0f,
        animationSpec = tween(if (idle) 1200 else 400),
        label = "screensaverAlpha",
    )
    if (layerAlpha == 0f) return

    var displayIndex by remember { mutableStateOf(0) }
    LaunchedEffect(idle, imageFiles.size) {
        if (!idle || imageFiles.size <= 1) return@LaunchedEffect
        while (true) {
            delay(Theme.ScreensaverIntervalMs)
            displayIndex = (displayIndex + 1) % imageFiles.size
        }
    }
    val safeIndex = if (imageFiles.isNotEmpty()) displayIndex % imageFiles.size else 0

    Box(Modifier.fillMaxSize().alpha(layerAlpha)) {
        Crossfade(
            targetState = safeIndex,
            animationSpec = tween(Theme.ScreensaverCrossfadeMs),
            label = "screensaverCrossfade",
        ) { index ->
            ScreensaverSlot(imageFiles.getOrNull(index))
        }
    }
}

@Composable
private fun ScreensaverSlot(file: java.io.File?) {
    file ?: return
    val bmp by produceState<android.graphics.Bitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Apps.decodeScaled(file.absolutePath, 1920, 1080, android.graphics.Bitmap.Config.RGBA_F16)
            }.getOrNull()
        }
    }
    val b = bmp ?: return
    val scale = remember { Animatable(1.0f) }
    LaunchedEffect(file.absolutePath) {
        scale.snapTo(1.0f)
        scale.animateTo(
            targetValue = Theme.ScreensaverZoom,
            animationSpec = tween(
                durationMillis = (Theme.ScreensaverIntervalMs + Theme.ScreensaverCrossfadeMs).toInt(),
                easing = LinearEasing,
            ),
        )
    }
    Image(
        bitmap = b.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().scale(scale.value),
    )
}

/**
 * 把旧版单张 screensaver.jpg/png 迁移到图库目录。只在图库为空时迁移一次。
 */
private fun migrateOldScreensaver(ctx: Context) {
    val dir = Paths.screensaverLibrary(ctx)
    if (dir.listFiles()?.any { it.isFile } == true) return
    for (old in listOf(Paths.screensaver(ctx), Paths.screensaverPng(ctx))) {
        if (old.exists()) {
            val dest = java.io.File(dir, old.name)
            old.renameTo(dest)
            break
        }
    }
}

/**
 * 输入源行。把每个硬件输入伪装成 [AppEntry](packageName 存输入 id、card=null 走文字回退),
 * 从而与应用行**共用** AppCard / CategoryRow / 整套纵向焦点账本。
 * 枚举为空(非电视、或没有硬件输入)时返回 null —— 这一行不渲染,焦点账本只挂非空行,
 * 与 buildRows 结尾那条不变量同源。标题走本地化字符串;ctx.getString 在 IO 线程可安全调用。
 */
private fun buildInputRow(ctx: Context): Row? {
    val inputs = Inputs.load(ctx)
    if (inputs.isEmpty()) return null
    return Row(
        name = ctx.getString(R.string.home_input_row_title),
        apps = inputs.map { AppEntry(packageName = it.id, label = it.label, card = null, isWide = false) },
        kind = RowKind.INPUTS,
    )
}

private fun buildRows(ctx: Context): List<Row> {
    val layout = Layout.read(ctx)
    val needed = layout.flatMap { it.second }.toSet()
    val all = Apps.load(ctx, needed, withBitmaps = needed, withLabels = needed)
    // **layoutRow 必须在 filter 之前定下来**:下面那个 filter 会整行丢掉空行,
    // 丢掉之后剩下行的下标就不再等于它们在 layout.json 里的下标。
    // 「移除 / 移动位置」写的是 layout.json,拿渲染下标去写就会打在别人那一行上。
    return layout.mapIndexed { layoutIndex, (name, pkgs) ->
        Row(name = name, apps = pkgs.mapNotNull { all[it] }, layoutRow = layoutIndex)
    }.filter { it.apps.isNotEmpty() }
    // ⚠️ 这个 filter 不只是显示意图,**它同时是焦点的不变量**:
    // upTarget/downTarget 指向相邻行的 rowFocus,而 rowFocus 只挂在非空行的卡片上。
    // 哪天想「空行也显示出来」,那些 requester 就会挂空,而 focusProperties 给出非 Default
    // 的 requester 会短路几何搜索 —— 按上/下将变成完全没反应。要改先想清楚这一条。
}
