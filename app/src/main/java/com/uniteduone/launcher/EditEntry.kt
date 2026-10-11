package com.uniteduone.launcher

// R167 进出编辑页的纯判据(MainActivity 用,JVM 单测)。

/**
 * 进编辑页前在 IO 线程预读 layout.json(报告 #2:不在组合阶段读盘)。读回来时:[requested] = 这次请求的号,[current] = 此刻的号
 * (HOME / 离开编辑页都会 +1),[resumed] = 还在前台,[blocked] = 此刻有设置外壳以外的整屏页开着(选图页 / 关于页 / 应用页 /
 * 输入源页 / 引导——编辑页一开会把选图页吞进自己那一层)。号对得上、在前台、没被挡才进——比对,不是闩(铁律 7)。
 */
internal fun shouldEnterEdit(requested: Int, current: Int, resumed: Boolean, blocked: Boolean): Boolean =
    requested == current && resumed && !blocked

/** 编辑页淡入走完之后再多等这么久才停画首页:FadeSwitch 的淡入跑在帧时钟上,宿主卡顿时最后一帧可能比 EDIT_SWAP_MS 晚一点。 */
internal const val EDIT_COVER_SLACK_MS = 50L

/**
 * 编辑页已经整屏不透明地盖住首页那一层([editing] 且淡入已走完)→ 首页不必再画(R167,报告 #4)。只影响绘制:首页照旧在组合里、
 * 焦点账本照旧冻结。退出编辑页(editing = false)当场恢复绘制——编辑页残影从不透明开始淡出,底下必须已经是首页。
 */
internal fun editCoversHome(editing: Boolean, elapsedMs: Long): Boolean =
    editing && elapsedMs >= GtvLayout.EDIT_SWAP_MS + EDIT_COVER_SLACK_MS
