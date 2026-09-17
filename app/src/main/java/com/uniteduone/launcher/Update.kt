package com.uniteduone.launcher

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/**
 * 检查更新的网络与文件 IO(spec §7.3)。**判定全在 UpdateChecker.kt**(纯函数、有单测):
 * 先问哪个通道、什么地址能访问、正文算不算数、失败算哪一种。这里只负责按那些规则
 * 把字节取回来、落到 `cacheDir/apk/` 里。只用 `HttpURLConnection`(不加依赖),
 * 所有方法都不在主线程上做事:[check] 标了 `@WorkerThread`,[download] / [sweepStale] 自己切到 IO。
 */
object Update {
    private const val TAG = "UnitedU"

    /** spec §7.3:每个通道 8 s。连接与每次读各自计时(`HttpURLConnection` 没有「总时长」)。 */
    private const val TIMEOUT_MS = 8_000

    /** latest.json 正文上限。正常文件几百字节;被劫持成门户页、或地址配错指向了大文件时不必读完。 */
    private const val MAX_JSON_BYTES = 64 * 1024

    /** 更新包上限 100 MB。UnitedU 本体约 3 MB,这是防「地址指错」的护栏,不是容量规划。 */
    private const val MAX_APK_BYTES = 100L * 1024 * 1024

    private const val PART_PREFIX = "update-"
    private const val PART_SUFFIX = ".part"

    /**
     * 构建时注入的通道列表(`app/build.gradle.kts` 从 Gradle 属性 `unitedu.updateUrls` 读,
     * 缺省只有 GitHub)。源码里不写死任何 COS 地址(spec §7.3)。
     */
    fun configuredUrls(): List<String> = parseUpdateUrls(BuildConfig.UPDATE_URLS)

    /** 校验通过后交给系统安装器的文件。只有 `cacheDir/apk/` 经 FileProvider 开放(res/xml/file_paths.xml)。 */
    fun apkFile(ctx: Context): File = File(apkDir(ctx), "update.apk")

    /**
     * `cacheDir/apk/` 里更新文件的**进程级**锁:「下载 → 校验 → 交给安装器」整段持有它,
     * [sweepStale] 拿不到就不扫。
     *
     * 为什么要进程级而不是每个关于页一把:MainActivity 可以**同时有两个实例**——HOME 启动的在
     * home 任务里,从应用列表 / `am start -n` 启动的在普通任务里(`singleTask` 不会跨这两种任务复用,
     * 2026-09-17 模拟器实测),各自有一个 [AboutController]。不共用一把锁的话:
     * ① 新实例 `onCreate` 的清扫会删掉另一个实例正在写的临时文件(实测:后台那次下载写完后
     * 改名失败);② 两次下载轮流改名覆盖 `update.apk`,一个实例刚校验过的文件可能在交给安装器
     * 之前被另一个实例换掉。`Mutex` 是公平的(先来先得),等锁可以被取消(返回键照样有效)。
     */
    val fileLock = Mutex()

    private fun apkDir(ctx: Context) = File(ctx.cacheDir, "apk")

    /** 逐个通道取 latest.json,规则见 [resolveLatest];每个失败的通道记一行 logcat。 */
    @WorkerThread
    fun check(urls: List<String>): Result<LatestInfo> =
        resolveLatest(urls, log = { Log.w(TAG, it) }) { url -> fetchText(url) }

    /**
     * 清掉上一条命留下的更新文件:进程在下载中途被杀会留下 `update-*.part`;装成功后
     * 进程被替换,`update.apk` 没人删。在 `MainActivity.onCreate` 调一次(与 UploadServer 的
     * sweepStale 同一思路)。
     *
     * **只在拿得到 [fileLock] 时才扫**:另一个 MainActivity 实例可能正在下载 / 等着交给安装器,
     * 这一轮就跳过,留给下一次启动。即便系统安装器此刻还开着那个文件也没关系:它经
     * FileProvider 拿的是已打开的描述符,unlink 不影响它读。
     */
    suspend fun sweepStale(ctx: Context) {
        if (!fileLock.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                runCatching {
                    apkFile(ctx).delete()
                    apkDir(ctx).listFiles()?.forEach {
                        if (it.name.startsWith(PART_PREFIX) && it.name.endsWith(PART_SUFFIX)) it.delete()
                    }
                }
            }
        } finally {
            fileLock.unlock()
        }
    }

    /**
     * 下载 [url] 到 [dst]:先写同目录下的唯一临时文件,写完、长度核对通过才改名成 [dst]。
     * 返回 false = 下载失败(已记日志);**被取消时抛 [CancellationException]**,不返回 false,
     * 调用方不会把「用户按了返回」误报成「下载失败」。
     *
     * - [onProgress] 在 IO 线程上调用,只在百分比变化时调;`Content-Length` 未知时只调一次 `null`
     *   ([downloadPercent])。调用方自己负责切回主线程。
     * - 超过 100 MB(声明的或实际读到的)立刻放弃。
     * - `Accept-Encoding: identity`:不让平台透明解 gzip——那样 `Content-Length` 会被抹成 -1,
     *   百分比就没了,读到的字节数也对不上声明的长度。
     * - **取消与临时文件**:阻塞在 `read()` 里的线程看不到协程状态,不处理的话要等 8 s 读超时
     *   才发现自己被取消。所以另起一个子协程守着:父协程一取消,它就从另一个线程
     *   `disconnect()`——平台实现会关掉底层 socket,`read()` 当场抛异常,`finally` 删掉半截文件。
     *   子协程用 `UNDISPATCHED` 启动:当场跑进 `try`、挂起在 `awaitCancellation()`;
     *   普通启动方式下,若父协程在它被调度之前就取消,它的 `finally` 一次都不会执行。
     * - 临时文件名每次唯一(`createTempFile`):被取消的上一次下载若还没退出,它的 `finally`
     *   删不到这一次的临时文件。
     */
    suspend fun download(url: String, dst: File, onProgress: (Int?) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!isAllowedUpdateUrl(url)) {
            Log.w(TAG, "update download refused (not https): $url")
            return@withContext false
        }
        val dir = dst.parentFile ?: return@withContext false
        val conn = try {
            open(url, identity = true)
        } catch (e: Exception) {
            Log.w(TAG, "update download failed to open: $url: $e")
            return@withContext false
        }
        val killer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                conn.disconnect()
            }
        }
        var part: File? = null
        try {
            connect(conn)
            val total = conn.contentLengthLong
            if (total > MAX_APK_BYTES) throw IOException("apk too large: $total bytes")
            dir.mkdirs()
            val tmp = File.createTempFile(PART_PREFIX, PART_SUFFIX, dir)
            part = tmp
            var done = 0L
            var last = downloadPercent(0, total)
            onProgress(last)
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_APK_BYTES) throw IOException("apk exceeds $MAX_APK_BYTES bytes")
                        out.write(buf, 0, n)
                        val p = downloadPercent(done, total)
                        if (p != last) {
                            last = p
                            onProgress(p)
                        }
                    }
                }
            }
            // 连接中途断开时平台不一定抛异常(Content-Length 未知时只会看到 EOF);声明了长度就逐字节核对。
            if (total >= 0 && done != total) throw IOException("short body: $done of $total bytes")
            ensureActive()
            if (!tmp.renameTo(dst)) {
                dst.delete()
                if (!tmp.renameTo(dst)) throw IOException("rename to ${dst.name} failed")
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 被取消时 read() 抛的是 IOException(连接被 killer 掐断)——那是取消,不是失败。
            ensureActive()
            Log.w(TAG, "update download failed: $url: $e")
            false
        } finally {
            killer.cancel()
            conn.disconnect()
            // 成功路径上它已被改名,这里是空操作;失败 / 取消路径上删掉半截文件。
            part?.delete()
        }
    }

    /**
     * 建连接但不发请求(`responseCode` 那一刻才真正连接)。
     * `instanceFollowRedirects` 保持默认的 true:GitHub 的 `releases/latest/download/…`
     * 回 302 到另一个 https 主机,必须跟。Android 的实现**不跟跨协议重定向**(https→http
     * 会把 3xx 原样交回来,[connect] 的 200 判定就拦下了),[connect] 另外还核对最终地址。
     */
    private fun open(url: String, identity: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            // 让 CDN / COS 回源校验,不要把几分钟前的旧 latest.json 当成「已是最新」。
            setRequestProperty("Cache-Control", "no-cache")
            if (identity) setRequestProperty("Accept-Encoding", "identity")
        }

    /** 发请求、跟完重定向、读完响应头;只接受 200,且最终地址仍须过 [isAllowedUpdateUrl]。 */
    private fun connect(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (!isAllowedUpdateUrl(conn.url.toString())) throw IOException("redirected to a disallowed url: ${conn.url}")
        if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
    }

    private fun fetchText(url: String): String {
        val conn = open(url, identity = false)
        try {
            connect(conn)
            val declared = conn.contentLengthLong
            if (declared > MAX_JSON_BYTES) throw IOException("latest.json too large: $declared bytes")
            val bytes = conn.inputStream.use { readCapped(it, MAX_JSON_BYTES) }
            // 手工编辑的文件可能带 UTF-8 BOM;它不算空白,留着会让「是不是一个完整对象」的判断失败。
            return String(bytes, Charsets.UTF_8).removePrefix("﻿")
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > cap) throw IOException("body exceeds $cap bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
