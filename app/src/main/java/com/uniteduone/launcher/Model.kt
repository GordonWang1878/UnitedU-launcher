package com.uniteduone.launcher

import android.graphics.Bitmap

/** 一个可启动的应用。[card] 是最终要画在卡片上的图,已按优先级选好。 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val card: Bitmap?,
    /** true = 接近 16:9 的横幅,铺满卡片;false = 方形图标,居中留边(Projectivy 也是这么摆的)。 */
    val isWide: Boolean,
    /** 无横幅回落卡的底色(图标主色,ARGB);有横幅/自定义图时 null。 */
    val fallbackColor: Int? = null,
    /** 装机时间(epoch ms),给「新应用」判据用;查不到为 0。 */
    val firstInstallTime: Long = 0L,
)

/** 一行的种类:普通应用行,或电视输入源行(design §2)。
 *  首页的焦点账本、渲染、位移全部按行索引推导,与种类无关 —— 种类只改「点一下做什么」
 *  (启动应用 vs 切换输入源)和行标题图标,不碰焦点不变量。 */
enum class RowKind { APPS, INPUTS }

/** 一行。应用行名字固定(VIDEO / LIVE / MUSIC),由 layout.json 决定成员与顺序;
 *  输入源行名字是本地化标题,成员由 [Inputs] 枚举。
 *  输入源行把每个输入伪装成 [AppEntry](packageName 存输入 id,card=null 走文字回退),
 *  从而**原样复用** AppCard / CategoryRow / 整套纵向焦点账本 —— 只在点击处按 [kind] 分流。 */
data class Row(
    val name: String,
    val apps: List<AppEntry>,
    val kind: RowKind = RowKind.APPS,
    /**
     * 这一行在 **layout.json** 里的下标;输入源行没有对应条目,记 -1。
     *
     * **不能用渲染位置代替**:`buildRows` 会丢掉装不到的包、再整行丢掉空行,
     * 所以「屏幕上第几行」和「layout.json 里第几行」随时可能对不上 ——
     * 而「从当前分类移除」「移动位置」写的是 layout.json。用渲染下标去写盘,
     * 前面有任何一行被丢掉,删的就是**别人那一行**的应用。
     * 因此这个值必须在 `filter` **之前**按 layout 的下标定下来(见 buildRows)。
     */
    val layoutRow: Int = -1,
)
