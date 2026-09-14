package com.uniteduone.launcher

import android.graphics.Bitmap

/** 一个可启动的应用。[card] 是最终要画在卡片上的图,已按优先级选好。 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val card: Bitmap?,
    /** true = 接近 16:9 的横幅,铺满卡片;false = 方形图标,居中留边(Projectivy 也是这么摆的)。 */
    val isWide: Boolean,
)

/** 一行。名字固定(VIDEO / LIVE / MUSIC),由 layout.json 决定成员与顺序。 */
data class Row(
    val name: String,
    val apps: List<AppEntry>,
)
