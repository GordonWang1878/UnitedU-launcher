# 设置页字体盘点(2026-09-24,代码读出,HEAD a28ea10 之后)

字体:全部 `Theme.Sans`——拉丁字母/数字 = Google Sans Flex(`res/font/google_sans_flex.ttf`,可变字重);中文不在该字体内,由系统回落到电视自带中文字体。字重 Normal = 400,Medium = 500。常量:`GtvLayout.MENU_ITEM_TEXT` 16、`MENU_ITEM_HINT_TEXT` 12、`SETTINGS_TITLE_TEXT` 32。

| 页 | 项 | 大字/小字 | 字号 | 字重 | 颜色/透明度 | 代码 |
|---|---|---|---|---|---|---|
| 所有页·左栏 | 页名 | 大标题 | 32 sp | Medium | EmphasisText | SettingsShell.kt:207 titleStyle |
| 二、三层·左栏 | 路径 | 标题上方 | 16 sp | Normal | SecondaryText | SettingsShell.kt:206 pathStyle |
| 布局/外观·左栏 | 预览提示行 | 说明 | 14 sp | Normal | SecondaryText | SettingsShell.kt:214 shellBodyStyle |
| 第一层 | 胶囊名字 | 大字 | 16 sp | 未聚焦 Normal / 聚焦 Medium | MenuItemText / 聚焦对比色 | ShellCapsule.kt MenuPillLabel |
| 第一层 | 胶囊说明 | 小字(第二行) | 12 sp | Normal | 文字色 70% | ShellCapsule.kt hint |
| 第一层 | › | 符号 | 20 sp | Normal | 文字色 55% | TrailingContent |
| 第二层 | 名字 | 大字 | 16 sp | Normal / 聚焦 Medium | 同上 | MenuPillLabel |
| 第二层 | 值(同一行右端) | 大字 | 16 sp | Normal | 文字色 55% | TrailingContent Value |
| 第二层 | 值放不下时(挪到第二行) | 小字 | 12 sp | Normal | 文字色 70% | valueWraps → hint |
| 外观 | 滑块聚焦:名字/数值 | 大字 | 16 sp | Medium | 文字色 | SliderContent |
| 外观 | 滑块 ‹ › | 符号 | 16 sp | Normal | 45%(到头 15%) | SliderContent |
| 第三层 | 选项名 | 大字 | 16 sp | Normal / 聚焦 Medium | 同上 | MenuPillLabel |
| 第三层 | ✓ | 符号 | 16 sp | Medium | 主题色 / 聚焦黑 | TrailingContent Check |
| 关于·左栏 | 版本号 | 正文 | 15 sp | Normal | EmphasisText | AboutScreen.kt ~390 |
| 关于·左栏 | 检查更新标题行 | 正文 | 14 sp | Medium | — | AboutScreen.kt ~400 |
| 关于·左栏 | 检查结果 | 正文 | 13 sp | Normal | 状态色 | AboutScreen.kt ~413 |
| 关于·左栏 | 更新说明 | 小字 | 12 sp | Normal | — | AboutScreen.kt ~425 |
| 关于·左栏 | 许可声明标题 | 小字 | 11 sp | Medium | FootnoteText | AboutScreen.kt ~438 |
| 关于·左栏 | 许可正文、项目地址 | 小字 | 11 sp | Normal | FootnoteText | AboutScreen.kt ~447/454 |
| 默认桌面·左栏 | 「当前」 | 小字 | 11 sp | Normal | HintText | HomeSettingsCard.kt:110 |
| 默认桌面·左栏 | 当前桌面名 | 正文 | 15 sp | Medium | — | HomeSettingsCard.kt:116 |
| 默认桌面/恢复默认·左栏 | 说明 | 说明 | 14 sp | Normal | SecondaryText | shellBodyStyle |
| 各页右栏 | 所有胶囊 | 大字 | 16 sp | 同上 | 同上 | MenuPill |

「忽大忽小」的来源:① 第一层两行胶囊(16 + 12)与二、三层单行(16)混在同一体系;② 同样是「值」,放得下 16 sp 同行、放不下 12 sp 第二行;③ 左栏说明字号散(15/14/13/12/11);④ 聚焦时 Normal → Medium 字重跳变。

建议(待 Gordon review):胶囊只两级(16/12),值永远同行、放不下省略;聚焦不加粗;左栏只三级(32/14/12)。
