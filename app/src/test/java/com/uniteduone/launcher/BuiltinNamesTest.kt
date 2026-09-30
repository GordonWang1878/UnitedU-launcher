package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R137:内置图的三语名字表 `assets/builtin/names.txt`(见 BuiltinCatalog.kt 的 [BUILTIN_NAMES_ASSET])。
 * Gordon 会直接往 assets/builtin/<分类>/ 里放图(builtin-assets.md);名字表漏补时图照样显示(退回简体 / 文件名),
 * 英文与繁体界面上就又冒出简体中文——所以在这里让构建失败,报错信息写明要补哪几行。
 */
class BuiltinNamesTest {
    private val root = listOf(File("src/main/assets/builtin"), File("app/src/main/assets/builtin")).first { it.isDirectory }
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val names by lazy { parseBuiltinNames(File(root, "names.txt").readText()) }

    private fun images(): List<BuiltinImage> = BuiltinKind.entries.flatMap { k ->
        builtinCatalog(k, File(root, k.dir).list()?.toList().orEmpty())
    }

    @Test fun `每张内置图都有三语名字`() {
        val missing = images().filter { img ->
            val cols = names["${img.kind.dir}/${img.id}"]
            cols == null || cols.size < BUILTIN_NAME_LANGS.size || cols.take(BUILTIN_NAME_LANGS.size).any { it.isEmpty() }
        }.map { "${it.kind.dir}/${it.id}" }
        assertTrue(
            "这些内置图缺三语名字,请在 app/src/main/assets/builtin/names.txt 补一行「分类/ID = 简体 | 繁體 | English」:\n  " +
                missing.joinToString("\n  "),
            missing.isEmpty(),
        )
    }

    @Test fun `名字表里没有已经不存在的图`() {
        val ids = images().map { "${it.kind.dir}/${it.id}" }.toSet()
        val stale = names.keys - ids
        assertTrue("names.txt 里这些行对应的图已经不在了(改名或删了?):\n  " + stale.joinToString("\n  "), stale.isEmpty())
    }

    @Test fun `三份 strings 的 builtin_names_lang 正好是名字表的三列`() {
        fun lang(dir: String) = Regex("""<string name="builtin_names_lang">([^<]*)</string>""")
            .find(File(res, "$dir/strings.xml").readText())?.groupValues?.get(1)
        assertEquals("zh-CN", lang("values"))
        assertEquals("zh-TW", lang("values-zh-rTW"))
        assertEquals("en", lang("values-en"))
        assertEquals(listOf("zh-CN", "zh-TW", "en"), BUILTIN_NAME_LANGS)
    }

    @Test fun `解析——注释、空行、首尾空白、BOM、没有等号的行`() {
        val parsed = parseBuiltinNames(
            "\uFEFF# 注释\n\n  wallpapers/01-a =  甲 | 乙 |  A  \nscreensavers/02-b=丙\n没有等号的行\n=没有键\n",
        )
        assertEquals(mapOf("wallpapers/01-a" to listOf("甲", "乙", "A"), "screensavers/02-b" to listOf("丙")), parsed)
    }

    @Test fun `取名——按语言取列,缺列退回简体,缺行退回文件名`() {
        val table = parseBuiltinNames("wallpapers/01-a = 甲 | 乙 | A\nwallpapers/02-b = 丙 |  | \n")
        val a = BuiltinImage(BuiltinKind.WALLPAPERS, "01-a.jpg")
        val b = BuiltinImage(BuiltinKind.WALLPAPERS, "02-b.jpg")
        val c = BuiltinImage(BuiltinKind.WALLPAPERS, "03-c.jpg")
        assertEquals("甲", builtinDisplayName(table, a, "zh-CN"))
        assertEquals("乙", builtinDisplayName(table, a, "zh-TW"))
        assertEquals("A", builtinDisplayName(table, a, "en"))
        assertEquals("甲", builtinDisplayName(table, a, "ja"))   // 认不出的语言按简体那一列
        assertEquals("丙", builtinDisplayName(table, b, "en"))   // 那一列空着 → 简体
        assertEquals("c", builtinDisplayName(table, c, "en"))    // 表里没有 → 去掉序号的文件名
        // 同一个 ID 在别的分类下不算数
        assertEquals("a", builtinDisplayName(table, BuiltinImage(BuiltinKind.CARDS, "01-a.png"), "en"))
    }
}
