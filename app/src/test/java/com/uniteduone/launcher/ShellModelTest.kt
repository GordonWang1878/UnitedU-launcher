package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页胶囊外壳(R69–R71)的纯模型:菜单树、栈、预览—保存—放弃、滑块、间距、预览框几何。
 * 界面(SettingsShell.kt)只画与管焦点,读的全是这里。
 */
class ShellModelTest {

    private val noActions = SettingsActions(
        pickWallpaper = {}, openImport = {}, setDefaultHome = {}, applyLanguage = {},
        openScreensaverGallery = {}, openSystemScreensaver = {}, openSystemAnimationSettings = {},
    )
    private val normalSystem = SystemUiStatus(
        screensaverEnabled = true,
        screensaverSource = DreamSource.Ours,
        screensaverStart = TimeoutDisplay.Minutes(5),
        animatorScale = 1f, transitionScale = 1f, windowScale = 1f,
    )

    private fun groups(s: Settings = Settings(), sys: SystemUiStatus = normalSystem) =
        settingsGroups(s, {}, noActions, 3, system = sys)

    private fun ids(g: List<GroupSpec>, id: GroupId) = g.first { it.id == id }.rows.map { it.id }

    // ---- 菜单树 ----

    /** Gordon 定案第 2 条:第一层 6 颗,通用 / 布局 / 外观 / 屏保 / 系统设置 / 关于,每颗都带说明小字(2026-09-24 起通用排第一)。 */
    @Test fun rootHasSixCapsulesInOrder() {
        assertEquals(
            listOf("g:GENERAL", "g:LAYOUT", "g:APPEARANCE", "g:SCREENSAVER", "systemSettings", "about"),
            SHELL_ROOT.map { it.id },
        )
        assertTrue(SHELL_ROOT.all { it.hintRes != 0 })
        assertEquals(R.string.shell_root_general_desc, SHELL_ROOT[0].hintRes)
        assertEquals(R.string.menu_system_settings_desc, SHELL_ROOT[4].hintRes)
        assertEquals(R.string.menu_about_desc, SHELL_ROOT[5].hintRes)
        // 四颗分组胶囊的 id 就是它们要进的页
        assertEquals(GroupId.entries.map { ShellPages.group(it) }.toSet(), SHELL_ROOT.take(4).map { it.id }.toSet())
    }

    /** Gordon 定案第 3 条:四个第二层的内容与顺序(条件行不在时)。 */
    @Test fun secondLevelPages() {
        val g = groups()
        // R120:卡片饱和度 / 亮度 / 透明度从外观挪到布局,跟在「卡片标题」之后
        assertEquals(
            listOf("editLayout", "cardsPerRow", "showTitles", "cardSaturation", "cardBrightness", "cardOpacity"),
            ids(g, GroupId.LAYOUT),
        )
        assertEquals(
            // R128:待机两行合成「待机」子页入口(原「待机时长」的位置),「恢复默认」挪进关于页
            listOf("language", "setDefaultHome", "openImport", "standby", "clockDisplay"),
            ids(g, GroupId.GENERAL),
        )
        assertEquals(
            listOf("pickWallpaper", "wallpaperBlur", "wallpaperBrightness", "themeColor", "followWallpaper"),
            ids(g, GroupId.APPEARANCE),
        )
        assertEquals(
            listOf("startScreensaver", "screensaverAfter", "screensaverInterval", "screensaverGallery", "systemScreensaver", "screenOff"),
            ids(g, GroupId.SCREENSAVER),
        )
    }

    /**
     * 条件行:动画缩放(R57 起在通用「恢复默认」之前;R128 恢复默认挪进关于页后它是组末,出现时通用组 6 颗)。
     * (「恢复隐藏的输入源」R92 起不在设置里了,在「输入源」页。)
     */
    @Test fun conditionalRows() {
        val general = ids(groups(sys = normalSystem.copy(animatorScale = 2f)), GroupId.GENERAL)
        assertEquals(listOf("clockDisplay", "systemAnimationScale"), general.takeLast(2))
        assertEquals(6, general.size)
    }

    // ---- R128:「待机」子页 ----

    /** 子页里就是原来那两行(id 不变),缺省焦点第一行;选项层照旧按行 id 找得到、写入函数不变。 */
    @Test fun standbySubPage() {
        val g = groups(Settings(idleAfterMs = 600_000L))
        val page = ShellPages.sub(STANDBY_ROW)
        assertEquals("s:standby", page)
        assertEquals(STANDBY_ROW, ShellPages.subRow(page))
        assertNull(ShellPages.subRow("s:"))
        assertNull(ShellPages.groupOf(page))
        assertNull(ShellPages.optionsRow(page))
        assertEquals(listOf("idleAfter", "idleContent"), pageCapsuleIds(page, g))
        assertEquals("idleAfter", defaultFocus(page, g))
        // 子页里的行仍能按 id 找到(选项层 / 预览 / 解码都靠它)
        assertEquals("opt:4", defaultFocus(ShellPages.options("idleAfter"), g))   // 10 分 = 第 4 档
        assertEquals(listOf(0, 1, 2), optionOrder(controlRow(g, "idleContent")!!))
        // 路径:设置 · 通用(· 待机)
        assertEquals(listOf(R.string.settings_group_general, R.string.settings_standby), rowParents(g, "idleAfter"))
        assertEquals(listOf(R.string.settings_group_general), rowParents(g, STANDBY_ROW))
        assertEquals(listOf(R.string.settings_group_general), rowParents(g, "clockDisplay"))
        assertEquals(emptyList<Int>(), rowParents(g, "nope"))
        assertFalse(pageHasPreview(page))
    }

    /** 进子页 → 进选项层 → 返回 → 返回:每一层落回进入时那颗(外壳原有的栈,没有新机制)。 */
    @Test fun standbyStackReturnsToEnteringCapsule() {
        val g = groups()
        var st = shellPush(shellOpened(), "g:GENERAL", defaultFocus("g:GENERAL", g))
        st = shellSetFocus(st, STANDBY_ROW)
        st = shellPush(st, ShellPages.sub(STANDBY_ROW), defaultFocus(ShellPages.sub(STANDBY_ROW), g))
        st = shellSetFocus(st, "idleContent")
        st = shellPush(st, ShellPages.options("idleContent"), defaultFocus(ShellPages.options("idleContent"), g))
        assertEquals(ShellFrame("o:idleContent", "opt:0"), st.last())
        st = shellPop(st)
        assertEquals(ShellFrame("s:standby", "idleContent"), st.last())
        st = shellPop(st)
        assertEquals(ShellFrame("g:GENERAL", STANDBY_ROW), st.last())
        // 切语言 recreate() 时整栈进 Bundle:子页认得
        val deep = listOf(
            ShellFrame("root", "g:GENERAL"), ShellFrame("g:GENERAL", STANDBY_ROW),
            ShellFrame("s:standby", "idleAfter"), ShellFrame("o:idleAfter", "opt:2"),
        )
        assertEquals(deep, decodeShellStack(encodeShellStack(deep)))
        assertTrue(decodeShellStack("root@x|s:nosuchpage").isEmpty())
    }

    /** 解 Bundle 用的子页清单与模型里真有的子页入口一致(加子页忘了登记会在这里红)。 */
    @Test fun subPageRegistryMatchesModel() {
        val inModel = allRows(groups(sys = SystemUiStatus.UNKNOWN)).filterIsInstance<SubPageRow>().map { it.id }.toSet()
        assertEquals(inModel, SUB_PAGE_ROWS)
    }

    // ---- R128:关于页两颗胶囊;恢复默认确认层从关于页进 ----

    @Test fun aboutPageCapsules() {
        assertEquals(listOf(ShellPages.ABOUT, ABOUT_RESTORE), ABOUT_CAPSULES)
        assertEquals("restoreDefaults", ABOUT_RESTORE)
        assertEquals(ABOUT_CAPSULES, pageCapsuleIds(ShellPages.ABOUT, groups()))
        assertEquals(listOf(SHELL_CANCEL, SHELL_CONFIRM), pageCapsuleIds(ShellPages.RESTORE, groups()))
        assertEquals(listOf(SHELL_CHANGE_HOME), pageCapsuleIds(ShellPages.HOME, groups()))
        assertNull(pageCapsuleIds(ShellPages.SYSTEM_SETTINGS, groups()))
        // 「恢复默认」不再是任何一组里的行
        assertTrue(allRows(groups(sys = SystemUiStatus.UNKNOWN)).none { it.id == ABOUT_RESTORE })
    }

    /** 关于页只在「恢复默认确认层在栈顶」时让开;弹栈后重新出现。关着的永远不画。 */
    @Test fun aboutPageStepsAsideOnlyForRestoreLayer() {
        val root = shellSetFocus(shellOpened(), ShellPages.ABOUT)
        assertTrue(aboutPageShown(true, root))
        val confirm = shellPush(root, ShellPages.RESTORE, defaultFocus(ShellPages.RESTORE, groups()))
        assertEquals(ShellFrame(ShellPages.RESTORE, SHELL_CANCEL), confirm.last())
        assertFalse(aboutPageShown(true, confirm))
        // 取消 / 返回 / 确定都是弹栈:回到第一层(焦点目标仍是「关于」),关于页重新出现
        assertEquals(root, shellPop(confirm))
        assertTrue(aboutPageShown(true, shellPop(confirm)))
        assertFalse(aboutPageShown(false, root))
        assertFalse(aboutPageShown(false, confirm))
        // 栈空而 about 为真(不该出现)时照样画,不留「开着却看不见」的黑洞
        assertTrue(aboutPageShown(true, emptyList()))
    }

    @Test fun defaultFocusPerPage() {
        val g = groups(Settings(cardsPerRow = 8, themePresetId = "blue"))
        assertEquals("g:GENERAL", defaultFocus(ShellPages.ROOT, g))
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
        for (r in listOf("cardsPerRow", "showTitles", "themeColor", "followWallpaper")) {
            assertTrue(r, pageHasPreview(ShellPages.options(r)))
        }
        for (r in listOf("language", "idleAfter", "idleContent", "clockDisplay", "screensaverAfter", "screensaverInterval")) {
            assertFalse(r, pageHasPreview(ShellPages.options(r)))
        }
        assertFalse(pageHasPreview(ShellPages.sub(STANDBY_ROW)))
        assertFalse(pageHasPreview(ShellPages.ABOUT))
    }

    // ---- 栈 ----

    @Test fun pushKeepsParentFocusAndPopReturnsToIt() {
        val g = groups()
        var st = shellOpened()
        assertEquals(listOf(ShellFrame("root", "g:GENERAL")), st)
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
        assertSame(st, shellSetFocus(st, "g:GENERAL"))
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

    // ---- 预览—保存—放弃(R71)----

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

    /** R86/R87:卡片透明度 7 档 0–60%(存盘 cardOpacity = 100 − 透明度),缺省 0,两头到头不越界。 */
    @Test fun cardOpacitySliderStepsClampAtBothEnds() {
        val none = controlRow(groups(Settings()), "cardOpacity")!!
        assertEquals(7, none.count)
        assertEquals(0, none.selected)                // 缺省:透明度 0%(不透明度 100)
        assertEquals("0%", sliderText(none))
        assertEquals(0, sliderStep(none, -1))         // 到头不动
        assertEquals(1, sliderStep(none, +1))
        assertEquals(10, sliderValue(none, 1))
        val most = controlRow(groups(Settings(cardOpacity = 40)), "cardOpacity")!!
        assertEquals(6, most.selected)
        assertEquals("60%", sliderText(most))
        assertEquals(6, sliderStep(most, +1))         // 到头不动
        assertEquals(5, sliderStep(most, -1))
        assertEquals(50, sliderValue(most, 5))
    }

    @Test fun sliderValuesAndText() {
        val g = groups(Settings(wallpaperBlur = 40, wallpaperBrightness = -20, cardSaturation = 30, cardBrightness = 75))
        val blur = controlRow(g, "wallpaperBlur")!!
        assertEquals("40%", sliderText(blur))
        assertEquals("50%", sliderText(blur, 10))   // R119:第 10 档 = 50%
        assertEquals("5%", sliderText(blur, 1))
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
        for (id in listOf("wallpaperBlur", "wallpaperBrightness", "cardSaturation", "cardBrightness", "cardOpacity")) {
            val row = controlRow(g, id)!!
            for (i in 0 until row.count) {
                val s = row.write!!(base, i)
                val v = when (id) {
                    "wallpaperBlur" -> s.wallpaperBlur
                    "wallpaperBrightness" -> s.wallpaperBrightness
                    "cardSaturation" -> s.cardSaturation
                    "cardOpacity" -> 100 - s.cardOpacity  // R87:界面显示透明度
                    else -> s.cardBrightness
                }
                assertEquals("$id#$i", sliderValue(row, i), v)
                // 写下去再读回来,档位不漂(夹取 / 取整是幂等的)
                assertEquals("$id#$i roundtrip", s, parseSettings(s.toJson()))
            }
        }
    }

    // ---- 版式 ----

    /**
     * 7 颗以内保持 16 dp;8 颗(R128 之前的通用组,含动画缩放条件行)缩到 8 dp;第一层 6 颗两行胶囊仍是 16。
     * R128 起每页 ≤ 6,单行胶囊页永远是 16——缩间距只剩兜底。
     */
    @Test fun capsuleGapShrinksOnlyWhenNeeded() {
        assertEquals(16f, capsuleGap(List(7) { 55f }))
        assertEquals(8f, capsuleGap(List(8) { 55f }))
        assertEquals(16f, capsuleGap(List(6) { SHELL_TWO_LINE_PILL_DP }))
        assertEquals(16f, capsuleGap(List(MAX_CAPSULES_PER_PAGE) { 55f }))
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
