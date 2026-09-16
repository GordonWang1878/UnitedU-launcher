package com.uniteduone.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
