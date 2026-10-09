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
}
