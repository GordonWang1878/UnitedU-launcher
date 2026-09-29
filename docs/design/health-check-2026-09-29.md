# 项目健康检查(2026-09-29)

检查对象:`main` @ `e3bf917`(本地比 `origin/main` 多 1 个提交),机器 Core。只读检查:没有改任何源码、构建文件或其他文档,也没有连任何设备。原始日志放在会话 scratchpad(`health/build-clean.log`、`health/lint.log`、`health/lint-results-release.txt`),没有进仓库。

## 一句话结论

构建和单测都是绿的(release 包 59 s 清洁构建,590 个单测全过),没有提交任何密钥,焦点和落盘两套铁律抽查没有发现违规。真正的欠账有两类:一是 **`lintRelease` 以 22 个 Error 失败**,其中 21 个经核对是误报,1 个是 launcher 有意为之;二是**外部复现门槛高**:仓库没有 Gradle wrapper,README 也没有构建说明。此外还有三处结构性风险:几个组合函数有 800 行左右、主线程上有少量文件 IO、局域网上传服务不带鉴权(设计上已接受)。

## 总表

| # | 项目 | 状态 | 一行证据 |
|---|---|---|---|
| 1 | 构建与单测 | ✅ | `testReleaseUnitTest assembleRelease` BUILD SUCCESSFUL,清洁构建 59 s;56 个测试类,590 个测试,0 失败 / 0 错误 / 0 跳过;APK 16,095,381 字节,用 release 证书签名 |
| 1b | 编译告警 | ✅ | 共 8 条:main 里 1 条弃用(`GtvTopBar.kt:143` 的 `Icons.Filled.Input`),另 7 条是测试方法名里带 `%` |
| 2 | Android Lint | ❌ | `lintRelease` 失败:22 个 Error、30 个 Warning、38 个 Information。Error 里 16 个 `ProduceStateDoesNotAssignValue` 和 5 个 `RestrictedApi` 核对后是误报,1 个 `QueryAllPackagesPermission` 是有意为之。`assembleRelease` 里的 `lintVitalRelease` 是通过的 |
| 3 | 依赖与构建配置 | ⚠️ | AGP 8.7.3、Kotlin 2.0.21、Compose BOM 2024.10.01、tv-material 1.0.0(有意钉住)、compile/target 35、min 28;R8 开;签名读 `~/.unitedu/`,仓库里零密钥。lint 报 6 个依赖有新版本 |
| 3b | 可复现性 | ⚠️ | 仓库**没有 `gradlew` / `gradle/wrapper`**;README 没有构建段落;Gradle 8.14.5 和 JDK 17 装在 `~/Library` 下,构建命令依赖本机的 `scripts/env.sh` |
| 4a | TODO/FIXME/HACK/XXX | ✅ | `app/src` 与 `scripts` 里一处都没有 |
| 4b | 超大文件和函数 | ⚠️ | `MainActivity.kt` 2117 行(其中 `onCreate` 576 行);`EditScreen()` 883 行、`HomeScreen()` 796 行、`PickerGrid()` 379 行 |
| 4c | 铁律 1(不用可滚动容器) | ✅ | 唯一的使用点是 `EditScreen.kt:1151` 的 `LazyColumn`,也就是 AppPicker 这个有文档的例外;其余 `verticalScroll` 出现的地方都在注释里 |
| 4d | 铁律 2(不把 requestFocus 当成功信号) | ✅ | 22 处 `runCatching { …requestFocus() }`,没有一处读 `.isSuccess`;`.isSuccess` 只用在 `startActivity` 和包查询上 |
| 4e | 铁律 6(守卫必须在 key 里) | ✅(抽查) | 扫了 58 个 `LaunchedEffect` 里带早退守卫的 25 处。有 3 处守卫不在 key 里,都属有意为之或已被间接覆盖(详见 §4) |
| 4f | GlobalScope / runBlocking / `!!` | ✅ | GlobalScope 0、runBlocking 0、`!!` 共 9 处(UploadServer 4 处) |
| 4g | 主线程 IO | ⚠️ | `SettingsStore.read/update`(带 fsync)在组合期或主线程上调用,`handlePick` 在主线程解码图片并写文件(persistence-audit 已知,没修) |
| 4h | 硬编码的用户可见文案 | ✅(抽查) | 19 处中文字面量全是日志或 `error()` 诊断文字;`BasicText("…")` 和 `toast("…")` 字面量都是 0 处 |
| 4i | 落盘铁律 | ✅ | 四个 JSON 状态文件都走 `LockedFile`;二进制文件走 `writeFileAtomically`;没有找到绕过这两条路的状态文件写入 |
| 5a | 字符串资源 | ✅ | 默认 / en / zh-rTW 各 293 条,互相不缺;未使用的有 1 条:`picker_back_to_close` |
| 5b | 内置资源 | ✅ | `assets` 共 10 MB(8 张 HDR JPEG,0.95–1.56 MB);`BuiltinHdrAssetsTest` 通过;`.DS_Store` 没有打进 APK |
| 5c | 仓库里的大文件 | ✅ | 超过 1 MiB 的已跟踪文件有 8 个,合计 14.4 MB(字体 4.15 MB、截图 1.8 MB、6 张内置图);历史里没有别的 >1 MiB blob;pack 大小 49.5 MiB |
| 6 | 仓库卫生 | ⚠️ | 工作区干净(检查时只有并行代理正在改的 `CLAUDE.md`);有 1 个未推送提交;有 1 个 locked 的过期 worktree 分支(已全部并入 main);没有跟踪不该跟踪的文件;LICENSE 是 Apache-2.0,NOTICE 基本齐全 |
| 7 | 文档一致性 | ⚠️ | 构建命令、`scripts/env.sh`、四台 AVD 都在;CLAUDE.md 里引用的 23 个符号和文件全部存在。但 `ImagePicker.kt:80/126/577` 这几个行号已经过期(现在是 135/186/916);R 编号不是统一的命名空间 |

## 各项详情

### 1. 构建与单测

```
source scripts/env.sh && gradle --no-daemon -q clean
gradle --no-daemon testReleaseUnitTest assembleRelease
→ BUILD SUCCESSFUL in 58s;54 actionable tasks: 52 executed, 2 up-to-date(墙钟 59 s)
```

- 增量构建(没有清理):5 s。
- 单测:把 `app/build/test-results/testReleaseUnitTest/*.xml` 加起来,得到 56 个文件、**590 个测试**,0 failures、0 errors、0 skipped,累计耗时 1.0 s。
- APK:`app/build/outputs/apk/release/app-release.apk`,16,095,381 字节(15.4 MiB),181 个条目。`apksigner verify --print-certs` 的结果是 `CN=UnitedU, O=UnitedU, C=CN`,SHA-256 为 `bdec5923…3199b`,说明用的是 release 证书,没有回落到 debug keystore。
- 编译告警 8 条,完整列表:
  - `app/src/main/java/com/uniteduone/launcher/GtvTopBar.kt:143:26`:`Icons.Filled.Input` 已弃用,应改用 `Icons.AutoMirrored.Filled.Input`
  - `Name contains character(s) that can cause problems on Windows: %`,共 7 条:`GtvGlowTest.kt:43`、`GtvLayoutTest.kt:225`、`GtvLayoutTest.kt:441`、`GtvMotionTest.kt:26/82/93`、`VerticalMotionTest.kt:68`
- Gradle 弃用提示(`--warning-mode all`):全部来自 AGP 内部(`isCrunchPngs` / `isUseProguard` / `isWearAppUnbundled`),与本项目脚本无关;升级到 Gradle 9 之前需要先升级 AGP。

### 2. Android Lint

```
gradle --no-daemon lintRelease
→ BUILD FAILED in 31s:Lint found 22 errors, 30 warnings(lint 8.7.3)
报告:app/build/reports/lint-results-release.{txt,xml,html}
```

各问题 ID 的数量:

| 严重度 | ID | 数量 | 判断 |
|---|---|---|---|
| Error | ProduceStateDoesNotAssignValue | 16 | **误报**:抽查的 5 处(`HomeScreen.kt:172`、`ThemeResolve.kt:21`、`EditScreen.kt:132`、`Wallpapers.kt:458`、`InputsPage.kt:85`)都在 lambda 里写了 `value = …`。这是 Compose lint 规则在 K2 或旧版本下已知的识别问题 |
| Error | RestrictedApi | 5 | **误报**:位置都是 `override fun dispatchKeyEvent`(`MainActivity.kt:1025`)和 `super.dispatchKeyEvent`(`:1126`、`:1206`,各报 2 次)。这是 `android.app.Activity` 的公开 API,lint 把它和 androidx.core `ComponentActivity` 上带 `@RestrictTo` 的同名方法混在了一起 |
| Error | QueryAllPackagesPermission | 1 | `AndroidManifest.xml:6`,**有意为之**:launcher 要列出全部应用,而且不上 Play |
| Warning | GradleDependency | 6 | `app/build.gradle.kts:107/108/111/112/113/117`:core-ktx 1.13.1→1.19.1、activity-compose 1.9.3→1.13.0、lifecycle-runtime 2.8.3→2.11.0、savedstate 1.2.1→1.5.0、compose-bom 2024.10.01→2026.09.00、tv-material 1.0.0→1.1.0(1.1.0 故意不升,build.gradle.kts 里写了理由) |
| Warning | UseOfNonLambdaOffsetOverload | 5 | `HomeScreen.kt:984`、`EditScreen.kt:639/710`、`ImagePicker.kt:523`、`AppsPage.kt:389`:按状态驱动的 `offset(y=…)` 走的是非 lambda 重载,动画期间每帧都要重组(首页主位移 R111 已经改成布局阶段读,剩下的是这几处) |
| Warning | PluralsCandidate | 4 | `values-en/strings.xml:116/131/272/295` |
| Warning | DiscouragedApi | 3 | `AndroidManifest.xml:37`、`SystemStatusReader.kt:75/78` |
| Warning | UnusedQuantity | 2 | `values-zh-rTW/strings.xml:45/66` |
| Warning | MonochromeLauncherIcon | 2 | `mipmap-anydpi-v26/ic_launcher{,_round}.xml:4` |
| Warning | ModifierParameter | 2 | `GtvTopBar.kt:94/280` |
| Warning | StaticFieldLeak | 1 | `VideoPlayback.kt:45`,`VideoGate` 这个 object 持有 `VideoController`,后者又持有 `TextureView`。核对发现 `releasePlayer()`(`VideoPlayback.kt:263`)会调用 `VideoGate.release(this)`,正常路径会放掉引用;异常路径**未验证** |
| Warning | UsableSpace / UnusedResources / ObsoleteSdkInt / AppBundleLocaleChanges / ModifierFactoryExtensionFunction | 各 1 | `UploadServer.kt:232`、`strings.xml:119`、`mipmap-anydpi-v26`、`LocaleOverride.kt:30`、`ImagePicker.kt:799` |
| Info | AutoboxingStateCreation | 36 | `mutableStateOf<Int/Float>` 可以换成 `mutableIntStateOf` 这类原始类型版本 |

Error 的完整位置:`MainActivity.kt:1025, 1126(×2), 1206(×2)`;`EditScreen.kt:132, 172, 1077, 1205`;`HomeScreen.kt:172`;`HomeSettingsCard.kt:79`;`ImagePicker.kt:104, 692, 892`;`ImportScreen.kt:162`;`InputsPage.kt:85`;`Onboarding.kt:197`;`Screensaver.kt:390`;`SettingsShell.kt:326`;`ThemeResolve.kt:21`;`Wallpapers.kt:458`;`AndroidManifest.xml:6`。

### 3. 依赖与构建配置

- 版本:AGP 8.7.3、Kotlin 2.0.21(包括 compose 编译器插件)、Compose BOM 2024.10.01、tv-material 1.0.0、compileSdk / targetSdk 35、minSdk 28、buildTools 35.0.0、JDK 17。Gradle 8.14.5 **不在仓库里**。
- 仓库源:`settings.gradle.kts` 把 `google()` 换成了 `https://dl-ssl.google.com/dl/android/maven2/`(本机网络的绕行办法,注释里写了原因)。别的网络环境应该也能用,但写法少见,**未在其他网络验证**。
- R8:`isMinifyEnabled = true`、`isShrinkResources = false`(理由写在注释里);`proguard-rules.pro` 只有一条 keep MainActivity。
- 签名:`app/build.gradle.kts:59-78` 从 `~/.unitedu/release.jks` 和 `release.properties` 读取;没有密钥时回落 debug keystore(`-PrequireReleaseKey=true` 时直接失败)。唯一的硬编码密码是 debug keystore 的公开默认值 `android`。
- 查密钥:用 `git grep` 找 `storePassword|keyPassword|api_key|secret|token|BEGIN …|ghp_|AKID|sk-`,在已跟踪文件里只命中 build.gradle.kts 读取属性的那几行,以及一些「设计 token」「tokenizer」字样的注释;`git log --all --diff-filter=A` 里从来没有加过 `.jks/.keystore/.p12/.pem/release.properties/local.properties`。`.gitignore` 已经覆盖 `*.jks *.keystore release.properties local.properties`。
- `gradle.properties`:`unitedu.updateUrls` 是注释状态(只有 GitHub Release 一条通道);`android.builder.sdkDownload=false`。
- 清单:`QUERY_ALL_PACKAGES`、`SYSTEM_ALERT_WINDOW`、`REQUEST_INSTALL_PACKAGES`、`REQUEST_DELETE_PACKAGES`、`INTERNET`。network security config 只放行 `127.0.0.1` 走明文(给模拟器验证用)。对外导出的组件有 MainActivity、`PackageRemovedReceiver`(只收受保护广播 `PACKAGE_FULLY_REMOVED`)、Dream 服务(要求 `BIND_DREAM_SERVICE`)。`allowBackup` 没写,默认是 true。

### 4. 代码层扫描

- `grep -rnE '\b(TODO|FIXME|HACK|XXX)\b' app/src scripts`:0 处。
- 规模:main 里 75 个 `.kt`、20,619 行;test 里 53 个 `.kt`、6,640 行,合计 27,259 行。
- 最大的 10 个文件:MainActivity 2117、EditScreen 1284、GtvLayout 1157、ImagePicker 1086、HomeScreen 1052、SettingsShell 636、Wallpapers 553、Onboarding 552、AboutScreen 539、AppsPage 515。
- 超过 200 行的函数:`EditScreen()`(EditScreen.kt:94,883 行)、`HomeScreen()`(HomeScreen.kt:63,796 行)、`MainActivity.onCreate`(MainActivity.kt:350,576 行)、`PickerGrid()`(ImagePicker.kt:228,379 行)、`settingsGroups()`(SettingsModel.kt:177,237 行)、`SettingsShell()`(SettingsShell.kt:300,228 行)。
- 铁律 1:用 `grep -rnE 'LazyRow|LazyColumn|LazyVerticalGrid|horizontalScroll|verticalScroll' app/src/main` 查,真正的使用点只有 `EditScreen.kt:1151 LazyColumn`,它在 `AppPicker` 里,属于 CLAUDE.md 焦点表里写明的例外(旁边注释说明了为什么聚焦的是整行)。
- 铁律 2:22 处 `runCatching { ….requestFocus() }` 都只是吞异常,落地判据都用目标自报的 `holder` / `focusedItem` / `isFocused`。
- 铁律 6:用脚本列出 25 个「早退守卫 + key」组合,其中 3 处守卫变量不在 key 里,都判定为可以接受:
  - `AppsPage.kt:351` 的看门狗守卫里有 `requesters.isEmpty()`,key 里没有。表换新时,定位效果(key 里有 `reqs`)会把 `restoring` 置真再放开,看门狗随之重启,等于间接覆盖了。
  - `AppsPage.kt:362` 守卫 `ghostNow`(`rememberUpdatedState`):残影只会从「活」变成「残影」,单向变化是有意设计。
  - `EditScreen.kt:340` 守卫 `appliedTarget == t`:这是铁律 7 推荐的「已应用值比对」写法,注释里有说明。
- GlobalScope 0、runBlocking 0;`!!` 共 9 处(UploadServer.kt 4、SettingsShell.kt 2、UploadPure / LayoutOps / EditScreen 各 1)。
- 主线程 IO(抽查):
  - `MainActivity.kt:468`:组合期 `remember(revision, settingsRevision) { SettingsStore.read(…) }`
  - `MainActivity.kt:1449/1498`:主线程 `SettingsStore.update`,`LockedFile.write` 里会 fsync
  - `MainActivity.kt:1822 handlePick`:主线程 `writeFileAtomically`,外加 `isDecodableImage` 校验,还有 `Wallpapers.select`
  - `EditScreen.kt:135`:`remember { mutableStateOf(Layout.read(ctx)) }`
  - `EditScreen.kt:1067`、`MainActivity.kt:865/963` 也有组合期的小文件读取
  - 没有启用 StrictMode
- 硬编码文案:`grep -nP '"[^"]*[\x{4e00}-\x{9fff}]…"'` 找出 19 处非注释的中文字面量,全在 `error()`、`log()`、`warn()` 或返回给日志的诊断串里(比如 `PrunePure.kt:41` 的 Skip 原因、`HdrGainmaps.kt:43`),不会显示到界面上。
- 落盘:`grep -nE '\.writeText\(|\.writeBytes\(|FileOutputStream\(|outputStream\(\)'` 找到的写入点,分别是 `LockedFile` 本身、`Update.kt:199`(每次独立的 `.part` 文件,见 persistence-audit)和 `UploadServer.kt:381`(`moveInto` 跨卷时直接写正式文件名,只用于 `upload.apk` 这个固定缓存路径,persistence-audit「没改的」一节已记录)。`LockedFile.write` 在 rename 失败时的 `dst.delete()` 兜底前面有 `.prev` 复制,符合铁律;只是那次复制包在 `runCatching` 里,复制失败时照样会走到 delete(边缘情况)。

### 5. 资源与素材

- 字符串:用脚本比对 `values/strings.xml`(293 条)和 values-en / values-zh-rTW,两边都是 0 缺失、0 多余。没有被代码或 XML 引用的只有 `picker_back_to_close`(`strings.xml:119`,三种语言都有),从 `fbe90d2`(R63)起就不再使用。lint 的 `UnusedResources` 也只报了这一条。
- `app/src/main/assets`:10 MB。screensavers 01–04 分别是 1.56 / 0.95 / 1.01 / 1.48 MB,wallpapers 01–04 分别是 1.31 / 1.09 / 1.48 / 1.56 MB,cards 4 个 webp 合计 60 KB,`web/index.html` 10 KB,再加 OFL 许可证。`BuiltinHdrAssetsTest`(1 个测试)通过。`assets/.DS_Store` 和 `assets/builtin/.DS_Store` 存在于磁盘上,但没有被跟踪,也没打进 APK(AGP 默认忽略点文件)。
- 已跟踪的 >1 MiB 文件有 8 个,合计 14,443,482 字节:`res/font/google_sans_flex.ttf` 4.15 MB、`docs/screenshots/r113-fade-before-tv.png` 1.81 MB,另外 6 张是内置 JPEG。整个历史里 >1 MiB 的 blob 也就是这 8 个。`git count-objects -vH` 显示 size-pack 49.52 MiB;docs 下已跟踪的文件共 40 MB,大部分是截图。

### 6. 仓库卫生

- `git status --short`:检查时只有 ` M CLAUDE.md`,是并行的文档代理在改,本检查没有碰它。
- `git rev-list --count origin/main..main` = 1:有一个未推送提交。
- `git worktree list`:`.claude/worktrees/agent-a7df0792767330047`(分支 `worktree-agent-a7df0792767330047`,状态 **locked**,最后一次提交在 2026-09-29 13:20,内容是 R127)。`git merge-base --is-ancestor` 确认它已经全部并入 main,可以清理。
- 被忽略但留在磁盘上的有:`.DS_Store`(根目录、docs、assets)、`.claude/`、`.gradle/`、`.kotlin/`、`.superpowers/`、`build/`、`app/build/`,都没有被跟踪。
- `.gitignore` 覆盖了构建产物、IDE 文件、密钥和本地配置。没有 `gradlew`,所以也就不存在「wrapper 该不该跟踪」的问题,但这本身就是一个缺口(见建议 2)。
- LICENSE 是 Apache-2.0 全文。NOTICE 列了 Google Sans Flex(OFL,并附全文)、AndroidX / Compose、NanoHTTPD(BSD-3)、ZXing,还有 AI 生成的横幅、图标、壁纸、屏保,以及 WeTV / Youku / YouTube / iQIYI 的商标声明。小缺口是 AndroidX 那一条没有点名 `androidx.tv:tv-material`、`palette-ktx`、`lifecycle-runtime`、`savedstate`(同属 Apache-2.0,条目写的是「including」,不算违规)。
- `scripts/migrate-gtv-to-main.sh` 是 gtv 包数据迁到正式包的一次性脚本。gtv 线已经并入 main,这个脚本很可能已经过期,是否删除没有核对。

### 7. 文档一致性抽查

- CLAUDE.md 的构建命令照原文可以跑通(见 §1)。`scripts/env.sh` 在;`~/Library/{Java/jdk-17, Gradle/gradle-8.14.5, Android/sdk}` 都在;emulator、adb、system-image(android-34 的 android-tv arm64-v8a 和 google-tv arm64)都在;AVD 有 `unitedu-tv`、`unitedu-tv-2`、`unitedu-tv-3`、`unitedu-gtv`,和 CLAUDE.md 的描述一致。
- CLAUDE.md 里点名的 23 个符号和文件(`CapsuleColumn`、`FadeSwitch`、`AppsPageCache`、`ShellMotion`、`MoveLanding`、`LocalPageGhost`、`aboutPageShown`、`appsPageRetarget`、`revealScroll`、`layoutWrites`、`dropRemovedElsewhere` 等,以及各个 `*Test.kt`)全部存在。
- **过期行号**:焦点表「图片选择器」那一行写着 `PickerGrid` 的调用点在 `ImagePicker.kt:80、126`,屏保图库在 `:577`,实际位置是 **135、186、916**。另外那两处也不是「固定传 `covered = false`」,而是没传参、用的默认值 `covered: Boolean = false`(ImagePicker.kt:246),语义没变。
- R 编号:源码注释里一共出现 117 个不同的 R 编号,和 `docs/superpowers/specs/2026-09-20-gtv-line-design.md` 逐个比对:
  - 抽查的 R29、R38、R61、R63、R67、R69、R83、R92、R102、R108、R115、R117、R122、R128、R129f,在 spec 里都能找到。
  - spec 里找不到的有 9 个:R2、R3、R4、R5、R13、R17、R18、R22、R111。原因是 spec 的 §12 索引从 R29 才开始,R29 之前的编号散落在 WORKLOG、各里程碑 spec 和 plan 里;R111 在 `docs/design/perf-2026-09-28.md` 和 WORKLOG 里。
  - 更要紧的是 **R 编号不是统一的命名空间**:`GtvTopBar.kt:209` 里的「owner 反馈 R2」指的是第 2 轮反馈;`network_security_config.xml` 里的「Controller ruling R4」、CLAUDE.md 里的「leftover-fixes Ruling R10」各属另一套编号。外部审查者会以为它们都能在 gtv spec 里查到。

## 建议优先处理的前 5 件(按风险排序,只建议不动手)

1. **局域网上传服务没有鉴权**(`UploadServer.kt`,路由 `DELETE /api/file`、`POST /api/upload`、`POST /api/apk`)
   - 风险:导入页开着的时候,同一局域网里的任何设备都能列出、上传、删除图库文件,还能推 APK 触发安装确认框。这是 M6 spec 已经接受的设计(服务只在导入页前台时存活,另有自定义头挡 CSRF、后台不弹安装),但外部审查几乎一定会把它列为头号问题。
   - 修法:开服时生成一次性随机 token 放进二维码 URL,所有 `/api/*` 校验它;或者至少让 `DELETE` 要求 token。
2. **`lintRelease` 是红的**(22 个 Error,其中 21 个误报)
   - 风险:任何人或 CI 跑 lint 都会失败;误报会淹没以后真正的新 Error;审查者可能拿「22 个 lint error」直接下结论。
   - 修法:在 `app/build.gradle.kts` 里加 `lint { baseline = file("lint-baseline.xml") }`,或者按 ID 带理由禁用 `ProduceStateDoesNotAssignValue` / `RestrictedApi`;在清单 `QUERY_ALL_PACKAGES` 上加 `tools:ignore` 并附一句理由;顺手清掉 `picker_back_to_close` 和 5 处 `UseOfNonLambdaOffsetOverload`。
3. **主线程和组合期上的文件 IO**(`MainActivity.kt:468/1449/1498/1822`、`EditScreen.kt:135`)
   - 风险:A95L 是 32 位 MTK,存储慢。`LockedFile.write` 每次都 fsync,`handlePick` 还要在主线程完整校验解码一张图,在 IO 压力下会掉帧,极端情况下会 ANR。launcher 发生 ANR 会被系统换回原厂桌面。
   - 修法:这些调用挪到 `Dispatchers.IO`(组合期读取改成 `produceState`);debug 构建开 StrictMode 的 disk read/write 检测来兜底。
4. **外部复现门槛高**:没有 Gradle wrapper,README 没有构建段落
   - 风险:GPT 审查者和贡献者没法用标准的 `./gradlew build` 复现构建和测试结果;网络受限时还要自己去理解 `dl-ssl.google.com` 镜像。
   - 修法:提交 Gradle 8.14.5 的 wrapper(`gradle wrapper --gradle-version 8.14.5`),在 README 加一段「构建 / 测试」写明 JDK 17、Android SDK 35 和 build-tools 35.0.0。这件事可以交给正在改 README 和 REVIEW-GUIDE 的代理一并处理。
5. **超大组合函数**(`EditScreen()` 883 行、`HomeScreen()` 796 行、`MainActivity.onCreate` 576 行 / 全文件 2117 行)
   - 风险:焦点账本、看门狗、还原效果、浮层状态全挤在同一个作用域里,铁律 5、6、7 那类「守卫和 key 失配」「目标和位置共用一个量」的回归最容易在这里再次出现,审查也很难逐条核对。
   - 修法:先别拆逻辑,只做机械提取。把每个浮层的焦点账本抽成独立的 `@Composable` 或 state holder(比如 `EditFocusState`),把 MainActivity 的浮层状态机抽成一个类,按「一个浮层一个文件」对齐 CLAUDE.md 的焦点责任表。

顺带一提(风险低):清理已并入 main 的 locked worktree `agent-a7df0792767330047`;修正 CLAUDE.md 里 `ImagePicker.kt:80/126/577` 的过期行号;在审查指南里说明 R 编号有好几套命名空间,R29 起的索引在 gtv spec §12;评估依赖升级,尤其 Compose BOM 已经落后 23 个月,tv-material 1.1.0 需要配 Compose 1.10。
