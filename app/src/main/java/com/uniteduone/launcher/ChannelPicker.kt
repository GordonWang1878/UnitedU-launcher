package com.uniteduone.launcher

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 胶囊阶段(拒绝 / 没频道)那一颗按钮在 [ChannelPicker] 焦点账本里的「id」(频道 id 都是正数)。 */
private const val PILL_ID = -1L

/**
 * R164 §3.1 选频道页(编辑页 `OverlayStack` 里的一层 `EditOverlay.ChannelPick`,状态并进 `overlayOpen`)。版式照 [AppPicker]:
 * 整屏两栏、右边一列 `LazyColumn`(与 AppPicker 同一处获准的可滚动容器)、每项是同一种小卡片行([PickerRow],画发布方的卡片图)。
 *
 * **焦点账本(本页自己负责恢复,编辑页的看门狗在 overlayOpen 时让路)**,照应用页 `AppsPage`:
 * - 逐项 requester(铁律 3 的实现约束:LazyColumn 会回收滚出视野的项);requester 表按「可聚焦项的 id 清单」换新。
 * - 目标 [focusedIdx](位置)与持有者 [holder](频道 id;胶囊 = [PILL_ID])分开(铁律 5);目标只在不还原([restoring] 为假)时
 *   跟着自报走。`restoring` 初值真、`ON_PAUSE` 起冻结(授权窗 / 系统设置 / 别的应用回来时 Compose 会抢先给第一项)。
 * - 列表换数据(TvProvider 变了 → ChannelCache 刷新)在**一个效果里**先置 `restoring`、按频道 id 重算目标([retargetById])、
 *   再换上新表——被删的那一项节点一拆,系统当场派给别的项的那次上报改写不了刚算好的目标。
 * - 定位效果 key `(nonce, ghost, ids)`,退出判据是目标自报(铁律 2),末尾只在 RESUMED 时放开 `restoring`;
 *   `holder == null` 看门狗(3 帧宽限、60 帧封顶),守卫与 key 同为 `ghost` / `restoring` / `holder == null` / 空表(铁律 6),
 *   再丢一次 key 翻转重新武装(铁律 7)。
 * - 残影让路([LocalPageGhost]):不请求、不可聚焦、不回调、不弹授权窗。返回键由编辑页唯一的 `BackHandler` 收,本页不接。
 *
 * 授权:进页没有 `READ_TV_LISTINGS` → 先弹系统授权窗(一次);拒绝 / 返回关窗 → 说明 +「去系统设置开启」,只有永久拒绝
 * (系统没弹窗)才自动跳一次系统设置(owner 裁定 2026-10-09,[permissionResult]);回来由 onResume 的 `channelsRevision++`
 * 重读授权。已授权 → 给还没通知过的包发 `INITIALIZE_PROGRAMS`([ChannelInit]);列表只读进程级 [ChannelCache]
 * (owner 裁定:与首页 / 编辑页同一份),TvProvider 一变(ContentObserver → channelsRevision → 缓存刷新)自动补上。
 */
@Composable
internal fun ChannelPicker(
    nonce: Int,
    onLayout: List<ChannelRef>,
    onPick: (ChannelRef) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val env = LocalChannelEnv.current
    val ghost = LocalPageGhost.current
    var granted by remember { mutableStateOf(ChannelSource.hasPermission(ctx)) }
    /** 系统授权窗已经弹过(本页每次打开只弹一次)。 */
    var requested by remember { mutableStateOf(false) }
    /**
     * 授权窗**已经回话**(= [channelPickerPhase] 的 asked)。不能在弹窗那一刻就置真:窗还盖着时页面就会画成拒绝态
     * (「去系统设置开启」胶囊还抢着落焦),模拟器实测。回话之前一直是 Asking(右边空着)。
     */
    var asked by remember { mutableStateOf(false) }
    // 授权结果 / 从系统设置回来:env.revision 变了就重读授权
    LaunchedEffect(env.revision) { granted = ChannelSource.hasPermission(ctx) }
    LaunchedEffect(granted, requested, ghost) {
        if (!granted && !requested && !ghost) {
            requested = true
            env.requestPermission { r ->
                asked = true
                when (r) {
                    PermissionResult.GRANTED -> granted = true
                    // owner 裁定(2026-10-09):只有永久拒绝(系统没弹窗)才自动跳系统设置;回来仍是 Denied(说明 +「去系统设置开启」)
                    PermissionResult.DENIED_PERMANENTLY -> env.openPermissionSettings()
                    PermissionResult.DENIED -> Unit   // 点了拒绝 / 返回关窗:留在本页,Denied 阶段
                }
            }
        }
    }
    LaunchedEffect(granted) {
        if (granted) withContext(Dispatchers.IO) { runCatching { ChannelInit.notifyPending(ctx) } }
    }
    // 缓存还没读过、或还是授权前那一份(permitted = false,授权结果触发的刷新在途)→ null = Loading
    val snap by ChannelCache.data.collectAsState()
    val fresh = remember(snap, granted, onLayout) {
        snap?.takeIf { granted && it.permitted }?.let { pickerChannels(it.channels, onLayout, it.labels, ctx.packageName) }
    }
    /** 画出来的那一份;只经下面的换数据效果改(先冻结目标再换,见类注释)。 */
    var shown by remember { mutableStateOf(fresh) }
    var focusedIdx by remember { mutableStateOf(0) }
    var holder by remember { mutableStateOf<Long?>(null) }
    var restoring by remember { mutableStateOf(true) }

    val phase = channelPickerPhase(granted, asked, shown)
    val list = (phase as? ChannelPickerPhase.Ready)?.items.orEmpty()
    /** 此刻可聚焦的项(按顺序):列表阶段是频道 id,拒绝 / 没频道阶段是那一颗胶囊,问授权 / 读取中一个都没有。 */
    val ids = when (phase) {
        is ChannelPickerPhase.Ready -> list.map { it.channel.id }
        ChannelPickerPhase.Denied, ChannelPickerPhase.Empty -> listOf(PILL_ID)
        else -> emptyList()
    }
    val idsNow by rememberUpdatedState(ids)
    val reqs = remember(ids) { List(ids.size) { FocusRequester() } }
    val requesters by rememberUpdatedState(reqs)

    LaunchedEffect(fresh) {
        if (fresh == shown) return@LaunchedEffect
        val oldIds = shown.orEmpty().map { it.channel.id }
        val newIds = fresh.orEmpty().map { it.channel.id }
        if (oldIds != newIds) {
            restoring = true
            focusedIdx = retargetById(oldIds, newIds, focusedIdx)
        }
        shown = fresh
    }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) restoring = true }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // **定位效果**:进页 / 换阶段或换列表(ids)/ 回到前台(nonce)时把焦点送到目标。退出判据只信目标自报(铁律 2)。
    LaunchedEffect(nonce, ghost, ids) {
        // 持有者的节点可能已随列表 / 阶段变化被拆掉(被拆时不一定收到「失去」回调):不在当前这一份里就当没有持有者
        if (holder != null && holder !in ids) holder = null
        if (ghost) { restoring = true; return@LaunchedEffect }
        if (ids.isEmpty()) return@LaunchedEffect
        restoring = true
        var frames = 0
        while (frames < 60) {
            val i = focusedIdx.coerceIn(0, ids.lastIndex)
            if (holder == ids[i]) break
            withFrameNanos { }
            runCatching { requesters[i].requestFocus() }
            frames++
        }
        restoring = !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    // **看门狗**(铁律 3):落地之后焦点莫名其妙没了时送回目标。守卫全在 key 里(铁律 6);每轮 60 帧封顶,再丢一次 key 翻转重新武装(铁律 7)。
    LaunchedEffect(holder == null, ghost, restoring, ids.isEmpty(), nonce) {
        if (ghost || restoring || holder != null || ids.isEmpty()) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }   // 换项时 lost / got 可能分属相邻两帧,中间那一帧的 null 不算丢
        var frames = 0
        while (holder == null && frames < 60) {
            val rs = requesters
            if (rs.isEmpty()) break
            runCatching { rs[focusedIdx.coerceIn(0, rs.lastIndex)].requestFocus() }
            withFrameNanos { }
            frames++
        }
    }
    fun report(id: Long, i: Int, got: Boolean) {
        if (got) {
            holder = id
            if (!restoring && idsNow.getOrNull(i) == id) focusedIdx = i
        } else if (holder == id) holder = null
    }

    Box(Modifier.fillMaxSize().focusGroup().pageBackdrop()) {
        val status = when (phase) {
            ChannelPickerPhase.Loading -> stringResource(R.string.channel_picker_loading)
            ChannelPickerPhase.Empty -> stringResource(R.string.channel_picker_empty) + "\n" + stringResource(R.string.channel_picker_empty_hint)
            ChannelPickerPhase.Denied -> stringResource(R.string.channel_picker_denied)
            else -> null
        }
        val hint = stringResource(R.string.channel_picker_hint)
        ShellScaffold(
            left = {
                ShellTitle(
                    path = null,
                    title = stringResource(R.string.channel_picker_title),
                    extra = {
                        Column {
                            ShellBody(hint)
                            if (status != null) {
                                // 两段说明之间空一行的高度:不空的话「没有权限」一段紧贴在隐私说明下面,读起来像同一句(模拟器截图)
                                Spacer(Modifier.height(12.dp))
                                ShellBody(status)
                            }
                        }
                    },
                )
            },
            right = {
                when (phase) {
                    // 与 AppPicker 同一处获准的可滚动容器(单列 LazyColumn,逐项 requester + 每行四向锁边界,见 PickerRow)
                    is ChannelPickerPhase.Ready -> LazyColumn(
                        modifier = Modifier.width(GtvLayout.PICKER_LIST_WIDTH.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        contentPadding = PaddingValues(vertical = GtvLayout.PICKER_LIST_PAD_V.dp),
                    ) {
                        itemsIndexed(list, key = { _, c -> c.channel.id }) { i, c ->
                            val id = c.channel.id
                            PickerRow(
                                app = AppEntry(
                                    packageName = c.channel.pkg,
                                    label = ctx.getString(R.string.channel_row_title, c.appLabel, c.channel.name),
                                    card = null,
                                    isWide = false,
                                ),
                                header = null,
                                modifier = Modifier
                                    .focusRequester(reqs[i.coerceIn(0, reqs.lastIndex)])
                                    .focusProperties { if (ghost) canFocus = false },
                                onFocusChange = { got -> if (!ghost) report(id, i, got) },
                                isFirst = i == 0,
                                isLast = i == list.lastIndex,
                                onClick = { if (!ghost) onPick(refFor(c.channel)) },
                            )
                        }
                    }
                    ChannelPickerPhase.Denied, ChannelPickerPhase.Empty -> {
                        val denied = phase == ChannelPickerPhase.Denied
                        MenuPill(
                            label = stringResource(if (denied) R.string.channel_picker_open_settings else R.string.channel_picker_back),
                            onClick = { if (!ghost) { if (denied) env.openPermissionSettings() else onBack() } },
                            modifier = Modifier
                                .focusRequester(reqs[0])
                                .focusProperties { if (ghost) canFocus = false },
                            onFocusChange = { got -> if (!ghost) report(PILL_ID, 0, got) },
                            isFirst = true,
                            isLast = true,
                        )
                    }
                    else -> Unit   // Asking / Loading:右边空着,系统授权窗或读取在途
                }
            },
        )
    }
}
