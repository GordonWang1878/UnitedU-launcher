# Aerial TV 独立研究与 UnitedU 对比（Pi）

日期：2026-10-28。作者：Pi（独立研究，不是 Codex 初稿的复核稿）。性质：**研究结论，不是 Gordon 的实施授权**；既有报告与已签署方案（`2026-10-22-cytv-codex-pi-proposal.md`）原文保留，本稿只提出勘误与增补。

## 0. 基线与方法

| 项 | 值 |
|---|---|
| Aerial 固定基线 | [`f57e46b`](https://github.com/pengchujin/aerial-tv/tree/f57e46b1b0459b1ced68025538bb85abab955636)，构建配置 `1.62` / `versionCode 76`（Codex 用的同一提交） |
| Aerial 最新 HEAD（我另读） | `022add6`（2026-10-27 23:40 +08:00），**基线之后还有 19 个功能提交而版本号未升**：开机/唤醒只开一次目标（#766）、康佳 `CLEAR_TASK`（#710）、海信聚好看列原桌面（#700）、FallbackHome 改用用量统计（#701）、手柄时不过滤按键（#774）、Android 13+ 会话安装（#723）、守护「聋」后重绑（#651）、HOME 查询带 `DEFAULT`（#767）等 |
| Aerial 规模 | `app/` 84 个 Kotlin 文件 + `lite/` 28 个；`tools/install.sh` 379 行、`restore.sh` 60、`report.sh` 137、`selftest.sh` 201、`site/agent.md` 297 |
| Aerial 项目状态 | 2026-10-20 建仓，7 天内 6,662 star / 554 fork / **252 个 open issue** / 55 个 release，MIT；作者自维护的真机只有两台（小米 MiTV-MFTR0 Android 14、TCL MT5879 Android 11） |
| UnitedU | 本仓库 `1.2.0` / `versionCode 41`（`6cd5679`），minSdk 28 / targetSdk 35，单 APK |
| 我做过的验证 | 只读源码与公开 issue；GitHub API 读项目元数据；在隔离 AVD（`unitedu-tv`，AOSP TV 14）上做 HOME 解析只读对比（§6.2）。**未编译、未安装、未运行 Aerial 任何脚本，未连接 Gordon 的电视** |

证据标注约定：**[源]** 我读到的源码/构建脚本；**[项目方]** 仓库内 `README`/`HANDOFF`/`devices/*.md` 的自述（含作者真机记录）；**[用户]** GitHub issue 里报告人自己的日志/实验；**[实测]** 我本轮亲自跑的。三类不互相替代。

---

## 1. 结论（先说要紧的三条）

1. **Aerial 是同一用户需求下的一个成熟度不均衡的竞品桌面，不是"接管工具"。** 它的桌面、磁贴、壁纸/屏保、手机页做得比 UnitedU 更深（磁贴重绘算法、航拍/DV 播放接力、逐项真机性能数据）；它的独有价值几乎全部集中在一件事上：**让"装不上 / 抢不到 HOME / 待机被杀"这三类国行故障变成可交付的路径**，并把每条路径写成机型档案。这部分是 UnitedU 真正缺的。
2. **Aerial 的"待机复活"与 `WRITE_SECURE_SETTINGS` 是同一件事，不能当两个独立候选。** `am force-stop` 会把应用的无障碍服务从 `enabled_accessibility_services` 摘掉（Aerial 文档 + UnitedU 自己 2026-10-02 的实测各自独立得出同一结论），所以只有"通知监听/同步适配器把进程拉起来"而没有写回 secure settings 的权限，复活后**接管依然是死的**。Codex 初稿把两者并列，这是最需要修正的一处事实性偏差。
3. **Aerial 不能作为"用户控制已解决"的先例。** `MainActivity` 每次启动都调 `GuardSetup.ensureEnabled()`，同步适配器在守护未运行时也无条件把它写回 [源]——用户在系统设置里关掉守护，下次打开 Aerial（或下一次任意设置写入触发的同步）就会被重新打开。这与我们方案里 E6 的约束正好相反。

其余判断：**值得纳入的是"设备知识与证据闭环"和少数纯逻辑修复，不是它的权限模型、不是它的组件清单、也不是它的资源做法。**

---

## 2. 产品能力全貌

### 2.1 Aerial 实际有什么

| 维度 | Aerial 1.62/HEAD | 证据 |
|---|---|---|
| 定位/外观 | 国行 Android TV，tvOS 26 风格：单排磁贴 + 玻璃底板 + 网格，全自绘 Compose（明确不用 androidx.tv / Material），960×540 逻辑画布按屏宽缩放 | [源] `ARCHITECTURE.md`、`app/build.gradle.kts` |
| 桌面组织 | 收藏排序、**文件夹**、隐藏、卸载、开机打开某个 App/信号源、信号源磁贴（可选 8 种设备外观） | [源] `apps/LayoutStore.kt`、`apps/SourceLooks.kt`、`system/BootOpen.kt` |
| 磁贴图 | 5 级优先：手机换图 → 系统 App 自绘 → **远端"精选图标库"**（当贝 4.1.7 美术资源二次分发）→ TV 横幅 → 自适应图标/满底色 → 老式图标同色渐变；带"白色 logo 统一风格"与逐条注明验证机型的重绘配方 | [源] `apps/TileArt.kt`、`TileRecipes.kt`、`IconLibrary.kt`；[项目方] `ARCHITECTURE.md` |
| 手机端 | NanoHTTPD 8790 + 8 位随机 token 路径前缀，仅面板打开时运行（+5 分钟后关）：换壁纸/屏保、传照片与视频、改名、排序、文件夹、换图标、天气城市、诊断、看磁贴现状 | [源] `wallpaper/PhoneServer.kt`、`assets/web/index.html` |
| 输入源 | 标准 `TvInputManager` + 小米 Intent 路由 + TCL/创维/酷开/海信/康佳/极米/微鲸/雷鸟私有路径 + `assets/devices.json` 按 `ro.product.model` 修正 + **信号源画面当壁纸**（透传 TvView）+ 开机/唤醒打开固定信号源 | [源] `system/SystemState.kt`、`DeviceProfile.kt`、`ui/home/SourceWallpaper.kt` |
| 壁纸/屏保 | 必应每日图、14 张自绘 Mac 风壁纸、手机上传照片/视频、**Apple 航拍流（约 0.8–1.3 s 出首帧，4K/杜比视界档位）**、跟随 App；屏保三条路（桌面内、系统 Dream、壁纸接力成屏保）；防烧屏漂移 | [源] `wallpaper/*`；[项目方] `ARCHITECTURE.md` |
| 频道 | **没有** Android TV Channel 行。作者查过：读别的 App 的频道要 `ACCESS_ALL_EPG_DATA`（signature\|privileged），两台电视上都只有 TvProvider 自己有 | [项目方] `HANDOFF.md`（1.27 条）；我复核代码里确无 `TvContract.Channels` 写入/读取实现 |
| 权限/隐私 | 联网点多：必应、Open-Meteo 天气、`sylvan.apple.com` 航拍、`aerial.qust.me` 图标库与更新；手机页明文 HTTP + token；诊断会脱敏 IP/MAC/token、不取账号/Wi-Fi 名/App 列表 | [源] `network_security_config.xml`、`Diagnostics.kt`、`report.sh` |
| 升级 | 每 3 小时读官网 `version.txt` → 下载 → 校验包名/签名/versionCode → **Android 13+ 走安装会话**（避免 `packageSource=3` 导致 `ACCESS_RESTRICTED_SETTINGS` deny）、12 及以下走 `ACTION_VIEW` 交给系统安装器（TCL 只放行系统安装器发起） | [源] `update/Updates.kt` |
| 适配与证据 | `tools/report.sh`（只读、脱敏）+ `tools/selftest.sh`（行为自检 PASS/FAIL/SKIP + JSON）+ `site/agent.md`（297 行 Agent 安装/还原指引）+ `site/adapt.md`（适配指引）+ 4 类 issue 模板 + `docs/devices/` 24 篇机型笔记 | [源] 目录与脚本 |

### 2.2 UnitedU 实际有什么（对照，避免把"功能多"当优劣）

- 中国大陆行货 + 海外 Google TV 双线；**多行货架编辑**（1–5 行、行内卡片图、拿起/放下、频道架）、**真正的 Android TV 频道行**（含授权、无授权态、频道消失/变少的落点）、**Ultra HDR 增益图**内置图与离线优先的资料库、手机页上传与 APK 安装、系统 Dream 屏保、自我更新（会话 API + 双通道 Beta/稳定 + 向下兼容铁律）、大量 e2e 夹具脚本与逐条裁定（R 号）。
- **接管能力只有两条**：`HomeKeyService`（HOME 键过滤 + 原厂桌面窗口盯守 + 1 s 去抖 + 补查）与「设为默认桌面」。状态自报做得比 Aerial 细（`ON / OFF / NOT_RUNNING / RESTRICTED`，心跳文件 + `BOOT_COUNT`，受限按安装来源推断并记忆）。
- 明确没有：`WRITE_SECURE_SETTINGS`、`PACKAGE_USAGE_STATS`、通知监听、同步适配器/账号、开机广播接收器、厂商 appops、系统屏保写回、厂商私有属性。

一句话：**产品面 UnitedU 更完整（尤其频道行与资源管线），接管面 Aerial 更深（尤其"装不上/被杀/看不见的原厂桌面"三类）。**

---

## 3. 接管机制拆解（逐条：它解决什么 / UnitedU 是否已有 / 是否增量）

### 3.1 标准 HOME

`standard` flavor 只多一个 intent-filter（`MAIN + HOME + DEFAULT`，另有酷开私有 `category.HOME.CC`）[源]。装完后 `cmd role add-role-holder` → 失败再 `cmd package set-home-activity` → 最后用 `resolve-activity` 复查，不是自己就转守护 [源]。**这是"先走温和路径、失败才加权限"的正确顺序**，UnitedU 目前是纯手动、没有这一步的自动化（A1 文档里只有文字）。

增量：**"设默认桌面 → 复查 → 不成立则降级"的脚本化**（不必引入 Aerial 的权限，只要把顺序和复查写死）。代价：小。

### 3.2 `guard` 变体（不声明 HOME）

存在的唯一原因是**安装期就被拒**，不是行为差异：TCL 固件对声明 HOME 的 APK 直接 `INSTALL_FAILED_VERIFICATION_FAILURE`；OPPO 电视报 `INSTALL_FAILED_DUPLICATE_PERMISSION: … because of launcher categories` [项目方 + 用户 #371 复现]。两个 flavor 同 `applicationId`、同 `versionCode`，只差 manifest，因此可互相覆盖安装且保留数据 [源]。

UnitedU 的 README 把 TCL/雷鸟列为「装不上」——**如果要做，就只能靠这个变体**，没有第二条路（HOME 声明无法在运行时关掉，`activity-alias` 同样会被安装期判定）。这是"单 APK 决策"唯一的真实例外，详见 §8.1。

### 3.3 按键过滤（截键）

`HomeGuardService.onKeyEvent`：HOME 按下/松开都吃、拉起自己；长按 → 打开最近任务；双击 → 最近任务；同时**接管音量键**（因为过滤开着时固件拿不到音量，TCL 会一按两格：过滤前调一次、放行又调一次 [项目方 1.38]）；VIDAA 上干脆完全不过滤，把音量交回固件 [源]。

`updateKeyFilter()` 的生效条件是全项目最值钱的一段逻辑 [源]：

```
wanted = !keysPassThrough && !screenOff && !padGame && (SDK <= 30 || aerialResumed)
```

- `keysPassThrough`：装了 VIDAA 组件就不开过滤（海信音量会全废，#7）。
- `screenOff`：屏幕关着时不过滤（唤醒那一下的按键不带 `FLAG_PASS_TO_USER`，且 TCL 待机不唤醒的嫌疑项之一）。
- `padGame`：接了 `SOURCE_GAMEPAD` 且前台不是自己/不是原厂桌面时**关掉过滤**——因为过滤开着时每个手柄事件都要经 `system_server` 主线程经 `AccessibilityInputFilter` 重新注入，小米 Android 11 上 Moonlight 串流会挂死看门狗、整机重启（#774，8 条 dropbox 记录）[用户 + 源]。
- Android 12+ 只在自己前台时过滤；小米 12+ 干脆从不过滤（`backOnPause`：改成在 `onPause` 里抢回，首领 120–290 ms）[源]，理由之一是长时间按住音量键不再连发（#632）。

UnitedU 现状：`onServiceConnected` 一次性设 `FLAG_REQUEST_FILTER_KEY_EVENTS`，之后**永不更新**；`onKeyEvent` 只吃 HOME，其他键返回 false（对音量行为正确，但**旗标本身仍让所有键过一次过滤器**）；没有手柄检测、没有屏幕关闭判断。→ **UnitedU 与 #774 的暴露面同构**。

增量：`updateKeyFilter` 式的"按条件开关过滤"。收益：避免一类整机重启与音量回归；代价：约 100 行 + 一次服务状态重设；风险：关过滤期间 HOME 会闪原厂桌面（可接受，仅手柄/息屏时）；验证：单测判定表 + 有手柄的 AVD 上确认 `dumpsys input`/日志里过滤开关切换；不采用的代价：把已知会重启电视的路径留给用户。

### 3.4 窗口盯守与"原厂桌面"建模

Aerial 的 `refreshStockHomes()` 是本轮最值得抄的**数据与判据**（不是代码）[源]：

- 候选 = `MAIN+HOME(+DEFAULT)` ∪ **厂商私有 HOME category**（`android.intent.category.HOME.`* 前缀、`*_HOME` 后缀，如酷开 `HOME.CC`、`LITE_HOME`）；
- **排除负优先级**（开机向导 / `FallbackHome`），除非固件真的会启动它（`LOW_PRIORITY_HOMES`）；
- **排除信号源播放器包**（`com.tcl.tv` / `com.xiaomi.mitv.tvplayer` / `com.skyworth.tv`），除非它就是系统解析出来的 HOME（TCL 85G60E 把 `com.tcl.tv` 列为 HOME，每次切信号源被拉回，#223）；
- **排除非桌面**（乐视 `com.stv.appdaemon`、`com.letv.lekids`）；
- **补 trampoline 背后的真实界面**（乐视 `ExternalJumpActivity` → `ui.activity.Launcher`；腾讯极光 `LauncherMainActivity` → `LauncherHomeSingleTaskActivity`）；
- **补"声明了 HOME 但固件查询不到 / 根本不声明"的固件桌面**（松下 `com.skyworth.panasonichome`、酷开 `com.coocaa.aigc.home`、天赐 `com.tianyi.movieplatform`、中国移动乐家 `com.mylejia.launcher`、海信聚好看 `com.jamdeo.tv.vod`）；
- **只认 Activity**（`getActivityInfo` 成功），弹窗/浮层不算（TCL 语音助手 `com.tcl.walleve` 在开机时抢前台，曾让守护不接管）；
- 切信号源时会先经过原厂桌面（创维），见此情形延后 1.5 s 复查再判。

UnitedU 现状：`stockHomes = queryIntentActivities(MAIN+HOME) − 自己`，**不排除负优先级、不排除播放器、不含厂商 category、不含 trampoline、不含固件私有桌面**。→ 这在创维/乐视/海信/极光这类机器上会**误判**（把用户主动打开的输入画面/电视播放器当桌面拉回，或把 `FallbackHome` 当桌面）。我在 AVD 上实测：AOSP TV 14 的 `queryIntentActivities(MAIN+HOME)` 里确实有 `com.android.tv.settings/.system.FallbackHome`（priority=-1000），现行写法会把它收进原厂桌面集合 **[实测]**。

增量：把这张表 + 判据做成我们自己的 `devices.json` 与单测。收益：直接覆盖整类误接管/不接管；代价：2–3 人日 + 每人一条要有来源；风险：表靠外部报告，必须逐条标证据级别；验证：纯逻辑单测（输入候选清单 → 期望集合）+ 在 AVD 上用占位 APK 造厂商 category 夹具；不采用的代价：创维/乐视/海信这些我们 README 现在直接写"用不了"的品牌永远没有判据可补。

### 3.5 用量统计兜底（对无障碍"看不见"的原厂桌面）

小米 Android 6 PatchWall、海信 VIDAA 聚好看对第三方无障碍**不发窗口变化事件**，HOME 键也被固件先吃掉；Aerial 改成：按 HOME 后 1.5 s 无窗口事件 → 每秒读 `UsageStatsManager`（`MOVE_TO_FOREGROUND`）判前台，是原厂桌面就拉回；「聋了」时更密（150 ms 采样）并做一次"拿出再放回"的重绑 [源]。需要 `GET_USAGE_STATS`（只能 adb/设置页授予）。

UnitedU 没有。**这是唯一能覆盖"原厂桌面在无障碍里隐身"的技术手段**，但它换来的是一个敏感权限（可读全部应用使用记录）。对我们"零广告零推荐、只在本机"的立场，这属于必须由 Gordon 拍板的产品决策，不能由工程顺手加。

### 3.6 待机复活（两段式）

`RevivalListener`（通知监听）在 `onListenerConnected` 里 → `GuardSetup.ensureEnabled()`（写回无障碍列表 + `accessibility_enabled=1`）→ 5 s 后 `rebindIfStalled()`；`RevivalSync`（账号 + 挂 `settings` authority 的同步适配器）给"系统禁止通知监听"的低内存 Android 9（小米 MFTP0）用，靠"设置写入 → 同步约 30 s 后跑"把进程拉起来 [源]；`BootReceiver` 声明 `directBootAware`，解锁前只记窗口不动作 [源]。

关键因果链（我的修正点）：

```
固件 force-stop → 无障碍服务被移出启用表 → （只有 WRITE_SECURE_SETTINGS 能写回）
```

所以通知监听/同步适配器**只负责让进程复活**，"复活后接管能不能用"完全取决于 `WRITE_SECURE_SETTINGS`。这也是为什么 Aerial 的 `install.sh` 必须授这个权限、`restore.sh` 第一步就是撤它 [源]。

副产物（用户报告，值得记）：`am force-stop` 之后若先 `am start` 再写设置，顺序不能反（#371 报告人复现）；收回 `WRITE_SECURE_SETTINGS` 后守护在 `dumpsys accessibility` 里显示 `Crashed services`（[用户]，我未能静态定位到未捕获的写入点，属未知）。

### 3.7 待机不唤醒的反向机制（TCL #371 / #132）

守护本身在某些 TCL 固件上导致"待机后唤不醒"（受控实验：只留守护也失败，撤掉守护一定正常 [用户 #371]）。Aerial 的应对是**opt-in 让守护在息屏时退出**（`disableSelf`）、亮屏再写回（`offInStandby`，`ConfigReceiver --ez guard_off_in_standby true`）[源]。默认关，且**没有界面开关**，只能 adb。

对我们的意义有两层：① 这类"守护本身是故障源"的可能性必须在试点里被显式排查（否则我们会把整机不唤醒记成"电视坏了"）；② 若将来做守护，必须有一个可关的退让模式——但入口应该是界面，不是 adb。

### 3.8 厂商安装/启动路径（都是特定固件线索）

| 路径 | 事实 | 级别 |
|---|---|---|
| TCL 多屏互动推送（`tools/tcast-push.py`） | 走 TCL 系统应用自己的推送协议，电视上点一次「同意」；配合 `adb reverse` 本地 HTTP 服务 | [源] 脚本 + [项目方] MT5879 等真机 |
| TCL `APP_AUTO_START` / `APP_ASSOC_START` appop | 不给就开机回 TCL 桌面；别的固件报 `Unknown operation string`，忽略即可 | [项目方] MT5879 |
| 海信 `persist.vt.use_home_pkg` | 改了之后 HOME 直接进第三方；**开机仍先进聚好看**（`VtInterceptApp` 在 `boot_completed` 前拦第三方进程） | [用户 #40] + [项目方] |
| 酷开 `HOME.CC` / `LITE_HOME` | 私有 category，固件按组件启动 | [源] manifest + [用户 #397] |
| 创维 `verifier_verify_adb_installs 0` | 用户反馈可装；未在创维真机验证，且要先记原值再写回 | [项目方] 明确标注"来自用户反馈、未验证" |
| Android 11+ 无线调试配对 | 索尼/TCL 新固件只开无线调试，`adb pair` 后 `adb tcpip 5555` 回到常规路径 | [源] `install.sh` + [项目方] 真机 |
| 写厂商属性 | `persist.vt.use_home_pkg` 是唯一一处；有前态备份（`settings global aerial_vt_home_pkg`）与还原 | [源] |

对照我们的边界（不改厂商属性、不停用系统包、不关校验）：**Aerial 跨过的三条线里，"多屏互动安装"和"TCL appops"是我们可考虑的（不动系统状态、有电视端确认），"关安装校验"和"写 persist 属性"不建议跟。**

---

## 4. 安装、权限与恢复：逐条对账

`install.sh` 实际修改（[源] 379 行）：

1. 安装 APK（失败 → 自动换 guard → `多屏互动` → 落到 `/sdcard/Download` + 当贝本地安装）；
2. `pm grant`：`ACCESS_FINE_LOCATION`（读 Wi-Fi 名）、`WRITE_SECURE_SETTINGS`；
3. `appops set`：`SYSTEM_ALERT_WINDOW`、`WRITE_SETTINGS`、`REQUEST_INSTALL_PACKAGES`、`GET_USAGE_STATS`（含 Android 5 的 `android:get_usage_stats` 别名）；
4. `cmd notification allow_listener`；拒绝时只打提示，不中断；
5. `screensaver_enabled=1` 时**直接把自己的 Dream 写成系统屏保**，并通过 `ConfigReceiver own_screensaver` 让 Aerial 每次启动都写回（TCL 开机会被改回去）；
6. TCL 两个 appops；
7. `KEEP_ADB=1` 时：`adb_enabled` 被关就写回 1；开机 3 分钟内探测 5555 不通就 0→1 抖一次（#454）；
8. `am start` + 等 5 s + `cmd package compile -m speed`；
9. `standard`：`role add-role-holder` / `set-home-activity` → 复查；海信改属性；
10. 追加写 `enabled_accessibility_services`（保留别人的）+ `accessibility_enabled=1`，等绑定，不行就强停重启（#72）。

**用户控制层面的三个事实（Codex 稿只说"不是精确快照"，我给出具体后果）**：

- `ensureEnabled` 在 `MainActivity` 每次启动、`BootReceiver`、`RevivalListener`、同步适配器里**无条件**调用 → 用户关掉守护后，打开一次 Aerial 就被写回。**没有"用户主动关闭"的表达方式**。
- `keep_adb` / `off_in_standby` / `cec_home_input` / `own_screensaver` 全在 `ConfigReceiver`（adb）里，**电视上没有开关**；控制中心「安装」页只给系统设置页的跳转链接。
- 授权失败用 `|| true` 吞掉 → 脚本"成功"不等于授权生效；`wait_guard` 用 `dumpsys accessibility` 的 `label=(Aerial|app.orchard.tv)` 判绑定（我们 AVD 上 uiautomator/dumpsys 的坑与此同源）。

`restore.sh` 的六个缺口（[源]，逐条可复核）：

| # | 缺口 | 后果 |
|---|---|---|
| 1 | 不撤销 `WRITE_SETTINGS` appop、不撤销 `ACCESS_FINE_LOCATION` | 恢复后仍留两个可写系统设置/定位的授权 |
| 2 | 不把 `accessibility_enabled` 复位 | 列表清空了，总开关仍是 1（用户看到"无障碍开着但没服务"） |
| 3 | 系统屏保写回 `screensaver_default_component` 而不是安装前的实际值 | 安装前若是第三方屏保，回不到原状 |
| 4 | 不还原安装前的默认桌面/首选 Activity（Android 10 以下没有 role，`set-home-activity` 的首选记录不回退） | 老系统上"Home now:"可能仍解析到自己 |
| 5 | 不还原 `cmd package compile -m speed` | 无害，但属未记录的变化 |
| 6 | `--uninstall` 不处理 Lite 的"已选为桌面"状态，只提示 | 4.x 用户卸载后可能停在选择框 |

结论：**Aerial 的还原是"脚本作者认为该还原的集合"，不是前态快照。** 我们方案里"记录前态 → 执行 → 读回 → 独立恢复通道"的要求是对的，且现在有了具体反例清单。

---

## 5. 设备证据分级（代表性报告与反例）

| 机型 / 固件 | 现象 | 级别与备注 |
|---|---|---|
| 小米 MiTV-MFTR0（Android 14）、TCL MT5879（Android 11） | 作者日常机：HOME 拉回、切信号源不被拉回、开机进 Aerial、屏保接力 | **[项目方]** 有逐条性能与日志，是项目唯一双机回归基线 |
| 小米 Android 11 + 手柄 + Moonlight 串流 | 遥控器/手柄全无响应，`system_server` 看门狗整机重启（8 条 dropbox，同签名） | **[用户 #774]** + **[源]** 已按此改 `padGame`。**这条直接适用于 UnitedU** |
| TCL tc8000_ay30a2（Android 11） | 守护在跑 → 待机无法唤醒，必须拔电；撤掉守护即正常（三组受控实验，`/proc/uptime` 验真 resume） | **[用户 #371]**；作者随后加 opt-in `offInStandby`（#132 同现象） |
| 小米 MiTV-MFTP0（Android 9，`ro.config.low_ram=true`） | 系统拒绝所有通知监听；待机强停后约 30–60 s 由同步适配器拉回 | **[项目方]** 设计与复现步骤；对"没有低内存机就无法验证"这一点作者也标注了 |
| 海信 VIDAA（MT9653 / RTD2851D / MSD6A828 / MT9669 / MT9655） | ①软件改 `persist.vt.use_home_pkg` 后 HOME 可用但开机仍聚好看；②音量键在过滤开着时全废→1.22 起不过滤；③聚好看不向第三方无障碍发窗口事件→用量统计兜底；④`accessibility_enabled` 每次开机被清 0、需触发包变更；⑤C1 投影接管不了 | **[用户 #40 #7 #84 #119 #120 #285 #323]** + **[项目方]** 笔记逐条标来源 |
| 创维/酷开（C10、P60、7T863、8R134/8R145、7T641、75A6E） | 私有 category / 开机先进酷开 / 被清理后 20–30 s 才回来；Android 13 机型**黑屏或卡死** | **[用户 + 项目方]**；"黑屏或卡死"是明确反例，不能当"支持" |
| 雷鸟 55S595C Ultra（MT9655） | 能装，接管不了主页键 | **[用户]**，README 记为 ❌ |
| 小米电视 2（Android 4.3） | Lite 能装能跑，HOME 由系统直接开小米桌面，当不了桌面 | **[用户 #770]** |
| 极米 m7642 / 神灯 L1 | `set-home-activity` 成功但默认桌面被"候选集变化"丢回极米；报告人用 `replacePreferredActivity` 双规则绕过 | **[用户 #767]** + **[源]** 已把脚本查询改成带 `DEFAULT` |
| 腾讯极光盒子 6/7S | 6 正常；7S 开机停原桌面；BA101 开机最后窗口是开机向导导致复查失效 | **[用户 #270 #269 #701]** |
| Xbox/PlayStation 之外：大眼橙 X7（Amlogic） | `/dev/cec` 发 CEC Root Menu 给 Apple TV，opt-in、未验证实体遥控器与待机 | **[用户 PR #392]** + **[源]** |

**不能从这张表推出的事**：这些成功记录不是统一条件下的复测，也不代表覆盖率；作者自己也只把两台当"日常使用"。其中至少 3 条（创维 13 黑屏、OPPO 改包名、乐视接管不稳）说明"装上"和"能用"之间还隔着固件行为。

---

## 6. 我实际做的验证与限制

### 6.1 静态核对（可复核）

- 逐行读了 `install.sh` / `restore.sh` / `report.sh` / `selftest.sh` / `agent.md` / `HomeGuardService.kt` / `GuardSetup.kt` / `RevivalListener.kt` / `RevivalSync.kt` / `BootReceiver.kt` / `CecHome.kt` / `Updates.kt` / manifest（含 `standard`）。§4 的六个缺口、§3.3 的过滤条件、§1 的"无条件写回"都来自这些文件。
- 交叉验证了一条与我方结论一致的机制：`am force-stop` 移除无障碍启用项（Aerial `install.sh` 注释与 `GuardSetup` 注释 + 我方 `CLAUDE.md` 2026-10-02 实测记录），**两个项目独立得出**。
- 复核 Codex 的一处负面结论：Aerial 确实没有 Channel 行，且作者在 `HANDOFF.md` 里给出了"为什么做不了"（`ACCESS_ALL_EPG_DATA` 是 signature\|privileged）。

### 6.2 隔离 AVD 实测（唯一一次真机级实验）

在 `unitedu-tv`（AOSP TV 14，`sdk_google_atv64_arm64`）上只读执行：

```
cmd package query-activities --brief -a MAIN -c HOME            → tvlauncher(priority=2) + com.android.tv.settings/.system.FallbackHome(priority=-1000)
cmd package query-activities --brief -a MAIN -c HOME -c DEFAULT  → 同上（该镜像的 FallbackHome 声明了 DEFAULT）
resolve-activity（带/不带 DEFAULT）→ 都是 com.google.android.tvlauncher/.MainActivity
```

结论：① 负优先级 `FallbackHome` **确实**出现在 `MAIN+HOME` 查询结果里（UnitedU 现行 `stockHomes` 会把它当原厂桌面）；② "带不带 DEFAULT 结果不同"只在极米那类固件成立，AOSP 镜像上**看不到差异**——所以这条只能按"固件特定"处理，不能当通用修复宣称。测完 `adb emu kill`，未改任何系统状态，未安装任何包。

### 6.3 没做（限制）

未编译 Aerial（未跑其 79 个单测与 lint）、未安装、未运行任何脚本、未连真机；未复测任何厂商路径；未审计 APK 体积/内存/耗电；未做联网面与本地服务的完整安全审计（`PhoneServer` 是明文 HTTP + 路径 token，能做换壁纸/改名/删照片/上传，是否能被局域网内他人利用取决于 token 泄漏面，我没有实测）。

---

## 7. 建议纳入清单

排序原则：**先补判断力与证据，再补少量零权限修复，最后才是要权限/要变体的重型项。**

### P0（建议本轮方案内做，不需要新权限）

**(1) 把"原厂桌面识别"升级成数据 + 判据（§3.4）**
- 收益：覆盖误接管/不接管整类故障；这是我们 README 里"创维/乐视/海信用不了"唯一能靠软件补的部分。
- 条件：每条记录必须有来源与固件；负优先级默认排除。
- 代价：2–3 人日（表 + 单测 + 占位 APK 夹具）。
- 风险：表是外部证据，可能过时；用"固件指纹 + 明确不命中就退回通用规则"控制。
- 验证：`HomeKeyPolicyTest` 扩成"候选清单 → 期望集合"表驱动；AVD 上装一个只声明 `HOME.CC` 的占位 APK 造夹具。
- 不采用：每遇到一台误拉回/拉不回的机器都要改代码，且我们无法解释原因。

**(2) 所有 HOME 解析带 `DEFAULT`，并排除负优先级**（§6.2）
- 收益：避免 Android 9 上"候选集变化 → 首选被丢"的整类问题（极米 #767）。
- 代价：半天。风险：无。验证：单测 + 脚本层 grep 断言。不采用：老系统上"设了默认桌面又丢"永远查不清。

**(3) 按键过滤按条件开关（§3.3）**：手柄 + 前台非桌面 → 关闭过滤；息屏 → 关闭过滤。
- 收益：避开 #774 一类整机重启与 #632 一类音量回归；也顺手解决"待机那一下 HOME 截不到"的伪故障。
- 条件：关闭期间接受 HOME 闪原厂桌面（只在玩游戏/息屏时）。
- 代价：~1 人日（判定表 + `serviceInfo` 重设 + 日志）。
- 风险：状态机写错会让过滤永久关闭（要像 R129 那样"自愈判据而不是一次性闩"）。
- 验证：判定表单测 + AVD 上插虚拟手柄（`InputDevice` 注入或用 `unitedu-gtv` 的蓝牙手柄不可得，至少验证开关切换日志）。
- 不采用：把已知会重启电视的路径留给用户，且我们无从解释。

**(4) `UnitedUDream` 收到 `ACTION_SCREEN_OFF` 自结束**（Aerial #371 / #371 的 1.61 修复）
- 收益：系统屏保是我们的 Dream 时，TCL 那类固件待机可能唤不醒——这是"用户设了我们的屏保，结果电视不亮"的严重故障。
- 代价：半天 + 一次模拟器验证（`cmd dreams start-dreaming` 走不通时用 Aerial 文的办法：`screensaver_*` + `screen_off_timeout`，见我方 `CLAUDE.md`）。
- 风险：几乎无（Dream 本来就在息屏时该退）。
- 不采用：留给用户一个"电视待机不亮"的黑锅。

**(5) A2/A3 拆成"只读 doctor"+"需同意的行为自检"**（吸收 `selftest.sh` 形态）
- 收益：只读诊断证明不了 HOME 真的回来；Aerial 的 PASS/FAIL/SKIP + JSON + "开机后逐秒前台时间线" 正是我们 A5 证据索引需要的格式。
- 条件：自检必须先列副作用（按 HOME、开系统设置、开输入源；可选待机/重启要单独同意）。
- 代价：+1.5 人日（在 A2/A3 内，不新增项目）。
- 风险：自检脚本会"动"电视 → 必须区分"只读"与"自检"两种模式，报告里写清做过什么。
- 不采用：试点拿回来的是"用户说能用"，无法比较。

### P1（有试点证据再定，需要产品决策或额外权限）

**(6) 守护"退出/复活"的完整故事**（§3.6 + §3.7）
- 收益：这是国行最大的单点收益——待机后被清理、开机后没接管，用户的感知就是"这桌面时好时坏"。
- 条件：**必须同时具备** `WRITE_SECURE_SETTINGS`（写回启用表）+ 某种复活源（通知监听或同步适配器）+ **明确的用户关闭语义**。
- 代价：3–5 人日 + 一个新权限的授权/撤销交互 + 至少一台小米类真机复现。
- 风险：与 E6 正面冲突（Aerial 的做法就是反面教材）；同步适配器要建账号、写 `settings` authority 的 syncable，属"新增 manifest 组件"，与我们"Beta 只新增字段"的兼容规则需要扩写成"新增组件也要能被回退包忽略"；低内存 Android 9 上通知监听根本不绑。
- 验证：先在模拟器上复现"`am force-stop` → 服务从启用表消失 → 谁能把它写回"；再在真实小米机上做"待机 1 分钟 → 唤醒 → HOME 是否可用"的对照。
- 不采用：Xiaomi 类设备上我们的接管只能维持到第一次待机。

**(7) 安装路径知识库 + TCL 多屏互动原型**（§3.8）
- 收益：把 README 里"TCL/雷鸟装不上"变成"有路径但要电视上点一次同意"；TCL 是国内出货大头。
- 条件：只在"ADB 直装被拒"时走；电视端有明确确认；不改校验、不停用系统包。
- 代价：1–2 人日（脚本 + 文档），协议本身约 100 行 Python 可读。
- 风险：未公开协议，固件升级可能失效；`VERIFICATION_FAILURE` 分支一旦误判会让人以为"装上了"。
- 验证：只在一台志愿者 TCL 上做，报告里记录 `installerPackageName` 与版本号。
- 不采用：TCL 用户只能靠 U 盘/当贝，支持成本长期落在人工。

**(8) `GET_USAGE_STATS` 兜底**（§3.5）
- 收益：唯一能覆盖"原厂桌面在无障碍里隐身"的手段（小米 6、海信 VIDAA）。
- 条件：仅在该机型复现后按需开启；权限说明要写清"能读到全部应用使用记录"。
- 代价：1 人日 + 权限文案/开关 + 隐私声明更新。
- 风险：与我们"零广告零推荐、只在本机"的叙事有摩擦；误用会变成常驻轮询耗电。
- 验证：模拟器上先用 `appops set … GET_USAGE_STATS allow` + 伪造前台事件验证判定，再等真机复现。
- 不采用：海信/老小米这两类"桌面隐身"设备只能靠用户手动按两次 HOME。

**(9) guard 变体（不声明 HOME 的第二产物）**（§3.2、§8.1）
- 收益：OPPO/TCL 这类"装不上"变成"装得上"。
- 代价/风险：见 §8.1；我建议**先只写设计、不发版**。

### P2（记录，不建议近期做）

- **CEC HOME**（`/dev/cec` 原始帧，给 Apple TV 转发 HOME）：设备/芯片特定、要碰别人的设备、作者自己标注未验证实体遥控器与待机；收益是一小撮"用 Apple TV 当信号源"的用户。→ 不纳入 1.x。
- **航拍流 / 每日必应**：内容层的差异化很强，但引 Apple CDN 与必应接口，违反我们"零联网"的默认叙事，且许可/资源问题不清（§9）。
- **远端图标库**：把别家 App 的美术资源二次分发（Aerial 用的是当贝 4.1.7 的图，存在作者自己的站点、不进仓库）。**不建议复制这条路**，哪怕技术上很有效。
- **Lite（Android 4.x 独立包）**：与新单 APK 决策直接冲突，且 4.x 真机仍未验证；Aerial 自己也标"测试版"。→ 不做。
- **音量键接管 / `keep_adb` / `off_in_standby` 走 adb 开关**：都是"用权限换便利"，与我们的用户控制立场相反。

---

## 8. 对既有方案（2026-10-22 提案）的补充与修改

**同意但需要加重的**：
- A1（文档/兼容模板）：应把 §3.8 的厂商路径按"证据级别 + 前置条件 + 可撤销性"写进模板；现有"首装必须 adb"的表述对 TCL 类机型不成立。
- A2（只读 doctor）：增加"默认桌面解析带 `DEFAULT`"和"命令行为差异"两类检查（极米 #767 就是脚本查询本身改变了结果）。
- A5（证据索引）：字段建议照 Aerial `report.sh`/`selftest.sh` 扩：`installerPackageName`、`appops get` 全量、`resolve-activity` 原始输出、开机后逐秒前台时间线、`dumpsys accessibility` 的绑定行。

**需要修改的**：
- **新增 A6：Aerial 式行为自检（需同意）**，与 A2 并列，不合并。理由见 §7(5)。
- **原方案 §4 的"自动恢复"候选描述要改**：不能写成"通知监听或同步适配器"两个并列候选；应写成"**复活源 + 写回权限**这一对，缺一不可"，并把"用户主动关闭"的判据缺失列为**前置阻断项**（Aerial 至今没有解决，且实际上会覆盖用户选择）。
- **E6 边界需要显式扩写**：不只"关闭开关后不写回"，还要覆盖"用户关掉后**打开应用**不写回"和"任意设置写入触发的后台任务不写回"——Aerial 两条都踩了。

**预算**：14 人日上限**对本轮建议仍成立**，但只覆盖 A1–A5 + 试点 + 一项择优修复；按本稿把 A2/A3 扩成 doctor + 自检（+1.5）、加入 P0(1)(3)(4)（约 3.5–4.5）后，合计约 **11–12 人日仍在 14 以内**，代价是"择优修复"最多只剩一项。**P1(6)(7)(8) 与 guard 变体不在 14 人日内**，它们是另一个 8–15 人日的项目，且要先有 Gordon 对权限与用户控制的决策。**不要把这部分算成"顺手带上"。**

### 8.1 单 APK 决策：结论是"不动，但写清例外条件"

- Aerial 的 `standard/guard` 证明：**HOME 声明只能在安装期生效，运行期无法关闭**（`activity-alias` 同样会被固件按 launcher category 拒装）。所以"装不上声明 HOME 的 APK"这类固件，在单 APK 前提下**无解**。
- 但引入变体的真实代价不是多编一次：① 发布/签名/回退面翻倍（同 `versionCode`、同签名、不同 manifest —— 升级链必须知道电视上装的是哪一个，Aerial 用 `versionNameSuffix="-guard"` + `BuildConfig.FLAVOR` 在更新 URL 里区分）；② 我们的"清单双通道交叉校验 + hash"信任故事要变成"两套清单"；③ `guard` 版永远拿不到 HOME 角色，只能靠守护，于是 P1(6) 的权限链又成了必需品。
- 建议：**保持单 APK**；在 `REVIEW-GUIDE`/兼容表里明确"TCL/OPPO 类固件在引入变体前不支持"，并在提案里预留一段"变体设计草案"（同包名、同版本、双 manifest、发布脚本按 flavor 出两套清单与 hash），**不实现**。

---

## 9. 许可与资源（不是法务结论）

- 代码：Aerial 为 **MIT**（`Copyright (c) 2026 pengchujin`）。UnitedU 是 Apache-2.0；Apache-2.0 项目引入 MIT 代码是允许的，但**保留 MIT 版权与许可声明**是硬要求，且要在 `NOTICE` 里写清来源文件/提交。
- 资源另算：
  - **Inter 字体**：SIL OFL 1.1（`FONT-LICENSE-OFL.txt`），可随包分发，需保留许可。
  - **Material Symbols Rounded 图标路径**：Apache-2.0（作者已注明）。
  - **Simple Icons**（Apple TV / PlayStation 标志）：CC0 的**路径**，但商标权不随 CC0 转移——只用于"标明接的是什么设备"这种指示性使用，风险低但非零。
  - **航拍影片**：仓库里只有 `assets/aerials.json`（地址 + 地名），影片实时从 `sylvan.apple.com` 播，并**打包了 Apple Root CA** 只对该域名信任。这是 Apple 的内容与 CDN，**不因仓库 MIT 而获得任何授权**；把它搬进 UnitedU 是把法律与运维风险一起搬进来。
  - **图标库**：作者用当贝桌面 4.1.7 的"美化图标"（128 个 App）生成到自己站点，**不进版本库**。这是第三方 App 的美术资源二次分发，我们不要复制这条路。
  - **Aerial 这个名字**本身是 Apple 屏保的名字（作者在 `ARCHITECTURE` 里说明包名 `app.orchard.tv` 是"改包名会丢用户数据"的遗留）。我们不需要评价其商标风险，但**不要把 "Aerial" 当成可复用的品牌资产**。
- 结论：**可以借鉴判据、脚本形态、文档结构；不要复制资源与内容管线。** 具体到"哪些文件是 MIT 代码、哪些是资源"，需要逐文件确认后才能写进 `NOTICE`——本轮没做这个逐文件核对。

---

## 10. 与 Codex 初稿的异同

**同意**（我独立复核后成立）：
- 定位判断（完整桌面而非接管工具）、能力对照的主体结论、频道行缺失、机型表是"项目方整理的用户反馈"而非统一复测、安装脚本不止装 APK、还原不是精确快照、Lite 未在真机验证、许可只覆盖代码。

**不同意 / 需修正**：
1. **§3「待机复活」把通知监听与同步适配器当成两个独立候选** → 实际上两者都只是"进程复活"，恢复接管依赖 `WRITE_SECURE_SETTINGS`；单列会让我们误估代价（以为只要加个监听服务）。
2. **"源码不展示一个可靠区分用户关闭与系统移除的通用判据"太温和** → 源码展示的是**主动覆盖**：`MainActivity.onStart` 与同步适配器无条件 `ensureEnabled`。这不是"判据缺失"，是"设计上不允许关闭"。因此不能把 Aerial 列为"用户控制待研究"的中性样本。
3. **遗漏 #774（system_server 看门狗整机重启）** → 这是本轮唯一"会重启用户电视"的已知路径，且 UnitedU 的过滤旗标同构暴露。属于必须进方案的风险项，不是脚注。
4. **遗漏"原厂桌面建模"的具体判据**（负优先级、播放器包、厂商 category、trampoline、固件私有桌面、只认 Activity）→ 这是可复用度最高的部分，初稿只写了"厂商适配是优先研究对象"。
5. **遗漏 Android 12+ 小米"完全不过滤按键"的取舍**（音量连发丢失 vs 闪一下）→ 直接影响我们"永久过滤"的选择是否成立。
6. **"复现并比较具体差异"一节把顺序写反了**：`WRITE_SECURE_SETTINGS`/`GET_USAGE_STATS` 是模拟器**无法**验证的（要真机 + 权限），模拟器能验证的是候选集、判定表、过滤开关逻辑。初稿把"模拟器先验证 HOME 变体解析、事件路径"放在前面可以，但不能暗示模拟器能替代真机对复活链的验证。
7. **预算段结论对但数字偏松**：初稿说"14 人日没有覆盖逐项吸收的可靠依据"，我给出具体拆分（§8）：A1–A5 + P0 约 11–12，P1 另计 8–15。

**初稿没提但我补上的**：`restore.sh` 的六个具体缺口表；`selftest.sh` 作为 A3 的形态参考；`#371` 的"守护本身导致待机不唤醒"对试点设计的影响；Dream 在 `SCREEN_OFF` 自结束这条一行级修复；`OffInStandby`/`keep_adb` 只有 adb 入口这一用户控制缺口。

---

## 11. 未知项

1. `#774` 的机制在 **Android 9/10/11 小米 + 只过滤 HOME** 时是否同样成立——上游按"旗标即路由全部按键"解释，但那台机器上他们同时接管音量；UnitedU 只吃 HOME，暴露窗口更窄，**但风险不为零**，需要一台小米 11 实机 + 手柄复现。
2. 我**没有**验证"设置写入是否在所有固件上都触发 `settings` authority 的同步"（上游断言，我没读 AOSP 对应代码）。这决定"用户关掉守护后被后台写回"的速度，也决定 #72 类问题的恢复延迟。
3. Aerial 的守护在 **Google TV（launcherx / 优先级 2）** 上的实际表现没有任何仓库证据；README 只有索尼 A80J/VH1 的用户报告。我们自己 `CLAUDE.md` 已证明"角色改了 HOME 也不给"，所以这块不能引用 Aerial 的结论。
4. `persist.vt.use_home_pkg` 在非海信固件上写会不会有副作用：Aerial 只在属性存在时写，未做跨品牌验证。
5. 手机页 `PhoneServer`/`ConfigReceiver` 的安全面（明文 HTTP token 泄漏、`ConfigReceiver` 的 `WRITE_SECURE_SETTINGS` 保护是否足够）我只做了静态阅读。
6. `#371` 报告人观察到的"收回 `WRITE_SECURE_SETTINGS` 后守护崩溃"我没能静态定位到未捕获写成点——可能是 `serviceInfo`/`Settings` 之外的路径，属未解释现象。
7. Aerial 的 6.6k star / 252 open issue 与真实装机量/留存的关系未知；把它当"市场验证"是过度解读。

---

## 12. 一句话交付

**Aerial 的价值在机型档案与证据闭环，不在它的权限模型。** 我们应当拿走它的"原厂桌面判据、按条件开关的按键过滤、行为自检与机型数据格式、Dream 息屏自退"这四样零权限/低风险的东西，把它的"写回无障碍 + 复活链 + 厂商属性 + 二创资源"留在方案之外，等 Gordon 对权限与用户控制单独拍板。

---

## 附录 A：本轮用到的关键链接

- 基线：`https://github.com/pengchujin/aerial-tv/tree/f57e46b1b0459b1ced68025538bb85abab955636`（`app/build.gradle.kts` 1.62/76）
- 守护：`app/src/main/java/app/orchard/tv/guard/HomeGuardService.kt`、`GuardSetup.kt`、`RevivalListener.kt`、`RevivalSync.kt`、`BootReceiver.kt`、`CecHome.kt`
- 清单：`app/src/main/AndroidManifest.xml`、`app/src/standard/AndroidManifest.xml`
- 脚本：`tools/install.sh`、`restore.sh`、`report.sh`、`selftest.sh`、`site/agent.md`、`site/adapt.md`
- 文档：`docs/ARCHITECTURE.md`、`docs/HANDOFF.md`、`docs/devices/*.md`（24 篇）
- 代表性 issue：#774（手柄过滤导致看门狗重启）、#371（TCL 待机不唤醒）、#72（升级后绑定失效）、#40（海信 HOME 锁定/开机拦截）、#651（守护"聋"）、#701（FallbackHome）、#704（用量统计救回）、#767（DEFAULT 与候选集变化）、#723（会话安装与受限设置）、#770（MiTV2 当不了桌面）

## 附录 B：我方需要随本稿一起看的文件

`docs/research/2026-10-02-home-key-takeover-prior-art.md`（§0 三条路，本稿的 P0(1)(3) 是它的补强）、`app/src/main/java/com/uniteduone/launcher/HomeKeyPolicy.kt`、`HomeKeyService.kt`、`HomeKeyState.kt`、`UnitedUDream.kt`、`CLAUDE.md`「主页键接管」条。

本稿引用的另外三份材料写在主工作区、本轮之前尚未提交，**不在研究分支里**，需要按绝对路径只读看：

- `/Users/gordonwang/GitHub/UnitedU-launcher/docs/research/2026-10-28-aerial-pi-brief.md`（研究入口）
- `/Users/gordonwang/GitHub/UnitedU-launcher/docs/research/2026-10-27-aerial-tv-unitedu.md`（Codex 初稿，§10 逐条对照它）
- `/Users/gordonwang/GitHub/UnitedU-launcher/docs/research/2026-10-22-cytv-codex-pi-proposal.md`（已签署方案；本稿 §8 建议改它的 §4 与 E6 表述，**原文保留不改**）
