package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「添加应用」列表分组(R83)。包名取自 A95L 只读实测(2026-09-24)与 unitedu-gtv 模拟器。
 */
class PickerGroupsTest {
    private val self = "com.uniteduone.launcher"

    private fun thirdParty(pkg: String) = PackageFacts(pkg, isSystem = false, isUpdatedSystem = false,
        hasLauncherEntry = true, hasExportedMain = true, inDefaultLayout = pkg in defaultPkgs)
    private fun preinstalled(pkg: String, updated: Boolean = false) = PackageFacts(pkg, isSystem = true,
        isUpdatedSystem = updated, hasLauncherEntry = true, hasExportedMain = true, inDefaultLayout = pkg in defaultPkgs)
    /** 只有裸 MAIN、没有启动分类的系统组件(includeAllInstalled 兜底曾把它们全捞进来)。 */
    private fun bareMainSystem(pkg: String) = PackageFacts(pkg, isSystem = true, isUpdatedSystem = false,
        hasLauncherEntry = false, hasExportedMain = true, inDefaultLayout = pkg in defaultPkgs)

    private val defaultPkgs = DEFAULT_LAYOUT.flatMap { it.apps }.toSet()

    @Test fun preinstalledContentAppsAreApps() {
        // 腾讯视频 / 乐播投屏 / 当贝市场(索尼版):系统预装、有启动分类、不在平台命名空间
        for (pkg in listOf("com.ktcp.tvvideo", "com.hpplay.happyplay.aw", "com.sony.dangbeimarket")) {
            assertEquals(pkg, PickerGroup.APPS, pickerGroupOf(preinstalled(pkg), self))
        }
    }

    @Test fun tvSettingsAndSonyToolsAreSystemTools() {
        val tools = listOf("com.android.tv.settings") + listOf(
            "appassistant", "calibrationmonitor", "ecodashboard", "livingfit", "mysony", "notificationcenter",
            "osat.music.toast", "smarthelp", "smartmediaapp", "timers", "tvlin",
        ).map { "com.sony.dtv.$it" }
        assertEquals(12, tools.size)
        for (pkg in tools) assertEquals(pkg, PickerGroup.SYSTEM_TOOLS, pickerGroupOf(preinstalled(pkg), self))
        // 模拟器上的直播频道(com.android.tv)同理
        assertEquals(PickerGroup.SYSTEM_TOOLS, pickerGroupOf(preinstalled("com.android.tv"), self))
    }

    @Test fun bareMainSystemComponentsAreNeverListed() {
        val components = listOf(
            // A95L 实测点名的几个
            "com.android.healthconnect.controller", "com.android.vpndialogs", "mediatek.factorymenu.ui",
            "com.sony.dtv.networksettings",
            // 模拟器上同形态的(只有裸 MAIN 的系统包)
            "com.android.providers.calendar", "com.android.systemui", "com.google.android.gms",
            "com.google.android.healthconnect.controller", "com.google.android.marvin.talkback",
            "com.google.android.apps.tv.launcherx", "com.mediatek.wwtv.tvcenter", "android",
        )
        for (pkg in components) assertNull(pkg, pickerGroupOf(bareMainSystem(pkg), self))
    }

    @Test fun systemComponentWithoutExportedMainIsNotListed() {
        assertNull(pickerGroupOf(bareMainSystem("com.sony.dtv.networksettings").copy(hasExportedMain = false), self))
    }

    @Test fun updatedSystemAppsWithLauncherAreApps() {
        // YouTube / Play 商店:预装在 com.google.android. / com.android. 下,但随商店更新过
        assertEquals(PickerGroup.APPS, pickerGroupOf(preinstalled("com.google.android.youtube.tv", updated = true), self))
        assertEquals(PickerGroup.SYSTEM_TOOLS, pickerGroupOf(preinstalled("com.android.vending", updated = true), self))
        // A95L 只读实测:MySony 带 FLAG_UPDATED_SYSTEM_APP,仍是索尼工具
        assertEquals(PickerGroup.SYSTEM_TOOLS, pickerGroupOf(preinstalled("com.sony.dtv.mysony", updated = true), self))
    }

    @Test fun thirdPartyAppsAreApps() {
        for (pkg in listOf("com.netease.cloudmusic.tv", "com.xiaodianshi.tv.yst", "cn.miguvideo.migutv", "com.uniteduone.iconprobe")) {
            assertEquals(pkg, PickerGroup.APPS, pickerGroupOf(thirdParty(pkg), self))
        }
    }

    @Test fun bareMainKnownContentAppFromDefaultLayoutIsApp() {
        // 当贝音乐:入口只有 MAIN + DEFAULT,预装在系统里,靠 DEFAULT_LAYOUT 认出来
        val dbMusic = bareMainSystem("com.dangbei.dbmusic.sonyos.tab")
        assertEquals(true, dbMusic.inDefaultLayout)
        assertEquals(PickerGroup.APPS, pickerGroupOf(dbMusic, self))
        // 非系统的第三方裸 MAIN 同样算应用;不可导出的则不列(列出来也打不开)
        val sideloaded = PackageFacts("com.example.tv", isSystem = false, isUpdatedSystem = false,
            hasLauncherEntry = false, hasExportedMain = true, inDefaultLayout = false)
        assertEquals(PickerGroup.APPS, pickerGroupOf(sideloaded, self))
        assertNull(pickerGroupOf(sideloaded.copy(hasExportedMain = false), self))
    }

    @Test fun selfIsNeverListed() {
        assertNull(pickerGroupOf(thirdParty(self), self))
    }

    @Test fun platformNamespaceMatchesPrefixesOnly() {
        assertEquals(true, isPlatformNamespace("android"))
        assertEquals(true, isPlatformNamespace("mediatek.factorymenu.ui"))
        assertEquals(true, isPlatformNamespace("com.mediatek.wwtv.tvcenter"))
        assertEquals(false, isPlatformNamespace("com.sony.dangbeimarket"))    // com.sony. 但不是 com.sony.dtv.
        assertEquals(false, isPlatformNamespace("com.androidx.fake"))         // 前缀要带点
    }

    @Test fun countNewCountsOnlyAppsGroup() {
        val seenAt = 100L
        val installed = listOf(
            thirdParty("com.new.app") to 200L,                                  // 新应用 → 算
            preinstalled("com.ktcp.tvvideo") to 200L,                           // 预装内容应用,新 → 算
            preinstalled("com.sony.dtv.smarthelp") to 200L,                     // 系统工具 → 不算
            bareMainSystem("com.android.vpndialogs") to 200L,                   // 系统组件 → 不算
            thirdParty("com.old.app") to 50L,                                   // 旧 → 不算
            thirdParty("com.on.layout") to 200L,                                // 已在桌面 → 不算
            thirdParty(self) to 200L,                                           // 自己 → 不算
            bareMainSystem("com.dangbei.dbmusic.sonyos.tab") to 200L,           // 裸 MAIN 的已知内容应用 → 算
        )
        assertEquals(3, countNewApps(installed, seenAt, onLayout = setOf("com.on.layout"), selfPackage = self))
    }

    @Test fun orderPutsAppsFirstThenToolsEachByName() {
        fun c(pkg: String, label: String, g: PickerGroup) = PickerCandidate(AppEntry(pkg, label, null, isWide = false), g)
        val ordered = orderPickerCandidates(listOf(
            c("t.b", "Timers", PickerGroup.SYSTEM_TOOLS),
            c("a.y", "youtube", PickerGroup.APPS),
            c("t.a", "Settings", PickerGroup.SYSTEM_TOOLS),
            c("a.b", "Bilibili", PickerGroup.APPS),
            c("a.none", "", PickerGroup.APPS),
        ))
        assertEquals(listOf("a.none", "a.b", "a.y", "t.a", "t.b"), ordered.map { it.app.packageName })
    }
}
