package com.uniteduone.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

/**
 * **文字色四档**(R134,2026-09-30 外观轮「字体统一」)。此前界面里有 11 种灰(#4A4A4A … #F5F5F5),
 * 相邻两档肉眼分不出、最暗的两档(#4A4A4A / #666666 的页脚提示)在深色底上对比度只有 2:1 上下、电视上几乎看不见。
 * 现在只有这四档;`Theme` 里的旧名字(EmphasisText、SecondaryText、FooterHintText……)都指到这里,值不再各写各的。
 * 强调色(主题 accent / highlight)不在这里——它跟着主题走,见 [LocalThemeColors]。
 */
object Ink {
    /** 标题、正在看的内容(聚焦项的名字、版本号、输入框里的字)。 */
    val Primary = Color(0xFFF5F5F5)
    /** 未聚焦的可选项:胶囊标签、缩略图名字、按钮文字。 */
    val Label = Color(0xFFB0B0B0)
    /** 说明文字:路径、一行的说明、页标题旁的提示。 */
    val Secondary = Color(0xFF9A9A9A)
    /**
     * 最弱的一档:页脚提示、许可声明、「当前」这类小标签。页面底色(#0E0E0F)上 5.0:1、未聚焦胶囊 / 信息块的底
     * (#161718)上 4.7:1(WCAG 正文下限 4.5,两种底都过;R140 复审前是 #7E7E7E,放在胶囊底上只有 4.4:1)。
     */
    val Tertiary = Color(0xFF828282)
}

/**
 * **全应用的字号与文字样式只此一处**(R134)。外观轮之前界面代码里写死了 15 种字号(10–32 sp),同一种角色在不同页面
 * 大小不一:页标题在设置页 31、编辑页 20、引导 26、选图页 / 对话框 16;页脚提示 10–11 sp。现在七档:
 *
 * | 档 | sp | 用在哪 |
 * |---|---|---|
 * | [TITLE] | 31 | 每个整屏页面的页名(设置各层、所有应用、编辑桌面、选图页、确认页、引导、从手机添加) |
 * | [HEADLINE] | 22 | 要人照着抄的一行(扫码页的网址) |
 * | [SECTION] | 17 | 分组标题(系统工具、内置 / 我的)、分步说明 |
 * | [LABEL] | 15 | 胶囊与按钮、列表项、行名、页名上方的路径 |
 * | [BODY] | 14 | 说明文字、卡片名 |
 * | [CAPTION] | 12 | 缩略图名字、页脚提示、长按菜单里应用图下的名字 |
 * | [MICRO] | 11 | 胶囊第二行、角标、许可声明 |
 *
 * 另有 [CLOCK] 16(顶栏时钟与字标,Google 实测值,只此一处用)。
 *
 * **与 R109 的关系**:R109 把设置类页面的字号整体小了 1 sp(Gordon 2026-09-28「就这样」),这里的 31 / 15 / 11 就是
 * 那一轮定下的值(32 − 1、16 − 1、12 − 1);外观轮做的是把**其余页面**(长按菜单、编辑页、选图页、对话框、引导)拉到同一套上。
 * `GtvLayout.SETTINGS_TYPE_STEP` / `settingsSp` 留作那一轮的记录,界面代码不再读它们(单测 `TypeScaleTest` 钉两边相等)。
 *
 * **最小 11 sp**:1080p / 320 dpi 上 10 sp 的字高 20 px,三米外读不出;原来 10 sp 的地方(图注、对话框提示、角标)升到 12 / 11。
 *
 * **字重只用两档**:Normal(400)与 Medium(500)。中文没有自带字体(Google Sans Flex 无 CJK),回落到系统的
 * Noto Sans CJK,A95L 与模拟器上它只有 Regular 一个字重——Medium 的中文按 Regular 画(差 100,系统不加粗),
 * Bold(700)会被系统**合成加粗**(笔画糊成一团),所以不用 Bold。
 *
 * 界面代码里不许再出现 `fontSize = 数字.sp`(单测 `TypeScaleTest` 扫源码)。要别的颜色 / 对齐:`Type.body.copy(color = …)`。
 */
object Type {
    const val TITLE = 31f
    const val HEADLINE = 22f
    const val SECTION = 17f
    const val LABEL = 15f
    const val BODY = 14f
    const val CAPTION = 12f
    const val MICRO = 11f
    const val CLOCK = 16f

    /**
     * **均衡折行**:一段字要折成两行以上时,各行长短接近,末行不会只剩一两个字。给页名与居中的短说明用。
     * 起因(2026-09-30 模拟器,繁体引导第 2 步):页名「把已安裝的應用程式放到桌面」按缺省的贪心折行是 12 + 1,
     * 第二行只有一个「面」。行数不变(均衡只在最少行数之内挪断点),固定高度的说明块不受影响。
     */
    val Balanced = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)

    /** 页名。折行时均衡([Balanced])。 */
    val title = TextStyle(
        fontFamily = Theme.Sans, fontWeight = FontWeight.Medium, color = Ink.Primary,
        fontSize = TITLE.sp, lineHeight = (TITLE * 1.2f).sp, lineBreak = Balanced,
    )
    /** 页名上方的一行小字:路径(「设置 · 通用」)、所属对象(行名、应用名)、引导的步数。 */
    val eyebrow = TextStyle(fontFamily = Theme.Sans, color = Ink.Secondary, fontSize = LABEL.sp, lineHeight = 20.sp)
    /** 要人照着抄的一行(网址)。 */
    val headline = TextStyle(
        fontFamily = Theme.Sans, fontWeight = FontWeight.Medium, color = Ink.Primary,
        fontSize = HEADLINE.sp, lineHeight = 28.sp,
    )
    /** 分组标题。 */
    val section = TextStyle(
        fontFamily = Theme.Sans, fontWeight = FontWeight.Medium, color = Ink.Secondary,
        fontSize = SECTION.sp, lineHeight = 22.sp,
    )
    /** 分步说明、要人读完再动手的一段(比 [body] 大一档、更亮)。 */
    val lead = TextStyle(fontFamily = Theme.Sans, color = Ink.Primary, fontSize = SECTION.sp, lineHeight = 25.sp)
    /** 胶囊 / 按钮 / 列表项上的字(未聚焦;聚焦时调用方换颜色与 Medium)。 */
    val label = TextStyle(fontFamily = Theme.Sans, color = Ink.Label, fontSize = LABEL.sp)
    /** 说明文字。 */
    val body = TextStyle(fontFamily = Theme.Sans, color = Ink.Secondary, fontSize = BODY.sp, lineHeight = 21.sp)
    /** 图注、页脚提示。 */
    val caption = TextStyle(fontFamily = Theme.Sans, color = Ink.Tertiary, fontSize = CAPTION.sp, lineHeight = 16.sp)
    /** 胶囊第二行、角标、许可声明。 */
    val micro = TextStyle(fontFamily = Theme.Sans, color = Ink.Tertiary, fontSize = MICRO.sp, lineHeight = 15.sp)
    /**
     * 顶栏时钟与字标、顶栏焦点名字。颜色由调用方给(主题 accent)。
     * 等宽数字(`tnum`,R141):比例数字下「1」比「0」窄,每分钟跳字时整串时间与后面的日期、字标会左右挪一下。
     */
    val clock = TextStyle(fontFamily = Theme.Sans, fontSize = CLOCK.sp, fontFeatureSettings = "tnum")
}
