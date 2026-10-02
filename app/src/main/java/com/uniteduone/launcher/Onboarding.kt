package com.uniteduone.launcher

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 第 2 步列表里行名那一列的宽度(图标 + 行名),让几行的应用名左对齐。 */
private val PLAN_ROW_NAME_W = 96.dp
/** 第 2、3 步左边信息块(计划列表、当前默认桌面)的宽度:与设置「默认桌面」页那一块同宽。 */
private val INFO_BLOCK_W = 360.dp

// 各步胶囊的 id(CapsuleColumn 的焦点目标按 id 记)。
private const val ONB_FILL = "fill"
private const val ONB_SKIP = "skip"
private const val ONB_CHANGE_HOME = "changeHome"
/** R162:第 3 步的「主页键接管」条件胶囊。 */
private const val ONB_HOME_KEY = "homeKey"
private const val ONB_DONE = "done"
private fun onbLanguageId(i: Int) = "lang:$i"

/**
 * 首次启动引导(spec §8):三步整屏页,叠在常驻首页之上。
 *
 * 1. **语言**:四颗语言胶囊([LANGUAGE_OPTION_RES],与设置页语言行同一张表),当前语言那一颗带 ✓、初始焦点落在它上面;
 *    按下哪一颗就用哪种语言并进第 2 步——语言真的变了由 Activity `recreate()`,重建后按 Bundle 里的步骤号直接落在
 *    第 2 步、已是新语言;按的就是当前语言时不重建,直接进第 2 步(= 原来的「继续」)。
 * 2. **铺应用**:左边列出「将放到桌面的应用」= 内置分类表 ∩ 已装(按行分组,空行不显示);右边「放到桌面」→ 按已装过滤
 *    写 layout.json,「跳过」→ 三行保留、应用清空。一个都没找到时只有一颗「继续」(此时两条路写下的文件完全相同,
 *    `plannedLayout` 的单测钉住)。
 * 3. **默认桌面**:左边 [CurrentHomeRow](与设置「默认桌面」页同一块)+ 一句说明;右边「去系统设置更改」「主页键接管」
 *    (条件行,R162:UnitedU 不是默认桌面、或接管服务已开着才画,与设置「默认桌面」页同一条规则)「完成」。
 *    每一颗都结束引导;去系统设置 / 主页键接管那两颗先结束引导、再打开系统页,从系统页回来落在普通首页。
 *    例外:主页键接管在受限时提示后照样打开无障碍页、但**不结束引导**——从系统页回来还停在这一步,左侧说明还在屏幕上。
 *
 * 返回键 = 上一步,第 1 步 = 结束整个引导([onBack] 由 Activity 按 `onboardingBack` 分派)。
 * 结束的每一条路都由 Activity 先写 `onboardingDone = true`,本页不碰任何文件。
 *
 * **R135(2026-09-30 外观轮)换皮**:与设置各层同一个版式——整屏 `MenuBg`,左边步数小字 + 页名 + 说明,右边一列胶囊;
 * 换步时新旧两步交叉淡化(同设置里层与层之间)。此前是 85% 黑蒙版上一列 620 dp 的内容、带描边的方按钮横排。
 *
 * **焦点账本**(铁律 2–7):每一步一份 [CapsuleColumn](设置外壳、关于页、输入源页用的同一套:逐项 requester、
 * 目标按 id 记且与持有者分开、`ON_PAUSE` 起冻结、定位效果只信目标自报、`holder == null` 看门狗)。换步 = 旧的一步变成
 * 淡出的残影([LocalPageGhost]:不可聚焦、不回调),新的一步是全新的组合、自己把焦点落到它的初始目标。此前引导自带一套
 * 同构的账本(`StepFocus`),外观轮起不再各养一份。本页是整屏浮层,首页的看门狗因 `previewing` 让路。
 */
@Composable
fun Onboarding(
    step: Int,
    /** 当前语言设置(`settings.json` 的 `language`),决定第 1 步哪一颗带 ✓、初始焦点落在哪。 */
    language: String,
    /** 首页那颗 `revision`:装卸应用后第 2 步的计划、第 3 步的当前桌面跟着重算。 */
    revision: Int,
    nonce: Int,
    onLanguage: (String) -> Unit,
    onStep: (Int) -> Unit,
    onFill: () -> Unit,
    onSkipFill: () -> Unit,
    onOpenHomeSettings: () -> Unit,
    /** R162:第 3 步「主页键接管」胶囊(条件行)。 */
    onOpenHomeKey: () -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    // 比 MainActivity 的常开回调后注册,本页在时由它接管返回键。整页淡出中(引导已结束)不收。
    val ghost = LocalPageGhost.current
    androidx.activity.compose.BackHandler(enabled = !ghost) { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .background(GtvTokens.ScrimOverlay)
            .pageBackdrop(),   // R142
    ) {
        // 换步交叉淡化:key = 步数。旧的一步变残影(冻结在它最后的输入上),新的一步全新组合。
        FadeSwitch(
            state = step,
            enterMs = GtvLayout.SETTINGS_LAYER_FADE_MS,
            exitMs = GtvLayout.SETTINGS_LAYER_FADE_MS,
            scaleFrom = GtvLayout.SETTINGS_LAYER_SCALE,
            contentKey = { it },
        ) { s ->
            val eyebrow = "${stringResource(R.string.app_name)} · $s / $ONBOARDING_STEPS"
            when (s) {
                1 -> LanguageStep(eyebrow = eyebrow, language = language, nonce = nonce, onLanguage = onLanguage)
                2 -> FillStep(eyebrow = eyebrow, revision = revision, nonce = nonce, onFill = onFill, onSkipFill = onSkipFill)
                else -> HomeStep(
                    eyebrow = eyebrow,
                    revision = revision,
                    nonce = nonce,
                    onOpenHomeSettings = onOpenHomeSettings,
                    onOpenHomeKey = onOpenHomeKey,
                    onFinish = onFinish,
                )
            }
        }
    }
}

/** 第 1 步:一句欢迎 + 四颗语言胶囊(顺序 = [VALID_LANGUAGES]);当前语言带 ✓,按下即用这种语言进第 2 步。 */
@Composable
private fun LanguageStep(eyebrow: String, language: String, nonce: Int, onLanguage: (String) -> Unit) {
    val selected = languageIndex(language)
    // 初始焦点落在「已选」的那一颗上:确定键 = 保持当前语言进下一步,不会误改语言。
    var target by remember { mutableStateOf<String?>(onbLanguageId(selected)) }
    val items = LANGUAGE_OPTION_RES.mapIndexed { i, res ->
        Capsule(
            id = onbLanguageId(i),
            label = stringResource(res),
            trailing = if (i == selected) Trailing.Check else Trailing.None,
            onClick = { onLanguage(VALID_LANGUAGES[i]) },
        )
    }
    ShellScaffold(
        left = {
            ShellTitle(eyebrow, stringResource(R.string.onb_step1_title)) {
                ShellBody(stringResource(R.string.onb_step1_body))
            }
        },
        right = { CapsuleColumn(items, target, { target = it }, nonce, covered = false) },
    )
}

/** 第 2 步:左边说明 + 计划列表(不可聚焦),右边「放到桌面」「跳过」(一个都没找到时只有「继续」)。 */
@Composable
private fun FillStep(eyebrow: String, revision: Int, nonce: Int, onFill: () -> Unit, onSkipFill: () -> Unit) {
    val ctx = LocalContext.current
    // IO 线程算(枚举应用 + 读标签);null = 还在读。revision 变了(装卸应用)重算,新结果到达前旧列表原样留着。
    val plan by produceState<List<PlanRow>?>(null, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching { onboardingPlan(ctx) }.getOrElse { e ->
                Log.w("UnitedU", "引导第 2 步读应用失败: ${e.message}")
                emptyList()
            }
        }
    }
    // R133:一个都没找到时说明换成「以后在所有应用里加」,右边只有一颗「继续」(没有东西可放,「放到桌面」是空话;
    // 「跳过」此时与它写下同一个文件,两颗并排只会让人琢磨有什么不同)。
    val empty = plan?.isEmpty() == true
    var target by remember { mutableStateOf<String?>(ONB_FILL) }
    // 「放到桌面」不看屏幕上这份 plan,落盘时在 IO 线程重新按已装过滤(见 writeOnboardingLayout):
    // 列表还没读完就按下、或读完之后又装了应用,写下去的都是按下那一刻的真实情况。
    val items = buildList {
        add(Capsule(ONB_FILL, stringResource(if (empty) R.string.onb_next else R.string.onb_add_to_home), onClick = onFill))
        if (!empty) add(Capsule(ONB_SKIP, stringResource(R.string.onb_skip), onClick = onSkipFill))
    }
    ShellScaffold(
        left = {
            ShellTitle(eyebrow, stringResource(R.string.onb_step2_title)) {
                Column(Modifier.width(INFO_BLOCK_W), horizontalAlignment = Alignment.CenterHorizontally) {
                    ShellBody(stringResource(if (empty) R.string.onb_step2_body_empty else R.string.onb_step2_body))
                    if (!empty) {
                        Spacer(Modifier.height(16.dp))
                        PlanPanel(plan)
                    }
                }
            }
        },
        right = { CapsuleColumn(items, target, { target = it }, nonce, covered = false) },
    )
}

/** 第 2 步的计划列表:每行 = 行图标 + 行名 + 这一行将放下的应用名。纯展示。 */
@Composable
private fun PlanPanel(plan: List<PlanRow>?) {
    val accent = LocalThemeColors.current.accent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(INFO_PANEL_CORNER.dp))
            .background(GtvTokens.SurfaceIdle)   // R144
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (plan == null) {
            BasicText(text = stringResource(R.string.edit_loading_apps), style = Type.body)
        } else plan.forEach { row ->
            val rowName = row.name
            val apps = row.apps
            Row(verticalAlignment = Alignment.Top) {
                Row(
                    modifier = Modifier.width(PLAN_ROW_NAME_W),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RowIcon(rowName, row.icon, tint = accent)
                    BasicText(
                        text = rowName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = Type.body.copy(fontWeight = FontWeight.Medium, color = accent),
                    )
                }
                // 应用名一行放不下就折到第二行,再多就省略——不滚动(铁律 1),默认表每行最多 5 个。
                BasicText(
                    text = apps.joinToString("  ·  ") { it.second },
                    style = Type.body.copy(color = Ink.Primary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 第 3 步:左边当前默认桌面 + 说明(不可聚焦),右边「去系统设置更改」「主页键接管」(条件,R162)「完成」。 */
@Composable
private fun HomeStep(eyebrow: String, revision: Int, nonce: Int, onOpenHomeSettings: () -> Unit, onOpenHomeKey: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val home = rememberCurrentHome(revision, nonce)
    val status = remember(nonce) { HomeKeyState.status(ctx) }
    val enabled = status == HomeKeyStatus.ON || status == HomeKeyStatus.NOT_RUNNING
    val takeover = showHomeKeyCapsule(home.pkg == ctx.packageName, enabled)
    // 已经是默认桌面时,初始焦点给「完成」;否则给「去系统设置更改」——这一步真正要做的事。
    var target by remember { mutableStateOf<String?>(if (home.pkg == ctx.packageName) ONB_DONE else ONB_CHANGE_HOME) }
    val items = buildList {
        add(Capsule(ONB_CHANGE_HOME, stringResource(R.string.home_settings_change_button), onClick = onOpenHomeSettings))
        if (takeover) add(Capsule(ONB_HOME_KEY, stringResource(R.string.homekey_capsule), onClick = onOpenHomeKey, hint = stringResource(homeKeyStatusRes(status))))
        add(Capsule(ONB_DONE, stringResource(R.string.onb_done), onClick = onFinish))
    }
    ShellScaffold(
        left = {
            ShellTitle(eyebrow, stringResource(R.string.onb_step3_title)) {
                Column(Modifier.width(INFO_BLOCK_W), horizontalAlignment = Alignment.CenterHorizontally) {
                    CurrentHomeRow(home)
                    Spacer(Modifier.height(14.dp))
                    // 同设置「默认桌面」页:说明跟着光标走,两段一样高地留位,页名不跳(ShellNote)
                    val homeNote = stringResource(R.string.home_settings_note)
                    val takeoverNote = if (takeover) stringResource(homeKeyNoteRes(status)) else null
                    ShellNote(
                        text = if (takeoverNote != null && target == ONB_HOME_KEY) takeoverNote else homeNote,
                        reserve = listOfNotNull(homeNote, takeoverNote),
                    )
                }
            }
        },
        right = { CapsuleColumn(items, target, { target = it }, nonce, covered = false) },
    )
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
    val toWrite = onboardingDoneToWrite(current, Layout.hasSaved(ctx))
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
    val wanted = DEFAULT_LAYOUT.flatMap { it.apps }.toSet()
    return Apps.load(ctx, extraPackages = wanted, withBitmaps = emptySet(), withLabels = wanted)
        .filterKeys { it in wanted }
        .mapValues { it.value.label }
}

/** 第 2 步屏幕上的计划:写盘用的那份布局([plannedLayout])去掉空行、配上显示名([planView])。 */
@WorkerThread
internal fun onboardingPlan(ctx: Context): List<PlanRow> {
    val labels = installedDefaultApps(ctx)
    return planView(localizedDefaultRows(plannedLayout(DEFAULT_LAYOUT, labels.keys), defaultRowNames(ctx)), labels)
}

/**
 * 第 2 步的写盘(在 [layoutWrites] 上调用:「继续 → 返回 → 跳过」两次整份写按提交顺序落盘,最后落盘的一定是最后一次按下的选择)。[fill] = 「继续」:按**此刻**的已装集合过滤分类表;
 * false = 「跳过」:三行空。@return 是否真的落盘了。
 */
@WorkerThread
internal fun writeOnboardingLayout(ctx: Context, fill: Boolean): Boolean = runCatching {
    val rows = if (fill) plannedLayout(DEFAULT_LAYOUT, installedDefaultApps(ctx).keys)
    else skippedLayout(DEFAULT_LAYOUT)
    Layout.write(ctx, localizedDefaultRows(rows, defaultRowNames(ctx)))
}.getOrDefault(false)
