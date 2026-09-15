# UnitedU M2(主题收敛 + 设置页 + 卡片三档)实施计划 — 草稿

> **状态:草稿,未开工。** 含「需 Gordon 决策」门,判断项他定后才执行;机械任务(Task A)已可做。
> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development 逐任务实施。步骤用 `- [ ]`。

**Goal:** 把散落的视觉常量收进一处主题调色板;搭起「UnitedU 设置」页与它的持久化和焦点账本;让行数(1–5)、卡片大小(每行 8/6/5 三档)、时钟、输入源行、待机可在界面里调,主题色可切预设或跟随壁纸主色。

**Architecture:** 新增 `Settings` 数据对象(读写 `settings.json`,与 `layout.json` 同目录),`Theme` 从写死常量改为「基准常量 × 档位系数 + 主题色」的求值。设置页是新浮层,按 `CLAUDE.md` 七条铁律单独立焦点账本(不用任何可滚动容器,位移自算)。卡片尺寸不再是 `Theme` 里的死值,而由「档位」推导,首页与编辑页共用同一套推导。

**Tech Stack:** 与 M1 同(Kotlin 2.0.21 / Compose / AGP 8.7.3)。新增 `TvInputManager`(输入源枚举)、`Palette`(跟随壁纸主色,androidx.palette)。

**Spec:** `docs/DESIGN-unitedu-open-source.md` §2(首页/卡片/输入源)、§3(主题/时钟)、§4(待机)、§6(设置菜单)、§11(M2 行)。焦点铁律 `docs/TVHOME-README-focus-rules.md` / `CLAUDE.md`。

## Global Constraints
- 绝不用可滚动容器(LazyRow/LazyColumn/scroll);位移自算 `offset`+`animateDpAsState`,配 `wrapContent*(unbounded=true)`。设置页/输入源行/每个新浮层各自立焦点账本(七条铁律)。
- 改视觉值一律走 `Theme`,不在别处写 `Color(0x..)` / 尺寸字面量。
- 设置项持久化到 `settings.json`;坏/缺文件回落默认并写回(与 `layout.json` 同策略)。
- 每个任务 `source scripts/env.sh && gradle --no-daemon assembleRelease` 通过再 commit;有单测的先红后绿。
- 提交信息末尾 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`。
- 不改 M1 已定的包名/签名/焦点核心算法。

---

## 需 Gordon 决策(判断项开工前必答;机械任务 Task A/B 不依赖)

这些是我在 M1 里说过「视觉/UX 判断留给你」的部分,设计文档给了方向但没给可直接落地的数值/顺序:

1. **卡片三档的确切尺寸(§2「每行 8 / 6 / 5 张」)**:M1 实测的是「每行约 7–8 张」的 v4 尺寸(卡宽 127.75dp)。三档要给出每档的卡宽/行高/圆角/光晕/聚焦放大,还是只给「每行张数」让代码按屏宽反推其余?倾向后者(只定张数,其余按比例算),但需你确认基准档是哪一档。
2. **主题色预设的确切色值(§3「金/香槟/蓝/紫/石墨/绿」)**:6 个预设各自的主色 hex。设计说取自 2026-09-10 的效果图候选——那些 hex 我手上没有,需你给或指认。
3. **设置页的布局与分组顺序(§6)**:齿轮→「UnitedU 设置」里的项(行数/卡片大小/标题开关/输入源行/主题色/时钟/待机…)按什么顺序、分几组、每项用什么控件(开关/档位选择/数字步进)?需你给个次序,或授权我按设计 §6 的列举顺序先做、你再调。
4. **待机项在 M2 只做 UI 开关还是接行为?** §11 把「待机」列在 M2 设置页,但待机/屏保的实际行为(DreamService)是 M5。倾向 M2 只做设置项的读写与 UI,行为接线留 M5;需你确认。

> 未决前:Task A 可做;Task B 可做(持久化基建与具体项无关);Task C 起(设置页与各设置项)等 1/3/4;Task E(卡片档位)等 1;Task F(主题色)等 2。

---

### Task A: 颜色/视觉常量收进 Theme 调色板(机械,已可做)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Theme.kt`(新增 palette 常量)
- Modify: `ImagePicker.kt`(14)、`EditScreen.kt`(12)、`HomeSettingsCard.kt`(9)、`GearMenu.kt`(4)、`AppCard.kt`(1)——把 `Color(0x..)` 字面量换成 `Theme.<name>` 引用

**Interfaces:**
- Produces: `Theme` 里一组命名颜色常量(按用途命名,如 `DialogScrim`/`DialogSurface`/`Divider`/`HintText`…),值与原字面量**逐一相等**。本任务只搬家+命名,**不改任何色值**,也不引入「主题色驱动」结构(那是 Task F)。

- [ ] **Step 1:** 列出全部 45 处 `Color(0x..)`,同值归并、按用途命名,写进 `Theme` 一个分组(如 `object Palette` 或就在 `Theme` 内加注释分区)。
- [ ] **Step 2:** 逐文件把字面量替换为 `Theme.<name>`;每替一两个文件跑 `gradle --no-daemon compileReleaseKotlin`。
- [ ] **Step 3:** 验收:`grep -rn 'Color(0x' app/src/main/java` 只剩 `Theme.kt` 里的定义(其余文件 0);`assembleRelease` BUILD SUCCESSFUL;装模拟器截图与 M1 首页**像素一致**(值没变)。
- [ ] **Step 4:** Commit `refactor: consolidate color literals into Theme palette (no value change)`。

**验收锚点(防漂移):** 换完后同参数重新截首页,与 M1 的 `docs/screenshots/home-*.png` 逐像素对比应无差异——色值一个都没动。

---

### Task B: Settings 持久化基建(机械,不依赖决策)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/Settings.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/SettingsTest.kt`
- Modify: `Paths.kt`(加 `settingsJson(ctx)` 路径)

**Interfaces:**
- Produces: `data class Settings(...)` + `object SettingsStore { fun read(ctx): Settings; fun write(ctx, s) }`。字段先放已确定的:`rowCount:Int=3`、`cardsPerRow:Int`(每行张数 5/6/8)、`showTitles:Boolean=false`、`showInputRow:Boolean=false`、`themePresetId:String`、`followWallpaperColor:Boolean=false`、`clock24hFollowSystem:Boolean=true`、`showDate:Boolean=true`、`idleAfterMs:Long`、`idleContent:Enum`。坏/缺文件回落默认并写回(照 `Layout.read` 策略,catch Throwable)。
- 纯解析逻辑(默认值、坏 JSON 回落、字段裁剪)抽成可 JVM 单测的函数,`SettingsTest` 覆盖:缺文件→默认、坏 JSON→默认、部分字段→其余取默认、越界值(如 rowCount=9)→夹到合法范围。

- [ ] Step 1–5:红→实现→绿→装模拟器确认设置无 UI 时默认行为不变→commit。(具体步骤按实施时字段最终集展开,先写测试。)

> 注:Task B 只建「读写 + 默认 + 校验」,不接任何 UI;各设置项的 UI 与生效在 Task C 起。字段集随 C–I 增补时回到本文件同步。

---

### Task C: 「UnitedU 设置」浮层骨架 + 焦点账本(**等决策 3**)

**Files:** Create `SettingsScreen.kt`;Modify `MainActivity.kt`(齿轮菜单加「UnitedU 设置」项,复用 `GearMenu` 的 `MenuItem`)。

**焦点铁律清单(七条,逐条落账本):** 初始焦点 `withFrameNanos` 后靠 `isFocused` 自报;看门狗覆盖本浮层全部可聚焦项、逐项挂 `FocusRequester`;守卫与依赖成对进 key;用 nonce 比对不用一次性布尔闩;位移自算不用 scroll;失焦恢复本层自负责。

- [ ] 按决策 3 给的分组/顺序搭静态骨架(先不接具体项逻辑),D-pad 上下走项、左右改值/进子页,返回关闭。装模拟器验证焦点全程不丢(坏账本会「按一下左右焦点消失再也回不来」——这是本任务最大回归面)。

---

### Task D: 行数 1–5(等 Task C 骨架)
把 `Layout` 的固定三行改为读 `Settings.rowCount`;不足的行留空、超出的隐藏;首页与编辑页纵向自算位移已有,按行数重算 keepout。测:rowCount=1 与 5 都不崩、焦点不丢。

### Task E: 卡片三档 8/6/5(**等决策 1**)
卡尺寸从 `Theme` 死值改为按档位+屏宽推导;行高、光晕、聚焦放大、编辑页布局按档位缩放(§10 风险点:5 行×大卡超 1080p,纵向自算位移已有但要按档重算)。每档装模拟器截图给 Gordon 看观感。

### Task F: 主题色预设 + 跟随壁纸主色(**等决策 2**)
`Theme` 的 4 处(光晕/时钟/行标题/齿轮)改为读 `Settings.themePresetId` 映射的主色;`followWallpaperColor` 开时用 `androidx.palette` 从壁纸取主色。预设色值来自决策 2。

### Task G: 时钟设置
12/24 跟系统(已是)、日期显示开关、星期语言跟系统语言(已是)——主要是把「日期显示」接到 `Settings.showDate`,`Clock.kt` 读它。

### Task H: 输入源行(§2,可开关默认关)
`TvInputManager` 枚举输入源,渲染成一行(每项可隐藏、可改名,复用「修改标题」);`Settings.showInputRow` 控制显隐。跨品牌,单机无法全验,模拟器可能无输入源→需真机/beta 反馈,M2 只保证不崩、开关生效。

### Task I: 待机设置项(**等决策 4**;默认只做 UI+读写)
待机时长(1/3/5/10 分/关)、待机内容(只时钟/全黑/不淡出)写进 `Settings`;UI 在设置页。**行为接线(DreamService)是 M5**——除非决策 4 要求 M2 就接 `IdleAfterMs`。

---

## 自检(草稿阶段)
- §11 M2「主题收敛」→ Task A+F;「设置页(行/卡片/输入源/时钟/待机)」→ Task C+D+G+H+I;「卡片三档」→ Task E。覆盖齐。
- 判断项(卡片档位数值、主题色 hex、设置页布局、待机是否接行为)已全部提到「需 Gordon 决策」栏,不擅自臆造。
- 估时(决策给齐后):Task A 1h、B 2h、C 3h(焦点账本是大头)、D 1.5h、E 3h、F 2.5h、G 1h、H 2.5h、I 1.5h ≈ 与 §11 的 4 天吻合。

## 执行说明
- 本轮(Gordon 睡前授权)只做 **Task A**(机械、无判断、可逆、验收锚点防漂移)。B 也机械但建议与 C 一起做以定字段集,故本轮暂不做 B,除非 A 顺利且时间富余。
- 判断门(决策 1–4)醒来答完,再起 C–I。
