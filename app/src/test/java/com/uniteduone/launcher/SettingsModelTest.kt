package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [settingsGroups] 是设置页的**唯一真相**:分组顺序、每组有哪些行、每行当前选中第几档、
 * 选中之后写什么 —— 全在这一个纯函数里,所以全部可以在 JVM 上钉死,不必上模拟器。
 * (界面那半只负责画和焦点账本;它读这份模型,不自己另存一份下标。)
 */
class SettingsModelTest {

    /** 把 [SettingsActions] 的九条动作各记一笔,断言「按下去真的调到了那一条」。 */
    private class Recorder {
        val fired = mutableListOf<String>()
        val languages = mutableListOf<String>()
        val actions = SettingsActions(
            openEdit = { fired += "openEdit" },
            pickWallpaper = { fired += "pickWallpaper" },
            openImport = { fired += "openImport" },
            setDefaultHome = { fired += "setDefaultHome" },
            restoreDefaults = { fired += "restoreDefaults" },
            applyLanguage = { lang -> languages += lang },
            openScreensaverGallery = { fired += "openScreensaverGallery" },
            openSystemScreensaver = { fired += "openSystemScreensaver" },
            openSystemAnimationSettings = { fired += "openSystemAnimationSettings" },
        )
    }

    /** 图库张数:多数用例只关心「非空」。 */
    private val someImages = 3

    private fun rowsOf(groups: List<GroupSpec>) = groups.flatMap { it.rows }
    private fun row(groups: List<GroupSpec>, id: String) = rowsOf(groups).first { it.id == id }
    private fun ctrl(groups: List<GroupSpec>, id: String) = row(groups, id) as ControlRow

    @Test fun groupOrderFollowsSpec() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        // R69:四组顺序 = 外壳第一层的分组胶囊顺序,布局在最上。
        assertEquals(
            listOf(GroupId.LAYOUT, GroupId.GENERAL, GroupId.APPEARANCE, GroupId.SCREENSAVER),
            g.map { it.id },
        )
    }

    @Test fun rowCountsPerGroup() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        // 布局 1 动作(R69 编辑分栏)+ 2(R92 删掉「输入源行」开关)/ 通用 7(R60 手机传输挪进来)+ 动画缩放条件行(默认 UNKNOWN = 读不到 → 出「查看」)/
        // 外观 1 动作 + 2 壁纸滑块(自动切换 R61 删掉)+ 2 主题(主题化卡片 R58 删掉)+ 2 卡片淡化滑块(R70)+ 卡片不透明度(R86)/ 屏保 2 控件 + 2 动作
        assertEquals(listOf(3, 8, 8, 4), g.map { it.rows.size })
    }

    @Test fun rowIdsAreUnique() {
        val ids = rowsOf(settingsGroups(Settings(), {}, Recorder().actions, someImages)).map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    /** 胶囊列一屏放得下的不变量(≤ 8 颗、永不滚动;8 颗时间距缩到 8 dp,见 capsuleGap)。 */
    @Test fun noGroupExceedsEightRows() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertTrue(g.all { it.rows.size <= 8 })
    }

    @Test fun appearanceGroupStartsWithOneActionRow() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        val appearance = g.first { it.id == GroupId.APPEARANCE }.rows
        assertTrue(appearance[0] is ActionRow)
        assertTrue(appearance.drop(1).all { it is ControlRow })
    }

    /** R57:通用组的行序——语言、默认桌面、手机传输(R60)、待机两行、时钟显示紧跟待机显示、恢复默认收尾。 */
    @Test fun generalGroupRowOrder() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages, system = allNormal)
        assertEquals(
            listOf("language", "setDefaultHome", "openImport", "idleAfter", "idleContent", "clockDisplay", "restoreDefaults"),
            g.first { it.id == GroupId.GENERAL }.rows.map { it.id },
        )
    }

    /** R60:「导入图片」改名「手机传输」、挪到通用组,打开的仍是扫码页。 */
    @Test fun phoneTransferRowInGeneral() {
        val r = Recorder()
        val g = settingsGroups(Settings(), {}, r.actions, someImages)
        val row = g.first { it.id == GroupId.GENERAL }.rows.first { it.id == "openImport" } as ActionRow
        assertEquals(R.string.settings_phone_transfer, row.labelRes)
        assertEquals(R.string.settings_phone_transfer_desc, row.hintRes)
        row.onActivate()
        assertEquals(listOf("openImport"), r.fired)
    }

    /** R57:外观组 = 原壁纸组 + 原主题组。 */
    @Test fun appearanceGroupRowOrder() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertEquals(
            listOf(
                "pickWallpaper", "wallpaperBlur", "wallpaperBrightness",
                "themeColor", "followWallpaper", "cardSaturation", "cardBrightness", "cardOpacity",
            ),
            g.first { it.id == GroupId.APPEARANCE }.rows.map { it.id },
        )
    }

    /** R57:「时钟显示」二选一映射 showDate(0 = 仅时间,1 = 时间与日期),存盘键不变。 */
    @Test fun clockDisplayMapsShowDate() {
        var written: Settings? = null
        val base = Settings(showDate = true)
        val g = settingsGroups(base, { t -> written = t(base) }, Recorder().actions, someImages)
        val row = ctrl(g, "clockDisplay")
        assertEquals(CtrlKind.SEGMENTED, row.kind)
        assertEquals(listOf(R.string.settings_clock_time_only, R.string.settings_clock_time_date), row.optionRes)
        assertEquals(1, row.selected)
        assertEquals(0, ctrl(settingsGroups(Settings(showDate = false), {}, Recorder().actions, someImages), "clockDisplay").selected)
        row.onSelect(0)
        assertEquals(false, written?.showDate)
        row.onSelect(1)
        assertEquals(true, written?.showDate)
    }

    @Test fun selectedMirrorsSettings() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        // 默认 cardsPerRow = 6 → VALID_CARDS_PER_ROW(5,6,8) 的第 1 档「中」
        assertEquals(1, ctrl(g, "cardsPerRow").selected)
        // 默认亮度 0 → 双向滑块正中(zeroAt = 5)
        assertEquals(5, ctrl(g, "wallpaperBrightness").selected)
        assertEquals(5, ctrl(g, "wallpaperBrightness").zeroAt)
        // 默认语言 system → 第 0 档
        assertEquals(0, ctrl(g, "language").selected)

        val other = settingsGroups(
            Settings(cardsPerRow = 8, wallpaperBrightness = -20, language = "zh-TW"),
            {}, Recorder().actions, someImages,
        )
        assertEquals(2, ctrl(other, "cardsPerRow").selected)
        assertEquals(3, ctrl(other, "wallpaperBrightness").selected)   // (−20 + 50) / 10
        assertEquals(2, ctrl(other, "language").selected)              // VALID_LANGUAGES 的第 2 档
    }

    @Test fun controlSelectWritesThroughUpdate() {
        var written: Settings? = null
        val base = Settings()
        val g = settingsGroups(base, { transform -> written = transform(base) }, Recorder().actions, someImages)
        ctrl(g, "cardsPerRow").onSelect(2)
        assertEquals(8, written?.cardsPerRow)
        ctrl(g, "wallpaperBrightness").onSelect(8)
        assertEquals(30, written?.wallpaperBrightness)
        ctrl(g, "idleContent").onSelect(1)
        assertEquals(IdleContent.BLACK, written?.idleContent)
    }

    /**
     * 语言行**不自己写盘**:它把档位翻译成 [VALID_LANGUAGES] 里的取值交给 [SettingsActions.applyLanguage]——
     * 那条动作在 T8 才接上 `recreate()`,本任务只写字段。行自己不认识 Activity,这条边界必须钉住。
     */
    @Test fun languageRowGoesThroughApplyLanguage() {
        val r = Recorder()
        var written: Settings? = null
        val g = settingsGroups(Settings(), { transform -> written = transform(Settings()) }, r.actions, someImages)
        ctrl(g, "language").onSelect(3)
        assertEquals(listOf("en"), r.languages)
        assertEquals(null, written)
    }

    @Test fun actionRowsFireTheirAction() {
        val r = Recorder()
        val g = settingsGroups(Settings(), {}, r.actions, someImages)
        (row(g, "editLayout") as ActionRow).onActivate()
        (row(g, "pickWallpaper") as ActionRow).onActivate()
        (row(g, "openImport") as ActionRow).onActivate()
        (row(g, "setDefaultHome") as ActionRow).onActivate()
        (row(g, "restoreDefaults") as ActionRow).onActivate()
        (row(g, "screensaverGallery") as ActionRow).onActivate()
        (row(g, "systemScreensaver") as ActionRow).onActivate()
        assertEquals(
            listOf(
                "openEdit", "pickWallpaper", "openImport", "setDefaultHome", "restoreDefaults",
                "openScreensaverGallery", "openSystemScreensaver",
            ),
            r.fired,
        )
    }

    /** 分段/开关的档数必须与它的显示文案条数一致,否则界面会画出一个点不到的档。 */
    @Test fun segmentedOptionsMatchCount() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        rowsOf(g).filterIsInstance<ControlRow>()
            .filter { it.kind == CtrlKind.SEGMENTED || it.kind == CtrlKind.TOGGLE }
            .forEach { assertEquals(it.id, it.count, it.optionRes.size) }
    }

    // ---- M5 屏保各行(spec §3;R57 起待机两行在「通用」组,屏保独立成「屏保」组)----

    @Test fun screensaverGroupRowOrder() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        val ss = g.first { it.id == GroupId.SCREENSAVER }.rows
        assertEquals(
            listOf("screensaverAfter", "screensaverInterval", "screensaverGallery", "systemScreensaver"),
            ss.map { it.id },
        )
        assertTrue(ss.take(2).all { it is ControlRow })
        assertTrue(ss.drop(2).all { it is ActionRow })
    }

    @Test fun screensaverRowsMirrorSettings() {
        val d = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertEquals(2, ctrl(d, "screensaverAfter").selected)      // 默认 5 分 = (关,1,5,10,30) 的第 2 档
        assertEquals(0, ctrl(d, "screensaverInterval").selected)   // 默认 30 秒 = (30 秒,1 分,5 分) 的第 0 档
        val o = settingsGroups(
            Settings(screensaverAfterMs = 0L, screensaverIntervalMs = 300_000L), {}, Recorder().actions, someImages,
        )
        assertEquals(0, ctrl(o, "screensaverAfter").selected)
        assertEquals(2, ctrl(o, "screensaverInterval").selected)
    }

    @Test fun screensaverRowsWriteThroughUpdate() {
        var written: Settings? = null
        val base = Settings()
        val g = settingsGroups(base, { transform -> written = transform(base) }, Recorder().actions, someImages)
        ctrl(g, "screensaverAfter").onSelect(4)
        assertEquals(1_800_000L, written?.screensaverAfterMs)
        ctrl(g, "screensaverInterval").onSelect(1)
        assertEquals(60_000L, written?.screensaverIntervalMs)
    }

    @Test fun screensaverOptionLabels() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        val after = ctrl(g, "screensaverAfter")
        assertEquals(R.string.settings_idle_off, after.optionRes[0])   // 「关」复用待机时长那一行的
        assertEquals(listOf(null, 1, 5, 10, 30), after.optionArgs)
        val interval = ctrl(g, "screensaverInterval")
        assertEquals(
            listOf(R.string.settings_seconds, R.string.settings_idle_minutes, R.string.settings_idle_minutes),
            interval.optionRes,
        )
        assertEquals(listOf(30, 1, 5), interval.optionArgs)
    }

    @Test fun screensaverAfterNoteCoversEveryCase() {
        assertEquals(R.string.settings_screensaver_note_empty, screensaverAfterNoteRes(180_000L, 300_000L, 0))
        assertEquals(R.string.settings_screensaver_note_from_input, screensaverAfterNoteRes(0L, 300_000L, 3))
        assertEquals(R.string.settings_screensaver_note_after_standby, screensaverAfterNoteRes(180_000L, 300_000L, 3))
        // 还没数完(−1)不当成空
        assertEquals(R.string.settings_screensaver_note_after_standby, screensaverAfterNoteRes(180_000L, 300_000L, -1))
        // 屏保启动本身是「关」:计时起点与图库都跟它无关了,不画提示
        assertNull(screensaverAfterNoteRes(180_000L, 0L, 0))
    }

    @Test fun onlyScreensaverAfterCarriesANote() {
        val empty = settingsGroups(Settings(), {}, Recorder().actions, 0)
        assertEquals(R.string.settings_screensaver_note_empty, ctrl(empty, "screensaverAfter").noteRes)
        val fromInput = settingsGroups(Settings(idleAfterMs = 0L), {}, Recorder().actions, someImages)
        assertEquals(R.string.settings_screensaver_note_from_input, ctrl(fromInput, "screensaverAfter").noteRes)
        assertTrue(
            rowsOf(empty).filterIsInstance<ControlRow>()
                .filter { it.id != "screensaverAfter" }
                .all { it.noteRes == null },
        )
    }

    // ---- R92:布局组没有「输入源行」开关与「恢复隐藏的输入源」了 ----

    @Test fun layoutGroupHasNoInputRows() {
        val ids = settingsGroups(Settings(), {}, Recorder().actions, someImages)
            .first { it.id == GroupId.LAYOUT }.rows.map { it.id }
        assertEquals(listOf("editLayout", "cardsPerRow", "showTitles"), ids)
        assertEquals(null, optionWrite("showInputRow"))
    }

    // ---- ui-pending #16:系统屏保摘要(R56)与动画缩放提示行(R57 在通用组)----

    private fun rowsWith(sys: SystemUiStatus, r: Recorder = Recorder()) =
        settingsGroups(Settings(), {}, r.actions, someImages, system = sys)

    private val allNormal = SystemUiStatus(
        screensaverEnabled = true,
        screensaverSource = DreamSource.Ours,
        screensaverStart = TimeoutDisplay.Minutes(5),
        animatorScale = 1f, transitionScale = 1f, windowScale = 1f,
    )

    /** 「系统屏保 ▸」一行带摘要;不再有 R55 的来源 / 启动时间两行。 */
    @Test fun systemScreensaverIsOneRowWithSummary() {
        val g = rowsWith(allNormal)
        val row = row(g, "systemScreensaver") as ActionRow
        assertNull(row.hintRes)
        assertEquals(screensaverSummary(allNormal), row.hintParts)
        val ids = rowsOf(g).map { it.id }
        assertTrue(ids.none { it == "systemScreensaverSource" || it == "systemScreensaverStart" })
    }

    @Test fun animRowOnlyWhenScaleIsNotOne() {
        val general = { sys: SystemUiStatus -> rowsWith(sys).first { it.id == GroupId.GENERAL }.rows.map { it.id } }
        assertTrue("systemAnimationScale" !in general(allNormal))
        // 出现时排在「恢复默认」之前(R57)
        val with = general(allNormal.copy(animatorScale = 1.25f))
        assertEquals(listOf("systemAnimationScale", "restoreDefaults"), with.takeLast(2))
    }

    @Test fun animRowCarriesFormattedScale() {
        fun anim(sys: SystemUiStatus) = row(rowsWith(sys), "systemAnimationScale") as ActionRow
        val slow = anim(allNormal.copy(animatorScale = 1.25f))
        assertEquals(R.string.settings_sys_anim_slower, slow.hintRes)
        assertEquals(listOf<Any>("1.25"), slow.hintArgs)
        assertEquals(R.string.settings_sys_anim_faster, anim(allNormal.copy(animatorScale = 0.5f)).hintRes)
        assertEquals(R.string.settings_sys_anim_off, anim(allNormal.copy(animatorScale = 0f)).hintRes)
        val win = anim(allNormal.copy(windowScale = 0.5f))
        assertEquals(R.string.settings_sys_anim_window, win.hintRes)
        assertEquals(listOf<Any>("0.5", "1"), win.hintArgs)
        // 动画程序那一项读不到:出「查看」,不猜是不是 1×
        assertEquals(R.string.settings_sys_view, anim(SystemUiStatus.UNKNOWN).hintRes)
    }

    @Test fun systemRowsJumpToTheirPages() {
        val r = Recorder()
        val g = rowsWith(allNormal.copy(animatorScale = 1.25f), r)
        (row(g, "systemScreensaver") as ActionRow).onActivate()
        (row(g, "systemAnimationScale") as ActionRow).onActivate()
        assertEquals(listOf("openSystemScreensaver", "openSystemAnimationSettings"), r.fired)
    }

    // ---- R69 / R70 / R71:设置页外壳用到的模型部分 ----

    /** R69:「编辑分栏」是布局组第一行,打开现有编辑页。 */
    @Test fun editLayoutIsFirstLayoutRow() {
        val r = Recorder()
        val g = settingsGroups(Settings(), {}, r.actions, someImages)
        val first = g.first { it.id == GroupId.LAYOUT }.rows.first() as ActionRow
        assertEquals("editLayout", first.id)
        assertEquals(R.string.menu_edit, first.labelRes)
        first.onActivate()
        assertEquals(listOf("openEdit"), r.fired)
    }

    /** R70:两条卡片淡化滑块——饱和度 0–100 步 10、亮度 50–100 步 5,都是 11 档;缺省 30 / 75。 */
    @Test fun cardFadeSlidersMirrorAndWrite() {
        var written: Settings? = null
        val base = Settings()
        val g = settingsGroups(base, { t -> written = t(base) }, Recorder().actions, someImages)
        val sat = ctrl(g, "cardSaturation")
        val bri = ctrl(g, "cardBrightness")
        assertEquals(CtrlKind.SLIDER, sat.kind)
        assertEquals(CtrlKind.SLIDER, bri.kind)
        assertEquals(11, sat.count)
        assertEquals(11, bri.count)
        assertEquals(3, sat.selected)          // 30%
        assertEquals(5, bri.selected)          // (75 − 50) / 5
        sat.onSelect(10)
        assertEquals(100, written?.cardSaturation)
        bri.onSelect(0)
        assertEquals(50, written?.cardBrightness)
        bri.onSelect(10)
        assertEquals(100, written?.cardBrightness)
    }

    /**
     * R71:每个可改值行的 onSelect 就是 `update { write(it, i) }`——确定键落盘的值与光标停留时的预览逐字段相同。
     * 语言行例外(write = null,走 applyLanguage)。
     */
    @Test fun onSelectAndWriteAgreeForEveryRow() {
        val base = Settings()
        rowsOf(settingsGroups(base, {}, Recorder().actions, someImages)).filterIsInstance<ControlRow>().forEach { row ->
            if (row.id == "language") { assertNull(row.write); return@forEach }
            for (i in 0 until row.count) {
                var written: Settings? = null
                val g = settingsGroups(base, { t -> written = t(base) }, Recorder().actions, someImages)
                ctrl(g, row.id).onSelect(i)
                assertEquals("${row.id}#$i", row.write!!(base, i), written)
            }
        }
    }

    /** 选项文案条数与档数一致(外壳选项层每档一颗胶囊);主题色的文案是预设名。 */
    @Test fun optionLabelsCoverEveryChoice() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        val theme = ctrl(g, "themeColor")
        assertEquals(ThemePresets.all.map { it.nameRes }, theme.optionRes)
        rowsOf(g).filterIsInstance<ControlRow>().filter { it.kind != CtrlKind.SLIDER }
            .forEach { assertEquals(it.id, it.count, it.optionRes.size) }
    }
}
