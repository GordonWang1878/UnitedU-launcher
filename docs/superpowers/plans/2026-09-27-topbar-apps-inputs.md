# 顶栏三颗胶囊:设置 / 应用 / 输入源(2026-09-27,Gordon 定)

分支 `feat/topbar-apps-inputs`(基于 main `4bba217`)。裁定号 R89 起(R89 顶栏三颗、R90 应用页、R91 输入源页、R92 去首页输入源行、R93 立即开始屏保)。
另一条分支会用 R90+ 附近的号,合并冲突时以先并入 main 的为准、这边整体顺延改号。

## 需求(Gordon 原话要点)

1. 顶栏胶囊改成 3 个:设置、应用、输入源(顺序如此),去掉屏保按钮。图标沿用顶栏 Material 风格(应用 = apps 网格,输入源 = input)。焦点行为与现有两颗相同(账本 row = -1)。
2. 「应用」= 所有应用页:整屏 MenuBg,标题「应用」,网格放「应用」分组的全部应用卡片(首页卡片画法与卡片图口径,R88),「系统工具」分组放最后并带分组标题(与添加应用列表同一分类 `pickerGroupOf`),按名字排序;确定 = 打开;长按 / MENU = 「加到桌面…」→ 选一行(`Layout.update`,落盘铁律)。已在桌面上的也列出。网格不许用可滚动容器(铁律 1)。
3. 「输入源」:设置外壳同款界面(左标题 + 当前输入源说明,右一列胶囊,当前在用的 ✓);确定 = 切换;长按 / MENU → 改名 / 隐藏;列表末尾「恢复隐藏的输入源」(有隐藏才出现)。
4. 去掉首页输入源行(渲染路径、`showInputRow` 设置、布局组「输入源行」「恢复隐藏的输入源」两行;旧 settings.json 的键按未知键忽略)。
5. 屏保按钮拿掉;「设置 → 屏保」组最上面加「立即开始屏保」;图库为空时提示「先在屏保图库里添加图片」,不进黑屏。
6. 三语文案;英文注意胶囊宽度。

## 设计要点

- **顶栏焦点目标**:`tgtGear: Boolean` → `tgtPill: Int`(-1 = 卡片,0 设置 / 1 应用 / 2 输入源)。与 `tgtRow`/`tgtIdx` 同一套冻结规则(铁律 5:每一个分量分开);还原效果与看门狗按 `tgtPill` 请求对应那颗的 requester,退出判据仍是自报 `focusedCell == (-1, tgtPill)`。
- **应用页**(`AppsPage.kt`):叠在常驻首页之上的整屏浮层(同选择器,首页 `previewing` 让路),状态 `appsPage` 住在 MainActivity、算进 `overlayOpen`。网格 = 纯模型 `appsPageLines`(标题行 / 卡片行 / 分组标题行)+ 自算纵向位移 `appsPageScroll`(焦点行完整可见、上一行是标题时连标题一起露出),`Column.wrapContentHeight(unbounded) + offset`,不滚动。焦点:逐项 requester、目标 `focusedIdx` 与持有者 `holder` 分开、`restoring` 从 ON_PAUSE 冻结、初始定位效果 key `(nonce, covered, requesters)`、`holder == null` 看门狗(3 帧宽限、60 帧封顶)。长按 / MENU 在 MainActivity 识别(同首页长按),菜单用 `GearMenu`(第一层「打开 / 加到桌面…」,第二层每行一颗),`covered` 让路;写盘 `Layout.update(addToRow)`,写完 `revision++`。
- **输入源页**(`InputsPage.kt`):`ShellScaffold` + `CapsuleColumn`(外壳同一套账本),目标 id 住在 MainActivity。「当前在用」= 本次进程里经 UnitedU 最后切过去的那个(系统没有给第三方桌面读「当前输入源」的公开 API);长按 / MENU → `GearMenu`(改名 / 隐藏),改名复用 `TitleDialog` + `Titles.set`,隐藏 `HiddenInputs.set`,「恢复隐藏的输入源」`HiddenInputs.clear`。条件行交接走 `CapsuleColumn` 现成的 id 目标。
- **首页去掉输入源行**:删 `buildInputRow`、`showInputRow`、`RowKind.INPUTS` 及所有只为它存在的分支(卡片菜单 HIDE、输入源文案分流、Move 里的跳过输入源行)。`Settings.showInputRow` 删字段,旧文件里的键按未知键忽略(测试钉住)。
- **立即开始屏保**:屏保组第一行动作行 `startScreensaver`。MainActivity 查图库:空 → toast,不关设置;非空 → 关设置 + `screensaverRequests++`(沿用原屏保按钮的请求效果)。

## 阶段(每阶段一个 commit)

1. 本计划。
2. 去掉首页输入源行 + `showInputRow` + `RowKind.INPUTS`;测试随改。
3. 顶栏三颗(`tgtPill`)+ 输入源页 + 立即开始屏保;应用胶囊先接到应用页的占位入口。
4. 应用页(打开 + 加到桌面);纯模型单测。
5. 文档:CLAUDE.md 焦点责任表、spec R89–R93、DESIGN 删除线;模拟器验证拼图 `docs/screenshots/topbar-apps-inputs.jpg`。
6. `git merge main`、全量测试。

## 验证(emulator-5556)

顶栏三颗逐个进出与返回落点;应用页上下左右走遍、翻页、打开一个应用再返回;输入源页(模拟器输入源可能为空,如实记录);首页三行上下焦点回归;设置 → 屏保「立即开始屏保」有图 / 无图。按键间隔 0.4 s,确定前断言焦点。测完还原模拟器。
