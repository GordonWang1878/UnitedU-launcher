# 频道行(R164 / R165 §3–§5)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在「编辑桌面」的「新的一行」里亲手把其它应用发布的 TvProvider 预览频道加成首页的一行(默认一行都没有),首页按海报比例画最多 12 张节目卡,确定启动节目。

**Architecture:** 数据层只在 `layout.json` 的行上加一个可选的 `channel` 字段(`ChannelRef`),行序、上移下移、落盘铁律全部复用;频道内容不落盘,运行时经 `ChannelSource` 读 TvProvider(显式投影、`?package=`、按频道取 `preview_program`),纯函数部分(解析、匹配、排序、组装)全部在 JVM 上测。首页把纵向位移从「每行等高」改成「逐行高度累计」(应用行之间逐像素不变,单测先钉),频道行是普通的 `Row`(多 `programs`),焦点账本不新增状态;编辑页的频道架子、「新的一行 → 频道」、选频道页挂在 edit-shelves 计划建好的货架模型与 `OverlayStack` 上。

**Tech Stack:** Kotlin、Jetpack Compose(tv-material 1.0.0,不用任何可滚动容器——`ChannelPicker` 沿用 `AppPicker` 那一处获准的 `LazyColumn`)、`android.media.tv.TvContract`(框架 API,不引入 androidx.tvprovider)、`HttpURLConnection`、`android.util.LruCache`、JUnit 4 + 真 org.json(`testImplementation`)、e2e 用 Python + uiautomator + 自建带代码的夹具 APK(javac + d8 + aapt2)。

**Spec:** `docs/superpowers/specs/2026-10-08-edit-shelves-and-channels-design.md`(§3–§5、§7 为准)+ `docs/superpowers/specs/2026-10-08-channel-rows-design.md`(§2.3、§3、§4、§7、§8 仍有效,冲突以前者为准)。模拟器事实:`docs/research/2026-10-07-tv-channels-recommendations.md` §6;原厂参照:`docs/screenshots/channels/ref/INDEX.md`。

**依赖:** 本计划在 `docs/superpowers/plans/2026-10-08-edit-shelves.md`(编辑桌面货架)**之后**执行。Task 2 改的是 edit-shelves 已经建好的 `appRowCount` / `appendAppRow`(它也删掉了 `addRowBelow`),Task 5 改它重写后的 `EditScreen` 的 `knownOnDisk`,所以**整份计划在 edit-shelves 合入之后才开工**,不并行。Task 13–15 写在那份计划的货架模型上(`sealed interface Shelf { AppShelf(row, …); NewRowShelf }`、`ShelfSpot` / `ShelfLane` / `shelfLanes` / `shelfChips` / `clampSpot` / `landingAfter*`、`enum NewRowChoice` + `choiceFull`、`EditShelfParts.kt` 的 `ShelfChipPill` / `ChoiceCard`、编辑页 `overlay: EditOverlay?` + `OverlayStack`、`AppPicker.kt`),名字已按那份计划的正文逐一核对过;动手前仍 `grep` 一遍,若那份计划落地时又改了名,以落地代码为准。

**仓库根(以下所有命令都在这里跑):** `/Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐`。单测命令一律:

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*<类名>*'
```

## Global Constraints

- 频道行数:`MAX_CHANNEL_ROWS = 5`;应用行仍 `MIN_ROWS = 1` / `MAX_ROWS = 5`,**只数应用行**;首页每个频道行最多 **12** 张卡(`MAX_PROGRAMS = 12`)。
- `layout.json` 频道行逐字是:`{"icon":"tv","apps":[],"channel":{"pkg":…,"key":…,"name":…}}`;`LayoutRow` 加有默认值的字段 `channel: ChannelRef? = null`,现有 `LayoutRow(icon, apps)` 构造与 `copy(apps = …)` 一处不改。
- 频道标识:`key` = 频道的 `internal_provider_id`;应用没写时 `key` 为空串,按 `name`(`display_name`)匹配;都对不上 = 「暂无内容」,**不自动换成别的频道**。
- `Layout.parse`:零行 = 损坏(已有);**没有任何应用行(全是频道行)= 损坏**,回落照旧。
- TvProvider:**一律显式投影**(`projection = null` 撞 BLOB 列抛异常,研究 §6.2);**不用 selection**(第三方带 selection 抛 `SecurityException`,§6.4);频道用 `?package=` 过滤;只要 `type = TYPE_PREVIEW`;**不看 `browsable`**(§6.6–6.7);节目按 `weight` 降序、同权重按 `_id` 升序(插入顺序),最多 12 张。
- **没授权时 TvProvider 不抛异常,返回 0 行**(研究 §6.2):「需要重新授权」以 `checkSelfPermission(READ_TV_LISTINGS)` 为准,`SecurityException` 只作兜底。在系统设置里撤销运行时权限会杀掉本进程(Android 行为),冷启动后按无权限画。
- 刷新:`ContentObserver` 监听 `content://android.media.tv`(含子路径),**500 ms** 去抖后 `channelsRevision++`;只重读频道数据,不重读布局与应用横幅;`onResume` 也 `channelsRevision++`(授权可能在系统设置里被改)。**频道数据只有一份进程级内存缓存 `ChannelCache`**(owner 裁定 2026-10-08,照 `AppsPageCache`),`channelsRevision` 每变一次 MainActivity 刷新它;首页、编辑页、选频道页都只读它,不各自查 TvProvider。
- **首页焦点目标按 `layoutRow` 认行**(owner 裁定 2026-10-08):频道行整行出现 / 消失时,下面那一行的焦点不动;目标不随画出来的行数整表重建。
- 海报:`content://` / `android.resource://` 走 `ContentResolver.openInputStream`;`https://` 走 `HttpURLConnection`,连接与读取超时各 **5 s**;`http://` 与其它 scheme 当作没图;解码到卡片像素(**110 dp × 2 = 220 px 高**,`inSampleSize` 先粗缩);内存 `LruCache` **16 MB**(按 `allocationByteCount`);**不做磁盘缓存**;首页只加载**焦点行 ± 1** 行;没图 / 失败 = 深色底 + 标题文字。
- 首页频道卡:高 **110 dp**,宽按比例 **16:9 → 196、3:2 → 165、4:3 → 147、1:1 → 110、2:3 → 73、3:4 → 83 dp**(电影海报按 2:3);间距 `CARD_GAP` 20、圆角 `CARD_CORNER` 8、左缘 `CONTENT_KEYLINE` 58;焦点视觉沿用应用卡(`gtvFocusFrameOverFade`:放大 + 描边 + 柔光)。
- 行头:卡片上方「应用名 · 频道名」,非焦点行白 60%、焦点行白 100%,不放大;**左边距不画行图标**。字号走 `Type` 七档(CLAUDE.md:界面代码不许写死字号)——spec 的「约 19 sp」取 `Type.section`(17 sp,行高 22);卡下标题 `Type.body`(14 sp,行高 21)、元数据 `Type.caption`(12 sp,行高 16)。
- 焦点行每张卡下两行:标题 + 一行元数据(剧集「第 n 季 · 第 n 集」、电影 / 短片写时长;**不重复「应用 · 频道」**;没有元数据第二行留空);非焦点行不画字但预留同样高度。
- 确定 = `Intent.parseUri(intent_uri, URI_INTENT_SCHEME)` + `FLAG_ACTIVITY_NEW_TASK` 启动;没有 `intent_uri`、解析失败、目标不是发布方自己的包或启动失败 → `Apps.launch(发布方包名)`;再失败 → 现有 Toast `toast_cant_open_app`。**长按频道卡不做任何事**(整下吞掉,不弹菜单、松开也不启动)。
- 频道暂时没节目、频道被删、授权被收回 → **首页不画这一行**(与空应用行同一条过滤,焦点账本里不存在它)。
- 清单加 `<uses-permission android:name="android.permission.READ_TV_LISTINGS" />` 与 `com.android.providers.tv.permission.READ_EPG_DATA`(normal,照研究 §6 探针原样;TvProvider 在权限层按清单 read/writePermission 拦人,只声明前者从没测过);`registerForActivityResult(ActivityResultContracts.RequestPermission())` 在 `MainActivity.onCreate` 注册(本应用第一处运行时权限)。
- 选频道页进页(已授权)时,给声明了 `android.media.tv.action.INITIALIZE_PROGRAMS` 接收器、还没通知过的包发一次**显式**广播,按「包名 + versionCode」记在 `channel-init.json`(走 `LockedFile`)。
- 选频道页文案:页名「添加频道」;说明「UnitedU 会读取电视上各应用提供的频道，只在本机显示，不上传。」;没有频道「暂时没有应用提供频道」+「打开过一次的视频应用才会提供」+「返回」;拒绝授权 → 说明 +「去系统设置开启」。
- 编辑页频道架子:顶部 = 频道图标 +「应用名 · 频道名」+ 小标签「频道」+ 操作胶囊(上移、下移、删除);没内容写「暂无内容」;没授权写「需要重新授权」并多一颗「重新授权」;海报预览高 68.6 dp、**不可聚焦**、最多画到架子右缘。「新的一行」某类满 5 行:选择卡变暗、写「已满 5 行」、确定不响应(仍可聚焦)。频道行删除**不弹确认**。
- 应用卸载(`PACKAGE_FULLY_REMOVED` / 启动缺包清理)→ 删它的频道行,与卸载应用时卡片被移出同一条路、同一把锁。
- 七条焦点铁律(CLAUDE.md「改这份界面前必须知道的七条」)与落盘铁律(`LockedFile`、`store.locked` 内读改写、`layoutWrites`)逐条适用;新文件 `channel-init.json` 也走 `LockedFile`。
- 本轮**不做**:继续观看、焦点自动播预告片、长按频道卡菜单、频道 logo、设置总开关 / 频道管理页、审批 `browsable`、磁盘图片缓存。
- 文案三语同步:`values/`(简体)、`values-en/`、`values-zh-rTW/`(`CopyTest` 钉 key 与占位符一致);`README.md` 与 `README.zh-CN.md` 两份同步改;`docs/` 只写中文。
- 模拟器只用 `unitedu-tv-2`(`-port 5562`,`DEV=emulator-5562`)或 `unitedu-tv-3`(`-port 5564`),开机前 `adb devices` 确认没人在用;**不碰电视、不往电视装包**(1.0 后电视验证一律走正式发布,由 Gordon 决定何时发)。
- 每个提交信息最后一行:`Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`。

## Review Focus

1. **应用更新 / 重装后频道 `_id` 变了**(卸载会清掉 TvProvider 里它的行,研究 §6.8;重装后应用重建频道)→ 行不丢:按 `internal_provider_id`(没有就按 `display_name`)找回新 `_id`。钉在 Task 3 的 `reinstalledChannelWithNewIdStillMatchesByKey`,e2e 在 Task 16 的 REPUBLISH 一步。
2. **首页开着时权限被收回**(系统设置撤销会杀进程后冷启动;「Unused apps」自动收回)→ 首页整行不画、焦点不落进不存在的行、编辑页显示「需要重新授权」、不崩。钉在 Task 7 的 `deniedMeansEveryRowNeedsPermission` 与 Task 12 的 `permissionlessAndEmptyChannelRowsAreDropped`,e2e 在 Task 16 的「撤销授权」一步。
3. **海报 https 超时 / 黑洞地址**→ 5 s 内返回、不卡主线程、一分钟内不重试,卡上画深色底 + 标题。钉在 Task 8 的 `fetchGivesUpAfterTheTimeoutWhenTheServerNeverAnswers` 与 `failedPosterIsNotRetriedWithinAMinute`,e2e 在 Task 16 的「海报超时」一步。
4. **焦点在频道行末张时,应用后台把节目从 8 张删到 3 张**→ 焦点落同一行的最后一张,不跳到行首、不跳行、不丢焦点(铁律 3 / 5)。钉在 Task 12 的 `shrinkingRowClampsTheFocusColumnToItsLastCard`(夹取口径)与 HomeScreen 里「内容真的变了 → 同一次恢复里先冻结目标再换数据」那段(Task 12 Step 6(c)),e2e 在 Task 16 的 SHRINK 一步。
5. **旧版本(1.0.x)重写 layout.json**:频道行被写成 `{"icon":"tv","apps":[]}` 空应用行,应用行数可能超过 5(5 应用行 + 2 频道行 → 7 应用行)→ 新版本读回不崩、不当损坏、只是不能再加应用行,删行照常。钉在 Task 1 的 `oldVersionRewriteBecomesPlainEmptyAppRows` 与 Task 2 的 `legacySevenAppRowsAreReadableButFull`。
6. **(owner 裁定 2026-10-08)焦点在频道行下面的应用行,上面的频道行消失 / 出现**(发布方清空、删频道、撤销授权后冷启动、冷启动节目晚到)→ 焦点留在同一 layout 行的同一张卡。首页目标按 `layoutRow` 认行(`tgtLayoutRow` / `tgtCol`,不随行数整表重建)。钉在 Task 12 的 `channelRowAboveDisappearingKeepsTheSameCard` / `channelRowAppearingAboveKeepsTheSameCard`,e2e 在 Task 16 的「上面的频道行消失 / 出现」一步。
7. **(owner 裁定 2026-10-08)频道数据只有一份进程级缓存** `ChannelCache`(首页、编辑页、选频道页共用,`channelsRevision` 驱动刷新);内容相同的刷新不通知(不重组、不冻结焦点)。钉在 Task 7 的 `ChannelCacheTest`。

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `app/src/main/java/com/uniteduone/launcher/Layout.kt` | 改 | `ChannelRef`、`LayoutRow.channel`、`LayoutRow.isChannel`、读写 `channel` 字段、「至少一行应用行」校验、`withoutPackage` 删频道行 |
| `app/src/main/java/com/uniteduone/launcher/LayoutOps.kt` | 改 | 行数判据(应用行 / 频道行分开:改 edit-shelves 已有的 `appRowCount`,`appendAppRow` 随之生效)、`appendChannelRow`、`appRowIndices`、`deleteRow` / `addToRow` / `setRowIcon` 的频道判据、`dropRemovedElsewhere` / `knownAfterWrite` 认频道行 |
| `app/src/main/java/com/uniteduone/launcher/ChannelModel.kt` | 新 | 纯模型:`TvCols` 列名、`TvChannel`、`Program`、`PosterAspect`、`ChannelContent`、行 → 对象解析、匹配、排序截断、元数据文案、`channelContents` 组装 |
| `app/src/main/java/com/uniteduone/launcher/Model.kt` | 改 | `Row` 加 `channel` / `programs` / `channelAppLabel`;`Row.isChannel`、`Row.cellCount` |
| `app/src/main/java/com/uniteduone/launcher/Move.kt` | 改 | `moveCard` / `moveInLayout` 上下搬运跳过频道行 |
| `app/src/main/java/com/uniteduone/launcher/PrunePure.kt` | 改 | `layoutPackages`(应用 + 频道包)、`withoutPackages` 删频道行 |
| `app/src/main/java/com/uniteduone/launcher/PackagePruning.kt` | 改 | 启动清理把频道包算进去 |
| `app/src/main/java/com/uniteduone/launcher/MainActivity.kt` | 改 | 「加到桌面… → 选一行」只列应用行;`channelsRevision`、授权请求、`ContentObserver`、`LocalChannelEnv`;频道卡长按吞掉 |
| `app/src/main/java/com/uniteduone/launcher/ChannelSource.kt` | 新 | Android 侧:读 TvProvider(显式投影、`?package=`、按频道取节目)、授权判断、给缓存的完整快照 `snapshot` |
| `app/src/main/java/com/uniteduone/launcher/ChannelCache.kt` | 新 | 进程级频道缓存(owner 裁定):`ChannelStore`(串行、去重,JVM 可测)+ `ChannelCache`;首页 / 编辑页 / 选频道页共用 |
| `app/src/main/java/com/uniteduone/launcher/PosterLoader.kt` | 新 | 海报:来源分类、`fetchBytes`(JVM 可测)、采样、`PosterCache`(16 MB LRU + 失败退避) |
| `app/src/main/java/com/uniteduone/launcher/ChannelLaunch.kt` | 新 | 启动节目:`parseUri` + 去掉授权位 + 只许发布方自己的包,失败回落 `Apps.launch` |
| `app/src/main/java/com/uniteduone/launcher/ChannelEnv.kt` | 新 | `ChannelEnv` + `LocalChannelEnv`(版本号、请求授权、去系统设置) |
| `app/src/main/java/com/uniteduone/launcher/HomeVertical.kt` | 新 | 纵向几何:`ChannelRowLayout`、`RowGeom`、`appRowGeom` / `channelRowGeom`、`HomeVertical`(逐行累计 top、焦点线、位移、壁纸逐行压暗) |
| `app/src/main/java/com/uniteduone/launcher/GtvLayout.kt` | 改 | `rowShiftX(focusedIndex, widths, screenWidthDp)` 变宽版 |
| `app/src/main/java/com/uniteduone/launcher/HomeChannels.kt` | 新 | 首页纯逻辑:`withChannelContent`、`homeFocusCol`、`loadsPosters` |
| `app/src/main/java/com/uniteduone/launcher/HomeChannelRow.kt` | 新 | 首页频道行 `ChannelRow` + 海报卡 `PosterCard` |
| `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt` | 改 | 读频道内容(collect `ChannelCache.data`;**不**进 `stale`,内容真的变了才冻结一次,见 Task 12 Step 6(c))、`HomeVertical`、按行种类画 `CategoryRow` / `ChannelRow`、格数改 `cellCount` |
| `app/src/main/java/com/uniteduone/launcher/Apps.kt` | 改 | `Apps.labelOf(ctx, pkg)` |
| `app/src/main/java/com/uniteduone/launcher/GtvTokens.kt` | 改 | `PosterFallback` 底色 |
| `app/src/main/java/com/uniteduone/launcher/EditShelves.kt` | 改(edit-shelves 产出) | `Shelf.ChannelShelf`、`ChannelShelfState`、`shelfChips` / `shelfLanes` / `clampSpot` 的频道分支、`NewRowChoice.CHANNEL` / `choiceMax`、`EditCounts.channels` |
| `app/src/main/java/com/uniteduone/launcher/EditShelfParts.kt` | 改(edit-shelves 产出) | `chipLabel` / `chipIcon` 加 `REAUTHORIZE`;`choiceTitle` / `choiceDesc` / `choiceIcon` 加 `CHANNEL`;`ChannelShelfView` / `ChannelShelfBody`(海报预览要 `clipToBounds`,`EditIronRulesTest` 不许它出现在 `EditScreen.kt`) |
| `app/src/main/java/com/uniteduone/launcher/EditScreen.kt` | 改(edit-shelves 产出) | `shelvesFor`(读 `ChannelCache`)、渲染 `ChannelShelfView`、「频道」选择卡接线、`EditOverlay.ChannelPick`、页头频道数、`knownOnDisk` 初值 |
| `app/src/main/java/com/uniteduone/launcher/AppPicker.kt` | 改(edit-shelves 产出) | `PickerRow` 改 `internal`(选频道页复用) |
| `app/src/main/java/com/uniteduone/launcher/ChannelPicker.kt` | 新 | 选频道页(照 `AppPicker`):授权流程、空态、拒绝态、按 id 重定位 |
| `app/src/main/java/com/uniteduone/launcher/ChannelInit.kt` | 新 | `INITIALIZE_PROGRAMS` 通知 + `channel-init.json`(纯函数可测) |
| `app/src/main/AndroidManifest.xml` | 改 | `READ_TV_LISTINGS` |
| `app/src/main/res/values{,-en,-zh-rTW}/strings.xml` | 改 | 首页元数据、选频道页、频道架子、新的一行文案 |
| `app/src/test/java/com/uniteduone/launcher/ChannelLayoutTest.kt` 等 | 新 | 见各任务 |
| `scripts/e2e/fixtures/channels/**` | 新 | 带代码的夹具发布方 `test.channels`(清单 + 5 个 Java 类) |
| `scripts/e2e/fixtures.py` | 改 | `channels()` 构建夹具 |
| `scripts/e2e/j_channels.py` | 新 | 频道行端到端旅程 |
| `scripts/e2e/run_all.py`、`scripts/e2e/README.md` | 改 | 挂上 `j_channels` |
| `docs/screenshots/channel-rows/` | 新 | 模拟器截图 |
| `CLAUDE.md`、`docs/REVIEW-GUIDE.md`、`README.md`、`README.zh-CN.md`、`docs/WORKLOG.md` | 改 | 焦点表、模拟器坑、文件地图、用户文档、工作记录 |

---

### Task 1: `layout.json` 频道行的读写

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Layout.kt`(`LayoutRow` 定义、`layoutRowFromDisk`、`Layout.parse`、`Layout.toJson`)
- Test: `app/src/test/java/com/uniteduone/launcher/ChannelLayoutTest.kt`

**Interfaces:**
- Consumes: 现有 `rowIconFromDisk(name: String?, icon: String?): String`、`LockedFile.load`(不变)。
- Produces:
  - `data class ChannelRef(val pkg: String, val key: String, val name: String)`
  - `data class LayoutRow(val icon: String, val apps: List<String> = emptyList(), val channel: ChannelRef? = null)`
  - `val LayoutRow.isChannel: Boolean`
  - `internal fun channelRefFromDisk(pkg: String?, key: String?, name: String?): ChannelRef?`
  - `internal fun layoutRowFromDisk(name: String?, icon: String?, apps: List<String>, channel: ChannelRef? = null): LayoutRow`
  - `Layout.parse` 在没有应用行时抛;`Layout.toJson` 对频道行多写 `"channel"`。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/ChannelLayoutTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** R164:layout.json 里的频道行(spec §3.3)。用真 org.json(testImplementation),走的就是 Layout.read / write 的 parse / toJson。 */
class ChannelLayoutTest {
    private val kumiao = ChannelRef("com.cibn.tv", "kumiao_android_tv_provider|2019.1125.1657", "酷喵推荐")

    @Test fun channelRowRoundTrips() {
        val rows = listOf(
            LayoutRow("movie", listOf("com.a", "com.b")),
            LayoutRow("tv", channel = kumiao),
            LayoutRow("music", emptyList()),
        )
        assertEquals(rows, Layout.parse(Layout.toJson(rows)))
    }

    @Test fun channelRowIsWrittenExactlyAsSpecified() {
        val json = JSONObject(Layout.toJson(listOf(LayoutRow("movie", listOf("com.a")), LayoutRow("tv", channel = kumiao))))
        val row = json.getJSONArray("rows").getJSONObject(1)
        assertEquals("tv", row.getString("icon"))
        assertEquals(0, row.getJSONArray("apps").length())
        val ch = row.getJSONObject("channel")
        assertEquals("com.cibn.tv", ch.getString("pkg"))
        assertEquals("kumiao_android_tv_provider|2019.1125.1657", ch.getString("key"))
        assertEquals("酷喵推荐", ch.getString("name"))
        assertFalse("应用行不写 channel", json.getJSONArray("rows").getJSONObject(0).has("channel"))
    }

    @Test fun filesWithoutChannelFieldReadAsAppRows() {
        val read = Layout.parse("""{"rows":[{"icon":"movie","apps":["com.a"]},{"icon":"tv","apps":[]}]}""")
        assertTrue(read.none { it.isChannel })
        assertEquals(listOf(listOf("com.a"), emptyList()), read.map { it.apps })
    }

    /** Review Focus 5:旧版本(不认 channel)重写后,频道行成了空应用行;应用行可以超过 5 行,读回不当损坏。 */
    @Test fun oldVersionRewriteBecomesPlainEmptyAppRows() {
        val rewritten = """{"rows":[
            {"icon":"movie","apps":["a"]},{"icon":"tv","apps":["b"]},{"icon":"music","apps":["c"]},
            {"icon":"apps","apps":["d"]},{"icon":"apps","apps":["e"]},{"icon":"tv","apps":[]},{"icon":"tv","apps":[]}
        ]}"""
        val read = Layout.parse(rewritten)
        assertEquals(7, read.size)
        assertTrue(read.none { it.isChannel })
    }

    @Test fun allChannelRowsIsCorrupt() {
        val text = Layout.toJson(listOf(LayoutRow("tv", channel = kumiao)))
        assertThrows(IllegalStateException::class.java) { Layout.parse(text) }
    }

    @Test fun zeroRowsIsStillCorrupt() {
        assertThrows(IllegalStateException::class.java) { Layout.parse("""{"rows":[]}""") }
    }

    @Test fun incompleteChannelFieldFallsBackToAnAppRow() {
        val read = Layout.parse(
            """{"rows":[{"icon":"movie","apps":["a"]},
               {"icon":"tv","apps":[],"channel":{"pkg":"","key":"k","name":"x"}},
               {"icon":"tv","apps":[],"channel":{"pkg":"p"}},
               {"icon":"tv","apps":[],"channel":"oops"}]}""",
        )
        assertEquals(listOf(false, false, false, false), read.map { it.isChannel })
    }

    @Test fun missingKeyDefaultsToEmptyAndAppsOfAChannelRowAreIgnored() {
        val read = Layout.parse(
            """{"rows":[{"icon":"movie","apps":["a"]},{"icon":"tv","apps":["stray"],"channel":{"pkg":"p","name":" 推荐 "}}]}""",
        )
        assertEquals(ChannelRef("p", "", "推荐"), read[1].channel)
        assertEquals(emptyList<String>(), read[1].apps)
    }

    @Test fun channelRefFromDiskTrimsAndRejectsBlanks() {
        assertEquals(ChannelRef("p", "k", "n"), channelRefFromDisk(" p ", " k ", " n "))
        assertNull(channelRefFromDisk("p", "k", "  "))
        assertNull(channelRefFromDisk(null, "k", "n"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelLayoutTest*'`
Expected: 编译失败,`Unresolved reference: ChannelRef` / `isChannel` / `channelRefFromDisk`。

- [ ] **Step 3: 实现**

`Layout.kt` 里把 `data class LayoutRow(...)` 那一行换成:

```kotlin
/**
 * R164:频道行指向的频道。[pkg] = 发布它的应用;[key] = 频道的 `internal_provider_id`(应用自己定的、跨重装稳定的 id;
 * 应用没写时为空串,按 [name] 认);[name] = 频道的 `display_name`(加行那一刻的,只用来匹配与在编辑页显示)。
 */
data class ChannelRef(val pkg: String, val key: String, val name: String)

/**
 * layout.json 的一行。**R163 起没有名字**:行只靠图标认。[icon] 必为 [ROW_ICON_IDS] 里的合法 id;[apps] 是有序的包名。
 * **R164**:[channel] 非 null = 频道行([apps] 恒空,[icon] 恒写 `tv`,只为旧版本回落成一个空应用行用,界面不画)。
 */
data class LayoutRow(val icon: String, val apps: List<String> = emptyList(), val channel: ChannelRef? = null)

/** R164:这一行是不是频道行。 */
val LayoutRow.isChannel: Boolean get() = channel != null

/**
 * 读盘:`channel` 对象的三个字段 → [ChannelRef]。去首尾空白;`pkg` 或 `name` 为空 → null(这一行按普通应用行读,
 * 与旧版本读到它时的样子相同);`key` 缺省为空串。
 */
internal fun channelRefFromDisk(pkg: String?, key: String?, name: String?): ChannelRef? {
    val p = pkg?.trim().orEmpty()
    val n = name?.trim().orEmpty()
    if (p.isEmpty() || n.isEmpty()) return null
    return ChannelRef(p, key?.trim().orEmpty(), n)
}
```

`layoutRowFromDisk` 换成:

```kotlin
internal fun layoutRowFromDisk(name: String?, icon: String?, apps: List<String>, channel: ChannelRef? = null): LayoutRow =
    if (channel != null) {
        // 频道行不收应用(spec §3.3:频道行 = 带 channel 字段的空应用行)
        LayoutRow(icon = rowIconFromDisk(name, icon), apps = emptyList(), channel = channel)
    } else {
        LayoutRow(
            icon = rowIconFromDisk(name, icon),
            apps = apps.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }
```

`Layout.parse` 的 `return (0 until rows.length()).map { … }` 换成:

```kotlin
        val parsed = (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            val apps = r.getJSONArray("apps")
            // R164:channel 不是对象(旧版本不会写它;手改坏了)或字段不全 → 当普通应用行读
            val ch = r.optJSONObject("channel")
            layoutRowFromDisk(
                // R163:新文件没有 name(缺了不抛);老文件有,只用来给没存合法 icon 的行回落图标
                name = if (r.has("name")) r.optString("name", "") else null,
                icon = if (r.has("icon")) r.optString("icon", "") else null,
                apps = (0 until apps.length()).map { apps.getString(it) },
                channel = ch?.let { channelRefFromDisk(it.optString("pkg", ""), it.optString("key", ""), it.optString("name", "")) },
            )
        }
        // R164:全是频道行 = 一个「添加应用」入口都没有,同「零行」一样是功能性死胡同,当损坏回落(spec §3.3)
        if (parsed.none { !it.isChannel }) error("layout.json 里没有应用行")
        return parsed
```

`Layout.toJson` 的循环换成:

```kotlin
        rows.forEach { row ->
            val o = JSONObject().put("icon", row.icon).put("apps", JSONArray(row.apps))
            row.channel?.let { o.put("channel", JSONObject().put("pkg", it.pkg).put("key", it.key).put("name", it.name)) }
            arr.put(o)
        }
```

同时把 `parse` 的 KDoc 末尾补一句:「R164:没有应用行(全是频道行)也抛。」

- [ ] **Step 4: 跑测试确认通过,并跑全部旧的 Layout 测试**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelLayoutTest*' --tests '*LayoutTest*' --tests '*OnboardingPureTest*'`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/Layout.kt app/src/test/java/com/uniteduone/launcher/ChannelLayoutTest.kt && git commit -F - <<'EOF'
feat(channels): layout.json 频道行读写与「至少一行应用行」校验(R164)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 2: 行数判据(应用行 / 频道行分开)

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/LayoutOps.kt`(顶部常量、edit-shelves 加的 `appRowCount` 函数体、`deleteRow`、`setRowIcon`、`addToRow`;新增函数。`addRowBelow` 已被 edit-shelves 删掉,不碰)
- Test: `app/src/test/java/com/uniteduone/launcher/ChannelRowRulesTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ChannelRef`、`LayoutRow.channel`、`LayoutRow.isChannel`;现有 `NEW_ROW_ICON`、`isRowIconId`、`MIN_ROWS`、`MAX_ROWS`。
- Produces:
  - `internal const val MAX_CHANNEL_ROWS = 5`、`internal const val CHANNEL_ROW_ICON = "tv"`
  - `appRowCount(rows: List<LayoutRow>): Int`(edit-shelves Task 3 已有,原来 `= rows.size`)改成只数应用行;同一处的 `appendAppRow`(`appRowCount(rows) >= MAX_ROWS` 就原样返回)**不改一个字**,判据随 `appRowCount` 生效——不另起同义函数
  - 新增 `internal fun channelRowCount(rows: List<LayoutRow>): Int`
  - 新增 `internal fun canAddAppRow(rows: List<LayoutRow>): Boolean`、`internal fun canAddChannelRow(rows: List<LayoutRow>): Boolean`
  - 新增 `internal fun appendChannelRow(rows: List<LayoutRow>, ref: ChannelRef): List<LayoutRow>`(满了 / 重复 → 同一个 list)
  - 新增 `internal fun appRowIndices(rows: List<LayoutRow>): List<Int>`
  - `deleteRow` / `addToRow` / `setRowIcon` 签名不变,判据改。(`addRowBelow` 已随 edit-shelves 删除,R165 §2.4 新行一律追加在最后。)

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/ChannelRowRulesTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R164 / R165 spec §4:应用行 1–5 行(只数应用行),频道行 0–5 行,各自判。 */
class ChannelRowRulesTest {
    private fun app(vararg pkgs: String) = LayoutRow("movie", pkgs.toList())
    private fun ch(n: Int) = LayoutRow(CHANNEL_ROW_ICON, channel = ChannelRef("p$n", "k$n", "c$n"))

    @Test fun countsAreSeparate() {
        val rows = listOf(app("a"), ch(1), app(), ch(2), ch(3))
        assertEquals(2, appRowCount(rows))
        assertEquals(3, channelRowCount(rows))
    }

    @Test fun channelRowIconIsALegalRowIcon() {
        assertTrue(isRowIconId(CHANNEL_ROW_ICON))
    }

    @Test fun appRowsFillToFiveRegardlessOfChannelRows() {
        val fourApps = List(4) { app() } + List(5) { ch(it) }
        val added = appendAppRow(fourApps)
        assertEquals(10, added.size)
        assertEquals(LayoutRow(NEW_ROW_ICON), added.last())
        val fiveApps = List(5) { app() } + List(2) { ch(it) }
        assertSame(fiveApps, appendAppRow(fiveApps))
    }

    @Test fun channelRowsFillToFiveAndAreAppendedLast() {
        val rows = listOf(app("a"), ch(1))
        val ref = ChannelRef("com.cibn.tv", "k", "酷喵推荐")
        val added = appendChannelRow(rows, ref)
        assertEquals(LayoutRow(CHANNEL_ROW_ICON, emptyList(), ref), added.last())
        val full = listOf(app("a")) + List(MAX_CHANNEL_ROWS) { ch(it) }
        assertFalse(canAddChannelRow(full))
        assertSame(full, appendChannelRow(full, ref))
    }

    @Test fun theSameChannelIsNotAddedTwice() {
        val ref = ChannelRef("p1", "k1", "c1")
        val rows = listOf(app("a"), ch(1))
        assertSame(rows, appendChannelRow(rows, ref))
    }

    @Test fun lastAppRowCannotBeDeletedButChannelRowsAlwaysCan() {
        val rows = listOf(app("a"), ch(1), ch(2))
        assertSame(rows, deleteRow(rows, 0))
        assertEquals(listOf(app("a"), ch(2)), deleteRow(rows, 1))
    }

    @Test fun addToRowRefusesAChannelRow() {
        val rows = listOf(app("a"), ch(1))
        assertSame(rows, addToRow(rows, 1, "com.x"))
        assertEquals(listOf("a", "com.x"), addToRow(rows, 0, "com.x")[0].apps)
    }

    @Test fun setRowIconLeavesChannelRowsAlone() {
        val rows = listOf(app("a"), ch(1))
        assertSame(rows, setRowIcon(rows, 1, "music"))
    }

    @Test fun appRowIndicesSkipChannelRows() {
        assertEquals(listOf(0, 2), appRowIndices(listOf(app("a"), ch(1), app("b"), ch(2))))
    }

    /** Review Focus 5:旧版本写出 7 个应用行。读得回、不能再加应用行、删行照常。 */
    @Test fun legacySevenAppRowsAreReadableButFull() {
        val rows = List(7) { app("p$it") }
        assertFalse(canAddAppRow(rows))
        assertSame(rows, appendAppRow(rows))
        assertEquals(6, deleteRow(rows, 3).size)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelRowRulesTest*'`
Expected: 编译失败,`Unresolved reference: channelRowCount` / `CHANNEL_ROW_ICON` / `appendChannelRow` 等(`appRowCount` / `appendAppRow` 已由 edit-shelves 提供)。

- [ ] **Step 3: 实现**

`LayoutOps.kt`:edit-shelves 加的 `appRowCount` 整个换成(KDoc 里「计划 2 加频道行后改成只数 `channel == null` 的行」那句随之改成现状):

```kotlin
/** 应用行数(R164 起 [MIN_ROWS] / [MAX_ROWS] 只数它;「新的一行 → 应用行」([appendAppRow])与删除胶囊的判据都经这里)。 */
internal fun appRowCount(rows: List<LayoutRow>): Int = rows.count { !it.isChannel }
```

顶部常量下面加:

```kotlin
/** R164:频道行上限(spec §4:频道行 0–5 行,与应用行分开数)。 */
internal const val MAX_CHANNEL_ROWS = 5

/** 频道行写盘的图标(只给旧版本回落成空应用行用,界面不画)。 */
internal const val CHANNEL_ROW_ICON = "tv"

/** 频道行数(上限 [MAX_CHANNEL_ROWS])。 */
internal fun channelRowCount(rows: List<LayoutRow>): Int = rows.count { it.isChannel }

internal fun canAddAppRow(rows: List<LayoutRow>): Boolean = appRowCount(rows) < MAX_ROWS

internal fun canAddChannelRow(rows: List<LayoutRow>): Boolean = channelRowCount(rows) < MAX_CHANNEL_ROWS

/** R164:在最后追加一个频道行;频道行已满,或这个频道已经在桌面上 → 同一个 list。 */
internal fun appendChannelRow(rows: List<LayoutRow>, ref: ChannelRef): List<LayoutRow> =
    if (!canAddChannelRow(rows) || rows.any { it.channel == ref }) rows
    else rows + LayoutRow(icon = CHANNEL_ROW_ICON, channel = ref)

/** 应用行在 layout.json 里的下标(「加到桌面… → 选一行」只列这些,spec §5)。 */
internal fun appRowIndices(rows: List<LayoutRow>): List<Int> = rows.indices.filter { !rows[it].isChannel }
```

`deleteRow` 整个换成:

```kotlin
/**
 * 删第 [index] 行;越界 → 原样返回。**R164**:频道行永远能删;应用行只剩 [MIN_ROWS] 行时不删
 * (只数应用行——频道行不能代替「至少一个添加应用的入口」)。行里的应用只是离开桌面,不卸载。
 */
internal fun deleteRow(rows: List<LayoutRow>, index: Int): List<LayoutRow> {
    val row = rows.getOrNull(index) ?: return rows
    if (!row.isChannel && appRowCount(rows) <= MIN_ROWS) return rows
    return rows.filterIndexed { i, _ -> i != index }
}
```

`setRowIcon` 的守卫换成:

```kotlin
    if (!isRowIconId(icon) || index !in rows.indices || rows[index].isChannel) return rows
```

`addToRow` 的前两行换成:

```kotlin
    val row = rows.getOrNull(index) ?: return rows
    if (row.isChannel || pkg in row.apps) return rows   // R164:频道行不收应用
```

- [ ] **Step 4: 跑测试确认通过(含旧的 LayoutOps 测试)**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelRowRulesTest*' --tests '*LayoutOps*'`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/LayoutOps.kt app/src/test/java/com/uniteduone/launcher/ChannelRowRulesTest.kt && git commit -F - <<'EOF'
feat(channels): 行数判据按应用行 / 频道行分开数(R164 §4)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 3: 频道纯模型(解析、匹配、排序、元数据)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/ChannelModel.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/ChannelModelTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ChannelRef`。
- Produces(全部纯 Kotlin,不碰 `android.*`):
  - `internal object TvCols`(列名常量 + `CHANNEL_PROJECTION: Array<String>`、`PROGRAM_PROJECTION: Array<String>`)
  - `internal const val CHANNEL_TYPE_PREVIEW = "TYPE_PREVIEW"`、`internal const val MAX_PROGRAMS = 12`
  - `data class TvChannel(val id: Long, val pkg: String, val type: String, val name: String, val internalId: String?)`
  - `enum class PosterAspect(val widthDp: Float) { R16_9, R3_2, R4_3, R1_1, R2_3, R3_4 }`
  - `data class Program(val id: Long, val title: String, val episodeTitle: String?, val season: String?, val episode: String?, val durationMs: Long, val posterUri: String?, val aspect: PosterAspect, val intentUri: String?, val weight: Int)`
  - `sealed interface ChannelContent { data object NeedsPermission; data object Missing; data class Ready(val programs: List<Program>) }`
  - `internal fun posterAspectOf(code: Long?): PosterAspect`
  - `internal fun channelFromRow(m: Map<String, Any?>): TvChannel?`、`internal fun programFromRow(m: Map<String, Any?>): Program?`
  - `internal fun refFor(c: TvChannel): ChannelRef`、`internal fun matchChannel(ref: ChannelRef, channels: List<TvChannel>): TvChannel?`
  - `internal fun topPrograms(programs: List<Program>): List<Program>`
  - `internal data class MetaFormats(val seasonEpisode: String, val episode: String, val hoursMinutes: String, val minutes: String)`、`internal fun programSubtitle(p: Program, f: MetaFormats): String?`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/ChannelModelTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R164:TvProvider 行 → 模型、频道匹配(spec §3.3 标识)、节目排序截断、元数据文案。全部纯 JVM,行用 Map 模拟游标。 */
class ChannelModelTest {
    private fun chRow(id: Long, pkg: String = "com.cibn.tv", type: String = CHANNEL_TYPE_PREVIEW, name: String = "酷喵推荐", internal: String? = "k1") =
        mapOf<String, Any?>(TvCols.ID to id, TvCols.PACKAGE to pkg, TvCols.TYPE to type, TvCols.DISPLAY_NAME to name, TvCols.INTERNAL_ID to internal)

    private fun prog(id: Long, weight: Int = 0, title: String = "t$id") =
        Program(id, title, null, null, null, 0L, null, PosterAspect.R16_9, null, weight)

    @Test fun channelRowsParse() {
        assertEquals(TvChannel(7, "com.cibn.tv", CHANNEL_TYPE_PREVIEW, "酷喵推荐", "k1"), channelFromRow(chRow(7)))
        assertNull("没有 _id 的行丢掉", channelFromRow(chRow(7) - TvCols.ID))
        assertNull("没有包名的行丢掉", channelFromRow(chRow(7, pkg = " ")))
        assertNull("空的 internal_provider_id 当没写", channelFromRow(chRow(7, internal = "  "))!!.internalId)
    }

    @Test fun programRowsParseWithThumbnailFallback() {
        val p = programFromRow(
            mapOf(
                TvCols.ID to 3L, TvCols.TITLE to " 海底 ", TvCols.SEASON to "2", TvCols.EPISODE to 5L,
                TvCols.DURATION to 6_300_000L, TvCols.THUMB to "content://x/1", TvCols.THUMB_ASPECT to 4L,
                TvCols.INTENT to "intent:#Intent;end", TvCols.WEIGHT to 9L,
            ),
        )!!
        assertEquals("海底", p.title)
        assertEquals("2", p.season)
        assertEquals("5", p.episode)
        assertEquals("content://x/1", p.posterUri)
        assertEquals(PosterAspect.R2_3, p.aspect)
        assertEquals(9, p.weight)
        assertNull(programFromRow(mapOf(TvCols.TITLE to "no id")))
    }

    @Test fun posterArtWinsOverThumbnailTogetherWithItsOwnAspect() {
        val p = programFromRow(
            mapOf(TvCols.ID to 1L, TvCols.POSTER to "https://a/p.jpg", TvCols.POSTER_ASPECT to 3L, TvCols.THUMB to "content://t", TvCols.THUMB_ASPECT to 0L),
        )!!
        assertEquals("https://a/p.jpg", p.posterUri)
        assertEquals(PosterAspect.R1_1, p.aspect)
    }

    @Test fun aspectCodesAndWidths() {
        assertEquals(
            listOf(PosterAspect.R16_9, PosterAspect.R3_2, PosterAspect.R4_3, PosterAspect.R1_1, PosterAspect.R2_3, PosterAspect.R2_3, PosterAspect.R3_4, PosterAspect.R16_9, PosterAspect.R16_9),
            listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L, 99L, null).map { posterAspectOf(it) },
        )
        // spec §4:110 dp 高时的卡宽
        assertEquals(listOf(196f, 165f, 147f, 110f, 73f, 83f), PosterAspect.values().map { it.widthDp })
    }

    @Test fun matchesByInternalProviderId() {
        val chans = listOf(channelFromRow(chRow(1, internal = "other"))!!, channelFromRow(chRow(2))!!)
        assertEquals(2L, matchChannel(ChannelRef("com.cibn.tv", "k1", "旧名字"), chans)!!.id)
    }

    /** Review Focus 1:重装 / 更新后应用重建了频道,_id 变了,key 不变 → 照样找回。 */
    @Test fun reinstalledChannelWithNewIdStillMatchesByKey() {
        val ref = refFor(channelFromRow(chRow(4))!!)
        val afterReinstall = listOf(channelFromRow(chRow(31))!!)
        assertEquals(31L, matchChannel(ref, afterReinstall)!!.id)
    }

    @Test fun withoutKeyMatchesByNameOnly() {
        val chans = listOf(channelFromRow(chRow(5, internal = null, name = "热播"))!!)
        assertEquals(5L, matchChannel(ChannelRef("com.cibn.tv", "", "热播"), chans)!!.id)
        assertNull(matchChannel(ChannelRef("com.cibn.tv", "", "别的"), chans))
    }

    @Test fun keyMismatchDoesNotFallBackToAnotherChannel() {
        val chans = listOf(channelFromRow(chRow(5, internal = "new-key", name = "酷喵推荐"))!!)
        assertNull("key 对不上就是「暂无内容」,不按名字换成别的频道", matchChannel(ChannelRef("com.cibn.tv", "k1", "酷喵推荐"), chans))
    }

    @Test fun onlyPreviewChannelsOfThatPackageMatch() {
        val chans = listOf(
            channelFromRow(chRow(1, type = "TYPE_OTHER"))!!,
            channelFromRow(chRow(2, pkg = "com.other"))!!,
        )
        assertNull(matchChannel(ChannelRef("com.cibn.tv", "k1", "酷喵推荐"), chans))
    }

    @Test fun refForUsesInternalIdOrEmpty() {
        assertEquals(ChannelRef("com.cibn.tv", "k1", "酷喵推荐"), refFor(channelFromRow(chRow(1))!!))
        assertEquals("", refFor(channelFromRow(chRow(1, internal = null))!!).key)
    }

    @Test fun programsSortByWeightThenInsertionAndCapAtTwelve() {
        val ps = (1L..20L).map { prog(it, weight = if (it % 2 == 0L) 5 else 1) }.shuffled(java.util.Random(7))
        val top = topPrograms(ps)
        assertEquals(MAX_PROGRAMS, top.size)
        assertEquals(listOf(2L, 4L, 6L, 8L, 10L, 12L, 14L, 16L, 18L, 20L, 1L, 3L), top.map { it.id })
    }

    @Test fun subtitles() {
        val f = MetaFormats("第 %1\$s 季 · 第 %2\$s 集", "第 %1\$s 集", "%1\$d 小时 %2\$d 分钟", "%1\$d 分钟")
        assertEquals("第 2 季 · 第 5 集", programSubtitle(prog(1).copy(season = "2", episode = "5"), f))
        assertEquals("第 5 集", programSubtitle(prog(1).copy(episode = "5"), f))
        assertEquals("1 小时 45 分钟", programSubtitle(prog(1).copy(durationMs = 6_300_000), f))
        assertEquals("42 分钟", programSubtitle(prog(1).copy(durationMs = 42 * 60_000L + 59_000), f))
        assertNull("不到一分钟、没有季集 → 第二行留空", programSubtitle(prog(1).copy(durationMs = 30_000), f))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelModelTest*'`
Expected: 编译失败,`Unresolved reference: TvCols` 等。

- [ ] **Step 3: 实现**

`app/src/main/java/com/uniteduone/launcher/ChannelModel.kt`:

```kotlin
package com.uniteduone.launcher

/**
 * R164 频道行的纯模型:TvProvider 两张表(`channel`、`preview_program`)的列名、行 → 对象、频道匹配、节目排序截断、
 * 卡下第二行的文案。**一行 android.* 都不碰**——读游标的那一半在 ChannelSource.kt,这里全部 JVM 单测(ChannelModelTest)。
 * 依据:spec `2026-10-08-channel-rows-design.md` §3、研究 `2026-10-07-tv-channels-recommendations.md` §6。
 */

/**
 * 我们读的列名。与 `android.media.tv.TvContract` 的常量**逐字相同**(`Channels._ID` = "_id"、
 * `Channels.COLUMN_PACKAGE_NAME` = "package_name"、`PreviewPrograms.COLUMN_POSTER_ART_URI` = "poster_art_uri" …),
 * 写成字面量是为了让纯函数与单测不依赖 android.jar。**投影一律显式**:`projection = null` 会撞 BLOB 列抛
 * `SQLiteException`(研究 §6.2)。
 */
internal object TvCols {
    const val ID = "_id"
    const val PACKAGE = "package_name"
    const val TYPE = "type"
    const val DISPLAY_NAME = "display_name"
    const val INTERNAL_ID = "internal_provider_id"
    const val TITLE = "title"
    const val EPISODE_TITLE = "episode_title"
    const val SEASON = "season_display_number"
    const val EPISODE = "episode_display_number"
    const val DURATION = "duration_millis"
    const val POSTER = "poster_art_uri"
    const val POSTER_ASPECT = "poster_art_aspect_ratio"
    const val THUMB = "thumbnail_uri"
    const val THUMB_ASPECT = "thumbnail_aspect_ratio"
    const val INTENT = "intent_uri"
    const val WEIGHT = "weight"

    val CHANNEL_PROJECTION = arrayOf(ID, PACKAGE, TYPE, DISPLAY_NAME, INTERNAL_ID)
    val PROGRAM_PROJECTION = arrayOf(ID, TITLE, EPISODE_TITLE, SEASON, EPISODE, DURATION, POSTER, POSTER_ASPECT, THUMB, THUMB_ASPECT, INTENT, WEIGHT)
}

/** `TvContract.Channels.TYPE_PREVIEW`:只认这一种(spec §3.2)。 */
internal const val CHANNEL_TYPE_PREVIEW = "TYPE_PREVIEW"

/** 首页每个频道行最多几张卡(spec §4)。 */
internal const val MAX_PROGRAMS = 12

/** TvProvider `channel` 表的一行(只取我们用的列)。[internalId] 空白时为 null。 */
data class TvChannel(val id: Long, val pkg: String, val type: String, val name: String, val internalId: String?)

/**
 * 海报比例与它在 110 dp 卡高下的卡宽(dp,spec §4:16:9 → 196、3:2 → 165、4:3 → 147、1:1 → 110、2:3 → 73、3:4 → 83)。
 * TvProvider 的 `MOVIE_POSTER`(1 : 1.441)按 2:3 画(spec「电影海报 2:3 同」)。
 */
enum class PosterAspect(val widthDp: Float) {
    R16_9(196f), R3_2(165f), R4_3(147f), R1_1(110f), R2_3(73f), R3_4(83f),
}

/**
 * `poster_art_aspect_ratio` / `thumbnail_aspect_ratio` 的整数码 → [PosterAspect]。码值即 `TvContract.PreviewPrograms` 的
 * `ASPECT_RATIO_16_9` = 0、`3_2` = 1、`4_3` = 2、`1_1` = 3、`2_3` = 4、`MOVIE_POSTER` = 5、`3_4` = 6;缺省 / 不认识 → 16:9。
 */
internal fun posterAspectOf(code: Long?): PosterAspect = when (code?.toInt()) {
    0 -> PosterAspect.R16_9
    1 -> PosterAspect.R3_2
    2 -> PosterAspect.R4_3
    3 -> PosterAspect.R1_1
    4, 5 -> PosterAspect.R2_3
    6 -> PosterAspect.R3_4
    else -> PosterAspect.R16_9
}

/** 预览节目(首页一张卡)。[posterUri] = `poster_art_uri`,没有就用 `thumbnail_uri`;[aspect] 跟着取的那一个走。 */
data class Program(
    val id: Long,
    val title: String,
    val episodeTitle: String?,
    val season: String?,
    val episode: String?,
    val durationMs: Long,
    val posterUri: String?,
    val aspect: PosterAspect,
    val intentUri: String?,
    val weight: Int,
)

/** 一个频道行此刻的内容。首页只画 [Ready];编辑页三种都画(海报 / 「暂无内容」/「需要重新授权」)。 */
sealed interface ChannelContent {
    data object NeedsPermission : ChannelContent
    /** 频道找不到(被删、key 对不上)或一个节目都没有。 */
    data object Missing : ChannelContent
    /** [programs] 已排序截断、非空。 */
    data class Ready(val programs: List<Program>) : ChannelContent
}

private fun Map<String, Any?>.text(k: String): String? = when (val v = this[k]) {
    is String -> v.trim().takeIf { it.isNotEmpty() }
    is Number -> v.toLong().toString()
    else -> null
}

private fun Map<String, Any?>.num(k: String): Long? = when (val v = this[k]) {
    is Number -> v.toLong()
    is String -> v.trim().toLongOrNull()
    else -> null
}

/** 游标一行(列名 → 值)→ [TvChannel];没有 `_id` 或包名 → null。 */
internal fun channelFromRow(m: Map<String, Any?>): TvChannel? {
    val id = m.num(TvCols.ID) ?: return null
    val pkg = m.text(TvCols.PACKAGE) ?: return null
    return TvChannel(id, pkg, m.text(TvCols.TYPE).orEmpty(), m.text(TvCols.DISPLAY_NAME).orEmpty(), m.text(TvCols.INTERNAL_ID))
}

/** 游标一行 → [Program];没有 `_id` → null。 */
internal fun programFromRow(m: Map<String, Any?>): Program? {
    val id = m.num(TvCols.ID) ?: return null
    val poster = m.text(TvCols.POSTER)
    return Program(
        id = id,
        title = m.text(TvCols.TITLE).orEmpty(),
        episodeTitle = m.text(TvCols.EPISODE_TITLE),
        season = m.text(TvCols.SEASON),
        episode = m.text(TvCols.EPISODE),
        durationMs = m.num(TvCols.DURATION) ?: 0L,
        posterUri = poster ?: m.text(TvCols.THUMB),
        aspect = posterAspectOf(if (poster != null) m.num(TvCols.POSTER_ASPECT) else m.num(TvCols.THUMB_ASPECT)),
        intentUri = m.text(TvCols.INTENT),
        weight = (m.num(TvCols.WEIGHT) ?: 0L).toInt(),
    )
}

/** 加行时写进 layout.json 的标识(spec §3.3):key = `internal_provider_id`,没有就空串(按名字认)。 */
internal fun refFor(c: TvChannel): ChannelRef = ChannelRef(c.pkg, c.internalId.orEmpty(), c.name)

/**
 * 在 [channels] 里找 [ref] 指的那个频道:同包、`TYPE_PREVIEW`;key 非空按 `internal_provider_id` 认,空则按 `display_name` 认;
 * 多个命中取 `_id` 最小的。**都对不上返回 null**——不换成同包的别的频道(spec §3.3)。
 */
internal fun matchChannel(ref: ChannelRef, channels: List<TvChannel>): TvChannel? {
    val mine = channels.filter { it.pkg == ref.pkg && it.type == CHANNEL_TYPE_PREVIEW }.sortedBy { it.id }
    return if (ref.key.isNotEmpty()) mine.firstOrNull { it.internalId == ref.key }
    else mine.firstOrNull { it.name == ref.name }
}

/** `weight` 降序、同权重按 `_id` 升序(= 插入顺序),最多 [MAX_PROGRAMS] 张(spec §2.3)。 */
internal fun topPrograms(programs: List<Program>): List<Program> =
    programs.sortedWith(compareByDescending<Program> { it.weight }.thenBy { it.id }).take(MAX_PROGRAMS)

/** 卡下第二行的四种格式(调用方按界面语言从资源取)。 */
internal data class MetaFormats(val seasonEpisode: String, val episode: String, val hoursMinutes: String, val minutes: String)

/**
 * 焦点行卡下第二行(spec §4):剧集「第 n 季 · 第 n 集」,只有集号「第 n 集」,否则按时长(≥ 1 分钟)写「n 小时 n 分钟 / n 分钟」;
 * 都没有 → null(第二行留空)。**不重复「应用 · 频道」**(行头已经写了)。
 */
internal fun programSubtitle(p: Program, f: MetaFormats): String? = when {
    p.season != null && p.episode != null -> String.format(f.seasonEpisode, p.season, p.episode)
    p.episode != null -> String.format(f.episode, p.episode)
    p.durationMs >= 60_000L -> {
        val total = (p.durationMs / 60_000L).toInt()
        if (total >= 60) String.format(f.hoursMinutes, total / 60, total % 60) else String.format(f.minutes, total)
    }
    else -> null
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelModelTest*'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/ChannelModel.kt app/src/test/java/com/uniteduone/launcher/ChannelModelTest.kt && git commit -F - <<'EOF'
feat(channels): 频道纯模型——列名、解析、按 key / 名字匹配、排序截断、元数据(R164)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 4: `Row` 认频道 + 搬运跳过频道行

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Model.kt`(`Row`)
- Modify: `app/src/main/java/com/uniteduone/launcher/Move.kt`(`moveCard`、`moveInLayout` 的 UP/DOWN 分支)
- Test: `app/src/test/java/com/uniteduone/launcher/MoveChannelTest.kt`

**Interfaces:**
- Consumes: Task 1 `ChannelRef`、`LayoutRow.isChannel`;Task 3 `Program`。
- Produces:
  - `data class Row(val apps: List<AppEntry>, val icon: String = NEW_ROW_ICON, val layoutRow: Int = -1, val channel: ChannelRef? = null, val programs: List<Program> = emptyList(), val channelAppLabel: String = "")`
  - `val Row.isChannel: Boolean`、`val Row.cellCount: Int`
  - `moveCard` / `moveInLayout` 签名不变;上下搬运落到那个方向上第一个**应用行**。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/MoveChannelTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** R164 spec §5:搬运(首页移动态 / 编辑页拿起)上下跳过频道行;前后都没有应用行就不动。 */
class MoveChannelTest {
    private val ref = ChannelRef("com.cibn.tv", "k", "酷喵推荐")
    private fun app(p: String) = AppEntry(packageName = p, label = p, card = null, isWide = false)
    private fun appRow(layoutRow: Int, vararg pkgs: String) = Row(apps = pkgs.map { app(it) }, layoutRow = layoutRow)
    private fun chRow(layoutRow: Int) = Row(
        apps = emptyList(), icon = CHANNEL_ROW_ICON, layoutRow = layoutRow, channel = ref,
        programs = listOf(Program(1, "t", null, null, null, 0, null, PosterAspect.R16_9, null, 0)),
    )
    private fun names(rows: List<Row>) = rows.map { r -> if (r.isChannel) listOf("#channel") else r.apps.map { it.packageName } }

    @Test fun cellCountIsProgramsForChannelRowsAndAppsOtherwise() {
        assertEquals(1, chRow(1).cellCount)
        assertEquals(2, appRow(0, "a", "b").cellCount)
    }

    @Test fun downSkipsAChannelRow() {
        val home = listOf(appRow(0, "a", "b"), chRow(1), appRow(2, "c"))
        val (r, p) = moveCard(home, MovePos(0, 1), MoveDir.DOWN)
        assertEquals(listOf(listOf("a"), listOf("#channel"), listOf("c", "b")), names(r))
        assertEquals(MovePos(2, 1), p)
    }

    @Test fun upSkipsAChannelRow() {
        val home = listOf(appRow(0, "a"), chRow(1), appRow(2, "c", "d"))
        // c 在第 2 行第 0 列,上移越过频道行落到第 0 行同一列(第 0 列)
        val (r, p) = moveCard(home, MovePos(2, 0), MoveDir.UP)
        assertEquals(listOf(listOf("c", "a"), listOf("#channel"), listOf("d")), names(r))
        assertEquals(MovePos(0, 0), p)
    }

    @Test fun noAppRowInThatDirectionStaysPut() {
        val home = listOf(appRow(0, "a"), chRow(1))
        val (r, p) = moveCard(home, MovePos(0, 0), MoveDir.DOWN)
        assertSame(home, r)
        assertEquals(MovePos(0, 0), p)
    }

    @Test fun emptiedSourceRowIsRemovedAndTheIndexFollowsPastTheChannelRow() {
        val home = listOf(appRow(0, "a"), chRow(1), appRow(2, "c"))
        val (r, p) = moveCard(home, MovePos(0, 0), MoveDir.DOWN)
        assertEquals(listOf(listOf("#channel"), listOf("a", "c")), names(r))
        assertEquals(MovePos(1, 0), p)
    }

    @Test fun moveInLayoutSkipsChannelRowsToo() {
        val rows = listOf(LayoutRow("movie", listOf("a", "b")), LayoutRow(CHANNEL_ROW_ICON, channel = ref), LayoutRow("music", listOf("c")))
        val (r, p) = moveInLayout(rows, MovePos(0, 0), MoveDir.DOWN)
        assertEquals(listOf(listOf("b"), emptyList(), listOf("a", "c")), r.map { it.apps })
        assertEquals(ref, r[1].channel)
        assertEquals(MovePos(2, 0), p)
        val lone = listOf(LayoutRow("movie", listOf("a")), LayoutRow(CHANNEL_ROW_ICON, channel = ref))
        assertSame(lone, moveInLayout(lone, MovePos(0, 0), MoveDir.DOWN).first)
    }

    @Test fun mergeMoveLeavesChannelRowsByteForByte() {
        val disk = listOf(LayoutRow("movie", listOf("a", "b")), LayoutRow(CHANNEL_ROW_ICON, channel = ref), LayoutRow("music", listOf("c")))
        val original = listOf(appRow(0, "a", "b"), chRow(1), appRow(2, "c"))
        val (working, _) = moveCard(original, MovePos(0, 1), MoveDir.DOWN)
        val merged = mergeMove(disk, original, working)
        assertSame(disk[1], merged[1])
        assertEquals(listOf("a"), merged[0].apps)
        assertEquals(listOf("c", "b"), merged[2].apps)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*MoveChannelTest*'`
Expected: 编译失败(`Row` 没有 `channel` / `programs` 参数,`cellCount` 未定义)。

- [ ] **Step 3: 实现**

`Model.kt` 的 `data class Row(...)` 换成(保留原来三个字段的 KDoc,在 `layoutRow` 之后加三个字段):

```kotlin
data class Row(
    val apps: List<AppEntry>,
    /** layout.json 里存的图标 id(见 RowIcons.kt;读盘时已补成合法值,默认值只给单测用)。 */
    val icon: String = NEW_ROW_ICON,
    /** (原 KDoc 不变:为什么不能用渲染位置代替) */
    val layoutRow: Int = -1,
    /** R164:非 null = 频道行([apps] 恒空,卡片是 [programs])。 */
    val channel: ChannelRef? = null,
    /** R164:频道行的节目(已排序、≤ 12 张);首页只画非空的频道行。 */
    val programs: List<Program> = emptyList(),
    /** R164:频道行发布方的应用名(行头「应用名 · 频道名」的前半;读不到时是包名)。 */
    val channelAppLabel: String = "",
)

/** R164:是不是频道行。 */
val Row.isChannel: Boolean get() = channel != null

/** 这一行可聚焦的格数:应用行 = 应用数,频道行 = 节目数。焦点账本里一切「夹到本行末格」都用它(铁律 2 的同一个夹取口径)。 */
val Row.cellCount: Int get() = if (channel != null) programs.size else apps.size
```

`Move.kt` 的 `moveCard` 里 UP/DOWN 分支开头三行:

```kotlin
            val step = if (dir == MoveDir.UP) -1 else 1
            val t = pos.row + step
            if (t !in rows.indices) return rows to pos
```

换成:

```kotlin
            // R164:跳过频道行,落到这个方向上第一个应用行;没有就不动
            val step = if (dir == MoveDir.UP) -1 else 1
            var t = pos.row + step
            while (t in rows.indices && rows[t].isChannel) t += step
            if (t !in rows.indices) return rows to pos
```

`moveInLayout` 的 UP/DOWN 分支前两行:

```kotlin
            val t = pos.row + if (dir == MoveDir.UP) -1 else 1
            val target = rows.getOrNull(t) ?: return rows to pos
```

换成:

```kotlin
            // R164:跳过频道行(编辑页拿起卡片上下换行,spec §2.3 / §5)
            val step = if (dir == MoveDir.UP) -1 else 1
            var t = pos.row + step
            while (t in rows.indices && rows[t].isChannel) t += step
            val target = rows.getOrNull(t) ?: return rows to pos
```

两个函数的 KDoc 各补一句:「R164:上下跳过频道行,那个方向没有应用行 → 不动。」

- [ ] **Step 4: 跑测试确认通过(含旧的 Move 测试)**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*MoveChannelTest*' --tests '*MoveTest*' --tests '*MoveBoundaryTest*'`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/Model.kt app/src/main/java/com/uniteduone/launcher/Move.kt app/src/test/java/com/uniteduone/launcher/MoveChannelTest.kt && git commit -F - <<'EOF'
feat(channels): Row 认频道行;首页 / 编辑页搬运上下跳过频道行(R164 §5)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 5: 卸载清理删频道行

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/Layout.kt`(`Layout.withoutPackage`)
- Modify: `app/src/main/java/com/uniteduone/launcher/PrunePure.kt`(`withoutPackages`、新增 `layoutPackages`)
- Modify: `app/src/main/java/com/uniteduone/launcher/PackagePruning.kt`(`pruneMissingPackages` 的 `pkgs`)
- Modify: `app/src/main/java/com/uniteduone/launcher/LayoutOps.kt`(`dropRemovedElsewhere`、`knownAfterWrite`)
- Modify: `app/src/main/java/com/uniteduone/launcher/EditScreen.kt`(edit-shelves 重写后的 `knownOnDisk` 初值)
- Test: `app/src/test/java/com/uniteduone/launcher/ChannelPruneTest.kt`

**Interfaces:**
- Consumes: Task 1 `LayoutRow.channel`。
- Produces:
  - `internal fun layoutPackages(rows: List<LayoutRow>): List<String>`(应用包 + 频道发布方包)
  - `Layout.withoutPackage(rows, pkg)`、`withoutPackages(rows, gone)`:同时删掉 `channel.pkg` 命中的频道行;没命中仍返回同一个 list。
  - `dropRemovedElsewhere` / `knownAfterWrite` 签名不变,把频道包当成「盘上的包」一样认。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/ChannelPruneTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** R164 spec §3.3:发布频道的应用被真正卸载(FULLY_REMOVED / 启动缺包清理)→ 它的频道行从 layout.json 删掉。 */
class ChannelPruneTest {
    private val kumiao = ChannelRef("com.cibn.tv", "k", "酷喵推荐")
    private val bili = ChannelRef("com.xiaodianshi.tv.yst", "", "热门")
    private val rows = listOf(
        LayoutRow("movie", listOf("com.cibn.tv", "com.a")),
        LayoutRow(CHANNEL_ROW_ICON, channel = kumiao),
        LayoutRow(CHANNEL_ROW_ICON, channel = bili),
    )

    @Test fun uninstallRemovesTheAppCardAndItsChannelRow() {
        val next = Layout.withoutPackage(rows, "com.cibn.tv")
        assertEquals(listOf(LayoutRow("movie", listOf("com.a")), LayoutRow(CHANNEL_ROW_ICON, channel = bili)), next)
    }

    @Test fun channelOnlyPackageIsRemovedToo() {
        val next = Layout.withoutPackage(rows, "com.xiaodianshi.tv.yst")
        assertEquals(2, next.size)
        assertEquals(kumiao, next[1].channel)
    }

    @Test fun untouchedWhenNothingMatches() {
        assertSame(rows, Layout.withoutPackage(rows, "com.zzz"))
        assertSame(rows, withoutPackages(rows, setOf("com.zzz")))
    }

    @Test fun startupPruneSeesChannelPackages() {
        assertEquals(listOf("com.cibn.tv", "com.a", "com.cibn.tv", "com.xiaodianshi.tv.yst"), layoutPackages(rows))
        val next = withoutPackages(rows, setOf("com.xiaodianshi.tv.yst"))
        assertEquals(listOf(null, kumiao), next.map { it.channel })
    }

    /** 编辑页整份写回前的合并(落盘排查 2026-09-23 同一个洞):别人刚清掉的频道行不能被编辑页的旧快照写回去。 */
    @Test fun editSnapshotDropsChannelRowsRemovedElsewhere() {
        val disk = listOf(LayoutRow("movie", listOf("com.a")), LayoutRow(CHANNEL_ROW_ICON, channel = kumiao))
        val known = knownAfterWrite(emptySet(), rows)
        val merged = dropRemovedElsewhere(rows, disk, known) { it != "com.xiaodianshi.tv.yst" }
        assertEquals(listOf(null, kumiao), merged.map { it.channel })
        assertEquals(listOf("com.cibn.tv", "com.a"), merged[0].apps)
    }

    @Test fun channelAddedInThisEditSessionIsKept() {
        val disk = listOf(LayoutRow("movie", listOf("com.a")))
        val snapshot = disk + LayoutRow(CHANNEL_ROW_ICON, channel = bili)
        assertSame(snapshot, dropRemovedElsewhere(snapshot, disk, knownAfterWrite(emptySet(), disk)) { false })
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelPruneTest*'`
Expected: 编译失败(`layoutPackages` 未定义);补上后 `uninstallRemovesTheAppCardAndItsChannelRow` 等 FAIL。

- [ ] **Step 3: 实现**

`Layout.kt` 的 `withoutPackage` 换成:

```kotlin
    fun withoutPackage(rows: List<LayoutRow>, pkg: String): List<LayoutRow> {
        if (rows.none { pkg in it.apps || it.channel?.pkg == pkg }) return rows
        // R164:发布方没了,它的频道行一起删(spec §3.3);应用行里的卡照旧移出、空行保留
        return rows.filter { it.channel?.pkg != pkg }
            .map { r -> if (pkg in r.apps) r.copy(apps = r.apps.filter { it != pkg }) else r }
    }
```

KDoc 补:「R164:`channel.pkg` 是它的频道行整行删掉;频道行不计入应用行,删掉它不会让应用行少于 1。」

`PrunePure.kt`:`withoutPackages` 换成,并在它前面加 `layoutPackages`:

```kotlin
/** 布局里出现的全部包:应用行里的包 + 频道行的发布方(R164;启动清理按它查装没装)。 */
internal fun layoutPackages(rows: List<LayoutRow>): List<String> =
    rows.flatMap { it.apps } + rows.mapNotNull { it.channel?.pkg }

/** 纯函数:把 [gone] 从每一行去掉,发布方在 [gone] 里的频道行整行删(R164);一个都没命中时返回**同一个** list。 */
internal fun withoutPackages(rows: List<LayoutRow>, gone: Set<String>): List<LayoutRow> {
    if (gone.isEmpty() || rows.none { r -> r.apps.any { it in gone } || r.channel?.pkg in gone }) return rows
    return rows.filter { it.channel?.pkg !in gone }
        .map { r -> if (r.apps.any { it in gone }) r.copy(apps = r.apps.filter { it !in gone }) else r }
}
```

并把 `planPrune` KDoc 里「layout.json 只有应用行」改成「layout.json 的应用包与频道发布方包(`layoutPackages`)」。

`PackagePruning.kt` 的 `pkgs = rows.flatMap { it.apps },` 换成 `pkgs = layoutPackages(rows),`,KDoc 里「layout.json 只有应用行」那句改成「R164 起频道行的发布方也算(没装了整行删)」。

`LayoutOps.kt`:`dropRemovedElsewhere` 换成:

```kotlin
internal fun dropRemovedElsewhere(
    snapshot: List<LayoutRow>,
    disk: List<LayoutRow>,
    knownOnDisk: Set<String>,
    installed: (String) -> Boolean,
): List<LayoutRow> {
    val onDisk = layoutPackages(disk).toHashSet()
    val gone = layoutPackages(snapshot).filterTo(HashSet()) { it in knownOnDisk && it !in onDisk && !installed(it) }
    if (gone.isEmpty()) return snapshot
    // R164:频道行的发布方同样按「曾在盘上、此刻不在、没装」认,删整行
    return snapshot.filter { it.channel?.pkg !in gone }
        .map { r -> if (r.apps.any { it in gone }) r.copy(apps = r.apps.filter { it !in gone }) else r }
}
```

`knownAfterWrite` 换成:

```kotlin
internal fun knownAfterWrite(known: Set<String>, written: List<LayoutRow>): Set<String> =
    HashSet(known).apply { addAll(layoutPackages(written)) }
```

`EditScreen.kt`(edit-shelves 重写后)的
`val knownOnDisk = remember { java.util.concurrent.atomic.AtomicReference(rows.flatMapTo(HashSet()) { it.apps }.toSet()) }`
换成 `… AtomicReference(layoutPackages(rows).toSet()) }`——初值只数应用包的话,进页时已在盘上的频道行的发布方不在「上次确知在盘上」里,
编辑页开着时发布方被卸载、接收器把频道行从盘上删掉,下一次整份写回会把它当成「本页新加」原样写回去(`editSnapshotDropsChannelRowsRemovedElsewhere` 的同一个洞,只是出在初值)。

- [ ] **Step 4: 跑测试确认通过(含旧的清理与 Layout 测试)**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelPruneTest*' --tests '*PrunePure*' --tests '*LayoutTest*' --tests '*LayoutOps*'`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/Layout.kt app/src/main/java/com/uniteduone/launcher/PrunePure.kt app/src/main/java/com/uniteduone/launcher/PackagePruning.kt app/src/main/java/com/uniteduone/launcher/LayoutOps.kt app/src/main/java/com/uniteduone/launcher/EditScreen.kt app/src/test/java/com/uniteduone/launcher/ChannelPruneTest.kt && git commit -F - <<'EOF'
feat(channels): 发布方卸载 / 缺包清理删掉它的频道行(R164 §3.3)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---
### Task 6: 「加到桌面… → 选一行」只列应用行

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(`appsMenuItems` 第二层,约 2100–2124 行的 `return rows.mapIndexed { i, r -> … }`)
- Modify: `app/src/main/java/com/uniteduone/launcher/AppsPage.kt`(`rowAppNames` 的 KDoc)
- Test: `app/src/test/java/com/uniteduone/launcher/AppsPageChannelTest.kt`

**Interfaces:**
- Consumes: Task 2 `appRowIndices(rows: List<LayoutRow>): List<Int>`、`addToRow`(已拒收频道行)。
- Produces: 无新符号;第二层药丸只对应用行生成,点击时用的仍是 **layout 下标**。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/AppsPageChannelTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** R164 spec §5:所有应用页「加到桌面… → 选一行」只列应用行,第 k 颗药丸映射回 layout 下标。 */
class AppsPageChannelTest {
    private val ref = ChannelRef("com.cibn.tv", "k", "酷喵推荐")
    private val rows = listOf(
        LayoutRow("movie", listOf("a")),
        LayoutRow(CHANNEL_ROW_ICON, channel = ref),
        LayoutRow("music", listOf("b")),
    )

    @Test fun secondLayerListsAppRowsAndMapsBackToLayoutIndices() {
        val choices = appRowIndices(rows)
        assertEquals(listOf(0, 2), choices)
        val next = addToRow(rows, choices[1], "x")   // 第 2 颗药丸 = layout 第 2 行
        assertEquals(listOf("b", "x"), next[2].apps)
        assertSame(rows[1], next[1])
    }

    @Test fun aStaleIndexPointingAtAChannelRowWritesNothing() {
        assertSame(rows, addToRow(rows, 1, "x"))
    }
}
```

- [ ] **Step 2: 跑测试确认通过(纯函数已在 Task 2 实现,这一步是钉住映射口径)**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*AppsPageChannelTest*'`
Expected: PASS。

- [ ] **Step 3: 改 MainActivity**

`appsMenuItems` 里 `return rows.mapIndexed { i, r ->` 换成:

```kotlin
        // R164:只列应用行(频道行不收应用);i 仍是 layout.json 下标,rowApps 与 addToRow 都按它取
        return appRowIndices(rows).map { i ->
            val r = rows[i]
```

(闭包体其余部分逐字不动——它读的 `i`、`r`、`m.rowApps.getOrNull(i)` 含义不变。)

`AppsPage.kt` 的 `rowAppNames` KDoc 末尾补一句:「R164:频道行在这里是空列表(它没有应用),第二层本来就不列它。」

- [ ] **Step 4: 编译确认**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon assembleRelease testReleaseUnitTest --tests '*AppsPage*'`
Expected: BUILD SUCCESSFUL,测试 PASS。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/MainActivity.kt app/src/main/java/com/uniteduone/launcher/AppsPage.kt app/src/test/java/com/uniteduone/launcher/AppsPageChannelTest.kt && git commit -F - <<'EOF'
feat(channels): 所有应用页「加到桌面」第二层只列应用行(R164 §5)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 7: 读 TvProvider(`ChannelSource`)+ 进程级缓存(`ChannelCache`)+ 清单权限

**Owner 裁定(2026-10-08)**:频道数据**只有一份进程级内存缓存**(`ChannelCache`,照 `AppsPageCache`:一把锁串行、一次读覆盖它开始之前的全部请求、`StateFlow` 按 `equals` 去重),首页、编辑页、选频道页**都读它**,谁也不自己查 TvProvider;刷新由 MainActivity 按 `channelsRevision`(ContentObserver 去抖、授权结果、onResume)驱动(Task 9)。内容相同的刷新不通知任何人——不重组、不冻结焦点。

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/ChannelSource.kt`
- Create: `app/src/main/java/com/uniteduone/launcher/ChannelCache.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/ChannelModel.kt`(末尾加 `channelContents`、`ChannelSnapshot`、`channelContentsFrom`)
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/uniteduone/launcher/Apps.kt`(加 `labelOf`)
- Test: `app/src/test/java/com/uniteduone/launcher/ChannelContentsTest.kt`、`app/src/test/java/com/uniteduone/launcher/ChannelCacheTest.kt`

**Interfaces:**
- Consumes: Task 3 全部(`TvCols`、`channelFromRow`、`programFromRow`、`matchChannel`、`topPrograms`、`ChannelContent`)。
- Produces:
  - `internal fun channelContents(refs: List<ChannelRef>, channelsOf: (String) -> List<TvChannel>?, programsOf: (Long) -> List<Program>?): Map<ChannelRef, ChannelContent>`
  - `internal data class ChannelSnapshot(val permitted: Boolean, val channels: List<TvChannel> = emptyList(), val programs: Map<Long, List<Program>> = emptyMap(), val labels: Map<String, String> = emptyMap())`
  - `internal fun channelContentsFrom(snap: ChannelSnapshot?, refs: List<ChannelRef>): Map<ChannelRef, ChannelContent>`(null = 还没读过 → 空表)
  - `object ChannelSource { const val PERMISSION: String; fun hasPermission(ctx: Context): Boolean; fun channels(ctx: Context, pkg: String? = null): List<TvChannel>?; fun programs(ctx: Context, channelId: Long): List<Program>?; internal fun snapshot(ctx: Context): ChannelSnapshot }`(全部 IO 线程;只有 `ChannelCache` 调 `snapshot`)
  - `internal class ChannelStore { val data: StateFlow<ChannelSnapshot?>; suspend fun refresh(load: suspend () -> ChannelSnapshot?) }`、`internal object ChannelCache { val data: StateFlow<ChannelSnapshot?>; suspend fun refresh(ctx: Context) }`
  - `Apps.labelOf(ctx: Context, pkg: String): String`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/ChannelContentsTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** R164:每个频道行此刻的内容(spec §3.2):找频道 → 取节目 → 排序截断;没权限 / 找不到 / 空。 */
class ChannelContentsTest {
    private val ref = ChannelRef("com.cibn.tv", "k1", "酷喵推荐")
    private val other = ChannelRef("com.other", "", "热门")
    private val chan = TvChannel(10, "com.cibn.tv", CHANNEL_TYPE_PREVIEW, "酷喵推荐", "k1")
    private fun prog(id: Long, w: Int = 0) = Program(id, "t$id", null, null, null, 0, null, PosterAspect.R16_9, null, w)

    /** Review Focus 2:授权被收回(冷启动后 / 自动收回)→ 每个频道行都是「需要重新授权」,不当成「暂无内容」。 */
    @Test fun deniedMeansEveryRowNeedsPermission() {
        val got = channelContents(listOf(ref, other), { null }, { error("没权限时不该查节目") })
        assertEquals(mapOf(ref to ChannelContent.NeedsPermission, other to ChannelContent.NeedsPermission), got)
    }

    @Test fun programsDeniedMidwayAlsoNeedsPermission() {
        assertEquals(ChannelContent.NeedsPermission, channelContents(listOf(ref), { listOf(chan) }, { null })[ref])
    }

    @Test fun missingChannelOrNoProgramsIsMissing() {
        assertEquals(ChannelContent.Missing, channelContents(listOf(other), { emptyList() }, { emptyList() })[other])
        assertEquals(ChannelContent.Missing, channelContents(listOf(ref), { listOf(chan) }, { emptyList() })[ref])
    }

    @Test fun readyIsSortedAndCapped() {
        val got = channelContents(listOf(ref), { listOf(chan) }, { id -> assertEquals(10L, id); (1L..15L).map { prog(it, w = it.toInt()) } })[ref]
        assertEquals((15L downTo 4L).toList(), (got as ChannelContent.Ready).programs.map { it.id })
    }

    @Test fun eachPackageIsQueriedOnce() {
        var calls = 0
        val twoOfSamePkg = listOf(ref, ChannelRef("com.cibn.tv", "k2", "第二个"))
        channelContents(twoOfSamePkg, { calls++; listOf(chan) }, { emptyList() })
        assertEquals(1, calls)
    }
}
```

`app/src/test/java/com/uniteduone/launcher/ChannelCacheTest.kt`(owner 裁定:一份进程级缓存;钉住更新 / 去重语义与从快照派生内容):

```kotlin
package com.uniteduone.launcher

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChannelCacheTest {
    private val ref = ChannelRef("p", "k", "c")
    private val chan = TvChannel(1, "p", CHANNEL_TYPE_PREVIEW, "c", "k")
    private fun prog(id: Long) = Program(id, "t$id", null, null, null, 0, null, PosterAspect.R16_9, null, 0)
    private fun snap(vararg ids: Long) = ChannelSnapshot(true, listOf(chan), mapOf(1L to ids.map { prog(it) }), mapOf("p" to "P"))

    @Test fun firstRefreshPublishes() {
        runBlocking {
            val s = ChannelStore()
            assertNull("还没读过", s.data.value)
            s.refresh { snap(1, 2) }
            assertEquals(snap(1, 2), s.data.value)
        }
    }

    /** 内容相同的刷新(onResume、TvProvider 无关的变化)不换引用 = 不通知:首页不重组、不冻结焦点。 */
    @Test fun equalContentKeepsTheSameInstance() {
        runBlocking {
            val s = ChannelStore()
            val first = snap(1, 2)
            s.refresh { first }
            s.refresh { snap(1, 2) }
            assertSame(first, s.data.value)
        }
    }

    @Test fun changedContentReplaces() {
        runBlocking {
            val s = ChannelStore()
            val first = snap(1, 2)
            s.refresh { first }
            s.refresh { snap(1) }
            assertNotSame(first, s.data.value)
            assertEquals(snap(1), s.data.value)
        }
    }

    @Test fun failedLoadKeepsThePreviousSnapshot() {
        runBlocking {
            val s = ChannelStore()
            s.refresh { snap(1) }
            s.refresh { null }
            assertEquals(snap(1), s.data.value)
        }
    }

    @Test fun contentsAreDerivedFromTheSharedSnapshot() {
        assertEquals("还没读过:谁都不画", emptyMap<ChannelRef, ChannelContent>(), channelContentsFrom(null, listOf(ref)))
        assertEquals(mapOf(ref to ChannelContent.NeedsPermission), channelContentsFrom(ChannelSnapshot(permitted = false), listOf(ref)))
        assertEquals(mapOf(ref to ChannelContent.Ready(listOf(prog(1)))), channelContentsFrom(snap(1), listOf(ref)))
        assertEquals(mapOf(ref to ChannelContent.Missing), channelContentsFrom(snap(), listOf(ref)))
        assertEquals(emptyMap<ChannelRef, ChannelContent>(), channelContentsFrom(snap(1), emptyList()))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelContentsTest*' --tests '*ChannelCacheTest*'`
Expected: 编译失败,`Unresolved reference: channelContents` / `ChannelSnapshot` / `ChannelStore`。

- [ ] **Step 3: 实现纯组装**

`ChannelModel.kt` 末尾加:

```kotlin
/**
 * 组装每个频道行的内容(spec §3.2)。[channelsOf] 按包取频道(`?package=`),返回 null = 没有权限;
 * [programsOf] 按频道 id 取预览节目,null = 没有权限。同一个包只查一次。
 */
internal fun channelContents(
    refs: List<ChannelRef>,
    channelsOf: (pkg: String) -> List<TvChannel>?,
    programsOf: (channelId: Long) -> List<Program>?,
): Map<ChannelRef, ChannelContent> {
    // 不用 getOrPut:值为 null(没权限)时它会把键当缺失、再查一次
    val byPkg = HashMap<String, List<TvChannel>?>()
    return refs.distinct().associateWith { ref ->
        val chans = if (byPkg.containsKey(ref.pkg)) byPkg[ref.pkg] else channelsOf(ref.pkg).also { byPkg[ref.pkg] = it }
        if (chans == null) return@associateWith ChannelContent.NeedsPermission
        val ch = matchChannel(ref, chans) ?: return@associateWith ChannelContent.Missing
        val progs = programsOf(ch.id) ?: return@associateWith ChannelContent.NeedsPermission
        val top = topPrograms(progs)
        if (top.isEmpty()) ChannelContent.Missing else ChannelContent.Ready(top)
    }
}

/**
 * 进程级频道缓存([ChannelCache])里的一份快照:读的那一刻有没有授权、全部频道、每个预览频道的节目(已排序截断)、
 * 发布方应用名。data class:内容相同即相等,`StateFlow` 据此不通知。
 */
internal data class ChannelSnapshot(
    val permitted: Boolean,
    val channels: List<TvChannel> = emptyList(),
    val programs: Map<Long, List<Program>> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
)

/** 从共享快照派生每个频道行的内容(首页 / 编辑页同一个口径)。[snap] = null(还没读过)→ 空表(首页不画、编辑页按「暂无内容」)。 */
internal fun channelContentsFrom(snap: ChannelSnapshot?, refs: List<ChannelRef>): Map<ChannelRef, ChannelContent> {
    if (snap == null || refs.isEmpty()) return emptyMap()
    if (!snap.permitted) return refs.distinct().associateWith { ChannelContent.NeedsPermission }
    return channelContents(refs, { pkg -> snap.channels.filter { it.pkg == pkg } }, { id -> snap.programs[id].orEmpty() })
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2(`ChannelCacheTest` 要等下面 Step 5 的 `ChannelStore` 写完才编得过,先注释掉它,Step 6 一起跑)。
Expected: `ChannelContentsTest` PASS。

- [ ] **Step 5: Android 侧读取 + 进程级缓存 + 清单 + `labelOf`**

`app/src/main/java/com/uniteduone/launcher/ChannelSource.kt`:

```kotlin
package com.uniteduone.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log

/**
 * R164:读 TvProvider 的两张表(spec §3.2)。**全部在 IO 线程调用**。纯逻辑(列、解析、匹配、组装)在 ChannelModel.kt。
 *
 * - 一律显式投影([TvCols.CHANNEL_PROJECTION] / [TvCols.PROGRAM_PROJECTION]),不用 selection(第三方带 selection 抛
 *   `SecurityException`,研究 §6.4);频道按 `?package=` 过滤,节目按 [TvContract.buildPreviewProgramsUriForChannel]。
 * - **没授权时 TvProvider 不抛异常,只返回 0 行**(研究 §6.2),所以「需要重新授权」先看 [hasPermission];
 *   `SecurityException` 只是兜底。不看 `browsable`(研究 §6.6–6.7:国行与 Google TV 上没人审批,永远是 0)。
 * - **列不受限、行受限**(AOSP android14-release `TvProvider.java` 核对,2026-10-09):`query` 只用 `createProjectionMapForQuery`
 *   (约 1823 行)按投影映射取列,不按调用方身份屏蔽或置空任何列——`internal_provider_id`(频道 237 行、预览节目 468 行)、
 *   `display_name`、`type`、`poster_art_uri` / `_aspect_ratio`、`intent_uri`、`weight`、`duration_millis`、季 / 集字段都在映射里,
 *   非特权调用方照样读得到;映射里没有的列返回 `NULL AS 列名`、不抛。限制全在**行**上:`createSqlParams`(1887–1900 行)对没有
 *   `ACCESS_ALL_EPG_DATA` 的调用方,带 selection 抛 `SecurityException`(1890 行),持 `READ_TV_LISTINGS` 时只放开
 *   `package_name = 自己 OR searchable = 1`(1896 行)。所以发布方把频道或节目设成 `searchable = 0` 时我们读不到那一行
 *   → 「暂无内容」,不是 bug;`?package=` 由 `appendWhere` 以括号 AND 在后面(1902 行),不破坏这个条件。
 *   `sortOrder` 传 null(非特权调用方的排序列要过 `validateSortOrder`,1492 行),排序在 `topPrograms` 里做。
 */
object ChannelSource {
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"
    private const val TAG = "UnitedU"

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** [pkg] = null:全部频道(选频道页);否则只要这个包的。null = 没有权限;其它读取错误 → 空表(只 Log)。 */
    fun channels(ctx: Context, pkg: String? = null): List<TvChannel>? {
        if (!hasPermission(ctx)) return null
        val uri = if (pkg == null) TvContract.Channels.CONTENT_URI
        else TvContract.Channels.CONTENT_URI.buildUpon().appendQueryParameter("package", pkg).build()
        return try {
            query(ctx, uri, TvCols.CHANNEL_PROJECTION).mapNotNull(::channelFromRow)
        } catch (e: SecurityException) {
            Log.w(TAG, "读频道被拒: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "读频道失败: ${e.javaClass.simpleName} ${e.message}")
            emptyList()
        }
    }

    /** 一个频道的全部预览节目(未排序)。null = 没有权限;其它错误 → 空表。 */
    fun programs(ctx: Context, channelId: Long): List<Program>? = try {
        query(ctx, TvContract.buildPreviewProgramsUriForChannel(channelId), TvCols.PROGRAM_PROJECTION).mapNotNull(::programFromRow)
    } catch (e: SecurityException) {
        Log.w(TAG, "读节目被拒: ${e.message}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "读节目失败: ${e.javaClass.simpleName} ${e.message}")
        emptyList()
    }

    /**
     * [ChannelCache] 的一次完整读取:全部频道(一次查询)+ 每个**别人发的**预览频道的节目(每个频道一次查询,排序截断)+
     * 发布方应用名。没授权 / 中途 `SecurityException` → `permitted = false`、其余为空。首页、编辑页、选频道页都从这一份派生。
     */
    internal fun snapshot(ctx: Context): ChannelSnapshot {
        val denied = ChannelSnapshot(permitted = false)
        val all = channels(ctx) ?: return denied
        val progs = HashMap<Long, List<Program>>()
        for (c in all) {
            if (c.type != CHANNEL_TYPE_PREVIEW || c.pkg == ctx.packageName) continue
            progs[c.id] = topPrograms(programs(ctx, c.id) ?: return denied)
        }
        val labels = all.map { it.pkg }.distinct().associateWith { Apps.labelOf(ctx, it) }
        return ChannelSnapshot(permitted = true, channels = all, programs = progs, labels = labels)
    }

    private fun query(ctx: Context, uri: Uri, projection: Array<String>): List<Map<String, Any?>> =
        ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.rowMap(projection)) }
        } ?: emptyList()

    private fun Cursor.rowMap(cols: Array<String>): Map<String, Any?> = cols.associateWith { name ->
        val i = getColumnIndex(name)
        if (i < 0 || isNull(i)) null
        else when (getType(i)) {
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            Cursor.FIELD_TYPE_STRING -> getString(i)
            else -> null   // BLOB 一律不读
        }
    }
}
```

`app/src/main/java/com/uniteduone/launcher/ChannelCache.kt`:

```kotlin
package com.uniteduone.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 读 → 发布的骨架(JVM 可测,ChannelCacheTest),照 [AppsPageCache]:读写串行(一把锁);读的时候又有人要刷新的,跑完再读一遍;
 * 更早的请求已被更晚开始的一次读覆盖的,直接跳过。[load] 返回 null(读失败)→ 留着上一份。`StateFlow` 按 `equals` 去重:
 * 内容相同的刷新不换引用、不通知任何人。
 */
internal class ChannelStore {
    private val state = MutableStateFlow<ChannelSnapshot?>(null)
    val data: StateFlow<ChannelSnapshot?> = state
    private val lock = Mutex()
    private val requested = AtomicLong(0)
    @Volatile private var covered = 0L

    suspend fun refresh(load: suspend () -> ChannelSnapshot?) {
        val ticket = requested.incrementAndGet()
        lock.withLock {
            if (covered >= ticket) return
            val start = requested.get()
            val fresh = load()
            covered = start
            if (fresh != null) state.value = fresh
        }
    }
}

/**
 * **频道数据的进程级缓存**(owner 裁定 2026-10-08):首页、编辑页、选频道页都 `collect` [data],谁也不自己查 TvProvider。
 * MainActivity 在 `channelsRevision` 每变一次(TvProvider 变化 500 ms 去抖、授权结果、onResume)时 [refresh](Task 9)。
 */
internal object ChannelCache {
    private val store = ChannelStore()
    val data: StateFlow<ChannelSnapshot?> get() = store.data

    suspend fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        store.refresh { withContext(Dispatchers.IO) { runCatching { ChannelSource.snapshot(app) }.getOrNull() } }
    }
}
```

`AndroidManifest.xml`:在 `INTERNET` 那条 `<uses-permission>` 之后加:

```xml
    <!-- R164 频道行:读其它应用发布的 TvProvider 预览频道与节目。dangerous,运行时在选频道页 / 「重新授权」申请
         (MainActivity.onCreate 注册的 RequestPermission)。查 INITIALIZE_PROGRAMS 接收器靠上面的 QUERY_ALL_PACKAGES。 -->
    <uses-permission android:name="android.permission.READ_TV_LISTINGS" />
    <!-- normal,装上即有。研究 §6 的探针读取方声明了它 + READ_TV_LISTINGS,「只声明 READ_TV_LISTINGS」从没测过;而 TvProvider
         在权限层按清单的 read/writePermission 拦人(§6.5:写操作缺 WRITE_EPG_DATA 当场被拒)。读侧若同样要它,缺了 query /
         registerContentObserver 就抛 SecurityException → 授权了也永远「需要重新授权」、收不到变化通知。声明它零代价,照探针原样。 -->
    <uses-permission android:name="com.android.providers.tv.permission.READ_EPG_DATA" />
```

`Apps.kt` 的 `object Apps` 里(`isInstalled` 之后)加:

```kotlin
    /** 包的应用名(频道行行头、选频道页「应用名 · 频道名」用;发布方不一定有启动入口,所以不走 [load])。读不到 → 包名。IO 线程。 */
    fun labelOf(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: pkg
```

- [ ] **Step 6: 编译 + 核对权限进了清单**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelContentsTest*' --tests '*ChannelCacheTest*' assembleRelease && "$ANDROID_HOME/build-tools/35.0.0/aapt2" dump permissions app/build/outputs/apk/release/app-release.apk | grep -E "READ_TV_LISTINGS|READ_EPG_DATA"`
Expected: 两个测试类 PASS(Step 4 注释掉的 `ChannelCacheTest` 先放回来);BUILD SUCCESSFUL;输出 `uses-permission: name='android.permission.READ_TV_LISTINGS'` 与 `uses-permission: name='com.android.providers.tv.permission.READ_EPG_DATA'` 两行。

- [ ] **Step 7: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/ChannelSource.kt app/src/main/java/com/uniteduone/launcher/ChannelCache.kt app/src/main/java/com/uniteduone/launcher/ChannelModel.kt app/src/main/AndroidManifest.xml app/src/main/java/com/uniteduone/launcher/Apps.kt app/src/test/java/com/uniteduone/launcher/ChannelContentsTest.kt app/src/test/java/com/uniteduone/launcher/ChannelCacheTest.kt && git commit -F - <<'EOF'
feat(channels): ChannelSource 读 TvProvider(显式投影、?package=、按频道取节目)+ READ_TV_LISTINGS(R164 §3.2)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 8: 海报加载(`PosterLoader`)

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/PosterLoader.kt`
- Test: `app/src/test/java/com/uniteduone/launcher/PosterLoaderTest.kt`

**Interfaces:**
- Consumes: 无(只用 `java.net`、`android.graphics`、`android.util.LruCache`)。
- Produces:
  - `internal enum class PosterSource { RESOLVER, HTTPS, NONE }`、`internal fun posterSourceOf(uri: String): PosterSource`
  - `internal fun posterSampleSize(srcW: Int, srcH: Int, targetH: Int): Int`
  - `internal fun posterRetryDue(failedAt: Long?, now: Long): Boolean`
  - `internal fun readCapped(input: java.io.InputStream, maxBytes: Int): ByteArray?`
  - `internal fun fetchBytes(url: java.net.URL, timeoutMs: Int, maxBytes: Int): ByteArray?`
  - 常量 `POSTER_TIMEOUT_MS = 5_000`、`POSTER_MAX_BYTES = 8 * 1024 * 1024`、`POSTER_RETRY_MS = 60_000L`、`POSTER_CACHE_BYTES = 16 * 1024 * 1024`
  - `object PosterCache { fun peek(uri: String): Bitmap?; suspend fun load(ctx: Context, uri: String, heightPx: Int): Bitmap? }`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/PosterLoaderTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/** R164 spec §3.3 海报:来源分类、采样、失败退避、下载上限与超时。下载用本机 ServerSocket 模拟(http 与 https 走同一个 fetchBytes)。 */
class PosterLoaderTest {
    private fun serveOnce(response: ByteArray): Int {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.use { s ->
                s.accept().use { c ->
                    c.getInputStream().read(ByteArray(4096))
                    c.getOutputStream().write(response)
                    c.getOutputStream().flush()
                }
            }
        }
        return server.localPort
    }

    @Test fun sources() {
        assertEquals(PosterSource.RESOLVER, posterSourceOf("content://test.channels.posters/1"))
        assertEquals(PosterSource.RESOLVER, posterSourceOf("android.resource://com.x/drawable/p"))
        assertEquals(PosterSource.HTTPS, posterSourceOf("HTTPS://cdn.example.com/p.jpg"))
        assertEquals("明文 http 按网络安全配置会失败,当作没图", PosterSource.NONE, posterSourceOf("http://cdn.example.com/p.jpg"))
        assertEquals(PosterSource.NONE, posterSourceOf("file:///sdcard/p.jpg"))
        assertEquals(PosterSource.NONE, posterSourceOf("garbage"))
    }

    @Test fun sampleSizeStaysAtOrAboveTheTargetHeight() {
        assertEquals(1, posterSampleSize(640, 360, 220))
        assertEquals("2160 / 8 = 270 ≥ 220,/ 16 = 135 < 220", 8, posterSampleSize(3840, 2160, 220))
        assertEquals(1, posterSampleSize(0, 0, 220))
    }

    /** Review Focus 3:失败过的海报一分钟内不重试(否则黑洞地址每次重组都卡 5 s 一个线程)。 */
    @Test fun failedPosterIsNotRetriedWithinAMinute() {
        assertTrue(posterRetryDue(null, 5_000))
        assertFalse(posterRetryDue(1_000, 1_000 + POSTER_RETRY_MS - 1))
        assertTrue(posterRetryDue(1_000, 1_000 + POSTER_RETRY_MS))
        assertTrue("时钟倒退(不该发生)时宁可重试", posterRetryDue(10_000, 5_000))
    }

    @Test fun readCappedRefusesOversizeStreams() {
        assertArrayEquals(ByteArray(10), readCapped(ByteArrayInputStream(ByteArray(10)), 10))
        assertNull(readCapped(ByteArrayInputStream(ByteArray(11)), 10))
    }

    @Test fun fetchReadsA200Body() {
        val body = "poster".toByteArray()
        val port = serveOnce("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
        assertArrayEquals(body, fetchBytes(URL("http://127.0.0.1:$port/p.png"), 2_000, 1024))
    }

    @Test fun fetchRejectsNon2xxAndDeclaredOversize() {
        val p404 = serveOnce("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        assertNull(fetchBytes(URL("http://127.0.0.1:$p404/p.png"), 2_000, 1024))
        val pBig = serveOnce("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\nConnection: close\r\n\r\n".toByteArray() + ByteArray(4096))
        assertNull(fetchBytes(URL("http://127.0.0.1:$pBig/p.png"), 2_000, 1024))
    }

    /** Review Focus 3:服务器接了连接但一个字节都不回 → 超时后返回 null,不挂住。 */
    @Test fun fetchGivesUpAfterTheTimeoutWhenTheServerNeverAnswers() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val held = ArrayList<Socket>()
        thread(isDaemon = true) { runCatching { held += server.accept() } }
        val t0 = System.nanoTime()
        val got = fetchBytes(URL("http://127.0.0.1:${server.localPort}/p.jpg"), 300, 1024)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNull(got)
        assertTrue("超时后要放手,实际 $ms ms", ms < 2_000)
        server.close()
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*PosterLoaderTest*'`
Expected: 编译失败,`Unresolved reference: posterSourceOf` 等。

- [ ] **Step 3: 实现**

`app/src/main/java/com/uniteduone/launcher/PosterLoader.kt`:

```kotlin
package com.uniteduone.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * R164 频道海报(spec `2026-10-08-channel-rows-design.md` §3.3)。纯函数在上半(JVM 单测 PosterLoaderTest),
 * Android 侧的缓存与解码是 [PosterCache]。**不做磁盘缓存**;这是 UnitedU 除「检查更新」「上传资料」之外唯一会联网的地方
 * (只在频道行的海报是 https 地址时,README 同步写明)。
 */

internal enum class PosterSource { RESOLVER, HTTPS, NONE }

/** `content://` / `android.resource://` 走 ContentResolver;`https://` 走网络;其它(含明文 `http://`、`file://`)当作没图。 */
internal fun posterSourceOf(uri: String): PosterSource = when (uri.substringBefore(':', "").lowercase()) {
    "content", "android.resource" -> PosterSource.RESOLVER
    "https" -> PosterSource.HTTPS
    else -> PosterSource.NONE
}

/** 粗缩的 `inSampleSize`:取 2 的幂,采样后高度仍 ≥ [targetH](之后再精确缩到 [targetH])。 */
internal fun posterSampleSize(srcW: Int, srcH: Int, targetH: Int): Int {
    if (srcW <= 0 || srcH <= 0 || targetH <= 0) return 1
    var s = 1
    while (srcH / (s * 2) >= targetH) s *= 2
    return s
}

internal const val POSTER_TIMEOUT_MS = 5_000
internal const val POSTER_MAX_BYTES = 8 * 1024 * 1024
internal const val POSTER_RETRY_MS = 60_000L
internal const val POSTER_CACHE_BYTES = 16 * 1024 * 1024

/** 失败过的海报多久后才再试(没失败过 = 现在就试;时钟倒退 = 试)。 */
internal fun posterRetryDue(failedAt: Long?, now: Long): Boolean =
    failedAt == null || now < failedAt || now - failedAt >= POSTER_RETRY_MS

/** 读完整个流;超过 [maxBytes] → null(不让一张海报吃掉几十 MB)。 */
internal fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** 下载 [url]:连接 / 每次读取各 [timeoutMs];非 2xx、声明或实际超过 [maxBytes]、任何异常 → null。只用 java.net,JVM 可测。 */
internal fun fetchBytes(url: URL, timeoutMs: Int, maxBytes: Int): ByteArray? {
    val conn = (runCatching { url.openConnection() }.getOrNull() as? HttpURLConnection) ?: return null
    return try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.useCaches = false
        if (conn.responseCode !in 200..299) null
        else if (conn.contentLengthLong > maxBytes) null
        else conn.inputStream.use { readCapped(it, maxBytes) }
    } catch (e: Exception) {
        null
    } finally {
        conn.disconnect()
    }
}

/**
 * 海报内存缓存:按 uri,`LruCache` 16 MB(按 `allocationByteCount`);失败的 uri 记时间,[POSTER_RETRY_MS] 内不再试。
 * 并发最多 4 路(一行 12 张同时进范围时不把 IO 池占满)。首页只对焦点行 ± 1 调 [load](见 HomeChannels.kt 的 `loadsPosters`)。
 */
object PosterCache {
    private val mem = object : LruCache<String, Bitmap>(POSTER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }
    private val failed = ConcurrentHashMap<String, Long>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(4)

    fun peek(uri: String): Bitmap? = mem.get(uri)

    /** 读 + 解码到 [heightPx] 高;失败返回 null(调用方画深色底 + 标题)。 */
    suspend fun load(ctx: Context, uri: String, heightPx: Int): Bitmap? {
        mem.get(uri)?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (!posterRetryDue(failed[uri], now)) return null
        val bmp = withContext(io) { runCatching { decode(bytesOf(ctx, uri), heightPx) }.getOrNull() }
        if (bmp != null) {
            mem.put(uri, bmp)
            failed.remove(uri)
        } else {
            failed[uri] = SystemClock.elapsedRealtime()
            Log.i("UnitedU", "海报读不到(${POSTER_RETRY_MS / 1000} s 内不再试): $uri")   // e2e j_channels 认这一行
        }
        return bmp
    }

    private fun bytesOf(ctx: Context, uri: String): ByteArray? = when (posterSourceOf(uri)) {
        PosterSource.RESOLVER -> ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { readCapped(it, POSTER_MAX_BYTES) }
        PosterSource.HTTPS -> fetchBytes(URL(uri), POSTER_TIMEOUT_MS, POSTER_MAX_BYTES)
        PosterSource.NONE -> null
    }

    private fun decode(bytes: ByteArray?, heightPx: Int): Bitmap? {
        if (bytes == null) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = posterSampleSize(bounds.outWidth, bounds.outHeight, heightPx) }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (raw.height <= heightPx) return raw
        val w = (raw.width.toLong() * heightPx / raw.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(raw, w, heightPx, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*PosterLoaderTest*'`
Expected: PASS(`fetchGivesUp…` 用时 < 2 s)。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/PosterLoader.kt app/src/test/java/com/uniteduone/launcher/PosterLoaderTest.kt && git commit -F - <<'EOF'
feat(channels): 海报加载——ContentResolver / https 5 s、220 px、16 MB LRU、失败一分钟内不重试(R164 §3.3)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 9: MainActivity 接线——授权、`ContentObserver`、`LocalChannelEnv`、启动节目

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/ChannelEnv.kt`
- Create: `app/src/main/java/com/uniteduone/launcher/ChannelLaunch.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(字段区约 118 行 `revision` 旁;`onCreate` 约 363;`setContent` 的 `CompositionLocalProvider` 约 630;`onResume` 约 1683;`onDestroy` 约 2268——行号以 HEAD b3d9a15 + Task 6 为准,按锚点文字找)
- Test: `app/src/test/java/com/uniteduone/launcher/ChannelLaunchTest.kt`、`app/src/test/java/com/uniteduone/launcher/ChannelPermissionTest.kt`

**Interfaces:**
- Consumes: Task 7 `ChannelSource.PERMISSION`、`Apps.launch`。
- Produces:
  - `enum class PermissionResult { GRANTED, DENIED, DENIED_PERMANENTLY }`、`internal fun permissionResult(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, deniedBefore: Boolean): PermissionResult`
  - `data class ChannelEnv(val revision: Int = 0, val requestPermission: ((PermissionResult) -> Unit) -> Unit = { it(PermissionResult.DENIED) }, val openPermissionSettings: () -> Unit = {})`、`val LocalChannelEnv: ProvidableCompositionLocal<ChannelEnv>`
  - `object ChannelLaunch { fun open(ctx: Context, pkg: String, intentUri: String?): Boolean }`
  - `internal fun launchFlags(flags: Int): Int`、`internal fun launchTargetAllowed(target: String?, publisher: String, self: String): Boolean`
  - `internal const val CHANNELS_DEBOUNCE_MS = 500L`
  - MainActivity:`private var channelsRevision by mutableStateOf(0)`(驱动 `ChannelCache.refresh`,也经 `LocalChannelEnv.revision` 给选频道页重查授权)、`private fun requestTvListings(onResult: (PermissionResult) -> Unit)`、`private fun openTvListingsSettings()`。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/ChannelLaunchTest.kt`:

```kotlin
package com.uniteduone.launcher

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R164:节目的 `intent_uri` 是别的应用写的,桌面拿自己的身份去启动它。两条防线:去掉一切 URI 授权位
 * (否则一个应用能借桌面之手把 UnitedU 的 FileProvider 里的文件授权出去),目标只许是发布方自己的包。
 */
class ChannelLaunchTest {
    @Test fun grantFlagsAreStrippedAndNewTaskAdded() {
        val evil = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK, launchFlags(evil))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launchFlags(0))
    }

    @Test fun onlyThePublishersOwnActivitiesMayBeLaunched() {
        assertTrue(launchTargetAllowed("com.cibn.tv", "com.cibn.tv", "com.uniteduone.launcher"))
        assertFalse("指向别的包", launchTargetAllowed("com.evil", "com.cibn.tv", "com.uniteduone.launcher"))
        assertFalse("指向桌面自己", launchTargetAllowed("com.uniteduone.launcher", "com.uniteduone.launcher", "com.uniteduone.launcher"))
        assertFalse("解析不到", launchTargetAllowed(null, "com.cibn.tv", "com.uniteduone.launcher"))
    }
}
```

`app/src/test/java/com/uniteduone/launcher/ChannelPermissionTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * owner 裁定(2026-10-09):授权申请回来是拒绝时,只有「永久拒绝」(系统这次没弹窗)才自动跳系统设置;
 * 用户在窗里点了拒绝、或按返回 / 主页键关掉窗,都留在原地。判据 = 申请前后两次 shouldShowRequestPermissionRationale。
 */
class ChannelPermissionTest {
    private val G = PermissionResult.GRANTED
    private val D = PermissionResult.DENIED
    private val P = PermissionResult.DENIED_PERMANENTLY

    @Test fun grantedWinsWhateverTheRationale() {
        assertEquals(G, permissionResult(granted = true, rationaleBefore = false, rationaleAfter = false, deniedBefore = true))
        assertEquals(G, permissionResult(true, true, false, false))
    }

    @Test fun theDialogWasShownSoStay() {
        assertEquals("第一次点拒绝:申请后 rationale 变真", D, permissionResult(false, false, true, false))
        assertEquals("拒绝过一次、这次按返回关窗:前后都真", D, permissionResult(false, true, true, true))
        assertEquals("第二次点拒绝(弹了窗,Android 11+ 此后不再询问):前真后假", D, permissionResult(false, true, false, true))
    }

    @Test fun neverDeniedAndDismissedIsNotPermanent() {
        // 从没拒绝过时按返回 / 主页键关掉窗,前后都是 false,与「系统没弹窗」长得一样——靠 deniedBefore 分开
        assertEquals(D, permissionResult(false, false, false, deniedBefore = false))
    }

    @Test fun deniedBeforeAndNoDialogIsPermanent() {
        assertEquals(P, permissionResult(false, false, false, deniedBefore = true))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelLaunchTest*' --tests '*ChannelPermissionTest*'`
Expected: 编译失败,`Unresolved reference: launchFlags` / `permissionResult`。

- [ ] **Step 3: 实现 `ChannelLaunch.kt` 与 `ChannelEnv.kt`**

`app/src/main/java/com/uniteduone/launcher/ChannelLaunch.kt`:

```kotlin
package com.uniteduone.launcher

import android.content.Context
import android.content.Intent

private const val GRANT_MASK = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

/** 去掉 URI 授权位、加上 NEW_TASK(桌面不是 Activity 栈的一部分)。 */
internal fun launchFlags(flags: Int): Int = (flags and GRANT_MASK.inv()) or Intent.FLAG_ACTIVITY_NEW_TASK

/** 只许启动发布方自己包里的 Activity;解析不到或指向桌面自己 → 不许。 */
internal fun launchTargetAllowed(target: String?, publisher: String, self: String): Boolean =
    target != null && target == publisher && target != self

/**
 * R164:首页频道卡的确定键(spec §2.3):`Intent.parseUri(intent_uri, URI_INTENT_SCHEME)` + NEW_TASK;
 * 没有 `intent_uri`、解析失败、目标不合规([launchTargetAllowed])或启动失败 → 打开发布方应用([Apps.launch]);
 * 返回 false = 都起不来,调用方弹 `toast_cant_open_app`。
 */
object ChannelLaunch {
    fun open(ctx: Context, pkg: String, intentUri: String?): Boolean {
        val intent = intentUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
        if (intent != null) {
            intent.selector = null
            intent.clipData = null
            intent.flags = launchFlags(intent.flags)
            val target = runCatching { ctx.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName }.getOrNull()
            if (launchTargetAllowed(target, pkg, ctx.packageName) && runCatching { ctx.startActivity(intent) }.isSuccess) return true
        }
        return Apps.launch(ctx, pkg)
    }
}
```

`app/src/main/java/com/uniteduone/launcher/ChannelEnv.kt`:

```kotlin
package com.uniteduone.launcher

import androidx.compose.runtime.compositionLocalOf

/** ContentObserver 去抖时长(spec §3.3:500 ms 后 `channelsRevision++`)。 */
internal const val CHANNELS_DEBOUNCE_MS = 500L

/** MainActivity 记「用户点过拒绝」的 SharedPreferences 文件名与键([permissionResult] 的 deniedBefore)。 */
internal const val TV_LISTINGS_MARKS = "channel-permission"
internal const val TV_LISTINGS_DENIED = "deniedBefore"

/**
 * R164:频道相关的运行时环境,MainActivity 在 setContent 顶层提供(编辑页的频道架子、选频道页读它;首页直接收参数)。
 * - [revision]:MainActivity 的 `channelsRevision`(TvProvider 变化去抖、授权结果、onResume 都 ++),读频道数据的效果拿它当 key。
 * - [requestPermission]:弹系统授权窗,结果([PermissionResult])回调到主线程。
 * - [openPermissionSettings]:跳本应用的系统详情页(拒绝过 / 「不再询问」后只能从那里开)。
 */
data class ChannelEnv(
    val revision: Int = 0,
    val requestPermission: (onResult: (PermissionResult) -> Unit) -> Unit = { it(PermissionResult.DENIED) },
    val openPermissionSettings: () -> Unit = {},
)

/** 一次授权申请的结果。只有 [DENIED_PERMANENTLY] 时调用方才自动跳系统设置(owner 裁定 2026-10-09)。 */
enum class PermissionResult { GRANTED, DENIED, DENIED_PERMANENTLY }

/**
 * owner 裁定(2026-10-09):拒绝后只有「永久拒绝」——系统这次**没弹窗**、直接回拒——才算 [PermissionResult.DENIED_PERMANENTLY];
 * 用户在窗里点了拒绝(哪怕是第二次、此后不再询问)或按返回 / 主页键关掉窗,都是 [PermissionResult.DENIED](留在原地)。
 * 标准的前后对照:申请前后 `shouldShowRequestPermissionRationale` 都是 false 且结果是拒绝 = 没弹窗。
 * 但「从没拒绝过、这次按返回关窗」前后也都是 false(返回不算拒绝),所以另要 [deniedBefore]:以前见过 rationale 为真
 * (= 用户点过拒绝;MainActivity 存在一份小 SharedPreferences 里,授权到手时清掉)。拿不准时一律算 DENIED——
 * 留在原地、页上有「去系统设置开启」,比误跳系统设置安全。
 */
internal fun permissionResult(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, deniedBefore: Boolean): PermissionResult = when {
    granted -> PermissionResult.GRANTED
    !rationaleBefore && !rationaleAfter && deniedBefore -> PermissionResult.DENIED_PERMANENTLY
    else -> PermissionResult.DENIED
}

val LocalChannelEnv = compositionLocalOf { ChannelEnv() }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelLaunchTest*' --tests '*ChannelPermissionTest*'`
Expected: 两个测试类 PASS。

- [ ] **Step 5: MainActivity 接线**

字段区(`private var revision by mutableStateOf(0)` 下面)加:

```kotlin
    /**
     * R164:频道数据的版本号——TvProvider 有变化(ContentObserver,500 ms 去抖)、授权结果回来、onResume 时 ++。
     * 只重读频道内容,不重读 layout.json 与应用横幅(那是 [revision] 的事,spec §3.3)。
     */
    private var channelsRevision by mutableStateOf(0)
    private var tvListingsCallback: ((PermissionResult) -> Unit)? = null
    /** 这次申请前的 shouldShowRequestPermissionRationale(前后对照判「系统有没有弹窗」,见 [permissionResult])。 */
    private var tvListingsRationaleBefore = false
    private lateinit var tvListingsRequest: androidx.activity.result.ActivityResultLauncher<String>
    private var channelsBump: kotlinx.coroutines.Job? = null
    private var tvObserverOn = false
    private val tvObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = bumpChannelsSoon()
    }
```

`onCreate` 里 `super.onCreate(savedInstanceState)` 之后紧接着加(必须早于 STARTED,spec §3.3「在 MainActivity.onCreate 注册」):

```kotlin
        // R164:本应用第一处运行时权限。结果回来 channelsRevision++(编辑页 / 选频道页 / 首页重读),focusNonce++(系统授权窗
        // 盖过来时本页 ON_PAUSE 冻结了焦点,回来要接回)。
        tvListingsRequest = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            channelsRevision++
            focusNonce++
            val after = shouldShowRequestPermissionRationale(ChannelSource.PERMISSION)
            val marks = getSharedPreferences(TV_LISTINGS_MARKS, MODE_PRIVATE)
            val result = permissionResult(granted, tvListingsRationaleBefore, after, marks.getBoolean(TV_LISTINGS_DENIED, false))
            // 「点过拒绝」的记号:见过 rationale 为真就记下,授权到手就清(之后被自动收回 / 清标记时不会把一次返回关窗误判成永久拒绝)。
            // 单写者(主线程)的小 SharedPreferences,同 RelaunchMarks,不是落盘铁律管的多写者状态文件。
            when {
                granted -> marks.edit().remove(TV_LISTINGS_DENIED).commit()
                tvListingsRationaleBefore || after -> marks.edit().putBoolean(TV_LISTINGS_DENIED, true).commit()
            }
            tvListingsCallback?.invoke(result)
            tvListingsCallback = null
        }
        ensureTvObserver()
        // owner 裁定(2026-10-08):频道数据只有一份进程级缓存 ChannelCache(Task 7),首页 / 编辑页 / 选频道页都读它。
        // channelsRevision 每变一次(TvProvider 去抖、授权结果、onResume)后台重读一次;snapshotFlow 先发当前值 = 启动时读一次。
        // 同 warmAppsPage 的写法(snapshotFlow + conflate:连着变几次只读最后一次)。
        lifecycleScope.launch {
            snapshotFlow { channelsRevision }.conflate().collect { ChannelCache.refresh(applicationContext) }
        }
```

类里(`onResume` 之前)加三个方法:

```kotlin
    /** R164:500 ms 去抖后 channelsRevision++(应用同步频道时会连写几十行)。 */
    private fun bumpChannelsSoon() {
        channelsBump?.cancel()
        channelsBump = lifecycleScope.launch {
            kotlinx.coroutines.delay(CHANNELS_DEBOUNCE_MS)
            channelsRevision++
        }
    }

    /** 监听 `content://android.media.tv`(含子路径)。注册失败(provider 不在、厂商改过)只是不自动刷新,onResume 再试。 */
    private fun ensureTvObserver() {
        if (tvObserverOn) return
        tvObserverOn = runCatching {
            contentResolver.registerContentObserver(android.net.Uri.parse("content://android.media.tv"), true, tvObserver)
        }.isSuccess
    }

    /** 弹系统授权窗;已经有权限时直接回调 GRANTED。结果经 [tvListingsRequest] 回到主线程;起不来按 DENIED(留在原地)。 */
    private fun requestTvListings(onResult: (PermissionResult) -> Unit) {
        if (ChannelSource.hasPermission(this)) { onResult(PermissionResult.GRANTED); return }
        tvListingsCallback = onResult
        tvListingsRationaleBefore = shouldShowRequestPermissionRationale(ChannelSource.PERMISSION)
        runCatching { tvListingsRequest.launch(ChannelSource.PERMISSION) }.onFailure {
            tvListingsCallback = null
            onResult(PermissionResult.DENIED)
        }
    }

    /** 跳本应用的系统详情页(拒绝过之后只能从那里开);回来由 onResume 的 channelsRevision++ 重读授权。 */
    private fun openTvListingsSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(intent) }.isFailure) toast(getString(R.string.toast_system_settings_unavailable))
    }
```

`onResume` 里 `focusNonce++` 之后加:

```kotlin
        // R164:授权可能在系统设置里被改(撤销会杀进程,开启不会);频道数据重读一次。没授权时 snapshot 只查一次
        // checkSelfPermission 就返回;有授权时不论有没有频道行都整份重读(缓存也供选频道页用),内容相同则不通知任何人。
        ensureTvObserver()
        channelsRevision++
```

`onDestroy` 里 `runCatching { unregisterReceiver(packageChanges) }` 之后加:

```kotlin
        if (tvObserverOn) runCatching { contentResolver.unregisterContentObserver(tvObserver) }
        channelsBump?.cancel()
```

`setContent` 里的 `CompositionLocalProvider(`(约 630 行)多提供一项:

```kotlin
                LocalChannelEnv provides remember(channelsRevision) {
                    ChannelEnv(channelsRevision, ::requestTvListings, ::openTvListingsSettings)
                },
```

- [ ] **Step 6: 编译 + 模拟器上核对授权窗能弹、能收回**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && adb devices
# 确认 emulator-5562 不在列表里(没人在用)后再起:
emulator -avd unitedu-tv-2 -port 5562 -no-snapshot -no-audio -gpu host &
adb -s emulator-5562 wait-for-device && adb -s emulator-5562 shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
gradle --no-daemon assembleRelease
adb -s emulator-5562 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5562 shell cmd package set-home-activity com.uniteduone.launcher/.MainActivity
adb -s emulator-5562 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.uniteduone.launcher/.MainActivity
adb -s emulator-5562 shell dumpsys package com.uniteduone.launcher | grep -A1 READ_TV_LISTINGS
```

Expected: `android.permission.READ_TV_LISTINGS: granted=false`(装上默认没授权);应用正常起来,`adb -s emulator-5562 logcat -d | grep -E "FATAL|ContentObserver"` 无崩溃。授权窗的实际弹出在 Task 15 / 16 验证(那时才有入口)。

- [ ] **Step 7: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/ChannelEnv.kt app/src/main/java/com/uniteduone/launcher/ChannelLaunch.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt app/src/test/java/com/uniteduone/launcher/ChannelLaunchTest.kt app/src/test/java/com/uniteduone/launcher/ChannelPermissionTest.kt && git commit -F - <<'EOF'
feat(channels): 授权请求、TvProvider 变化去抖、LocalChannelEnv、节目启动的两条防线(R164 §3.3)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 10: 首页纵向几何改成逐行累计 + 横向位移变宽版

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/HomeVertical.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/GtvLayout.kt`(`rowShiftX` 下面加重载)
- Test: `app/src/test/java/com/uniteduone/launcher/HomeVerticalTest.kt`

**Interfaces:**
- Consumes: `GtvLayout.rowPitch` / `cardHeight` / `appFocusOverflow` / `titleHeight` / `ROW_CARD_TOP` / `ROW_GAP` / `HOME_BOTTOM_MARGIN` / `CONTENT_KEYLINE` / `CARD_GAP`(都不改)。
- Produces:
  - `internal object ChannelRowLayout { CARD_HEIGHT = 110f; HEADER_LINE = 22f; HEADER_IDLE_ALPHA = 0.6f; INFO_TITLE_LINE = 21f; INFO_META_LINE = 16f; fun headerGap(): Float; fun infoGap(): Float; fun infoHeight(): Float; fun cardTop(): Float }`
  - `internal data class RowGeom(val pitch: Float, val cardTop: Float, val visibleBelow: Float)`
  - `internal fun appRowGeom(size: GtvCardSize, showTitles: Boolean): RowGeom`、`internal fun channelRowGeom(): RowGeom`
  - `internal class HomeVertical(geoms: List<RowGeom>, screenHeightDp: Float, fallback: RowGeom) { val rowsTop: Float; fun focusLine(row: Int): Float; fun restBlockTop(row: Int): Float; fun restCardTop(row: Int): Float; fun shiftY(activeRow: Int): Float; fun wallpaperAlpha(shiftDp: Float, perRow: Boolean = GtvLayout.WALLPAPER_DIM_PER_ROW): Float }`(最后一个给 MainActivity 的壁纸逐行压暗,替掉按统一 `rowPitch` 插值的 `homeWallpaperAlpha`)
  - `GtvLayout.rowShiftX(focusedIndex: Int, widths: List<Float>, screenWidthDp: Float): Float`

- [ ] **Step 1: 写失败的测试(先钉「全是应用行时逐像素不变」)**

`app/src/test/java/com/uniteduone/launcher/HomeVerticalTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R164 spec §4:首页纵向位移从「每行等高」改成「逐行高度累计」;应用行之间逐像素不变。 */
class HomeVerticalTest {
    private val sizes = GtvCardSize.values().toList()
    private val heights = listOf(540f, 720f)

    private fun appsOnly(n: Int, size: GtvCardSize, titles: Boolean, h: Float) =
        HomeVertical(List(n) { appRowGeom(size, titles) }, h, appRowGeom(size, titles))

    @Test fun appOnlyLayoutsArePixelIdenticalToToday() {
        for (size in sizes) for (titles in listOf(false, true)) for (h in heights) for (n in 1..5) {
            val v = appsOnly(n, size, titles, h)
            val tag = "$size titles=$titles h=$h n=$n"
            assertEquals("$tag rowsTop", GtvLayout.rowsTop(size, titles, h), v.rowsTop, 0.001f)
            assertEquals("$tag 顶栏 = 静止", 0f, v.shiftY(-1), 0.001f)
            for (r in 0 until n) {
                assertEquals("$tag 行 $r 静止卡顶", GtvLayout.restCardTop(r, size, titles, h), v.restCardTop(r), 0.001f)
                assertEquals("$tag 行 $r 位移", GtvLayout.rowShiftY(r, size, titles), v.shiftY(r), 0.001f)
                assertEquals("$tag 行 $r 焦点线", GtvLayout.focusLineCardTop(size, titles, h), v.focusLine(r), 0.001f)
            }
        }
    }

    @Test fun emptyHomeFallsBackToTheAppGeometry() {
        val v = HomeVertical(emptyList(), 540f, appRowGeom(GtvCardSize.MEDIUM, false))
        assertEquals(GtvLayout.rowsTop(GtvCardSize.MEDIUM, false, 540f), v.rowsTop, 0.001f)
        assertEquals(0f, v.shiftY(0), 0.001f)
    }

    @Test fun channelRowGeometryNumbers() {
        // 卡高 110、聚焦溢出 110 × 0.05 + 2 + 1.5 = 9;行头 22;卡下 9 + 21 + 16 = 46
        assertEquals(9f, ChannelRowLayout.headerGap(), 0.0001f)
        assertEquals(46f, ChannelRowLayout.infoHeight(), 0.0001f)
        val g = channelRowGeom()
        assertEquals(7f + 22f + 9f, g.cardTop, 0.0001f)
        assertEquals(2 * 7f + 22f + 9f + 110f + 46f + GtvLayout.ROW_GAP, g.pitch, 0.0001f)
        assertEquals(110f + 46f, g.visibleBelow, 0.0001f)
        assertEquals(540f - 32f - 156f, HomeVertical(listOf(g), 540f, g).focusLine(0), 0.0001f)
    }

    @Test fun focusRowCardTopAlwaysSitsOnItsOwnFocusLine() {
        val a = appRowGeom(GtvCardSize.MEDIUM, false)
        val c = channelRowGeom()
        val v = HomeVertical(listOf(a, c, a, c, c, a), 540f, a)
        for (r in 0..5) assertEquals("行 $r", v.focusLine(r), v.restCardTop(r) + v.shiftY(r), 0.001f)
    }

    @Test fun blocksStackByTheirOwnPitches() {
        val a = appRowGeom(GtvCardSize.LARGE, true)
        val c = channelRowGeom()
        val v = HomeVertical(listOf(c, a, c), 540f, a)
        assertEquals(v.restBlockTop(0) + c.pitch, v.restBlockTop(1), 0.001f)
        assertEquals(v.restBlockTop(1) + a.pitch, v.restBlockTop(2), 0.001f)
    }

    /** R52「静止只露行 0」在混排下照样成立:行 1 的整块(含频道行行头)静止时在屏外。 */
    @Test fun onlyRowZeroIsVisibleAtRestInMixedLayouts() {
        val c = channelRowGeom()
        for (size in sizes) for (titles in listOf(false, true)) {
            val a = appRowGeom(size, titles)
            for (pair in listOf(listOf(a, c), listOf(c, a), listOf(c, c))) {
                val v = HomeVertical(pair, 540f, a)
                assertTrue("$size titles=$titles ${pair.map { it === c }} 行 1 顶 ${v.restBlockTop(1)}", v.restBlockTop(1) >= 540f)
            }
        }
    }

    /** 壁纸逐行压暗(WALLPAPER_DIM_PER_ROW):全是应用行时与改前 `homeWallpaperAlpha(shift, rowPitch)` 逐值相同(含越过最后一行的弹簧过冲)。 */
    @Test fun wallpaperDimForAppOnlyLayoutsIsUnchanged() {
        for (size in sizes) for (titles in listOf(false, true)) for (n in 1..5) {
            val v = appsOnly(n, size, titles, 540f)
            val pitch = GtvLayout.rowPitch(size, titles)
            var s = 0f
            while (s <= 8 * pitch) {
                for (perRow in listOf(true, false)) for (sign in listOf(-1f, 1f))
                    assertEquals("$size titles=$titles n=$n s=$s perRow=$perRow", GtvLayout.homeWallpaperAlpha(sign * s, pitch, perRow), v.wallpaperAlpha(sign * s, perRow), 0.0001f)
                s += 7.3f
            }
        }
    }

    /** 混排:每行静止位移上 = 该位移的静止 alpha;两行之间按这次位移的进度线性插值(不按统一 rowPitch 折行)。 */
    @Test fun wallpaperDimInMixedLayoutsFollowsCumulativeTops() {
        val a = appRowGeom(GtvCardSize.MEDIUM, false)
        val c = channelRowGeom()
        val v = HomeVertical(listOf(a, c, a, c), 540f, a)
        for (r in 0..3) assertEquals("行 $r", GtvLayout.wallpaperAlpha(v.shiftY(r)), v.wallpaperAlpha(v.shiftY(r), perRow = true), 0.0001f)
        val mid = (v.shiftY(1) + v.shiftY(2)) / 2
        assertEquals((GtvLayout.wallpaperAlpha(v.shiftY(1)) + GtvLayout.wallpaperAlpha(v.shiftY(2))) / 2, v.wallpaperAlpha(mid, perRow = true), 0.0001f)
    }

    @Test fun widthListShiftEqualsTheUniformOneForEqualWidths() {
        for (size in sizes) for (f in 0 until 12) {
            val widths = List(12) { GtvLayout.cardWidth(size) }
            assertEquals("$size f=$f", GtvLayout.rowShiftX(f, size, 960f), GtvLayout.rowShiftX(f, widths, 960f), 0.001f)
        }
    }

    @Test fun widthListShiftForMixedPosters() {
        val widths = List(5) { 196f } + listOf(73f, 73f, 110f)
        assertEquals(0f, GtvLayout.rowShiftX(0, widths, 960f), 0f)
        // 58 + (980 + 146 + 110) + 20 × 7 + 9(110 的聚焦溢出)+ 58 − 960 = 541
        assertEquals(-541f, GtvLayout.rowShiftX(7, widths, 960f), 0.001f)
        assertEquals(0f, GtvLayout.rowShiftX(0, emptyList(), 960f), 0f)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*HomeVerticalTest*'`
Expected: 编译失败,`Unresolved reference: HomeVertical` 等。

- [ ] **Step 3: 实现**

`app/src/main/java/com/uniteduone/launcher/HomeVertical.kt`:

```kotlin
package com.uniteduone.launcher

/**
 * R164:首页频道行的纵向几何(dp)。数值出处 spec `2026-10-08-channel-rows-design.md` §4;字号走 `Type`
 * (行头 `Type.section` 17 sp / 行高 22,卡下标题 `Type.body` 14 sp / 21,元数据 `Type.caption` 12 sp / 16),
 * 行盒高就是那三个 lineHeight——改 Type 的行高要连这里一起改。HomeChannelRow.kt 照这些数画,单测 HomeVerticalTest 钉住。
 *
 * 一行的布局块(从上到下):上留白 [GtvLayout.ROW_CARD_TOP] → 行头 [HEADER_LINE] → [headerGap](= 聚焦溢出,放大 + 描边
 * 不压行头)→ 卡 [CARD_HEIGHT] → 卡下 [infoHeight](= 聚焦溢出 + 标题行 + 元数据行;非焦点行同样占位)→ 下留白 ROW_CARD_TOP;
 * 行距 [GtvLayout.ROW_GAP] 由外层 Column 的 spacedBy 给。
 */
internal object ChannelRowLayout {
    const val CARD_HEIGHT = 110f
    const val HEADER_LINE = 22f
    /** 非焦点行的行头不透明度(spec §4:白 60%;焦点行 100%)。 */
    const val HEADER_IDLE_ALPHA = 0.6f
    const val INFO_TITLE_LINE = 21f
    const val INFO_META_LINE = 16f
    fun headerGap(): Float = GtvLayout.appFocusOverflow(CARD_HEIGHT)
    fun infoGap(): Float = GtvLayout.appFocusOverflow(CARD_HEIGHT)
    fun infoHeight(): Float = infoGap() + INFO_TITLE_LINE + INFO_META_LINE
    /** 行布局块顶 → 卡顶。 */
    fun cardTop(): Float = GtvLayout.ROW_CARD_TOP + HEADER_LINE + headerGap()
}

/**
 * 一行的纵向几何:[pitch] = 布局块高 + 行距;[cardTop] = 块顶 → 卡顶;[visibleBelow] = 卡顶 → 「需要可见」的下沿
 * (应用行:卡 + 聚焦溢出 + 卡片标题;频道行:卡 + 卡下两行)。
 */
internal data class RowGeom(val pitch: Float, val cardTop: Float, val visibleBelow: Float)

internal fun appRowGeom(size: GtvCardSize, showTitles: Boolean): RowGeom = RowGeom(
    pitch = GtvLayout.rowPitch(size, showTitles),
    cardTop = GtvLayout.ROW_CARD_TOP,
    visibleBelow = GtvLayout.cardHeight(size) + GtvLayout.appFocusOverflow(GtvLayout.cardHeight(size)) +
        GtvLayout.titleHeight(size, showTitles),
)

internal fun channelRowGeom(): RowGeom = RowGeom(
    pitch = 2f * GtvLayout.ROW_CARD_TOP + ChannelRowLayout.HEADER_LINE + ChannelRowLayout.headerGap() +
        ChannelRowLayout.CARD_HEIGHT + ChannelRowLayout.infoHeight() + GtvLayout.ROW_GAP,
    cardTop = ChannelRowLayout.cardTop(),
    visibleBelow = ChannelRowLayout.CARD_HEIGHT + ChannelRowLayout.infoHeight(),
)

/**
 * R52 焦点线的逐行版本(R164)。每行有自己的焦点线([focusLine]:让这一行的「需要可见」下沿离屏底 [GtvLayout.HOME_BOTTOM_MARGIN]),
 * 行块按各自 pitch 累计排开;静止(焦点在顶栏或行 0)时行 0 卡顶 = 行 0 的焦点线;焦点在行 n 时整页位移 [shiftY](n),
 * 让行 n 的卡顶落在**行 n 自己的**焦点线上。全是应用行时 = `GtvLayout.rowsTop / restCardTop / rowShiftY`,逐像素相同
 * (HomeVerticalTest 钉住)。[fallback] 给「一行都没有」时用(空桌面,与改前一样按应用行算)。
 */
internal class HomeVertical(geoms: List<RowGeom>, private val screenHeightDp: Float, fallback: RowGeom) {
    private val g: List<RowGeom> = geoms.ifEmpty { listOf(fallback) }
    private val blockTops = FloatArray(g.size).also { tops ->
        var acc = 0f
        for (i in g.indices) { tops[i] = acc; acc += g[i].pitch }
    }
    private fun at(row: Int) = row.coerceIn(0, g.lastIndex)

    fun focusLine(row: Int): Float = screenHeightDp - GtvLayout.HOME_BOTTOM_MARGIN - g[at(row)].visibleBelow

    /** 装着全部行的 Column 的 `padding(top)`(静止态行 0 布局块顶)。 */
    val rowsTop: Float = focusLine(0) - g[0].cardTop

    fun restBlockTop(row: Int): Float = rowsTop + blockTops[at(row)]

    fun restCardTop(row: Int): Float = restBlockTop(row) + g[at(row)].cardTop

    /** 焦点在 [activeRow] 时的整页位移(dp,≤ 0);负值(顶栏)= 0。 */
    fun shiftY(activeRow: Int): Float {
        if (activeRow <= 0) return focusLine(0) - restCardTop(0)
        val r = at(activeRow)
        return focusLine(r) - restCardTop(r)
    }

    /**
     * 壁纸逐行压暗([GtvLayout.WALLPAPER_DIM_PER_ROW])的逐行累计版本,MainActivity 的壁纸层用它替掉 `homeWallpaperAlpha(shift, rowPitch)`:
     * |[shiftDp]| 落在哪两行的静止位移(-[shiftY])之间,就在这两行的静止 alpha(都取 [GtvLayout.wallpaperAlpha])之间线性插值;
     * 越过最后一行(弹簧过冲)按最后一行的 pitch 外推。全是应用行时 -shiftY(n) = n × rowPitch,与改前逐值相同(HomeVerticalTest)。
     */
    fun wallpaperAlpha(shiftDp: Float, perRow: Boolean = GtvLayout.WALLPAPER_DIM_PER_ROW): Float {
        if (!perRow) return GtvLayout.wallpaperAlpha(shiftDp)
        val d = kotlin.math.abs(shiftDp)
        fun lerp(s0: Float, s1: Float): Float {
            val a0 = GtvLayout.wallpaperAlpha(s0)
            val a1 = GtvLayout.wallpaperAlpha(s1)
            return if (s1 <= s0) a0 else a0 + (a1 - a0) * ((d - s0) / (s1 - s0))
        }
        for (i in 0 until g.lastIndex) {
            val s1 = -shiftY(i + 1)
            if (d < s1) return lerp(-shiftY(i), s1)
        }
        val sLast = -shiftY(g.lastIndex)
        val p = g.last().pitch
        if (p <= 0f) return GtvLayout.wallpaperAlpha(d)
        val n = kotlin.math.floor(((d - sLast) / p).coerceAtLeast(0f))
        return lerp(sLast + n * p, sLast + (n + 1f) * p)
    }
}
```

`GtvLayout.kt` 的 `rowShiftX(focusedIndex, size, screenWidthDp)` 下面加:

```kotlin
    /**
     * R164:同一条规则(焦点卡完全可见就不动,右缘含聚焦溢出超出右侧可视区才左移刚好这么多),卡宽逐张给(频道行按海报比例)。
     * 等宽时与上面那个版本逐值相同(HomeVerticalTest)。[focusedIndex] 夹到 [widths] 范围内;空表 = 0。
     */
    fun rowShiftX(focusedIndex: Int, widths: List<Float>, screenWidthDp: Float): Float {
        if (widths.isEmpty()) return 0f
        val f = focusedIndex.coerceIn(0, widths.lastIndex)
        var right = CONTENT_KEYLINE
        for (i in 0..f) right += widths[i]
        right += CARD_GAP * f + appFocusOverflow(widths[f])
        val overRight = right + CONTENT_KEYLINE - screenWidthDp
        return if (overRight > 0f) -overRight else 0f
    }
```

- [ ] **Step 4: 跑测试确认通过,并跑全部旧的几何测试**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*HomeVerticalTest*' --tests '*GtvLayoutTest*' --tests '*VerticalMotionTest*' --tests '*RowEnterTest*' --tests '*GtvGlowTest*'`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/HomeVertical.kt app/src/main/java/com/uniteduone/launcher/GtvLayout.kt app/src/test/java/com/uniteduone/launcher/HomeVerticalTest.kt && git commit -F - <<'EOF'
feat(channels): 首页纵向几何改为逐行累计(应用行逐像素不变)+ rowShiftX 变宽版(R164 §4)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---
### Task 11: 夹具发布方 APK `test.channels`(带代码)

**Files:**
- Create: `scripts/e2e/fixtures/channels/AndroidManifest.xml`
- Create: `scripts/e2e/fixtures/channels/src/test/channels/Publish.java`、`Cmd.java`、`Init.java`、`Play.java`、`Posters.java`
- Modify: `scripts/e2e/fixtures.py`(`import glob`、`channels()`、`packages()`、`build()`、`install()` 的 `skip`)

**Interfaces:**
- Consumes: `fixtures.py` 现有的 `run` / `sign` / `BT` / `JAR` / `APKS`。
- Produces(Task 12 的模拟器核对、Task 16 的 e2e、Task 17 的截图与性能都用它):
  - 包 `test.channels`(versionCode 1,应用名「E2E Channels」),`--user 0` 安装;
  - `INITIALIZE_PROGRAMS` 接收器 `.Init`:建频道「E2E Picks」(`internal_provider_id = e2e-picks`)+ 8 个节目;
  - 显式广播命令 `am broadcast -f 32 -n test.channels/.Cmd -a test.channels.<X>`:`PUBLISH`(8 个)、`SHRINK`(3 个)、`CLEAR`(0 个)、`REPUBLISH`(删频道再建,`_id` 变、key 不变)、`DROP`(删全部夹具频道)、`MANY`(5 个频道 `e2e-many-0..4`,各 12 个);
  - 8 个节目:标题 `Ocean Deep, City Lights, Desert Run, Night Train, Snow Peak, The Long Road, Paper Moon, Blue Album`,前 5 个 16:9 剧集(第 1 季第 1–5 集),6–7 是 2:3 电影(1 h 45 min),8 是 1:1 专辑;`weight = 1000 − n`;第 5 个(Snow Peak)海报是黑洞地址 `https://10.255.255.1/poster.jpg`,其余是本包 ContentProvider `content://test.channels.posters/<n>`;`intent_uri` 指向本包 `.Play` 并带 `p = n`;
  - `.Play` 启动时打日志 `CHFIX play p=<n>`;发布打 `CHFIX published ch=<id> n=<count>`、删频道打 `CHFIX dropped <key> ch=<id>`。

- [ ] **Step 1: 写清单与源码**

`scripts/e2e/fixtures/channels/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- R164 e2e 夹具:带代码的频道发布方(仿研究 §6 的探针)。由 scripts/e2e/fixtures.py 的 channels() 构建,不进 UnitedU 本体。 -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.channels" android:versionCode="1" android:versionName="1">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
  <uses-feature android:name="android.software.leanback" android:required="false"/>
  <!-- normal 级,装上即有;写自己包的频道 / 节目要它 -->
  <uses-permission android:name="com.android.providers.tv.permission.WRITE_EPG_DATA"/>
  <!-- 同为 normal;channelId() 要查自己的频道,照研究 §6 的探针 / 哔哩哔哩云视听两个都声明 -->
  <uses-permission android:name="com.android.providers.tv.permission.READ_EPG_DATA"/>
  <application android:label="E2E Channels">
    <activity android:name=".Play" android:exported="true" android:label="E2E Channels">
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
      </intent-filter>
    </activity>
    <receiver android:name=".Init" android:exported="true">
      <intent-filter><action android:name="android.media.tv.action.INITIALIZE_PROGRAMS"/></intent-filter>
    </receiver>
    <receiver android:name=".Cmd" android:exported="true"/>
    <provider android:name=".Posters" android:authorities="test.channels.posters" android:exported="true"/>
  </application>
</manifest>
```

`scripts/e2e/fixtures/channels/src/test/channels/Publish.java`:

```java
package test.channels;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

/** 写自己的预览频道与节目(TvProvider 只许改自己包的行)。preview 频道不需要 input_id(TvProvider 自己填空串)。 */
final class Publish {
    static final String TAG = "CHFIX";
    static final String KEY = "e2e-picks";
    static final String NAME = "E2E Picks";
    static final String[] TITLES = {"Ocean Deep", "City Lights", "Desert Run", "Night Train", "Snow Peak", "The Long Road", "Paper Moon", "Blue Album"};
    /** 海报比例码(TvContract.PreviewPrograms.ASPECT_RATIO_*):5 张 16:9、2 张 2:3、1 张 1:1。 */
    static final int[] ASPECT = {0, 0, 0, 0, 0, 4, 4, 3};

    private Publish() {}

    static int[] size(int n) {
        switch (ASPECT[n % 8]) {
            case 4: return new int[]{240, 360};
            case 3: return new int[]{360, 360};
            default: return new int[]{640, 360};
        }
    }

    static long channelId(Context c, String key) {
        Uri uri = TvContract.Channels.CONTENT_URI.buildUpon().appendQueryParameter("package", c.getPackageName()).build();
        String[] proj = {TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID};
        try (Cursor k = c.getContentResolver().query(uri, proj, null, null, null)) {
            while (k != null && k.moveToNext()) if (key.equals(k.getString(1))) return k.getLong(0);
        }
        return -1;
    }

    static long ensureChannel(Context c, String key, String name) {
        long id = channelId(c, key);
        if (id >= 0) return id;
        ContentValues v = new ContentValues();
        v.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
        v.put(TvContract.Channels.COLUMN_DISPLAY_NAME, name);
        v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, key);
        v.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, new Intent(c, Play.class).toUri(Intent.URI_INTENT_SCHEME));
        Uri u = c.getContentResolver().insert(TvContract.Channels.CONTENT_URI, v);
        return ContentUris.parseId(u);
    }

    /** 清空频道 [ch] 的节目,再写 [count] 个(序号从 [base] 起,海报 / 比例 / 类型按序号 % 8 轮换)。 */
    static void programs(Context c, long ch, int count, int base) {
        ContentResolver r = c.getContentResolver();
        r.delete(TvContract.buildPreviewProgramsUriForChannel(ch), null, null);
        for (int i = 0; i < count; i++) {
            int n = base + i;
            int k = n % 8;
            ContentValues p = new ContentValues();
            p.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, ch);
            p.put(TvContract.PreviewPrograms.COLUMN_TYPE, k < 5 ? TvContract.PreviewPrograms.TYPE_TV_EPISODE
                : (k < 7 ? TvContract.PreviewPrograms.TYPE_MOVIE : TvContract.PreviewPrograms.TYPE_ALBUM));
            p.put(TvContract.PreviewPrograms.COLUMN_TITLE, base == 0 ? TITLES[k] : TITLES[k] + " " + n);
            if (k < 5) {
                p.put(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, "1");
                p.put(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, String.valueOf(k + 1));
            } else {
                p.put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, 6_300_000);
            }
            p.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                k == 4 ? "https://10.255.255.1/poster.jpg" : "content://test.channels.posters/" + n);
            p.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, ASPECT[k]);
            p.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, new Intent(c, Play.class).putExtra("p", n).toUri(Intent.URI_INTENT_SCHEME));
            p.put(TvContract.PreviewPrograms.COLUMN_WEIGHT, 1000 - n);
            p.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, "p" + n);
            r.insert(TvContract.PreviewPrograms.CONTENT_URI, p);
        }
        Log.i(TAG, "published ch=" + ch + " n=" + count);
    }

    static void drop(Context c, String key) {
        long id = channelId(c, key);
        if (id >= 0) c.getContentResolver().delete(TvContract.buildChannelUri(id), null, null);
        Log.i(TAG, "dropped " + key + " ch=" + id);
    }
}
```

`scripts/e2e/fixtures/channels/src/test/channels/Cmd.java`:

```java
package test.channels;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * e2e 的遥控:am broadcast -f 32 -n test.channels/.Cmd -a test.channels.<X>(显式广播,不受后台限制)。
 * `-f 32` = FLAG_INCLUDE_STOPPED_PACKAGES:adb 装上、从没启动过的包处于 stopped 状态,广播默认不送(显式的也不送)。
 */
public class Cmd extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = String.valueOf(i.getAction());
        switch (a) {
            case "test.channels.PUBLISH": Publish.programs(c, Publish.ensureChannel(c, Publish.KEY, Publish.NAME), 8, 0); break;
            case "test.channels.SHRINK": Publish.programs(c, Publish.ensureChannel(c, Publish.KEY, Publish.NAME), 3, 0); break;
            case "test.channels.CLEAR": Publish.programs(c, Publish.ensureChannel(c, Publish.KEY, Publish.NAME), 0, 0); break;
            case "test.channels.REPUBLISH":
                Publish.drop(c, Publish.KEY);
                Publish.programs(c, Publish.ensureChannel(c, Publish.KEY, Publish.NAME), 8, 0);
                break;
            case "test.channels.DROP":
                Publish.drop(c, Publish.KEY);
                for (int k = 0; k < 5; k++) Publish.drop(c, "e2e-many-" + k);
                break;
            case "test.channels.MANY":
                for (int k = 0; k < 5; k++) Publish.programs(c, Publish.ensureChannel(c, "e2e-many-" + k, "Many " + k), 12, 8 + k * 12);
                break;
            default: break;
        }
        Log.i(Publish.TAG, "cmd " + a);
    }
}
```

`scripts/e2e/fixtures/channels/src/test/channels/Init.java`:

```java
package test.channels;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 桌面发来 INITIALIZE_PROGRAMS 才建频道(酷喵 / Netflix / YouTube 型,研究 §7)。 */
public class Init extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Log.i(Publish.TAG, "init " + i.getAction());
        Publish.programs(c, Publish.ensureChannel(c, Publish.KEY, Publish.NAME), 8, 0);
    }
}
```

`scripts/e2e/fixtures/channels/src/test/channels/Play.java`:

```java
package test.channels;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** 节目的 intent_uri 指到这里;e2e 读日志 CHFIX play p=<n> 认是哪个节目被启动。 */
public class Play extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = getIntent().getIntExtra("p", -1);
        Log.i(Publish.TAG, "play p=" + p);
        TextView t = new TextView(this);
        t.setText("Playing " + p);
        t.setTextSize(48);
        setContentView(t);
    }
}
```

`scripts/e2e/fixtures/channels/src/test/channels/Posters.java`:

```java
package test.channels;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** 本地海报:content://test.channels.posters/<n> → 按序号画一张纯色 PNG(比例同节目),不联网。 */
public class Posters extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int n;
        try { n = Integer.parseInt(uri.getLastPathSegment()); } catch (RuntimeException e) { throw new FileNotFoundException(String.valueOf(uri)); }
        File f = new File(getContext().getCacheDir(), "poster-" + n + ".png");
        if (!f.exists()) {
            int[] wh = Publish.size(n);
            Bitmap b = Bitmap.createBitmap(wh[0], wh[1], Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(Color.HSVToColor(new float[]{(n * 45f) % 360f, 0.6f, 0.8f}));
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            p.setTextSize(wh[1] / 6f);
            c.drawText(Publish.TITLES[n % 8], wh[1] / 12f, wh[1] - wh[1] / 8f, p);
            try (FileOutputStream out = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
```

- [ ] **Step 2: 改 `fixtures.py`**

文件头 `import os, subprocess, sys` 改成 `import glob, os, subprocess, sys`;模块文档串的清单里加一条:

```
- 频道发布方 test.channels(R164 j_channels):带代码(javac + d8),源码在 fixtures/channels/;INITIALIZE_PROGRAMS 时建频道「E2E Picks」,
  e2e 用 `am broadcast -f 32 -n test.channels/.Cmd -a test.channels.<PUBLISH|SHRINK|CLEAR|REPUBLISH|DROP|MANY>` 改内容。由 j_channels 自己 `--user 0` 装。
```

`media()` 之前加:

```python
CHANNELS_SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "channels")

def channels():
    """带代码的频道发布方 test.channels:javac → d8 → aapt2 link(只有清单)→ 把 classes.dex 塞进去 → 对齐签名。"""
    d = os.path.join(APKS, "test.channels"); os.makedirs(d, exist_ok=True)
    classes = os.path.join(d, "classes"); os.makedirs(classes, exist_ok=True)
    srcs = sorted(glob.glob(os.path.join(CHANNELS_SRC, "src", "test", "channels", "*.java")))
    run("javac", "--release", "11", "-cp", JAR, "-d", classes, *srcs)
    cls = sorted(glob.glob(os.path.join(classes, "test", "channels", "*.class")))
    run(f"{BT}/d8", "--lib", JAR, "--min-api", "26", "--output", d, *cls)
    run(f"{BT}/aapt2", "link", "-I", JAR, "--manifest", os.path.join(CHANNELS_SRC, "AndroidManifest.xml"), "-o", os.path.join(d, "u.apk"))
    run("zip", "-j", os.path.join(d, "u.apk"), os.path.join(d, "classes.dex"))
    sign(os.path.join(d, "u.apk"), os.path.join(APKS, "test.channels.apk"))
```

`packages()` 的返回值末尾加 `+ ["test.channels"]`;`build()` 里 `tvinput()` 之后加 `channels()`;`install` 的默认参数改成 `skip=("test.dummy.app18", "test.channels")`,并在函数上方注释补一句:「test.channels 由 j_channels 自己用 `--user 0` 装(卸载要发 FULLY_REMOVED,见 CLAUDE.md 模拟器坑)」。

- [ ] **Step 3: 构建并在模拟器上核对夹具能发布**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh
export DEV=emulator-5562   # Task 9 起的 unitedu-tv-2;没开就照 Task 9 Step 6 先起
python3 scripts/e2e/fixtures.py
adb -s $DEV install -r --user 0 /tmp/unitedu-e2e/apks/test.channels.apk
adb -s $DEV logcat -c
adb -s $DEV shell am broadcast -f 32 -n test.channels/.Cmd -a test.channels.PUBLISH
sleep 2; adb -s $DEV logcat -d -s CHFIX
```

Expected:`fixtures ok`;日志里有 `CHFIX published ch=<数字> n=8` 与 `CHFIX cmd test.channels.PUBLISH`。若 insert 抛 `IllegalArgumentException`(某些镜像要求 `input_id`),在 `ensureChannel` 里补一行 `v.put(TvContract.Channels.COLUMN_INPUT_ID, TvContract.buildInputId(new android.content.ComponentName(c, Cmd.class)));` 再构建,并把这一条写进 Task 18 的 WORKLOG。

- [ ] **Step 4: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add scripts/e2e/fixtures/channels scripts/e2e/fixtures.py && git commit -F - <<'EOF'
test(channels): e2e 夹具——带代码的频道发布方 test.channels(R164)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 12: 首页画频道行

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/HomeChannels.kt`(纯逻辑)
- Create: `app/src/main/java/com/uniteduone/launcher/HomeChannelRow.kt`(`ChannelRow`、`PosterCard`)
- Modify: `app/src/main/java/com/uniteduone/launcher/HomeScreen.kt`(参数、频道内容、`restoring` 声明位置、`rows`、`report`、还原效果、纵向几何、行渲染、`buildRows`)
- Modify: `app/src/main/java/com/uniteduone/launcher/MainActivity.kt`(HomeScreen 调用处约 765–795;长按识别约 1269–1280)
- Modify: `app/src/main/java/com/uniteduone/launcher/GtvTokens.kt`(`PosterFallback`)
- Modify: `app/src/main/res/values/strings.xml`、`values-en/strings.xml`、`values-zh-rTW/strings.xml`
- Test: `app/src/test/java/com/uniteduone/launcher/HomeChannelsTest.kt`

**Interfaces:**
- Consumes: Task 3 `Program` / `ChannelContent` / `MetaFormats` / `programSubtitle`;Task 4 `Row.isChannel` / `Row.cellCount`;Task 7 `ChannelCache.data`、`channelContentsFrom`、`Apps.labelOf`;Task 8 `PosterCache`;Task 9 `ChannelLaunch.open`;Task 10 `HomeVertical` / `appRowGeom` / `channelRowGeom` / `ChannelRowLayout` / `GtvLayout.rowShiftX(Int, List<Float>, Float)`。
- Produces:
  - `internal fun withChannelContent(rows: List<Row>, content: Map<ChannelRef, ChannelContent>): List<Row>`
  - `internal fun homeFocusCol(rows: List<Row>, row: Int, wanted: Int): Int`
  - `internal fun loadsPosters(row: Int, activeRow: Int): Boolean`
  - `internal fun homeTargetRow(rows: List<Row>, tgtLayoutRow: Int): Int`、`internal fun homeTargetCell(rows: List<Row>, tgtLayoutRow: Int, tgtCol: Int): Pair<Int, Int>`(owner 裁定:首页焦点目标按 `layoutRow` 认行)
  - `@Composable internal fun ChannelRow(...)`、`@Composable internal fun PosterCard(...)`(签名见 Step 5)
  - `HomeScreen(..., onFocusedChannel: (Boolean) -> Unit = {}, onVerticalGeometry: (HomeVertical?) -> Unit = {})`(后者给 MainActivity 的壁纸逐行压暗;频道内容直接 collect `ChannelCache.data`,不再收版本号参数)
  - 字符串 `channel_row_title`、`channel_meta_season_episode`、`channel_meta_episode`、`channel_meta_hours_minutes`、`channel_meta_minutes`。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/HomeChannelsTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R164 首页:哪些频道行画出来、焦点列号按「这一行的格数」夹、海报只加载焦点行 ± 1。 */
class HomeChannelsTest {
    private fun app(p: String) = AppEntry(packageName = p, label = p, card = null, isWide = false)
    private fun prog(id: Long) = Program(id, "t$id", null, null, null, 0, null, PosterAspect.R16_9, null, 0)
    private fun ref(n: Int) = ChannelRef("p$n", "k$n", "c$n")
    private fun chRow(n: Int, layoutRow: Int) = Row(apps = emptyList(), icon = CHANNEL_ROW_ICON, layoutRow = layoutRow, channel = ref(n), channelAppLabel = "App$n")

    /** Review Focus 2:没授权 / 找不到 / 没节目的频道行与空应用行一样不画;layoutRow 在过滤前就定好了。 */
    @Test fun permissionlessAndEmptyChannelRowsAreDropped() {
        val appA = Row(apps = listOf(app("a")), layoutRow = 0)
        val rows = listOf(appA, chRow(1, 1), chRow(2, 2), chRow(3, 3), chRow(4, 4), Row(apps = emptyList(), layoutRow = 5))
        val content = mapOf(
            ref(2) to ChannelContent.NeedsPermission,
            ref(3) to ChannelContent.Missing,
            ref(4) to ChannelContent.Ready(listOf(prog(1), prog(2))),
        )
        val shown = withChannelContent(rows, content)
        assertEquals(listOf(0, 4), shown.map { it.layoutRow })
        assertSame(appA, shown[0])
        assertEquals(listOf(1L, 2L), shown[1].programs.map { it.id })
        assertEquals("App4", shown[1].channelAppLabel)
    }

    @Test fun everythingDeniedLeavesOnlyAppRows() {
        val rows = listOf(Row(apps = listOf(app("a")), layoutRow = 0), chRow(1, 1))
        assertEquals(listOf(0), withChannelContent(rows, mapOf(ref(1) to ChannelContent.NeedsPermission)).map { it.layoutRow })
    }

    /** Review Focus 4:焦点在第 8 张,应用把节目删到 3 张 → 目标列夹到这一行的最后一张(不是行首、不是别的行)。 */
    @Test fun shrinkingRowClampsTheFocusColumnToItsLastCard() {
        val before = listOf(Row(apps = listOf(app("a")), layoutRow = 0), chRow(1, 1).copy(programs = (1L..8L).map { prog(it) }))
        assertEquals(7, homeFocusCol(before, 1, 7))
        val after = listOf(before[0], before[1].copy(programs = (1L..3L).map { prog(it) }))
        assertEquals(2, homeFocusCol(after, 1, 7))
        assertEquals(0, homeFocusCol(after, 0, 7))
        assertEquals("行不在了按 0", 0, homeFocusCol(after, 5, 3))
    }

    @Test fun postersLoadOnlyNearTheFocusRow() {
        assertEquals(listOf(false, true, true, true, false), (0..4).map { loadsPosters(it, 2) })
        assertTrue(loadsPosters(1, 0))
        assertFalse(loadsPosters(2, 0))
    }

    // ---- owner 裁定(2026-10-08):焦点目标按 layoutRow 认行,画出来的行数变了不换行、不清列 ----
    private fun appRow(lr: Int, vararg pkgs: String) = Row(apps = pkgs.map { app(it) }, layoutRow = lr)
    private val ready = chRow(1, 1).copy(programs = (1L..4L).map { prog(it) })

    /** 焦点在频道行下面的应用行,发布方把频道清空 → 频道行不画了,目标仍是同一个 layout 行的同一张卡(画出来的行号 2 → 1)。 */
    @Test fun channelRowAboveDisappearingKeepsTheSameCard() {
        val before = listOf(appRow(0, "a"), ready, appRow(2, "c", "d", "e"))
        assertEquals(2 to 1, homeTargetCell(before, tgtLayoutRow = 2, tgtCol = 1))
        val after = listOf(before[0], before[2])
        assertEquals(1 to 1, homeTargetCell(after, tgtLayoutRow = 2, tgtCol = 1))
    }

    /** 冷启动节目晚到:频道行出现在焦点行上面 → 目标跟着同一个 layout 行往下挪一格,不跳到新冒出来的频道行。 */
    @Test fun channelRowAppearingAboveKeepsTheSameCard() {
        val before = listOf(appRow(0, "a"), appRow(2, "c", "d"))
        assertEquals(1 to 1, homeTargetCell(before, 2, 1))
        val after = listOf(before[0], ready, before[1])
        assertEquals(2 to 1, homeTargetCell(after, 2, 1))
    }

    @Test fun targetRowGoneFallsToTheRowThatTookItsPlace() {
        val rows = listOf(appRow(0, "a"), appRow(3, "c", "d"), appRow(4, "x"))
        assertEquals("layout 行 2 没画 → 补上它位置的行 3", 1, homeTargetRow(rows, 2))
        assertEquals("下面没有了 → 最后一行", 2, homeTargetRow(rows, 9))
        assertEquals("还没定目标(-1)→ 第一行", 0, homeTargetRow(rows, -1))
        assertEquals(0, homeTargetRow(emptyList(), 2))
        assertEquals("列号夹到落点那一行的格数", 2 to 0, homeTargetCell(rows, 4, 5))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*HomeChannelsTest*'`
Expected: 编译失败,`Unresolved reference: withChannelContent` 等。

- [ ] **Step 3: 实现纯逻辑**

`app/src/main/java/com/uniteduone/launcher/HomeChannels.kt`:

```kotlin
package com.uniteduone.launcher

import kotlin.math.abs

/**
 * 首页要画的行(R164):应用行要有卡;频道行要有内容([ChannelContent.Ready])——没授权、找不到、没节目的频道行与空应用行
 * 一样整行不画(spec §2.3)。
 *
 * ⚠️ 这个过滤**同时是焦点的不变量**(原来写在 buildRows 末尾,R164 挪到这里):upTarget / downTarget 指向相邻行的 rowFocus,
 * 而 rowFocus 只挂在画出来的卡上;哪天想「空行也显示出来」,那些 requester 就会挂空,focusProperties 给出非 Default 的
 * requester 会短路几何搜索——按上 / 下将完全没反应。
 */
internal fun withChannelContent(rows: List<Row>, content: Map<ChannelRef, ChannelContent>): List<Row> =
    rows.mapNotNull { r ->
        val ref = r.channel ?: return@mapNotNull r.takeIf { it.apps.isNotEmpty() }
        val ready = content[ref] as? ChannelContent.Ready ?: return@mapNotNull null
        if (ready.programs.isEmpty()) null else r.copy(programs = ready.programs)
    }

/** 焦点目标列夹到第 [row] 行的格数以内(应用行按应用数、频道行按节目数;行不在了 → 0)。还原效果的「目标」与 requester 挂点共用它(铁律 2)。 */
internal fun homeFocusCol(rows: List<Row>, row: Int, wanted: Int): Int =
    wanted.coerceIn(0, ((rows.getOrNull(row)?.cellCount ?: 1) - 1).coerceAtLeast(0))

/** 海报只加载焦点行与上下各一行(spec §3.3);其余行先画占位底色,进入范围再加载。 */
internal fun loadsPosters(row: Int, activeRow: Int): Boolean = abs(row - activeRow) <= 1

/**
 * **owner 裁定(2026-10-08)**:首页焦点目标的行按 **layout.json 行号**([Row.layoutRow])认,不按「画出来的第几行」。
 * 频道行会因为没内容 / 没授权 / 节目晚到而整行出现或消失,画出来的行号随之整体挪一格;按画出来的行号记目标的话,
 * 焦点会被还原到别的行上。返回 [tgtLayoutRow] 此刻画在第几行:那一行没画 → 它下面第一行(补上它位置的那一行),
 * 再没有 → 最后一行;还没定目标(< 0)或一行都没有 → 0。
 */
internal fun homeTargetRow(rows: List<Row>, tgtLayoutRow: Int): Int {
    if (rows.isEmpty() || tgtLayoutRow < 0) return 0
    val exact = rows.indexOfFirst { it.layoutRow == tgtLayoutRow }
    if (exact >= 0) return exact
    val below = rows.indexOfFirst { it.layoutRow > tgtLayoutRow }
    return if (below >= 0) below else rows.lastIndex
}

/** 目标格 = ([homeTargetRow], [tgtCol] 夹到那一行的格数)。还原效果的退出判据与 requester 挂点用同一个夹取(铁律 2)。 */
internal fun homeTargetCell(rows: List<Row>, tgtLayoutRow: Int, tgtCol: Int): Pair<Int, Int> {
    val r = homeTargetRow(rows, tgtLayoutRow)
    return r to homeFocusCol(rows, r, tgtCol)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*HomeChannelsTest*'`
Expected: PASS。

- [ ] **Step 5: 文案、底色、频道行组件**

三份 `strings.xml` 末尾(`</resources>` 之前)各加五条:

`app/src/main/res/values/strings.xml`:

```xml
    <!-- R164 首页频道行:行头「应用名 · 频道名」;焦点行卡下第二行的元数据 -->
    <string name="channel_row_title">%1$s · %2$s</string>
    <string name="channel_meta_season_episode">第 %1$s 季 · 第 %2$s 集</string>
    <string name="channel_meta_episode">第 %1$s 集</string>
    <string name="channel_meta_hours_minutes">%1$d 小时 %2$d 分钟</string>
    <string name="channel_meta_minutes">%1$d 分钟</string>
```

`app/src/main/res/values-en/strings.xml`:

```xml
    <!-- R164 home channel rows: header "App · Channel"; second line under cards in the focused row -->
    <string name="channel_row_title">%1$s · %2$s</string>
    <string name="channel_meta_season_episode">S%1$s · E%2$s</string>
    <string name="channel_meta_episode">Episode %1$s</string>
    <string name="channel_meta_hours_minutes">%1$d h %2$d min</string>
    <string name="channel_meta_minutes">%1$d min</string>
```

`app/src/main/res/values-zh-rTW/strings.xml`:

```xml
    <!-- R164 首頁頻道列:列頭「應用程式名稱 · 頻道名稱」;焦點列卡片下第二行的資訊 -->
    <string name="channel_row_title">%1$s · %2$s</string>
    <string name="channel_meta_season_episode">第 %1$s 季 · 第 %2$s 集</string>
    <string name="channel_meta_episode">第 %1$s 集</string>
    <string name="channel_meta_hours_minutes">%1$d 小時 %2$d 分鐘</string>
    <string name="channel_meta_minutes">%1$d 分鐘</string>
```

`GtvTokens.kt` 的 `object GtvTokens` 里(`MenuBg` 之后)加:

```kotlin
    /** R164:频道海报没图 / 加载失败 / 还没进加载范围时的卡底(深灰,上面写节目标题;照 Google TV 无图卡)。 */
    val PosterFallback = Color(0xFF26282C)
```

`app/src/main/java/com/uniteduone/launcher/HomeChannelRow.kt`:

```kotlin
package com.uniteduone.launcher

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row as ComposeRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults

/**
 * R164 首页频道行(spec `2026-10-08-channel-rows-design.md` §2.3、§4;R164-4)。与 [CategoryRow] 同一套骨架:
 * 不可滚动的 Row + `wrapContentWidth(unbounded = true)` + 自算 `offset`(铁律 1),requester 挂在目标格(铁律 3),
 * 得失焦点都上报(铁律 4);行的图层四边撑大柔光那么多(R129f,见 CategoryRow 的注释)。不同处:
 * 行头「应用名 · 频道名」在卡片上方、左边距不画行图标;卡宽按海报比例([PosterAspect.widthDp]),横向位移用变宽版
 * [GtvLayout.rowShiftX];焦点行每张卡下两行字,非焦点行同样占位(版面不跳);长按不做事(MainActivity 吞掉)。
 */
@Composable
internal fun ChannelRow(
    row: Row,
    firstCard: FocusRequester?,
    rowRequester: FocusRequester?,
    isLastRow: Boolean,
    upTarget: FocusRequester?,
    downTarget: FocusRequester?,
    targetIndex: Int,
    landingShiftsPage: Boolean,
    isFocusRow: Boolean,
    loadPosters: Boolean,
    rowAlpha: () -> Float,
    enterAlpha: () -> Float,
    onFocusChange: (Int, Boolean) -> Unit,
) {
    val ref = row.channel ?: return
    val ctx = LocalContext.current
    val showToast = LocalToast.current
    val appLabel = row.channelAppLabel.ifBlank { ref.pkg }
    val title = stringResource(R.string.channel_row_title, appLabel, ref.name)
    val formats = MetaFormats(
        seasonEpisode = stringResource(R.string.channel_meta_season_episode),
        episode = stringResource(R.string.channel_meta_episode),
        hoursMinutes = stringResource(R.string.channel_meta_hours_minutes),
        minutes = stringResource(R.string.channel_meta_minutes),
    )
    val widths = remember(row.programs) { row.programs.map { it.aspect.widthDp } }
    var focusedIndex by remember { mutableStateOf(0) }
    var landedWithShift by remember { mutableStateOf(false) }
    val focused = focusedIndex.coerceIn(0, row.programs.lastIndex.coerceAtLeast(0))
    val focusedNow by rememberUpdatedState(focused)
    val landingShiftsPageNow by rememberUpdatedState(landingShiftsPage)
    val widthsNow by rememberUpdatedState(widths)
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val xShift by animateDpAsState(
        targetValue = GtvLayout.rowShiftX(focused, widths, screenWidthDp).dp,
        animationSpec = Theme.browseShiftSpec(),
        label = "channelXShift",
    )
    val headerAlpha by animateFloatAsState(
        targetValue = if (isFocusRow) 1f else ChannelRowLayout.HEADER_IDLE_ALPHA,
        animationSpec = Theme.homeRowIconSpec(),
        label = "channelHeader",
    )
    val glowPad = GtvLayout.APP_FOCUS_GLOW_DP.dp
    Box(
        Modifier
            .layout { measurable, constraints ->
                val e = glowPad.roundToPx()
                val p = measurable.measure(constraints.offset(horizontal = 2 * e, vertical = 2 * e))
                layout((p.width - 2 * e).coerceAtLeast(0), (p.height - 2 * e).coerceAtLeast(0)) { p.place(-e, -e) }
            }
            .graphicsLayer { alpha = rowAlpha() * enterAlpha() }
            .padding(horizontal = glowPad, vertical = glowPad),
    ) {
        Column(Modifier.padding(top = GtvLayout.ROW_CARD_TOP.dp, bottom = GtvLayout.ROW_CARD_TOP.dp)) {
            BasicText(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Type.section.copy(color = Ink.Primary),
                modifier = Modifier
                    .padding(start = GtvLayout.CONTENT_KEYLINE.dp, end = GtvLayout.CONTENT_KEYLINE.dp)
                    .height(ChannelRowLayout.HEADER_LINE.dp)
                    .graphicsLayer { alpha = headerAlpha },
            )
            Spacer(Modifier.height(ChannelRowLayout.headerGap().dp))
            ComposeRow(
                horizontalArrangement = Arrangement.spacedBy(GtvLayout.CARD_GAP.dp),
                modifier = Modifier
                    // 铁律 1:必须 unbounded,否则屏幕宽用完之后的卡被量成 0 宽、永远聚焦不到
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset(x = xShift)
                    .padding(start = GtvLayout.CONTENT_KEYLINE.dp),
            ) {
                row.programs.forEachIndexed { index, p ->
                    PosterCard(
                        program = p,
                        widthDp = widths[index],
                        showInfo = isFocusRow,
                        subtitle = remember(p, formats) { programSubtitle(p, formats) },
                        loadPoster = loadPosters,
                        focusAfterShift = landedWithShift && index == focused,
                        onClick = {
                            if (!ChannelLaunch.open(ctx, ref.pkg, p.intentUri)) {
                                showToast(ctx.getString(R.string.toast_cant_open_app, appLabel), false)
                            }
                        },
                        modifier = Modifier
                            .let { m ->
                                val t = targetIndex.coerceIn(0, row.programs.lastIndex.coerceAtLeast(0))
                                if (rowRequester != null && index == t) m.focusRequester(rowRequester) else m
                            }
                            .let { m -> if (index == 0 && firstCard != null) m.focusRequester(firstCard) else m },
                        onFocusChange = { got ->
                            if (got) {
                                val xBefore = GtvLayout.rowShiftX(focusedNow, widthsNow, screenWidthDp)
                                val xAfter = GtvLayout.rowShiftX(index, widthsNow, screenWidthDp)
                                landedWithShift = landingShiftsPageNow || xAfter != xBefore
                                focusedIndex = index
                            }
                            onFocusChange(index, got)
                        },
                        isRowStart = index == 0,
                        isRowEnd = index == row.programs.lastIndex,
                        isLastRow = isLastRow,
                        upTarget = upTarget,
                        downTarget = downTarget,
                    )
                }
            }
        }
    }
}

/**
 * 一张节目海报卡(R164-4):高 [ChannelRowLayout.CARD_HEIGHT],宽 [widthDp];聚焦画法与应用卡同一套([gtvFocusFrameOverFade]);
 * 没图 / 失败 / 不在加载范围 → [GtvTokens.PosterFallback] 底 + 标题。卡下:[showInfo] 时标题 + [subtitle],否则等高占位。
 * 无障碍名 = 节目标题(e2e 靠它认焦点卡)。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PosterCard(
    program: Program,
    widthDp: Float,
    showInfo: Boolean,
    subtitle: String?,
    loadPoster: Boolean,
    focusAfterShift: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    onFocusChange: (Boolean) -> Unit,
    isRowStart: Boolean,
    isRowEnd: Boolean,
    isLastRow: Boolean,
    upTarget: FocusRequester?,
    downTarget: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    val fade = LocalCardFade.current
    val ctx = LocalContext.current
    val heightPx = with(LocalDensity.current) { ChannelRowLayout.CARD_HEIGHT.dp.roundToPx() }
    val uri = program.posterUri
    val poster by produceState(uri?.let { PosterCache.peek(it) }, uri, loadPoster) {
        // produceState 的状态不随 key 重建:同一格换了节目(重排 / 删减,卡片按位置组合)时先换成新 uri 的缓存(没有 = null),
        // 否则旧节目的海报会留在新节目的卡上
        value = uri?.let { PosterCache.peek(it) }
        if (uri != null && value == null && loadPoster) value = PosterCache.load(ctx, uri, heightPx)
    }
    val corner = GtvLayout.CARD_CORNER.dp
    val shape = RoundedCornerShape(corner)
    Column(Modifier.zIndex(if (focused) 1f else 0f).width(widthDp.dp)) {
        Card(
            onClick = onClick,
            onLongClick = null,
            modifier = modifier
                .gtvFocusFrameOverFade(focused, accent, corner, afterShift = focusAfterShift, fade = fade, restAlpha = fade.restAlpha)
                .size(widthDp.dp, ChannelRowLayout.CARD_HEIGHT.dp)
                .focusProperties {
                    if (isRowStart) left = FocusRequester.Cancel
                    if (isRowEnd) right = FocusRequester.Cancel
                    if (isLastRow) down = FocusRequester.Cancel else downTarget?.let { down = it }
                    upTarget?.let { up = it }
                }
                .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
                .semantics { contentDescription = program.title },
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(
                containerColor = GtvTokens.PosterFallback,
                contentColor = Ink.Primary,
                focusedContainerColor = GtvTokens.PosterFallback,
                pressedContainerColor = GtvTokens.PosterFallback,
            ),
            scale = CardDefaults.scale(focusedScale = 1f),
            border = CardDefaults.border(focusedBorder = Border.None, border = Border.None),
        ) {
            Box(Modifier.fillMaxSize().cardHairline(corner), contentAlignment = Alignment.Center) {
                val b = poster
                if (b != null) {
                    Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    BasicText(
                        text = program.title,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = Type.caption.copy(color = Ink.Primary, textAlign = TextAlign.Center),
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(ChannelRowLayout.infoGap().dp))
        if (showInfo) {
            BasicText(
                text = program.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Type.body.copy(color = Ink.Primary),
                modifier = Modifier.height(ChannelRowLayout.INFO_TITLE_LINE.dp),
            )
            BasicText(
                text = subtitle.orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Type.caption.copy(color = Ink.Primary.copy(alpha = 0.7f)),
                modifier = Modifier.height(ChannelRowLayout.INFO_META_LINE.dp),
            )
        } else {
            Spacer(Modifier.height((ChannelRowLayout.INFO_TITLE_LINE + ChannelRowLayout.INFO_META_LINE).dp))
        }
    }
}
```

- [ ] **Step 6: 改 `HomeScreen.kt`**

(a) 参数表最后(`onContentAlpha` 之后)加:

```kotlin
    /** R164:焦点在不在频道卡上(得到 / 失去都报)——MainActivity 据此把长按整下吞掉(频道卡长按不做事)。 */
    onFocusedChannel: (Boolean) -> Unit = {},
    /** R164:首页此刻的纵向几何(逐行累计),交给 MainActivity 的壁纸逐行压暗;null = 首页不在组合里。同 [onPageShiftState] 的上报方式。 */
    onVerticalGeometry: (HomeVertical?) -> Unit = {},
```

(b) 把 `var restoring by remember { mutableStateOf(false) }`(原在 `var tgtPill …` 之后)**整行挪到** `val titles = loaded?.second.orEmpty()` 之前(语义不变,只是要早于下面的频道效果声明);原位置删掉这一行。

(c) 紧接在 `val titles = loaded?.second.orEmpty()` 之后加:

```kotlin
    // R164 + owner 裁定(2026-10-08):频道内容来自进程级 ChannelCache(MainActivity 按 channelsRevision 刷新,Task 9),
    // 只认这份布局里的频道行;不重读 layout.json 与应用横幅(spec §3.3)。缓存内容相同的刷新不通知,这里什么都不发生。
    val channelRefs = remember(loaded) { loaded?.first.orEmpty().mapNotNull { it.channel } }
    /** 频道内容每真的换一次 +1:还原效果以它为 key 重跑,把焦点送回夹过的那一格。不是闩(铁律 7):只增不减,比对自然失效。 */
    var channelsLanding by remember { mutableStateOf(0) }
    var channelContent by remember { mutableStateOf<Map<ChannelRef, ChannelContent>>(emptyMap()) }
    LaunchedEffect(channelRefs) {
        // collect 在主线程(组合的调度器)上回调;下面三次写之间没有挂起点
        ChannelCache.data.collect { snap ->
            val next = channelContentsFrom(snap, channelRefs)
            if (next != channelContent) {
                // **同一次恢复里、中间没有挂起点**:先冻结目标、再换数据、再触发还原。应用在后台把焦点行的节目从 8 张删到 3 张、
                // 或焦点行上面的频道行整行出现 / 消失时,新数据落地那一帧焦点卡的节点被拆(或换了内容),Compose 把焦点塞给别处——
                // 那次上报此刻看到 restoring = true,改不了目标;还原效果随 channelsLanding 重跑,按 layoutRow 认行、按 homeFocusCol
                // 夹列,把焦点送回同一行的同一张(或末张)(铁律 3 / 5,Review Focus 4,owner 裁定)。
                // 不把「频道重读在途」整段算进 stale:那样 TvProvider 每变一次,用户那几十毫秒里的方向键都会被还原效果拽回去。
                restoring = true
                channelContent = next
                channelsLanding++
            }
        }
    }
```

(d) `rows` 的 `else -> loaded?.first.orEmpty()` 换成:

```kotlin
        else -> remember(loaded, channelContent) { withChannelContent(loaded?.first.orEmpty(), channelContent) }
```

(e) `cardAt` 下面加:

```kotlin
    /** R164:[cell] 是不是一张频道卡。同 [cardAt],按当前 rows 派生、不缓存。 */
    fun channelAt(cell: Pair<Int, Int>?): Boolean = cell != null && cell.first >= 0 && rows.getOrNull(cell.first)?.isChannel == true
```

`report` 的最后一行 `onFocusedCard(cardAt(focusedCell))` 之后加 `onFocusedChannel(channelAt(focusedCell))`;`LaunchedEffect(rows, focusedCell) { onFocusedCard(cardAt(focusedCell)) }` 换成:

```kotlin
    LaunchedEffect(rows, focusedCell) {
        onFocusedCard(cardAt(focusedCell))
        onFocusedChannel(channelAt(focusedCell))
    }
```

(f) 纵向几何:`val anchorTop = GtvLayout.rowsTop(cardSize, showTitles, screenHeightDp).dp` 与 `val shiftTarget = GtvLayout.rowShiftY(activeRowSafe, cardSize, showTitles)` 两行换成:

```kotlin
    // R164:逐行高度累计(频道行比应用行高);全是应用行时与改前逐像素相同(HomeVerticalTest)。
    val rowKinds = rows.map { it.isChannel }
    val vertical = remember(rowKinds, cardSize, showTitles, screenHeightDp) {
        HomeVertical(
            rowKinds.map { if (it) channelRowGeom() else appRowGeom(cardSize, showTitles) },
            screenHeightDp,
            appRowGeom(cardSize, showTitles),
        )
    }
    /** 行 [row] 静止时看得见的上沿:应用行 = 卡顶(与改前相同);频道行 = 行头顶。R53 淡出与 R129 进场都按它判。 */
    fun restVisibleTop(row: Int): Float =
        if (rows.getOrNull(row)?.isChannel == true) vertical.restBlockTop(row) + GtvLayout.ROW_CARD_TOP
        else vertical.restCardTop(row)
    val anchorTop = vertical.rowsTop.dp
    val shiftTarget = vertical.shiftY(activeRowSafe)
    // 壁纸逐行压暗按同一份累计几何插值(MainActivity 绘制阶段读;同一实例重复写不触发失效,只在行的种类 / 尺寸变时换)
    SideEffect { onVerticalGeometry(vertical) }
    DisposableEffect(Unit) { onDispose { onVerticalGeometry(null) } }
```

`startRowEnter` 里 `val top = GtvLayout.restCardTop(row, cardSize, showTitles, screenHeightDp) + shiftState.value.value` 换成 `val top = restVisibleTop(row) + shiftState.value.value`。

(g0) **owner 裁定(2026-10-08):目标按 `layoutRow` 认行,不随画出来的行数整表重建。**把

```kotlin
    val tgtIdx = remember(rows.size) {
        mutableStateListOf(*Array(rows.size.coerceAtLeast(1)) { 0 })
    }
    var tgtRow by remember { mutableStateOf(0) }
```

(连同上面那段「目标格一律从 (0,0) 起」的注释,改写成下面的 KDoc)换成:

```kotlin
    /**
     * 首页焦点目标的两个分量(铁律 5:与「当前位置」focusedCell 分开,`restoring` 期间——含 ON_PAUSE 起——冻结):
     * [tgtLayoutRow] = 目标行的 **layout.json 行号**([Row.layoutRow]),-1 = 还没定(还原效果第一次落地时定成第一行);
     * [tgtCol] = 目标列。**owner 裁定(2026-10-08)**:频道行会因为没内容 / 没授权 / 节目晚到整行出现或消失,画出来的行号整体挪一格,
     * 所以目标不能按「画出来的第几行」记,也不能随行数整表重建(原来 `tgtIdx = remember(rows.size)`,行数一变全清成 0)。
     * 画在第几行由 [homeTargetCell] 现算(行不在了 → 补上它位置的那一行)。上下键「同列落点」规则只读目标行的列,
     * 所以一个 [tgtCol] 就够(原来的 tgtIdx 每行一格,实际只读过 `tgtIdx[tgtRow]`)。
     * 已知边界:发布方被卸载、它的频道行从 layout.json 删掉时,下面各行的 layoutRow 会少 1;那一刻目标落到同一 layout 下标上
     * 的那一行(卸载是罕见操作,且焦点仍在首页、不丢)。
     */
    var tgtLayoutRow by remember { mutableStateOf(-1) }
    var tgtCol by remember { mutableStateOf(0) }
```

`mutableStateListOf` 的 import 若不再被用到就删掉。

(g) 还原效果:key 表末尾加 `channelsLanding`(只是触发量、不是守卫,不违反铁律 6):

```kotlin
    LaunchedEffect(focusNonce, rows.size, rows.isEmpty(), loaded != null, covered, frozen, moveTarget, moveLanding, channelsLanding) {
```

效果里落点写入的三行

```kotlin
            tgtPill = -1
            tgtRow = landing.pos.row
            if (landing.pos.row in tgtIdx.indices) tgtIdx[landing.pos.row] = landing.pos.col
```

换成(落点是画出来的行号,这时 rows 已是重读落地后的那份,换算成 layout 行号再记):

```kotlin
            tgtPill = -1
            rows.getOrNull(landing.pos.row)?.let { tgtLayoutRow = it.layoutRow }
            tgtCol = landing.pos.col
```

效果末段的

```kotlin
        val r = (moveTarget?.row ?: tgtRow).coerceIn(0, rowFocus.lastIndex)
        …(注释不动)
        val cap = rows.getOrNull(r)?.apps?.lastIndex?.coerceAtLeast(0) ?: 0
        val want = r to (moveTarget?.col ?: tgtIdx.getOrNull(r) ?: 0).coerceIn(0, cap)
```

换成:

```kotlin
        // 目标还没定(冷启动第一次落地):定成此刻的第一行。之后频道行在它上面出现 / 消失都按 layoutRow 跟着这一行走
        if (tgtLayoutRow < 0) tgtLayoutRow = rows.first().layoutRow
        // R164 + owner 裁定:行按 layoutRow 认、列按「这一行的格数」夹(频道行按节目数),与 requester 挂点同一个夹取口径(铁律 2)
        val want = if (moveTarget != null) moveTarget.row.coerceIn(0, rowFocus.lastIndex).let { it to homeFocusCol(rows, it, moveTarget.col) }
            else homeTargetCell(rows, tgtLayoutRow, tgtCol)
        val r = want.first.coerceIn(0, rowFocus.lastIndex)
```

(下面的 `while` 循环照旧请求 `rowFocus[r]`、退出判据 `focusedCell != want`。)

**同一个效果里三处 `restoring = false`(顶栏分支、空桌面分支、末尾)一律换成**
`restoring = !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)`(`lifecycle` 是上面 ON_PAUSE 观察者用的那个,声明在本效果之前)。
理由:R164 之前这个效果在后台几乎不会重跑;现在 `channelsLanding` 会在**本页 ON_PAUSE 期间**变——用户在发布方应用里看视频时,那个应用正好在后台同步频道,
ContentObserver → `channelsRevision++` → 缓存换内容 → 本效果重跑,末尾若无条件 `restoring = false`,就把 ON_PAUSE 起的冻结提前放掉了;
用户按返回回到首页那一刻 Compose 抢先给 (0,0) 的那次上报就会改写 `tgtLayoutRow` / `tgtCol`(铁律 5「从 ON_PAUSE 就冻结」,同设置外壳「末尾只在 RESUMED 时放开 restoring」)。
留在 true 不会卡死:回前台 `onResume` 的 `focusNonce++` 让本效果再跑一次,那时已是 RESUMED,照常放开。看门狗以 `restoring` 为 key 与守卫,期间让路(本来也收不到按键)。

看门狗里的

```kotlin
                rowFocus.getOrNull((moveTarget?.row ?: tgtRow).coerceIn(0, rowFocus.lastIndex)) ?: firstCard
```

换成

```kotlin
                rowFocus.getOrNull((moveTarget?.row ?: homeTargetRow(rows, tgtLayoutRow)).coerceIn(0, rowFocus.lastIndex)) ?: firstCard
```

(`tgtLayoutRow` 与原来的 `tgtRow` 同例:是「读的量」不是守卫,只在焦点真的落下时变,不进 key,铁律 6 不受影响。)
顶栏 `GtvTopBar(downTarget = rowFocus.getOrNull(tgtRow.coerceIn(0, rowFocus.lastIndex)), …)` 换成
`downTarget = rowFocus.getOrNull(homeTargetRow(rows, tgtLayoutRow).coerceIn(0, rowFocus.lastIndex))`。
改完 `grep -n "tgtRow\|tgtIdx" HomeScreen.kt` 只剩注释(注释里的旧名顺手改成新名)。

(h) 行渲染:`rows.forEachIndexed { rowIndex, row -> CategoryRow(…) }` 整段换成(CategoryRow 的实参逐字保留,只把四个共用量提出来):

```kotlin
            rows.forEachIndexed { rowIndex, row ->
                // 原来「当前行挂自己记住的列、别的行挂当前行的列」两支取的都是目标行的列,合成一个 tgtCol(owner 裁定后只有它)
                val targetIndex = moveTarget?.col ?: tgtCol
                val landingShifts = rowIndex != activeRowSafe && vertical.shiftY(rowIndex) != shiftTarget
                // R53:顶栏下淡出,按本行当前的「看得见的上沿」(频道行是行头顶)
                val rowAlphaFn = run {
                    val isActiveRow = rowIndex == activeRowSafe
                    val restTop = restVisibleTop(rowIndex)
                    ({ GtvLayout.homeRowAlpha(isActiveRow, restTop + shift.value) })
                }
                val enterAlphaFn = run {
                    val state = rowEnter.getOrNull(rowIndex)
                    if (moving != null || state == null) ({ 1f }) else ({ state.floatValue })
                }
                val onRowFocus: (Int, Boolean) -> Unit = { idx, got ->
                    report(rowIndex, idx, got)
                    if (got) {
                        val prevRow = activeRow.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
                        if (rowIndex != prevRow && !restoring && movingNow == null) startRowEnter(rowIndex)
                        activeRow = rowIndex
                        if (!restoring && movingNow == null) { tgtLayoutRow = row.layoutRow; tgtCol = idx }
                    }
                }
                if (row.isChannel) {
                    ChannelRow(
                        row = row,
                        firstCard = if (rowIndex == 0) firstCard else null,
                        rowRequester = rowFocus.getOrNull(rowIndex),
                        isLastRow = rowIndex == rows.lastIndex,
                        upTarget = if (rowIndex > 0) rowFocus.getOrNull(rowIndex - 1) else gearFocus,
                        downTarget = if (rowIndex < rows.lastIndex) rowFocus.getOrNull(rowIndex + 1) else null,
                        targetIndex = targetIndex,
                        landingShiftsPage = landingShifts,
                        isFocusRow = rowIndex == iconFocusRow,
                        loadPosters = loadsPosters(rowIndex, activeRowSafe),
                        rowAlpha = rowAlphaFn,
                        enterAlpha = enterAlphaFn,
                        onFocusChange = onRowFocus,
                    )
                } else {
                    CategoryRow(
                        row = row,
                        metrics = metrics,
                        cardSize = cardSize,
                        showTitles = showTitles,
                        titles = titles,
                        firstCard = if (rowIndex == 0) firstCard else null,
                        rowRequester = rowFocus.getOrNull(rowIndex),
                        isLastRow = rowIndex == rows.lastIndex,
                        upTarget = if (rowIndex > 0) rowFocus.getOrNull(rowIndex - 1) else gearFocus,
                        downTarget = if (rowIndex < rows.lastIndex) rowFocus.getOrNull(rowIndex + 1) else null,
                        targetIndex = targetIndex,
                        carried = if (moveTarget?.row == rowIndex) moveTarget.col else -1,
                        landingShiftsPage = landingShifts,
                        isFocusRow = rowIndex == iconFocusRow,
                        rowAlpha = rowAlphaFn,
                        enterAlpha = enterAlphaFn,
                        onFocusChange = onRowFocus,
                    )
                }
            }
```

(原 `onFocusChange` lambda 里的中文注释——R129 换行淡入、R111 等——随代码一起搬进 `onRowFocus`,一句不删。**不要**给 `forEachIndexed` 的每一行包 `key(row.layoutRow)`:那样上面的频道行消失时焦点节点原地跟着行挪走、没有任何焦点事件,`focusedCell` 停在旧的画出行号上,还原效果请求的又正是那个已聚焦的节点(不再发事件),长按会弹错卡的菜单;按位置组合时节点换了内容、由还原效果按 layoutRow 送回,上报照常。)

(i) `buildRows` 整个换成:

```kotlin
private fun buildRows(ctx: Context): List<Row> {
    val layout = Layout.read(ctx)
    val needed = layout.flatMap { it.apps }.toSet()
    val all = Apps.load(ctx, needed, withBitmaps = needed, withLabels = needed)
    // **layoutRow 必须在过滤之前定下来**:withChannelContent 会整行丢掉空行 / 没内容的频道行,
    // 丢掉之后剩下行的下标就不再等于它们在 layout.json 里的下标。「移除 / 移动位置」写的是 layout.json。
    // R164:频道行这里只带标识与应用名,节目由 HomeScreen 另外按 channelsRevision 读、在 withChannelContent 里合进来。
    return layout.mapIndexed { layoutIndex, row ->
        val ref = row.channel
        if (ref != null) {
            Row(apps = emptyList(), icon = row.icon, layoutRow = layoutIndex, channel = ref, channelAppLabel = Apps.labelOf(ctx, ref.pkg))
        } else {
            Row(icon = row.icon, apps = row.apps.mapNotNull { all[it] }, layoutRow = layoutIndex)
        }
    }
    // 过滤(空应用行 / 没内容的频道行不画)与它承担的焦点不变量见 withChannelContent 的 KDoc。
}
```

- [ ] **Step 7: 改 MainActivity(传参 + 长按吞掉)**

字段区(`focusedCard` 旁)加:

```kotlin
    /** R164:首页焦点在频道卡上(HomeScreen 上报)。长按频道卡整下吞掉:不弹菜单,松开也不当点击启动节目(spec §2.3)。 */
    private var focusedOnChannel by mutableStateOf(false)
```

HomeScreen 调用处(`onFocusedCard = { focusedCard = it },` 之后)加:

```kotlin
                    onFocusedChannel = { focusedOnChannel = it },
                    onVerticalGeometry = { homeVertical = it },
```

壁纸逐行压暗改读累计几何(原来按统一 `homePitch` 折行,频道行比应用行高,混排时每行静止位置上的暗度会错):`val homePitch = …` 下面加

```kotlin
            // R164:首页交上来的逐行累计几何(HomeVertical);null = 首页不在组合里,退回按统一行距。只在下面的 alpha lambda(绘制阶段)里读。
            var homeVertical by remember { mutableStateOf<HomeVertical?>(null) }
```

`Wallpaper(…)` 的 `alpha = { GtvLayout.homeWallpaperAlpha(pageShiftState?.value?.value ?: 0f, homePitch) },` 换成

```kotlin
                alpha = {
                    val shift = pageShiftState?.value?.value ?: 0f
                    homeVertical?.wallpaperAlpha(shift) ?: GtvLayout.homeWallpaperAlpha(shift, homePitch)
                },
```

长按识别处:

```kotlin
                val ref = focusedCard
                if (ref != null) {
                    …(原样)
                    return true
                }
```

之后(同一个 `if (… homeBare …)` 块内)加:

```kotlin
                if (focusedOnChannel) {
                    // R164:频道卡长按不做事——整下吞掉(含随后的 UP),否则松手会被 Card 当成一次点击、把节目打开
                    longPressDownTime = event.downTime
                    return true
                }
```

- [ ] **Step 8: 跑单测 + 构建**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: 全部单测 PASS(含 `CopyTest`:三语 key 与占位符一致),BUILD SUCCESSFUL。

- [ ] **Step 9: 模拟器核对(unitedu-tv-2,夹具来自 Task 11)**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && export DEV=emulator-5562
adb -s $DEV install -r app/build/outputs/apk/release/app-release.apk
adb -s $DEV shell cmd package set-home-activity com.uniteduone.launcher/.MainActivity
adb -s $DEV shell pm grant com.uniteduone.launcher android.permission.READ_TV_LISTINGS
adb -s $DEV shell am broadcast -f 32 -n test.channels/.Cmd -a test.channels.PUBLISH
python3 - <<'PY'
import json, subprocess
lay = json.load(open("scripts/e2e/fixtures/layout.json"))
lay["rows"].insert(1, {"icon": "tv", "apps": [], "channel": {"pkg": "test.channels", "key": "e2e-picks", "name": "E2E Picks"}})
open("/tmp/unitedu-e2e/_layout-ch.json", "w").write(json.dumps(lay))
subprocess.run(["adb", "-s", "emulator-5562", "push", "/tmp/unitedu-e2e/_layout-ch.json",
                "/sdcard/Android/data/com.uniteduone.launcher/files/layout.json"], check=True)
PY
adb -s $DEV shell am force-stop com.uniteduone.launcher
adb -s $DEV shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.uniteduone.launcher/.MainActivity
sleep 3; adb -s $DEV shell input keyevent 20; sleep 1.5
adb -s $DEV exec-out screencap -p > /tmp/unitedu-e2e/home-channel-focused.png
adb -s $DEV shell input keyevent 20; sleep 1.5
adb -s $DEV exec-out screencap -p > /tmp/unitedu-e2e/home-channel-unfocused.png
adb -s $DEV logcat -d | grep -E "FATAL EXCEPTION|ANR in com.uniteduone" || echo "no crash"
```

Expected(用 Read 打开两张图逐项看):第一张焦点在频道行——行头「E2E Channels · E2E Picks」全白,8 张卡 5 张宽(16:9)、2 张窄(2:3)、1 张方,焦点卡放大 + 描边 + 柔光,每张卡下有标题与「S1 · E1」这类第二行,第 5 张(Snow Peak)是深灰底 + 白字标题;第二张焦点在下一行应用行,频道行行头变暗、卡下文字不画但行距不跳;输出 `no crash`。截图只放 `/tmp`,正式截图在 Task 17。

- [ ] **Step 10: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/HomeChannels.kt app/src/main/java/com/uniteduone/launcher/HomeChannelRow.kt app/src/main/java/com/uniteduone/launcher/HomeScreen.kt app/src/main/java/com/uniteduone/launcher/MainActivity.kt app/src/main/java/com/uniteduone/launcher/GtvTokens.kt app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/test/java/com/uniteduone/launcher/HomeChannelsTest.kt && git commit -F - <<'EOF'
feat(channels): 首页频道行——海报卡、行头、焦点行两行字、逐行累计位移、长按吞掉(R164-4)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---
### Task 13: 编辑页的频道架子(依赖 edit-shelves)

> 写在 `docs/superpowers/plans/2026-10-08-edit-shelves.md` 产出的货架模型上,名字已按那份计划正文逐一核对:`EditShelves.kt` 的 `internal sealed interface Shelf { data class AppShelf(val row: Int, val icon: String, val apps: List<String>); data object NewRowShelf }`、`ShelfChip { ADD_APP, ICON, UP, DOWN, DELETE }`、`ShelfZone`、`ShelfSpot(shelf, zone, index)`、`ShelfLane`、`shelvesOf(view)`、`appShelfCount`(只数 `AppShelf`)、`shelfChips(shelves, shelf)`、`laneSize`、`shelfLanes`、`clampSpot`、`landingAfterSwap` / `landingAfterDelete`、`EditCounts` / `editCounts`;`EditShelfParts.kt` 的 `ShelfFrame`、`ShelfChipPill`、私有的 `chipLabel` / `chipIcon`(穷举 `when`);`EditScreen.kt` 的 `shelvesNow()`、`onChip(si, chip)`、`AppShelfView`(行头在它里面)、`activeShelf`、`retarget(ShelfSpot)`、`persist()`、`EditTopBar`。那份计划文件头 KDoc 的「扩展点」一节就是本任务的清单。动手前 `grep -n "sealed interface Shelf\|enum class ShelfChip\|fun shelvesOf\|fun shelfChips\|fun shelfLanes\|fun clampSpot\|data class EditCounts" app/src/main/java/com/uniteduone/launcher/EditShelves.kt` 再核对一遍落地代码。

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/EditShelves.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/EditShelfParts.kt`(`chipLabel` / `chipIcon` 加 `REAUTHORIZE`;末尾加 `ChannelShelfView` / `ChannelShelfBody`)
- Modify: `app/src/main/java/com/uniteduone/launcher/EditScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`、`values-en/strings.xml`、`values-zh-rTW/strings.xml`
- Test: `app/src/test/java/com/uniteduone/launcher/ChannelShelfTest.kt`

**Interfaces:**
- Consumes: Task 1–4、7、8、9(`ChannelRef`、`LayoutRow.isChannel`、`swapRows`、`deleteRow`、`moveInLayout` 跳过频道行、`ChannelContent`、`ChannelCache.data`、`channelContentsFrom`、`PosterCache`、`LocalChannelEnv`)。
- Produces:
  - `internal sealed interface ChannelShelfState { data class Posters(val programs: List<Program>); data object Empty; data object NeedsPermission }`
  - `Shelf.ChannelShelf(val row: Int, val ref: ChannelRef, val appLabel: String, val state: ChannelShelfState) : Shelf`(嵌在 `Shelf` 里,与 `AppShelf` 同一种写法;`row` = layout.json 行号)
  - `ShelfChip.REAUTHORIZE`(新枚举值)
  - `internal fun channelShelfState(c: ChannelContent?): ChannelShelfState`
  - `shelvesOf(view, channelLabels: Map<String, String> = emptyMap(), channelContent: Map<ChannelRef, ChannelContent> = emptyMap())`(多两个有默认值的参数,edit-shelves 的单测不用改)
  - `shelfChips` / `shelfLanes` / `clampSpot` 各多一支 `ChannelShelf`(删除胶囊仍按 `appShelfCount`,它本来就只数应用架子)
  - `EditCounts(rows, apps, channels: Int = 0)`、`editCounts` 数频道行;`internal fun landingAfterAppendChannel(shelves: List<Shelf>): ShelfSpot`
  - `internal const val SHELF_POSTER_HEIGHT = 68.625f`、`internal fun shelfPosterWidthDp(aspect: PosterAspect): Float`
  - 文案 `shelf_channel_tag`、`shelf_channel_empty`、`shelf_channel_needs_permission`、`shelf_chip_reauthorize`、plurals `edit_channels_count`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/ChannelShelfTest.kt`:

```kotlin
package com.uniteduone.launcher

import com.uniteduone.launcher.ShelfZone.CARDS
import com.uniteduone.launcher.ShelfZone.CHIPS
import com.uniteduone.launcher.ShelfZone.NEW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R165 §2.1–§2.2 / R164:编辑页的频道架子——状态、胶囊、焦点 lane、夹取与落点、页头计数、海报预览尺寸。 */
class ChannelShelfTest {
    private val ref = ChannelRef("com.cibn.tv", "k", "酷喵推荐")
    private val view = listOf(
        LayoutRow("movie", listOf("a")),
        LayoutRow(CHANNEL_ROW_ICON, channel = ref),
        LayoutRow("music", emptyList()),
    )
    private fun shelves(c: ChannelContent? = null) =
        shelvesOf(view, mapOf("com.cibn.tv" to "CIBN酷喵"), c?.let { mapOf(ref to it) } ?: emptyMap())

    @Test fun shelvesMapChannelRowsToChannelShelves() {
        val s = shelves(ChannelContent.NeedsPermission)
        assertEquals(4, s.size)
        assertEquals(Shelf.ChannelShelf(1, ref, "CIBN酷喵", ChannelShelfState.NeedsPermission), s[1])
        assertEquals(Shelf.NewRowShelf, s.last())
        assertEquals("没有应用名时用包名", "com.cibn.tv", (shelvesOf(view)[1] as Shelf.ChannelShelf).appLabel)
    }

    @Test fun shelfStateFollowsContent() {
        val p = Program(1, "t", null, null, null, 0, null, PosterAspect.R16_9, null, 0)
        assertEquals(ChannelShelfState.Posters(listOf(p)), channelShelfState(ChannelContent.Ready(listOf(p))))
        assertEquals(ChannelShelfState.Empty, channelShelfState(ChannelContent.Missing))
        assertEquals("还没读到当暂无内容", ChannelShelfState.Empty, channelShelfState(null))
        assertEquals(ChannelShelfState.NeedsPermission, channelShelfState(ChannelContent.NeedsPermission))
    }

    @Test fun channelChips() {
        assertEquals(listOf(ShelfChip.UP, ShelfChip.DOWN, ShelfChip.DELETE), shelfChips(shelves(), 1))
        assertEquals("第一层没有上移", listOf(ShelfChip.DOWN, ShelfChip.DELETE), shelfChips(shelvesOf(listOf(view[1], view[0])), 0))
        assertEquals("最后一个内容层没有下移", listOf(ShelfChip.UP, ShelfChip.DELETE), shelfChips(shelvesOf(listOf(view[0], view[1])), 1))
        assertEquals(
            "没授权多一颗「重新授权」,排最前",
            listOf(ShelfChip.REAUTHORIZE, ShelfChip.UP, ShelfChip.DOWN, ShelfChip.DELETE),
            shelfChips(shelves(ChannelContent.NeedsPermission), 1),
        )
    }

    @Test fun appShelfDeleteCountsAppShelvesOnly() {
        val oneApp = shelvesOf(listOf(LayoutRow("movie", listOf("a")), LayoutRow(CHANNEL_ROW_ICON, channel = ref)))
        assertFalse("只剩一个应用行,频道行不算", ShelfChip.DELETE in shelfChips(oneApp, 0))
        assertTrue(ShelfChip.DELETE in shelfChips(shelves(), 0))
        assertTrue("频道架子永远能删", ShelfChip.DELETE in shelfChips(oneApp, 1))
    }

    /** §2.2:上下键固定顺序——第 1 层胶囊 → 第 1 层卡片 → 第 2 层胶囊 → …;频道架子只有胶囊那一条。 */
    @Test fun channelShelvesOnlyHaveTheChipLane() {
        assertEquals(
            listOf(ShelfLane(0, CHIPS), ShelfLane(0, CARDS), ShelfLane(1, CHIPS), ShelfLane(2, CHIPS), ShelfLane(2, CARDS), ShelfLane(3, NEW)),
            shelfLanes(shelves()),
        )
        assertEquals(0, laneSize(shelves(), 1, CARDS))
    }

    @Test fun clampKeepsSpotsOnAChannelShelfOnItsChips() {
        // clampSpot 换条时格号不清零、照夹(edit-shelves 的写法,应用架子 NEW → CARDS 同样如此):3 颗胶囊 → 夹到 2
        assertEquals("频道架子没有卡片条:改落胶囊条", ShelfSpot(1, CHIPS, 2), clampSpot(shelves(), ShelfSpot(1, CARDS, 3)))
        assertEquals("授权回来「重新授权」没了:同一位置夹取", ShelfSpot(1, CHIPS, 2), clampSpot(shelves(), ShelfSpot(1, CHIPS, 3)))
    }

    @Test fun swapDeleteAndAppendLandings() {
        // 频道架子上移到第一层:没有「上移」了 → 落「下移」(edit-shelves 的 landingAfterSwap 原样适用)
        assertEquals(ShelfSpot(0, CHIPS, 0), landingAfterSwap(shelvesOf(listOf(view[1], view[0], view[2])), 0, ShelfChip.UP))
        assertEquals("删掉频道架子:落上一层第一颗胶囊", ShelfSpot(0, CHIPS, 0), landingAfterDelete(shelvesOf(listOf(view[0], view[2])), 1))
        val added = shelvesOf(view + LayoutRow(CHANNEL_ROW_ICON, channel = ChannelRef("p", "", "n")))
        assertEquals("加频道:新频道架子的第一颗胶囊", ShelfSpot(3, CHIPS, 0), landingAfterAppendChannel(added))
    }

    @Test fun headerCountsChannels() {
        assertEquals(EditCounts(rows = 3, apps = 1, channels = 1), editCounts(view))
    }

    @Test fun posterPreviewIsScaledToTheShelfCardHeight() {
        assertEquals(68.625f, SHELF_POSTER_HEIGHT, 0f)
        assertEquals(196f * 68.625f / 110f, shelfPosterWidthDp(PosterAspect.R16_9), 0.001f)
        assertEquals(68.625f, shelfPosterWidthDp(PosterAspect.R1_1), 0.001f)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelShelfTest*'`
Expected: 编译失败(`ChannelShelf` / `ChannelShelfState` / `REAUTHORIZE` / `landingAfterAppendChannel` 等未定义,`EditCounts` 没有 `channels`)。

- [ ] **Step 3: 实现纯模型(EditShelves.kt)**

1. `ShelfChip` 末尾加 `REAUTHORIZE`(胶囊的左右顺序由 `shelfChips` 的 `buildList` 决定,不看枚举顺序)。
2. `sealed interface Shelf` 里 `AppShelf` 之后加一个子类型,并在文件里加状态与几个函数:

```kotlin
    /** R164 / R165 §2.1:频道架子——顶部频道图标 +「应用名 · 频道名」+ 小标签「频道」+ 胶囊;下面不可聚焦的海报预览或状态文字。[row] = layout.json 行号。 */
    data class ChannelShelf(val row: Int, val ref: ChannelRef, val appLabel: String, val state: ChannelShelfState) : Shelf
```

```kotlin
/** R164:频道架子此刻画什么。 */
internal sealed interface ChannelShelfState {
    data class Posters(val programs: List<Program>) : ChannelShelfState
    data object Empty : ChannelShelfState
    data object NeedsPermission : ChannelShelfState
}

/** 内容 → 架子状态;还没读到(null)按「暂无内容」画。 */
internal fun channelShelfState(c: ChannelContent?): ChannelShelfState = when (c) {
    is ChannelContent.Ready -> ChannelShelfState.Posters(c.programs)
    ChannelContent.NeedsPermission -> ChannelShelfState.NeedsPermission
    ChannelContent.Missing, null -> ChannelShelfState.Empty
}

/** 加频道之后:落最后一个频道架子(就是新的那层)的第一颗胶囊(spec §2.2)。 */
internal fun landingAfterAppendChannel(shelves: List<Shelf>): ShelfSpot =
    clampSpot(shelves, ShelfSpot(shelves.indexOfLast { it is Shelf.ChannelShelf }.coerceAtLeast(0), ShelfZone.CHIPS, 0))

/** 编辑页海报预览的高(dp)= 编辑页应用卡高(中档 122 × 9 / 16,§2.1「高 68.6 dp」)。 */
internal const val SHELF_POSTER_HEIGHT = 68.625f

internal fun shelfPosterWidthDp(aspect: PosterAspect): Float = aspect.widthDp * SHELF_POSTER_HEIGHT / ChannelRowLayout.CARD_HEIGHT
```

3. `shelvesOf` 换成:

```kotlin
/**
 * 看得见的那份行(R67,`visibleRows`)→ 货架:应用行一层应用架子,频道行一层频道架子(R164),最后一层「新的一行」。
 * [channelLabels] / [channelContent] 来自进程级 `ChannelCache`(EditScreen 的 `shelvesFor` 统一传);缺省空表 = 用包名、按「暂无内容」。
 */
internal fun shelvesOf(
    view: List<LayoutRow>,
    channelLabels: Map<String, String> = emptyMap(),
    channelContent: Map<ChannelRef, ChannelContent> = emptyMap(),
): List<Shelf> = view.mapIndexed { i, r ->
    val ref = r.channel
    if (ref != null) Shelf.ChannelShelf(i, ref, channelLabels[ref.pkg] ?: ref.pkg, channelShelfState(channelContent[ref]))
    else Shelf.AppShelf(i, r.icon, r.apps)
} + Shelf.NewRowShelf
```

4. `shelfChips` 的 `when (shelves.getOrNull(shelf))` 改成 `when (val s = shelves.getOrNull(shelf))`,在 `is Shelf.AppShelf -> …` 之后加一支(KDoc 补一句「频道架子:上移 / 下移同应用架子;删除永远有、不弹确认;没授权时最前面多一颗「重新授权」——那是这一层此刻最该按的一颗」):

```kotlin
    is Shelf.ChannelShelf -> buildList {
        if (s.state == ChannelShelfState.NeedsPermission) add(ShelfChip.REAUTHORIZE)
        if (shelf > 0) add(ShelfChip.UP)
        if (shelf < lastContentShelf(shelves)) add(ShelfChip.DOWN)
        add(ShelfChip.DELETE)
    }
```

5. `shelfLanes` 的 `when (s)` 加 `is Shelf.ChannelShelf -> listOf(ShelfLane(i, ShelfZone.CHIPS))`(海报预览不可聚焦,只有胶囊那一条)。`laneSize` 不用改:`CARDS` 只认 `AppShelf`,频道架子自然是 0。
6. `clampSpot` 的 `when (shelves[shelf])` 加 `is Shelf.ChannelShelf -> ShelfZone.CHIPS`。授权回来「重新授权」那颗消失时,目标 `(层, CHIPS, i)` 由这里按新胶囊表夹到同一位置——不需要另写「胶囊表变了」的重定位。
7. `EditCounts` 换成 `internal data class EditCounts(val rows: Int, val apps: Int, val channels: Int = 0)`,`editCounts` 换成
   `EditCounts(view.size, view.sumOf { it.apps.size }, view.count { it.isChannel })`(KDoc:「N 行 · N 个应用 · N 个频道」,spec §2.1)。

`appShelfCount` 不动:它只数 `AppShelf`,应用架子的「删除」胶囊因此不会把频道行算进去。`landingAfterSwap` / `landingAfterDelete` 不动(频道架子的上移 / 下移 / 删除落点照 spec §2.2 正好是它们)。

- [ ] **Step 4: 胶囊文案 / 图标 + 文案(与 Step 3 一起才编得过:`chipLabel` / `chipIcon` 是穷举 `when`,`REAUTHORIZE` 一加就要有文案与图标)**

1. `EditShelfParts.kt`:`chipLabel` 加 `ShelfChip.REAUTHORIZE -> R.string.shelf_chip_reauthorize`;`chipIcon` 加 `ShelfChip.REAUTHORIZE -> Icons.Rounded.LockOpen`(import `androidx.compose.material.icons.rounded.LockOpen`,material-icons-extended 里有)。
2. 三份 `strings.xml` 各加:

`values/strings.xml`:

```xml
    <!-- R164 编辑页频道架子 -->
    <string name="shelf_channel_tag">频道</string>
    <string name="shelf_channel_empty">暂无内容</string>
    <string name="shelf_channel_needs_permission">需要重新授权</string>
    <string name="shelf_chip_reauthorize">重新授权</string>
    <plurals name="edit_channels_count">
        <item quantity="one">%1$d 个频道</item>
        <item quantity="other">%1$d 个频道</item>
    </plurals>
```

`values-en/strings.xml`:

```xml
    <!-- R164 edit page: channel shelf -->
    <string name="shelf_channel_tag">Channel</string>
    <string name="shelf_channel_empty">Nothing here right now</string>
    <string name="shelf_channel_needs_permission">Permission needed</string>
    <string name="shelf_chip_reauthorize">Allow Again</string>
    <plurals name="edit_channels_count">
        <item quantity="one">%1$d channel</item>
        <item quantity="other">%1$d channels</item>
    </plurals>
```

`values-zh-rTW/strings.xml`:

```xml
    <!-- R164 編輯頁頻道架 -->
    <string name="shelf_channel_tag">頻道</string>
    <string name="shelf_channel_empty">暫無內容</string>
    <string name="shelf_channel_needs_permission">需要重新授權</string>
    <string name="shelf_chip_reauthorize">重新授權</string>
    <plurals name="edit_channels_count">
        <item quantity="one">%1$d 個頻道</item>
        <item quantity="other">%1$d 個頻道</item>
    </plurals>
```

(上移 / 下移 / 删除三颗胶囊用 edit-shelves 已有的 `edit_chip_up` / `edit_chip_down` / `edit_chip_delete`,不另起。)

- [ ] **Step 5: (不单独跑)**

Step 3 新加的 `Shelf.ChannelShelf` 与 `ShelfChip.REAUTHORIZE` 让 `EditScreen.kt` 的两个穷举 `when`(货架渲染、`onChip`)暂时编不过,单测源集也跟着编不过——Step 6 (b)(c) 补完之后,在 Step 7 一起跑 `ChannelShelfTest` 与 edit-shelves 自己的单测。

- [ ] **Step 6: 编辑页读频道缓存、画频道架子、接胶囊**

`EditScreen.kt`(edit-shelves 重写后的那份):

(a) 在 `fun view()` 之后、`fun shelvesNow()` 之前加,并把本页**每一处** `shelvesOf(…)` 换成 `shelvesFor(…)`(`shelvesNow()`、`val shelves = shelvesOf(viewRows)`、显式重定位效果里两处(`snapshotFlow` 里一处、`val want = clampSpot(shelvesOf(view()), wanted)` 一处)、看门狗里那一处——共五处,换完 `grep -n "shelvesOf(" EditScreen.kt` 为空):

```kotlin
    // R164 + owner 裁定(2026-10-08):频道架子的内容与应用名来自进程级 ChannelCache(MainActivity 按 channelsRevision 刷新),
    // 本页不查 TvProvider;海报预览不可聚焦,换内容不拆任何焦点节点。
    val channelEnv = LocalChannelEnv.current
    val channelSnap by ChannelCache.data.collectAsState()
    val channelRefs = remember(rows) { rows.mapNotNull { it.channel } }
    val channelContentNow by rememberUpdatedState(remember(channelSnap, channelRefs) { channelContentsFrom(channelSnap, channelRefs) })
    val channelLabelsNow by rememberUpdatedState(channelSnap?.labels.orEmpty())
    /**
     * 本页的货架一律经这里算(组合期、按键回调、重定位的 snapshotFlow、看门狗)。频道架子的胶囊表取决于授权状态(「重新授权」那颗),
     * 有一处漏传内容,那一处算出的胶囊下标就与画出来的对不上。读的是 State(rememberUpdatedState),回调与 snapshotFlow 读到的都是最新的。
     */
    fun shelvesFor(v: List<LayoutRow>): List<Shelf> = shelvesOf(v, channelLabelsNow, channelContentNow)
```

(import `androidx.compose.runtime.collectAsState`、`rememberUpdatedState`,已有的不重复加。)

(b) `onChip(si, chip)` 的 `when (chip)` 加一支(穷举 `when` 不加编不过;上移 / 下移 / 删除三支对频道架子原样适用:`swapRows` 不看行的种类,删除时 `view()` 里这一行的 `apps` 恒空 → 直接 `deleteRowAt`、不弹确认,落点 `landingAfterDelete`):

```kotlin
            ShelfChip.REAUTHORIZE -> channelEnv.requestPermission { result ->
                // 授权窗盖上来时本页 ON_PAUSE 冻结焦点、ON_RESUME 按目标重定位;授权回来 channelsRevision++ → ChannelCache 重读 →
                // 「重新授权」那颗消失,目标 (层, CHIPS, i) 由 clampSpot 夹到同一位置。owner 裁定(2026-10-09):只有永久拒绝
                // (系统没弹窗,见 permissionResult)才自动去系统设置;点了拒绝 / 返回关窗 → 留在这颗胶囊上,再按一次再问。
                if (result == PermissionResult.DENIED_PERMANENTLY) channelEnv.openPermissionSettings()
            }
```

(c) 货架渲染的 `when (shelf)` 加一支(穷举):

```kotlin
                        is Shelf.ChannelShelf -> ChannelShelfView(
                            shelf = shelf,
                            chips = shelfChips(shelves, si),
                            focus = { focusAnim.value },
                            active = si == activeShelf,
                            accent = accent,
                            loadPosters = loadsPosters(si, activeShelf),
                            req = ::req,
                            report = ::report,
                            place = place,
                            onChip = { onChip(si, it) },
                            modifier = measure,
                        )
```

(d) `EditTopBar` 的概况那一行换成三段:

```kotlin
        val channelsText = pluralStringResource(R.plurals.edit_channels_count, counts.channels, counts.channels)
        BasicText("$rowsText · $appsText · $channelsText", style = Type.caption)
```

(e) **`EditShelfParts.kt`**(不是 `EditScreen.kt`)末尾加频道架子与它的正文,`internal`。放这里是因为海报预览要 `clipToBounds`,而 `EditIronRulesTest.theShelfNeverClipsTheFocusedCardOrItsLeftEdge` 禁止 `EditScreen.kt` 里出现 `clipToBounds`(连 import 都算)——那条防的是卡片条裁掉焦点卡,海报预览不可聚焦,不在它要防的范围;写进 `EditScreen.kt` 会让 Step 7 的全量单测变红。(都不新增焦点状态:可聚焦的只有胶囊,写法与 `AppShelfView` 的胶囊行逐字相同——逐项 requester、得失都上报、量水平中心):

```kotlin
/**
 * 一层频道架子(R164 / R165 §2.1):顶行 = 频道图标 +「应用名 · 频道名」+ 小标签「频道」+ 胶囊;下面是海报预览或状态文字。
 * 高度同应用架子(海报预览高 = 应用卡高),纵向位移的累计不需要区分种类。
 */
@Composable
internal fun ChannelShelfView(
    shelf: Shelf.ChannelShelf,
    chips: List<ShelfChip>,
    focus: () -> Float,
    active: Boolean,
    accent: Color,
    loadPosters: Boolean,
    req: (ShelfSpot) -> FocusRequester,
    report: (ShelfSpot, Boolean) -> Unit,
    place: (ShelfSpot, Float) -> Unit,
    onChip: (ShelfChip) -> Unit,
    modifier: Modifier,
) {
    val si = shelf.row
    ShelfFrame(focus = focus, dashed = false, modifier = modifier.fillMaxWidth().height(ShelfLayout.APP_SHELF_HEIGHT.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = ShelfLayout.PAD_START.dp, end = ShelfLayout.PAD_END.dp, top = ShelfLayout.HEADER_TOP.dp)
                .height(ShelfLayout.HEADER_HEIGHT.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(CHANNEL_ROW_ICON, tint = { if (active) accent else Ink.Secondary }, boxSize = ShelfLayout.ICON.dp)
            Spacer(Modifier.width(ShelfLayout.ICON_GAP.dp))
            BasicText(
                stringResource(R.string.channel_row_title, shelf.appLabel, shelf.ref.name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Type.body.copy(color = Ink.Label),
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(
                Modifier
                    .padding(start = 10.dp)
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                BasicText(stringResource(R.string.shelf_channel_tag), style = Type.micro.copy(color = Ink.Label))
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CHIP_GAP.dp), verticalAlignment = Alignment.CenterVertically) {
                chips.forEachIndexed { ci, chip ->
                    val spot = ShelfSpot(si, ShelfZone.CHIPS, ci)
                    ShelfChipPill(
                        chip = chip,
                        visible = focus,
                        onClick = { onChip(chip) },
                        onFocusChange = { report(spot, it) },
                        isFirst = ci == 0,
                        isLast = ci == chips.lastIndex,
                        modifier = Modifier.focusRequester(req(spot)).onGloballyPositioned { place(spot, it.boundsInRoot().center.x) },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = ShelfLayout.PAD_START.dp, end = ShelfLayout.PAD_END.dp, top = ShelfLayout.CARDS_TOP.dp)) {
            ChannelShelfBody(shelf.state, loadPosters)
        }
    }
}

/**
 * R164 频道架子的正文:海报预览(按比例、高 [SHELF_POSTER_HEIGHT]、最多画到架子右缘,**不可聚焦**——一个 focusable 都没有,
 * 不进任何焦点账本),或一行状态文字(「暂无内容」/「需要重新授权」)。海报只在焦点层 ± 1 才加载,同首页。
 */
@Composable
internal fun ChannelShelfBody(state: ChannelShelfState, loadPosters: Boolean) {
    when (state) {
        // 外层按架子宽裁;里层 Row 放开测量(unbounded),右缘那张是被裁掉一截,而不是被约束挤窄(铁律 1 同一个测量坑)
        is ChannelShelfState.Posters -> Box(Modifier.fillMaxWidth().clipToBounds()) {
            Row(
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CARD_GAP.dp),
            ) {
                val ctx = LocalContext.current
                val heightPx = with(LocalDensity.current) { ChannelRowLayout.CARD_HEIGHT.dp.roundToPx() }
                state.programs.forEach { p ->
                    val uri = p.posterUri
                    val bmp by produceState(uri?.let { PosterCache.peek(it) }, uri, loadPosters) {
                        value = uri?.let { PosterCache.peek(it) }   // 同 PosterCard:同一格换了节目先换掉旧图
                        if (uri != null && value == null && loadPosters) value = PosterCache.load(ctx, uri, heightPx)
                    }
                    Box(
                        Modifier
                            .size(shelfPosterWidthDp(p.aspect).dp, SHELF_POSTER_HEIGHT.dp)
                            .clip(RoundedCornerShape(GtvLayout.CARD_CORNER.dp))
                            .background(GtvTokens.PosterFallback),
                        contentAlignment = Alignment.Center,
                    ) {
                        val b = bmp
                        if (b != null) Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else BasicText(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = Type.micro.copy(color = Ink.Primary, textAlign = TextAlign.Center), modifier = Modifier.padding(4.dp))
                    }
                }
            }
        }
        ChannelShelfState.Empty -> BasicText(stringResource(R.string.shelf_channel_empty), style = Type.body)
        ChannelShelfState.NeedsPermission -> BasicText(stringResource(R.string.shelf_channel_needs_permission), style = Type.body)
    }
}
```

(海报缓存与首页共用,解码高度用首页卡高 220 px,同一张图只缓存一份。`EditShelfParts.kt` 缺的 import 按编译器提示补(它已有 `Box` / `Row` / `Spacer` / `BasicText` / `RoundedCornerShape` / `clip` / `background` / `TextAlign` 等,另需 `fillMaxWidth` / `fillMaxSize` / `wrapContentWidth` / `focusRequester` / `onGloballyPositioned` / `boundsInRoot` / `LocalDensity` / `LocalContext` 等):
`androidx.compose.foundation.Image`、`androidx.compose.ui.draw.clip`、`androidx.compose.ui.draw.clipToBounds`、`androidx.compose.ui.graphics.asImageBitmap`、
`androidx.compose.ui.layout.ContentScale`、`androidx.compose.ui.text.style.TextAlign`、`androidx.compose.ui.text.style.TextOverflow`、`androidx.compose.runtime.produceState`;
已有的不重复加。)

- [ ] **Step 7: 构建 + 模拟器核对**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && export DEV=emulator-5562
gradle --no-daemon testReleaseUnitTest assembleRelease   # 含 ChannelShelfTest 与 EditShelves* 全部 PASS
adb -s $DEV install -r app/build/outputs/apk/release/app-release.apk
adb -s $DEV shell am force-stop com.uniteduone.launcher
adb -s $DEV shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.uniteduone.launcher/.MainActivity
```

Task 12 Step 9 推上去的布局第 2 行是频道行。`python3 -c "import sys, os; sys.path.insert(0,'scripts/e2e'); os.environ['DEV']='emulator-5562'; from j_edit import open_edit; open_edit()"` 进编辑页,上下键走一遍:应用架子胶囊 → 应用卡 → 频道架子胶囊(一条)→ 下一个应用架子胶囊;截图 `adb -s $DEV exec-out screencap -p > /tmp/unitedu-e2e/edit-channel-shelf.png` 看:页头「… · 1 channel」;频道架子顶部「E2E Channels · E2E Picks」+「Channel」小标签 + 上移 / 下移 / 删除,下面 8 张小海报(Snow Peak 是深灰底字)、超出架子右缘被裁。再 `adb -s $DEV shell pm revoke com.uniteduone.launcher android.permission.READ_TV_LISTINGS`(会杀进程)、重新拉起、进编辑页:频道架子写「Permission needed」,最前一颗「Allow Again」。

Expected: 以上都看得见,`adb -s $DEV logcat -d | grep -E "FATAL EXCEPTION|ANR in com.uniteduone"` 为空。

- [ ] **Step 8: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/EditShelves.kt app/src/main/java/com/uniteduone/launcher/EditShelfParts.kt app/src/main/java/com/uniteduone/launcher/EditScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/test/java/com/uniteduone/launcher/ChannelShelfTest.kt && git commit -F - <<'EOF'
feat(channels): 编辑页频道架子——行头 + 「频道」标签 + 上移 / 下移 / 删除 / 重新授权、不可聚焦的海报预览、页头频道数(R165 §2.1)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 14: 「新的一行」里的「频道」选择卡(依赖 edit-shelves)

> edit-shelves 计划(已核对正文)里「新的一行」是 `enum class NewRowChoice { APP_ROW }`(EditShelves.kt,注释写明「计划 2 加 CHANNEL」)+ `choiceFull(shelves, choice)`;卡片是 `EditShelfParts.kt` 的 `ChoiceCard(choice, full, …)`,标题 / 说明 / 图标由私有穷举 `when` 的 `choiceTitle` / `choiceDesc` / `choiceIcon` 取;`NewRowShelfView` 按 `NewRowChoice.entries` 画卡、`laneSize(NEW) = entries.size`;确定键走 `EditScreen` 的 `onChoice(choice)`(穷举 `when`);「已满」文案是已有的 `edit_choice_full`(`%1$d`),说明行是 `edit_new_row_caption`。本任务只在这些点上加 `CHANNEL`,不另起 `NewRowKind` / 数据类。

**Files:**
- Modify: `app/src/main/java/com/uniteduone/launcher/EditShelves.kt`(`NewRowChoice`、`choiceFull`、新 `choiceMax`)
- Modify: `app/src/main/java/com/uniteduone/launcher/EditShelfParts.kt`(`choiceTitle` / `choiceDesc` / `choiceIcon`、`ChoiceCard` 的「已满」数字)
- Modify: `app/src/main/java/com/uniteduone/launcher/EditScreen.kt`(`onChoice`;`NewRowShelfView` 的说明行)
- Modify: 三份 `strings.xml`
- Test: `app/src/test/java/com/uniteduone/launcher/NewRowChoiceTest.kt`
- Modify: `app/src/test/java/com/uniteduone/launcher/EditShelvesTest.kt`(一条按「新的一行只有一格」写死的断言,见 Step 3 末尾)
- Modify: `scripts/e2e/j_edit.py`(`edit-new-row` 旅程按「新的一行只有一张卡」走,见 Step 3 末尾)

**Interfaces:**
- Consumes: Task 2 `MAX_CHANNEL_ROWS`;Task 13 `Shelf.ChannelShelf`;edit-shelves `NewRowChoice` / `choiceFull` / `appShelfCount` / `laneSize`。
- Produces:
  - `NewRowChoice.CHANNEL`(顺序:应用行、频道)
  - `choiceFull(shelves, NewRowChoice.CHANNEL)` = 频道架子满 [MAX_CHANNEL_ROWS]
  - `internal fun choiceMax(choice: NewRowChoice): Int`(「已满 N 行」的 N)
  - 文案 `shelf_new_channel`、`shelf_new_channel_desc`;改 `edit_new_row_caption`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/NewRowChoiceTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** R165 §2.4:「新的一行」两张选择卡;某类满 5 行那张变暗(仍可聚焦,确定不响应),两类分开数。 */
class NewRowChoiceTest {
    private fun app() = LayoutRow("movie", listOf("a"))
    private fun ch(n: Int) = LayoutRow(CHANNEL_ROW_ICON, channel = ChannelRef("p$n", "k", "c"))

    @Test fun channelIsTheSecondCard() {
        assertEquals(listOf(NewRowChoice.APP_ROW, NewRowChoice.CHANNEL), NewRowChoice.entries.toList())
        assertEquals("「新的一行」那一条有两格", 2, laneSize(shelvesOf(listOf(app())), 1, ShelfZone.NEW))
    }

    @Test fun bothOpenByDefault() {
        val s = shelvesOf(listOf(app()))
        assertFalse(choiceFull(s, NewRowChoice.APP_ROW))
        assertFalse(choiceFull(s, NewRowChoice.CHANNEL))
    }

    @Test fun eachKindFillsSeparately() {
        val fullChannels = shelvesOf(listOf(app()) + List(MAX_CHANNEL_ROWS) { ch(it) })
        assertEquals(listOf(false, true), NewRowChoice.entries.map { choiceFull(fullChannels, it) })
        val fullApps = shelvesOf(List(MAX_ROWS) { app() } + ch(1))
        assertEquals(listOf(true, false), NewRowChoice.entries.map { choiceFull(fullApps, it) })
        assertEquals(listOf(MAX_ROWS, MAX_CHANNEL_ROWS), NewRowChoice.entries.map { choiceMax(it) })
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*NewRowChoiceTest*'`
Expected: 编译失败(`Unresolved reference: CHANNEL` / `choiceMax`)。

- [ ] **Step 3: 实现(判据 + 穷举 `when` 的各处 + 文案,一起才编得过)**

`EditShelves.kt`:`NewRowChoice` 换成 `internal enum class NewRowChoice { APP_ROW, CHANNEL }`(注释改成「应用行 / 频道(R164)」);`choiceFull` 的 `when` 加一支,并在它下面加 `choiceMax`:

```kotlin
    NewRowChoice.CHANNEL -> shelves.count { it is Shelf.ChannelShelf } >= MAX_CHANNEL_ROWS
```

```kotlin
/** 「已满 N 行」的 N(两类各自的上限,spec §4)。 */
internal fun choiceMax(choice: NewRowChoice): Int = when (choice) {
    NewRowChoice.APP_ROW -> MAX_ROWS
    NewRowChoice.CHANNEL -> MAX_CHANNEL_ROWS
}
```

`EditShelfParts.kt`:

```kotlin
private fun choiceTitle(c: NewRowChoice): Int = when (c) {
    NewRowChoice.APP_ROW -> R.string.edit_choice_app_row
    NewRowChoice.CHANNEL -> R.string.shelf_new_channel
}
private fun choiceDesc(c: NewRowChoice): Int = when (c) {
    NewRowChoice.APP_ROW -> R.string.edit_choice_app_row_desc
    NewRowChoice.CHANNEL -> R.string.shelf_new_channel_desc
}
private fun choiceIcon(c: NewRowChoice): ImageVector = when (c) {
    NewRowChoice.APP_ROW -> Icons.Rounded.Apps
    NewRowChoice.CHANNEL -> Icons.Outlined.LiveTv
}
```

(import `androidx.compose.material.icons.outlined.LiveTv`,RowIcon.kt 已在用。)`ChoiceCard` 里 `stringResource(R.string.edit_choice_full, MAX_ROWS)` 换成 `stringResource(R.string.edit_choice_full, choiceMax(choice))`。

`EditScreen.kt`:`onChoice` 的 `when (choice)` 加一支(Task 15 换成打开选频道页;本任务先让它在满了时什么都不做、没满时也暂不响应,编得过、行为与改前一致):

```kotlin
            NewRowChoice.CHANNEL -> Unit   // Task 15:打开选频道页(EditOverlay.ChannelPick)
```

`NewRowShelfView` 下面的说明行 `stringResource(R.string.edit_new_row_caption, MAX_ROWS)` 不改调用,只改文案(两类上限都是 5,一个占位符):

三份 `strings.xml`:

`values/strings.xml`(加两条;`edit_new_row_caption` 改值):

```xml
    <!-- R164 新的一行:频道选择卡 -->
    <string name="shelf_new_channel">频道</string>
    <string name="shelf_new_channel_desc">其他应用推送的节目</string>
```
`<string name="edit_new_row_caption">应用行、频道各最多 %1$d 行</string>`

`values-en/strings.xml`:

```xml
    <!-- R164 new row: channel choice -->
    <string name="shelf_new_channel">Channel</string>
    <string name="shelf_new_channel_desc">Shows and movies from your apps</string>
```
`<string name="edit_new_row_caption">Up to %1$d app rows and %1$d channel rows</string>`

`values-zh-rTW/strings.xml`:

```xml
    <!-- R164 新的一列:頻道選擇卡 -->
    <string name="shelf_new_channel">頻道</string>
    <string name="shelf_new_channel_desc">其他應用程式推送的節目</string>
```
`<string name="edit_new_row_caption">應用程式列、頻道各最多 %1$d 列</string>`

(「已满 N 行」用已有的 `edit_choice_full`,不另起;`CopyTest` 按占位符集合比对,英文用两次 `%1$d` 照样一致。)

`EditShelvesTest.kt`(edit-shelves 已提交)的 `clampKeepsTheNewRowOnTheNewRowWhenRowsChange` 里
`assertEquals(ShelfSpot(3, NEW, 0), clampSpot(three, ShelfSpot(3, NEW, 5)))` 是按「新的一行只有一张卡」写的——加了 `CHANNEL` 后格号夹到 1,
Step 4 的 `--tests '*EditShelves*'` 会红。改成 `assertEquals(ShelfSpot(3, NEW, NewRowChoice.entries.lastIndex), clampSpot(three, ShelfSpot(3, NEW, 5)))`
(意思不变:越界的格号夹到最后一张选择卡)。同文件别的 `NEW` 断言都用格 0 或 `NewRowChoice.entries.size`,不用改。

`scripts/e2e/j_edit.py`(edit-shelves 已提交)的 `edit-new-row` 旅程开头 `s = move_to(S("edit_choice_app_row"), "down", max_steps=14)` 也是按一张卡写的:
它从第 2 层的「删除」胶囊一路按下,每一步按水平中心取最近,到第 4 层卡片条时停在第 3 张(中心 ≈ 403 dp),再按下落到「新的一行」里
离它最近的**「频道」卡**(中心 ≈ 528 dp,「应用行」≈ 208 dp);之后的下键在最后一条上被吞掉,`move_to` 永远等不到「App Row」→ FAIL
(Task 16 Step 3 回归 `E2E_ONLY=j_edit` 时暴露)。把那两行换成(落到「频道」卡就向左一格):

```python
    s = None
    for _ in range(15):   # R164 起「新的一行」有两张卡:按最近可能落到「频道」卡,向左回「应用行」
        lab = screen().label()
        if S("edit_choice_app_row") in lab:
            s = screen(); break
        key("left" if S("shelf_new_channel_desc") in lab else "down")
    check("一路按下到「新的一行」→「应用行」", s is not None, screen().label())
```

后面 `move_to(S("edit_choice_app_row"), "down", max_steps=4)`(从新架子的「添加应用」方块按下,最近的是「应用行」)不用改。

- [ ] **Step 4: 跑测试确认通过 + 构建**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*NewRowChoiceTest*' --tests '*EditShelves*' --tests '*CopyTest*' assembleRelease`
Expected: PASS,BUILD SUCCESSFUL。

- [ ] **Step 5: 模拟器核对变暗**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && export DEV=emulator-5562
adb -s $DEV install -r app/build/outputs/apk/release/app-release.apk
adb -s $DEV shell am broadcast -f 32 -n test.channels/.Cmd -a test.channels.MANY
python3 - <<'PY'
import json, subprocess
lay = json.load(open("scripts/e2e/fixtures/layout.json"))
lay["rows"] += [{"icon": "tv", "apps": [], "channel": {"pkg": "test.channels", "key": f"e2e-many-{k}", "name": f"Many {k}"}} for k in range(5)]
open("/tmp/unitedu-e2e/_layout-5ch.json", "w").write(json.dumps(lay))
subprocess.run(["adb", "-s", "emulator-5562", "push", "/tmp/unitedu-e2e/_layout-5ch.json",
                "/sdcard/Android/data/com.uniteduone.launcher/files/layout.json"], check=True)
PY
adb -s $DEV shell am force-stop com.uniteduone.launcher
adb -s $DEV shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.uniteduone.launcher/.MainActivity
```

进编辑页走到「新的一行」→ 右键到「频道」卡:截图 `/tmp/unitedu-e2e/new-row-full.png`,卡变暗、写「Full (max 5 rows)」(`edit_choice_full` 现行英文);说明行「Up to 5 app rows and 5 channel rows」。再跑 `E2E_ONLY=j_edit DEV=emulator-5562 python3 scripts/e2e/run_all.py`,全过。

- [ ] **Step 6: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/EditShelves.kt app/src/main/java/com/uniteduone/launcher/EditShelfParts.kt app/src/main/java/com/uniteduone/launcher/EditScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/test/java/com/uniteduone/launcher/NewRowChoiceTest.kt app/src/test/java/com/uniteduone/launcher/EditShelvesTest.kt scripts/e2e/j_edit.py && git commit -F - <<'EOF'
feat(channels): 「新的一行」加「频道」选择卡,两类各自满 5 行变暗(R165 §2.4)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 15: 选频道页 `ChannelPicker` + `INITIALIZE_PROGRAMS`(依赖 edit-shelves)

> 写在 edit-shelves 计划(已核对正文)的编辑页上:浮层状态是 `var overlay: EditOverlay?`、`overlayOpen = overlay != null`(新浮层并进 `EditOverlay` 就自动并进 `overlayOpen`——它同时是看门狗的 key 与守卫,铁律 6),`private sealed interface EditOverlay` 的每一种带 `layer`;`returnSpot(o)` / `staleOverlay` 是对它的穷举 `when`;`closeOverlay(o)` 关掉并 `retarget(returnSpot(o))`;返回键由编辑页唯一的 `BackHandler` 收(`o != null -> closeOverlay(o)`)。`AppPicker` / `PickerRow` 已搬到 `AppPicker.kt`(`PickerRow` 仍是 `private`)。

**Files:**
- Create: `app/src/main/java/com/uniteduone/launcher/ChannelInit.kt`
- Create: `app/src/main/java/com/uniteduone/launcher/ChannelPicker.kt`
- Modify: `app/src/main/java/com/uniteduone/launcher/ChannelModel.kt`(末尾加选频道页的纯函数)
- Modify: `app/src/main/java/com/uniteduone/launcher/AppPicker.kt`(`PickerRow` 改 `internal`)
- Modify: `app/src/main/java/com/uniteduone/launcher/EditScreen.kt`(`EditOverlay.ChannelPick`;`returnSpot` / `staleOverlay`;`onChoice` 的 `CHANNEL`;挂进 `OverlayStack`)
- Modify: 三份 `strings.xml`
- Test: `app/src/test/java/com/uniteduone/launcher/ChannelPickerTest.kt`

**Interfaces:**
- Consumes: Task 3 `matchChannel` / `refFor`;Task 7 `ChannelCache.data`(`ChannelSnapshot.channels` / `labels`)、`ChannelSource.hasPermission`;Task 9 `LocalChannelEnv`;Task 13 `landingAfterAppendChannel`;Task 14 `NewRowChoice.CHANNEL` / `choiceFull`;edit-shelves `overlay` / `EditOverlay` / `closeOverlay` / `ShelfSpot` / `clampSpot`;`LockedFile`、`Paths.baseOrNull`、`PickerRow`、`MenuPill`、`ShellScaffold` / `ShellTitle` / `ShellBody`、`LocalPageGhost`。
- Produces:
  - `internal data class ChannelCandidate(val channel: TvChannel, val appLabel: String)`
  - `internal fun pickerChannels(all: List<TvChannel>, onLayout: List<ChannelRef>, labels: Map<String, String>, selfPkg: String): List<ChannelCandidate>`
  - `internal fun retargetById(old: List<Long>, new: List<Long>, idx: Int): Int`
  - `internal sealed interface ChannelPickerPhase { Asking; Denied; Loading; Empty; data class Ready(val items: List<ChannelCandidate>) }`、`internal fun channelPickerPhase(granted: Boolean, asked: Boolean, candidates: List<ChannelCandidate>?): ChannelPickerPhase`
  - `internal fun parseChannelInit(text: String?): Map<String, Long>`、`internal fun channelInitToJson(m: Map<String, Long>): String`、`internal fun pendingInit(receivers: Map<String, Long>, done: Map<String, Long>, selfPkg: String): List<String>`
  - `object ChannelInit { const val ACTION: String; fun notifyPending(ctx: Context): List<String> }`
  - `@Composable internal fun ChannelPicker(nonce: Int, onLayout: List<ChannelRef>, onPick: (ChannelRef) -> Unit, onBack: () -> Unit)`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/uniteduone/launcher/ChannelPickerTest.kt`:

```kotlin
package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R164 §3.1 选频道页:列哪些、按 id 重定位、页面阶段;INITIALIZE_PROGRAMS 只发一次(按包名 + versionCode)。 */
class ChannelPickerTest {
    private fun ch(id: Long, pkg: String, name: String, internal: String? = "k$id", type: String = CHANNEL_TYPE_PREVIEW) =
        TvChannel(id, pkg, type, name, internal)

    @Test fun listsPreviewChannelsNotOnTheHomeScreenSortedByAppThenName() {
        val all = listOf(
            ch(1, "com.cibn.tv", "酷喵推荐"),
            ch(2, "com.xiaodianshi.tv.yst", "热门"),
            ch(3, "com.cibn.tv", "动漫"),
            ch(4, "com.cibn.tv", "老频道", type = "TYPE_OTHER"),
            ch(5, "com.uniteduone.launcher", "自己"),
        )
        val labels = mapOf("com.cibn.tv" to "CIBN酷喵", "com.xiaodianshi.tv.yst" to "云视听小电视")
        val onLayout = listOf(ChannelRef("com.cibn.tv", "k1", "酷喵推荐"))
        val got = pickerChannels(all, onLayout, labels, "com.uniteduone.launcher")
        assertEquals(listOf(3L, 2L), got.map { it.channel.id })
        assertEquals("CIBN酷喵", got[0].appLabel)
    }

    @Test fun retargetFollowsTheSameChannelOrTheSlot() {
        assertEquals("同一个频道换了位置", 2, retargetById(listOf(10, 20, 30), listOf(5, 10, 20, 30), 1))
        assertEquals("焦点那个被删 → 补上来的下一项", 1, retargetById(listOf(10, 20, 30), listOf(10, 30), 1))
        assertEquals("删的是最后一项 → 上一项", 1, retargetById(listOf(10, 20, 30), listOf(10, 20), 2))
        assertEquals(0, retargetById(listOf(10), emptyList(), 0))
        assertEquals("第一次有列表", 0, retargetById(emptyList(), listOf(10, 20), 0))
    }

    @Test fun phases() {
        val one = listOf(ChannelCandidate(ch(1, "p", "c"), "P"))
        assertTrue(channelPickerPhase(granted = false, asked = false, candidates = null) is ChannelPickerPhase.Asking)
        assertTrue(channelPickerPhase(false, true, null) is ChannelPickerPhase.Denied)
        assertTrue(channelPickerPhase(true, true, null) is ChannelPickerPhase.Loading)
        assertTrue(channelPickerPhase(true, false, emptyList()) is ChannelPickerPhase.Empty)
        assertEquals(ChannelPickerPhase.Ready(one), channelPickerPhase(true, true, one))
    }

    @Test fun initializeProgramsIsSentOncePerPackageAndVersion() {
        val receivers = mapOf("com.cibn.tv" to 1413L, "com.netflix.ninja" to 7L, "com.uniteduone.launcher" to 9L)
        assertEquals(listOf("com.cibn.tv", "com.netflix.ninja"), pendingInit(receivers, emptyMap(), "com.uniteduone.launcher"))
        assertEquals(listOf("com.netflix.ninja"), pendingInit(receivers, mapOf("com.cibn.tv" to 1413L, "com.netflix.ninja" to 6L), "com.uniteduone.launcher"))
        assertEquals(emptyList<String>(), pendingInit(receivers, mapOf("com.cibn.tv" to 1413L, "com.netflix.ninja" to 7L), "com.uniteduone.launcher"))
    }

    @Test fun channelInitFileRoundTripsAndToleratesGarbage() {
        val m = mapOf("com.cibn.tv" to 1413L, "test.channels" to 1L)
        assertEquals(m, parseChannelInit(channelInitToJson(m)))
        assertEquals(emptyMap<String, Long>(), parseChannelInit(null))
        assertEquals(emptyMap<String, Long>(), parseChannelInit("{not json"))
        assertEquals(mapOf("a" to 2L), parseChannelInit("""{"notified":{"a":2,"b":"x"}}"""))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelPickerTest*'`
Expected: 编译失败,`Unresolved reference: pickerChannels` 等。

- [ ] **Step 3: 实现纯函数**

`ChannelModel.kt` 末尾加:

```kotlin
/** 选频道页的一项:频道 + 发布方应用名(显示「应用名 · 频道名」)。 */
internal data class ChannelCandidate(val channel: TvChannel, val appLabel: String)

/**
 * 选频道页列哪些(§3.1):`TYPE_PREVIEW`、不是自己发的、还不在桌面上(按 [matchChannel] 认,与首页同一个规则);
 * 按应用名、频道名、id 排。[labels] 缺的用包名。
 */
internal fun pickerChannels(all: List<TvChannel>, onLayout: List<ChannelRef>, labels: Map<String, String>, selfPkg: String): List<ChannelCandidate> {
    val taken = onLayout.mapNotNull { matchChannel(it, all)?.id }.toSet()
    return all.filter { it.type == CHANNEL_TYPE_PREVIEW && it.pkg != selfPkg && it.id !in taken }
        .map { ChannelCandidate(it, labels[it.pkg] ?: it.pkg) }
        .sortedWith(compareBy<ChannelCandidate>({ it.appLabel.lowercase() }, { it.channel.name.lowercase() }, { it.channel.id }))
}

/**
 * 列表变了(TvProvider 冒出新频道 / 删了一个)时焦点目标挪到哪(同应用页 `appsPageRetarget`):原来那个频道还在 → 它的新位置;
 * 被删 → 同一位置(补上来的下一项),越界 → 最后一项;列表空 → 0。
 */
internal fun retargetById(old: List<Long>, new: List<Long>, idx: Int): Int {
    if (new.isEmpty()) return 0
    val id = old.getOrNull(idx) ?: return idx.coerceIn(0, new.lastIndex)
    return new.indexOf(id).takeIf { it >= 0 } ?: idx.coerceIn(0, new.lastIndex)
}

/** 选频道页的阶段:还没问过授权 → Asking(弹窗在途);问过被拒 → Denied;读列表中 → Loading;没频道 → Empty;否则 Ready。 */
internal sealed interface ChannelPickerPhase {
    data object Asking : ChannelPickerPhase
    data object Denied : ChannelPickerPhase
    data object Loading : ChannelPickerPhase
    data object Empty : ChannelPickerPhase
    data class Ready(val items: List<ChannelCandidate>) : ChannelPickerPhase
}

internal fun channelPickerPhase(granted: Boolean, asked: Boolean, candidates: List<ChannelCandidate>?): ChannelPickerPhase = when {
    !granted && !asked -> ChannelPickerPhase.Asking
    !granted -> ChannelPickerPhase.Denied
    candidates == null -> ChannelPickerPhase.Loading
    candidates.isEmpty() -> ChannelPickerPhase.Empty
    else -> ChannelPickerPhase.Ready(candidates)
}
```

`app/src/main/java/com/uniteduone/launcher/ChannelInit.kt`:

```kotlin
package com.uniteduone.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * R164 §3.1:我们当桌面时,系统不会替酷喵 / Netflix / YouTube 这类「等 INITIALIZE_PROGRAMS 才建频道」的应用发这个广播
 * (文档说由桌面发、不是 protected broadcast)。选频道页进页(已授权)时给声明了接收器、还没通知过的包发一次**显式**广播,
 * 按「包名 + versionCode」记在 `channel-init.json`(应用更新后会再通知一次)。文件格式:`{"notified":{"<包名>":<versionCode>}}`。
 * 读改写整段在 [LockedFile] 的锁里(落盘铁律);文件坏了按空表读——最坏是每个包再通知一次,广播本身是幂等的。
 */
internal fun parseChannelInit(text: String?): Map<String, Long> {
    if (text.isNullOrBlank()) return emptyMap()
    val o = runCatching { JSONObject(text).optJSONObject("notified") }.getOrNull() ?: return emptyMap()
    return o.keys().asSequence().mapNotNull { k -> (o.opt(k) as? Number)?.let { k to it.toLong() } }.toMap()
}

internal fun channelInitToJson(m: Map<String, Long>): String =
    JSONObject().put("notified", JSONObject().apply { m.toSortedMap().forEach { (k, v) -> put(k, v) } }).toString(2)

/** 该通知哪些包:有接收器、不是自己、没记录或记录的 versionCode 与现在不同。按包名排。 */
internal fun pendingInit(receivers: Map<String, Long>, done: Map<String, Long>, selfPkg: String): List<String> =
    receivers.filter { (pkg, ver) -> pkg != selfPkg && done[pkg] != ver }.keys.sorted()

object ChannelInit {
    const val ACTION = "android.media.tv.action.INITIALIZE_PROGRAMS"
    private const val TAG = "UnitedU"
    private val store = LockedFile("channel-init.json")

    /** IO 线程。@return 这次通知了哪些包。 */
    fun notifyPending(ctx: Context): List<String> {
        val base = Paths.baseOrNull(ctx) ?: return emptyList()
        val pm = ctx.packageManager
        val receivers: Map<String, List<ComponentName>> =
            runCatching { pm.queryBroadcastReceivers(Intent(ACTION), 0) }.getOrDefault(emptyList())
                .mapNotNull { it.activityInfo }
                .groupBy({ it.packageName }, { ComponentName(it.packageName, it.name) })
        val versions = receivers.keys.associateWith { pkg -> runCatching { pm.getPackageInfo(pkg, 0).longVersionCode }.getOrDefault(-1L) }
            .filterValues { it >= 0 }
        return store.locked {
            // 落盘铁律:读取统一走 store.load(坏文件改名 .bad 留证、回落 .prev);parseChannelInit 不抛,坏内容按空表
            val done = (store.load(base, parse = ::parseChannelInit) as? LockedFile.Load.Ok)?.value ?: emptyMap()
            val pending = pendingInit(versions, done, ctx.packageName)
            pending.forEach { pkg ->
                // FLAG_INCLUDE_STOPPED_PACKAGES:装上后还没打开过的应用处于 stopped 状态,广播默认不送(显式的也不送);
                // 不带它的话这次发不到、却照样记成「已通知」,这个版本号下永远不会再发。
                receivers[pkg].orEmpty().forEach { cn ->
                    runCatching { ctx.sendBroadcast(Intent(ACTION).setComponent(cn).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)) }
                }
            }
            if (pending.isNotEmpty()) {
                store.write(base, channelInitToJson(done + pending.associateWith { versions.getValue(it) }))
                Log.i(TAG, "INITIALIZE_PROGRAMS → $pending")
            }
            pending
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest --tests '*ChannelPickerTest*'`
Expected: PASS。

- [ ] **Step 5: 文案**

三份 `strings.xml` 各加:

`values/strings.xml`:

```xml
    <!-- R164 选频道页 -->
    <string name="channel_picker_title">添加频道</string>
    <string name="channel_picker_hint">UnitedU 会读取电视上各应用提供的频道，只在本机显示，不上传。</string>
    <string name="channel_picker_loading">正在读取频道…</string>
    <string name="channel_picker_empty">暂时没有应用提供频道</string>
    <string name="channel_picker_empty_hint">打开过一次的视频应用才会提供</string>
    <string name="channel_picker_denied">没有读取频道的权限，可以在系统设置里开启。</string>
    <string name="channel_picker_open_settings">去系统设置开启</string>
    <string name="channel_picker_back">返回</string>
```

`values-en/strings.xml`:

```xml
    <!-- R164 channel picker -->
    <string name="channel_picker_title">Add Channel</string>
    <string name="channel_picker_hint">UnitedU reads the channels that apps on this TV provide. They\'re shown only on this TV and never uploaded.</string>
    <string name="channel_picker_loading">Loading channels…</string>
    <string name="channel_picker_empty">No apps offer channels yet</string>
    <string name="channel_picker_empty_hint">Video apps usually offer one after you open them once</string>
    <string name="channel_picker_denied">UnitedU isn\'t allowed to read channels. You can allow it in System Settings.</string>
    <string name="channel_picker_open_settings">Open System Settings</string>
    <string name="channel_picker_back">Back</string>
```

`values-zh-rTW/strings.xml`:

```xml
    <!-- R164 選頻道頁 -->
    <string name="channel_picker_title">新增頻道</string>
    <string name="channel_picker_hint">UnitedU 會讀取電視上各應用程式提供的頻道，只在本機顯示，不會上傳。</string>
    <string name="channel_picker_loading">正在讀取頻道…</string>
    <string name="channel_picker_empty">目前沒有應用程式提供頻道</string>
    <string name="channel_picker_empty_hint">開啟過一次的影音應用程式才會提供</string>
    <string name="channel_picker_denied">沒有讀取頻道的權限，可以在系統設定裡開啟。</string>
    <string name="channel_picker_open_settings">前往系統設定開啟</string>
    <string name="channel_picker_back">返回</string>
```

- [ ] **Step 6: 选频道页组件**

`AppPicker.kt` 里 `private fun PickerRow(` 改成 `internal fun PickerRow(`(选频道页复用同一种小卡片行;`PickerCardMetrics` 不动)。

`app/src/main/java/com/uniteduone/launcher/ChannelPicker.kt`:

```kotlin
package com.uniteduone.launcher

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 胶囊阶段(拒绝 / 没频道)那一颗按钮在 [ChannelPicker] 焦点账本里的「id」(频道 id 都是正数)。 */
private const val PILL_ID = -1L

/**
 * R164 §3.1 选频道页(编辑页 `OverlayStack` 里的一层 `EditOverlay.ChannelPick`,状态并进 `overlayOpen`)。版式与焦点机制照
 * [AppPicker]:整屏两栏、右边一列 `LazyColumn`(与 AppPicker 同一处获准的可滚动容器)、逐项 requester(铁律 3 的实现约束)、
 * nonce 驱动的初始循环、只信自报(铁律 2 / 4)、目标([focusedIdx],位置)与持有者([holder],频道 id)分开(铁律 5)、
 * 残影让路([LocalPageGhost]:不请求、不可聚焦、不回调)。
 *
 * 授权:进页没有 `READ_TV_LISTINGS` → 先弹系统授权窗(一次);拒绝 → 说明 +「去系统设置开启」,永久拒绝(系统没弹窗)
 * 另外自动跳一次系统设置(owner 裁定 2026-10-09,[permissionResult])(回来由 onResume 的
 * `channelsRevision++` 重读授权)。已授权 → 给还没通知过的包发 `INITIALIZE_PROGRAMS`([ChannelInit]);列表读进程级
 * [ChannelCache](owner 裁定:与首页 / 编辑页同一份),TvProvider 一变(ContentObserver → channelsRevision → 缓存刷新)
 * 自动补上,焦点按频道 id 重定位([retargetById])。
 */
@Composable
internal fun ChannelPicker(
    nonce: Int,
    onLayout: List<ChannelRef>,
    onPick: (ChannelRef) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val env = LocalChannelEnv.current
    val ghost = LocalPageGhost.current
    var granted by remember { mutableStateOf(ChannelSource.hasPermission(ctx)) }
    var asked by remember { mutableStateOf(false) }
    // 授权结果 / 从系统设置回来:env.revision 变了就重读授权
    LaunchedEffect(env.revision) { granted = ChannelSource.hasPermission(ctx) }
    LaunchedEffect(granted, asked, ghost) {
        if (!granted && !asked && !ghost) {
            asked = true
            env.requestPermission { r ->
                when (r) {
                    PermissionResult.GRANTED -> granted = true
                    // owner 裁定(2026-10-09):只有永久拒绝(系统没弹窗)才自动跳系统设置;回来仍是 Denied(说明 +「去系统设置开启」)
                    PermissionResult.DENIED_PERMANENTLY -> env.openPermissionSettings()
                    PermissionResult.DENIED -> Unit   // 点了拒绝 / 返回关窗:留在本页,Denied 阶段
                }
            }
        }
    }
    LaunchedEffect(granted) {
        if (granted) withContext(Dispatchers.IO) { runCatching { ChannelInit.notifyPending(ctx) } }
    }
    // 缓存还没读过、或还是授权前那一份(permitted = false,授权结果触发的刷新在途)→ null = Loading
    val snap by ChannelCache.data.collectAsState()
    val candidates = remember(snap, granted, onLayout) {
        snap?.takeIf { granted && it.permitted }?.let { pickerChannels(it.channels, onLayout, it.labels, ctx.packageName) }
    }
    val phase = channelPickerPhase(granted, asked, candidates)
    val list = (phase as? ChannelPickerPhase.Ready)?.items.orEmpty()
    val ids = list.map { it.channel.id }
    val focusCount = when (phase) {
        is ChannelPickerPhase.Ready -> list.size
        ChannelPickerPhase.Denied, ChannelPickerPhase.Empty -> 1
        else -> 0
    }
    val requesters = remember(focusCount) { List(focusCount.coerceAtLeast(1)) { FocusRequester() } }
    var focusedIdx by remember { mutableStateOf(0) }
    var holder by remember { mutableStateOf<Long?>(null) }
    /** 上一次定位用的 id 表。普通引用:只在下面的效果里读写。 */
    val prevIds = remember { arrayListOf<Long>() }
    // 初始落焦 + 列表变化后按 id 重定位 + 回到前台(nonce)。退出判据只信自报(holder),守卫(ghost、focusCount)都在 key 里(铁律 6)。
    LaunchedEffect(nonce, phase::class, ids, ghost) {
        if (ids != prevIds) {
            focusedIdx = retargetById(prevIds.toList(), ids, focusedIdx)
            prevIds.clear(); prevIds.addAll(ids)
        }
        // 持有者的节点可能已随列表 / 阶段变化被拆掉(被拆时不一定收到「失去」回调):不在当前这一份里就当没有持有者
        val pillPhase = phase == ChannelPickerPhase.Denied || phase == ChannelPickerPhase.Empty
        val h = holder
        if (h != null && (if (h == PILL_ID) !pillPhase else h !in ids)) holder = null
        if (ghost || focusCount == 0) return@LaunchedEffect
        val i = focusedIdx.coerceIn(0, requesters.lastIndex)
        var frames = 0
        while (holder == null && frames < 60) {
            withFrameNanos { }
            runCatching { requesters[i].requestFocus() }
            frames++
        }
    }
    Box(Modifier.fillMaxSize().focusGroup().pageBackdrop()) {
        val status = when (phase) {
            ChannelPickerPhase.Loading -> stringResource(R.string.channel_picker_loading)
            ChannelPickerPhase.Empty -> stringResource(R.string.channel_picker_empty) + "\n" + stringResource(R.string.channel_picker_empty_hint)
            ChannelPickerPhase.Denied -> stringResource(R.string.channel_picker_denied)
            else -> null
        }
        val hint = stringResource(R.string.channel_picker_hint)
        ShellScaffold(
            left = {
                ShellTitle(
                    path = null,
                    title = stringResource(R.string.channel_picker_title),
                    extra = {
                        Column {
                            ShellBody(hint)
                            if (status != null) ShellBody(status)
                        }
                    },
                )
            },
            right = {
                when (phase) {
                    is ChannelPickerPhase.Ready -> LazyColumn(
                        modifier = Modifier.width(GtvLayout.PICKER_LIST_WIDTH.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = GtvLayout.PICKER_LIST_PAD_V.dp),
                    ) {
                        itemsIndexed(list, key = { _, c -> c.channel.id }) { i, c ->
                            val id = c.channel.id
                            PickerRow(
                                app = AppEntry(
                                    packageName = c.channel.pkg,
                                    label = ctx.getString(R.string.channel_row_title, c.appLabel, c.channel.name),
                                    card = null,
                                    isWide = false,
                                ),
                                header = null,
                                modifier = Modifier
                                    .focusRequester(requesters[i.coerceIn(0, requesters.lastIndex)])
                                    .focusProperties { if (ghost) canFocus = false },
                                onFocusChange = { got ->
                                    if (got) { holder = id; focusedIdx = i } else if (holder == id) holder = null
                                },
                                isFirst = i == 0,
                                isLast = i == list.lastIndex,
                                onClick = { if (!ghost) onPick(refFor(c.channel)) },
                            )
                        }
                    }
                    ChannelPickerPhase.Denied, ChannelPickerPhase.Empty -> {
                        val denied = phase == ChannelPickerPhase.Denied
                        MenuPill(
                            label = stringResource(if (denied) R.string.channel_picker_open_settings else R.string.channel_picker_back),
                            onClick = { if (!ghost) { if (denied) env.openPermissionSettings() else onBack() } },
                            modifier = Modifier
                                .focusRequester(requesters[0])
                                .focusProperties { if (ghost) canFocus = false },
                            onFocusChange = { got -> if (got) holder = PILL_ID else if (holder == PILL_ID) holder = null },
                            isFirst = true,
                            isLast = true,
                        )
                    }
                    else -> Unit   // Asking / Loading:右边空着,系统授权窗或读取在途
                }
            },
        )
    }
}
```

- [ ] **Step 7: 挂进编辑页的浮层摞**

`EditScreen.kt`:

1. `private sealed interface EditOverlay` 里加:

```kotlin
    /** R164 选频道页。[onLayout] 打开那一刻布局里已有的频道(残影画关掉前那一份,不现查);[from] = 打开它的「频道」卡,返回落回那里。 */
    class ChannelPick(val onLayout: List<ChannelRef>, val from: ShelfSpot) : EditOverlay {
        override val layer get() = "channels"
    }
```

2. 两处穷举 `when` 各加一支:`returnSpot` 加 `is EditOverlay.ChannelPick -> clampSpot(shelvesNow(), o.from)`;`staleOverlay` 加 `is EditOverlay.ChannelPick -> false`(它不指向某一行,行被删不影响它)。

3. `onChoice` 里 Task 14 留的 `NewRowChoice.CHANNEL -> Unit` 换成:

```kotlin
            NewRowChoice.CHANNEL -> {
                if (choiceFull(shelvesNow(), NewRowChoice.CHANNEL)) return   // 已满 5 行:确定不响应(spec §2.4)
                // 状态并进 overlay = 自动并进 overlayOpen(看门狗 / 定位效果让路,铁律 6);ON_PAUSE 不清它(授权窗回来选页要还在)
                overlay = EditOverlay.ChannelPick(
                    rows.mapNotNull { it.channel },
                    from = ShelfSpot(shelvesNow().lastIndex, ShelfZone.NEW, NewRowChoice.CHANNEL.ordinal),
                )
            }
```

4. `OverlayStack` 的 `when (ov)` 里加(返回键不用另接:编辑页唯一的 `BackHandler` 对任何浮层都是 `closeOverlay(o)`,落回 `ov.from`):

```kotlin
                is EditOverlay.ChannelPick -> ChannelPicker(
                    nonce = focusNonce,
                    onLayout = ov.onLayout,
                    onPick = { ref ->
                        if (overlay === ov) {
                            val next = appendChannelRow(rows, ref)
                            overlay = null
                            if (next !== rows) {
                                rows = next
                                persist()
                                // §2.2:加频道 → 新频道架子的第一颗胶囊(新行一律在最后)
                                retarget(landingAfterAppendChannel(shelvesNow()))
                            } else {
                                // 满了 / 重复(选页开着时别处改过布局)→ 回「频道」卡
                                retarget(clampSpot(shelvesNow(), ov.from))
                            }
                        }
                    },
                    onBack = { closeOverlay(ov) },
                )
```

- [ ] **Step 8: 构建 + 模拟器走一遍授权 → 列表 → 加行**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && export DEV=emulator-5562
gradle --no-daemon testReleaseUnitTest assembleRelease
adb -s $DEV install -r app/build/outputs/apk/release/app-release.apk
adb -s $DEV shell pm revoke com.uniteduone.launcher android.permission.READ_TV_LISTINGS
adb -s $DEV shell am broadcast -f 32 -n test.channels/.Cmd -a test.channels.DROP
adb -s $DEV shell rm -f /sdcard/Android/data/com.uniteduone.launcher/files/channel-init.json
adb -s $DEV push scripts/e2e/fixtures/layout.json /sdcard/Android/data/com.uniteduone.launcher/files/layout.json
adb -s $DEV shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.uniteduone.launcher/.MainActivity
```

手动(遥控键用 `adb -s $DEV shell input keyevent …`):编辑页 →「新的一行」→「频道」→ 系统授权窗(截图 `/tmp/unitedu-e2e/perm-dialog.png`)→ Allow → 列表在 5 s 内出现「E2E Channels · E2E Picks」(夹具收到 `INITIALIZE_PROGRAMS` 才建,`adb -s $DEV logcat -d -s CHFIX` 有 `init`)→ 确定 → 页关掉、最后多一层频道架子、焦点在它第一颗胶囊;`adb -s $DEV shell cat /sdcard/Android/data/com.uniteduone.launcher/files/channel-init.json` 有 `"test.channels": 1`;`layout.json` 最后一行是 `{"icon":"tv","apps":[],"channel":{"pkg":"test.channels","key":"e2e-picks","name":"E2E Picks"}}`。

Expected: 以上逐条成立,无崩溃。

- [ ] **Step 9: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add app/src/main/java/com/uniteduone/launcher/ChannelInit.kt app/src/main/java/com/uniteduone/launcher/ChannelPicker.kt app/src/main/java/com/uniteduone/launcher/ChannelModel.kt app/src/main/java/com/uniteduone/launcher/AppPicker.kt app/src/main/java/com/uniteduone/launcher/EditScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/test/java/com/uniteduone/launcher/ChannelPickerTest.kt && git commit -F - <<'EOF'
feat(channels): 选频道页——授权流程、空态 / 拒绝态、INITIALIZE_PROGRAMS 一次、按 id 重定位(R164 §3.1)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---
### Task 16: 端到端旅程 `j_channels.py`

> 编辑页的步骤依赖 edit-shelves 的界面与文案 key(`edit_choice_app_row` 是那份计划的「应用行」卡标题,`edit_choice_full` 是「已满 N 行」;`open_edit()` 来自那份计划重写后的 `j_edit.py`)。已按那份计划正文核对。

**Files:**
- Create: `scripts/e2e/j_channels.py`
- Modify: `scripts/e2e/run_all.py`(`ALL` 里 `j_edit` 之后加 `"j_channels"`)
- Modify: `scripts/e2e/README.md`(覆盖表加一行)

**Interfaces:**
- Consumes: Task 11 夹具与命令;Task 12–15 的界面与文案 key(`channel_row_title`、`channel_meta_season_episode`、`shelf_new_channel_desc`、`shelf_channel_empty`、`shelf_channel_needs_permission`、`shelf_chip_reauthorize`、`edit_choice_app_row`、`edit_choice_full`、`edit_chip_up`、`edit_chip_delete`、`edit_row_delete_confirm_title`、`channel_picker_title`、`channel_picker_denied`、`channel_picker_open_settings`);`lib.py` 的 `restart` / `move_to` / `long_ok` / `shot` / `check` / `pull_json`。
- Produces: 截图 `E2E_OUT/ch-01…07-*.png`(Task 17 挑进仓库)。

- [ ] **Step 1: 写旅程**

`scripts/e2e/j_channels.py`:

```python
"""频道行(R164 / R165):编辑页加频道 → 授权弹窗 → 首页出现 → 左右 / 长按 / 启动 → 节目变少 / 重建 / 清空
→ 撤销授权 → 选频道页拒绝授权 → 满 5 行 → 删频道架子 → 卸载发布方。

夹具:带代码的发布方 test.channels(fixtures/channels/,fixtures.py 的 channels() 造;本旅程自己 `--user 0` 装,
卸载才发 PACKAGE_FULLY_REMOVED,见 CLAUDE.md 模拟器坑)。只驱动模拟器;并行时用 unitedu-tv-2 / -3(DEV=emulator-5562 / 5564)。
约 8 分钟。
"""
import json, os, re, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import *
from j_edit import open_edit, go_chip

BASE = {"language": "en", "onboardingDone": True, "showTitles": False}
PUB = "test.channels"
PERM = "android.permission.READ_TV_LISTINGS"
TITLES = ["Ocean Deep", "City Lights", "Desert Run", "Night Train", "Snow Peak", "The Long Road", "Paper Moon", "Blue Album"]
REF = {"pkg": PUB, "key": "e2e-picks", "name": "E2E Picks"}

def cmd(action, wait=3.0):
    sh(f"am broadcast -f 32 -n {PUB}/.Cmd -a test.channels.{action}")
    time.sleep(wait)

def rows():
    return (pull_json("layout.json") or {}).get("rows", [])

def channel_rows():
    return [r for r in rows() if "channel" in r]

def fatal():
    out = sh("logcat -b crash -d") + sh("logcat -d | grep -E 'FATAL EXCEPTION|ANR in com.uniteduone'")
    return [l for l in out.splitlines() if l.strip() and not l.startswith("---------")]

def header():
    return S("channel_row_title").replace("%1$s", "E2E Channels").replace("%2$s", "E2E Picks")

def revoke():
    """撤销授权(会杀掉本进程),并清掉「用户拒绝过 / 不再询问」两个标记:`pm revoke` 不清它们,重跑本旅程时
    上一轮「Don't allow」留下的标记会让下一次申请不弹窗、直接回 false(Android 11+ 两次拒绝 = 不再询问)。"""
    sh(f"pm revoke {PKG} {PERM}")
    sh(f"pm clear-permission-flags {PKG} {PERM} user-set user-fixed")

def wait_permission_dialog():
    """等系统授权窗到前台(最多 6 s):选页淡入 → LaunchedEffect → 授权窗起来,宿主负载高时 1.5 s 不一定够。"""
    for _ in range(12):
        if "permissioncontroller" in foreground():
            return True
        time.sleep(0.5)
    return False

def answer_permission(allow=True):
    """系统授权窗(permissioncontroller 的 GrantPermissionsActivity)里把焦点挪到 Allow / Don't allow(旧版 Deny)再按确定。"""
    if not wait_permission_dialog():
        return False
    for direction in ["down"] * 4 + ["up"] * 8:
        lab = screen().label().strip()
        hit = (lab == "Allow") if allow else lab.lower().startswith(("don", "deny"))
        if hit:
            key("ok"); time.sleep(2)
            return True
        key(direction)
    return False

def to_new_channel_card():
    """编辑页一路向下到「新的一行」,在「应用行」卡上就向右,直到焦点在「频道」卡(认它的说明文字,标题「Channel」与架子标签同名)。"""
    for _ in range(40):
        lab = screen().label()
        if S("shelf_new_channel_desc") in lab or S("edit_choice_full").replace("%1$d", "5") in lab:
            return True
        if S("edit_choice_app_row") in lab:
            key("right"); continue
        key("down")
    return False

def to_last_shelf_first_chip():
    """最后一个内容层(本旅程里总是频道架子)的第一颗胶囊:先到「新的一行」,上一格,再一路向左。"""
    to_new_channel_card()
    key("up")
    for _ in range(4):
        key("left")
    return screen()

def run():
    journey("频道行:准备")
    adb("install", "-r", "--user", "0", f"{APKS}/{PUB}.apk")
    cmd("DROP", 2)
    revoke()                               # 撤销运行时权限会杀掉本进程(Android 行为),下面 restart 重新拉起
    sh(f"rm -f {FILES}/channel-init.json")
    restart(BASE, layout=LAYOUT)
    sh("logcat -c")

    journey("频道行:编辑页加频道 → 授权 → 选频道")
    open_edit()
    check("走到「新的一行 → 频道」", to_new_channel_card(), screen().label())
    key("ok"); time.sleep(1.5)
    check("没授权时弹系统授权窗", wait_permission_dialog(), foreground())
    shot("ch-01-permission-dialog")
    check("按 Allow", answer_permission(True))
    check("授权到手", "granted=true" in sh(f"dumpsys package {PKG} | grep {PERM}"))
    seen = False
    for _ in range(10):
        if screen().has(header()):
            seen = True; break
        time.sleep(0.5)
    check("INITIALIZE_PROGRAMS 之后列表里冒出夹具频道", seen)
    check("夹具收到 INITIALIZE_PROGRAMS", "init" in sh("logcat -d -s CHFIX"))
    init = pull_json("channel-init.json") or {}
    check("channel-init.json 记下 test.channels = 1", init.get("notified", {}).get(PUB) == 1, init)
    shot("ch-02-picker")
    check("焦点落到夹具频道", move_to(header(), "down", max_steps=10) is not None)
    key("ok"); time.sleep(1.5)
    last = (rows() or [{}])[-1]
    check("layout.json 最后一行是频道行(格式逐字)", last == {"icon": "tv", "apps": [], "channel": REF}, last)
    s = screen()
    check("选页关了、焦点恰好 1 个", s.count_focused() == 1 and not s.has(S("channel_picker_title")), s.label())
    shot("ch-03-edit-shelf")

    journey("频道行:首页")
    key("back"); time.sleep(2)
    home_intent()
    s = move_to("Ocean Deep", "down", max_steps=8)
    check("首页下到频道行,焦点在 weight 最高的第一张", s is not None, s and s.label())
    s = screen()
    check("行头「应用名 · 频道名」", s.has(header()))
    meta = S("channel_meta_season_episode").replace("%1$s", "1").replace("%2$s", "1")
    check("焦点行卡下第二行写季集", s.has(meta), meta)
    shot("ch-04-home-focused")
    for _ in TITLES[1:]:
        key("right")
    check("8 张按 weight 排,最后一张是 Blue Album", "Blue Album" in screen().label(), screen().label())
    key("right")
    check("行尾右键停住", "Blue Album" in screen().label())

    journey("频道行:海报超时")      # Review Focus 3
    time.sleep(7)    # 黑洞地址,连接超时 5 s
    check("Snow Peak 的 https 海报读不到被记下(之后一分钟不再试)", "10.255.255.1" in sh("logcat -d -s UnitedU"))
    check("没有崩溃、焦点恰好 1 个", not fatal() and screen().count_focused() == 1, fatal()[:3])

    journey("频道行:长按不做事、确定启动节目")
    move_to("Ocean Deep", "left", max_steps=8)
    sh("logcat -c")
    long_ok()
    check("长按后前台仍是 UnitedU", foreground() == PKG, foreground())
    check("长按没弹菜单", not screen().has(S("card_menu_open")))
    check("长按松手也没启动节目", "play" not in sh("logcat -d -s CHFIX"))
    key("ok"); time.sleep(2.5)
    check("确定 = intent_uri 启动节目", foreground() == PUB and "play p=0" in sh("logcat -d -s CHFIX"), foreground())
    key("back"); time.sleep(2)
    check("返回后焦点还在 Ocean Deep", "Ocean Deep" in screen().label(), screen().label())

    journey("频道行:焦点在末张时节目从 8 删到 3")   # Review Focus 4
    move_to("Blue Album", "right", max_steps=8)
    cmd("SHRINK", 3.5)
    s = screen()
    check("焦点恰好 1 个", s.count_focused() == 1, s.count_focused())
    check("焦点落同一行末张 Desert Run", "Desert Run" in s.label(), s.label())
    check("前台仍是 UnitedU、没崩", foreground() == PKG and not fatal())

    journey("频道行:应用重建频道(_id 变,key 不变)")   # Review Focus 1
    sh("logcat -c")
    cmd("REPUBLISH", 3.5)
    log = sh("logcat -d -s CHFIX")
    published = re.findall(r"published ch=(\d+)", log)
    dropped = re.findall(r"dropped e2e-picks ch=(\d+)", log)
    check("夹具确实换了 _id", bool(published and dropped) and published[-1] != dropped[-1], (dropped, published))
    check("行还在(按 internal_provider_id 找回)", screen().has(header()))

    journey("频道行:上面的频道行消失 / 出现,下面应用行的焦点不动")   # owner 裁定:目标按 layoutRow 认行
    lay = json.loads(json.dumps(LAYOUT))
    lay["rows"].insert(1, {"icon": "tv", "apps": [], "channel": REF})   # 行 0 应用、行 1 频道、行 2 起应用
    restart(BASE, layout=lay)
    s = move_to("虎牙直播", "down", max_steps=8)                       # 第 2 行(频道行下面那一行)第 1 张(test.dummy.app05)
    key("right")
    check("焦点在频道行下面那一行的第 2 张(斗鱼)", "斗鱼" in screen().label(), screen().label())
    cmd("CLEAR", 3.5)
    s = screen()
    check("频道清空、行不画了,焦点仍在同一张(斗鱼)、恰好 1 个", "斗鱼" in s.label() and s.count_focused() == 1 and not s.has(header()), s.label())
    cmd("PUBLISH", 3.5)
    s = screen()
    check("节目回来、频道行重新出现在上面,焦点仍在斗鱼", "斗鱼" in s.label() and s.count_focused() == 1 and s.has(header()), s.label())
    tail = json.loads(json.dumps(LAYOUT))
    tail["rows"].append({"icon": "tv", "apps": [], "channel": REF})
    restart(BASE, layout=tail)          # 回到「频道行在最后」的布局:后面「重新授权」那步按「最后一个内容层是频道架子」走

    journey("频道行:清空 → 首页不画,布局里保留")
    cmd("CLEAR", 3.5)
    s = screen()
    check("首页没有这一行了", not s.has(header()))
    check("焦点恰好 1 个", s.count_focused() == 1)
    check("layout.json 里频道行还在", len(channel_rows()) == 1)
    open_edit()
    # 频道架子是第 5 层,进页时在屏幕外(uiautomator 不报屏外节点):先走到它的胶囊上,让它进焦点线再读字
    to_last_shelf_first_chip()
    check("编辑页频道架子写「暂无内容」", screen().has(S("shelf_channel_empty")))
    key("back"); time.sleep(1.5)

    journey("频道行:撤销授权")      # Review Focus 2
    cmd("PUBLISH", 2)
    revoke()                           # 杀进程
    restart(BASE)
    s = screen()
    check("冷启动后首页不画频道行", not s.has(header()))
    check("焦点恰好 1 个、没崩", s.count_focused() == 1 and not fatal())
    open_edit()
    s = to_last_shelf_first_chip()     # 同上:先把屏幕外的频道架子带进焦点线
    check("编辑页频道架子写「需要重新授权」", s.has(S("shelf_channel_needs_permission")))
    shot("ch-05-needs-permission")
    check("「重新授权」是频道架子的第一颗胶囊", S("shelf_chip_reauthorize") in s.label(), s.label())
    key("ok"); time.sleep(1.5)
    check("再次弹授权窗并允许", answer_permission(True))
    time.sleep(2)
    s = screen()
    check("授权回来后架子不再写「需要重新授权」", not s.has(S("shelf_channel_needs_permission")))
    check("焦点恰好 1 个(「重新授权」那颗没了,夹到同一位置)", s.count_focused() == 1)
    key("back"); time.sleep(1.5)

    journey("频道行:选频道页拒绝授权(只有永久拒绝才自动跳系统设置)")   # owner 裁定 2026-10-09
    revoke()
    restart(BASE)
    open_edit()

    def denied_here(what):
        s = screen()
        check(f"{what}:留在选频道页(前台是 UnitedU)、有说明、焦点在「去系统设置开启」",
              foreground() == PKG and s.has(S("channel_picker_denied")) and S("channel_picker_open_settings") in s.label(),
              (foreground(), s.label()))

    def reopen_picker():
        key("back"); time.sleep(1.2)        # 关选页 → 落回「频道」卡
        check("返回落回「频道」卡", S("shelf_new_channel_desc") in screen().label(), screen().label())
        key("ok"); time.sleep(1.5)

    to_new_channel_card(); key("ok"); time.sleep(1.5)
    check("授权窗弹出", wait_permission_dialog(), foreground())
    key("back"); time.sleep(2)             # 从没拒绝过时按返回关窗:前后 rationale 都是 false,但不算永久拒绝
    denied_here("返回关窗")
    reopen_picker()
    check("第一次按 Don't allow", answer_permission(False))
    denied_here("第一次拒绝")
    shot("ch-06-picker-denied")
    reopen_picker()
    check("第二次按 Don't allow(弹了窗;此后系统不再询问)", answer_permission(False))
    denied_here("第二次拒绝")
    reopen_picker()
    time.sleep(1.5)
    fg = foreground()
    check("永久拒绝(系统没弹窗)→ 自动跳本应用的系统设置页(com.android.tv.settings)", "settings" in fg, fg)
    key("back"); time.sleep(2)
    denied_here("从系统设置返回")
    key("back"); time.sleep(1.2)           # → 「频道」卡
    key("back"); time.sleep(1.5)           # 退出编辑页
    revoke()                               # 清掉「不再询问」标记(会杀进程,下一步 restart 拉起)
    sh(f"pm grant {PKG} {PERM}")

    journey("频道行:满 5 行")
    cmd("MANY", 4)
    lay = json.loads(json.dumps(LAYOUT))
    lay["rows"] += [{"icon": "tv", "apps": [], "channel": {"pkg": PUB, "key": f"e2e-many-{k}", "name": f"Many {k}"}} for k in range(5)]
    restart(BASE, layout=lay)
    open_edit()
    to_new_channel_card()
    full = S("edit_choice_full").replace("%1$d", "5")
    check("「频道」卡写「已满 5 行」", full in screen().label(), screen().label())
    shot("ch-07-new-row-full")
    key("ok"); time.sleep(1.5)
    check("确定不响应(没开选频道页)", not screen().has(S("channel_picker_title")))

    journey("频道行:删频道架子(不弹确认)")
    key("up")                                   # 「频道」卡 → 最后一层(频道架子 Many 4)的胶囊,按最近落「上移」
    go_chip("edit_chip_delete"); key("ok"); time.sleep(1.5)
    s = screen()
    check("频道架子直接删、不弹确认", len(channel_rows()) == 4 and not s.has(S("edit_row_delete_confirm_title")), channel_rows())
    check("删后焦点落上一层(Many 3)第一颗胶囊「上移」、恰好 1 个",
          s.count_focused() == 1 and s.label() == S("edit_chip_up"), s.label())
    key("back"); time.sleep(1.5)

    journey("频道行:卸载发布方 → 布局里的频道行删掉")
    sh(f"pm uninstall {PUB}")
    time.sleep(3.5)
    check("layout.json 里没有频道行了", channel_rows() == [], channel_rows())
    s = screen()
    check("焦点恰好 1 个、前台是 UnitedU", s.count_focused() == 1 and foreground() == PKG)

    restart(BASE, layout=LAYOUT)

if __name__ == "__main__":
    run()
    sys.exit(1 if summary() else 0)
```

- [ ] **Step 2: 挂进总表与 README**

`scripts/e2e/run_all.py` 的 `ALL` 改成 `["j_home", "j_edit", "j_channels", "j_upload", …]`(其余顺序不动)。

`scripts/e2e/README.md` 覆盖表在 `j_edit.py` 那一行后加:

```
| `j_channels.py` | 频道行(R164):编辑页「新的一行 → 频道」→ 系统授权窗 → `INITIALIZE_PROGRAMS` 后列表冒出夹具频道 → 写盘格式;首页行头 / 季集 / 8 张按 weight / 行尾停住;https 海报超时;长按不做事、确定启动节目;焦点在末张时节目 8 → 3;频道重建 `_id` 变;清空不画、布局保留;撤销授权(杀进程)→「需要重新授权」→ 重新授权;选频道页返回关窗 / 拒绝两次都留在原地、第三次(系统不再弹窗)才自动跳系统设置;满 5 行变暗;删频道架子不弹确认;卸载发布方删行。夹具 `test.channels` 由本脚本 `--user 0` 装 |
```

并在「跑法」代码块下补一句:「频道夹具 `test.channels` 不在 `fixtures.py` 的默认安装里,`j_channels.py` 自己装。」

- [ ] **Step 3: 在模拟器上跑**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && export DEV=emulator-5562
gradle --no-daemon assembleRelease
adb -s $DEV install -r app/build/outputs/apk/release/app-release.apk
adb -s $DEV shell cmd package set-home-activity com.uniteduone.launcher/.MainActivity
python3 scripts/e2e/fixtures.py
python3 scripts/e2e/j_channels.py
```

Expected: 结尾 `N/N passed`,退出码 0。有 FAIL 就按 `E2E_OUT/FAIL-*.png` 与 logcat 修代码(不是改断言),再跑到全过;同一轮里再跑一遍 `E2E_ONLY=j_edit,j_pkg python3 scripts/e2e/run_all.py`,确认没把编辑页与装卸旅程带坏。

- [ ] **Step 4: 提交**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add scripts/e2e/j_channels.py scripts/e2e/run_all.py scripts/e2e/README.md && git commit -F - <<'EOF'
test(channels): e2e 旅程 j_channels——授权、首页、启动、节目变化、撤销、满行、卸载(R164)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 17: 模拟器验收、截图与性能

**Files:**
- Create: `docs/screenshots/channel-rows/01-home-channel-focused.jpg` … `10-permission-dialog.jpg`(见 Step 2 的表)
- Modify: `docs/WORKLOG.md`(性能数字与截图索引写进 Task 18 的那一条)

**Interfaces:**
- Consumes: Task 11 夹具、Task 16 的截图(`/tmp/unitedu-e2e/ch-*.png`)。
- Produces: 仓库内截图(Task 18 的 README / WORKLOG / CLAUDE.md 引用);性能数字。

- [ ] **Step 1: 以观众身份 1× 看一遍(CLAUDE.md「抄 Google 动效:先对照两边的静止终态」)**

在 `unitedu-tv-2` 上用 Task 12 Step 9 的布局,按下 / 上在应用行与频道行之间来回各 5 次、在频道行里左右走到底再回来,`adb -s emulator-5562 shell screenrecord --size 960x540 --time-limit 20 /sdcard/ch.mp4` 录下来拉回看(模拟器用 `-gpu host` 起才有 50+ fps)。逐条看:换行时整页位移没有跳一下;频道行行头在焦点行全白、离开后变暗;卡下两行字在焦点进来时出现、离开时消失但下面的行不跳;焦点卡放大 + 描边 + 柔光在行尾那张没被裁(R129f);上一行在顶栏下按行头的位置淡出。哪一条不对先修再往下。

- [ ] **Step 2: 截图进仓库**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && mkdir -p docs/screenshots/channel-rows
# e2e 截下的
for pair in "ch-04-home-focused:01-home-channel-focused" "ch-03-edit-shelf:04-edit-channel-shelf" "ch-05-needs-permission:05-edit-needs-permission" \
            "ch-07-new-row-full:06-new-row-full" "ch-02-picker:07-channel-picker" "ch-06-picker-denied:08-picker-denied" "ch-01-permission-dialog:10-permission-dialog"; do
  src="/tmp/unitedu-e2e/${pair%%:*}.png"; dst="docs/screenshots/channel-rows/${pair##*:}.jpg"
  sips -s format jpeg -s formatOptions 85 "$src" --out "$dst"
done
```

再手动补三张(焦点在频道行上一行的应用行 → `02-home-channel-unfocused`;焦点在 Snow Peak 上、等 7 s → `03-home-poster-fallback`;「没有任何频道」→ `09-picker-empty`,造法:`adb -s emulator-5562 shell am broadcast -f 32 -n test.channels/.Cmd -a test.channels.DROP` + `adb -s emulator-5562 shell pm disable-user --user 0 com.google.android.tvrecommendations`,截完**立刻** `pm enable com.google.android.tvrecommendations`),每张 `adb -s emulator-5562 exec-out screencap -p > /tmp/x.png && sips -s format jpeg -s formatOptions 85 /tmp/x.png --out docs/screenshots/channel-rows/<名字>.jpg`。

用 Read 逐张打开核对:截的是 UnitedU(不是原厂桌面 / 「Default Home」对话框)、画面与文件名说的一致。

- [ ] **Step 3: 性能(spec §7:首页 5 应用行 + 5 频道行 × 12)**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && export DEV=emulator-5562
adb -s $DEV install -r --user 0 /tmp/unitedu-e2e/apks/test.channels.apk
adb -s $DEV shell pm grant com.uniteduone.launcher android.permission.READ_TV_LISTINGS
adb -s $DEV shell am broadcast -f 32 -n test.channels/.Cmd -a test.channels.MANY
python3 - <<'PY'
import json, subprocess
lay = json.load(open("scripts/e2e/fixtures/layout.json"))
lay["rows"].append({"icon": "apps", "apps": ["test.dummy.app14", "test.dummy.app15"]})
lay["rows"] += [{"icon": "tv", "apps": [], "channel": {"pkg": "test.channels", "key": f"e2e-many-{k}", "name": f"Many {k}"}} for k in range(5)]
open("/tmp/unitedu-e2e/_perf.json", "w").write(json.dumps(lay))
subprocess.run(["adb", "-s", "emulator-5562", "push", "/tmp/unitedu-e2e/_perf.json", "/sdcard/Android/data/com.uniteduone.launcher/files/layout.json"], check=True)
PY
adb -s $DEV shell am force-stop com.uniteduone.launcher
adb -s $DEV shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.uniteduone.launcher/.MainActivity
sleep 4; adb -s $DEV shell dumpsys gfxinfo com.uniteduone.launcher reset >/dev/null
for i in $(seq 1 9); do adb -s $DEV shell input keyevent 20; sleep 0.6; done
for i in $(seq 1 11); do adb -s $DEV shell input keyevent 22; sleep 0.4; done
for i in $(seq 1 9); do adb -s $DEV shell input keyevent 19; sleep 0.6; done
adb -s $DEV shell dumpsys gfxinfo com.uniteduone.launcher | grep -E "Total frames|Janky|50th|90th|95th|99th"
adb -s $DEV shell dumpsys meminfo com.uniteduone.launcher | grep -E "TOTAL PSS|Graphics"
```

再把布局换回 `scripts/e2e/fixtures/layout.json`(只有应用行)同样量一遍,两组数字并排记进 WORKLOG(Task 18)。Expected:没有崩溃;频道行那组 90th 百分位与纯应用行同一量级(模拟器数字只作相对比较,A95L 的手感由 Gordon 随正式版在电视上看);`Graphics` 内存不因海报无限上涨(LRU 16 MB 封顶)。若 90th 比纯应用行高出一倍以上,先查是不是全部 5 行都在加载海报(应只有焦点行 ± 1),再查 `PosterCard` 是否每帧重组。

- [ ] **Step 4: 收尾:模拟器复原**

```bash
adb -s emulator-5562 shell pm enable com.google.android.tvrecommendations
adb -s emulator-5562 shell pm uninstall test.channels
adb -s emulator-5562 emu kill
```

- [ ] **Step 5: 提交截图**

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add docs/screenshots/channel-rows && git commit -F - <<'EOF'
docs(channels): 频道行模拟器截图(首页 / 编辑页 / 选频道页 / 授权)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 18: 文档同步(CLAUDE.md、REVIEW-GUIDE、README 双语、WORKLOG)

**Files:**
- Modify: `CLAUDE.md`(焦点表「首页卡片」行、新增「选频道页」行、编辑页行补频道架子;「模拟器验证的坑」加一条)
- Modify: `docs/REVIEW-GUIDE.md`(§4 文件地图、§5 约束、§6 已知限制)
- Modify: `README.md`、`README.zh-CN.md`(功能一览、联网说明)
- Modify: `docs/WORKLOG.md`(本轮记录)
- Modify: `docs/superpowers/specs/2026-09-20-gtv-line-design.md`(§12 R164 一行末尾补实现计划路径)

**Interfaces:**
- Consumes: 前面全部任务的文件名与机制;Task 17 的截图与性能数字。
- Produces: 文档。

- [ ] **Step 1: CLAUDE.md**

焦点表「首页卡片 / 顶栏药丸组」那一行末尾追加:

```
**R164 频道行**是 `rows` 里的普通一行(`Row.channel` 非 null,卡是 `programs`,画法 `ChannelRow`,HomeChannelRow.kt)。**目标按 layout.json 行号认行**(owner 裁定 2026-10-08):`tgtRow` / `tgtIdx`(画出来的行号、`remember(rows.size)` 整表重建)换成 `tgtLayoutRow` / `tgtCol`,画在第几行由 `homeTargetCell` 现算(行不在了 → 补上它位置的那一行)——频道行没内容 / 节目晚到时整行出现或消失,画出来的行号整体挪一格,下面那行的焦点不动(e2e `j_channels` 实测);rows 仍按位置组合、**不包 `key(layoutRow)`**(包了之后焦点节点跟着行挪走、没有焦点事件,`focusedCell` 过期)。一切「夹到本行末格」改按 `Row.cellCount`(`homeFocusCol`,与 requester 挂点同一个夹取);频道内容来自进程级 `ChannelCache`(首页 / 编辑页 / 选频道页同一份,MainActivity 按 `channelsRevision` 刷新),真的换了内容时**同一次恢复里**先 `restoring = true`、再换数据、再 `channelsLanding++` 让还原效果重跑——应用在后台删节目、焦点卡被拆那一帧 Compose 抢先给的 (0,0) 改不了目标,焦点落回同一行的末张(e2e `j_channels` 8 → 3 张实测);不把「频道重读在途」整段算进 `stale`(否则 TvProvider 每变一次都会吞掉用户的方向键);还原效果三处放开 `restoring` 都只在 RESUMED 时放(发布方在后台同步频道会让它在 ON_PAUSE 期间重跑,无条件放开等于提前解冻)。没内容 / 没授权的频道行与空应用行同一条过滤(`withChannelContent`)。长按频道卡由 MainActivity 整下吞掉(`focusedOnChannel`),不弹菜单、松手也不启动
```

在「添加应用列表」那一行之后插入新行:

```
| 选频道页(R164,`ChannelPicker`;编辑页 `OverlayStack` 的 `EditOverlay.ChannelPick`) | 照 AppPicker:与它同一处获准的 `LazyColumn`、逐项 requester、`(nonce, 阶段, 频道 id 表, ghost)` 定位效果、只信自报的持有者 `holder`(频道 id;胶囊阶段 = -1),目标 `focusedIdx` 与持有者分开(铁律 5);列表读 `ChannelCache`,随 TvProvider 变化按 `retargetById` 重定位(同 `appsPageRetarget`),持有者的节点被拆时按「不在这一份里」作废再请求;拒绝授权 / 没有频道时只有一颗胶囊(「去系统设置开启」/「返回」),焦点总有地方落;系统授权窗是别的 Activity,期间 ON_PAUSE,回来 `focusNonce++` 接回;状态是编辑页 `overlay = EditOverlay.ChannelPick(…, from)`,自动并进 `overlayOpen`(守卫与 key 同一个量),返回 / 选完落回 `from`(「频道」卡)或新频道架子的第一颗胶囊;残影让路 |
```

编辑页那一行(edit-shelves 计划重写后的货架账本)末尾追加:

```
**R164 频道架子**(`Shelf.ChannelShelf`)在 `shelfLanes` 里只有胶囊那一条,海报预览不可聚焦、不进任何账本;本页货架一律经 `shelvesFor`(带 `ChannelCache` 的内容与应用名)算,胶囊表才与画出来的一致;没授权时最前面多一颗「重新授权」,授权回来它消失,目标 `(层, CHIPS, i)` 由 `clampSpot` 夹到同一位置;删除频道架子不弹确认,落上一层第一颗胶囊(`landingAfterDelete`);拿起卡片上下搬运跳过频道架子(`moveInLayout`)
```

「模拟器验证的坑」末尾加一条:

```
- **测频道行(R164,2026-10-08 起)**:用 e2e 夹具 `test.channels`(带代码,`scripts/e2e/fixtures/channels/`,`fixtures.py` 的 `channels()` 构建、`--user 0` 装),`am broadcast -f 32 -n test.channels/.Cmd -a test.channels.<PUBLISH|SHRINK|CLEAR|REPUBLISH|DROP|MANY>` 改内容。**没授权时 TvProvider 不抛异常、只返回 0 行**(研究 §6.2),「需要重新授权」以 `checkSelfPermission` 为准;`pm revoke … READ_TV_LISTINGS` 会**杀掉本进程**(授权不会),且不清「拒绝过 / 不再询问」标记——要下一次申请必弹窗,接着 `pm clear-permission-flags com.uniteduone.launcher android.permission.READ_TV_LISTINGS user-set user-fixed`;`pm grant` 可跳过授权窗;编辑页第 4 层起的架子进页时在屏幕外,uiautomator 读不到它的字,先把焦点走过去;卸载发布方后 TvProvider 自动清掉它的频道;`unitedu-tv*` 上还有系统的「Apps Spotlight」频道(`com.google.android.tvrecommendations`),要造「一个频道都没有」得 `pm disable-user --user 0 com.google.android.tvrecommendations`,测完立刻 `pm enable`;`INITIALIZE_PROGRAMS` 每个包 + versionCode 只发一次(`channel-init.json`),重测前删掉它。
```

- [ ] **Step 2: REVIEW-GUIDE.md**

§4 标题里的「84 个文件」按 `ls app/src/main/java/com/uniteduone/launcher/*.kt | wc -l` 的实际数改掉(本计划新增 11 个,edit-shelves 另有新增)。§4 文件地图「首页、行、卡片」一节末尾加:

```
- 频道行(R164):`ChannelModel.kt`(纯模型:TvProvider 列名、解析、按 key / 名字匹配、排序截断、元数据、选频道页的列表与重定位)、`ChannelSource.kt`(读 TvProvider:显式投影、`?package=`、按频道取节目)、`ChannelCache.kt`(进程级频道缓存:首页 / 编辑页 / 选频道页共用一份,`channelsRevision` 驱动刷新、内容相同不通知)、`PosterLoader.kt`(海报:ContentResolver / https 5 s、220 px、16 MB LRU、失败一分钟内不重试)、`ChannelLaunch.kt`(启动节目:去掉 URI 授权位、只许发布方自己的包)、`ChannelEnv.kt`(`LocalChannelEnv`)、`ChannelInit.kt`(`INITIALIZE_PROGRAMS` + `channel-init.json`)、`HomeVertical.kt`(首页纵向几何逐行累计)、`HomeChannels.kt` + `HomeChannelRow.kt`(首页频道行)、`ChannelPicker.kt`(选频道页)。
```

§5 约束里加:

```
- **TvProvider 没授权时返回 0 行、不抛异常**:「需要重新授权」以 `checkSelfPermission(READ_TV_LISTINGS)` 判,`SecurityException` 只是兜底。查询一律显式投影、不带 selection(带了 TvProvider 抛 `SecurityException`)。不看 `browsable`(国行与 Google TV 上没人审批,永远是 0)。
- **节目的 `intent_uri` 是别的应用写的**:`ChannelLaunch` 去掉一切 URI 授权位、只启动解析到发布方自己包的 Activity,不合规就退回打开那个应用。不要「简化」掉这两条。
- 频道行在 layout.json 里是带 `channel` 字段的空应用行;**旧版本**读到它是空应用行,第一次写盘就把它变成真的空应用行(版本只升不降,接受),新版本读回时应用行可能多于 5 行——这是合法状态(不能再加应用行),不是损坏。
```

§6 已知问题加:

```
- 频道海报是 https 时会联网下载(除「检查更新」「上传资料」外唯一的联网点,只在用户自己加了频道行时);`http://` 明文海报按网络安全配置画成无图卡。
- 频道数据一变(TvProvider 去抖 500 ms)首页就重读频道内容;只有内容真的变了才冻结焦点目标重定位一次。
```

- [ ] **Step 3: README 双语**

`README.md` 的 Features 列表在「All Apps」那一条之后加:

```
- **Channel rows** (optional, none by default): in Edit Home Screen → New Row → Channel, add a row of shows and movies that another app on the TV publishes as an Android TV "channel" (for example Kodi, Jellyfin, Emby, SmartTube, YouTube, Netflix, CIBN Kumiao). Up to 5 channel rows with 12 posters each; OK opens the show in its app. The first time, Android asks whether UnitedU may read the TV's channel listings; they are only shown on this TV and never uploaded.
```

并把开头「UnitedU never goes online, except …」那一句改成:

```
UnitedU never goes online, except at the moment you press "Check for Updates", while the "Upload Files" page is open (the TV runs a temporary upload server on your local network), and — only if you add a channel row — to download that channel's poster images over HTTPS.
```

`README.zh-CN.md` 功能一览在「所有应用页」那一条之后加:

```
- **频道行**(可选,默认没有):在「编辑桌面 → 新的一行 → 频道」里,把电视上其他应用以 Android TV「频道」形式推送的节目(例如 Kodi、Jellyfin、Emby、SmartTube、YouTube、Netflix、CIBN 酷喵)加成首页的一行;最多 5 行、每行 12 张海报,确定直接在那个应用里打开。第一次加时系统会弹窗询问是否允许 UnitedU 读取电视上的频道信息;只在本机显示,不上传。
```

并把「全程不联网,唯一的例外是……」那一句改成:

```
全程不联网,例外只有三种:你自己按「检查更新」的那一刻;打开「上传资料」页时(电视在局域网里临时开一个上传服务);以及你自己加了频道行时,按 HTTPS 下载那个频道的海报图。
```

- [ ] **Step 4: WORKLOG 与 gtv 线裁定索引**

`docs/WORKLOG.md` 末尾加一条 `## 2026-10-?? 频道行实现(R164 / R165 §3–§5)`(日期写实际完成那天),内容:做了什么(按任务列一行一个)、关键结论(没授权返回 0 行;撤销授权杀进程;频道重读不整段冻结、只在内容真的变了时冻结一次的理由)、e2e 结果(`j_channels` N/N)、Task 17 的两组性能数字与截图目录 `docs/screenshots/channel-rows/`、未验证项(**国行 A95L 上有没有频道数据、Kodi 型「不 browsable 就不写节目」的应用会怎样,都只能随正式版在电视上看**)、Task 11 若补过 `input_id` 写在这里。

`docs/superpowers/specs/2026-09-20-gtv-line-design.md` §12 的 R164 行末尾补:`实现计划 docs/superpowers/plans/2026-10-08-channel-rows.md。`

- [ ] **Step 5: 全量单测 + 构建,提交**

Run: `cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && source scripts/env.sh && gradle --no-daemon testReleaseUnitTest assembleRelease`
Expected: 全部 PASS,BUILD SUCCESSFUL。

```bash
cd /Users/gordonwang/orca/workspaces/UnitedU-launcher/频道推荐 && git add CLAUDE.md docs/REVIEW-GUIDE.md README.md README.zh-CN.md docs/WORKLOG.md docs/superpowers/specs/2026-09-20-gtv-line-design.md && git commit -F - <<'EOF'
docs(channels): 焦点表、模拟器坑、评审入口、README 双语、工作记录(R164)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

## Self-Review(对照 spec)

**1. Spec 覆盖**

| Spec 条目 | 任务 |
|---|---|
| R165 §3.1 选频道页:两栏版式、说明文案、已在桌面的不列、授权窗 / 拒绝 / 没频道、`INITIALIZE_PROGRAMS` 按包名 + versionCode 记 `channel-init.json`、ContentObserver 自动补上 + 按 id 重定位、`OverlayStack` 新一层并进 `overlayOpen`、残影让路 | 15(纯函数 + 组件 + 挂层,`EditOverlay.ChannelPick` 并进 `overlayOpen`)、9(授权、observer、缓存刷新)、7(`ChannelCache`) |
| R165 §3.2 / 旧稿 §2.3:行头、110 dp、按比例宽、weight 降序 ≤ 12、焦点行两行字、`intent_uri` 启动失败打开应用、长按不做事、没内容 / 没授权不画、纵向累计、横向变宽版 | 3、10、12 |
| R165 §3.3 / 旧稿 §3:layout.json 格式、`LayoutRow.channel`、key 规则、`parse` 至少一行应用行、显式投影、不用 selection、只要 TYPE_PREVIEW、不看 browsable、500 ms 去抖、`SecurityException` = 需要重新授权、海报来源 / 5 s / 220 px / 16 MB / 焦点行 ± 1 / 失败深色底、清单权限、onCreate 注册、卸载删频道行 | 1、3、5、7、8、9、12 |
| R165 §4 数量 | 2、3、14 |
| R165 §5 旧路径:`moveCard` / `moveInLayout`、「加到桌面」只列应用行、两种清理、`appendAppRow`(经 `appRowCount`)/ `deleteRow` 判据(`addRowBelow` 已由 edit-shelves 删除)、菜单键 | 4、6、5、2;菜单键在编辑页的新含义归 edit-shelves 计划(spec §2.3),本计划不碰 |
| R165 §2.1 频道架子 / §2.4 新的一行「频道」卡与「已满 5 行」 | 13、14 |
| R165 §6 焦点表 | 18 |
| R165 §7 单测(行数判据、`moveCard` 跳过、往返与全是频道行、缺包清理、匹配、海报宽度、纵向累计、排序截断)、e2e `j_channels.py`、性能 | 1–5、10、3、16、17 |
| 旧稿 §7 权限与清单 | 7、9 |
| 旧稿 §8 测试 | 同上 |
| R165 §8 不做的事 | Global Constraints 末条;没有任务做它们 |

**2. 处理掉的歧义(写进对应任务)**
- spec 说「`SecurityException` = 需要重新授权」,研究 §6.2 实测没授权是 **0 行不抛**——以 `checkSelfPermission` 为准、异常兜底(Task 7)。
- 行头「约 19 sp」与 CLAUDE.md「字号走 Type 七档」冲突——取 `Type.section`(17 sp)(Global Constraints)。
- 「长按不做事」——不只是不弹菜单,松手也不能被 Card 当点击启动节目,所以整下吞掉(Task 12)。
- 频道刷新时冻结焦点目标:整段冻结会吞方向键,不冻结会在焦点卡被拆时跳行首——只在内容真的变了时、同一次恢复里冻结一次(Task 12)。
- 「重新授权」胶囊排最前(spec 只说「多一颗」);拒绝后只有永久拒绝(系统没弹窗)才自动跳系统详情页,点拒绝 / 返回关窗留在原地(owner 裁定 2026-10-09,Task 9 `permissionResult`,Task 13 / 15 调用)。
- `intent_uri` 来自第三方:加了去授权位 + 只许发布方包两条防线(Task 9,spec 没写,安全上必须)。
- 海报是这个应用第一个「用户加了才发生」的联网点,README 的「全程不联网」一句同步改(Task 18)。

**3. 类型一致性**:`ChannelRef(pkg, key, name)`、`LayoutRow.channel`、`Row.channel / programs / channelAppLabel / cellCount`、`Program`、`PosterAspect.widthDp`、`ChannelContent.{NeedsPermission, Missing, Ready}`、`HomeVertical.{rowsTop, focusLine, restBlockTop, restCardTop, shiftY}`、`GtvLayout.rowShiftX(Int, List<Float>, Float)`、`PosterCache.{peek, load}`、`ChannelLaunch.open`、`LocalChannelEnv.{revision, requestPermission, openPermissionSettings}`、`PermissionResult` / `permissionResult`、`Shelf.ChannelShelf / ChannelShelfState / ShelfChip.REAUTHORIZE`、`NewRowChoice.CHANNEL` / `choiceMax`、`ChannelSnapshot` / `ChannelCache` / `channelContentsFrom`、`homeTargetRow` / `homeTargetCell`、`ChannelPicker(nonce, onLayout, onPick, onBack)` 在定义任务与使用任务里逐字一致。

**4. Review Focus**:七条各有钉住它的测试——6 → Task 12 `channelRowAboveDisappearingKeepsTheSameCard` / `channelRowAppearingAboveKeepsTheSameCard` + Task 16「上面的频道行消失 / 出现」;7 → Task 7 `ChannelCacheTest`;1 → Task 3 `reinstalledChannelWithNewIdStillMatchesByKey` + Task 16 REPUBLISH;2 → Task 7 `deniedMeansEveryRowNeedsPermission` + Task 12 `permissionlessAndEmptyChannelRowsAreDropped` + Task 16 撤销授权;3 → Task 8 `fetchGivesUpAfterTheTimeoutWhenTheServerNeverAnswers` / `failedPosterIsNotRetriedWithinAMinute` + Task 16 海报超时;4 → Task 12 `shrinkingRowClampsTheFocusColumnToItsLastCard` + Task 16 SHRINK;5 → Task 1 `oldVersionRewriteBecomesPlainEmptyAppRows` + Task 2 `legacySevenAppRowsAreReadableButFull`。
