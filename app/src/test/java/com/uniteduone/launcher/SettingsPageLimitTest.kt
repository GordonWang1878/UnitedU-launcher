package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **R128(Gordon 2026-09-29,方案 A):设置页每一页右边的胶囊 ≤ 6 颗**——第一层、四组、子页、每个选项层、默认桌面、
 * 恢复默认确认层、关于页,一页都不放过;条件行按「全部出现」算。要加一颗而某页已满 6:先合并(像「待机」子页那样),再加。
 *
 * 怎么数:页清单来自 [shellPages](外壳能走到的每一页),每页的胶囊来自 [pageCapsuleIds]——界面画的就是这两份
 * (分组 / 子页画行、选项层画 [optionOrder] 的档位、第一层 / 默认桌面 / 确认层 / 关于页按同名那张表画),所以这里数的就是屏幕上的。
 * 条件行:模型按「系统快照」决定出不出现,下面 [variants] 把每一种会让条件行出现的输入都喂一遍,每页取最多的那次。
 * **新加条件行时,把让它出现的那份输入加进 [variants]**,否则这条测试数不到它。
 */
class SettingsPageLimitTest {

    private val noActions = SettingsActions(
        pickWallpaper = {}, openImport = {}, setDefaultHome = {}, applyLanguage = {},
        openScreensaverGallery = {}, openSystemScreensaver = {}, openSystemAnimationSettings = {},
    )

    private val normal = SystemUiStatus(
        screensaverEnabled = true,
        screensaverSource = DreamSource.Ours,
        screensaverStart = TimeoutDisplay.Minutes(5),
        animatorScale = 1f, transitionScale = 1f, windowScale = 1f,
    )

    /** 让每一条条件行都出现过的输入组合(动画缩放行:≠ 1× 或读不到)。图库张数 0 / −1 / 有图都走一遍(只影响小字,不影响行数)。 */
    private val variants: List<List<GroupSpec>> = buildList {
        val systems = listOf(
            normal,
            normal.copy(animatorScale = 1.5f),       // 动画缩放行出现(界面动画变慢)
            normal.copy(windowScale = 0.5f),         // 只有窗口 / 过渡 ≠ 1 也出现
            SystemUiStatus.UNKNOWN,                  // 读不到:出现并写「查看」
        )
        val settings = listOf(Settings(), Settings(idleAfterMs = 0L, screensaverAfterMs = 0L, followWallpaperColor = true))
        for (sys in systems) for (s in settings) for (images in listOf(-1, 0, 3)) {
            add(settingsGroups(s, {}, noActions, images, system = sys))
        }
    }

    @Test fun everySettingsPageHasAtMostSixCapsules() {
        val maxPerPage = linkedMapOf<String, Int>()
        for (groups in variants) {
            for (page in shellPages(groups)) {
                val ids = pageCapsuleIds(page, groups)
                assertNotNull("外壳页 $page 数不出胶囊(pageCapsuleIds 返回 null)——新页要在 pageCapsuleIds 里登记", ids)
                maxPerPage[page] = maxOf(maxPerPage[page] ?: 0, ids!!.size)
            }
        }
        val over = maxPerPage.filterValues { it > MAX_CAPSULES_PER_PAGE }
        assertTrue(
            "设置页每页胶囊 ≤ 6(R128,Gordon 2026-09-29)——先合并再加。超了的页:" +
                over.entries.joinToString { (p, n) -> "$p 有 $n 颗" },
            over.isEmpty(),
        )
    }

    /** 这条测试真的数到了条件行最多的情况:动画缩放行出现时「通用」正好 6 颗(= 上限,再加一颗常驻行就会红)。 */
    @Test fun conditionalRowsAreCountedAtTheirMaximum() {
        val general = ShellPages.group(GroupId.GENERAL)
        val counts = variants.map { pageCapsuleIds(general, it)!! }
        assertTrue(counts.any { "systemAnimationScale" in it })
        assertEquals(MAX_CAPSULES_PER_PAGE, counts.maxOf { it.size })
        assertEquals(MAX_CAPSULES_PER_PAGE - 1, counts.minOf { it.size })
    }

    /** 页清单覆盖外壳里每一种页:第一层、四组、子页、选项层(滑块不开选项层)、默认桌面、恢复默认、关于。 */
    @Test fun pageListCoversEveryKindOfPage() {
        val groups = settingsGroups(Settings(), {}, noActions, 3, system = normal.copy(animatorScale = 1.5f))
        val pages = shellPages(groups)
        assertEquals(pages.size, pages.distinct().size)
        assertTrue(ShellPages.ROOT in pages)
        GroupId.entries.forEach { assertTrue(it.name, ShellPages.group(it) in pages) }
        assertTrue(ShellPages.sub(STANDBY_ROW) in pages)
        allRows(groups).filterIsInstance<ControlRow>().forEach { r ->
            assertEquals(r.id, r.kind != CtrlKind.SLIDER, ShellPages.options(r.id) in pages)
        }
        assertTrue(ShellPages.options("idleAfter") in pages)     // 子页里的行的选项层也在
        assertTrue(ShellPages.HOME in pages)
        assertTrue(ShellPages.RESTORE in pages)
        assertTrue(ShellPages.ABOUT in pages)
        // 第一层每颗进的页(系统设置直接跳安卓设置,不是一页)都在清单里
        SHELL_ROOT.map { it.id }.filter { it != ShellPages.SYSTEM_SETTINGS }.forEach { assertTrue(it, it in pages) }
    }

    /**
     * R128 之后的具体数:第一层 6、通用 5(+ 条件行 6)、布局 6、外观 5、屏保 6、待机 2、关于 2、恢复默认 2、
     * 默认桌面 2(R162 起:第二颗「主页键接管」是条件行,按全部出现算)。
     */
    @Test fun capsuleCountsAfterR128() {
        val plain = settingsGroups(Settings(), {}, noActions, 3, system = normal)
        fun n(page: String, g: List<GroupSpec> = plain) = pageCapsuleIds(page, g)!!.size
        assertEquals(6, n(ShellPages.ROOT))
        assertEquals(5, n(ShellPages.group(GroupId.GENERAL)))
        assertEquals(6, n(ShellPages.group(GroupId.GENERAL), settingsGroups(Settings(), {}, noActions, 3, system = SystemUiStatus.UNKNOWN)))
        assertEquals(6, n(ShellPages.group(GroupId.LAYOUT)))
        assertEquals(5, n(ShellPages.group(GroupId.APPEARANCE)))
        assertEquals(6, n(ShellPages.group(GroupId.SCREENSAVER)))
        assertEquals(2, n(ShellPages.sub(STANDBY_ROW)))
        assertEquals(3, n(ShellPages.ABOUT))
        assertEquals(2, n(ShellPages.CHANNEL))
        assertEquals(2, n(ShellPages.RESTORE))
        assertEquals(2, n(ShellPages.HOME))
        // 选项层里最多的是主题色 5 个预设、待机时长 5 档、屏保启动 5 档
        val optionMax = shellPages(plain).filter { ShellPages.optionsRow(it) != null }.maxOf { n(it) }
        assertEquals(5, optionMax)
    }
}
