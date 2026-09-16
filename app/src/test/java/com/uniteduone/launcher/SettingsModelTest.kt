package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [settingsGroups] 是设置页的**唯一真相**:分组顺序、每组有哪些行、每行当前选中第几档、
 * 选中之后写什么 —— 全在这一个纯函数里,所以全部可以在 JVM 上钉死,不必上模拟器。
 * (界面那半只负责画和焦点账本;它读这份模型,不自己另存一份下标。)
 */
class SettingsModelTest {

    /** 把 [SettingsActions] 的五条动作各记一笔,断言「按下去真的调到了那一条」。 */
    private class Recorder {
        val fired = mutableListOf<String>()
        val languages = mutableListOf<String>()
        val actions = SettingsActions(
            pickWallpaper = { fired += "pickWallpaper" },
            openImport = { fired += "openImport" },
            setDefaultHome = { fired += "setDefaultHome" },
            restoreDefaults = { fired += "restoreDefaults" },
            applyLanguage = { lang -> languages += lang },
        )
    }

    private fun rowsOf(groups: List<GroupSpec>) = groups.flatMap { it.rows }
    private fun row(groups: List<GroupSpec>, id: String) = rowsOf(groups).first { it.id == id }
    private fun ctrl(groups: List<GroupSpec>, id: String) = row(groups, id) as ControlRow

    @Test fun groupOrderFollowsSpec() {
        val g = settingsGroups(Settings(), {}, Recorder().actions)
        assertEquals(
            listOf(
                GroupId.LAYOUT, GroupId.WALLPAPER, GroupId.THEME,
                GroupId.STANDBY, GroupId.CLOCK, GroupId.LANGUAGE, GroupId.OTHER,
            ),
            g.map { it.id },
        )
    }

    @Test fun rowCountsPerGroup() {
        val g = settingsGroups(Settings(), {}, Recorder().actions)
        // 布局 3 / 壁纸 2 动作 + 3 控件 / 主题 3 / 待机 2 / 时钟 1 / 语言 1 / 其他 2 动作
        assertEquals(listOf(3, 5, 3, 2, 1, 1, 2), g.map { it.rows.size })
    }

    @Test fun rowIdsAreUnique() {
        val ids = rowsOf(settingsGroups(Settings(), {}, Recorder().actions)).map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    /** 右栏一屏放得下的不变量(spec §2.1:≤ 8 行、永不滚动)。 */
    @Test fun noGroupExceedsEightRows() {
        val g = settingsGroups(Settings(), {}, Recorder().actions)
        assertTrue(g.all { it.rows.size <= 8 })
    }

    @Test fun wallpaperGroupStartsWithTwoActionRows() {
        val g = settingsGroups(Settings(), {}, Recorder().actions)
        val wallpaper = g.first { it.id == GroupId.WALLPAPER }.rows
        assertTrue(wallpaper[0] is ActionRow)
        assertTrue(wallpaper[1] is ActionRow)
        assertTrue(wallpaper.drop(2).all { it is ControlRow })
    }

    @Test fun otherGroupIsTwoActionRows() {
        val g = settingsGroups(Settings(), {}, Recorder().actions)
        assertTrue(g.first { it.id == GroupId.OTHER }.rows.all { it is ActionRow })
    }

    @Test fun selectedMirrorsSettings() {
        val g = settingsGroups(Settings(), {}, Recorder().actions)
        // 默认 cardsPerRow = 6 → VALID_CARDS_PER_ROW(5,6,8) 的第 1 档「中」
        assertEquals(1, ctrl(g, "cardsPerRow").selected)
        // 默认亮度 0 → 双向滑块正中(zeroAt = 5)
        assertEquals(5, ctrl(g, "wallpaperBrightness").selected)
        assertEquals(5, ctrl(g, "wallpaperBrightness").zeroAt)
        // 默认语言 system → 第 0 档
        assertEquals(0, ctrl(g, "language").selected)

        val other = settingsGroups(
            Settings(cardsPerRow = 8, wallpaperBrightness = -20, language = "zh-TW"),
            {}, Recorder().actions,
        )
        assertEquals(2, ctrl(other, "cardsPerRow").selected)
        assertEquals(3, ctrl(other, "wallpaperBrightness").selected)   // (−20 + 50) / 10
        assertEquals(2, ctrl(other, "language").selected)              // VALID_LANGUAGES 的第 2 档
    }

    @Test fun controlSelectWritesThroughUpdate() {
        var written: Settings? = null
        val base = Settings()
        val g = settingsGroups(base, { transform -> written = transform(base) }, Recorder().actions)
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
        val g = settingsGroups(Settings(), { transform -> written = transform(Settings()) }, r.actions)
        ctrl(g, "language").onSelect(3)
        assertEquals(listOf("en"), r.languages)
        assertEquals(null, written)
    }

    @Test fun actionRowsFireTheirAction() {
        val r = Recorder()
        val g = settingsGroups(Settings(), {}, r.actions)
        (row(g, "pickWallpaper") as ActionRow).onActivate()
        (row(g, "openImport") as ActionRow).onActivate()
        (row(g, "setDefaultHome") as ActionRow).onActivate()
        (row(g, "restoreDefaults") as ActionRow).onActivate()
        assertEquals(
            listOf("pickWallpaper", "openImport", "setDefaultHome", "restoreDefaults"),
            r.fired,
        )
    }

    /** 分段/开关的档数必须与它的显示文案条数一致,否则界面会画出一个点不到的档。 */
    @Test fun segmentedOptionsMatchCount() {
        val g = settingsGroups(Settings(), {}, Recorder().actions)
        rowsOf(g).filterIsInstance<ControlRow>()
            .filter { it.kind == CtrlKind.SEGMENTED || it.kind == CtrlKind.TOGGLE }
            .forEach { assertEquals(it.id, it.count, it.optionRes.size) }
    }
}
