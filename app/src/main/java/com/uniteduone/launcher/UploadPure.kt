package com.uniteduone.launcher

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 上传页里**不碰 Android 类**的部分:类型表、文件名清洗、重名、选址、JSON 拼装、二维码矩阵。
 * 单独一个文件,好在纯 JVM 单测里直接断言;Android 侧在 UploadServer / ImportScreen / ApkInstaller。
 */

val LIBRARY_TYPES = listOf("wallpapers", "cards", "screensavers")
val UPLOAD_IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")
const val MAX_UPLOAD_BYTES = 30L * 1024 * 1024
const val MAX_APK_BYTES = 100L * 1024 * 1024
const val UPLOAD_PORT_FIRST = 8090
const val UPLOAD_PORT_LAST = 8099

fun isValidType(type: String?): Boolean = type != null && type in LIBRARY_TYPES

/**
 * 上传文件名清洗:只取最后一个 / 或 \ 之后;去控制字符;trim;空、"."、".."、以 "." 开头 → null
 * (点开头会撞上 library 里的 .seeded 标记);保留中文等 Unicode;超过 100 字符截主名、保扩展名。
 */
fun sanitizeUploadName(raw: String?): String? {
    if (raw == null) return null
    var n = raw.substringAfterLast('/').substringAfterLast('\\')
    n = n.filter { it.code >= 0x20 && it.code != 0x7F }.trim()
    if (n.isEmpty() || n == "." || n == ".." || n.startsWith('.')) return null
    if (n.length > 100) {
        val dot = n.lastIndexOf('.')
        val ext = if (dot > 0) n.substring(dot) else ""
        val stem = if (dot > 0) n.substring(0, dot) else n
        n = stem.take((100 - ext.length).coerceAtLeast(1)) + ext
    }
    return n
}

fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/** a.jpg 已存在 → a-1.jpg → a-2.jpg … */
fun uniqueName(existing: Set<String>, name: String): String {
    if (name !in existing) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while ("$stem-$i$ext" in existing) i++
    return "$stem-$i$ext"
}

/** (接口名, IPv4) 候选里挑一个给用户看:wlan/eth/en 开头的优先,其次任意;空 → null。 */
fun pickAddress(candidates: List<Pair<String, String>>): String? {
    val preferred = candidates.firstOrNull { (iface, _) ->
        iface.startsWith("wlan") || iface.startsWith("eth") || iface.startsWith("en")
    }
    return (preferred ?: candidates.firstOrNull())?.second
}

// ---- JSON 拼装(与 Settings 同理:不用 org.json,纯 Kotlin,可单测) ----

fun jsonStr(s: String): String = buildString {
    append('"')
    for (c in s) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        // 转义 < 防止网页文案里若出现 </script 提前截断 index.html 内联的 <script> 块(合法 JSON,无害)。
        c == '<' -> append("\\u003c")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c.code < 0x20 -> append("\\u%04x".format(c.code))
        else -> append(c)
    }
    append('"')
}

/** 一个文件:名、字节数、mtime(epoch ms)。 */
data class FileEntry(val name: String, val size: Long, val mtime: Long)

fun jsonFileList(type: String, files: List<FileEntry>): String =
    files.joinToString(",", prefix = "{\"type\":${jsonStr(type)},\"files\":[", postfix = "]}") {
        "{\"name\":${jsonStr(it.name)},\"size\":${it.size},\"mtime\":${it.mtime}}"
    }

fun jsonUploadResult(saved: List<String>, rejected: List<Pair<String, String>>): String {
    val s = saved.joinToString(",") { jsonStr(it) }
    val r = rejected.joinToString(",") { (n, why) -> "{\"name\":${jsonStr(n)},\"reason\":${jsonStr(why)}}" }
    return "{\"saved\":[$s],\"rejected\":[$r]}"
}

fun jsonOk(extra: String = ""): String = if (extra.isEmpty()) "{\"ok\":true}" else "{\"ok\":true,$extra}"
fun jsonFail(reason: String): String = "{\"ok\":false,\"reason\":${jsonStr(reason)}}"

/** 二维码矩阵(纯 ZXing,不碰 Android):单测里用 ZXing 自己的解码器反解验证。 */
fun qrMatrix(text: String, size: Int = 360): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
