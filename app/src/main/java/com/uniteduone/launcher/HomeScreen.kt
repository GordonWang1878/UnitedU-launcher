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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首页:壁纸层 + hero 区(0–192dp,恒是壁纸,不叠任何时钟——84 sp 大字时钟已删,spec §2.3 B2)
 * + 卡片行(R52:焦点行卡顶钉在屏幕下部的焦点线上,静止只露行 0)+ 顶栏(gtv 线的 [GtvTopBar]:药丸组靠左 / 时钟字标靠右)。
 * 待机由 [MainActivity] 通过 [idle] 传进来,内容由 [idleContent] 定(Task 3),驱动这里的两个
 * 淡出动画——`contentAlpha`(卡片行 / 渐变 / 顶栏药丸组 / 「新应用」提示)与 `topBarClockAlpha`
 * (顶栏的时钟 + 字标,单独判断,见该 val 自己的注释):
 * - [IdleContent.CLOCK_ONLY](默认):`contentAlpha` 淡出,`topBarClockAlpha` 钉 1——顶栏这行
 *   16sp 小字正是这一档要留住的内容(**Ruling R23**,终审 2026-09-20,撤回 Fix R16 曾经在 hero
 *   区加回的 84 sp [HeroClock]:owner 真机走查后否掉大字时钟——「我的 GTV 就是要尽可能还原 GTV
 *   的那个样子,你加一个大时钟,整个气氛就破坏掉了」——三个候选方案里选了「只留顶栏小时钟」)。
 *   不这样拆开的话 `topBarClockAlpha` 会跟着 `contentAlpha` 一起淡到 0,CLOCK_ONLY 就会和
 *   [IdleContent.BLACK] 长得一模一样,这一档等于白设。
 * - [IdleContent.BLACK]:两个动画一起淡到 0,不叠时钟,与淡出前的唯一差别就是「全黑」——
 *   这条路径不受 R23 影响,和 gtv 线改版之前一样。
 * - [IdleContent.NO_FADE]:`contentAlpha` 恒为 1,`topBarClockAlpha` 循同一判据自然也是 1,
 *   什么都不淡出。
 *
 * [screensaver](M5 spec §1.4)为真 = 自定义屏保:行 / 渐变 / 顶栏一律淡出(「不淡出」也不例外——
 * 照片上不该浮着一排卡片),`topBarClockAlpha` 同样淡到 0——轮播照片上不该再叠一个时钟(见
 * [Screensaver] 顶部 KDoc)。系统屏保不读这里的两个 alpha:[UnitedUDream] 自 **Ruling R26**
 * (2026-09-21,推翻 R9)起画的也是顶栏同款 `ClockWordmark`(恒亮,照片上带淡阴影),不再是
 * 84 sp `HeroClock`——三处待机画面里,首页待机 CLOCK_ONLY 与系统屏保是同一行小字,自定义屏保不叠。
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
    /**
     * 顶栏「设置」药丸按下(R69:打开设置页外壳的第一层,取代原来嵌在首页里的齿轮菜单)。设置外壳住在 MainActivity、
     * 叠在首页之上,首页由 [previewing] 让路、冻结目标;关掉后按冻结的 `tgtPill` 回到药丸(或 MENU 键打开时回到那张卡)。
     */
    onSettings: () -> Unit = {},
    /** R89:顶栏「应用」胶囊 = 打开所有应用页(MainActivity 那一层的整屏浮层,同设置外壳走 [previewing] 让路)。 */
    onApps: () -> Unit = {},
    /** R89:顶栏「输入源」胶囊 = 打开输入源页(同上)。R89 前这一格是屏保按钮(`onScreensaver`),挪进「设置 → 屏保」。 */
    onInputs: () -> Unit = {},
    focusNonce: Int,
    revision: Int = 0,
    showDate: Boolean = true,
    cardsPerRow: Int = 6,
    /** 卡片标题全局开关(design §2)。开着时卡片下方多一行标题,行高随之增加
     *  (见 GtvLayout.titleHeight;main 线的编辑页等未换皮界面走 HomeLayout.titleHeight 同一套公式),
     *  纵向位移沿用同一套自算逻辑。 */
    showTitles: Boolean = false,
    // ~~showInputRow~~(R92,2026-09-27 Gordon):首页不再有输入源行,输入源搬到顶栏「输入源」胶囊打开的页面(InputsPage)。
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
     * - **不可聚焦**:所有 [AppCard] / [GtvTopBar] `canFocus = false` —— 与 `anyOverlay`
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
     * 被搬的那张卡描 accent 边,顶栏下方一行提示(2026-09-23 前在屏幕底部);焦点的目标格就是 [MoveState.pos]——还原效果以它为 key 与目标,
     * 看门狗以它为目标。它**不并进 `covered`**:移动态没有浮层,焦点始终在被搬的卡上,首页的两个焦点效果照常工作,
     * 只是目标换成了它。期间焦点上报不改 `tgtRow`/`tgtIdx`(首页自己的记忆冻结,结束时由 [moveLanding] 一次写入)。
     */
    moving: MoveState? = null,
    /** 移动态结束时焦点该落的那一格(见 [MoveLanding]):还原效果把它写进 `tgtRow`/`tgtIdx`,每个落点只写一次。 */
    moveLanding: MoveLanding? = null,
    /** 这一次组合画出来的行。MainActivity 进移动态时拿最近一份当工作副本。 */
    onRowsShown: (List<Row>) -> Unit = {},
    /**
     * **Ruling R35**:整页位移的**每帧动画值**(dp,≤ 0 表示上移,= 下面 `shift`)上报给 MainActivity,
     * 由它喂给住在 setContent 顶层的壁纸层——壁纸不在这里被位移的 Column 里,要和行走同一根曲线,
     * 只能把动画值举上去。报的是动画的当前值不是目标值,每一帧都报;首页不在组合里时(编辑页替换首页)
     * MainActivity 自己把它归 0。
     */
    onPageShift: (Dp) -> Unit = {},
) {
    val ctx = LocalContext.current
    // gtv 线:卡片尺寸不再由「每行几张」反推,而是旧的 5/6/8 存量档位映射到三个固定尺寸
    // (cardsPerRowToGtvSize)之一,渲染统一读 Theme.gtvCardMetrics——与 main 线的 Theme.cardMetrics 并存。
    val cardSize = cardsPerRowToGtvSize(cardsPerRow)
    val metrics = Theme.gtvCardMetrics(cardSize)
    // **首页内嵌的浮层**:齿轮菜单、长按卡片菜单、修改标题对话框 —— 它们住在首页这棵树里面。
    val anyOverlay = cardMenu != null || renameTarget != null
    // **「首页被盖住了没有」只此一个判据。**内嵌的那三层(`anyOverlay`)之外,M7 T4 起还有
    // 叠在首页之上的整屏浮层([previewing]:选择器 / 导入页,T5 起加设置页)。四者对下面**四处**的
    // 要求完全相同:卡片不可聚焦、齿轮不可聚焦、还原效果让路、看门狗让路。
    // 分开写四遍 `cardMenu != null ||` 迟早漏掉一处,而漏掉的那一处就是「菜单开着时看门狗每帧抢焦点,
    // 菜单里一项都不高亮」(铁律 4 的推论)。合成一个量之后,它同时是那两个效果的 key 与守卫(铁律 6)。
    // (R69 起齿轮菜单不再嵌在首页里:设置页外壳是 MainActivity 那一层的整屏浮层,算在 [previewing] 里;
    //  原来专管「齿轮菜单关了回齿轮」的 gearNonce 随之删掉,回齿轮只靠冻结的 tgtPill〔R89 前叫 tgtGear〕,见它的 KDoc。)
    val covered = anyOverlay || previewing
    // 枚举应用 + 解码全部横幅是重活,放到 IO 线程,别拖慢首帧
    // (冷启动实测 2.0–2.3s,Projectivy 是 1.45s)。
    // 用 null 区分「还在加载」和「真的空」,否则每次冷启动和每次退出编辑都会闪一句求救文案
    // revision 变化(换了卡片图、装/卸了应用)时重跑,但 produceState 的 remember 不带 key,
    // 新数据到达前**旧画面原样留着**——不会像 key(revision) 那样先黑一下再重建。
    // titles.json 与 rows 同一趟 IO 读出,配成一对:标题开关关着时 titles 仍会被读到但不渲染
    // (显示与否只由 showTitles 决定,不进 key——开关切换不必重读数据,只是换一种渲不渲染)。
    // 「手上这份数据是为哪个 revision 算的」。**移除 / 卸载后焦点能不能留在同一行,全靠它**:
    // revision++ 之后新的行数据要过几百毫秒才到,数据落地的那一帧焦点卡的节点被销毁,
    // Compose 会立刻把焦点塞给整棵树第一个可聚焦节点 (0,0) —— 那次上报若不冻结就会把
    // tgtRow/tgtIdx 改写成 0,记忆在被用到之前就没了(铁律 5),焦点静默跳到第一行。
    // 与 EditScreen 的 allFresh 同构:数据不新鲜时冻结目标,新鲜之后再由还原效果送回去。
    var loadedRevision by remember { mutableStateOf(-1) }
    val loaded by produceState<Triple<List<Row>, Map<String, String>, Int>?>(
        initialValue = null, ctx, revision, newAppsSeenAt,
    ) {
        value = withContext(Dispatchers.IO) {
            val rows = runCatching { buildRows(ctx) }.getOrDefault(emptyList())
            val titles = runCatching { Titles.read(ctx) }.getOrDefault(emptyMap())
            // 「新应用」计数:与首页同一趟 IO 算(应用已经枚举过一次)。
            // 基线还没建立(newAppsSeenAt == 0:onCreate 那次基线写盘失败,比如外置存储开机时还没挂上)
            // 就什么都不算新——否则 countNew(ctx, 0, …) 会把整机几十个应用全算成「新」,整个会话都挂着计数
            // (终审 Minor #5)。isNewApp 的纯语义不动(仍是「装机时间 > seenAt 且不在桌面上」),只是不喂 0 进去。
            val newCount = if (newAppsSeenAt == 0L) 0 else runCatching {
                Apps.countNew(ctx, newAppsSeenAt, rows.flatMap { r -> r.apps.map { it.packageName } }.toSet())
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
     * **目标是顶栏哪一颗胶囊(0 设置 / 1 应用 / 2 输入源),还是那一格卡片(-1)**。与 [tgtRow]/[tgtIdx] 同构,是同一条铁律 5
     * 在顶栏上的应用:「目标」与「当前位置」必须分开,而且从浮层打开(或 `ON_PAUSE`)就冻住。
     * **R89(2026-09-27)从布尔 `tgtGear` 扩成列号**:顶栏从「设置 / 屏保」两颗变成「设置 / 应用 / 输入源」三颗,
     * 其中两颗(应用、输入源)会打开浮层,关掉后要回到**打开它的那一颗**——布尔量只记得「回顶栏」,还原时一律
     * 送到设置那颗(R89 之前屏保按钮不开浮层,这个差别从来没暴露过)。「每一个分量都要拆」(铁律 5 原话):
     * 顶栏的列号与卡片的行 / 列同样是目标的一个分量,同样跟着自报的焦点走、同样在还原期间冻结。
     *
     * 为什么不能只靠 `gearNonce`(M7 T4 实测):`gearNonce` 把「关掉之后回齿轮」这个意图
     * **钉在某一个 focusNonce 上**,而浮层链里每一层关掉时都会 `focusNonce++`
     * (关长按菜单、关选择器、关导入页、关设置页)。于是同一个意图在两条路上给出两种结果——
     * 「换壁纸 → 选一张」不 ++、比对成立,「导入图片 → 返回」++ 一次、比对失效、焦点落回卡片。
     * 记成目标就没有这个问题:它由**焦点真的落在哪**更新(铁律 4:只信控件自报),
     * 浮层期间 `restoring` 冻着它,关掉后原样还原,中途 nonce 怎么涨都不影响。
     * 也不是闩(铁律 7):每次焦点落地都是一次全新赋值,没有「只有一条窄路能清」的状态。
     */
    var tgtPill by remember { mutableStateOf(-1) }
    var restoring by remember { mutableStateOf(false) }
    // **谁持有焦点,只信控件自己的上报。**根节点的 onFocusChanged 在「退到后台再回来」
    // 这条路上不会重发,`hasFocus` 会停在过期的 true —— 实测日志说有焦点,截图里
    // 卡片却没有放大也没有光晕(上边缘 777→812、光晕峰值 142→66)。
    // (-1, col) 表示焦点在顶栏 pill 组上(col 0 设置 / 1 应用 / 2 输入源,R89)。
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
        return CardRef(row, idx, r.layoutRow, app.packageName, app.label)
    }
    fun report(row: Int, idx: Int, got: Boolean) {
        // 目标跟着「焦点真的落在哪」走,**还原过程中不更新**——理由与下面卡片那两个目标完全相同:
        // 浮层关掉那一帧 Compose 会抢先把焦点塞给 (0,0),那次上报若不挡住就会把目标从齿轮改成卡片。
        // **数据还没到也不更新**(`loaded != null`):冷启动时卡片一张都还没建出来,整棵树里
        // 唯一可聚焦的就是齿轮,Compose 会把首帧的焦点给它 —— 那不是用户的选择,是「没得选」。
        // 不挡住的话目标被这一下定成齿轮,行数据到达后还原效果反而主动把焦点拽回齿轮,
        // 开机第一屏的焦点就从第一张卡变成了左上角(2026-09-17 冷启动三连实测;gtv 线齿轮药丸组
        // 靠左对齐 CONTENT_KEYLINE,B2-a 裁定,这句话说的是它现在的位置)。
        // 卡片那两个目标不必判:卡片本身就是数据到了才存在,这条件对它们是隐含成立的。
        // **移动态期间也不更新**(与卡片那两个目标同一条,铁律 5「每一个分量」):那时的目标归 MainActivity 的
        // moving.pos 管,首页自己的记忆冻结,结束时由落点(moveLanding)一次写入。
        if (got && !restoring && loaded != null && movingNow == null) tgtPill = if (row == -1) idx else -1
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
    // 顶栏三颗胶囊各一个 requester(R89:设置 / 应用 / 输入源),下标 = 焦点账本里的 col。
    val pillFocus = remember { List(TOP_PILL_COUNT) { FocusRequester() } }
    val gearFocus = pillFocus[0]
    /** 冻结的顶栏目标对应的 requester;越界(理论上不会)退回设置那颗。 */
    fun pillTarget(): FocusRequester = pillFocus.getOrElse(tgtPill) { gearFocus }
    // 哪一行是「当前行」——决定纵向锚定位移;跟着焦点走。Task 9 曾经把 -1 当合法值写进来
    // (药丸组拿到焦点时写入),给 GtvTopBar 的 `collapsed` 参数当「焦点在不在应用行」的信号。
    // Ruling R21(终审 2026-09-20)删掉了顶栏折叠,这个信号没有消费者了——药丸组拿到焦点时
    // 直接写回 0(见下面 GtvTopBar 的 onFocusChange):activeRowSafe 本来就会把负值夹回 0,
    // 数值上与写 -1 完全等价,只是不再需要一个没人读的哨兵值。
    var activeRow by remember { mutableStateOf(0) }

    // 垂直位置自己算,不用 verticalScroll(铁律 1)。
    val activeRowSafe = activeRow.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    // Ruling R52(owner 2026-09-23,推翻 R42 的最小位移):固定焦点线。行 0 静止卡顶 = 焦点线
    // (GtvLayout.focusLineCardTop,屏幕下部,静止只露行 0);焦点在行 n 时整页上移 n × pitch,
    // 焦点行卡顶恒在焦点线上,每换一行走一整行,上下对称。纯派生、无状态:目标只跟 activeRowSafe 走,
    // activeRow 只在卡片 / 药丸真的拿到焦点时改写,浮层 / ON_PAUSE 期间焦点离开卡片不改它,位移随之不动
    // (与 R32/R42 相同的冻结规则)。不进任何效果的 key 或守卫。
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    val anchorTop = GtvLayout.rowsTop(cardSize, showTitles, screenHeightDp).dp
    val shiftTarget = GtvLayout.rowShiftY(activeRowSafe, cardSize, showTitles)
    val shift by animateDpAsState(
        // R32 → R42 → R52:曾钉锚点 120dp(R32)、改最小位移(R42),现在是焦点线(R52)。hero 的空位
        // (现在就是行 0 上方到顶栏之间的壁纸区)仍是下面 Column 的 padding(top)、在 offset 之内,随 shift 一起走。
        targetValue = shiftTarget.dp,
        // Ruling R29(owner 反馈 Round 8):换行时整块内容的纵向平移 = Google TV 的 browse 手势,
        // 逐帧实测是先加速后减速的临界阻尼弹簧(R27 的 tv_easing_browse 是纯硬减速,对不上),
        // 四处位移共用 Theme.browseShiftSpec,依据见 GtvLayout.BROWSE_SPRING_STIFFNESS。
        animationSpec = Theme.browseShiftSpec(),
        label = "rowShift",
    )
    // R35:每帧把动画的当前值举给 MainActivity(壁纸层住在那里)。这里的 shift 本来就在组合阶段被
    // 下面 Column 的 offset(y = shift) 读取,动画期间每帧都重组,SideEffect 每次重组后跑一遍;
    // 值没变时 MainActivity 那颗 mutableStateOf 写入相同值不会触发任何失效。
    SideEffect { onPageShift(shift) }
    // **焦点看门狗。**判据取自真机日志:根节点的 onFocusChanged 里
    //   hasFocus=true && !isFocused  → 某个子节点持有焦点(正常)
    //   hasFocus=true &&  isFocused  → 焦点停在根上,即**没有任何卡片持有**(要补)
    //   hasFocus=false               → 整棵树都没有焦点(要补)
    // 这比原来「在菜单关闭/退出编辑/onResume 这几个时刻盲目补请求」可靠得多:
    // 卡片节点被销毁(某个应用后台更新触发 PACKAGE_* → 那一行短一格)时焦点也会没,
    // 而那一刻不在任何一张「猜得到的时刻」清单里,遥控器就此全死、按 HOME 也回不来。
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
            tgtPill = -1
            tgtRow = landing.pos.row
            if (landing.pos.row in tgtIdx.indices) tgtIdx[landing.pos.row] = landing.pos.col
        }
        // **回齿轮这条路也必须主动请求**,不能像以前那样早退、把它交给看门狗(M7 T4 实测):
        // 看门狗只在「树里一个焦点都没有」时才动手,而它开头要等 3 帧(躲 D-pad 导航的得失间隙)——
        // 浮层关掉后 Compose 的默认恢复就在这几帧里把焦点塞给了 (0,0),看门狗看到 `focusedCell != null`
        // 当场让路,于是「换壁纸回来焦点回齿轮」变成了「落在第一张卡」。卡片那条路一直是对的,
        // 正因为它是这里主动请求的;齿轮只是缺了对称的一半。
        // 目标读 [tgtPill](冻结过的);它是「读的量」不是守卫,与 tgtRow/tgtIdx 同例,不进 key(进了会在每次导航时重跑还原)。
        restoring = true
        var frames = 0
        // 移动态下焦点只去被搬的那张卡,不回齿轮
        if (moveTarget == null && tgtPill >= 0) {
            // 退出条件同样只信控件自报(铁律 2),而且要落在**冻结的那一颗**上(R89:三颗里两颗会开浮层,
            // 关掉要回到打开它的那颗;只判「在顶栏上」的话,Compose 先把焦点给设置那颗就会提前退出)。
            val want = -1 to tgtPill
            while (frames < 60 && focusedCell != want) {
                withFrameNanos { }
                runCatching { pillTarget().requestFocus() }
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
    LaunchedEffect(rows.isEmpty(), loaded != null, focusNonce, focusedCell, covered, restoring) {
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
        // 与还原效果同一判据:冻结过的目标优先。
        // `tgtPill` 不进 key 也不违反铁律 6:它只在焦点真的落下时才变,而那一下必定同时改写
        // `focusedCell`(已经是 key),本效果照样会以新值重启;而且它不是守卫,只决定送去哪儿。
        // 移动态:目标是被搬的那张卡(moveTarget 只决定送去哪儿、不是守卫;它一变还原效果就重启、
        // restoring 随之置真,本效果以 restoring 这个 key 重启让路,不会拿着旧目标跟还原效果抢)。
        val usePill = moveTarget == null && tgtPill >= 0
        val target = when {
            usePill && loaded != null -> pillTarget()
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

        // M7 T6 的「待机演示」覆盖(demoIdle)随两栏设置页一起删掉(R75),这里只剩真实的 idle。
        val effectiveIdle = idle
        val effectiveIdleContent = idleContent
        // 待机用 alpha 淡出,不用 AnimatedVisibility——后者自带裁剪,会把超出屏幕的
        // 第三行整块切掉(实测 MUSIC 行因此始终不可见)。
        // NO_FADE(Task 3):待机时恒 1,卡片/行图标/pill 都不淡出。**自定义屏保例外**(M5 spec §0 / §1.4):
        // 「不淡出」只管待机显示,屏保照样全屏——照片上不能浮着一排卡片,所以 screensaver 为真时一律淡出。
        // 「该淡出了」的谓词只写一份:contentAlpha 与下面的 topBarClockAlpha 都读它(整枝审查
        // 2026-09-22 合并,此前两处各抄了一份同样的表达式)。
        val idleFading = screensaver || (effectiveIdle && effectiveIdleContent != IdleContent.NO_FADE)
        val contentAlpha by animateFloatAsState(
            targetValue = if (idleFading) 0f else 1f,
            animationSpec = tween(if (effectiveIdle) 1200 else 400),
            label = "contentAlpha",
        )
        val surface = androidx.tv.material3.MaterialTheme.colorScheme.surface
        // 首页提示文字的字样:空桌面求救那句与移动态底部提示共用一份(M4b spec §0-10「沿用现有提示文字样式」)
        val hintStyle = TextStyle(
            fontFamily = Theme.Sans,
            color = androidx.tv.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            fontSize = 15.sp,
        )
        // Ruling R24(终审 2026-09-21,owner 真机走查 Round 3):**2D 背景衰减**,取代 R22 的纯横向
        // 渐变加 Round 2 那版跟着行位移走的竖直 scrim。owner 指出 Google 的暗色区域是「右上角一块图,
        // 其余整块黑底」,不是「只从右到左压暗、上下不变」;量参考截图 `docs/screenshots/gtv/01-home-default.jpg`
        // 的 7×8 亮度网格证实形状是**横向衰减 × 纵向衰减的乘积**(推导见 GtvTokens.HeroGradientNear
        // 的 KDoc 与 `docs/WORKLOG.md` 2026-09-21 R24 条目),不是单一方向的线性渐变。
        // 下面画两条独立的纯黑半透明 1D 渐变(这一层管横向,下一层管纵向),Compose 默认的图层
        // over 合成本身就是透光率相乘,不需要手写 2D shader。
        //
        // 铺满全屏(不只是 192dp 的 hero 条):卡片行与左边距里的行图标(R48 前是贴左基准线的行标题)
        // 一路往下到最后一行都是,只压 hero 那一段的话第一行以下依旧没人管。
        // 随 contentAlpha 一起淡出——待机 / 自定义屏保时两层暗色一起消失,只剩干净壁纸,
        // 与 HomeScreen 顶部 KDoc「screensaver 为真时行 / 渐变 / 顶栏一律淡出」说的是同一件事;
        // 两层共读同一个 `contentAlpha`,不会互相错拍。
        // R82(2026-09-24 owner):**暂时拿掉**这层「右上亮、往左下压暗」的背景衰减——现在的首页英雄区左侧没有内容,
        // 照 Google 那样把左侧压黑只会让左半屏显得空着没用。之后 owner 会给新的渐变策略,替换下面这两层即可。
        // 上下移动时的整体压暗(wallpaperAlpha)不受影响。
        // R84(2026-09-24 owner 试做):从上往下「加速」压暗到黑——压暗程度 = 屏高分数的 3 次方
        // (一半高度 12.5%、四分之三 42%、底边 100%):上面的英雄区基本是原壁纸,越往下暗得越快,卡片行落在深色底上。
        // Compose 渐变在相邻 stop 之间线性插值,用 [GtvTokens.HOME_FADE_STOPS] 个等分 stop 逼近曲线(R85 起三段,见 homeFadeAlpha)。
        // 与 R24 一样固定在屏幕坐标、随 contentAlpha 淡出(待机 / 屏保时消失)。
        if (GtvTokens.HOME_FADE_ENABLED) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(contentAlpha)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            *Array(GtvTokens.HOME_FADE_STOPS + 1) { i ->
                                val t = i.toFloat() / GtvTokens.HOME_FADE_STOPS
                                t to GtvTokens.MenuBg.copy(alpha = GtvTokens.homeFadeAlpha(t))
                            },
                        ),
                    ),
            )
        }
        if (GtvTokens.HERO_GRADIENT_ENABLED) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(contentAlpha)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            0f to GtvTokens.HeroGradientNear,
                            GtvTokens.HeroGradientHPlateau to GtvTokens.HeroGradientNear,
                            GtvTokens.HeroGradientHFadeEnd to GtvTokens.HeroGradientFar,
                        ),
                    ),
            )
            // 纵向的一半(R24 新增)。**固定在屏幕坐标上,不读 anchorTop/shift/activeRow 里任何一个**——
            // 这是与 Round 2 那版 scrimTop 竖直 scrim 的关键区别:Google 的暗色窗口不随内容行的焦点
            // 滚动而移动,行位移只搬内容,不搬背景;所以这里改用默认(无 startY/endY)的
            // `Brush.verticalGradient`,两个 stop 的分数直接对应这个 `fillMaxSize()` Box 自身的实际
            // 高度——完全不需要 Round 2 那套「转 px、算 scrimTop」的机制,那套机制本身正是这次删掉的
            // 东西(它在 scrimTop 为负时会在屏幕底部露出硬边,详见 `docs/WORKLOG.md` Round 2 条目里
            // 「measure 先于 offset」的完整推导——那次的教训移到那边存档,不再在这里为一段已删除的
            // 代码重复解释它当年为什么错)。
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(contentAlpha)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            GtvTokens.HeroGradientVFadeStart to GtvTokens.HeroGradientFar,
                            GtvTokens.HeroGradientVPlateau to GtvTokens.HeroGradientNear,
                        ),
                    ),
            )
        }

        // hero 区(0–192dp,GtvLayout.HERO_HEIGHT)恒是壁纸(spec §3 B3):84 sp 大字时钟已删
        // (spec §2.3 B2),这里什么都不画,壁纸直接透出来——待机时也一样。
        //
        // **Ruling R23(终审 2026-09-20,撤回 Fix R16)**:R16 曾在待机 CLOCK_ONLY 档于这里淡入一份
        // 84 sp 的 [HeroClock](理由是顶栏那行 16sp 小字随 contentAlpha 一起淡出后,CLOCK_ONLY
        // 会和 BLACK 长得一模一样)。owner 真机走查后否掉的不是这个判断,是**大字时钟本身**——
        // 「我的 GTV 就是要尽可能还原 GTV 的那个样子,你加一个大时钟,整个气氛就破坏掉了」,
        // 三个候选方案里选了「只留顶栏小时钟」。待机 CLOCK_ONLY 因此不淡出的是顶栏自己的时钟 +
        // 字标(见下方 `topBarClockAlpha`),不是在 hero 区另画一份;hero 区从此不再需要关心
        // 待机状态。

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
                // R32:offset 在 padding 之外——padding(top = rowsTop,R52 焦点线)就是 hero 的空间,它必须
                // 随 shift 一起走(整页位移),两者顺序不能对调。
                .offset(y = shift)
                .padding(top = anchorTop),
            // gtv 线:行外间距改读 GtvLayout(Task 9b)——之前留读 HomeLayout.ROW_GAP(20dp)是
            // 每行 26.5dp 纵向漂移的来源之一(与 rowPitch() 假设的 ROW_GAP 对不上,见 GtvLayoutTest)。
            verticalArrangement = Arrangement.spacedBy(GtvLayout.ROW_GAP.dp),
        ) {
            // 配置里的应用一个都装不到时,屏幕上只剩时钟和齿轮,看着像坏了。
            // 给一句话告诉用户怎么自救(实测:此时齿轮菜单仍可用)。
            // **被整屏浮层盖着时不画**(M7 T10):这句话说的是「齿轮已选中」,而浮层开着时齿轮
            // 不可聚焦、焦点在浮层里——它是一句假话;首次引导的 α 0.85 遮罩下它还正好横在
            // 语言按钮与「继续」之间(模拟器截图实测)。previewing = false 时行为不变。
            if (loaded != null && rows.isEmpty() && !previewing) {
                BasicText(
                    text = stringResource(R.string.home_empty_apps_hint),
                    // gtv 线内读一个常量(Fix 5,终审 2026-09-20):这个文件里以前 Theme.SidePadding
                    // 与 GtvLayout.CONTENT_KEYLINE 两个名字都指同一条 58dp 基准线,值相同、名字不同,
                    // 是与纵向 26.5dp 漂移同一类的命名漂移,统一改读后者。
                    modifier = Modifier.padding(start = GtvLayout.CONTENT_KEYLINE.dp),
                    style = hintStyle,
                )
            }
            // Ruling R43 → R48:哪一行的行图标是「焦点行」近白态(R48 前是行标题大白态)。焦点在顶栏药丸组
            // (tgtPill ≥ 0)→ 没有焦点行(-1);否则就是 activeRowSafe(整页位移用的同一个量,图标与位移同时变)。
            // 两者都只在卡片 / 药丸真的拿到焦点时改写、浮层 / ON_PAUSE 期间冻结,所以图标在浮层与退后台时
            // 保持最后状态。纯派生,不写任何状态,不进任何效果的 key 或守卫(铁律 3–7 一处不动)。
            val iconFocusRow = if (tgtPill >= 0) -1 else activeRowSafe
            // 「有 N 个新应用」提示在不在(顶栏下那一行小字,见下方顶栏 Column)。在的话 R53 淡出带的零点
            // 下移到提示底边(GtvLayout.NEW_APPS_HINT_BOTTOM),换行动画里扫过去的行不与提示字叠在一起。
            val newAppsShown = (loaded?.third ?: 0) > 0
            rows.forEachIndexed { rowIndex, row ->
                CategoryRow(
                    row = row,
                    metrics = metrics,
                    cardSize = cardSize,
                    showTitles = showTitles,
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
                    // R30 + R52:焦点落到本行会不会让整页位移——看位移目标会不会变。R52 下换行必位移,
                    // 从顶栏落到行 0 不位移(两者位移都是 0),放大不该等一个不存在的位移。
                    landingShiftsPage = rowIndex != activeRowSafe &&
                        GtvLayout.rowShiftY(rowIndex, cardSize, showTitles) != shiftTarget,
                    // R48:行图标近白 ⇔ 本行是焦点行。只读焦点账本、不写(见 iconFocusRow)。
                    isFocusRow = rowIndex == iconFocusRow,
                    // R53:顶栏下淡出。本行当前卡顶 = 静止卡顶(GtvLayout.restCardTop,焦点线 + rowIndex × pitch)
                    // + 动画中的 shift;lambda 在 graphicsLayer 里(绘制阶段)才读 shift,位移每帧只重放图层,
                    // 不为此重组本行。**焦点行(rowIndex == activeRowSafe)恒 1**(GtvLayout.homeRowAlpha):按住上键
                    // 连发时位移追不上焦点,刚拿到焦点的行卡顶还在顶栏下,曾淡到 0 达 130–190 ms(柔光也被离屏层裁掉)。
                    rowAlpha = run {
                        val isActiveRow = rowIndex == activeRowSafe
                        val restTop = GtvLayout.restCardTop(rowIndex, cardSize, showTitles, screenHeightDp)
                        ({ GtvLayout.homeRowAlpha(isActiveRow, restTop + shift.value, clearOfNewAppsHint = newAppsShown) })
                    },
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

        // 顶栏(spec §4):gtv 新顶栏,药丸组靠左对齐 CONTENT_KEYLINE + 右侧时钟/字标,铺满顶部;
        // 其下的「有 N 个新应用」跟着药丸组左对齐(原来贴右上 pill,随药丸组一起搬到左边)。
        // 不随 shift 走。节点只淡出不移除:移除会连带销毁停在按钮上的焦点,醒来第一下按键落空。
        //
        // **药丸组 / 「新应用」提示随 contentAlpha 淡出,时钟 + 字标另算(Ruling R23,终审
        // 2026-09-20)**:gtv 线把首页大字时钟挪进了顶栏这行 16sp 小字之后,`IdleContent.CLOCK_ONLY`
        // 待机档唯一的意义就是「这行小字仍然看得见」——owner 真机走查否掉了 Fix R16 加回大字时钟
        // 的方案(「气氛就破坏掉了」),选了「只留顶栏小时钟」。所以这一行不能再跟着药丸组一起
        // 淡到 0,否则 CLOCK_ONLY 又会和 BLACK 长得一模一样。BLACK / 非待机 / 自定义屏保三种情形下
        // topBarClockAlpha 与 contentAlpha 取值相同(该淡就淡,BLACK 路径不变);只在「未在自定义
        // 屏保、真待机、且档位是 CLOCK_ONLY」这一种情形下钉 1——与 contentAlpha 同一份 idleFading
        // 判据(上面 contentAlpha 处定义的那一份),只是多一层例外,不是另起一套逻辑,tween 时长也与 contentAlpha 一致。
        val topBarClockAlpha by animateFloatAsState(
            targetValue = when {
                !screensaver && effectiveIdle && effectiveIdleContent == IdleContent.CLOCK_ONLY -> 1f
                idleFading -> 0f
                else -> 1f
            },
            animationSpec = tween(if (effectiveIdle) 1200 else 400),
            label = "topBarClockAlpha",
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            GtvTopBar(
                pillFocusRequesters = pillFocus,
                canFocus = !covered,
                rowsEmpty = rows.isEmpty(),
                downTarget = rowFocus.getOrNull(tgtRow.coerceIn(0, rowFocus.lastIndex)),
                pillAlpha = contentAlpha,
                clockAlpha = topBarClockAlpha,
                showDate = showDate,
                // ui-pending #8:待机(含设置页待机演示)时两层压暗渐变随 contentAlpha 淡掉、壁纸原样露出,
                // 亮壁纸上 accent 小字对比度只有 1.4:1——给时钟字标加紧贴字形的深阴影(ClockWordmark 的
                // strongShadow,不是系统屏保照片上那档淡阴影)。阴影 alpha 跟着 1 − contentAlpha 走:压暗渐变
                // 淡掉多少、阴影就补上多少,进出待机随同一个 tween 渐变,不瞬切;NO_FADE 档渐变不淡,阴影也不出。
                clockShadowAlpha = 1f - contentAlpha,
                onSettings = onSettings,
                onApps = onApps,
                onInputs = onInputs,
                onFocusChange = { col, got ->
                    report(-1, col, got)
                    // 与下面卡片行「got 时 activeRow = rowIndex」对称的另一半:药丸组拿到焦点也要
                    // 认领 activeRow,否则它会停在离开前那一行的值上,与焦点实际所在的位置
                    // (顶栏,不是任何一行)对不上。
                    // 安全性:药丸组只能从第 0 行 UP 到达(CategoryRow 的 upTarget 只有 rowIndex==0
                    // 才指向 gearFocus),这一刻 activeRow 必然已经是 0——写成 0 只是重申当前值,
                    // activeRowSafe/rowShiftY 都不会因此变化,不会让卡片行跟着抖一下。
                    // (Ruling R21 之前这里写的是 -1,专给已删掉的顶栏折叠动画当信号;
                    // 折叠没了,-1 这个哨兵值没有消费者,改回语义更直接的 0。)
                    if (got) activeRow = 0
                },
            )
            val newCount = loaded?.third ?: 0
            if (newCount > 0) {
                BasicText(
                    text = stringResource(R.string.home_new_apps, newCount),
                    modifier = Modifier
                        .padding(start = GtvLayout.CONTENT_KEYLINE.dp, top = GtvLayout.NEW_APPS_HINT_GAP.dp)
                        .alpha(contentAlpha),
                    style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(
                        color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }

        // 移动态提示(M4b spec §0-10:视觉只加描边与这一行)。字样沿用首页提示文字;垫一层 surface α0.8 的胶囊底。
        // **2026-09-23 从屏幕底部挪到顶栏下方**(R52 连带):R52 把焦点行钉在屏幕下部,焦点行卡底 ≈ 500 dp、
        // 放大后 ≈ 508,原来「贴底 28 dp」的提示(≈ 484–512)正好压在被搬的那张卡上。顶栏下这条带
        // (GtvLayout.MOVE_HINT_TOP)上方的行已按 R53 淡出,焦点行恒在焦点线,不会与提示相交。
        if (moving != null) {
            BasicText(
                text = stringResource(R.string.home_move_hint),
                style = hintStyle,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = GtvLayout.MOVE_HINT_TOP.dp)
                    .background(surface.copy(alpha = 0.8f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
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
                // gtv 线 Task 8:左半 banner 就是这张卡当前画的那个 AppEntry,按行列坐标原样取,
                // 不用另起一份按 pkg 查的 map——rows 已经是这次组合画出来的那一份,行列必然对得上。
                app = rows.getOrNull(cm.rowIndex)?.apps?.getOrNull(cm.colIndex),
            )
        }

        // 「修改标题」对话框(Task 5,spec §3)。同样嵌在首页里而不是替换首页,理由同上。
        val rt = renameTarget
        if (rt != null) {
            TitleDialog(
                key = rt.pkg,
                current = titles[rt.pkg] ?: "",
                heading = stringResource(R.string.title_dialog_title),
                hint = stringResource(R.string.title_dialog_hint),
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
    /** gtv 线的卡片档位(Task 3);横向位移公式 [GtvLayout.rowShiftX] 按它算 pitch。 */
    cardSize: GtvCardSize,
    /** 卡片标题全局开关 + 自定义标题表(design §2)。 */
    showTitles: Boolean,
    titles: Map<String, String>,
    firstCard: FocusRequester?,
    rowRequester: FocusRequester?,
    isLastRow: Boolean,
    upTarget: FocusRequester?,
    downTarget: FocusRequester?,
    targetIndex: Int,
    /** 移动态里被搬的卡在本行第几列;-1 = 不在本行(或不在移动态)。 */
    carried: Int = -1,
    /** R30 + R52:焦点落到本行会不会改变整页纵向位移的目标(HomeScreen 用 `rowShiftY` 预先算好)。
     *  R52 下换行必位移;从顶栏落到行 0 不位移,所以仍按「目标变不变」判,不只看「是不是当前行」。 */
    landingShiftsPage: Boolean,
    /** R48:本行是不是焦点行(焦点在本行卡片上);是则行图标近白,否则灰(不缩放)。 */
    isFocusRow: Boolean,
    /** R53:本行(卡片 + 行图标)的 alpha,只在绘制阶段读(见 [GtvLayout.topFadeAlpha])。 */
    rowAlpha: () -> Float,
    onFocusChange: (Int, Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    // Ruling R48(2026-09-22,owner 看效果图后选 A2):首页取消行标题,行图标留在左边距里当焦点提示。
    // 焦点行 accent、其余行 accent 压暗(R80,原 R46 灰 ↔ 近白),与整页位移同一根弹簧(R47 的 Theme.rowIconFocusSpec),
    // **不缩放**。进度量 iconFocus 只在绘制阶段读(RowIcon 的 tint lambda),动画每帧不重组本行、
    // 不改布局;不进焦点账本、不碰任何 FocusRequester / 看门狗(铁律 3–7)。
    val iconFocus by animateFloatAsState(
        targetValue = if (isFocusRow) 1f else 0f,
        animationSpec = Theme.rowIconFocusSpec(),
        label = "rowIconFocus",
    )
    // R80(2026-09-24 owner):行图标跟主题色走——焦点行 = accent,其余行 = accent 的 55% 透明(暗底上压暗),
    // 取代 R46 的灰 ↔ 近白两色。编辑页的行图标本来就是 accent,两处一致了。
    val accent = LocalThemeColors.current.accent
    val iconColor = { androidx.compose.ui.graphics.lerp(accent.copy(alpha = GtvLayout.ROW_ICON_IDLE_ALPHA), accent, iconFocus) }
    // 记住聚焦在第几张,用来算这一行的横向位移(行放得下就不动、放不下才移够用的距离,
    // 见下面 GtvLayout.rowShiftX 的 KDoc——R20)
    var focusedIndex by remember { mutableStateOf(0) }
    // Ruling R30(owner 反馈 Round 8):最近一次落到本行的焦点有没有带着行位移(纵向切行或
    // 横向 rowShiftX 目标值变了)。在焦点回调里与 focusedIndex 同一个事件写入,AppCard 下一次
    // 重组时 focused 与它一起生效,gtvAppFocusFrame 据此决定放大要不要等位移。它只是给绘制动画
    // 选 spec 用的旁路信号,不进焦点账本、不被任何效果读(铁律 3–7 的链条一处不动)。
    var landedWithShift by remember { mutableStateOf(false) }
    // R48:没有标题行了,本行 = 行图标(左边距)+ 卡片行。Box 里先画图标、再画卡片行:行放不下、
    // 整行左移(rowShiftX)时卡片从图标上面滑过、把它盖住,而不是图标压在卡片内容上。
    // R53:整行(图标 + 卡片)一起按卡顶位置在顶栏下淡出;graphicsLayer 的 block 在绘制阶段读 rowAlpha,
    // 不改布局、不碰焦点(焦点行的 rowAlpha 恒 1,见 HomeScreen / GtvLayout.homeRowAlpha)。
    Box(Modifier.graphicsLayer { alpha = rowAlpha() }) {
        // 水平中心 x = CONTENT_KEYLINE / 2(29 dp),纵向中心 = 卡片中心(上侧描边留白 + 半个卡高;
        // 卡片标题开着时标题在卡下方,不参与居中——效果图 A2 对齐的是卡片本身)。
        // 行名由 RowIcon 的 contentDescription 带给无障碍服务。图标不随 xShift 走。
        RowIcon(
            row.name, row.icon,
            tint = iconColor,
            boxSize = GtvLayout.ROW_ICON_SIZE.dp,
            modifier = Modifier.padding(
                start = ((GtvLayout.CONTENT_KEYLINE - GtvLayout.ROW_ICON_SIZE) / 2f).dp,
                top = metrics.rowVerticalPad + metrics.cardHeight / 2 - (GtvLayout.ROW_ICON_SIZE / 2f).dp,
            ),
        )
        // **绝不能用 LazyRow / horizontalScroll**:任何可滚动容器都会挡住纵向焦点外出。
        // 2026-09-11 真机实测:按上/下时 Compose 找不到候选,平台的 View 级焦点导航接手,
        // 把整棵树的焦点清空(日志里是 ROOT hasFocus=false → 再 isFocused=true),
        // 之后按什么都没反应 —— 三行的桌面实际退化成只有第一行能用。
        // LazyRow 加 focusGroup、普通 Row 套 horizontalScroll,两种都试过,同样断。
        // 所以横向位移和上面纵向那段一样自己算:只有「不可滚动的 Row」不挡焦点。
        // Ruling R20(终审 2026-09-20,owner 真机走查后推翻 Task 7 的「焦点卡永远钉左基准线」):
        // 那条规则是照搬 Google 无边界推荐流的模型,对我们「常见 5 张卡、一行本来就装得下」的
        // 有限应用列表不成立——从第一次按右键就整行左移一个 pitch,会把第 1 张卡推出屏幕左侧、
        // 右边空出约 230dp 死白。现在改回「行完全可见就不动,只在焦点卡右缘会超出屏幕右侧可视
        // 区域时才左移刚好这么多」(pre-Task-7 的规则,数值出处与推导见 GtvLayout.rowShiftX 的
        // KDoc,不要再往回改)。超出屏幕右缘的卡依旧不砍宽度,靠 wrapContentWidth(unbounded)
        // + 屏幕本身的绘制裁切自然露出一截、仍可聚焦(行尾 peeking,见下面 Row 的注释)。
        // 行可能变短(卸载了应用),索引留在旧值上会让 rowShiftX 按一个不存在的列数左移
        val focused = focusedIndex.coerceIn(0, row.apps.lastIndex.coerceAtLeast(0))
        val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
        val xShift by animateDpAsState(
            targetValue = GtvLayout.rowShiftX(focused, cardSize, screenWidthDp).dp,
            // Ruling R29(owner 反馈 Round 8):行内横向平移与上面的换行纵向平移是同一个 browse
            // 手势的两个方向,同一根弹簧(见 Theme.browseShiftSpec 的 KDoc)。
            animationSpec = Theme.browseShiftSpec(),
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
                .padding(start = GtvLayout.CONTENT_KEYLINE.dp, top = metrics.rowVerticalPad, bottom = metrics.rowVerticalPad),
        ) {
            row.apps.forEachIndexed { index, app ->
                AppCard(
                    app = app,
                    metrics = metrics,
                    // 标题开关为全局(design §2.2),自定义标题也一样受它约束。
                    title = if (showTitles) (titles[app.packageName] ?: app.label) else null,
                    fallbackColor = app.fallbackColor?.let { Color(it) },
                    moving = index == carried,
                    focusAfterShift = landedWithShift,
                    onClick = {
                        if (!Apps.launch(ctx, app.packageName)) {
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
                    onFocusChange = { got ->
                        if (got) {
                            // R30:判「这次落焦会不会让行动」——lambda 捕获的 focused / landingShiftsPage /
                            // screenWidthDp 都是上一次重组的值,正好是这次焦点变化**之前**的状态。
                            // 纵向:落上来整页位移目标会变(R42);横向:目标 rowShiftX 变了才算滑行
                            // (行放得下时左右移不动,与 R20 的规则一致,不延迟)。
                            val xBefore = GtvLayout.rowShiftX(focused, cardSize, screenWidthDp)
                            val xAfter = GtvLayout.rowShiftX(index, cardSize, screenWidthDp)
                            landedWithShift = landingShiftsPage || xAfter != xBefore
                            focusedIndex = index
                        }
                        onFocusChange(index, got)
                    },
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
