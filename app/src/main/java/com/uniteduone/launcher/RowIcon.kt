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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * 行标题前的小图标。用真的 Material 图标,不再手画:
 * 复审逐项对比后指出手画版本的填充、朝向、笔画粗细都和参考图不同
 * (胶片画成了横向描边矩形、电视画成了开口盒子加天线、音符的旗是细线)。
 *
 * **owner 反馈 Round 5(2026-09-21)24→20dp**:Google TV 首页本身没有行图标这个元素
 * (`docs/research/2026-09-20-google-tv-launcherx-measurements.md` 通篇没有对应物),没有
 * Google 数值可以对齐,判据只能是「和它现在挨着的标题字号相不相称」——任务原话。这里的标题字号
 * 随 Ruling R25 从 16sp/23dp 行盒改成了 14sp/20dp 行盒(`GtvLayout.ROW_TITLE_LINE`),原来
 * 24dp 的图标框对比新行盒会显得明显偏大(24/23≈1.04,原本贴合;24/20=1.2,新行盒下超出两成)。
 * 改成与 `ROW_TITLE_LINE` 相等的 20dp——图标框高与它右边文字的行盒高度一致,是这里唯一
 * 站得住脚的比例基准(两者都读同一个 `GtvLayout` 常量,以后行标题字号再变,这里跟着一起变,
 * 不会重新漂移)。 */
@Composable
fun RowIcon(
    name: String,
    /** layout.json 里存的图标 id(见 RowIcons.kt);null 或不认识 → 按 [name] 回落。 */
    icon: String? = null,
    tint: androidx.compose.ui.graphics.Color = Theme.RowTitle,
) {
    // 用 Image + ColorFilter 着色,免得为一个 Icon 引入整套 material3
    Image(
        imageVector = rowIconFor(name, icon),
        contentDescription = name,
        colorFilter = ColorFilter.tint(tint),
        // owner 反馈 Round 5:框高改为与行标题的行盒(GtvLayout.ROW_TITLE_LINE)相等,理由见本
        // 文件顶部 KDoc——不再是「填满外框的 24/32」那个已作废的 36px 参考推导。
        modifier = Modifier.size(GtvLayout.ROW_TITLE_LINE.dp),
    )
}

/** R46:首页用的版本——颜色在**绘制阶段**读([tint] 每帧求值),跟着焦点态的灰 ↔ 近白插值走,
 *  动画不重组;图形与上面的 [RowIcon] 相同。
 *  **R48**:首页不再画行标题,图标独自画在左边距里,方框改为 [boxSize](没有默认值,唯一调用点首页显式传
 *  `GtvLayout.ROW_ICON_SIZE`);
 *  行名改由这里的 `contentDescription` 带给无障碍服务(原来由旁边的标题文字提供)。 */
@Composable
fun RowIcon(
    name: String,
    icon: String?,
    tint: () -> androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    boxSize: androidx.compose.ui.unit.Dp,
) {
    val painter = rememberVectorPainter(rowIconFor(name, icon))
    Box(
        modifier
            .size(boxSize)
            .semantics { contentDescription = name }
            .drawBehind { with(painter) { draw(size, colorFilter = ColorFilter.tint(tint())) } },
    )
}

// (R92 前这里还按 kind 给置顶的输入源行画 HDMI 插口图标;首页没有输入源行了,只剩应用行。)
private fun rowIconFor(name: String, icon: String?): ImageVector = rowIconVector(effectiveRowIconId(name, icon))

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
