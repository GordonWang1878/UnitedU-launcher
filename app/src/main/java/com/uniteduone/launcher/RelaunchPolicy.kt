package com.uniteduone.launcher

/** 更新后要不要把自己拉回桌面任务:必须仍是默认桌面,且有悬浮窗 appop(否则 BAL_BLOCK,见 CLAUDE.md)。 */
fun shouldRelaunchHome(isDefaultHome: Boolean, canDrawOverlays: Boolean): Boolean =
    isDefaultHome && canDrawOverlays
