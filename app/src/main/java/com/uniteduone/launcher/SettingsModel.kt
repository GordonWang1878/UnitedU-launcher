package com.uniteduone.launcher

/**
 * 设置页的**内容模型**:四个分组(R57)、每组有哪些行、每行当前在第几档、选中之后写什么。
 *
 * 为什么单独一个文件、而且**一行 Compose / Android 都不碰**:M7 之前这些信息散在
 * (已退役的两栏设置页)`SettingsScreen` 的 `controls`/`order`/`WALLPAPER_CTRLS` 三处,靠「同一个下标」互相对齐 ——
 * 加一行就要同步改三处,漏一处就是「预览判定指到了别的行」。改成两栏之后行不再是一条线性表,
 * 那种下标对齐根本不可能维持,所以先把「有什么」从「怎么画、焦点怎么走」里整个拆出来:
 * 这边是纯数据 + 纯函数,可以在 JVM 单元测试里全部钉死([SettingsModelTest]);
 * 界面那边只剩渲染与二维焦点账本,不再自己另存一份行清单。
 *
 * `labelRes` / `optionRes` 是普通的 `Int`(资源 id):这一层不认识 `Context`,
 * 文案由界面用 `stringResource` 解析 —— 语言切换后整页重建,文案自然跟着变。
 */

/** 控件种类。SWATCH = 主题色色板(选项来自 [ThemePresets]),SLIDER = 11 档滑块。 */
enum class CtrlKind { SEGMENTED, TOGGLE, SWATCH, SLIDER }

/**
 * 设置的四个分组 = 设置页外壳第一层的四颗分组胶囊(R69,2026-09-23:顺序改成 布局 / 通用 / 外观 / 屏保,
 * 与第一层一致)。
 * **R57(2026-09-23 傍晚,Gordon 定)**:只剩四组(当时顺序 通用 / 布局 / 外观 / 屏保)。取代此前的
 * 布局 / 壁纸 / 主题 / 待机与屏保 / 时钟 / 语言 /(R55 的系统)/ 其他:语言、默认桌面、待机、时钟、恢复默认并入「通用」,
 * 壁纸 + 主题并成「外观」,屏保独立成组。R56 先撤掉了 R55 的「系统」组(三行跳同一个系统页),
 * 屏保那三项合成「屏保」组末行「系统屏保 ▸」的一行摘要,动画缩放提示行进「通用」组。
 * **R128(2026-09-29 Gordon)**:每页胶囊 ≤ 6——「通用」的待机两行合成「待机」子页([SubPageRow]),恢复默认挪进关于页。
 */
enum class GroupId { LAYOUT, GENERAL, APPEARANCE, SCREENSAVER }

/** 右栏的一行。`id` 是稳定标识(测试与日志按它找行,不按下标)。 */
sealed interface RowSpec {
    val id: String
    val labelRes: Int
}

/**
 * 可改值的一行。[selected] 是「当前在第几档」—— 由 [Settings] 派生,不是界面自己记的状态,
 * 所以左右键改完值、盘上落了新值之后,下一次重组自然显示新档位(界面不持有任何档位副本)。
 * [zeroAt] 只对 SLIDER 有意义:代表 0 的档位(双向亮度滑块是 5,填充从它画到当前档)。
 * [optionArgs] 与 [optionRes] 一一对应:非 null 时该项文案是带格式参数的(如「%1$d 分」的 1/3/5/10),
 * 界面按 `stringResource(res, arg)` 解析 —— 一个资源 id 要出现在同一行的好几档里,光靠 id 分不开。
 * [noteRes](M5 spec §3):标签下方一行小字提示;null = 不画。目前只有「屏保启动」行用它(计时起点 / 图库为空)。
 */
data class ControlRow(
    override val id: String,
    override val labelRes: Int,
    val kind: CtrlKind,
    val optionRes: List<Int>,
    val count: Int,
    val selected: Int,
    val zeroAt: Int = 0,
    val optionArgs: List<Int?> = emptyList(),
    val noteRes: Int? = null,
    /**
     * 第 i 档写成什么设置(纯函数,R71)。设置页外壳拿它做两件事:确定键落盘([onSelect] 就是
     * `update { write(it, i) }`),以及**光标停在某一档时的实时预览**(`effectiveSettings` 不落盘、只把它套在
     * 已保存的设置上)。两处读同一个函数,预览看到的就一定是按确定之后会存下的样子。
     * 只有语言行是 null:切语言要 `recreate()`,不是改一个字段(见 [SettingsActions.applyLanguage])。
     */
    val write: ((Settings, Int) -> Settings)? = null,
    /** SLIDER 专用:第 i 档的数值 = [sliderMin] + i × [sliderStep](百分比);[sliderMin] < 0 时显示带正负号。 */
    val sliderMin: Int = 0,
    val sliderStep: Int = 10,
    val onSelect: (Int) -> Unit,
) : RowSpec

/** 动作行(spec §2.2 的 ▸):确定键打开一个子界面或弹确认框,没有档位。 */
data class ActionRow(
    override val id: String,
    override val labelRes: Int,
    /** 值文字;null = 不显示值(只画 ▸)。[hintParts] 非空时以它为准,不看这一项。 */
    val hintRes: Int?,
    /**
     * 非空时 [hintRes] 是带格式参数的文案(如「%1$s×,界面动画会变慢」),界面按
     * `stringResource(hintRes, *hintArgs)` 解析——与 [ControlRow.optionArgs] 同一个理由:这一层不认识
     * Context,不能自己把数字拼进字符串。用它的:「通用」组的动画缩放提示行(ui-pending #16,Int 或 String)。
     */
    val hintArgs: List<Any> = emptyList(),
    /**
     * 非空时值文字由这几段拼成(逐段解析,「 · 」连接),不看 [hintRes]。只给「系统屏保」行的摘要用(R56):
     * 那一行要把开关、来源(可能是别的应用的名字,没有资源 id)、启动时间拼成一串,单个资源 id 表达不了。
     */
    val hintParts: List<HintPart> = emptyList(),
    /**
     * 标签下方的一行小字(胶囊的第二行,与外壳第一层的说明小字同一个样式);null = 不画。
     * 只有「关闭屏幕」行用它(R127):那一页在系统设置里没有直达入口,小字写去哪改。
     */
    val noteRes: Int? = null,
    val onActivate: () -> Unit,
) : RowSpec

/**
 * **子页入口**(R128,2026-09-29 Gordon):一颗胶囊,右端是子页里各行当前值的摘要(「3 分 · 时钟」),
 * 确定键进外壳的下一层 [ShellPages.sub],那一层只有 [rows]——行本身原样搬进去(同一个 id、同一个选项层、同一个写入函数),
 * 所以进子页 → 进选项层 → 返回 → 返回的每一步都是外壳原有的栈机制,没有新的焦点账本。
 * 为什么要有它:设置页每页胶囊 ≤ 6(R128),一组放不下时先合并再加。子页不再嵌套子页。
 */
data class SubPageRow(
    override val id: String,
    override val labelRes: Int,
    val rows: List<RowSpec>,
) : RowSpec

data class GroupSpec(val id: GroupId, val titleRes: Int, val rows: List<RowSpec>)

/** 一列行连同子页里的行一起展开(子页入口在前、它的行紧跟其后)。按 id 找行的地方一律走它,子页里的行才找得到。 */
fun List<RowSpec>.withSubRows(): List<RowSpec> =
    flatMap { if (it is SubPageRow) listOf(it) + it.rows.withSubRows() else listOf(it) }

/** 所有组的所有行(含子页里的行)。 */
fun allRows(groups: List<GroupSpec>): List<RowSpec> = groups.flatMap { it.rows.withSubRows() }

/** 「待机」子页入口的 id(R128:待机时长 + 待机显示合成一颗)。也是子页的页 id 后缀(`s:standby`)。 */
const val STANDBY_ROW = "standby"

/**
 * 语言的四个选项文案,与 [VALID_LANGUAGES] 逐项对应(第 i 项的文案对应第 i 个取值)。
 * 两处读同一张表:设置页的语言行、首次引导第 1 步的四个按钮(M7 T10)——
 * 抽出来是为了不让两边各写一份、改一处漏一处(与 `Settings.kt` 那几张合法值表同一个道理)。
 */
internal val LANGUAGE_OPTION_RES: List<Int> = listOf(
    R.string.settings_lang_system,
    R.string.settings_lang_zh_cn,
    R.string.settings_lang_zh_tw,
    R.string.settings_lang_en,
)

/**
 * 设置页要做、但**只有 Activity 做得了**的几件事(开子界面、切语言、跳系统页)。
 * 模型只管把它们挂到对应的行上,不认识 `Context`;真正的实现在 `MainActivity`。
 */
class SettingsActions(
    /** R69:「布局」组第一行「编辑分栏」——打开现有的整屏编辑页(编辑页本身不改)。 */
    val openEdit: () -> Unit = {},
    val pickWallpaper: () -> Unit,
    val openImport: () -> Unit,
    val setDefaultHome: () -> Unit,
    // ~~restoreDefaults~~(R128):「恢复默认」挪出「通用」组、进关于页(第二颗胶囊),由 MainActivity 直接推确认层,不经模型。
    /** 取值是 [VALID_LANGUAGES] 里的一项。T8 起它 = 写盘 + `recreate()`;在那之前只写盘。 */
    val applyLanguage: (String) -> Unit,
    /** M5:打开屏保图库(叠在设置页上;设置页 `covered` 让路,关掉后焦点回同一行)。 */
    val openScreensaverGallery: () -> Unit,
    /** M5:跳系统屏保设置页;解析不到退到系统设置首页,两个都打不开 toast(spec §3)。 */
    val openSystemScreensaver: () -> Unit,
    /** R127:屏保组「关闭屏幕」行——跳系统设置(候选链 [SCREEN_OFF_SETTINGS_PAGES],今天只有首页)。 */
    val openSystemScreenOff: () -> Unit = {},
    /** R93:屏保组第一行「立即开始屏保」(原顶栏屏保按钮):图库空 → 提示;有图 → 关设置、进自定义屏保。 */
    val startScreensaver: () -> Unit = {},
    // ~~restoreHiddenInputs~~(R92):布局组「恢复隐藏的输入源」行随首页输入源行一起删掉,挪到「输入源」页列表末尾。
    /** ui-pending #16:「通用」组的动画缩放提示行——跳开发者选项;解析不到退到系统设置首页。 */
    val openSystemAnimationSettings: () -> Unit,
)

/**
 * 「屏保启动」行的行内提示(M5 spec §3):图库为空 →「不会进入」;待机时长为「关」→「从最后一次按键算」;
 * 否则 →「从进入待机算」。屏保启动本身是「关」时不画提示——计时起点与图库都跟它无关了。
 * [screensaverImages] = −1 表示设置页还没数完(IO 在途),按非空处理,不在打开的那一瞬间误报「图库为空」。
 */
internal fun screensaverAfterNoteRes(idleAfterMs: Long, screensaverAfterMs: Long, screensaverImages: Int): Int? = when {
    screensaverAfterMs == 0L -> null
    screensaverImages == 0 -> R.string.settings_screensaver_note_empty
    idleAfterMs == 0L -> R.string.settings_screensaver_note_from_input
    else -> R.string.settings_screensaver_note_after_standby
}

/**
 * 按当前设置 [s] 生成整棵内容树。[update] 是「读-改-写一次完成」的写入口
 * (界面传的是 `SettingsStore.update` 的包装,见 `SettingsShell.kt` 里 `SettingsShell` 的 `update`)——
 * 每行只描述**自己那一个字段**怎么改,绝不整对象回写,别的写者(选图、壁纸铺入)同时写 `wallpaperFile` 也不会被踩掉。
 *
 * 分段控件的档位顺序一律取自 `Settings.kt` 里那几张合法值表([VALID_CARDS_PER_ROW] 等):
 * 一份表两处读(夹取 + 显示顺序),才不会有人改了一处、另一处悄悄漂移。
 */
fun settingsGroups(
    s: Settings,
    update: ((Settings) -> Settings) -> Unit,
    actions: SettingsActions,
    /** 屏保图库张数(M5:「屏保启动」行的提示要分「图库为空」);−1 = 设置页还没数完。 */
    screensaverImages: Int,
    /**
     * 系统设置快照:「系统屏保 ▸」行的摘要、「关闭屏幕」行的值(R127)与「通用」组的动画缩放提示行(R56/R57)。
     * 默认 [SystemUiStatus.UNKNOWN]——既有调用点不关心,不必逐一改;UNKNOWN 下系统屏保行不显示值、
     * 「关闭屏幕」行写「查看」、动画缩放那一项读不到 → 提示行出现并写「查看」。
     */
    system: SystemUiStatus = SystemUiStatus.UNKNOWN,
): List<GroupSpec> {
    val onOff = listOf(R.string.settings_off, R.string.settings_on)
    /** 可改值的一行:[ControlRow.onSelect] 一律由 [ControlRow.write] 派生(`update { write(it, i) }`),两者不会各写一套。 */
    fun ctl(
        id: String, labelRes: Int, kind: CtrlKind, optionRes: List<Int>, count: Int, selected: Int,
        optionArgs: List<Int?> = emptyList(), noteRes: Int? = null, zeroAt: Int = 0,
        sliderMin: Int = 0, sliderStep: Int = 10,
    ): ControlRow {
        val write = optionWrite(id) ?: error("没有写入函数的控件行: $id")
        return ControlRow(
            id = id, labelRes = labelRes, kind = kind, optionRes = optionRes, count = count, selected = selected,
            zeroAt = zeroAt, optionArgs = optionArgs, noteRes = noteRes, write = write,
            sliderMin = sliderMin, sliderStep = sliderStep,
            onSelect = { i -> update { write(it, i) } },
        )
    }
    fun toggle(id: String, labelRes: Int, value: Boolean) =
        ctl(id, labelRes, CtrlKind.TOGGLE, onOff, 2, if (value) 1 else 0)
    val minutes = R.string.settings_idle_minutes

    return listOf(
        // R69(2026-09-23 设置页胶囊外壳,Gordon 定):四组的顺序 = 外壳第一层胶囊的顺序——布局 / 通用 / 外观 / 屏保
        // (R57 的两栏时代是 通用 / 布局 / 外观 / 屏保)。布局放最上:改版后「编辑分栏」住在这一组第一行,
        // 它是最常用的入口。
        GroupSpec(
            GroupId.LAYOUT, R.string.settings_group_layout,
            listOfNotNull(
                // R69:原齿轮菜单第一项「编辑分栏」挪进来,打开的仍是同一个整屏编辑页。
                ActionRow("editLayout", R.string.menu_edit, R.string.menu_edit_desc) { actions.openEdit() },
                ctl(
                    id = "cardsPerRow", labelRes = R.string.settings_card_size,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(
                        R.string.settings_card_large,
                        R.string.settings_card_medium,
                        R.string.settings_card_small,
                    ),
                    count = VALID_CARDS_PER_ROW.size,
                    // 表里找不到(理论上不可能,读盘就夹过)时退到「中」,不让下标变成 −1。
                    selected = VALID_CARDS_PER_ROW.indexOf(s.cardsPerRow).let { if (it < 0) 1 else it },
                ),
                toggle("showTitles", R.string.settings_show_titles, s.showTitles),
                // ~~「输入源行」开关 / 「恢复隐藏的输入源」~~(R92,2026-09-27 Gordon):首页不再有输入源行,
                // 输入源(连同隐藏 / 恢复)搬到顶栏「输入源」胶囊打开的页面。
                // **R120(2026-09-28 Gordon)**:卡片的三条色彩滑块从「外观」挪到这里,跟在「卡片标题」之后,顺序不变
                // (饱和度、亮度、透明度)——跟卡片有关的都在「布局」一组里调;左侧桌面预览照旧(布局组本来就带预览)。
                // R70:卡片淡化(R49)的两个参数做成滑块。饱和度 0–100% 步 10,亮度 50–100% 步 5——
                // 亮度下限 50:再暗卡片就与深色底糊成一片,认不出是哪个应用。
                ctl(
                    id = "cardSaturation", labelRes = R.string.settings_card_saturation,
                    kind = CtrlKind.SLIDER, optionRes = emptyList(),
                    count = 11, selected = (s.cardSaturation - CARD_SATURATION_MIN) / CARD_SATURATION_STEP,
                    sliderMin = CARD_SATURATION_MIN, sliderStep = CARD_SATURATION_STEP,
                ),
                ctl(
                    id = "cardBrightness", labelRes = R.string.settings_card_brightness,
                    kind = CtrlKind.SLIDER, optionRes = emptyList(),
                    count = 11, selected = (s.cardBrightness - CARD_BRIGHTNESS_MIN) / CARD_BRIGHTNESS_STEP,
                    sliderMin = CARD_BRIGHTNESS_MIN, sliderStep = CARD_BRIGHTNESS_STEP,
                ),
                // R86:卡片不透明度 40–100% 步 10(7 档),缺省 100。只压未聚焦的卡,焦点卡恒 100%(见 AppCard)。
                // R87(2026-09-27 owner):界面上反过来叫「卡片透明度」0–60%、缺省 0、往右加——更符合直觉。
                // 存盘仍是 cardOpacity(不迁移),界面值 = 100 − cardOpacity。
                ctl(
                    id = "cardOpacity", labelRes = R.string.settings_card_opacity,
                    kind = CtrlKind.SLIDER, optionRes = emptyList(),
                    count = (100 - CARD_OPACITY_MIN) / CARD_OPACITY_STEP + 1,
                    selected = (100 - s.cardOpacity) / CARD_OPACITY_STEP,
                    sliderMin = 0, sliderStep = CARD_OPACITY_STEP,
                ),
            ),
        ),
        // R57:「通用」= 语言、默认桌面、待机、时钟这些「装好先调一次」的项。
        // R128(2026-09-29 Gordon,方案 A):每页胶囊 ≤ 6——待机时长 + 待机显示合成一颗「待机」子页入口(放在原「待机时长」的位置),
        // 「恢复默认」挪进关于页。现在 5 颗常驻 + 动画缩放条件行 = 最多 6。
        GroupSpec(
            GroupId.GENERAL, R.string.settings_group_general,
            listOfNotNull(
                ControlRow(
                    id = "language", labelRes = R.string.settings_language,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = LANGUAGE_OPTION_RES,
                    count = VALID_LANGUAGES.size,
                    // 读盘时已经夹过(非法值 → system),找不到再退一次 0,不让下标变成 −1。
                    selected = VALID_LANGUAGES.indexOf(s.language).coerceAtLeast(0),
                    // **不走 update**:切语言要重建 Activity(spec §5),那是 Activity 的事。
                    // 这一行只负责把档位翻译成合法取值交出去,写盘与 recreate 都在 applyLanguage 里。
                    // 没有 write:切语言不是「改一个字段看效果」,外壳里它也不做实时预览(通用组没有预览)。
                    onSelect = { i -> actions.applyLanguage(VALID_LANGUAGES[i]) },
                ),
                ActionRow("setDefaultHome", R.string.menu_set_default_home, R.string.menu_set_default_home_desc) {
                    actions.setDefaultHome()
                },
                // R60(2026-09-23 傍晚,Gordon 定):原外观组「导入图片」改名「手机传输」挪到这里——手机传的不只是
                // 壁纸,还有卡片图、屏保图片和 APK,放在「外观」里名不副实。打开的仍是同一个扫码页(openImport)。
                ActionRow("openImport", R.string.settings_phone_transfer, R.string.settings_phone_transfer_desc) {
                    actions.openImport()
                },
                // R128:「待机」子页——里面就是原来的两行,id / 选项 / 写入函数逐字未动;胶囊右端显示两行当前值的摘要。
                SubPageRow(
                    STANDBY_ROW, R.string.settings_standby,
                    listOf(
                        ctl(
                            id = "idleAfter", labelRes = R.string.settings_idle_after,
                            kind = CtrlKind.SEGMENTED,
                            optionRes = listOf(R.string.settings_idle_off, minutes, minutes, minutes, minutes),
                            optionArgs = listOf(null, 1, 3, 5, 10),
                            count = VALID_IDLE_AFTER_MS.size,
                            selected = VALID_IDLE_AFTER_MS.indexOf(s.idleAfterMs).let { if (it < 0) 2 else it },
                        ),
                        ctl(
                            id = "idleContent", labelRes = R.string.settings_idle_content,
                            kind = CtrlKind.SEGMENTED,
                            optionRes = listOf(
                                R.string.settings_idle_clock,
                                R.string.settings_idle_black,
                                R.string.settings_idle_nofade,
                            ),
                            count = IdleContent.entries.size,
                            selected = IdleContent.entries.indexOf(s.idleContent).coerceAtLeast(0),
                        ),
                    ),
                ),
                // R57:原「时钟」组那一个开关改成二选一,紧跟待机(待机时留在屏上的就是这个时钟)。
                // 映射既有的 showDate,存盘键不变。
                ctl(
                    id = "clockDisplay", labelRes = R.string.settings_clock_display,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(R.string.settings_clock_time_only, R.string.settings_clock_time_date),
                    count = 2, selected = if (s.showDate) 1 else 0,
                ),
                // 动画缩放提示行(仅 ≠ 1× 或读不到时出现;R56 时在「其他」组顶上,R57 放「恢复默认」之前)。
                // R128 起「恢复默认」挪进关于页,它成了组末行;行数变化时的焦点交接照旧靠外壳胶囊列「目标按行 id 记」——
                // id 还在就跟着那一行走,这一行自己没了落到上一行(时钟显示)。
                animScaleRow(system, actions),
            ),
        ),
        // R57:原「壁纸」「主题」两组合成「外观」:先壁纸(换 / 调),再主题色。R70 / R86 加在末尾的三条卡片色彩滑块 R120 挪到「布局」组。
        GroupSpec(
            GroupId.APPEARANCE, R.string.settings_group_appearance,
            listOf(
                // 从齿轮菜单搬进来的动作行(spec §1)。原来还有一条「导入图片」,R60 改名「手机传输」挪到「通用」组;
                // 「壁纸自动切换」R61 删掉。
                ActionRow("pickWallpaper", R.string.menu_wallpaper, R.string.menu_wallpaper_desc) {
                    actions.pickWallpaper()
                },
                ctl(
                    id = "wallpaperBlur", labelRes = R.string.settings_wallpaper_blur,
                    kind = CtrlKind.SLIDER, optionRes = emptyList(),
                    // R119:0–50% 步 5,仍 11 档
                    count = WALLPAPER_BLUR_MAX / WALLPAPER_BLUR_STEP + 1, selected = s.wallpaperBlur / WALLPAPER_BLUR_STEP,
                    sliderStep = WALLPAPER_BLUR_STEP,
                ),
                ctl(
                    id = "wallpaperBrightness", labelRes = R.string.settings_wallpaper_brightness,
                    kind = CtrlKind.SLIDER, optionRes = emptyList(),
                    // −50…+50 步 10:11 档双向滑块,第 5 档 = 0 = 原片。
                    count = 11, selected = (s.wallpaperBrightness + 50) / 10, zeroAt = 5, sliderMin = -50,
                ),
                ctl(
                    id = "themeColor", labelRes = R.string.settings_theme_color,
                    kind = CtrlKind.SWATCH, optionRes = ThemePresets.all.map { it.nameRes },
                    count = ThemePresets.all.size, selected = ThemePresets.indexOf(s.themePresetId),
                ),
                toggle("followWallpaper", R.string.settings_follow_wallpaper, s.followWallpaperColor),
                // ~~卡片饱和度 / 卡片亮度 / 卡片透明度~~:R120 挪到「布局」组「卡片标题」之后。
            ),
        ),
        GroupSpec(
            GroupId.SCREENSAVER, R.string.settings_group_screensaver,
            listOf(
                // R93(2026-09-27 Gordon):顶栏屏保按钮挪到这里,放最上面。按下去当场开始,不进外壳的下一层。
                ActionRow("startScreensaver", R.string.settings_start_screensaver, hintRes = null) {
                    actions.startScreensaver()
                },
                // M5 spec §3:进入待机后再过多久进自定义屏保;小字说明计时起点 / 图库为空(screensaverAfterNoteRes)。
                ctl(
                    id = "screensaverAfter", labelRes = R.string.settings_screensaver_after,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(R.string.settings_idle_off, minutes, minutes, minutes, minutes),
                    optionArgs = listOf(null, 1, 5, 10, 30),
                    count = VALID_SCREENSAVER_AFTER_MS.size,
                    // 读盘已夹过;万一找不到退到默认 5 分(第 2 档),不让下标变成 −1。
                    selected = VALID_SCREENSAVER_AFTER_MS.indexOf(s.screensaverAfterMs).let { if (it < 0) 2 else it },
                    noteRes = screensaverAfterNoteRes(s.idleAfterMs, s.screensaverAfterMs, screensaverImages),
                ),
                ctl(
                    id = "screensaverInterval", labelRes = R.string.settings_screensaver_interval,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(R.string.settings_seconds, minutes, minutes),
                    optionArgs = listOf(30, 1, 5),
                    count = VALID_SCREENSAVER_INTERVAL_MS.size,
                    selected = VALID_SCREENSAVER_INTERVAL_MS.indexOf(s.screensaverIntervalMs).coerceAtLeast(0),
                ),
                // 两条动作行(spec §3):图库叠在设置页上;系统屏保跳系统页。都只有 Activity 做得了,走 actions。
                ActionRow(
                    "screensaverGallery",
                    R.string.settings_screensaver_gallery,
                    R.string.settings_screensaver_gallery_desc,
                ) { actions.openScreensaverGallery() },
                // R56:值是系统屏保的摘要「开 · UnitedU · 5 分钟」(screensaverSummary;读不到的部分省略,全读不到不显示值)。
                // 确定键仍走 MainActivity.openSystemPage 的候选链 + 弹回检测(cc7b3cf)。
                ActionRow(
                    "systemScreensaver",
                    R.string.settings_system_screensaver,
                    hintRes = null,
                    hintParts = screensaverSummary(system),
                    onActivate = { actions.openSystemScreensaver() },
                ),
                // R127(2026-09-29 Gordon):紧跟「系统屏保」。值读 secure sleep_timeout(「无操作 24 小时后」;读不到「查看」),
                // 小字写系统里去哪改;确定键先试索尼节能控制面板(R127b),别的品牌落系统设置首页,小字随落点变。只读,不写任何系统设置。
                // 常驻行(不是条件行),胶囊 id 就是行 id。
                ActionRow(
                    "screenOff",
                    R.string.settings_screen_off,
                    hintRes = null,
                    hintParts = screenOffSummary(system.screenOff),
                    noteRes = screenOffNoteRes(system.screenOffWhere),
                    onActivate = { actions.openSystemScreenOff() },
                ),
            ),
        ),
    )
}

/**
 * 每个可改值行的「第 i 档 → 设置」纯函数(R71)。[settingsGroups] 的确定键与设置页外壳的实时预览
 * (`effectiveSettings`)读的是同一张表。语言不在表里(见 [ControlRow.write]);返回 null = 没有这一行。
 * 档位顺序一律取自 `Settings.kt` 的合法值表,与 [settingsGroups] 的 `selected` 同源。
 */
internal fun optionWrite(rowId: String): ((Settings, Int) -> Settings)? = when (rowId) {
    "cardsPerRow" -> { s, i -> s.copy(cardsPerRow = VALID_CARDS_PER_ROW[i]) }
    "showTitles" -> { s, i -> s.copy(showTitles = i == 1) }
    "idleAfter" -> { s, i -> s.copy(idleAfterMs = VALID_IDLE_AFTER_MS[i]) }
    "idleContent" -> { s, i -> s.copy(idleContent = IdleContent.entries[i]) }
    "clockDisplay" -> { s, i -> s.copy(showDate = i == 1) }
    "wallpaperBlur" -> { s, i -> s.copy(wallpaperBlur = i * WALLPAPER_BLUR_STEP) }
    "wallpaperBrightness" -> { s, i -> s.copy(wallpaperBrightness = i * 10 - 50) }
    "themeColor" -> { s, i -> s.copy(themePresetId = ThemePresets.all[i].id) }
    "followWallpaper" -> { s, i -> s.copy(followWallpaperColor = i == 1) }
    "cardSaturation" -> { s, i -> s.copy(cardSaturation = CARD_SATURATION_MIN + i * CARD_SATURATION_STEP) }
    "cardBrightness" -> { s, i -> s.copy(cardBrightness = CARD_BRIGHTNESS_MIN + i * CARD_BRIGHTNESS_STEP) }
    "cardOpacity" -> { s, i -> s.copy(cardOpacity = 100 - i * CARD_OPACITY_STEP) }  // R87:档位 = 透明度
    "screensaverAfter" -> { s, i -> s.copy(screensaverAfterMs = VALID_SCREENSAVER_AFTER_MS[i]) }
    "screensaverInterval" -> { s, i -> s.copy(screensaverIntervalMs = VALID_SCREENSAVER_INTERVAL_MS[i]) }
    else -> null
}

/**
 * 「通用」组末行的「系统动画缩放」提示行(ui-pending #16 起;R56/R57 从已撤掉的「系统」组挪来,行为不变;
 * R57 时在「恢复默认」之前,R128 恢复默认挪进关于页后它是组末):
 * **条件行**,只在 [animScaleNotice] 非 null 时出现(三项缩放任一 ≠ 1×,或动画程序那一项读不到);
 * `animator_duration_scale` ≠ 1 时写「1.25×,界面动画会变慢」,只有窗口 / 过渡 ≠ 1 时写两者的值并注明只影响
 * 应用切换,读不到写「查看」。确定键跳开发者选项。只读,我们不写任何系统设置。
 */
internal fun animScaleRow(sys: SystemUiStatus, actions: SettingsActions): ActionRow? {
    val n = animScaleNotice(sys.animatorScale, sys.transitionScale, sys.windowScale) ?: return null
    val (res, args) = when (n) {
        AnimScaleNotice.Unreadable -> R.string.settings_sys_view to emptyList()
        is AnimScaleNotice.Animator -> when {
            n.scale <= 0f -> R.string.settings_sys_anim_off to emptyList()
            n.scale > 1f -> R.string.settings_sys_anim_slower to listOf<Any>(formatScale(n.scale))
            else -> R.string.settings_sys_anim_faster to listOf<Any>(formatScale(n.scale))
        }
        is AnimScaleNotice.WindowOnly -> R.string.settings_sys_anim_window to
            listOf<Any>(formatScale(n.window), formatScale(n.transition))
    }
    return ActionRow(
        "systemAnimationScale", R.string.settings_sys_anim_scale,
        hintRes = res, hintArgs = args, onActivate = { actions.openSystemAnimationSettings() },
    )
}
