package com.uniteduone.launcher

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * 检查更新的网络与文件 IO(spec §7.3)。**判定全在 UpdateChecker.kt**(纯函数、有单测):
 * 先问哪个通道、什么地址能访问、正文算不算数、更新包身份对不对、失败算哪一种。这里只负责
 * 按那些规则把字节取回来、落到 `cacheDir/apk/` 里、从 PackageManager 读出身份。
 * 只用 `HttpURLConnection`(不加依赖);[check] / [verify] / [sweepStale] 是阻塞调用
 * (标了 `@WorkerThread`),[download] 自己切到 IO。
 */
object Update {
    private const val TAG = "UnitedU"

    /** spec §7.3:每个通道 8 s。连接与每次读各自计时(`HttpURLConnection` 没有「总时长」)。 */
    private const val TIMEOUT_MS = 8_000

    /** latest.json 正文上限。正常文件几百字节;被劫持成门户页、或地址配错指向了大文件时不必读完。 */
    private const val MAX_JSON_BYTES = 64 * 1024

    /** 更新包上限 100 MB。UnitedU 本体约 3 MB,这是防「地址指错」的护栏,不是容量规划。 */
    private const val MAX_APK_BYTES = 100L * 1024 * 1024

    private const val PART_SUFFIX = ".part"
    private const val APK_SUFFIX = ".apk"

    /**
     * 构建时注入的通道列表(`app/build.gradle.kts` 从 Gradle 属性 `unitedu.updateUrls` 读,
     * 缺省只有 GitHub)。源码里不写死任何 COS 地址(spec §7.3)。
     */
    fun configuredUrls(): List<String> = parseUpdateUrls(BuildConfig.UPDATE_URLS)

    @Volatile private var registry: UpdateFiles? = null

    /**
     * `cacheDir/apk/`(FileProvider 只开放这个目录,见 res/xml/file_paths.xml)的**进程内唯一**登记簿。
     *
     * 必须进程内唯一:MainActivity 同时有 LEANBACK_LAUNCHER 与 HOME 两个入口,`singleTask` 不跨
     * 这两种任务复用,生产环境里本来就可能同时存在两个实例、各有一个 [AboutController]
     * (2026-09-17 模拟器实测)。各记各的账,一个实例 `onCreate` 的清扫就会删掉另一个实例正在用的文件。
     */
    fun files(ctx: Context): UpdateFiles = registry ?: synchronized(this) {
        registry ?: UpdateFiles(File(ctx.applicationContext.cacheDir, "apk")).also { registry = it }
    }

    /** 与任何页面的生命周期无关的删文件协程:页面关掉 / Activity 销毁后,丢弃的文件照样删掉。 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 丢弃一个不再交给安装器的文件(删除并注销),在后台线程做。 */
    fun discard(ctx: Context, file: File) {
        val files = files(ctx)
        cleanupScope.launch { files.release(file) }
    }

    /** 为一次下载尝试预留一个独占的目标文件名(只登记,不碰磁盘,主线程可调)。 */
    fun reserveApk(ctx: Context): File = files(ctx).reserve(APK_SUFFIX)

    /** 逐个通道取 latest.json,规则见 [resolveLatest];每个失败的通道记一行 logcat。 */
    @WorkerThread
    fun check(urls: List<String>): Result<LatestInfo> =
        resolveLatest(urls, log = { Log.w(TAG, it) }) { url -> fetchText(url) }

    /**
     * 清掉上一条命留下的更新文件(进程在下载中途被杀留下的 `.part`、装成功后进程被替换没人删的 `.apk`、
     * 上一版固定文件名的 `update.apk`)。在 `MainActivity.onCreate` 调(与 UploadServer 的 sweepStale 同一思路)。
     * **本进程登记在案的文件一律跳过**:另一个 MainActivity 实例可能正在下载、等着用户按「安装」、
     * 或者刚把文件交给系统安装器(安装器经 FileProvider 异步读它)。
     */
    @WorkerThread
    fun sweepStale(ctx: Context) {
        val n = runCatching { files(ctx).sweep() }.getOrDefault(0)
        if (n > 0) Log.i(TAG, "swept $n stale update file(s)")
    }

    /**
     * 哈希通过之后再核对身份(review Important 1,规则见 [checkUpdateApk]),全部通过返回 null。
     * 哈希不符就不去解析这个文件了(它可能根本不是 APK)。任何拒绝都记一行日志,删文件由调用方做。
     */
    @WorkerThread
    fun verify(ctx: Context, file: File, info: LatestInfo): UpdateRejection? {
        val actual = runCatching { sha256Hex(file) }.getOrNull()
        if (actual != info.sha256) {
            Log.w(TAG, "update rejected: HASH_MISMATCH expected ${info.sha256}, got $actual")
            return UpdateRejection.HASH_MISMATCH
        }
        val archive = archiveIdentity(ctx, file)
        val installed = installedIdentity(ctx)
        val rejection = checkUpdateApk(archive, installed, info.versionCode)
        if (rejection != null) Log.w(TAG, "update rejected: $rejection; archive=$archive; installed=$installed")
        return rejection
    }

    /**
     * 解析下载下来的 APK。**API 28 的 `getPackageArchiveInfo` 只在带 `GET_SIGNATURES` 时才收集证书**
     * (只给 `GET_SIGNING_CERTIFICATES` 的话 `signingInfo` 恒为 null),所以两个标志一起给;
     * 29 起任一标志都会收集。解析不了(不是 APK)返回 null。
     */
    @Suppress("DEPRECATION")
    private fun archiveIdentity(ctx: Context, file: File): ApkIdentity? = runCatching {
        ctx.packageManager
            .getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES,
            )
            ?.let(::identityOf)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun installedIdentity(ctx: Context): ApkIdentity? = runCatching {
        identityOf(ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
    }.getOrNull()

    /**
     * 当前签名者:多签名时是全部 `apkContentsSigners`,单签名时是证书历史的最后一张
     * (按 `SigningInfo` 文档,两个方法各自只在对应情形下使用)。读不到就给空集合,由纯函数判 UNSIGNED。
     */
    private fun identityOf(pi: PackageInfo): ApkIdentity {
        val signing = pi.signingInfo
        val certs: List<Signature> = when {
            signing == null -> emptyList()
            signing.hasMultipleSigners() -> signing.apkContentsSigners?.toList().orEmpty()
            else -> listOfNotNull(signing.signingCertificateHistory?.lastOrNull())
        }
        return ApkIdentity(pi.packageName.orEmpty(), pi.longVersionCode, certs.map { sha256Hex(it.toByteArray()) }.toSet())
    }

    /**
     * 下载 [url] 到 [dst]([reserveApk] 预留的独占文件名):先写一个同样登记在案的唯一临时文件,
     * 写完、长度核对通过才在登记簿的锁内改名成 [dst]([UpdateFiles.promote])——锁只包住改名这一下,
     * 下载本身不持锁,两个实例的下载互不等待。
     * 返回 false = 下载失败(已记日志);**被取消时抛 [CancellationException]**,不返回 false,
     * 调用方不会把「用户按了返回」误报成「下载失败」。[dst] 的去留归调用方管。
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
     * - 临时文件名每次唯一且在登记簿里:被取消的上一次下载若还没退出,它的 `finally` 删不到这一次的文件;
     *   另一个实例的清扫也碰不到它。
     */
    suspend fun download(ctx: Context, url: String, dst: File, onProgress: (Int?) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!isAllowedUpdateUrl(url)) {
            Log.w(TAG, "update download refused (not https): $url")
            return@withContext false
        }
        val files = files(ctx)
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
        // 只在内存里登记名字,紧接着进 try:任何一条出口都经 finally 注销并删除。
        val part = files.reserve(PART_SUFFIX)
        try {
            connect(conn)
            val total = conn.contentLengthLong
            if (total > MAX_APK_BYTES) throw IOException("apk too large: $total bytes")
            part.parentFile?.mkdirs()
            var done = 0L
            var last = downloadPercent(0, total)
            onProgress(last)
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
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
            if (!files.promote(part, dst)) throw IOException("rename to ${dst.name} failed")
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
            // 成功路径上它已被改名并注销,这里是空操作;失败 / 取消路径上删掉半截文件并注销。
            files.release(part)
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
            return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
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

/**
 * `cacheDir/apk/` 里更新文件的登记簿(review Important 2)。只用 java.io,JVM 单测直接测(UpdateFilesTest)。
 *
 * 机制:**每次尝试一个独占文件**,名字在内存里先登记([reserve])再落盘;凡是登记着的文件——
 * 下载中的 `.part`、校验中 / 等用户按「安装」的 `.apk`、已交给系统安装器的 `.apk`——[sweep] 一个都不碰。
 * 锁(本对象的监视器)只包住「登记 / 注销 / 改名 / 清扫」这几下瞬时操作,下载、算哈希、等用户
 * 这些长时间的事一律不持锁,所以两个 MainActivity 实例的更新流程互不阻塞。
 *
 * 交给安装器的文件**本进程内不再注销**:安装器经 FileProvider 异步读它,什么时候读完我们无从得知;
 * 它会在下一次冷启动(包括更新成功后新进程的那一次)被 [sweep] 清掉。
 */
class UpdateFiles(private val dir: File) {
    private val inUse = HashSet<String>()

    /** 预留一个独占文件名并登记。只动内存,不碰磁盘(主线程可调)。 */
    fun reserve(suffix: String): File = synchronized(this) {
        var file: File
        do {
            file = File(dir, "update-" + UUID.randomUUID() + suffix)
        } while (file.name in inUse)
        inUse += file.name
        file
    }

    /** 下载完的临时文件改名成预留好的目标(锁内)。成功后临时文件的名字注销;失败则原样保留登记。 */
    fun promote(part: File, dst: File): Boolean = synchronized(this) {
        val ok = part.renameTo(dst)
        if (ok) inUse -= part.name
        ok
    }

    /** 删除并注销。返回此刻文件是否已不存在(本来就没落盘也算)。 */
    fun release(file: File): Boolean = synchronized(this) {
        file.delete()
        inUse -= file.name
        !file.exists()
    }

    /** 删掉目录里所有**未登记**的更新文件([isUpdateFileName]),返回删掉的个数。目录不存在返回 0。 */
    fun sweep(): Int = synchronized(this) {
        dir.listFiles()?.count { it.isFile && isUpdateFileName(it.name) && it.name !in inUse && it.delete() } ?: 0
    }

    fun isInUse(file: File): Boolean = synchronized(this) { file.name in inUse }
}
