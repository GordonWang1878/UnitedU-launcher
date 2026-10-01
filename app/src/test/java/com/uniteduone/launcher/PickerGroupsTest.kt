package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「添加应用」列表分组(R83)。包名取自 A95L 只读实测(2026-09-24)与 unitedu-gtv 模拟器。
 */
class PickerGroupsTest {
    private val self = "com.uniteduone.launcher"

    private fun thirdParty(pkg: String) = PackageFacts(pkg, isSystem = false, isUpdatedSystem = false,
        hasLauncherEntry = true, hasExportedMain = true, knownTvApp = pkg in KNOWN_TV_APPS)
    private fun preinstalled(pkg: String, updated: Boolean = false) = PackageFacts(pkg, isSystem = true,
        isUpdatedSystem = updated, hasLauncherEntry = true, hasExportedMain = true, knownTvApp = pkg in KNOWN_TV_APPS)
    /** 只有裸 MAIN、没有启动分类的系统组件(includeAllInstalled 兜底曾把它们全捞进来)。 */
    private fun bareMainSystem(pkg: String) = PackageFacts(pkg, isSystem = true, isUpdatedSystem = false,
        hasLauncherEntry = false, hasExportedMain = true, knownTvApp = pkg in KNOWN_TV_APPS)

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

    @Test fun knownTvAppsCoverTheOnboardingTableAndTheOnesDroppedFromIt() {
        assertTrue(KNOWN_TV_APPS.containsAll(DEFAULT_LAYOUT.flatMap { it.apps }))
        // R160 从引导表里拿掉的三个,预装成系统应用时仍要能在所有应用里看到
        assertTrue(KNOWN_TV_APPS.containsAll(listOf("com.ktcp.tvvideo", "com.huya.nftv", "com.dangbei.dbmusic.sonyos.tab")))
    }

    @Test fun bareMainKnownContentAppIsApp() {
        // 当贝音乐:入口只有 MAIN + DEFAULT,预装在系统里,靠 KNOWN_TV_APPS 认出来。R160 起它不在引导第 2 步的表里了,
        // 但所有应用页照样要列——两件事分开以后,这一条防的就是「改引导的表,顺手把它从所有应用里藏掉」。
        val dbMusic = bareMainSystem("com.dangbei.dbmusic.sonyos.tab")
        assertEquals(true, dbMusic.knownTvApp)
        assertEquals(PickerGroup.APPS, pickerGroupOf(dbMusic, self))
        // 非系统的第三方裸 MAIN 同样算应用;不可导出的则不列(列出来也打不开)
        val sideloaded = PackageFacts("com.example.tv", isSystem = false, isUpdatedSystem = false,
            hasLauncherEntry = false, hasExportedMain = true, knownTvApp = false)
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

    /**
     * R90:汉字按拼音排(爱 ai < 斗 dou < 腾 teng < 优 you),拉丁字母不分大小写。汉字组与拉丁组谁在前**不断言**:
     * JDK 的 zh 规则拉丁在前,Android(ICU)的 zh 规则把汉字重排到拉丁之前(模拟器实测),两边各自一致即可。
     */
    @Test fun chineseNamesSortByPinyin() {
        fun c(pkg: String, label: String) = PickerCandidate(AppEntry(pkg, label, null, isWide = false), PickerGroup.APPS)
        val ordered = orderPickerCandidates(listOf(
            c("you", "优酷"), c("net", "netflix"), c("teng", "腾讯视频"), c("ai", "爱奇艺"), c("dou", "斗鱼"), c("bili", "Bilibili"),
        ), java.util.Locale.CHINA)
        val ids = ordered.map { it.app.packageName }
        assertEquals(listOf("ai", "dou", "teng", "you"), ids.filter { it in setOf("ai", "dou", "teng", "you") })
        assertEquals(listOf("bili", "net"), ids.filter { it in setOf("bili", "net") })
    }
}
