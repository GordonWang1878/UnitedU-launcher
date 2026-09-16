# M7 设计:设置体系重做(两栏 + 实时预览)+ 恢复默认 + 语言 + 待机接线 + 关于/检查更新 + 首次引导 + README

状态:设计定稿待 Gordon 审阅(2026-09-16)。基线 = main + `theme-cleanup` 分支(删主题化壁纸、主题色全面接线)合并之后。

## 0. 决策(Gordon,2026-09-16 真机第二轮之后)

| 议题 | 决定 | 理由 |
|---|---|---|
| M7 范围 | 六项全进 M7:齿轮菜单归并、设置页实时预览、恢复默认、待机设置接线、关于/检查更新、首次引导(+ README/发布脚本) | 一轮做完 |
| 实时预览版面 | **透明叠加**:首页整体留在设置面板底下继续组合(不可聚焦),面板半透明,遮罩**左深右浅渐变** | 与壁纸预览同一机制;设置行不必改窄;文字列可读、右侧卡片清楚 |
| 恢复默认 | **只恢复设置**(settings.json)+ 确认框;分栏、标题、卡片图、图片库全部保留 | "调乱了想回来"只涉及设置;下半张表是用户自己做的东西 |
| 设置页结构 | **左右两栏**:左栏分组列表,右栏当前组的行 | 约 18 行一屏放不下、又不能滚(铁律 1);Android TV 系统设置同款 |
| 分组清单 | 见 §2 表 | 通过 |
| 更新通道 | **COS 公共读桶为主、GitHub Release 为备**(§8 原案) | 国行电视访问 GitHub 时好时坏 |
| 首次引导第 2 步 | **只铺分类表命中的**已装应用;其余不铺 | 零推荐;等于把内置默认布局按已装过滤后落盘 |
| 主题化壁纸 / 主题色 | 已另行落地:删主题化壁纸;主题色经 `LocalThemeColors` 全面接线 | 用户要原图;两开关并存易混淆;选蓝只有 4 处变蓝 |

## 1. 齿轮菜单

四项,顺序固定:**编辑分栏 · UnitedU 设置 · 系统设置 · 关于**。
- 编辑分栏 = 现「编辑桌面」(`EditScreen`)。
- 系统设置 = `startActivity(Settings.ACTION_SETTINGS)`,失败 toast。
- 关于 = 新页面(§7)。
- 现有的「导入图片 / 换壁纸 / 屏保图库 / 设置默认桌面」四项从菜单移除,搬进设置页对应分组(§2);`GearMenu` 组件不变(长按卡片菜单继续复用它)。MENU 键行为不变。

## 2. UnitedU 设置:两栏

### 2.1 结构
- 左栏:分组列表(7 项);右栏:当前分组的行(≤ 8 行,永远一屏放下;**不滚动**)。
- 按键:左右键在两栏之间切换(右栏在最左列控件上按左 → 回左栏;左栏按右 → 进右栏当前组第一行,或上次离开的行);上下键在栏内移动;BACK 关闭设置页。
- 首次进入焦点落在左栏当前组(默认「布局」);每组记住上次离开时的行(rule 5:目标与当前位置分开)。

### 2.2 分组与行

| 左栏 | 右栏的行(控件) | 备注 |
|---|---|---|
| 布局 | 卡片大小(分段 大/中/小)/ 卡片标题(开关)/ 输入源行(开关) | 「行数」归 M4b 编辑页(行的增删就是改行数) |
| 壁纸 | 换壁纸 ▸ / 导入图片 ▸ / 轮播间隔(分段)/ 模糊(滑块)/ 亮度(双向滑块) | 前两行是动作行 |
| 主题 | 主题色(色板)/ 跟随壁纸主色(开关) | 全面接线后改的是整个界面 |
| 待机 | 待机时长(分段 关/1/3/5/10 分)/ 待机内容(分段 只留时钟/全黑/不淡出) | M5 在此组追加:屏保开关 / 屏保间隔 / 屏保图库 ▸ |
| 时钟 | 显示日期(开关) | 12/24 小时跟系统,不出控件 |
| 语言 | 语言(分段 跟随系统/简体/繁體/English) | 新增,§5 |
| 其他 | 设为默认桌面 ▸ / 恢复默认 ▸ | 后者弹确认框,§4 |

动作行(▸):确定键打开子界面——换壁纸 → 现 `ImagePicker`(壁纸模式);导入图片 → `ImportScreen`;设为默认桌面 → `HomeSettingsCard`;恢复默认 → 确认框。子界面关闭后焦点回到**同一行**(目标与当前位置分开,rule 5);每个子界面自己负责初始焦点(rule 3,现有实现不变)。

### 2.3 焦点账本
- 状态:`pane`(LEFT/RIGHT)、`group`(0..6)、`row[group]`(每组独立记忆)、`focusedCell`(控件自报,rule 4)。
- 看门狗:一个效果,key = (pane, group, row, overlay 状态, nonce),guard 同一组量(rule 6);任何子界面开着时让路(rule 3/4)。
- 现有 `SettingsScreen` 的「12 控件按顺序索引」改成 `(group, row)` 二维;`WALLPAPER_CTRLS` 之类的范围常量随之删除,预览判定改为「当前组」。
- 每个可聚焦控件逐项挂 `FocusRequester`(rule 3 的实现约束)。

## 3. 实时预览

### 3.1 机制
- `MainActivity` 在 `settings == true` 时**不再替换** `HomeScreen`:底层继续组合 `HomeScreen(previewing = true)`,上层组合 `SettingsScreen`。
- `previewing` 下 `HomeScreen`:所有卡片/齿轮 `canFocus = false`,不请求焦点,看门狗以 `previewing` 为 key + guard 让路(rule 6),按键一律不处理;`tgtRow/tgtIdx` 保持(退出设置后按 rule 5 还原到进入前那张卡——首页不再被销毁,记忆天然保留,同时退役了「设置页回来落 (0,0)」这条延后项)。
- 面板背景:水平渐变遮罩,左端(文字列)黑 α≈0.60,右端 α≈0.25;数值真机调。现有「壁纸组亮一档」的 `SettingsPreviewScrim` 逻辑并入:任何组都用同一渐变,不再分组切换遮罩。

### 3.2 各组预览内容
- 布局:卡片大小 / 标题 / 输入源行改动 → 底层 `HomeScreen` 读的是同一份 `Settings` 状态,改动即重组,无需通知。
- 壁纸:换壁纸 / 轮播间隔无即时效果;模糊 / 亮度沿用 300 ms 防抖 → `settingsRevision++` 重渲。
- 主题:`LocalThemeColors` 变 → 整个界面(含底层首页)换色。
- 待机:焦点停在「待机内容」行时,底层按所选值**演示待机态**(只留时钟 / 全黑 / 不淡出),离开该行即恢复;「待机时长」不演示。
- 语言:改动 `recreate()`,回到语言行(§5)。

### 3.3 零回归
`previewing = false` 时 `HomeScreen` 行为与现在完全相同(像素对比:默认设置首页不变)。

## 4. 恢复默认

| 项目 | 存在哪 | 恢复默认 |
|---|---|---|
| 卡片大小、行数、卡片标题、输入源行、主题色、跟随壁纸主色、显示日期、待机时长、待机内容、当前壁纸、轮播间隔、模糊、亮度、语言 | settings.json | 回出厂值(`Settings()`);当前壁纸回第一张内置 |
| 「新应用」基线时间 | settings.json | 重置为现在(此后只有新装的算新) |
| 分栏、自定义标题、换过的卡片图、上传/内置图片库 | layout.json / titles.json / icons/ / library/ | **不动** |
| 壁纸处理缓存 | cache/ | 清空(自动重算) |
| 首次引导已完成标记 | settings.json | **不动**(不重新引导) |

交互:其他 → 恢复默认 ▸ → 确认框(标题「恢复默认设置?」正文「分栏、标题、卡片图和图片库都不会变」,按钮「恢复」/「取消」,默认焦点在「取消」)→ 执行 `SettingsStore.update { Settings().copy(newAppsSeenAt = now, onboardingDone = it.onboardingDone) }` + 清壁纸缓存 → `settingsRevision++` → toast「已恢复默认设置」;焦点回「恢复默认」行。语言若被恢复成「跟随系统」且与当前不同,走 §5 的 `recreate()`。

## 5. 语言

- 字段:`Settings.language: String`,取值 `system` / `zh-CN` / `zh-TW` / `en`,默认 `system`;解析非法值 → `system`。
- 机制:`MainActivity.attachBaseContext` 用 `Configuration.setLocales(LocaleList(locale))` 包一层 `createConfigurationContext`;`system` 时不包。改动后 `recreate()`;设置页在 `onSaveInstanceState` 记住 (pane, group, row),重建后还原到语言行(rule 5)。不引入 AppCompat。`Clock` 的 `Locale.getDefault()` 改为读同一来源。
- 首次引导第 1 步写同一字段。

## 6. 待机接线

- `idleAfterMs`:0 = 关(永不进入待机);其余替代 `Theme.IdleAfterMs`;待机效果 key 加 `homeSettings.idleAfterMs`(改动即重新计时,rule 6)。
- `idleContent`:CLOCK_ONLY = 现状(卡片/行标题淡出,留时钟);BLACK = 全黑(遮罩 α=1,时钟也不留;任意键唤醒);NO_FADE = 不淡出(待机只作为 M5 屏保层的触发条件,M5 之前 NO_FADE 下什么都不发生)。
- 预览见 §3.2。`Theme.IdleAfterMs` 常量降级为默认值来源。

## 7. 关于 + 检查更新

### 7.1 关于页
齿轮菜单第 4 项,独立页面(不在设置左栏):应用名 + 版本(`versionName (versionCode)`)、「检查更新」按钮(唯一可聚焦项,加上「许可声明」不可聚焦文本)、许可声明短文(本项目 Apache-2.0;第三方:DM Sans(OFL 1.1)、Material Icons(Apache-2.0)、NanoHTTPD(BSD-3-Clause)、ZXing(Apache-2.0);内置壁纸为程序生成)、项目地址 `github.com/GordonWang1878/UnitedU-launcher`。一屏放下,不滚动。BACK 关闭。

### 7.2 latest.json
```json
{ "versionCode": 2, "versionName": "1.0.0-beta", "notes": "更新说明(≤ 200 字)",
  "apkUrl": "https://…/unitedu-1.0.0-beta.apk", "sha256": "<hex64>", "minSdk": 28 }
```

### 7.3 通道与流程
- 地址:主 `https://<bucket>.cos.<region>.myqcloud.com/unitedu/latest.json`(桶域名由 Gordon 提供,经 `BuildConfig.UPDATE_URLS` 注入,不硬编码在源码里);备 `https://github.com/GordonWang1878/UnitedU-launcher/releases/latest/download/latest.json`。每个 8 s 超时,顺序尝试。仅 https,`HttpURLConnection`,IO 线程。
- 点击「检查更新」→「检查中…」→ 比较 `versionCode`:≤ 当前 →「已是最新」;更高 → 显示版本名 + notes + 「下载并安装」按钮 → 下载到 `cacheDir/apk/update.apk`(百分比进度)→ SHA-256 校验(不符 → 删文件、提示「校验失败」)→ `ApkInstaller.install`(复用 M6 的 NEEDS_PERMISSION 引导与 `RelaunchAfterUpdate`)。
- 失败文案:网络失败 / 格式错误 / 校验失败 各一条短提示;**手动检查不静默**(修正 DESIGN §6「失败静默」——手动触发必须有结果)。

### 7.4 发布脚本 `scripts/release.sh`
`assembleRelease` → 计算 APK SHA-256 → 生成 `latest.json` → `gh release create v<版本> APK latest.json`(GitHub)→ `coscli cp` 两个文件到 COS `unitedu/`(凭据来自 Gordon 本地 `~/.cos.yaml`,不进仓库)。脚本由 Gordon 在本机运行。

## 8. 首次启动引导(三步)

- 触发:新字段 `Settings.onboardingDone: Boolean`。`onCreate` 首次判定:字段缺省且 `layout.json` 已存在 → 老用户,写 `true`(不打扰);缺省且 `layout.json` 不存在 → 新装,写 `false` → 显示引导。
- 三步全屏页,每步「继续」/「跳过」两个按钮;BACK = 上一步,第 1 步 BACK = 跳过整个引导。任意路径结束都写 `onboardingDone = true`,不再出现。引导期间不进入待机、MENU 键无效。
- 步 1 语言:四选(§5 同字段),选中即写入,进步 2 时已按新语言显示(`recreate()` 后按保存的步骤号还原)。
- 步 2 铺应用:列出「将放到桌面的应用」= 内置分类表 ∩ 已装,按行分组显示(空行不显示);「继续」→ `layout.json` = 默认三行按已装过滤(从未安装的默认条目就此消失);「跳过」→ 三行保留、`apps` 清空。
- 步 3 设默认桌面:复用 `HomeSettingsCard` 内容(当前默认桌面 + 去系统设置按钮);「跳过」/ 从系统页返回 → 结束。

## 9. README 与发布件
面向用户的 `README.md`:是什么 / 安装(adb 或手机上传页传 APK)/ 设默认桌面 / 上传图片 / 检查更新 / 回退原桌面 / 反馈方式(`adb logcat`);征集其他品牌测试。首发版本号 `1.0.0-beta`(`versionCode 2`)。NOTICE 已在 M6 完成。

## 10. 验收
1. 齿轮菜单四项,系统设置能跳转。
2. 两栏设置页:七组遍历,左右切栏、上下移动、动作行进出,全程焦点不丢(模拟器 + 真机)。
3. 预览:每组至少一项改动在底层即时可见(截图:卡片大小、标题、输入源行、亮度、主题色、待机内容三态)。
4. 恢复默认:按 §4 表逐项核对;确认框默认焦点在「取消」。
5. 语言:四档切换,`recreate()` 后回到语言行;时钟星期语言随之变。
6. 待机:1 分钟档实测淡出;三种待机内容;关 = 永不待机。
7. 检查更新:本地 HTTP 假 `latest.json` 三种结果(已是最新 / 有新版下载并安装 / 校验失败);COS 与 GitHub 顺序与超时。
8. 首次引导:`pm clear` 后三步走完,`layout.json` 只含已装命中;老用户升级不出现引导。
9. 零回归:`previewing = false` 首页像素不变;默认主题色像素不变。
10. 真机:Gordon 遍历 1–8。

## 11. 不做
行数控件(M4b);屏保三行(M5);自动/定时检查更新;取色器;任何可滚动容器;关于页之外的许可全文。

## 12. 风险
- 两栏设置页 + 预览是最大的焦点重构:先做骨架(两栏、二维焦点账本、透明叠加、`previewing` 让路)并单独评审,再迁移各组的行。
- `recreate()`(语言)重建 Activity:设置页与引导页必须按 rule 5 还原位置,冻结从 `onSaveInstanceState` 起。
- 国行网络对 COS 也可能慢:8 s 超时 + 明确文案;GitHub 备用。
