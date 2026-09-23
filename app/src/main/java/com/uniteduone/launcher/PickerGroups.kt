package com.uniteduone.launcher

/**
 * 「添加应用」列表的分组(Gordon 2026-09-24 定,spec §12 R83):真正的应用排在上面,电视设置与厂商系统工具
 * 收进列表最底部的「系统工具」分组;只有裸 MAIN 的系统组件(工厂菜单、VPN 对话框、Health Connect……)一律不列。
 * 分类只看 [PackageFacts] 这几个布尔量,纯函数,JVM 单测直接喂 A95L 实测的包名。
 */
enum class PickerGroup { APPS, SYSTEM_TOOLS }

/**
 * 分类要用的全部事实,由 `Apps` 在 IO 线程从 PackageManager 读出来。
 * @param hasLauncherEntry 有 `LAUNCHER` 或 `LEANBACK_LAUNCHER` 分类的入口。
 * @param hasExportedMain 有一个 **exported** 的裸 `MAIN` 活动(没有启动分类时才有意义;
 *   不可导出的活动列出来也打不开,要到 startActivity 才报 SecurityException)。
 * @param inDefaultLayout 包在内置分类表 [DEFAULT_LAYOUT] 里——当贝音乐这类「只有 MAIN + DEFAULT」
 *   的已知内容应用靠这一条留在列表里。
 */
data class PackageFacts(
    val packageName: String,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val hasLauncherEntry: Boolean,
    val hasExportedMain: Boolean,
    val inDefaultLayout: Boolean,
)

/**
 * 平台 / 厂商命名空间:系统预装、又在这些前缀下的「有启动入口」的包,是电视设置、直播频道、索尼的
 * 帮助 / 计时器 / 通知中心这类工具,不是内容应用。`com.google.android.` 下的 YouTube 同样是预装,
 * 但它随 Play 更新过(FLAG_UPDATED_SYSTEM_APP),在第一条规则就归进应用,走不到这里。
 */
internal val PLATFORM_PREFIXES = listOf("com.android.", "com.google.android.", "com.sony.dtv.", "mediatek.", "com.mediatek.")

internal fun isPlatformNamespace(pkg: String): Boolean =
    pkg == "android" || PLATFORM_PREFIXES.any { pkg.startsWith(it) }

/**
 * 一个包在「添加应用」列表里归哪组;null = 不列。规则按顺序:
 * 1. 本应用自身不列。
 * 2. 有启动分类 + (非系统 **或** 系统但更新过)→ 应用(第三方应用;YouTube、Play 商店这类随商店更新的预装)。
 * 3. 有启动分类 + 系统 + 平台 / 厂商命名空间 → 系统工具(电视设置、索尼工具、直播频道)。
 * 4. 有启动分类 + 系统 + 其他命名空间 → 应用(腾讯视频、乐播投屏、当贝市场这类厂商预装的内容应用)。
 * 5. 只有裸 MAIN:要有 exported 的 MAIN,且(在 [DEFAULT_LAYOUT] 里 **或** 非系统)→ 应用;其余是系统组件,不列。
 */
fun pickerGroupOf(f: PackageFacts, selfPackage: String): PickerGroup? {
    if (f.packageName == selfPackage) return null
    if (f.hasLauncherEntry) {
        if (!f.isSystem || f.isUpdatedSystem) return PickerGroup.APPS
        return if (isPlatformNamespace(f.packageName)) PickerGroup.SYSTEM_TOOLS else PickerGroup.APPS
    }
    if (!f.hasExportedMain) return null
    return if (f.inDefaultLayout || !f.isSystem) PickerGroup.APPS else null
}

/**
 * 「有 N 个新应用」的口径(与列表的「应用」分组对齐):只数归进 [PickerGroup.APPS] 的包——系统工具
 * 和不列的系统组件都不算新应用。其余判据仍是 [isNewApp](装机时间晚于基线、不在桌面上)。
 * @param installed 每个包的事实 + firstInstallTime。
 */
fun countNewApps(
    installed: List<Pair<PackageFacts, Long>>,
    seenAt: Long,
    onLayout: Set<String>,
    selfPackage: String,
): Int = installed.count { (f, firstInstall) ->
    pickerGroupOf(f, selfPackage) == PickerGroup.APPS &&
        isNewApp(firstInstall, seenAt, onLayout = f.packageName in onLayout)
}

/** 列表里的一项:应用本身(只有名字与装机时间,位图按需另读)与它的分组。 */
data class PickerCandidate(val app: AppEntry, val group: PickerGroup)

/** 列表顺序:应用在上、系统工具在下,两组各按名字排(名字读不到时按包名)。 */
fun orderPickerCandidates(items: List<PickerCandidate>): List<PickerCandidate> {
    val byName = compareBy<PickerCandidate, String>(String.CASE_INSENSITIVE_ORDER) { it.app.label.ifBlank { it.app.packageName } }
    return items.filter { it.group == PickerGroup.APPS }.sortedWith(byName) +
        items.filter { it.group == PickerGroup.SYSTEM_TOOLS }.sortedWith(byName)
}
