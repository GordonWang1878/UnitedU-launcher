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

/**
 * 发完之后要记成什么:只把**真的发出去了**的包([sent])按此刻的 versionCode 记上;发失败的不记,下次进页再发
 * (记了就是「这个版本号下永远不会再发」)。
 */
internal fun recordInit(done: Map<String, Long>, versions: Map<String, Long>, sent: List<String>): Map<String, Long> =
    done + sent.mapNotNull { pkg -> versions[pkg]?.let { pkg to it } }

object ChannelInit {
    const val ACTION = "android.media.tv.action.INITIALIZE_PROGRAMS"
    private const val TAG = "UnitedU"
    private val store = LockedFile("channel-init.json")

    /** IO 线程。@return 这次真的通知到了哪些包。 */
    fun notifyPending(ctx: Context): List<String> {
        val base = Paths.baseOrNull(ctx) ?: return emptyList()
        val pm = ctx.packageManager
        // 列别家的接收器靠清单里的 QUERY_ALL_PACKAGES(Android 11+ 包可见性)
        val receivers: Map<String, List<ComponentName>> =
            runCatching { pm.queryBroadcastReceivers(Intent(ACTION), 0) }.getOrDefault(emptyList())
                .mapNotNull { it.activityInfo }
                .groupBy({ it.packageName }, { ComponentName(it.packageName, it.name) })
        val versions = receivers.keys.associateWith { pkg -> runCatching { pm.getPackageInfo(pkg, 0).longVersionCode }.getOrDefault(-1L) }
            .filterValues { it >= 0 }
        return store.locked {
            // 落盘铁律:读取统一走 store.load(坏文件改名 .bad 留证、回落 .prev);parseChannelInit 不抛,坏内容按空表
            val done = (store.load(base, log = { Log.w(TAG, it) }, parse = ::parseChannelInit) as? LockedFile.Load.Ok)?.value ?: emptyMap()
            val pending = pendingInit(versions, done, ctx.packageName)
            // FLAG_INCLUDE_STOPPED_PACKAGES:装上后还没打开过的应用(adb 装的夹具也是)处于 stopped 状态,广播默认不送(显式的也不送);
            // 不带它的话这次发不到、却照样记成「已通知」,这个版本号下永远不会再发。
            // 一个包的每个接收器都发成功才算通知到(map 再 all:不短路,每个都发)。
            val sent = pending.filter { pkg ->
                receivers[pkg].orEmpty().map { cn ->
                    runCatching {
                        ctx.sendBroadcast(Intent(ACTION).setComponent(cn).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
                    }.onFailure { Log.w(TAG, "INITIALIZE_PROGRAMS → $cn 发送失败: ${it.message}") }.isSuccess
                }.all { it }
            }
            if (sent.isNotEmpty()) {
                runCatching { store.write(base, channelInitToJson(recordInit(done, versions, sent))) }
                    .onFailure { Log.w(TAG, "channel-init.json 写不了: ${it.message}") }
                Log.i(TAG, "INITIALIZE_PROGRAMS → $sent")
            }
            sent
        }
    }
}
