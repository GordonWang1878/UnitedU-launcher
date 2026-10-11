package com.uniteduone.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * R164:频道行指向的频道。[pkg] = 发布它的应用;[key] = 频道的 `internal_provider_id`(应用自己定的、跨重装稳定的 id;
 * 应用没写时为空串,按 [name] 认);[name] = 频道的 `display_name`(加行那一刻的,只用来匹配与在编辑页显示)。
 */
data class ChannelRef(val pkg: String, val key: String, val name: String)

/**
 * layout.json 的一行。**R163 起没有名字**:行只靠图标认——桌面上、编辑页里、各种菜单里都画这一行的图标,不再有行名。
 * [icon] 必为 [ROW_ICON_IDS] 里的合法 id(读盘时缺失 / 非法的值已补成合法值,见 [layoutRowFromDisk]);[apps] 是有序的包名。
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

/**
 * 读盘:把 layout.json 里一行取出来的三个原始字段变成 [LayoutRow]。纯函数(`org.json` 只负责取字段,见 [Layout.parse]),JVM 单测钉住。
 * - **图标**:盘上存了合法 id 就用;没有(缺失 / 非法)时,**老文件**(R163 之前,每行带 `name`)按旧名字回落
 *   (VIDEO → movie、LIVE → tv、MUSIC → music、其它 → tv,外观与取消行名之前一致),新文件没有 `name` → [NEW_ROW_ICON]
 *   (见 [rowIconFromDisk])。**`name` 只在这里用一次**:不进内存里的行,也不再写回盘。
 * - **应用**:去首尾空白、去掉空串、**同一行里去重**(重复的包名会让列表 key 撞车,状态和焦点会挂到错卡片上)。
 */
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

/**
 * 内置分类表(= 缺省布局):三行,每行是「国行电视上常见、我们认得出该归哪一类」的包。
 *
 * **顶层 `internal`,不藏在 [Layout] 里**(M7 T10):首次引导第 2 步的纯函数
 * (`plannedLayout` / `skippedLayout`,见 OnboardingPure.kt)要拿它当输入,在 JVM 单元测试里
 * 直接读它;放在 `object Layout` 里也能读,但那样纯函数就平白依赖了一个会碰 `Log` / `org.json`
 * 的对象。它仍然只有这一份:[Layout.read] 的三处回落、引导的「按已装过滤」读的都是它。
 */
internal val DEFAULT_LAYOUT: List<LayoutRow> = listOf(
    // R160(2026-10-01 Gordon):影视加上腾讯视频的云视听极光(com.ktcp.video,A95L 上就是它),去掉 NewTV极光
    // (com.ktcp.tvvideo);直播只留央视频、咪视界(去掉虎牙);音乐只留网易云、QQ 音乐(去掉当贝音乐)。
    // R163:行没有名字了,三行显式写图标(原来靠名字 VIDEO / LIVE / MUSIC 回落:影片 / 电视 / 音乐)。
    LayoutRow(
        icon = "movie",
        apps = listOf(
            "com.ktcp.video", "com.gitvdemo.video", "com.cibn.tv",
            "com.starcor.mango", "com.xiaodianshi.tv.yst",
        ),
    ),
    LayoutRow(icon = "tv", apps = listOf("com.newtv.cboxtv", "cn.miguvideo.migutv")),
    LayoutRow(icon = "music", apps = listOf("com.netease.cloudmusic.tv", "com.tencent.qqmusictv")),
)

/**
 * **认得的电视应用**:系统预装、只有裸 `MAIN`(没有启动分类)的包里,哪些仍要在所有应用 / 添加应用列表里出现
 * (当贝音乐索尼版就是这样;见 `pickerGroupOf` 的 [PackageFacts.knownTvApp])。
 * R160 前直接拿 [DEFAULT_LAYOUT] 当这张名单,于是「改引导第 2 步放什么」会顺手决定「所有应用里看不看得见」;
 * 现在分开:名单 = 引导的表 + R160 从表里拿掉的三个,谁都不会因为改了引导的表而从所有应用里消失。
 */
internal val KNOWN_TV_APPS: Set<String> =
    DEFAULT_LAYOUT.flatMap { it.apps }.toSet() + setOf("com.ktcp.tvvideo", "com.huya.nftv", "com.dangbei.dbmusic.sonyos.tab")

/**
 * **整份快照写盘**(首次引导第 2 步、编辑页每一步的 `persist`)专用的串行 IO 调度器。
 * [Layout.write] 有锁,文件不会丢;但两次整份写各自在 `Dispatchers.IO` 上跑时**谁先拿到锁不确定**,
 * 较早的快照可能最后落盘、把较新的那份盖掉(编辑页连按两次「上移」,盘上只剩第一次)。
 * `limitedParallelism(1)` 内部是 FIFO 队列:按提交顺序执行,最后落盘的一定是最后一次提交的快照。
 * 读改写型的调用([Layout.update] / [Layout.removePackage] 等)不需要它——它们本来就在锁内合并盘上最新。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal val layoutWrites: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/**
 * layout.json 形如:
 *   {"rows":[{"icon":"movie","apps":["com.a","com.b"]}, ...]}
 * **R164 频道行**多一个可选的 `channel` 对象:`{"icon":"tv","apps":[],"channel":{"pkg":…,"key":…,"name":…}}`(`apps` 恒空)。
 * 至少要有一行应用行,否则读盘当损坏([hasAppRow]);[write] 也拒绝写出这样的列表。
 * **R163 起行没有名字**:写盘只写 `icon` + `apps`。老文件里的 `name` 读盘时只用来给没存合法 `icon` 的行回落图标
 * ([layoutRowFromDisk]),此后写盘就不再带它;新文件没有 `name` 也能读。
 * 缺失或损坏时回落到内置默认([DEFAULT_LAYOUT])按已装过滤的那份([installedDefaultLayout]),并写回磁盘,方便 adb 拉下来改。
 *
 * **「文件缺失就写默认」是首次引导三态判定的前提**(spec §8):任何跑过旧版本的用户都一定有
 * layout.json,所以 `MainActivity.onCreate` 必须赶在任何人调 [read] 之前看一眼它在不在。
 */
/**
 * 内置默认布局按**已装、可启动**过滤(R68):三行照旧,没装的条目不写进文件。与首次引导「继续」同一个函数、
 * 同一个已装集合(`plannedLayout` + `installedDefaultApps`);查询失败时 `Apps.load` 返回空,结果是三个空行
 * (与「跳过」相同),桌面空着但编辑页的「+」都在,不是死胡同。在 [Layout] 的锁里调用(IO 线程)。
 */
internal fun installedDefaultLayout(ctx: Context): List<LayoutRow> =
    runCatching { plannedLayout(DEFAULT_LAYOUT, installedDefaultApps(ctx).keys) }
        .getOrElse { skippedLayout(DEFAULT_LAYOUT) }

/** R164:至少有一行应用行。[Layout.parse] 与 [Layout.write] 共用这一条,写出去的东西一定读得回来。 */
internal fun hasAppRow(rows: List<LayoutRow>): Boolean = rows.any { !it.isChannel }

object Layout {
    private const val TAG = "UnitedU"

    /**
     * 落盘层:锁 + 独立临时文件 + `.prev` 备份,见 [LockedFile](2026-09-23 A95L 卸载一个应用、整个首页被换成
     * 默认分类表的事故)。本对象里**每一个**读、写、读改写都在 `store.locked` 里——read 也要锁:它在文件缺失时
     * 会写默认值,落在别人写盘的间隙里就会把别人的结果抹掉。
     */
    private val store = LockedFile("layout.json")

    /** 有没有任何一份已保存的布局(正式文件或 `.prev`)。首次引导的「老用户 / 新装」判定用它,不单看正式文件。 */
    fun hasSaved(ctx: Context): Boolean = store.locked {
        Paths.layoutJson(ctx).exists() || Paths.layoutPrev(ctx).exists()
    }

    fun read(ctx: Context): List<LayoutRow> = store.locked {
        val base = Paths.baseOrNull(ctx)
        if (base == null) {
            Log.w(TAG, "外部存储没挂上,这次用内存里的默认布局,不写盘")
            return@locked DEFAULT_LAYOUT
        }
        // 回落口径见 LockedFile.load:正式文件 → (坏的改名 .bad 留证)→ .prev → 都不行才写回默认值。
        // 不先试 .prev 就写默认的话,每次开机都静默退回默认,用户只看到「我排的顺序又没了」。
        when (val got = store.load(base, log = { Log.w(TAG, it) }, parse = ::parse)) {
            is LockedFile.Load.Ok -> {
                if (got.restored) write(ctx, got.value)
                got.value
            }
            // Missing = 正式文件与 .prev 都不在:真正的首次运行;Corrupt = 一份都解析不了。
            // 写回的默认值**按已装过滤**(Ruling R68,与首次引导「继续」同一口径:plannedLayout + installedDefaultApps):
            // 不过滤的话,没装的默认条目会被启动清理([pruneMissingPackages])当成「没装」去掉——在一台一个默认应用
            // 都没装的机器上,11 个里 11 个没装,正好撞上「比例异常就整次跳过」,于是永远清不掉。
            else -> {
                val fallback = installedDefaultLayout(ctx)
                write(ctx, fallback)
                fallback
            }
        }
    }

    /** 文本 → 行(`internal`:JVM 单测直接喂文本,见 LayoutTest)。语法坏了 / 缺 `rows` / 缺 `apps` / 零行 / 没有应用行(全是频道行,R164)都抛,交给 [LockedFile.load] 当损坏处理。 */
    internal fun parse(text: String): List<LayoutRow> {
        if (text.length > 1_000_000) error("layout.json 大得离谱: ${text.length} 字符")
        val rows = JSONObject(text).getJSONArray("rows")
        // "rows":[] 是功能性死胡同:一行都没有 = 一个加号都没有,界面里再也加不回应用,
        // 只能靠齿轮切回 Projectivy 或 adb。当成损坏处理,回落默认。
        if (rows.length() == 0) error("layout.json 里一行都没有")
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
        if (!hasAppRow(parsed)) error("layout.json 里没有应用行")
        return parsed
    }

    /** 行 → 文本(`internal`:单测核对落盘字段)。每行只有 `icon` + `apps`,**不写 `name`**(R163);频道行(R164)多写 `channel` 对象,`apps` 恒写空数组;缩进 2 格,adb 拉下来好读好改。 */
    internal fun toJson(rows: List<LayoutRow>): String {
        val arr = JSONArray()
        rows.forEach { row ->
            val o = JSONObject().put("icon", row.icon).put("apps", JSONArray(if (row.isChannel) emptyList() else row.apps))
            row.channel?.let { o.put("channel", JSONObject().put("pkg", it.pkg).put("key", it.key).put("name", it.name)) }
            arr.put(o)
        }
        return JSONObject().put("rows", arr).toString(2)
    }

    /**
     * 读 → 改 → 写 一整段在锁内(照 `SettingsStore.update`)。[transform] 返回**同一个** list 表示不用写盘。
     * @return 是否真的写了盘(没变化也是 false)。凡是「先读盘上最新、再合并写回」的调用方都走这里,
     *   不要自己 read 再 write——两步之间别的写者插进来,它的结果就被覆盖掉。
     */
    fun update(ctx: Context, transform: (List<LayoutRow>) -> List<LayoutRow>): Boolean = store.locked {
        val rows = read(ctx)
        val next = transform(rows)
        next !== rows && write(ctx, next)
    }

    /**
     * 同 [update],但**没有任何已保存的布局时什么都不做**(不读,也就不会写默认值):启动清理
     * ([pruneMissingPackages])挂在 onResume 上,真正首次运行时 layout.json 还不在——若照 [update] 走 [read],
     * 就会抢在引导前把默认布局写出来。判断与读改写在同一把锁里。
     */
    fun updateSaved(ctx: Context, transform: (List<LayoutRow>) -> List<LayoutRow>): Boolean = store.locked {
        hasSaved(ctx) && update(ctx, transform)
    }

    /** 同 [update],但无论结果是否与盘上相同都写一次;@return 是否落盘。给「写失败要告诉用户」的调用方(放下移动)。 */
    fun rewrite(ctx: Context, transform: (List<LayoutRow>) -> List<LayoutRow>): Boolean = store.locked {
        write(ctx, transform(read(ctx)))
    }

    /** 从第 rowIndex 行移除一个包(长按菜单「从这一行移出」)。行不存在或包不在该行 → false,不写盘。 */
    fun removeFromRow(ctx: Context, rowIndex: Int, pkg: String): Boolean = update(ctx) { rows ->
        val row = rows.getOrNull(rowIndex)
        if (row == null || pkg !in row.apps) rows
        else rows.mapIndexed { i, r -> if (i == rowIndex) r.copy(apps = r.apps.filter { it != pkg }) else r }
    }

    /**
     * 纯函数:把一个包从所有行里去掉。一行都没命中时返回**同一个** list(调用方用 `!==` 判断要不要写盘)。
     * 行序、行图标、其余包的顺序都不动;整行空了也保留(空行只是没有卡片,不是损坏——`read` 只把「零行」当损坏)。
     * R164:`channel.pkg` 是它的频道行整行删掉;频道行不计入应用行,删掉它不会让应用行少于 1。
     */
    fun withoutPackage(rows: List<LayoutRow>, pkg: String): List<LayoutRow> {
        if (rows.none { pkg in it.apps || it.channel?.pkg == pkg }) return rows
        // R164:发布方没了,它的频道行一起删(spec §3.3);应用行里的卡照旧移出、空行保留
        return rows.filter { it.channel?.pkg != pkg }
            .map { r -> if (pkg in r.apps) r.copy(apps = r.apps.filter { it != pkg }) else r }
    }

    /**
     * 应用被**真正卸载**(`PACKAGE_FULLY_REMOVED`)后从所有行移除;不在任何行 → false,不写盘。
     * 2026-09-16 A95L 真机:从长按菜单卸载后首页卡片消失,编辑页原位却留着一张「未安装 com.dangbei.dbmusic.sonyos.tab」
     * 的僵尸卡——就是缺这一步。同一次卸载会被两个接收器各调一次(见 [pruneUninstalled]),靠 [update] 的锁串行,第二次是无操作。
     *
     * **读取时仍然不按「未安装」清理**([read] 只读不删),但「没装的包留在文件里等它装上自动出现」这条旧规则
     * **作废了**(Ruling R68,2026-09-23,Gordon:「应用卸载后,卡片消失,布局里那个位置也消失」)。旧规则的前提是
     * 默认布局列着还没装的包、装上就该冒出来——首次引导起默认布局按已装过滤后才写盘(`plannedLayout`,
     * [read] 的缺失回落也是),这个前提不存在了。现行规则:卸载事件上清(这里),**再加**启动 / 回到前台时的兜底清理
     * ([pruneMissingPackages]:接收器没收到、进程不在、历史残留),防误删的判据在那里(更新窗口、查询出错、比例异常)。
     * 清理不放进 [read]:read 在锁里、被每个读者调用,一次 PackageManager 抽风就会连带写盘。
     */
    fun removePackage(ctx: Context, pkg: String): Boolean = update(ctx) { withoutPackage(it, pkg) }

    /**
     * 原子替换 layout.json(临时文件 → fsync → rename,旧版本先复制成 `.prev`,见 [LockedFile.write])。
     * @return 是否真的落盘了。**调用方必须告诉用户失败**——只 Log 的话,界面上顺序已经变了,
     *   重启后又变回原样,用户只会觉得「我排的顺序总是丢」。
     */
    fun write(ctx: Context, rows: List<LayoutRow>): Boolean = store.locked {
        val base = Paths.baseOrNull(ctx) ?: return@locked false
        // R164:写出去读不回来的列表(没有应用行)会被 parse 当损坏、回落 .prev / 默认 = 静默丢掉这次编辑
        if (!hasAppRow(rows)) {
            Log.w(TAG, "layout.json 拒绝写入:没有任何应用行")
            return@locked false
        }
        try {
            store.write(base, toJson(rows))
        } catch (e: Throwable) {
            Log.w(TAG, "layout.json 写不了: ${e.message}")
            false
        }
    }
}
