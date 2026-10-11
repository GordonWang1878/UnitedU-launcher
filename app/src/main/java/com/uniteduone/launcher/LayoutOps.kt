package com.uniteduone.launcher

/** 应用行数上下限(DESIGN §2:1–5 行)。 */
internal const val MIN_ROWS = 1
internal const val MAX_ROWS = 5

/** 应用行数(R164 起 [MIN_ROWS] / [MAX_ROWS] 只数它;「新的一行 → 应用行」([appendAppRow])与删除胶囊的判据都经这里)。 */
internal fun appRowCount(rows: List<LayoutRow>): Int = rows.count { !it.isChannel }

/** R164:频道行上限(spec §4:频道行 0–5 行,与应用行分开数)。 */
internal const val MAX_CHANNEL_ROWS = 5

/** 频道行写盘的图标(只给旧版本回落成空应用行用,界面不画)。 */
internal const val CHANNEL_ROW_ICON = "tv"

/** 频道行数(上限 [MAX_CHANNEL_ROWS])。 */
internal fun channelRowCount(rows: List<LayoutRow>): Int = rows.count { it.isChannel }

internal fun canAddAppRow(rows: List<LayoutRow>): Boolean = appRowCount(rows) < MAX_ROWS

internal fun canAddChannelRow(rows: List<LayoutRow>): Boolean = channelRowCount(rows) < MAX_CHANNEL_ROWS

/** R164:在最后追加一个频道行;频道行已满,或这个频道已经在桌面上 → 同一个 list。 */
internal fun appendChannelRow(rows: List<LayoutRow>, ref: ChannelRef): List<LayoutRow> =
    if (!canAddChannelRow(rows) || rows.any { it.channel == ref }) rows
    else rows + LayoutRow(icon = CHANNEL_ROW_ICON, channel = ref)

/** 应用行在 layout.json 里的下标(「加到桌面… → 选一行」只列这些,spec §5)。 */
internal fun appRowIndices(rows: List<LayoutRow>): List<Int> = rows.indices.filter { !rows[it].isChannel }

/**
 * 在最后追加一个空应用行(默认图标 [NEW_ROW_ICON];R165「新的一行 → 应用行」,新行一律插在最后,换位置用上移 / 下移)。
 * 应用行已满 [MAX_ROWS](或手改坏的文件超量)→ 原样返回**同一个** list,调用方据此不写盘、不挪焦点。
 */
internal fun appendAppRow(rows: List<LayoutRow>): List<LayoutRow> =
    if (appRowCount(rows) >= MAX_ROWS) rows else rows + LayoutRow(icon = NEW_ROW_ICON)

/**
 * 删第 [index] 行;越界 → 原样返回。**R164**:频道行永远能删;应用行只剩 [MIN_ROWS] 行时不删
 * (只数应用行——频道行不能代替「至少一个添加应用的入口」)。行里的应用只是离开桌面,不卸载。
 */
internal fun deleteRow(rows: List<LayoutRow>, index: Int): List<LayoutRow> {
    val row = rows.getOrNull(index) ?: return rows
    if (!row.isChannel && appRowCount(rows) <= MIN_ROWS) return rows
    return rows.filterIndexed { i, _ -> i != index }
}

/** 换图标;不认识的 id 或越界 → 原样返回。 */
internal fun setRowIcon(rows: List<LayoutRow>, index: Int, icon: String): List<LayoutRow> {
    if (!isRowIconId(icon) || index !in rows.indices || rows[index].isChannel) return rows
    return rows.mapIndexed { i, r -> if (i == index) r.copy(icon = icon) else r }
}

/**
 * 所有应用页「加到桌面…」(R90):把 [pkg] 加到第 [index] 行末尾。那一行不在(越界),或那一行里已经有它
 * (一行里一个包只能有一张,`Layout.read` 做 distinct)→ 原样返回**同一个** list,[Layout.update] 据此不写盘。
 * 其余行一个字节不动。(R163 前还要核对行名,防「菜单打开之后别处改过 layout.json」;行没有名字了,只认下标——
 * 菜单开着时没有别的入口能改行序 / 删行,那些只能在编辑页里做。)
 */
internal fun addToRow(rows: List<LayoutRow>, index: Int, pkg: String): List<LayoutRow> {
    val row = rows.getOrNull(index) ?: return rows
    if (row.isChannel || pkg in row.apps) return rows   // R164:频道行不收应用
    return rows.mapIndexed { i, r -> if (i == index) r.copy(apps = r.apps + pkg) else r }
}

/** 交换两行(上移 / 下移);任一越界或两者相同 → 原样返回。 */
internal fun swapRows(rows: List<LayoutRow>, a: Int, b: Int): List<LayoutRow> {
    if (a !in rows.indices || b !in rows.indices || a == b) return rows
    return rows.toMutableList().apply { val t = this[a]; this[a] = this[b]; this[b] = t }
}

/**
 * 编辑页整份写盘前的合并(2026-09-23 落盘排查):编辑页开着时 `rows` 只在进页时读一次,而清单里的
 * `PackageRemovedReceiver` / MainActivity 的动态接收器随时会把**真正卸载**的包从盘上清掉([Layout.removePackage])。
 * 编辑页下一次整份写回,会把那个包原样写回去——编辑页从此多一张「未安装」的僵尸卡(2026-09-16 修过的同一个症状)。
 *
 * 规则:快照里的包,**上次已知在盘上**([knownOnDisk])、此刻**不在盘上**、且**没装**——三条同时成立才去掉
 * (= 被别人清理掉的卸载)。刚在本页加进来、还没落过盘的包不在 [knownOnDisk] 里,一律保留;
 * 卸载后又重装、用户又加回来的包 [installed] 为 true,也保留。一个都没去掉时返回**同一个** list。
 * 行序、行图标、其余包的顺序都不动。
 */
internal fun dropRemovedElsewhere(
    snapshot: List<LayoutRow>,
    disk: List<LayoutRow>,
    knownOnDisk: Set<String>,
    installed: (String) -> Boolean,
): List<LayoutRow> {
    val onDisk = layoutPackages(disk).toHashSet()
    val gone = layoutPackages(snapshot).filterTo(HashSet()) { it in knownOnDisk && it !in onDisk && !installed(it) }
    if (gone.isEmpty()) return snapshot
    // R164:频道行的发布方同样按「曾在盘上、此刻不在、没装」认,删整行
    return snapshot.filter { it.channel?.pkg !in gone }.map { r -> if (r.apps.any { it in gone }) r.copy(apps = r.apps.filter { it !in gone }) else r }
}

/**
 * 一次写盘成功之后,「上次确知在盘上」的包集合怎么更新(2026-09-30 Codex 评审 P2):**只增不减** = 旧集合 ∪ 这次写下的。
 * 原来直接换成「这次写下的」:第一次写盘按 [dropRemovedElsewhere] 滤掉了已卸载的包,集合里也随之没了它,
 * 而编辑页内存里的 `rows` 仍留着它(看不见的包留原下标,R67);第二次写盘它就被当成「本页新加、还没落过盘」保留,
 * 又写回了布局——以后重装会意外回到旧位置。只增不减之后,它每次都满足「曾在盘上、此刻不在、没装」,每次都被滤掉。
 */
internal fun knownAfterWrite(known: Set<String>, written: List<LayoutRow>): Set<String> =
    HashSet(known).apply { addAll(layoutPackages(written)) }

/**
 * 编辑页的一格要不要画(Ruling R67,2026-09-23:已卸载的应用不占位、不画「未安装」)。与首页 `buildRows` 同一口径:
 * 只画 `Apps.load` 认得出的(已安装、可启动)包。[checked] = 上一次 `Apps.load` 查过的包(null = 一次都还没查完),
 * [found] = 其中查到的。三种情况:
 * - 查过、查到 → 画(真卡片);
 * - 查过、没查到 → **不画**(没装 / 装了但没有可启动入口,比如被停用);
 * - 还没查过(进页数据没到、或刚加进来的包)→ 画(中性的加载占位),不能当成没装藏起来——
 *   刚加的应用若先被藏掉、数据到了再冒出来,焦点与列号会跟着跳一格。
 */
internal fun editCardShown(pkg: String, checked: Set<String>?, found: Set<String>): Boolean =
    checked == null || pkg !in checked || pkg in found

/**
 * 编辑页**看得见的**那份行(R67):行一一对应(行数、图标、行序都照 [rows]),每行只留 [shown] 的包。
 * 编辑页里一切「第几行第几格」(焦点目标、搬运位置、卡片菜单)都按这份算——与首页同一个口径,
 * 所以不再有「编辑页列号 = layout.json 下标、首页列号不是」的两套坐标。改完之后经 [withVisibleEdits] 合回整份。
 */
internal fun visibleRows(rows: List<LayoutRow>, shown: (String) -> Boolean): List<LayoutRow> =
    rows.map { r -> if (r.apps.all(shown)) r else r.copy(apps = r.apps.filter(shown)) }

/**
 * 把编辑页对**可见那份**([visibleRows])做的改动合回整份 [full](R67)。[edited] 与 [full] 行一一对应
 * (行级操作——增删、交换、换图标——直接作用在整份上,不经这里;这里只管行内的包)。
 *
 * 每一行:[full] 里看不见的包(`!shown`)**留在原来的下标上**(行变短放不下时依次往前挤,彼此顺序不变),
 * 其余的格子按 [edited] 的顺序填可见的包。所以
 * - 编辑页看不见的包(装了但被停用、或盘上还没清掉的已卸载包)不会被编辑页弄丢,也不会被挪到行尾;
 *   已卸载的包由数据层清理(`pruneMissingPackages` / `pruneUninstalled`),编辑页不负责删它们;
 * - 没有看不见的包时,结果就是 [edited] 本身。
 * 行数对不上(不该发生:可见那份由整份逐行派生)时原样返回 [edited]——宁可丢看不见的包,也不把包塞进别的行。
 * 与 [full] 结构相同时返回**同一个** [full]。
 */
internal fun withVisibleEdits(
    full: List<LayoutRow>,
    edited: List<LayoutRow>,
    shown: (String) -> Boolean,
): List<LayoutRow> {
    if (full.size != edited.size) return edited
    val merged = edited.mapIndexed { i, e ->
        val hidden = full[i].apps.withIndex().filter { !shown(it.value) }
        if (hidden.isEmpty()) return@mapIndexed e
        val n = hidden.size + e.apps.size
        val out = arrayOfNulls<String>(n)
        var minSlot = 0
        hidden.forEachIndexed { j, (idx, pkg) ->
            // 夹到「后面还有几个看不见的包就留几格」,再夹到上一个之后:顺序不变、不越界
            val slot = idx.coerceAtMost(n - (hidden.size - j)).coerceAtLeast(minSlot)
            out[slot] = pkg
            minSlot = slot + 1
        }
        val vis = e.apps.iterator()
        for (k in 0 until n) if (out[k] == null) out[k] = vis.next()
        e.copy(apps = out.map { it!! })
    }
    return if (merged == full) full else merged
}

/**
 * 编辑页卡片菜单开在哪张卡上:行号、打开那一刻的列号、**包名**(测试轮 B-06,2026-09-30)。
 * 菜单认的是包名,不是坐标——这张卡的应用在菜单开着时被卸载,同一行后面的卡左移一格,只按 (行, 列) 认的话
 * 菜单会悄悄换成下一张卡,再按「移出」就移错了应用(铁律 5:目标要按身份认)。此刻的列号用 [editActingCol] 现查。
 */
internal data class EditActing(val row: Int, val col: Int, val pkg: String)

/** [a] 那张卡此刻在看得见的那份([visible])里的列号;卡不在那一行了(被卸载、被移走)→ null,菜单随之收掉。 */
internal fun editActingCol(a: EditActing, visible: List<LayoutRow>): Int? =
    visible.getOrNull(a.row)?.apps?.indexOf(a.pkg)?.takeIf { it >= 0 }
