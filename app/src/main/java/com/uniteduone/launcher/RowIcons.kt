package com.uniteduone.launcher

/** 行图标 id(DESIGN §2「约 12 个内置 Material 图标」,M4b spec §0-6)。顺序 = 选择器里的顺序。 */
internal val ROW_ICON_IDS = listOf(
    "movie", "tv", "live", "music", "games", "kids",
    "tools", "education", "sports", "news", "photos", "apps",
)

/** 新建行的默认图标。 */
internal const val NEW_ROW_ICON = "apps"

internal fun isRowIconId(id: String?): Boolean = id != null && id in ROW_ICON_IDS

/** 没存图标的旧行按名字匹配——与 M4b 之前 RowIcon 的 when 逐条对应,外观不变;其余一律 tv。 */
internal fun legacyRowIconId(name: String): String = when (name.uppercase()) {
    "VIDEO" -> "movie"
    "LIVE" -> "tv"
    "MUSIC" -> "music"
    else -> "tv"
}

/** 这一行最终用哪个图标 id:存了合法 id 就用它,否则按名字回落。 */
internal fun effectiveRowIconId(name: String, icon: String?): String =
    if (icon != null && isRowIconId(icon)) icon else legacyRowIconId(name)
