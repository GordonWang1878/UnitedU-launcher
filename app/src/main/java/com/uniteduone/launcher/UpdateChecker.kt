package com.uniteduone.launcher

import java.io.File
import java.security.MessageDigest

// 检查更新的纯逻辑(spec §7.2 / §7.3)。**本文件不 import 任何 android.* 类**:
// JVM 单测(UpdateCheckerTest)直接跑这里的每一个函数;网络与文件 IO 在 Update.kt,
// 它只负责「把字节取回来」,「取回来的东西算不算数、先问谁、失败算哪一种」全部在这里决定。

/**
 * `latest.json` 解析后的结果(spec §7.2)。只有 [parseLatest] 造得出合法实例:
 * [sha256] 一定是 64 位小写 hex,[apkUrl] 一定过了 [isAllowedUpdateUrl]。
 */
data class LatestInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sha256: String,
    val minSdk: Int,
)

/**
 * 手动检查失败的两种原因,各对应一条提示(spec §7.3「手动检查不静默」)。
 * - [NETWORK]:没有任何通道给出正文(超时、DNS、非 200、地址不合规、正文过大……);
 * - [BAD_JSON]:至少有一个通道**给了正文**,但解析不出合法的 [LatestInfo]。
 */
enum class CheckFailure { NETWORK, BAD_JSON }

/** [resolveLatest] 失败时 `Result` 里装的异常;界面只看 [reason]。 */
class UpdateCheckException(val reason: CheckFailure) : Exception(reason.name)

/**
 * 更新通道允许访问的地址——**检查地址、`apkUrl`、重定向后的最终地址共用这一条规则**,
 * 三处若各写一份,迟早有一处放过 `http://`。
 *
 * 规则(Controller ruling R4):
 * - `https://` + 非空主机,主机段里不许出现 `@`(userinfo)、反斜杠、空白;
 * - 或者 **恰好** `http://127.0.0.1`,后面只能跟 `:端口` 和/或路径——模拟器验证时经
 *   `adb reverse` 走本机回环,流量不出设备,所以不受「仅 https」约束。
 *
 * 回环这一支必须**整段匹配**而不是 `startsWith("http://127.0.0.1")`:前缀判定会放过
 * `http://127.0.0.1.evil.com/`(主机其实是 evil.com 的子域)和
 * `http://127.0.0.1:80@evil.com/`(`127.0.0.1:80` 只是 userinfo,真正的主机是 evil.com)。
 * 端口只认 ASCII 数字(`[0-9]` 而不是 `\d`:Android 的 ICU 正则里 `\d` 会匹配全角/阿拉伯数字)。
 * 大写的 `HTTPS://` 一律拒绝:地址只来自我们自己的构建配置和发布脚本,不需要宽容。
 */
fun isAllowedUpdateUrl(url: String): Boolean = HTTPS_URL.matches(url) || LOOPBACK_URL.matches(url)

private val HTTPS_URL = Regex("https://[^/?#@\\\\\\s]+(?:[/?#]\\S*)?")
private val LOOPBACK_URL = Regex("http://127\\.0\\.0\\.1(?::[0-9]{1,5})?(?:[/?#]\\S*)?")

/**
 * `BuildConfig.UPDATE_URLS`(Gradle 属性 `unitedu.updateUrls`,逗号分隔)→ 有序地址表。
 * 顺序就是尝试顺序(spec §7.3:COS 在前、GitHub 在后)。这里只拆分,不过滤——
 * 不合规的地址留给 [resolveLatest] 跳过并记日志,配置写错时 logcat 里看得见是哪一条。
 */
fun parseUpdateUrls(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

private val HEX64 = Regex("[0-9a-fA-F]{64}")

/** notes 的显示上限(spec §7.2「≤ 200 字」);关于页不能滚动,超长的截掉。 */
private const val NOTES_MAX = 200

/**
 * 解析 `latest.json`。**不用 `org.json`**:Android 单测桩 jar 里它的每个方法都抛
 * "not mocked"。沿用 [parseSettings] 的扁平 tokenizer 思路——文件是一层没有嵌套的
 * key-value,先用 [isWellFormedJsonObject](Settings.kt,`internal`)确认「是一个完整的对象」,
 * 再按键名逐个取值。
 *
 * 必填:`versionCode`(正整数)、`versionName`(非空白)、`apkUrl`(过 [isAllowedUpdateUrl])、
 * `sha256`(64 位 hex,统一转小写,与 [sha256Hex] 的输出直接比较)。
 * 可选:`notes`(缺省空串,截到 200 字)、`minSdk`(缺省 1 = 不设限;**写了但不是整数**则整份拒绝——
 * 那说明发布脚本出错了,宁可提示「无法解析」也不要猜)。
 * 任何输入都不抛异常:不合格一律返回 null,由调用方报「更新信息无法解析」。
 */
fun parseLatest(json: String): LatestInfo? = try {
    if (!isWellFormedJsonObject(json)) {
        null
    } else {
        val code = jsonRaw(json, "versionCode")?.toIntOrNull()?.takeIf { it > 0 }
        val name = jsonString(json, "versionName")?.trim()?.takeIf { it.isNotEmpty() }
        val url = jsonString(json, "apkUrl")?.takeIf { isAllowedUpdateUrl(it) }
        val sha = jsonString(json, "sha256")?.takeIf { HEX64.matches(it) }?.lowercase()
        val minSdkRaw = jsonRaw(json, "minSdk")
        val minSdk = if (minSdkRaw == null) 1 else minSdkRaw.toIntOrNull()
        val notes = capNotes(jsonString(json, "notes") ?: "")
        if (code == null || name == null || url == null || sha == null || minSdk == null) null
        else LatestInfo(code, name, notes, url, sha, minSdk.coerceAtLeast(1))
    }
} catch (e: Throwable) {
    // 与 parseSettings 同一姿势:上面每一步都已兜底,这层只防「某行实现细节意外抛出」。
    null
}

/** 截到 [NOTES_MAX] 个 UTF-16 单元;截断点若劈开了代理对(emoji),把落单的高位代理一并丢掉。 */
private fun capNotes(s: String): String {
    if (s.length <= NOTES_MAX) return s
    val cut = s.take(NOTES_MAX)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/**
 * 裸值(数字 / true / false)一路取到下一个逗号或右花括号,与 Settings.kt 的 extractRaw 同构。
 * 键名前后都要求是真正的引号:值里出现的 `\"versionCode\"` 结尾是反斜杠,匹配不上。
 */
private fun jsonRaw(json: String, key: String): String? =
    Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*([^,}]+)").find(json)?.groupValues?.get(1)?.trim()

/**
 * 字符串值。正则只负责定位 `"key": "` 的起点,正文**手工扫描**到第一个未转义的引号:
 * `(?:[^"\\]|\\.)*` 这种交替分组在 JVM 的正则实现里按字符递归,一段几千字的 notes 就能
 * 把栈打爆;手工扫描是线性的。取出的原文交给 [unescapeJson] 还原转义。
 */
private fun jsonString(json: String, key: String): String? {
    val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"").find(json) ?: return null
    val start = m.range.last + 1
    var i = start
    while (i < json.length) {
        when (json[i]) {
            '\\' -> i += 2
            '"' -> return unescapeJson(json.substring(start, i))
            else -> i++
        }
    }
    return null
}

/**
 * JSON 字符串转义还原:`\" \\ \/ \b \f \n \r \t \uXXXX`。发布脚本若用 Python 的 `json.dumps`
 * (默认 `ensure_ascii`)生成文件,中文 notes 全是 `\uXXXX`——只认 `\"` 和 `\\` 的简化版
 * (Settings.kt 够用,那里没有自由文本)在这里会原样显示一串转义码。
 * 非法转义返回 null(整个字段按缺失处理)。未转义的控制字符(裸换行)宽容接受。
 */
private fun unescapeJson(s: String): String? {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\') {
            sb.append(c)
            i++
            continue
        }
        if (i + 1 >= s.length) return null
        when (s[i + 1]) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'b' -> sb.append('\b')
            'f' -> sb.append('')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> {
                if (i + 6 > s.length) return null
                val hex = s.substring(i + 2, i + 6)
                // 逐位判 hex:toIntOrNull(16) 会接受前导 +/-,"\u-123" 不能被当成合法转义。
                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                sb.append(hex.toInt(16).toChar())
                i += 6
                continue
            }
            else -> return null
        }
        i += 2
    }
    return sb.toString()
}

/**
 * 有没有**这台设备能装**的新版:版本号更高,且设备 SDK 不低于它要求的 minSdk。
 * 设备太旧装不了的新版按「已是最新」处理——没有任何这台设备能做的事。
 */
fun isNewer(info: LatestInfo, currentCode: Int, sdk: Int): Boolean =
    info.versionCode > currentCode && sdk >= info.minSdk

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * 文件的 SHA-256,64 位小写 hex(与 `shasum -a 256` 输出一致)。分块读,百兆文件也不整读进内存。
 * 不用 `"%02x".format`:`String.format` 走默认 Locale,查表拼接与语言设置无关。
 * **阻塞 IO,调用方负责放到 IO 线程。**
 */
fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    val sb = StringBuilder(64)
    for (b in md.digest()) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
    }
    return sb.toString()
}

/**
 * 下载进度百分比。**只有 `Content-Length` 已知(> 0)才给数字**,否则 null → 界面显示
 * 不带数字的「下载中…」,绝不拿一个猜出来的总长去算。夹到 0..100:服务器多给的字节
 * 由下载函数的长度核对判失败,这里不负责。
 */
fun downloadPercent(done: Long, total: Long): Int? =
    if (total <= 0) null else (done.coerceAtLeast(0) * 100 / total).coerceIn(0, 100).toInt()

/**
 * 逐个通道取 `latest.json`,**第一个解析成功的即为结果**(spec §7.3:COS 在前、GitHub 在后,
 * 各自 8 s 超时由 [fetch] 的实现负责)。
 *
 * - 不合规的地址([isAllowedUpdateUrl])**连请求都不发**,直接跳过;
 * - [fetch] 抛任何 `Exception` 都算这个通道网络失败,换下一个——`fetch` 是阻塞调用,
 *   这里不会遇到协程取消异常;
 * - 取回了正文但解析不出 → 这个通道算格式错误,同样换下一个(主通道文件坏了,备用通道可能是好的);
 * - 全部失败时:只要有一个通道给过正文就报 [CheckFailure.BAD_JSON](那是服务端的问题,
 *   比「请稍后再试」更准确),否则报 [CheckFailure.NETWORK]。
 *
 * 每个失败的通道经 [log] 记一行(含地址与原因):用户在真机上反馈「检查失败」时,
 * `adb logcat` 里能直接看到是哪一个通道、卡在哪一步。
 * [fetch] 放最后一个参数,调用处可以写成尾随 lambda。
 */
fun resolveLatest(
    urls: List<String>,
    log: (String) -> Unit = {},
    fetch: (String) -> String,
): Result<LatestInfo> {
    var sawBody = false
    for (url in urls) {
        if (!isAllowedUpdateUrl(url)) {
            log("update channel skipped (not https): $url")
            continue
        }
        val body = try {
            fetch(url)
        } catch (e: Exception) {
            log("update channel failed: $url: ${e.javaClass.simpleName}: ${e.message}")
            continue
        }
        val info = parseLatest(body)
        if (info != null) return Result.success(info)
        sawBody = true
        log("update channel returned unparsable latest.json: $url (${body.length} chars)")
    }
    return Result.failure(UpdateCheckException(if (sawBody) CheckFailure.BAD_JSON else CheckFailure.NETWORK))
}
