package com.uniteduone.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 自我更新走 PackageInstaller **会话** API(R162 ⑥,探针 #11 / #12):用 `ACTION_VIEW` 交给系统安装器的更新会把本包标成
 * `PACKAGE_SOURCE_LOCAL_FILE`,Android 13+ 当场锁上「受限设置」、把正在跑的主页键接管服务停掉、开关清空;会话安装不带来源标记,
 * appop 原样、服务更新后由系统自动重连。传 APK 装**别的**应用照旧走 [ApkInstaller](别人的受限状态与我们无关)。
 * 字节在 [install] 里就拷进会话,文件之后可以删;确认页由系统经 [SelfUpdateResult] 要我们打开。
 */
object SelfUpdate {
    private const val TAG = "UnitedU"

    /** 主线程;文件已校验([Update.verify])。STARTED = 会话已提交(系统接着弹确认页);INVALID = 开会话 / 写入失败。 */
    fun install(ctx: Context, file: File): ApkInstaller.Result {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // 与 ApkInstaller.launch 同一套权限引导:跳不过去也返回 NEEDS_PERMISSION,提示足以让用户自己去开。
            ApkInstaller.requestInstallPermission(ctx)
            return ApkInstaller.Result.NEEDS_PERMISSION
        }
        return runCatching {
            val installer = ctx.packageManager.packageInstaller
            val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val callback = Intent(ctx, SelfUpdateResult::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(ctx, id, callback, flags).intentSender)
            }
            Log.i(TAG, "self-update session $id committed (${file.name})")
            ApkInstaller.Result.STARTED
        }.getOrElse { e ->
            Log.w(TAG, "self-update session failed", e)
            ApkInstaller.Result.INVALID
        }
    }
}

/**
 * 会话结果。要用户确认时系统给一个确认页的 intent,这里替它打开(用户刚按了「安装更新」,本应用在前台,启动不受后台限制);
 * 装成功后旧进程已被杀,成功状态由新进程里的这个接收器收到(模拟器实测),只记日志;失败(用户取消等)同样只记日志——
 * 关于页停在「安装中」,与改前 ACTION_VIEW 的行为一致。
 */
class SelfUpdateResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i("UnitedU", "self-update status=$status msg=${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w("UnitedU", "self-update confirm page failed", it) }
    }
}
