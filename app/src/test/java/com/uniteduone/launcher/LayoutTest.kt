package com.uniteduone.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    // R163:行没有名字,测试里用图标 id 认行
    private val rows = listOf(
        LayoutRow("movie", apps = listOf("com.a", "com.b")),
        LayoutRow("tv", apps = listOf("com.b", "com.c")),
        LayoutRow("music", apps = listOf("com.b")),
    )

    @Test fun withoutPackageRemovesFromEveryRowAndKeepsOrder() {
        val next = Layout.withoutPackage(rows, "com.b")
        assertEquals(
            listOf(
                LayoutRow("movie", apps = listOf("com.a")),
                LayoutRow("tv", apps = listOf("com.c")),
                LayoutRow("music", apps = emptyList()),
            ),
            next,
        )
    }

    @Test fun withoutPackageReturnsSameInstanceWhenAbsent() {
        assertSame(rows, Layout.withoutPackage(rows, "com.zzz"))
    }

    // ---- R163:读盘兼容(老文件有 name)与落盘字段(不再写 name) ----
    // 用的是真的 org.json(build.gradle.kts 的 testImplementation),走的就是 Layout.read / write 里的 parse / toJson。

    /** 老文件(R163 之前):每行带 name、icon 可缺 → 读出来的图标按旧名字回落,外观与取消行名之前一致。 */
    @Test fun oldFileWithNamesAndNoIconsReadsBackAsMovieTvMusic() {
        val old = """
            {"rows":[
              {"name":"VIDEO","apps":["com.a","com.b"]},
              {"name":"LIVE","apps":["com.c"]},
              {"name":"MUSIC","apps":[]},
              {"name":"My Games","apps":["com.g"]}
            ]}
        """.trimIndent()
        val read = Layout.parse(old)
        assertEquals(listOf("movie", "tv", "music", "tv"), read.map { it.icon })   // 认得的三个名字照旧;其它名字一律 tv
        assertEquals(listOf(listOf("com.a", "com.b"), listOf("com.c"), emptyList(), listOf("com.g")), read.map { it.apps })
    }

    /** 老文件里的小写名字 / 中文名字(R133 默认行名跟界面语言写成「影视」等,那时同时写了 icon)也都能读。 */
    @Test fun oldFileNamesAreMatchedCaseInsensitivelyAndLocalizedNamesWithIconKeepTheirIcon() {
        val read = Layout.parse(
            """{"rows":[{"name":"video","apps":[]},{"name":"影视","icon":"movie","apps":[]},{"name":"直播","icon":"tv","apps":[]},{"name":"音乐","icon":"music","apps":[]}]}""",
        )
        assertEquals(listOf("movie", "movie", "tv", "music"), read.map { it.icon })
    }

    /** 老文件里存了合法 icon:不看名字;存了非法 icon:同样按名字回落(手改坏的文件不崩、不画垃圾)。 */
    @Test fun storedValidIconWinsOverTheOldNameAndAnInvalidOneFallsBackToIt() {
        val read = Layout.parse(
            """{"rows":[{"name":"VIDEO","icon":"games","apps":[]},{"name":"MUSIC","icon":"bogus","apps":[]},{"name":"LIVE","icon":"","apps":[]}]}""",
        )
        assertEquals(listOf("games", "music", "tv"), read.map { it.icon })
    }

    /**
     * 手改坏的文件:name / icon 是数字(不是字符串)——org.json 的 `optString` 会转成字符串,照常读、不崩;
     * 图标 7 不是合法 id → 按(转成字符串的)旧名字回落 tv;icon 是 null → 没有旧名字可回落 → 默认图标。
     */
    @Test fun numericOrNullNameAndIconFieldsDoNotCrashTheReader() {
        val read = Layout.parse("""{"rows":[{"name":42,"icon":7,"apps":["a"]},{"icon":null,"apps":[]}]}""")
        assertEquals(listOf("tv", NEW_ROW_ICON), read.map { it.icon })
        assertEquals(listOf(listOf("a"), emptyList<String>()), read.map { it.apps })
    }

    /** 新文件没有 name:读得出来;icon 缺失 / 非法 → 默认图标(新建行用的那个)。 */
    @Test fun newFileWithoutNamesReadsAndMissingOrInvalidIconsBecomeTheDefault() {
        val read = Layout.parse("""{"rows":[{"icon":"kids","apps":["com.a"]},{"icon":"nonsense","apps":[]},{"apps":["com.b"]}]}""")
        assertEquals(listOf("kids", NEW_ROW_ICON, NEW_ROW_ICON), read.map { it.icon })
        assertTrue(read.all { isRowIconId(it.icon) })   // 读出来的行图标一定合法(LayoutRow.icon 的不变量)
    }

    @Test fun readTrimsDedupesAndDropsBlankPackagesWithinARow() {
        val read = Layout.parse("""{"rows":[{"icon":"movie","apps":["  com.a  ","com.b","com.a","","   "]},{"icon":"tv","apps":["com.a"]}]}""")
        assertEquals(listOf("com.a", "com.b"), read[0].apps)   // 同一行里重复的包去重;别的行里可以有同一个
        assertEquals(listOf("com.a"), read[1].apps)
    }

    /** 坏文件照旧抛(交给 LockedFile.load 当损坏处理、回落 .prev / 默认):零行、缺 apps、缺 rows、不是 JSON。 */
    @Test fun malformedFilesStillThrow() {
        assertThrows(Exception::class.java) { Layout.parse("""{"rows":[]}""") }
        assertThrows(Exception::class.java) { Layout.parse("""{"rows":[{"icon":"movie"}]}""") }
        assertThrows(Exception::class.java) { Layout.parse("""{"nothing":1}""") }
        assertThrows(Exception::class.java) { Layout.parse("not json") }
        assertThrows(Exception::class.java) { Layout.parse("") }
    }

    /** 写盘:每行只有 icon + apps,**没有 name 字段**(R163)。 */
    @Test fun writtenFileHasIconAndAppsOnlyNeverAName() {
        val text = Layout.toJson(rows)
        val arr = JSONObject(text).getJSONArray("rows")
        assertEquals(3, arr.length())
        for (i in 0 until arr.length()) {
            val keys = arr.getJSONObject(i).keys().asSequence().toSet()
            assertEquals("第 $i 行的字段", setOf("icon", "apps"), keys)
        }
        assertFalse(text.contains("\"name\""))
        assertEquals("movie", arr.getJSONObject(0).getString("icon"))
        assertEquals(listOf("com.a", "com.b"), (0 until 2).map { arr.getJSONObject(0).getJSONArray("apps").getString(it) })
    }

    @Test fun writeThenReadKeepsIconsAppsAndRowOrder() {
        assertEquals(rows, Layout.parse(Layout.toJson(rows)))
        // 空行(没有应用)与默认图标也原样往返
        val withEmpty = rows + LayoutRow(NEW_ROW_ICON)
        assertEquals(withEmpty, Layout.parse(Layout.toJson(withEmpty)))
    }

    /** 老文件读进来再写回去:name 没了,图标是读盘时回落出来的那几个——之后再怎么读都是同一个样子。 */
    @Test fun anOldFileRewrittenLosesItsNamesButKeepsItsLook() {
        val old = """{"rows":[{"name":"VIDEO","apps":["a"]},{"name":"LIVE","apps":["b"]},{"name":"MUSIC","apps":[]}]}"""
        val written = Layout.toJson(Layout.parse(old))
        assertFalse(written.contains("\"name\""))
        assertEquals(listOf("movie", "tv", "music"), Layout.parse(written).map { it.icon })
        assertEquals(listOf(listOf("a"), listOf("b"), emptyList()), Layout.parse(written).map { it.apps })
    }

    @Test fun defaultLayoutSurvivesTheRoundTripAndHasNoNames() {
        assertEquals(DEFAULT_LAYOUT, Layout.parse(Layout.toJson(DEFAULT_LAYOUT)))
        assertFalse(Layout.toJson(DEFAULT_LAYOUT).contains("\"name\""))
    }
}
