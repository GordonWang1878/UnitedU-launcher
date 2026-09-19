package com.uniteduone.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 编辑分栏(原「编辑桌面」)。范围按设计文档 Q5:只管应用的**进出与行内排序**,外加换卡片图。
 * 三行的名字是固定的,不在这里改。
 */
@Composable
fun EditScreen(
    /**
     * 「换卡片图」:(layout.json 行号, 包名)。**选择器会替换本页**(M7 终审 C1):选择器开着时本页不在组合里,
     * 关掉后整页重建、所有 `remember` 归零——调用方必须把这两个值原样当 [initialTarget] 喂回来,
     * 焦点才回得到这张卡。行号就是本页 `rows` 的下标(本页按 `Layout.read` 原样排,与 layout.json 一致)。
     */
    onPickIcon: (row: Int, pkg: String) -> Unit,
    onExit: () -> Unit,
    focusNonce: Int = 0,
    revision: Int = 0,
    cardsPerRow: Int = 6,
    /** 卡片标题全局开关(design §2):编辑页与首页共用同一份 titles.json,标题同样显示——
     *  编辑时看得见名字更好认。 */
    showTitles: Boolean = false,
    /**
     * 首页长按菜单「移动位置」、或本页「换卡片图」的选择器关掉之后(见 [onPickIcon])带进来的
     * **(layout.json 行号, 包名)**;null = 正常进入,不定位。
     *
     * 用包名而不是列号:首页那边 `buildRows` 把装不到的包丢掉了,编辑页这边是
     * `Layout.read` 的原样(缺的包也占一格,画成暗红的「未安装」)—— 两边的列号对不上。
     * 包名在一行里唯一(`Layout.read` 做过 distinct),按它查才落在同一张卡上。
     */
    initialTarget: Pair<Int, String>? = null,
) {
    val ctx = LocalContext.current
    // 与首页同一套卡片档位尺寸,编辑页的卡片才会和首页一样大。见 Theme.cardMetrics。
    val metrics = Theme.cardMetrics(cardsPerRow)
    // 自定义标题表,revision 变化(改过标题)时重读;与首页同一份数据源。
    val titles by produceState(emptyMap<String, String>(), revision) {
        value = withContext(Dispatchers.IO) { Titles.read(ctx) }
    }
    var rows by remember { mutableStateOf(Layout.read(ctx).map { it.first to it.second.toMutableList() }) }
    var picking by remember { mutableStateOf<Int?>(null) }        // 正在给第几行加应用
    var acting by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (行, 位置) 的操作菜单
    val needed = remember(rows) { rows.flatMap { it.second }.toSet() }
    // 和 HomeScreen 一样挪到 IO:同步解码 11 张 banner 会让进编辑界面卡一下
    // 用 null 区分「还在加载」和「加载失败/真的空」—— 与首页同一做法。
    // 用 emptyMap 当初值时,失败结果与初值**结构相等**,mutableStateOf 不触发重组、
    // 看门狗不重启,而它的守卫正是 `all.isEmpty()`:于是初始焦点一次都不会请求,
    // 进编辑界面后按什么都没反应(而进编辑界面恰恰是首页空掉后的自救动作)。
    // 连同「这份数据是给哪套 needed 算的」一起存:onPick 之后 needed 先变、all 还是旧值,
    // 新包在旧 map 里查不到 —— 直接渲染成暗红的「未安装」,像是加错了。
    // 只有数据与当前 needed 对得上时,「查不到」才真的等于「没装」。
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
    val allFresh = loadedFor?.first == needed
    // 每个新界面都必须显式给初始焦点,否则遥控器进来后按什么都没反应
    // (2026-09-10 实测:编辑界面漏了这一步,后续所有按键全部落空)。
    // 每行一个 focus requester:改完某一行后把焦点还给那一行,
    // 否则列表重组会让焦点掉回第一行,连着操作同一行时很别扭。
    val rowFocus = remember(rows.size) { List(rows.size.coerceAtLeast(1)) { FocusRequester() } }
    // 「当前行」跟着真实焦点走,给看门狗用(焦点没了时回到最后待过的那一行)。
    var focusRow by remember { mutableStateOf(0) }
    // 「目标行」只由动作与取消路径设置,**绝不跟着焦点事件走**。
    // 上一版让 focusRow 一身二职,结果:onDismiss 明明设了目标行 1,
    // Compose 抢先把焦点给了 (0,0),那次上报把 focusRow 改回 0,
    // 重定位于是去还原第 0 行的第 0 格 —— 焦点静默跳到 VIDEO 行,
    // 而下一步「移出」就打在别的行上(复审因此误删了一个应用)。
    // 这是列号那边早就拆过的同一个坑,行号这边漏了。
    var retargetRow by remember { mutableStateOf(0) }
    // 与首页的 restoring 同构:重定位期间冻结目标,平时让目标跟着导航走。
    // 没有它的话 focusTarget 不敢跟导航,看门狗就只能把焦点还到「上次菜单动作留下的那一格」。
    // 用「安排了几次 / 完成了几次」的比对表达,而不是一次性布尔闩(铁律 7):
    // 闩只有一条路写回 false,新增任何早退分支就会永久卡住,而它是看门狗的第一道守卫。
    // 派生量没人需要去清,而且守卫读的量本身就是 key。
    var retargetTick by remember { mutableStateOf(0) }
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
        focusTarget[ri.coerceIn(0, focusTarget.lastIndex)] = col
    }

    // 「移动位置」兜底:从首页带着 (layout 行号, 包名) 进来,数据到位后定位一次。
    // 用「已应用的目标」比对,不用一次性布尔闩(铁律 7):同一个 initialTarget 只应用一次,
    // 换了新值自然再应用。本页被「换卡片图」的选择器替换、关掉后重建时,appliedTarget 随整页
    // 归零,同一颗种子会再应用一次——这正是要的:焦点回到刚才换图的那张卡(M7 终审 C1)。
    var appliedTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(initialTarget, all) {
        val t = initialTarget ?: return@LaunchedEffect
        if (all == null || appliedTarget == t) return@LaunchedEffect
        appliedTarget = t
        val ri = t.first.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
        // **按包名查列号**,不信任首页传来的渲染列号(两边的行内容不一样,见 initialTarget 的 KDoc)。
        // 查不到(那一行刚被别处改过)就退到行首,至少落在正确的那一行上,绝不乱指一张卡。
        val ci = rows.getOrNull(ri)?.second?.indexOf(t.second) ?: -1
        if (ci >= 0) retarget(ri, ci) else retarget(ri, 0)
    }

    // 与首页同一套:**从 ON_PAUSE 就冻结**、ON_RESUME 再显式恢复。
    // 编辑界面原本两半都没有,只靠看门狗,而看门狗第一行是「已经有焦点就返回」——
    // 从 X-plore 换完图回来时 Compose 已经抢先把焦点给了 (0,0),恢复被自己的守卫吞掉。
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> retargetTick++
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    retarget(focusRow, focusTarget.getOrElse(focusRow) { 0 })
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
    // 看门狗不会重启**,编辑界面里只剩返回键能用。
    LaunchedEffect(focusNonce, all, focusedCell, picking, acting, retargeting) {
        if (retargeting) return@LaunchedEffect
        if (picking != null || acting != null) return@LaunchedEffect
        if (focusedCell != null) return@LaunchedEffect
        if (all == null && rows.any { it.second.isNotEmpty() }) return@LaunchedEffect
        repeat(3) { withFrameNanos {} }
        if (focusedCell != null) return@LaunchedEffect
        var frames = 0
        while (focusedCell == null && frames < 60) {
            withFrameNanos { }
            rowFocus.getOrNull(focusRow.coerceIn(0, rowFocus.lastIndex))
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
    LaunchedEffect(retargetTick) {
        if (retargetTick == 0) return@LaunchedEffect
        val ri = retargetRow.coerceIn(0, rowFocus.lastIndex)
        // 夹到 pkgs.size(**含行尾加号那一格**),与下面的挂点用同一个夹法。
        // 少了这一致性:目标被设成加号那一格,而挂点只夹到 lastIndex、加号只在空行时接 requester,
        // 于是焦点只能送到最后一张卡,判据恒不成立、循环跑满 60 帧,每帧把焦点拽回去。
        val cap = rows.getOrNull(ri)?.second?.size ?: 0
        val want = ri to (focusTarget.getOrNull(ri) ?: 0).coerceIn(0, cap)
    
    // retargeting 是派生量(retargetTick != retargetDone),由 retarget() 递增 tick、
    // 本效果末尾把 done 追平。没有需要清除的闩,新增早退分支也不会把它卡住 —— 最多晚一拍追平。
        var frames = 0
        while (frames < 60 && focusedCell != want) {
            withFrameNanos { }
            runCatching { rowFocus[ri].requestFocus() }
            frames++
        }
        retargetDone = retargetTick
    }

    val scope = rememberCoroutineScope()
    fun persist() {
        val snapshot = rows.map { it.first to it.second.toList() }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Layout.write(ctx, snapshot) }
            if (!ok) android.widget.Toast.makeText(
                ctx, ctx.getString(R.string.edit_toast_order_not_saved), android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    // 返回键走 OnBackPressedDispatcher。用 onKeyEvent 有两个问题:预测式返回启用后
    // BACK 不再作为按键事件下发;而且焦点不在浮层里时(比如「没有可添加的应用了」——
    // 那个对话框里一个可聚焦节点都没有)按返回会**一步退出整个编辑界面**。
    androidx.activity.compose.BackHandler {
        // 取消 = 什么都没做,焦点必须留在原来那张卡上。这三条路原本根本没有安排重定位,
        // 于是 Compose 的默认恢复把焦点丢到第一行第一张。
        val a = acting; val p = picking
        if (a != null || p != null) {
            val ri = a?.first ?: p ?: 0
            picking = null; acting = null
            retarget(ri, a?.second ?: rows[ri].second.size)
        } else onExit()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Theme.EditScreenBackground),
    ) {
        // 必须能滚:三行按首页的尺寸合计高于屏幕,不滚的话 Column 会把最后一行压扁
        // (实测 MUSIC 行的卡片被压成一条)。这里和首页不同,编辑界面本来就该能滚。
        val overlayOpen = picking != null || acting != null
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .focusProperties { canFocus = !overlayOpen }
                .padding(top = 40.dp, bottom = 40.dp)
        ) {
            BasicText(
                text = stringResource(R.string.edit_title),
                modifier = Modifier.padding(start = Theme.SidePadding, bottom = 4.dp),
                style = TextStyle(fontFamily = Theme.Sans, color = LocalThemeColors.current.highlight, fontSize = 20.sp),
            )
            BasicText(
                text = stringResource(R.string.edit_hint),
                modifier = Modifier.padding(start = Theme.SidePadding, bottom = 18.dp),
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.SecondaryText, fontSize = 12.sp),
            )
            rows.forEachIndexed { ri, (name, pkgs) ->
                Column(
                    Modifier.padding(bottom = Theme.EditRowSpacing),
                    verticalArrangement = Arrangement.spacedBy(Theme.EditRowTitleGap),
                ) {
                    BasicText(
                        text = name,
                        modifier = Modifier.padding(start = Theme.SidePadding),
                        style = TextStyle(fontFamily = Theme.Sans, color = Color.White, fontSize = 13.sp),
                    )
                    // 同首页:**不能用 LazyRow**,可滚动容器会挡住纵向焦点外出,
                    // 表现为「进编辑界面后按下键焦点就没了,之后按什么都没反应」。
                    // 横向位移自己算(把行尾的加号也算成一格)。
                    val fi = rowFocused.getOrElse(ri) { 0 }.coerceIn(0, pkgs.size)
                    val right = Theme.SidePadding + metrics.cardWidth * (fi + 1) + metrics.cardSpacing * fi
                    val over = right + Theme.SidePadding - LocalConfiguration.current.screenWidthDp.dp
                    val dx by animateDpAsState(if (over > 0.dp) -over else 0.dp, label = "editRowX")
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
                            // requester 挂在**这一行当前聚焦的那一格**上,不是永远挂在第 0 格:
                            // 否则「往右移一位」之后焦点回到行首,把一张卡挪三位要重走三遍。
                            // 目标越过末卡(= 指向行尾加号)时,requester 归加号,见下面 AddCard
                            val want = focusTarget.getOrElse(ri) { 0 }.coerceIn(0, pkgs.size)
                            val fm = if (pi == want) Modifier.focusRequester(rowFocus[ri]) else Modifier
                            // focusRow 也要跟着方向键走:看门狗与显式重定位都拿它当「回哪一行」,
                            // 只在菜单动作里写的话,在第三行按返回取消会跳回第一行、滚动条也弹回顶部。
                            val mark = {
                                // 这个必须跟着真实焦点,算横向位移用
                                rowFocused[ri] = pi
                                // **行号也要冻结**:看门狗拿 focusRow 当「回哪一行」,
                                // 而它原本写在冻结判断之外 —— 从别的应用回来时 Compose 抢先把焦点
                                // 给了 (0,0),这一下就把行号改成 0,恢复于是回到 VIDEO 行第 1 张,
                                // 下一次确定打在别的应用上。铁律 5 说的「每个分量都要拆」,行号这一半漏了。
                                if (!retargeting) { focusRow = ri; focusTarget[ri] = pi }
                            }
                            val tell = { got: Boolean -> report(ri, pi, got); if (got) mark() }
                            if (app != null) {
                                AppCard(
                                    app = app,
                                    metrics = metrics,
                                    title = if (showTitles) (titles[pkg] ?: app.label) else null,
                                    fallbackColor = app.fallbackColor?.let { Color(it) },
                                    onClick = { acting = ri to pi },
                                    modifier = fm,
                                    onFocusChange = tell,
                                    isRowStart = pi == 0,
                                    // 编辑界面的行尾之后**还有一个加号**,不能在这里锁右键,
                                    // 否则同一行里永远到不了它。锁移到 AddCard 上。
                                    isRowEnd = false,
                                    isLastRow = ri == rows.lastIndex,
                                    isFirstRow = ri == 0,
                                )
                            } else if (!allFresh) {
                                // 数据还没跟上:中性占位,别说「未安装」
                                PendingCard(pkg, metrics, fm, onFocusChange = tell,
                                    isRowStart = pi == 0, isLastRow = ri == rows.lastIndex,
                                    isFirstRow = ri == 0)
                            } else {
                                MissingCard(
                                    pkg, metrics, fm, onFocusChange = tell,
                                    isRowStart = pi == 0, isLastRow = ri == rows.lastIndex, isFirstRow = ri == 0,
                                ) { acting = ri to pi }
                            }
                        }
                        AddCard(
                            metrics = metrics,
                            // 该行被清空时,rowFocus 没有卡片可挂,焦点会一个都落不下——
                            // 而这时唯一能自救的控件正是加号,所以让它接住。
                            modifier = if (pkgs.isEmpty() ||
                                focusTarget.getOrElse(ri) { 0 } >= pkgs.size
                            ) Modifier.focusRequester(rowFocus[ri]) else Modifier,
                            onFocusChange = { got ->
                                report(ri, pkgs.size, got)
                                if (got) {
                                    rowFocused[ri] = pkgs.size
                                    if (!retargeting) { focusRow = ri; focusTarget[ri] = pkgs.size }
                                }
                            },
                            isRowStart = pkgs.isEmpty(),
                            isLastRow = ri == rows.lastIndex,
                            isFirstRow = ri == 0,
                        ) { picking = ri }
                        }
                }
            }
        }

        // acting 指向的卡片可能已经不在了(比如它所在的行被别处改短)。
        // **不在组合期写状态**:清空动作放进 LaunchedEffect,组合期只负责不渲染。
        val actingPkg = acting?.let { (ri, pi) -> rows.getOrNull(ri)?.second?.getOrNull(pi) }
        LaunchedEffect(acting, actingPkg) { if (acting != null && actingPkg == null) acting = null }
        acting?.let { (ri, pi) ->
            val pkg = actingPkg ?: return@let
            GearMenu(
                items = buildList {
                    // 不适用的方向直接不列出,否则点了什么都不发生、也没有反馈
                    if (pi > 0) add(MenuItem(stringResource(R.string.edit_move_left), stringResource(R.string.edit_move_left_desc)) {
                        if (pi > 0) {
                            rows = rows.mapIndexed { i, r ->
                                if (i == ri) r.first to r.second.toMutableList().also {
                                    it.add(pi - 1, it.removeAt(pi))
                                } else r
                            }
                            persist(); retarget(ri, pi - 1)
                        }
                        acting = null
                    })
                    if (pi < rows[ri].second.lastIndex) add(MenuItem(stringResource(R.string.edit_move_right), stringResource(R.string.edit_move_right_desc)) {
                        if (pi < rows[ri].second.size - 1) {
                            rows = rows.mapIndexed { i, r ->
                                if (i == ri) r.first to r.second.toMutableList().also {
                                    it.add(pi + 1, it.removeAt(pi))
                                } else r
                            }
                            persist(); retarget(ri, pi + 1)
                        }
                        acting = null
                    })
                    add(MenuItem(stringResource(R.string.edit_change_image), stringResource(R.string.edit_change_image_desc)) {
                        // retarget 只服务「选择器没打开」(存储没就绪、已 toast)那条路:本页留在原地,焦点回这张卡。
                        // 打开了的话本页随即被选择器替换,回来时由调用方把 (ri, pkg) 当 initialTarget 喂回来。
                        acting = null; retarget(ri, pi); onPickIcon(ri, pkg)
                    })
                    add(MenuItem(stringResource(R.string.edit_remove), stringResource(R.string.edit_remove_desc)) {
                        rows = rows.mapIndexed { i, r ->
                            if (i == ri) r.first to r.second.toMutableList().also { it.removeAt(pi) } else r
                        }
                        // 移出之后那一格没了,焦点落到它原来位置的前一格(行空了就是加号)
                        persist(); acting = null; retarget(ri, (pi - 1).coerceAtLeast(0))
                    })
                },
                onDismiss = {
                    acting = null; retarget(ri, pi)
                },
                nonce = focusNonce,
            )
        }

        picking?.let { ri ->
            AppPicker(
                nonce = focusNonce,
                ctx = ctx,
                exclude = rows.flatMap { it.second }.toSet(),
                onPick = { pkg ->
                    rows = rows.mapIndexed { i, r ->
                        if (i == ri) r.first to r.second.toMutableList().also { it.add(pkg) } else r
                    }
                    // 刚加进来的那张卡就是新的行尾,焦点落到它身上
                    persist(); picking = null
                    retarget(ri, rows[ri].second.lastIndex.coerceAtLeast(0))
                },
                // 注:AppPicker 自己没有 BackHandler,取消走的是本文件上方那个 —— 目标也在那里设。

            )
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AddCard(
    metrics: CardMetrics,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    isRowStart: Boolean = false,
    isLastRow: Boolean = false,
    isFirstRow: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val highlight = LocalThemeColors.current.highlight
    // 卡片聚焦会放大 1.1 倍并起描边,加号原来只换个底色,暗背景下看不出「我选中的是它」
    val addScale by androidx.compose.animation.core.animateFloatAsState(
        if (focused) 1.12f else 1f, label = "addScale",
    )
    Box(
        modifier = modifier
            .size(metrics.cardWidth, metrics.cardHeight)
            .scale(addScale)
            .clip(RoundedCornerShape(metrics.cardCorner))
            .background(if (focused) highlight.copy(alpha = 0.30f) else Theme.AddCardBackground)
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
        BasicText("＋", style = TextStyle(fontFamily = Theme.Sans, color = highlight, fontSize = 26.sp))
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
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.SecondaryText, fontSize = 11.sp, textAlign = TextAlign.Center),
            modifier = Modifier.padding(6.dp),
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MissingCard(
    pkg: String,
    metrics: CardMetrics,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    isRowStart: Boolean = false,
    isLastRow: Boolean = false,
    isFirstRow: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(metrics.cardWidth, metrics.cardHeight)
            .clip(RoundedCornerShape(metrics.cardCorner))
            .background(if (focused) Theme.MissingCardFocusedBackground else Theme.MissingCardBackground)
            // 行首/末行的边界同样要锁,理由见 AppCard:找不到候选时焦点会整棵树消失
            .focusProperties {
                if (isRowStart) left = FocusRequester.Cancel
                if (isLastRow) down = FocusRequester.Cancel
                if (isFirstRow) up = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = stringResource(R.string.edit_not_installed, pkg),
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.MissingCardText, fontSize = 10.sp, textAlign = TextAlign.Center),
            modifier = Modifier.padding(6.dp),
        )
    }
}

/** 选一个应用加进某行。列出机器上所有能启动的应用,已在桌面上的不再重复列出。 */
@Composable
private fun AppPicker(
    nonce: Int,
    ctx: Context,
    exclude: Set<String>,
    onPick: (String) -> Unit,
) {
    // 打开列表那一刻的基线:本次列表按打开前的时间戳标「新」,同时把时间戳推到现在(先算后写)。
    val seenAtBefore = remember { SettingsStore.read(ctx).newAppsSeenAt }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            SettingsStore.update(ctx) { it.copy(newAppsSeenAt = System.currentTimeMillis()) }
        }
    }
    // 选择器只显示名字,不需要位图
    // null = 还在读。用 emptyList 当初值时,弹出的框第一眼就写着「没有可添加的应用了」,
    // 几百毫秒后才刷出列表 —— 看到这句话的人会直接按返回,认定功能坏了。
    // (includeAllInstalled 之后要多做上百次包查询,这个窗口更明显。)
    val candidates by produceState<List<AppEntry>?>(initialValue = null, exclude) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadCandidates(ctx, exclude) }.getOrDefault(emptyList())
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
    LaunchedEffect(nonce, candidates) {
        if (candidates.isNullOrEmpty()) return@LaunchedEffect
        val i = focusedIdx.coerceIn(0, rowFocus.lastIndex)
        var frames = 0
        while (focusedItem == null && frames < 60) {
            withFrameNanos { }
            runCatching { rowFocus[i].requestFocus() }
            frames++
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusGroup()   // 同 GearMenu:不圈起来焦点会跑到蒙版后面
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Theme.DialogSurface)
                .padding(14.dp)
                .width(460.dp)
                .heightIn(max = 420.dp),
        ) {
            BasicText(
                stringResource(R.string.edit_add_app_title),
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                style = TextStyle(fontFamily = Theme.Sans, color = LocalThemeColors.current.highlight, fontSize = 16.sp),
            )
            if (candidates == null) {
                BasicText(
                    stringResource(R.string.edit_loading_apps),
                    modifier = Modifier.padding(10.dp),
                    style = TextStyle(fontFamily = Theme.Sans, color = Theme.SecondaryText, fontSize = 13.sp),
                )
            } else if (candidates!!.isEmpty()) {
                BasicText(
                    stringResource(R.string.edit_no_more_apps),
                    modifier = Modifier.padding(10.dp),
                    style = TextStyle(fontFamily = Theme.Sans, color = Theme.SecondaryText, fontSize = 13.sp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(candidates.orEmpty(), key = { _, a -> a.packageName }) { i, app ->
                    PickerRow(
                        app = app,
                        modifier = Modifier.focusRequester(rowFocus[i.coerceIn(0, rowFocus.lastIndex)]),
                        onFocusChange = { got ->
                            if (got) { focusedItem = i; focusedIdx = i }
                            else if (focusedItem == i) focusedItem = null
                        },
                        isFirst = i == 0,
                        isLast = i == candidates.orEmpty().lastIndex,
                        // 候选本来就不在桌面上(loadCandidates 已经把 layout.json 里的包 exclude 掉了)。
                        isNew = isNewApp(app.firstInstallTime, seenAtBefore, onLayout = false),
                        onClick = { onPick(app.packageName) },
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PickerRow(
    app: AppEntry,
    modifier: Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    isFirst: Boolean = false,
    isLast: Boolean = false,
    isNew: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val highlight = LocalThemeColors.current.highlight
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) highlight.copy(alpha = 0.16f) else Color.Transparent)
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
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                text = app.label.ifBlank { app.packageName },
                // weight(fill = false):名字很长时先挤自己(换行),不把「新」标推出对话框右边缘;
                // fill = false 保证短名字仍然紧挨着标,不会中间空一大段。
                modifier = Modifier.weight(1f, fill = false),
                style = TextStyle(fontFamily = Theme.Sans, color = if (focused) highlight else Theme.DialogBodyText, fontSize = 14.sp),
            )
            if (isNew) Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(highlight.copy(alpha = 0.22f)).padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                BasicText(text = stringResource(R.string.edit_badge_new), style = TextStyle(fontFamily = Theme.Sans, color = highlight, fontSize = 10.sp))
            }
        }
        BasicText(
            text = app.packageName,
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.FootnoteText, fontSize = 10.sp),
        )
    }
}

/**
 * 可添加的应用。用 `includeAllInstalled` 按已安装包全量补齐,好让当贝音乐这类
 * 「只有 MAIN/DEFAULT、没有 LAUNCHER 分类」的应用**移出后还能加回来**。
 * 2026-09-11 实测:此前那版用 `extraPackages = exclude` 再 filter 掉,等于从没生效,
 * 当贝音乐移出后在列表里彻底消失,只能 adb 改 layout.json 才找得回来。
 */
private fun loadCandidates(ctx: Context, exclude: Set<String>): List<AppEntry> =
    // **不要再传 extraPackages = exclude**:那些包紧接着就被下面的 filter 滤掉,
    // 这条兜底对选择器从来没有生效过;而应用一旦被移出就不在 exclude 里,更查不到它。
    // 改为按已安装包全量补齐。
    Apps.load(ctx, withBitmaps = emptySet(), includeAllInstalled = true).values
        .filter { it.packageName !in exclude && it.packageName != ctx.packageName }
        .sortedBy { it.label }
