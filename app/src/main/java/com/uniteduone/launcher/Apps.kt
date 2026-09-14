package com.uniteduone.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
     *   同一个道理:首页只画 11 张卡,却给全部约 50 个应用读了标签。选择器则相反,
     *   它只要名字、一个位图都不要。
     */
    fun load(
        ctx: Context,
        extraPackages: Collection<String> = emptyList(),
        withBitmaps: Set<String>? = null,
        withLabels: Set<String>? = null,
        includeAllInstalled: Boolean = false,
    ): Map<String, AppEntry> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            // queryIntentActivities 会在结果撑破 Binder 事务上限时抛 TransactionTooLargeException。
            // 一个分类查失败不该让整张桌面变空,另一个分类的结果照样能用。
            for (ri in runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())) {
                found.putIfAbsent(ri.activityInfo.packageName, ri)
            }
        }
        // 兜底:不带 category 按包名查一次
        for (pkg in extraPackages) {
            if (found.containsKey(pkg)) continue
            val intent = Intent(Intent.ACTION_MAIN).setPackage(pkg)
            runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
                .firstOrNull()?.let { found[pkg] = it }
        }
        // 选择器要用:按分类枚举查不到「只有 MAIN + DEFAULT」的应用(当贝音乐就是这种),
        // 而 extraPackages 那条兜底只能捞出**调用方已经知道**的包 —— 一个应用被移出桌面之后
        // 就不在任何已知集合里,于是**再也加不回来**,只能 adb 改 layout.json。
        // 所以这里按已安装包全量补一遍,只在选择器那条路上开(首页不需要,也不该付这个代价)。
        if (includeAllInstalled) {
            val installed = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            for (info in installed) {
                val pkg = info.packageName
                if (pkg == ctx.packageName || found.containsKey(pkg)) continue
                // 只收 **exported** 的活动:不带 category 的查询会匹配「任何声明了 action MAIN
                // 的活动」,输入法/动态壁纸/系统组件的设置页(裸 MAIN 无 category)都会被捞进来;
                // 而 queryIntentActivities 不按 exported 过滤,不可导出的要到 startActivity
                // 才报 SecurityException —— 列出来却打不开,提示词还会说「可能已被卸载」。
                runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(pkg), 0) }
                    .getOrDefault(emptyList())
                    .firstOrNull { it.activityInfo?.exported == true }
                    ?.let { found[pkg] = it }
            }
        }
        return found.mapValues { (pkg, ri) ->
            // **逐包兜底,不是整批**。原来整个 mapValues 外面才有一层 runCatching:
            // 任何一个包在被替换的中间态下 loadBanner/loadIcon 抛异常,整张桌面就变成空的,
            // 而重建恰恰由 PACKAGE_CHANGED 触发 —— 正是最容易抛的那一刻。
            runCatching { entryOf(ctx, pm, pkg, ri, withBitmaps, withLabels) }
                .getOrElse { AppEntry(pkg, "", null, isWide = false) }
        }
    }

    private fun entryOf(
        ctx: Context,
        pm: PackageManager,
        pkg: String,
        ri: ResolveInfo,
        withBitmaps: Set<String>?,
        withLabels: Set<String>?,
    ): AppEntry {
        val label = if (withLabels == null || pkg in withLabels) {
            runCatching { ri.loadLabel(pm)?.toString() }.getOrNull().orEmpty()
        } else ""
        if (withBitmaps != null && pkg !in withBitmaps) {
            return AppEntry(pkg, label, card = null, isWide = false)
        }
        val custom = Paths.iconFor(ctx, pkg).takeIf { it.exists() }
            ?.let { runCatching { decodeScaled(it.absolutePath, CARD_W, CARD_H) }.getOrNull() }
            ?.let { shrink(it, CARD_W, CARD_H) }
        // 横幅只有在接近 16:9 时才用来铺满;比例不对的(如咪咕)当图标处理,
        // 否则 Crop 会把它裁出白边。
        val banner = runCatching {
            drawableOf(ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.loadLogo(pm))
        }.getOrNull()?.takeIf { it.width.toFloat() / it.height.coerceAtLeast(1) in 1.4f..2.2f }
        val bmp = custom ?: banner ?: runCatching { drawableOf(ri.loadIcon(pm)) }.getOrNull()
        return AppEntry(
            packageName = pkg,
            label = label,
            card = bmp,
            isWide = custom != null || banner != null,
        )
    }

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

    /** 卡片在屏幕上的像素尺寸(1920x1080 下 127x71dp @2x),位图不必比这更大。 */
    private const val CARD_W = 264
    private const val CARD_H = 148

    /**
     * 位图一律缩到卡片尺寸再留在内存里。
     * 不做这件事时整机 PSS 约 197MB(Projectivy 是 87MB),图形内存 104MB——
     * 应用横幅按原尺寸解码是主因。
     */
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

    fun originalIcon(ctx: Context, pkg: String): Bitmap? = runCatching {
        val pm = ctx.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        val banner = info.loadBanner(pm)?.let { drawableOf(it) }
        banner ?: drawableOf(pm.getApplicationIcon(info))
    }.getOrNull()

    /** 只读尺寸判断是不是能解的图片,不真的解码——用于校验用户选的文件。 */
    fun isDecodableImage(path: String): Boolean {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        return b.outWidth > 0 && b.outHeight > 0
    }

    /** 先读尺寸再按 inSampleSize 解码,避免把大图整张读进内存。 */
    fun decodeScaled(
        path: String,
        maxW: Int,
        maxH: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
        }
        val bmp = BitmapFactory.decodeFile(path, opts)
        if (bmp == null && config == Bitmap.Config.RGBA_F16) {
            val fallback = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(path, fallback)
        }
        return bmp
    }
}
