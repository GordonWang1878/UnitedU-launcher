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

    @Test fun defaultRowsTakeInterfaceLanguageAndKeepIcons() {
        val names = mapOf("VIDEO" to "影视", "LIVE" to "直播", "MUSIC" to "音乐")
        val rows = localizedDefaultRows(DEFAULT_LAYOUT, names)
        assertEquals(listOf("影视", "直播", "音乐"), rows.map { it.name })
        // 名字换了,图标照旧是按英文名推出来的那一个,而且写死在行上
        assertEquals(listOf("movie", "tv", "music"), rows.map { it.icon })
        assertEquals(DEFAULT_LAYOUT.map { it.apps }, rows.map { it.apps })
        // 用户自己的行不动
        val mine = LayoutRow(name = "我的", icon = "games", apps = listOf("a"))
        assertEquals(listOf(mine), localizedDefaultRows(listOf(mine), names))
    }
}
