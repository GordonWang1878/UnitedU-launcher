# 编辑桌面顺滑度(R167)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「设置 → 布局 → 编辑桌面」的进出转场和页内拿起搬运做顺(owner 原话:「编辑桌面的顺滑度远远不够……一卡一卡的,显得很廉价」),按诊断报告的修复清单 #1 #2 #4 #5 #7 #8 #9 #10 落地,外加两处文案。

**Architecture:** 转场卡顿的根因是「转场中途整页重建 + 重读数据 + 离屏层叠太深」,所以主线是**不重建、不重读、少开层**:首页与设置外壳在编辑页期间留在组合里(沿用 `previewing` / 残影让路机制),编辑页首帧直接用进程级卡片缓存与预读好的布局,`leaveEdit()` 不再无条件 `revision++`;页内的「一跳一跳」是动效缺口,补上只改绘制的位置过渡;焦点变化时把状态读取下沉到用得到的那一层,停止整页重组。焦点账本(七条铁律)一律不动,所有新东西都只在绘制阶段或数据层。

**Tech Stack:** Kotlin 2.0.21(Compose 强跳过默认开启)、Compose BOM 2024.10.01、tv-material 1.0.0、AGP 8.7.3、JUnit 4(纯 JVM 单测,`app/src/test`)、模拟器 `unitedu-tv-2` / `unitedu-tv-3` + perfetto / screenrecord / ffprobe。

**Spec:** `docs/design/perf-2026-10-10-edit-page.md`(诊断报告,本计划的权威依据;下文「报告 #N」指它的修复清单第 N 条)。相关设计:`docs/superpowers/specs/2026-10-08-edit-shelves-and-channels-design.md`(编辑页货架 R165,§2.5 动效)、`docs/superpowers/specs/2026-10-09-release-channels-design.md`(R166 双通道,文案 a 与 Task 8 的会话安装)、`docs/research/2026-10-10-cn-apps-tv-channels.md` §3(文案 b)。

### 报告条目 ↔ 任务

| 报告 | 内容 | 任务 |
|---|---|---|
| — | 测量脚手架 + 改前基线 | Task 1 |
| #2、#9 | 编辑页首帧有图(进程级缓存)、`Layout.read` 挪出组合;焦点放大与淡入同时开始 | Task 2 |
| #1(+ #4 的一半) | 首页 / 外壳在编辑页期间留在组合里;`leaveEdit()` 只在布局真改过时通知首页重读;首页残影那一层随之消失 | Task 3 |
| #4 | 转场期间少一个整屏层:首页被编辑页盖满后不画;层数与 R129f 截图核对 | Task 4 |
| #5 | 焦点变化不再整页重组 | Task 5 |
| #7 | 非焦点架子改 `ModulateAlpha`(像素闸门不过就回退并记为不做) | Task 6 |
| #8 | 拿起搬运的位置过渡(同层换位、跨层搬入、取消) | Task 7 |
| #10 | 自家 baseline profile + 会话安装带 `.dm` 的模拟器实验(闸门) | Task 8 |
| 文案 a | 关于页:Beta 通道上装着回退包时的「已是最新」说明 | Task 9 |
| 文案 b | 选频道页:内容与更新时机由发布方决定,不叫「推荐」 | Task 10 |
| — | 复测出对照表 + 文档同步(R167) | Task 11 |
| #3、#6 | 位图 `prepareToDraw`、电视 4× 重绘消融 | **不在本计划**(要电视测量) |

## Global Constraints

- **焦点七条铁律**(仓库 `CLAUDE.md`)逐条有效:不用任何可滚动容器(编辑页唯一获准的 `LazyColumn` 仍只在 AppPicker / ChannelPicker);焦点是否落下只信目标自报;目标与持有者分开、`ON_PAUSE` 起冻结;效果里的守卫必须同时是 key;不用一次性布尔闩。**本计划不新增、不改动任何焦点账本**(目标 / 持有者 / requester 挂点 / 看门狗 / 重定位效果的逻辑),只改读取位置、绘制与数据来源。
- **R129f**:`graphicsLayer` alpha < 1 会把内容画进以图层尺寸为界的离屏层、越界部分被裁;给任何带焦点溢出的容器加 / 改 alpha 前先想这一条,改完要有截图对比。
- **数据格式向下兼容铁律**:不改 layout / titles / hidden-inputs / settings 任何字段与文件;不新增落盘文件;不新增 manifest 组件。
- **落盘铁律**:写 layout.json 仍只走 `Layout.rewrite` + `layoutWrites` 串行调度器。
- **动效**:沿用现有时长档与曲线(spec §2.5:「换层 200 ms `FastOutSlowIn`;拿起 / 放下 150 ms;沿用现有时长档,不新造曲线」);量时长一律用 mp4 真实 pts(`ffprobe -show_entries frame=pts_time`),不数帧、不靠慢放倍率。
- **模拟器只用 `unitedu-tv-2`(端口 5570,`S=emulator-5570`)或 `unitedu-tv-3`(端口 5572,`S=emulator-5572`)**,一律 `-gpu host`、带窗口启动(`-no-window -gpu host` 下 `screencap` 可能返回旧画面);**绝不用 `unitedu-tv`,绝不碰真机**。开机前确认没有别的代理正在用同一台(CLAUDE.md:同一时刻只能有一个代理操作同一台模拟器)。
- 每个任务的构建 / 测试命令:`source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`。
- 视觉 / 动效任务的改前改后关键帧存 `docs/screenshots/edit-smooth/`(JPEG,`sips -s format jpeg`);装电视前的规矩照旧:自己以观众身份把 1× 录像看一遍,不只核对数字。
- 文案:`strings.xml` 三种语言(`values` 简中 / `values-en` / `values-zh-rTW`)同一套 key,中文标点全角(`CopyTest`);README 改动 `README.md` 与 `README.zh-CN.md` 同步;`docs/` 只写中文。
- 不 push、不发版、不改 versionCode / versionName(电视验证随下一个 Beta,发不发由 Gordon 定)。
- 提交信息末尾带 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`。

## Review Focus

1. **编辑页开着时某个桌面应用被卸载 / 更新(revision++ 恰在进页前后)** → 编辑页不能画出僵尸卡或别人的图,退出后首页也对。钉在 Task 2(`AppEntryCacheTest` 的 revision 不同即不命中)与 Task 3(e2e `j_pkg.py` 的「编辑页开着时卸载」)。
2. **拿起后连按方向键,上一步的滑动还没走完又来一步** → 卡片从它此刻画在的位置接着滑,不先跳回再滑。钉在 Task 7(`CarrySlideTest.midAnimationStepContinuesFromCurrentOffset`)。
3. **按下「编辑桌面」后立刻按 HOME / 返回 / MENU(预读 layout.json 还在 IO 线程上)** → 不能过一会儿在首页上自己弹出编辑页。钉在 Task 2(`EditEntryTest` 的请求号比对;`leaveEdit` / `popShell` / `leaveSettings` 都加请求号)。
4. **在编辑页把首页焦点原来所在的那一行删掉,退出编辑页再关设置** → 焦点落到补位的那一行,不丢、不落到奇怪的位置。钉在 Task 3(`HomeChannelsTest` 补位用例 + 模拟器步骤)。
5. **编辑页写盘还没落地就退出(残影 400 ms 后组合作用域被取消)** → 首页仍按新布局重读,不画旧顺序。钉在 Task 2(`persist()` 改 `NonCancellable`)与 Task 3(模拟器「放下后 0.1 s 内按返回」步骤)。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `scripts/perf/edit-jank/*` | 新建(由诊断时的临时脚本整理入库) | 测量:搭环境、按键剧本、framestats / mp4 pts / perfetto 汇总、联系表、像素比对 |
| `app/src/main/java/com/uniteduone/launcher/AppEntryCache.kt` | 新建 | 进程级卡片数据缓存(按 revision 失效)+ `loadAppEntries` |
| `app/src/main/java/com/uniteduone/launcher/EditEntry.kt` | 新建 | 进出编辑页的两个纯判据:`shouldEnterEdit`、`editCoversHome` |
| `app/src/main/java/com/uniteduone/launcher/EditCarrySlide.kt` | 新建 | 拿起换位的位移计算(纯函数)+ 每层的滑动状态 |
| `app/src/main/java/com/uniteduone/launcher/DexMetadata.kt` | 新建(Task 8 闸门通过才建) | 从 APK 取 baseline.prof 包成 `.dm` |
| `app/src/main/baseline-prof.txt` | 新建 | 本应用自己的 baseline profile 规则 |
| `EditScreen.kt` / `EditShelfParts.kt` / `EditShelves.kt` | 改 | 首帧数据、写盘通知、重组下沉、ModulateAlpha、滑动 |
| `HomeScreen.kt` / `MainActivity.kt` | 改 | 首页常驻、`layoutRevision`、预读、绘制让路、外壳常驻 |
| `UpdateChannels.kt` / `Update.kt` / `AboutScreen.kt` | 改 | 文案 a |
| `SelfUpdate.kt` | 改(Task 8 闸门通过才改) | 会话里带 `base.dm` |
| `res/values*/strings.xml`、`README*.md` | 改 | 文案 a / b |
| `scripts/e2e/channels.sh` | 改 | 版本名替换修正 + 文案 a 场景 |
| 测试:`AppEntryCacheTest` / `EditEntryTest` / `CarrySlideTest` / `DexMetadataTest`(新)、`HomeChannelsTest` / `UpdateChannelsTest` / `AboutStateTest` / `CopyTest`(改) | | |

---

### Task 1: 测量脚手架入库 + 改前基线

**Files:**
- Create: `scripts/perf/edit-jank/u.sh`、`suite.sh`、`setup.sh`、`report.sh`、`ana.py`、`fsum.py`、`rtbreak.py`、`passes.py`、`sheet.py`、`frames.py`、`agg.py`、`pixdiff.py`、`layout.json`、`settings.json`
- Create: `docs/screenshots/edit-smooth/before-*.jpg`

**Interfaces:**
- Produces:`bash scripts/perf/edit-jank/setup.sh`(环境变量 `S`,默认 `emulator-5570`)、`bash scripts/perf/edit-jank/suite.sh <tag>`、`bash scripts/perf/edit-jank/report.sh <tag>` → `$T/summary_<tag>.txt`(`T` 默认 `/tmp/edit-jank`)、`python3 scripts/perf/edit-jank/pixdiff.py a.png b.png [maxDiff] [meanDiff]`(退出码 0 = 在阈值内)、`python3 scripts/perf/edit-jank/sheet.py <mp4> <输出前缀> [起-止秒]`。后面所有模拟器步骤都用它们。

- [ ] **Step 1: 把诊断时的脚本拷进仓库**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
SRC=/Users/gordonwang/.claude/jobs/612c4907/tmp/edit-jank
# 诊断时的临时目录可能已被清掉:不在就停下,在 WORKLOG 记一笔,按报告「测量方法」一节与下面给出的 u.sh / suite.sh 重写缺的脚本
[ -d "$SRC/bin" ] || { echo "诊断脚本目录不在了:$SRC" >&2; exit 1; }
mkdir -p scripts/perf/edit-jank
cp "$SRC"/bin/{suite.sh,u.sh,ana.py,fsum.py,rtbreak.py,passes.py,sheet.py,frames.py,agg.py} scripts/perf/edit-jank/
cp "$SRC"/layout.json "$SRC"/settings.json scripts/perf/edit-jank/
```

- [ ] **Step 2: 去掉写死的临时路径**

Python 脚本里 perfetto 包的路径改成可配(默认 `/tmp/edit-jank/tp/py`;**仓库是公开的,不写本机家目录路径**):

```bash
cd scripts/perf/edit-jank
for f in ana.py fsum.py rtbreak.py passes.py frames.py agg.py; do
  sed -i '' 's#sys.path.insert(0, "/Users/gordonwang/.claude/jobs/612c4907/tmp/edit-jank/tp/py")#sys.path.insert(0, __import__("os").environ.get("PERFETTO_PY", "/tmp/edit-jank/tp/py"))#' "$f"
done
grep -n "sys.path.insert" *.py
grep -rn "/Users/" . && { echo "还有写死的本机路径,改掉再提交" >&2; exit 1; } || true
```

perfetto 包不在时装一份(诊断时那份可以直接拷过来:`mkdir -p /tmp/edit-jank/tp && cp -R "$SRC/tp/py" /tmp/edit-jank/tp/`):`python3 -m pip install --target /tmp/edit-jank/tp/py perfetto==0.58.2`。`pixdiff.py` / `sheet.py` 要 Pillow:`python3 -c 'import PIL'` 先确认。

`u.sh` 整个换成下面这份(序列号、输出目录可配;`dumpui` 不再写死 xml 路径;`m_end` 调同目录的 `ana.py`):

```bash
# source me(bash)。S = 模拟器序列号(unitedu-tv-2 → emulator-5570,unitedu-tv-3 → emulator-5572),T = 原始数据目录
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export S="${S:-emulator-5570}" T="${T:-/tmp/edit-jank}" P=com.uniteduone.launcher
mkdir -p "$T"
k() { for x in "$@"; do adb -s $S shell input keyevent $x; sleep 0.45; done; }
dumpui() { adb -s $S shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb -s $S shell cat /sdcard/u.xml > $T/u.xml; T=$T python3 - <<'PY'
import os, xml.etree.ElementTree as ET
r=ET.parse(os.path.join(os.environ['T'], 'u.xml')).getroot()
def txt(n): return [m.get('text') or m.get('content-desc') for m in n.iter() if (m.get('text') or m.get('content-desc'))]
for n in r.iter():
  if n.get('focused')=='true': print('FOCUS', n.get('bounds'), txt(n)[:4])
print('TEXTS', [t for t in txt(r)][:40])
PY
}
shot() { adb -s $S exec-out screencap -p > $T/$1.png; }
run() { local n=$1 s=$2; shift 2; adb -s $S shell dumpsys gfxinfo $P reset >/dev/null; adb -s $S shell rm -f /sdcard/$n.mp4 /data/misc/perfetto-traces/$n.pftrace
 (adb -s $S shell perfetto -o /data/misc/perfetto-traces/$n.pftrace -t ${s}s -b 64mb -a $P gfx view input sched freq dalvik am wm >/dev/null 2>&1 &)
 (adb -s $S shell screenrecord --time-limit $s --size 960x540 /sdcard/$n.mp4 &); sleep 1.5
 for x in "$@"; do adb -s $S shell input keyevent $x; sleep 0.6; done
 sleep 1.5; adb -s $S shell dumpsys gfxinfo $P framestats > $T/$n.gfx.txt
 while adb -s $S shell pgrep -x perfetto >/dev/null || adb -s $S shell pgrep screenrecord >/dev/null; do sleep 0.5; done
 adb -s $S pull /data/misc/perfetto-traces/$n.pftrace $T/$n.pftrace >/dev/null; adb -s $S pull /sdcard/$n.mp4 $T/$n.mp4 >/dev/null; }
```

`suite.sh` 换成下面这份(开头冷启动一次,保证每次起点相同:首页、焦点在第 1 行第 1 张):

```bash
# 用法:bash scripts/perf/edit-jank/suite.sh <tag>
# H 首页导航(对照)/ A 进编辑页 / B 上下换层 / C 层内左右 / D 拿起搬运 / E 退出 / A2 E2 第二次进出。按键剧本与诊断报告逐键相同。
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/u.sh"
source "$HERE/../../env.sh"
g=$1
adb -s $S shell am force-stop $P
adb -s $S shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $P/.MainActivity >/dev/null
sleep 5
k 20; sleep 1
run H_$g 16 22 22 22 22 22 21 21 21 21 21 20 20 19 19 20 20 19 19
k 19 19 21 21 21; k 23; sleep 1.2; k 20 23; sleep 1.5; dumpui | head -1 > $T/pre_A_$g.txt
run A_$g 5 23; sleep 1; dumpui | head -1 > $T/post_A_$g.txt
run B_$g 14 20 20 20 20 20 20 19 19 19 19 19 19
run C_$g 15 22 22 22 22 22 22 22 21 21 21 21 21 21 21
run D_$g 12 23 22 22 22 20 19 21 21 21 4
sleep 1; dumpui | head -1 > $T/pre_E_$g.txt
run E_$g 5 4
sleep 1.5; run A2_$g 5 23; sleep 1; run E2_$g 5 4
k 4; sleep 1; k 4; sleep 1.5
```

- [ ] **Step 3: 新建 `setup.sh`、`report.sh`、`pixdiff.py`**

`scripts/perf/edit-jank/setup.sh`:

```bash
#!/usr/bin/env bash
# 在 unitedu-tv-2 / -3 上搭测量环境:装当前构建、设 HOME、装 19 个占位应用、推测试布局(5 行 10/5/4/4/4 张)与设置、编译成 speed。
# 只对模拟器用。S 默认 emulator-5570(unitedu-tv-2)。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../../.." && pwd)"
source "$ROOT/scripts/env.sh"
S="${S:-emulator-5570}"; P=com.uniteduone.launcher; F=/sdcard/Android/data/$P/files
adb -s "$S" install -r "$ROOT/app/build/outputs/apk/release/app-release.apk"
adb -s "$S" shell cmd package set-home-activity $P/.MainActivity
DEV="$S" python3 "$ROOT/scripts/e2e/fixtures.py"
adb -s "$S" install -r "${E2E_OUT:-/tmp/unitedu-e2e}/apks/test.dummy.app18.apk"
adb -s "$S" shell am start -n $P/.MainActivity >/dev/null; sleep 5
adb -s "$S" shell am force-stop $P
adb -s "$S" push "$HERE/layout.json" "$F/layout.json"
adb -s "$S" push "$HERE/settings.json" "$F/settings.json"
adb -s "$S" shell settings put global animator_duration_scale 1.0
for _ in 1 2 3 4 5; do
  adb -s "$S" shell cmd package compile -m speed -f $P >/dev/null; sleep 3
  adb -s "$S" shell dumpsys package dexopt | grep -A3 "\[$P\]" | grep -q "status=speed" && break
done
adb -s "$S" shell dumpsys package dexopt | grep -A3 "\[$P\]"
```

`scripts/perf/edit-jank/report.sh`:

```bash
#!/usr/bin/env bash
# 用法:report.sh <tag>  把 suite.sh <tag> 的各段汇总到 $T/summary_<tag>.txt(mp4 pts、framestats 分位、trace 按键帧 / 动画帧、RT 拆分、渲染遍数)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; T="${T:-/tmp/edit-jank}"; tag=$1
{
for seg in H A B C D E A2 E2; do
  b="$T/${seg}_$tag"; [ -f "$b.mp4" ] || continue
  echo "=== $seg"
  python3 "$HERE/ana.py" "$b"
  python3 "$HERE/fsum.py" "$b.pftrace"
done
python3 "$HERE/rtbreak.py" "$T"/*_"$tag".pftrace
python3 "$HERE/passes.py" "$T"/*_"$tag".pftrace
} 2>&1 | tee "$T/summary_$tag.txt"
```

`scripts/perf/edit-jank/pixdiff.py`:

```python
# 用法:pixdiff.py a.png b.png [最大通道差=8] [平均通道差=0.5] [x0,y0,x1,y1]
# 两张同尺寸截图逐像素比:打印最大 / 平均通道差;都在阈值内退出码 0,否则 1。Task 4 / 6 的截图闸门用。
import sys
from PIL import Image, ImageChops, ImageStat
a = Image.open(sys.argv[1]).convert("RGB"); b = Image.open(sys.argv[2]).convert("RGB")
mx_lim = int(sys.argv[3]) if len(sys.argv) > 3 else 8
mean_lim = float(sys.argv[4]) if len(sys.argv) > 4 else 0.5
box = tuple(map(int, sys.argv[5].split(","))) if len(sys.argv) > 5 else (0, 0, a.width, a.height)
d = ImageChops.difference(a.crop(box), b.crop(box))
mx = max(hi for _, hi in d.getextrema())
mean = sum(ImageStat.Stat(d).mean) / 3
print(f"max {mx} mean {mean:.3f} (limits {mx_lim} / {mean_lim})")
sys.exit(0 if mx <= mx_lim and mean <= mean_lim else 1)
```

```bash
chmod +x scripts/perf/edit-jank/*.sh
```

- [ ] **Step 4: 在当前 HEAD 上量基线(before)**

```bash
source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease
emulator -avd unitedu-tv-2 -port 5570 -no-snapshot -no-audio -gpu host &      # 带窗口;等开机
adb -s emulator-5570 wait-for-device && adb -s emulator-5570 shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
S=emulator-5570 bash scripts/perf/edit-jank/setup.sh
S=emulator-5570 bash scripts/perf/edit-jank/suite.sh before
bash scripts/perf/edit-jank/report.sh before
```

Expected:`summary_before.txt` 里 A 段 mp4 fps 37–42、E 段约 29 fps、A / E 的 `tex upload` 数百次(与报告汇总表同一量级;宿主负载不同会有出入,以这次的数为准)。记下 `uptime` 的负载值,Task 11 复测时在相近负载下跑。

- [ ] **Step 5: 存改前关键帧**

```bash
T=/tmp/edit-jank; O=docs/screenshots/edit-smooth; mkdir -p $O
for seg in A E D; do
  python3 scripts/perf/edit-jank/sheet.py $T/${seg}_before.mp4 $T/sheet_${seg}_before
  sips -s format jpeg $T/sheet_${seg}_before.png --out $O/before-${seg}.jpg
done
ls -la $O
```

Expected:`before-A.jpg` 里能看到卡片先是文字占位、约 0.4–0.5 s 后整排换成图;`before-D.jpg` 里搬一步卡片一帧换位。

- [ ] **Step 6: Commit**

```bash
git add scripts/perf/edit-jank docs/screenshots/edit-smooth
git commit -m "perf: 编辑页卡顿测量脚手架入库 + 改前基线关键帧

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: 编辑页首帧有图 + 布局预读(报告 #2、#9)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/AppEntryCache.kt`
- Create: `app/src/main/java/com/uniteduone/launcher/EditEntry.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/EditScreen.kt`(参数、`rows` 初值、`loadedFor`、`persist()`)
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt`(`buildRows`)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(`enterEdit()`、`editSeedRows`、`editEnterSeq`、`leaveEdit()`、`onPickIcon`)
- Test: `app/src/test/java/com/uniteduone/launcher/AppEntryCacheTest.kt`、`EditEntryTest.kt`

**Interfaces:**
- Produces:
  - `internal class AppEntrySnapshot(val revision: Int, val checked: Set<String>, val entries: Map<String, AppEntry>)`
  - `internal object AppEntryCache { val epoch: Int; fun publish(s: AppEntrySnapshot, epoch: Int); fun lookup(revision: Int, needed: Set<String>): Map<String, AppEntry>?; fun sameRevision(revision: Int): AppEntrySnapshot?; fun clear() }`(`clear()` 在 `MainActivity.onCreate` 调:revision 是 Activity 字段、每个新实例从 0 数起,进程级缓存不能跨实例按号命中;`clear()` 同时 `epoch++`,上一个实例还在 IO 线程上的读取回来时按旧 epoch 发布会被丢掉)
  - `internal fun appEntryHit(s: AppEntrySnapshot?, revision: Int, needed: Set<String>): Map<String, AppEntry>?`
  - `internal fun mergeAppEntries(base: AppEntrySnapshot, fresh: Map<String, AppEntry>, missing: Set<String>): AppEntrySnapshot`(同一 revision 只补查缺的包)
  - `internal fun loadAppEntries(ctx: Context, revision: Int, needed: Set<String>): Map<String, AppEntry>`(IO 线程;整段串行,首页与编辑页同时没命中时只读一遍)
  - `internal fun shouldEnterEdit(requested: Int, current: Int, resumed: Boolean, blocked: Boolean): Boolean`
  - `EditScreen(..., initialRows: List<LayoutRow>? = null, onLayoutWritten: () -> Unit = {})`(Task 3 接上 `onLayoutWritten`)
  - `MainActivity.enterEdit()`(Task 3、4 沿用)

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/AppEntryCacheTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R167 进程级卡片缓存:命中条件 = 同一个 revision 且查过的包覆盖所需。纯 JVM。 */
class AppEntryCacheTest {
    private fun e(p: String) = AppEntry(p, p.uppercase(), null, isWide = false)
    private val entries = mapOf("a" to e("a"), "b" to e("b"))
    private val snap = AppEntrySnapshot(revision = 3, checked = setOf("a", "b", "gone"), entries = entries)

    @Test fun hitWhenSameRevisionAndCovered() = assertEquals(entries, appEntryHit(snap, 3, setOf("a", "b")))
    @Test fun subsetIsAHit() = assertEquals(entries, appEntryHit(snap, 3, setOf("a")))
    /** 查过却没找到的包(被停用)也算覆盖:编辑页按 R67 不画它,与首页一致 */
    @Test fun checkedButNotFoundStillHits() = assertEquals(entries, appEntryHit(snap, 3, setOf("a", "gone")))
    /** 装 / 卸 / 换卡片图都会 revision++:旧快照一律作废(Review Focus 1) */
    @Test fun otherRevisionMisses() = assertNull(appEntryHit(snap, 4, setOf("a")))
    /** 编辑页刚加了一个新应用:没查过 → 不命中,照旧去读 */
    @Test fun uncheckedPackageMisses() = assertNull(appEntryHit(snap, 3, setOf("a", "new")))
    @Test fun emptyCacheMisses() = assertNull(appEntryHit(null, 0, emptySet()))
    /**
     * 新的 MainActivity 实例 revision 从 0 重新数:上一个实例留下的快照号可能恰好相同(都是 0),而两个实例之间装 / 卸的应用
     * 没人通知(接收器跟着 Activity 走)。onCreate 清掉,同号也不命中。
     */
    @Test fun clearedCacheMissesEvenWithSameRevision() {
        AppEntryCache.publish(AppEntrySnapshot(revision = 0, checked = setOf("a"), entries = entries), AppEntryCache.epoch)
        assertEquals(entries, AppEntryCache.lookup(0, setOf("a")))
        AppEntryCache.clear()
        assertNull(AppEntryCache.lookup(0, setOf("a")))
    }
    /** 上一个实例开始的读取在 clear() 之后才回来:按旧 epoch 发布,丢掉(否则新实例的 revision 0 会命中它) */
    @Test fun publishFromPreviousInstanceIsDropped() {
        AppEntryCache.clear()
        val old = AppEntryCache.epoch
        AppEntryCache.clear()
        AppEntryCache.publish(AppEntrySnapshot(revision = 0, checked = setOf("a"), entries = entries), old)
        assertNull(AppEntryCache.lookup(0, setOf("a")))
    }
    /**
     * 编辑页加了一个应用(同一 revision、needed 多了 "n"):只补查 "n",已有的位图原样沿用。
     * [Apps.load] 的结果含**全部**桌面应用(只有要的那几个带位图)——不能拿它整份盖掉已有条目,否则 "a" 的位图会变成没解码的那一份。
     */
    @Test fun mergeKeepsDecodedEntriesAndAddsMissing() {
        val fresh = mapOf("a" to AppEntry("a", "", null, isWide = false), "n" to e("n"), "other" to AppEntry("other", "", null, isWide = false))
        val m = mergeAppEntries(snap, fresh, missing = setOf("n"))
        assertEquals(3, m.revision)
        assertEquals(setOf("a", "b", "gone", "n"), m.checked)
        assertEquals(e("a"), m.entries["a"])
        assertEquals(e("n"), m.entries["n"])
        assertEquals(entries + ("n" to e("n")), appEntryHit(m, 3, setOf("a", "n")).orEmpty().filterKeys { it != "other" })
    }
}
```

`app/src/test/java/com/uniteduone/launcher/EditEntryTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R167 进编辑页的判据:预读 layout.json 回来时,请求号还是最新的、而且还在前台,才真的进。 */
class EditEntryTest {
    @Test fun latestRequestWhileResumedEnters() = assertTrue(shouldEnterEdit(requested = 5, current = 5, resumed = true, blocked = false))
    /** 预读期间按了 HOME / 离开了编辑页(请求号被加过):不进(Review Focus 3) */
    @Test fun supersededRequestDoesNotEnter() = assertFalse(shouldEnterEdit(requested = 5, current = 6, resumed = true, blocked = false))
    /** 预读期间退到后台:不进 */
    @Test fun backgroundDoesNotEnter() = assertFalse(shouldEnterEdit(requested = 5, current = 5, resumed = false, blocked = false))
    /** 预读期间别的整屏页(选图页 / 关于页 / 应用页 / 输入源页 / 引导)开了:不进,否则编辑页会把那一页吞进自己那一层 */
    @Test fun otherOverlayDoesNotEnter() = assertFalse(shouldEnterEdit(requested = 5, current = 5, resumed = true, blocked = true))
}
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*AppEntryCacheTest' --tests '*EditEntryTest'`
Expected: 编译失败,`AppEntrySnapshot` / `appEntryHit` / `shouldEnterEdit` 未定义。

- [ ] **Step 3: 实现缓存与判据**

`app/src/main/java/com/uniteduone/launcher/AppEntryCache.kt`:

```kotlin
package com.uniteduone.launcher

import android.content.Context

/**
 * R167(报告 perf-2026-10-10 #2):首页读好的卡片数据(含已缩到卡片尺寸的位图)在进程里留一份,编辑页进页第一帧直接用,
 * 不再进页才 `Apps.load` 一遍、先画文字占位再整排换图。
 * **失效 = MainActivity 的 revision 变了**:装 / 卸 / 更新应用、换卡片图、恢复原图都会 revision++,首页与编辑页本来就按这个量重读,
 * 所以失效时机与首页逐一致。[checked] = 这份是给哪些包查的:查过但没找到 = 被停用(R67,编辑页不画),没查过 = 不命中。
 * 只存同一批 AppEntry 的引用,不多占位图内存。整份不可变快照 + @Volatile:IO 线程写、任意线程读。
 */
internal class AppEntrySnapshot(val revision: Int, val checked: Set<String>, val entries: Map<String, AppEntry>)

internal object AppEntryCache {
    @Volatile private var snap: AppEntrySnapshot? = null
    /** 第几个 MainActivity 实例的缓存([clear] +1)。读取开始时记下,发布时对不上就丢(上一个实例在 IO 线程上没读完的那一趟)。 */
    @Volatile var epoch = 0
        private set
    @Synchronized fun publish(s: AppEntrySnapshot, epoch: Int) { if (epoch == this.epoch) snap = s }
    fun lookup(revision: Int, needed: Set<String>): Map<String, AppEntry>? = appEntryHit(snap, revision, needed)
    /** 同一 revision 的那一份(不管覆不覆盖):[loadAppEntries] 只补查缺的包。 */
    fun sameRevision(revision: Int): AppEntrySnapshot? = snap?.takeIf { it.revision == revision }
    /**
     * MainActivity.onCreate 调。revision 是 Activity 的字段,每个新实例从 0 数起;上一个实例留下的快照号可能与新实例的号相同,
     * 而两个实例之间(Activity 被销毁、进程还活着)装 / 卸的应用没人通知——不清就会按同号命中一份过期数据(僵尸卡 / 缺图)。
     */
    @Synchronized fun clear() { snap = null; epoch++ }
}

/** 命中 = 同一个 revision,且这份查过的包覆盖了 [needed]。 */
internal fun appEntryHit(s: AppEntrySnapshot?, revision: Int, needed: Set<String>): Map<String, AppEntry>? =
    s?.takeIf { it.revision == revision && it.checked.containsAll(needed) }?.entries

/**
 * 同一 revision 只补查了 [missing]:[fresh] 是 [Apps.load] 的结果——**全部**桌面应用,只有 [missing] 带位图 / 名字,
 * 所以底子用 fresh(桌面应用全集,R67 的「查过没找到」靠它),再把 [base] 查过的那几个(已解码)盖回去。
 */
internal fun mergeAppEntries(base: AppEntrySnapshot, fresh: Map<String, AppEntry>, missing: Set<String>): AppEntrySnapshot =
    AppEntrySnapshot(base.revision, base.checked + missing, fresh + base.entries.filterKeys { it in base.checked })

private val loadLock = Any()

/**
 * 先查缓存,没命中再 [Apps.load] 并发布。**IO 线程调用。** 整段串行(R167 复审 2):编辑页开着时首页也在底下(Task 3),
 * 装 / 卸 / 换卡片图让 revision++ 时两边同时没命中——不串行就各解码一遍全部横幅;串行后后到的那个直接命中。
 * 同一 revision 已有一份(编辑页加了应用、首页随写盘重读)只补查缺的包,不重解码已有的位图。
 */
internal fun loadAppEntries(ctx: Context, revision: Int, needed: Set<String>): Map<String, AppEntry> = synchronized(loadLock) {
    AppEntryCache.lookup(revision, needed)?.let { return@synchronized it }
    val epoch = AppEntryCache.epoch
    val base = AppEntryCache.sameRevision(revision)
    val missing = if (base == null) needed else needed - base.checked
    val fresh = Apps.load(ctx, missing, withBitmaps = missing, withLabels = missing)
    val snap = if (base == null) AppEntrySnapshot(revision, needed, fresh) else mergeAppEntries(base, fresh, missing)
    AppEntryCache.publish(snap, epoch)
    snap.entries
}
```

`app/src/main/java/com/uniteduone/launcher/EditEntry.kt`:

```kotlin
package com.uniteduone.launcher

// R167 进出编辑页的纯判据(MainActivity 用,JVM 单测)。

/**
 * 进编辑页前在 IO 线程预读 layout.json(报告 #2:不在组合阶段读盘)。读回来时:[requested] = 这次请求的号,[current] = 此刻的号
 * (HOME / 离开编辑页都会 +1),[resumed] = 还在前台,[blocked] = 此刻有设置外壳以外的整屏页开着(选图页 / 关于页 / 应用页 /
 * 输入源页 / 引导——编辑页一开会把选图页吞进自己那一层)。号对得上、在前台、没被挡才进——比对,不是闩(铁律 7)。
 */
internal fun shouldEnterEdit(requested: Int, current: Int, resumed: Boolean, blocked: Boolean): Boolean =
    requested == current && resumed && !blocked
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*AppEntryCacheTest' --tests '*EditEntryTest'`
Expected: PASS。

- [ ] **Step 5: 首页经缓存读卡片数据**

`HomeScreen.kt` 的 `buildRows` 改为带 revision、经 `loadAppEntries`(读到的同时发布给编辑页):

```kotlin
private fun buildRows(ctx: Context, revision: Int): List<Row> {
    val layout = Layout.read(ctx)
    val needed = layout.flatMap { it.apps }.toSet()
    // R167:经进程级缓存读(同一 revision 已读过就直接用),读到的同时发布给编辑页,进页第一帧就有图
    val all = loadAppEntries(ctx, revision, needed)
```

(函数其余部分不变。)调用处 `runCatching { buildRows(ctx) }` 改成 `runCatching { buildRows(ctx, revision) }`。

- [ ] **Step 6: 编辑页首帧用缓存、`rows` 用预读、写盘不随残影取消**

`EditScreen.kt` 参数表末尾(`initialTarget` 之后)加:

```kotlin
    /** R167:MainActivity 进页前在 IO 线程读好的 layout.json;null(换卡片图回来重建)时照旧当场读。 */
    initialRows: List<LayoutRow>? = null,
    /** R167:写盘落地一次回调一次(MainActivity 让底下常驻的首页按新布局重读,Task 3 接上)。 */
    onLayoutWritten: () -> Unit = {},
```

`rows` 初值:

```kotlin
    var rows by remember { mutableStateOf(initialRows ?: Layout.read(ctx)) }
```

`loadedFor` 整段换成:

```kotlin
    // R167(报告 #2 / #9):首页那份卡片数据(同一 revision、覆盖本页所需)直接当初值——进页第一帧就是真卡,
    // 重定位效果不必等「占位换真卡」,焦点放大与页面淡入同时开始。没命中(进页前刚 revision++)照旧在 IO 线程读,读完发布。
    val cachedAtEntry = remember { AppEntryCache.lookup(revision, needed)?.let { needed to it } }
    val loadedFor by produceState(initialValue = cachedAtEntry, needed, revision) {
        val hit = AppEntryCache.lookup(revision, needed)
        value = needed to (hit ?: withContext(Dispatchers.IO) {
            // 必须兜底:produceState 里抛出会终结 Recomposer,应用当场崩溃
            runCatching { loadAppEntries(ctx, revision, needed) }.getOrDefault(emptyMap())
        })
    }
```

`persist()` 换成(加 `import kotlinx.coroutines.NonCancellable`):

```kotlin
    val layoutWritten by rememberUpdatedState(onLayoutWritten)
    fun persist() {
        val snapshot = rows
        scope.launch {
            // R167:NonCancellable——退出编辑页后本页残影 EDIT_SWAP_MS 就离开组合、scope 被取消;写盘与「首页重读」的通知不能跟着丢
            // (Review Focus 5)。回调在块内调:withContext 返回时若外层已取消会直接抛,块外的代码不一定跑得到。
            val ok = withContext(NonCancellable) {
                // layoutWrites 串行:较早的快照不会后落盘;rewrite 整段在 Layout 的锁里(落盘铁律)
                val landed = withContext(layoutWrites) {
                    var written: List<LayoutRow>? = null
                    val l = Layout.rewrite(ctx) { disk ->
                        dropRemovedElsewhere(snapshot, disk, knownOnDisk.get()) { Apps.isInstalled(ctx, it) }.also { written = it }
                    }
                    if (l) written?.let { w -> knownOnDisk.set(knownAfterWrite(knownOnDisk.get(), w)) }
                    l
                }
                if (landed) layoutWritten()
                landed
            }
            if (!ok) showToast(ctx.getString(R.string.edit_toast_order_not_saved), true)
        }
    }
```

- [ ] **Step 7: MainActivity 预读后再进页**

`MainActivity` 字段区(`editTarget` 附近)加:

```kotlin
    /** R167:进编辑页前在 IO 线程读好的 layout.json(EditScreen 首帧用,不在组合阶段读盘)。只给这一次进入用:换卡片图回来重建时置 null,照旧当场读。 */
    private var editSeedRows by mutableStateOf<List<LayoutRow>?>(null)
    /** R167:进编辑页的请求号。HOME / 离开编辑页 / 设置外壳返回或关掉(popShell、leaveSettings)+1;预读回来号对不上就不进([shouldEnterEdit],铁律 7)。只在主线程读写,不参与组合。 */
    private var editEnterSeq = 0
```

新函数(放在 `leaveEdit()` 旁边):

```kotlin
    /** 进编辑页**只有这一条路**(设置「编辑桌面」胶囊、R161 空桌面按钮)。layout.json 先在 IO 线程读好(报告 #2),回来再置 editing。 */
    private fun enterEdit() {
        if (editing) return
        val my = ++editEnterSeq
        lifecycleScope.launch {
            // 在 layoutWrites 上读:刚退出的上一轮编辑页还在途的写盘(NonCancellable,见 persist)一定先落地,种子不会是旧文件
            val seed = withContext(layoutWrites) { runCatching { Layout.read(this@MainActivity) }.getOrNull() }
            val blocked = pickerTarget != null || about || appsPage || inputsPage || onboarding
            if (!shouldEnterEdit(my, editEnterSeq, lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED), blocked)) return@launch
            editSeedRows = seed
            editing = true
        }
    }
```

`leaveEdit()` 暂时只加请求号与清种子(revision 的改动在 Task 3):

```kotlin
    private fun leaveEdit() {
        editEnterSeq++
        if (editing) { editing = false; revision++; editTarget = null; editSeedRows = null }
    }
```

两个入口改调它:`openEdit = { editing = true }` → `openEdit = { enterEdit() }`;HomeScreen 的 `onEditHome = { editing = true }` → `onEditHome = ::enterEdit`。

预读那几十毫秒里用户也可能按返回 / MENU 把设置关掉(不经 `leaveEdit`),回来的预读不能再把编辑页弹到首页上:`popShell()` 与 `leaveSettings()` 开头各加一行 `editEnterSeq++`(HOME 走 `onNewIntent` → `leaveEdit()`,已覆盖)。

`onCreate` 里 `setContent` 之前(`warmAppsPage()` 旁边)加 `AppEntryCache.clear()`(理由见它的 KDoc:新实例的 revision 从 0 重新数)。
EditScreen 调用处加 `initialRows = editSeedRows,`,`onPickIcon` 改为:

```kotlin
                        onPickIcon = { row, pkg -> pickIcon(pkg).also { if (it) { editTarget = row to pkg; editSeedRows = null } } },
```

- [ ] **Step 8: 构建 + 全部单测**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL,全部测试通过。

- [ ] **Step 9: 模拟器验证(unitedu-tv-2)——首帧有图、焦点放大与淡入同时开始**

```bash
S=emulator-5570 bash scripts/perf/edit-jank/setup.sh
S=emulator-5570 bash scripts/perf/edit-jank/suite.sh t2
bash scripts/perf/edit-jank/report.sh t2
python3 scripts/perf/edit-jank/sheet.py /tmp/edit-jank/A_t2.mp4 /tmp/edit-jank/sheet_A_t2
sips -s format jpeg /tmp/edit-jank/sheet_A_t2.png --out docs/screenshots/edit-smooth/t2-A.jpg
ffprobe -v error -show_entries frame=pts_time -of csv=p=0 /tmp/edit-jank/A_t2.mp4 | head -40
```

Expected:
- `t2-A.jpg` 里编辑页出现的第一帧起就是图片卡,**没有一帧是纯文字占位**;第 1 层第 1 张的放大 + 柔光在编辑页可见的头 2 帧(按 pts ≤ 50 ms)内开始,不再晚 0.5 s。
- `summary_t2.txt` 的 A 段 trace 里不再有「UI 47 ms + RT 93 ms 后 100 ms 空档」那一帧;按键帧 UI 比 before 低(主线程不读盘)。
- 冷路径(没命中)核对一次:`adb -s emulator-5570 shell am force-stop com.uniteduone.launcher` 后冷启动,首页卡片刚出现就立刻进编辑页,仍能正常换成真卡、焦点落第 1 张(与改前一致即可)。

- [ ] **Step 10: e2e 回归(编辑页)**

```bash
DEV=emulator-5570 python3 scripts/e2e/j_edit.py
```

Expected: 全部 PASS(含「换卡片图 → 返回落回同一张卡,0.3 s 内快速进出」)。

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/{AppEntryCache.kt,EditEntry.kt,EditScreen.kt,HomeScreen.kt,MainActivity.kt} app/src/test/java/com/uniteduone/launcher/{AppEntryCacheTest.kt,EditEntryTest.kt} docs/screenshots/edit-smooth/t2-A.jpg
git commit -m "perf(edit): 编辑页首帧用进程级卡片缓存、layout 进页前预读(R167 / 报告 #2 #9)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 首页与外壳在编辑页期间留在组合里(报告 #1)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(首页那段 `FadeSwitch`、外壳的显示条件、`layoutRevision`、`leaveEdit()`、`onLayoutWritten`)
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt`(`layoutRevision` 参数、数据新鲜度、KDoc)
- Test: `app/src/test/java/com/uniteduone/launcher/HomeChannelsTest.kt`(加用例)

**Interfaces:**
- Consumes:Task 2 的 `EditScreen(onLayoutWritten)`、`enterEdit()`、`loadAppEntries`。
- Produces:`HomeScreen(..., layoutRevision: Int = 0, ...)`;`MainActivity.layoutRevision`(编辑页写盘落地 +1);`leaveEdit()` 不再 `revision++`。Task 4 在本任务的首页调用处外面包绘制让路。

**行为裁定(写进 R167)**:改前首页在编辑页期间被拆掉,回来重建、焦点落 (0,0)。改后首页与所有整屏浮层一样在底下冻结目标:从设置进的编辑页 → 退回设置 → 关设置时,焦点回到**打开设置之前**的那一格(顶栏「设置」药丸打开的回药丸,MENU 键打开的回那张卡);R161 空桌面进的编辑页退出时,目标是空桌面按钮的行号(负数)→ 按 `homeTargetRow` 落第一行第一张。目标行在编辑页里被删掉 / 移空 → 补位行(既有规则)。**编辑页里按 HOME** 同理:`onNewIntent` 收掉编辑页与外壳后,首页按冻结的目标还原(与从设置 / 关于页按 HOME 回来一致,T9 实测语义),不再落 (0,0)——写进 R167 裁定与 CLAUDE.md 焦点表(Task 11)。

- [ ] **Step 1: 写失败(或钉住既有行为)的测试**

在 `HomeChannelsTest.kt` 末尾加(用该文件已有的帮手 `appRow(lr, vararg pkgs)`,它在文件中部 owner 裁定那一段声明;`EMPTY_EDIT_ROW` 是 `GtvTopBar.kt` 里的公开常量,直接可见):

```kotlin
    // 删行后 layout.json 下标整体前移:原第 3 行(layoutRow 3)变成 layoutRow 2。
    private val afterDelete = listOf(appRow(0, "a", "b"), appRow(1, "c"), appRow(2, "d"))

    /** R167:编辑页里删掉了首页焦点所在的中间那一行(原 layoutRow 2):补位的是下一行(下标前移后同为 2),列夹到它的格数(Review Focus 4) */
    @Test fun deletedMiddleTargetRowAfterEditLandsOnNextRow() =
        assertEquals(2 to 0, homeTargetCell(afterDelete, tgtLayoutRow = 2, tgtCol = 3))

    /** R167:删掉的是最后一行(原 layoutRow 3,已不存在):落到现在的最后一行 */
    @Test fun deletedLastTargetRowAfterEditLandsOnLastRow() =
        assertEquals(2 to 0, homeTargetCell(afterDelete, tgtLayoutRow = 3, tgtCol = 1))

    /** R167:R161 空桌面进的编辑页(目标是按钮的负行号)加了应用回来:落第一行第一张 */
    @Test fun emptyDesktopTargetLandsOnFirstCardAfterEdit() =
        assertEquals(0 to 0, homeTargetCell(listOf(appRow(0, "a")), tgtLayoutRow = EMPTY_EDIT_ROW, tgtCol = 0))
```

- [ ] **Step 2: 跑测试**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*HomeChannelsTest'`
Expected: 新用例 PASS——它们钉住的是**既有**的补位规则(改前就过,不是红灯;本任务让编辑页回来的路径开始依赖它,所以先钉住)。行为变化本身由 Step 6 的模拟器焦点落点与 Step 7 的 e2e 验收。

- [ ] **Step 3: 首页加 `layoutRevision`,数据新鲜度按两个量判**

`HomeScreen.kt` 参数表 `revision: Int = 0,` 之后加:

```kotlin
    /** R167:编辑页写盘落地一次 +1(MainActivity.layoutRevision)。首页在编辑页底下(留在组合里、不画)按新 layout.json 重读;
     *  卡片数据经 [AppEntryCache] 按 revision 复用,不重新解码。与 [revision] 一起决定数据新不新鲜。 */
    layoutRevision: Int = 0,
```

`loadedRevision` 那段换成:

```kotlin
    var loadedKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val loaded by produceState<Pair<List<Row>, Map<String, String>>?>(initialValue = null, ctx, revision, layoutRevision) {
        val key = revision to layoutRevision
        value = withContext(Dispatchers.IO) {
            val rows = runCatching { buildRows(ctx, revision) }.getOrDefault(emptyList())
            val titles = runCatching { Titles.read(ctx) }.getOrDefault(emptyMap())
            rows to titles
        }
        // **紧跟在 value 之后、同一次恢复里写**:中间没有挂起点,两次快照写入会被同一帧的
        // 重组一起看到,不会出现「新数据已到但还标着不新鲜」的中间态。
        loadedKey = key
    }
```

`val stale = loadedRevision != revision` → `val stale = loadedKey != (revision to layoutRevision)`。
`tgtLayoutRow` 的 KDoc 里「这棵树只在冷启动 / 进出编辑页时才重建,那两条路本来就该落在第一张卡」改为「这棵树只在冷启动时重建(R167 起编辑页开着时也留在组合里,回来按冻结的目标还原)」;`appliedLanding` 的 KDoc 里「首页只在冷启动 / 进出编辑页时重建」同样改为「首页只在冷启动时重建」。

- [ ] **Step 4: MainActivity——首页去掉 FadeSwitch、外壳保留组合、`layoutRevision`**

字段区加:

```kotlin
    /**
     * R167:编辑页写盘落地一次 +1。只喂首页([HomeScreen] 的 layoutRevision),首页在编辑页底下按新 layout.json 重读;
     * **不碰 revision**——那会让编辑页自己、壁纸 / 主题色、氛围底、应用页缓存跟着全部重读(报告 E 的根因 2)。
     */
    private var layoutRevision by mutableStateOf(0)
```

`leaveEdit()` 改为:

```kotlin
    /**
     * R167:不再 `revision++`。编辑页只改 layout.json,首页早已随每次写盘([layoutRevision])在底下重读好;换卡片图那条路
     * 自己会 revision++。首页与外壳都留在组合里,退出时不重建(报告 #1)。
     */
    private fun leaveEdit() {
        editEnterSeq++
        if (editing) { editing = false; editTarget = null; editSeedRows = null }
    }
```

首页那段(注释「**R147(2026-09-30 Gordon…)**」起到 `HomeScreen(...)` 外面的 `FadeSwitch` 结束)换成:

```kotlin
            // **R167(报告 perf-2026-10-10 #1)**:编辑页开着时首页**也留在组合里**,不再包 FadeSwitch、不再有残影:
            // previewing 让路(不可聚焦、不收键、看门狗与还原效果让路、目标冻结——与选择器 / 设置外壳同一套),退出编辑页时不重建、不重读。
            // 编辑页在它上面淡入 / 残影淡出,盖在上面的永远是编辑页。R147 的「首页残影在底下淡出」随之取消:首页始终不透明,
            // 编辑页的淡入就是一次标准的交叉淡化(少一个整屏离屏层,报告 #4)。
            HomeScreen(
                previewing = overlayOpen || editing,
                onEditHome = ::enterEdit,
                idle = idle,
                screensaver = screensaverActive,
                idleContent = homeSettings.idleContent,
                onSettings = ::openSettings,
                onApps = ::openApps,
                onInputs = ::openInputs,
                focusNonce = focusNonce,
                revision = revision,
                layoutRevision = layoutRevision,
                showDate = homeSettings.showDate,
                showWeekday = homeSettings.showWeekday,
                cardsPerRow = homeSettings.cardsPerRow,
                showTitles = homeSettings.showTitles,
                onFocusedCard = { focusedCard = it },
                onFocusedChannel = { focusedOnChannel = it },
                onVerticalGeometry = { homeVertical = it },
                cardMenu = cardMenu,
                cardMenuItems = remember(cardMenu) { cardMenu?.let { cardMenuItems(it) } ?: emptyList() },
                onCardMenuDismiss = ::closeCardMenu,
                renameTarget = renameTarget,
                onRenameSave = ::onRenameSave,
                onRenameCancel = { renameTarget = null; focusNonce++ },
                moving = moving,
                moveLanding = moveLanding,
                onRowsShown = { shownRows = it },
                onPageShiftState = { pageShiftState = it },
                gradientInBackdrop = gradientBaked,
                onContentAlpha = { backdrop.homeContentAlpha = it },
            )
```

(参数逐项照改前那份抄,只改 `previewing`、`onEditHome`,加 `layoutRevision`;执行时以改前代码为准逐项核对,一个不漏。)上方注释块里「唯一的例外:**编辑页仍然独占那一层**」一段改为「R167 起编辑页也不例外:首页留在组合里,编辑页盖在上面」。同步改掉随之过期的注释:`SettingsFade.kt` 的 `previewMotion` 里「(MainActivity 首页 / 编辑页各一个 FadeSwitch)」改为「(R167 起首页常驻、只有编辑页一个 FadeSwitch)」;`pageShiftState` 上方「编辑页替换首页时首页不在组合里、没人上报,这里归 0」改为「R167 起编辑页期间首页照常上报(被编辑页的不透明底盖住,不影响画面)」;外壳那段「编辑页开着时外壳不在组合里」改为「编辑页开着时外壳以残影语义留在组合里」。

EditScreen 调用处加:

```kotlin
                        onLayoutWritten = { layoutRevision++ },
```

外壳:在 `val shellLast = remember { … }` 之后,把显示条件改为编辑页期间也保留组合(残影语义,`live = shellShown` 为假 → `LocalPageGhost` 为真 → 不可聚焦、不收返回键、不回调;退出编辑页时同一份组合直接变回活的,不重建):

```kotlin
            // R167:编辑页开着时外壳也留在组合里(透明度随 shellMotion.a 走到 0,残影语义让路),退出编辑页时同一份组合变回活的,
            // 「布局」页不在动画中间重新组合落地(报告 E 的根因 1)。
            val shellKept = editing && shellStack.isNotEmpty()
            if ((shellVisible || shellKept) && shellLast[0].isNotEmpty()) {
```

(原行是 `if (shellVisible && shellLast[0].isNotEmpty()) {`,块内不变。)

- [ ] **Step 5: 构建 + 全部单测**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL,全部通过。

- [ ] **Step 6: 模拟器验证(unitedu-tv-2)——退出不重建、焦点落点**

```bash
S=emulator-5570 bash scripts/perf/edit-jank/setup.sh
S=emulator-5570 bash scripts/perf/edit-jank/suite.sh t3
bash scripts/perf/edit-jank/report.sh t3
python3 scripts/perf/edit-jank/sheet.py /tmp/edit-jank/E_t3.mp4 /tmp/edit-jank/sheet_E_t3
sips -s format jpeg /tmp/edit-jank/sheet_E_t3.png --out docs/screenshots/edit-smooth/t3-E.jpg
```

Expected:E 段 trace 第一帧不再有整棵 HomeScreen 组合(按键帧组合时长明显低于 before 的 11–20 ms)、没有 1920×1080 的 `DrawGainmapBitmap` 纹理上传;E 段 mp4 帧数多于 before 的 14 帧。

焦点落点(每条走完截图 + `dumpui` 断言聚焦节点,存 `docs/screenshots/edit-smooth/t3-focus-*.jpg`):
1. 首页焦点放到第 3 行第 2 张 → MENU 打开设置 → 布局 → 编辑桌面 → 返回 → 返回(关设置)→ 焦点回第 3 行第 2 张(改前是第 1 行第 1 张)。
2. 顶栏「设置」药丸打开设置 → 布局 → 编辑桌面 → 返回 → 返回 → 焦点回「设置」药丸。
3. 首页焦点在第 3 行 → 进编辑页把第 3 层删掉(胶囊「删除」→ 确定)→ 返回 → 返回 → 焦点落补位那一行(原第 4 行),不丢。
4. 放下一次搬运后 **0.1 s 内**按返回退出(`input keyevent 23; sleep 0.1; input keyevent 4`)→ 退出动画里预览框中的首页已是新顺序,不先画旧顺序再跳(Review Focus 5)。
5. 空桌面(推一份只有空行的 layout.json)→「立即前往」进编辑页 → 加一个应用 → 返回 → 焦点落第一行第一张。

**放下写盘那一帧的闸门(复审 2)**:首页留在底下之后,每次写盘落地(`layoutRevision++`)首页都在主线程上整页重组一次(报告里首页组合 11–20 ms),而落地恰在放下的抬起动画(150 ms)里。suite 的 D 段以返回取消收尾、不写盘,量不到这一下,另跑一段「拿起 → 右移 → 放下」:

```bash
source scripts/env.sh; S=emulator-5570 source scripts/perf/edit-jank/u.sh
# 进编辑页同 suite.sh(k 20; k 19 19 21 21 21; k 23; sleep 1.2; k 20 23; sleep 1.5),然后:
run W_t3 6 23 22 23
python3 scripts/perf/edit-jank/ana.py $T/W_t3; python3 scripts/perf/edit-jank/fsum.py $T/W_t3.pftrace
```

通过条件:W 段 UI 线程最长帧 ≤ D_t3 段的 UI p90 + 4 ms,且 perfetto 里放下之后 300 ms 内没有 > 16 ms 的 `Recomposer:recompose` 切片(release 构建没开 composition tracing,trace 里认不出是哪个组件在重组——报告「测量方法」末条;这里按时长判。注意放下时编辑页自己也会重组一次(rows 换成新顺序、目标换格),所以一条 > 16 ms 的切片不一定是首页那一下:不通过就先上下面的去抖再复测 W 段——去抖后仍不通过,说明慢的是编辑页自己放下那一帧,记进 WORKLOG 交 Task 5 的「只重组焦点层」一并看,不再在首页这边加码;去抖本身对正确性无害,误判的代价只是多了它)。**不通过**:首页的 `layoutRevision` 改成去抖——MainActivity 另设 `private var homeLayoutRevision by mutableStateOf(0)`(喂给 HomeScreen 的 `layoutRevision`),在 `setContent` 里:

```kotlin
            // R167 复审 3:只在编辑页开着时去抖(放下的抬起动画里不让底下的首页整页重组);编辑页一关,
            // editing 进了 key、效果当场重跑、不等——退出后才落地的写盘(Review Focus 5,NonCancellable)也立即追上。
            LaunchedEffect(layoutRevision, editing) {
                if (editing) delay(600)
                homeLayoutRevision = layoutRevision
            }
```

(没有 `return` 守卫;读到的 `editing` 本身就是 key,铁律 6 成立;不是闩,每次两个量变化都重新比。)`leaveEdit()` 里再当场 `homeLayoutRevision = layoutRevision`,让退出那一帧就开始重读、不等效果下一帧重跑。**只用 `LaunchedEffect(layoutRevision)` + `delay` 是错的**:退出后才落地的那次写盘也会被延后 600 ms,首页在编辑页残影淡完之后还画旧顺序再跳——正是 Review Focus 5 要防的形态。Activity 重建时两个量都从 0 起、首页本来就整份重读;HOME 走 `onNewIntent → leaveEdit()`,同上当场追上。复测 W 段并在 WORKLOG 写两次的数字。

- [ ] **Step 7: e2e 回归**

```bash
for j in j_edit j_home j_pkg j_overlays j_settings j_persist j_recreate; do DEV=emulator-5570 python3 scripts/e2e/$j.py || echo "FAIL $j"; done
```

Expected: 全部 PASS。`j_pkg` 里「编辑页开着时卸载」覆盖 Review Focus 1;若某条断言写死了「退出编辑页后首页落 (0,0)」,按本任务的行为裁定改断言(改成「落回打开设置前那一格」),并在提交信息里写明。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/{MainActivity.kt,HomeScreen.kt} app/src/test/java/com/uniteduone/launcher/HomeChannelsTest.kt docs/screenshots/edit-smooth/t3-*.jpg scripts/e2e
git commit -m "perf(edit): 编辑页期间首页与外壳留在组合里,退出不重建不重读(R167 / 报告 #1)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 转场期间少一个整屏层——首页被盖满后不画(报告 #4)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/EditEntry.kt`(加 `editCoversHome`)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(首页外包绘制让路、外壳透明时不画)
- Test: `app/src/test/java/com/uniteduone/launcher/EditEntryTest.kt`

**Interfaces:**
- Consumes:Task 3 的常驻 `HomeScreen(...)` 调用。
- Produces:`internal fun editCoversHome(editing: Boolean, elapsedMs: Long): Boolean`;`internal const val EDIT_COVER_SLACK_MS = 50L`。

**为什么只做这些**:Task 3 已经拿掉首页残影那一层整屏 alpha 层;剩下的整屏层(首页那一层的缩放 + 圆角 + 投影、编辑页淡入层、外壳内容淡出层)都是设计本身要的动效。编辑页内部的非焦点架子离屏层由 Task 6 处理。这里再省下「编辑页已经整屏不透明之后,底下还在画的首页」——页内每一帧都少画一整个首页。

- [ ] **Step 1: 写失败的测试**

在 `EditEntryTest.kt` 加:

```kotlin
    @Test fun homeStillDrawnWhileEditFadesIn() = assertFalse(editCoversHome(editing = true, elapsedMs = GtvLayout.EDIT_SWAP_MS.toLong()))
    @Test fun homeHiddenAfterFadeInPlusSlack() = assertTrue(editCoversHome(editing = true, elapsedMs = GtvLayout.EDIT_SWAP_MS + EDIT_COVER_SLACK_MS))
    /** 退出编辑页当场就画:编辑页残影从 1 开始淡出,底下的首页必须已经在 */
    @Test fun notEditingAlwaysDrawsHome() = assertFalse(editCoversHome(editing = false, elapsedMs = 10_000))
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*EditEntryTest'`
Expected: 编译失败,`editCoversHome` 未定义。

- [ ] **Step 3: 实现**

`EditEntry.kt` 加:

```kotlin
/** 编辑页淡入走完之后再多等这么久才停画首页:FadeSwitch 的淡入跑在帧时钟上,宿主卡顿时最后一帧可能比 EDIT_SWAP_MS 晚一点。 */
internal const val EDIT_COVER_SLACK_MS = 50L

/**
 * 编辑页已经整屏不透明地盖住首页那一层([editing] 且淡入已走完)→ 首页不必再画(R167,报告 #4)。只影响绘制:首页照旧在组合里、
 * 焦点账本照旧冻结。退出编辑页(editing = false)当场恢复绘制——编辑页残影从不透明开始淡出,底下必须已经是首页。
 */
internal fun editCoversHome(editing: Boolean, elapsedMs: Long): Boolean =
    editing && elapsedMs >= GtvLayout.EDIT_SWAP_MS + EDIT_COVER_SLACK_MS
```

`MainActivity` `setContent` 里(首页那一层 `Box` 之内、`HomeScreen` 之前)加计时,并把 Task 3 的 `HomeScreen(...)` 包进一个只在绘制阶段判断的 Box(加 `import androidx.compose.ui.draw.drawWithContent`、`import androidx.compose.ui.MotionDurationScale`;`coroutineContext` 是 `LaunchedEffect` 接收者 CoroutineScope 的成员,不用另外 import;`MotionDurationScale` 由 Compose 的窗口 Recomposer 放进效果协程的上下文,`Animatable` / `tween` 读的也是它):

```kotlin
            // R167:编辑页淡入走完(整屏不透明)之后首页不画——只在绘制阶段读,不重组首页;editing 一假当场恢复。
            var editSettled by remember { mutableStateOf(false) }
            LaunchedEffect(editing) {
                editSettled = false
                if (!editing) return@LaunchedEffect
                // FadeSwitch 的淡入吃系统「动画时长缩放」(设置里的「动画缩放」行读的就是它;CLAUDE.md:animator_duration_scale
                // 对我们的 Compose 动画生效),帧时钟的墙钟时间不吃:不折算的话缩放 > 1 时淡入才走一半首页就不画了,
                // 半透明的编辑页底下卡片整排消失。按同一个缩放把墙钟换算成「动画时间」;缩放为 0(动画关)时当场算盖满。
                val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                val t0 = withFrameNanos { it }
                while (true) {
                    val wallMs = (withFrameNanos { it } - t0) / 1_000_000
                    val animMs = if (scale <= 0f) Long.MAX_VALUE else (wallMs / scale).toLong()
                    if (editCoversHome(true, animMs)) break
                }
                editSettled = true
            }
            Box(Modifier.fillMaxSize().drawWithContent { if (!(editing && editSettled)) drawContent() }) {
                HomeScreen(
                    // …Task 3 的参数原样…
                )
            }
```

外壳内容那个 `Box(Modifier.fillMaxSize().graphicsLayer { … })` 的修饰链末尾加一句,透明到 0 时不画(编辑页期间它留在组合里,见 Task 3):

```kotlin
                        .drawWithContent { if (live || shellMotion.a.value > 0f) drawContent() },
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: PASS,BUILD SUCCESSFUL。

- [ ] **Step 5: 模拟器——层数、页内帧、R129f 截图(unitedu-tv-2)**

```bash
S=emulator-5570 bash scripts/perf/edit-jank/setup.sh
S=emulator-5570 bash scripts/perf/edit-jank/suite.sh t4
bash scripts/perf/edit-jank/report.sh t4
grep -A2 "passes\|drawLayer" /tmp/edit-jank/summary_t4.txt /tmp/edit-jank/summary_before.txt
```

Expected:A / E 段 `drawLayer p50` 比 before 至少少 1(首页残影层没了);B / C / D 段 `renderpasses/frame` 与 RT p50 不高于 t3。

**取舍闸门(写进 WORKLOG)**:停画首页会让首页自己的 RenderNode 图层在退出时重新录一次。比较 t4 与 t3 的 E 段:若 t4 的 E 段 `flush layers` 比 t3 多 30 ms 以上、或 E 段出现 1920×1080 纹理上传,**而**页内 B / C / D 的 RT p50 改善不足 1 ms → 撤掉 `drawWithContent` 那层让路(保留 `editCoversHome` 与测试,注释写明为什么不用),否则保留。

R129f 截图核对(进页途中焦点卡柔光不被裁):

(`screencap -p` 在这台 AVD 上单次约 9.7 s,赶不上转场中途——用录屏按 pts 取帧,CLAUDE.md「量动画用 mp4 真实时间戳」。)

```bash
S=emulator-5570
adb -s $S shell settings put global animator_duration_scale 10
# 首页 → 设置 → 布局,光标停在「编辑桌面」上;开录后按确定(慢放 10 倍,转场约 4 s)
adb -s $S shell rm -f /sdcard/t4mid.mp4
(adb -s $S shell screenrecord --time-limit 8 --size 960x540 /sdcard/t4mid.mp4 &); sleep 1.5
adb -s $S shell input keyevent 23; sleep 8
adb -s $S pull /sdcard/t4mid.mp4 /tmp/edit-jank/t4mid.mp4
adb -s $S shell settings put global animator_duration_scale 1.0
# 取按键后约 2 s(= 1× 的 200 ms)那一帧:按键在录屏开始后约 1.5 s
ffmpeg -y -loglevel error -ss 3.5 -i /tmp/edit-jank/t4mid.mp4 -frames:v 1 /tmp/edit-jank/t4-mid-enter.png
sips -s format jpeg /tmp/edit-jank/t4-mid-enter.png --out docs/screenshots/edit-smooth/t4-mid-enter.jpg
```

Expected:第 1 层第 1 张卡的柔光四边完整(右缘、下缘没有被直线截断);与 `before-A.jpg` 同一时刻比没有新的裁切。这一帧同时核对 `editSettled` 的缩放折算:慢放 10 倍下编辑页还半透明,底下应仍是首页的卡片(没折算的话首页在 1× 的 450 ms 就停画,这一帧底下只剩壁纸)。再以观众身份看一遍 A_t4.mp4 的 1× 录像:淡入是一次连贯的交叉淡化,中途没有变暗一下再亮回来。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/{EditEntry.kt,MainActivity.kt} app/src/test/java/com/uniteduone/launcher/EditEntryTest.kt docs/screenshots/edit-smooth/t4-*.jpg
git commit -m "perf(edit): 编辑页盖满后首页与透明外壳不画(R167 / 报告 #4)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 焦点变化不再整页重组(报告 #5)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/EditScreen.kt`

**Interfaces:**
- Consumes:Task 2 之后的 EditScreen。
- Produces:`private class EditShelfCallbacks`(Task 7 沿用 `cb`);`AppShelfView(col: () -> Int, …)`(原 `col: Int`);`internal object EditPerf { const val TAG = "UnitedUPerf"; fun log(what: String) }`。

**原理**:EditScreen 顶层读了 `holder`(→ `activeShelf`、按键提示、`noHolder`)、`target`(胶囊表比对那段)和 `shelfCol[si]`,而每次焦点移动 `report()` 都会写这三样 → 整个 EditScreen 重组;再加上每次组合新建的 `shelves`、胶囊表、`::req` / `::report` 函数引用与 lambda,5 层 + 全部卡片都不可跳过。改法:顶层只读 `derivedStateOf` 派生出来、只在「换了层 / 换了区域 / 有无持有者」时才变的值;`shelfCol` 改由各层自己读;回调整页只建一份。焦点账本的读写点一个都不动。

- [ ] **Step 1: 先加重组探针、量改前**

`EditScreen.kt` 文件末尾加:

```kotlin
/**
 * R167:编辑页重组探针。`adb shell setprop log.tag.UnitedUPerf D` 后冷启动,每次整页 / 每层重组在 logcat 打一行;平时 isLoggable 为假,不打。
 * 量「焦点移动时谁在重组」用(报告 #5 的验收),不影响任何行为。
 */
internal object EditPerf {
    const val TAG = "UnitedUPerf"
    private val on: Boolean by lazy { android.util.Log.isLoggable(TAG, android.util.Log.DEBUG) }
    fun log(what: String) { if (on) android.util.Log.d(TAG, what) }
}
```

EditScreen 函数体开头(`val ctx = LocalContext.current` 之后)加 `SideEffect { EditPerf.log("page") }`;`AppShelfView` 函数体开头加 `SideEffect { EditPerf.log("shelf ${shelf.row}") }`。

```bash
source scripts/env.sh && gradle --no-daemon assembleRelease
S=emulator-5570 bash scripts/perf/edit-jank/setup.sh
adb -s emulator-5570 shell setprop log.tag.UnitedUPerf D
adb -s emulator-5570 shell am force-stop com.uniteduone.launcher
adb -s emulator-5570 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.uniteduone.launcher/.MainActivity; sleep 5
# 进编辑页(同 suite.sh:k 20; k 19 19 21 21 21; k 23; k 20 23; k 23),停 2 s 后:
adb -s emulator-5570 logcat -c
for i in 1 2 3 4 5; do adb -s emulator-5570 shell input keyevent 22; sleep 0.6; done
adb -s emulator-5570 logcat -d -s UnitedUPerf | sort | uniq -c
```

Expected(改前):每按一下右键至少一行 `page`,以及 5 行 `shelf 0..4` 左右——即 5 次右键 ≥ 5 次 `page`、≥ 25 次 `shelf`。把输出存进 WORKLOG 草稿。

- [ ] **Step 2: 顶层改读派生值**

加 import:`androidx.compose.runtime.snapshots.Snapshot`。`viewRows` / `shelves` 记住:

```kotlin
    val viewRows = remember(rows, loadedFor) { view() }
    val shelves = remember(viewRows, channelLabelsNow, channelContentNow) { shelvesFor(viewRows) }
    /** 每层的胶囊表(组合期一份,同一份 shelves 下是同一个实例,传给各层不会让它们不可跳过)。 */
    val chipsByShelf = remember(shelves) { shelves.indices.map { shelfChips(shelves, it) } }
```

「组合阶段发现目标那一层的胶囊表变了」那段(`lastTargetChips` … `lastTargetChips[0] = …`)整段换成:

```kotlin
    // **组合阶段发现目标那一层的胶囊表变了 → 同步冻结并按身份重定位**(Task 13 复审 2;原理见改前注释,语义不变)。
    // R167:按层记上一份胶囊表,只在胶囊表真的换了一份(= shelves 变了)时比对;读 target / carry 不登记依赖——
    // 否则每次焦点移动(report → setTarget)都会让整页重组(报告 #5)。
    val lastChips = remember { arrayOfNulls<List<List<ShelfChip>>>(1) }
    val prevAll = lastChips[0]
    if (prevAll !== chipsByShelf) {
        lastChips[0] = chipsByShelf
        if (prevAll != null) Snapshot.withoutReadObservation {
            val t = target
            val before = prevAll.getOrNull(t.shelf)
            if (t.zone == ShelfZone.CHIPS && carry == null && before != null && before != chipsByShelf.getOrNull(t.shelf)) {
                val to = resolveEditTarget(shelves, t, targetChip)
                retarget(to, shelfChips(shelves, to.shelf).getOrNull(to.index))
            }
        }
    }
```

`activeShelf`、`noHolder`、按键提示改成派生值。**这段放在 `report()` 定义之后、「组合阶段发现目标那一层的胶囊表变了」那段之前**——看门狗(在纵向位移之前)的 key 要读 `noHolder`,声明必须排在它前面:

```kotlin
    // R167:三个只在「换了层 / 换了区域 / 有无持有者」时才变的派生值;层内左右移动焦点时它们不变,整页不重组(报告 #5)
    val activeShelfRaw by remember { derivedStateOf { (if (retargetTick != retargetDone || paused) target else holder ?: target).shelf } }
    val noHolder by remember { derivedStateOf { holder == null } }
    val hintZone by remember { derivedStateOf { holder?.zone ?: target.zone } }
```

原 `val activeShelf = (if (retargeting || paused) target else holder ?: target).shelf.coerceIn(0, shelves.lastIndex)` → `val activeShelf = activeShelfRaw.coerceIn(0, shelves.lastIndex)`(保留原注释);原 `val noHolder = holder == null` 删掉(看门狗 key 里的 `noHolder` 现在读派生值);`EditTopBar(counts = editCounts(viewRows), hints = editHintSet(holder?.zone ?: target.zone, carry != null))` → `EditTopBar(counts = remember(viewRows) { editCounts(viewRows) }, hints = editHintSet(hintZone, carry != null))`;`val arrows = …` → `val arrows = remember(viewRows, carry) { carry?.let { carryArrows(viewRows, it.pos) } ?: emptySet() }`。

改完 `grep -n "holder\|target\b\|shelfCol\[" EditScreen.kt`,逐个确认**组合阶段**(不在 lambda / 效果 / 回调里)不再直接读 `holder`、`target`、`shelfCol`。

- [ ] **Step 3: 回调整页一份**

在「按键截获」那段之前加:

```kotlin
/** R167:编辑页传给各层的回调,整页只建一份;字段都转调「最新那一份」实现(rememberUpdatedState),组合多少次都是同一个实例(报告 #5)。 */
@Stable
private class EditShelfCallbacks(
    val req: (ShelfSpot) -> FocusRequester,
    val report: (ShelfSpot, Boolean) -> Unit,
    val place: (ShelfSpot, Float) -> Unit,
    val onChip: (Int, ShelfChip) -> Unit,
    val onChoice: (NewRowChoice) -> Unit,
    val onAddTile: (Int) -> Unit,
    val onCardClick: (Int, Int) -> Unit,
)
```

(放在文件顶层,`EditOverlay` 之后。)EditScreen 里(`press` 之后、纵向位移之前):

```kotlin
    val ghostNow by rememberUpdatedState(ghost)
    val onChipNow by rememberUpdatedState<(Int, ShelfChip) -> Unit>(::onChip)
    val onChoiceNow by rememberUpdatedState<(NewRowChoice) -> Unit>(::onChoice)
    val reportNow by rememberUpdatedState<(ShelfSpot, Boolean) -> Unit>(::report)
    val openPickerNow by rememberUpdatedState<(Int, ShelfSpot) -> Unit>(::openPicker)
    val openCardMenuNow by rememberUpdatedState<(Int, Int) -> Unit>(::openCardMenu)
    val cb = remember {
        EditShelfCallbacks(
            req = { s -> requesters.getOrPut(s) { FocusRequester() } },
            report = { s, got -> reportNow(s, got) },
            place = { s, x -> centers[s] = x },
            onChip = { si, chip -> onChipNow(si, chip) },
            onChoice = { c -> onChoiceNow(c) },
            onAddTile = { si -> if (!ghostNow && carry == null && overlay == null) openPickerNow(si, ShelfSpot(si, ShelfZone.CARDS, 0)) },
            // 卡片的 onClick 只可能来自指针 / 无障碍(确定键在根上就被截走):给卡片菜单
            onCardClick = { si, ci -> if (!ghostNow && carry == null && overlay == null) openCardMenuNow(si, ci) },
        )
    }
```

`shelves.forEachIndexed` 里三处调用改为:

```kotlin
                    val measure = remember(si) { Modifier.onSizeChanged { heights[si] = it.height } }
                    when (shelf) {
                        is Shelf.AppShelf -> AppShelfView(
                            shelf = shelf,
                            chips = chipsByShelf[si],
                            focus = { focusAnim.value },
                            active = si == activeShelf,
                            all = all,
                            metrics = metrics,
                            col = { shelfCol[si] ?: 0 },
                            carriedCol = carry?.pos?.takeIf { it.row == si }?.col,
                            arrows = arrows,
                            arrowColor = highlight,
                            accent = accent,
                            screenW = screenW,
                            req = cb.req,
                            report = cb.report,
                            place = cb.place,
                            onChip = { cb.onChip(si, it) },
                            onAddTile = { cb.onAddTile(si) },
                            onCardClick = { ci -> cb.onCardClick(si, ci) },
                            modifier = measure,
                        )
                        is Shelf.ChannelShelf -> ChannelShelfView(
                            shelf = shelf,
                            chips = chipsByShelf[si],
                            focus = { focusAnim.value },
                            active = si == activeShelf,
                            accent = accent,
                            loadPosters = loadsPosters(si, activeShelf),
                            req = cb.req,
                            report = cb.report,
                            place = cb.place,
                            onChip = { cb.onChip(si, it) },
                            modifier = measure,
                        )
                        Shelf.NewRowShelf -> NewRowShelfView(
                            shelfIndex = si,
                            shelves = shelves,
                            focus = { focusAnim.value },
                            active = si == activeShelf,
                            accent = accent,
                            req = cb.req,
                            report = cb.report,
                            place = cb.place,
                            onChoice = cb.onChoice,
                            modifier = measure,
                        )
                    }
```

(`place` 局部变量删掉。)`AppShelfView` 签名 `col: Int,` → `col: () -> Int,`,函数体 `val cur = col.coerceIn(…)` → `val cur = col().coerceIn(0, (shelf.apps.size - 1).coerceAtLeast(0))`。

- [ ] **Step 4: 构建 + 全部单测**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL,全部通过(`EditIronRulesTest` 等按源码扫描的测试若对 `activeShelf` 的写法有断言,按新写法更新断言、在提交信息里写明)。

- [ ] **Step 5: 量改后(同 Step 1 的命令)**

Expected:5 次右键 → `page` 0 次、`shelf 0` ≤ 5 次、其它层 0 次;再按 5 次下键 → 每次 1 行 `page` + 至多 2 层(新旧焦点层)。另跑:

```bash
S=emulator-5570 bash scripts/perf/edit-jank/suite.sh t5 && bash scripts/perf/edit-jank/report.sh t5
```

Expected:B / C / D 段「key recomp p50」低于 before。

- [ ] **Step 6: e2e 回归**

```bash
for j in j_edit j_channels; do DEV=emulator-5570 python3 scripts/e2e/$j.py || echo "FAIL $j"; done
```

Expected:全部 PASS(`j_channels` 的「重新授权 → 焦点落这一层第一颗胶囊」覆盖胶囊表比对那段的改写)。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/EditScreen.kt
git commit -m "perf(edit): 焦点移动只重组焦点所在那一层(R167 / 报告 #5)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 非焦点架子改 `ModulateAlpha`(报告 #7,带像素闸门)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/EditShelves.kt`(`ShelfLayout.DIM_BY_MODULATE`)
- Modify: `app/src/main/java/com/uniteduone/launcher/EditShelfParts.kt`(`ShelfFrame`)

**Interfaces:**
- Produces:`ShelfLayout.DIM_BY_MODULATE: Boolean`(通过与否都要提交这个常量)。Task 7 的跨层滑入距离读它:为假(仍是离屏层)时从下方滑入要收短,见 Task 7 的 `carryArriveDp`。

**原理与风险**:现在非焦点架子的 0.62 是 `graphicsLayer { alpha }` 的整层离屏(约 2000×520 px × 4 层,有任何东西在动就每帧重画)。`CompositingStrategy.ModulateAlpha` 让 HWUI 把透明度乘进每个绘制调用与子图层,不开离屏层,也就没有 R129f 的裁切。与整层淡化只在「东西叠在一起」处不同:卡片压着白 5.5% 的玻璃底,理论差约 0.38 × 3.4% ≈ 1.3%(≈ 3/255)。HWUI 是否把父层透明度乘进子 RenderNode(卡片自己的 R49 / R86 图层)没有文档保证——**所以本任务以截图逐像素比对为闸门,不过就回退、记为不做。** JVM 单测测不了渲染行为,本任务没有单测,闸门就是验收。

- [ ] **Step 1: 先截改前参照(unitedu-tv-3,带窗口)**

```bash
emulator -avd unitedu-tv-3 -port 5572 -no-snapshot -no-audio -gpu host &
adb -s emulator-5572 wait-for-device && adb -s emulator-5572 shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
source scripts/env.sh && gradle --no-daemon assembleRelease
S=emulator-5572 bash scripts/perf/edit-jank/setup.sh
# 进编辑页(同 suite.sh 的按键),焦点停在第 1 层第 1 张,静止 3 s(柔光进焦 1.2 s 走完)
adb -s emulator-5572 exec-out screencap -p > /tmp/edit-jank/t6-auto-1.png; sleep 1
adb -s emulator-5572 exec-out screencap -p > /tmp/edit-jank/t6-auto-2.png
python3 scripts/perf/edit-jank/pixdiff.py /tmp/edit-jank/t6-auto-1.png /tmp/edit-jank/t6-auto-2.png 0 0
# 下 2 次到第 2 层第 1 张(第 1 层变非焦点),静止 3 s 后再截一组
adb -s emulator-5572 shell input keyevent 20; sleep 0.6; adb -s emulator-5572 shell input keyevent 20; sleep 3
adb -s emulator-5572 exec-out screencap -p > /tmp/edit-jank/t6-auto-down.png
```

Expected:两张静止截图完全相同(`max 0`,证明画面是新的、静止的);否则重截。

- [ ] **Step 2: 实现**

`EditShelves.kt` 的 `ShelfLayout` 里(`LAYER_PAD` 之后)加:

```kotlin
    /**
     * R167(报告 #7):非焦点架子的 0.62 用 `CompositingStrategy.ModulateAlpha`(HWUI 逐个绘制乘透明度,不开每帧重画的整层离屏)。
     * 与整层淡化只在卡片压着玻璃底处差约 1%;闸门见计划 2026-10-10-edit-page-smoothness Task 6。false = 回到整层离屏(改前)。
     */
    const val DIM_BY_MODULATE = true
```

`EditShelfParts.kt` 的 `ShelfFrame` 里(加 `import androidx.compose.ui.graphics.CompositingStrategy`):

```kotlin
            .graphicsLayer {
                alpha = shelfAlpha(focus())
                // R167:见 ShelfLayout.DIM_BY_MODULATE。不开离屏层也就没有 R129f 的裁切;外层 layout 撑大的 LAYER_PAD 保留(回退时还要用)
                compositingStrategy = if (ShelfLayout.DIM_BY_MODULATE) CompositingStrategy.ModulateAlpha else CompositingStrategy.Auto
            }
```

同步改 `ShelfFrame` 的 KDoc:「整层淡化的离屏层四边各撑大 …」一句前加「`DIM_BY_MODULATE` 为假时」。

- [ ] **Step 3: 构建 + 全部单测**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 像素闸门**

```bash
S=emulator-5572 bash scripts/perf/edit-jank/setup.sh
# 与 Step 1 同样的按键与停顿
adb -s emulator-5572 exec-out screencap -p > /tmp/edit-jank/t6-mod-1.png
# 下 2 次、静止 3 s
adb -s emulator-5572 exec-out screencap -p > /tmp/edit-jank/t6-mod-down.png
python3 scripts/perf/edit-jank/pixdiff.py /tmp/edit-jank/t6-auto-1.png /tmp/edit-jank/t6-mod-1.png 8 0.5
python3 scripts/perf/edit-jank/pixdiff.py /tmp/edit-jank/t6-auto-down.png /tmp/edit-jank/t6-mod-down.png 8 0.5
```

**通过条件**:两组都退出码 0(整屏最大通道差 ≤ 8/255、平均 ≤ 0.5/255)。再看一眼差异图确认差异只在卡片与玻璃底重叠处:

```bash
python3 -c "from PIL import Image, ImageChops; d=ImageChops.difference(Image.open('/tmp/edit-jank/t6-auto-1.png').convert('RGB'), Image.open('/tmp/edit-jank/t6-mod-1.png').convert('RGB')); d.point(lambda v: min(255, v*30)).save('/tmp/edit-jank/t6-diff-x30.png')"
for f in t6-auto-1 t6-mod-1 t6-diff-x30; do sips -s format jpeg /tmp/edit-jank/$f.png --out docs/screenshots/edit-smooth/$f.jpg; done
```

换层过渡中途(`animator_duration_scale 10`,同 Task 4 Step 5 的录屏取帧法:开录后按下键,`ffmpeg -ss` 取按键后约 1 s 那一帧;不用 `screencap -p`,它单次约 9.7 s 赶不上)再看一次:新焦点层的焦点卡柔光完整、旧焦点层没有闪烁或局部发亮。

**不通过**:`DIM_BY_MODULATE` 改回 `false`(代码路径保留),在 WORKLOG 与报告里写「报告 #7 未做:ModulateAlpha 在 HWUI 上与整层淡化的观感不一致(max X / mean Y,差异图 `t6-diff-x30.jpg`),保持整层离屏」,跳到 Step 6 只提交截图与这一行常量。

- [ ] **Step 5: 通过时量收益**

```bash
S=emulator-5572 bash scripts/perf/edit-jank/suite.sh t6 && T=/tmp/edit-jank bash scripts/perf/edit-jank/report.sh t6
```

Expected:B / C / D 段 `renderpasses/frame p50` 比 t5 少(4 个非焦点架子的离屏遍没了),RT p50 不升。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/{EditShelves.kt,EditShelfParts.kt} docs/screenshots/edit-smooth/t6-*.jpg
git commit -m "perf(edit): 非焦点架子改 ModulateAlpha,去掉每帧重画的整层离屏(R167 / 报告 #7)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: 拿起搬运的位置过渡(报告 #8)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/EditCarrySlide.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/EditShelves.kt`(`ShelfLayout.CARRY_ARRIVE_DP`)
- Modify: `app/src/main/java/com/uniteduone/launcher/EditScreen.kt`(`arrival` 状态、`stepCarry` / `cancelCarry`、`AppShelfView`、`clipPastShelfEnd` 的 `shiftPx` 参数)
- Modify: `docs/superpowers/specs/2026-10-08-edit-shelves-and-channels-design.md` §2.5(改设计先改它)
- Test: `app/src/test/java/com/uniteduone/launcher/CarrySlideTest.kt`

**Interfaces:**
- Consumes:Task 5 的 `AppShelfView`(`col: () -> Int`、`cb`);Task 6 的 `ShelfLayout.DIM_BY_MODULATE`。
- Produces:
  - `internal fun carrySlideStarts(old: List<String>, new: List<String>, oldOffsets: FloatArray, pitch: Float): FloatArray?`
  - `internal fun arrivalSign(fromRow: Int, toRow: Int): Int`
  - `internal data class CarryArrival(val row: Int, val pkg: String, val sign: Int)`
  - `internal class ShelfSlide`(`dx(ci)` / `dy(ci)` 只在绘制阶段读)
  - `ShelfLayout.CARRY_ARRIVE_DP = 40f`、`internal fun carryArriveDp(sign: Int, modulate: Boolean = ShelfLayout.DIM_BY_MODULATE): Float`

**设计(R167 裁定)**:卡片的组合身份仍按位置(第 ci 格,不包 `key(pkg)`——包了焦点节点会跟着包走、没有焦点事件,`holder` 过期,铁律 2 的判据失效)。换位只做**绘制**位移:某一格换上了原来在第 j 格的卡,就从 (j − ci) 个卡距(加上它在旧格里还没走完的位移)滑回 0,200 ms `FastOutSlowIn`(= 换层的 `SHIFT_MS`,不新造时长与曲线)。同层左右、跨层搬走(源层邻卡合拢)、搬入(目标层邻卡让开)、取消(全部滑回)都走同一个函数。**跨层搬入的那张卡从来的方向滑入 40 dp,而不是整段飞过去**:它画在目标架子里,目标架子在淡到 1 之前是 alpha 图层(Task 6 不通过时是离屏层),整段飞行的前半程在图层外会被裁掉(R129f)。**被裁的不只是卡身,还有焦点卡的放大 + 60 dp 柔光**:撑大后的图层只比架子多 `LAYER_PAD`(= 柔光 60 dp),卡片上方离架子顶 56 dp、下方离架子底只有 15.4 dp——从上方滑入 40 dp 时柔光仍在图层内,从下方滑入 40 dp 时柔光下缘会越出图层约 28 dp、被一条直线截掉(复审 2 实算)。所以距离由 `carryArriveDp(sign)` 定:非焦点架子不开离屏层(Task 6 通过,`DIM_BY_MODULATE`)时两个方向都是 40 dp;仍是离屏层时按「卡 + 放大 + 柔光不出图层」收短(从下方约 12 dp)。位移用 `drawWithContent` 平移,`onGloballyPositioned` 量到的仍是布局位置,`centers` 不受影响。

- [ ] **Step 1: 先改设计稿**

`docs/superpowers/specs/2026-10-08-edit-shelves-and-channels-design.md` §2.5「动效」那一条后面加一行:

```markdown
- 拿起换位(R167):被搬的卡与让位的邻卡按旧 → 新位置滑动 200 ms(`FastOutSlowIn`,同换层);搬到相邻架子时源层邻卡合拢、目标层邻卡让开,被搬的卡从来的方向滑入(40 dp;非焦点架子仍是离屏层时从下方收短到柔光不出图层,R129f);取消时全部滑回。只是绘制位移,焦点始终在被搬的卡上。
```

- [ ] **Step 2: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/CarrySlideTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R167 拿起换位的绘制位移(纯函数)。卡距记作 P。 */
class CarrySlideTest {
    private val p = 100f
    private fun zeros(n: Int) = FloatArray(n)

    /** a 右移一格:第 0 格换上 b(原在第 1 格)从 +P 滑回,第 1 格换上 a 从 −P 滑回 */
    @Test fun swapRight() =
        assertArrayEquals(floatArrayOf(p, -p, 0f), carrySlideStarts(listOf("a", "b", "c"), listOf("b", "a", "c"), zeros(3), p), 0f)

    /** 搬走中间那张:后面的卡往左合拢,从 +P 滑回 */
    @Test fun removalClosesGap() =
        assertArrayEquals(floatArrayOf(0f, p), carrySlideStarts(listOf("a", "b", "c"), listOf("a", "c"), zeros(3), p), 0f)

    /** 搬进第 1 格:搬进来的那张水平不动(竖直滑入另算),后面的卡从 −P 滑回 */
    @Test fun insertionOpensGap() =
        assertArrayEquals(floatArrayOf(0f, 0f, -p), carrySlideStarts(listOf("a", "c"), listOf("a", "x", "c"), zeros(2), p), 0f)

    /** 上一步还没走完(第 1 格的 a 还在 −0.4P 处)又右移一步:a 到第 2 格,从 (1−2)P − 0.4P 接着滑,不先跳回(Review Focus 2) */
    @Test fun midAnimationStepContinuesFromCurrentOffset() {
        val s = carrySlideStarts(listOf("b", "a", "c"), listOf("b", "c", "a"), floatArrayOf(0.4f * p, -0.4f * p, 0f), p)!!
        assertEquals(-1.4f * p, s[2], 1e-3f)
        assertEquals(p, s[1], 1e-3f)
        assertEquals(0.4f * p, s[0], 1e-3f)
    }

    /** 整行换了内容(上移 / 下移 / 删行让这一层显示别的行):不动画 */
    @Test fun wholesaleChangeIsNotAnimated() = assertNull(carrySlideStarts(listOf("a", "b"), listOf("x", "y"), zeros(2), p))
    @Test fun unchangedIsNotAnimated() = assertNull(carrySlideStarts(listOf("a", "b"), listOf("a", "b"), zeros(2), p))
    /** 一次少两张(不是搬运能造成的):不动画 */
    @Test fun twoRemovedIsNotAnimated() = assertNull(carrySlideStarts(listOf("a", "b", "c"), listOf("a"), zeros(3), p))

    @Test fun arrivalComesFromWhereItWas() {
        assertEquals(-1, arrivalSign(fromRow = 0, toRow = 1))   // 从上一层下来:从上方滑入
        assertEquals(1, arrivalSign(fromRow = 2, toRow = 1))    // 从下一层上来:从下方滑入
        assertEquals(0, arrivalSign(fromRow = 1, toRow = 1))
    }

    /**
     * 跨层滑入的起点上,卡 + 焦点放大 + 柔光仍在撑大后的图层内(R129f)——非焦点架子还是离屏层(DIM_BY_MODULATE 为假)时才有图层可裁。
     * 卡片上方离架子顶 CARDS_TOP、下方离架子底 CARDS_BOTTOM,图层四边各多 LAYER_PAD。
     */
    @Test fun arrivalGlowStaysInsideLayer() {
        val cardH = ShelfLayout.APP_SHELF_HEIGHT - ShelfLayout.CARDS_TOP - ShelfLayout.CARDS_BOTTOM
        val overflow = GtvLayout.APP_FOCUS_GLOW_DP + cardH * (GtvLayout.APP_FOCUS_SCALE - 1f) / 2f
        val up = carryArriveDp(sign = -1, modulate = false)
        val down = carryArriveDp(sign = 1, modulate = false)
        assertTrue("从上方:$up", ShelfLayout.CARDS_TOP + ShelfLayout.LAYER_PAD - up >= overflow - 1e-3f)
        assertTrue("从下方:$down", ShelfLayout.CARDS_BOTTOM + ShelfLayout.LAYER_PAD - down >= overflow - 1e-3f)
        assertTrue("仍有可见的滑入", down > 0f && up == ShelfLayout.CARRY_ARRIVE_DP)
    }
    /** 不开离屏层就没有图层可裁:两个方向都是整段 40 dp;同层(sign 0)不滑 */
    @Test fun modulateUsesFullArrivalDistance() {
        assertEquals(ShelfLayout.CARRY_ARRIVE_DP, carryArriveDp(sign = 1, modulate = true), 0f)
        assertEquals(ShelfLayout.CARRY_ARRIVE_DP, carryArriveDp(sign = -1, modulate = true), 0f)
        assertEquals(0f, carryArriveDp(sign = 0, modulate = false), 0f)
    }
}
```

- [ ] **Step 3: 跑测试,确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*CarrySlideTest'`
Expected: 编译失败,`carrySlideStarts` / `arrivalSign` / `carryArriveDp` 未定义。

- [ ] **Step 4: 实现纯函数与状态**

`ShelfLayout` 里(`LIFT_MS` 之后)加:

```kotlin
    /** R167:从相邻架子搬进来的卡从来的方向滑入这么远(dp)——上限;非焦点架子仍是离屏层时由 [carryArriveDp] 收短(R129f)。 */
    const val CARRY_ARRIVE_DP = 40f
```

`app/src/main/java/com/uniteduone/launcher/EditCarrySlide.kt`:

```kotlin
package com.uniteduone.launcher

import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.Job

/**
 * R167 拿起搬运的位置过渡(报告 perf-2026-10-10 #8)。卡片的组合身份仍按位置(第 ci 格),焦点账本不动;
 * 换位只是**绘制**位移:某一格换上了原来在第 j 格的卡,就从 (j − ci) 个卡距、加上它在旧格里还没走完的位移,滑回 0。
 * 只认搬运造成的三种变化——同一批卡换了顺序(左右搬、取消)、少了一张(搬走 / 移出)、多了一张(搬进来);
 * 其余(整行换了内容:上移 / 下移 / 删行、数据重读)返回 null = 不动画、原样到位。[oldOffsets] = 旧的每一格此刻画在的位移。
 */
internal fun carrySlideStarts(old: List<String>, new: List<String>, oldOffsets: FloatArray, pitch: Float): FloatArray? {
    if (old == new) return null
    val shaped = (new.size == old.size && new.toSet() == old.toSet()) ||
        (new.size == old.size - 1 && isSubsequence(new, old)) ||
        (new.size == old.size + 1 && isSubsequence(old, new))
    if (!shaped) return null
    return FloatArray(new.size) { ci ->
        val j = old.indexOf(new[ci])
        if (j < 0) 0f else (j - ci) * pitch + oldOffsets.getOrElse(j) { 0f }
    }
}

private fun isSubsequence(small: List<String>, big: List<String>): Boolean {
    var i = 0
    for (x in big) if (i < small.size && small[i] == x) i++
    return i == small.size
}

/** 从别的架子搬进来的卡从哪边滑入:上一层来的从上方(−1),下一层来的从下方(+1)。 */
internal fun arrivalSign(fromRow: Int, toRow: Int): Int = when {
    fromRow < toRow -> -1
    fromRow > toRow -> 1
    else -> 0
}

/**
 * 跨层搬入的滑入距离(dp)。[sign] −1 = 从上方来,+1 = 从下方来,0 = 不滑。非焦点架子不开离屏层([modulate],Task 6)时没有图层可裁,
 * 用整段 [ShelfLayout.CARRY_ARRIVE_DP];仍是离屏层时收短到「卡 + 焦点放大 + 60 dp 柔光」在起点也不出撑大后的图层(R129f):
 * 卡片下方离架子底只有 CARDS_BOTTOM(15.4 dp),从下方来的只剩约 12 dp。
 */
internal fun carryArriveDp(sign: Int, modulate: Boolean = ShelfLayout.DIM_BY_MODULATE): Float {
    if (sign == 0) return 0f
    if (modulate) return ShelfLayout.CARRY_ARRIVE_DP
    val cardH = ShelfLayout.APP_SHELF_HEIGHT - ShelfLayout.CARDS_TOP - ShelfLayout.CARDS_BOTTOM
    val overflow = GtvLayout.APP_FOCUS_GLOW_DP + cardH * (GtvLayout.APP_FOCUS_SCALE - 1f) / 2f
    val room = (if (sign < 0) ShelfLayout.CARDS_TOP else ShelfLayout.CARDS_BOTTOM) + ShelfLayout.LAYER_PAD - overflow
    return ShelfLayout.CARRY_ARRIVE_DP.coerceAtMost(room.coerceAtLeast(0f))
}

/** 最近一次跨层搬入(或跨层取消放回):搬到了第 [row] 层、是哪个包、从哪边来。 */
internal data class CarryArrival(val row: Int, val pkg: String, val sign: Int)

/**
 * 一层卡片条的滑动状态。普通对象 + 一个快照状态 [progress](0 → 1,已含缓动):AppShelfView 在 SideEffect 里更新(早于这一帧的绘制),
 * 卡片在绘制阶段读 [dx] / [dy]——位移每帧变化只重画,不重组、不改布局(`centers` 仍是布局位置)。
 */
internal class ShelfSlide {
    var lastApps: List<String>? = null
    var startX = FloatArray(0)
    var arriveSlot = -1
    var arriveDy = 0f
    var job: Job? = null
    val progress = mutableFloatStateOf(1f)
    fun dx(ci: Int): Float = startX.getOrElse(ci) { 0f } * (1f - progress.floatValue)
    fun dy(ci: Int): Float = if (ci == arriveSlot) arriveDy * (1f - progress.floatValue) else 0f
    fun currentX(n: Int): FloatArray = FloatArray(n) { dx(it) }
}
```

- [ ] **Step 5: 跑测试,确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*CarrySlideTest'`
Expected: PASS。

- [ ] **Step 6: 接进编辑页**

`EditScreen.kt` 加 import:`androidx.compose.animation.core.animate`、`androidx.compose.ui.graphics.drawscope.translate`。

状态(`carry` 旁边):

```kotlin
    /** R167:最近一次跨层搬入 / 跨层取消放回(只给滑入方向用,不进任何焦点账本;下一次跨层时整个换掉)。 */
    var arrival by remember { mutableStateOf<CarryArrival?>(null) }
```

`stepCarry` 在 `applyView(...)` 之前加:

```kotlin
        if (pos.row != c.pos.row) v.getOrNull(c.pos.row)?.apps?.getOrNull(c.pos.col)?.let { arrival = CarryArrival(pos.row, it, arrivalSign(c.pos.row, pos.row)) }
```

`cancelCarry` 在 `rows = c.original` 之前加:

```kotlin
        if (c.pos.row != c.from.row) view().getOrNull(c.pos.row)?.apps?.getOrNull(c.pos.col)?.let { arrival = CarryArrival(c.from.row, it, arrivalSign(c.pos.row, c.from.row)) }
```

`AppShelfView` 调用处加 `arrival = arrival?.takeIf { it.row == si },`;签名加 `arrival: CarryArrival?,`。函数体开头(`val si = shelf.row` 之后)加:

```kotlin
    // R167 拿起换位的滑动(见 EditCarrySlide.kt)。SideEffect 在本帧绘制之前跑:第一帧就画在起点,不会先闪到终点再滑
    val slide = remember { ShelfSlide() }
    val slideScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pitchPx = with(density) { (metrics.cardWidth.value + ShelfLayout.CARD_GAP).dp.toPx() }
    val apps = shelf.apps
    SideEffect {
        val prev = slide.lastApps
        slide.lastApps = apps
        if (prev == null || prev == apps) return@SideEffect
        val starts = carrySlideStarts(prev, apps, slide.currentX(prev.size), pitchPx)
        slide.job?.cancel()
        if (starts == null) {
            slide.startX = FloatArray(0); slide.arriveSlot = -1; slide.progress.floatValue = 1f
            return@SideEffect
        }
        slide.startX = starts
        val inserted = apps.indexOfFirst { it !in prev }
        slide.arriveSlot = if (inserted >= 0 && arrival != null && apps[inserted] == arrival.pkg) inserted else -1
        val sign = arrival?.sign ?: 0
        slide.arriveDy = sign * with(density) { carryArriveDp(sign).dp.toPx() }
        slide.progress.floatValue = 0f
        slide.job = slideScope.launch {
            animate(0f, 1f, animationSpec = tween(ShelfLayout.SHIFT_MS, easing = FastOutSlowInEasing)) { v, _ -> slide.progress.floatValue = v }
        }
    }
```

每张卡外面那个 `Box` 的修饰链改为(`zIndex` 让被搬的卡在滑动途中盖住邻卡;平移放在裁切之内,裁切仍按布局位置算架子右端):

```kotlin
                    Box(
                        Modifier
                            .zIndex(if (ci == cur || ci == carriedCol) 1f else 0f)
                            // 裁切边界仍按布局位置算——平移放在裁切之内,裁切线就停在架子右端,不跟着卡走(这正是要的)。
                            // 但「整张都在架子外就不画」那条捷径要看**画在哪**:被挤出架子的卡(布局位置已在右端外)滑动途中还画在架子里,
                            // 按布局位置判会当场消失。所以把滑动位移传进去(见下面对 clipPastShelfEnd 的改动)
                            .clipPastShelfEnd(shiftPx = { slide.dx(ci) }) {
                                shelfCardClipRight(shelfW - (ShelfLayout.PAD_START + ci * pitch + dx.value.value), metrics.cardWidth.value, ci == cur)
                            }
                            // R167:拿起换位的滑动,只在绘制阶段平移(不改布局、不改 onGloballyPositioned 量到的位置)
                            .drawWithContent { translate(slide.dx(ci), slide.dy(ci)) { this@drawWithContent.drawContent() } }
                            .graphicsLayer {
                                shadowElevation = lift.value * ShelfLayout.CARRY_SHADOW.dp.toPx()
                                shape = RoundedCornerShape(metrics.cardCorner)
                            }
                            .then(if (carried) Modifier.carryArrows(arrows, arrowColor) else Modifier),
                    ) {
```

`EditScreen.kt` 顶部的 `clipPastShelfEnd` 加一个可选参数(缺省 0 = 改前行为,别的调用点不动):

```kotlin
private fun Modifier.clipPastShelfEnd(shiftPx: () -> Float = { 0f }, rightDp: () -> Float?): Modifier = drawWithContent {
    val rd = rightDp() ?: return@drawWithContent drawContent()
    val r = rd.dp.toPx()
    // 内容此刻画在本地 x ∈ [shiftPx, shiftPx + 宽](R167 拿起换位的平移在裁切之内);左缘都过了裁切线才整张不画
    if (r <= shiftPx()) return@drawWithContent
    clipRect(left = -size.width * 4f, top = -size.height * 4f, right = r, bottom = size.height * 5f) { this@drawWithContent.drawContent() }
}
```

(`EditIronRulesTest.theShelfNeverClipsTheFocusedCardOrItsLeftEdge` 只查 `clipPastShelfEnd` 在用、没有 `clipToBounds`、没有 `clipRect(left = 0f`,不受影响。)

- [ ] **Step 7: 构建 + 全部单测**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL,全部通过。

- [ ] **Step 8: 模拟器验证(unitedu-tv-2,1×,mp4 pts)**

```bash
S=emulator-5570 bash scripts/perf/edit-jank/setup.sh
S=emulator-5570 bash scripts/perf/edit-jank/suite.sh t7 && bash scripts/perf/edit-jank/report.sh t7
python3 scripts/perf/edit-jank/sheet.py /tmp/edit-jank/D_t7.mp4 /tmp/edit-jank/sheet_D_t7
sips -s format jpeg /tmp/edit-jank/sheet_D_t7.png --out docs/screenshots/edit-smooth/t7-D.jpg
```

逐帧量被搬的卡(D 段第一下右键之后):`ffmpeg -fps_mode passthrough -vf scale=960:540` 抽帧 + `ffprobe` 取 pts,追踪被搬卡片左缘的 x(箭头 / 抬起阴影可作为标记)。

Expected:
- 每一步左右换位:被搬的卡与邻卡在约 200 ms(pts)里连续滑过一个卡距,中间至少 6 帧处于中间位置;不再是一帧换位。
- 下搬一层:源层后面的卡合拢、目标层后面的卡让开,被搬的卡从上方滑入,卡身、焦点描边与柔光都不被直线截断(看 `t7-D.jpg`)。再上搬一层(从下方滑入)同样核对柔光下缘——Task 6 不通过(仍是离屏层)时这一向的距离被 `carryArriveDp` 收短,R129f 截图要在 `animator_duration_scale 10` 下按 Task 4 Step 5 的录屏取帧法取滑入中途那一帧,存 `t7-up-mid.jpg`。
- 连按两下右键(间隔 0.15 s,`input keyevent 22; sleep 0.15; input keyevent 22`):第二步从当前位置接着滑,没有回跳(Review Focus 2),截图 `t7-double.jpg`。
- 取消(返回):所有卡滑回原位。
- D 段 RT p50、掉帧比例不高于 t5 / t6。以观众身份看一遍 D_t7.mp4 的 1× 录像。

- [ ] **Step 9: e2e 回归**

```bash
DEV=emulator-5570 python3 scripts/e2e/j_edit.py
```

Expected:全部 PASS(拿起 → 左移 / 下搬一层 → 放下写盘、返回取消不写盘;焦点断言不受绘制位移影响)。

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/{EditCarrySlide.kt,EditShelves.kt,EditScreen.kt} app/src/test/java/com/uniteduone/launcher/CarrySlideTest.kt docs/superpowers/specs/2026-10-08-edit-shelves-and-channels-design.md docs/screenshots/edit-smooth/t7-*.jpg
git commit -m "feat(edit): 拿起搬运补位置过渡,同层换位 / 跨层搬入 / 取消都滑动(R167 / 报告 #8)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: 自家 baseline profile + 会话安装带 `.dm`(报告 #10,闸门)

**Files:**
- Create: `app/src/main/baseline-prof.txt`
- Create(闸门通过才建): `app/src/main/java/com/uniteduone/launcher/DexMetadata.kt`、`app/src/test/java/com/uniteduone/launcher/DexMetadataTest.kt`
- Modify(闸门通过才改): `app/src/main/java/com/uniteduone/launcher/SelfUpdate.kt`

**Interfaces:**
- Produces(闸门通过时):`internal const val APK_BASELINE_PROFILE = "assets/dexopt/baseline.prof"`、`internal fun writeDexMetadata(apk: File, out: File): Boolean`、`SelfUpdate.dexMetadataDisabled` / `SelfUpdate.lastSessionHadDm`。

**背景**(报告「会话安装后的编译状态」):APK 里的 `baseline.prof` 只有 3954 字节,全是 androidx 库自带规则;会话安装装完是 `verify`,ART 安装时不读 APK 内嵌画像,要等夜里后台 dexopt。两步:① 先让 APK 带上覆盖本应用全部代码的画像(ProfileInstaller 首启写入 + 夜里编译就能吃到,零风险);② 实验「会话里随 base.apk 写一份 base.dm」能否让系统**安装时**就 speed-profile。② 是有代价的:`.dm` 被包管理器判为非法会让**整个更新装不上**,而它在 A95L 的首版 Android 14 ART 上没验证过——所以只在模拟器实验通过后才接进代码,并带「失败一次就不再带」的自愈。

- [ ] **Step 1: 加 baseline profile 规则**

`app/src/main/baseline-prof.txt`:

```
# UnitedU 自己的 baseline profile(R167,报告 perf-2026-10-10 #10)。
# 首页 / 编辑页 / 设置外壳的启动与按键路径都在本包里;dex 只有约 1.4 MB,整包全编比逐条挑路径稳,且不会因改代码漏掉新路径。
# 构建开了 R8 混淆,AGP 会按 mapping 改写这里的规则。Compose / tv-material 等库的规则由各自的 AAR 带进来。
HSPLcom/uniteduone/launcher/**;->**(**)**
Lcom/uniteduone/launcher/**;
```

- [ ] **Step 2: 构建并确认画像变大**

```bash
source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease 2>&1 | tee /tmp/edit-jank/build-t8.log | tail -3
grep -i "baseline\|profile" /tmp/edit-jank/build-t8.log | head
unzip -l app/build/outputs/apk/release/app-release.apk | grep dexopt
```

Expected:BUILD SUCCESSFUL,日志里没有「invalid rule」类警告;`assets/dexopt/baseline.prof` 明显大于 3954 字节(本包类方法全部入画像,应到数十 KB)。若 AGP 对通配报错,把规则换成逐类前缀(`Lcom/uniteduone/launcher/MainActivity;` 等,按 `app/src/main/java/com/uniteduone/launcher/` 下文件名逐个写 `HSPLcom/uniteduone/launcher/<类>;->**(**)**`),直到构建通过且画像变大。

- [ ] **Step 3: 模拟器核对 ProfileInstaller 路径(unitedu-tv-3)**

```bash
S=emulator-5572; P=com.uniteduone.launcher
adb -s $S uninstall $P; adb -s $S install app/build/outputs/apk/release/app-release.apk
adb -s $S shell cmd package set-home-activity $P/.MainActivity
adb -s $S shell am start -n $P/.MainActivity; sleep 15
adb -s $S logcat -d | grep -i profileinstaller | tail -3
adb -s $S shell pm bg-dexopt-job
adb -s $S shell dumpsys package dexopt | grep -A3 "\[$P\]"
```

Expected:logcat 有 `Installing profile for com.uniteduone.launcher`;后台编译后读回 `status=speed-profile`。再用 `S=emulator-5572 bash scripts/perf/edit-jank/suite.sh t8prof`(跳过 setup 的 `compile -m speed`,保持 bg-dexopt 后的状态)量 H 段按键帧 UI p50,记入 WORKLOG(对照报告里 verify 8.1 / speed 3.1 ms)。

- [ ] **Step 4: `.dm` 实验(unitedu-tv-3,只记录、不改代码)**

```bash
S=emulator-5572; P=com.uniteduone.launcher; O=/tmp/unitedu-dm; rm -rf $O; mkdir -p $O/e1 $O/e2
APK=app/build/outputs/apk/release/app-release.apk; cp $APK $O/app.apk
# E1:dm = APK 里的 baseline.prof 原样当 primary.prof
unzip -p $APK assets/dexopt/baseline.prof > $O/e1/primary.prof
(cd $O/e1 && zip -X -q ../app.dm primary.prof)
adb -s $S uninstall $P; adb -s $S install-multiple $O/app.apk $O/app.dm; echo "E1 exit $?"
adb -s $S shell dumpsys package dexopt | grep -A3 "\[$P\]"
# E2:dm = 本机按同一个包跑出来的画像快照(格式一定是本机 ART 认的)
adb -s $S uninstall $P; adb -s $S install $O/app.apk
adb -s $S shell cmd package set-home-activity $P/.MainActivity
adb -s $S shell am start -n $P/.MainActivity; sleep 20
adb -s $S shell cmd package snapshot-profile $P
adb -s $S shell ls -l /data/misc/profman/ | grep $P     # 快照文件名随系统版本不同(<包>.prof / <包>-primary.prof),按这里列出的取
adb -s $S pull /data/misc/profman/$P-primary.prof $O/e2/primary.prof || adb -s $S pull /data/misc/profman/$P.prof $O/e2/primary.prof
(cd $O/e2 && zip -X -q ../app-e2.dm primary.prof)
adb -s $S uninstall $P; cp $O/app-e2.dm $O/app.dm; adb -s $S install-multiple $O/app.apk $O/app.dm; echo "E2 exit $?"
adb -s $S shell dumpsys package dexopt | grep -A3 "\[$P\]"
# E3:一个坏 dm(随便一段字节)——看失败模式:整个安装失败,还是只是退回 verify
printf 'garbage' > $O/e1/primary.prof; (cd $O/e1 && rm -f ../bad.dm && zip -X -q ../bad.dm primary.prof)
cp $O/bad.dm $O/app.dm; adb -s $S uninstall $P; adb -s $S install-multiple $O/app.apk $O/app.dm; echo "E3 exit $?"
adb -s $S shell dumpsys package dexopt | grep -A3 "\[$P\]"
```

记录三组的退出码与 `status=` / `reason=`(成功的标志是 `status=speed-profile`,`reason=install-dm`)。

**闸门**(铁律:**dm 绝不能让更新装不上**——A95L 的首版 Android 14 ART 在模拟器上验证不了,模拟器上任何「dm 坏了就整个安装失败」的迹象都意味着电视上可能每次更新都先失败一次):
- **E1 成功(`reason=install-dm`)且 E3 安装成功(坏 dm 只是被忽略、退回 `verify`/`install`)** → 做 Step 5–8(会话里带 dm)。
- **E1 成功但 E3 整个安装失败** → **不接进代码**:坏 dm 的失败模式是「整次更新失败」,而 dm 由已装版本写进会话、电视上的 ART 认不认没法提前验证;把实验结果写进 WORKLOG / 报告,作为待 Gordon 定的下一步(例如先发一个只记录、不带 dm 的 Beta 在电视上读 `dumpsys`)。跳到 Step 9。
- **E1 失败、E2 成功** → dm 机制在这版 ART 上可用,但要的是本机格式的画像;要在发版时用模拟器跑快照、随 APK 发 `.dm` 并在清单里加可选字段——超出本计划,**不改代码**,把实验结果与这个方案写进 WORKLOG / 报告,作为待 Gordon 定的下一步。跳到 Step 9。
- **都失败** → 不改代码,记录结论,跳到 Step 9。

- [ ] **Step 5(闸门通过才做): 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/DexMetadataTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** R167:从 APK 取 baseline.prof 包成 .dm(只含 primary.prof)。纯 JVM。 */
class DexMetadataTest {
    private fun zip(f: File, entries: Map<String, ByteArray>) = ZipOutputStream(f.outputStream()).use { z ->
        entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
    }

    @Test fun copiesBaselineProfileAsPrimaryProf() {
        val apk = File.createTempFile("app", ".apk"); val dm = File.createTempFile("app", ".dm")
        val prof = byteArrayOf(0x70, 0x72, 0x6f, 0x00, 1, 2, 3)
        zip(apk, mapOf("classes.dex" to byteArrayOf(9), APK_BASELINE_PROFILE to prof))
        assertTrue(writeDexMetadata(apk, dm))
        ZipFile(dm).use { z ->
            assertArrayEquals(prof, z.getInputStream(z.getEntry("primary.prof")).readBytes())
            assertTrue(z.size() == 1)
        }
    }

    @Test fun noProfileMeansNoDm() {
        val apk = File.createTempFile("app", ".apk"); val dm = File.createTempFile("app", ".dm").apply { delete() }
        zip(apk, mapOf("classes.dex" to byteArrayOf(9)))
        assertFalse(writeDexMetadata(apk, dm))
    }

    @Test fun notAZipMeansNoDm() {
        val apk = File.createTempFile("app", ".apk").apply { writeText("not a zip") }
        assertFalse(writeDexMetadata(apk, File.createTempFile("app", ".dm")))
    }
}
```

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*DexMetadataTest'`
Expected: 编译失败,`writeDexMetadata` 未定义。

- [ ] **Step 6(闸门通过才做): 实现**

`app/src/main/java/com/uniteduone/launcher/DexMetadata.kt`:

```kotlin
package com.uniteduone.launcher

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** AGP 打进 APK 的画像(ProfileInstaller 也读这一份)。 */
internal const val APK_BASELINE_PROFILE = "assets/dexopt/baseline.prof"

/**
 * R167(报告 perf-2026-10-10 #10):从新包里取出 [APK_BASELINE_PROFILE],包成 ART 认的 DexMetadata(只含 `primary.prof` 的 zip)写到 [out]。
 * 自我更新的会话里随 base.apk 一起写 base.dm,系统安装时就按画像 speed-profile 编译,不再先 verify、等夜里后台编译。
 * 取不到画像 / 不是 zip → false(调用方只写 base.apk,与改前一样)。纯 java.util.zip。
 */
internal fun writeDexMetadata(apk: File, out: File): Boolean = runCatching {
    ZipFile(apk).use { zip ->
        val entry = zip.getEntry(APK_BASELINE_PROFILE) ?: return false
        ZipOutputStream(out.outputStream()).use { zo ->
            zo.putNextEntry(ZipEntry("primary.prof"))
            zip.getInputStream(entry).use { it.copyTo(zo) }
            zo.closeEntry()
        }
        true
    }
}.getOrDefault(false)
```

`SelfUpdate.kt`:`object SelfUpdate` 里加

```kotlin
    /**
     * R167:带 base.dm 的会话失败过一次,本进程里之后的会话就不再带。同步失败(写 dm / 开会话 / 提交)在 [install] 里当场不带 dm 重试,
     * 用户看不到失败;异步失败(系统装的时候才拒)由 [SelfUpdateResult] 置位,用户重试一次即可装上——闸门(计划 Task 8 Step 4)
     * 已要求坏 dm 在模拟器上不致命,这一条只是兜底。只在内存里,不落盘(数据格式铁律)。
     */
    @Volatile var dexMetadataDisabled = false
    @Volatile internal var lastSessionHadDm = false
```

`install()` 改成「先带 dm 试一次,**同步失败就当场放弃这个会话、不带 dm 重开一次**」——文件此刻还在(调用方在 `install` 返回后才删),dm 的任何同步故障都不能变成一次失败的更新。权限检查之后的部分换成:

```kotlin
        val installer = ctx.packageManager.packageInstaller
        // 上次确认页被 HOME 关掉会留下一个没人收尾的会话;新一轮开始前清掉
        runCatching { installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } } }
        if (!dexMetadataDisabled) {
            val r = commitSession(ctx, installer, file, withDm = true)
            if (r == ApkInstaller.Result.STARTED) return r
            // 带 dm 的那一次同步失败(写 dm / 开会话 / 提交任一步):本进程之后不再带,当场不带 dm 再来一次
            dexMetadataDisabled = true
            Log.w(TAG, "self-update with base.dm failed synchronously; retrying without it")
        }
        return commitSession(ctx, installer, file, withDm = false)
    }

    /** 开一个会话写 base.apk(+ [withDm] 时 base.dm)并提交。失败时放弃已开的会话、返回 INVALID(与改前同一语义)。 */
    private fun commitSession(ctx: Context, installer: PackageInstaller, file: File, withDm: Boolean): ApkInstaller.Result {
        var id = -1
        val dm = File(ctx.cacheDir, "self-update.dm")
        lastSessionHadDm = false   // 先清:下面任何一步抛出都不会留着上一次会话的值
        return runCatching {
            id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                if (withDm && writeDexMetadata(file, dm)) {
                    dm.inputStream().use { input ->
                        session.openWrite("base.dm", 0, dm.length()).use { out -> input.copyTo(out); session.fsync(out) }
                    }
                    lastSessionHadDm = true
                }
                val callback = Intent(ctx, SelfUpdateResult::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(ctx, id, callback, flags).intentSender)
            }
            Log.i(TAG, "self-update session $id committed (${file.name}, dm=$lastSessionHadDm)")
            ApkInstaller.Result.STARTED
        }.getOrElse { e ->
            // 会话开了却没提交成功:Session.close() 只关句柄,不放弃的话它会一直留在系统里
            if (id != -1) runCatching { installer.abandonSession(id) }
            Log.w(TAG, "self-update session failed (dm=$withDm)", e)
            ApkInstaller.Result.INVALID
        }.also { dm.delete() }
    }
```

(日志行保留 `self-update session … committed` 的前缀:`channels.sh` 的 `committed()` 按它数。)

`SelfUpdateResult.onReceive` 的 `else ->` 分支第一行加:

```kotlin
                // R167:带了 base.dm 的会话失败 → 本进程之后不再带(见 SelfUpdate.dexMetadataDisabled)
                if (SelfUpdate.lastSessionHadDm) SelfUpdate.dexMetadataDisabled = true
```

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: PASS,BUILD SUCCESSFUL。

- [ ] **Step 7(闸门通过才做): 模拟器端到端——会话安装带 dm(unitedu-tv-3)**

```bash
S=emulator-5572; P=com.uniteduone.launcher
cp app/build/outputs/apk/release/app-release.apk /tmp/unitedu-dm/A.apk                              # versionCode 8,带本任务代码
gradle --no-daemon assembleRelease -PversionCodeOverride=9 && cp app/build/outputs/apk/release/app-release.apk /tmp/unitedu-dm/B.apk
adb -s $S uninstall $P; adb -s $S install /tmp/unitedu-dm/A.apk
adb -s $S shell cmd package set-home-activity $P/.MainActivity
adb -s $S shell appops set $P REQUEST_INSTALL_PACKAGES allow; adb -s $S shell appops set $P SYSTEM_ALERT_WINDOW allow
# 打开「设置 → 从手机添加」扫码页(上传服务 8090),然后:
adb -s $S forward tcp:18090 tcp:8090
curl -X POST -H 'X-Requested-With: UnitedU' -F apk=@/tmp/unitedu-dm/B.apk http://127.0.0.1:18090/api/apk
# 系统确认页:焦点默认在 Cancel,按左再确定
adb -s $S shell input keyevent 21; sleep 0.5; adb -s $S shell input keyevent 23; sleep 15
adb -s $S logcat -d -s UnitedU | grep self-update
adb -s $S shell dumpsys package $P | grep versionCode
adb -s $S shell dumpsys package dexopt | grep -A3 "\[$P\]"
```

Expected:`versionCode=9`;dexopt 读回 `status=speed-profile`、`reason=install-dm`。再核对一次失败自愈:装回 A,把 B 换成一个 `assets/dexopt/baseline.prof` 被替换成乱码后用 debug 签名重签的包(`zip` 替换条目 → `zipalign -f 4` → `apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android`;签名不同会先被 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒,所以 A 也要用 debug 签名重签一份装上),上传后应**一次就装上**(闸门已要求 E3 坏 dm 不致命)、读回非 `install-dm` 的 reason、没有失败 Toast;日志里那一行 committed 带 `dm=true`。若这里出现安装失败,说明会话路径与 `adb install-multiple` 的失败模式不同——撤掉 dm 接线(保留 `DexMetadata.kt` 与测试,`dexMetadataDisabled` 初值改 `true` 并注释原因),按闸门「E3 整个安装失败」那一支处理。同步故障的重试路径另验一次:临时把 `writeDexMetadata` 后面的 `openWrite("base.dm", …)` 改成写入后抛异常的探针构建(不提交),上传后日志应有 `retrying without it` 且版本照样升上去。

- [ ] **Step 8(闸门通过才做): e2e 双通道回归**

**先修 `channels.sh` 的版本名替换**(复审 2:它只认 `versionName = "1.0.3"`,本分支已是 `1.2.0-beta.1`,不修的话 A / C 会被构建成 Beta 名,场景判定全错)——即 Task 9 Step 6 的第一处改动,在这里先做、随本任务提交:`build_one` 第一行换成 `sed -i '' -E "s/versionName = \"[^\"]+\"/versionName = \"$2\"/" app/build.gradle.kts`。

```bash
DEV=emulator-5572 scripts/e2e/channels.sh build
```

Expected:5 个场景全过(会话安装路径带 dm 后仍能装 Beta / 回退包)。

- [ ] **Step 9: Commit**

```bash
git add app/src/main/baseline-prof.txt app/src/main/java/com/uniteduone/launcher/ app/src/test/java/com/uniteduone/launcher/ scripts/e2e/channels.sh
git commit -m "perf: 本应用自己的 baseline profile;会话安装带 base.dm(模拟器闸门见 WORKLOG)(R167 / 报告 #10)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(闸门不过时提交信息改为「perf: 本应用自己的 baseline profile(.dm 实验结论见 WORKLOG)」,只提交 `baseline-prof.txt`。)

**电视验证**:随下一个 Beta。画像变大这一半装上后第二天(夜里后台编译过)就生效;`.dm` 这一半要**再下一次**应用内更新才用得上(dm 是由已装版本的安装代码写进会话的)。读回电视上的编译状态需要 adb `dumpsys`(只读、不按键),做不做由 Gordon 定。

---

### Task 9: 关于页——Beta 通道上装着回退包时的说明(文案 a)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/UpdateChannels.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/Update.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/AboutScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`、`values-en/strings.xml`、`values-zh-rTW/strings.xml`
- Modify: `scripts/e2e/channels.sh`
- Test: `app/src/test/java/com/uniteduone/launcher/UpdateChannelsTest.kt`、`AboutStateTest.kt`

**Interfaces:**
- Produces:`enum class UpToDateNote { PLAIN, ROLLBACK_AWAITING_BETA }`;`fun upToDateNote(installedCode: Int, installedName: String, stableCode: Int?, betaCode: Int?): UpToDateNote`;`resolveChannel(..., onUpToDate: (UpToDateNote) -> Unit = {})`;`Update.checkChannel(..., onUpToDate: (UpToDateNote) -> Unit = {})`;`AboutState.LatestRollback`。

**场景**:从 Beta 切回稳定会装上回退包(版本名不带 `-beta`、versionCode = 最新 Beta + 1);再切回 Beta 时,两份清单都比它旧,关于页只说「已是最新」,看起来像切换没生效。改为说明这是回退包、下一个 Beta 发布后检查更新即可装上(本应用不在后台自动检查,所以不说「自动收到」)。

- [ ] **Step 1: 写失败的测试**

`UpdateChannelsTest.kt` 末尾加:

```kotlin
    private fun noteOf(ch: UpdateChannel, installed: Int, name: String, stable: () -> Result<LatestInfo>, beta: () -> Result<LatestInfo> = never): Pair<ChannelUpdate?, UpToDateNote?> {
        var note: UpToDateNote? = null
        val r = resolveChannel(ch, installed, name, 34, fetchStable = stable, fetchBeta = beta, fetchRollback = never) { note = it }
        return r.getOrThrow() to note
    }

    /** 回退包(1.1.0,code 10)在 Beta 通道:稳定 8、Beta 9 都比它旧 → 已是最新,但要说明在等下一个 Beta */
    @Test fun rollbackBuildOnBetaAwaitsNextBeta() =
        assertEquals(null to UpToDateNote.ROLLBACK_AWAITING_BETA, noteOf(UpdateChannel.BETA, 10, "1.1.0", ok(8), ok(9)))
    /** Beta 还没发过(beta.json 404)也一样 */
    @Test fun rollbackBuildWithoutBetaManifestAwaitsNextBeta() =
        assertEquals(null to UpToDateNote.ROLLBACK_AWAITING_BETA, noteOf(UpdateChannel.BETA, 10, "1.1.0", ok(8), net))
    /** 装的就是最新 Beta:普通的已是最新 */
    @Test fun latestBetaIsPlainUpToDate() =
        assertEquals(null to UpToDateNote.PLAIN, noteOf(UpdateChannel.BETA, 9, "1.2.0-beta.1", ok(8), ok(9)))
    /** 普通稳定版用户刚切到 Beta、Beta 还没发:不比稳定版新 → 普通的已是最新 */
    @Test fun stableBuildEqualToLatestIsPlain() =
        assertEquals(null to UpToDateNote.PLAIN, noteOf(UpdateChannel.BETA, 8, "1.1.0", ok(8), net))
    /** 有新版时不报说明 */
    @Test fun noNoteWhenUpdateFound() =
        assertEquals(UpdateKind.BETA to null, noteOf(UpdateChannel.BETA, 8, "1.1.0", ok(8), ok(11)).let { it.first?.kind to it.second })
    /** 稳定通道从不报说明 */
    @Test fun stableChannelNeverNotes() = assertEquals(null to null, noteOf(UpdateChannel.STABLE, 12, "1.1.0", ok(10)))
```

`AboutStateTest.kt`:`checkStatesCarryNoUpdate` 与 `onlyDownloadAndVerifyAreCancellable` 两个列表里各加 `AboutState.LatestRollback`;再加

```kotlin
    @Test fun latestRollbackOffersAnotherCheck() = assertEquals(AboutAction.CHECK, AboutState.LatestRollback.action)
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*UpdateChannelsTest' --tests '*AboutStateTest'`
Expected: 编译失败,`UpToDateNote` / `onUpToDate` / `LatestRollback` 未定义。

- [ ] **Step 3: 实现判定**

`UpdateChannels.kt`(`ChannelUpdate` 之后)加:

```kotlin
/** 「已是最新」是哪一种(R167):普通的,还是装着回退包、在等下一个 Beta。只在 Beta 通道判出「没有新版」时报。 */
enum class UpToDateNote { PLAIN, ROLLBACK_AWAITING_BETA }

/** 装的是回退包(版本名不带 -beta)而且比取到的清单都新:Beta 通道里没有比它新的包,要等下一个 Beta。 */
fun upToDateNote(installedCode: Int, installedName: String, stableCode: Int?, betaCode: Int?): UpToDateNote {
    val known = listOfNotNull(stableCode, betaCode)
    return if (!installedName.contains("-beta") && known.isNotEmpty() && installedCode > known.max()) {
        UpToDateNote.ROLLBACK_AWAITING_BETA
    } else UpToDateNote.PLAIN
}
```

`resolveChannel` 参数表末尾加 `onUpToDate: (UpToDateNote) -> Unit = {},`(KDoc 加一句:「Beta 通道判出没有新版时经 [onUpToDate] 报是哪一种(R167)」);Beta 分支改为:

```kotlin
        if (candidates.isEmpty()) {
            if (stable.isSuccess && beta.isFailure) { // 稳定版被基础版本规则滤掉、Beta 又没发过:已是最新
                onUpToDate(upToDateNote(installedCode, installedName, stable.getOrNull()?.versionCode, null))
                return Result.success(null)
            }
            return Result.failure(stable.exceptionOrNull() ?: UpdateCheckException(CheckFailure.NETWORK))
        }
        if (beta.isFailure) log("beta manifest unavailable; using stable only")
        val best = candidates.filter { isNewer(it.info, installedCode, sdk) }.maxByOrNull { it.info.versionCode }
        if (best == null) onUpToDate(upToDateNote(installedCode, installedName, stable.getOrNull()?.versionCode, beta.getOrNull()?.versionCode))
        return Result.success(best)
```

`Update.checkChannel` 参数表末尾加 `onUpToDate: (UpToDateNote) -> Unit = {},`,调用 `resolveChannel` 时传 `onUpToDate = onUpToDate`。

- [ ] **Step 4: 关于页状态与文案**

`AboutScreen.kt`:`AboutState` 里 `Latest` 之后加

```kotlin
    /** R167:Beta 通道上装着回退包、两份清单都比它旧——仍是「已是最新」,但说明要等下一个 Beta(见 [upToDateNote])。 */
    data object LatestRollback : AboutState() {
        override val action get() = AboutAction.CHECK
    }
```

`check()` 里:

```kotlin
            val result = withContext(Dispatchers.IO) {
                val ch = channel() // 每次检查只读一次,日志与请求用同一个值
                var note = UpToDateNote.PLAIN
                val r = Update.checkChannel(ch, currentVersionCode, BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT) { note = it }
                Triple(ch, r, note)
            }
            if (my != session) return@launch
            val (ch, outcome, note) = result
```

`onSuccess` 里的结果改为:

```kotlin
                    if (up != null) AboutState.Found(up.info, up.kind)
                    else if (note == UpToDateNote.ROLLBACK_AWAITING_BETA) AboutState.LatestRollback
                    else AboutState.Latest
```

`headline()` 的 `when` 加一支:

```kotlin
        AboutState.LatestRollback -> stringResource(R.string.about_latest_rollback_beta) to Tone.GOOD
```

`grep -n "AboutState.Latest" app/src/main/java/com/uniteduone/launcher/*.kt`,其余出现处若是 `when` 分支,同样补上 `LatestRollback`。

三份 `strings.xml` 在 `about_latest` 之后各加一条:

```xml
    <string name="about_latest_rollback_beta">已是最新：这是回退到稳定版的包，下一个 Beta 发布后在这里检查更新即可装上</string>
```

```xml
    <string name="about_latest_rollback_beta">You\'re up to date — this is the rollback-to-stable build. Check again here after the next Beta is out.</string>
```

```xml
    <string name="about_latest_rollback_beta">已是最新：這是回退到穩定版的套件，下一個 Beta 發布後在這裡檢查更新即可安裝</string>
```

- [ ] **Step 5: 跑测试,确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: PASS(含 `CopyTest` 三语同 key、全角标点),BUILD SUCCESSFUL。

- [ ] **Step 6: e2e——修版本名替换 + 加场景**

`scripts/e2e/channels.sh` 的 `build_one` 第一行(只认 `1.0.3` 的那句)换成通用替换(本分支 versionName 已是 `1.2.0-beta.1`,原写法会把 A / C 构建成 Beta 名;Task 8 闸门通过时 Step 8 已先改过,这里跳过):

```bash
  sed -i '' -E "s/versionName = \"[^\"]+\"/versionName = \"$2\"/" app/build.gradle.kts
```

在场景 3 的 `print("  3 前:", …)` 之后、`evidence()` 之前插入:

```python
journey("3c 回退包上再切 Beta:两份清单都比它旧 → 说明在等下一个 Beta(R167)")
pick_channel("beta")
s = wait_for(lambda s: s.has("rollback-to-stable") or s.has("New version"), 30); show("rollback-on-beta")
check("显示回退包说明", s.has("rollback-to-stable"), s.texts())
check("仍算已是最新(没有新版提示)", s.has("up to date") and not s.has("New version"), s.texts())
print("  ", shot_jpg("3c"))
# 还原场景 4 的前提(从稳定切到 Beta):不切回去的话场景 4 的 pick_channel("beta") 是「同通道再选一次」,
# 它的 wait_for 可能直接读到 3c 留在屏幕上的「up to date」,检查不到真正那次重检的结果
pick_channel("stable")
s = wait_for(lambda s: s.has("up to date") and not s.has("rollback-to-stable"), 30); show("back-to-stable")
check("切回稳定:普通的已是最新(稳定通道不报回退说明)", s.has("up to date") and not s.has("rollback-to-stable"), s.texts())
```

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐
emulator -avd unitedu-tv-2 -port 5570 -no-snapshot -no-audio -gpu host &   # 已开着就跳过
DEV=emulator-5570 SHOTS=docs/screenshots/edit-smooth scripts/e2e/channels.sh build
```

Expected:场景 1–5 与新场景 3c 全过;截图 `docs/screenshots/edit-smooth/e2e-3c.jpg` 上关于页第一行是新说明(英文界面)。场景 4(beta.json 404)此时装的仍是回退包,判定同样是 `ROLLBACK_AWAITING_BETA`——它的断言 `s.has("up to date")` 因英文文案里含「up to date」照样成立。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/uniteduone/launcher/{UpdateChannels.kt,Update.kt,AboutScreen.kt} app/src/main/res/values*/strings.xml app/src/test/java/com/uniteduone/launcher/{UpdateChannelsTest.kt,AboutStateTest.kt} scripts/e2e/channels.sh docs/screenshots/edit-smooth/e2e-*.jpg
git commit -m "feat(about): Beta 通道上装着回退包时说明在等下一个 Beta(R167)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: 选频道页——内容与更新时机由发布方决定(文案 b)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`、`values-en/strings.xml`、`values-zh-rTW/strings.xml`(`channel_picker_hint`、`channel_picker_empty_hint`)
- Modify: `README.md`、`README.zh-CN.md`(频道行那一条)
- Test: `app/src/test/java/com/uniteduone/launcher/CopyTest.kt`

**Interfaces:** 无代码接口变化(`ChannelPicker` 照旧读这两个 key)。

依据:研究 `docs/research/2026-10-10-cn-apps-tv-channels.md` §3.2——选频道页说明加「频道内容由应用提供,何时更新也由应用决定」,可补「有的应用只在你打开它、并按返回退出时才更新」,不点名应用;我们自己的文案里别把频道叫「推荐」(小电视那一行其实是观看记录);§3.3——空状态对小电视还要加「按返回退出过」。

- [ ] **Step 1: 写失败的测试**

`CopyTest.kt` 加:

```kotlin
    /** R167(研究 2026-10-10 §3.2):频道相关文案不把频道叫「推荐」;选频道页说明写明内容与更新时机由提供它的应用决定。 */
    @Test fun channelCopySaysTheAppDecidesAndNeverRecommends() {
        val bad = Regex("推荐|推薦|[Rr]ecommend")
        val decides = mapOf("values" to "应用决定", "values-en" to "decided by the app", "values-zh-rTW" to "應用程式決定")
        for (l in langs) {
            val s = strings(l)
            val offenders = s.filter { (k, v) -> (k.startsWith("channel_picker") || k.startsWith("shelf_")) && bad.containsMatchIn(v) }
            assertTrue("$l 频道文案里出现「推荐」:$offenders", offenders.isEmpty())
            assertTrue("$l 的 channel_picker_hint 没写明由应用决定:${s["channel_picker_hint"]}", s["channel_picker_hint"].orEmpty().contains(decides.getValue(l)))
        }
    }
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*CopyTest'`
Expected: FAIL,`channel_picker_hint 没写明由应用决定`。

- [ ] **Step 3: 改文案**

`values/strings.xml`:

```xml
    <string name="channel_picker_hint">频道里放什么、什么时候更新，都由提供它的应用决定；有的应用只在你打开它、按返回退出后才更新。UnitedU 只在本机读取和显示，不上传。</string>
    <string name="channel_picker_empty_hint">打开过的视频应用才可能提供；有的还要在应用里按返回退出一次</string>
```

`values-en/strings.xml`:

```xml
    <string name="channel_picker_hint">What a channel shows, and when it updates, is decided by the app that provides it; some apps only update after you open them and exit with Back. UnitedU only reads and shows them on this TV and never uploads anything.</string>
    <string name="channel_picker_empty_hint">Video apps usually offer one only after you\'ve opened them; some also need you to exit with Back once</string>
```

`values-zh-rTW/strings.xml`:

```xml
    <string name="channel_picker_hint">頻道裡放什麼、何時更新，都由提供它的應用程式決定；有的應用程式只在你開啟它、按返回離開後才更新。UnitedU 只在本機讀取與顯示，不會上傳。</string>
    <string name="channel_picker_empty_hint">開啟過的影音應用程式才可能提供；有的還要在應用程式裡按返回離開一次</string>
```

注意:简中断言找的是「应用决定」——上面的句子是「由提供它的应用决定」,含该子串;英文句子含「decided by the app」;繁中含「應用程式決定」。

README 两份的「频道行」那一条末尾各加一句:

`README.zh-CN.md`(在「只在本机显示,不上传。」之后):

```markdown
频道里放什么、什么时候更新都由提供它的应用决定(有的应用只在你打开它、按返回退出后才更新),UnitedU 不推荐任何内容。
```

`README.md`(在「they are only shown on this TV and never uploaded.」之后):

```markdown
What a channel shows, and when it updates, is up to the app that provides it (some apps only refresh after you open them and exit with Back); UnitedU itself recommends nothing.
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: PASS,BUILD SUCCESSFUL。

- [ ] **Step 5: 模拟器看排版(unitedu-tv-3)**

```bash
S=emulator-5572; P=com.uniteduone.launcher
adb -s $S install -r app/build/outputs/apk/release/app-release.apk
adb -s $S shell pm grant $P android.permission.READ_TV_LISTINGS
# 编辑桌面 → 新的一行 → 频道,停 2 s
adb -s $S exec-out screencap -p > /tmp/edit-jank/t10-zh.png
# settings.json 改 "language": "en" 后冷启动,同样路径再截
adb -s $S exec-out screencap -p > /tmp/edit-jank/t10-en.png
for f in t10-zh t10-en; do sips -s format jpeg /tmp/edit-jank/$f.png --out docs/screenshots/edit-smooth/$f.jpg; done
```

Expected:左栏说明完整显示、不被截断、不压到右边胶囊;中英文各一张。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/values*/strings.xml README.md README.zh-CN.md app/src/test/java/com/uniteduone/launcher/CopyTest.kt docs/screenshots/edit-smooth/t10-*.jpg
git commit -m "copy(channels): 选频道页写明内容与更新时机由应用决定,不叫推荐(R167)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: 复测出对照表 + 文档同步(R167)

**Files:**
- Modify: `docs/design/perf-2026-10-10-edit-page.md`(末尾加「R167 之后复测」)
- Modify: `CLAUDE.md`(焦点表「首页」「编辑页」两行、模拟器验证一条)
- Modify: `docs/superpowers/specs/2026-09-20-gtv-line-design.md`(§12 加 R167)
- Modify: `docs/REVIEW-GUIDE.md`(文件地图、约束、已知问题)
- Modify: `docs/WORKLOG.md`(本轮记录)
- Create: `docs/screenshots/edit-smooth/after-*.jpg`

**Interfaces:** 无。

- [ ] **Step 1: 全量单测 + 构建**

Run: `source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL,全部通过。

- [ ] **Step 2: 复测(同一台 unitedu-tv-2、相近宿主负载)**

```bash
uptime   # 负载与 Task 1 记下的值相差过大(> 5)就等一会儿再跑
S=emulator-5570 bash scripts/perf/edit-jank/setup.sh
S=emulator-5570 bash scripts/perf/edit-jank/suite.sh after
bash scripts/perf/edit-jank/report.sh after
T=/tmp/edit-jank; O=docs/screenshots/edit-smooth
for seg in A E D; do
  python3 scripts/perf/edit-jank/sheet.py $T/${seg}_after.mp4 $T/sheet_${seg}_after
  sips -s format jpeg $T/sheet_${seg}_after.png --out $O/after-${seg}.jpg
done
```

再以观众身份把 `A_after.mp4`、`E_after.mp4`、`D_after.mp4` 的 1× 录像各看一遍,写一句主观结论(连贯 / 哪里还顿)。

- [ ] **Step 3: e2e 整套回归**

```bash
DEV=emulator-5570 python3 scripts/e2e/run_all.py
```

Expected: 全部 PASS(约 25 分钟)。失败的逐条判断是回归还是断言写死了旧行为(如「退出编辑页落 (0,0)」),前者修代码、后者改断言并在 WORKLOG 写明。

- [ ] **Step 4: 报告末尾加对照表**

`docs/design/perf-2026-10-10-edit-page.md` 末尾加一节(数字从 `summary_before.txt` / `summary_after.txt` 抄,不估):

```markdown
## R167 之后复测(<日期>,同一套脚本 `scripts/perf/edit-jank/`,AVD unitedu-tv-2,speed 编译,宿主负载 <值>)

| 交互 | 版本 | 帧数 / 时长 | 录屏 fps | gfxinfo p50 / p90 | 按键帧 UI p50 | RT p50 / p90 | RT 帧间隔 > 20 ms | 纹理上传 | drawLayer / 帧 p50 |
|---|---|---|---|---|---|---|---|---|---|
| A 进编辑页 | 改前 | | | | | | | | |
| A 进编辑页 | R167 | | | | | | | | |
| B 上下换层 | 改前 / R167 | | | | | | | | |
| C 层内左右 | 改前 / R167 | | | | | | | | |
| D 拿起搬运 | 改前 / R167 | | | | | | | | |
| E 退出编辑页 | 改前 | | | | | | | | |
| E 退出编辑页 | R167 | | | | | | | | |

关键帧:改前 `docs/screenshots/edit-smooth/before-{A,E,D}.jpg`,改后 `after-{A,E,D}.jpg`。修复清单落地情况:#1 #2 #4 #5 #8 #9 已做;#7 <做了 / 未做及原因>;#10 <画像已加;.dm 实验结论>;#3 #6 仍待电视测量。
```

(表格每格填实数;「改前 / R167」写成两行或「x → y」均可,与上面汇总表风格一致。)

- [ ] **Step 5: 文档同步**

1. `CLAUDE.md` 焦点表「首页卡片 / 顶栏药丸组」一行里「编辑页仍整体替换首页,回来落 (0,0)」改为:

   「**R167 起编辑页开着时首页也留在组合里**(`previewing = overlayOpen || editing`,编辑页淡入走完后不画),退出编辑页(含编辑页里按 HOME)不重建、按冻结的目标还原(不再落 (0,0));编辑页写盘落地经 `layoutRevision` 让首页在底下重读,`leaveEdit()` 不再 `revision++`」

   「编辑页」一行末尾加:

   「**R167**:进页 layout.json 由 MainActivity 在 IO 线程预读(`enterEdit`,请求号比对)、卡片数据取进程级 `AppEntryCache`(按 revision,与首页同一份),第一帧就是真卡、重定位不必等占位;焦点变化只重组焦点所在那一层(`activeShelf` / 按键提示 / 有无持有者走 `derivedStateOf`,回调整页一份 `EditShelfCallbacks`,胶囊表变化的比对用 `Snapshot.withoutReadObservation` 读目标,不登记依赖);拿起换位的滑动(`ShelfSlide`)只是 `drawWithContent` 平移,不包 `key(pkg)`、`centers` 仍是布局位置,不进任何焦点账本;<若 Task 6 通过>非焦点架子 `ModulateAlpha`,不再是离屏层;外壳在编辑页期间以残影语义留在组合里」

   「模拟器验证的坑」末尾加一条:「**量编辑页转场 / 页内帧用 `scripts/perf/edit-jank/`**(2026-10-10 R167):`setup.sh` 搭环境(19 个占位应用 + 5 行布局 + speed 编译)、`suite.sh <tag>` 跑 H/A/B/C/D/E 固定剧本(trace + mp4 + framestats 同时采)、`report.sh <tag>` 汇总;`pixdiff.py` 做截图逐像素闸门(要带窗口启动模拟器,静止截两张先确认 `max 0`)。重组计数:`setprop log.tag.UnitedUPerf D` 后冷启动,`logcat -s UnitedUPerf`。」

2. `docs/superpowers/specs/2026-09-20-gtv-line-design.md` §12 表格在 R166 之后加一行:

```markdown
| R167 | 2026-10-10(Gordon:「编辑桌面的顺滑度远远不够,非常不顺滑……从布局点击『编辑桌面』胶囊跳转过来的画面就不是足够平滑……一卡一卡的,显得很廉价」;诊断报告 `docs/design/perf-2026-10-10-edit-page.md`) | **编辑桌面顺滑度**:①编辑页开着时首页与设置外壳留在组合里(首页 `previewing`、外壳残影语义),退出不重建、不重读;`leaveEdit()` 不再 `revision++`,编辑页写盘落地经 `layoutRevision` 让首页在底下重读;**行为变化**:从编辑页回来再关设置,首页焦点回到打开设置前那一格(原来落 (0,0))。②编辑页首帧用进程级 `AppEntryCache`(按 revision 失效,与首页同一份)、layout 进页前预读,不再先画文字占位;焦点放大与淡入同时开始。③R147 的「首页残影在底下淡出」取消:首页始终不透明,编辑页淡入即交叉淡化;编辑页盖满后首页不画。④焦点移动只重组焦点所在那一层。⑤<非焦点架子 `ModulateAlpha` / 未做及原因>。⑥拿起换位滑动 200 ms `FastOutSlowIn`(同层换位、跨层搬入从来的方向滑入〔40 dp,离屏层下从下方收短,R129f〕、取消滑回),只是绘制位移。⑦本应用自己的 baseline profile;会话安装带 `.dm` <已接入 / 实验结论>。⑧文案:关于页 Beta 通道装着回退包时说明在等下一个 Beta;选频道页写明内容与更新时机由应用决定、不叫推荐。计划 `docs/superpowers/plans/2026-10-10-edit-page-smoothness.md` | 单测 + e2e 全过;模拟器复测对照表见报告「R167 之后复测」,关键帧 `docs/screenshots/edit-smooth/`;**电视随下一个 Beta 看** |
```

3. `docs/REVIEW-GUIDE.md`:§4 文件地图加 `AppEntryCache.kt`(进程级卡片数据缓存,按 revision 失效)、`EditEntry.kt`(进出编辑页的纯判据)、`EditCarrySlide.kt`(拿起换位的绘制位移)、(闸门通过时)`DexMetadata.kt`(会话安装带的 `.dm`),文件总数随之更新;§5 约束加「编辑页开着时首页与外壳仍在组合里(R167)——首页不可聚焦 / 不画是刻意的,不是泄漏」「拿起换位的卡片不包 `key(pkg)` 是刻意的(焦点节点要留在原位置,见 R167)」;§6 已知问题加「报告 #3 / #6 未做(要电视测量)」,Task 6 未通过时加「#7 未做:ModulateAlpha 观感不一致」,Task 8 闸门未过时加「会话安装仍是 verify,要等夜里后台编译」。
4. `docs/WORKLOG.md` 末尾加「## 2026-10-10 · R167 编辑桌面顺滑度」:做了什么(按任务一行)、Task 4 / 6 / 8 三个闸门的实测数字与取舍、重组探针的改前改后计数、1× 观感结论、未验证项(电视:转场帧率、纹理重传、`.dm`)、需要 Gordon 定的(是否发 Beta 上电视看;`.dm` 若走「发版时随包发」方案要不要做)。

- [ ] **Step 6: Commit**

```bash
git add docs/design/perf-2026-10-10-edit-page.md CLAUDE.md docs/superpowers/specs/2026-09-20-gtv-line-design.md docs/REVIEW-GUIDE.md docs/WORKLOG.md docs/screenshots/edit-smooth/after-*.jpg
git commit -m "docs: R167 编辑桌面顺滑度——复测对照表、裁定、焦点表与评审指南同步

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## 复审记录

- **2026-10-10 · 第 1 轮**(独立对抗复审,对照 EditScreen / EditShelfParts / EditShelves / MainActivity / HomeScreen / SettingsFade / Apps / SelfUpdate / UpdateChannels / AboutScreen 源码与 `scripts/e2e/channels.sh`):Critical 1、Important 3、Minor 8(另 1 条记录在案不改)。
  - **Critical** — Task 8 `.dm`:原 Step 6 写 dm 时任何同步异常都让整个会话作废、`install()` 返回 INVALID(一次失败的更新);原闸门在「E1 成功、E3 坏 dm 让整个安装失败」时仍允许接进代码,A95L 的 ART 认不认 dm 无法预先验证,最坏情况是电视上每个进程的第一次更新都失败。→ `install()` 改为「带 dm 试一次,同步失败当场放弃会话、不带 dm 重开」(文件此刻还在);闸门收紧为 **E1 成功且 E3 坏 dm 不致命** 才接线,E3 致命一律只记录待 Gordon 定;Step 7 的自愈验收改成「一次就装上」+ 同步重试探针;`dexMetadataDisabled` KDoc 同步。
  - **Important** — Task 2 `AppEntryCache` 按 MainActivity 的 `revision` 命中,而 revision 是 Activity 字段、新实例从 0 重数,进程级快照可能跨实例同号命中一份过期数据(两实例之间装 / 卸的应用没人通知 → 僵尸卡 / 缺图)。→ 加 `AppEntryCache.clear()`,`onCreate` 调;加单测 `clearedCacheMissesEvenWithSameRevision`。
  - **Important** — Task 2 预读竞态只覆盖了 HOME(`leaveEdit`):预读那几十毫秒里按返回 / MENU 关掉设置不改请求号,预读回来照样把编辑页弹到首页上(Review Focus 3 同类)。→ `popShell()` / `leaveSettings()` 也 `editEnterSeq++`,Review Focus 3 与字段 KDoc 同步。
  - **Important** — Task 4 Step 5 / Task 6 Step 4 的转场中途截图用 `screencap -p`,这台 AVD 单次约 9.7 s(CLAUDE.md),慢放 10 倍的 4 s 转场早已走完,R129f 截图闸门形同虚设。→ 改成 `screenrecord` + `ffmpeg -ss` 按时间取帧。
  - Minor — Task 1:`PERFETTO_PY` 缺省值是本机家目录的临时 job 路径,会进公开仓库,且该目录随时可能被清。→ 缺省改 `/tmp/edit-jank/tp/py`、提交前 grep `/Users/`、源目录不在就停;补 Pillow 检查。
  - Minor — Task 3 Step 1 测试用了文件里不存在的 `entry()` 帮手、建议改 `EMPTY_EDIT_ROW` 可见性(它本来就是 `GtvTopBar.kt` 的公开常量);且这几条是钉既有行为、改前就过。→ 改用现成的 `appRow()`,Step 2 写明不是红灯、行为变化由模拟器 / e2e 验收。
  - Minor — Task 3 行为裁定漏了「编辑页里按 HOME」也从落 (0,0) 变成按冻结目标还原。→ 裁定与 Task 11 的 CLAUDE.md 焦点表文案补上。
  - Minor — Task 3 拿掉首页 FadeSwitch 后,`SettingsFade.previewMotion`、`pageShiftState`、外壳三处注释描述的仍是旧结构。→ Step 4 列出同步改写。
  - Minor — Task 5 派生值代码块没写放哪:`noHolder` 被看门狗(在纵向位移之前)的 key 读,原地替换会出现先用后声明。→ 指定放在 `report()` 之后。
  - Minor — Task 7 平移放在 `clipPastShelfEnd` 之内:裁切线停在架子右端是对的,但「左缘已过右端就整张不画」的捷径按布局位置判,被挤出架子的卡滑动途中会当场消失。→ `clipPastShelfEnd` 加 `shiftPx` 参数(缺省 0,其它调用点不变),按画出来的位置判。
  - Minor — Task 8 Step 4 `snapshot-profile` 的输出文件名随系统版本不同。→ 先 `ls` 再按两种名字取。
  - Minor — Task 9 新场景 3c 把通道留在 Beta,场景 4 的「从稳定切 Beta」前提没了,它的 `wait_for` 可能直接读到 3c 残留的「up to date」。→ 3c 末尾切回稳定并断言普通的已是最新。
  - 记录不改 — Task 7 `carrySlideStarts` 不只认搬运:「添加应用」加一张、「移出」、后台卸载少一张也会滑;两层上下对调且两层有同一个包时,同一格可能被判成「少了一张」而滑一下。都是 200 ms 的无害位移、不碰焦点,执行时留意即可。
  - 结论:主体结构(焦点账本不动、previewing / 残影让路、NonCancellable 写盘、layoutRevision 与 revision 分离、不改任何落盘格式)与源码对得上,代码片段的名字 / 签名逐一核过(`resolveChannel` 末位 lambda、`AboutState` 分支、`ShelfLayout.LAYER_PAD`、`AppShelfView` 参数、`EMPTY_EDIT_ROW`、`versionCodeOverride` 等)。本轮改动集中在 Task 8 Step 6 的代码与 Task 7 的裁切,建议下一轮只抽查这两处。
- **2026-10-10 · 第 2 轮**(独立对抗复审,重点:Task 8 会话安装、Task 7 裁切 / 滑动几何、Task 3 常驻首页与外壳的各条退出路径;对照 SelfUpdate / UploadServer / EditScreen / EditShelves / EditShelfParts / HomeScreen / HomeChannels / MainActivity / SettingsFade / SettingsShell / Apps / UpdateChannels / AboutScreen / CopyTest / channels.sh 源码):Critical 0、Important 3、Minor 6。
  - **Important** — Task 7 跨层滑入的 R129f 论证只算了卡身:撑大后的图层只比架子多 `LAYER_PAD`(= 柔光 60 dp),卡片下方离架子底只有 `CARDS_BOTTOM` 15.4 dp,从下方滑入 40 dp 时焦点卡的放大 + 柔光下缘越出图层约 28 dp,Task 6 不通过(仍是离屏层)时会被一条直线截掉——正是 R129f 那次电视上看到的形态。→ 新增纯函数 `carryArriveDp(sign, modulate = DIM_BY_MODULATE)`(不开离屏层时两向 40 dp;离屏层下按「卡 + 放大 + 柔光不出图层」收短,从下方约 12 dp),单测 `arrivalGlowStaysInsideLayer` / `modulateUsesFullArrivalDistance` 取代只比 `CARRY_ARRIVE_DP < LAYER_PAD` 的旧断言;Task 6 的 Interfaces、设计稿 §2.5 那一行、R167 裁定文案、Step 8 的截图核对(加上搬一层的中途帧)同步。
  - **Important** — Task 8 Step 8 跑 `channels.sh build` 时,`build_one` 的版本名替换还只认 `1.0.3`(Task 9 才修),本分支 versionName 已是 `1.2.0-beta.1`,A / C 会被构建成 Beta 名,双通道 5 个场景的结论作废。→ 修正挪到 Task 8 Step 8 先做并随 Task 8 提交(`git add` 加 `scripts/e2e/channels.sh`),Task 9 Step 6 注明已改则跳过。
  - **Important** — Task 3 让首页留在编辑页底下后,每次写盘落地(`layoutRevision++`)首页都在主线程整页重组一次(报告:首页组合 11–20 ms),落点恰在放下的 150 ms 抬起动画里;suite 的 D 段以返回取消收尾、不写盘,量不到这一帧。另有装 / 卸 / 换卡片图让 revision++ 时首页与编辑页各自没命中、各解码一遍全部横幅,编辑页加应用时两边各做一次全量 `Apps.load`。→ Task 3 Step 6 加「拿起 → 右移 → 放下」的 W 段闸门,不过就把首页的 `layoutRevision` 去抖(600 ms,`leaveEdit` 当场追上)并记 WORKLOG;Task 2 的 `loadAppEntries` 整段串行(后到的直接命中)且同一 revision 只补查缺的包(`mergeAppEntries`:底子用 `Apps.load` 的桌面应用全集,已解码条目盖回去,不能反过来),加单测 `mergeKeepsDecodedEntriesAndAddsMissing`。
  - Minor — Task 2 `AppEntryCache.clear()` 挡不住上一个实例在 IO 线程上没读完、`clear()` 之后才回来发布的那一份(同号命中)。→ 加 `epoch`:读取开始时记下,发布时对不上就丢;单测 `publishFromPreviousInstanceIsDropped`。
  - Minor — Task 2 预读 layout.json 不经 `layoutWrites`:刚退出的上一轮编辑页还有在途写盘(NonCancellable)时,快速重进会拿旧文件当种子,下一次写盘按旧种子合并。→ 预读改在 `layoutWrites` 上做。
  - Minor — Task 2 预读期间若打开了别的整屏页(选图页等),回来照样 `editing = true`,编辑页会把选图页吞进自己那一层。→ `shouldEnterEdit` 加 `blocked`(选图页 / 关于页 / 应用页 / 输入源页 / 引导),单测 `otherOverlayDoesNotEnter`。
  - Minor — Task 8 `lastSessionHadDm` 在会话里写 base.apk 之后才清,之前任一步抛出会留着上一次的值。→ 移到 `commitSession` 开头。(记录不改:写 base.apk 本身失败也会把 `dexMetadataDisabled` 置真,本进程之后只是少了 dm,无害。)
  - Minor — Task 3 `appliedLanding` 的 KDoc(「只在冷启动 / 进出编辑页时重建」)随首页常驻过期;e2e 回归漏了直接相关的 `j_persist`(写盘)与 `j_recreate`(重建)。→ 补上。
  - 核过无误(不改):Task 8 `install()` 的重试只在同步失败时触发、失败的会话一律 `abandonSession`、`UploadServer` 在 `install` 返回后才释放文件、异步失败经 `SelfUpdateResult` 置位后下一次不带 dm——任何一次失败最多让本进程的一次更新失败一回,不会让更新永久坏掉;`writeDexMetadata` 显式返回类型下表达式体里的 `return false` 可编译。Task 7 平移在裁切之内、`shiftPx` 的整张跳过判据、`centers` 与焦点搜索只看布局位置。Task 3 外壳残影复活:`CapsuleColumn` 把 ghost 并进 `covered`(key 与守卫同量),退出编辑页时定位效果随 covered 翻转重跑、落回「编辑桌面」;R161 空桌面目标 `EMPTY_EDIT_ROW` 回来时由 `tgtLayoutRow < 0 → rows.first()` 落第一张;HOME(onNewIntent:leaveEdit → leaveSettings → focusNonce++)、编辑页里 MENU(原样交给编辑页)、编辑页开着时卸载 / 频道变化(首页 covered 期间 restoring 按住、关掉后重跑)、Activity 重建(editing 不进 Bundle)各条路径成立;Task 4 新效果守卫即 key、`editSettled` 每次进效果先清,不是闩;编辑页底下的 `pageBackdrop` 不在 `OverlayStack` 里,确实不透明。
  - 结论:本轮改动都在计划正文里落了代码 / 步骤,未动焦点账本。剩下的风险都已有实测闸门兜住(Task 3 的 W 段、Task 4 / 6 / 8 的原有闸门),**不需要第 3 轮**;执行时 Task 7 先看 Task 6 的结果再定滑入距离是否收短。
- **2026-10-10 · 第 3 轮**(独立对抗复审,重点:第 1–2 轮新增 / 改动的部分——Task 2 的 `AppEntryCache` epoch / `mergeAppEntries` / 串行 `loadAppEntries` / `layoutWrites` 上预读 / `shouldEnterEdit(blocked)`,Task 3 的 W 段闸门与去抖,Task 7 `carryArriveDp`,Task 8 `lastSessionHadDm` 与 `build_one` 前移;对照 Layout.kt(`layoutWrites` = `Dispatchers.IO.limitedParallelism(1)`)、Apps.load 签名、Model.kt(`AppEntry` 是 data class)、EditScreen 现有 `loadedFor` / `persist`、MainActivity 字段与 `onNewIntent` / `popShell` / `leaveSettings` / 外壳块、SelfUpdate、HomeChannels / HomeChannelsTest、EditShelves / GtvLayout 常量):Critical 0、Important 2、Minor 1。
  - **Important** — Task 3 W 段不通过时的去抖写成 `LaunchedEffect(layoutRevision) { delay(600) … }` + `leaveEdit` 当场追上:追上的只是退出那一刻已落地的写盘;「放下后 0.1 s 内按返回」那次写盘(NonCancellable)在 `leaveEdit` **之后**才落地,照样被延后 600 ms,首页在编辑页残影淡完后还画旧顺序再跳——正是 Review Focus 5 要防的形态,原文却把它记成「降级」。→ 效果 key 加 `editing`、只在编辑页开着时 `delay`(无 return 守卫,读到的量即 key,铁律 6;不是闩),`leaveEdit` 的当场赋值保留;Review Focus 5 不再降级。
  - **Important** — Task 4 `editSettled` 用帧时钟的墙钟时间判「淡入已走完」,而 FadeSwitch 的淡入吃系统动画时长缩放(应用设置里就有「动画缩放」这一行):缩放 > 1 的用户淡入才走一半首页就停画,半透明的编辑页底下卡片整排消失;Task 4 Step 5 自己在 `animator_duration_scale 10` 下取的 R129f 中途帧也会因此失真。→ 按效果协程上下文里的 `MotionDurationScale` 把墙钟折算成动画时间(缩放 0 当场算盖满),`editCoversHome` 签名与单测不变;Step 5 那一帧同时核对底下仍是首页。
  - Minor — Task 3 W 段闸门的第二条「perfetto 里含 `HomeScreen` 重组的帧」在 release 构建里认不出(没开 composition tracing,报告「测量方法」末条)。→ 改为按时长判:放下后 300 ms 内没有 > 16 ms 的 `Recomposer:recompose`。
  - 核过无误(不改):`loadAppEntries` 在 `synchronized` 内联 lambda 里 `return@synchronized` 可编译,epoch 在锁内、命中检查之后取,上一个实例的那一趟按旧 epoch 发布被丢;`mergeAppEntries` 以 fresh(桌面应用全集 + 按 `missing` 兜底查到的)为底、盖回 base 查过的条目,经 fallback 找到的旧条目也保留,单测 `mergeKeepsDecodedEntriesAndAddsMissing` 的期望与实现一致且改前编译失败;`AppEntry` 是 data class,单测里的 `assertEquals` 有意义;编辑页 `produceState(initialValue = cachedAtEntry, …)` 首轮命中时写回的 Pair 结构相等、不触发重组;`persist()` 的 `layoutWritten()` 在 NonCancellable 块内、回到 `rememberCoroutineScope` 的主线程上 `layoutRevision++`;`enterEdit` 的 `withContext(layoutWrites)` 与 `blocked` 用到的五个字段(`pickerTarget` / `about` / `appsPage` / `inputsPage` / `onboarding`)都在,请求号是比对不是闩;`popShell` 经 `leaveSettings` 时多加一次号无害;Task 3 外壳 `shellKept` 下 `shellLast` 只在 `shellShown` 时更新、编辑页期间冻结在进页那一份,退出时同一份组合变回活的;`carryArriveDp` 按真实常量(CARDS_TOP 56、CARDS_BOTTOM 15.375、APP_SHELF_HEIGHT 140、LAYER_PAD = APP_FOCUS_GLOW_DP 60、APP_FOCUS_SCALE 1.10)算得从上方 40、从下方 ≈ 11.94 dp,两条单测成立;Task 8 `commitSession` 开头清 `lastSessionHadDm`、带 dm 那次同步失败后重试前再清,异步失败只在真带了 dm 时置 `dexMetadataDisabled`;`build_one` 的修正在 Task 8 Step 8 与 Task 9 Step 6 两处说法一致。全计划扫过占位符与接口名(`enterEdit` / `editSeedRows` / `editEnterSeq` / `layoutRevision` / `editCoversHome` / `EDIT_COVER_SLACK_MS` / `DIM_BY_MODULATE` / `CARRY_ARRIVE_DP`),前后一致;`<日期>` 等只出现在 Task 11 的复测模板里,属有意留空。
  - 结论:本轮两处 Important 都是只在特定路径上才露出的时序问题(退出后才落地的写盘、非 1 的动画缩放),已在正文落了代码与核对步骤,未动焦点账本。**不需要第 4 轮**。
- **2026-10-10 · 第 4 轮**(窄复审,只看第 3 轮改动;对照 compose-bom 2024.10.01 解出的 `ui-android` 1.7.5 字节码、诊断 trace `D_s.pftrace`、`fsum.py`、报告「测量方法」):Critical 0、Important 0、Minor 1。
  - Minor — Task 3 W 段闸门把「放下后 300 ms 内 > 16 ms 的 `Recomposer:recompose`」直接认作首页重组,理由写的是「编辑页自己只有抬起动画的绘制」——不对,放下时编辑页的 rows / 目标都变,自己也重组一次。误判的后果只是多上一个(正确的)去抖。→ 改写理由:不通过先上去抖再复测,仍不通过即编辑页自己慢,记 WORKLOG 交 Task 5,不在首页这边加码。
  - 核过无误(不改):(a) `LaunchedEffect(layoutRevision, editing)` 去抖——退出后才落地的写盘:editing 已假,效果随 layoutRevision 变化重跑、不 delay 当场赋值;600 ms 内重进编辑页:`leaveEdit` 已当场追上,重进那次效果 delay 后写回同值,无害;编辑页里连续多次放下只是重启 delay(去抖本意);HOME 经 `onNewIntent → leaveEdit()` 当场追上;重建时两个字段都从 0 起、HomeScreen 首次 produceState 本来就整份读;首页重读路径是 Task 3 Step 3 的 `produceState(…, revision, layoutRevision)` + `stale = loadedKey != (revision to layoutRevision)`,喂进去的换成 `homeLayoutRevision` 两处读的是同一个参数,一致;去抖期间若 `revision++` 让首页提前读到新文件,随后再读一次,无害。(b) `androidx.compose.ui.MotionDurationScale` 存在(含 `Key`);1.7.5 的 `createLifecycleAwareWindowRecomposer` 把 `MotionDurationScaleImpl`(初值 1f,按 `ANIMATOR_DURATION_SCALE` 的 ContentObserver 更新)拼进窗口 Recomposer 的上下文,效果协程继承它,`coroutineContext[MotionDurationScale]?.scaleFactor` 读得到;缩放 0 时 Compose 动画一帧走完、这里 `animMs = MAX` 第一帧即算盖满,一致;`editCoversHome` 签名不变、只在 MainActivity 里用到 MotionDurationScale,单测编译不受影响。(c) 诊断 trace(release 包)里 `Recomposer:recompose` 切片实有(D_s 一段 341 条),`fsum.py` 也按这个名字取,闸门可执行。
  - 结论:**不需要第 5 轮**。
