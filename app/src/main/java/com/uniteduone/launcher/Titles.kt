package com.uniteduone.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** 卡片自定义标题(design §2「修改标题」):titles.json = {"pkg": "标题", …},独立于 layout.json。 */

const val MAX_TITLE_CHARS = 40

/** 去首尾空白与控制字符,截到 40 字符;空 = 「没有自定义标题」(恢复应用名)。 */
fun sanitizeTitle(raw: String?): String =
    (raw ?: "").filter { it.code >= 0x20 && it.code != 0x7F }.trim().take(MAX_TITLE_CHARS)

private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

/** 固定顺序、每行一条,adb pull 下来好读。空表输出 "{}\n"。 */
fun titlesToJson(m: Map<String, String>): String {
    if (m.isEmpty()) return "{}\n"
    return m.entries.sortedBy { it.key }
        .joinToString(",\n", prefix = "{\n", postfix = "\n}\n") { (k, v) -> "  \"${esc(k)}\": \"${esc(v)}\"" }
}

/**
 * 纯函数、不抛:扁平「字符串 → 字符串」对象的极简解析(与 Settings 同一理由:org.json 单测里用不了)。
 * 非字符串值、空键、空标题一律丢弃;语法坏了返回空表(调用方把文件当损坏处理)。
 */
fun parseTitles(json: String): Map<String, String> {
    val t = json.trim()
    if (!t.startsWith("{") || !t.endsWith("}")) return emptyMap()
    val out = LinkedHashMap<String, String>()
    var i = 1
    fun skipWs() { while (i < t.length && t[i].isWhitespace()) i++ }
    fun readString(): String? {
        if (i >= t.length || t[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < t.length) {
            val c = t[i]
            when {
                c == '\\' && i + 1 < t.length -> { sb.append(t[i + 1]); i += 2 }
                c == '"' -> { i++; return sb.toString() }
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }
    while (true) {
        skipWs()
        if (i < t.length && t[i] == '}') return out
        val key = readString() ?: return emptyMap()
        skipWs()
        if (i >= t.length || t[i] != ':') return emptyMap()
        i++
        skipWs()
        val value = readString() ?: return emptyMap()   // 非字符串值:整个文件按损坏处理
        val title = sanitizeTitle(value)
        if (key.isNotBlank() && title.isNotEmpty()) out[key] = title
        skipWs()
        if (i < t.length && t[i] == ',') { i++; continue }
        if (i < t.length && t[i] == '}') return out
        return emptyMap()
    }
}

/** 读写照 SettingsStore:外置没挂用内存空表;缺文件 = 空表;坏文件改名 .bad + 写空表 + Log.w;写走 tmp → rename。 */
object Titles {
    private const val TAG = "UnitedU"

    fun read(ctx: Context): Map<String, String> {
        if (Paths.baseOrNull(ctx) == null) return emptyMap()
        val f = Paths.titlesJson(ctx)
        if (!f.exists()) return emptyMap()
        return try {
            if (f.length() > 1_000_000) error("titles.json 大得离谱: ${f.length()} 字节")
            val text = f.readText()
            if (!isWellFormedJsonObject(text)) error("titles.json 不是合法的 JSON 对象")
            parseTitles(text)
        } catch (e: Throwable) {
            Log.w(TAG, "titles.json 读不了,改名保留并重写空表: ${e.message}")
            runCatching { f.renameTo(Paths.titlesBad(ctx)) }
            write(ctx, emptyMap())
            emptyMap()
        }
    }

    fun write(ctx: Context, m: Map<String, String>): Boolean {
        val base = Paths.baseOrNull(ctx) ?: return false
        val tmp = File(base, "titles.json.tmp")
        return try {
            FileOutputStream(tmp).use { out -> out.write(titlesToJson(m).toByteArray()); out.flush(); out.fd.sync() }
            val dst = Paths.titlesJson(ctx)
            if (tmp.renameTo(dst)) return true
            dst.delete()
            tmp.renameTo(dst)
        } catch (e: Throwable) {
            Log.w(TAG, "titles.json 写不了: ${e.message}")
            false
        }
    }

    /** 设置/清除一个应用的标题(空 = 清除)。IO 线程调用。 */
    fun set(ctx: Context, pkg: String, title: String): Boolean {
        val clean = sanitizeTitle(title)
        val m = read(ctx).toMutableMap()
        if (clean.isEmpty()) m.remove(pkg) else m[pkg] = clean
        return write(ctx, m)
    }
}
