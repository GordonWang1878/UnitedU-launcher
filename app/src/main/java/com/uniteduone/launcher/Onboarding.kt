package com.uniteduone.launcher

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/** 页面内容列宽。960dp 宽的电视屏上居中,四个语言按钮与「继续 / 跳过」都在这一列里。 */
private val PAGE_W = 620.dp
/** 每步内容与底部「继续 / 跳过」之间的间距。 */
private val STEP_GAP = 30.dp
/** 第 2 步列表里行名那一列的宽度(图标 + VIDEO/LIVE/MUSIC),让三行的应用名左对齐。 */
private val PLAN_ROW_NAME_W = 112.dp
/**
 * 按钮**固定高度**,不靠上下内边距撑:中文标签走回落字体,行高比 DM Sans 高几个像素,
 * 用内边距的话「跟隨系統」比「English」高一截,同一排四个按钮参差不齐(模拟器截图实测)。
 */
private val BUTTON_H = 48.dp

/**
 * 首次启动引导(spec §8):三步全屏页,叠在常驻首页之上(α 0.85 的黑遮罩,底下的首页隐约可见)。
 *
 * 1. **语言**:四个语言按钮([LANGUAGE_OPTION_RES],与设置页语言行同一张表),按下即写入并进第 2 步
 *    ——语言真的变了由 Activity `recreate()`,重建后按 Bundle 里的步骤号直接落在第 2 步、已是新语言;
 *    「继续」「跳过」都是「不改语言,进第 2 步」(这一步的动作就是选语言,不选即沿用当前值)。
 * 2. **铺应用**:列出「将放到桌面的应用」= 内置分类表 ∩ 已装(按行分组,空行不显示);
 *    「继续」→ 按已装过滤写 layout.json,「跳过」→ 三行保留、应用清空。一个都没装时显示 `onb_none`,
 *    此时「继续」写下的文件与「跳过」完全相同(`plannedLayout` 的单测钉住)。
 * 3. **默认桌面**:复用 [CurrentHomeRow](与设置页「默认桌面」卡同一块)+「在系统设置中更改」按钮;
 *    「继续」「跳过」都结束引导;系统设置按钮先结束引导、再打开系统页,从系统页回来落在普通首页。
 *
 * 返回键 = 上一步,第 1 步 = 结束整个引导([onBack] 由 Activity 按 `onboardingBack` 分派)。
 * 结束的每一条路都由 Activity 先写 `onboardingDone = true`,本页不碰任何文件。
 *
 * **焦点账本**(铁律 2–7):每一步是 `when` 里各自独立的一段组合,换步时整段销毁、下一步整段新建,
 * 各带一份 [StepFocus](逐项 requester + 自报的当前项 + 冻结的目标 + 定位循环 + 看门狗)。
 * 本页是整屏浮层,首页的看门狗此时因 `previewing` 让路——**本页必须自己负责自己的焦点恢复**(铁律 3 的推论),
 * 没有第二个人会把它捞回来。所有方向键落点显式写死,找不到邻居的方向锁 `Cancel`,
 * 焦点永远不会被几何搜索带到遮罩后面去;整页 `focusGroup()`,页面里没有任何可滚动容器(铁律 1)。
 */
@Composable
fun Onboarding(
    step: Int,
    /** 当前语言设置(`settings.json` 的 `language`),决定第 1 步哪个按钮是「已选」、初始焦点落在哪。 */
    language: String,
    /** 首页那颗 `revision`:装卸应用后第 2 步的计划、第 3 步的当前桌面跟着重算。 */
    revision: Int,
    nonce: Int,
    onLanguage: (String) -> Unit,
    onStep: (Int) -> Unit,
    onFill: () -> Unit,
    onSkipFill: () -> Unit,
    onOpenHomeSettings: () -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    // 比 MainActivity 的常开回调后注册,本页在时由它接管返回键。
    androidx.activity.compose.BackHandler { onBack() }
    val accent = LocalThemeColors.current.accent

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .background(Color.Black.copy(alpha = 0.85f)),
        // **顶端对齐、固定上边距**(同关于页):三步内容高度不同,居中的话标题每换一步就上下跳。
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.padding(top = 84.dp).width(PAGE_W)) {
            BasicText(
                text = "${stringResource(R.string.app_name)}  ·  $step / $ONBOARDING_STEPS",
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                ),
            )
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = stringResource(
                    when (step) {
                        1 -> R.string.onb_step1_title
                        2 -> R.string.onb_step2_title
                        else -> R.string.onb_step3_title
                    },
                ),
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = Theme.EmphasisText,
                    fontSize = 26.sp,
                ),
            )
            Spacer(Modifier.height(22.dp))
            // 三个分支是三个不同的调用点:换步 = 旧的一段(连同它的 StepFocus 与两个效果)整段离场,
            // 新的一段带着全新的账本进场。不会有上一步残留的「当前项」挡住下一步的初始定位。
            when (step) {
                1 -> LanguageStep(language = language, nonce = nonce, onLanguage = onLanguage, onNext = { onStep(2) })
                2 -> FillStep(revision = revision, nonce = nonce, onFill = onFill, onSkipFill = onSkipFill)
                else -> HomeStep(
                    revision = revision,
                    nonce = nonce,
                    onOpenHomeSettings = onOpenHomeSettings,
                    onFinish = onFinish,
                )
            }
        }
    }
}

/** 第 1 步:四个语言按钮(下标 0..3,顺序 = [VALID_LANGUAGES])+ 继续(4)+ 跳过(5)。 */
@Composable
private fun LanguageStep(language: String, nonce: Int, onLanguage: (String) -> Unit, onNext: () -> Unit) {
    val selected = languageIndex(language)
    val langs = LANGUAGE_OPTION_RES.size
    val next = langs
    val skip = langs + 1
    // 初始焦点落在「已选」的那个语言上:确定键 = 保持当前语言进下一步,不会误改语言。
    val focus = rememberStepFocus(count = langs + 2, initial = selected, nonce = nonce)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LANGUAGE_OPTION_RES.forEachIndexed { i, res ->
            OnbButton(
                label = stringResource(res),
                focus = focus,
                index = i,
                left = (i - 1).takeIf { it >= 0 },
                right = (i + 1).takeIf { it < langs },
                down = next,
                marked = i == selected,
                minWidth = 110.dp,
                onClick = { onLanguage(VALID_LANGUAGES[i]) },
            )
        }
    }
    Spacer(Modifier.height(STEP_GAP))
    // 从「继续 / 跳过」按上,回到已选的那个语言(而不是几何上最近的那一个)。
    NextSkipRow(focus = focus, next = next, skip = skip, up = selected, onNext = onNext, onSkip = onNext)
}

/** 第 2 步:说明 + 计划列表(不可聚焦)+ 继续(0)+ 跳过(1)。 */
@Composable
private fun FillStep(revision: Int, nonce: Int, onFill: () -> Unit, onSkipFill: () -> Unit) {
    val ctx = LocalContext.current
    // IO 线程算(枚举应用 + 读标签);null = 还在读。revision 变了(装卸应用)重算,新结果到达前旧列表原样留着。
    val plan by produceState<List<Pair<String, List<Pair<String, String>>>>?>(null, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching { onboardingPlan(ctx) }.getOrElse { e ->
                Log.w("UnitedU", "引导第 2 步读应用失败: ${e.message}")
                emptyList()
            }
        }
    }
    val focus = rememberStepFocus(count = 2, initial = 0, nonce = nonce)
    BasicText(
        text = stringResource(R.string.onb_step2_body),
        style = TextStyle(fontFamily = Theme.Sans, color = Theme.SecondaryText, fontSize = 14.sp, lineHeight = 20.sp),
    )
    Spacer(Modifier.height(14.dp))
    PlanPanel(plan)
    Spacer(Modifier.height(STEP_GAP))
    // 「继续」不看屏幕上这份 plan,落盘时在 IO 线程重新按已装过滤(见 writeOnboardingLayout):
    // 列表还没读完就按下、或读完之后又装了应用,写下去的都是按下那一刻的真实情况。
    NextSkipRow(focus = focus, next = 0, skip = 1, up = null, onNext = onFill, onSkip = onSkipFill)
}

/** 第 2 步的计划列表:每行 = 行图标 + 行名(与首页行标题同一套)+ 这一行将放下的应用名。 */
@Composable
private fun PlanPanel(plan: List<Pair<String, List<Pair<String, String>>>>?) {
    val accent = LocalThemeColors.current.accent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Theme.InfoRowBackground)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            plan == null -> BasicText(
                text = stringResource(R.string.edit_loading_apps),
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.HintText, fontSize = 14.sp),
            )
            plan.isEmpty() -> BasicText(
                text = stringResource(R.string.onb_none),
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.SecondaryText, fontSize = 14.sp),
            )
            else -> plan.forEach { (rowName, apps) ->
                Row(verticalAlignment = Alignment.Top) {
                    Row(
                        modifier = Modifier.width(PLAN_ROW_NAME_W),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RowIcon(rowName, tint = accent)
                        BasicText(
                            text = rowName,
                            style = TextStyle(
                                fontFamily = Theme.Sans,
                                fontWeight = FontWeight.Medium,
                                color = accent,
                                fontSize = 14.sp,
                            ),
                        )
                    }
                    // 应用名一行放不下就折到第二行,再多就省略——不滚动(铁律 1),默认表每行最多 5 个。
                    BasicText(
                        text = apps.joinToString("  ·  ") { it.second },
                        style = TextStyle(
                            fontFamily = Theme.Sans,
                            color = Theme.EmphasisText,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** 第 3 步:当前默认桌面(不可聚焦)+ 去系统设置(0)+ 继续(1)+ 跳过(2)。 */
@Composable
private fun HomeStep(revision: Int, nonce: Int, onOpenHomeSettings: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val home = rememberCurrentHome(revision)
    // 已经是默认桌面时,初始焦点给「继续」(确定键 = 完成);否则给「去系统设置」——这一步真正要做的事。
    val focus = rememberStepFocus(
        count = 3,
        initial = if (home.pkg == ctx.packageName) 1 else 0,
        nonce = nonce,
    )
    CurrentHomeRow(home)
    Spacer(Modifier.height(14.dp))
    OnbButton(
        label = stringResource(R.string.home_settings_change_button),
        focus = focus,
        index = 0,
        down = 1,
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenHomeSettings,
    )
    Spacer(Modifier.height(10.dp))
    BasicText(
        text = stringResource(R.string.home_settings_note),
        style = TextStyle(fontFamily = Theme.Sans, color = Theme.FootnoteText, fontSize = 12.sp, lineHeight = 17.sp),
    )
    Spacer(Modifier.height(STEP_GAP))
    NextSkipRow(focus = focus, next = 1, skip = 2, up = 0, onNext = onFinish, onSkip = onFinish)
}

/** 每步底部的「继续」「跳过」:左右互指,按上去 [up](null = 锁住),按下锁住。 */
@Composable
private fun NextSkipRow(
    focus: StepFocus,
    next: Int,
    skip: Int,
    up: Int?,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OnbButton(
            label = stringResource(R.string.onb_next),
            focus = focus,
            index = next,
            up = up,
            right = skip,
            minWidth = 140.dp,
            onClick = onNext,
        )
        OnbButton(
            label = stringResource(R.string.onb_skip),
            focus = focus,
            index = skip,
            up = up,
            left = next,
            minWidth = 140.dp,
            onClick = onSkip,
        )
    }
}

/**
 * 引导页的一个按钮,也是焦点账本的原子单位:
 * - 挂**自己那一项**的 requester(逐项挂,铁律 3);
 * - 四个方向显式指向同一步里的另一项下标,null = `FocusRequester.Cancel`(框架的「别再找了」哨兵,
 *   不是取消按钮)——不留任何几何搜索的余地;
 * - 得失都经 [StepFocus.report] 上报(铁律 4:只信控件自报);
 * - [marked] = 第 1 步里「当前语言」的标记:null 不留标记位,true/false 留一个同宽的圆点位,
 *   四个语言按钮宽度不随选中项变化。焦点靠描边表示,已选靠圆点 + 淡底,两者一眼分得开。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun OnbButton(
    label: String,
    focus: StepFocus,
    index: Int,
    modifier: Modifier = Modifier,
    up: Int? = null,
    down: Int? = null,
    left: Int? = null,
    right: Int? = null,
    marked: Boolean? = null,
    minWidth: Dp = 0.dp,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val highlight = LocalThemeColors.current.highlight
    val shape = RoundedCornerShape(10.dp)
    val isMarked = marked == true
    Box(
        modifier = modifier
            .widthIn(min = minWidth)
            .height(BUTTON_H)
            .clip(shape)
            .background(
                when {
                    focused -> highlight.copy(alpha = if (isMarked) 0.28f else 0.16f)
                    isMarked -> highlight.copy(alpha = 0.10f)
                    else -> Theme.UnfocusedSurface
                },
            )
            .then(if (focused) Modifier.border(BorderStroke(1.dp, highlight.copy(alpha = 0.7f)), shape) else Modifier)
            .focusRequester(focus.reqs[index])
            .focusProperties {
                this.up = up?.let { focus.reqs[it] } ?: FocusRequester.Cancel
                this.down = down?.let { focus.reqs[it] } ?: FocusRequester.Cancel
                this.left = left?.let { focus.reqs[it] } ?: FocusRequester.Cancel
                this.right = right?.let { focus.reqs[it] } ?: FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; focus.report(index, it.isFocused) }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (marked != null) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isMarked) highlight else Color.Transparent),
                )
                Spacer(Modifier.width(8.dp))
            }
            BasicText(
                text = label,
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        focused -> highlight
                        isMarked -> Theme.EmphasisText
                        else -> Theme.ButtonText
                    },
                    fontSize = 14.sp,
                ),
            )
        }
    }
}

/**
 * 一步的焦点账本。每步一份,随该步的组合一起建、一起销毁。
 *
 * - [reqs]:这一步每个可聚焦项**各挂一个** requester(铁律 3 的实现约束)。项数固定、全部常驻组合,
 *   请求哪一项都不会落空。
 * - [focused]:**当前**焦点在哪一项,只由按钮自报(铁律 2/4);null = 这一步里没有任何一项持有焦点。
 * - [target]:**目标**,焦点该回到哪一项。与 [focused] 分开存(铁律 5):它只在「焦点真的落下」且
 *   不在 [restoring] 期间时才跟着走,Compose 抢先塞给某个按钮的那一下改不了它。
 * - [restoring]:定位循环在跑(或 Activity 已暂停,等回到前台再定位)。**初值为真**:本步第一帧里
 *   Compose 可能抢先把焦点交给第一个可聚焦项(第 1 步就是「跟随系统」),那次上报若被当成用户的选择,
 *   初始焦点就再也落不到「已选语言」上。ON_PAUSE 时也置真(终审 I1,理由相同:回到前台那一下的抢先焦点)。
 *   它不是一次性闩(铁律 7):定位效果在每次进场、每次 nonce 变化时都会跑,结束时只要在前台就把它放掉;
 *   不在前台就留着,回到前台必经 `onResume` 的 `focusNonce++`,新一轮会重新置真、再放掉。
 */
@Stable
private class StepFocus(count: Int, initial: Int) {
    val reqs: List<FocusRequester> = List(count) { FocusRequester() }
    var focused by mutableStateOf<Int?>(null)
    var target by mutableStateOf(initial)
    var restoring by mutableStateOf(true)

    fun report(index: Int, got: Boolean) {
        if (got && !restoring) target = index
        // 得失顺序保护:左右移动时新项先报 got、旧项后报 lost,旧项那次 lost 不能把新项抹掉。
        if (got) focused = index
        else if (focused == index) focused = null
    }
}

/**
 * 建一份 [StepFocus] 并挂上它的两个效果:
 *
 * 1. **定位**(key = nonce):进场时、以及每次 nonce 变化(从别的应用回来 → `onResume` → `focusNonce++`)
 *    时,把焦点送到 [StepFocus.target]。退出条件是「自报的当前项 == 目标」——目标是具体下标、
 *    当前项为 null 时条件不成立,所以它同时要求「焦点真的落下了」(铁律 2、5)。
 * 2. **看门狗**(key = nonce / focused / restoring,守卫读的正是这三个量,铁律 6):焦点莫名其妙没了
 *    (节点被重组、窗口焦点来回)就送回目标。先等 3 帧再判一次:D-pad 换项时旧项的 lost 与新项的
 *    got 可能分属相邻两帧,中间那一帧的 null 不算丢(与首页 / 设置页看门狗同一手法)。
 */
@Composable
private fun rememberStepFocus(count: Int, initial: Int, nonce: Int): StepFocus {
    val f = remember { StepFocus(count, initial.coerceIn(0, count - 1)) }
    // **从 ON_PAUSE 就冻结目标**(铁律 5 的后半句,M7 终审 I1;与 HomeScreen / SettingsScreen 同一手法)。
    // 灭屏再亮、别的应用到前台再回来:Compose 会抢在定位效果重启之前把焦点塞给这一步第一个可聚焦项
    // (第 1 步是「跟随系统」),那次上报若看到 restoring 为假就把目标改写掉,用户回来落在「跟随系统」
    // 而不是离开时的「繁體」。放开交给下面的定位效果(onResume 的 focusNonce++ 让它重跑)。
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, f) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) f.restoring = true
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(nonce) {
        f.restoring = true
        var frames = 0
        // requestFocus() 返回 Unit,只有一个节点都没挂上才抛——落没落下只能靠自报(铁律 2)。
        while (frames < 60 && f.focused != f.target) {
            withFrameNanos { }
            runCatching { f.reqs[f.target].requestFocus() }
            frames++
        }
        // 只在前台时放开(理由同 SettingsScreen 定位效果末尾):暂停期间跑完就继续冻着,
        // 回到前台必经 focusNonce++,本效果重跑时再放开——不是闩(铁律 7)。
        f.restoring = !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    }
    LaunchedEffect(nonce, f.focused, f.restoring) {
        if (f.restoring) return@LaunchedEffect
        if (f.focused != null) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        if (f.focused != null) return@LaunchedEffect
        var frames = 0
        while (f.focused == null && frames < 60) {
            withFrameNanos { }
            runCatching { f.reqs[f.target].requestFocus() }
            frames++
        }
    }
    return f
}

// ---- 数据接线(不画界面;Activity 与上面的第 2 步调用) --------------------------------------

/**
 * `onCreate` 三态判定的落地(spec §8,规则见 [onboardingDoneToWrite])。@return 这次是否显示引导。
 *
 * **必须在任何 `Layout.read` 之前调用**:`Layout.read` 在文件缺失时会把默认布局写出来,
 * 而「layout.json 在不在」正是区分老用户与新装的唯一依据。`MainActivity.onCreate` 在
 * `setContent`(首页从那里开始读布局)之前调它。
 *
 * 外置存储没挂(`Paths.baseOrNull == null`)时**不判、不显示、字段保持缺省**:那时连
 * `onboardingDone = true` 都写不下去,引导开了也关不掉(下次启动还在),不如下次存储就绪时再判。
 * 写盘失败同理(`update` 返回 null)→ 不显示。已判定过就只读不写,冷启动不重写 settings.json。
 */
internal fun resolveOnboarding(ctx: Context): Boolean {
    if (Paths.baseOrNull(ctx) == null) return false
    val current = SettingsStore.read(ctx).onboardingDone
    val toWrite = onboardingDoneToWrite(current, Paths.layoutJson(ctx).exists())
        ?: return shouldShowOnboarding(current)
    // 在锁里再判一次缺省:与别的写者(理论上此刻没有)交错时,绝不覆盖一个已经判定过的值。
    val written = SettingsStore.update(ctx) { it.copy(onboardingDone = it.onboardingDone ?: toWrite) }
    return shouldShowOnboarding(written?.onboardingDone)
}

/**
 * 分类表里**已装、可启动**的应用:包名 → 显示名(读不到标签时是空串)。
 * 与首页 `buildRows` 同一口径:分类表的包名当 `extraPackages` 传进去,只有 MAIN + DEFAULT 入口的应用
 * (当贝音乐那类)也认得出来;只读这几个包的标签,一张位图都不解。
 */
@WorkerThread
internal fun installedDefaultApps(ctx: Context): Map<String, String> {
    val wanted = DEFAULT_LAYOUT.flatMap { it.second }.toSet()
    return Apps.load(ctx, extraPackages = wanted, withBitmaps = emptySet(), withLabels = wanted)
        .filterKeys { it in wanted }
        .mapValues { it.value.label }
}

/** 第 2 步屏幕上的计划:写盘用的那份布局([plannedLayout])去掉空行、配上显示名([planView])。 */
@WorkerThread
internal fun onboardingPlan(ctx: Context): List<Pair<String, List<Pair<String, String>>>> {
    val labels = installedDefaultApps(ctx)
    return planView(plannedLayout(DEFAULT_LAYOUT, labels.keys), labels)
}

/**
 * 第 2 步落盘专用的**串行** IO 调度器。`Layout.write` 不加锁、固定用同一个 `layout.json.tmp`:
 * 「继续 → 返回 → 跳过」若两次写盘交叠,输的一方可能在兜底的 delete + rename 里把赢家刚放好的文件删掉。
 * 串行且按提交顺序执行(`limitedParallelism(1)` 内部是 FIFO 队列),最后落盘的一定是最后一次按下的选择。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal val onboardingLayoutWrites: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/**
 * 第 2 步的写盘(在 [onboardingLayoutWrites] 上调用)。[fill] = 「继续」:按**此刻**的已装集合过滤分类表;
 * false = 「跳过」:三行空。@return 是否真的落盘了。
 */
@WorkerThread
internal fun writeOnboardingLayout(ctx: Context, fill: Boolean): Boolean = runCatching {
    val rows = if (fill) plannedLayout(DEFAULT_LAYOUT, installedDefaultApps(ctx).keys)
    else skippedLayout(DEFAULT_LAYOUT)
    Layout.write(ctx, rows)
}.getOrDefault(false)
