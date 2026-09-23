package com.uniteduone.launcher

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

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
 * 手机网页默认打开哪个分页(Ruling R63):注入 index.html 的 `__DEFAULT_TAB__` 占位,是一段 JS 字面量。
 * 从某个图片网格的「＋ 从手机添加」进来时是那个网格的分类;总入口「手机传输」不带分类 → `null`,
 * 网页按原来的默认(壁纸)打开。只认 [LIBRARY_TYPES],别的值一律当没带。
 */
fun defaultTabJs(tab: String?): String = if (isValidType(tab)) jsonStr(tab!!) else "null"

/**
 * multipart 请求头缺 charset 时返回补上 `; charset=UTF-8` 的新值,否则 null(不用改)。
 * NanoHTTPD 2.3.1 用这个 charset 解 part 头(文件名在里面),缺省是 US-ASCII——中文名会全变 U+FFFD。
 */
fun utf8MultipartContentType(contentType: String?): String? {
    if (contentType == null) return null
    if (!contentType.contains("multipart/form-data", ignoreCase = true)) return null
    if (contentType.contains("charset=", ignoreCase = true)) return null
    return "$contentType; charset=UTF-8"
}

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

/**
 * multipart 同批上传要处理哪些字段 key,按处理顺序排好(纯函数,好单测;Android 侧由
 * `UploadServer.serveUpload` 传入 `files`(key → 临时文件路径)与 `session.parameters` 的 key 集)。
 *
 * 规律见 `serveUpload` 的注释:NanoHTTPD 把同名多个文件摊成 `files`、`files1`、`files2`…
 * 这里**按实际存在的 key 取并集**(两张表各自可能缺项)、**按数字后缀排序**,而不是按固定步长
 * 硬猜序号——某个 part 没带逐段 Content-Type 时编号会跳号,硬猜撞上空位就会把后面全部截断。
 * 后缀不是合法 Int 的(手工构造的 `files99999999999999` 溢出、或干脆不是数字的 `filesX`)
 * 排到最后而不是抛异常:这种边角输入不该把整批正常文件拖垮。同名次序再按 key 字面排,保证稳定。
 */
fun uploadKeys(fileKeys: Set<String>, paramKeys: Set<String>): List<String> =
    (fileKeys + paramKeys)
        .filter { it.startsWith("files") }
        .sortedWith(
            compareBy(
                { if (it == "files") Int.MIN_VALUE else it.substring(5).toIntOrNull() ?: Int.MAX_VALUE },
                { it },
            ),
        )

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

/** 图库落盘的进程内锁:「挑不重名的名字 → 移入」必须一口气做完,见 [saveIntoLibrary]。 */
private val libraryLock = Any()

/**
 * 把一个已校验过的上传临时文件移进图库目录 [dir],落盘名按 [uniqueName] 避开已有文件;@return 落盘名,失败 null。
 *
 * **挑名字与移入在同一把锁里**:NanoHTTPD 每个请求一个线程,两批同名上传(手机上连选两次、两台手机)各自
 * `dir.list()` 看到同一个空位,都挑中 `a.jpg`,后 rename 的那个**静默覆盖**先到的那张(POSIX rename 覆盖已有文件)。
 * 移入优先同卷 [rename](原子);跨卷(外置 cache 没挂时)回落到 [writeFileAtomically]:复制进同目录的独立临时文件
 * (扩展名 `.tmp`,图库扫描看不见)→ fsync → rename,网格重扫 / 屏保播放器 / 缩略图**读不到半截文件**。
 * 旧写法直接往正式文件名里流式复制,复制途中被扫描到就是半张图,复制失败还会把半截文件留在图库里。
 * [rename] 只为单测注入(模拟跨卷 rename 失败)。
 */
fun saveIntoLibrary(src: File, dir: File, name: String, rename: (File, File) -> Boolean = { a, b -> a.renameTo(b) }): String? =
    synchronized(libraryLock) {
        val finalName = uniqueName(dir.list()?.toSet() ?: emptySet(), name)
        val dst = File(dir, finalName)
        if (rename(src, dst)) return@synchronized finalName
        val ok = runCatching {
            writeFileAtomically(dst) { out -> src.inputStream().use { it.copyTo(out) } }
        }.getOrDefault(false)
        if (ok) { src.delete(); finalName } else null
    }

/** 图库里遗留的落盘临时文件(进程在复制途中被杀):`writeFileAtomically` 的 `.<名>.<随机>.tmp`。 */
fun isStaleLibraryTemp(name: String): Boolean = name.startsWith(".") && name.endsWith(".tmp")

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
