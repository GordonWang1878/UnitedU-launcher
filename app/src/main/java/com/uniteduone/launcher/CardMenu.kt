package com.uniteduone.launcher

/** 首页当前聚焦的那张卡:HomeScreen 上报给 MainActivity,长按时据此弹菜单。 */
data class CardRef(
    /** 在 HomeScreen rows 里的行下标(含置顶的输入源行)。 */
    val rowIndex: Int,
    val colIndex: Int,
    /** 在 layout.json 里的行下标(不含输入源行);INPUTS 卡为 -1。 */
    val layoutRow: Int,
    val kind: RowKind,
    val pkg: String,
    val label: String,
)

/** 长按菜单项。应用行六项顺序即 design §2;HIDE 只用于输入源行(M4b spec §0-11)。 */
enum class CardAction { OPEN, UNINSTALL, RENAME, CHANGE_ICON, MOVE, REMOVE, HIDE }

/** 应用行六项(design §2);输入源行三项(M4b spec §0-11)。 */
fun cardMenuActions(kind: RowKind): List<CardAction> =
    if (kind == RowKind.APPS) {
        listOf(CardAction.OPEN, CardAction.UNINSTALL, CardAction.RENAME, CardAction.CHANGE_ICON, CardAction.MOVE, CardAction.REMOVE)
    } else {
        listOf(CardAction.OPEN, CardAction.RENAME, CardAction.HIDE)
    }

/** 「新」= 装机时间晚于上次打开添加列表的时刻,且还没放上桌面。 */
fun isNewApp(firstInstallTime: Long, seenAt: Long, onLayout: Boolean): Boolean =
    firstInstallTime > seenAt && !onLayout
