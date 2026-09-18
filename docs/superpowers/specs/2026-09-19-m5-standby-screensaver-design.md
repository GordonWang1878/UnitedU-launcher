# M5 设计:待机与屏保(先后两个状态 + 自定义屏保 + 系统屏保)

状态:设计定稿(2026-09-19,Gordon grilling 三轮后同意)。基线 = main `f7743e0` 之后(M8 已并入,含同列焦点与黑白预设)。术语与状态模型的权威版本在 `docs/DESIGN-unitedu-open-source.md` §4,本文只展开实现。

## 0. 决策(Gordon,2026-09-18 ~ 09-19)

| 议题 | 决定 |
|---|---|
| 术语 | 壁纸 = 首页底层背景图(壁纸库,「壁纸自动切换」);待机 = 首页无人操作达「待机时长」后的状态;自定义屏保 = 待机之后仍无人操作再进入的状态,全屏轮播屏保图库;系统屏保 = 电视系统的屏幕保护程序 |
| 状态模型 | 正常 → 待机 → 自定义屏保,**互斥、先后发生**;任意键回到正常,那一下只负责唤醒 |
| 待机显示 | 时钟 / 全黑 / 不淡出(不加「屏保」选项;09-18 版作废) |
| 屏保启动 | 按「进入待机后再过多久」算:关 / 1 / 5 / 10 / 30 分,默认 5 分;待机时长为「关」时从最后一次按键算;图库为空时永远不进屏保、停在待机,并在这一行提示 |
| 屏保轮播设置 | 30 秒 / 1 分 / 5 分,默认 30 秒(今天写死 30 秒) |
| 屏保画面 | 全屏轮播 + 左上大字时钟,轮播时时钟加淡阴影;自定义屏保与系统屏保一致 |
| 首页「屏保」按钮 | 立刻进入自定义屏保、跳过待机;图库为空时退为进入待机(「不淡出」时空操作) |
| 屏保图库 | 设置页入口;看图;长按缩略图 →「删除」→ 确认框 |
| 系统屏保 | `DreamService`,与桌面共用播放器**和播放进度**;图库为空时黑底 + 时钟;设置页「系统屏保 ▸」跳系统屏保设置页 |
| 改名 | 「待机」不变;壁纸组「轮播间隔」→「壁纸自动切换」;屏保间隔叫「屏保轮播设置」;设置组「待机」→「待机与屏保」 |
| 迁移 | 不需要:旧 `settings.json` 没有两个新键,读成默认值(屏保启动 5 分、轮播 30 秒);Gordon 电视上即变为「3 分待机只留时钟 → 再 5 分进屏保」 |

## 1. 状态机(MainActivity)

两个布尔量,**不改成枚举**(`idle` 已有五个消费者,见下):

| 状态 | `idle` | `screensaverActive` |
|---|---|---|
| 正常 | false | false |
| 待机 | true | false |
| 自定义屏保 | true | true |

`idle` 继续承担「首页内容淡出 + 下一个按键当唤醒吞掉」;`screensaverActive` 只在 `idle` 为真时可能为真(不变量,单测覆盖构造函数)。

### 1.1 计时

纯函数(`StandbySchedule.kt`,JVM 单测):

```kotlin
data class StandbyPlan(val standbyAt: Long?, val screensaverAt: Long?)   // 距最后一次按键的毫秒数,null = 不发生
fun standbyPlan(idleAfterMs: Long, screensaverAfterMs: Long): StandbyPlan
```

- `standbyAt = idleAfterMs.takeIf { it > 0 }`
- `screensaverAt = if (screensaverAfterMs == 0) null else (standbyAt ?: 0) + screensaverAfterMs`

计时效果(替换现有 `LaunchedEffect(touched, editing, menuOpen, overlay, homeOverlay, idleAfterMs)`,key 加 `screensaverAfterMs`,铁律 6):开头 `idle = false; screensaverActive = false`;有浮层就返回;按 plan 先 `delay(standbyAt)` 写 `idle = true`,再 `delay(screensaverAt − 已等)`,**到点时在 IO 线程查一次图库是否非空**,非空才写 `idle = true; screensaverActive = true`。图库为空 → 停在待机(若待机为「关」,则什么都不发生)。

### 1.2 唤醒

`dispatchKeyEvent` 首支:`if (idle) { idle = false; screensaverActive = false; wakeDownTime = …; return true }`。其余不变。

### 1.3 首页「屏保」按钮

沿用 M8 的请求计数 `screensaverRequests` 与「计时效果之后、等一帧」的效果(R3),把效果体改为:查图库 → 非空:`idle = true; screensaverActive = true`;为空:`if (idleContent != NO_FADE) idle = true`。调用点的 NO_FADE 守卫(M8 终审修的)移进效果体——有图时「不淡出」也能进屏保。

### 1.4 层次与淡入淡出(自下而上)

1. 壁纸(不变)。
2. **屏保层** `Screensaver(active = screensaverActive)`:不再看 `idle`、不再因 NO_FADE 不组合(NO_FADE 只管待机显示;待机显示为「不淡出」时屏保照样会来)。淡入 1200 ms、淡出 400 ms(同今天)。
3. 全黑层:目标值 `blackIdle && content == BLACK && !screensaverActive` —— 进屏保时黑层淡出,照片亮出来。
4. 首页:`HomeScreen` 新增参数 `screensaver: Boolean`。为真时:`clockAlpha` 目标恒 1(即使待机显示是「全黑」)、`heroAlpha` 恒 1(已由 idle 保证)、`HeroClock(shadow = true)`;卡片行 / 渐变 / pill 仍按 `contentAlpha` 淡出(idle 为真)。

### 1.5 大字时钟阴影

`HeroClock(modifier, showDate, shadow: Boolean = false)`:`shadow` 为真时时间与日期的 `TextStyle` 加 `Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 2f), blurRadius = 16f)`。不做描边、不做底板。

## 2. 共用播放器(`ScreensaverPlayer.kt`)

进程级单例,桌面屏保层与 `DreamService` 都只读它:

```kotlin
object ScreensaverPlayer {
    val files: StateFlow<List<File>>
    val index: StateFlow<Int>
    fun attach(ctx: Context, intervalMs: Long)   // 引用计数 +1;第一个 attach 时在 IO 线程重扫图库、启动换图计时
    fun detach()                                 // 引用计数 −1;归零时停计时,index 保留(下次 attach 接着播)
    fun rescan(ctx: Context)                     // 删图后调用
}
internal fun nextIndex(i: Int, size: Int): Int   // (i + 1) % size,size == 0 → 0
internal fun clampIndex(i: Int, size: Int): Int  // 图被删后夹回范围
```

- 换图计时只有一份(单例里的一个协程,`Dispatchers.Main`),避免桌面与系统屏保同时在场时双倍推进;两处都 attach 时间隔取最后一次 attach 传入的值。
- 扫描规则与今天相同:`library/screensavers/` 下 jpg / jpeg / png / webp,按文件名排序;`migrateOldScreensaver` 迁到播放器的首次扫描里。
- 每张图的呈现(`ScreensaverSlot`)不变:RGBA_F16 解码保留 Ultra HDR、`Crossfade` 2000 ms、Ken Burns 放大到 1.08;放大时长改为「轮播间隔 + 交叉淡入」(今天是写死 30 s + 2 s)。
- `Screensaver` 与 `ScreensaverSlot` 从 `HomeScreen.kt` 移到新文件 `Screensaver.kt`,桌面与系统屏保共用同一个 composable:`ScreensaverContent(showClock: Boolean)` 画轮播层;时钟由调用方叠(桌面是 HomeScreen 的 HeroClock,系统屏保见 §4)。

## 3. 设置页「待机与屏保」组

`GroupId.STANDBY` 标题改「待机与屏保」,六行:

| id | 行名 | 类型 | 内容 |
|---|---|---|---|
| idleAfter | 待机时长 | 分段 | 不变 |
| idleContent | 待机显示 | 分段 | 不变(时钟 / 全黑 / 不淡出) |
| screensaverAfter | 屏保启动 | 分段 | 关 / 1 / 5 / 10 / 30 分;行内小字提示:图库为空 →「图库为空,不会进入」;待机为关 →「从最后一次按键算」;否则 →「从进入待机算」 |
| screensaverInterval | 屏保轮播设置 | 分段 | 30 秒 / 1 分 / 5 分 |
| screensaverGallery | 屏保图库 ▸ | 动作 | 打开 `ScreensaverPoolViewer`(叠在设置页上,关掉后焦点回同一行) |
| systemScreensaver | 系统屏保 ▸ | 动作 | `startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))`,`ActivityNotFoundException` 时退到 `Settings.ACTION_SETTINGS`,两者都失败 toast |

- `ControlRow` 新增可选字段 `noteRes: Int? = null`,`SettingRow` 在标签与控件之间画一行小字(样式同 `ActionRowItem` 的 hint:12 sp,聚焦 `SecondaryText`、未聚焦 `FooterHintText`)。
- `settingsGroups` 新增参数 `screensaverImages: Int`;`SettingsScreen` 用 `produceState` 在 IO 线程数图库,key 带一个「图库版本」(删图后 +1)。
- 右栏行数 6 ≤ 8(SettingsScreen 注释里的上限),不需要滚动(铁律 1)。
- 壁纸组 `settings_wallpaper_rotate` 文案改「壁纸自动切换」(键名不变)。
- `Settings` 新增 `screensaverAfterMs: Long = 300_000L`(合法值 `VALID_SCREENSAVER_AFTER_MS = 0, 60_000, 300_000, 600_000, 1_800_000`)与 `screensaverIntervalMs: Long = 30_000L`(`VALID_SCREENSAVER_INTERVAL_MS = 30_000, 60_000, 300_000`);解析时不在表里就夹回默认,写盘写这两个键;「恢复默认」自然覆盖。

## 4. 系统屏保(`UnitedUDream.kt`)

- 从 spike 分支 `worktree-agent-a21dcfb91b5103162`(`4f184b7`)取清单条目与 `res/xml/dream.xml`,`UnitedUDream` 重写。
- **Compose 宿主**:`DreamService` 不是 `LifecycleOwner`,自己实现 `LifecycleOwner` + `SavedStateRegistryOwner`,在 `onAttachedToWindow` 里建 `ComposeView`,给它和 `window.decorView` 设 `setViewTreeLifecycleOwner` / `setViewTreeSavedStateRegistryOwner` 后 `setContentView`;生命周期:`onCreate` → CREATED,`onDreamingStarted` → RESUMED + `ScreensaverPlayer.attach`,`onDreamingStopped` → CREATED + `detach`,`onDetachedFromWindow` → DESTROYED。`isInteractive = false`(任意键结束屏保,系统行为),`isFullscreen = true`,`isScreenBright = true`。
- **内容**:读 `SettingsStore`(轮播间隔、显示日期、语言、主题色);主题色解析与 `MainActivity` 同一条路——`wallpaperThemeColors` 从 `MainActivity.kt` 的 private 顶层函数提到 `ThemePresets.kt` 旁边的共享位置;语言用 `localeFor(settings.language)` 设 `AppLocale.current`(进程可能是被系统屏保拉起的冷进程,MainActivity 没跑过)。画面:`UnitedUTheme` + `LocalThemeColors` 包住 `ScreensaverContent` + `HeroClock(shadow = true)`,位置与首页相同(左 58 dp、顶 150 dp)。
- **图库为空**:黑底 + 时钟(`HeroClock(shadow = false)`)。
- **续播**:`attach` 不重置 `index`,所以桌面先进了自定义屏保、系统屏保随后接管时,从同一张接着播。

## 5. 屏保图库删图(长按)

- 长按识别仍在 `MainActivity.dispatchKeyEvent`(M4 的 600 ms,Compose 看不到确认键的重复事件):新增一支「图库光着」——`pickerTarget == VIEW_SCREENSAVER_POOL && poolDeleteTarget == null && poolFocusedFile != null`——满 `LONG_PRESS_MS` 时 `poolDeleteTarget = poolFocusedFile`,吞掉这次按压(同 `longPressDownTime` 手法)。
- `PickerGrid` 新增可选回调 `onFocusedFile: ((File?) -> Unit)? = null`:缩略图得到焦点报文件、失去报 null(得失顺序保护同 `HomeScreen.report`);只有 `ScreensaverPoolViewer` 传它。全屏预览打开时网格失焦 → 报 null → 长按不生效。
- `ScreensaverPoolViewer` 新增参数:`refresh: Int`(图库版本,作 `files` 的 remember key)、`onFocusedFile`、`deleteTarget: File?`、`onConfirmDelete(File)`、`onCancelDelete()`;`deleteTarget != null` 时在自身之上画 `ConfirmDialog`(标题「删除这张图片?」、正文带文件名、确定「删除」、取消「取消」,默认焦点在取消)。
- 删除:`MainActivity` 在 IO 线程 `file.delete()` → `ScreensaverPlayer.rescan` → 图库版本 +1 → `poolDeleteTarget = null` → `focusNonce++`(网格按 nonce 重落焦点,`focusedIdx` 夹到新长度,落在原位置的下一张或上一张);删完为空 → 显示空态。
- 焦点账本:确认框自己负责焦点(ConfirmDialog 的 nonce + focusedBtn);关掉后由网格的 nonce 循环接回。`CLAUDE.md` 铁律 3 的表加一行。
- 设置页上的「屏保图库」行 hint 写「看图、长按删图」。

## 6. 文案(三语)

| key | 简体 | 繁體 | English |
|---|---|---|---|
| settings_group_standby(改) | 待机与屏保 | 待機與螢幕保護 | Standby & Screensaver |
| settings_wallpaper_rotate(改) | 壁纸自动切换 | 桌布自動切換 | Auto-change wallpaper |
| settings_screensaver_after | 屏保启动 | 螢幕保護啟動 | Screensaver starts |
| settings_screensaver_note_empty | 图库为空,不会进入 | 圖庫是空的,不會進入 | Gallery empty, won't start |
| settings_screensaver_note_from_input | 从最后一次按键算 | 從最後一次按鍵算起 | From the last key press |
| settings_screensaver_note_after_standby | 从进入待机算 | 從進入待機算起 | After standby begins |
| settings_screensaver_interval | 屏保轮播设置 | 螢幕保護輪播設定 | Slideshow interval |
| settings_seconds | %1$d 秒 | %1$d 秒 | %1$d s |
| settings_screensaver_gallery | 屏保图库 | 螢幕保護圖庫 | Screensaver gallery |
| settings_screensaver_gallery_desc | 看图、长按删图 | 看圖、長按刪圖 | View; hold OK to delete |
| settings_system_screensaver | 系统屏保 | 系統螢幕保護 | System screensaver |
| settings_system_screensaver_desc | 在系统设置里选 UnitedU | 在系統設定選 UnitedU | Pick UnitedU in system settings |
| pool_delete_title | 删除这张图片? | 刪除這張圖片? | Delete this picture? |
| pool_delete_body | 「%1$s」会从屏保图库删除,无法恢复 | 「%1$s」會從螢幕保護圖庫刪除,無法復原 | "%1$s" will be removed from the gallery for good |
| pool_delete_ok | 删除 | 刪除 | Delete |
| toast_system_screensaver_unavailable | 打不开系统屏保设置 | 無法開啟系統螢幕保護設定 | Can't open system screensaver settings |

分钟档复用 `settings_idle_minutes`,「关」复用 `settings_idle_off`,取消复用现有取消文案。`menu_settings_desc` 里的「待机」改「待机与屏保」。

## 7. 验收

- **单测**:`standbyPlan` 四种组合;`Settings` 新键默认值、非法值夹回、写盘往返;`nextIndex` / `clampIndex`;`screensaverActive ⇒ idle` 不变量(若抽成纯函数)。
- **模拟器时间线**(settings.json 设待机 1 分、屏保 1 分):1 分后待机只留时钟;再 1 分进屏保(照片 + 带阴影的时钟,黑层不在);任意键回正常且焦点原位;待机显示「全黑」→ 1 分全黑、2 分照片亮起;图库清空 → 永远停在待机;待机「关」+ 屏保 1 分 → 1 分直接进屏保;屏保「关」→ 永远停在待机。
- **屏保按钮**:有图 → 立刻进屏保(跳过待机);图库空 + 待机显示「时钟」→ 进待机;图库空 +「不淡出」→ 什么都不发生、下一个键不被吞。
- **设置页**:六行、提示文案三种情况、轮播间隔生效(30 秒 / 1 分可在模拟器上计时验证)、「屏保图库 ▸」打开并返回同一行、「系统屏保 ▸」打开系统页或回退、壁纸组改名。
- **删图**:长按缩略图 → 确认框(焦点在取消)→ 删除后焦点落到邻图、计数减一;删最后一张 → 空态;全屏预览里长按无效;取消不删。
- **系统屏保**:模拟器上设 `screensaver_components` 为 `com.uniteduone.launcher/.UnitedUDream` 并启动屏保(`adb shell cmd dreams` 或 Somnambulator,以实际可用为准),画面 = 照片 + 时钟;桌面先进屏保再触发系统屏保 → 同一张接着播;图库空 → 黑底 + 时钟;按键结束后回到原应用。
- **焦点七条**:待机 / 屏保唤醒后焦点原位;删图确认框与网格的焦点恢复;设置页新行的上下左右。
- **A95L 真机清单**(写进 WORKLOG,Gordon 做):时间线手感;屏保画面与时钟可读性;系统屏保在索尼列表里选 UnitedU 后能正常渲染与续播(需要他先在系统设置里打开屏保,他之前用 adb 关过)。

## 8. 不做

随机顺序播放;屏保转场效果选项;天气、通知等屏保信息;在电视上添加屏保图(仍走手机上传页);检测系统当前选的屏保是不是 UnitedU(隐藏设置键,读不可靠);屏保期间的媒体播放。

## 9. 风险

- **DreamService 里的 Compose 宿主**:生命周期 owner 手工接,漏一个 ViewTree owner 就是 attach 时崩溃;模拟器必测。退路:改用 `ImageView` 双缓冲 + `TextView` 的纯 View 实现,画面等价但不共用 composable。
- **冷进程拉起**:系统屏保可能在 UnitedU 进程不在时启动,`AppLocale`、主题色、存储路径都要在 Dream 里自己初始化;`Paths.baseOrNull == null`(外置存储未就绪)时按图库为空处理。
- **两个触发器同时在场**:桌面屏保与系统屏保叠放时只有一份换图计时(§2),不会双倍推进;但两层都在解码同一张大图,内存翻倍——`ScreensaverSlot` 的解码尺寸保持 1920×1080。
- **长按新入口**:图库那一支与首页那一支必须互斥(`homeBare` 本来就要求 `!overlayOpen`,图库打开时 `pickerTarget != null`),测试覆盖两处。
