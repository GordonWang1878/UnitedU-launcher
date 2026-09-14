# UnitedU 工作记录

## 2026-09-14 · M1 前置:Core 工具链装完并验证,M1 计划写出

- 装了 JDK 17.0.20.1+1(Temurin aarch64)→ `~/Library/Java/jdk-17`;Gradle 8.14.5 → `~/Library/Gradle/gradle-8.14.5`;Android SDK(platform-tools r37.0.1 / platforms android-35 / build-tools 35.0.0 / licenses 三 hash)→ `~/Library/Android/sdk`。版本、路径与 Hub 完全一致;五个包 SHA-256/SHA-1 与来源清单核对通过。
- Core 上 `dl.google.com` 与 `dl-ssl.google.com` 都通(Hub 只有后者通),配置统一用 `dl-ssl`。
- 端到端验证:scratchpad 里一个最小 Compose 工程(AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.01 / `android.builder.sdkDownload=false` / maven 走 dl-ssl 镜像)`assembleDebug` 成功,产出 22 MB debug APK。Gradle 缓存 ~0.8 GB。
- **未解决**:① TvHome 源码在 Hub 上 sshd 读不到(Desktop/Documents/Downloads 全部 `Operation not permitted`,TCC 限制;`~/Public/unitedu-handoff/` 可读,目前只有两份文档)。② 电视 192.168.1.50 可 ping、5555 端口关闭(重启后 adb tcpip 失效),M8 前需在电视上重开。
- 计划:`docs/superpowers/plans/2026-09-14-m1-bootstrap.md`(8 个任务,估 1.5–2 天)。代码一行未动,仓库尚未 `git init`。

## 2026-09-14 · M1 决策定案(未开工)

- 包名 `com.uniteduone.launcher`(自有域名 uniteduone.com 反写,发布后不可改)。
- GitHub 远程 `GordonWang1878/UnitedU-launcher`,公开,Task 1 首个 commit 起建。
- 执行方式:子代理驱动。
- 计划已按以上更新。仍未 `git init`、未动代码(遵「先装工具链、不要动代码」)。执行前需 Gordon:① Hub 上复制源码到 ~/Public;② Core 写 ~/.unitedu/release.properties。

## 2026-09-15 · 签名密钥保管方式

- Gordon 定:一个随机强密码(store=key 同值),存 Apple「密码」App(条目「UnitedU 发布签名密钥」,用户名 unitedu);密钥文件 `~/.unitedu/release.jks` 由 Task 4 生成后备份到 iCloud Drive `UnitedU-keystore/`。`.jks` 自带密码加密,云盘存储安全。密码与文件分两处 = 双保险。
- 计划 Task 4 已加「Step 5:备份到 iCloud Drive」。`.jks`/`.keystore` 已在 .gitignore(Task 1)拦截,永不进仓库。
- Gordon 已建 `~/.unitedu/release.properties`(权限 600),正在填真密码(此前误填占位字「改这里」,已给改法)。

## 2026-09-15 · M1 开跑:Task 1–2 完成

- Task 1(控制器):git init、.gitignore(挡 *.jks/*.keystore/release.properties/.superpowers)、scripts/env.sh、CLAUDE.md(七条焦点铁律逐字复制)、README;建公开 GitHub 仓库 github.com/GordonWang1878/UnitedU-launcher 并推送(remote 由 SSH 改 HTTPS + gh 凭据助手)。commit b2bc734。
- Task 2(控制器):rsync 源码到 ~/GitHub/_staging/tvhome(34 文件,无仓库 diff)。TvHome 构建值:AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01 / minSdk 28 / targetSdk 35 / compileSdk 35 / buildTools 35.0.0 / versionName 0.1。旧包名引用 17 处。
- 执行方式:子代理驱动(SDD),ledger 在 .superpowers/sdd/。Task 3 起派实施代理 + 任务评审。

## 2026-09-15 · M1 全部完成(Task 1–8)

模拟器验收(unitedu-tv,Android 14 TV):release APK(2.08MB,签名 CN=UnitedU)装上启动渲染正常——金色生成壁纸 + 齿轮 + 时钟。三语端到端验证(per-app locale):en「No apps to show」/ zh-CN「没有可显示的应用」/ zh-TW「沒有可顯示的應用程式」(台湾用语,非机械转换)。健壮性:坏 JSON、空 layout.json 均不崩(0 FATAL,齿轮可达)。空首页符合预期(自动铺应用是 M7)。截图见 docs/screenshots/home-{en,zh-CN,zh-TW}.png。

**模拟器默认桌面设置的注意**:`cmd package set-home-activity` 在此模拟器上报 Success 但 HOME 键仍解析到 Google TV launcher;直接 `am start` 拉起 UnitedU 正常。真机切换靠齿轮里的「设置默认桌面」引导卡(HOME_SETTINGS),已在 TvHome 实证。M1 不阻塞。

M1 里程碑达成,全部推送到 github.com/GordonWang1878/UnitedU-launcher。

## 2026-09-15 · M1 里程碑收口(终审通过)

opus 全分支终审(b2bc734..4534256,8 commit):**可合并 = 是**,零 Critical、零 Important。构建自洽、release 签名 CN=UnitedU 已验、无密钥泄漏、三语 key 完全一致、焦点不变量保留(无 LazyRow/scroll)、LICENSE/NOTICE 准确。

### 我在 M1 期间替 Gordon 做的裁决(醒来复盘,错的可回退)

1. **Task 1 由控制器直接做**(仓库此刻才建,worktree 脚本需已有仓库;含已批准的 GitHub 建仓)。代价若错:骨架易改。
2. **工作直接在 main、不逐任务开 worktree**(串行互相依赖,worktree 只对并行改文件有意义)。代价:无。
3. **远程 SSH 无 key → 改 HTTPS + gh 凭据助手**。代价:仅推送通道,可改回。
4. **默认壁纸用 Python(PIL+numpy)生成,不装 ffmpeg**(Core 无 ffmpeg)。复刻噪声配方,非第三方图。代价:观感可重生成。
5. **包名 com.uniteduone.launcher**(你选的,基于 uniteduone.com)。发布后不可改。
6. **签名密钥保管**:密码存 Apple 密码 App,.jks 备份 iCloud Drive(你选)。
7. **Task 5 适配真实代码**:计划假设的 onReceive 结构与实际不同,抽 isDefaultHome + shouldRelaunchHome 纯函数门控。代价:去-adb 逻辑,单测覆盖真值表。
8. **zh-TW 由实施代理直接简→繁(台湾用语),不装 opencc**。代价:繁体用词可后调(实测「應用程式/選取/新增」正确)。
9. **Layout.kt 的 error() 中文异常消息不抽**(非 UI)。终审确认无用户影响。
10. **Theme.TvHome 样式名、settings.gradle mirror URL 去重、docs 旧包名/IP** → 全部 park 到 M2,不在 M1 改。
11. **13 个 lintRelease 错误确认为 TvHome 预存债**(assembleRelease 不跑 lint,构建正常;QueryAllPackages 对 sideload 合规)→ M2 清理。
12. **Task 8 由控制器做**(环境/验证,无代码评审面)。

### 留给 Gordon 决策的 M2/后续项
- overlay 门在 AOSP 合规机型会抑制本可成功的 post-update relaunch;M2 可改「先试 HOME、失败再跳过」。
- fail-closed 发布守卫(如 UNITEDU_REQUIRE_RELEASE_KEY=1,缺密钥就构建失败而非静默回落 debug 签)——放 M7 发布流程。
- android:allowBackup 未显式设(默认 true);设计§5「不做备份」倾向 false,属行为决策。
- lint 债清理(13 错 + AutoboxingStateCreation×18 等 warning)。

M1 全部推送 github.com/GordonWang1878/UnitedU-launcher(main,8 commit)。

## 2026-09-15 · M2 起步(计划草稿 + Task A 颜色收拢)

- **M2 计划草稿** `docs/superpowers/plans/2026-09-15-m2-theme-settings.md`:9 任务(A 颜色收拢 / B 设置持久化 / C 设置页骨架+焦点账本 / D 行数1-5 / E 卡片三档 / F 主题色预设+跟随主色 / G 时钟 / H 输入源行 / I 待机设置)。含 4 个「需 Gordon 决策」门:①卡片三档确切尺寸 ②6 个主题色预设 hex ③设置页布局与项顺序 ④待机在 M2 只做 UI 还是接行为。
- **Task A 完成**(commit cb2ed01,评审 Approved 零 issue):45 处散落 `Color(0x)`(31 不同值)收进 Theme.kt 26 个命名常量,逐调用点值正确无接错,既有 5 成员未动,color 值集完全一致(diff 空)。Theme.kt 外零 `Color(0x)`。
- 本轮自主到此:Task B(设置持久化)留到与 Task C 一起定字段集;C–I 等决策门。
- 顺手修:DESIGN §1 包名从「待定」改为已定值(终审 doc-hygiene 项)。
