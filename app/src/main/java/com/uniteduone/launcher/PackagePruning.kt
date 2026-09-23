package com.uniteduone.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log

/**
 * 应用被真正卸载后的清理:`layout.json` 各行里的这个包 + `titles.json` 里它的自定义标题。IO 线程调用。
 * 幂等——活着的 [MainActivity] 和清单里的 [PackageRemovedReceiver] 可能对同一次卸载各跑一遍。
 * @return 是否改了任何文件(调用方据此决定要不要 `revision++`)。
 */
fun pruneUninstalled(ctx: Context, pkg: String): Boolean {
    val layoutChanged = Layout.removePackage(ctx, pkg)
    val titleChanged = pkg in Titles.read(ctx) && Titles.set(ctx, pkg, "")
    return layoutChanged || titleChanged
}

/**
 * **启动 / 回到前台时的兜底清理**(Ruling R68,2026-09-23):把 layout.json 里「此刻没装」的包去掉,连同它们在
 * titles.json 里的自定义标题。补的是 [pruneUninstalled] 漏掉的情况——接收器没收到、进程不在、历史残留
 * (A95L 上一直留着的 `com.huya.nftv`)。IO 线程调用;MainActivity.onResume 触发。@return 是否改了 layout.json。
 *
 * **防误删**(判据见 [planPrune],任一条命中整次跳过、只 Log、不写盘):
 * - 更新中的包:`PACKAGE_REMOVED` / `PACKAGE_ADDED` 带 `EXTRA_REPLACING` 时 MainActivity 记进 [recentReplacements],
 *   [REPLACING_WINDOW_MS] 内不清。没选 `MATCH_UNINSTALLED_PACKAGES`:它区分的是「卸载但保留数据」(`-k`),
 *   对用户来说那就是卸载了,拿它判断反而会把这类包留下;也分不出「正在更新」。
 * - 查询出错:只有 `NameNotFoundException` 算没装,其余异常(Binder 断了、系统服务重启)算 UNKNOWN;
 *   查自己的包当自检,查不到说明 PackageManager 这会儿不可信。
 * - 外部存储没就绪:不查、不写。没有已保存的布局(真正首次运行):不读、不写([Layout.updateSaved])。
 * - 没装的比例异常高:[pruneRatioTooHigh]。
 *
 * 口径是「装没装」(`getPackageInfo`),不是首页的「能不能启动」:装着但被停用、或没有启动入口的包
 * 首页与编辑页都不画([editCardShown]),但它还在机器上,重新启用后应当原位回来,所以不清。
 * layout.json 只有应用行;输入源行由 [Inputs] 枚举、不在文件里,不受影响。
 * titles.json 只清**这一次从布局里去掉的**包:输入源的标题、已经不在布局里的应用标题都不动(键是任意字符串,
 * 不能按「像不像包名」去猜)。
 */
fun pruneMissingPackages(ctx: Context, now: Long = SystemClock.elapsedRealtime()): Boolean {
    if (Paths.baseOrNull(ctx) == null) {
        Log.i(TAG_PRUNE, "启动清理跳过:外部存储没就绪")
        return false
    }
    val pm = ctx.packageManager
    fun presence(pkg: String): PkgPresence = try {
        pm.getPackageInfo(pkg, 0)
        PkgPresence.INSTALLED
    } catch (_: PackageManager.NameNotFoundException) {
        PkgPresence.MISSING
    } catch (e: Throwable) {
        Log.w(TAG_PRUNE, "查询 $pkg 出错: ${e.javaClass.simpleName} ${e.message}")
        PkgPresence.UNKNOWN
    }
    var removed: Set<String> = emptySet()
    val changed = try {
        Layout.updateSaved(ctx) { rows ->
            val plan = planPrune(
                pkgs = rows.flatMap { it.apps },
                presence = ::presence,
                recentlyReplaced = { recentReplacements.isRecent(it, now) },
                canary = presence(ctx.packageName),
            )
            when (plan) {
                is PrunePlan.Skip -> { Log.w(TAG_PRUNE, "启动清理跳过:${plan.reason}"); rows }
                is PrunePlan.Remove -> { removed = plan.pkgs; withoutPackages(rows, plan.pkgs) }
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG_PRUNE, "启动清理失败: ${e.message}")
        false
    }
    if (changed) {
        Log.i(TAG_PRUNE, "启动清理:从布局去掉没装的 ${removed.sorted()}")
        runCatching {
            val titles = Titles.read(ctx)
            removed.filter { it in titles }.forEach { Titles.set(ctx, it, "") }
        }
    }
    return changed
}

private const val TAG_PRUNE = "UnitedU"

/**
 * 桌面进程不在时(别的桌面当前台、在系统设置里卸载)也要清理——`PACKAGE_FULLY_REMOVED` 在
 * API 26+ 的隐式广播白名单里,清单注册的接收器收得到;被卸载的那个应用自己收不到,其它应用才收。
 * 桌面活着时 [MainActivity] 里的动态接收器也会跑一遍并 `revision++`,这里只管落盘。
 */
class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        val pending = goAsync()
        Thread {
            try { pruneUninstalled(ctx.applicationContext, pkg) } finally { pending.finish() }
        }.start()
    }
}
