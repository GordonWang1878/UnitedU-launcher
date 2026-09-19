package com.uniteduone.launcher

/**
 * 首次启动引导(spec §8)里**不碰 Android** 的那一半,全部可以在 JVM 上钉死([OnboardingPureTest])。
 *
 * 引导的所有「规则」都在这里:第 2 步两个按钮各写下什么布局、屏幕上列出什么;`onCreate` 的三态
 * 判定;返回键往哪一步走;`recreate()` 之后步骤号怎么还原。`Onboarding.kt` 只画和管焦点,
 * `MainActivity` 只接线——它们不再各自另写一份判断。
 *
 * 布局的内存形状与 `Layout.read`/`Layout.write` 一致:`LayoutRow`(有序)。
 */

/** 引导一共三步:1 语言 / 2 铺应用 / 3 设默认桌面。 */
internal const val ONBOARDING_STEPS = 3

/**
 * 第 2 步「继续」要写进 layout.json 的布局:内置分类表**逐行**按已装过滤。
 *
 * - 行名、行序、行内顺序都照抄 [default];
 * - **行保留,哪怕过滤后是空的**——空行在首页不显示(`buildRows` 会丢掉),但编辑页照样画出这一行
 *   和它行尾的「＋」,用户之后还能往里加;而一行都没有的文件会被 `Layout.read` 当成损坏;
 * - 只做减法:已装但不在分类表里的应用一个都不加(零推荐,spec §0「只铺分类表命中的」)。
 *   于是「从未安装的默认条目」就此从文件里消失,之后装上也不会自己冒出来。
 */
internal fun plannedLayout(
    default: List<LayoutRow>,
    installed: Set<String>,
): List<LayoutRow> =
    default.map { it.copy(apps = it.apps.filter { p -> p in installed }) }

/** 第 2 步「跳过」:三行保留、`apps` 清空(spec §8)。桌面从空白开始,由用户自己在编辑分栏里添加。 */
internal fun skippedLayout(default: List<LayoutRow>): List<LayoutRow> =
    default.map { it.copy(apps = emptyList()) }

/**
 * 第 2 步屏幕上的「将放到桌面的应用」:就是 [planned](= [plannedLayout] 的结果)去掉空行,
 * 每个包配上显示名。从写盘用的那份布局**派生**,而不是另算一遍,列出来的与写下去的天然一致。
 *
 * [labels] 的取值来自 `Apps.load`(读不到标签时是空串);空白或缺失时退回包名——
 * 列表里绝不出现一个没有名字的空位。
 */
internal fun planView(
    planned: List<LayoutRow>,
    labels: Map<String, String>,
): List<Pair<String, List<Pair<String, String>>>> =
    planned.mapNotNull { row ->
        if (row.apps.isEmpty()) null
        else row.name to row.apps.map { pkg -> pkg to (labels[pkg]?.takeIf { it.isNotBlank() } ?: pkg) }
    }

/**
 * `onCreate` 的三态判定(spec §8):返回要写回 `settings.json` 的 `onboardingDone`;
 * `null` = 这个字段早就判定过了,什么都不写。
 *
 * - 缺省 + layout.json 在 → **老用户**(任何跑过旧版本的人都有它:`Layout.read` 在文件缺失时
 *   会把默认布局写出来)→ `true`,不打扰;
 * - 缺省 + layout.json 不在 → **新装**(或 `pm clear`)→ `false`,显示引导;
 * - 已经是 `true`/`false` → 不再看 layout.json。这一条是必须的:引导开着时底下的首页照常
 *   `Layout.read`,默认布局在引导第一帧之后就落盘了;若每次冷启动都重判,一个走到一半被杀掉的
 *   新用户下次启动就会被误判成老用户,引导再也不出现。
 */
internal fun onboardingDoneToWrite(current: Boolean?, layoutJsonExists: Boolean): Boolean? =
    if (current != null) null else layoutJsonExists

/**
 * 只有明确的 `false` 才显示引导。`null` 只可能出现在「外置存储没挂、判定没做成」的时候——
 * 那时连 `onboardingDone = true` 都写不下去,开了引导也结束不掉,不如不打扰。
 */
internal fun shouldShowOnboarding(done: Boolean?): Boolean = done == false

/** 返回键:回上一步;第 1 步返回 `null` = 结束整个引导(spec §8「第 1 步 BACK = 跳过整个引导」)。 */
internal fun onboardingBack(step: Int): Int? = if (step <= 1) null else step - 1

/**
 * Activity 重建后从 Bundle 还原的步骤号。键不存在时 `getInt` 给 0 → 第 1 步;越界一律夹进 1..3,
 * 界面拿到的永远是一个画得出来的步骤。
 */
internal fun restoredOnboardingStep(saved: Int): Int = saved.coerceIn(1, ONBOARDING_STEPS)

/**
 * 语言取值在 [VALID_LANGUAGES](也就是 [LANGUAGE_OPTION_RES] 的按钮顺序)里的下标;
 * 不认识的值退回 0(跟随系统)——与 `parseSettings` 把非法值读成 `system` 同一口径。
 */
internal fun languageIndex(language: String): Int = VALID_LANGUAGES.indexOf(language).coerceAtLeast(0)
