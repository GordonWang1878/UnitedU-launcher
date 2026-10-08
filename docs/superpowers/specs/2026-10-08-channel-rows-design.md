# 频道行(Ruling R164)设计

日期:2026-10-08 · 状态:待 Gordon 审 · 调研:`docs/research/2026-10-07-tv-channels-recommendations.md` · 原厂参照:`docs/screenshots/channels/ref/INDEX.md`

## 0. 一句话

用户可以在「编辑桌面」里把电视上其它应用发布的**频道**(Android TV 的 TvProvider 预览频道)亲手加成首页的一行;默认一行都没有,不加就和现在完全一样。「零推荐」的意思是默认不推、随时能去掉,不是想开也开不了(Gordon 2026-10-07)。

## 1. 已定的裁定(按时间)

| # | 内容 | 来源 |
|---|---|---|
| 1 | 第一版范围:各应用的频道行;**不做**焦点停留自动播预告片 | Gordon 2026-10-08 选 ① |
| 2 | 做法 A:频道是用户在编辑桌面里亲手加的一行,和应用行一样可上下移、删除;**不做**设置总开关 / 频道管理页 | Gordon 2026-10-08 |
| 3 | 第 1 段「怎么用」批准(见 §2) | Gordon 2026-10-08 |
| 4 | **不做「继续观看」**:首页一行叫「继续观看」会被当成全系统观看记录,实际只收录主动写表的应用(YouTube 新版走 Engage 读不到,国内基本只有云视听极光写),承诺大于能给。选频道页不列它,也不读 `watch_next_program` 表 | Gordon 2026-10-08 |
| 5 | 视觉:频道卡固定 110 dp 高、宽按海报比例;焦点行里**每张卡正下方**写标题 + 第二行(照 Google TV,不写简介);行头在卡片上方写「应用名 · 频道名」,左边距不画图标;长按频道卡**不弹菜单** | Gordon 2026-10-08 选择卡 |

## 2. 用户怎么用

### 2.1 入口与选频道页
- 编辑桌面里,应用行的行菜单(行尾「+」)多一项 **「在下方添加频道」**,放在「在下方新建一行」之后;频道行的行菜单里也有这一项。频道行已满 5 行时不出现。
- 选频道页(`ChannelPicker`)是整屏两栏页,版式、焦点机制照「添加应用」(`AppPicker`):左边页名「添加频道」+ 一段说明;右边一列,每项 = 应用图标小卡 + 「应用名 · 频道名」。已经在桌面上的频道不列。
- **授权**:进页时没有 `android.permission.READ_TV_LISTINGS` 就先弹系统授权窗。
  - 允许 → 列出频道。
  - 拒绝 → 右边只剩一段说明 + 一颗「去系统设置开启」(跳本应用的系统详情页;从那里回来按 `onResume` 重读授权)。
- **没有任何频道**:写「暂时没有应用提供频道」+「打开过一次的视频应用才会提供」;只有一颗「返回」,焦点总有地方落。
- **通知应用建频道**:进页时(已授权),给每个声明了 `android.media.tv.action.INITIALIZE_PROGRAMS` 接收器、且还没通知过的包发一次显式广播(按包名 + versionCode 记在 `channel-init.json`)。文档说这个广播由「桌面」发、不是 protected broadcast;我们当桌面时不发,酷喵 / Netflix / YouTube 这类等它才建频道的应用永远不会出现。列表随 TvProvider 变化(ContentObserver)自动补上新出现的频道。

### 2.2 编辑页里的频道行
- 一行 = 一张宽的**频道把手卡**(高同本页应用卡,宽 = 3 张应用卡 + 2 个间距):左边应用图标,右边两行字「频道名」/「应用名」;频道当前没内容 / 没授权时第二行改成「暂无内容」/「需要重新授权」。
- 把手卡在这一行的位置等同应用行的「+」(列号 0):确定 = 这一行的菜单——此行上移 / 此行下移 / 在下方新建一行 / 在下方添加频道 / 删除此行。**删除不弹确认**(随时能加回)。「需要重新授权」时确定先走授权窗,再开菜单。
- 左边距的行图标位置不画东西。
- 搬运模式(移动应用卡)里,频道行不接收应用:上下搬运跳过频道行,落到下一个应用行;前后都没有应用行就不动。

### 2.3 首页上的频道行
- 出现在用户放的位置。行头:卡片上方一行「应用名 · 频道名」。卡片:节目海报,按 TvProvider 的 `weight` 降序、同权重按插入顺序,**最多 12 张**。
- 按确定 = `Intent.parseUri(intent_uri, URI_INTENT_SCHEME)` + `NEW_TASK` 启动;没有 `intent_uri` 或启动失败 → 打开该应用(`Apps.launch`);再失败 → 现有 Toast「打不开」。
- 长按 / MENU 在频道卡上不做任何事(`cardAt` 给 null,现有长按菜单的判据自然不成立)。
- 频道暂时没节目、频道被应用删了、授权被收回 → **首页不画这一行**(与空应用行同一条过滤,焦点账本里不存在它)。

### 2.4 数量
- 应用行仍 `MIN_ROWS = 1` / `MAX_ROWS = 5`,**只数应用行**;频道行另算,最多 `MAX_CHANNEL_ROWS = 5`;每行最多 12 张卡。
- 至少保留 1 行应用行:只剩一行应用行时,它的菜单里没有「删除此行」(频道行永远可删)。

### 2.5 自动处理
- 应用被真正卸载(`PACKAGE_FULLY_REMOVED`,以及 `onResume` 的缺包清理)→ 它的频道行从 layout.json 删掉,与卸载应用时卡片被移出同一条路、同一把锁。
- 应用更新 / 重装后重建了频道(`_id` 变了)→ 按 §3.1 的标识找回,行不丢。
- 「恢复默认」不碰 layout.json(现状),频道行保留。

## 3. 数据

### 3.1 layout.json
频道行写成带 `channel` 字段的空应用行:

```json
{"icon":"tv","apps":[],"channel":{"pkg":"com.cibn.tv","key":"kumiao_android_tv_provider|2019.1125.1657","name":"酷喵推荐"}}
```

- `LayoutRow` 加一个有默认值的字段 `channel: ChannelRef? = null`(`ChannelRef(pkg, key, name)`);现有 `LayoutRow(icon, apps)` 构造与 `copy(apps = …)` 全部不用改。
- **标识**:`key` = 频道的 `internal_provider_id`(应用自己定的、跨重装稳定的 id);应用没写时 `key` 为空,按 `name`(`display_name`)匹配;都对不上就当「暂无内容」,不自动换成别的频道。
- **为什么不另开文件**:顺序天然和应用行在一起,上移 / 下移 / 落盘铁律都复用。**旧版本读新文件**:`channel` 被忽略,看到一个空应用行(首页本来就不画空行);旧版本第一次写盘会把它变成真正的空应用行——版本只升不降(自我更新),接受。
- `parse` 补一条校验:至少一行应用行(零行原本就当损坏);全是频道行同样当损坏回落。
- 频道行的 `icon` 写合法 id(`tv`),只为旧版本回落用,界面不画。

### 3.2 读 TvProvider(`ChannelSource`)
- 只读两张表:`channel`(`TvContract.Channels.CONTENT_URI`,`?package=` 过滤)、`preview_program`(`TvContract.buildPreviewProgramsUriForChannel(id)`)。**一律显式投影**(`projection = null` 会撞 BLOB 列抛异常,§6.2 实测),不用 selection(TvProvider 对第三方带 selection 直接抛异常)。
- 只要 `type = TYPE_PREVIEW` 的频道;`searchable` 由 TvProvider 过滤(逐行、不级联:隐藏频道下的节目照样读得到——我们只按频道 id 取节目,不受影响)。**不看 `browsable`**:国行 A95L 与 Google TV 上没人审批,频道永远是 0(§6.6–6.7)。
- 节目字段:`title`、`episode_title`/季集号、`duration_millis`、`poster_art_uri` + `poster_art_aspect_ratio`(缺省按 `thumbnail_uri` / 16:9)、`intent_uri`、`weight`、`internal_provider_id`。
- 全在 IO 线程;结果按频道缓存在内存里,首页 / 编辑页 / 选频道页共用。
- **刷新**:`ContentObserver` 监听 `content://android.media.tv`(在 `MainActivity` 与包广播同处注册 / 注销),500 ms 去抖后 `channelsRevision++`;只重读频道数据,不重读布局与应用横幅。`onResume` 也读一次(授权可能在系统设置里被改)。
- 权限没了:读到 `SecurityException` 即视为「需要重新授权」,不崩。

### 3.3 海报图
- 来源:`content://` / `android.resource://` 走 `ContentResolver.openInputStream`;`https://` 走 `HttpURLConnection`(超时 5 s);`http://` 明文按现有网络安全配置会失败,当作没图。
- 解码到卡片实际像素(110 dp × 2 = 220 px 高,`inSampleSize` 先粗缩),内存 `LruCache` 16 MB(按 `allocationByteCount`);不做磁盘缓存。
- **只加载可视附近的行**:焦点行和上下各一行;其余行先画占位底色,进入范围再加载。
- 没图 / 加载失败:卡上画深色底 + 标题文字(同 Google TV「Key to the Phoenix Heart」那张)。

## 4. 首页版式

- 卡高 110 dp,宽 = 110 × 比例(16:9 → 196、3:2 → 165、4:3 → 147、1:1 → 110、2:3 → 73、3:4 → 83 dp,电影海报 2:3 同);间距 `CARD_GAP` 20、圆角 `CARD_CORNER` 8、左缘 `CONTENT_KEYLINE` 58;焦点视觉沿用应用卡(放大 + 描边 + 柔光、`gtvFocusFrameOverFade`)。
- 行头:卡片上方,字号照 Google TV 未聚焦行标题(约 19 sp、白 60%),焦点行里白 100%;不做焦点行标题放大。
- **焦点行**每张卡下方两行:标题(约 14 sp、白)+ 第二行(约 12 sp、白 70%)。第二行写元数据(剧集「第 n 季 · 第 n 集」、电影 / 短片写时长),**不重复「应用 · 频道」**——Google TV 那一行写「YouTube • 频道名」是因为它那一行混着多个来源,我们的行头已经写了;没有元数据时第二行留空。非焦点行不画字,但高度照 `reserveTitleSpace` 先例预留,换行时版面不跳。
- **纵向位移要改成按行累计**:现在 `rowPitch` / `restCardTop` / `focusLineCardTop` 都假设每行等高;频道行高(行头 + 110 + 两行字)≠ 应用行高。改成逐行高度表 + 累计 top,焦点线按焦点行自己的卡高算;应用行之间的观感逐像素不变(单测钉住)。
- **横向位移**:`rowShiftX` 增加一个吃「每张卡宽度列表」的版本,频道行用它;应用行仍走等宽版本。
- 铁律 1:频道行同样是不可滚动的 `Row` + `wrapContentWidth(unbounded = true)` + `offset`;12 张上限就是为了全部常驻组合时 A95L 扛得住。

## 5. 焦点账本(七条铁律)

- 首页:频道行是 `rows` 里的普通一行(`Row` 加 `programs` 与频道标题),`tgtRow/tgtIdx`、还原效果、看门狗不新增状态;所有 `.apps.lastIndex` 夹取改成「这一行的格数」。节目数变化(应用后台刷新,一行少了几张)→ 焦点卡被拆走的情形走现有看门狗(铁律 3)。
- 编辑页:频道行只有一个可聚焦节点(把手卡,列 0,挂 `rowFocus[ri]`,左右 `Cancel`),等同空应用行的「+」,`toRowEnd` 现成可用。
- 选频道页是编辑页 `OverlayStack` 里的新一层 `EditOverlay.ChannelPick`:逐项 requester、`(nonce, candidates, ghost)` 初始循环、`holder == null` 看门狗、残影让路(`LocalPageGhost`),照 `AppPicker`;**它的状态必须并进 `EditScreen.overlayOpen`**(守卫与 key 同一个量,铁律 6)。列表随 ContentObserver 变化时按 id 重定位(同应用页 `appsPageRetarget` 的规则)。
- 授权窗:系统弹窗期间 Activity `ON_PAUSE`,各页按现有规则冻结;回来 `focusNonce++` 接回。
- CLAUDE.md 焦点表补三行:首页频道行、编辑页频道把手卡、选频道页。

## 6. 要顺手修的旧路径

- `Move.kt` `moveCard` / `moveInLayout`:上下搬运跳过频道行(否则空的频道行会接住应用)。
- 所有应用页「加到桌面… → 选一行」(`MainActivity` 约 2083–2130、`AppsPage.rowAppNames`):只列应用行,下标映射回 layout 下标。
- 缺包清理(`pruneMissingPackages` / `planPrune`)把频道行的包也算进去;`PackageRemovedReceiver` / `pruneUninstalled` 删掉该包的频道行。
- `addRowBelow` / `deleteRow` / 行菜单的条件按「应用行数」「频道行数」分别判。

## 7. 权限与清单

- `AndroidManifest.xml` 加 `<uses-permission android:name="android.permission.READ_TV_LISTINGS" />`(dangerous,运行时申请)。
- `<queries>`:`QUERY_ALL_PACKAGES` 已有,查 `INITIALIZE_PROGRAMS` 接收器不需要再加。
- 运行时申请走 `registerForActivityResult(RequestPermission)`,在 `MainActivity.onCreate` 注册(本应用第一处运行时权限)。
- 说明文案(选频道页左侧):UnitedU 会读取电视上各应用提供的频道,只在本机显示,不上传。

## 8. 测试

- **单测(纯函数)**:`Layout.parse/toJson` 频道行往返、旧文件、「全是频道行」当损坏;行数判据(应用行 / 频道行分开);`moveCard` 跳过频道行;缺包清理删频道行;频道匹配(key → name → 无);海报宽度按比例;`rowShiftX` 变宽版;纵向累计 top(应用行之间与现状逐像素一致);节目排序与 12 张截断。
- **e2e**(`scripts/e2e/j_channels.py`,模拟器 `unitedu-tv` / `unitedu-gtv`):带代码的夹具发布 APK(沿用调研探针:建频道、写 8 个节目、混排比例);旅程:编辑页加频道 → 授权弹窗 → 首页出现 → 焦点左右上下 → 确定启动 → 发布方删节目 / 卸载 → 首页行消失 / layout.json 里行被删 → 撤销授权 → 编辑页显示「需要重新授权」。
- **性能**:A95L 档位的判断沿用 `docs/design/perf-2026-09-28.md` 的方法,在模拟器上先量 5 个频道行 × 12 张的首页帧时间;真机效果随正式版由 Gordon 在电视上看(1.0 后电视验证一律走正式发布)。

## 9. 不做(第一版)

继续观看行;焦点自动播预告片;长按频道卡菜单(隐藏单条 / 加到继续观看);频道 logo;设置页总开关与频道管理页;磁盘图片缓存;审批 `browsable`(要系统权限,做不了)。

## 10. 风险

- 国行实际有没有数据只有电视上能看(APK 分析:云视听极光受腾讯云端开关、酷喵等 `INITIALIZE_PROGRAMS`、云视听小电视要打开过一次)。第一版发出后 Gordon 在电视上加一次频道就知道。
- 纵向位移从等高改成累计,是首页最敏感的那段代码;要求应用行之间逐像素不变的单测先写。
- 用户长期不用时系统会自动收回权限(「Unused apps」);桌面天天在用,一般不触发,触发了走「需要重新授权」。
