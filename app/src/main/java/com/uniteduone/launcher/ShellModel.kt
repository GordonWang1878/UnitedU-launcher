package com.uniteduone.launcher

/**
 * **设置页胶囊外壳的纯模型**(R69,2026-09-23):导航栈、页 id、每页缺省焦点、实时预览的
 * 「预览—保存—放弃」状态机、滑块步进、胶囊间距、预览框几何。一行 Compose / Android 都不碰,
 * 全部在 JVM 上钉死([ShellModelTest]);界面(`SettingsShell.kt`)只负责画与焦点账本。
 *
 * **栈为什么住在 MainActivity、而且每帧记的是「焦点目标的 id」**(不是下标):
 * - 进下一层时父帧的 [ShellFrame.focus] 原样留着,返回时父层按它落焦——「返回落回进入时那颗胶囊」
 *   不需要任何额外的记号;
 * - 用 id:条件行(「恢复隐藏的输入源」「动画缩放」)出现 / 消失时,目标自动跟着那一行走,
 *   不会像下标那样静默指到邻行(R57「按行 id 重映射」的同一个道理,这里干脆从源头记 id);
 * - 「编辑分栏」进的是整屏编辑页,那时外壳不在组合里;切语言要 `recreate()`——两条路都要求这份状态活在
 *   外壳之外。
 */
data class ShellFrame(
    /** 页 id,见 [ShellPages]。 */
    val page: String,
    /**
     * 这一层的**焦点目标**(胶囊 id)。跟着用户的导航走,还原期间冻结(铁律 5,见 `CapsuleColumn`);
     * null = 还没定,落到该页的 [defaultFocus]。
     */
    val focus: String? = null,
)

/** 页 id 的拼法。只有这几种;[decodeShellStack] 按它校验,认不出的整栈丢掉。 */
object ShellPages {
    const val ROOT = "root"
    /** 「设置默认桌面」页(取代 M7 的 `HomeSettingsCard` 浮层)。 */
    const val HOME = "home"
    /** 「恢复默认」确认页(取代 `ConfirmDialog` 那一处用法)。 */
    const val RESTORE = "restore"
    /** 「更新通道」二选一页(R164):从关于页的「更新通道」进来,与 [RESTORE] 同属关于页让开的那类层。 */
    const val CHANNEL = "channel"
    /** 第一层的两颗不进下一层的胶囊:跳系统设置、打开关于页。 */
    const val SYSTEM_SETTINGS = "systemSettings"
    const val ABOUT = "about"

    fun group(id: GroupId) = "g:${id.name}"
    fun options(rowId: String) = "o:$rowId"
    /** R128:子页(「待机」)——页 id = `s:` + 子页入口那一行的 id。 */
    fun sub(rowId: String) = "s:$rowId"
    fun groupOf(page: String): GroupId? =
        page.removePrefix("g:").takeIf { page.startsWith("g:") }?.let { n -> GroupId.entries.firstOrNull { it.name == n } }
    fun optionsRow(page: String): String? = page.removePrefix("o:").takeIf { page.startsWith("o:") && it.isNotEmpty() }
    fun subRow(page: String): String? = page.removePrefix("s:").takeIf { page.startsWith("s:") && it.isNotEmpty() }
}

/** 认得的子页(解 Bundle 里的栈时校验用;模型里每加一个 [SubPageRow] 就加进来,[ShellModelTest] 钉住两边一致)。 */
val SUB_PAGE_ROWS: Set<String> = setOf(STANDBY_ROW)

/** 选项层里第 i 档那颗胶囊的 id。 */
fun optionId(i: Int) = "opt:$i"
fun optionIndex(id: String?): Int? = id?.removePrefix("opt:")?.takeIf { id.startsWith("opt:") }?.toIntOrNull()

/** 「恢复默认」页两颗胶囊、「默认桌面」页两颗胶囊的 id。 */
const val SHELL_CANCEL = "cancel"
const val SHELL_CONFIRM = "confirm"
const val SHELL_CHANGE_HOME = "changeHome"
/** R162:「主页键接管」条件胶囊(不是默认桌面、或服务已开着时才画)。 */
const val SHELL_HOME_TAKEOVER = "homeTakeover"

/** 「恢复默认」确认层的胶囊,自上而下(默认焦点「取消」在上,破坏性动作在下;界面按这张表画)。 */
val RESTORE_CAPSULES: List<String> = listOf(SHELL_CANCEL, SHELL_CONFIRM)

/** 「默认桌面」页的胶囊(全部出现时)。第二颗是条件行,界面按 [showHomeKeyCapsule] 决定画不画;缺省焦点仍是第一颗。 */
val HOME_CAPSULES: List<String> = listOf(SHELL_CHANGE_HOME, SHELL_HOME_TAKEOVER)

/**
 * 关于页的第二颗胶囊「恢复默认」(R128:从「通用」组挪来,照 Google TV 把重置放在 系统 → 关于 里)。
 * id 沿用原来那一行的 id。按下去推外壳的「恢复默认」确认层([ShellPages.RESTORE])。
 */
const val ABOUT_RESTORE = "restoreDefaults"

/** R164:关于页的第二颗「更新通道 稳定版 ›」,按下去推外壳的通道页([ShellPages.CHANNEL])。 */
const val ABOUT_CHANNEL = "updateChannel"

/** 关于页的胶囊,自上而下:检查更新(id 沿用 [ShellPages.ABOUT])、更新通道、恢复默认(界面按这张表画)。 */
val ABOUT_CAPSULES: List<String> = listOf(ShellPages.ABOUT, ABOUT_CHANNEL, ABOUT_RESTORE)

/** 通道页的两颗胶囊 id(R164)。 */
const val CHANNEL_STABLE = "channel:stable"
const val CHANNEL_BETA = "channel:beta"
val CHANNEL_CAPSULES: List<String> = listOf(CHANNEL_STABLE, CHANNEL_BETA)

/** 通道对应的胶囊 id。通道页推栈时的缺省焦点 = 当前已保存通道那一颗(由 MainActivity 传,`defaultFocus` 不给 CHANNEL 缺省)。 */
fun channelCapsuleId(c: UpdateChannel): String = if (c == UpdateChannel.BETA) CHANNEL_BETA else CHANNEL_STABLE

/**
 * 关于页此刻画不画(R128)。关于页是叠在外壳第一层之上的整屏页;从它的「恢复默认」进确认层时,确认层是外壳栈上的一层,
 * 关于页让开(淡出、外壳不再 covered),确认层返回 / 确定弹栈后关于页重新出现、落回「恢复默认」。
R164 起通道页([ShellPages.CHANNEL])同理。
 * 只有「确认层 / 通道页在栈顶」这两种情况让开——[about] 为真时其余任何栈形状都照常画关于页(不会出现「开着却看不见」的黑洞)。
 */
fun aboutPageShown(about: Boolean, stack: List<ShellFrame>): Boolean =
    about && stack.lastOrNull()?.page.let { it != ShellPages.RESTORE && it != ShellPages.CHANNEL }

/** 第一层的一颗胶囊:标题 + 说明小字(Gordon 定案:第一层保留两行胶囊)。 */
data class RootEntry(val id: String, val labelRes: Int, val hintRes: Int)

/** 第一层 6 颗,顺序即显示顺序;缺省焦点在第一颗。2026-09-24 owner 定「通用」排第一(原「布局」第一)。 */
val SHELL_ROOT: List<RootEntry> = listOf(
    RootEntry(ShellPages.group(GroupId.GENERAL), R.string.settings_group_general, R.string.shell_root_general_desc),
    RootEntry(ShellPages.group(GroupId.LAYOUT), R.string.settings_group_layout, R.string.shell_root_layout_desc),
    RootEntry(ShellPages.group(GroupId.APPEARANCE), R.string.settings_group_appearance, R.string.shell_root_appearance_desc),
    RootEntry(ShellPages.group(GroupId.SCREENSAVER), R.string.settings_group_screensaver, R.string.shell_root_screensaver_desc),
    RootEntry(ShellPages.SYSTEM_SETTINGS, R.string.menu_system_settings, R.string.menu_system_settings_desc),
    RootEntry(ShellPages.ABOUT, R.string.menu_about, R.string.menu_about_desc),
)

/**
 * 选项层的显示顺序(下标 = [ControlRow] 的档位)。只有卡片大小倒过来排成 小 / 中 / 大(效果图 M3;
 * 存储顺序是 [VALID_CARDS_PER_ROW] 的 5 / 6 / 8 张 = 大 / 中 / 小,改存储会动 settings.json,不改)。
 */
fun optionOrder(row: ControlRow): List<Int> =
    if (row.id == "cardsPerRow") (row.count - 1 downTo 0).toList() else (0 until row.count).toList()

/** 一页的缺省焦点(第一次进这一页时落哪)。选项层落在**已保存**那一档(✓),所以一进去没有未保存的预览。 */
fun defaultFocus(page: String, groups: List<GroupSpec>): String? {
    ShellPages.groupOf(page)?.let { g -> return groups.firstOrNull { it.id == g }?.rows?.firstOrNull()?.id }
    ShellPages.subRow(page)?.let { r -> return subPageRow(groups, r)?.rows?.firstOrNull()?.id }
    ShellPages.optionsRow(page)?.let { r -> return controlRow(groups, r)?.let { optionId(it.selected) } }
    return when (page) {
        ShellPages.ROOT -> SHELL_ROOT.first().id
        ShellPages.HOME -> SHELL_CHANGE_HOME
        // 默认焦点在「取消」(spec §4 终审,防止误触恢复)
        ShellPages.RESTORE -> SHELL_CANCEL
        else -> null
    }
}

/** 按 id 找可改值的行(含子页里的行,R128)。 */
internal fun controlRow(groups: List<GroupSpec>, rowId: String): ControlRow? =
    allRows(groups).firstOrNull { it.id == rowId } as? ControlRow

/** 按 id 找子页入口(R128)。 */
internal fun subPageRow(groups: List<GroupSpec>, rowId: String): SubPageRow? =
    allRows(groups).firstOrNull { it.id == rowId } as? SubPageRow

/**
 * 一行的「上级」标题,自外而内:所属组的标题,在子页里的再加子页入口的标签(R128)。外壳拿它拼选项层 / 子页左上的路径
 * (「设置 · 通用 · 待机」)。找不到这一行 = 空表。
 */
fun rowParents(groups: List<GroupSpec>, rowId: String): List<Int> {
    for (g in groups) {
        for (r in g.rows) {
            if (r.id == rowId) return listOf(g.titleRes)
            if (r is SubPageRow && r.rows.withSubRows().any { it.id == rowId }) return listOf(g.titleRes, r.labelRes)
        }
    }
    return emptyList()
}

/** R128(Gordon 2026-09-29):设置页**每一页**右边的胶囊最多几颗。条件行按全部出现算。先合并再加。 */
const val MAX_CAPSULES_PER_PAGE = 6

/**
 * 一页右边胶囊列的 id 清单,自上而下(R128)。界面画的就是这些:分组页 / 子页 = 行 id,选项层 = [optionOrder] 的档位,
 * 第一层 / 默认桌面 / 恢复默认 / 关于 = 各自那张表([SHELL_ROOT] / [HOME_CAPSULES] / [RESTORE_CAPSULES] / [ABOUT_CAPSULES])。
 * null = 不是一页(系统设置那颗胶囊直接跳安卓设置)或认不出。
 */
fun pageCapsuleIds(page: String, groups: List<GroupSpec>): List<String>? {
    ShellPages.groupOf(page)?.let { g -> return groups.firstOrNull { it.id == g }?.rows?.map { it.id } }
    ShellPages.subRow(page)?.let { r -> return subPageRow(groups, r)?.rows?.map { it.id } }
    ShellPages.optionsRow(page)?.let { r -> return controlRow(groups, r)?.let { row -> optionOrder(row).map { optionId(it) } } }
    return when (page) {
        ShellPages.ROOT -> SHELL_ROOT.map { it.id }
        ShellPages.HOME -> HOME_CAPSULES
        ShellPages.RESTORE -> RESTORE_CAPSULES
        ShellPages.ABOUT -> ABOUT_CAPSULES
        ShellPages.CHANNEL -> CHANNEL_CAPSULES
        else -> null
    }
}

/**
 * 设置外壳能走到的**每一页**(R128 的单测据此逐页数胶囊):第一层、四组、每个子页、每个会打开选项层的行(滑块不开选项层)、
 * 默认桌面、恢复默认、关于。条件行出没出现取决于 [groups] 是按什么系统快照生成的——测试要喂一份让所有条件行都出现的快照。
 */
fun shellPages(groups: List<GroupSpec>): List<String> = buildList {
    add(ShellPages.ROOT)
    groups.forEach { add(ShellPages.group(it.id)) }
    allRows(groups).forEach { r ->
        when {
            r is SubPageRow -> add(ShellPages.sub(r.id))
            r is ControlRow && r.kind != CtrlKind.SLIDER -> add(ShellPages.options(r.id))
        }
    }
    add(ShellPages.HOME)
    add(ShellPages.RESTORE)
    add(ShellPages.CHANNEL)
    add(ShellPages.ABOUT)
}

/** 打开设置 = 一个只有第一层的栈,焦点在「布局」。 */
fun shellOpened(): List<ShellFrame> = listOf(ShellFrame(ShellPages.ROOT, SHELL_ROOT.first().id))

/**
 * 进下一层。父帧的焦点目标**不动**——它此刻就是被按下的那颗胶囊(目标跟着焦点走),返回时父层据此落焦。
 * [focus] = 新一层的初始焦点(调用方用 [defaultFocus] 算)。
 */
fun shellPush(stack: List<ShellFrame>, page: String, focus: String?): List<ShellFrame> = stack + ShellFrame(page, focus)

/** 回上一层;栈空 = 设置关了。**什么都不写**:选项层的未保存预览随栈顶一起消失(R71「返回 = 放弃」)。 */
fun shellPop(stack: List<ShellFrame>): List<ShellFrame> = stack.dropLast(1)

/** 用户在当前层导航到了 [id](目标跟着焦点走)。 */
fun shellSetFocus(stack: List<ShellFrame>, id: String): List<ShellFrame> =
    if (stack.isEmpty() || stack.last().focus == id) stack else stack.dropLast(1) + stack.last().copy(focus = id)

/**
 * 有实时预览的页(Gordon 定案第 6 条):「布局」「外观」两组,以及其下带预览的选项层。
 * 其余(第一层、通用、屏保、默认桌面、恢复默认、它们的选项层)左边只放标题。
 * 子页(R128「待机」,在通用组里)同样没有预览——待机那两行本来就没有(R75 删了待机演示)。
 */
fun pageHasPreview(page: String): Boolean {
    val g = ShellPages.groupOf(page)
    if (g != null) return g == GroupId.LAYOUT || g == GroupId.APPEARANCE
    val r = ShellPages.optionsRow(page) ?: return false
    return r in PREVIEW_ROW_IDS
}

/** 选项层里「光标停在哪档,首页就立刻变成那样」的行(布局、外观两组的选项行)。 */
val PREVIEW_ROW_IDS = setOf("cardsPerRow", "showTitles", "themeColor", "followWallpaper")

/**
 * **预览—保存—放弃状态机的「预览」一半**(R71):栈顶是带预览的选项层、光标停在第 i 档 →
 * 把那一档的写入函数套在已保存的设置上(不落盘);其它任何情况 → 原样返回 [saved]。
 * - 进选项层时光标 = 已保存档,结果等于 [saved](没有未保存的预览);
 * - 返回键 = [shellPop],栈顶不再是这一层,结果自然回到 [saved]——「返回 = 放弃」不需要任何撤销逻辑;
 * - 确定键 = `ControlRow.onSelect(i)` 落盘 + [shellPop],落盘后的 [saved] 与刚才看到的预览逐字段相同(同一个写入函数)。
 * MainActivity 用返回值喂首页、主题色与卡片淡化。
 */
fun effectiveSettings(saved: Settings, stack: List<ShellFrame>): Settings {
    val top = stack.lastOrNull() ?: return saved
    val row = ShellPages.optionsRow(top.page)?.takeIf { it in PREVIEW_ROW_IDS } ?: return saved
    val i = optionIndex(top.focus) ?: return saved
    val write = optionWrite(row) ?: return saved
    return runCatching { write(saved, i) }.getOrDefault(saved)
}

/** 滑块左右键:下一档,夹在 0..count−1(到头就不动)。 */
fun sliderStep(row: ControlRow, delta: Int): Int = (row.selected + delta).coerceIn(0, row.count - 1)

/** 滑块第 i 档代表的数值(百分比)。 */
fun sliderValue(row: ControlRow, i: Int = row.selected): Int = row.sliderMin + i * row.sliderStep

/** 滑块数值文字:双向滑块(下限 < 0,即壁纸亮度)正数带「+」;其余照写。与旧设置页的 `SliderControl` 同一规则。 */
fun sliderText(row: ControlRow, i: Int = row.selected): String {
    val v = sliderValue(row, i)
    return if (row.sliderMin < 0 && v > 0) "+$v%" else "$v%"
}

/**
 * 胶囊之间的纵向间距(dp):优先 [GtvLayout.MENU_ITEM_GAP](16),整列放不进 [maxColumn] 时等比缩小,
 * 不低于 [minGap]。整列高 = Σ 胶囊高 + (n−1) × 间距。R128 之前「通用」组最多 8 颗单行胶囊:8 × 55 + 7 × 16 = 552 > 540,
 * 缩成 8 dp(8 × 55 + 7 × 8 = 496);R128 起每页 ≤ [MAX_CAPSULES_PER_PAGE] 颗,单行胶囊 6 × 55 + 5 × 16 = 410 用不着缩,
 * 缩间距只剩给两行胶囊多的页(英文第一层说明折行)兜底。
 */
fun capsuleGap(heights: List<Float>, maxColumn: Float = SHELL_MAX_COLUMN_DP, preferred: Float = GtvLayout.MENU_ITEM_GAP, minGap: Float = 4f): Float {
    if (heights.size < 2) return preferred
    val total = heights.sum()
    val fit = (maxColumn - total) / (heights.size - 1)
    return fit.coerceAtMost(preferred).coerceAtLeast(minGap).let { kotlin.math.floor(it) }
}

/** 胶囊列最多占多高(dp):540 dp 屏高上下各留 20 dp。 */
const val SHELL_MAX_COLUMN_DP = 500f

/** 两行胶囊(第一层,带说明小字)的估计高度,只给 [capsuleGap] 用:10 + 16sp 行高 21 + 2 + 12sp 行高 16 + 10。 */
const val SHELL_TWO_LINE_PILL_DP = 59f

/** 预览框(dp,相对整屏左上角)。 */
data class PreviewRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * 预览框几何(效果图 README 第 2 条):宽 400 dp、左边距 40 dp、高按屏幕比例(1080p = 225 dp,正好 16:9);
 * 顶边 = 屏高 / 2 − 90(1080p = 180 dp):「路径 + 页名 + 预览 + 说明行」这一整块在左半屏里竖直居中。
 * **常量几何而不是量出来**:MainActivity 缩放首页那一层与外壳画描边读同一个函数,第一帧就对齐,
 * 不会像 `onGloballyPositioned` 那样先整屏画一帧再缩进去。
 */
fun previewRect(screenWidthDp: Float, screenHeightDp: Float): PreviewRect {
    val w = PREVIEW_WIDTH_DP
    val h = if (screenWidthDp > 0f) w * screenHeightDp / screenWidthDp else w * 9f / 16f
    return PreviewRect(PREVIEW_LEFT_DP, screenHeightDp / 2f - PREVIEW_CENTER_OFFSET_DP, w, h)
}

const val PREVIEW_WIDTH_DP = 400f
const val PREVIEW_LEFT_DP = 40f
/** 预览框顶边在屏幕中线之上多少 dp。见 [previewRect]。 */
const val PREVIEW_CENTER_OFFSET_DP = 90f

/**
 * 切语言 `recreate()` 时把整栈写进 Bundle 的一行字符串:`page@focus|page@focus…`(focus 为 null 时只写 page)。
 * 页 id 与胶囊 id 里只有字母、数字、`:`、`_`,不会撞上 `|` 与 `@`。
 */
fun encodeShellStack(stack: List<ShellFrame>): String =
    stack.joinToString("|") { f -> if (f.focus == null) f.page else "${f.page}@${f.focus}" }

/** [encodeShellStack] 的反函数。第一帧必须是第一层、每一页都认得,否则整栈作废(返回空 = 设置关着)。 */
fun decodeShellStack(text: String?): List<ShellFrame> {
    if (text.isNullOrBlank()) return emptyList()
    val frames = text.split("|").map { part ->
        val at = part.indexOf('@')
        if (at < 0) ShellFrame(part, null) else ShellFrame(part.substring(0, at), part.substring(at + 1).ifEmpty { null })
    }
    if (frames.first().page != ShellPages.ROOT) return emptyList()
    val known = frames.all { f ->
        f.page == ShellPages.ROOT || f.page == ShellPages.HOME || f.page == ShellPages.RESTORE || f.page == ShellPages.CHANNEL ||
            ShellPages.groupOf(f.page) != null ||
            ShellPages.subRow(f.page)?.let { it in SUB_PAGE_ROWS } == true ||
            ShellPages.optionsRow(f.page)?.let { it == "language" || optionWrite(it) != null } == true
    }
    return if (known) frames else emptyList()
}
