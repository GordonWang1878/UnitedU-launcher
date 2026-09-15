package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlesTest {
    @Test fun roundTripsUnicodeQuotesAndBackslashes() {
        val m = mapOf("com.a" to "爱奇艺 TV", "com.b" to "say \"hi\" \\ there", "com.c" to "x")
        assertEquals(m, parseTitles(titlesToJson(m)))
    }

    @Test fun malformedOrEmptyYieldsEmptyMap() {
        assertTrue(parseTitles("").isEmpty())
        assertTrue(parseTitles("{not json").isEmpty())
        assertTrue(parseTitles("[]").isEmpty())
        assertTrue(parseTitles("""{"com.a": 5}""").isEmpty())   // 非字符串值整条丢弃
    }

    @Test fun parseIgnoresEntriesWithBlankKeyOrTitle() {
        assertEquals(mapOf("com.a" to "A"), parseTitles("""{"": "x", "com.a": "A", "com.b": "   "}"""))
    }

    @Test fun sanitizeTrimsStripsControlAndCaps() {
        // NOTE(task-1 impl): brief's snippet for this first assertion was byte-corrupted
        // (raw file has `sanitizeTitle("  My` truncated mid-string-literal, no closing
        // quote/paren, jumping straight into the next @Test). Reconstructed from the test
        // name's intent (trim + strip control chars) and the verbatim-given sanitizeTitle
        // impl: filter drops code<0x20 (incl. '\t'=0x09) and 0x7F, then trim(). A tab
        // between "My" and " TV" plus outer padding exercises both trim and control-strip
        // while still yielding exactly "My TV". Flagged in task-1-report.md; not guessed silently.
        assertEquals("My TV", sanitizeTitle("  My\t TV  "))
        assertEquals("", sanitizeTitle(null))
        assertEquals("", sanitizeTitle("   "))
        assertEquals(MAX_TITLE_CHARS, sanitizeTitle("x".repeat(100)).length)
        assertEquals("看剧", sanitizeTitle("看剧"))
    }

    @Test fun emptyMapSerializesToEmptyObject() {
        assertEquals("{}\n", titlesToJson(emptyMap()))
    }

    @Test fun truncateNeverSplitsSurrogatePair() {
        val emoji = "\uD83D\uDE00"                                   // 😀:一个码点、两个 UTF-16 单元
        val straddle = "x".repeat(MAX_TITLE_CHARS - 1) + emoji       // 第 40/41 个单元正好是这一对
        val t = truncateTitle(straddle)
        assertEquals(MAX_TITLE_CHARS - 1, t.length)                  // 截到 39,而不是留半个 emoji 凑 40
        assertFalse(t.last().isSurrogate())
        assertEquals(t, sanitizeTitle(straddle))                     // 两条截断路径同一口径
        val inside = "x".repeat(MAX_TITLE_CHARS - 2) + emoji         // 第 39/40 个单元是这一对:完整保留
        assertEquals(inside, truncateTitle(inside))
        val bmp = "y".repeat(MAX_TITLE_CHARS)                        // 40 个基本平面字符:原样
        assertEquals(bmp, truncateTitle(bmp))
        assertEquals(bmp, sanitizeTitle(bmp))
    }
}
