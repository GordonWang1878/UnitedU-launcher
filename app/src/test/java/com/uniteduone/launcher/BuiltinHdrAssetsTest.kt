package com.uniteduone.launcher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置壁纸 / 屏保必须先经 `scripts/hdr-assets.py` 处理(2026-09-28 Gordon:「你不能指望我记」)。
 * 处理过的判据与脚本的 `already_converted` 同一口径:.jpg、≤ 2.5 MB、且 HDR 说明书两份都在
 * (Android 14 的 XMP `hdrgm` + Android 15+ 的 ISO 21496-1),或是脚本按 SDR 压过的(没有增益图)。
 * 没处理过的图——4K 原图体积大、只有一种写法、或是 png/webp——在这里让构建失败,报错信息给出要跑的命令。
 * 规范:docs/design/hdr-image-spec.md。
 */
class BuiltinHdrAssetsTest {
    private val root = listOf(File("src/main/assets/builtin"), File("app/src/main/assets/builtin")).first { it.isDirectory }

    private fun problems(dir: String): List<String> {
        val d = File(root, dir)
        val files = d.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }.orEmpty()
        return files.mapNotNull { f ->
            val bytes = f.readBytes()
            fun has(s: String) = String(bytes, Charsets.ISO_8859_1).contains(s)
            val xmp = has("hdrgm")
            val iso = has("iso:ts:21496")
            when {
                f.extension.lowercase() != "jpg" -> "${f.name}:不是 .jpg"
                f.length() > 2_500_000 -> "${f.name}:${f.length() / 1_000_000.0} MB,像是没压缩的原图"
                xmp != iso -> "${f.name}:只有一种 HDR 写法(XMP=$xmp ISO=$iso)"
                !xmp && has("MPF") -> "${f.name}:带增益图但两种 HDR 写法都没有"
                else -> null
            }
        }
    }

    @Test fun builtinWallpapersAndScreensaversAreConverted() {
        val bad = listOf("wallpapers", "screensavers").flatMap { dir -> problems(dir).map { "$dir/$it" } }
        assertTrue(
            "内置图还没经 scripts/hdr-assets.py 处理:\n  " + bad.joinToString("\n  ") +
                "\n请运行:\n  scripts/hdr-assets.py --in-place app/src/main/assets/builtin/wallpapers" +
                "\n  scripts/hdr-assets.py --in-place app/src/main/assets/builtin/screensavers",
            bad.isEmpty(),
        )
    }
}
