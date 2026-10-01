# 国内电视品牌兼容性调研 · 第一部分:小米/Redmi、TCL/雷鸟、海信/Vidda、创维/酷开

- 调研日期:2026-10-02
- 被调研对象:UnitedU(`com.uniteduone.launcher`),minSdk 28 / targetSdk 35,四种 ABI 都有 .so;清单声明 `MAIN+LEANBACK_LAUNCHER` 与 `MAIN+HOME+DEFAULT`(**没有**普通 `LAUNCHER`),`uses-feature android.software.leanback required=true`,另有 `DreamService`、`SYSTEM_ALERT_WINDOW`、`REQUEST_INSTALL_PACKAGES`。
- 方法:WebSearch + WebFetch。本轮 WebSearch 配额用尽后,只对已检索到的页面做了抓取补证,没有再开新搜索。知乎、部分 CSDN/雪球页面抓不到(403/521),这类只用搜索摘要的结论一律标「仅搜索摘要」并降为「低」。
- **把握度**:高 = 一手来源,或 ≥2 个独立二手来源一致且年代较新;中 = 单个可靠二手来源,或多来源但年代较旧;低 = 单个论坛帖、仅搜索摘要、或间接推断。标「推论」的是根据证据推出的,不是来源原话。
- **来源类型**:一手 = 厂商官网/开发者文档/官方公告、AOSP/Android 官方文档、政府公告;二手 = 当贝/沙发管家官方教程(高质量二手)、媒体评测、论坛实测帖、个人技术博客。正文中每条证据后用 `[编号](URL)` 标出,编号对应文末「来源清单」。

---

## 0. 总结论(先看这里)

| 品牌 | 对 UnitedU | 成立条件 / 卡点 |
|---|---|---|
| **小米 / Redmi** | **部分能用**(新机基本只能当普通应用) | 安卓 9+ 机型能侧载(未知来源按应用授权 + ADB)。约 2020 年前的老固件按 HOME 可选第三方桌面;**2021 起新固件 HOME 强制回 PatchWall**,要用 adb 禁用原桌面,且有固件会联网强制还原;**澎湃 OS(底层安卓 11)禁用原桌面后开机黑屏**,社区结论是用不了第三方桌面。切 HDMI 要走小米私有 Activity。 |
| **TCL / 雷鸟** | **默认不能用** | TCL 的安装链路会拦截「桌面类」APK(声明 HOME 的应用),UnitedU 正好声明 HOME → 正常途径大概率装不上。个别雷鸟安卓 11 机型用 adb 关闭安装校验后能装、按 HOME 能进,但开机仍进自家桌面;也有机型(75V8E Pro)adb 也装不上。系统只允许在自家「灵控/聚合」两个桌面间切换。 |
| **海信 / Vidda** | **部分能用** | 国行是安卓底层(海外 VIDAA OS 才是 Linux,不能装 APK)。安卓 9+ 机型可装(商场模式 / 安装权限 / ADB);**按 HOME 会弹系统桌面选择框**,可选 UnitedU;但断电冷启动回聚好看;**2023-08 起云端风控会拦截从自家界面打开部分第三方应用**(经 HOME 选择框或别的入口打开可绕过)。 |
| **创维 / 酷开** | **不能当桌面**,只能当普通应用 | 默认主页写死在厂商配置文件(`DEFAULT_HOMEPAGE`),原桌面用私有 HOME intent;adb 禁用原桌面会卡开机;改它要 root 或改 factory 分区。安装还要过「应用圈」白名单,新系统才有「允许安装未知来源应用」开关。 |

跨品牌共性(细节见第 5、6 节):

1. **minSdk 28 直接挡掉安卓 8.1 及以下机器**:本轮查到仍在用的有小米电视 4 系列(安卓 6.0.1)、酷开 65P50(安卓 8)、海信 VIDAA_TV(安卓 8.0.0,2024 年仍有人用)。
2. **64 位芯片跑 32 位系统很普遍**(小米、TCL、酷开都有实测/报告);UnitedU 自带 armeabi-v7a,这一点不受影响。
3. **切信号源普遍做在厂商私有接口里**,第三方桌面(包括当贝桌面)普遍切不了 HDMI;标准 TIF 透传(`TvContract` passthrough URI + `ACTION_VIEW`)在这四个品牌上能否用,**都未查到实测**。
4. **系统屏保能否选第三方、「显示在其他应用上层」设置入口**:四个品牌都**未查到**证据。
5. `uses-feature leanback required=true` 不影响侧载:Android 系统安装时不检查 uses-feature(一手,见 [G1](https://developer.android.com/guide/topics/manifest/uses-feature-element))。

---

## 1. 小米 / Redmi(PatchWall、MIUI TV、澎湃 OS 电视版)

### 结论

**部分能用,且越新越差。** 安卓 9+ 的机型可以把 UnitedU 当普通应用装上运行。想当默认桌面:约 2020 年前的老固件可以(HOME 弹选择框 / adb 卸原桌面);2021–2024 年的 MIUI TV 固件 HOME 被强制回 PatchWall,只有用 adb 禁用 `com.mitv.tvhome`(如 LM Ultimate)才行,还可能被云端强制还原;2024 年起的澎湃 OS(底层安卓 11)禁用原桌面后开机黑屏,社区普遍认为当不了桌面。HDMI 切换是私有接口,标准 TIF 路径大概率不通(未实测)。

### 1.1 系统名称与安卓版本

- 2017–2018 年的小米电视 4(MiTV4-ANSM0):MIUI TV 1.3.97,底层安卓 6.0.1 → UnitedU 装不上(minSdk 28)。[X8](https://c.m.163.com/news/a/H60BJMOA08380019.html)(二手,2022-04)· 中
- 一份 2026 年刷机指南称:旧款(安卓 6.0/8.0)可 `adb root`,新款(安卓 9.0+)关闭了 ADB root,可见存在安卓 9 这一代。[X23](https://tencentcloud.csdn.net/69ea754c54b52172bc6e46a2.html)(二手,内容农场风格)· 低
- 澎湃 OS 电视版底层安卓 11:Redmi A Pro 55 用户贴出系统版本 `OS1.0.24.0.RSTAATV`,自述底层安卓 11。[X6](https://www.znds.com/tv-1257474-1-1.html)(二手,2025-01)· 中。推论:小米版本号里的 `R` 按惯例对应 Android R(11),与用户说法一致。
- 2024-06 起澎湃 OS 适配名单含小米电视 S Pro Mini LED 65/75/85、S 系列 Mini LED、Redmi MAX 100 2025 款、Redmi A 2025 系列等。[X19](https://n.znds.com/article/news/66059.html)(二手,2024-06)· 中
- 国行小米电视有没有安卓 12/14 的机型:**未查到**。非安卓机型:**未查到**。

### 1.2 侧载

- 未知来源:设置 → 账号与安全 → 安装未知来源应用,**按应用逐个授权**(比如给文件管理器)。[X13](https://www.sohu.com/a/879807110_122004016)(二手,2025-04)· 高(与 [X12](https://www.znds.com/tv-1239403-1-1.html) 一致)
- 开发者模式与 ADB:设置 → 关于 → 「产品型号」连按若干次 → 账号与安全 → ADB 调试;有汇总称**每次开机都要重新打开 ADB 开关**。[X17](https://www.znds.com/tv-1251785-1-1.html)(二手,2024-08)· 中
- 厂商/监管限制时间线:
  - 2020-05:小米电视 3、4C 一度提示 `禁止通过非系统应用商店安装应用`,后来解除。[X9](https://www.znds.com/tv-1174704-1-1.html)(二手,2020-05)· 中
  - 2022-08 起(雪球摘要称 8 月 26 日开始):直播类应用(如电视家)安装时提示 `根据互联网电视相关要求，应用存在违规功能，禁止安装`;2023-06 前后还见过 `检测安装包异常，无法安装`。[X10](https://www.163.com/dy/article/IF2OFEOS05520K4W.html)(二手,2023-09)· 中;[X22](https://xueqiu.com/1505526877/258147925)(仅搜索摘要)· 低
  - 机制:系统 PackageInstaller 被更新,读取一份**开机自动刷新的云端黑名单**(设置项 `PICONFIG`,列的是直播类包名,如 `com.tvrun.run`、`com.dianshijia.dangbei`);`adb install` 不经过 PackageInstaller,不受拦。[X11](https://www.znds.com/tv-1252116-1-1.html)(二手,2024-08)· 中
  - 推论:这份名单针对直播应用,UnitedU 目前不在其列;名单云端下发,日后是否加入无法排除。

### 1.3 能否设为默认桌面

- **老固件(约 2020 年前)可以**:装好第三方桌面后按 HOME,弹选择框选「始终」。[X2](https://miuiver.com/ad-blocking-on-mitv/)(二手,作者注明新系统可能失效)· 中;用 `adb shell pm uninstall --user 0 com.mitv.tvhome` 卸掉原桌面后开机直接进第三方桌面,并建议同时卸 `com.xiaomi.mitv.upgrade` 防升级还原。[X3](https://www.znds.com/tv-1161941-1-1.html)(二手,2019-12)· 中;同法 2024 年仍有教程转载 [X21](https://blog.csdn.net/qq_42123284/article/details/135484544)· 低
- **2021-07**:用户报告新系统强制小米桌面、第三方桌面失效,adb 卸载这招已被封;回帖建议改用官方的「办公模式」桌面。[X1](https://www.v2ex.com/t/788781)(二手,2021-07)· 中
- **2023-03**:小米 EA55(L55M7-EA,MIUI TV 2.5.1233)升级后,打开当贝桌面再按 HOME 会回系统桌面,adb 卸载无效;装 LM Ultimate 选 `DISABLE STOCK LAUNCHER` 禁用原桌面后,HOME 才稳定进当贝桌面。[X4](https://www.znds.com/tv-1233298-1-1.html)(二手,2023-03)· 中
- **2024-04**:有新版系统检测到原桌面被替换后联网同步升级包、强制重启还原;帖子给出在路由器屏蔽小米同步/升级域名的办法,但 2024-12 有 EA55 用户称失效。[X5](https://www.znds.com/tv-1247860-1-1.html)(二手,2024-04)· 中
- **澎湃 OS(2025)**:Redmi A Pro 55(安卓 11)装当贝桌面并禁用原桌面后,每次重启都黑屏;回帖称澎湃 OS 用不了第三方桌面,而且删掉了原来自带的「企业桌面」。[X6](https://www.znds.com/tv-1257474-1-1.html)(二手,2025-01)· 中;澎湃 2.0 上 adb 操作都不起作用、找不到刷机包。[X7](https://www.znds.com/tv-1260979-1-1.html)(二手,2025-05)· 低–中
- 系统设置里有没有「默认桌面 / 主屏幕应用」选项:**未查到可靠证据**(有搜索摘要提到「设置 → 全部设置 → 显示 → 默认桌面」,无法核实,不采信)。
- `cmd package set-home-activity` 在小米电视上是否生效:**未查到任何实测**。

### 1.4 HOME 键与开机

- 2021 年后的 MIUI TV 固件:HOME 键强制回 PatchWall(见 1.3 的 [X1](https://www.v2ex.com/t/788781)、[X4](https://www.znds.com/tv-1233298-1-1.html))· 中;澎湃 OS 推测同样(无相反证据)· 低–中
- 开机:默认进 PatchWall,开机广告由 `mitv.service` 提供(教程用 adb 卸载它)。[X3](https://www.znds.com/tv-1161941-1-1.html)· 中
- 已知绕法:
  - LM Ultimate 禁用原桌面(需 ADB,2023 有效,有被云端还原的风险)。[X4](https://www.znds.com/tv-1233298-1-1.html)、[X5](https://www.znds.com/tv-1247860-1-1.html)· 中
  - ProjectivyTools + 无障碍服务做 HDMI 快捷方式(2021,老固件)。[X20](https://www.znds.com/tv-1201974-1-1.html)· 低
  - 当贝桌面「开机自启动」:澎湃 OS 帖回帖认为定制系统禁止第三方应用开机自启(推测,未验证)。[X6](https://www.znds.com/tv-1257474-1-1.html)· 低

### 1.5 UnitedU 依赖的能力

- **输入源 / TIF**:HDMI 切换走私有 Activity:`am start -n com.xiaomi.mitv.tvplayer/com.xiaomi.mitv.tvplayer.ExternalSourceActivity --ei input 23`(HDMI1=23、HDMI2=24、HDMI3=25,AV=2,TV=1)。[X14](https://www.52pojie.cn/thread-1983446-1-1.html)(二手,2024-11)· 中;`com.xiaomi.mitv.tvplayer` 在系统应用表里是「模拟电视」。[X15](https://blog.csdn.net/qq_42123284/article/details/135349127)· 中。标准 `TvInputManager` / `TvContract` 透传 URI 是否可用:**未查到**。
- **系统屏保**:系统屏保包是 `com.mitv.screensaver`(智能屏保)。[X15](https://blog.csdn.net/qq_42123284/article/details/135349127)· 中。设置里能否选第三方 `DreamService`:**未查到**。
- **「显示在其他应用上层」**:**未查到**电视端入口。
- **应用内安装更新**:未知来源是按应用授权的,UnitedU 需要在「安装未知来源应用」列表里单独打开(推论,依据 [X13](https://www.sohu.com/a/879807110_122004016));PackageInstaller 的云端黑名单只拦名单内的包(见 1.2)· 中

### 1.6 典型硬件

- 用户用 AIDA64 实测:小米电视是 64 位 CPU 配 32 位系统;2024 年的回帖称当年新款小米、TCL、索尼几乎仍是 32 位系统,有人归因于联发科方案。[X16](https://www.znds.com/tv-1223480-1-1.html)(二手,2022-09 起)· 中
- 内存:**未查到一手规格**。

---

## 2. TCL / 雷鸟(FFALCON)

### 结论

**默认不能用。** TCL 系统在安装环节专门拦截「桌面类」应用:2017 年固件的反编译代码显示,声明 `MAIN+HOME` 的应用必须是 TCL 签名才能装;2019–2026 年各代用户都报告当贝桌面 / Emotn 这类桌面装不上。UnitedU 清单声明了 HOME,正常途径大概率装不上。个别雷鸟安卓 11 机型 adb 关闭安装校验后能装上第三方桌面,按 HOME 能进,但**开机仍进自家桌面**;75V8E Pro 等机型 adb 也装不上。系统自带的「桌面切换」只在 TCL 自家的灵控桌面 / 聚合桌面之间切。

### 2.1 系统名称与安卓版本

- 2017 款 TCL:安卓 5.0.1(API 21),arm64。[T1](https://rocka.me/article/cursed-tcl-android-tv)(二手·逆向分析,2020-02)· 中
- 雷鸟电视(2022):安卓 11(帖子针对「安卓 11 装不上当贝桌面」的说法给出 adb 装法,楼主机器即安卓 11)。[T5](https://www.znds.com/tv-1225751-1-1.html)(二手,2022-11)· 低–中;雷鸟鹤 7 Pro:安卓 11、MT9655、4GB+64GB(知乎,仅搜索摘要)· 低
- 2023 年起新系统上线「灵控桌面」,可与传统「聚合桌面」互切。[T14](https://www.znds.com/tv-1255993-1-1.html)(二手,2024-12)、[T15](https://www.znds.com/tv-1239800-1-1.html)(二手,2023-09)· 中;TCL X11H(2024)评测确认带灵控 / 聚合两种桌面。[T16](https://digi.ithome.com/archiver/782/721.htm)(二手·媒体评测,2024-07)· 中
- 国行安卓 12/14 机型:**未查到**。非安卓机型:**未查到**。

### 2.2 侧载

- 2017 固件的定制安装器:除非是应用自更新或可信市场发起,否则直接拒绝安装(日志 `disable install apk from unknow AppMarket`),除非系统属性 `persist.tcl.installapk.enable=1`;`pm install` / adb 安装受 `persist.tcl.debug.installapk` 控制;另有黑 / 白名单路径属性 `persist.tcl.appblacklistpath`、`persist.tcl.whitelistpath`。[T1](https://rocka.me/article/cursed-tcl-android-tv)· 中(代码级证据,年代老)
- 实操(多源一致):设置 → 系统 → 本机信息,遥控器依次按「上下左右」露出 ADB 开关;`adb shell` 里 `setprop persist.tcl.debug.installapk 1` 和 `setprop persist.tcl.installapk.enable 1`,之后可 adb 安装或用文件管理器安装。[T12](https://www.cnblogs.com/woaixingxing/p/18698547)(2025-02)、[T11](https://www.znds.com/tv-1189881-1-1.html)(2021-02)、[T8](https://www.tofuwine.cn/posts/be8d2dca/)(2024-02)· 高;端口 5555。[T11](https://www.znds.com/tv-1189881-1-1.html)· 中
- 互相矛盾的说法:有用户 2024-05 报 `setprop: failed to set property`(仅搜索摘要)· 低;2022 年知乎称「安卓 11 系统只能精简系统应用,无法破解安装权限」(仅搜索摘要,页面 403)· 低 —— 与 [T5](https://www.znds.com/tv-1225751-1-1.html)(安卓 11 雷鸟用 adb 装上了当贝桌面)矛盾,两说并列。
- 老提示文案:`出于安全考虑，已禁止您的电视安装来自此来源的未知应用`。[T18](https://n.znds.com/article/35272.html)(二手,2018-12)· 中
- 官方渠道:电视卫士「极速安装」走 U 盘,或在自带商店装「欢视助手」再装「电视应用管家」;从当贝市场下载的应用也要绕这条路。[T17](https://www.dangbei.com/tcl.html)(二手·当贝官方)· 中
- 2024 年汇总帖:灵控桌面系统更新后限制加码,2022 年前的机型才适用 ADB 工具包那套方法。[T20](https://www.znds.com/tv-1245535-1-1.html)(二手,2024-02)· 低–中

### 2.3 能否设为默认桌面

- **桌面类应用在安装时被拦截(核心卡点)**:2017 固件的 PackageManagerService 在安装时检查 `MAIN+HOME`;这类应用必须匹配 TCL 签名并带 `meta-data com.tcl.app.type=com.tcl.ui`,否则返回 -104,日志 `disable install launcher app not signature by tcl`。[T1](https://rocka.me/article/cursed-tcl-android-tv)· 中
- 各代用户报告一致:2019 年 D55A620U 官方禁止安装第三方桌面,U 盘法只能装普通应用 [T2](https://blog.fyun.org/tcl-root.html)(2019-04);2021 年当贝桌面安装报「已经存在相同签名的软件」[T3](https://www.znds.com/tv-1191620-1-1.html)(推论:与 -104 签名不一致错误吻合);2022 年雷鸟 75S545C 当贝桌面装不上、其它应用能装 [T4](https://www.znds.com/tv-1214305-1-1.html);2023 年 75V8E Pro 当贝桌面 / Emotn 都装不上,setprop 也没用 [T7](https://v2ex.com/t/945781);2024 年同型号 adb 装当贝桌面仍失败 [T8](https://www.tofuwine.cn/posts/be8d2dca/);2026 年博客称当贝桌面像是在系统层面被禁,需先 `pm disable-user com.android.packageinstaller` 再 adb 安装,装完必须 `enable` 回来否则开不了机,报错为 `INSTALL_FAILED_VERIFICATION_FAILURE` [T10](https://youthlin.com/20261889.html)。→ **「拦截存在」把握度:高;新固件上的具体机制(PMS / 安装校验器 / 包名单):中–低**
- 例外:2022 年雷鸟安卓 11 用 `settings put global verifier_verify_adb_installs 0`、`settings put global package_verifier_enable 0` 后 `adb install` 当贝桌面成功。[T5](https://www.znds.com/tv-1225751-1-1.html)· 中;2025 年雷鸟 55F270C-J 用同样两条命令排查校验失败(未写结果)。[T9](https://blog.5625.cn/index.php/archives/46/)· 低
- 系统自带的「桌面切换」只有 TCL 自家灵控桌面 / 聚合桌面(长按主页键或首页底部 / 顶部下拉进入)。[T14](https://www.znds.com/tv-1255993-1-1.html)、[T15](https://www.znds.com/tv-1239800-1-1.html)· 中;回帖称官方精简桌面(灵动 / 灵控桌面)可直接装上用,是官方能走通的「换桌面」路线。[T7](https://v2ex.com/t/945781)(第 2 页回帖)· 中
- 旧法:装好第三方桌面后 `pm hide com.tcl.cyberui`(原桌面),按 HOME 弹「请选择要使用的电视桌面」。[T3](https://www.znds.com/tv-1191620-1-1.html)(2021-02)· 中;`com.tcl.cyberui` 即系统桌面。[T13](https://github.com/gaesa/TCLDebloater)(二手·开源脚本,2024-02 归档)· 中

### 2.4 HOME 键与开机

- 第三方桌面装上后:按 HOME 能进,但开机进不了,要开机后再按一次 HOME。[T5](https://www.znds.com/tv-1225751-1-1.html)(2022-11)· 中;另一帖称雷鸟目前无法开机直接进第三方桌面,禁用原桌面会卡在初始化界面。[T6](https://www.znds.com/tv-1222082-1-1.html)(2022-08)· 低–中
- S48CTXX 系列最新固件锁定了主页键,冻结原桌面会卡开机画面。[T3](https://www.znds.com/tv-1191620-1-1.html)(2021-02)· 中
- 系统没有官方「开机自启某应用」选项,adb `am start` 只能当场打开、不能开机自启。[T19](https://www.znds.com/tv-1189016-1-1.html)(2021-01)· 低

### 2.5 UnitedU 依赖的能力

- **输入源 / TIF**:**未查到**。
- **系统屏保选第三方**:**未查到**。
- **「显示在其他应用上层」**:**未查到**。
- **应用内安装更新**:2017 代码里调用方包名等于被装包名时按「自更新」放行,其它来源一律拒绝。推论:UnitedU 更新自己可能被放行,但新包仍声明 HOME,会再次撞上桌面类拦截;UnitedU「传 APK 安装」别的应用会被拒(除非 `persist.tcl.installapk.enable=1`)。[T1](https://rocka.me/article/cursed-tcl-android-tv)· 低(老固件、未实测)

### 2.6 典型硬件

- TCL X11H(2024):A73 四核 + G52 MC1,4GB + 128GB。[T16](https://digi.ithome.com/archiver/782/721.htm)· 中
- 2017 款为 arm64(64 位系统)。[T1](https://rocka.me/article/cursed-tcl-android-tv)· 中;2024 年回帖称 TCL 新款也是 64 位 CPU 配 32 位系统。[X16](https://www.znds.com/tv-1223480-1-1.html)· 低–中

---

## 3. 海信 / Vidda(VIDAA、聚好看系统)

### 结论

**部分能用。** 国行海信 / Vidda 是安卓底层的定制系统(海外的 VIDAA OS 才是 Linux、只能跑 HTML5 应用,装不了 APK;本轮没查到国行在售机型用 Linux VIDAA 的证据)。安卓 9+ 机型可以装 UnitedU(商场模式 / 安装权限 / ADB)。**按 HOME 会弹系统桌面选择框,可以选第三方桌面**;但断电后冷启动回聚好看。2023-08-09 起海信「统一配置」的云端风控会拦截部分已装第三方应用的打开(提示有安全风险),经 HOME 选择框、当贝市场、长按主页键、「喜好」键列表打开可绕过。安卓 8 及以下的老机(2024 年仍有在用)装不上 UnitedU。

### 3.1 系统名称与安卓版本

- 国行:2018 年媒体文章称创维酷开 OS、海信 VIDAA 名字不同,本质都是 Android TV 的本地化定制版。[H17](https://www.jiemian.com/article/1983335.html)(二手·媒体,2018-03)· 中
- 各代安卓版本:VIDAA 4 为安卓 5.1 定制(2016 MU8600,仅搜索摘要)· 低;VIDAA 3 / VIDAA 4 新机为安卓 9(2020 年教程)。[H10](https://blog.csdn.net/weixin_36317800/article/details/117679909)(二手,2020-03)· 中;2024 年仍有机型显示 `Hisense VIDAA_TV (Android 8.0.0)`。[H6](https://www.cnblogs.com/backuper/p/18068033)(二手,2024-03)· 中;2023 年 E8K:基于 64 位安卓 11 定制的聚好看系统。[H12](https://zhuanlan.zhihu.com/p/635025521)(二手·评测,2023)· 中
- 海外 VIDAA OS:Linux 系统,应用用 HTML5/CSS/JS 开发。[H16](https://spyro-soft.com/blog/media-and-entertainment/what-is-vidaa-os-a-comprehensive-guide-to-your-smart-tv-experience)(二手,2025)· 高(仅限海外)
- 矛盾说法:有 CSDN 问答称「国内多数中低端机型跑 Linux 的 VIDAA OS」,疑似自动生成内容,无出处,不采信。国行在售机型使用 Linux VIDAA:**未查到证据**。
- Vidda:与海信同用 VIDAA 系统,ADB 入口单列(见 3.2)。[X17](https://www.znds.com/tv-1251785-1-1.html)、[H13](https://www.znds.com/tv-1247333-1-1.html)· 中;Vidda 独有差异:**未查到**。

### 3.2 侧载

- 2017–2018:直接装提示 `此安装包未经过安全检测，存在风险，不能进行安装`;绕法是去掉 `.apk` 后缀,经「聚好用 → U 盘助手 → 打包安装程序」安装。[H9](https://jingyan.baidu.com/article/d3b74d6400d0c01f77e609ad.html)(二手,2018-05)、[H8](http://www.shafa.com/methods/haixin_116)(二手·沙发管家官方,2017-12)、[H19](https://news.mydrivers.com/1/575/575936.htm)(二手·当贝稿,2018-05)· 中
- 2019–2020(VIDAA 4+):设置 → 通用 → 商场模式「打开」→ 应用 → 全部 → 媒体中心里打开 U 盘 APK 安装;OLED/ULED 装完要关商场模式。[H7](https://www.znds.com/tv-1157856-1-1.html)(二手,2019-10 发、2020-11 编辑)· 中
- VIDAA 5+:部分版本把安装许可并到「通用设置 → 应用管理 → 安装权限」。[H15](https://erweng.com/posts/hisense-tv-third-party-apps/)(二手,2026-06)· 低–中
- **2023-08-09 起云端风控**:部分已装第三方应用(Kodi、电视家等)打开时提示 `检测到该应用存在安全性风险，建议您卸载使用`,客服称是海信统一配置;经当贝市场再打开、或长按主页键打开可绕过。[H3](https://www.right.com.cn/forum/thread-8301067-1-1.html)(二手,2023-08)· 中;2023 年底更新后「已装的禁止打开、未装的禁止安装」,用当贝市场海信版里的安装器能装。[H4](https://blog.csdn.net/tr33xm/article/details/135714415)(二手,2024-01)· 中;2024-03 用户称新系统连当贝都被禁,客服拒绝回滚。[H5](https://www.znds.com/tv-1246775-1-1.html)· 中;2024 年用户:提示危险应用无法打开,但从遥控器「喜好」键弹出的列表打开不受限;`adb install` 可装。[H6](https://www.cnblogs.com/backuper/p/18068033)· 中
- 矛盾说法:ZNDS 2023-09 汇总帖称海信安装第三方软件没有限制。[H18](https://www.znds.com/tv-1240172-1-1.html)· 低
- ADB 入口(按机型不同):新机 设置 → 声音 → 声音平衡,OK 与菜单键交替按 3–5 次进工厂模式,`To Fac` 改 `M` 后重启;老机在声音平衡上按 `1969`;Vidda 按「红绿蓝黄红」。[H13](https://www.znds.com/tv-1247333-1-1.html)(二手,2024-04)· 中;也可在「设置 → 关于 → 本机信息」连按菜单键进原生设置开开发者选项。[H14](https://blog.csdn.net/hnjcxy/article/details/125552250)(二手)· 中

### 3.3 能否设为默认桌面

- 2016:回帖称不能设默认,只能开当贝自启动、再用遥控切换。[H1](https://www.znds.com/tv-525140-1-1.html)(二手,2016-08)· 低(年代老)
- 2020(VIDAA 3/4,安卓 9):精简脚本删掉原桌面后,选第三方桌面「始终」为默认;脚本把「电视信号源」作为可选保留项。[H10](https://blog.csdn.net/weixin_36317800/article/details/117679909)· 中
- 2023:原桌面包名 `com.jamdeo.launcher`(智·汇中心),`pm uninstall --user 0` 后系统只剩一个桌面。[H11](https://www.cnblogs.com/dingshaohua/p/17297664.html)(二手,2023-04)· 中
- 2023:回帖称按 HOME 会弹桌面选择,选当贝即可,还能绕开「非法应用」提示。[H2](https://www.znds.com/tv-1237534-1-1.html)(二手,2023-08 回帖)· 中
- 系统设置里的「默认桌面」选项:**未查到**。`set-home-activity` 实测:**未查到**。

### 3.4 HOME 键与开机

- HOME 键:能弹系统桌面选择框(见 3.3)· 中
- 开机:当贝「开机自启动」只在待机唤醒后生效,断电后开机仍进聚好看(2023-12 回帖);2025-01 回帖称停电一次后开机卡蓝屏。[H2](https://www.znds.com/tv-1237534-1-1.html)· 中 / 低
- 2023-07 回帖:开了当贝自启动,再开机提示「非法应用」打不开。[H2](https://www.znds.com/tv-1237534-1-1.html)· 低–中

### 3.5 UnitedU 依赖的能力

- **输入源 / TIF**:**未查到**。推论:精简脚本把「电视信号源」当成可单独保留 / 删除的组件 [H10](https://blog.csdn.net/weixin_36317800/article/details/117679909),说明信号源是厂商独立组件,标准透传 intent 是否被它接收未知。
- **系统屏保选第三方**、**「显示在其他应用上层」**:**未查到**。
- **应用内安装更新**:**未查到**。推论:同时受「安装权限 / 商场模式」和 2023 年起云端风控影响。

### 3.6 典型硬件

- E8K(2023):联发科 MT9653(A73 四核 1.4GHz,Mali-G52),4GB + 64GB,评测称「64 位安卓 11」。[H12](https://zhuanlan.zhihu.com/p/635025521)· 中(「64 位」指 CPU 还是用户空间未核实)
- Vidda 硬件:**未查到**。

---

## 4. 创维 / 酷开(酷开系统 Coocaa OS)

### 结论

**不能当桌面,只能当普通应用。** 酷开的默认主页由厂商配置文件 `general_config.xml` 的 `DEFAULT_HOMEPAGE` 写死(`com.tianci.movieplatform/com.coocaa.homepage.vast.HomePageActivity`),原桌面声明的是私有 action `coocaa.intent.action.HOME`、category `android.intent.category.HOME.CC`;用户反馈第三方桌面启动后会弹回酷开主页,adb 禁用原桌面会卡开机屏,改默认主页要 root 或改 factory 分区。安装方面,历来有「应用圈」白名单,新系统(2023 起)在应用管理里有「允许安装未知来源应用」开关。安卓 8 及以下机型装不上 UnitedU。

### 4.1 系统名称与安卓版本

- 创维 G7200(8H87,2015 款,最后一版酷开 6.0 于 2018-06 发布):安卓 5.0,arm64-v8a。[C5](https://www.xfy9326.top/posts/g7200_8h87_root/)(二手)· 中
- 酷开 65P50:安卓 8,32 位 AOSP(armv7),A73 芯片跑 32 位。[C3](https://post.smzdm.com/p/aox8gew9/)(二手·用户评测,2022-01)· 中
- 创维 55A3(8A001):酷开 9 = 安卓 9,Amlogic T963。[C4](https://www.right.com.cn/forum/thread-8274149-1-1.html)(二手,2023-02)· 中
- 创维 7T87 G32P:酷开 9.00.220924,安卓 10。[C6](https://www.znds.com/tv-1254594-1-1.html)(二手,2024-11)· 中
- 酷开 M85(7T873):酷开系统 9.0.30626。[C2](https://blog.alliot.tech/post/fxxk-android-tv)(二手,2024-02)· 中
- 安卓 11 及以上机型:**未查到**。非安卓机型:**未查到**。

### 4.2 侧载

- 「应用圈」白名单:非应用圈来源的安装被拒,提示 `请使用应用圈安装`(2024)、`请到酷开应用圈下载安装该软件`(2018)。[C6](https://www.znds.com/tv-1254594-1-1.html)、[C11](https://wd.dangbei.com/wenda-1060-1-1.html)(二手·当贝官方问答)· 高(跨年一致)
- 机制:白名单在 `/data/data/com.tianci.appstore/shared_prefs/SaveSet.xml`。[C1](https://www.jianshu.com/p/01bca4ce7494)(二手,2021-12)· 中;厂商配置 `general_config.xml` 里有 `APP_SELF_INSTALL` 开关。[C4](https://www.right.com.cn/forum/thread-8274149-1-1.html)· 中;65P50 即使装了当贝市场也装不了第三方应用。[C3](https://post.smzdm.com/p/aox8gew9/)· 中
- 官方开口:设置 → 应用管理 → 「允许安装未知来源应用」(点「同意并开启」)。[C8](https://m.tech.china.com/tech/article/20230301/032023_1231425.html)(二手,2023-03)、[C9](https://tech.china.com/articles/20250516/202505161673507.html)(二手,2025-05)· 中;「实验室功能 → 帮助与客服 → 常见问题 → 开启 U 盘安装应用」,再到「我的应用 → 管理 → U 盘安装」。[C7](https://blog.csdn.net/yezhijing/article/details/128496085)(二手)· 中
- 2023-11:用户称酷开大版本更新后官方开放三方应用安装,应用圈不再锁权限。[C10](https://www.right.com.cn/forum/thread-8311751-1-1.html)(二手,2023-11)· 低–中
- ADB:设置 → 设备信息 → 遥控器「上上下下左右左右」进工厂模式 → ADB 开关(部分机型密码 123456)。[X17](https://www.znds.com/tv-1251785-1-1.html)· 中;另有「应用调试」应用(密码 69573028)走 U 盘安装的说法。[C14](https://blog.csdn.net/m0_46268055/article/details/135572963)· 低
- 坑(两说并列):有文章称酷开上不写 `android:installLocation` 会导致重启后已装应用「消失」,需写 `internalOnly`。[C13](https://blog.csdn.net/qq_35624842/article/details/113104635)· 低;但 Android 官方文档写明不声明时默认就是只装内部存储。[G13](https://developer.android.com/guide/topics/manifest/manifest-element)(一手)· 高 —— UnitedU 清单没写该属性,按 AOSP 默认应不受影响,需实机确认。

### 4.3 能否设为默认桌面

- 默认主页由厂商配置写死:`general_config.xml` 中 `<config name="DEFAULT_HOMEPAGE" value="com.tianci.movieplatform/com.coocaa.homepage.vast.HomePageActivity" />`,改它需要 root 改 `/system/pcfg/.../general_config.xml`(G7200)[C5](https://www.xfy9326.top/posts/g7200_8h87_root/),或刷 TWRP 改 factory 分区里的同名文件(55A3)[C4](https://www.right.com.cn/forum/thread-8274149-1-1.html)· 中–高(两代机型一致)
- 原桌面 Activity 的 intent-filter:`android.intent.action.MAIN`、`coocaa.intent.action.HOME`、`android.intent.category.HOME.CC`;作者称桌面替换需要 root。[C1](https://www.jianshu.com/p/01bca4ce7494)· 中。推论:HOME 键走私有 intent,不经标准 `CATEGORY_HOME` 解析,第三方桌面接不到 HOME。
- 用户反馈:系统桌面无法替换,当贝桌面启动后弹回主页(65P50,2022)。[C3](https://post.smzdm.com/p/aox8gew9/)· 中;启动器包名写在系统里,强行 adb 禁用 / 删除会卡开机屏(酷开 M85,2024)。[C2](https://blog.alliot.tech/post/fxxk-android-tv)· 中;当贝官方:冻结原桌面的方法「亲测创维盒子不支持」(2018)。[C12](https://wd.dangbei.com/wenda-1389-1-1.html)(二手·当贝官方)· 中
- 系统设置里的默认桌面选项:**未查到**。B 站有「创维 / 酷开开机直启第三方桌面」视频(2022、2023),页面无文字说明,内容无法核实。[C15](https://www.bilibili.com/video/BV1XY4y1z7Cg/)· 低

### 4.4 HOME 键与开机

- HOME 键回酷开主页;开机进 `DEFAULT_HOMEPAGE`(见 4.3)· 中
- 禁用原桌面后「我的应用」失灵(G7200)。[C5](https://www.xfy9326.top/posts/g7200_8h87_root/)· 低
- 已知绕法:只有 root / 改 factory 分区改 `DEFAULT_HOMEPAGE`(见 4.3);无 root 绕法:**未查到**。

### 4.5 UnitedU 依赖的能力

- 输入源 / TIF、系统屏保选第三方、「显示在其他应用上层」:**未查到**。
- 应用内安装更新:**未查到**。推论:受应用圈白名单 / `APP_SELF_INSTALL` / 未知来源开关控制。

### 4.6 典型硬件

- 65P50:A73 64 位芯片跑 32 位安卓 8(armv7),作者用 MX Player 测得 32 位下播放性能约降三成。[C3](https://post.smzdm.com/p/aox8gew9/)· 中
- 55A3:Amlogic T963(A35 四核 1.8GHz,Mali-G31)。[C4](https://www.right.com.cn/forum/thread-8274149-1-1.html)· 中
- G7200:arm64-v8a。[C5](https://www.xfy9326.top/posts/g7200_8h87_root/)· 中
- 内存:**未查到**。

---

## 5. 跨品牌背景:监管与第三方桌面的通行做法

- **当贝桌面的通行做法**(和 UnitedU 同一种机制):HOME 键弹选择框时选「始终」[G8](https://wd.dangbei.com/wenda-680-1-1.html)(二手·当贝官方,2018-04);厂商不让换时,用当贝桌面设置里的「开机自启动」开关,让电视开机自动进当贝桌面 [G9](https://news.mydrivers.com/1/644/644210.htm)(二手·当贝稿,2019-09)· 中。各品牌实际效果见上文:小米澎湃 OS 疑被禁、海信断电后失效、雷鸟无官方自启。
- **后台拉起 Activity 的限制**(一手):安卓 10 起限制后台启动 Activity,「用户授予了 `SYSTEM_ALERT_WINDOW`」是官方列出的豁免之一。[G3](https://developer.android.com/guide/components/activities/background-starts)· 高。UnitedU 已声明该权限。
- **TIF 的官方定位**(一手):AOSP 文档称 TV 应用是「第三方应用无法替代的系统应用」,HDMI 透传输入用 `TvContract.buildChannelUriForPassthroughInput(inputId)` 引用,切源时 TV Input Manager Service 给 TV 应用发 intent。[G4](https://source.android.google.cn/docs/devices/tv?hl=zh-cn)· 高。国产电视的「系统 TV 应用」是厂商私有的(小米是 `com.xiaomi.mitv.tvplayer`),它是否接标准透传 `ACTION_VIEW`:四个品牌都**未查到**。
- **第三方桌面切信号源普遍靠不住**:删掉原桌面后 AV/HDMI 切换跟着没了,因为切换界面在系统桌面里 [G10](https://www.znds.com/tv-571720-1-1.html)(二手,2016-10);2026 年仍有用户找不到能控制信号源的桌面,回帖认为取决于厂商系统 [G11](https://www.znds.com/tv-1271084-1-1.html)(二手,2026-06)· 中。矛盾说法并列:当贝桌面影视版(氧气桌面)在小米电视 4C 上带「信号源切换」(2021,后有回帖称该功能要付费)[G14](https://www.znds.com/tv-1195022-1-1.html)(二手,2021-04);当贝问答称当贝桌面设置里有信号源切换开关,网络波动时会消失 [G15](https://wd.znds.com/149573.html)(二手,日期不详)· 低。推论:当贝是按品牌逐个适配私有接口,不是走标准 TIF。
- **监管时间线**:
  - 2023-11:广电总局发布三项行业标准(含 GY/T 380-2023),要求**有线电视终端**提供「开机进入全屏直播」和「开机进入突出直播频道的交互主页」两种模式,默认前者;属推荐性标准。[G6](https://m.gmw.cn/2023-11/27/content_1303583757.htm)(二手·媒体,2023-11)· 中
  - 2026-06-15:广电总局发布 GY/T 428-2026《一体化电视专网电视业务应用技术要求和测量方法》,发布即实施。[G5](https://www.nrta.gov.cn/art/2026/6/17/art_113_73477.html)(一手)· 高(技术细节在附件 PDF,未读)
  - 2026-08/09 一体化电视集采:设备默认开机进入全屏直播,加电到直播画面 ≤35 s。[G7](https://www.tvoao.com/a/225085.aspx)(二手·行业媒体,2026-09)· 中。推论:运营商定制的一体化电视开机落点是直播,不是任何桌面,这是 UnitedU 新增的「开机不在桌面」场景。
  - 用户问答里把 TCL 禁装第三方应用归因于广电要求,无原文出处。[T21](https://wd.znds.com/4035.html)· 低

---

## 6. 对 UnitedU 的直接含义(推论,需实机验证)

1. **TCL / 雷鸟**:UnitedU 清单里的 HOME intent-filter 很可能让整个 APK 在 TCL 上装不上([T1](https://rocka.me/article/cursed-tcl-android-tv)、[T7](https://v2ex.com/t/945781)、[T8](https://www.tofuwine.cn/posts/be8d2dca/))。要覆盖 TCL,要么出一个不声明 HOME 的构建,要么只支持「adb + 关安装校验」的高级路线。
2. **小米新机 / 创维**:装上也当不了 HOME。若仍要「开机就看到 UnitedU」,只能学当贝做「开机自启」:收开机广播后拉起自己,安卓 10+ 靠用户授予的悬浮窗权限豁免后台启动限制([G3](https://developer.android.com/guide/components/activities/background-starts))。但澎湃 OS 有回帖称系统禁止第三方开机自启([X6](https://www.znds.com/tv-1257474-1-1.html),低)。
3. **信号源**:四个品牌都没查到标准 TIF 透传可用的证据;小米至少有可调用的私有入口([X14](https://www.52pojie.cn/thread-1983446-1-1.html))。
4. **能不能在厂商「我的应用」里找到 UnitedU**:UnitedU 只声明 `LEANBACK_LAUNCHER`,没有普通 `LAUNCHER`。国产桌面按哪个 category 列应用:**未查到**。Google 文档只说明没有 `LEANBACK_LAUNCHER` 的应用不会出现在 TV 界面([G2](https://developer.android.com/training/tv/start/start)),反过来国产桌面认不认它没有资料。HOME 被锁的品牌上,这决定用户能不能打开 UnitedU。
5. **minSdk 28**:安卓 8.1 及以下机型直接装不上(本轮查到:小米电视 4 系列 6.0.1、酷开 65P50 安卓 8、海信 VIDAA_TV 8.0.0)。
6. `uses-feature leanback required=true` 不影响侧载:系统安装时不检查 uses-feature([G1](https://developer.android.com/guide/topics/manifest/uses-feature-element),一手)。

---

## 7. 汇总表(品牌 × 6 点)

| 品牌 | ① 系统 / 安卓版本 | ② 侧载 | ③ 设为默认桌面 | ④ HOME 键 / 开机 | ⑤ 依赖能力(TIF / 屏保 / 悬浮窗 / 应用内更新) | ⑥ 硬件 |
|---|---|---|---|---|---|---|
| 小米 / Redmi | MIUI TV:安卓 6(电视 4 系列)→ 安卓 9 代;澎湃 OS:底层安卓 11。安卓 12+ / 非安卓:未查到 | 可:未知来源按应用授权 + ADB(每次开机要重开);云端黑名单只拦直播类;2020-05 曾短暂全禁 | 老固件可(HOME 选择框 / adb 卸原桌面);2021 起需 adb 禁 `com.mitv.tvhome`,可能被云端还原;澎湃 OS 禁后黑屏 | 2021 起 HOME 强制回 PatchWall;开机进 PatchWall | TIF:私有 `ExternalSourceActivity --ei input 23..`,标准路径未查到;屏保 `com.mitv.screensaver`,第三方可选性未查到;悬浮窗未查到;应用内更新需单独授权 | 64 位 CPU + 32 位系统;内存未查到 |
| TCL / 雷鸟 | 安卓 5(2017)…安卓 11(2022+),2023 起灵控桌面。安卓 12+ / 非安卓:未查到 | 受 `persist.tcl.*` 属性门控,adb `setprop` 可开;部分新固件疑失效(矛盾) | **桌面类 APK 安装被拦**(HOME 声明);系统只能切自家灵控 / 聚合;个别安卓 11 机型 adb 关校验可装 | 装上后 HOME 可进,开机仍进自家桌面;S48CTXX 锁 HOME | 全部未查到;老代码拒绝非自更新的安装 | X11H 4GB+128GB;32 位系统(低) |
| 海信 / Vidda | 国行安卓底层(5.1 / 8 / 9 / 11);海外 VIDAA OS 为 Linux。国行 Linux 机型:未查到 | 商场模式 / 安装权限 / ADB;2023-08 起云端风控拦截打开部分第三方应用 | 卸 `com.jamdeo.launcher` 后可选第三方(2020–2023);HOME 选择框可用 | HOME 弹选择框;断电冷启动回聚好看 | 全部未查到;信号源是独立组件(推论) | E8K:MT9653,4GB+64GB,「64 位安卓 11」 |
| 创维 / 酷开 | 酷开 6 = 安卓 5;65P50 = 安卓 8;酷开 9 = 安卓 9 / 10。安卓 11+ / 非安卓:未查到 | 应用圈白名单;新系统有「允许安装未知来源应用」与「帮助与客服」开口;2023-11 据称放开 | **不能**:`DEFAULT_HOMEPAGE` 写死,需 root / 改 factory | HOME 走私有 intent,回酷开主页;禁原桌面卡开机 | 全部未查到 | 65P50 32 位安卓 8;55A3 T963;G7200 arm64 |

---

## 8. 本轮查不到、需要实机验证的项

1. 四个品牌上 `adb shell cmd package set-home-activity com.uniteduone.launcher/.MainActivity` 是否生效(全部未查到实测)。
2. 标准 TIF:`TvInputManager.getTvInputList()` 是否列出 HDMI,`ACTION_VIEW` + `TvContract.buildChannelUriForPassthroughInput()` 能否切过去(全部未查到)。
3. 系统设置的屏保列表里会不会出现第三方 `DreamService`;`settings put secure screensaver_components` 是否被厂商屏保机制尊重(全部未查到)。
4. 「显示在其他应用上层」在电视设置里有没有入口;没有时 `adb shell appops set com.uniteduone.launcher SYSTEM_ALERT_WINDOW allow` 是否可用(全部未查到)。
5. 厂商「我的应用」是否列出只有 `LEANBACK_LAUNCHER` 的应用(全部未查到)。
6. TCL / 雷鸟:去掉 HOME intent-filter 的构建能否正常装;带 HOME 的构建 adb 关校验后能否装(按机型)。
7. 海信 2023 年起的云端风控是否会拦 UnitedU 本身、以及从 UnitedU 打开的第三方应用。

---

## 9. 来源清单

类型:一手 / 二手;日期为页面所示发布或主要讨论时间。

### 小米 / Redmi
- [X1] V2EX《小米电视最新系统已经不能换桌面了》,2021-07-10,二手·用户论坛 — https://www.v2ex.com/t/788781
- [X2] miuiver《小米电视去广告及更换第三方桌面操作方法》,日期不详(评论 2018–2024),二手·技术博客 — https://miuiver.com/ad-blocking-on-mitv/
- [X3] ZNDS《小米电视屏蔽强制升级，默认当贝桌面，几行代码就能搞定!》,2019-12-27,二手·论坛 — https://www.znds.com/tv-1161941-1-1.html
- [X4] ZNDS《小米EA55(L55M7-EA)怎么把系统桌面替换当贝桌面》,2023-03-30,二手·论坛 — https://www.znds.com/tv-1233298-1-1.html
- [X5] ZNDS《小米新版电视系统强制还原桌面的解决方案》,2024-04-18,二手·论坛 — https://www.znds.com/tv-1247860-1-1.html
- [X6] ZNDS《小米电视-澎湃OS-底层安卓11，安装当贝桌面后关机重启黑屏》,2025-01-20,二手·论坛 — https://www.znds.com/tv-1257474-1-1.html
- [X7] ZNDS《澎湃2.0系统更换默认桌面》,2025-05-14,二手·论坛 — https://www.znds.com/tv-1260979-1-1.html
- [X8] 网易《小米电视怎么自定义桌面菜单 解除小米使用第三方桌面限制》,2022-04-27,二手·媒体转载 — https://c.m.163.com/news/a/H60BJMOA08380019.html
- [X9] ZNDS《解决：小米电视提示“禁止通过非系统应用商店安装应用”》,2020-05-25,二手·论坛 — https://www.znds.com/tv-1174704-1-1.html
- [X10] 网易《小米电视安装第三方应用显示违规？这个电视app应用市场可以解决》,2023-09-20,二手·媒体 — https://www.163.com/dy/article/IF2OFEOS05520K4W.html
- [X11] ZNDS《小米电视盒子限制安装应用的原理和解决办法》,2024-08-23,二手·论坛 — https://www.znds.com/tv-1252116-1-1.html
- [X12] ZNDS《小米电视安装第三方应用提示禁止安装/安装失败解决方法》,2023-09-05,二手·论坛 — https://www.znds.com/tv-1239403-1-1.html
- [X13] 搜狐《揭秘小米电视：如何轻松安装第三方应用！》,2025-04-04,二手·媒体 — https://www.sohu.com/a/879807110_122004016
- [X14] 吾爱破解《求助分析小米电视外部信号源传入参数》,2024-11,二手·论坛 — https://www.52pojie.cn/thread-1983446-1-1.html
- [X15] CSDN《小米电视内置软件卸载必看的-系统应用对照表》,2024-12(页面所示),二手·博客 — https://blog.csdn.net/qq_42123284/article/details/135349127
- [X16] ZNDS《2022年了小米电视的系统居然是32位的》,2022-09-16(回帖至 2024),二手·论坛 — https://www.znds.com/tv-1223480-1-1.html
- [X17] ZNDS《【超全】2024各品牌电视打开ADB调试方法汇总》,2024-08-15,二手·论坛 — https://www.znds.com/tv-1251785-1-1.html
- [X18] 小米澎湃OS开发者平台《电视应用开发指南》,一手·开发者文档(内容为 MiBox1–4 / MiTV1–4 时代,未涉及桌面/侧载,本文未作为结论依据) — https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1329
- [X19] ZNDS 资讯《小米更新澎湃OS适配机型名单》,2024-06-24,二手·媒体 — https://n.znds.com/article/news/66059.html
- [X20] ZNDS《小米电视去广告、root精简、第三方桌面开机自动进入HDMI》,2021-08-31,二手·论坛 — https://www.znds.com/tv-1201974-1-1.html
- [X21] CSDN《小米电视/机顶盒怎么更换默认的小米桌面？》,2024-01(2024-12 修改),二手·博客 — https://blog.csdn.net/qq_42123284/article/details/135484544
- [X22] 雪球帖(仅搜索摘要,页面无法读取):称小米自 2022-08-26 起加大第三方应用安装限制 — https://xueqiu.com/1505526877/258147925
- [X23] 腾讯云开发者社区《小米电视全系通用刷机指南(2026实战版)》,2026,二手·内容农场风格 — https://tencentcloud.csdn.net/69ea754c54b52172bc6e46a2.html

### TCL / 雷鸟
- [T1] rocka.me《揭秘“太差了”智障电视》(2017 款 TCL,安卓 5.0.1 反编译),2020-02-05,二手·逆向分析 — https://rocka.me/article/cursed-tcl-android-tv
- [T2] 飞云算博客《给家里的TCL电视安装当贝桌面、刷精简系统》,2019-04-07,二手·博客 — https://blog.fyun.org/tcl-root.html
- [T3] ZNDS《新年新方法TCL电视无ROOT安装当贝桌面教程》,2021-02-25,二手·论坛 — https://www.znds.com/tv-1191620-1-1.html
- [T4] ZNDS《电视型号75S545C无法安装腾讯视频和当贝桌面？》,2022-03-28,二手·论坛 — https://www.znds.com/tv-1214305-1-1.html
- [T5] ZNDS《新买的雷鸟电视安装当贝桌面》,2022-11,二手·论坛 — https://www.znds.com/tv-1225751-1-1.html
- [T6] ZNDS《有大佬实现雷鸟电视开机直接启动第三方桌面了吗？》,2022-08,二手·论坛 — https://www.znds.com/tv-1222082-1-1.html
- [T7] V2EX《吐槽： TCL 电视不能修改启动桌面，让我大失所望》(含第 2、3 页),2023-06-05,二手·用户论坛 — https://v2ex.com/t/945781
- [T8] Tofuwine's Blog《TCL 电视安装第三方软件 & 卸载自带软件》,2024-02-20,二手·博客 — https://www.tofuwine.cn/posts/be8d2dca/
- [T9] 三月笔记《TCL雷鸟 电视安装当贝桌面 & 卸载自带软件》,2025-01-25,二手·博客 — https://blog.5625.cn/index.php/archives/46/
- [T10] 霖博客《TCL/雷鸟电视安装当贝桌面等第三方软件》,2026-02-08,二手·博客 — https://youthlin.com/20261889.html
- [T11] ZNDS《TCL电视利用adb调试获得第三方软件安装权限 解除禁止安装》,2021-02-02,二手·论坛 — https://www.znds.com/tv-1189881-1-1.html
- [T12] 博客园《TCL电视打开通过adb打开安装apk权限》,2025-02-04,二手·博客 — https://www.cnblogs.com/woaixingxing/p/18698547
- [T13] GitHub gaesa/TCLDebloater(2024-02-16 归档),二手·开源脚本 — https://github.com/gaesa/TCLDebloater
- [T14] ZNDS《TCL电视灵控桌面好用吗？》,2024-12-13,二手·论坛 — https://www.znds.com/tv-1255993-1-1.html
- [T15] ZNDS《TCL/雷鸟电视怎么从灵控桌面切换成旧桌面？》,2023-09-16,二手·论坛 — https://www.znds.com/tv-1239800-1-1.html
- [T16] IT之家评测室《TCL X11H 真实体验》,2024-07-18,二手·媒体评测 — https://digi.ithome.com/archiver/782/721.htm
- [T17] 当贝官网《TCL专版当贝市场》安装说明,日期不详,二手·当贝官方 — https://www.dangbei.com/tcl.html
- [T18] ZNDS 资讯《TCL电视禁止安装此来源应用怎么办？》,2018-12-06,二手·媒体 — https://n.znds.com/article/35272.html
- [T19] ZNDS《雷鸟电视怎样设置开机自启动某个app？》,2021-01-22,二手·论坛 — https://www.znds.com/tv-1189016-1-1.html
- [T20] ZNDS《TCL电视没法下载软件怎么办？TCL电视一键解决禁止软件安装》,2024-02-19,二手·论坛 — https://www.znds.com/tv-1245535-1-1.html
- [T21] ZNDS 问答《TCL智能电视机为什么突然禁止安装其他软件？》,约 2021,二手·问答 — https://wd.znds.com/4035.html

### 海信 / Vidda
- [H1] ZNDS《海信的电视当贝桌面怎么设置成默认桌面？》,2016-08-10,二手·论坛 — https://www.znds.com/tv-525140-1-1.html
- [H2] ZNDS《海信电视桌面自启动怎么设置？》,2023-07-11(回帖至 2025-01),二手·论坛 — https://www.znds.com/tv-1237534-1-1.html
- [H3] 恩山无线论坛《海信电视无法使用第三方软件求助》,2023-08-10,二手·论坛 — https://www.right.com.cn/forum/thread-8301067-1-1.html
- [H4] CSDN《海信还能看电视直播！禁止安装APK安装包的解决办法》,约 2024-01,二手·博客 — https://blog.csdn.net/tr33xm/article/details/135714415
- [H5] ZNDS《海信电视新系统屏蔽了很多第三方软件》,2024-03-20,二手·论坛 — https://www.znds.com/tv-1246775-1-1.html
- [H6] 博客园《海信安卓电视安装影视APP-新增直播和点播》,2024-03-12,二手·博客 — https://www.cnblogs.com/backuper/p/18068033
- [H7] ZNDS《海信VIDAA 4及以上系统U盘安装第三方软件、当贝市场方法！》,2019-10-24(2020-11 编辑),二手·论坛 — https://www.znds.com/tv-1157856-1-1.html
- [H8] 沙发管家《海信 VIDAA LED55V1UC 怎么安装第三方软件》,2017-12-19,二手·沙发管家官方教程 — http://www.shafa.com/methods/haixin_116
- [H9] 百度经验《海信电视无法安装第三方软件了？最新安装教程！》,2018-05-07,二手 — https://jingyan.baidu.com/article/d3b74d6400d0c01f77e609ad.html
- [H10] CSDN《海信电视精简教程，去除电视多余应用，换桌面！》(VIDAA 3/4,安卓 9),2020-03-31,二手·博客 — https://blog.csdn.net/weixin_36317800/article/details/117679909
- [H11] 博客园《海信电视精简系统》,2023-04-07,二手·博客 — https://www.cnblogs.com/dingshaohua/p/17297664.html
- [H12] 知乎专栏《85寸海信电视E8K深度测评》,2023,二手·用户评测 — https://zhuanlan.zhihu.com/p/635025521
- [H13] ZNDS《海信电视如何ADB去广告？2024海信电视ADB模式怎么开启？》,2024-04-03,二手·论坛 — https://www.znds.com/tv-1247333-1-1.html
- [H14] CSDN《海信电视开发者模式开启教程》,日期不详,二手·博客 — https://blog.csdn.net/hnjcxy/article/details/125552250
- [H15] erweng.com《海信电视安装第三方 APP 完整指南》,2026-06-24,二手·博客 — https://erweng.com/posts/hisense-tv-third-party-apps/
- [H16] Spyrosoft《What is VIDAA OS?》,2025-05(2025-11 更新),二手·业界博客 — https://spyro-soft.com/blog/media-and-entertainment/what-is-vidaa-os-a-comprehensive-guide-to-your-smart-tv-experience
- [H17] 界面新闻《操作系统知多少：你家的国产电视都是Android TV》,2018-03-12,二手·媒体 — https://www.jiemian.com/article/1983335.html
- [H18] ZNDS《【全】小米/海信/TCL/创维/索尼/雷鸟安装第三方软件方法》,2023-09-26,二手·论坛 — https://www.znds.com/tv-1240172-1-1.html
- [H19] 快科技《海信电视禁止安装第三方软件怎么办？当贝市场教你解决方法！》,2018-05-07,二手·媒体(当贝稿) — https://news.mydrivers.com/1/575/575936.htm

### 创维 / 酷开
- [C1] 简书《破解创维酷开电视安装第三方应用限制以及替换默认桌面应用突破笔记》,2021-12-23,二手·博客(CSDN 镜像 https://blog.csdn.net/u010042660/article/details/122187960) — https://www.jianshu.com/p/01bca4ce7494
- [C2] alliot 博客《浅浅的调教一下国产智障电视》(酷开 M85),2024-02-01,二手·博客 — https://blog.alliot.tech/post/fxxk-android-tv
- [C3] 什么值得买《创维电视避坑指南(酷开VS康佳)》(酷开 65P50),2022-01-08,二手·用户评测 — https://post.smzdm.com/p/aox8gew9/
- [C4] 恩山无线论坛《创维55A3(8A001)完美破解》,2023-02-03,二手·论坛 — https://www.right.com.cn/forum/thread-8274149-1-1.html
- [C5] xfy9326《一种老旧安卓电视通用Root与优化方案-以创维G7200_8H87为例》,日期不详(机型 2015、系统止于 2018-06),二手·博客 — https://www.xfy9326.top/posts/g7200_8h87_root/
- [C6] ZNDS《请教一下创维酷开9.00.220924系统如何用第三方市场装软件》,2024-11-06,二手·论坛 — https://www.znds.com/tv-1254594-1-1.html
- [C7] CSDN《创维智能电视(SKYWORTH)如何开启U盘安装(酷开系统)》,约 2022-12(页面示 2024-12),二手·博客 — https://blog.csdn.net/yezhijing/article/details/128496085
- [C8] 中华网《创维电视安装第三方应用最新图文教程，三步就能搞定》,2023-03-01,二手·媒体 — https://m.tech.china.com/tech/article/20230301/032023_1231425.html
- [C9] 中华网《实测有效！创维电视怎么安装第三方应用？三步搞定安装》,2025-05-16,二手·媒体 — https://tech.china.com/articles/20250516/202505161673507.html
- [C10] 恩山无线论坛《创维电视，酷开系统！大版本更新官方开放三方应用安装了！》,2023-11-10,二手·论坛 — https://www.right.com.cn/forum/thread-8311751-1-1.html
- [C11] 当贝问答《创维电视不能安装当贝，提示只能应用圈下载怎么办》,2018-08-10,二手·当贝官方 — https://wd.dangbei.com/wenda-1060-1-1.html
- [C12] 当贝问答《当贝桌面怎么在安卓机顶盒上设置默认自启动？》,2018-09-18,二手·当贝官方 — https://wd.dangbei.com/wenda-1389-1-1.html
- [C13] CSDN《创维酷开电视应用安装成功，设备重启后应用莫名消失的问题》,日期不详,二手·博客 — https://blog.csdn.net/qq_35624842/article/details/113104635
- [C14] CSDN《酷开TV安装第三方APP方式，打开adb调试》,日期不详,二手·博客 — https://blog.csdn.net/m0_46268055/article/details/135572963
- [C15] 哔哩哔哩《创维酷开电视安装第三方桌面软件，这样设置可以开机直启！》,2022-05-17,二手·视频(内容未能核实) — https://www.bilibili.com/video/BV1XY4y1z7Cg/

### 通用 / 官方
- [G1] Android Developers《`<uses-feature>`》,一手 — https://developer.android.com/guide/topics/manifest/uses-feature-element
- [G2] Android Developers《Get started with TV apps》,一手 — https://developer.android.com/training/tv/start/start
- [G3] Android Developers《Restrictions on starting activities from the background》,一手 — https://developer.android.com/guide/components/activities/background-starts
- [G4] AOSP《TV 输入框架》,一手 — https://source.android.google.cn/docs/devices/tv?hl=zh-cn
- [G5] 国家广播电视总局《关于发布〈一体化电视专网电视业务应用技术要求和测量方法〉广播电视和网络视听行业标准的通知》,2026-06-17,一手 — https://www.nrta.gov.cn/art/2026/6/17/art_113_73477.html
- [G6] 光明网《广电总局：有线电视终端开机应默认全屏直播》,2023-11-27,二手·媒体 — https://m.gmw.cn/2023-11/27/content_1303583757.htm
- [G7] 流媒体网《对机顶盒说“不” 一体化电视集采启动》,2026-09-04,二手·行业媒体 — https://www.tvoao.com/a/225085.aspx
- [G8] 当贝问答《当贝桌面是什么？怎么修改电视的默认桌面？》,2018-04-21,二手·当贝官方 — https://wd.dangbei.com/wenda-680-1-1.html
- [G9] 快科技《智能电视开机桌面自启动怎样设置？当贝市场分解教程》,2019-09-02,二手·媒体(当贝稿) — https://news.mydrivers.com/1/644/644210.htm
- [G10] ZNDS《当贝桌面如何切换到其它信号源？》,2016-10-27,二手·论坛 — https://www.znds.com/tv-571720-1-1.html
- [G11] ZNDS《请问当贝桌面有没有支持信号源的版本啊？》,2026-06,二手·论坛 — https://www.znds.com/tv-1271084-1-1.html
- [G12] V2EX《为什么 e900v22c 的电视盒子不能装 arm64-v8a 的 VLC》(电视多为 64 位芯片跑 32 位系统的讨论),2025-03,二手·用户论坛 — https://www.v2ex.com/t/1121419
- [G13] Android Developers《`<manifest>`》(installLocation 默认值),一手 — https://developer.android.com/guide/topics/manifest/manifest-element
- [G14] ZNDS《当贝桌面影视版(氧气桌面) 有信号源切换， 完美替换原当贝桌面。》,2021-04-17,二手·论坛 — https://www.znds.com/tv-1195022-1-1.html
- [G15] ZNDS 问答《当贝桌面没有信号源切换了》,日期不详,二手·问答 — https://wd.znds.com/149573.html
