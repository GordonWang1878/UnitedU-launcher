package com.uniteduone.launcher

import kotlin.math.max
import kotlin.math.min

/** 屏保图库里一项是照片还是视频(Ruling R100)。 */
enum class MediaKind { PHOTO, VIDEO }

/**
 * 屏保图库「照片 + 短视频」的全部规则(Ruling R100–R104,2026-09-27)。**不碰 Android 类**,纯 JVM 单测
 * (`ScreensaverMediaTest`);Android 侧在 [VideoPlayback](播放)、[VideoThumbs](缩略图)、[UploadServer](上传)。
 */
object ScreensaverMedia {
    /** 照片扩展名(小写)= 原来的 `IMAGE_EXTS`,壁纸 / 卡片图也是这一套。 */
    val PHOTO_EXTS = setOf("jpg", "jpeg", "png", "webp")

    /**
     * 视频扩展名(R100)。mp4 / m4v / mov 都是 ISO BMFF 容器(系统 `MPEG4Extractor` 一个解析器),mov 是 iPhone
     * 拍的原片,不收的话手机上传最常见的那一种会被拒;webm 走 `MatroskaExtractor`。编码(H.264 / HEVC / VP9 / AV1)
     * 不看扩展名,由上传时 [android.media.MediaMetadataRetriever] 有没有视频轨、播放时解码器认不认来判。
     */
    val VIDEO_EXTS = setOf("mp4", "m4v", "mov", "webm")

    /** 屏保图库收的全部扩展名。 */
    val ALL_EXTS = PHOTO_EXTS + VIDEO_EXTS

    /** 一个视频在屏保里最多播多久(R101):超过 60 s 只播前 60 s,然后照常过渡到下一项。 */
    const val VIDEO_MAX_PLAY_MS = 60_000L

    /** 视频单文件上传上限(R103)。照片仍是 [MAX_UPLOAD_BYTES](30 MB)。 */
    const val MAX_VIDEO_BYTES = 500L * 1024 * 1024

    /** 屏保 / 预览里从开始准备到首帧出来最多等多久;超时当解码失败跳过(R101)。 */
    const val FIRST_FRAME_TIMEOUT_MS = 8_000L

    /**
     * 计时器轮到一个视频后等画面那一侧报「开始播了」最多等多久(R101)。大于 [FIRST_FRAME_TIMEOUT_MS]:画面那侧
     * 自己超时会报失败,这里只兜「根本没有画面在渲染」(桌面退到后台、系统屏保还没接上)——那时不能永远停在这一项。
     */
    const val START_WAIT_MS = 20_000L

    /** 报了「开始」之后,在预期播完时刻之外再多等多久没收到「播完」就自己往下走(播放器卡住 / 画面那侧被拆)。 */
    const val END_GRACE_MS = 5_000L

    fun kindOf(name: String): MediaKind? = when (extensionOf(name)) {
        in PHOTO_EXTS -> MediaKind.PHOTO
        in VIDEO_EXTS -> MediaKind.VIDEO
        else -> null
    }

    fun isVideo(name: String): Boolean = kindOf(name) == MediaKind.VIDEO

    /** 这个视频在屏保里实际播多久:时长与 [VIDEO_MAX_PLAY_MS] 取小;时长读不到(≤ 0)按上限算。 */
    fun playMs(durationMs: Long): Long = if (durationMs <= 0) VIDEO_MAX_PLAY_MS else min(durationMs, VIDEO_MAX_PLAY_MS)

    /** 报了「开始」之后最多等多久(见 [END_GRACE_MS])。 */
    fun endWaitMs(durationMs: Long): Long = playMs(durationMs) + END_GRACE_MS

    /**
     * 按文件头认容器(R103,上传校验的一道):ISO BMFF(第 4–7 字节是 `ftyp`,mp4 / m4v / mov 都是)→ "isobmff";
     * EBML 魔数 `1A 45 DF A3`(webm / mkv)→ "ebml";别的 → null。老式 QuickTime 文件可能以 `moov` / `mdat` /
     * `wide` / `free` 原子开头而没有 `ftyp`,也认作 isobmff。
     */
    fun sniffContainer(head: ByteArray): String? {
        if (head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        ) return "ebml"
        if (head.size >= 8) {
            val atom = String(head, 4, 4, Charsets.ISO_8859_1)
            if (atom in setOf("ftyp", "moov", "mdat", "wide", "free", "skip")) return "isobmff"
        }
        return null
    }

    /** 扩展名与文件头对得上:mp4 / m4v / mov 要 isobmff,webm 要 ebml。改了扩展名的别的文件在这里被拒。 */
    fun containerMatches(ext: String, container: String?): Boolean = when (ext.lowercase()) {
        "mp4", "m4v", "mov" -> container == "isobmff"
        "webm" -> container == "ebml"
        else -> false
    }

    /**
     * 上传的前几道闸(R103;可解码与否在 Android 侧再判):按分类挑允许的扩展名与大小上限。
     * @return 拒绝理由(网页按 `rejected_<理由>` 取文案),通过 → null。
     * 屏保分类收照片 + 视频;壁纸 / 卡片图只收照片(视频放进去没人会播)。
     * [head] = 文件开头若干字节;null = 还没收到 body(原始上传读 body 之前的预检),跳过文件头那道。
     */
    fun uploadRejection(type: String, name: String?, size: Long, head: ByteArray?): String? {
        if (name == null) return "name"
        val ext = extensionOf(name)
        val allowed = if (type == "screensavers") ALL_EXTS else PHOTO_EXTS
        if (ext !in allowed) return "type"
        return if (ext in VIDEO_EXTS) {
            when {
                size > MAX_VIDEO_BYTES -> "video_size"
                head != null && !containerMatches(ext, sniffContainer(head)) -> "video_decode"
                else -> null
            }
        } else if (size > MAX_UPLOAD_BYTES) "size" else null
    }

    /** 角标上的时长:`0:08`、`1:05`、`1:02:03`;不足 1 s 的向上取整(0.4 s 的短片不显示成 0:00);读不到 → null。 */
    fun formatDuration(ms: Long): String? {
        if (ms <= 0) return null
        val total = (ms + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * 从第 [i] 项往后找下一项能播的(R101 跳过失败项):先看 i + 1、i + 2 …绕一圈,最后才轮到 i 自己;
     * 全都失败 → null(计时器原地等,不空转)。[size] ≤ 0 → null。
     */
    fun nextPlayable(i: Int, size: Int, failed: (Int) -> Boolean): Int? {
        if (size <= 0) return null
        val from = clampIndex(i, size)
        for (step in 1..size) {
            val j = (from + step) % size
            if (!failed(j)) return j
        }
        return null
    }

    /**
     * TextureView 默认把视频拉伸到整个视图;要「Crop 铺满、不变形」(与照片的 `ContentScale.Crop` 同一个样子)
     * 就在它之上再按 (sx, sy) 以中心缩放。视频或视图尺寸不知道 → (1, 1)。
     */
    fun cropScale(viewW: Int, viewH: Int, videoW: Int, videoH: Int): Pair<Float, Float> {
        if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) return 1f to 1f
        val s = max(viewW.toFloat() / videoW, viewH.toFloat() / videoH)
        return (videoW * s / viewW) to (videoH * s / viewH)
    }

    /**
     * HTTP Range(R103,手机浏览器播视频要能跳):只认单段 `bytes=a-b` / `bytes=a-` / `bytes=-n`;
     * 越界、多段、格式不对 → null(调用方回整个文件)。结果夹在 [0, length − 1]。
     */
    fun parseRange(header: String?, length: Long): LongRange? {
        if (header == null || length <= 0) return null
        val spec = header.trim()
        if (!spec.startsWith("bytes=")) return null
        val body = spec.removePrefix("bytes=").trim()
        if (body.contains(',')) return null
        val dash = body.indexOf('-')
        if (dash < 0) return null
        val a = body.substring(0, dash).trim()
        val b = body.substring(dash + 1).trim()
        return when {
            a.isEmpty() -> {
                val n = b.toLongOrNull() ?: return null
                if (n <= 0) return null
                max(0L, length - n)..(length - 1)
            }
            else -> {
                val start = a.toLongOrNull() ?: return null
                if (start < 0 || start >= length) return null
                val end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return null)
                if (end < start) return null
                start..min(end, length - 1)
            }
        }
    }
}
