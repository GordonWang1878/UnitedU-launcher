# M4b 真机验收补丁 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉 A95L 真机验收报的两个问题:编辑页也能跨行搬卡(与首页同一套搬运模式,空行也是落点);多个调谐器输入只显示一张「电视」。

**Architecture:** 两个任务。Task 1 是纯函数 `mergeTuners` 接进首页输入源行的管线(`Inputs.load → dedupeCec → mergeTuners → applyInputPrefs`)。Task 2 给编辑页加搬运模式:纯函数 `moveInLayout`(对象是 layout.json 的行,空行合法、源行移空保留)+ 编辑页里的搬运状态、按键截获、复原,焦点全部走编辑页现成的 `retarget(ri, col)` 单一入口。

**Tech Stack:** Kotlin、Jetpack Compose(BOM 2024.10.01)、tv-material 1.0.0、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-09-19-m4b-rows-move-inputs-design.md` §0 第 18、19 行(Gordon 2026-09-20 真机验收后选定),以及同文件 §0-9(首页搬运的键位与取消规则,编辑页照搬)。

## Global Constraints

- 不做 UI 美化:编辑页搬运只复用首页已有的两样视觉——被搬卡片的 3dp accent 描边(`AppCard(moving = true)`)与底部提示 `home_move_hint`。
- `CLAUDE.md` 铁律 1–7 约束每一处 Compose 改动;编辑页是全应用焦点最脆的界面(看门狗、显式重定位、`ON_PAUSE` 冻结、浮层让路),搬运模式**不是浮层**,焦点始终在被搬的卡上,只通过现有的 `retarget(ri, col)` 移动。
- 模拟器命令一律 `adb -s emulator-5554`;**绝不向 A95L(192.168.1.22:38673)发任何东西**(装包由 controller 做)。
- 构建 + 单测:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`;单跑某类加 `--tests 'com.uniteduone.launcher.XxxTest'`。
- 三套字符串同步(values / values-zh-rTW / values-en,英文 Title Case);新字符串追加在各文件末尾的 `<!-- M4b -->` 块。
- 提交信息结尾:`Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`。

---

### Task 1: 多个调谐器合并成一张「电视」

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/InputPrefs.kt`(加 `mergeTuners`)、`app/src/main/java/com/uniteduone/launcher/HomeScreen.kt`(`buildInputRow` 的管线)
- Test: `app/src/test/java/com/uniteduone/launcher/InputPrefsTest.kt`

**Interfaces:**
- Produces: `internal fun mergeTuners(entries: List<InputEntry>): List<InputEntry>`。

- [ ] **Step 1: 写失败的测试**——在 `InputPrefsTest` 里追加:

```kotlin
    private fun tuner(id: String) = InputEntry(id = id, label = "电视", isPassthrough = false)

    @Test fun twoTunersBecomeOneAndTheDigitalOneStays() {
        // A95L 2026-09-20 实测:数字 DVB(HW0)+ 模拟(HW1)两个调谐器,系统都叫「电视」
        val dvb = tuner("com.sony.dtv.tvinput.dvbtuner/.DvbTvInputService/HW0")
        val atv = tuner("com.mediatek.tis/.AnalogInputService/HW1")
        val hdmi = e("HW2", "HDMI 1")
        assertEquals(listOf(hdmi, dvb), mergeTuners(listOf(hdmi, atv, dvb)))
    }

    @Test fun withoutAnAnalogHintTheSmallestIdStays() {
        val a = tuner("a/T1")
        val b = tuner("b/T2")
        assertEquals(listOf(a), mergeTuners(listOf(b, a)))
    }

    @Test fun oneOrNoTunerLeavesTheListAlone() {
        val one = listOf(e("HW2", "HDMI 1"), tuner("x/HW0"))
        assertSame(one, mergeTuners(one))
        val none = listOf(e("HW2", "HDMI 1"), e("HW3", "HDMI 2"))
        assertSame(none, mergeTuners(none))
    }
```

(import 补 `org.junit.Assert.assertSame`。)

- [ ] **Step 2: 跑测试确认失败**:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.InputPrefsTest'`,Expected:编译失败(`mergeTuners` 未定义)。

- [ ] **Step 3: 实现**——`InputPrefs.kt` 在 `dedupeCec` 之后加:

```kotlin
/**
 * 多个调谐器合并成一张卡(M4b spec §0-19,Gordon 2026-09-20):点调谐器卡打开的是系统通用的频道列表,
 * 不分是哪一个调谐器(见 [Inputs.launch]),所以两张「电视」效果完全一样。只留一个:id 里不带 analog 的优先
 * (A95L 上是索尼的 DVB 数字调谐器),同类按 id 取第一个——规则固定,改名 / 隐藏记在它的 id 上不会漂。
 * 透传输入(HDMI 等)原样保留,顺序不变;只有一个或没有调谐器时原样返回同一个 list。
 */
internal fun mergeTuners(entries: List<InputEntry>): List<InputEntry> {
    val tuners = entries.filter { !it.isPassthrough }
    if (tuners.size <= 1) return entries
    val keep = tuners.sortedWith(
        compareBy<InputEntry>({ it.id.contains("analog", ignoreCase = true) }, { it.id })
    ).first()
    return entries.filter { it.isPassthrough || it.id == keep.id }
}
```

`HomeScreen.buildInputRow` 里 `applyInputPrefs(dedupeCec(Inputs.load(ctx)), …)` 改成 `applyInputPrefs(mergeTuners(dedupeCec(Inputs.load(ctx))), …)`,KDoc 补一句「多个调谐器只留一张,见 mergeTuners」。

- [ ] **Step 4: 跑测试确认通过**:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`,Expected:BUILD SUCCESSFUL,`InputPrefsTest` 8 个用例全过,全部单测 0 失败。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/uniteduone/launcher/InputPrefs.kt app/src/main/java/com/uniteduone/launcher/HomeScreen.kt app/src/test/java/com/uniteduone/launcher/InputPrefsTest.kt
git commit -m "fix(m4b): several TV tuners show as one card

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

(模拟器没有硬件输入源,本任务只能单测 + A95L 复验:输入源行里只剩一张「电视」。)

---

### Task 2: 编辑页搬运模式

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Move.kt`(加 `moveInLayout`)、`app/src/main/java/com/uniteduone/launcher/EditScreen.kt`(卡片菜单、搬运状态、按键、渲染、取消)、视需要 `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(搬运中 MENU 等不响应)、三套 `strings.xml`、`CLAUDE.md`(焦点责任表「编辑页」一行)、`docs/DESIGN-unitedu-open-source.md`(§2 移动位置)
- Test: `app/src/test/java/com/uniteduone/launcher/MoveTest.kt`

**Interfaces:**
- Consumes: `MovePos`、`MoveDir`(Move.kt)、`LayoutRow`、`AppCard(moving = true)`、字符串 `home_move_hint`、`card_menu_move`。
- Produces: `internal fun moveInLayout(rows: List<LayoutRow>, pos: MovePos, dir: MoveDir): Pair<List<LayoutRow>, MovePos>`。

- [ ] **Step 1: 写失败的测试**——在 `MoveTest` 里追加:

```kotlin
    private val layout = listOf(
        LayoutRow("VIDEO", apps = listOf("a", "b", "c")),
        LayoutRow("NEW"),                       // 编辑页里新建的空行
        LayoutRow("MUSIC", apps = listOf("d")),
    )

    @Test fun editLeftRightSwapAndStopAtTheEnds() {
        val (r, p) = moveInLayout(layout, MovePos(0, 1), MoveDir.RIGHT)
        assertEquals(listOf("a", "c", "b"), r[0].apps); assertEquals(MovePos(0, 2), p)
        val (r2, p2) = moveInLayout(layout, MovePos(0, 0), MoveDir.LEFT)
        assertSame(layout, r2); assertEquals(MovePos(0, 0), p2)
    }

    @Test fun editDownLandsInAnEmptyRow() {
        val (r, p) = moveInLayout(layout, MovePos(0, 2), MoveDir.DOWN)
        assertEquals(listOf("a", "b"), r[0].apps)
        assertEquals(listOf("c"), r[1].apps)
        assertEquals(MovePos(1, 0), p)
    }

    @Test fun editEmptiedSourceRowStays() {
        val (r, p) = moveInLayout(layout, MovePos(2, 0), MoveDir.UP)
        assertEquals(3, r.size)
        assertEquals(listOf("d"), r[1].apps)
        assertEquals(emptyList<String>(), r[2].apps)
        assertEquals(MovePos(1, 0), p)
    }

    @Test fun editUpIntoARowThatHasTheAppIsANoOp() {
        val rows = listOf(LayoutRow("A", apps = listOf("x", "y")), LayoutRow("B", apps = listOf("y")))
        val (r, p) = moveInLayout(rows, MovePos(1, 0), MoveDir.UP)
        assertSame(rows, r); assertEquals(MovePos(1, 0), p)
    }

    @Test fun editNoRowInThatDirectionIsANoOp() {
        val (r, p) = moveInLayout(layout, MovePos(0, 0), MoveDir.UP)
        assertSame(layout, r); assertEquals(MovePos(0, 0), p)
        val (r2, p2) = moveInLayout(layout, MovePos(2, 0), MoveDir.DOWN)
        assertSame(layout, r2); assertEquals(MovePos(2, 0), p2)
    }

    @Test fun editColumnClampsToTheShorterRow() {
        val rows = listOf(LayoutRow("A", apps = listOf("a", "b", "c")), LayoutRow("B", apps = listOf("d")))
        val (r, p) = moveInLayout(rows, MovePos(0, 2), MoveDir.DOWN)
        assertEquals(listOf("d", "c"), r[1].apps)   // 第 2 列越过 B 的行尾 → 放在行尾
        assertEquals(MovePos(1, 1), p)
    }
```

- [ ] **Step 2: 跑测试确认失败**:`--tests 'com.uniteduone.launcher.MoveTest'`,Expected:编译失败(`moveInLayout` 未定义)。

- [ ] **Step 3: 实现纯函数**——`Move.kt` 末尾加:

```kotlin
/**
 * 编辑页搬卡一步(M4b spec §0-18,Gordon 2026-09-20):与首页 [moveCard] 同一套键位,对象是 layout.json 的行——
 * 空行也是合法落点(编辑页显示空行),源行被移空照样保留;编辑页没有输入源行。左右:与同行邻卡换位,到头不动。
 * 上下:落到相邻行的同一列,越过行尾放行尾;相邻行已有同一个包 → 不动(同首页 Ruling M4b-R16);那个方向没有行 → 不动。
 * 不动时返回同一个 list 与原位置。
 */
internal fun moveInLayout(rows: List<LayoutRow>, pos: MovePos, dir: MoveDir): Pair<List<LayoutRow>, MovePos> {
    val src = rows.getOrNull(pos.row) ?: return rows to pos
    if (pos.col !in src.apps.indices) return rows to pos
    return when (dir) {
        MoveDir.LEFT, MoveDir.RIGHT -> {
            val to = if (dir == MoveDir.LEFT) pos.col - 1 else pos.col + 1
            if (to !in src.apps.indices) return rows to pos
            val apps = src.apps.toMutableList().apply { val t = this[pos.col]; this[pos.col] = this[to]; this[to] = t }
            rows.mapIndexed { i, r -> if (i == pos.row) r.copy(apps = apps) else r } to pos.copy(col = to)
        }
        MoveDir.UP, MoveDir.DOWN -> {
            val t = pos.row + if (dir == MoveDir.UP) -1 else 1
            val target = rows.getOrNull(t) ?: return rows to pos
            val pkg = src.apps[pos.col]
            if (pkg in target.apps) return rows to pos
            val col = pos.col.coerceAtMost(target.apps.size)
            rows.mapIndexed { i, r ->
                when (i) {
                    pos.row -> r.copy(apps = r.apps.filterIndexed { c, _ -> c != pos.col })
                    t -> r.copy(apps = r.apps.toMutableList().apply { add(col, pkg) })
                    else -> r
                }
            } to MovePos(t, col)
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**:`--tests 'com.uniteduone.launcher.MoveTest'`,Expected:原有 10 个 + 新 6 个全过。

- [ ] **Step 5: 接线(按铁律逐条)**
  - **菜单**:编辑页卡片(acting)菜单删掉「往左移 / 往右移」两项,在原位置换成一项「移动位置」(复用 `card_menu_move`;说明文字新写 `edit_move_desc`:在编辑页里用方向键挪这张卡,空行也能去 / 在編輯頁裡用方向鍵挪這張卡,空行也能去 / Move this card with the arrow keys, empty rows included)。`edit_move_left*` / `edit_move_right*` 四条字符串若已无引用就删掉(三套同删)。
  - **状态**:编辑页新增 `carry: MovePos?`(被搬的卡,按位置追踪)与 `carryOriginal: List<LayoutRow>?`(进入时的整份 `rows`,取消时复原)。进入 = 关菜单 → 记下 original → `carry = MovePos(ri, pi)` → `retarget(ri, pi)`。搬运中 `rows` 实时更新(画面跟着动),**不写盘**。
  - **按键**:搬运中由编辑页根节点的 `onPreviewKeyEvent` 截获(不进 Compose 焦点搜索):方向键 `KeyDown` → `moveInLayout` → 更新 `rows` 与 `carry` → `retarget(新位置)`;方向键 `KeyUp` 吞掉;确定键(DPAD_CENTER / ENTER)**松开**时放下 = `persist()` + `carry = null` + `carryOriginal = null`(焦点留在原地),按住超过 600 ms 的长按不放下也不做别的(与首页 M4b-R14 同规则,用 `KeyEvent.nativeKeyEvent.repeatCount` / 按下时刻判断);其它键吞掉。返回键:搬运中注册一个更晚的 `BackHandler`(优先级高于编辑页自己的),取消 = `rows = carryOriginal` + 清两个状态 + `retarget(出发位置)`,不写盘。若 `MainActivity.dispatchKeyEvent` 在编辑页时会抢 MENU 等键,搬运中让它不响应(给 MainActivity 一个「编辑页正在搬运」的状态或回调,守卫与 key 成对)。
  - **取消的其它入口**:编辑页收到 `ON_PAUSE`、离开编辑页、任何浮层要打开时,若在搬运 → 取消(同返回键)。
  - **渲染**:被搬的卡 `AppCard(moving = true)`;若被搬的是 `MissingCard`(未安装的占位),给它同样的 3dp accent 描边(加同名参数,最小改动)。搬运中编辑页底部显示 `home_move_hint`(沿用首页提示条的样式与位置)。
  - **焦点账本**:搬运不是浮层——不并入 `overlayOpen`;看门狗与显式重定位照常工作,目标由 `retarget` 设成被搬卡的位置;搬运中焦点报告不得把 `focusRow/focusTarget` 改到别处(沿用编辑页 `retargeting` 冻结的写法)。守卫出现在哪个效果里,就进那个效果的 key(铁律 6)。
- [ ] **Step 6: 文档**——`CLAUDE.md` 焦点责任表「编辑页」一行补一句「搬运模式(M4b 补丁)不是浮层:焦点始终在被搬的卡上,每一步走 `retarget`,返回 / `ON_PAUSE` / 离开编辑页取消并复原」;`docs/DESIGN-unitedu-open-source.md` §2「移动位置」一条补「编辑页里同一套搬运,空行也是落点」。
- [ ] **Step 7: 构建 + 模拟器验证**(0.4 s 间隔、每步截图 + `uiautomator` 焦点数与 bounds;先备份、测完还原 `layout.json`):
  1. 编辑页用行菜单新建一个空行;回到 VIDEO 行某张卡 → 确定 → 菜单里只有「移动位置 / 换卡片图 / 移出」这类项,没有左移右移 → 选「移动位置」:描边变 accent、底部出提示。
  2. ↓ 一次:卡落进空行第 0 列,原行少一张;确定(短按松开):`layout.json` 与屏幕一致;回首页:新行出现且有这张卡。
  3. 再进搬运 → ← → ↑ ↓ 若干步 → 返回:屏幕与 `layout.json` 都回到原样,焦点回出发格。
  4. 把某行唯一一张卡搬走:该行在编辑页里变成空行(还在),确定后首页不显示空行。
  5. 往已有同一应用的行里 ↑/↓:不动。
  6. 搬运中拉系统设置侧板(或 `input keyevent KEYCODE_SLEEP` 再唤醒):回来已取消、复原。
  7. 搬运中长按确定:不放下、无反应;松开后再短按一次确定才放下。
  8. 全程每步焦点数恒 1 且在被搬的卡上。
- [ ] **Step 8: 提交**

```bash
git add -A app/src CLAUDE.md docs/DESIGN-unitedu-open-source.md
git commit -m "feat(m4b): carry a card across rows in the edit screen, empty rows included

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
