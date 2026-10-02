package com.uniteduone.launcher

/** 行图标 id(DESIGN §2「约 12 个内置 Material 图标」,M4b spec §0-6)。顺序 = 选择器里的顺序。 */
internal val ROW_ICON_IDS = listOf(
    "movie", "tv", "live", "music", "games", "kids",
    "tools", "education", "sports", "news", "photos", "apps",
)

/** 新建行的默认图标。 */
internal const val NEW_ROW_ICON = "apps"

internal fun isRowIconId(id: String?): Boolean = id != null && id in ROW_ICON_IDS

/**
 * 老文件(R163 之前)里没存图标的行按**旧名字**回落——与 M4b 之前 RowIcon 的 when 逐条对应,外观不变;其余一律 tv。
 * **只给读盘用**([rowIconFromDisk]):行没有名字了,内存里不再有这个输入。
 */
internal fun legacyRowIconId(name: String): String = when (name.uppercase()) {
    "VIDEO" -> "movie"
    "LIVE" -> "tv"
    "MUSIC" -> "music"
    else -> "tv"
}

/** 这个 id 能不能直接画:合法就用,否则 [NEW_ROW_ICON]。 */
internal fun effectiveRowIconId(icon: String?): String = icon?.takeIf { isRowIconId(it) } ?: NEW_ROW_ICON

/**
 * 读盘时一行最终用哪个图标 id(R163):盘上存了合法 id 就用它;没有(缺失 / 非法)时,**老文件**带着 `name` →
 * 按旧名字回落([legacyRowIconId]);新文件没有 `name` → [NEW_ROW_ICON]。`name` 到这里就用完了,不会进内存里的行。
 */
internal fun rowIconFromDisk(name: String?, icon: String?): String =
    icon?.takeIf { isRowIconId(it) } ?: name?.let { legacyRowIconId(it) } ?: NEW_ROW_ICON
