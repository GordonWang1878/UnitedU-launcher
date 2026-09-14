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
