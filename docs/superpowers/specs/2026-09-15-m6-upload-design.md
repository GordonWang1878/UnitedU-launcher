# M6 设计:手机上传页(应用内 HTTP 服务 + 网页 + 二维码页 + 传 APK 安装)

> **状态:已实施(commit 4ebdbe1),待真机扫码验证。**
> 上游:`docs/DESIGN-unitedu-open-source.md` §5(素材入口)、§6(齿轮菜单)、§8(更新通道:传 APK 安装是兜底)、§11 M6 行。
> 与 M3 的关系:M6 只往 `library/*` 目录写文件;M3 的壁纸轮播每次轮换都重扫目录,上传即生效,**两者没有代码接口**。M6 在 M3 运行期间并行开发(Gordon 2026-09-15 定)。

## 0. 范围与 Gordon 已定项

| 决策 | 结论 |
|---|---|
| 手机页远程「设为当前壁纸」 | **不做**。只传 / 看 / 删;壁纸与卡片图仍在电视端选择器里选(守设计 §9「不在手机上编辑布局」) |
| 传 APK 安装 | **放 M6**(按设计)。安装函数独立成 `ApkInstaller`,M7 检查更新复用 |
| HTTP 服务 | **NanoHTTPD 2.3.1**(`org.nanohttpd:nanohttpd:2.3.1`,mavenCentral)。**许可证是 BSD-3-Clause**,设计 §5 写的 Apache-2.0 有误,NOTICE 按 BSD-3 写,设计文档同步纠正 |
| 二维码 | **ZXing core 3.5.3**(`com.google.zxing:core:3.5.3`,Apache-2.0,纯 Java,~540 KB) |
| 端口 / 地址 | 固定 8090,占用则顺延到 8099;页面显示 `http://<电视IP>:<端口>/` + 二维码 |
| 寿命 | 服务只在「导入图片」页打开时运行,返回键关闭即停;不设密码(设计定) |

**做**:服务 + 网页(壁纸 / 卡片图 / 屏保三类的传、看、删)+ 电视端二维码页 + 传 APK 安装 + 齿轮菜单入口 + NOTICE。
**不做**(带去向):远程设壁纸(v1 排除)、远程编辑布局(v1 排除)、齿轮菜单四项归并(M7)、检查更新(M7,复用 `ApkInstaller`)、U 盘导入(v1 排除)。

## 1. 服务(`UploadServer.kt`,NanoHTTPD 子类)

- 启动:`UploadServer(ctx, port, onSaved: (name) -> Unit)`,`start(SOCKET_READ_TIMEOUT, false)`;`bind` 失败(端口占用)由调用方顺延端口重试,8090–8099 全占 → 页面显示错误文案。
- `type` 参数 ∈ `wallpapers` / `cards` / `screensavers`,分别映射 `Paths.wallpaperLibrary` / `cardLibrary` / `screensaverLibrary`;其他值 → 400。
- 路由:

| 方法 路径 | 作用 | 响应 |
|---|---|---|
| `GET /` | 网页 | `assets/web/index.html`,把 `__STRINGS__` 占位替换成按电视语言取的三语 JSON |
| `GET /api/list?type=` | 列文件 | `{"type":"wallpapers","files":[{"name":"a.jpg","size":123,"mtime":1700000000000}]}`,按名排序,只列 jpg/jpeg/png/webp |
| `POST /api/upload?type=` | multipart 多文件,字段名 `files` | `{"saved":["a.jpg","b-1.jpg"],"rejected":[{"name":"x.gif","reason":"type"}]}`;reason ∈ `type` / `size` / `decode` / `name` |
| `DELETE /api/file?type=&name=` | 删一张 | `{"ok":true}`;不存在 404;名字清洗不过 400 |
| `GET /thumb?type=&name=` | 缩略图 | image/jpeg,`Apps.decodeScaled(path, 320, 180)` 压 q70;内存缓存 `name+mtime` → bytes,上限 200 项 |
| `GET /file?type=&name=` | 原图预览 | 原文件字节,MIME 按扩展名 |
| `POST /api/apk` | 传 APK,字段名 `apk` | 见 §4 |

- 上传落盘:NanoHTTPD `parseBody` 的临时文件目录指到 `cacheDir/upload/`(自定义 `TempFileManagerFactory`);每个文件:大小 ≤ 30 MB(`MAX_UPLOAD_BYTES`)→ 扩展名白名单 → 文件名清洗 → 重名追加 `-1`/`-2` → `Apps.isDecodableImage(tmp)` → `rename` 进目标目录 → `onSaved(name)`。任何一步失败进 `rejected`,不影响同批其他文件。
- **文件名清洗(纯函数 `sanitizeUploadName(raw): String?`)**:取最后一个 `/` 或 `\` 之后的部分;去掉控制字符(< 0x20、0x7F);trim;空、`.`、`..`、以 `.` 开头 → null;保留中文等 Unicode;总长 > 100 时截主名保扩展名。
- **重名(纯函数 `uniqueName(existing: Set<String>, name): String`)**:`a.jpg` 存在 → `a-1.jpg` → `a-2.jpg`…
- **电视 IP(纯函数 `pickAddress(candidates: List<Pair<String, String>>): String?`)**:输入 (接口名, IPv4) 列表,优先接口名以 `wlan`/`eth`/`en` 开头的,其次任意非回环;空 → null。Android 侧用 `NetworkInterface.getNetworkInterfaces()` 收集非回环、非虚拟、已 up 的 IPv4。
- JSON 用纯 Kotlin 拼(`jsonString`/`jsonList` 帮助函数,转义 `"`、`\`、控制字符),与 Settings 同理:可单测、不碰 org.json。
- 线程:NanoHTTPD 每请求一线程;`onSaved` 通过 `Handler(Looper.getMainLooper()).post` 回主线程改 Compose 状态。
- **写请求要带 `X-Requested-With: UnitedU`(终审补)**:`fetch` + `FormData` 是 CORS 简单请求——手机浏览器里**任何**网页都能不经预检直接往这台电视 POST/DELETE(拿不到响应,但事已经做了)。所以非 GET 路由(`POST /api/upload`、`DELETE /api/file`、`POST /api/apk`)在路由之前先查这个自定义头,缺 → `403 {"ok":false,"reason":"origin"}`;自定义头把跨源请求顶成要预检,我们不回 CORS 头,浏览器就把它拦在发出之前。GET 路由不查(只读,且 `/thumb`、`/file` 要能被 `<img>` 直接加载)。页面自己的三处非 GET `fetch` 都带上它。
- **`Content-Length` 预检(终审补)**:`POST /api/apk` > `MAX_APK_BYTES`、`POST /api/upload` > `MAX_UPLOAD_BYTES × 8`(一批最多 8 张)时**不读 body**,直接 `413 {"ok":false,"reason":"size"}` + `Connection: close`(body 没读完,连接不能复用)。声明值含 multipart 边界、比文件本身略大,卡在这里的一定也过不了后面逐文件那道闸。
- **开服前扫垃圾(终审补)**:进程被杀在安装流程中间时 `cacheDir/apk/upload.apk` 与 multipart 临时文件没人删;`UploadServer.init` 删掉前者、删掉 `cacheDir/upload/` 里超过 60 s 的残留。

## 2. 网页(`assets/web/index.html`,单文件,内联 CSS/JS,零外部资源)

- 手机优先、暗色(黑底、香槟强调,与电视 UI 同调)。顶部四个标签:壁纸 / 卡片图 / 屏保 / APK。
- 图片标签:缩略图网格(`/thumb`)、右上「上传」(`<input type=file multiple accept="image/*">`)、每格「删除」(二次确认)、点图开全屏预览(`/file`)。上传用 `fetch` + `FormData`,显示「上传中 N/M」,完成后刷新列表并列出 `rejected` 的原因。
- APK 标签:一个文件框 + 「安装到电视」按钮;成功显示包名与版本,`needs-permission` 时显示「请先在电视上允许安装,然后重试」。
- 文案全部来自 `window.STRINGS`(服务端注入),key 见 §5;不在 HTML 里写死任何语言。

## 3. 电视端「导入图片」页(`ImportScreen.kt`)

- 齿轮菜单新项「导入图片」→ `MainActivity.pickerTarget = VIEW_IMPORT`(与「默认桌面」卡同一挂法);全屏暗底浮层。
- 内容:标题「用手机导入图片」、大字 URL、二维码(ZXing `QRCodeWriter.encode(url, QR_CODE, 360, 360)` → `BitMatrix` → `Bitmap`,IO 线程生成)、实时行「已收到 N 个文件 · 最近:x.jpg」、底部提示「按返回键关闭并停止服务」。
- 服务寿命:`DisposableEffect(Unit)` 起服务(8090 起顺延),`onDispose` 停;拿不到 IP 或端口全占 → 用错误文案替换 URL 与二维码,页面照常可返回。**无密码的局域网服务不能活过用户离开**(T2 评审补的计划漏洞):按 HOME(`onNewIntent`)与 Activity `ON_STOP`(待机、切到别的应用)都关掉本页,服务随之停;回到桌面再进导入页重新起。ON_STOP 关页靠的是 **Compose(≥ 1.5)在 Activity STOPPED 期间照常重组**(停的只有帧时钟),不要把 `stop()` 从 `onDispose` 挪走。
- **ON_STOP 豁免窗窗长**:默认 30 s(够系统安装器走完);`import_apk_needs_permission` 那条 **120 s**(终审 Minor #6:用户要在电视设置页里翻到本应用、开开关、再返回,遥控器上这几步真机常常超过 30 s)。窗只在页面**还在前台**时才撑(见 §4)。
- **焦点账本(最简)**:根节点是唯一可聚焦项,`BackHandler { onExit() }`;`LaunchedEffect(focusNonce, focused) { if (!focused) 逐帧 requestFocus 直到自报 isFocused }`(铁律 2、3、6:守卫 `focused` 同时是 key)。
- **待机**:导入页打开期间不进入待机——手机传图几分钟没人碰遥控器是常态,若待机接管,第一下返回键会被当唤醒吞掉。`MainActivity` 那个待机 `LaunchedEffect` 的 key 与守卫同时加上 `pickerTarget == VIEW_IMPORT`(铁律 6)。

## 4. 传 APK 安装(`ApkInstaller.kt`,M7 复用)

- `POST /api/apk`:字段 `apk`,≤ 100 MB,存 `cacheDir/apk/upload.apk`(覆盖上一次);`packageManager.getPackageArchiveInfo` 解析不出 → `{"ok":false,"reason":"invalid"}`。
- **只在导入页还在前台时才装(终审 H2)**:`UploadServer` 拿一个 `isForeground: () -> Boolean`(导入页传 `lifecycle.currentState.isAtLeast(STARTED)`),在**主线程那一回合里**先问它与 `isAlive`,任一不成立 → 不调 `install`、不撑窗、删掉暂存的 APK,返回 `Result.BACKGROUND` → `{"ok":false,"reason":"background"}`,手机显示 `web_apk_background`。否则局域网上任何人都能每 30 s 续一次豁免窗,在用户已经切去看视频时把安装弹窗糊到屏幕上——而 ON_STOP 关页这道保险正被那个窗压着。`Result.BACKGROUND` 是调用方的判定,`install` 自己永不返回它。
- `ApkInstaller.install(ctx, file): Result`,`Result ∈ STARTED / NEEDS_PERMISSION / INVALID`(加上服务端才会产出的 `BACKGROUND`):
  - `!pm.canRequestPackageInstalls()` → `startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")).addFlags(NEW_TASK))`,返回 `NEEDS_PERMISSION`;手机端收到 `{"ok":false,"reason":"needs-permission"}`,电视端「导入图片」页实时行显示「请允许安装未知应用后重试」。**这次 `startActivity` 要 `runCatching` 兜住**(终审 Minor #1:国行定制固件不一定有这个设置页 → `ActivityNotFoundException`),跳不过去照样返回 `NEEDS_PERMISSION`——结局仍是「权限不够」,不该变成 500。
  - 否则 `Intent(ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(ctx, "$packageName.fileprovider", file), "application/vnd.android.package-archive").addFlags(FLAG_GRANT_READ_URI_PERMISSION or FLAG_ACTIVITY_NEW_TASK)` → 系统安装器接管;返回 `STARTED`,手机端 `{"ok":true,"package":…,"version":…}`。
- 清单:`<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>`;`<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.fileprovider" android:exported="false" android:grantUriPermissions="true">` + `res/xml/file_paths.xml`(`<cache-path name="apk" path="apk/"/>`)。
- 装的是别的应用时 UnitedU 自身不受影响;装的是 UnitedU 自己的新版时,现有 `RelaunchAfterUpdate` 照旧接管。
- **与 §3 的 ON_STOP 关页规则的交互(T2 复审发现)**:系统安装器 / 「允许安装未知应用」页都是全屏 Activity,会让 `MainActivity` 走 ON_STOP;若照 §3 一律关页,服务会被杀在安装流程中间。所以导入页持有 `suppressStopUntil: Long`(epoch ms):APK 路由在主线程回调里把它设为 `now + 30_000`,ON_STOP 观察者只在 `now > suppressStopUntil` 时才 `onExit()`。用时间戳不用布尔闩(铁律 7):窗口自然过期,不需要任何人清。**窗必须在 `startActivity` 之前、同一个主线程回合内设好**(否则安装器的 ON_STOP 可能抢先);**被窗压掉的 ON_STOP 要到期复查**:窗过了 Activity 仍未回到 STARTED(待机、切走)→ 关页停服务,否则无密码服务会无限期活着(T4 评审补)。

## 5. 入口、文案、声明

- `MainActivity.menuItems()` 在「换壁纸」之前加「导入图片」(`menu_import` / `menu_import_desc`);归并成设计 §6 的四项留 M7。
- strings ×3(`values` / `values-en` / `values-zh-rTW`):`menu_import`、`menu_import_desc`、`import_title`、`import_received`(`%1$d`)、`import_last`、`import_hint_back`、`import_error_no_network`、`import_error_port`、`import_apk_needs_permission`、`import_apk_started`;网页用 `web_title`、`web_tab_wallpapers`、`web_tab_cards`、`web_tab_screensavers`、`web_tab_apk`、`web_upload`、`web_delete`、`web_confirm_delete`、`web_empty`、`web_uploading`、`web_done`、`web_rejected_type`、`web_rejected_size`、`web_rejected_decode`、`web_apk_hint`、`web_apk_install`、`web_apk_needs_permission`、`web_apk_invalid`、`web_apk_background`。
- `NOTICE`:加 NanoHTTPD(BSD-3-Clause,含版权与许可全文要求的三条)与 ZXing core(Apache-2.0)。`docs/DESIGN-unitedu-open-source.md` §5、§8 的「NanoHTTPD,Apache-2.0」改为 BSD-3-Clause。
- `app/build.gradle` 加两条依赖;仍不引 material3 / tv-material。

## 6. 错误处理、测试、验收

- 不崩不留黑:端口全占 / 无 IP / 服务起不来 → 页面显示错误、可返回;坏图不落盘;`type` 非法 400;超大文件的 413 语义放进 `rejected.reason = size`(整批请求的 `Content-Length` 就超限时才真回 413,见 §1)。
- **名字的 400 与 404 是两件事**:`name` 先过 `sanitizeUploadName`,**清洗不过**(空、`.`、`..`、点开头)→ 400;**清洗得出一个合法名字**的一律当那个名字去目标目录里找,不在 → 404。所以 `../settings.json` 是 404 而不是 400:清洗按契约剥掉路径段、得到 `settings.json`,而 `library/wallpapers/` 里没有这个文件。逃逸在清洗那一步就已经堵死(剥路径 = 不可能跳出目录),404 是「这个目录里没有」的如实回答,不是漏判。
- 单测(纯 JVM,`UploadPureTest`):`sanitizeUploadName`(路径剥离、控制字符、`..`、点开头、超长、中文保留)、`uniqueName`(-1/-2 递增、扩展名保留)、`pickAddress`(wlan 优先、空表 null)、JSON 拼装转义、`isValidType`、`uploadKeys`(`files` 在先、数字后缀按数值排、跳号照收、不可解析的后缀排最后、两张表取并集)。
- 模拟器:`adb forward tcp:8090 tcp:8090` → `curl` 依次:`/api/list` 空、上传两张同名 jpg 得 `a.jpg`/`a-1.jpg`、上传 gif 进 `rejected(type)`、`/thumb` 返回 JPEG、`DELETE` 后列表减一、`adb shell ls library/wallpapers` 与列表一致;截图二维码页(URL 正确、二维码可由本机 `zbarimg`/ZXing 反解出同一 URL——没有工具就用手机相机扫);APK:`curl -F apk=@app-release.apk` → 模拟器弹出系统安装器(截图)。
- 真机(Gordon):手机扫码打开页面,传一张壁纸、删一张,电视回首页壁纸池含新图(M6 唯一需要肉眼的步骤)。
- 估时 2.5 天(与设计 §11 一致)。

## 7. 文件清单

- **新增**:`UploadServer.kt`、`UploadPure.kt`(清洗 / 重名 / 选址 / JSON 纯函数)、`ImportScreen.kt`、`ApkInstaller.kt`、`app/src/main/assets/web/index.html`、`app/src/main/res/xml/file_paths.xml`、`app/src/test/java/com/uniteduone/launcher/UploadPureTest.kt`。
- **修改**:`app/build.gradle`(nanohttpd、zxing core)、`AndroidManifest.xml`(权限 + provider)、`MainActivity.kt`(菜单项、`VIEW_IMPORT`、待机守卫)、`strings.xml` ×3、`NOTICE`、`docs/DESIGN-unitedu-open-source.md`(许可证纠错)。
