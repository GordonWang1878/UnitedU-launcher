# 主页键接管(无障碍服务)实施计划

> **执行记录(2026-10-02,实现时相对本计划的改动;下面正文保持原样,以代码与 spec R162 为准)**
> - **屏保改广播**:屏保窗口的无障碍事件类名是 `android.widget.FrameLayout`、不是 `DreamActivity`,按窗口类名认不出;`dreaming` 改由服务运行时注册接收 `ACTION_DREAMING_STARTED / STOPPED`——正文里的 `nextDreaming` / `DREAM_ACTIVITY_CLASS` / API ≥ 30 门槛都已不存在。
> - **受限改安装来源 + 记号**:应用读不到自己的 `ACCESS_RESTRICTED_SETTINGS` appop,`isRestricted` 改按 `packageSource ∈ {3, 4}`(API ≥ 33)推断,并加「见过一次」的记号 `restrictedSeen`(会话更新会把来源改回 0);受限时按胶囊 = 系统 Toast + 照样打开无障碍页。
> - **「在运行」= 心跳 + 已绑定**:心跳(本次开机连上过)**且**系统此刻真的绑定着(`HomeKeyState.isBound`);「开关开着」另按 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 判(`enabledServiceSetting`)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 UnitedU 抢不到 HOME 键的电视(Google TV / GMS Android TV、锁 HOME 的国产品牌)上,用户在系统「无障碍」里打开一个服务后,按主页键直接回到 UnitedU,原厂桌面不用卸。

**Architecture:** 一个跑在独立进程 `:homekey` 的 `AccessibilityService`(`HomeKeyService`),判定全在纯函数文件 `HomeKeyPolicy.kt`(JVM 单测);A 截键(HOME 按下拉起、按下 + 松开都吃)+ B 盯窗口(别的桌面的 HOME Activity 到前台就拉回)双路;只在 UnitedU 不是默认桌面时生效;屏保在前台时放行。设置「默认桌面」页与引导第 3 步各加一颗条件胶囊跳系统无障碍设置;状态由服务写的心跳文件 + 系统开关 + 受限 appop 三者合成。应用内自我更新改走 PackageInstaller 会话 API(否则每次更新把服务锁掉)。

**Tech Stack:** Kotlin、Compose(现有 `CapsuleColumn` / `ShellScaffold`)、`android.accessibilityservice`、`PackageInstaller` 会话 API、JUnit 4(`gradle --no-daemon testReleaseUnitTest`)。

**Spec:** `docs/superpowers/specs/2026-09-20-gtv-line-design.md` §12 **R162**;先例与探针:`docs/research/2026-10-02-home-key-takeover-prior-art.md`(§0、§2.2、§3)。

## Global Constraints

- 包名 `com.uniteduone.launcher`,`minSdk = 28`,`compileSdk = targetSdk = 35`(`app/build.gradle.kts`)。
- 构建 / 单测:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`;APK 在 `app/build/outputs/apk/release/app-release.apk`。
- **`:homekey` 进程不碰任何状态文件**(layout / titles / settings / hidden-inputs 走 `LockedFile`,它是进程内锁;落盘铁律)。心跳文件只有服务一个写者,用 `writeFileAtomically`。
- 文案三语:`res/values/strings.xml`(简体)、`values-zh-rTW`、`values-en`,键名一致;R108 起界面名在英文里用 “…”、简繁用「」。
- 设置页每页胶囊 ≤ 6(R128,`SettingsPageLimitTest` 逐页数,条件行按「全部出现」算)。
- 焦点铁律(CLAUDE.md 七条):新胶囊都走现成的 `CapsuleColumn`(逐项 requester、目标按 id),不新造焦点机制。
- 模拟器验证一律 `unitedu-gtv`(launcherx,HOME 角色被它占)与 `unitedu-tv`(tvlauncher);**测 HOME 必须用 `scripts/e2e/hwhome.sh`**(硬件路径),`adb shell input keyevent 3` 不经过无障碍过滤器。电视(A95L)只做回归,全程不按键,装包用 `scripts/tv-install.sh`。
- 不推远程;提交信息末尾加 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`。

## 文件结构

- 新建 `app/src/main/java/com/uniteduone/launcher/HomeKeyPolicy.kt`:纯判定(按键动作、窗口判定、屏保状态机、开机拉起、原厂桌面集合)。
- 新建 `HomeKeyState.kt`:心跳文件读写、系统开关 / 受限 appop 查询、状态合成(纯部分可测)。
- 新建 `HomeKeyService.kt`:无障碍服务本体(Android 接线)。
- 新建 `SelfUpdate.kt`:会话 API 自我更新 + 结果广播接收器。
- 改 `AndroidManifest.xml`:服务 + 接收器;新建 `res/xml/homekey_service.xml`。
- 改 `ShellModel.kt`(胶囊 id、`HOME_CAPSULES`)、`SettingsShell.kt`(默认桌面页)、`Onboarding.kt`(第 3 步)、`SystemStatus.kt`(无障碍设置候选链)、`MainActivity.kt`(接线)、`AboutScreen.kt`(`handOver` 改用 `SelfUpdate`)、`RelaunchAfterUpdate.kt` / `RelaunchPolicy.kt`(接管开着也拉、点名自己)。
- 三份 `strings.xml`;测试 `HomeKeyPolicyTest.kt`、`HomeKeyStateTest.kt`、`RelaunchPolicyTest.kt`(加用例)。
- 新建 `scripts/e2e/hwhome.sh` + `scripts/e2e/hid-home.json`。
- 文档:spec R162 已写;`docs/design/settings-inventory.md`、`docs/REVIEW-GUIDE.md`、`README.md`、`CLAUDE.md`(模拟器一节)、`docs/WORKLOG.md`。

---

### Task 1: 纯判定 `HomeKeyPolicy.kt`

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/HomeKeyPolicy.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/HomeKeyPolicyTest.kt`

**Interfaces:**
- Produces: `enum HomeKeyAction { PASS, CONSUME, CONSUME_AND_LAUNCH }`;`fun onHomeKey(active: Boolean, dreaming: Boolean, down: Boolean, repeat: Int): HomeKeyAction`;`fun nextDreaming(prev: Boolean, cls: String?): Boolean`;`fun stockHomes(all: List<Pair<String, String>>, self: String): Set<Pair<String, String>>`;`fun onWindowChanged(active: Boolean, pkg: String?, cls: String?, stockHomes: Set<Pair<String, String>>, lastLaunchAt: Long, now: Long): Boolean`;`fun shouldLaunchOnConnect(active: Boolean, uptimeMs: Long): Boolean`;常量 `DREAM_ACTIVITY_CLASS`、`HOME_RELAUNCH_DEBOUNCE_MS = 1_000L`、`BOOT_LAUNCH_WINDOW_MS = 180_000L`。

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeKeyPolicyTest {
    private val launcherx = "com.google.android.apps.tv.launcherx" to "com.google.android.apps.tv.launcherx.home.HomeActivity"
    private val tvlauncher = "com.google.android.tvlauncher" to "com.google.android.tvlauncher.MainActivity"
    private val stock = setOf(launcherx, tvlauncher)

    /** R162:不生效 / 屏保 → 放行;第一下按下 → 吃掉并拉起;松开、长按重复 → 只吃掉。 */
    @Test fun homeKeyTable() {
        assertEquals(HomeKeyAction.PASS, onHomeKey(active = false, dreaming = false, down = true, repeat = 0))
        assertEquals(HomeKeyAction.PASS, onHomeKey(active = true, dreaming = true, down = true, repeat = 0))
        assertEquals(HomeKeyAction.PASS, onHomeKey(active = true, dreaming = true, down = false, repeat = 0))
        assertEquals(HomeKeyAction.CONSUME_AND_LAUNCH, onHomeKey(active = true, dreaming = false, down = true, repeat = 0))
        assertEquals(HomeKeyAction.CONSUME, onHomeKey(active = true, dreaming = false, down = false, repeat = 0))
        assertEquals(HomeKeyAction.CONSUME, onHomeKey(active = true, dreaming = false, down = true, repeat = 3))
    }

    /** 屏保状态:DreamActivity → 在屏保里;别的 Activity → 不在;弹窗 / 非 Activity 类名不改状态。 */
    @Test fun dreamingTracksActivityWindowsOnly() {
        assertTrue(nextDreaming(prev = false, cls = DREAM_ACTIVITY_CLASS))
        assertTrue(nextDreaming(prev = true, cls = "android.widget.FrameLayout"))
        assertTrue(nextDreaming(prev = true, cls = "android.app.Dialog"))
        assertTrue(nextDreaming(prev = true, cls = null))
        assertFalse(nextDreaming(prev = true, cls = "com.android.tv.settings.MainSettings"))
        assertFalse(nextDreaming(prev = false, cls = "com.uniteduone.launcher.MainActivity"))
    }

    @Test fun stockHomesExcludesSelf() {
        val all = listOf(launcherx, "com.uniteduone.launcher" to "com.uniteduone.launcher.MainActivity", tvlauncher)
        assertEquals(stock, stockHomes(all, "com.uniteduone.launcher"))
    }

    /** 只认别的桌面的 HOME Activity 本身;同一个包的别的窗口(弹窗、其它 Activity)不算;自己不算;1 s 内不重复拉。 */
    @Test fun windowChangedOnlyForStockHomeActivity() {
        assertTrue(onWindowChanged(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 0, now = 10_000))
        assertFalse(onWindowChanged(true, launcherx.first, "android.widget.FrameLayout", stock, 0, 10_000))
        assertFalse(onWindowChanged(true, launcherx.first, "com.google.android.apps.tv.launcherx.settings.SettingsActivity", stock, 0, 10_000))
        assertFalse(onWindowChanged(true, "com.uniteduone.launcher", "com.uniteduone.launcher.MainActivity", stock, 0, 10_000))
        assertFalse(onWindowChanged(false, launcherx.first, launcherx.second, stock, 0, 10_000))
        assertFalse(onWindowChanged(true, null, null, stock, 0, 10_000))
        assertFalse("1 s 内第二个窗口事件不再拉", onWindowChanged(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 9_500, now = 10_000))
        assertTrue(onWindowChanged(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 9_000, now = 10_000))
    }

    /** 开机 3 分钟内连上才拉;用户在设置里刚打开(开机很久了)不拉——他还在设置页里。 */
    @Test fun launchOnConnectOnlyAtBoot() {
        assertTrue(shouldLaunchOnConnect(active = true, uptimeMs = 30_000))
        assertFalse(shouldLaunchOnConnect(active = true, uptimeMs = BOOT_LAUNCH_WINDOW_MS))
        assertFalse(shouldLaunchOnConnect(active = false, uptimeMs = 30_000))
    }
}
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*HomeKeyPolicyTest*'`
Expected: 编译失败(`HomeKeyAction` / `onHomeKey` 未定义)。

- [ ] **Step 3: 写实现**

```kotlin
package com.uniteduone.launcher

/**
 * 主页键接管(R162)的纯判定。服务(`HomeKeyService`,独立进程)只负责把系统事件翻译成这里的参数、再按返回值动作;
 * 一行 Android 都不碰,全部在 JVM 上钉死(`HomeKeyPolicyTest`)。先例与探针:docs/research/2026-10-02-home-key-takeover-prior-art.md。
 */

/** 对一下 HOME 键怎么办。 */
enum class HomeKeyAction {
    /** 原样放行,系统照常处理(回原厂桌面 / 退出屏保)。 */
    PASS,
    /** 吃掉,不做别的(松开、长按的重复事件)。 */
    CONSUME,
    /** 吃掉并拉起 UnitedU(第一下按下)。 */
    CONSUME_AND_LAUNCH,
}

/**
 * @param active 接管此刻生效 = UnitedU **不是**默认桌面(是的话系统自己会回到我们,服务只旁观——A95L 上就是这样)。
 * @param dreaming 前台是屏保(任何包的 `DreamActivity`):放行,让系统按原生规则「HOME 只退出屏保、不回桌面」
 *   (探针实测:吃掉的话屏保不退出,UnitedU 在屏保后面被拉起,用户卡在屏保里)。
 * @param down 按下(false = 松开);@param repeat 重复计数(长按时 > 0,长按等于短按)。
 * 松开也要吃:系统是在松开那一下才回桌面(AOSP `DisplayHomeButtonHandler.handleHomeButton`),只吃按下照样回原厂桌面。
 */
fun onHomeKey(active: Boolean, dreaming: Boolean, down: Boolean, repeat: Int): HomeKeyAction = when {
    !active || dreaming -> HomeKeyAction.PASS
    down && repeat == 0 -> HomeKeyAction.CONSUME_AND_LAUNCH
    else -> HomeKeyAction.CONSUME
}

/** 任何包的屏保 Activity 在窗口事件里的类名。 */
const val DREAM_ACTIVITY_CLASS = "android.service.dreams.DreamActivity"

/**
 * 「前台是不是屏保」只跟着 **Activity** 窗口走:屏保 Activity 来了 = 在屏保里;别的 Activity 来了 = 不在;
 * 弹窗 / 非 Activity 的窗口(`android.widget.*`、`android.app.Dialog` 这类类名)和没有类名的事件不改状态。
 */
fun nextDreaming(prev: Boolean, cls: String?): Boolean = when {
    cls == null -> prev
    cls == DREAM_ACTIVITY_CLASS -> true
    cls.startsWith("android.widget.") || cls.startsWith("android.app.") || cls.startsWith("android.view.") -> prev
    else -> false
}

/** 别的桌面的 (包名, HOME Activity 类名):`queryIntentActivities(MAIN + HOME)` 的结果去掉自己。 */
fun stockHomes(all: List<Pair<String, String>>, self: String): Set<Pair<String, String>> =
    all.filter { it.first != self }.toSet()

/** 连续两次拉起之间的最短间隔:一次换桌面会发两三个窗口事件,别拉两次。 */
const val HOME_RELAUNCH_DEBOUNCE_MS = 1_000L

/**
 * 前台窗口换成了 ([pkg], [cls]):要不要把 UnitedU 拉到前面。只认别的桌面的 **HOME Activity 本身**([stockHomes]),
 * 它们的别的 Activity / 弹窗不算——Projectivy 4.63 / 4.64 修过「所有声明 launcher activity 的应用被误接管」。
 */
fun onWindowChanged(
    active: Boolean,
    pkg: String?,
    cls: String?,
    stockHomes: Set<Pair<String, String>>,
    lastLaunchAt: Long,
    now: Long,
): Boolean = active && pkg != null && cls != null && (pkg to cls) in stockHomes && now - lastLaunchAt >= HOME_RELAUNCH_DEBOUNCE_MS

/** 开机后多久以内连上算「开机」。模拟器上 27–31 s 连上,真电视慢得多;3 分钟够。 */
const val BOOT_LAUNCH_WINDOW_MS = 3 * 60 * 1000L

/**
 * 服务刚连上(开机 / 用户刚在设置里打开 / 更新后系统重连):只在开机窗口内拉一次——开机时服务连上之前原厂桌面已经在屏幕上
 * 约 1.5 s(躲不掉),连上后它也不会再发窗口事件,不拉就停在原厂桌面。用户刚打开时不拉,他还在设置页里。
 */
fun shouldLaunchOnConnect(active: Boolean, uptimeMs: Long): Boolean = active && uptimeMs < BOOT_LAUNCH_WINDOW_MS
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*HomeKeyPolicyTest*'`
Expected: BUILD SUCCESSFUL,5 个用例全过。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/uniteduone/launcher/HomeKeyPolicy.kt app/src/test/java/com/uniteduone/launcher/HomeKeyPolicyTest.kt
git commit -m "feat: 主页键接管的纯判定(R162)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: 状态 `HomeKeyState.kt`(心跳、系统开关、受限 appop、状态合成)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/HomeKeyState.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/HomeKeyStateTest.kt`

**Interfaces:**
- Consumes: `writeFileAtomically(dst: File, verify: (File) -> Boolean = { true }, write: (OutputStream) -> Unit): Boolean`(`LockedFile.kt:119`)。
- Produces: `enum HomeKeyStatus { ON, OFF, NOT_RUNNING, RESTRICTED }`;`fun homeKeyStatus(enabled: Boolean, running: Boolean, restricted: Boolean): HomeKeyStatus`;`fun showHomeKeyCapsule(isDefaultHome: Boolean, enabled: Boolean): Boolean`;`data class HomeKeyHeartbeat(val connected: Boolean, val bootCount: Int)`;`fun formatHeartbeat(h): String`;`fun parseHeartbeat(text: String?): HomeKeyHeartbeat?`;`fun isRunning(h: HomeKeyHeartbeat?, bootCount: Int): Boolean`;`object HomeKeyState { fun write(ctx, connected: Boolean); fun read(ctx): HomeKeyHeartbeat?; fun bootCount(ctx): Int; fun isEnabled(ctx): Boolean; fun isRestricted(ctx): Boolean; fun status(ctx): HomeKeyStatus }`。

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeKeyStateTest {
    /** 开着且在跑 → ON;开着没跑 → NOT_RUNNING;没开且受限 → RESTRICTED;其余 OFF。受限只在没开时有意义。 */
    @Test fun statusTable() {
        assertEquals(HomeKeyStatus.ON, homeKeyStatus(enabled = true, running = true, restricted = false))
        assertEquals(HomeKeyStatus.ON, homeKeyStatus(enabled = true, running = true, restricted = true))
        assertEquals(HomeKeyStatus.NOT_RUNNING, homeKeyStatus(enabled = true, running = false, restricted = false))
        assertEquals(HomeKeyStatus.RESTRICTED, homeKeyStatus(enabled = false, running = false, restricted = true))
        assertEquals(HomeKeyStatus.OFF, homeKeyStatus(enabled = false, running = false, restricted = false))
    }

    /** 不是默认桌面就画;是默认桌面但服务开着也画(得让人能关掉);A95L(默认桌面、没开)不画。 */
    @Test fun capsuleVisibility() {
        assertTrue(showHomeKeyCapsule(isDefaultHome = false, enabled = false))
        assertTrue(showHomeKeyCapsule(isDefaultHome = true, enabled = true))
        assertFalse(showHomeKeyCapsule(isDefaultHome = true, enabled = false))
    }

    @Test fun heartbeatRoundTrip() {
        val h = HomeKeyHeartbeat(connected = true, bootCount = 17)
        assertEquals("1 17", formatHeartbeat(h))
        assertEquals(h, parseHeartbeat("1 17"))
        assertEquals(HomeKeyHeartbeat(false, 3), parseHeartbeat(" 0 3\n"))
        assertNull(parseHeartbeat(null))
        assertNull(parseHeartbeat(""))
        assertNull(parseHeartbeat("garbage"))
        assertNull(parseHeartbeat("1 x"))
    }

    /** 「在运行」= 这次开机连上过且没断;上次开机的心跳不算(重启后服务没起来,系统开关却还显示开着)。 */
    @Test fun runningNeedsThisBoot() {
        assertTrue(isRunning(HomeKeyHeartbeat(true, 5), bootCount = 5))
        assertFalse(isRunning(HomeKeyHeartbeat(true, 4), bootCount = 5))
        assertFalse(isRunning(HomeKeyHeartbeat(false, 5), bootCount = 5))
        assertFalse(isRunning(null, bootCount = 5))
    }
}
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*HomeKeyStateTest*'`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

```kotlin
package com.uniteduone.launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.io.File

/** 设置页 / 引导里「主页键接管」胶囊显示的状态(R162)。 */
enum class HomeKeyStatus { ON, OFF, NOT_RUNNING, RESTRICTED }

/**
 * [enabled] 系统无障碍开关开着;[running] 服务这次开机以来连上过且没断(心跳);[restricted] 受限设置锁着
 * (Android 13+ 用系统安装器侧载的包,见调研 §2.2 #9–#10)。系统开关显示开着却没在跑是 Projectivy 最常见的用户问题,所以分开报。
 */
fun homeKeyStatus(enabled: Boolean, running: Boolean, restricted: Boolean): HomeKeyStatus = when {
    enabled && running -> HomeKeyStatus.ON
    enabled -> HomeKeyStatus.NOT_RUNNING
    restricted -> HomeKeyStatus.RESTRICTED
    else -> HomeKeyStatus.OFF
}

/** 胶囊画不画:UnitedU 不是默认桌面就画;是默认桌面但服务开着也画(得让人能关掉)。A95L(默认桌面、没开)不画。 */
fun showHomeKeyCapsule(isDefaultHome: Boolean, enabled: Boolean): Boolean = !isDefaultHome || enabled

/** 心跳文件的内容:`connected bootCount`。 */
data class HomeKeyHeartbeat(val connected: Boolean, val bootCount: Int)

fun formatHeartbeat(h: HomeKeyHeartbeat): String = "${if (h.connected) 1 else 0} ${h.bootCount}"

fun parseHeartbeat(text: String?): HomeKeyHeartbeat? {
    val parts = text?.trim()?.split(' ')?.takeIf { it.size == 2 } ?: return null
    val connected = parts[0].toIntOrNull() ?: return null
    val boot = parts[1].toIntOrNull() ?: return null
    return HomeKeyHeartbeat(connected == 1, boot)
}

/** 「在运行」= 这次开机连上过且没断。 */
fun isRunning(h: HomeKeyHeartbeat?, bootCount: Int): Boolean = h != null && h.connected && h.bootCount == bootCount

/**
 * 心跳文件读写与系统状态查询。服务进程写、桌面进程读——跨进程,所以不用 SharedPreferences(它按进程缓存,另一个进程的写入看不到)。
 * 只有服务一个写者,原子写;读失败一律当「没有心跳」。
 */
object HomeKeyState {
    /** 受限设置的 appop(`AppOpsManager.OPSTR_ACCESS_RESTRICTED_SETTINGS`,API 33;老系统没有这个 op,查询抛异常 → 当不受限)。 */
    private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    private fun file(ctx: Context) = File(ctx.filesDir, "homekey.state")

    fun bootCount(ctx: Context): Int =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    fun write(ctx: Context, connected: Boolean) {
        val text = formatHeartbeat(HomeKeyHeartbeat(connected, bootCount(ctx)))
        runCatching { writeFileAtomically(file(ctx)) { it.write(text.toByteArray()) } }
    }

    fun read(ctx: Context): HomeKeyHeartbeat? = runCatching { parseHeartbeat(file(ctx).readText()) }.getOrNull()

    /** 系统无障碍开关:已启用的服务里有没有本包的。 */
    fun isEnabled(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
    }.getOrDefault(false)

    /** 受限设置锁着(自己的 appop 不是 allow)。 */
    fun isRestricted(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(OP_ACCESS_RESTRICTED_SETTINGS, Process.myUid(), ctx.packageName) != AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun status(ctx: Context): HomeKeyStatus =
        homeKeyStatus(isEnabled(ctx), isRunning(read(ctx), bootCount(ctx)), isRestricted(ctx))
}
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*HomeKeyStateTest*'`
Expected: 4 个用例全过。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/uniteduone/launcher/HomeKeyState.kt app/src/test/java/com/uniteduone/launcher/HomeKeyStateTest.kt
git commit -m "feat: 主页键接管的状态(心跳文件、系统开关、受限 appop)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: 服务 `HomeKeyService` + 清单 + 硬件 HOME 脚本,模拟器验证

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/HomeKeyService.kt`
- Create: `app/src/main/res/xml/homekey_service.xml`
- Modify: `app/src/main/AndroidManifest.xml`(`</application>` 之前)
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rTW/strings.xml`、`values-en/strings.xml`(两条:`homekey_service_label`、`homekey_service_desc`)
- Create: `scripts/e2e/hwhome.sh`、`scripts/e2e/hid-home.json`

**Interfaces:**
- Consumes: Task 1 全部函数;Task 2 `HomeKeyState.write`。
- Produces: 组件 `com.uniteduone.launcher/.HomeKeyService`(设置页 / 引导靠系统开关识别,不直接引用)。

- [ ] **Step 1: 服务**

```kotlin
package com.uniteduone.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 主页键接管(R162):UnitedU 不是默认桌面的电视(Google TV、锁 HOME 的国产品牌)上,按 HOME 回到 UnitedU。
 * 判定全在 `HomeKeyPolicy.kt`;这里只翻译系统事件、执行动作。两条路:
 * - 截键:[onKeyEvent] 先于系统看到 HOME(AOSP 里过滤器在 PhoneWindowManager 处理 HOME 之前),吃掉按下 + 松开、拉起首页——
 *   原厂桌面一帧都不画;
 * - 盯窗口:[onAccessibilityEvent] 看到别的桌面的 HOME Activity 到了前台就拉回来——开机、系统别的路径回桌面时的兜底,会先闪一下原厂桌面。
 * 跑在独立进程 `:homekey`(清单 `android:process`):开着时电视上每一下按键都先经过它,不能和首页的绘制抢主线程;
 * 系统把被绑定的无障碍服务常驻,独立进程才不会把整个桌面(壁纸位图)钉在内存里。**这个进程不碰任何状态文件**(`LockedFile` 是进程内锁),
 * 只写自己的心跳文件([HomeKeyState])。
 * 拉起用 `MAIN + HOME` + 显式组件:任务是 `type=home`,从应用按返回直接落回我们(普通 intent 是 `type=standard`,会先闪原厂桌面——Projectivy #605)。
 */
class HomeKeyService : AccessibilityService() {
    private var dreaming = false
    private var lastLaunchAt = 0L
    private var active = false
    private var stockHomes: Set<Pair<String, String>> = emptySet()
    private var refreshedAt = Long.MIN_VALUE / 2

    override fun onServiceConnected() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.notificationTimeout = 0
        serviceInfo = info
        refresh(force = true)
        HomeKeyState.write(this, connected = true)
        val uptime = SystemClock.elapsedRealtime()
        Log.i(TAG, "homekey connected: active=$active stock=$stockHomes uptime=$uptime")
        if (shouldLaunchOnConnect(active, uptime)) launch("connect")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        HomeKeyState.write(this, connected = false)
        Log.i(TAG, "homekey unbound")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount == 0) refresh(force = false)
        return when (onHomeKey(active, dreaming, down, event.repeatCount)) {
            HomeKeyAction.PASS -> false
            HomeKeyAction.CONSUME -> true
            HomeKeyAction.CONSUME_AND_LAUNCH -> { launch("key"); true }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()
        dreaming = nextDreaming(dreaming, cls)
        val now = SystemClock.elapsedRealtime()
        // 别的桌面来了才值得重算(resolveActivity / queryIntentActivities 是 binder 调用,窗口事件很频繁)
        if (pkg != null && pkg != packageName && stockHomes.any { it.first == pkg }) refresh(force = false)
        if (onWindowChanged(active, pkg, cls, stockHomes, lastLaunchAt, now)) launch("window:$pkg")
    }

    /** 默认桌面是谁、别的桌面有哪些:至多每 [REFRESH_MS] 重算一次(用户在系统设置里换默认桌面没有广播)。 */
    private fun refresh(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - refreshedAt < REFRESH_MS) return
        refreshedAt = now
        val pm = packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val default = runCatching { pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        // 没有默认桌面(null,按 HOME 弹选择框)也算生效:接管开着就是要回 UnitedU。
        active = default != packageName
        stockHomes = runCatching {
            pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName to it.activityInfo.name }
        }.getOrDefault(emptyList()).let { stockHomes(it, packageName) }
    }

    private fun launch(why: String) {
        lastLaunchAt = SystemClock.elapsedRealtime()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(this, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 无障碍服务被系统以 BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS 绑定,后台启动不需要悬浮窗权限;失败只记日志。
        runCatching { startActivity(intent) }
            .onSuccess { Log.i(TAG, "homekey launch ($why)") }
            .onFailure { Log.w(TAG, "homekey launch ($why) failed", it) }
    }

    private companion object {
        const val TAG = "UnitedU"
        const val REFRESH_MS = 5_000L
    }
}
```

- [ ] **Step 2: 配置 xml、清单、文案**

`app/src/main/res/xml/homekey_service.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- R162 主页键接管:只过滤按键 + 窗口切换事件,不读窗口内容(canRetrieveWindowContent=false,系统页上也这么说明)。 -->
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/homekey_service_desc"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:canRequestFilterKeyEvents="true"
    android:canRetrieveWindowContent="false"
    android:notificationTimeout="0" />
```

清单,放在 `UnitedUDream` 的 `</service>` 之后:

```xml
        <!-- R162 主页键接管:无障碍服务,独立进程(每一下按键都先经过它,不与首页抢主线程;常驻的只是这个小进程)。
             BIND_ACCESSIBILITY_SERVICE = 只有系统能绑;系统来绑,exported 必须为 true。判定与探针见 docs/research/2026-10-02-home-key-takeover-prior-art.md。 -->
        <service
            android:name=".HomeKeyService"
            android:exported="true"
            android:label="@string/homekey_service_label"
            android:process=":homekey"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/homekey_service" />
        </service>
```

文案(三份 strings.xml 各加,放在 `home_settings_*` 后面):

```xml
    <!-- R162 主页键接管:系统「无障碍」页里的服务名与说明 -->
    <string name="homekey_service_label">UnitedU 主页键接管</string>
    <string name="homekey_service_desc">只做一件事:在 UnitedU 不是默认桌面的电视上,按主页键回到 UnitedU。只看主页键和前台应用的名字,不读取屏幕内容。</string>
```
繁體:`UnitedU 主頁鍵接管` / `只做一件事:在 UnitedU 不是預設桌面的電視上,按主頁鍵回到 UnitedU。只看主頁鍵和前景應用程式的名稱,不讀取螢幕內容。`
English:`UnitedU Home Button` / `Does one thing: on TVs where UnitedU is not the default home app, the Home button returns to UnitedU. It only sees the Home button and the name of the app in front; it never reads screen content.`

- [ ] **Step 3: 硬件 HOME 脚本**

`scripts/e2e/hid-home.json`(uhid 造一个 USB 消费类遥控,报告 = AC Home 0x0223 → `KEY_HOMEPAGE` → `KEYCODE_HOME`):

```json
{"id": 1, "command": "register", "name": "UnitedU e2e remote", "vid": 0x18d1, "pid": 0x4e71, "bus": "usb",
 "descriptor": [0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00, 0xC0]}
{"id": 1, "command": "delay", "duration": 800}
{"id": 1, "command": "report", "report": [0x23, 0x02]}
{"id": 1, "command": "delay", "duration": 120}
{"id": 1, "command": "report", "report": [0x00, 0x00]}
{"id": 1, "command": "delay", "duration": 600}
```

`scripts/e2e/hwhome.sh`(`chmod +x`):

```bash
#!/bin/bash
# 往模拟器发一下**硬件路径**的 HOME 键:用系统自带的 /system/bin/hid 经 uhid 造一个 USB 消费类遥控,发 AC Home。
# `adb shell input keyevent 3` 是注入事件,不经过无障碍按键过滤器(AOSP InputDispatcher::injectInputEvent 不调 filterInputEvent),
# `adb emu event send` / `sendevent` 在这两台 AVD 上到不了输入层——测主页键接管(R162)只能用这个。
# 用法:scripts/e2e/hwhome.sh [serial](缺省 emulator-5554)
set -euo pipefail
source "$(dirname "$0")/../env.sh"
S=${1:-emulator-5554}
adb -s "$S" push "$(dirname "$0")/hid-home.json" /data/local/tmp/hid-home.json >/dev/null
adb -s "$S" shell hid /data/local/tmp/hid-home.json >/dev/null 2>&1 || true
```

- [ ] **Step 4: 构建并在 `unitedu-gtv` 上验证**

```bash
source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease
emulator -avd unitedu-gtv -port 5554 -no-snapshot -no-audio -gpu host &   # 没开着才起
adb -s emulator-5554 wait-for-device && adb -s emulator-5554 shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5554 shell settings put secure enabled_accessibility_services com.uniteduone.launcher/com.uniteduone.launcher.HomeKeyService
adb -s emulator-5554 shell settings put secure accessibility_enabled 1
sleep 3; adb -s emulator-5554 logcat -d -s UnitedU | grep homekey        # 期望:homekey connected: active=true stock=[(launcherx, …HomeActivity), …]
adb -s emulator-5554 shell cat /data/data/com.uniteduone.launcher/files/homekey.state 2>/dev/null || adb -s emulator-5554 shell run-as com.uniteduone.launcher cat files/homekey.state   # release 包不可 run-as:改看日志即可
adb -s emulator-5554 shell am start -n com.android.tv.settings/.MainSettings; sleep 2
adb -s emulator-5554 logcat -c; scripts/e2e/hwhome.sh emulator-5554; sleep 3
adb -s emulator-5554 logcat -d -s UnitedU | grep homekey                   # 期望:homekey launch (key)
adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus             # 期望:com.uniteduone.launcher/.MainActivity
adb -s emulator-5554 logcat -d | grep -c "launcherx.home.HomeActivity"      # 期望:0(原厂桌面没出现过;对照:关掉服务再按,这里 > 0)
adb -s emulator-5554 shell dumpsys activity activities | grep -E "^\s*\* Task\{" | head -3   # 期望:UnitedU 的任务 type=home
```
再验三件:①`adb shell input keyevent 23`(打开焦点卡的应用)→ `input keyevent 4` 返回 → 焦点回 UnitedU,logcat 里没有 launcherx 的 `HomeActivity`;②`adb reboot`,等开机 + 40 s,`logcat -d -s UnitedU | grep homekey` 有 `connected` 与 `launch (connect)`,前台是 UnitedU;③屏保:按 CLAUDE.md「系统屏保」那一段让 `UnitedUDream` 到点启动,`dumpsys window` 看到 `DreamActivity` 后 `hwhome.sh` → 屏保退出、**不**拉起(logcat 没有 `launch`),前台是屏保下面那个应用。测完还原那几个 settings。
`unitedu-tv`(5556)重复截键那一段(tvlauncher)。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/uniteduone/launcher/HomeKeyService.kt app/src/main/res/xml/homekey_service.xml app/src/main/AndroidManifest.xml app/src/main/res/values*/strings.xml scripts/e2e/hwhome.sh scripts/e2e/hid-home.json
git commit -m "feat: 主页键接管的无障碍服务(R162):截键 + 盯窗口,独立进程

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: 更新后拉回(R151)认接管

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/RelaunchPolicy.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/RelaunchAfterUpdate.kt:18-36`
- Test: `app/src/test/java/com/uniteduone/launcher/RelaunchPolicyTest.kt`

**Interfaces:**
- Consumes: `HomeKeyState.isEnabled(ctx)`(Task 2)。
- Produces: `fun homeOrTakeover(isDefaultHome: Boolean, takeoverEnabled: Boolean): Boolean`。

- [ ] **Step 1: 加失败的测试**(`RelaunchPolicyTest` 末尾)

```kotlin
    /** R162:不是默认桌面但主页键接管开着,更新后也拉回(intent 点名自己,见 RelaunchAfterUpdate)。 */
    @Test fun takeoverCountsAsHome() {
        assertTrue(homeOrTakeover(isDefaultHome = true, takeoverEnabled = false))
        assertTrue(homeOrTakeover(isDefaultHome = false, takeoverEnabled = true))
        assertFalse(homeOrTakeover(isDefaultHome = false, takeoverEnabled = false))
    }
```

- [ ] **Step 2: 跑,确认编译失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*RelaunchPolicyTest*'`

- [ ] **Step 3: 实现**

`RelaunchPolicy.kt` 末尾加:

```kotlin
/** R162:主页键接管开着时,HOME 本来就会回到我们,更新后拉回与默认桌面同一待遇。 */
fun homeOrTakeover(isDefaultHome: Boolean, takeoverEnabled: Boolean): Boolean = isDefaultHome || takeoverEnabled
```

`RelaunchAfterUpdate.onReceive` 里,`val isDefaultHome = …` 之后、`shouldRelaunchHome(...)` 调用改成:

```kotlin
        val isDefaultHome = defaultHome == context.packageName
        // R162:主页键接管开着时也拉;不是默认桌面的话隐式 HOME 会解析到原厂桌面,得点名自己(仍带 MAIN + HOME → type=home 任务)。
        val takeover = !isDefaultHome && HomeKeyState.isEnabled(context)
        val canOverlay = android.provider.Settings.canDrawOverlays(context)
        val marks = RelaunchMarks.read(context)
        val wasOnScreen = marks.homeVisible || marks.dreaming
        val userStarted = isRecentUpdateRequest(marks.updatePendingAt, System.currentTimeMillis())
        RelaunchMarks.consume(context)
        if (!shouldRelaunchHome(homeOrTakeover(isDefaultHome, takeover), canOverlay, wasOnScreen, userStarted)) {
            Log.i(TAG, "package replaced; skip relaunch: defaultHome=$defaultHome takeover=$takeover overlay=$canOverlay " +
                "onScreen=$wasOnScreen (home=${marks.homeVisible} dream=${marks.dreaming}) userStarted=$userStarted")
            return
        }
        val target = if (isDefaultHome) home else home.setComponent(android.content.ComponentName(context, MainActivity::class.java))
        runCatching { context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
```
(下面两行 `onSuccess` / `onFailure` 不变;类 KDoc 加一句「R162:接管开着时也拉」。)

- [ ] **Step 4: 跑测试通过,提交**

```bash
source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*RelaunchPolicyTest*'
git add app/src/main/java/com/uniteduone/launcher/RelaunchPolicy.kt app/src/main/java/com/uniteduone/launcher/RelaunchAfterUpdate.kt app/src/test/java/com/uniteduone/launcher/RelaunchPolicyTest.kt
git commit -m "feat: 更新后拉回也认主页键接管(R162 ⑦)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: 自我更新改走 PackageInstaller 会话 API

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/SelfUpdate.kt`
- Modify: `app/src/main/AndroidManifest.xml`(接收器)
- Modify: `app/src/main/java/com/uniteduone/launcher/AboutScreen.kt:309-320`(`handOver`)

**Interfaces:**
- Consumes: `ApkInstaller.Result`(现有枚举)。
- Produces: `object SelfUpdate { fun install(ctx: Context, file: File): ApkInstaller.Result }`;`class SelfUpdateResult : BroadcastReceiver`。

- [ ] **Step 1: 实现 `SelfUpdate.kt`**

```kotlin
package com.uniteduone.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * 自我更新走 PackageInstaller **会话** API(R162 ⑥,探针 #11 / #12):用 `ACTION_VIEW` 交给系统安装器的更新会把本包标成
 * `PACKAGE_SOURCE_LOCAL_FILE`,Android 13+ 当场锁上「受限设置」、把正在跑的主页键接管服务停掉、开关清空;会话安装不带来源标记,
 * appop 原样、服务更新后由系统自动重连。传 APK 装**别的**应用照旧走 [ApkInstaller](别人的受限状态与我们无关)。
 * 字节在 [install] 里就拷进会话,文件之后可以删;确认页由系统经 [SelfUpdateResult] 要我们打开。
 */
object SelfUpdate {
    private const val TAG = "UnitedU"

    /** 主线程;文件已校验([Update.verify])。STARTED = 会话已提交(系统接着弹确认页);INVALID = 开会话 / 写入失败。 */
    fun install(ctx: Context, file: File): ApkInstaller.Result {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // 与 ApkInstaller.launch 同一套权限引导:跳不过去也返回 NEEDS_PERMISSION,提示足以让用户自己去开。
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return ApkInstaller.Result.NEEDS_PERMISSION
        }
        return runCatching {
            val installer = ctx.packageManager.packageInstaller
            val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val callback = Intent(ctx, SelfUpdateResult::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(ctx, id, callback, flags).intentSender)
            }
            Log.i(TAG, "self-update session $id committed (${file.name})")
            ApkInstaller.Result.STARTED
        }.getOrElse { e ->
            Log.w(TAG, "self-update session failed", e)
            ApkInstaller.Result.INVALID
        }
    }
}

/**
 * 会话结果。要用户确认时系统给一个确认页的 intent,这里替它打开(用户刚按了「安装更新」,本应用在前台,启动不受后台限制);
 * 装成功后本进程早被杀了,收不到;失败(用户取消等)只记日志——关于页停在「安装中」,与改前 ACTION_VIEW 的行为一致。
 */
class SelfUpdateResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i("UnitedU", "self-update status=$status msg=${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w("UnitedU", "self-update confirm page failed", it) }
    }
}
```

清单(`RelaunchAfterUpdate` 的 `</receiver>` 之后):

```xml
        <!-- R162 ⑥:自我更新会话的结果(系统要用户确认时经它打开确认页)。只有本应用的 PendingIntent 会发,不导出。 -->
        <receiver
            android:name=".SelfUpdateResult"
            android:exported="false" />
```

- [ ] **Step 2: `AboutScreen.handOver` 改用它**

把

```kotlin
        RelaunchMarks.markUpdatePending(activity)
        val result = ApkInstaller.launch(activity, file)
```
改成
```kotlin
        RelaunchMarks.markUpdatePending(activity)
        // R162 ⑥:自我更新走会话 API(ACTION_VIEW 会把本包标成受限、把主页键接管服务停掉)。字节已拷进会话,文件不必再留。
        val result = SelfUpdate.install(activity, file)
```
并把末尾 `if (result != ApkInstaller.Result.STARTED) Update.discard(activity, file)` 改成 `Update.discard(activity, file)`(无论结果都删),`handOver` 的 KDoc 里「交出去的文件**不再注销**」那句改为「字节已拷进会话,文件一律删掉」。

- [ ] **Step 3: 构建 + 全量单测 + 模拟器验证**

```bash
source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease
```
验证(`emulator-5554`,服务已开着):本机起一个更新服务器——`mkdir -p /tmp/upd && cp app/build/outputs/apk/release/app-release.apk /tmp/upd/u.apk`,按 `UpdateChecker.kt` 的字段写 `/tmp/upd/latest.json`(`versionCode` 比当前大 1、`versionName`、`apkUrl` = `http://10.0.2.2:8765/u.apk`、`sha256` = `shasum -a 256 /tmp/upd/u.apk`、`minSdk` 28;`isAllowedUpdateUrl` 若只放行 https,改用 `python3 -m http.server` 配自签证书不值得——此时改为用会话 API 直接验:`adb shell cmd package install` 不行,改装 `versionCode` 相同的包走关于页也不触发;**退一步**:用 `-Punitedu.updateUrls=http://10.0.2.2:8765/latest.json` 构一个 debug 构建、并在 `isAllowedUpdateUrl` 允许 http 的前提下验证;若不允许则只验前两项),`cd /tmp/upd && python3 -m http.server 8765 &`。关于页 → 检查更新 → 安装 → 系统确认页 → 装完;然后:
```bash
adb -s emulator-5554 shell dumpsys package com.uniteduone.launcher | grep -o 'packageSource=[0-9]*'     # 期望 0(不是 3)
adb -s emulator-5554 shell appops get com.uniteduone.launcher ACCESS_RESTRICTED_SETTINGS                  # 期望 allow / No operations
adb -s emulator-5554 shell settings get secure enabled_accessibility_services                              # 期望仍含 HomeKeyService
adb -s emulator-5554 logcat -d -s UnitedU | grep "homekey connected"                                        # 期望更新后有新的一条
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/uniteduone/launcher/SelfUpdate.kt app/src/main/AndroidManifest.xml app/src/main/java/com/uniteduone/launcher/AboutScreen.kt
git commit -m "fix: 自我更新改走 PackageInstaller 会话 API,更新不再锁掉主页键接管(R162 ⑥)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: 设置「默认桌面」页与引导第 3 步的「主页键接管」胶囊

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/ShellModel.kt:57-63`
- Modify: `app/src/main/java/com/uniteduone/launcher/SystemStatus.kt`(候选链)
- Modify: `app/src/main/java/com/uniteduone/launcher/SettingsShell.kt`(参数 + `ShellPages.HOME` 分支,现 558–575 行)
- Modify: `app/src/main/java/com/uniteduone/launcher/Onboarding.kt`(`Onboarding` 参数、`HomeStep`)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(两处接线 + `openHomeKeySettings()`)
- Modify: 三份 `strings.xml`
- Test: `app/src/test/java/com/uniteduone/launcher/ShellModelTest.kt`(若有 `HOME_CAPSULES` 断言则改)、`SettingsPageLimitTest`(自动覆盖)、新增 `HomeKeyStateTest.statusTexts`

**Interfaces:**
- Consumes: `HomeKeyState.status(ctx)`、`showHomeKeyCapsule`、`HomeKeyStatus`(Task 2);`rememberCurrentHome(revision, refresh)`(`HomeSettingsCard.kt`);`Capsule(id, label, onClick, hint)`;`openSystemPage(chain, failRes)`(MainActivity 私有)。
- Produces: `const val SHELL_HOME_TAKEOVER = "homeTakeover"`;`val HOME_CAPSULES = listOf(SHELL_CHANGE_HOME, SHELL_HOME_TAKEOVER)`;`fun homeKeyStatusRes(s: HomeKeyStatus): Int`、`fun homeKeyNoteRes(s: HomeKeyStatus): Int`(放 `HomeKeyState.kt`);`internal val ACCESSIBILITY_SETTINGS_PAGES`;`SettingsShell(... onHomeKeyTakeover: () -> Unit ...)`;`Onboarding(... onOpenHomeKey: () -> Unit ...)`。

- [ ] **Step 1: 失败的测试**(`HomeKeyStateTest` 加)

```kotlin
    /** 四种状态各有自己的小字与说明;不共用,免得改一处漏一处。 */
    @Test fun statusTexts() {
        val labels = HomeKeyStatus.entries.map { homeKeyStatusRes(it) }
        val notes = HomeKeyStatus.entries.map { homeKeyNoteRes(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(notes.size, notes.toSet().size)
    }
```
以及 `ShellModelTest` 里若有 `assertEquals(listOf(SHELL_CHANGE_HOME), HOME_CAPSULES)` 一类断言,改成两颗。

- [ ] **Step 2: 模型与文案**

`ShellModel.kt`:
```kotlin
const val SHELL_CHANGE_HOME = "changeHome"
/** R162:「主页键接管」条件胶囊(不是默认桌面、或服务已开着时才画)。 */
const val SHELL_HOME_TAKEOVER = "homeTakeover"

/** 「默认桌面」页的胶囊(全部出现时)。第二颗是条件行,界面按 [showHomeKeyCapsule] 决定画不画;缺省焦点仍是第一颗。 */
val HOME_CAPSULES: List<String> = listOf(SHELL_CHANGE_HOME, SHELL_HOME_TAKEOVER)
```

`HomeKeyState.kt` 末尾:
```kotlin
/** 胶囊下的状态小字。 */
fun homeKeyStatusRes(s: HomeKeyStatus): Int = when (s) {
    HomeKeyStatus.ON -> R.string.homekey_state_on
    HomeKeyStatus.OFF -> R.string.homekey_state_off
    HomeKeyStatus.NOT_RUNNING -> R.string.homekey_state_not_running
    HomeKeyStatus.RESTRICTED -> R.string.homekey_state_restricted
}

/** 左侧说明(光标在这颗胶囊时;引导第 3 步直接画在当前桌面下面)。 */
fun homeKeyNoteRes(s: HomeKeyStatus): Int = when (s) {
    HomeKeyStatus.ON -> R.string.homekey_note_on
    HomeKeyStatus.OFF -> R.string.homekey_note_off
    HomeKeyStatus.NOT_RUNNING -> R.string.homekey_note_not_running
    HomeKeyStatus.RESTRICTED -> R.string.homekey_note_restricted
}
```

`SystemStatus.kt`(`SCREEN_OFF_SETTINGS_PAGES` 后面):
```kotlin
/** R162:「主页键接管」胶囊 → 系统无障碍设置(TvSettings 的无障碍页列出我们的服务);没有这一页的固件退到系统设置首页。 */
internal val ACCESSIBILITY_SETTINGS_PAGES = listOf(
    SystemPage(action = "android.settings.ACCESSIBILITY_SETTINGS"), // Settings.ACTION_ACCESSIBILITY_SETTINGS
    SystemPage(action = "android.settings.SETTINGS"),
)
```

简体 strings(`homekey_service_desc` 后面):
```xml
    <string name="homekey_capsule">主页键接管</string>
    <string name="homekey_state_on">已开启</string>
    <string name="homekey_state_off">未开启</string>
    <string name="homekey_state_not_running">已开启,但没在运行</string>
    <string name="homekey_state_restricted">这台电视不允许</string>
    <string name="homekey_note_off">这台电视把主页键交给了原厂桌面。开启主页键接管后,按主页键会直接回到 UnitedU,原厂桌面不用卸载。按确定前往原生电视设置的「无障碍」,打开「UnitedU 主页键接管」。</string>
    <string name="homekey_note_on">主页键接管已开启:按主页键回到 UnitedU。要还原,到原生电视设置的「无障碍」里关掉它。</string>
    <string name="homekey_note_not_running">电视显示已开启,但服务没在运行(更新或重启后常见)。到原生电视设置的「无障碍」里关掉再打开,或重启电视。</string>
    <string name="homekey_note_restricted">用文件管理器安装的 UnitedU,这台电视不允许打开它的无障碍服务,设置里也没有解锁入口。用电脑执行:adb shell appops set com.uniteduone.launcher ACCESS_RESTRICTED_SETTINGS allow;或改用 adb 安装 UnitedU。</string>
    <string name="toast_homekey_restricted">这台电视不允许打开。按左侧说明用电脑解锁</string>
```
繁體:主頁鍵接管 / 已開啟 / 未開啟 / 已開啟,但沒在執行 / 這台電視不允許 / 「這台電視把主頁鍵交給了原廠桌面。開啟主頁鍵接管後,按主頁鍵會直接回到 UnitedU,原廠桌面不用解除安裝。按確定前往原生電視設定的「無障礙」,開啟「UnitedU 主頁鍵接管」。」/ 「主頁鍵接管已開啟:按主頁鍵回到 UnitedU。要還原,到原生電視設定的「無障礙」裡關掉它。」/ 「電視顯示已開啟,但服務沒在執行(更新或重新啟動後常見)。到原生電視設定的「無障礙」裡關掉再開啟,或重新啟動電視。」/ 「用檔案管理員安裝的 UnitedU,這台電視不允許開啟它的無障礙服務,設定裡也沒有解鎖入口。用電腦執行:adb shell appops set com.uniteduone.launcher ACCESS_RESTRICTED_SETTINGS allow;或改用 adb 安裝 UnitedU。」/ 「這台電視不允許開啟。按左側說明用電腦解鎖」
English:Home Button Takeover / On / Off / On, but not running / Not allowed on this TV / “This TV gives the Home button to its stock home screen. With takeover on, Home goes straight to UnitedU; the stock home screen stays installed. Press OK to open “Accessibility” in Native TV Settings and turn on “UnitedU Home Button”.” / “Takeover is on: Home returns to UnitedU. To undo, turn it off under “Accessibility” in Native TV Settings.” / “The TV shows it as on, but the service is not running (common after an update or reboot). Turn it off and on again under “Accessibility” in Native TV Settings, or restart the TV.” / “UnitedU was installed with a file manager, and this TV will not let its accessibility service be turned on; Settings has no unlock option. On a computer run: adb shell appops set com.uniteduone.launcher ACCESS_RESTRICTED_SETTINGS allow — or reinstall UnitedU with adb.” / “Not allowed on this TV. Unlock it from a computer as described on the left”

- [ ] **Step 3: 设置页**

`SettingsShell` 参数表在 `onChangeHome: () -> Unit,` 后加 `onHomeKeyTakeover: () -> Unit,`;`ShellPages.HOME` 分支改为:

```kotlin
            top.page == ShellPages.HOME -> {
                // 取代 M7 的 HomeSettingsCard 浮层(R74):左边当前默认桌面 + 说明,右边「在系统设置中更改」。
                // R162:第二颗「主页键接管」是条件行——不是默认桌面、或服务已开着才画;状态从系统设置回来(onResume 的 focusNonce++)重读。
                val ctx = LocalContext.current
                val home = rememberCurrentHome(revision, focusNonce)
                val status = remember(focusNonce) { HomeKeyState.status(ctx) }
                val enabled = status == HomeKeyStatus.ON || status == HomeKeyStatus.NOT_RUNNING
                val takeover = showHomeKeyCapsule(home.pkg == ctx.packageName, enabled)
                val items = buildList {
                    add(Capsule(SHELL_CHANGE_HOME, stringResource(R.string.home_settings_change_button), onClick = onChangeHome))
                    if (takeover) add(Capsule(SHELL_HOME_TAKEOVER, stringResource(R.string.homekey_capsule), onClick = onHomeKeyTakeover,
                        hint = stringResource(homeKeyStatusRes(status))))
                }
                ShellScaffold(
                    left = {
                        ShellTitle(settingsTitle + " · " + stringResource(R.string.settings_group_general), stringResource(R.string.home_settings_title)) {
                            Column(Modifier.width(360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                CurrentHomeRow(home)
                                Spacer(Modifier.height(14.dp))
                                // 光标在「主页键接管」上时说明换成它的(R131 同一个道理:说明跟着光标所在那一行)
                                val note = if (takeover && target == SHELL_HOME_TAKEOVER) stringResource(homeKeyNoteRes(status))
                                else stringResource(R.string.home_settings_note)
                                BasicText(note, style = shellBodyStyle.copy(textAlign = TextAlign.Center))
                            }
                        }
                    },
                    right = { CapsuleColumn(items, target, onFocus, focusNonce, covered) },
                )
            }
```
(`target` 是这一分支里已有的「栈顶帧的焦点 id」变量名——按文件里 HOME 分支现用的名字接;`LocalContext` 若未 import 则加。)

- [ ] **Step 4: 引导第 3 步**

`Onboarding(...)` 参数在 `onOpenHomeSettings: () -> Unit,` 后加 `onOpenHomeKey: () -> Unit,`,传给 `HomeStep`;`HomeStep` 改为:

```kotlin
private const val ONB_HOME_KEY = "homeKey"

/** 第 3 步:左边当前默认桌面 + 说明(不可聚焦),右边「去系统设置更改」「主页键接管」(条件,R162)「完成」。 */
@Composable
private fun HomeStep(eyebrow: String, revision: Int, nonce: Int, onOpenHomeSettings: () -> Unit, onOpenHomeKey: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val home = rememberCurrentHome(revision, nonce)
    val status = remember(nonce) { HomeKeyState.status(ctx) }
    val enabled = status == HomeKeyStatus.ON || status == HomeKeyStatus.NOT_RUNNING
    val takeover = showHomeKeyCapsule(home.pkg == ctx.packageName, enabled)
    // 已经是默认桌面时,初始焦点给「完成」;否则给「去系统设置更改」——这一步真正要做的事。
    var target by remember { mutableStateOf<String?>(if (home.pkg == ctx.packageName) ONB_DONE else ONB_CHANGE_HOME) }
    val items = buildList {
        add(Capsule(ONB_CHANGE_HOME, stringResource(R.string.home_settings_change_button), onClick = onOpenHomeSettings))
        if (takeover) add(Capsule(ONB_HOME_KEY, stringResource(R.string.homekey_capsule), onClick = onOpenHomeKey, hint = stringResource(homeKeyStatusRes(status))))
        add(Capsule(ONB_DONE, stringResource(R.string.onb_done), onClick = onFinish))
    }
    ShellScaffold(
        left = {
            ShellTitle(eyebrow, stringResource(R.string.onb_step3_title)) {
                Column(Modifier.width(INFO_BLOCK_W), horizontalAlignment = Alignment.CenterHorizontally) {
                    CurrentHomeRow(home)
                    Spacer(Modifier.height(14.dp))
                    ShellBody(stringResource(if (takeover && target == ONB_HOME_KEY) homeKeyNoteRes(status) else R.string.home_settings_note))
                }
            }
        },
        right = { CapsuleColumn(items, target, { target = it }, nonce, covered = false) },
    )
}
```

- [ ] **Step 5: MainActivity 接线**

`switchHome()` 旁边加:

```kotlin
    /**
     * R162:「主页键接管」胶囊 → 系统无障碍设置(候选链 + 弹回检测,回来时 onResume 的 focusNonce++ 让页面重读状态)。
     * 受限设置锁着时系统页上开关一拨就弹回、没有任何解释,不如直接提示(说明里有 adb 命令)。
     */
    private fun openHomeKeySettings() {
        if (HomeKeyState.status(this) == HomeKeyStatus.RESTRICTED) { toast(getString(R.string.toast_homekey_restricted)); return }
        openSystemPage(ACCESSIBILITY_SETTINGS_PAGES, R.string.toast_system_settings_unavailable)
    }
```
`SettingsShell(...)` 调用处在 `onChangeHome = …` 后加 `onHomeKeyTakeover = ::openHomeKeySettings,`;`Onboarding(...)` 调用处在 `onOpenHomeSettings = …` 后加 `onOpenHomeKey = { endOnboarding(); openHomeKeySettings() }`(同一条注释:先结束引导再跳系统页)。

- [ ] **Step 6: 构建、单测、模拟器看一眼、提交**

```bash
source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5554 shell am start -n com.uniteduone.launcher/.MainActivity   # 发两次(gtv 的坑)
```
模拟器(gtv,不是默认桌面):设置 → 通用 → 设置默认桌面 → 右边两颗,第二颗小字「已开启」(服务开着)/「未开启」(`settings put secure enabled_accessibility_services ""` 后);按它 → TvSettings 无障碍页。`appops set com.uniteduone.launcher ACCESS_RESTRICTED_SETTINGS deny` → 小字「这台电视不允许」,按下只 toast;测完 `allow`。`unitedu-tv`(UnitedU 是 HOME 角色但 HOME 解析到 tvlauncher → `rememberCurrentHome` 认 tvlauncher → 胶囊出现)。截图存 `docs/screenshots/r162/`(1080p `screencap -p`)。`pm clear` 走引导第 3 步看三颗。
```bash
git add -A app/src/main/java/com/uniteduone/launcher app/src/main/res docs/screenshots/r162
git commit -m "feat: 设置「默认桌面」页与引导第 3 步加「主页键接管」胶囊(R162 ④⑤)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: 文档同步、全量验证、电视回归

**Files:**
- Modify: `docs/design/settings-inventory.md`(「设置默认桌面」行 + 新小节「设置默认桌面」页)
- Modify: `docs/REVIEW-GUIDE.md`(§4 文件地图加 `HomeKeyService.kt` / `HomeKeyPolicy.kt` / `HomeKeyState.kt` / `SelfUpdate.kt`;§5 进程模型一句改成「除 `:homekey` 外同一进程;`:homekey` 不碰状态文件」;§6 已知限制加:开机前 1.5 s 原厂桌面、待机唤醒那一下 HOME 截不到、Android 13+ 侧载受限、Fire OS 不支持)
- Modify: `README.md`「设为默认桌面」一节加「主页键接管」小节(什么时候用、怎么开、受限时的 adb 命令、路 C `pm disable-user` 一行)
- Modify: `CLAUDE.md` 模拟器一节加一条:「测 HOME 截键用 `scripts/e2e/hwhome.sh`;`input keyevent 3` 不经过无障碍过滤器;`:homekey` 进程的日志 `logcat -s UnitedU | grep homekey`」
- Modify: `docs/WORKLOG.md`(本轮记录)、`docs/handoff-2026-10-02.md`(§2.1 改成「已做,待电视回归」)

- [ ] **Step 1: 写文档**(每处 1–5 行,内容取自 spec R162 与调研 §2.2)
- [ ] **Step 2: 全量**:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease` 全过;`unitedu-gtv` 从干净状态(`pm uninstall` 后 `adb install`)走一遍:开服务 → HOME → UnitedU;开机;屏保放行;关服务 → 一切还原(HOME 回 launcherx)。
- [ ] **Step 3: 电视回归**:`scripts/tv-install.sh app/build/outputs/apk/release/app-release.apk`(A95L 是默认桌面:胶囊不出现、服务不存在于系统无障碍页也无妨——它在;不开它,行为零变化)。装完 `dumpsys window | grep mCurrentFocus` 是首页即可,不按键。
- [ ] **Step 4: 提交文档**

```bash
git add docs README.md CLAUDE.md
git commit -m "docs: 主页键接管(R162)——设置清单、评审指南、README、模拟器测法

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## 自查(写完后对着 spec R162 过一遍)

- ①服务双路、独立进程、只在不是默认桌面时生效、开机拉一次、长按 = 短按 → Task 1 / 3。
- ②`MAIN + HOME` + 显式组件 → Task 3 `launch()`、Task 4 点名自己。
- ③屏保放行 → Task 1 `onHomeKey(dreaming)` + `nextDreaming`,Task 3 验证第③项。
- ④条件胶囊、四种状态、无自有开关、心跳判「在运行」→ Task 2 / 6。
- ⑤受限设置识别 + adb 命令 → Task 2 `isRestricted`、Task 6 文案与 toast。
- ⑥自我更新会话 API → Task 5。
- ⑦R151 认接管 → Task 4。
- ⑧路 C 只写 README → Task 7。
- 类型一致:`HomeKeyAction` / `HomeKeyStatus` / `HomeKeyHeartbeat` / `showHomeKeyCapsule` / `homeKeyStatusRes` / `homeKeyNoteRes` / `SHELL_HOME_TAKEOVER` / `ACCESSIBILITY_SETTINGS_PAGES` / `SelfUpdate.install` 在各任务里名字相同。
