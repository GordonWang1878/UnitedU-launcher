package com.uniteduone.launcher

import android.graphics.Bitmap

/** 一个可启动的应用。[card] 是最终要画在卡片上的图,已按优先级选好。 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val card: Bitmap?,
    /**
     * true = 接近 16:9 且四边不透明的横幅,铺满卡片;false = 当图标画,居中留边(Projectivy 也是这么摆的)。
     * 应用横幅与自定义卡片图同一条判据 [fitsAsBanner](R88)。
     */
    val isWide: Boolean,
    /** 当图标画的卡([isWide] = false 且有图)的底色(图标边缘色,ARGB);铺满的横幅 / 无图时 null。 */
    val fallbackColor: Int? = null,
    /** 装机时间(epoch ms),给「新应用」判据用;查不到为 0。 */
    val firstInstallTime: Long = 0L,
)

/** 一行。名字与图标来自 layout.json(M4b 起可在编辑页改),由 layout.json 决定成员与顺序。
 *  (R92 起首页没有输入源行了——输入源搬到顶栏「输入源」胶囊打开的页面,`RowKind` 随之删掉。) */
data class Row(
    val name: String,
    val apps: List<AppEntry>,
    /** layout.json 里存的图标 id;null = 按名字回落,见 RowIcons.kt。 */
    val icon: String? = null,
    /**
     * 这一行在 **layout.json** 里的下标;-1 = 没有对应条目(只在单测里出现)。
     *
     * **不能用渲染位置代替**:`buildRows` 会丢掉装不到的包、再整行丢掉空行,
     * 所以「屏幕上第几行」和「layout.json 里第几行」随时可能对不上 ——
     * 而「从这一行移出」「移动位置」写的是 layout.json。用渲染下标去写盘,
     * 前面有任何一行被丢掉,删的就是**别人那一行**的应用。
     * 因此这个值必须在 `filter` **之前**按 layout 的下标定下来(见 buildRows)。
     */
    val layoutRow: Int = -1,
)
