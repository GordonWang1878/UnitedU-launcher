package com.uniteduone.launcher

import android.content.ComponentName
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
            val info = runCatching { ctx.packageManager.resolveActivity(intent, 0)?.activityInfo }.getOrNull()
            if (info != null && launchTargetAllowed(info.packageName, pkg, ctx.packageName)) {
                // 钉死到刚才审过的那个组件:startActivity 不再自己重新解析(否则审的和起的可能不是同一个)
                intent.component = ComponentName(info.packageName, info.name)
                if (runCatching { ctx.startActivity(intent) }.isSuccess) return true
            }
        }
        return Apps.launch(ctx, pkg)
    }
}
