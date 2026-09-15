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

/**
 * 手机上传页的 HTTP 服务(spec §1)。只在「导入图片」页打开时活着;每请求一线程(NanoHTTPD 默认)。
 * 路由:GET / | GET /api/list | POST /api/upload | DELETE /api/file | GET /thumb | GET /file | POST /api/apk。
 */
class UploadServer(
    private val ctx: Context,
    port: Int,
    /** 每存下一个文件回调一次(**主线程**),给电视端页面计数。 */
    private val onSaved: (String) -> Unit,
    /** 有提示要给电视端页面显示时回调一次(**主线程**),参数是 R.string id(spec §4)。 */
    private val onNotice: (Int) -> Unit = {},
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
        return try {
            when {
                uri == "/" && session.method == Method.GET -> serveIndex()
                uri == "/api/list" && session.method == Method.GET -> serveList(type)
                uri == "/api/upload" && session.method == Method.POST -> serveUpload(session, type)
                uri == "/api/file" && session.method == Method.DELETE -> serveDelete(type, name)
                uri == "/thumb" && session.method == Method.GET -> serveThumb(type, name)
                uri == "/file" && session.method == Method.GET -> serveFile(type, name)
                uri == "/api/apk" && session.method == Method.POST -> serveApk(session)
                else -> text(Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "上传服务请求失败 $uri: ${e.message}")
            json(Response.Status.INTERNAL_ERROR, jsonFail("server"))
        }
    }

    /** 网页。把 index.html 里的 __STRINGS__ 占位替换成按电视当前语言取的三语 JSON。 */
    private fun serveIndex(): Response {
        val html = runCatching {
            ctx.assets.open("web/index.html").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return text(Response.Status.NOT_FOUND, "index missing")
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html.replace("__STRINGS__", webStringsJson(ctx)))
    }

    private fun listImages(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && extensionOf(it.name) in UPLOAD_IMAGE_EXTS }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun serveList(type: String?): Response {
        val dir = libraryFor(type) ?: return json(Response.Status.BAD_REQUEST, jsonFail("type"))
        val entries = listImages(dir).map { FileEntry(it.name, it.length(), it.lastModified()) }
        return json(Response.Status.OK, jsonFileList(type!!, entries))
    }

    /**
     * multipart 多文件上传,字段名 `files`。**实测**(2026-09-15 模拟器,非任务文档假设的
     * files/files2/files3):NanoHTTPD 把同名多个文件的临时路径放在
     * files["files"]（第 1 个)、files["files1"]（第 2 个)、files["files2"]（第 3 个)…
     * ——后缀是「第几个额外文件」的 0 起序号,不是「第几个文件」的 1 起序号;
     * 且原始文件名**不是**累加进 `parameters["files"]` 一个 list,而是每个后缀 key 各自
     * 只装一个元素(`parameters["files1"] = [name]`、`parameters["files2"] = [name]`…)。
     * 所以按 key 探测(而不是按 parameters["files"] 的长度)才能拿全同批全部文件。
     * **探测按「实际存在的 key」枚举,不按连续序号硬猜**:某个 part 没带逐段 Content-Type 时
     * NanoHTTPD 的编号可能跳号(如 files、files2 之间缺 files1),按固定步长探测撞上空位就
     * 会把后面全部截断;改成先收集 files 映射与 parameters 里全部形如 files/files<数字> 的 key、
     * 按数字排序再逐个处理,断一个不连累同批其余文件。
     * 每个文件独立判定:名字清洗 → 扩展名 → 大小 → 可解码 → 重名 → 移入;失败进 rejected,不影响同批其他文件。
     */
    private fun serveUpload(session: IHTTPSession, type: String?): Response {
        val dir = libraryFor(type) ?: return json(Response.Status.BAD_REQUEST, jsonFail("type"))
        val files = HashMap<String, String>()
        session.parseBody(files)
        val saved = ArrayList<String>()
        val rejected = ArrayList<Pair<String, String>>()
        val keys = (files.keys + session.parameters.keys)
            .filter { it == "files" || (it.startsWith("files") && it.length > 5 && it.substring(5).all(Char::isDigit)) }
            .distinct()
            // 手工构造的字段名(如 files99999999999999)可能超出 Int 范围;toInt() 会抛异常把整个
            // 请求 500——排到最后即可,不必让这种边角输入拖垮同批其它正常文件。
            .sortedBy { if (it == "files") 0 else it.substring(5).toIntOrNull() ?: Int.MAX_VALUE }
        for (key in keys) {
            val tmpPath = files[key] ?: continue
            val original = session.parameters[key]?.firstOrNull() ?: continue
            val tmp = File(tmpPath)
            val clean = sanitizeUploadName(original)
            when {
                clean == null -> rejected += original to "name"
                extensionOf(clean) !in UPLOAD_IMAGE_EXTS -> rejected += original to "type"
                tmp.length() > MAX_UPLOAD_BYTES -> rejected += original to "size"
                !Apps.isDecodableImage(tmp.absolutePath) -> rejected += original to "decode"
                else -> {
                    val finalName = uniqueName(dir.list()?.toSet() ?: emptySet(), clean)
                    if (moveInto(tmp, File(dir, finalName))) {
                        saved += finalName
                        main.post { onSaved(finalName) }
                    } else rejected += original to "write"
                }
            }
            tmp.delete()
        }
        return json(Response.Status.OK, jsonUploadResult(saved, rejected))
    }

    /**
     * 传 APK:存到 cacheDir/apk/upload.apk(FileProvider 只开放这个目录)→ 校验是 APK →
     * 主线程调 [ApkInstaller.install](startActivity 不能在请求线程)→ 把结果告诉手机。
     * STARTED 路径的 [onNotice] 在 `startActivity` **之前**、同一个主线程回合里调用——先撑开
     * `suppressStopUntil` 窗口再放系统安装器出场,不然安装器自己的 ON_STOP 可能抢在窗口插上之前
     * 就把页面拆了(T4 复审发现,同一个 looper 上两件事没有 happens-before)。NEEDS_PERMISSION
     * 分支事后再回调一次,把提示改写成更准确的那句。
     */
    private fun serveApk(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val tmpPath = files["apk"] ?: return json(Response.Status.BAD_REQUEST, jsonFail("invalid"))
        val tmp = File(tmpPath)
        if (tmp.length() > MAX_APK_BYTES) { tmp.delete(); return json(Response.Status.OK, jsonFail("size")) }
        val dst = File(File(ctx.cacheDir, "apk").also { it.mkdirs() }, "upload.apk")
        if (!moveInto(tmp, dst)) { dst.delete(); return json(Response.Status.OK, jsonFail("write")) }
        val info = ApkInstaller.archiveInfo(ctx, dst) ?: run { dst.delete(); return json(Response.Status.OK, jsonFail("invalid")) }
        val task = java.util.concurrent.FutureTask {
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
            val bmp = Apps.decodeScaled(f.absolutePath, 320, 180) ?: return text(Response.Status.NOT_FOUND, "undecodable")
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            out.toByteArray().also { synchronized(thumbs) { thumbs[key] = it } }
        }
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun serveFile(type: String?, name: String?): Response {
        val f = resolve(type, name) ?: return text(Response.Status.NOT_FOUND, "missing")
        val mime = when (extensionOf(f.name)) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
        return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), f.length())
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
        fun startOnFreePort(ctx: Context, onSaved: (String) -> Unit, onNotice: (Int) -> Unit = {}): UploadServer? {
            for (port in UPLOAD_PORT_FIRST..UPLOAD_PORT_LAST) {
                val s = UploadServer(ctx, port, onSaved, onNotice)
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
        "uploading" to R.string.web_uploading,
        "done" to R.string.web_done,
        "error" to R.string.web_error,
        "rejected_type" to R.string.web_rejected_type,
        "rejected_size" to R.string.web_rejected_size,
        "rejected_decode" to R.string.web_rejected_decode,
        "rejected_name" to R.string.web_rejected_name,
        "rejected_write" to R.string.web_rejected_write,
        "apk_hint" to R.string.web_apk_hint,
        "apk_install" to R.string.web_apk_install,
        "apk_needs-permission" to R.string.web_apk_needs_permission,
        "apk_invalid" to R.string.web_apk_invalid,
        "apk_size" to R.string.web_apk_size,
        "apk_server" to R.string.web_error,
        "apk_write" to R.string.web_rejected_write,
    )
    return keys.entries.joinToString(",", "{", "}") { (k, res) -> "${jsonStr(k)}:${jsonStr(ctx.getString(res))}" }
}
