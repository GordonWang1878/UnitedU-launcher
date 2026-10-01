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
            applyLanguage = { lang -> languages += lang },
            openScreensaverGallery = { fired += "openScreensaverGallery" },
            openSystemScreensaver = { fired += "openSystemScreensaver" },
            openSystemScreenOff = { fired += "openSystemScreenOff" },
            startScreensaver = { fired += "startScreensaver" },
            openSystemAnimationSettings = { fired += "openSystemAnimationSettings" },
        )
    }

    /** 图库张数:多数用例只关心「非空」。 */
    private val someImages = 3

    /** 所有行,含子页(R128「待机」)里的行——按 id 找行的用例都走它。 */
    private fun rowsOf(groups: List<GroupSpec>) = allRows(groups)
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
        // 布局 1 动作(R69 编辑分栏)+ 2(R92 删掉「输入源行」开关)+ 3 卡片色彩滑块(R120 从外观挪来)/
        // 通用 5(R128:待机两行合成一颗子页入口、恢复默认挪进关于页)+ 动画缩放条件行(默认 UNKNOWN = 读不到 → 出「查看」)/
        // 外观 1 动作 + 2 壁纸滑块(自动切换 R61 删掉)+ 2 主题(主题化卡片 R58 删掉;卡片淡化 R70 / 不透明度 R86 三条 R120 挪走)/ 屏保 1 动作(R93 立即开始屏保)+ 2 控件 + 3 动作(R127 关闭屏幕)
        assertEquals(listOf(6, 6, 5, 6), g.map { it.rows.size })
    }

    @Test fun rowIdsAreUnique() {
        val ids = rowsOf(settingsGroups(Settings(), {}, Recorder().actions, someImages)).map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    /** 胶囊列一屏放得下、永不滚动;R128 起每页 ≤ 6(逐页的完整检查见 SettingsPageLimitTest)。 */
    @Test fun noGroupExceedsSixRows() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertTrue(g.all { it.rows.size <= MAX_CAPSULES_PER_PAGE })
    }

    @Test fun appearanceGroupStartsWithOneActionRow() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        val appearance = g.first { it.id == GroupId.APPEARANCE }.rows
        assertTrue(appearance[0] is ActionRow)
        assertTrue(appearance.drop(1).all { it is ControlRow })
    }

    /**
     * R57:通用组的行序——语言、默认桌面、手机传输(R60)、待机、时钟显示紧跟待机。
     * R128:待机两行合成一颗「待机」子页入口(在原「待机时长」的位置),「恢复默认」挪进关于页。
     */
    @Test fun generalGroupRowOrder() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages, system = allNormal)
        assertEquals(
            listOf("language", "setDefaultHome", "openImport", "standby", "clockDisplay"),
            g.first { it.id == GroupId.GENERAL }.rows.map { it.id },
        )
    }

    /** R128:「待机」子页里就是原来那两行,顺序、选项、写入逐字未动;组里不再有这两行本身。 */
    @Test fun standbySubPageHoldsTheTwoIdleRows() {
        var written: Settings? = null
        val base = Settings()
        val g = settingsGroups(base, { t -> written = t(base) }, Recorder().actions, someImages, system = allNormal)
        val general = g.first { it.id == GroupId.GENERAL }.rows
        val standby = general.first { it.id == STANDBY_ROW } as SubPageRow
        assertEquals(R.string.settings_standby, standby.labelRes)
        assertEquals(listOf("idleAfter", "idleContent"), standby.rows.map { it.id })
        assertTrue(general.none { it.id == "idleAfter" || it.id == "idleContent" })
        val after = standby.rows[0] as ControlRow
        assertEquals(R.string.settings_idle_after, after.labelRes)
        assertEquals(listOf(null, 1, 3, 5, 10), after.optionArgs)
        assertEquals(2, after.selected)                         // 缺省 3 分
        val content = standby.rows[1] as ControlRow
        assertEquals(R.string.settings_idle_content, content.labelRes)
        assertEquals(
            listOf(R.string.settings_idle_clock, R.string.settings_idle_black, R.string.settings_idle_nofade),
            content.optionRes,
        )
        assertEquals(0, content.selected)                       // 缺省时钟
        after.onSelect(4)
        assertEquals(600_000L, written?.idleAfterMs)
        content.onSelect(2)
        assertEquals(IdleContent.NO_FADE, written?.idleContent)
    }

    /** R60:「导入图片」改名「手机传输」、挪到通用组,打开的仍是扫码页。 */
    @Test fun phoneTransferRowInGeneral() {
        val r = Recorder()
        val g = settingsGroups(Settings(), {}, r.actions, someImages)
        val row = g.first { it.id == GroupId.GENERAL }.rows.first { it.id == "openImport" } as ActionRow
        assertEquals(R.string.settings_phone_transfer, row.labelRes)
        // R131:说明不再塞进 hintRes(胶囊里本来就不画),改由外壳左侧按 descRes 显示
        assertEquals(R.string.settings_phone_transfer_desc, row.descRes)
        assertEquals(null, row.hintRes)
        row.onActivate()
        assertEquals(listOf("openImport"), r.fired)
    }

    /** R57:外观组 = 原壁纸组 + 原主题组。R120:卡片的三条色彩滑块挪去布局组,外观只剩壁纸与主题色。 */
    @Test fun appearanceGroupRowOrder() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertEquals(
            listOf("pickWallpaper", "wallpaperBlur", "wallpaperBrightness", "themeColor", "followWallpaper"),
            g.first { it.id == GroupId.APPEARANCE }.rows.map { it.id },
        )
    }

    /** R120(2026-09-28 Gordon):卡片饱和度 / 亮度 / 透明度跟在「卡片标题」之后,顺序不变,仍是滑块,写的字段不变。 */
    @Test fun cardColorSlidersLiveInLayoutAfterTitles() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        val layout = g.first { it.id == GroupId.LAYOUT }.rows
        assertEquals(
            listOf("editLayout", "cardsPerRow", "showTitles", "cardSaturation", "cardBrightness", "cardOpacity"),
            layout.map { it.id },
        )
        assertTrue(layout.drop(3).all { it is ControlRow && it.kind == CtrlKind.SLIDER })
        // 别的组里不再有这三行(行 id 全局唯一由 rowIdsAreUnique 钉住,这里再从外观那头确认一次)
        val appearance = g.first { it.id == GroupId.APPEARANCE }.rows.map { it.id }
        assertTrue(appearance.none { it in setOf("cardSaturation", "cardBrightness", "cardOpacity") })
    }

    /** R57:「时钟显示」二选一映射 showDate(0 = 仅时间,1 = 时间与日期),存盘键不变。 */
    @Test fun clockDisplayMapsShowDate() {
        var written: Settings? = null
        val base = Settings(showDate = true)
        val g = settingsGroups(base, { t -> written = t(base) }, Recorder().actions, someImages)
        val row = ctrl(g, "clockDisplay")
        assertEquals(CtrlKind.SEGMENTED, row.kind)
        // R149:三档
        assertEquals(
            listOf(R.string.settings_clock_time_only, R.string.settings_clock_time_date, R.string.settings_clock_time_date_weekday),
            row.optionRes,
        )
        assertEquals(1, row.selected)
        assertEquals(0, ctrl(settingsGroups(Settings(showDate = false), {}, Recorder().actions, someImages), "clockDisplay").selected)
        assertEquals(2, ctrl(settingsGroups(Settings(showDate = true, showWeekday = true), {}, Recorder().actions, someImages), "clockDisplay").selected)
        row.onSelect(0)
        assertEquals(false, written?.showDate)
        row.onSelect(1)
        assertEquals(true, written?.showDate)
        assertEquals(false, written?.showWeekday)
        row.onSelect(2)
        assertEquals(true, written?.showDate)
        assertEquals(true, written?.showWeekday)
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
        (row(g, "screensaverGallery") as ActionRow).onActivate()
        (row(g, "systemScreensaver") as ActionRow).onActivate()
        (row(g, "screenOff") as ActionRow).onActivate()
        (row(g, "startScreensaver") as ActionRow).onActivate()
        assertEquals(
            listOf(
                "openEdit", "pickWallpaper", "openImport", "setDefaultHome",
                "openScreensaverGallery", "openSystemScreensaver", "openSystemScreenOff", "startScreensaver",
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
            // R93:「立即开始屏保」(原顶栏屏保按钮)放最上面;R127:「关闭屏幕」紧跟「系统屏保」;R158:图库在「自动切换间隔」上方
            listOf("startScreensaver", "screensaverAfter", "screensaverGallery", "screensaverInterval", "systemScreensaver", "screenOff"),
            ss.map { it.id },
        )
        // 动作行 / 选项行交错:立即开始、启动时间(选项)、图库、切换间隔(选项)、系统屏保、自动关屏
        assertEquals(listOf(true, false, true, false, true, true), ss.map { it is ActionRow })
        assertTrue(ss.filterNot { it is ActionRow }.all { it is ControlRow })
    }

    @Test fun screensaverRowsMirrorSettings() {
        val d = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertEquals(2, ctrl(d, "screensaverAfter").selected)      // 默认 5 分 = (关,1,5,10,30) 的第 2 档
        assertEquals(1, ctrl(d, "screensaverInterval").selected)   // 默认 1 分(R152)= (30 秒,1 分,5 分) 的第 1 档
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
        assertEquals(listOf("editLayout", "cardsPerRow", "showTitles", "cardSaturation", "cardBrightness", "cardOpacity"), ids)   // R120 末尾三条卡片滑块
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
        // 出现时在组末(R57 时在「恢复默认」之前;R128 恢复默认挪进关于页)
        val with = general(allNormal.copy(animatorScale = 1.25f))
        assertEquals(listOf("clockDisplay", "systemAnimationScale"), with.takeLast(2))
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
        (row(g, "screenOff") as ActionRow).onActivate()
        (row(g, "systemAnimationScale") as ActionRow).onActivate()
        assertEquals(listOf("openSystemScreensaver", "openSystemScreenOff", "openSystemAnimationSettings"), r.fired)
    }

    /**
     * R127:「关闭屏幕」常驻在「系统屏保」正下方;值来自 sleep_timeout 的快照(一段),小字写去哪改;
     * 快照读不到(UNKNOWN)时值是「查看」、行照样在(不是条件行)。
     */
    @Test fun screenOffRowCarriesSleepTimeoutAndWhereToChange() {
        val g = rowsWith(allNormal.copy(screenOff = TimeoutDisplay.Hours(24)))
        val ss = g.first { it.id == GroupId.SCREENSAVER }.rows.map { it.id }
        assertEquals(listOf("systemScreensaver", "screenOff"), ss.takeLast(2))
        val row = row(g, "screenOff") as ActionRow
        assertEquals(R.string.settings_screen_off, row.labelRes)
        assertNull(row.hintRes)
        assertEquals(listOf(HintPart.Res(R.string.settings_sys_idle_hours, listOf(24))), row.hintParts)
        assertEquals(R.string.settings_screen_off_where_generic, row.noteRes)
        val sony = row(rowsWith(allNormal.copy(screenOffPath = listOf("系统", "电源和能耗", "自动关闭"))), "screenOff") as ActionRow
        assertEquals(R.string.settings_screen_off_where, sony.noteRes)
        assertEquals(listOf<Any>("系统 → 电源和能耗 → 自动关闭"), sony.noteArgs)
        val never = row(rowsWith(allNormal.copy(screenOff = TimeoutDisplay.Never)), "screenOff") as ActionRow
        assertEquals(listOf(HintPart.Res(R.string.settings_sys_never)), never.hintParts)
        val unknown = row(rowsWith(SystemUiStatus.UNKNOWN), "screenOff") as ActionRow
        assertEquals(listOf(HintPart.Res(R.string.settings_sys_view)), unknown.hintParts)
        // 其余动作行都不带小字
        assertTrue(rowsOf(g).filterIsInstance<ActionRow>().filter { it.id != "screenOff" }.all { it.noteRes == null })
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
        assertEquals(9, sat.selected)          // 90%(R152 起的缺省)
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
