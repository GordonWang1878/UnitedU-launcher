package com.uniteduone.launcher

import kotlin.math.min

/*
 * ---- 图片网格的格子换算与版式(Ruling R63 / R115,纯函数,单测在 PickerCellsTest)----
 *
 * R115 起三个选图页(换壁纸 / 屏保图库 / 换卡片图)分「内置 / 我的」上下两块,但**仍是一个网格**:
 * 格子下标(cell)连续编号 = [内置图 0 … B−1] + [「＋ 从手机添加」] + [我的图片 …]。
 * PickerGrid 的 focusedIdx / holderIdx / focusRequesters 全用格子下标;「图片下标」(image)是调用方「我的」那份
 * 列表(换卡片图时第 0 项是「恢复原图」)。两者的换算只在这几个函数里,别处不手写 ±1 / ±B。
 * B = 内置图张数;清单为空(B = 0)时与 R63 逐格相同:格 0 =「＋」,图片 i 在格 i + 1。
 */

/** 「＋ 从手机添加」所在的格子 = 内置图之后的第一格。 */
internal fun addCell(builtinCount: Int = 0): Int = builtinCount

/** 「我的」第 [image] 张图片的格子。 */
internal fun cellOfImage(image: Int, builtinCount: Int = 0): Int = builtinCount + 1 + image

/** 格子 → 「我的」图片下标;内置格与「＋」不是「我的」图片 → null。 */
internal fun imageOfCell(cell: Int, builtinCount: Int = 0): Int? = (cell - builtinCount - 1).takeIf { it >= 0 }

/** 格子 → 内置图下标;不是内置格 → null。 */
internal fun builtinOfCell(cell: Int, builtinCount: Int): Int? = cell.takeIf { it in 0 until builtinCount }

/** 整个网格的格子:内置 + 「＋」+ 我的。 */
internal fun <T> pickerCells(builtins: List<T>, add: T, images: List<T>): List<T> = builtins + add + images

/** 焦点目标夹回合法格子(删图后末格消失、删空只剩「＋」都靠它;「我的」在最后,删图只会让尾巴变短)。 */
internal fun clampCell(cell: Int, cellCount: Int): Int = cell.coerceIn(0, (cellCount - 1).coerceAtLeast(0))

/**
 * 从扫码页回来时焦点落哪一格:本次新传的文件([uploaded],按上传先后)里第一张**还在网格里**的那张;
 * 一张都没有(没传、传的是别的分类、在手机上又删了、被卡片图上限挤掉)→ 「＋」。
 * [imageNames] 与「我的」图片下标一一对应,不是文件的项(换卡片图的「恢复原图」)给 null。
 * 上传只进「我的」,内置图不参与匹配(内置与我的同名也不会落到内置那格上)。
 */
internal fun landingCell(imageNames: List<String?>, uploaded: List<String>, builtinCount: Int = 0): Int {
    for (name in uploaded) {
        val i = imageNames.indexOf(name)
        if (i >= 0) return cellOfImage(i, builtinCount)
    }
    return addCell(builtinCount)
}

/** 网格的两块。 */
internal enum class PickerSection { BUILTIN, MINE }

/** 网格里的一行:分组标题(不可聚焦),或一行格子([first] 起连续 [count] 格)。 */
internal sealed class PickerLine {
    data class Title(val section: PickerSection) : PickerLine()
    data class Cells(val section: PickerSection, val first: Int, val count: Int) : PickerLine()
}

/**
 * 网格的行:有内置图时「内置」标题 + 内置各行 + 「我的」标题 + 我的各行(第一格是「＋」);
 * **内置清单为空时上块整块不显示,「我的」标题也不画**——只有一块时标题是多余的,版式与 R63 逐像素相同。
 * [mineCells] 含「＋」,至少 1。两块各自从行首排起(内置最后一行没排满,「我的」也另起一行)。
 */
internal fun pickerLines(builtinCount: Int, mineCells: Int, columns: Int): List<PickerLine> {
    val cols = columns.coerceAtLeast(1)
    val out = ArrayList<PickerLine>()
    if (builtinCount > 0) {
        out += PickerLine.Title(PickerSection.BUILTIN)
        for (start in 0 until builtinCount step cols) {
            out += PickerLine.Cells(PickerSection.BUILTIN, start, min(cols, builtinCount - start))
        }
        out += PickerLine.Title(PickerSection.MINE)
    }
    for (start in 0 until mineCells step cols) {
        out += PickerLine.Cells(PickerSection.MINE, builtinCount + start, min(cols, mineCells - start))
    }
    return out
}

/** 格子 [cell] 在第几行(行下标指 [lines]);不在任何一行 → −1。 */
internal fun lineOfCell(lines: List<PickerLine>, cell: Int): Int =
    lines.indexOfFirst { it is PickerLine.Cells && cell >= it.first && cell < it.first + it.count }

/** 每一行的顶(像素):行高 [heights] 依次累加,行与行之间隔 [gap]。 */
internal fun lineTops(heights: List<Int>, gap: Int): List<Int> {
    val out = ArrayList<Int>(heights.size)
    var y = 0
    for (h in heights) {
        out += y
        y += h + gap
    }
    return out
}

/** 内容总高:最后一行的底;没有行 → 0。 */
internal fun contentHeight(tops: List<Int>, heights: List<Int>): Int =
    if (heights.isEmpty()) 0 else tops.last() + heights.last()

/**
 * 焦点在第 [line] 行时要露出的纵向范围 (top, bottom):这一行;**它紧挨着的上一行是分组标题时,标题一起露出**
 * (焦点从「我的」第一行往上回到内置最后一行不带标题;从内置最后一行往下进「我的」第一行,「我的」标题跟着露出)。
 */
internal fun revealRange(lines: List<PickerLine>, tops: List<Int>, heights: List<Int>, line: Int): Pair<Int, Int> {
    val top = if (line > 0 && lines[line - 1] is PickerLine.Title) tops[line - 1] else tops[line]
    return top to tops[line] + heights[line]
}

/**
 * 视窗能停的最深位置:从这一行的顶往下,剩下的内容正好放得进视窗。只停在行顶上([tops]),
 * 删图后行数变少时末页不会留一截空白;内容比视窗矮 → 0。
 */
internal fun maxScroll(tops: List<Int>, total: Int, viewport: Int): Int =
    tops.firstOrNull { total - it <= viewport } ?: 0

/**
 * 图片网格视窗的纵向位移(铁律 1:不用 verticalScroll,位移自己算;R115 从「按行数」改成「按像素 + 行顶」,
 * 因为分组标题与格子行不一样高)。**只停在行顶上**——视窗顶上永远是一整行,不会露出半截缩略图。
 * - [top, bottom](见 [revealRange])已经完整在视窗里 → 不动;
 * - 往下出界 → 停在「能让 bottom 露出来」的最浅的行顶;
 * - 往上出界 → 停在 top(标题或这一行的顶);
 * 最后夹到 [0, [maxScroll]]。没有分组标题、各行等高时与 R63 的 keepInView(首行 = 焦点行 − 可见行数 + 1)逐行相同。
 */
internal fun revealScroll(scroll: Int, top: Int, bottom: Int, viewport: Int, tops: List<Int>, total: Int): Int {
    var s = scroll
    if (bottom > s + viewport) s = tops.firstOrNull { it >= bottom - viewport } ?: top
    if (top < s) s = top
    return s.coerceAtMost(maxScroll(tops, total, viewport)).coerceAtLeast(0)
}
