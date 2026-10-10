# 国行电视应用的频道:谁写、写什么、什么时候更新(云视听小电视时间差根因)

调研日期 2026-10-10。只做调研,不改代码。承接 `2026-10-07-tv-channels-recommendations.md`(下称「前稿」)§7 的静态分析,这里不重复「是否写频道」的已知结论,只补**写什么、靠什么触发、多久更新**,以及 Owner 在 A95L 上看到的「小电视频道像几个月前的观看记录」的根因。

## 0. 结论先行

**一句话:国行视频应用写频道的确实少(6 家里只有小电视、酷喵、极光 3 家在通用渠道包里写,央视频只在「索尼定制版」里写);而且没有一家按「系统来要就刷新」的方式工作。小电视的频道内容本来就是「你的观看记录前 20 条」,不是推荐,而且只在你在小电视首页按返回键退出时才重写一次。用 HOME 键离开,或者应用在后台被杀,它就永远停在上一次按返回退出时的样子。**

- 时间差的根因是应用自己的写入时机(代码证据,把握度高),不是 GMS、不是索尼桌面、也不是 INITIALIZE_PROGRAMS 只发一次。
- 我们帮不了它刷新:小电视没有任何能被外部唤起的刷新入口,再发 INITIALIZE_PROGRAMS 对它无效。对酷喵有效,但每发一次就要在后台冷启动一个很重的应用,不值得。
- 产品上该做的:在选频道页明说「内容由应用提供,何时更新也由应用决定」;小电视这类频道的名字照应用写的显示,但我们自己不能把它叫成「推荐」。

**方法与证据等级**:APK 静态分析(jadx 1.5 反编译 + aapt2 读清单),APK 来源见 §1 表。**没有实测**:这 4 个 APK 全是 32 位(`armeabi` / `armeabi-v7a`),`unitedu-tv-3` 的系统镜像只有 64 位(`ro.system.product.cpu.abilist32=` 为空),装不上,所以没起模拟器。电视按约定不碰。下文「高」= 反编译代码里直接看到调用链;「中」= 有代码但触发条件取决于服务端配置或系统行为;「推断」= 没有直接证据。

---

## 1. 每个应用一行

| 应用 | 包名 / 版本(本次) | 写不写 preview 频道 | 频道名 · 内容 | 触发 / 刷新时机 | 证据 · 把握度 |
|---|---|---|---|---|---|
| **云视听小电视**(哔哩哔哩) | `com.xiaodianshi.tv.yst` 1.8.9,官方 CDN `dl.hdslb.com/mobile/latest/android_tv_yst/iBiliTV-master.apk` | **写** | 「云视听小电视」· **用户自己的播放历史**(云端 + 本地合并排序,取前 20 条:标题、副标题、封面、`yst://` 跳转)。服务端开关 `tv_channel_enable`(默认开) | **建频道**:应用启动后的初始化任务(`performanceinspector.a.h()` → `createChannelMediaSynchronizer`),已有频道就只记下 id。**写节目**:只有一处——`MainActivity.W1()` → `P0()`(Kotlin 原名 `fetchHistoryToChannel`),而 `W1()` 只在**首页按返回键要退出**时调用(`onBackPressed` → `r1()` → 登录引导回调 0/2)。没有清单接收器、没有 JobService / WorkManager / 闹钟 | 反编译 `tvchannel/b.java`、`MainActivity.java` · 高 |
| **CIBN 酷喵**(优酷) | `com.cibn.tv` 14.0.1.3,当贝渠道包 | **写** | 应用名(`internal_provider_id = kumiao_android_tv_provider\|2019.1125.1657`)· 服务端推荐(`mtop` 接口下发的条目,每次整份删掉重插) | 两个入口,都只跑**每个进程一次**(单例 `c` 在构造时触发):① `INITIALIZE_PROGRAMS` 接收器(代理进内置插件 `assets/com.cibn.tv.apk`);② 应用打开后的空闲任务(插件 `ott_booter_main.json` 的 `onIdle` 列表里有 `AndroidTvChannelInitJob`)。没有定时刷新 | 反编译 `e/t/s/d/a/c.java`、`d.java`、插件清单 · 高 |
| **云视听极光**(腾讯) | `com.ktcp.video` 20.0.0.1008,当贝渠道包 | **写**,受云端开关 `tuijian` 控制(前稿已述) | 应用名 · 服务端推荐(最多 20 条);另有继续观看(取本地观看记录) | 唯一一家**有后台定时刷新**的:开机广播 / 应用启动 → `AlarmManager` 定时拉起 `UpdateRecommendService`,间隔由服务端 `req_interval_time` 下发,缺省 **30 分钟**;拉到数据后排一次性 `JobScheduler` 任务写 TvProvider。没有 `INITIALIZE_PROGRAMS` 接收器。Android 8 以下改走旧的通知式推荐 | 反编译 `jf/b.java`、`UpdateRecommendService.java`、`sf/b.java` · 高(代码);开关在 A95L 上开没开 · 未知 |
| **央视频 TV 版**(总台,原 CBox) | `com.newtv.cboxtv` 7.4.7,当贝渠道包 `cctvxst_7.4.7.dangbei.apk` | **只在「索尼」构建里写**;当贝渠道包**不写** | 「央视频TV版」· 服务端编辑位(CMS 接口 `getLauncherPageTab`,按位置排 weight) | 打开应用时(`EntryActivity` / 开屏广告结束)起一个 `AsyncTask` 同步一次,条件是 `Libs.getFlavor().equals("sony")`;当贝包的 flavor 写死为 `"dangbei"`。清单声明了 `WRITE_EPG_DATA`,没有接收器、没有定时任务 | 反编译 `tv/newtv/cboxtv/e0/e.java`、`f.java`、`com/newtv/host/n.java`(`Libs.init(..., "dangbei", ...)`)、`com/newtv/utils/x.java`(`D = "sony"`)· 高 |
| 银河奇异果(爱奇艺) | `com.gitvdemo.video` 16.9.0.221243 | 未发现 | — | — | 前稿 §7 结论;当贝市场今天仍是同一版本,没有重做 · 中(加固 / 插件) |
| 芒果TV | `com.starcor.mango` 7.1.701 | 未发现 | — | — | 同上 · 中(爱加密加固) |

补充两点:

1. **Sony 私有通道**。酷喵有一个导出的接收器 `HistoryDataBroadCastReceiver`,收 `com.youku.history.list.get` / `com.youku.reserve.list.get` / `com.youku.track.list.get`,配合 `open_sony_app_like`(缺省 = 是否索尼 CIBN 渠道)——这是**桌面来拉观看记录**的私有协议,不走 TvProvider,第三方桌面接不上(协议没文档,内容在远程插件里)。极光声明了索尼私有权限 `com.sony.dtv.permission.READ_MODEL_VARIATION_INFO_NORMAL`。央视频直接按「索尼构建」开关频道。**国行应用写频道,主要是给索尼定制的。**
2. **没有一家用旧的 `RecommendationService` / 通知式推荐来覆盖 Android 8+**;极光的通知推荐只在 8.0 以下走。

---

## 2. 小电视时间差:根因与证据

### 2.1 根因(代码,把握度高)

小电视写进频道的,是**你的播放历史**(`PlayerHistoryStorage.readFirstAsync(1, 20, …)` 的云端 + 本地合并结果,前 20 条),不是应用首页的推荐流。写入只发生在这一条路径上:

```
首页按返回 → MainActivity.onBackPressed() → r1()
  → (showExit 为真时)HalfLogin171.jumpHalfLoginIfNeeded(3, …) 回调 0 或 2
  → W1() → P0() 读历史 → refreshMediaResource() → 更新 / 插入 preview_program
```

所以出现时间差有三个叠加原因:

1. **内容本来就和应用里的推荐不是一回事**。频道 = 「最近看过」,打开 App 看到的是推荐流,两者本来就不会一致。
2. **只有「按返回退出」才刷新**。从小电视里按 HOME 回桌面、从别的应用切走、待机,都不会走 `onBackPressed`。电视用户大多用 HOME 键离开,频道就停在上一次按返回退出时的历史。
3. **按返回也不一定刷新**:
   - 登录引导弹窗选第二项(回调 4)直接 `finish()` + 杀进程,不刷新;
   - 用带 `showExit=false` 或 `stay≠1` 参数的 intent 拉起时,`r` 为假,按返回直接 `N0()` 退出,不刷新;
   - 频道 id 只记在内存里(启动任务查到 / 建好后才有),启动任务没跑完或失败(`checkIfCreateChannel()` 为假)时也不刷新;
   - 服务端把 `tv_channel_enable` 关掉也不刷新。

另外,频道节目是**原地更新**(按顺序覆盖已有行,多的插入、少的删除);历史为空时会把整个频道的节目删光。

### 2.2 候选根因逐一核对

| 候选 | 结论 | 证据 · 把握度 |
|---|---|---|
| 只在 `INITIALIZE_PROGRAMS` 时写一次 | **排除**:小电视清单里**没有**这个接收器 | 清单 · 高 |
| 只在应用被打开时写一次、之后不更新 | **接近,但更窄**:建频道在启动时,写内容只在「首页按返回退出」时 | 反编译 · 高 |
| 依赖 GMS / Google TV 桌面触发 | **排除**:整条路径只用 `ContentResolver` 写 TvProvider,没有 GMS 调用,也不等任何外部触发 | 反编译 · 高 |
| 索尼桌面不再请求刷新 | **不适用**:Android 本来就没有「桌面请求应用刷新」的机制。官方文档里桌面只在安装后发一次 `INITIALIZE_PROGRAMS`,其余全由应用自己用 JobScheduler 安排;小电视连这个入口都没接 | 官方文档 C5 · 高 |
| 后台被国行系统杀 / 限制自启动,Job 跑不了 | **对小电视不是原因**:它根本没有后台任务可被限制。对极光是相关因素(闹钟 + 后台服务,targetSdk 24);对酷喵影响小(只在进程启动时跑) | 反编译 · 高(小电视);极光在 A95L 上是否被限制 · 推断 |
| 写的是 watch next 不是 preview | **排除**:写的是 `TYPE_PREVIEW` 频道。代码里有 `refreshWatchNextResource`,但全应用没有调用方 | 反编译 · 高 |

### 2.3 怎么在电视上验证(没做,需 Owner 看一眼,不用 adb)

1. 在小电视里看一个新视频,然后**一路按返回**直到出现退出提示,退出;回 UnitedU 看小电视那一行,第一张应变成刚看的视频。
2. 再看另一个视频,这次**按 HOME** 离开;那一行应保持不变。

两步都符合,根因就坐实了。

---

## 3. 对我们产品的含义

### 3.1 能不能 / 该不该主动触发刷新

- **对发布方再发 `INITIALIZE_PROGRAMS`**(我们现在按「包名 + versionCode」只发一次,`ChannelInit.kt`):
  - 小电视、极光、央视频:**无效**,它们没有这个接收器。
  - 酷喵:有效,前提是它的进程是新起的(单例,每个进程只同步一次)。代价是带 `FLAG_INCLUDE_STOPPED_PACKAGES` 的广播会在后台**冷启动一个很重的应用**(阿里系插件框架、保活 `DaemonJobService`、埋点 SDK),在 32 位的 A95L 上吃内存和网络;而且它的内容是服务端推荐,刷新频率对用户价值不大。
  - 结论:**不建议定期重发**,维持「每个版本一次」。
- **其它刷新手段**:Android 没有让第三方桌面请求刷新的接口(`sendTvInputNotifyIntent` 要 `signature|privileged` 权限,见前稿 §1.4);小电视的刷新入口在它自己的 `onBackPressed` 里,外部够不着。**做不了,也不该去模拟按键 / 拉起应用。**

### 3.2 建议在界面上说清楚

- 选频道页说明里加一句:「频道内容由应用提供,何时更新也由应用决定。」可以再补一句「有的应用只在你打开它、并按返回退出时才更新」。不点名应用,免得应用改版后说明过时。
- 频道名照应用写的显示(小电视写的就是「云视听小电视」)。我们自己的文案里**别把频道叫「推荐」**:小电视那一行其实是观看记录。
- 不建议显示「更新于 X 天前」:`preview_program` 没有修改时间列,只能靠我们自己观察到变化的时间推算;我们没在跑时漏掉的变化会算错,这个数不可靠。

### 3.3 对「国行电视上频道少」的预期

在 A95L 这类国行电视上,常见视频应用里:能稳定看到内容的,是酷喵(装好后经我们的 INITIALIZE 或打开一次就有,内容是推荐);小电视要打开过、并按返回退出过(内容是观看记录);极光取决于腾讯服务端开关;央视频只有索尼渠道版才有;奇异果、芒果没有。选频道页空状态的文案「打开过一次的视频应用才会提供」基本成立,但对小电视要再加上「按返回退出过」这个条件。

---

## 来源

| ID | 内容 |
|---|---|
| A1 | `com.xiaodianshi.tv.yst` 1.8.9(versionCode 108900,targetSdk 26,`armeabi-v7a`),`dl.hdslb.com/mobile/latest/android_tv_yst/iBiliTV-master.apk`,2026-10-10 下载。关键类:`com.xiaodianshi.tv.yst.tvchannel.{ITVChannel,a,b}`、`kotlin.cf5`(节目读写)、`ui.main.MainActivity`(`P0` / `W1` / `r1` / `onBackPressed` / 回调 `m`)、`yst.performanceinspector.a`(`h` → `j`)、`account.login.HalfLogin171`;字符串 `channel_yst_name = 云视听小电视` |
| A2 | `com.cibn.tv` 14.0.1.3,`app.qingyingyong.net/down/20260920/yk10013600_14.0.1.3_dangbei.apk`;内置插件 `assets/com.cibn.tv.apk`(`agileplugin.json`)。关键类:`com.youku.tv.androidtv.channel.boot.*`、`e.t.s.d.a.{c,d}`;插件 `assets/ott_booter_main.json` 的 `onIdle[10]` |
| A3 | `com.ktcp.video` 20.0.0.1008,`app.qingyingyong.net/down/20260903/txsp16158_20.0.0.1008_dangbei.apk`。关键类:`jf.{a,b}`、`com.tencent.qqlivetv.android.recommendation.{RecommendReceiver,UpdateRecommendService,channel.a}`、`sf.b`(JobScheduler) |
| A4 | `com.newtv.cboxtv` 7.4.7,`app.qingyingyong.net/down/20260903/cctvxst_7.4.7.dangbei.apk`。关键类:`tv.newtv.cboxtv.e0.{e,f}`、`tv.newtv.cboxtv.EntryActivity`、`views.EntryAdView`、`com.newtv.host.n`、`com.newtv.utils.x` |
| C5 | https://developer.android.com/training/tv/discovery/recommendations-channel:「`INITIALIZE_PROGRAMS` … which the home screen sends after the app is installed」;「When your app is not in the foreground and you need to update a channel or a program, use the JobScheduler」;文档没有任何桌面向应用请求刷新的机制 |
| 前稿 | `docs/research/2026-10-07-tv-channels-recommendations.md` §1.3 / §1.4 / §7 |

APK 与反编译产物放在会话临时目录,不进仓库。公开讨论里没找到关于小电视频道不更新的资料(2026-10-10 搜索)。
