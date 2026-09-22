# spec:gtv 线 —— 全 app 复刻 Google TV 外观

> **这是什么**:在 main 之外单开一条 UI 线,把 UnitedU 的全部界面换成 Google TV(`launcherx` 1.0.976298245)的外观,做完与现在的 UnitedU 并排比,由 Gordon 挑一套当 1.0。
> **依据**:尺寸与画法来自实测报告 `docs/research/2026-09-20-google-tv-launcherx-measurements.md`;每一项取舍的裁定见 `docs/research/2026-09-20-gtv-vs-unitedu-comparison.md` §D,本文不重复论证,只写落地。
> **对照图**:`docs/screenshots/gtv/`(带 `INDEX.md`)。
> **不变的产品定义**:零广告、零推荐,只有用户自己放上去的应用。外观照搬 Google,**内容与功能仍是 UnitedU 的**。

## §0 不变量(改任何一条之前回来读)

1. **焦点七条铁律照旧全部适用**(`CLAUDE.md`):不用任何可滚动容器、位移自己算 + `wrapContentWidth(unbounded = true)`、焦点落地只信目标自报 `isFocused`、每个浮层自己负责焦点恢复、目标与当前位置每个分量都拆开、守卫与依赖成对出现、不用一次性布尔闩。**本线新增的每一个浮层都要在 CLAUDE.md 的焦点责任表里加一行。**
2. **行尾裁切不等于 0 宽**。Google 的行尾卡是被屏幕右缘裁切、仍正常参与布局(实测宽 74 px 仍可聚焦);铁律 §1 里「被量成 0 宽永远聚焦不到」是父约束夹出来的另一回事。本线**必须继续给行容器加 `wrapContentWidth(Alignment.Start, unbounded = true)`**,靠 `Modifier.offset` 自算位移,靠屏幕边界自然裁切 —— **不能**用 `clipToBounds` 之外的任何约束去实现 peeking。
3. **密度换算**:实测在 1920×1080 @ 320 dpi(density = 2.0)下取得,A95L 的 override 尺寸与密度逐位相同,所有 dp 值 1:1 可用。
4. 本线**不动** main;分支 `gtv`,worktree `.claude/worktrees/gtv`。
5. 不推 GitHub,直到 Gordon 说「推」。

## §1 并存:独立包名(第一个任务)

- `applicationId` = `com.uniteduone.launcher.gtv`,应用名「UnitedU GTV」。
- 与现有 UnitedU 同时装在 A95L 上来回比,**两边设置与图片各存各的**:数据目录随包名天然隔离,`layout.json` / `settings.json` / `titles.json` / `hidden-inputs.json` / `library/` 全部独立,不做任何迁移、不读对方的目录。
- 签名复用 `~/.unitedu/release.jks`(同一把 key 不同 applicationId 可以共存)。
- 这一步单独成一个任务先落地,后面每个任务都能装机对比。

## §2 设计 token

新建一个 `GtvTokens` 对象集中所有数值,**其余文件不得出现字面量**。

### 2.1 尺寸

| token | 值 | 来源 |
|---|---|---|
| `contentKeyline` | 58 dp | 左基准线,行标题/首卡/头像都对齐 |
| `topBarHeight` | 36 dp | 药丸组高度 |
| `topBarTop` | 34 dp | 顶栏距屏幕上缘 |
| `topBarIconBox` | 32 dp | 药丸组内图标按钮的触控/焦点框(a11y bounds,两个图标共用同一个框) |
| `topBarGearGlyph` | 21.5 dp | 设置(齿轮)图标本身的绘制大小(owner 反馈 Round 6 起与上面的框分开,见 `GtvLayout.TOP_BAR_GEAR_GLYPH` KDoc) |
| `topBarScreensaverGlyph` | 17.5 dp | 屏保(相册)图标本身的绘制大小,与齿轮不同(Google 两个图标本来就不一样大),见 `GtvLayout.TOP_BAR_SCREENSAVER_GLYPH` KDoc |
| `topBarIconGap` | 8 dp | 组内图标间距 |
| `topBarGroupGap` | 22 dp | 两组药丸之间(**本线只有一组,保留此 token 仅作记录**) |
| `heroHeight` | 192 dp | 顶部留给壁纸的高度 |
| `rowTitleToCard` | 12.5 dp | 行标题底 → 该行首卡顶 |
| `rowPitch` | ~~125.5 dp~~ 按公式算(中档不显示标题 140.5625 dp) | 行 band 顶到下一行 band 顶。R15/R25:125.5 是 Google 用 Latin 标题量的,CJK 行盒更高,改为 `GtvLayout.rowPitch()` 逐项相加(标题行盒 20 + 12.5 + 2×7 描边留白 + 卡高 + 标题 + `ROW_GAP` 8),不再凑这个数 |
| `cardGap` | 20 dp | 卡片横向间距 |
| `cardRadius` | 8 dp | 16:9 卡圆角 |
| `tileRadius` | 12 dp | 快捷设置磁贴圆角 |
| `menuItemSize` | 268×55 dp,全圆角 | 长按菜单项 |
| `qsTileSize` | 124×55 dp | 快捷设置磁贴,2 列 |

### 2.2 卡片三档(B5-a 裁定:三个固定尺寸,都允许行尾裁切)

| 档 | 卡宽 × 高 | pitch | 本屏宽下露出张数 |
|---|---|---|---|
| 小 | 122 × 68.5 dp | 142 dp | ≈ 6.4 |
| **中(默认)** | **153 × 86 dp** | **173 dp** | **≈ 5.2** |
| 大 | 192 × 108 dp | 212 dp | ≈ 4.3 |

中档是 Google 的实测值;小与大按 ×0.8 / ×1.25 取整到 16:9 能整除的值。行高随档位缩放:~~`rowPitch` = 卡高 + 39.5 dp(按中档实测 125.5 反推)~~ → R15/R25 起 `rowPitch` = `ROW_TITLE_LINE` 20 + `ROW_TITLE_TO_CARD` 12.5 + 2 × 7(描边留白)+ 卡高 + 标题(显示时 4 + 20)+ `ROW_GAP` 8,中档不显示标题 = 140.5625 dp(见 §2.1 与 `GtvLayout.rowPitch` KDoc)。

### 2.3 字号

| 用途 | 值 |
|---|---|
| 顶栏时钟 / 字标 | 16 sp †† |
| 二级页大标题(设置页、Apps 页一类) | 32 sp |
| 行标题 | 14 sp † |
| 卡片标题 / 应用名 | 14 sp |
| 菜单项 / 磁贴 | 16 sp |
| 长按菜单左侧 banner 下的应用名(`MENU_BANNER_NAME_TEXT`) | 12 sp(owner 反馈 Round 5,从 16 改;参考图 `16-app-longpress-menu.png` 量「Live TV」cap height 8.5dp 反推) |

**84 sp 大字时钟取消**(B2)。

† **Ruling R25(2026-09-21,owner 真机反馈 Round 5)推翻 R19,改回 14 sp**:R19(2026-09-20)
曾以「14 sp 的中文字形更小、还会把 `rowPitch` 再往下推一次」为由维持 16 sp,只改这张表。
owner 这一轮拿真机与 Google TV 并排比对,判定整条产品线的字号/图标普遍偏大,行标题是第一个
点名的例子;取舍标准是「像素级贴近 Google」优先于 R19 的 legibility 顾虑——不是没考虑过
R19 的理由,是这次明确认为不该以牺牲 Google 一致性为代价。`ROW_TITLE_LINE`(GtvLayout.kt)
在 14 sp 下重新装机实测为 20 dp(不是把 23dp 按字号比例折算——CJK 行盒只能重测,R15 就是
这个教训),`rowPitch` 与相关 JVM 测试的三处断言值一并更新,详见该常量的 KDoc 与
`.superpowers/sdd/2026-09-20-gtv-line/owner-feedback-fix-report.md`「Round 5」。
Google 的参考对象是拉丁字母(见 §C 的 cap-height 反推),我们的行标题恒为中文,按拉丁字号
反推的具体数值本就是刻舟求剑——这条没有变,变的是这一次 owner 判断「字号本身」仍应贴 Google
的 14 sp,只是行盒高度(不是字号)需要为 CJK 单独测量。

†† **owner 反馈 Round 5**:原 20 sp 借用的是研究文档 §10「快捷设置时钟(`11:11 AM`)」那一行的
cap-height 反推值——但那是 Google 快捷设置面板里刻意放大的「大字」时钟,不是常驻顶栏右上角的
小时钟,参照物本身选错了。重新在参考截图上量常驻顶栏「10:48 | Google TV」的 flat-top 字母
(`T`/`V`,避开圆形字母的 overshoot),cap height ≈ 24px = 12dp,按 cap≈0.71em 换算
≈16.9sp,取整改为 16 sp。详见 `GtvLayout.TOP_BAR_CLOCK_TEXT` 的 KDoc。

### 2.4 颜色

| token | 值 | 说明 |
|---|---|---|
| `pillTrack` | `#282A2C` | 药丸组底色 |
| `panelBg` | `#171A1F` | 快捷设置面板底 |
| `menuBg` | `#0E0E0F` | 长按菜单全屏底 |
| `menuItemIdle` | `#161718` | 未聚焦菜单项 |
| `focusFill` | **主题色** | B6:画法照 Google 的浅色实填,颜色用用户选的主题色;文字自动取对比色(深/浅按亮度判) |
| `tabSelected` | 主题色 | 顶栏选中态 |
| ~~`tabFocusedUnselected`~~ | ~~`#585E64`~~ | ~~顶栏聚焦但未选中~~ → 本线顶栏没有 tab(§4),该 token 零调用,整枝审查 2026-09-22 删除 |
| `cardFocusStroke` | 主题色,2 dp | 内容卡描边 |
| `scrimOverlay` | 黑 65%(待真机调) | 浮层压暗 |

## §3 首页

自上而下:**顶栏(常驻)→ hero 区(纯壁纸,192 dp)→ 应用行 ×1–5**。

- **hero 区什么都不放**(B3),壁纸直接透出来。
- 应用行从 hero 下面开始,行内卡片 16:9(B8:**不改圆形图标**,banner 优先 → 自定义卡片图 → 无 banner 回落成图标居中 + 纯色底,三条路全保留)。
- **行内横向位移自己算(Ruling R20,2026-09-20 owner 真机走查推翻原稿)**:原稿照搬 Google「焦点卡永远钉在 `contentKeyline`、整行按索引 × pitch 平移、不做整行放得下的反算」——那条规则的测量没错(连按 7 次右键焦点 bounds 恒为 58 dp 起),错的是前提:Google 的行是无边界推荐流,我们的行是有限应用列表,常见 5 张中档卡 845 dp 本来就放得下,照搬会从第一次按右键起就把第 1 张推出屏幕、右边空出约 230 dp 死白。**现在:焦点卡完全可见时行不动;只在焦点卡的视觉右缘(含聚焦缩放 + 描边溢出,Round 4 §5)会超出右侧 `contentKeyline` 对称线时,才左移刚好这么多**(`GtvLayout.rowShiftX`)。超出屏幕右缘的卡仍自然裁切、不砍宽度。
- 纵向:焦点行锚定照旧,位移自己算(`rowShiftY = −activeRow × rowPitch`)。
- **邻行压暗仍然不做**(B4 只裁定了浮层压暗)。~~底部 scrim 保留~~ → **Ruling R24(Round 3)**:随行走的底部 scrim 已删,换成钉在屏幕上的二维暗色衰减——横向渐变(`HeroGradientHPlateau` 0.42 → `HFadeEnd` 0.88,近端黑 α0.96)× 纵向渐变(`VFadeStart` 0.30 → `VPlateau` 0.66)两层叠乘,复现参考截图 7×8 亮度网格「右上角一块图、其余整体黑底」的形状;不随行位移移动,待机/屏保时随 `contentAlpha` 一起淡出。
- **待机与屏保(Ruling R23 终审 2026-09-20 + R26 2026-09-21,推翻 R16/R9)**:三个候选里 owner 选「只留顶栏小时钟」——待机 `CLOCK_ONLY` 档不再在 hero 区淡入 84 sp `HeroClock`,而是顶栏那行 `ClockWordmark`(16 sp)单独不淡出(`topBarClockAlpha` 与药丸组的 `contentAlpha` 分开);系统屏保 `UnitedUDream` 也改画同一行 `ClockWordmark`(右对齐 58 dp、顶 34 dp,照片上带淡阴影),不再保留 R9 的大字时钟;`HeroClock` 在 gtv 线零调用(main 线仍用,函数保留)。桌面自定义屏保(`Screensaver`)按 R23 **不叠时钟**,照片上不浮任何 UI。

## §4 顶栏

左起:**药丸组(设置 / 屏保,左缘对齐 58 dp)→ …大片留白… → 右侧时钟 + 「UnitedU」字标**。Google 在留白处放的是搜索 / Home / Apps 三个 tab,我们没有对应功能,整组省略。

- **药丸组 A 整个省略**:我们没有搜索、没有 Home/Apps 两个 tab、没有账号头像,照搬空壳没有意义(见 §9)。
- **唯一那组药丸靠左,左缘对齐 `contentKeyline`(58 dp)**(Gordon 2026-09-20 裁定,方案 1,对照图 `docs/screenshots/gtv/18-mock-topbar-variants.png`)。这样保留 Google「左边是功能、右边是信息」的结构;左上角比 Google 空一块,但 hero 区本来就是留白给壁纸,两者连成一片。
- **药丸组内容**:`设置`(触控框 32 dp,图形 21.5 dp)+ `屏保`(触控框 32 dp,图形 17.5 dp),间距 8 dp,底色 `pillTrack`,整体高 36 dp、全圆角。这是现有右上 pill 组换皮 + 移位。owner 反馈 Round 6(2026-09-21):图形大小原先与触控框共用 32 dp 一个数字,被判「显得过大」,改为两个图标各自独立的绘制尺寸,触控框本身不变(见 `GtvLayout.TOP_BAR_ICON_BOX`/`TOP_BAR_GEAR_GLYPH`/`TOP_BAR_SCREENSAVER_GLYPH`)。
- **右侧**:时钟 16 sp(owner 反馈 Round 5,原 20 sp 参照物选错,见 §2.3 ††)+ 「UnitedU」字标,与顶栏垂直居中。12/24 小时跟系统,日期显示开关保留(日期跟在时钟后面,同字号)。
- ~~**折叠**:焦点进入应用行时顶栏折叠成一个向上箭头(照 Google)~~ → **Ruling R21(2026-09-20 owner 真机走查):不折叠,顶栏常驻**。折叠箭头属于 Google 上一代设计,目标版 1.0.976298245 的顶栏本身不折叠;`GtvTopBar` 的 `collapsed` 参数、`TOP_BAR_COLLAPSE_MS` 已删。

## §5 焦点画法(B1:四种分控件混用)

| 控件 | 画法 |
|---|---|
| ~~内容卡(16:9)~~ **应用卡(app tile)** | ~~不缩放;在布局框外扩 5 dp 处画 2 dp 描边~~ → **owner 反馈 Round 4(2026-09-21)**:我们的首页 100% 是 app,不是 Google 无边界推荐流里的 content card,套用 content card 的静态外扩描边是错认了分类。Google 对 app tile 的处理是**聚焦放大 `APP_FOCUS_SCALE` = 1.10 倍**(目标版资源 fraction 0x7f0a0081 = 1.099976;整枝审查 C 从像素反推的 1.105 改为资源原值)+ 描边贴着**缩放后**边缘外扩 2 dp gap + 2 dp stroke(`card_focused_frame_outer_stroke_width`),缩放只走 `graphicsLayer`、不改布局(标题不跟着动);时长进出对称 150 ms(`default_focused_animation_duration_ms`),曲线 `AccelerateDecelerate`(animator 未写 interpolator 的平台默认)。**Ruling R28 柔光**见下方。content card 那套「不缩放 + 外扩 5 dp」只剩 `RowIconPicker` 的行图标格子在用 |
| 顶栏药丸项 | 聚焦 = 主题色实填 + 对比色图标(`TOP_NAV_FADE_IN/OUT` 100/200 ms);「选中/未选中」是 Google tab 的概念,本线无 tab,不适用 |
| 菜单项 / 快捷设置磁贴 | 主题色实填 + 对比色文字 |
| 搬运中的卡 | highlight 色 2 dp 描边,画在聚焦描边**外侧**:外扩 = 当前缩放溢出 + 5 dp(整枝审查 A,2026-09-22:此前固定画在布局框外 5 dp,被放大后的卡片整条盖住;搬运中焦点恒在被搬的卡上,等于从没露出过) |

**Ruling R28(2026-09-21,owner 真机反馈 Round 7「从沙发上完全感觉不到动效」)——焦点柔光**:描边只是焦点处理的一半,Google 在描边外缘之外还铺一层大面积指数衰减的辉光,我们此前一点没画。数值(模拟器实测 Google 剖面,`GtvLayout.APP_FOCUS_GLOW_*`):紧贴描边外缘的目标**亮度增量** 48/255 ≈ 0.188(是增量不是 alpha——画布 srcOver 在伽马域混合,`增量 = alpha × (前景 − 背景)`,alpha 由 `focusGlowAlphaFor` 按 accent 的实际亮度反推,背景假定 0.07,上限 0.5);半衰期 16 dp;0→30 dp 是实测数据区、纯指数;30→60 dp 收尾段再乘一条 smoothstep 平滑收到 0(30 dp 硬截断在近黑背景上是一道看得见的台阶)。画法是一串 2 dp 宽的同心圆角矩形描边,**只是绘制、不进任何布局量**(`appFocusOverflow`/`rowPitch`/`rowVerticalPad` 都不加它),柔光会淡淡盖到邻居卡与上一行标题区,Google 本身就是这样。定标后逐点比 Google 剖面平均 0.98。

**Ruling R27(2026-09-21,同一轮)——浏览位移的缓动**:首页行 x/y 位移、编辑页纵向与行内横向位移(四处)一律 `tween(250 ms, CubicBezierEasing(0.18, 1, 0.22, 1))`——曲线逐字来自 launcherx `anim/tv_easing_browse`,时长取 `lb_browse_rows_anim_duration` 与设计 token `gtvm3_sys_motion_duration_medium1` 两个同源候选收敛的 250(没有「曲线配时长」的直接证据,如实记录);取代此前 Material 通用的 300 ms 减速曲线。焦点缩放/淡入淡出仍是 150 ms AccelerateDecelerate,两套曲线各管一段、不互换。图片选择器的翻页位移刻意不换(Google 无对应物)。

**外扩描边不能用 `border` 直接画**(`border` 画在布局框上),要用 `drawBehind` 在框外画,且父容器不能 clip 掉 —— 与铁律 §1 的 `unbounded` 同一类问题,实现时先写一个最小例子验证再铺开。

## §6 菜单与浮层(B9)

- **长按卡片菜单 / 齿轮菜单**:改成全屏 `menuBg` 底 + 左侧该应用的 banner 图与名字 + 右侧一列整宽药丸(268×55 dp,全圆角)。动作项内容不变(打开 / 卸载 / 修改标题 / 更改图标 / 移动位置 / 从当前分类移除)。
- **快捷设置**:现有设置页保留为整屏两栏;**新增**一个从右侧浮出的快捷设置 sheet(`panelBg`,2 列磁贴 124×55 dp,圆角 12 dp),放最常用的几项(屏保、壁纸切换、输入源、卡片大小),齿轮长按或某个入口打开——**具体入口与磁贴清单在 plan 阶段定**。
- **浮层压暗**:浮层在场时背景压暗 `scrimOverlay`。

## §7 字体(B7)

- 内置 `Google Sans Flex`(SIL OFL 1.1,`ofl/googlesansflex/` 变量字体,轴 `GRAD/ROND/opsz/slnt/wdth/wght`),替换 `app/src/main/res/font/dm_sans_*.ttf`。
- **OFL 要求随软件分发许可证全文**:把 `OFL.txt` 放进 `app/src/main/assets/licenses/` 并在关于页列出。
- **中文行为不变**:Google Sans Flex 无 CJK 子集,中文照旧回退系统 Noto Sans CJK;换字体只影响数字、英文、标点。
- 变量字体在 Android 上用 `FontVariationSettings` 指定 `wght`;先只用 400/500/700 三档。

## §8 受影响的现有实现(改动清单)

| 位置 | 改什么 |
|---|---|
| `HomeLayout` | 「每行 N 张反算卡宽」整个作废,改成三个固定卡宽 + pitch |
| 首页顶栏 | pill 组换皮 + 加时钟/字标(16 sp);~~折叠行为~~(R21 不做);删掉 84 sp 大字时钟与日期块(待机 CLOCK_ONLY 与系统屏保按 R23/R26 只留顶栏小时钟) |
| `AppCard` | 焦点画法换成 app tile 处理:缩放 1.10 + 贴缩放后边缘的 2+2 dp 描边 + R28 柔光(`gtvAppFocusFrame`);~~不缩放 + 外扩描边~~ 已被 Round 4 推翻 |
| `TopPills` | 换成药丸组 B 的样式 |
| `GearMenu` | 换成全屏 banner + 药丸列 |
| 设置页 | 「卡片大小」三档语义从「每行几张」改成「卡片多大」;新增快捷设置 sheet |
| `Theme.kt` | 新增 `GtvTokens`;`focusFill` 走主题色 + 对比色文字 |
| `res/font/` | DM Sans → Google Sans Flex |
| 关于页 | 加 OFL 许可证 |

## §9 不做

- 不做搜索、不做 Home/Apps 两个 tab、不做账号头像 —— 我们没有对应功能,照搬是空壳。
- 不做圆形应用图标(B8)。
- 不做「焦点落在卡上整屏换剧照」—— 我们没有剧照。
- 不做邻行压暗。
- 不做推荐位、不做内容行。

## §10 验收

1. 模拟器 `unitedu-gtv` 上并排:左边跑 Google TV(HOME 角色)、右边装 UnitedU GTV,逐屏截图对比。
2. A95L 真机装 `com.uniteduone.launcher.gtv`,与现有 UnitedU 来回切换比。
3. 焦点回归:每个新浮层按铁律 §2/§4/§6/§7 自测,并在 CLAUDE.md 焦点责任表里补行。

## §11 spec 阶段还没定的

- 三档里「小」「大」两个卡宽的具体值(本文先给 122 / 192 dp,真机看过再定)。
- 快捷设置 sheet 的入口与磁贴清单。
- ~~顶栏折叠的触发点与动画曲线(实测未量)~~ → R21 裁定不折叠,关闭。
- ~~纵向换行时整页的位移规则(实测未量,横向已确认钉左基准线)~~ → 横向按 R20(放得下不动、放不下只移够用的量),纵向 `rowShiftY = −activeRow × rowPitch`,两者缓动按 R27;关闭。
