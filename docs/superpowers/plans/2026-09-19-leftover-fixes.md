# 遗留修复批(main 精简版第一步)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉 M5 / M7 / M8 终审与验收记下的、不属于 M4b 的全部遗留 bug,不做任何 UI 美化。

**Architecture:** 一个分支 `leftover-fixes`(worktree `.claude/worktrees/leftover-fixes`),五个互不依赖输出的任务:播放器加固(纯 JVM 可测)→ 图库查看器(去掉滚动容器、IO 扫描、单一画图函数、预览焦点、删图失败提示)→ 首页与菜单焦点(菜单看门狗、输入源行标题占位、指针输入核查)→ 卫生项(死代码、测试、英文大小写、显式依赖)→ 模拟器回归与文档。

**Tech Stack:** Kotlin、Jetpack Compose(BOM 2024.10.01)、androidx.tv:tv-material 1.0.0、JUnit 4。

**Spec:** 本批没有独立 spec,需求来源是 `docs/WORKLOG.md` 里已经记下的遗留条目(逐条引在下表),约束来源是 `CLAUDE.md`「改这份界面前必须知道的七条」。Gordon 2026-09-19 定:「我们把 main 剩余当中的 bug 部分都修掉……任何跟 UI 美化相关的就先不做了」。

| # | 遗留(原文出处) | 任务 |
|---|---|---|
| 1 | 屏保 ticker 扫库没包 `runCatching`,IO 异常杀掉协程、轮播停转(WORKLOG「M5 终审收尾」遗留) | T1 |
| 2 | `ScreensaverPlayer` 引用计数只在模拟器人工验过,无 JVM 单测(同上) | T1 |
| 3 | 图库查看器主线程列文件 + 与播放器重复的扩展名表(同上) | T2 |
| 4 | `PreviewSlot` 与 `ScreensaverSlot` 画法重复(同上) | T2 |
| 5 | `PickerGrid` 用 `verticalScroll`,违反铁律 1(同上 + 「M5」已知未修) | T2 |
| 6 | 全屏预览的初始焦点循环只落一次;预览开着时回到前台,网格的 nonce 循环把焦点抢走(同上两处) | T2 |
| 7 | 删图失败只写 `Log.w`,界面没反馈(同上) | T2 |
| 8 | 被吞掉的长按 UP 可能让缩略图按压指示残留(「M5」已知未修,未复现) | T2 核查 |
| 9 | 冷启动约 4 s 内按 MENU:齿轮菜单开着但焦点数 0(WORKLOG M7「新发现」) | T3 |
| 10 | `showInputRow && showTitles` 时锚点偏 20dp(输入源行不画标题)(WORKLOG M8「终审遗留」) | T3 |
| 11 | 指针输入清空 Compose 焦点、看门狗补不回来(WORKLOG M7「新发现」,飞鼠遥控器) | T3 核查 |
| 12 | 死代码 `CardMetrics.rowPitch`/`titleHeight`、`Theme.CardFallbackText`、`HomeLayout.scrimHeight`(M8 终审遗留) | T4 |
| 13 | 补 `cardMetrics(7)` 回落单测;`themePresetIdFallsBackToGoldWhenAbsentOrBlank` 改名(M8 终审遗留) | T4 |
| 14 | 英文设置项大小写不一致(M5 终审遗留) | T4 |
| 15 | `androidx.lifecycle` / `androidx.savedstate` 只是传递依赖,`UnitedUDream` 却直接 import(M5 终审遗留) | T4 |

**不在本批(归 M4b,它会重做这两处)**:编辑页初始焦点落在第 1 行行尾「+」而不是第 1 张卡(WORKLOG M7「新发现」);输入源行长按后卡片按压态要等失焦才释放(M8 终审遗留,原文就写「M4b 加菜单时给 Card 传 `interactionSource`」)。

## Global Constraints

- **不做 UI 美化**:颜色、尺寸、字体、间距、动效时长、布局一律不动,除非是下表某条 bug 本身要求的最小改动(Gordon 2026-09-19)。
- `CLAUDE.md` 铁律 1–7 约束每一处 Compose 改动:不用任何可滚动容器(`LazyRow`/`LazyColumn`/`horizontalScroll`/`verticalScroll`);位移一律自己算(`Modifier.offset` + `animateDpAsState`),并配 `wrapContentWidth/Height(unbounded = true)`;焦点是否落下只信目标自报 `isFocused`;每个浮层自己负责焦点恢复;守卫与 key 成对;不用一次性布尔闩。
- 模拟器命令一律 `adb -s emulator-5554`;**绝不向 A95L(192.168.1.22:38673)发按键 / 装包 / 改设置**。
- 构建 + 单测:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`;单跑某个测试类加 `--tests 'com.uniteduone.launcher.XxxTest'`。
- 三套字符串同步:`values`(简体)、`values-zh-rTW`、`values-en`。
- tv-material 保持 1.0.0;除 T4 的两条显式声明(版本 = 现在实际解析到的版本)外,不新增依赖。
- 不碰 `EditScreen.kt`、`Layout.kt`、`Inputs.kt` 和长按菜单的条目——M4b 的地盘。
- 提交信息结尾:`Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`。

---

### Task 1: 屏保播放器加固 + 引用计数单测

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/ScreensaverPlayer.kt`(全文件,109 行)
- Modify: `app/src/main/java/com/uniteduone/launcher/ImagePicker.kt:48`(删掉私有的 `IMAGE_EXTS`)
- Test: `app/src/test/java/com/uniteduone/launcher/ScreensaverPlayerTest.kt`

**Interfaces:**
- Produces: `internal class RefCounter { val count: Int; fun acquire(): Boolean; fun release(): Boolean }`;`internal val IMAGE_EXTS: Set<String>`(原 `private val SCREENSAVER_IMAGE_EXTS` 改名提升,ImagePicker 与播放器共用);`hasScreensaverImages(ctx)` 不再抛异常(扫描失败 = false)。

- [ ] **Step 1: 写失败的测试**——在 `ScreensaverPlayerTest` 里追加(并把 import 补成 `assertEquals`/`assertTrue`/`assertFalse`;类头 KDoc 的「播放器本体不在 JVM 上测」改成「引用计数抽成 RefCounter 在 JVM 上测;协程与主线程部分放模拟器」):

```kotlin
    @Test fun firstAcquireStartsAndNestedAcquiresDoNot() {
        val r = RefCounter()
        assertTrue(r.acquire())      // 桌面屏保先到:启动计时
        assertFalse(r.acquire())     // 系统屏保叠上来接班:不起第二个计时(否则换图速度翻倍)
        assertEquals(2, r.count)
    }

    @Test fun onlyTheLastReleaseStops() {
        val r = RefCounter()
        r.acquire(); r.acquire()
        assertFalse(r.release())     // 桌面先走、系统屏保还在:计时继续
        assertTrue(r.release())      // 最后一个走:停计时
        assertEquals(0, r.count)
    }

    @Test fun extraReleaseIsANoOp() {
        val r = RefCounter()
        assertFalse(r.release())     // 多 detach 一次:不会变负数
        assertEquals(0, r.count)
        assertTrue(r.acquire())      // 也不会让下一次 attach 起不了计时
    }

    @Test fun reacquireAfterFullReleaseStartsAgain() {
        val r = RefCounter()
        r.acquire(); r.release()
        assertTrue(r.acquire())
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.ScreensaverPlayerTest'`
Expected: 编译失败,`Unresolved reference: RefCounter`。

- [ ] **Step 3: 实现**——`ScreensaverPlayer.kt` 改成下面这样(`nextIndex`/`clampIndex`/`scanScreensaverLibrary` 的正文、`migrateOldScreensaver`、`publish` 不变,只改注明的地方):

```kotlin
/** 图片扩展名(小写)。图库扫描与 ImagePicker 的壁纸 / 卡片图列表共用这一份,口径一致。 */
internal val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")
```

`scanScreensaverLibrary` 里的 `SCREENSAVER_IMAGE_EXTS` 换成 `IMAGE_EXTS`。`hasScreensaverImages` 换成:

```kotlin
/**
 * 状态机与屏保按钮的「图库非空」判据(spec §1.1 / §1.3)。**必须在 IO 线程调用。**
 * 扫描抛异常(外置存储被拔、权限变化)按空图库处理:调用方都在 LaunchedEffect 里,
 * 异常冒出去就是在主线程崩掉整个桌面。
 */
internal fun hasScreensaverImages(ctx: Context): Boolean =
    runCatching { scanScreensaverLibrary(ctx).isNotEmpty() }.getOrDefault(false)
```

在 `object ScreensaverPlayer` 之前加:

```kotlin
/**
 * 播放器的引用计数(spec §2):第一个 [acquire] 返回 true(调用方启动计时),最后一个 [release] 返回 true
 * (调用方停计时);多余的 [release] 是空操作、返回 false——计数不会变负,也不会因为多 detach 一次就把
 * 另一处还在用的计时停掉。只在主线程调,不加锁。
 */
internal class RefCounter {
    var count = 0
        private set

    fun acquire(): Boolean {
        count++
        return count == 1
    }

    fun release(): Boolean {
        if (count == 0) return false
        count--
        return count == 0
    }
}

/** 扫一次图库;任何异常都当作「这次没扫到」,返回 null,调用方保留上一份列表、计时照走。 */
private fun safeScan(app: Context): List<File>? =
    runCatching { scanScreensaverLibrary(app) }
        .onFailure { android.util.Log.w("UnitedU", "屏保图库扫描失败", it) }
        .getOrNull()
```

对象里 `private var refs = 0` 换成 `private val refs = RefCounter()`,`attach`/`detach`/`rescan` 换成:

```kotlin
    fun attach(ctx: Context, intervalMs: Long) {
        intervalNow = intervalMs
        if (!refs.acquire()) return
        val app = ctx.applicationContext
        ticker = scope.launch {
            // 扫描失败不能杀掉这个协程(原来 IO 异常直接冒出去,轮播从此停转)
            withContext(Dispatchers.IO) { safeScan(app) }?.let { publish(it) }
            while (true) {
                // 每轮重读间隔:另一处 attach 改了它,下一张起就按新值
                delay(intervalNow)
                _index.value = nextIndex(_index.value, _files.value.size)
            }
        }
    }

    fun detach() {
        if (refs.release()) {
            ticker?.cancel()
            ticker = null
        }
    }

    /** 删图后调用(spec §5):重扫并把 [index] 夹回范围。计时状态不动;扫描失败保留旧列表。 */
    fun rescan(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { withContext(Dispatchers.IO) { safeScan(app) }?.let { publish(it) } }
    }
```

对象 KDoc 里「`refs` 不加锁」改成「`refs`([RefCounter])不加锁」。最后删掉 `ImagePicker.kt:48` 的 `private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")`(同名的 internal 版本已在本文件,ImagePicker 里的引用自动改指它,值不变;两份同名声明留着会编译冲突)。

- [ ] **Step 4: 跑测试确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL,`ScreensaverPlayerTest` 9 个用例全过,全部单测 0 失败。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/uniteduone/launcher/ScreensaverPlayer.kt app/src/main/java/com/uniteduone/launcher/ImagePicker.kt app/src/test/java/com/uniteduone/launcher/ScreensaverPlayerTest.kt
git commit -m "fix(screensaver): scan failures no longer kill the slideshow; ref counting extracted and tested

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: 图库查看器——去掉滚动容器、IO 扫描、单一画图函数、预览焦点、删图失败提示

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/ImagePicker.kt`(`PickerGrid` 195–306、`ScreensaverPoolViewer` 394–466、`ScreensaverPreview` 519–586、删 `PreviewSlot` 588–616)
- Modify: `app/src/main/java/com/uniteduone/launcher/Screensaver.kt:80`(`ScreensaverSlot` 改 internal)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(`ScreensaverPoolViewer(` 调用处去掉 `directory` 参数;`deletePoolImage` 1224–1234)
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rTW/strings.xml`、`values-en/strings.xml`(各加一条)
- Test: `app/src/test/java/com/uniteduone/launcher/PickerScrollTest.kt`(新建)

**Interfaces:**
- Consumes: T1 的 `IMAGE_EXTS`、`scanScreensaverLibrary(ctx)`。
- Produces: `internal fun keepInView(focusedRow: Int, firstVisible: Int, visibleRows: Int): Int`;`internal fun ScreensaverSlot(file: File?, intervalMs: Long)`;`ScreensaverPoolViewer(nonce, refresh, onFocusedFile, deleteTarget, onConfirmDelete, onCancelDelete, onDismiss)`(去掉 `directory`);字符串 `toast_pool_delete_failed`(一个 `%1$s` 参数 = 文件名)。

- [ ] **Step 1: 写失败的测试**——新建 `PickerScrollTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** 图片网格自算位移的首行规则(铁律 1:不用 verticalScroll)。 */
class PickerScrollTest {
    @Test fun insideTheViewportNothingMoves() {
        assertEquals(0, keepInView(focusedRow = 0, firstVisible = 0, visibleRows = 4))
        assertEquals(0, keepInView(3, 0, 4))
        assertEquals(2, keepInView(4, 2, 4))
    }

    @Test fun goingBelowMakesTheFocusedRowTheLastVisible() {
        assertEquals(1, keepInView(4, 0, 4))
        assertEquals(6, keepInView(9, 0, 4))   // 一次跳多行(删图后夹回、nonce 重落)也只露到刚好
    }

    @Test fun goingAboveMakesTheFocusedRowTheFirst() {
        assertEquals(2, keepInView(2, 5, 4))
        assertEquals(0, keepInView(0, 3, 4))
    }

    @Test fun nonPositiveVisibleRowsActsAsOne() {
        assertEquals(3, keepInView(3, 0, 0))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.PickerScrollTest'`
Expected: 编译失败,`Unresolved reference: keepInView`。

- [ ] **Step 3: 实现 `keepInView` 并把 `PickerGrid` 的滚动容器换成自算位移**

在 `ImagePicker.kt` 顶层(`PickerGrid` 之前)加:

```kotlin
/**
 * 图片网格视窗的首行(铁律 1:不用 verticalScroll,位移自己算)。焦点行还在视窗里就不动;
 * 往上出界 → 焦点行成为首行;往下出界 → 焦点行成为视窗里最后一个完整可见的行。
 * [visibleRows] ≤ 0 按 1 算(防御:量出来之前不会用到)。
 */
internal fun keepInView(focusedRow: Int, firstVisible: Int, visibleRows: Int): Int {
    val v = visibleRows.coerceAtLeast(1)
    return when {
        focusedRow < firstVisible -> focusedRow
        focusedRow >= firstVisible + v -> focusedRow - v + 1
        else -> firstVisible
    }
}
```

`PickerGrid`:删掉 `val scroll = rememberScrollState()`,在 `holderIdx` 声明之后加:

```kotlin
    // 铁律 1:原来的 heightIn(max = 600.dp).verticalScroll(...) 换成「裁剪视窗 + 整块自算位移」,
    // 与 HomeScreen 纵向那套同一招。视窗外的行照常组合(图库张数有限,可接受)。
    val density = LocalDensity.current
    val rowGapPx = with(density) { 8.dp.roundToPx() }
    /** 一行缩略图的实测高度(首行量出来,各行等高);0 = 还没量到,此时不位移。 */
    var rowHeightPx by remember { mutableStateOf(0) }
    /** 视窗实测高度(≤ 600dp)。 */
    var viewportPx by remember { mutableStateOf(0) }
    /** 视窗顶上是第几行:只在某格报「得到焦点」时由 [keepInView] 推进。 */
    var firstVisibleRow by remember { mutableStateOf(0) }
    val pitchPx = rowHeightPx + rowGapPx
    val visibleRows =
        if (rowHeightPx > 0 && viewportPx > 0) ((viewportPx + rowGapPx) / pitchPx).coerceAtLeast(1) else rows.size
    // 删图后行数变少:首行夹回合法范围,末页不会留一截空白
    val firstRow = firstVisibleRow.coerceIn(0, (rows.size - visibleRows).coerceAtLeast(0))
    val yShift by animateDpAsState(
        targetValue = with(density) { (-(firstRow * pitchPx)).toDp() },
        animationSpec = tween(Theme.MotionInMs, easing = Theme.MotionEasing),
        label = "pickerYShift",
    )
```

把原来的

```kotlin
        Column(
            modifier = Modifier
                .heightIn(max = 600.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        rows.forEachIndexed { rowIdx, rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
```

换成

```kotlin
        Box(
            modifier = Modifier
                .heightIn(max = 600.dp)
                .clipToBounds()
                .onSizeChanged { viewportPx = it.height },
        ) {
        Column(
            modifier = Modifier
                // **必须 unbounded**(铁律 1 的另一半):不放开测量,超出 600dp 的行会被压扁 / 量成 0 高,
                // offset 发生在测量之后救不回来
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .offset(y = yShift),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        rows.forEachIndexed { rowIdx, rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = if (rowIdx == 0) Modifier.onSizeChanged { rowHeightPx = it.height } else Modifier,
            ) {
```

对应在网格 `Column` 的闭合 `}` 之后多补一个 `}` 闭合新 `Box`。每格 `onFocusChanged` 的得焦分支改成:

```kotlin
                                if (it.isFocused) {
                                    focusedIdx = idx
                                    landed = true
                                    firstVisibleRow = keepInView(rowIdx, firstVisibleRow, visibleRows)
                                }
```

(下一行 `holderIdx` 的得失处理保持不变。)import:删 `androidx.compose.foundation.rememberScrollState`、`androidx.compose.foundation.verticalScroll`;加 `androidx.compose.animation.core.animateDpAsState`、`androidx.compose.ui.draw.clipToBounds`、`androidx.compose.ui.layout.onSizeChanged`、`androidx.compose.ui.platform.LocalDensity`。

- [ ] **Step 4: 跑测试确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.PickerScrollTest'`
Expected: 4 个用例全过。

- [ ] **Step 5: 查看器改在 IO 线程、按播放器同一条规则扫描**——`ScreensaverPoolViewer` 去掉 `directory: File` 参数,把

```kotlin
    val files = remember(directory, refresh) {
        directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
```

换成

```kotlin
    val ctx = LocalContext.current
    // IO 线程扫、与播放器同一条规则(scanScreensaverLibrary)——原来在主线程按自己的扩展名表列目录,两份口径。
    // produceState 的值跨 key 保留:删图后 refresh+1 重扫期间仍显示旧列表,不会闪一下空态。null = 首次还没扫完。
    val scanned by produceState<List<File>?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            runCatching { scanScreensaverLibrary(ctx) }.getOrDefault(emptyList())
        }
    }
    val files = scanned ?: emptyList()
```

`Box` 里的 `if (files.isEmpty()) … else …` 换成:

```kotlin
        when {
            scanned == null -> Unit   // 首次扫描中(几十毫秒):只有半透明底
            files.isEmpty() -> PoolEmptyState(nonce, onDismiss)
            else -> PickerGrid(
                // 参数同原来,见 Step 7 的 nonce / covered 两处改动
            )
        }
```

(`PickerGrid(` 的实参照搬原来那一段,Step 7 再改其中两个。)import 加 `androidx.compose.ui.platform.LocalContext`。在 `MainActivity.kt` 里找到 `ScreensaverPoolViewer(` 调用,删掉 `directory = …` 那一个实参,其余不动。

- [ ] **Step 6: 预览与屏保共用一个画图函数**——`Screensaver.kt:80` 的 `private fun ScreensaverSlot` 改成 `internal fun ScreensaverSlot`,KDoc 末尾补一句「屏保图库的全屏预览也用它(间隔传 `Theme.ScreensaverIntervalMs`)」。`ImagePicker.kt` 里 `Crossfade` 内的 `PreviewSlot(files.getOrNull(idx))` 换成 `ScreensaverSlot(files.getOrNull(idx), Theme.ScreensaverIntervalMs)`,删掉整个 `PreviewSlot` 函数,再删只有它在用的 import(`Animatable`、`LinearEasing`、`androidx.compose.ui.draw.scale`——删前 grep 确认文件里没有别的用处)。行为不变:原 `PreviewSlot` 的放大时长本来就是 `Theme.ScreensaverIntervalMs + Theme.ScreensaverCrossfadeMs`。

- [ ] **Step 7: 预览自己负责焦点、网格被盖住时让路**

`PickerGrid` 加参数(放在 `onFocusedFile` 之前):

```kotlin
    /**
     * 网格上盖着别的浮层(全屏预览 / 删图确认框):它们自己负责焦点(铁律 3),网格的初始焦点循环让路——
     * 否则回到前台时(MainActivity.onResume → focusNonce++)这里会把焦点从预览底下抢走(M5 遗留)。
     */
    covered: Boolean = false,
```

初始焦点循环改成(守卫读 `covered`,key 里就有 `covered`,铁律 6;盖子掀开时 key 变、循环重跑,焦点接回 `focusedIdx`):

```kotlin
    LaunchedEffect(nonce, covered) {
        if (covered) return@LaunchedEffect
        landed = false
        val i = focusedIdx.coerceIn(0, focusRequesters.lastIndex)
        var frames = 0
        while (!landed && frames < 60) {
            withFrameNanos { }
            runCatching { focusRequesters[i].requestFocus() }
            frames++
        }
    }
```

`ScreensaverPoolViewer`:删掉 `previewCloses` 变量及其注释(由 `covered` 接替:预览关掉 → `covered` 变 false → 网格循环重跑);`PickerGrid(` 的 `nonce = nonce + previewCloses` 改回 `nonce = nonce`,并加 `covered = previewIndex >= 0 || deleteTarget != null,`;预览的 `onDismiss = { previewIndex = -1; previewCloses++ }` 改成 `onDismiss = { previewIndex = -1 }`;调用 `ScreensaverPreview(` 时多传 `nonce = nonce,`。

`ScreensaverPreview` 加参数 `nonce: Int`(放在 `startIndex` 之后,KDoc:「外层焦点 nonce(MainActivity.focusNonce):回到前台等时刻 +1,预览据此重新落焦点」),把 `var landed by remember { mutableStateOf(false) }` 换成 `var focused by remember { mutableStateOf(false) }`,`onFocusChanged { if (it.isFocused) landed = true }` 换成 `onFocusChanged { focused = it.isFocused }`,末尾的 `LaunchedEffect(Unit) { … }` 换成:

```kotlin
    // 以 nonce 为 key(原来是 Unit,只落一次地):回到前台时 nonce 变,预览自己把焦点要回来。
    // 判据是自报的 focused(得失都报),不是只写 true 的 landed——已经有焦点时这里一次都不请求。
    LaunchedEffect(nonce) {
        var frames = 0
        while (!focused && frames < 60) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            frames++
        }
    }
```

- [ ] **Step 8: 删图失败给提示**——`MainActivity.deletePoolImage` 里的

```kotlin
            if (!gone) android.util.Log.w("UnitedU", "屏保图删不掉: ${file.name}")
```

换成

```kotlin
            if (!gone) {
                android.util.Log.w("UnitedU", "屏保图删不掉: ${file.name}")
                toast(getString(R.string.toast_pool_delete_failed, file.name))
            }
```

三套 `strings.xml` 各在 `toast_system_screensaver_unavailable` 那一行之后加:

- `values`:`<string name="toast_pool_delete_failed">「%1$s」没能删掉</string>`
- `values-zh-rTW`:`<string name="toast_pool_delete_failed">「%1$s」無法刪除</string>`
- `values-en`:`<string name="toast_pool_delete_failed">Couldn\'t delete “%1$s”</string>`

- [ ] **Step 9: 构建 + 模拟器验证**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`,Expected: BUILD SUCCESSFUL、0 失败。然后(模拟器已开着;没开就按 CLAUDE.md「模拟器」一节起):

```bash
source scripts/env.sh
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
# 造 24 张测试图(8 行 × 3 列,超过 600dp 视窗),推进图库;测完删掉
python3 - <<'EOF'
from PIL import Image
import colorsys
for i in range(24):
    r,g,b = [int(c*255) for c in colorsys.hsv_to_rgb(i/24, .6, .9)]
    Image.new('RGB', (1920,1080), (r,g,b)).save(f'/tmp/lf-{i:02d}.jpg', quality=80)
EOF
adb -s emulator-5554 shell mkdir -p /sdcard/Android/data/com.uniteduone.launcher/files/library/screensavers
for f in /tmp/lf-*.jpg; do adb -s emulator-5554 push "$f" /sdcard/Android/data/com.uniteduone.launcher/files/library/screensavers/ >/dev/null; done
adb -s emulator-5554 shell am start -n com.uniteduone.launcher/.MainActivity
```

(图库真实路径以 `Paths.screensaverLibrary` 为准;与上面不同就改用它返回的目录。)从设置页「待机与屏保 → 屏保图库 ▸」打开图库,每次按键之间留 0.4 s(CLAUDE.md 坑),每步 `adb -s emulator-5554 exec-out screencap -p` 截图并 `uiautomator dump` 数 `focused="true"`:
1. 一路按 DOWN 到第 8 行再一路按 UP 回第 1 行:每一步焦点数 = 1,焦点格始终在视窗内完整可见(截图核对),不出现焦点跑到视窗外、或整块停在半截的情况。
2. 在第 6 行某格按确定进预览,按 RIGHT 翻两张;`adb -s emulator-5554 shell input keyevent KEYCODE_SLEEP`,3 s 后 `KEYCODE_WAKEUP`(走 onPause/onResume → focusNonce++):回来后焦点在预览上(再按 RIGHT 能翻页、截图左下角序号变化),网格没有抢走焦点;按返回关预览,焦点落回进预览前那一格。
3. 长按某格约 0.6 s 出删图确认框(模拟器长按:`settings put secure long_press_timeout 700` + `input keyevent --longpress KEYCODE_DPAD_CENTER`,测完改回 400),按确认删一张:焦点落在补上来的那张;再长按 → 取消:焦点回原格。两次都截图核对该缩略图**没有残留的按压遮罩**(遗留 #8;有残留则记下复现步骤并修:给该格的 `clickable` 传一个 `MutableInteractionSource`,在确认框关闭时对它 `tryEmit(PressInteraction.Cancel(press))`;没有残留就在报告里写「未复现,判为不存在」)。
4. 删到只剩 0 张:空态出现,焦点在「按返回关闭」。
5. 测完:`adb -s emulator-5554 shell rm -rf` 掉本步推入的 `lf-*.jpg`(只删这些),`rm /tmp/lf-*.jpg`。

删图失败的提示在模拟器上造不出失败(外置存储是 FUSE,改不了权限),靠代码审查确认;在报告里写明未实测。

- [ ] **Step 10: 提交**

```bash
git add app/src/main/java/com/uniteduone/launcher/ImagePicker.kt app/src/main/java/com/uniteduone/launcher/Screensaver.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/main/res/values-en/strings.xml app/src/test/java/com/uniteduone/launcher/PickerScrollTest.kt
git commit -m "fix(gallery): no scroll container, IO scan with the player's rule, one image slot, preview owns its focus, delete-failure toast

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: 首页与菜单的焦点——菜单看门狗、输入源行标题占位、指针输入核查

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/GearMenu.kt`(`GearMenu` 32–100、`MenuRow` 102–171)
- Modify: `app/src/main/java/com/uniteduone/launcher/AppCard.kt`(参数表 + 141–156 标题段)
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt`(`CategoryRow` 里 `AppCard(` 调用多传一个参数)
- 视 Step 1 的根因而定,可能还动 `HomeScreen.kt` / `MainActivity.kt` 里抢焦点的那一处

**Interfaces:**
- Produces: `AppCard(…, reserveTitleSpace: Boolean = false)`;`MenuRow(…, onFocusChange: (Boolean) -> Unit)`(原 `onFocused: () -> Unit`)。

- [ ] **Step 1: 复现冷启动菜单无焦点,找抢焦点的人**(superpowers:systematic-debugging 的做法:先复现、再定位、最后改)

```bash
source scripts/env.sh
for n in 1 2 3 4 5; do
  adb -s emulator-5554 shell am force-stop com.uniteduone.launcher
  adb -s emulator-5554 shell am start -n com.uniteduone.launcher/.MainActivity >/dev/null
  sleep 3
  adb -s emulator-5554 shell input keyevent KEYCODE_MENU
  sleep 1
  adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml >/dev/null
  echo "run $n: focused=$(adb -s emulator-5554 shell cat /sdcard/ui.xml | grep -o 'focused="true"' | wc -l)"
done
```

把启动后等待改成 1 / 2 / 4 s 各再跑一轮。菜单开着时焦点数应为 1。若出现 0:临时在 `MenuRow` 的 `onFocusChanged`、`HomeScreen` 的焦点上报(`report`)、`HomeScreen` 的还原效果与看门狗里加 `Log.d("LF", …)`(带 `System.nanoTime()`),`adb -s emulator-5554 logcat -s LF` 看菜单项得焦之后是谁的请求 / 上报紧随其后——那就是抢焦点的人。它必须在菜单开着时让路(同 HomeScreen 里 `menuOpen` 的写法,守卫进 key,铁律 6)。临时日志在提交前全部删掉。根因写进报告。

- [ ] **Step 2: 菜单看门狗(不管根因是什么都要有,铁律 3)**——`MenuRow` 的参数 `onFocused: () -> Unit = {}` 换成 `onFocusChange: (Boolean) -> Unit = {}`,`onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }` 换成 `onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }`。`GearMenu` 里把 `var landed by remember { mutableStateOf(false) }` 与原初始焦点循环换成:

```kotlin
    /** 现在持有焦点的那一项(只信控件自报,铁律 4);null = 菜单里没有。与 focusedIdx(回来落哪)分开(铁律 5)。 */
    var holder by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(nonce) {
        val i = focusedIdx.coerceIn(0, rowFocus.lastIndex)
        var frames = 0
        while (holder == null && frames < 60) {
            withFrameNanos { }
            runCatching { rowFocus[i].requestFocus() }
            frames++
        }
    }
    // 看门狗(铁律 3):初始循环落地之后焦点又被清掉(M7 实测:装包后约 4 s 按 MENU,菜单开着但焦点数为 0,
    // 第一下 DOWN 才落到第 1 项)时,初始循环早已退出,没有人会再请求。守卫与 key 都是 holder == null(铁律 6)。
    LaunchedEffect(holder == null) {
        if (holder != null) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }   // 换项时 lost / got 可能分属相邻两帧,中间那一帧的 null 不算丢
        while (holder == null) {
            runCatching { rowFocus[focusedIdx.coerceIn(0, rowFocus.lastIndex)].requestFocus() }
            withFrameNanos { }
        }
    }
```

`MenuRow(` 调用处的 `onFocused = { landed = true; focusedIdx = i }` 换成:

```kotlin
                    onFocusChange = { got ->
                        // 得失顺序保护(同 HomeScreen.report):只有「本项仍是持有者」时 lost 才作废
                        if (got) { holder = i; focusedIdx = i } else if (holder == i) holder = null
                    },
```

- [ ] **Step 3: 复验冷启动**——重跑 Step 1 的循环(1 / 2 / 3 / 4 s 各 5 次):每次焦点数 = 1,且 `uiautomator` 里持焦节点是菜单第 1 项(按 bounds 与文字核对)。再验菜单常规路径:MENU 开 → DOWN 两次 → 确定进对应界面 → 返回 → 首页焦点回到开菜单前那张卡;长按卡片菜单同样开合一次。

- [ ] **Step 4: 输入源行也空出标题那一行**——`AppCard` 参数表在 `title` 之后加:

```kotlin
    /**
     * 本卡不显示标题、但同屏应用行显示时,照样空出标题那一行的高度:每一行的高度才都等于
     * HomeLayout.rowPitch,纵向锚点不会因为输入源行少 20dp 而整体偏移(M8 终审遗留)。
     */
    reserveTitleSpace: Boolean = false,
```

标题段 `if (title != null) { … }` 之后接:

```kotlin
        else if (reserveTitleSpace) {
            Spacer(Modifier.height(metrics.titleGap + metrics.titleLine))
        }
```

(需要的话补 import `androidx.compose.foundation.layout.Spacer`、`androidx.compose.foundation.layout.height`。)`HomeScreen.kt` 的 `CategoryRow` 里 `AppCard(` 调用在 `title = …` 之后加 `reserveTitleSpace = showTitles,`。应用行此时 `title` 非空,不受影响;只有输入源行走到新分支。模拟器没有硬件输入(`dumpsys tv_input` 为空,输入源行不渲染),本条只能在 A95L 上验——写进 T5 的真机清单。模拟器上验不回归:标题开 / 关各截一张首页,应用行的行距与改前逐像素一致(改前先截基线)。

- [ ] **Step 5: 核查指针输入会不会清空焦点**——焦点停在第 2 行第 3 张卡时分别执行:

```bash
adb -s emulator-5554 shell input mouse tap 960 1000
adb -s emulator-5554 shell input keyevent KEYCODE_DPAD_RIGHT
adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml >/dev/null && adb -s emulator-5554 shell cat /sdcard/ui.xml | grep -o 'focused="true"[^>]*bounds="[^"]*"'
```

以及把 `mouse` 换成 `touchscreen` 再做一遍。预期:鼠标点击(飞鼠遥控器就是 `SOURCE_MOUSE`)之后 RIGHT 让焦点落在第 2 行第 4 张。若鼠标路径正常、只有触摸屏路径丢焦点 → 结论「只影响触摸屏,电视没有触摸屏,不修」,写进报告即可。若鼠标路径也丢 → 在 `MainActivity` 里重写 `dispatchGenericMotionEvent` 与 `dispatchTouchEvent`:先 `super`,在 `ACTION_UP` / `ACTION_BUTTON_RELEASE` 时 `focusNonce++`(让首页 / 设置页等的还原效果把焦点送回冻结的目标),再复验两条路径。

- [ ] **Step 6: 构建 + 提交**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`,Expected: BUILD SUCCESSFUL、0 失败。

```bash
git add -A app/src/main/java/com/uniteduone/launcher/
git commit -m "fix(focus): gear menu watchdog; input row reserves the title line; pointer-input check

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

(提交前 `git diff --cached` 确认临时 `Log.d("LF"…)` 已全部删掉。)

---

### Task 4: 卫生项——死代码、测试、英文大小写、显式依赖

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Theme.kt`(`CardMetrics` 14–29、`CardFallbackText` 121–122、`cardMetrics` 133–147)
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeLayout.kt`(`scrimHeight` 及其 KDoc)
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt:66`(注释)、`:105`(`cardMetrics` 调用)
- Modify: `app/src/test/java/com/uniteduone/launcher/HomeLayoutTest.kt:53-56`、`app/src/test/java/com/uniteduone/launcher/SettingsTest.kt:58`
- Modify: `app/src/main/res/values-en/strings.xml`(5 行)
- Modify: `app/build.gradle.kts`(依赖块)
- Test: `app/src/test/java/com/uniteduone/launcher/ThemeMetricsTest.kt`(新建)

**Interfaces:**
- Produces: `Theme.cardMetrics(cardsPerRow: Int): CardMetrics`(去掉已无作用的 `showTitles` 参数);`CardMetrics` 不再有 `rowPitch` / `titleHeight` 字段(`titleGap` / `titleLine` 保留,T3 在用)。

- [ ] **Step 1: 写失败的测试**——新建 `ThemeMetricsTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 卡片档位的回落(M8 终审遗留):非法张数一律按中档 6 张。 */
class ThemeMetricsTest {
    @Test fun invalidCardsPerRowFallsBackToSix() {
        assertEquals(Theme.cardMetrics(6), Theme.cardMetrics(7))
        assertEquals(Theme.cardMetrics(6), Theme.cardMetrics(0))
        assertEquals(Theme.cardMetrics(6), Theme.cardMetrics(-3))
    }

    @Test fun validCountsKeepTheirOwnWidth() {
        assertNotEquals(Theme.cardMetrics(6).cardWidth, Theme.cardMetrics(5).cardWidth)
        assertNotEquals(Theme.cardMetrics(6).cardWidth, Theme.cardMetrics(8).cardWidth)
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.ThemeMetricsTest'`
Expected: 两个用例**直接通过**(回落逻辑早就在,本条是补测试不是修行为;若 `Theme` 的静态初始化在 JVM 上失败,报告里写明并改成断言 `HomeLayout.cardWidth` 与 `VALID_CARDS_PER_ROW` 的组合,不改产品代码)。

- [ ] **Step 3: 删死代码**
  - `Theme.kt`:删 `CardMetrics` 的 `rowPitch`、`titleHeight` 两个字段(连同 KDoc),`cardMetrics` 去掉 `showTitles` 参数和对应两行赋值;删 `CardFallbackText` 及其 KDoc 行。
  - `HomeScreen.kt:105` 的 `Theme.cardMetrics(cardsPerRow, showTitles)` 改成 `Theme.cardMetrics(cardsPerRow)`;`:66` 注释里的「(见 Theme.cardMetrics 的 titleHeight)」改成「(见 HomeLayout.titleHeight)」。`grep -rn "cardMetrics(" app/src` 确认没有别的调用传两个参数。
  - `HomeLayout.kt`:删 `scrimHeight` 及其 KDoc;`grep -rn "SCRIM_LEAD" app/src` 若只剩常量定义本身,连常量一起删,否则保留。
  - `HomeLayoutTest.kt`:`anchorIsTwoThirdsAndScrimReachesSixtyAbove` 改名 `anchorIsTwoThirds`,删 `scrimHeight` 那一行断言。
- [ ] **Step 4: 测试改名**——`SettingsTest.kt:58` 的 `themePresetIdFallsBackToGoldWhenAbsentOrBlank` 改成 `themePresetIdFallsBackToMaterialWhenAbsentOrBlank`(断言本来就是 `"material"`,只改名)。
- [ ] **Step 5: 英文设置项统一 Title Case**(同组 M2/M3 的「Standby Timeout」「Card Size」都是 Title Case)——`values-en/strings.xml`:
  - `settings_wallpaper_rotate`:`Auto-change wallpaper` → `Auto-Change Wallpaper`
  - `settings_screensaver_after`:`Screensaver starts` → `Screensaver Starts`
  - `settings_screensaver_interval`:`Slideshow interval` → `Slideshow Interval`
  - `settings_screensaver_gallery`:`Screensaver gallery` → `Screensaver Gallery`
  - `settings_system_screensaver`:`System screensaver` → `System Screensaver`
- [ ] **Step 6: 显式声明 lifecycle / savedstate**——先记下现在解析到的版本:

```bash
source scripts/env.sh && gradle --no-daemon :app:dependencies --configuration releaseRuntimeClasspath > /tmp/lf-deps-before.txt
grep -E "androidx\.lifecycle:lifecycle-runtime:|androidx\.savedstate:savedstate:" /tmp/lf-deps-before.txt | head
```

取箭头右边(`->`)的最终版本,在 `app/build.gradle.kts` 的 `implementation("androidx.activity:activity-compose:1.9.3")` 之后加(版本号填上面读到的,**不升级**):

```kotlin
    // UnitedUDream 直接用 LifecycleRegistry / setViewTreeLifecycleOwner / SavedStateRegistryController,
    // 显式声明、不靠 activity-compose 的传递依赖;版本 = 声明时实际解析到的版本(M5 终审遗留)
    implementation("androidx.lifecycle:lifecycle-runtime:<解析到的版本>")
    implementation("androidx.savedstate:savedstate:<解析到的版本>")
```

再跑一遍 `:app:dependencies` 到 `/tmp/lf-deps-after.txt`,`diff` 两份:除这两个直接依赖出现在顶层外,任何库的最终版本都不应变化。

- [ ] **Step 7: 全量构建 + 提交**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`,Expected: BUILD SUCCESSFUL、0 失败。

```bash
git add app/src/main/java/com/uniteduone/launcher/Theme.kt app/src/main/java/com/uniteduone/launcher/HomeLayout.kt app/src/main/java/com/uniteduone/launcher/HomeScreen.kt app/src/test/java/com/uniteduone/launcher/HomeLayoutTest.kt app/src/test/java/com/uniteduone/launcher/SettingsTest.kt app/src/test/java/com/uniteduone/launcher/ThemeMetricsTest.kt app/src/main/res/values-en/strings.xml app/build.gradle.kts
git commit -m "chore: drop dead layout code, test the card-count fallback, Title Case English labels, declare lifecycle/savedstate

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: 模拟器回归 + 文档同步

**Files:**
- Modify: `CLAUDE.md`(铁律 3 的焦点责任表)
- Modify: `docs/WORKLOG.md`(新增一节)

- [ ] **Step 1: 全量构建**:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`,记下测试总数与 0 失败;`adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk`。
- [ ] **Step 2: 回归**(每步 0.4 s 间隔,截图 + 焦点数;遵守 CLAUDE.md「模拟器验证的坑」):首页四向导航与同列规则;右上 pill 两个按钮;长按卡片菜单开合;齿轮菜单开合(含冷启动 3 s 内开);设置页「待机与屏保」六行上下左右;屏保按钮进轮播、任意键回原卡;图库 24 张翻到底再翻回、预览、删图、取消;标题开 / 关各看一次首页。任何一步失败:按 systematic-debugging 定位根因、修、再从这一步重跑。
- [ ] **Step 3: 焦点责任表**——`CLAUDE.md` 铁律 3 的表:
  - 「齿轮菜单 / 长按卡片菜单」一行改成:`GearMenu 自己的初始焦点循环(nonce)+ 看门狗(holder == null 时重请求 focusedIdx,守卫与 key 同为 holder == null)`。
  - 「屏保图库的全屏预览(M5 起可达)」一行改成:`自己的焦点循环,以外层 nonce 为 key(回到前台会重落);开着时网格的循环让路(PickerGrid 的 covered,预览或删图确认框任一在场即为 true);关掉后 covered 翻回 false,网格循环重跑接回 focusedIdx`。
  - 「屏保图库的删除确认框(M5)」一行里「由图库网格的 nonce 循环接回原位置」补成「由图库网格的循环接回原位置(covered 翻回 false 触发)」。
- [ ] **Step 4: WORKLOG**——新增「2026-09-19 · 遗留修复批(leftover-fixes)」一节:每条遗留的处理结果(修了 / 核查后判为不存在 / 只能真机验)、Step 2 的回归结果、单测总数,以及**A95L 真机清单**(与 M4b 合并验收时用):①设置里同时打开「卡片标题」与「输入源行」,从第 1 行一路按到最后一行,每一行的行标题都停在同一高度(不再差 20dp);②屏保图库放 20 张以上,翻到底再翻回、进预览、在预览里等系统屏保触发再唤醒:回来焦点仍在预览;③装新包后 3 秒内按菜单键:菜单第 1 项已高亮;④英文界面下设置「待机与屏保」组六行大小写一致。
- [ ] **Step 5: 提交**

```bash
git add CLAUDE.md docs/WORKLOG.md
git commit -m "docs: leftover-fixes results, focus-owner table, A95L checks

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
