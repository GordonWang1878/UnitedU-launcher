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
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * 行图标(R163 起**行只认图标**,没有行名):按 [icon](layout.json 里存的图标 id,见 RowIcons.kt;不认识的当 [NEW_ROW_ICON])
 * 画一个 [boxSize] 见方的 Material 矢量图。首页(左边距里,R48)、编辑页(同一位置,R163)、各种菜单与页头(「加到桌面…」的药丸、
 * 删行确认 / 添加应用 / 换行图标页的页头)、引导第 2 步的计划表都走这一个。
 *
 * 颜色经 [tint] 在**绘制阶段**读(每帧求值):首页的焦点行 accent ↔ 压暗插值动画不重组本行,编辑页 / 菜单直接给常量。
 * 无障碍描述 = 这个图标的名字(`row_icon_<id>`,「影片」「电视」……,同换行图标页里那一格的字):行没有名字了,
 * 原来由行名承担的那一句现在由图标自己说。
 *
 * 用真的 Material 图标,不再手画:复审逐项对比后指出手画版本的填充、朝向、笔画粗细都和参考图不同
 * (胶片画成了横向描边矩形、电视画成了开口盒子加天线、音符的旗是细线)。
 */
@Composable
fun RowIcon(
    icon: String,
    tint: () -> Color,
    modifier: Modifier = Modifier,
    boxSize: Dp,
) {
    val id = effectiveRowIconId(icon)
    val painter = rememberVectorPainter(rowIconVector(id))
    val description = stringResource(rowIconLabel(id))
    Box(
        modifier
            .size(boxSize)
            .semantics { contentDescription = description }
            .drawBehind { with(painter) { draw(size, colorFilter = ColorFilter.tint(tint())) } },
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
