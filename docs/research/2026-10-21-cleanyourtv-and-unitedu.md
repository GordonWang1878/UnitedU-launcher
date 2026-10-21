# CleanYourTV 研究与 UnitedU 结合方案讨论稿

> 日期：2026-10-21
> 状态：研究 / 决策讨论稿，供 Gordon 与其他 agent 独立评审；**不是已批准的产品设计或实施计划**。
> 研究对象：[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)，本次读取的源码快照 `687b5ee`（下文称 CYTV）。UnitedU 对照本地当前源码及正式发布 `v1.2.0`。
> 范围：产品形态、技术实现、优势与不足、可结合部分、默认 HOME 限制、实施方向与验证要求。
> 验证边界：阅读源码、文档、部分 issue，并在本机运行测试 / 构建；**没有把 CYTV 装到电视或模拟器，也没有实际执行它的 debloat / 恢复脚本**。他人的设备报告不等于本项目复验。

## 0. 执行摘要

1. **CYTV 不只是 Launcher**。它是「AI 辅助安装流程 + ADB 工具 + 机型精简清单 + 修改前记录与恢复 + 一个轻量 Launcher」的组合。
2. **UnitedU 是完整的日常电视桌面产品**：用户编排首页、编辑、频道、壁纸 / 屏保、输入源、资料库、自我更新与低侵入 HOME 接管是主线。
3. 两者**技术上互补，用户需求上部分竞争**。追求干净首页的用户可以二选一；但 CYTV 的设备知识与安装工具可以服务 UnitedU，不需要合并两套 UI。
4. 值得吸收的优先项是：**真实设备档案、安装诊断与回滚、Agent 交互入口、厂商输入源 / 遥控器适配、公共 CI**。不是简单复制它的桌面。
5. 建议保留一个正式签名、正式包名的 UnitedU APK，在外围增加可选的 **UnitedU Agent Setup**。所谓“新版本融合”可以是一次包含工具和文档的产品发布，不必做另一种 APK。
6. **结合后不保证任何电视都能设为默认桌面。**AI 不增加 ADB shell 权限；停用其他 HOME 候选不等于获得系统权限，更不能普遍绕过厂商签名、安装校验、框架白名单或硬编码。
7. 尚不能量化可新增覆盖多少机型。当前证据支持“值得验证的扩展路径”，不支持“必然突破”或“已经解决大部分机型”。

---

## 1. 讨论背景与需要回答的问题

Gordon 提出的方向：

- CYTV 更偏系统去广告，UnitedU 更偏美化、精简和可控的桌面，两者可以结合。
- 认可「给用户一个仓库与 prompt，让 AI agent 完成安装与诊断」的分发方式，希望吸收。
- 询问结合后是否必然能解决无法接管系统 / 无法成为默认桌面的痛点。
- 希望其他 agent 参与独立判断，而不是顺着既有结论实施。

本稿要回答：

1. CYTV 实际交付了什么，而不只是宣传什么？
2. 哪些能力 UnitedU 缺少，哪些只是产品取舍不同？
3. AI 安装应放在哪里，是否需要新的 APK？
4. 哪些 HOME 限制有解，哪些仍然没有通用解？
5. 怎样做最小验证，才值得进入实施？

## 2. 证据与可信度

### 2.1 本轮实际检查

- GitHub 仓库元数据、README、PROMPT、Release 资产情况。
- `examples/` 机型目录的数量与部分代表性清单，包括国行 TCL、小米等。
- `tools/disable.sh`、`tools/recovery.py`、恢复与安装交接相关测试。
- Launcher Manifest、Gradle 配置、`MainActivity.java`、配置项、HOME 服务、输入源与按键接口、主题和 Module Cards 协议。
- UnitedU Manifest、HOME 接管服务、README、兼容研究、发布资产与测试目录。

本机实跑：

| 检查 | 结果 |
|---|---|
| `python3 -m unittest discover -s tests -v` | 共 58 项：57 通过、1 跳过；跳过项为 README 计数严格检查 |
| `./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease` | 成功；先载入 UnitedU 的本地工具链环境 |
| CYTV release APK | 403,051 字节，约 393.6 KiB |

**这些测试不证明真实电视安全或遥控体验可靠**。脚本测试主要验证逻辑和模拟响应；没有做同机性能对照。

### 2.2 社区规模快照

查询时 CYTV 约 12,364 stars、1,387 forks；贡献者接口第一页返回 100 人；`examples/` 有 123 个目录。仓库创建时间为 2026-10-17，仍是非常新的项目。数字随时变化，只用于说明社区反馈规模，不作为质量评分。

**123 个设备目录不等于 123 台电视完整支持其 Launcher**：可能只有 debloat 成功、Launcher 被拒绝、功能部分成功或仍有未验证项。

### 2.3 结论标注原则

- **代码事实**：源码、Manifest、脚本直接可见。
- **本机验证**：本轮跑过的测试与构建。
- **外部报告**：上游设备记录 / issue，未复验。
- **推断 / 建议**：对 UnitedU 的产品判断，不能当作已实现或兼容承诺。

## 3. CYTV 是什么形态的产品

### 3.1 五层组成

```text
用户给出电视型号与偏好
          ↓
PROMPT.md / AI 编程助手（也可手动执行）
          ↓
设备探测 + 机型知识库
          ↓
ADB 工具：备份、停用、安装、HOME 交接、恢复
          ↓
轻量原生 Launcher + 时钟屏保
```

它不是一个必须联网运行的 AI 桌面。AI 主要运行在电脑上，帮助安装和配置；装好的 Launcher 不需要 AI 才能使用。

### 3.2 典型安装流程

1. 用户打开电视调试功能，电脑连接电视。
2. Agent 读取设备和包清单，查询对应机型，询问保留哪些功能。
3. 记录修改前状态。
4. 用 `pm disable-user --user 0` 停用选定包，不卸载系统包。
5. 构建 / 安装 Launcher，显式启动并交接 HOME。
6. 检查结果，失败时恢复；完成后关闭调试。

仓库提供手动脚本，不是只有 AI 才能安装。主要工具有 Python ADB 客户端、分块传输、disable / restore、主题推送、构建安装脚本及 PowerShell 对应版本。Python 客户端是连接工具，不会获得高于普通 ADB shell 的权限。

### 3.3 去广告的实际范围

CYTV 可停用厂商广告、推荐、遥测和部分预装服务，因此改变的是系统运行的组件，不仅是首页外观。

限制：

- 不能据此保证电视所有广告 / 遥测都消失。
- 不会自动去掉流媒体应用内部广告。
- 可选 Private DNS 只对部分广告域名有效，不去除同域提供的 YouTube 视频广告。
- DNS 服务商会看到解析请求；网络可达性和播放需另测。
- 部分精简清单包含更新、语音、投屏等组件，必须按用户需求取舍，不能统称“垃圾”。

UnitedU 的无广告主要保证 **UnitedU 自身首页**；没有承诺清理厂商整个系统。

## 4. CYTV Launcher 的技术特点

### 4.1 实现与预算

- 包名 `com.gathin.tvhome`，Java + Android View / RecyclerView。
- 依赖包括 RecyclerView、DynamicAnimation；不是 Compose。
- `minSdk 26`（Android 8.0），`targetSdk 34`。
- Release 开启 R8 和资源压缩；CI 要求 APK 小于 409,600 字节。
- 默认构建使用本机 debug keystore 签名，适合个人自建，不是统一官方签名发布链。
- Manifest 未声明 `INTERNET`；网络状态和 SSID 显示另用状态 / 位置权限。
- 面向弱硬件，强调 1GB RAM；高级动效默认关闭，有 reduced-motion 配置。

**APK 大小不等于运行内存，也不证明同机帧率更好**。其功能和资源远少于 UnitedU，不能把大小差直接解释为 UnitedU 效率差。

### 4.2 首页与配置

- tvOS 风格：Dock、应用网格、聚焦应用名称、环境色光晕、移动高亮。
- 网络、设置、输入源按钮；时钟和问候语。
- 可选 CPU / 内存 / 存储状态，前台更新。
- 漂移时钟 DreamService 屏保。
- 电视端可改部分列数、动效等设置；大量配置通过 XML 或外置主题 JSON。
- 外置主题可配置 Dock、隐藏应用、列数、壁纸轮换、配色、按键、私有输入 action 等；还有节庆、时间背景、F1 日历等可选内容。
- 可选 App Library：显示普通 Android 与 TV 可启动应用，最近安装 / 未放首页优先，支持添加、移出、打开、应用信息。
- 19 种界面语言；希伯来语有专门 RTL 布局和方向键处理。不能推断所有 RTL 语言均有同等完善适配。

### 4.3 厂商与遥控器适配

配置提供：

- `source_panel_service` / `source_panel_action`
- `input_action`
- `live_tv_package` / `live_tv_action`
- Settings keycode / scancode
- Input keycode 和应用专用键映射

这些接口允许按机型打开厂商私有输入面板、直播 Activity 或恢复原厂桌面原本处理的遥控按键。

其 `HomeKeyService` 主要处理部分遥控器的 `KEYCODE_ALL_APPS` 以及设置 / 应用键；**不能只因类名相同，就认为它与 UnitedU 的 HOME 接管等价**。

UnitedU 当前有实际 HOME 截键、原厂 HOME 窗口兜底、去抖补查、屏保放行和独立服务进程。两者解决的问题有交集，但不是谁的类更短谁就更可靠。

### 4.4 Module Cards 协议

已安装应用通过 Manifest category 与 XML 声明 label、caption、accent。不是任意远程 widget，也不是完整内容频道。

- 默认由用户选择是否显示。
- 决策绑定包名、签名摘要与名称；变化后重新询问。
- 数量、字段、XML 深度等有边界。
- 解析故障隔离，功能异常时关闭本进程内 cards，不拖垮 HOME。
- spec 1 文本字符受限，不支持正常资源本地化。

这是值得参考的扩展安全设计，但 UnitedU 已支持标准 Android TV Channel，没必要在缺乏开发者需求时立即再造私有协议。

## 5. 系统修改与恢复：做得好，也不是无风险

### 5.1 已有保护

- 操作前记录包是启用、停用还是不存在。
- 记录原 HOME 组件及三个动画缩放值。
- 恢复记录按电视地址与列表路径关联，首次记录保留；列表新增项补录。
- 恢复不是无差别启用全部包，尽量回到原状态。
- 校验包名，避免列表进入 shell 命令时注入。
- 当前唯一可用 HOME 默认不允许停用；排除 setup wizard / FallbackHome 等伪候选。
- `setupwraith` 拥有 Wi-Fi 配置或无法确认归属时跳过，避免断网。
- 请求 `TV_INPUT_HARDWARE` 的包默认跳过。
- 安装脚本先启动替代桌面，再交接并检查 HOME；失败恢复原厂桌面。
- 文档提醒关闭网络 ADB，特别是 `ro.adb.secure=0` 的固件。

### 5.2 不能把保护称为完整安全保证

- 硬件输入权限检查失败时，当前脚本是**警告后继续保留待停用清单**，不是所有检查都失败即停止。
- 启用 / 停用记录不是所有 Android component enabled-state 的精确快照。
- 恢复记录关联 IP / 路径不等于稳定设备身份；改地址、移动清单、跨清单重叠等场景仍需专门审查。
- 保留恢复脚本不代表断网后还能执行；必须存在电视端或另一种恢复入口。
- 上游明确报告过 HDMI 黑屏、Wi-Fi 丢失、Cast 失效和 HOME 导致系统崩溃循环。
- 软件单测覆盖逻辑，不覆盖所有厂商固件的语义差异。

如果复用，需要审计并明确我们的失败策略，而不是把“有恢复脚本”等同于“不会出问题”。

## 6. 与 UnitedU 的对比

| 维度 | CYTV | UnitedU |
|---|---|---|
| 产品重点 | 系统清理、安装方法、设备知识 + 轻桌面 | 长期日常使用的完整桌面 |
| 广告边界 | 尝试停用厂商广告 / 推荐组件 | 自身首页无广告，不默认改系统包 |
| 官方交付 | 源码、脚本；所查 Release 无 APK 资产 | 正式签名 APK、更新清单 |
| 安装 | 推荐电脑 + Agent / 脚本构建安装 | 标准 APK 侧载；ADB 只是选项 |
| 首页 | Dock + 自动应用网格 | 用户主动编排多行应用 / 频道 |
| 定制入口 | XML / JSON + 部分电视端设置 | 电视端编辑、设置、图片选择、手机上传 |
| 媒体 | 壁纸、漂移时钟屏保 | 图片 / 视频屏保、图片资源、图库 |
| 更新 | 拉源码重新构建安装 | 应用内更新、稳定 / Beta、回退包 |
| HOME | 默认选择 / 停用竞争候选 + 特殊按键服务 | 默认选择 + 非侵入无障碍接管 |
| 厂商适配 | 多机型清单、私有 action / 按键配置 | 已有研究，但真机覆盖窄、输入源主要依赖标准 TIF |
| Android 下限 | 8.0 | 9 |
| 国际化 | 19 语言，部分 RTL 专门适配 | 英文、简中、繁中 |
| 测试协作 | 公共 CI：工具、单测、Lint、构建、体积 | 多项本地单测 / E2E / 性能记录；当前未见公共 Actions workflow |

UnitedU 的编辑、焦点状态管理、浮层、频道、持久化和发布链有明显更深的工程投入。但**没有同机运行 CYTV，不能直接断言 UnitedU 真实焦点可靠性或流畅度全面更优**。代码复杂、测试文件多也不自动等于质量高。

### 6.1 安装方式纠正

本轮讨论最初把 UnitedU 首装说成必须 ADB，这是错误的，也受到现有 README 误导。

正确事实：

- UnitedU 是普通预编译 APK，可通过电视浏览器、U 盘 / 文件管理器、手机文件传输工具或 ADB 安装，前提是电视允许该渠道和 APK。
- 非 ADB 安装通常需给打开 APK 的应用授予“安装未知应用”。
- UnitedU 自带上传页必须先有 UnitedU 才能打开；**不能由此推导首装必须 ADB**。
- 安装成功、出现在应用列表、成为默认 HOME 是不同问题。
- Android 13+ 的侧载受限设置可能影响无障碍 HOME 接管，不代表 Launcher 本身不能安装运行；是否能在系统 UI 解锁取决于固件。

README 两种语言当前“首次仍需 adb”的说法应另行同步修正。本稿不直接修改用户文档，以免把研究建议混入已批准产品行为。

## 7. 哪些差距值得补，哪些不值得急着追

### 7.1 优先项

1. **可贡献的真实设备数据库**：型号、地区、固件、安装、HOME、重启、输入源、遥控器、恢复方法、证据等级。
2. **确定性的 Setup / Doctor**：探测、安装、诊断、验证、报告、恢复；默认为不精简系统。
3. **Agent 入口**：把复杂诊断变成自然语言交互，降低文档阅读门槛。
4. **公共 CI**：把现有基础测试变成外部贡献者能看见的门槛。
5. **按机型的输入源和遥控器配置**：只对可靠设备证据开放，不全局猜 action。

### 7.2 中长期项

- 更多语言与 RTL 工程准备。
- 真正的低端设备验收和资源预算，而不是未经测量宣称 1GB / 2GB 最低配置。
- 主题导入 / 分享。
- 应用页的新安装、未加入首页分组。

### 7.3 不应自动跟进

- 为追求 400KB 改写整个 Compose UI。
- 没有需求就做另一套 Module Cards 生态。
- 默认关闭 OTA、语音、Cast 或全部厂商后台服务。
- 默认改包名冒用厂商白名单。
- 将 Agent 变成普通用户安装 UnitedU 的必要条件。

## 8. Agent 安装方式：认可价值，但控制权限与执行边界

### 8.1 为什么值得做

Agent 可以识别型号、读取诊断结果、查询档案、解释取舍、引导授权并逐步验证。相比一长串 ADB 文档，它能更好处理差异化设备。

但它只降低认知与操作门槛，不消除电脑、网络可达、ADB 授权和系统权限的前提。Agent 截图也验证不了受保护 HDMI / 视频层是否正常，部分环节必须让用户真实按键、看画面。

### 8.2 形态建议

```text
同一个正式 UnitedU APK
        +
可选 UnitedU Agent Setup（仓库 / prompt / 固定工具 / 设备档案）
```

可以随新产品版本一起发布，不需要新包名、签名或单独用户数据目录。目录位置、是否独立仓库尚未裁定。

### 8.3 AI 与工具的职责

| AI 适合负责 | 固定工具必须负责 |
|---|---|
| 理解用户需求、解释方案 | 参数校验、设备身份确认 |
| 查阅并比较设备档案 | 备份、原子记录、幂等执行 |
| 引导系统授权、请求人工验收 | 安装、状态检测、明确退出码 |
| 推荐下一步 | 允许列表动作、超时、停止与恢复 |

建议的命令形态仅为概念，不是已存在接口：

```text
probe → backup → install → diagnose-home → plan → apply → verify → restore
```

安全原则：

- 安装使用官方发布包，校验来源、签名及校验和；同一不可信来源的 APK 与 hash 不构成独立信任。
- 已知设备档案带固件范围和证据，未知固件默认只读诊断。
- 默认仅安装与非侵入配置；原厂包停用单独逐项授权。
- 高风险动作需要计划、恢复前提和用户确认，不能只靠 prompt 里一句“谨慎”。
- Agent 读取的 issue、设备文本和网络内容属于不可信输入，不能转成任意 shell 命令。
- 一个电视同一时刻只允许一个操作者；与现有本项目真机操作约束一致。
- 诊断报告最小化 / 脱敏：IP、SSID、账号、序列号、截图等默认不公开上传。
- 断连不宣称成功恢复；无法证实安全前提时停止，而不是猜测继续。
- 结束指导关闭调试、撤销授权，并验证网络端口关闭。

## 9. 接管 HOME 的技术边界

### 9.1 首先区分四个结果

1. APK 安装并能打开。
2. 被系统列为 HOME 候选。
3. 系统真正将其解析为默认 HOME。
4. 重启 / 待机 / OTA 后仍按预期工作。

此外，无障碍截键或拉回窗口可以实现“用户按 HOME 看见 UnitedU”，但它不一定意味着系统默认 HOME 角色已改变。报告必须区分这两种成功。

### 9.2 限制分类

| 类型 | CYTV + UnitedU 可能做什么 | 不能承诺什么 |
|---|---|---|
| 标准 HOME 可选择 | 普通安装和设置即可 | 不需要 debloat 才能成功 |
| 原厂 HOME 优先级更高 | 优先 UnitedU 接管；高级方案停用竞争候选 | 不保证没有 Cast / Wi-Fi / 按键副作用 |
| 包名白名单 | 研究特定固件；上游有小米改包名成功报告 | 不保证跨固件、签名校验、更新兼容 |
| 安装器拦 HOME APK | 识别并报告；仅研究已证实特例 | Agent 无法提供厂商签名，不能通用绕过 |
| HOME / 原厂启动硬编码 | 尝试无障碍截键或窗口兜底 | 不保证按键交付、开机持续或厂商服务不恢复 |
| 无 ADB / 被设备策略锁定 | 指导合法可达安装与配置渠道 | 没有入口不能远程 shell 操作 |
| 非 Android / Fire OS 特殊限制 | 分别说明不支持或只支持部分清理 | 不能把 Android 清单当通用解 |

### 9.3 代表性证据

**Google TV**：原厂 Launcher 高优先级，使设置 HOME 角色不一定生效。停用其候选可能释放 HOME，但 setupwraith 也可能接管；其 Wi-Fi 配置与部分 Chromecast 认证依赖构成真实副作用。

**小米 MiTV-AETP0 / dofus，Android 9，固件 22.9.2.851（上游报告）**：标准包名不被接受为 HOME，停原厂后进入 FallbackHome；改为允许列表包名 `com.ts.launcher` 后报告成功。上游明确提示包名属于其他应用、只能本地谨慎使用、可能签名冲突，其他固件 / OTA 未验证。不能把它推广成 UnitedU 官方兼容包。

**国行 TCL Q10G Pro（上游快照记录）**：安装链路校验 HOME 声明和厂商签名 / 允许列表，档案记录了拒装。另有新 issue 声称部分 TCL / FFalcon 可利用短时停用安装器完成安装；**未在本轮复验，不作为支持承诺，也不把早期拒装报告视为永久不可能**。

### 9.4 为什么没有必然突破

`pm disable-user` 通常只是停用其他候选，不会使 UnitedU 获得：

- root 或系统签名；
- `/system` 写权限；
- 修改 framework 的权限；
- 厂商私有特权；
- 绕过所有安装校验或不可停用包的能力。

因此应承诺“找到并验证该设备可达到的最好路径”，不能承诺“所有电视都能真正默认 HOME”。

## 10. 建议的四级接管策略

### Level 1：标准路径

普通 APK 安装 → 系统选择 / 合法 shell 设置 HOME → 检查解析和真实按键 → 用户确认重启后结果。

### Level 2：UnitedU 无障碍接管

保留原厂组件，处理必要授权，测试 HOME 截键、窗口兜底、屏保、后台和开机。不能只看 enabled 设置就当服务已经绑定；ADB 注入 HOME 不能代替真实遥控器对无障碍截键的测试。

### Level 3：高级 ADB 交接 / 精简

不是一定要等前两级失败才允许：有明确去广告需求的高级用户也可主动选择。但必须单独授权、有记录、可恢复，且不能与默认安装混在一起。

- 限定到确切型号 / 固件与包版本。
- 识别 HOME、Wi-Fi、输入源、Cast、键盘、遥控依赖。
- 小批次执行，实际验证。
- 恢复通道不依赖于被修改的唯一网络 / UI。

### Level 4：厂商实验路径

改包名、私有接口、特定安装器行为等只做隔离研究；不得自动发布成通用支持。是否值得承担签名、数据、更新和长期维护成本需另行决策。

## 11. 复用与合作选项

| 方案 | 优点 | 风险 / 成本 |
|---|---|---|
| A：向 CYTV 上游增加 UnitedU 选项 | 共用社区档案，减少重复 | 上游是否接受未知；流程与安全标准非我们完全控制 |
| B：UnitedU 独立 Setup | 官方包、低侵入策略、体验可控 | 设备档案分叉、持续维护成本 |
| C：自己的安装诊断核心 + 审查后引用上游档案 | 核心可控，兼容知识共享 | 需设计版本固定、来源追踪、复验和贡献回流 |

**当前倾向 C，未定案。**不建议 Agent 每次从上游 main 下载最新清单后未经审核直接修改电视。

CYTV 代码为 MIT，UnitedU 为 Apache-2.0；复用需保留版权与许可声明，字体等资源另有许可证。许可证允许不等于获得作者背书，合作宣传须另行沟通。

## 12. 最小验证计划建议（尚未批准）

### 阶段 A：只读诊断 + 安装

- 固定 CYTV 研究版本。
- 定义设备报告格式和脱敏规则。
- 在模拟器验证安装、签名校验、重复运行、异常中断与 HOME 判定。
- Agent 只调用固定工具，尚不开放 debloat。

通过标准：区分安装成功、Activity 成功、HOME 角色成功、截键成功；未知情况明确报告，不能假成功。

### 阶段 B：小规模真实设备矩阵

至少选择不同限制机制：标准 HOME、Google TV 优先级、厂商锁定。由愿意参与的设备所有者操作与验收，遵守本项目既有真机规则；不擅自向 Gordon 的电视装包。

验收项目：

- 真遥控 HOME、长按 HOME、应用返回、设置 / 输入 / 应用专用键。
- 待机唤醒、屏保退出、冷启动。
- Wi-Fi、蓝牙、HDMI / TV、Cast、应用播放。
- 更新 UnitedU 后状态保持。
- 每一步失败可恢复，断连和恢复不成功不误报。

### 阶段 C：选择性开放高级精简

只收录经过复验的少量配置，不以支持型号数量为目标。设置“无修改诊断”和“恢复”作为独立入口。

在获得这些数据前，不确定最低内存门槛、不宣称新增覆盖比例、不承诺全品牌接管。

## 13. 给其他 agent 的独立评审问题

请先核实证据再给结论，不把本稿建议视为要求实现。

1. 产品分层是否正确？AI 安装对目标用户是真实收益，还是把复杂度从文档搬到了电脑 / 模型订阅？
2. 除安装和 HOME 诊断外，是否值得 UnitedU 承担系统 debloat 的支持责任？
3. 对最重要目标设备，Level 2 与 Level 3 的可靠性 / 副作用如何取舍？需要哪些实测？
4. 上游哪些保护是真正失败即停止，哪些仍失败后继续？恢复覆盖了哪些状态、漏了哪些？
5. 修改前后断网、断电、Agent 被中止、重复执行、IP 变化、重叠精简清单如何处理？
6. 应否上游合作而不是自建？最小维护范围是什么？
7. Agent 的不可信输入、任意 shell、权限、日志隐私、远程恢复应怎样约束？
8. 哪些机型结论已被新固件或新 issue 推翻？请区分新主张与复验结果。
9. 是否存在比改包名 / 停原厂更安全的实现？若需要修改 APK，最小理由是什么？
10. 最先验证哪三类设备，成功与否如何客观判定？

建议评审输出：

- 同意 / 不同意的核心判断与理由；
- 事实纠错及带版本的来源；
- 高风险项与缺失前提；
- 最小验证方案，而非直接展开大规模开发；
- 明确“不知道 / 未验证”的项目。

## 14. 已知未验证与文档待办

- 未实机安装 CYTV、未复验其 debloat 恢复、未同机测性能。
- 123 机型报告没有逐一审计，翻译质量没有逐语言评审。
- 未验证上游最新 TCL 安装绕路 issue。
- 尚未确定 Agent Setup 的仓库位置、接口、依赖、支持平台、版本固定和维护责任。
- 尚未取得 CYTV 作者合作意向。
- UnitedU README 的“首装必须 adb”表述错误；版本说明也存在滞后，需单独双语修订。
- README 中关于停用 setupwraith 的简短说明应补足 Wi-Fi / Cast 风险；不能让研究中的高级路线成为无条件操作指南。
- 本稿不修改现行 UI spec、不新增 R 裁定、不改变真机安装与正式发布规则。

## 15. 来源索引

### CYTV（优先核对固定快照 `687b5ee`，main 链接可能变化）

- [仓库](https://github.com/Gathin23/cleanyourtv)
- [固定快照](https://github.com/Gathin23/cleanyourtv/tree/687b5ee)
- [README](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/README.md)
- [PROMPT](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/PROMPT.md)
- [机型清单](https://github.com/Gathin23/cleanyourtv/tree/687b5ee/examples)
- [故障排查](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/docs/TROUBLESHOOTING.md)
- [停用脚本](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/tools/disable.sh)
- [恢复记录](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/tools/recovery.py)
- [安装脚本](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/launcher/build-and-install.sh)
- [Manifest](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/launcher/app/src/main/AndroidManifest.xml)
- [Gradle](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/launcher/app/build.gradle)
- [配置项](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/launcher/app/src/main/res/values/config.xml)
- [主题](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/themes/README.md)
- [App Library](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/docs/app-library.md)
- [Module Cards](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/docs/specs/card.md)
- [CI](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/.github/workflows/ci.yml)
- [小米白名单报告](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/docs/devices/xiaomi-mitv-aetp0-dofus.md)
- [TCL Q10G Pro 档案](https://github.com/Gathin23/cleanyourtv/blob/687b5ee/examples/tcl-q10g-pro/debloat.txt)
- [TCL / FFalcon 后续安装主张 #376，未复验](https://github.com/Gathin23/cleanyourtv/issues/376)

### UnitedU

- [英文 README](../../README.md)、[中文 README](../../README.zh-CN.md)（注意 §6.1、§14 的已知表述问题）
- [兼容性研究](2026-10-02-tv-compatibility.md)
- [HOME 接管先例研究](2026-10-02-home-key-takeover-prior-art.md)
- [频道研究](2026-10-07-tv-channels-recommendations.md)
- [现行设计与裁定](../superpowers/specs/2026-09-20-gtv-line-design.md)
- [Manifest](../../app/src/main/AndroidManifest.xml)
- [HomeKeyService](../../app/src/main/java/com/uniteduone/launcher/HomeKeyService.kt)
- [正式版本](https://github.com/GordonWang1878/UnitedU-launcher/releases/tag/v1.2.0)

---

**决策底线：**可以把 UnitedU 从一个 APK 扩展为“APK + 可选 Agent 安装诊断套件”，但不能把一个尚未验证的兼容研究包装成“任何电视都能接管”。先验证新增覆盖和恢复可靠性，再决定是否承担系统清理产品的责任。
