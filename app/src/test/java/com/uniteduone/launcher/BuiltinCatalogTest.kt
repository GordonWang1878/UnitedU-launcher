package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置图(R115–R117):目录清单(扩展名过滤、排序、ID、去重、命名警告)、伪路径、壁纸选中值编码与默认壁纸、
 * 屏保轮播 = 参与的内置 + 我的。
 */
class BuiltinCatalogTest {

    // ---- 清单 ----

    @Test fun catalogKeepsOnlyImageExtensionsCaseInsensitively() {
        val names = listOf(".gitkeep", "README.md", "02-b.PNG", "01-a.jpg", "03-c.webp", "04-d.JPEG", "05-e.mp4", "sub", ".DS_Store")
        val list = builtinCatalog(BuiltinKind.WALLPAPERS, names)
        assertEquals(listOf("01-a.jpg", "02-b.PNG", "03-c.webp", "04-d.JPEG"), list.map { it.fileName })
    }

    @Test fun catalogSortsByFileNameAscendingSoTheFirstIsTheDefault() {
        val list = builtinCatalog(BuiltinKind.WALLPAPERS, listOf("10-j.jpg", "02-b.jpg", "01-a.jpg"))
        assertEquals(listOf("01-a", "02-b", "10-j"), list.map { it.id })
    }

    @Test fun idIsTheFileNameWithoutExtension() {
        assertEquals("01-dusk-city", builtinIdOf("01-dusk-city.jpg"))
        assertEquals("01-a.b", builtinIdOf("01-a.b.png"))   // 只去最后一个扩展名
        assertEquals("noext", builtinIdOf("noext"))
        assertEquals("01-dusk-city", BuiltinImage(BuiltinKind.CARDS, "01-dusk-city.png").id)
    }

    @Test fun duplicateIdsKeepTheFirstAndWarn() {
        val warnings = mutableListOf<String>()
        val list = builtinCatalog(BuiltinKind.SCREENSAVERS, listOf("01-a.png", "01-a.jpg", "02-b.jpg"), warn = { warnings += it })
        // 排序后 01-a.jpg 在 01-a.png 前面,留它
        assertEquals(listOf("01-a.jpg", "02-b.jpg"), list.map { it.fileName })
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("01-a.png"))
    }

    @Test fun unconventionalNamesAreKeptButWarned() {
        val warnings = mutableListOf<String>()
        val list = builtinCatalog(BuiltinKind.CARDS, listOf("WeTV.png", "01-ok.png", "1-x.png", "02-Bad.png"), warn = { warnings += it })
        assertEquals(listOf("01-ok.png", "02-Bad.png", "1-x.png", "WeTV.png"), list.map { it.fileName })
        assertEquals(3, warnings.size)
        assertFalse(warnings.any { it.contains("01-ok.png") })
    }

    @Test fun namingRule() {
        assertTrue(isConventionalBuiltinName("01-dusk-city.jpg"))
        assertTrue(isConventionalBuiltinName("12-a1.PNG"))
        assertFalse(isConventionalBuiltinName("1-dusk.jpg"))       // 两位序号
        assertFalse(isConventionalBuiltinName("01-Dusk.jpg"))      // 大写
        assertFalse(isConventionalBuiltinName("01-dusk city.jpg")) // 空格
        assertFalse(isConventionalBuiltinName("01--dusk.jpg"))     // 连用连字符
        assertFalse(isConventionalBuiltinName("01-dusk-.jpg"))     // 连字符结尾
        assertFalse(isConventionalBuiltinName("01-.jpg"))
    }

    @Test fun labelDropsTheOrderPrefixOnlyForConventionalIds() {
        assertEquals("dusk-city", builtinLabelOf("01-dusk-city"))
        assertEquals("WeTV", builtinLabelOf("WeTV"))
        assertEquals("1-x", builtinLabelOf("1-x"))
    }

    @Test fun emptyOrHiddenNamesAreIgnored() {
        assertEquals(emptyList<BuiltinImage>(), builtinCatalog(BuiltinKind.CARDS, emptyList()))
        assertEquals(emptyList<BuiltinImage>(), builtinCatalog(BuiltinKind.CARDS, listOf(".jpg", ".hidden.png")))
    }

    // ---- 伪路径 ----

    @Test fun pseudoPathRoundTrips() {
        val img = BuiltinImage(BuiltinKind.SCREENSAVERS, "01-aurora.jpg")
        assertEquals("builtin/screensavers/01-aurora.jpg", img.assetPath)
        assertEquals("/android_asset/builtin/screensavers/01-aurora.jpg", img.file.path)
        assertEquals("builtin/screensavers/01-aurora.jpg", builtinAssetPathOf(img.file.path))
        assertEquals(img, builtinImageOf(img.file))
        assertEquals("01-aurora.jpg", img.file.name)   // 播放器按 name 认视频 / 取 ID
    }

    @Test fun ordinaryFilesAreNotBuiltin() {
        assertNull(builtinAssetPathOf("/sdcard/Android/data/x/files/library/wallpapers/a.jpg"))
        assertNull(builtinImageOf(File("/storage/emulated/0/a.jpg")))
        assertNull(builtinAssetPathOf("/android_asset/web/index.html"))           // 不是 builtin/ 底下
        assertNull(builtinAssetPathOf("/android_asset/builtin/../web/index.html")) // 防御
        assertNull(builtinImageOf(File("/android_asset/builtin/unknown/a.jpg")))  // 不认识的分类
    }

    // ---- 壁纸选中值(R116)----

    @Test fun choiceEncodingDistinguishesBuiltinAndMine() {
        assertEquals("builtin:01-a", encodeBuiltinChoice("01-a"))
        assertEquals(ImageChoice.Builtin("01-a"), decodeImageChoice("builtin:01-a"))
        // 旧 settings:纯文件名 = 我的
        assertEquals(ImageChoice.Mine("01-a.jpg"), decodeImageChoice("01-a.jpg"))
        assertNull(decodeImageChoice(""))
        // 只有前缀、没有 ID → 当文件名(不会是合法的内置选中值)
        assertEquals(ImageChoice.Mine("builtin:"), decodeImageChoice("builtin:"))
    }

    private fun resolve(value: String, builtins: List<String>, mine: List<String>) =
        resolveWallpaperChoice(value, builtins, mineExists = { it in mine }, firstMine = { mine.sorted().firstOrNull() })

    @Test fun defaultWallpaperIsTheFirstBuiltinNotRandom() {
        val builtins = listOf("01-a", "02-b", "03-c", "04-d")
        repeat(5) { assertEquals(ImageChoice.Builtin("01-a"), resolve("", builtins, listOf("x.jpg"))) }
    }

    @Test fun selectedChoiceWinsWhileItExists() {
        val builtins = listOf("01-a", "02-b")
        assertEquals(ImageChoice.Builtin("02-b"), resolve("builtin:02-b", builtins, emptyList()))
        assertEquals(ImageChoice.Mine("x.jpg"), resolve("x.jpg", builtins, listOf("x.jpg")))
        // 内置与我的同名不冲突:各认各的
        assertEquals(ImageChoice.Mine("01-a.jpg"), resolve("01-a.jpg", builtins, listOf("01-a.jpg")))
        assertEquals(ImageChoice.Builtin("01-a"), resolve("builtin:01-a", builtins, listOf("01-a.jpg")))
    }

    @Test fun missingChoiceFallsBackToTheDefault() {
        val builtins = listOf("01-a", "02-b")
        assertEquals(ImageChoice.Builtin("01-a"), resolve("gone.jpg", builtins, listOf("x.jpg")))
        assertEquals(ImageChoice.Builtin("01-a"), resolve("builtin:09-removed", builtins, listOf("x.jpg")))
    }

    @Test fun emptyBuiltinListKeepsTheR61Behaviour() {
        // 清单为空:没选 / 选中的没了 → 图库第一张;图库也空 → null(纯深色)
        assertEquals(ImageChoice.Mine("a.jpg"), resolve("", emptyList(), listOf("b.jpg", "a.jpg")))
        assertEquals(ImageChoice.Mine("a.jpg"), resolve("gone.jpg", emptyList(), listOf("a.jpg")))
        assertNull(resolve("", emptyList(), emptyList()))
        assertNull(resolve("builtin:01-a", emptyList(), emptyList()))
    }

    @Test fun userFileLiterallyNamedLikeABuiltinChoiceStillResolves() {
        // 防御:用户上传的图叫 `builtin:x.jpg`、清单里没有 `x.jpg` 这个 ID → 按图库文件认
        assertEquals(ImageChoice.Mine("builtin:x.jpg"), resolve("builtin:x.jpg", listOf("01-a"), listOf("builtin:x.jpg")))
    }

    // ---- 屏保轮播(R117)----

    @Test fun playlistIsParticipatingBuiltinsThenMine() {
        val builtins = listOf("01-a", "02-b", "03-c")
        val mine = listOf("m1.jpg", "m2.mp4")
        assertEquals(listOf("01-a", "02-b", "03-c", "m1.jpg", "m2.mp4"), screensaverPlaylist(builtins, { it }, emptySet(), mine))
        assertEquals(listOf("01-a", "03-c", "m1.jpg", "m2.mp4"), screensaverPlaylist(builtins, { it }, setOf("02-b"), mine))
        // 全关掉 + 图库空 = 轮播空(「立即开始屏保」据此提示)
        assertTrue(screensaverPlaylist(builtins, { it }, builtins.toSet(), emptyList()).isEmpty())
        // 排除集合里有清单已没有的 ID:不影响
        assertEquals(listOf("01-a", "m1.jpg"), screensaverPlaylist(listOf("01-a"), { it }, setOf("09-gone"), listOf("m1.jpg")))
    }

    @Test fun playlistWithPseudoFilesUsesTheIdFromTheName() {
        val files = listOf(BuiltinImage(BuiltinKind.SCREENSAVERS, "01-a.jpg").file, BuiltinImage(BuiltinKind.SCREENSAVERS, "02-b.png").file)
        val mine = listOf(File("/x/library/screensavers/01-a.jpg"))
        val out = screensaverPlaylist(files, { builtinIdOf(it.name) }, setOf("01-a"), mine)
        // 内置 01-a 被排除,图库里同名的 01-a.jpg 照样在
        assertEquals(listOf(files[1], mine[0]), out)
    }

    @Test fun toggleAddsAndRemoves() {
        assertEquals(setOf("01-a"), toggleExcluded(emptySet(), "01-a"))
        assertEquals(emptySet<String>(), toggleExcluded(setOf("01-a"), "01-a"))
        assertEquals(setOf("01-a", "02-b"), toggleExcluded(setOf("01-a"), "02-b"))
    }
}
