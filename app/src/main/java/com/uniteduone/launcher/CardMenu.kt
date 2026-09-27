package com.uniteduone.launcher

/** 首页当前聚焦的那张卡:HomeScreen 上报给 MainActivity,长按时据此弹菜单。 */
data class CardRef(
    /** 在 HomeScreen rows 里的行下标。 */
    val rowIndex: Int,
    val colIndex: Int,
    /** 在 layout.json 里的行下标。 */
    val layoutRow: Int,
    val pkg: String,
    val label: String,
)

/**
 * 长按菜单项。顺序即 design §2。
 * (R92 起首页没有输入源行,输入源卡专属的 HIDE 随之删掉;输入源的「改名 / 隐藏」在「输入源」页的胶囊菜单里。)
 */
enum class CardAction { OPEN, UNINSTALL, RENAME, CHANGE_ICON, MOVE, REMOVE }

/** 应用卡的六项(design §2)。 */
fun cardMenuActions(): List<CardAction> =
    listOf(CardAction.OPEN, CardAction.UNINSTALL, CardAction.RENAME, CardAction.CHANGE_ICON, CardAction.MOVE, CardAction.REMOVE)

/** 「新」= 装机时间晚于上次打开添加列表的时刻,且还没放上桌面。 */
fun isNewApp(firstInstallTime: Long, seenAt: Long, onLayout: Boolean): Boolean =
    firstInstallTime > seenAt && !onLayout
