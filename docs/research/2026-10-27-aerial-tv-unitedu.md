# Aerial TV 与 UnitedU：能力对比及接管方案研究

日期：2026-10-27。Aerial 固定提交 [`f57e46b1b0459b1ced68025538bb85abab955636`](https://github.com/pengchujin/aerial-tv/tree/f57e46b1b0459b1ced68025538bb85abab955636)，`versionName=1.62` / `versionCode=76`；UnitedU 为本工作区 1.2.0 / 41，包含当前未提交研究文档。Aerial 源码临时只读副本在 `/tmp/aerial-tv-20261027`，可能随临时目录清理消失。公开仓库接口读取时 6,624 stars，是当时快照，不代表真实用户数或覆盖率。Aerial 的运行、具体机型结果均为作者/用户报告；本轮未编译、安装、运行项目脚本或连接电视。

## 结论

Aerial 是直接面向国行的完整电视桌面，与 UnitedU 产品定位高度重叠；它并非专门的“系统级接管工具”，但把多品牌安装、HOME 接管、休眠恢复与机型适配整合进了产品。此前方案将部分这些能力放在探索候选，Aerial 的代码和设备档案提供了更具体的研究起点，值得将其加入 **UnitedU 的接管方案研究和真机验证清单**。这不是已验证的通用修复，也不是自动批准广泛权限或发布。

依据：固定版本 [README](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/README.md)、[架构](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/docs/ARCHITECTURE.md)、[构建配置](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/app/build.gradle.kts)。

## 1. 产品能力对照

| 维度 | Aerial 1.62 | UnitedU 工作区 1.2.0 | 判断 |
|---|---|---|---|
| 目标与界面 | 国行 Android TV，Apple TV 风格，常用应用 + 全部应用网格，无推荐流 | 国行无 GMS Android TV，Google TV 风格，用户自己编排的应用行、卡片 | 同一用户需求下的不同 UI 产品，不是上游工具与下游桌面的简单互补 |
| 系统版本 | 主版 Android 5+；另有 Android 4.0–4.4 Lite 测试版，上游声明仅模拟器/新电视测试过，未在真实 4.x 电视验证 | Android 9+（minSdk 28） | Aerial 声明覆盖更多旧系统，真实表现按固件验证；不据此断言 UnitedU 应降 minSdk |
| 首页整理 | 收藏、移动、文件夹、隐藏、卸载；手机页可排序、改名、换图、配置输入源 | 多应用行、增删排序、卡片图和标题等由电视编辑；手机资料库上传图片/视频/APK | Aerial 的手机端远程整理和文件夹是产品差异；UnitedU 的多行编辑是不同组织方式，不以功能数量断优劣 |
| 输入源 | 标准 TvInputManager，加小米、TCL、创维等厂商路径及机型修正；可固定首页 | 标准电视输入框架；可改名、隐藏；README 提醒许多国行设备无标准输入源 | 厂商适配是优先研究对象；只在已验证机型/组件可用，不能套成通用接口 |
| 壁纸/屏保 | Apple 航拍、Bing 每日图、本地视频/照片；可支持 HDR/Dolby Vision，取决设备；部分功能联网 | 内置、自上传图片/视频；可选在线图库；Ultra HDR 图仅在显示通路支持时生效 | 内容与实现路线不同，航拍视频依赖网络、设备解码与 Apple 资源条件；许可不随代码 MIT 自动获得 |
| 频道 | 以 README 与目录结构核查，未发现与 Android TV Channel 行等价的公开功能；不是全仓证明不存在 | 可选 Android TV 频道行；入口有 GMS 条件，节目取决第三方写入 | UnitedU 有明确的标准频道能力，但不等于国内所有应用都能产生节目 |
| HOME 接管 | 标准 HOME、无障碍守护、厂商窗口和按键处理、后台复活及具体固件路径；有用户反馈与失败机型 | `HomeKeyService` 已有 HOME 截键、原厂 HOME Activity 窗口兜底、开机内服务连接拉回；现有 Google TV 路径以模拟器证据为主 | 基本截键和窗口拉回双方都有；Aerial 优势在“不能安装/进程被清理/厂商非标准行为”的组合与设备证据 |
| 安装/支持 | Agent 指引、ADB 脚本、品牌引导、机型诊断、手机报告及 standard/guard/Lite；Aerial 用自身签名和独立发布 | 正式 APK，既有手动安装/资料库/版本更新；此前 Agent 方案仍为提案 | 借鉴故障分型和闭环验收；不能把未实施方案说成现有功能 |
| 隐私/联网 | 航拍、Bing、天气等功能使用网络；手机配置服务带随机 URL token；具体开启时机需按功能审计 | 用户按需检查更新、资料库、本地媒体，以及可选频道与在线图库；README 前段“只有三种联网”与后续在线图库内容存在版本/文案不一致 | 不作“哪一个绝对不联网”判断；分别审计功能、触发时机和本地服务 |

UnitedU 工作区依据：[README.zh-CN.md](../../README.zh-CN.md)、[HomeKeyService.kt](../../app/src/main/java/com/uniteduone/launcher/HomeKeyService.kt)、[HomeKeyPolicy.kt](../../app/src/main/java/com/uniteduone/launcher/HomeKeyPolicy.kt)、[app/build.gradle.kts](../../app/build.gradle.kts)。README 有历史叠加，具体功能以实现及既有版本记录进一步核对，不以文档数字推定当前壁纸张数。

## 2. 安装与 HOME 的四种机制

1. **标准 HOME**：standard 构建的 Manifest 声明 `HOME`。选择后仍需检查真实遥控、冷启动、待机；HOME holder 与屏幕最终显示不是同一件事。
2. **guard 构建**：同一 applicationId 的另一构建变体不声明 HOME intent-filter，用于部分厂商拒装声明 HOME 的 APK；无障碍负责把桌面拉回。这不是绕过所有安装或签名限制，也不等于成为 Android 默认 HOME。标准版与 guard 切换仍须审计签名、版本码、更新、回退与数据兼容；Aerial 的做法不改变原先 UnitedU 单 APK 方案，是否引入变体需另定。
3. **无障碍守护**：Aerial 根据 HOME 按键、窗口、厂商事件和可用条件判断；旧设备还可用 usage stats 看前台。UnitedU 已有标准 HOME 键过滤和已知 HOME Activity 窗口兜底，并非从零起步；按键无法到服务、厂商不报告窗口或错误识别输入源时仍是实际缺口。
4. **厂家安装/启动路径**：TCL 的多屏互动在报告机型上通过自身接收通道和电视确认完成安装；一些 TCL 型号使用特定 appop 放行启动；海信 VIDAA 某型号报告修改 `persist.vt.use_home_pkg` 影响 HOME 按键。所有这些都是特定固件线索，不是通用命令；原厂功能、权限、还原及 OTA 均需验证。

源码：[标准 HOME 声明](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/app/src/standard/AndroidManifest.xml)、[构建变体](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/app/build.gradle.kts)、[安装脚本](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/tools/install.sh)、[HomeGuardService](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/app/src/main/java/app/orchard/tv/guard/HomeGuardService.kt)。

## 3. 待机复活与用户控制

Aerial 的 `RevivalListener` 利用系统重新绑定通知监听来启动进程；低内存固件不绑定监听时，`RevivalSync` 注册本地账户/同步适配器，借设置变化促使同步框架唤醒进程。上游 MiTV-MFTP0 文档报告恢复可能延迟约 30–60 秒，且依赖自动同步；各固件不保证按相同方式启动。`GuardSetup.ensureEnabled` 在拿到 `WRITE_SECURE_SETTINGS` 时，若自己的无障碍服务不在列表里会将它加回并启用无障碍。源码不展示一个可靠区分“用户主动关闭”与“系统移除”的通用判据，因此先前共同方案的 E6 约束仍成立。通知权限能力不因代码未读取通知而变小，应清楚说明授权的范围与实际用途。

Aerial 的 README 示例建议 `KEEP_ADB=1`；该选项使程序尝试恢复 ADB，而非接管桌面不可分割的要求。安装脚本还尝试授予系统设置、悬浮窗、用量统计、通知监听及厂商 appops，并在符合条件时设系统屏保；不能概括成“只安装 APK、不修改设置”。`|| true` 分支意味着个别授权失败不一定直接中断；我们不得据此宣称已获得权限，必须读取状态并区分未生效/未知。

还原脚本先关闭自动恢复并撤销权限、移除自己的无障碍服务、处理厂商属性；它不等于还原所有设置的精确快照：例如屏保回系统默认组件，部分 appop 回 default。断网、原始值缺失、多用户、并发设置变更与失败中断都需要独立审计；不得承诺“一键必然回到原状”。

参考：[GuardSetup](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/app/src/main/java/app/orchard/tv/guard/GuardSetup.kt)、[RevivalListener](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/app/src/main/java/app/orchard/tv/guard/RevivalListener.kt)、[RevivalSync](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/app/src/main/java/app/orchard/tv/guard/RevivalSync.kt)、[restore.sh](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/tools/restore.sh)。

## 4. 设备证据与反例

- **TCL MT5879 Android 11**：上游设备笔记描述 HOME 声明拦截、guard 变体及多屏互动安装；特定 appop 用于自启动绑定。记录属于该报告设备，未对其他 TCL 固件验证。[设备笔记](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/docs/devices/tcl-mt5879-cn.md)
- **小米 MiTV-MFTP0 Android 9**：上游描述低内存下通知监听不绑定，改走 sync 复活且可能有延迟；更新后系统持有失效路径也曾造成服务绑定失败。[问题 #72](https://github.com/pengchujin/aerial-tv/issues/72)
- **海信 VIDAA Android 11 MT9653**：上游 #40 与笔记描述显式启动聚好看，修改属性可能改变 HOME 键目标，但开机仍进入聚好看；不能把按键成功当成开机成功。[设备笔记](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/docs/devices/hisense-vidaa.md)、[问题 #40](https://github.com/pengchujin/aerial-tv/issues/40)
- **TCL tc8000_ay30a2 Android 11**：上游报告守护开启时待机后无法唤醒，需断电；Aerial 提供特定退让方案，但不能据此保证全型号有效。[问题 #371](https://github.com/pengchujin/aerial-tv/issues/371)
- 上游还报告部分创维固件黑屏/卡死、某些海信机型无法接管；索尼 A80J/VH1 的外部反馈不等于我们的 A95L 实验。其支持表是项目方整理的用户反馈，并非独立、统一条件下的复测或市场覆盖率。

## 5. 建议如何纳入 UnitedU 方案（研究附录，非已批准实施）

**第一优先：可复用的证据与诊断。** 在此前 A1–A5 的诊断/报告范围里增加：HOME 声明是否拦安装、真实按键与目标窗口、服务启用/绑定、被杀后的恢复来源、待机/屏保/HDMI/CEC、联网与系统权限前后态；明确来源、固件和证据级别。研究是否可用 Aerial 的机型档案作为外部线索，同时对照 UnitedU 实际行为，避免将对方成功记录记成 UnitedU 支持。

**第二优先：复现并比较具体差异。** 在模拟器先用测试夹具或纯逻辑分析验证 HOME 变体解析、事件路径和服务状态；测试方法不依赖改用户设备。选择愿意参与的真实设备所有者，在授权范围内分别验证 TCL 安装拦截、某小米待机服务移除、某海信 HOME/冷启动路径。每台记录基线、具体型号固件、权限/设定前态、真实遥控、冷启动和待机、副作用、明确回退结果。未经权限确认不得动 Gordon 的电视。

**第三优先：候选能力按证据择优。** (a) 小范围厂商输入和桌面识别；(b) 不声明 HOME 的 guard 变体，只在标准 APK 被该固件拦截且更新/回退设计成立时提案；(c) 通知监听/同步复活，只有在真实复现、额外权限与用户主动关闭语义满足 E6 后才考虑发布；(d) 特定系统属性交接，限定已复验固件，单独授权、前态备份、回读及独立恢复通道。保持“默认 HOME”和“无障碍拉回”的状态文案分开。

**暂不直接采用的动作。** 自动保持 ADB、默认替用户更换系统屏保、默认批量授广泛 appop、关闭安装校验、对未知设备写厂商属性；它们分别影响调试暴露、用户既有设置、权限和恢复行为。自动化不能让未验证的厂商特殊路径变成默认路径。

**与原预算的关系。** 这些是现有签署方案的研究增补建议，不自动增加或锁定项目预算。原提案最多 14 人日的估算没有覆盖逐项吸收/测试全部 Aerial 机制的可靠依据；应先拆范围并重新估算，不能假装免费纳入原上限。对外发布、真机实验、额外权限与产品交互语义仍按既有授权边界办理。

## 6. 许可与未验证项

Aerial 代码仓库 [MIT License](https://github.com/pengchujin/aerial-tv/blob/f57e46b1b0459b1ced68025538bb85abab955636/LICENSE)，直接复用实质代码应保留版权与许可声明，并核查依赖、字体、视频与商标各自条件；Apple 航拍内容并不因为仓库为 MIT 就成为 MIT 资源。UnitedU 使用 Apache-2.0；这不是已完成的版权/法务审计。

本轮只做静态阅读和外部报告检索：未对项目代码做完整安全审计，未跑测试，未比较 APK 大小、启动速度、内存与耗电；未复测厂商行为，未逐一核实全部设备与 issue，未确认联网/本地服务所有安全边界。报告中的“建议”是研究方向，非产品能力或已完成的开发计划。
