package com.uniteduone.launcher

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "UnitedU"

/** [UploadServer] 里 acceptOne 的「通过」前缀(后面跟落盘名);别的返回值都是拒绝理由。 */
private const val SAVED = "saved:"

/**
 * 手机上传页的 HTTP 服务(spec §1)。只在「导入图片」页打开时活着;每请求一线程(NanoHTTPD 默认)。
 * 路由:GET / | GET /api/list | POST /api/upload | DELETE /api/file | GET /thumb | GET /file | POST /api/apk | POST /api/upload-raw(R103)。
 */
class UploadServer(
    private val ctx: Context,
    port: Int,
    /** 每存下一个文件回调一次(**主线程**),参数是(分类, 落盘文件名),给电视端页面计数、给图片网格定落点(R63)。 */
    private val onSaved: (type: String, name: String) -> Unit,
    /** 有提示要给电视端页面显示时回调一次(**主线程**),参数是 R.string id(spec §4)。 */
    private val onNotice: (Int) -> Unit = {},
    /** 导入页是否还在前台(**只在主线程问**)。false 时不许弹系统安装器,见 [serveApk]。 */
    private val isForeground: () -> Boolean = { true },
    /** 手机网页默认打开的分页(R63,见 [defaultTabJs]);null = 网页自己的默认。 */
    private val defaultTab: String? = null,
) : NanoHTTPD(port) {

    private val main = Handler(Looper.getMainLooper())
    /** multipart 临时文件放外置 cache(与 library 同一卷,rename 才是原子移动);没挂用内置 cache。 */
    private val tmpDir = File(ctx.externalCacheDir ?: ctx.cacheDir, "upload").also { it.mkdirs() }
    /** 缩略图内存缓存:name|mtime → JPEG bytes,最多 200 项(插入序淘汰)。 */
    private val thumbs = object : LinkedHashMap<String, ByteArray>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 200
    }

    init {
        // NanoHTTPD 默认把上传临时文件放 java.io.tmpdir,Android 上那不可写;改放我们的 cache 子目录。
        setTempFileManagerFactory { CacheTempFileManager(tmpDir) }
        sweepStale()
    }

    /**
     * 开服前扫一遍上一条命留下的垃圾:进程被杀在安装流程中间时(系统安装器在前台,我们在后台被回收),
     * `cacheDir/apk/upload-*.apk`(旧版是固定的 upload.apk)与 multipart 临时文件都没人删,几十上百 MB 就那么占着。
     * 临时文件按 60 s 老化判定:本轮正在传的文件由 `TempFileManager.clear()` 自己收,
     * 不能被同一进程里后开的服务误删——不过这两件事本就不会同时发生(服务寿命 = 导入页寿命)。
     */
    private fun sweepStale() = runCatching {
        // 手机传来的 APK:未登记的(上一条命留下的、上一版固定名的 upload.apk)删;本进程交给过安装器的仍登记着,不碰
        uploadApks(ctx).sweep()
        val cutoff = System.currentTimeMillis() - 60_000
        tmpDir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        // 跨卷回落复制途中被杀、留在图库里的临时文件(见 saveIntoLibrary);60 s 内的可能正在写,不碰
        if (Paths.baseOrNull(ctx) != null) for (type in LIBRARY_TYPES) {
            libraryFor(type)?.listFiles()?.forEach {
                if (it.isFile && isStaleLibraryTemp(it.name) && it.lastModified() < cutoff) it.delete()
            }
        }
    }

    private fun libraryFor(type: String?): File? = when (type) {
        "wallpapers" -> Paths.wallpaperLibrary(ctx)
        "cards" -> Paths.cardLibrary(ctx)
        "screensavers" -> Paths.screensaverLibrary(ctx)
        else -> null
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val p = session.parameters
        val type = p["type"]?.firstOrNull()
        val name = p["name"]?.firstOrNull()
        // CSRF:`fetch` + `FormData` 属于 CORS 简单请求——手机浏览器里**任何**网页都能直接往这台
        // 电视 POST/DELETE(没有预检、也不需要读回响应就已经把事做了)。自定义头把它顶成非简单请求:
        // 跨源发它会先触发预检,我们不回 CORS 头,浏览器就把请求拦在发出之前。只管非 GET——
        // GET 那几条路由只读、且本来就要能被 <img> 直接加载。(NanoHTTPD 把请求头 key 全小写)
        // 挡下来时 body 还没读,所以同样要 `Connection: close`,理由见 [closing]。
        if (session.method != Method.GET && session.headers["x-requested-with"] != "UnitedU") {
            return closing(json(Response.Status.FORBIDDEN, jsonFail("origin")))
        }
        // Content-Length 预检:超限的请求不必先把几百 MB 收进 cache 再判——直接 413 + 关连接。
        // 声明值含 multipart 边界,比文件本身略大;卡在这里的一定也过不了后面逐文件那道闸。
        contentLengthOverLimit(session, uri)?.let { return it }
        return try {
            when {
                uri == "/" && session.method == Method.GET -> serveIndex()
                uri == "/api/list" && session.method == Method.GET -> serveList(type)
                uri == "/api/upload" && session.method == Method.POST -> serveUpload(session, type)
                uri == "/api/upload-raw" && session.method == Method.POST -> serveRawUpload(session, type, name)
                uri == "/api/file" && session.method == Method.DELETE -> serveDelete(type, name)
                uri == "/thumb" && session.method == Method.GET -> serveThumb(type, name)
                uri == "/file" && session.method == Method.GET -> serveFile(session, type, name)
                uri == "/api/apk" && session.method == Method.POST -> serveApk(session)
                else -> text(Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "上传服务请求失败 $uri: ${e.message}")
            json(Response.Status.INTERNAL_ERROR, jsonFail("server"))
        }
    }

    /**
     * **没读 body 就回的响应一律经这里**:body 还躺在 socket 里,这条连接若被复用,剩下的字节会被
     * 当成下一个请求的请求行。`Connection: close` 让 NanoHTTPD 发完就关(它按响应头的
     * `connection` 判,见 `Response.isCloseConnection`),连接复用这条路就不存在了。
     */
    private fun closing(resp: Response): Response = resp.apply { addHeader("Connection", "close") }

    /**
     * POST 路由的 Content-Length 上限:`/api/apk` 一个 APK,`/api/upload` 最多一批 8 张图。
     * 超了直接 413,不读 body(不然几百 MB 要先落进 cache 才判得出来)。
     * 声明值含 multipart 边界、比文件本身略大;卡在这里的一定也过不了后面逐文件那道闸。
     * 没超 / 不是这两条路由 → null(照常走)。
     */
    private fun contentLengthOverLimit(session: IHTTPSession, uri: String): Response? {
        if (session.method != Method.POST) return null
        val cap = when (uri) {
            "/api/apk" -> MAX_APK_BYTES
            "/api/upload" -> MAX_UPLOAD_BYTES * 8
            // 原始字节上传一次一个文件,没有 multipart 边界(R103)
            "/api/upload-raw" -> ScreensaverMedia.MAX_VIDEO_BYTES
            else -> return null
        }
        val declared = session.headers["content-length"]?.toLongOrNull() ?: return null
        if (declared <= cap) return null
        // 原始上传的理由按文件种类细分(网页的文案不同);multipart 那条照旧
        val rawName = session.parameters["name"]?.firstOrNull() ?: ""
        val reason = if (uri == "/api/upload-raw" && ScreensaverMedia.isVideo(rawName)) "video_size" else "size"
        return closing(json(Response.Status.PAYLOAD_TOO_LARGE, jsonFail(reason)))
    }

    /** 网页。把 index.html 里的 __STRINGS__ 占位替换成按电视当前语言取的三语 JSON,__DEFAULT_TAB__ 换成默认分页(R63)。 */
    private fun serveIndex(): Response {
        val html = runCatching {
            ctx.assets.open("web/index.html").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return text(Response.Status.NOT_FOUND, "index missing")
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html.replace("__STRINGS__", webStringsJson(ctx)).replace("__DEFAULT_TAB__", defaultTabJs(defaultTab)))
    }

    /** 屏保分类照片 + 视频(R100),壁纸 / 卡片图只有照片。 */
    private fun listMedia(type: String, dir: File): List<File> {
        val exts = if (type == "screensavers") ScreensaverMedia.ALL_EXTS else UPLOAD_IMAGE_EXTS
        return dir.listFiles()
            ?.filter { it.isFile && extensionOf(it.name) in exts }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun serveList(type: String?): Response {
        val dir = libraryFor(type) ?: return json(Response.Status.BAD_REQUEST, jsonFail("type"))
        val entries = listMedia(type!!, dir).map { FileEntry(it.name, it.length(), it.lastModified()) }
        return json(Response.Status.OK, jsonFileList(type, entries))
    }

    /**
     * multipart 多文件上传,字段名 `files`。**实测**(2026-09-15 模拟器,非任务文档假设的
     * files/files2/files3):NanoHTTPD 把同名多个文件的临时路径放在
     * files["files"]（第 1 个)、files["files1"]（第 2 个)、files["files2"]（第 3 个)…
     * ——后缀是「第几个额外文件」的 0 起序号,不是「第几个文件」的 1 起序号;
     * 且原始文件名**不是**累加进 `parameters["files"]` 一个 list,而是每个后缀 key 各自
     * 只装一个元素(`parameters["files1"] = [name]`、`parameters["files2"] = [name]`…)。
     * 所以按 key 探测(而不是按 parameters["files"] 的长度)才能拿全同批全部文件。
     * **探测按「实际存在的 key」枚举,不按连续序号硬猜**(枚举与排序是纯函数 [uploadKeys],单测在
     * `UploadPureTest`):某个 part 没带逐段 Content-Type 时 NanoHTTPD 的编号可能跳号
     * (如 files、files2 之间缺 files1),按固定步长探测撞上空位就会把后面全部截断。
     * 每个文件独立判定:名字清洗 → 扩展名 → 大小 → 可解码 → 重名 → 移入;失败进 rejected,不影响同批其他文件。
     */
    private fun serveUpload(session: IHTTPSession, type: String?): Response {
        val dir = libraryFor(type) ?: return json(Response.Status.BAD_REQUEST, jsonFail("type"))
        val files = HashMap<String, String>()
        forceUtf8Multipart(session)
        session.parseBody(files)
        val saved = ArrayList<String>()
        val rejected = ArrayList<Pair<String, String>>()
        for (key in uploadKeys(files.keys, session.parameters.keys)) {
            val tmpPath = files[key] ?: continue
            val original = session.parameters[key]?.firstOrNull() ?: continue
            val tmp = File(tmpPath)
            val result = acceptOne(type!!, dir, sanitizeUploadName(original), tmp)
            if (result.startsWith(SAVED)) saved += result.removePrefix(SAVED) else rejected += original to result
            tmp.delete()
        }
        return json(Response.Status.OK, jsonUploadResult(saved, rejected))
    }

    /**
     * 一个已落进临时文件的上传:校验 → 移进图库。multipart 与原始上传共用(R103)。
     * 闸的顺序:名字 → 扩展名(按分类)→ 大小 → 文件头([ScreensaverMedia.uploadRejection])→ 能解(照片
     * `isDecodableImage`、视频 [VideoThumbs.hasVideoTrack])→ [saveIntoLibrary]。
     * @return 通过时 `SAVED + 落盘名`,否则拒绝理由。
     */
    private fun acceptOne(type: String, dir: File, clean: String?, tmp: File): String {
        val head = runCatching {
            tmp.inputStream().use { input -> ByteArray(16).let { b -> b.copyOf(input.read(b).coerceAtLeast(0)) } }
        }.getOrDefault(ByteArray(0))
        ScreensaverMedia.uploadRejection(type, clean, tmp.length(), head)?.let { return it }
        val name = clean!!
        val video = ScreensaverMedia.isVideo(name)
        if (video && !VideoThumbs.hasVideoTrack(tmp.absolutePath)) return "video_decode"
        if (!video && !Apps.isDecodableImage(tmp.absolutePath)) return "decode"
        val finalName = saveIntoLibrary(tmp, dir, name) ?: return "write"
        main.post { onSaved(type, finalName) }
        return SAVED + finalName
    }

    /**
     * 原始字节上传(Ruling R103):`POST /api/upload-raw?type=&name=`,body 就是文件本身,一次一个。
     * 视频可达 500 MB:NanoHTTPD 的 multipart 解析先把**整个请求**落进一个临时文件,再 `mmap` 整个文件去找边界
     * ——32 位进程映射几百 MB 的连续地址很可能失败,而且同一份数据在盘上要占两份。这里直接从 socket 按
     * Content-Length 流式写进 cache 里的临时文件(与图库同卷,rename 原子),不进内存、不 mmap。
     * 名字 / 类型 / 大小不合格的在读 body **之前**就拒(`Connection: close`,见 [closing]);空间不够同理。
     */
    private fun serveRawUpload(session: IHTTPSession, type: String?, name: String?): Response {
        val dir = libraryFor(type) ?: return closing(json(Response.Status.BAD_REQUEST, jsonFail("type")))
        val original = name ?: ""
        val clean = sanitizeUploadName(name)
        val declared = session.headers["content-length"]?.toLongOrNull()
            ?: return closing(json(Response.Status.LENGTH_REQUIRED, jsonFail("size")))
        ScreensaverMedia.uploadRejection(type!!, clean, declared, null)?.let {
            return closing(json(Response.Status.OK, jsonUploadResult(emptyList(), listOf(original to it))))
        }
        // 留 64 MB 余量:写满存储会连带别的应用出问题
        if (tmpDir.usableSpace < declared + 64L * 1024 * 1024) {
            return closing(json(Response.Status.OK, jsonUploadResult(emptyList(), listOf(original to "space"))))
        }
        val tmp = File.createTempFile("raw", ".part", tmpDir)
        try {
            var left = declared
            val buf = ByteArray(64 * 1024)
            tmp.outputStream().use { out ->
                val input = session.inputStream
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                out.flush()
                out.fd.sync()
            }
            if (left > 0) {
                // 手机中途断了:body 没收全,连接也不能再用
                return closing(json(Response.Status.BAD_REQUEST, jsonUploadResult(emptyList(), listOf(original to "write"))))
            }
            val result = acceptOne(type, dir, clean, tmp)
            return if (result.startsWith(SAVED)) json(Response.Status.OK, jsonUploadResult(listOf(result.removePrefix(SAVED)), emptyList()))
            else json(Response.Status.OK, jsonUploadResult(emptyList(), listOf(original to result)))
        } finally {
            tmp.delete()
        }
    }

    /**
     * 传 APK:存到 cacheDir/apk/upload-<唯一名>.apk(FileProvider 只开放这个目录;每次上传独占一个文件,见 [uploadApks])→ 校验是 APK →
     * 主线程调 [ApkInstaller.install](startActivity 不能在请求线程)→ 把结果告诉手机。
     * STARTED 路径的 [onNotice] 在 `startActivity` **之前**、同一个主线程回合里调用——先撑开
     * `suppressStopUntil` 窗口再放系统安装器出场,不然安装器自己的 ON_STOP 可能抢在窗口插上之前
     * 就把页面拆了(T4 复审发现,同一个 looper 上两件事没有 happens-before)。NEEDS_PERMISSION
     * 分支事后再回调一次,把提示改写成更准确的那句。
     *
     * **只在导入页还在前台时才装**(spec §4):否则局域网上任何人都能每 30 s 续一次豁免窗、
     * 在用户已经切去看视频的时候把安装弹窗糊到屏幕上——而 ON_STOP 关页这道保险正好被那个窗压着。
     * 前台判定与 `isAlive` 一起放在**主线程回合里**问:请求线程上问到的答案可能已经过期,而
     * `stop()` 与 ON_STOP 都发生在主线程,同一个回合里问到的「前台」与随后的 `startActivity`
     * 之间没有别的机会插进来。`isAlive` 兜的是另一头:body 刚解析完、`stop()` 已经把服务停了。
     */
    /**
     * 浏览器发 multipart 时 Content-Type 只有 boundary、不带 charset,NanoHTTPD 2.3.1 于是按 US-ASCII
     * 解每个 part 的头,文件名里每个非 ASCII 字节都变成 U+FFFD——2026-09-16 A95L 真机实测:手机传中文名
     * 图片,落盘与列表都成「���.jpg」。补救:在 [IHTTPSession.parseBody] 之前把 `charset=UTF-8` 补进
     * 请求头([IHTTPSession.getHeaders] 返回的就是会话内部那张 map),NanoHTTPD 建 ContentType 时读到它。
     * 判定与拼接是纯函数 [utf8MultipartContentType](单测在 `UploadPureTest`)。
     */
    private fun forceUtf8Multipart(session: IHTTPSession) {
        val fixed = utf8MultipartContentType(session.headers["content-type"]) ?: return
        (session.headers as? MutableMap<String, String>)?.put("content-type", fixed)
    }

    private fun serveApk(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        forceUtf8Multipart(session)
        session.parseBody(files)
        val tmpPath = files["apk"] ?: return json(Response.Status.BAD_REQUEST, jsonFail("invalid"))
        val tmp = File(tmpPath)
        if (tmp.length() > MAX_APK_BYTES) { tmp.delete(); return json(Response.Status.OK, jsonFail("size")) }
        // 每次上传一个独占文件(2026-09-30 Codex 评审 P2):原来都写固定的 upload.apk,两台手机同时传时,
        // 后一个请求会在前一个解析完、安装器还没读之前把文件换掉,失败分支还会删掉别人的文件。
        // 交给安装器的文件本进程内不注销(安装器经 FileProvider 异步读,读完时刻不可知),下次开服务的 sweep 清。
        val apks = uploadApks(ctx)
        val dst = apks.reserve(".apk").also { it.parentFile?.mkdirs() }
        if (!moveInto(tmp, dst)) { apks.release(dst); return json(Response.Status.OK, jsonFail("write")) }
        val info = ApkInstaller.archiveInfo(ctx, dst) ?: run { apks.release(dst); return json(Response.Status.OK, jsonFail("invalid")) }
        val task = java.util.concurrent.FutureTask {
            if (!isAlive || !isForeground()) return@FutureTask ApkInstaller.Result.BACKGROUND
            onNotice(R.string.import_apk_started)
            ApkInstaller.install(ctx, dst)
        }
        main.post(task)
        return when (task.get()) {
            ApkInstaller.Result.STARTED ->
                json(Response.Status.OK, jsonOk("\"package\":${jsonStr(info.first)},\"version\":${jsonStr(info.second)}"))
            ApkInstaller.Result.NEEDS_PERMISSION -> {
                main.post { onNotice(R.string.import_apk_needs_permission) }
                json(Response.Status.OK, jsonFail("needs-permission"))
            }
            ApkInstaller.Result.INVALID -> json(Response.Status.OK, jsonFail("invalid"))
            // 没装,暂存文件立刻删掉:窗口没被续,页面照原到期时间关,这个文件也不该留到下次。
            ApkInstaller.Result.BACKGROUND -> { apks.release(dst); json(Response.Status.OK, jsonFail("background")) }
        }
    }

    private fun serveDelete(type: String?, name: String?): Response {
        val dir = libraryFor(type) ?: return json(Response.Status.BAD_REQUEST, jsonFail("type"))
        val clean = sanitizeUploadName(name) ?: return json(Response.Status.BAD_REQUEST, jsonFail("name"))
        val f = File(dir, clean)
        if (!f.isFile) return json(Response.Status.NOT_FOUND, jsonFail("missing"))
        return if (f.delete()) json(Response.Status.OK, jsonOk()) else json(Response.Status.INTERNAL_ERROR, jsonFail("delete"))
    }

    private fun resolve(type: String?, name: String?): File? {
        val dir = libraryFor(type) ?: return null
        val clean = sanitizeUploadName(name) ?: return null
        return File(dir, clean).takeIf { it.isFile }
    }

    private fun serveThumb(type: String?, name: String?): Response {
        val f = resolve(type, name) ?: return text(Response.Status.NOT_FOUND, "missing")
        val key = "${f.name}|${f.lastModified()}"
        val cached = synchronized(thumbs) { thumbs[key] }
        val bytes = cached ?: run {
            // 视频:首帧(R104,与电视端网格同一个取法与缓存)
            val bmp = (if (ScreensaverMedia.isVideo(f.name)) VideoThumbs.load(f).frame else Apps.decodeScaled(f.absolutePath, 320, 180))
                ?: return text(Response.Status.NOT_FOUND, "undecodable")
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            out.toByteArray().also { synchronized(thumbs) { thumbs[key] = it } }
        }
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    /**
     * 原文件。视频支持单段 Range(R103):手机浏览器(尤其 iOS Safari)对不支持 Range 的服务端拒绝播放 `<video>`。
     * 解析是纯函数 [ScreensaverMedia.parseRange];不认的 Range 头当没带、回整个文件。
     */
    private fun serveFile(session: IHTTPSession, type: String?, name: String?): Response {
        val f = resolve(type, name) ?: return text(Response.Status.NOT_FOUND, "missing")
        val mime = when (extensionOf(f.name)) {
            "png" -> "image/png"; "webp" -> "image/webp"
            "mp4", "m4v" -> "video/mp4"; "mov" -> "video/quicktime"; "webm" -> "video/webm"
            else -> "image/jpeg"
        }
        val length = f.length()
        val range = if (ScreensaverMedia.isVideo(f.name)) ScreensaverMedia.parseRange(session.headers["range"], length) else null
        if (range == null) {
            return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), length)
                .apply { addHeader("Accept-Ranges", "bytes") }
        }
        val input = FileInputStream(f)
        var skip = range.first
        while (skip > 0) {
            val n = input.skip(skip)
            if (n <= 0) break
            skip -= n
        }
        val count = range.last - range.first + 1
        return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, input, count).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", "bytes ${range.first}-${range.last}/$length")
        }
    }

    /** 同卷 rename;跨卷(外置 cache 没挂时)回落到复制 + fsync + 删源。 */
    private fun moveInto(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true
        return runCatching {
            src.inputStream().use { input -> dst.outputStream().use { out -> input.copyTo(out); out.flush(); out.fd.sync() } }
            src.delete()
            true
        }.getOrDefault(false)
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun text(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    companion object {
        /** 电视的局域网 IPv4:已 up、非回环接口里按 [pickAddress] 的偏好挑;没有 → null。 */
        fun localAddress(): String? {
            val candidates = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { ni ->
                        ni.inetAddresses.toList()
                            .filter { it is Inet4Address && !it.isLoopbackAddress }
                            .map { ni.name to it.hostAddress.orEmpty() }
                    }
                    .filter { it.second.isNotEmpty() }
            }.getOrDefault(emptyList())
            return pickAddress(candidates)
        }

        /**
         * 从 8090 起找一个能 bind 的端口起服务;8090–8099 全占返回 null。
         * bind 失败时 `start()` 抛异常,但 NanoHTTPD 在抛之前已经把底层 ServerSocket 建好
         * (只是没 bind 成功)——不 `stop()` 就换下一个端口重试,这个 fd 就漏在那里,
         * 10 个端口全占的最坏情况会漏 9 个。
         */
        fun startOnFreePort(
            ctx: Context,
            onSaved: (type: String, name: String) -> Unit,
            onNotice: (Int) -> Unit = {},
            isForeground: () -> Boolean = { true },
            defaultTab: String? = null,
        ): UploadServer? {
            for (port in UPLOAD_PORT_FIRST..UPLOAD_PORT_LAST) {
                val s = UploadServer(ctx, port, onSaved, onNotice, isForeground, defaultTab)
                val ok = runCatching { s.start(SOCKET_READ_TIMEOUT, false); true }
                    .onFailure { runCatching { s.stop() } }
                    .getOrDefault(false)
                if (ok) return s
            }
            return null
        }
    }
}

/** 把 NanoHTTPD 的上传临时文件放进指定目录(默认 java.io.tmpdir 在 Android 上不可写)。 */
private class CacheTempFileManager(private val dir: File) : NanoHTTPD.TempFileManager {
    private val files = ArrayList<NanoHTTPD.TempFile>()
    override fun createTempFile(filenameHint: String?): NanoHTTPD.TempFile =
        NanoHTTPD.DefaultTempFile(dir).also { files += it }
    override fun clear() {
        // 已被移进 library 的文件 delete 会抛(源不存在),吞掉即可
        files.forEach { runCatching { it.delete() } }
        files.clear()
    }
}

/** 网页文案:按电视 app 当前语言取 web_* 资源,拼成 JSON 对象注入 index.html 的 __STRINGS__。key 与网页 JS 里 S.xxx 一一对应。 */
fun webStringsJson(ctx: Context): String {
    val keys = mapOf(
        "title" to R.string.web_title,
        "tab_wallpapers" to R.string.web_tab_wallpapers,
        "tab_cards" to R.string.web_tab_cards,
        "tab_screensavers" to R.string.web_tab_screensavers,
        "tab_apk" to R.string.web_tab_apk,
        "upload" to R.string.web_upload,
        "delete" to R.string.web_delete,
        "confirm_delete" to R.string.web_confirm_delete,
        "empty" to R.string.web_empty,
        "done" to R.string.web_done,
        "error" to R.string.web_error,
        "rejected_type" to R.string.web_rejected_type,
        "rejected_size" to R.string.web_rejected_size,
        "rejected_decode" to R.string.web_rejected_decode,
        "rejected_name" to R.string.web_rejected_name,
        "rejected_write" to R.string.web_rejected_write,
        "rejected_type_media" to R.string.web_rejected_type_media,
        "rejected_video_size" to R.string.web_rejected_video_size,
        "rejected_video_decode" to R.string.web_rejected_video_decode,
        "rejected_space" to R.string.web_rejected_space,
        "screensavers_hint" to R.string.web_screensavers_hint,
        "empty_screensavers" to R.string.web_empty_screensavers,
        "apk_hint" to R.string.web_apk_hint,
        "apk_install" to R.string.web_apk_install,
        "apk_needs-permission" to R.string.web_apk_needs_permission,
        "apk_invalid" to R.string.web_apk_invalid,
        "apk_size" to R.string.web_apk_size,
        "apk_background" to R.string.web_apk_background,
        "apk_server" to R.string.web_error,
        "apk_write" to R.string.web_rejected_write,
    )
    // 复数文案(P5):网页端没有 Android 的复数规则,把 one / other 两种形态原样(保留 %d)注入,JS 按件数挑。
    // 不带格式参数的 getQuantityString 返回未格式化的原文;中文两种形态同文。
    val plurals = mapOf(
        "uploading_one" to ctx.resources.getQuantityString(R.plurals.web_uploading, 1),
        "uploading_other" to ctx.resources.getQuantityString(R.plurals.web_uploading, 2),
    )
    return (keys.mapValues { ctx.getString(it.value) } + plurals).entries
        .joinToString(",", "{", "}") { (k, v) -> "${jsonStr(k)}:${jsonStr(v)}" }
}

/** 手机传来的 APK 的文件名:每次上传 `upload-<唯一名>.apk`,以及旧版固定名 `upload.apk`(清扫时一并清掉)。 */
fun isUploadApkName(name: String): Boolean =
    name == "upload.apk" || (name.startsWith("upload-") && name.endsWith(".apk"))

@Volatile private var uploadApkRegistry: UpdateFiles? = null

/**
 * `cacheDir/apk/` 里手机传 APK 的**进程内唯一**登记簿(与检查更新的 [Update.files] 同一个类、同一个目录,
 * 前缀不同、清扫互不越界)。进程内唯一的理由同 [Update.files]:导入页可能在两个 MainActivity 实例里各开一次。
 */
fun uploadApks(ctx: Context): UpdateFiles = uploadApkRegistry ?: synchronized(UploadServer::class.java) {
    uploadApkRegistry ?: UpdateFiles(File(ctx.applicationContext.cacheDir, "apk"), "upload-", ::isUploadApkName)
        .also { uploadApkRegistry = it }
}
