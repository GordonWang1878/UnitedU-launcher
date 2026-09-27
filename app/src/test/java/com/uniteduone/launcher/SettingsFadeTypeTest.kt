package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R108(设置类页面淡入淡出)与 R109(设置类页面字号小一号)的纯逻辑:常量、字号换算、[FadeBook] 账本、
 * 外壳预览进出的 [previewMotion] / [homeLayerAlpha]。
 *
 * **覆盖不到的**(如实记录):不渲染 Compose——`FadeSwitch` 的残影「不可聚焦 / 不收返回键」、首页那一层的缩放,
 * 只能装机验(模拟器实测见 spec R108 一节:淡出期间按键全部落在首页 / 活着的那一层)。
 */
class SettingsFadeTypeTest {
    // ---------- R109 字号 ----------

    @Test fun `R109 只有一个数,SETTINGS_TYPE_STEP = -1`() {
        assertEquals(-1f, GtvLayout.SETTINGS_TYPE_STEP, 0f)
    }

    @Test fun `R109 设置类页面字号 = 基准 + step`() {
        assertEquals("左栏页名 32 → 31", 31f, GtvLayout.settingsSp(GtvLayout.SETTINGS_TITLE_TEXT), 0f)
        assertEquals("左栏路径 16 → 15", 15f, GtvLayout.settingsSp(16f), 0f)
        assertEquals("左栏说明 14 → 13", 13f, GtvLayout.settingsSp(14f), 0f)
        // 关于页 15 / 14 / 13 / 12 / 11 → 14 / 13 / 12 / 11 / 10
        assertEquals(listOf(14f, 13f, 12f, 11f, 10f), listOf(15f, 14f, 13f, 12f, 11f).map(GtvLayout::settingsSp))
        // 默认桌面卡:名字 15 → 14、「当前」11 → 10(CurrentHomeRow 的 textStep)
        assertEquals(14f, 15f + GtvLayout.SETTINGS_TYPE_STEP, 0f)
        assertEquals(10f, 11f + GtvLayout.SETTINGS_TYPE_STEP, 0f)
    }

    @Test fun `R109 设置页胶囊,标签 15、说明 11、右端 › 按比例`() {
        val t = PillType(GtvLayout.SETTINGS_TYPE_STEP)
        assertEquals(15f, t.text, 0f)
        assertEquals(11f, t.hint, 0f)
        assertEquals(15f * 1.25f, t.chevron, 1e-4f)
    }

    @Test fun `R109 长按 - 编辑页菜单不传 step,与改前逐位相同(16 - 12 - 20)`() {
        val t = PillType()
        assertEquals(GtvLayout.MENU_ITEM_TEXT, t.text, 0f)
        assertEquals(16f, t.text, 0f)
        assertEquals(12f, t.hint, 0f)
        assertEquals("R71 起 › = 标签 + 4 = 20", 20f, t.chevron, 0f)
        assertEquals("长按菜单左上的页名仍读基准 32", 32f, GtvLayout.SETTINGS_TITLE_TEXT, 0f)
    }

    // ---------- R108 时长 ----------

    @Test fun `R108 打开 200 ms、关闭 150 ms、层与层 150 ms`() {
        assertEquals(200, GtvLayout.SETTINGS_FADE_IN_MS)
        assertEquals(150, GtvLayout.SETTINGS_FADE_OUT_MS)
        assertEquals(150, GtvLayout.SETTINGS_LAYER_FADE_MS)
    }

    // ---------- FadeBook ----------

    @Test fun `打开 = 新开一项,活着`() {
        val b = FadeBook<String>()
        assertTrue(b.sync("A", 1))
        assertEquals(1, b.entries.size)
        assertTrue(b.entries[0].live)
        assertEquals("A", b.entries[0].state)
    }

    @Test fun `同一个 key = 同一页,原地更新输入,不新开`() {
        val b = FadeBook<String>()
        b.sync("A", 1)
        assertFalse(b.sync("A'", 1))
        assertEquals(1, b.entries.size)
        assertEquals("A'", b.live!!.state)
    }

    @Test fun `关掉 = 活着的那项变残影,输入冻结在最后一份`() {
        val b = FadeBook<String>()
        b.sync("A", 1)
        b.sync("A2", 1)
        assertFalse(b.sync(null, null))
        assertNull(b.live)
        assertEquals(1, b.entries.size)
        assertFalse(b.entries[0].live)
        assertEquals("残影画的是关掉前最后那一份", "A2", b.entries[0].state)
        // 残影不再跟着输入走:再次 sync(null) 不改它
        b.sync(null, null)
        assertEquals("A2", b.entries[0].state)
    }

    @Test fun `换页 = 旧的变残影、新开一项(交叉淡化),新的一项在最上面`() {
        val b = FadeBook<String>()
        b.sync("root", "root")
        assertTrue(b.sync("layout", "layout"))
        assertEquals(listOf(false, true), b.entries.map { it.live })
        assertEquals("root", b.entries[0].state)
        assertEquals("layout", b.entries.last().state)
    }

    @Test fun `关掉又马上打开(残影还在)= 全新的一项,不复活残影`() {
        val b = FadeBook<String>()
        b.sync("A", 1)
        b.sync(null, null)
        val ghostGen = b.entries[0].gen
        assertTrue(b.sync("B", 1))
        assertEquals(2, b.entries.size)
        assertFalse(b.entries[0].live)
        assertTrue(b.entries[1].live)
        assertTrue("新的一项 gen 不同(组合 key 不同,焦点从头落)", b.entries[1].gen != ghostGen)
        assertEquals("B", b.entries[1].state)
    }

    @Test fun `remove 只拿掉残影,不会拿掉活着的那项`() {
        val b = FadeBook<String>()
        b.sync("A", 1)
        val liveGen = b.entries[0].gen
        assertFalse(b.remove(liveGen))
        assertEquals(1, b.entries.size)
        b.sync("B", 2)
        assertTrue(b.remove(liveGen))
        assertEquals(listOf("B"), b.entries.map { it.state })
    }

    @Test fun `快速进出,层层残影各自独立,gen 只增不减`() {
        val b = FadeBook<String>()
        b.sync("root", "root"); b.sync("layout", "layout"); b.sync("root", "root")
        assertEquals(3, b.entries.size)
        assertEquals(listOf(false, false, true), b.entries.map { it.live })
        assertEquals(b.entries.map { it.gen }.sorted(), b.entries.map { it.gen })
        assertEquals(3, b.entries.map { it.gen }.toSet().size)
    }

    // ---------- 首页那一层(预览 R73)----------

    @Test fun `homeLayerAlpha 两端与预览`() {
        assertEquals("外壳关着:首页全不透明", 1f, homeLayerAlpha(0f, 0f), 0f)
        assertEquals("外壳关着(v 冻在 1)", 1f, homeLayerAlpha(0f, 1f), 0f)
        assertEquals("外壳开在没有预览的页:首页全透明", 0f, homeLayerAlpha(1f, 0f), 0f)
        assertEquals("外壳开在有预览的页:首页就是预览本身", 1f, homeLayerAlpha(1f, 1f), 0f)
        assertEquals("从第一层淡入一半", 0.5f, homeLayerAlpha(0.5f, 0f), 1e-6f)
        for (a in listOf(0f, 0.3f, 0.7f, 1f)) {
            assertEquals("从预览页关掉(v = 1)全程不透明,不闪 a=$a", 1f, homeLayerAlpha(a, 1f), 1e-6f)
        }
        assertEquals("越界夹紧", 1f, homeLayerAlpha(-1f, 2f), 0f)
    }

    @Test fun `关掉,v 冻结,z 缩回整屏 150 ms`() {
        val m = previewMotion(shown = false, preview = false, fresh = false, vNow = 1f)
        assertNull("v 冻结", m.vTarget)
        assertNull(m.vSnap)
        assertEquals(0f, m.zTarget!!, 0f)
        assertEquals(GtvLayout.SETTINGS_FADE_OUT_MS, m.zMs)
    }

    @Test fun `从完全关着打开到第一层,v、z 直接归 0(首页随 a 淡出)`() {
        val m = previewMotion(shown = true, preview = false, fresh = true, vNow = 1f)
        assertEquals(0f, m.vSnap!!, 0f)
        assertEquals(0f, m.zSnapFirst!!, 0f)
        assertNull(m.vTarget)
        assertNull(m.zTarget)
    }

    @Test fun `从完全关着打开到有预览的页(编辑页回来),v 直接 1,z 200 ms 缩进预览框`() {
        val m = previewMotion(shown = true, preview = true, fresh = true, vNow = 0f)
        assertEquals(1f, m.vSnap!!, 0f)
        assertEquals(1f, m.zTarget!!, 0f)
        assertEquals(GtvLayout.SETTINGS_FADE_IN_MS, m.zMs)
    }

    @Test fun `第一层进布局,先把首页瞬移进预览框(此刻看不见),再 150 ms 淡入`() {
        val m = previewMotion(shown = true, preview = true, fresh = false, vNow = 0f)
        assertEquals(1f, m.zSnapFirst!!, 0f)
        assertEquals(1f, m.vTarget!!, 0f)
        assertEquals(GtvLayout.SETTINGS_LAYER_FADE_MS, m.vMs)
    }

    @Test fun `预览还没淡完又回到预览页,不瞬移 z(否则看得见一跳)`() {
        val m = previewMotion(shown = true, preview = true, fresh = false, vNow = 0.4f)
        assertNull(m.zSnapFirst)
        assertEquals(1f, m.zTarget!!, 0f)
    }

    @Test fun `布局返回第一层,v 150 ms 淡出,淡完才把 z 归 0`() {
        val m = previewMotion(shown = true, preview = false, fresh = false, vNow = 1f)
        assertEquals(0f, m.vTarget!!, 0f)
        assertEquals(GtvLayout.SETTINGS_LAYER_FADE_MS, m.vMs)
        assertNull("淡出期间 z 不动", m.zTarget)
        assertNull(m.zSnapFirst)
        assertEquals(0f, m.zSnapAfter!!, 0f)
    }
}
