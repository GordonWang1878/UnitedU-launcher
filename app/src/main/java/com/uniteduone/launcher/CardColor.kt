package com.uniteduone.launcher

/**
 * 「图标外圈色」:纯图标卡回落底该用的颜色。取图标最外一圈像素里**占比最高的那个颜色**,而不是
 * 整图的 Palette 主色——主色常挑到 logo 图形色(红底白字的图标会挑到白/红里更扎眼的那个),铺成底
 * 反而和图标边缘割裂、像硬包了一圈(2026-09-16 Gordon 真机指出)。边缘色则与图标那块底融成一块。
 *
 * **不能对边缘像素直接取算术均色**(owner 反馈 Round 5,2026-09-21,以「咪视界」真机截图为例):
 * 图标边缘若是「上方大半圈白、下方一小圈蓝紫」这种两色环,均色会落在两者中间,算出一个原图里
 * 根本不存在的灰蓝色,填出来的底和图标边缘明显割裂,比割裂本身还突兀。**规则改成:边缘出现多种
 * 颜色时,取占比更高的那一个,不再和稀泥。**
 *
 * 做法:把不透明边缘像素按 RGB 每通道右移 5 位(÷32,量到 8 档、9 bit 组合键)分桶——桶要够粗,
 * 才能把同一种颜色的抗锯齿羽化 / 轻微渐变都并进同一桶;又要够细,像白与蓝紫这种视觉上明显不同的
 * 颜色不会被并到一个桶里。取像素数最多的那一桶,返回**桶内像素的均值**而不是桶的几何中心——
 * 纯色或近纯色边缘因此依然精确复原原色(桶内只有一种值,均值 = 该值本身);只有边缘真的混了
 * 多种颜色时,均值才在「哪种颜色占比最高」之间做出取舍,而不是把所有颜色都拉平。
 *
 * **桶界撕票(整枝审查存疑 3,2026-09-22,单测复现后修)**:固定网格分桶有一个固有病——同一种颜色
 * 恰好跨在桶界两侧(0xDF → 桶 6、0xE0 → 桶 7),会被撕成两张各 30% 的票,让 40% 的另一种颜色当选。
 * 修法是**用邻桶合并选赢家、用核心桶算颜色**:每个桶的得分 = 自己 + 26 个相邻桶(RGB 各 ±1)的
 * 像素数,得分最高的那一簇是赢家;返回值仍是这一簇里**像素数最多的那一个桶**的均值,而不是整簇
 * 的均值——合并只用来投票,不用来调色,否则 55% 白 + 45% 浅灰会再次「算出原图里不存在的颜色」。
 * 单桶 32 级宽、邻桶合并的窗口最多 96 级,白与蓝紫这类真正不同的颜色不会被并进同一簇。
 *
 * 透明边缘像素(alpha < 128)不计,既不进有效像素计数、也不进任何桶;有效像素不足(浮在透明上的
 * 老式图标)→ null,调用方回落到占位底。纯函数,JVM 单测在 [CardColorTest]。
 * 入参是 ARGB 像素(如 Bitmap.getPixels 的输出)。
 */
fun edgeColor(edgePixels: IntArray): Int? {
    var n = 0
    // key = 分桶后的 (r,g,b)(每通道 3 bit,共 9 bit,足够放下 8×8×8 种组合不撞车);
    // value = [该桶像素数, r 累加, g 累加, b 累加],最后除以像素数得到桶内均值。
    val buckets = HashMap<Int, LongArray>()
    for (p in edgePixels) {
        if ((p ushr 24 and 0xFF) < 128) continue
        n++
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val key = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
        val acc = buckets.getOrPut(key) { LongArray(4) }
        acc[0] += 1; acc[1] += r; acc[2] += g; acc[3] += b
    }
    if (n < edgePixels.size / 4) return null   // 有效边缘太少 = 图标本就透明边,别硬造底色
    if (buckets.isEmpty()) return null
    // 邻桶合并选赢家:得分 = 本桶 + RGB 各 ±1 的 26 个邻桶的像素数(见 KDoc「桶界撕票」)。
    fun neighbourhood(key: Int): List<LongArray> {
        val r = (key shr 6) and 7; val g = (key shr 3) and 7; val b = key and 7
        val out = ArrayList<LongArray>(27)
        for (dr in -1..1) for (dg in -1..1) for (db in -1..1) {
            val nr = r + dr; val ng = g + dg; val nb = b + db
            if (nr !in 0..7 || ng !in 0..7 || nb !in 0..7) continue
            buckets[(nr shl 6) or (ng shl 3) or nb]?.let { out.add(it) }
        }
        return out
    }
    val winnerKey = buckets.keys.maxWithOrNull(
        compareBy<Int>({ key -> neighbourhood(key).sumOf { it[0] } }, { key -> buckets.getValue(key)[0] }),
    ) ?: return null
    // 颜色只取赢家簇里像素最多的那一个核心桶的均值,不把整簇拉平。
    val dominant = neighbourhood(winnerKey).maxByOrNull { it[0] } ?: return null
    val count = dominant[0]
    // **必须带满 alpha**:回落底存进 AppEntry.fallbackColor(Int),首页用 `Color(it)` 按 ARGB 解——
    // 少了 0xFF alpha 就是全透明,铺底会透出壁纸(2026-09-16 网易云在亮壁纸上暴露过)。
    return (0xFF shl 24) or
        ((dominant[1] / count).toInt() shl 16) or
        ((dominant[2] / count).toInt() shl 8) or
        (dominant[3] / count).toInt()
}

/**
 * 边缘不透明占比:判断一张候选横幅是不是**真的铺满**(TV 横幅是不透明的 16:9 图,四边基本不透明),
 * 还是一张四周透明的 logo(如网易云的 loadLogo)。后者当横幅会浮在壁纸上、留一圈透明,
 * 应改当图标处理、补一块边缘色底。入参是候选图的四边像素(ARGB)。纯函数,单测在 [CardColorTest]。
 */
fun opaqueFraction(edgePixels: IntArray): Float {
    if (edgePixels.isEmpty()) return 0f
    var opaque = 0
    for (p in edgePixels) if ((p ushr 24 and 0xFF) >= 128) opaque++
    return opaque.toFloat() / edgePixels.size
}

/**
 * **Ruling R88**:一张卡片图能不能「当横幅铺满 16:9 卡」的唯一判据——应用自带横幅与用户换的自定义图**共用**。
 * 宽高比在 [BANNER_MIN_RATIO]..[BANNER_MAX_RATIO] **且**四边不透明占比([opaqueFraction])≥ [BANNER_MIN_OPAQUE]
 * → 横幅;否则当图标处理:居中、按卡高的方框缩放,底色 = 边缘色([edgeColor]),取不到兜底占位底(design §2.3)。
 * [width]/[height] 要用**原图**的尺寸(解码只按比例缩、不裁,缩后比例与原图一致;不要先裁成 16:9 再判)。
 * 纯函数,单测在 [CardColorTest] 的 FitsAsBannerTest。
 */
fun fitsAsBanner(width: Int, height: Int, edgeOpaqueFraction: Float): Boolean {
    if (width <= 0 || height <= 0) return false
    val ratio = width.toFloat() / height
    return ratio in BANNER_MIN_RATIO..BANNER_MAX_RATIO && edgeOpaqueFraction >= BANNER_MIN_OPAQUE
}

const val BANNER_MIN_RATIO = 1.4f
const val BANNER_MAX_RATIO = 2.2f
const val BANNER_MIN_OPAQUE = 0.8f
