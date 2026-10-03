# 第 3 部分:Android / Android TV 版本与平台兼容性

调研日期 2026-10-02。对象:UnitedU(`com.uniteduone.launcher`,minSdk 28 / targetSdk 35,侧载分发)。本文件只做调研,不改任何仓库文件。

**怎么查的**

- **AOSP 源码直读**:android.googlesource.com 上 `pie-release`、`android10-release` … `android16-release` 各分支的具体文件(下文每条都给到文件级链接)。
- **官方文档**:developer.android.com、source.android.com(CDD)、Google 官方博客 / 帮助中心 / Play 政策页。
- **实测(一手,只读)**:
  1. 国行索尼 A95L:`Sony/BRAVIA_AE1_CN/BRAVIA_AE_M6L:14/UKN4.250422.001`,Android 14 / API 34,安全补丁 2026-05-05。只跑查询类 adb 命令,没按键、没装包、没改设置。
  2. 本机两个 Google 官方 Android 14 电视模拟器镜像,都用 `-read-only` 启动(改动不落盘,跑完即关):`unitedu-gtv` = Google TV(`sdk_google_atv64_amati_arm64 … UTT1.240131.001.F1`),`unitedu-tv` = GMS 版 Android TV(`sdk_google_atv64_arm64 … UTT1.240131.001.F1`)。
  3. 命令与原始输出见附录 A。
- **二手**:媒体 / 社区 / 第三方厂商文档,只用来补官方没写的部分,逐条标注。

**标注规则**:每条证据后写「来源 · 类型 · 把握度」。

- 一手 = AOSP 源码、Google 官方文档 / 政策 / 博客、本项目实测;二手 = 媒体、社区、第三方厂商文档。
- 把握度:高 = 源码或实测直接证实;中 = 官方说法但未实测,或多方二手一致;低 = 单一二手或推断。
- 「推断」两字出现处,表示结论由证据推出、没有直接证据。

**局限**:

- 本会话的 WebSearch 配额中途用尽。2026-09 之后的新闻没覆盖到,Android TV 16 是否开始推送只核到 2026-08-31。
- Android 9–13 没有真机或模拟器实测,只有源码。
- 没在电视上按任何键,因此「系统弹窗长什么样」这类交互没有实测。

---

## 0. 结论速览

1. **能不能当「真·默认桌面」,取决于厂商有没有给原厂桌面更高的 HOME 优先级,跟 Android 版本无关。**
   - 系统解析 HOME 时,只要候选的 intent-filter 优先级不同,就直接取最高者,不看 HOME 角色或用户偏好。
   - 非特权应用(侧载的都是)的优先级会被系统压到 0。
   - Google 两个 Android 14 镜像里,`launcherx`(Google TV)和 `tvlauncher`(Android TV)的 HOME 优先级都是 2。实测把 HOME 角色改成 UnitedU 后,HOME 键仍然回 Google 桌面。
   - 国行 A95L 上所有桌面都是 0,HOME 角色说了算。
2. **AOSP 电视设置(TvSettings)从 9 到 16 都没有「主屏幕应用」页。**
   - `HOME_SETTINGS` 在 TvSettings 里只映射到一个打开即关闭的空壳页。
   - Android 10 起,权限控制器(PermissionController,优先级 2)接走这个 intent,显示手机样式的默认桌面页。三套实测环境都解析到它。
   - CDD 只要求声明了 `android.software.home_screen` 的设备支持换桌面。AOSP 的电视核心特性表里没有这一项;Google TV 镜像和 A95L 实测也都没声明。
3. **后台启动(BAL)限制分四段:**
   - 9:没有限制。
   - 10/11:AOSP 对默认桌面**没有**豁免。
   - 12–15:有豁免,但快速路径只比对「当前 home 进程」的 uid。
   - 16:当前 home 进程属于 system uid 时,改为按默认桌面包名比对。
   - 「显示在其他应用上层」(SAW)豁免在各版本都存在。AOSP 明文把电视排除在「低内存设备禁用 SAW」之外,三套实测环境都有授权页。
4. **屏保**:
   - AOSP / GMS Android TV 的屏保页会列出第三方 DreamService。模拟器实测 UnitedU 在列表里。
   - Google TV 只提供 Ambient(环境模式);第三方屏保只能用 adb 写 `screensaver_components`。
   - `ACTION_DREAM_SETTINGS` 在 AOSP TvSettings 和两个 Google 镜像上都解析不到;索尼自己加了处理者。
5. **TIF(电视输入框架)**:
   - 任何 leanback 设备都有 TvInputManager。输入列表全靠厂商的 TvInputService 和 HAL。
   - 直通(passthrough)URI 要由系统 TV 应用接:AOSP 是 `com.android.tv`,索尼是 `com.sony.dtv.tvlin`。
6. **版本线**:
   - 9 / 10 / 11 / 12 都有消费设备。13 只有开发者版。14 于 2024 年发布。15 直接跳过。
   - 16 于 2025-05 发布说明和模拟器;截至 2026-08-31 没查到任何消费设备推送。
   - 因此 targetSdk 35 那批行为变化(BAL 收紧、edge-to-edge 等)在今天的电视上一台都不生效,要等 Android TV 16 的设备。
7. **安装与库**:
   - 最低可装的 targetSdk 从 Android 14 起是 23,15 起是 24。
   - 开发者验证 2026-09-30 起只对手机 / 平板强制,ADB 安装豁免。
   - 当前依赖 minSdk 21,最新版 23,都低于本项目的 28。
   - APK 里唯一的 .so 已 16 KB 对齐、四个 ABI 齐全。只有 32 位的电视(A95L 只有 `armeabi-v7a`)没有问题。

---

## 1. Android TV / Google TV 版本谱系

| 电视版本 | API | 电视版发布 | 消费设备 | 来源 |
|---|---|---|---|---|
| Android TV 9 | 28 | 电视版发布日期未查到一手来源(平台版本 2018 年) | 有(本调研未逐一核实机型) | — |
| Android TV 10 | 29 | 2019-12-10 随 ADT-3 发布 | 有。例:Chromecast with Google TV 2022 年的固件号 `QTS1.*` 以 Q(10)开头 | [S6] 二手 · 中;[S45] 二手 · 中 |
| Android TV 11 | 30 | 2020-09-22 发布给 ADT-3 | 有(未逐一核实) | [S6] 二手 · 中 |
| Android TV 12 | 31 | 2021-11-30 发布给 ADT-3,随后推给厂商 | 有。A95L 的 `ro.product.first_api_level=31`,即出厂 12 | [S4] 一手 · 高;[S60] 实测 · 高 |
| Android TV 13 | 33 | 2022-12 起向开发者发布 | **无**:官方写明只是开发者版,不会推到消费设备 | [S3] 一手 · 高 |
| Android TV 14 | 34 | 2024-05-15 官方宣布;Google TV Streamer 是首台(2024-09) | 有:Google TV Streamer、Chromecast with Google TV、A95L(实测 14) | [S2][S5] 一手 · 高;[S12] 二手 · 中;[S60] |
| Android TV 15 | 35 | **跳过**:Google I/O 2025 议程直接写 Android 16 for TV | 无 | [S7] 二手 · 中;[S1] 一手 · 高 |
| Android TV 16 | 36 | 2025-05-20 发布说明 + 电视模拟器 | **未查到**:2026-05-26 报道称 Google TV 仍未推送 16;Streamer 2026-06 的更新号 `UTTK.260317.003`(U 前缀推断仍是 14);至 2026-08-31 的 Streamer 新闻里也没有 16 | [S1] 一手 · 高;[S9][S10][S11] 二手 · 中 |
| (Android 17) | 37 | 手机版已发布;AOSP 电视设备仓库 `device/google/atv` 已有 `android17-release` 分支 | 电视版未见任何消息 | [S22] 一手(分支列表)· 高;[S59] 一手 · 高;电视版状态未查到 |

**各版本对第三方桌面有影响的变化**(详细证据见后面各节):

- **9**:
  - 没有 RoleManager,也没有后台启动限制。
  - TvSettings 已有三样东西:屏保选择页、「显示在其他应用上层」页、按应用授权的「未知来源」页。
  - 但 `ACTION_MANAGE_OVERLAY_PERMISSION` 没有处理者。
- **10**:
  - 加了 HOME 角色(RoleManager)。
  - `HOME_SETTINGS` 改由权限控制器处理(当时还在 PackageInstaller 里)。
  - 开始限制后台启动,AOSP 对默认桌面**没有**豁免。
  - Go(低内存)手机禁用 SAW,电视不在此列。
- **11**:
  - 包可见性开始生效(`QUERY_ALL_PACKAGES`)。
  - TvSettings 开始处理 `ACTION_MANAGE_OVERLAY_PERMISSION`。
- **12**:
  - AOSP 后台启动加「Home app」豁免。
  - UI 层支持 4K;加入麦克风 / 摄像头指示。
- **13**:无消费设备。
- **14**:
  - 最低可装 targetSdk 提到 23。
  - targetSdk 34 起,发送 PendingIntent 的一方要显式授出后台启动权限。
  - 部分机型支持画中画;新增能耗模式。
- **15**:电视跳过。其平台改动随 Android TV 16 一起到来:最低 targetSdk 24、targetSdk 35 的 BAL 收紧、16 KB 页、后台网络限制。
- **16**:
  - 官方说明只列了三项:MediaQualityManager、Eclipsa 音频、HDMI-CEC 与 64 位内核优化。
  - 没有任何关于桌面的限制说明。

---

## 2. 默认桌面:怎么设,HOME 键归谁

### 结论

1. **Android 9**:
   - 没有 HOME 角色,也没有设置入口:AOSP TvSettings 把 `HOME_SETTINGS` 映射到空壳页。
   - 能用的只有两条路:
     - 按 HOME 时系统弹出的选择框(多个桌面优先级相同、又没有偏好时才会出现),选「始终」;
     - 用 adb 跑 `cmd package set-home-activity`(Android 7 起就有,在 9 上写的是首选 Activity)。
2. **Android 10+**:
   - 有 HOME 角色。`HOME_SETTINGS` 进的是权限控制器的默认桌面页(手机样式界面,遥控器能用)。
   - `cmd package set-home-activity` 在 10+ 的实现就是「把 HOME 角色给某个包」。
   - `RoleManager.createRequestRoleIntent(ROLE_HOME)` 的处理者(RequestRoleActivity)在三套实测环境里都存在。没有点按实测弹窗。
3. **HOME 键实际去哪,先看优先级,再看角色**:
   - 系统把 HOME intent 当普通隐式 intent 解析。前两名优先级不同时直接取第一名,不查偏好、不查角色。
   - 非特权应用声明的优先级大于 0 时一律被压成 0。
   - 所以只要系统里有一个特权桌面声明了大于 0 的 HOME 优先级,第三方桌面在任何版本上都拿不到 HOME 键。
4. **Google TV 与 GMS 版 Android TV**(Android 14 镜像实测):
   - `launcherx` 与 `tvlauncher` 的 HOME 优先级都是 2。
   - `set-home-activity` 返回 Success,HOME 角色也确实改成了 UnitedU,但 HOME 解析结果和按 HOME 键的结果都还是 Google 桌面。
   - Google 没有公开说过「禁止第三方桌面」(未查到)。效果上等于禁止,只能:
     - 用 adb 停用 Google 桌面(实机还要停用 `setupwraith`);
     - 或用无障碍服务拦截 HOME 键(Projectivy 的做法)。2026-03 有一次 Google TV 更新让部分用户的拦截失效。
5. **国行无 GMS(A95L 实测)**:所有桌面的 HOME 优先级都是 0,HOME 角色就是 HOME 键的去向;`HOME_SETTINGS` 解析到权限控制器页。
6. **Android 14 以后**:
   - 官方电视版说明里没有任何桌面相关变化。
   - Android 16 源码里「优先级先决」的解析规则原样保留。
   - 唯一和桌面有关的改动在后台启动豁免(见第 3 节)。
   - 各厂商自己的 Google TV 机型没有实测,推断与 Google 镜像相同(同一个 `launcherx` 包)。

### 证据

**2.1 HOME 解析规则:优先级先决**

- `chooseBestActivity` 有一条硬规则:只要前两名的优先级、preferredOrder 或 isDefault 不同,就直接返回第一名,不去查已保存的偏好。
  - 该规则在 Android 9 的 `PackageManagerService.java`、Android 14 和 16 的 `ResolveIntentHelper.java` 里逐字相同。
  - 来源:[S14](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/ResolveIntentHelper.java) · 一手 · 高
- 非特权应用的 intent-filter 优先级被强制压到 0;只有特权应用、以及开机向导,才能声明大于 0。
  - 来源:[S15](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/resolution/ComponentResolver.java)(`adjustPriority`)· 一手 · 高
- Android 10+ 的设计前提是 HOME 角色永远有持有者。源码注释写着「不应该发生」;万一没有,就退回「优先级最高且唯一的那个 HOME」。
  - 来源:[S16](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/ComputerEngine.java)(`getDefaultHomeActivity`)· 一手 · 高
- 新装一个 HOME 候选后,原来的「始终」偏好会因为候选集合变化被丢弃,改记成「上次选择」,下次重新询问用户。这时如果多个候选优先级相同,按 HOME 就会弹选择框。
  - 用户在选择框里选定后,系统会把结果同步到 HOME 角色(`updateDefaultHomeNotLocked` → `setActiveLauncherPackage`)。
  - 来源:[S16](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/ComputerEngine.java)、[S17](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/PreferredActivityHelper.java) · 一手 · 高。弹框是否出现没有在电视上实测。
- AOSP 电视开机向导 `TvProvision` 的 HOME 优先级是 3,配完会把自己关掉。Google TV 实机上与之对应的是 `setupwraith`(闭源)。
  - 来源:[S22](https://android.googlesource.com/device/google/atv/+/refs/heads/android14-release/TvProvision/AndroidManifest.xml) · 一手 · 高;`setupwraith` 的行为见 2.4 · 二手

**2.2 `cmd package set-home-activity` 的演变**

| 版本 | 实现 | 来源 |
|---|---|---|
| 7.0(nougat) | 首次出现 | [S13](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/nougat-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java) · 一手 · 高 |
| 9 | `mInterface.setHomeActivity(component, userId)`,写 HOME 首选 Activity | [S13](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java) · 一手 · 高 |
| 10 / 14 / 16 | 只取包名,调 `RoleManager.addRoleHolderAsUser(ROLE_HOME, …)`;`--user` 缺省为 `USER_SYSTEM` | [S13](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java) · 一手 · 高 |

**2.3 电视设置里有没有「主屏幕应用」**

- **AOSP TvSettings(pie → android16)**:
  - `HOME_SETTINGS` 与 `MANAGE_DEFAULT_APPS_SETTINGS` 都挂在 `.EmptyStubActivity` 上,优先级 1。清单注释说明这是给 CTS 的占位过滤器。
  - 这个 Activity 的 `onCreate` 里只有一句 `finish()`。
  - 「应用」页的条目只有:最近使用、全部应用、未使用应用、权限、特殊应用权限、安全与限制。没有默认应用 / 主屏幕应用。
  - 来源:[S18](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android16-release/Settings/AndroidManifest.xml)、[S18b](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android16-release/Settings/src/com/android/tv/settings/EmptyStubActivity.java)、[S19](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android16-release/Settings/res/xml/apps.xml) · 一手 · 高
- **Android 10+ 的权限控制器**:
  - `HomeSettingsActivity` 以优先级 2 接 `HOME_SETTINGS`,`DefaultAppListActivity` 以优先级 2 接 `MANAGE_DEFAULT_APPS_SETTINGS`。两者都高于 TvSettings 的 1,所以会胜出。
  - `HomeSettingsActivity` 转到 `DefaultAppActivity`;后者只区分车机与其他设备,电视用的是手机版界面(`HandheldDefaultAppFragment`)。
  - 页面是否显示取决于 `config_showDefaultHome`:AOSP 默认是 true,`device/google/atv` 没有覆盖它。
  - Android 10 时这些类在 `packages/apps/PackageInstaller` 里,11 起移到 `packages/modules/Permission`,优先级同样是 2。
  - 来源:[S20](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android14-release/PermissionController/AndroidManifest.xml)、[S20b](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android14-release/PermissionController/src/com/android/permissioncontroller/role/ui/DefaultAppActivity.java)、[S20c](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android14-release/PermissionController/src/com/android/permissioncontroller/role/ui/behavior/HomeRoleUiBehavior.java)、[S21](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/res/res/values/config.xml)、[S20d](https://android.googlesource.com/platform/packages/apps/PackageInstaller/+/refs/heads/android10-release/AndroidManifest.xml) · 一手 · 高
- **实测**:A95L、Google TV 镜像、Android TV 镜像三处的 `HOME_SETTINGS` 都解析到权限控制器的 `HomeSettingsActivity`(优先级 2),TvSettings 空壳排第二。
  - 项目早先在 Android TV 镜像上看到过:系统页里已勾选 UnitedU,HOME 却仍然解析到 Android TV Home(`docs/WORKLOG.md:530`)。
  - 来源:[S60] 实测 · 高;[S61] 项目记录 · 高
- **CDD(Android 14)**:
  - 允许第三方替换桌面的设备,**必须**声明 `android.software.home_screen`。
  - 声明了 `home_screen` 的设备,**必须**响应 `HOME_SETTINGS`。
  - 对没声明的设备,这两条都不适用。
  - 来源:[S24](https://source.android.com/docs/compatibility/14/android-14-cdd)(3.2.3.5、Launcher 一节)· 一手 · 高
- **AOSP 特性表**:手机的 `handheld_core_hardware.xml` 声明了 `home_screen`;电视的 `tv_core_hardware.xml` 没有。
  - 实测:Google TV 镜像的 60 个特性里没有 `home_screen`;A95L 也没有,但它实际允许换桌面。
  - 来源:[S23](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android14-release/data/etc/handheld_core_hardware.xml)、[S22](https://android.googlesource.com/device/google/atv/+/refs/heads/android14-release/permissions/tv_core_hardware.xml) · 一手 · 高;[S60] 实测 · 高

**2.4 Google TV / GMS Android TV 是否限制第三方桌面**

- **实测(`unitedu-gtv`,Google TV,Android 14 镜像)**:
  - HOME 候选:`launcherx/.home.HomeActivity` 优先级 2;`com.uniteduone.launcher` 优先级 0;TvSettings 的 `FallbackHome` 优先级 -1000。
  - 执行 `set-home-activity com.uniteduone.launcher/.MainActivity`:返回 Success,HOME 角色变成 `com.uniteduone.launcher`。
  - 但 `resolve-activity` HOME 的结果仍是 `launcherx`,按 HOME 键后前台也是 `launcherx`。
  - 这个镜像里没有 `setupwraith`,说明光靠 `launcherx` 自己的优先级 2 就够了。
  - 来源:[S60] 实测 · 高
- **实测(`unitedu-tv`,GMS 版 Android TV,Android 14 镜像)**:`tvlauncher/.MainActivity` 优先级 2;HOME 角色已经是 UnitedU,HOME 仍解析到 `tvlauncher`。
  - 来源:[S60] 实测 · 高
- **推断**:厂商的 Google TV 机型(索尼、TCL、海信等)预装的是同一个 `launcherx`,预计同样是优先级 2。没有在这些机型上实测。
  - 把握度:中
- **社区实测**:
  - Chromecast with Google TV 上要用 adb `pm disable-user` 同时停用 `launcherx` 和 `setupwraith`,之后按 HOME 才会弹选择框;作者称网上有些旧方法在 Android 14 上已失效。来源:[S25](https://www.celsoazevedo.com/replace-launcher-chromecast-google-tv/)(2026-01-27)· 二手 · 中
  - 无障碍拦截 HOME 键(Projectivy「覆盖当前启动器」)在 2026-03 的一次 Google TV 更新后对部分用户失效,原因不明(是故意还是 bug 不清楚)。来源:[S26](https://www.androidauthority.com/google-tv-update-blocks-projectivy-override-3649445/)(2026-03-16)· 二手 · 中
  - 指南提到 Google 有把设备推回默认 Google TV 桌面的历史。来源:[S27](https://www.flatpanelshd.com/guide.php?subaction=showfull&id=1754640308)(2025-08-08)· 二手 · 低
- **官方表态**:没有查到 Google 明文「Google TV 不允许第三方桌面」。结合 CDD 的写法(换桌面是可选能力)和优先级机制,实际效果就是不允许。把握度:中

**2.5 国行 A95L(Android 14,无 GMS)**

- HOME 候选:当贝 `com.dangbei.TVHomeLauncher`、Projectivy `com.spocky.projengmenu`、`com.uniteduone.launcher` 三个都是优先级 0;TvSettings `FallbackHome` 是 -1000。
- HOME 角色 = `com.uniteduone.launcher`;HOME 解析结果也是它。
- `HOME_SETTINGS` 解析到权限控制器;`REQUEST_ROLE` 解析到权限控制器的 `RequestRoleActivity`。
- 来源:[S60] 实测 · 高

**2.6 与项目现有笔记的关系**

CLAUDE.md 写的是:「`set-home-activity` 带 `--user 0` 会返回 Success 却不生效」。

本次实测不带 `--user` 也一样:角色确实改了,HOME 不认。根因是 Google 桌面的 HOME 优先级为 2,和 `--user` 参数无关。这个结论只适用于 GMS 镜像;在国行优先级为 0 的设备上,`set-home-activity` 会生效。

---

## 3. 后台启动限制与「显示在其他应用上层」

### 结论

1. **默认桌面是否豁免后台启动,分版本看**:
   - **9**:没有后台启动限制。
   - **10 / 11**:AOSP 对默认桌面没有任何豁免。
     - 实际可用的豁免:SAW 获授权、可见窗口、10 秒宽限期、被前台应用绑定、配套设备应用、设备所有者,以及 recents 组件(电视上不是我们)。
   - **12–15**:AOSP 加了「Home app」豁免,判断用 `isHomeApp(uid, pkg)`。
     - 快速路径:只要 `mHomeProcess` 不为空,就只比较调用方 uid 和 `mHomeProcess` 的 uid。
     - `mHomeProcess` 为空时,才比较包名和默认桌面。
   - **16**:如果 `mHomeProcess` 属于 system uid、而且调用方带了包名,就跳过快速路径,改为比较默认桌面包名。
   - 官方文档也把「由设备启动器发起的启动」列为豁免情形之一。
2. **对 A95L「默认桌面不被豁免」的解释(推断,未取证)**:
   - 更新安装会先杀掉 UnitedU 进程。新进程在 `MY_PACKAGE_REPLACED` 里发 HOME 时,`mHomeProcess` 很可能不是 UnitedU。
     - 例如系统先拉起了 TvSettings 的 `FallbackHome`(system uid),或者另一个桌面。
   - 这时 12–15 的快速路径直接判否,与项目实测「索尼需要 SAW 才放行」相符。
   - Android 16 只修了「`mHomeProcess` 是 system uid」这一种情况。
   - 也不排除索尼改了框架。要确认,需要在更新瞬间抓 `ActivityTaskManager` 的 BAL 日志看 `mHomeProcess`。
3. **SAW 豁免各版本一直都在**(10 → 16 源码都有)。
   - BAL 只认 appop 状态:`appops set <包> SYSTEM_ALERT_WINDOW allow` 即可;appop 为 default 时才回退检查权限本身。
   - 在 Android 16 里,SAW 豁免仍然允许新开任务(`FLAG_ACTIVITY_NEW_TASK`)的启动。
4. **电视上的 SAW 授权界面**:
   - **AOSP TvSettings 9 → 16**:都有「应用 → 特殊应用权限 → 显示在其他应用上层」。
   - **`ACTION_MANAGE_OVERLAY_PERMISSION` 直达**:TvSettings 从 Android 11 起才处理这个 intent。在 AOSP 9/10 上会抛 ActivityNotFoundException,除非厂商自己补了处理者。
   - **实测**:A95L、Google TV 镜像、Android TV 镜像都解析到 TvSettings 的 `SystemAlertActivity`。
   - **受限资料**(restricted profile)下,这个页面会直接关闭。
   - **低内存设备**:AOSP 在 Go / 低内存设备上默认禁用 SAW,但源码里明确排除了 leanback 设备(注释大意是电视长期插电,对内存和功耗的顾虑较小),所以低内存电视仍可授权。
5. **targetSdk 35 下的坑**:
   - **改动何时在电视上生效**:Android 15 的 targetSdk 门控改动,只在 API ≥ 35 的设备上生效,电视要等 Android TV 16。这批改动包括:
     - PendingIntent 创建方默认不授出 BAL;
     - 发送方不允许时不把任务带到前台;
     - 不可见窗口不再算作 BAL 理由;
     - 持有 SAW 启动前台服务,必须有可见的悬浮窗。
   - **UnitedU 不受这些规则影响**:它是从 BroadcastReceiver 直接 `startActivity`,不经过 PendingIntent,也没有前台服务。
   - **targetSdk 34 起**:发送 PendingIntent、或 `bindService` 时要显式授出 BAL 权限。UnitedU 不用这两种方式。
   - **被 BAL 拦下时不报错**:`startActivity` 不抛异常,只在系统日志里记一笔(项目已实测,见 `RelaunchAfterUpdate.kt` 的注释)。所以不能拿「没有抛异常」当成功。

### 证据

- **Android 9 没有 BAL 检查**:`pie-release` 的 `ActivityStarter.java` 里没有 `shouldAbortBackgroundActivityStart`;Android 10 才有。
  - 来源:[S29](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/wm/ActivityStarter.java) · 一手 · 高
- **Android 10 豁免清单**:重要 uid、可见窗口 / 持久进程、`START_ACTIVITIES_FROM_BACKGROUND`、recents 组件、设备所有者、配套设备应用、进程自身被允许(宽限期 / 可见任务 / 被前台 uid 绑定)、SAW。没有 home 分支。
  - Android 11 的 `WindowProcessController.areBackgroundActivityStartsAllowed` 同样没有 home 分支。
  - 来源:[S29]、[S31](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/wm/WindowProcessController.java) · 一手 · 高
- **Android 12 / 13 加了 home 豁免**:`ActivityStarter.isHomeApp` 带「Fast check」,注释为「Always allow home application to start activities」。
  - 来源:[S29](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/wm/ActivityStarter.java) · 一手 · 高
- **Android 14 / 15 移到 `BackgroundActivityStartController`**,逻辑相同。
  - **Android 16** 的 `isHomeApp` 增加了「快速路径跳过 system uid」的条件;「Home app」豁免与 SAW 豁免,都在 `checkActivityAllowedToStart` 的「新任务」放行名单里。
  - 检查 12–16 各 release 分支,只有 android16 有这个条件。
  - 来源:[S30](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/BackgroundActivityStartController.java) · 一手 · 高
- **官方文档**:后台启动的豁免列表包括「SAW 已由用户授予」,也包括「由设备启动器发起的启动」。另写明 14 要求 PendingIntent 发送方显式授出、15 要求创建方显式授出。
  - 来源:[S28](https://developer.android.com/guide/components/activities/background-starts) · 一手 · 高
- **BAL 只看 appop**:`hasSystemAlertWindowPermission` 读 `OP_SYSTEM_ALERT_WINDOW` 的 appop 状态,为 default 时再检查权限。
  - 来源:[S32](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/wm/ActivityTaskManagerService.java) · 一手 · 高
- **低内存与电视**:`AppOpsManager.getSystemAlertWindowDefault()` 在低内存设备上返回 `MODE_IGNORED`,条件是 `!hasSystemFeature(FEATURE_LEANBACK)`。
  - 官方 Android 10 文档:Go 设备拿不到 SAW,`canDrawOverlays()` 恒为 false。
  - AOSP 的电视低内存配置会设 `ro.config.low_ram=true`,所以这条电视例外是实际会用到的。A95L 实测没有设置 low_ram。
  - 来源:[S33](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/app/AppOpsManager.java)、[S34](https://developer.android.com/about/versions/10/behavior-changes-all)、[S22b](https://android.googlesource.com/device/google/atv/+/refs/heads/android14-release/products/atv_lowram_defaults.mk) · 一手 · 高
- **TvSettings 的 SAW 页**:
  - `special_app_access.xml` 在 pie / 10 / 11 / 14 / 16 都有 `system_alert_window` 一项。
  - `SystemAlertWindow` 用开关直接设 appop。
  - 清单从 android11 起才出现 `MANAGE_OVERLAY_PERMISSION` 的过滤器;`SystemAlertActivity` 在受限资料下直接 `finish()`。
  - 来源:[S19b](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android14-release/Settings/res/xml/special_app_access.xml)、[S35](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android14-release/Settings/src/com/android/tv/settings/device/apps/specialaccess/SystemAlertWindow.java)、[S18](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android11-release/Settings/AndroidManifest.xml) · 一手 · 高
- **实测**:三套环境的 `ACTION_MANAGE_OVERLAY_PERMISSION` 都解析到 `com.android.tv.settings/.device.apps.specialaccess.SystemAlertActivity`。来源:[S60] · 高
- **Google TV 零售机**:数字标牌厂商文档给出的路径是「设置 → 应用 → 特殊应用权限 → 显示在其他应用上层」,并说部分 Chromecast with Google TV 固件版本曾无法自启。
  - 来源:[S36](https://playsignage.com/setup/google-tv-digital-signage/) · 二手 · 中
- **targetSdk 门控**:
  - 来源:[S37](https://developer.android.com/about/versions/14/behavior-changes-14)(14)、[S38](https://developer.android.com/about/versions/15/behavior-changes-15)(15 的 BAL 收紧、SAW 与前台服务)· 一手 · 高
- **A95L「默认桌面不被豁免」是项目已有实测**:
  - 没有 SAW 时,`RelaunchAfterUpdate` 跳过拉回;电视上有 `SystemAlertActivity`。
  - 来源:`docs/WORKLOG.md:1755`、CLAUDE.md「真机装包的一个副作用」[S61] · 一手(项目)· 高。根因属于推断,见结论第 2 条。

---

## 4. 应用内安装更新

### 结论

1. **「安装未知应用」(未知来源)开关**:
   - **AOSP TvSettings 9 → 16**:路径是「应用 → 安全与限制 → 未知来源」,按应用逐个授权。
   - **`MANAGE_UNKNOWN_APP_SOURCES` 直达**:TvSettings 从 pie 起就处理这个 intent(`ExternalSourcesActivity`)。
   - **实测**:A95L、Google TV 镜像、Android TV 镜像都解析得到。
   - **权限性质**:`REQUEST_INSTALL_PACKAGES` 的保护级别是 `signature|appop`,只能由用户开启。
2. **最低可装 targetSdk**:
   - Android 14 起为 23,15 起为 24(低于这个值会报 `INSTALL_FAILED_DEPRECATED_SDK_VERSION`;升级前已装的应用不受影响)。
   - Android 16 的「所有应用」行为变更里,没有看到再提高。
   - UnitedU 的 targetSdk 是 35,不受影响。
   - 电视上 24 这一档,要等 Android TV 16 才生效。
3. **开发者验证(2026)**:
   - 2026-09-30 起先在巴西、印尼、新加坡、泰国执行。
   - 官方 FAQ 写明,在 Google Play 之外分发的应用,目前**只在手机和平板**上强制。
   - ADB 安装不需要验证。
   - 只适用于「已认证的 Android 设备」(Android 7+)。推断:国行无 GMS 电视不在认证体系内,不受影响。把握度:中。
   - 高级流程(开发者模式 → 重启 → 24 小时等待 → 确认)只是手机上的描述,电视上是否有同样的流程未查到。
4. **Google TV 上是否要先开开发者选项才看得到「未知来源」**:
   - 有社区指南这么说,但没有一手来源,镜像也没法验证(模拟器默认开着开发者选项)。
   - 结论:未查到一手证据。

### 证据

- **TvSettings 的未知来源页**:`security.xml`(pie 与 android16)里有 `unknown_sources` → `ExternalSources`;清单从 pie 起就有 `MANAGE_UNKNOWN_APP_SOURCES` 的过滤器。
  - 来源:[S19c](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android16-release/Settings/res/xml/security.xml)、[S18](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/pie-release/Settings/AndroidManifest.xml) · 一手 · 高
- **权限保护级别**:`REQUEST_INSTALL_PACKAGES` = `signature|appop`;`REQUEST_DELETE_PACKAGES` = `normal`;`QUERY_ALL_PACKAGES` = `normal`;SAW = `signature|setup|appop|installer|pre23|development`。
  - 来源:[S39](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/res/AndroidManifest.xml) · 一手 · 高
- **最低可装 targetSdk**:
  - 14 → 23:[S40](https://developer.android.com/about/versions/14/behavior-changes-all) · 一手 · 高
  - 15 → 24,另有 16 KB 页与后台网络限制:[S41](https://developer.android.com/about/versions/15/behavior-changes-all) · 一手 · 高
  - 16 的「所有应用」清单里没有这一项:[S57](https://developer.android.com/about/versions/16/behavior-changes-all) · 一手 · 中(依据是「没看到」)
- **开发者验证**:时间表与「已认证设备、Android 7+」的范围见 [S42](https://developer.android.com/developer-verification);「只在手机和平板上强制」「ADB 不需要验证」「目前直接侧载不受影响」见 [S43](https://developer.android.com/developer-verification/guides/faq)(页面更新于 2026-09-30)· 一手 · 高
- **实测**:三套环境的 `MANAGE_UNKNOWN_APP_SOURCES` 都解析到 `ExternalSourcesActivity`。来源:[S60] · 高

---

## 5. 屏保(DreamService)

### 结论

1. **AOSP / GMS 版 Android TV**:
   - TvSettings 的「屏幕保护程序」页把所有 DreamService 列成单选,包括第三方的。
   - **实测**:Android TV 14 镜像的列表是 Backdrop、Colors、关闭屏幕、**UnitedU**。
   - **`ACTION_DREAM_SETTINGS`**:AOSP TvSettings 没有声明它的处理者(`DaydreamActivity` 是 exported 的,但没有 intent-filter),只能用显式组件打开。
   - **CDD 的要求**:只是「应当」(SHOULD)响应 `DREAM_SETTINGS`,不是「必须」。
2. **Google TV**:
   - 设置里只有 Ambient(环境模式),选项是 Google 相册、艺术画廊、AI 艺术。官方帮助页完全没提第三方屏保。
   - **实测(Google TV 镜像)**:
     - `DREAM_SETTINGS` 解析不到;
     - 屏保页只剩 `com.google.android.tv.settings.ambient` → `AmbientActivity`;
     - 默认组件是 `com.google.android.apps.tv.dreamx/.service.Backdrop`;
     - 系统里能查到 UnitedUDream 服务,但界面上选不了。
   - **社区资料**:
     - 2022-07 的 Chromecast with Google TV 更新 `QTS1.220504.008`(当时基于 Android 10)去掉了第三方屏保选项;
     - 另一来源说「从 Android TV 12 起所有 Google TV 设备都锁了」,覆盖 Streamer 和索尼、TCL、飞利浦的 Google TV 机型。
     - 两个来源对起始版本的说法不一致,但都说明这是 Google TV 层面的限制,和 Android 版本号无关。
   - **绕过办法**:用 adb 执行 `settings put secure screensaver_components <组件>`。这是社区方法,把握度中。
3. **国行 A95L**:
   - `DREAM_SETTINGS` 解析到 TvSettings 的 `DaydreamActivity`(索尼加的过滤器,优先级 0)。
   - UnitedUDream 出现在系统屏保列表里(项目 2026-09-16 的 spike)。
4. **Android TV 14 / 16 对屏保有没有改动**:两份官方发布说明都没提屏保;14 只提了能耗模式。未查到改动。

### 证据

- **TvSettings 的屏保页**:`daydream.xml` 里有 `activeDream` 列表;清单里的 `DaydreamActivity` 没有 intent-filter;pie / android14 清单都不含 `DREAM_SETTINGS`。
  - 来源:[S19d](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android16-release/Settings/res/xml/daydream.xml)、[S18](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android14-release/Settings/AndroidManifest.xml) · 一手 · 高
- **CDD 关于屏保设置**:「SHOULD … provide a settings option … in response to the android.settings.DREAM_SETTINGS intent」。
  - 来源:[S24](https://source.android.com/docs/compatibility/14/android-14-cdd) · 一手 · 高
- **实测**:两个镜像与 A95L 的解析结果、Android TV 镜像屏保列表的 UI dump、Google TV 镜像的 `screensaver_components`,见附录 A。来源:[S60] · 高
- **Google TV 官方帮助**:只列了 Google 相册、艺术画廊、自定义 AI 艺术三项。
  - 来源:[S44](https://support.google.com/googletv/answer/10070821?hl=en) · 一手 · 高
- **第三方屏保被移除**:[S45](https://www.howtogeek.com/125089/chromecast-with-google-tv-loses-third-party-screensavers/)(2022-08-08)· 二手 · 中;[S46](https://prateek11rai.github.io/sanji/blog/2026/05/17/freeing-the-screensaver-4k-anime-art-on-google-tv/)(2026-05-17,含 adb 方法)· 二手 · 中
- **项目内记录**:Google TV 镜像上 `DaydreamActivity` 一打开就自己关掉、`AmbientActivity` 崩溃(镜像缺 dreamx),见 `app/src/main/java/com/uniteduone/launcher/SystemStatus.kt` 中 `DREAM_SETTINGS_PAGES` 的注释;A95L 的系统屏保列表里有 UnitedU,见清单注释与 `docs/WORKLOG.md:776`。来源:[S61] · 高

---

## 6. TIF:输入源枚举与切换

### 结论

1. **服务是否存在**:`SystemServer` 在设备具备 `FEATURE_LIVE_TV` **或** `FEATURE_LEANBACK` 时启动 `TvInputManagerService`(9 / 14 / 16 都一样)。
   - UnitedU 要求 leanback,所以 `getSystemService(TV_INPUT_SERVICE)` 在目标设备上不为 null。
   - 如果服务不存在,注册表抓住 `ServiceNotFoundException`,返回 null。
2. **没有调谐器、或厂商没实现 HAL 时**:
   - `getTvInputList()` 返回空列表,不是 null。它只收集已绑定的 TvInputService。
   - HDMI 这类硬件输入,要厂商提供一个基于 TV Input HAL 的 TvInputService 才会出现。没有 HAL 时 `TvInputHal.init()` 拿不到句柄(`mPtr == 0`),所有流操作都返回 `ERROR_NO_INIT`。
   - **实测**:A95L 有 7 路输入(联发科 `com.mediatek.external/.HdmiInputService` 的 HW2–HW5 等 HDMI、`com.mediatek.tis` 的模拟输入、索尼 DVB 调谐器);Google TV 镜像 0 路。项目早先在模拟器上也记过 inputMap 为空。
3. **直通 URI 必须由「系统 TV 应用」处理**:
   - `buildChannelUriForPassthroughInput` 生成的是 `content://android.media.tv/passthrough/<inputId>`。
   - `startActivity(ACTION_VIEW, uri)` 时系统先问 TvProvider 要 MIME 类型,得到 `vnd.android.cursor.item/channel`,再按这个类型找 Activity。
   - AOSP 里接这个类型的是 Live TV(`com.android.tv` 的 MainActivity);索尼是 `com.sony.dtv.tvlin/.view.MainActivity`(实测)。
   - 没有这类应用的设备(比如没有直播应用的电视盒)会抛 ActivityNotFoundException。没有 TvProvider 时类型解析不出来,也匹配不上。
   - 项目在模拟器上的经验与此一致:要另装一个接 `ACTION_VIEW` + `content://android.media.tv` 的 Activity,才能测切换。

### 证据

- `SystemServer.java`(pie / android14 / android16)的启动条件:[S47](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/java/com/android/server/SystemServer.java) · 一手 · 高
- `SystemServiceRegistry`(`getServiceOrThrow`,抛 `ServiceNotFoundException`):[S48](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/app/SystemServiceRegistry.java) · 一手 · 高
- `TvInputManagerService.getTvInputList` 返回新建的 `ArrayList`;`TvInputHal.init` / `ERROR_NO_INIT`:[S49](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/tv/TvInputManagerService.java)、[S49b](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/tv/TvInputHal.java) · 一手 · 高
- `TvContract.buildChannelUriForPassthroughInput` 的文档写着「用于直通输入(如 HDMI)」;TvProvider 的 `getType` 对 `MATCH_PASSTHROUGH_ID` 返回 `Channels.CONTENT_ITEM_TYPE`:[S50](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/media/java/android/media/tv/TvContract.java)、[S50b](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android14-release/src/com/android/providers/tv/TvProvider.java) · 一手 · 高
- AOSP Live TV 的清单:`VIEW` + `vnd.android.cursor.item/channel`:[S51](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/android14-release/AndroidManifest.xml) · 一手 · 高
- **实测**:
  - A95L:`content gettype` 返回 `vnd.android.cursor.item/channel`;带类型查询时直通 intent 解析到 `com.sony.dtv.tvlin/.view.MainActivity`;`dumpsys tv_input` 有 7 个 `TvInputInfo`。
  - Google TV 镜像:0 个输入,直通 intent 解析到 `com.android.tv/.MainActivity`。
  - 来源:[S60] · 高
- Android 13 for TV 新增了交互电视框架(作为 TIF 的扩展),Android 16 新增了 MediaQualityManager。两者都和「枚举 + 切换」无关。来源:[S3]、[S1] · 一手 · 高

---

## 7. QUERY_ALL_PACKAGES 与包可见性

### 结论

1. **系统行为**:
   - `QUERY_ALL_PACKAGES` 的保护级别是 `normal`,侧载安装时自动授予,用户看不到、也不能撤销。
   - 包可见性过滤只在「设备 Android 11+ 且应用 targetSdk ≥ 30」时生效。UnitedU(targetSdk 35)在 11+ 上靠它看到全部应用;在 9 / 10 上本来就不过滤。
2. **Play 政策**(与系统行为无关,只管 Play 上架):
   - 需要提交声明表,仅限确有必要的用途。政策页列出的用途是设备搜索、杀毒、文件管理、浏览器;启动器没有被点名。
   - 侧载分发不受这条政策约束。
3. **更省的替代**:在 `<queries>` 里声明 `MAIN` + `LEANBACK_LAUNCHER` / `LAUNCHER` 的 intent,就能看到可启动的应用。
   - 是否值得换,取决于 UnitedU 还要不要看那些不可启动的包。比如卸载清理、输入源相关的包;不过 TIF 的数据走系统服务,不受包可见性影响。这一点是推断。
4. **厂商私有的「读应用列表」权限**:未查到电视厂商(小米、海信、TCL 等)有这类权限。手机上有先例,电视上没有资料。

### 证据

- 保护级别:[S39](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/res/AndroidManifest.xml) · 一手 · 高
- 「可见性规则只在 targetSdk ≥ 30 时生效」,以及 `QUERY_ALL_PACKAGES` 只用于少数场景的说明:[S52](https://developer.android.com/training/package-visibility/declaring) · 一手 · 高
- Play 政策(声明表、允许用途、「只在 targetSdk 30+ 且设备 11+ 时生效」):[S53](https://support.google.com/googleplay/android-developer/answer/10158779) · 一手 · 高

---

## 8. Compose / tv-material 的最低系统要求;32 位用户空间

### 结论

1. **当前依赖,minSdk 都远低于 28**(数据取自 Google Maven 上 AAR 的清单):

   | 依赖 | minSdk |
   |---|---|
   | `tv-material` 1.0.0 | 21 |
   | Compose BOM 2024.10.01 对应的 ui / foundation 1.7.5 | 21 |
   | `activity-compose` 1.9.3 | 21 |
   | `core` 1.13.1 | 19 |

2. **最新版**(2026-10):

   | 依赖 | minSdk | 构建要求 |
   |---|---|---|
   | `tv-material` 1.1.0(2026-05-06) | 23 | 依赖 Compose 1.10.3,`minCompileSdk=35`、AGP ≥ 8.6 |
   | Compose 1.12.1(BOM 2026.09.00) | 23 | `minCompileSdk=37`、AGP ≥ 9.1 |

   - 这些都是**构建侧**的要求。运行时 minSdk 23 仍远低于 28。
   - AndroidX 在 2025-08(Activity 1.12.0-alpha06 的发布说明)宣布默认 minSdk 从 21 提到 23,与上表一致。
3. **32 位用户空间**:
   - A95L 实测 `ro.product.cpu.abilist = armeabi-v7a,armeabi`(纯 32 位)。
   - Google TV Streamer 也跑 32 位系统(二手)。
   - 带 native 代码的 APK 必须含 `armeabi-v7a`,否则装不上。UnitedU 唯一的 .so(`libandroidx.graphics.path.so`,来自 Compose)四个 ABI 都有。
   - Compose / ART 对 32 位没有额外要求。
   - **CDD**:32 位电视至少 896 MB 可用内存,64 位至少 1280 MB(在规定的屏幕密度下)。
4. **Google Play 的电视 64 位要求**(2026-08-01 起):
   - 只针对 Play 上架、带 native 代码的应用,要求同时提供 arm64 与 armeabi-v7a。Play 继续向 32 位设备分发。
   - targetSdk ≥ 35 时,64 位版本必须兼容 16 KB 页。
   - 对侧载不构成约束。即便如此,UnitedU 也已满足:已用 `zipalign -c -P 16` 验证通过,ELF LOAD 段对齐 2^14,四个 ABI 都是。
   - Android TV 16 的官方说明提到「64 位内核优化」,Google 也说即将有 64 位电视设备。arm64 那份 .so 已经备好。

### 证据

- **AAR 清单**:从 Google Maven(本机用 `dl-ssl.google.com/dl/android/maven2` 镜像,内容相同)下载 AAR,解出 `AndroidManifest.xml` 里的 `minSdkVersion` 和 `aar-metadata.properties`。
  - 来源:[S55] · 一手 · 高
- **tv-material 版本时间线**(1.0.0 是 2024-08-21,1.0.1 是 2025-07-16,1.1.0 是 2026-05-06):[S54](https://developer.android.com/jetpack/androidx/releases/tv) · 一手 · 高
- **AndroidX 默认 minSdk 21 → 23**:[S62](https://developer.android.com/jetpack/androidx/releases/activity)(Activity 1.12.0-alpha06,2025-08-13)· 一手 · 高
- **64 位要求、Play 继续支持 32 位、16 KB 要求**:[S56](https://android-developers.googleblog.com/2025/08/64-bit-app-compatibility-for-google-tv-android-tv.html)(2025-08-21)· 一手 · 高
- **Streamer 跑 32 位**:[S12](https://www.aftvnews.com/google-tv-streamer-to-be-the-first-device-with-android-tv-14-but-its-still-using-32-bit-architecture/) · 二手 · 中
- **CDD 2.3 电视内存**:[S24](https://source.android.com/docs/compatibility/14/android-14-cdd) · 一手 · 高
- **APK 实测**:`app/build/outputs/apk/release/app-release.apk`(2026-10-02 00:00 构建)。`zipalign -c -P 16 -v 4` 对四个 .so 都报 OK;用 readelf 看 LOAD 段,对齐都是 `2**14`。来源:[S60] · 高

---

## 9. Android 16(以及 17)在电视上

### 结论

1. **发布状态**:
   - Android 16 for TV 官方页的更新日期是 2025-05-20。说明里只列了 MediaQualityManager、Eclipsa 音频(IAMF)、HDMI-CEC 可靠性与 64 位内核优化,并注明这几项只在硬件上可用。
   - 截至 2026-08-31,没查到任何 Google TV / Android TV 消费设备推送 16。2026-05 的报道说 Google TV 一直缺席;Streamer 2026-06 的更新号仍是 U(14)系列。
   - 亚马逊确认 Fire OS 16 会包含 Android 15 / 16 的改动。这与 Google TV 是两条线。
2. **对第三方桌面的新限制**:官方电视说明没写。源码层面:
   - HOME 解析规则、HOME 角色、`set-home-activity`、TvSettings 空壳都没变。
   - `isHomeApp` 对 system uid 的放宽对 UnitedU 有利。
   - SAW 豁免仍然存在。
3. **到 16 之后,targetSdk 35 才在电视上生效的改动**:
   - **edge-to-edge 强制**:电视没有状态栏 / 导航栏,影响面小,但还是要在 16 模拟器上看一眼。targetSdk 35 下仍可用 `windowOptOutEdgeToEdgeEnforcement` 退出,到 36 才取消。
   - **targetSdk 35 的 BAL 收紧**:见第 3 节。
   - **最低可装 targetSdk 24**。
   - **后台网络限制**:不在有效的进程生命周期内发起网络请求会报异常。这一条对所有应用生效。
   - **16 KB 页兼容模式**:UnitedU 已经对齐。
4. **以后升 targetSdk 36 要注意的**:
   - **预测式返回默认开启**:系统不再调用 `onBackPressed`,`KEYCODE_BACK` 也不再派发给应用。
     - 项目里 `MainActivity.kt:1386` 在搬运模式下用 `KEYCODE_BACK` 取消搬运,升级后在 16 上会收不到。
     - 主返回逻辑已经走 `OnBackPressedDispatcher`,见 `MainActivity.kt:2254` 的注释。
     - 电视上预测式返回的具体表现没查到专门说明。
   - **大屏忽略方向限制**:只对最短边 ≥ 600 dp 的屏幕生效。A95L 是 1920×1080 @ 320 dpi,即 960×540 dp,不受影响。
   - **长按返回键**:16 起,对已迁移到预测式返回的应用,长按返回会触发预测式返回动画。这条对所有应用生效。
5. **Android 17(手机已发布)**:
   - 局域网权限 `ACCESS_LOCAL_NETWORK` 只对 targetSdk 37 强制。UnitedU 的局域网上传服务在 targetSdk 35 下不受影响。
   - 17 的 BAL 改动也只针对 targetSdk 37。
   - 电视版 17 未见任何消息。

### 证据

- [S1](https://developer.android.com/tv/release/16) · 一手 · 高
- [S9](https://9to5google.com/2026/05/26/android-16-will-come-amazon-fire-tv-despite-new-vegaos-focus/)、[S10](https://9to5google.com/guides/google-tv-streamer/)、[S11](https://www.aftvnews.com/google-tv-streamer-gets-its-first-update-of-2026/) · 二手 · 中
- [S8](https://www.androidauthority.com/google-tv-android-16-for-tv-preview-3559852/)(2025-05-21:16 的预览版在首页和快捷设置上与 14 没有区别)· 二手 · 中
- [S57](https://developer.android.com/about/versions/16/behavior-changes-all)、[S58](https://developer.android.com/about/versions/16/behavior-changes-16)、[S41](https://developer.android.com/about/versions/15/behavior-changes-all)、[S38](https://developer.android.com/about/versions/15/behavior-changes-15)、[S59](https://developer.android.com/about/versions/17/behavior-changes-17) · 一手 · 高
- [S30](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/BackgroundActivityStartController.java)、[S14](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/pm/ResolveIntentHelper.java)、[S13](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java) · 一手 · 高

---

## 10. 对 UnitedU 的直接含义(只列事实,不改代码)

1. **GMS 电视上,UnitedU 拿不到 HOME 键。** 这和「面向国行无 GMS」的定位一致,值得写进 README / FAQ。
   - 在 Google TV 和 GMS 版 Android TV 上,无论 Android 版本,都只能靠 adb 停用 Google 桌面,或用无障碍拦截 HOME 键。
2. **「设为默认桌面」在 GMS 电视上会给出误导。**
   - `switchHome()` 发的 `HOME_SETTINGS`,在 10+ 上会进权限控制器页,用户能勾选 UnitedU。
   - 但在 GMS 电视上勾了也不改变 HOME 键。
   - 要判断是否真的生效,得看 `resolveActivity(HOME)`。`RelaunchAfterUpdate` 已经是这么判断的。
3. **Android 9 的 AOSP 电视上,「设为默认桌面」可能毫无反应。**
   - 9 没有权限控制器,`HOME_SETTINGS` 会落到 TvSettings 的空壳页:`resolveActivity` 不为空、`startActivity` 成功,但页面当场关闭。
   - 当前代码的兜底条件(能解析 + 启动成功)识别不出这种情况。
   - 屏保那条入口链里的「弹回检测」可以复用到这里。
4. **更新后自动拉回(`shouldRelaunchHome`)要求悬浮窗权限,但各版本的必要性不同。**
   - 9:没有 BAL 限制,其实不需要。
   - 10 / 11 AOSP:没有桌面豁免,确实需要。
   - 12+ AOSP:按源码理论上不需要,但快速路径有漏洞;A95L 实测需要。
   - 16:对 system uid 的情况放宽了。
5. **悬浮窗授权页的直达**:9 / 10 的 AOSP TvSettings 不处理 `ACTION_MANAGE_OVERLAY_PERMISSION`。关于页已用 `runCatching` 兜底。
6. **屏保入口**:
   - `ACTION_DREAM_SETTINGS` 在 AOSP 和 GMS 电视上解析不到。现有的候选链(显式 `DaydreamActivity` → Ambient → 设置首页)与实测一致。
   - Google TV 用户在界面上选不到 UnitedU 屏保。
7. **升级 targetSdk 前的检查**:
   - 升到 36 前,先处理 `KEYCODE_BACK` 的取消搬运路径。
   - 升到 37 前,要为局域网上传服务申请 `ACCESS_LOCAL_NETWORK`。
8. **升级依赖**:想用 `tv-material` 1.1.0,要连带把 Compose 升到 1.10.3(minSdk 23,compileSdk 35 / AGP 8.6 够用);再往上到 Compose 1.12,需要 compileSdk 37 和 AGP 9.1。

---

## 矩阵:Android 版本 × UnitedU 依赖的能力

图例:

- 「可」= 能用;「条件」= 视厂商或配置;「否」= 不能用。
- 括号内是依据:A = AOSP 源码,O = 官方文档,D = 本项目实测,C = 社区 / 媒体。
- 「国行」= 无 GMS 的厂商固件(以 A95L 为样本);「GMS」= Google TV / GMS 版 Android TV。

| 版本 | 设默认桌面 | HOME 键 | 屏保 | TIF | 悬浮窗权限 | 应用内安装 |
|---|---|---|---|---|---|---|
| **9**(28) | 条件:没有 HOME 角色;AOSP 设置里 `HOME_SETTINGS` 是空壳;只能按 HOME 时在选择框选「始终」,或用 adb `set-home-activity`(A) | 条件:优先级最高者胜;同优先级时按偏好(A)。GMS 原厂桌面若是高优先级,则永远去原厂(推断) | 可:AOSP 屏保页列出第三方;`DREAM_SETTINGS` 没有 AOSP 处理者(A) | 条件:服务在,输入看厂商 HAL / 服务;直通要有系统 TV 应用(A) | 可授:「特殊应用权限」里有页,但没有直达 intent(A);**没有 BAL 限制,拉回不需要它**(A) | 可:按应用开「未知来源」(A) |
| **10**(29) | 条件:有 HOME 角色;`HOME_SETTINGS` 进权限控制器页(手机样式)(A)。GMS 上不生效(见 14 行) | 同 9 | 同 9。Google TV 2022-07 起只剩 Ambient(C,当时机型基于 10) | 同 9 | 可授,但无直达 intent(A);**AOSP 没有桌面 BAL 豁免**,拉回需要 SAW(A) | 同 9 |
| **11**(30) | 同 10 | 同 10 | 同 10 | 同 10 | 同 10,另外 TvSettings 开始处理直达 intent(A) | 同 10;包可见性开始生效,`QUERY_ALL_PACKAGES` 侧载照常授予(A / O) |
| **12**(31) | 同 10 | 同 10 | 同 10;Google TV 普遍锁定(C) | 同 10 | 可授;**AOSP 新增桌面豁免**,但快速路径只比对当前 home 进程的 uid(A) | 同 11 |
| **13**(33) | 无消费设备(O) | — | — | — | — | — |
| **14**(34) | 国行:可,角色生效、`HOME_SETTINGS` 可用(D)。GMS:否,角色能改但没用(D) | 国行:跟随角色(D)。GMS:永远回 Google 桌面(优先级 2)(D) | 国行(索尼):可,`DREAM_SETTINGS` 可达,列表里有 UnitedU(D)。GMS 版 Android TV:可,页面里有 UnitedU,但只能用显式组件进入(D)。Google TV:否,只有 Ambient,需 adb(D / C) | 索尼:7 路输入,直通交给 tvlin(D);无 HAL 的盒子:0 路(D) | 三套环境都有授权页(D);索尼实测桌面不被豁免,拉回需要 SAW(D) | 可;最低可装 targetSdk 23(O);三套环境都有未知来源页(D) |
| **15**(35) | 电视跳过(C / O) | — | — | — | — | — |
| **16**(36) | 没查到变化;源码里的解析规则同 14(A)。**截至 2026-08-31 没有已出货的设备**(C) | 同 14(A) | 没查到变化(O) | 没查到变化;新增的 MediaQualityManager 与输入切换无关(O) | 可授;`isHomeApp` 对 system uid 放宽(A);targetSdk 35 的 BAL 收紧从这一版起在电视上生效(O) | 可;最低 24(O);开发者验证只对手机 / 平板强制,ADB 豁免(O) |

---

## 附录 A:实测命令与关键输出(2026-10-02)

所有命令都是查询类,用 `scripts/env.sh` 的工具链执行。模拟器以 `emulator -avd <名> -read-only -no-window -port 5600/5602` 启动,跑完即 `adb emu kill`,不影响 AVD 的持久状态。

**A95L(`<电视IP>:<端口>`,国行,Android 14)**

```text
getprop ro.build.version.sdk                → 34
getprop ro.product.cpu.abilist              → armeabi-v7a,armeabi
getprop ro.config.low_ram                   → (空)
getprop ro.product.first_api_level         → 31
pm list features | grep home_screen         → (无)
特性:leanback、leanback_only、live_tv、hardware.tv.tuner、com.sony.dtv …

cmd package query-activities --brief -a MAIN -c HOME
  priority=0  com.dangbei.TVHomeLauncher/…sony.main.MainActivity
  priority=0  com.spocky.projengmenu/.ui.home.MainActivity
  priority=0  com.uniteduone.launcher/.MainActivity
  priority=-1000 com.android.tv.settings/.system.FallbackHome
cmd role get-role-holders android.app.role.HOME → com.uniteduone.launcher
resolve HOME → com.uniteduone.launcher/.MainActivity

HOME_SETTINGS                → #0 prio 2 com.android.permissioncontroller/.role.ui.HomeSettingsActivity
                               #1 prio 1 com.android.tv.settings/.EmptyStubActivity
MANAGE_DEFAULT_APPS_SETTINGS → #0 prio 2 …permissioncontroller/.role.ui.DefaultAppListActivity
DREAM_SETTINGS               → com.android.tv.settings/.device.display.daydream.DaydreamActivity (prio 0)
MANAGE_OVERLAY_PERMISSION    → com.android.tv.settings/.device.apps.specialaccess.SystemAlertActivity
MANAGE_UNKNOWN_APP_SOURCES   → com.android.tv.settings/.device.apps.specialaccess.ExternalSourcesActivity
REQUEST_ROLE                 → com.android.permissioncontroller/.role.ui.RequestRoleActivity

content gettype --uri content://android.media.tv/passthrough/x → vnd.android.cursor.item/channel
VIEW + 上述类型 → com.sony.dtv.tvlin/.view.MainActivity
service check tv_input → found
dumpsys tv_input → 7 个 TvInputInfo(com.mediatek.external/.HdmiInputService/HW2…HW5、HDMI200004;
                   com.mediatek.tis/.AnalogInputService/HW1;com.sony.dtv.tvinput.dvbtuner/.DvbTvInputService/HW0)
wm size / density → 物理 3840x2160,覆盖为 1920x1080;320
```

**`unitedu-gtv`(Google TV 镜像,`UTT1.240131.001.F1`)**

```text
abilist → arm64-v8a;low_ram → (空);pm list features 共 60 项,无 home_screen
HOME 候选:priority=2 com.google.android.apps.tv.launcherx/.home.HomeActivity
          priority=0 com.uniteduone.launcher/.MainActivity(以及 .gtv 旧包)
          priority=-1000 com.android.tv.settings/.system.FallbackHome
role HOME(初始)→ launcherx
cmd package set-home-activity com.uniteduone.launcher/.MainActivity → Success
role HOME → com.uniteduone.launcher
resolve HOME → com.google.android.apps.tv.launcherx/.home.HomeActivity
input keyevent KEYCODE_HOME → mCurrentFocus = launcherx HomeActivity
没有 setupwraith 包;有 launcherx、tvlauncher、com.google.android.permissioncontroller
HOME_SETTINGS → #0 prio 2 permissioncontroller HomeSettingsActivity / #1 prio 1 TvSettings EmptyStubActivity
DREAM_SETTINGS → No activities found
com.google.android.tv.settings.ambient → com.android.tv.settings/com.google.android.tv.settings.AmbientActivity
MANAGE_OVERLAY_PERMISSION → SystemAlertActivity;MANAGE_UNKNOWN_APP_SOURCES → ExternalSourcesActivity
screensaver_components → com.google.android.apps.tv.dreamx/.service.Backdrop
DreamService:com.android.dreams.basic/.Colors、com.uniteduone.launcher/.UnitedUDream
tv_input found,0 个输入;直通 VIEW(带类型)→ com.android.tv/.MainActivity
appops SYSTEM_ALERT_WINDOW / REQUEST_INSTALL_PACKAGES(UnitedU)→ default
```

**`unitedu-tv`(GMS 版 Android TV 镜像,`UTT1.240131.001.F1`)**

```text
HOME 候选:priority=2 com.google.android.tvlauncher/.MainActivity
          priority=0 com.uniteduone.launcher/.MainActivity
          priority=-1000 TvSettings FallbackHome
role HOME → com.uniteduone.launcher(AVD 早先就设过)
resolve HOME → com.google.android.tvlauncher/.MainActivity
HOME_SETTINGS → permissioncontroller(prio 2)> EmptyStubActivity(prio 1)
DREAM_SETTINGS → No activities found
am start -n com.android.tv.settings/.device.display.daydream.DaydreamActivity → 正常打开
屏保列表(UI dump)→ Backdrop / Colors / Turn screen off / UnitedU
```

---

## 来源清单

类型:一手 = 官方文档 / AOSP 源码 / Google 政策与博客 / 本项目实测;二手 = 媒体、社区、第三方厂商。AOSP 链接都指向具体分支上的文件;同一文件在其他分支上的路径,把 `refs/heads/<分支>` 换掉即可。

**官方文档与博客(一手)**

| 编号 | 链接 | 说明 |
|---|---|---|
| S1 | https://developer.android.com/tv/release/16 | Android 16 for TV,更新于 2025-05-20 |
| S2 | https://developer.android.com/tv/release/14 | Android 14 for TV |
| S3 | https://developer.android.com/tv/release/13 | Android 13 for TV(只是开发者版) |
| S4 | https://developer.android.com/tv/release/12 | Android 12 for TV |
| S5 | https://android-developers.googleblog.com/2024/05/android-14-and-compose-on-tv.html | 2024-05-15 宣布 Android 14 for TV |
| S24 | https://source.android.com/docs/compatibility/14/android-14-cdd | Android 14 CDD:2.3 电视要求、3.2.3.5、Launcher、Dreams |
| S28 | https://developer.android.com/guide/components/activities/background-starts | 后台启动的限制与豁免,更新于 2026-05-18 |
| S34 | https://developer.android.com/about/versions/10/behavior-changes-all | Go 设备上的 SAW |
| S37 | https://developer.android.com/about/versions/14/behavior-changes-14 | targetSdk 34 的 BAL 改动 |
| S38 | https://developer.android.com/about/versions/15/behavior-changes-15 | targetSdk 35 的 BAL、SAW + 前台服务、edge-to-edge |
| S40 | https://developer.android.com/about/versions/14/behavior-changes-all | 最低可装 targetSdk 23 |
| S41 | https://developer.android.com/about/versions/15/behavior-changes-all | 最低可装 24、16 KB 页、后台网络 |
| S42 | https://developer.android.com/developer-verification | 开发者验证:时间表与范围 |
| S43 | https://developer.android.com/developer-verification/guides/faq | 开发者验证 FAQ,更新于 2026-09-30 |
| S44 | https://support.google.com/googletv/answer/10070821?hl=en | Google TV 帮助:更换屏保 |
| S52 | https://developer.android.com/training/package-visibility/declaring | 包可见性,更新于 2026-09-16 |
| S53 | https://support.google.com/googleplay/android-developer/answer/10158779 | Play 的 `QUERY_ALL_PACKAGES` 政策 |
| S54 | https://developer.android.com/jetpack/androidx/releases/tv | androidx.tv 发布记录 |
| S55 | https://dl.google.com/dl/android/maven2/(本机经 `dl-ssl.google.com` 镜像) | Google Maven 上的 AAR 清单与元数据:tv-material 1.0.0 / 1.1.0、ui / foundation 1.7.5 / 1.10.3 / 1.12.1、activity-compose 1.9.3、core 1.13.1 |
| S56 | https://android-developers.googleblog.com/2025/08/64-bit-app-compatibility-for-google-tv-android-tv.html | 电视 64 位要求,2025-08-21 |
| S57 | https://developer.android.com/about/versions/16/behavior-changes-all | Android 16 对所有应用的行为变更 |
| S58 | https://developer.android.com/about/versions/16/behavior-changes-16 | targetSdk 36 的行为变更 |
| S59 | https://developer.android.com/about/versions/17/behavior-changes-17 | targetSdk 37 的行为变更,更新于 2026-09-16 |
| S62 | https://developer.android.com/jetpack/androidx/releases/activity | AndroidX 默认 minSdk 21 → 23(Activity 1.12.0-alpha06,2025-08-13) |

**AOSP 源码(一手,android.googlesource.com)**

| 编号 | 链接 | 说明 |
|---|---|---|
| S13 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java | `set-home-activity`。另查 `nougat-release`、`pie-release`、`android10-release`、`android16-release` |
| S14 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/ResolveIntentHelper.java | `chooseBestActivity`。另查 android16;9 版在 `pie-release` 的 `PackageManagerService.java` |
| S15 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/resolution/ComponentResolver.java | `adjustPriority` |
| S16 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/ComputerEngine.java | `getDefaultHomeActivity`、首选 Activity 失效 |
| S17 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/PreferredActivityHelper.java | `updateDefaultHomeNotLocked` |
| S18 | https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android16-release/Settings/AndroidManifest.xml | TvSettings 清单。另查 pie / 10 / 11 / 12 / 13 / 14 / 15 |
| S18b | https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android16-release/Settings/src/com/android/tv/settings/EmptyStubActivity.java | 空壳页 |
| S19 | https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android16-release/Settings/res/xml/apps.xml | 「应用」页 |
| S19b | https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android14-release/Settings/res/xml/special_app_access.xml | 特殊应用权限页 |
| S19c | https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android16-release/Settings/res/xml/security.xml | 安全与限制页 |
| S19d | https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android16-release/Settings/res/xml/daydream.xml | 屏保页 |
| S20 | https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android14-release/PermissionController/AndroidManifest.xml | 权限控制器清单;另有同目录的 `res/xml/roles.xml` 与 `role-controller/…/RoleParser.java` |
| S20b | https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android14-release/PermissionController/src/com/android/permissioncontroller/role/ui/DefaultAppActivity.java | 另有同目录的 `HomeSettingsActivity.java` |
| S20c | https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android14-release/PermissionController/src/com/android/permissioncontroller/role/ui/behavior/HomeRoleUiBehavior.java | `config_showDefaultHome` |
| S20d | https://android.googlesource.com/platform/packages/apps/PackageInstaller/+/refs/heads/android10-release/AndroidManifest.xml | Android 10 时 `HomeSettingsActivity` 所在位置 |
| S21 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/res/res/values/config.xml | `config_showDefaultHome` 默认值 |
| S22 | https://android.googlesource.com/device/google/atv/+/refs/heads/android14-release/permissions/tv_core_hardware.xml | 电视核心特性表;另有同仓库的 `TvProvision/AndroidManifest.xml` 与分支列表 `+refs` |
| S22b | https://android.googlesource.com/device/google/atv/+/refs/heads/android14-release/products/atv_lowram_defaults.mk | 电视低内存配置 |
| S23 | https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android14-release/data/etc/handheld_core_hardware.xml | 手机核心特性表 |
| S29 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/wm/ActivityStarter.java | 另查 android11 / 12 / 13;9 版在 `pie-release` 的 `services/core/java/com/android/server/am/ActivityStarter.java` |
| S30 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/BackgroundActivityStartController.java | 另查 android14 / 15 |
| S31 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/wm/WindowProcessController.java | 11 的进程级 BAL 放行条件 |
| S32 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/wm/ActivityTaskManagerService.java | `hasSystemAlertWindowPermission` |
| S33 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/app/AppOpsManager.java | `getSystemAlertWindowDefault` |
| S35 | https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android14-release/Settings/src/com/android/tv/settings/device/apps/specialaccess/SystemAlertWindow.java | 另有同目录的 `SystemAlertActivity.java` |
| S39 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/res/AndroidManifest.xml | 权限保护级别 |
| S47 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/java/com/android/server/SystemServer.java | 另查 pie / android14 |
| S48 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/app/SystemServiceRegistry.java | 系统服务注册 |
| S49 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/tv/TvInputManagerService.java | `getTvInputList` |
| S49b | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/tv/TvInputHal.java | `init` / `ERROR_NO_INIT` |
| S50 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/media/java/android/media/tv/TvContract.java | `buildChannelUriForPassthroughInput` |
| S50b | https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android14-release/src/com/android/providers/tv/TvProvider.java | `getType` |
| S51 | https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/android14-release/AndroidManifest.xml | AOSP Live TV 清单 |

**媒体 / 社区 / 第三方(二手)**

| 编号 | 链接 | 说明 |
|---|---|---|
| S6 | https://en.wikipedia.org/wiki/Android_TV | 各版本发布日期 |
| S7 | https://www.aftvnews.com/google-is-skipping-android-tv-15-and-jumping-straight-to-android-tv-16-for-its-next-release/ | 2025-04-25,跳过 15 |
| S8 | https://www.androidauthority.com/google-tv-android-16-for-tv-preview-3559852/ | 2025-05-21,16 的预览 |
| S9 | https://9to5google.com/2026/05/26/android-16-will-come-amazon-fire-tv-despite-new-vegaos-focus/ | 2026-05-26 |
| S10 | https://9to5google.com/guides/google-tv-streamer/ | 2026 年各篇,最新一篇 2026-08-31 |
| S11 | https://www.aftvnews.com/google-tv-streamer-gets-its-first-update-of-2026/ | 2026-06-09,`UTTK.260317.003` |
| S12 | https://www.aftvnews.com/google-tv-streamer-to-be-the-first-device-with-android-tv-14-but-its-still-using-32-bit-architecture/ | 2024-08-16 |
| S25 | https://www.celsoazevedo.com/replace-launcher-chromecast-google-tv/ | 2026-01-27 |
| S26 | https://www.androidauthority.com/google-tv-update-blocks-projectivy-override-3649445/ | 2026-03-16 |
| S27 | https://www.flatpanelshd.com/guide.php?subaction=showfull&id=1754640308 | 2025-08-08 |
| S36 | https://playsignage.com/setup/google-tv-digital-signage/ | 页面无日期 |
| S45 | https://www.howtogeek.com/125089/chromecast-with-google-tv-loses-third-party-screensavers/ | 2022-08-08 |
| S46 | https://prateek11rai.github.io/sanji/blog/2026/05/17/freeing-the-screensaver-4k-anime-art-on-google-tv/ | 2026-05-17 |

**本项目(一手)**

| 编号 | 位置 | 说明 |
|---|---|---|
| S60 | 本文件附录 A | 2026-10-02 的只读实测:A95L、`unitedu-gtv`、`unitedu-tv` |
| S61 | 仓库内 | `CLAUDE.md`(「模拟器验证的坑」「真机装包的一个副作用」);`docs/WORKLOG.md`(第 34、148、530、776、977、1755 行);`app/src/main/java/com/uniteduone/launcher/{SystemStatus.kt, MainActivity.kt, RelaunchAfterUpdate.kt, AboutScreen.kt, ApkInstaller.kt, Inputs.kt}`;`app/src/main/AndroidManifest.xml`;`app/build.gradle.kts` |
