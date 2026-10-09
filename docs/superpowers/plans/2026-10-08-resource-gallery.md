# 官方图片资源图库实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 执行方式待 Gordon 选择；本计划建议当前会话直接执行。

**Goal:** 在三个图片选择器接入官方在线图库，支持目录自动刷新、按需下载与离线选用，资源发布独立于 APK。

**Architecture:** 纯模型解析与策略、可注入的 HTTP/文件存储、进程级仓库和选图接入四层分工。APK assets、官方持久下载和用户上传分别管理，共用现有解码与显示流程。发布工具生成不可变图片和清单，图片先于清单上线。

**Tech Stack:** Kotlin、Compose、现有 kotlinx.coroutines、HttpURLConnection、Android org.json（JVM 测试已有真实 JSON 依赖）、JUnit 4；Python 3 图片处理与发布产物生成、现有 wrangler 发布能力。不增加客户端运行时依赖。

**Spec:** `docs/superpowers/specs/2026-10-08-resource-gallery-design.md`（已确认）。

## Global Constraints

- 首期只支持图片；不做视频、账号、云端个人同步、付费或管理后台。
- 同步清单和缩略图，选用时下载原图，下载后离线可用。
- 打开图库自动检查更新，并提供手动刷新入口；不进行常驻定时同步。
- 三个选图页提供「内置 / 在线图库 / 我的」；卡片按包名精确筛选。
- 固定 ID 与名称、排序分离；下载成功才提交选择；新在线屏保主动加入。
- 原图持久目录 `files/gallery/`，缩略图可回收目录 `cacheDir/gallery/`。
- `schemaVersion: 1`；清单最大 2 MiB、最多 2,000 项、缩略图每张最大 512 KiB、原图每张最大 32 MiB、最长边不超过 8,192 像素。
- 连接超时 10 秒、读取超时 15 秒、单次资源下载总超时 120 秒；首期无断点续传。
- 正式地址 HTTPS，同域同图库目录；所有重定向重新校验；清单和文件原子替换。
- 遵守现有自算位移、焦点与 HDR 规范，客户端 minSdk 28 不变。
- 用户界面、README、发布说明三语/双语沿用项目规则；开发文档中文。
- 本轮只授权计划；实施开始前取得计划确认。推远程、云端上传、正式发布分别确认，不调用发布脚本试跑生产。

## Review Focus

1. 两个 Activity 同时刷新或下载：仅一个共享任务，取消一个消费者不误删另一个正在使用的文件（任务 3、4）。
2. 用户点击 A 后立刻点击 B 或离开页面：A 迟到完成不覆盖 B、不改离开后的设置（任务 5、6）。
3. 同一 ID 新版本与已选旧版本并存：目录可更新，当前采用内容保持原样（任务 2、5）。
4. 下载时磁盘满或进程被杀：上一份完整文件与选择仍可用，重启清理仅删非活动临时文件（任务 2、3）。
5. 已下载资源下架或缩略图被系统清理：离线原图仍可选，焦点不跳到未知节点（任务 4、6）。

## 文件职责

新建 Kotlin 文件均位于 `app/src/main/java/com/uniteduone/launcher/`，对应 JVM 测试位于 `app/src/test/java/com/uniteduone/launcher/`。

| 文件 | 职责 |
|---|---|
| `GalleryCatalog.kt` | 清单模型、解析、排序、名称和包名筛选 |
| `GalleryPolicy.kt` | URL、限额和目录/选择纯策略 |
| `GalleryStore.kt` | 文件、登记簿、版本与原子写入 |
| `GalleryHttp.kt` | HTTP 条件请求、重定向、受限读取 |
| `GalleryRepository.kt` | 共享刷新/下载、状态与取消订阅 |
| `GallerySelection.kt` | 选择意图代次、预览/应用交接 |
| `GalleryPicker.kt` | 在线项目状态与操作展示 |
| `scripts/gallery-assets.py` | 校验、缩略图、清单与差异生成 |
| `scripts/gallery-publish.py` | 校验生成产物、受控上传、清单最后发布 |

不会把应用列表用的 `PickerGroups.kt` 改造成图片分组。

## Task 1: 清单协议和安全边界

**Files:** 创建 `GalleryCatalog.kt`、`GalleryPolicy.kt`；测试 `GalleryCatalogTest.kt`、`GalleryPolicyTest.kt`。

**Interfaces:**
```kotlin
data class GalleryBlob(val path: String, val bytes: Long, val sha256: String)
data class GalleryOriginal(val blob: GalleryBlob, val width: Int, val height: Int, val mediaType: String)
data class GalleryItem(val id: String, val kind: BuiltinKind, val names: Map<String, String>, val order: Int, val thumbnail: GalleryBlob, val original: GalleryOriginal, val packages: Set<String>, val credit: String)
data class GalleryCatalog(val revision: Long, val items: List<GalleryItem>)
fun parseGalleryCatalog(json: String): Result<GalleryCatalog>
fun galleryItems(catalog: GalleryCatalog, kind: BuiltinKind, packageName: String?): List<GalleryItem>
fun galleryUrl(base: String, relativePath: String): Result<String>
```

- [ ] 写失败测试：有效三类清单、多卡同包、三语缺项、重复 ID、未知 schema、负/非整数 revision、超限条目、非法大小/尺寸/哈希；整份清单拒绝而非悄悄丢项。
```kotlin
@Test fun rejectsPathTraversal() {
    assertTrue(galleryUrl("https://dl.uniteduone.com/gallery/", "../latest.json").isFailure)
    assertTrue(galleryUrl("https://dl.uniteduone.com/gallery/", "%2e%2e/a.jpg").isFailure)
    assertTrue(galleryUrl("https://dl.uniteduone.com/gallery/", "https://other.example/a.jpg").isFailure)
}
```
- [ ] 运行 `source scripts/env.sh && gradle --no-daemon testDebugUnitTest --tests '*GalleryCatalogTest' --tests '*GalleryPolicyTest'`，确认缺实现导致失败。
- [ ] 用 org.json 严格检查字段类型；固定 ID 用 `[a-z0-9][a-z0-9-]{0,95}`；检查路径段、百分号编码、反斜杠和 URI authority，阻止编码绕过。名称允许 Unicode；多余字段忽略。
```kotlin
fun galleryItems(catalog: GalleryCatalog, kind: BuiltinKind, packageName: String?) =
    catalog.items.filter { it.kind == kind && (kind != BuiltinKind.CARDS || packageName in it.packages) }
        .sortedWith(compareBy<GalleryItem> { it.order }.thenBy { it.id })
```
- [ ] 重跑上述测试至通过；补正常路径和查询/fragment/协议相对路径用例。
- [ ] 检查差异后只提交本任务文件：`git commit -m "feat: define gallery catalog and URL policies"`。

## Task 2: 持久下载与采用版本

**Files:** 创建 `GalleryStore.kt`、测试 `GalleryStoreTest.kt`。

**Consumes:** 任务 1 的模型。
**Produces:**
```kotlin
data class GalleryAdoption(val id: String, val sha256: String)
data class GalleryLocal(val item: GalleryItem, val file: File)
class GalleryStore(val root: File, val thumbs: File) {
    fun readCatalog(): GalleryCatalog?
    fun writeCatalog(catalog: GalleryCatalog, json: String, etag: String?)
    fun resolve(adoption: GalleryAdoption): GalleryLocal?
    fun downloaded(): List<GalleryLocal>
    fun commitOriginal(item: GalleryItem, part: File): GalleryLocal
    fun removeOriginal(sha256: String, protected: Set<String>): Boolean
    fun sweepParts(active: Set<File>)
}
```
登记簿和采用版本保存在 `files/gallery/`，登记簿以 `org.json` 序列化。图片文件名由已验证哈希生成，源 ID 不作为路径；不同原图版本共存。清单 JSON 与 ETag 用同一原子封装记录，不能分别提交后留下混搭状态。

- [ ] 写失败测试：原子替换中断、坏登记簿、同 ID 不同哈希并存、下架后 resolve、活动临时文件跳过、删除保护。
```kotlin
@Test fun keepsOldAdoptionAfterContentUpdate() {
    val old = store.commitOriginal(oldItem, oldPart)
    store.commitOriginal(newItem, newPart)
    assertEquals(old.file, store.resolve(GalleryAdoption(oldItem.id, oldItem.original.blob.sha256))!!.file)
}
```
测试类用 JUnit TemporaryFolder；`oldItem/newItem` 为同 ID 两份实际字节和实际 SHA-256，不能使用伪哈希绕过校验。
- [ ] 运行 `source scripts/env.sh && gradle --no-daemon testDebugUnitTest --tests '*GalleryStoreTest'` 确认失败。
- [ ] 实现同目录 `.part` 写入、flush、原子 rename 与完整登记；坏登记簿隔离后报告错误，不把已选图直接回落成默认。注入写入失败点以验证磁盘满保留旧文件。启动扫描恢复完整原图登记，跳过活动任务。
- [ ] 重跑测试通过；加入删未使用下载保留正在用壁纸/屏保的断言。
- [ ] 提交本任务文件：`git commit -m "feat: persist gallery originals and adopted versions"`。

## Task 3: HTTP 下载和校验

**Files:** 创建 `GalleryHttp.kt`、测试 `GalleryHttpTest.kt`；仅抽取必要通用校验，不改变 APK 更新策略。

**Consumes:** `galleryUrl`、`GalleryBlob`、`GalleryStore`。
**Produces:**
```kotlin
sealed interface GalleryFetch {
    data object NotModified : GalleryFetch
    data class Text(val json: String, val etag: String?) : GalleryFetch
}
interface GalleryTransport {
    suspend fun catalog(etag: String?): GalleryFetch
    suspend fun download(blob: GalleryBlob, part: File, onProgress: (Long) -> Unit)
}
```
实际实现使用可注入连接工厂和时钟，JVM 测试使用假连接，不依赖公网。Android 图片解码验证通过注入接口连接到现有解码函数；不在纯测试里调用 Android stub。

- [ ] 写失败测试：200/304、ETag、HTTP 错误、同域重定向与跨域拒绝、重定向次数最多 5、分块响应超限、实际字节数不匹配、SHA 错误、取消和总超时。
```kotlin
@Test fun redirectsCannotEscapeGallery() {
    val result = runCatching { runBlocking { transport.catalog(null) } }
    assertTrue(result.isFailure) // 假连接返回 Location: https://other.example/index.json
    assertEquals(1, fakeConnections.opened.size)
}
```
- [ ] 运行 `source scripts/env.sh && gradle --no-daemon testDebugUnitTest --tests '*GalleryHttpTest'` 确认失败。
- [ ] 禁止自动重定向，逐跳检查 URL。后台读块时检查取消、实际限额和总时长；结束校验字节数、哈希、格式、解码尺寸。取消时断开连接并删除本次 part；readTimeout 不能替代总超时。
```kotlin
connection.instanceFollowRedirects = false
connection.connectTimeout = 10_000
connection.readTimeout = 15_000
```
- [ ] 重跑测试通过；针对截断响应和下载中写盘 IOException 确认旧文件不变。
- [ ] 提交本任务文件：`git commit -m "feat: download and verify gallery resources"`。

## Task 4: 共享图库仓库

**Files:** 创建 `GalleryRepository.kt`、测试 `GalleryRepositoryTest.kt`；修改 `app/build.gradle.kts` 增加 `BuildConfig.GALLERY_BASE_URL` 可覆盖构建属性。

**Consumes:** 模型、store、transport。
**Produces:**
```kotlin
data class GallerySnapshot(val catalog: GalleryCatalog?, val downloaded: List<GalleryLocal>, val refreshing: Boolean, val error: String?)
interface GalleryRepository {
    val state: StateFlow<GallerySnapshot>
    suspend fun refresh()
    suspend fun original(item: GalleryItem, onProgress: (Long) -> Unit): GalleryLocal
    suspend fun thumbnail(item: GalleryItem): File
    suspend fun removeDownloaded(sha256: String, protected: Set<String>): Boolean
}
```
Android 提供进程级实例，构造参数注入 store、transport、scope 以便测试。生产默认地址来自设计文档，模拟器可覆盖；HTTP 仅允许明确的调试构建本地测试地址，生产不能开放任意 HTTP。

- [ ] 写失败测试：缓存先发出、304 保留、无效清单不替换、较旧 revision 不覆盖、并发刷新仅一次请求、按哈希下载去重、一位等待者取消不影响另一位。
```kotlin
@Test fun simultaneousRefreshUsesOneRequest() = runBlocking {
    coroutineScope { listOf(async { repo.refresh() }, async { repo.refresh() }).awaitAll() }
    assertEquals(1, fakeTransport.catalogCalls)
}
```
- [ ] 运行 `source scripts/env.sh && gradle --no-daemon testDebugUnitTest --tests '*GalleryRepositoryTest'` 确认失败。
- [ ] 用 Mutex 管理共享任务登记，用仓库 scope 持有请求，消费者取消仅退出等待；没有消费者时取消未完成下载。StateFlow 更新通过同一状态入口，避免下载覆盖刷新结果。首次离线返回空在线目录、已缓存离线保留目录及已下载资源。
- [ ] 目录合并本地已下载下架条目，标记「已下载」；缩略图丢失后可重取或从本地原图生成，更新不改已采用版本。
- [ ] 重跑测试通过；增加旧 revision、缩略图回收及下架项目合并测试。
- [ ] 提交本任务文件：`git commit -m "feat: coordinate gallery refresh and download state"`。

## Task 5: 壁纸、卡片与屏保选用

**Files:** 创建 `GallerySelection.kt`、测试 `GallerySelectionTest.kt`；修改 `BuiltinCatalog.kt`、`Settings.kt`、`Wallpapers.kt`、`ScreensaverPlayer.kt`、`MainActivity.kt` 和对应 Settings/Builtin/播放器测试。实施前以 `rg` 定位应用卡片保存回调，仅修改其现有文件。

**Consumes:** `GalleryAdoption`、`GalleryLocal`、repository。
**Produces:** `ImageChoice.Gallery(id)`；Settings 增加 `galleryWallpaper: GalleryAdoption?` 和 `galleryScreensavers: Map<String, GalleryAdoption>`。壁纸字符串仍写 `gallery:<ID>`，版本在 galleryWallpaper。普通/内置壁纸选用时清除 galleryWallpaper，避免残留引用。
```kotlin
class GallerySelection {
    private var generation = 0L
    fun begin(): Long = ++generation
    fun invalidate() { generation++ }
    fun isCurrent(token: Long): Boolean = token == generation
}
```
采用版本保存在 settings 的原子写盘流程中，不与另一个文件分别提交。下载登记不等于使用登记；store 持有内容，settings 持有当前采用意图。

- [ ] 写失败测试：旧设置字段缺失、三种壁纸编码、恢复默认保留图库取舍、下载失败/取消不改设置、预览不加屏保、下架保持使用、同 ID 更新不改版本。
```kotlin
@Test fun lateSelectionCannotApply() {
    val selection = GallerySelection()
    val first = selection.begin()
    val second = selection.begin()
    assertFalse(selection.isCurrent(first))
    assertTrue(selection.isCurrent(second))
    selection.invalidate()
    assertFalse(selection.isCurrent(second))
}
```
- [ ] 运行 `source scripts/env.sh && gradle --no-daemon testDebugUnitTest --tests '*GallerySelectionTest' --tests '*SettingsTest' --tests '*BuiltinCatalogTest' --tests '*ScreensaverPlayerTest'` 确认新增断言失败。
- [ ] 扩展设置解析/序列化和默认恢复保留字段；`Wallpapers` 先用采用哈希查持久原图，未就绪不能当成用户文件名或触发默认回落。IO 只在后台。
- [ ] 选用异步回调必须 token 仍有效、目标包名/页面仍一致才提交。卡片调用现有复制路径；屏保先下载再写加入映射，移出只删加入映射。
- [ ] `ScreensaverPlayer` 按内置过滤 + 已加入在线原图 + 我的图库组装播放队列；桌面和 DreamService 共用队列，变更后 rescan。坏图按现有失败规则跳过，不自动下载屏保。
- [ ] 重跑测试通过，验证冷启动离线与坏采用字段不破坏其他设置。
- [ ] 提交本任务文件：`git commit -m "feat: apply gallery resources to wallpaper cards and screensavers"`。

## Task 6: 图库展示和遥控器焦点

**Files:** 创建 `GalleryPicker.kt`、测试 `GalleryPickerTest.kt`；修改 `ImagePicker.kt`、`PickerCells.kt`、`PickerCellsTest.kt`、`PickerCellsBoundaryTest.kt` 和 `app/src/main/res/values{,-zh-rCN,-zh-rTW}/strings.xml`（以实际语言目录为准）。

**Consumes:** snapshot、GalleryItem、selection 和三个现有选用回调。
**Produces:** 三组选图布局、刷新入口、下载进度/取消/重试、已下载删除与版本更新显示。

- [ ] 写失败测试：按 key 聚焦、刷新插入不移焦、焦点项下架邻项回落、无邻项落刷新；应用卡片无匹配时不显示无关资源；已下载下架图片继续显示。
```kotlin
fun galleryFocusKey(id: String) = "gallery:$id"
fun resolveGalleryFocus(previous: String, previousKeys: List<String>, newKeys: List<String>): String
```
`resolveGalleryFocus` 保留仍在的 key，否则按旧位置找新相邻项，空组返回 `gallery:refresh`；测试覆盖第一项、末项、唯一项被移除。
- [ ] 运行 `source scripts/env.sh && gradle --no-daemon testDebugUnitTest --tests '*GalleryPickerTest' --tests '*PickerCells*'` 确认失败。
- [ ] 将图片分组扩展为 BUILTIN/GALLERY/MINE，计数和 cell 映射显式包含在线条目；不要只在 UI 插入项目而继续用旧索引。保留已有新增用户上传格。
- [ ] 下载状态更新按 key 替换，不重建焦点请求对象；屏幕可见条目请求缩略图，滚出取消订阅。所有文字加入三语字符串资源。
- [ ] 预览等待原图，成功再进入现有全屏预览；离开 invalidate 意图。删除已下载原图前检查采用保护，正在使用项提示先取消使用。
- [ ] 重跑 JVM 测试并在模拟器验证三类页、长列表、自算位移、按住方向键、取消下载、刷新插入/下架与返回后的焦点。
- [ ] 提交本任务文件：`git commit -m "feat: expose online gallery in image pickers"`。

## Task 7: 资源生成和可审阅发布工具

**Files:** 创建 `scripts/gallery-assets.py`、`scripts/gallery-publish.py`、`scripts/tests/test_gallery_assets.py`、`scripts/tests/test_gallery_publish.py`、`docs/design/resource-gallery-publishing.md`；读取现有 `scripts/hdr-assets.py` 与 `docs/design/hdr-image-spec.md`，不改变现有 APK 发布流程。

**Consumes:** 任务 1 的固定协议。
**Produces:** `python3 scripts/gallery-assets.py --source <目录> --previous <清单> --out <目录> --revision <整数>`；`python3 scripts/gallery-publish.py --artifact <目录> --bucket unitedu --base-url https://dl.uniteduone.com/gallery/ --dry-run`。实际上传必须显式 `--publish`，默认只检查与输出操作清单。

源目录提供 `resources.json`，使用清单的 id/kind/names/order/packages/credit，另用 `source` 指向源图片；工具根据内容生成 original/thumbnail 字段。原图不改源文件、不在仓库生成正式资源。

- [ ] 写失败测试：三语缺失、卡片包名缺失、非法 ID、revision 未递增、源文件路径穿越、HDR 规范不合格、图片超限、输出哈希错误；三类合法源生成可被任务 1 解析的 JSON。
```python
def test_manifest_is_published_last(self):
    commands = build_upload_commands(artifact=self.artifact, bucket="unitedu")
    self.assertTrue(commands[-1][0].endswith("/index.json"))
    self.assertTrue(all(not key.endswith("/index.json") for key, _ in commands[:-1]))
```
`build_upload_commands` 返回 `(object_key, local_path)` 数组；测试使用 tempfile 和假上传器，不访问云端。
- [ ] 运行 `python3 -m unittest discover -s scripts/tests -p 'test_gallery_*.py'` 确认失败。
- [ ] 使用现有 Pillow/HDR 工具链检查原图，生成最长边 640px、512 KiB 以内 SDR JPEG 缩略图；HDR 原图保留输入字节，规范不合格拒绝并给出现有转换命令。格式/尺寸和客户端限额一致。
- [ ] 发布器 `subprocess.run([...], check=True)` 调用 wrangler，禁止 shell=True；先验证完整产物、上传不可变图片、读取远端验证哈希/体积，最后上传短缓存清单。任一步失败不更新清单，dry-run 不联网、不上传、不读取凭据。
- [ ] 加测试验证图上传失败阻止清单、远端不一致阻止清单、只生成模式不创建云对象、上版 ID 的 kind 改动拒绝。对输入中的多行、裸 `\c`、正常文本做回归；Python 保真处理，不加 shell 数据通道。
- [ ] 重跑 Python 测试通过。写发布手册：新增/更新/下架、固定 ID、三语/包名/许可、HDR、生成预览、确认后发布和更高 revision 回滚。
- [ ] 提交本任务文件：`git commit -m "feat: prepare and publish standalone gallery catalogs"`；此步骤仅本地提交，不发布。

## Task 8: 集成验收和文档

**Files:** 更新 `docs/design/builtin-assets.md`、`docs/REVIEW-GUIDE.md`、`docs/design/settings-inventory.md`、`README.md`、`README.zh-CN.md`；在现行设计裁定索引按实际最新 R 号追加，不预占编号。仅记录有长期价值的最终结论至 `docs/WORKLOG.md`。

- [ ] 运行客户端全部 JVM 测试和构建：
```sh
source scripts/env.sh && gradle --no-daemon testDebugUnitTest assembleDebug assembleRelease
```
结果必须成功；若依赖或构建权限受限，按平台流程申请，不把失败写成通过。
- [ ] 运行发布工具全部测试：
```sh
python3 -m unittest discover -s scripts/tests -p 'test_gallery_*.py'
git diff --check
```
- [ ] 使用仓库外本地 fixture 目录托管版本 A/B 清单、有效/截断/坏哈希原图和缩略图；模拟器构建覆盖测试 URL。模拟器网络策略只存在 debug 配置，不修改生产 HTTPS 限制。
- [ ] 按设计 §9 验收：首次/缓存离线、304、刷新失败、下载取消/错误、重启、三类选用、自定义/系统屏保、下架保留、内容更新不换图、HDR 和焦点；记录真实未通过项，不靠截图替代行为检查。
- [ ] 从正式 APK 解包检查没有测试地址、fixture 或自动发布配置；确认生产 BuildConfig 指向设计地址。尚无线上清单时必须有正确空态，不伪称服务已上线。
- [ ] 同步双语用户文档及项目手册：首次支持需更新 APK、之后资源独立更新、离线范围、空间管理、首期无账号同步。
- [ ] 审查全分支差异，确认无无关重构、敏感信息、未经授权上传/推送。按选定执行方式进行最终评审，修复实质问题后提交文档。

## 收口与发布边界

本计划实施完成的标准是客户端功能、离线/并发/焦点/HDR 验收和发布工具干跑验证完成。实际资源上传和首次支持图库的 APK 发布是单独的对外操作：先展示具体资源差异、目标地址和版本，再获得 Gordon 确认，由 agent 执行。真机通过正式检查更新路径验收，不直接安装测试包。

## 自检结果

设计 §1–3 对应任务 1、7；§4 对应任务 3、4；§5 对应任务 5、6；§6 对应任务 2、5、6；§7 对应任务 2、3；§8 对应任务 7；§9 对应任务 1–8。五项 Review Focus 均已纳入所属任务测试。公共接口以本计划定义为准；实施若发现结构性冲突，先明确修订设计/计划，不默默改变已确认行为。
