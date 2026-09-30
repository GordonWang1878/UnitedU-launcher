package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置图名字表 names.txt 与目录清单的边界(BuiltinCatalogTest / BuiltinNamesTest 之外):CRLF / CR 换行、BOM、
 * 空白与缩进的注释、值里的 `#` 与 `=`、重复的键、缺列 / 多列 / 空列、认不出的语言代码,以及清单里的大小写与重名。
 */
class BuiltinCatalogBoundaryTest {
    private fun wp(fileName: String) = BuiltinImage(BuiltinKind.WALLPAPERS, fileName)

    // ---- names.txt 解析 ----

    @Test fun crlfAndBareCrLineEndingsParseLikeLf() {
        val lf = "# 注释\nwallpapers/01-a = 甲 | 乙 | A\n\nwallpapers/02-b = 丙 | 丁 | B\n"
        val expected = parseBuiltinNames(lf)
        assertEquals(2, expected.size)
        assertEquals(expected, parseBuiltinNames(lf.replace("\n", "\r\n")))
        assertEquals(expected, parseBuiltinNames(lf.replace("\n", "\r")))
        // 最后一列不带着 \r
        assertEquals(listOf("甲", "乙", "A"), parseBuiltinNames("wallpapers/01-a = 甲 | 乙 | A\r\n")["wallpapers/01-a"])
    }

    @Test fun aLeadingBomIsStrippedWhateverFollowsIt() {
        assertEquals(mapOf("wallpapers/01-a" to listOf("甲")), parseBuiltinNames("﻿# c\r\nwallpapers/01-a=甲"))
        assertEquals(setOf("wallpapers/01-a"), parseBuiltinNames("﻿wallpapers/01-a = 甲").keys)
        assertEquals(emptyMap<String, List<String>>(), parseBuiltinNames("﻿"))
    }

    @Test fun emptyAndBlankTextsParseToAnEmptyTable() {
        assertEquals(emptyMap<String, List<String>>(), parseBuiltinNames(""))
        assertEquals(emptyMap<String, List<String>>(), parseBuiltinNames("\n\r\n \t \n"))
    }

    @Test fun whitespaceOnlyLinesAndIndentedCommentsAreIgnored() {
        val t = "\n   \n\t\n  # 缩进的注释\n#wallpapers/01-a = 注释掉的行\nwallpapers/02-b = 乙\n\n"
        assertEquals(mapOf("wallpapers/02-b" to listOf("乙")), parseBuiltinNames(t))
    }

    @Test fun hashAndEqualsInsideAValueArePartOfTheValue() {
        val t = parseBuiltinNames("cards/01-a = C# 频道 | C# 頻道 | C# Channel\ncards/02-b = 1=1 | 2=2 | 3=3")
        assertEquals(listOf("C# 频道", "C# 頻道", "C# Channel"), t["cards/01-a"])
        assertEquals(listOf("1=1", "2=2", "3=3"), t["cards/02-b"])
    }

    @Test fun tabsAndFullWidthSpacesAroundKeysAndColumnsAreTrimmed() {
        val t = parseBuiltinNames("\twallpapers/01-a\t=\t甲\t|　乙　|  A  ")
        assertEquals(listOf("甲", "乙", "A"), t["wallpapers/01-a"])
    }

    @Test fun linesWithoutASeparatorOrWithAnEmptyKeyAreIgnored() {
        assertEquals(emptyMap<String, List<String>>(), parseBuiltinNames("wallpapers/01-a\n = x\n   =\n=\n|\n"))
    }

    @Test fun aDuplicateKeyKeepsTheLastLine() {
        // 当前行为:后写的覆盖先写的(KDoc 没有约定;BuiltinNamesTest 不查重复行)
        val t = parseBuiltinNames("wallpapers/01-a = 旧 | 舊 | Old\nwallpapers/01-a = 新 | 新 | New\n")
        assertEquals(1, t.size)
        assertEquals(listOf("新", "新", "New"), t["wallpapers/01-a"])
    }

    @Test fun sameIdUnderDifferentKindsAreSeparateEntries() {
        val t = parseBuiltinNames("wallpapers/01-a = 壁 | 壁 | Wall\nscreensavers/01-a = 屏 | 屏 | Saver")
        assertEquals("Wall", builtinDisplayName(t, wp("01-a.jpg"), "en"))
        assertEquals("Saver", builtinDisplayName(t, BuiltinImage(BuiltinKind.SCREENSAVERS, "01-a.png"), "en"))
        assertEquals("a", builtinDisplayName(t, BuiltinImage(BuiltinKind.CARDS, "01-a.png"), "en"))
    }

    // ---- 取名 ----

    @Test fun extraColumnsAreKeptButIgnored() {
        val t = parseBuiltinNames("wallpapers/01-a = 甲 | 乙 | A | extra")
        assertEquals(4, t["wallpapers/01-a"]!!.size)
        assertEquals("甲", builtinDisplayName(t, wp("01-a.jpg"), "zh-CN"))
        assertEquals("乙", builtinDisplayName(t, wp("01-a.jpg"), "zh-TW"))
        assertEquals("A", builtinDisplayName(t, wp("01-a.jpg"), "en"))
    }

    @Test fun missingColumnsFallBackToSimplified() {
        val one = parseBuiltinNames("wallpapers/01-a = 甲")
        for (lang in BUILTIN_NAME_LANGS) assertEquals(lang, "甲", builtinDisplayName(one, wp("01-a.jpg"), lang))
        val two = parseBuiltinNames("wallpapers/01-a = 甲 | 乙")
        assertEquals("乙", builtinDisplayName(two, wp("01-a.jpg"), "zh-TW"))
        assertEquals("甲", builtinDisplayName(two, wp("01-a.jpg"), "en"))
    }

    @Test fun anEmptyValueOrAnEmptySimplifiedColumnFallsBackToTheFileLabel() {
        val t = parseBuiltinNames("wallpapers/01-a =\nwallpapers/02-b = | 乙 | B\n")
        assertEquals(listOf(""), t["wallpapers/01-a"])
        for (lang in BUILTIN_NAME_LANGS) assertEquals(lang, "a", builtinDisplayName(t, wp("01-a.jpg"), lang))
        // 简体那一列空着:别的语言照用自己那一列,简体界面退回文件名
        assertEquals("b", builtinDisplayName(t, wp("02-b.jpg"), "zh-CN"))
        assertEquals("乙", builtinDisplayName(t, wp("02-b.jpg"), "zh-TW"))
        assertEquals("B", builtinDisplayName(t, wp("02-b.jpg"), "en"))
    }

    @Test fun unknownOrOddlyCasedLanguageCodesUseTheSimplifiedColumn() {
        val t = parseBuiltinNames("wallpapers/01-a = 甲 | 乙 | A")
        for (lang in listOf("", "EN", "zh-cn", "zh_TW", "zh-Hant", "en-US", "fr")) {
            assertEquals("「$lang」", "甲", builtinDisplayName(t, wp("01-a.jpg"), lang))
        }
    }

    @Test fun anUnconventionalFileWithoutANameShowsItsWholeId() {
        assertEquals("WeTV", builtinDisplayName(emptyMap(), BuiltinImage(BuiltinKind.CARDS, "WeTV.png"), "en"))
        assertEquals("雨夜巴士站", builtinDisplayName(emptyMap(), wp("01-雨夜巴士站.jpg"), "en"))
    }

    // ---- 目录清单 ----

    @Test fun extensionCaseVariantsOfOneIdKeepTheFirstInStringOrder() {
        val warnings = mutableListOf<String>()
        val list = builtinCatalog(BuiltinKind.WALLPAPERS, listOf("01-a.jpg", "01-a.JPG"), warn = { warnings += it })
        // String 自然序里大写在前:留 01-a.JPG,01-a.jpg 当重名警告后丢掉
        assertEquals(listOf("01-a.JPG"), list.map { it.fileName })
        assertEquals(1, warnings.size)
    }

    @Test fun idsThatDifferOnlyInCaseAreDifferentImages() {
        val list = builtinCatalog(BuiltinKind.CARDS, listOf("01-a.png", "01-A.png"))
        assertEquals(listOf("01-A", "01-a"), list.map { it.id })
    }

    @Test fun theSameFileListedTwiceIsKeptOnce() {
        val warnings = mutableListOf<String>()
        val list = builtinCatalog(BuiltinKind.SCREENSAVERS, listOf("01-a.jpg", "01-a.jpg"), warn = { warnings += it })
        assertEquals(listOf("01-a.jpg"), list.map { it.fileName })
        assertEquals(1, warnings.size)
    }

    @Test fun namesWithoutAUsableStemOrExtensionAreSkipped() {
        val names = listOf("01-a.", ".jpg", ".JPG", "..jpg", "jpg", "01-b.gif", "01-c.jpg.txt", "01-d.heic")
        assertEquals(emptyList<BuiltinImage>(), builtinCatalog(BuiltinKind.WALLPAPERS, names))
    }

    @Test fun aDoubleExtensionKeepsTheInnerPartInTheId() {
        val list = builtinCatalog(BuiltinKind.WALLPAPERS, listOf("01-a.png.jpg"))
        assertEquals(listOf("01-a.png"), list.map { it.id })
    }

    // ---- 伪路径 ----

    @Test fun pseudoPathsWithTheWrongShapeAreNotBuiltin() {
        assertNull(builtinImageOf(File("/android_asset/builtin/wallpapers/")))
        assertNull(builtinImageOf(File("/android_asset/builtin/wallpapers")))
        assertNull(builtinImageOf(File("/android_asset/builtin/wallpapers/sub/01-a.jpg")))
        assertNull(builtinImageOf(File("/android_asset/builtin/WALLPAPERS/01-a.jpg")))
        assertNull(builtinAssetPathOf("android_asset/builtin/wallpapers/01-a.jpg"))
        assertNull(builtinAssetPathOf("/android_asset/builtinx/wallpapers/01-a.jpg"))
    }

    @Test fun everyKindRoundTripsThroughItsPseudoFile() {
        for (kind in BuiltinKind.entries) {
            val img = BuiltinImage(kind, "01-雨夜巴士站.jpg")
            assertEquals(img, builtinImageOf(img.file))
            assertTrue(builtinAssetPathOf(img.file.path)!!.startsWith("builtin/${kind.dir}/"))
        }
    }

    @Test fun everyNameTheCatalogAcceptsResolvesBackThroughItsPseudoPath() {
        // 清单「不合规的照样收」:收下的每一张都得能从伪路径认回来,否则网格里有这一格、却解不出图也选不中。
        // 手工改名打错的「05-极光..jpg」(连着两个点)就是这种:伪路径里有「..」,旧写法按子串拒掉了它
        val names = listOf("05-极光..jpg", "01-a..b.png", "WeTV.png", "01-雨夜 巴士.jpg", "01-a.b.webp", "02-dusk-city.jpeg")
        val list = builtinCatalog(BuiltinKind.WALLPAPERS, names)
        assertEquals(names.size, list.size)
        for (img in list) {
            assertEquals(img.fileName, "builtin/wallpapers/${img.fileName}", builtinAssetPathOf(img.file.path))
            assertEquals(img.fileName, img, builtinImageOf(img.file))
        }
    }

    @Test fun traversalSegmentsAreStillRejected() {
        assertNull(builtinAssetPathOf("/android_asset/builtin/../web/index.html"))
        assertNull(builtinAssetPathOf("/android_asset/builtin/wallpapers/../../web/x.jpg"))
        assertNull(builtinAssetPathOf("/android_asset/builtin/wallpapers/.."))
        assertNull(builtinImageOf(File("/android_asset/builtin/wallpapers/../cards/01-wetv.webp")))
    }
}
