package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新的边界(UpdateCheckerTest 之外):版本号卡在 Int 上限与溢出、minSdk 的非正数 / 溢出、
 * notes 恰好 200 个单元、\u 转义拼出的代理对、版本比较的极值、身份核对的先后、下载百分比的取整。
 */
class UpdateCheckerBoundaryTest {

    private val hex = "0123456789abcdef".repeat(4)
    private val https = "https://example.com/unitedu-1.0.1.apk"

    private fun latest(
        versionCode: String = "3",
        notes: String? = "\"修复若干问题\"",
        minSdk: String? = "28",
    ): String = buildList {
        add("\"versionCode\": $versionCode")
        add("\"versionName\": \"1.0.1\"")
        if (notes != null) add("\"notes\": $notes")
        add("\"apkUrl\": \"$https\"")
        add("\"sha256\": \"$hex\"")
        if (minSdk != null) add("\"minSdk\": $minSdk")
    }.joinToString(",\n  ", prefix = "{\n  ", postfix = "\n}\n")

    // ---- 版本号 ----

    @Test fun versionCodeAtIntMaxIsAcceptedAndOneMoreIsRejected() {
        assertEquals(Int.MAX_VALUE, parseLatest(latest(versionCode = "2147483647"))?.versionCode)
        assertNull(parseLatest(latest(versionCode = "2147483648")))
        assertNull(parseLatest(latest(versionCode = "99999999999999999999")))
    }

    @Test fun versionCodeMustBeAPlainInteger() {
        assertEquals(1, parseLatest(latest(versionCode = "1"))?.versionCode)
        assertNull(parseLatest(latest(versionCode = "1e3")))
        assertNull(parseLatest(latest(versionCode = "0x10")))
        assertNull(parseLatest(latest(versionCode = "null")))
        assertNull(parseLatest(latest(versionCode = "true")))
    }

    @Test fun nonPositiveMinSdkMeansNoLimitButAnOverflowRejectsTheFile() {
        assertEquals(1, parseLatest(latest(minSdk = "0"))?.minSdk)
        assertEquals(1, parseLatest(latest(minSdk = "-5"))?.minSdk)
        assertEquals(99, parseLatest(latest(minSdk = "99"))?.minSdk)
        assertNull(parseLatest(latest(minSdk = "2147483648")))
        assertNull(parseLatest(latest(minSdk = "28.0")))
    }

    // ---- notes ----

    @Test fun notesOfExactlyTwoHundredUnitsEndingInAnEmojiAreKeptWhole() {
        val exact = "a".repeat(198) + "😀"
        assertEquals(exact, parseLatest(latest(notes = "\"$exact\""))?.notes)
        val one = "a".repeat(200)
        assertEquals(one, parseLatest(latest(notes = "\"$one\""))?.notes)
        assertEquals(one, parseLatest(latest(notes = "\"${one}b\""))?.notes)
    }

    @Test fun escapedSurrogatePairsDecodeToOneCharacter() {
        assertEquals("海😀", parseLatest(latest(notes = "\"\\u6d77\\ud83d\\ude00\""))?.notes)
        assertEquals("", parseLatest(latest(notes = "\"\""))?.notes)
    }

    @Test fun aBadEscapeInTheOptionalNotesDropsOnlyTheNotes() {
        // notes 是可选字段:转义坏了按缺失处理(空串),整份照样能用
        val info = parseLatest(latest(notes = "\"坏的 \\x 转义\""))
        assertEquals("", info?.notes)
        assertEquals(3, info?.versionCode)
    }

    // ---- 版本比较 ----

    @Test fun isNewerAtTheExtremes() {
        val max = LatestInfo(Int.MAX_VALUE, "x", "", https, hex, 1)
        assertTrue(isNewer(max, currentCode = Int.MAX_VALUE - 1, sdk = 1))
        assertFalse(isNewer(max, currentCode = Int.MAX_VALUE, sdk = 1))
        assertTrue(isNewer(max.copy(versionCode = 1), currentCode = 0, sdk = 1))
        assertTrue(isNewer(max.copy(versionCode = 1), currentCode = Int.MIN_VALUE, sdk = 1))
    }

    @Test fun isNewerSdkBoundaryIsInclusive() {
        val v = LatestInfo(5, "x", "", https, hex, 34)
        assertTrue(isNewer(v, currentCode = 4, sdk = 34))
        assertFalse(isNewer(v, currentCode = 4, sdk = 33))
        assertFalse(isNewer(v, currentCode = 4, sdk = 0))
    }

    // ---- 身份核对的先后 ----

    @Test fun theFirstFailingCheckIsReportedInOrder() {
        val installed = ApkIdentity("p", 2, setOf("k"))
        // 包名与版本号都不对:报包名
        assertEquals(UpdateRejection.WRONG_PACKAGE, checkUpdateApk(ApkIdentity("q", 9, emptySet()), installed, 3))
        // 版本号不对又没签名:报版本号
        assertEquals(UpdateRejection.WRONG_VERSION, checkUpdateApk(ApkIdentity("p", 9, emptySet()), installed, 3))
        // 不比已装的新又没签名:报不够新
        assertEquals(UpdateRejection.NOT_NEWER, checkUpdateApk(ApkIdentity("p", 2, emptySet()), installed, 2))
    }

    @Test fun installedVersionAboveIntRangeIsNeverDowngraded() {
        val installed = ApkIdentity("p", Int.MAX_VALUE.toLong() + 1, setOf("k"))
        assertEquals(UpdateRejection.NOT_NEWER, checkUpdateApk(ApkIdentity("p", Int.MAX_VALUE.toLong(), setOf("k")), installed, Int.MAX_VALUE))
    }

    // ---- 下载百分比 ----

    @Test fun percentRoundsDownAndOnlyShowsHundredWhenDone() {
        assertEquals(99, downloadPercent(done = 999, total = 1000))
        assertEquals(0, downloadPercent(done = 9, total = 1000))
        assertEquals(1, downloadPercent(done = 10, total = 1000))
        assertEquals(100, downloadPercent(done = 1, total = 1))
        assertEquals(0, downloadPercent(done = -5, total = 1000))
        assertNull(downloadPercent(done = 0, total = Long.MIN_VALUE))
    }

    @Test fun percentOfAHundredMegabyteApkDoesNotOverflow() {
        val total = 100L * 1024 * 1024
        assertEquals(50, downloadPercent(done = total / 2, total = total))
        assertEquals(100, downloadPercent(done = total, total = total))
    }

    // ---- 通道地址 ----

    @Test fun moreRejectedUpdateUrls() {
        listOf(
            "https://exa mple.com/latest.json",
            "https://example.com\\@evil.com/latest.json",
            "https://example.com/latest.json\n",
            "https://example.com/\tlatest.json",
            "http://127.0.0.1:123456/latest.json",
            "http://127.0.0.1:８０８０/latest.json",
            "http://[::1]:8080/latest.json",
            "https:/example.com/latest.json",
        ).forEach { assertFalse(it, isAllowedUpdateUrl(it)) }
    }

    @Test fun commaOnlyOrBlankUrlListsAreEmpty() {
        assertEquals(emptyList<String>(), parseUpdateUrls(",,,"))
        assertEquals(emptyList<String>(), parseUpdateUrls(" , \t ,\n"))
    }
}
