package com.uniteduone.launcher

/** 应用行数上下限(DESIGN §2:1–5 行)。 */
internal const val MIN_ROWS = 1
internal const val MAX_ROWS = 5

/** 在第 [index] 行下方插一个空行(默认图标 [NEW_ROW_ICON]);已满 [MAX_ROWS] 行或越界 → 原样返回同一个 list。 */
internal fun addRowBelow(rows: List<LayoutRow>, index: Int, name: String): List<LayoutRow> {
    if (rows.size >= MAX_ROWS || index !in rows.indices) return rows
    return rows.toMutableList().apply { add(index + 1, LayoutRow(name = name, icon = NEW_ROW_ICON)) }
}

/** 删第 [index] 行;只剩 [MIN_ROWS] 行或越界 → 原样返回。行里的应用只是离开桌面,不卸载。 */
internal fun deleteRow(rows: List<LayoutRow>, index: Int): List<LayoutRow> {
    if (rows.size <= MIN_ROWS || index !in rows.indices) return rows
    return rows.filterIndexed { i, _ -> i != index }
}

/**
 * 改名:与卡片标题同一套清洗([sanitizeTitle]:去首尾空白、截到 [MAX_TITLE_CHARS]);清洗后为空、越界,
 * 或与现在的名字相同 → 原样返回同一个 list(行必须有名字;名字没变就不写盘,也不去钉图标)。
 * **改名不改图标**:没存图标的旧行靠名字回落([effectiveRowIconId]),改了名回落结果就跟着变(MUSIC 改成 "Kids"
 * 会从音符变成电视)——所以这种行改名时把**当前看到的**图标存进 `icon`;已经存了图标的行照旧。
 */
internal fun renameRow(rows: List<LayoutRow>, index: Int, name: String): List<LayoutRow> {
    val clean = sanitizeTitle(name)
    if (clean.isEmpty() || index !in rows.indices) return rows
    if (clean == rows[index].name) return rows
    return rows.mapIndexed { i, r ->
        // 用 effectiveRowIconId(r.name, r.icon) 而不是 r.icon ?: effectiveRowIconId(r.name, null):
        // 后者只在 icon 为 null 时才回落,存了非法 id(比如手改坏的文件)的行会被原样带过去;
        // 前者连非法 id 也一并纠正(终审 Minor #4)。
        if (i == index) r.copy(name = clean, icon = effectiveRowIconId(r.name, r.icon)) else r
    }
}

/** 换图标;不认识的 id 或越界 → 原样返回。 */
internal fun setRowIcon(rows: List<LayoutRow>, index: Int, icon: String): List<LayoutRow> {
    if (!isRowIconId(icon) || index !in rows.indices) return rows
    return rows.mapIndexed { i, r -> if (i == index) r.copy(icon = icon) else r }
}

/**
 * 所有应用页「加到桌面…」(R90):把 [pkg] 加到第 [index] 行末尾。那一行不在、名字对不上 [expectName](菜单打开之后
 * 别处改过 layout.json:删行 / 换序 / 改名),或那一行里已经有它(一行里一个包只能有一张,`Layout.read` 做 distinct)
 * → 原样返回**同一个** list,[Layout.update] 据此不写盘。其余行一个字节不动。
 */
internal fun addToRow(rows: List<LayoutRow>, index: Int, expectName: String, pkg: String): List<LayoutRow> {
    val row = rows.getOrNull(index) ?: return rows
    if (row.name != expectName || pkg in row.apps) return rows
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
 * 行名、行序、其余包的顺序都不动。
 */
internal fun dropRemovedElsewhere(
    snapshot: List<LayoutRow>,
    disk: List<LayoutRow>,
    knownOnDisk: Set<String>,
    installed: (String) -> Boolean,
): List<LayoutRow> {
    val onDisk = disk.flatMapTo(HashSet()) { it.apps }
    val gone = snapshot.flatMapTo(HashSet()) { it.apps }
        .filterTo(HashSet()) { it in knownOnDisk && it !in onDisk && !installed(it) }
    if (gone.isEmpty()) return snapshot
    return snapshot.map { r -> if (r.apps.any { it in gone }) r.copy(apps = r.apps.filter { it !in gone }) else r }
}

/**
 * 一次写盘成功之后,「上次确知在盘上」的包集合怎么更新(2026-09-30 Codex 评审 P2):**只增不减** = 旧集合 ∪ 这次写下的。
 * 原来直接换成「这次写下的」:第一次写盘按 [dropRemovedElsewhere] 滤掉了已卸载的包,集合里也随之没了它,
 * 而编辑页内存里的 `rows` 仍留着它(看不见的包留原下标,R67);第二次写盘它就被当成「本页新加、还没落过盘」保留,
 * 又写回了布局——以后重装会意外回到旧位置。只增不减之后,它每次都满足「曾在盘上、此刻不在、没装」,每次都被滤掉。
 */
internal fun knownAfterWrite(known: Set<String>, written: List<LayoutRow>): Set<String> =
    written.flatMapTo(HashSet(known)) { it.apps }

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
 * 编辑页**看得见的**那份行(R67):行一一对应(行数、行名、图标、行序都照 [rows]),每行只留 [shown] 的包。
 * 编辑页里一切「第几行第几格」(焦点目标、搬运位置、卡片菜单)都按这份算——与首页同一个口径,
 * 所以不再有「编辑页列号 = layout.json 下标、首页列号不是」的两套坐标。改完之后经 [withVisibleEdits] 合回整份。
 */
internal fun visibleRows(rows: List<LayoutRow>, shown: (String) -> Boolean): List<LayoutRow> =
    rows.map { r -> if (r.apps.all(shown)) r else r.copy(apps = r.apps.filter(shown)) }

/**
 * 把编辑页对**可见那份**([visibleRows])做的改动合回整份 [full](R67)。[edited] 与 [full] 行一一对应
 * (行级操作——增删、交换、改名、换图标——直接作用在整份上,不经这里;这里只管行内的包)。
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
