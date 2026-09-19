# M4b 行管理 · 原地移动 · 输入源 —— 设计(spec)

> 上游:`docs/DESIGN-unitedu-open-source.md` §2(应用行 1–5 行、名字、图标 id;输入源可隐藏 / 改名;移动位置 = 首页原地移动态);`docs/superpowers/specs/2026-09-15-m4-card-menu-design.md` §0(M4 / M4b 切分);`docs/superpowers/specs/2026-09-16-m7-settings-design.md` §2(「行数」归 M4b 编辑页:行的增删就是改行数);WORKLOG 2026-09-19(Gordon 定「先把 bug 修完、功能欠缺做完,任何 UI 美化先不做」)。
>
> **状态**:2026-09-19 无人值守窗口里起草。DESIGN §2 已经定死的照搬;DESIGN 没说的交互细节是我按规格做的**裁定(Ruling)**,集中在 §0,每条写了「错了的代价」。**Gordon 2026-09-19 16:45 过目后确认「按现在的方案做」**(Rulings A–M 全部照此实施)。
>
> 代码事实(引文件:行)来自 2026-09-19 的代码调查;真机输入源来自同日 A95L `dumpsys tv_input`(只读)。

## 0. 决定表

| # | 决定 | 内容 | 来源 |
|---|---|---|---|
| 1 | 范围 | 应用行 1–5 行:增、删、改名、换图标、上下移;首页原地移动;输入源逐项改名 / 隐藏 / 恢复;HDMI-CEC 父子去重。顺带修三处编辑页 / 输入源的遗留:编辑页 `verticalScroll`(铁律 1)、编辑页初始焦点落在「+」、输入源卡长按后按压态不释放 | DESIGN §2 + WORKLOG 遗留;「行上下移」是 Ruling A |
| 2 | 行管理入口 | **编辑页每行行尾的「+」卡改为打开「行菜单」**:添加应用 / 重命名此行 / 更换此行图标 / 此行上移 / 此行下移 / 在下方新建一行 / 删除此行(不适用的项不列出)。首页不加任何行管理入口 | Ruling B |
| 3 | 新建行 | 插在当前行下方;默认名 = 本地化「新行」/「新行」/「New Row」,默认图标 `apps`;建好后焦点落在新行的「+」。空行照旧只在编辑页可见(首页只显示非空行,`HomeScreen.kt:709-723` 的不变量不动) | Ruling C |
| 4 | 删行 | 只剩 1 行时不列「删除此行」;空行直接删;非空行先弹确认框(`ConfirmDialog`,默认焦点在取消):「删除『行名』?这一行的 N 个应用会从桌面移除,不会卸载。」删后焦点落在上一行的「+」(删的是第 1 行则落新的第 1 行的「+」) | Ruling D |
| 5 | 行数 | 上限 5:已有 5 行时不列「在下方新建一行」。行数 = `layout.json` 里的行数;`Settings.rowCount`(`Settings.kt:20`,全仓无人读)不启用、不删,KDoc 标注「不用,行数以 layout.json 为准」 | M7 spec §2 + Ruling E |
| 6 | 行图标 | 12 个内置图标,存稳定 id 进 `layout.json` 每行的 `"icon"`:`movie` 影片、`tv` 电视、`live` 直播、`music` 音乐、`games` 游戏、`kids` 儿童、`tools` 工具、`education` 学习、`sports` 运动、`news` 资讯、`photos` 相册、`apps` 应用。**没有 `icon` 字段的旧行按原来的名字匹配**(VIDEO → `movie`、LIVE → `tv`、MUSIC → `music`,其余 `tv`),渲染结果与今天逐像素相同。选择器 = 4×3 图标网格浮层 `RowIconPicker`,当前图标预先聚焦 | DESIGN §2(约 12 个、存 id)+ Ruling F(具体 12 个) |
| 7 | 行改名 | 复用「修改标题」对话框(`TitleDialog.kt`,系统输入法),改成与卡片无关的通用签名;确定保存,**清空 = 不改**(行名不能为空),长度上限沿用 `MAX_TITLE_CHARS` = 40 | DESIGN §2(自由改,系统输入法)+ Ruling G |
| 8 | 行上下移 | 与相邻行交换;焦点跟着这一行走(落在它的「+」) | Ruling A |
| 9 | 原地移动 | 长按菜单「移动位置」→ 首页进入移动态:被移动的卡保持聚焦,屏幕底部一行提示「← → 移动 · ↑ ↓ 换行 · 确定 放下 · 返回 取消」;左右与同行邻卡换位(到头不动),上下移到相邻的**首页可见应用行**的同一列(越过行尾则放到行尾;跳过输入源行);确定 → 写盘并退出;返回 → 原样放回并退出;按 HOME、进待机、任何浮层要打开 → 等同取消。移空的行在首页随即消失(与今天空行不显示一致),取消时复原。旧的「跳编辑页定位」兜底删除 | DESIGN §2 + Ruling H(细节) |
| 10 | 原地移动的视觉 | 只加两样:被移动卡片 3dp accent 描边(与聚焦描边同粗,换成主题 accent 色以区分「搬运中」),底部提示条(沿用现有提示文字样式)。不做抬起动画、不做阴影 | Ruling I(「UI 美化先不做」:只做功能必需的可见状态) |
| 11 | 输入源卡长按菜单 | 打开 / 改名 / 隐藏(三项,顺序如此)。改名走同一个对话框,**清空 = 恢复系统名**;隐藏后卡片立即消失,toast「已隐藏『X』,可在 设置 → 布局 → 恢复隐藏的输入源 找回」 | DESIGN §2(可隐藏、可改名,复用修改标题)+ Ruling J |
| 12 | 恢复隐藏 | 设置页「布局」组在「输入源行」开关下加一行动作「恢复隐藏的输入源」(有隐藏项时显示「N 个」,没有时整行不显示);按下全部恢复并 toast。**不做逐项管理列表** | Ruling K |
| 13 | 输入源数据 | **改名存进 `titles.json`**(key = 输入 id,如 `com.mediatek.external/.HdmiInputService/HW3`,带 `/` 不会与包名撞):与卡片「修改标题」同一个文件、同一条保存路径(`Titles.set`),清空即恢复系统名。**隐藏存进新文件 `hidden-inputs.json`**,平铺对象 `{"<输入 id>":"hidden"}`,复用 `titles.json` 的纯函数解析 / 序列化(`parseTitles` / `titlesToJson`,JVM 可测)与同一套读写姿势。两者都**不被「恢复默认」清掉** | Ruling L |
| 14 | CEC 去重 | 纯函数:某个输入若是任何其它输入的 `parentId`,就不列它(只列它下面的子设备,子设备名如「PlayStation 5」比「HDMI 1」有用);只看一层。A95L 2026-09-19 实测只有 HW0–HW5 六个硬件输入、没有 CEC 子输入,**真机上现在看不到去重效果**,以 JVM 单测为证 | DESIGN §2 + Ruling M(留子不留父) |
| 15 | 编辑页滚动 | `EditScreen.kt:280` 的 `verticalScroll(rememberScrollState())` 换成自算位移(铁律 1):视窗内容按当前焦点行整块平移,5 行 × 大档卡片也能到达每一行 | 铁律 1 |
| 16 | 编辑页初始焦点 | 进编辑页落在第 1 行第 1 张卡(第 1 行空则落它的「+」),不再落在第 1 行的「+」 | WORKLOG M7 遗留 |
| 17 | 不做 | 首页行尾「+」卡(归 gtv 线)、行的颜色 / 背景、逐项隐藏管理列表、多级 CEC 链、行管理的撤销、拖动动画 | — |

**Rulings(给 Gordon 过目)**

- **A 行上下移**:DESIGN 只写「增删 / 命名 / 图标」,我把「上下移」也做了——行序对用户有意义,而菜单里多两项的成本很低。错了的代价:删两个菜单项。
- **B 入口放在「+」**:不给编辑页加任何新的可聚焦节点(铁律 3/5 的账本不用动),空行也够得着;代价是「添加应用」多按一下(先出菜单)。错了的代价:改回「+」直接开选择器,另找行菜单入口(比如行标题可聚焦),要重做编辑页焦点账本。
- **C 新建后不自动弹改名**:少一次浮层衔接(菜单 → 新建 → 对话框)的焦点风险;代价是用户多按一次「重命名此行」。
- **D 非空行删前确认**:删行会让一串应用离开桌面,值得一次确认;空行没有损失,直接删。
- **E `rowCount` 不启用也不删**:删掉要动设置文件格式与 6 处测试,换不来任何用户价值。
- **F 12 个图标的具体清单**:按国行电视常见分类挑的;全部来自已依赖的 `material-icons-extended`。错了的代价:换 id 对应的图标,数据不受影响。
- **G 行名清空 = 不改**:行必须有名字(首页行标题),空名没有意义。
- **H 原地移动细节**:见决定 9。最有争议的是「跳过输入源行」「移空的行立即消失」「按 HOME 等于取消」——都是为了不让移动态和别的状态机交叉。
- **I 移动态视觉只加描边 + 提示条**:Gordon 定「UI 美化先不做」,这两样是让人知道「现在在搬运」的最小必要。
- **J 输入源改名清空 = 恢复系统名**:与卡片「修改标题」清空 = 恢复应用名一致。
- **K 只做「全部恢复」**:逐项管理要一个新的列表浮层(新焦点账本),功能收益小;先用一键恢复兜住「全藏光了、输入源行消失、没有地方长按」这条死路。
- **L 名字进 `titles.json`、隐藏进 `hidden-inputs.json`**:改名与卡片「修改标题」同一条路(真正的「复用修改标题」);两者都是用户数据,和卡片标题一样不该被「恢复默认」抹掉。
- **M 留子不留父**:子设备名更有用;切到子输入时系统会切到它所在的 HDMI 口并按 CEC 唤醒设备。真机现在无 CEC 子输入,等 Gordon 接了 CEC 设备再验。

## 1. 数据

### 1.1 `layout.json`

现在:`{"rows":[{"name":"VIDEO","apps":["com.a",…]},…]}`(`Layout.kt:28-31`)。M4b 起每行多一个可选字段 `"icon"`:

```json
{"rows":[{"name":"影视","icon":"movie","apps":["com.a"]},{"name":"LIVE","apps":[]}]}
```

- 读:`icon` 缺失 / 非法 id → `null`,渲染时按 §0-6 的旧名字匹配回落。写:`icon == null` 时不写该字段(老数据原样往返)。
- 行的类型从 `Pair<String, List<String>>` 换成 `data class LayoutRow(val name: String, val icon: String?, val apps: List<String>)`;`DEFAULT_LAYOUT` 三行的 icon 为 `null`(保持今天的外观)。所有读写点(`Layout`、`EditScreen`、`HomeScreen.buildRows`、`OnboardingPure`、`MainActivity` 的移除 / 移动)跟着换类型。
- 行操作全部是 `Layout` 上的**纯函数**(JVM 单测):`addRowBelow(rows, index, name)`、`deleteRow(rows, index)`、`renameRow(rows, index, name)`、`setRowIcon(rows, index, icon)`、`swapRows(rows, a, b)`;行数边界(1..5)在纯函数里挡住(越界返回原 list)。

### 1.2 输入源的名字与隐藏

- 名字:`titles.json`,key = 输入 id。首页输入源行的标签 = `titles[id] ?: 系统标签`(今天输入源行不读 titles,`HomeScreen.kt:656` 只给 APPS 行用——M4b 起输入源行也读,但**只取名字,不受「卡片标题」开关影响**:改名是改卡片上那几个字,输入源卡本来就是文字卡)。
- 隐藏:`Paths.hiddenInputsJson(ctx) = File(base, "hidden-inputs.json")`,`{"<id>":"hidden"}`;`HiddenInputs.read/write/set(ctx, id, hidden)`,照 `Titles` 的姿势。
- 纯函数 `applyInputPrefs(entries, hidden: Set<String>, names: Map<String, String>)`:先去隐藏、再换名。

### 1.3 CEC 去重

`Inputs.load` 读 `TvInputInfo.parentId`,`InputEntry` 加 `parentId: String?`。纯函数 `dedupeCec(entries)`:`val parents = entries.mapNotNull { it.parentId }.toSet(); entries.filter { it.id !in parents }`。

## 2. 编辑页

- 行尾「+」→ 行菜单(`GearMenu`,与卡片的 acting 菜单同一个浮层机制、同一套焦点让路)。「添加应用」打开现有 `AppPicker`。
- 行标题前画该行图标(`RowIcon` 改为按 id 取图标,回落见 §0-6),与首页一致。
- 自算纵向位移替换 `verticalScroll`:焦点行变化时整块平移,让焦点行完整可见(与图片网格的 `keepInView` 同一规则,按行高 + 行距算)。
- 初始焦点:`initialTarget == null` 时目标 = (0, 0),第 1 行为空时 (0, 0) 就是「+」。
- 焦点责任表新增两行:行菜单(GearMenu 自带初始循环 + 看门狗)、行图标选择器(自己的 nonce 循环 + 逐项 requester);删行确认框复用 ConfirmDialog。

## 3. 首页原地移动

- 状态:`MainActivity` 持有 `moving: MoveState?`——`MoveState(pos: MovePos, rows: List<Row>, original: List<Row>)`,`rows` 是**首页已渲染的行**的工作副本(不重读磁盘、不重解码图标,每一步只是列表重排)。被移动的卡**按位置 `MovePos(row, col)` 追踪,不按包名找**:同一个包允许出现在两行里(`Layout.withoutPackage` 就是按多行设计的),按包名找会找错那一张。
- 按键:移动态下 `MainActivity.dispatchKeyEvent` 先于 Compose 截获方向键 / 确定 / 返回(同今天长按检测的位置),改工作副本,再把首页的目标格(`tgtRow/tgtIdx`)设成这张卡的新位置并 `focusNonce++`,由首页现成的还原效果把焦点落过去。其它键吞掉。
- 纯函数(JVM 单测):`moveCard(rows, pos, dir): Pair<List<Row>, MovePos>`,`dir ∈ {LEFT, RIGHT, UP, DOWN}`,规则见 §0-9;输入源行(`RowKind.INPUTS`)永远不是目标行;源行被移空时从工作副本里去掉,目标行号随之校正。写回用纯函数 `mergeMove(disk, original, working)`。
- 放下:工作副本按每行的 `layoutRow` 写回 `layout.json`;该行里**首页没显示的包**(未安装的)保持在原行、排在可见包之后。取消:丢弃副本,首页用 `original` 重画。
- 看门狗:移动态下目标就是被移动的卡;浮层一律不开(长按、MENU、齿轮、屏保按钮在移动态下都被吞掉)。

## 4. 输入源

- 首页输入源行:`Inputs.load` → `dedupeCec` → `applyInputPrefs`(隐藏的去掉、改过名的换名,名字取自 `titles.json`)。全部隐藏 → 这一行不渲染(与今天「没有输入」同一条路)。
- 长按输入源卡 → 菜单(打开 / 改名 / 隐藏):`CardAction` 加 `HIDE`,`cardMenuActions(RowKind.INPUTS) = [OPEN, RENAME, HIDE]`;「打开」按种类分流到 `Inputs.launch`;「改名」就是现有的 RENAME(`TitleDialog` + `Titles.set(ref.pkg)`,输入源卡的 `pkg` 本来就是输入 id)。菜单打开时焦点离开卡片,按压态随失焦释放(WORKLOG M8 遗留的现象由此消失;真机验)。
- 设置页「布局」组:`showInputRow` 下加一行动作「恢复隐藏的输入源」(隐藏数为 0 时不显示)。

## 5. 验收(模拟器能验的在模拟器验;输入源与 CEC 只能真机)

1. 编辑页:新建到 5 行后「新建」消失;删到 1 行后「删除」消失;改名、换图标、上下移后首页行标题 / 图标 / 行序一致;重启应用后仍在。
2. 编辑页 5 行 × 5 张 / 行(大档)时每一行都能到达,焦点从不丢;进编辑页焦点在第 1 张卡。
3. 首页原地移动:同行左右、跨行上下、移空一行、确定写盘(重启后仍在)、返回复原、移动中按 HOME 复原。
4. 输入源(A95L):长按菜单三项;改名后首页显示新名、清空恢复系统名;隐藏后卡消失,设置里一键恢复;全部隐藏后输入源行消失、恢复后回来。
5. 单测:行操作纯函数、行图标 id 回落、`hidden-inputs.json` 往返、`dedupeCec`、`applyInputPrefs`、移动纯函数与写回合并。
