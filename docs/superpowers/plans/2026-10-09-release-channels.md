# 稳定版 / Beta 双通道 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户在关于页选「稳定版 / Beta」;Beta 通道收到 Beta 与稳定版中较新的那个,从 Beta 切回稳定当场装回退包、不丢数据;`release.sh` 能发 Beta(连带回退包)。

**Architecture:** 三份清单 `latest.json` / `beta.json` / `rollback.json`,格式都是现有 `latest.json` 的格式(`parseLatest` 原样复用)。「该读哪几份、选哪一个」是一个纯函数 `resolveChannel`(JVM 单测钉死),`Update.kt` 只负责按它的要求取字节。界面只加一颗关于页胶囊 + 外壳栈上的一层二选一页(照「恢复默认」确认层的写法)。

**Tech Stack:** Kotlin / Compose for TV、JUnit4(JVM 单测)、bash + python3(`release.sh`)、wrangler(R2)、gh。

**Spec:** `docs/superpowers/specs/2026-10-09-release-channels-design.md`

## Global Constraints

- 包名 `com.uniteduone.launcher`、签名证书(`RELEASE_CERT_SHA256`)不变;安装链路(`Update.verify` / `checkUpdateApk` / `SelfUpdate`)不改。
- `latest.json` 的地址与格式**不变**(已发出去的 1.0.x 只认它)。
- versionCode 全局单调:稳定、Beta、回退包共用一个计数;回退包 = 对应 Beta + 1。
- Beta versionName 形如 `1.1.0-beta.1`;GitHub 上 Beta 是 prerelease,回退包 / `beta.json` / `rollback.json` 挂固定 tag `channel-beta` 的 prerelease(`--clobber`)。
- R2 地址:`https://dl.uniteduone.com/unitedu/{latest,beta,rollback}.json`;R2 的 json 一律 `--cache-control "no-cache"`。
- 设置字段 `updateChannel`:`"stable"`(缺省)/ `"beta"`;「恢复默认」**不动**它(与 `onboardingDone` 同类)。
- 焦点七条铁律照旧:新页是外壳栈上普通的一层,复用 `CapsuleColumn`,不建新账本;每页胶囊 ≤ 6(`SettingsPageLimitTest`)。
- 用户可见文案三份(`values` / `values-en` / `values-zh-rTW`);README 中英两份同步;`docs/` 只写中文。
- 本计划**不发版**:不跑非 dry-run 的 `release.sh`、不 push。

## Review Focus

1. **Beta 清单取不到、稳定清单取得到**(R2 上还没发过任何 Beta / 网络半通):Beta 通道应照常按稳定版判断,不能报「检查失败」。→ Task 1 测试 `betaMissingFallsBackToStable`。
2. **从 Beta 切回稳定,但 `rollback.json` 不存在或版本号不比已装的大**(回退包漏发):应显示「已是最新」而不是去下一个装不上的包;日志记一行。→ Task 1 测试 `rollbackNotNewerIsUpToDate` / `rollbackMissingIsUpToDate`。
3. **稳定版已经追上并超过 Beta**:Beta 通道的用户应拿到稳定版(而非停在旧 Beta)。→ Task 1 测试 `betaChannelTakesNewerStable`。
4. **设置里切通道时关于页正在下载**:切通道必须先作废进行中的下载(`session++`),再按新通道检查,不能把旧通道下了一半的包装上。→ Task 4 Step 里 `switchChannel` 调 `begin(...)`,模拟器验收第 4 条。
5. **发版版本号撞车**:新包 versionCode ≤ 已发布三份清单里的最大值时 `release.sh` 必须中止(否则用户永远收不到它)。→ Task 6 的 `check_code_monotonic` 与其 dry-run 测试。

---

### Task 1: 通道判定纯逻辑 `resolveChannel`

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/UpdateChannels.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/UpdateChannelsTest.kt`

**Interfaces:**
- Consumes: `LatestInfo`、`isNewer(info, currentCode, sdk)`、`CheckFailure`、`UpdateCheckException`(`UpdateChecker.kt`,不改)。
- Produces:
  ```kotlin
  enum class UpdateChannel(val id: String) { STABLE("stable"), BETA("beta");
      companion object { fun fromId(id: String?): UpdateChannel } }   // 认不出 → STABLE
  enum class UpdateKind { STABLE, BETA, ROLLBACK }
  data class ChannelUpdate(val info: LatestInfo, val kind: UpdateKind)
  fun resolveChannel(
      channel: UpdateChannel, installedCode: Int, sdk: Int,
      log: (String) -> Unit = {},
      fetchStable: () -> Result<LatestInfo>,
      fetchBeta: () -> Result<LatestInfo>,
      fetchRollback: () -> Result<LatestInfo>,
  ): Result<ChannelUpdate?>   // success(null) = 已是最新;failure = 检查失败(只在稳定清单本身失败时)
  ```

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelsTest {
    private val hex = "0123456789abcdef".repeat(4)
    private fun info(code: Int, name: String = "v$code") = LatestInfo(code, name, "", "https://e.com/$code.apk", hex, 1)
    private fun ok(code: Int) = { Result.success(info(code)) }
    private val net = { Result.failure<LatestInfo>(UpdateCheckException(CheckFailure.NETWORK)) }
    private val never: () -> Result<LatestInfo> = { error("不该被读") }

    private fun run(ch: UpdateChannel, installed: Int, stable: () -> Result<LatestInfo>, beta: () -> Result<LatestInfo> = never, rollback: () -> Result<LatestInfo> = never) =
        resolveChannel(ch, installed, 34, fetchStable = stable, fetchBeta = beta, fetchRollback = rollback)

    @Test fun stableNewer() = assertEquals(ChannelUpdate(info(7), UpdateKind.STABLE), run(UpdateChannel.STABLE, 6, ok(7)).getOrThrow())
    @Test fun stableSameIsUpToDate() = assertNull(run(UpdateChannel.STABLE, 6, ok(6)).getOrThrow())
    @Test fun stableFailureIsFailure() = assertTrue(run(UpdateChannel.STABLE, 6, net).isFailure)

    @Test fun stableChannelAboveStableReadsRollback() =
        assertEquals(ChannelUpdate(info(8), UpdateKind.ROLLBACK), run(UpdateChannel.STABLE, 7, ok(6), rollback = ok(8)).getOrThrow())
    @Test fun rollbackNotNewerIsUpToDate() = assertNull(run(UpdateChannel.STABLE, 9, ok(6), rollback = ok(8)).getOrThrow())
    @Test fun rollbackMissingIsUpToDate() = assertNull(run(UpdateChannel.STABLE, 7, ok(6), rollback = net).getOrThrow())

    @Test fun betaChannelTakesBeta() =
        assertEquals(ChannelUpdate(info(9), UpdateKind.BETA), run(UpdateChannel.BETA, 6, ok(6), beta = ok(9)).getOrThrow())
    @Test fun betaChannelTakesNewerStable() =
        assertEquals(ChannelUpdate(info(11), UpdateKind.STABLE), run(UpdateChannel.BETA, 9, ok(11), beta = ok(9)).getOrThrow())
    @Test fun betaMissingFallsBackToStable() =
        assertEquals(ChannelUpdate(info(7), UpdateKind.STABLE), run(UpdateChannel.BETA, 6, ok(7), beta = net).getOrThrow())
    @Test fun betaOnlyStableFailedStillWorks() =
        assertEquals(ChannelUpdate(info(9), UpdateKind.BETA), run(UpdateChannel.BETA, 6, net, beta = ok(9)).getOrThrow())
    @Test fun betaBothFailedIsFailure() = assertTrue(run(UpdateChannel.BETA, 6, net, beta = net).isFailure)
    @Test fun betaUpToDate() = assertNull(run(UpdateChannel.BETA, 9, ok(6), beta = ok(9)).getOrThrow())
    @Test fun betaChannelNeverReadsRollback() { run(UpdateChannel.BETA, 9, ok(6), beta = ok(9)) }  // never 会抛

    @Test fun minSdkTooHighIsNotNewer() {
        val high = { Result.success(info(7).copy(minSdk = 99)) }
        assertNull(run(UpdateChannel.STABLE, 6, high).getOrThrow())
    }

    @Test fun channelIdRoundTrip() {
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromId("beta"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromId("stable"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromId(null))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromId("nightly"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon :app:testReleaseUnitTest --tests '*UpdateChannelsTest*'`
Expected: 编译失败,`resolveChannel` 未定义。

- [ ] **Step 3: 实现**

```kotlin
package com.uniteduone.launcher

// 双通道的判定(spec 2026-10-09 §1)。与 UpdateChecker.kt 同一约束:不 import android.*,JVM 单测直接跑。
// 「读哪几份清单、选哪一个」全在这里;Update.kt 只负责把字节取回来。

/** 用户选的更新通道;存进 settings.json 的 `updateChannel`。 */
enum class UpdateChannel(val id: String) {
    STABLE("stable"), BETA("beta");

    companion object {
        /** 认不出(缺键、手改坏、将来删掉的通道)一律按稳定版——最保守的那一个。 */
        fun fromId(id: String?): UpdateChannel = entries.firstOrNull { it.id == id } ?: STABLE
    }
}

/** 找到的新版来自哪份清单;关于页据此选文案(回退包说「回到稳定版」)。 */
enum class UpdateKind { STABLE, BETA, ROLLBACK }

data class ChannelUpdate(val info: LatestInfo, val kind: UpdateKind)

/**
 * - **稳定通道**:读 `latest.json`;比已装的新 → 它。已装的**比它还新**(刚从 Beta 切回来)→ 读 `rollback.json`,
 *   比已装的新才给;回退包取不到 / 不够新 → 已是最新(记一行日志:多半是回退包漏发)。稳定清单本身失败 = 检查失败。
 * - **Beta 通道**:`latest.json` 与 `beta.json` 都读,取 versionCode 大的那个;只要有一份取到就不算失败
 *   (R2 上还没发过 Beta 时 `beta.json` 不存在是常态)。两份都失败才是检查失败,失败原因取稳定那份的。
 *   从不读 `rollback.json`。
 */
fun resolveChannel(
    channel: UpdateChannel,
    installedCode: Int,
    sdk: Int,
    log: (String) -> Unit = {},
    fetchStable: () -> Result<LatestInfo>,
    fetchBeta: () -> Result<LatestInfo>,
    fetchRollback: () -> Result<LatestInfo>,
): Result<ChannelUpdate?> {
    val stable = fetchStable()
    if (channel == UpdateChannel.BETA) {
        val beta = fetchBeta()
        val candidates = listOfNotNull(
            stable.getOrNull()?.let { ChannelUpdate(it, UpdateKind.STABLE) },
            beta.getOrNull()?.let { ChannelUpdate(it, UpdateKind.BETA) },
        )
        if (candidates.isEmpty()) return Result.failure(stable.exceptionOrNull() ?: UpdateCheckException(CheckFailure.NETWORK))
        if (beta.isFailure) log("beta manifest unavailable; using stable only")
        val best = candidates.maxBy { it.info.versionCode }
        return Result.success(best.takeIf { isNewer(it.info, installedCode, sdk) })
    }
    val s = stable.getOrElse { return Result.failure(it) }
    if (isNewer(s, installedCode, sdk)) return Result.success(ChannelUpdate(s, UpdateKind.STABLE))
    if (installedCode <= s.versionCode) return Result.success(null)
    val rb = fetchRollback().getOrElse {
        log("installed $installedCode > stable ${s.versionCode} but rollback manifest unavailable")
        return Result.success(null)
    }
    if (!isNewer(rb, installedCode, sdk)) {
        log("rollback ${rb.versionCode} not newer than installed $installedCode")
        return Result.success(null)
    }
    return Result.success(ChannelUpdate(rb, UpdateKind.ROLLBACK))
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `source scripts/env.sh && gradle --no-daemon :app:testReleaseUnitTest --tests '*UpdateChannelsTest*'`
Expected: PASS(16 个)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/UpdateChannels.kt app/src/test/java/com/uniteduone/launcher/UpdateChannelsTest.kt
git commit -m "feat(update): 双通道判定纯逻辑 resolveChannel"
```

---

### Task 2: 设置字段 `updateChannel`

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Settings.kt`(`Settings` 数据类、`parseSettings`、`toJson`、`restoredDefaults`)
- Test: `app/src/test/java/com/uniteduone/launcher/SettingsTest.kt`

**Interfaces:**
- Consumes: `UpdateChannel`(Task 1)。
- Produces: `Settings.updateChannel: UpdateChannel`(缺省 `STABLE`),json 键 `"updateChannel"`,值为 `UpdateChannel.id`。

- [ ] **Step 1: 写失败的测试**(追加到 `SettingsTest`)

```kotlin
    @Test fun updateChannelDefaultsToStable() = assertEquals(UpdateChannel.STABLE, parseSettings("{}").updateChannel)
    @Test fun updateChannelRoundTrips() {
        val s = Settings(updateChannel = UpdateChannel.BETA)
        assertEquals(UpdateChannel.BETA, parseSettings(s.toJson()).updateChannel)
    }
    @Test fun updateChannelUnknownIsStable() =
        assertEquals(UpdateChannel.STABLE, parseSettings("{\"updateChannel\":\"nightly\"}").updateChannel)
    @Test fun restoreDefaultsKeepsChannel() =
        assertEquals(UpdateChannel.BETA, restoredDefaults(Settings(updateChannel = UpdateChannel.BETA), 0L).updateChannel)
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon :app:testReleaseUnitTest --tests '*SettingsTest*'`
Expected: 编译失败,`updateChannel` 不存在。

- [ ] **Step 3: 实现**

`Settings` 数据类末尾(`onboardingDone` 之后)加:
```kotlin
    /** 更新通道(2026-10-09 双通道):关于页「更新通道」选的。「恢复默认」不动它(见 [restoredDefaults])。 */
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
```
`parseSettings` 的构造里(`onboardingDone = …` 之后)加:
```kotlin
            updateChannel = UpdateChannel.fromId(extractString(json, "updateChannel")),
```
`toJson` 里 `language` 那行之后加:
```kotlin
        append("  \"updateChannel\": \"${updateChannel.id}\",\n")
```
`restoredDefaults` 的 `copy(...)` 里加 `updateChannel = current.updateChannel,`,并在 KDoc 的例外清单里补一句「`updateChannel`(更新通道不是外观设置)」。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS(含原有用例)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/Settings.kt app/src/test/java/com/uniteduone/launcher/SettingsTest.kt
git commit -m "feat(settings): updateChannel 字段,恢复默认不动它"
```

---

### Task 3: 数据格式向下兼容的单测 + 铁律

回退包 = 旧代码读新数据。四个落盘文件的读取都要对「多出来的字段」宽容,现在就钉住,以后 Beta 加字段时这几条测试会提醒。

**Files:**
- Test: `app/src/test/java/com/uniteduone/launcher/ForwardCompatTest.kt`(新)
- Modify: `CLAUDE.md`(加「数据格式向下兼容铁律」一节,放在「落盘铁律」之后)

**Interfaces:**
- Consumes: `parseSettings`、`Layout.parse`(`internal`)、`parseTitles`、`parseHiddenInputs`。先读 `LayoutTest.kt` 看 `Layout.parse` 在 JVM 单测里怎么调用(它用 `org.json`;沿用 `LayoutTest` 的写法)。

- [ ] **Step 1: 写测试**

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 数据格式向下兼容(spec 2026-10-09 §5):从 Beta 切回稳定版时,旧代码要读 Beta 写下的文件。
 * Beta 只许**新增**字段——这里钉住每个读盘函数对未知字段(顶层与嵌套)宽容、已知字段不丢。
 */
class ForwardCompatTest {
    @Test fun settingsIgnoresUnknownKeys() {
        val s = parseSettings("{\"cardsPerRow\":8,\"futureFlag\":true,\"futureObj\":{\"a\":1},\"futureArr\":[1,2]}")
        assertEquals(8, s.cardsPerRow)
    }

    @Test fun layoutIgnoresUnknownKeys() {
        val rows = Layout.parse("{\"rows\":[{\"icon\":\"movie\",\"apps\":[\"com.a\"],\"futureRowKey\":1}],\"futureTop\":\"x\"}")
        assertEquals(listOf("com.a"), rows.single().apps)
    }

    @Test fun titlesKeepKnownEntries() {
        val t = parseTitles("{\"com.a\":\"甲\",\"com.b\":\"乙\"}")
        assertEquals("甲", t["com.a"])
    }

    @Test fun hiddenInputsKeepKnownEntries() {
        assertEquals(setOf("in1"), parseHiddenInputs("{\"in1\":\"x\"}"))
    }
}
```
(若 `LayoutRow` 的字段名不是 `apps`,按 `Layout.kt` 改;若 `parseHiddenInputs` 的样本格式与此不同,按 `InputPrefsTest.kt` 里的合法样本改。titles / hidden-inputs 是扁平「键→值」表,本身没有可新增的「字段」,这两条只防将来有人把它们改成带结构的格式。)

- [ ] **Step 2: 跑测试**

Run: `source scripts/env.sh && gradle --no-daemon :app:testReleaseUnitTest --tests '*ForwardCompatTest*'`
Expected: PASS。**若 `settingsIgnoresUnknownKeys` 失败**(嵌套对象里的 `}` 截断了扁平 tokenizer),这是真问题:在 `Settings.kt` 的 `extractRaw` 修到能跳过嵌套值,再跑。

- [ ] **Step 3: 写 CLAUDE.md 铁律**

在「## 落盘铁律」一节之后加:
```markdown
## 数据格式向下兼容铁律(2026-10-09 双通道)

从 Beta 切回稳定版装的是**旧代码**(回退包),它要读 Beta 写下的 layout / titles / hidden-inputs / settings。
- Beta 只**新增**字段;不改既有字段的含义、类型、取值集合,不改文件名与目录结构。
- 确需改格式:先发一个「能读新格式」的稳定版,再发写新格式的 Beta。
- `ForwardCompatTest` 钉住每个读盘函数对未知字段宽容;新增落盘文件时同步加一条。设计稿 `docs/superpowers/specs/2026-10-09-release-channels-design.md`。
```

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/uniteduone/launcher/ForwardCompatTest.kt CLAUDE.md
git commit -m "test: 落盘文件向下兼容单测;CLAUDE.md 加数据格式向下兼容铁律"
```

---

### Task 4: 通道地址配置 + 检查更新接上通道

**Files:**
- Modify: `gradle.properties`(加两个属性)
- Modify: `app/build.gradle.kts`(`BETA_URLS` / `ROLLBACK_URLS` 两个 BuildConfig 字段,写法照 `UPDATE_URLS`)
- Modify: `app/src/main/java/com/uniteduone/launcher/Update.kt`(`checkChannel`)
- Modify: `app/src/main/java/com/uniteduone/launcher/AboutScreen.kt`(`AboutController` 构造、`check()`、`Found` 带 kind、`switchChannel`、文案)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt:265`(构造 `AboutController`)
- Modify: `app/src/main/res/values{,-en,-zh-rTW}/strings.xml`
- Test: `app/src/test/java/com/uniteduone/launcher/AboutStateTest.kt`

**Interfaces:**
- Consumes: `resolveChannel`、`UpdateChannel`、`UpdateKind`、`ChannelUpdate`(Task 1);`Settings.updateChannel`(Task 2)。
- Produces:
  - `Update.checkChannel(channel: UpdateChannel, installedCode: Int, sdk: Int): Result<ChannelUpdate?>`
  - `AboutState.Found(info: LatestInfo, kind: UpdateKind = UpdateKind.STABLE)`
  - `AboutController(activity, currentVersionCode, channel: () -> UpdateChannel)`;`fun switchChannel()` = 作废进行中的一切,回 Idle,再 `check()`。
  - `BuildConfig.BETA_URLS` / `BuildConfig.ROLLBACK_URLS`(逗号分隔,同 `UPDATE_URLS`)。

- [ ] **Step 1: 配置**

`gradle.properties` 加:
```properties
unitedu.betaUrls=https://dl.uniteduone.com/unitedu/beta.json,https://github.com/GordonWang1878/UnitedU-launcher/releases/download/channel-beta/beta.json
unitedu.rollbackUrls=https://dl.uniteduone.com/unitedu/rollback.json,https://github.com/GordonWang1878/UnitedU-launcher/releases/download/channel-beta/rollback.json
```
`app/build.gradle.kts`:照 `updateUrls` 的读法加 `betaUrls` / `rollbackUrls`(缺省空串),照 `UPDATE_URLS` 的 `buildConfigField` 写法加 `BETA_URLS` / `ROLLBACK_URLS`。KDoc 写一句:模拟器验证时用 `-Punitedu.betaUrls=http://127.0.0.1:…/beta.json` 覆盖(同 `updateUrls`)。

- [ ] **Step 2: `Update.checkChannel`**

在 `Update.check` 之后加:
```kotlin
    /** 双通道检查(规则见 [resolveChannel]);三份清单各自走 [resolveLatest] 的逐通道兜底。 */
    @WorkerThread
    fun checkChannel(channel: UpdateChannel, installedCode: Int, sdk: Int): Result<ChannelUpdate?> =
        resolveChannel(
            channel, installedCode, sdk,
            log = { Log.w(TAG, it) },
            fetchStable = { check(configuredUrls()) },
            fetchBeta = { check(parseUpdateUrls(BuildConfig.BETA_URLS)) },
            fetchRollback = { check(parseUpdateUrls(BuildConfig.ROLLBACK_URLS)) },
        )
```

- [ ] **Step 3: 写失败的测试**(追加到 `AboutStateTest`)

```kotlin
    @Test fun foundDefaultsToStableKind() {
        val i = LatestInfo(7, "1.1.0", "", "https://e.com/a.apk", "0".repeat(64), 1)
        assertEquals(UpdateKind.STABLE, AboutState.Found(i).kind)
        assertEquals(AboutAction.DOWNLOAD, AboutState.Found(i, UpdateKind.ROLLBACK).action)
    }
```
Run: `source scripts/env.sh && gradle --no-daemon :app:testReleaseUnitTest --tests '*AboutStateTest*'` → 编译失败。

- [ ] **Step 4: 改 `AboutController` / `AboutState`**

- `data class Found(override val info: LatestInfo, val kind: UpdateKind = UpdateKind.STABLE)`。
- 构造参数 `urls: List<String>` 换成 `channel: () -> UpdateChannel`。
- `check()` 里:
```kotlin
            val result = withContext(Dispatchers.IO) { Update.checkChannel(channel(), currentVersionCode, Build.VERSION.SDK_INT) }
            if (my != session) return@launch
            state = result.fold(
                onSuccess = { up ->
                    Log.i(TAG, "update check (${channel().id}): current $currentVersionCode, found=${up?.info?.versionCode} kind=${up?.kind}")
                    if (up != null) AboutState.Found(up.info, up.kind) else AboutState.Latest
                },
                onFailure = { /* 原样保留 */ },
            )
```
- 新增:
```kotlin
    /**
     * 用户刚换了通道(Review Focus 4):作废进行中的检查 / 下载(包括等着按「安装」的那一份,文件删掉),
     * 再按新通道检查一次。旧通道下了一半的包绝不会被装上——它的会话号已经作废。
     */
    fun switchChannel() {
        reset()
        check()
    }
```
- `headline()`:`info != null` 时按 kind 选文案:`ROLLBACK` → `R.string.about_found_rollback`(参数 versionName),其余 → 原 `about_found`。
- `MainActivity.kt:265`:`AboutController(this, BuildConfig.VERSION_CODE) { SettingsStore.read(this).updateChannel }`。

- [ ] **Step 5: 文案**(三份 strings.xml 各加)

| key | 简中 | 英 | 繁中 |
|---|---|---|---|
| `about_found_rollback` | 可回到稳定版 %1$s(布局与设置保留) | Back to stable %1$s (layout and settings kept) | 可回到穩定版 %1$s(版面與設定保留) |

- [ ] **Step 6: 跑全部单测**

Run: `source scripts/env.sh && gradle --no-daemon :app:testReleaseUnitTest`
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add gradle.properties app/build.gradle.kts app/src/main/java/com/uniteduone/launcher/{Update,AboutScreen,MainActivity}.kt app/src/main/res/values*/strings.xml app/src/test/java/com/uniteduone/launcher/AboutStateTest.kt
git commit -m "feat(update): 检查更新按通道读清单;回退包文案"
```

---

### Task 5: 关于页「更新通道」胶囊 + 二选一页(R164)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/ShellModel.kt`(页 id、胶囊表、`aboutPageShown`、`defaultFocus`、`pageCapsuleIds`、`shellPages`)
- Modify: `app/src/main/java/com/uniteduone/launcher/SettingsShell.kt`(画 `ShellPages.CHANNEL` 这一层,照 `ShellPages.RESTORE` 分支写)
- Modify: `app/src/main/java/com/uniteduone/launcher/AboutScreen.kt`(第二颗胶囊、版本号后标 Beta)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(接线:`onChannel` 推栈、选中写盘 + 弹栈 + `aboutFlow.switchChannel()`)
- Modify: `app/src/main/res/values{,-en,-zh-rTW}/strings.xml`
- Test: `app/src/test/java/com/uniteduone/launcher/ShellModelTest.kt`(`SettingsPageLimitTest` 自动覆盖新页)

**Interfaces:**
- Consumes: `UpdateChannel`、`Settings.updateChannel`、`AboutController.switchChannel()`。
- Produces(ShellModel.kt):
  ```kotlin
  object ShellPages { const val CHANNEL = "channel" }   // 加进现有 object
  const val ABOUT_CHANNEL = "updateChannel"
  const val CHANNEL_STABLE = "channel:stable"
  const val CHANNEL_BETA = "channel:beta"
  val CHANNEL_CAPSULES: List<String> = listOf(CHANNEL_STABLE, CHANNEL_BETA)
  val ABOUT_CAPSULES = listOf(ShellPages.ABOUT, ABOUT_CHANNEL, ABOUT_RESTORE)
  fun channelCapsuleId(c: UpdateChannel): String
  fun aboutPageShown(about: Boolean, stack: List<ShellFrame>): Boolean  // 栈顶是 RESTORE 或 CHANNEL 时为假
  ```

- [ ] **Step 1: 写失败的测试**(追加到 `ShellModelTest`)

```kotlin
    @Test fun aboutHidesUnderChannelPage() {
        assertFalse(aboutPageShown(true, listOf(ShellFrame(ShellPages.ROOT), ShellFrame(ShellPages.CHANNEL))))
        assertTrue(aboutPageShown(true, listOf(ShellFrame(ShellPages.ROOT))))
    }
    @Test fun channelPageCapsules() {
        assertEquals(CHANNEL_CAPSULES, pageCapsuleIds(ShellPages.CHANNEL, emptyList()))
        assertTrue(ShellPages.CHANNEL in shellPages(emptyList()))
        assertEquals(listOf(ShellPages.ABOUT, ABOUT_CHANNEL, ABOUT_RESTORE), ABOUT_CAPSULES)
    }
    @Test fun channelCapsuleIds() {
        assertEquals(CHANNEL_BETA, channelCapsuleId(UpdateChannel.BETA))
        assertEquals(CHANNEL_STABLE, channelCapsuleId(UpdateChannel.STABLE))
    }
```
另:若 `decodeShellStack` 用页 id 白名单校验(读一遍确认),加一条 `decodeShellStack` 认得 `ShellPages.CHANNEL` 的用例并照改。

Run: `source scripts/env.sh && gradle --no-daemon :app:testReleaseUnitTest --tests '*ShellModel*'` → 编译失败。

- [ ] **Step 2: 改 ShellModel.kt**

按 Interfaces 加常量与表;`aboutPageShown` 改成 `about && stack.lastOrNull()?.page !in setOf(ShellPages.RESTORE, ShellPages.CHANNEL)`(KDoc 补一句:通道页同理);`pageCapsuleIds` 的 `when` 加 `ShellPages.CHANNEL -> CHANNEL_CAPSULES`;`shellPages` 在 `add(ShellPages.RESTORE)` 后加 `add(ShellPages.CHANNEL)`;`defaultFocus` **不**给 CHANNEL 缺省(推栈时由 MainActivity 传当前通道那颗,见 Step 4);`channelCapsuleId` = `if (c == UpdateChannel.BETA) CHANNEL_BETA else CHANNEL_STABLE`。若 `decodeShellStack` 有页 id 白名单,把 `CHANNEL` 加进去。

- [ ] **Step 3: SettingsShell.kt 画通道页**

在 `top.page == ShellPages.RESTORE -> { … }` 分支之后加(参数 `channel: UpdateChannel`、`onPickChannel: (UpdateChannel) -> Unit` 顺着 `onConfirmRestore` 的路子加到 `SettingsShell` 的参数表):
```kotlin
            top.page == ShellPages.CHANNEL -> {
                // 双通道(2026-10-09,R164):从关于页「更新通道」进来,关于页此时让开(aboutPageShown)。
                // 选中即写盘、弹栈,关于页重新出现并按新通道检查一次;返回 = 不改。✓ 标在已保存的那一颗。
                val items = CHANNEL_CAPSULES.map { id ->
                    val c = if (id == CHANNEL_BETA) UpdateChannel.BETA else UpdateChannel.STABLE
                    Capsule(
                        id = id,
                        label = stringResource(if (c == UpdateChannel.BETA) R.string.channel_beta else R.string.channel_stable),
                        trailing = if (c == channel) Trailing.Check else Trailing.None,
                        onClick = { onPop(); if (c != channel) onPickChannel(c) },
                    )
                }
                ShellScaffold(
                    left = {
                        ShellTitle(settingsTitle + " · " + stringResource(R.string.menu_about), stringResource(R.string.channel_title)) {
                            ShellNote(
                                text = stringResource(if (target == CHANNEL_BETA) R.string.channel_beta_note else R.string.channel_stable_note),
                                reserve = listOf(stringResource(R.string.channel_beta_note), stringResource(R.string.channel_stable_note)),
                            )
                        }
                    },
                    right = { CapsuleColumn(items, target, onFocus, focusNonce, covered) },
                )
            }
```
(`ShellNote` 的签名以 `SettingsShell.kt` 默认桌面页那处调用为准。)

- [ ] **Step 4: AboutScreen + MainActivity 接线**

- `AboutScreen` 加参数 `channel: UpdateChannel`、`onChannel: () -> Unit`;`items` 按 `ABOUT_CAPSULES` 画,`ABOUT_CHANNEL` 那颗:`Capsule(id, stringResource(R.string.channel_title), onClick = onChannel, trailing = Trailing.Value(<当前通道名>, chevron = true))`(`Trailing.Value` 的构造以 `ShellCapsule.kt:63` 起的定义为准)。
- 版本号那行:`BuildConfig.VERSION_NAME.contains("-beta")` 时在后面接 ` · Beta`(`R.string.about_beta_badge`)。
- MainActivity 关于页调用处(`MainActivity.kt` 约 990 行):
  ```kotlin
  channel = homeSettings.updateChannel,   // homeSettings 若不随 settingsRevision 重读,改用 remember(settingsRevision) { SettingsStore.read(...) }
  onChannel = { if (live) shellStack = shellPush(shellStack, ShellPages.CHANNEL, channelCapsuleId(homeSettings.updateChannel)) },
  ```
  `SettingsShell(...)` 调用处传 `channel` 与
  ```kotlin
  onPickChannel = { c ->
      SettingsStore.update(this@MainActivity) { it.copy(updateChannel = c) }
      settingsRevision++
      aboutFlow.switchChannel()
  },
  ```
- 返回键:外壳栈顶是 CHANNEL 时,返回走 `popShell()`(与 RESTORE 同路;读 `dispatchKeyEvent` / `onAboutBack` 确认 RESTORE 是怎么分流的,CHANNEL 照抄)。

- [ ] **Step 5: 文案**(三份 strings.xml)

| key | 简中 | 英 | 繁中 |
|---|---|---|---|
| `channel_title` | 更新通道 | Update channel | 更新通道 |
| `channel_stable` | 稳定版 | Stable | 穩定版 |
| `channel_beta` | Beta | Beta | Beta |
| `channel_stable_note` | 经过验证的版本,推荐大多数人使用。从 Beta 切回时会装回稳定版,布局与设置保留。 | Tested releases, recommended for most people. Switching back from Beta reinstalls the stable version and keeps your layout and settings. | 經過驗證的版本,推薦大多數人使用。從 Beta 切回時會裝回穩定版,版面與設定保留。 |
| `channel_beta_note` | 先用上新功能,可能不太稳定。随时可以切回稳定版,不丢布局。 | Get new features first; may be less stable. You can switch back to Stable anytime without losing your layout. | 先用上新功能,可能不太穩定。隨時可以切回穩定版,不丟版面。 |
| `about_beta_badge` | Beta | Beta | Beta |

- [ ] **Step 6: 单测 + 构建**

Run: `source scripts/env.sh && gradle --no-daemon :app:testReleaseUnitTest assembleRelease`
Expected: 全部 PASS、构建成功(`SettingsPageLimitTest` 数到通道页 2 颗、关于页 3 颗)。

- [ ] **Step 7: 模拟器焦点验收**(`unitedu-tv`,装法见 CLAUDE.md「模拟器」)

逐条,截图存 `docs/screenshots/channels/`:
1. 设置 → 关于:三颗胶囊,第二颗「更新通道 稳定版 ›」(`01-about.jpg`)。
2. 按下 → 通道页,焦点落「稳定版」(带 ✓),左侧说明随光标切换、页名不跳(`02-channel.jpg`)。
3. 下 → Beta → 确定:回到关于页、焦点在「更新通道」、值变「Beta」、自动开始检查(`03-switched.jpg`);`adb shell cat /sdcard/Android/data/com.uniteduone.launcher/files/settings.json | grep updateChannel` 为 `"beta"`(路径以 `Paths.kt` 为准)。
4. 通道页按返回:回关于页「更新通道」,设置不变。
5. HOME:全部收掉,首页焦点正常;再进关于页,焦点落「检查更新」。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/{ShellModel,SettingsShell,AboutScreen,MainActivity}.kt app/src/main/res/values*/strings.xml app/src/test/java/com/uniteduone/launcher/ShellModelTest.kt docs/screenshots/channels/
git commit -m "feat(about): 更新通道胶囊与二选一页(R164)"
```

---

### Task 6: `release.sh` 发 Beta + 回退包,版本号单调检查

**Files:**
- Modify: `scripts/release.sh`

**Interfaces:**
- Consumes: 现有 `gen_manifest`、`check_signer`、`resolve_notes`、构建与 aapt2 读身份那一段。
- Produces:
  - 模式按版本号判定:`^[0-9]+\.[0-9]+\.[0-9]+$` = 稳定;`^[0-9]+\.[0-9]+\.[0-9]+-beta\.[0-9]+$` = Beta;其余中止。
  - `check_code_monotonic <code>`(放在 `release-checks` 段内,可被 source):取 R2 上三份清单的 versionCode 最大值(取不到的当 0),`<code>` 不大于它就返回 1。
  - Beta 模式产物:`dist/unitedu-<beta>.apk`、`dist/unitedu-<stable>-rollback-<code>.apk`、`dist/beta.json`、`dist/rollback.json`(+ R2 版 `beta-r2.json` / `rollback-r2.json`)。

- [ ] **Step 1: 模式判定 + 单调检查**

在参数解析之后加:
```bash
if [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  CHANNEL=stable
elif [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-beta\.[0-9]+$ ]]; then
  CHANNEL=beta
else
  echo "版本号格式不认识:${VERSION}(稳定 1.2.3,Beta 1.2.3-beta.4)" >&2
  exit 1
fi
```
在 `release-checks` 段内加:
```bash
# 已发布的三份清单里最大的 versionCode(取不到的当 0)。新包必须比它大,否则谁都收不到(Review Focus 5)。
MANIFEST_BASE="${R2_BASE_URL:-https://dl.uniteduone.com}"
published_max_code() {
  local max=0 c name
  for name in latest beta rollback; do
    c="$(curl -fsSL --max-time 10 "${MANIFEST_BASE%/}/unitedu/${name}.json" 2>/dev/null \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["versionCode"])' 2>/dev/null || echo 0)"
    [[ "$c" =~ ^[0-9]+$ ]] || c=0
    (( c > max )) && max=$c
  done
  echo "$max"
}
check_code_monotonic() {
  local code="$1" max
  max="$(published_max_code)"
  if (( code <= max )); then
    echo "versionCode ${code} 不比已发布的最大值 ${max} 大——用户收不到它。先在 app/build.gradle.kts 把 versionCode 改成 $((max + 1)) 或更大" >&2
    return 1
  fi
  echo "==> versionCode ${code} > 已发布最大值 ${max}"
}
```
读完 APK 身份(`APK_VERSION_CODE` 赋值)之后调用:`check_code_monotonic "$APK_VERSION_CODE" || { [[ "$DRY_RUN" -eq 1 ]] && echo "警告:dry-run 继续" >&2 || exit 1; }`。Beta 模式额外要求 `APK_VERSION_CODE + 1` 也大于最大值(恒成立,不另查)。

- [ ] **Step 2: 回退包构建(只在 Beta 模式)**

签名核对之后、生成清单之前:
```bash
if [[ "$CHANNEL" == beta ]]; then
  # 上一个稳定 tag:只认 vX.Y.Z(不带 -beta),按版本排序取最大。
  STABLE_TAG="$(git tag --list 'v[0-9]*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1)"
  [[ -n "$STABLE_TAG" ]] || { echo "找不到稳定版 tag" >&2; exit 1; }
  RB_CODE=$((APK_VERSION_CODE + 1))
  RB_DIR="$(mktemp -d)/rollback"
  git worktree add --detach "$RB_DIR" "$STABLE_TAG"
  # 回退包 = 稳定版源码 + 更高的 versionCode;versionName 不变(用户看到的仍是稳定版号)。
  ( cd "$RB_DIR" && source scripts/env.sh && gradle --no-daemon assembleRelease -PrequireReleaseKey=true -PversionCodeOverride="$RB_CODE" )
  RB_NAME="unitedu-${STABLE_TAG#v}-rollback-${RB_CODE}.apk"
  cp "$RB_DIR/app/build/outputs/apk/release/app-release.apk" "dist/$RB_NAME"
  git worktree remove --force "$RB_DIR"
  check_signer "dist/$RB_NAME" || exit 1
  RB_SHA="$(shasum -a 256 "dist/$RB_NAME" | awk '{print $1}')"
  RB_VNAME="$("$AAPT2" dump badging "dist/$RB_NAME" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
fi
```
`gen_manifest` 改成多收三个位置参数 `code name sha`(缺省用 `$APK_VERSION_CODE` / `$APK_VERSION_NAME` / `$SHA256`),回退包清单传 `$RB_CODE "$RB_VNAME" "$RB_SHA"`,notes 用一句固定双语短话「回到稳定版 / Back to stable」。

- [ ] **Step 3: 发布分流**

- 稳定模式:与现在完全一样(`gh release create … --latest`,R2 写 `latest.json`)。删掉文件头「必须发成普通 release」那段对 `-beta` 的旧说明,改写成:稳定版 `--latest`;Beta 版 `--prerelease`,不碰 `releases/latest`。
- Beta 模式:
```bash
  gh release create "$TAG" "$APK_DIST" --title "UnitedU ${VERSION}" --notes "$NOTES_RAW" --prerelease
  # 固定 tag channel-beta:放 beta.json / rollback.json / 回退包,每次覆盖。第一次不存在就建。
  gh release view channel-beta >/dev/null 2>&1 || gh release create channel-beta --prerelease --title "Beta channel" --notes "Beta 通道清单(自动维护)/ Beta channel manifests (auto-maintained)"
  gh release upload channel-beta "dist/beta.json" "dist/rollback.json" "dist/$RB_NAME" --clobber
```
GitHub 版清单的 apkUrl:Beta 包 `…/releases/download/${TAG}/${APK_NAME}`,回退包 `…/releases/download/channel-beta/${RB_NAME}`。
R2:Beta 包与回退包按现有 APK 的 put 写法上传;`beta.json` / `rollback.json` 按现有 `latest.json` 的 put 写法(`no-cache`)。**Beta 模式不写 `latest.json`。**

- [ ] **Step 4: dry-run 验证**

```bash
# 临时把 versionName 改成 1.1.0-beta.1、versionCode 改成 R2 上最大值 + 1(只在工作区,测完 git checkout 还原)
scripts/release.sh 1.1.0-beta.1 --dry-run --notes "测试"
```
Expected:构建两个 APK、两者签名都是 release 证书、`dist/beta.json` 的 versionCode = N、`dist/rollback.json` = N+1 且 versionName 是最新稳定版号;打印的计划里 Beta 是 `--prerelease`、不写 `latest.json`。再把 versionCode 改成已发布最大值,dry-run 应打印「versionCode … 不比已发布的最大值 … 大」的警告。还原:`git checkout app/build.gradle.kts`,`git worktree list` 里不应残留回退构建目录。

- [ ] **Step 5: Commit**

```bash
git add scripts/release.sh
git commit -m "feat(release): Beta 模式(prerelease + 回退包 + beta/rollback 清单)与 versionCode 单调检查"
```

---

### Task 7: 端到端(模拟器 + 本机清单服务)

不发版,用 `adb reverse` + 本机 HTTP 服务模拟三份清单,走真实的下载 → 校验 → 安装。

**Files:**
- Create: `scripts/e2e/channels.sh`(起服务、造包、按场景换清单;读一遍 `scripts/e2e/` 现有脚本照抄约定)

- [ ] **Step 1: 造三个包**(都用 release 签名,`-PrequireReleaseKey=true`)

基线 A:当前代码,`-PversionCodeOverride=100`,`-Punitedu.updateUrls=http://127.0.0.1:8099/latest.json -Punitedu.betaUrls=http://127.0.0.1:8099/beta.json -Punitedu.rollbackUrls=http://127.0.0.1:8099/rollback.json`。
Beta B:同上地址,`versionCodeOverride=101`(versionName 临时改 `x-beta.1`)。
回退 C:同上地址,`versionCodeOverride=102`(versionName 用稳定版号)。
清单用 `release.sh` 里的 python 片段生成(apkUrl 指 `http://127.0.0.1:8099/…`,sha256 实算)。

- [ ] **Step 2: 场景**

`adb reverse tcp:8099 tcp:8099`,`python3 -m http.server 8099`(在一个只放清单与 APK 的 scratchpad 目录里)。装 A。
1. 稳定通道,`latest.json` → A(100):关于页「已是最新」。
2. 切 Beta,`beta.json` → B:自动检查出「发现新版本 x-beta.1」→ 下载安装 → 关于页版本带 Beta。
3. 切回稳定,`rollback.json` → C:显示「可回到稳定版 …(布局与设置保留)」→ 安装 → 版本号回稳定、首页布局与改过的一项设置(先改一下卡片大小)都还在。
4. 删掉 `beta.json`,切 Beta:按稳定版判断,不报检查失败(Review Focus 1)。
5. 切 Beta 后在下载中途立刻切回稳定:下载作废,按稳定通道重新检查(Review Focus 4);`adb logcat -s UnitedU` 里只有一条 `committed`。
每条截图存 `docs/screenshots/channels/e2e-<n>.jpg`。

- [ ] **Step 3: Commit**

```bash
git add scripts/e2e/channels.sh docs/screenshots/channels/
git commit -m "test(e2e): 双通道端到端脚本与截图"
```

---

### Task 8: 文档同步

**Files:**
- Modify: `docs/superpowers/specs/2026-09-20-gtv-line-design.md`(§12 续写 R164:关于页更新通道胶囊 + 通道页)
- Modify: `docs/design/settings-inventory.md`(关于页 3 颗、通道页 2 颗)
- Modify: `docs/REVIEW-GUIDE.md`(文件地图加 `UpdateChannels.kt`;约束加「数据格式向下兼容」)
- Modify: `README.md` / `README.zh-CN.md`(「更新」一节加一段:稳定版 / Beta 两条通道,在 关于 → 更新通道 切换,切回稳定不丢布局)
- Modify: `CLAUDE.md`(「1.0 之后不再往电视内网直连装包」那段补:Beta 用 `release.sh x.y.z-beta.N`;**第一个带通道开关的版本必须先作为稳定版发出**,用户才有地方切 Beta)
- Modify: `docs/WORKLOG.md`(实施记录、未验证项:真机 R2 上的 Beta 发布还没跑过)

- [ ] **Step 1: 逐个改完**
- [ ] **Step 2: Commit**

```bash
git add docs CLAUDE.md README.md README.zh-CN.md
git commit -m "docs: 双通道(R164)同步设计索引、设置清单、评审入口、README"
```
