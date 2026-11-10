package com.uniteduone.launcher

/**
 * 「主页键接管」跳系统无障碍设置时的解析(issue #1,问题 1)。
 *
 * 有的电视把 `ACTION_ACCESSIBILITY_SETTINGS` 解析到框架桩
 * `com.google.android.tv.frameworkpackagestubs.Stubs$SettingsStub`。那个 Activity 什么都不做,
 * 用户按下去像没按。这里把桩跳过,改试候选链里下一个真实页面;一个真实的都没有,就改在屏幕上写手动步骤。
 *
 * 不碰 Android 类型:[candidates] 由调用方把 `PackageManager` 的结果翻译成 [ResolvedActivity] 再送进来。
 */

const val TV_FRAMEWORK_STUB_PACKAGE = "com.google.android.tv.frameworkpackagestubs"

/** 框架桩的类名。包名对不上、但类名就是这一个时也算桩。 */
const val TV_FRAMEWORK_SETTINGS_STUB_CLASS = "com.google.android.tv.frameworkpackagestubs.Stubs\$SettingsStub"

data class ResolvedActivity(
    val packageName: String,
    val className: String,
    val exported: Boolean = true,
)

data class SettingsTarget(
    val page: SystemPage,
    val activity: ResolvedActivity,
)

sealed interface SettingsShortcut {
    /** 按顺序试。前面的弹回或打不开,再试下一个。 */
    data class Open(val targets: List<SettingsTarget>) : SettingsShortcut

    /** 没有任何真实 Activity。调用方在屏幕上给出手动开启步骤。 */
    data object ShowManualSteps : SettingsShortcut
}

fun isFrameworkSettingsStub(activity: ResolvedActivity): Boolean {
    if (activity.packageName == TV_FRAMEWORK_STUB_PACKAGE) return true
    if (activity.className == TV_FRAMEWORK_SETTINGS_STUB_CLASS) return true
    return activity.className.startsWith("$TV_FRAMEWORK_STUB_PACKAGE.")
}

/**
 * [pages] 按产品定的候选顺序。[candidates] 返回这一页能解析到的 Activity,优先的放前面
 * (生产把 `resolveActivity` 的默认匹配放在 `queryIntentActivities` 之前)。
 * 跳过框架桩和未导出的。每一页最多取第一个真实 Activity。一个都没有 → [SettingsShortcut.ShowManualSteps]。
 */
fun resolveSettingsShortcut(
    pages: List<SystemPage>,
    candidates: (SystemPage) -> List<ResolvedActivity>,
): SettingsShortcut {
    val targets = ArrayList<SettingsTarget>()
    for (page in pages) {
        val real = candidates(page).firstOrNull { it.exported && !isFrameworkSettingsStub(it) } ?: continue
        targets.add(SettingsTarget(page, real))
    }
    return if (targets.isEmpty()) SettingsShortcut.ShowManualSteps else SettingsShortcut.Open(targets)
}
