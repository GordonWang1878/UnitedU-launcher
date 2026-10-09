package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelsTest {
    private val hex = "0123456789abcdef".repeat(4)
    private fun info(code: Int, name: String = "v$code") = LatestInfo(code, name, "", "https://e.com/$code.apk", hex, 1)
    private fun ok(code: Int) = { Result.success(info(code)) }
    private val net = { Result.failure<LatestInfo>(UpdateCheckException(CheckFailure.NETWORK)) }
    private val never: () -> Result<LatestInfo> = { error("不该被读") }

    private fun run(ch: UpdateChannel, installed: Int, stable: () -> Result<LatestInfo>, beta: () -> Result<LatestInfo> = never, rollback: () -> Result<LatestInfo> = never) =
        resolveChannel(ch, installed, 34, fetchStable = stable, fetchBeta = beta, fetchRollback = rollback)

    @Test fun stableNewer() = assertEquals(ChannelUpdate(info(7), UpdateKind.STABLE), run(UpdateChannel.STABLE, 6, ok(7)).getOrThrow())
    @Test fun stableSameIsUpToDate() = assertNull(run(UpdateChannel.STABLE, 6, ok(6)).getOrThrow())
    @Test fun stableFailureIsFailure() = assertTrue(run(UpdateChannel.STABLE, 6, net).isFailure)

    @Test fun stableChannelAboveStableReadsRollback() =
        assertEquals(ChannelUpdate(info(8), UpdateKind.ROLLBACK), run(UpdateChannel.STABLE, 7, ok(6), rollback = ok(8)).getOrThrow())
    @Test fun rollbackNotNewerIsUpToDate() = assertNull(run(UpdateChannel.STABLE, 9, ok(6), rollback = ok(8)).getOrThrow())
    @Test fun rollbackMissingIsUpToDate() = assertNull(run(UpdateChannel.STABLE, 7, ok(6), rollback = net).getOrThrow())

    @Test fun betaChannelTakesBeta() =
        assertEquals(ChannelUpdate(info(9), UpdateKind.BETA), run(UpdateChannel.BETA, 6, ok(6), beta = ok(9)).getOrThrow())
    @Test fun betaChannelTakesNewerStable() =
        assertEquals(ChannelUpdate(info(11), UpdateKind.STABLE), run(UpdateChannel.BETA, 9, ok(11), beta = ok(9)).getOrThrow())
    @Test fun betaMissingFallsBackToStable() =
        assertEquals(ChannelUpdate(info(7), UpdateKind.STABLE), run(UpdateChannel.BETA, 6, ok(7), beta = net).getOrThrow())
    @Test fun betaOnlyStableFailedStillWorks() =
        assertEquals(ChannelUpdate(info(9), UpdateKind.BETA), run(UpdateChannel.BETA, 6, net, beta = ok(9)).getOrThrow())
    @Test fun betaBothFailedIsFailure() = assertTrue(run(UpdateChannel.BETA, 6, net, beta = net).isFailure)
    @Test fun betaUpToDate() = assertNull(run(UpdateChannel.BETA, 9, ok(6), beta = ok(9)).getOrThrow())
    @Test fun betaChannelNeverReadsRollback() { run(UpdateChannel.BETA, 9, ok(6), beta = ok(9)) }  // never 会抛

    @Test fun minSdkTooHighIsNotNewer() {
        val high = { Result.success(info(7).copy(minSdk = 99)) }
        assertNull(run(UpdateChannel.STABLE, 6, high).getOrThrow())
    }

    @Test fun channelIdRoundTrip() {
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromId("beta"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromId("stable"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromId(null))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromId("nightly"))
    }
}
