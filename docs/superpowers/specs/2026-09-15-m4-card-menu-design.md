# M4 设计:长按卡片菜单 + 卡片标题 + 新应用标记

> **状态:已实施(commit 8cc5134),待真机验。**
> 上游:`docs/DESIGN-unitedu-open-source.md` §2(卡片、长按菜单、移动位置、新装应用)、§6、§11 M4 行;M2 收官时推给 M4 的四项里,本文只收「卡片标题渲染」,其余三项(行管理、CEC 去重、输入源隐藏/改名)归 **M4b**(另立 spec)。
> 焦点铁律:根 `CLAUDE.md` 七条。

## 0. 范围与 Gordon 已定项

| 决策 | 结论 |
|---|---|
| 范围切分 | **M4 = 长按菜单 + 卡片标题渲染 + 新应用标记;M4b = 行数 1–5 与行的增删/命名/图标 + HDMI-CEC 父子去重 + 输入源逐项隐藏/改名 + 原地移动**。两段分开评审、分开真机验 |
| 「移动位置」 | **M4 只做兜底**:长按 →「移动位置」跳编辑页并定位到这张卡;原地移动态(卡片抬起、左右换位、上下换行)后置到 M4b 之后,等长按菜单真机验过再做 |
| 卡片标题位置 | **卡片下方一行小字**(Projectivy 同款,不遮横幅);标题开关打开时行高增加,纵向位移沿用自算逻辑 |

**做**:首页长按卡片菜单六项(打开 / 卸载 / 修改标题 / 更改图标 / 移动位置(兜底)/ 从当前分类移除)、卡片标题(全局开关 + 自定义标题 + 无横幅回落自动标题)、新应用标记(首页角落计数 + 添加列表「新」标)、设置页补回「卡片标题」开关。
**不做**(带去向):原地移动(M4b 之后)、行管理(M4b)、输入源卡片的长按菜单(M4b)、编辑页条目菜单加「修改标题」(polish,按需)。

## 1. 长按检测与卡片菜单

- **检测在 `MainActivity.dispatchKeyEvent`**(今日已在此吞掉确定键的重复事件):首页无浮层(`!editing && !settings && !menuOpen && pickerTarget == null && cardMenu == null`)且 `HomeScreen` 上报的当前焦点卡非空时,确定键 / Enter 的 `ACTION_DOWN` 且 `repeatCount > 0` 且 `eventTime - downTime ≥ 600 ms`(2026-09-16 真机定 0.6 s;原稿 `repeatCount == 1` ≈ 0.4 s,Gordon 试后嫌短,且首次重复延迟随固件而异,改按时长判)→ 记 `longPressDownTime = event.downTime`、置 `cardMenu = 当前焦点卡`,返回 true;**同一 `downTime` 的后续事件(含 UP)全部吞掉**——Compose `clickable` 在 UP 才触发,所以不会顺带启动应用。`repeatCount > 1` 照旧吞。
- **当前焦点卡**:`HomeScreen` 本来就为看门狗记着 (行索引, 列索引)(`focusedCell`);新增回调 `onFocusedCard: (CardRef?) -> Unit`,`CardRef(rowIndex, colIndex, layoutRow, kind, pkg, label)`(`rowIndex/colIndex` 是渲染坐标,`layoutRow` 是 `layout.json` 行号、输入源行为 −1)。上报值**只派生、不缓存**:永远等于 `cardAt(focusedCell)` 对当前 `rows` 的求值——焦点事件时算一次,数据重载(`loaded` 变化)时再算一次;齿轮 (−1,−1) 派生为 null。缓存一份只在焦点事件时重报会过期:卡片按位置组合(无 `key()`),移除/卸载后节点原地换卡却没有焦点事件,长按会弹出被移除那张的菜单(终审修复波次 Important #1)。`MainActivity` 用 `mutableStateOf<CardRef?>` 存。
- **菜单 UI 复用 `GearMenu`**(齿轮菜单与编辑页条目菜单都在用它):`cardMenu != null` 时 `HomeScreen` 里叠一层 `GearMenu(items, onDismiss, nonce)`,标题为该卡显示名。**零新焦点模式**:`GearMenu` 自带焦点账本;首页看门狗与齿轮菜单打开时同一处理——`cardMenu != null` 时让路(守卫与 key 成对,铁律 6)。关闭菜单 `focusNonce++`,焦点回到那张卡(现有「记住的行/列」机制)。
- 菜单项(顺序即设计 §2):

| 项 | 动作 |
|---|---|
| 打开应用 | `Apps.launch(ctx, pkg)` |
| 卸载应用 | `startActivity(Intent(ACTION_DELETE, "package:$pkg"))` 走系统确认页;卸载完成靠现有 `PACKAGE_REMOVED → revision++`,`buildRows` 的 `mapNotNull` 让卡片消失;`layout.json` 里的包名留着(重装即复现,与今日行为一致) |
| 修改标题 | 打开 `TitleDialog`(§3) |
| 更改图标 | 现有 `pickIcon(pkg)`(`IconPicker`) |
| 移动位置 | `editTarget = (layoutRow, pkg)`、`editing = true`;`EditScreen` 新参数 `initialTarget: Pair<Int, String>?`(= `(layout.json 行号, 包名)`,见下方「坐标口径」),数据加载完成后用 `indexOf(pkg)` 解出列号、调用它自己的 `retarget(ri, col)` 一次(该函数已是「所有重定位的唯一入口」);`MainActivity` 在 `leaveEdit()` 时清 `editTarget` |
| 从当前分类移除 | `Layout` 里该行去掉 pkg → `Layout.write` → `revision++`;焦点落到同行相邻卡(现有「行变短时索引夹取」逻辑) |

- 输入源行(`RowKind.INPUTS`)的卡片长按**不出菜单**(隐藏/改名归 M4b),按压照常吞掉、不启动:`dispatchKeyEvent` 只要有聚焦卡就记 `longPressDownTime` 并吞掉本次按压的后续事件,只在 `cardMenuActions` 非空时才开菜单。
- **坐标口径(T4 评审纠正)**:`buildRows` 会丢掉未安装的包和整行为空的行,所以渲染下标 ≠ `layout.json` 下标。`Row.layoutRow` 在过滤**之前**赋值(输入源行 −1),`CardRef.layoutRow` 用它;「移除」「移动位置」一律按 **(layoutRow, pkg)** 寻址(`EditScreen.initialTarget: Pair<Int, String>` 用 `indexOf(pkg)` 解列号),绝不用渲染列号——否则任一配置包未安装时,移除会打错行(铁律 5 的另一扇门)。
- **卸载需要 `REQUEST_DELETE_PACKAGES`**(API 26+ 起 `ACTION_DELETE` 必需;缺了系统卸载器静默退出、`startActivity` 仍返回成功)。清单已加。
- **三条杠键(MENU)在卡片菜单开着时先关卡片菜单**,不能叠出齿轮菜单(两个 320dp 面板重叠、焦点在看不见的那层)。
- **选择器回来的焦点**:「更改图标」走 `pickerTarget` 浮层,它会把 `HomeScreen` 整棵拆掉;`HomeScreen(initialTarget)` 用 (行, 列) 作 `tgtRow/tgtIdx` 的**初值**(只在组合实例创建时生效,不是闩),`MainActivity.homeInitialTarget` 在 CHANGE_ICON 时设、进编辑/设置/其它选择器时清。同一机制顺带修了 M3 备案的「换壁纸后焦点回到第一张」——从齿轮进的选择器不设种子,回到齿轮/第一张仍是既有行为。
  - **已由 M7 T4 退役(`93f00aa`)**:选择器改为叠在常驻 `HomeScreen` 之上、首页不再被拆,`covered` 期间冻结 `tgtRow/tgtIdx/tgtGear`、关掉后按它还原,`initialTarget`/`homeInitialTarget` 整套删除;「picker 回来落 (0,0)」随之解决(编辑页仍整体替换首页,不在此列)。
- 菜单项文案 `card_menu_*` ×6 + 描述行,三语。

## 2. 卡片标题

### 2.1 数据:`titles.json`(新,`Titles.read/write`)
- 形如 `{"com.a": "自定义名", …}`,独立于 `layout.json`(同一应用在两行共用一个标题;够用且零迁移,不改 `Layout` 的解析)。
- 健壮性照 `SettingsStore`:外置未挂用内存空表;缺文件 = 空表;坏文件改名 `.bad` + 写空表 + `Log.w`;写走 tmp → rename。标题清洗:trim、去控制字符、≤ 40 字符、空 = 删除条目(恢复应用名)。
- 手写扁平 JSON 解析(与 `Settings` 同理,纯 JVM 可测):键与值都是字符串,转义只处理 `\"` `\\`。

### 2.2 显示规则
- 全局开关 `Settings.showTitles`(字段已在,默认 false);设置页「布局」组补回「卡片标题」开关(`settings_show_titles`)。
- 文字 = `titles[pkg] ?: AppEntry.label`;**开关关着一律不显示**,自定义标题也不显示(设计 §2「标题开关为全局」)。
- 位置:卡片正下方一行,`Theme.CardTitleGap`(6dp)+ 一行 `Theme.CardTitleSize`(13sp 中档;随档位按 `cardWidth` 比例缩放,与 `cardMetrics` 同一推导)`Theme.RowTitle` 色 0.85 alpha,超宽省略号;聚焦卡随卡片一起放大(标题在 `AppCard` 的缩放容器内)。
- **行高**:`showTitles` 时 `CardMetrics.rowPitch` 增加标题行高;`HomeScreen` 的纵向溢出与位移已按 `rowPitch` 自算,不改机制;`EditScreen` 同一份 `cardMetrics`,标题同样显示(编辑时看得见名字更好认)。
- **零回归不变量**:`showTitles=false` 且无自定义标题时,首页**横幅卡**像素与 M3 收官一致。**§2.3 除外**:无横幅的纯图标应用不论开关一律铺回落色(§2.3 只改底色、不看开关),那些卡与 M3 的纯图标占位底不同,不在像素一致的范围内;像素对比只对横幅卡成立。

### 2.3 无横幅回落卡(设计 §2)
- `Apps.load` 对「既无自定义图也无 banner、只有方形图标」的应用,用 `Palette` 从图标取主色(dominant → vibrant → `Theme.IconPlaceholderBackground`),存进 `AppEntry.fallbackColor: Int?`;`AppCard` 用它铺 16:9 底,图标居中。
- 「自动显示标题」= 这类卡的标题也只受全局开关约束(设计原话:标题开关关着时也不显示)。所以 2.3 只改底色,不引入第二套标题规则。

## 3. 修改标题:`TitleDialog`

- 居中浮层(照 `HomeSettingsCard` 的卡片样式):标题「修改标题」、当前应用名、一个 `BasicTextField`(预填现标题)、提示「确定保存 · 返回取消 · 清空恢复应用名」。
- 焦点账本:文本框是唯一可聚焦项;初始焦点循环只信自报 `isFocused`(铁律 2);`focusNonce` 变化重请求(铁律 3);`BackHandler` 取消。聚焦后调用软键盘显示(`LocalSoftwareKeyboardController.show()`),系统输入法(索尼国行自带)接管;D-pad 在输入法内导航由系统负责。
- 提交:IME 动作 Done / 确定键 → `Titles.write` → `revision++` → 关闭。
- **风险(唯一需要真机的点)**:模拟器 TV 镜像的输入法与索尼真机不同;输入体验、中文输入必须真机验。

## 4. 新应用标记

- `Settings.newAppsSeenAt: Long`(默认 0;`MainActivity.onCreate` 读到 0 时写成当前时间——首启之前装的都不算「新」)。
- 「新」= `pkg.firstInstallTime > newAppsSeenAt` **且**不在 `layout.json` 任何一行里。纯函数 `isNewApp(firstInstallTime, seenAt, onLayout)` 可测。
- 首页:小字 `home_new_apps`「有 %1$d 个新应用」放在**右上状态栏里、齿轮左侧**(与齿轮/时钟同一 `Row`,`Theme.FooterHintText`,12sp),N = 0 不显示;待机时随齿轮一起淡出。位置改过两次:左下角会与底行卡片标题重叠(标题开着、底行靠近屏底时);左上角在底行聚焦、整列上移时会被首行卡片盖住。状态栏是唯一已被接受「卡片上移时从下面穿过」的区域(齿轮/时钟本来就这样),所以放这里。计数在 `buildRows` 同一 IO 块里算(已枚举全部应用),随 `revision` 刷新——装/卸应用广播已接。
- 编辑页「添加应用」列表:候选项右侧香槟色小标「新」(`edit_badge_new`);打开列表那一刻 `newAppsSeenAt = now`(写 settings),列表本次仍按打开前的时间戳标记(先算后写),关闭后首页计数归零。

## 5. 设置页

- 「布局」组在「卡片大小」之后补回「卡片标题」开关(M2 曾移除该控件、保留字段)。控件索引后移,`order`/`WALLPAPER_CTRLS`(M3 引入)同步。

## 6. 文件与接口

- **新增**:`Titles.kt`(读写 + 纯解析 + `truncateTitle`)、`TitleDialog.kt`、`CardMenu.kt`(`data class CardRef`、`enum CardAction`、`fun cardMenuActions(kind): List<CardAction>` 纯函数——菜单项的文案与动作在 `MainActivity.cardMenuItems(ref)` 里按它构造;`isNewApp`)、`app/src/test/.../TitlesTest.kt`、`CardMenuTest.kt`(六项顺序、输入源空表、`isNewApp`)。
- **修改**:`MainActivity.kt`(长按分发、`cardMenu`/`editTarget`/`focusedCard` 状态、`newAppsSeenAt` 初始化、卸载/移除动作)、`HomeScreen.kt`(上报焦点卡、叠 `GearMenu`、角落文字、标题接线、看门狗让路)、`AppCard.kt`(标题行 + 回落底色)、`EditScreen.kt`(`initialTarget`、「新」标、`newAppsSeenAt` 写入)、`Apps.kt`(`fallbackColor`、`firstInstallTime`)、`Theme.kt`(`CardTitleGap`/`CardTitleSize`,`cardMetrics` 的 `rowPitch` 带标题)、`Settings.kt`(`newAppsSeenAt`)、`SettingsScreen.kt`(标题开关)、`Layout.kt`(`removeFromRow(ctx, rowIndex, pkg)`)、`strings.xml` ×3。

## 7. 错误处理、测试、验收

- 不崩不留黑:`titles.json` 坏 → 改名重写;卸载被系统拒绝 → toast;`editTarget` 指向的行/列在加载后已不存在 → `retarget` 夹取到最近合法格。
- 单测:`Titles` 往返 / 坏文件 / 清洗(trim、控制字符、40 字符、空=删除);`isNewApp()` 三种情形;`cardMenuActions(kind)`(`CardMenuTest.kt`)输入源卡返回空表、应用卡六项顺序固定。
- 模拟器:`adb shell input keyevent --longpress KEYCODE_DPAD_CENTER` 出菜单截图(应用未启动);六项各走一遍——卸载到系统确认页截图即止、移动位置进编辑页焦点落在该卡、移除后卡片消失且焦点在同行邻卡;改 `titles.json` 后开关开/关各截一张(行高变化、省略号);`newAppsSeenAt` 改 0 → 角落计数 = 未上桌面的应用数,打开添加列表带「新」标,关闭后计数 0;零回归像素对比(开关关、无自定义标题;横幅卡逐位一致,§2.3 回落卡除外;截图前把焦点放到齿轮上,避免聚焦卡的呼吸光晕当噪声源)。
- 真机(Gordon):长按手感(0.4 s 是否合适)、`TitleDialog` 用索尼输入法输中文。
- 估时 3 天(设计 §11 原估 3 天含原地移动;原地移动后置换来标题渲染与设置页开关,量相当)。
