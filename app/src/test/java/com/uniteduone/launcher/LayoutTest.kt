package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LayoutTest {
    private val rows = listOf(
        "VIDEO" to listOf("com.a", "com.b"),
        "LIVE" to listOf("com.b", "com.c"),
        "MUSIC" to listOf("com.b"),
    )

    @Test fun withoutPackageRemovesFromEveryRowAndKeepsOrder() {
        val next = Layout.withoutPackage(rows, "com.b")
        assertEquals(
            listOf("VIDEO" to listOf("com.a"), "LIVE" to listOf("com.c"), "MUSIC" to emptyList()),
            next,
        )
    }

    @Test fun withoutPackageReturnsSameInstanceWhenAbsent() {
        assertSame(rows, Layout.withoutPackage(rows, "com.zzz"))
    }
}
