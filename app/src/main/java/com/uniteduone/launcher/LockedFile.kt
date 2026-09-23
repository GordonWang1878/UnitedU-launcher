package com.uniteduone.launcher

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 多写者共用的小 JSON 状态文件(layout.json / titles.json / hidden-inputs.json / settings.json)的落盘层:只管字节,不管格式(格式与回落在调用方)。
 * 纯 `java.io`,JVM 单测直接压并发([LockedFileTest])。
 *
 * **为什么要有它**(2026-09-23 A95L 真机事故):卸载一个应用时,MainActivity 的动态接收器与清单里的
 * `PackageRemovedReceiver` 对同一次卸载各跑一遍 read → 改 → write(一个在 IO 协程、一个在裸线程),
 * 两边共用同一个 `layout.json.tmp`;输的一方 rename 失败走兜底 `dst.delete()`,正式文件没了,
 * 下一次 `Layout.read` 当成「首次运行」把内置默认布局写回去——用户排好的整个首页被换成默认分类表。
 * `SettingsStore` 早就为同一个坑加了锁(「不加它会真的丢掉全部设置」),Layout / Titles 一直没加。
 * 排查表(每个落盘文件的写者、风险、处置)见 `docs/design/persistence-audit.md`。
 *
 * 三层防线:
 * 1. [lock]:同一进程内所有读、写、读改写串行(两个接收器都在本应用进程里)。JVM monitor 可重入,
 *    [update] 里套 [readText] / [write] 不会自锁;调用方要把「读 → 改 → 写」整段包进 [locked]。
 * 2. 写:每次一个独立的临时文件,rename 之前把现有正式文件**复制**成 `.prev`(复制而不是改名,正式文件全程都在)。
 * 3. 读:正式文件不在时从 `.prev` 读回(调用方据 [Read.fromPrev] 写回正式文件并留痕),而不是直接当首次运行。
 */
class LockedFile(private val name: String) {
    data class Read(val text: String, val fromPrev: Boolean)

    /** [load] 的结果。 */
    sealed interface Load<out T> {
        /** 正式文件与 `.prev` 都不在:真正的首次运行 / 从没写过。 */
        data object Missing : Load<Nothing>
        /** 有文件,但一份都解析不了(坏的正式文件已改名 `.bad` 留证)。调用方写回默认值。 */
        data object Corrupt : Load<Nothing>
        /** 能用的内容。[restored] = 取自 `.prev`(正式文件不在或坏了),调用方应把它写回正式文件。 */
        data class Ok<T>(val value: T, val restored: Boolean) : Load<T>
    }

    private val lock = Any()

    fun <T> locked(block: () -> T): T = synchronized(lock, block)

    fun file(dir: File) = File(dir, name)
    fun prev(dir: File) = File(dir, "$name.prev")
    fun bad(dir: File) = File(dir, "$name.bad")

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

    /**
     * 所有状态文件共用的「读 + 解析 + 回落」口径(Layout / Titles / HiddenInputs / SettingsStore):
     * 正式文件能解析 → 用它;不在或坏了(坏的改名 `.bad` 留证)→ 试 `.prev`;都不行 → [Load.Corrupt] / [Load.Missing]。
     * **只读不写**:写回(`.prev` 恢复、写默认值)由调用方在**同一个** [locked] 里做,缺失时的「写默认」才不会落进别人写盘的间隙。
     * [parse] 抛任何 Throwable 都算坏(超大文件 readText 抛的是 OutOfMemoryError);超过 [maxBytes] 不读、直接算坏。
     */
    fun <T> load(dir: File, maxBytes: Long = 1_000_000, log: (String) -> Unit = {}, parse: (String) -> T): Load<T> = locked {
        fun tryParse(f: File): Result<T> = runCatching {
            if (f.length() > maxBytes) error("${f.name} 大得离谱: ${f.length()} 字节")
            parse(f.readText())
        }
        val f = file(dir)
        var sawFile = false
        if (f.exists()) {
            sawFile = true
            val r = tryParse(f)
            if (r.isSuccess) return@locked Load.Ok(r.getOrThrow(), restored = false)
            log("$name 读不了,改名 ${bad(dir).name} 保留: ${r.exceptionOrNull()?.message}")
            runCatching { f.renameTo(bad(dir)) }
        }
        val p = prev(dir)
        if (!p.exists()) return@locked if (sawFile) Load.Corrupt else Load.Missing
        val r = tryParse(p)
        if (r.isSuccess) {
            log("$name ${if (sawFile) "坏了" else "不见了"},从 ${p.name} 恢复")
            Load.Ok(r.getOrThrow(), restored = true)
        } else {
            log("${p.name} 也读不了: ${r.exceptionOrNull()?.message}")
            Load.Corrupt
        }
    }

    /** 读 → 改 → 写 一整段在锁内。[transform] 收到 null = 正式文件与 `.prev` 都不在。 */
    fun update(dir: File, transform: (String?) -> String): Boolean = locked {
        write(dir, transform(readText(dir)?.text))
    }
}

/**
 * 单写者或「谁最后写谁赢」的二进制文件(自定义卡片图、壁纸处理缓存、内置壁纸铺入、上传落盘的跨卷回落)的原子替换:
 * **每次一个独立的临时文件**(同目录、扩展名 `.tmp`,图库扫描按图片扩展名过滤,看不见它)→ fsync →
 * [verify] 通过才 rename。rename 失败**不删正式文件**,只清掉临时文件、返回 false(旧文件原样还在)。
 * 旧写法是固定的 `<名>.tmp` + 「rename 失败 → 删正式文件再 rename」:两个写者共用一个临时文件时会写出交错的半截内容,
 * 输的一方还会把赢家刚放好的文件删掉——就是 2026-09-23 layout.json 丢失的同一个形状。
 * 纯 `java.io`,JVM 单测见 [LockedFileTest]。
 */
fun writeFileAtomically(dst: File, verify: (File) -> Boolean = { true }, write: (OutputStream) -> Unit): Boolean {
    val dir = dst.absoluteFile.parentFile ?: return false
    dir.mkdirs()
    val tmp = File.createTempFile(".${dst.name}.", ".tmp", dir)
    try {
        FileOutputStream(tmp).use { out -> write(out); out.flush(); out.fd.sync() }
        if (!verify(tmp)) return false
        return tmp.renameTo(dst)
    } finally {
        tmp.delete()   // 改名成功后它已不存在,delete 是空操作
    }
}
