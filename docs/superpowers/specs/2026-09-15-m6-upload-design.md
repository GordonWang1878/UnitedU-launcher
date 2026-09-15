# M6 设计:手机上传页(应用内 HTTP 服务 + 网页 + 二维码页 + 传 APK 安装)

> **状态:设计定稿(2026-09-15 brainstorming 六节 Gordon 通过),未开工。**开工前按本文拆实施计划。
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

## 2. 网页(`assets/web/index.html`,单文件,内联 CSS/JS,零外部资源)

- 手机优先、暗色(黑底、香槟强调,与电视 UI 同调)。顶部四个标签:壁纸 / 卡片图 / 屏保 / APK。
- 图片标签:缩略图网格(`/thumb`)、右上「上传」(`<input type=file multiple accept="image/*">`)、每格「删除」(二次确认)、点图开全屏预览(`/file`)。上传用 `fetch` + `FormData`,显示「上传中 N/M」,完成后刷新列表并列出 `rejected` 的原因。
- APK 标签:一个文件框 + 「安装到电视」按钮;成功显示包名与版本,`needs-permission` 时显示「请先在电视上允许安装,然后重试」。
- 文案全部来自 `window.STRINGS`(服务端注入),key 见 §5;不在 HTML 里写死任何语言。

## 3. 电视端「导入图片」页(`ImportScreen.kt`)

- 齿轮菜单新项「导入图片」→ `MainActivity.pickerTarget = VIEW_IMPORT`(与「默认桌面」卡同一挂法);全屏暗底浮层。
- 内容:标题「用手机导入图片」、大字 URL、二维码(ZXing `QRCodeWriter.encode(url, QR_CODE, 360, 360)` → `BitMatrix` → `Bitmap`,IO 线程生成)、实时行「已收到 N 个文件 · 最近:x.jpg」、底部提示「按返回键关闭并停止服务」。
- 服务寿命:`DisposableEffect(Unit)` 起服务(8090 起顺延),`onDispose` 停;拿不到 IP 或端口全占 → 用错误文案替换 URL 与二维码,页面照常可返回。
- **焦点账本(最简)**:根节点是唯一可聚焦项,`BackHandler { onExit() }`;`LaunchedEffect(focusNonce, focused) { if (!focused) 逐帧 requestFocus 直到自报 isFocused }`(铁律 2、3、6:守卫 `focused` 同时是 key)。
- **待机**:导入页打开期间不进入待机——手机传图几分钟没人碰遥控器是常态,若待机接管,第一下返回键会被当唤醒吞掉。`MainActivity` 那个待机 `LaunchedEffect` 的 key 与守卫同时加上 `pickerTarget == VIEW_IMPORT`(铁律 6)。

## 4. 传 APK 安装(`ApkInstaller.kt`,M7 复用)

- `POST /api/apk`:字段 `apk`,≤ 100 MB,存 `cacheDir/apk/upload.apk`(覆盖上一次);`packageManager.getPackageArchiveInfo` 解析不出 → `{"ok":false,"reason":"invalid"}`。
- `ApkInstaller.install(ctx, file): Result`,`Result ∈ STARTED / NEEDS_PERMISSION / INVALID`:
  - `!pm.canRequestPackageInstalls()` → `startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")).addFlags(NEW_TASK))`,返回 `NEEDS_PERMISSION`;手机端收到 `{"ok":false,"reason":"needs-permission"}`,电视端「导入图片」页实时行显示「请允许安装未知应用后重试」。
  - 否则 `Intent(ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(ctx, "$packageName.fileprovider", file), "application/vnd.android.package-archive").addFlags(FLAG_GRANT_READ_URI_PERMISSION or FLAG_ACTIVITY_NEW_TASK)` → 系统安装器接管;返回 `STARTED`,手机端 `{"ok":true,"package":…,"version":…}`。
- 清单:`<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>`;`<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.fileprovider" android:exported="false" android:grantUriPermissions="true">` + `res/xml/file_paths.xml`(`<cache-path name="apk" path="apk/"/>`)。
- 装的是别的应用时 UnitedU 自身不受影响;装的是 UnitedU 自己的新版时,现有 `RelaunchAfterUpdate` 照旧接管。

## 5. 入口、文案、声明

- `MainActivity.menuItems()` 在「换壁纸」之前加「导入图片」(`menu_import` / `menu_import_desc`);归并成设计 §6 的四项留 M7。
- strings ×3(`values` / `values-en` / `values-zh-rTW`):`menu_import`、`menu_import_desc`、`import_title`、`import_received`(`%1$d`)、`import_last`、`import_hint_back`、`import_error_no_network`、`import_error_port`、`import_apk_needs_permission`;网页用 `web_title`、`web_tab_wallpapers`、`web_tab_cards`、`web_tab_screensavers`、`web_tab_apk`、`web_upload`、`web_delete`、`web_confirm_delete`、`web_empty`、`web_uploading`、`web_done`、`web_rejected_type`、`web_rejected_size`、`web_rejected_decode`、`web_apk_hint`、`web_apk_install`、`web_apk_needs_permission`、`web_apk_invalid`。
- `NOTICE`:加 NanoHTTPD(BSD-3-Clause,含版权与许可全文要求的三条)与 ZXing core(Apache-2.0)。`docs/DESIGN-unitedu-open-source.md` §5、§8 的「NanoHTTPD,Apache-2.0」改为 BSD-3-Clause。
- `app/build.gradle` 加两条依赖;仍不引 material3 / tv-material。

## 6. 错误处理、测试、验收

- 不崩不留黑:端口全占 / 无 IP / 服务起不来 → 页面显示错误、可返回;坏图不落盘;删除不存在的文件 404;`type` 非法 400;超大文件 413 语义放进 `rejected.reason = size`。
- 单测(纯 JVM,`UploadPureTest`):`sanitizeUploadName`(路径剥离、控制字符、`..`、点开头、超长、中文保留)、`uniqueName`(-1/-2 递增、扩展名保留)、`pickAddress`(wlan 优先、空表 null)、JSON 拼装转义、`isValidType`。
- 模拟器:`adb forward tcp:8090 tcp:8090` → `curl` 依次:`/api/list` 空、上传两张同名 jpg 得 `a.jpg`/`a-1.jpg`、上传 gif 进 `rejected(type)`、`/thumb` 返回 JPEG、`DELETE` 后列表减一、`adb shell ls library/wallpapers` 与列表一致;截图二维码页(URL 正确、二维码可由本机 `zbarimg`/ZXing 反解出同一 URL——没有工具就用手机相机扫);APK:`curl -F apk=@app-release.apk` → 模拟器弹出系统安装器(截图)。
- 真机(Gordon):手机扫码打开页面,传一张壁纸、删一张,电视回首页壁纸池含新图(M6 唯一需要肉眼的步骤)。
- 估时 2.5 天(与设计 §11 一致)。

## 7. 文件清单

- **新增**:`UploadServer.kt`、`UploadPure.kt`(清洗 / 重名 / 选址 / JSON 纯函数)、`ImportScreen.kt`、`ApkInstaller.kt`、`app/src/main/assets/web/index.html`、`app/src/main/res/xml/file_paths.xml`、`app/src/test/java/com/uniteduone/launcher/UploadPureTest.kt`。
- **修改**:`app/build.gradle`(nanohttpd、zxing core)、`AndroidManifest.xml`(权限 + provider)、`MainActivity.kt`(菜单项、`VIEW_IMPORT`、待机守卫)、`strings.xml` ×3、`NOTICE`、`docs/DESIGN-unitedu-open-source.md`(许可证纠错)。
