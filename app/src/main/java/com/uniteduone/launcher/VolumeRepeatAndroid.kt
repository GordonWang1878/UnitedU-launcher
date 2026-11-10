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
 * 生产走 `adjustSuggestedStreamVolume(direction, USE_DEFAULT_STREAM_TYPE, FLAG_SHOW_UI)`:
 * 由系统选当前路由的那一路(本机喇叭、CEC / ARC、回音壁),不在调用前读音量、也不因为读数已经到顶就跳过调节。
 * 固定音量设备([AudioManager.isVolumeFixed],音量在外部设备上)本地读数不能当边界,返回 [VolumeStepResult.MOVED]。
 * 只有非固定音量、且调节之后能安全读到 `STREAM_MUSIC` 已经顶到这一侧时,才返回 [VolumeStepResult.AT_BOUND]。
 * 读不到、调用失败,都返回 [VolumeStepResult.MOVED],免得把外部设备误判成已经到顶而停住。
 */
class AudioVolumeStepper(private val audio: AudioManager?) : VolumeStepper {
    override fun step(direction: VolumeDirection): VolumeStepResult {
        val manager = audio ?: return VolumeStepResult.MOVED
        val raising = direction == VolumeDirection.UP
        val sent = runCatching {
            manager.adjustSuggestedStreamVolume(
                if (raising) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                AudioManager.FLAG_SHOW_UI,
            )
        }.isSuccess
        if (!sent) return VolumeStepResult.MOVED
        if (runCatching { manager.isVolumeFixed }.getOrDefault(true)) return VolumeStepResult.MOVED
        return runCatching {
            val stream = AudioManager.STREAM_MUSIC
            val volume = manager.getStreamVolume(stream)
            val atRail = if (raising) volume >= manager.getStreamMaxVolume(stream)
            else volume <= manager.getStreamMinVolume(stream)
            if (atRail) VolumeStepResult.AT_BOUND else VolumeStepResult.MOVED
        }.getOrDefault(VolumeStepResult.MOVED)
    }
}

fun volumeKeyLogStore(context: Context): VolumeKeyLogStore = VolumeKeyLogStore(volumeKeyLogFile(context.filesDir))
