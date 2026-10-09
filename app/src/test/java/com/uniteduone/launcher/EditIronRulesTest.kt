package com.uniteduone.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R165 重写编辑页的源码防线(纯 JVM,扫 main 源码,同 TypeScaleTest 的手法):七条铁律里能静态看出来的两条、
 * 以及行菜单 / 行尾「+」确实删干净了。行为由 e2e(scripts/e2e/j_edit.py)验。
 */
class EditIronRulesTest {
    private val src = listOf(
        File("src/main/java/com/uniteduone/launcher"),
        File("app/src/main/java/com/uniteduone/launcher"),
    ).first { it.isDirectory }

    private fun code(name: String): String =
        File(src, name).readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** 铁律 1:编辑页与零件里不许有可滚动容器(唯一的例外 AppPicker 在自己的文件里)。 */
    @Test fun editPageUsesNoScrollableContainer() {
        for (f in listOf("EditScreen.kt", "EditShelfParts.kt")) {
            val hits = Regex("""\b(LazyRow|LazyColumn|verticalScroll|horizontalScroll)\b""").findAll(code(f)).map { it.value }.toList()
            assertTrue("$f 用了可滚动容器(铁律 1):$hits", hits.isEmpty())
        }
    }

    /** 铁律 1 的另一半:自算位移必须配放开测量。 */
    @Test fun offsetsComeWithUnboundedMeasuring() {
        val c = code("EditScreen.kt")
        assertTrue("纵向:wrapContentHeight(unbounded)", c.contains("wrapContentHeight(Alignment.Top, unbounded = true)"))
        assertTrue("横向:wrapContentWidth(unbounded)", c.contains("wrapContentWidth(Alignment.Start, unbounded = true)"))
    }

    /** 铁律 2:落没落下只信目标自报,不信 requestFocus() 的返回。 */
    @Test fun landingIsNeverJudgedByRequestFocusSucceeding() {
        for (f in listOf("EditScreen.kt", "EditShelfParts.kt")) {
            assertFalse(f, Regex("""requestFocus\(\)\s*\}\s*\.isSuccess""").containsMatchIn(code(f)))
        }
    }

    /**
     * owner 裁定(R165 计划复审):架子不裁焦点卡的描边 / 柔光,左边缘一律不裁(第 1 张卡是默认焦点,柔光要画到架子外);
     * 横向只裁越过架子右端的非焦点卡。防的是有人「顺手」给卡片条加回整条裁切。
     */
    @Test fun theShelfNeverClipsTheFocusedCardOrItsLeftEdge() {
        val c = code("EditScreen.kt")
        assertTrue("卡片条的裁切走 clipPastShelfEnd", c.contains("clipPastShelfEnd"))
        assertFalse("不许整块 clipToBounds", c.contains("clipToBounds"))
        assertFalse("不许从左缘 0 起裁", Regex("""clipRect\(\s*left\s*=\s*0f""").containsMatchIn(c))
    }

    /** R165:行菜单、行尾「+」、在下方新建一行都没了。 */
    @Test fun theRowMenuAndTheRowEndPlusAreGone() {
        val c = code("EditScreen.kt")
        for (gone in listOf("RowMenu", "AddCard(", "addRowBelow", "edit_row_menu_title", "editFirstRow")) {
            assertFalse("EditScreen.kt 里还有 $gone", c.contains(gone))
        }
        assertFalse("LayoutOps.kt 里还有 addRowBelow", code("LayoutOps.kt").contains("fun addRowBelow"))
    }
}
