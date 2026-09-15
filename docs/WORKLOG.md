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

## 2026-09-15 · M2 Task B 完成(设置持久化)+ 本轮自主封顶

- **Task B 完成**(commit 69ef91b + 修复 4f542ca,评审1轮:Important 已修、re-review 无新破坏):`Settings`(10 字段)+ `IdleContent` 枚举 + 纯 `parseSettings`/`toJson` + `SettingsStore.read/write`(照 Layout 健壮性 + 原子写)。14 单测。手写零依赖扁平 JSON(org.json 在纯 JVM 单测不可用);修了「拼接对象 {..}{..} 损坏漏检」。
- **决策门**:门 1(卡片档位=只定每行张数、按比例推导)、门 4(待机 M2 只做 UI+读写、行为留 M5)已答。**门 2(6 主题色 hex)、门 3(设置页布局与项顺序)仍待 Gordon 文字答**——C/F 等它们。
- **本轮自主到此**:M2 只做了不依赖门 2/3、且不改首页观感的机械部分(A 颜色收拢、B 设置持久化)。Task C(设置页)、D(行数)、E(卡片档位)、F(主题色)、G(时钟)、H(输入源行)、I(待机设置)留给 Gordon 定方向后做——多数改首页 UX 或需设置页作宿主。

### M2 已交付 commit(main)
20f27c7 计划草稿 / cb2ed01 颜色收拢 / 335224d 包名doc / 69ef91b 设置模型 / 4f542ca 损坏检测修复。

## 2026-09-15 · M2 四决策门全定 + Google 工具评估

**决策门(Gordon 定)**:
- 主题色:**沉稳**(4 处强调低饱和;金保持 `#C0A73A`;香槟 `#D9C7A0`/蓝 `#6E8FB0`/紫 `#9280AA`/石墨 `#9AA0A6`/绿 `#7FA07A`)。
- 设置页:**分组带标题**(布局/主题/时钟/待机)。
- 卡片三档:只定每行张数 8/6/5,其余按屏宽比例推导,中档锚定。
- 待机:M2 只做设置项 UI+读写,DreamService 行为留 M5。
- **默认背景(追加)**:不设壁纸时用「固定中性暗背景」,主题色只染 4 处强调、不染背景 → 选任何预设整屏仍暗,深色用户友好。**M3 待办:把 M1 现有的金色 `default-wallpaper.jpg` 换成中性暗底。**
- 主题色驱动的 4 处 = 齿轮 / 时钟 / 行标题 / 光晕(设计 §3),不含卡片图与背景。
- 决策对比页 artifact:https://claude.ai/artifact/MdYivJkN8PRpqFF6LmcdDa

**Google 开发者权益评估(Gordon 有 Google AI Pro)**:结论——对本项目基本不划算,不为用权益改工作流。理由:app 完全离线、零 AI、不上 Play、更新走腾讯 COS 非 Google Cloud。逐项:Cloud/GenAI credits 无落点;AI Studio 无关(零 AI);Dev Program Premium 的 Play/Firebase 实惠用不上;Gemini-in-Android-Studio 与 Claude Code 职责冗余;Antigravity 是替代 agent,中途换=净增复杂度,仅留作后备(Android 冷门难题时求第二意见)。**唯一按需推荐**:Android Studio 的 Layout Inspector / Compose 运行时检查——仅在 M2 焦点 bug 用 CLU+截图查不出时,临时装来定位,不进主流程。

## 2026-09-15 · M2 收官(主题收敛 + 设置页 + 卡片三档)

M2 全分支终审(opus)通过(可合并/零 Critical),终审后单次修复波 + re-review 清。全部推送到 main。

### M2 交付(逐任务 SDD 实施 + 评审)
- **颜色收拢**:45 处散落 `Color(0x)` 收进 `Theme` 命名调色板(零改值)。
- **设置持久化**:`Settings`(10 字段)+ `SettingsStore`(照 Layout 健壮性 + 原子写)+ 14 单测;手写零依赖扁平 JSON(org.json 单测不可用)。
- **设置页**:「UnitedU 设置」分组浮层(布局/主题/时钟/待机)+ D-pad 焦点账本(镜像 HomeScreen watchdog,七条铁律)。
- **首页消费管线**:`leaveSettings()` 触发 `revision++` 重读 Settings;各设置返回首页即生效。
- **时钟**:日期开关(Settings.showDate)+ 12/24 跟系统 + 星期跟系统语言。
- **卡片三档**:每行 8/6/5(小/中/大),`Theme.cardMetrics(cardsPerRow)`;中档=当前常量原样(零回归),5/8 跨度守恒推导,纵向焦点按档重算。
- **主题色**:6 预设(沉稳)驱动齿轮/时钟/行标题/光晕;gold 档零回归(gear #C0A73A、clock/glow #FFF5DC);行标题 gold 档 #FFF5DC(Gordon 定保留);跟随壁纸主色(androidx.palette,IO 线程 + 回落)。
- **值表统一**:`VALID_CARDS_PER_ROW`/`VALID_IDLE_AFTER_MS` 为 Settings 唯一源,SettingsScreen 引用。

### 决策(Gordon)
包名 com.uniteduone.launcher / 主题色沉稳 / 设置页分组带标题 / 卡片档位只定张数中档锚定 / 待机 M2 只做 UI(行为 M5)/ 默认背景固定中性暗(不随主题)/ 行数推迟 M4 移除控件 / 输入源行推迟真机 pass 移除控件 / gold 行标题保留 #FFF5DC / 卡片标题移除控件(渲染后续).

### 延后项(带去向,字段已保留)
- **H 输入源行 → 真机 pass**:TvInputManager 枚举 + 焦点集成必须真机才能建对+验对。Settings.showInputRow 字段保留。
- **行数 → M4**:与行 增删/命名/图标 一起做。Settings.rowCount 字段保留。
- **卡片标题渲染 → 后续任务**:Settings.showTitles 字段保留。
- **待机行为(DreamService)→ M5**:idleAfterMs/idleContent 设置 UI 已做、已持久化。
- **默认壁纸 → M3**:把 M1 的金色 default-wallpaper.jpg 换成中性暗底(配合默认背景决策)。
- **Polish**:聚焦 drop shadow / edit ＋字号未随档缩放、RowIcon 未主题化、跟随壁纸壁纸解码两次(IO 线程)。
- **预存债(非 M2 引入)**:EditScreen verticalScroll + AppPicker LazyColumn;13 个 lintRelease 错误。

### 注记
多个子代理 commit 的 co-author 用了「Claude Sonnet 5」而非「Claude Opus 4.8」(子代理系统配置强制,覆盖了任务指令)。纯 cosmetic,未 force-push 改写历史。

## 2026-09-15 · M2 100% 完成(H 真机收尾)

真机 pass 收掉输入源行(Task H),M2 全部任务完成。
- **真机连接**:A95L 无线调试配对(非 5555;`printf '<码>\n'|adb pair` + mdns 找 connect 端口 + `adb connect`)。IP/端口每次变,见记忆 [tv-adb-wireless-debugging]。
- **Task H(输入源行)真机四项全验**:① 枚举真实输入(HDMI 1-4 / Apple TV(CEC) / 电视 / eARC,TvInputManager loadLabel 友好名);② 渲染置顶输入行;③ 焦点(上下进出 app 行、左右穿行,活动/非活动变暗,全程不丢焦——输入行作为普通 Row 复用整套索引推导账本);④ 切换(选中 → 索尼 com.sony.dtv.tvlin 显示输入源,HDCP 挡截图=真实内容)。代码评审 Approved。
- Task G 的「星期跟系统语言」真机确认(中文「周二」)。
- **UnitedU 保留在 TV**(与 TvHome 共存、非默认桌面,留作真机测试)。TV 无线调试仍开(FYI:开发者选项可关)。
- Task H 延后小项 → M4:HDMI-CEC 父子去重、per-input 隐藏/改名(复用修改标题);Inputs.launch 冗余 enumerate 为 polish。

**M2 里程碑达成**:主题收敛 + 设置页 + 卡片三档 + 主题色 + 时钟 + 输入源行,全部实机验证,推送 github.com/GordonWang1878/UnitedU-launcher。

## 2026-09-15 · M6 收官(手机上传页 + 传 APK 安装 + NOTICE 纠错)

M6(Task 1–5)全部完成,子代理驱动 + 每任务评审通过,收官验收在 emulator-5558 全项命中(`pm clear` 干净安装重跑)。

### 交付(spec §0)
应用内 HTTP 服务(NanoHTTPD:壁纸/卡片图/屏保三类的传/看/删 + 传 APK 安装)+ 网页(`assets/web/index.html`,四标签、缩略图网格、上传/删除/预览,服务端注入三语文案)+ 电视端二维码页(`ImportScreen`,ZXing 生成)+ 齿轮菜单入口(「导入图片」,插在「换壁纸」之前)+ `NOTICE` 条目。

### 验收结果(spec §6)
- 不崩不留黑:坏图不落盘(`x.gif` → `rejected reason=type`)PASS;`type=icons` → 400 PASS;删除不存在的文件 → 404 PASS(`../settings.json` 经 `sanitizeUploadName` 剥离成 `settings.json`,该名字在目标目录不存在,404 而非设计原稿设想的 400——Task 2 已查实这是清洗函数既有契约下的安全行为,非 bug,详见 task-2-report)。
- 单测:`testReleaseUnitTest` 24 项全绿、0 failure(`SettingsTest` 14 / `UploadPureTest` 9 / `RelaunchPolicyTest` 1)。
- 模拟器:三类目录各传 1 张 + 列表核对,wallpapers 额外走完整序列(同名去重 `a.jpg`/`a-1.jpg`、gif 拒收、`/thumb` 与 `/file` 200、delete、`adb shell ls` 与 API 列表一致)全部命中;二维码页截图(`docs/screenshots/m6-import.png`,URL/二维码/计数三项清晰可读)、网页截图(`docs/screenshots/m6-web.png`,四标签、缩略图、删除按钮渲染正确)、`GET /` 注入的 `const S = {...}` 三语字符串核对、`__STRINGS__` 占位符清零;APK 三段式(gif → invalid、未授权 → needs-permission + 系统「安装未知应用」页截图、放行后 → started + 系统安装器截图)全部命中,两次跳系统页返回后服务都还活着(30 s 豁免窗生效,详见下方注记)。
- 真机:留 Gordon(spec §6 唯一需要肉眼的一步)。

### 决策
- Gordon:手机页不做远程「设为当前壁纸」(壁纸/卡片图仍只在电视端选择器选);传 APK 安装放进 M6,不等 M7。
- 技术选型:HTTP 服务用 **NanoHTTPD 2.3.1**(许可证是 **BSD-3-Clause**——设计文档 §5/§8 原写 Apache-2.0 系笔误,本任务已订正,`NOTICE` 按 BSD-3 的三条件写全);二维码用 **ZXing core 3.5.3**(Apache-2.0);服务寿命绑定「导入图片」页本身——端口从 8090 起顺延到 8099,HOME/`ON_STOP` 触发即关闭,但传 APK 安装期间(系统安装器 / 「允许安装未知应用」页会让 `MainActivity` 走 `ON_STOP`)有 30 s 自发起安装豁免窗,用时间戳到期自动失效,不需要任何人手动清(不是一次性布尔闩)。

### 延后项
- 真机扫码验证(Gordon,spec §6 唯一肉眼步骤)。
- 齿轮菜单四项归并 → M7(设计 §6)。
- 检查更新 → M7,复用本任务的 `ApkInstaller`(`install`/`archiveInfo` 已是独立、无状态的可复用入口)。

### 注记
- 目录/端口:上传落盘临时目录 `externalCacheDir/upload/`(外置卷未挂载时回落 `cacheDir/upload/`);APK 落 `cacheDir/apk/upload.apk`(FileProvider 只开放这一个子目录,见 `res/xml/file_paths.xml`);服务端口固定从 8090 尝试到 8099。
- **NanoHTTPD 同名多文件字段命名坑**:2.3.1 把重复的 `files` multipart 字段依次存成 tempfile key `files`(第 1 个)、`files1`(第 2 个)、`files2`(第 3 个)……**不是**计划书最初假设的 `files`/`files2`/`files3`(1-based 跳号少算一位);`parameters` 里每个 key 各自持有自己那一份原始文件名,不是全部塞进一个 `files` 列表。Task 2 用 3 文件、4 文件各实测一次后,改成按 key 直接枚举(`files` 加 `files<digits>`,数字排序)而不是假设连续序号,序号中间有洞也不会把整批截断。
- 其余计划外发现:`AndroidManifest.xml` 最初漏了 `android.permission.INTERNET`(服务器完全绑不上端口,Task 2 补上,commit 286a32d);模拟器上系统「安装未知应用」设置页是半透明浮层,不会真正触发 `MainActivity` 的 `ON_STOP`,真正验证 30 s 豁免窗要靠不透明的系统安装器确认框(Task 4 报告已记录,本任务验收沿用同一手法验证)。
- 已知技术债(均不影响当前功能,留手):缩略图内存缓存 key 只有 `name|mtime`、没带 `type`(三个 library 目录彼此独立,实际不会撞,但契约上不够严谨);`UploadServer.start()` 在主线程跑(实测最坏约 100 ms);超限上传先落临时文件再判断大小(30 MB 封顶,极端情况多一次磁盘写);`uniqueName` 在多台手机并发上传同名文件时有 TOCTOU 窗口;网页上传 `fetch` 失败(网络错误)不会重置文件 `<input>`;网页 tab 切换有竞态——异步 `await` 完成时读的是彼时的全局 `type` 而不是发起请求那一刻的 `type`;`ApkInstaller` 极少数情况下会先报 `STARTED`(`onNotice` 已经喊了「已交给系统安装器」)、随后因异常被判成 `INVALID`,电视端文案会短暂说错。
- **合并顺序**:M6 基于 M3 落地之前的 main 切出(`library/wallpapers` 在本分支是空的,M3 的壁纸种子没有落进来,验收时确认过这是预期状态,不是回归)。M6 → main 的合并在 M3 之后做,已知冲突点在 `MainActivity.kt`(菜单项/待机守卫附近)、`strings.xml`(追加行)、`NOTICE`,由控制器处理,本任务不动。
- **验收方法论踩坑一则**:`adb shell appops set <pkg> REQUEST_INSTALL_PACKAGES default` 在应用进程存活期间执行会被 Android 直接杀掉该进程(logcat:`ActivityManager: Killing … REQUEST_INSTALL_PACKAGES changed`——appop 收紧才会触发这条安全策略,放宽成 `allow` 不会)。Task 4 把这一步放在 `am start` 之前,没撞上;本任务重置权限的时点晚了,撞了一次,`am start` 重新拉起后 `library/*` 三个目录的文件原样还在(未丢数据),后续同类验收改用「先重置权限、再启动 app」的顺序可以绕开。
