# M3(壁纸轮播 + 主题化管线 + 内置壁纸)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 壁纸从 `library/wallpapers/` 轮播(关/5 分/30 分/每天)、可一键主题化(去色→染主题色→模糊→压暗,CPU 离线处理 + 缓存)、模糊/压暗两个滑块实时预览、内置 6 张程序生成壁纸并把默认底换成中性暗。

**Architecture:** 「当前壁纸」不再是复制出来的 `wallpaper.jpg`,而是 `settings.json` 里的一个文件名(指向 library),轮播/选图只改字段、`settingsRevision++` 重读,不再 `recreate()`。处理管线是纯 CPU:先缩小再套一个合成的 `ColorMatrix` 再放大(线性算子可交换),产物按「源文件 + 参数」哈希缓存成 JPEG。不碰 Android 类的数学与轮播逻辑单独放 `WallpaperMath.kt`,纯 JVM 单测。

**Tech Stack:** Kotlin 2.0.21 / Compose(BOM 2024.10.01)/ AGP 8.7.3 / JUnit 4;`android.graphics.ColorMatrix` + `Canvas`;素材生成用本机 Python 3 + Pillow(已装,不依赖 ffmpeg)。

**Spec:** `docs/superpowers/specs/2026-09-15-m3-wallpaper-design.md`(本计划逐节实现它;执行者两份都读)。

## Global Constraints

- `minSdk 28`:**不用 `RenderEffect`**(API 31),管线全 CPU。
- **绝不用可滚动容器**(LazyRow/LazyColumn/scroll);设置页位移自算(`offset` + `animateDpAsState` + `wrapContentHeight(unbounded = true)`),焦点账本按 `CLAUDE.md` 七条铁律。
- 颜色/尺寸字面量只在 `Theme.kt` / `ThemePresets.kt`;新常量加在 `Theme`。
- 文案全部进 `strings.xml` 三份:`values`(简中)/ `values-en` / `values-zh-rTW`(台湾用语:壁纸=桌布)。
- **零回归不变量**:6 个新字段默认全零/空/关 ⇒ 首页壁纸观感与 M2 收官逐位一致(含 RGBA_F16 HDR 路径)。
- `settings.json` 读写只经 `SettingsStore`;任何输入不崩、非法值夹到合法域。
- 每个任务:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease` 通过再 commit;先红后绿。
- 提交信息末尾 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`。
- 构建/模拟器命令见根 `CLAUDE.md`;临时文件放执行者自己的 scratchpad 目录(下文写作 `$SCRATCH`)。

## 并行波次(依赖图;串行执行也按此顺序)

| 波 | 可并行的任务 | 互不碰的文件 | 汇合时要手解的冲突 |
|---|---|---|---|
| 1 | **Task 1 → 2**(Settings 字段 + 纯函数)‖ **Task 3**(生成脚本 + 素材 + NOTICE) | 1/2 只碰 `Settings*.kt`、`WallpaperMath*.kt`;3 只碰 `scripts/`、`assets/`、`NOTICE`、`HomeScreen.kt` 两处字符串 | 无 |
| 2 | **Task 4 → 5 → 6**(壁纸引擎,同一执行者串做)‖ **Task 7**(设置页 UI) | 4–6 碰 `Wallpapers.kt`、`Paths.kt`、`HomeScreen.kt`;7 碰 `SettingsScreen.kt`、`strings.xml` ×3 | 两边都**加行**到 `MainActivity.kt`(4 加 `settingsRevision`/spec,7 加 `onWallpaperParamsChanged` 接线)和 `Theme.kt`(各加一个常量);Task 7 若先于 Task 4 落地,按其 Step 里的说明自己补 `settingsRevision` 字段,汇合时两份相同、取一即可 |
| 3 | **Task 8**(模拟器验收 + 文档)→ **Task 9**(真机调参回填,等 Gordon) | — | — |

模拟器只有一台 AVD:波 2 两个执行者的模拟器验证步骤要排队。

---

### Task 1: Settings 新增 6 个壁纸字段 + 值表 + 单测

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Settings.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/SettingsTest.kt`

**Interfaces:**
- Produces: `Settings.wallpaperFile: String`(默认 `""`)、`wallpaperRotateMs: Long`(0)、`wallpaperRotatedAt: Long`(0)、`wallpaperThemed: Boolean`(false)、`wallpaperBlur: Int`(0)、`wallpaperDim: Int`(0);`internal val VALID_WALLPAPER_ROTATE_MS = longArrayOf(0L, 300_000L, 1_800_000L, 86_400_000L)`;`internal fun sanitizeWallpaperFileName(name: String?): String`。
- Consumes: 现有 `parseSettings` / `toJson` / `extract*` 帮助函数。

- [ ] **Step 1: 写失败的测试** — 在 `SettingsTest.kt` 末尾(最后一个 `}` 之前)追加:

```kotlin
    @Test fun wallpaperFieldsDefaultWhenAbsent() {
        val s = parseSettings("{}")
        assertEquals("", s.wallpaperFile)
        assertEquals(0L, s.wallpaperRotateMs)
        assertEquals(0L, s.wallpaperRotatedAt)
        assertFalse(s.wallpaperThemed)
        assertEquals(0, s.wallpaperBlur)
        assertEquals(0, s.wallpaperDim)
    }

    @Test fun wallpaperFileRejectsPathEscapes() {
        // settings.json 用户可手改;文件名只能指向 library/wallpapers/ 里的一个条目
        assertEquals("", parseSettings("""{"wallpaperFile": "../x.jpg"}""").wallpaperFile)
        assertEquals("", parseSettings("""{"wallpaperFile": "a/b.jpg"}""").wallpaperFile)
        assertEquals("", parseSettings("""{"wallpaperFile": "a\\b.jpg"}""").wallpaperFile)
        assertEquals("", parseSettings("""{"wallpaperFile": "   "}""").wallpaperFile)
        assertEquals("unitedu-00-neutral.jpg",
            parseSettings("""{"wallpaperFile": "unitedu-00-neutral.jpg"}""").wallpaperFile)
    }

    @Test fun wallpaperRotateMsMustBeOneOfAllowedValues() {
        assertEquals(0L, parseSettings("""{"wallpaperRotateMs": 12345}""").wallpaperRotateMs)
        for (v in listOf(0L, 300_000L, 1_800_000L, 86_400_000L)) {
            assertEquals(v, parseSettings("""{"wallpaperRotateMs": $v}""").wallpaperRotateMs)
        }
    }

    @Test fun wallpaperRotatedAtNeverNegative() {
        assertEquals(0L, parseSettings("""{"wallpaperRotatedAt": -5}""").wallpaperRotatedAt)
        assertEquals(1_700_000_000_000L,
            parseSettings("""{"wallpaperRotatedAt": 1700000000000}""").wallpaperRotatedAt)
    }

    @Test fun blurAndDimClampAndSnapToTens() {
        assertEquals(0, parseSettings("""{"wallpaperBlur": -20}""").wallpaperBlur)
        assertEquals(100, parseSettings("""{"wallpaperBlur": 250}""").wallpaperBlur)
        assertEquals(50, parseSettings("""{"wallpaperBlur": 54}""").wallpaperBlur)
        assertEquals(60, parseSettings("""{"wallpaperBlur": 55}""").wallpaperBlur)
        assertEquals(100, parseSettings("""{"wallpaperDim": 96}""").wallpaperDim)
        assertEquals(0, parseSettings("""{"wallpaperDim": "x"}""").wallpaperDim)
    }

    @Test fun wallpaperFieldsRoundTrip() {
        val s = Settings(
            wallpaperFile = "sea.jpg",
            wallpaperRotateMs = 1_800_000L,
            wallpaperRotatedAt = 1_700_000_000_000L,
            wallpaperThemed = true,
            wallpaperBlur = 30,
            wallpaperDim = 70,
        )
        assertEquals(s, parseSettings(s.toJson()))
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd ~/GitHub/UnitedU-launcher && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest 2>&1 | grep -E 'Unresolved reference|FAILED|BUILD' | head -5
```
Expected: `Unresolved reference: wallpaperFile`(编译失败即红)。

- [ ] **Step 3: 实现** — `Settings.kt`:

(a)`data class Settings` 在 `idleContent` 之后追加 6 个字段:
```kotlin
    val idleContent: IdleContent = IdleContent.CLOCK_ONLY,
    // ---- M3 壁纸(spec §1)。默认全零/空/关 ⇒ 首页观感与 M2 逐位一致 ----
    /** library/wallpapers/ 里的文件名;空 = 未指定(解析顺序见 Wallpapers.resolveSource)。 */
    val wallpaperFile: String = "",
    /** 轮播间隔 ms;0 = 关。合法值见 [VALID_WALLPAPER_ROTATE_MS]。 */
    val wallpaperRotateMs: Long = 0L,
    /** 上次轮换的 epoch ms;「每天」档靠它跨重启续等。 */
    val wallpaperRotatedAt: Long = 0L,
    /** 主题化壁纸(去色→染主题色);默认关,守住 M2「默认背景不随主题」。 */
    val wallpaperThemed: Boolean = false,
    /** 模糊 0–100,步 10。 */
    val wallpaperBlur: Int = 0,
    /** 压暗 0–100,步 10。 */
    val wallpaperDim: Int = 0,
)
```

(b)`VALID_IDLE_AFTER_MS` 之后追加值表与夹取函数:
```kotlin
internal val VALID_WALLPAPER_ROTATE_MS = longArrayOf(0L, 300_000L, 1_800_000L, 86_400_000L)

private fun snapRotateMs(v: Long?): Long =
    if (v != null && VALID_WALLPAPER_ROTATE_MS.contains(v)) v else 0L

/** 0..100 夹取后四舍五入到 10 的倍数(滑块 11 档);解析不出数字 → 该字段的默认值(真机调参后默认可能非零)。 */
private fun clampPercentStep10(v: Int?, default: Int): Int =
    if (v == null) default else ((v.coerceIn(0, 100) + 5) / 10) * 10

private fun clampEpoch(v: Long?): Long = (v ?: 0L).coerceAtLeast(0L)

/**
 * 壁纸文件名只能指向 library/wallpapers/ 里的一个条目:含路径分隔符或 `..` 的一律当没写。
 * `internal`:Wallpapers.select 写入前也走同一道清洗。
 */
internal fun sanitizeWallpaperFileName(name: String?): String {
    val n = name?.trim() ?: return ""
    if (n.isEmpty() || n.contains('/') || n.contains('\\') || n.contains("..")) return ""
    return n
}
```

(c)`parseSettings` 的 `Settings(...)` 构造里,`idleContent = ...` 之后追加:
```kotlin
            wallpaperFile = sanitizeWallpaperFileName(extractString(json, "wallpaperFile")),
            wallpaperRotateMs = snapRotateMs(extractLong(json, "wallpaperRotateMs")),
            wallpaperRotatedAt = clampEpoch(extractLong(json, "wallpaperRotatedAt")),
            wallpaperThemed = extractBoolean(json, "wallpaperThemed") ?: d.wallpaperThemed,
            wallpaperBlur = clampPercentStep10(extractInt(json, "wallpaperBlur"), d.wallpaperBlur),
            wallpaperDim = clampPercentStep10(extractInt(json, "wallpaperDim"), d.wallpaperDim),
```

(d)`toJson()`:把 `append("  \"idleContent\": \"${idleContent.name}\"\n")` 改成带逗号,并追加 6 行:
```kotlin
        append("  \"idleContent\": \"${idleContent.name}\",\n")
        append("  \"wallpaperFile\": \"${esc(wallpaperFile)}\",\n")
        append("  \"wallpaperRotateMs\": $wallpaperRotateMs,\n")
        append("  \"wallpaperRotatedAt\": $wallpaperRotatedAt,\n")
        append("  \"wallpaperThemed\": $wallpaperThemed,\n")
        append("  \"wallpaperBlur\": $wallpaperBlur,\n")
        append("  \"wallpaperDim\": $wallpaperDim\n")
```

- [ ] **Step 4: 跑测试确认全绿**

```bash
gradle --no-daemon testReleaseUnitTest 2>&1 | grep -E 'FAILED|BUILD|tests completed'
```
Expected: `BUILD SUCCESSFUL`,无 FAILED(原 14 + 新 6 = 20 个)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/Settings.kt app/src/test/java/com/uniteduone/launcher/SettingsTest.kt
git commit -m "feat(settings): wallpaper rotation/themed/blur/dim fields with clamping

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: `WallpaperMath.kt` 纯函数 + 单测

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/WallpaperMath.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/WallpaperMathTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `Settings` 壁纸字段。
- Produces(后续任务按名引用):
  - `data class WallpaperSpec(file: String, themed: Boolean, accentRgb: Int, blur: Int, dim: Int)`,`val isIdentity: Boolean`
  - `fun wallpaperSpecOf(s: Settings, accentRgb: Int): WallpaperSpec`
  - `fun nextWallpaper(names: List<String>, current: String): String?`
  - `fun rotationDelayMs(rotatedAt: Long, intervalMs: Long, nowMs: Long): Long`
  - `fun blurTargetWidth(blur: Int, fullWidth: Int = 1920): Int`
  - `fun wallpaperColorMatrix(themed: Boolean, accentRgb: Int, dim: Int): FloatArray`(20 个,android `ColorMatrix` 行主序)
  - `fun wallpaperCacheKey(path: String, mtime: Long, size: Long, themed: Boolean, accentRgb: Int, blur: Int, dim: Int): String`(40 位 hex)

- [ ] **Step 1: 写失败的测试** — 新建 `WallpaperMathTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperMathTest {

    @Test fun nextWallpaperCyclesInNameOrder() {
        val names = listOf("c.jpg", "a.jpg", "b.jpg")
        assertEquals("b.jpg", nextWallpaper(names, "a.jpg"))
        assertEquals("a.jpg", nextWallpaper(names, "c.jpg"))   // 末尾回到开头
    }

    @Test fun nextWallpaperUnknownCurrentStartsFromFirst() {
        assertEquals("a.jpg", nextWallpaper(listOf("b.jpg", "a.jpg"), "zzz.jpg"))
        assertEquals("a.jpg", nextWallpaper(listOf("b.jpg", "a.jpg"), ""))
    }

    @Test fun nextWallpaperEmptyAndSingle() {
        assertNull(nextWallpaper(emptyList(), "a.jpg"))
        assertEquals("only.jpg", nextWallpaper(listOf("only.jpg"), "only.jpg"))
    }

    @Test fun rotationDelayClampsToZeroAndOneInterval() {
        assertEquals(0L, rotationDelayMs(rotatedAt = 0L, intervalMs = 300_000L, nowMs = 1_000_000L))      // 早过期
        assertEquals(200_000L, rotationDelayMs(rotatedAt = 900_000L, intervalMs = 300_000L, nowMs = 1_000_000L))
        assertEquals(300_000L, rotationDelayMs(rotatedAt = 5_000_000L, intervalMs = 300_000L, nowMs = 1_000_000L)) // 时钟回拨
    }

    @Test fun blurTargetWidthIsMonotonicAndDistinctAcrossElevenSteps() {
        val widths = (0..100 step 10).map { blurTargetWidth(it) }
        assertEquals(1920, widths.first())
        assertEquals(120, widths.last())
        for (i in 1 until widths.size) assertTrue("step $i", widths[i] < widths[i - 1])
    }

    @Test fun colorMatrixIsIdentityWhenUnthemedAndUndimmed() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        assertArrayEquals(identity, wallpaperColorMatrix(themed = false, accentRgb = 0, dim = 0), 1e-6f)
    }

    @Test fun colorMatrixDimScalesRgbDiagonalOnly() {
        val m = wallpaperColorMatrix(themed = false, accentRgb = 0, dim = 50)
        assertEquals(0.5f, m[0], 1e-6f)
        assertEquals(0.5f, m[6], 1e-6f)
        assertEquals(0.5f, m[12], 1e-6f)
        assertEquals(1f, m[18], 1e-6f)   // alpha 不动
    }

    @Test fun colorMatrixThemedMapsWhiteToAccent() {
        // 白 (1,1,1) 经去色 = 亮度 1,再染色 → 恰好等于主题色
        val m = wallpaperColorMatrix(themed = true, accentRgb = 0xC0A73A, dim = 0)
        assertEquals(0xC0 / 255f, m[0] + m[1] + m[2], 1e-4f)
        assertEquals(0xA7 / 255f, m[5] + m[6] + m[7], 1e-4f)
        assertEquals(0x3A / 255f, m[10] + m[11] + m[12], 1e-4f)
    }

    @Test fun colorMatrixThemedUsesRec709LumaLikeAndroidSetSaturation() {
        val m = wallpaperColorMatrix(themed = true, accentRgb = 0xFFFFFF, dim = 0)
        assertEquals(0.213f, m[0], 1e-6f)
        assertEquals(0.715f, m[1], 1e-6f)
        assertEquals(0.072f, m[2], 1e-6f)
    }

    @Test fun cacheKeyChangesWhenAnyParamChanges() {
        val base = wallpaperCacheKey("/a.jpg", 1L, 2L, false, 0, 0, 0)
        assertEquals(40, base.length)
        assertEquals(base, wallpaperCacheKey("/a.jpg", 1L, 2L, false, 0, 0, 0))
        val variants = listOf(
            wallpaperCacheKey("/b.jpg", 1L, 2L, false, 0, 0, 0),
            wallpaperCacheKey("/a.jpg", 9L, 2L, false, 0, 0, 0),
            wallpaperCacheKey("/a.jpg", 1L, 3L, false, 0, 0, 0),
            wallpaperCacheKey("/a.jpg", 1L, 2L, true, 0, 0, 0),
            wallpaperCacheKey("/a.jpg", 1L, 2L, true, 0xC0A73A, 0, 0),
            wallpaperCacheKey("/a.jpg", 1L, 2L, false, 0, 10, 0),
            wallpaperCacheKey("/a.jpg", 1L, 2L, false, 0, 0, 10),
        )
        for (k in variants) assertNotEquals(base, k)
    }

    @Test fun specDropsAccentWhenNotThemedAndKnowsIdentity() {
        val s = Settings(wallpaperThemed = false, wallpaperBlur = 10)
        assertEquals(0, wallpaperSpecOf(s, 0xC0A73A).accentRgb)          // 换预设不触发无谓重处理
        assertEquals(0xC0A73A, wallpaperSpecOf(s.copy(wallpaperThemed = true), 0xC0A73A).accentRgb)
        assertTrue(wallpaperSpecOf(Settings(), 0xC0A73A).isIdentity)
        assertFalse(wallpaperSpecOf(s, 0).isIdentity)
        assertFalse(wallpaperSpecOf(Settings(wallpaperThemed = true), 0).isIdentity)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
gradle --no-daemon testReleaseUnitTest 2>&1 | grep -E 'Unresolved reference|FAILED|BUILD' | head -5
```
Expected: `Unresolved reference: nextWallpaper`(编译失败)。

- [ ] **Step 3: 实现** — 新建 `WallpaperMath.kt`:

```kotlin
package com.uniteduone.launcher

import java.security.MessageDigest

/**
 * 壁纸处理与轮播里**不碰 Android 类**的部分,单独一个文件,好在纯 JVM 单测里直接断言。
 * Android 侧(解码 / Canvas / 文件 / Compose)在 Wallpapers.kt。
 */

/**
 * 一次壁纸渲染的全部输入。[accentRgb] 只在 [themed] 时有意义,不主题化时**恒为 0**
 * (见 [wallpaperSpecOf]):否则换个预设就会让 spec 变化、触发一次无谓的重处理。
 */
data class WallpaperSpec(
    val file: String,
    val themed: Boolean,
    val accentRgb: Int,
    val blur: Int,
    val dim: Int,
) {
    /** 参数全零:完全绕开管线,走原图 + F16 解码(零回归路径)。 */
    val isIdentity: Boolean get() = !themed && blur == 0 && dim == 0
}

fun wallpaperSpecOf(s: Settings, accentRgb: Int): WallpaperSpec = WallpaperSpec(
    file = s.wallpaperFile,
    themed = s.wallpaperThemed,
    accentRgb = if (s.wallpaperThemed) accentRgb and 0xFFFFFF else 0,
    blur = s.wallpaperBlur,
    dim = s.wallpaperDim,
)

/** library 里当前壁纸的下一张(按名排序、循环)。当前不在列表 → 第一张;空表 → null;单张 → 它自己。 */
fun nextWallpaper(names: List<String>, current: String): String? {
    if (names.isEmpty()) return null
    val sorted = names.sorted()
    val i = sorted.indexOf(current)
    return if (i < 0) sorted[0] else sorted[(i + 1) % sorted.size]
}

/** 距下一次轮换还要等多久:已过期 → 0;rotatedAt 在未来(时钟回拨)→ 最多等一个间隔。 */
fun rotationDelayMs(rotatedAt: Long, intervalMs: Long, nowMs: Long): Long =
    (rotatedAt + intervalMs - nowMs).coerceIn(0L, intervalMs)

/**
 * 模糊档位 → 缩小到的工作宽度。模糊 = 缩小再放大(spec §3.2),这里定「缩到多宽」:
 * 0 → 1920(不缩),10 → 768,50 → 226,100 → 120;11 档单调递减、无重复。
 */
fun blurTargetWidth(blur: Int, fullWidth: Int = 1920): Int {
    val b = blur.coerceIn(0, 100) / 100f
    return Math.round(fullWidth / (1f + 15f * b))
}

/**
 * 去色 → 染主题色 → 压暗 三步合成一个 4×5 ColorMatrix(android.graphics.ColorMatrix 行主序)。
 * 去色用 Rec.709 亮度权重 (0.213, 0.715, 0.072),与 `ColorMatrix.setSaturation(0)` 同值;
 * 染色 = 各通道乘主题色分量(黑→主题色的渐变映射,与金雾底同一手法);压暗 = 整体乘 (1 - dim)。
 * 不主题化时只剩压暗(对角阵)。
 */
fun wallpaperColorMatrix(themed: Boolean, accentRgb: Int, dim: Int): FloatArray {
    val k = 1f - dim.coerceIn(0, 100) / 100f
    if (!themed) {
        return floatArrayOf(
            k, 0f, 0f, 0f, 0f,
            0f, k, 0f, 0f, 0f,
            0f, 0f, k, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
    val ar = ((accentRgb shr 16) and 0xFF) / 255f
    val ag = ((accentRgb shr 8) and 0xFF) / 255f
    val ab = (accentRgb and 0xFF) / 255f
    val lr = 0.213f
    val lg = 0.715f
    val lb = 0.072f
    return floatArrayOf(
        k * ar * lr, k * ar * lg, k * ar * lb, 0f, 0f,
        k * ag * lr, k * ag * lg, k * ag * lb, 0f, 0f,
        k * ab * lr, k * ab * lg, k * ab * lb, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/** 缓存文件名:源文件身份(路径 + mtime + 大小)+ 全部参数 + 算法版本号,任一变则键变。 */
fun wallpaperCacheKey(
    path: String, mtime: Long, size: Long,
    themed: Boolean, accentRgb: Int, blur: Int, dim: Int,
): String {
    val raw = "$path|$mtime|$size|$themed|${Integer.toHexString(accentRgb)}|$blur|$dim|v1"
    val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
    return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
```

- [ ] **Step 4: 跑测试确认全绿**

```bash
gradle --no-daemon testReleaseUnitTest 2>&1 | grep -E 'FAILED|BUILD|tests completed'
```
Expected: `BUILD SUCCESSFUL`(20 + 11 = 31 个)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/WallpaperMath.kt app/src/test/java/com/uniteduone/launcher/WallpaperMathTest.kt
git commit -m "feat(wallpaper): pure math for rotation, blur scale, color matrix, cache key

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: 生成脚本 + 6 张内置壁纸 + 中性默认底 + NOTICE

**Files:**
- Create: `scripts/gen-wallpapers.py`
- Create: `app/src/main/assets/wallpapers/00-neutral.jpg` … `05-green.jpg`(脚本产出)
- Delete: `app/src/main/assets/default-wallpaper.jpg`
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt`(两处 `"default-wallpaper.jpg"` → `"wallpapers/00-neutral.jpg"`,约 367 / 603 行)
- Modify: `NOTICE`

**Interfaces:**
- Produces: 资源路径 `assets/wallpapers/<NN>-<name>.jpg`,`<name>` ∈ neutral / gold / champagne / blue / purple / green(Task 4 的 `BUILTIN_WALLPAPERS` 与 `DEFAULT_WALLPAPER_ASSET = "wallpapers/00-neutral.jpg"` 按此引用)。

- [ ] **Step 1: 写脚本** — `scripts/gen-wallpapers.py`:

```python
#!/usr/bin/env python3
"""生成 UnitedU 内置壁纸(6 张,1920×1080,JPEG q85)。

手法与 M1 金雾底相同:低频噪声 → 放大 → 高斯模糊 → 归一化 → 压暗曲线 → 乘染色。
只依赖 Pillow;参数、种子全在下面,改了重跑即可复现。
用法:python3 scripts/gen-wallpapers.py [输出目录]   默认 app/src/main/assets/wallpapers
"""
import pathlib
import random
import sys

from PIL import Image, ImageFilter, ImageOps

W, H = 1920, 1080
NOISE_W, NOISE_H = 8, 5      # 噪声格数:越少,云团越大
BLUR_RADIUS = 78             # 与 ffmpeg 版 sigma 78 同量级
# 压暗曲线(x 输入亮度, y 输出亮度),分段线性:压掉暗部,峰值 0.62
CURVE = [(0.0, 0.0), (0.55, 0.0), (0.80, 0.22), (1.0, 0.62)]

# (文件名, 染色 RGB, 噪声种子)。00 中性灰是默认底(M2 决策:默认背景固定中性暗);
# 其余对应主题预设(ThemePresets.kt 的 accent),石墨 ↔ 中性。
WALLPAPERS = [
    ("00-neutral",   (0x8C, 0x8C, 0x8C), 11),
    ("01-gold",      (0xC0, 0xA7, 0x3A), 12),
    ("02-champagne", (0xD9, 0xC7, 0xA0), 13),
    ("03-blue",      (0x6E, 0x8F, 0xB0), 14),
    ("04-purple",    (0x92, 0x80, 0xAA), 15),
    ("05-green",     (0x7F, 0xA0, 0x7A), 16),
]


def curve_lut():
    lut = []
    for i in range(256):
        x = i / 255
        for (x0, y0), (x1, y1) in zip(CURVE, CURVE[1:]):
            if x <= x1:
                t = (x - x0) / (x1 - x0) if x1 > x0 else 0.0
                lut.append(round(255 * (y0 + t * (y1 - y0))))
                break
    return lut


def make(name, rgb, seed, out_dir):
    rnd = random.Random(seed)
    noise = Image.new("L", (NOISE_W, NOISE_H))
    noise.putdata([rnd.randrange(256) for _ in range(NOISE_W * NOISE_H)])
    img = noise.resize((W, H), Image.BICUBIC).filter(ImageFilter.GaussianBlur(BLUR_RADIUS))
    img = ImageOps.autocontrast(img)      # 归一化到 0..255
    img = img.point(curve_lut())          # 压暗曲线
    channels = [img.point(lambda v, c=c: round(v * c / 255)) for c in rgb]   # 乘染色
    out = out_dir / f"{name}.jpg"
    Image.merge("RGB", channels).save(out, "JPEG", quality=85, optimize=True)
    print(f"wrote {out} ({out.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    out_dir = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/wallpapers")
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, rgb, seed in WALLPAPERS:
        make(name, rgb, seed, out_dir)
```

- [ ] **Step 2: 生成并检查体积**

```bash
cd ~/GitHub/UnitedU-launcher && python3 scripts/gen-wallpapers.py && ls -la app/src/main/assets/wallpapers/ && du -ch app/src/main/assets/wallpapers/*.jpg | tail -1
```
Expected: 6 个 jpg,每张 ≤ 120 KB,合计 ≤ 600 KB。超了就把 `quality` 降到 80 重跑。用 Read 工具看一眼 `00-neutral.jpg`:应是深灰雾状、无竖条纹、无明显块状。

- [ ] **Step 3: 换掉 M1 默认底的引用并删旧文件**

`HomeScreen.kt` 两处 `ctx.assets.open("default-wallpaper.jpg")` 改为 `ctx.assets.open("wallpapers/00-neutral.jpg")`(`Wallpaper` 组件的回落 + `ensureDefaultWallpaper`),然后:

```bash
git rm -q app/src/main/assets/default-wallpaper.jpg && grep -rn "default-wallpaper" app/src docs NOTICE
```
Expected: `app/src` 里 0 处;docs / NOTICE 的提及在下一步与 Task 8 改。

- [ ] **Step 4: 改 NOTICE** — 把
```
- app/src/main/assets/default-wallpaper.jpg — generated procedurally from random noise (see scripts), not derived from any third-party image
```
改成
```
- app/src/main/assets/wallpapers/*.jpg — six built-in wallpapers generated procedurally from seeded random noise by scripts/gen-wallpapers.py; not derived from any third-party image
```

- [ ] **Step 5: 构建 + 模拟器确认默认底为中性暗**

```bash
source scripts/env.sh && gradle --no-daemon assembleRelease 2>&1 | grep -E 'BUILD|error:' | head -3
emulator -avd unitedu-tv -no-snapshot -no-audio -gpu swiftshader_indirect &
adb wait-for-device && adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm clear com.uniteduone.launcher
adb shell cmd package set-home-activity --user 0 com.uniteduone.launcher/.MainActivity
adb shell input keyevent KEYCODE_HOME && sleep 5
adb exec-out screencap -p > $SCRATCH/m3-t3-default.png
```
用 Read 看 `$SCRATCH/m3-t3-default.png`:背景是**灰雾**不是金雾。

- [ ] **Step 6: Commit**

```bash
git add scripts/gen-wallpapers.py app/src/main/assets/wallpapers NOTICE app/src/main/java/com/uniteduone/launcher/HomeScreen.kt
git commit -m "feat(assets): six generated built-in wallpapers; neutral dark default replaces gold

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: `Wallpapers.kt` — 解析 / 迁移 / 铺入 / 选择 + 壁纸层搬家(不含管线)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/Wallpapers.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt`(删 `Wallpaper` 组件约 345–380 行、`ensureDefaultWallpaper` 约 591–610 行)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(`settingsRevision`、spec 组装、`handlePick`、`wallpaperThemeColors`)
- Modify: `app/src/main/java/com/uniteduone/launcher/Theme.kt`(`WallpaperCrossfadeMs`)

**Interfaces:**
- Consumes: Task 1 字段;Task 2 `WallpaperSpec` / `wallpaperSpecOf`;Task 3 资源路径。
- Produces:
  - `object Wallpapers { fun libraryImages(ctx): List<File>; fun resolveSource(ctx, fileName: String): File?; fun prepare(ctx); fun select(ctx, file: File): Boolean; fun builtinDefault(ctx): Bitmap?; fun load(ctx, spec: WallpaperSpec): Bitmap? }`
  - `@Composable fun Wallpaper(ctx: Context, spec: WallpaperSpec)`
  - `MainActivity.settingsRevision: Int`(只重读 settings、不重建首页行的计数器)
  - `const val DEFAULT_WALLPAPER_ASSET = "wallpapers/00-neutral.jpg"`

- [ ] **Step 1: `Theme.kt` 加常量** — `ScreensaverZoom` 之后:
```kotlin
    /** 壁纸换图(选图 / 轮播 / 改参数)的交叉淡入时长。 */
    const val WallpaperCrossfadeMs = 1500
```

- [ ] **Step 2: 新建 `Wallpapers.kt`**

```kotlin
package com.uniteduone.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "UnitedU"
private val WALLPAPER_IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

/** APK 内置 6 张(assets/wallpapers/<name>.jpg);铺进 library 时加前缀。00 中性底是默认。 */
private val BUILTIN_WALLPAPERS = listOf("00-neutral", "01-gold", "02-champagne", "03-blue", "04-purple", "05-green")
private const val BUILTIN_PREFIX = "unitedu-"
const val DEFAULT_WALLPAPER_ASSET = "wallpapers/00-neutral.jpg"
/** 铺过一次就留个标记:M6 上传页删掉内置图后不复活(恢复默认是 M7 的事)。 */
private const val SEED_MARKER = ".seeded"

/**
 * 壁纸的文件侧:当前壁纸不再是复制出来的 wallpaper.jpg,而是 settings.json 里的一个文件名,
 * 指向 library/wallpapers/。选图 / 轮播只改字段,不复制、不 recreate。
 */
object Wallpapers {

    fun libraryImages(ctx: Context): List<File> =
        Paths.wallpaperLibrary(ctx).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in WALLPAPER_IMAGE_EXTS }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 当前壁纸源文件:设置指定的 → library 按名排序第一张 → null(调用方回落 APK 内置)。 */
    fun resolveSource(ctx: Context, fileName: String): File? {
        if (fileName.isNotEmpty()) {
            val f = File(Paths.wallpaperLibrary(ctx), fileName)
            if (f.isFile) return f
        }
        return libraryImages(ctx).firstOrNull()
    }

    /** 一次性准备:迁移旧根目录壁纸 + 铺入内置 6 张。IO 线程;外置没挂整段跳过。 */
    fun prepare(ctx: Context) {
        if (Paths.baseOrNull(ctx) == null) return
        migrateLegacy(ctx)
        seedBuiltins(ctx)
    }

    /**
     * M1/M2 的壁纸是根目录 files/wallpaper.jpg|png(选择器复制过去的)。搬进 library,
     * 若设置里还没指定壁纸就指向它——升级后用户看到的仍是升级前那张。
     * 此后 adb 后门 = push 进 library/wallpapers/ 再在选择器里选(与文档一致)。
     */
    private fun migrateLegacy(ctx: Context) {
        val old = listOf(Paths.wallpaper(ctx), Paths.wallpaperPng(ctx)).firstOrNull { it.exists() } ?: return
        val dest = File(Paths.wallpaperLibrary(ctx), "legacy-wallpaper.${old.extension.lowercase()}")
        if (!old.renameTo(dest)) { Log.w(TAG, "旧壁纸迁移失败: ${old.name}"); return }
        listOf(Paths.wallpaper(ctx), Paths.wallpaperPng(ctx)).forEach { it.delete() }
        val s = SettingsStore.read(ctx)
        if (s.wallpaperFile.isEmpty()) SettingsStore.write(ctx, s.copy(wallpaperFile = dest.name))
    }

    private fun seedBuiltins(ctx: Context) {
        val dir = Paths.wallpaperLibrary(ctx)
        val marker = File(dir, SEED_MARKER)
        if (marker.exists()) return
        for (name in BUILTIN_WALLPAPERS) {
            val dst = File(dir, "$BUILTIN_PREFIX$name.jpg")
            if (dst.exists()) continue
            // tmp → 校验可解码 → rename:复制到一半被杀不能留下半截文件(与 M1 ensureDefaultWallpaper 同理)
            val tmp = File(dir, "$BUILTIN_PREFIX$name.tmp")
            runCatching {
                ctx.assets.open("wallpapers/$name.jpg").use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out); out.flush(); out.fd.sync() }
                }
                check(Apps.isDecodableImage(tmp.absolutePath))
                if (!tmp.renameTo(dst)) { dst.delete(); check(tmp.renameTo(dst)) }
            }.onFailure { Log.w(TAG, "内置壁纸铺入失败 $name: ${it.message}") }
            tmp.delete()
        }
        runCatching { marker.createNewFile() }
        val s = SettingsStore.read(ctx)
        if (s.wallpaperFile.isEmpty()) {
            SettingsStore.write(ctx, s.copy(wallpaperFile = "$BUILTIN_PREFIX${BUILTIN_WALLPAPERS[0]}.jpg"))
        }
    }

    /** 选择器选中:只记文件名 + 重置轮播计时。 */
    fun select(ctx: Context, file: File): Boolean {
        val name = sanitizeWallpaperFileName(file.name)
        if (name.isEmpty() || !Apps.isDecodableImage(file.absolutePath)) return false
        val s = SettingsStore.read(ctx)
        return SettingsStore.write(ctx, s.copy(wallpaperFile = name, wallpaperRotatedAt = System.currentTimeMillis()))
    }

    /** APK 内置默认底:任何路径都失败时的最后一张,保证永远不黑屏。 */
    fun builtinDefault(ctx: Context): Bitmap? = runCatching {
        ctx.assets.open(DEFAULT_WALLPAPER_ASSET).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /** 显示用位图。IO 线程。解不出来回落内置;调用方只在非 null 时换图。 */
    fun load(ctx: Context, spec: WallpaperSpec): Bitmap? {
        prepare(ctx)
        // prepare 可能刚把 wallpaperFile 写进 settings,而 spec 是拿旧 settings 组装的;补读一次。
        val name = spec.file.ifEmpty { SettingsStore.read(ctx).wallpaperFile }
        val src = resolveSource(ctx, name) ?: return builtinDefault(ctx)
        // RGBA_F16 保留 Ultra HDR gain map(与 M1 同);decodeScaled 在 F16 失败时自动回落 8888。
        return runCatching { Apps.decodeScaled(src.absolutePath, 1920, 1080, Bitmap.Config.RGBA_F16) }.getOrNull()
            ?: builtinDefault(ctx)
    }
}

/**
 * 壁纸层。**住在 MainActivity 的 setContent 顶层,不在 HomeScreen 里**:进出编辑页/设置页会把
 * 那一层整棵拆掉重建,壁纸若跟着走就要每次重解一张 1920×1080,期间纯黑——退出时黑闪一下。
 * key 只有 spec:换图 / 改参数 / 轮播都只换位图;新图就绪前旧图原样留着,再交叉淡入过去。
 */
@Composable
fun Wallpaper(ctx: Context, spec: WallpaperSpec) {
    // produceState 的 remember 不带 key:spec 变时只重启生产者,旧值留着 → 不闪黑
    val bmp by produceState<Bitmap?>(initialValue = null, spec) {
        val next = withContext(Dispatchers.IO) { Wallpapers.load(ctx, spec) }
        if (next != null) value = next
    }
    val b = bmp ?: return
    Crossfade(
        targetState = b,
        animationSpec = tween(Theme.WallpaperCrossfadeMs),
        label = "wallpaperCrossfade",
    ) { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
```

- [ ] **Step 3: `HomeScreen.kt` 删旧实现** — 删掉整个 `fun Wallpaper(ctx: Context)`(含其 KDoc)与 `private fun ensureDefaultWallpaper(ctx: Context)`(含 KDoc)。若此后 `android.graphics.BitmapFactory` 在该文件无其他引用,删掉该 import。

- [ ] **Step 4: `MainActivity.kt`**

(a)字段:`revision` 声明之后加
```kotlin
    /**
     * 只重读 settings.json、**不重建首页行**的计数器。壁纸选图 / 轮播 / 滑块实时预览走它:
     * 这些事每 5 分钟就来一次,若走 revision 会连 layout.json 与全部卡片图一起重读一遍。
     */
    private var settingsRevision by mutableStateOf(0)
```

(b)`val homeSettings = remember(revision) { ... }` → `remember(revision, settingsRevision) { ... }`。

(c)`wallpaperColors` 的 `produceState` key 加上壁纸文件,并把文件名传进去:
```kotlin
            val wallpaperColors by produceState<ThemeColors?>(
                null, homeSettings.followWallpaperColor, homeSettings.wallpaperFile, revision,
            ) {
                value = if (!homeSettings.followWallpaperColor) null
                else withContext(Dispatchers.IO) {
                    wallpaperThemeColors(this@MainActivity, homeSettings.wallpaperFile)
                }
            }
```

(d)`val themeColors = ...` 之后加 spec 组装(`toArgb` 需 `import androidx.compose.ui.graphics.toArgb`):
```kotlin
            // 壁纸渲染输入:文件名 + 主题化参数;accent 只在主题化时参与(见 wallpaperSpecOf)。
            val wallpaperSpec = remember(homeSettings, themeColors) {
                wallpaperSpecOf(homeSettings, themeColors.accent.toArgb() and 0xFFFFFF)
            }
```

(e)`Wallpaper(this@MainActivity)` → `Wallpaper(this@MainActivity, wallpaperSpec)`。

(f)`handlePick` 的 `if (target == PICK_WALLPAPER) { ... }` 整块换成:
```kotlin
        if (target == PICK_WALLPAPER) {
            // 只记文件名,不复制、不 recreate(recreate 会把焦点打回第一张卡、屏幕黑一下)
            val ok = Wallpapers.select(this, file)
            toast(getString(if (ok) R.string.toast_wallpaper_changed else R.string.toast_invalid_image))
            if (ok) settingsRevision++
        } else {
```

(g)文件底部 `wallpaperThemeColors` 改签名与取文件方式,KDoc 里「Paths.wallpaper / wallpaperPng」那句改成「Wallpapers.resolveSource(原图,不是处理后的缓存——否则主题化开着时会自己染自己)」:
```kotlin
private fun wallpaperThemeColors(ctx: android.content.Context, wallpaperFile: String): ThemeColors? {
    val f = Wallpapers.resolveSource(ctx, wallpaperFile) ?: return null
    val bmp = runCatching { Apps.decodeScaled(f.absolutePath, 320, 180) }.getOrNull() ?: return null
```
(其余行不变。)

- [ ] **Step 5: 构建 + 单测**

```bash
gradle --no-daemon testReleaseUnitTest assembleRelease 2>&1 | grep -E 'BUILD|error:|FAILED' | head -5
```
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 6: 模拟器验证 — 铺入、迁移、选图不黑闪**

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm clear com.uniteduone.launcher
adb shell input keyevent KEYCODE_HOME && sleep 5
adb shell ls -a /sdcard/Android/data/com.uniteduone.launcher/files/library/wallpapers/
adb shell cat /sdcard/Android/data/com.uniteduone.launcher/files/settings.json | grep wallpaperFile
adb exec-out screencap -p > $SCRATCH/m3-t4-seeded.png
```
Expected:目录里 `.seeded` + `unitedu-00-neutral.jpg … unitedu-05-green.jpg`;`"wallpaperFile": "unitedu-00-neutral.jpg"`;截图灰雾底。

迁移(模拟「M2 升级上来」:settings 里没有 wallpaperFile、根目录有 wallpaper.jpg):
```bash
adb shell pm clear com.uniteduone.launcher   # 先清,pm clear 会连外置 files/ 一起清
adb push app/src/main/assets/wallpapers/01-gold.jpg /sdcard/Android/data/com.uniteduone.launcher/files/wallpaper.jpg
adb shell input keyevent KEYCODE_HOME && sleep 5
adb shell ls /sdcard/Android/data/com.uniteduone.launcher/files/ | grep -c '^wallpaper.jpg$'
adb shell ls /sdcard/Android/data/com.uniteduone.launcher/files/library/wallpapers/ | grep legacy
adb shell cat /sdcard/Android/data/com.uniteduone.launcher/files/settings.json | grep wallpaperFile
adb exec-out screencap -p > $SCRATCH/m3-t4-migrated.png
```
Expected:根目录计数 0;`legacy-wallpaper.jpg` 存在;`"wallpaperFile": "legacy-wallpaper.jpg"`;截图金雾底。

选图:齿轮 → 换壁纸 → 选一张(`adb shell input keyevent KEYCODE_MENU`,方向键 + `KEYCODE_DPAD_CENTER`),观察 logcat 无 `recreate`,截图前后 2 秒各一张 → 新壁纸淡入,卡片焦点仍在。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/Wallpapers.kt app/src/main/java/com/uniteduone/launcher/HomeScreen.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt app/src/main/java/com/uniteduone/launcher/Theme.kt
git commit -m "feat(wallpaper): settings-backed current wallpaper, legacy migration, built-in seeding, no recreate on pick

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: 处理管线 + 缓存

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Wallpapers.kt`(`processed` / `render` 及私有帮助函数;`load` 接管线)
- Modify: `app/src/main/java/com/uniteduone/launcher/Paths.kt`(`wallpaperCacheDir`)

**Interfaces:**
- Consumes: Task 2 `blurTargetWidth` / `wallpaperColorMatrix` / `wallpaperCacheKey` / `WallpaperSpec.isIdentity`。
- Produces: `Wallpapers.processed(ctx, src: File, spec): Bitmap?`;`Paths.wallpaperCacheDir(ctx): File`。

- [ ] **Step 1: `Paths.kt`** — `cardLibrary` 之后:
```kotlin
    /** 处理后的壁纸缓存。外置 cache 优先(adb 能看、卸载即清),没挂用内置 cache。 */
    fun wallpaperCacheDir(ctx: Context) = File(ctx.externalCacheDir ?: ctx.cacheDir, "wallpapers").also { it.mkdirs() }
```

- [ ] **Step 2: `Wallpapers.kt` 加管线** — `object Wallpapers` 内、`load` 之前追加:

```kotlin
    private const val OUT_W = 1920
    private const val OUT_H = 1080
    private const val CACHE_KEEP = 4

    /** 处理后的位图:先查缓存,没有就渲染并写缓存。任何一步失败返回 null,调用方退回原图。IO 线程。 */
    fun processed(ctx: Context, src: File, spec: WallpaperSpec): Bitmap? {
        val key = wallpaperCacheKey(
            src.absolutePath, src.lastModified(), src.length(),
            spec.themed, spec.accentRgb, spec.blur, spec.dim,
        )
        val dir = Paths.wallpaperCacheDir(ctx)
        val cached = File(dir, "$key.jpg")
        if (cached.isFile) {
            runCatching { Apps.decodeScaled(cached.absolutePath, OUT_W, OUT_H) }.getOrNull()?.let { return it }
            cached.delete()   // 缓存文件坏了:删掉重做
        }
        val t0 = System.currentTimeMillis()
        val bmp = runCatching { render(src, spec) }
            .onFailure { Log.w(TAG, "壁纸处理失败 ${src.name}: ${it.message}") }
            .getOrNull() ?: return null
        Log.i(TAG, "壁纸处理 ${src.name} blur=${spec.blur} dim=${spec.dim} themed=${spec.themed} 用时 ${System.currentTimeMillis() - t0}ms")
        writeCache(dir, cached, bmp)
        return bmp
    }

    /**
     * 缩小 → 套 ColorMatrix → 放大。颜色运算与模糊都是线性算子、顺序可交换,
     * 所以矩阵作用在缩小后的小图上,几乎免费;blur=0 时矩阵直接作用于 1920×1080。
     */
    private fun render(src: File, spec: WallpaperSpec): Bitmap? {
        val decoded = Apps.decodeScaled(src.absolutePath, OUT_W, OUT_H) ?: return null
        val full = centerCrop(decoded, OUT_W, OUT_H)
        val targetW = blurTargetWidth(spec.blur, OUT_W)
        val small = if (targetW >= full.width) full else downscale(full, targetW)
        val colored = applyMatrix(small, wallpaperColorMatrix(spec.themed, spec.accentRgb, spec.dim))
        return if (colored.width == OUT_W && colored.height == OUT_H) colored else upscale(colored, OUT_W, OUT_H)
    }

    /** 中心裁剪成 w:h 再缩到恰好 w×h(缓存尺寸固定,后面的缩放链才有确定的起点)。 */
    private fun centerCrop(b: Bitmap, w: Int, h: Int): Bitmap {
        val scale = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
        val sw = (w / scale).toInt().coerceIn(1, b.width)
        val sh = (h / scale).toInt().coerceIn(1, b.height)
        val cropped = Bitmap.createBitmap(b, (b.width - sw) / 2, (b.height - sh) / 2, sw, sh)
        return if (cropped.width == w && cropped.height == h) cropped
        else Bitmap.createScaledBitmap(cropped, w, h, true)
    }

    /** 反复减半到 ≤ 2× 目标,再一步缩到目标宽;每次减半都是一次 2×2 均值,叠起来就是一块便宜的低通滤波。 */
    private fun downscale(b: Bitmap, targetW: Int): Bitmap {
        var cur = b
        while (cur.width / 2 >= targetW * 2) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        val targetH = maxOf(1, Math.round(targetW * OUT_H.toFloat() / OUT_W))
        cur = Bitmap.createScaledBitmap(cur, targetW, targetH, true)
        return boxBlur3(cur)
    }

    /** 3×3 均值:小图上的最后一道低通,消掉减半链留下的锯齿。小图最多 1920 宽,像素循环可接受。 */
    private fun boxBlur3(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        val src = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var bl = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy < 0 || yy >= h || xx < 0 || xx >= w) continue
                val p = src[yy * w + xx]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun applyMatrix(b: Bitmap, m: FloatArray): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(m))
        }
        android.graphics.Canvas(out).drawBitmap(b, 0f, 0f, paint)
        return out
    }

    /** 分级放大,每级 ≤ 4×:一次 16× 的 bilinear 会留下菱形纹。 */
    private fun upscale(b: Bitmap, w: Int, h: Int): Bitmap {
        var cur = b
        while (cur.width * 4 < w) {
            cur = Bitmap.createScaledBitmap(cur, cur.width * 4, cur.height * 4, true)
        }
        return Bitmap.createScaledBitmap(cur, w, h, true)
    }

    /** tmp → rename 写缓存,然后只留最新 [CACHE_KEEP] 个。失败只记日志:这次仍用内存里的位图显示。 */
    private fun writeCache(dir: File, dst: File, bmp: Bitmap) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "${dst.nameWithoutExtension}.tmp")
            tmp.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush(); out.fd.sync()
            }
            if (!tmp.renameTo(dst)) { dst.delete(); check(tmp.renameTo(dst)) }
            dir.listFiles { f -> f.isFile && f.extension == "jpg" }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(CACHE_KEEP)
                ?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "壁纸缓存写入失败: ${it.message}") }
    }
```

- [ ] **Step 3: `load` 接管线** — 把 `val src = resolveSource(ctx, name) ?: return builtinDefault(ctx)` 之后改成:
```kotlin
        val src = resolveSource(ctx, name) ?: return builtinDefault(ctx)
        // 参数全零完全绕开管线:不解码两次、不写缓存、保留 F16(零回归路径)。
        // 处理失败退回原图而不是黑屏。
        if (!spec.isIdentity) processed(ctx, src, spec)?.let { return it }
        return runCatching { Apps.decodeScaled(src.absolutePath, OUT_W, OUT_H, Bitmap.Config.RGBA_F16) }.getOrNull()
            ?: builtinDefault(ctx)
```

- [ ] **Step 4: 构建 + 单测**

```bash
gradle --no-daemon testReleaseUnitTest assembleRelease 2>&1 | grep -E 'BUILD|error:|FAILED' | head -5
```
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 模拟器验证 — 主题化 / 模糊 / 压暗 / 缓存**

先写一个改 settings.json 的小工具(放 `$SCRATCH/setjson.sh`,后续任务复用):
```bash
cat > $SCRATCH/setjson.sh <<'EOF'
#!/bin/zsh
# 用法: setjson.sh key=value [key=value ...]   (字符串值自己带引号,如 wallpaperFile='"x.jpg"')
set -e
REMOTE=/sdcard/Android/data/com.uniteduone.launcher/files/settings.json
LOCAL=$(dirname "$0")/settings.json
adb pull -q "$REMOTE" "$LOCAL"
for kv in "$@"; do
  k="${kv%%=*}"; v="${kv#*=}"
  python3 - "$LOCAL" "$k" "$v" <<'PY'
import re, sys, pathlib
p, k, v = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
t = p.read_text()
t2 = re.sub(r'"%s":\s*[^,\n}]+' % re.escape(k), '"%s": %s' % (k, v), t)
if t2 == t: t2 = t.rstrip().rstrip('}').rstrip().rstrip(',') + ',\n  "%s": %s\n}\n' % (k, v)
p.write_text(t2)
PY
done
adb push -q "$LOCAL" "$REMOTE"
adb shell am force-stop com.uniteduone.launcher
adb shell input keyevent KEYCODE_HOME
EOF
chmod +x $SCRATCH/setjson.sh
```

然后:
```bash
$SCRATCH/setjson.sh wallpaperFile='"legacy-wallpaper.jpg"' wallpaperThemed=false wallpaperBlur=0 wallpaperDim=50 && sleep 6
adb exec-out screencap -p > $SCRATCH/m3-t5-dim50.png
$SCRATCH/setjson.sh wallpaperBlur=50 && sleep 6
adb exec-out screencap -p > $SCRATCH/m3-t5-blur50.png
$SCRATCH/setjson.sh wallpaperThemed=true && sleep 6
adb exec-out screencap -p > $SCRATCH/m3-t5-themed.png
adb shell ls -la /sdcard/Android/data/com.uniteduone.launcher/cache/wallpapers/
adb logcat -d | grep -E '壁纸处理|壁纸缓存'
```
Expected:三张截图依次「变暗」「变糊」「整体金色调(gold 预设)」;缓存目录 3 个 `<40hex>.jpg`;logcat 三行「壁纸处理 … 用时 N ms」且 N < 1500(swiftshader 模拟器慢,真机更快)。再跑一次最后一条 setjson(参数不变)→ logcat 不再出现新的「壁纸处理」行(命中缓存)。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/Wallpapers.kt app/src/main/java/com/uniteduone/launcher/Paths.kt
git commit -m "feat(wallpaper): CPU pipeline (desaturate/tint/dim via one ColorMatrix, downscale blur) with file cache

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: 轮播

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Wallpapers.kt`(`rotate`)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(轮播 effect)

**Interfaces:**
- Consumes: Task 2 `nextWallpaper` / `rotationDelayMs`;Task 4 `settingsRevision` / `libraryImages`。
- Produces: `Wallpapers.rotate(ctx): Boolean`(返回**是否写盘成功**)。

- [ ] **Step 1: `Wallpapers.rotate`** — `select` 之后加:
```kotlin
    /**
     * 轮播一步:重扫目录(推完/删完图下次轮换即生效,与屏保同理)→ 当前的下一张 → 写盘。
     * **返回「是否写盘成功」,不是「是否换了图」**:单张图库也要刷新 rotatedAt,MainActivity 据此
     * settingsRevision++ → effect 以新 key 重启。若按「换了图才通知」写,单张时 key 不变、
     * effect 结束,轮播从此停转(铁律 6 的变体:effect 的续命信号必须由它自己的 key 承载)。
     */
    fun rotate(ctx: Context): Boolean {
        val s = SettingsStore.read(ctx)
        val names = libraryImages(ctx).map { it.name }
        val next = nextWallpaper(names, s.wallpaperFile) ?: s.wallpaperFile
        if (next != s.wallpaperFile) Log.i(TAG, "壁纸轮播 → $next")
        return SettingsStore.write(ctx, s.copy(wallpaperFile = next, wallpaperRotatedAt = System.currentTimeMillis()))
    }
```

- [ ] **Step 2: `MainActivity.kt` 轮播 effect** — 待机那个 `LaunchedEffect(touched, editing, menuOpen, settings)` 之后加:
```kotlin
            // 壁纸轮播。守卫读的两个量就是 key(铁律 6):rotate() 写盘后 settingsRevision++ 重读 settings,
            // rotatedAt 变 → 本 effect 以新 key 重启、再等一个间隔;重启 app 后按剩余时间续等。
            val rotateMs = homeSettings.wallpaperRotateMs
            val rotatedAt = homeSettings.wallpaperRotatedAt
            LaunchedEffect(rotateMs, rotatedAt) {
                if (rotateMs == 0L) return@LaunchedEffect
                delay(rotationDelayMs(rotatedAt, rotateMs, System.currentTimeMillis()))
                val wrote = withContext(Dispatchers.IO) { Wallpapers.rotate(this@MainActivity) }
                if (wrote) settingsRevision++
            }
```

- [ ] **Step 3: 构建**

```bash
gradle --no-daemon testReleaseUnitTest assembleRelease 2>&1 | grep -E 'BUILD|error:|FAILED' | head -5
```

- [ ] **Step 4: 模拟器验证**

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
$SCRATCH/setjson.sh wallpaperThemed=false wallpaperBlur=0 wallpaperDim=0 wallpaperFile='"unitedu-00-neutral.jpg"' wallpaperRotateMs=300000 wallpaperRotatedAt=0 && sleep 8
adb shell cat /sdcard/Android/data/com.uniteduone.launcher/files/settings.json | grep -E 'wallpaperFile|wallpaperRotatedAt'
adb logcat -d | grep '壁纸轮播'
adb exec-out screencap -p > $SCRATCH/m3-t6-rotated.png
```
Expected:`wallpaperFile` 变成 `unitedu-01-gold.jpg`(neutral 的下一张),`wallpaperRotatedAt` 是当前 epoch(非 0);logcat 一行「壁纸轮播 → unitedu-01-gold.jpg」;截图金雾。

单张图库不停转:
```bash
adb shell 'cd /sdcard/Android/data/com.uniteduone.launcher/files/library/wallpapers && mkdir -p ../wp-bak && mv unitedu-0[1-5]*.jpg legacy-wallpaper.jpg ../wp-bak/ 2>/dev/null; ls'
$SCRATCH/setjson.sh wallpaperFile='"unitedu-00-neutral.jpg"' wallpaperRotatedAt=0 && sleep 8
adb shell cat /sdcard/Android/data/com.uniteduone.launcher/files/settings.json | grep -E 'wallpaperFile|wallpaperRotatedAt'
adb shell 'cd /sdcard/Android/data/com.uniteduone.launcher/files/library && mv wp-bak/* wallpapers/ && rmdir wp-bak'
```
Expected:`wallpaperFile` 仍是 neutral,但 `wallpaperRotatedAt` 已刷新为当前 epoch(effect 续命)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/Wallpapers.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt
git commit -m "feat(wallpaper): timed rotation through library (off/5m/30m/daily), survives restart

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: 设置页「壁纸」分组 + SLIDER 控件 + 实时预览 + 三语文案

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/SettingsScreen.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/Theme.kt`(`SettingsPreviewScrim`)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(接 `onWallpaperParamsChanged`)
- Modify: `app/src/main/res/values/strings.xml`、`values-en/strings.xml`、`values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: Task 1 字段与 `VALID_WALLPAPER_ROTATE_MS`;Task 4 `MainActivity.settingsRevision`(若你的分支里还没有 Task 4,按下面 Step 5 的说明自己补同名字段)。
- Produces: `SettingsScreen(onExit, focusNonce, onWallpaperParamsChanged: () -> Unit = {})`。

- [ ] **Step 1: 文案** — 三份 `strings.xml` 的 `settings_group_standby` 那行之后各加 6 行:

`values/strings.xml`:
```xml
    <string name="settings_group_wallpaper">壁纸</string>
    <string name="settings_wallpaper_rotate">轮播间隔</string>
    <string name="settings_rotate_daily">每天</string>
    <string name="settings_wallpaper_themed">主题化壁纸</string>
    <string name="settings_wallpaper_blur">模糊</string>
    <string name="settings_wallpaper_dim">压暗</string>
```
`values-en/strings.xml`:
```xml
    <string name="settings_group_wallpaper">Wallpaper</string>
    <string name="settings_wallpaper_rotate">Rotate Every</string>
    <string name="settings_rotate_daily">Daily</string>
    <string name="settings_wallpaper_themed">Themed Wallpaper</string>
    <string name="settings_wallpaper_blur">Blur</string>
    <string name="settings_wallpaper_dim">Dim</string>
```
`values-zh-rTW/strings.xml`:
```xml
    <string name="settings_group_wallpaper">桌布</string>
    <string name="settings_wallpaper_rotate">輪播間隔</string>
    <string name="settings_rotate_daily">每天</string>
    <string name="settings_wallpaper_themed">主題化桌布</string>
    <string name="settings_wallpaper_blur">模糊</string>
    <string name="settings_wallpaper_dim">壓暗</string>
```

- [ ] **Step 2: `Theme.kt`** — `EditScreenBackground` 那行之后:
```kotlin
    /** 设置页聚焦在壁纸分组时的浮层底色:黑 35%,让壁纸透出来做实时预览。 */
    val SettingsPreviewScrim = Color(0x59000000)
```

- [ ] **Step 3: `SettingsScreen.kt`**

(a)`CtrlKind` 加 `SLIDER`:`private enum class CtrlKind { SEGMENTED, TOGGLE, SWATCH, SLIDER }`。

(b)签名:`fun SettingsScreen(onExit: () -> Unit, focusNonce: Int = 0, onWallpaperParamsChanged: () -> Unit = {})`。

(c)`idleContentLabels` 之后加:
```kotlin
    val rotateLabels = listOf(
        stringResource(R.string.settings_off),
        stringResource(R.string.settings_idle_minutes, 5),
        stringResource(R.string.settings_idle_minutes, 30),
        stringResource(R.string.settings_rotate_daily),
    )
```

(d)`controls` 列表:在「1 输入源行」之后、「主题色 swatch」之前插入 4 个,并把后面控件的编号注释改为 6–10:
```kotlin
        // 2 轮播间隔 关/5 分/30 分/每天(VALID_WALLPAPER_ROTATE_MS 顺序)
        Ctrl(R.string.settings_wallpaper_rotate, CtrlKind.SEGMENTED,
            options = rotateLabels, count = 4,
            selected = VALID_WALLPAPER_ROTATE_MS.indexOf(s.wallpaperRotateMs).let { if (it < 0) 0 else it },
            onSelect = { i -> update(s.copy(wallpaperRotateMs = VALID_WALLPAPER_ROTATE_MS[i])) }),
        // 3 主题化壁纸
        Ctrl(R.string.settings_wallpaper_themed, CtrlKind.TOGGLE,
            options = onLabels, count = 2,
            selected = if (s.wallpaperThemed) 1 else 0,
            onSelect = { i -> update(s.copy(wallpaperThemed = i == 1)) }),
        // 4 模糊 0–100 步 10(11 档滑块;selected = 档位下标)
        Ctrl(R.string.settings_wallpaper_blur, CtrlKind.SLIDER,
            options = emptyList(), count = 11,
            selected = s.wallpaperBlur / 10,
            onSelect = { i -> update(s.copy(wallpaperBlur = i * 10)) }),
        // 5 压暗 0–100 步 10
        Ctrl(R.string.settings_wallpaper_dim, CtrlKind.SLIDER,
            options = emptyList(), count = 11,
            selected = s.wallpaperDim / 10,
            onSelect = { i -> update(s.copy(wallpaperDim = i * 10)) }),
```
顶部那句「7 个可聚焦控件的描述,顺序即 ctrlIndex(0..6)」改成「11 个 … (0..10)」。

(e)`order`:
```kotlin
    val order: List<Elem> = remember {
        listOf(
            TitleElem,
            HeaderElem(R.string.settings_group_layout),
            ControlElem(0), ControlElem(1),
            HeaderElem(R.string.settings_group_wallpaper),
            ControlElem(2), ControlElem(3), ControlElem(4), ControlElem(5),
            HeaderElem(R.string.settings_group_theme),
            ControlElem(6), ControlElem(7),
            HeaderElem(R.string.settings_group_clock),
            ControlElem(8),
            HeaderElem(R.string.settings_group_standby),
            ControlElem(9), ControlElem(10),
        )
    }
```
文件顶部常量区加(与 `order` 里壁纸分组的下标一致,改一处必须改另一处):
```kotlin
// 壁纸分组的控件下标区间(与 order 里的 ControlElem 编号一致):聚焦在这几行时浮层变半透明做实时预览。
private val WALLPAPER_CTRLS = 2..5
```

(f)实时预览的防抖通知 — `fun update(...)` 定义之后加:
```kotlin
    // 主题化/模糊/压暗改动后 300ms 防抖通知首页重读(实时预览)。轮播间隔无可视效果,不通知。
    // 用「上次通知过的值」比对,不用一次性布尔闩(铁律 7):首次组合两者相等不发;
    // 改回原值也会再发一次(与上次通知值不同),预览不会卡在旧参数上。
    val previewKey = Triple(s.wallpaperThemed, s.wallpaperBlur, s.wallpaperDim)
    var lastNotified by remember { mutableStateOf(previewKey) }
    LaunchedEffect(previewKey) {
        if (previewKey == lastNotified) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        lastNotified = previewKey
        onWallpaperParamsChanged()
    }
```

(g)浮层底色跟焦点走 — 把最外层 `Box(... .background(Theme.EditScreenBackground))` 改成:
```kotlin
    // 聚焦在壁纸分组时浮层降到 35% 黑,壁纸从内容列两侧与底下透出——否则滑块是盲调。
    val previewing = focusedRow in WALLPAPER_CTRLS
    val overlayColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (previewing) Theme.SettingsPreviewScrim else Theme.EditScreenBackground,
        animationSpec = androidx.compose.animation.core.tween(250),
        label = "settingsOverlay",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(overlayColor),
    ) {
```

(h)`SettingRow` 的 `when (ctrl.kind)` 加分支:
```kotlin
                    CtrlKind.SLIDER -> SliderControl(selected = ctrl.selected, count = ctrl.count, rowFocused = focused)
```

(i)`SwatchControl` 之后加滑块渲染(需 `import androidx.compose.foundation.layout.fillMaxHeight`):
```kotlin
/** 滑块:11 档(0–100 步 10)。轨道 + 已填充段 + 百分比;行聚焦时填充段亮香槟,与分段控件选中态同色。 */
@Composable
private fun SliderControl(selected: Int, count: Int, rowFocused: Boolean) {
    val fraction = if (count <= 1) 0f else selected.toFloat() / (count - 1)
    val percent = Math.round(fraction * 100)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .width(220.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Theme.UnfocusedSurface),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(if (rowFocused) Theme.Champagne else Theme.Champagne.copy(alpha = 0.22f)),
            )
        }
        BasicText(
            text = "$percent%",
            style = TextStyle(
                fontFamily = Theme.Sans,
                color = if (rowFocused) Theme.EmphasisText else Theme.SecondaryText,
                fontSize = 13.sp,
            ),
            modifier = Modifier.width(44.dp),
        )
    }
}
```

(j)KDoc「分组带标题(布局 / 主题 / 时钟 / 待机)」改为「(布局 / 壁纸 / 主题 / 时钟 / 待机)」;「内容可能高于屏幕(7 控件 + 4 标题 + 标题栏 ≈ 498dp)」改为「(11 控件 + 5 标题 + 标题栏 ≈ 712dp,超过 540dp 的屏)」。

- [ ] **Step 4: `MainActivity.kt` 接线** — `SettingsScreen(onExit = ::leaveSettings, focusNonce = focusNonce)` 改为:
```kotlin
                SettingsScreen(
                    onExit = ::leaveSettings,
                    focusNonce = focusNonce,
                    onWallpaperParamsChanged = { settingsRevision++ },
                )
```

- [ ] **Step 5: 若你的分支里 `settingsRevision` 还不存在**(Task 4 未汇合):在 `revision` 声明之后加下面这段,并把 `val homeSettings = remember(revision) { ... }` 改成 `remember(revision, settingsRevision) { ... }`——与 Task 4 Step 4(a)(b)逐字相同,汇合时取一份即可:
```kotlin
    /**
     * 只重读 settings.json、**不重建首页行**的计数器。壁纸选图 / 轮播 / 滑块实时预览走它:
     * 这些事每 5 分钟就来一次,若走 revision 会连 layout.json 与全部卡片图一起重读一遍。
     */
    private var settingsRevision by mutableStateOf(0)
```

- [ ] **Step 6: 构建 + 单测**

```bash
gradle --no-daemon testReleaseUnitTest assembleRelease 2>&1 | grep -E 'BUILD|error:|FAILED' | head -5
```

- [ ] **Step 7: 模拟器验证 — 焦点账本 + 滑块 + 透出 + 落盘**

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell input keyevent KEYCODE_HOME && sleep 4
adb shell input keyevent KEYCODE_MENU && sleep 1          # 齿轮菜单(menuItems 顺序:编辑分栏 / UnitedU 设置 / 换壁纸 / …)
adb shell input keyevent KEYCODE_DPAD_DOWN && sleep 0.4   # 第二项「UnitedU 设置」
adb shell input keyevent KEYCODE_DPAD_CENTER && sleep 2
adb exec-out screencap -p > $SCRATCH/m3-t7-settings.png
for i in 1 2 3 4; do adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 0.4; done   # 落到「模糊」
adb shell input keyevent KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT && sleep 1
adb exec-out screencap -p > $SCRATCH/m3-t7-blur30.png
adb shell input keyevent KEYCODE_DPAD_DOWN && sleep 0.4
adb shell input keyevent KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT && sleep 1
adb exec-out screencap -p > $SCRATCH/m3-t7-dim20.png
adb shell input keyevent KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT && sleep 1   # 边界处多按一下也不能丢焦
for i in 1 2 3 4 5 6 7; do adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 0.4; done       # 一路到底
adb exec-out screencap -p > $SCRATCH/m3-t7-bottom.png
adb shell cat /sdcard/Android/data/com.uniteduone.launcher/files/settings.json | grep -E 'wallpaperBlur|wallpaperDim'
adb logcat -d | grep -c '壁纸处理'
```
Expected:`m3-t7-settings.png` 有「壁纸」分组四行;`blur30` 里模糊滑块 30% 且**浮层半透明、壁纸可见**;`dim20` 压暗 20%;`bottom` 焦点在「待机显示」行、浮层恢复不透明、内容整体上移;`settings.json` `wallpaperBlur: 30, wallpaperDim: 0`(压暗按左键归零后);logcat 「壁纸处理」计数 ≥ 2(实时预览触发过;若 Task 5 未汇合则此项跳过)。全程没有任何一步「按了没反应」。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/SettingsScreen.kt app/src/main/java/com/uniteduone/launcher/Theme.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(settings): wallpaper group (rotate/themed/blur/dim), slider control, live preview scrim

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: 模拟器全验收 + 零回归像素对比 + 文档

**Files:**
- Modify: `docs/WORKLOG.md`(M3 收官条目)
- Modify: `docs/TVHOME-README-focus-rules.md`(文件树 + 壁纸段落)
- Modify: `docs/superpowers/specs/2026-09-15-m3-wallpaper-design.md`(状态行改「已实施」)
- Modify: `CLAUDE.md`(模拟器段落末尾加 settings.json 改值工具一句)

- [ ] **Step 0: 设置页写前重读(T6 评审的根治;在合并了引擎链与 T7 的分支上做)** — `SettingsScreen.kt` 的 `update` 改成接一个变换函数,写盘前重读磁盘,只改自己那个字段;12 个 `onSelect` 调用点从 `update(s.copy(x = …))` 改成 `update { it.copy(x = …) }`:
```kotlin
    // 写前重读、只改自己那个字段:后台轮播(Task 6)也会写 settings.json,整对象回写会把它的
    // wallpaperFile/rotatedAt 覆盖(壁纸来回翻)。s 仍是界面显示用的最新值。
    fun update(transform: (Settings) -> Settings) {
        val newS = transform(SettingsStore.read(ctx))
        s = newS
        val ok = SettingsStore.write(ctx, newS)
        if (!ok) Log.w(LOG_TAG, "settings.json 写入失败,改动只留在内存里")
    }
```
`SettingsStore.read` 在文件不存在/坏时会写默认值再返回——与今日 `update` 语义一致。改完 `gradle --no-daemon testReleaseUnitTest assembleRelease` 绿,模拟器进设置页改两项、退出后 `settings.json` 两项都在,提交 `fix(settings): re-read before write so background rotation writes are never clobbered`。

- [ ] **Step 1: 零回归像素对比(spec §6.3 第 1 项)**

原则:M2 收官 commit(`f6c5184`)与 M3 各装一次,**同一张用户壁纸、同一套 layout**,只允许时钟区域不同。

```bash
# 1) 建 M2 基线 APK
git worktree add $SCRATCH/m2 f6c5184 && (cd $SCRATCH/m2 && source scripts/env.sh && gradle --no-daemon assembleRelease 2>&1 | grep BUILD)
# 2) 装 M2,清数据,首页截两张(隔 61s,差异 = 时钟区)
adb install -r $SCRATCH/m2/app/build/outputs/apk/release/app-release.apk
adb shell pm clear com.uniteduone.launcher && adb shell input keyevent KEYCODE_HOME && sleep 6
adb exec-out screencap -p > $SCRATCH/base-a.png && sleep 61 && adb exec-out screencap -p > $SCRATCH/base-b.png
# 3) 升级到 M3(不清数据:走迁移路径),首页截图
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop com.uniteduone.launcher && adb shell input keyevent KEYCODE_HOME && sleep 6
adb exec-out screencap -p > $SCRATCH/m3-home.png
# 4) 比对:m3 与 base-a 的差异 bbox 必须落在 base-a/base-b 的差异 bbox(时钟)之内
python3 - $SCRATCH <<'PY'
import sys, pathlib
from PIL import Image, ImageChops
d = pathlib.Path(sys.argv[1])
a, b, m = (Image.open(d / n).convert("RGB") for n in ("base-a.png", "base-b.png", "m3-home.png"))
clock = ImageChops.difference(a, b).getbbox()
diff = ImageChops.difference(a, m).getbbox()
print("clock bbox:", clock); print("m3 diff bbox:", diff)
inside = diff is None or (clock and diff[0] >= clock[0] and diff[1] >= clock[1] and diff[2] <= clock[2] and diff[3] <= clock[3])
print("ZERO-REGRESSION:", "PASS" if inside else "FAIL")
PY
git worktree remove --force $SCRATCH/m2
```
Expected:`ZERO-REGRESSION: PASS`。FAIL 就把 `m3 diff bbox` 区域 `zoom` 出来看是什么(常见:Crossfade 首帧未完成 → 多等 2 秒再截)。

- [ ] **Step 2: 跑完 spec §6.3 第 2–7 项**(第 2、3 项 Task 4 已验,第 4 项 Task 5、第 5 项 Task 6、第 6 项 Task 7 已验;这里只补第 7 项并把全部截图归档):

```bash
$SCRATCH/setjson.sh followWallpaperColor=true wallpaperThemed=true wallpaperFile='"unitedu-03-blue.jpg"' && sleep 6
adb exec-out screencap -p > $SCRATCH/m3-t8-follow-themed.png
$SCRATCH/setjson.sh themePresetId='"green"' && sleep 6
adb exec-out screencap -p > $SCRATCH/m3-t8-follow-themed-green.png
```
Expected:两张截图壁纸都是**蓝**调(跟随壁纸主色取自原图,换预设不影响),齿轮/时钟色也是蓝系。

把本轮所有 `m3-*.png` 里最有代表性的 3 张(默认中性底 / 主题化 / 设置页壁纸分组)拷到 `docs/screenshots/m3-{default,themed,settings}.png`。

- [ ] **Step 3: 文档**

`docs/TVHOME-README-focus-rules.md`:
- 文件树的 `wallpapers/` 那行改成 `├── wallpapers/          # 壁纸池:内置 6 张首次启动铺入;当前壁纸与轮播设置在 settings.json`;
- 「`assets/wallpaper-gold-fog.jpg` 是当前那张…」一整段改为:`内置壁纸由 scripts/gen-wallpapers.py 生成(噪声 → 放大 → 高斯模糊 → 归一化 → 压暗曲线 → 染色,参数与种子在脚本顶部),00-neutral 为默认底。`;
- 「同一张图也打包在 `app/src/main/assets/default-wallpaper.jpg`…」段改为:`APK 内置 assets/wallpapers/*.jpg;首次启动铺进 library/wallpapers/(标记 .seeded,只铺一次),settings.json 的 wallpaperFile 指向当前壁纸;M1/M2 的根目录 wallpaper.jpg 首次启动自动迁入 library 为 legacy-wallpaper.jpg。`;
- 「换图方式」段末尾加:`轮播、主题化、模糊、压暗在「UnitedU 设置 → 壁纸」调;处理结果缓存在 externalCacheDir/wallpapers/(最多 4 张)。`

`CLAUDE.md` 模拟器段代码块之后加一行:`改 settings.json 单个字段验证用:pull → 正则替换 → push → force-stop → HOME(见 M3 计划 Task 5 的 setjson.sh)。`

`docs/WORKLOG.md` 末尾追加「## 2026-09-XX · M3 收官」:交付清单(按 spec §0 五项)、验收结果(§6.3 七项各一行 + 零回归 PASS 的 bbox 数字)、决策(Gordon 三项)、延后项(真机调参回填 Task 9;齿轮菜单归并 M7;上传页删图 M6;恢复默认 M7)、注记(处理耗时数字、缓存目录位置)。

spec 顶部状态行改为「**状态:已实施(commit …),待 Task 9 真机调参回填。**」。

- [ ] **Step 4: Commit + 推送前确认**

```bash
git add docs CLAUDE.md && git commit -m "docs: M3 closeout — wallpaper rotation/themed pipeline verified on emulator

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
git log --oneline f6c5184..HEAD
```
推送到 `origin main` 前先问 Gordon(对外生效动作)。

---

### Task 9: 真机调参回填(等 Gordon)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Settings.kt`(`wallpaperBlur` / `wallpaperDim` 默认值,视 Gordon 结论)
- Modify: `app/src/test/java/com/uniteduone/launcher/SettingsTest.kt`(`wallpaperFieldsDefaultWhenAbsent` 期望值同步)
- Modify: `docs/superpowers/specs/2026-09-15-m3-wallpaper-design.md` §1 默认值

前置:Gordon 在 A95L 上(无线调试配对见记忆 `tv-adb-wireless-debugging`)打开「主题化壁纸」,用滑块调到满意,回报两个百分比 B、D。

- [ ] **Step 1:** 若 Gordon 定的默认仍是 0/0(主题化关时不改观感),本任务只在 WORKLOG 记一行「真机确认默认 0/0」,结束。
- [ ] **Step 2:** 否则只改两处:`Settings` 数据类默认 `wallpaperBlur = B`、`wallpaperDim = D`(`clampPercentStep10` 已按 `d.wallpaperBlur` / `d.wallpaperDim` 回落,不用动);测试 `wallpaperFieldsDefaultWhenAbsent` 的 `assertEquals(0, s.wallpaperBlur)` / `assertEquals(0, s.wallpaperDim)` 改成 B、D;spec §1 表格同步。
- [ ] **Step 3:** `gradle --no-daemon testReleaseUnitTest assembleRelease` 绿 → 提交 `feat(settings): themed wallpaper defaults from real-device tuning (blur B, dim D)`。

**注意**:默认非零会打破「默认全零 ⇒ 零回归」不变量——只有 Gordon 明确要改默认才做,并在 WORKLOG 写明这是有意为之。
