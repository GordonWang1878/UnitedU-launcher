# M3 设计:壁纸轮播 + 主题化管线 + 内置壁纸

> **状态:设计定稿(2026-09-15 brainstorming 六节 Gordon 通过),未开工。**开工前按本文拆实施计划。
> 上游:`docs/DESIGN-unitedu-open-source.md` §3(壁纸/主题)、§5(内置壁纸)、§11 M3 行;M2 决策「默认背景固定中性暗、主题色不染背景」。
> 焦点铁律:`CLAUDE.md` 七条。

## 0. 范围与 Gordon 已定的三项

| 决策 | 结论 | 理由 |
|---|---|---|
| 内置 6 张壁纸来源 | **程序生成抽象图**(1 中性暗底 + 5 同系变体) | 零版权、每张 ~50 KB、我全程可做、与「沉稳」主题一致 |
| 主题化管线参数 | **我定默认,Gordon 真机用滑块调**,调完把满意值改成默认 | 电脑管线数值在 Hub(SSH 不通);滑块让默认值不再关键 |
| 模糊/压暗控件 | **真滑块条**:左右键每步 10%,0–100% 共 11 档,带进度条 | 忠于设计 §3「两个滑块」;分段档位调不细 |

**做**:壁纸轮播(关/5 分/30 分/每天)、主题化壁纸开关、模糊/压暗滑块、CPU 离线处理 + 缓存、内置 6 张生成壁纸、替换 M1 金色默认底为中性暗底、设置页新增「壁纸」分组。
**不做**(带去向):齿轮菜单四项归并(M7)、上传页删图(M6)、DreamService(M5)、恢复默认(M7)、壁纸 Ken Burns(不做)、取色器(v1 排除)。

**技术约束**:`minSdk 28`,`RenderEffect` 需 API 31 → 管线走 CPU 离线处理,产物缓存文件,**不做实时 GPU 处理**。

## 1. 数据:Settings 新增 6 字段

同一套 `parseSettings` / `toJson` / 夹取 / 单测姿势(照 M2 Task B),字段追加在 `idleContent` 之后:

| 字段 | 类型 / 默认 | 合法域与夹取 |
|---|---|---|
| `wallpaperFile` | `String = ""` | `library/wallpapers/` 内**文件名**;空 = 未指定。含 `/`、`\`、`..` 或为空白 → 视为 `""`(settings.json 用户可手改,堵路径逃逸) |
| `wallpaperRotateMs` | `Long = 0` | `VALID_WALLPAPER_ROTATE_MS = [0, 300_000, 1_800_000, 86_400_000]`(关 / 5 分 / 30 分 / 每天);不在表内 → 0 |
| `wallpaperRotatedAt` | `Long = 0` | 上次轮换的 epoch ms;负数 → 0 |
| `wallpaperThemed` | `Boolean = false` | 默认**关**,守住 M2「默认背景不随主题」 |
| `wallpaperBlur` | `Int = 0` | 夹到 0..100,四舍五入到 10 的倍数 |
| `wallpaperDim` | `Int = 0` | 同上 |

**不变量:默认全零 ⇒ 首页壁纸观感与今日逐位一致(含 RGBA_F16 HDR 解码路径)。**

值表是唯一源(与 `VALID_CARDS_PER_ROW` 同理),设置页分段控件引用它。

## 2. 解析、迁移、铺入、轮播(新文件 `Wallpapers.kt`)

### 2.1 当前壁纸解析(`resolveSource(ctx, s): File?`)
1. `s.wallpaperFile` 非空且 `library/wallpapers/<name>` 是文件 → 它;
2. 否则 library 内图片(`jpg/jpeg/png/webp`,与屏保同一扩展名集)按文件名排序第一张;
3. 否则 `null` → 显示层回落 APK 内置 `assets/wallpapers/00-neutral.jpg`。

### 2.2 一次性准备(`prepare(ctx)`,IO 线程,在壁纸首次解析前跑,即今日 `ensureDefaultWallpaper` 的位置)
- **迁移旧根目录壁纸**:`files/wallpaper.jpg|png` 存在 → 移入 `library/wallpapers/legacy-wallpaper.<ext>`,若 `wallpaperFile` 为空则写成它;根文件删除。此后 **adb 后门 = push 进 `library/wallpapers/` 再在选择器里选**,与现有文档一致。
- **铺入内置壁纸**:标记文件 `library/wallpapers/.seeded` 不存在 → 把 6 张内置复制为 `unitedu-00-neutral.jpg` … `unitedu-05-green.jpg`(tmp → 校验可解码 → rename,照 `ensureDefaultWallpaper` 套路),写标记;若此时 `wallpaperFile` 为空,写成 `unitedu-00-neutral.jpg`。标记保证只铺一次:用户在 M6 上传页删掉内置图后不会复活(恢复默认是 M7 的事)。
- 外置存储没挂(`Paths.baseOrNull == null`)→ 整段跳过,显示层直接回落 APK 内置。
- prepare 写 settings 时 `MainActivity.homeSettings` 可能短暂过期(`wallpaperFile` 仍为空)——只影响轮播的「当前指针」与选择器高亮,下一次 `revision++` 即同步,无害。

### 2.3 选择壁纸(选择器 `onSelect`)
不再复制文件、**不再 `recreate()`**:写 `wallpaperFile = 文件名`、`wallpaperRotatedAt = now`(重置间隔),`settingsRevision++`。toast 文案不变。

**`settingsRevision`(实施时补的计数器)**:`MainActivity` 新增一个只让 `homeSettings` 重读、**不重建首页行**的计数器(`homeSettings = remember(revision, settingsRevision)`)。选图 / 轮播 / 滑块预览都走它——这些事每 5 分钟就来一次,若走 `revision` 会连 `layout.json` 与全部卡片图一起重读一遍。

### 2.4 轮播
`MainActivity.setContent` 内:

```
LaunchedEffect(homeSettings.wallpaperRotateMs, homeSettings.wallpaperRotatedAt) {
    val interval = homeSettings.wallpaperRotateMs
    if (interval == 0L) return@LaunchedEffect          // 守卫 = key(铁律 6)
    delay(rotationDelayMs(homeSettings.wallpaperRotatedAt, interval, now))
    val wrote = withContext(IO) { Wallpapers.rotate(this@MainActivity) }
    if (wrote) settingsRevision++   // 重读 settings → rotatedAt 变 → 本 effect 以新 key 重启、再等一个间隔
}
```
- `rotationDelayMs(rotatedAt, interval, now) = (rotatedAt + interval - now).coerceIn(0, interval)`:重启后按剩余时间续等;`rotatedAt` 在未来(时钟回拨)也最多等一个间隔。
- `rotate(ctx)`:重读 settings → **重扫目录**(复用屏保「进入时重扫」逻辑)→ `nextWallpaper(names, current)`(按名排序,当前的下一张,循环;当前不在列表 → 第一张)→ 写 `wallpaperFile` + `wallpaperRotatedAt = now` → **返回「是否写盘成功」,不是「是否换了图」**。少于 2 张:只刷新 `rotatedAt`、不换图,但仍返回 true —— `settingsRevision++` 让 effect 拿到新 `rotatedAt` 重启;若按「换了图才通知」写,单张图库时 key 不变、effect 结束,轮播从此停转,直到别的事件碰巧重读 settings(铁律 6 的变体:effect 的续命信号必须由它自己的 key 承载)。写盘失败返回 false,effect 自然结束;**不会**因单纯重读 settings 而恢复(key 是值不是计数器,重读到相同值不重启),要等 `rotateMs`/`rotatedAt` 真变(选图、改间隔、重启)。写失败意味着外置存储没了、图库也没了,可接受(T6 评审纠正原文)。
- **设置页开着时不轮播**(T6 评审补):`SettingsScreen` 持整份 Settings 快照、每次改动整对象回写,后台轮播写进去的字段会被下一次按键覆盖(壁纸来回翻)。effect 的 key 与守卫同时加 `settings`;`leaveSettings()` 的 `revision++` 让它重启、过期的一拍在退出时补上。根治(设置页写前重读、只改自己那个字段)在 T8 集成时做。
- 5 分钟一次写几百字节的 `settings.json`(原子写),可接受。
- 待机/屏保盖在壁纸上时轮播照常,只是看不见;不额外暂停。

### 2.5 显示层(`Wallpaper(ctx, spec)` 从 `HomeScreen.kt` 搬到 `Wallpapers.kt`)
- `WallpaperSpec(file: String, themed: Boolean, accent: Int /*ARGB,themed=false 时恒 0*/, blur: Int, dim: Int)` 由 `homeSettings` + `themeColors` 组装;`accent` 只在 themed 时参与,避免换预设触发无谓重处理。
- `produceState(key = spec)`(`settingsRevision` 只用来重读 settings 组装出新的 spec,不直接当 key):IO 线程 `prepare` → `resolveSource` → 参数全零走原路径(`decodeScaled` RGBA_F16),否则走 §3 管线;都失败回落 APK 内置。`prepare` 刚写进的 `wallpaperFile` 若 spec 里还是空,`load` 补读一次 settings。
- 换图用 `Crossfade`(`Theme.WallpaperCrossfadeMs = 1500`),新图未就绪前旧图原样留着(与 `revision` 不强制重建的既有原则一致)。
- 仍住在 `MainActivity.setContent` 顶层,不随编辑页重建。

## 3. 处理管线与缓存(`Wallpapers.process`,IO 线程)

### 3.1 顺序与数学
设计 §3:开 = 去色 → 染主题色 → 模糊 → 压暗;关 = 原图 + 模糊、压暗。三步颜色运算合成**一个 ColorMatrix**(纯函数 `wallpaperColorMatrix(themed, accentRgb, dim): FloatArray(20)`,可单测):

```
M = Scale(1 - dim/100) × [themed ? Tint(accent) × Saturation(0) : I]
Saturation(0):Rec.709 权重 (0.213, 0.715, 0.072),与 android ColorMatrix.setSaturation(0) 同值
Tint(accent):diag(a.r, a.g, a.b, 1)  —— 即「黑 → 主题色」的渐变映射,与金雾底同一手法
```
颜色运算与模糊都是线性算子,**顺序可交换**,所以先缩小再套矩阵:矩阵作用在小图上,几乎免费。

### 3.2 模糊 = 缩小 → 放大
- `blurTargetWidth(blur) = round(1920 / (1 + 15 × blur/100))`:0 → 1920(不缩),10 → 768,50 → 226,100 → 120。11 档单调、无重复。
- 缩小:反复减半(`createScaledBitmap(filter=true)`)到 ≤ 2× 目标,再一步缩到目标宽(高按 16:9);小图上跑一遍 3×3 均值消锯齿。
- 套 ColorMatrix(`Canvas.drawBitmap` + `ColorMatrixColorFilter`)。
- 放大:分级 ≤ 4× 逐步 bilinear 回 1920×1080,避免单次 16× 放大的菱形纹。
- `blur = 0` 时跳过缩放,矩阵直接作用于 1920×1080 工作图(~2M 像素,软件 Canvas 数十毫秒)。

### 3.3 输入输出
- 输入:`Apps.decodeScaled(path, 1920, 1080, ARGB_8888)` 后中心裁剪到恰好 1920×1080(缓存尺寸固定)。
- 输出:同一个 IO 块里 **既返回 Bitmap 直接显示,也写缓存**:`(externalCacheDir ?: cacheDir)/wallpapers/<key>.jpg`(外置 cache 优先:adb 能看、卸载即清),JPEG q90,tmp → rename。
- `key = sha1("$path|$mtime|$size|$themed|$accentHex|$blur|$dim|v1")`。下次同键直接解码缓存。
- 清理:每次写入后只保留最新 **12** 个(按 lastModified;命中时刷新 mtime,即真正的 LRU)。**T5 评审纠正**:原定 4 个小于内置 6 张,轮播永远不命中;且按写入时间淘汰会把每次开机都读的那张挤掉。
- 内存:中心裁剪 + 缩放(+ blur=0 时的矩阵)合成**一次 `Canvas.drawBitmap(src, srcRect, dstRect, paint)`**,只分配一张输出——原设计的 `createBitmap` 裁剪会让非 16:9 源图(手机照片)同时活着三张全分辨率位图(30–66 MB 瞬时)。
- **参数全零 → 完全绕开管线**:不解码两次、不写缓存、保留 F16。
- 跟随壁纸主色:`wallpaperThemeColors()` 改从 `resolveSource()` 的**原图**取 Palette(今日读死 `Paths.wallpaper`),再拿 accent 去染——不成环;主题化开着时 accent 变 → spec 变 → 重处理。

## 4. 设置页:「壁纸」分组 + SLIDER 控件 + 实时预览

### 4.1 分组与行(插在「布局」与「主题」之间;`order` / `controls` 同步扩,索引全部后移)
| 行 | 控件 | 取值 |
|---|---|---|
| 轮播间隔 | SEGMENTED | 关 / 5 分 / 30 分 / 每天(`VALID_WALLPAPER_ROTATE_MS` 顺序) |
| 主题化壁纸 | TOGGLE | 关 / 开 |
| 模糊 | **SLIDER** | 0–100,步 10 |
| 压暗 | **SLIDER** | 0–100,步 10 |

### 4.2 SLIDER 控件
- 数据上就是 `count = 11` 的 `Ctrl`,复用 `SettingRow.step(±1)` 与「左右键全消费、焦点不横移」的既有语义,**不引入新的焦点行为**;行高仍 `H_CTRL`,位移账本不变。
- 渲染:轨道(`Theme.UnfocusedSurface`)+ 已填充段(行聚焦 `Theme.Champagne`,否则其 0.22 alpha,与分段控件选中态同色)+ 右侧百分比文字。
- 字符串:`settings_group_wallpaper` 壁纸 / `settings_wallpaper_rotate` 轮播间隔 / `settings_rotate_daily` 每天 / `settings_wallpaper_themed` 主题化壁纸 / `settings_wallpaper_blur` 模糊 / `settings_wallpaper_dim` 压暗;「关」「%1$d 分」复用现有 key。三语(`values` / `values-en` / `values-zh-rTW`)。

### 4.3 实时预览(没有它,真机调参是盲调)
- 设置页浮层今日是不透明 `Theme.EditScreenBackground`。**焦点落在壁纸分组四行之一时**,浮层背景动画到 `Theme.SettingsPreviewScrim`(黑 0.35 alpha),壁纸从 640dp 内容列两侧与底下透出;离开该分组恢复不透明。
- `SettingsScreen` 新增参数 `onWallpaperParamsChanged: () -> Unit`;主题化/模糊/压暗任一改动后 **300 ms 防抖**再调用;`MainActivity` 实现为 `settingsRevision++`(重读 settings → spec 变 → 重处理 → Crossfade)。防抖用「上次通知过的值」比对,不用一次性布尔闩(铁律 7)。
- 轮播间隔改动不触发实时预览(无可视效果),照旧 `leaveSettings()` 时生效。

## 5. 内置壁纸与默认底

- `scripts/gen-wallpapers.py`(PIL,本机已有;不依赖 ffmpeg/ImageMagick):按金雾底同一手法——固定种子的 8×5 灰度噪声 → 双三次放大到 1920×1080 → 高斯模糊(半径 ≈ 78)→ 归一化 → 分段线性曲线 `0/0 0.55/0 0.80/0.22 1/0.62` 压掉暗部 → 乘染色 → JPEG q85。脚本进仓库,参数与种子写在脚本顶部,任何人可复现。
- 6 张输出到 `app/src/main/assets/wallpapers/`:`00-neutral.jpg`(灰染,**默认底**,兑现 M2 决策)、`01-gold`(#C0A73A)、`02-champagne`、`03-blue`、`04-purple`、`05-green`(各用不同种子),与 6 预设一一对应(石墨 ↔ 中性)。**色值以生成后截图为准,Gordon 看图定**;超预算(6 张合计 > 600 KB)则降 q 或缩小噪声频率。
- 删除 M1 的 `assets/default-wallpaper.jpg`;所有回落路径改读 `assets/wallpapers/00-neutral.jpg`。
- `NOTICE`:把 `default-wallpaper.jpg` 那条改为「`app/src/main/assets/wallpapers/*.jpg` 由 `scripts/gen-wallpapers.py` 程序生成,无第三方素材」——顺带了结设计 §5 提到的 Projectivy 来源疑云。
- `docs/TVHOME-README-focus-rules.md` 里描述壁纸文件路径与生成方式的段落同步更新。

## 6. 错误处理、测试、验收

### 6.1 逐级回落,绝不黑屏(沿用今日不变量)
外置未挂 → 跳过 prepare,读 APK 内置;`wallpaperFile` 指向的文件没了 → 排序第一张;library 空 → APK 内置;源图解码失败 → 当没有(不缓存坏结果);缓存写失败 → 本次仍用内存里的 Bitmap 显示,只 `Log.w`;settings 写失败 → 与 M2 同(内存生效、日志留痕)。

### 6.2 单元测试(纯 JVM,不碰 android 类)
- `SettingsTest` 扩:6 字段 parse / toJson 往返、缺失回默认、`wallpaperFile` 路径逃逸清洗、`rotateMs` 表外回 0、`blur/dim` 夹取与 10 的倍数。
- 新 `WallpapersTest`:`nextWallpaper()`(循环、当前不在列表、空表、单张)、`rotationDelayMs()`(过期→0、未来→夹到一个间隔)、`wallpaperColorMatrix()`(themed=false/dim=0 为单位阵;dim=50 对角 0.5;themed 时用 Rec.601 权重与 accent 对角)、`blurTargetWidth()` 11 档单调、`cacheKey()` 参数任一变则键变。

### 6.3 模拟器验收(`unitedu-tv`)
1. 全零参数:首页截图与 M2 收官截图**像素一致**(零回归)。
2. 首次启动:`library/wallpapers/` 出现 6 张 `unitedu-*.jpg` + `.seeded`,`settings.json` 的 `wallpaperFile = unitedu-00-neutral.jpg`,首页为中性暗底。
3. 旧根目录 `wallpaper.jpg` 迁移:push 一张到根 → 启动后出现在 library、根文件消失、首页显示它。
4. 主题化开/关、模糊 50、压暗 50 各一张截图;`cacheDir/wallpapers/` 出现对应缓存且 ≤ 4 个。
5. 轮播:间隔设 5 分,`adb shell` 把 `wallpaperRotatedAt` 改成 0 → 回首页即换下一张(Crossfade),`rotatedAt` 更新;单张 library 不换图但 `rotatedAt` 刷新。
6. 设置页:壁纸分组四行上下左右全程焦点不丢(铁律回归);聚焦滑块时壁纸透出、松手 300 ms 内首页壁纸跟着变。
7. 跟随壁纸主色开 + 主题化开:换预设不改壁纸主色来源(取原图),齿轮色跟原图主色、壁纸被该色染。

### 6.4 真机(A95L,Gordon)
用滑块把主题化默认观感调到满意 → 我把值改成 `Settings` 默认(单独一个小 commit)。这是 M3 唯一需要 Gordon 肉眼的步骤。

## 7. 文件清单

- **新增**:`app/src/main/java/com/uniteduone/launcher/Wallpapers.kt`(Android 侧:resolve / prepare / select / rotate / process / cache / `Wallpaper` composable)、`WallpaperMath.kt`(纯函数:`WallpaperSpec`、`nextWallpaper`、`rotationDelayMs`、`blurTargetWidth`、`wallpaperColorMatrix`、`wallpaperCacheKey`)、`app/src/test/java/com/uniteduone/launcher/WallpaperMathTest.kt`、`scripts/gen-wallpapers.py`、`app/src/main/assets/wallpapers/00–05-*.jpg`。
- **修改**:`Settings.kt`(6 字段 + 值表)、`SettingsTest.kt`、`SettingsScreen.kt`(分组 / SLIDER / 预览透出 / 防抖回调)、`MainActivity.kt`(`settingsRevision`、轮播 effect、`handlePick` 改写不 recreate、`wallpaperThemeColors` 改读原图、spec 组装)、`HomeScreen.kt`(移走 `Wallpaper` / `ensureDefaultWallpaper`)、`Paths.kt`(`wallpaperCacheDir`)、`Theme.kt`(`WallpaperCrossfadeMs`、`SettingsPreviewScrim`)、`strings.xml` ×3、`NOTICE`、`docs/TVHOME-README-focus-rules.md`。
- **实施计划**:`docs/superpowers/plans/2026-09-15-m3-wallpaper.md`(9 个任务,含并行波次表)。
- **删除**:`app/src/main/assets/default-wallpaper.jpg`。

## 8. 估时

2.5 天(与设计 §11 一致):数据 + 单测 0.3 / 解析迁移轮播 0.5 / 管线缓存 0.6 / 设置页 SLIDER + 预览 0.5 / 生成脚本 + 素材 + NOTICE 0.3 / 模拟器验收 + 收官 0.3。
