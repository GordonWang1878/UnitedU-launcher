# 设置页胶囊外壳改版 Implementation Plan

> **For agentic workers:** 分四阶段,每阶段单独构建 + 单测通过后一个 commit。Steps 用 checkbox(`- [ ]`)记进度。

**Goal:** 所有设置界面换成统一外壳——左右 1:1,左边标题或实时预览,右边永远是一列 268×55 dp 全圆角胶囊(`GearMenu.MenuPill` 同款),点胶囊进下一层、返回回上一层并落回进入时那颗胶囊。旧的「UnitedU 设置」两栏浮层、齿轮菜单第一层(4 项)、「默认桌面」卡、「恢复默认」对话框整体退役。

**设计来源:** Gordon 2026-09-23 定案(派单原文 7 条)+ `docs/design/settings-shell/README.md` 与 M1–M4 效果图。与效果图的出入(Gordon 定案优先):第一层 6 项且保留说明小字(M1 是 7 项无说明),「编辑分栏」挪进「布局」第一项;滑块改动即时保存,不显示「预览:…按返回不改」那行(M4 画了,但那行对滑块是假话,见 R70)。

**Tech Stack:** Kotlin、Jetpack Compose(BOM 2024.10.01)、tv-material 1.0.0、JUnit 4。

## Global Constraints

- 工作区 `/Users/gordonwang/GitHub/UnitedU-wt-shell`(分支 `feat/settings-shell`),不 push、不碰电视;模拟器只用 `emulator-5556`(AVD `unitedu-tv`)。
- `CLAUDE.md` 铁律 1–7 约束每一处 Compose 改动:任何胶囊列不滚动;焦点只信自报;每层自己负责初始焦点 + 看门狗;目标与当前分开、`ON_PAUSE` 冻结;守卫与 key 成对;不用一次性布尔闩。
- 设置写盘一律 `SettingsStore.update`(已有锁)。
- 三套字符串同步(values / values-zh-rTW / values-en,英文 Title Case);新字符串追加在各文件末尾的 `<!-- settings shell -->` 块。
- 构建:`source scripts/env.sh && gradle --no-daemon assembleRelease`;单测:`rm -rf app/build/test-results && gradle --no-daemon testReleaseUnitTest`(基线 365)。
- 不 add `docs/WORKLOG.md`。

## 架构决定

### A. 导航栈提到 MainActivity(取代 `settings: Boolean` + `SettingsPos`)

`shellStack: List<ShellFrame>`,`ShellFrame(page: String, focus: String?)`;空 = 设置关着。页 id:`root`、`g:LAYOUT|GENERAL|APPEARANCE|SCREENSAVER`、`o:<行 id>`(选项层)、`home`(默认桌面)、`restore`(恢复默认确认)。
- `focus` 是**这一层的焦点目标**(行 / 胶囊 id,不是下标):跟着用户导航走,还原期间冻结;进下一层时它原样留在父帧里,返回时父层按它落焦 → 「返回落回进入时那个胶囊」不需要任何额外记号。
- 用 id 不用下标:条件行(「恢复隐藏的输入源」「动画缩放」)出现 / 消失时目标自动跟着那一行走;那一行自己消失了,退回原下标夹取(同 R57 的「按行 id 重映射」)。
- 栈提到 Activity 的理由:①「编辑分栏」进的是整屏编辑页,编辑页开着时外壳不在组合里,返回后要回到同一颗胶囊;②切语言 `recreate()` 要整栈跨过去(`onSaveInstanceState` 编码成一个字符串,规则仍是只在 `selfTriggeredRecreate` 时种回,`SettingsRestorePolicy` 不变);③HOME 一处清空。
- 纯函数(`ShellModel.kt`,JVM 单测):`shellPush` / `shellPop` / `shellSetFocus` / `encodeShellStack` / `decodeShellStack` / `defaultFocus` / `pageHasPreview`。

### B. 胶囊列的焦点账本(`CapsuleColumn`,每层一个,`key(页)` 重建)

- 逐项 `FocusRequester`(铁律 3),`holder` 只信自报(铁律 2/4)。
- 定位效果 key `(focusNonce, covered, ids)`:`covered` → 冻结返回;否则循环请求目标直到**目标自报**落下(60 帧封顶),末尾只在 RESUMED 时放开 `restoring`(同 SettingsScreen 终审 I1)。
- 看门狗 key `(holder == null, covered, restoring, focusNonce)`,三条守卫全在 key 里(铁律 6);3 帧宽限、60 帧封顶,再丢焦点 key 翻转自动重新武装(铁律 7)。
- `ON_PAUSE` 起冻结目标;组合阶段发现 id 清单变了(条件行出现 / 消失)**同步**置 `restoring`,挡住节点被摘那一帧的意外上报(M4b fix round 1 同一手法)。
- 上下到头 `Cancel` 锁住;左右恒 `Cancel`,滑块胶囊在 `onKeyEvent` 里消费左右键调值——焦点不出胶囊列。

### C. 实时预览 = 把常驻首页那一层整体缩小(不另画一份首页)

首页本来就常驻在设置之下(M7 T4 分层叠加),且在 `previewing` 下不可聚焦、看门狗让路、不收按键。所以预览**不复用绘制代码,而是复用那一层本身**:MainActivity 把「壁纸 + 屏保层 + 黑层 + HomeScreen」包进一个 Box,外壳开在有预览的页时给它一个 `graphicsLayer`:`transformOrigin (0,0)`、缩放 `400 dp / 屏宽`、平移到预览框、`clip` + 圆角(本地坐标 `8 dp / 缩放`);没预览的页 `alpha = 0`。外壳自己的底色 `MenuBg` 铺在这一层**之下**,外壳内容(透明底)在之上,描边由外壳在预览框位置画。
- 焦点:零新增——HomeScreen 的 `previewing` 早已让它不可聚焦、不注册进任何账本(本来就是这样叠在设置页底下的)。
- 性能:零新增组合;缩放只在绘制层。「预览只在需要时组合」自然成立(它一直在组合里,只是换了画法)。
- 预览框几何是常量(不靠 `onGloballyPositioned` 测量,免得第一帧整屏闪一下):左 40 dp、宽 400 dp、高 = 400 × 屏高 / 屏宽(1080p = 225 dp)、顶 = 屏高 / 2 − 90(1080p = 180 dp);标题块压在它上方、说明行在它下方且始终占位。纯函数 `previewRect()` 两边共用。

### D. 预览—保存—放弃状态机(纯函数)

- 选项层(`o:<行>`)的焦点目标就是「光标所在选项」`opt:<i>`。`effectiveSettings(saved, stack)`:栈顶是选项层、行在 `PREVIEW_ROW_IDS`(卡片大小 / 卡片标题 / 输入源行 / 主题色 / 跟随壁纸主色)、光标 ≠ 已保存档 → 返回 `optionWrite(行)(saved, i)`;否则 `saved`。MainActivity 用它喂首页、主题色、卡片淡化——光标移到哪,预览就是哪。
- 进选项层初始焦点 = 已保存档(✓),所以一进去没有未保存预览。
- **确定** = `ControlRow.onSelect(i)`(走 `SettingsStore.update`;语言走 `applyLanguage`)+ 弹回父层;**返回** = 只弹栈,什么都不写,`effectiveSettings` 自然回到已保存值。
- 滑块(R70):左右键每一格即时 `SettingsStore.update` 落盘(与原壁纸滑块一致),壁纸重处理照旧 300 ms 防抖;确定键在滑块上无动作;返回 / 离开胶囊不撤销。

### E. 卡片饱和度 / 亮度(新设置)

`Settings.cardSaturation`(0–100 步 10,缺省 30)、`cardBrightness`(50–100 步 5,缺省 75),settings.json 新键;旧文件缺键 = 30 / 75 = 原 `GtvLayout.CARD_FADE_*`,观感零变化。`gtvCardFade(fade)` 改成 `ModifierNodeElement` 数据类(参数相等 → 元素相等,GtvGlowTest 的判别前提不变),`LocalCardFade` 在 MainActivity 顶层按有效设置提供;首页 / 编辑页的 `AppCard` 与长按菜单 banner 读它。

## Phase 1:模型 + 卡片淡化设置(不动界面结构)

**Files:** `Settings.kt`、`SettingsModel.kt`、`ShellModel.kt`(新)、`AppCard.kt`、`GearMenu.kt`、`MainActivity.kt`(只加 `LocalCardFade` 提供与 `openEdit` 接线)、`SettingsScreen.kt`(只是让新行在旧界面里也能画)、三套 strings;测试 `SettingsTest`、`SettingsModelTest`、`ShellModelTest`(新)、`GtvGlowTest`。

- [ ] Settings:两个新字段、解析夹取、`toJson`;测试缺键 = 30/75、夹取 / 取整、往返。
- [ ] `CardFade` 数据类 + `cardFadeMatrix(fade)`;`Modifier.gtvCardFade(fade)` 改 Node;`LocalCardFade`;AppCard / GearMenu banner 读它。
- [ ] SettingsModel:布局组首行 `editLayout`(动作行 → `actions.openEdit`);外观组末尾两条滑块 `cardSaturation` / `cardBrightness`;`ControlRow` 加 `write`(纯函数,`onSelect` 由它派生),`optionWrite(id)` 表;壁纸两条滑块改名「壁纸模糊 / 壁纸亮度」。
- [ ] ShellModel:页 id、栈操作、编解码、`defaultFocus`、`optionOrder`(卡片大小显示成 小 / 中 / 大)、`effectiveSettings`、`sliderStep`、`sliderText`、`capsuleGap`、`previewRect`、第一层条目表。
- [ ] 构建 + 单测 → commit「settings-shell phase 1」。

## Phase 2:外壳本体 + 路由 + 预览层

**Files:** `ShellCapsule.kt`(新:从 GearMenu 抽出的 `MenuPill` + 值 / ✓ / › / 滑块形态)、`SettingsShell.kt`(新:`CapsuleColumn`、`ShellScaffold`、各页)、`MainActivity.kt`、`HomeScreen.kt`(删齿轮菜单那层)、`GearMenu.kt`(改用共享 `MenuPill`,删 `showHints`)、删 `SettingsScreen.kt`、`HomeSettingsCard.kt` 只留 `rememberCurrentHome` / `CurrentHomeRow`;strings。

- [ ] `MenuPill` 抽出,GearMenu 单行外观像素级不变。
- [ ] `CapsuleColumn`(见架构 B)、`ShellScaffold`(左右 1:1)。
- [ ] 页:第一层(6 颗两行胶囊,缺省焦点「布局」)、四个分组页、选项层、默认桌面页、恢复默认确认页。
- [ ] MainActivity:`shellStack` 取代 `settings` / `settingsPos` / `confirmRestore` / `settingsReloadNonce` / `menuOpen` / `demoIdle`;齿轮药丸与 MENU 键打开第一层;预览层 `graphicsLayer`;`effectiveSettings`;壁纸参数防抖挪到这里;Bundle 编码栈;HOME 全收(含从设置里打开的选择器)。
- [ ] 构建 + 单测 → commit。

## Phase 3:关于页换壳

- [ ] `AboutScreen` 改用 `ShellScaffold` + 单颗胶囊的 `CapsuleColumn`;状态机 `AboutController` 不动;返回键语义不变(下载中 = 取消下载)。
- [ ] 构建 + 单测 → commit。

## Phase 4:验证 + 文档

- [ ] 模拟器 5556 逐层截图(中英两套第一层 + 布局层,卡片大小第三层光标在「大」,外观滑块聚焦,关于,恢复默认确认,默认桌面),拼 `docs/screenshots/settings-shell-implemented.jpg`。
- [ ] 焦点回归清单(派单「验证」一节逐条),读 settings.json 断言预览不写盘、确定才写。
- [ ] CLAUDE.md 焦点责任表:删「设置页两栏」「确认框(恢复默认)」,改「齿轮菜单 / 长按卡片菜单」「关于页」「图片选择器」,加外壳各层。
- [ ] spec §6 / §8 / §12 补 R67 起的裁定,删除线标注被推翻的旧设置页结构。
- [ ] 模拟器还原 → commit。
