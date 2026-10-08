# 首页「频道 / 内容推荐行」:机制、第三方桌面能否读取、代价

调研日期 2026-10-07。对象:UnitedU(`com.uniteduone.launcher`,侧载、非系统应用)。本文件只做调研,不改代码,不构成决定。

**怎么查的**

- **AOSP 源码直读**(`android14-release` 分支):TvProvider 的 `AndroidManifest.xml` 与 `TvProvider.java`、framework 的 `TvContract.java`、`TvInputManagerService.java`、`core/res/AndroidManifest.xml`。下文行为结论都落到具体文件。
- **官方文档**:developer.android.com 的「Channels on the home screen」「Watch Next」「Recommendations in Android N and earlier」、Engage SDK 文档与 FAQ、Android Developers Blog 2026-05。
- **开源客户端源码**(GitHub,`gh api` 读取):Kodi(`xbmc/xbmc`)、Jellyfin(`jellyfin/jellyfin-androidtv`)。
- **Projectivy**:Play 商店页(2026-07-12 更新版)+ XDA / 媒体二手转述。XDA 帖子与 APKMirror 页面本次 403,没能直读开发者原话。
- **实测**:本次**没有**实测。没有正在运行的模拟器(按约定不自起),也不碰电视。只引用 2026-10-02 兼容性调研里的 A95L 已有实测。

**标注规则**:同 `2026-10-02-tv-compat-part3-*`。每条证据后写「来源 · 一手/二手 · 把握度」。一手 = AOSP 源码、Google 官方文档 / 博客、开源项目源码、本项目实测;二手 = 媒体、论坛转述。把握度:高 = 源码或实测直接证实;中 = 官方说法未实测,或多方二手一致;低 = 单一二手或推断。「推断」两字表示没有直接证据。

---

## 0. 结论先行

1. **机制是什么**:Android 8.0 起,各应用把「频道」(一行)和「节目」(一张卡:标题、海报图、点击后的 intent、可选预览视频)**写进系统的 TvProvider 数据库**(`content://android.media.tv/`,表 `channel` / `preview_program` / `watch_next_program`);桌面只是**读这个库再画出来**。桌面不和各应用直接通信。8.0 之前的「通知式推荐」已废弃。
2. **我们能不能做**:**能读,但审批做不了,而且要用户授一个危险权限。**
   - 读:TvProvider 对持有 `android.permission.READ_TV_LISTINGS` 的调用方,放开所有 `searchable = 1` 的行(默认就是 1)。这个权限的保护级别是 **`dangerous`**,也就是运行时弹窗授予,或 `adb shell pm grant` 授予。**不需要 root,也不需要系统签名。** Projectivy 据二手资料走的就是这条路。
   - 不能做:写 `browsable`(频道上不上首页的审批开关)、通知应用「用户把这张卡移除了」,这两件事要 `signature|privileged` 级别的权限,侧载应用拿不到。
3. **代价**:定位上不冲突——Gordon 2026-10-07 裁定:「零推荐」指的是默认不推、能关掉,不是用户想开也开不了;做成**默认关闭、用户主动开启**的可选项即可(§9 的「不做」是 1.0 的范围)。剩下要注意的是:各应用写进频道的内容由应用自己的算法挑,常含推广位,我们只能整行开关。技术上还要:申请一个读得到全部电视节目单 / 观看记录的敏感权限;联网加载大量海报图(A95L 是 32 位 armv7);在「不用可滚动容器」铁律下自己做横向位移;国行适用性:APK 静态分析(§7)显示云视听极光、CIBN 酷喵、云视听小电视写频道(极光受腾讯云端开关控制),奇异果 / 芒果 / 咪视界没发现;**继续观看在国内基本是空的**;海外 Kodi / Jellyfin / Emby / SmartTube / Netflix 等都写。模拟器实测(§6):没人审批的频道照样读得到。A95L 上实际有没有数据仍待电视实测(放最后)。

---

## 1. 机制

### 1.1 Android 8.0+:TvProvider 里的三张表

1. **频道(`channel` 表,类型 `TYPE_PREVIEW`)**:一个应用一行或多行。字段有名字、logo、`app_link_intent_uri`(点 logo 跳哪)、`browsable`(是否在首页显示)。
2. **预览节目(`preview_program` 表)**:频道里的每张卡。关键列:
   - `title`、`poster_art_uri`(海报,通常是 http(s) 地址)、`poster_art_aspect_ratio`;
   - `intent_uri`(用户点卡片时桌面要发的 intent,序列化成 URI);
   - `preview_video_uri`(聚焦停留时自动播放的预览视频,可选);
   - `searchable`(默认 1,决定别的应用能不能读)、`browsable`(默认 1)。
3. **继续观看(`watch_next_program` 表)**:「Play Next / 继续观看」行。这一行由**系统**建、由**系统**维护,应用只往里加节目,不能移动、隐藏或删除这一行。

证据:

- URI 路径 `channel` / `preview_program` / `watch_next_program`、两张节目表的建表语句(`searchable` / `browsable` 都 `DEFAULT 1`,频道的 `browsable` `DEFAULT 0`):[C2](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android14-release/src/com/android/providers/tv/TvProvider.java) · 一手 · 高
- 列名 `intent_uri` / `poster_art_uri` / `preview_video_uri`,以及 `browsable` / `searchable` 的语义注释:[C3](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/media/java/android/media/tv/TvContract.java) · 一手 · 高
- 「The home screen sends the Uri stored in the INTENT_URI attribute of a program to the app when the user selects a program」;Watch Next「The system creates and maintains this channel」「your app cannot move, remove, or hide the Watch Next channel's row」:[C5](https://developer.android.com/training/tv/discovery/recommendations-channel)、[C6](https://developer.android.com/training/tv/discovery/watch-next-add-programs) · 一手 · 高
- 应用侧用 `androidx.tvprovider:tvprovider`(`PreviewChannelHelper`、`PreviewProgram`、`WatchNextProgram`)写入,声明 `com.android.providers.tv.permission.WRITE_EPG_DATA`(`normal` 级):[C5](https://developer.android.com/training/tv/discovery/recommendations-channel)、[C1](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android14-release/AndroidManifest.xml) · 一手 · 高

### 1.2 默认频道、`requestChannelBrowsable` 与审批

1. 应用建的**第一个频道是默认频道**:应用调 `TvContract.requestChannelBrowsable(context, channelId)`,文档写「The first request from a package is guaranteed to be approved」。
2. 系统(`TvInputManagerService.requestChannelBrowsable`)做的事很简单:**把受保护广播 `ACTION_CHANNEL_BROWSABLE_REQUESTED` 发给所有声明了这个接收器的包**。真正把 `browsable` 改成 1 的,是接收方桌面。
3. 第二个及以后的频道,应用要 `startActivityForResult(ACTION_REQUEST_CHANNEL_BROWSABLE)`,由系统(实际是桌面)弹「是否把这个频道加到首页」对话框。
4. **改 `browsable` 列要 `ACCESS_ALL_EPG_DATA`**:TvProvider 发现非特权调用方写这一列,直接抛 `SecurityException("Not allowed to access Channels.COLUMN_BROWSABLE")`。

这意味着:**「审批」这一环只有系统级桌面做得了。**第三方桌面能收到 `CHANNEL_BROWSABLE_REQUESTED` 广播(这是任何应用都能声明的接收器),但改不了库;也没法承接 `ACTION_REQUEST_CHANNEL_BROWSABLE` 的对话框并把结果真正落库。

证据:

- `requestChannelBrowsable` 的注释与实现(转给 `TvInputManager`):[C3](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/media/java/android/media/tv/TvContract.java) · 一手 · 高
- `TvInputManagerService.requestChannelBrowsable`:`queryBroadcastReceivers(ACTION_CHANNEL_BROWSABLE_REQUESTED)` 后逐包定向发送:[C4](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/tv/TvInputManagerService.java) · 一手 · 高
- `CHANNEL_BROWSABLE_REQUESTED`、`PREVIEW_PROGRAM_BROWSABLE_DISABLED`、`WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED`、`PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT` 都是 `protected-broadcast`:[C7](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/res/AndroidManifest.xml) · 一手 · 高
- 非特权调用方写 `browsable` 抛异常(`TvProvider.java` 约 2215–2226 行):[C2](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android14-release/src/com/android/providers/tv/TvProvider.java) · 一手 · 高
- 「All other channels you create must be selected and accepted by the user」;`ACTION_REQUEST_CHANNEL_BROWSABLE` 由「The system displays a dialog」:[C5](https://developer.android.com/training/tv/discovery/recommendations-channel) · 一手 · 高

### 1.3 `ACTION_INITIALIZE_PROGRAMS`

- 应用装好后,**由桌面**(文档原话「The home screen sends」)向它发 `android.media.tv.action.INITIALIZE_PROGRAMS`,提示它去建频道、填节目。这个广播不在 `protected-broadcast` 名单里,开发时可以用 `adb shell am broadcast -a … -n <包>/<接收器>` 手动发。
- 推断:第三方桌面也可以向新装的应用定向发它(显式广播),相当于「提醒应用来写数据」。但很多应用是在自己被打开、或定时任务里同步频道,不依赖这个广播。
- 证据:[C3](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/media/java/android/media/tv/TvContract.java)(注释:只在 `FEATURE_LEANBACK` 设备上发)、[C5](https://developer.android.com/training/tv/discovery/recommendations-channel)、[C7](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/res/AndroidManifest.xml) · 一手 · 高(「谁发」);推断部分 · 低

### 1.4 用户「移除这张卡」怎么回传

- 系统桌面把某张卡的 `browsable` 置 0 后,通过 `TvInputManager.sendTvInputNotifyIntent` 给应用发 `PREVIEW_PROGRAM_BROWSABLE_DISABLED` 等广播,应用据此删数据。
- `sendTvInputNotifyIntent` 要求 `android.permission.NOTIFY_TV_INPUTS`(`signature|privileged`)。**第三方桌面做不了「移除并通知应用」,只能在自己这边本地藏起来。**
- 证据:[C4](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/tv/TvInputManagerService.java)(`sendTvInputNotifyIntent` 的权限检查)、[C7](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/res/AndroidManifest.xml)(`NOTIFY_TV_INPUTS` 的保护级别)· 一手 · 高

### 1.5 点击、海报、预览视频

- **点击**:读 `intent_uri`,`Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)` 后 `startActivity`。Jellyfin 就是用 `PreviewProgram.Builder.setIntent(Intent(context, StartupActivity::class.java)…)` 写入的。频道 logo 走 `app_link_intent_uri`。[C5](https://developer.android.com/training/tv/discovery/recommendations-channel)、[C10](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/integration/LeanbackChannelWorker.kt) · 一手 · 高
- **海报**:`poster_art_uri` 一般是应用自己 CDN 上的 http(s) 图片,桌面要自己下载、解码、缓存。频道 logo 存在 TvProvider 里,通过 `channel/#/logo` 读,同样受下文权限过滤。[C2](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android14-release/src/com/android/providers/tv/TvProvider.java)(`openLogoFile`)· 一手 · 高
- **预览视频**:`preview_video_uri`,桌面在焦点停留时自己起播放器播放。可选功能。[C3](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/media/java/android/media/tv/TvContract.java) · 一手 · 高

### 1.6 旧机制:8.0 之前的通知式推荐

- 7.1(API 25)及以前:应用发 `CATEGORY_RECOMMENDATION` 通知 / `RecommendationService`,全部挤在一个「推荐行」。官方文档写明只用于 API 25 及以下,8.0+ 必须用频道;旧应用在 8.0 上会被自动转成一个频道。
- 我们 minSdk 28,**不用管这一套**。
- 证据:[C8](https://developer.android.com/training/tv/discovery/recommendations-row)、[C9](https://developer.android.com/training/tv/discovery/recommendations) · 一手 · 高

### 1.7 Google TV 的新路线:Engage SDK

- Google TV 的「继续观看」新集成一律走 **Engage SDK**(Play 服务里的服务,不是 TvProvider)。官方博客 2026-05:**「The legacy Watch Next API … will lose support in the 2nd half of 2027」**。
- Engage FAQ:「Engage will be backward compatible on all Android TV devices that support the Watch Next API」。Engage 支持的界面包括 Google TV、Android TV(仅设备本地)、Google TV 手机应用等,都依赖 Google 账号 / Play 服务。Engage 的 TV 库仍声明 `WRITE_EPG_DATA`(推断:在 Android TV 设备上它会顺带写本地的 Watch Next 表)。
- **对我们的含义**:
  - Engage 的数据进 Google 的服务,第三方桌面读不到。
  - 2027 下半年之后,大应用可能只在 Google TV 上走 Engage,TvProvider 里的「继续观看」会变少。
  - 国行无 GMS 的电视上 Engage 本来就不可用(推断:`isServiceAvailable` 依赖 Play 服务)。
- 证据:[C11](https://android-developers.googleblog.com/2026/05/increase-google-tv-app-discovery.html)、[C12](https://developer.android.com/guide/playcore/engage/faq)、[C13](https://developer.android.com/guide/playcore/engage/tv)、[C14](https://developer.android.com/guide/playcore/engage/tv/continue-watching/client) · 一手 · 高(时间表与兼容表述);对我们的含义 · 推断 · 中

---

## 2. 权限:第三方桌面能不能读到别家的频道(核心)

### 2.1 四个相关权限的保护级别(AOSP 14 TvProvider 清单原文)

| 权限 | 保护级别 | 作用 | 侧载桌面能拿到吗 |
|---|---|---|---|
| `com.android.providers.tv.permission.READ_EPG_DATA` | `normal` | 读**自己**的数据。注释写「@deprecated No longer enforced」 | 能,但没用 |
| `com.android.providers.tv.permission.WRITE_EPG_DATA` | `normal` | 写自己的数据 | 能(发布方用的) |
| **`android.permission.READ_TV_LISTINGS`** | **`dangerous`** | 注释:「Allows an application to read (but not write) all the TV listings」 | **能:运行时弹窗,或 `adb shell pm grant`** |
| `com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA` | `signature\|privileged` | 读写全部数据,包括改 `browsable` | 不能(要系统签名或预装在 priv-app 并进白名单) |
| `com.android.providers.tv.permission.ACCESS_WATCHED_PROGRAMS` | `signature\|privileged` | 系统维护的观看历史表(`watched_program`) | 不能;做推荐行也用不到 |

证据:[C1](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android14-release/AndroidManifest.xml) · 一手 · 高。另:`TvContract.PERMISSION_READ_TV_LISTINGS` 常量本身是 `@hide`,但权限是在 TvProvider 清单里正式声明的,应用直接写字符串就能申请:[C3](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/media/java/android/media/tv/TvContract.java) · 一手 · 高

### 2.2 TvProvider 实际怎么过滤(`createSqlParams`)

TvProvider 对**没有** `ACCESS_ALL_EPG_DATA` 的调用方:

1. **不准带 `selection`**:非空就抛 `SecurityException("Selection not allowed for …")`。过滤只能用 URI 参数(`?package=`、`?channel=`、`?browsable_only=true`、`?preview=true`)或读回来自己筛。
2. **查询时持有 `READ_TV_LISTINGS`**:`WHERE package_name = <自己> OR searchable = 1`。三张表(频道、预览节目、继续观看)都有 `searchable` 列且默认 1,**所以绝大多数应用写的数据都读得到**,除非应用特意把 `searchable` 设成 0。
3. **不持有**:`WHERE package_name = <自己>`,只能看到自己的。
4. 频道 logo(`openLogoFile`)同样按这套规则过滤。
5. `call()`(如 `METHOD_GET_COLUMNS`)对非 `ACCESS_ALL_EPG_DATA` 调用方直接返回 null。不影响读数据。

证据:`TvProvider.java` 的 `createSqlParams`(约 1870–1905 行)、`openLogoFile`(约 2265 行)、`call`(约 1313 行):[C2](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android14-release/src/com/android/providers/tv/TvProvider.java) · 一手 · 高。`searchable` 的语义(「1 = its columns can be read by other applications」):[C3](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/media/java/android/media/tv/TvContract.java) · 一手 · 高

另外,TvProvider 的 `<application android:forceQueryable="true">`,Android 11+ 的包可见性限制不挡它:[C1](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android14-release/AndroidManifest.xml) · 一手 · 高

### 2.3 所以第三方桌面的能力边界

| 想做的事 | 侧载桌面 + `READ_TV_LISTINGS` |
|---|---|
| 读所有应用的频道、预览节目、继续观看 | **能**(`searchable = 1` 的部分,即默认全部) |
| 顺带读到电视调谐器的直播节目单(`program` 表) | 也能(同一个权限,范围更大,见 §4 隐私) |
| 判断某频道是否已被系统桌面「批准」 | 能读 `browsable`,但只有系统桌面会把它改成 1 |
| 自己批准频道、承接「加到首页」对话框 | **不能**(写 `browsable` 要 `ACCESS_ALL_EPG_DATA`) |
| 用户移除一张卡并通知应用 | **不能**(要 `NOTIFY_TV_INPUTS`);只能本地隐藏 |
| 读系统观看历史 `watched_program` | 不能(要 `ACCESS_WATCHED_PROGRAMS`) |
| 读 Google TV 的 Engage「继续观看」 | 不能(数据不在 TvProvider) |

**整体判断**:**不需要 root、不需要系统签名、不需要辅助 APK**,但需要用户授予一个危险权限。审批和回传两环做不了,桌面只能自己决定显示哪些频道(例如:显示所有 `TYPE_PREVIEW` 频道,不管 `browsable`;或者只显示 `browsable = 1` 的,但在没有系统桌面批准的设备上这会是空的)。· 由 C1–C4 源码推出 · 高

### 2.4 Projectivy 怎么做的

- Play 商店页(2026-07-12 更新)只写「Organize your apps into categories and channels」,没说权限细节:[C15](https://play.google.com/store/apps/details?id=com.spocky.projengmenu) · 一手 · 中
- 二手资料一致说法:Projectivy 申请 `READ_TV_LISTINGS` 来显示「channels / watch next」,用户可以用 `adb shell pm grant <包> android.permission.READ_TV_LISTINGS` 授予;「正在播放」另需通知使用权。另有用户反馈 Netflix 只在 Android TV 上有频道,Google TV 上只出现在 watch next:[C16](https://www.flatpanelshd.com/guide.php?subaction=showfull&id=1754640308)、[C17](https://xdaforums.com/t/app-android-tv-projectivy-launcher.4436549/)(搜索摘要,原帖 403 未直读) · 二手 · 中
- 与 §2.2 的源码完全吻合:`READ_TV_LISTINGS` 是 `dangerous`,`pm grant` 对 dangerous 权限有效。**结论:Projectivy 走的就是「危险权限 + 读 searchable 行」这条路,没有用特权。** · 推断(源码 + 二手相互印证) · 中高
- 实测线索:2026-10-02 的调研在 A95L 上看到 `com.spocky.projengmenu` 已装(part3 附录 A 的 HOME 候选列表)。Gordon 的电视上本来就有一个现成对照。

---

## 3. 适用性:谁在写数据

| 应用 / 场景 | 状态 | 证据 |
|---|---|---|
| Kodi | 写:建「建议」默认频道 + 按订阅建频道,预览节目类型如 `TYPE_ALBUM`。**频道不是 `browsable` 时它会删掉节目**(「If the channel is not browsable, the programs will be removed」) | [C18](https://github.com/xbmc/xbmc/tree/master/tools/android/packaging/xbmc/src/channels) · 一手 · 高 |
| Jellyfin 官方 Android TV 客户端 | 写:默认频道(`requestChannelBrowsable`)+ Watch Next(继续观看 / 下一集) | [C10](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/integration/LeanbackChannelWorker.kt) · 一手 · 高 |
| YouTube TV、Netflix、Prime Video、Apple TV+ 等国际大应用 | 在 Android TV(非 Google TV)上历来写频道;Google TV 上各家情况不一(Netflix 被报只进 watch next) | [C19](https://9to5google.com/?p=351313)(2020,YouTube TV 加频道)、[C16](https://www.flatpanelshd.com/guide.php?subaction=showfull&id=1754640308) · 二手 · 中低 |
| Plex / Emby | 未查到一手证据 | — |
| 国内 TV 版(云视听极光 / 银河奇异果 / CIBN 酷喵 等) | **推断不写**:国内电视桌面(当贝、厂商自研)不读 TvProvider,应用没有动力适配 | 推断 · 低,待实测 |
| 国行 A95L 系统本身 | **有 TvProvider**:`content gettype content://android.media.tv/passthrough/x` 返回 `vnd.android.cursor.item/channel`。预装桌面是当贝(`com.dangbei.TVHomeLauncher`),不是 Google 的桌面 | `2026-10-02-tv-compat-part3-*` 附录 A · 一手实测 · 高 |

**关键连带问题(推断,把握度中)**:Kodi 这类「频道不 `browsable` 就不填节目」的应用,依赖系统桌面响应 `CHANNEL_BROWSABLE_REQUESTED` 把默认频道改成 1。A95L 预装的是当贝桌面,它是否声明这个接收器、是否持有 `ACCESS_ALL_EPG_DATA`,都未知。如果没人处理,**这些应用在 A95L 上建了频道也是空的**,我们读到的只有频道壳。Jellyfin 这类不看 `browsable` 的应用则照写不误。

**Google TV 设备**:Google TV 桌面的首页以 Google 自己的聚合推荐为主,应用频道的地位比老 Android TV 桌面低。官方「继续观看」正在迁到 Engage(§1.7),2027 下半年旧 Watch Next API 失去支持。所以 TvProvider 里的数据量**在缩小,不在增长**。· 一手(时间表)+ 推断(趋势)· 中

---

## 4. 代价与取舍

1. **产品定位(Gordon 2026-10-07 裁定:不冲突,前提是默认关闭、用户主动开启)**:原文评估如下,保留作背景——`docs/DESIGN-unitedu-open-source.md` 口号「零广告、零推荐,只有你放上去的应用」,§9「不做」里明确有「内容推荐行」。频道内容由各应用的算法决定,可能夹带推广、付费内容、新片宣传,我们控制不了单张卡(只能整行开关或本地隐藏)。就算做成默认关闭的可选项,也要改设计定稿,并改 README 的定位文案。
2. **权限与隐私**:`READ_TV_LISTINGS` 读的是**整个**电视节目库:所有应用的推荐、继续观看(等于观看记录),还有调谐器的直播节目单。授权时系统会弹一个陌生的危险权限弹窗。2025 年以来的开源桌面用户对此敏感,需要在 README 和设置页说明用途,并且只在用户主动开启时申请。
3. **审批缺口导致的体验不一致**:我们不能批准频道,也不能通知应用「卡片已移除」(§2.3)。必须自己维护「哪些频道显示、哪些卡片隐藏」的本地状态,而应用那边以为卡片还在。在没有系统桌面审批的设备(很可能包括 A95L)上,部分应用的频道会是空的。
4. **性能(A95L 是 32 位 armv7,按键帧 UI 线程本来就紧)**:每行十几张 http 海报 → 要引入图片加载、磁盘缓存、降采样(现在项目没有网络图片库)。首页多出若干行后,按「不用可滚动容器」铁律(CLAUDE.md 第 1 条),**行内所有卡片都会被组合**,必须给每行封顶(例如 ≤ 10–15 张)。纵向位移也要重新算。预览视频(`preview_video_uri`)在这台电视上更贵,建议不做。
5. **焦点账本**:每加一种可聚焦行,都要按七条铁律补账本(看门狗、目标与持有者分开、`ON_PAUSE` 冻结)。数据是异步刷新的(应用随时增删节目)→ 正是铁律 3 里「卡片节点被销毁 → 焦点消失」的高发场景。
6. **维护面**:要监听 TvProvider 变化(`ContentObserver`)、解析 `intent_uri`(格式各家不一,解析失败要有兜底)、处理应用卸载后残留的行(TvProvider 自己会在 `PACKAGE_FULLY_REMOVED` / `PACKAGE_CHANGED` 时清理)。还有 Engage 迁移带来的数据缩水趋势(§1.7)。
7. **收益面(公平地讲)**:海外 Android TV(非 Google TV)用户、Kodi / Jellyfin 用户能在首页直接点「继续观看」。这是 Projectivy 被称为「probably the only TV launcher supporting」的差异点之一(二手)。对国内用户收益很可能接近零(§3,待实测)。

---

## 5. 未验证 / 待实测

> 2026-10-07 起第 1、2 条已在模拟器上做完,见 §6;电视实测按 Gordon 要求放最后。

都只读,不按键、不装产品包。电视上的项目须 Gordon 同意后再做。

1. **模拟器(`unitedu-tv` = 带 GMS 的 Android TV,`unitedu-gtv` = Google TV)**:
   - `adb shell pm list packages | grep providers.tv` → 预期 `com.android.providers.tv`
   - `adb shell dumpsys package com.android.providers.tv | grep -A2 "READ_TV_LISTINGS\|ACCESS_ALL_EPG_DATA"` → 核对 `prot=dangerous` / `signature|privileged`(验证 §2.1 在 Google 镜像上没被改)
   - `adb shell content query --uri content://android.media.tv/channel --projection _id:package_name:type:browsable:searchable:display_name`、`…/preview_program --projection channel_id:title:poster_art_uri:intent_uri`、`…/watch_next_program`。**注意**:shell(uid 2000)不一定持有 `READ_TV_LISTINGS`,结果空不等于没数据。可靠的做法是一个只申请 `READ_TV_LISTINGS` 的探针 APK(放 scratchpad,不进仓库)+ `pm grant`,对比授权前后的行数。
   - `adb shell cmd package query-receivers --brief -a android.media.tv.action.CHANNEL_BROWSABLE_REQUESTED` → 看哪个桌面承接审批(预期 Google 桌面)
   - 在一台装了 Kodi / Jellyfin 的模拟器上,验证 §2.2 的过滤结论(能读到别家行、带 selection 抛异常)。
2. **运行时弹窗**:`READ_TV_LISTINGS` 不属于任何平台权限组。Android 14 上 `requestPermissions` 会不会正常弹窗、弹出来写什么、TvSettings 的「应用权限」里能不能事后撤销,都未实测。
3. **A95L(需 Gordon 点头)**:`dumpsys package com.android.providers.tv` 看保护级别;`cmd package query-receivers … CHANNEL_BROWSABLE_REQUESTED` 看当贝 / 索尼有没有承接;`dumpsys package com.spocky.projengmenu | grep READ_TV_LISTINGS` 看 Projectivy 是否已获授权;如已获授权,让 Gordon 肉眼看 Projectivy 的频道行在这台电视上有没有内容。这一条最能回答「对国行用户值不值」。
4. **国内应用是否写频道**:在 A95L 上读 `channel` 表的 `package_name` 分布(需要一个有权限的读者,比如 Projectivy 已授权时的界面,或探针)。
5. Plex / Emby / YouTube / Netflix 在 2026 年的当前状态:本次没找到一手证据。

---

## 6. 模拟器实测(2026-10-07,探针 APK)

> 本节的结果优先于上文 §1–§5 的文献推断;末尾列了与上文不一致之处。

环境:`unitedu-gtv`(emulator-5580,Android 14,Google TV,launcherx,已登 Google 账号)、`unitedu-tv`(emulator-5582,Android 14 TV,tvlauncher + `com.google.android.tvrecommendations`)。两台 HOME 角色持有者都是 `com.uniteduone.launcher`。
探针(不进仓库,放过会话 scratchpad 的 `channel-probe/`)(`pub/` 发布方 `com.probe.publisher`,`rd/` 读取方 `com.probe.reader`,`build.sh` javac + d8 + aapt2 + zipalign + apksigner,`run.sh` 拉起并抓 `PROBE` 日志)。读取方声明 `READ_TV_LISTINGS` + `READ_EPG_DATA`(第二轮起加 `WRITE_EPG_DATA`),并注册了 `CHANNEL_BROWSABLE_REQUESTED` 接收器。
截图:`docs/screenshots/channels/01–06*.jpg`。已清理:两个探针都卸载,`tvrecommendations` 已重新启用(接收器回来了),两台模拟器已关。`unitedu-gtv` 上原有的 `com.uniteduone.iconprobe` 不是我装的,没动。

### 6.1 权限保护级别(研究文档 §2.1)
- 做了什么:两台都跑 `dumpsys package com.android.providers.tv`。
- 结果:与 AOSP 一致,Google 镜像没改:`READ_TV_LISTINGS` = dangerous,`READ_EPG_DATA` / `WRITE_EPG_DATA` = normal,`ACCESS_ALL_EPG_DATA` / `ACCESS_WATCHED_PROGRAMS` = signature|privileged。
- 证据:`Permission [android.permission.READ_TV_LISTINGS] … prot=dangerous`(两台相同)。launcherx 持有 `ACCESS_ALL_EPG_DATA: granted=true`。

### 6.2 授权前 vs 授权后能读到什么(§2.2 核心)
- 做了什么:发布方建频道 A(searchable=1,3 个节目,其中 1 个 searchable=0)、频道 B(searchable=0,1 个节目 searchable=1)、1 条 watch next、两个 logo;读取方在授权前 / 后各查一遍。
- 结果:
  - 未授权(`READ_EPG_DATA` 已自动授予也没用):三张表全是 0 行。
  - 授权后:读到别家的频道、预览节目(含 `poster_art_uri`、`intent_uri`)、watch next,还读得到频道 logo(`openInputStream(buildChannelLogoUri)` 519 字节)。
  - `searchable=0` 的频道 B 和 searchable=0 的那个节目**读不到**;但频道 B 下面那个 searchable=1 的节目**照样读得到**(过滤是逐行的,节目不随频道隐藏)。
  - unitedu-tv 上还读到系统的 `com.google.android.tvrecommendations`「Apps Spotlight」频道。
- 证据(emulator-5582,`pm grant` 后):`rd: channel rows=2` / `_id=1 package_name=com.google.android.tvrecommendations … display_name=Apps Spotlight browsable=1` / `_id=2 package_name=com.probe.publisher … browsable=1`;`rd: preview_program rows=3`(_id 1、2、4,缺 3 = searchable=0 那个);`rd: watch_next_program rows=1`;`rd: logo ch=1 bytes=3645`。
- 附带:`projection = null` 查 channel 会抛 `SQLiteException: Unable to convert BLOB to string`(我的探针逐列 `getString` 撞上 blob 列),实际代码要显式写投影。

### 6.3 运行时弹窗与撤销(§5.2)
- 做了什么:`requestPermissions(READ_TV_LISTINGS)`,截图;在 Settings 里撤销;再请求多次。
- 结果:
  - **会正常弹系统窗**,文案「Allow ProbeReader to read all TV listings available on your device?」+「You can change this later in Settings > Apps」。Allow → 立刻读得到;Don't allow → 0 行。
  - 撤销可行:应用权限页里它不在任何分组,收在「Additional permissions → read all TV listings」,点进去是 Allow / Don't allow 单选。撤销后读取立刻回到 0 行。
  - 拒绝过后再请求仍会弹,第二次起多出「Deny and don't ask again」。
  - 应用权限页底部有「Unused apps: Remove permissions and free up space」开关(默认开):长期不用的应用会被自动收回权限。桌面天天用,一般不受影响,但要知道有这条。
- 证据:`rd: permResult android.permission.READ_TV_LISTINGS = GRANTED`(gtv)/ `= DENIED`(tv);撤销后 `READ_TV_LISTINGS: granted=false, flags=[ USER_SET]`;截图 01、02、04、05、06。

### 6.4 带 selection 查询
- 做了什么:`query(channel, …, "type=?", …)`。
- 结果:两台都抛异常,与 §2.2 一致。URI 参数过滤可用:`?package=com.probe.publisher` 返回 1 行,`?browsable_only=true` 生效。
- 证据:`SecurityException: Selection not allowed for content://android.media.tv/channel`。
- 补充:**`preview_program?browsable_only=true` 看的是节目自己的 `browsable`(默认 1),不看所属频道**:gtv 上频道 A 是 browsable=0,它的节目照样在 browsable_only 结果里。想「只显示已批准的频道」得自己先查频道再按 channel_id 筛。

### 6.5 第三方改 `browsable` / 改别家数据(§1.2 / §2.3)
- 做了什么:读取方 update 发布方频道的 `browsable=1`、`display_name`,以及节目的 `browsable=0`。
- 结果:
  - 只有 `READ_TV_LISTINGS`、没有 `WRITE_EPG_DATA` 时:在权限层就被拒(`requires …WRITE_EPG_DATA`)。
  - 加上 `WRITE_EPG_DATA`(normal,装上即有)后:改 `browsable` 抛 `SecurityException: Not allowed to access Channels.COLUMN_BROWSABLE`(节目是 `Programs.COLUMN_BROWSABLE`);改 `display_name` 不报错但 `rows=0`(只能改自己包的行)。数据确认没变。
- 证据:`rd: update display_name ch=2 -> rows=0`;发布方随后 dump `ch 2 … browsable=0` 不变。

### 6.6 谁来审批 browsable
- 做了什么:`query-receivers -a CHANNEL_BROWSABLE_REQUESTED`、`query-activities -a REQUEST_CHANNEL_BROWSABLE`;发布方调 `requestChannelBrowsable`,再对第二个频道 `startActivityForResult(ACTION_REQUEST_CHANNEL_BROWSABLE)`。
- 结果:
  - **unitedu-tv**:承接方是 `com.google.android.tvrecommendations`(`.AddChannelBroadcastReceiver` / `.AddChannelActivity`),**不是 tvlauncher 本身**。默认频道在请求后 ~14 ms 内被改成 browsable=1——**即使 HOME 角色归 UnitedU**,审批照样发生(审批与谁是默认桌面无关)。第二个频道弹「Add the channel … to your home screen?」Add/Cancel,选 Add → `rc=-1`、browsable=1。
  - **unitedu-gtv**:承接方是 launcherx(`.coreservices.localchannels.AddChannelBroadcastReceiver_Receiver` / `.AddChannelActivity`)。广播确实送达(`dumpsys activity broadcasts` 有记录),但**默认频道一直是 browsable=0**;对话框 Activity 不显示任何界面,立刻返回 `rc=0`(CANCELED),日志只有一行 `W AddChannelActivityPeer: com.probe.publisher called android.media.tv.action.REQUEST_CHANNEL_BROWSABLE`。把发布方改成 `-i com.android.vending`(伪装 Play 安装)重装也一样。原因未查明;结论是**这台 Google TV 镜像对侧载应用等于没有审批方**。
  - 第三方接收器**收得到** `CHANNEL_BROWSABLE_REQUESTED`(系统逐包定向发送,读取方的 `Rx` 两台都收到,带 `CHANNEL_ID` / `PACKAGE_NAME`),但按 §5 改不了库,收到也没用,只能当「某应用想上首页」的通知。
- 证据:`rd: RECEIVED android.media.tv.action.CHANNEL_BROWSABLE_REQUESTED … ch=2`;`pub: onActivityResult rq=7 rc=-1`(tv)/ `rc=0`(gtv);截图 03。

### 6.7 模拟「没有审批方」(国行 A95L 情形)
- 做了什么:unitedu-tv 上 `pm disable-user --user 0 com.google.android.tvrecommendations`(此时接收器只剩读取方自己,Activity 0 个),清掉发布方数据重新发布;gtv 本来就等于没审批方(见 6)。
- 结果:
  - `requestChannelBrowsable` **静默返回,不抛异常**;频道停在 browsable=0。
  - `ACTION_REQUEST_CHANNEL_BROWSABLE` 抛 `ActivityNotFoundException`(应用要自己 catch,否则崩)。
  - **读取方照样读得到未批准的频道和它的全部节目、watch next**(与 browsable 无关,只看 searchable)。所以「没人批准」不挡第三方桌面读取;挡的是**那些自己检查 browsable、不 browsable 就不写节目的应用**(Kodi 型)——这一点探针模拟不了,要看具体应用。
  - 副作用:禁用 tvrecommendations 后它的「Apps Spotlight」频道从库里消失(TvProvider 在包被禁用 / 变更时清理该包的行),重新启用后接收器恢复。
- 证据:`pub: requestChannelBrowsable(4) returned (no exception)` / `ch 4 'ProbeChannel A' browsable=0`;`rd: channel rows=1 … _id=4 … browsable=0`;`rd: preview_program rows=3`;`dialog ERR android.content.ActivityNotFoundException`。

### 6.8 应用卸载后的残留
- 做了什么:卸载发布方后再读。
- 结果:频道、节目、watch next 全部自动清空(两台都 0 行)。
- 证据:卸载后 `rd: channel rows=0` / `preview_program rows=0` / `watch_next_program rows=0`。

### 6.9 Jellyfin / Kodi
- 没装。Jellyfin 的频道和 Watch Next 来自服务器上的媒体库,没有服务器写不出东西;Kodi 的频道来自本地媒体库 / 订阅,同样需要先配内容,而且还依赖 browsable(见 §3 研究文档)。不能「不配服务器就发布」,按约定跳过。

### 6.11 Google TV 不显示侧载应用的继续观看(2026-10-08 补)
- 探针写的 watch_next 在 launcherx 的「继续观看」行里不出现(行里只有 YouTube 的 3 条),日志 `Watch next verification data is empty`;Apps 页也没有任何应用频道行。原因未查实。参照截图与尺寸见 `docs/screenshots/channels/ref/INDEX.md`。

### 6.10 与上文不一致 / 需要修正的地方
1. §5.1 写「`unitedu-tv` = 带 GMS 的 Android TV」——它上面承接审批的是 `com.google.android.tvrecommendations`,不是 tvlauncher;文档 §1.2「真正把 browsable 改成 1 的是接收方桌面」应改成「接收方(可能是桌面,也可能是 tvrecommendations 这类独立系统组件)」。
2. §2.3 / §3 隐含「Google 桌面会审批」:实测 Google TV(launcherx)对侧载应用**不审批**(广播收到但不改、对话框直接 CANCELED)。所以「没有审批方」的情况不只出现在 A95L,Google TV 上的侧载应用也是。
3. §2.2 第 1 条「过滤只能用 URI 参数」正确,但要补一句:`preview_program?browsable_only=true` 只看节目自身的 browsable,不看频道的。
4. §2.2 只说 searchable 过滤,没说过滤是逐行、不级联:隐藏频道(searchable=0)下 searchable=1 的节目照样能读到,桌面得按 channel_id 自己丢掉孤儿节目。
5. §1.2「第三方桌面……也没法承接 `ACTION_REQUEST_CHANNEL_BROWSABLE` 的对话框并把结果真正落库」——实测确认改 browsable 抛 SecurityException;但「接收器任何应用都能声明」也确认:第三方确实收得到广播。
6. §5.2 运行时弹窗:已实测会弹,权限在「Additional permissions」里可撤销;另有「Unused apps」自动收回权限开关,文档未提。


---

## 7. 哪些应用真的写频道(2026-10-07,APK 静态分析)

> 本节优先于 §3 的推断。

调研日期 2026-10-07。方法:只做静态分析,不装、不运行、不碰任何设备。工具:`aapt dump badging` / `aapt2 dump xmltree`(清单)、`dexdump -d`(反汇编,按类找调用方与字符串常量)、`strings` 扫 dex。APK 与反汇编结果不进仓库。

**判据分三级**

- **接线**:清单里有 `INITIALIZE_PROGRAMS` 接收器或同步用的 JobService,**并且**能找到业务代码调用频道 / 节目的写入方法 → 可信度高。
- **有业务代码、没有清单入口**:找到了业务类(比如在主界面启动时同步频道),只是清单里没有接收器 → 可信度中高。
- **只有库**:dex 里只有 androidx.tvprovider 的类和常量,找不到业务调用方 → 视为不写。
- 国内应用大多做了混淆,androidx.tvprovider 被改成 `a/d/c/a/*` 这种短名字,所以「没有 androidx/tvprovider 字样」不代表没用这个库。判断看的是 `content://android.media.tv/preview_program` 这类常量和调用链。

### 7.1 表

| 应用 | 包名 | 版本 | 来源 | 写频道 | 写继续观看 | Engage | 证据 | 可信度 |
|---|---|---|---|---|---|---|---|---|
| 云视听极光(腾讯) | `com.ktcp.video` | 20.0.0.1008 | 当贝市场 CDN `app.qingyingyong.net/down/20260903/txsp16158_20.0.0.1008_dangbei.apk`(当贝渠道包) | **是,但由云端开关控制** | **是,同样受开关控制** | 无 | 清单:`recommendation.channel.SyncChannelJobService` / `SyncProgramsJobService`、`RecommendReceiver`(开机广播)、`UpdateRecommendService`;声明 `WRITE_EPG_DATA`,以及索尼私有权限 `com.sony.dtv.permission.READ_MODEL_VARIATION_INFO_NORMAL`。业务类 `sf/b` 里有 "insert preview program"、"insert watch next program"、`TYPE_PREVIEW`、`tenvideo2://tvrecommendation/...&pull_from=androidTV`。**开关**:`jf/a.d()` 要求云端配置 `service_control` 里的 `tuijian` 为 `force_on`;或者同时满足配置为 on、设备能力表 `DeviceFunctions.IS_SUPPORT_ANDROIDTV` 为真、包名是 `com.ktcp.video` / `com.ktcp.tvvideo` | 高(代码);能不能在 A95L 上真跑起来,取决于腾讯服务端怎么配,**未知** |
| CIBN 酷喵(优酷) | `com.cibn.tv` | 14.0.1.3 | 当贝市场 CDN `.../20260920/yk10013600_14.0.1.3_dangbei.apk` | **是** | 否(业务代码里没有写继续观看) | 无 | 清单:接收器 `com.youku.tv.androidtv.channel.boot.AndroidTvChannelOnInstallReceiver` 收 `android.media.tv.action.INITIALIZE_PROGRAMS`;声明 `WRITE_EPG_DATA`。业务类 `e/t/s/d/a/d` 建一个 `TYPE_PREVIEW` 频道(内部 ID `kumiao_android_tv_provider|2019.1125.1657`),`e/t/s/d/a/c` 用服务端接口 `mtop.fireworks.taitan.recommend` 下发的条目写 preview_program;Android 版本过低直接退出("android version under-qualified")。另有 `HistoryDataBroadCastReceiver` + `SonyUserDataAppLike` / `XiaoMiUserDataAppLike`(配置项 `open_sony_app_like` / `open_xiaomi_app_like`、接口 `mtop.yunos.tvpublic.thirdDesktop`)——这是**给索尼 / 小米桌面推观看记录的私有通道**,不走 TvProvider | 高 |
| 云视听小电视(哔哩哔哩) | `com.xiaodianshi.tv.yst` | 1.8.8 | **官方 CDN** `dl.hdslb.com/mobile/latest/android_tv_yst/iBiliTV-master.apk`(2026-09-11) | **是**(应用启动后同步,没有清单接收器) | 否(只有库里的常量,业务代码没调用) | 无 | 业务包 `com.xiaodianshi.tv.yst.tvchannel`(`TVChannelSync`,"create new channel id"、`TYPE_PREVIEW`、条目跳 `yst://...from=androidlauncher&resource=rec`),由 `MainActivity` 调用(`refreshMediaResource`);清单声明 `WRITE_EPG_DATA` / `READ_EPG_DATA`,代码里会**运行时弹窗申请**("no permission, require permission")。清单里没有 `INITIALIZE_PROGRAMS` 接收器 → 至少要打开过一次应用才会建频道 | 中高 |
| 银河奇异果(爱奇艺) | `com.gitvdemo.video` | 16.9.0.221243 | 当贝市场 CDN `.../20260910/aqy11642_16.9.0.221243_dangbei.apk` | 未发现 | 未发现 | 无 | 清单与 6 个 dex 里都没有 TvProvider 常量、`EPG` 权限或接收器。带 `libprotect.so`,而且爱奇艺 TV 用插件架构,插件可能运行时下载,**不能完全排除** | 中 |
| 芒果TV TV版 | `com.starcor.mango` | 7.1.701(当贝渠道) | 当贝市场 CDN `.../20260813/mgtvDBEI_7.1.701_dangbei.apk` | 未发现 | 未发现 | 无 | 清单与 9 个 dex 都没有命中;用爱加密(`libijiami_release01.so`)加固,加固可能藏住部分代码 | 中 |
| 咪视界(咪咕) | `cn.miguvideo.migutv` | 2.0.4.0003 | 当贝市场 CDN `.../20260716/msj_V2.0.4.0003.DB070214_dangbei.apk` | 未发现 | 未发现 | 无 | 清单与 15 个 dex 都没有命中;爱加密加固(`assets/ijiami.dat`) | 中 |
| 当贝影视 | `com.tv.kuaisou` | 3.13.6(2023) | 当贝官网 `app.qingyingyong.net/down/20230802/dbys_3.13.6_dangbei.apk` | 否 | 否 | 无 | 没有任何命中 | 中(版本旧) |
| **当贝桌面**(第三方桌面,参照) | `com.dangbei.tvlauncher` | 4.1.7(2023) | 当贝官网 `.../20230419/dbzm_4.1.7_dangbei.apk` | — | — | — | **不读** TvProvider:没有 `READ_TV_LISTINGS`、`READ_EPG_DATA`,dex 里也没有任何 `android.media.tv` 常量 | 中(版本旧) |
| 索尼电视应用商店(当贝为索尼国行定制) | `com.sony.dangbeimarket` | 1.2.5(按 Android 8.0 SDK 编译) | 本机已有安装包 | **是** | 有相关代码(接收器收 `PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT`) | 无 | `BootReceiver` 收 `INITIALIZE_PROGRAMS`、`PREVIEW_PROGRAM_BROWSABLE_DISABLED`、`WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED`、`REQUEST_CHANNEL_BROWSABLE`;业务类 `CustomChannelHelper` / `CustomChannelsBean`(频道内容由服务端下发) | 高(代码);版本很旧 |
| 网易爆米花 TV | `com.netease.filmlytv` | 1.4.7 | 本机已有安装包 | 否 | 否 | 无 | 没有任何命中 | 高 |
| VidHub TV | `com.oumi.utility.media.hub` | 3.0.2 | 本机已有安装包 | 否 | 否 | 无 | 没有任何命中 | 高 |
| Kodi | `org.xbmc.kodi` | 21.3 | 官方镜像 `mirrors.kodi.tv/releases/android/arm/kodi-21.3-Omega-armeabi-v7a.apk` | **是** | 否(业务代码不调用 `WatchNextProgram`) | 无 | `org.xbmc.kodi.channels.SyncChannelJobService` / `SyncProgramsJobService`、开机接收器;声明 `WRITE_EPG_DATA` | 高 |
| Jellyfin for Android TV | `org.jellyfin.androidtv` | 0.19.10 | GitHub Release `jellyfin-androidtv-v0.19.10-release.apk` | **是** | **是** | 无 | `integration/LeanbackChannelWorker`(WorkManager)调用 PreviewProgram 与 WatchNextProgram(44 处) | 高 |
| Emby for Android TV | `tv.emby.embyatv` | 2.1.54g | 本机已有安装包 | **是** | **是** | 无 | `integration/OreoChannelHelper` 调用 PreviewProgram(38 处)和 WatchNextProgram(22 处) | 高 |
| SmartTube | `org.smarttube.stable` | 32.56 | GitHub Release `SmartTube_stable_32.56_armeabi-v7a.apk` | **是** | **是** | 无 | `leanbackassistant.channels.ChannelsProvider`(142 处 tvprovider 调用,其中 39 处 WatchNext)、`UpdateChannelsReceiver`(开机广播)、`UpdateChannelsJobService` | 高 |
| YouTube for Android TV | `com.google.android.youtube.tv` | 7.24.300 | 本机已有 APKMirror 安装包 | 是(有接收器) | 有常量 | **有**(`com.google.android.engage.service.ENV`、`AppEngagePublishTaskWorker`) | `INITIALIZE_PROGRAMS` 接收器 + `WRITE_EPG_DATA`;同时接了 Engage | 中(混淆,未追调用链) |
| Netflix | `com.netflix.ninja` | 13.1.3 | 本机已有 APKMirror 安装包 | 是(有接收器) | 有常量 | 未发现 | `INITIALIZE_PROGRAMS` 接收器 + `WRITE_EPG_DATA`、`PreviewChannelHelper` | 中(混淆) |
| Prime Video | `com.amazon.amazonvideo.livingroom` | 6.24.7 | 本机已有 APKMirror 安装包 | 是(有两个接收器) | 有常量 | 未发现 | 两个 `INITIALIZE_PROGRAMS` 接收器 + `WRITE_EPG_DATA` | 中(混淆) |
| HBO Max | `com.wbd.stream` | 7.12.0.68 | 本机已有 APKMirror 安装包 | 是 | 是(`WatchNextProgram` 20 处) | 未发现 | `INITIALIZE_PROGRAMS`、`PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT` 接收器 + `WRITE_EPG_DATA` | 中(混淆) |
| Projectivy | `com.spocky.projengmenu` | — | **没拿到**:APKPure 与 APKMirror 用 curl 都返回 403,本机也没有现成文件 | — | — | — | 权限与接收器没能直接核实,仍只有 Play 商店页和二手资料 | — |

### 7.2 结论

1. **国内四大视频应用里,有两家半确实写频道**:云视听极光(频道 + 继续观看,但受腾讯云端开关和设备能力表控制)、CIBN 酷喵(频道,装好后系统发 `INITIALIZE_PROGRAMS` 就建)、云视听小电视(频道,要打开过一次应用,而且会弹窗要权限)。银河奇异果、芒果、咪视界都没找到(其中两家加了固,把握度中)。
2. **继续观看在国内基本是空的**:只有云视听极光的代码写继续观看,而且受开关控制。国内应用把观看记录推给索尼、小米桌面走的是**私有通道**(酷喵的 `SonyUserDataAppLike` / `XiaoMiUserDataAppLike`),第三方桌面读不到。
3. **这些代码是冲着索尼国行写的**:极光声明了索尼私有权限,酷喵专门写了索尼适配,索尼国行定制的当贝商店也写频道。这说明索尼国行原厂桌面(至少 Android 8 那一代)会显示频道,A95L 上真有数据的可能性不小——**但这只是推断,只有在 A95L 上查 TvProvider 才能定论**(例如 `content query --uri content://android.media.tv/channel`,要 shell 权限,按约定这一步由 Gordon 决定)。
4. **海外应用普遍都写**:Kodi、Jellyfin、Emby、SmartTube 是开源或第三方,都写了,而且这几个在 A95L 上能装;YouTube、Netflix、Prime、HBO Max 也接了 TvProvider,YouTube 另外接了 Engage(那部分只有 Google TV 能读)。
5. **当贝桌面不读 TvProvider**(按 2023 年 4.1.7 版看)。所以国内第三方桌面里,目前没看到谁做这一行。这次没有再查小米 PatchWall。
6. **不确定的地方**:国内的 APK 都是当贝渠道包(哔哩哔哩除外,它来自官方 CDN),和厂商直供的包可能有差别;加固应用的结论只能算「没发现」;云视听极光到底开没开,要看服务端配置。

---

## 来源

| ID | 链接 | 内容 |
|---|---|---|
| C1 | https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android14-release/AndroidManifest.xml | 权限定义与保护级别、provider 声明、`forceQueryable` |
| C2 | https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android14-release/src/com/android/providers/tv/TvProvider.java | `createSqlParams` 过滤、建表语句、`browsable` 写保护、`openLogoFile`、`call` |
| C3 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/media/java/android/media/tv/TvContract.java | 广播 / Activity Action、`requestChannelBrowsable`、列语义、`PERMISSION_READ_TV_LISTINGS`(@hide) |
| C4 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/tv/TvInputManagerService.java | `requestChannelBrowsable` 转发广播;`sendTvInputNotifyIntent` 要 `NOTIFY_TV_INPUTS` |
| C5 | https://developer.android.com/training/tv/discovery/recommendations-channel | 频道 / 默认频道 / 审批对话框 / `INITIALIZE_PROGRAMS` / `WRITE_EPG_DATA` / `intent_uri` |
| C6 | https://developer.android.com/training/tv/discovery/watch-next-add-programs | Watch Next 由系统维护,应用不能移动 / 隐藏 |
| C7 | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/res/AndroidManifest.xml | `protected-broadcast` 名单;`NOTIFY_TV_INPUTS` 为 `signature\|privileged` |
| C8 | https://developer.android.com/training/tv/discovery/recommendations-row | 通知式推荐只用于 API 25 及以下 |
| C9 | https://developer.android.com/training/tv/discovery/recommendations | 8.0 前后推荐展示方式;旧推荐自动转频道 |
| C10 | https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/integration/LeanbackChannelWorker.kt | Jellyfin 写默认频道与 Watch Next |
| C11 | https://android-developers.googleblog.com/2026/05/increase-google-tv-app-discovery.html | 2026-05:旧 Watch Next API 2027 下半年失去支持 |
| C12 | https://developer.android.com/guide/playcore/engage/faq | Engage 与 Watch Next 的兼容表述、支持的界面 |
| C13 | https://developer.android.com/guide/playcore/engage/tv | Engage TV 集成(`engage-tv` 库,声明 `WRITE_EPG_DATA`) |
| C14 | https://developer.android.com/guide/playcore/engage/tv/continue-watching/client | Continue Watching API 整表替换、Google TV 合并多应用列表 |
| C15 | https://play.google.com/store/apps/details?id=com.spocky.projengmenu | Projectivy 商店页(2026-07-12 更新) |
| C16 | https://www.flatpanelshd.com/guide.php?subaction=showfull&id=1754640308 | 二手:Projectivy 设置指南(权限、Netflix 频道差异,经搜索摘要) |
| C17 | https://xdaforums.com/t/app-android-tv-projectivy-launcher.4436549/ | 二手:XDA 帖(本次 403,仅搜索摘要:`READ_TV_LISTINGS`、`pm grant`) |
| C18 | https://github.com/xbmc/xbmc/tree/master/tools/android/packaging/xbmc/src/channels | Kodi 频道同步(`SyncChannelJobService` / `SyncProgramsJobService`) |
| C19 | https://9to5google.com/?p=351313 | 二手:2020-04 YouTube TV 支持 Android TV 首页频道 |
