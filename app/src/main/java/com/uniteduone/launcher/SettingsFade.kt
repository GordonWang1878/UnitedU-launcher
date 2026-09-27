package com.uniteduone.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
    if (opened) book.live?.let { alphas[it.gen] = Animatable(if (first[0]) 1f else 0f) }
    first[0] = false
    if (book.entries.isEmpty()) return
    val outerGhost = LocalPageGhost.current
    Box(Modifier.fillMaxSize()) {
        for (e in book.entries.toList()) key(e.gen) {
            val alpha = alphas.getOrPut(e.gen) { Animatable(1f) }
            val live = e.live
            LaunchedEffect(live) {
                if (live) {
                    alpha.animateTo(1f, tween(enterMs, easing = FastOutSlowInEasing))
                } else {
                    alpha.animateTo(0f, tween(exitMs, easing = FastOutSlowInEasing))
                    if (book.remove(e.gen)) { alphas.remove(e.gen); removals++ }
                }
            }
            Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
                CompositionLocalProvider(LocalPageGhost provides (outerGhost || !live)) { content(e.state) }
            }
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
 * - 从编辑页回到「布局」(外壳重新打开、落在有预览的页):z 从 0 缩进预览框(200 ms),v 直接取 1。
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
    fresh && preview -> PreviewMotion(vSnap = 1f, zTarget = 1f, zMs = GtvLayout.SETTINGS_FADE_IN_MS)
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

    suspend fun run(shown: Boolean, preview: Boolean) = coroutineScope {
        val fresh = shown && a.value <= 0f
        launch {
            if (shown) a.animateTo(1f, tween(GtvLayout.SETTINGS_FADE_IN_MS, easing = FastOutSlowInEasing))
            else a.animateTo(0f, tween(GtvLayout.SETTINGS_FADE_OUT_MS, easing = FastOutSlowInEasing))
        }
        val m = previewMotion(shown, preview, fresh, v.value)
        m.vSnap?.let { v.snapTo(it) }
        m.zSnapFirst?.let { z.snapTo(it) }
        val zJob = m.zTarget?.let { t -> launch { z.animateTo(t, tween(m.zMs, easing = FastOutSlowInEasing)) } }
        m.vTarget?.let { t -> v.animateTo(t, tween(m.vMs, easing = FastOutSlowInEasing)) }
        zJob?.join()
        m.zSnapAfter?.let { z.snapTo(it) }
    }
}

/** 外壳的淡入淡出状态(MainActivity 用):[shown] / [preview] 一变就按 [previewMotion] 动一轮。 */
@Composable
fun rememberShellMotion(shown: Boolean, preview: Boolean): ShellMotion {
    val m = remember { ShellMotion(shown, preview) }
    LaunchedEffect(shown, preview) { m.run(shown, preview) }
    return m
}

/** 外壳(底色 + 内容)还要不要画:开着,或者还在淡出。只在「是否 > 0」翻转时重组。 */
@Composable
fun rememberShellVisible(m: ShellMotion, shown: Boolean): Boolean {
    val fading by remember(m) { derivedStateOf { m.a.value > 0f } }
    return shown || fading
}
