package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页胶囊外壳(R67–R71)的纯模型:菜单树、栈、预览—保存—放弃、滑块、间距、预览框几何。
 * 界面(SettingsShell.kt)只画与管焦点,读的全是这里。
 */
class ShellModelTest {

    private val noActions = SettingsActions(
        pickWallpaper = {}, openImport = {}, setDefaultHome = {}, restoreDefaults = {}, applyLanguage = {},
        openScreensaverGallery = {}, openSystemScreensaver = {}, restoreHiddenInputs = {}, openSystemAnimationSettings = {},
    )
    private val normalSystem = SystemUiStatus(
        screensaverEnabled = true,
        screensaverSource = DreamSource.Ours,
        screensaverStart = TimeoutDisplay.Minutes(5),
        animatorScale = 1f, transitionScale = 1f, windowScale = 1f,
    )

    private fun groups(s: Settings = Settings(), hidden: Int = 0, sys: SystemUiStatus = normalSystem) =
        settingsGroups(s, {}, noActions, 3, hiddenInputs = hidden, system = sys)

    private fun ids(g: List<GroupSpec>, id: GroupId) = g.first { it.id == id }.rows.map { it.id }

    // ---- 菜单树 ----

    /** Gordon 定案第 2 条:第一层 6 颗,布局 / 通用 / 外观 / 屏保 / 系统设置 / 关于,每颗都带说明小字。 */
    @Test fun rootHasSixCapsulesInOrder() {
        assertEquals(
            listOf("g:LAYOUT", "g:GENERAL", "g:APPEARANCE", "g:SCREENSAVER", "systemSettings", "about"),
            SHELL_ROOT.map { it.id },
        )
        assertTrue(SHELL_ROOT.all { it.hintRes != 0 })
        assertEquals(R.string.shell_root_layout_desc, SHELL_ROOT[0].hintRes)
        assertEquals(R.string.menu_system_settings_desc, SHELL_ROOT[4].hintRes)
        assertEquals(R.string.menu_about_desc, SHELL_ROOT[5].hintRes)
        // 四颗分组胶囊的 id 就是它们要进的页
        assertEquals(GroupId.entries.map { ShellPages.group(it) }, SHELL_ROOT.take(4).map { it.id })
    }

    /** Gordon 定案第 3 条:四个第二层的内容与顺序(条件行不在时)。 */
    @Test fun secondLevelPages() {
        val g = groups()
        assertEquals(listOf("editLayout", "cardsPerRow", "showTitles", "showInputRow"), ids(g, GroupId.LAYOUT))
        assertEquals(
            listOf("language", "setDefaultHome", "openImport", "idleAfter", "idleContent", "clockDisplay", "restoreDefaults"),
            ids(g, GroupId.GENERAL),
        )
        assertEquals(
            listOf("pickWallpaper", "wallpaperBlur", "wallpaperBrightness", "themeColor", "followWallpaper", "cardSaturation", "cardBrightness"),
            ids(g, GroupId.APPEARANCE),
        )
        assertEquals(
            listOf("screensaverAfter", "screensaverInterval", "screensaverGallery", "systemScreensaver"),
            ids(g, GroupId.SCREENSAVER),
        )
    }

    /** 条件行:恢复隐藏的输入源(布局末行)、动画缩放(通用「恢复默认」之前)。 */
    @Test fun conditionalRows() {
        assertEquals("restoreHiddenInputs", ids(groups(hidden = 2), GroupId.LAYOUT).last())
        val general = ids(groups(sys = normalSystem.copy(animatorScale = 2f)), GroupId.GENERAL)
        assertEquals(listOf("systemAnimationScale", "restoreDefaults"), general.takeLast(2))
        assertEquals(8, general.size)
    }

    @Test fun defaultFocusPerPage() {
        val g = groups(Settings(cardsPerRow = 8, themePresetId = "blue"))
        assertEquals("g:LAYOUT", defaultFocus(ShellPages.ROOT, g))
        assertEquals("editLayout", defaultFocus(ShellPages.group(GroupId.LAYOUT), g))
        assertEquals("language", defaultFocus(ShellPages.group(GroupId.GENERAL), g))
        // 选项层落在已保存那一档:8 张 = 第 2 档「小」
        assertEquals("opt:2", defaultFocus(ShellPages.options("cardsPerRow"), g))
        assertEquals(optionId(ThemePresets.indexOf("blue")), defaultFocus(ShellPages.options("themeColor"), g))
        assertEquals(SHELL_CANCEL, defaultFocus(ShellPages.RESTORE, g))
        assertEquals(SHELL_CHANGE_HOME, defaultFocus(ShellPages.HOME, g))
        assertNull(defaultFocus("nope", g))
    }

    /** 卡片大小显示成 小 / 中 / 大(效果图 M3),存储档位不变;其它行按档位顺序。 */
    @Test fun optionOrderReversesOnlyCardSize() {
        val g = groups()
        assertEquals(listOf(2, 1, 0), optionOrder(controlRow(g, "cardsPerRow")!!))
        assertEquals(listOf(0, 1), optionOrder(controlRow(g, "showTitles")!!))
        assertEquals(listOf(0, 1, 2, 3), optionOrder(controlRow(g, "language")!!))
    }

    @Test fun pagesWithPreview() {
        assertTrue(pageHasPreview(ShellPages.group(GroupId.LAYOUT)))
        assertTrue(pageHasPreview(ShellPages.group(GroupId.APPEARANCE)))
        assertFalse(pageHasPreview(ShellPages.group(GroupId.GENERAL)))
        assertFalse(pageHasPreview(ShellPages.group(GroupId.SCREENSAVER)))
        assertFalse(pageHasPreview(ShellPages.ROOT))
        assertFalse(pageHasPreview(ShellPages.RESTORE))
        assertFalse(pageHasPreview(ShellPages.HOME))
        for (r in listOf("cardsPerRow", "showTitles", "showInputRow", "themeColor", "followWallpaper")) {
            assertTrue(r, pageHasPreview(ShellPages.options(r)))
        }
        for (r in listOf("language", "idleAfter", "idleContent", "clockDisplay", "screensaverAfter", "screensaverInterval")) {
            assertFalse(r, pageHasPreview(ShellPages.options(r)))
        }
    }

    // ---- 栈 ----

    @Test fun pushKeepsParentFocusAndPopReturnsToIt() {
        val g = groups()
        var st = shellOpened()
        assertEquals(listOf(ShellFrame("root", "g:LAYOUT")), st)
        st = shellPush(st, "g:LAYOUT", defaultFocus("g:LAYOUT", g))
        st = shellSetFocus(st, "cardsPerRow")
        st = shellPush(st, "o:cardsPerRow", defaultFocus("o:cardsPerRow", g))
        assertEquals(ShellFrame("g:LAYOUT", "cardsPerRow"), st[1])
        assertEquals(ShellFrame("o:cardsPerRow", "opt:1"), st.last())
        st = shellPop(st)
        // 返回 = 落回进入时那颗胶囊
        assertEquals(ShellFrame("g:LAYOUT", "cardsPerRow"), st.last())
        st = shellPop(shellPop(st))
        assertTrue(st.isEmpty())
        assertTrue(shellPop(st).isEmpty())
    }

    @Test fun setFocusIsNoOpWhenUnchangedOrEmpty() {
        val st = shellOpened()
        assertSame(st, shellSetFocus(st, "g:LAYOUT"))
        assertTrue(shellSetFocus(emptyList(), "x").isEmpty())
    }

    @Test fun stackEncodingRoundTrips() {
        val st = listOf(ShellFrame("root", "g:GENERAL"), ShellFrame("g:GENERAL", "language"), ShellFrame("o:language", null))
        assertEquals(st, decodeShellStack(encodeShellStack(st)))
        val st2 = listOf(ShellFrame("root", "about"), ShellFrame("restore", "cancel"))
        assertEquals(st2, decodeShellStack(encodeShellStack(st2)))
    }

    @Test fun badStacksDecodeToClosed() {
        assertTrue(decodeShellStack(null).isEmpty())
        assertTrue(decodeShellStack("").isEmpty())
        assertTrue(decodeShellStack("g:LAYOUT@editLayout").isEmpty())          // 第一帧不是第一层
        assertTrue(decodeShellStack("root@x|g:NOPE@y").isEmpty())               // 不认识的组
        assertTrue(decodeShellStack("root@x|o:nosuchrow@opt:0").isEmpty())      // 不认识的选项行
        assertTrue(decodeShellStack("root@x|weird").isEmpty())
    }

    // ---- 预览—保存—放弃(R69)----

    @Test fun cursorOnOptionPreviewsWithoutSaving() {
        val saved = Settings()   // 中档
        val g = groups(saved)
        var st = shellPush(shellPush(shellOpened(), "g:LAYOUT", "cardsPerRow"), "o:cardsPerRow", defaultFocus("o:cardsPerRow", g))
        // 一进来光标在已保存档:没有预览
        assertEquals(saved, effectiveSettings(saved, st))
        // 光标移到「大」(档位 0 = 5 张):首页立刻变成大档,但 saved 没动
        st = shellSetFocus(st, optionId(0))
        assertEquals(5, effectiveSettings(saved, st).cardsPerRow)
        assertEquals(6, saved.cardsPerRow)
        // 返回 = 放弃:弹栈后预览消失
        assertEquals(saved, effectiveSettings(saved, shellPop(st)))
    }

    /** 确定 = 用同一个写入函数落盘:落盘后的设置就是刚才看到的预览。 */
    @Test fun okSavesExactlyWhatWasPreviewed() {
        val saved = Settings()
        val st = listOf(ShellFrame("root", "g:APPEARANCE"), ShellFrame("g:APPEARANCE", "themeColor"), ShellFrame("o:themeColor", optionId(0)))
        val previewed = effectiveSettings(saved, st)
        val row = controlRow(groups(saved), "themeColor")!!
        assertEquals(ThemePresets.all[0].id, previewed.themePresetId)
        assertEquals(previewed, row.write!!(saved, 0))
    }

    @Test fun rowsWithoutPreviewNeverChangeEffectiveSettings() {
        val saved = Settings()
        for ((row, i) in listOf("language" to 3, "idleAfter" to 0, "clockDisplay" to 0, "screensaverAfter" to 0)) {
            val st = listOf(ShellFrame("root", "g:GENERAL"), ShellFrame("g:GENERAL", row), ShellFrame("o:$row", optionId(i)))
            assertEquals(row, saved, effectiveSettings(saved, st))
        }
        // 栈顶不是选项层、或焦点不是选项:原样
        assertEquals(saved, effectiveSettings(saved, emptyList()))
        assertEquals(saved, effectiveSettings(saved, listOf(ShellFrame("root", "g:LAYOUT"))))
        assertEquals(saved, effectiveSettings(saved, listOf(ShellFrame("o:cardsPerRow", "g:LAYOUT"))))
        // 越界档位不崩,当作没有预览
        assertEquals(saved, effectiveSettings(saved, listOf(ShellFrame("o:cardsPerRow", optionId(9)))))
    }

    @Test fun togglePreviewFlips() {
        val saved = Settings(showTitles = false, followWallpaperColor = false)
        assertTrue(effectiveSettings(saved, listOf(ShellFrame("o:showTitles", optionId(1)))).showTitles)
        assertTrue(effectiveSettings(saved, listOf(ShellFrame("o:followWallpaper", optionId(1)))).followWallpaperColor)
        assertTrue(effectiveSettings(saved, listOf(ShellFrame("o:showInputRow", optionId(1)))).showInputRow)
    }

    // ---- 滑块 ----

    @Test fun sliderStepsClampAtBothEnds() {
        val g = groups(Settings(wallpaperBlur = 0, cardBrightness = 100))
        val blur = controlRow(g, "wallpaperBlur")!!
        assertEquals(0, sliderStep(blur, -1))
        assertEquals(1, sliderStep(blur, +1))
        val bri = controlRow(g, "cardBrightness")!!
        assertEquals(10, sliderStep(bri, +1))
        assertEquals(9, sliderStep(bri, -1))
    }

    @Test fun sliderValuesAndText() {
        val g = groups(Settings(wallpaperBlur = 40, wallpaperBrightness = -20, cardSaturation = 30, cardBrightness = 75))
        val blur = controlRow(g, "wallpaperBlur")!!
        assertEquals("40%", sliderText(blur))
        assertEquals("100%", sliderText(blur, 10))
        val wb = controlRow(g, "wallpaperBrightness")!!
        assertEquals("-20%", sliderText(wb))
        assertEquals("0%", sliderText(wb, 5))
        assertEquals("+50%", sliderText(wb, 10))
        val sat = controlRow(g, "cardSaturation")!!
        assertEquals(30, sliderValue(sat))
        assertEquals("0%", sliderText(sat, 0))
        val cb = controlRow(g, "cardBrightness")!!
        assertEquals("75%", sliderText(cb))
        assertEquals("50%", sliderText(cb, 0))
        assertEquals(100, sliderValue(cb, 10))
    }

    /** 滑块的档位 → 写入值与 sliderValue 一致(界面显示的数字就是落盘的数字)。 */
    @Test fun sliderWriteMatchesDisplayedValue() {
        val base = Settings()
        val g = groups(base)
        for (id in listOf("wallpaperBlur", "wallpaperBrightness", "cardSaturation", "cardBrightness")) {
            val row = controlRow(g, id)!!
            for (i in 0 until row.count) {
                val s = row.write!!(base, i)
                val v = when (id) {
                    "wallpaperBlur" -> s.wallpaperBlur
                    "wallpaperBrightness" -> s.wallpaperBrightness
                    "cardSaturation" -> s.cardSaturation
                    else -> s.cardBrightness
                }
                assertEquals("$id#$i", sliderValue(row, i), v)
                // 写下去再读回来,档位不漂(夹取 / 取整是幂等的)
                assertEquals("$id#$i roundtrip", s, parseSettings(s.toJson()))
            }
        }
    }

    // ---- 版式 ----

    /** 7 颗以内保持 16 dp;通用组 8 颗(含动画缩放条件行)缩到 8 dp;第一层 6 颗两行胶囊仍是 16。 */
    @Test fun capsuleGapShrinksOnlyWhenNeeded() {
        assertEquals(16f, capsuleGap(List(7) { 55f }))
        assertEquals(8f, capsuleGap(List(8) { 55f }))
        assertEquals(16f, capsuleGap(List(6) { SHELL_TWO_LINE_PILL_DP }))
        assertEquals(16f, capsuleGap(listOf(55f)))
        // 放不下时也不低于下限
        assertEquals(4f, capsuleGap(List(12) { 55f }))
        // 结果总能放进 500 dp(在下限之上时)
        val h = List(8) { 55f }
        assertTrue(h.sum() + 7 * capsuleGap(h) <= SHELL_MAX_COLUMN_DP)
    }

    /** 1080p(960 × 540 dp):预览 400 × 225 dp(16:9),左 40、顶 180——与效果图 README 第 2 条一致。 */
    @Test fun previewRectOn1080p() {
        val r = previewRect(960f, 540f)
        assertEquals(40f, r.x)
        assertEquals(180f, r.y)
        assertEquals(400f, r.width)
        assertEquals(225f, r.height, 1e-4f)
        // 预览框在左半屏之内
        assertTrue(r.x + r.width <= 960f / 2)
    }
}
