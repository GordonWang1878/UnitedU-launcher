package com.uniteduone.launcher

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOG_TAG = "UnitedU"

/** 右边一列里的一颗胶囊(界面层)。[id] 稳定,是焦点目标的记号(见 [ShellFrame.focus])。 */
data class Capsule(
    val id: String,
    val label: String,
    val onClick: () -> Unit,
    val hint: String? = null,
    val trailing: Trailing = Trailing.None,
    val slider: SliderLook? = null,
    val onStep: ((Int) -> Unit)? = null,
    val leadingDot: Color? = null,
)

/**
 * **一列胶囊的焦点账本**(R69)。设置页外壳的每一层、关于页、输入源页都用它;每一层重建一份(R108 起由 FadeSwitch 按 `(栈深, 页)` 换层)。淡出中的残影([LocalPageGhost])当作 covered,且每颗 `canFocus = false`。
 * 七条铁律逐条落在:
 * - 铁律 2 / 4:焦点落没落下只信胶囊自报([holder]),`requestFocus()` 的返回值什么都不说明;
 * - 铁律 3:逐项挂 `FocusRequester`;本列自己负责初始焦点(定位效果)与丢焦点(看门狗),外层谁都不替它管;
 *   有东西盖在上面([covered]:选择器 / 扫码页 / 关于页 / 引导)时让路,盖着的那一层自己负责自己;
 * - 铁律 5:**目标**([target],住在 MainActivity 的栈帧里)与**当前**([holder])分开;目标只在「不在还原中」
 *   时跟着焦点走([onTarget]),还原期间(定位效果跑着、被盖着、`ON_PAUSE` 之后)冻结——Compose 抢先把焦点塞给
 *   第一颗的那次上报改写不了它;
 * - 铁律 6:定位效果的守卫 `covered` 在 key 里;看门狗三条守卫 `covered` / `restoring` / `holder == null` 全在 key 里;
 * - 铁律 7:没有一次性布尔闩——`restoring` 由 ON_PAUSE / 定位效果写真、由定位效果在前台落地后写假;看门狗每次再丢焦点
 *   key 翻转自动重新武装。
 *
 * **条件行**(「恢复隐藏的输入源」「动画缩放」)出现 / 消失:目标记的是 id,那一行还在就跟着它走;那一行自己没了,
 * 退回它原来的下标、夹到新长度。组合阶段发现 id 清单变了**同步**置 `restoring`(M4b fix round 1 同一手法:节点被摘
 * 那一帧 Compose 的焦点重定向会落到任意邻居,那次上报不能改写目标),收口交给以 `ids` 为 key 的定位效果——它只在
 * 目标**自报**落下时才收手。
 */
@Composable
fun CapsuleColumn(
    items: List<Capsule>,
    target: String?,
    onTarget: (String) -> Unit,
    nonce: Int,
    covered: Boolean,
) {
    if (items.isEmpty()) return
    // R108:淡出中的残影(见 SettingsFade.kt)当作被盖住——两个效果都让路,不抢焦点;下面每一颗再 canFocus = false。
    // 守卫与 key 读的是同一个合并后的 covered(铁律 6)。
    val ghost = LocalPageGhost.current
    @Suppress("NAME_SHADOWING")
    val covered = covered || ghost
    val ids = items.map { it.id }
    // 逐项一个 requester;id 清单一变整表换新。两个效果都在协程里跑,读的必须是当前这一份(rememberUpdatedState)。
    val reqs = remember(ids) { ids.map { FocusRequester() } }
    val requesters by rememberUpdatedState(reqs)
    var holder by remember { mutableStateOf<Int?>(null) }
    // 初值 true:第一帧 Compose 若自己把焦点给了某一颗,那次上报不能改写目标;定位效果落地后放开。
    var restoring by remember { mutableStateOf(true) }
    // 目标最近一次**还在**时的下标:目标 id 不在了(条件行消失)就落到它的上一行(评审 #6:原先退回同一下标,
    // 落的是消失那行的下一行——动画缩放行消失时焦点跳到「恢复默认」),夹到新长度。
    // 只在找到时更新,目标缺席期间的每次重组都算出同一个下标,不会一路往上走。普通数组,只在组合阶段读写,不引起重组。
    val lastFound = remember { intArrayOf(0) }
    val found = ids.indexOf(target)
    if (found >= 0) lastFound[0] = found
    val targetIdx = (if (found >= 0) found else lastFound[0] - 1).coerceIn(0, ids.lastIndex)
    val targetNow by rememberUpdatedState(targetIdx)
    val lastIds = remember { arrayOfNulls<List<String>>(1) }
    if (lastIds[0] != null && lastIds[0] != ids) restoring = true
    lastIds[0] = ids

    // **从 ON_PAUSE 就冻结目标**(铁律 5 后半句):跳系统设置 / 系统屏保页、灭屏再亮回来时,Compose 会抢在定位效果之前
    // 把焦点给第一颗;那次上报若看到 restoring 为假,目标就被改写成第一颗。放开交给定位效果(onResume 的 nonce++ 让它重跑)。
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) restoring = true }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // **定位效果**:进页 / 盖着的东西关掉(covered 落下)/ 回到前台(nonce)/ 条件行出现消失(ids)时,把焦点送到目标。
    LaunchedEffect(nonce, covered, ids) {
        if (covered) { restoring = true; return@LaunchedEffect }
        restoring = true
        var frames = 0
        // 退出判据:目标**自报**落下(铁律 2);只判「有没有焦点」的话,Compose 抢先给了第一颗就会提前退出。
        while (frames < 60 && holder != targetNow) {
            withFrameNanos { }
            runCatching { requesters[targetNow].requestFocus() }
            frames++
        }
        // 目标 id 已不在(条件行消失)时,把落下的那一颗写回成新目标,账本与画面一致。
        if (holder == targetNow) onTarget(ids[targetNow])
        // 只在前台时放开(同 SettingsScreen 终审 I1):不在前台就继续冻着,回到前台必经 nonce++,本效果必然重跑。
        restoring = !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    // **看门狗**(铁律 3):焦点莫名其妙没了(节点被重组销毁等)时送回目标。守卫三条全在 key 里(铁律 6)。
    LaunchedEffect(holder == null, covered, restoring, nonce) {
        if (covered || restoring || holder != null) return@LaunchedEffect
        // 上下换项时 lost / got 可能分属相邻两帧,中间那一帧的 null 不算丢。
        repeat(3) { withFrameNanos { } }
        var frames = 0
        while (holder == null && frames < 60) {
            runCatching { requesters[targetNow].requestFocus() }
            withFrameNanos { }
            frames++
        }
    }

    // 纵向排一列,间距按**量出来的**胶囊高度算(capsuleGap):优先 16 dp,整列放不进 500 dp 才等比缩小——
    // 「通用」组 8 颗、英文第一层说明文字折成两行时会缩。不滚动(铁律 1)。
    Layout(
        content = {
            items.forEachIndexed { i, c ->
                MenuPill(
                    label = c.label,
                    onClick = c.onClick,
                    modifier = Modifier.focusRequester(reqs[i]).focusProperties { if (ghost) canFocus = false },
                    onFocusChange = { got ->
                        // 得失顺序保护:只有「本项仍是持有者」时 lost 才作废。
                        if (got) {
                            holder = i
                            if (!restoring) onTarget(c.id)
                        } else if (holder == i) holder = null
                    },
                    isFirst = i == 0,
                    isLast = i == items.lastIndex,
                    hint = c.hint,
                    trailing = c.trailing,
                    slider = c.slider,
                    onStep = c.onStep,
                    leadingDot = c.leadingDot,
                    textStep = GtvLayout.SETTINGS_TYPE_STEP,
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gapPx = capsuleGap(placeables.map { it.height.toDp().value }).dp.roundToPx()
        val w = placeables.maxOf { it.width }
        val h = placeables.sumOf { it.height } + gapPx * (placeables.size - 1).coerceAtLeast(0)
        layout(w, h) {
            var y = 0
            placeables.forEach { p -> p.place(0, y); y += p.height + gapPx }
        }
    }
}

/**
 * 外壳的骨架:**左右 1:1**(与 `GearMenu` 的 `Row { weight(1f) / weight(1f) }` 一样,右边胶囊列中心在 x = 屏宽 3/4,
 * 与长按菜单完全重合——每一层切换时胶囊列的位置一动不动,变的只有内容)。**透明底**:外壳的 `MenuBg` 由
 * MainActivity 铺在首页那一层之下,预览框里露出的就是缩小的真首页(R73)。
 */
@Composable
fun ShellScaffold(left: @Composable BoxScope.() -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize().focusGroup()) {
        Box(Modifier.weight(1f).fillMaxHeight(), content = left)
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { right() }
    }
}

// R109:设置类页面的字号一律「基准 + GtvLayout.SETTINGS_TYPE_STEP」(settingsSp)。基准:路径 16、页名 32、说明 14 / 行距 20。
private val pathStyle = TextStyle(fontFamily = Theme.Sans, color = Theme.SecondaryText, fontSize = GtvLayout.settingsSp(16f).sp)
private val titleStyle = TextStyle(
    fontFamily = Theme.Sans,
    fontWeight = FontWeight.Medium,
    color = Theme.EmphasisText,
    fontSize = GtvLayout.settingsSp(GtvLayout.SETTINGS_TITLE_TEXT).sp,
    lineHeight = (GtvLayout.settingsSp(GtvLayout.SETTINGS_TITLE_TEXT) * 1.2f).sp,
)
internal val shellBodyStyle = TextStyle(
    fontFamily = Theme.Sans, color = Theme.SecondaryText,
    fontSize = GtvLayout.settingsSp(14f).sp, lineHeight = GtvLayout.settingsSp(20f).sp,
)

/**
 * 没有预览的页:路径(小字灰)+ 页名(32 sp)放在左半屏正中(效果图 README 第 4 条「照 M1 的做法」);
 * [extra] 是页名下方的说明 / 信息块(默认桌面、恢复默认、关于、屏保启动的提示)。
 */
@Composable
fun BoxScope.ShellTitle(path: String?, title: String, extra: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier.align(Alignment.Center).widthIn(max = PREVIEW_WIDTH_DP.dp).padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (path != null) {
            BasicText(path, style = pathStyle.copy(textAlign = TextAlign.Center))
            Spacer(Modifier.height(4.dp))
        }
        BasicText(title, style = titleStyle.copy(textAlign = TextAlign.Center))
        if (extra != null) {
            Spacer(Modifier.height(20.dp))
            extra()
        }
    }
}

/**
 * 有预览的页(R73):路径 + 页名压在预览框上方;预览框本身是 MainActivity 缩小进来的真首页,这里只画 1 dp 14% 白
 * 描边(产品默认无壁纸时首页底色与 `MenuBg` 几乎一样,没有描边预览会融进背景,README 第 3 条);框下方一行
 * 「● 预览:大 · 按确定保存,按返回不改」只在有未保存预览时出现([pending])。几何全读 [previewRect],与
 * MainActivity 缩放首页那一层同一个函数,第一帧就对齐。
 */
@Composable
fun BoxScope.ShellPreviewFrame(path: String, title: String, pending: String?) {
    val cfg = LocalConfiguration.current
    val r = previewRect(cfg.screenWidthDp.toFloat(), cfg.screenHeightDp.toFloat())
    Box(
        modifier = Modifier.padding(start = r.x.dp).width(r.width.dp).height((r.y - 16f).coerceAtLeast(0f).dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column {
            BasicText(path, style = pathStyle)
            Spacer(Modifier.height(4.dp))
            BasicText(title, style = titleStyle)
        }
    }
    Box(
        Modifier
            .offset(r.x.dp, r.y.dp)
            .size(r.width.dp, r.height.dp)
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(GtvLayout.CARD_CORNER.dp)),
    )
    if (pending != null) {
        Row(
            modifier = Modifier.offset(r.x.dp, (r.y + r.height + 12f).dp).width(r.width.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(LocalThemeColors.current.accent))
            Spacer(Modifier.width(8.dp))
            BasicText(pending, maxLines = 1, style = shellBodyStyle)
        }
    }
}

/**
 * **设置页胶囊外壳**(R69,Gordon 2026-09-23 定案;取代 M7 起的「UnitedU 设置」两栏浮层与齿轮菜单第一层)。
 * 画栈顶那一层:第一层(6 颗两行胶囊)/ 四个分组页 / 子页(R128「待机」)/ 选项层 / 默认桌面页 / 恢复默认确认页。
 * 每一页的胶囊 ≤ [MAX_CAPSULES_PER_PAGE](R128,清单见 [pageCapsuleIds],单测逐页数)。
 * 导航栈住在 MainActivity([stack],理由见 [ShellFrame]),这里只经 [onPush] / [onPop] / [onFocus] 改它。
 *
 * - 分组页:可改值的行显示当前值(同一行右对齐、更淡);选项行进选项层;滑块行聚焦时变成滑块、左右键即时调值并落盘(R72);
 *   动作行打开各自的整屏界面(编辑页、换壁纸、屏保图库、手机传输)或跳系统页。
 * - 选项层:每一档一颗胶囊,已保存档 ✓;光标移到哪,MainActivity 的 `effectiveSettings` 就把首页预览成哪(R71);
 *   **确定 = 落盘 + 回上一层,返回 = 什么都不写回上一层**。
 * - 写盘一律 `SettingsStore.update`(有锁;每行只改自己那个字段),写完 [onWritten](MainActivity `settingsRevision++`,
 *   首页与本页都按新值重读)。
 */
@Composable
fun SettingsShell(
    stack: List<ShellFrame>,
    /** 盘上已保存的设置(不含预览)——✓ 与值都读它。 */
    saved: Settings,
    actions: SettingsActions,
    onPush: (page: String, focus: String?) -> Unit,
    onPop: () -> Unit,
    onFocus: (String) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onConfirmRestore: () -> Unit,
    onChangeHome: () -> Unit,
    onWritten: () -> Unit,
    focusNonce: Int,
    /** 有东西盖在外壳之上(选择器 / 扫码页 / 关于页 / 引导):让路,焦点归那一层(铁律 3)。 */
    covered: Boolean,
    galleryVersion: Int,
    revision: Int,
) {
    val ctx = LocalContext.current
    if (stack.isEmpty()) return

    // 屏保图库张数(「屏保启动」选项层的提示要分「图库为空」)、隐藏的输入源数(「恢复隐藏的输入源」条件行)、
    // 系统设置快照(「系统屏保」摘要与「动画缩放」条件行)——与旧设置页同一组 key 习惯:盖着的东西关掉时重数,
    // 回到前台(focusNonce)重读系统快照。−1 = 还没数完,模型按「非空 / 不露出」处理。
    // R117 起数的是**轮播**(参与的内置图 + 用户图库),不是图库:内置图全关掉、图库又空,「屏保启动」同样提示为空。
    val screensaverImages by produceState(-1, galleryVersion, covered) {
        value = withContext(Dispatchers.IO) { safePlaylist(ctx)?.size ?: 0 }
    }
    val systemStatus = remember(focusNonce, covered) { readSystemUiStatus(ctx) }

    val written by rememberUpdatedState(onWritten)
    // 最近一次写盘成没成功:选项层据此决定回不回上一层(写失败就留在原地,提示过了也不假装保存了)。
    val lastWriteOk = remember { booleanArrayOf(true) }
    fun update(transform: (Settings) -> Settings) {
        val ok = SettingsStore.update(ctx, transform) != null
        lastWriteOk[0] = ok
        if (!ok) {
            Log.w(LOG_TAG, "settings.json 写入失败")
            // 交互测试 2026-09-23(评审 #2):写失败原先只进日志,人看到的是「按了确定、回了上一层、值没变」。
            android.widget.Toast.makeText(ctx, R.string.toast_storage_not_ready, android.widget.Toast.LENGTH_SHORT).show()
        }
        written()
    }
    // 切语言绕过本页直接写盘(MainActivity.applyLanguage);没有重建(同一 Locale 等价类)的那条路也要让 saved 跟上。
    val liveActions = remember(actions) {
        SettingsActions(
            openEdit = actions.openEdit,
            pickWallpaper = actions.pickWallpaper,
            openImport = actions.openImport,
            setDefaultHome = actions.setDefaultHome,
            applyLanguage = { lang -> actions.applyLanguage(lang); written() },
            openScreensaverGallery = actions.openScreensaverGallery,
            openSystemScreensaver = actions.openSystemScreensaver,
            openSystemScreenOff = actions.openSystemScreenOff,
            startScreensaver = actions.startScreensaver,
            openSystemAnimationSettings = actions.openSystemAnimationSettings,
        )
    }
    val groups = settingsGroups(saved, ::update, liveActions, screensaverImages, systemStatus)

    // 返回键 = 回上一层(第一层再按 = 关掉设置)。叠在外壳之上的选择器 / 关于页各自的 BackHandler 注册得更晚,先接管。
    // 淡出中的残影(R108)不收返回键。
    val shellGhost = LocalPageGhost.current
    androidx.activity.compose.BackHandler(enabled = !shellGhost) { onPop() }

    val settingsTitle = stringResource(R.string.menu_settings_title)

    // **层与层之间交叉淡化(R108)**:改前是 `key(栈深, 页)` 整层重建;现在同一个 key 交给 FadeSwitch——key 变了,
    // 旧层变残影 150 ms 淡出(输入冻结在它最后那条栈,不可聚焦、不回调),新层是全新的组合、150 ms 淡入,
    // 它的胶囊列照旧自己把焦点落到目标。同一层里焦点移动(栈顶帧的 focus 变)key 不变,原地更新。
    FadeSwitch(
        state = stack,
        enterMs = GtvLayout.SETTINGS_LAYER_FADE_MS,
        exitMs = GtvLayout.SETTINGS_LAYER_FADE_MS,
        scaleFrom = GtvLayout.SETTINGS_LAYER_SCALE,  // R113:层间更轻的放大
        contentKey = { s -> s.size to s.last().page },
    ) { layerStack ->
        val ghost = LocalPageGhost.current
        // 残影不改 MainActivity 的任何状态(栈、焦点目标、设置外壳的开关)。
        val onPush: (String, String?) -> Unit = if (ghost) { _, _ -> } else onPush
        val onPop: () -> Unit = if (ghost) ({}) else onPop
        val onFocus: (String) -> Unit = if (ghost) { _ -> } else onFocus
        val top = layerStack.last()
        val target = top.focus ?: defaultFocus(top.page, groups)
        val groupId = ShellPages.groupOf(top.page)
        val optionsRow = ShellPages.optionsRow(top.page)
        val subRow = ShellPages.subRow(top.page)
        when {
            top.page == ShellPages.ROOT -> {
                val items = SHELL_ROOT.map { e ->
                    Capsule(
                        id = e.id,
                        label = stringResource(e.labelRes),
                        hint = stringResource(e.hintRes),
                        // 右端 › = 进下一层;「系统设置」直接跳安卓原生设置,不进外壳的下一层,不画 ›。
                        trailing = if (e.id == ShellPages.SYSTEM_SETTINGS) Trailing.None else Trailing.Chevron,
                        onClick = {
                            when (e.id) {
                                ShellPages.SYSTEM_SETTINGS -> onOpenSystemSettings()
                                ShellPages.ABOUT -> onOpenAbout()
                                else -> onPush(e.id, defaultFocus(e.id, groups))
                            }
                        },
                    )
                }
                ShellScaffold(
                    left = { ShellTitle(path = null, title = settingsTitle) },
                    right = { CapsuleColumn(items, target, onFocus, focusNonce, covered) },
                )
            }

            groupId != null -> {
                val spec = groups.first { it.id == groupId }
                val items = spec.rows.map { row -> groupCapsule(row, onPush, followingWallpaper = saved.followWallpaperColor) }
                val title = stringResource(spec.titleRes)
                ShellScaffold(
                    left = {
                        if (pageHasPreview(top.page)) ShellPreviewFrame(settingsTitle, title, pending = null)
                        else ShellTitle(settingsTitle, title)
                    },
                    right = { CapsuleColumn(items, target, onFocus, focusNonce, covered) },
                )
            }

            // **子页**(R128「待机」):与分组页同一个画法、同一套胶囊列;行就是子页入口里的那几行(id 不变),
            // 选项行照旧进选项层,返回落回这里进入时那颗;这一页返回落回分组页的子页入口那颗(父帧的目标原样留着)。
            // 理论上不会找不到(只能从现存的入口进来);万一没了,退回上一层,绝不留一个空列。
            subRow != null && subPageRow(groups, subRow) == null -> LaunchedEffect(Unit) { onPop() }

            subRow != null -> {
                val sub = subPageRow(groups, subRow)!!
                val path = (listOf(settingsTitle) + rowParents(groups, sub.id).map { stringResource(it) }).joinToString(" · ")
                val items = sub.rows.map { row -> groupCapsule(row, onPush, followingWallpaper = saved.followWallpaperColor) }
                ShellScaffold(
                    left = { ShellTitle(path, stringResource(sub.labelRes)) },
                    right = { CapsuleColumn(items, target, onFocus, focusNonce, covered) },
                )
            }

            // 理论上不会(选项层只能从现存的行进来);万一那一行没了,退回上一层,绝不留一个空列。
            optionsRow != null && controlRow(groups, optionsRow) == null -> LaunchedEffect(Unit) { onPop() }

            optionsRow != null -> {
                val row = controlRow(groups, optionsRow)!!
                // 路径 = 设置 · 所属组(· 子页,R128:待机两行的选项层是「设置 · 通用 · 待机」)。
                val path = (listOf(settingsTitle) + rowParents(groups, optionsRow).map { stringResource(it) }).joinToString(" · ")
                val title = stringResource(row.labelRes)
                val items = optionOrder(row).map { i ->
                    Capsule(
                        id = optionId(i),
                        label = optionLabel(row, i),
                        trailing = if (i == row.selected) Trailing.Check else Trailing.None,
                        leadingDot = if (row.kind == CtrlKind.SWATCH) ThemePresets.all.getOrNull(i)?.color else null,
                        onClick = {
                            when {
                                i == row.selected -> onPop()
                                // **语言:先回上一层,再落盘**:切语言会当场 recreate(),onSaveInstanceState 要存下的是
                                // 已经回到父层的栈(重建后落在「语言」那颗胶囊上,而不是又停在选项层)。
                                row.id == "language" -> { onPop(); row.onSelect(i) }
                                // 其余:先写,写成了才回上一层(评审 #2);写失败留在选项层,update 已经弹过提示。
                                else -> {
                                    lastWriteOk[0] = true
                                    row.onSelect(i)
                                    if (lastWriteOk[0]) onPop()
                                }
                            }
                        },
                    )
                }
                val cursor = optionIndex(target)
                // 跟随壁纸主色开着时,主题色的光标预览不代表首页会变成那样(壁纸取色压过预设),不显示「预览:…」(评审 #7)。
                val swatchOverridden = row.kind == CtrlKind.SWATCH && saved.followWallpaperColor
                val pending = if (cursor != null && cursor != row.selected && !swatchOverridden) {
                    stringResource(R.string.shell_preview_hint, optionLabel(row, cursor))
                } else null
                val note = row.noteRes?.let { stringResource(it) }
                ShellScaffold(
                    left = {
                        if (pageHasPreview(top.page)) ShellPreviewFrame(path, title, pending)
                        else ShellTitle(path, title, extra = note?.let { { BasicText(it, style = shellBodyStyle.copy(textAlign = TextAlign.Center)) } })
                    },
                    right = { CapsuleColumn(items, target, onFocus, focusNonce, covered) },
                )
            }

            top.page == ShellPages.HOME -> {
                // 取代 M7 的 HomeSettingsCard 浮层(R74):左边当前默认桌面 + 说明,右边一颗「在系统设置中更改」。
                val home = rememberCurrentHome(revision, focusNonce)
                val items = HOME_CAPSULES.map { id ->
                    Capsule(id, stringResource(R.string.home_settings_change_button), onClick = onChangeHome)
                }
                ShellScaffold(
                    left = {
                        ShellTitle(settingsTitle + " · " + stringResource(R.string.settings_group_general), stringResource(R.string.home_settings_title)) {
                            Column(Modifier.width(360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                CurrentHomeRow(home, textStep = GtvLayout.SETTINGS_TYPE_STEP)
                                Spacer(Modifier.height(14.dp))
                                BasicText(stringResource(R.string.home_settings_note), style = shellBodyStyle.copy(textAlign = TextAlign.Center))
                            }
                        }
                    },
                    right = { CapsuleColumn(items, target, onFocus, focusNonce, covered) },
                )
            }

            top.page == ShellPages.RESTORE -> {
                // 取代「恢复默认」ConfirmDialog(R74):默认焦点「取消」、破坏性动作在下面那颗(spec §4 终审)。
                // R128 起从关于页的「恢复默认」进来(关于页此时让开,见 aboutPageShown):取消 / 返回 / 确定都弹栈,
                // 关于页重新出现、落回「恢复默认」那颗。
                val items = RESTORE_CAPSULES.map { id ->
                    if (id == SHELL_CANCEL) Capsule(id, stringResource(R.string.dialog_cancel), onClick = onPop)
                    else Capsule(id, stringResource(R.string.restore_ok), onClick = { onPop(); onConfirmRestore() })
                }
                ShellScaffold(
                    left = {
                        ShellTitle(settingsTitle + " · " + stringResource(R.string.menu_about), stringResource(R.string.restore_title)) {
                            BasicText(stringResource(R.string.restore_body), style = shellBodyStyle.copy(textAlign = TextAlign.Center))
                        }
                    },
                    right = { CapsuleColumn(items, target, onFocus, focusNonce, covered) },
                )
            }

            else -> LaunchedEffect(Unit) { onPop() }
        }
    }
}

/** 选项第 i 档的文案(带参数的「%1$d 分」之类按 [ControlRow.optionArgs] 解析)。 */
@Composable
private fun optionLabel(row: ControlRow, i: Int): String {
    val res = row.optionRes.getOrNull(i) ?: return ""
    val arg = row.optionArgs.getOrNull(i)
    return if (arg != null) stringResource(res, arg) else stringResource(res)
}

/**
 * 右端不画 › 的动作行:跳安卓原生设置页的(不进外壳的下一层,Gordon 定案第 1 条的例外),
 * 以及按下去当场生效、不打开任何界面的。› 只表示「会进到另一个界面」。
 */
private val JUMP_ROWS = setOf("systemScreensaver", "screenOff", "systemAnimationScale", "startScreensaver")

/**
 * 滑块展开时的短标签(交互测试 2026-09-23):聚焦的滑块胶囊里标签只剩约 104 dp,英文
 * 「Wallpaper Blur / Wallpaper Brightness」都截成「Wallpaper B…」分不清;没聚焦的胶囊仍显示全称。
 */
private val SLIDER_SHORT_LABEL = mapOf(
    "wallpaperBlur" to R.string.shell_slider_wallpaper_blur,
    "wallpaperBrightness" to R.string.shell_slider_wallpaper_brightness,
    "cardSaturation" to R.string.shell_slider_card_saturation,
    "cardBrightness" to R.string.shell_slider_card_brightness,
    "cardOpacity" to R.string.shell_slider_card_opacity,
)

/** 分组页的一颗胶囊。 */
@Composable
private fun groupCapsule(row: RowSpec, onPush: (String, String?) -> Unit, followingWallpaper: Boolean = false): Capsule {
    val label = stringResource(row.labelRes)
    return when (row) {
        is ControlRow -> if (row.kind == CtrlKind.SLIDER) {
            val last = (row.count - 1).coerceAtLeast(1)
            val text = sliderText(row)
            Capsule(
                id = row.id,
                label = label,
                // 滑块上确定键不做事(R72):调值即落盘,没有「确认」这一步。
                onClick = {},
                trailing = Trailing.Value(text),
                slider = SliderLook(
                    fraction = row.selected / last.toFloat(),
                    zero = row.zeroAt / last.toFloat(),
                    text = text,
                    canDecrease = row.selected > 0,
                    canIncrease = row.selected < row.count - 1,
                    label = SLIDER_SHORT_LABEL[row.id]?.let { stringResource(it) },
                ),
                onStep = { d -> val n = sliderStep(row, d); if (n != row.selected) row.onSelect(n) },
            )
        } else {
            Capsule(
                id = row.id,
                label = label,
                trailing = if (row.kind == CtrlKind.SWATCH && followingWallpaper) {
                    // 跟随壁纸主色开着:首页用的不是这个预设,值写「跟随壁纸」、不画预设色点(评审 #7)。
                    Trailing.Value(text = stringResource(R.string.shell_theme_following_wallpaper))
                } else Trailing.Value(
                    text = optionLabel(row, row.selected),
                    dot = if (row.kind == CtrlKind.SWATCH) ThemePresets.all.getOrNull(row.selected)?.color else null,
                ),
                onClick = { onPush(ShellPages.options(row.id), optionId(row.selected)) },
            )
        }
        is SubPageRow -> {
            // R128:右端是子页里各行当前值的摘要(「3 分 · 时钟」)——与那几行自己显示的值同一份文案,「 · 」连接
            // (同「系统屏保」摘要的连法)。不画 ›:与它合并掉的两行一样只显示值(可改值的行点进去也是另一层,同样不画 ›)。
            val summary = row.rows.filterIsInstance<ControlRow>().map { r ->
                if (r.kind == CtrlKind.SLIDER) sliderText(r) else optionLabel(r, r.selected)
            }.joinToString(" · ")
            Capsule(
                id = row.id,
                label = label,
                trailing = Trailing.Value(summary),
                onClick = { onPush(ShellPages.sub(row.id), row.rows.firstOrNull()?.id) },
            )
        }
        is ActionRow -> {
            // 带参数 / 分段的值(「2 个」「开 · UnitedU · 5 分钟」「1.25×,界面动画会变慢」)显示在右端;
            // 只有一句说明的动作行(换壁纸、手机传输……)不显示——一整句塞不进 268 dp 的胶囊,› 已经说明「会打开东西」。
            val value: String? = when {
                row.hintParts.isNotEmpty() -> row.hintParts.map { part ->
                    when (part) {
                        is HintPart.Text -> part.text
                        is HintPart.Res ->
                            if (part.args.isEmpty()) stringResource(part.id)
                            else stringResource(part.id, *part.args.toTypedArray())
                    }
                }.joinToString(" · ")
                row.hintRes != null && row.hintArgs.isNotEmpty() -> stringResource(row.hintRes, *row.hintArgs.toTypedArray())
                else -> null
            }
            val jump = row.id in JUMP_ROWS
            Capsule(
                id = row.id,
                label = label,
                // R127:标签下方的小字(「关闭屏幕」行写去哪改),与第一层的说明小字同一个样式;值照旧在右端。
                hint = row.noteRes?.let { stringResource(it) },
                trailing = when {
                    value != null -> Trailing.Value(value, chevron = !jump)
                    jump -> Trailing.None
                    else -> Trailing.Chevron
                },
                onClick = row.onActivate,
            )
        }
    }
}
