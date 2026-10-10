package com.uniteduone.launcher

// R167 进出编辑页的纯判据(MainActivity 用,JVM 单测)。

/**
 * 进编辑页前在 IO 线程预读 layout.json(报告 #2:不在组合阶段读盘)。读回来时:[requested] = 这次请求的号,[current] = 此刻的号
 * (HOME / 离开编辑页都会 +1),[resumed] = 还在前台,[blocked] = 此刻有设置外壳以外的整屏页开着(选图页 / 关于页 / 应用页 /
 * 输入源页 / 引导——编辑页一开会把选图页吞进自己那一层)。号对得上、在前台、没被挡才进——比对,不是闩(铁律 7)。
 */
internal fun shouldEnterEdit(requested: Int, current: Int, resumed: Boolean, blocked: Boolean): Boolean =
    requested == current && resumed && !blocked
