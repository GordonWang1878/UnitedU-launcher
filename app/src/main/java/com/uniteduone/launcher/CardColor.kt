package com.uniteduone.launcher

/**
 * 「主题化卡片」的单色染色矩阵:去色(Rec.709 亮度)→ 染成主题 accent。
 * 与 2026-09-16 删掉的「主题化壁纸」管线是同一手法(那条现在只对壁纸没意义了,搬到卡片上),
 * 但卡片图小、直接用 Compose 的 `ColorFilter.colorMatrix` 在 GPU 上现染,不走离线缓存。
 * 白 (1,1,1) 去色后亮度 1,再乘 accent 分量 → 恰好等于 accent;黑 → 黑。纯函数,JVM 单测在 [CardColorTest]。
 * 返回 4×5 行主序(android.graphics.ColorMatrix / Compose ColorMatrix 同布局)。
 */
fun cardTintMatrix(accentRgb: Int): FloatArray {
    val ar = ((accentRgb shr 16) and 0xFF) / 255f
    val ag = ((accentRgb shr 8) and 0xFF) / 255f
    val ab = (accentRgb and 0xFF) / 255f
    val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
    return floatArrayOf(
        ar * lr, ar * lg, ar * lb, 0f, 0f,
        ag * lr, ag * lg, ag * lb, 0f, 0f,
        ab * lr, ab * lg, ab * lb, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/**
 * 「图标外圈色」:纯图标卡回落底该用的颜色。取图标最外一圈像素的均色,而不是整图的 Palette 主色——
 * 主色常挑到 logo 图形色(红底白字的图标会挑到白/红里更扎眼的那个),铺成底反而和图标边缘割裂、
 * 像硬包了一圈(2026-09-16 Gordon 真机指出)。边缘色则与图标那块底融成一块。
 * 透明边缘像素(alpha < 128)不计;有效像素不足(浮在透明上的老式图标)→ null,调用方回落到占位底。
 * 纯函数,JVM 单测在 [CardColorTest]。入参是 ARGB 像素(如 Bitmap.getPixels 的输出)。
 */
fun edgeColor(edgePixels: IntArray): Int? {
    var n = 0; var rs = 0L; var gs = 0L; var bs = 0L
    for (p in edgePixels) {
        if ((p ushr 24 and 0xFF) < 128) continue
        rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF; n++
    }
    if (n < edgePixels.size / 4) return null   // 有效边缘太少 = 图标本就透明边,别硬造底色
    // **必须带满 alpha**:回落底存进 AppEntry.fallbackColor(Int),首页用 `Color(it)` 按 ARGB 解——
    // 少了 0xFF alpha 就是全透明,铺底会透出壁纸(2026-09-16 网易云在亮壁纸上暴露过)。
    return (0xFF shl 24) or (((rs / n).toInt()) shl 16) or (((gs / n).toInt()) shl 8) or (bs / n).toInt()
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
