package com.uniteduone.launcher

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/** ContentObserver 去抖时长(spec §3.3:500 ms 后 `channelsRevision++`)。 */
internal const val CHANNELS_DEBOUNCE_MS = 500L

/** MainActivity 记「用户点过拒绝」的 SharedPreferences 文件名与键([permissionResult] 的 deniedBefore)。 */
internal const val TV_LISTINGS_MARKS = "channel-permission"
internal const val TV_LISTINGS_DENIED = "deniedBefore"

/**
 * R164:频道相关的运行时环境,MainActivity 在 setContent 顶层提供(编辑页的频道架子、选频道页读它;首页直接收参数)。
 * - [revision]:MainActivity 的 `channelsRevision`(TvProvider 变化去抖、授权结果、onResume 都 ++),读频道数据的效果拿它当 key。
 * - [requestPermission]:弹系统授权窗,结果([PermissionResult])回调到主线程。
 * - [openPermissionSettings]:跳本应用的系统详情页(拒绝过 / 「不再询问」后只能从那里开)。
 */
data class ChannelEnv(
    val revision: Int = 0,
    val requestPermission: (onResult: (PermissionResult) -> Unit) -> Unit = { it(PermissionResult.DENIED) },
    val openPermissionSettings: () -> Unit = {},
)

/** 一次授权申请的结果。只有 [DENIED_PERMANENTLY] 时调用方才自动跳系统设置(owner 裁定 2026-10-09)。 */
enum class PermissionResult { GRANTED, DENIED, DENIED_PERMANENTLY }

/**
 * owner 裁定(2026-10-09):拒绝后只有「永久拒绝」——系统这次**没弹窗**、直接回拒——才算 [PermissionResult.DENIED_PERMANENTLY];
 * 用户在窗里点了拒绝(哪怕是第二次、此后不再询问)或按返回 / 主页键关掉窗,都是 [PermissionResult.DENIED](留在原地)。
 * 标准的前后对照:申请前后 `shouldShowRequestPermissionRationale` 都是 false 且结果是拒绝 = 没弹窗。
 * 但「从没拒绝过、这次按返回关窗」前后也都是 false(返回不算拒绝),所以另要 [deniedBefore]:以前见过 rationale 为真
 * (= 用户点过拒绝;MainActivity 存在一份小 SharedPreferences 里,授权到手时清掉)。拿不准时一律算 DENIED——
 * 留在原地、页上有「去系统设置开启」,比误跳系统设置安全。
 */
internal fun permissionResult(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, deniedBefore: Boolean): PermissionResult = when {
    granted -> PermissionResult.GRANTED
    !rationaleBefore && !rationaleAfter && deniedBefore -> PermissionResult.DENIED_PERMANENTLY
    else -> PermissionResult.DENIED
}

val LocalChannelEnv: ProvidableCompositionLocal<ChannelEnv> = compositionLocalOf { ChannelEnv() }
