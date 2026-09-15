package com.uniteduone.launcher

import org.junit.Assert.assertEquals
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
}
