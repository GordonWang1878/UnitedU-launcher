# 频道 / 继续观看:Google 原生桌面参照截图(2026-10-08)

给频道推荐行的设计规格当对照用。全部来自模拟器,没碰电视。

- **Android TV**:`unitedu-tv`(Android 14 TV 镜像),桌面 `com.google.android.tvlauncher` 7.7.15-968255986-f,审批方 `com.google.android.tvrecommendations` 7.4.0。下文简称 atv。
- **Google TV**:`unitedu-gtv`(已登录 Google 账号,区域台湾),桌面 `com.google.android.apps.tv.launcherx` 1.0.988926538。下文简称 gtv。
- 两台都是 1920×1080 / 320 dpi,**dp = px ÷ 2**。表里的 px 都按 1080p 截图量。
- **探针**:发布方 `com.probe.publisher`(应用名 Probe TV)。它建了一个 `TYPE_PREVIEW` 频道「Probe Picks」,带 8 个节目(5 张 16:9、2 张 2:3、1 张 1:1,分别是剧集、电影、短片、专辑和直播),海报由探针自带的 ContentProvider 在本地提供,不联网。另有 4 条 watch next:2 条 CONTINUE(16:9 看到 60%、2:3 看到 25%)、1 条 NEXT、1 条 NEW。探针不进仓库,源码放在会话 scratchpad 的 `channel-ref/`,测完两台都已卸载。
- **数值的出处**:像素实测、uiautomator 边界、从 tvlauncher APK 读出的 dimen / style 资源。表里写「资源」的是 APK 里的原值。

## 截图

| 文件 | 内容 |
|---|---|
| `atv-01-home-playnext-and-channel.jpg` | 首页。焦点在「Play Next」第 1 张(继续观看卡,底部有进度条,下方是应用 banner、标题和「Resume watching」);下面是未聚焦的「Probe TV: Probe Picks」频道行,三种比例混排。频道已手动挪到 Play Next 正下方 |
| `atv-02-channel-default-position.jpg` | 刚批准的频道默认追加在最后一行(在 Google Play 的「Top selling movies」「Recently Added」之后)。焦点在 Google 自家频道,下方元数据带价格 |
| `atv-03-focus-16x9-episode.jpg` | 焦点在 16:9 剧集卡。卡片放大 1.2 倍,信息写在行下方:标题,「2025 · Season 1 · Episode 3 · 52m · Animal/Wildlife」,再一行「集名 - 简介」 |
| `atv-04-focus-2x3-movie.jpg` | 焦点在 2:3 电影海报卡(184×276)。元数据「2024 · 1h 58m」+ 简介;星级、分级都不显示。行已滚动,上一张露出左边一截 |
| `atv-05-focus-1x1-album.jpg` | 焦点在 1:1 专辑卡(276×276):「Probe Music · 12 Tracks」/「2026 · 简介」 |
| `atv-06-focus-16x9-clip.jpg` | 焦点在 16:9 短片卡:「Probe Travel · September 14, 2026 · 1,234,567 Views」 |
| `atv-07-focus-16x9-live.jpg` | 焦点在 `live=1` 的直播卡:只有「3,200 Viewers」,**卡上没有 LIVE 角标** |
| `atv-08-channel-actions-move.jpg` | 在频道第一张卡上按左键进入频道操作:整行右移,左侧出现「Remove」(减号)和「Move」两颗 48 dp 圆钮,当前焦点在 Move |
| `atv-09-channel-actions-remove.jpg` | 同上,焦点在 Remove(白色实心圆 + 减号,标签在钮下方) |
| `atv-10-channel-move-mode.jpg` | 在 Move 上按确定进入移动模式:整行加高亮底板,上下键换位置。已紧贴 Play Next,所以只剩向下箭头(Play Next 永远在最上面) |
| `atv-11-program-longpress-menu.jpg` | 长按频道里的节目:卡片下方弹出浅灰菜单,两项「Add to Play Next」「Remove」,其余画面压暗 |
| `atv-12-playnext-longpress-menu.jpg` | 长按 Play Next 卡:只有一项「Remove from Play Next」 |
| `atv-13-playnext-focus-next-episode.jpg` | 焦点在 NEXT 类 Play Next 卡:状态行是「Next episode: Season 2, Episode 2」(NEW 类是「New episode: Season 3, Episode 8」)。卡面上没有角标 |
| `atv-14-playnext-first-run-tip.jpg` | 首次使用时 Play Next 第 2 格插着一张说明卡,带「Got it」按钮(本次已点掉,不会再出现) |
| `atv-15-customize-channels.jpg` | 「Customize channels」右侧面板:Play Next(On)、Home screen channels(逐个应用:banner + 应用名 + 频道名 + 开关)、Promotional channels(1 个,即 Apps Spotlight · Google Play) |
| `atv-16-customize-playnext-sources.jpg` | Play Next 子页:一个总开关,加上 Sources 里逐个应用的开关 |
| `gtv-01-home-your-apps-continue-watching.jpg` | Google TV 首页:「Your apps」里有 Probe TV(已装),但「Continue watching」里只有 3 条 YouTube,**探针的 4 条一条都没出现**。图中继续观看行没有焦点 |
| `gtv-02-continue-watching-focused.jpg` | 焦点在 Continue watching 行:行标题放大;卡片有外描边,并有「RESUME」胶囊和内缩进度条;这一行每张卡下方都显示标题和「YouTube • 频道名」 |
| `gtv-03-continue-watching-longpress.jpg` | 长按继续观看卡:进入全屏实体菜单(左边是图和标题,右边是胶囊列表),只有「Open」一项 |
| `gtv-04-apps-tab-no-channels.jpg` | Apps 页:顶部推广 banner、Your apps,下面往下滚只有 Play 商店的推广行(Popular apps、Stream the music you love…),没有任何应用频道行 |

## 测量

| 项目 | Android TV(tvlauncher) | Google TV(launcherx) |
|---|---|---|
| 行左缘(keyline) | 108 px = 54 dp(资源 `channel_title_padding`) | 116 px = 58 dp |
| 行标题 | 文本格式是「应用名: 频道名」;12 sp(24 px),TextView 高 16 dp(资源)。焦点行里是白色 100%,非焦点行随整行压暗到约 60%。标题顶到卡片顶:焦点行 72 px(36 dp),非焦点行 48 px(24 dp)(资源 `channel_gridview_focused/unfocused_margin_top` 24 / 12 dp) | 非焦点行约 19 sp(大写字母高 28 px),白色约 60%;焦点行标题放大到约 33 sp(大写字母高 48 px) |
| 频道 logo | **不显示**。写进 TvProvider 的频道 logo,这个版本哪儿都没用上;行头只有文字。行头**拿不到焦点** | —(没有频道行) |
| 卡片高度 | 所有比例都是 230 px = 115 dp(资源 `card_height`) | 继续观看卡 220 px = 110 dp |
| 卡片宽度 | 16:9 408 px(204 dp)、2:3 153 px(77 dp)、1:1 230 px(115 dp);资源里另有 3:2 172 dp、4:3 153 dp、3:4 86 dp、电影海报 80 dp | 16:9 392 px(196 dp) |
| 卡片间距 | 24 px = 12 dp | 40 px = 20 dp |
| 卡片圆角 | 4 dp = 8 px(资源 `card_rounded_corner_radius`) | 约 16 dp(实测约 30 px) |
| 焦点卡 | 放大 1.2 倍:16:9 490×276、2:3 184×276、1:1 276×276;以左缘为轴(左边不动,上下各长 23 px),压住右边那张;无描边,Z 抬高 10 dp(资源)。行会横向滚动,焦点卡始终停在 keyline;到行尾也不回弹,右侧可以留空 | 放大约 1.08 倍(424×238);外描边环约 4–5 px,环和图之间空约 5 px,环色约 #929CA4 |
| 焦点卡信息 | **不画在卡上**,画在行下方的元数据区(焦点卡底边下 19 px 起,高 62 dp)。标题 16 sp 白色(Google Sans Medium,style `EntityTitle`);下面两行 12 sp、白色 60%(`EntityDescription`)。第 1 行按类型拼:剧集是「年 · Season n · Episode n · 时长 · 类型」;电影是「年 · 时长」;短片是「作者 · 日期 · n Views」;专辑是「作者 · n Tracks」;直播是「n Viewers」。第 2 行是「集名 - 简介」或简介。**不显示**:内容分级、星级 / 百分比评分、本探针的价格、LIVE 角标 | 焦点行里每张卡下方都显示两行:标题约 14 sp 白色,「YouTube • 频道名」约 12–13 sp、白色约 70%。非焦点行不显示文字 |
| Play Next / 继续观看的焦点信息 | 左边是应用 banner,71×40 dp(142×80 px);右边是标题(16 sp)和状态行(12 sp):CONTINUE 显示「Resume watching」,NEXT 显示「Next episode: Season x, Episode y」,NEW 显示「New episode: …」 | 同上一行(标题 + 应用 • 频道) |
| 进度条 | 8 px = 4 dp,**贴卡片底边、与卡同宽**,随卡一起放大(焦点时约 10 px)。填充纯白 #FFFFFF,轨道约为白色 20%。只有 CONTINUE(有播放位置和时长)才画;NEXT / NEW 卡面不加角标也不画条 | 8 px = 4 dp,**内缩**:左右各缩 24 px,离底边 20 px。填充 #E4F3FF,轨道约为 #E4F3FF 20%。条上方有「RESUME」小胶囊 |
| 非焦点行 | 整行压暗到约 60%(像素拟合 k ≈ 0.605,等于卡片以约 60% 不透明度叠在背景色上) | 同样压暗,约 70%(进度条填充从 #E4F3FF 变成约 (165,174,169)) |
| 行间距 | 非焦点行之间 336 px = 168 dp(资源 `channel_unfocused_height`);焦点行 258 dp,含 62 dp 元数据区 | 非焦点行标题顶之间 340 px |
| 背景 | 页面底色随焦点卡的主色变,保持深色调 | 首页顶部是大图;往下是深色底 |
| 频道操作 | 在第一张卡上按左:整行右移 208 px,出现两颗 48 dp 圆钮。Move 进入移动模式(行加高亮底板,上下键换位,确定键落位);Remove 把这个频道从首页移除(本次没按下去实测)。标签在钮下方 | — |
| 长按菜单 | 浅灰卡片菜单,贴在焦点卡下方,带一个指向卡片的小三角;宽 460 px(230 dp),每项 48 dp,圆角 6 dp,图标 24 dp,文字 14 sp(资源);其余画面加 0.8 遮罩。频道节目有「Add to Play Next」「Remove」两项;Play Next 卡只有「Remove from Play Next」 | 全屏实体菜单(左边是图和标题,右边是胶囊列表),继续观看卡只有「Open」 |
| 设置入口 | 首页最底下有「Customize your Home screen」卡;设置页是右侧 720 px(360 dp)的侧栏(本次用 `am start -a com.google.android.tvlauncher.SETTINGS` 打开)。频道按应用逐个开关(每项是应用 banner 64×36 dp + 应用名 + 频道名 + 开关),Play Next 也按应用逐个开关,另有「Promotional channels」单独一组 | — |

## 要点 / 注意

- **gtv 上看不到侧载应用的继续观看和频道**。探针写入后,launcherx 进程打出 `W SetWatchNextVerificatio: Watch next verification data is empty`。等了约 1 分钟并重开首页,继续观看仍然只有 YouTube 的 3 条。推断:launcherx 只信任经过服务端校验的应用的 watch next,但没有查实。频道依旧是 `browsable=0`,与研究文档 §6.6 一致。
- atv 的频道行和 Play Next 行都可以混排不同比例的卡,卡高统一,宽度按 `poster_art_aspect_ratio` 变化。
- **本轮在模拟器上改过的状态**:
  - `unitedu-tv` 上 UnitedU 的主页键接管无障碍服务原本是开着的(它会把原厂桌面拉回 UnitedU),截图期间临时关了,测完已恢复;
  - Play Next 的说明卡点过「Got it」,不会再出现;
  - 频道挪过位置,但随探针卸载一起消失了;
  - `long_press_timeout` 两台都已改回 400。
