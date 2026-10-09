package com.uniteduone.launcher

// 双通道的判定(spec 2026-10-09 §1)。与 UpdateChecker.kt 同一约束:不 import android.*,JVM 单测直接跑。
// 「读哪几份清单、选哪一个」全在这里;Update.kt 只负责把字节取回来。

/** 用户选的更新通道;存进 settings.json 的 `updateChannel`。 */
enum class UpdateChannel(val id: String) {
    STABLE("stable"), BETA("beta");

    companion object {
        /** 认不出(缺键、手改坏、将来删掉的通道)一律按稳定版——最保守的那一个。 */
        fun fromId(id: String?): UpdateChannel = entries.firstOrNull { it.id == id } ?: STABLE
    }
}

/** 找到的新版来自哪份清单;关于页据此选文案(回退包说「回到稳定版」)。 */
enum class UpdateKind { STABLE, BETA, ROLLBACK }

data class ChannelUpdate(val info: LatestInfo, val kind: UpdateKind)

/**
 * - **稳定通道**:读 `latest.json`;比已装的新 → 它。已装的**比它还新**(刚从 Beta 切回来)→ 读 `rollback.json`,
 *   比已装的新才给;回退包取不到 / 不够新 → 已是最新(记一行日志:多半是回退包漏发)。稳定清单本身失败 = 检查失败。
 * - **Beta 通道**:`latest.json` 与 `beta.json` 都读,取 versionCode 大的那个;只要有一份取到就不算失败
 *   (R2 上还没发过 Beta 时 `beta.json` 不存在是常态)。两份都失败才是检查失败,失败原因取稳定那份的。
 *   从不读 `rollback.json`。
 */
fun resolveChannel(
    channel: UpdateChannel,
    installedCode: Int,
    sdk: Int,
    log: (String) -> Unit = {},
    fetchStable: () -> Result<LatestInfo>,
    fetchBeta: () -> Result<LatestInfo>,
    fetchRollback: () -> Result<LatestInfo>,
): Result<ChannelUpdate?> {
    val stable = fetchStable()
    if (channel == UpdateChannel.BETA) {
        val beta = fetchBeta()
        val candidates = listOfNotNull(
            stable.getOrNull()?.let { ChannelUpdate(it, UpdateKind.STABLE) },
            beta.getOrNull()?.let { ChannelUpdate(it, UpdateKind.BETA) },
        )
        if (candidates.isEmpty()) return Result.failure(stable.exceptionOrNull() ?: UpdateCheckException(CheckFailure.NETWORK))
        if (beta.isFailure) log("beta manifest unavailable; using stable only")
        val best = candidates.maxBy { it.info.versionCode }
        return Result.success(best.takeIf { isNewer(it.info, installedCode, sdk) })
    }
    val s = stable.getOrElse { return Result.failure(it) }
    if (isNewer(s, installedCode, sdk)) return Result.success(ChannelUpdate(s, UpdateKind.STABLE))
    if (installedCode <= s.versionCode) return Result.success(null)
    val rb = fetchRollback().getOrElse {
        log("installed $installedCode > stable ${s.versionCode} but rollback manifest unavailable")
        return Result.success(null)
    }
    if (!isNewer(rb, installedCode, sdk)) {
        log("rollback ${rb.versionCode} not newer than installed $installedCode")
        return Result.success(null)
    }
    return Result.success(ChannelUpdate(rb, UpdateKind.ROLLBACK))
}
