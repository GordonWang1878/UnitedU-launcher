package com.uniteduone.launcher

import android.content.Context
import android.graphics.BitmapFactory
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
 * 待机由 [MainActivity] 通过 [idle] 传进来:除时钟外全部淡出。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    idle: Boolean,
    menuItems: List<MenuItem>,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    focusNonce: Int,
    revision: Int = 0,
    menuFromGear: Boolean = true,
    showDate: Boolean = true,
    cardsPerRow: Int = 6,
    /** 输入源行开关(design §2,默认关)。开着且真机枚举到硬件输入时,在应用行**上方**
     *  多渲染一行输入源;它以普通行的身份加进纵向焦点账本,种类差异只影响点击行为与行图标。 */
    showInputRow: Boolean = false,
    /** 主题色(选中预设或跟随壁纸解析出的)。accent 给齿轮;highlight 给行标题/光晕/时钟。
     *  默认今日常量,保证未接线的调用点逐位复现今日观感。 */
    accent: Color = Theme.ChampagneGold,
    highlight: Color = Theme.Champagne,
) {
    val ctx = LocalContext.current
    // 卡片档位尺寸:6=当前标定常量原样(零回归),5/8 按跨度守恒推导。见 Theme.cardMetrics。
    val metrics = Theme.cardMetrics(cardsPerRow)
    // 枚举应用 + 解码全部横幅是重活,放到 IO 线程,别拖慢首帧
    // (冷启动实测 2.0–2.3s,Projectivy 是 1.45s)。
    // 用 null 区分「还在加载」和「真的空」,否则每次冷启动和每次退出编辑都会闪一句求救文案
    // revision 变化(换了卡片图、装/卸了应用)时重跑,但 produceState 的 remember 不带 key,
    // 新数据到达前**旧画面原样留着**——不会像 key(revision) 那样先黑一下再重建。
    // showInputRow 也作 key:设置页改了这个开关后 leaveSettings() 会 revision++,
    // 这里本就会重跑;带上它是白纸黑字,不依赖「revision 一定跟着变」这条间接约束。
    val loaded by produceState<List<Row>?>(initialValue = null, ctx, revision, showInputRow) {
        value = withContext(Dispatchers.IO) {
            val appRows = runCatching { buildRows(ctx) }.getOrDefault(emptyList())
            // 输入源行放**最上面**:design §2 把「输入源」当独立顶层类目,置顶与之相符;
            // 且置顶后应用行的相对次序、以及「开机焦点落在最上一行」的直觉都不变。
            // 枚举为空(非电视 / 没有硬件输入)时返回 null,这一行干脆不存在 —— 焦点账本
            // 只认非空行,不会挂空 requester(见 buildRows 结尾那条不变量)。
            val inputRow = if (showInputRow) runCatching { buildInputRow(ctx) }.getOrNull() else null
            if (inputRow != null) listOf(inputRow) + appRows else appRows
        }
    }
    val rows = loaded.orEmpty()
    // 开机后焦点要自己落到第一张卡片上,否则方向键第一下没有反应。
    val firstCard = remember { FocusRequester() }
    // 每行一个 requester,挂在「这一行的目标格」上 —— 用来把焦点**还原到离开前那张卡**。
    val rowFocus = remember(rows.size) { List(rows.size.coerceAtLeast(1)) { FocusRequester() } }
    // 「目标格」只由用户的主动导航更新,还原过程中不更新 ——
    // 否则 Compose 抢先把焦点给了第一张卡,目标就被改写成 0 了。
    // (横向位移由 CategoryRow 自己的 focusedIndex 算,不在这里。)
    val tgtIdx = remember(rows.size) { mutableStateListOf(*Array(rows.size.coerceAtLeast(1)) { 0 }) }
    var tgtRow by remember { mutableStateOf(0) }
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
    fun report(row: Int, idx: Int, got: Boolean) {
        // 齿轮真的拿到焦点 = 这次「关菜单回齿轮」的意图已经兑现,比对立刻作废。
        // 不作废的话它会一直成立到下一次 nonce 递增,**窗口里每一次丢焦点都被送到齿轮**
        // (比如后台某个应用自动更新让某行短一格、焦点所在节点被销毁),
        // 人正站在第三行却突然瞬移到右上角。
        if (got && row == -1) gearNonce = -1
        if (got) focusedCell = row to idx
        else if (focusedCell == row to idx) focusedCell = null
    }
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
    val overflow = metrics.firstCardTop + metrics.rowPitch * activeRowSafe +
        metrics.cardHeight + Theme.BottomKeepout - screenH
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
    LaunchedEffect(focusNonce, rows.size, rows.isEmpty(), menuOpen) {
        // 无论走哪条分支都要把 restoring 放掉,否则用户自己的导航从此更新不了目标
        if (focusNonce == 0 || rows.isEmpty() || menuOpen || focusNonce == gearNonce) {
            restoring = false; return@LaunchedEffect
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
    LaunchedEffect(rows.isEmpty(), loaded != null, focusNonce, focusedCell, menuOpen, restoring, gearNonce) {
        // **菜单开着时让路。**focusedCell 只记录卡片与齿轮,不认识菜单项 ——
        // 菜单一开它就变成 null,看门狗会误判「树里没焦点」并每帧抢着请求,
        // 把菜单自己刚拿到的焦点搅掉,症状是「打开菜单后一项都没高亮、按什么都没反应」。
        if (menuOpen) return@LaunchedEffect
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
        val contentAlpha by animateFloatAsState(
            targetValue = if (idle) 0f else 1f,
            animationSpec = tween(if (idle) 1200 else 400),
            label = "contentAlpha",
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
                .focusProperties { canFocus = !menuOpen }
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
                    highlight = highlight,
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
                .focusProperties { canFocus = !menuOpen },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),   // 复审实测参考 36.2px,17dp 给出 42.2px
        ) {
            // 齿轮同样用 alpha 而不是 AnimatedVisibility:待机时若把节点移除,
            // 恰好停在齿轮上的焦点会被销毁,醒来第一下按键落空。
            GearButton(
                onClick = { onMenuOpenChange(true) },
                accent = accent,
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
            Clock(showDate = showDate, highlight = highlight)
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
    }
}

/**
 * 壁纸层。**住在 MainActivity 的 setContent 里,不在 HomeScreen 里**:
 * 进出编辑界面会把 HomeScreen 整棵拆掉重建,壁纸若跟着走就要每次重新从盘上解一张
 * 1920x1080,期间背景是纯黑——退出编辑时会黑闪一下。
 */
@Composable
fun Wallpaper(ctx: Context) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, ctx) {
        value = withContext(Dispatchers.IO) {
            ensureDefaultWallpaper(ctx)
            val f = listOf(Paths.wallpaper(ctx), Paths.wallpaperPng(ctx)).firstOrNull { it.exists() }
            // RGBA_F16 保留 Ultra HDR gain map,在 HDR 通路下能显示完整亮度范围;
            // decodeScaled 内部会在 F16 解码失败时自动回落到 ARGB_8888
            val decoded = f?.let {
                runCatching {
                    Apps.decodeScaled(it.absolutePath, 1920, 1080, android.graphics.Bitmap.Config.RGBA_F16)
                }.getOrNull()
            }
            // 用户选的壁纸可能是坏的(只读文件头的校验放得过去)。这时文件**存在**,
            // 于是 ensureDefaultWallpaper 每次都直接 return —— 结果是永久黑屏。
            // 解不出来就当没有,回落到 APK 里内置的那张。
            decoded ?: runCatching {
                ctx.assets.open("wallpapers/00-neutral.jpg").use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                }
            }.getOrNull()
        }
    }
    val b = bmp ?: return
    Image(
        bitmap = b.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun CategoryRow(
    row: Row,
    metrics: CardMetrics,
    /** highlight 主题色:行标题文字色 + 卡片呼吸光晕色。 */
    highlight: Color,
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
            RowIcon(row.name, row.kind)
            BasicText(
                text = row.name,
                style = TextStyle(
                fontFamily = Theme.Sans,
                    // 行标题跟主题色走(design §3 四处之一)。金预设下 highlight=#FFF5DC,
                    // 相比今日的纯白 #FFFFFF 是约 5% 的暖移(见任务报告的零回归说明)。
                    color = highlight, fontSize = 15.5.sp, fontWeight = FontWeight.Medium,
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
                    glowColor = highlight,
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
 * 一张壁纸都没有时,把 APK 里内置的那张铺出去。
 * 不做这件事的话,全新安装、清除数据、或外置那份被删,桌面就是永久全黑且无法自救。
 */
private fun ensureDefaultWallpaper(ctx: Context) {
    val dst = Paths.wallpaper(ctx)
    if (dst.exists() || Paths.wallpaperPng(ctx).exists()) return
    if (Paths.baseOrNull(ctx) == null) return
    // 和其它三处写入一样走 tmp → 校验 → rename:直接写的话,复制到一半被杀会留下
    // 截断文件,而它一旦存在就永不重写 → 永久黑底,且这正是「全新安装」的恢复路径。
    val tmp = java.io.File(dst.parentFile, "wallpaper.default.tmp")
    runCatching {
        ctx.assets.open("wallpapers/00-neutral.jpg").use { input ->
            tmp.outputStream().use { out -> input.copyTo(out); out.flush(); out.fd.sync() }
        }
        check(Apps.isDecodableImage(tmp.absolutePath))
        if (!tmp.renameTo(dst)) { dst.delete(); check(tmp.renameTo(dst)) }
    }
    tmp.delete()
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
    return layout.map { (name, pkgs) ->
        Row(name = name, apps = pkgs.mapNotNull { all[it] })
    }.filter { it.apps.isNotEmpty() }
    // ⚠️ 这个 filter 不只是显示意图,**它同时是焦点的不变量**:
    // upTarget/downTarget 指向相邻行的 rowFocus,而 rowFocus 只挂在非空行的卡片上。
    // 哪天想「空行也显示出来」,那些 requester 就会挂空,而 focusProperties 给出非 Default
    // 的 requester 会短路几何搜索 —— 按上/下将变成完全没反应。要改先想清楚这一条。
}
