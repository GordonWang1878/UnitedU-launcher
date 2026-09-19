# M4b 行管理 · 原地移动 · 输入源 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 应用行 1–5 行可增删 / 改名 / 换图标 / 上下移;首页长按「移动位置」原地搬卡;输入源可改名 / 隐藏 / 一键恢复;HDMI-CEC 父子输入去重。

**Architecture:** 先把数据层做成纯函数(`LayoutRow` + 行操作、行图标 id、输入源偏好、CEC 去重、搬卡与写回合并),全部 JVM 单测;再在既有界面上接线:输入源卡长按菜单与设置页一键恢复 → 编辑页行菜单(入口是行尾「+」)与图标选择器 → 首页移动态。每个新浮层自己负责焦点(铁律 3),首页移动态的按键在 `MainActivity.dispatchKeyEvent` 截获,焦点靠首页现成的「目标格 + nonce」还原效果落地。

**Tech Stack:** Kotlin、Jetpack Compose(BOM 2024.10.01)、androidx.tv:tv-material 1.0.0、material-icons-extended、JUnit 4、org.json(仅 Android 路径)。

**Spec:** `docs/superpowers/specs/2026-09-19-m4b-rows-move-inputs-design.md`(§0 决定表 + Rulings A–M 是本计划的约束)。

## Global Constraints

- **不做 UI 美化**(Gordon 2026-09-19):只画功能必需的新界面元素;现有界面的颜色、尺寸、字体、间距、动效不动。移动态只加 3dp accent 描边 + 底部提示条(spec §0-10)。
- `CLAUDE.md` 铁律 1–7 约束每一处 Compose 改动:不用任何可滚动容器;位移自己算(`offset` + `animateDpAsState` + `wrapContentWidth/Height(unbounded = true)`);焦点是否落下只信目标自报;每个浮层自己负责焦点恢复(新浮层必须写进 CLAUDE.md 的焦点责任表);守卫与 key 成对;不用一次性布尔闩;目标与当前位置分开、从 `ON_PAUSE` 冻结。
- 应用行 1–5 行(`MIN_ROWS = 1`、`MAX_ROWS = 5`);行名清空 = 不改,上限 `MAX_TITLE_CHARS` = 40;新建行默认图标 `apps`、默认名取字符串资源 `edit_new_row_name`(简体「新行」、繁體「新行」、English「New Row」)。
- 行图标 id 恰好 12 个,顺序:`movie tv live music games kids tools education sports news photos apps`;没有 `icon` 字段的旧行按名字回落(VIDEO → movie、LIVE → tv、MUSIC → music,其余 tv),外观与今天逐像素一致。
- 输入源名字存 `titles.json`(key = 输入 id);隐藏存 `hidden-inputs.json`(`{"<id>":"hidden"}`);「恢复默认」不碰这两个文件。
- 模拟器命令一律 `adb -s emulator-5554`;**绝不向 A95L(192.168.1.22:38673)发按键 / 装包 / 改设置**。模拟器没有硬件输入,输入源相关只能单测 + 真机清单。
- 构建 + 单测:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`;单跑某类加 `--tests 'com.uniteduone.launcher.XxxTest'`。
- 三套字符串同步:`values`(简体)、`values-zh-rTW`、`values-en`(英文设置 / 菜单项用 Title Case)。
- 提交信息结尾:`Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`。

---

### Task 1: `LayoutRow` + 行操作纯函数 + 行图标 id

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/LayoutOps.kt`、`app/src/main/java/com/uniteduone/launcher/RowIcons.kt`
- Modify: `Layout.kt`(`DEFAULT_LAYOUT`、`read`、`write`、`removeFromRow`、`withoutPackage`)、`Model.kt`(`Row` 加 `icon`)、`RowIcon.kt`、`HomeScreen.kt`(`buildRows`、`CategoryRow` 里的 `RowIcon` 调用)、`EditScreen.kt`(`rows` 状态类型与所有 `.first/.second` 用法)、`OnboardingPure.kt`、`Onboarding.kt:530-559`
- Test: `app/src/test/java/com/uniteduone/launcher/LayoutOpsTest.kt`、`RowIconsTest.kt`(新建);`LayoutTest.kt`、`OnboardingPureTest.kt`(跟着换类型)

**Interfaces:**
- Produces: `data class LayoutRow(val name: String, val icon: String? = null, val apps: List<String> = emptyList())`;`Layout.read(ctx): List<LayoutRow>`、`Layout.write(ctx, rows: List<LayoutRow>): Boolean`;`MIN_ROWS`、`MAX_ROWS`、`addRowBelow`、`deleteRow`、`renameRow`、`setRowIcon`、`swapRows`;`ROW_ICON_IDS`、`NEW_ROW_ICON`、`isRowIconId`、`legacyRowIconId`、`effectiveRowIconId`;`rowIconVector(id): ImageVector`(RowIcon.kt);`Row(…, icon: String? = null)`;`RowIcon(name, kind, icon: String? = null, tint)`。

- [ ] **Step 1: 写失败的测试**——新建 `RowIconsTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowIconsTest {
    @Test fun twelveIdsInPickerOrder() {
        assertEquals(
            listOf("movie", "tv", "live", "music", "games", "kids", "tools", "education", "sports", "news", "photos", "apps"),
            ROW_ICON_IDS,
        )
        assertEquals("apps", NEW_ROW_ICON)
    }

    @Test fun unknownOrNullIdIsNotAnIcon() {
        assertTrue(isRowIconId("movie"))
        assertFalse(isRowIconId("MOVIE"))
        assertFalse(isRowIconId(""))
        assertFalse(isRowIconId(null))
    }

    @Test fun legacyNamesKeepTodaysIcons() {
        assertEquals("movie", legacyRowIconId("VIDEO"))
        assertEquals("movie", legacyRowIconId("video"))
        assertEquals("tv", legacyRowIconId("LIVE"))
        assertEquals("music", legacyRowIconId("MUSIC"))
        assertEquals("tv", legacyRowIconId("影视"))
    }

    @Test fun storedIdWinsOverTheName() {
        assertEquals("games", effectiveRowIconId("VIDEO", "games"))
        assertEquals("movie", effectiveRowIconId("VIDEO", null))
        assertEquals("movie", effectiveRowIconId("VIDEO", "bogus"))
    }
}
```

新建 `LayoutOpsTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LayoutOpsTest {
    private val three = listOf(
        LayoutRow("VIDEO", apps = listOf("a", "b")),
        LayoutRow("LIVE", icon = "tv", apps = listOf("c")),
        LayoutRow("MUSIC"),
    )

    @Test fun addInsertsAnEmptyRowBelowWithTheDefaultIcon() {
        val next = addRowBelow(three, 0, "New Row")
        assertEquals(listOf("VIDEO", "New Row", "LIVE", "MUSIC"), next.map { it.name })
        assertEquals(LayoutRow("New Row", icon = "apps"), next[1])
    }

    @Test fun addStopsAtFiveRowsAndOnBadIndex() {
        val five = three + LayoutRow("X") + LayoutRow("Y")
        assertSame(five, addRowBelow(five, 0, "Z"))
        assertSame(three, addRowBelow(three, 3, "Z"))
        assertSame(three, addRowBelow(three, -1, "Z"))
    }

    @Test fun deleteRemovesButNeverTheLastRow() {
        assertEquals(listOf("VIDEO", "MUSIC"), deleteRow(three, 1).map { it.name })
        val one = listOf(LayoutRow("ONLY", apps = listOf("a")))
        assertSame(one, deleteRow(one, 0))
        assertSame(three, deleteRow(three, 5))
    }

    @Test fun renameTrimsAndRejectsBlank() {
        assertEquals("影视", renameRow(three, 0, "  影视 ")[0].name)
        assertSame(three, renameRow(three, 0, "   "))
        assertSame(three, renameRow(three, 9, "X"))
        assertEquals(MAX_TITLE_CHARS, renameRow(three, 0, "x".repeat(100))[0].name.length)
    }

    @Test fun setIconAcceptsOnlyKnownIds() {
        assertEquals("games", setRowIcon(three, 2, "games")[2].icon)
        assertSame(three, setRowIcon(three, 2, "bogus"))
        assertSame(three, setRowIcon(three, 7, "games"))
    }

    @Test fun swapMovesRowsAndKeepsTheirContent() {
        val next = swapRows(three, 0, 1)
        assertEquals(listOf("LIVE", "VIDEO", "MUSIC"), next.map { it.name })
        assertEquals(listOf("a", "b"), next[1].apps)
        assertSame(three, swapRows(three, 0, 3))
        assertSame(three, swapRows(three, 1, 1))
    }
}
```

`LayoutTest.kt` 的 fixture 改成 `LayoutRow`(`LayoutRow("VIDEO", apps = listOf("com.a", "com.b"))` 等),断言里的 `.second` 换 `.apps`、`.first` 换 `.name`;`OnboardingPureTest.kt` 同样替换(`DEFAULT_LAYOUT.map { it.name }`、`it.apps.first()`、`sumOf { it.apps.size }`),断言的值不变。

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.RowIconsTest' --tests 'com.uniteduone.launcher.LayoutOpsTest'`
Expected: 编译失败(`LayoutRow` / `ROW_ICON_IDS` 等未定义)。

- [ ] **Step 3: 实现数据层**

新建 `RowIcons.kt`:

```kotlin
package com.uniteduone.launcher

/** 行图标 id(DESIGN §2「约 12 个内置 Material 图标」,M4b spec §0-6)。顺序 = 选择器里的顺序。 */
internal val ROW_ICON_IDS = listOf(
    "movie", "tv", "live", "music", "games", "kids",
    "tools", "education", "sports", "news", "photos", "apps",
)

/** 新建行的默认图标。 */
internal const val NEW_ROW_ICON = "apps"

internal fun isRowIconId(id: String?): Boolean = id != null && id in ROW_ICON_IDS

/** 没存图标的旧行按名字匹配——与 M4b 之前 RowIcon 的 when 逐条对应,外观不变;其余一律 tv。 */
internal fun legacyRowIconId(name: String): String = when (name.uppercase()) {
    "VIDEO" -> "movie"
    "LIVE" -> "tv"
    "MUSIC" -> "music"
    else -> "tv"
}

/** 这一行最终用哪个图标 id:存了合法 id 就用它,否则按名字回落。 */
internal fun effectiveRowIconId(name: String, icon: String?): String =
    if (icon != null && isRowIconId(icon)) icon else legacyRowIconId(name)
```

新建 `LayoutOps.kt`:

```kotlin
package com.uniteduone.launcher

/** 应用行数上下限(DESIGN §2:1–5 行)。 */
internal const val MIN_ROWS = 1
internal const val MAX_ROWS = 5

/** 在第 [index] 行下方插一个空行(默认图标 [NEW_ROW_ICON]);已满 [MAX_ROWS] 行或越界 → 原样返回同一个 list。 */
internal fun addRowBelow(rows: List<LayoutRow>, index: Int, name: String): List<LayoutRow> {
    if (rows.size >= MAX_ROWS || index !in rows.indices) return rows
    return rows.toMutableList().apply { add(index + 1, LayoutRow(name = name, icon = NEW_ROW_ICON)) }
}

/** 删第 [index] 行;只剩 [MIN_ROWS] 行或越界 → 原样返回。行里的应用只是离开桌面,不卸载。 */
internal fun deleteRow(rows: List<LayoutRow>, index: Int): List<LayoutRow> {
    if (rows.size <= MIN_ROWS || index !in rows.indices) return rows
    return rows.filterIndexed { i, _ -> i != index }
}

/** 改名:与卡片标题同一套清洗([sanitizeTitle]:去首尾空白、截到 [MAX_TITLE_CHARS]);清洗后为空或越界 → 原样返回(行必须有名字)。 */
internal fun renameRow(rows: List<LayoutRow>, index: Int, name: String): List<LayoutRow> {
    val clean = sanitizeTitle(name)
    if (clean.isEmpty() || index !in rows.indices) return rows
    return rows.mapIndexed { i, r -> if (i == index) r.copy(name = clean) else r }
}

/** 换图标;不认识的 id 或越界 → 原样返回。 */
internal fun setRowIcon(rows: List<LayoutRow>, index: Int, icon: String): List<LayoutRow> {
    if (!isRowIconId(icon) || index !in rows.indices) return rows
    return rows.mapIndexed { i, r -> if (i == index) r.copy(icon = icon) else r }
}

/** 交换两行(上移 / 下移);任一越界或两者相同 → 原样返回。 */
internal fun swapRows(rows: List<LayoutRow>, a: Int, b: Int): List<LayoutRow> {
    if (a !in rows.indices || b !in rows.indices || a == b) return rows
    return rows.toMutableList().apply { val t = this[a]; this[a] = this[b]; this[b] = t }
}
```

(若 `sanitizeTitle` 除了去空白与截断还做了别的清洗,以它为准;测试里的四个断言必须仍然成立,不成立就在报告里写明 `sanitizeTitle` 的实际行为并改用 `truncateTitle(name.trim())`。)

`Layout.kt`:在 `DEFAULT_LAYOUT` 之前加

```kotlin
/** layout.json 的一行(M4b):名字、可选的图标 id(见 RowIcons.kt;null = 按名字回落)、有序的包名。 */
data class LayoutRow(val name: String, val icon: String? = null, val apps: List<String> = emptyList())
```

`DEFAULT_LAYOUT` 改成 `List<LayoutRow>`(三行 `LayoutRow("VIDEO", apps = listOf(…))` 等,包名与顺序一字不改,icon 为 null);`read` 的每行改成

```kotlin
                val r = rows.getJSONObject(i)
                val apps = r.getJSONArray("apps")
                LayoutRow(
                    name = r.getString("name"),
                    // 缺失 / 非法 id 一律 null,渲染时按名字回落(老文件原样可读)
                    icon = r.optString("icon", "").takeIf { isRowIconId(it) },
                    apps = (0 until apps.length())
                        .map { apps.getString(it).trim() }
                        .filter { it.isNotEmpty() }
                        .distinct(),
                )
```

`write` 里每行改成 `arr.put(JSONObject().put("name", row.name).also { o -> row.icon?.let { o.put("icon", it) } }.put("apps", JSONArray(row.apps)))`(icon 为 null 不写,老数据原样往返);`removeFromRow`、`withoutPackage` 换成 `LayoutRow` 的 `apps` / `copy(apps = …)`,行为不变;所有签名里的 `List<Pair<String, List<String>>>` 换 `List<LayoutRow>`。

`Model.kt` 的 `Row` 加字段 `val icon: String? = null`(放在 `kind` 之前,KDoc:「layout.json 里存的图标 id;null = 按名字回落,见 RowIcons.kt。输入源行不用」),并把 KDoc 里「应用行名字固定(VIDEO / LIVE / MUSIC)」改成「应用行名字与图标来自 layout.json(M4b 起可在编辑页改)」。

`RowIcon.kt`:签名改成 `fun RowIcon(name: String, kind: RowKind = RowKind.APPS, icon: String? = null, tint: …)`,`else when (name.uppercase()) { … }` 那段换成 `else rowIconVector(effectiveRowIconId(name, icon))`,并在同文件加:

```kotlin
/** 行图标 id → 矢量图。movie / tv / music 三个与 M4b 之前按名字匹配的图完全相同(外观不变)。 */
internal fun rowIconVector(id: String): ImageVector = when (id) {
    "movie" -> Icons.Filled.Theaters
    "tv" -> Icons.Outlined.Tv
    "live" -> Icons.Outlined.LiveTv
    "music" -> Icons.Filled.MusicNote
    "games" -> Icons.Outlined.SportsEsports
    "kids" -> Icons.Outlined.ChildCare
    "tools" -> Icons.Outlined.Build
    "education" -> Icons.Outlined.School
    "sports" -> Icons.Outlined.FitnessCenter
    "news" -> Icons.Outlined.Newspaper
    "photos" -> Icons.Outlined.PhotoLibrary
    else -> Icons.Outlined.Apps   // "apps"
}
```

(补对应的 `androidx.compose.material.icons.outlined.*` / `filled.*` 与 `androidx.compose.ui.graphics.vector.ImageVector` import;某个图标在 `material-icons-extended` 里没有就编译失败——换成同义的另一个 outlined 图标并在报告里写明。)

`HomeScreen.buildRows`:`layout.mapIndexed { layoutIndex, row -> Row(name = row.name, icon = row.icon, apps = row.apps.mapNotNull { all[it] }, layoutRow = layoutIndex) }`,`needed` 用 `layout.flatMap { it.apps }`;`CategoryRow` 里 `RowIcon(row.name, row.kind, tint = accent)` 改成 `RowIcon(row.name, row.kind, row.icon, tint = accent)`。

`EditScreen.kt`:`rows` 状态改成 `List<LayoutRow>`(`Layout.read(ctx)` 原样,不再转 `Pair`);所有 `rows[ri].second` → `rows[ri].apps`、`r.first to …` → `r.copy(apps = …)`、`forEachIndexed { ri, (name, pkgs) -> … }` → `forEachIndexed { ri, row -> val name = row.name; val pkgs = row.apps; … }`;`persist()` 直接写 `rows`。行为不变(本任务不加任何编辑页功能)。

`OnboardingPure.kt`:`plannedLayout` → `default.map { it.copy(apps = it.apps.filter { p -> p in installed }) }`;`skippedLayout` → `default.map { it.copy(apps = emptyList()) }`;`planView` 的参数换 `List<LayoutRow>`,内部 `planned.mapNotNull { row -> if (row.apps.isEmpty()) null else row.name to row.apps.map { … } }`,返回类型不变。`Onboarding.kt:530` 的 `DEFAULT_LAYOUT.flatMap { it.second }` → `it.apps`。KDoc 里「行名 → 该行的包名」改成「`LayoutRow`」。

- [ ] **Step 4: 跑测试确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL;新增两个测试类全过,原有全部单测 0 失败。

- [ ] **Step 5: 模拟器冒烟**——装包后首页三行的行标题图标与改前逐像素一致(改前先 `adb -s emulator-5554 exec-out screencap -p > /tmp/m4b-t1-before.png` 截基线);`adb -s emulator-5554 shell cat /sdcard/Android/data/com.uniteduone.launcher/files/layout.json`(路径以 `Paths.layoutJson` 为准)在编辑页挪一张卡之后仍是合法 JSON、没有凭空多出 `"icon"` 字段。

- [ ] **Step 6: 提交**

```bash
git add -A app/src
git commit -m "feat(m4b): LayoutRow with an optional icon id; pure row operations; row icons by id with the legacy name fallback

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: 输入源数据——CEC 去重、隐藏列表、名字

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/InputPrefs.kt`
- Modify: `Inputs.kt`(`InputEntry` 加 `parentId`,`load` 读 `info.parentId`,删掉 KDoc 里「⚠️ 未做父子去重」那段、改写成「去重见 InputPrefs.dedupeCec」)、`Paths.kt`(加 `hiddenInputsJson` / `hiddenInputsBad`)、`HomeScreen.kt`(`buildInputRow`)
- Test: `app/src/test/java/com/uniteduone/launcher/InputPrefsTest.kt`(新建)

**Interfaces:**
- Produces: `InputEntry(id, label, isPassthrough, parentId: String? = null)`;`dedupeCec(entries): List<InputEntry>`;`applyInputPrefs(entries, hidden: Set<String>, names: Map<String, String>): List<InputEntry>`;`parseHiddenInputs(text): Set<String>`、`hiddenInputsToJson(ids): String`;`object HiddenInputs { fun read(ctx): Set<String>; fun write(ctx, ids: Set<String>): Boolean; fun set(ctx, id: String, hidden: Boolean): Boolean; fun clear(ctx): Boolean }`。

- [ ] **Step 1: 写失败的测试**——新建 `InputPrefsTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class InputPrefsTest {
    private fun e(id: String, label: String = id, parent: String? = null) =
        InputEntry(id = id, label = label, isPassthrough = true, parentId = parent)

    @Test fun aPortWithACecDeviceShowsOnlyTheDevice() {
        val hdmi1 = e("HW2", "HDMI 1")
        val ps5 = e("HDMI4008", "PlayStation 5", parent = "HW2")
        val hdmi2 = e("HW3", "HDMI 2")
        assertEquals(listOf(ps5, hdmi2), dedupeCec(listOf(hdmi1, ps5, hdmi2)))
    }

    @Test fun twoDevicesOnOnePortBothStay() {
        val port = e("HW4")
        val avr = e("HDMI1", "AV Receiver", parent = "HW4")
        val player = e("HDMI2", "Player", parent = "HW4")
        assertEquals(listOf(avr, player), dedupeCec(listOf(port, avr, player)))
    }

    @Test fun withoutCecNothingChanges() {
        // A95L 2026-09-19 实测形态:只有端口,没有子输入
        val list = listOf(e("HW0", "TV"), e("HW1", "AV"), e("HW2", "HDMI 1"), e("HW3", "HDMI 2"))
        assertEquals(list, dedupeCec(list))
    }

    @Test fun hiddenGoAndNamesReplaceLabels() {
        val list = listOf(e("a", "HDMI 1"), e("b", "HDMI 2"), e("c", "HDMI 3"))
        val out = applyInputPrefs(list, hidden = setOf("b"), names = mapOf("c" to "游戏机", "zzz" to "无关"))
        assertEquals(listOf("a" to "HDMI 1", "c" to "游戏机"), out.map { it.id to it.label })
    }

    @Test fun hiddenListRoundTripsThroughTheTitlesFormat() {
        val ids = setOf("com.mediatek.external/.HdmiInputService/HW3", "HW0")
        assertEquals(ids, parseHiddenInputs(hiddenInputsToJson(ids)))
        assertEquals(emptySet<String>(), parseHiddenInputs("{}"))
        assertEquals(emptySet<String>(), parseHiddenInputs("not json"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.InputPrefsTest'`
Expected: 编译失败(`parentId` / `dedupeCec` 等未定义)。

- [ ] **Step 3: 实现**——`Inputs.kt`:`InputEntry` 加 `val parentId: String? = null`(KDoc:「HDMI-CEC 子设备所在端口的输入 id([TvInputInfo.getParentId]);端口本身为 null」);`load` 里构造时加 `parentId = info.parentId`。`Paths.kt` 在 `titlesBad` 之后加:

```kotlin
    fun hiddenInputsJson(ctx: Context) = File(base(ctx), "hidden-inputs.json")
    fun hiddenInputsBad(ctx: Context) = File(base(ctx), "hidden-inputs.json.bad")
```

新建 `InputPrefs.kt`:

```kotlin
package com.uniteduone.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * HDMI-CEC 父子去重(M4b spec §1.3 / Ruling M):某个输入若是任何其它输入的 parentId,就不列它,
 * 只列它下面的子设备(「PlayStation 5」比「HDMI 1」有用)。只看一层;顺序不变。
 */
internal fun dedupeCec(entries: List<InputEntry>): List<InputEntry> {
    val parents = entries.mapNotNull { it.parentId }.toSet()
    return entries.filter { it.id !in parents }
}

/** 先去掉隐藏的,再把改过名的换成用户起的名字(名字来自 titles.json,key = 输入 id)。 */
internal fun applyInputPrefs(
    entries: List<InputEntry>,
    hidden: Set<String>,
    names: Map<String, String>,
): List<InputEntry> =
    entries.filter { it.id !in hidden }.map { e -> names[e.id]?.let { e.copy(label = it) } ?: e }

/** hidden-inputs.json = {"<输入 id>":"hidden", …}:复用 titles.json 的纯函数解析 / 序列化(JVM 可测)。 */
internal fun parseHiddenInputs(text: String): Set<String> = parseTitles(text).keys

internal fun hiddenInputsToJson(ids: Set<String>): String = titlesToJson(ids.associateWith { "hidden" })

/** 读写照 [Titles]:外置没挂 = 空集;缺文件 = 空集;坏文件改名 .bad + 写空 + Log.w;写走 tmp → fsync → rename。 */
object HiddenInputs {
    private const val TAG = "UnitedU"

    fun read(ctx: Context): Set<String> {
        if (Paths.baseOrNull(ctx) == null) return emptySet()
        val f = Paths.hiddenInputsJson(ctx)
        if (!f.exists()) return emptySet()
        return try {
            if (f.length() > 1_000_000) error("hidden-inputs.json 大得离谱: ${f.length()} 字节")
            val text = f.readText()
            if (!isWellFormedJsonObject(text)) error("hidden-inputs.json 不是合法的 JSON 对象")
            parseHiddenInputs(text)
        } catch (e: Throwable) {
            Log.w(TAG, "hidden-inputs.json 读不了,改名保留并重写空表: ${e.message}")
            runCatching { f.renameTo(Paths.hiddenInputsBad(ctx)) }
            write(ctx, emptySet())
            emptySet()
        }
    }

    fun write(ctx: Context, ids: Set<String>): Boolean {
        val base = Paths.baseOrNull(ctx) ?: return false
        val tmp = File(base, "hidden-inputs.json.tmp")
        return try {
            FileOutputStream(tmp).use { out -> out.write(hiddenInputsToJson(ids).toByteArray()); out.flush(); out.fd.sync() }
            val dst = Paths.hiddenInputsJson(ctx)
            if (tmp.renameTo(dst)) return true
            dst.delete()
            tmp.renameTo(dst)
        } catch (e: Throwable) {
            Log.w(TAG, "hidden-inputs.json 写不了: ${e.message}")
            false
        }
    }

    /** 隐藏 / 取消隐藏一个输入。IO 线程调用。 */
    fun set(ctx: Context, id: String, hidden: Boolean): Boolean {
        val now = read(ctx)
        val next = if (hidden) now + id else now - id
        return next == now || write(ctx, next)
    }

    /** 一键恢复全部(设置页「恢复隐藏的输入源」)。IO 线程调用。 */
    fun clear(ctx: Context): Boolean = read(ctx).isEmpty() || write(ctx, emptySet())
}
```

(`isWellFormedJsonObject` 若不在 Titles.kt 而在 Settings.kt,照样可用——同包顶层函数。)`HomeScreen.buildInputRow(ctx)` 改成 `buildInputRow(ctx, titles: Map<String, String>)`:`val inputs = applyInputPrefs(dedupeCec(Inputs.load(ctx)), HiddenInputs.read(ctx), titles)`,其余不变;调用处(`produceState` 里同一趟 IO)把已经读出的 titles 传进去(若 titles 在 buildInputRow 之后才读,把读 titles 挪到它之前)。KDoc 补一句「名字只换卡上文字,不受『卡片标题』开关影响」。

- [ ] **Step 4: 跑测试确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL,`InputPrefsTest` 5 个用例全过,全部单测 0 失败。

- [ ] **Step 5: 提交**

```bash
git add -A app/src
git commit -m "feat(m4b): HDMI-CEC parent/child de-dup, hidden-inputs.json, input names from titles.json

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: 输入源卡长按菜单 + 设置页「恢复隐藏的输入源」

**Files:**
- Modify: `CardMenu.kt`、`MainActivity.kt`(`cardMenuItems`、长按分支不用改——`cardMenuActions` 非空就会开菜单)、`SettingsModel.kt`(`settingsGroups` 布局组 + 新参数)、`SettingsScreen.kt`(把隐藏数读出来传进去,照 M5 `screensaverImages` 的做法)、三套 `strings.xml`
- Test: `app/src/test/java/com/uniteduone/launcher/CardMenuTest.kt`、`SettingsModelTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `HiddenInputs.set/clear/read`;现有 `Titles.set`、`TitleDialog`、`Inputs.launch`。
- Produces: `enum class CardAction { OPEN, UNINSTALL, RENAME, CHANGE_ICON, MOVE, REMOVE, HIDE }`;`cardMenuActions(RowKind.INPUTS) == listOf(OPEN, RENAME, HIDE)`;`settingsGroups(s, update, actions, screensaverImages, hiddenInputs: Int)`(−1 = 还没数完,0 = 不显示那一行)。

- [ ] **Step 1: 改测试**——`CardMenuTest` 里 `assertTrue(cardMenuActions(RowKind.INPUTS).isEmpty())` 换成 `assertEquals(listOf(CardAction.OPEN, CardAction.RENAME, CardAction.HIDE), cardMenuActions(RowKind.INPUTS))`;应用行六项的断言保持(`HIDE` 不进应用行)。`SettingsModelTest` 按现有写法加两例:`hiddenInputs = 0` 时布局组行数与今天相同;`hiddenInputs = 2` 时布局组在「输入源行」之后多一行动作,id `restoreHiddenInputs`,值文字取 `settings_hidden_inputs_count`。
- [ ] **Step 2: 跑测试确认失败**(`--tests 'com.uniteduone.launcher.CardMenuTest' --tests 'com.uniteduone.launcher.SettingsModelTest'`,Expected:断言失败 / 编译失败)。
- [ ] **Step 3: 实现**
  - `CardMenu.kt`:枚举末尾加 `HIDE`;`cardMenuActions` = `if (kind == RowKind.APPS) listOf(OPEN, UNINSTALL, RENAME, CHANGE_ICON, MOVE, REMOVE) else listOf(OPEN, RENAME, HIDE)`;KDoc 改成「应用行六项(design §2);输入源行三项(M4b spec §0-11)」。
  - `MainActivity.cardMenuItems(ref)`:`OPEN` 按 `ref.kind` 分流(`INPUTS` → `Inputs.launch(this, ref.pkg)`,失败同今天的 toast);`RENAME` 不改(输入源卡的 `pkg` 就是输入 id,`Titles.set` 同一条路;对话框预填 `titles[id] ?: label`,清空 = 恢复系统名——确认 `onRenameSave` 对 INPUTS 不做任何按包名的额外处理,有就按 kind 跳过);新增 `HIDE`:菜单文字 `menu_hide_input` / 说明 `menu_hide_input_desc`,动作 = 关菜单 → IO `HiddenInputs.set(this, ref.pkg, true)` → 成功 toast `toast_input_hidden`(参数 = `ref.label`)并 `settingsRevision++`(首页重读,卡片消失;焦点由首页现有的「行变短」处理接住),失败 toast `toast_input_hide_failed`。
  - `SettingsModel.settingsGroups`:新参数 `hiddenInputs: Int`;布局组在 `showInputRow` 开关之后,`hiddenInputs > 0` 时插一行动作 `restoreHiddenInputs`(标签 `settings_restore_hidden_inputs`,值 `settings_hidden_inputs_count` 格式化 N),动作走 `SettingsActions` 新增的 `restoreHiddenInputs: () -> Unit`。`SettingsScreen` 照 `screensaverImages` 的做法在 IO 线程读 `HiddenInputs.read(ctx).size`(重读时机也照它),`MainActivity` 实现该动作:IO `HiddenInputs.clear` → toast `toast_inputs_restored` → `settingsRevision++` → 设置页重读计数(隐藏数归零后这一行消失,焦点由设置页看门狗落到相邻行——验证这一点)。
  - 字符串(三套各一份,简体 / 繁體 / English):`menu_hide_input` 隐藏 / 隱藏 / Hide;`menu_hide_input_desc` 从桌面隐藏这个输入源 / 從桌面隱藏這個輸入源 / Hide this input from the home screen;`toast_input_hidden` 已隐藏「%1$s」,可在 设置 → 布局 → 恢复隐藏的输入源 找回 / 已隱藏「%1$s」,可在 設定 → 版面 → 恢復隱藏的輸入源 找回 / Hid “%1$s” — bring it back in Settings → Layout → Restore Hidden Inputs;`toast_input_hide_failed` 没能隐藏,请稍后再试 / 無法隱藏,請稍後再試 / Couldn\'t hide it, try again;`settings_restore_hidden_inputs` 恢复隐藏的输入源 / 恢復隱藏的輸入源 / Restore Hidden Inputs;`settings_hidden_inputs_count` %1$d 个 / %1$d 個 / %1$d hidden;`toast_inputs_restored` 已恢复全部输入源 / 已恢復全部輸入源 / All inputs restored。(繁體「布局」组在现有 `values-zh-rTW` 里叫什么就用什么,toast 里的路径文字与之一致。)
- [ ] **Step 4: 跑测试确认通过**:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`,0 失败。
- [ ] **Step 5: 模拟器能验的**:设置页布局组在隐藏数 0 时与改前逐行一致(截图对比);手工造一个隐藏文件验「恢复」这一行的出现与消失:`adb -s emulator-5554 shell 'echo "{\"x/y\":\"hidden\"}" > /sdcard/Android/data/com.uniteduone.launcher/files/hidden-inputs.json'`(路径以 `Paths.hiddenInputsJson` 为准)→ 重进设置页出现「恢复隐藏的输入源 1 个」→ 按下 → toast、行消失、焦点落在相邻行、文件变成 `{}`。输入源卡菜单本身模拟器上造不出来(无硬件输入),写进 Task 6 的真机清单。
- [ ] **Step 6: 提交**

```bash
git add -A app/src
git commit -m "feat(m4b): input card long-press menu (open / rename / hide) and a one-tap restore in Settings

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: 编辑页行管理——「+」行菜单、改名、图标选择器、上下移、新建、删除;去掉 verticalScroll;初始焦点

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/RowIconPicker.kt`
- Modify: `EditScreen.kt`、`TitleDialog.kt`(通用化)、`HomeScreen.kt`(TitleDialog 调用处跟着改)、三套 `strings.xml`
- Test: 纯函数已在 Task 1;本任务以模拟器脚本为证

**Interfaces:**
- Consumes: Task 1 的 `LayoutRow`、`addRowBelow/deleteRow/renameRow/setRowIcon/swapRows`、`MIN_ROWS/MAX_ROWS`、`ROW_ICON_IDS`、`effectiveRowIconId`、`rowIconVector`、`RowIcon(…, icon)`;修 bug 批的 `keepInView(focusedRow, firstVisible, visibleRows)`(ImagePicker.kt)。
- Produces: `TitleDialog(key: String, current: String, heading: String, hint: String, onSave: (String) -> Unit, onCancel: () -> Unit, nonce: Int)`;`RowIconPicker(current: String, nonce: Int, onPick: (String) -> Unit, onDismiss: () -> Unit)`。

- [ ] **Step 1: `TitleDialog` 通用化**——参数 `ref: CardRef` 换成 `key: String`(`remember(key)` 用它)、加 `heading: String`、`hint: String`(原来写死的标题与「清空 = 恢复应用名」提示改由调用方传);`HomeScreen` 里现有调用传 `key = ref.pkg`、`heading = stringResource(R.string.<原标题资源>)`、`hint = stringResource(R.string.<原提示资源>)`——首页对话框的外观与文字不变。
- [ ] **Step 2: 行菜单**——`AddCard` 的点击从 `picking = ri` 改成 `rowMenu = ri`(新状态 `var rowMenu by remember { mutableStateOf<Int?>(null) }`,并入 `overlayOpen` 判据,与 `acting` 同等对待:编辑页看门狗与重定位让路,守卫进 key,铁律 6)。`rowMenu != null` 时画 `GearMenu`(`nonce = focusNonce`),条目按下面顺序、不适用的不列:
  1. 添加应用(`edit_row_add_app` / `_desc`)→ `rowMenu = null; picking = ri`
  2. 重命名此行(`edit_row_rename`)→ `rowMenu = null; renamingRow = ri`
  3. 更换此行图标(`edit_row_icon`)→ `rowMenu = null; iconRow = ri`
  4. 此行上移(`edit_row_up`,`ri > 0`)→ `rows = swapRows(rows, ri, ri - 1); persist(); retarget(ri - 1, rows[ri - 1].apps.size)`
  5. 此行下移(`edit_row_down`,`ri < rows.lastIndex`)→ 对称
  6. 在下方新建一行(`edit_row_new`,`rows.size < MAX_ROWS`)→ `rows = addRowBelow(rows, ri, getString(edit_new_row_name)); persist(); retarget(ri + 1, 0)`(新行是空行,`(ri + 1, 0)` 就是它的「+」)
  7. 删除此行(`edit_row_delete`,`rows.size > MIN_ROWS`)→ 空行:`rows = deleteRow(rows, ri); persist(); retarget(max(ri - 1, 0), rows[max(ri - 1, 0)].apps.size)`;非空行:`confirmDeleteRow = ri`,画 `ConfirmDialog`(标题 `edit_row_delete_confirm_title` 带行名,正文 `edit_row_delete_confirm_body` 带 N,确定 `edit_row_delete_ok`,取消沿用 `dialog_cancel`;默认焦点在取消,它自己负责焦点),确定后同空行的删法,取消 → `retarget(ri, rows[ri].apps.size)`。
  菜单 `onDismiss` → `rowMenu = null; retarget(ri, rows[ri].apps.size)`。每个动作都要写盘失败提示(沿用 `persist()` 现有的失败处理)。
- [ ] **Step 3: 改名与图标**——`renamingRow != null` 时画 `TitleDialog(key = "row-$ri", current = rows[ri].name, heading = edit_row_rename_heading, hint = edit_row_rename_hint, onSave = { rows = renameRow(rows, ri, it); persist(); renamingRow = null; retarget(…「+」) }, onCancel = { renamingRow = null; retarget(…「+」) }, nonce = focusNonce)`。`iconRow != null` 时画 `RowIconPicker(current = effectiveRowIconId(rows[ri].name, rows[ri].icon), nonce = focusNonce, onPick = { rows = setRowIcon(rows, ri, it); persist(); iconRow = null; retarget(…「+」) }, onDismiss = { iconRow = null; retarget(…「+」) })`。两者都并入 `overlayOpen`。
- [ ] **Step 4: `RowIconPicker.kt`**——整屏半透明底(同 GearMenu 的 `Color.Black.copy(alpha = 0.72f)` 与 `Theme.DialogSurface` 面板)+ 标题(`edit_row_icon_heading`)+ 4 列 × 3 行图标格(每格:`rowIconVector(id)` 32dp,用 `LocalThemeColors.current.accent` 着色;下方 12sp 名字 `row_icon_<id>`;聚焦 = 与 `MenuRow` 相同的 highlight 12% 底)+ 底部「按返回取消」。焦点:逐项 `FocusRequester`;初始目标 = `current` 的下标;`LaunchedEffect(nonce)` 初始循环 + 以 `holder == null` 为 key 与守卫的看门狗(与修 bug 批 GearMenu 的写法一致);四边 `FocusRequester.Cancel`;`BackHandler` → `onDismiss`;确定 → `onPick(id)`。不用任何可滚动容器(12 格一屏放得下)。
- [ ] **Step 5: 行标题画图标**——编辑页每行的 `BasicText(text = name…)` 前加 `RowIcon(name, RowKind.APPS, row.icon, tint = …)`(与首页同一个组件,尺寸沿用首页的 24dp;行标题的 `Row` 竖直居中)。
- [ ] **Step 6: 去掉 `verticalScroll`(铁律 1)**——`EditScreen.kt:280` 附近的 `.verticalScroll(rememberScrollState())` 换成与图片网格同一招:外层 `Box(Modifier.fillMaxSize().clipToBounds())`,内容 `Column` 加 `.wrapContentHeight(Alignment.Top, unbounded = true).offset(y = yShift)`;`yShift` 由「当前焦点行」决定——量出标题区高度与每行高度(`onSizeChanged`),以 `keepInView(focusRow, firstVisibleRow, visibleRows)` 推进 `firstVisibleRow`(焦点行报 got 时推进,冻结期间不推进,铁律 5),`yShift = -(标题区高度(首行不为 0 时)+ firstVisibleRow × 行距)` 的 `animateDpAsState`。验收标准:5 行 × 大档(5 张 / 行)时每一行都能到达、焦点行始终完整可见。
- [ ] **Step 7: 初始焦点**——`initialTarget == null` 时目标 = `(0, 0)`:第 1 行有卡就是第 1 张卡,空行就是「+」(追查今天落在「+」的原因——`focusTarget` 初值或 `AddCard` 的 requester 挂法——在报告里写明根因)。
- [ ] **Step 8: 字符串**(三套):`edit_row_add_app` 添加应用 / 新增應用 / Add App(`_desc`:从已安装的应用里挑 / 從已安裝的應用裡挑 / Pick from installed apps);`edit_row_rename` 重命名此行 / 重新命名此列 / Rename Row;`edit_row_icon` 更换此行图标 / 更換此列圖示 / Change Row Icon;`edit_row_up` 此行上移 / 此列上移 / Move Row Up;`edit_row_down` 此行下移 / 此列下移 / Move Row Down;`edit_row_new` 在下方新建一行 / 在下方新增一列 / New Row Below;`edit_row_delete` 删除此行 / 刪除此列 / Delete Row;每项配一条 `_desc` 说明(一句话,照现有编辑菜单项的 desc 口吻);`edit_new_row_name` 新行 / 新行 / New Row;`edit_row_rename_heading` 重命名此行 / 重新命名此列 / Rename Row;`edit_row_rename_hint` 清空不会改名 / 清空不會改名 / Clearing keeps the current name;`edit_row_icon_heading` 选择行图标 / 選擇列圖示 / Choose Row Icon;`edit_row_delete_confirm_title` 删除「%1$s」? / 刪除「%1$s」? / Delete “%1$s”?;`edit_row_delete_confirm_body` 这一行的 %1$d 个应用会从桌面移除,不会卸载。/ 這一列的 %1$d 個應用會從桌面移除,不會解除安裝。/ The %1$d apps in this row leave the home screen. Nothing is uninstalled.;`edit_row_delete_ok` 删除 / 刪除 / Delete;`row_icon_movie` 影片 / 影片 / Movies、`row_icon_tv` 电视 / 電視 / TV、`row_icon_live` 直播 / 直播 / Live、`row_icon_music` 音乐 / 音樂 / Music、`row_icon_games` 游戏 / 遊戲 / Games、`row_icon_kids` 儿童 / 兒童 / Kids、`row_icon_tools` 工具 / 工具 / Tools、`row_icon_education` 学习 / 學習 / Learning、`row_icon_sports` 运动 / 運動 / Sports、`row_icon_news` 资讯 / 資訊 / News、`row_icon_photos` 相册 / 相簿 / Photos、`row_icon_apps` 应用 / 應用 / Apps。(繁體版「行 / 列」用词以 `values-zh-rTW` 现有编辑页文案为准,保持一致。)
- [ ] **Step 9: 构建 + 模拟器验证**(每步 0.4 s 间隔、截图 + `uiautomator` 焦点数):进编辑页焦点在第 1 张卡;在第 1 行「+」→ 菜单七项里「上移」不在;新建到 5 行(「新建」消失);第 5 行 → 删除(空行直接删);非空行删除 → 确认框默认在取消 → 取消回原「+」→ 再删 → 确定;改名(IME 输入用 `adb -s emulator-5554 shell input text`)、清空改名(不变);换图标(选择器初始聚焦在当前图标、四边按键不跑出去);上移 / 下移;大档(设置里卡片大小 = 大)+ 5 行时逐行下移到第 5 行再回第 1 行,焦点行始终可见、焦点数恒 1;返回首页:行序、行名、图标与编辑页一致;`am force-stop` 后重开仍一致;`layout.json` 里改过图标的行有 `"icon"`、没改过的没有。
- [ ] **Step 10: 提交**

```bash
git add -A app/src
git commit -m "feat(m4b): row management in the edit screen — row menu on the row-end +, rename, icon picker, reorder, add, delete; no scroll container; first-card initial focus

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: 首页原地移动

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/Move.kt`
- Modify: `MainActivity.kt`(`CardAction.MOVE` 的动作、`dispatchKeyEvent`、`onPause`/`onNewIntent`/待机入口的取消)、`HomeScreen.kt`(用工作副本渲染、移动中的卡与提示条、目标格)、`AppCard.kt`(`moving: Boolean` 参数 → 3dp accent 描边)、三套 `strings.xml`
- Test: `app/src/test/java/com/uniteduone/launcher/MoveTest.kt`(新建)

**Interfaces:**
- Consumes: Task 1 的 `LayoutRow`、`Row(…, icon, layoutRow)`;现有 `CardRef`、首页的目标格(`tgtRow`/`tgtIdx`)与 `focusNonce` 还原效果。
- Produces: `data class MovePos(val row: Int, val col: Int)`、`enum class MoveDir { LEFT, RIGHT, UP, DOWN }`、`moveCard(rows: List<Row>, pos: MovePos, dir: MoveDir): Pair<List<Row>, MovePos>`、`mergeMove(disk: List<LayoutRow>, original: List<Row>, working: List<Row>): List<LayoutRow>`。

- [ ] **Step 1: 写失败的测试**——新建 `MoveTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MoveTest {
    private fun app(p: String) = AppEntry(packageName = p, label = p, card = null, isWide = false)
    private fun row(name: String, layoutRow: Int, vararg pkgs: String, kind: RowKind = RowKind.APPS) =
        Row(name = name, apps = pkgs.map { app(it) }, kind = kind, layoutRow = layoutRow)
    private fun names(rows: List<Row>) = rows.map { r -> r.apps.map { it.packageName } }

    private val inputs = row("Inputs", -1, "HW2", "HW3", kind = RowKind.INPUTS)
    private val home = listOf(inputs, row("VIDEO", 0, "a", "b", "c"), row("MUSIC", 2, "d"))

    @Test fun leftAndRightSwapWithinTheRowAndStopAtTheEnds() {
        val (r1, p1) = moveCard(home, MovePos(1, 1), MoveDir.LEFT)
        assertEquals(listOf("b", "a", "c"), names(r1)[1]); assertEquals(MovePos(1, 0), p1)
        val (r2, p2) = moveCard(home, MovePos(1, 0), MoveDir.LEFT)
        assertSame(home, r2); assertEquals(MovePos(1, 0), p2)
        val (r3, p3) = moveCard(home, MovePos(1, 2), MoveDir.RIGHT)
        assertSame(home, r3); assertEquals(MovePos(1, 2), p3)
    }

    @Test fun downLandsInTheSameColumnOrAtTheRowEnd() {
        val (r, p) = moveCard(home, MovePos(1, 2), MoveDir.DOWN)
        assertEquals(listOf(listOf("HW2", "HW3"), listOf("a", "b"), listOf("d", "c")), names(r))
        assertEquals(MovePos(2, 1), p)
    }

    @Test fun upSkipsTheInputRow() {
        val (r, p) = moveCard(home, MovePos(1, 0), MoveDir.UP)
        assertSame(home, r); assertEquals(MovePos(1, 0), p)
    }

    @Test fun emptiedSourceRowDisappearsAndTheTargetIndexFollows() {
        // d 在 MUSIC 第 0 列,上移落到 VIDEO 第 0 列;MUSIC 被移空、从工作副本去掉,VIDEO 行号仍是 1
        val (r, p) = moveCard(home, MovePos(2, 0), MoveDir.UP)
        assertEquals(listOf(listOf("HW2", "HW3"), listOf("d", "a", "b", "c")), names(r))
        assertEquals(MovePos(1, 0), p)
    }

    @Test fun emptiedRowAboveTheTargetShiftsTheTargetUp() {
        val rows = listOf(row("A", 0, "a"), row("B", 1, "b1", "b2"))
        val (r, p) = moveCard(rows, MovePos(0, 0), MoveDir.DOWN)
        assertEquals(listOf(listOf("a", "b1", "b2")), names(r))
        assertEquals(MovePos(0, 0), p)
    }

    @Test fun mergeKeepsUnrenderedPackagesAndEmptiedRows() {
        val disk = listOf(
            LayoutRow("VIDEO", apps = listOf("a", "x", "b", "c")),   // x 没装,首页不显示
            LayoutRow("EMPTY"),
            LayoutRow("MUSIC", icon = "music", apps = listOf("d")),
        )
        val original = listOf(row("VIDEO", 0, "a", "b", "c"), row("MUSIC", 2, "d"))
        val working = listOf(row("VIDEO", 0, "b", "a"), row("MUSIC", 2, "d", "c"))
        assertEquals(
            listOf(
                LayoutRow("VIDEO", apps = listOf("b", "a", "x")),
                LayoutRow("EMPTY"),
                LayoutRow("MUSIC", icon = "music", apps = listOf("d", "c")),
            ),
            mergeMove(disk, original, working),
        )
    }

    @Test fun mergeWritesAnEmptiedRowAsEmpty() {
        val disk = listOf(LayoutRow("VIDEO", apps = listOf("a")), LayoutRow("MUSIC", apps = listOf("d")))
        val original = listOf(row("VIDEO", 0, "a"), row("MUSIC", 1, "d"))
        val working = listOf(row("MUSIC", 1, "d", "a"))   // VIDEO 被移空、已从工作副本里去掉
        assertEquals(
            listOf(LayoutRow("VIDEO"), LayoutRow("MUSIC", apps = listOf("d", "a"))),
            mergeMove(disk, original, working),
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**:`--tests 'com.uniteduone.launcher.MoveTest'`,Expected:编译失败。
- [ ] **Step 3: 实现 `Move.kt`**

```kotlin
package com.uniteduone.launcher

/** 首页移动态里被搬的那张卡的位置(首页渲染行的下标,含置顶的输入源行)。按位置追踪,不按包名找(同一个包可以在两行里)。 */
data class MovePos(val row: Int, val col: Int)

enum class MoveDir { LEFT, RIGHT, UP, DOWN }

/**
 * 搬一步(M4b spec §0-9)。左右:与同行邻卡换位,到头不动。上下:落到那个方向最近的**应用行**
 * (跳过输入源行)的同一列,越过行尾就放行尾;那个方向没有应用行 → 不动。源行被移空 → 从结果里去掉
 * (首页不显示空行),落点行号随之校正。不动时返回**同一个** list 与原位置。
 */
internal fun moveCard(rows: List<Row>, pos: MovePos, dir: MoveDir): Pair<List<Row>, MovePos> {
    val src = rows.getOrNull(pos.row) ?: return rows to pos
    if (src.kind != RowKind.APPS || pos.col !in src.apps.indices) return rows to pos
    when (dir) {
        MoveDir.LEFT, MoveDir.RIGHT -> {
            val to = if (dir == MoveDir.LEFT) pos.col - 1 else pos.col + 1
            if (to !in src.apps.indices) return rows to pos
            val apps = src.apps.toMutableList().apply { val t = this[pos.col]; this[pos.col] = this[to]; this[to] = t }
            return rows.mapIndexed { i, r -> if (i == pos.row) r.copy(apps = apps) else r } to pos.copy(col = to)
        }
        MoveDir.UP, MoveDir.DOWN -> {
            val step = if (dir == MoveDir.UP) -1 else 1
            var t = pos.row + step
            while (t in rows.indices && rows[t].kind != RowKind.APPS) t += step
            if (t !in rows.indices) return rows to pos
            val card = src.apps[pos.col]
            val target = rows[t]
            val col = pos.col.coerceAtMost(target.apps.size)
            val next = rows.mapIndexed { i, r ->
                when (i) {
                    pos.row -> r.copy(apps = r.apps.filterIndexed { c, _ -> c != pos.col })
                    t -> r.copy(apps = r.apps.toMutableList().apply { add(col, card) })
                    else -> r
                }
            }
            if (next[pos.row].apps.isNotEmpty()) return next to MovePos(t, col)
            val pruned = next.filterIndexed { i, _ -> i != pos.row }
            return pruned to MovePos(if (t > pos.row) t - 1 else t, col)
        }
    }
}

/**
 * 放下时写回 layout.json 的内容(纯函数)。对磁盘上的每一行 L:
 * 新顺序 = 工作副本里 layoutRow == L 那一行的包(没有 = 被移空)+ 该行里首页原本就没显示的包(未安装的,保持相对顺序)。
 * 名字、图标、行序全部照磁盘;首页不显示的行(空行、全是未安装)原样保留。
 */
internal fun mergeMove(disk: List<LayoutRow>, original: List<Row>, working: List<Row>): List<LayoutRow> =
    disk.mapIndexed { l, row ->
        val before = original.firstOrNull { it.kind == RowKind.APPS && it.layoutRow == l } ?: return@mapIndexed row
        val shown = before.apps.map { it.packageName }.toSet()
        val now = working.firstOrNull { it.kind == RowKind.APPS && it.layoutRow == l }?.apps?.map { it.packageName } ?: emptyList()
        row.copy(apps = (now + row.apps.filter { it !in shown }).distinct())
    }
```

- [ ] **Step 4: 跑测试确认通过**:`--tests 'com.uniteduone.launcher.MoveTest'`,Expected:7 个用例全过。
- [ ] **Step 5: 接线(按铁律逐条)**
  - **状态只有一份**:`MainActivity` 持有 `moving: MoveState?`(`MoveState(pos: MovePos, rows: List<Row>, original: List<Row>)`)。`CardAction.MOVE` 的动作改成:关菜单 → 从首页取当前渲染的行(给 `HomeScreen` 加一个 `onRowsShown: (List<Row>) -> Unit` 上报,MainActivity 记住最近一份)→ `moving = MoveState(MovePos(ref.rowIndex, ref.colIndex), rows, rows)`。旧的「`editTarget = …; editing = true`」兜底删除(`editTarget` 仍留给换卡片图回来定位用)。
  - **渲染**:`HomeScreen` 新参数 `moving: MoveState?`;非空时一律用 `moving.rows` 画行(不读 `loaded`),被搬的那张卡 `AppCard(moving = true)`:tv-material `Card` 的 `border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, accent)))`(只改颜色,粗细与库默认相同),屏幕底部居中一行提示 `home_move_hint`(「← → 移动 · ↑ ↓ 换行 · 确定 放下 · 返回 取消」/ 繁體同義 / 「← → Move · ↑ ↓ Change Row · OK Drop · Back Cancel」),字样沿用首页现有的提示文字样式。移动态并入首页的 `covered` 判据之外的**独立**让路:看门狗与还原效果照常工作,但目标格由移动态决定(下一条)。
  - **按键**:`dispatchKeyEvent` 最前面(长按检测之前)加:`moving != null` 时,`ACTION_DOWN` 的 DPAD_LEFT/RIGHT/UP/DOWN → `moveCard` → 更新 `moving` → 把首页目标格设成新位置并 `focusNonce++`(用首页已有的「按目标格还原」那条路;需要的话给 HomeScreen 加一个 `moveTarget: MovePos?` 参数,非空时还原效果与看门狗都以它为目标,守卫与 key 成对);DPAD_CENTER/ENTER → 放下:IO `Layout.write(ctx, mergeMove(Layout.read(ctx), original, rows))`,成功 → `moving = null; settingsRevision++`(首页重读),失败 → toast `toast_move_failed` 并保持移动态;BACK → 取消:`moving = null`,首页回到 `original`、焦点回到出发那一格;**其余所有键吞掉**(MENU、长按、屏保按钮都不响应)。对应的 `ACTION_UP` 一律吞掉。
  - **取消的其它入口**:`onPause`、`onNewIntent`(HOME)、进入待机(`standby` 从 NORMAL 变化)时若 `moving != null` → 取消(同 BACK)。
  - **焦点责任表**:移动态没有浮层——焦点始终在被搬的卡上,由首页看门狗 + 还原效果以 `moveTarget` 为目标负责;写进 Task 6 的 CLAUDE.md 更新。
- [ ] **Step 6: 构建 + 模拟器验证**(0.4 s 间隔、每步截图 + 焦点数 + bounds):首页第 2 行第 2 张长按 → 菜单「移动位置」→ 描边变 accent、底部出提示 → RIGHT ×2(到行尾后第三下不动)→ LEFT → DOWN(落到下一行同列或行尾)→ UP 回来 → 确定:`layout.json` 与屏幕一致,`am force-stop` 重开仍一致;再进移动态 → 移动若干步 → 返回:屏幕与 `layout.json` 都回到原样、焦点回出发格;移动态按 MENU / 长按确定:无反应;移动态按 HOME(`am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.uniteduone.launcher/.MainActivity`):复原;把某行唯一一张卡移走:该行消失,确定后编辑页里该行为空行仍在。
- [ ] **Step 7: 提交**

```bash
git add -A app/src
git commit -m "feat(m4b): move a card in place on the home screen — swap, change row, drop, cancel

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: 回归 + 文档

**Files:**
- Modify: `CLAUDE.md`(焦点责任表)、`docs/DESIGN-unitedu-open-source.md`(§2 应用行 / 输入源 / 移动位置三条落实为现状,§11 路线)、`docs/WORKLOG.md`

- [ ] **Step 1: 全量构建 + 装包**:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`,记测试总数与 0 失败;`adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk`。
- [ ] **Step 2: 回归**(0.4 s 间隔,截图 + 焦点数):首页四向导航与同列规则、pill 两个按钮、长按菜单开合(应用卡六项)、齿轮菜单、设置页布局组、屏保按钮、编辑页全套(Task 4 Step 9 精简版)、原地移动全套(Task 5 Step 6 精简版)。失败即按 systematic-debugging 定位、修、从该步重跑。
- [ ] **Step 3: CLAUDE.md 焦点责任表**新增 / 改写:「编辑页的行菜单(M4b)| GearMenu 初始循环 + 看门狗;关掉后由编辑页 `retarget` 落到该行的「+」」;「行图标选择器(M4b)| RowIconPicker 自己的 nonce 循环 + 逐项 requester + 看门狗」;「编辑页的删行确认框(M4b)| ConfirmDialog(默认取消);关掉后 `retarget`」;「编辑页的改名对话框(M4b 起通用化)| TitleDialog(nonce + focused)」;「首页移动态(M4b)| 无浮层;首页看门狗 + 还原效果以 `moveTarget` 为目标」;「编辑页」一行补「纵向位移自算(M4b 去掉 verticalScroll)」。
- [ ] **Step 4: DESIGN §2** 把「应用行」「输入源行」「移动位置」三条改写成现状(行菜单入口、12 个图标 id、hidden-inputs.json、原地移动的键位与取消规则),并在 §11 标 M4b 完成(待 A95L 验收)。
- [ ] **Step 5: WORKLOG** 新增「M4b」一节:每个任务的结果、Rulings 摘要(指向 spec §0)、单测总数、模拟器回归结果,以及 **A95L 真机清单**:①长按输入源卡 → 打开 / 改名 / 隐藏三项;改名后首页显示新名、清空恢复系统名;②隐藏一个输入源 → 卡片消失 → 设置 → 布局 →「恢复隐藏的输入源 1 个」→ 恢复;③把输入源全部隐藏 → 输入源行消失 → 设置里恢复 → 回来;④输入源卡长按之后不再残留按压态;⑤(接了支持 HDMI-CEC 的设备时)同一个 HDMI 口不再出现两张卡,只剩设备名那张;⑥编辑页建到 5 行、改名、换图标、上下移、删行,首页一致;⑦首页原地移动:换位、换行、确定、返回、按 HOME。
- [ ] **Step 6: 提交**

```bash
git add CLAUDE.md docs/DESIGN-unitedu-open-source.md docs/WORKLOG.md
git commit -m "docs(m4b): focus-owner table, DESIGN §2 current state, WORKLOG with A95L checks

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
