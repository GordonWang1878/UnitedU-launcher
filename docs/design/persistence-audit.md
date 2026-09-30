# 落盘排查表(2026-09-23)

起因:2026-09-23 A95L 真机,卸载一个应用后整个首页被换成默认分类表。同一次卸载,MainActivity 的动态接收器(IO 协程)
和清单里的 `PackageRemovedReceiver`(裸线程)各跑一遍 read → 改 → write,两边共用 `layout.json.tmp`;输的一方 rename
失败,走兜底 `dst.delete()`,正式文件就没了,下一次 read 按首次运行写回默认。修复见 commit `e2ba6d4`(`LockedFile`)。
本表把应用写的**每一个**持久化文件都过一遍。

**进程模型**:全部组件(MainActivity、`PackageRemovedReceiver`、`UnitedUDream`、`UploadServer` 的请求线程)都在同一个进程里,
清单没有 `android:process`,所以进程内锁就够用。外部写者只有 `adb push`(开发后门),不在防护范围内。

## JSON 状态文件(外置 files/ 根目录)

| 文件 | 写者(线程) | 读改写 | 临时文件 | rename 失败兜底 | 缺失 / 损坏时 | 处置 |
|---|---|---|---|---|---|---|
| `layout.json` | 卸载清理 ×2(动态接收器 IO 协程 + 清单接收器裸线程);回到前台的未安装清理(IO,`pruneMissingPackages`,R68,锁内 `Layout.updateSaved`:没有已保存的布局时不读不写);首页「从分类移除」(IO);首页放下移动(IO,`rewrite`);编辑页 `persist`(IO,整份);引导第 2 步(串行 IO,整份);`read` 缺失时写默认 | 有 | 原来共用 `.tmp` → 现在每次独立 | 原来删正式文件且没有备份 → 现在先有 `.prev` 再删 | 原来写默认 → 现在先试 `.prev` | `e2ba6d4` 接入 `LockedFile`;本轮把 `read` 并进 `LockedFile.load`。**另修两处**:① 编辑页连续几次 `persist` 各自在 `Dispatchers.IO` 上跑,旧快照可能最后落盘、盖掉新快照 → 和引导共用串行调度器 `layoutWrites`;② 编辑页开着时后台卸载被清理掉的包,会被下一次整份写回复活成僵尸卡 → `persist` 改成锁内 `rewrite` + `dropRemovedElsewhere`。R67/R68(2026-09-23 晚):编辑页只画看得见的包、行内改动经 `withVisibleEdits` 合回整份(看不见的包留原位);回到前台清理没装的包,`read` 缺失回落的默认布局按已装过滤 |
| `titles.json` | 卡片 / 行 / 输入源改名(IO);卸载清理 ×2 | 有(`set`) | 同上 | 同上 | 缺失 = 空表;坏了原来直接写空表 → 现在先试 `.prev` | `e2ba6d4` 接入;本轮 `read` 并进 `load`,坏文件也会试 `.prev` |
| `hidden-inputs.json` | 长按菜单「隐藏」(IO 协程);设置页「恢复隐藏的输入源」(IO 协程) | **有,原来没加锁** | **原来共用 `hidden-inputs.json.tmp`** | **原来是删正式文件、没有备份** | 缺失 = 空集;坏了写空集 | **本轮接入 `LockedFile`**(`set` / `clear` 整段在锁内)。风险:两次操作交叠会丢一次隐藏,兜底 delete 还可能删掉整个文件,所有隐藏的输入源一起冒回来 |
| `settings.json` | 设置页(主线程)、壁纸 prepare 的迁移和清理(IO)、选壁纸、引导、语言、恢复默认、编辑页 `newAppsSeenAt` | 有(`update`) | 原来固定 `.tmp`,但有自己的锁 | 原来删正式文件、没有备份,但在锁内 | 原来写默认 | **本轮改成复用 `LockedFile`**:语义不变(缺失 → 写默认;`update` 返回写下的值或 null),多了 `.prev`:缺失或损坏时先从 `.prev` 恢复。模拟器实测过缺失和损坏两种情况 |

## 图片 / 二进制文件

| 文件 / 目录 | 写者(线程) | 风险 | 处置 |
|---|---|---|---|
| `library/{wallpapers,cards,screensavers}/` 上传落盘 | `UploadServer.serveUpload`(NanoHTTPD 每个请求一个线程) | ① 挑名字(`uniqueName(dir.list())`)和 rename 之间没加锁:两批同名上传并发时挑中同一个空位,后到的 rename **静默覆盖**先到的那张;② 跨卷回落是直接往正式文件名里流式复制,网格重扫、屏保、缩略图可能读到半截图,复制失败还会把半截文件留在图库里 | **本轮修**:`saveIntoLibrary`(`UploadPure.kt`)在锁内挑名字并移入;跨卷回落改走 `writeFileAtomically`(同目录独立 `.tmp` → fsync → rename)。开服时的 `sweepStale` 顺带清掉超过 60 s 的遗留 `.tmp` |
| 图库删图 | 手机 `DELETE /api/file`(请求线程);电视端屏保图库删图(IO) | 删的是整个文件,不改写内容;删掉当前壁纸时 `resolveSource` 回落到第一张图或纯深色;屏保播放器解不出图会返回 null 并跳过 | 无需修 |
| `icons/<pkg>.png` 自定义卡片图 | `handlePick`(主线程) | 只有一个写者;原来是固定 `<pkg>.tmp` + rename 失败先删正式文件 | **本轮改走 `writeFileAtomically`**(带「能解码」校验;rename 失败时旧图原样保留)。仍在主线程做 IO,这是性能问题,不是安全问题,没动 |
| `icons/<pkg>.png` 恢复原图 | `restoreOriginalIcon`(主线程)删除 | 删除本来就是目的 | 无需修 |
| 壁纸处理缓存 `cache/wallpapers/<hash>.jpg` | `Wallpapers.processed`(IO) | 临时文件名由 key 派生,同 key 的两次渲染会往同一个 `.tmp` 里交错写出半张 JPEG(Android 能把截断的 JPEG 解成下半截发灰的图) | **本轮改走 `writeFileAtomically`**。现在只有一个 `Wallpaper` 组合会调它,实际触发概率很低 |
| 内置壁纸铺入 `library/wallpapers/unitedu-*.jpg` + `.seeded` | `Wallpapers.seedBuiltins`(IO) | 清单目前为空,这段代码不会执行 | 铺图改走 `writeFileAtomically`;`.seeded` 用的是 `writeText` 直接写(不是原子写),写坏的最坏后果是补铺一次,**没改** |
| 旧壁纸 / 旧屏保迁移(`wallpaper.jpg` → library、`screensaver.jpg` → library) | `Wallpapers.prepare` / `scanScreensaverLibrary`(IO) | 一次性同卷 rename,旧文件名以后不再有人写 | 无需修 |
| 上传 APK `cache/apk/upload.apk` | `UploadServer.serveApk`(请求线程) | 路径固定:两个 APK 上传并发时,后到的会覆盖或删掉先到的文件,而系统安装器可能还在读。只是缓存,不丢用户数据;安装器会显示包信息,装错也看得见 | ~~没改~~ → **2026-09-30 已修**(Codex 评审 P2):每次上传独占 `upload-<唯一名>.apk`,复用 `UpdateFiles` 登记簿(前缀 `upload-`),交给安装器的文件本进程内不删、下次开服务清扫 |
| 更新包 `cache/apk/update-<uuid>.{part,apk}` | `Update.download`(IO) | `UpdateFiles` 已经做到每次独立文件名 + 进程内登记簿 + 锁内 promote 和 sweep | 无需修 |
| NanoHTTPD multipart 临时文件 `cache/upload/` | NanoHTTPD | 每个请求独立;开服时清掉超过 60 s 的 | 无需修 |
| 屏保视频原始上传临时文件 `cache/upload/raw*.part`(R103,2026-09-27) | `UploadServer.serveRawUpload`(请求线程) | 每次 `File.createTempFile` 独立命名;按 Content-Length 流式写入、fsync;`finally` 删除;校验过后经 `saveIntoLibrary` 移入(同卷 rename,跨卷回落 `writeFileAtomically`) | 新增即按铁律写;进程被杀留下的由开服 `sweepStale`(> 60 s)清掉。模拟器实测 300 MB 上传 5 s、Java 堆全程 ~7 MB、上传后目录为空 |

## 没改的,以及理由

- ~~**`upload.apk` 固定路径**~~(2026-09-30 已修,见上表;登记簿直接复用 `UpdateFiles` 加前缀参数,改动面比当时估计的小):要彻底修就得给每次上传一个独立文件名,再决定什么时候删(安装器读完的时间我们拿不到)。这就要重做一套 `UpdateFiles` 那样的登记簿,改动面和收益不成比例:触发条件是两个 APK 并发上传,而且最坏后果只是这次安装失败或弹窗包信息不对,用户看得见、可以重试。
- **`.seeded` 标记**:内置壁纸清单目前为空,这段代码不会执行;写坏的最坏后果是多补铺一次。
- **`handlePick` 在主线程做文件 IO**:这是性能问题,不在本轮范围内。

## 统一口径(`LockedFile.load`)

四个 JSON 状态文件的读取走同一个函数:正式文件能解析就用它;不在或坏了(坏文件改名 `.bad` 留证)就试 `.prev`;
还不行就返回 `Missing`(两个文件都不在)或 `Corrupt`。写回默认值由调用方在**同一把锁里**完成。
单测:`LockedFileTest`(每个分支、并发读改写时 `load` 一次都没读到文件缺失、`writeFileAtomically` 并发不交错)、
`UploadPureTest`(同名上传并发不覆盖、跨卷回落)、`LayoutOpsTest`(`dropRemovedElsewhere`)。
