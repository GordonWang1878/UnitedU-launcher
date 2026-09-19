package com.uniteduone.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首页:壁纸层 + hero 大字时钟 + 锚定在下三分之一的卡片行 + 右上 pill 组(设置 / 屏保)。
 * 待机由 [MainActivity] 通过 [idle] 传进来,内容由 [idleContent] 定(Task 3):
 * [IdleContent.CLOCK_ONLY](默认)卡片/行标题淡出、时钟留着;[IdleContent.BLACK] 同上但
 * 时钟也淡出(配合 MainActivity 叠加的黑屏,整屏全黑);[IdleContent.NO_FADE] 这里的
 * `contentAlpha` 恒为 1、什么都不淡出。
 *
 * [demoIdle](M7 T6,spec §3.2)非 null 时会**覆盖**这两个:设置页「待机内容」行拿着焦点
 * 期间,不管真实 [idle] 是不是待机,都按 `demoIdle` 演示对应内容,离开该行即恢复。
 * 只影响这里的 `contentAlpha`/`clockAlpha` 两个动画,`Screensaver` 不参与(它是
 * `MainActivity` 单独组合的另一层,M5 起读的是 `screensaverActive`)。
 *
 * [screensaver](M5 spec §1.4)为真 = 自定义屏保:行 / 渐变 / pill 一律淡出(「不淡出」也不例外——
 * 照片上不该浮着一排卡片),大字时钟恒亮并加淡阴影(「全黑」待机进屏保时,时钟随照片一起亮出来)。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    idle: Boolean,
    /** 自定义屏保(M5 spec §1.4)。只在 [idle] 为真时可能为真(MainActivity 的 StandbyFlags 钉死)。 */
    screensaver: Boolean = false,
    /** 待机时屏幕上显示什么(design 待机 §,Task 3);默认与老行为一致。 */
    idleContent: IdleContent = IdleContent.CLOCK_ONLY,
    /** 待机演示(M7 T6,spec §3.2):非 null 时覆盖 [idle]/[idleContent] 驱动的两个淡出动画。 */
    demoIdle: IdleContent? = null,
    menuItems: List<MenuItem>,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onScreensaver: () -> Unit = {},
    focusNonce: Int,
    revision: Int = 0,
    menuFromGear: Boolean = true,
    showDate: Boolean = true,
    cardsPerRow: Int = 6,
    /** 卡片标题全局开关(design §2)。开着时卡片下方多一行标题,行高随之增加
     *  (见 HomeLayout.titleHeight),纵向位移沿用同一套自算逻辑。 */
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
     * **预览态**(M7 T4 分层叠加):选择器 / 导入页这类整屏浮层现在**叠在首页之上**,
     * 首页不再被移除,而是退到底下当背景(设置页 T5 起同理)。为真时首页交出一切交互:
     * - **不可聚焦**:所有 [AppCard] / [TopPills] `canFocus = false` —— 与 `anyOverlay`
     *   合成 `covered` 一个量,上面那层拿焦点,底下这层绝不抢(铁律 4 的推论)。
     * - **不处理任何按键**:首页自己没有 `onKeyEvent`,卡片的点击挂在 `clickable` 上,
     *   不可聚焦就一个按键都收不到;长按识别在 `MainActivity.dispatchKeyEvent` 里,
     *   由那边的 `homeBare`(判 `overlayOpen`)挡住。
     * - **焦点记忆冻结**:`restoring = covered || stale`,`tgtRow`/`tgtIdx` 原样留着,
     *   浮层关掉后由还原效果送回离开前那一格。
     *
     * 正因为最后这条,**焦点记忆的「种子」整套退役了**:以前浮层住在 if/else 链上、开着时
     * 首页整棵树被移除,`remember` 一起没,才需要 MainActivity 用 `homeInitialTarget`
     * 把坐标种回来;现在这棵树自始至终活着,记忆天然保留,种子反而是多出来的一份状态
     * (还要额外一条「消费完清掉」的窄路,正是铁律 7 要避免的东西)。
     */
    previewing: Boolean = false,
    /**
     * **首页原地移动态**(M4b spec §3,状态住在 MainActivity)。非空时:行一律画 [MoveState.rows](不读 `loaded`),
     * 被搬的那张卡描 accent 边,屏幕底部一行提示;焦点的目标格就是 [MoveState.pos]——还原效果以它为 key 与目标,
     * 看门狗以它为目标。它**不并进 `covered`**:移动态没有浮层,焦点始终在被搬的卡上,首页的两个焦点效果照常工作,
     * 只是目标换成了它。期间焦点上报不改 `tgtRow`/`tgtIdx`(首页自己的记忆冻结,结束时由 [moveLanding] 一次写入)。
     */
    moving: MoveState? = null,
    /** 移动态结束时焦点该落的那一格(见 [MoveLanding]):还原效果把它写进 `tgtRow`/`tgtIdx`,每个落点只写一次。 */
    moveLanding: MoveLanding? = null,
    /** 这一次组合画出来的行(含置顶的输入源行)。MainActivity 进移动态时拿最近一份当工作副本。 */
    onRowsShown: (List<Row>) -> Unit = {},
) {
    val ctx = LocalContext.current
    // 卡片档位尺寸:5/6/8 三档统一由 HomeLayout 按张数推导,不再有「6 是标定常量、5/8 反推」的特例。见 Theme.cardMetrics。
    val metrics = Theme.cardMetrics(cardsPerRow)
    // **首页内嵌的浮层**:齿轮菜单、长按卡片菜单、修改标题对话框 —— 它们住在首页这棵树里面。
    val anyOverlay = menuOpen || cardMenu != null || renameTarget != null
    // **「首页被盖住了没有」只此一个判据。**内嵌的那三层(`anyOverlay`)之外,M7 T4 起还有
    // 叠在首页之上的整屏浮层([previewing]:选择器 / 导入页,T5 起加设置页)。四者对下面**四处**的
    // 要求完全相同:卡片不可聚焦、齿轮不可聚焦、还原效果让路、看门狗让路。
    // 分开写四遍 `menuOpen ||` 迟早漏掉一处,而漏掉的那一处就是「菜单开着时看门狗每帧抢焦点,
    // 菜单里一项都不高亮」(铁律 4 的推论)。合成一个量之后,它同时是那两个效果的 key 与守卫(铁律 6)。
    // **例外:`gearNonce` 那个 LaunchedEffect 仍然只看 menuOpen** —— 它专管「齿轮菜单关了回齿轮」,
    // 长按菜单关掉后焦点应该回到那张卡,不是齿轮。
    val covered = anyOverlay || previewing
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
            // titles 要在输入源行之前读出:buildInputRow 用它给改过名的输入源换标签(applyInputPrefs)。
            val titles = runCatching { Titles.read(ctx) }.getOrDefault(emptyMap())
            // 输入源行放**最上面**:design §2 把「输入源」当独立顶层类目,置顶与之相符;
            // 且置顶后应用行的相对次序、以及「开机焦点落在最上一行」的直觉都不变。
            // 枚举为空(非电视 / 没有硬件输入 / 全部隐藏)时返回 null,这一行干脆不存在 —— 焦点账本
            // 只认非空行,不会挂空 requester(见 buildRows 结尾那条不变量)。
            val inputRow = if (showInputRow) runCatching { buildInputRow(ctx, titles) }.getOrNull() else null
            val rows = if (inputRow != null) listOf(inputRow) + appRows else appRows
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
    val titles = loaded?.second.orEmpty()
    /** 数据还没跟上当前 revision(重读在途)。冻结目标用,见 loadedRevision 的注释。 */
    val stale = loadedRevision != revision
    /** 上一次组合画出来的行。普通引用、不是快照状态:只在下面 `rows` 的第二支里读,而那一支的两个条件翻转本身就会触发重组。 */
    val onScreen = remember { arrayOf<List<Row>>(emptyList()) }
    val rows = when {
        // 移动态:画工作副本,每按一步就是一次列表重排(M4b spec §3)
        moving != null -> moving.rows
        // **刚放下、写了盘、revision++ 的重读还在路上**:落地之前继续画搬好的那一份(= 上一次组合画的)。
        // 退回 loaded 的话,卡片会先跳回搬之前的位置、几百毫秒后再跳到新位置。取消不走这一支(wrote = false):
        // 取消要的正是 loaded 里原来的样子。其余任何重读期间 onScreen 本来就等于 loaded 的旧值,这一支与原行为相同。
        stale && moveLanding?.wrote == true -> onScreen[0]
        else -> loaded?.first.orEmpty()
    }
    SideEffect {
        onScreen[0] = rows
        onRowsShown(rows)
    }
    /**
     * 还原效果的「数据不新鲜就冻结」。**移动态期间不冻**:那时画的是工作副本,与在途的重读无关——冻住的话,
     * 后台某个应用恰好更新(revision++)的那几百毫秒里,每搬一步焦点都追不过去,停在换过来的邻卡上。
     */
    val frozen = stale && moving == null
    /** 移动态的目标格(见 [moving] 的 KDoc);null = 不在移动态,目标照旧是 tgtRow/tgtIdx。 */
    val moveTarget = moving?.pos
    // 焦点回调在事件发生时才执行,读的是**最近一次组合**的移动态,不是回调被创建那一刻的。
    val movingNow by rememberUpdatedState(moving)
    // 开机后焦点要自己落到第一张卡片上,否则方向键第一下没有反应。
    val firstCard = remember { FocusRequester() }
    // 每行一个 requester。当前行(tgtRow)的挂在它记住的那一格,用来把焦点**还原到离开前那张卡**;
    // 其它行的挂在「与当前列对齐、按该行长度夹取」的格子上,上下键就落在同一列(见 CategoryRow 调用处)。
    val rowFocus = remember(rows.size) { List(rows.size.coerceAtLeast(1)) { FocusRequester() } }
    // 「目标格」只由用户的主动导航更新,还原过程中不更新 ——
    // 否则 Compose 抢先把焦点给了第一张卡,目标就被改写成 0 了。
    // (横向位移由 CategoryRow 自己的 focusedIndex 算,不在这里。)
    // 目标格一律从 (0,0) 起。**这棵树只在冷启动 / 进出编辑页时才重建**,那两条路本来就该
    // 落在第一张卡。M7 T4 之前这里还有一颗 `initialTarget` 种子,专为「图片选择器把首页
    // 整棵树移除」那条路把坐标种回来;选择器改成叠加之后首页常驻,记忆改由 [previewing]
    // 的冻结保住(见它的 KDoc),种子连同「消费完要清掉」那条窄路一起退役 ——
    // 少一份状态,就少一条会过期的路(铁律 7)。
    val tgtIdx = remember(rows.size) {
        mutableStateListOf(*Array(rows.size.coerceAtLeast(1)) { 0 })
    }
    var tgtRow by remember { mutableStateOf(0) }
    /**
     * **目标是齿轮(true)还是那一格卡片(false)**。与 [tgtRow]/[tgtIdx] 同构,是同一条铁律 5
     * 在齿轮上的应用:「目标」与「当前位置」必须分开,而且从浮层打开(或 `ON_PAUSE`)就冻住。
     *
     * 为什么不能只靠 `gearNonce`(M7 T4 实测):`gearNonce` 把「关掉之后回齿轮」这个意图
     * **钉在某一个 focusNonce 上**,而浮层链里每一层关掉时都会 `focusNonce++`
     * (关长按菜单、关选择器、关导入页、关设置页)。于是同一个意图在两条路上给出两种结果——
     * 「换壁纸 → 选一张」不 ++、比对成立,「导入图片 → 返回」++ 一次、比对失效、焦点落回卡片。
     * 记成目标就没有这个问题:它由**焦点真的落在哪**更新(铁律 4:只信控件自报),
     * 浮层期间 `restoring` 冻着它,关掉后原样还原,中途 nonce 怎么涨都不影响。
     * 也不是闩(铁律 7):每次焦点落地都是一次全新赋值,没有「只有一条窄路能清」的状态。
     */
    var tgtGear by remember { mutableStateOf(false) }
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
    // (-1, col) 表示焦点在顶栏 pill 组上(col 0 设置 / 1 屏保)。
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
     * (ii) **卡→顶栏 pill 且回调顺序是「新先旧后」**——pill 的 got 把 focusedCell 写成 (-1, col)(不上报),
     *     随后卡片的 lost 看到 focusedCell != null 就不清,pill 上长按弹出上一张卡的菜单。
     * 派生之后两条路都自愈:上报值永远等于 cardAt(focusedCell) 对**当前** rows 的求值;
     * 数据重载由下面那个 LaunchedEffect(loaded, focusedCell) 再算一次(rows 变 → key 变,没有闩)。
     * layoutRow **直接取 Row 自己带的那个**(buildRows 在 filter 之前按 layout.json 定的),
     * 不由渲染下标推导 —— 装不到的包会让某一行消失,推导出来的行号就会偏移,
     * 「移除」会删到别人那一行(见 Row.layoutRow 的 KDoc)。
     */
    fun cardAt(cell: Pair<Int, Int>?): CardRef? {
        val (row, idx) = cell ?: return null
        if (row < 0) return null                       // (-1, col) = 顶栏 pill:它不是卡,长按不该出菜单
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
        // 目标跟着「焦点真的落在哪」走,**还原过程中不更新**——理由与下面卡片那两个目标完全相同:
        // 浮层关掉那一帧 Compose 会抢先把焦点塞给 (0,0),那次上报若不挡住就会把目标从齿轮改成卡片。
        // **数据还没到也不更新**(`loaded != null`):冷启动时卡片一张都还没建出来,整棵树里
        // 唯一可聚焦的就是齿轮,Compose 会把首帧的焦点给它 —— 那不是用户的选择,是「没得选」。
        // 不挡住的话目标被这一下定成齿轮,行数据到达后还原效果反而主动把焦点拽回齿轮,
        // 开机第一屏的焦点就从第一张卡变成了右上角(2026-09-17 冷启动三连实测)。
        // 卡片那两个目标不必判:卡片本身就是数据到了才存在,这条件对它们是隐含成立的。
        // **移动态期间也不更新**(与卡片那两个目标同一条,铁律 5「每一个分量」):那时的目标归 MainActivity 的
        // moving.pos 管,首页自己的记忆冻结,结束时由落点(moveLanding)一次写入。
        if (got && !restoring && loaded != null && movingNow == null) tgtGear = row == -1
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
    // key 用**画出来的** rows 而不是 loaded(M4b):移动态每搬一步、取消时画回原样,rows 都变而 loaded 不变。
    LaunchedEffect(rows, focusedCell) { onFocusedCard(cardAt(focusedCell)) }
    // 配置里的包一个都装不到时,卡片一张都没有,焦点无处可落;而这时唯一能自救的
    // 控件正是齿轮。不能指望框架的隐式 focus-enter——这份代码在别处恰恰拒绝依赖它。
    val gearFocus = remember { FocusRequester() }
    // 哪一行是「当前行」——决定纵向锚定位移与 hero 淡出;跟着焦点走。
    var activeRow by remember { mutableStateOf(0) }

    // 垂直位置自己算,不用 verticalScroll(铁律 1)。M8:焦点行**锚定**在下三分之一(spec §2.2)——
    // 内容整块上移 activeRow 个行距,第 0 行时 hero 完整;不再是「溢出才上移」。
    val screenH = LocalConfiguration.current.screenHeightDp.toFloat()
    val activeRowSafe = activeRow.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val anchorTop = HomeLayout.anchorTop(screenH).dp
    val shift by animateDpAsState(
        targetValue = HomeLayout.shift(activeRowSafe, cardsPerRow, showTitles).dp,
        animationSpec = tween(Theme.MotionInMs, easing = Theme.MotionEasing),
        label = "rowShift",
    )
    // hero 主体第 1 行起淡出(spec §2.3);待机时无条件回到 1(spec §2.4)——Task 6 的 HeroClock 读它。
    val heroAlpha by animateFloatAsState(
        targetValue = if (idle || demoIdle != null) 1f else HomeLayout.heroAlpha(activeRowSafe),
        animationSpec = tween(Theme.MotionInMs, easing = Theme.MotionEasing),
        label = "heroAlpha",
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
    /**
     * 最近一次已经写进目标格的落点(按对象身份比对)。初值取**本页创建那一刻**已有的落点:那是上一个首页实例的,
     * 这一个不该再应用它(首页只在冷启动 / 进出编辑页时重建,那两条路本来就该落在第一张卡)。
     * 不是闩(铁律 7):每次移动态结束 MainActivity 都给一个新对象,比对自然不成立,不需要任何人清。
     */
    var appliedLanding by remember { mutableStateOf(moveLanding) }
    // **key 里多了 moveTarget 与 moveLanding**(M4b 原地移动):移动态下每搬一步 moving.pos 就变,本效果随之重启、
    // 把焦点送到被搬的卡的新位置——目标变了效果就重跑,守卫与 key 成对(铁律 6)。移动态结束(放下 / 取消)时
    // moveTarget 变回 null、moveLanding 换成新对象,本效果再跑一次,把落点写进首页自己的目标格。
    // stale 换成了 frozen(= stale && 不在移动态,见其 KDoc),同样既是 key 又是守卫。
    LaunchedEffect(focusNonce, rows.size, rows.isEmpty(), covered, frozen, moveTarget, moveLanding) {
        // 除「浮层开着」外的每条分支都要把 restoring 放掉,否则用户自己的导航从此更新不了目标。
        // **浮层开着时反过来要把它按住**(`restoring = covered` 而不是恒 false):
        // 浮层关掉的那一帧,canFocus 从 false 回到 true,Compose 的默认恢复会抢在本效果重启之前
        // 把焦点给整棵树第一个可聚焦节点 = (0,0);那次上报此时看到 restoring 还是 false,
        // 于是把 tgtRow/tgtIdx 改写成 (0,0) —— **记忆在被用到之前就没了**(铁律 5),
        // 本效果随后读到的目标已经是第一张卡,循环一次都不跑,焦点静默留在 (0,0)。
        // 2026-09-16 实测:长按菜单与三条杠键打开的齿轮菜单都复现,而「从别的应用回来」这条路
        // 不复现 —— 差别正是后者在 ON_PAUSE 就冻结了。冻结点必须早于那次默认恢复,
        // 而浮层**打开**时冻结留有整整一个浮层的时间,足够早。
        // 写成派生于 covered 而不是一次性布尔闩(铁律 7):浮层一关它自然放开,没有要清的闩;
        // 而守卫读的 covered 本身就是 key(铁律 6)。
        // **M7 T4 起 `covered` 还包含 [previewing]**,于是「选择器开着」这段时间目标同样被冻住:
        // 这正是种子能退役的原因 —— 冻结期间任何抢先的默认恢复都改写不了 tgtRow/tgtIdx。
        // **`stale` 同理,而且它是「移除 / 卸载后还站在同一行」的关键**:那两个动作先关菜单
        // (nonce++)、再 revision++,新的行数据要几百毫秒才到。此刻若放开冻结,数据落地那一帧
        // 焦点卡的节点被销毁、Compose 把焦点塞给 (0,0),那次上报就把目标改写成第一行第一张,
        // 随后的还原只会把焦点送回那里。冻到数据新鲜为止,还原效果再按夹过的列号把焦点送到
        // 同行邻卡(design §1 的「行变短时索引夹取」)。
        if (focusNonce == 0 || rows.isEmpty() || covered || frozen) {
            restoring = covered || frozen; return@LaunchedEffect
        }
        // **移动态刚结束:落点成为首页的目标格**(放下 = 卡的新位置,取消 = 出发那一格)。写在守卫**之后**:
        // 放下后要等重读落地(frozen)才写,那时 tgtIdx 已按新数据的行数建好;浮层开着(covered)时同理等它关掉。
        // 冻结期间 restoring 为真,焦点上报本来就改不了目标,晚写不会被谁抢先改掉。
        val landing = moveLanding
        if (moveTarget == null && landing != null && landing !== appliedLanding) {
            appliedLanding = landing
            tgtGear = false
            tgtRow = landing.pos.row
            if (landing.pos.row in tgtIdx.indices) tgtIdx[landing.pos.row] = landing.pos.col
        }
        // **回齿轮这条路也必须主动请求**,不能像以前那样早退、把它交给看门狗(M7 T4 实测):
        // 看门狗只在「树里一个焦点都没有」时才动手,而它开头要等 3 帧(躲 D-pad 导航的得失间隙)——
        // 浮层关掉后 Compose 的默认恢复就在这几帧里把焦点塞给了 (0,0),看门狗看到 `focusedCell != null`
        // 当场让路,于是「换壁纸回来焦点回齿轮」变成了「落在第一张卡」。卡片那条路一直是对的,
        // 正因为它是这里主动请求的;齿轮只是缺了对称的一半。
        // 目标读 [tgtGear](冻结过的),`gearNonce` 仍然并进来:它管的是「菜单刚关掉」那一拍。
        // 两者都是「读的量」不是守卫,与 tgtRow/tgtIdx 同例,不进 key(进了会在每次导航时重跑还原)。
        restoring = true
        var frames = 0
        // 移动态下焦点只去被搬的那张卡,不回齿轮
        if (moveTarget == null && (tgtGear || focusNonce == gearNonce)) {
            // 退出条件同样只信控件自报(铁律 2):设置 / 屏保两个 pill 都会 report(-1, col, true),
            // 两者都算「回到顶栏」——按 focusedCell?.first 判,不钉死某一列(col 0/1 都算数)。
            while (frames < 60 && focusedCell?.first != -1) {
                withFrameNanos { }
                runCatching { gearFocus.requestFocus() }
                frames++
            }
            restoring = false
            return@LaunchedEffect
        }
        // 移动态:目标 = 被搬的卡现在的位置(MainActivity 的 moving.pos);否则 = 首页记住的那一格。
        val r = (moveTarget?.row ?: tgtRow).coerceIn(0, rowFocus.lastIndex)
        // 退出条件必须**同时**满足「树里真的有焦点」和「落在目标格上」:
        // 只看「当前格 == 目标格」的话,丢焦点时没人把「当前」作废,条件一开始就成立、
        // 循环一次都不跑;只看「有没有焦点」的话,Compose 抢先给了第一张卡就会提前退出。
        // 判据必须与 requester 的挂点用**同一个夹过的坐标**:挂点夹过、判据没夹的话,
        // 行变短后「目标 = 第 5 格」而焦点只可能落到第 4 格,条件恒不成立,
        // 循环会跑满 60 帧、期间每帧把焦点拽回同一格,用户按的方向键当场被撤销。
        val cap = rows.getOrNull(r)?.apps?.lastIndex?.coerceAtLeast(0) ?: 0
        val want = r to (moveTarget?.col ?: tgtIdx.getOrNull(r) ?: 0).coerceIn(0, cap)
        while (frames < 60 && focusedCell != want) {
            withFrameNanos { }
            runCatching { rowFocus[r].requestFocus() }
            frames++
        }
        restoring = false
    }
    LaunchedEffect(rows.isEmpty(), loaded != null, focusNonce, focusedCell, covered, restoring, gearNonce) {
        // **任何浮层开着时让路。**focusedCell 只记录卡片与齿轮,不认识菜单项 ——
        // 浮层一开它就变成 null,看门狗会误判「树里没焦点」并每帧抢着请求,
        // 把浮层自己刚拿到的焦点搅掉,症状是「打开菜单后一项都没高亮、按什么都没反应」。
        // 让路的前提是浮层自己负责焦点恢复(铁律 3 的推论):GearMenu 自带 nonce 驱动的初始焦点循环。
        // 叠在首页之上的选择器 / 导入页同理(各自的初始焦点循环),所以 [previewing] 也算在 covered 里。
        if (covered) return@LaunchedEffect
        // 还原效果正在把焦点送回离开前那一格时也要让路:否则两者同挤一帧,
        // 中间必然有一帧落在 (0,0),那次上报会把 activeRow 改成 0、锚定位移 shift 当场跳去第 0 行的
        // 目标值再弹回 —— 闪一下(压暗邻行 M8 已删,现在会跳的只剩这个位移量)。
        if (restoring) return@LaunchedEffect
        if (focusedCell != null) return@LaunchedEffect
        // D-pad 导航时 unfocus 和 focus 分属相邻两帧:旧控件先报 focusedCell=null,
        // 新控件下一帧才报 focusedCell=(x,y)。不等的话看门狗会在间隙里抢焦点,
        // 症状是「按上到齿轮时焦点闪一下弹回卡片」(2026-09-11 真机复现)。
        repeat(3) { withFrameNanos {} }
        if (focusedCell != null) return@LaunchedEffect
        // 与还原效果同一判据:冻结过的目标优先,`gearNonce` 管「菜单刚关掉」那一拍。
        // `tgtGear` 不进 key 也不违反铁律 6:它只在焦点真的落下时才变,而那一下必定同时改写
        // `focusedCell`(已经是 key),本效果照样会以新值重启;而且它不是守卫,只决定送去哪儿。
        // 移动态:目标是被搬的那张卡(moveTarget 只决定送去哪儿、不是守卫;它一变还原效果就重启、
        // restoring 随之置真,本效果以 restoring 这个 key 重启让路,不会拿着旧目标跟还原效果抢)。
        val useGear = moveTarget == null && (tgtGear || focusNonce == gearNonce)
        val target = when {
            useGear && loaded != null -> gearFocus
            // 落点用「那一行记住的那一格」而不是第一行第一张 —— rowFocus 正好挂在那里
            // (upTarget/downTarget 用的就是它)。冷启动时两者是同一个节点,不构成回归。
            rows.isNotEmpty() ->
                rowFocus.getOrNull((moveTarget?.row ?: tgtRow).coerceIn(0, rowFocus.lastIndex)) ?: firstCard
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

        // **待机演示覆盖**(M7 T6,spec §3.2):`demoIdle` 非空时不管真实 `idle`,这两个
        // 动画都按它演示——设置页「待机内容」行左右切换时,底层首页要当场看到三种效果。
        // `Screensaver` 不读这两个量,不参与演示(它是 MainActivity 单独组合的另一层)。
        val effectiveIdle = idle || demoIdle != null
        val effectiveIdleContent = demoIdle ?: idleContent
        // 待机用 alpha 淡出,不用 AnimatedVisibility——后者自带裁剪,会把超出屏幕的
        // 第三行整块切掉(实测 MUSIC 行因此始终不可见)。
        // NO_FADE(Task 3):待机时恒 1,卡片/行标题/pill 都不淡出。**自定义屏保例外**(M5 spec §0 / §1.4):
        // 「不淡出」只管待机显示,屏保照样全屏——照片上不能浮着一排卡片,所以 screensaver 为真时一律淡出。
        val contentAlpha by animateFloatAsState(
            targetValue = if (screensaver || (effectiveIdle && effectiveIdleContent != IdleContent.NO_FADE)) 0f else 1f,
            animationSpec = tween(if (effectiveIdle) 1200 else 400),
            label = "contentAlpha",
        )
        // 时钟默认待机也留着(CLOCK_ONLY/NO_FADE);只有 BLACK 时钟才跟着淡出,
        // 配合 MainActivity 在 Screensaver 之上叠的黑色蒙版,整屏才会真正全黑。
        // 自定义屏保时恒 1(M5 spec §1.4):「全黑」待机进屏保那一刻,时钟随照片一起亮出来。
        val clockAlpha by animateFloatAsState(
            targetValue = if (!screensaver && effectiveIdle && effectiveIdleContent == IdleContent.BLACK) 0f else 1f,
            animationSpec = tween(if (effectiveIdle) 1200 else 400),
            label = "clockAlpha",
        )
        // scrim(spec §2.1):#1C1B1F α0 → α0.8;顶边 = 锚点上方 60dp 再加 shift,底边固定屏底——行往上推时它变高,
        // 下方新露出的行始终在暗层里。待机时随内容一起淡出。
        val scrimTop = anchorTop - HomeLayout.SCRIM_LEAD.dp + shift
        val surface = androidx.tv.material3.MaterialTheme.colorScheme.surface
        // 首页提示文字的字样:空桌面求救那句与移动态底部提示共用一份(M4b spec §0-10「沿用现有提示文字样式」)
        val hintStyle = TextStyle(
            fontFamily = Theme.Sans,
            color = androidx.tv.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            fontSize = 15.sp,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .offset(y = scrimTop)
                .height((screenH.dp - scrimTop).coerceAtLeast(0.dp))
                .alpha(contentAlpha)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to surface.copy(alpha = 0f), 1f to surface.copy(alpha = 0.8f),
                    ),
                ),
        )

        // hero 主体(spec §2.1 第 3 层):不随 shift 走;第 1 行起淡出、待机时回到 1(heroAlpha),BLACK 待机再随 clockAlpha 淡出。
        // 自定义屏保时加淡阴影(M5 spec §1.5):照片可能很亮。
        HeroClock(
            showDate = showDate,
            shadow = screensaver,
            modifier = Modifier
                .padding(start = Theme.SidePadding, top = HomeLayout.HERO_TOP.dp)
                .alpha(heroAlpha * clockAlpha),
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
                .focusProperties { canFocus = !covered }
                .offset(y = shift)
                .padding(top = anchorTop),
            verticalArrangement = Arrangement.spacedBy(HomeLayout.ROW_GAP.dp),
        ) {
            // 配置里的应用一个都装不到时,屏幕上只剩时钟和齿轮,看着像坏了。
            // 给一句话告诉用户怎么自救(实测:此时齿轮菜单仍可用)。
            // **被整屏浮层盖着时不画**(M7 T10):这句话说的是「齿轮已选中」,而浮层开着时齿轮
            // 不可聚焦、焦点在浮层里——它是一句假话;首次引导的 α 0.85 遮罩下它还正好横在
            // 语言按钮与「继续」之间(模拟器截图实测)。previewing = false 时行为不变。
            if (loaded != null && rows.isEmpty() && !previewing) {
                BasicText(
                    text = stringResource(R.string.home_empty_apps_hint),
                    modifier = Modifier.padding(start = Theme.SidePadding),
                    style = hintStyle,
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
                    rowRequester = rowFocus.getOrNull(rowIndex),
                    isLastRow = rowIndex == rows.lastIndex,
                    // 上下移动落到相邻行的 requester;它挂在哪一格由下面 targetIndex 决定
                    upTarget = if (rowIndex > 0) rowFocus.getOrNull(rowIndex - 1) else gearFocus,
                    downTarget = if (rowIndex < rows.lastIndex) rowFocus.getOrNull(rowIndex + 1) else null,
                    // **上下键同列落点,邻行更短就夹到它的末张**(Google TV / tvOS 规则;2026-09-18 Gordon A95L 验收后定,
                    // 取代原来「每行记住自己的列」——那条规则依赖历史,同一个起点会落到不同列,看着像随机)。
                    // 实现:非当前行的 requester 挂在「当前行的列」经该行长度夹取后的格子上;当前行(tgtRow)仍挂自己记住的列,
                    // 还原效果与顶栏 pill 的下键都靠它回到离开前那一格。tgtRow/tgtIdx 在浮层 / 还原期间冻结,挂点随之稳定。
                    // 移动态:每一行的 requester 都挂在被搬的卡的列上(夹到该行长度)——被搬的卡所在那一行
                    // 就正好挂在它身上,还原效果与看门狗请求的就是它。
                    targetIndex = when {
                        moveTarget != null -> moveTarget.col
                        rowIndex == tgtRow -> tgtIdx.getOrElse(rowIndex) { 0 }
                        else -> tgtIdx.getOrElse(tgtRow) { 0 }
                    },
                    carried = if (moveTarget?.row == rowIndex) moveTarget.col else -1,
                    onFocusChange = { idx, got ->
                        report(rowIndex, idx, got)
                        if (got) {
                            // 纵向锚定照常跟着焦点走:被搬的卡换到哪一行,那一行就被推到锚点上
                            activeRow = rowIndex
                            // 还原过程中不更新目标:否则 Compose 抢先把焦点给了第一张卡,
                            // 这次焦点事件就把目标改写成 0,还原当场失效。
                            // 移动态期间也不更新:目标归 moving.pos 管,首页的记忆冻结到落点写入(铁律 5)。
                            if (!restoring && movingNow == null) { tgtRow = rowIndex; tgtIdx[rowIndex] = idx }
                        }
                    },
                )
            }
        }

        // 顶栏(spec §1.5):右上 pill 组 + 其下的「有 N 个新应用」。不随 shift 走;待机随内容淡出。
        // 节点只淡出不移除:移除会连带销毁停在按钮上的焦点,醒来第一下按键落空。
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = HomeLayout.PILL_TOP.dp, end = Theme.SidePadding)
                .alpha(contentAlpha),
            horizontalAlignment = Alignment.End,
        ) {
            TopPills(
                gearFocus = gearFocus,
                canFocus = !covered,
                rowsEmpty = rows.isEmpty(),
                downTarget = rowFocus.getOrNull(tgtRow.coerceIn(0, rowFocus.lastIndex)),
                onSettings = { onMenuOpenChange(true) },
                onScreensaver = onScreensaver,
                onFocusChange = { col, got -> report(-1, col, got) },
            )
            val newCount = loaded?.third ?: 0
            if (newCount > 0) {
                BasicText(
                    text = stringResource(R.string.home_new_apps, newCount),
                    modifier = Modifier.padding(top = 6.dp),
                    style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(
                        color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }

        // 移动态底部提示(M4b spec §0-10:视觉只加描边与这一行)。字样沿用首页提示文字;垫一层与 scrim 底端
        // 同色同透明度的底:焦点行下面那一行的行标题正好露在屏幕底部,不垫的话两行字叠在一起认不出来。
        if (moving != null) {
            BasicText(
                text = stringResource(R.string.home_move_hint),
                style = hintStyle,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = HomeLayout.PILL_TOP.dp)
                    .background(surface.copy(alpha = 0.8f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
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
                key = rt.pkg,
                current = titles[rt.pkg] ?: "",
                heading = stringResource(R.string.title_dialog_title),
                // 输入源卡:清空恢复系统名(title_dialog_hint_input);应用卡:清空恢复应用名。
                hint = stringResource(
                    if (rt.kind == RowKind.INPUTS) R.string.title_dialog_hint_input else R.string.title_dialog_hint,
                ),
                onSave = { onRenameSave(rt, it) },
                onCancel = onRenameCancel,
                nonce = focusNonce,
                subtitle = rt.label.ifBlank { rt.pkg },
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
    rowRequester: FocusRequester?,
    isLastRow: Boolean,
    upTarget: FocusRequester?,
    downTarget: FocusRequester?,
    targetIndex: Int,
    /** 移动态里被搬的卡在本行第几列;-1 = 不在本行(或不在移动态)。 */
    carried: Int = -1,
    onFocusChange: (Int, Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    // 行标题文字 + 行图标 = 主题 **accent**(与齿轮同一个饱和色),换预设时和齿轮一起明显变色。
    // 早先用的是 highlight(accent 混 55% 白后近白),六个预设的近白值肉眼几乎无差,看着「换了预设也没变」
    // (2026-09-16 Gordon 真机指出);卡片聚焦的呼吸光晕仍读 highlight,那处要浅色不刺眼。
    val accent = LocalThemeColors.current.accent
    // 记住聚焦在第几张,用来算这一行的横向位移(超出右边界就整行左移)
    var focusedIndex by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(HomeLayout.ROW_TITLE_GAP.dp)) {
        Row(
            modifier = Modifier.padding(start = Theme.SidePadding).height(HomeLayout.ROW_TITLE_LINE.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RowIcon(row.name, row.kind, row.icon, tint = accent)
            BasicText(
                text = row.name,
                // 行标题 = titleMedium 16sp Medium(spec §1.4),颜色 accent(spec §0「accent 落点」)
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium.copy(color = accent),
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
            animationSpec = tween(Theme.MotionInMs, easing = Theme.MotionEasing),
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
                .padding(start = Theme.SidePadding, top = metrics.rowVerticalPad, bottom = metrics.rowVerticalPad),
        ) {
            row.apps.forEachIndexed { index, app ->
                AppCard(
                    app = app,
                    metrics = metrics,
                    // 标题开关为全局(design §2.2):输入源行不显示,自定义标题也一样受它约束。
                    title = if (showTitles && row.kind == RowKind.APPS) (titles[app.packageName] ?: app.label) else null,
                    // 输入源行不画标题但照样占住那一行,行距与应用行一致(应用行 title 非空,走不到这一支)
                    reserveTitleSpace = showTitles,
                    fallbackColor = app.fallbackColor?.let { Color(it) },
                    themed = themedCards,
                    moving = index == carried,
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

/**
 * 输入源行。把每个硬件输入伪装成 [AppEntry](packageName 存输入 id、card=null 走文字回退),
 * 从而与应用行**共用** AppCard / CategoryRow / 整套纵向焦点账本。
 * 枚举为空(非电视、或没有硬件输入、或全部被隐藏)时返回 null —— 这一行不渲染,焦点账本只挂非空行,
 * 与 buildRows 结尾那条不变量同源。标题走本地化字符串;ctx.getString 在 IO 线程可安全调用。
 * HDMI-CEC 父子去重见 [dedupeCec];隐藏 / 改名见 [applyInputPrefs]——[titles] 与卡片标题共用
 * titles.json(key = 输入 id),名字只换卡上文字,不受「卡片标题」开关影响。
 * 多个调谐器只留一张,见 [mergeTuners]。
 */
private fun buildInputRow(ctx: Context, titles: Map<String, String>): Row? {
    val inputs = applyInputPrefs(mergeTuners(dedupeCec(Inputs.load(ctx))), HiddenInputs.read(ctx), titles)
    if (inputs.isEmpty()) return null
    return Row(
        name = ctx.getString(R.string.home_input_row_title),
        apps = inputs.map { AppEntry(packageName = it.id, label = it.label, card = null, isWide = false) },
        kind = RowKind.INPUTS,
    )
}

private fun buildRows(ctx: Context): List<Row> {
    val layout = Layout.read(ctx)
    val needed = layout.flatMap { it.apps }.toSet()
    val all = Apps.load(ctx, needed, withBitmaps = needed, withLabels = needed)
    // **layoutRow 必须在 filter 之前定下来**:下面那个 filter 会整行丢掉空行,
    // 丢掉之后剩下行的下标就不再等于它们在 layout.json 里的下标。
    // 「移除 / 移动位置」写的是 layout.json,拿渲染下标去写就会打在别人那一行上。
    return layout.mapIndexed { layoutIndex, row ->
        Row(name = row.name, icon = row.icon, apps = row.apps.mapNotNull { all[it] }, layoutRow = layoutIndex)
    }.filter { it.apps.isNotEmpty() }
    // ⚠️ 这个 filter 不只是显示意图,**它同时是焦点的不变量**:
    // upTarget/downTarget 指向相邻行的 rowFocus,而 rowFocus 只挂在非空行的卡片上。
    // 哪天想「空行也显示出来」,那些 requester 就会挂空,而 focusProperties 给出非 Default
    // 的 requester 会短路几何搜索 —— 按上/下将变成完全没反应。要改先想清楚这一条。
}
