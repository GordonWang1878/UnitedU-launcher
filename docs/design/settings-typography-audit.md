# 设置页字体盘点(2026-09-24 代码读出;2026-09-27 R109 更新)

字体:全部 `Theme.Sans`——拉丁字母/数字 = Google Sans Flex(`res/font/google_sans_flex.ttf`,可变字重);中文不在该字体内,由系统回落到电视自带中文字体。字重 Normal = 400,Medium = 500。

**R109(2026-09-27 Gordon:设置类页面的字小一号,先看效果)**:下表「R109 现值」= 「基准」+ `GtvLayout.SETTINGS_TYPE_STEP`(现为 **−1**)。要「再小一点」改成 −2,「改回去」改成 0,**只改这一个数**。范围:设置外壳每一层、关于页、默认桌面 / 恢复默认确认页、应用页、输入源页;长按卡片菜单、编辑页菜单、首次引导、首页**不跟着变**(它们读基准常量)。胶囊高度 55 dp、宽 268 dp、内边距、行高常量、卡片尺寸等布局尺寸不变;文字行距(lineHeight)同样加这一步。第一层两行胶囊的高度跟着文字走,会矮 1–2 dp(模拟器:「布局」胶囊 57 → 55 dp,「通用」说明折两行的 72 → 68 dp)。对比图 `docs/screenshots/settings-fade-type.jpg`。

常量 / 换算:`GtvLayout.settingsSp(基准)`;胶囊里是 `PillType(step)`(`ShellCapsule.kt`):标签 `MENU_ITEM_TEXT + step`、说明 `MENU_ITEM_HINT_TEXT + step`、右端 › = 标签 × `CHEVRON_SCALE`(1.25,基准下 = 20,与 R71 的「标签 + 4」逐位相同)。

| 页 | 项 | 大字/小字 | 基准 | R109 现值 | 字重 | 颜色/透明度 | 代码 |
|---|---|---|---|---|---|---|---|
| 所有页·左栏 | 页名 | 大标题 | 32 sp(行距 ×1.2) | 31 sp | Medium | EmphasisText | SettingsShell.kt titleStyle |
| 二、三层·左栏 | 路径 | 标题上方 | 16 sp | 15 sp | Normal | SecondaryText | SettingsShell.kt pathStyle |
| 布局/外观·左栏 | 预览提示行 | 说明 | 14 sp / 行距 20 | 13 / 19 | Normal | SecondaryText | SettingsShell.kt shellBodyStyle |
| 第一层 | 胶囊名字 | 大字 | 16 sp | 15 sp | 未聚焦 Normal / 聚焦 Medium | MenuItemText / 聚焦对比色 | ShellCapsule.kt MenuPillLabel |
| 第一层 | 胶囊说明 | 小字(第二行) | 12 sp | 11 sp | Normal | 文字色 70% | ShellCapsule.kt hint |
| 第一层 | › | 符号 | 20 sp | 18.75 sp | Normal | 文字色 55% | TrailingContent |
| 第二层 | 名字 | 大字 | 16 sp | 15 sp | Normal / 聚焦 Medium | 同上 | MenuPillLabel |
| 第二层 | 值(同一行右端) | 大字 | 16 sp | 15 sp | Normal | 文字色 55% | TrailingContent Value |
| 第二层 | 值放不下时(挪到第二行) | 小字 | 12 sp | 11 sp | Normal | 文字色 70% | valueWraps → hint(按现值字号量宽) |
| 外观 | 滑块聚焦:名字/数值 | 大字 | 16 sp | 15 sp | Medium | 文字色 | SliderContent |
| 外观 | 滑块 ‹ › | 符号 | 16 sp | 15 sp | Normal | 45%(到头 15%) | SliderContent |
| 第三层 | 选项名 | 大字 | 16 sp | 15 sp | Normal / 聚焦 Medium | 同上 | MenuPillLabel |
| 第三层 | ✓ | 符号 | 16 sp | 15 sp | Medium | 主题色 / 聚焦黑 | TrailingContent Check |
| 关于·左栏 | 版本号 | 正文 | 15 sp | 14 sp | Normal | EmphasisText | AboutScreen.kt |
| 关于·左栏 | 检查更新标题行 | 正文 | 14 sp | 13 sp | Medium | — | AboutScreen.kt |
| 关于·左栏 | 检查结果 | 正文 | 13 sp | 12 sp | Normal | 状态色 | AboutScreen.kt |
| 关于·左栏 | 更新说明 | 小字 | 12 sp / 行距 17 | 11 / 16 | Normal | — | AboutScreen.kt |
| 关于·左栏 | 许可声明标题 | 小字 | 11 sp | 10 sp | Medium | HintText | AboutScreen.kt |
| 关于·左栏 | 许可正文、项目地址 | 小字 | 11 sp(正文行距 16) | 10 sp(15) | Normal | FootnoteText | AboutScreen.kt |
| 默认桌面·左栏 | 「当前」 | 小字 | 11 sp | 10 sp | Normal | HintText | HomeSettingsCard.kt CurrentHomeRow(textStep) |
| 默认桌面·左栏 | 当前桌面名 | 正文 | 15 sp | 14 sp | Medium | — | 同上 |
| 默认桌面/恢复默认·左栏 | 说明 | 说明 | 14 sp | 13 sp | Normal | SecondaryText | shellBodyStyle |
| 应用页 | 页名「应用」 | 大标题 | 32 sp | 31 sp | Medium | EmphasisText | AppsPage.kt titleStyle |
| 应用页 | 标题右边的提示 | 说明 | 14 sp | 13 sp | Normal | SecondaryText | shellBodyStyle |
| 应用页 | 「系统工具」分组标题 | 正文 | 18 sp | 17 sp | Medium | SecondaryText | AppsPage.kt |
| 应用页 | 卡片名 | 小字 | 14 sp(= 首页小档) | 13 sp | Normal | 60% | AppsPage.kt metrics.copy(titleSize) |
| 输入源页 | 页名 / 说明 / 胶囊 | — | 32 / 14 / 16 | 31 / 13 / 15 | 同外壳 | 同外壳 | InputsPage.kt(ShellTitle / shellBodyStyle / CapsuleColumn) |
| 各页右栏 | 所有胶囊 | 大字 | 16 sp | 15 sp | 同上 | 同上 | CapsuleColumn → MenuPill(textStep) |

「忽大忽小」的来源(2026-09-24 盘点时):① 第一层两行胶囊(16 + 12)与二、三层单行(16)混在同一体系;② 同样是「值」,放得下 16 sp 同行、放不下 12 sp 第二行;③ 左栏说明字号散(15/14/13/12/11);④ 聚焦时 Normal → Medium 字重跳变。R109 只整体平移一步,不改这四点(层级关系不变)。

建议(2026-09-24,待 Gordon review,R109 未采纳也未否决):胶囊只两级(16/12),值永远同行、放不下省略;聚焦不加粗;左栏只三级(32/14/12)。
