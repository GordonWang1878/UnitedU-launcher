# 编辑桌面「货架」实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把编辑桌面整页换成全屏「货架」:每行一层玻璃架子,行操作胶囊摆在架子顶部,确定键拿起卡片、长按 / 菜单键开卡片菜单,最后一层「新的一行」里选「应用行」;取消行尾「+」与行菜单。

**Architecture:** 纯逻辑(货架模型、上下键的固定焦点顺序、最近胶囊 / 卡片映射、动作后的落点、纵向位移、拿起时的方向箭头、按键长短判定、模糊底的像素计算)全部放进不依赖 Android 的新文件,先写 JVM 单测;Compose 界面拆成「零件」(`EditShelfParts.kt`)与「账本 + 组装」(重写的 `EditScreen.kt`);「添加应用」列表原样搬到 `AppPicker.kt`。焦点账本照七条铁律:目标 `ShelfSpot` 与持有者分开、`ON_PAUSE` 起冻结、逐项挂 requester、只信目标自报、看门狗守卫与 key 同一个量、不用可滚动容器。货架模型留好扩展点(`sealed interface Shelf`、`enum NewRowChoice`),计划 2 在上面加频道架子与「频道」选择卡。

**Tech Stack:** Kotlin、Jetpack Compose(foundation + tv-material 1.0)、JUnit 4(纯 JVM 单测)、Python 3 + uiautomator(e2e)、Android 14 TV 模拟器。

**Spec:** `docs/superpowers/specs/2026-10-08-edit-shelves-and-channels-design.md`(本计划 = §2 编辑页全部 + §5 里涉及编辑页 / 菜单键的两条;§3 频道行、§5 其余三条属计划 2)。效果图 `docs/screenshots/edit-redesign/c1.jpg`、`c2.jpg`,数值源 `docs/design/edit-redesign/shelf.css`、`c1.html`、`c2.html`(画布 960 × 540 = dp);Google 拿起参照 `docs/screenshots/edit-ref/gtv-03…06`。

## Global Constraints

逐字取自 spec(§2 / §4 / §5)与仓库 CLAUDE.md,每个任务都隐含遵守:

- 顶部(左缘 58 dp):「编辑桌面」(`Type.headline`)+ 概况小字「N 行 · N 个应用 · N 个频道」;右边三个按键提示:`确定` 拿起卡片、`↑` 行操作、`返回` 完成。原来的整段说明删除。(本计划无频道:概况只写「N 行 · N 个应用」。)
- 货架:从上往下排,左右各留 40 dp,圆角 20 dp。焦点所在那一层的顶边对齐屏幕上方约 1/3(焦点线),其余层按累计高度排开;纵向位移自己算(`offset` + `animateDpAsState` + `wrapContentHeight(unbounded = true)`,铁律 1)。
- 应用架子:顶部一行 = 行图标 + 「N 个应用」+ 右侧操作胶囊(添加应用、换图标、上移〔不是第一层时〕、下移〔不是最后一个内容层时〕、删除〔应用行多于 1 行时〕);下面是应用卡(固定中档 122 × 68.6 dp,不跟「卡片大小」设置),行尾不再有「+」。空架子在卡片位置放一张「添加应用」方块。
- 新的一行:永远是最后一层,虚线边框,里面选择卡(本计划只有「应用行」),各一句说明(见 c2)。
- 焦点:上下键按固定顺序逐格走:第 1 层胶囊 → 第 1 层卡片 → 第 2 层胶囊 → 第 2 层卡片 → … → 新的一行。从卡片按上 → 本层胶囊里离得最近的那颗(按水平中心);从胶囊按下 → 本层卡片里离得最近的那张。左右到头 `Cancel`。
- 账本:目标 = `(shelf, zone ∈ {CHIPS, CARDS, NEW}, index)`,三个分量各自与「当前位置」分开(铁律 5),`ON_PAUSE` 起冻结。每个可聚焦节点逐项挂 requester,定位效果只信目标自报 `isFocused`(铁律 2),`holder == null` 看门狗(3 帧宽限、60 帧封顶,守卫与 key 同一个量,铁律 6)。
- 动作后的落点:上移 / 下移 → 跟着这一层走,落它的同一颗胶囊;删除 → 落上一层的胶囊(删的是第一层落新的第一层);新建应用行 → 新架子的「添加应用」方块;添加应用 → 新卡;从浮层返回 → 打开它的那颗胶囊 / 那张卡。
- 确定 = 拿起:左右在本行换位;上下搬到相邻的应用架子;确定放下(写盘),返回取消(回原位、不写盘)。沿用现在的搬运模式实现(`carry`),只是入口从菜单改成确定键。
- 长按 / 菜单键 = 卡片菜单:换卡片图、移出这一行(`GearMenu` 两颗胶囊)。**菜单键在编辑页的含义从「退出编辑页」改成「卡片菜单」**(焦点不在卡片上时菜单键不做事);退出一律按返回。
- 删除一行:空行直接删;有应用的行弹确认页(默认在取消,现有 `ConfirmDialog`)。
- 「应用行」→ 在最后插入空应用架子,焦点落它的「添加应用」方块;某类已满 5 行:那张选择卡变暗、写「已满 5 行」,确定不响应(仍可聚焦);新行一律插在最后,原行菜单里的「在下方新建一行」随行菜单一起取消。
- 应用行 1–5 行(`MIN_ROWS = 1` / `MAX_ROWS = 5` 不变)。
- 背景:当前壁纸缩到 1/8 做一次模糊,缓存为位图,之后每帧只画它;上盖约 72% 深色。无壁纸时纯 `MenuBg`。A95L 不实时算模糊。
- 架子:白 5.5% 底 + 白 7% 细边;焦点层白 10% 底 + 白 14% 边 + 阴影;非焦点层整层约 60% 不透明(只在绘制阶段读,不影响可聚焦性)。
- **(owner 裁定,R165 计划复审)架子不裁焦点卡的描边 / 柔光**:横向裁切只允许发生在屏幕边缘、以及越过架子右端的非焦点卡上;架子左边缘一律不裁,第 1 张卡(默认焦点)的柔光画到架子外。
- 操作胶囊:28 dp 高、14 dp 圆角、白 8% 底、12 sp;聚焦填主题 accent、按亮度选对比字色、放大 1.08。与设置页胶囊同一语言(`ShellCapsule` 的取色逻辑复用)。卡片焦点样式沿用首页(放大 + 描边 + 柔光)。
- 动效:换层时架子亮度与整页位移同走 200 ms(`FastOutSlowIn`);拿起 / 放下 150 ms;沿用现有时长档,不新造曲线。进出编辑页、浮层开关的淡入淡出沿用 `FadeSwitch` / `OverlayStack`(残影让路规则照旧)。
- 不变的:入口(设置 → 布局 → 编辑桌面;空桌面的「立即前往」)与退出(返回)不变;每一步即时落盘(`persist` + `layoutWrites`)不变;`AppPicker`、`RowIconPicker`、换卡片图页沿用;「只画已装、可启动的包」(R67)与看得见的列号口径不变。
- 仓库规则:绝不用 `LazyRow` / `LazyColumn` / `verticalScroll` / `horizontalScroll`(`AppPicker` 那一个 `LazyColumn` 是唯一获准的例外);字号只从 `Type` 取(`TypeScaleTest` 扫源码,不许 `数字.sp`);字重只用 Normal / Medium;界面文案三种语言同步(`values` = 简体、`values-en`、`values-zh-rTW`,`CopyTest` 校验同 key 同占位符);`graphicsLayer` alpha < 1 的容器若装着带焦点溢出的卡,图层四边撑大 `APP_FOCUS_GLOW_DP`、外层按原尺寸上报(CLAUDE.md R129f)。
- 构建 `source scripts/env.sh && gradle --no-daemon assembleRelease`;单测 `gradle --no-daemon testReleaseUnitTest`;单跑一个类加 `--tests 'com.uniteduone.launcher.<类名>'`。命令一律在仓库根 `/Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐` 下跑。
- 模拟器只用 `unitedu-tv-2`(端口 5562)或 `unitedu-tv-3`,不碰 `unitedu-tv` / `unitedu-gtv`(并行代理隔离);不往电视装包。

## Review Focus

spec 没明写、但最可能让真人用户撞上的五种情况,各自在所属任务里有钉死它的测试:

1. **长按确定开卡片菜单之后松手**:同一次按压的松开(UP)不能再被当成「短按 → 拿起」,也不能落到菜单第一颗胶囊上触发「换卡片图」。期望:菜单开着、什么都没动。→ Task 4 `OkPressTest.upOfALongPressIsOwnedButNeverAShortPress`;Task 9 e2e「长按松手不误拿起 / 不误点菜单第一项」。
2. **按「上移」把一层移到最上面(或「下移」到最后一个内容层)之后,按下的那颗胶囊消失了**。期望:焦点落在这一层还存在的胶囊上(反方向那颗),不丢焦点、不跳到别的层。→ Task 2 `EditShelvesLandingTest.movingAShelfKeepsFocusOnTheSameChipOrItsOpposite`。
3. **焦点停在「新的一行」时行数变了**(刚加满 5 行、删掉一行、数据重载)。期望:焦点仍在「新的一行」的选择卡上,不落到某张卡或越界。→ Task 1 `EditShelvesTest.clampKeepsTheNewRowOnTheNewRowWhenRowsChange`。
4. **一行里的应用多于一屏**(测试布局第 1 行 8 张,加完 9 张):焦点走到最右一张时整行要左移,焦点卡放大 + 描边必须完整落在架子里,不被架子左右边缘或屏幕裁掉。→ Task 2 `EditShelvesLandingTest.longRowSlidesSoTheFocusedCardStaysInsideTheShelf`。
5. **壁纸很亮(雪景 / 白天的天空)或解不出来**:模糊底不能把架子上的字洗白;解不出来退回纯 `MenuBg`、不崩。→ Task 5 `EditBackdropTest.brightestWallpaperStillLeavesTextReadable` 与 `undecodableInputIsRejectedNotCrashed`。

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `app/src/main/java/com/uniteduone/launcher/EditShelves.kt` | 新建 | 纯逻辑:货架模型(`Shelf` / `ShelfChip` / `NewRowChoice` / `ShelfZone` / `ShelfSpot` / `ShelfLane`)、固定焦点顺序与最近映射、`clampSpot`、动作后的落点、`shelfScroll`、`carryArrows`、`shelfLook` / `shelfAlpha`、`editHintSet`、`editCounts`、几何常量 `ShelfLayout`。**扩展点**写在文件头 KDoc。 |
| `app/src/main/java/com/uniteduone/launcher/EditPress.kt` | 新建 | 纯逻辑:确定键一次按压的长短判定 `OkPress` / `OkOutcome`。 |
| `app/src/main/java/com/uniteduone/launcher/EditBackdrop.kt` | 新建 | 模糊底:纯像素计算 `editBackdropPixels` + 解码 / 缓存 `buildEditBackdrop` / `cachedEditBackdrop` + `Modifier.editBackdrop`。 |
| `app/src/main/java/com/uniteduone/launcher/EditShelfParts.kt` | 新建 | Compose 零件:`ShelfFrame`、`ShelfChipPill`、`ChoiceCard`、`AddAppTile`、`ShelfPendingCard`、`ShelfIcon`、`EditKeyHints`、`Modifier.carryArrows`。 |
| `app/src/main/java/com/uniteduone/launcher/AppPicker.kt` | 新建(搬家) | 原 `EditScreen.kt` 里的 `AppPicker` / `PickerRow` / `PickerCardMetrics`,逐字搬来,`AppPicker` 改 `internal`。 |
| `app/src/main/java/com/uniteduone/launcher/EditScreen.kt` | 重写 | 焦点账本、按键截获、浮层摞、写盘、拿起;组装货架。 |
| `app/src/main/java/com/uniteduone/launcher/LayoutOps.kt` | 改 | 加 `appRowCount` / `appendAppRow`;删 `addRowBelow`。 |
| `app/src/main/java/com/uniteduone/launcher/Ambient.kt` | 改 | `boxBlur` 由 `private` 改 `internal`(模糊底复用)。 |
| `app/src/main/java/com/uniteduone/launcher/MainActivity.kt` | 改 | 编辑页里确定键整下与菜单键原样交给编辑页;删 `editCarrying`;`EditScreen` 调用点换参数。 |
| `app/src/main/res/values{,-en,-zh-rTW}/strings.xml` | 改 | 加货架文案;删行菜单 / 旧说明文案。 |
| `app/src/test/java/com/uniteduone/launcher/EditShelvesTest.kt` | 新建 | Task 1 单测。 |
| `app/src/test/java/com/uniteduone/launcher/EditShelvesLandingTest.kt` | 新建 | Task 2 单测。 |
| `app/src/test/java/com/uniteduone/launcher/OkPressTest.kt` | 新建 | Task 4 单测。 |
| `app/src/test/java/com/uniteduone/launcher/EditBackdropTest.kt` | 新建 | Task 5 单测。 |
| `app/src/test/java/com/uniteduone/launcher/EditIronRulesTest.kt` | 新建 | Task 8 源码扫描(铁律 1 / 2、行菜单已删)。 |
| `app/src/test/java/com/uniteduone/launcher/LayoutOpsTest.kt`、`LayoutOpsBoundaryTest.kt` | 改 | `appendAppRow` 测试进;`addRowBelow` 测试出。 |
| `app/src/test/java/com/uniteduone/launcher/CopyTest.kt` | 改 | 新文案存在、旧文案已删。 |
| `app/src/test/java/com/uniteduone/launcher/EditScrollTest.kt` | 删 | 测的 `editFirstRow` 随旧编辑页一起删。 |
| `app/src/test/java/com/uniteduone/launcher/GtvMotionTest.kt` | 改(注释) | KDoc 里「EditScreen 的纵向位移」一句更正。 |
| `scripts/e2e/j_edit.py` | 重写 | 新交互旅程。 |
| `scripts/e2e/j_overlays.py`、`j_pkg.py`、`j_recreate.py`、`j_i18n.py`、`j_home.py`、`README.md` | 改 | 去掉行菜单 / `+` / `edit_hint` 依赖。 |
| `docs/screenshots/edit-shelves/` | 新建 | 模拟器截图。 |
| `CLAUDE.md`、`docs/REVIEW-GUIDE.md`、`README.md`、`README.zh-CN.md`、`docs/superpowers/specs/2026-09-20-gtv-line-design.md`、`docs/WORKLOG.md` | 改 | 焦点表、文件地图、用户文档、R165 状态、工作记录。`docs/design/settings-inventory.md` 已核对:编辑桌面那一行的说明「添加或移除应用卡片、调整顺序、管理每一行。」仍成立,不改。 |

**任务顺序与依赖**:1 → 2(同一文件)→ 3 → 4 → 5 → 6(文案)→ 7(零件,用到 2 / 5 / 6)→ 8(重写 + 接线,用到全部)→ 9(e2e)→ 10(模拟器验收)→ 11(文档)。

**开工前一次性**:

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git switch -c edit-shelves
git status --short   # 应为空(上一段会话的 WORKLOG / spec / edit-ref 截图都已提交);若有别人的未提交改动,不要 add 进本计划的提交
```

---

### Task 1: 货架模型与固定焦点顺序(纯逻辑)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/EditShelves.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/EditShelvesTest.kt`

**Interfaces:**
- Consumes: `LayoutRow(icon: String, apps: List<String>)`(Layout.kt)、`MIN_ROWS` / `MAX_ROWS`(LayoutOps.kt)
- Produces:
  - `internal sealed interface Shelf { data class AppShelf(val row: Int, val icon: String, val apps: List<String>) : Shelf; data object NewRowShelf : Shelf }`
  - `internal enum class ShelfChip { ADD_APP, ICON, UP, DOWN, DELETE }`
  - `internal enum class NewRowChoice { APP_ROW }`
  - `internal enum class ShelfZone { CHIPS, CARDS, NEW }`
  - `internal data class ShelfSpot(val shelf: Int, val zone: ShelfZone, val index: Int)`
  - `internal data class ShelfLane(val shelf: Int, val zone: ShelfZone)`
  - `internal data class EditCounts(val rows: Int, val apps: Int)`
  - `internal fun shelvesOf(view: List<LayoutRow>): List<Shelf>`
  - `internal fun appShelfCount(shelves: List<Shelf>): Int`
  - `internal fun shelfChips(shelves: List<Shelf>, shelf: Int): List<ShelfChip>`
  - `internal fun laneSize(shelves: List<Shelf>, shelf: Int, zone: ShelfZone): Int`
  - `internal fun shelfLanes(shelves: List<Shelf>): List<ShelfLane>`
  - `internal fun nearestByCenter(x: Float?, centers: List<Float?>): Int`
  - `internal fun verticalStep(shelves: List<Shelf>, from: ShelfSpot, down: Boolean, centerOf: (ShelfSpot) -> Float?): ShelfSpot?`
  - `internal fun clampSpot(shelves: List<Shelf>, spot: ShelfSpot): ShelfSpot`
  - `internal fun choiceFull(shelves: List<Shelf>, choice: NewRowChoice): Boolean`
  - `internal fun editCounts(view: List<LayoutRow>): EditCounts`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/EditShelvesTest.kt`:

```kotlin
package com.uniteduone.launcher

import com.uniteduone.launcher.ShelfChip.ADD_APP
import com.uniteduone.launcher.ShelfChip.DELETE
import com.uniteduone.launcher.ShelfChip.DOWN
import com.uniteduone.launcher.ShelfChip.ICON
import com.uniteduone.launcher.ShelfChip.UP
import com.uniteduone.launcher.ShelfZone.CARDS
import com.uniteduone.launcher.ShelfZone.CHIPS
import com.uniteduone.launcher.ShelfZone.NEW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R165 编辑桌面「货架」:货架模型、上下键的固定焦点顺序(spec §2.2)、层内胶囊 ↔ 卡片的最近映射、目标格的夹取。
 * 纯 JVM。
 */
class EditShelvesTest {
    private fun row(icon: String, vararg apps: String) = LayoutRow(icon, apps = apps.toList())

    /** 0:4 张  1:2 张  2:空行  3:新的一行 */
    private val view3 = listOf(row("movie", "a", "b", "c", "d"), row("tv", "e", "f"), row("music"))
    private val three = shelvesOf(view3)
    private val none: (ShelfSpot) -> Float? = { null }

    @Test fun shelvesKeepLayoutRowIndicesAndEndWithTheNewRow() {
        assertEquals(4, three.size)
        assertEquals(Shelf.AppShelf(1, "tv", listOf("e", "f")), three[1])
        assertEquals(Shelf.NewRowShelf, three.last())
        assertEquals(3, appShelfCount(three))
        assertEquals(listOf<Shelf>(Shelf.NewRowShelf), shelvesOf(emptyList()))
    }

    @Test fun chipsDependOnPositionAndHowManyAppRowsThereAre() {
        assertEquals(listOf(ADD_APP, ICON, DOWN, DELETE), shelfChips(three, 0))        // 第一层:没有上移
        assertEquals(listOf(ADD_APP, ICON, UP, DOWN, DELETE), shelfChips(three, 1))
        assertEquals(listOf(ADD_APP, ICON, UP, DELETE), shelfChips(three, 2))          // 最后一个内容层:没有下移
        assertEquals(emptyList<ShelfChip>(), shelfChips(three, 3))                     // 新的一行没有胶囊
        assertEquals(emptyList<ShelfChip>(), shelfChips(three, 9))
        // 只剩一个应用行:不能上下移,也不能删
        assertEquals(listOf(ADD_APP, ICON), shelfChips(shelvesOf(listOf(row("movie", "a"))), 0))
    }

    @Test fun lanesAreChipsThenCardsPerShelfAndTheNewRowLast() {
        assertEquals(
            listOf(
                ShelfLane(0, CHIPS), ShelfLane(0, CARDS), ShelfLane(1, CHIPS), ShelfLane(1, CARDS),
                ShelfLane(2, CHIPS), ShelfLane(2, CARDS), ShelfLane(3, NEW),
            ),
            shelfLanes(three),
        )
        assertEquals(4, laneSize(three, 0, CARDS))
        assertEquals("空架子只有一格:「添加应用」方块", 1, laneSize(three, 2, CARDS))
        assertEquals(NewRowChoice.entries.size, laneSize(three, 3, NEW))
        assertEquals(0, laneSize(three, 3, CARDS))
        assertEquals(0, laneSize(three, 0, NEW))
        assertEquals(0, laneSize(three, 7, CHIPS))
    }

    @Test fun downWalksTheFixedOrderAndStopsAtTheNewRow() {
        val seen = mutableListOf(ShelfSpot(0, CHIPS, 0))
        while (true) seen += verticalStep(three, seen.last(), down = true, centerOf = none) ?: break
        assertEquals(shelfLanes(three).map { ShelfSpot(it.shelf, it.zone, 0) }, seen)
        assertNull("新的一行往下:到头", verticalStep(three, ShelfSpot(3, NEW, 0), down = true, centerOf = none))
    }

    @Test fun upWalksBackAndStopsAtTheFirstChips() {
        val seen = mutableListOf(ShelfSpot(3, NEW, 0))
        while (true) seen += verticalStep(three, seen.last(), down = false, centerOf = none) ?: break
        assertEquals(shelfLanes(three).reversed().map { ShelfSpot(it.shelf, it.zone, 0) }, seen)
        assertNull("第一层胶囊往上:到头", verticalStep(three, ShelfSpot(0, CHIPS, 3), down = false, centerOf = none))
        assertNull("不在任何 lane 上(越界)", verticalStep(three, ShelfSpot(8, CARDS, 0), down = true, centerOf = none))
    }

    @Test fun cardAndChipMapToTheNearestByHorizontalCentre() {
        val six = shelvesOf(listOf(row("movie", "a", "b", "c", "d", "e", "f"), row("tv", "g")))
        // 第一层胶囊靠右(添加应用 / 换图标 / 下移 / 删除),卡片靠左、最后一张已经滑到右边
        val chipX = listOf(1230f, 1420f, 1585f, 1735f)
        val cardX = listOf(238f, 522f, 806f, 1090f, 1374f, 1700f)
        val centre: (ShelfSpot) -> Float? = { s ->
            when (s.zone) {
                CHIPS -> chipX.getOrNull(s.index)
                CARDS -> if (s.shelf == 0) cardX.getOrNull(s.index) else 300f
                NEW -> null
            }
        }
        assertEquals(ShelfSpot(0, CHIPS, 3), verticalStep(six, ShelfSpot(0, CARDS, 5), down = false, centerOf = centre))
        assertEquals(ShelfSpot(0, CHIPS, 0), verticalStep(six, ShelfSpot(0, CARDS, 0), down = false, centerOf = centre))
        assertEquals(ShelfSpot(0, CARDS, 4), verticalStep(six, ShelfSpot(0, CHIPS, 1), down = true, centerOf = centre))
        // 跨层也按最近:第 1 层最右那张卡往下 → 第 2 层胶囊里最靠右的那颗
        assertEquals(ShelfSpot(1, CHIPS, 3), verticalStep(six, ShelfSpot(0, CARDS, 5), down = true, centerOf = centre))
    }

    @Test fun nearestFallsBackToTheFirstWhenNothingIsMeasured() {
        assertEquals(0, nearestByCenter(null, listOf(1f, 2f)))
        assertEquals(0, nearestByCenter(500f, listOf(null, null)))
        assertEquals(0, nearestByCenter(500f, emptyList()))
        assertEquals("一样近取左边那个", 0, nearestByCenter(10f, listOf(0f, 20f)))
        assertEquals(1, nearestByCenter(500f, listOf(null, 480f, 900f)))
    }

    /** Review Focus 3:焦点停在「新的一行」时行数变了,目标仍在新的一行上。 */
    @Test fun clampKeepsTheNewRowOnTheNewRowWhenRowsChange() {
        val four = shelvesOf(view3 + row("games"))
        assertEquals("多了一行:新的一行往后挪一格", ShelfSpot(4, NEW, 0), clampSpot(four, ShelfSpot(3, NEW, 0)))
        val two = shelvesOf(view3.take(2))
        assertEquals("少了一行:新的一行往前挪一格", ShelfSpot(2, NEW, 0), clampSpot(two, ShelfSpot(3, NEW, 0)))
        assertEquals(ShelfSpot(3, NEW, 0), clampSpot(three, ShelfSpot(3, NEW, 5)))
    }

    @Test fun clampPullsStaleSpotsBackInsideTheShelves() {
        assertEquals(ShelfSpot(0, CARDS, 3), clampSpot(three, ShelfSpot(0, CARDS, 9)))
        assertEquals("空架子:只剩方块", ShelfSpot(2, CARDS, 0), clampSpot(three, ShelfSpot(2, CARDS, 2)))
        assertEquals("胶囊少了(删除没了):落最后一颗", ShelfSpot(0, CHIPS, 3), clampSpot(three, ShelfSpot(0, CHIPS, 4)))
        assertEquals(ShelfSpot(0, CARDS, 0), clampSpot(three, ShelfSpot(-1, CARDS, -2)))
        assertEquals("越过最后一层:落到新的一行", ShelfSpot(3, NEW, 0), clampSpot(three, ShelfSpot(5, CARDS, 0)))
        assertEquals("应用架子上不会有 NEW", ShelfSpot(3, NEW, 0), clampSpot(three, ShelfSpot(1, NEW, 0)))
    }

    @Test fun appRowChoiceIsFullAtFiveAppRows() {
        assertFalse(choiceFull(three, NewRowChoice.APP_ROW))
        val five = shelvesOf(view3 + row("games") + row("kids"))
        assertTrue(choiceFull(five, NewRowChoice.APP_ROW))
    }

    @Test fun countsAreRowsAndVisibleApps() {
        assertEquals(EditCounts(rows = 3, apps = 6), editCounts(view3))
        assertEquals(EditCounts(rows = 0, apps = 0), editCounts(emptyList()))
    }
}
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.EditShelvesTest' 2>&1 | grep -E "Unresolved reference|FAILED|BUILD" | head -5`
Expected: `BUILD FAILED`,编译错误 `Unresolved reference 'shelvesOf'`(以及 `Shelf`、`ShelfChip` 等)。

- [ ] **Step 3: 写实现**

`app/src/main/java/com/uniteduone/launcher/EditShelves.kt`:

```kotlin
package com.uniteduone.launcher

import kotlin.math.abs

/*
 * **编辑桌面「货架」(R165)的纯逻辑**:货架模型、上下键的固定焦点顺序、层内胶囊 ↔ 卡片的最近映射、目标格夹取、
 * 动作后的落点、纵向位移、拿起时的方向箭头、架子的明暗。不依赖 Android,单测 EditShelvesTest / EditShelvesLandingTest。
 *
 * **扩展点(计划 2:频道行)**——加频道时只动这几处,`EditScreen` 的账本与按键截获不用改:
 * - `Shelf` 加 `data class ChannelShelf(val row: Int, …) : Shelf`(行号仍 = layout.json 行号);
 * - `shelfLanes` 给它只出一条 `CHIPS`(海报预览不可聚焦,spec §2.1);
 * - `shelfChips` 给它 `UP` / `DOWN` / `DELETE`(删除不受「应用行多于 1 行」限制),需要时加 `REAUTHORIZE`;
 * - `NewRowChoice` 加 `CHANNEL`,`choiceFull` 按频道行数判;
 * - `clampSpot` 里 `when (shelves[shelf])` 是穷举的——新加子类型时编译器会逐处点名要处理的地方。
 */

/** 编辑页的一层。[AppShelf.row] = layout.json 的行号(= 本页 `rows` 的下标);[NewRowShelf] 永远是最后一层。 */
internal sealed interface Shelf {
    data class AppShelf(val row: Int, val icon: String, val apps: List<String>) : Shelf
    data object NewRowShelf : Shelf
}

/** 应用架子顶部的操作胶囊,从左到右就是这个顺序(spec §2.1)。 */
internal enum class ShelfChip { ADD_APP, ICON, UP, DOWN, DELETE }

/** 「新的一行」里的选择卡(计划 2 加 CHANNEL)。 */
internal enum class NewRowChoice { APP_ROW }

/** 一层里的哪一条:顶部胶囊、卡片(空架子 = 「添加应用」方块)、新的一行的选择卡。 */
internal enum class ShelfZone { CHIPS, CARDS, NEW }

/** 焦点账本里的一格(spec §2.2):第几层、哪一条、第几个。 */
internal data class ShelfSpot(val shelf: Int, val zone: ShelfZone, val index: Int)

/** 上下键逐格走的一条(一层的胶囊、一层的卡片、新的一行)。 */
internal data class ShelfLane(val shelf: Int, val zone: ShelfZone)

/** 顶部概况「N 行 · N 个应用」的两个数(计划 2 加频道数)。 */
internal data class EditCounts(val rows: Int, val apps: Int)

/** 看得见的那份行(R67,`visibleRows`)→ 货架:每行一层应用架子,最后一层「新的一行」。 */
internal fun shelvesOf(view: List<LayoutRow>): List<Shelf> =
    view.mapIndexed { i, r -> Shelf.AppShelf(i, r.icon, r.apps) } + Shelf.NewRowShelf

internal fun appShelfCount(shelves: List<Shelf>): Int = shelves.count { it is Shelf.AppShelf }

/** 最后一个内容层(「新的一行」之前那层)的下标;没有内容层 → -1。 */
private fun lastContentShelf(shelves: List<Shelf>): Int = shelves.indexOfLast { it !is Shelf.NewRowShelf }

/**
 * 第 [shelf] 层顶部画哪几颗胶囊(spec §2.1):添加应用、换图标恒有;上移只在不是第一层时;下移只在不是最后一个内容层时;
 * 删除只在应用行多于 [MIN_ROWS] 行时。「新的一行」与越界 → 空。
 */
internal fun shelfChips(shelves: List<Shelf>, shelf: Int): List<ShelfChip> = when (shelves.getOrNull(shelf)) {
    is Shelf.AppShelf -> buildList {
        add(ShelfChip.ADD_APP)
        add(ShelfChip.ICON)
        if (shelf > 0) add(ShelfChip.UP)
        if (shelf < lastContentShelf(shelves)) add(ShelfChip.DOWN)
        if (appShelfCount(shelves) > MIN_ROWS) add(ShelfChip.DELETE)
    }
    else -> emptyList()
}

/** 一条里有几格。卡片:应用数,空架子 1(「添加应用」方块);不存在的组合 0。 */
internal fun laneSize(shelves: List<Shelf>, shelf: Int, zone: ShelfZone): Int {
    val s = shelves.getOrNull(shelf) ?: return 0
    return when (zone) {
        ShelfZone.CHIPS -> shelfChips(shelves, shelf).size
        ShelfZone.CARDS -> if (s is Shelf.AppShelf) s.apps.size.coerceAtLeast(1) else 0
        ShelfZone.NEW -> if (s is Shelf.NewRowShelf) NewRowChoice.entries.size else 0
    }
}

/** 上下键的固定顺序(spec §2.2):第 1 层胶囊 → 第 1 层卡片 → 第 2 层胶囊 → … → 新的一行。 */
internal fun shelfLanes(shelves: List<Shelf>): List<ShelfLane> = shelves.flatMapIndexed { i, s ->
    when (s) {
        is Shelf.AppShelf -> listOf(ShelfLane(i, ShelfZone.CHIPS), ShelfLane(i, ShelfZone.CARDS))
        Shelf.NewRowShelf -> listOf(ShelfLane(i, ShelfZone.NEW))
    }
}.filter { laneSize(shelves, it.shelf, it.zone) > 0 }

/** [centers] 里水平中心离 [x] 最近的下标;一样近取靠左的;[x] 没量到或一个都没量到 → 0。 */
internal fun nearestByCenter(x: Float?, centers: List<Float?>): Int {
    if (x == null) return 0
    var best = 0
    var bestD = Float.MAX_VALUE
    centers.forEachIndexed { i, c ->
        if (c != null) {
            val d = abs(c - x)
            if (d < bestD) { bestD = d; best = i }
        }
    }
    return best
}

/**
 * 从 [from] 按上 / 下([down])落到哪一格:沿 [shelfLanes] 走一条,落点取那条里水平中心离 [from] 最近的一格
 * ([centerOf] 给每一格此刻的水平中心,没量到给 null)。到头 / [from] 不在任何一条上 → null(调用方吞掉这一下 = `Cancel`)。
 * spec 只写了「层内卡片 ↔ 胶囊按最近」;跨层(卡片 → 下一层胶囊、胶囊 → 上一层卡片、进出新的一行)同样按最近。
 */
internal fun verticalStep(
    shelves: List<Shelf>,
    from: ShelfSpot,
    down: Boolean,
    centerOf: (ShelfSpot) -> Float?,
): ShelfSpot? {
    val lanes = shelfLanes(shelves)
    val i = lanes.indexOf(ShelfLane(from.shelf, from.zone))
    if (i < 0) return null
    val to = lanes.getOrNull(if (down) i + 1 else i - 1) ?: return null
    val n = laneSize(shelves, to.shelf, to.zone)
    val centers = List(n) { centerOf(ShelfSpot(to.shelf, to.zone, it)) }
    return ShelfSpot(to.shelf, to.zone, nearestByCenter(centerOf(from), centers))
}

/**
 * 把一个可能已经过期的目标夹回此刻的货架里:层号、条、格号依次夹。**`NEW` 永远指向「新的一行」**(行数变了它跟着挪,
 * Review Focus 3);应用架子上不会有 `NEW`(→ `CARDS`);落到「新的一行」上的别的条 → `NEW`。
 */
internal fun clampSpot(shelves: List<Shelf>, spot: ShelfSpot): ShelfSpot {
    if (shelves.isEmpty()) return ShelfSpot(0, spot.zone, 0)
    val newRow = shelves.indexOfLast { it is Shelf.NewRowShelf }
    val shelf = if (spot.zone == ShelfZone.NEW && newRow >= 0) newRow else spot.shelf.coerceIn(0, shelves.lastIndex)
    val zone = when (shelves[shelf]) {
        is Shelf.AppShelf -> if (spot.zone == ShelfZone.NEW) ShelfZone.CARDS else spot.zone
        Shelf.NewRowShelf -> ShelfZone.NEW
    }
    val n = laneSize(shelves, shelf, zone)
    return ShelfSpot(shelf, zone, spot.index.coerceIn(0, (n - 1).coerceAtLeast(0)))
}

/** 这张选择卡是不是已满(spec §2.4:变暗、写「已满 5 行」、确定不响应,仍可聚焦)。 */
internal fun choiceFull(shelves: List<Shelf>, choice: NewRowChoice): Boolean = when (choice) {
    NewRowChoice.APP_ROW -> appShelfCount(shelves) >= MAX_ROWS
}

/** 顶部概况:行数 + 看得见的应用数。 */
internal fun editCounts(view: List<LayoutRow>): EditCounts = EditCounts(view.size, view.sumOf { it.apps.size })
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.EditShelvesTest' 2>&1 | grep -E "FAILED|BUILD|tests completed" | head -5`
Expected: `BUILD SUCCESSFUL`,没有 FAILED。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git add app/src/main/java/com/uniteduone/launcher/EditShelves.kt app/src/test/java/com/uniteduone/launcher/EditShelvesTest.kt
git commit -m "$(cat <<'EOF'
feat(edit): 货架模型与固定焦点顺序(R165,纯逻辑)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 动作后的落点、纵向位移、方向箭头、架子明暗与几何(纯逻辑)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/EditShelves.kt`(在文件末尾追加)
- Test: `app/src/test/java/com/uniteduone/launcher/EditShelvesLandingTest.kt`

**Interfaces:**
- Consumes: Task 1 全部;`moveInLayout(rows: List<LayoutRow>, pos: MovePos, dir: MoveDir): Pair<List<LayoutRow>, MovePos>`、`MovePos`、`MoveDir`(Move.kt);`GtvLayout.CONTENT_KEYLINE` / `CARD_GAP` / `APP_FOCUS_GLOW_DP` / `cardWidth()` / `cardHeight()` / `appFocusOverflow()` / `rowShiftX()`、`GtvCardSize.MEDIUM`
- Produces:
  - `internal object ShelfLayout`(常量见下)
  - `internal fun landingOnChip(shelves: List<Shelf>, shelf: Int, chip: ShelfChip): ShelfSpot`
  - `internal fun landingOnCard(shelves: List<Shelf>, shelf: Int, col: Int): ShelfSpot`
  - `internal fun landingAfterSwap(shelves: List<Shelf>, newShelf: Int, pressed: ShelfChip): ShelfSpot`
  - `internal fun landingAfterDelete(shelves: List<Shelf>, deleted: Int): ShelfSpot`
  - `internal fun landingAfterAppend(shelves: List<Shelf>): ShelfSpot`
  - `internal fun shelfScroll(heights: List<Int>, gap: Int, focused: Int, top: Int, focusLine: Int, viewport: Int, bottomPad: Int): Int`
  - `internal fun carryArrows(view: List<LayoutRow>, pos: MovePos): Set<MoveDir>`
  - `internal fun shelfAlpha(focus: Float): Float`
  - `internal data class ShelfLook(val bg: Float, val border: Float, val shadow: Float)`、`internal fun shelfLook(focus: Float, dashed: Boolean): ShelfLook`
  - `internal enum class EditHintSet { BROWSE, NEW_ROW, CARRY }`、`internal fun editHintSet(zone: ShelfZone?, carrying: Boolean): EditHintSet`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/EditShelvesLandingTest.kt`:

```kotlin
package com.uniteduone.launcher

import com.uniteduone.launcher.ShelfChip.DELETE
import com.uniteduone.launcher.ShelfChip.DOWN
import com.uniteduone.launcher.ShelfChip.ICON
import com.uniteduone.launcher.ShelfChip.UP
import com.uniteduone.launcher.ShelfZone.CARDS
import com.uniteduone.launcher.ShelfZone.CHIPS
import com.uniteduone.launcher.ShelfZone.NEW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R165 货架:动作后的落点(spec §2.2)、纵向位移(焦点线)、拿起时的方向箭头、架子明暗、几何与效果图对齐。纯 JVM。 */
class EditShelvesLandingTest {
    private fun row(icon: String, vararg apps: String) = LayoutRow(icon, apps = apps.toList())
    private val view3 = listOf(row("movie", "a", "b", "c", "d"), row("tv", "e", "f"), row("music"))
    private val three = shelvesOf(view3)

    /** Review Focus 2:按下的那颗胶囊在新位置上没了(移到顶没有「上移」、移到底没有「下移」)→ 落反方向那颗。 */
    @Test fun movingAShelfKeepsFocusOnTheSameChipOrItsOpposite() {
        assertEquals("移到中间:「下移」还在", ShelfSpot(1, CHIPS, 3), landingAfterSwap(three, 1, DOWN))
        assertEquals("移到最后一个内容层:没有「下移」→「上移」", ShelfSpot(2, CHIPS, 2), landingAfterSwap(three, 2, DOWN))
        assertEquals("移到第一层:没有「上移」→「下移」", ShelfSpot(0, CHIPS, 2), landingAfterSwap(three, 0, UP))
        val two = shelvesOf(view3.take(2))
        for (s in 0..1) for (c in listOf(UP, DOWN)) {
            val l = landingAfterSwap(two, s, c)
            assertEquals(s, l.shelf)
            assertEquals(CHIPS, l.zone)
            assertTrue("落点那颗胶囊一定存在:$l", l.index in shelfChips(two, s).indices)
        }
    }

    @Test fun deletingLandsOnTheFirstChipOfTheShelfAbove() {
        assertEquals(ShelfSpot(0, CHIPS, 0), landingAfterDelete(shelvesOf(listOf(view3[0], view3[2])), 1))
        assertEquals("删的是第一层:落新的第一层", ShelfSpot(0, CHIPS, 0), landingAfterDelete(shelvesOf(view3.drop(1)), 0))
        assertEquals("删的是最后一个内容层:落新的最后一层,不落到「新的一行」",
            ShelfSpot(1, CHIPS, 0), landingAfterDelete(shelvesOf(view3.take(2)), 2))
    }

    @Test fun appendingLandsOnTheNewShelfsAddTile() {
        assertEquals(ShelfSpot(3, CARDS, 0), landingAfterAppend(shelvesOf(view3 + LayoutRow(NEW_ROW_ICON))))
    }

    @Test fun cardAndChipLandingsAreClamped() {
        assertEquals(ShelfSpot(0, CARDS, 3), landingOnCard(three, 0, 9))
        assertEquals("移出第一张:落 0", ShelfSpot(0, CARDS, 0), landingOnCard(three, 0, -1))
        assertEquals("移空了:落「添加应用」方块", ShelfSpot(2, CARDS, 0), landingOnCard(three, 2, 0))
        assertEquals(ShelfSpot(0, CHIPS, 1), landingOnChip(three, 0, ICON))
        assertEquals(ShelfSpot(1, CHIPS, 4), landingOnChip(three, 1, DELETE))
        assertEquals("胶囊不在了:落第一颗", ShelfSpot(0, CHIPS, 0), landingOnChip(shelvesOf(listOf(row("movie"))), 0, DELETE))
        assertEquals(ShelfSpot(3, NEW, 0), clampSpot(three, ShelfSpot(3, NEW, 0)))
    }

    // ---- 纵向位移:焦点层顶边对齐焦点线(180 dp),夹到内容末尾(c1 / c2 的数,1 px = 1 dp) ----
    private val heights = listOf(140, 140, 140, 222)   // 三层应用架子 + 新的一行(190 + 说明一行)
    private fun scroll(f: Int, h: List<Int> = heights, viewport: Int = 540) =
        shelfScroll(h, gap = 14, focused = f, top = 84, focusLine = 180, viewport = viewport, bottomPad = 24)

    @Test fun focusedShelfTopSitsOnTheFocusLineClampedToTheContentEnd() {
        assertEquals("第一层本来就在焦点线之上:不动", 0, scroll(0))
        assertEquals(58, scroll(1))                       // 84 + 154 − 180
        assertEquals(212, scroll(2))                      // 84 + 308 − 180
        assertEquals("最后一层:夹到内容末尾", 252, scroll(3))   // 84 + 642 + 42 + 24 − 540
        assertEquals("越界当最后一层", 252, scroll(9))
        for (f in heights.indices) {
            val top = 84 + (0 until f).sumOf { heights[it] + 14 } - scroll(f)
            assertTrue("第 $f 层完整可见", top >= 0 && top + heights[f] <= 540)
        }
    }

    @Test fun shortContentAndUnmeasuredLayoutsDoNotScroll() {
        assertEquals(0, scroll(1, h = listOf(140, 222)))
        assertEquals("视窗还没量到", 0, scroll(2, viewport = 0))
        assertEquals(0, shelfScroll(emptyList(), 14, 0, 84, 180, 540, 24))
    }

    // ---- 拿起时的方向箭头:只画真的挪得动的方向(照 Google TV gtv-04) ----
    @Test fun carryArrowsShowOnlyDirectionsThatMove() {
        val v = listOf(row("movie", "a", "b", "c"), row("tv", "d"), row("music"))
        assertEquals(setOf(MoveDir.RIGHT, MoveDir.DOWN), carryArrows(v, MovePos(0, 0)))
        assertEquals(setOf(MoveDir.LEFT, MoveDir.RIGHT, MoveDir.DOWN), carryArrows(v, MovePos(0, 1)))
        assertEquals("空的第 3 行也是落点", setOf(MoveDir.UP, MoveDir.DOWN), carryArrows(v, MovePos(1, 0)))
        val dup = listOf(row("movie", "a", "b"), row("tv", "a"))
        assertEquals("下一行已有同一个应用:不能往下", setOf(MoveDir.RIGHT), carryArrows(dup, MovePos(0, 0)))
        assertEquals(emptySet<MoveDir>(), carryArrows(v, MovePos(5, 0)))
    }

    // ---- 架子明暗(spec §2.5)----
    @Test fun shelfLookInterpolatesBetweenIdleAndFocused() {
        assertEquals(ShelfLayout.IDLE_SHELF_ALPHA, shelfAlpha(0f), 1e-6f)
        assertEquals(1f, shelfAlpha(1f), 1e-6f)
        assertEquals(1f, shelfAlpha(3f), 1e-6f)
        val idle = shelfLook(0f, dashed = false)
        assertEquals(0.055f, idle.bg, 1e-6f); assertEquals(0.07f, idle.border, 1e-6f); assertEquals(0f, idle.shadow, 1e-6f)
        val on = shelfLook(1f, dashed = false)
        assertEquals(0.10f, on.bg, 1e-6f); assertEquals(0.14f, on.border, 1e-6f); assertEquals(0.45f, on.shadow, 1e-6f)
        assertEquals("新的一行平时更淡(c1)", 0.03f, shelfLook(0f, dashed = true).bg, 1e-6f)
        assertEquals("新的一行聚焦时同焦点层(c2)", 0.10f, shelfLook(1f, dashed = true).bg, 1e-6f)
    }

    @Test fun hintsFollowTheZoneAndCarrying() {
        assertEquals(EditHintSet.BROWSE, editHintSet(CARDS, carrying = false))
        assertEquals(EditHintSet.BROWSE, editHintSet(CHIPS, carrying = false))
        assertEquals(EditHintSet.BROWSE, editHintSet(null, carrying = false))
        assertEquals(EditHintSet.NEW_ROW, editHintSet(NEW, carrying = false))
        assertEquals(EditHintSet.CARRY, editHintSet(CARDS, carrying = true))
    }

    // ---- 几何:与效果图、与首页基准线对齐 ----
    @Test fun shelfGeometryMatchesTheMockupAndTheHomeKeyline() {
        assertEquals("卡片起点 = 首页基准线,rowShiftX 直接可用", GtvLayout.CONTENT_KEYLINE, ShelfLayout.SIDE + ShelfLayout.PAD_START, 0f)
        assertEquals("固定中档", 122f, GtvLayout.cardWidth(GtvCardSize.MEDIUM), 0f)
        assertEquals(GtvLayout.CARD_GAP, ShelfLayout.CARD_GAP, 0f)
        assertEquals(ShelfLayout.APP_SHELF_HEIGHT,
            ShelfLayout.CARDS_TOP + GtvLayout.cardHeight(GtvCardSize.MEDIUM) + ShelfLayout.CARDS_BOTTOM, 1e-3f)
        assertEquals(ShelfLayout.CARDS_TOP, ShelfLayout.HEADER_TOP + ShelfLayout.HEADER_HEIGHT + 16f, 0f)
        val overH = GtvLayout.appFocusOverflow(GtvLayout.cardHeight(GtvCardSize.MEDIUM))
        assertTrue("焦点卡放大 + 描边不压到胶囊", ShelfLayout.CARDS_TOP - (ShelfLayout.HEADER_TOP + ShelfLayout.HEADER_HEIGHT) >= overH)
        assertTrue("焦点卡放大 + 描边不出架子底边", ShelfLayout.CARDS_BOTTOM >= overH)
        assertTrue("方向箭头画在放大溢出之外", ShelfLayout.ARROW_GAP > GtvLayout.appFocusOverflow(GtvLayout.cardWidth(GtvCardSize.MEDIUM)))
        assertEquals(ShelfLayout.NEW_ROW_HEIGHT, ShelfLayout.CHOICE_TOP + ShelfLayout.CHOICE_HEIGHT + 28f, 0f)
        assertEquals("焦点线 = 屏幕上方 1/3", 540f / 3f, ShelfLayout.FOCUS_LINE, 0f)
        assertTrue("整层淡化的离屏层四边撑够柔光(R129f)", ShelfLayout.LAYER_PAD >= GtvLayout.APP_FOCUS_GLOW_DP)
    }

    /** Review Focus 4:一行多于一屏,焦点走到哪张,那张放大后的外缘都在架子左右边缘之内。 */
    @Test fun longRowSlidesSoTheFocusedCardStaysInsideTheShelf() {
        val w = GtvLayout.cardWidth(GtvCardSize.MEDIUM)
        val over = GtvLayout.appFocusOverflow(w)
        for (col in 0 until 12) {
            val dx = GtvLayout.rowShiftX(col, GtvCardSize.MEDIUM, 960f)
            val left = GtvLayout.CONTENT_KEYLINE + col * (w + GtvLayout.CARD_GAP) + dx - over
            val right = left + w + 2 * over
            assertTrue("第 $col 张左缘 $left 越过架子左边", left >= ShelfLayout.SIDE)
            assertTrue("第 $col 张右缘 $right 越过架子右边", right <= 960f - ShelfLayout.SIDE)
        }
    }
}
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.EditShelvesLandingTest' 2>&1 | grep -E "Unresolved reference|FAILED|BUILD" | head -5`
Expected: `BUILD FAILED`,`Unresolved reference 'landingAfterSwap'`(以及 `ShelfLayout`、`shelfScroll` 等)。

- [ ] **Step 3: 写实现(追加到 `EditShelves.kt` 末尾)**

```kotlin
/**
 * 货架的几何、配色、动效常量(R165 §2.1 / §2.5)。数值逐项来自效果图 `docs/design/edit-redesign/shelf.css`、`c1.html`、`c2.html`
 * (画布 960 × 540 = dp)。字号不在这里——一律 `Type`(12 sp = `Type.CAPTION`,13 px 的行头取 `Type.BODY` 14,17 = `SECTION`,11 = `MICRO`)。
 */
internal object ShelfLayout {
    /** 架子左右留白、圆角、层间距。 */
    const val SIDE = 40f
    const val CORNER = 20f
    const val GAP = 14f
    /** 第一层架子不位移时的顶边(c1)。 */
    const val TOP = 84f
    /** 焦点线:焦点层的顶边对齐屏幕上方 1/3(540 / 3)。 */
    const val FOCUS_LINE = 180f
    /** 内容末尾(「新的一行」下面那行说明之后)的留白。 */
    const val BOTTOM_PAD = 24f
    /** 页头:左右边距、文字行的顶、渐隐遮罩高(c2:96 px,55% 处起变透明)。 */
    const val HEADER_LEFT = 58f
    const val HEADER_Y = 34f
    const val SCRIM_HEIGHT = 96f
    const val HINT_GAP = 12f
    /** 架子里的顶行:离顶 12、高 28、左 18 / 右 16;行图标 18、图标与字间距 8。 */
    const val HEADER_TOP = 12f
    const val HEADER_HEIGHT = 28f
    const val PAD_START = 18f
    const val PAD_END = 16f
    const val ICON = 18f
    const val ICON_GAP = 8f
    /** 卡片行:离架子顶 56(= 12 + 28 + 16),卡下留 15.375,应用架子总高 140。 */
    const val CARDS_TOP = 56f
    const val CARDS_BOTTOM = 15.375f
    const val APP_SHELF_HEIGHT = 140f
    const val CARD_GAP = 20f
    /** 操作胶囊(spec §2.5):28 高、14 圆角、左右内边距 12、间距 8、图标 15;聚焦放大 1.08;平时白 8% 底。 */
    const val CHIP_HEIGHT = 28f
    const val CHIP_CORNER = 14f
    const val CHIP_PAD_H = 12f
    const val CHIP_GAP = 8f
    const val CHIP_ICON = 15f
    const val CHIP_ICON_GAP = 5f
    const val CHIP_FOCUS_SCALE = 1.08f
    const val CHIP_IDLE_ALPHA = 0.08f
    const val CHIP_SHADOW = 6f
    /** 「新的一行」的选择卡(c2):离架子顶 52、300 × 110、圆角 16、内边距 20 / 16、间距 20;聚焦放大 1.05;平时白 7% 底;已满整卡 45%。 */
    const val CHOICE_TOP = 52f
    const val CHOICE_WIDTH = 300f
    const val CHOICE_HEIGHT = 110f
    const val CHOICE_CORNER = 16f
    const val CHOICE_PAD_H = 20f
    const val CHOICE_PAD_V = 16f
    const val CHOICE_GAP = 20f
    const val CHOICE_ICON = 28f
    const val CHOICE_IDLE_ALPHA = 0.07f
    const val CHOICE_FOCUS_SCALE = 1.05f
    const val CHOICE_SHADOW = 14f
    const val CHOICE_FULL_ALPHA = 0.45f
    /** 「新的一行」架子高 190(= 52 + 110 + 28),下面一行说明离架子 16。 */
    const val NEW_ROW_HEIGHT = 190f
    const val NEW_ROW_CAPTION_GAP = 16f
    /** 架子玻璃(spec §2.5):平时白 5.5% 底 + 白 7% 边;焦点层白 10% + 白 14% + 阴影;新的一行平时白 3%。 */
    const val BG_ALPHA = 0.055f
    const val BORDER_ALPHA = 0.07f
    const val BG_ALPHA_FOCUSED = 0.10f
    const val BORDER_ALPHA_FOCUSED = 0.14f
    const val NEW_ROW_BG_ALPHA = 0.03f
    const val BORDER = 1f
    /** 焦点层阴影(效果图 `0 20px 60px rgba(0,0,0,.45)`):黑 45%、向下 20、铺开 40。 */
    const val SHADOW_ALPHA = 0.45f
    const val SHADOW_SPREAD = 40f
    const val SHADOW_DY = 20f
    /** 非焦点层整层不透明度(效果图 .62)。 */
    const val IDLE_SHELF_ALPHA = 0.62f
    /** 整层淡化用的离屏层四边各撑大这么多(R129f:焦点卡的 60 dp 柔光画在架子外)。 */
    const val LAYER_PAD = GtvLayout.APP_FOCUS_GLOW_DP
    /** 拿起时的方向箭头:离卡边 12(在放大溢出之外)、三角半宽 5;抬起的阴影 12。 */
    const val ARROW_GAP = 12f
    const val ARROW_SIZE = 5f
    const val CARRY_SHADOW = 12f
    /** 换层(架子亮度 + 整页位移)200 ms、拿起 / 放下 150 ms,曲线 FastOutSlowIn(spec §2.5)。 */
    const val SHIFT_MS = 200
    const val LIFT_MS = 150
}

/** 落在第 [shelf] 层的 [chip] 那颗;那颗此刻不在(比如删除已不允许)→ 第一颗。 */
internal fun landingOnChip(shelves: List<Shelf>, shelf: Int, chip: ShelfChip): ShelfSpot =
    clampSpot(shelves, ShelfSpot(shelf, ShelfZone.CHIPS, shelfChips(shelves, shelf).indexOf(chip).coerceAtLeast(0)))

/** 落在第 [shelf] 层第 [col] 张卡(夹到这一层的卡片数;空架子 = 「添加应用」方块)。 */
internal fun landingOnCard(shelves: List<Shelf>, shelf: Int, col: Int): ShelfSpot =
    clampSpot(shelves, ShelfSpot(shelf, ShelfZone.CARDS, col))

/**
 * 上移 / 下移之后([shelves] 是换完的,[newShelf] 是这一层的新下标):跟着这一层走,落同一颗胶囊;那颗在新位置上没了
 * (移到第一层没有「上移」、移到最后一个内容层没有「下移」)→ 反方向那颗;再没有 →「换图标」(Review Focus 2)。
 */
internal fun landingAfterSwap(shelves: List<Shelf>, newShelf: Int, pressed: ShelfChip): ShelfSpot {
    val chips = shelfChips(shelves, newShelf)
    val opposite = when (pressed) {
        ShelfChip.UP -> ShelfChip.DOWN
        ShelfChip.DOWN -> ShelfChip.UP
        else -> pressed
    }
    val chip = listOf(pressed, opposite, ShelfChip.ICON).firstOrNull { it in chips } ?: ShelfChip.ADD_APP
    return landingOnChip(shelves, newShelf, chip)
}

/**
 * 删掉第 [deleted] 层之后([shelves] 是删完的):落上一层的**第一颗**胶囊(「添加应用」);删的是第一层落新的第一层。
 * spec 只写「落上一层的胶囊」——取第一颗而不是同位置的「删除」,免得连按两下确定连删两行(空行删除不弹确认)。
 */
internal fun landingAfterDelete(shelves: List<Shelf>, deleted: Int): ShelfSpot =
    clampSpot(shelves, ShelfSpot((deleted - 1).coerceAtLeast(0), ShelfZone.CHIPS, 0))

/** 新建应用行之后:落最后一个应用架子(就是新的那层)的「添加应用」方块。 */
internal fun landingAfterAppend(shelves: List<Shelf>): ShelfSpot =
    clampSpot(shelves, ShelfSpot(shelves.indexOfLast { it is Shelf.AppShelf }.coerceAtLeast(0), ShelfZone.CARDS, 0))

/**
 * 货架整块往上挪多少 px(≥ 0,spec §2.1):焦点层([focused])的顶边对齐焦点线 [focusLine],再夹到「内容末尾 + [bottomPad]
 * 刚好贴屏幕底边」为止。[heights] = 各层实测高度,[top] = 第一层不位移时的顶边,[gap] = 层间距,[viewport] = 屏高;
 * 视窗没量到(0)或没有层 → 0。
 */
internal fun shelfScroll(heights: List<Int>, gap: Int, focused: Int, top: Int, focusLine: Int, viewport: Int, bottomPad: Int): Int {
    if (heights.isEmpty() || viewport <= 0) return 0
    val f = focused.coerceIn(0, heights.lastIndex)
    val focusedTop = top + (0 until f).sumOf { heights[it] + gap }
    val contentBottom = top + heights.sum() + gap * (heights.size - 1) + bottomPad
    val maxShift = (contentBottom - viewport).coerceAtLeast(0)
    return (focusedTop - focusLine).coerceIn(0, maxShift)
}

/** 拿起时卡片四周画哪几个方向箭头:只画按下去真的会挪的方向(与 [moveInLayout] 同一判据;照 Google TV gtv-04)。 */
internal fun carryArrows(view: List<LayoutRow>, pos: MovePos): Set<MoveDir> =
    MoveDir.entries.filterTo(mutableSetOf()) { moveInLayout(view, pos, it).first !== view }

/** 整层不透明度:[focus] 0 = 非焦点层([ShelfLayout.IDLE_SHELF_ALPHA]),1 = 焦点层。只在绘制阶段读。 */
internal fun shelfAlpha(focus: Float): Float {
    val t = focus.coerceIn(0f, 1f)
    return ShelfLayout.IDLE_SHELF_ALPHA + (1f - ShelfLayout.IDLE_SHELF_ALPHA) * t
}

/** 一层玻璃的底 / 边 / 阴影透明度(白 / 白 / 黑),按焦点系数插值。 */
internal data class ShelfLook(val bg: Float, val border: Float, val shadow: Float)

internal fun shelfLook(focus: Float, dashed: Boolean): ShelfLook {
    val t = focus.coerceIn(0f, 1f)
    val bgIdle = if (dashed) ShelfLayout.NEW_ROW_BG_ALPHA else ShelfLayout.BG_ALPHA
    return ShelfLook(
        bg = bgIdle + (ShelfLayout.BG_ALPHA_FOCUSED - bgIdle) * t,
        border = ShelfLayout.BORDER_ALPHA + (ShelfLayout.BORDER_ALPHA_FOCUSED - ShelfLayout.BORDER_ALPHA) * t,
        shadow = ShelfLayout.SHADOW_ALPHA * t,
    )
}

/** 页头右边的按键提示是哪一组:平时(c1)、焦点在新的一行(c2)、拿起中。 */
internal enum class EditHintSet { BROWSE, NEW_ROW, CARRY }

internal fun editHintSet(zone: ShelfZone?, carrying: Boolean): EditHintSet = when {
    carrying -> EditHintSet.CARRY
    zone == ShelfZone.NEW -> EditHintSet.NEW_ROW
    else -> EditHintSet.BROWSE
}
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.EditShelvesLandingTest' --tests 'com.uniteduone.launcher.EditShelvesTest' 2>&1 | grep -E "FAILED|BUILD" | head -5`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git add app/src/main/java/com/uniteduone/launcher/EditShelves.kt app/src/test/java/com/uniteduone/launcher/EditShelvesLandingTest.kt
git commit -m "$(cat <<'EOF'
feat(edit): 货架落点、焦点线位移、拿起箭头与几何常量(R165,纯逻辑)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 追加应用行(`appendAppRow`)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/LayoutOps.kt`(在 `addRowBelow` 上方插入;`addRowBelow` 本任务不删,旧编辑页还在用,Task 8 删)
- Test: `app/src/test/java/com/uniteduone/launcher/LayoutOpsTest.kt`、`LayoutOpsBoundaryTest.kt`

**Interfaces:**
- Consumes: `LayoutRow`、`NEW_ROW_ICON`、`MAX_ROWS`
- Produces: `internal fun appRowCount(rows: List<LayoutRow>): Int`、`internal fun appendAppRow(rows: List<LayoutRow>): List<LayoutRow>`

- [ ] **Step 1: 写失败的测试**

在 `LayoutOpsTest` 类里(`deleteRemovesButNeverTheLastRow` 之前)加:

```kotlin
    /** R165:新行一律追加在最后(「新的一行 → 应用行」),不再有「在下方新建一行」。 */
    @Test fun appendAddsAnEmptyAppRowAtTheEnd() {
        val next = appendAppRow(three)
        assertEquals(listOf("movie", "tv", "music", NEW_ROW_ICON), next.map { it.icon })
        assertEquals(LayoutRow(icon = NEW_ROW_ICON), next.last())
        assertEquals(three, next.take(3))
        assertEquals(3, appRowCount(three))
    }

    @Test fun appendStopsAtFiveAppRows() {
        val five = three + LayoutRow("games") + LayoutRow("kids")
        assertSame(five, appendAppRow(five))
        val six = five + LayoutRow("tools")
        assertSame("手改坏的超量文件不再加", six, appendAppRow(six))
    }
```

在 `LayoutOpsBoundaryTest` 类里(`// ---- 删行 ----` 之前)加:

```kotlin
    @Test fun appendToAnEmptyOrSingleLayout() {
        assertEquals(listOf(LayoutRow(icon = NEW_ROW_ICON)), appendAppRow(empty))
        assertEquals(listOf(ONLY, NEW_ROW_ICON), appendAppRow(one).map { it.icon })
        val five = appendAppRow(four)
        assertEquals(listOf(A, B, C, D, NEW_ROW_ICON), five.map { it.icon })
        assertEquals(four, five.take(4))
        assertSame("第 5 行是最后一行", five, appendAppRow(five))
    }
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.LayoutOpsTest' --tests 'com.uniteduone.launcher.LayoutOpsBoundaryTest' 2>&1 | grep -E "Unresolved reference|FAILED|BUILD" | head -5`
Expected: `BUILD FAILED`,`Unresolved reference 'appendAppRow'`。

- [ ] **Step 3: 写实现**

在 `LayoutOps.kt` 的 `addRowBelow` 的 KDoc 之前插入:

```kotlin
/**
 * 应用行有几行(R165)。现在每一行都是应用行;计划 2 加频道行后改成只数 `channel == null` 的行——
 * 「新的一行 → 应用行」与删除胶囊的判据都经这里,改一处即可。
 */
internal fun appRowCount(rows: List<LayoutRow>): Int = rows.size

/**
 * 在最后追加一个空应用行(默认图标 [NEW_ROW_ICON];R165「新的一行 → 应用行」,新行一律插在最后,换位置用上移 / 下移)。
 * 应用行已满 [MAX_ROWS](或手改坏的文件超量)→ 原样返回**同一个** list,调用方据此不写盘、不挪焦点。
 */
internal fun appendAppRow(rows: List<LayoutRow>): List<LayoutRow> =
    if (appRowCount(rows) >= MAX_ROWS) rows else rows + LayoutRow(icon = NEW_ROW_ICON)
```

- [ ] **Step 4: 跑测试,确认通过**

Run: 同 Step 2。
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git add app/src/main/java/com/uniteduone/launcher/LayoutOps.kt app/src/test/java/com/uniteduone/launcher/LayoutOpsTest.kt app/src/test/java/com/uniteduone/launcher/LayoutOpsBoundaryTest.kt
git commit -m "$(cat <<'EOF'
feat(edit): appendAppRow——新行一律追加在最后(R165)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 确定键一次按压的长短判定(`OkPress`)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/EditPress.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/OkPressTest.kt`

**Interfaces:**
- Consumes: `LONG_PRESS_MS`(MainActivity.kt 顶层 `internal const val`,600)
- Produces:
  - `internal enum class OkOutcome { NOTHING, LONG_PRESS, SHORT_PRESS }`
  - `internal class OkPress(longPressMs: Long = LONG_PRESS_MS)`,方法 `fun owns(downTime: Long): Boolean`、`fun onDown(downTime: Long, eventTime: Long, repeatCount: Int): OkOutcome`、`fun onUp(downTime: Long, canceled: Boolean): OkOutcome`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/OkPressTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R165:编辑页卡片上的确定键——短按松开 = 拿起 / 放下,按满 600 ms = 卡片菜单。按 downTime 认一次按压(同首页移动态的
 * moveDownTime 手法),每一下按压天然不同,不需要清(铁律 7)。
 */
class OkPressTest {
    private val t0 = 10_000L

    @Test fun aQuickReleaseIsAShortPress() {
        val p = OkPress()
        assertEquals(OkOutcome.NOTHING, p.onDown(t0, t0, 0))
        assertEquals(OkOutcome.NOTHING, p.onDown(t0, t0 + 300, 1))     // 首次重复,还没满 600
        assertEquals(OkOutcome.SHORT_PRESS, p.onUp(t0, canceled = false))
    }

    @Test fun holdingFiresTheLongPressExactlyOnce() {
        val p = OkPress()
        p.onDown(t0, t0, 0)
        assertEquals(OkOutcome.NOTHING, p.onDown(t0, t0 + 400, 1))
        assertEquals(OkOutcome.LONG_PRESS, p.onDown(t0, t0 + 650, 2))
        assertEquals("同一下按压只出一次", OkOutcome.NOTHING, p.onDown(t0, t0 + 700, 3))
    }

    /** Review Focus 1:长按开菜单之后松手——这一下的 UP 归我们(要吞掉),但绝不是短按(不能拿起)。 */
    @Test fun upOfALongPressIsOwnedButNeverAShortPress() {
        val p = OkPress()
        p.onDown(t0, t0, 0)
        p.onDown(t0, t0 + 650, 2)
        assertTrue("UP 要吞掉:否则落到菜单第一颗胶囊上被当成一次点击", p.owns(t0))
        assertEquals(OkOutcome.NOTHING, p.onUp(t0, canceled = false))
    }

    @Test fun aCanceledReleaseDoesNothing() {
        val p = OkPress()
        p.onDown(t0, t0, 0)
        assertEquals(OkOutcome.NOTHING, p.onUp(t0, canceled = true))
    }

    @Test fun pressesWeNeverSawAreNotOurs() {
        val p = OkPress()
        assertFalse(p.owns(t0))
        assertEquals("没见过 DOWN 的 UP", OkOutcome.NOTHING, p.onUp(t0, canceled = false))
        assertEquals("没见过首个 DOWN 的重复", OkOutcome.NOTHING, p.onDown(t0, t0 + 900, 5))
    }

    @Test fun theNextPressStartsFresh() {
        val p = OkPress()
        p.onDown(t0, t0, 0); p.onDown(t0, t0 + 650, 2); p.onUp(t0, false)
        val t1 = t0 + 5_000
        p.onDown(t1, t1, 0)
        assertFalse(p.owns(t0))
        assertTrue(p.owns(t1))
        assertEquals(OkOutcome.SHORT_PRESS, p.onUp(t1, canceled = false))
    }

    @Test fun thresholdIsTheSharedLongPressTime() {
        val p = OkPress()
        p.onDown(t0, t0, 0)
        assertEquals(OkOutcome.NOTHING, p.onDown(t0, t0 + LONG_PRESS_MS - 1, 1))
        assertEquals(OkOutcome.LONG_PRESS, p.onDown(t0, t0 + LONG_PRESS_MS, 2))
    }
}
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.OkPressTest' 2>&1 | grep -E "Unresolved reference|FAILED|BUILD" | head -5`
Expected: `BUILD FAILED`,`Unresolved reference 'OkPress'`。

- [ ] **Step 3: 写实现**

`app/src/main/java/com/uniteduone/launcher/EditPress.kt`:

```kotlin
package com.uniteduone.launcher

/** 一次确定键按压的结论:还没有结论 / 按满了长按 / 短按松开。 */
internal enum class OkOutcome { NOTHING, LONG_PRESS, SHORT_PRESS }

/**
 * **编辑页卡片上的确定键**(R165 §2.3):短按松开 = 拿起(拿起中 = 放下),按满 [longPressMs] = 卡片菜单。
 * 按 downTime 认一次按压(同首页移动态 / 旧搬运的 `CarryPresses`):[onDown] 在首个 DOWN(repeatCount 0)记下它,
 * 重复事件按满时长出一次 [OkOutcome.LONG_PRESS] 并记为「按住过」;[onUp] 只有「我们见过它的 DOWN、没按住过、没被取消」
 * 才是 [OkOutcome.SHORT_PRESS]。[owns] = 这一下是不是我们接手的——**接手的那一下,UP 一律吞掉**(哪怕中间开了菜单),
 * 否则 UP 会落到菜单第一颗胶囊上被当成一次点击(Review Focus 1)。不是 Compose 状态:只在按键回调里读写。
 */
internal class OkPress(private val longPressMs: Long = LONG_PRESS_MS) {
    private var down = -1L
    private var held = -1L

    fun owns(downTime: Long): Boolean = downTime == down

    fun onDown(downTime: Long, eventTime: Long, repeatCount: Int): OkOutcome {
        if (repeatCount == 0) {
            down = downTime
            return OkOutcome.NOTHING
        }
        if (downTime != down || held == downTime) return OkOutcome.NOTHING
        if (eventTime - downTime >= longPressMs) {
            held = downTime
            return OkOutcome.LONG_PRESS
        }
        return OkOutcome.NOTHING
    }

    fun onUp(downTime: Long, canceled: Boolean): OkOutcome =
        if (downTime == down && downTime != held && !canceled) OkOutcome.SHORT_PRESS else OkOutcome.NOTHING
}
```

- [ ] **Step 4: 跑测试,确认通过**

Run: 同 Step 2。
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git add app/src/main/java/com/uniteduone/launcher/EditPress.kt app/src/test/java/com/uniteduone/launcher/OkPressTest.kt
git commit -m "$(cat <<'EOF'
feat(edit): OkPress——编辑页确定键短按 / 长按判定(R165)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 壁纸模糊底(算一次、缓存位图)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/EditBackdrop.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/Ambient.kt`(`private fun boxBlur` → `internal fun boxBlur`)
- Test: `app/src/test/java/com/uniteduone/launcher/EditBackdropTest.kt`

**Interfaces:**
- Consumes: `boxBlur(p: FloatArray, w: Int, h: Int, r: Int)`、`ditherAt(x: Int, y: Int): Float`(Ambient.kt)、`Wallpapers.resolveSource(ctx, value): File?`、`decodeImagePath(path: String, opts: BitmapFactory.Options?): Bitmap?`、`GtvTokens.MenuBg`
- Produces:
  - `internal object EditBackdrop { DOWNSCALE, SRC_W, SRC_H, OUT_W, OUT_H, BLUR_RADIUS, BLUR_PASSES, SATURATION, DIM, DIM_COLOR }`
  - `internal fun editBackdropPixels(src: IntArray, sw: Int, sh: Int, outW: Int, outH: Int, dim: Float = …, dimColor: Int = …, saturation: Float = …, radius: Int = …, passes: Int = …): IntArray`
  - `@WorkerThread fun buildEditBackdrop(ctx: Context, wallpaperValue: String): ImageBitmap?`
  - `fun cachedEditBackdrop(): ImageBitmap?`
  - `internal fun Modifier.editBackdrop(img: ImageBitmap?): Modifier`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/EditBackdropTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * R165 §2.5:编辑页底 = 当前壁纸缩到 1/8 做一次模糊、上盖约 72% 深色,算一次、缓存成位图,之后每帧只画它
 * (A95L 不实时算模糊)。纯像素计算在 [editBackdropPixels],这里不起 Android。
 */
class EditBackdropTest {
    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun ch(p: Int, c: Int) = (p shr (16 - 8 * c)) and 0xFF
    private fun lin(c: Int): Double { val s = c / 255.0; return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
    private fun lum(p: Int) = 0.2126 * lin(ch(p, 0)) + 0.7152 * lin(ch(p, 1)) + 0.0722 * lin(ch(p, 2))
    private fun contrast(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun constantsAreAnEighthOfTheScreenAndTheSpecDim() {
        assertEquals(1920, EditBackdrop.SRC_W * EditBackdrop.DOWNSCALE)
        assertEquals(1080, EditBackdrop.SRC_H * EditBackdrop.DOWNSCALE)
        assertEquals(0.72f, EditBackdrop.DIM, 0f)
    }

    @Test fun uniformGreyIsDimmedToTheMixedValue() {
        val out = editBackdropPixels(IntArray(24 * 14) { px(128, 128, 128) }, 24, 14, 48, 28)
        assertEquals(48 * 28, out.size)
        // 128 × 0.28 + 底色 (10, 10, 12) × 0.72 = 43.0 / 43.0 / 44.5;灰色不受饱和度影响;抖动 ±0.5 级
        for (p in out) {
            assertEquals(0xFF, p ushr 24)
            assertTrue(ch(p, 0) in 42..44); assertTrue(ch(p, 1) in 42..44); assertTrue(ch(p, 2) in 43..45)
        }
    }

    /** Review Focus 5:最亮的壁纸(纯白)也不把底洗白——胶囊未聚焦的字(Ink.Label)≥ 3:1,标题(Ink.Primary)≥ 4.5:1。 */
    @Test fun brightestWallpaperStillLeavesTextReadable() {
        val out = editBackdropPixels(IntArray(24 * 14) { px(255, 255, 255) }, 24, 14, 48, 28)
        val label = 0xFFB0B0B0.toInt(); val primary = 0xFFF5F5F5.toInt()
        for (p in out) {
            assertTrue("底色 ${Integer.toHexString(p)} 太亮", ch(p, 0) <= 80)
            assertTrue(contrast(label, p) >= 3.0)
            assertTrue(contrast(primary, p) >= 4.5)
        }
    }

    @Test fun saturationBoostsColourBeforeDimming() {
        val out = editBackdropPixels(IntArray(24 * 14) { px(200, 60, 60) }, 24, 14, 48, 28)
        // 不加饱和度时 (200 − 60) × 0.28 ≈ 39;× 1.2 之后 ≈ 47
        assertTrue(out.all { ch(it, 0) - ch(it, 1) >= 45 })
    }

    @Test fun blurSoftensAHardEdge() {
        val w = 48; val h = 8
        val src = IntArray(w * h) { i -> if (i % w < w / 2) px(0, 0, 0) else px(255, 255, 255) }
        val out = editBackdropPixels(src, w, h, w, h)
        val mid = h / 2
        val rowR = (0 until w).map { ch(out[mid * w + it], 0) }
        assertTrue("从左到右不减:$rowR", rowR.zipWithNext().all { (a, b) -> b >= a - 1 })
        val edge = rowR[w / 2]
        assertTrue("分界处是过渡值 $edge,不是硬边", edge > rowR.first() + 5 && edge < rowR.last() - 5)
    }

    @Test fun sameInputSameOutput() {
        val src = IntArray(30 * 17) { i -> px(i % 256, (i * 7) % 256, (i * 13) % 256) }
        assertArrayEquals(editBackdropPixels(src, 30, 17, 60, 34), editBackdropPixels(src, 30, 17, 60, 34))
    }

    /** Review Focus 5(另一半):尺寸对不上的输入当场拒绝(调用方 runCatching 兜底 → 退回纯 MenuBg),不画乱码。 */
    @Test(expected = IllegalArgumentException::class)
    fun undecodableInputIsRejectedNotCrashed() {
        editBackdropPixels(IntArray(10), 24, 14, 48, 28)
    }
}
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.EditBackdropTest' 2>&1 | grep -E "Unresolved reference|FAILED|BUILD" | head -5`
Expected: `BUILD FAILED`,`Unresolved reference 'editBackdropPixels'`。

- [ ] **Step 3: 写实现**

先在 `Ambient.kt` 把

```kotlin
/** 原地做一遍可分离的盒式模糊(边缘按边界值延伸)。 */
private fun boxBlur(p: FloatArray, w: Int, h: Int, r: Int) {
```

改成

```kotlin
/** 原地做一遍可分离的盒式模糊(边缘按边界值延伸)。编辑页的模糊底(EditBackdrop.kt)也用它。 */
internal fun boxBlur(p: FloatArray, w: Int, h: Int, r: Int) {
```

再新建 `app/src/main/java/com/uniteduone/launcher/EditBackdrop.kt`:

```kotlin
package com.uniteduone.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.WorkerThread
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * **编辑桌面的模糊底**(R165 §2.5,效果图 `.bg{filter:blur(36px) saturate(1.2)}` + `.dim{rgba(10,10,12,.72)}`):
 * 当前壁纸缩到 1/8(1920 → 240)、三遍盒式模糊(半径 8 ≈ 效果图 36 px 的高斯,按 1/8 换算)、饱和度 × 1.2、
 * 再按 72% 混进深色,输出 480 × 270(±0.5 级抖动防色带),绘制时双线性放大铺满。**算一次、缓存成位图**,之后每帧只画它——
 * A95L 上不实时算模糊(不用 RenderEffect)。没有壁纸 / 解不出来 → null,页面画纯 MenuBg。
 * 与 [Ambient](整屏页的氛围底,18%、压亮部)是两种东西:那个只要一点冷暖,这个要看得出是哪张壁纸。
 */
internal object EditBackdrop {
    const val DOWNSCALE = 8
    const val SRC_W = 240
    const val SRC_H = 135
    const val OUT_W = 480
    const val OUT_H = 270
    const val BLUR_RADIUS = 8
    const val BLUR_PASSES = 3
    const val SATURATION = 1.2f
    const val DIM = 0.72f
    const val DIM_COLOR = 0xFF0A0A0C.toInt()
}

/**
 * 纯计算:[src] 是 [sw] × [sh] 的 ARGB(缩小后的壁纸),返回 [outW] × [outH] 的不透明 ARGB。
 * 步骤:盒式模糊 [passes] 遍 → 饱和度 × [saturation] → `v × (1 − dim) + dimColor × dim` → ±0.5 级抖动取整。
 * 同一输入结果恒定(抖动按坐标取哈希,[ditherAt])。尺寸对不上 → IllegalArgumentException。
 */
internal fun editBackdropPixels(
    src: IntArray, sw: Int, sh: Int, outW: Int, outH: Int,
    dim: Float = EditBackdrop.DIM, dimColor: Int = EditBackdrop.DIM_COLOR,
    saturation: Float = EditBackdrop.SATURATION,
    radius: Int = EditBackdrop.BLUR_RADIUS, passes: Int = EditBackdrop.BLUR_PASSES,
): IntArray {
    require(sw > 0 && sh > 0 && outW > 0 && outH > 0 && src.size == sw * sh)
    val ch = Array(3) { c -> FloatArray(sw * sh) { i -> ((src[i] shr (16 - 8 * c)) and 0xFF).toFloat() } }
    repeat(passes) { for (p in ch) boxBlur(p, sw, sh, radius) }
    for (i in 0 until sw * sh) {
        val g = (ch[0][i] + ch[1][i] + ch[2][i]) / 3f
        for (c in 0..2) ch[c][i] = (g + (ch[c][i] - g) * saturation).coerceIn(0f, 255f)
    }
    val add = FloatArray(3) { c -> ((dimColor shr (16 - 8 * c)) and 0xFF).toFloat() * dim }
    val keep = 1f - dim
    val out = IntArray(outW * outH)
    for (y in 0 until outH) {
        val fy = ((y + 0.5f) * sh / outH - 0.5f).coerceIn(0f, (sh - 1).toFloat())
        val y0 = fy.toInt(); val y1 = minOf(y0 + 1, sh - 1); val ty = fy - y0
        for (x in 0 until outW) {
            val fx = ((x + 0.5f) * sw / outW - 0.5f).coerceIn(0f, (sw - 1).toFloat())
            val x0 = fx.toInt(); val x1 = minOf(x0 + 1, sw - 1); val tx = fx - x0
            val d = ditherAt(x, y)
            var argb = 0xFF shl 24
            for (c in 0..2) {
                val p = ch[c]
                val top = p[y0 * sw + x0] + (p[y0 * sw + x1] - p[y0 * sw + x0]) * tx
                val bot = p[y1 * sw + x0] + (p[y1 * sw + x1] - p[y1 * sw + x0]) * tx
                val v = top + (bot - top) * ty
                val o = (v * keep + add[c] + d).roundToInt().coerceIn(0, 255)
                argb = argb or (o shl (16 - 8 * c))
            }
            out[y * outW + x] = argb
        }
    }
    return out
}

/** 按壁纸选中值算模糊底。IO 线程;同一张图(路径 + 修改时间 + 大小)不重算、给同一个对象(不让读它的树白白重组)。 */
@WorkerThread
fun buildEditBackdrop(ctx: Context, wallpaperValue: String): ImageBitmap? {
    val file = Wallpapers.resolveSource(ctx, wallpaperValue) ?: return null
    val key = "${file.path}|${file.lastModified()}|${file.length()}"
    lastEditBackdrop?.let { (k, img) -> if (k == key) return img }
    return computeEditBackdrop(file)?.asImageBitmap()?.also { lastEditBackdrop = key to it }
}

/** 上一次算出的模糊底:再进编辑页时当初值,第一帧就有底,不先闪一下纯色。 */
fun cachedEditBackdrop(): ImageBitmap? = lastEditBackdrop?.second

@Volatile private var lastEditBackdrop: Pair<String, ImageBitmap>? = null

@WorkerThread
private fun computeEditBackdrop(file: java.io.File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decodeImagePath(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= EditBackdrop.SRC_W && bounds.outHeight / (sample * 2) >= EditBackdrop.SRC_H) sample *= 2
    val decoded = decodeImagePath(file.path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: return null
    val small = Bitmap.createScaledBitmap(decoded, EditBackdrop.SRC_W, EditBackdrop.SRC_H, true)
    if (small !== decoded) decoded.recycle()
    val px = IntArray(EditBackdrop.SRC_W * EditBackdrop.SRC_H)
    small.getPixels(px, 0, EditBackdrop.SRC_W, 0, 0, EditBackdrop.SRC_W, EditBackdrop.SRC_H)
    small.recycle()
    val out = editBackdropPixels(px, EditBackdrop.SRC_W, EditBackdrop.SRC_H, EditBackdrop.OUT_W, EditBackdrop.OUT_H)
    return Bitmap.createBitmap(out, EditBackdrop.OUT_W, EditBackdrop.OUT_H, Bitmap.Config.ARGB_8888)
}

/** 编辑页的底:有模糊底就放大铺满(它本身不透明),没有就纯 MenuBg。只在绘制阶段,不改布局、不进焦点。 */
internal fun Modifier.editBackdrop(img: ImageBitmap?): Modifier = drawBehind {
    if (img != null) {
        drawImage(img, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
    } else drawRect(GtvTokens.MenuBg)
}
```

- [ ] **Step 4: 跑测试,确认通过(连同 Ambient 的旧测试)**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.EditBackdropTest' --tests 'com.uniteduone.launcher.AmbientTest' 2>&1 | grep -E "FAILED|BUILD" | head -5`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git add app/src/main/java/com/uniteduone/launcher/EditBackdrop.kt app/src/main/java/com/uniteduone/launcher/Ambient.kt app/src/test/java/com/uniteduone/launcher/EditBackdropTest.kt
git commit -m "$(cat <<'EOF'
feat(edit): 编辑页壁纸模糊底——1/8 缩小模糊一次、缓存位图(R165)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 货架文案(三种语言)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`、`app/src/main/res/values-en/strings.xml`、`app/src/main/res/values-zh-rTW/strings.xml`
- Test: `app/src/test/java/com/uniteduone/launcher/CopyTest.kt`

**Interfaces:**
- Produces(资源 id,Task 7 / 8 用):`R.plurals.edit_summary_rows`、`R.plurals.edit_apps_count`、`R.string.edit_key_ok`、`edit_key_back`、`edit_hint_pick`、`edit_hint_row`、`edit_hint_done`、`edit_hint_choose`、`edit_hint_add`、`edit_hint_move`、`edit_hint_drop`、`edit_hint_cancel`、`edit_chip_icon`、`edit_chip_up`、`edit_chip_down`、`edit_chip_delete`、`edit_new_row`、`edit_choice_app_row`、`edit_choice_app_row_desc`、`edit_choice_full`(`%1$d`)、`edit_new_row_caption`(`%1$d`)。胶囊「添加应用」与空架子方块沿用现有 `R.string.edit_row_add_app`。

- [ ] **Step 1: 写失败的测试**

在 `CopyTest` 类末尾(最后一个 `}` 之前)加:

```kotlin
    /** R165 货架的新文案:三种语言都有;两条带行数上限的、两条 plurals 都带 %1$d。 */
    @Test fun editShelfStringsExist() {
        val keys = listOf(
            "edit_summary_rows", "edit_apps_count", "edit_key_ok", "edit_key_back",
            "edit_hint_pick", "edit_hint_row", "edit_hint_done", "edit_hint_choose", "edit_hint_add",
            "edit_hint_move", "edit_hint_drop", "edit_hint_cancel",
            "edit_chip_icon", "edit_chip_up", "edit_chip_down", "edit_chip_delete",
            "edit_new_row", "edit_choice_app_row", "edit_choice_app_row_desc", "edit_choice_full", "edit_new_row_caption",
        )
        for (l in langs) {
            val s = strings(l)
            for (k in keys) assertNotNull("$l 缺 $k(R165)", s[k])
            for (k in listOf("edit_choice_full", "edit_new_row_caption", "edit_summary_rows", "edit_apps_count")) {
                assertEquals("$l 的 $k", setOf("%1\$d"), placeholders(s.getValue(k)))
            }
        }
    }
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.CopyTest' 2>&1 | grep -E "FAILED|BUILD|缺" | head -5`
Expected: `CopyTest > editShelfStringsExist FAILED`(`values 缺 edit_summary_rows(R165)`)。

- [ ] **Step 3: 加文案**

`app/src/main/res/values/strings.xml`:在 `<string name="edit_row_delete_ok">删除</string>` 这一行之后插入

```xml
    <!-- edit_summary_* / edit_hint_* / edit_chip_* / edit_choice_* : 编辑桌面「货架」(R165) -->
    <plurals name="edit_summary_rows">
        <item quantity="one">%1$d 行</item>
        <item quantity="other">%1$d 行</item>
    </plurals>
    <plurals name="edit_apps_count">
        <item quantity="one">%1$d 个应用</item>
        <item quantity="other">%1$d 个应用</item>
    </plurals>
    <string name="edit_key_ok">确定</string>
    <string name="edit_key_back">返回</string>
    <string name="edit_hint_pick">拿起卡片</string>
    <string name="edit_hint_row">行操作</string>
    <string name="edit_hint_done">完成</string>
    <string name="edit_hint_choose">选择</string>
    <string name="edit_hint_add">添加</string>
    <string name="edit_hint_move">移动</string>
    <string name="edit_hint_drop">放下</string>
    <string name="edit_hint_cancel">取消</string>
    <string name="edit_chip_icon">换图标</string>
    <string name="edit_chip_up">上移</string>
    <string name="edit_chip_down">下移</string>
    <string name="edit_chip_delete">删除</string>
    <string name="edit_new_row">新的一行</string>
    <string name="edit_choice_app_row">应用行</string>
    <string name="edit_choice_app_row_desc">放你自己挑的应用，可以排顺序、换图标</string>
    <string name="edit_choice_full">已满 %1$d 行</string>
    <string name="edit_new_row_caption">应用行最多 %1$d 行</string>
```

`app/src/main/res/values-en/strings.xml`:在 `<string name="edit_row_delete_ok">Delete</string>` 之后插入

```xml
    <!-- edit_summary_* / edit_hint_* / edit_chip_* / edit_choice_* : Edit Home Screen shelves (R165) -->
    <plurals name="edit_summary_rows">
        <item quantity="one">%1$d row</item>
        <item quantity="other">%1$d rows</item>
    </plurals>
    <plurals name="edit_apps_count">
        <item quantity="one">%1$d app</item>
        <item quantity="other">%1$d apps</item>
    </plurals>
    <string name="edit_key_ok">OK</string>
    <string name="edit_key_back">Back</string>
    <string name="edit_hint_pick">Pick up</string>
    <string name="edit_hint_row">Row actions</string>
    <string name="edit_hint_done">Done</string>
    <string name="edit_hint_choose">Choose</string>
    <string name="edit_hint_add">Add</string>
    <string name="edit_hint_move">Move</string>
    <string name="edit_hint_drop">Drop</string>
    <string name="edit_hint_cancel">Cancel</string>
    <string name="edit_chip_icon">Icon</string>
    <string name="edit_chip_up">Move Up</string>
    <string name="edit_chip_down">Move Down</string>
    <string name="edit_chip_delete">Delete</string>
    <string name="edit_new_row">New Row</string>
    <string name="edit_choice_app_row">App Row</string>
    <string name="edit_choice_app_row_desc">Apps you pick yourself. Reorder them and change the icon.</string>
    <string name="edit_choice_full">Full · %1$d rows max</string>
    <string name="edit_new_row_caption">Up to %1$d app rows</string>
```

`app/src/main/res/values-zh-rTW/strings.xml`:在 `<string name="edit_row_delete_ok">刪除</string>` 之后插入

```xml
    <!-- edit_summary_* / edit_hint_* / edit_chip_* / edit_choice_* : 編輯桌面「貨架」(R165) -->
    <plurals name="edit_summary_rows">
        <item quantity="one">%1$d 列</item>
        <item quantity="other">%1$d 列</item>
    </plurals>
    <plurals name="edit_apps_count">
        <item quantity="one">%1$d 個應用程式</item>
        <item quantity="other">%1$d 個應用程式</item>
    </plurals>
    <string name="edit_key_ok">確定</string>
    <string name="edit_key_back">返回</string>
    <string name="edit_hint_pick">拿起卡片</string>
    <string name="edit_hint_row">列操作</string>
    <string name="edit_hint_done">完成</string>
    <string name="edit_hint_choose">選擇</string>
    <string name="edit_hint_add">新增</string>
    <string name="edit_hint_move">移動</string>
    <string name="edit_hint_drop">放下</string>
    <string name="edit_hint_cancel">取消</string>
    <string name="edit_chip_icon">換圖示</string>
    <string name="edit_chip_up">上移</string>
    <string name="edit_chip_down">下移</string>
    <string name="edit_chip_delete">刪除</string>
    <string name="edit_new_row">新的一列</string>
    <string name="edit_choice_app_row">應用程式列</string>
    <string name="edit_choice_app_row_desc">放你自己挑的應用程式，可以排順序、換圖示</string>
    <string name="edit_choice_full">已滿 %1$d 列</string>
    <string name="edit_new_row_caption">應用程式列最多 %1$d 列</string>
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.CopyTest' 2>&1 | grep -E "FAILED|BUILD" | head -5`
Expected: `BUILD SUCCESSFUL`(含 `everyLanguageHasTheSameKeys`、`pluralsHaveOneAndOtherInEveryLanguage`)。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git add app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/test/java/com/uniteduone/launcher/CopyTest.kt
git commit -m "$(cat <<'EOF'
feat(edit): 货架文案三语(R165)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 货架的 Compose 零件

Compose 零件在纯 JVM 单测里跑不起来(没有 Robolectric / 设备测试);本任务的门是 **能编译 + 全部单测仍绿 + 源码扫描类测试(`TypeScaleTest`)不报写死字号**。零件的数值全部读 Task 2 的 `ShelfLayout`(那里有单测);行为在 Task 8 接线后由 Task 9 e2e 与 Task 10 模拟器验收。

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/EditShelfParts.kt`

**Interfaces:**
- Consumes: `ShelfLayout`、`ShelfChip`、`NewRowChoice`、`MoveDir`、`EditHintSet`、`shelfAlpha`、`shelfLook`(Task 1 / 2);Task 6 的字符串;`contrastingTextColor(fill: Color): Color`(ShellCapsule.kt)、`gtvAppFocusFrame(...)`(GtvFocusStroke.kt)、`PlusGlyph(color, size)`(PageChrome.kt)、`LocalThemeColors`、`Theme.AppFocusEasing`、`Theme.PendingCardBackground` / `PendingCardFocusedBackground`、`GtvLayout.FOCUS_FADE_IN_MS` / `FOCUS_FADE_OUT_MS`、`GtvTokens.SurfacePlaceholder`、`Ink`、`Type`
- Produces:
  - `@Composable internal fun ShelfFrame(focus: () -> Float, dashed: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit)`
  - `@Composable internal fun ShelfChipPill(chip: ShelfChip, visible: () -> Float, onClick: () -> Unit, onFocusChange: (Boolean) -> Unit, isFirst: Boolean, isLast: Boolean, modifier: Modifier = Modifier)`
  - `@Composable internal fun ChoiceCard(choice: NewRowChoice, full: Boolean, onClick: () -> Unit, onFocusChange: (Boolean) -> Unit, isFirst: Boolean, isLast: Boolean, modifier: Modifier = Modifier)`
  - `@Composable internal fun AddAppTile(metrics: CardMetrics, onClick: () -> Unit, onFocusChange: (Boolean) -> Unit, modifier: Modifier = Modifier)`
  - `@Composable internal fun ShelfPendingCard(pkg: String, metrics: CardMetrics, modifier: Modifier = Modifier, onFocusChange: (Boolean) -> Unit = {}, isRowStart: Boolean = false, isRowEnd: Boolean = false)`
  - `@Composable internal fun ShelfIcon(vector: ImageVector, tint: Color, boxSize: Dp, modifier: Modifier = Modifier)`
  - `@Composable internal fun EditKeyHints(set: EditHintSet, modifier: Modifier = Modifier)`
  - `internal fun Modifier.carryArrows(dirs: Set<MoveDir>, color: Color): Modifier`

- [ ] **Step 1: 写零件**

`app/src/main/java/com/uniteduone/launcher/EditShelfParts.kt`:

```kotlin
package com.uniteduone.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/*
 * 编辑桌面「货架」(R165)的界面零件。账本、按键与组装在 EditScreen.kt;数值一律读 ShelfLayout(EditShelves.kt,有单测)。
 * 所有零件:得失焦点都经 onFocusChange 上报(铁律 4);requester 与方向由调用方放在 modifier 里(在可聚焦节点之前);
 * 左右到头在零件里 Cancel,上下由编辑页根节点截获(verticalStep),零件不管。
 */

/**
 * 一层架子的外壳(spec §2.5):玻璃底 + 细边(新的一行是虚线)+ 焦点层的阴影,整层不透明度 [shelfAlpha]。[focus] 在绘制阶段读
 * (0 = 非焦点层,1 = 焦点层,调用方 200 ms 过渡),不改布局、不影响可聚焦性。
 * **整层淡化的离屏层四边各撑大 [ShelfLayout.LAYER_PAD](= 60 dp 柔光)**,外层 `layout` 按原尺寸上报(CLAUDE.md R129f):
 * 换层那 200 ms 新焦点层从 0.62 淡到 1,焦点卡的放大 + 描边 + 柔光画在架子外,不撑大会被离屏层裁掉一截。
 * 不可聚焦、不进任何焦点账本。
 */
@Composable
internal fun ShelfFrame(
    focus: () -> Float,
    dashed: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val pad = ShelfLayout.LAYER_PAD.dp
    Box(
        modifier
            .layout { measurable, constraints ->
                val e = pad.roundToPx()
                val p = measurable.measure(constraints.offset(horizontal = 2 * e, vertical = 2 * e))
                layout((p.width - 2 * e).coerceAtLeast(0), (p.height - 2 * e).coerceAtLeast(0)) { p.place(-e, -e) }
            }
            .graphicsLayer { alpha = shelfAlpha(focus()) }
            .padding(pad)
            .drawBehind { drawShelfGlass(focus(), dashed) },
        content = content,
    )
}

private fun DrawScope.drawShelfGlass(focus: Float, dashed: Boolean) {
    val look = shelfLook(focus, dashed)
    val cr = ShelfLayout.CORNER.dp.toPx()
    if (look.shadow > 0f) {
        // 阴影只画在架子外面(效果图的 box-shadow 不透过玻璃):挖掉架子本身的圆角矩形,一圈圈往外铺、越外越淡
        val hole = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(cr))) }
        clipPath(hole, ClipOp.Difference) {
            val rings = 6
            val spread = ShelfLayout.SHADOW_SPREAD.dp.toPx()
            val dy = ShelfLayout.SHADOW_DY.dp.toPx()
            for (k in 1..rings) {
                val e = spread * k / rings
                drawRoundRect(
                    color = Color.Black.copy(alpha = look.shadow / rings),
                    topLeft = Offset(-e, -e + dy),
                    size = Size(size.width + 2 * e, size.height + 2 * e),
                    cornerRadius = CornerRadius(cr + e),
                )
            }
        }
    }
    drawRoundRect(Color.White.copy(alpha = look.bg), cornerRadius = CornerRadius(cr))
    val sw = ShelfLayout.BORDER.dp.toPx()
    drawRoundRect(
        color = Color.White.copy(alpha = look.border),
        topLeft = Offset(sw / 2, sw / 2),
        size = Size(size.width - sw, size.height - sw),
        cornerRadius = CornerRadius(cr - sw / 2),
        style = Stroke(
            width = sw,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null,
        ),
    )
}

private fun chipLabel(chip: ShelfChip): Int = when (chip) {
    ShelfChip.ADD_APP -> R.string.edit_row_add_app
    ShelfChip.ICON -> R.string.edit_chip_icon
    ShelfChip.UP -> R.string.edit_chip_up
    ShelfChip.DOWN -> R.string.edit_chip_down
    ShelfChip.DELETE -> R.string.edit_chip_delete
}

private fun chipIcon(chip: ShelfChip): ImageVector = when (chip) {
    ShelfChip.ADD_APP -> Icons.Rounded.Add
    ShelfChip.ICON -> Icons.Outlined.Category
    ShelfChip.UP -> Icons.Rounded.ArrowUpward
    ShelfChip.DOWN -> Icons.Rounded.ArrowDownward
    ShelfChip.DELETE -> Icons.Outlined.Delete
}

/** 一颗矢量图标([boxSize] 见方),颜色在绘制阶段着色。 */
@Composable
internal fun ShelfIcon(vector: ImageVector, tint: Color, boxSize: Dp, modifier: Modifier = Modifier) {
    val painter = rememberVectorPainter(vector)
    Box(modifier.size(boxSize).drawBehind { with(painter) { draw(size, colorFilter = ColorFilter.tint(tint)) } })
}

/**
 * 架子顶部的一颗操作胶囊(spec §2.5):28 dp 高、14 dp 圆角、白 8% 底、12 sp;聚焦填主题 accent、文字按亮度取对比色
 * ([contrastingTextColor],与 `MenuPill` / `CompactPill` 同一套)、放大 1.08 + 一圈影子;填色 / 放大与卡片焦点同一个时长与曲线。
 * [visible] 在绘制阶段读:非焦点层的胶囊淡到 0(效果图 c1 里只有焦点层露出胶囊),但**照常布局、照常可聚焦**——
 * 从上一层卡片按下要能落到它上面,落上之后这一层变成焦点层、胶囊随之淡入。透明度与放大写在同一个 graphicsLayer 里:
 * 放大不会被一个更小的离屏层裁掉。
 */
@Composable
internal fun ShelfChipPill(
    chip: ShelfChip,
    visible: () -> Float,
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    val ms = if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS
    val fill by animateColorAsState(
        targetValue = if (focused) accent else Color.White.copy(alpha = ShelfLayout.CHIP_IDLE_ALPHA),
        animationSpec = tween(ms, easing = Theme.AppFocusEasing),
        label = "shelfChipFill",
    )
    val grow by animateFloatAsState(if (focused) 1f else 0f, tween(ms, easing = Theme.AppFocusEasing), label = "shelfChipGrow")
    val ink = if (focused) contrastingTextColor(accent) else Ink.Label
    val shape = RoundedCornerShape(ShelfLayout.CHIP_CORNER.dp)
    Row(
        modifier
            .height(ShelfLayout.CHIP_HEIGHT.dp)
            .graphicsLayer {
                val s = 1f + (ShelfLayout.CHIP_FOCUS_SCALE - 1f) * grow
                scaleX = s; scaleY = s
                alpha = visible().coerceIn(0f, 1f)
                shadowElevation = ShelfLayout.CHIP_SHADOW.dp.toPx() * grow
                this.shape = shape
            }
            .clip(shape)
            .background(fill)
            .focusProperties {
                if (isFirst) left = FocusRequester.Cancel
                if (isLast) right = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = ShelfLayout.CHIP_PAD_H.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CHIP_ICON_GAP.dp),
    ) {
        ShelfIcon(chipIcon(chip), ink, ShelfLayout.CHIP_ICON.dp)
        BasicText(
            text = stringResource(chipLabel(chip)),
            maxLines = 1,
            style = Type.caption.copy(color = ink, fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal),
        )
    }
}

private fun choiceTitle(c: NewRowChoice): Int = when (c) { NewRowChoice.APP_ROW -> R.string.edit_choice_app_row }
private fun choiceDesc(c: NewRowChoice): Int = when (c) { NewRowChoice.APP_ROW -> R.string.edit_choice_app_row_desc }
private fun choiceIcon(c: NewRowChoice): ImageVector = when (c) { NewRowChoice.APP_ROW -> Icons.Rounded.Apps }

/**
 * 「新的一行」里的一张选择卡(c2):300 × 110、圆角 16、白 7% 底;图标(主题色)+ 名字(17 sp)+ 一句说明(12 sp);
 * 聚焦填 accent、文字取对比色、放大 1.05 + 影子。[full](spec §2.4)= 整卡压到 45%、说明换成「已满 5 行」,**仍可聚焦**
 * (好让人看到原因),确定由调用方不响应。
 */
@Composable
internal fun ChoiceCard(
    choice: NewRowChoice,
    full: Boolean,
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    val ms = if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS
    val fill by animateColorAsState(
        targetValue = if (focused) accent else Color.White.copy(alpha = ShelfLayout.CHOICE_IDLE_ALPHA),
        animationSpec = tween(ms, easing = Theme.AppFocusEasing),
        label = "choiceFill",
    )
    val grow by animateFloatAsState(if (focused) 1f else 0f, tween(ms, easing = Theme.AppFocusEasing), label = "choiceGrow")
    val ink = if (focused) contrastingTextColor(accent) else Ink.Primary
    val sub = if (focused) contrastingTextColor(accent).copy(alpha = 0.75f) else Ink.Secondary
    val shape = RoundedCornerShape(ShelfLayout.CHOICE_CORNER.dp)
    Column(
        modifier
            .size(ShelfLayout.CHOICE_WIDTH.dp, ShelfLayout.CHOICE_HEIGHT.dp)
            .graphicsLayer {
                val s = 1f + (ShelfLayout.CHOICE_FOCUS_SCALE - 1f) * grow
                scaleX = s; scaleY = s
                alpha = if (full) ShelfLayout.CHOICE_FULL_ALPHA else 1f
                shadowElevation = ShelfLayout.CHOICE_SHADOW.dp.toPx() * grow
                this.shape = shape
            }
            .clip(shape)
            .background(fill)
            .focusProperties {
                if (isFirst) left = FocusRequester.Cancel
                if (isLast) right = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = ShelfLayout.CHOICE_PAD_H.dp, vertical = ShelfLayout.CHOICE_PAD_V.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ShelfIcon(choiceIcon(choice), if (focused) ink else accent, ShelfLayout.CHOICE_ICON.dp)
        BasicText(stringResource(choiceTitle(choice)), maxLines = 1, style = Type.section.copy(color = ink, fontWeight = FontWeight.Normal))
        BasicText(
            text = if (full) stringResource(R.string.edit_choice_full, MAX_ROWS) else stringResource(choiceDesc(choice)),
            maxLines = 2,
            style = Type.caption.copy(color = sub),
        )
    }
}

/**
 * 空架子在卡片位置的「添加应用」方块(spec §2.1,取代行尾「+」):卡片大小、半透明底、「＋」+ 一行字;聚焦样式同首页卡片
 * (`gtvAppFocusFrame`:放大 + 描边 + 柔光)。它是那一层卡片条里唯一的一格,左右都到头。
 */
@Composable
internal fun AddAppTile(
    metrics: CardMetrics,
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    val highlight = LocalThemeColors.current.highlight
    Column(
        modifier
            .gtvAppFocusFrame(focused, accent, metrics.cardCorner)
            .size(metrics.cardWidth, metrics.cardHeight)
            .clip(RoundedCornerShape(metrics.cardCorner))
            .background(GtvTokens.SurfacePlaceholder)
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PlusGlyph(color = highlight, size = 20.dp)
        BasicText(
            text = stringResource(R.string.edit_row_add_app),
            style = Type.caption.copy(color = if (focused) Ink.Primary else Ink.Label),
        )
    }
}

/** 数据还在加载时的中性占位(取代旧 EditScreen 的 PendingCard,多一个行尾锁)。 */
@Composable
internal fun ShelfPendingCard(
    pkg: String,
    metrics: CardMetrics,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    isRowStart: Boolean = false,
    isRowEnd: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(metrics.cardWidth, metrics.cardHeight)
            .clip(RoundedCornerShape(metrics.cardCorner))
            .background(if (focused) Theme.PendingCardFocusedBackground else Theme.PendingCardBackground)
            .focusProperties {
                if (isRowStart) left = FocusRequester.Cancel
                if (isRowEnd) right = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .focusable(interactionSource = remember { MutableInteractionSource() }),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = pkg.substringAfterLast('.'),
            style = Type.micro.copy(color = Ink.Secondary, textAlign = TextAlign.Center),
            modifier = Modifier.padding(6.dp),
        )
    }
}

@Composable
private fun Kbd(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        BasicText(text, style = Type.micro.copy(color = Ink.Primary))
    }
}

/** 页头右边的按键提示(spec §2.1;c1 / c2):每组 = 一两个键帽 + 一个词。纯展示、不可聚焦。 */
@Composable
internal fun EditKeyHints(set: EditHintSet, modifier: Modifier = Modifier) {
    val ok = stringResource(R.string.edit_key_ok)
    val back = stringResource(R.string.edit_key_back)
    val chunks: List<Pair<List<String>, String>> = when (set) {
        EditHintSet.BROWSE -> listOf(
            listOf(ok) to stringResource(R.string.edit_hint_pick),
            listOf("↑") to stringResource(R.string.edit_hint_row),
            listOf(back) to stringResource(R.string.edit_hint_done),
        )
        EditHintSet.NEW_ROW -> listOf(
            listOf("←", "→") to stringResource(R.string.edit_hint_choose),
            listOf(ok) to stringResource(R.string.edit_hint_add),
            listOf(back) to stringResource(R.string.edit_hint_done),
        )
        EditHintSet.CARRY -> listOf(
            listOf("←", "→", "↑", "↓") to stringResource(R.string.edit_hint_move),
            listOf(ok) to stringResource(R.string.edit_hint_drop),
            listOf(back) to stringResource(R.string.edit_hint_cancel),
        )
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        chunks.forEachIndexed { i, (keys, label) ->
            if (i > 0) Spacer(Modifier.width(ShelfLayout.HINT_GAP.dp))
            keys.forEach { k -> Kbd(k); Spacer(Modifier.width(4.dp)) }
            BasicText(label, style = Type.caption.copy(color = Ink.Secondary))
        }
    }
}

/**
 * 拿起时卡片四周的方向箭头(照 Google TV `gtv-04`:只画挪得动的方向,[carryArrows] 算)。画在卡片框外 [ShelfLayout.ARROW_GAP]
 * (放大溢出之外),实心小三角,主题 highlight 色。只是绘制,不改布局、不进焦点。
 */
internal fun Modifier.carryArrows(dirs: Set<MoveDir>, color: Color): Modifier = drawWithContent {
    drawContent()
    if (dirs.isEmpty()) return@drawWithContent
    val gap = ShelfLayout.ARROW_GAP.dp.toPx()
    val s = ShelfLayout.ARROW_SIZE.dp.toPx()
    val cx = size.width / 2
    val cy = size.height / 2
    fun tri(a: Offset, b: Offset, c: Offset) =
        drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }, color)
    if (MoveDir.LEFT in dirs) tri(Offset(-gap - s, cy), Offset(-gap, cy - s), Offset(-gap, cy + s))
    if (MoveDir.RIGHT in dirs) tri(Offset(size.width + gap + s, cy), Offset(size.width + gap, cy - s), Offset(size.width + gap, cy + s))
    if (MoveDir.UP in dirs) tri(Offset(cx, -gap - s), Offset(cx - s, -gap), Offset(cx + s, -gap))
    if (MoveDir.DOWN in dirs) tri(Offset(cx, size.height + gap + s), Offset(cx - s, size.height + gap), Offset(cx + s, size.height + gap))
}
```

- [ ] **Step 2: 编译 + 全部单测**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease 2>&1 | grep -E "error:|e: |FAILED|BUILD" | head -10`
Expected: `BUILD SUCCESSFUL`(新零件还没人调用,R8 会把它们裁掉;`TypeScaleTest` 照过——零件里没有 `数字.sp`)。
若报 `Unresolved reference 'Category'` / `'Delete'`:说明扩展图标包里的名字不同,换成 `Icons.Outlined.Interests` / `Icons.Rounded.Delete` 再编(`material-icons-extended` 已在依赖里)。

- [ ] **Step 3: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git add app/src/main/java/com/uniteduone/launcher/EditShelfParts.kt
git commit -m "$(cat <<'EOF'
feat(edit): 货架界面零件——架子、胶囊、选择卡、添加方块、按键提示、拿起箭头(R165)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: 重写编辑页 + 接线(账本、按键、浮层、MainActivity)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/AppPicker.kt`(从旧 EditScreen.kt 搬)
- Rewrite: `app/src/main/java/com/uniteduone/launcher/EditScreen.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(约 274–284、819–830、1189–1215、1250–1258 行)
- Modify: `app/src/main/java/com/uniteduone/launcher/LayoutOps.kt`(删 `addRowBelow`)
- Modify: `app/src/main/res/values{,-en,-zh-rTW}/strings.xml`(删旧文案)
- Test: Create `app/src/test/java/com/uniteduone/launcher/EditIronRulesTest.kt`;Modify `CopyTest.kt`、`LayoutOpsTest.kt`、`LayoutOpsBoundaryTest.kt`、`GtvMotionTest.kt`(注释);Delete `EditScrollTest.kt`

**Interfaces:**
- Consumes: Task 1–7 全部;`EditActing(row, col, pkg)` / `editActingCol(a, visible)` / `editCardShown` / `visibleRows` / `withVisibleEdits` / `dropRemovedElsewhere` / `knownAfterWrite` / `swapRows` / `deleteRow` / `setRowIcon`(LayoutOps.kt);`moveInLayout` / `MovePos` / `MoveDir`(Move.kt);`Layout.read` / `Layout.rewrite` / `layoutWrites` / `Titles.read` / `Apps.load` / `Apps.isInstalled`;`GearMenu` / `MenuItem` / `ConfirmDialog` / `RowIconPicker` / `OverlayStack` / `LocalPageGhost` / `LocalToast` / `AppCard` / `RowIcon`;`MOVE_PASSTHROUGH_KEYS`
- Produces(MainActivity 调用):

```kotlin
@Composable
fun EditScreen(
    onPickIcon: (row: Int, pkg: String) -> Boolean,
    onExit: () -> Unit,
    focusNonce: Int = 0,
    revision: Int = 0,
    wallpaperFile: String = "",
    initialTarget: Pair<Int, String>? = null,
)
```

以及 `internal fun AppPicker(nonce: Int, ctx: Context, exclude: Set<String>, onPick: (String) -> Unit, rowIcon: String? = null)`(AppPicker.kt)。删掉的参数:`cardsPerRow`(卡片固定中档)、`showTitles`(货架不画卡片标题,见文末「歧义的处理」)、`onCarryingChange`(MainActivity 不再需要知道拿起状态)。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/EditIronRulesTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R165 重写编辑页的源码防线(纯 JVM,扫 main 源码,同 TypeScaleTest 的手法):七条铁律里能静态看出来的两条、
 * 以及行菜单 / 行尾「+」确实删干净了。行为由 e2e(scripts/e2e/j_edit.py)验。
 */
class EditIronRulesTest {
    private val src = listOf(
        File("src/main/java/com/uniteduone/launcher"),
        File("app/src/main/java/com/uniteduone/launcher"),
    ).first { it.isDirectory }

    private fun code(name: String): String =
        File(src, name).readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** 铁律 1:编辑页与零件里不许有可滚动容器(唯一的例外 AppPicker 在自己的文件里)。 */
    @Test fun editPageUsesNoScrollableContainer() {
        for (f in listOf("EditScreen.kt", "EditShelfParts.kt")) {
            val hits = Regex("""\b(LazyRow|LazyColumn|verticalScroll|horizontalScroll)\b""").findAll(code(f)).map { it.value }.toList()
            assertTrue("$f 用了可滚动容器(铁律 1):$hits", hits.isEmpty())
        }
    }

    /** 铁律 1 的另一半:自算位移必须配放开测量。 */
    @Test fun offsetsComeWithUnboundedMeasuring() {
        val c = code("EditScreen.kt")
        assertTrue("纵向:wrapContentHeight(unbounded)", c.contains("wrapContentHeight(Alignment.Top, unbounded = true)"))
        assertTrue("横向:wrapContentWidth(unbounded)", c.contains("wrapContentWidth(Alignment.Start, unbounded = true)"))
    }

    /** 铁律 2:落没落下只信目标自报,不信 requestFocus() 的返回。 */
    @Test fun landingIsNeverJudgedByRequestFocusSucceeding() {
        for (f in listOf("EditScreen.kt", "EditShelfParts.kt")) {
            assertFalse(f, Regex("""requestFocus\(\)\s*\}\s*\.isSuccess""").containsMatchIn(code(f)))
        }
    }

    /**
     * owner 裁定(R165 计划复审):架子不裁焦点卡的描边 / 柔光,左边缘一律不裁(第 1 张卡是默认焦点,柔光要画到架子外);
     * 横向只裁越过架子右端的非焦点卡。防的是有人「顺手」给卡片条加回整条裁切。
     */
    @Test fun theShelfNeverClipsTheFocusedCardOrItsLeftEdge() {
        val c = code("EditScreen.kt")
        assertTrue("卡片条的裁切走 clipPastShelfEnd", c.contains("clipPastShelfEnd"))
        assertFalse("不许整块 clipToBounds", c.contains("clipToBounds"))
        assertFalse("不许从左缘 0 起裁", Regex("""clipRect\(\s*left\s*=\s*0f""").containsMatchIn(c))
    }

    /** R165:行菜单、行尾「+」、在下方新建一行都没了。 */
    @Test fun theRowMenuAndTheRowEndPlusAreGone() {
        val c = code("EditScreen.kt")
        for (gone in listOf("RowMenu", "AddCard(", "addRowBelow", "edit_row_menu_title", "editFirstRow")) {
            assertFalse("EditScreen.kt 里还有 $gone", c.contains(gone))
        }
        assertFalse("LayoutOps.kt 里还有 addRowBelow", code("LayoutOps.kt").contains("fun addRowBelow"))
    }
}
```

在 `CopyTest` 类末尾加:

```kotlin
    /** R165:行菜单与行尾「+」取消,它们的文案(以及旧的整段说明、「移动位置」说明)三种语言都删掉。 */
    @Test fun rowMenuStringsAreGone() {
        val gone = listOf(
            "edit_hint", "edit_row_add_app_desc", "edit_row_icon", "edit_row_icon_desc",
            "edit_row_up", "edit_row_up_desc", "edit_row_down", "edit_row_down_desc",
            "edit_row_new", "edit_row_new_desc", "edit_row_delete", "edit_row_delete_desc",
            "edit_row_menu_title", "edit_move_desc",
        )
        for (l in langs) {
            val s = strings(l)
            for (k in gone) assertTrue("$l 里还有 $k(R165 行菜单取消)", k !in s)
        }
    }
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.EditIronRulesTest' --tests 'com.uniteduone.launcher.CopyTest' 2>&1 | grep -E "FAILED|BUILD" | head -8`
Expected: `editPageUsesNoScrollableContainer FAILED`(旧 EditScreen.kt 里有 `LazyColumn`)、`theShelfNeverClipsTheFocusedCardOrItsLeftEdge FAILED`(旧页有 `clipToBounds`、没有 `clipPastShelfEnd`)、`theRowMenuAndTheRowEndPlusAreGone FAILED`、`rowMenuStringsAreGone FAILED`。

- [ ] **Step 3: 把「添加应用」列表搬到 `AppPicker.kt`(逐字,只改可见性)**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
python3 - <<'EOF'
import pathlib
d = pathlib.Path("app/src/main/java/com/uniteduone/launcher")
src = (d / "EditScreen.kt").read_text()
start = src.index("/**\n * 选一个应用加进某行")
chunk = src[start:].replace("private fun AppPicker(", "internal fun AppPicker(", 1)
assert "internal fun AppPicker(" in chunk and "private fun PickerRow(" in chunk and "PickerCardMetrics" in chunk
header = """package com.uniteduone.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 「添加应用」列表(R83 起每项是小卡片 + 名字)。R165 前住在 EditScreen.kt 里,货架重写时原样搬来、改成 internal;
 * 焦点账本(逐项 requester + (nonce, candidates) 初始循环 + 正反都报的 focusedItem)一字未动。
 * 这是全应用唯一获准的可滚动容器(单列 LazyColumn,见 CLAUDE.md 焦点表「添加应用列表」一行)。
 */

"""
(d / "AppPicker.kt").write_text(header + chunk)
print("AppPicker.kt written:", len(chunk), "chars")
EOF
```

- [ ] **Step 4: 重写 `EditScreen.kt`(整份替换)**

```kotlin
package com.uniteduone.launcher

import android.view.KeyEvent as AKey
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拿起(搬运)的状态(spec §2.3,沿用 M4b §0-18 的 carry):被搬的卡此刻在哪一格(同时就是焦点目标)、出发那一格(取消后回这里)、
 * 拿起那一刻的整份行(取消时原样放回)。三者总是一起变,理由同首页的 [MoveState]。拿起中 `rows` 实时跟着改、**不写盘**,放下才 persist。
 */
private data class EditCarry(val pos: MovePos, val from: MovePos, val original: List<LayoutRow>)

/**
 * 编辑页上叠着的那一层(R136 / R165,给 [OverlayStack] 当状态)。每一种带上自己要显示的东西:关掉之后残影还要画一个淡出时长,
 * 那时 `rows` 可能已经变了。[Pick.from] = 打开它的那一格(胶囊「添加应用」或空架子的方块),返回落回那里。
 * **新加的浮层并进这里就自动并进 `overlayOpen`**(铁律 6:它同时是看门狗的 key 与守卫)。
 */
private sealed interface EditOverlay {
    val layer: String
    class Card(val acting: EditActing, val title: String, val app: AppEntry?) : EditOverlay {
        override val layer get() = "card:${acting.row}:${acting.pkg}"
    }
    class Icon(val row: Int, val icon: String) : EditOverlay {
        override val layer get() = "icon:$row"
    }
    class Confirm(val row: Int, val icon: String, val apps: Int) : EditOverlay {
        override val layer get() = "confirm:$row"
    }
    class Pick(val row: Int, val icon: String, val exclude: Set<String>, val from: ShelfSpot) : EditOverlay {
        override val layer get() = "pick:$row"
    }
}

private fun dirOf(code: Int): MoveDir? = when (code) {
    AKey.KEYCODE_DPAD_LEFT -> MoveDir.LEFT
    AKey.KEYCODE_DPAD_RIGHT -> MoveDir.RIGHT
    AKey.KEYCODE_DPAD_UP -> MoveDir.UP
    AKey.KEYCODE_DPAD_DOWN -> MoveDir.DOWN
    else -> null
}

private fun isOkKey(code: Int): Boolean =
    code == AKey.KEYCODE_DPAD_CENTER || code == AKey.KEYCODE_ENTER || code == AKey.KEYCODE_NUMPAD_ENTER

/**
 * 卡片条的横向裁切(owner 裁定,R165 计划复审):**只裁越过架子右端的非焦点卡**——行多于一屏时右边露出的那一截不画到架子外。
 * 焦点卡(放大 + 描边 + 60 dp 柔光)一律不裁,柔光可以画出架子;**左边一律不裁**(第 1 张卡的柔光画到架子外,默认焦点就落在那里),
 * 滑出左边的卡只由屏幕边缘裁(同首页)。上下不裁。[rightDp] 在绘制阶段读:这张卡左缘到架子右边缘的距离(dp,随横向位移每帧变),
 * ≤ 0 时整张不画。只是绘制:不改布局、不影响可聚焦性。
 */
private fun Modifier.clipPastShelfEnd(rightDp: () -> Float): Modifier = drawWithContent {
    val r = rightDp().dp.toPx()
    if (r <= 0f) return@drawWithContent
    clipRect(left = -size.width * 4f, top = -size.height * 4f, right = r, bottom = size.height * 5f) { this@drawWithContent.drawContent() }
}

/** 页头下的渐隐遮罩色(c2:rgba(12,13,16,.97))。 */
private val EditScrim = Color(0xF70C0D10)

/**
 * **编辑桌面「货架」**(R165,spec §2)。每一行是一层玻璃架子:顶部是行图标 +「N 个应用」+ 操作胶囊(添加应用 / 换图标 / 上移 /
 * 下移 / 删除),下面是这一行的应用卡(固定中档);最后一层「新的一行」里选「应用行」。确定键在卡片上 = 拿起(左右换位、上下搬到相邻
 * 应用架子、确定放下、返回取消);长按或菜单键 = 卡片菜单(换卡片图 / 移出这一行);返回 = 完成。
 *
 * **焦点账本**(七条铁律逐条落实,见 CLAUDE.md 焦点表「编辑页」一行):
 * - 目标 [target](`ShelfSpot`)与持有者 [holder] 永远是两个量(铁律 5);`report()` 只在不重定位、没暂停、不在拿起中时让目标跟着走;
 * - `ON_PAUSE` 起 `paused` 冻结目标,`ON_RESUME` 按目标重定位;
 * - 每个可聚焦节点按 `ShelfSpot` 逐项挂 requester(`req()`,表只增不换,铁律 3 的实现约束);
 * - 显式重定位只信目标自报 `holder == want`(铁律 2);`holder == null` 看门狗守卫与 key 同一组量(铁律 6);没有布尔闩(铁律 7);
 * - 上下键由根节点 `onPreviewKeyEvent` 按 [verticalStep] 走固定顺序,不交给 Compose 的二维搜索;不用可滚动容器(铁律 1)。
 */
@Composable
fun EditScreen(
    /**
     * 「换卡片图」:(layout.json 行号, 包名)。**选择器会替换本页**(M7 终审 C1):关掉后整页重建,调用方把这两个值原样当
     * [initialTarget] 喂回来,焦点回到这张卡。返回选择器是否真的打开了(存储没就绪时打不开)。打开了的话本页不收菜单——本页随即
     * 变成淡出的残影(R138),菜单原样留在画面上,选择器在它上面淡入。
     */
    onPickIcon: (row: Int, pkg: String) -> Boolean,
    onExit: () -> Unit,
    focusNonce: Int = 0,
    revision: Int = 0,
    /** 当前壁纸的选中值(`Settings.wallpaperFile`),给模糊底用(spec §2.5)。 */
    wallpaperFile: String = "",
    /** 「换卡片图」的选择器关掉之后带进来的 (layout.json 行号, 包名);null = 正常进入,落第 1 层第 1 张卡(空架子落「添加应用」方块)。 */
    initialTarget: Pair<Int, String>? = null,
) {
    val ctx = LocalContext.current
    val showToast = LocalToast.current
    /** R138 / R147:本页也会以残影出现(打开换卡片图、退出编辑页时)。残影里不可聚焦、两个效果让路、返回键不收、不回调。 */
    val ghost = LocalPageGhost.current
    val metrics = Theme.gtvCardMetrics(GtvCardSize.MEDIUM)   // spec §2.1:固定中档,不跟「卡片大小」设置
    val accent = LocalThemeColors.current.accent
    val highlight = LocalThemeColors.current.highlight
    val density = LocalDensity.current
    val screenW = LocalConfiguration.current.screenWidthDp.toFloat()
    val hostView = LocalView.current

    val titles by produceState(emptyMap<String, String>(), revision) {
        value = withContext(Dispatchers.IO) { Titles.read(ctx) }
    }
    // 模糊底:壁纸变了才算一次(IO 线程),之后每帧只画位图(spec §2.5);上次算好的当初值
    val backdrop by produceState(cachedEditBackdrop(), wallpaperFile) {
        value = withContext(Dispatchers.IO) { runCatching { buildEditBackdrop(ctx, wallpaperFile) }.getOrNull() }
    }
    var rows by remember { mutableStateOf(Layout.read(ctx)) }
    var overlay by remember { mutableStateOf<EditOverlay?>(null) }
    val overlayOpen = overlay != null
    var carry by remember { mutableStateOf<EditCarry?>(null) }
    val needed = remember(rows) { rows.flatMap { it.apps }.toSet() }
    // 与旧编辑页同一做法:null 区分「还在加载」与「加载失败」;连同「这份数据是给哪套 needed 算的」一起存(R67,见 editCardShown)
    val loadedFor by produceState<Pair<Set<String>, Map<String, AppEntry>>?>(initialValue = null, needed, revision) {
        value = needed to withContext(Dispatchers.IO) {
            // 必须兜底:produceState 里抛出会终结 Recomposer,应用当场崩溃
            runCatching { Apps.load(ctx, needed, withBitmaps = needed, withLabels = needed) }.getOrDefault(emptyMap())
        }
    }
    val all = loadedFor?.second
    fun shownNow(): (String) -> Boolean {
        val lf = loadedFor
        val found = lf?.second?.keys.orEmpty()
        return { pkg -> editCardShown(pkg, lf?.first, found) }
    }
    /** 看得见的那份(R67);回调里一律现调,不用组合期的 viewRows(拿起的方向键可能在两次组合之间连着来)。 */
    fun view(): List<LayoutRow> = visibleRows(rows, shownNow())
    fun shelvesNow(): List<Shelf> = shelvesOf(view())
    fun applyView(edited: List<LayoutRow>, base: List<LayoutRow> = rows) {
        rows = withVisibleEdits(base, edited, shownNow())
    }
    val viewRows = view()
    val shelves = shelvesOf(viewRows)

    // ---------------- 焦点账本 ----------------
    /** 目标:回来落哪一格。三个分量在一个不可变值里一起写,与 [holder] 永远是两个量(铁律 5)。 */
    var target by remember { mutableStateOf(ShelfSpot(0, ShelfZone.CARDS, 0)) }
    /** 此刻持有焦点的那一格,只信控件自报(铁律 2 / 4);null = 本页没有焦点。 */
    var holder by remember { mutableStateOf<ShelfSpot?>(null) }
    // 「安排了几次 / 完成了几次」的比对(铁律 7)。tick 从 1 起:一进页就处在一次待办的重定位里,目标 = 初始格,从第一帧起冻结
    var retargetTick by remember { mutableStateOf(1) }
    var retargetDone by remember { mutableStateOf(0) }
    val retargeting = retargetTick != retargetDone
    /** ON_PAUSE 起为真,ON_RESUME 清(spec §2.2「ON_PAUSE 起冻结」):期间焦点上报改不动目标,看门狗让路。 */
    var paused by remember { mutableStateOf(false) }
    // 逐项 requester,按格子身份取,表只增不换:不会因为行数 / 胶囊数变了整表换新、协程里捏着旧表(铁律 3 的实现约束)
    val requesters = remember { HashMap<ShelfSpot, FocusRequester>() }
    fun req(s: ShelfSpot): FocusRequester = requesters.getOrPut(s) { FocusRequester() }
    /** 每一格此刻的水平中心(px,根坐标),给 [verticalStep] 取最近;只在按键回调里读,不是 Compose 状态。 */
    val centers = remember { HashMap<ShelfSpot, Float>() }
    /** 每层卡片条**当前**聚焦到第几张(只算横向位移,跟着真实焦点走;不是目标)。 */
    val shelfCol = remember { mutableStateMapOf<Int, Int>() }

    /** 安排一次重定位:冻结(tick++)与写目标在同一个同步动作里(旧编辑页 2026-09-11 的实测教训)。所有动作都走这里。 */
    fun retarget(s: ShelfSpot) {
        retargetTick++
        target = s
    }
    fun report(s: ShelfSpot, got: Boolean) {
        if (got) {
            holder = s
            if (s.zone == ShelfZone.CARDS) shelfCol[s.shelf] = s.index
            // 读的是活的状态(不是组合期的 val):重定位 / 暂停 / 拿起中,Compose 抢先给出的焦点事件改不动目标
            if (retargetTick == retargetDone && !paused && carry == null) target = s
        } else if (holder == s) holder = null
    }

    // ---------------- 写盘 ----------------
    val scope = rememberCoroutineScope()
    val knownOnDisk = remember { java.util.concurrent.atomic.AtomicReference(rows.flatMapTo(HashSet()) { it.apps }.toSet()) }
    fun persist() {
        val snapshot = rows
        scope.launch {
            // layoutWrites 串行:较早的快照不会后落盘;rewrite 整段在 Layout 的锁里(落盘铁律)
            val ok = withContext(layoutWrites) {
                var written: List<LayoutRow>? = null
                val landed = Layout.rewrite(ctx) { disk ->
                    dropRemovedElsewhere(snapshot, disk, knownOnDisk.get()) { Apps.isInstalled(ctx, it) }.also { written = it }
                }
                if (landed) written?.let { w -> knownOnDisk.set(knownAfterWrite(knownOnDisk.get(), w)) }
                landed
            }
            if (!ok) showToast(ctx.getString(R.string.edit_toast_order_not_saved), true)
        }
    }

    // ---------------- 拿起 ----------------
    fun startCarry(row: Int, col: Int) {
        val at = MovePos(row, col)
        carry = EditCarry(pos = at, from = at, original = rows)
        retarget(ShelfSpot(row, ShelfZone.CARDS, col))
    }
    /** 搬一步:在看得见的那份上搬,以拿起那一刻的整份为底合回(看不见的包始终按出发时的下标摆,R67)。上下到头 / 相邻行已有它:不动。 */
    fun stepCarry(dir: MoveDir) {
        val c = carry ?: return
        val v = view()
        val (next, pos) = moveInLayout(v, c.pos, dir)
        if (next === v) return
        applyView(next, base = c.original)
        carry = c.copy(pos = pos)
        retarget(ShelfSpot(pos.row, ShelfZone.CARDS, pos.col))
    }
    /** 放下:没动过不写盘;显式再重定位一次(被搬的应用途中被卸载时,节点会被摘掉,账本冻结期间没人上报)。 */
    fun dropCarry() {
        val c = carry ?: return
        carry = null
        if (rows != c.original) persist()
        retarget(ShelfSpot(c.pos.row, ShelfZone.CARDS, c.pos.col))
    }
    /** 取消:整份放回、不写盘、焦点回出发格。入口:返回键、ON_PAUSE、任何浮层要打开。 */
    fun cancelCarry() {
        val c = carry ?: return
        carry = null
        rows = c.original
        retarget(ShelfSpot(c.from.row, ShelfZone.CARDS, c.from.col))
    }

    // ---------------- 动作 ----------------
    fun cardSpot(a: EditActing): ShelfSpot = landingOnCard(shelvesNow(), a.row, editActingCol(a, view()) ?: a.col)
    fun returnSpot(o: EditOverlay): ShelfSpot = when (o) {
        is EditOverlay.Card -> cardSpot(o.acting)
        is EditOverlay.Icon -> landingOnChip(shelvesNow(), o.row, ShelfChip.ICON)
        is EditOverlay.Confirm -> landingOnChip(shelvesNow(), o.row, ShelfChip.DELETE)
        is EditOverlay.Pick -> clampSpot(shelvesNow(), o.from)
    }
    fun closeOverlay(o: EditOverlay) {
        if (overlay !== o) return
        overlay = null
        retarget(returnSpot(o))
    }
    fun openCardMenu(row: Int, col: Int) {
        val pkg = view().getOrNull(row)?.apps?.getOrNull(col) ?: return
        overlay = EditOverlay.Card(EditActing(row, col, pkg), titles[pkg] ?: all?.get(pkg)?.label ?: pkg, all?.get(pkg))
    }
    fun openPicker(row: Int, from: ShelfSpot) {
        val r = rows.getOrNull(row) ?: return
        overlay = EditOverlay.Pick(row, r.icon, rows.flatMap { it.apps }.toSet(), from)
    }
    /** 两层对调时,两层的「当前横向位置」跟着层走(不然横向位移会套到别的层上)。 */
    fun swapCols(a: Int, b: Int) {
        val x = shelfCol[a]; val y = shelfCol[b]
        if (y != null) shelfCol[a] = y else shelfCol.remove(a)
        if (x != null) shelfCol[b] = x else shelfCol.remove(b)
    }
    fun dropColAfterDelete(ri: Int) {
        val old = shelfCol.toMap()
        shelfCol.clear()
        old.forEach { (k, v) -> if (k < ri) shelfCol[k] = v else if (k > ri) shelfCol[k - 1] = v }
    }
    /** 删第 [ri] 层:落上一层第一颗胶囊(删的是第一层落新的第一层)。纯函数挡住了(只剩一行 / 越界)→ 回「删除」胶囊。 */
    fun deleteRowAt(ri: Int) {
        val before = rows
        rows = deleteRow(rows, ri)
        if (rows === before) { retarget(landingOnChip(shelvesNow(), ri, ShelfChip.DELETE)); return }
        dropColAfterDelete(ri)
        persist()
        retarget(landingAfterDelete(shelvesNow(), ri))
    }
    fun onChip(si: Int, chip: ShelfChip) {
        if (carry != null || ghost || overlay != null) return
        val r = rows.getOrNull(si) ?: return
        val here = landingOnChip(shelvesNow(), si, chip)
        when (chip) {
            ShelfChip.ADD_APP -> openPicker(si, here)
            ShelfChip.ICON -> overlay = EditOverlay.Icon(si, r.icon)
            ShelfChip.UP, ShelfChip.DOWN -> {
                val to = if (chip == ShelfChip.UP) si - 1 else si + 1
                val next = swapRows(rows, si, to)
                if (next === rows) return
                rows = next
                swapCols(si, to)
                persist()
                retarget(landingAfterSwap(shelvesNow(), to, chip))
            }
            ShelfChip.DELETE -> {
                // 按看得见的算(R67):只剩看不见的包(被停用的)的行,在用户眼里就是空行 → 直接删
                val apps = view().getOrNull(si)?.apps.orEmpty()
                if (apps.isEmpty()) deleteRowAt(si) else overlay = EditOverlay.Confirm(si, r.icon, apps.size)
            }
        }
    }
    fun onChoice(choice: NewRowChoice) {
        if (carry != null || ghost || overlay != null) return
        when (choice) {
            NewRowChoice.APP_ROW -> {
                val next = appendAppRow(rows)
                if (next === rows) return   // 已满 5 行:确定不响应(spec §2.4)
                rows = next
                persist()
                retarget(landingAfterAppend(shelvesNow()))
            }
        }
    }

    // ---------------- 进页种子 / 生命周期 ----------------
    // 带着 (layout 行号, 包名) 进来(换卡片图的选择器关掉、本页重建):数据到位后定位一次。按「已应用的目标」比对,不用闩(铁律 7)
    var appliedTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(initialTarget, all) {
        val t = initialTarget ?: return@LaunchedEffect
        if (all == null || appliedTarget == t) return@LaunchedEffect
        appliedTarget = t
        val ci = view().getOrNull(t.first)?.apps?.indexOf(t.second) ?: -1
        retarget(landingOnCard(shelvesNow(), t.first, ci.coerceAtLeast(0)))
    }
    // ON_PAUSE:拿起取消 + 冻结目标;ON_RESUME:解冻并按目标重定位(从系统设置侧板 / 别的应用回来时 Compose 会抢先给第一张卡)。
    // 经 rememberUpdatedState 调:观察者只建一次,直接捕获的话读到的是第一次组合的函数
    val pauseNow by rememberUpdatedState { cancelCarry(); paused = true }
    val resumeNow by rememberUpdatedState { paused = false; retarget(target) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> pauseNow()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> resumeNow()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // ---------------- 显式重定位(与看门狗分开:看门狗第一行是「已有焦点就不管」,会吞掉明确的请求)----------------
    LaunchedEffect(retargetTick, ghost) {
        if (ghost) return@LaunchedEffect
        val wanted = target
        // 目标格若是注定被整格替换的加载占位,先等它换完(旧编辑页 §0-16 的根因:焦点落在占位上,数据一到占位被真卡替换,
        // 焦点随旧节点消失、Compose 从左上角往下找)。只等目标格自己;加载失败也会对上,不会卡住
        snapshotFlow {
            val sh = shelvesOf(view())
            val s = clampSpot(sh, wanted)
            val pkg = (sh.getOrNull(s.shelf) as? Shelf.AppShelf)?.takeIf { s.zone == ShelfZone.CARDS }?.apps?.getOrNull(s.index)
            pkg == null || loadedFor?.first == rows.flatMap { it.apps }.toSet() || loadedFor?.second?.containsKey(pkg) == true
        }.first { it }
        val want = clampSpot(shelvesOf(view()), wanted)
        target = want
        var frames = 0
        while (frames < 60 && holder != want) {   // 退出判据:目标自报(铁律 2),不信 requestFocus() 的返回
            withFrameNanos { }
            runCatching { req(want).requestFocus() }
            frames++
        }
        retargetDone = retargetTick
    }

    // ---------------- 看门狗(铁律 3 / 6):守卫里的每个量都在 key 里 ----------------
    val noHolder = holder == null
    LaunchedEffect(focusNonce, all, rows, noHolder, overlayOpen, retargeting, paused, ghost) {
        if (ghost || retargeting || overlayOpen || paused || !noHolder) return@LaunchedEffect
        if (all == null && rows.any { it.apps.isNotEmpty() }) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        if (holder != null) return@LaunchedEffect
        var frames = 0
        while (holder == null && frames < 60) {
            withFrameNanos { }
            runCatching { req(clampSpot(shelvesOf(view()), target)).requestFocus() }
            frames++
        }
    }

    // 任何浮层要打开 = 拿起取消(结构上的兜底;按键路径上开不出浮层)。守卫的两个量都是 key(铁律 6)
    val carrying = carry != null
    LaunchedEffect(overlayOpen, carrying) {
        if (overlayOpen && carrying) cancelCarry()
    }
    // 浮层指向的东西没了(卡片被卸载、行被删):收掉,看门狗随 overlayOpen 翻回 false 接回焦点。组合期只负责不渲染
    val staleOverlay = when (val o = overlay) {
        is EditOverlay.Card -> editActingCol(o.acting, viewRows) == null
        is EditOverlay.Icon -> o.row !in rows.indices
        is EditOverlay.Confirm -> o.row !in rows.indices
        is EditOverlay.Pick -> o.row !in rows.indices
        null -> false
    }
    LaunchedEffect(staleOverlay) { if (staleOverlay) overlay = null }

    // ---------------- 返回键(走 OnBackPressedDispatcher:预测式返回下 BACK 不作为按键事件下发)----------------
    androidx.activity.compose.BackHandler(enabled = !ghost) {
        val o = overlay
        when {
            o != null -> closeOverlay(o)
            carry != null -> cancelCarry()
            else -> onExit()
        }
    }
    // 拿起中的返回 = 取消。只在拿起中存在,组合得更晚 → 先接管
    if (carry != null && !ghost) androidx.activity.compose.BackHandler { cancelCarry() }

    // ---------------- 按键截获(根节点 onPreviewKeyEvent:焦点在本页任何一格、包括浮层里时,先到这里)----------------
    val press = remember { OkPress() }
    fun cardAt(s: ShelfSpot): String? =
        (shelvesNow().getOrNull(s.shelf) as? Shelf.AppShelf)?.takeIf { s.zone == ShelfZone.CARDS }?.apps?.getOrNull(s.index)
    fun onEditKey(e: AKey): Boolean {
        if (ghost) return false
        val code = e.keyCode
        val ok = isOkKey(code)
        // 我们按下时接手的那一下:UP 一律吞掉,不论中间开了什么浮层(Review Focus 1)
        if (ok && e.action == AKey.ACTION_UP && press.owns(e.downTime)) {
            if (press.onUp(e.downTime, e.isCanceled) == OkOutcome.SHORT_PRESS) {
                if (carry != null) dropCarry()
                else holder?.let { h -> if (overlay == null && cardAt(h) != null) startCarry(h.shelf, h.index) }
            }
            return true
        }
        val o = overlay
        if (o != null) {
            if (code == AKey.KEYCODE_MENU) {
                // 卡片菜单开着时菜单键 = 收掉它(同所有应用页);别的浮层上什么都不做
                if (e.action == AKey.ACTION_DOWN && e.repeatCount == 0 && o is EditOverlay.Card) closeOverlay(o)
                return true
            }
            // 确定键的重复事件一律吞掉:长按开出菜单之后那一下还在重复(别让菜单胶囊连点);浮层里按住确定也不连点——
            // MainActivity 在编辑页里把确定键整下原样交过来(Step 5 (d)),它原来那条「重复事件全吞」对编辑页不再生效,由这里补上
            if (ok && e.action == AKey.ACTION_DOWN && e.repeatCount > 0) return true
            return false
        }
        if (carry != null) {
            if (code == AKey.KEYCODE_BACK || code in MOVE_PASSTHROUGH_KEYS) return false
            if (e.action == AKey.ACTION_DOWN) {
                if (ok) press.onDown(e.downTime, e.eventTime, e.repeatCount)
                else dirOf(code)?.let { stepCarry(it) }
            }
            return true   // 拿起中其余键一律吞掉(含菜单键)
        }
        val h = holder
        if (code == AKey.KEYCODE_DPAD_UP || code == AKey.KEYCODE_DPAD_DOWN) {
            if (h == null) return false   // 本页没焦点:交给 Compose 默认搜索,随后由上报 / 看门狗接管
            if (e.action == AKey.ACTION_DOWN) {
                val next = verticalStep(shelvesNow(), h, down = code == AKey.KEYCODE_DPAD_DOWN) { centers[it] }
                if (next != null) runCatching { req(next).requestFocus() }
            }
            return true   // 到头也吞掉 = Cancel
        }
        if (code == AKey.KEYCODE_MENU) {
            if (e.action == AKey.ACTION_DOWN && e.repeatCount == 0 && h != null && cardAt(h) != null) {
                hostView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                openCardMenu(h.shelf, h.index)
            }
            return true   // 焦点不在卡片上:菜单键什么都不做(spec §2.3)
        }
        if (ok && h != null && cardAt(h) != null) {
            if (e.action == AKey.ACTION_DOWN && press.onDown(e.downTime, e.eventTime, e.repeatCount) == OkOutcome.LONG_PRESS) {
                hostView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                openCardMenu(h.shelf, h.index)
            }
            return true   // 卡片上的确定键整下归这里:tv-material 的卡在 UP 时直接 onClick,不能放过去
        }
        // 胶囊 / 选择卡 / 「添加应用」方块:交给它们的 clickable;按住的重复事件吞掉,不连点
        return ok && e.action == AKey.ACTION_DOWN && e.repeatCount > 0
    }

    // ---------------- 纵向位移(焦点线,spec §2.1)----------------
    var viewportPx by remember { mutableStateOf(0) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    val activeShelf = target.shelf.coerceIn(0, shelves.lastIndex)
    val shiftPx = with(density) {
        shelfScroll(
            heights = shelves.indices.map { heights[it] ?: 0 },
            gap = ShelfLayout.GAP.dp.roundToPx(),
            focused = activeShelf,
            top = ShelfLayout.TOP.dp.roundToPx(),
            focusLine = ShelfLayout.FOCUS_LINE.dp.roundToPx(),
            viewport = viewportPx,
            bottomPad = ShelfLayout.BOTTOM_PAD.dp.roundToPx(),
        )
    }
    // spec §2.5:换层时整页位移与架子亮度同走 200 ms FastOutSlowIn(R29 的弹簧只留给行内横向位移)
    val shift = animateDpAsState(
        targetValue = with(density) { shiftPx.toDp() },
        animationSpec = tween(ShelfLayout.SHIFT_MS, easing = FastOutSlowInEasing),
        label = "shelfShift",
    )
    val arrows = carry?.let { carryArrows(viewRows, it.pos) } ?: emptySet()

    Box(
        Modifier
            .fillMaxSize()
            .editBackdrop(backdrop)
            .onSizeChanged { viewportPx = it.height }
            .onPreviewKeyEvent { onEditKey(it.nativeKeyEvent) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // **必须 unbounded**:不放开测量,超出视窗的层会被压扁,offset 发生在测量之后救不回来(铁律 1)
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .offset { IntOffset(0, (ShelfLayout.TOP.dp - shift.value).roundToPx()) }
                .focusProperties { canFocus = !overlayOpen && !ghost }
                .padding(horizontal = ShelfLayout.SIDE.dp),
            verticalArrangement = Arrangement.spacedBy(ShelfLayout.GAP.dp),
        ) {
            shelves.forEachIndexed { si, shelf ->
                key(si) {
                    val focusAnim = animateFloatAsState(
                        targetValue = if (si == activeShelf) 1f else 0f,
                        animationSpec = tween(ShelfLayout.SHIFT_MS, easing = FastOutSlowInEasing),
                        label = "shelfFocus",
                    )
                    val measure = Modifier.onSizeChanged { heights[si] = it.height }
                    val place: (ShelfSpot, Float) -> Unit = { s, x -> centers[s] = x }
                    when (shelf) {
                        is Shelf.AppShelf -> AppShelfView(
                            shelf = shelf,
                            chips = shelfChips(shelves, si),
                            focus = { focusAnim.value },
                            active = si == activeShelf,
                            all = all,
                            metrics = metrics,
                            col = shelfCol[si] ?: 0,
                            carriedCol = carry?.pos?.takeIf { it.row == si }?.col,
                            arrows = arrows,
                            arrowColor = highlight,
                            accent = accent,
                            screenW = screenW,
                            req = ::req,
                            report = ::report,
                            place = place,
                            onChip = { onChip(si, it) },
                            onAddTile = { if (carry == null && overlay == null) openPicker(si, ShelfSpot(si, ShelfZone.CARDS, 0)) },
                            // 卡片的 onClick 只可能来自指针 / 无障碍(确定键在根上就被截走):给卡片菜单
                            onCardClick = { ci -> if (carry == null && overlay == null) openCardMenu(si, ci) },
                            modifier = measure,
                        )
                        Shelf.NewRowShelf -> NewRowShelfView(
                            shelfIndex = si,
                            shelves = shelves,
                            focus = { focusAnim.value },
                            active = si == activeShelf,
                            accent = accent,
                            req = ::req,
                            report = ::report,
                            place = place,
                            onChoice = ::onChoice,
                            modifier = measure,
                        )
                    }
                }
            }
        }

        EditTopBar(counts = editCounts(viewRows), hints = editHintSet(holder?.zone ?: target.zone, carry != null))

        // **R136:编辑页的浮层是一摞,淡入淡出**(OverlayStack)。残影画关掉前那一份,回调只有活着的那一层会调到(`overlay === ov` 判一次)
        OverlayStack(state = overlay?.takeIf { !staleOverlay }, layerKey = { it.layer }) { ov ->
            when (ov) {
                is EditOverlay.Card -> {
                    val a = ov.acting
                    GearMenu(
                        items = listOf(
                            MenuItem(stringResource(R.string.edit_change_image), stringResource(R.string.edit_change_image_desc)) {
                                if (overlay !== ov) return@MenuItem
                                // 打开了:本页随即被选择器替换(残影淡出),回来时由调用方把 (行, 包名) 喂回来;没打开:收菜单、回这张卡
                                if (!onPickIcon(a.row, a.pkg)) closeOverlay(ov)
                            },
                            MenuItem(stringResource(R.string.edit_remove), stringResource(R.string.edit_remove_desc)) {
                                if (overlay !== ov) return@MenuItem
                                val col = editActingCol(a, view()) ?: a.col
                                // 按包名移出(R67:列号是看得见的那份里的,不是 layout.json 下标)
                                rows = rows.mapIndexed { i, r -> if (i == a.row) r.copy(apps = r.apps - a.pkg) else r }
                                persist()
                                overlay = null
                                retarget(landingOnCard(shelvesNow(), a.row, col - 1))
                            },
                        ),
                        onDismiss = { closeOverlay(ov) },
                        nonce = focusNonce,
                        title = ov.title,
                        app = ov.app,
                    )
                }
                is EditOverlay.Icon -> RowIconPicker(
                    current = ov.icon,
                    nonce = focusNonce,
                    onPick = { id ->
                        if (overlay === ov) {
                            overlay = null
                            rows = setRowIcon(rows, ov.row, id)
                            persist()
                            retarget(landingOnChip(shelvesNow(), ov.row, ShelfChip.ICON))
                        }
                    },
                    onDismiss = { closeOverlay(ov) },
                )
                is EditOverlay.Confirm -> ConfirmDialog(
                    title = stringResource(R.string.edit_row_delete_confirm_title),
                    body = pluralStringResource(R.plurals.edit_row_delete_confirm_body, ov.apps, ov.apps),
                    okLabel = stringResource(R.string.edit_row_delete_ok),
                    cancelLabel = stringResource(R.string.dialog_cancel),
                    nonce = focusNonce,
                    icon = ov.icon,
                    onOk = { if (overlay === ov) { overlay = null; deleteRowAt(ov.row) } },
                    onCancel = { closeOverlay(ov) },
                )
                is EditOverlay.Pick -> AppPicker(
                    nonce = focusNonce,
                    ctx = ctx,
                    rowIcon = ov.icon,
                    exclude = ov.exclude,
                    onPick = { pkg ->
                        if (overlay === ov) {
                            rows = rows.mapIndexed { i, r -> if (i == ov.row && pkg !in r.apps) r.copy(apps = r.apps + pkg) else r }
                            persist()
                            overlay = null
                            // 刚加进来的那张(还没查过,画成占位)就是新卡;重定位效果会等它换成真卡再落
                            retarget(landingOnCard(shelvesNow(), ov.row, view().getOrNull(ov.row)?.apps?.indexOf(pkg) ?: 0))
                        }
                    },
                    // AppPicker 没有自己的 BackHandler:返回走上面那个,落回打开它的那一格(ov.from)
                )
            }
        }
    }
}

/** 页头(spec §2.1):左「编辑桌面」+ 概况,右按键提示;下面一条渐隐遮罩盖住滚上来的架子(c2)。纯展示、不可聚焦。 */
@Composable
private fun EditTopBar(counts: EditCounts, hints: EditHintSet) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(ShelfLayout.SCRIM_HEIGHT.dp)
            .background(Brush.verticalGradient(0f to EditScrim, 0.55f to EditScrim, 1f to EditScrim.copy(alpha = 0f))),
    )
    Row(
        Modifier.fillMaxWidth().padding(start = ShelfLayout.HEADER_LEFT.dp, end = ShelfLayout.HEADER_LEFT.dp, top = ShelfLayout.HEADER_Y.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(stringResource(R.string.edit_title), style = Type.headline)
        Spacer(Modifier.width(14.dp))
        val rowsText = pluralStringResource(R.plurals.edit_summary_rows, counts.rows, counts.rows)
        val appsText = pluralStringResource(R.plurals.edit_apps_count, counts.apps, counts.apps)
        BasicText("$rowsText · $appsText", style = Type.caption)
        Spacer(Modifier.weight(1f))
        EditKeyHints(hints)
    }
}

/**
 * 一层应用架子(spec §2.1):顶行(行图标 + 「N 个应用」+ 胶囊)、卡片行(固定中档;空架子 = 「添加应用」方块)。
 * 每个可聚焦节点:逐项 requester、得失都上报、量水平中心。卡片行横向位移 = 首页同一个 [GtvLayout.rowShiftX]
 * (卡片起点就在 CONTENT_KEYLINE 上,单测钉过),配 `wrapContentWidth(unbounded)`(铁律 1)。横向只裁越过架子右端的非焦点卡
 * ([clipPastShelfEnd],owner 裁定:焦点卡与左边缘一律不裁);当前那张([col])`zIndex` 1,放大 + 柔光盖在邻卡上面(同首页)——
 * 每张卡外面包了一层 Box(抬起阴影 / 箭头 / 裁切),AppCard 自己的 zIndex 只在它那层 Box 里生效,管不到邻卡。
 */
@Composable
private fun AppShelfView(
    shelf: Shelf.AppShelf,
    chips: List<ShelfChip>,
    focus: () -> Float,
    active: Boolean,
    all: Map<String, AppEntry>?,
    metrics: CardMetrics,
    col: Int,
    carriedCol: Int?,
    arrows: Set<MoveDir>,
    arrowColor: Color,
    accent: Color,
    screenW: Float,
    req: (ShelfSpot) -> FocusRequester,
    report: (ShelfSpot, Boolean) -> Unit,
    place: (ShelfSpot, Float) -> Unit,
    onChip: (ShelfChip) -> Unit,
    onAddTile: () -> Unit,
    onCardClick: (Int) -> Unit,
    modifier: Modifier,
) {
    val si = shelf.row
    ShelfFrame(focus = focus, dashed = false, modifier = modifier.fillMaxWidth().height(ShelfLayout.APP_SHELF_HEIGHT.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = ShelfLayout.PAD_START.dp, end = ShelfLayout.PAD_END.dp, top = ShelfLayout.HEADER_TOP.dp)
                .height(ShelfLayout.HEADER_HEIGHT.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(shelf.icon, tint = { if (active) accent else Ink.Secondary }, boxSize = ShelfLayout.ICON.dp)
            Spacer(Modifier.width(ShelfLayout.ICON_GAP.dp))
            BasicText(
                pluralStringResource(R.plurals.edit_apps_count, shelf.apps.size, shelf.apps.size),
                style = Type.body.copy(color = Ink.Label),
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CHIP_GAP.dp), verticalAlignment = Alignment.CenterVertically) {
                chips.forEachIndexed { ci, chip ->
                    val spot = ShelfSpot(si, ShelfZone.CHIPS, ci)
                    ShelfChipPill(
                        chip = chip,
                        visible = focus,
                        onClick = { onChip(chip) },
                        onFocusChange = { report(spot, it) },
                        isFirst = ci == 0,
                        isLast = ci == chips.lastIndex,
                        modifier = Modifier.focusRequester(req(spot)).onGloballyPositioned { place(spot, it.boundsInRoot().center.x) },
                    )
                }
            }
        }
        // 当前那张夹到这一层的卡片数:卸载 / 移出让行变短时,不按过期的列号多滑一截
        val cur = col.coerceIn(0, (shelf.apps.size - 1).coerceAtLeast(0))
        val dx = animateDpAsState(
            targetValue = GtvLayout.rowShiftX(cur, GtvCardSize.MEDIUM, screenW).dp,
            animationSpec = Theme.browseShiftSpec(),
            label = "shelfRowX",
        )
        // 架子内宽(dp):Column 左右各留 SIDE;第 ci 张卡的左缘 = PAD_START + ci × (卡宽 + 间距) + dx
        val shelfW = screenW - 2 * ShelfLayout.SIDE
        val pitch = metrics.cardWidth.value + ShelfLayout.CARD_GAP
        Box(Modifier.fillMaxWidth().padding(top = ShelfLayout.CARDS_TOP.dp)) {
            Row(
                Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset { IntOffset(dx.value.roundToPx(), 0) }
                    .padding(start = ShelfLayout.PAD_START.dp),
                horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CARD_GAP.dp),
            ) {
                if (shelf.apps.isEmpty()) {
                    val spot = ShelfSpot(si, ShelfZone.CARDS, 0)
                    AddAppTile(
                        metrics = metrics,
                        onClick = onAddTile,
                        onFocusChange = { report(spot, it) },
                        modifier = Modifier.focusRequester(req(spot)).onGloballyPositioned { place(spot, it.boundsInRoot().center.x) },
                    )
                } else shelf.apps.forEachIndexed { ci, pkg ->
                    val spot = ShelfSpot(si, ShelfZone.CARDS, ci)
                    val carried = carriedCol == ci
                    val lift = animateFloatAsState(
                        targetValue = if (carried) 1f else 0f,
                        animationSpec = tween(ShelfLayout.LIFT_MS, easing = FastOutSlowInEasing),
                        label = "carryLift",
                    )
                    val cell = Modifier.focusRequester(req(spot)).onGloballyPositioned { place(spot, it.boundsInRoot().center.x) }
                    Box(
                        Modifier
                            .zIndex(if (ci == cur) 1f else 0f)
                            // rowShiftX 保证当前那张(焦点卡 / 被拿起的卡)完整落在架子里:它不裁,柔光照常画出架子(owner 裁定)
                            .then(
                                if (ci == cur) Modifier
                                else Modifier.clipPastShelfEnd { shelfW - (ShelfLayout.PAD_START + ci * pitch + dx.value.value) },
                            )
                            .graphicsLayer {
                                shadowElevation = lift.value * ShelfLayout.CARRY_SHADOW.dp.toPx()
                                shape = RoundedCornerShape(metrics.cardCorner)
                            }
                            .then(if (carried) Modifier.carryArrows(arrows, arrowColor) else Modifier),
                    ) {
                        val app = all?.get(pkg)
                        if (app != null) {
                            AppCard(
                                app = app,
                                onClick = { onCardClick(ci) },
                                metrics = metrics,
                                modifier = cell,
                                onFocusChange = { report(spot, it) },
                                isRowStart = ci == 0,
                                isRowEnd = ci == shelf.apps.lastIndex,
                                fallbackColor = app.fallbackColor?.let { Color(it) },
                                moving = carried,
                            )
                        } else {
                            ShelfPendingCard(
                                pkg = pkg,
                                metrics = metrics,
                                modifier = cell,
                                onFocusChange = { report(spot, it) },
                                isRowStart = ci == 0,
                                isRowEnd = ci == shelf.apps.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「新的一行」(spec §2.4,c2):虚线架子 + 选择卡,下面一行说明。 */
@Composable
private fun NewRowShelfView(
    shelfIndex: Int,
    shelves: List<Shelf>,
    focus: () -> Float,
    active: Boolean,
    accent: Color,
    req: (ShelfSpot) -> FocusRequester,
    report: (ShelfSpot, Boolean) -> Unit,
    place: (ShelfSpot, Float) -> Unit,
    onChoice: (NewRowChoice) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        ShelfFrame(focus = focus, dashed = true, modifier = Modifier.fillMaxWidth().height(ShelfLayout.NEW_ROW_HEIGHT.dp)) {
            Row(
                Modifier.padding(start = ShelfLayout.PAD_START.dp, top = ShelfLayout.HEADER_TOP.dp).height(ShelfLayout.HEADER_HEIGHT.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShelfIcon(Icons.Rounded.Add, if (active) accent else Ink.Secondary, ShelfLayout.ICON.dp)
                Spacer(Modifier.width(ShelfLayout.ICON_GAP.dp))
                BasicText(stringResource(R.string.edit_new_row), style = Type.body.copy(color = Ink.Label))
            }
            Row(
                Modifier.padding(start = ShelfLayout.PAD_START.dp, top = ShelfLayout.CHOICE_TOP.dp),
                horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CHOICE_GAP.dp),
            ) {
                val choices = NewRowChoice.entries
                choices.forEachIndexed { i, c ->
                    val spot = ShelfSpot(shelfIndex, ShelfZone.NEW, i)
                    ChoiceCard(
                        choice = c,
                        full = choiceFull(shelves, c),
                        onClick = { onChoice(c) },
                        onFocusChange = { report(spot, it) },
                        isFirst = i == 0,
                        isLast = i == choices.lastIndex,
                        modifier = Modifier.focusRequester(req(spot)).onGloballyPositioned { place(spot, it.boundsInRoot().center.x) },
                    )
                }
            }
        }
        BasicText(
            stringResource(R.string.edit_new_row_caption, MAX_ROWS),
            style = Type.caption,
            modifier = Modifier.padding(start = ShelfLayout.PAD_START.dp, top = ShelfLayout.NEW_ROW_CAPTION_GAP.dp),
        )
    }
}
```

- [ ] **Step 5: MainActivity 接线**

(a)删掉 `editCarrying` 整段(KDoc 起「编辑页正在搬运一张卡(M4b spec §0-18)。EditScreen 以 `onCarryingChange` 上报」,到 `private var editCarrying = false` 止,约 277–284 行)。

(b)`EditScreen(` 调用(约 819–830 行)整段替换为:

```kotlin
                    EditScreen(
                        // 选择器真的打开了(存储就绪)才记种子:打不开时编辑页留在原地,
                        // 它自己收菜单、把焦点放回这张卡。
                        onPickIcon = { row, pkg -> pickIcon(pkg).also { if (it) editTarget = row to pkg } },
                        onExit = ::leaveEdit,
                        focusNonce = focusNonce,
                        revision = revision,
                        // R165:卡片固定中档、不画标题;模糊底按当前壁纸算
                        wallpaperFile = homeSettings.wallpaperFile,
                        initialTarget = editTarget,
                    )
```

(c)MENU 分支:把

```kotlin
            // 选择器开着(叠在首页 / 设置页上,或替换了编辑页)时 MENU 什么都不做:排在 editing /
            // settings 之前,否则会把底下那页收掉、选择器留在首页上(终审 C1)。关掉选择器之后
            // pickerTarget 回到 null,MENU 在编辑页上照常 = 退出编辑。
```

的最后一句改成 `// pickerTarget 回到 null,MENU 在编辑页上照常 = 卡片菜单(R165)。`;再把

```kotlin
            // 编辑页搬运中 MENU 什么都不做(M4b spec §0-18,同首页移动态「其余键按下去什么都不发生」);
            // 要走先按返回取消,或确定放下。
            // Ruling R76(2026-09-23 交互测试):从设置外壳进来的编辑页按 MENU 整个收回首页(编辑页 + 外壳),
            // 与外壳其他层按 MENU 一致;不是从外壳进来的(shellStack 空)leaveSettings 什么都不做,行为不变。
            if (editing) { if (!editCarrying) { leaveEdit(); leaveSettings() }; return true }
```

替换为:

```kotlin
            // **R165:编辑页里 MENU = 卡片菜单**(焦点在卡片上时;别处、拿起中什么都不做;卡片菜单开着时 = 收掉它)——
            // 由编辑页根节点的 onPreviewKeyEvent 认(它知道焦点在哪一格),这里原样交过去、不出声(开菜单那一声由编辑页出)。
            // 退出编辑页一律按返回;R76「从外壳进来按 MENU 整个收回首页」随之取消。
            if (editing) return super.dispatchKeyEvent(event)
```

(d)确定键分支:把

```kotlin
        // **编辑页搬运中**(M4b spec §0-18):确定键整下(按下 / 重复 / 松开)原样交给编辑页,不走下面的长按识别、
        // 重复吞掉与按键音——编辑页按重复事件认长按、松开才放下,放下那一声由它自己出(与首页移动态同一套:
        // 短按松开 = 放下 + 一声,长按 = 什么都不发生)。搬运结束后才松开的那一下 UP,编辑页按 downTime 认出来照吞。
        if (editCarrying && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            return super.dispatchKeyEvent(event)
        }
```

替换为:

```kotlin
        // **R165:编辑页里确定键整下(按下 / 重复 / 松开)原样交给编辑页**,不走下面的长按识别与重复吞掉——编辑页根节点按
        // downTime 认(OkPress):卡片上短按松开 = 拿起 / 放下,按满 LONG_PRESS_MS = 卡片菜单;胶囊、选择卡、「添加应用」方块
        // 照常点击(重复事件由编辑页吞掉)。按下那一声在这里出,开菜单那一声由编辑页出。选择器替换编辑页时(pickerTarget != null)走原路。
        if (editing && pickerTarget == null && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            val handled = super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) playKeySound(event.keyCode)
            return handled
        }
```

然后确认没有残留:

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && grep -n "editCarrying\|onCarryingChange" app/src/main/java/com/uniteduone/launcher/MainActivity.kt`
Expected: 无输出(`HomeScreen(` 调用里的 `cardsPerRow` / `showTitles` 不动)。

- [ ] **Step 6: 删旧代码、旧文案、旧测试**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
python3 - <<'EOF'
import re, pathlib
root = pathlib.Path(".")
# 1) 文案:三种语言各删 14 条 + edit_move_desc 的注释行
gone = ["edit_hint", "edit_row_add_app_desc", "edit_row_icon", "edit_row_icon_desc", "edit_row_up", "edit_row_up_desc",
        "edit_row_down", "edit_row_down_desc", "edit_row_new", "edit_row_new_desc", "edit_row_delete", "edit_row_delete_desc",
        "edit_row_menu_title", "edit_move_desc"]
for d in ["values", "values-en", "values-zh-rTW"]:
    p = root / "app/src/main/res" / d / "strings.xml"
    t = p.read_text()
    for k in gone:
        t, n = re.subn(r'\n[ \t]*<string name="%s">.*?</string>' % re.escape(k), "", t, flags=re.S)
        assert n == 1, (d, k, n)
    t, n = re.subn(r'\n[ \t]*<!-- edit_move_desc[^\n]*-->', "", t)
    assert n == 1, (d, "edit_move_desc comment", n)
    p.write_text(t)
# 2) LayoutOps.kt:删 addRowBelow(KDoc 一行 + 函数 4 行)
p = root / "app/src/main/java/com/uniteduone/launcher/LayoutOps.kt"
t = p.read_text()
t, n = re.subn(r'/\*\* 在第 \[index\] 行下方插一个空行[^\n]*\*/\ninternal fun addRowBelow\(.*?\n}\n\n', "", t, flags=re.S)
assert n == 1, n
p.write_text(t)
# 3) LayoutOpsTest:删两条 addRowBelow 的测试
p = root / "app/src/test/java/com/uniteduone/launcher/LayoutOpsTest.kt"
t = p.read_text()
for name in ["addInsertsAnEmptyRowBelowWithTheDefaultIcon", "addStopsAtFiveRowsAndOnBadIndex"]:
    t, n = re.subn(r'\n    @Test fun %s\(\) \{.*?\n    \}\n' % name, "\n", t, flags=re.S)
    assert n == 1, (name, n)
p.write_text(t)
# 4) LayoutOpsBoundaryTest:addRowBelow 的断言换成 appendAppRow 的等价断言,两条只测 addRowBelow 的测试删掉
p = root / "app/src/test/java/com/uniteduone/launcher/LayoutOpsBoundaryTest.kt"
t = p.read_text()
t = t.replace("        assertSame(empty, addRowBelow(empty, 0))\n        assertSame(empty, addRowBelow(empty, -1))\n", "")
t = t.replace("        assertEquals(listOf(ONLY, NEW_ROW_ICON), addRowBelow(one, 0).map { it.icon })\n",
              "        assertEquals(listOf(ONLY, NEW_ROW_ICON), appendAppRow(one).map { it.icon })\n")
for name in ["addBelowTheLastRowAppendsAndTheFifthRowIsTheLastOneAllowed", "addBelowIndexEqualToSizeIsOutOfRange"]:
    t, n = re.subn(r'\n    @Test fun %s\(\) \{.*?\n    \}\n' % name, "\n", t, flags=re.S)
    assert n == 1, (name, n)
t = t.replace("        assertSame(six, addRowBelow(six, 0))\n", "        assertSame(six, appendAppRow(six))\n")
assert "addRowBelow" not in t
p.write_text(t)
# 5) GtvMotionTest 的 KDoc:编辑页纵向位移 R165 起是 200 ms tween,只有行内横向仍读 browseShiftSpec
p = root / "app/src/test/java/com/uniteduone/launcher/GtvMotionTest.kt"
t = p.read_text()
old = "(`HomeScreen` 的行 x/y 位移两处、`EditScreen` 的纵向位移与行内横向位移两处)都读"
assert old in t and t.count("浏览位移走临界阻尼弹簧,四处调用点") == 1
t = t.replace("浏览位移走临界阻尼弹簧,四处调用点", "浏览位移走临界阻尼弹簧,三处调用点")
t = t.replace(old, "(`HomeScreen` 的行 x/y 位移两处、`EditScreen` 的行内横向位移一处;R165 起编辑页纵向位移按 spec 走 200 ms FastOutSlowIn)都读")
p.write_text(t)
print("ok")
EOF
git rm -q app/src/test/java/com/uniteduone/launcher/EditScrollTest.kt
```

- [ ] **Step 7: 全部单测 + 构建**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease 2>&1 | grep -E "e: |error:|FAILED|BUILD" | head -15`
Expected: `BUILD SUCCESSFUL`;`EditIronRulesTest`、`CopyTest.rowMenuStringsAreGone`、`TypeScaleTest`、`EditActingTest`、`EditVisibleTest`、`MoveTest` 全过。
编译报 `Overload resolution ambiguity` 时检查是不是有零件名与别的文件重名(本计划已核对过 `ShelfFrame`、`ShelfChipPill`、`ChoiceCard`、`AddAppTile`、`ShelfPendingCard`、`ShelfIcon`、`EditKeyHints` 在仓库里都不存在)。

- [ ] **Step 8: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git add app/src/main/java/com/uniteduone/launcher/EditScreen.kt app/src/main/java/com/uniteduone/launcher/AppPicker.kt \
  app/src/main/java/com/uniteduone/launcher/MainActivity.kt app/src/main/java/com/uniteduone/launcher/LayoutOps.kt \
  app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/main/res/values-zh-rTW/strings.xml \
  app/src/test/java/com/uniteduone/launcher/EditIronRulesTest.kt app/src/test/java/com/uniteduone/launcher/CopyTest.kt \
  app/src/test/java/com/uniteduone/launcher/LayoutOpsTest.kt app/src/test/java/com/uniteduone/launcher/LayoutOpsBoundaryTest.kt \
  app/src/test/java/com/uniteduone/launcher/GtvMotionTest.kt
git commit -m "$(cat <<'EOF'
feat(edit): 编辑桌面重做成货架——胶囊行操作、确定拿起、长按 / 菜单键卡片菜单、新的一行(R165)

取消行尾「+」与行菜单;菜单键在编辑页不再退出;AppPicker 搬到自己的文件。

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: e2e 脚本按新交互改写

**Files:**
- Rewrite: `scripts/e2e/j_edit.py`
- Modify: `scripts/e2e/j_overlays.py`(约 103–128、170–181 行)、`scripts/e2e/j_pkg.py`(约 136、150–152 行)、`scripts/e2e/j_recreate.py`(约 8、17、70 行)、`scripts/e2e/j_i18n.py`(约 11、22 行)、`scripts/e2e/j_home.py`(约 150 行)、`scripts/e2e/README.md`(覆盖表 `j_edit.py` 一行)

**Interfaces:**
- Consumes: `scripts/e2e/lib.py`(`key`、`keys_fast`、`long_ok`、`screen`、`move_to(sub, direction, max_steps, exact)`、`focus_stable`、`pull_json`、`home_intent`、`restart`、`check`、`journey`、`shot`、`S`、`LAYOUT`);Task 6 的文案 key
- Produces: `j_edit.open_settings()`、`open_edit()`、`rows()`、`row_end()`、`lane_start()`、`go_chip(key)`、`PILL_SETTINGS`(j_overlays / j_pkg 导入);`j_overlays.o_edit_chips()`、`o_edit_card_menu()`、`o_edit_add_app()`、`o_edit_row_icon()`、`o_edit_delete_confirm()`(j_recreate / j_i18n 导入;`o_edit_row_menu` 删除)

- [ ] **Step 1: 重写 `scripts/e2e/j_edit.py`**

```python
"""旅程:设置 → 布局 → 编辑桌面(R165 货架)。
胶囊(添加应用 / 换图标 / 上移 / 下移 / 删除)、确定拿起 → 左右 / 下一层 → 放下、返回取消、长按与菜单键 = 卡片菜单
(换卡片图 / 移出这一行;长按松手不误拿起、不误点菜单)、删非空行确认默认在取消、新的一行 → 应用行(满 5 行变暗不响应)、
空行直接删、菜单键不退出编辑页、HOME、退出落回「编辑桌面」。"""
import sys, time, json, os
sys.path.insert(0, os.path.dirname(__file__))
from lib import *

PILL_SETTINGS = (116, 56, 212, 152)

def open_settings():
    home_intent(); key("down")
    for _ in range(4): key("up")
    s = screen()
    if s.focus() != PILL_SETTINGS:
        key("left", "left")
    key("ok"); time.sleep(1.2)

def open_edit():
    open_settings()
    move_to(S("settings_group_layout")); key("ok"); time.sleep(1.2)
    move_to(S("edit_title")); key("ok"); time.sleep(2)

def rows():
    return (pull_json("layout.json") or {}).get("rows", [])

def row_end():
    """在当前这一条(胶囊或卡片)里一直往右,直到焦点不再移动。"""
    last = None
    for _ in range(14):
        f = screen().focus()
        if f == last: return f
        last = f
        key("right")
    return last

def lane_start():
    """回到当前这一条的最左一格(到头 Cancel,多按无害)。"""
    for _ in range(12): key("left", gap=0.3)

def chip_labels():
    """焦点在某层胶囊上:从最左一颗起往右,记下每颗的字。"""
    lane_start()
    labels, last = [], None
    for _ in range(7):
        s = screen(); f = s.focus()
        if f == last: break
        labels.append(s.label()); last = f
        key("right")
    return labels

def go_chip(k):
    """焦点在某层胶囊上:走到字为 S(k) 的那颗。"""
    lane_start()
    return move_to(S(k), "right", max_steps=6, exact=True)

def full_text():
    return S("edit_choice_full").replace("%1$d", "5")

def run():
    journey("edit-setup")
    restart(settings_patch={"language": "en", "onboardingDone": True}, layout=LAYOUT)
    open_edit()
    s = screen()
    check("编辑页标题", s.has(S("edit_title")), s.texts()[:6])
    check("编辑页单个焦点", s.count_focused() == 1, s.count_focused())
    check("页头按键提示「拿起卡片」", s.has(S("edit_hint_pick")), s.texts()[:12])
    check("初始焦点在第 1 层第 1 张卡", s.label() not in ("", S("edit_row_add_app")), s.label())
    shot("edit-shelves-default")

    journey("edit-chips")
    key("up")
    s = screen()
    check("卡片按上 → 本层离得最近的胶囊(第一颗)", s.label() == S("edit_row_add_app"), s.label())
    labs = chip_labels()
    check("第 1 层胶囊:添加应用 / 换图标 / 下移 / 删除(没有上移)",
          labs == [S("edit_row_add_app"), S("edit_chip_icon"), S("edit_chip_down"), S("edit_chip_delete")], labs)
    # 添加应用
    go_chip("edit_row_add_app"); key("ok"); time.sleep(2.5)
    s = screen()
    check("添加应用页单个焦点", s.count_focused() == 1, s.count_focused())
    before = len(rows()[0]["apps"]); lab = s.label()
    key("ok"); time.sleep(1.5)
    check("加了一个应用到第 1 行", len(rows()[0]["apps"]) == before + 1, (before, len(rows()[0]["apps"])))
    time.sleep(1.0)
    s = screen()
    # 列表那一项的字可能多一个「New」角标:按「卡片名包含在列表项里」认
    check("加完焦点落在新卡上", s.count_focused() == 1 and s.label() != "" and s.label() in lab, (s.label(), lab))
    # 换图标
    key("up"); go_chip("edit_chip_icon"); key("ok"); time.sleep(1.2)
    s = screen()
    check("行图标页", s.has(S("edit_row_icon_heading")), s.texts()[:6])
    icon_before = rows()[0].get("icon")
    key("right"); key("ok"); time.sleep(1.2)
    check("行图标写盘变化", rows()[0].get("icon") != icon_before, (icon_before, rows()[0].get("icon")))
    s = screen()
    check("换完图标焦点回「换图标」胶囊", s.label() == S("edit_chip_icon"), s.label())
    check("写盘每行只有 icon + apps", all(set(r) == {"icon", "apps"} for r in rows()), rows()[:2])
    # 下移 / 上移
    before = rows(); mine = before[0]
    go_chip("edit_chip_down"); key("ok"); time.sleep(1.5)
    after = rows()
    check("下移一层", after[1] == mine and after[0] == before[1], (mine, after[:2]))
    s = screen()
    check("下移后焦点跟着这一层、仍在「下移」", s.label() == S("edit_chip_down"), s.label())
    go_chip("edit_chip_up"); key("ok"); time.sleep(1.5)
    check("上移回来", rows() == before, rows()[:2])
    s = screen()
    check("回到第一层没有「上移」→ 焦点落「下移」", s.label() == S("edit_chip_down"), s.label())

    journey("edit-pick-up")
    key("down"); time.sleep(0.4)          # 胶囊 → 本层卡片(离「下移」最近的那张)
    s = screen(); card = s.label()
    order0 = rows()[0]["apps"][:]
    key("ok"); time.sleep(0.8)
    s = screen()
    check("确定 = 拿起:页头提示换成「放下」", s.has(S("edit_hint_drop")), s.texts()[:12])
    shot("edit-carry")
    key("left"); key("ok"); time.sleep(1.5)
    order1 = rows()[0]["apps"]
    check("左移一格并写盘", order1 != order0 and sorted(order1) == sorted(order0), (order0, order1))
    s = screen()
    check("放下后焦点还在这张卡", s.label() == card and s.count_focused() == 1, (s.label(), card))
    key("ok"); time.sleep(0.6); key("right"); key("back"); time.sleep(1.2)
    check("返回取消:不写盘", rows()[0]["apps"] == order1, rows()[0]["apps"])
    s = screen()
    check("取消后焦点回出发那张卡、仍在编辑页", s.label() == card and s.has(S("edit_title")), (s.label(), card))
    n0, n1 = len(rows()[0]["apps"]), len(rows()[1]["apps"])
    key("ok"); time.sleep(0.6); key("down"); key("ok"); time.sleep(1.5)
    check("下搬到第 2 层并写盘", len(rows()[0]["apps"]) == n0 - 1 and len(rows()[1]["apps"]) == n1 + 1,
          (len(rows()[0]["apps"]), len(rows()[1]["apps"])))
    s = screen()
    check("搬下去之后焦点还在这张卡", s.label() == card, (s.label(), card))

    journey("edit-card-menu")
    order_before = rows()
    long_ok()
    s = screen()
    check("长按 = 卡片菜单:换卡片图 / 移出这一行", s.has(S("edit_change_image")) and s.has(S("edit_remove")), s.texts()[-6:])
    # 按整串相等判:菜单底下的编辑页节点仍在 uiautomator 树里,焦点层胶囊「Move Up / Move Down」含子串「Move」,s.has 会误报
    check("卡片菜单里没有「移动位置」(R165:确定就是拿起)", S("card_menu_move") not in s.texts(), s.texts()[-6:])
    check("长按松手不误拿起(没有「放下」提示、没写盘)", not s.has(S("edit_hint_drop")) and rows() == order_before, s.texts()[:12])
    check("长按松手不误点菜单第一项(没进换卡片图页)", not s.has(S("picker_card_image_title")), s.texts()[:6])
    check("卡片菜单单个焦点", s.count_focused() == 1, s.count_focused())
    shot("edit-card-menu")
    move_to(S("edit_change_image")); key("ok"); time.sleep(2)
    s = screen()
    check("进了换卡片图页", s.has(S("picker_card_image_title")), s.texts()[:6])
    key("back"); time.sleep(1.5)
    s = screen()
    check("返回编辑页、焦点回到那张卡", s.label() == card and s.count_focused() == 1, (s.label(), card))
    long_ok(); move_to(S("edit_change_image")); keys_fast("ok", "back", gap=0.3); time.sleep(1.5)
    s = screen()
    check("快速进出选图页 → 回到那张卡", s.label() == card and s.has(S("edit_title")), (s.label(), card))
    key("menu"); time.sleep(1.2)
    s = screen()
    check("菜单键 = 卡片菜单", s.has(S("edit_remove")), s.texts()[-6:])
    key("menu"); time.sleep(1.2)
    s = screen()
    check("再按菜单键收菜单、焦点回卡、编辑页还在",
          s.label() == card and s.has(S("edit_title")) and not s.has(S("edit_remove")), (s.label(), s.texts()[:4]))
    key("up"); chip = screen().label()
    key("menu"); time.sleep(1.0)
    s = screen()
    check("焦点在胶囊上时菜单键什么都不做(不退出)",
          s.label() == chip and s.has(S("edit_title")) and not s.has(S("edit_remove")), (s.label(), chip))
    key("down"); time.sleep(0.4)
    n = len(rows()[1]["apps"])
    long_ok(); move_to(S("edit_remove")); key("ok"); time.sleep(1.5)
    check("移出后少一个", len(rows()[1]["apps"]) == n - 1, rows()[1]["apps"])
    ok, s = focus_stable()
    check("移出后单个焦点", ok, s.count_focused())

    journey("edit-delete-confirm")
    key("up"); go_chip("edit_chip_delete"); key("ok"); time.sleep(1.5)
    s = screen()
    check("删非空行弹确认页", s.has(S("edit_row_delete_confirm_title")), s.texts()[:6])
    check("确认页默认焦点在 Cancel", S("dialog_cancel") in s.label(), s.label())
    shot("edit-delete-confirm")
    n_rows = len(rows())
    key("ok"); time.sleep(1.5)
    check("取消 → 行还在", len(rows()) == n_rows, len(rows()))
    s = screen()
    check("取消后焦点回「删除」胶囊", s.label() == S("edit_chip_delete"), s.label())

    journey("edit-new-row")
    s = move_to(S("edit_choice_app_row"), "down", max_steps=14)
    check("一路按下到「新的一行」→「应用行」", s is not None, screen().label())
    s = screen()   # 「Add」是「Add App」的子串,单判它恒真:改判这一组独有的「Choose」,且「Pick up」已不在
    check("页头提示换成「选择 / 添加」", s.has(S("edit_hint_choose")) and not s.has(S("edit_hint_pick")), s.texts()[:12])
    shot("edit-new-row")
    n_rows = len(rows())
    key("ok"); time.sleep(1.5)
    check("新建一行(插在最后、空的)", len(rows()) == n_rows + 1 and rows()[-1]["apps"] == [], rows()[-1:])
    s = screen()
    check("新建后焦点落在新架子的「添加应用」方块", s.label() == S("edit_row_add_app"), s.label())
    move_to(S("edit_choice_app_row"), "down", max_steps=4)
    s = screen()
    check("满 5 行:应用行写「已满」", s.has(full_text()), s.texts()[-6:])
    shot("edit-new-row-full")
    key("ok"); time.sleep(1.2)
    check("满 5 行:确定不响应", len(rows()) == 5, len(rows()))
    key("up"); key("up")                  # 新的一行 → 第 5 层方块 → 第 5 层胶囊
    go_chip("edit_chip_delete"); key("ok"); time.sleep(1.5)
    s = screen()
    check("空行直接删、不弹确认", len(rows()) == 4 and not s.has(S("edit_row_delete_confirm_title")), len(rows()))
    check("删后焦点落上一层第一颗胶囊", s.label() == S("edit_row_add_app") and s.count_focused() == 1, s.label())

    journey("edit-home-intent")
    key("down"); time.sleep(0.4)
    long_ok()
    home_intent(); time.sleep(1.2)
    s = screen()
    check("编辑页 + 菜单时按 HOME → 回首页", not s.has(S("edit_title")) and not s.has(S("edit_remove")), s.texts()[:5])
    check("HOME 后单个焦点", s.count_focused() == 1, s.count_focused())

    journey("edit-exit-to-layout")
    open_edit()
    key("back"); time.sleep(1.5)
    s = screen()
    check("退出编辑页 → 回到布局页的「Edit Home Screen」", S("edit_title") in s.label(), s.label())
    shot("edit-exit-layout")
    home_intent()
    restart(layout=LAYOUT)

if __name__ == "__main__":
    run()
    summary()
```

- [ ] **Step 2: 改其余脚本**

`scripts/e2e/j_overlays.py`:把 `o_edit_card_menu` 到 `o_edit_delete_confirm` 这一段(`def o_edit_card_menu():` 起、`def o_edit_cardart():` 之前)整段替换为:

```python
def o_edit_card_menu():
    open_edit(); c = screen().focus(); long_ok(); return ("box", c)

def o_edit_chips():
    """编辑页第 2 层的胶囊(R165:取代行菜单;不是浮层,给三语溢出检查 / 重建用)。"""
    open_edit(); key("down"); return None

def _edit_chip(k):
    # 第 1 层卡片按下 → 第 2 层胶囊(3 个应用,不横向滚动),再走到那颗
    open_edit(); key("down")
    for _ in range(6): key("left", gap=0.3)
    move_to(S(k), "right", max_steps=6, exact=True)
    key("ok"); time.sleep(1.8); return ("label", S(k))

def o_edit_add_app():
    return _edit_chip("edit_row_add_app")

def o_edit_row_icon():
    return _edit_chip("edit_chip_icon")

def o_edit_delete_confirm():
    return _edit_chip("edit_chip_delete")
```

同文件 `CASES` 里把编辑页那几行换成:

```python
    ("编辑页", o_edit, lambda s: s.has(S("edit_title")) and s.has(S("edit_hint_pick"))),
    ("编辑·卡片菜单", o_edit_card_menu, lambda s: s.has(S("edit_remove"))),
    # 页名「Add App」与胶囊同字:按字高认页名(31 sp 的页名 > 50 px,12 sp 的胶囊字 ≈ 32 px)
    ("编辑·添加应用", o_edit_add_app, lambda s: any(n["text"] == S("edit_add_app_title") and n["b"][3] - n["b"][1] > 50 for n in s.nodes)),
    ("编辑·行图标", o_edit_row_icon, lambda s: s.has(S("edit_row_icon_heading"))),
    ("编辑·删行确认", o_edit_delete_confirm, lambda s: s.has(S("edit_row_delete_confirm_title"))),
```

(删掉 `("编辑·行菜单", o_edit_row_menu, …)` 那一行;`("编辑·换卡片图", o_edit_cardart, …)` 保留,但 `o_edit_cardart` 里的 `key("ok")` 改成 `long_ok()`:)

```python
def o_edit_cardart():
    open_edit(); s = screen(); lab = s.label(); long_ok()
    move_to(S("edit_change_image")); key("ok"); time.sleep(2); return ("label", lab)
```

`scripts/e2e/j_pkg.py`:`open_edit(); key("ok"); time.sleep(1.2)` 改成 `open_edit(); long_ok()`;「pkg-edit-add-app-list」一段里

```python
    open_edit(); key("down")
    for _ in range(8): key("right")
    key("ok"); time.sleep(1.2); move_to(S("edit_row_add_app")); key("ok"); time.sleep(2.5)
```

改成

```python
    open_edit(); key("down")                     # 第 2 层胶囊
    for _ in range(6): key("left", gap=0.3)      # 最左一颗 =「添加应用」
    key("ok"); time.sleep(2.5)
```

`scripts/e2e/j_recreate.py`:import 里 `o_edit_row_menu` 换成 `o_edit_card_menu`;`CASES` 里 `("编辑·行菜单", o_edit_row_menu)` 换成 `("编辑·卡片菜单", o_edit_card_menu)`;`force-stop-cold-start` 的列表里同样换。

`scripts/e2e/j_i18n.py`:import 里 `o_edit_row_menu` 换成 `o_edit_chips`;`PAGES` 里 `("edit-row-menu", o_edit_row_menu)` 换成 `("edit-shelves", o_edit_chips)`。

`scripts/e2e/j_home.py`:`check("确定 → 进编辑页", s.has(S("edit_hint")[:20]), s.texts()[:8])` 改成 `check("确定 → 进编辑页", s.has(S("edit_hint_pick")), s.texts()[:8])`。

`scripts/e2e/README.md`:覆盖表 `j_edit.py` 一行改为

```markdown
| `j_edit.py` | 编辑桌面(R165 货架):胶囊(添加应用、换图标、上移 / 下移跟着这一层走、删非空行确认默认在取消、空行直接删);确定拿起 → 左移 / 下搬一层 → 放下写盘、返回取消不写盘;长按 / 菜单键 = 卡片菜单(换卡片图 → 返回落回同一张卡,含 0.3 s 内快速进出;移出),长按松手不误拿起、不误点;焦点在胶囊上菜单键不做事、不退出;新的一行 → 应用行(插在最后、落「添加应用」方块;满 5 行写「已满」、确定不响应);HOME;退出落回「编辑桌面」 |
```

并在「写这类脚本的规矩」末尾加一条:

```markdown
- **编辑页(R165)的上下键由页面自己按固定顺序走**(胶囊 → 卡片 → 下一层胶囊 → … → 新的一行),不是几何搜索:脚本按「几下上 / 下」定位是可靠的;胶囊在非焦点层透明度为 0、uiautomator 可能不报,断言胶囊一律先让那一层成为焦点层(`go_chip` / `chip_labels`)。
```

- [ ] **Step 3: 语法自检**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐/scripts/e2e && python3 -m py_compile j_edit.py j_overlays.py j_pkg.py j_recreate.py j_i18n.py j_home.py && grep -n "o_edit_row_menu\|edit_row_up\|edit_row_new\|edit_row_delete\"\|S(\"edit_hint\")" *.py`
Expected: 编译无输出;grep 无输出(`edit_row_delete_confirm_title` / `edit_row_delete_ok` 不在 grep 模式里)。真跑在 Task 10。

- [ ] **Step 4: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git add scripts/e2e/j_edit.py scripts/e2e/j_overlays.py scripts/e2e/j_pkg.py scripts/e2e/j_recreate.py scripts/e2e/j_i18n.py scripts/e2e/j_home.py scripts/e2e/README.md
git commit -m "$(cat <<'EOF'
test(e2e): 编辑页按货架交互重写 j_edit,其余脚本去掉行菜单依赖(R165)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: 模拟器验收(e2e + 截图 + 换层裁切 + 帧时间)

**Files:**
- Create: `docs/screenshots/edit-shelves/*.jpg`
- 若 e2e 暴露问题:回到 Task 8 的文件修(修完重跑本任务对应段落),修复另起提交。

**Interfaces:**
- Consumes: Task 8 的 APK、Task 9 的脚本;AVD `unitedu-tv-2`(端口 5562)

- [ ] **Step 1: 起模拟器、装包(只用 unitedu-tv-2)**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
source scripts/env.sh
emulator -list-avds | grep -x unitedu-tv-2
emulator -avd unitedu-tv-2 -port 5562 -no-snapshot -no-audio -gpu host > /tmp/edit-shelves-emu.log 2>&1 &
adb -s emulator-5562 wait-for-device && adb -s emulator-5562 shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
adb -s emulator-5562 emu avd name          # 必须回 unitedu-tv-2;不对就停下,不要往别的 AVD 装
gradle --no-daemon assembleRelease
adb -s emulator-5562 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5562 shell cmd package set-home-activity com.uniteduone.launcher/.MainActivity
export DEV=emulator-5562 E2E_OUT=/tmp/edit-shelves-e2e
python3 scripts/e2e/fixtures.py
```

Expected: `emu avd name` 输出 `unitedu-tv-2`;安装 `Success`;fixtures 装完测试 APK。

- [ ] **Step 2: 跑 e2e**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
export DEV=emulator-5562 E2E_OUT=/tmp/edit-shelves-e2e
python3 scripts/e2e/j_edit.py 2>&1 | tail -25
OVERLAYS_ONLY=编辑页,编辑·卡片菜单,编辑·添加应用,编辑·行图标,编辑·删行确认,编辑·换卡片图 python3 scripts/e2e/j_overlays.py 2>&1 | tail -15
E2E_ONLY=j_home,j_pkg python3 scripts/e2e/run_all.py 2>&1 | tail -15
```

Expected: 三段的 `summary()` 都是 `N/N passed`。任何 FAIL:看 `$E2E_OUT/FAIL-*.png`,按 superpowers:systematic-debugging 找根因,改 Task 8 的代码、单测补一条复现、重跑这一步。

- [ ] **Step 3: 截图归档(1920 × 1080 → jpg)**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
mkdir -p docs/screenshots/edit-shelves
export DEV=emulator-5562 E2E_OUT=/tmp/edit-shelves-e2e
python3 - <<'EOF'
import sys, time, subprocess, os
sys.path.insert(0, "scripts/e2e")
from lib import *
from j_edit import open_edit, go_chip
OUTD = "docs/screenshots/edit-shelves"
def cap(name):
    png = f"/tmp/{name}.png"
    with open(png, "wb") as f:
        f.write(subprocess.run(["adb", "-s", DEV, "exec-out", "screencap", "-p"], capture_output=True).stdout)
    subprocess.run(["sips", "-s", "format", "jpeg", "-s", "formatOptions", "85", png, "--out", f"{OUTD}/{name}.jpg"], capture_output=True)
restart(settings_patch={"language": "zh-CN", "onboardingDone": True}, layout=LAYOUT)
set_lang("zh-CN")
open_edit(); time.sleep(1.5); cap("01-shelves-default")
for _ in range(8): key("right", gap=0.3)                                          # 第 1 层 8 张:走到最后一张,整行左移
time.sleep(1.0); cap("01b-long-row-end")
for _ in range(8): key("left", gap=0.3)                                           # 回到第 1 张
time.sleep(1.0)
key("up"); time.sleep(0.8); cap("02-chips-focused")
key("down"); key("ok"); time.sleep(0.8); cap("03-carry-arrows")
key("back"); time.sleep(1.0)
long_ok(); time.sleep(0.8); cap("04-card-menu")
key("back"); time.sleep(1.0)
move_to(S("edit_choice_app_row"), "down", max_steps=14); time.sleep(0.8); cap("05-new-row")
key("ok"); time.sleep(1.5); move_to(S("edit_choice_app_row"), "down", max_steps=4); time.sleep(0.8); cap("06-new-row-full")
key("up"); key("up"); go_chip("edit_chip_delete"); key("ok"); time.sleep(1.2)   # 删掉刚建的空行 → 焦点落第 4 层第一颗胶囊
go_chip("edit_chip_delete"); key("ok"); time.sleep(1.2); cap("07-delete-confirm")      # 第 4 层有应用:弹确认
key("back"); time.sleep(1.0)
home_intent(); restart(settings_patch={"language": "en"}, layout=LAYOUT)
EOF
ls -la docs/screenshots/edit-shelves/
```

Expected: 8 张 jpg。**逐张用 Read 看一遍**,对照 `docs/screenshots/edit-redesign/c1.jpg` / `c2.jpg`:页头位置与按键提示、架子左右 40 dp 与圆角、焦点层更亮 + 阴影、非焦点层整体压暗且不露胶囊、胶囊填主题色放大、卡片中档与间距、拿起时的箭头与 highlight 描边、「新的一行」虚线框与选择卡、满 5 行时选择卡变暗写「已满 5 行」、确认页默认在「取消」。有出入记进 WORKLOG 的「与效果图的差异」,数值问题回 Task 2 / 7 改常量。
**另专门核对 owner 裁定「架子不裁焦点卡」**(横向只允许两种裁:屏幕边缘、越过架子右端的非焦点卡):
- `01-shelves-default` 第 1 层第 1 张卡(默认焦点)的左边:描边完整,60 dp 柔光**连续地画出架子左缘**(x < 40 dp 处仍有渐弱的光),没有一条竖直硬边;
- `01b-long-row-end` 第 1 层最后一张(焦点)右边:描边与柔光完整、可以画出架子右缘;前面滑出架子左边的卡画到架子外、只被屏幕左缘裁(同首页);
- `01-shelves-default` 第 1 层右端露出一截的第 7 张:在架子右边缘(x = 920 dp)处齐齐截掉,不画到架子外的 40 dp 留白里;
- 非焦点层(整层 0.62)里第 1 张卡左边同样不出现硬边(离屏层四边撑大 60 dp,R129f)。
任何一条不对:回 Task 8 的 `clipPastShelfEnd` / `cur` 或 Task 7 的 `ShelfFrame` 修,不另出题。

- [ ] **Step 4: 换层淡化不裁焦点卡(R129f)——1× 录像逐帧看**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
export DEV=emulator-5562
adb -s $DEV shell input keyevent 20; sleep 0.5            # 唤醒键(待机后第一下会被吞)
adb -s $DEV shell screenrecord --size 960x540 --time-limit 6 /sdcard/shelf.mp4 &
sleep 1; for k in 19 20 20 20 19 19; do adb -s $DEV shell input keyevent $k; sleep 0.7; done; wait
adb -s $DEV pull /sdcard/shelf.mp4 /tmp/shelf.mp4
rm -rf /tmp/shelf-frames && mkdir -p /tmp/shelf-frames
ffmpeg -loglevel error -i /tmp/shelf.mp4 -fps_mode passthrough -vf scale=960:540 /tmp/shelf-frames/f%04d.png
ffprobe -v error -show_entries frame=pts_time -of csv=p=0 /tmp/shelf.mp4 | head -80 > /tmp/shelf-frames/pts.txt
ls /tmp/shelf-frames | wc -l
```

Expected: 帧数 ≥ 150(`-gpu host` 下约 50–60 fps)。用 Read 抽看每次按键后 0–250 ms 的几帧(按 `pts.txt` 定位):新焦点层淡入期间,焦点卡的放大、描边、柔光**四边都完整**,没有被架子的矩形截掉一截、淡完又冒出来。若被截:`ShelfFrame` 的 `layout` 撑大没生效,回 Task 7 修。

- [ ] **Step 5: 帧时间(5 行 + 新的一行 + 模糊底)**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
export DEV=emulator-5562 E2E_OUT=/tmp/edit-shelves-e2e
python3 - <<'EOF'
import sys; sys.path.insert(0, "scripts/e2e")
from lib import *
from j_edit import open_edit
L = {"rows": LAYOUT["rows"] + [{"icon": "games", "apps": ["test.dummy.app09", "test.dummy.app14", "test.dummy.app15"]}]}
restart(settings_patch={"language": "en", "onboardingDone": True}, layout=L)
open_edit()
EOF
adb -s $DEV shell dumpsys gfxinfo com.uniteduone.launcher reset > /dev/null
for i in $(seq 1 10); do adb -s $DEV shell input keyevent 20; sleep 0.4; done
for i in $(seq 1 10); do adb -s $DEV shell input keyevent 19; sleep 0.4; done
adb -s $DEV shell dumpsys gfxinfo com.uniteduone.launcher | grep -E "Total frames|Janky|50th|90th|95th|99th"
```

Expected: 打印帧数与分位;把四个数原样记进 WORKLOG(模拟器数只作相对参照;真机由 Gordon 随正式版看,spec §7)。

- [ ] **Step 6: 还原并关模拟器**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
export DEV=emulator-5562 E2E_OUT=/tmp/edit-shelves-e2e
python3 scripts/e2e/fixtures.py --uninstall
adb -s $DEV shell settings put secure long_press_timeout 400
adb -s $DEV emu kill
```

Expected: 测试包卸掉、`long_press_timeout` 回 400、模拟器关闭(Dock 上若留下 `qemu-system-aarch64` 空壳图标,属已知现象,见 WORKLOG 2026-10-08)。

- [ ] **Step 7: 提交截图**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git add docs/screenshots/edit-shelves/
git commit -m "$(cat <<'EOF'
docs: 编辑桌面货架模拟器截图(R165)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: 文档同步

**Files:**
- Modify: `CLAUDE.md`(焦点表四行)、`docs/REVIEW-GUIDE.md`(48、107、111 行)、`README.zh-CN.md`(107、123 行)、`README.md`(112、128 行)、`docs/superpowers/specs/2026-09-20-gtv-line-design.md`(R165 行)、`docs/WORKLOG.md`(末尾)

- [ ] **Step 1: CLAUDE.md 焦点表**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
python3 - <<'EOF'
import pathlib
p = pathlib.Path("CLAUDE.md"); lines = p.read_text().split("\n")
EDIT = ("   | 编辑页(R165 货架,`EditScreen`) | EditScreen 自己的账本。**目标 `target: ShelfSpot(shelf, zone ∈ {CHIPS, CARDS, NEW}, index)` 与持有者 `holder` 分开**"
 "(铁律 5:三个分量在一个值里一起写;`report()` 只在不重定位、没 `paused`、不在拿起中时让目标跟着持有者走,读的是活的状态),"
 "`ON_PAUSE` 起 `paused` 冻结、`ON_RESUME` 按目标重定位;每个可聚焦节点(胶囊、卡片、「添加应用」方块、选择卡)按 `ShelfSpot` 逐项挂 requester"
 "(`req()`,表只增不换,不会整表换新而悬空);显式重定位 `LaunchedEffect(retargetTick, ghost)` 只信目标自报 `holder == want`(铁律 2),"
 "目标格是加载占位时先等数据;`holder == null` 看门狗(3 帧宽限、60 帧封顶;守卫 ghost / retargeting / overlayOpen / paused / noHolder / 数据未到 全在 key 里,铁律 6)。"
 "**上下键不走 Compose 的二维搜索**:根节点 `onPreviewKeyEvent` 按 `verticalStep`(EditShelves.kt,单测)走固定顺序「第 1 层胶囊 → 第 1 层卡片 → … → 新的一行」,"
 "层内与跨层都按水平中心取最近(`centers` 由 `onGloballyPositioned` 量),到头吞掉;左右在每条首末 `Cancel`。"
 "纵向位移 `shelfScroll`(焦点层顶边对齐焦点线 180 dp,夹到内容末尾,200 ms FastOutSlowIn)+ `offset` + `wrapContentHeight(unbounded)`,"
 "行内横向 `rowShiftX` + `wrapContentWidth(unbounded)`(铁律 1);**横向只裁越过架子右端的非焦点卡**(`clipPastShelfEnd`,owner 裁定:焦点卡与架子左边缘一律不裁,第 1 张卡的柔光画到架子外),当前那张 `zIndex` 1。非焦点层整层 0.62 不透明(`ShelfFrame`,图层四边撑大 `APP_FOCUS_GLOW_DP`,R129f),"
 "胶囊在非焦点层透明度 0 但照常可聚焦。浮层(卡片菜单 / 添加应用 / 行图标 / 删行确认)是一个 `overlay` 状态 + `OverlayStack`,开着时卡片子树 `canFocus = false`、"
 "看门狗与重定位让路;关掉时 `returnSpot` 落回打开它的胶囊 / 卡片 / 方块。动作后的落点(`landing*`,单测):上移 / 下移跟着这一层、落同一颗胶囊(那颗没了落反方向那颗);"
 "删除落上一层第一颗胶囊;新建应用行落新架子的「添加应用」方块;加应用落新卡;移出落前一张。**拿起不是浮层**:卡片上确定键短按松开 = 拿起,"
 "焦点始终在被搬的卡上,目标 = `carry.pos`,每一步 `retarget`;返回 / `ON_PAUSE` / 浮层要打开 = 取消复原。确定键与菜单键由根节点截获"
 "(MainActivity 在编辑页里把确定键整下与菜单键原样交过来):`OkPress` 按 downTime 认长按,**按下时接手的那一下,松开一律吞掉**(长按开菜单后松手不点到菜单第一项);"
 "菜单键在卡片上 = 卡片菜单,别处不做事,不再退出编辑页。「换卡片图」的选择器**替换**编辑页(`editTarget` 种子、R138 残影照旧);"
 "R147 / R138 残影让路照旧(`ghost` 进两个效果的守卫与 key、`BackHandler(enabled = !ghost)`)。 |")
out, hit = [], {"edit": 0, "rowmenu": 0, "icon": 0, "confirm": 0, "picker": 0}
for ln in lines:
    if ln.startswith("   | 编辑页 |"):
        out.append(EDIT); hit["edit"] += 1; continue
    if ln.startswith("   | 编辑页的行菜单"):
        hit["rowmenu"] += 1; continue
    if ln.startswith("   | 行图标选择器"):
        old = "选定 / BACK 都 `retarget` 回该行的「+」"
        assert old in ln; ln = ln.replace(old, "选定 / BACK 都落回这一层的「换图标」胶囊(R165;R165 前回该行的「+」)"); hit["icon"] += 1
    if ln.startswith("   | 编辑页的删行确认页"):
        old = "Cancel/BACK → 回到该行的「+」,OK → 落到上一行的「+」(删的是第 1 行则落新第 1 行的「+」)"
        assert old in ln; ln = ln.replace(old, "Cancel/BACK → 回到这一层的「删除」胶囊,OK → 落到上一层的第一颗胶囊(删的是第 1 层则落新第 1 层的;R165,原来落行尾「+」)"); hit["confirm"] += 1
    if ln.startswith("   | 添加应用列表 |"):
        assert ln.endswith(" |"); ln = ln[:-2] + ";**R165**:从胶囊「添加应用」或空架子的「添加应用」方块打开,返回落回打开它的那一格(`EditOverlay.Pick.from`);实现搬到 `AppPicker.kt`,焦点账本未动 |"; hit["picker"] += 1
    out.append(ln)
assert all(v == 1 for v in hit.values()), hit
t = "\n".join(out)
# 长按卡片菜单那一行:编辑页只剩卡片菜单(行菜单取消)
old_menu = "这里三个调用点(长按卡片菜单、编辑页的两个菜单)只传标签"
assert t.count(old_menu) == 1
t = t.replace(old_menu, "这里两个调用点(长按卡片菜单、编辑页的卡片菜单;R165 前编辑页还有行菜单)只传标签")
p.write_text(t); print(hit)
EOF
```

Expected: 打印 `{'edit': 1, 'rowmenu': 1, 'icon': 1, 'confirm': 1, 'picker': 1}`。

- [ ] **Step 2: REVIEW-GUIDE、两份 README、gtv 线设计 §12、WORKLOG**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
python3 - <<'EOF'
import pathlib
def sub(path, old, new):
    p = pathlib.Path(path); t = p.read_text(); assert t.count(old) == 1, (path, old[:40]); p.write_text(t.replace(old, new))
# REVIEW-GUIDE
sub("docs/REVIEW-GUIDE.md",
    "- `EditScreen.kt`(1350 行):编辑分栏页(行管理、搬运模式、添加应用列表 `AppPicker`);",
    "- 编辑桌面(R165「货架」):`EditScreen.kt`(焦点账本、按键截获、浮层摞、写盘、拿起)、`EditShelves.kt`(纯逻辑:货架模型、上下键固定顺序、落点、焦点线位移,单测)、`EditShelfParts.kt`(架子 / 胶囊 / 选择卡等零件)、`EditPress.kt`(确定键长短判定)、`EditBackdrop.kt`(壁纸模糊底,算一次缓存位图)、`AppPicker.kt`(添加应用列表);")
p = pathlib.Path("docs/REVIEW-GUIDE.md"); t = p.read_text()
old107 = next(l for l in t.split("\n") if l.startswith("- **从「换卡片图」回到编辑页时,编辑页的纵向滚动位置可能与离开前不同**"))
t = t.replace(old107 + "\n", "")
t = t.replace("`EditScreen` 约 880 行、", "`EditScreen` 约 540 行(R165 已拆出纯逻辑与零件)、")
t = t.replace("`EditScreen.kt` 约 135 行", "`EditScreen.kt` 里 `rows` 的初值 `Layout.read`")
p.write_text(t)
# README 中文
sub("README.zh-CN.md", "在每一行末尾的「＋」里添加;", "在每一行顶部的「添加应用」里添加(空行直接按卡片位置的「添加应用」方块);")
sub("README.zh-CN.md", "增删行、给行换图标(行没有名字,只认图标)、在行与行之间整理,在「设置 → 布局 → 编辑桌面」里做。",
    "增删行、给行换图标(行没有名字,只认图标)、在行与行之间整理,在「设置 → 布局 → 编辑桌面」里做。编辑桌面是一层层「架子」:每层顶部摆着这一行能做的事(添加应用 / 换图标 / 上移 / 下移 / 删除);在卡片上按确定拿起,方向键挪(上下可以挪到别的行),再按确定放下、返回取消;长按或菜单键是卡片菜单(换卡片图 / 移出这一行);最下面「新的一行」加新行(最多 5 行)。")
# README 英文
sub("README.md", "where you add apps with the \"＋\" at the end of each row.", "where you add apps with \"Add App\" at the top of each row (an empty row has an \"Add App\" tile where its cards go).")
sub("README.md", "Adding and deleting rows, changing a row's icon (rows have no names, only icons) and reorganizing across rows happen in Settings → Layout → Edit Home Screen.",
    "Adding and deleting rows, changing a row's icon (rows have no names, only icons) and reorganizing across rows happen in Settings → Layout → Edit Home Screen. It shows your rows as shelves: each shelf's actions sit along its top (Add App / Icon / Move Up / Move Down / Delete). Press OK on a card to pick it up, move it with the arrow keys (up and down move it to another row), then OK to drop it or Back to cancel. Hold OK or press Menu on a card for its menu (Change Card Art / Remove). \"New Row\" at the bottom adds a row (up to 5).")
# gtv 线设计 §12:R165 行补状态列
p = pathlib.Path("docs/superpowers/specs/2026-09-20-gtv-line-design.md"); lines = p.read_text().split("\n")
i = next(k for k, l in enumerate(lines) if l.startswith("| R165 |"))
lines[i] = lines[i] + " 编辑页部分已实施(计划 `docs/superpowers/plans/2026-10-08-edit-shelves.md`):单测 + e2e `j_edit` 全过,模拟器截图 `docs/screenshots/edit-shelves/`;频道行(计划 2)未做;电视随正式版看 |"
# R76(从外壳进来的编辑页按 MENU 整个收回首页)被 R165 取代:编辑页里 MENU = 卡片菜单
j = next(k for k, l in enumerate(lines) if l.startswith("| R76 |"))
assert lines[j].endswith("| 现行 |"), lines[j][-20:]
lines[j] = lines[j][: -len("| 现行 |")] + "| 被 R165 取代(编辑页里 MENU = 卡片菜单,退出一律按返回) |"
p.write_text("\n".join(lines))
print("ok")
EOF
```

Expected: 打印 `ok`。用 `git diff --stat` 看到 5 个文件改动。README 两份确认同步(中文 / 英文各两处)。

再在 `docs/WORKLOG.md` 末尾追加(把 Task 10 Step 5 的四个帧时间数、Step 3 与效果图的差异、e2e 结果填进去):

```markdown

## 2026-10-08 · 编辑桌面货架实施(R165,计划 1)
- 按 `docs/superpowers/plans/2026-10-08-edit-shelves.md` 实施 spec §2(编辑页)与 §5 的两条(菜单键、新行追加到末尾);频道行留给计划 2。
- 新文件:`EditShelves.kt`(纯逻辑,单测 `EditShelvesTest` / `EditShelvesLandingTest`)、`EditPress.kt`(`OkPressTest`)、`EditBackdrop.kt`(`EditBackdropTest`)、`EditShelfParts.kt`、`AppPicker.kt`(从 EditScreen 搬来);`EditScreen.kt` 重写;删 `addRowBelow`、`editFirstRow`(`EditScrollTest`)与行菜单文案。
- 解释过的 spec 空白:非焦点层胶囊透明度 0 但照常布局、可聚焦(效果图里非焦点层不露胶囊,但「卡片 → 下一层胶囊」要有落点);所有应用架子同高 140 dp(效果图非焦点层 118 dp,同高避免换层时整页跳);拿起 = 焦点放大 + highlight 描边 + 阴影 + 方向箭头,不再叠 1.08;删除落上一层**第一颗**胶囊(防连删);跨层上下也按水平中心取最近;货架不画卡片标题(效果图没有)。
- 验证:单测全过;模拟器 `unitedu-tv-2` 跑 `j_edit`、`j_overlays`(编辑页 6 个浮层)、`j_home`、`j_pkg` 全过;换层淡化 1× 录像逐帧看,焦点卡四边不被裁;帧时间(5 行 + 新的一行 + 模糊底,上下各 10 下):Total frames ___ / Janky ___ / 50th ___ / 90th ___ / 99th ___(模拟器,只作相对参照)。截图 `docs/screenshots/edit-shelves/`。
- 与效果图的差异:___(无则写「无」)。
- 仍待:电视上看(随下一个正式版);计划 2(频道行、`Move.kt` 跳过频道行、应用页「加到桌面」只列应用行)。
```

- [ ] **Step 3: 全量单测再跑一遍(文档改动不该影响,确认 CopyTest 等仍过)**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest 2>&1 | grep -E "FAILED|BUILD" | head -5`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git add CLAUDE.md docs/REVIEW-GUIDE.md README.md README.zh-CN.md docs/superpowers/specs/2026-09-20-gtv-line-design.md
git diff --stat HEAD -- docs/WORKLOG.md
```

WORKLOG 的提交规则(不用交互式 `git add -p`,本环境不支持):`git diff HEAD -- docs/WORKLOG.md` 若**只有**本任务追加的那一段(开工前上一段会话的改动已由主线提交),就 `git add docs/WORKLOG.md` 一起提交;若还混着上一段会话没提交的内容,本次不 add WORKLOG,在交付报告里写明「WORKLOG 已追加、留给主线与那段改动一并提交」。然后:

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
git commit -m "$(cat <<'EOF'
docs: 编辑桌面货架——焦点表、评审入口、README 双语、R165 状态、工作记录

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## 歧义的处理(实施时照此执行,不再出题)

1. **非焦点层不露胶囊、且比焦点层矮**(效果图 c1:非焦点层 118 dp、没有胶囊):所有应用架子同高 140 dp,胶囊每层都布局、都可聚焦,只是透明度跟着「这一层是不是焦点层」在 0 ↔ 1 之间 200 ms 过渡。理由:spec §2.2 要求「第 1 层卡片按下 → 第 2 层胶囊」,胶囊必须先存在才能被走到;同高免得换层时整页跳一下。
2. **拿起「放大 1.08 + 阴影」**:被拿起的卡此时正是焦点卡,已按首页样式放大 1.10;再乘 1.08 会到 1.19。实现为「焦点放大(不叠加)+ highlight 描边(`moving`)+ 150 ms 抬起阴影 + 方向箭头」。
3. **删除后「落上一层的胶囊」**取第一颗(「添加应用」),不取同位置的「删除」:空行删除不弹确认,停在「删除」上连按两下会连删两行。
4. **上移 / 下移后那颗胶囊没了**:落反方向那颗,再没有落「换图标」。
5. **「最近」的范围**:spec 只写层内卡片 ↔ 胶囊;跨层(卡片 → 下一层胶囊、胶囊 → 上一层卡片、进出新的一行)同样按水平中心取最近,没量到时落第一格。
6. **卡片标题**:效果图的架子里没有卡片标题,货架固定中档、固定高度;`showTitles` 不再传给编辑页。
7. **整页纵向位移的曲线**:spec 明写 200 ms `FastOutSlowIn`,替换旧编辑页的 R29 弹簧;行内横向位移仍读 `Theme.browseShiftSpec()`(与首页一致)。
8. **频道相关文案**:概况只写「N 行 · N 个应用」,「新的一行」下的说明只写「应用行最多 5 行」;计划 2 加频道时一并改。
9. **卡片菜单开着时按菜单键**:收掉菜单、焦点回这张卡(同所有应用页的约定)。
10. **初始焦点**:第 1 层第 1 张卡(第 1 层空则它的「添加应用」方块),与旧编辑页一致。
11. **卡片条的横向裁切**(owner 裁定,不是歧义):架子不裁焦点卡的描边 / 柔光,左边缘一律不裁(第 1 张卡的柔光画到架子外);横向只在屏幕边缘、以及越过架子右端的非焦点卡上裁(`clipPastShelfEnd`)。非焦点层整层淡化的离屏层四边撑大 60 dp(R129f),同样不截柔光。

## Self-Review(对照 spec 逐条)

**1. 覆盖**
- §2.1 页面结构:页头 + 概况 + 三个提示(Task 7 `EditKeyHints`、Task 8 `EditTopBar`);货架 40 dp / 20 dp / 焦点线 / 自算纵向位移(Task 2 `shelfScroll` + Task 8);应用架子顶行与五颗胶囊的出现条件(Task 1 `shelfChips`);固定中档、无行尾「+」、空架子方块(Task 7 `AddAppTile`、Task 8);新的一行虚线框 + 选择卡(Task 7 / 8)。频道架子 → 计划 2(扩展点写在 Task 1 文件头)。
- §2.2 焦点:固定顺序与最近映射(Task 1 `verticalStep`);账本、`ON_PAUSE` 冻结、逐项 requester、只信自报、看门狗(Task 8);动作后的落点(Task 2 `landing*` + Task 8)。
- §2.3 卡片操作:确定 = 拿起(Task 4 `OkPress` + Task 8 `carry`);长按 / 菜单键 = 卡片菜单两项(Task 8 + MainActivity 接线);菜单键不再退出(Task 8 Step 5);删除空行直接删、有应用弹确认默认取消(Task 8 `onChip`)。上下搬「跳过频道架子」:本计划没有频道行,`moveInLayout` 原样即正确;跳过逻辑属 §5 第 1 条,计划 2。
- §2.4 新的一行:追加到末尾(Task 3 `appendAppRow`)、落方块(Task 2 `landingAfterAppend`)、满 5 行变暗写「已满」不响应仍可聚焦(Task 1 `choiceFull` + Task 7 `ChoiceCard` + Task 8 `onChoice`)。
- §2.5 视觉与动效:模糊底(Task 5)、架子明暗与阴影(Task 2 `shelfLook` + Task 7 `ShelfFrame`)、胶囊(Task 7)、卡片焦点沿用首页(Task 8 用 `AppCard`)、200 ms / 150 ms(Task 2 常量 + Task 8)、`FadeSwitch` / `OverlayStack` 沿用(Task 8)。
- §2.6 不变的:入口 / 退出、即时落盘、三个选择页沿用、R67 口径(Task 8 原样保留 `persist` / `visibleRows` / `withVisibleEdits`)。
- §5:菜单键分支(Task 8 Step 5);`addRowBelow` 改为追加(Task 3 / 8)。其余三条(`moveCard` 跳过频道行、应用页「加到桌面」只列应用行、`pruneMissingPackages` 处理频道行)依赖频道数据,属计划 2。
- §6 焦点表:Task 11 Step 1。§7 测试:单测(Task 1–6、8)、e2e `j_edit` 重写(Task 9)、性能(Task 10 Step 5)。
- 仓库规则:三语文案(Task 6 / 8,CopyTest)、`TypeScaleTest`(零件只用 `Type`)、铁律源码扫描(Task 8 `EditIronRulesTest`)、R129f(Task 2 常量测试 + Task 7 + Task 10 Step 4)、模拟器隔离(Task 10 只用 unitedu-tv-2)、文档分流(Task 11)。

**2. 占位扫描**:全文没有 TBD / TODO / 「同 Task N」;WORKLOG 模板里的 `___` 是 Task 10 量出来的数,不是代码占位。

**3. 类型一致**:`ShelfSpot(shelf, zone, index)`、`ShelfChip.ADD_APP/ICON/UP/DOWN/DELETE`、`NewRowChoice.APP_ROW`、`landingOnChip/landingOnCard/landingAfterSwap/landingAfterDelete/landingAfterAppend`、`shelfScroll(heights, gap, focused, top, focusLine, viewport, bottomPad)`、`carryArrows(view, pos)`、`OkPress.owns/onDown/onUp`、`editBackdropPixels(...)`、`buildEditBackdrop` / `cachedEditBackdrop` / `Modifier.editBackdrop`、`ShelfFrame(focus, dashed, modifier, content)`、`ShelfChipPill(chip, visible, onClick, onFocusChange, isFirst, isLast, modifier)`、`ChoiceCard(choice, full, onClick, onFocusChange, isFirst, isLast, modifier)`、`AddAppTile(metrics, onClick, onFocusChange, modifier)`、`ShelfPendingCard(...)`、`EditKeyHints(set, modifier)`、`AppPicker(nonce, ctx, exclude, onPick, rowIcon)`、`EditScreen(onPickIcon, onExit, focusNonce, revision, wallpaperFile, initialTarget)` 在定义与使用处一致。

**4. Review Focus**:五条各有测试——1 `OkPressTest.upOfALongPressIsOwnedButNeverAShortPress`(+ e2e);2 `EditShelvesLandingTest.movingAShelfKeepsFocusOnTheSameChipOrItsOpposite`;3 `EditShelvesTest.clampKeepsTheNewRowOnTheNewRowWhenRowsChange`;4 `EditShelvesLandingTest.longRowSlidesSoTheFocusedCardStaysInsideTheShelf`;5 `EditBackdropTest.brightestWallpaperStillLeavesTextReadable` / `undecodableInputIsRejectedNotCrashed`。
