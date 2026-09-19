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

    /** 把 [SettingsActions] 的八条动作各记一笔,断言「按下去真的调到了那一条」。 */
    private class Recorder {
        val fired = mutableListOf<String>()
        val languages = mutableListOf<String>()
        val actions = SettingsActions(
            pickWallpaper = { fired += "pickWallpaper" },
            openImport = { fired += "openImport" },
            setDefaultHome = { fired += "setDefaultHome" },
            restoreDefaults = { fired += "restoreDefaults" },
            applyLanguage = { lang -> languages += lang },
            openScreensaverGallery = { fired += "openScreensaverGallery" },
            openSystemScreensaver = { fired += "openSystemScreensaver" },
            restoreHiddenInputs = { fired += "restoreHiddenInputs" },
        )
    }

    /** 图库张数:多数用例只关心「非空」。 */
    private val someImages = 3

    private fun rowsOf(groups: List<GroupSpec>) = groups.flatMap { it.rows }
    private fun row(groups: List<GroupSpec>, id: String) = rowsOf(groups).first { it.id == id }
    private fun ctrl(groups: List<GroupSpec>, id: String) = row(groups, id) as ControlRow

    @Test fun groupOrderFollowsSpec() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertEquals(
            listOf(
                GroupId.LAYOUT, GroupId.WALLPAPER, GroupId.THEME,
                GroupId.STANDBY, GroupId.CLOCK, GroupId.LANGUAGE, GroupId.OTHER,
            ),
            g.map { it.id },
        )
    }

    @Test fun rowCountsPerGroup() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        // 布局 3 / 壁纸 2 动作 + 3 控件 / 主题 3 / 待机与屏保 4 控件 + 2 动作(M5)/ 时钟 1 / 语言 1 / 其他 2 动作
        assertEquals(listOf(3, 5, 3, 6, 1, 1, 2), g.map { it.rows.size })
    }

    @Test fun rowIdsAreUnique() {
        val ids = rowsOf(settingsGroups(Settings(), {}, Recorder().actions, someImages)).map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    /** 右栏一屏放得下的不变量(spec §2.1:≤ 8 行、永不滚动)。 */
    @Test fun noGroupExceedsEightRows() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertTrue(g.all { it.rows.size <= 8 })
    }

    @Test fun wallpaperGroupStartsWithTwoActionRows() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        val wallpaper = g.first { it.id == GroupId.WALLPAPER }.rows
        assertTrue(wallpaper[0] is ActionRow)
        assertTrue(wallpaper[1] is ActionRow)
        assertTrue(wallpaper.drop(2).all { it is ControlRow })
    }

    @Test fun otherGroupIsTwoActionRows() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertTrue(g.first { it.id == GroupId.OTHER }.rows.all { it is ActionRow })
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
        (row(g, "pickWallpaper") as ActionRow).onActivate()
        (row(g, "openImport") as ActionRow).onActivate()
        (row(g, "setDefaultHome") as ActionRow).onActivate()
        (row(g, "restoreDefaults") as ActionRow).onActivate()
        (row(g, "screensaverGallery") as ActionRow).onActivate()
        (row(g, "systemScreensaver") as ActionRow).onActivate()
        assertEquals(
            listOf(
                "pickWallpaper", "openImport", "setDefaultHome", "restoreDefaults",
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

    // ---- M5「待机与屏保」组(spec §3)----

    @Test fun standbyGroupHasSixRowsInSpecOrder() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        val standby = g.first { it.id == GroupId.STANDBY }.rows
        assertEquals(
            listOf(
                "idleAfter", "idleContent", "screensaverAfter",
                "screensaverInterval", "screensaverGallery", "systemScreensaver",
            ),
            standby.map { it.id },
        )
        assertTrue(standby.take(4).all { it is ControlRow })
        assertTrue(standby.drop(4).all { it is ActionRow })
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

    // ---- M4b「恢复隐藏的输入源」(布局组第四行,只在有隐藏项时出现)----

    /** hiddenInputs 有默认值 0,不传时与「今天」(没有这一行)完全一致——这条顺带钉住那个默认值。 */
    @Test fun hiddenInputsZeroKeepsLayoutGroupUnchanged() {
        fun layoutRowIds(hiddenInputs: Int?) = (
            if (hiddenInputs == null) {
                settingsGroups(Settings(), {}, Recorder().actions, someImages)
            } else {
                settingsGroups(Settings(), {}, Recorder().actions, someImages, hiddenInputs = hiddenInputs)
            }
            ).first { it.id == GroupId.LAYOUT }.rows.map { it.id }
        val expected = listOf("cardsPerRow", "showTitles", "showInputRow")
        assertEquals(expected, layoutRowIds(0))
        // 不传第五个参数(19 处既有调用全是这样)必须等价于显式传 0。
        assertEquals(expected, layoutRowIds(null))
    }

    @Test fun hiddenInputsPositiveAddsRestoreRowAfterShowInputRow() {
        val r = Recorder()
        val g = settingsGroups(Settings(), {}, r.actions, someImages, hiddenInputs = 2)
        val layout = g.first { it.id == GroupId.LAYOUT }.rows
        assertEquals(
            listOf("cardsPerRow", "showTitles", "showInputRow", "restoreHiddenInputs"),
            layout.map { it.id },
        )
        val row = layout.last() as ActionRow
        assertEquals(R.string.settings_restore_hidden_inputs, row.labelRes)
        assertEquals(R.string.settings_hidden_inputs_count, row.hintRes)
        assertEquals(2, row.hintArg)
        row.onActivate()
        assertEquals(listOf("restoreHiddenInputs"), r.fired)
    }
}
