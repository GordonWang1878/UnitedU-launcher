package com.uniteduone.launcher

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SettingsInputHdmi
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 行标题前的小图标。用真的 Material 图标,不再手画:
 * 复审逐项对比后指出手画版本的填充、朝向、笔画粗细都和参考图不同
 * (胶片画成了横向描边矩形、电视画成了开口盒子加天线、音符的旗是细线)。
 */
@Composable
fun RowIcon(
    name: String,
    kind: RowKind = RowKind.APPS,
    /** layout.json 里存的图标 id(见 RowIcons.kt);null 或不认识 → 按 [name] 回落。输入源行不用。 */
    icon: String? = null,
    tint: androidx.compose.ui.graphics.Color = Theme.RowTitle,
) {
    // 输入源行的标题是本地化文字(「输入源」/「Inputs」),按 name 匹配跨语言不可靠 ——
    // 用 kind 判定,不看标题文字。
    val vector = if (kind == RowKind.INPUTS) {
        Icons.Outlined.SettingsInputHdmi     // 信号源:HDMI 插口
    } else rowIconVector(effectiveRowIconId(name, icon))
    // 用 Image + ColorFilter 着色,免得为一个 Icon 引入整套 material3
    Image(
        imageVector = vector,
        contentDescription = name,
        colorFilter = ColorFilter.tint(tint),
        // Material 的矢量图标只填满外框的 24/32,所以框要给到 24dp 才等于参考里的 36px 字形
        modifier = Modifier.size(24.dp),
    )
}

/** 行图标 id → 矢量图。movie / tv / music 三个与 M4b 之前按名字匹配的图完全相同(外观不变)。 */
internal fun rowIconVector(id: String): ImageVector = when (id) {
    "movie" -> Icons.Filled.Theaters
    "tv" -> Icons.Outlined.Tv
    "live" -> Icons.Outlined.LiveTv
    "music" -> Icons.Filled.MusicNote
    "games" -> Icons.Outlined.SportsEsports
    "kids" -> Icons.Outlined.ChildCare
    // material-icons-extended 没有 Icons.Outlined.Build(这个 BOM 版本只有 BuildCircle,外框会和
    // 本行其它图标的「无边框实心/线性字形」不一致),换成语义相近、同样无边框的 Handyman(扳手+螺丝刀)。见任务报告。
    "tools" -> Icons.Outlined.Handyman
    "education" -> Icons.Outlined.School
    "sports" -> Icons.Outlined.FitnessCenter
    "news" -> Icons.Outlined.Newspaper
    "photos" -> Icons.Outlined.PhotoLibrary
    else -> Icons.Outlined.Apps   // "apps"
}

/**
 * 行图标 id → 选择器里显示的名字(`row_icon_<id>`)。与上面的 [rowIconVector] 放在一起:加一个 id 要两张表一起改;
 * 漏写的 id 会静默掉进 else(显示成「应用」)——RowIconsTest 逐个核对每个 [ROW_ICON_IDS] 都对到自己那条。
 */
internal fun rowIconLabel(id: String): Int = when (id) {
    "movie" -> R.string.row_icon_movie
    "tv" -> R.string.row_icon_tv
    "live" -> R.string.row_icon_live
    "music" -> R.string.row_icon_music
    "games" -> R.string.row_icon_games
    "kids" -> R.string.row_icon_kids
    "tools" -> R.string.row_icon_tools
    "education" -> R.string.row_icon_education
    "sports" -> R.string.row_icon_sports
    "news" -> R.string.row_icon_news
    "photos" -> R.string.row_icon_photos
    else -> R.string.row_icon_apps   // "apps"
}
