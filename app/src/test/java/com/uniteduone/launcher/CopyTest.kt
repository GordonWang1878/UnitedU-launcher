package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R130–R133(2026-09-30,新手可读性)文案线的防线。都是纯 JVM:直接读 res / assets 源文件,不起 Android。
 * - 三种语言的 strings.xml 同一套 key、同一个 key 的格式占位符一致(占位符对不上,切到那种语言 getString 当场崩);
 * - 手机网页 index.html 用到的每个 `S.xxx` 都在服务端注入的表里(漏了网页上就写着 undefined);
 * - 设置外壳每一行都有说明(左侧说明区空着,第一次用的人就只剩一个标签可猜)。
 */
class CopyTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    private val langs = listOf("values", "values-en", "values-zh-rTW")

    /** name → 这一条的全部文字(plurals 把各个 item 拼在一起),按语言。 */
    private fun strings(dir: String): Map<String, String> {
        val xml = File(res, "$dir/strings.xml").readText()
        val out = LinkedHashMap<String, String>()
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .forEach { out[it.groupValues[1]] = it.groupValues[2] }
        Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .forEach { out[it.groupValues[1]] = it.groupValues[2] }
        return out
    }

    private fun placeholders(text: String): Set<String> =
        Regex("""%(\d+\$)?[sd]""").findAll(text).map { it.value }.toSet()

    @Test fun everyLanguageHasTheSameKeys() {
        val base = strings(langs[0]).keys
        for (l in langs.drop(1)) {
            val keys = strings(l).keys
            assertEquals("$l 缺:${base - keys} 多:${keys - base}", base, keys)
        }
    }

    /** 每条 plurals 三种语言都给了 one 与 other(R140 复审:有的 ROM 对中文也取 one,缺了就落空)。 */
    @Test fun pluralsHaveOneAndOtherInEveryLanguage() {
        for (l in langs) {
            val xml = File(res, "$l/strings.xml").readText()
            Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
                for (q in listOf("one", "other")) {
                    assertTrue("$l 的 plurals ${m.groupValues[1]} 缺 $q", m.groupValues[2].contains("quantity=\"$q\""))
                }
            }
        }
    }

    /** 中文里与汉字相邻的标点一律全角(R132);半角的 , : ; ? ! ( ) 紧挨着汉字就算漏网(文件扩展名里的点不算)。 */
    @Test fun chinesePunctuationIsFullWidth() {
        val bad = Regex("""[\u4e00-\u9fff][,:;?!()]|[,:;?!()][\u4e00-\u9fff]""")
        // R155(Gordon 定「保留原样」):括着纯英文的半角括号可以挨着汉字,如「主页(Home)键」。
        val asciiParen = Regex("""\([A-Za-z0-9 ]+\)""")
        for (l in listOf("values", "values-zh-rTW")) {
            val offenders = strings(l).filter { (_, v) -> bad.containsMatchIn(asciiParen.replace(v, "A")) }.map { (k, v) -> "$k = $v" }
            assertTrue("$l 有半角标点挨着汉字:\n" + offenders.joinToString("\n"), offenders.isEmpty())
        }
    }

    @Test fun placeholdersMatchAcrossLanguages() {
        val all = langs.associateWith { strings(it) }
        for ((name, zh) in all.getValue("values")) {
            for (l in langs.drop(1)) {
                val other = all.getValue(l)[name] ?: continue
                assertEquals("$name 在 $l 的占位符与简体不一致", placeholders(zh), placeholders(other))
            }
        }
    }

    @Test fun webPageOnlyUsesInjectedStrings() {
        val html = File(assets, "web/index.html").readText()
        val known = WEB_STRING_KEYS.keys + WEB_PLURALS.keys.flatMap { listOf("${it}_one", "${it}_other") }
        val used = Regex("""\bS\.([a-z_]+)""").findAll(html).map { it.groupValues[1] }.toSet()
        assertTrue("网页用到了没注入的文案:${used - known}", known.containsAll(used))
        // 拼出来的 key:各分类的说明、各种拒收理由、APK 的各种结局
        for (t in LIBRARY_TYPES) assertTrue("desc_$t", "desc_$t" in known)
        for (r in listOf("type", "size", "video_size", "decode", "video_decode", "name", "write", "space", "type_media")) {
            assertTrue("rejected_$r", "rejected_$r" in known)
        }
        for (r in listOf("needs-permission", "invalid", "size", "background", "server", "write")) {
            assertTrue("apk_$r", "apk_$r" in known)
        }
        // 服务端注入的四个占位都还在网页里
        for (p in listOf("__STRINGS__", "__DEFAULT_TAB__", "__ACCENT__", "__LANG__")) assertTrue(p, p in html)
    }

    @Test fun everySettingsRowExplainsItself() {
        // 让所有条件行都出现:动画缩放读不到 → 「系统动画速度」行出现
        val g = settingsGroups(Settings(), {}, SettingsActions(
            pickWallpaper = {}, openImport = {}, setDefaultHome = {}, applyLanguage = {},
            openScreensaverGallery = {}, openSystemScreensaver = {}, openSystemAnimationSettings = {},
        ), screensaverImages = 3, system = SystemUiStatus.UNKNOWN)
        val rows = allRows(g)
        assertTrue(rows.any { it.id == "systemAnimationScale" })
        for (r in rows) assertNotNull("${r.id} 没有说明(R131)", r.descRes)
    }

    @Test fun addressShownWithoutScheme() {
        assertEquals("192.168.1.22:8090", displayAddress("http://192.168.1.22:8090/"))
        assertEquals("10.0.2.15:8091", displayAddress("http://10.0.2.15:8091"))
    }

    @Test fun accentAsCssHex() {
        assertEquals("#C5B6DF", cssHex(0xFFC5B6DF.toInt()))
        assertEquals("#00000A", cssHex(0x0000000A))   // 透明度丢掉、补齐 6 位
        assertEquals("#C5B6DF", cssHex(DEFAULT_WEB_ACCENT))
    }

    @Test fun defaultHomeValue() {
        assertEquals(emptyList<HintPart>(), defaultHomeSummary(null))
        assertEquals(listOf(HintPart.Res(R.string.settings_home_not_set)), defaultHomeSummary(DefaultHome.NotSet))
        assertEquals(listOf(HintPart.Text("UnitedU")), defaultHomeSummary(DefaultHome.App("UnitedU")))
    }

    /**
     * R163:行没有名字——写进 layout.json 的内置三行不再带名字,界面里也没有地方显示行名,所以每种语言里都不再有
     * 「内置行名 / 重命名此行 / 新行」这几条文案;「加到桌面…」第二层的药丸与几条提示不再带行名占位。
     */
    @Test fun rowsHaveNoNamesAnyMore() {
        val gone = listOf(
            "row_default_video", "row_default_live", "row_default_music",
            "edit_row_rename", "edit_row_rename_desc", "edit_row_rename_heading", "edit_row_rename_hint", "edit_new_row_name",
        )
        for (l in langs) {
            val s = strings(l)
            for (k in gone) assertTrue("$l 里还有 $k(R163 起行没有名字)", k !in s)
            // 这几条原来带 %1$s = 行名;现在一个占位都不带
            for (k in listOf("edit_row_delete_confirm_title", "apps_add_row_here", "toast_added_to_row", "toast_already_in_row")) {
                assertEquals("$l 的 $k 不该再带占位符", emptySet<String>(), placeholders(s.getValue(k)))
            }
            // 「加到桌面…」药丸小字:应用名 + 个数(两个占位,三种语言一致由 placeholdersMatchAcrossLanguages 保证)
            assertEquals(setOf("%1\$s", "%2\$d"), placeholders(s.getValue("apps_row_names_more")))
        }
    }

    /** R165 货架的新文案:三种语言都有;两条带行数上限的、两条 plurals 都带 %1$d。 */
    @Test fun editShelfStringsExist() {
        val keys = listOf(
            "edit_summary_rows", "edit_apps_count", "edit_key_ok", "edit_key_back",
            "edit_hint_pick", "edit_hint_row", "edit_hint_done", "edit_hint_choose", "edit_hint_add",
            "edit_hint_move", "edit_hint_drop", "edit_hint_cancel",
            "edit_chip_icon", "edit_chip_up", "edit_chip_down", "edit_chip_delete",
            "edit_new_row", "edit_choice_app_row", "edit_choice_app_row_desc", "edit_choice_full", "edit_new_row_caption",
        )
        for (l in langs) {
            val s = strings(l)
            for (k in keys) assertNotNull("$l 缺 $k(R165)", s[k])
            for (k in listOf("edit_choice_full", "edit_new_row_caption", "edit_summary_rows", "edit_apps_count")) {
                assertEquals("$l 的 $k", setOf("%1\$d"), placeholders(s.getValue(k)))
            }
        }
    }

    /** R165:行菜单与行尾「+」取消,它们的文案(以及旧的整段说明、「移动位置」说明)三种语言都删掉。 */
    @Test fun rowMenuStringsAreGone() {
        val gone = listOf(
            "edit_hint", "edit_row_add_app_desc", "edit_row_icon", "edit_row_icon_desc",
            "edit_row_up", "edit_row_up_desc", "edit_row_down", "edit_row_down_desc",
            "edit_row_new", "edit_row_new_desc", "edit_row_delete", "edit_row_delete_desc",
            "edit_row_menu_title", "edit_move_desc",
        )
        for (l in langs) {
            val s = strings(l)
            for (k in gone) assertTrue("$l 里还有 $k(R165 行菜单取消)", k !in s)
        }
    }
}
