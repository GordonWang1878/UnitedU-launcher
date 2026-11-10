package com.uniteduone.launcher

import android.content.Context
import android.media.AudioManager
import android.os.Handler

/** `Handler.postDelayed` 与 [VolumeClock] 的 `uptimeMillis` 是同一条时间轴。 */
class HandlerVolumeScheduler(private val handler: Handler) : VolumeScheduler {
    private class Token(val runnable: Runnable) : VolumeTask

    override fun postDelayed(delayMs: Long, run: () -> Unit): VolumeTask {
        val runnable = Runnable { run() }
        val token = Token(runnable)
        handler.postDelayed(runnable, delayMs)
        return token
    }

    override fun cancel(task: VolumeTask) {
        val token = task as? Token ?: return
        handler.removeCallbacks(token.runnable)
    }
}

/**
 * 生产走 `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE / ADJUST_LOWER, FLAG_SHOW_UI)`。
 * 已经在最大或最小、或这一步没有改变读数,就返回 [VolumeStepResult.AT_BOUND],调用方不再继续点。
 */
class AudioVolumeStepper(private val audio: AudioManager?) : VolumeStepper {
    override fun step(direction: VolumeDirection): VolumeStepResult {
        val manager = audio ?: return VolumeStepResult.AT_BOUND
        return runCatching {
            val stream = AudioManager.STREAM_MUSIC
            val before = manager.getStreamVolume(stream)
            val raising = direction == VolumeDirection.UP
            val blocked = if (raising) before >= manager.getStreamMaxVolume(stream)
            else before <= manager.getStreamMinVolume(stream)
            if (blocked) return@runCatching VolumeStepResult.AT_BOUND
            manager.adjustStreamVolume(
                stream,
                if (raising) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
            if (manager.getStreamVolume(stream) == before) VolumeStepResult.AT_BOUND else VolumeStepResult.MOVED
        }.getOrDefault(VolumeStepResult.AT_BOUND)
    }
}

fun volumeKeyLogStore(context: Context): VolumeKeyLogStore = VolumeKeyLogStore(volumeKeyLogFile(context.filesDir))
