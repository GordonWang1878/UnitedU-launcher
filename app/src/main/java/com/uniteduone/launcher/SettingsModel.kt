package com.uniteduone.launcher

/**
 * 设置页的**内容模型**:七个分组、每组有哪些行、每行当前在第几档、选中之后写什么。
 *
 * 为什么单独一个文件、而且**一行 Compose / Android 都不碰**:M7 之前这些信息散在
 * `SettingsScreen` 的 `controls`/`order`/`WALLPAPER_CTRLS` 三处,靠「同一个下标」互相对齐 ——
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

/** 左栏的七个分组。顺序即 spec §2.2 表格的顺序,列表下标 = 左栏焦点账本里的 `group`。 */
enum class GroupId { LAYOUT, WALLPAPER, THEME, STANDBY, CLOCK, LANGUAGE, OTHER }

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
    val onSelect: (Int) -> Unit,
) : RowSpec

/** 动作行(spec §2.2 的 ▸):确定键打开一个子界面或弹确认框,没有档位。 */
data class ActionRow(
    override val id: String,
    override val labelRes: Int,
    val hintRes: Int,
    val onActivate: () -> Unit,
) : RowSpec

data class GroupSpec(val id: GroupId, val titleRes: Int, val rows: List<RowSpec>)

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
 * 设置页要做、但**只有 Activity 做得了**的七件事(开子界面、切语言、跳系统页)。
 * 模型只管把它们挂到对应的行上,不认识 `Context`;真正的实现在 `MainActivity`。
 */
class SettingsActions(
    val pickWallpaper: () -> Unit,
    val openImport: () -> Unit,
    val setDefaultHome: () -> Unit,
    val restoreDefaults: () -> Unit,
    /** 取值是 [VALID_LANGUAGES] 里的一项。T8 起它 = 写盘 + `recreate()`;在那之前只写盘。 */
    val applyLanguage: (String) -> Unit,
    /** M5:打开屏保图库(叠在设置页上;设置页 `covered` 让路,关掉后焦点回同一行)。 */
    val openScreensaverGallery: () -> Unit,
    /** M5:跳系统屏保设置页;解析不到退到系统设置首页,两个都打不开 toast(spec §3)。 */
    val openSystemScreensaver: () -> Unit,
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
 * (界面传的是 `SettingsStore.update` 的包装,见 `SettingsScreen.update`)——
 * 每行只描述**自己那一个字段**怎么改,绝不整对象回写,后台轮播同时写 `wallpaperFile` 也不会被踩掉。
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
): List<GroupSpec> {
    val onOff = listOf(R.string.settings_off, R.string.settings_on)
    fun toggle(id: String, labelRes: Int, value: Boolean, write: (Settings, Boolean) -> Settings) =
        ControlRow(
            id = id, labelRes = labelRes, kind = CtrlKind.TOGGLE,
            optionRes = onOff, count = 2, selected = if (value) 1 else 0,
            onSelect = { i -> update { write(it, i == 1) } },
        )

    return listOf(
        GroupSpec(
            GroupId.LAYOUT, R.string.settings_group_layout,
            listOf(
                ControlRow(
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
                    onSelect = { i -> update { it.copy(cardsPerRow = VALID_CARDS_PER_ROW[i]) } },
                ),
                toggle("showTitles", R.string.settings_show_titles, s.showTitles) { st, v ->
                    st.copy(showTitles = v)
                },
                toggle("showInputRow", R.string.settings_show_input_row, s.showInputRow) { st, v ->
                    st.copy(showInputRow = v)
                },
            ),
        ),
        GroupSpec(
            GroupId.WALLPAPER, R.string.settings_group_wallpaper,
            listOf(
                // 两条动作行从齿轮菜单搬进来(spec §1):菜单只剩四项,壁纸相关的事都在壁纸组里。
                ActionRow("pickWallpaper", R.string.menu_wallpaper, R.string.menu_wallpaper_desc) {
                    actions.pickWallpaper()
                },
                ActionRow("openImport", R.string.menu_import, R.string.menu_import_desc) {
                    actions.openImport()
                },
                ControlRow(
                    id = "wallpaperRotate", labelRes = R.string.settings_wallpaper_rotate,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(
                        R.string.settings_off,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                        R.string.settings_rotate_daily,
                    ),
                    optionArgs = listOf(null, 5, 30, null),
                    count = VALID_WALLPAPER_ROTATE_MS.size,
                    selected = VALID_WALLPAPER_ROTATE_MS.indexOf(s.wallpaperRotateMs)
                        .let { if (it < 0) 0 else it },
                    onSelect = { i -> update { it.copy(wallpaperRotateMs = VALID_WALLPAPER_ROTATE_MS[i]) } },
                ),
                ControlRow(
                    id = "wallpaperBlur", labelRes = R.string.settings_wallpaper_blur,
                    kind = CtrlKind.SLIDER, optionRes = emptyList(),
                    count = 11, selected = s.wallpaperBlur / 10,
                    onSelect = { i -> update { it.copy(wallpaperBlur = i * 10) } },
                ),
                ControlRow(
                    id = "wallpaperBrightness", labelRes = R.string.settings_wallpaper_brightness,
                    kind = CtrlKind.SLIDER, optionRes = emptyList(),
                    // −50…+50 步 10:11 档双向滑块,第 5 档 = 0 = 原片。
                    count = 11, selected = (s.wallpaperBrightness + 50) / 10, zeroAt = 5,
                    onSelect = { i -> update { it.copy(wallpaperBrightness = i * 10 - 50) } },
                ),
            ),
        ),
        GroupSpec(
            GroupId.THEME, R.string.settings_group_theme,
            listOf(
                ControlRow(
                    id = "themeColor", labelRes = R.string.settings_theme_color,
                    kind = CtrlKind.SWATCH, optionRes = emptyList(),
                    count = ThemePresets.all.size, selected = ThemePresets.indexOf(s.themePresetId),
                    onSelect = { i -> update { it.copy(themePresetId = ThemePresets.all[i].id) } },
                ),
                toggle("followWallpaper", R.string.settings_follow_wallpaper, s.followWallpaperColor) { st, v ->
                    st.copy(followWallpaperColor = v)
                },
                toggle("themedCards", R.string.settings_themed_cards, s.themedCards) { st, v ->
                    st.copy(themedCards = v)
                },
            ),
        ),
        GroupSpec(
            GroupId.STANDBY, R.string.settings_group_standby,
            listOf(
                ControlRow(
                    id = "idleAfter", labelRes = R.string.settings_idle_after,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(
                        R.string.settings_idle_off,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                    ),
                    optionArgs = listOf(null, 1, 3, 5, 10),
                    count = VALID_IDLE_AFTER_MS.size,
                    selected = VALID_IDLE_AFTER_MS.indexOf(s.idleAfterMs).let { if (it < 0) 2 else it },
                    onSelect = { i -> update { it.copy(idleAfterMs = VALID_IDLE_AFTER_MS[i]) } },
                ),
                ControlRow(
                    id = "idleContent", labelRes = R.string.settings_idle_content,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(
                        R.string.settings_idle_clock,
                        R.string.settings_idle_black,
                        R.string.settings_idle_nofade,
                    ),
                    count = IdleContent.entries.size,
                    selected = IdleContent.entries.indexOf(s.idleContent).coerceAtLeast(0),
                    onSelect = { i -> update { it.copy(idleContent = IdleContent.entries[i]) } },
                ),
                // M5 spec §3:进入待机后再过多久进自定义屏保;行内小字说明计时起点 / 图库为空(screensaverAfterNoteRes)。
                ControlRow(
                    id = "screensaverAfter", labelRes = R.string.settings_screensaver_after,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(
                        R.string.settings_idle_off,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                    ),
                    optionArgs = listOf(null, 1, 5, 10, 30),
                    count = VALID_SCREENSAVER_AFTER_MS.size,
                    // 读盘已夹过;万一找不到退到默认 5 分(第 2 档),不让下标变成 −1。
                    selected = VALID_SCREENSAVER_AFTER_MS.indexOf(s.screensaverAfterMs).let { if (it < 0) 2 else it },
                    noteRes = screensaverAfterNoteRes(s.idleAfterMs, s.screensaverAfterMs, screensaverImages),
                    onSelect = { i -> update { it.copy(screensaverAfterMs = VALID_SCREENSAVER_AFTER_MS[i]) } },
                ),
                ControlRow(
                    id = "screensaverInterval", labelRes = R.string.settings_screensaver_interval,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(
                        R.string.settings_seconds,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                    ),
                    optionArgs = listOf(30, 1, 5),
                    count = VALID_SCREENSAVER_INTERVAL_MS.size,
                    selected = VALID_SCREENSAVER_INTERVAL_MS.indexOf(s.screensaverIntervalMs).coerceAtLeast(0),
                    onSelect = { i -> update { it.copy(screensaverIntervalMs = VALID_SCREENSAVER_INTERVAL_MS[i]) } },
                ),
                // 两条动作行(spec §3):图库叠在设置页上;系统屏保跳系统页。都只有 Activity 做得了,走 actions。
                ActionRow(
                    "screensaverGallery",
                    R.string.settings_screensaver_gallery,
                    R.string.settings_screensaver_gallery_desc,
                ) { actions.openScreensaverGallery() },
                ActionRow(
                    "systemScreensaver",
                    R.string.settings_system_screensaver,
                    R.string.settings_system_screensaver_desc,
                ) { actions.openSystemScreensaver() },
            ),
        ),
        GroupSpec(
            GroupId.CLOCK, R.string.settings_group_clock,
            listOf(
                toggle("showDate", R.string.settings_show_date, s.showDate) { st, v ->
                    st.copy(showDate = v)
                },
            ),
        ),
        GroupSpec(
            GroupId.LANGUAGE, R.string.settings_group_language,
            listOf(
                ControlRow(
                    id = "language", labelRes = R.string.settings_language,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = LANGUAGE_OPTION_RES,
                    count = VALID_LANGUAGES.size,
                    // 读盘时已经夹过(非法值 → system),找不到再退一次 0,不让下标变成 −1。
                    selected = VALID_LANGUAGES.indexOf(s.language).coerceAtLeast(0),
                    // **不走 update**:切语言要重建 Activity(spec §5),那是 Activity 的事。
                    // 这一行只负责把档位翻译成合法取值交出去,写盘与 recreate 都在 applyLanguage 里。
                    onSelect = { i -> actions.applyLanguage(VALID_LANGUAGES[i]) },
                ),
            ),
        ),
        GroupSpec(
            GroupId.OTHER, R.string.settings_group_other,
            listOf(
                ActionRow("setDefaultHome", R.string.menu_set_default_home, R.string.menu_set_default_home_desc) {
                    actions.setDefaultHome()
                },
                ActionRow(
                    "restoreDefaults",
                    R.string.settings_action_restore_defaults,
                    R.string.settings_action_restore_defaults_desc,
                ) { actions.restoreDefaults() },
            ),
        ),
    )
}
