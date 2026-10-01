package com.uniteduone.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * **整屏页的两种版式**(R135,2026-09-30 外观轮「UI 提升」)。外观轮之前界面里并存两套视觉:设置 / 关于 / 输入源 /
 * 长按菜单 / 所有应用是 gtv 线的整屏 `MenuBg` 页(Gordon 在电视上认可过);改名框、删除确认、行图标、选图、添加应用、
 * 引导还是更早的「屏幕中间一块小面板 + 半透明蒙版」。现在所有页面只用下面两种版式之一:
 *
 * 1. **左右两栏**([ShellScaffold] + [ShellTitle],在 SettingsShell.kt):左半屏正中是页名(上方一行小字写这页属于谁,
 *    下方写说明),右半屏正中是一列胶囊或一个控件。设置各层、关于、输入源、长按菜单、确认页、改名页、行图标、添加应用、引导。
 * 2. **页头 + 网格**([PageHeader]):页名在左上角基准线上、说明跟在页名右边,下面是铺满屏宽的卡片 / 缩略图网格。
 *    所有应用、编辑桌面、换壁纸、换卡片图、屏保图库。
 *
 * 底色一律 [GtvTokens.MenuBg];聚焦一律「填主题色」(胶囊、图标格、输入框)或「放大 + 描边」(卡片、缩略图)。
 */

/**
 * 页头 + 网格那一种版式的页头:31 sp 页名,同一行右边 14 sp 的说明(与页名基线对齐)。高 [AppsPageLayout.TITLE_LINE]。
 * 调用方自己管左边距(基准线 [GtvLayout.CONTENT_KEYLINE])。
 */
@Composable
fun PageHeader(title: String, hint: String?, modifier: Modifier = Modifier) {
    Row(modifier.height(AppsPageLayout.TITLE_LINE.dp), verticalAlignment = Alignment.Top) {
        BasicText(title, style = Type.title, maxLines = 1, modifier = Modifier.alignByBaseline())
        if (hint != null) {
            BasicText(
                hint,
                style = Type.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp).alignByBaseline(),
            )
        }
    }
}

/**
 * 「＋」(编辑页行尾的加卡、选图页的「从手机添加」)。画成图标而不是一个全角加号字:字形的粗细、大小随回落字体变,
 * 图标在哪台电视上都一样。
 */
@Composable
fun PlusGlyph(color: Color, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Image(
        imageVector = Icons.Filled.Add,
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier.size(size),
    )
}

/** 「✓」图形(R154 屏保图库的「参与轮播」角标):与 [PlusGlyph] 同一个理由画成图标。 */
@Composable
fun CheckGlyph(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Image(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier.size(size),
    )
}
