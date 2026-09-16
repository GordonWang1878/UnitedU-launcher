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

## 2026-09-15 · M3 开工(设计定稿 + 实施计划 + 并行执行)

- **范围**:壁纸轮播(关/5 分/30 分/每天)+ 主题化管线(去色→染主题色→模糊→压暗,CPU 离线 + 缓存;minSdk 28 不能用 RenderEffect)+ 模糊/压暗滑块实时预览 + 内置 6 张程序生成壁纸 + 默认底换中性暗。spec `docs/superpowers/specs/2026-09-15-m3-wallpaper-design.md`(ba95464),计划 `docs/superpowers/plans/2026-09-15-m3-wallpaper.md`(3cd9ac8,9 任务)。
- **决策(Gordon)**:内置壁纸=程序生成抽象图(非 CC0 照片);管线参数=我定默认、真机滑块调后回填;滑块=真滑块条 11 档;spec 六节与计划一次通过。
- **执行方式(Gordon)**:两波并行 subagent(波 1:T1→2 ‖ T3;波 2:T4→5→6 ‖ T7),各自 worktree,我汇合评审;T1–3 机械任务只跑一轮合并评审。分支 `m3-wallpaper`。
- **环境**:为波 2 两个 agent 各占一台模拟器(同包名互相覆盖安装),把 `unitedu-tv` 克隆为 `unitedu-tv-2`(`~/.android/avd/`,改 AvdId/路径,942 MB)。多台并行时 adb 命令必须带 `ANDROID_SERIAL`。M6 并行后又克隆了第三台 `unitedu-tv-3`(console 端口 5558);三台同开时 16 GB 内存约剩 43%、swap 1.2 GB,再多就不要开了。
- **设计要点**:当前壁纸 = settings 字段指向 library 文件(不再复制 wallpaper.jpg、不再 recreate);新增 `settingsRevision` 计数器只重读 settings 不重建首页行(轮播每 5 分钟一次,走 `revision` 会连卡片图一起重读);`rotate()` 返回「写盘成功」而非「换了图」,否则单张图库时 effect key 不变、轮播停转(铁律 6 变体)。

## 2026-09-15 · M3 运行期间并行起 M4/M5/M6(Gordon 定)

- **并行原则**:按耦合面而非里程碑编号——M6(上传页)几乎全是新文件,整段并行;M5 先做真机 spike;M4 全在焦点代码上,只设计不实施,等 M3 合并。
- **M6 spec + 计划**已落分支 `m6-upload`(worktree `.claude/worktrees/m6-upload`):NanoHTTPD 2.3.1(**BSD-3-Clause,设计 §5 写 Apache-2.0 有误,M6 收官时纠正**)+ ZXing core 3.5.3;端口 8090–8099;不做远程设壁纸(守 §9);传 APK 安装放 M6(`ApkInstaller` 供 M7 复用)。T1(纯函数)已完成待评审;T2 起需一台模拟器,排在 M3 波 2 之后。
- **M4 spec**已落分支 `m4-card-menu`:范围拆 M4(长按菜单 + 卡片标题 + 新应用标记)/ M4b(行管理 + CEC 去重 + 输入源隐藏改名 + 原地移动);「移动位置」M4 只做兜底;标题放卡片下方;自定义标题存独立 `titles.json`。
- **M5 spike**:最小 `DreamService` 测试 APK 已编(`.superpowers/spike-m5/unitedu-dream-spike.apk`,分支 `worktree-agent-a21dcfb91b5103162`),待 Gordon 电视配对后安装、看系统屏保列表是否列出 UnitedU。
- **工程发现**:Agent 工具的 `isolation: worktree` 从 **main** 分支,不是当前分支——波 2 起改为控制器自己 `git worktree add` 从 `m3-wallpaper` 建;子 agent 的 Write 被沙箱限制在自己的 worktree 内,报告落在各自 worktree 的 `.superpowers/` 再由控制器拷回。模拟器 `unitedu-tv` 上 `KEYCODE_HOME` 归原厂 Google TV 桌面(RoleManager 持有 HOME,`set-home-activity` 压不过),验证一律 `am start -n com.uniteduone.launcher/.MainActivity`。
- **T7 评审附带两条备案**(非本次引入):① `SettingRow.step()` 从组合快照读 `ctrl.selected`,同一帧内到达的多个左右键只算一步(`adb input keyevent A B C` 批量发送会少计;真人按键有帧间隔,未见影响),且每步同步写 `settings.json`——滑块长按快速连发时若写盘慢于按键间隔会吃掉按键,真机 pass 时留意;② 一次未复现的冷启动齿轮菜单首项高亮缺失(release 冷启动 JIT 下初始焦点循环 1 s 内未落地的形态,铁律 2 的已知落地延迟),记录备查。
- **T4 修复轮发现(既有行为,非 M3 引入)**:壁纸选择器 / 屏保图库 / 默认桌面卡这几个 `pickerTarget` 浮层在 `MainActivity` 的 if/else 链里**替换**了 `HomeScreen`(不是叠在上面),首页的焦点记忆随之丢失——选完壁纸焦点回到第一行第一张,recreate 时代也一样。裁定不在 M3 范围,留作 polish:让这些浮层叠加而非替换,或关闭时按记忆的 (行, 列) 还原(与铁律 5 同一机制)。

## 2026-09-15 · M3 收官(壁纸轮播 + 主题化管线 + 内置壁纸)

Task 8 全项集成验收:模拟器零回归像素对比 PASS,spec §6.3 七项验收全过,文档同步完毕。分支 `m3-wallpaper`(commit 8de1950 起,T8 收官在 37c864c 之上)。

### M3 交付(按 spec §0)
- **壁纸轮播**:关/5 分/30 分/每天,`MainActivity` 常驻 `LaunchedEffect`(key = `wallpaperRotateMs`+`wallpaperRotatedAt`+设置页开关),重启后按剩余时间续等;设置页开着时暂停,退出补一拍(T6 评审)。
- **主题化管线**:去色→染主题色→模糊→压暗合成一个 `ColorMatrix`,CPU 离线处理(minSdk 28 无 `RenderEffect`,不做实时 GPU),结果写文件缓存;模糊/压暗滑块 300ms 防抖实时预览,聚焦壁纸分组时设置页浮层降透明度、壁纸从两侧透出。
- **内置 6 张生成壁纸**:`scripts/gen-wallpapers.py`(噪声→放大→高斯模糊→归一化→压暗曲线→染色),`00-neutral` 为默认底,首次启动铺入 `library/wallpapers/`(`.seeded` 标记只铺一次)。
- **默认底替换**:M1 金色 `default-wallpaper.jpg` → 中性暗底,兑现 M2 决策「默认背景不随主题」。
- **设置页壁纸分组**:轮播间隔(SEGMENTED)/ 主题化壁纸(TOGGLE)/ 模糊(SLIDER)/ 压暗(SLIDER)四行,插在「布局」与「主题」之间。

### 验收结果(spec §6.3,`unitedu-tv` 模拟器)
1. 全零参数零回归:**PASS**——clock bbox `(1386, 45, 1571, 143)`,m3 diff bbox `(1386, 45, 1571, 143)`,两者完全重合,时钟区域外逐像素一致。
2. 首次启动铺入 6 张 + `.seeded` + `wallpaperFile=unitedu-00-neutral.jpg`:**PASS**(T4 已验;T8 用 fresh install 复核同样结果)。
3. 旧根目录 `wallpaper.jpg` 迁移为 `legacy-wallpaper.jpg`:**PASS**(T4 已验;T8 的零回归测试本身走的就是 M2→M3 升级路径,复核 `migrateLegacy` 正确)。
4. 主题化开/关、模糊 50、压暗 50 三张截图 + 缓存 ≤12 个:**PASS**(T5 已验)。
5. 轮播:`rotatedAt` 清零后回首页换下一张、单张 library 不换图但刷新 `rotatedAt`:**PASS**(T6 已验)。
6. 设置页壁纸分组四行焦点不丢 + 聚焦滑块 300ms 内首页联动:**PASS**(T7 已验)。
7. 跟随壁纸主色开 + 主题化开,换预设不改壁纸主色来源:**PASS**(T8 本轮验——两张截图 gear 均值 RGB≈(14, 22, 29) 蓝调,`themePresetId` 从默认切到 `green` 前后 gear 颜色不变)。

### 决策(Gordon,spec §0)
1. 内置 6 张壁纸来源:**程序生成抽象图**(1 中性暗底 + 5 同系变体)——零版权、每张 ~50 KB、我全程可做、契合「沉稳」主题。
2. 主题化管线参数:**我定默认,Gordon 真机用滑块调**,调完把满意值改成默认(电脑管线数值在 Hub、SSH 不通,滑块让默认值不再关键)。
3. 模糊/压暗控件:**真滑块条**,左右键每步 10%,0–100% 共 11 档带进度条(忠于设计「两个滑块」;分段档位调不细)。

### 延后项(带去向)
- 真机调参回填(主题化默认观感)→ **Task 9**(spec §6.4,M3 唯一需要 Gordon 肉眼的步骤)。
- 齿轮菜单四项归并 → **M7**。
- 上传页删图 → **M6**。
- 恢复默认 → **M7**。

### 注记
- **处理耗时**(swiftshader 模拟器,box blur 跑在 768×432):blur=0 ≈ 1.0 s、blur=50 ≈ 0.7 s、blur=10 ≈ 3.4 s(最坏情况——`boxBlur3` 的纯像素循环跑在三档里最大的 768 宽图上;真机预期远快)。
- **缓存**:最多 12 张 JPEG,按 `lastModified` LRU(缓存命中会刷新 mtime),目录 `externalCacheDir/wallpapers/`。
- **已知细节,非阻塞**(留意但不在本轮修):选择器类浮层(壁纸选择器 / 屏保图库 / 默认桌面卡)替换而非叠加 `HomeScreen`,选完后卡片焦点回到 (0,0)(T4 记录的既有行为,非 M3 引入,留 polish);`boxBlur3` 强制 alpha 不透明,blur=0 与 blur>0 的 PNG 透明度因此不同;`migrateLegacy` 会静默覆盖已存在的 `legacy-wallpaper.jpg`;`Wallpapers.select` 的头部解码 + settings 写入在主线程;轮播间隔改动要退出设置页才生效;`delay()` 按 uptime 计时,「每天」跨面板休眠会漂移;`wallpaperCacheKey` 用 `|` 分隔符拼参数;`scripts/gen-wallpapers.py` 缺可执行位(`chmod +x` 未做)。

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

## 2026-09-16 · M6 终审加固波次(CSRF + 前台限制 + 五个 Minor)

commit `a0ae3ad`(分支 `m6-upload`)。全branch 复审判定「可合并」,但点名两项 Important 与几个便宜的 Minor,控制器裁定一次性落地。
本质:M6 的写入面原先**只靠「谁连得上局域网」保护**——没有来源判定、没有前台判定、没有请求大小预判。

### 交付
- **H1 CSRF**:非 GET 路由要求 `X-Requested-With: UnitedU`,缺 → `403 {"ok":false,"reason":"origin"}`;网页三处非 GET `fetch` 带上它。`fetch` + `FormData` 属 CORS 简单请求,**手机上任何网页都能不经预检往电视 POST/DELETE**;自定义头把它顶成要预检。GET 不动(`/thumb`、`/file` 要能被 `<img>` 直接加载)。
- **H2 前台限制**:`UploadServer` 拿 `isForeground()`,`serveApk` 在主线程那一回合里先查前台与 `isAlive`,不满足 → 新的 `Result.BACKGROUND`、不弹安装器、删暂存 APK、回 `{"ok":false,"reason":"background"}`(新增三语 `web_apk_background`);`ImportScreen.onNotice` 也只在前台才撑豁免窗。否则局域网上任何人每 <30 s 传一次 APK 就能让这个无密码服务在用户切走后无限期活着、还能糊安装弹窗——而关页保险正被那个窗压着。
- **Minor**:`Content-Length` 预检(超限直接 413 + `Connection: close`,不读 body;403 同样没读 body,一并处理);未知来源 `startActivity` 包 `runCatching`(定制固件没这个设置页时仍返回 `NEEDS_PERMISSION`,不 500);开服前扫残留(`cacheDir/apk/upload.apk` + `tmpDir` 里超过 60 s 的文件);multipart key 枚举提纯成 `UploadPure.uploadKeys()` + 5 个单测;needs-permission 的豁免窗从 30 s 放宽到 **120 s**(设置页上开开关再返回,30 s 不够)。

### 验收结果
- `testReleaseUnitTest` **29/29 全绿**(24 → 29)、`assembleRelease` + `lintVitalRelease` 通过。
- 模拟器 `emulator-5558`:H1 无头 403 / 带头 200、GET 不受影响;H2 前台 `ok:true` + 安装器起来 → 切到原生桌面后再传 → `background` 且**无任何弹窗**;**窗未被续**(设备侧 `/proc/net/tcp` 轮询 8090:t0+30 s 还在监听、t0+32 s 已停,若续窗应活到 ≈t0+36 s);H3 413 用时 12 ms;120 s 窗实测 t0+46 s 服务仍在;H5 的 60 s 老化清扫(13 分钟前的临时文件被删、刚写的留着);手机页面走自身 handler 的上传与删除均正常。
- 详细报告与截图:`.superpowers/sdd/2026-09-15-m6-upload/final-fix-report.md`(+ `final-fix-shots/`)。

### 注记
- **口径订正(写进 spec §6)**:名字的 400 与 404 是两件事——清洗不过(空、`.`、`..`、点开头)→ 400;**清洗得出合法名字**的按那个名字去找,不在 → 404。所以 `../settings.json` 是 404(上一轮已查实,这轮把 spec 的措辞改成与实现一致)。
- `ImportScreen` 的 ON_STOP 关页**依赖 Compose(≥1.5)在 Activity STOPPED 期间照常重组**(停的只有帧时钟),已在代码里注明:别因为「后台还能跑」看着可疑就把 `stop()` 从 `onDispose` 挪走。
- `uploadKeys` 收下了 `filesX` 这类非数字后缀(旧代码会滤掉),按复审给的用例实现;无害——没有临时文件的 key 直接跳过,每个文件仍逐个过四道闸。
- **又撞了一次 appops 杀进程**(上面 M6 收官的「验收方法论踩坑」那条):`appops set … deny` 收紧权限时系统会 `Killing … REQUEST_INSTALL_PACKAGES changed` 直接杀掉应用,服务凭空消失不是产品缺陷。顺序永远是**先设 appop、再开页面**。
- `cacheDir/apk/upload.apk` 的清扫在 release 包上无法直接目击(不可 `run-as`、模拟器 `adb root` 被拒);它与 tmp 清扫在同一个 `runCatching` 里且在循环之前,tmp 被删即证明该行已执行。

## 2026-09-16 · M3 + M6 合并进 main(本地,未推送)+ 接缝冒烟

- 合并顺序 M3(`e1a6a4e`)→ M6(`61e5c73`);唯一冲突 `docs/WORKLOG.md`(两边各追加一节,保留两段)。合并树 `testReleaseUnitTest assembleRelease` 绿,48 单测。
- 模拟器接缝冒烟 5/5 通过:首启铺入 6 张内置;上传页能列/删/传内置库,删掉的内置图重启不复活(`.seeded`);轮播每次重扫目录,上传的图进入轮播;删掉**当前**壁纸后回落到排序第一张,不黑屏不崩;设置页 12 行上下全程焦点不丢,导入页开关后首页焦点仍在。
- 待 Gordon:推送;T9 真机调参;手机扫码真机验证;M5 电视 spike。

## 2026-09-16 · M4 收官(长按卡片菜单 + 卡片标题 + 新应用标记)

分支 `m4-card-menu`(design spec 提交 `bf4f1fb` 起,T1–T5 共 16 commit,HEAD `65b70d5`;T6 收官提交在其上;pre-M4 基线 = main `61e5c73`,M3+M6 合并)。子代理驱动 + 每任务评审,T2/T4/T5 各 1 轮修复后过审,T1/T3 一次过审,全部 clean。

### M4 交付(spec §0)
- **长按卡片菜单**:确定键/Enter `ACTION_DOWN` 且 `repeatCount == 1`(≈0.4 s)触发,复用 `GearMenu` 承载六项——打开、卸载(`ACTION_DELETE` 系统确认页)、修改标题、更改图标、移动位置(兜底,跳编辑页定位到该卡)、从当前分类移除;输入源行(`RowKind.INPUTS`)长按只吞按压、不出菜单(隐藏/改名留 M4b)。
- **卡片标题**:`titles.json` 独立存储(读写 + 清洗:trim、去控制字符、≤40 字符、空=删除条目);全局开关 `Settings.showTitles`(默认 false,设置页「布局」组补回);卡片正下方一行,开关打开时行高按 `cardMetrics` 三档同比例增加;无横幅纯图标应用用 `Palette` 取主色铺底(`AppEntry.fallbackColor`)。
- **新应用标记**:`Settings.newAppsSeenAt` 首启写入当前时间(之前装的都不算新);首页状态栏(齿轮左侧,随齿轮/时钟一起待机淡出)显示计数;编辑页「添加应用」列表候选项标「新」角标,打开列表即把 `newAppsSeenAt` 刷新为当前时间(按打开前的时间戳标记本次列表,关闭后首页计数归零)。
- `TitleDialog`:系统 IME 重命名对话框,`BasicTextField` + nonce 焦点账本 + 四向 Cancel。

### 验收结果(spec §7,`unitedu-tv` 模拟器)
1. T1(`f6f5dc1..cee9628`):`Titles` 读写/清洗 + `isNewApp` + `cardMenuItems` 纯函数,43/43 单测,评审 clean。
2. T2(`cee9628..36b7c34`):卡片标题渲染(全局开关、三档行高缩放、无横幅回落色),43/43,评审 1 轮修复(溢出公式漏计 `titleHeight` → 三行桌面底行标题被裁;spec §2.2 分档缩放漏实现)后 clean。
3. T3(`ccac3b7..c1d570c`,先合并 main/M6 到分支):新应用计数 + 角标 + `newAppsSeenAt`,57/57,评审 clean(Important 项——onCreate 基线写入无读门控、`countNew` 重复枚举——折入 T4/留手)。
4. T4(`4dea174..c8a7204`):长按菜单六项落地,57/57,评审 1 轮修复(MENU 键在卡片菜单上又叠出齿轮菜单;`layoutRow`/`colIndex` 用了渲染下标而非 `layout.json` 下标;更改图标回来后焦点归零)后 clean。
5. T5(`a4814e1..65b70d5`):`TitleDialog` 系统 IME 改名,评审 1 轮修复(Critical——`onInitialTargetConsumed` 与 `tgtIdx` 靠 `rows.size` 的重新播种互相竞态,非 0 列的种子会丢;Important——MENU 未在改名时让路、输入框 `focusProperties` 缺 Cancel 出口)后 clean。
6. T6(`b2a7578`)+ 终审修复波次(`8cc5134`):零回归像素对比 + 焦点责任表 + 文档收尾,见下;全分支终审的 3 Important + 5 Minor 在修复波次一次修完(见「终审修复波次」)。

### 零回归像素对比
方法同 M3 Task 8 Step 1(Python/PIL,clock-bbox 法),但这次用**真实三行布局**(YouTube/TV设置/TV桌面/Play商店,Google ATV 镜像自带系统应用)而非空布局——M4 的长按菜单/标题/新标全挂在真实卡片上,空首页测不出东西。脚本原样输出:
```
clock bbox: (102, 70, 1557, 677)
m4 diff bbox: (99, 70, 1556, 680)
ZERO-REGRESSION: FAIL
```
raw `getbbox()` 判 FAIL,但差在 clock bbox 外的 1168 个像素逐一核实**全部只差 1/255**(单通道最小可表示量,肉眼不可辨)。根因是 `AppCard.kt` 的「呼吸光晕」——聚焦卡外发光半径按 2.5 s 线性周期呼吸(`Theme.GlowPeriodMs`,注释标「v4 实测周期」,M4 之前就有、非本轮改动),M3 当年测空首页从未触发过它(默认 `layout.json` 引用的应用在测试机上一个都没装,首页本身是空的)。按 >10/255 可见性阈值重算,基线自比(61 s 间隔)与「基线 vs M4」两组差异框几乎重合、都落在时钟本体(约 (1391–1393,70)–(1555–1556,118)),框外像素在所有阈值下最大量级仅 9/255(与 T2 记录的「glow-phase noise ≤9/255」同一现象),零结构性差异。**结论:M4 相对 M3+M6 基线无可见回归;raw FAIL 是这个脚本第一次遇到真实聚焦卡时暴露的呼吸光晕采样噪声,不是代码问题**(逐阈值数据见 `task-6-report.md`)。角落新应用计数按预期不显示(`newAppsSeenAt` 本次冷启动刚初始化,参照应用都是装机自带的旧系统应用)。

**前向缓解(T6 评审 Critical 项的裁定,终审修复波次补记)**:以后凡是带真实布局的零回归对比,**截图前先把焦点放到齿轮上**——没有聚焦卡就没有呼吸光晕,噪声源根本不存在;做不到的话就在 diff 里把聚焦卡所在区域整块遮掉/排除。脚本里 >10/255 的量级下限只作次级保险,不再当主判据(它能滤掉光晕采样噪声,但也会把真正的 ≤10/255 结构性差异一起滤掉)。另:不变量本身只对**横幅卡**成立——spec §2.3 的无横幅回落卡不看开关一律铺回落色,对比布局里若有纯图标应用,那几张卡本来就与基线不同(spec §2.2 已改写)。

### 终审修复波次(`b2a7578` → 代码 `8cc5134`,文档随后一提交)
全分支终审(base `19fe918`)判「ready with fixes」,裁定后一波修完、再做范围内复审。**3 Important**:
- **#1 `focusedCard` 改为派生,不再缓存**:`HomeScreen.cardAt(focusedCell)`,`report()` 无条件按它上报,`LaunchedEffect(loaded, focusedCell)` 在数据重载时重算。原来在焦点事件时缓存一份 `CardRef`、只在下一次焦点事件才重报,而卡片是按位置组合的(无 `key()`):移除首行首张后节点 (0,0) 原地换成原 (0,1),焦点没动、没有事件,长按弹出的是**被移除那张**的菜单(打开会启动它、卸载会卸它、移动位置 `indexOf = -1`);后台 `PACKAGE_REMOVED` 让焦点卡左侧任一张消失同形;卡→齿轮回调「新先旧后」时齿轮上长按也弹上一张卡的菜单。
- **#2 改名对话框确定键保存**:`BasicTextField` 只把 `Key.Enter` 映射到 IME 动作,第一次返回键收起输入法(对话框还在)后 DPAD_CENTER 到达 Compose 却无人接,唯一出口是返回=取消、刚打的字全丢。文本框加 `onPreviewKeyEvent`(`DirectionCenter` 抬起保存、按下抬起都吞);**不加「已提交」布尔闩**(铁律 7),重复触发由 `MainActivity.onRenameSave` 的幂等守卫(`renameTarget` 已清则返回)吸收。
- **#3 待机计时对卡片菜单 / 改名对话框让路**:输入法开着时按键到不了 `dispatchKeyEvent`,`lastInput` 打字期间不刷新,三分钟后卡片淡出、屏保从蒙版后渐入、下一键被当唤醒吞掉。`cardMenu != null || renameTarget != null` 与 `menuOpen` 同一处理:既是 key 也是守卫(铁律 6)。

**5 Minor**:#4「移除」写盘(tmp → fsync → rename)搬到 IO 线程(`lifecycleScope.launch` + `withContext(IO)`,与 `onRenameSave`/`EditScreen.persist` 同构);#5 `newAppsSeenAt == 0`(onCreate 基线写盘失败)时不算新,不再把整机应用全算成新;#6 `pickIcon` 返回 Boolean,存储没就绪时不种 `homeInitialTarget`;#10a `truncateTitle` 不拆代理对(`sanitizeTitle` 与对话框 `onValueChange` 共用同一 helper,+1 单测);#10b zh-rTW 卸载描述改「透過系統確認解除安裝;之後卡片會自動消失」。单测 **58/58**(57 + 1)。
模拟器证据(`.superpowers/sdd/2026-09-15-m4-card-menu/final-fix-shots/`,`final-fix-report.md` 逐项;菜单标题以 `uiautomator dump` 的文本为准,不只看截图):移除 (0,0) 后长按新 (0,0),菜单标题为原 (0,1)「Cast moderator」而非被移除的 YouTube(`22/23-a1a-*`);后台 `pm disable-user` 掉焦点卡左侧的应用后长按,菜单命名的是高亮卡「Android TV Home」(`17/18-a1c-*`);卡→齿轮后长按齿轮只出齿轮菜单、无卡片菜单(`28/29-a1b-*`);中列 (0,1) 移除后焦点留在同行邻卡、菜单命名它(`26/27-a4-*`);改名对话框输入 NewName → BACK 收起输入法(对话框仍在,`mInputShown=false`)→ 确定键保存,`titles.json` 与卡下标题均为 NewName(`05–07-a2-*`);Enter 仍保存(`10-a2-*`)、BACK×2 取消不保存(`13–15-a2-*`);对话框开着等 3 分 45 秒(05:16:46 → 05:20:31)卡片不淡出、无屏保(`03/04-a3-*`,卡片区亮度逐位不变)。

### 决策(Gordon,spec §0)
1. 范围切分:**M4 = 长按菜单 + 卡片标题渲染 + 新应用标记;M4b = 行数 1–5 与行的增删/命名/图标 + HDMI-CEC 父子去重 + 输入源逐项隐藏/改名 + 原地移动**,分开评审、分开真机验。
2. 「移动位置」:M4 只做兜底(跳编辑页定位到该卡),原地移动态(卡片抬起/左右换位/上下换行)后置到 M4b 之后,等长按菜单真机验过再做。
3. 卡片标题位置:卡片下方一行(Projectivy 同款,不遮横幅)。

### 延后项(带去向)
- **M4b**:行数 1–5 与行的增删/命名/图标、HDMI-CEC 父子去重、输入源逐项隐藏/改名、原地移动。
- **真机(Gordon)**:长按手感(0.4 s 是否合适)、`TitleDialog` 用索尼输入法输中文、DPAD_CENTER 与 KEYCODE_ENTER 哪个当保存手势、改名时 IME 的返回键要按几次、**BACK 隐藏 IME 后按确定键是否保存(本波已实现,索尼输入法下复核)**。
- M6 的 `ImportScreen` `ON_STOP` 关页逻辑与 M4 无接口交叉,本轮未碰、未验,维持既有行为。
- **M4b(外观)**:输入源行不渲染标题,但纵向溢出公式对每一行都按 `titleHeight` 算——两个开关都开着、焦点落在输入源行下方时整列多上移一个 `titleHeight`(纯外观,M4b 重做输入源行时一并处理)。
- **架构跟进(终审建议)**:把首页焦点记忆(`tgtRow/tgtIdx`)提升到 `setContent` 层 `remember` 的一个 holder 里,让每个**替换**首页的界面(四个选择器、编辑页、设置页、导入页)回来都能还原,从而退役 `initialTarget`/`seedTarget`/`onInitialTargetConsumed` 这套接线及它缓解的两条延后项(picker 类浮层回来落 (0,0);`initialTarget` 只覆盖「改图标」一条路)。今日实况:「移动位置 → 编辑页 → 返回」落回 (0,0),因为 `leaveEdit()` 会清种子。
- **既有问题,非 M4 引入**:设置页能改 `Settings.idleAfterMs` / `idleContent`,但没有任何地方消费它们——`MainActivity` 仍是 `delay(Theme.IdleAfterMs)` 常量。M4 之外另立跟进。
- **同根小项(A5 的编辑页兄弟,留手)**:`EditScreen` 的「新」角标以 `seenAtBefore = newAppsSeenAt` 为基准,基线写入失败(外置存储晚挂载)时它是 0,首次打开「添加应用」列表会把全部候选标「新」;`:573` 的写入成功后自愈。首页计数已在终审修复波加了 `== 0L → 0` 门控,列表侧未加。

### 注记
- `titles.json` 与 `layout.json` 各自独立存储(同一应用在两行共用一个标题,零迁移成本)。
- 新应用角落计数的位置改了两次才定案:左下角会和开着标题的底行卡片标题重叠;左上角在底行聚焦、整列上移时被首行卡片盖住;最终并入状态栏(齿轮左侧)——「卡片上移时从状态栏下方穿过」本来就是齿轮/时钟已接受的既有行为。
- `buildRows` 会丢弃未安装的包和整行为空的行,渲染下标 ≠ `layout.json` 下标——`Row.layoutRow` 在过滤前赋值,「移除」「移动位置」一律按 `(layoutRow, pkg)` 寻址,绝不用渲染列号(T4 评审 Critical 项,CLAUDE.md 七条第 5 条的又一实例)。
- `HomeScreen(initialTarget)` 的焦点种子在组合创建时用 `remember` 冻结成 `seedTarget`,经回调消费一次后由 `MainActivity` 清空——T5 评审揪出 `tgtIdx` 靠 `rows.size` 重新播种会与种子消费竞态(非 0 列的种子会静默丢失),是「目标与当前位置必须拆开」铁律(第 5 条)的第三次实例。
- 输入源行(`RowKind.INPUTS`)长按只吞按压、不出菜单。
- 清单原缺 `REQUEST_DELETE_PACKAGES`(API 26+ `ACTION_DELETE` 必需),卸载点了没反应、`startActivity` 仍返回成功——T4 修复。
- MENU/BACK 在卡片菜单、改名对话框开着时要先关自己再冒泡,否则会在看不见的那层上叠出齿轮菜单/丢焦点。
- T4 实施代理在 `HomeScreen` 里顺手加了两处任务书之外的焦点修复(打开浮层时冻结目标;`stale` 重载时冻结)——评审专门复核,判定 clean,没有引入铁律第 6 条那类漏洞。
- onCreate 的 `newAppsSeenAt` 基线写入现在有读门控(先 `SettingsStore.read` 判 `== 0L` 再写);T3 初版是无条件写、每次冷启动都写一次——T3 评审 Important 项,T4 一并修。
- 已知技术债(非阻塞,留手):`countNew` 重新枚举了一遍 `Apps.load` 刚算过的东西(需要 `buildRows` 暴露那张 map 才能省掉);`parseTitles` 容忍尾随逗号、`readString` 对任意 `\X` 都转义(应只认 `\"` `\\`);角落计数与添加列表「新」标的「新」口径不完全一致(前者不含已上桌面的,已记录非 bug);添加列表候选项走的「便宜路径」仍对每个已装应用产生一次 `getPackageInfo` IPC;`fontScale > 1.15` 时固定行高的标题会被裁;后台 `PACKAGE_CHANGED` 触发的 reload 冻结窗口可能吞掉一次方向键;壁纸选择器/屏保图库/默认桌面卡等 picker 类浮层仍是整体替换 `HomeScreen`(M3 已记录的既有行为),`initialTarget` 种子只覆盖了「改图标」这一条长按菜单路径,其余 picker 回来焦点仍落 (0,0)。
- **工具链事故一则**:T4 修复轮的一个 opus 实施代理在验证阶段撞上 `claude-opus-5` 的 API 403 中途死亡,改动留在 worktree 未提交;由一个 sonnet 代理从这份未提交的 diff 接手,核对后补完验证并提交。后续若当晚再撞 403,优先换 sonnet/fable 而非 opus。

spec 状态行已改为「已实施(commit `8cc5134`),待真机验」(T6 时为 `65b70d5`,修复波次后更新)。

## 2026-09-16 · M4 合并进 main(本地,未推送)+ 通宵委托收口

- 合并 `m4-card-menu`(HEAD `5c96f3e`,merge-base 就是 main `19fe918`,无冲突)→ main `bb629d1`。合并树 `testReleaseUnitTest assembleRelease` 绿,58 单测。
- 终审(fable,全分支)判「可合并,需修复」:3 项 Important(长按菜单在无焦点事件的重载后可能作用到错的卡片——`focusedCard` 改为按 `focusedCell` 推导而非缓存;改名对话框 BACK 收起 IME 后确定键失效——确定键现在保存;待机计时忽略卡片菜单/改名对话框——加入 key+guard)+ 7 项 Minor 一次修复波落地(`8cc5134` 代码、`5c96f3e` 文档),scoped 复审判「可合并」,残留 3 处文档行(计划零回归不变量 ×2、spec 单测函数名)与 1 条延后备案在本 commit 补齐。细节见 m4 分支 WORKLOG 的「M4 收官」节与 `.superpowers/sdd/2026-09-15-m4-card-menu/`。
- 通宵委托(Gordon 2026-09-15 夜「未来 12 小时都交给你」)四项:M3 合并 ✅(`e1a6a4e`)、M6 合并 ✅(`61e5c73`)、M4 计划+实施+终审+合并 ✅(`bb629d1`)、WORKLOG 同步 + 醒来清单 ✅(本节)。全程零 push、零提问卡;所有裁定以 `Ruling:` 落在三份 ledger。
- 分支/worktree 现状:根目录已切回 `main`(合并与构建在临时 worktree `.claude/worktrees/main` 做完后删除);`m3-wallpaper`/`m3-final`/`m3-engine`/`m3-settings`/`m6-upload`/`m4-card-menu` 全部已并入 main,可删;`worktree-agent-a21dcfb91b5103162` 持有 M5 spike(`.superpowers/spike-m5/unitedu-dream-spike.apk`),M5 验完再删。

### 醒来看的清单(只列必须 Gordon 做的)
1. **推送**:main 领先 origin 70 个 commit(M3+M6+M4),确认后由我 `git push origin main`。
2. **M5 电视 spike**:电视开无线调试 → 配对码给我 → 我装 `.superpowers/spike-m5/unitedu-dream-spike.apk` → 你看系统「屏保」列表是否出现 UnitedU。
3. **T9 真机调参**(M3):设置页「壁纸」组用滑块调模糊/压暗到满意,报我数值改成默认。
4. **M6 手机扫码**:齿轮 → 导入图片 → 手机扫码 → 传一张壁纸/删一张,看电视端计数与首页壁纸。
5. **M4 真机手感**:长按 0.4 s 是否合适;索尼输入法改名输中文;IME 收起后确定键保存;改名时 IME 的返回键要按几次;卸载走系统确认页是否正常。

## 2026-09-16 · M5 spike 真机结果(通过)+ 电视 adb 通道

- **M5 spike 通过**:把 M2 版 launcher 加一个最小 `DreamService`(`UnitedUDream`,分支 `worktree-agent-a21dcfb91b5103162` 4f184b7)装到 A95L 后,系统「设置 → 屏保」的选择列表出现 **UnitedU**(与 睡眠/万花筒/BRAVIA屏保/咪视界/生活空间装饰 并列;`pm query-services -a android.service.dreams.DreamService` 也列出 `com.uniteduone.launcher/.UnitedUDream`)。**M5 可以按 DreamService 路线做**,不需要绕开 Sony 的屏保框架。未测「立即启动」渲染(spike 只画一屏,留 M5 实作时验)。
- **电视 adb 通道**(本次踩坑,已写进 CLAUDE.md):配对成功后 `adb connect` 一直 `offline`,原因是端口——mDNS 广播的 39055 是休眠前的过期记录,全端口扫描扫出的 20 个开放端口没有一个是 adbd;最终以电视「无线调试」页显示的 38673 一次连上。结论:**连接端口只信电视页面显示**。
- UnitedU 不是这台电视的当前桌面(`com.gordonwang.tvhome` 是),spike 覆盖安装不影响使用;随后用合并后的 main 构建覆盖回去做 T9/M6/M4 真机项。
- **M6 服务端真机通过**(合并后 main 构建,导入页开在 `http://192.168.1.22:8090/`):`/api/list` 列出 6 张内置 + `legacy-wallpaper.jpg`(M2 时代真机上的 `wallpaper.jpg` 被 M3 迁移逻辑正确改名);从 Mac `curl -F files=@…png` 上传 → `{"saved":["from-mac.png"]}`,电视端计数同步为「已收到 1 个文件 · 最近:from-mac.png」;不带 `X-Requested-With` 的 POST → 403。手机扫码页面体验留 Gordon。

## 2026-09-16 · 真机回填(Gordon A95L 三项验收 → 两处改动 + 一个新问题)

- **M6 手机扫码通过,一处瑕疵**:手机传中文名图片,落盘与列表都成乱码。根因:浏览器的 multipart `Content-Type` 只带 boundary、不带 charset,NanoHTTPD 2.3.1 就按 **US-ASCII** 解 part 头,文件名的每个非 ASCII 字节变成 U+FFFD(`sanitizeUploadName` 本来就保留 Unicode,不是它的问题)。修法:`parseBody` 之前把 `; charset=UTF-8` 补进会话的 `content-type` 头(`IHTTPSession.getHeaders()` 返回内部那张 map),纯函数 `utf8MultipartContentType` + 单测;电视上已有的乱码文件从手机页删掉即可。
- **T9 收口**:Gordon 定默认 = 原片(模糊 0%、压暗 0%)——就是现有默认值,不改代码。追问「能不能提亮」→ 设计取舍待定(见下一节)。
- **M4 真机全过**;长按时长从「第一个重复事件(≈0.4 s)」改为**按时长判 ≥ 600 ms**(`LONG_PRESS_MS`):首次重复延迟与重复频率由固件定,按时长跨设备一致;未满时长松手仍是普通点击。模拟器验证法:`settings put secure long_press_timeout 700` 后 `input keyevent --longpress`(该命令只注入一次 repeatCount=1、eventTime=downTime+long_press_timeout 的重复事件,默认 500 ms 不到阈值、不出菜单——正好证明阈值生效)。
- **僵尸卡(Gordon 真机发现)**:从长按菜单卸载应用后首页卡片消失(`buildRows` 过滤未安装包),但「编辑桌面」原位留着一张暗红「未安装 com.dangbei.dbmusic.sonyos.tab」——`layout.json` 从没被清理,编辑页按 `Layout.read` 原样画。修法:**只在真正卸载事件上清**(`PACKAGE_FULLY_REMOVED`,更新不发它):`Layout.withoutPackage`(纯函数,`LayoutTest`)→ `Layout.removePackage` + 顺手删 `titles.json` 里的自定义标题(`pruneUninstalled`);桌面活着时 `MainActivity` 的动态接收器**清完再** `revision++`(先 bump 编辑页会按旧文件重载),桌面进程不在时清单注册的 `PackageRemovedReceiver`(该广播在隐式广播白名单里)起进程落盘。**绝不按「未安装」在读取时清理**:默认布局里没装的包装上就该自动出现,更新过程中包也会短暂不存在。模拟器验证:桌面运行中 `pm uninstall --user 0 YouTube` → layout 里没了;桌面退到后台被 `am kill` 后再卸载 → `ActivityManager: Start proc … for broadcast …PackageRemovedReceiver`,同样清掉。**验证方法论**:`am force-stop` 之后的应用处于 stopped 状态,系统**不给它发任何隐式广播**,用它模拟「进程不在」会得出假阴性——要用 HOME 退后台 + `am kill`。Gordon 电视上已有的那条僵尸记录用 pull/改/push `layout.json` 手工清掉(A95L 的 shell 能读写 `/sdcard/Android/data/<pkg>/`)。

## 2026-09-16 · 「压暗」改「亮度」双向滑块(Gordon 真机调参后的追问:能提亮吗)

- 决策(Gordon,三选一):**一根「亮度」滑块 −50…+50%,10% 一档,0 居中 = 原片**(不做 ±100/20% 档,也不另加一根提亮滑块)。默认 0/0 = 原片就是他定的 T9 默认值,代码默认未动。
- 机制:压暗本来就是 RGB 整体乘 (1 − dim);提亮是同一个乘法系数 >1,超白由 `ColorMatrix` 应用时截断——同一条管线、同一个矩阵(`wallpaperColorMatrix(themed, accentRgb, brightness)`,k = 1 + b/100)。
- 存储:`Settings.wallpaperBrightness`(−50…50 步 10)取代 `wallpaperDim`;旧文件只有 `wallpaperDim` 时读入换算成 −min(dim, 50),两键并存以新键为准,写盘只写新键(`SettingsTest.legacyWallpaperDimMigratesToNegativeBrightness`)。缓存键版本 v1 → **v2**:第 7 段数字含义反了(50 压暗 vs +50 提亮),不升版旧缓存会被错配。
- 设置页:控件 6 变双向滑块(`Ctrl.zeroAt = 5`):填充段从正中画到当前档、中点一道刻度、文字带正负号;控件下标不变,焦点账本不动。
- 文档:DESIGN §主题化壁纸开关、M3 spec(决策表 / §3 / 控件表 / 防抖)同步改。

## 2026-09-16 · 真机第二轮:删主题化壁纸 + 主题色全面接线

- **两个决策(Gordon,真机第二轮)**:①「主题化壁纸」**彻底删除**——用户要么选内置图(本来就按预设色生成),要么传自己的照片、要的就是原图;它与「跟随壁纸主色」并存只会让人分不清哪个在管颜色。② 主题色**全面接线**——原先所选主题色只到首页四处(齿轮 / 光晕 / 时钟 / 行标题),其余界面全部写死 `Theme.Champagne` / `Theme.ChampagneGold`,换了预设设置页、菜单、编辑页还是香槟金。
- **删主题化(commit 3242b28)**:`Settings.wallpaperThemed` 字段 / 解析 / 写盘一并删;旧 settings.json 里的键按未知键忽略(扁平 tokenizer 只认列出的字段,`SettingsTest.legacyWallpaperThemedKeyIsIgnored` 钉住)。`WallpaperSpec(file, blur, brightness)`,`wallpaperSpecOf(s)` 不再带 accent;`wallpaperColorMatrix(brightness)` 只剩亮度对角阵(去色 / 染色分支与 Rec.709 常量删);缓存键去掉 themed / accent 两段,版本 v2 → **v3**(键形状变了,旧缓存永不命中、由 LRU 淘汰)。`Wallpapers.load` 的 followColor 取色分支删,`paletteAccent` 保留——它仍给「跟随壁纸主色」供色。三语字符串 `settings_wallpaper_themed` 删。**壁纸 spec 从此不认识主题色**:换预设、开关跟随、取色落地都不触发重处理(单测钉住)。
- **设置页控件 12 → 11**:壁纸分组只剩 轮播间隔 / 模糊 / 亮度(下标 3..5,`WALLPAPER_CTRLS = 3..5`),其后主题 / 时钟 / 待机全部前移一位。看门狗核对:下标只在 `controls` 列表、`order` 表、`WALLPAPER_CTRLS` 三处出现,`ctrlCount` / `rowFocus` / `controlTop` / 上下 requester / 看门狗落点全由它们派生,没有别的字面量;`previewKey` 改成 `Pair(blur, brightness)`。
- **零回归证据**:模拟器,默认设置,基线 7591c3c 的 APK 与删后 APK 在**同一分钟内**各截一张首页(齿轮聚焦)→ **整帧逐像素一致(0 像素差)**;删后那次 settings.json 里故意留着 `"wallpaperThemed": true`,启动后被静默忽略并重写掉。「同一分钟」是必要条件:DM Sans 数字不等宽,时钟文字宽度每分钟变 1–2 px,右上角整条状态栏(含齿轮)跟着平移——跨分钟比对会在齿轮边缘看到 ~1200 个像素的假差异(同一构建两次启动也一样),这是布局不是回归。
- **全面接线(commit fe1ffa5)**:机制 = 一个 `staticCompositionLocalOf`——`LocalThemeColors`(ThemePresets.kt),`MainActivity.setContent` 顶层 `CompositionLocalProvider(LocalThemeColors provides themeColors)` 提供一次(预设,或跟随时的壁纸取色);全部 `Theme.Champagne` 改读 `.highlight`、`Theme.ChampagneGold` 改读 `.accent`,`.copy(alpha)` 调制不动。HomeScreen / CategoryRow / AppCard / Clock / GearButton 去掉 accent / highlight / glowColor 参数改读 local——一条线,没有第二来源(编辑页卡片光晕也跟着走了)。`Theme.Champagne / ChampagneGold` 常量保留作标定记录,界面代码不再引用。**金预设逐位一致的原因**:金的 highlight / accent 正是 #FFF5DC / #C0A73A,就是那两个常量的值。
- **接线证据**(截图在 `.superpowers/sdd/theme-cleanup-shots/`):金默认——首页(同分钟整帧)0 像素差、设置页 0 像素差、齿轮菜单时钟框外 0 像素差、编辑页只有聚焦卡呼吸光晕 ≤3/255;`themePresetId=blue`——齿轮 #63809E、设置页分组标题 #6E8FB0(= 蓝 accent),选中段 / 编辑页标题与「＋」/ 添加应用列表 / 选择器标题 / 导入页地址 / 修改标题对话框 / 齿轮菜单与长按菜单标题 全部 #BECDDB(= 蓝 highlight);`followWallpaperColor=true`——同一批元素全部换成取色结果(内置金雾图 → accent #181808 / highlight #8D8D85;自造鲜橙测试图 → #382000 / #A09385,色相 31–34 暖色)。
- **发现(未改,待 Gordon 定)**:`paletteAccent`(vibrant → dominant)对暗底壁纸给出的主色**很暗**——M3 验收时蓝图取到 (14,22,29) 也是近黑,当时只影响四处所以没显眼;现在它铺到整套界面,「跟随壁纸主色」开着时设置页分组标题 #181808 压在 #0A0A0A 底上几乎看不见。取色算法本身没动。可选修法:优先 `getLightVibrantColor`,或对 accent 做明度下限。
- 单测 62 → 59(删 4 条主题化用例,加 1 条旧键忽略);`gradle --no-daemon testReleaseUnitTest assembleRelease` 三个提交都绿。文档:DESIGN §3、M3 spec、TVHOME-README 同步(历史实施计划 `plans/2026-09-15-m3-wallpaper.md` 不改写)。
- **接线后立刻暴露的老问题(本波顺手修)**:「跟随壁纸主色」从内置深色壁纸取出的主色近黑(蓝底 ≈(14,22,29)),主题色一流到每个界面,设置页分组标题等深色文字压在 #0A0A0A 上直接消失。加 `usableAccent`(HSL 亮度地板 0.62;仅在原本有色相 s≥0.08 时把饱和度抬到 0.45,纯灰壁纸保持中性浅灰不凭噪声造色,色相始终不动),只用在跟随壁纸这条路,预设不动。`ThemeColorTest` 4 项;模拟器跟随蓝底实测分组标题为可读浅蓝(见截图)。

## 2026-09-16 · 主题色一致性修复 + 主题化卡片 + 回落底改边缘色(Gordon 真机第三轮)

- **A 一致性(bug)**:换预设时齿轮变色、首页分栏标题却「没变」——根因是两个角色色:标题/分栏图标用的是 `highlight`(accent 混 55% 白,六个预设都接近白,肉眼几乎无差),齿轮用的是饱和 `accent`;分栏图标更是写死 `Theme.RowTitle` 白、根本没接线。跟随壁纸主色时 highlight 带色调,于是「跟随会变、换预设不变」两种效果不一致。修:分栏标题 + `RowIcon` 都改读 `accent`(与齿轮同色);卡片聚焦呼吸光晕仍用 highlight(要浅不刺眼)。**注意这对默认金预设不是零回归**:标题从近白 #FFF5DC 变成金 #C0A73A,是 Gordon 要的一致性,非回归。
- **B 主题化卡片(新开关)**:`Settings.themedCards`,设置页「主题」组第 3 行。开启后所有应用卡片去色→染 accent(`cardTintMatrix` = 删掉的壁纸管线同一手法,卡片图小、用 `ColorFilter.colorMatrix` GPU 现染不走缓存;有图的卡再统一铺 `accent α0.20` 底)。编辑页不染(要认应用)。`CardColorTest` 4 项。
- **回落底改边缘色**:纯图标卡的回落底从「整图 Palette 主色」改成「图标最外一圈均色」(`edgeColor`)——Palette 常挑到 logo 图形色,铺底和图标边缘割裂像硬包一圈;边缘色则与图标融为一块。透明边(有效像素 < ¼)返回 null 回落到占位底。`EdgeColorTest` 3 项。
- 单测 63 → 71(+8);`assembleRelease` 绿;模拟器截图 off/on-gold/on-blue 见 scratchpad。分支 `theme-cards`,待 Gordon 真机看过再并 main。
