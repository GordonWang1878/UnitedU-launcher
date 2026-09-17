# M8 设计:首页视觉重构(照搬 Google TV 原生:壁纸 hero + Material 网格)

状态:设计定稿待 Gordon 审阅(2026-09-17)。基线 = main `e6c3519`。一手数值全部来自 `docs/research/2026-09-17-android-tv-native-ui-design-refs.md`(下称「调研」),本文只引用、不再重复出处。

与 M7 的关系(2026-09-17 下午改定):**M7 先并入 main**(`m7-settings` 分支进行中,已领先 11 提交),**M8 之后从新 main 开 `m8-visual`**,现阶段只写 plan。M8 只改**首页与共用 token**;M7 做好的设置页、编辑页、选择器、菜单、对话框在 M8 里用同一套 token 换皮,列为 M8 的收尾任务。

## 0. 决策(Gordon,2026-09-17 grilling 四轮 + 效果图确认)

| 议题 | 决定 | 理由 |
|---|---|---|
| 目标观感 | **两者都要,分区域用**:壁纸即 hero 的电影感区 + Material for TV 克制感的应用网格 | UnitedU 没有海报级内容,只有 320×180 烧字横幅;电影感只能来自壁纸 |
| 改动范围 | **结构可改**,前提是视觉收益大 | 产品未上线,不必局限小迭代 |
| 对标方式 | **直接照搬 Google 默认值**:几何、动效、中性色阶、主题色 primary 全搬 | 减少主观判断 |
| 字体 | **保留 DM Sans**(照搬的唯一例外);字阶数值照搬 | 看过字体对照图后定;中文两方案无差,只影响数字与英文 |
| 取色功能 | 跟随壁纸主色 / 主题化卡片 / 亮度滑块**保留,只调数值** | Google 官方也推荐 content-based color |
| hero 内容 | **壁纸即 hero,不换内置图**;上部主体 = **大字时钟 + 日期**(效果图 A) | 内置壁纸是暗色斑无主体,留空显空;横幅放大即糊 |
| 纵向导航 | **焦点行锚定在下三分之一,上面的行推入 hero 区,scrim 随之上移**(Google TV 首页做法) | 3 行放不进下三分之一;固定窗口一屏只见一行 |
| 顶栏 | **右上一组 pill 装设置与屏保按钮;顶栏不放时钟** | Google TV 2025 最易识别的外形;时钟已是 hero 主体 |
| 卡宽 | **三档全按 Google 网格推导**:5 张 152.8 / 6 张 124 / 8 张 88dp,默认中档 | 中档正好等于 Google 表的 124dp;三档都是缩小不放大 |
| 主题色预设 | **新增「Material 紫」#D0BCFF 并设默认,六个旧预设保留** | 新用户首次看到 Google 默认配色,老预设不丢 |
| accent 落点 | **保留现有落点,其余中性**:行标题、行图标、大字时钟、pill 图标;卡片容器、描边、scrim、正文全走中性色阶 | 纯 Google 用法换预设首页几乎不变,预设失去意义 |
| 分期 | **首页先做,二级界面并入 M7** | M7 本来要重做设置页 |
| 照搬的隐含后果(已声明、未反对) | 焦点 1.31→1.1 + 3dp 描边;呼吸光晕停用;邻行压暗停用;行内留白按 1.1 重算 | Google 默认 `Glow.None`;锚定 + scrim 已天然分层 |
| 实现方式 | **引 `androidx.tv:tv-material:1.0.0`,只用叶子组件与 token**:Card / Surface / IconButton / 描边 / 缩放动效 / 色阶 / 字阶用库默认;布局、锚定、hero、pill 容器自写;不用 ImmersiveList / Carousel / TvLazyRow | 数字不会抄错、跟 Google 更新;七条铁律管的是滚动容器与焦点恢复,不是叶子组件;推翻 `app/build.gradle.kts` 第 65 行「不引 tv-material」的旧注释 |
| 长按判据 | **保留 M4:`MainActivity.dispatchKeyEvent` 按时长 ≥ 600ms 判,一字不动;`Card` 传 `onLongClick = null`** | 读了 tv-material 1.0.0 源码(`Surface.kt` handleDPadEnter):库的长按 = 确认键 `repeatCount == 1` 的重复事件,即固件定时(A95L ≈ 0.4s),正是 M4 否掉的那种;而 M4 的计时在 Activity 层、Compose 之前就吞掉长按与后续松手,不需要任何拦截代码;库在失焦时自动释放按压态(菜单一开卡片失焦),按下缩放不会卡住。2026-09-17 两次改口后 Gordon 定 |
| 分支与顺序 | **M7 先并 main,M8 之后从新 main 开 `m8-visual`**;新旧对比靠同包名 APK 互相覆盖安装;不做 feature flag、不做双装 flavor(备选) | M7 已改 HomeScreen / MainActivity / Theme / Clock / Settings / strings,与 M8 正面重叠,并行必冲突;两套首页 = 两本焦点账本 |

## 1. 视觉 token(照搬清单)

### 1.0 库提供 vs 自写

| 来源 | 内容 |
|---|---|
| tv-material 1.0.0 默认(下表标 ★) | `Card` / `Surface`:容器形状 8dp、`focusedScale` 1.1、聚焦描边 3dp `colorScheme.border`、`Glow.None`、进 300 / 出 500 / 按下 120ms 与减速曲线;`IconButton`:pill 内两个按钮的聚焦反白与 1.1;`MaterialTheme(colorScheme = darkColorScheme(primary = 当前预设 accent, …), typography = Typography(), shapes = Shapes())` 提供 §1.3 中性色阶与 §1.4 字阶(字族换成 `Theme.Sans`) |
| 自写 | 行布局与横向位移(铁律 1)、锚定与 shift、scrim、hero 时钟、pill 容器几何、横幅 / 图标回落绘制与 `edgeColor`、主题化卡片 `ColorFilter`、焦点看门狗与账本(`onFocusChanged` 挂在传给 `Card` 的 modifier 上,位于库内部 `focusable` 之前,能观察到焦点) |
| 不用 | `ImmersiveList`、`Carousel`、`TvLazyRow` / `TvLazyColumn`、`NavigationDrawer`、`TabRow`(本轮无此结构;前三者触铁律 1) |

版本钉 **1.0.0**:1.1.0 依赖 Compose 1.10.3,超出现 BOM 2024.10.01(Compose 1.7.x);1.0.0 基于 Compose 1.6.8,与 1.7.x 共存。本机 Gradle 缓存没有 `androidx.tv`,加依赖后的首次构建需联网。**★ 数值已对 1.0.0 的 sources.jar 核过**(2026-09-17):`SurfaceScaleTokens` 300/500/120/300 与 (0,0,0.2,1)、`CardDefaults.scale` 1.1、`border` 3dp `colorScheme.border`、`ContainerShape` 8dp、`ShapeTokens` Small 8 / Medium 12、`ColorDarkTokens` Border=NeutralVariant60 / Surface=Neutral10 / SurfaceVariant=NeutralVariant30 / InverseSurface=Neutral90,全部一致;`IconButtonDefaults` Medium 40dp / 图标 20dp。

### 1.1 几何(dp,960×540 基准;1080p 下 ×2 = px)

| 项 | 现值 | M8 | 出处 |
|---|---|---|---|
| 左右安全边距 `SidePadding` | 84.5 | **58** | 调研 §1.3 |
| 上下最低边距 | 195.3(`TopPadding`,布局量) | **28**(pill 顶 28) | 调研 §1.3 |
| 可见跨度 | 811 | **844** = 960 − 2×58 | 推导 |
| 卡间距 `CardSpacing` | 8.9 | **20** | 调研 §1.4 peaking |
| 卡宽 cardWidth(N) | 中档锚定常量 | **(844 − 20×(N−1)) / N** → 5: 152.8 / 6: 124 / 8: 88 | 调研 §1.4 表 |
| 卡高 | 宽×9/16 | 同 → 85.95 / 69.75 / 49.5 | 16:9 |
| 圆角 `CardCorner` | 13.75(随档缩) | **8,固定不随档** ★ | 调研 §2.1 `CardDefaults.ContainerShape` |
| 焦点放大 `FocusScale` | 1.31 | **1.1** ★ | 调研 §1.5 / §2.1 |
| 行内上下留白 `RowVerticalPad` | 20 | **cardHeight × 0.05 + 3**(放大溢出 + 描边),每档各算 | 推导 |
| 横幅缩放 | 0.8× | 0.955 / 0.775 / 0.55× | 均为缩小 |

`Theme.cardMetrics` 的「中档 6 走原始常量、零回归锚点」分支**删除**,三档同一公式。6 张 × 124 + 5 × 20 = 844 整,行尾整齐;行内超出屏幕的卡片仍靠现有 `xShift` 自算横移(铁律 1 不变)。

### 1.2 焦点

- ★ **描边**:3dp,颜色 `border` #938F99,形状同卡片圆角,inset 0——即 `CardDefaults.border()` 默认,由库绘制,随 `graphicsLayer` 整体缩放(3dp 放大成 3.3dp 是照搬的一部分)。
- ★ **光晕**:`Glow.None`(库默认)。删 `GlowRadius`、`GlowPeriodMs`、`rememberGlowBreath` 及 AppCard 里的呼吸相位绘制。
- **邻行压暗**:删 `InactiveRowAlpha`(等价于 1f)。
- ★ **动效**(`SurfaceScaleTokens`,库内实现):进焦 **300ms**、失焦 **500ms**、按下 **120ms**、松开 300ms;四者共用 `CubicBezierEasing(0f, 0f, 0.2f, 1f)`。按下 = DPAD_CENTER 按下到抬起期间 `pressedScale = scale`(即回到 1.0)。AppCard 自己的 `animateFloatAsState(tween(180))` 与 `.scale()` 删除。

### 1.3 颜色

中性色阶(dark,调研 §2.6),首页落点:

| 角色 | Hex | 首页落点 |
|---|---|---|
| surface / background | #1C1B1F | scrim 终点色;pill 底(α0.65);无横幅且取不到图标边缘色时的回落底 |
| surfaceVariant | #49454F | 占位卡、待加载卡、编辑态「＋」卡容器 |
| onSurface | #E6E1E5 | 正文、卡片标题主色、pill 图标未聚焦 |
| onSurfaceVariant | #CAC4D0 | 卡片副标题、「有 N 个新应用」 |
| border | #938F99 | 焦点描边 |
| inverseSurface / inverseOnSurface | #E6E1E5 / #313033 | pill 按钮聚焦时反白 / 图标反色 |
| scrim | #000000 | 菜单、对话框遮罩(M7 接手) |

- `Theme.kt` 里首页用到的字面量(`Background`、`RowTitle`、`FooterHintText`、`IconPlaceholderBackground`、`PendingCardBackground`、`AddCardBackground`、`ThumbPlaceholderBackground` 等)改读色阶;二级界面的字面量**本轮不动**(M7)。
- **主题色**:`ThemePresets.all` 头部新增 `ThemePreset("material", R.string.preset_material, accent = #D0BCFF, highlight = #E8DCFF)`(highlight = 混白 55% 派生,写显式 hex);`DEFAULT_ID = "material"`;`Settings.themePresetId` 默认 `"material"`。旧 settings.json 里的 `"gold"` 等 id 照常命中,不迁移。新预设放最前 = swatch 最左 = 默认在最左,左右键方向随之,是有意的。
- **accent 落点**(首页,读 `LocalThemeColors.current.accent`):行标题、行图标、大字时钟与日期、pill 图标(未聚焦)。**highlight 在首页不再有落点**(原光晕已删、时钟改 accent);二级界面的 highlight 落点 M7 处理。
- 跟随壁纸主色(`usableAccent` 地板)与主题化卡片(`cardTintMatrix`)**不变**。

### 1.4 字体与字阶

字族 DM Sans 不变(`Theme.Sans`)。字阶照搬 Material TypeScale(sp;1080p ×2 = px):

| 元素 | 现值 | M8 | Token |
|---|---|---|---|
| 行标题 | 15.5sp Medium accent | **16sp Medium** accent | titleMedium |
| 卡片标题(开关开时) | 13sp | **12sp Regular,α0.6** onSurface | bodySmall + `SubtitleAlpha` |
| 大字时钟 | 16sp 顶栏 | **84sp Medium**(168px,效果图 A) accent | 自定,大于 displayLarge |
| 日期 | 随时钟 | **24sp Regular** accent α0.85 | headlineSmall |
| 「有 N 个新应用」 | 12sp | **11sp Medium** onSurfaceVariant | labelSmall |

### 1.5 顶栏 pill 组

- 位置右上:top 28dp、right 58dp;胶囊圆角,底 #1C1B1F α0.65,内边距 4dp;内含 2 个 tv-material `IconButton`(Medium:按钮 40dp、图标 20dp,库默认),按钮间距 4dp → pill 高 48dp(原写 32dp/24dp 图标,改按库默认尺寸,2026-09-17 写 plan 时定)。
- 按钮:齿轮(现 `GearButton`,点击打开 `GearMenu`,行为不变)+ 屏保(点击立即进入待机层:`MainActivity` 提供 `onScreensaver` 回调,走与 `idle = true` 同一条路径;再按任意键退出同现有逻辑)。
- ★ 焦点:tv-material `IconButton` 默认——聚焦容器 `inverseSurface` 反白、图标 `inverseOnSurface`、放大 1.1、动效同 §1.2。两个按钮互为左右;上/右锁 `FocusRequester.Cancel`,最左按钮的左锁;下 = 记住的那一格(沿用 `GearButton` 现有 `focusProperties` 与看门狗账本,`report(-1, -1, got)` 扩成 `(-1, col)` 两格)。
- 顶栏不放时钟:`Clock` 组件改为 hero 大字时钟(§2.1)。「有 N 个新应用」移到 pill 组正下方,右对齐 58dp。

## 2. 结构:壁纸 hero + 锚定行

### 2.1 层次(自下而上)

1. **壁纸**:现有管线(轮播、模糊、亮度)不变,全屏。
2. **scrim**:线性渐变 #1C1B1F α0 → α0.8(`CompactCard` 同一 Brush);**顶边 = 锚点上方 60dp 再加 shift(随行区上移),底边固定在屏底**,即行往上推时 scrim 变高而不是整块上移,下方新露出的行始终在暗层里(写 plan 时修正措辞)。
3. **hero 主体**:大字时钟 + 日期,左对齐 58dp,时钟块顶 150dp;**不随 shift 走**;第 1 行起淡出(§2.3)。
4. **行区**:锚点 `AnchorTop = 360dp`(= 540 × 2/3);第 0 行的**行标题顶** = AnchorTop。行距 `rowPitch(N) = titleLine + RowTitleGap + cardHeight(N) × 1.1 + 20dp`(20 = 行间距,照卡间距)。
5. **顶栏 pill 组 + 新应用提示**:不随 shift。

`TopPadding` / `FirstCardTop` / `BottomKeepout` 删除,全部由 `AnchorTop` 与 `rowPitch` 推导。

### 2.2 锚定(纵向导航)

- 现在:`overflow > 0` 才上移。M8:**`shift = −activeRow × rowPitch`**,焦点行永远落在锚点;第 0 行 shift = 0,hero 完整。
- 行 > 0 时上面的行随 shift 推入 hero 区,scrim 同步上移把它们盖在渐变里;下一行在锚点下方自然露出(1080p 中档:锚点 720px,下一行标题顶 ≈ 720 + 2×rowPitch ≈ 1000px,露出标题与卡片上沿)。
- 动画:`animateDpAsState(tween(300, easing = CubicBezierEasing(0,0,0.2,1)))`,与焦点同曲线。
- 铁律 1 不变:`Column` + `offset` 自算,不用任何可滚动容器;横向逻辑不动。

### 2.3 hero 压缩

`activeRow ≥ 1` 时 hero 主体 alpha 0(tween 300),`activeRow == 0` 恢复 1。**不做几何缩放**——时钟不缩小、不位移,只淡出;不引入新的可聚焦节点(时钟不可聚焦),焦点账本不变。

### 2.4 待机

`idle = true`:行区、scrim、pill 组、新应用提示淡出(现 `contentAlpha` 逻辑,1200ms),hero 时钟保留并恢复 alpha 1——`IdleContent.CLOCK_ONLY` 自然成立;屏保层不变。齿轮/pill 节点继续用 alpha 而非移除(现注释里的理由不变)。

### 2.5 空桌面 / 首次引导

`rows` 为空:hero 照常;行区位置显示现有引导提示;焦点在 pill 齿轮,下键锁 Cancel(沿用)。

## 3. 每行张数与档位

`VALID_CARDS_PER_ROW = 5/6/8` 不变;设置页「卡片大小」三段不变;三档同一公式(§1.1)。`FocusScale`、`CardSpacing`、`CardCorner` 跨档不变。

## 4. 文案与资源

- strings(三语):`preset_material` = 「Material 紫」/ Material Purple / Material パープル;屏保按钮 `contentDescription`。
- 删除:`GlowRadius`、`GlowPeriodMs`、`rememberGlowBreath`、`InactiveRowAlpha`、`TopPadding`、`FirstCardTop`、`BottomKeepout`、`RowVerticalPad`、`Shadow*`、`CardWidth/Height/Corner/Spacing`、`RowPitch` 常量(改由 `HomeLayout` 公式给出);`GearButton.kt` 整个文件(pill 组取代)。`LONG_PRESS_MS` 与 `dispatchKeyEvent` 的长按分支**不动**。
- 依赖:`implementation("androidx.tv:tv-material:1.0.0")`;`app/build.gradle.kts` 第 65 行注释改写为「tv-material 只用叶子组件与 token;滚动容器一律不用(铁律 1)」。
- `docs/DESIGN-unitedu-open-source.md` §2 首页、§3 主题:按本文改写(卡片圆角 40% → 8dp;三档推导;顶栏;hero)。

## 5. 验收

- **布局对照**:默认设置(material、中档、3 行、内置 00-neutral)首页截图与效果图 A(`docs/screenshots/m8-mock-A-big-clock.png`)对齐:边距、卡宽、锚点、pill 位置误差 ≤ 4px。
- **焦点**:描边 3dp、放大 1.1;进 300 / 出 500ms(单测 `animateFloatAsState` spec 断言 + 录屏帧计数);无光晕、无邻行压暗。
- **纵向**:3 行上下往返,焦点行标题顶始终 720px ± 2;hero 时钟第 1 行起淡出、回第 0 行恢复;**铁律 2–7 全部复验**(看门狗、菜单开合、后台返回、包更新缩行、空桌面)。
- **pill**:两按钮可聚焦,上/右锁、下回记忆格;屏保按钮进入待机、任意键退出。
- **长按(M4 验收项重跑)**:模拟器 `settings put secure long_press_timeout 700` + `input keyevent --longpress KEYCODE_DPAD_CENTER` 出菜单、默认 400 不出;短按启动应用;长按弹菜单关掉后卡片回到 1.1 放大(按压态已释放);A95L 同套。
- **主题**:7 个预设逐个切 → 行标题 / 行图标 / 时钟 / pill 图标变色,卡片容器 / 描边 / scrim 不变(§6 不变量);跟随壁纸主色、主题化卡片行为与 M3 验收一致。
- **单测**:只覆盖自写公式——`cardMetrics` 三档(152.8 / 124 / 88;高 85.95 / 69.75 / 49.5)、`shift(activeRow)`、`rowPitch(N)`、预设默认 material 与旧 id 兼容、`Settings` 默认 themePresetId;★ 值(1.1 / 3dp / 300 / 500ms)由库保证,不单测数字。
- **真机 A95L**:中档 0.775× 横幅烧字清晰度肉眼验收(不接受 → 默认改大档,备选已定);DM Sans 时钟。

## 6. 不变量

- **主题色不进卡片中间**(M7 §10.5)延续并扩展:accent 不染卡片容器、不染描边、不染 scrim。
- 焦点七条铁律全部适用;M8 新增的 pill 组与 hero 时钟不引入新的可聚焦浮层。
- 中文字形不变(两方案都回退系统 CJK)。
- 壁纸、亮度、模糊、轮播行为不变。

## 7. 不做

不换内置壁纸;不做海报 / 精选轮播;不做 tabs / 搜索;不做 ImmersiveList 的按项换背景;不换 Roboto;二级界面本轮不动(M7);不做 peaking(行尾整齐)。

## 8. 风险

- **横幅烧字 0.775×**:65 寸上是否可接受只能真机看;备选是默认改大档 152.8dp(0.955×),设置项已存在。
- **锚定改动触及焦点看门狗与 shift**:`overflow` 逻辑被替换,`rowFocus` / `tgtRow` / 齿轮下键回忆格全部要复验;建议分支 `m8-visual`,模拟器逐像素对照。
- **删中档零回归锚点**:M2–M6 的像素级标定全部失效,是有意的;之后的对照基准改为效果图 A。
- **描边随 scale 放大**:3 → 3.3dp,库行为;若真机看着粗,只能自绘补偿(偏离照搬,需 Gordon 定)。
- **长按语义**(已定,§0):M4 的 600ms 计时留在 `MainActivity.dispatchKeyEvent`,`Card` 的 `onLongClick = null`。Activity 已吞掉长按那次 DOWN、其后同一 downTime 的 UP、以及所有 `repeatCount > 0` 的确认键重复事件,所以库只会看到「短按 DOWN(repeatCount 0)→ UP」= 点击,永远看不到 repeatCount 1;长按时菜单接管焦点,库的 `onFocusChanged` 分支发 `PressInteraction.Release`,按压态不会卡在 pressedScale。验收:M4 长按验收项在模拟器与 A95L 重跑(§5)。
- **Compose 版本**:钉 tv-material 1.0.0;升 1.1.0 需整体升 Compose 到 1.10,不在本轮。调研数字已对 1.0.0 sources.jar 核过(§1.0),无差异。
