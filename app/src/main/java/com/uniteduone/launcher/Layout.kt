package com.uniteduone.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.json.JSONArray
import org.json.JSONObject

/** layout.json 的一行(M4b):名字、可选的图标 id(见 RowIcons.kt;null = 按名字回落)、有序的包名。 */
data class LayoutRow(val name: String, val icon: String? = null, val apps: List<String> = emptyList())

/**
 * 内置分类表(= 缺省布局):三行,每行是「国行电视上常见、我们认得出该归哪一类」的包。
 *
 * **顶层 `internal`,不藏在 [Layout] 里**(M7 T10):首次引导第 2 步的纯函数
 * (`plannedLayout` / `skippedLayout`,见 OnboardingPure.kt)要拿它当输入,在 JVM 单元测试里
 * 直接读它;放在 `object Layout` 里也能读,但那样纯函数就平白依赖了一个会碰 `Log` / `org.json`
 * 的对象。它仍然只有这一份:[Layout.read] 的三处回落、引导的「按已装过滤」读的都是它。
 */
internal val DEFAULT_LAYOUT: List<LayoutRow> = listOf(
    LayoutRow(
        name = "VIDEO",
        apps = listOf(
            "com.ktcp.tvvideo", "com.gitvdemo.video", "com.cibn.tv",
            "com.starcor.mango", "com.xiaodianshi.tv.yst",
        ),
    ),
    LayoutRow(name = "LIVE", apps = listOf("com.newtv.cboxtv", "com.huya.nftv", "cn.miguvideo.migutv")),
    LayoutRow(
        name = "MUSIC",
        apps = listOf(
            "com.dangbei.dbmusic.sonyos.tab", "com.netease.cloudmusic.tv",
            "com.tencent.qqmusictv",
        ),
    ),
)

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
 *   {"rows":[{"name":"VIDEO","icon":"movie","apps":["com.a","com.b"]}, ...]}
 * `icon` 是可选字段(M4b 起,见 RowIcons.kt):缺失或不认识的 id 一律按名字回落,不影响读取。
 * 缺失或损坏时回落到内置默认([DEFAULT_LAYOUT]),并把默认写回磁盘,方便 adb 拉下来改。
 *
 * **「文件缺失就写默认」是首次引导三态判定的前提**(spec §8):任何跑过旧版本的用户都一定有
 * layout.json,所以 `MainActivity.onCreate` 必须赶在任何人调 [read] 之前看一眼它在不在。
 */
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
            // Missing = 正式文件与 .prev 都不在:真正的首次运行;Corrupt = 一份都解析不了
            else -> {
                write(ctx, DEFAULT_LAYOUT)
                DEFAULT_LAYOUT
            }
        }
    }

    private fun parse(text: String): List<LayoutRow> {
        if (text.length > 1_000_000) error("layout.json 大得离谱: ${text.length} 字符")
        val rows = JSONObject(text).getJSONArray("rows")
        // "rows":[] 是功能性死胡同:一行都没有 = 一个加号都没有,界面里再也加不回应用,
        // 只能靠齿轮切回 Projectivy 或 adb。当成损坏处理,回落默认。
        if (rows.length() == 0) error("layout.json 里一行都没有")
        return (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            val apps = r.getJSONArray("apps")
            LayoutRow(
                name = r.getString("name"),
                // 缺失 / 非法 id 一律 null,渲染时按名字回落(老文件原样可读)
                icon = r.optString("icon", "").takeIf { isRowIconId(it) },
                apps = (0 until apps.length())
                    .map { apps.getString(it).trim() }
                    .filter { it.isNotEmpty() }
                    .distinct(),   // 同一行里重复的包名会让列表 key 撞车,状态和焦点会挂到错卡片上
            )
        }
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

    /** 同 [update],但无论结果是否与盘上相同都写一次;@return 是否落盘。给「写失败要告诉用户」的调用方(放下移动)。 */
    fun rewrite(ctx: Context, transform: (List<LayoutRow>) -> List<LayoutRow>): Boolean = store.locked {
        write(ctx, transform(read(ctx)))
    }

    /** 从第 rowIndex 行移除一个包(长按菜单「从当前分类移除」)。行不存在或包不在该行 → false,不写盘。 */
    fun removeFromRow(ctx: Context, rowIndex: Int, pkg: String): Boolean = update(ctx) { rows ->
        val row = rows.getOrNull(rowIndex)
        if (row == null || pkg !in row.apps) rows
        else rows.mapIndexed { i, r -> if (i == rowIndex) r.copy(apps = r.apps.filter { it != pkg }) else r }
    }

    /**
     * 纯函数:把一个包从所有行里去掉。一行都没命中时返回**同一个** list(调用方用 `!==` 判断要不要写盘)。
     * 行名、行序、其余包的顺序都不动;整行空了也保留(空行只是没有卡片,不是损坏——`read` 只把「零行」当损坏)。
     */
    fun withoutPackage(rows: List<LayoutRow>, pkg: String): List<LayoutRow> {
        if (rows.none { pkg in it.apps }) return rows
        return rows.map { r -> if (pkg in r.apps) r.copy(apps = r.apps.filter { it != pkg }) else r }
    }

    /**
     * 应用被**真正卸载**(`PACKAGE_FULLY_REMOVED`)后从所有行移除;不在任何行 → false,不写盘。
     * 只在卸载事件上调,**绝不在读取时按「未安装」清理**:默认布局里的包可能还没装(装上就该自动出现),
     * 应用更新过程中包也会短暂"不存在"。2026-09-16 A95L 真机:从长按菜单卸载后首页卡片消失,
     * 编辑页原位却留着一张「未安装 com.dangbei.dbmusic.sonyos.tab」的僵尸卡——就是缺这一步。
     * 同一次卸载会被两个接收器各调一次(见 [pruneUninstalled]),靠 [update] 的锁串行,第二次是无操作。
     */
    fun removePackage(ctx: Context, pkg: String): Boolean = update(ctx) { withoutPackage(it, pkg) }

    /**
     * 原子替换 layout.json(临时文件 → fsync → rename,旧版本先复制成 `.prev`,见 [LockedFile.write])。
     * @return 是否真的落盘了。**调用方必须告诉用户失败**——只 Log 的话,界面上顺序已经变了,
     *   重启后又变回原样,用户只会觉得「我排的顺序总是丢」。
     */
    fun write(ctx: Context, rows: List<LayoutRow>): Boolean = store.locked {
        val base = Paths.baseOrNull(ctx) ?: return@locked false
        try {
            val arr = JSONArray()
            rows.forEach { row ->
                arr.put(
                    JSONObject().put("name", row.name)
                        .also { o -> row.icon?.let { o.put("icon", it) } }
                        .put("apps", JSONArray(row.apps)),
                )
            }
            store.write(base, JSONObject().put("rows", arr).toString(2))
        } catch (e: Throwable) {
            Log.w(TAG, "layout.json 写不了: ${e.message}")
            false
        }
    }
}
