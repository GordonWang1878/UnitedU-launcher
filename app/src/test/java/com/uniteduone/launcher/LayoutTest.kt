package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LayoutTest {
    private val rows = listOf(
        LayoutRow("VIDEO", apps = listOf("com.a", "com.b")),
        LayoutRow("LIVE", apps = listOf("com.b", "com.c")),
        LayoutRow("MUSIC", apps = listOf("com.b")),
    )

    @Test fun withoutPackageRemovesFromEveryRowAndKeepsOrder() {
        val next = Layout.withoutPackage(rows, "com.b")
        assertEquals(
            listOf(
                LayoutRow("VIDEO", apps = listOf("com.a")),
                LayoutRow("LIVE", apps = listOf("com.c")),
                LayoutRow("MUSIC", apps = emptyList()),
            ),
            next,
        )
    }

    @Test fun withoutPackageReturnsSameInstanceWhenAbsent() {
        assertSame(rows, Layout.withoutPackage(rows, "com.zzz"))
    }
}
