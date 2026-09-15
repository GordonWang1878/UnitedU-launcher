package com.uniteduone.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** 传 APK 安装(spec §4)。M7 检查更新复用同一个入口。[install] 会 startActivity,**必须在主线程调用**。 */
object ApkInstaller {
    /**
     * [BACKGROUND] 是**调用方**的判定,[install] 自己永不返回它:导入页不在前台时不该弹安装器
     * (spec §4),`UploadServer` 在主线程回合里先查前台、不满足就直接给这个结果、根本不调 [install]。
     * 放进同一个枚举是为了让路由那一处 `when` 覆盖全部结局,不必再拆一层可空类型。
     */
    enum class Result { STARTED, NEEDS_PERMISSION, INVALID, BACKGROUND }

    /** 解析 APK 的 (包名, 版本名);不是 APK 返回 null。 */
    fun archiveInfo(ctx: Context, file: File): Pair<String, String>? {
        val info = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
        return info.packageName to (info.versionName ?: "")
    }

    fun install(ctx: Context, file: File): Result {
        if (archiveInfo(ctx, file) == null) return Result.INVALID
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // 「允许安装未知应用」只能用户自己在系统页点;我们跳过去,电视端与手机端各提示一句。
            // 跳转本身要兜住:国行定制 TV 固件不一定有这个设置页(ActivityNotFoundException),
            // 跳不过去照样返回 NEEDS_PERMISSION——那句提示足以让用户自己去系统设置里开,
            // 不该因为跳不过去就把整个请求变成 500(结局仍是「权限不够」,不是「服务坏了」)。
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return Result.NEEDS_PERMISSION
        }
        // getUriForFile 在 file 落在 file_paths.xml 声明范围外时抛 IllegalArgumentException;
        // startActivity 找不到能处理这个 Intent 的 Activity 时抛 ActivityNotFoundException——
        // M7 检查更新会拿任意来源的文件复用这个入口,两种异常都不该让调用方崩溃,按 INVALID 处理。
        return runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Result.STARTED
        }.getOrDefault(Result.INVALID)
    }
}
