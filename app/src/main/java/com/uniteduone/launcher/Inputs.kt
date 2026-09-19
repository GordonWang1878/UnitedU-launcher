package com.uniteduone.launcher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.util.Log

/**
 * 一个可切换的电视输入源(HDMI / 分量 / 复合 / 内置调谐器等)。
 * [id] 是 [TvInputInfo.getId] 返回的稳定标识,启动时用它构造 passthrough URI。
 * [parentId] 是 HDMI-CEC 子设备所在端口的输入 id([TvInputInfo.getParentId]);端口本身为 null。
 */
data class InputEntry(
    val id: String,
    val label: String,
    val isPassthrough: Boolean,
    val parentId: String? = null,
)

/**
 * 电视输入源枚举 + 切换。走标准 [TvInputManager](design §2「跨品牌」),
 * 索尼 / 小米 / TCL 的 Android TV 固件都通过它暴露硬件输入。
 *
 * **健壮性照 [Apps] / [Layout] 的姿势**:拿不到服务、列表为空、某一项读标签抛异常
 * 一律不崩 —— 返回能拿到的那些,空表交给 [buildRows] 优雅处理(不渲染输入源行,
 * 首页焦点不受影响)。真机上「切源」的确切机制是设备相关的,见 [launch] 的说明。
 */
object Inputs {
    private const val TAG = "UnitedU"

    /**
     * 枚举「用户会想切过去」的**硬件**输入源:透传输入(HDMI / 分量 / 复合 / …)
     * 与内置调谐器([TvInputInfo.TYPE_TUNER])。软件 / 应用型输入(流媒体应用注册成的
     * TvInput,type=TYPE_OTHER 且非透传)不是「信号源」,过滤掉。
     *
     * 读标签用 [TvInputInfo.loadLabel]:系统给的就是「HDMI 1 / 分量 / 电视」这类。
     * 拿不到标签时按类型兜一个可读名(见 [fallbackLabel])。
     *
     * 父子(HDMI-CEC)去重见 [dedupeCec]:连了 CEC 设备的 HDMI 口会既有端口输入(HDMI 1)
     * 又有子设备输入(如 PlayStation),这里全量列出,去重是调用方(HomeScreen)的活。
     */
    fun load(ctx: Context): List<InputEntry> {
        val tim = runCatching {
            ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        }.getOrNull()
        if (tim == null) {
            // 非电视设备(手机 / 平板模拟器)没有这个系统服务 —— 正常,不是错误。
            Log.i(TAG, "本机没有 TV_INPUT_SERVICE,输入源行不渲染")
            return emptyList()
        }
        val list = runCatching { tim.tvInputList }.getOrDefault(emptyList())
        return list.mapNotNull { info ->
            runCatching {
                val keep = info.isPassthroughInput || info.type == TvInputInfo.TYPE_TUNER
                if (!keep) return@runCatching null
                val label = info.loadLabel(ctx)?.toString()?.trim().orEmpty()
                InputEntry(
                    id = info.id,
                    label = label.ifEmpty { fallbackLabel(info) },
                    isPassthrough = info.isPassthroughInput,
                    parentId = info.parentId,
                )
            }.getOrNull()
        }
    }

    /**
     * 切到某个输入源。**best-effort,真机上可能要再调**(切源是设备相关的):
     * - 透传输入(HDMI / 分量 / …)用官方推荐的 `ACTION_VIEW` +
     *   [TvContract.buildChannelUriForPassthroughInput];系统 TV 应用会接这条并切过去。
     * - 调谐器(非透传)没有 passthrough URI,退回打开系统 TV 应用的频道列表
     *   (`ACTION_VIEW` + [TvContract.Channels.CONTENT_URI]),让用户落到直播频道。
     *
     * 任一步 startActivity 失败(没有应用接、被后台启动限制拦)返回 false,调用方给提示,
     * 别让用户以为遥控器坏了。真机验证要确认:①索尼固件是否接 passthrough URI;
     * ②默认桌面身份发起 startActivity 会不会被后台启动限制拦(与 RelaunchAfterUpdate 同类风险)。
     */
    fun launch(ctx: Context, inputId: String): Boolean {
        val entry = load(ctx).firstOrNull { it.id == inputId }
        val uri = if (entry == null || entry.isPassthrough) {
            runCatching { TvContract.buildChannelUriForPassthroughInput(inputId) }.getOrNull()
        } else {
            TvContract.Channels.CONTENT_URI
        } ?: return false
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }
            .onFailure { Log.w(TAG, "切换输入源失败 id=$inputId: ${it.message}") }
            .isSuccess
    }

    /** 系统标签拿不到时,按输入类型兜一个可读名。跨品牌都用同一批常量。 */
    private fun fallbackLabel(info: TvInputInfo): String = when (info.type) {
        TvInputInfo.TYPE_TUNER -> "TV"
        TvInputInfo.TYPE_HDMI -> "HDMI"
        TvInputInfo.TYPE_COMPONENT -> "Component"
        TvInputInfo.TYPE_COMPOSITE -> "Composite"
        TvInputInfo.TYPE_SVIDEO -> "S-Video"
        TvInputInfo.TYPE_SCART -> "SCART"
        TvInputInfo.TYPE_VGA -> "VGA"
        TvInputInfo.TYPE_DVI -> "DVI"
        TvInputInfo.TYPE_DISPLAY_PORT -> "DisplayPort"
        else -> "Input"
    }
}
