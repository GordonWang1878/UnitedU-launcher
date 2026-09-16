package com.uniteduone.launcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * layout.json 形如:
 *   {"rows":[{"name":"VIDEO","apps":["com.a","com.b"]}, ...]}
 * 缺失或损坏时回落到内置默认,并把默认写回磁盘,方便 adb 拉下来改。
 */
object Layout {
    private const val TAG = "UnitedU"

    private val DEFAULT = listOf(
        "VIDEO" to listOf(
            "com.ktcp.tvvideo", "com.gitvdemo.video", "com.cibn.tv",
            "com.starcor.mango", "com.xiaodianshi.tv.yst",
        ),
        "LIVE" to listOf("com.newtv.cboxtv", "com.huya.nftv", "cn.miguvideo.migutv"),
        "MUSIC" to listOf(
            "com.dangbei.dbmusic.sonyos.tab", "com.netease.cloudmusic.tv",
            "com.tencent.qqmusictv",
        ),
    )

    fun read(ctx: Context): List<Pair<String, List<String>>> {
        if (Paths.baseOrNull(ctx) == null) {
            Log.w(TAG, "外部存储没挂上,这次用内存里的默认布局,不写盘")
            return DEFAULT
        }
        val f = Paths.layoutJson(ctx)
        if (!f.exists()) {
            write(ctx, DEFAULT)
            return DEFAULT
        }
        // catch Throwable:超大文件时 readText 抛的是 OutOfMemoryError,那是 Error 不是 Exception
        return try {
            if (f.length() > 1_000_000) error("layout.json 大得离谱: ${f.length()} 字节")
            val rows = JSONObject(f.readText()).getJSONArray("rows")
            // "rows":[] 是功能性死胡同:一行都没有 = 一个加号都没有,界面里再也加不回应用,
            // 只能靠齿轮切回 Projectivy 或 adb。当成损坏处理,回落默认。
            if (rows.length() == 0) error("layout.json 里一行都没有")
            (0 until rows.length()).map { i ->
                val r = rows.getJSONObject(i)
                val apps = r.getJSONArray("apps")
                r.getString("name") to (0 until apps.length())
                    .map { apps.getString(it).trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()   // 同一行里重复的包名会让列表 key 撞车,状态和焦点会挂到错卡片上
            }
        } catch (e: Throwable) {
            // 把坏文件留证但改名,并写回默认值——否则每次开机都静默退回默认,
            // 用户只看到「我排的顺序又没了」,却不知道文件是坏的。
            Log.w(TAG, "layout.json 读不了,改名保留并重写默认: ${e.message}")
            runCatching { f.renameTo(Paths.layoutBad(ctx)) }
            write(ctx, DEFAULT)
            DEFAULT
        }
    }

    /** 从第 rowIndex 行移除一个包(长按菜单「从当前分类移除」)。行不存在或包不在该行 → false,不写盘。 */
    fun removeFromRow(ctx: Context, rowIndex: Int, pkg: String): Boolean {
        val rows = read(ctx)
        val row = rows.getOrNull(rowIndex) ?: return false
        if (pkg !in row.second) return false
        return write(ctx, rows.mapIndexed { i, r -> if (i == rowIndex) r.first to r.second.filter { it != pkg } else r })
    }

    /**
     * 纯函数:把一个包从所有行里去掉。一行都没命中时返回**同一个** list(调用方用 `!==` 判断要不要写盘)。
     * 行名、行序、其余包的顺序都不动;整行空了也保留(空行只是没有卡片,不是损坏——`read` 只把「零行」当损坏)。
     */
    fun withoutPackage(rows: List<Pair<String, List<String>>>, pkg: String): List<Pair<String, List<String>>> {
        if (rows.none { pkg in it.second }) return rows
        return rows.map { r -> if (pkg in r.second) r.first to r.second.filter { it != pkg } else r }
    }

    /**
     * 应用被**真正卸载**(`PACKAGE_FULLY_REMOVED`)后从所有行移除;不在任何行 → false,不写盘。
     * 只在卸载事件上调,**绝不在读取时按「未安装」清理**:默认布局里的包可能还没装(装上就该自动出现),
     * 应用更新过程中包也会短暂"不存在"。2026-09-16 A95L 真机:从长按菜单卸载后首页卡片消失,
     * 编辑页原位却留着一张「未安装 com.dangbei.dbmusic.sonyos.tab」的僵尸卡——就是缺这一步。
     */
    fun removePackage(ctx: Context, pkg: String): Boolean {
        val rows = read(ctx)
        val next = withoutPackage(rows, pkg)
        return next !== rows && write(ctx, next)
    }

    /**
     * 先写临时文件再改名:直接 writeText 会先截断,断电或进程被杀就留下半截文件。
     * @return 是否真的落盘了。**调用方必须告诉用户失败**——只 Log 的话,界面上顺序已经变了,
     *   重启后又变回原样,用户只会觉得「我排的顺序总是丢」。
     */
    fun write(ctx: Context, rows: List<Pair<String, List<String>>>): Boolean {
        val base = Paths.baseOrNull(ctx) ?: return false
        val tmp = java.io.File(base, "layout.json.tmp")
        return try {
            val arr = JSONArray()
            rows.forEach { (name, apps) ->
                arr.put(JSONObject().put("name", name).put("apps", JSONArray(apps)))
            }
            // rename 只保证「要么旧要么新」,不保证内容已经到介质上;不 fsync 的话
            // 断电可能留下一个长度正确但内容全是 0 的文件。
            java.io.FileOutputStream(tmp).use { out ->
                out.write(JSONObject().put("rows", arr).toString(2).toByteArray())
                out.flush()
                out.fd.sync()
            }
            val dst = Paths.layoutJson(ctx)
            // 第一次 rename 失败才走 delete + 重试,而那一步是非原子的:失败就两头皆空,
            // 所以失败时把 tmp 留着(下面不删),至少数据还在盘上。
            if (tmp.renameTo(dst)) return true
            dst.delete()
            tmp.renameTo(dst)
        } catch (e: Throwable) {
            Log.w(TAG, "layout.json 写不了: ${e.message}")
            false
        }
    }
}
