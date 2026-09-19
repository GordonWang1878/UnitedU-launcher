package com.uniteduone.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * HDMI-CEC 父子去重(M4b spec §1.3 / Ruling M):某个输入若是任何其它输入的 parentId,就不列它,
 * 只列它下面的子设备(「PlayStation 5」比「HDMI 1」有用)。只看一层;顺序不变。
 */
internal fun dedupeCec(entries: List<InputEntry>): List<InputEntry> {
    val parents = entries.mapNotNull { it.parentId }.toSet()
    return entries.filter { it.id !in parents }
}

/** 先去掉隐藏的,再把改过名的换成用户起的名字(名字来自 titles.json,key = 输入 id)。 */
internal fun applyInputPrefs(
    entries: List<InputEntry>,
    hidden: Set<String>,
    names: Map<String, String>,
): List<InputEntry> =
    entries.filter { it.id !in hidden }.map { e -> names[e.id]?.let { e.copy(label = it) } ?: e }

/** hidden-inputs.json = {"<输入 id>":"hidden", …}:复用 titles.json 的纯函数解析 / 序列化(JVM 可测)。 */
internal fun parseHiddenInputs(text: String): Set<String> = parseTitles(text).keys

internal fun hiddenInputsToJson(ids: Set<String>): String = titlesToJson(ids.associateWith { "hidden" })

/** 读写照 [Titles]:外置没挂 = 空集;缺文件 = 空集;坏文件改名 .bad + 写空 + Log.w;写走 tmp → fsync → rename。 */
object HiddenInputs {
    private const val TAG = "UnitedU"

    fun read(ctx: Context): Set<String> {
        if (Paths.baseOrNull(ctx) == null) return emptySet()
        val f = Paths.hiddenInputsJson(ctx)
        if (!f.exists()) return emptySet()
        return try {
            if (f.length() > 1_000_000) error("hidden-inputs.json 大得离谱: ${f.length()} 字节")
            val text = f.readText()
            if (!isWellFormedJsonObject(text)) error("hidden-inputs.json 不是合法的 JSON 对象")
            parseHiddenInputs(text)
        } catch (e: Throwable) {
            Log.w(TAG, "hidden-inputs.json 读不了,改名保留并重写空表: ${e.message}")
            runCatching { f.renameTo(Paths.hiddenInputsBad(ctx)) }
            write(ctx, emptySet())
            emptySet()
        }
    }

    fun write(ctx: Context, ids: Set<String>): Boolean {
        val base = Paths.baseOrNull(ctx) ?: return false
        val tmp = File(base, "hidden-inputs.json.tmp")
        return try {
            FileOutputStream(tmp).use { out -> out.write(hiddenInputsToJson(ids).toByteArray()); out.flush(); out.fd.sync() }
            val dst = Paths.hiddenInputsJson(ctx)
            if (tmp.renameTo(dst)) return true
            dst.delete()
            tmp.renameTo(dst)
        } catch (e: Throwable) {
            Log.w(TAG, "hidden-inputs.json 写不了: ${e.message}")
            false
        }
    }

    /** 隐藏 / 取消隐藏一个输入。IO 线程调用。 */
    fun set(ctx: Context, id: String, hidden: Boolean): Boolean {
        val now = read(ctx)
        val next = if (hidden) now + id else now - id
        return next == now || write(ctx, next)
    }

    /** 一键恢复全部(设置页「恢复隐藏的输入源」)。IO 线程调用。 */
    fun clear(ctx: Context): Boolean = read(ctx).isEmpty() || write(ctx, emptySet())
}
