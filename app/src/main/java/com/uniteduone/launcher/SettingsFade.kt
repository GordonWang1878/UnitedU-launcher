package com.uniteduone.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * R113(2026-09-28 owner:淡入淡出「依然看不出来」):A95L 录屏证实动画在跑(60 fps、约 0.4 s),看不出来是因为
 * 暗对暗 + FastOutSlowIn 前 150 ms 就完成约 80% + 画面里没有东西在动。改为对称缓入缓出 + 轻微放大。
 */
val SettingsFadeEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/*
 * **R108 设置类页面的淡入淡出**(Gordon 2026-09-27)。
 *
 * **焦点怎么处理(核心决定)**:逻辑状态**当场**关掉,画面另起一层「残影」淡出。
 * - 关页的那一刻 MainActivity 的状态(`shellStack` / `about` / `appsPage` / `inputsPage`)照旧立即变,`focusNonce++`、
 *   首页 `previewing` 立即为假——首页的还原效果与改前在**同一帧**开始把焦点送回目标,落点与改前逐位一致(铁律 2 / 3 / 5)。
 * - 残影 = 同一个页面组合继续留在树里,输入冻结在关掉前最后一份(外壳是最后那条栈、关于页是最后那个状态),
 *   外面包一层 [LocalPageGhost] = true。读它的地方只有这几处,各管一件事:
 *   ① 胶囊列 [CapsuleColumn] / 应用页网格:**当作 covered**——定位效果与看门狗让路,不去抢焦点(铁律 3 的「让路」口子,
 *      守卫与 key 同一个量,铁律 6);**每一颗胶囊 / 卡片 `canFocus = false`**——方向键搜不进来,`requestFocus()` 落不下;
 *      关掉那一刻若焦点还在残影里的某一颗上,Compose 当场把它清掉(与改前「节点被拆」同一条路,随后由首页还原接走);
 *   ② 页面自己的 `BackHandler` 关掉——返回键只归活着的那一层;
 *   ③ 残影不回调任何会改 MainActivity 状态的东西(外壳的 push / pop / focus,应用页的焦点上报)。
 *   所以淡出那 150 ms 里按键:落在首页(焦点已经回去了)或活着的下一层,**绝不会**落进残影,也不会出现
 *   「焦点先跑到首页卡片又跳回」——残影里没有任何东西能拿到焦点。
 * - 不做成位图快照:Compose 1.7 的 GraphicsLayer 录的是子节点 RenderNode 的引用,节点一拆就空了;读回像素要等一帧以上。
 *   同一棵树继续画、只是不可交互,最简单也最准。
 *
 * **外壳层与层之间**(进下一层 / 返回):同一套 [FadeSwitch],旧层变残影 150 ms 淡出、新层 150 ms 淡入,
 * 新层的胶囊列照旧自己把焦点落到目标(与改前 `key(栈深, 页)` 整层重建是同一个初始焦点流程)。
 *
 * **HOME 一次收起所有层**:同样淡出(150 ms),不另开「直接消失」一条路——状态照旧一次清空,残影各自淡出。
 */

/** 为真 = 本页是正在淡出的残影:不可聚焦、不抢焦点、不收返回键、不回调(见文件头)。外层为真时里层一律为真。 */
val LocalPageGhost = compositionLocalOf { false }

/**
 * [FadeSwitch] 的账本(纯逻辑,JVM 可测)。每一项 = 一次「打开」:[gen] 只增不减,当作组合的 key——
 * 关掉又立刻打开得到的是**新的一项**(全新的组合,焦点从头落,与改前每次打开都是新组合一致),旧的那项照样淡出。
 */
class FadeBook<S : Any> {
    class Entry<S>(val gen: Int, val key: Any?, state: S) {
        /** 活着的那一项跟着最新输入走;变成残影后冻结在最后一份。 */
        var state: S = state
            internal set
        var live: Boolean = true
            internal set
    }

    private val _entries = mutableListOf<Entry<S>>()
    val entries: List<Entry<S>> get() = _entries
    private var nextGen = 0

    val live: Entry<S>? get() = _entries.lastOrNull { it.live }

    /**
     * 对齐到当前输入:[state] 为 null = 关掉(活着的那项变残影);key 与活着的那项相同 = 同一页,原地更新输入;
     * 不同 = 旧的变残影、新开一项。返回是否新开了一项。
     */
    fun sync(state: S?, key: Any?): Boolean {
        val cur = live
        if (state == null) { cur?.live = false; return false }
        if (cur != null && cur.key == key) { cur.state = state; return false }
        cur?.live = false
        _entries += Entry(nextGen++, key, state)
        return true
    }

    /** 残影淡出走完:从账本里拿掉(活着的那项不会被拿掉)。 */
    fun remove(gen: Int): Boolean = _entries.removeAll { it.gen == gen && !it.live }
}

/**
 * 按 [state] 显示一页,打开淡入 [enterMs]、关掉淡出 [exitMs];[contentKey] 变了 = 换了一页,新旧交叉淡化。
 * 残影期间给内容提供 [LocalPageGhost] = true(见文件头)。**第一次组合就已经开着**(切语言 `recreate()` 之后)不淡入。
 */
@Composable
fun <S : Any> FadeSwitch(
    state: S?,
    enterMs: Int,
    exitMs: Int,
    /** 淡入时从这个比例放大到 1(淡出反过来);1 = 不缩放。 */
    scaleFrom: Float = GtvLayout.SETTINGS_ENTER_SCALE,
    contentKey: (S) -> Any? = { Unit },
    content: @Composable (S) -> Unit,
) {
    val book = remember { FadeBook<S>() }
    val alphas = remember { HashMap<Int, Animatable<Float, *>>() }
    val first = remember { booleanArrayOf(true) }
    // 残影淡完从账本拿掉之后要重组一次(账本是普通对象,不是快照状态)。
    var removals by remember { mutableIntStateOf(0) }
    check(removals >= 0)
    val opened = book.sync(state, state?.let(contentKey))
    // enterMs ≤ 0 = 不淡入(R138 编辑页从选择器回来时当场出现在选择器残影底下):直接从 1 开始,不画一帧透明的。
    if (opened) book.live?.let { alphas[it.gen] = Animatable(if (first[0] || enterMs <= 0) 1f else 0f) }
    first[0] = false
    if (book.entries.isEmpty()) return
    val outerGhost = LocalPageGhost.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    Box(Modifier.fillMaxSize()) {
        for (e in book.entries.toList()) key(e.gen) {
            val alpha = alphas.getOrPut(e.gen) { Animatable(1f) }
            val live = e.live
            LaunchedEffect(live) {
                if (live) {
                    alpha.animateTo(1f, tween(enterMs, easing = SettingsFadeEasing))
                } else {
                    // **宿主掉出前台就不等淡出**(R140,复审 Critical):Compose 在 ON_STOP 暂停组合的帧时钟,淡出动画停在原地,
                    // 残影连同它的副作用(扫码页的上传服务、全屏预览里的播放器)就一直挂在组合里,等人回到桌面才走完——
                    // 在后台关掉的扫码页,服务会一直开着。所以淡出与「宿主低于 STARTED」赛跑,先掉出就当场拿掉。
                    val fade = launch { alpha.animateTo(0f, tween(exitMs, easing = SettingsFadeEasing)) }
                    val watch = launch {
                        lifecycle.currentStateFlow.first { !it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                        fade.cancel()
                    }
                    fade.join()
                    watch.cancel()
                    if (book.remove(e.gen)) { alphas.remove(e.gen); removals++ }
                }
            }
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val t = alpha.value
                    this.alpha = t
                    val sc = scaleFrom + (1f - scaleFrom) * t
                    scaleX = sc; scaleY = sc
                },
            ) {
                CompositionLocalProvider(LocalPageGhost provides (outerGhost || !live)) { content(e.state) }
            }
        }
    }
}

/**
 * **一摞整屏浮层**(R136,2026-09-30 外观轮「动效统一」):打开淡入、关掉淡出(与设置外壳同一个时长与轻微放大),
 * 摞里换一层(菜单 → 改名页、选图页 → 扫码页、应用菜单第一层 → 第二层)新旧交叉淡化(与设置里层与层之间同一个时长)。
 *
 * 外观轮之前只有设置外壳、关于页、所有应用页、输入源页有淡入淡出(R108);长按菜单、确认页、改名页、行图标、
 * 添加应用、选图页、扫码页、引导都是硬切。现在全部走这里,时长只有 [GtvLayout] 里那三个数。
 *
 * [state] == null = 关着;[layerKey] 相同 = 同一层(输入原地更新)。焦点规矩同 R108:逻辑状态当场变,画面另起残影淡出;
 * 残影里 [LocalPageGhost] 为真,**放进来的每一种页面都必须照它让路**(不可聚焦、不抢焦点、不收返回键、不回调)——
 * `GearMenu` / `CapsuleColumn` / `PickerGrid` / `TitleDialog` / `RowIconPicker` / 添加应用列表 / 扫码页 / 全屏预览都已照做;
 * 新加页面时先做这一条,再放进来。
 *
 * 两层 [FadeSwitch]:外层管开 / 关(key 恒定,关掉后冻结在最后那一层),里层管换层。第一次开时里层不淡入(只有外层那一次)。
 */
@Composable
fun <S : Any> OverlayStack(
    state: S?,
    layerKey: (S) -> Any? = { Unit },
    content: @Composable (S) -> Unit,
) {
    FadeSwitch(
        state = state,
        enterMs = GtvLayout.SETTINGS_FADE_IN_MS,
        exitMs = GtvLayout.SETTINGS_FADE_OUT_MS,
    ) { s ->
        // **R138:换层时底下垫一块不透明底色**。新旧两层各自带着 MenuBg 交叉淡化,中途两层合起来的覆盖率不到 1
        // (各 0.5 时只有 0.75),底下那一页(首页的亮壁纸、编辑页的卡片)会透出来「呼吸」一下。垫的这块跟着外层
        // 一起开关(打开 / 关掉照样是整页淡入淡出),换层时它始终不透明,只有内容在交叉淡化——同设置外壳的底色(R108)。
        Box(Modifier.fillMaxSize().background(GtvTokens.MenuBg)) {
            FadeSwitch(
                state = s,
                enterMs = GtvLayout.SETTINGS_LAYER_FADE_MS,
                exitMs = GtvLayout.SETTINGS_LAYER_FADE_MS,
                scaleFrom = GtvLayout.SETTINGS_LAYER_SCALE,
                contentKey = layerKey,
                content = content,
            )
        }
    }
}

/*
 * **设置外壳的整体淡入淡出 + 预览(R73)的进出**。外壳不止它自己那层内容:底色 `MenuBg` 铺在首页那一层**之下**,
 * 首页那一层在没有预览的页整层透明、在有预览的页整层缩进预览框。三样东西要一起动,所以由三个量驱动:
 * - `a` 外壳在不在(0..1):底色与外壳内容的不透明度;打开 200 ms、关闭 150 ms;
 * - `v` 预览露不露(0..1):只在外壳开着、层与层切换时动(150 ms);外壳关掉时**冻结**;
 * - `z` 首页那一层的几何(0 = 整屏,1 = 缩在预览框里)。
 * 首页那一层的不透明度 = [homeLayerAlpha](a, v) = 1 − a + a·v:外壳关着(a = 0)恒为 1;外壳开在没有预览的页(v = 0)
 * 随 a 反向淡出;开在有预览的页(v = 1)恒为 1——它就是预览本身。于是:
 * - 从首页打开(总是第一层,没有预览):首页淡出、外壳淡入;
 * - 第一层 → 布局 / 外观:首页先瞬移进预览框(此刻 v = 0,看不见)再淡入;返回反过来,淡完(v 到 0)才瞬移回整屏;
 * - 从有预览的页直接关掉(MENU / HOME / 进编辑页):v 冻在 1,首页不透明度恒为 1,z 从 1 缩放回 0(150 ms)——
 *   预览框里的首页放大回整屏,外壳在它周围淡出,不闪;
 * - 从编辑页回到「布局」(外壳重新打开、落在有预览的页):首页**当场**就在预览框里(z 直接取 1、v 直接取 1),外壳在它周围淡入。
 *   R136 之前是「z 从 0 缩进预览框」:编辑页(深色整屏)一关,首页先以整屏露出来再缩进去——壁纸亮的话,
 *   深 → 整屏亮壁纸 → 深,电视上就是闪一下(模拟器 1× 录像实测,内置壁纸「夏日数码门」)。
 */

/** 首页那一层的不透明度(见上)。 */
fun homeLayerAlpha(a: Float, v: Float): Float {
    val aa = a.coerceIn(0f, 1f)
    return (1f - aa + aa * v.coerceIn(0f, 1f)).coerceIn(0f, 1f)
}

/**
 * 外壳开关 / 换层时,`v`、`z` 该怎么动(纯函数,JVM 可测)。
 * @property vSnap 先把 v 瞬移到这个值(null = 不动)。
 * @property zSnapFirst 在 v 动之前先把 z 瞬移到这个值(null = 不动)。
 * @property vTarget / [vMs] v 的目标与时长(vTarget == null = v 冻结)。
 * @property zTarget / [zMs] z 的目标与时长(与 v 同时开始;zTarget == null = z 不动)。
 * @property zSnapAfter v 动完之后把 z 瞬移到这个值(null = 不动)。
 */
data class PreviewMotion(
    val vSnap: Float? = null,
    val zSnapFirst: Float? = null,
    val vTarget: Float? = null,
    val vMs: Int = 0,
    val zTarget: Float? = null,
    val zMs: Int = 0,
    val zSnapAfter: Float? = null,
)

/**
 * @param shown 外壳现在开着(且不在编辑页)。
 * @param preview 栈顶页有预览。
 * @param fresh 这是一次从「完全关着」(a = 0)开始的打开。
 * @param vNow v 此刻的值。
 */
fun previewMotion(shown: Boolean, preview: Boolean, fresh: Boolean, vNow: Float): PreviewMotion = when {
    !shown -> PreviewMotion(zTarget = 0f, zMs = GtvLayout.SETTINGS_FADE_OUT_MS)
    // R136:不再从整屏缩进预览框(见上:编辑页回来会闪一下亮壁纸),首页当场落在预览框里
    fresh && preview -> PreviewMotion(vSnap = 1f, zSnapFirst = 1f)
    fresh -> PreviewMotion(vSnap = 0f, zSnapFirst = 0f)
    preview -> PreviewMotion(
        zSnapFirst = if (vNow <= 0f) 1f else null,
        vTarget = 1f, vMs = GtvLayout.SETTINGS_LAYER_FADE_MS,
        zTarget = 1f, zMs = GtvLayout.SETTINGS_LAYER_FADE_MS,
    )
    else -> PreviewMotion(vTarget = 0f, vMs = GtvLayout.SETTINGS_LAYER_FADE_MS, zSnapAfter = 0f)
}

/** 外壳淡入淡出的三个量(见上)。只在绘制阶段读([a] / [v] / [z] 的 `.value`),动画每帧不重组。 */
class ShellMotion(initialShown: Boolean, initialPreview: Boolean) {
    val a = Animatable(if (initialShown) 1f else 0f)
    val v = Animatable(if (initialShown && initialPreview) 1f else 0f)
    val z = Animatable(if (initialShown && initialPreview) 1f else 0f)

    /**
     * **同一帧的瞬移**(R136)。[run] 里的 `snapTo` 在效果里跑,比「外壳重新打开」那次组合晚一帧——从编辑页回到「布局」时,
     * 那一帧首页以整屏画出来(亮壁纸闪一帧,模拟器 1× 录像实测),下一帧才缩进预览框。[rememberShellMotion] 在组合阶段
     * 把这一次要瞬移到的值先写在这里,绘制阶段读 [zNow];效果里的瞬移落地后清掉。不是闩:每次 [run] 结束都清。
     */
    var zHold by mutableStateOf<Float?>(null)
        internal set

    /** 首页那一层此刻的几何(绘制阶段读):有 [zHold] 用它,否则是动画值。 */
    val zNow: Float get() = zHold ?: z.value

    suspend fun run(shown: Boolean, preview: Boolean) = coroutineScope {
        val fresh = shown && a.value <= 0f
        launch {
            if (shown) a.animateTo(1f, tween(GtvLayout.SETTINGS_FADE_IN_MS, easing = SettingsFadeEasing))
            else a.animateTo(0f, tween(GtvLayout.SETTINGS_FADE_OUT_MS, easing = SettingsFadeEasing))
        }
        val m = previewMotion(shown, preview, fresh, v.value)
        m.vSnap?.let { v.snapTo(it) }
        m.zSnapFirst?.let { z.snapTo(it) }
        val zJob = m.zTarget?.let { t -> launch { z.animateTo(t, tween(m.zMs, easing = FastOutSlowInEasing)) } }
        m.vTarget?.let { t -> v.animateTo(t, tween(m.vMs, easing = FastOutSlowInEasing)) }
        zJob?.join()
        m.zSnapAfter?.let { z.snapTo(it) }
        zHold = null
    }
}

/** 外壳的淡入淡出状态(MainActivity 用):[shown] / [preview] 一变就按 [previewMotion] 动一轮。 */
@Composable
fun rememberShellMotion(shown: Boolean, preview: Boolean): ShellMotion {
    val m = remember { ShellMotion(shown, preview) }
    // 这一次要不要先把首页瞬移进预览框:在**这一次组合**里定下来(见 ShellMotion.zHold)。zHold 只在绘制阶段被读,
    // 组合阶段写它不会造成「先读后写」。shown / preview 没变的重组不重算。
    remember(shown, preview) {
        val fresh = shown && m.a.value <= 0f
        m.zHold = previewMotion(shown, preview, fresh, m.v.value).zSnapFirst
    }
    LaunchedEffect(shown, preview) { m.run(shown, preview) }
    return m
}

/** 外壳(底色 + 内容)还要不要画:开着,或者还在淡出。只在「是否 > 0」翻转时重组。 */
@Composable
fun rememberShellVisible(m: ShellMotion, shown: Boolean): Boolean {
    val fading by remember(m) { derivedStateOf { m.a.value > 0f } }
    return shown || fading
}
