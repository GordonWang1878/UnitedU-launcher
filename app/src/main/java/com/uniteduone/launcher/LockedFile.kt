package com.uniteduone.launcher

import java.io.File
import java.io.FileOutputStream

/**
 * 一个多写者共用的小 JSON 文件(layout.json / titles.json)的落盘层:只管字节,不管格式(格式与回落在调用方)。
 * 纯 `java.io`,JVM 单测直接压并发([LockedFileTest])。
 *
 * **为什么要有它**(2026-09-23 A95L 真机事故):卸载一个应用时,MainActivity 的动态接收器与清单里的
 * `PackageRemovedReceiver` 对同一次卸载各跑一遍 read → 改 → write(一个在 IO 协程、一个在裸线程),
 * 两边共用同一个 `layout.json.tmp`;输的一方 rename 失败走兜底 `dst.delete()`,正式文件没了,
 * 下一次 `Layout.read` 当成「首次运行」把内置默认布局写回去——用户排好的整个首页被换成默认分类表。
 * `SettingsStore` 早就为同一个坑加了锁(「不加它会真的丢掉全部设置」),Layout / Titles 一直没加。
 *
 * 三层防线:
 * 1. [lock]:同一进程内所有读、写、读改写串行(两个接收器都在本应用进程里)。JVM monitor 可重入,
 *    [update] 里套 [readText] / [write] 不会自锁;调用方要把「读 → 改 → 写」整段包进 [locked]。
 * 2. 写:每次一个独立的临时文件,rename 之前把现有正式文件**复制**成 `.prev`(复制而不是改名,正式文件全程都在)。
 * 3. 读:正式文件不在时从 `.prev` 读回(调用方据 [Read.fromPrev] 写回正式文件并留痕),而不是直接当首次运行。
 */
class LockedFile(private val name: String) {
    data class Read(val text: String, val fromPrev: Boolean)

    private val lock = Any()

    fun <T> locked(block: () -> T): T = synchronized(lock, block)

    fun file(dir: File) = File(dir, name)
    fun prev(dir: File) = File(dir, "$name.prev")

    /** 正式文件的全文;不在就读 `.prev`;两个都没有 → null(真正的首次运行)。 */
    fun readText(dir: File): Read? = locked {
        val f = file(dir)
        if (f.exists()) return@locked Read(f.readText(), fromPrev = false)
        val p = prev(dir)
        if (p.exists()) Read(p.readText(), fromPrev = true) else null
    }

    /**
     * 原子替换正式文件。rename 失败时仍保留旧的 `dst.delete()` 兜底(某些文件系统不许覆盖式 rename),
     * 但它现在只会在锁内、且 `.prev` 已经备好之后发生,读者看不到那个空档,真出事也能从 `.prev` 读回。
     */
    fun write(dir: File, text: String): Boolean = locked {
        val tmp = File.createTempFile("$name.", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { out -> out.write(text.toByteArray()); out.flush(); out.fd.sync() }
            val dst = file(dir)
            if (dst.exists()) runCatching { dst.copyTo(prev(dir), overwrite = true) }
            if (tmp.renameTo(dst)) return@locked true
            dst.delete()
            tmp.renameTo(dst)
        } catch (e: Throwable) {
            // 写临时文件本身失败才清掉它;rename 兜底失败时反而要留着 tmp(那时它是唯一一份新数据)
            tmp.delete()
            throw e
        }
    }

    /** 读 → 改 → 写 一整段在锁内。[transform] 收到 null = 正式文件与 `.prev` 都不在。 */
    fun update(dir: File, transform: (String?) -> String): Boolean = locked {
        write(dir, transform(readText(dir)?.text))
    }
}
