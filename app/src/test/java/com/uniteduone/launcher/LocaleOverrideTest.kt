package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class LocaleOverrideTest {

    @Test fun localeMapping() {
        assertNull(localeFor("system"))
        assertNull(localeFor("xx"))
        assertEquals(Locale.SIMPLIFIED_CHINESE, localeFor("zh-CN"))
        assertEquals(Locale.TRADITIONAL_CHINESE, localeFor("zh-TW"))
        assertEquals(Locale.ENGLISH, localeFor("en"))
    }
}
