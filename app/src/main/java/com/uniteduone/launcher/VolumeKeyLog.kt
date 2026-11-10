package com.uniteduone.launcher

import java.io.File

/**
 * 音量键按下 / 松开的本机记录(issue #1)。
 *
 * 只有服务进程写、设置页读。环形缓冲,写在应用私有目录,不上传、不主动发到网上;
 * 用户要拿走时,从「关于」里自己分享。格式是纯文本,旧版本不认识这个文件就当它不存在。
 */

const val VOLUME_KEY_LOG_CAPACITY = 48
const val VOLUME_KEY_LOG_FILE = "volume-key.log"

data class VolumeKeyRecord(
    val timeMs: Long,
    val keyCode: Int,
    val action: String,
    val steps: Int,
    val autoStop: Boolean,
) {
    fun formatLine(): String = "$timeMs $keyCode $action $steps $autoStop"
}

fun volumeKeyLogFile(dir: File): File = File(dir, VOLUME_KEY_LOG_FILE)

fun formatVolumeKeyLog(records: List<VolumeKeyRecord>): String = buildString {
    append("# local-only volume key log; not uploaded\n")
    records.forEach { append(it.formatLine()).append('\n') }
}

fun parseVolumeKeyLog(text: String): List<VolumeKeyRecord> = text.lineSequence().mapNotNull { raw ->
    val line = raw.trim()
    if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
    val parts = line.split(' ')
    if (parts.size != 5) return@mapNotNull null
    val time = parts[0].toLongOrNull() ?: return@mapNotNull null
    val key = parts[1].toIntOrNull() ?: return@mapNotNull null
    val action = parts[2]
    if (action != VOLUME_LOG_DOWN && action != VOLUME_LOG_UP && action != VOLUME_LOG_AUTOSTOP) return@mapNotNull null
    val steps = parts[3].toIntOrNull() ?: return@mapNotNull null
    val autoStop = when (parts[4]) {
        "true" -> true
        "false" -> false
        else -> return@mapNotNull null
    }
    VolumeKeyRecord(time, key, action, steps, autoStop)
}.toList()

/** 屏幕上放不下整份时,只留最后 [maxLines] 条非注释行。分享仍用全文。 */
fun volumeLogTail(text: String, maxLines: Int = 8): String =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()
        .takeLast(maxLines)
        .joinToString("\n")

/**
 * 单写者的环形日志。服务进程追加,桌面进程只读;原子替换,读到的要么是旧的整份、要么是新的整份。
 */
class VolumeKeyLogStore(
    private val file: File,
    private val capacity: Int = VOLUME_KEY_LOG_CAPACITY,
) {
    @Synchronized
    fun append(record: VolumeKeyRecord): Boolean {
        val kept = (read() + record).let { if (it.size > capacity) it.takeLast(capacity) else it }
        return writeFileAtomically(file) { out -> out.write(formatVolumeKeyLog(kept).toByteArray(Charsets.UTF_8)) }
    }

    @Synchronized
    fun read(): List<VolumeKeyRecord> =
        runCatching { parseVolumeKeyLog(file.readText()) }.getOrDefault(emptyList())

    fun exportText(): String = formatVolumeKeyLog(read())
}
