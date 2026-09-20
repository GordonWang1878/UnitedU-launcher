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
| `topBarIcon` | 32 dp | 药丸组内的图标 |
| `topBarIconGap` | 8 dp | 组内图标间距 |
| `topBarGroupGap` | 22 dp | 两组药丸之间(**本线只有一组,保留此 token 仅作记录**) |
| `heroHeight` | 192 dp | 顶部留给壁纸的高度 |
| `rowTitleToCard` | 12.5 dp | 行标题底 → 该行首卡顶 |
| `rowPitch` | ~~125.5 dp~~ → **143.5625 dp**(中档) | Google 实测 125.5 是**拉丁界面**的数字;中文行标题需要 23 dp 行盒,行距随之撑开。裁定 R15,详见 `GtvLayout.rowPitch` 的 KDoc |
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

中档是 Google 的实测值;小与大按 ×0.8 / ×1.25 取整到 16:9 能整除的值。

**`rowPitch` 的实际公式(R15 之后)**:`ROW_TITLE_LINE(23) + ROW_TITLE_TO_CARD(12.5) + 2×(FOCUS_OUTSET+FOCUS_STROKE)(14) + 卡高 + ROW_GAP(8)`,中档 = 143.5625 dp。**本文原先写的「按中档实测 125.5 反推」已作废**:125.5 是 Google 在拉丁界面上量的,15 dp 的行标题盒会把中文裁成别的字(实测:视频→初频、直播→吉拫),把盒子撑到中文需要的 23 dp 又想保住 125.5,`ROW_GAP` 得压到约 −11 dp、焦点描边会压在下一行标题上。**文字不能裁,行距让步** —— 这是本线唯一一处「照搬 Google」真的搬不动的地方。

### 2.3 字号

| 用途 | 值 |
|---|---|
| 顶栏时钟 / 字标 | 20 sp |
| 二级页大标题(设置页、Apps 页一类) | 32 sp |
| 行标题 | 14 sp |
| 卡片标题 / 应用名 | 14 sp |
| 菜单项 / 磁贴 | 16 sp |

**84 sp 大字时钟取消**(B2)。

### 2.4 颜色

| token | 值 | 说明 |
|---|---|---|
| `pillTrack` | `#282A2C` | 药丸组底色 |
| `panelBg` | `#171A1F` | 快捷设置面板底 |
| `menuBg` | `#0E0E0F` | 长按菜单全屏底 |
| `menuItemIdle` | `#161718` | 未聚焦菜单项 |
| `focusFill` | **主题色** | B6:画法照 Google 的浅色实填,颜色用用户选的主题色;文字自动取对比色(深/浅按亮度判) |
| `tabSelected` | 主题色 | 顶栏选中态 |
| `tabFocusedUnselected` | `#585E64` | 顶栏聚焦但未选中 |
| `cardFocusStroke` | 主题色,2 dp | 内容卡描边 |
| `scrimOverlay` | 黑 65%(待真机调) | 浮层压暗 |

## §3 首页

自上而下:**顶栏(常驻)→ hero 区(纯壁纸,192 dp)→ 应用行 ×1–5**。

- **hero 区什么都不放**(B3),壁纸直接透出来。
- 应用行从 hero 下面开始,行内卡片 16:9(B8:**不改圆形图标**,banner 优先 → 自定义卡片图 → 无 banner 回落成图标居中 + 纯色底,三条路全保留)。
- **行内横向位移自己算**:目标偏移 = −(焦点卡索引 × pitch),焦点卡永远钉在 `contentKeyline`(实测确认:连按 7 次右键焦点 bounds 恒为 58 dp 起)。超出屏幕右缘的卡自然裁切,**不做任何「整行放得下」的反算**。
- 纵向:焦点行锚定照旧,位移自己算。
- **邻行压暗仍然不做**(B4 只裁定了浮层压暗)。底部 scrim 保留。

## §4 顶栏

左起:**药丸组(设置 / 屏保,左缘对齐 58 dp)→ …大片留白… → 右侧时钟 + 「UnitedU」字标**。Google 在留白处放的是搜索 / Home / Apps 三个 tab,我们没有对应功能,整组省略。

- **药丸组 A 整个省略**:我们没有搜索、没有 Home/Apps 两个 tab、没有账号头像,照搬空壳没有意义(见 §9)。
- **唯一那组药丸靠左,左缘对齐 `contentKeyline`(58 dp)**(Gordon 2026-09-20 裁定,方案 1,对照图 `docs/screenshots/gtv/18-mock-topbar-variants.png`)。这样保留 Google「左边是功能、右边是信息」的结构;左上角比 Google 空一块,但 hero 区本来就是留白给壁纸,两者连成一片。
- **药丸组内容**:`设置`(32 dp)+ `屏保`(32 dp),间距 8 dp,底色 `pillTrack`,整体高 36 dp、全圆角。这是现有右上 pill 组换皮 + 移位。
- **右侧**:时钟 20 sp + 「UnitedU」字标,与顶栏垂直居中。12/24 小时跟系统,日期显示开关保留(日期跟在时钟后面,同字号)。
- **折叠**:焦点进入应用行时顶栏折叠成一个向上箭头(照 Google)。折叠/展开的触发点与动画曲线**实测报告 §11 还没量**,实现时先用 200 ms `FastOutSlowIn`,真机验收再调。

## §5 焦点画法(B1:四种分控件混用)

| 控件 | 画法 |
|---|---|
| 内容卡(16:9) | **不缩放**;在布局框**外扩 5 dp** 处画 **2 dp** 描边(主题色)+ 柔光。现有「1.1 倍 + 3 dp 描边」作废 |
| 顶栏药丸项 | 选中 = 主题色实填 + 对比色文字;聚焦未选中 = `#585E64` 实填 |
| 菜单项 / 快捷设置磁贴 | 主题色实填 + 对比色文字 |
| 搬运中的卡 | 沿用现有 accent 描边换色以区分,但粗细改成 2 dp、同样外扩 5 dp |

**外扩 5 dp 的描边不能用 `border` 直接画**(`border` 画在布局框上),要用 `drawBehind` 在框外画,且父容器不能 clip 掉 —— 与铁律 §1 的 `unbounded` 同一类问题,实现时先写一个最小例子验证再铺开。

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
| 首页顶栏 | pill 组换皮 + 加时钟/字标 + 折叠行为;删掉 84 sp 大字时钟与日期块 |
| `AppCard` | 焦点画法换成「不缩放 + 外扩描边」 |
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
- 顶栏折叠的触发点与动画曲线(实测未量)。
- 纵向换行时整页的位移规则(实测未量,横向已确认钉左基准线)。
