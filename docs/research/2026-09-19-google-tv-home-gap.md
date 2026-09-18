# Google TV 首页差距报告:UnitedU M8 首页 vs 原生首页(2026-09-19)

**结论:在「零广告、零推荐」约束内,UnitedU 能把首页的*骨架*(顶栏、行网格、卡片、焦点、标签)做到与原生几乎同一套尺寸,约 2 个工作日(含模拟器回归 + 一轮 A95L);但原生首页的*视觉重心*——内容 hero、推荐行、赞助卡、搜索/通知/账号——全部依赖 Google 后台或直接违反产品原则,永远做不到,也不该做。** 剩余可缩的差距里,收益最大的是「聚焦卡片下方显示应用名」「网格常量按实机」「行标题 12sp + 焦点行提亮」三项,合计 5.5 小时。

基线:main `7a76a84`(M8 已并入)。一手数值来自本次模拟器截图的像素量测(附录 A),设计决策引用 `docs/superpowers/specs/2026-09-17-m8-home-visual-refresh-design.md`(下称 spec)§0 与 §7,调研引用 `docs/research/2026-09-17-android-tv-native-ui-design-refs.md`(下称调研)。

## 1. 取材

### 1.1 模拟器上的「原生首页」是哪一个

AVD `unitedu-tv`(`sdk_google_atv64_arm64`,Android 14,1920×1080 @ 320dpi,**1dp = 2px**)的原生桌面是 **`com.google.android.tvlauncher` 7.7.15-968255986-f(Android TV Home)**,不是 Chromecast / Sony 上的 Google TV(`com.google.android.apps.tv.launcherx`)。两者同属一个设计族(顶部 tabs、内容 hero、Favorite/Your apps 行、内容行),但 launcherx 有三处本次**无法在模拟器验证**、只能引调研 §3 的差异:

- 2025 改版把 Live / Apps tabs 与搜索装进一个 pill,设置与屏保装进第二个 pill(调研 §3.2)——**UnitedU M8 的右上 pill 组正是这第二个盒子**;本次截到的 7.7.15 顶栏还是裸图标。
- 2024 起 Home tab 的「Your apps」行改成**圆形小图标**,Apps 页仍用 16:9 banner(调研 §3.1);7.7.15 两处都是 banner。
- Google TV 的 Ambient 卡片、Watchlist / Library 分页(调研 §3.2)。

模拟器有网络,首页拉到了真实推荐与赞助内容(iQIYI、Prime、「Top selling movies」),所以截图里的 hero 与内容行就是用户实际看到的形态。

### 1.2 截图(全部 1920×1080 原尺寸;原生侧为 JPEG q92,UnitedU 侧为 PNG)

| 状态 | Android TV Home | UnitedU M8 |
|---|---|---|
| 默认 | [atv-home-default](../screenshots/gtv-compare-atv-home-default.jpg)(开机焦点落在 Favorite Apps 行的「+」) | [uu-row0](../screenshots/gtv-compare-uu-row0.png) |
| 焦点下移一行 | [atv-row-focused](../screenshots/gtv-compare-atv-row-focused.jpg) | [uu-row1](../screenshots/gtv-compare-uu-row1.png) |
| 再下一行 | [atv-row2-focused](../screenshots/gtv-compare-atv-row2-focused.jpg) | [uu-row2](../screenshots/gtv-compare-uu-row2.png) |
| 顶栏聚焦 | [atv-topnav-focused](../screenshots/gtv-compare-atv-topnav-focused.jpg)(Home tab) | [uu-pill-settings](../screenshots/gtv-compare-uu-pill-settings.png) / [uu-pill-screensaver](../screenshots/gtv-compare-uu-pill-screensaver.png) |
| hero 按钮聚焦 | [atv-hero-button](../screenshots/gtv-compare-atv-hero-button.jpg)(Watch Now) | —(hero 不可聚焦) |
| Apps tab | [atv-apps-tab](../screenshots/gtv-compare-atv-apps-tab.jpg)、[atv-apps-hero-button](../screenshots/gtv-compare-atv-apps-hero-button.jpg)(行未聚焦=压暗)、[atv-apps-tile-focused](../screenshots/gtv-compare-atv-apps-tile-focused.jpg) | — |
| 纯网格(占位) | [atv-shop-grid](../screenshots/gtv-compare-atv-shop-grid.jpg)(Shop tab 加载中,网格数值最干净) | — |

### 1.3 对照图(左原生、右 UnitedU,同比例)

- [side-by-side-home](../screenshots/gtv-compare-side-by-side-home.png):默认态
- [side-by-side-row-down](../screenshots/gtv-compare-side-by-side-row-down.png):下移一行后的锚定方式
- [side-by-side-topnav](../screenshots/gtv-compare-side-by-side-topnav.png):顶栏
- [side-by-side-apps](../screenshots/gtv-compare-side-by-side-apps.png):同一个应用(Settings,图标回落卡)的聚焦态
- [tile-focus-1to1](../screenshots/gtv-compare-tile-focus-1to1.png):聚焦卡片 1:1 像素裁切

## 2. 差异表

差异性质四选一:**纯视觉或布局(可以做)** / **依赖内容或 Google 服务(没有数据源)** / **与「零广告零推荐」冲突(不该做)** / **M8 有意偏离(引 spec §0 那条决定)**。工作量只给「可以做」的行;有意偏离的行在括号里给「若推翻决定」的估时,不计入总数。数值单位 px @1080p,括号内 dp。

| 元素 | Google TV(模拟器实测 7.7.15) | UnitedU 现在 | 差异性质 | 还原工作量 |
|---|---|---|---|---|
| 顶部 tabs(Search / Home / Shop / Discover / Apps) | 文字 tab,16sp,未选灰(lum 142)、聚焦白(lum 234);选中指示条 2dp 厚、26dp 宽,聚焦时展宽到整个词(86px)并变白;**焦点落上即切换 tab**;词距约 29dp | 没有 tabs | Shop / Discover 与「零广告零推荐」冲突;Home / Apps 结构性 tab 是 spec §7「不做 tabs / 搜索」 | —(单 tab 无意义) |
| Search(麦克风 + 文字) | 顶栏最左,22×32px 图标 | 无 | 依赖 Google 服务(Assistant) | — |
| 顶栏右侧:通知角标 / 齿轮 / 头像 / 时钟 | 三个 20dp 图标,48dp 间距,右对齐 54dp;时钟 20sp 灰(lum 181),无日期;中线 44dp | 右上 pill 92×48dp(top 28 / right 58dp),底 #1C1B1F α0.65,齿轮 + 屏保两个 20dp accent 图标;聚焦 = 44dp 反白圆(#E6E1E5) | 通知 / 头像:依赖 Google 服务。pill 本身:spec §0「顶栏」(且与 Google TV 2025 的第二个 pill 同构,比 7.7.15 更像 launcherx)。顶栏时钟:spec §0「顶栏不放时钟」 | (顶栏加 20sp 时钟 1 h) |
| 顶栏几何 | 图标中线 44dp,左右 54dp | pill 中线 52dp,右 58dp | 纯视觉或布局(可以做) | **0.5 h**:`PILL_TOP` 28→20dp、`SIDE_PADDING` 见网格行 |
| hero 区 | 内容轮播(约 5s 自动换):服务商 logo 23px、标题 28sp 白、「Recommended For You」16sp、简介 14sp 灰、「Watch Now」95×32dp 白胶囊;右侧约 40% 宽海报,向左向下渐变到黑;轮播圆点右下 | 壁纸全屏 + 84sp accent 大字时钟 + 24sp 日期;不可聚焦 | 依赖内容 + 与「零推荐」冲突(hero 就是推荐位);「用应用横幅当 hero」已被 spec §0「hero 内容」否掉(横幅放大即糊) | — |
| 提示 / 促销卡(「Discover tab is going away」80dp 卡 + Dismiss;底部「Customize your Home screen / Send feedback」两张 348px 大卡) | 常驻,占一整行 | 无 | 依赖 Google 服务;促销卡与「零广告」冲突 | — |
| 内容行(Top selling movies / Recently Added) | 204×115dp 16:9 海报,4 张 + 露出 84px;聚焦卡下方三行元数据(16sp 标题、12sp 灰年份 / 时长、12sp 价格) | 无内容行 | 与「零广告零推荐」冲突(即 Apps only 模式下 Google 也保留赞助内容,调研 §3.1) | — |
| 「Your apps」行样式 | 7.7.15:**16:9 banner**,Home tab 的 Favorite Apps 96×54dp(一屏 8 张)、Apps tab 的 Installed Apps 132×74dp(一屏 6 张);launcherx 2024 起 Home tab 改**圆形小图标**、Apps 页仍 banner(调研 §3.1) | 16:9 banner,中档 124×69.75dp(一屏 6 张) | banner:纯视觉(尺寸差见下行)。圆形图标行:纯视觉或布局(可以做),但违背 DESIGN §2「卡片 16:9」与 spec §0「卡宽三档全按 Google 网格推导」,不建议 | 圆形图标行 **1.5 天**(新卡型 + 遮罩渲染 + 三档几何 + 编辑页同步 + 焦点账本);不进推荐子集 |
| 行尾「+ Add app to favorite」卡 | Favorite Apps 行末常驻一张同尺寸「+」卡,聚焦时下方出标签 | 无;添加应用只在编辑分栏页 | 纯视觉或布局(可以做) | **5 h**:每行末尾一个可聚焦 `Card`,点击进编辑页并定位到该行;`rowFocus` 挂点、`coerceIn(lastIndex)` 四处夹取、`cardAt` 对「+」返回 null(长按不出菜单)、`isRowEnd` 移到「+」;单测 |
| 网格常量 | 安全边 **54dp**;卡距 **12dp**;圆角约 **4dp**;跨度 852dp:8 张 = 96dp、6 张 = 132dp、4 张 = 204dp | 安全边 58dp;卡距 20dp;圆角 8dp;跨度 844dp:8/6/5 张 = 88 / 124 / 152.8dp | 纯视觉或布局(可以做)——M8 照搬的是 Google *设计指南*(58 / 20 / 8),实机用的是 54 / 12 / 4 | **1.5 h**:`HomeLayout` 三个常量 + 公式 `(852 − 12×(N−1))/N` → 5/6/8 张 = 160.8 / 132 / 96dp(6、8 档正好等于实机两种 tile);单测改数;mock-A 基线作废、重截 |
| 卡片焦点效果 | 应用 tile **×1.29**(264→341px)、内容卡 ×1.2;**以左边缘为轴**(首张左沿钉在 108px 不动);无描边、无光晕;聚焦卡浮到邻卡之上 | ×1.1 以中心为轴 + 3dp #938F99 描边(实测 6–7px,跨在边缘上,聚焦占位 280×161px);无光晕 | M8 有意偏离:spec §0「照搬的隐含后果」(焦点 1.31→1.1 + 3dp 描边) | (若推翻 3 h:`CardDefaults.scale(focusedScale=1.29)` + `Border.None` + `rowVerticalPad` 随 `FOCUS_SCALE` 重算 + 左轴要自写 `graphicsLayer(transformOrigin)`,偏离「只用库默认」) |
| 卡片标签 | **只在聚焦 tile 下方**显示应用名,14sp 白居中(Settings / Add app to favorite);未聚焦无字 | 全局开关(默认关):开则每张卡下 12sp α0.6 常驻 | 纯视觉或布局(可以做) | **3 h**:`AppCard` 标题 alpha 随焦点(300ms),行高常驻预留;设置项「卡片标题」改三态 关 / 聚焦时 / 总是(`Settings` + `SettingsModel` + 三语 strings + 单测) |
| 行标题 | 12sp Regular,无图标;**焦点行白(lum 234)、其他行灰(lum 140)** | 16sp Medium accent + 24dp accent 图标,所有行同亮 | 字号与亮度:纯视觉或布局(可以做)。accent 色与图标:spec §0「accent 落点」(行标题、行图标) | **1 h**:`bodySmall`/12sp + `animateColorAsState(activeRow == rowIndex)` 在 onSurface 与 onSurfaceVariant 之间切换;图标与 accent 不动 |
| 非焦点行压暗 | 焦点不在行内时整行 tile 压到约 **60%**(白底 245→148);Home tab 海报行同样(均值 200→124);顶栏持有焦点时全部行都压暗 | 无(M8 删除 `InactiveRowAlpha`) | M8 有意偏离:spec §0「邻行压暗停用」 | (若推翻 1 h:`CategoryRow` 按 `activeRow` 做 alpha 0.6,300ms) |
| 纵向锚定 / 滚动 | 焦点行**顶到屏幕上沿**(行标题顶 ≈ 62dp),hero 与上方各行整体滚出画面,下方行照常露出;行距 168dp,聚焦行带元数据时 258dp | 焦点行锚定在 **2/3 高(360dp)**,上方行推入 hero 区并被 scrim 盖住,hero 时钟第 1 行起淡出;行距 134.7dp | M8 有意偏离:spec §0「纵向导航」——注意该条把下三分之一锚定称为「Google TV 首页做法」,**7.7.15 实测不是**;launcherx 未验证 | (若推翻 3 h:`anchorTop` 改 62dp、hero 随 shift 滚出而非淡出、scrim 顶边逻辑重写、看门狗与 `tgtRow` 复验) |
| 行内露出(peeking)与左箭头 | 第 5 张露 84px;行可左滚时左沿出 20×48px「‹」 | 行尾整齐,无露出、无箭头;超出屏幕靠 `xShift` 自算位移 | M8 有意偏离:spec §7「不做 peaking(行尾整齐)」 | (若推翻 2 h:卡宽公式加 0.2 张露出;箭头 0.5 h) |
| 背景 / scrim | 顶部:hero 海报 + 渐变;进入内容行后 hero 消失,**整屏换成纯色**——第 1 行黑 (0,0,0),第 2 行随聚焦项换成 rgb(29,34,41)→(49,54,61) 的横向渐变;行上没有 scrim | 用户壁纸全屏不变 + 底部 scrim #1C1B1F α0→0.8(锚点上方 60dp 起) | hero 海报:依赖内容。纯色随焦点换背景:纯视觉可做,但与 spec §0「壁纸即 hero,不换内置图」相抵 | (若要「背景随焦点卡取色」3 h,不建议) |
| 时钟 | 顶栏 20sp 灰,无日期 | hero 84sp accent + 24sp 日期 | M8 有意偏离:spec §0「hero 内容 = 大字时钟 + 日期」 | (顶栏时钟 1 h,见上) |
| 字体 | 系统 Google Sans / Roboto | DM Sans(三档字重内置) | M8 有意偏离:spec §0「字体:保留 DM Sans」 | (换 `FontFamily.SansSerif` 0.5 h) |
| 颜色 | **全程单色**:黑底、白 / 灰两级文字、白胶囊焦点,没有任何强调色 | tv-material 中性色阶 + Material 紫 accent(#D0BCFF)落在行标题 / 行图标 / 时钟 / pill 图标 | 纯视觉或布局(可以做) | **0 h**:选「白」预设(#F5F5F5,2026-09-18 已加)即得单色首页 |
| 动效 | 静态截图无法量化(tab 指示条滑动、行滚动、tile 缩放);Leanback 默认约 150ms | 进 300 / 出 500 / 按下 120ms(tv-material)+ 300ms 行位移 | 纯视觉或布局——但无法从截图判断差距,不估 | — |
| 设置入口 | 顶栏齿轮 → 系统设置;头像 → 账号 | pill 齿轮 → 应用自己的 GearMenu;屏保按钮 | 齿轮:已等价。头像:依赖 Google 服务 | — |
| 无横幅应用的 tile | 启动器自己合成:灰底 + 居中图标(Settings) | 图标边缘色底 + 居中图标 | 已等价(底色策略略异) | — |

**7.7.15 与 launcherx 的不确定项**(本次无法验证,不进表):pill 导航的确切几何、圆形 Your apps 图标的直径与间距、Ambient 卡片、以及 launcherx 的纵向锚定位置。

## 3. 可行性判断

### 3.1 能靠多近

能做的都是**骨架层**:网格(边距 / 卡距 / 圆角 / 三档尺寸)、行标题字号与焦点提亮、聚焦标签、顶栏几何、行尾「+」、单色配色。做完之后,UnitedU 的「行 + 卡片 + 焦点」与 Android TV Home 的 Apps tab 在像素尺寸上一致(6 档 = 132dp、8 档 = 96dp、12dp 卡距、4dp 圆角),观感差异只剩 M8 有意保留的四件事:1.1 倍 + 描边的焦点语言、无压暗、下三分之一锚定、DM Sans——这四件是 Gordon 在 spec §0 里选过的,推翻要另起决策,不算差距。

永远做不到的是**重心层**:原生首页 60% 以上的画面(hero 轮播、内容行、促销卡、搜索、通知、账号)全部由 Google 后台喂数据,或者本身就是推荐 / 赞助位。UnitedU 没有这些数据源,而且「零广告、零推荐,只有你放上去的应用」(DESIGN §1)把它们明确划到产品之外——调研 §3.1 也证实 Google 自己的 Apps only 模式仍保留赞助内容,所以「关掉推荐的 Google TV」也不是 UnitedU 的目标形态。**结论:UnitedU 首页与 Google TV 首页的最终关系是「同一套骨架、不同的重心」——它更像 Google TV 的 Apps 页而不是 Home 页,这是产品原则决定的,不是工程差距。**

### 3.2 「尽可能还原」总估时

| 项 | 估时 |
|---|---|
| 网格常量按实机(54 / 12 / 4,三档 160.8 / 132 / 96) | 1.5 h |
| 行标题 12sp + 焦点行提亮 | 1 h |
| 聚焦卡片标签(三态设置) | 3 h |
| 顶栏几何 44dp | 0.5 h |
| 行尾「+」卡 | 5 h |
| 单色配色 | 0 h |
| **可做项小计** | **11 h** |
| 模拟器回归:spec §5 的铁律 2–7 清单(看门狗、菜单开合、后台返回、包更新缩行、空桌面、长按)+ 三档 × 标题三态截图对照 | 3 h |
| A95L 一轮:装包、三档横幅烧字肉眼、导航、长按、待机 | 2 h |
| **总计** | **16 h ≈ 2 个工作日** |

若加「圆形 Your apps 图标行」+ 1.5 天 → 3.5 天;若同时推翻 spec §0 的四项有意偏离(焦点 1.29 无描边 3 h、压暗 1 h、顶部锚定 3 h、peeking 2 h、Roboto 0.5 h、顶栏时钟 1 h)再 + 10.5 h 与一轮复验 3 h。

### 3.3 主要风险

1. **焦点铁律(CLAUDE.md 1–7)是最大的回归面。**行尾「+」给每行加一个可聚焦节点,直接触碰铁律 3(恢复责任表)与铁律 5(目标与当前位置分开):`HomeScreen` 里 `coerceIn(0, lastIndex)` 的夹取至少四处(还原效果、`targetIndex`、`CategoryRow` 挂点、`cardAt`),漏一处就是「行变短后请求打在不存在的格子上、循环空转 60 帧」那类故障;`cardAt` 不把「+」判成非卡片,长按会弹出幽灵菜单。编辑页 2026-09-11 的「+」被量成 0 宽事故(铁律 1)在首页不会重演——`wrapContentWidth(unbounded)` 已在 —— 但要复验。
2. **聚焦标签不能是可聚焦节点,行高必须常驻预留**:否则聚焦时行高跳动,spec §5「焦点行标题顶始终 720px ± 2」当场失守。
3. **网格常量一改,M8 的像素基线全部作废**(spec §8 已声明 mock-A 是新基线):要重截基线图;A95L 的横幅烧字结论(0.775×)也要按 132dp(0.825×)重看——方向是变清楚,风险低。
4. **有意偏离若推翻**:焦点 1.29 会把 `rowVerticalPad` 从 6.5dp 推到约 13dp,行距、锚点、`zIndex` 覆盖全部重算;左轴缩放要自写 `graphicsLayer`,偏离 spec §0「只用库默认」的实现方式。
5. **对标对象本身不稳**:7.7.15 与 launcherx 已有三处已知不同(§1.1),Google 2025 还在改;按实机数值对齐要写明「对齐的是哪一版」,否则下次量测又是一堆差异。

## 4. 推荐子集(每小时视觉收益最高)

1. **聚焦卡片下方显示应用名(仅焦点卡,14sp 白)— 3 h。**两代 Google 首页都靠它让人认出应用,且不增加常驻文字;对 UnitedU 尤其值:图标回落卡(Settings 这类)没有名字时只能靠猜。
2. **网格常量按实机 54 / 12 / 4,三档 160.8 / 132 / 96dp — 1.5 h。**行密度、圆角、露边一眼对上;中档 132dp = 264px 比现在 248px 更接近 320px 横幅,烧字更清。
3. **行标题 12sp + 焦点行白 / 其他行灰 — 1 h。**这是原生「hero > 焦点行 > 其他行」层级感的来源;accent 与图标保留,不动 spec §0。
4. **顶栏中线 44dp — 0.5 h。**与实机顶栏同高,单独看不出,配合前两项后不再「pill 偏低」。
5. (可选)**行尾「+」卡 — 5 h。**结构相似度最大的一项,也是唯一动焦点账本的一项;建议放在 M4b(移动位置 / 原地编辑)那轮一起做,共用一次焦点复验。

前四项合计 **6 h**,不触碰任何 spec §0 决定,不新增可聚焦节点。

## 附录 A. 像素量测(1920×1080,1dp = 2px)

量测脚本用 PIL 沿行 / 列扫描亮度阈值取边、按首字母取 cap height,字号按 cap ≈ 0.71–0.72 em 反推。

**Android TV Home 7.7.15**

| 项 | 数值 |
|---|---|
| 安全边 | 左 108px(54dp),右沿 1812px |
| 顶栏 | 文字 / 图标中线 y≈88px;tab 文字 cap 23px(≈16sp),未选 / 选中未聚焦 lum 142,聚焦 lum 234;指示条 y 118–122(4px 厚),选中 52px 宽、聚焦 86px 宽;右侧图标 40px(20dp)@ x 1438 / 1534 / 1630(48dp 间距);时钟 cap 29px(≈20sp)lum 181,右沿 1811 |
| hero | logo 23px 高 @ y 204;标题 cap 40px(≈28sp)@ y 254;「Recommended For You」cap 23(≈16sp);简介 cap 20(≈14sp)灰;Watch Now 190×64px;海报从 x≈1150 起,x=1500 处亮度 y 100→750 由 68 降到 4;轮播圆点 @ (1725–1800, 458);三次截图三部不同片 = 自动轮播 |
| 提示卡 | 108–1812 × 431–591(1704×160px),底 rgb(36,26,24);Dismiss 158×56px |
| Favorite Apps 行 | 标题 cap 17px(≈12sp);tile 192×108px(96×54dp),8 张 + 7×24px = 1704px 整;聚焦 248×140px(×1.29),左沿钉在 108、垂直居中;标签 cap 19px(≈14sp),只在聚焦时出现;未聚焦 tile 底 rgb(33,39,47) vs 占位色 rgb(47,63,79)(≈70%) |
| 内容行 | 卡 408×230px(204×115dp),间距 24px,4 张 + 露出 84px;圆角 ≈ 8px;聚焦 491×276px(×1.2),左沿不动;焦点行标题 cap 顶 y=129(≈62dp);行距 336px 无焦点 / 516px 带元数据;元数据:CJK 标题 30px 高(≈16sp)白、两行 cap 17px(≈12sp)灰 lum 140;同一海报未聚焦行均值 124 vs 聚焦行 200(≈62%);左箭头 20×48px @ x 34 |
| 行进入后的背景 | 第 1 行 (0,0,0) 全黑;第 2 行 rgb(29,34,41)→(49,54,61) 横向渐变;底部大卡 836×348px 底 rgb(56,56,56),标题 cap 29px(≈20sp) |
| Apps tab | 标题 cap 42px(≈28sp);Installed Apps cap 17px(12sp);tile 264×148px(132×74dp),间距 24px,6 张 = 1704px 整;圆角 ≈ 6–8px;聚焦 341×192px(×1.29),标签「Settings」cap 20px(≈14sp);未聚焦行白底 245→148(60%),顶栏持焦时同样压暗 |

**UnitedU M8(默认设置:material、中档 6 张、标题关、内置壁纸)**

| 项 | 数值 |
|---|---|
| 安全边 | 116px(58dp) |
| pill 组 | 1620–1804 × 56–152(184×96px = 92×48dp);底 rgb(18,18,20)(#1C1B1F α0.65 压黑壁纸);图标字形 30×32px(20dp 框);聚焦圆 88px(44dp)#E6E1E5,图标反色 |
| hero 时钟 | 数字 121px 高(84sp DM Sans Medium),顶 y 348,accent #D0BCFF;日期 cap 34px(24sp)@ y 549 |
| 行标题 | cap 23px(16sp Medium)accent;图标字形 32×36px(24dp 框);行 0 标题行框 720–768px |
| 卡片 | 248×140px(124×69.75dp),间距 40px(20dp),圆角 16px(8dp);聚焦:内容 273×153(×1.1,中心轴)+ 描边 rgb(147,143,153) 6–7px 跨边缘,占位 ≈ 280×161px;无标签、无光晕、无压暗 |
| 纵向 | 每下移一行整块上移 269px(134.7dp);焦点行标题顶恒 720px;hero 第 1 行起淡出 |
| scrim | y 600 起到底,#1C1B1F α0→0.8;在黑壁纸上 x=1500 列亮度 0→19 |
