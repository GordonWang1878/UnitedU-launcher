package com.uniteduone.launcher

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 两栏的尺寸(spec §2.1)。左栏 7 项、右栏最多 8 行,两栏都**一屏放得下**,
// 所以这里不需要 HomeScreen / 旧设置页那种自算纵向位移 —— 但同样一行滚动容器都不许有(铁律 1)。
private val PANE_LEFT_W = 260.dp
private val PANE_RIGHT_W = 640.dp
private val H_TITLE = 56.dp
private val H_ROW = 46.dp
/**
 * 右栏行内标签列宽:标签右边才是控件,同一组的控件因此纵向对齐。
 * 190 而不是旧设置页的 150 —— 英文的「Follow Wallpaper Color」在 150 下要折成两行、
 * 高出 46dp 的行高(2026-09-17 模拟器实测)。右栏 640dp 里最宽的一行是滑块
 * (12 + 2.5 + 12 + 190 + 8 + 220 + 10 + 44 ≈ 499dp),加宽后仍有富余。
 */
private val LABEL_W = 190.dp

private const val PANE_L = 0
private const val PANE_R = 1

private const val LOG_TAG = "UnitedU"

/** 设置页当前站在哪一格。Activity 持有它,`recreate()`(切语言)后原样种回来(spec §5,T8)。 */
data class SettingsPos(val pane: Int, val group: Int, val row: Int)

/**
 * UnitedU 设置页:**左栏分组 + 右栏当前组的行**,整页盖在常驻首页之上做实时预览(spec §2、§3)。
 *
 * 为什么是两栏:全部行加起来约 18 行,一屏放不下,而这份代码里**任何可滚动容器都是禁区**
 * (铁律 1:`LazyColumn`/`verticalScroll` 会让 D-pad 焦点整棵树消失)。两栏之后左 7 项、右 ≤ 8 行,
 * 各自都在一屏内,连自算位移都省了。
 *
 * 为什么是叠加而不是替换:遮罩是**左深右浅的水平渐变**,左边压住让文字可读,右边只压 25%,
 * 底下首页的卡片清清楚楚 —— 改卡片大小 / 标题 / 主题色的效果当场可见(spec §3.1)。
 * 底层首页由 `previewing` 交出焦点与按键(见 HomeScreen 那个参数的 KDoc),这一层独占输入。
 *
 * 焦点账本是**二维**的(`pane` / `group` / 每组独立的 `rowOf`),七条铁律逐条落在:
 * - 铁律 2/4:焦点落没落下只信控件自报 [report],`requestFocus()` 的返回值什么都不说明;
 * - 铁律 3:每个左栏项、每个右栏行**逐项**挂 `FocusRequester`;子界面(选择器)开着时
 *   本页让路(`covered`),由它自己负责焦点 —— 它关掉时本页负责把焦点接回同一行;
 * - 铁律 5:「目标」(pane/group/rowOf)与「当前」([focusedCell])分开,而且 `restoring`
 *   期间冻结目标,不让 Compose 抢先给出的那次焦点事件把记忆改写掉;
 * - 铁律 6:两个效果里每一条 `return@LaunchedEffect` 的守卫都在自己的 key 里;
 * - 铁律 7:没有一次性布尔闩,`restoring` 派生自 `covered`、切栏靠 `moveNonce` 递增。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    onExit: () -> Unit,
    actions: SettingsActions,
    focusNonce: Int = 0,
    /** 有子界面(选择器 / 导入页 / 默认桌面卡)盖在本页之上:让路,别跟它抢焦点(铁律 3)。 */
    covered: Boolean = false,
    /** 任一设置改动后立刻通知(→ `settingsRevision++`):底层首页重读 settings.json,预览即时生效。 */
    onSettingsChanged: () -> Unit = {},
    /** 模糊 / 亮度改动 300 ms 防抖后通知:壁纸要重新处理一遍,不该每按一下就跑一次。 */
    onWallpaperParamsChanged: () -> Unit = {},
    /**
     * 待机演示(spec §3.2):焦点停在「待机内容」这一行时,把当前选中的值上报出去;
     * 离开这一行(或整页被 `covered` 盖住)就要变回 null。`MainActivity` 拿它驱动
     * 底层首页的待机预览,`leaveSettings()` 那条路另有一层派生兜底(铁律 7 见 `demoIdle` KDoc),
     * 这里只管「本页开着期间,焦点在不在那一行」这一件事。
     */
    onDemoIdle: (IdleContent?) -> Unit = {},
    /** `recreate()` 之前记下的位置(T8);null = 从左栏第一组开始。 */
    initialPos: SettingsPos? = null,
    onPosChanged: (SettingsPos) -> Unit = {},
    /**
     * `MainActivity` 专为「本页需要绕过自己重读一次 settings.json」开的计数器,**只有
     * `confirmRestoreDefaults()` 在 `withContext(IO)` 写盘落地之后才会 `++`**(T7 复审
     * Important #1)。
     *
     * 最初这条重读挂在 `covered` 上,但 `confirmRestore` 有五条路径能把它写回 `false`
     * (确认框自己的取消 / 返回、MENU 键、返回键兜底、`leaveSettings`),其中任意一条若抢在
     * 写盘完成**之前**跑完,`covered` 就会提前落回 `false`、触发一次「读到旧值」的重读;
     * 写盘真正完成后 `confirmRestore` 再赋一次 `false` 对 Compose 是无操作(值没变),`covered`
     * 不会二次翻转,依赖它的效果永远不会再用最终值重读一次——「其他」组以外的分组会一直停在
     * 恢复前的档位直到退出设置页重进,而 toast 却已经说「已恢复默认设置」。
     *
     * 改用**专用**计数器而不是复用「任一改动都 `++`」的 `settingsRevision`:本页自己的
     * `update()` 也会通过 `onSettingsChanged()` 间接让 `settingsRevision` 递增,若挂在那颗上,
     * 每按一次分段控件都会在写完之后**再重读一次盘**——多数时候只是白读一次,但
     * `SettingsStore.update` 写失败(外置存储没挂)时,`update()` 特地保留的「内存里的改动」
     * 会被这次重读立刻覆盖回盘上的旧值,刚才的调整在画面上当场弹回去,把「写失败时至少内存
     * 里保住这次改动」的既有兜底整个废掉。专用计数器只在这一件事(恢复默认写盘落地)上递增,
     * 不搭车。
     */
    reloadNonce: Int = 0,
    /** 屏保图库版本(M5):MainActivity 删图后 +1,本页据此重数图库(「屏保启动」行的提示)。 */
    galleryVersion: Int = 0,
    /**
     * 布局 / 应用数据版本(M4b):与首页 `HomeScreen` 共用 `MainActivity` 的同一颗 `revision`——
     * 「隐藏输入源」「恢复隐藏的输入源」两个动作都要让首页把卡片行重建一遍(不能挂
     * `settingsRevision`:那颗只重读 settings.json、明确不重建首页行,见 MainActivity 里它的 KDoc),
     * 本页「恢复隐藏的输入源」那一行的计数就顺着同一颗车重读,不必再开一颗专用计数器。
     */
    revision: Int = 0,
) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(SettingsStore.read(ctx)) }

    // 改一下存一下,同步落盘(理由同 M2:文件几百字节,主线程写是亚毫秒级;异步写会让
    // 「改完立刻按返回」读到旧值)。**写前重读、只改自己那个字段**:后台轮播也写 settings.json,
    // 整对象回写会把它的 wallpaperFile/rotatedAt 盖掉(壁纸来回翻)。
    // 写失败(外置存储没挂)时保住内存里的改动而不是退回默认值,只 Log、不崩。
    fun update(transform: (Settings) -> Settings) {
        s = SettingsStore.update(ctx, transform) ?: transform(s).also {
            Log.w(LOG_TAG, "settings.json 写入失败,改动只留在内存里")
        }
        // **每次改动都通知,不防抖**:布局组(卡片大小 / 标题 / 输入源行)、主题组的效果都在
        // 底层首页上,晚一拍就不叫实时预览了。壁纸的模糊 / 亮度另走下面那条 300 ms 防抖。
        onSettingsChanged()
    }

    // [SettingsActions] 里那几条会**绕过本页直接写盘**(切语言、T7 的恢复默认),
    // 写完本页手上的快照就过期了 —— 语言行会停在旧档位。所以在这里包一层:动作跑完立刻重读。
    // (不能让模型去做:它不认识 Context;也不该让 Activity 回调进来:那又是一条要维护的边。)
    val liveActions = remember(actions) {
        SettingsActions(
            pickWallpaper = actions.pickWallpaper,
            openImport = actions.openImport,
            setDefaultHome = actions.setDefaultHome,
            restoreDefaults = { actions.restoreDefaults(); s = SettingsStore.read(ctx) },
            applyLanguage = { lang -> actions.applyLanguage(lang); s = SettingsStore.read(ctx) },
            // M5 两条:不写盘,本页快照不会过期,原样转交。
            openScreensaverGallery = actions.openScreensaverGallery,
            openSystemScreensaver = actions.openSystemScreensaver,
            // M4b:写的是 hidden-inputs.json,不是 settings.json——本页的 `s` 快照不会过期,原样转交
            // (同上面两条 M5 动作同一个理由);计数靠 hiddenInputs 那颗 produceState 跟 revision 重读。
            restoreHiddenInputs = actions.restoreHiddenInputs,
        )
    }

    // **T7 恢复默认的写盘走确认框**,不像切语言那样在 `actions.restoreDefaults()` 返回前就
    // 写完盘——`liveActions.restoreDefaults` 那次 `s = SettingsStore.read(ctx)`(上面这段
    // KDoc 写的重读)因此读到的还是恢复前的旧值:确认框此刻才刚打开,`MainActivity` 真正的
    // 写盘要等用户按下「恢复」才发生。这条效果补上那一半:`reloadNonce`(见其参数 KDoc,
    // T7 复审 Important #1)在写盘真正落地之后才递增一次,这里跟着重读一次,保证读到的是
    // 盘上刚落地的最终值——不需要 guard,计数器本身的语义就是「该重读了」(铁律 6:这里
    // 没有可省略的分支,key 即全部逻辑)。
    LaunchedEffect(reloadNonce) {
        s = SettingsStore.read(ctx)
    }

    // 屏保图库张数(M5 spec §3):「屏保启动」行的提示要分「图库为空」。IO 线程数(与播放器同一条扫描规则,
    // 见 scanScreensaverLibrary);key 带图库版本(删图后 +1)与 covered——从导入页 / 图库查看器回来时重数一次,
    // 手机上传也会改图库。−1 = 还没数完,模型按非空处理。produceState 没有守卫,不涉及铁律 6。
    val screensaverImages by produceState(-1, galleryVersion, covered) {
        value = withContext(Dispatchers.IO) { scanScreensaverLibrary(ctx).size }
    }

    // 隐藏的输入源数(M4b spec §0-11):「恢复隐藏的输入源」行的计数与出现/消失,做法照上面的
    // screensaverImages——IO 线程数,key 带 revision(见它的参数 KDoc:「隐藏」「恢复」两个动作
    // 都靠它让首页重建行,这里跟着同一颗车重读)与 covered。−1 = 还没数完,不会提前把这一行露出来——
    // 下面 settingsGroups 的插入条件是 `hiddenInputs > 0`,−1 与 0 同样不露出。
    val hiddenInputs by produceState(-1, revision, covered) {
        value = withContext(Dispatchers.IO) { HiddenInputs.read(ctx).size }
    }

    // 内容模型(分组 / 行 / 当前档位)全在 SettingsModel.kt 里,这里只画和管焦点。
    val groups = settingsGroups(s, { transform -> update(transform) }, liveActions, screensaverImages, hiddenInputs)

    // 模糊/亮度改动后 300 ms 防抖通知首页重处理壁纸。用「上次通知过的值」比对,不用一次性布尔闩
    // (铁律 7):首次组合两者相等不发;改回原值也会再发一次,预览不会卡在旧参数上。
    val previewKey = Pair(s.wallpaperBlur, s.wallpaperBrightness)
    var lastNotified by remember { mutableStateOf(previewKey) }
    LaunchedEffect(previewKey) {
        if (previewKey == lastNotified) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        lastNotified = previewKey
        onWallpaperParamsChanged()
    }

    // ---- 二维焦点账本 ----------------------------------------------------------
    // 「目标」三件套:哪一栏、左栏第几组、**每组各自**上次离开时停在第几行(spec §2.1)。
    var pane by remember { mutableStateOf(initialPos?.pane?.coerceIn(PANE_L, PANE_R) ?: PANE_L) }
    var group by remember {
        mutableStateOf(initialPos?.group?.coerceIn(0, groups.lastIndex) ?: 0)
    }
    val rowOf = remember {
        mutableStateListOf(*Array(groups.size) { i ->
            if (initialPos != null && initialPos.group == i) initialPos.row.coerceAtLeast(0) else 0
        })
    }
    val rows = groups[group.coerceIn(0, groups.lastIndex)].rows
    /** **当前**焦点在哪一格,只由控件自报(铁律 4);null = 整棵树没有任何一格持有焦点。 */
    var focusedCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /**
     * 正在把焦点往目标上送(或被子界面盖着)。期间**冻结目标**:子界面关掉那一帧
     * Compose 会抢先把焦点塞给整棵树第一个可聚焦节点(= 左栏第一组),那次上报若不挡住
     * 就会把 group 改写成 0 —— 记忆在被用到之前就没了(铁律 5)。
     * 派生自 `covered` 而不是一次性布尔闩(铁律 7):子界面一关它自然放开。
     */
    var restoring by remember { mutableStateOf(false) }
    // **从 ON_PAUSE 就冻结目标**(铁律 5 的后半句,M7 终审 I1;与 HomeScreen / EditScreen 同一手法)。
    // 灭屏再亮、或别的应用到前台再回来:Compose 会抢在定位效果重启之前,把焦点塞给整棵树第一个
    // 可聚焦节点 = 左栏第一组;那次上报若看到 restoring 还是 false,就把 pane/group 改写成「左栏·布局」,
    // 用户回来落在布局组而不是离开时那一行。ON_PAUSE 一到就置位;放开交给定位效果——onResume 的
    // focusNonce++ 让它重启、把焦点送回冻着的目标,并且**只在前台时**才放开(见它末尾)。
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) restoring = true
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // 每一项一个 requester(铁律 3 的实现约束:逐项挂,恢复才有地方落)。
    // 右栏按「组内行号」挂:同一时刻只有当前组的行在组合里,下标 0..rows.lastIndex 一定挂得上,
    // 而所有落点都夹在这个区间内(见 targetOf),不会去请求一个没挂上的 requester。
    val groupReq = remember { List(groups.size) { FocusRequester() } }
    val rowReq = remember { List(8) { FocusRequester() } }

    fun report(p: Int, i: Int, got: Boolean) {
        // 目标跟着「焦点真的落在哪」走,**还原/让路期间不更新**(理由见 restoring 的 KDoc)。
        if (got && !restoring) {
            pane = p
            if (p == PANE_L) group = i else rowOf[group] = i
        }
        // 得失顺序保护:导航时若新格先报 got、旧格后报 lost,旧格那次 lost 不该把新格抹掉。
        if (got) focusedCell = p to i
        else if (focusedCell == p to i) focusedCell = null
    }

    /** 目标格 + 它的 requester。落点与判据用**同一个夹过的坐标**,否则循环会跑满 60 帧还落不下。 */
    fun targetOf(): Pair<Pair<Int, Int>, FocusRequester> {
        val g = group.coerceIn(0, groups.lastIndex)
        return if (pane == PANE_L) {
            (PANE_L to g) to groupReq[g]
        } else {
            val r = rowOf[g].coerceIn(0, groups[g].rows.lastIndex)
            (PANE_R to r) to rowReq[r]
        }
    }

    /**
     * 切栏(左↔右)**不是「requestFocus 然后假定成功」**:先把目标改掉,再让下面那个效果
     * 用「循环请求 + 只信自报」把焦点送过去(铁律 2)。`moveNonce` 每次递增,天然自愈,
     * 不需要任何人把它清回去(铁律 7)。
     */
    var moveNonce by remember { mutableStateOf(0) }
    fun moveTo(p: Int, i: Int) {
        pane = p
        if (p == PANE_L) group = i else rowOf[group] = i
        moveNonce++
    }

    // **定位效果**:三件事共用这一条 —— ①首次进入(落左栏当前组);②切栏(moveNonce);
    // ③从子界面 / 别的应用回来(covered 落下、focusNonce++)时把焦点接回同一格。
    // 守卫 `covered` 就是 key(铁律 6)。
    LaunchedEffect(focusNonce, covered, moveNonce) {
        if (covered) { restoring = true; return@LaunchedEffect }
        restoring = true
        val (want, req) = targetOf()
        var frames = 0
        // requestFocus() 返回 Unit,只有一个节点都没挂上才抛 —— 落没落下只能由目标自报(铁律 2)。
        while (frames < 60 && focusedCell != want) {
            withFrameNanos { }
            runCatching { req.requestFocus() }
            frames++
        }
        // **只在前台时放开**(终审 I1):ON_PAUSE 若恰好打在这段循环中途,循环在暂停期间跑完后
        // 无条件放开,就等于撤销了上面那个观察者的冻结。不在前台就继续冻着——这不是闩(铁律 7):
        // 回到前台必经 onResume 的 focusNonce++,本效果必然重跑,到那时再在前台放开。
        restoring = !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    // **焦点看门狗**:上面那条管「我想去哪」,这条管「焦点莫名其妙没了」——节点被重组销毁、
    // 从后台回来、子界面关掉时都会没。不靠「在猜得到的几个时刻补请求」(铁律 3)。
    // 三条守卫 covered / restoring / focusedCell 全在 key 里(铁律 6);pane/group/rowOf 只是读的量。
    LaunchedEffect(focusNonce, focusedCell, covered, restoring, pane, group, rowOf[group.coerceIn(0, rowOf.lastIndex)]) {
        if (covered) return@LaunchedEffect
        if (restoring) return@LaunchedEffect
        if (focusedCell != null) return@LaunchedEffect
        // D-pad 换格时旧格先报 null、新格下一帧才报 got —— 中间那一帧的 null 不算「丢了」。
        repeat(3) { withFrameNanos {} }
        if (focusedCell != null) return@LaunchedEffect
        val (_, req) = targetOf()
        var frames = 0
        while (focusedCell == null && frames < 60) {
            withFrameNanos { }
            runCatching { req.requestFocus() }
            frames++
        }
    }

    // 位置上报给 Activity:`onSaveInstanceState` 存它,切语言 recreate() 后经 `initialPos`
    // 种回本页顶上那几个 `remember` 的初值(T8,spec §5)。纯读、无守卫,每次账本变动都
    // 重报一次全新的值,没有要清的状态。
    LaunchedEffect(pane, group, rowOf[group.coerceIn(0, rowOf.lastIndex)]) {
        onPosChanged(SettingsPos(pane, group, rowOf[group.coerceIn(0, rowOf.lastIndex)]))
    }

    // **待机演示**(spec §3.2):判据直接从 `focusedCell`/`group` 派生「是不是正站在
    // 『待机内容』这一行」——不缓存、不设一次性布尔闩(铁律 7):离开那一行、切到别的组、
    // 或整页被子界面盖住(此时 `focusedCell` 已经因失焦变 null),这个判据自己就变回 false,
    // 没有专门的「清空」路径要另外维护。上报值取 `s.idleContent` 而不是控件自己另存一份——
    // 它与 `ControlRow.selected` 同源,左右键改完档位那一刻这里跟着变,预览与实际选中永远一致。
    val standbyGroupIndex = groups.indexOfFirst { it.id == GroupId.STANDBY }
    val idleContentRowIndex = groups.getOrNull(standbyGroupIndex)?.rows?.indexOfFirst { it.id == "idleContent" } ?: -1
    val onIdleContentRow = pane == PANE_R && group == standbyGroupIndex &&
        idleContentRowIndex >= 0 && focusedCell == (PANE_R to idleContentRowIndex)
    val demoIdle = if (onIdleContentRow) s.idleContent else null
    LaunchedEffect(demoIdle) { onDemoIdle(demoIdle) }

    // 返回键关闭本页。走 Compose 的 BackHandler:它比 MainActivity 那个常开回调后注册,
    // 本页在时优先接管;子界面叠在本页之上时,它自己的 BackHandler 又比这条更后注册,先接管。
    androidx.activity.compose.BackHandler { onExit() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // **左深右浅的水平渐变**(spec §3.1):左边文字列压到 60% 黑保证可读,
            // 右端只压 25%,底层首页的卡片在那里清清楚楚 —— 这就是「实时预览」看得见的那一半。
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.60f),
                    1f to Color.Black.copy(alpha = 0.25f),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 28.dp, top = 24.dp),
        ) {
            Box(Modifier.height(H_TITLE), contentAlignment = Alignment.CenterStart) {
                Column {
                    BasicText(
                        text = stringResource(R.string.settings_title),
                        style = TextStyle(
                            fontFamily = Theme.Sans,
                            fontWeight = FontWeight.Medium,
                            color = Theme.EmphasisText,
                            fontSize = 22.sp,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        text = stringResource(R.string.menu_back_to_close),
                        style = TextStyle(
                            fontFamily = Theme.Sans,
                            color = Theme.FooterHintText,
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
            // **放开横向测量**(铁律 1 的后半句)。两栏合计 28 + 260 + 16 + 640 = 944dp,
            // 1920×1080/320dpi 的屏是 960dp,本来放得下;但一旦换到更窄的设备,父容器按屏宽给约束时
            // `Modifier.width()` 会被 constrain —— 空间用完之后的条目被量成 0 宽,而 0 宽条目在
            // 焦点搜索里**永远选不中**(right 全部相等)。宁可让它探出屏幕,也不能让它变成 0。
            Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)) {
                // ---- 左栏:七个分组 ----
                Column(Modifier.width(PANE_LEFT_W)) {
                    groups.forEachIndexed { i, g ->
                        GroupItem(
                            titleRes = g.titleRes,
                            current = i == group,
                            focusRequester = groupReq[i],
                            upReq = if (i > 0) groupReq[i - 1] else null,
                            downReq = if (i < groups.lastIndex) groupReq[i + 1] else null,
                            onFocusChange = { got -> report(PANE_L, i, got) },
                            onEnterRightPane = {
                                // 进右栏**那一组上次离开的行**(spec §2.1),不是恒第一行。
                                moveTo(PANE_R, rowOf[i].coerceIn(0, groups[i].rows.lastIndex))
                            },
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                // ---- 右栏:当前组的行 ----
                Column(Modifier.width(PANE_RIGHT_W)) {
                    rows.forEachIndexed { i, row ->
                        val up = if (i > 0) rowReq[i - 1] else null
                        val down = if (i < rows.lastIndex) rowReq[i + 1] else null
                        val onLeave = { moveTo(PANE_L, group) }
                        when (row) {
                            is ControlRow -> SettingRow(
                                ctrl = row,
                                focusRequester = rowReq[i],
                                upReq = up,
                                downReq = down,
                                onFocusChange = { got -> report(PANE_R, i, got) },
                                onLeaveToLeftPane = onLeave,
                            )
                            is ActionRow -> ActionRowItem(
                                action = row,
                                focusRequester = rowReq[i],
                                upReq = up,
                                downReq = down,
                                onFocusChange = { got -> report(PANE_R, i, got) },
                                onLeaveToLeftPane = onLeave,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 左栏的一个分组项。上下键交给焦点系统(显式落点,到边界锁 `Cancel`,防整棵树失焦);
 * **右键与确定键都在 onKeyEvent 里消费掉**,改成一次 [onEnterRightPane] 请求 ——
 * 不直接 `requestFocus` 是因为它的成功与否无从判断(铁律 2),只能「设目标 + 等自报」。
 * 左键同样消费:不消费的话焦点系统找不到左边的候选,会把整棵树的焦点丢掉。
 * [current] = 右栏正显示着这一组:即使焦点已经移到右栏,它也要保持「选中」样式,不然人不知道自己在改哪组。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GroupItem(
    titleRes: Int,
    current: Boolean,
    focusRequester: FocusRequester,
    upReq: FocusRequester?,
    downReq: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
    onEnterRightPane: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val highlight = LocalThemeColors.current.highlight
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(H_ROW)
            .focusRequester(focusRequester)
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                up = upReq ?: FocusRequester.Cancel
                down = downReq ?: FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .onKeyEvent { ke ->
                when (ke.key) {
                    Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (ke.type == KeyEventType.KeyDown) onEnterRightPane(); true
                    }
                    Key.DirectionLeft -> true
                    else -> false
                }
            }
            .focusable(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when {
                        focused -> Brush.horizontalGradient(
                            listOf(highlight.copy(alpha = 0.22f), Color.Transparent),
                        )
                        current -> Brush.horizontalGradient(
                            listOf(highlight.copy(alpha = 0.08f), Color.Transparent),
                        )
                        else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                    },
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(2.5.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (focused) highlight else Color.Transparent),
            )
            Spacer(Modifier.width(12.dp))
            BasicText(
                text = stringResource(titleRes),
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = if (focused || current) FontWeight.Medium else FontWeight.Normal,
                    color = when {
                        focused -> Theme.EmphasisText
                        current -> LocalThemeColors.current.accent
                        else -> Theme.MenuItemText
                    },
                    fontSize = 15.sp,
                ),
            )
        }
    }
}

/**
 * 右栏的一个可改值行。焦点账本的原子单位:
 * - 上下键交给焦点系统(显式落点,到边界锁 `Cancel`);
 * - **左右键在 onKeyEvent 里全部消费**(返回 true),只在 KeyDown 那一下动作 —— 焦点一格不横移;
 * - 右键 = 下一档,到最右档照样消费(不消费就会落到焦点系统、找不到候选而丢掉整棵树的焦点);
 * - 左键 = 上一档,**只有已经在最左档时才回左栏**(spec §2.1)—— 这样滑块可以一路调回 0,
 *   而不是调到一半突然跳栏。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SettingRow(
    ctrl: ControlRow,
    focusRequester: FocusRequester,
    upReq: FocusRequester?,
    downReq: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
    onLeaveToLeftPane: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val highlight = LocalThemeColors.current.highlight

    fun step(delta: Int) {
        val next = (ctrl.selected + delta).coerceIn(0, ctrl.count - 1)
        if (next != ctrl.selected) ctrl.onSelect(next)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(H_ROW)
            .focusRequester(focusRequester)
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                up = upReq ?: FocusRequester.Cancel
                down = downReq ?: FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .onKeyEvent { ke ->
                when (ke.key) {
                    Key.DirectionLeft -> {
                        if (ke.type == KeyEventType.KeyDown) {
                            if (ctrl.selected > 0) step(-1) else onLeaveToLeftPane()
                        }
                        true
                    }
                    Key.DirectionRight -> { if (ke.type == KeyEventType.KeyDown) step(+1); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        RowFrame(focused = focused, label = stringResource(ctrl.labelRes), note = ctrl.noteRes?.let { stringResource(it) }) {
            when (ctrl.kind) {
                CtrlKind.SWATCH -> SwatchControl(selected = ctrl.selected, rowFocused = focused)
                CtrlKind.SLIDER -> SliderControl(
                    selected = ctrl.selected, count = ctrl.count,
                    rowFocused = focused, zeroAt = ctrl.zeroAt,
                )
                else -> SegmentedControl(
                    options = ctrl.optionRes.mapIndexed { i, res ->
                        val arg = ctrl.optionArgs.getOrNull(i)
                        if (arg != null) stringResource(res, arg) else stringResource(res)
                    },
                    selected = ctrl.selected,
                    rowFocused = focused,
                )
            }
        }
    }
}

/**
 * 右栏的一个动作行(spec §2.2 的 ▸):确定键打开子界面。左键直接回左栏 —— 它没有档位,
 * 没有「先调值」这一说。确定键交给 `clickable`(与齿轮菜单同一套),不自己在 onKeyEvent 里判,
 * 免得和 MainActivity 里的长按识别各判一套。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ActionRowItem(
    action: ActionRow,
    focusRequester: FocusRequester,
    upReq: FocusRequester?,
    downReq: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
    onLeaveToLeftPane: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(H_ROW)
            .focusRequester(focusRequester)
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                up = upReq ?: FocusRequester.Cancel
                down = downReq ?: FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .onKeyEvent { ke ->
                when (ke.key) {
                    // 右键在动作行上没有语义,但同样要消费掉:落到焦点系统就会丢焦点。
                    Key.DirectionLeft -> { if (ke.type == KeyEventType.KeyDown) onLeaveToLeftPane(); true }
                    Key.DirectionRight -> true
                    else -> false
                }
            }
            .clickable(onClick = action.onActivate),
    ) {
        RowFrame(focused = focused, label = stringResource(action.labelRes)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    // hintArg 非 null = 带格式参数的文案(目前只有「恢复隐藏的输入源」的「%1$d 个」),
                    // 与 SettingRow 里 optionArgs 的解析方式同一个道理(见 ActionRow.hintArg 的 KDoc)。
                    text = action.hintArg?.let { stringResource(action.hintRes, it) } ?: stringResource(action.hintRes),
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        color = if (focused) Theme.SecondaryText else Theme.FooterHintText,
                        fontSize = 12.sp,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = "▸",
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        color = if (focused) LocalThemeColors.current.highlight else Theme.SecondaryText,
                        fontSize = 13.sp,
                    ),
                )
            }
        }
    }
}

/**
 * 行的外壳:聚焦底色 + 左侧竖条 + 标签列。两种行共用,免得「只有动作行忘了改」那种漂移。
 * [note](M5 spec §3「标签与控件之间一行小字」):画在标签**下方**、仍在 190 dp 标签列里——控件列不右移,
 * 同组控件照旧纵向对齐;横着塞不下(五档分段控件之后只剩约 80 dp)。15 sp 标签 + 12 sp 小字两行放得进
 * 46 dp 行高,右栏行数上限(≤ 8,不滚动)不受影响。样式同 ActionRowItem 的 hint。
 */
@Composable
private fun RowFrame(focused: Boolean, label: String, note: String? = null, content: @Composable () -> Unit) {
    val highlight = LocalThemeColors.current.highlight
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) Brush.horizontalGradient(
                    listOf(highlight.copy(alpha = 0.12f), Color.Transparent),
                ) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)),
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.5.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (focused) highlight else Color.Transparent),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(LABEL_W)) {
            BasicText(
                text = label,
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                    color = if (focused) Theme.EmphasisText else Theme.MenuItemText,
                    fontSize = 15.sp,
                ),
            )
            if (note != null) {
                BasicText(
                    text = note,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        color = if (focused) Theme.SecondaryText else Theme.FooterHintText,
                        fontSize = 12.sp,
                    ),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.CenterStart) { content() }
    }
}

/** 分段选择器 / 开关(开关就是两段「关|开」)。选中项高亮;行聚焦时选中项更亮(主题 highlight 底)。 */
@Composable
private fun SegmentedControl(options: List<String>, selected: Int, rowFocused: Boolean) {
    val highlight = LocalThemeColors.current.highlight
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, opt ->
            val isSel = i == selected
            val bg = when {
                isSel && rowFocused -> highlight
                isSel -> highlight.copy(alpha = 0.22f)
                else -> Theme.UnfocusedSurface
            }
            val fg = when {
                isSel && rowFocused -> Theme.Background   // 亮高亮底上用深色字
                isSel -> Theme.EmphasisText
                else -> Theme.SecondaryText
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(bg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = opt,
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        fontWeight = if (isSel) FontWeight.Medium else FontWeight.Normal,
                        color = fg,
                        fontSize = 13.sp,
                    ),
                )
            }
        }
    }
}

/** 主题色 swatch:9 个色点,选中项加环;行聚焦时环变主题 highlight 色(焦点 + 选中都清楚)。 */
@Composable
private fun SwatchControl(selected: Int, rowFocused: Boolean) {
    val highlight = LocalThemeColors.current.highlight
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemePresets.all.forEachIndexed { i, preset ->
            val isSel = i == selected
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(if (isSel) 26.dp else 21.dp)
                        .clip(CircleShape)
                        .background(preset.color)
                        .then(
                            if (isSel) Modifier.border(
                                width = if (rowFocused) 3.dp else 2.dp,
                                color = if (rowFocused) highlight else Theme.EmphasisText,
                                shape = CircleShape,
                            ) else Modifier,
                        ),
                )
            }
        }
    }
}

/**
 * 滑块:11 档,每档 10%。轨道 + 已填充段 + 百分比;行聚焦时填充段亮主题 highlight 色,与分段控件选中态同色。
 * [zeroAt] = 代表 0 的档位:单向滑块为 0(填充从左端起),双向亮度滑块为 5(填充从正中画到当前档,
 * 文字带正负号,0 档显示 0%)。
 */
@Composable
private fun SliderControl(selected: Int, count: Int, rowFocused: Boolean, zeroAt: Int = 0) {
    val highlight = LocalThemeColors.current.highlight
    val trackWidth = 220.dp
    fun at(i: Int) = if (count <= 1) 0f else i.toFloat() / (count - 1)
    val value = (selected - zeroAt) * 10
    val lo = minOf(at(selected), at(zeroAt))
    val hi = maxOf(at(selected), at(zeroAt))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .width(trackWidth)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Theme.UnfocusedSurface),
        ) {
            Box(
                Modifier
                    .padding(start = trackWidth * lo)
                    .fillMaxHeight()
                    .width(trackWidth * (hi - lo))
                    .background(if (rowFocused) highlight else highlight.copy(alpha = 0.22f)),
            )
            // 双向滑块的零点刻度:细竖线,让人一眼看出中点就是原片
            if (zeroAt > 0) Box(
                Modifier
                    .padding(start = trackWidth * at(zeroAt) - 1.dp)
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(Theme.EmphasisText.copy(alpha = 0.55f)),
            )
        }
        BasicText(
            text = if (zeroAt > 0 && value > 0) "+$value%" else "$value%",
            style = TextStyle(
                fontFamily = Theme.Sans,
                color = if (rowFocused) Theme.EmphasisText else Theme.SecondaryText,
                fontSize = 13.sp,
            ),
            modifier = Modifier.width(44.dp),
        )
    }
}
