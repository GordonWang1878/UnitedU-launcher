# UnitedU M1(工程起点)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Core 上把 UnitedU 从「零」推进到「一个能 `assembleRelease`、用自家 release 密钥签名、新包名、Apache-2.0 发布件齐全、文案三语抽出」的可构建仓库,且不改变任何 TvHome 行为之外的功能。

**Architecture:** 逐文件复制 TvHome(`Sony TV/launcher`)的 Kotlin/Compose 源码与可再分发资源到新仓库,改包名、加 release 签名、去 adb 依赖、抽文案;不做任何界面或焦点行为改动(那是 M2+)。每个任务的验收都是「`gradle assembleRelease` 通过 + 指定检查命令输出」。

**Tech Stack:** Kotlin 2.0.21 / Jetpack Compose / AGP 8.7.x / Gradle 8.14.5 / JDK 17 / compileSdk 35 / minSdk 按 TvHome 原值。

**Spec:** `docs/DESIGN-unitedu-open-source.md` §8、§11(M1 行);焦点铁律 `docs/TVHOME-README-focus-rules.md`。

## Global Constraints

- 工具链**不进 PATH**,每个 shell 先 `source scripts/env.sh`(Task 1 建)。
- `settings.gradle.kts` 只用 `https://dl-ssl.google.com/dl/android/maven2/` 替代 `google()`;`gradle.properties` 必含 `android.builder.sdkDownload=false`;`buildToolsVersion = "35.0.0"`。
- 包名:`com.uniteduone.launcher`(2026-09-14 Gordon 定,基于自有域名 uniteduone.com;发布后不可改)。
- 许可证 Apache-2.0;**禁止复制**:`assets/wallpaper-gold-fog.jpg`、`app/src/main/assets/default-wallpaper.jpg`(Projectivy 派生)、以及 `library/` 下任何剧照/同人图。
- 密钥密码只在 `~/.unitedu/release.properties`(仓库外);仓库里只有读取路径的代码。
- 任何界面/焦点代码**本里程碑不改**;`RelaunchAfterUpdate` 的 appop 判断是唯一行为改动。
- 每个任务结束 `gradle --no-daemon assembleRelease` 必须通过后再 commit。
- 提交信息末尾加 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`。

---

## 前置状态(2026-09-14 已完成,不再重复)

| 组件 | 位置 | 验证 |
|---|---|---|
| JDK 17.0.20.1+1 (Temurin, aarch64) | `~/Library/Java/jdk-17/Contents/Home` | `java -version` → 17.0.20.1 |
| Gradle 8.14.5 | `~/Library/Gradle/gradle-8.14.5` | `gradle --version` → 8.14.5 / Kotlin 2.0.21 |
| Android SDK: platform-tools r37.0.1、platforms/android-35、build-tools/35.0.0、licenses | `~/Library/Android/sdk` | `aapt2 version` → 2.19-11948202;`adb version` → 1.0.41 |

全部按 Hub 的版本与路径原样装,SHA 双验通过。Core 上 `dl.google.com` 与 `dl-ssl.google.com` 都通,配置仍统一用 `dl-ssl`(与 Hub 一致,少一个变量)。

**已知阻塞(需 Gordon)**:① TvHome 源码在 Hub 的 Desktop/Documents/Downloads 之一,sshd 受 TCC 限制读不到,Task 2 需他先复制到 `~/Public/unitedu-handoff/launcher`;② 电视 `192.168.1.50` 可 ping 但 5555 关闭(重启后 adb tcpip 失效),M1 不需要真机,M8 前再开。

---

### Task 1: 仓库骨架 + 环境脚本 + CLAUDE.md

**Files:**
- Create: `.gitignore`、`scripts/env.sh`、`CLAUDE.md`、`README.md`(占位一段,M7 再写用户版)

**Interfaces:**
- Produces: `scripts/env.sh`(导出 `JAVA_HOME`/`ANDROID_HOME`/`PATH`),后续每个任务的每条 gradle 命令都以 `source scripts/env.sh &&` 开头。

- [ ] **Step 1: git init 与 .gitignore**

```bash
cd ~/GitHub/UnitedU-launcher && git init -b main
cat > .gitignore <<'EOF'
build/
.gradle/
local.properties
*.iml
.idea/
.DS_Store
*.keystore
*.jks
release.properties
app/release/
EOF
```

- [ ] **Step 2: 写 scripts/env.sh**

```bash
mkdir -p scripts && cat > scripts/env.sh <<'EOF'
# source 这个文件后再跑 gradle / adb;工具链刻意不进全局 PATH
export JAVA_HOME="$HOME/Library/Java/jdk-17/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$HOME/Library/Gradle/gradle-8.14.5/bin:$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
EOF
source scripts/env.sh && java -version 2>&1 | head -1 && gradle --version | grep '^Gradle'
```
Expected: `openjdk version "17.0.20.1"` 与 `Gradle 8.14.5`。

- [ ] **Step 3: 写 CLAUDE.md**:把 `docs/TVHOME-README-focus-rules.md` 中「改这份界面前必须知道的七条」整节**逐字**复制,前面加三段:项目一句话(零广告、零推荐)、构建命令(`source scripts/env.sh && gradle --no-daemon assembleRelease`)、文档分流(`docs/DESIGN-*.md` 是设计定稿;`docs/superpowers/plans/` 是实施计划;每轮结束更新 `docs/WORKLOG.md`)。

```bash
sed -n '/^## 改这份界面前必须知道的七条/,/^## 工具链是怎么装的/p' docs/TVHOME-README-focus-rules.md | sed '$d' > /tmp/rules.md
{ printf '# UnitedU\n\n零广告、零推荐,只有你放上去的应用。国行无 GMS Android TV 的开源桌面。设计定稿:`docs/DESIGN-unitedu-open-source.md`。\n\n## 构建\n\n```bash\nsource scripts/env.sh && gradle --no-daemon assembleRelease\n```\n\n工具链在 `~/Library/{Java,Gradle,Android}`,不进 PATH。\n\n## 文档分流\n\n- `docs/DESIGN-*.md`:设计定稿,改设计先改它\n- `docs/superpowers/plans/`:实施计划\n- `docs/WORKLOG.md`:每轮工作记录(排查、结论、未验证项)\n\n'; cat /tmp/rules.md; } > CLAUDE.md
grep -c '^[0-9]\. \*\*' CLAUDE.md
```
Expected: `7`。

- [ ] **Step 4: 首次提交**

```bash
printf '# UnitedU\n\n零广告、零推荐,只有你放上去的应用。面向国行无 GMS Android TV 的开源桌面。**开发中,尚无可用版本。**\n' > README.md
git add -A && git commit -m "chore: repo skeleton, env script, CLAUDE.md with focus rules

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 5: 建 GitHub 公开远程(对外生效,执行前用选择卡向 Gordon 确认一次)**

```bash
gh repo create GordonWang1878/UnitedU-launcher --public --source=. --remote=origin --push --description "Ad-free, recommendation-free launcher for Android TV without GMS"
git remote -v
```
Expected: origin 指向 `github.com/GordonWang1878/UnitedU-launcher`。

---

### Task 2: 拉取 TvHome 起点代码到暂存区(不进仓库)

**Files:**
- Create(仓库外):`~/GitHub/_staging/tvhome/`(Hub 复制来的原样副本,只读参考)

**Interfaces:**
- Produces: 暂存路径 `~/GitHub/_staging/tvhome/`,Task 3–8 从这里复制。

- [ ] **Step 1: 等 Gordon 在 Hub 上完成复制**(见文末「需要你做的事」第 1 条),然后从 Core 拉:

```bash
mkdir -p ~/GitHub/_staging && rsync -a --exclude build --exclude .gradle --exclude '*.apk' hub:~/Public/unitedu-handoff/launcher/ ~/GitHub/_staging/tvhome/
find ~/GitHub/_staging/tvhome -type f | grep -v '/build/' | sort > ~/GitHub/_staging/tvhome-files.txt
wc -l ~/GitHub/_staging/tvhome-files.txt; cat ~/GitHub/_staging/tvhome/gradle/libs.versions.toml 2>/dev/null; sed -n 1,60p ~/GitHub/_staging/tvhome/app/build.gradle.kts
```
Expected: 文件清单非空;能看到 AGP / Compose BOM / minSdk 的确切值。**把这三个值记进 `docs/WORKLOG.md`**,Task 3 的 build 文件以它们为准。

- [ ] **Step 2: 列出禁复制项并确认它们的存在**

```bash
cd ~/GitHub/_staging/tvhome && ls -la assets/ app/src/main/assets/ 2>/dev/null; grep -rn 'gordonwang' --include='*.kt' --include='*.kts' --include='*.xml' . | grep -v /build/ | wc -l
```
Expected: 看到 `wallpaper-gold-fog.jpg` / `default-wallpaper.jpg`(这两个**不复制**);`gordonwang` 出现次数 = Task 3 要改的包名引用数。

---

### Task 3: 复制源码 + 改包名 + 构建配置

**Files:**
- Create: `settings.gradle.kts`、`gradle.properties`、`build.gradle.kts`、`gradle/libs.versions.toml`(若 TvHome 有)、`app/build.gradle.kts`、`app/src/main/**`(除禁复制项)、`app/proguard-rules.pro`
- Test: 构建本身

**Interfaces:**
- Produces: 包 `com.uniteduone.launcher`,目录 `app/src/main/java/com/uniteduone/launcher/`;`namespace` 与 `applicationId` 同值。

- [ ] **Step 1: 复制构建文件与源码**

```bash
cd ~/GitHub/UnitedU-launcher && T=~/GitHub/_staging/tvhome
cp $T/settings.gradle.kts $T/gradle.properties $T/build.gradle.kts . 2>/dev/null; [ -d $T/gradle ] && cp -R $T/gradle .
mkdir -p app && cp $T/app/build.gradle.kts app/ && cp $T/app/proguard-rules.pro app/ 2>/dev/null
rsync -a --exclude 'assets/default-wallpaper.jpg' $T/app/src/ app/src/
find app/src -type f | wc -l
```

- [ ] **Step 2: 改包名(目录 + 所有引用)**

```bash
cd ~/GitHub/UnitedU-launcher
OLD=$(find app/src/main/java -type d -name tvhome | head -1); echo "old dir: $OLD"
mkdir -p app/src/main/java/com/uniteduone/launcher && git mv -k "$OLD"/* app/src/main/java/com/uniteduone/launcher/ 2>/dev/null || mv "$OLD"/* app/src/main/java/com/uniteduone/launcher/
rm -rf app/src/main/java/com/gordonwang
grep -rl 'com\.gordonwang\.tvhome' app settings.gradle.kts build.gradle.kts 2>/dev/null | xargs sed -i '' 's/com\.gordonwang\.tvhome/com.uniteduone.launcher/g'
grep -rn 'gordonwang\|TvHome' app --include='*.kt' --include='*.kts' --include='*.xml' | grep -v 'logcat\|Log\.' | head
```
Expected: 最后一条 grep 只剩日志 tag 或注释里的 `TvHome`(下一步处理),没有 `gordonwang`。

- [ ] **Step 3: 改应用名与日志 tag**

```bash
cd ~/GitHub/UnitedU-launcher
grep -rn '"TvHome"' app/src/main/java | cut -d: -f1 | sort -u | xargs sed -i '' 's/"TvHome"/"UnitedU"/g'
grep -rn 'TvHome' app/src/main/res/values/strings.xml app/src/main/AndroidManifest.xml 2>/dev/null
```
把 `android:label` 与 `strings.xml` 里的 `TvHome` 改成 `UnitedU`(手工 `sed`,一处一处看,不要全局替换类名)。

- [ ] **Step 4: 构建配置三条硬约束**

```bash
cd ~/GitHub/UnitedU-launcher
grep -n 'dl-ssl.google.com' settings.gradle.kts && grep -n 'sdkDownload=false' gradle.properties && grep -n 'buildToolsVersion = "35.0.0"' app/build.gradle.kts
```
Expected: 三行都命中。缺哪条补哪条(TvHome 原本三条都有,复制过来应该齐)。

- [ ] **Step 5: 补一张自制默认壁纸**(替代 Projectivy 派生图;README 里的噪声配方,`/dev/urandom` 喂真二维噪声)

```bash
cd ~/GitHub/UnitedU-launcher && mkdir -p app/src/main/assets
head -c $((6*4*3)) /dev/urandom > /tmp/noise.rgb
ffmpeg -y -f rawvideo -pix_fmt rgb24 -s 6x4 -i /tmp/noise.rgb -frames:v 1 -vf "scale=1920:1080:flags=bicubic,gblur=sigma=78,normalize,curves=all='0/0 0.55/0 0.80/0.22 1/0.62',colorchannelmixer=rr=1.0:gg=0.78:bb=0.45" -q:v 3 app/src/main/assets/default-wallpaper.jpg
ls -l app/src/main/assets/default-wallpaper.jpg && sips -g pixelWidth -g pixelHeight app/src/main/assets/default-wallpaper.jpg
```
Expected: 1920×1080 JPEG,几十到几百 KB。`ffmpeg` 不在 Core 时先 `brew install ffmpeg`。

- [ ] **Step 6: 构建验证**

```bash
cd ~/GitHub/UnitedU-launcher && source scripts/env.sh && gradle --no-daemon assembleRelease 2>&1 | tail -3
ls -l app/build/outputs/apk/release/*.apk
```
Expected: `BUILD SUCCESSFUL`;APK 约 2 MB(与 TvHome release 1.85 MB 同量级)。

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: import TvHome as starting point under com.uniteduone.launcher

Copied file-by-file from Smart Home/Sony TV/launcher (no history).
Excludes Projectivy-derived default wallpaper; replaced with generated noise wallpaper.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: release 签名密钥

**Files:**
- Modify: `app/build.gradle.kts`(`signingConfigs` 段)
- Create(仓库外):`~/.unitedu/release.jks`、`~/.unitedu/release.properties`(Gordon 写密码)

**Interfaces:**
- Consumes: `~/.unitedu/release.properties`,格式三行 `storePassword=`/`keyPassword=`/`keyAlias=unitedu`。
- Produces: `signingConfigs["release"]`;`buildTypes.release.signingConfig = signingConfigs["release"]`;文件不存在时**回落到 debug keystore 并打印 warning**(社区贡献者不需要密钥也能 build)。

- [ ] **Step 1: 等 Gordon 写好密码文件**(文末「需要你做的事」第 2 条),然后生成密钥(密码从文件读,不出现在命令历史):

```bash
source ~/GitHub/UnitedU-launcher/scripts/env.sh
set -a; source ~/.unitedu/release.properties; set +a
keytool -genkeypair -v -keystore ~/.unitedu/release.jks -alias "${keyAlias:-unitedu}" -keyalg RSA -keysize 4096 -validity 10950 -storepass "$storePassword" -keypass "$keyPassword" -dname "CN=UnitedU, O=UnitedU, C=CN"
keytool -list -keystore ~/.unitedu/release.jks -storepass "$storePassword" | grep -i unitedu
```
Expected: 一行 `unitedu, ..., PrivateKeyEntry`。

- [ ] **Step 2: 把 TvHome 的 `signingConfigs["sideload"]`(debug keystore)改成读文件的 release 配置**。在 `app/build.gradle.kts` 的 `android {}` 内替换 `signingConfigs` 段为:

```kotlin
val releaseProps = java.util.Properties().apply {
    val f = file(System.getProperty("user.home") + "/.unitedu/release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
signingConfigs {
    create("release") {
        val ks = file(System.getProperty("user.home") + "/.unitedu/release.jks")
        if (ks.exists() && releaseProps.containsKey("storePassword")) {
            storeFile = ks
            storePassword = releaseProps.getProperty("storePassword")
            keyAlias = releaseProps.getProperty("keyAlias", "unitedu")
            keyPassword = releaseProps.getProperty("keyPassword")
        } else {
            logger.warn("UnitedU: ~/.unitedu/release.jks not found, signing release with debug keystore")
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
}
```
并把 `buildTypes { release { signingConfig = signingConfigs.getByName("sideload") } }` 中的 `"sideload"` 改为 `"release"`。

- [ ] **Step 3: 验证签名者**

```bash
cd ~/GitHub/UnitedU-launcher && source scripts/env.sh && gradle --no-daemon assembleRelease 2>&1 | tail -2
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep 'Signer #1 certificate DN'
```
Expected: `CN=UnitedU, O=UnitedU, C=CN`(不是 `CN=Android Debug`)。

- [ ] **Step 4: 回落路径验证**:临时改名密码文件再 build,应见 warning 且签名者为 `CN=Android Debug`;改回。

```bash
mv ~/.unitedu/release.properties ~/.unitedu/release.properties.off && gradle --no-daemon assembleRelease 2>&1 | grep -c 'signing release with debug keystore'; mv ~/.unitedu/release.properties.off ~/.unitedu/release.properties
```
Expected: `1`。

- [ ] **Step 5: 备份密钥文件到 iCloud Drive**(Gordon 定的保管方式:密码在 Apple「密码」App,`.jks` 存 iCloud Drive;`.jks` 自带密码加密,放云盘安全)。**永不进 Git**(`.gitignore` 已挡 `*.jks`)。

```bash
mkdir -p ~/Library/Mobile\ Documents/com~apple~CloudDocs/UnitedU-keystore
cp ~/.unitedu/release.jks ~/Library/Mobile\ Documents/com~apple~CloudDocs/UnitedU-keystore/release.jks
shasum -a256 ~/.unitedu/release.jks ~/Library/Mobile\ Documents/com~apple~CloudDocs/UnitedU-keystore/release.jks | awk '{print $1}' | uniq | wc -l
```
Expected: `1`(两份 SHA-256 一致,备份完整)。iCloud 会自动上传;丢机时从任一 Apple 设备取回,配 Apple「密码」App 里那条密码即可重新签名。

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts && git commit -m "build: release signing from ~/.unitedu, debug-keystore fallback

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: 去 adb 依赖 —— `RelaunchAfterUpdate` 无 appop 时静默跳过

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/RelaunchPolicy.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/RelaunchAfterUpdate.kt`(接收器,复制来的)
- Test: `app/src/test/java/com/uniteduone/launcher/RelaunchPolicyTest.kt`
- Modify: `app/build.gradle.kts`(加 `testImplementation("junit:junit:4.13.2")`,若 TvHome 没有)

**Interfaces:**
- Produces: `fun shouldRelaunchHome(isDefaultHome: Boolean, canDrawOverlays: Boolean): Boolean`(纯函数);接收器用 `Settings.canDrawOverlays(context)` 取第二个参数。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.uniteduone.launcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class RelaunchPolicyTest {
    @Test fun relaunchesOnlyWhenDefaultHomeAndOverlayGranted() {
        assertTrue(shouldRelaunchHome(isDefaultHome = true, canDrawOverlays = true))
        assertFalse(shouldRelaunchHome(isDefaultHome = true, canDrawOverlays = false))
        assertFalse(shouldRelaunchHome(isDefaultHome = false, canDrawOverlays = true))
        assertFalse(shouldRelaunchHome(isDefaultHome = false, canDrawOverlays = false))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd ~/GitHub/UnitedU-launcher && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest 2>&1 | grep -E 'Unresolved reference|FAILED|BUILD' | head -3
```
Expected: `Unresolved reference: shouldRelaunchHome` 或 `BUILD FAILED`。

- [ ] **Step 3: 实现**

```kotlin
package com.uniteduone.launcher
/** 更新后要不要把自己拉回桌面任务:必须仍是默认桌面,且有悬浮窗 appop(否则 BAL_BLOCK,见 CLAUDE.md)。 */
fun shouldRelaunchHome(isDefaultHome: Boolean, canDrawOverlays: Boolean): Boolean =
    isDefaultHome && canDrawOverlays
```
在 `RelaunchAfterUpdate.onReceive` 里,原来 `if (isDefaultHome) { startActivity(homeIntent) }` 的判断改为:
```kotlin
val canOverlay = android.provider.Settings.canDrawOverlays(context)
if (!shouldRelaunchHome(isDefaultHome, canOverlay)) {
    android.util.Log.i("UnitedU", "skip relaunch: defaultHome=$isDefaultHome overlay=$canOverlay")
    return
}
```
(`isDefaultHome` 沿用文件里已有的解析逻辑,不改。)

- [ ] **Step 4: 测试通过 + 构建通过**

```bash
gradle --no-daemon testReleaseUnitTest assembleRelease 2>&1 | grep -E 'BUILD|tests completed'
```
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: skip post-update relaunch when SYSTEM_ALERT_WINDOW not granted

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: LICENSE + NOTICE + 资源合规

**Files:**
- Create: `LICENSE`、`NOTICE`
- Modify: `README.md`(加许可证一行)

- [ ] **Step 1: LICENSE**

```bash
cd ~/GitHub/UnitedU-launcher && curl -sL https://www.apache.org/licenses/LICENSE-2.0.txt -o LICENSE && head -2 LICENSE && wc -l LICENSE
```
Expected: 第一行 `Apache License`,约 202 行。

- [ ] **Step 2: NOTICE(本里程碑只写已经打包的三方件;NanoHTTPD 到 M6、内置壁纸到 M3 再补)**

```bash
cat > NOTICE <<'EOF'
UnitedU
Copyright 2026 UnitedU contributors

Licensed under the Apache License, Version 2.0. See LICENSE.

This product bundles the following third-party materials:

- DM Sans (app/src/main/res/font/): Copyright 2014-2019 Indian Type Foundry / Google.
  Licensed under the SIL Open Font License 1.1, https://openfontlicense.org
- Material Icons (androidx.compose.material:material-icons-*): Copyright Google LLC.
  Licensed under the Apache License 2.0.
- Default wallpaper (app/src/main/assets/default-wallpaper.jpg): generated procedurally
  by the UnitedU project from random noise; no third-party imagery.
EOF
ls app/src/main/res/font/ 2>/dev/null
```
Expected: 字体目录里确有 DM Sans 文件;若 TvHome 还引了别的字体或库,逐个补进 NOTICE。

- [ ] **Step 3: 复核没有带入禁复制图片**

```bash
find app assets -type f \( -name '*.jpg' -o -name '*.png' -o -name '*.webp' \) 2>/dev/null | grep -v 'ic_launcher\|mipmap' 
```
Expected: 只有 `app/src/main/assets/default-wallpaper.jpg`。多出的每一张都要能说明来源,否则删。

- [ ] **Step 4: Commit**

```bash
printf '\n## 许可证\n\nApache-2.0,见 `LICENSE`;第三方声明见 `NOTICE`。\n' >> README.md
git add LICENSE NOTICE README.md && git commit -m "docs: Apache-2.0 LICENSE and NOTICE

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: 文案抽出到 strings.xml(简 / 繁 / 英)

**Files:**
- Create: `app/src/main/res/values/strings.xml`(简中,默认)、`app/src/main/res/values-zh-rTW/strings.xml`、`app/src/main/res/values-en/strings.xml`
- Modify: 所有含用户可见字面量的 `.kt`(`Text("…")`、菜单项、引导卡、提示)
- Test: 构建 + lint 检查

**Interfaces:**
- Produces: 资源 id 命名 `menu_edit`、`menu_wallpaper`、`menu_screensaver`、`menu_system_settings`、`menu_set_default_home`、`home_card_*`、`picker_*`;Compose 里统一用 `stringResource(R.string.xxx)`。
- 默认 `values/` = 简体中文(v1 用户是国行);首次引导选语言是 M7 的事,M1 只保证三份资源齐、构建过。

- [ ] **Step 1: 盘点字面量**

```bash
cd ~/GitHub/UnitedU-launcher
grep -rnE 'Text\(\s*"|"[^"]*[\x{4e00}-\x{9fff}][^"]*"' app/src/main/java --include='*.kt' | grep -v 'Log\.' > /tmp/literals.txt; wc -l /tmp/literals.txt; cut -d: -f1 /tmp/literals.txt | sort | uniq -c
```
把这份清单贴进 `docs/WORKLOG.md`,数量就是本任务的验收基线。

- [ ] **Step 2: 写三份 strings.xml**。每个字面量一个 key,先建 `values/strings.xml`(简中),再复制成 `values-zh-rTW`(用 opencc 转繁:`brew install opencc && opencc -c s2twp -i values/strings.xml -o values-zh-rTW/strings.xml`),`values-en` 手写英文。示例(以 TvHome 齿轮菜单四项 + 引导卡为准):

```xml
<resources>
    <string name="app_name">UnitedU</string>
    <string name="menu_edit">编辑桌面</string>
    <string name="menu_wallpaper">换壁纸</string>
    <string name="menu_screensaver">屏保图库</string>
    <string name="menu_system_settings">系统设置</string>
    <string name="menu_set_default_home">设置默认桌面</string>
    <string name="home_settings_change">在系统设置中更改</string>
    <string name="home_settings_current">当前默认桌面</string>
    <string name="edit_move_left">往左移</string>
    <string name="edit_move_right">往右移</string>
    <string name="edit_change_image">换卡片图</string>
    <string name="edit_remove">从这一行移出</string>
    <string name="edit_no_more_apps">没有可添加的应用了</string>
</resources>
```
Step 1 清单里的每一条都要有对应 key;三份文件 key 集合必须完全相同。

- [ ] **Step 3: 替换代码里的字面量**为 `stringResource(R.string.key)`(Composable 内)或 `context.getString(R.string.key)`(非 Composable)。逐文件改,每改完一个文件跑一次 `gradle --no-daemon compileReleaseKotlin`。

- [ ] **Step 4: 验收**

```bash
cd ~/GitHub/UnitedU-launcher && source scripts/env.sh
grep -rnE '"[^"]*[\x{4e00}-\x{9fff}][^"]*"' app/src/main/java --include='*.kt' | grep -v 'Log\.' | wc -l
python3 - <<'EOF'
import re,glob
sets={f:set(re.findall(r'name="([^"]+)"',open(f).read())) for f in glob.glob('app/src/main/res/values*/strings.xml')}
base=sets['app/src/main/res/values/strings.xml']
for f,s in sets.items(): print(f, len(s), 'MISSING' if s!=base else 'ok', sorted(base^s)[:5])
EOF
gradle --no-daemon lintRelease assembleRelease 2>&1 | grep -E 'MissingTranslation|HardcodedText|BUILD'
```
Expected: 中文字面量 `0`;三份文件全 `ok`;lint 无 `MissingTranslation`;`BUILD SUCCESSFUL`。

- [ ] **Step 5: 装到模拟器目视一遍三种语言**(Task 8 装好模拟器后):`adb shell setprop persist.sys.locale zh-TW; adb shell stop; adb shell start`,再 `en-US`,截图对比齿轮菜单四项。

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: extract all UI copy to strings.xml (zh-CN default, zh-TW, en)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: Android TV 模拟器(工具链收尾;M7 的引导与 M8 的回归都靠它)

**Files:**
- Create(仓库外):`~/Library/Android/sdk/emulator/`、`~/Library/Android/sdk/system-images/android-34/android-tv/arm64-v8a/`、`~/.android/avd/unitedu-tv.avd/`
- Modify: `scripts/env.sh`(PATH 加 `$ANDROID_HOME/emulator`)、`CLAUDE.md`(加「模拟器」一段)

**Interfaces:**
- Produces: AVD 名 `unitedu-tv`;启动命令 `emulator -avd unitedu-tv -no-snapshot-load`;`adb -s emulator-5554`。

- [ ] **Step 1: 下载并手动解压**(和 SDK 三包同一套路;版本已核:emulator 16259959、TV 34 r03。选 34 不选 36:与 A95L 的 Android 版本更近,且 36 镜像大 150 MB)

```bash
S=~/Downloads/unitedu-toolchain; mkdir -p $S && cd $S; B=https://dl-ssl.google.com/android/repository
curl -sL -o emulator.zip $B/emulator-darwin_aarch64-16259959.zip &
curl -sL -o tv34.zip $B/sys-img/android-tv/arm64-v8a-34_r03.zip &
wait; unzip -tq emulator.zip && unzip -tq tv34.zip
unzip -q emulator.zip -d ~/Library/Android/sdk
mkdir -p ~/Library/Android/sdk/system-images/android-34/android-tv && unzip -q tv34.zip -d ~/Library/Android/sdk/system-images/android-34/android-tv
ls ~/Library/Android/sdk/system-images/android-34/android-tv/arm64-v8a/system.img ~/Library/Android/sdk/emulator/emulator
```
Expected: 两个路径都存在。`sys-img` 解出来的顶层目录若不叫 `arm64-v8a`,`mv` 成它。

- [ ] **Step 2: 手写 AVD(不装 cmdline-tools,避免又一个 148 MB)**

```bash
mkdir -p ~/.android/avd/unitedu-tv.avd
cat > ~/.android/avd/unitedu-tv.ini <<'EOF'
avd.ini.encoding=UTF-8
path=/Users/gordonwang/.android/avd/unitedu-tv.avd
target=android-34
EOF
cat > ~/.android/avd/unitedu-tv.avd/config.ini <<'EOF'
AvdId=unitedu-tv
PlayStore.enabled=false
abi.type=arm64-v8a
avd.ini.displayname=unitedu-tv
hw.cpu.arch=arm64
hw.lcd.density=320
hw.lcd.height=1080
hw.lcd.width=1920
hw.ramSize=2048
hw.keyboard=yes
hw.dPad=yes
image.sysdir.1=system-images/android-34/android-tv/arm64-v8a/
tag.display=Android TV
tag.id=android-tv
disk.dataPartition.size=4G
EOF
sed -i '' 's#platform-tools:#platform-tools:$ANDROID_HOME/emulator:#' ~/GitHub/UnitedU-launcher/scripts/env.sh
```

- [ ] **Step 3: 启动并装包**

```bash
source ~/GitHub/UnitedU-launcher/scripts/env.sh
emulator -avd unitedu-tv -no-snapshot-load -no-audio > /tmp/emu.log 2>&1 &
adb wait-for-device && adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done; echo booted'
adb install -r ~/GitHub/UnitedU-launcher/app/build/outputs/apk/release/app-release.apk
adb shell cmd package set-home-activity --user 0 com.uniteduone.launcher/.MainActivity
adb shell input keyevent KEYCODE_HOME; sleep 3; adb exec-out screencap -p > /tmp/unitedu-home.png; sips -g pixelWidth /tmp/unitedu-home.png
```
Expected: `booted`、`Success`、截图 1920 宽,画面是三行卡片 + 时钟(内容和 TvHome 一样)。**这张截图是 M1 的最终验收证据**,放进 `docs/WORKLOG.md`。

- [ ] **Step 4: Commit**

```bash
cd ~/GitHub/UnitedU-launcher && git add scripts/env.sh CLAUDE.md docs/WORKLOG.md && git commit -m "chore: emulator AVD unitedu-tv, env PATH

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" && git push
```

---

## 自检(写完后按 spec 复核)

- §8 独立仓库/GitHub 远程 → Task 1;逐文件复制 → Task 2–3;七条铁律进 CLAUDE.md → Task 1;工具链 → 前置状态 + Task 8;签名 → Task 4;去 adb 依赖 → Task 5;文案三语 → Task 7;LICENSE/NOTICE → Task 6;更新通道、上传页、README 用户版 → M6/M7,不在此计划。
- §11 M1 行「Core 装工具链、新建仓库 + GitHub 远程、从 TvHome 复制起点代码、签名、包名、LICENSE/NOTICE、文案抽出与三语」全部覆盖。
- 估时:Task 1 0.5 h;Task 2 0.5 h(等 Gordon 复制);Task 3 2 h;Task 4 1 h;Task 5 1 h;Task 6 0.5 h;Task 7 4–6 h(视字面量数量);Task 8 1.5 h(含下载 1.2 GB)。合计约 **1.5–2 个工作日**,比 §11 的 2.5 天少,因为工具链今天已装完。

## 已定决策(2026-09-14 Gordon)

1. **包名**:`com.uniteduone.launcher`(自有域名 uniteduone.com 反写)。
2. **GitHub 远程**:`GordonWang1878/UnitedU-launcher`,公开,Task 1 首个 commit 起就建。
3. **执行方式**:子代理驱动(subagent-driven-development),每任务派新代理、任务间人工审查。

## 执行前置(两件事等 Gordon,不阻塞 Task 1)

- **TvHome 源码**:Gordon 在 Hub 上 `cp -R "Sony TV/launcher" ~/Public/unitedu-handoff/launcher`,Core 才能 rsync(Task 2)。SSH 读不到 Hub 的 Desktop/Documents/Downloads(TCC)。
- **签名密码**:Gordon 在 Core 写 `~/.unitedu/release.properties`(三行,密码自填),Task 4 才用。
- **电视 adb**:192.168.1.50:5555 重启后关闭,Task 8 用模拟器不需要真机;M8 真机验收前 Gordon 在电视上重开网络调试。
