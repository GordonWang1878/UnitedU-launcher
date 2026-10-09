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
 * 第三条防线(Task 9 复审):有些 Intent 即使去掉了授权位,系统仍会替桌面授权出去——
 * `Instrumentation.execStartActivity` 对 ACTION_SEND / SEND_MULTIPLE(以及 CHOOSER 套着的)调
 * `migrateExtraStreamToClipData()`,在我们改完 flags **之后**补上 `FLAG_GRANT_READ_URI_PERMISSION`,
 * 系统随之把 `data` 的读权限授给目标。所以这些 action 一律不许;`data` 指向桌面自己的 content provider
 * (如 `….fileprovider`,含 `content://0@…` 这种带用户前缀的写法)也不许。不许 → 回落 [Apps.launch]。
 */
internal fun launchIntentAllowed(action: String?, dataScheme: String?, dataAuthority: String?, self: String): Boolean {
    if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE || action == Intent.ACTION_CHOOSER) return false
    if (dataScheme.equals("content", ignoreCase = true)) {
        val host = dataAuthority?.substringAfterLast('@')
        if (host == null || host.startsWith(self, ignoreCase = true)) return false
    }
    return true
}

/** 后台(低于 STARTED)不刷新频道:onResume 本来就会 channelsRevision++,不会漏。 */
internal fun shouldBumpChannels(state: androidx.lifecycle.Lifecycle.State): Boolean =
    state.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

/** ContentObserver 去抖的下一次等待:平时 [CHANNELS_DEBOUNCE_MS](尾沿),但从第一次变化起最多等 [CHANNELS_MAX_WAIT_MS]。 */
internal fun channelsBumpDelay(now: Long, firstPending: Long): Long =
    minOf(CHANNELS_DEBOUNCE_MS, maxOf(0L, firstPending + CHANNELS_MAX_WAIT_MS - now))

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
            if (info != null && info.exported && launchTargetAllowed(info.packageName, pkg, ctx.packageName) &&
                launchIntentAllowed(intent.action, intent.data?.scheme, intent.data?.authority, ctx.packageName)
            ) {
                // 钉死到刚才审过的那个组件:startActivity 不再自己重新解析(否则审的和起的可能不是同一个)
                intent.component = ComponentName(info.packageName, info.name)
                if (runCatching { ctx.startActivity(intent) }.isSuccess) return true
            }
        }
        return Apps.launch(ctx, pkg)
    }
}
