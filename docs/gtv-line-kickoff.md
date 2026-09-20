# gtv 线开工说明(Gordon 说「继续,开 gtv 线」时,从这份文件开始)

> 用途:这条线上一个会话里已经定好了方向、备好了素材,但还没写一行代码。新会话读完本文即可直接开工,**不需要再问 Gordon 已经定过的事**。最后更新 2026-09-20,main 在 `83091b1`(已推 GitHub)。

## 1. 这条线是什么

在 main 之外单开一条 UI 线,**全 app 复刻真 Google TV 的界面**,做完与现在的 UnitedU 并排比较,由 Gordon 挑一套作为 1.0 的界面。Gordon 2026-09-19 的原话:「fork 一条 UI 线,完全复刻 Google UI 系统」。

## 2. Gordon 已经定过的(不要重问)

| # | 定了什么 | 备注 |
|---|---|---|
| 1 | **对标真 Google TV**(`com.google.android.apps.tv.launcherx`),不是模拟器自带的 Android TV 旧桌面(`com.google.android.tvlauncher` 7.7.15) | 他同意下载官方 google-tv 模拟器镜像实测,也同意我在开机向导里点「同意 Google 条款」;**不登 Google 账号**(密码不代输) |
| 2 | **范围 = 全 app**:首页 + 设置页、齿轮 / 长按菜单、对话框、图片选择器、编辑页、首次引导、关于页;首页先做 | 估时:实测约半天;实施约 8 个工作日(首页约 4 天先出) |
| 3 | **「Google 的壳、UnitedU 的内容」**:布局照搬 Google;推荐位换成用户自己的东西(如大海报位放壁纸);换壁纸 / 主题色 / 屏保 / 卡片标题等独有功能保留,外观改 Google 样式;每项落点在「逐项对照表」里定 | 产品原则不变:零广告零推荐 |
| 4 | **独立包名并存**:复刻版用 `com.uniteduone.launcher.gtv`、应用名「UnitedU GTV」,与现在的 UnitedU 同时装在 A95L 上来回比;两边设置与图片各存各的 | |
| 5 | 差距报告遗留的两个决定(6 小时子集、M8 有意偏离要不要改回原生)**并入这条线**,不再单独决定 | 报告:`docs/research/2026-09-19-google-tv-home-gap.md` |
| 6 | 顺序:**先把 main 做完再开这条线** | 已完成:遗留 bug 批 + M4b + 两个真机补丁,全部真机验收通过并推送(WORKLOG 2026-09-19 / 2026-09-20 各节) |

## 3. 手上已有的素材

- **Google TV 系统镜像已下载并校验、解压好**:`~/Library/Android/sdk/system-images/android-34/google-tv/arm64-v8a/`(`system-images;android-34;google-tv;arm64-v8a`,`arm64-v8a-34_r03.zip`,869,585,766 字节,sha1 `a5ecfa06ee6e4b5d5262d47dffb84136ce1645d1`)。**AVD 还没建**。
- 现有 AVD:`unitedu-tv`(开发用,Android TV 镜像)、`unitedu-tv-2`、`unitedu-tv-3`(同镜像备用)。`~/.android/avd/unitedu-tv.avd/config.ini` 是手写 AVD 的样板(本机没有 `avdmanager`,照抄它、改 `AvdId` / `image.sysdir.1` / `tag.id` / `tag.display` 即可;Google TV 的 `tag.id=google-tv`、`tag.display=Google TV`)。
- 差距报告 `docs/research/2026-09-19-google-tv-home-gap.md`(对标的是 7.7.15 旧桌面,数值仍可参考)+ 19 张对比截图 `docs/screenshots/gtv-compare-*`;设计调研 `docs/research/2026-09-17-android-tv-native-ui-design-refs.md`;M8 spec `docs/superpowers/specs/2026-09-17-m8-home-visual-refresh-design.md`(§0 记着当初有意偏离原生的几条)。

## 4. 第一步该做什么

1. 起 Google TV 模拟器:按上面的样板建 AVD(建议名 `unitedu-gtv`),`emulator -avd unitedu-gtv -no-snapshot -no-audio -gpu swiftshader_indirect &`,**另一台模拟器同时开着时注意内存**(本机 16 GB,两台各 2 GB);走开机向导(可点同意条款;不登账号,选「基础电视 / 跳过」之类的路径),记下 launcherx 的版本号。
2. 派一个子代理做**实测**(照 2026-09-19 那次差距报告的做法):截图 Google TV 的首页(默认态 / 焦点下移 / 顶栏聚焦)、应用页、设置面板、对话框、菜单,按像素量测(PIL 扫描阈值取边、cap height 反推字号),产出一份新的报告 `docs/research/YYYY-MM-DD-google-tv-launcherx-measurements.md`。注意:不登账号时首页很可能是简化形态,报告里要写明「量的是哪一版、哪种形态」。
3. 量完出**逐项对照表**问 Gordon(grilling 第三轮):字体(Google Sans 能不能合法内置要查许可)、时钟位置、焦点样式(1.29 左轴无描边 vs 现在 1.1 + 描边)、行锚定、压暗、peeking、tabs / 搜索 / 账号这些位置放什么、主题色何去何从。**出卡前把材料写进正文,并把要点塞进卡片问题本身**(记忆 `card-prose-not-rendered`)。
4. 定完写 spec → plan → 用 subagent-driven-development 执行;分支建议 `gtv`,worktree `.claude/worktrees/gtv`,独立包名的改动作为第一个任务。

## 5. 要守的约束

- 本文件与 `CLAUDE.md`(七条焦点铁律、模拟器的坑、推 GitHub 的坑)、`docs/DESIGN-unitedu-open-source.md`(产品定义与现状)一起读;焦点责任表在改任何界面前必看。
- 模拟器命令一律带 `-s <序列号>`(两台模拟器并存时尤其重要);**绝不向 A95L(192.168.1.22:38673)发按键 / 装包 / 改设置**,除非 Gordon 要求装包验收。
- 不推 GitHub,直到 Gordon 说「推」;推法见 CLAUDE.md「推 GitHub 的坑」。
- 电视的默认桌面(HOME 角色)现在是 UnitedU,**不要去改**(记忆 `a95l-default-home-stays-tvhome`)。
- main 上仍开着的已知 bug(不属于这条线,别当新 bug):鼠标 / 飞鼠点击后齿轮菜单与设置页丢焦点;搬卡离开行尾最后一格时焦点闪一帧;WORKLOG 2026-09-19「遗留修复批」与 2026-09-20 两节里还有一份「留给以后」的清单。
