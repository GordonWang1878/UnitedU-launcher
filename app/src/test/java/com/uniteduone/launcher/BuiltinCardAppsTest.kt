package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R159:内置卡片图只给对应的应用用(2026-10-01 Gordon:「不能允许把内置的爱奇艺卡片用到腾讯上」)。
 * 对应关系在 `assets/builtin/card-apps.txt`(见 BuiltinCatalog.kt 的 [BUILTIN_CARD_APPS_ASSET]);
 * 表里漏了某张卡,它在任何应用的选图页里都不出现——所以在这里让构建失败,报错信息写明要补哪几行。
 */
class BuiltinCardAppsTest {
    private val root = listOf(File("src/main/assets/builtin"), File("app/src/main/assets/builtin")).first { it.isDirectory }
    private val table by lazy { parseBuiltinCardApps(File(root, "card-apps.txt").readText()) }
    private fun cards(): List<BuiltinImage> =
        builtinCatalog(BuiltinKind.CARDS, File(root, BuiltinKind.CARDS.dir).list()?.toList().orEmpty())

    @Test fun `每张内置卡都至少对应一个应用`() {
        val missing = cards().filter { table[it.id].isNullOrEmpty() }.map { it.id }
        assertTrue(
            "这些内置卡没有对应的应用,请在 app/src/main/assets/builtin/card-apps.txt 补一行「ID = 包名, 包名」:\n  " +
                missing.joinToString("\n  "),
            missing.isEmpty(),
        )
    }

    @Test fun `对应表里没有已经不存在的卡`() {
        val stale = table.keys - cards().map { it.id }.toSet()
        assertTrue("card-apps.txt 里这些行对应的卡已经不在了(改名或删了?):\n  " + stale.joinToString("\n  "), stale.isEmpty())
    }

    @Test fun `一个包名只属于一张卡`() {
        val owners = table.flatMap { (id, pkgs) -> pkgs.map { it to id } }.groupBy({ it.first }, { it.second })
        val shared = owners.filterValues { it.size > 1 }
        assertTrue("这些包名同时写在几张卡下面:$shared", shared.isEmpty())
    }

    @Test fun `Gordon 电视上的七个应用各认得自己的卡`() {
        val expect = mapOf(
            "com.ktcp.video" to "01-wetv",
            "com.gitvdemo.video" to "02-iqiyi",
            "com.cibn.tv" to "03-youku",
            "com.xiaodianshi.tv.yst" to "04-bilibili",
            "cn.miguvideo.migutv" to "05-migu",
            "com.starcor.mango" to "06-mangotv",
            "com.google.android.youtube.tv" to "07-youtube",
        )
        for ((pkg, id) in expect) {
            assertEquals(pkg, listOf(id), builtinCardsFor(cards(), table, pkg).map { it.id })
        }
        // 同是腾讯的 QQ 音乐 TV 不是腾讯视频,不配腾讯卡
        assertEquals(emptyList<String>(), builtinCardsFor(cards(), table, "com.tencent.qqmusictv").map { it.id })
    }

    @Test fun `解析——注释、空行、首尾空白、BOM、逗号与空格、没有等号的行`() {
        val parsed = parseBuiltinCardApps(
            "﻿# 注释\n\n  01-a =  com.a.tv ,com.a.phone,,  \n02-b=com.b\n没有等号的行\n=没有键\n03-c =  \n",
        )
        assertEquals(
            mapOf("01-a" to setOf("com.a.tv", "com.a.phone"), "02-b" to setOf("com.b"), "03-c" to emptySet<String>()),
            parsed,
        )
    }

    @Test fun `筛选——只留属于这个包的卡、保持原顺序、表里没有的卡谁都不给`() {
        val a = BuiltinImage(BuiltinKind.CARDS, "01-a.png")
        val b = BuiltinImage(BuiltinKind.CARDS, "02-b.png")
        val c = BuiltinImage(BuiltinKind.CARDS, "03-c.png")
        val t = mapOf("01-a" to setOf("com.x"), "02-b" to setOf("com.y", "com.x"))
        assertEquals(listOf(a, b), builtinCardsFor(listOf(a, b, c), t, "com.x"))
        assertEquals(listOf(b), builtinCardsFor(listOf(a, b, c), t, "com.y"))
        assertEquals(emptyList<BuiltinImage>(), builtinCardsFor(listOf(a, b, c), t, "com.z"))
        assertEquals(emptyList<BuiltinImage>(), builtinCardsFor(listOf(a, b, c), emptyMap(), "com.x"))
    }
}
