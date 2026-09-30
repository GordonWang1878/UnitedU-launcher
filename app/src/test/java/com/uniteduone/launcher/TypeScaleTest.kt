package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R134(2026-09-30 外观轮「字体统一」)的防线:字号只有 [Type] 里那几档,界面代码里不许再写死。
 * 纯 JVM:直接扫 main 源码。
 */
class TypeScaleTest {
    private val src = listOf(
        File("src/main/java/com/uniteduone/launcher"),
        File("app/src/main/java/com/uniteduone/launcher"),
    ).first { it.isDirectory }

    private fun sources(): List<File> = src.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }

    /** 去掉注释(块注释与行注释),只看代码。 */
    private fun code(f: File): String =
        f.readText().replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

    @Test fun scaleHasSevenStepsAndNothingBelowEleven() {
        val steps = listOf(Type.TITLE, Type.HEADLINE, Type.SECTION, Type.LABEL, Type.BODY, Type.CAPTION, Type.MICRO)
        assertEquals(listOf(31f, 22f, 17f, 15f, 14f, 12f, 11f), steps)
        assertEquals("从大到小、没有重复", steps.sortedDescending().distinct(), steps)
        assertTrue("电视上 11 sp 以下读不出", steps.min() >= 11f)
        assertEquals(16f, Type.CLOCK, 0f)
        assertEquals("顶栏时钟仍是 Google 实测的那个数", GtvLayout.TOP_BAR_CLOCK_TEXT, Type.CLOCK, 0f)
    }

    @Test fun noHardCodedFontSizesOutsideType() {
        val literal = Regex("""fontSize\s*=\s*\(?\s*\d+(\.\d+)?f?\s*(\+[^)]*)?\)?\s*\.sp""")
        val offenders = sources().filter { it.name != "Type.kt" }.flatMap { f ->
            literal.findAll(code(f)).map { "${f.name}: ${it.value}" }.toList()
        }
        assertTrue("界面代码里写死了字号,改用 Type.*:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun uiCodeNoLongerReadsTheSettingsStep() {
        val offenders = sources().filter { it.name != "GtvLayout.kt" && it.name != "Type.kt" }.flatMap { f ->
            Regex("""settingsSp\(|SETTINGS_TYPE_STEP|textStep""").findAll(code(f)).map { "${f.name}: ${it.value}" }.toList()
        }
        assertTrue("字号一律读 Type,不再按「基准 + step」现算:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun onlyTheAppFontIsUsed() {
        val offenders = sources().flatMap { f ->
            Regex("""fontFamily\s*=\s*(?!Theme\.Sans)[A-Za-z_.]+""").findAll(code(f)).map { "${f.name}: ${it.value}" }.toList()
        }
        assertTrue("只用 Theme.Sans:\n" + offenders.joinToString("\n"), offenders.isEmpty())
        // 不用 Bold:中文回落字体只有 Regular,Bold 会被系统合成加粗(见 Type 的 KDoc)
        val bold = sources().flatMap { f ->
            Regex("""FontWeight\.(Bold|SemiBold|ExtraBold|Black)""").findAll(code(f)).map { "${f.name}: ${it.value}" }.toList()
        }.filterNot { it.startsWith("Theme.kt") }   // Theme.Sans 注册字重轴的那一处
        assertTrue("字重只用 Normal / Medium:\n" + bold.joinToString("\n"), bold.isEmpty())
    }

    @Test fun textColoursComeFromFourInks() {
        assertEquals(4, setOf(Ink.Primary, Ink.Label, Ink.Secondary, Ink.Tertiary).size)
        // 旧名字都指到四档里
        val inks = setOf(Ink.Primary, Ink.Label, Ink.Secondary, Ink.Tertiary)
        for (c in listOf(
            Theme.EmphasisText, Theme.DialogBodyText, Theme.ButtonText, Theme.MenuItemText, Theme.ThumbLabelText,
            Theme.SecondaryText, Theme.HintText, Theme.ThumbLoadingText, Theme.FootnoteText, Theme.PickerFooterText,
            Theme.FooterHintText,
        )) assertTrue(c in inks)
    }

    /** 最弱的一档在页面底色上仍有 4.5:1(WCAG AA 正文),不再有看不见的页脚提示。 */
    @Test fun weakestInkIsReadableOnThePageBackground() {
        fun lum(c: androidx.compose.ui.graphics.Color): Double {
            fun ch(v: Float): Double = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        val ratio = (lum(Ink.Tertiary) + 0.05) / (lum(GtvTokens.MenuBg) + 0.05)
        assertTrue("对比度 $ratio", ratio >= 4.5)
    }
}
