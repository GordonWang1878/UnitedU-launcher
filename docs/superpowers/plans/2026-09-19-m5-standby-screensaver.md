# M5 待机与屏保 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把今天「一进待机、图库有图就叠轮播」改成 spec 的先后两段互斥状态——正常 →(待机时长)→ 待机 →(屏保启动,且图库非空)→ 自定义屏保,任意键回正常;设置页「待机与屏保」组六行;屏保图库长按删图;`UnitedUDream` 系统屏保与桌面共用播放器与播放进度。

**Architecture:** 状态机的两个布尔量打包成一个 `StandbyFlags` 值(构造函数钉死「屏保 ⇒ 待机」,`idle` / `screensaverActive` 仍以布尔量读,原有消费者不改);纯函数 `standbyPlan` 给出两个时刻,MainActivity 的计时效果照它写状态、到点在 IO 线程查图库。轮播归进程级单例 `ScreensaverPlayer`(扫描、下标、唯一一份换图计时、引用计数),桌面屏保层 `Screensaver` 与系统屏保 `UnitedUDream` 都只读它,所以续播是自然结果。设置页 STANDBY 组加四行(`ControlRow.noteRes` 画行内提示);图库删图沿用 `MainActivity.dispatchKeyEvent` 的 600 ms 长按识别 + 现成 `ConfirmDialog`;`UnitedUDream` 自己当 `LifecycleOwner` + `SavedStateRegistryOwner` 托管 `ComposeView`,主题色经新的 `rememberThemeColors` 与桌面走同一条路。

**Tech Stack:** Kotlin, Jetpack Compose(BOM 2024.10.01 = 1.7.x), `androidx.tv:tv-material:1.0.0`, kotlinx-coroutines `StateFlow`, `androidx.lifecycle` 2.8.3 / `androidx.savedstate` 1.2.1(都已经经 activity 1.9.3 的 api 依赖在编译类路径上,**不加新依赖**), `android.service.dreams.DreamService`, JUnit 4, Android 14 TV AVD `unitedu-tv`.

**Spec:** `docs/superpowers/specs/2026-09-19-m5-standby-screensaver-design.md`(唯一权威;术语与状态模型以 `docs/DESIGN-unitedu-open-source.md` §4 为准;本 plan 的数字全部来自 spec)。spec 里的歧义与本 plan 的裁定见文末「Pre-flight」。

**基线:** main `5b0c85f`(spec 在 `7126406` 提交,其后四笔只改文档,源码与 spec 定稿时一致)。工作分支 `m5-standby`,worktree `.claude/worktrees/m5-standby`(M7 / M8 惯例):

```bash
cd /Users/gordonwang/GitHub/UnitedU-launcher
git worktree add .claude/worktrees/m5-standby -b m5-standby main
cd .claude/worktrees/m5-standby
```

以下所有路径与命令都相对 worktree 根目录。

## Global Constraints

- **铁律 1–7**(项目 `CLAUDE.md`「改这份界面前必须知道的七条」)全程有效:不新增任何可滚动容器;焦点落地只信控件自报 `isFocused`;每个新浮层自己负责焦点恢复(铁律 3 的表要补行);目标与当前位置分开、`ON_PAUSE` 起冻结;`if (X) return@LaunchedEffect` 的 `X` 必须在 key 里;不写只有一条路清掉的布尔闩。
- **首页焦点账本不动**:`HomeScreen` 的看门狗、还原效果、`report()`、`tgtRow/tgtIdx/tgtGear` 一行不改;本里程碑只改它的三个淡出量与 `HeroClock` 调用。
- **长按识别只在 `MainActivity.dispatchKeyEvent`**,阈值仍是 `LONG_PRESS_MS = 600L`;图库那一支与首页那一支互斥(`homeBare` 要求 `!overlayOpen`,图库开着时 `pickerTarget != null`)。
- **字族**:所有新文字 `fontFamily = Theme.Sans`。
- **命令**:构建 + 单测一律 `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`;单跑某个测试类加 `--tests 'com.uniteduone.launcher.XxxTest'`。
- **模拟器**:
  - **所有 adb 命令都带 `-s emulator-5554`**——Gordon 的 A95L(序列号形如 `adb-…-1F8N2S (2)._adb-tls-connect._tcp`)常常同时在线,**绝不对它装包、改设置、发按键**。`adb devices` 看不到 `emulator-5554` 就按 CLAUDE.md「模拟器」一节启动(`emulator … &` 后台跑)。
  - 拉起一律 `adb -s emulator-5554 shell am start -n com.uniteduone.launcher/.MainActivity`,**不用 HOME 键**(原厂 Google TV 桌面会抢);弹「Default Home」对话框就 `input keyevent 4` 两次,截图前 `dumpsys window | grep mCurrentFocus` 确认前台是 UnitedU。
  - 设置文件在 `/sdcard/Android/data/com.uniteduone.launcher/files/settings.json`,改字段一律用下面夹具里的 `/tmp/m5-setjson.sh`(pull → 改 → push → force-stop → `am start -n`)。
  - 注入按键之间留 ≥ 0.4 s(夹具脚本用 0.6 s);uiautomator 不报全透明节点,真待机 / 屏保时 `FOCUS: <none>` 是正常的;TV 设置卡的 content-desc 也叫「Settings」,按确定键之前先用 `/tmp/m5-focus.py` 断言焦点文字 **和 bounds**。
  - 文中的 `sleep N` 是等待时长;若 harness 不许前台 `sleep`,用它允许的方式(后台命令 / Monitor)等同样久。时间线脚本单次约 140 s,Bash 调用给 `timeout: 200000`。
  - 截图可以直接用 Read 工具看 PNG;像素判据用夹具里的 `/tmp/m5-probe.py`。
  - 系统屏保的两个安全设置键(`screensaver_components` / `screensaver_enabled`)**只在模拟器上改**,测完还原;真机上 Gordon 自己在系统设置里开(A95L 清单里写明)。
- **提交**:每个任务结束一个 commit,两个 `-m`:主题行英文 conventional(`feat(m5): …` / `docs(m5): …`),第二个 `-m` 是 `Co-Authored-By: Claude <实际模型> <noreply@anthropic.com>`——**把 `<实际模型>` 换成执行者自己的模型名**(如 `Sonnet 5`;2026-09-18 R8:真实归属优先)。
- **范围**:二级界面里只动设置页 STANDBY 组(含 `RowFrame` 的行内提示)与屏保图库(`ScreensaverPoolViewer` / `PickerGrid` 的可选回调);其余二级界面观感一律不动。spec §8「不做」:随机顺序、转场选项、天气 / 通知、电视上加图、检测系统当前选的屏保、屏保期间的媒体播放。

## 模拟器夹具(Task 2 第一次用,Task 7 还原)

在 Task 2 的模拟器步骤开头**只跑一次**。脚本全部 `#!/bin/bash`(本机交互 shell 是 zsh,脚本里的变量拆分按 bash 来)。

- [ ] **F1: 写六个辅助脚本**

```bash
cat > /tmp/m5-key.sh <<'EOF'
#!/bin/bash
# /tmp/m5-key.sh KEY [KEY...]:逐个发按键(KEYCODE_ 前缀省略),间隔 0.6 s,给焦点与动画落地
for k in "$@"; do adb -s emulator-5554 shell input keyevent "KEYCODE_$k"; sleep 0.6; done
EOF

cat > /tmp/m5-shot.sh <<'EOF'
#!/bin/bash
# /tmp/m5-shot.sh NAME:截模拟器屏到 /tmp/m5-NAME.png
adb -s emulator-5554 exec-out screencap -p > "/tmp/m5-$1.png" && echo "/tmp/m5-$1.png"
EOF

cat > /tmp/m5-setjson.sh <<'EOF'
#!/bin/bash
# /tmp/m5-setjson.sh KEY VALUE [KEY VALUE ...]:改模拟器 settings.json 的字段(VALUE 按 JSON 字面量写,字符串带引号),
# 然后 force-stop + am start -n 冷启动。文件里没有的键插到最前面(M5 之前写的文件没有两个新键)。
set -e
F=/sdcard/Android/data/com.uniteduone.launcher/files/settings.json
adb -s emulator-5554 pull "$F" /tmp/m5-settings.json >/dev/null
python3 - "$@" <<'PY'
import re, sys
p = '/tmp/m5-settings.json'
s = open(p).read()
args = sys.argv[1:]
for k, v in zip(args[0::2], args[1::2]):
    s2, n = re.subn(r'("%s"\s*:\s*)[^,}\n]+' % re.escape(k), lambda m: m.group(1) + v, s)
    s = s2 if n else s.replace('{', '{\n  "%s": %s,' % (k, v), 1)
open(p, 'w').write(s)
PY
adb -s emulator-5554 push /tmp/m5-settings.json "$F" >/dev/null
adb -s emulator-5554 shell am force-stop com.uniteduone.launcher
adb -s emulator-5554 shell am start -n com.uniteduone.launcher/.MainActivity >/dev/null
echo "settings: $*"
EOF

cat > /tmp/m5-gallery.sh <<'EOF'
#!/bin/bash
# /tmp/m5-gallery.sh empty|full|reset:把三张测试图移出 / 移回屏保图库(移到 files/m5-hold/test-screensavers/,不删);
# reset = 重新推送三张(删图测试之后用)。
D=/sdcard/Android/data/com.uniteduone.launcher/files
case "$1" in
  empty) adb -s emulator-5554 shell "mv $D/library/screensavers/m5-test-* $D/m5-hold/test-screensavers/ 2>/dev/null; true" ;;
  full)  adb -s emulator-5554 shell "mv $D/m5-hold/test-screensavers/m5-test-* $D/library/screensavers/ 2>/dev/null; true" ;;
  reset) for i in 0 1 2; do adb -s emulator-5554 push "/tmp/m5-ss-$i.jpg" "$D/library/screensavers/m5-test-$i.jpg" >/dev/null; done ;;
esac
echo "gallery: $(adb -s emulator-5554 shell ls $D/library/screensavers/ | tr '\n' ' ')"
EOF

cat > /tmp/m5-probe.py <<'EOF'
#!/usr/bin/env python3
# /tmp/m5-probe.py SHOT.png [REF.png] —— M5 待机 / 屏保的像素判据(1920×1080 截图)
#   photo=    (1800,400) 是哪张测试图(red/green/blue);none = 不是屏保照片。首页上这一点只会是壁纸。
#   under=    (1000,850) 同上。正常时这一点压在第一行卡片上;屏保时卡片淡出,这里也该是照片色。
#   clockMax  大字时钟框(x 116–700, y 300–520)里最亮像素的亮度:时钟在 ≥ 140(三张测试图亮度都 < 100),全黑待机 ≤ 20。
#   cardsDiff 卡片行区域(x 116–1804, y 760–900)与 REF 的平均逐像素差;只有给了 REF 才打印。
import sys
from PIL import Image
TEST = {'red': (170, 40, 40), 'green': (40, 130, 70), 'blue': (40, 70, 170)}
def load(p): return Image.open(p).convert('RGB')
def lum(c): return (299 * c[0] + 587 * c[1] + 114 * c[2]) // 1000
def which(c): return next((k for k, v in TEST.items() if all(abs(a - b) <= 40 for a, b in zip(c, v))), 'none')
im = load(sys.argv[1])
p, u = im.getpixel((1800, 400)), im.getpixel((1000, 850))
clock = max(lum(im.getpixel((x, y))) for x in range(116, 700, 4) for y in range(300, 520, 4))
out = f'photo={which(p)} under={which(u)} pixel={p} clockMax={clock}'
if len(sys.argv) > 2:
    ref = load(sys.argv[2])
    pts = [(x, y) for x in range(116, 1804, 8) for y in range(760, 900, 8)]
    d = sum(sum(abs(a - b) for a, b in zip(im.getpixel(q), ref.getpixel(q))) / 3 for q in pts) / len(pts)
    out += f' cardsDiff={d:.1f}'
print(out)
EOF

cat > /tmp/m5-focus.py <<'EOF'
#!/usr/bin/env python3
# /tmp/m5-focus.py [--all]:打印模拟器上持有焦点的节点 bounds,以及落在它范围内的全部文字 / content-desc
# (设置页的行、确认框按钮、缩略图的文字都在子节点上)。--all 另外按文档顺序打印整屏所有文字。
# uiautomator 不报全透明节点:真待机 / 屏保时打印 FOCUS: <none> 是正常的(CLAUDE.md 模拟器坑)。
import html, re, subprocess, sys
ADB = ['adb', '-s', 'emulator-5554']
subprocess.run(ADB + ['shell', 'rm', '-f', '/sdcard/m5-ui.xml'], capture_output=True)
subprocess.run(ADB + ['shell', 'uiautomator', 'dump', '/sdcard/m5-ui.xml'], capture_output=True)
xml = subprocess.run(ADB + ['shell', 'cat', '/sdcard/m5-ui.xml'], capture_output=True, text=True).stdout
if '<node' not in xml:
    sys.exit('DUMP FAILED(还有动画在跑?过一秒再跑)')
nodes = re.findall(r'<node [^>]*>', xml)
def attr(n, k):
    m = re.search(' ' + k + r'="([^"]*)"', n)
    return html.unescape(m.group(1)) if m else ''
def box(n): return tuple(int(v) for v in re.findall(r'-?\d+', attr(n, 'bounds')))
def label(n): return attr(n, 'text') or attr(n, 'content-desc')
focused = [n for n in nodes if attr(n, 'focused') == 'true']
if not focused:
    print('FOCUS: <none>')
for f in focused:
    x0, y0, x1, y1 = box(f)
    inside = [label(n) for n in nodes
              if label(n) and x0 <= box(n)[0] and box(n)[2] <= x1 and y0 <= box(n)[1] and box(n)[3] <= y1]
    print('FOCUS:', attr(f, 'bounds'), '|', ' / '.join(inside))
if '--all' in sys.argv:
    print('TEXTS:', ' | '.join(label(n) for n in nodes if label(n)))
EOF

cat > /tmp/m5-timeline.sh <<'EOF'
#!/bin/bash
# /tmp/m5-timeline.sh TAG [T1 T2]:从现在起在 20 s、T1、T2 秒各截一张并跑 m5-probe(后两张以 20 s 那张为 REF)。
# 默认 T1=75(待机 1 分之后)、T2=135(再 1 分之后)。调用前:m5-setjson.sh 设好值(冷启动)+ 把焦点摆好——
# 之后不要再发任何按键,计时从最后一次按键 / onResume 算。
TAG=$1; T1=${2:-75}; T2=${3:-135}
t0=$(date +%s)
wait_until() { while [ $(( $(date +%s) - t0 )) -lt "$1" ]; do sleep 1; done; }
wait_until 20;    /tmp/m5-shot.sh "$TAG-a" >/dev/null; echo "20s   $(python3 /tmp/m5-probe.py /tmp/m5-$TAG-a.png)"
wait_until "$T1"; /tmp/m5-shot.sh "$TAG-b" >/dev/null; echo "${T1}s  $(python3 /tmp/m5-probe.py /tmp/m5-$TAG-b.png /tmp/m5-$TAG-a.png)"
wait_until "$T2"; /tmp/m5-shot.sh "$TAG-c" >/dev/null; echo "${T2}s $(python3 /tmp/m5-probe.py /tmp/m5-$TAG-c.png /tmp/m5-$TAG-a.png)"
EOF

chmod +x /tmp/m5-key.sh /tmp/m5-shot.sh /tmp/m5-setjson.sh /tmp/m5-gallery.sh /tmp/m5-probe.py /tmp/m5-focus.py /tmp/m5-timeline.sh
```

- [ ] **F2: 备份、推测试图、定主题**

```bash
source scripts/env.sh
adb devices                                   # 必须看到 emulator-5554
D=/sdcard/Android/data/com.uniteduone.launcher/files
adb -s emulator-5554 shell "mkdir -p $D/m5-hold/orig-screensavers $D/m5-hold/test-screensavers $D/library/screensavers"
adb -s emulator-5554 shell "cp $D/settings.json $D/m5-hold/settings.json.orig"
adb -s emulator-5554 shell "mv $D/library/screensavers/* $D/m5-hold/orig-screensavers/ 2>/dev/null; true"
adb -s emulator-5554 shell settings get secure long_press_timeout > /tmp/m5-orig-long-press.txt
python3 - <<'PY'
from PIL import Image
# 三张纯色测试图:亮度都 < 100,时钟(Material 紫,亮度约 201)在上面一眼可辨;文件名排序 = red → green → blue
for i, rgb in enumerate([(170, 40, 40), (40, 130, 70), (40, 70, 170)]):
    Image.new('RGB', (1920, 1080), rgb).save(f'/tmp/m5-ss-{i}.jpg', quality=92)
PY
/tmp/m5-gallery.sh reset
/tmp/m5-setjson.sh themePresetId '"material"' followWallpaperColor false showDate true
sleep 5
adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus
/tmp/m5-shot.sh fixture; python3 /tmp/m5-probe.py /tmp/m5-fixture.png
```

Expected:`gallery: m5-test-0.jpg m5-test-1.jpg m5-test-2.jpg`;`mCurrentFocus` 是 `com.uniteduone.launcher/…MainActivity`;probe 打印 `photo=none under=none … clockMax=` ≥ 140。**若 `photo` 不是 `none`**(壁纸恰好接近某个测试色),改 `m5-probe.py` 与上面生成脚本里的三种颜色(保持亮度 < 100)后重推。

---

### Task 1: 两个新设置键 + 纯函数 `standbyPlan` / `StandbyFlags`(TDD)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Settings.kt`(`data class Settings` 的 `idleContent` 字段之后;`VALID_LANGUAGES` 之后;`snapIdleAfterMs` 之后;`parseSettings` 与 `toJson` 里 `idleContent` 那一行之后)
- Create: `app/src/main/java/com/uniteduone/launcher/StandbySchedule.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/StandbyScheduleTest.kt`(新建)、`app/src/test/java/com/uniteduone/launcher/SettingsTest.kt`(末尾加 5 条)

**Interfaces:**
- Produces:
  - `Settings.screensaverAfterMs: Long = 300_000L`、`Settings.screensaverIntervalMs: Long = 30_000L`(写盘键同名)
  - `internal val VALID_SCREENSAVER_AFTER_MS = longArrayOf(0L, 60_000L, 300_000L, 600_000L, 1_800_000L)`
  - `internal val VALID_SCREENSAVER_INTERVAL_MS = longArrayOf(30_000L, 60_000L, 300_000L)`
  - `data class StandbyPlan(val standbyAt: Long?, val screensaverAt: Long?)`
  - `fun standbyPlan(idleAfterMs: Long, screensaverAfterMs: Long): StandbyPlan`
  - `data class StandbyFlags(val idle: Boolean, val screensaverActive: Boolean)`,`StandbyFlags.NORMAL / STANDBY / SCREENSAVER`;构造 `StandbyFlags(idle = false, screensaverActive = true)` 抛 `IllegalArgumentException`
- Consumes: 无。

- [ ] **Step 1: 写失败的测试**

`StandbyScheduleTest.kt`:
```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 spec §1.1 的四种组合 + §1 表的不变量「屏保 ⇒ 待机」。两个时刻都从最后一次按键起算。 */
class StandbyScheduleTest {
    @Test fun standbyThenScreensaver() {
        // 默认:3 分待机,再 5 分屏保 → 距最后一次按键 3 分、8 分
        assertEquals(StandbyPlan(180_000L, 480_000L), standbyPlan(180_000L, 300_000L))
    }

    @Test fun standbyOffCountsScreensaverFromLastKey() {
        assertEquals(StandbyPlan(null, 60_000L), standbyPlan(0L, 60_000L))
    }

    @Test fun screensaverOffLeavesStandbyAlone() {
        assertEquals(StandbyPlan(60_000L, null), standbyPlan(60_000L, 0L))
    }

    @Test fun bothOffMeansNothingHappens() {
        assertEquals(StandbyPlan(null, null), standbyPlan(0L, 0L))
    }

    @Test fun flagConstantsMatchTheSpecTable() {
        assertFalse(StandbyFlags.NORMAL.idle); assertFalse(StandbyFlags.NORMAL.screensaverActive)
        assertTrue(StandbyFlags.STANDBY.idle); assertFalse(StandbyFlags.STANDBY.screensaverActive)
        assertTrue(StandbyFlags.SCREENSAVER.idle); assertTrue(StandbyFlags.SCREENSAVER.screensaverActive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun screensaverWithoutStandbyIsRejected() {
        StandbyFlags(idle = false, screensaverActive = true)
    }
}
```

`SettingsTest.kt` 最后一个 `}` 之前加:
```kotlin
    @Test fun screensaverFieldsDefaultWhenAbsent() {
        // 不需要迁移(spec §0):旧 settings.json 没有这两个键 → 屏保启动 5 分、轮播 30 秒
        val s = parseSettings("""{"idleAfterMs": 180000}""")
        assertEquals(300_000L, s.screensaverAfterMs)
        assertEquals(30_000L, s.screensaverIntervalMs)
        assertEquals(300_000L, Settings().screensaverAfterMs)
        assertEquals(30_000L, Settings().screensaverIntervalMs)
    }

    @Test fun screensaverAfterMsMustBeOneOfAllowedValues() {
        assertEquals(300_000L, parseSettings("""{"screensaverAfterMs": 12345}""").screensaverAfterMs)
        assertEquals(300_000L, parseSettings("""{"screensaverAfterMs": "x"}""").screensaverAfterMs)
        for (v in listOf(0L, 60_000L, 300_000L, 600_000L, 1_800_000L)) {
            assertEquals(v, parseSettings("""{"screensaverAfterMs": $v}""").screensaverAfterMs)
        }
    }

    @Test fun screensaverIntervalMsMustBeOneOfAllowedValues() {
        assertEquals(30_000L, parseSettings("""{"screensaverIntervalMs": 45000}""").screensaverIntervalMs)
        assertEquals(30_000L, parseSettings("""{"screensaverIntervalMs": 0}""").screensaverIntervalMs)
        for (v in listOf(30_000L, 60_000L, 300_000L)) {
            assertEquals(v, parseSettings("""{"screensaverIntervalMs": $v}""").screensaverIntervalMs)
        }
    }

    @Test fun screensaverFieldsRoundTrip() {
        val s = Settings(screensaverAfterMs = 0L, screensaverIntervalMs = 300_000L)
        assertTrue(s.toJson().contains("\"screensaverAfterMs\": 0"))
        assertTrue(s.toJson().contains("\"screensaverIntervalMs\": 300000"))
        assertEquals(s, parseSettings(s.toJson()))
    }

    @Test fun restoredDefaultsResetsScreensaverFields() {
        val r = restoredDefaults(Settings(screensaverAfterMs = 1_800_000L, screensaverIntervalMs = 60_000L), 1L)
        assertEquals(300_000L, r.screensaverAfterMs)
        assertEquals(30_000L, r.screensaverIntervalMs)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.StandbyScheduleTest' --tests 'com.uniteduone.launcher.SettingsTest'`
Expected: 编译失败,`Unresolved reference: standbyPlan` / `StandbyPlan` / `StandbyFlags` / `screensaverAfterMs`。

- [ ] **Step 3: `StandbySchedule.kt`**

```kotlin
package com.uniteduone.launcher

/**
 * M5 待机与屏保的计时(spec §1.1)。纯 Kotlin、不碰 Android,JVM 单测见 StandbyScheduleTest。
 * 两个时刻都从「最后一次按键」起算:[standbyAt] 进待机,[screensaverAt] 进自定义屏保;null = 这一步不发生。
 * 屏保按「进入待机后再过多久」配置,待机时长为「关」时从最后一次按键算(spec §0「屏保启动」)。
 * 「到点时图库是不是空的」不在这里判:那要读盘,由 MainActivity 在 IO 线程到点再查。
 */
data class StandbyPlan(val standbyAt: Long?, val screensaverAt: Long?)

fun standbyPlan(idleAfterMs: Long, screensaverAfterMs: Long): StandbyPlan {
    val standbyAt = idleAfterMs.takeIf { it > 0L }
    val screensaverAt = if (screensaverAfterMs <= 0L) null else (standbyAt ?: 0L) + screensaverAfterMs
    return StandbyPlan(standbyAt, screensaverAt)
}

/**
 * spec §1 表的三个合法状态。MainActivity 只持有**一个**这样的值:两个布尔量打包写入,一次写完、没有
 * 「只写了一半」的中间态;读者照旧按布尔量读(`idle` 原有的五个消费者不用改,spec §1「不改成枚举」的理由)。
 * 不变量「屏保 ⇒ 待机」由构造函数钉死:屏保层、黑层、唤醒吞键都默认它成立(spec §1.4),
 * 漏写一半的状态在这里当场抛,而不是在电视上表现成「照片开着、按键却直接落到卡片上」。
 */
data class StandbyFlags(val idle: Boolean, val screensaverActive: Boolean) {
    init {
        require(idle || !screensaverActive) { "screensaverActive requires idle" }
    }

    companion object {
        val NORMAL = StandbyFlags(idle = false, screensaverActive = false)
        val STANDBY = StandbyFlags(idle = true, screensaverActive = false)
        val SCREENSAVER = StandbyFlags(idle = true, screensaverActive = true)
    }
}
```

- [ ] **Step 4: `Settings.kt` 加两个字段**

`data class Settings` 里
```kotlin
    val idleContent: IdleContent = IdleContent.CLOCK_ONLY,
```
之后插入:
```kotlin
    /**
     * 进入待机后再过多久进自定义屏保(M5 spec §0「屏保启动」);0 = 关。待机时长为「关」时改从最后一次按键算
     * (两个时刻的换算在 [standbyPlan])。合法值见 [VALID_SCREENSAVER_AFTER_MS];旧文件没有这个键 → 默认 5 分。
     */
    val screensaverAfterMs: Long = 300_000L,
    /** 屏保换图间隔(「屏保轮播设置」,桌面与系统屏保共用)。合法值见 [VALID_SCREENSAVER_INTERVAL_MS]。 */
    val screensaverIntervalMs: Long = 30_000L,
```

`internal val VALID_LANGUAGES = listOf("system", "zh-CN", "zh-TW", "en")` 之后插入:
```kotlin
// M5(spec §3):「屏保启动」「屏保轮播设置」两行的唯一合法取值,同样一份表两处读(夹取 + 分段控件的档位顺序)。
internal val VALID_SCREENSAVER_AFTER_MS = longArrayOf(0L, 60_000L, 300_000L, 600_000L, 1_800_000L)
internal val VALID_SCREENSAVER_INTERVAL_MS = longArrayOf(30_000L, 60_000L, 300_000L)
```

`snapIdleAfterMs` 函数之后插入:
```kotlin
/** 不在表里(手改的 12345、解析不出数字)→ 默认 5 分(spec §3「解析时不在表里就夹回默认」)。 */
private fun snapScreensaverAfterMs(v: Long?): Long =
    if (v != null && VALID_SCREENSAVER_AFTER_MS.contains(v)) v else 300_000L

/** 不在表里 → 默认 30 秒。 */
private fun snapScreensaverIntervalMs(v: Long?): Long =
    if (v != null && VALID_SCREENSAVER_INTERVAL_MS.contains(v)) v else 30_000L
```

`parseSettings` 里 `idleContent = …` 那个表达式(以 `?: d.idleContent,` 结尾)之后插入:
```kotlin
            screensaverAfterMs = snapScreensaverAfterMs(extractLong(json, "screensaverAfterMs")),
            screensaverIntervalMs = snapScreensaverIntervalMs(extractLong(json, "screensaverIntervalMs")),
```

`toJson` 里
```kotlin
        append("  \"idleContent\": \"${idleContent.name}\",\n")
```
之后插入:
```kotlin
        append("  \"screensaverAfterMs\": $screensaverAfterMs,\n")
        append("  \"screensaverIntervalMs\": $screensaverIntervalMs,\n")
```
(`restoredDefaults` 不用改:它回落到 `Settings()` 的构造默认值,「恢复默认」自然覆盖两个新键。)

- [ ] **Step 5: 跑测试确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL;`StandbyScheduleTest` 6 条、`SettingsTest` 新增 5 条全过,原有测试不变(总数 = 基线 178 + 11)。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/Settings.kt app/src/main/java/com/uniteduone/launcher/StandbySchedule.kt app/src/test/java/com/uniteduone/launcher/StandbyScheduleTest.kt app/src/test/java/com/uniteduone/launcher/SettingsTest.kt
git commit -m "feat(m5): screensaver settings keys and pure standby schedule (standbyPlan, StandbyFlags)" -m "Co-Authored-By: Claude <实际模型> <noreply@anthropic.com>"
```

---

### Task 2: 共用播放器 `ScreensaverPlayer` + `Screensaver.kt`(下标纯函数 TDD)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/ScreensaverPlayer.kt`
- Create: `app/src/main/java/com/uniteduone/launcher/Screensaver.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt`(删掉文件底部从 `private val SCREENSAVER_IMAGE_EXTS` 到 `migrateOldScreensaver` 结束的整段;删 8 个随之无用的 import)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(`Screensaver(this@MainActivity, idle)` 那一处,约 416 行)
- Modify: `app/src/main/java/com/uniteduone/launcher/Theme.kt`(`ScreensaverIntervalMs` 的 KDoc)
- Test: `app/src/test/java/com/uniteduone/launcher/ScreensaverPlayerTest.kt`(新建)

**Interfaces:**
- Produces:
  - `internal fun nextIndex(i: Int, size: Int): Int`、`internal fun clampIndex(i: Int, size: Int): Int`
  - `internal fun scanScreensaverLibrary(ctx: Context): List<File>`(IO 线程;外置没挂 → 空表)、`internal fun hasScreensaverImages(ctx: Context): Boolean`
  - `object ScreensaverPlayer { val files: StateFlow<List<File>>; val index: StateFlow<Int>; fun attach(ctx: Context, intervalMs: Long); fun detach(); fun rescan(ctx: Context) }`
  - `@Composable fun Screensaver(active: Boolean, intervalMs: Long)`(桌面屏保层:attach/detach 成对 + 1200/400 ms 淡入淡出)
  - `@Composable fun ScreensaverContent(intervalMs: Long, modifier: Modifier = Modifier)`(轮播层本体,不画时钟;桌面与系统屏保共用)
- Consumes: `Settings.screensaverIntervalMs`(Task 1)。
- **行为不变**:本任务只搬家 + 间隔改读设置。触发条件仍是 `idle`、仍受「不淡出」守卫——Task 3 才换成 `screensaverActive`。

- [ ] **Step 1: 写失败的测试**

`ScreensaverPlayerTest.kt`:
```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** 播放器的两个下标纯函数(spec §2)。播放器本体(协程 + 主线程)不在 JVM 上测,放模拟器。 */
class ScreensaverPlayerTest {
    @Test fun nextIndexWrapsAround() {
        assertEquals(1, nextIndex(0, 3))
        assertEquals(2, nextIndex(1, 3))
        assertEquals(0, nextIndex(2, 3))
    }

    @Test fun nextIndexOfSingleImageStaysPut() {
        assertEquals(0, nextIndex(0, 1))
    }

    @Test fun nextIndexOfEmptyGalleryIsZero() {
        assertEquals(0, nextIndex(0, 0))
        assertEquals(0, nextIndex(5, 0))
    }

    @Test fun nextIndexClampsAStaleIndexFirst() {
        // 删图后还没来得及夹回的下标:先夹到末张,再往后一张 = 回到第 0 张;负数先夹到 0
        assertEquals(0, nextIndex(7, 3))
        assertEquals(1, nextIndex(-4, 3))
    }

    @Test fun clampIndexPullsBackIntoRange() {
        assertEquals(2, clampIndex(5, 3))   // 删掉了末尾几张:落在新的末张
        assertEquals(1, clampIndex(1, 3))   // 范围内不动
        assertEquals(0, clampIndex(-1, 3))
        assertEquals(0, clampIndex(3, 0))   // 删空
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.ScreensaverPlayerTest'`
Expected: 编译失败,`Unresolved reference: nextIndex` / `clampIndex`。

- [ ] **Step 3: `ScreensaverPlayer.kt`**

```kotlin
package com.uniteduone.launcher

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val SCREENSAVER_IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

/** 下一张(spec §2):(i + 1) % size;空图库恒为 0。先夹回范围,过期的下标也不会越界。 */
internal fun nextIndex(i: Int, size: Int): Int = if (size <= 0) 0 else (clampIndex(i, size) + 1) % size

/** 图被删 / 重扫之后把下标夹回 [0, size − 1];空图库恒为 0。 */
internal fun clampIndex(i: Int, size: Int): Int = if (size <= 0) 0 else i.coerceIn(0, size - 1)

/**
 * 屏保图库的唯一扫描规则(spec §2「与今天相同」):library/screensavers/ 下 jpg / jpeg / png / webp,按文件名排序。
 * 播放器、状态机「到点查图库非空」、屏保按钮、设置页计数四处都读它,口径一致。
 * 扫描前先把旧版单张 screensaver.jpg/png 迁进图库(原来在 HomeScreen 的待机重扫里,spec §2 迁到这里)。
 * 外置存储没挂(`baseOrNull == null`)时按空图库处理(spec §9):不走 `Paths.base` 的 internal 回落——
 * 那里必然没有图,`screensaverLibrary()` 还会在 internal 凭空建一层目录。**必须在 IO 线程调用。**
 */
internal fun scanScreensaverLibrary(ctx: Context): List<File> {
    if (Paths.baseOrNull(ctx) == null) return emptyList()
    migrateOldScreensaver(ctx)
    return Paths.screensaverLibrary(ctx).listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in SCREENSAVER_IMAGE_EXTS }
        ?.sortedBy { it.name }
        ?: emptyList()
}

/** 状态机与屏保按钮的「图库非空」判据(spec §1.1 / §1.3)。**必须在 IO 线程调用。** */
internal fun hasScreensaverImages(ctx: Context): Boolean = scanScreensaverLibrary(ctx).isNotEmpty()

/** 把旧版单张 screensaver.jpg/png 迁移到图库目录。只在图库为空时迁移一次。 */
private fun migrateOldScreensaver(ctx: Context) {
    val dir = Paths.screensaverLibrary(ctx)
    if (dir.listFiles()?.any { it.isFile } == true) return
    for (old in listOf(Paths.screensaver(ctx), Paths.screensaverPng(ctx))) {
        if (old.exists()) {
            old.renameTo(File(dir, old.name))
            break
        }
    }
}

/**
 * 屏保播放器(spec §2):进程级单例,桌面屏保层([Screensaver])与系统屏保([UnitedUDream])都只读它。
 *
 * - **换图计时只此一份**(一个 `Dispatchers.Main` 协程):两处同时在场也不会双倍推进;
 *   两处都 attach 时间隔取最后一次 attach 传入的值(两处读同一份设置,正常情况下相等)。
 * - **引用计数**:第一个 [attach] 在 IO 线程重扫图库、启动计时;最后一个 [detach] 停计时,
 *   [index] 与 [files] **保留**——下次 attach 从同一张接着播。「系统屏保接桌面屏保的班」靠的就是这一条。
 * - [attach] / [detach] 只在主线程调(Compose 效果与 DreamService 回调都在主线程),`refs` 不加锁。
 */
object ScreensaverPlayer {
    private val _files = MutableStateFlow<List<File>>(emptyList())
    private val _index = MutableStateFlow(0)
    val files: StateFlow<List<File>> = _files.asStateFlow()
    val index: StateFlow<Int> = _index.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refs = 0
    private var ticker: Job? = null
    private var intervalNow = Theme.ScreensaverIntervalMs

    fun attach(ctx: Context, intervalMs: Long) {
        intervalNow = intervalMs
        refs++
        if (refs > 1) return
        val app = ctx.applicationContext
        ticker = scope.launch {
            publish(withContext(Dispatchers.IO) { scanScreensaverLibrary(app) })
            while (true) {
                // 每轮重读间隔:另一处 attach 改了它,下一张起就按新值
                delay(intervalNow)
                _index.value = nextIndex(_index.value, _files.value.size)
            }
        }
    }

    fun detach() {
        if (refs == 0) return
        refs--
        if (refs == 0) {
            ticker?.cancel()
            ticker = null
        }
    }

    /** 删图后调用(spec §5):重扫并把 [index] 夹回范围。计时状态不动。 */
    fun rescan(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { publish(withContext(Dispatchers.IO) { scanScreensaverLibrary(app) }) }
    }

    private fun publish(list: List<File>) {
        _files.value = list
        _index.value = clampIndex(_index.value, list.size)
    }
}
```

- [ ] **Step 4: `Screensaver.kt`**

```kotlin
package com.uniteduone.launcher

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 桌面的自定义屏保层(spec §1.4 第 2 层):[active] 为真时 attach 播放器、淡入 1200 ms,为假时 detach、淡出 400 ms。
 * 计时、扫描、下标都在 [ScreensaverPlayer];这里只管淡入淡出与 attach / detach 配对。
 * 图库为空时什么都不画(播放器扫出空表,alpha 目标就是 0)。
 */
@Composable
fun Screensaver(active: Boolean, intervalMs: Long) {
    val ctx = LocalContext.current
    // attach / detach 严格成对:onDispose 读的是这一轮效果自己的 active(key 变了才换轮)。
    // 间隔变了也按一对 detach + attach 走——只可能在屏保不在时发生(设置页开着就不会进屏保)。
    DisposableEffect(active, intervalMs) {
        if (active) ScreensaverPlayer.attach(ctx, intervalMs)
        onDispose { if (active) ScreensaverPlayer.detach() }
    }
    val files by ScreensaverPlayer.files.collectAsState()
    // 空图库并进目标值而不是提前 return:动画状态从一开始就在组合里,首次进入才有 0→1 的淡入。
    val layerAlpha by animateFloatAsState(
        targetValue = if (active && files.isNotEmpty()) 1f else 0f,
        animationSpec = tween(if (active) 1200 else 400),
        label = "screensaverAlpha",
    )
    if (layerAlpha == 0f) return
    ScreensaverContent(intervalMs = intervalMs, modifier = Modifier.fillMaxSize().alpha(layerAlpha))
}

/**
 * 轮播层本体(spec §2),桌面与系统屏保共用:读 [ScreensaverPlayer] 的当前图,交叉淡入 + Ken Burns。
 * **不画时钟**——时钟由调用方叠(桌面是 HomeScreen 的 HeroClock,系统屏保是 UnitedUDream 里那一个)。
 * 以**文件**而不是下标作 Crossfade 的目标:删图重扫后同一个下标可能换了图,按文件比对才会淡入而不是硬切。
 */
@Composable
fun ScreensaverContent(intervalMs: Long, modifier: Modifier = Modifier) {
    val files by ScreensaverPlayer.files.collectAsState()
    val index by ScreensaverPlayer.index.collectAsState()
    val current = files.getOrNull(clampIndex(index, files.size))
    Box(modifier) {
        Crossfade(
            targetState = current,
            animationSpec = tween(Theme.ScreensaverCrossfadeMs),
            label = "screensaverCrossfade",
        ) { file ->
            ScreensaverSlot(file, intervalMs)
        }
    }
}

/**
 * 每张图的呈现(spec §2「不变」):RGBA_F16 解码保留 Ultra HDR gain map;解码尺寸封顶 1920×1080
 * ——桌面与系统屏保叠放时两层各解一张,别再放大内存(spec §9)。Ken Burns 放大到 1.08,
 * 时长 = 轮播间隔 + 交叉淡入(原来写死 30 s + 2 s)。
 */
@Composable
private fun ScreensaverSlot(file: File?, intervalMs: Long) {
    file ?: return
    val bmp by produceState<Bitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Apps.decodeScaled(file.absolutePath, 1920, 1080, Bitmap.Config.RGBA_F16)
            }.getOrNull()
        }
    }
    val b = bmp ?: return
    val scale = remember { Animatable(1.0f) }
    LaunchedEffect(file.absolutePath) {
        scale.snapTo(1.0f)
        scale.animateTo(
            targetValue = Theme.ScreensaverZoom,
            animationSpec = tween(
                durationMillis = (intervalMs + Theme.ScreensaverCrossfadeMs).toInt(),
                easing = LinearEasing,
            ),
        )
    }
    Image(
        bitmap = b.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().scale(scale.value),
    )
}
```

- [ ] **Step 5: 从 `HomeScreen.kt` 删掉旧屏保**

删除从
```kotlin
private val SCREENSAVER_IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")
```
起,经 `fun Screensaver(ctx: Context, idle: Boolean)`、`private fun ScreensaverSlot(file: java.io.File?)`,到 `private fun migrateOldScreensaver(ctx: Context) { … }` 的右花括号为止的整段(下一段是 `buildInputRow` 的 KDoc `/**\n * 输入源行。…`,保留)。
然后删掉这 8 行 import(删完上面那段后它们在本文件已无引用;`Context`、`Dispatchers`、`withContext`、`tween` 仍在用,保留):
```kotlin
import kotlinx.coroutines.delay
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
```

- [ ] **Step 6: MainActivity 调用点(触发条件暂不变)**

`MainActivity.kt` 里
```kotlin
            if (homeSettings.idleContent != IdleContent.NO_FADE) {
                Screensaver(this@MainActivity, idle)
            }
```
改为
```kotlin
            if (homeSettings.idleContent != IdleContent.NO_FADE) {
                // M5 Task 2:画法搬到共用播放器(Screensaver.kt),间隔改读设置;触发条件 Task 3 换成 screensaverActive。
                Screensaver(active = idle, intervalMs = homeSettings.screensaverIntervalMs)
            }
```

- [ ] **Step 7: `Theme.kt` 的 KDoc**

```kotlin
    /** 屏保轮播:每张图显示多久。 */
    const val ScreensaverIntervalMs = 30_000L
```
改为
```kotlin
    /**
     * 屏保轮播默认间隔(= [Settings.screensaverIntervalMs] 的默认值)。M5 起实际间隔读设置;
     * 这里只剩两个读者:播放器第一次 attach 之前的初值、图库全屏预览的 Ken Burns 时长。
     */
    const val ScreensaverIntervalMs = 30_000L
```

- [ ] **Step 8: 跑测试 + 构建**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL;`ScreensaverPlayerTest` 5 条全过;`grep -rn "SCREENSAVER_IMAGE_EXTS\|migrateOldScreensaver" app/src/main/java` 只在 `ScreensaverPlayer.kt` 里命中。

- [ ] **Step 9: 模拟器夹具 + 冒烟(行为应与改前一致)**

先按上文「模拟器夹具」跑 F1、F2(只这一次)。然后:
```bash
source scripts/env.sh
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
/tmp/m5-setjson.sh idleContent '"CLOCK_ONLY"' idleAfterMs 180000
sleep 8
/tmp/m5-key.sh DPAD_UP DPAD_RIGHT
python3 /tmp/m5-focus.py
```
Expected:`FOCUS: [..][..] | Screensaver`,bounds 的 y 都 < 200(右上 pill;不是 TV 设置卡)。
```bash
/tmp/m5-shot.sh t2-before
/tmp/m5-key.sh DPAD_CENTER; sleep 3
/tmp/m5-shot.sh t2-ss; python3 /tmp/m5-probe.py /tmp/m5-t2-ss.png
```
Expected:`photo=red`(或 green / blue),`under=` 同色——本任务仍是旧语义:屏保按钮 = 进待机,待机有图就轮播;照片经共用播放器画出来。
```bash
/tmp/m5-key.sh DPAD_LEFT; sleep 1
python3 /tmp/m5-focus.py
/tmp/m5-shot.sh t2-wake; python3 /tmp/m5-probe.py /tmp/m5-t2-wake.png /tmp/m5-t2-before.png
```
Expected:`photo=none`,`cardsDiff` ≤ 3;焦点仍是 Screensaver 按钮(唤醒那一下被吞——没被吞的话左键会把焦点移到设置按钮)。

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/ScreensaverPlayer.kt app/src/main/java/com/uniteduone/launcher/Screensaver.kt app/src/main/java/com/uniteduone/launcher/HomeScreen.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt app/src/main/java/com/uniteduone/launcher/Theme.kt app/src/test/java/com/uniteduone/launcher/ScreensaverPlayerTest.kt
git commit -m "feat(m5): shared ScreensaverPlayer and Screensaver.kt; slideshow interval from settings" -m "Co-Authored-By: Claude <实际模型> <noreply@anthropic.com>"
```

---

### Task 3: 状态机——待机、自定义屏保先后互斥;屏保按钮;时钟阴影;黑层让位

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Clock.kt`(`HeroClock` 整个函数 + 3 个 import)
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt`(文件头 KDoc 末段;签名 `idle: Boolean,` 之后;`contentAlpha` / `clockAlpha` 两个动画;`HeroClock(` 调用)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(`private var idle` 字段、`screensaverRequests` 的 KDoc、计时效果、屏保按钮效果、屏保层、黑层目标值、`HomeScreen(` 调用、`dispatchKeyEvent` 首支)

**Interfaces:**
- Consumes: `standbyPlan` / `StandbyFlags`(Task 1)、`Settings.screensaverAfterMs`(Task 1)、`hasScreensaverImages` / `Screensaver(active, intervalMs)`(Task 2)。
- Produces:
  - `@Composable fun HeroClock(modifier: Modifier = Modifier, showDate: Boolean = true, shadow: Boolean = false)`
  - `HomeScreen(..., screensaver: Boolean = false, ...)`(紧跟 `idle` 之后)
  - MainActivity 内部:`private var standby: StandbyFlags`(唯一存储)、`private val idle: Boolean`、`private val screensaverActive: Boolean`(派生读者)。Task 5 / 6 不再改这三处。

- [ ] **Step 1: `HeroClock(shadow)`**

`Clock.kt` 在 `import androidx.compose.ui.Modifier` 之后加:
```kotlin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
```
把 `HeroClock` 连同它的 KDoc 整个替换为:
```kotlin
/**
 * M8 hero 主体:大字时钟 84sp Medium + 日期 24sp(spec §1.4),颜色 accent(spec §0「accent 落点」)。
 * 位置由调用方给(HomeScreen / UnitedUDream:左对齐 SidePadding、顶 HomeLayout.HERO_TOP);不可聚焦。
 * [shadow](M5 spec §1.5):自定义屏保 / 系统屏保轮播照片时为真——照片可能很亮,时间与日期各加一层淡阴影
 * (黑 α0.55、下移 2、模糊 16);不做描边、不做底板,平时不加(壁纸本来就压暗过)。
 */
@Composable
fun HeroClock(modifier: Modifier = Modifier, showDate: Boolean = true, shadow: Boolean = false) {
    val accent = LocalThemeColors.current.accent
    val state = rememberClockState()
    val locale = AppLocale.current ?: Locale.getDefault()
    val (timePattern, datePattern) = clockPatterns(state.is24Hour)
    // SimpleDateFormat 出生时就把时区绑死:tzTick 变(换时区 / 校时 / 改 12-24 开关)就重建,不能只看 pattern。
    val timeFmt = remember(state.tzTick, timePattern, locale) { SimpleDateFormat(timePattern, locale) }
    val dateFmt = remember(state.tzTick, datePattern, locale) { SimpleDateFormat(datePattern, locale) }
    val textShadow = if (shadow) {
        Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 2f), blurRadius = 16f)
    } else {
        null
    }
    Column(modifier) {
        BasicText(
            text = timeFmt.format(state.now),
            style = TextStyle(
                fontFamily = Theme.Sans, fontWeight = FontWeight.Medium,
                fontSize = 84.sp, lineHeight = 84.sp, color = accent, shadow = textShadow,
            ),
        )
        if (showDate) {
            BasicText(
                text = dateFmt.format(state.now),
                modifier = Modifier.padding(top = 8.dp),
                style = TextStyle(
                    fontFamily = Theme.Sans, fontSize = 24.sp,
                    color = accent.copy(alpha = 0.85f), shadow = textShadow,
                ),
            )
        }
    }
}
```

- [ ] **Step 2: `HomeScreen` 接 `screensaver`**

文件头 KDoc 最后三行
```kotlin
 * 只影响这里的 `contentAlpha`/`clockAlpha` 两个动画,`Screensaver` 不参与(它是
 * `MainActivity` 单独组合的另一层,读的是真实 `idle`)。
 */
```
改为
```kotlin
 * 只影响这里的 `contentAlpha`/`clockAlpha` 两个动画,`Screensaver` 不参与(它是
 * `MainActivity` 单独组合的另一层,M5 起读的是 `screensaverActive`)。
 *
 * [screensaver](M5 spec §1.4)为真 = 自定义屏保:行 / 渐变 / pill 一律淡出(「不淡出」也不例外——
 * 照片上不该浮着一排卡片),大字时钟恒亮并加淡阴影(「全黑」待机进屏保时,时钟随照片一起亮出来)。
 */
```

签名里
```kotlin
fun HomeScreen(
    idle: Boolean,
```
之后插入:
```kotlin
    /** 自定义屏保(M5 spec §1.4)。只在 [idle] 为真时可能为真(MainActivity 的 StandbyFlags 钉死)。 */
    screensaver: Boolean = false,
```

把 `contentAlpha` 与 `clockAlpha` 两段(从注释 `// 待机用 alpha 淡出,不用 AnimatedVisibility——` 到 `label = "clockAlpha",\n        )`)替换为:
```kotlin
        // 待机用 alpha 淡出,不用 AnimatedVisibility——后者自带裁剪,会把超出屏幕的
        // 第三行整块切掉(实测 MUSIC 行因此始终不可见)。
        // NO_FADE(Task 3):待机时恒 1,卡片/行标题/pill 都不淡出。**自定义屏保例外**(M5 spec §0 / §1.4):
        // 「不淡出」只管待机显示,屏保照样全屏——照片上不能浮着一排卡片,所以 screensaver 为真时一律淡出。
        val contentAlpha by animateFloatAsState(
            targetValue = if (screensaver || (effectiveIdle && effectiveIdleContent != IdleContent.NO_FADE)) 0f else 1f,
            animationSpec = tween(if (effectiveIdle) 1200 else 400),
            label = "contentAlpha",
        )
        // 时钟默认待机也留着(CLOCK_ONLY/NO_FADE);只有 BLACK 时钟才跟着淡出,
        // 配合 MainActivity 在 Screensaver 之上叠的黑色蒙版,整屏才会真正全黑。
        // 自定义屏保时恒 1(M5 spec §1.4):「全黑」待机进屏保那一刻,时钟随照片一起亮出来。
        val clockAlpha by animateFloatAsState(
            targetValue = if (!screensaver && effectiveIdle && effectiveIdleContent == IdleContent.BLACK) 0f else 1f,
            animationSpec = tween(if (effectiveIdle) 1200 else 400),
            label = "clockAlpha",
        )
```

`HeroClock(` 调用改为:
```kotlin
        // hero 主体(spec §2.1 第 3 层):不随 shift 走;第 1 行起淡出、待机时回到 1(heroAlpha),BLACK 待机再随 clockAlpha 淡出。
        // 自定义屏保时加淡阴影(M5 spec §1.5):照片可能很亮。
        HeroClock(
            showDate = showDate,
            shadow = screensaver,
            modifier = Modifier
                .padding(start = Theme.SidePadding, top = HomeLayout.HERO_TOP.dp)
                .alpha(heroAlpha * clockAlpha),
        )
```
(`heroAlpha` 不用改:屏保态 `idle` 为真,它本来就回到 1。)

- [ ] **Step 3: MainActivity——一个值、两个布尔量**

```kotlin
    /** 待机(超时淡出)。**必须住在 Activity 里**,因为唤醒发生在 dispatchKeyEvent。 */
    private var idle by mutableStateOf(false)
```
替换为:
```kotlin
    /**
     * 待机与自定义屏保(M5 spec §1)。**一个值装两个布尔量**,只取 [StandbyFlags] 的三个常量:
     * 不变量「屏保 ⇒ 待机」由它的构造函数钉死,两个量一次写完、没有「只写了一半」的中间态。
     * 读者照旧按布尔量读([idle] / [screensaverActive]),`idle` 原有的五个消费者一个字都不用改。
     * **必须住在 Activity 里**,因为唤醒发生在 dispatchKeyEvent。
     */
    private var standby by mutableStateOf(StandbyFlags.NORMAL)
    /** 待机:首页内容淡出 + 下一个按键当唤醒吞掉。自定义屏保时同样为真(spec §1 表)。 */
    private val idle: Boolean get() = standby.idle
    /** 自定义屏保:全屏轮播屏保图库。只在 [idle] 为真时可能为真。 */
    private val screensaverActive: Boolean get() = standby.screensaverActive
```

`screensaverRequests` 的 KDoc(`/**` 到 `*/`,紧挨 `private var screensaverRequests by mutableStateOf(0)` 之上)替换为:
```kotlin
    /**
     * 屏保按钮的请求计数(M8 spec §1.5;M5 spec §1.3 起 = 立刻进自定义屏保、跳过待机,图库空时退为待机)。
     * **不能在点击回调里直接写状态**:按下确认键的那次 dispatchKeyEvent 已经刷新了 lastInput,
     * 计时效果随之在同一次重组里重启、把状态写回正常。所以走一个独立的请求计数:它的效果声明在
     * 计时效果之后、先等一帧再写(R3),稳赢那次重启。唤醒仍由 dispatchKeyEvent 吞掉下一次按键
     * (与超时进入同一条路);不是闩——每次点击都是一次新计数(铁律 7)。
     */
```

- [ ] **Step 4: MainActivity——计时效果(spec §1.1)**

把从注释 `// 待机时长/内容改由设置页驱动(Task 3):idleAfterMs 既是 key 也是守卫(铁律 6)——` 到计时效果右花括号(`idle = true\n            }`)的整段替换为:
```kotlin
            // 待机时长/内容改由设置页驱动(Task 3):idleAfterMs 既是 key 也是守卫(铁律 6)——
            // 用户把它从「关」改成别的值(或反过来)时,这条 effect 必须以新 key 重启,
            // 否则「关」之后再打开待机,要等到下一次别的 key 变化才会生效。
            // M5:screensaverAfterMs 同理(铁律 6)——两个时刻都由 standbyPlan 从同一次按键起算(spec §1.1)。
            val idleAfterMs = homeSettings.idleAfterMs
            val screensaverAfterMs = homeSettings.screensaverAfterMs
            LaunchedEffect(touched, editing, menuOpen, overlay, homeOverlay, idleAfterMs, screensaverAfterMs) {
                standby = StandbyFlags.NORMAL
                if (editing || menuOpen || overlay || homeOverlay) return@LaunchedEffect
                val plan = standbyPlan(idleAfterMs, screensaverAfterMs)
                var waited = 0L
                val standbyAt = plan.standbyAt
                if (standbyAt != null) {
                    delay(standbyAt)
                    waited = standbyAt
                    // 只升不降:屏保按钮可能已经把状态推到了屏保,这一拍不能把它拉回待机
                    // (spec 的写法是「只写 idle = true」;打包成一个值之后,等价写法就是这个判断)。
                    if (!standby.idle) standby = StandbyFlags.STANDBY
                }
                // 屏保「关」:停在待机(待机也「关」就是什么都不发生)。
                val screensaverAt = plan.screensaverAt ?: return@LaunchedEffect
                delay(screensaverAt - waited)
                // 到点才查图库(spec §1.1):空 → 停在待机,下一个键照常只负责唤醒;非空 → 进屏保。
                if (withContext(Dispatchers.IO) { hasScreensaverImages(this@MainActivity) }) {
                    standby = StandbyFlags.SCREENSAVER
                }
            }
```
(铁律 6 自查:`return` 守卫读的 `editing / menuOpen / overlay / homeOverlay` 全在 key 里;`screensaverAt` 由两个 key 算出;`!standby.idle` 不是 return 守卫,是「只升不降」的写入条件,故意不进 key——进了会让每次状态变化都重启计时。)

- [ ] **Step 5: MainActivity——屏保按钮效果(spec §1.3)**

```kotlin
            // 屏保按钮的请求(见 screensaverRequests 的 KDoc):声明在计时效果之后、再等一帧,保证后写。
            LaunchedEffect(screensaverRequests) {
                if (screensaverRequests == 0) return@LaunchedEffect
                withFrameNanos { }
                idle = true
            }
```
替换为:
```kotlin
            // 屏保按钮的请求(见 screensaverRequests 的 KDoc):声明在计时效果之后、再等一帧,保证后写(R3)。
            // M5 spec §1.3:有图 → 立刻进自定义屏保、跳过待机;图库空 → 退为进待机;空图库 +「不淡出」→ 空操作,
            // 下一个键不被吞(「不淡出」的待机没有任何可见效果,进了只会白吞一个键)。NO_FADE 的判断从调用点
            // 移到这里:有图时「不淡出」也能进屏保。idleContentNow 是按下那一刻的设置(本效果随请求计数重启)。
            val idleContentNow = homeSettings.idleContent
            LaunchedEffect(screensaverRequests) {
                if (screensaverRequests == 0) return@LaunchedEffect
                withFrameNanos { }
                val hasImages = withContext(Dispatchers.IO) { hasScreensaverImages(this@MainActivity) }
                if (hasImages) standby = StandbyFlags.SCREENSAVER
                else if (idleContentNow != IdleContent.NO_FADE) standby = StandbyFlags.STANDBY
            }
```

- [ ] **Step 6: MainActivity——屏保层、黑层、HomeScreen 调用、唤醒**

屏保层:把 Task 2 留下的
```kotlin
            // NO_FADE(Task 3):干脆不组合 Screensaver——M5 之前待机不淡出时就是「什么都不发生」
            // (spec §6),屏保图片一张都不该解码,不只是不显示。
            if (homeSettings.idleContent != IdleContent.NO_FADE) {
                // M5 Task 2:画法搬到共用播放器(Screensaver.kt),间隔改读设置;触发条件 Task 3 换成 screensaverActive。
                Screensaver(active = idle, intervalMs = homeSettings.screensaverIntervalMs)
            }
```
替换为:
```kotlin
            // 自定义屏保层(M5 spec §1.4 第 2 层):只看 screensaverActive。不再因「不淡出」不组合——
            // 待机显示只管待机,「不淡出」时屏保照样会来(spec §0);没进屏保时 alpha 为 0,一张图都不画。
            Screensaver(active = screensaverActive, intervalMs = homeSettings.screensaverIntervalMs)
```

黑层:`val blackAlpha = animateFloatAsState(` 里
```kotlin
                targetValue = if (blackIdle && blackContent == IdleContent.BLACK) 1f else 0f,
```
替换为:
```kotlin
                // 进自定义屏保时黑层淡出、照片亮出来(M5 spec §1.4 第 3 层)——「全黑」只管待机。
                targetValue = if (blackIdle && blackContent == IdleContent.BLACK && !screensaverActive) 1f else 0f,
```

`HomeScreen(` 调用里 `idle = idle,` 之后加一行:
```kotlin
                    screensaver = screensaverActive,
```
同一调用里
```kotlin
                    // 屏保按钮 = 立即进入待机(spec §1.5),走请求计数(见 screensaverRequests 的 KDoc)。
                    // NO_FADE 下待机没有任何可见效果(HomeScreen 的 contentAlpha 恒为 1、黑幕不升),
                    // 请求只会白白吞掉下一个按键当唤醒,所以这一档不发请求,按钮不动作。
                    onScreensaver = { if (homeSettings.idleContent != IdleContent.NO_FADE) screensaverRequests++ },
```
替换为:
```kotlin
                    // 屏保按钮 = 立刻进自定义屏保、跳过待机(M5 spec §1.3),走请求计数(见 screensaverRequests 的 KDoc)。
                    // 「不淡出」的判断移进了请求效果:有图时照样进屏保,只有空图库 +「不淡出」才是空操作。
                    onScreensaver = { screensaverRequests++ },
```

`dispatchKeyEvent` 首支
```kotlin
        if (idle) {
            idle = false
            wakeDownTime = event.downTime
            return true
        }
```
替换为:
```kotlin
        if (idle) {
            // 待机与自定义屏保一样:任意键回到正常,这一下只负责唤醒(spec §1.2)。
            standby = StandbyFlags.NORMAL
            wakeDownTime = event.downTime
            return true
        }
```

- [ ] **Step 7: 构建 + 单测**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL,测试全绿(数量同 Task 2)。(`idle` 现在是派生的 `val`,任何漏改的 `idle = …` 写法都会直接编译失败——写状态只剩 `standby = …` 这一种。)

- [ ] **Step 8: 模拟器时间线(spec §7「模拟器时间线」五个场景)**

```bash
source scripts/env.sh
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5554 logcat -b crash -c
```

**A · 时钟待机 1 分 + 屏保 1 分,顺带验唤醒与焦点原位**:
```bash
/tmp/m5-setjson.sh idleContent '"CLOCK_ONLY"' idleAfterMs 60000 screensaverAfterMs 60000 screensaverIntervalMs 30000
sleep 8
/tmp/m5-key.sh DPAD_RIGHT DPAD_RIGHT
python3 /tmp/m5-focus.py | tee /tmp/m5-t3-focus-before.txt
/tmp/m5-timeline.sh t3a
```
Expected:
- 焦点在第一行第 3 张(bounds 的 y 在 700 以上);
- `20s  photo=none under=none … clockMax=`≥ 140;
- `75s  photo=none under=none … clockMax=`≥ 140 `cardsDiff=`≥ 10(卡片 / 渐变 / pill 淡出,只留时钟);
- `135s photo=<颜色> under=<同色> … clockMax=`≥ 140(照片 + 时钟,卡片不在)。

Read `/tmp/m5-t3a-c.png` 看一眼:时钟数字周围有一圈淡淡的暗晕,没有描边、没有底板。然后唤醒:
```bash
/tmp/m5-key.sh DPAD_CENTER; sleep 1
adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus
python3 /tmp/m5-focus.py
/tmp/m5-shot.sh t3a-wake; python3 /tmp/m5-probe.py /tmp/m5-t3a-wake.png /tmp/m5-t3a-a.png
/tmp/m5-key.sh DPAD_RIGHT; python3 /tmp/m5-focus.py
```
Expected:前台仍是 UnitedU(唤醒的确定键被整下吞掉,没启动应用);第一次 `FOCUS` 与 `/tmp/m5-t3-focus-before.txt` 逐字相同;`photo=none`、`cardsDiff` ≤ 3;按右键后焦点移到第 4 张。

**B · 全黑待机**(1 分全黑,2 分照片亮起、时钟随之亮出):
```bash
/tmp/m5-setjson.sh idleContent '"BLACK"'
sleep 8
/tmp/m5-timeline.sh t3b
/tmp/m5-key.sh DPAD_RIGHT
```
Expected:`75s photo=none … pixel=(0, 0, 0)`(允许 ±3)`clockMax=`≤ 20;`135s photo=<颜色> under=<同色> clockMax=`≥ 140。

**C · 图库清空 → 永远停在待机**:
```bash
/tmp/m5-gallery.sh empty
/tmp/m5-setjson.sh idleContent '"CLOCK_ONLY"'
sleep 8
/tmp/m5-timeline.sh t3c
/tmp/m5-key.sh DPAD_RIGHT
/tmp/m5-gallery.sh full
```
Expected:`75s` 与 `135s` 都是 `photo=none under=none clockMax=`≥ 140 `cardsDiff=`≥ 10(到点查到空图库,停在待机)。

**D · 待机「关」+ 屏保 1 分 → 不经过待机,1 分直接进屏保**:
```bash
/tmp/m5-setjson.sh idleAfterMs 0 screensaverAfterMs 60000
sleep 8
/tmp/m5-timeline.sh t3d 45 75
/tmp/m5-key.sh DPAD_RIGHT
```
Expected:`45s photo=none … cardsDiff=`≤ 3(仍是正常态,没有待机);`75s photo=<颜色> under=<同色>`。

**E · 屏保「关」→ 永远停在待机**:
```bash
/tmp/m5-setjson.sh idleAfterMs 60000 screensaverAfterMs 0
sleep 8
/tmp/m5-timeline.sh t3e
/tmp/m5-key.sh DPAD_RIGHT
```
Expected:`75s` 与 `135s` 都是 `photo=none clockMax=`≥ 140 `cardsDiff=`≥ 10。

- [ ] **Step 9: 模拟器——屏保按钮(spec §7「屏保按钮」)**

**F1 · 有图:立刻进屏保,且不会被计时效果降回待机**:
```bash
/tmp/m5-setjson.sh idleContent '"CLOCK_ONLY"' idleAfterMs 60000 screensaverAfterMs 300000
sleep 8
/tmp/m5-key.sh DPAD_UP DPAD_RIGHT
python3 /tmp/m5-focus.py
```
Expected:`FOCUS: … | Screensaver`,bounds 的 y 都 < 200。然后:
```bash
/tmp/m5-key.sh DPAD_CENTER
/tmp/m5-timeline.sh t3f1 30 75
```
Expected:三行全是 `photo=<颜色> under=<同色> clockMax=`≥ 140——20 s 时已是照片(跳过待机);75 s(过了 60 s 的待机时刻)仍是照片,没有退回只剩时钟(Step 4「只升不降」)。唤醒:
```bash
/tmp/m5-key.sh DPAD_LEFT; sleep 1; python3 /tmp/m5-focus.py
```
Expected:焦点仍在 Screensaver 按钮(左键被当唤醒吞掉;没吞的话会落到设置按钮)。

**F2 · 图库空 +「时钟」→ 退为待机**:
```bash
/tmp/m5-gallery.sh empty
/tmp/m5-setjson.sh idleAfterMs 180000
sleep 8
/tmp/m5-key.sh DPAD_UP DPAD_RIGHT; python3 /tmp/m5-focus.py
/tmp/m5-shot.sh t3f2-before
/tmp/m5-key.sh DPAD_CENTER; sleep 3
/tmp/m5-shot.sh t3f2; python3 /tmp/m5-probe.py /tmp/m5-t3f2.png /tmp/m5-t3f2-before.png
/tmp/m5-key.sh DPAD_RIGHT
```
Expected:`photo=none clockMax=`≥ 140 `cardsDiff=`≥ 10。

**F3 · 图库空 +「不淡出」→ 什么都不发生,下一个键不被吞**:
```bash
/tmp/m5-setjson.sh idleContent '"NO_FADE"'
sleep 8
/tmp/m5-key.sh DPAD_UP DPAD_RIGHT; python3 /tmp/m5-focus.py
/tmp/m5-shot.sh t3f3-before
/tmp/m5-key.sh DPAD_CENTER; sleep 3
/tmp/m5-shot.sh t3f3; python3 /tmp/m5-probe.py /tmp/m5-t3f3.png /tmp/m5-t3f3-before.png
/tmp/m5-key.sh DPAD_DOWN; python3 /tmp/m5-focus.py
```
Expected:`cardsDiff` ≤ 3;按下键后 `FOCUS` 是第一行的一张卡(bounds 的 y > 700)——这一键没被当唤醒吞掉。

**F4 · 有图 +「不淡出」→ 照样进屏保,卡片不浮在照片上**:
```bash
/tmp/m5-gallery.sh full
/tmp/m5-setjson.sh idleContent '"NO_FADE"'
sleep 8
/tmp/m5-key.sh DPAD_UP DPAD_RIGHT; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_CENTER; sleep 3
/tmp/m5-shot.sh t3f4; python3 /tmp/m5-probe.py /tmp/m5-t3f4.png
/tmp/m5-key.sh DPAD_RIGHT
```
Expected:`photo=<颜色> under=<同色> clockMax=`≥ 140。

收尾:
```bash
/tmp/m5-setjson.sh idleContent '"CLOCK_ONLY"' idleAfterMs 180000 screensaverAfterMs 300000
adb -s emulator-5554 logcat -b crash -d | grep -c uniteduone
```
Expected:最后一行输出 `0`。**任何一项不符:先按 superpowers:systematic-debugging 找原因,修在本任务内。**

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/Clock.kt app/src/main/java/com/uniteduone/launcher/HomeScreen.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt
git commit -m "feat(m5): standby then screensaver as two sequential states; screensaver button and clock shadow" -m "Co-Authored-By: Claude <实际模型> <noreply@anthropic.com>"
```

---

### Task 4: 设置页「待机与屏保」组六行(行内提示 + 两条动作行 + 三语文案)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/SettingsModel.kt`(`ControlRow`、`SettingsActions`、`settingsGroups` 签名与 STANDBY 组;新增 `screensaverAfterNoteRes`)
- Modify: `app/src/main/java/com/uniteduone/launcher/SettingsScreen.kt`(import;参数 `galleryVersion`;`liveActions`;图库计数;`settingsGroups(` 调用;`SettingRow` → `RowFrame(note)`;`RowFrame`)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(字段 `galleryVersion`;`settingsActions`;`SettingsScreen(` 调用;`openScreensaverPool()`;新增 `openSystemScreensaverSettings()`)
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rTW/strings.xml`、`values-en/strings.xml`
- Test: `app/src/test/java/com/uniteduone/launcher/SettingsModelTest.kt`(整个文件重写:原 11 条语义不变、补第 4 个参数,新增 6 条)

**Interfaces:**
- Consumes: `VALID_SCREENSAVER_AFTER_MS` / `VALID_SCREENSAVER_INTERVAL_MS` / 两个新字段(Task 1);`scanScreensaverLibrary`(Task 2)。
- Produces:
  - `ControlRow(..., optionArgs: List<Int?> = emptyList(), noteRes: Int? = null, onSelect: (Int) -> Unit)`
  - `class SettingsActions(pickWallpaper, openImport, setDefaultHome, restoreDefaults, applyLanguage, openScreensaverGallery: () -> Unit, openSystemScreensaver: () -> Unit)`
  - `internal fun screensaverAfterNoteRes(idleAfterMs: Long, screensaverAfterMs: Long, screensaverImages: Int): Int?`
  - `fun settingsGroups(s: Settings, update: ((Settings) -> Settings) -> Unit, actions: SettingsActions, screensaverImages: Int): List<GroupSpec>`
  - `SettingsScreen(..., galleryVersion: Int = 0)`;MainActivity `private var galleryVersion`(Task 5 删图后 `++`)
  - STANDBY 组行 id 依次:`idleAfter`、`idleContent`、`screensaverAfter`、`screensaverInterval`、`screensaverGallery`、`systemScreensaver`
  - 字符串键:`settings_screensaver_after`、`settings_screensaver_note_empty`、`settings_screensaver_note_from_input`、`settings_screensaver_note_after_standby`、`settings_screensaver_interval`、`settings_seconds`、`settings_screensaver_gallery`、`settings_screensaver_gallery_desc`、`settings_system_screensaver`、`settings_system_screensaver_desc`、`toast_system_screensaver_unavailable`;改:`settings_group_standby`、`settings_wallpaper_rotate`、`menu_settings_desc`

- [ ] **Step 1: 写失败的测试(整个 `SettingsModelTest.kt` 替换为)**

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [settingsGroups] 是设置页的**唯一真相**:分组顺序、每组有哪些行、每行当前选中第几档、
 * 选中之后写什么 —— 全在这一个纯函数里,所以全部可以在 JVM 上钉死,不必上模拟器。
 * (界面那半只负责画和焦点账本;它读这份模型,不自己另存一份下标。)
 */
class SettingsModelTest {

    /** 把 [SettingsActions] 的七条动作各记一笔,断言「按下去真的调到了那一条」。 */
    private class Recorder {
        val fired = mutableListOf<String>()
        val languages = mutableListOf<String>()
        val actions = SettingsActions(
            pickWallpaper = { fired += "pickWallpaper" },
            openImport = { fired += "openImport" },
            setDefaultHome = { fired += "setDefaultHome" },
            restoreDefaults = { fired += "restoreDefaults" },
            applyLanguage = { lang -> languages += lang },
            openScreensaverGallery = { fired += "openScreensaverGallery" },
            openSystemScreensaver = { fired += "openSystemScreensaver" },
        )
    }

    /** 图库张数:多数用例只关心「非空」。 */
    private val someImages = 3

    private fun rowsOf(groups: List<GroupSpec>) = groups.flatMap { it.rows }
    private fun row(groups: List<GroupSpec>, id: String) = rowsOf(groups).first { it.id == id }
    private fun ctrl(groups: List<GroupSpec>, id: String) = row(groups, id) as ControlRow

    @Test fun groupOrderFollowsSpec() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertEquals(
            listOf(
                GroupId.LAYOUT, GroupId.WALLPAPER, GroupId.THEME,
                GroupId.STANDBY, GroupId.CLOCK, GroupId.LANGUAGE, GroupId.OTHER,
            ),
            g.map { it.id },
        )
    }

    @Test fun rowCountsPerGroup() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        // 布局 3 / 壁纸 2 动作 + 3 控件 / 主题 3 / 待机与屏保 4 控件 + 2 动作(M5)/ 时钟 1 / 语言 1 / 其他 2 动作
        assertEquals(listOf(3, 5, 3, 6, 1, 1, 2), g.map { it.rows.size })
    }

    @Test fun rowIdsAreUnique() {
        val ids = rowsOf(settingsGroups(Settings(), {}, Recorder().actions, someImages)).map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    /** 右栏一屏放得下的不变量(spec §2.1:≤ 8 行、永不滚动)。 */
    @Test fun noGroupExceedsEightRows() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertTrue(g.all { it.rows.size <= 8 })
    }

    @Test fun wallpaperGroupStartsWithTwoActionRows() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        val wallpaper = g.first { it.id == GroupId.WALLPAPER }.rows
        assertTrue(wallpaper[0] is ActionRow)
        assertTrue(wallpaper[1] is ActionRow)
        assertTrue(wallpaper.drop(2).all { it is ControlRow })
    }

    @Test fun otherGroupIsTwoActionRows() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertTrue(g.first { it.id == GroupId.OTHER }.rows.all { it is ActionRow })
    }

    @Test fun selectedMirrorsSettings() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        // 默认 cardsPerRow = 6 → VALID_CARDS_PER_ROW(5,6,8) 的第 1 档「中」
        assertEquals(1, ctrl(g, "cardsPerRow").selected)
        // 默认亮度 0 → 双向滑块正中(zeroAt = 5)
        assertEquals(5, ctrl(g, "wallpaperBrightness").selected)
        assertEquals(5, ctrl(g, "wallpaperBrightness").zeroAt)
        // 默认语言 system → 第 0 档
        assertEquals(0, ctrl(g, "language").selected)

        val other = settingsGroups(
            Settings(cardsPerRow = 8, wallpaperBrightness = -20, language = "zh-TW"),
            {}, Recorder().actions, someImages,
        )
        assertEquals(2, ctrl(other, "cardsPerRow").selected)
        assertEquals(3, ctrl(other, "wallpaperBrightness").selected)   // (−20 + 50) / 10
        assertEquals(2, ctrl(other, "language").selected)              // VALID_LANGUAGES 的第 2 档
    }

    @Test fun controlSelectWritesThroughUpdate() {
        var written: Settings? = null
        val base = Settings()
        val g = settingsGroups(base, { transform -> written = transform(base) }, Recorder().actions, someImages)
        ctrl(g, "cardsPerRow").onSelect(2)
        assertEquals(8, written?.cardsPerRow)
        ctrl(g, "wallpaperBrightness").onSelect(8)
        assertEquals(30, written?.wallpaperBrightness)
        ctrl(g, "idleContent").onSelect(1)
        assertEquals(IdleContent.BLACK, written?.idleContent)
    }

    /**
     * 语言行**不自己写盘**:它把档位翻译成 [VALID_LANGUAGES] 里的取值交给 [SettingsActions.applyLanguage]——
     * 那条动作在 T8 才接上 `recreate()`,本任务只写字段。行自己不认识 Activity,这条边界必须钉住。
     */
    @Test fun languageRowGoesThroughApplyLanguage() {
        val r = Recorder()
        var written: Settings? = null
        val g = settingsGroups(Settings(), { transform -> written = transform(Settings()) }, r.actions, someImages)
        ctrl(g, "language").onSelect(3)
        assertEquals(listOf("en"), r.languages)
        assertEquals(null, written)
    }

    @Test fun actionRowsFireTheirAction() {
        val r = Recorder()
        val g = settingsGroups(Settings(), {}, r.actions, someImages)
        (row(g, "pickWallpaper") as ActionRow).onActivate()
        (row(g, "openImport") as ActionRow).onActivate()
        (row(g, "setDefaultHome") as ActionRow).onActivate()
        (row(g, "restoreDefaults") as ActionRow).onActivate()
        (row(g, "screensaverGallery") as ActionRow).onActivate()
        (row(g, "systemScreensaver") as ActionRow).onActivate()
        assertEquals(
            listOf(
                "pickWallpaper", "openImport", "setDefaultHome", "restoreDefaults",
                "openScreensaverGallery", "openSystemScreensaver",
            ),
            r.fired,
        )
    }

    /** 分段/开关的档数必须与它的显示文案条数一致,否则界面会画出一个点不到的档。 */
    @Test fun segmentedOptionsMatchCount() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        rowsOf(g).filterIsInstance<ControlRow>()
            .filter { it.kind == CtrlKind.SEGMENTED || it.kind == CtrlKind.TOGGLE }
            .forEach { assertEquals(it.id, it.count, it.optionRes.size) }
    }

    // ---- M5「待机与屏保」组(spec §3)----

    @Test fun standbyGroupHasSixRowsInSpecOrder() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        val standby = g.first { it.id == GroupId.STANDBY }.rows
        assertEquals(
            listOf(
                "idleAfter", "idleContent", "screensaverAfter",
                "screensaverInterval", "screensaverGallery", "systemScreensaver",
            ),
            standby.map { it.id },
        )
        assertTrue(standby.take(4).all { it is ControlRow })
        assertTrue(standby.drop(4).all { it is ActionRow })
    }

    @Test fun screensaverRowsMirrorSettings() {
        val d = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        assertEquals(2, ctrl(d, "screensaverAfter").selected)      // 默认 5 分 = (关,1,5,10,30) 的第 2 档
        assertEquals(0, ctrl(d, "screensaverInterval").selected)   // 默认 30 秒 = (30 秒,1 分,5 分) 的第 0 档
        val o = settingsGroups(
            Settings(screensaverAfterMs = 0L, screensaverIntervalMs = 300_000L), {}, Recorder().actions, someImages,
        )
        assertEquals(0, ctrl(o, "screensaverAfter").selected)
        assertEquals(2, ctrl(o, "screensaverInterval").selected)
    }

    @Test fun screensaverRowsWriteThroughUpdate() {
        var written: Settings? = null
        val base = Settings()
        val g = settingsGroups(base, { transform -> written = transform(base) }, Recorder().actions, someImages)
        ctrl(g, "screensaverAfter").onSelect(4)
        assertEquals(1_800_000L, written?.screensaverAfterMs)
        ctrl(g, "screensaverInterval").onSelect(1)
        assertEquals(60_000L, written?.screensaverIntervalMs)
    }

    @Test fun screensaverOptionLabels() {
        val g = settingsGroups(Settings(), {}, Recorder().actions, someImages)
        val after = ctrl(g, "screensaverAfter")
        assertEquals(R.string.settings_idle_off, after.optionRes[0])   // 「关」复用待机时长那一行的
        assertEquals(listOf(null, 1, 5, 10, 30), after.optionArgs)
        val interval = ctrl(g, "screensaverInterval")
        assertEquals(
            listOf(R.string.settings_seconds, R.string.settings_idle_minutes, R.string.settings_idle_minutes),
            interval.optionRes,
        )
        assertEquals(listOf(30, 1, 5), interval.optionArgs)
    }

    @Test fun screensaverAfterNoteCoversEveryCase() {
        assertEquals(R.string.settings_screensaver_note_empty, screensaverAfterNoteRes(180_000L, 300_000L, 0))
        assertEquals(R.string.settings_screensaver_note_from_input, screensaverAfterNoteRes(0L, 300_000L, 3))
        assertEquals(R.string.settings_screensaver_note_after_standby, screensaverAfterNoteRes(180_000L, 300_000L, 3))
        // 还没数完(−1)不当成空
        assertEquals(R.string.settings_screensaver_note_after_standby, screensaverAfterNoteRes(180_000L, 300_000L, -1))
        // 屏保启动本身是「关」:计时起点与图库都跟它无关了,不画提示
        assertNull(screensaverAfterNoteRes(180_000L, 0L, 0))
    }

    @Test fun onlyScreensaverAfterCarriesANote() {
        val empty = settingsGroups(Settings(), {}, Recorder().actions, 0)
        assertEquals(R.string.settings_screensaver_note_empty, ctrl(empty, "screensaverAfter").noteRes)
        val fromInput = settingsGroups(Settings(idleAfterMs = 0L), {}, Recorder().actions, someImages)
        assertEquals(R.string.settings_screensaver_note_from_input, ctrl(fromInput, "screensaverAfter").noteRes)
        assertTrue(
            rowsOf(empty).filterIsInstance<ControlRow>()
                .filter { it.id != "screensaverAfter" }
                .all { it.noteRes == null },
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests 'com.uniteduone.launcher.SettingsModelTest'`
Expected: 编译失败——`Cannot find a parameter with this name: openScreensaverGallery`、`Too many arguments` / `Unresolved reference: screensaverAfterNoteRes`、`Unresolved reference: settings_seconds`。

- [ ] **Step 3: 三语文案(spec §6)**

`values/strings.xml`:
- `<string name="menu_settings_desc">布局、壁纸、主题、待机、时钟、语言</string>` → `<string name="menu_settings_desc">布局、壁纸、主题、待机与屏保、时钟、语言</string>`
- `<string name="settings_group_standby">待机</string>` → `<string name="settings_group_standby">待机与屏保</string>`
- `<string name="settings_wallpaper_rotate">轮播间隔</string>` → `<string name="settings_wallpaper_rotate">壁纸自动切换</string>`
- `<string name="settings_idle_nofade">不淡出</string>` 之后插入:
```xml
    <!-- M5「待机与屏保」组新增的四行(spec §3、§6);分钟档复用 settings_idle_minutes,「关」复用 settings_idle_off -->
    <string name="settings_screensaver_after">屏保启动</string>
    <string name="settings_screensaver_note_empty">图库为空,不会进入</string>
    <string name="settings_screensaver_note_from_input">从最后一次按键算</string>
    <string name="settings_screensaver_note_after_standby">从进入待机算</string>
    <string name="settings_screensaver_interval">屏保轮播设置</string>
    <string name="settings_seconds">%1$d 秒</string>
    <string name="settings_screensaver_gallery">屏保图库</string>
    <string name="settings_screensaver_gallery_desc">看图、长按删图</string>
    <string name="settings_system_screensaver">系统屏保</string>
    <string name="settings_system_screensaver_desc">在系统设置里选 UnitedU</string>
```
- `<string name="toast_restored">已恢复默认设置</string>` 之后插入:
```xml
    <string name="toast_system_screensaver_unavailable">打不开系统屏保设置</string>
```

`values-zh-rTW/strings.xml`:
- `<string name="menu_settings_desc">版面、桌布、主題、待機、時鐘、語言</string>` → `<string name="menu_settings_desc">版面、桌布、主題、待機與螢幕保護、時鐘、語言</string>`
- `<string name="settings_group_standby">待機</string>` → `<string name="settings_group_standby">待機與螢幕保護</string>`
- `<string name="settings_wallpaper_rotate">輪播間隔</string>` → `<string name="settings_wallpaper_rotate">桌布自動切換</string>`
- `<string name="settings_idle_nofade">不淡出</string>` 之后插入:
```xml
    <!-- M5「待機與螢幕保護」組新增的四行(spec §3、§6);分鐘檔複用 settings_idle_minutes,「關」複用 settings_idle_off -->
    <string name="settings_screensaver_after">螢幕保護啟動</string>
    <string name="settings_screensaver_note_empty">圖庫是空的,不會進入</string>
    <string name="settings_screensaver_note_from_input">從最後一次按鍵算起</string>
    <string name="settings_screensaver_note_after_standby">從進入待機算起</string>
    <string name="settings_screensaver_interval">螢幕保護輪播設定</string>
    <string name="settings_seconds">%1$d 秒</string>
    <string name="settings_screensaver_gallery">螢幕保護圖庫</string>
    <string name="settings_screensaver_gallery_desc">看圖、長按刪圖</string>
    <string name="settings_system_screensaver">系統螢幕保護</string>
    <string name="settings_system_screensaver_desc">在系統設定選 UnitedU</string>
```
- `<string name="toast_restored">已恢復預設設定</string>` 之后插入:
```xml
    <string name="toast_system_screensaver_unavailable">無法開啟系統螢幕保護設定</string>
```

`values-en/strings.xml`(XML 里 `&` 写 `&amp;`、撇号写 `\'`;`menu_settings_desc` 的 `\n` 断行位置不动,第二行加长后约 400 px,仍小于那段注释说的聚焦可用宽 515 px):
- `<string name="menu_settings_desc">Layout, wallpaper, theme,\nstandby, clock and language</string>` → `<string name="menu_settings_desc">Layout, wallpaper, theme,\nstandby &amp; screensaver, clock and language</string>`
- `<string name="settings_group_standby">Standby</string>` → `<string name="settings_group_standby">Standby &amp; Screensaver</string>`
- `<string name="settings_wallpaper_rotate">Rotate Every</string>` → `<string name="settings_wallpaper_rotate">Auto-change wallpaper</string>`
- `<string name="settings_idle_nofade">No Fade</string>` 之后插入:
```xml
    <!-- M5 "Standby & Screensaver" group, four new rows (spec §3, §6); minutes reuse settings_idle_minutes, "Off" reuses settings_idle_off -->
    <string name="settings_screensaver_after">Screensaver starts</string>
    <string name="settings_screensaver_note_empty">Gallery empty, won\'t start</string>
    <string name="settings_screensaver_note_from_input">From the last key press</string>
    <string name="settings_screensaver_note_after_standby">After standby begins</string>
    <string name="settings_screensaver_interval">Slideshow interval</string>
    <string name="settings_seconds">%1$d s</string>
    <string name="settings_screensaver_gallery">Screensaver gallery</string>
    <string name="settings_screensaver_gallery_desc">View; hold OK to delete</string>
    <string name="settings_system_screensaver">System screensaver</string>
    <string name="settings_system_screensaver_desc">Pick UnitedU in system settings</string>
```
- `<string name="toast_restored">Defaults restored</string>` 之后插入:
```xml
    <string name="toast_system_screensaver_unavailable">Can\'t open system screensaver settings</string>
```

- [ ] **Step 4: `SettingsModel.kt`**

`data class ControlRow` 的 KDoc 末尾(`* 界面按 \`stringResource(res, arg)\` 解析 —— 一个资源 id 要出现在同一行的好几档里,光靠 id 分不开。` 之后、`*/` 之前)加一行:
```kotlin
 * [noteRes](M5 spec §3):标签下方一行小字提示;null = 不画。目前只有「屏保启动」行用它(计时起点 / 图库为空)。
```
字段 `val optionArgs: List<Int?> = emptyList(),` 之后插入:
```kotlin
    val noteRes: Int? = null,
```

`SettingsActions` 连同它的 KDoc 整个替换为:
```kotlin
/**
 * 设置页要做、但**只有 Activity 做得了**的七件事(开子界面、切语言、跳系统页)。
 * 模型只管把它们挂到对应的行上,不认识 `Context`;真正的实现在 `MainActivity`。
 */
class SettingsActions(
    val pickWallpaper: () -> Unit,
    val openImport: () -> Unit,
    val setDefaultHome: () -> Unit,
    val restoreDefaults: () -> Unit,
    /** 取值是 [VALID_LANGUAGES] 里的一项。T8 起它 = 写盘 + `recreate()`;在那之前只写盘。 */
    val applyLanguage: (String) -> Unit,
    /** M5:打开屏保图库(叠在设置页上;设置页 `covered` 让路,关掉后焦点回同一行)。 */
    val openScreensaverGallery: () -> Unit,
    /** M5:跳系统屏保设置页;解析不到退到系统设置首页,两个都打不开 toast(spec §3)。 */
    val openSystemScreensaver: () -> Unit,
)

/**
 * 「屏保启动」行的行内提示(M5 spec §3):图库为空 →「不会进入」;待机时长为「关」→「从最后一次按键算」;
 * 否则 →「从进入待机算」。屏保启动本身是「关」时不画提示——计时起点与图库都跟它无关了。
 * [screensaverImages] = −1 表示设置页还没数完(IO 在途),按非空处理,不在打开的那一瞬间误报「图库为空」。
 */
internal fun screensaverAfterNoteRes(idleAfterMs: Long, screensaverAfterMs: Long, screensaverImages: Int): Int? = when {
    screensaverAfterMs == 0L -> null
    screensaverImages == 0 -> R.string.settings_screensaver_note_empty
    idleAfterMs == 0L -> R.string.settings_screensaver_note_from_input
    else -> R.string.settings_screensaver_note_after_standby
}
```

`settingsGroups` 的签名
```kotlin
fun settingsGroups(
    s: Settings,
    update: ((Settings) -> Settings) -> Unit,
    actions: SettingsActions,
): List<GroupSpec> {
```
改为
```kotlin
fun settingsGroups(
    s: Settings,
    update: ((Settings) -> Settings) -> Unit,
    actions: SettingsActions,
    /** 屏保图库张数(M5:「屏保启动」行的提示要分「图库为空」);−1 = 设置页还没数完。 */
    screensaverImages: Int,
): List<GroupSpec> {
```

整个 STANDBY 组(`GroupSpec(\n            GroupId.STANDBY, R.string.settings_group_standby,` 到它的 `),` 结束)替换为:
```kotlin
        GroupSpec(
            GroupId.STANDBY, R.string.settings_group_standby,
            listOf(
                ControlRow(
                    id = "idleAfter", labelRes = R.string.settings_idle_after,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(
                        R.string.settings_idle_off,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                    ),
                    optionArgs = listOf(null, 1, 3, 5, 10),
                    count = VALID_IDLE_AFTER_MS.size,
                    selected = VALID_IDLE_AFTER_MS.indexOf(s.idleAfterMs).let { if (it < 0) 2 else it },
                    onSelect = { i -> update { it.copy(idleAfterMs = VALID_IDLE_AFTER_MS[i]) } },
                ),
                ControlRow(
                    id = "idleContent", labelRes = R.string.settings_idle_content,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(
                        R.string.settings_idle_clock,
                        R.string.settings_idle_black,
                        R.string.settings_idle_nofade,
                    ),
                    count = IdleContent.entries.size,
                    selected = IdleContent.entries.indexOf(s.idleContent).coerceAtLeast(0),
                    onSelect = { i -> update { it.copy(idleContent = IdleContent.entries[i]) } },
                ),
                // M5 spec §3:进入待机后再过多久进自定义屏保;行内小字说明计时起点 / 图库为空(screensaverAfterNoteRes)。
                ControlRow(
                    id = "screensaverAfter", labelRes = R.string.settings_screensaver_after,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(
                        R.string.settings_idle_off,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                    ),
                    optionArgs = listOf(null, 1, 5, 10, 30),
                    count = VALID_SCREENSAVER_AFTER_MS.size,
                    // 读盘已夹过;万一找不到退到默认 5 分(第 2 档),不让下标变成 −1。
                    selected = VALID_SCREENSAVER_AFTER_MS.indexOf(s.screensaverAfterMs).let { if (it < 0) 2 else it },
                    noteRes = screensaverAfterNoteRes(s.idleAfterMs, s.screensaverAfterMs, screensaverImages),
                    onSelect = { i -> update { it.copy(screensaverAfterMs = VALID_SCREENSAVER_AFTER_MS[i]) } },
                ),
                ControlRow(
                    id = "screensaverInterval", labelRes = R.string.settings_screensaver_interval,
                    kind = CtrlKind.SEGMENTED,
                    optionRes = listOf(
                        R.string.settings_seconds,
                        R.string.settings_idle_minutes,
                        R.string.settings_idle_minutes,
                    ),
                    optionArgs = listOf(30, 1, 5),
                    count = VALID_SCREENSAVER_INTERVAL_MS.size,
                    selected = VALID_SCREENSAVER_INTERVAL_MS.indexOf(s.screensaverIntervalMs).coerceAtLeast(0),
                    onSelect = { i -> update { it.copy(screensaverIntervalMs = VALID_SCREENSAVER_INTERVAL_MS[i]) } },
                ),
                // 两条动作行(spec §3):图库叠在设置页上;系统屏保跳系统页。都只有 Activity 做得了,走 actions。
                ActionRow(
                    "screensaverGallery",
                    R.string.settings_screensaver_gallery,
                    R.string.settings_screensaver_gallery_desc,
                ) { actions.openScreensaverGallery() },
                ActionRow(
                    "systemScreensaver",
                    R.string.settings_system_screensaver,
                    R.string.settings_system_screensaver_desc,
                ) { actions.openSystemScreensaver() },
            ),
        ),
```
(右栏 6 行 ≤ 8,`SettingsScreen` 的 `rowReq = List(8)` 不用动,不需要滚动——铁律 1。)

- [ ] **Step 5: `SettingsScreen.kt`**

import:`import androidx.compose.ui.text.font.FontWeight` 之后加 `import androidx.compose.ui.text.style.TextOverflow`;`import androidx.compose.ui.unit.sp` 之后加
```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

参数表里 `reloadNonce: Int = 0,` 之后加:
```kotlin
    /** 屏保图库版本(M5):MainActivity 删图后 +1,本页据此重数图库(「屏保启动」行的提示)。 */
    galleryVersion: Int = 0,
```

`liveActions` 整个 `remember(actions) { SettingsActions(...) }` 替换为:
```kotlin
    val liveActions = remember(actions) {
        SettingsActions(
            pickWallpaper = actions.pickWallpaper,
            openImport = actions.openImport,
            setDefaultHome = actions.setDefaultHome,
            restoreDefaults = { actions.restoreDefaults(); s = SettingsStore.read(ctx) },
            applyLanguage = { lang -> actions.applyLanguage(lang); s = SettingsStore.read(ctx) },
            // M5 两条:不写盘,本页快照不会过期,原样转交。
            openScreensaverGallery = actions.openScreensaverGallery,
            openSystemScreensaver = actions.openSystemScreensaver,
        )
    }
```

```kotlin
    // 内容模型(分组 / 行 / 当前档位)全在 SettingsModel.kt 里,这里只画和管焦点。
    val groups = settingsGroups(s, { transform -> update(transform) }, liveActions)
```
替换为:
```kotlin
    // 屏保图库张数(M5 spec §3):「屏保启动」行的提示要分「图库为空」。IO 线程数(与播放器同一条扫描规则,
    // 见 scanScreensaverLibrary);key 带图库版本(删图后 +1)与 covered——从导入页 / 图库查看器回来时重数一次,
    // 手机上传也会改图库。−1 = 还没数完,模型按非空处理。produceState 没有守卫,不涉及铁律 6。
    val screensaverImages by produceState(-1, galleryVersion, covered) {
        value = withContext(Dispatchers.IO) { scanScreensaverLibrary(ctx).size }
    }

    // 内容模型(分组 / 行 / 当前档位)全在 SettingsModel.kt 里,这里只画和管焦点。
    val groups = settingsGroups(s, { transform -> update(transform) }, liveActions, screensaverImages)
```

`SettingRow` 里
```kotlin
        RowFrame(focused = focused, label = stringResource(ctrl.labelRes)) {
```
改为
```kotlin
        RowFrame(focused = focused, label = stringResource(ctrl.labelRes), note = ctrl.noteRes?.let { stringResource(it) }) {
```

`RowFrame` 连同它的 KDoc 整个替换为:
```kotlin
/**
 * 行的外壳:聚焦底色 + 左侧竖条 + 标签列。两种行共用,免得「只有动作行忘了改」那种漂移。
 * [note](M5 spec §3「标签与控件之间一行小字」):画在标签**下方**、仍在 190 dp 标签列里——控件列不右移,
 * 同组控件照旧纵向对齐;横着塞不下(五档分段控件之后只剩约 80 dp)。15 sp 标签 + 12 sp 小字两行放得进
 * 46 dp 行高,右栏行数上限(≤ 8,不滚动)不受影响。样式同 ActionRowItem 的 hint。
 */
@Composable
private fun RowFrame(focused: Boolean, label: String, note: String? = null, content: @Composable () -> Unit) {
    val highlight = LocalThemeColors.current.highlight
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) Brush.horizontalGradient(
                    listOf(highlight.copy(alpha = 0.12f), Color.Transparent),
                ) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)),
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.5.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (focused) highlight else Color.Transparent),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(LABEL_W)) {
            BasicText(
                text = label,
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                    color = if (focused) Theme.EmphasisText else Theme.MenuItemText,
                    fontSize = 15.sp,
                ),
            )
            if (note != null) {
                BasicText(
                    text = note,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        color = if (focused) Theme.SecondaryText else Theme.FooterHintText,
                        fontSize = 12.sp,
                    ),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.CenterStart) { content() }
    }
}
```

- [ ] **Step 6: `MainActivity.kt`**

`private var screensaverRequests by mutableStateOf(0)` 之后插入:
```kotlin
    /**
     * 屏保图库版本(M5 spec §3 / §5):删图后 +1。设置页的图库计数(「屏保启动」行的提示)与图库查看器的
     * 文件列表都以它为 key 重读。只增不减,不是闩(铁律 7)。
     */
    private var galleryVersion by mutableStateOf(0)
```

`val settingsActions = remember { SettingsActions( … ) }` 整段替换为:
```kotlin
            val settingsActions = remember {
                SettingsActions(
                    pickWallpaper = { pickWallpaper() },
                    openImport = { openImport() },
                    setDefaultHome = { openHomeSettings() },
                    // 只开确认框(spec §4),真正的写盘在用户按下「恢复」之后 —— 见 confirmRestoreDefaults()。
                    restoreDefaults = { confirmRestore = true },
                    // T8:接回真正的 applyLanguage()——写盘,且只在 Locale 真的变了才 recreate()。
                    // 重建后的位置由 onSaveInstanceState/onCreate 经 Bundle 还原(见 settingsPos 的
                    // KDoc),SettingsScreen 拿 initialPos 当 remember 的种子把焦点落回同一行。
                    applyLanguage = ::applyLanguage,
                    // M5:屏保图库与换壁纸同一套(只置 pickerTarget,叠在设置页上),关掉后设置页把焦点接回这一行。
                    openScreensaverGallery = { openScreensaverPool() },
                    openSystemScreensaver = { openSystemScreensaverSettings() },
                )
            }
```

`SettingsScreen(` 调用里 `reloadNonce = settingsReloadNonce,` 之后插入:
```kotlin
                        // 图库版本(M5):删图后 +1,设置页据此重数图库(「屏保启动」行的提示)。
                        galleryVersion = galleryVersion,
```

`openScreensaverPool()` 连同它的 KDoc 与 `@Suppress("unused")` 整个替换为:
```kotlin
    /**
     * 屏保图库查看器的入口:设置页「待机与屏保 → 屏保图库 ▸」(M5 spec §3)。叠在设置页之上,
     * 关掉后 focusNonce++ 让设置页把焦点接回这一行(设置页 `covered` 期间冻结目标)。
     */
    private fun openScreensaverPool() {
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        closeMenu()
        pickerTarget = VIEW_SCREENSAVER_POOL
    }
```

`private fun open(intent: Intent) { … }` 之后插入:
```kotlin
    /**
     * 设置页「系统屏保 ▸」(M5 spec §3):系统屏保设置页;解析不到(`ActivityNotFoundException`)退到系统设置首页;
     * 两个都打不开才 toast。不检测系统当前选的是不是 UnitedU(spec §8:隐藏设置键,读不可靠)。
     * 回来时 onResume 的 focusNonce++ 让设置页把焦点送回这一行(ON_PAUSE 起冻结)。
     */
    private fun openSystemScreensaverSettings() {
        for (action in listOf(Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_SETTINGS)) {
            val ok = runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return
        }
        toast(getString(R.string.toast_system_screensaver_unavailable))
    }
```
(本文件的 `Settings` 是 `android.provider.Settings`,正是这里要的。)

- [ ] **Step 7: 跑测试 + 构建**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL;`SettingsModelTest` 17 条全过(原 11 + 新 6)。

- [ ] **Step 8: 模拟器——六行、提示三种情况、两条动作行、改名**

```bash
source scripts/env.sh
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
/tmp/m5-setjson.sh language '"system"' idleContent '"CLOCK_ONLY"' idleAfterMs 180000 screensaverAfterMs 300000 screensaverIntervalMs 30000
sleep 8
/tmp/m5-key.sh MENU DPAD_DOWN; python3 /tmp/m5-focus.py
```
Expected:`FOCUS: … | UnitedU Settings / …`。然后:
```bash
/tmp/m5-key.sh DPAD_CENTER DPAD_DOWN DPAD_DOWN DPAD_DOWN; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_RIGHT; python3 /tmp/m5-focus.py --all
```
Expected:第一次 `FOCUS … | Standby & Screensaver`;第二次 `FOCUS … | Standby Timeout / …`,`TEXTS` 里依次有 `Standby Timeout`、`Standby Display`、`Screensaver starts`、`After standby begins`、`Off`、`1 min`、`5 min`、`10 min`、`30 min`、`Slideshow interval`、`30 s`、`Screensaver gallery`、`View; hold OK to delete`、`System screensaver`、`Pick UnitedU in system settings`。截图 `/tmp/m5-shot.sh t4-standby-group` 用 Read 看:六行、小字在「Screensaver starts」下面、所有控件左边缘对齐。

提示随设置当场变(不退出设置页):
```bash
/tmp/m5-key.sh DPAD_LEFT DPAD_LEFT; python3 /tmp/m5-focus.py --all | grep -o "From the last key press\|After standby begins"
/tmp/m5-key.sh DPAD_RIGHT DPAD_RIGHT DPAD_DOWN DPAD_DOWN DPAD_LEFT DPAD_LEFT
python3 /tmp/m5-focus.py --all | grep -c "After standby begins\|From the last key press\|Gallery empty"
/tmp/m5-key.sh DPAD_RIGHT DPAD_RIGHT
```
Expected:第一条打印 `From the last key press`(待机时长被左键调到 Off);第二条打印 `0`(屏保启动被调到 Off,不画提示);最后两下右键回到 5 分。

图库为空的提示:
```bash
/tmp/m5-key.sh BACK
/tmp/m5-gallery.sh empty
/tmp/m5-key.sh MENU DPAD_DOWN DPAD_CENTER DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_RIGHT
python3 /tmp/m5-focus.py --all | grep -o "Gallery empty, won't start"
/tmp/m5-key.sh BACK
/tmp/m5-gallery.sh full
```
Expected:打印 `Gallery empty, won't start`。

两条动作行(焦点必须回到同一行):
```bash
/tmp/m5-key.sh MENU DPAD_DOWN DPAD_CENTER DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN
python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_CENTER; sleep 1; python3 /tmp/m5-focus.py --all | grep -o "Screensaver Gallery · 3 images[^|]*"
/tmp/m5-key.sh BACK; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_DOWN DPAD_CENTER; sleep 2
adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus
/tmp/m5-key.sh BACK; sleep 1; python3 /tmp/m5-focus.py
```
Expected:依次 `FOCUS … | Screensaver gallery / View; hold OK to delete`;查看器标题 `Screensaver Gallery · 3 images · Press OK to preview`;BACK 后焦点回到 `Screensaver gallery` 那一行;`mCurrentFocus` 是一个系统设置 Activity(把类名记进 WORKLOG:`ACTION_DREAM_SETTINGS` 在这台镜像上能不能解析、落到了哪一页);BACK 回来焦点在 `System screensaver` 那一行。

壁纸组改名 + 繁中宽度:
```bash
/tmp/m5-key.sh DPAD_LEFT DPAD_UP DPAD_UP DPAD_RIGHT; python3 /tmp/m5-focus.py --all | grep -o "Auto-change wallpaper"
/tmp/m5-key.sh BACK
/tmp/m5-setjson.sh language '"zh-TW"'
sleep 8
/tmp/m5-key.sh MENU DPAD_DOWN DPAD_CENTER DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_RIGHT
/tmp/m5-shot.sh t4-zhtw
/tmp/m5-key.sh BACK
/tmp/m5-setjson.sh language '"system"'
adb -s emulator-5554 shell cat /sdcard/Android/data/com.uniteduone.launcher/files/settings.json | grep screensaver
```
Expected:打印 `Auto-change wallpaper`;Read `/tmp/m5-t4-zhtw.png`:组名「待機與螢幕保護」、六行不折行不重叠、「螢幕保護啟動」下有「從進入待機算起」;settings.json 里有 `"screensaverAfterMs": 300000` 与 `"screensaverIntervalMs": 30000`。

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/SettingsModel.kt app/src/main/java/com/uniteduone/launcher/SettingsScreen.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/main/res/values-en/strings.xml app/src/test/java/com/uniteduone/launcher/SettingsModelTest.kt
git commit -m "feat(m5): settings Standby & Screensaver group with six rows and an inline note" -m "Co-Authored-By: Claude <实际模型> <noreply@anthropic.com>"
```

---

### Task 5: 屏保图库长按删图(确认框 + 焦点接回)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/ImagePicker.kt`(`PickerGrid` 加可选回调与持有者上报;`ScreensaverPoolViewer` 整个替换)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(两个字段;`PickerLayer` 的 `VIEW_SCREENSAVER_POOL` 分支;`dispatchKeyEvent` 新的一支;`openScreensaverPool()`;新增 `deletePoolImage()`)
- Modify: 三个 `strings.xml`(`pool_delete_*` 三个键)
- Modify: `CLAUDE.md`(铁律 3 的表加两行)

**Interfaces:**
- Consumes: `galleryVersion`(Task 4)、`ScreensaverPlayer.rescan`(Task 2)、现成的 `ConfirmDialog(title, body, okLabel, cancelLabel, nonce, onOk, onCancel)`(默认焦点在取消)、`R.string.dialog_cancel`。
- Produces:
  - `PickerGrid(..., onFocusedFile: ((File?) -> Unit)? = null)`(private;缩略图得到焦点报文件、失去报 null)
  - `fun ScreensaverPoolViewer(directory: File, nonce: Int = 0, refresh: Int = 0, onFocusedFile: (File?) -> Unit = {}, deleteTarget: File? = null, onConfirmDelete: (File) -> Unit = {}, onCancelDelete: () -> Unit = {}, onDismiss: () -> Unit)`
  - MainActivity:`poolFocusedFile: File?`、`poolDeleteTarget: File?`、`deletePoolImage(file: File)`
  - 字符串:`pool_delete_title`、`pool_delete_body`(`%1$s` = 文件名)、`pool_delete_ok`

- [ ] **Step 1: 三语文案**

三个文件都插在 `picker_adb_hint_screensaver` 那一行之后。

`values/strings.xml`:
```xml
    <!-- pool_delete_* : 屏保图库长按删图的确认框(M5 spec §5);取消复用 dialog_cancel -->
    <string name="pool_delete_title">删除这张图片?</string>
    <string name="pool_delete_body">「%1$s」会从屏保图库删除,无法恢复</string>
    <string name="pool_delete_ok">删除</string>
```
`values-zh-rTW/strings.xml`:
```xml
    <!-- pool_delete_* : 螢幕保護圖庫長按刪圖的確認框(M5 spec §5);取消複用 dialog_cancel -->
    <string name="pool_delete_title">刪除這張圖片?</string>
    <string name="pool_delete_body">「%1$s」會從螢幕保護圖庫刪除,無法復原</string>
    <string name="pool_delete_ok">刪除</string>
```
`values-en/strings.xml`(双引号在资源里要写 `\"`,否则会被当成引用符吃掉):
```xml
    <!-- pool_delete_* : long-press delete confirm in the screensaver gallery (M5 spec §5); cancel reuses dialog_cancel -->
    <string name="pool_delete_title">Delete this picture?</string>
    <string name="pool_delete_body">\"%1$s\" will be removed from the gallery for good</string>
    <string name="pool_delete_ok">Delete</string>
```

- [ ] **Step 2: `PickerGrid` 上报聚焦的文件**

签名里 `onDismiss: () -> Unit,` 之后插入:
```kotlin
    /**
     * 当前聚焦的图库文件(M5 spec §5,长按删图用):缩略图得到焦点报文件、失去报 null;只有屏保图库传它。
     * 全屏预览 / 确认框盖上来时网格失焦 → 报 null → 长按不生效。
     */
    onFocusedFile: ((File?) -> Unit)? = null,
```
`var landed by remember { mutableStateOf(false) }` 之后插入:
```kotlin
    /**
     * **现在**持有焦点的那一格(只信控件自报,铁律 4);null = 网格里没有。与 [focusedIdx] 分开(铁律 5):
     * 后者是「回来时落哪」的目标,失焦时不清;这一个失焦就清,长按判据只认它。
     */
    var holderIdx by remember { mutableStateOf<Int?>(null) }
```
`LaunchedEffect(nonce) { … }` 那一整块之后插入:
```kotlin
    // 上报只派生、不缓存(同 HomeScreen 的 onFocusedCard):删图后同一格换了文件、没有焦点事件,
    // items 变 → 这里按新列表再报一次。离开组合(关图库 / 删空换成空态)报 null,不留过期文件。
    if (onFocusedFile != null) {
        LaunchedEffect(holderIdx, items) {
            onFocusedFile((holderIdx?.let { items.getOrNull(it) } as? PickerItem.Library)?.file)
        }
        DisposableEffect(Unit) { onDispose { onFocusedFile(null) } }
    }
```
缩略图的
```kotlin
                            .onFocusChanged {
                                if (it.isFocused) { focusedIdx = idx; landed = true }
                            }
```
改为
```kotlin
                            .onFocusChanged {
                                if (it.isFocused) { focusedIdx = idx; landed = true }
                                // 得失顺序保护(同 HomeScreen.report):只有「本格仍是持有者」时 lost 才作废,
                                // 新格先报 got、旧格后报 lost 时不会把新格抹掉。
                                if (it.isFocused) holderIdx = idx else if (holderIdx == idx) holderIdx = null
                            }
```

- [ ] **Step 3: `ScreensaverPoolViewer` 整个替换(连同 KDoc)**

```kotlin
/**
 * 屏保图库:显示 library/screensavers/ 里的全部图片,确定键全屏预览;长按缩略图 → 删除确认框
 * (M5 spec §5;长按识别在 MainActivity.dispatchKeyEvent,这里只画与上报)。
 * [refresh] = 图库版本,删图后 +1,文件列表据此重读。
 * [deleteTarget] 非 null 时在自身之上画 [ConfirmDialog]:它自己负责焦点(nonce + focusedBtn,默认在取消);
 * 关掉后(删除 / 取消都 focusNonce++)由网格的 nonce 循环把焦点接回原位置——删掉的那格由下一张补上,
 * 删的是末张就夹到上一张(`focusedIdx` 夹到新长度);删空换成空态,空态自己的循环接住焦点。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ScreensaverPoolViewer(
    directory: File,
    nonce: Int = 0,
    refresh: Int = 0,
    onFocusedFile: (File?) -> Unit = {},
    deleteTarget: File? = null,
    onConfirmDelete: (File) -> Unit = {},
    onCancelDelete: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val files = remember(directory, refresh) {
        directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
    var previewIndex by remember { mutableStateOf(-1) }
    // 全屏预览关掉时,它拿着的焦点随节点一起销毁,外层没有人会补请求(铁律 3:浮层自己负责恢复)。
    // M5 让图库重新可达(设置页入口),这条路因此变成常规路径。本地计数并进网格的 nonce:两个量都只增不减,
    // 任何一个变了和就变,网格的初始焦点循环据此再跑一轮,落回 focusedIdx。
    var previewCloses by remember { mutableStateOf(0) }

    androidx.activity.compose.BackHandler { onDismiss() }
    Box(
        modifier = Modifier.fillMaxSize().focusGroup()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        if (files.isEmpty()) {
            PoolEmptyState(nonce, onDismiss)
        } else {
            PickerGrid(
                items = files.map { PickerItem.Library(it) },
                title = stringResource(R.string.picker_screensaver_pool_title, files.size),
                columns = 3,
                thumbWidth = 170.dp,
                thumbHeight = 96.dp,
                nonce = nonce + previewCloses,
                onSelectFile = { file ->
                    val idx = files.indexOf(file)
                    if (idx >= 0) previewIndex = idx
                },
                onRestoreOriginal = null,
                onDismiss = onDismiss,
                onFocusedFile = onFocusedFile,
            )
        }
    }

    if (previewIndex in files.indices) {
        ScreensaverPreview(
            files = files,
            startIndex = previewIndex,
            onDismiss = { previewIndex = -1; previewCloses++ },
        )
    }

    // 删除确认框(spec §5):画在最上层;BackHandler 比查看器的更晚注册,返回键先关它。
    val target = deleteTarget
    if (target != null) {
        ConfirmDialog(
            title = stringResource(R.string.pool_delete_title),
            body = stringResource(R.string.pool_delete_body, target.name),
            okLabel = stringResource(R.string.pool_delete_ok),
            cancelLabel = stringResource(R.string.dialog_cancel),
            nonce = nonce,
            onOk = { onConfirmDelete(target) },
            onCancel = onCancelDelete,
        )
    }
}
```

- [ ] **Step 4: `MainActivity.kt`**

`private var galleryVersion by mutableStateOf(0)` 之后插入:
```kotlin
    /**
     * 屏保图库网格当前聚焦的那张图(PickerGrid 上报:得到报文件、失去报 null)。长按确定键据此弹删除确认框。
     * 只在 [pickerTarget] == [VIEW_SCREENSAVER_POOL] 时有意义(长按那一支先判它);网格离开组合时报 null,
     * [openScreensaverPool] 打开时再清一次——下一次会话在第一次焦点上报之前也读不到上一次的旧文件。
     */
    private var poolFocusedFile by mutableStateOf<java.io.File?>(null)
    /**
     * 删除确认框开着的那张图;null = 没开(M5 spec §5)。确定([deletePoolImage] 删完才清)、取消 / 返回
     * (onCancelDelete)各自清它,[openScreensaverPool] 打开时再兜底清一次:不会有「上次没清掉、下次一打开图库
     * 就蹦出确认框」的路(铁律 7)。
     */
    private var poolDeleteTarget by mutableStateOf<java.io.File?>(null)
```

`PickerLayer` 里
```kotlin
            VIEW_SCREENSAVER_POOL -> ScreensaverPoolViewer(
                directory = Paths.screensaverLibrary(this),
                nonce = focusNonce,
                onDismiss = { pickerTarget = null; focusNonce++ },
            )
```
替换为:
```kotlin
            // M5 spec §5:长按缩略图删图。长按识别在 dispatchKeyEvent(「图库光着」那一支),这里只接线:
            // 网格上报聚焦的文件、确认框的目标与两个按钮。确认框自己负责焦点;关掉后(删除 / 取消都 focusNonce++)
            // 由网格的 nonce 循环把焦点接回原位置。
            VIEW_SCREENSAVER_POOL -> ScreensaverPoolViewer(
                directory = Paths.screensaverLibrary(this),
                nonce = focusNonce,
                refresh = galleryVersion,
                onFocusedFile = { poolFocusedFile = it },
                deleteTarget = poolDeleteTarget,
                onConfirmDelete = ::deletePoolImage,
                onCancelDelete = { poolDeleteTarget = null; focusNonce++ },
                onDismiss = { pickerTarget = null; focusNonce++ },
            )
```

`dispatchKeyEvent` 里,首页长按那一支(`if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && homeBare` 开头、以两层 `}` 收尾的 `if` 块)之后、`KEYCODE_DPAD_CENTER || KEYCODE_ENTER` 那个外层 `if` 的右花括号之前,插入:
```kotlin
            // 屏保图库「光着」(M5 spec §5):图库开着、确认框没开、网格上有聚焦的缩略图。满 LONG_PRESS_MS 弹删除
            // 确认框,整下吞掉(同 longPressDownTime 手法:UP 落不到缩略图上,不会顺带打开全屏预览)。
            // 与上面的 homeBare 天然互斥:homeBare 要求 !overlayOpen,而图库开着时 pickerTarget != null。
            // 全屏预览开着时网格失焦、poolFocusedFile 已报 null,这一支不成立 = 预览里长按无效。
            val poolBare = pickerTarget == VIEW_SCREENSAVER_POOL && poolDeleteTarget == null && poolFocusedFile != null
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && poolBare
                && event.eventTime - event.downTime >= LONG_PRESS_MS
            ) {
                longPressDownTime = event.downTime
                window.decorView.playSoundEffect(SoundEffectConstants.CLICK)
                poolDeleteTarget = poolFocusedFile
                return true
            }
```

`openScreensaverPool()` 整个替换为:
```kotlin
    /**
     * 屏保图库查看器的入口:设置页「待机与屏保 → 屏保图库 ▸」(M5 spec §3)。叠在设置页之上,
     * 关掉后 focusNonce++ 让设置页把焦点接回这一行(设置页 `covered` 期间冻结目标)。
     * 打开时清掉删图的两个量:它们只属于一次图库会话,旧值不能带进新会话(见两个字段的 KDoc)。
     */
    private fun openScreensaverPool() {
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        closeMenu()
        poolDeleteTarget = null
        poolFocusedFile = null
        pickerTarget = VIEW_SCREENSAVER_POOL
    }

    /**
     * 图库删图的「删除」键(M5 spec §5)。顺序照 spec:IO 线程删文件 → 播放器重扫 → 图库版本 +1 →
     * 收确认框 → focusNonce++(网格按 nonce 把焦点落回原位置)。确认框留到删完才收:收掉那一刻焦点随它的
     * 按钮销毁,紧接着的 nonce 让网格接回,中间没有「谁都不管」的空档。开头比对目标:过期的调用直接忽略。
     * 删不掉(文件还在)只记日志——列表按盘上实况重读,那张图留在原处,用户看得见结果。
     */
    private fun deletePoolImage(file: java.io.File) {
        if (poolDeleteTarget != file) return
        lifecycleScope.launch {
            val gone = withContext(Dispatchers.IO) { file.delete() || !file.exists() }
            if (!gone) android.util.Log.w("UnitedU", "屏保图删不掉: ${file.name}")
            ScreensaverPlayer.rescan(this@MainActivity)
            galleryVersion++
            poolDeleteTarget = null
            focusNonce++
        }
    }
```

- [ ] **Step 5: `CLAUDE.md` 铁律 3 的表加两行**

在
```
   | 图片选择器 / 屏保图库 / 默认桌面卡 / 导入图片页 | 各自的初始焦点循环(nonce) |
```
之后插入(行首同样缩进 3 个空格):
```
   | 屏保图库的删除确认框(M5) | ConfirmDialog 自己的 nonce + focusedBtn 循环(默认在取消);关掉后(删除 / 取消都 `focusNonce++`)由图库网格的 nonce 循环接回原位置,`focusedIdx` 夹到新长度;删空换成空态,空态自己的循环接住 |
   | 屏保图库的全屏预览(M5 起可达) | 自己的初始焦点循环;关掉时焦点随节点销毁,图库用本地计数 `previewCloses` 并进网格的 nonce,让网格循环再跑一轮接回 |
```

- [ ] **Step 6: 构建 + 单测**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL,测试全绿(数量同 Task 4)。

- [ ] **Step 7: 模拟器——删图全流程(spec §7「删图」)**

```bash
source scripts/env.sh
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
/tmp/m5-gallery.sh reset
adb -s emulator-5554 shell settings put secure long_press_timeout 700
/tmp/m5-setjson.sh idleAfterMs 180000
sleep 8
/tmp/m5-key.sh MENU DPAD_DOWN DPAD_CENTER DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN
python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_CENTER; sleep 1; python3 /tmp/m5-focus.py
```
Expected:先 `FOCUS … | Screensaver gallery / …`;进图库后 `FOCUS … | m5-test-0`。

**预览里长按无效,关预览焦点回网格**:
```bash
/tmp/m5-key.sh DPAD_CENTER; sleep 1
adb -s emulator-5554 shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 1
/tmp/m5-shot.sh t5-preview-lp; python3 /tmp/m5-probe.py /tmp/m5-t5-preview-lp.png
/tmp/m5-key.sh BACK; sleep 1; python3 /tmp/m5-focus.py
```
Expected:probe `photo=red`(仍是全屏预览;确认框若弹出,0.72 黑罩会让这一点变暗成 `none`);BACK 后 `FOCUS … | m5-test-0`(预览里 uiautomator 可能因 Ken Burns 动画 dump 失败,所以预览里只用截图判)。

**取消不删**:
```bash
/tmp/m5-key.sh DPAD_RIGHT; python3 /tmp/m5-focus.py
adb -s emulator-5554 shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 1
python3 /tmp/m5-focus.py --all
/tmp/m5-key.sh DPAD_CENTER; sleep 1
python3 /tmp/m5-focus.py; python3 /tmp/m5-focus.py --all | grep -o "Screensaver Gallery · [0-9] images"
/tmp/m5-shot.sh t5-after-cancel
/tmp/m5-gallery.sh
```
Expected:`FOCUS … | m5-test-1`;确认框里 `FOCUS … | Cancel`,`TEXTS` 有 `Delete this picture?` 与 `"m5-test-1.jpg" will be removed from the gallery for good`;取消后 `FOCUS … | m5-test-1`、`· 3 images`、`gallery: m5-test-0.jpg m5-test-1.jpg m5-test-2.jpg`。Read `/tmp/m5-t5-after-cancel.png`:m5-test-1 那张缩略图不该比邻居明显发暗(若 `clickable` 的按压指示残留——那次按压的 UP 被吞了——在 WORKLOG 记为已知外观问题,下一次按确定键就会复原,本任务不修)。

**删除 → 焦点落到补上来的下一张**:
```bash
adb -s emulator-5554 shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 1
/tmp/m5-key.sh DPAD_RIGHT; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_CENTER; sleep 1
python3 /tmp/m5-focus.py; python3 /tmp/m5-focus.py --all | grep -o "Screensaver Gallery · [0-9] images"
/tmp/m5-gallery.sh
```
Expected:`FOCUS … | Delete`;删后 `FOCUS … | m5-test-2`、`· 2 images`、`gallery: m5-test-0.jpg m5-test-2.jpg`。

**删末张 → 夹到上一张;删空 → 空态**:
```bash
adb -s emulator-5554 shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 1
/tmp/m5-key.sh DPAD_RIGHT DPAD_CENTER; sleep 1; python3 /tmp/m5-focus.py
adb -s emulator-5554 shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 1
/tmp/m5-key.sh DPAD_RIGHT DPAD_CENTER; sleep 1; python3 /tmp/m5-focus.py --all
```
Expected:第一次 `FOCUS … | m5-test-0`(1 张);第二次 `TEXTS` 有 `No screensaver images yet`,`FOCUS … | Press Back to close`。

**回设置页:同一行 + 提示变成「图库为空」**:
```bash
/tmp/m5-key.sh BACK; sleep 1
python3 /tmp/m5-focus.py; python3 /tmp/m5-focus.py --all | grep -o "Gallery empty, won't start"
/tmp/m5-key.sh BACK
/tmp/m5-gallery.sh reset
```
Expected:`FOCUS … | Screensaver gallery / …`;打印 `Gallery empty, won't start`;最后图库回到三张。(`long_press_timeout` 留在 700,Task 7 还要用,夹具还原时改回。)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/ImagePicker.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/main/res/values-en/strings.xml CLAUDE.md
git commit -m "feat(m5): delete screensaver images by long press with a confirm dialog" -m "Co-Authored-By: Claude <实际模型> <noreply@anthropic.com>"
```

---

### Task 6: 系统屏保 `UnitedUDream`(Compose 宿主 + 共用播放器 + 同一条主题色解析)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/ThemeResolve.kt`(`rememberThemeColors` + 从 MainActivity 搬来的 `wallpaperThemeColors`)
- Create: `app/src/main/java/com/uniteduone/launcher/UnitedUDream.kt`
- Create: `app/src/main/res/xml/dream.xml`
- Modify: `app/src/main/AndroidManifest.xml`(`</application>` 之前加 `<service>`)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(`setContent` 里主题色那一段;删文件末尾 private 的 `wallpaperThemeColors`)
- Modify: `CLAUDE.md`(「模拟器验证的坑」加一条)

**Interfaces:**
- Consumes: `ScreensaverPlayer` / `ScreensaverContent`(Task 2)、`HeroClock(shadow)`(Task 3)、`Settings.screensaverIntervalMs`(Task 1)、`SettingsStore.read`、`localeFor` / `AppLocale`、`UnitedUTheme`、`LocalThemeColors`、`Theme.SidePadding`、`HomeLayout.HERO_TOP`。
- Produces:
  - `@Composable fun rememberThemeColors(ctx: Context, s: Settings, refresh: Int = 0): ThemeColors`
  - `internal fun wallpaperThemeColors(ctx: Context, wallpaperFile: String): ThemeColors?`(原 MainActivity 私有顶层函数,逐字搬家)
  - `class UnitedUDream : DreamService(), SavedStateRegistryOwner`(清单名 `.UnitedUDream`,label = `@string/app_name`)

- [ ] **Step 1: `ThemeResolve.kt`**

```kotlin
package com.uniteduone.launcher

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主题色的**唯一**解析路径(M5 spec §4:桌面与系统屏保同一条路)。选中预设的两种角色色同步给出;
 * followWallpaperColor 打开时在 IO 线程从壁纸主色取 accent,取到之前、或取不到时回落预设。
 * [refresh] 变了就重取(MainActivity 传 revision:重扫后「当前壁纸」可能换了文件)。
 * 从 MainActivity 的 setContent 里原样搬出(M5 Task 6),key 与回落规则一字未改。
 */
@Composable
fun rememberThemeColors(ctx: Context, s: Settings, refresh: Int = 0): ThemeColors {
    val preset = remember(s.themePresetId) { ThemePresets.byId(s.themePresetId).colors() }
    val fromWallpaper by produceState<ThemeColors?>(null, s.followWallpaperColor, s.wallpaperFile, refresh) {
        value = if (!s.followWallpaperColor) null
        else withContext(Dispatchers.IO) { wallpaperThemeColors(ctx, s.wallpaperFile) }
    }
    return if (s.followWallpaperColor) fromWallpaper ?: preset else preset
}

/**
 * followWallpaperColor 打开时,从当前壁纸主色推导界面强调色(经 LocalThemeColors 供给每个界面)。
 * **必须在 IO 线程调用**:取色会解一张缩略图并跑 Palette(实现见 [Wallpapers.paletteAccent])。
 *
 * accent 用取到的色,highlight 由 [highlightFrom] 混白 55% 推得 —— 与非金预设 highlight 同一手法。
 * 任何一步落空(没壁纸、解不出、Palette 抽不到色)返回 null,调用方回落到选中预设,绝不崩、绝不留黑。
 *
 * 取到的色只喂界面强调色,壁纸本身**不染色**(「主题化壁纸」2026-09-16 删掉,壁纸管线不再认识主题色)。
 * (M5 从 MainActivity.kt 的 private 顶层函数搬来:系统屏保也要用。)
 */
internal fun wallpaperThemeColors(ctx: Context, wallpaperFile: String): ThemeColors? =
    Wallpapers.resolveSource(ctx, wallpaperFile)
        ?.let { Wallpapers.paletteAccent(ctx, it) }
        ?.let { rgb ->
            // 壁纸主色可能很暗 / 很灰,先提亮到可读地板再当强调色(见 usableAccent);
            // 否则深色主题色压在 #0A0A0A 的设置页上,分组标题等文字直接消失。
            val accent = Color(usableAccent(rgb) or 0xFF000000.toInt())
            ThemeColors(accent, highlightFrom(accent))
        }
```

- [ ] **Step 2: MainActivity 改走 `rememberThemeColors`**

`setContent` 里从 `val presetColors = remember(homeSettings.themePresetId) {` 到
```kotlin
            val themeColors =
                if (homeSettings.followWallpaperColor) wallpaperColors ?: presetColors
                else presetColors
```
为止的三个声明整段替换为(上面那 6 行 `// 主题色:…` 注释保留):
```kotlin
            // M5:解析本体搬到 ThemeResolve.kt 的 rememberThemeColors——系统屏保(UnitedUDream)走同一条路(spec §4)。
            val themeColors = rememberThemeColors(this@MainActivity, homeSettings, revision)
```
然后删除文件末尾的 `/** followWallpaperColor 打开时,从当前壁纸主色推导界面强调色 … */` KDoc 与 `private fun wallpaperThemeColors(…)` 整个函数(已搬进 ThemeResolve.kt)。

- [ ] **Step 3: `UnitedUDream.kt`**

```kotlin
package com.uniteduone.launcher

import android.content.pm.ActivityInfo
import android.service.dreams.DreamService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 系统屏保(M5 spec §4):UnitedU 在电视「屏幕保护程序」列表里的那一项。画面与桌面的自定义屏保一致——
 * 全屏轮播屏保图库 + 左上大字时钟(淡阴影);图库为空时黑底 + 时钟。与桌面**共用播放器和播放进度**
 * ([ScreensaverPlayer] 的 attach 不重置下标):桌面先进了屏保、系统屏保随后接管时,从同一张接着播。
 *
 * **Compose 宿主**(spec §9 风险一):DreamService 不是 LifecycleOwner,ComposeView 挂上窗口时要沿 View 树
 * 找到 LifecycleOwner 与 SavedStateRegistryOwner,少一个就在 attach 时崩。所以这里自己当这两个 owner,
 * 在 setContentView **之前**把它们设到 ComposeView 与 window.decorView 上。生命周期:
 * onCreate → CREATED;onDreamingStarted → RESUMED(帧时钟随 ON_START 恢复,动画才会动)+ attach;
 * onDreamingStopped → CREATED + detach;onDetachedFromWindow → DESTROYED(detach 兜底)。
 *
 * **冷进程**(spec §9 风险二):系统屏保可能在 UnitedU 进程不在时被拉起,MainActivity 没跑过——
 * 语言(AppLocale)、主题色、间隔都在这里自己从 settings.json 读;外置存储没挂时 SettingsStore 给默认值、
 * 播放器扫出空图库 → 黑底 + 时钟。任意键结束屏保是系统行为(isInteractive = false),这里不处理按键。
 */
class UnitedUDream : DreamService(), SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /**
     * 这一次屏保是否拿着播放器的一个引用。attach / detach 必须成对:多 detach 一次会抢走桌面那一份。
     * 两条路把它写回 false(onDreamingStopped、onDetachedFromWindow 兜底),不是只有一条窄路的闩(铁律 7)。
     */
    private var holdsPlayer = false
    private var intervalMs = Theme.ScreensaverIntervalMs

    override fun onCreate() {
        super.onCreate()
        // savedstate 规定:performRestore 必须在 owner 离开 INITIALIZED 之前调,之后调会抛。
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        // 与 MainActivity 同:RGBA_F16 解码的 Ultra HDR 图要 HDR 窗口才亮得出来(画面一致,spec §4)。
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        // 冷进程时 MainActivity.attachBaseContext 没跑过:AppLocale 还是 null,日期的星期会跟系统语言走。
        val settings = SettingsStore.read(this)
        AppLocale.current = localeFor(settings.language)
        val interval = settings.screensaverIntervalMs
        intervalMs = interval
        val view = ComposeView(this)
        // 两个 owner 先设再 setContentView:ComposeView 在挂上窗口那一刻沿 View 树往上找它们。
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setContent { DreamContent(settings, interval) }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (!holdsPlayer) {
            ScreensaverPlayer.attach(this, intervalMs)
            holdsPlayer = true
        }
    }

    override fun onDreamingStopped() {
        releasePlayer()
        // 只从「开着」退回 CREATED:个别固件先拆窗(已 DESTROYED)再报停,不能倒着走回 CREATED。
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        // 兜底:拆窗前没收到 onDreamingStopped 时,引用也不能漏还。
        releasePlayer()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }

    private fun releasePlayer() {
        if (holdsPlayer) {
            ScreensaverPlayer.detach()
            holdsPlayer = false
        }
    }
}

/**
 * 系统屏保的画面:与桌面自定义屏保同一个轮播层 + 同一个大字时钟,位置与首页相同(左 58 dp、顶 150 dp)。
 * 图库为空 → 黑底 + 时钟,不加阴影(黑底上不需要,spec §4)。主题色走 [rememberThemeColors],与 MainActivity 同一条路。
 */
@Composable
private fun DreamContent(settings: Settings, intervalMs: Long) {
    val colors = rememberThemeColors(LocalContext.current, settings)
    UnitedUTheme(colors) {
        CompositionLocalProvider(LocalThemeColors provides colors) {
            val files by ScreensaverPlayer.files.collectAsState()
            val hasImages = files.isNotEmpty()
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (hasImages) ScreensaverContent(intervalMs = intervalMs, modifier = Modifier.fillMaxSize())
                HeroClock(
                    showDate = settings.showDate,
                    shadow = hasImages,
                    modifier = Modifier.padding(start = Theme.SidePadding, top = HomeLayout.HERO_TOP.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 4: `res/xml/dream.xml` + 清单**

`app/src/main/res/xml/dream.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- M5 系统屏保的元数据(spec §4,取自 spike 4f184b7)。不设 settingsActivity:屏保的设置都在 UnitedU 设置页「待机与屏保」组。 -->
<dream xmlns:android="http://schemas.android.com/apk/res/android" />
```
`AndroidManifest.xml` 里 FileProvider 的 `</provider>` 之后、`</application>` 之前插入:
```xml

        <!-- M5 系统屏保(spec §4):UnitedU 在电视「屏幕保护程序」列表里的那一项,画面与桌面自定义屏保一致、
             共用播放进度。BIND_DREAM_SERVICE = 只有系统能绑定;系统来绑,exported 必须为 true。
             清单条目取自 spike 4f184b7(2026-09-16 A95L 上确认会出现在系统屏保列表里)。 -->
        <service
            android:name=".UnitedUDream"
            android:exported="true"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_DREAM_SERVICE">
            <intent-filter>
                <action android:name="android.service.dreams.DreamService" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <meta-data
                android:name="android.service.dream"
                android:resource="@xml/dream" />
        </service>
```
(R8:清单里的组件 AGP 自动生成 keep 规则,`proguard-rules.pro` 不用加。)

- [ ] **Step 5: 构建 + 单测**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL,测试全绿;`grep -n "wallpaperThemeColors" app/src/main/java/com/uniteduone/launcher/*.kt` 只剩 `ThemeResolve.kt` 两处(定义 + 调用)。
**若 `setViewTreeLifecycleOwner` / `setViewTreeSavedStateRegistryOwner` 报 Unresolved**:它们是 lifecycle-runtime 2.8.3 与 savedstate 1.2.1 里的 Kotlin 扩展(Java 名 `ViewTreeLifecycleOwner.set` / `ViewTreeSavedStateRegistryOwner.set`,已核对 AAR),经 activity 1.9.3 的 api 依赖在编译类路径上;先 `gradle --no-daemon :app:dependencies --configuration releaseCompileClasspath | grep -E "lifecycle-runtime|savedstate"` 确认,不要先加依赖。

- [ ] **Step 6: 模拟器——登记、找启动命令、四个场景(spec §7「系统屏保」)**

登记与原值:
```bash
source scripts/env.sh
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5554 shell pm query-services -a android.service.dreams.DreamService | grep -i uniteduone
for k in screensaver_components screensaver_enabled screensaver_activate_on_sleep; do echo "$k=$(adb -s emulator-5554 shell settings get secure $k)"; done | tee /tmp/m5-orig-dream.txt
adb -s emulator-5554 shell settings put secure screensaver_components com.uniteduone.launcher/.UnitedUDream
adb -s emulator-5554 shell settings put secure screensaver_enabled 1
adb -s emulator-5554 shell cmd dreams 2>&1 | head -20
```
Expected:`query-services` 列出 `com.uniteduone.launcher/.UnitedUDream`;`cmd dreams` 打印帮助——看有没有 `start-dreaming`。

启动脚本(按顺序试,打印用的是哪条):
```bash
cat > /tmp/m5-dream.sh <<'EOF'
#!/bin/bash
# /tmp/m5-dream.sh:在模拟器上立即启动当前选中的系统屏保。按顺序试,第一条成功即止;打印用的是哪一条。
A="adb -s emulator-5554"
if $A shell cmd dreams 2>&1 | grep -q start-dreaming; then
  $A shell cmd dreams start-dreaming && echo "started via: cmd dreams start-dreaming" && exit 0
fi
out=$($A shell am start -n com.android.systemui/.Somnambulator 2>&1)
if ! echo "$out" | grep -qi "error"; then echo "started via: Somnambulator"; exit 0; fi
echo "no direct start command on this image: $out" >&2
exit 1
EOF
chmod +x /tmp/m5-dream.sh
```
两条都不行(脚本 exit 1)时的退路:先 `adb -s emulator-5554 shell settings get system screen_off_timeout` 记下原值,再 `settings put system screen_off_timeout 15000` + `settings put secure screensaver_activate_on_sleep 1`,不碰遥控器等 25 s,屏保会按「该睡了」启动;下面每个场景里的 `/tmp/m5-dream.sh` 换成这段等待,测完把 `screen_off_timeout` 改回原值。**用的是哪条记进 WORKLOG。**

**场景 1 · 首页正常态触发系统屏保**:
```bash
/tmp/m5-setjson.sh idleContent '"CLOCK_ONLY"' idleAfterMs 180000 screensaverAfterMs 300000
sleep 8
adb -s emulator-5554 logcat -b crash -c
/tmp/m5-dream.sh; sleep 4
adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus
/tmp/m5-shot.sh t6-dream; python3 /tmp/m5-probe.py /tmp/m5-t6-dream.png
```
Expected:`mCurrentFocus` 是屏保窗口(DreamActivity 一类,类名记进 WORKLOG);`photo=<颜色> under=<同色> clockMax=`≥ 140。Read `/tmp/m5-t6-dream.png`:时钟在与首页相同的左上位置、带淡阴影、日期在下。结束:
```bash
/tmp/m5-key.sh DPAD_CENTER; sleep 2
adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus
python3 /tmp/m5-focus.py
```
Expected:回到 `com.uniteduone.launcher/…MainActivity`;焦点在屏保前那张卡(冷启动后的第一张)。

**场景 2 · 桌面先进自定义屏保,系统屏保接班 → 同一张接着播**:
```bash
/tmp/m5-key.sh DPAD_UP DPAD_RIGHT; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_CENTER; sleep 3
/tmp/m5-shot.sh t6-desk; python3 /tmp/m5-probe.py /tmp/m5-t6-desk.png
/tmp/m5-dream.sh; sleep 3
/tmp/m5-shot.sh t6-handover; python3 /tmp/m5-probe.py /tmp/m5-t6-handover.png
/tmp/m5-key.sh DPAD_CENTER; sleep 2
adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus
```
Expected:两次 `photo=` 同一个颜色(下标没有被重置;若恰好碰上一次换图,接班那张是顺序里的下一种颜色 red → green → blue,重跑一次确认);结束后前台回到 UnitedU。

**场景 3 · 图库为空 → 黑底 + 时钟**:
```bash
/tmp/m5-gallery.sh empty
/tmp/m5-dream.sh; sleep 4
/tmp/m5-shot.sh t6-empty; python3 /tmp/m5-probe.py /tmp/m5-t6-empty.png
/tmp/m5-key.sh DPAD_CENTER; sleep 2
/tmp/m5-gallery.sh full
```
Expected:`photo=none under=none pixel=(0, 0, 0) clockMax=`≥ 140。

**场景 4 · 冷进程拉起,结束后回到原应用**:
```bash
adb -s emulator-5554 shell monkey -p com.google.android.youtube.tv -c android.intent.category.LEANBACK_LAUNCHER 1; sleep 4
adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus
adb -s emulator-5554 shell am force-stop com.uniteduone.launcher
adb -s emulator-5554 shell pidof com.uniteduone.launcher; echo "pidof-exit=$?"
/tmp/m5-dream.sh; sleep 5
adb -s emulator-5554 shell pidof com.uniteduone.launcher
/tmp/m5-shot.sh t6-cold; python3 /tmp/m5-probe.py /tmp/m5-t6-cold.png
/tmp/m5-key.sh DPAD_CENTER; sleep 2
adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus
adb -s emulator-5554 logcat -b crash -d | grep -c uniteduone
adb -s emulator-5554 shell am start -n com.uniteduone.launcher/.MainActivity
```
Expected:前台先是 YouTube;force-stop 后 `pidof` 无输出(`pidof-exit=1`);屏保起来后 `pidof` 打印一个 pid(进程只为屏保而起);`photo=<颜色> clockMax=`≥ 140(主题色与语言都来自 settings.json);按键后前台回到 YouTube;崩溃计数 `0`。本机没有 YouTube 时,用 `adb -s emulator-5554 shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER` 挑一个 UnitedU 以外的全屏应用,同样用 monkey 拉起(**不要用系统设置**:它是半透明侧边面板,UnitedU 在它底下仍是可见的)。

还原两个安全设置键:
```bash
while IFS='=' read -r k v; do
  if [ "$v" = null ]; then adb -s emulator-5554 shell settings delete secure "$k"; else adb -s emulator-5554 shell settings put secure "$k" "$v"; fi
done < /tmp/m5-orig-dream.txt
for k in screensaver_components screensaver_enabled screensaver_activate_on_sleep; do echo "$k=$(adb -s emulator-5554 shell settings get secure $k)"; done | diff - /tmp/m5-orig-dream.txt && echo restored
```
Expected:打印 `restored`。任何一步崩溃(`AndroidRuntime` / crash 缓冲里有 `UnitedUDream`):先看是不是 ViewTree owner 或 savedstate 顺序问题;修不好就按 spec §9 的退路(`ImageView` 双缓冲 + `TextView` 的纯 View 实现)**停下来向 controller 报告**,不要自行换方案。

- [ ] **Step 7: `CLAUDE.md` 记下启动办法**

「模拟器验证的坑」列表最后加一条(`<实测可用的启动方式>` 换成 Step 6 实测可用的那条命令,原样照抄):
```
- 系统屏保(M5 `UnitedUDream`):只在模拟器上 `settings put secure screensaver_components com.uniteduone.launcher/.UnitedUDream` + `settings put secure screensaver_enabled 1`,再用 `<实测可用的启动方式>` 立即启动;任意键结束。测完按原值还原这两个键(真机的屏保由 Gordon 在系统设置里开)。
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/ThemeResolve.kt app/src/main/java/com/uniteduone/launcher/UnitedUDream.kt app/src/main/res/xml/dream.xml app/src/main/AndroidManifest.xml app/src/main/java/com/uniteduone/launcher/MainActivity.kt CLAUDE.md
git commit -m "feat(m5): UnitedUDream system screensaver on a Compose host, sharing the player and theme path" -m "Co-Authored-By: Claude <实际模型> <noreply@anthropic.com>"
```

---

### Task 7: 终验(模拟器)+ 文档同步 + A95L 清单

**Files:**
- Modify: `docs/DESIGN-unitedu-open-source.md`(§4「现状机制」段、§4「状态模型」段末一句、§11「现状与后续路线」一句)
- Modify: `docs/WORKLOG.md`(文末新一节)
- 代码无改动;验证中发现问题就在本任务内修、单独 commit(`fix(m5): …`),修完重跑受影响的那一步。

- [ ] **Step 1: 全量**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease --rerun-tasks`
Expected: BUILD SUCCESSFUL;`app/build/reports/tests/testReleaseUnitTest/index.html` 里 0 failures,总数 = 基线 178 + 本里程碑 22 = 200(基线若不是 178,以「基线 + 22」为准)。再跑:
```bash
git diff main -- app/src/main | grep -E "^\+.*(LazyRow|LazyColumn|verticalScroll|horizontalScroll)" | wc -l
```
Expected:`0`(没有新增可滚动容器——铁律 1)。

- [ ] **Step 2: 最终 APK 上重跑时间线 A(Task 5 / 6 之后 MainActivity 又改过)**

```bash
source scripts/env.sh
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
/tmp/m5-gallery.sh reset
/tmp/m5-setjson.sh idleContent '"CLOCK_ONLY"' idleAfterMs 60000 screensaverAfterMs 60000 screensaverIntervalMs 30000
sleep 8
/tmp/m5-key.sh DPAD_RIGHT DPAD_RIGHT
python3 /tmp/m5-focus.py | tee /tmp/m5-t7-focus-before.txt
/tmp/m5-timeline.sh t7a
/tmp/m5-key.sh DPAD_CENTER; sleep 1
python3 /tmp/m5-focus.py | diff - /tmp/m5-t7-focus-before.txt && echo focus-unchanged
adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus
```
Expected:与 Task 3 Step 8 场景 A 相同的三行判据;打印 `focus-unchanged`;前台仍是 UnitedU。

- [ ] **Step 3: 轮播间隔生效(spec §7「设置页」:30 秒 / 1 分可计时)**

```bash
cat > /tmp/m5-interval.sh <<'EOF'
#!/bin/bash
# /tmp/m5-interval.sh SECONDS:每 5 s 截一张,持续 SECONDS 秒,打印「epoch 秒 颜色」;颜色一变就是换图
# (交叉淡入的 2 s 里可能采到 photo=none,算作那一次换图)。
end=$(( $(date +%s) + $1 ))
while [ "$(date +%s)" -lt "$end" ]; do
  adb -s emulator-5554 exec-out screencap -p > /tmp/m5-iv.png
  echo "$(date +%s) $(python3 /tmp/m5-probe.py /tmp/m5-iv.png | cut -d' ' -f1)"
  sleep 5
done
EOF
chmod +x /tmp/m5-interval.sh
/tmp/m5-setjson.sh idleAfterMs 180000 screensaverAfterMs 300000 screensaverIntervalMs 60000
sleep 8
/tmp/m5-key.sh DPAD_UP DPAD_RIGHT; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_CENTER
/tmp/m5-interval.sh 150
/tmp/m5-key.sh DPAD_RIGHT
/tmp/m5-setjson.sh screensaverIntervalMs 30000
sleep 8
/tmp/m5-key.sh DPAD_UP DPAD_RIGHT; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_CENTER
/tmp/m5-interval.sh 80
/tmp/m5-key.sh DPAD_RIGHT
```
Expected:第一段颜色按 red → green → blue 的顺序变,相邻两次变化相隔 60 s ± 6 s;第二段相隔 30 s ± 6 s。(每次按钮前的 `FOCUS` 必须是右上角的 `Screensaver`,y < 200。)

- [ ] **Step 4: 长按两个入口互斥(spec §9 风险四)**

```bash
/tmp/m5-setjson.sh idleAfterMs 180000
sleep 8
adb -s emulator-5554 shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 1
python3 /tmp/m5-focus.py --all | grep -o "Open App\|Delete this picture?"
/tmp/m5-key.sh BACK
/tmp/m5-key.sh MENU DPAD_DOWN DPAD_CENTER DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER
sleep 1
adb -s emulator-5554 shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 1
python3 /tmp/m5-focus.py --all | grep -o "Open App\|Delete this picture?"
/tmp/m5-key.sh BACK BACK
```
Expected:首页那次只打印 `Open App`(卡片长按菜单);图库那次只打印 `Delete this picture?`。

- [ ] **Step 5: 设置页新行的上下左右(焦点七条)**

接着上一步(设置页开着、焦点在「Screensaver gallery」行):
```bash
/tmp/m5-key.sh DPAD_UP DPAD_UP; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_UP; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_DOWN; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_RIGHT; python3 /tmp/m5-focus.py
/tmp/m5-key.sh DPAD_LEFT; python3 /tmp/m5-focus.py
/tmp/m5-key.sh BACK
```
Expected:依次 `Screensaver starts`、`Standby Display`、`System screensaver`、仍是 `System screensaver`(末行下键锁住,焦点不消失)、仍是 `System screensaver`(动作行右键消费掉)、左栏 `Standby & Screensaver`。**每一次都有 `FOCUS:` 行,不能出现 `FOCUS: <none>`。**

- [ ] **Step 6: 还原夹具**

```bash
D=/sdcard/Android/data/com.uniteduone.launcher/files
adb -s emulator-5554 shell "rm -f $D/library/screensavers/m5-test-*"
adb -s emulator-5554 shell "mv $D/m5-hold/orig-screensavers/* $D/library/screensavers/ 2>/dev/null; true"
adb -s emulator-5554 shell "cp $D/m5-hold/settings.json.orig $D/settings.json"
adb -s emulator-5554 shell "rm -rf $D/m5-hold"
v=$(cat /tmp/m5-orig-long-press.txt)
if [ "$v" = null ]; then adb -s emulator-5554 shell settings delete secure long_press_timeout; else adb -s emulator-5554 shell settings put secure long_press_timeout "$v"; fi
adb -s emulator-5554 shell am force-stop com.uniteduone.launcher
adb -s emulator-5554 shell am start -n com.uniteduone.launcher/.MainActivity
adb -s emulator-5554 shell ls $D/library/screensavers/
adb -s emulator-5554 shell settings get secure long_press_timeout
```
Expected:图库里只剩夹具之前的原图(可能为空);`long_press_timeout` 回到 `/tmp/m5-orig-long-press.txt` 里的值。两个屏保安全设置键 Task 6 已还原。

- [ ] **Step 7: DESIGN 同步**

`docs/DESIGN-unitedu-open-source.md` §4,把以 `**现状机制**(改动前必须懂):应用只有一个计时器` 开头、以 `产品用户没有。` 结尾的那一段整段替换为:
```markdown
**现状机制**(M5 起,改动前必须懂):`MainActivity` 只持有一个 `StandbyFlags`(`idle` + `screensaverActive` 两个布尔量,构造函数钉死「屏保 ⇒ 待机」)。纯函数 `standbyPlan(待机时长, 屏保启动)` 给出两个时刻(都从最后一次按键算),计时效果先写待机、到点在 IO 线程查图库非空再写屏保;首页「屏保」按钮直接写屏保(图库空时退为待机,「不淡出」时空操作);任意键回正常,那一下被吞。轮播归进程级单例 `ScreensaverPlayer`(扫描、下标、唯一一份换图计时、引用计数),桌面屏保层与系统屏保 `UnitedUDream` 都只读它,所以系统屏保从桌面那一张接着播。系统屏保(`screensaver_enabled=0`)与 63 分钟灭屏(`screen_off_timeout`)是 Gordon 用 adb 手设的,产品用户没有。
```
同一节「状态模型」段里的 `**今天的实现违反这条模型**:一进待机、图库有图就叠轮播,「待机显示 = 时钟」名不副实,M5 修。` 替换为 `M5 已按这条模型实现(见上「现状机制」)。`(其后「2026-09-18 版…作废。」一句保留)。
§11 里的 `进行中:M5 待机与屏保(spec \`docs/superpowers/specs/2026-09-19-m5-standby-screensaver-design.md\`)。` 替换为 `M5 待机与屏保已实施(spec \`docs/superpowers/specs/2026-09-19-m5-standby-screensaver-design.md\`、plan \`docs/superpowers/plans/2026-09-19-m5-standby-screensaver.md\`),待 A95L 验收。`

- [ ] **Step 8: WORKLOG 新一节**

`docs/WORKLOG.md` 文末追加(尖括号处填本次实测值;没有的项写「未验证」及原因,不许空着):
```markdown
## <执行当日日期> · M5 待机与屏保:实施完成 + 模拟器验证

HEAD `<sha>`,worktree `m5-standby`,模拟器 `emulator-5554`。截图 `/tmp/m5-*.png`;夹具(settings.json、原图库、`long_press_timeout`、两个屏保安全设置键)已还原。

- **单测 / 构建**:<总数> tests,0 failures;APK <字节数>。
- **时间线**(Task 3 / Task 7 Step 2):A 时钟 / B 全黑 / C 图库空 / D 待机关 / E 屏保关,各一行「20 s / 75 s / 135 s 的 probe 输出 → 结论」。
- **屏保按钮**:F1 有图(含「75 s 后仍是照片,没被降回待机」)/ F2 空图库 + 时钟 / F3 空图库 + 不淡出(下一个键没被吞)/ F4 有图 + 不淡出(卡片不浮在照片上)。
- **设置页**(Task 4):六行;提示三种情况 + 屏保启动「关」时不画;`ACTION_DREAM_SETTINGS` 在模拟器上落到 <Activity 类名 / 退到了系统设置首页>;壁纸组改名;繁中截图 `/tmp/m5-t4-zhtw.png` 不折行。
- **删图**(Task 5):取消不删;删中间 → 焦点落下一张;删末张 → 落上一张;删空 → 空态;预览里长按无效、关预览焦点回网格;按压指示残留:<有 / 无>。
- **系统屏保**(Task 6):模拟器上可用的启动办法 <命令>;屏保窗口 <类名>;画面 = 照片 + 带阴影的时钟;桌面接班同一张 <颜色>;空图库黑底 + 时钟;冷进程(<pid>)正常、结束后回到 YouTube;crash 缓冲 0 条。
- **轮播间隔**(Task 7 Step 3):60 s 档实测相邻换图 <…> s;30 s 档 <…> s。
- **焦点七条**:唤醒后焦点原位;确认框与网格的焦点接回;设置页新行上下左右无 `<none>`;长按两个入口互斥。
- **已知未修**:`PickerGrid` 仍用 `verticalScroll`(M5 之前就有,违反铁律 1;图库超过约 15 张才会滚,真机未测);全屏预览开着时回到前台(如系统屏保结束),网格的 nonce 循环会把焦点从预览上拿走(M5 之前就有)。

**A95L 真机清单**(Gordon 做):
1. 时间线手感:设置页「待机与屏保」把待机时长设 1 分、屏保启动设 1 分,放着不动——1 分后卡片淡出只留时钟,再 1 分照片轮播 + 带淡阴影的时钟;按任意键回到原来那张卡,这一下不会启动应用。看完改回自己习惯的值(你原来是待机 3 分;屏保启动默认 5 分)。
2. 屏保画面:照片亮的时候大字时钟看得清;换图 2 秒交叉淡入 + 缓慢放大不卡。
3. 右上「屏保」按钮:按下立刻进照片轮播,不经过待机。
4. 系统屏保:电视「设置 → 屏幕保护程序」先把屏保打开(你之前用 adb 关过),在列表里选 UnitedU——也可以从 UnitedU 设置页「系统屏保 ▸」跳过去,顺便看这一行在索尼上能不能打开对的页面;「立即启动」或等系统时长:照片 + 时钟正常;UnitedU 自己的屏保开着时触发系统屏保,照片从同一张接着播;按任意键回到原来的应用。
5. 屏保图库:「屏保图库 ▸」看图;长按缩略图约 0.6 秒弹「删除这张图片?」,默认焦点在「取消」;删一张后焦点落在补上来的那张。
6. 设置页六行,「屏保启动」下面的小字随图库空 / 待机关 / 其余三种情况变化。
```

- [ ] **Step 9: Commit**

```bash
git add docs/DESIGN-unitedu-open-source.md docs/WORKLOG.md
git commit -m "docs(m5): emulator verification, A95L checklist and design sync for standby and screensaver" -m "Co-Authored-By: Claude <实际模型> <noreply@anthropic.com>"
```

- [ ] **Step 10: 收尾**

按 superpowers:finishing-a-development-branch 决定并入方式(本项目惯例:本地 `merge --no-ff` 到 main,不推远程,Gordon 说「推」再推;推的办法见 CLAUDE.md「推 GitHub 的坑」)。

---

## Self-Review

- **Spec 覆盖**:
  - §0 决策表 → 术语 / 改名 T4(文案);状态模型 T1 + T3;待机显示不加选项(T4 保留三档);屏保启动 T1(字段)+ T3(计时)+ T4(行与提示);屏保轮播设置 T1 + T2(间隔进播放器与 Ken Burns)+ T4;屏保画面与时钟阴影 T3;屏保按钮 T3;图库删图 T5;系统屏保 T6;迁移(不需要)T1 单测。
  - §1 状态机 → T1(`StandbyFlags` 构造函数钉不变量)+ T3;§1.1 计时 → T1(`standbyPlan`)+ T3 Step 4;§1.2 唤醒 → T3 Step 6;§1.3 屏保按钮 → T3 Step 5;§1.4 四层 → T2(屏保层)+ T3(`active = screensaverActive`、黑层、HomeScreen);§1.5 时钟阴影 → T3 Step 1。
  - §2 共用播放器 → T2(单例、引用计数、唯一计时、扫描规则、迁移、1920×1080 解码、Ken Burns = 间隔 + 交叉淡入、`Screensaver.kt`),T6 消费。
  - §3 设置组 → T4(六行、`noteRes`、`screensaverImages`、`produceState` + 图库版本、≤ 8 行、壁纸组改名、两键默认值与夹取来自 T1)。
  - §4 系统屏保 → T6(spike 清单与 `dream.xml`、Compose 宿主与生命周期、`isInteractive/isFullscreen/isScreenBright`、读设置与 `AppLocale`、主题色同一条路、空图库黑底 + 时钟、续播)。
  - §5 删图 → T5(dispatchKeyEvent 新一支、`onFocusedFile`、Viewer 五个新参数、`ConfirmDialog`、删除顺序、nonce 接回、铁律 3 表加行、设置行 hint 来自 T4 文案)。
  - §6 文案 → T4(十一个新键 + 三处改)+ T5(`pool_delete_*`)。
  - §7 验收 → 单测 T1 / T2 / T4;时间线与屏保按钮 T3;设置页 T4;删图 T5;系统屏保 T6;焦点七条与轮播间隔 T7;A95L 清单 T7 Step 8。
  - §8 不做 → Global Constraints「范围」。§9 风险 → T6(宿主、冷进程、退路只报告不自换)、T2(唯一计时、解码尺寸)、T5 + T7 Step 4(长按互斥)。
- **占位符**:无 TBD / 「同 Task N」。执行时要填的只有三类且都写明了填什么:commit trailer 的 `<实际模型>`、T6 Step 7 的 `<实测可用的启动方式>`、WORKLOG 的实测值。
- **类型一致**:`StandbyFlags.NORMAL/STANDBY/SCREENSAVER`(T1)= T3 各写入点;`standbyPlan(Long, Long)`(T1)= T3 计时效果;`hasScreensaverImages(Context)`(T2)= T3 两个效果;`scanScreensaverLibrary(Context)`(T2)= T4 计数;`Screensaver(active: Boolean, intervalMs: Long)`(T2)= T2 / T3 调用点;`ScreensaverContent(intervalMs, modifier)`(T2)= T6;`ScreensaverPlayer.rescan(Context)`(T2)= T5;`HeroClock(modifier, showDate, shadow)`(T3)= T3 HomeScreen / T6 DreamContent;`settingsGroups(s, update, actions, screensaverImages)`(T4)= SettingsScreen 与测试;`SettingsActions` 七个参数(T4)= MainActivity / SettingsScreen.liveActions / 测试 Recorder;`galleryVersion`(T4)= T5 的 `refresh` 与 `galleryVersion++`;`ScreensaverPoolViewer` 参数(T5)= PickerLayer 调用;`rememberThemeColors(Context, Settings, Int)`(T6)= MainActivity / DreamContent。
- **每个任务结束都能构建、测试全绿**:T2 保留旧触发条件(`active = idle` + NO_FADE 守卫),T3 才换;T4 同一任务里改完 `SettingsActions` 的三个构造点;T6 搬函数与改调用点同一任务。

## Pre-flight:spec 里的歧义与本 plan 的裁定

1. **§2 `ScreensaverContent(showClock: Boolean)` 与同句「时钟由调用方叠」矛盾**;§4 又写 Dream 里是 `ScreensaverContent` + `HeroClock` 两个并列。裁定:不设 `showClock`,签名 `ScreensaverContent(intervalMs, modifier)` 只画轮播层,两处调用方各自叠 `HeroClock`(T2 / T6)。
2. **§1「两个布尔量,不改成枚举」与「单测覆盖构造函数」**:两个裸 `mutableStateOf` 没有构造函数可测。裁定:打包成 `data class StandbyFlags(idle, screensaverActive)`(init 里 require 不变量),Activity 只存一个值,`idle` / `screensaverActive` 做派生读者——布尔量与五个消费者都不变。连带一处:spec 计时写「delay(standbyAt) 写 idle = true」只动一个量;打包后等价写法是「只升不降」(`if (!standby.idle) standby = STANDBY`),否则按钮进的屏保会在待机时刻被降回待机(T3 Step 9 F1 专测这一条)。
3. **§1.4「卡片行 / 渐变 / pill 仍按 contentAlpha 淡出(idle 为真)」与「不淡出」冲突**:NO_FADE 下 contentAlpha 恒为 1,照片上会浮着一排卡片,违背 §0「屏保画面 = 全屏轮播 + 时钟」。裁定:`screensaver` 为真时 contentAlpha 一律 → 0(T3 Step 2;F4 验)。
4. **§3 提示只列三种情况,没说「屏保启动」本身为「关」时画什么**。裁定:不画(计时起点与图库都无关了);单测钉住。controller 若要三种情况照画,删 `screensaverAfterNoteRes` 第一条分支与对应断言即可。
5. **§3「标签与控件之间画一行小字」没说横排还是竖排**。裁定:画在标签下方、仍在 190 dp 标签列里——横排放不下(五档分段控件之后只剩约 80 dp),竖排不动控件列对齐、46 dp 行高装得下两行。
6. **§3 计数在 IO 上、第一帧还没有值**。裁定:初值 −1,模型按非空处理,不在打开那一瞬间误报「图库为空」。
7. **§5 没提全屏预览关掉后的焦点**:预览拿着焦点的节点一销毁,没有人补请求(铁律 3),而 M5 让图库重新可达。裁定:加一个本地计数 `previewCloses` 并进网格的 nonce(T5 Step 3),CLAUDE.md 焦点表一并记下。
8. **小处**:`migrateOldScreensaver` 放在每次扫描里(spec 说「首次扫描」;它本来就只在图库为空时动,每次调用无害,且让「到点查图库」与播放器口径一致);删图失败只记日志不 toast(§6 没有这条文案);`openSystemScreensaverSettings` 用 `runCatching` 兜住所有异常(不只 `ActivityNotFoundException`);Dream 窗口加 `COLOR_MODE_HDR`(与 MainActivity 同,「画面一致」);主题色抽出 `rememberThemeColors`(比 spec 的「只搬 `wallpaperThemeColors`」多走一步,换来两处逐字同一条路)。

**已知风险(本 plan 不修,写进 WORKLOG)**:`PickerGrid` 既有的 `verticalScroll`(铁律 1,M5 之前就有);被吞掉 UP 的那次按压可能留下 `clickable` 的按压指示(外观,下一次按确定复原);全屏预览开着时回到前台,网格 nonce 循环会抢走预览的焦点(M5 之前就有);模拟器上系统屏保的立即启动命令要到 T6 才探得出来;英文新行标签照 spec 用句首大写,与旧行的 Title Case 不一致。
