package com.uniteduone.launcher

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R164:节目的 `intent_uri` 是别的应用写的,桌面拿自己的身份去启动它。两条防线:去掉一切 URI 授权位
 * (否则一个应用能借桌面之手把 UnitedU 的 FileProvider 里的文件授权出去),目标只许是发布方自己的包。
 */
class ChannelLaunchTest {
    @Test fun grantFlagsAreStrippedAndNewTaskAdded() {
        val evil = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK, launchFlags(evil))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launchFlags(0))
    }

    @Test fun onlyThePublishersOwnActivitiesMayBeLaunched() {
        assertTrue(launchTargetAllowed("com.cibn.tv", "com.cibn.tv", "com.uniteduone.launcher"))
        assertFalse("指向别的包", launchTargetAllowed("com.evil", "com.cibn.tv", "com.uniteduone.launcher"))
        assertFalse("指向桌面自己", launchTargetAllowed("com.uniteduone.launcher", "com.uniteduone.launcher", "com.uniteduone.launcher"))
        assertFalse("解析不到", launchTargetAllowed(null, "com.cibn.tv", "com.uniteduone.launcher"))
    }

    private val self = "com.uniteduone.launcher"

    @Test fun shareAndChooserActionsAreRefused() {
        // Instrumentation 会把 ACTION_SEND 的 EXTRA_TEXT 迁成 ClipData 并在我们去掉授权位之后重新加上 READ 授权
        assertFalse(launchIntentAllowed(Intent.ACTION_SEND, null, null, self))
        assertFalse(launchIntentAllowed(Intent.ACTION_SEND_MULTIPLE, null, null, self))
        assertFalse(launchIntentAllowed(Intent.ACTION_CHOOSER, null, null, self))
    }

    @Test fun dataPointingAtOurOwnProvidersIsRefused() {
        assertFalse(launchIntentAllowed(Intent.ACTION_VIEW, "content", "com.uniteduone.launcher.fileprovider", self))
        assertFalse(launchIntentAllowed(Intent.ACTION_VIEW, "content", self, self))
        assertFalse("带用户前缀的 authority(content://0@…)", launchIntentAllowed(Intent.ACTION_VIEW, "content", "0@com.uniteduone.launcher.fileprovider", self))
        assertFalse("scheme 大小写不影响", launchIntentAllowed(Intent.ACTION_VIEW, "CONTENT", "com.uniteduone.launcher.fileprovider", self))
    }

    @Test fun ordinaryProgramIntentsAreAllowed() {
        assertTrue(launchIntentAllowed(Intent.ACTION_VIEW, "https", "www.cibn.tv", self))
        assertTrue(launchIntentAllowed(Intent.ACTION_VIEW, "content", "com.cibn.tv.provider", self))
        assertTrue(launchIntentAllowed(null, null, null, self))
        assertTrue(launchIntentAllowed(Intent.ACTION_MAIN, "cibn", "play", self))
    }

    @Test fun debounceIsCappedByMaxWait() {
        assertEquals(CHANNELS_DEBOUNCE_MS, channelsBumpDelay(now = 1_000, firstPending = 1_000))
        assertEquals("离 2 s 上限还剩 300 ms", 300L, channelsBumpDelay(now = 2_700, firstPending = 1_000))
        assertEquals("已超过上限:立刻刷新", 0L, channelsBumpDelay(now = 3_500, firstPending = 1_000))
    }

    @Test fun bumpOnlyWhenStarted() {
        val L = androidx.lifecycle.Lifecycle.State.values()
        assertEquals(false, shouldBumpChannels(androidx.lifecycle.Lifecycle.State.CREATED))
        assertEquals(true, shouldBumpChannels(androidx.lifecycle.Lifecycle.State.STARTED))
        assertEquals(true, shouldBumpChannels(androidx.lifecycle.Lifecycle.State.RESUMED))
        assertEquals(false, shouldBumpChannels(androidx.lifecycle.Lifecycle.State.DESTROYED))
        assertEquals(5, L.size)
    }
}
