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
