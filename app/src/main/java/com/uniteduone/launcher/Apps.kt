package com.uniteduone.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max

/** 枚举可启动的应用,并按「自定义图 → leanback banner → 应用图标」选卡片图。 */
object Apps {

    /**
     * @param extraPackages 配置里点名、但可能没有标准启动分类的包。
     *   当贝音乐(com.dangbei.dbmusic.sonyos.tab)就是这种:它的入口只有 MAIN + DEFAULT,
     *   没有 LAUNCHER 也没有 LEANBACK_LAUNCHER,按分类查一个都查不到。
     * @param withBitmaps 只有真正要画在卡片上的包才解码位图。
     *   之前给电视上**每一个**可启动应用都建位图(约 50 个),而首页只用其中 11 个,
     *   其余立刻被丢掉——纯浪费,且每次退出编辑界面都重跑一遍。
     * @param withLabels 只有这些包需要读标签(读标签要打开对方的资源包)。null = 全都要。
     *   同一个道理:首页只画 11 张卡,却给全部约 50 个应用读了标签。
     *
     * 「添加应用」列表不走这里,走 [pickerCandidates](按已安装包全量枚举 + 分组过滤,R83)。
     */
    fun load(
        ctx: Context,
        extraPackages: Collection<String> = emptyList(),
        withBitmaps: Set<String>? = null,
        withLabels: Set<String>? = null,
    ): Map<String, AppEntry> {
        val pm = ctx.packageManager
        val found = launcherEntries(pm)
        // 兜底:不带 category 按包名查一次
        for (pkg in extraPackages) {
            if (found.containsKey(pkg)) continue
            val intent = Intent(Intent.ACTION_MAIN).setPackage(pkg)
            runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
                .firstOrNull()?.let { found[pkg] = it }
        }
        return found.mapValues { (pkg, ri) ->
            // **逐包兜底,不是整批**。原来整个 mapValues 外面才有一层 runCatching:
            // 任何一个包在被替换的中间态下 loadBanner/loadIcon 抛异常,整张桌面就变成空的,
            // 而重建恰恰由 PACKAGE_CHANGED 触发 —— 正是最容易抛的那一刻。
            runCatching { entryOf(ctx, pm, pkg, ri, withBitmaps, withLabels) }
                .getOrElse { AppEntry(pkg, "", null, isWide = false) }
        }
    }

    /** 按两个启动分类枚举(LEANBACK 优先),包名 → 它的入口。 */
    private fun launcherEntries(pm: PackageManager): LinkedHashMap<String, ResolveInfo> {
        val found = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            // queryIntentActivities 会在结果撑破 Binder 事务上限时抛 TransactionTooLargeException。
            // 一个分类查失败不该让整张桌面变空,另一个分类的结果照样能用。
            for (ri in runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())) {
                found.putIfAbsent(ri.activityInfo.packageName, ri)
            }
        }
        return found
    }

    /**
     * 包的第一个 **exported** 裸 MAIN 活动。不带 category 的查询会匹配「任何声明了 action MAIN 的活动」,
     * 而 queryIntentActivities 不按 exported 过滤,不可导出的要到 startActivity 才报 SecurityException ——
     * 列出来却打不开,提示词还会说「可能已被卸载」。
     */
    private fun exportedMain(pm: PackageManager, pkg: String): ResolveInfo? =
        runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(pkg), 0) }
            .getOrDefault(emptyList())
            .firstOrNull { it.activityInfo?.exported == true }

    private val defaultLayoutPackages: Set<String> by lazy { DEFAULT_LAYOUT.flatMap { it.apps }.toSet() }

    /**
     * 读出分类用的事实([pickerGroupOf] 的输入)与可用的入口。**裸 MAIN 查询只对可能入选的包做**
     * (非系统、或在分类表里的):系统组件无论有没有 exported MAIN 都不列,不必为它们多一次包查询——
     * 电视上这类包上百个。
     */
    private fun factsOf(
        pm: PackageManager,
        info: ApplicationInfo,
        launcher: ResolveInfo?,
    ): Pair<PackageFacts, ResolveInfo?> {
        val pkg = info.packageName
        val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val inDefault = pkg in defaultLayoutPackages
        val main = if (launcher == null && (!system || inDefault)) exportedMain(pm, pkg) else null
        val facts = PackageFacts(
            packageName = pkg,
            isSystem = system,
            isUpdatedSystem = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            hasLauncherEntry = launcher != null,
            hasExportedMain = main != null,
            inDefaultLayout = inDefault,
        )
        return facts to (launcher ?: main)
    }

    /**
     * 「添加应用」列表的候选(R83,Gordon 2026-09-24):按已安装包**全量**枚举——当贝音乐这类
     * 「只有 MAIN + DEFAULT、没有启动分类」的应用移出桌面后要能加回来(2026-09-11 实测:只按分类查,
     * 移出后在列表里彻底消失)——再用 [pickerGroupOf] 分组:应用在上、系统工具在下,只有裸 MAIN 的
     * 系统组件一律不列。[exclude](已在桌面上的)与本应用自身不列。只读名字与装机时间,**不解码位图**:
     * 卡片图由每一项上屏时 [pickerCard] 按需读。IO 线程调用。
     */
    fun pickerCandidates(ctx: Context, exclude: Set<String>): List<PickerCandidate> {
        val pm = ctx.packageManager
        val launcher = launcherEntries(pm)
        val installed = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        // 包全集:已安装包 ∪ 启动分类查到的(getInstalledPackages 失败时启动分类那份照样能用)
        val infos = LinkedHashMap<String, Pair<ApplicationInfo, Long>>()
        for (pi in installed) pi.applicationInfo?.let { infos[pi.packageName] = it to pi.firstInstallTime }
        for ((pkg, ri) in launcher) if (pkg !in infos) ri.activityInfo?.applicationInfo?.let { infos[pkg] = it to 0L }
        val out = ArrayList<PickerCandidate>()
        for ((pkg, v) in infos) {
            if (pkg in exclude || pkg == ctx.packageName) continue
            val (info, firstInstall) = v
            runCatching {
                val (facts, ri) = factsOf(pm, info, launcher[pkg])
                val group = pickerGroupOf(facts, ctx.packageName) ?: return@runCatching
                val entry = ri ?: return@runCatching
                val label = runCatching { entry.loadLabel(pm)?.toString() }.getOrNull().orEmpty()
                out += PickerCandidate(
                    AppEntry(pkg, label, card = null, isWide = false, firstInstallTime = firstInstall),
                    group,
                    canUninstall = canUninstall(facts.isSystem, facts.isUpdatedSystem),
                )
            }
        }
        return orderPickerCandidates(out)
    }

    /**
     * @param useCustom false = 只看应用自带的图(横幅 / 图标),不读用户换的自定义图——「换卡片图」网格里
     *   「恢复原图」那一格的预览用([originalIcon]),与首页恢复之后的卡面同一份。
     */
    private fun entryOf(
        ctx: Context,
        pm: PackageManager,
        pkg: String,
        ri: ResolveInfo,
        withBitmaps: Set<String>?,
        withLabels: Set<String>?,
        useCustom: Boolean = true,
    ): AppEntry {
        val label = if (withLabels == null || pkg in withLabels) {
            runCatching { ri.loadLabel(pm)?.toString() }.getOrNull().orEmpty()
        } else ""
        if (withBitmaps != null && pkg !in withBitmaps) {
            return AppEntry(
                pkg, label, card = null, isWide = false,
                firstInstallTime = runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrDefault(0L),
            )
        }
        // 自定义图(R88,Gordon 2026-09-27 真机:把爱奇艺的方形图标换成卡片图,卡片没补底):与应用横幅**同一条判据**
        // [fitsAsBanner] ——过了才当横幅铺满,否则当图标处理、补边缘色底。原来自定义图一律 isWide = true,
        // 方图被当横幅画成卡片左侧一块方形、右边透出壁纸。比例按**原图尺寸**判:解码([decodeScaled] 只按 2 的幂采样)
        // 与 [shrink] 都保持比例、不裁,但原图尺寸最准,读一次 bounds 很便宜。
        val custom = if (!useCustom) null else Paths.iconFor(ctx, pkg).takeIf { it.exists() }?.let { f ->
            val b = runCatching { decodeScaled(f.absolutePath, CARD_W, CARD_H) }.getOrNull()
                ?.let { shrink(it, CARD_W, CARD_H) } ?: return@let null
            val (w, h) = imageSize(f.absolutePath) ?: (b.width to b.height)
            b to fitsAsBanner(w, h, opaqueFraction(edgePixelsOf(b)))
        }
        // 横幅只有在接近 16:9 **且真的铺满(边缘基本不透明)**时才当横幅铺满;比例不对的(如咪咕)、
        // 或四周透明的 logo(如网易云的 loadLogo,宽高比过关但边是透明的)一律当图标处理,补一块边缘色底——
        // 否则那种「透明边 logo」会被原样画成浮在壁纸上的一块、留一圈透明,不是连续的卡(2026-09-16 Gordon 真机指出)。
        // 有自定义图时不读(用不上)。
        val banner = if (custom != null) null else runCatching {
            drawableOf(ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.loadLogo(pm))
        }.getOrNull()
            ?.takeIf { fitsAsBanner(it.width, it.height, opaqueFraction(edgePixelsOf(it))) }
        val bmp = custom?.first ?: banner ?: runCatching { drawableOf(ri.loadIcon(pm)) }.getOrNull()
        val isWide = custom?.second ?: (banner != null)
        // 当图标画的卡(应用图标回落,或不像横幅的自定义图):铺 16:9 底(design §2.3)。底色用**图标最外一圈的均色**
        // (edgeColor),不是整图 Palette 主色——主色常挑到 logo 图形色,铺成底和图标边缘割裂、像硬包一圈
        // (2026-09-16 Gordon 真机指出);边缘色则与图标融为一块。
        // 图标本就透明边(edgeColor 返回 null)/ 取色抛异常时兜底到 Theme.IconPlaceholderBackground:
        // 不能留 null——那样这张卡会透回黑底,和「isWide=true 本就不该铺底」两种情况混在一起分不清。IO 线程(load 本就在 IO)。
        // R107(2026-09-27 Gordon 真机:爱奇艺 1050×630 自定义图左右露灰边):横幅也要底色。判据只要求宽高比在
        // 1.4–2.2,不要求正好 16:9,等比 Fit 进 16:9 卡片后会空出两条;用图片边缘均色补齐,看起来是一整张卡。
        // 标准 16:9 横幅没有空边,这层底色看不见。
        val fallbackColor = if (bmp != null) iconBackdrop(bmp) else null
        val firstInstall = runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrDefault(0L)
        return AppEntry(
            packageName = pkg,
            label = label,
            card = bmp,
            isWide = isWide,
            fallbackColor = fallbackColor,
            firstInstallTime = firstInstall,
        )
    }

    /** 当图标画时的卡片底色:边缘色,取不到兜底占位底。ARGB,满 alpha。 */
    private fun iconBackdrop(b: Bitmap): Int =
        runCatching { edgeColor(edgePixelsOf(b)) }.getOrNull() ?: Theme.IconPlaceholderBackground.toArgb()

    /**
     * 「换卡片图」网格的缩略图按卡面画(R88):这张图换上去之后当横幅铺满(null),还是当图标、铺这个底色(非 null)。
     * 与 [entryOf] 同一条判据([fitsAsBanner])、同一个取色。[srcW]/[srcH] 传原图尺寸(缩略图是采样过的)。
     */
    fun cardBackdropFor(b: Bitmap, srcW: Int = b.width, srcH: Int = b.height): Int? =
        if (fitsAsBanner(srcW, srcH, opaqueFraction(edgePixelsOf(b)))) null else iconBackdrop(b)

    /** @return 是否真的起来了;起不来时调用方要给提示,别让用户以为遥控器坏了。 */
    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            // 同上:没有 LAUNCHER 分类的应用要按包名解析
            ?: runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(pkg), 0) }
                .getOrDefault(emptyList())
                .firstOrNull()?.activityInfo?.let {
                    Intent(Intent.ACTION_MAIN).setClassName(it.packageName, it.name)
                }
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    /**
     * 「新应用」个数(R83 起口径与「添加应用」列表的「应用」分组对齐,见 [countNewApps]):系统工具、
     * 系统组件不算。先按 firstInstallTime 筛出「晚于 seenAt、不在桌面上、不是自己」的包——平常一个都没有,
     * 两次分类查询都省掉;有才对这几个包读分类事实。IO 线程调用。
     */
    fun countNew(ctx: Context, seenAt: Long, onLayout: Set<String>): Int {
        val pm = ctx.packageManager
        val fresh = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
            .filter { isNewApp(it.firstInstallTime, seenAt, it.packageName in onLayout) && it.packageName != ctx.packageName }
        if (fresh.isEmpty()) return 0
        val launcher = launcherEntries(pm)
        val facts = fresh.mapNotNull { pi ->
            val info = pi.applicationInfo ?: return@mapNotNull null
            runCatching { factsOf(pm, info, launcher[pi.packageName]).first to pi.firstInstallTime }.getOrNull()
        }
        return countNewApps(facts, seenAt, onLayout, ctx.packageName)
    }

    /** 卡片在屏幕上的像素尺寸(1920x1080 下 127x71dp @2x),位图不必比这更大。 */
    private const val CARD_W = 264
    private const val CARD_H = 148

    /**
     * 位图一律缩到卡片尺寸再留在内存里。
     * 不做这件事时整机 PSS 约 197MB(Projectivy 是 87MB),图形内存 104MB——
     * 应用横幅按原尺寸解码是主因。
     */
    /** 一张位图最外一圈的像素(上下两行 + 左右两列),喂给 [edgeColor] / [opaqueFraction]。空/过小 → 空数组。 */
    private fun edgePixelsOf(b: Bitmap): IntArray {
        val w = b.width; val h = b.height
        if (w < 2 || h < 2) return IntArray(0)
        val top = IntArray(w).also { b.getPixels(it, 0, w, 0, 0, w, 1) }
        val bottom = IntArray(w).also { b.getPixels(it, 0, w, 0, h - 1, w, 1) }
        val left = IntArray(h).also { b.getPixels(it, 0, 1, 0, 0, 1, h) }
        val right = IntArray(h).also { b.getPixels(it, 0, 1, w - 1, 0, 1, h) }
        return top + bottom + left + right
    }

    private fun drawableOf(d: Drawable?): Bitmap? {
        if (d == null) return null
        (d as? BitmapDrawable)?.bitmap?.let { return shrink(it, CARD_W, CARD_H) }
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        return runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it); d.setBounds(0, 0, w, h); d.draw(c)
            }
        }.getOrNull()?.let { shrink(it, CARD_W, CARD_H) }
    }

    private fun shrink(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (src.width <= maxW && src.height <= maxH) return src
        val ratio = max(src.width.toFloat() / maxW, src.height.toFloat() / maxH)
        val w = (src.width / ratio).toInt().coerceAtLeast(1)
        val h = (src.height / ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(src, w, h, true) }.getOrDefault(src)
    }

    /**
     * 「添加应用」列表每项左边的小卡片图(R83,取代上一轮的 24dp 小图标):与首页卡片同一套选图
     * ([entryOf]:自定义图 → leanback banner → 图标 + 边缘色底),只在这一项上屏时读。
     * 进程内 LRU,**按包名做 key、值里带版本戳**(`lastUpdateTime` + 自定义图的修改时间):应用更新 / 换了卡片图,
     * 戳对不上就重读并**原地替换**这个包的条目——同一个包在缓存里永远只有一份。
     * (上一轮小图标按 `包名@戳` 做 key,更新后新旧两份并存,主线程取缓存按 `startsWith` 找到的是 LRU 顺序里
     * 最旧的那份,即更新前的旧图——评审指出的问题,随这次改写一起消掉。)
     * 读不到(包正被替换、没有入口)返回 null,调用方画占位底。**IO 线程调用**。
     */
    fun pickerCard(ctx: Context, pkg: String): AppEntry? {
        val pm = ctx.packageManager
        val updated = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrNull() ?: return null
        val stamp = "$updated/${runCatching { Paths.iconFor(ctx, pkg).lastModified() }.getOrDefault(0L)}"
        pickerCards.get(pkg)?.takeIf { it.first == stamp }?.let { return it.second }
        val ri = launcherEntryOf(pm, pkg) ?: exportedMain(pm, pkg) ?: return null
        val entry = runCatching { entryOf(ctx, pm, pkg, ri, withBitmaps = null, withLabels = emptySet()) }.getOrNull()
            ?: return null
        pickerCards.put(pkg, stamp to entry)
        return entry
    }

    /** 缓存里这个包当前那一份卡片图(主线程可调,不碰 PackageManager):列表滚回来时首帧就有图,不闪占位。 */
    fun cachedPickerCard(pkg: String): AppEntry? = pickerCards.get(pkg)?.second

    private fun launcherEntryOf(pm: PackageManager, pkg: String): ResolveInfo? {
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(pkg), 0) }
                .getOrNull()?.firstOrNull()?.let { return it }
        }
        return null
    }

    /**
     * 上界按字节算:一张卡片图最大 [CARD_W]×[CARD_H]×4 ≈ 153 KB(横幅),图标回落的方图 ≈ 86 KB;
     * 8 MB 装得下 50 张以上横幅——A95L 的候选约 38 个,整张列表翻一遍都不会被挤出去。
     */
    private val pickerCards = object : android.util.LruCache<String, Pair<String, AppEntry>>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Pair<String, AppEntry>): Int =
            (value.second.card?.allocationByteCount ?: 0).coerceAtLeast(1)
    }

    /**
     * 「换卡片图」网格里「恢复原图」那一格的图:与 [entryOf] 同一套选图(去掉自定义图)——R88 前这里另读
     * 应用级 banner、不过横幅判据,预览和恢复后的卡面可能不是同一张。
     */
    fun originalIcon(ctx: Context, pkg: String): Bitmap? = runCatching {
        val pm = ctx.packageManager
        val ri = launcherEntryOf(pm, pkg) ?: exportedMain(pm, pkg) ?: return@runCatching null
        entryOf(ctx, pm, pkg, ri, withBitmaps = null, withLabels = emptySet(), useCustom = false).card
    }.getOrNull()

    /** 包是否已安装(有 QUERY_ALL_PACKAGES,看得见所有包)。IO 线程调用。 */
    fun isInstalled(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** 只读尺寸判断是不是能解的图片,不真的解码——用于校验用户选的文件。 */
    fun isDecodableImage(path: String): Boolean = imageSize(path) != null

    /** 只读文件头拿原图宽高;读不出 → null。[path] 可以是内置图的伪路径(R115,经 [decodeImagePath] 读 assets)。 */
    fun imageSize(path: String): Pair<Int, Int>? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeImagePath(path, b)
        return if (b.outWidth > 0 && b.outHeight > 0) b.outWidth to b.outHeight else null
    }

    /**
     * 先读尺寸再按 inSampleSize 解码,避免把大图整张读进内存。
     * [path] 可以是内置图的伪路径(R115):读字节一律经 [decodeImagePath],内置图从 assets 读。
     */
    fun decodeScaled(
        path: String,
        maxW: Int,
        maxH: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeImagePath(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
        }
        val bmp = decodeImagePath(path, opts)
        if (bmp == null && config == Bitmap.Config.RGBA_F16) {
            val fallback = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return decodeImagePath(path, fallback)
        }
        return bmp
    }
}
