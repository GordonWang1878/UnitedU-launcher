package com.uniteduone.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics

private sealed class PickerItem {
    data class Original(val bitmap: Bitmap) : PickerItem()
    data class Library(val file: File) : PickerItem()
    /**
     * 「内置」块里的一张(Ruling R115):随 APK 附送,直接从 assets 读,不在用户图库里。只能预览 / 选用,
     * **删不掉**(长按那一支按 [PoolFocus.Builtin] 分流:屏保图库里弹「不参与 / 加入轮播」,别处长按 = 确定)。
     */
    data class Builtin(val image: BuiltinImage) : PickerItem()
    /**
     * 首格「＋ 从手机添加」(Ruling R63;R115 起是「我的」块的首格)。**不是图片**:不预览、不删
     * (长按那一支只认图片,见 PickerGrid 的 onFocusedItem 上报)、不选定,确定键只打开扫码页。
     */
    object AddFromPhone : PickerItem()
}

/**
 * 屏保图库网格上报的「现在聚焦的是哪一张」(长按 / MENU 用,R115):「我的」那张(长按 → 删除确认框)
 * 或内置那张(长按 / MENU → 「不参与 / 加入轮播」胶囊菜单,R117)。「＋」与失焦都报 null。
 */
sealed class PoolFocus {
    data class Mine(val file: File) : PoolFocus()
    data class Builtin(val image: BuiltinImage) : PoolFocus()
}

/**
 * 缩略图下标签的固定高度(R63 顺手修)。网格的翻页位移按量出来的行高算,
 * 格子行必须等高;而 10 sp 的中文标签比英文 / 数字文件名高 2 dp(模拟器实测 14.5 vs 12.5 dp)。首格「从手机添加」
 * 在中文界面下恒是中文,那一行因此恒比别的行高,翻页时累计差出几个像素、把顶上那行的卡片边裁掉。
 */
private val THUMB_LABEL_HEIGHT = 18.dp   // R134:标签 10 → 12 sp,行高随之 16 → 18

/** 分组标题「内置 / 我的」那一行的固定高度(R115;同 [THUMB_LABEL_HEIGHT] 的理由:中英文字高不一样,行高要钉死)。 */
private val SECTION_TITLE_HEIGHT = 28.dp   // R134:分组标题 13 → 17 sp

/** 不参与轮播的内置屏保图(R117)画得多暗:缩略图内容的不透明度。 */
private const val EXCLUDED_THUMB_ALPHA = 0.35f

private fun listImages(directory: File): List<File> =
    directory.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
        ?.sortedBy { it.name }
        ?: emptyList()

/**
 * 某一分类的内置清单(R115)。已经列过(启动时 MainActivity 在 IO 线程预热过)→ 同一帧就有;没有 → IO 线程列一次,
 * 列完之前是 null——调用方**等它到了才挂网格**:落点种子只在网格挂载那一刻读一次,格子下标又要把内置格算进去。
 */
@Composable
private fun rememberBuiltins(kind: BuiltinKind): List<BuiltinImage>? {
    val ctx = LocalContext.current
    val listed by produceState(BuiltinImages.cached(kind), kind) {
        if (value == null) value = withContext(Dispatchers.IO) { BuiltinImages.list(ctx, kind) }
    }
    return listed
}

/**
 * 内置图在当前界面语言下的名字(R137):names.txt 里取 `builtin_names_lang` 那一列,表里没有就退回文件名。
 * 网格标签、内置图菜单的页名、全屏预览左下角都用它,三处同一个名字。
 */
@Composable
private fun builtinName(image: BuiltinImage): String {
    val ctx = LocalContext.current
    val lang = stringResource(R.string.builtin_names_lang)
    return remember(image, lang) { builtinDisplayName(BuiltinImages.names(ctx), image, lang) }
}

/**
 * [onAddFromPhone] / [landing] 见 [PickerGrid]。没有图片时不再是单独的空态(原来那句「用 adb push 复制图片」
 * 已删,R63):网格只剩「＋」一格,标题下一行写「还没有图片可选」。
 * R115/R116:上块「内置」= assets/builtin/wallpapers/ 里的图(确定 = 选用,选中值记 `builtin:<ID>`),下块「我的」照旧。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun WallpaperPicker(
    directory: File,
    title: String = stringResource(R.string.picker_wallpaper_title),
    nonce: Int = 0,
    onSelect: (File) -> Unit,
    onDismiss: () -> Unit,
    onAddFromPhone: () -> Unit,
    landing: List<String>? = null,
) {
    // 从扫码页回来时本组合是新挂上的(扫码页替换了它),这里自然按盘上实况重读。
    val files = remember(directory) { listImages(directory) }
    val builtins = rememberBuiltins(BuiltinKind.WALLPAPERS)
    val ghost = LocalPageGhost.current
    androidx.activity.compose.BackHandler(enabled = !ghost) { onDismiss() }
    PickerPage {
        if (builtins != null) PickerGrid(
            builtins = builtins.map { PickerItem.Builtin(it) },
            items = files.map { PickerItem.Library(it) },
            title = title,
            columns = PICKER_PHOTO_COLUMNS,
            thumbWidth = PICKER_PHOTO_WIDTH,
            thumbHeight = PICKER_PHOTO_HEIGHT,
            nonce = nonce,
            onSelectFile = onSelect,
            onRestoreOriginal = null,
            onDismiss = onDismiss,
            emptyHint = stringResource(R.string.picker_no_images),
            onAddFromPhone = onAddFromPhone,
            landing = landing,
        )
    }
}

/**
 * 换卡片图。R118:上块「内置」= assets/builtin/cards/ 里的通用装饰图,任何应用都能选,照原图显示(不叠应用图标 / 名字),
 * 横幅判据照 R88/R107;选中时把**内容**复制成 icons/<包名>.png(与「我的」同一条路,内置与我的同名也不冲突)。
 * 下块「我的」=「＋」+「恢复原图」+ 卡片图库。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun IconPicker(
    directory: File,
    originalIcon: Bitmap?,
    nonce: Int = 0,
    onSelect: (File) -> Unit,
    onRestoreOriginal: () -> Unit,
    onDismiss: () -> Unit,
    onAddFromPhone: () -> Unit,
    landing: List<String>? = null,
) {
    val files = remember(directory) { listImages(directory) }
    val builtins = rememberBuiltins(BuiltinKind.CARDS)
    // R63 起不再截到 16 项(TvHome 时期网格不能滚的遗留;现在是自算位移的视窗,换壁纸 / 屏保图库本来就不截):
    // 有了「从手机添加」,卡片图库超过 16 张是常态,截掉的话刚传的那张在网格里根本不存在、焦点也落不上去。
    val items = remember(originalIcon, files) {
        buildList {
            if (originalIcon != null) add(PickerItem.Original(originalIcon))
            addAll(files.map { PickerItem.Library(it) })
        }
    }
    val ghost = LocalPageGhost.current
    androidx.activity.compose.BackHandler(enabled = !ghost) { onDismiss() }
    PickerPage {
        if (builtins != null) PickerGrid(
            builtins = builtins.map { PickerItem.Builtin(it) },
            items = items,
            title = stringResource(R.string.picker_card_image_title),
            columns = PICKER_CARD_COLUMNS,
            thumbWidth = PICKER_CARD_WIDTH,
            thumbHeight = PICKER_CARD_HEIGHT,
            nonce = nonce,
            onSelectFile = onSelect,
            onRestoreOriginal = onRestoreOriginal,
            asCard = true,
            onDismiss = onDismiss,
            emptyHint = stringResource(R.string.picker_no_images),
            onAddFromPhone = onAddFromPhone,
            landing = landing,
        )
    }
}

/**
 * 图片视窗的首行(铁律 1:不用 verticalScroll,位移自己算)。焦点行还在视窗里就不动;
 * 往上出界 → 焦点行成为首行;往下出界 → 焦点行成为视窗里最后一个完整可见的行。
 * [visibleRows] ≤ 0 按 1 算(防御:量出来之前不会用到)。
 * R115 起图片网格改用按像素的 [revealScroll](分组标题与格子行不一样高);这一份留给编辑页的纵向位移([EditScreen])。
 */
internal fun keepInView(focusedRow: Int, firstVisible: Int, visibleRows: Int): Int {
    val v = visibleRows.coerceAtLeast(1)
    return when {
        focusedRow < firstVisible -> focusedRow
        focusedRow >= firstVisible + v -> focusedRow - v + 1
        else -> firstVisible
    }
}

/** 一行在测量表里的键(按内容定,不按行下标:删图后行下标不变、内容换了的行不会拿到旧高度)。 */
private fun lineKey(line: PickerLine): String = when (line) {
    is PickerLine.Title -> "t-${line.section}"
    is PickerLine.Cells -> "c-${line.first}"
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PickerGrid(
    /** 上块「内置」(R115);空 = 上块整块不画,「我的」也不画标题(版式与 R63 相同)。 */
    builtins: List<PickerItem.Builtin>,
    /** 下块「我的」里「＋」之后的格子:图库图片(换卡片图时第一项是「恢复原图」)。 */
    items: List<PickerItem>,
    title: String,
    columns: Int,
    thumbWidth: Dp,
    thumbHeight: Dp,
    nonce: Int = 0,
    /** 确定键选中一张图:「我的」给图库文件,内置给它的伪路径文件(见 [BUILTIN_PSEUDO_ROOT],调用方按 [builtinImageOf] 认)。 */
    onSelectFile: (File) -> Unit,
    onRestoreOriginal: (() -> Unit)?,
    onDismiss: () -> Unit,
    /**
     * 网格上盖着别的浮层(全屏预览 / 删图确认框 / 内置图胶囊菜单):它们自己负责焦点(铁律 3),网格的初始焦点循环让路——
     * 否则回到前台时(MainActivity.onResume → focusNonce++)这里会把焦点从预览底下抢走(M5 遗留)。
     */
    covered: Boolean = false,
    /**
     * 当前聚焦的是哪一张(M5 spec §5 长按删图;R117 起还有内置图的胶囊菜单):得到报 [PoolFocus],失去 / 「＋」/
     * 「恢复原图」报 null;只有屏保图库传它。全屏预览 / 确认框 / 菜单盖上来时网格失焦 → 报 null → 长按不生效。
     */
    onFocusedItem: ((PoolFocus?) -> Unit)? = null,
    /** 没有任何图片(内置与我的都空,网格只剩「＋」)时标题下的一行说明;null = 不写。 */
    emptyHint: String? = null,
    /**
     * 网格底下那一行操作提示(R131)。选图(壁纸 / 卡片图)是「按返回键取消」;屏保图库不是在选东西,
     * 写的是「按确定预览,长按可删除或关闭」——长按那两件事原来哪里都没写(R135 起提示在页头,返回键不用再写)。
     */
    backHint: Int = R.string.picker_back_to_cancel,
    /**
     * 「我的」首格「＋ 从手机添加」的确定键(Ruling R63)。打开扫码页时 MainActivity 把本网格**替换**掉
     * (pickerTarget 换成扫码页),关掉扫码页再重新挂上本网格——所以回来时文件列表是重扫过的。
     */
    onAddFromPhone: () -> Unit,
    /**
     * 从扫码页回来时的落点种子(R63):本次新上传的文件名(按上传先后),挂载时经 [landingCell] 算成格子下标,
     * **只在挂载那一刻读一次**(同编辑页的 editTarget 种子)。null = 普通打开,落第 0 格(有内置图时是第一张内置图,
     * 没有时是「＋」)。
     */
    landing: List<String>? = null,
    /**
     * 缩略图按**卡面**画(R88,只有「换卡片图」网格传 true):不像横幅的图([fitsAsBanner] 不过)居中、铺边缘色底,
     * 与换上去之后首页那张卡同一个样子;false(壁纸 / 屏保)照旧按 16:9 裁满。内置装饰图同样按卡面画(R118)。
     */
    asCard: Boolean = false,
    /** 不参与轮播的内置屏保图 ID(R117,只有屏保图库传):这几格画暗 + 「已关」角标。 */
    excludedBuiltins: Set<String> = emptySet(),
) {
    // 淡出中的残影(R108 的约定,R136 起选图页也淡入淡出):当作被盖住——定位效果与看门狗让路(守卫与 key 读的是
    // 同一个合并后的 covered,铁律 6);下面每一格再 canFocus = false、点击与焦点上报一律不接。
    val ghost = LocalPageGhost.current
    @Suppress("NAME_SHADOWING")
    val covered = covered || ghost
    // 格子 = 内置 + 「＋」+ 我的(换算见 PickerCells.kt)。下面 focusedIdx / holderIdx / focusRequesters / interactionSources
    // 全用格子下标,只有 onFocusedItem 上报、落点种子这两处要认「是哪一类」。
    val builtinCount = builtins.size
    val cells = remember(builtins, items) { pickerCells(builtins, PickerItem.AddFromPhone, items) }
    val lines = remember(builtinCount, cells.size, columns) { pickerLines(builtinCount, cells.size - builtinCount, columns) }
    val firstCellsLine = lines.indexOfFirst { it is PickerLine.Cells }
    val lastCellsLine = lines.indexOfLast { it is PickerLine.Cells }
    val focusRequesters = remember(cells.size) {
        List(cells.size.coerceAtLeast(1)) { FocusRequester() }
    }
    // 效果里读的必须是**当前**这一份 requester(同 GearMenu 的写法):items.size 一变 remember 就换新表;
    // 定位效果的 key 里带着 focusRequesters,换表会重跑没问题,但看门狗的 key 里没有它——如果直接捕获
    // 看门狗启动那一刻的表,新表在它循环跑到一半时才到,它还在挂空的旧表上重试。
    val requesters by rememberUpdatedState(focusRequesters)
    // R63 落点种子:挂载时算一次(items 此刻已是重扫过的列表——屏保图库等 produceState 扫完才挂网格;
    // 内置清单也是到了才挂,见 rememberBuiltins)。
    val seedCell = remember {
        landing?.let { landingCell(items.map { (it as? PickerItem.Library)?.file?.name }, it, builtinCount) }
    }
    var focusedIdx by remember { mutableStateOf(seedCell ?: 0) }
    /**
     * **目标冻结**(铁律 5:目标与当前位置分开)。非 null 时,只有「夹紧后等于它」的那一格得到焦点才改写
     * [focusedIdx];别的格得到焦点(系统 / Compose 自己派的)一律不算数。两种来源:
     * - **落点种子**(R63):扫码页一拆,Compose 可能抢在定位效果之前把焦点先给左上角那一格(格 0:有内置图时是
     *   第一张内置图,没有时是「＋」),那次焦点事件若照常写 focusedIdx,种子在被用到之前就没了。种子就是格 0 时不冻
     *   (抢焦点的正是它)。R115 前格 0 恒是「＋」,所以原来写的是「种子是「＋」时不冻」。
     * - **浮层盖上**(预览 / 删图确认框 / 内置图胶囊菜单 / 删后重扫,即 [covered]):盖上那一刻冻住当时的目标。R63 实测复现
     *   (6 次删图 2 次落错格):确认框节点被拆、或删掉末张时那一格的节点被拆,持有焦点的节点一没,系统当场
     *   把焦点派给网格里另一格(实测落到过视窗左上角那张、或上一行某张),这一下原来会直接改写 focusedIdx,
     *   随后重跑的定位效果就「忠实地」把焦点送到了错的那格。
     * 定位效果那一轮跑完(落没落下都算——不能让一次落空把焦点记忆永久冻住)就解冻。
     */
    var frozenTarget by remember { mutableStateOf(seedCell?.takeIf { it != 0 }) }
    // fix round 1:删图后 focusedIdx 可能落在新列表的界外(删的正是末张)——统一在这里夹一次,
    // 定位效果、看门狗、渲染时的 focused 判据都读这一个,不再各处各夹各的。删空时夹到「＋」(R63;「我的」在最后,
    // 删图只让尾巴变短,内置格与「＋」的下标不变)。
    val clampedFocusedIdx = clampCell(focusedIdx, focusRequesters.size)
    /**
     * **现在**持有焦点的那一格(只信控件自报,铁律 4);null = 网格里没有。与 [focusedIdx] 分开(铁律 5):
     * 后者是「回来时落哪」的目标,失焦时不清;这一个失焦就清,长按判据、定位效果的退出条件、
     * 看门狗都只认它(fix round 1:换掉了原来只增不减的 landed——旧格被销毁时没人把它拨回
     * false,循环会在假的「已经落地」上直接跳过)。
     */
    var holderIdx by remember { mutableStateOf<Int?>(null) }

    // 遗留 #8(实测复现,2026-09-19 模拟器):长按识别在 MainActivity.dispatchKeyEvent(组合树外),
    // 它把长按之后那次 DPAD_CENTER 的 key-up 吞掉——clickable 默认内建的 MutableInteractionSource
    // 因此永远等不到配对的 Release/Cancel,indication(按压暗色)永久卡住,直到进程重来都不会自己解开。
    // 每格自带一份 interactionSource + 记下「当前是否有未配对的 Press」;确认框 / 预览关闭那一刻
    // (covered 从 true 变 false,复用下面的定位效果)照 press 补发一个 Cancel 解开。
    // **SnapshotStateList,不是普通 List**(fix round 1):补发 Cancel 只清得掉 indication 观察到的
    // 那条流,foundation 的 ClickableNode 自己另有一份「当前哪个键按着」的记录绑在旧 source 对象上,
    // 外部发不进去——不换一个新对象,同一格下一次确定键会被当成「这键还按着」而丢一次按压视觉
    // (只丢那一下,不是从此往后每次都哑掉:ClickableNode 处理完那次被吞的按键,内部记录就翻篇了)。
    // 换成可写的 list,发完 Cancel 顺手把那一格的元素替换成新对象,下面的收集效果会跟着 source
    // 这个 key 自动重订阅,ClickableNode 也会因为 interactionSource 参数变了而丢掉旧记录。
    val interactionSources = remember(cells.size) {
        List(cells.size.coerceAtLeast(1)) { MutableInteractionSource() }.toMutableStateList()
    }
    val pendingPress = remember(cells.size) {
        arrayOfNulls<PressInteraction.Press?>(cells.size.coerceAtLeast(1))
    }
    interactionSources.forEachIndexed { i, source ->
        LaunchedEffect(source) {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pendingPress[i] = interaction
                    is PressInteraction.Release, is PressInteraction.Cancel -> pendingPress[i] = null
                }
            }
        }
    }

    // 铁律 1:原来的 heightIn(max = 600.dp).verticalScroll(...) 换成「裁剪视窗 + 整块自算位移」,
    // 与 HomeScreen 纵向那套同一招。视窗外的行照常组合(图库张数有限,可接受)。
    val density = LocalDensity.current
    // 一份 val 两处用(fix round 1):下面 verticalArrangement 的行距与这里的位移算术必须是
    // 同一个数,分写两处迟早改一处漏一处、量出来的间距和实际渲染的间距对不上。
    // P2(交互测试第二轮):聚焦格照首页卡片放大 + 描边,纵向溢出 = appFocusOverflow(缩略图高)≈ 8.3dp(96 高,R126 描边 1.5 起;此前 8.8);
    // 行距要大于它,聚焦描边才碰不到上一行的标签,视窗下面的纵向裁剪留白(= rowGap)也才盖得住描边。
    val rowGap = 16.dp
    val rowGapPx = with(density) { rowGap.roundToPx() }
    val focusOverflowPx = with(density) { GtvLayout.appFocusOverflow(thumbHeight.value).dp.toPx() }
    /**
     * 每一行的实测高度(按 [lineKey],R115:分组标题与格子行不一样高,不再只量首行)。只增不清:
     * 键按内容定,删图后仍在的行高度不变,新出现的行挂上时 onSizeChanged 补量。没量到的行按 0 算,此时不位移。
     */
    val lineHeights = remember { mutableStateMapOf<String, Int>() }
    /** 视窗实测高度(≤ 600dp)。 */
    var viewportPx by remember { mutableStateOf(0) }
    /** 视窗顶离内容顶多少像素:只在某格报「得到焦点」时由 [revealScroll] 推进(只停在行顶上)。 */
    var scrollPx by remember { mutableStateOf(0) }
    // 调用时现读量出来的状态(R63):onFocusChanged 的 lambda 是上一次组合时捕获的,首帧还没量到行高时
    // 它手里的是全 0 的几何,种子若在那一刻落到视窗外的行上就不会翻页。现读就没有这个时差。
    fun geometryNow(): Triple<List<Int>, List<Int>, Boolean> {
        val heights = lines.map { lineHeights[lineKey(it)] ?: 0 }
        val measured = viewportPx > 0 && heights.all { it > 0 }
        return Triple(heights, lineTops(heights, rowGapPx), measured)
    }
    /** 焦点落在 [cell] 时视窗该停哪(没量完 → 原地不动)。 */
    fun revealNow(cell: Int): Int {
        val (heights, tops, measured) = geometryNow()
        val line = lineOfCell(lines, cell)
        if (!measured || line < 0) return scrollPx
        val (top, bottom) = revealRange(lines, tops, heights, line)
        return revealScroll(scrollPx, top, bottom, viewportPx, tops, contentHeight(tops, heights))
    }
    val (heightsNow, topsNow, measuredNow) = geometryNow()
    // 兜底:焦点在量完之前就落下了(上面的现读也救不了「那一刻真的还没量到」),量完之后补一次翻页。
    LaunchedEffect(measuredNow, viewportPx, heightsNow) {
        if (measuredNow) holderIdx?.let { scrollPx = revealNow(it) }
    }
    // 删图后行数变少:夹回合法范围,末页不会留一截空白
    val shownScroll = if (measuredNow) scrollPx.coerceAtMost(maxScroll(topsNow, contentHeight(topsNow, heightsNow), viewportPx)) else 0
    val yShift by animateDpAsState(
        targetValue = with(density) { (-shownScroll).toDp() },
        // R136(动效统一):图片网格的翻页与首页换行、编辑页、所有应用页同一根 browse 弹簧(Theme.browseShiftSpec)。
        // R27 时这里刻意留在 tween(300, MotionEasing)——当时它是面板里的小网格;现在是整屏网格,与所有应用页同一个版式,
        // 翻页手感理应一样。
        animationSpec = Theme.browseShiftSpec(),
        label = "pickerYShift",
    )

    // **定位效果**:进入 / nonce 变 / 浮层刚让路时把焦点送到 clampedFocusedIdx。
    // fix round 1(reviewer 实测复现,HEAD 4deae96 删完聚焦到背后盖住的设置页):删图后的重扫是
    // 异步的(produceState 在 IO 线程跑,见 [ScreensaverPoolViewer]),covered 变 false 那一刻
    // items 常常还是删除前的旧列表——原来只以 (nonce, covered) 为 key,请求打在这批旧
    // focusRequesters 上;新列表一到,`remember(items.size)` 把 focusRequesters 整表换新,而这个
    // 效果的 key 都没变、不会重跑,从此没有人再请求焦点。**把 focusRequesters 也编进 key**:
    // 它一变(items.size 变,亦即任何一次删除)这里就跟着重跑,用的是换新之后那一批。
    // 浮层盖上 → 冻住当时的目标(见 frozenTarget)。盖上时网格里的格子只会失焦、不会得焦,此刻的 focusedIdx 就是对的。
    LaunchedEffect(covered) {
        if (covered && frozenTarget == null) frozenTarget = focusedIdx
    }

    LaunchedEffect(nonce, covered, focusRequesters) {
        if (covered) return@LaunchedEffect
        // 遗留 #8:浮层刚让路,可能留了一个卡住的 Press(不一定是当前聚焦格——长按发生时聚焦的是
        // 被长按的那格,covered 期间焦点没有别处可去,所以就是它自己)。没有卡住的格子 press 为 null,不发。
        pendingPress.forEachIndexed { i, press ->
            if (press != null) {
                // tryEmit(Cancel) 只是让这条流的观察者(indication)体面收尾——保险丝而已;
                // 真正解开 ClickableNode 内部「这键还按着」那份记录的是下面换新对象这一步。
                interactionSources[i].tryEmit(PressInteraction.Cancel(press))
                pendingPress[i] = null
                // 见 interactionSources 声明处的注释:换新对象,ClickableNode 才会真正忘掉这一格
                // 的按键状态,下一次确定键才会重新出现按压视觉。
                interactionSources[i] = MutableInteractionSource()
            }
        }
        // 退出条件是目标格自己报了 holderIdx == i(铁律 2),不再信只增不减的 landed——
        // 旧格被销毁那一刻不会有人把它拨回未落地,循环会在假的「已经落地」上直接跳过。
        val i = clampedFocusedIdx
        var frames = 0
        while (holderIdx != i && frames < 60) {
            withFrameNanos { }
            runCatching { requesters[i].requestFocus() }
            frames++
        }
        // 这一轮跑完就解冻(见 frozenTarget 的 KDoc)。冻结期间落地那一下的翻页被闸住了,这里按实际持有者补一次。
        frozenTarget = null
        holderIdx?.let { scrollPx = revealNow(it) }
    }

    // **焦点看门狗**:上面那条管「我想去哪」,这条管「焦点莫名其妙没了」——旧格随删除被销毁、
    // 定位效果又恰好在新列表到达前已经打满 60 帧(或全打在行将销毁的旧格上、次次抛异常)时,
    // 谁都不会再补请求。不靠「在猜得到的几个时刻补请求」(铁律 3),镜像 SettingsScreen(以及
    // GearMenu 那份同形状的看门狗)的写法:守卫 covered / holderIdx==null 都在 key 里(铁律 6);
    // 每轮最多 60 帧封顶,再丢一次焦点时 key 翻转、自动重新武装(铁律 7)——不会在请求注定落空
    // (比如 items 为空)时每帧空转到网格关掉为止。读 requesters 而不是 focusRequesters:这条效果
    // 的 key 里没有 focusRequesters,循环已经在跑的时候如果删图换了表,直接捕获的旧表会挂空。
    LaunchedEffect(holderIdx == null, covered) {
        if (covered || holderIdx != null) return@LaunchedEffect
        // D-pad 换格 / 节点销毁时得、失可能分属相邻两帧:旧格先报丢、新格下一帧才报得,
        // 中间那一帧的 null 不算真丢(与 HomeScreen / SettingsScreen 看门狗同一手法)。
        repeat(3) { withFrameNanos {} }
        if (holderIdx != null) return@LaunchedEffect
        var frames = 0
        while (holderIdx == null && frames < 60) {
            runCatching { requesters[focusedIdx.coerceIn(0, requesters.lastIndex)].requestFocus() }
            withFrameNanos { }
            frames++
        }
    }

    // 上报只派生、不缓存(同 HomeScreen 的 onFocusedCard):删图后同一格换了文件、没有焦点事件,
    // items 变 → 这里按新列表再报一次。离开组合(关图库 / 删空换成空态)报 null,不留过期文件。
    // 「＋」与「恢复原图」报 null(R63):长按那一支(MainActivity.dispatchKeyEvent 的 poolBare)因此不成立。
    if (onFocusedItem != null) {
        val ghostNow by rememberUpdatedState(ghost)
        LaunchedEffect(holderIdx, cells, ghost) {
            if (ghost) return@LaunchedEffect
            onFocusedItem(
                when (val item = holderIdx?.let { cells.getOrNull(it) }) {
                    is PickerItem.Library -> PoolFocus.Mine(item.file)
                    is PickerItem.Builtin -> PoolFocus.Builtin(item.image)
                    else -> null
                },
            )
        }
        // 残影离场时不报:关掉又马上打开时,它的这一声 null 会盖掉新页刚报上去的那一张(同所有应用页)。
        DisposableEffect(Unit) { onDispose { if (!ghostNow) onFocusedItem(null) } }
    }

    // **R135 换皮**:页头 + 网格的整屏页(与所有应用页同一个版式)——页名 31 sp 在左上角基准线上,操作提示跟在页名右边
    // (原来在面板最底下、11 sp 深灰);下面的网格铺满屏宽。此前是屏幕中间一块 ≤ 700 dp 的面板、页名 16 sp。
    // 网格的焦点 / 翻页机制一行没动:视窗仍是「量出来的高度 + 整块自算位移」。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = GtvLayout.CONTENT_KEYLINE.dp, end = GtvLayout.CONTENT_KEYLINE.dp, top = AppsPageLayout.PAGE_TOP.dp),
    ) {
        PageHeader(
            title = title,
            // 一张图都没有时说明换成「还没有图片可选」(原来是标题下单独一行)。
            hint = if (emptyHint != null && builtins.isEmpty() && items.isEmpty()) emptyHint else stringResource(backHint),
        )

        Box(
            modifier = Modifier
                // 视窗拿页头以下的全部高度(带权重的子项最后量);底下留 PICKER_BOTTOM_PAD,末行的标签不贴屏幕底边。
                .weight(1f)
                .padding(bottom = PICKER_BOTTOM_PAD)
                // P2:原来是 clipToBounds()。聚焦格放大后描边 + 柔光伸出格子外(描边横向 ≈ 12.5dp、纵向 ≈ 8.8dp),
                // 贴着视窗边的那一格会被裁掉。横向放开(左右由外层面板 16dp 内边距 + 圆角裁剪兜底,描边溢出 < 16dp);
                // 纵向**固定**外扩一个聚焦溢出量 appFocusOverflow(缩略图高),与页码无关。
                // 不能按翻页位置决定「这一侧要不要裁」(第二轮初版这么做过,夜间评审 Important):位置在得焦那一刻
                // 就跳到新页,yShift 却要几百毫秒才走到——翻回首页 / 翻到末页的途中,整行缩略图从视窗外滑过、
                // 压在页头上。外扩量 ≈ 9dp < 页头下方的留白,描边完整、碰不到文字;代价是柔光在视窗上下边被硬切。
                .drawWithContent {
                    val m = focusOverflowPx
                    clipRect(left = -size.width, top = -m, right = size.width * 2, bottom = size.height + m) {
                        this@drawWithContent.drawContent()
                    }
                }
                .onSizeChanged { viewportPx = it.height },
        ) {
        Column(
            modifier = Modifier
                // **必须 unbounded**(铁律 1 的另一半):不放开测量,超出 600dp 的行会被压扁 / 量成 0 高,
                // offset 发生在测量之后救不回来
                .wrapContentHeight(Alignment.Top, unbounded = true)
                // 位移在布局阶段读(R140 复审):写成 offset(y = …) 是组合期读,弹簧走的那半秒整个网格逐帧重组。
                .offset { IntOffset(0, yShift.roundToPx()) },
            verticalArrangement = Arrangement.spacedBy(rowGap),
        ) {
        lines.forEachIndexed { lineIdx, line ->
            val k = lineKey(line)
            key(k) {
                when (line) {
                    // 分组标题(R115):不可聚焦,上下方向键从上一块的最后一行直接走到下一块的第一行
                    is PickerLine.Title -> SectionTitle(
                        text = stringResource(
                            if (line.section == PickerSection.BUILTIN) R.string.picker_section_builtin
                            else R.string.picker_section_mine,
                        ),
                        modifier = Modifier.onSizeChanged { lineHeights[k] = it.height },
                    )
                    is PickerLine.Cells -> Row(
                        // P2:格距照首页 CARD_GAP(20dp)——大于放大后的横向溢出(≤ 12.5dp),聚焦描边不压到邻格。
                        horizontalArrangement = Arrangement.spacedBy(GtvLayout.CARD_GAP.dp),
                        modifier = Modifier.onSizeChanged { lineHeights[k] = it.height },
                    ) {
                        for (colIdx in 0 until line.count) {
                            val idx = line.first + colIdx
                            val item = cells[idx]
                            val focused = clampedFocusedIdx == idx
                            ThumbCard(
                                item = item,
                                focused = focused,
                                thumbWidth = thumbWidth,
                                thumbHeight = thumbHeight,
                                asCard = asCard,
                                dimmed = item is PickerItem.Builtin && item.image.id in excludedBuiltins,
                                cellModifier = Modifier
                                    .focusRequester(focusRequesters[idx])
                                    .focusProperties {
                                        if (ghost) canFocus = false
                                        // 左右到行头 / 行尾钉死(不斜跳到别的行);上下只在整个网格的第一 / 最后一行钉死,
                                        // 两块之间的上下交给默认的二维搜索(标题不可聚焦,自然跳过)
                                        if (colIdx == 0) left = FocusRequester.Cancel
                                        if (colIdx == line.count - 1) right = FocusRequester.Cancel
                                        if (lineIdx == firstCellsLine) up = FocusRequester.Cancel
                                        if (lineIdx == lastCellsLine) down = FocusRequester.Cancel
                                    }
                                    .onFocusChanged {
                                        // 冻结期间只认目标那一格(铁律 5,见 frozenTarget)。
                                        if (it.isFocused && frozenTarget.let { t -> t == null || clampCell(t, cells.size) == idx }) {
                                            frozenTarget = null
                                            focusedIdx = idx
                                            scrollPx = revealNow(idx)
                                        }
                                        // 得失顺序保护(同 HomeScreen.report):只有「本格仍是持有者」时 lost 才作废,
                                        // 新格先报 got、旧格后报 lost 时不会把新格抹掉。
                                        if (it.isFocused) holderIdx = idx else if (holderIdx == idx) holderIdx = null
                                    }
                                    // P2:indication 不再用 LocalIndication——默认的那份在「聚焦」时给整格盖一层 10% 黑,
                                    // 盖在格子布局框上、不跟着缩略图放大,放大出来的那一圈边和标签底下各出现一块明暗
                                    // 不一的矩形(模拟器截图实测)。聚焦由 gtvAppFocusFrame 表达,同首页卡片;按压
                                    // 不再有暗色(首页卡片也没有)。interactionSource 与遗留 #8 的补发 Cancel 照旧保留:
                                    // 它还管着 ClickableNode 内部「这键还按着」的记录。
                                    .clickable(
                                        interactionSource = interactionSources[idx],
                                        indication = null,
                                    ) {
                                        if (!ghost) when (item) {
                                            is PickerItem.Original -> onRestoreOriginal?.invoke()
                                            is PickerItem.Library -> onSelectFile(item.file)
                                            is PickerItem.Builtin -> onSelectFile(item.image.file)
                                            PickerItem.AddFromPhone -> onAddFromPhone()
                                        }
                                    },
                            )
                        }
                    }
                }
            }
        }
        }
        }
    }
}

/** 选图页的底:与所有整屏页同一个 [GtvTokens.MenuBg](R135;原来是 85% 黑的蒙版,后面透出压暗的首页)。 */
@Composable
private fun PickerPage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().focusGroup()
            .background(GtvTokens.ScrimOverlay).pageBackdrop(),   // R142
    ) { content() }
}

// R135 网格几何:屏宽 960 − 左右基准线 2 × 58 = 844 dp。
// 照片(壁纸、屏保图库)一行 4 张:4 × 196 + 3 × 20 = 844;卡片图一行 5 张:5 × 152 + 4 × 20 = 840。
// 此前面板里是 3 × 170 与 4 × 130。
private const val PICKER_PHOTO_COLUMNS = 4
private val PICKER_PHOTO_WIDTH = 196.dp
private val PICKER_PHOTO_HEIGHT = 110.dp
private const val PICKER_CARD_COLUMNS = 5
private val PICKER_CARD_WIDTH = 152.dp
private val PICKER_CARD_HEIGHT = 86.dp
private val PICKER_BOTTOM_PAD = 20.dp

/** 分组标题「内置 / 我的」(R115):不可聚焦,固定行高([SECTION_TITLE_HEIGHT])。 */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        style = Type.section,
        maxLines = 1,
        modifier = modifier.height(SECTION_TITLE_HEIGHT).wrapContentHeight(Alignment.CenterVertically),
    )
}

/** 缩略图读好的结果,连同产出它的 [item](见 ThumbCard 里 R5 的说明)。[videoMs] 只有视频格有(读不到时长为 0)。 */
private class ThumbData(val item: PickerItem, val bitmap: Bitmap?, val backdrop: Int?, val videoMs: Long?)

/**
 * 视频格的角标(Ruling R104):「▶ 0:08」;时长读不到写「▶ 视频」。半透明黑底圆角,贴右下角,
 * 画在缩略图框里,随聚焦放大。不可聚焦。
 */
@Composable
private fun VideoBadge(durationMs: Long?, modifier: Modifier = Modifier) {
    val text = ScreensaverMedia.formatDuration(durationMs ?: 0) ?: stringResource(R.string.picker_video_badge)
    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        BasicText(text = "▶ $text", style = Type.micro.copy(color = Color.White))
    }
}

/**
 * 不参与轮播的内置屏保图的角标(R117):「已关」,样式同 [VideoBadge](半透明黑底圆角),贴左上角,
 * 画在缩略图框里、随聚焦放大。内置图不收视频,两个角标不会同时出现。
 */
@Composable
private fun OffBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        BasicText(text = stringResource(R.string.picker_builtin_off_badge), style = Type.micro.copy(color = Color.White))
    }
}

@Composable
private fun ThumbCard(
    item: PickerItem,
    focused: Boolean,
    thumbWidth: Dp,
    thumbHeight: Dp,
    asCard: Boolean = false,
    /** 不参与轮播的内置屏保图(R117):缩略图内容压暗到 [EXCLUDED_THUMB_ALPHA] + 左上「已关」角标;聚焦时照样暗,状态一眼看得出。 */
    dimmed: Boolean = false,
    cellModifier: Modifier = Modifier,
) {
    if (item is PickerItem.AddFromPhone) {
        AddFromPhoneCard(focused, thumbWidth, thumbHeight, cellModifier)
        return
    }
    val modifier = cellModifier
    // 有文件的格子:「我的」是图库文件,内置是伪路径文件(R115,解码经 decodeImagePath 改读 assets)。
    val file: File? = when (item) {
        is PickerItem.Library -> item.file
        is PickerItem.Builtin -> item.image.file
        else -> null
    }
    // R5(实测复现):produceState 换 key 时只重启协程,value 不会先跳回 null——删图导致列表整体
    // 前移一格时,这一格的 item 已经指向新文件,但旧协程解出来的旧 Bitmap 还挂在 value 上,新协程
    // 解码完成前的这几帧会显示上一个占用者的缩略图(标题与 onFocusedItem 那时已经是新文件了)。
    // 把状态连同产出它的 item 一起存;渲染时只认「item 与当前一致」那一份,过期的那份自然被滤掉,
    // 不需要手动清零——效果与「换 key 就重置」等价,但不用在协程开头多写一次 value = null。
    // 第三项 = 卡面底色(R88,只在 asCard 时算):null = 当横幅裁满,非 null = 当图标居中、铺这个底色。
    val isVideo = item is PickerItem.Library && ScreensaverMedia.isVideo(item.file.name)
    val thumb by produceState<ThumbData?>(null, item, asCard) {
        value = withContext(Dispatchers.IO) {
            var srcW = 0; var srcH = 0
            var videoMs: Long? = null
            val bmp = when {
                item is PickerItem.Original -> item.bitmap.also { srcW = it.width; srcH = it.height }
                file == null -> null
                // 视频(R104):首帧缩略图 + 时长,MediaMetadataRetriever,带缓存
                isVideo -> VideoThumbs.load(file).also { videoMs = it.durationMs }.frame
                else -> runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    decodeImagePath(file.absolutePath, opts)
                    val w = opts.outWidth; val h = opts.outHeight
                    if (w <= 0 || h <= 0) return@runCatching null
                    srcW = w; srcH = h
                    val sample = maxOf(1, minOf(w / 240, h / 135))
                    val decOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                    decodeImagePath(file.absolutePath, decOpts)
                }.getOrNull()
            }
            val backdrop = if (asCard && bmp != null) {
                runCatching { Apps.cardBackdropFor(bmp, srcW, srcH) }.getOrNull()
            } else null
            ThumbData(item, bmp, backdrop, videoMs)
        }
    }

    val label = when (item) {
        PickerItem.AddFromPhone -> ""   // 不会走到(上面已分流),只为 when 穷尽
        is PickerItem.Original -> stringResource(R.string.picker_restore_original)
        is PickerItem.Library -> item.file.nameWithoutExtension
        is PickerItem.Builtin -> builtinName(item.image)
    }
    val highlight = LocalThemeColors.current.highlight
    val accent = LocalThemeColors.current.accent

    Column(
        modifier = thumbCellModifier(modifier, focused, thumbWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(thumbLabelGap(thumbHeight)),
    ) {
        // PickerItem.Library / Builtin 是 data class,按文件 / 内置图判等:只认还没被换下去的那一份。
        val current = thumb?.takeIf { it.item == item }
        val bmp = current?.bitmap
        val backdrop = current?.backdrop
        val contentAlpha = if (dimmed) EXCLUDED_THUMB_ALPHA else 1f
        // 三种画法共用一个框(R117 起为了能叠「已关」角标;布局与原来的裸 Image 相同):
        // ① R88 当图标画——与首页 AppCardImage 的非横幅分支同一个画法(卡高见方的框里 Fit 居中 + 边缘色底);
        // ② 照片按 16:9 裁满;③ 视频格(R104)与「还在读」的占位:视频有首帧就铺满,右下角标「▶ 时长」随格子一起放大。
        val frameBg = when {
            bmp != null && backdrop != null -> Color(backdrop)
            bmp != null && !isVideo -> Color.Transparent
            else -> Theme.ThumbPlaceholderBackground
        }
        Box(
            modifier = Modifier
                .gtvAppFocusFrame(focused, accent, THUMB_CORNER)
                .fillMaxWidth()
                .height(thumbHeight)
                .clip(RoundedCornerShape(THUMB_CORNER))
                .background(frameBg),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null && backdrop != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(thumbHeight).alpha(contentAlpha),
                )
            } else if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(contentAlpha),
                )
            } else if (current == null) {
                BasicText("…", style = Type.caption)
            }
            if (isVideo && current != null) VideoBadge(current.videoMs, Modifier.align(Alignment.BottomEnd))
            if (dimmed) OffBadge(Modifier.align(Alignment.TopStart))
        }

        ThumbLabel(label, focused)
    }
}

/** 缩略图下的名字(R134:10 → 12 sp;聚焦 = 主题 highlight + Medium)。固定行高见 [THUMB_LABEL_HEIGHT]。 */
@Composable
private fun ThumbLabel(label: String, focused: Boolean) {
    BasicText(
        text = label,
        style = Type.caption.copy(
            color = if (focused) LocalThemeColors.current.highlight else Ink.Label,
            fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().height(THUMB_LABEL_HEIGHT).wrapContentHeight(),
    )
}

/**
 * P2(交互测试 2026-09-23 第二轮):图片网格的聚焦格与首页卡片同一套表现——缩略图本身走
 * [gtvAppFocusFrame](放大 [GtvLayout.APP_FOCUS_SCALE] + 贴着放大后边缘的 accent 描边 + 柔光),不再是
 * 「整格底色亮 16%」。所以格子不再有自己的底板,也**不能 clip**:放大与描边都画在缩略图布局框之外。
 * 聚焦格 zIndex 抬高,同一行里柔光盖在左右邻格之上(同首页 AppCard)。焦点逻辑(requester / 上报)全在
 * 调用方传进来的 [cell] 里,这里一行没动。
 */
private fun thumbCellModifier(cell: Modifier, focused: Boolean, thumbWidth: Dp): Modifier =
    cell.zIndex(if (focused) 1f else 0f).width(thumbWidth)

/** 缩略图与下方标签的间距 = 放大后纵向溢出(缩放增量一半 + 描边间隙 + 描边),聚焦描边不压标签。 */
private fun thumbLabelGap(thumbHeight: Dp): Dp = GtvLayout.appFocusOverflow(thumbHeight.value).dp

/** 缩略图圆角 = 首页卡片圆角(P2:同一套聚焦几何,描边与缩略图同心)。 */
private val THUMB_CORNER = GtvLayout.CARD_CORNER.dp

/**
 * 「＋ 从手机添加」格(Ruling R63):与图片格同尺寸、同聚焦样式(P2 起:缩略图放大 + 描边,标签变 highlight),
 * 缩略图位置画一个「＋」(同编辑页行尾 AddCard 的字形)。
 */
@Composable
private fun AddFromPhoneCard(focused: Boolean, thumbWidth: Dp, thumbHeight: Dp, modifier: Modifier) {
    val highlight = LocalThemeColors.current.highlight
    val accent = LocalThemeColors.current.accent
    val label = stringResource(R.string.picker_add_from_phone)
    Column(
        modifier = thumbCellModifier(modifier, focused, thumbWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(thumbLabelGap(thumbHeight)),
    ) {
        Box(
            modifier = Modifier.gtvAppFocusFrame(focused, accent, THUMB_CORNER).fillMaxWidth().height(thumbHeight)
                .clip(RoundedCornerShape(THUMB_CORNER)).background(Theme.ThumbPlaceholderBackground)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            PlusGlyph(color = if (focused) highlight else Ink.Label, size = 30.dp)
        }
        ThumbLabel(label, focused)
    }
}

/**
 * 屏保图库:上块「内置」(assets/builtin/screensavers/,R115/R117)+ 下块「我的」(library/screensavers/ 里的全部照片与视频)。
 * 确定键全屏预览(内置与我的连成一串,左右键翻);长按「我的」缩略图 → 删除确认框(M5 spec §5);
 * 长按或 MENU「内置」缩略图 → 胶囊菜单「不参与轮播 / 加入轮播」(R117;不参与的那几格画暗 + 「已关」角标)。
 * 长按 / MENU 的识别在 MainActivity.dispatchKeyEvent,这里只画与上报([onFocusedItem])。
 * [refresh] = 图库版本,删图后 +1,文件列表据此重读。
 * [deleteTarget] 非 null 时在自身之上画 [ConfirmDialog]:它自己负责焦点(nonce + focusedBtn,默认在取消);
 * 关掉后(删除 / 取消都 focusNonce++)由 [PickerGrid] 接回焦点——删掉的那格由下一张补上,删的是末张
 * 就夹到上一张(读的时候夹,见 PickerGrid 的 clampedFocusedIdx)。**重扫是异步的**(下面的
 * produceState 在 IO 线程跑):covered 变 false 那一刻 items 常常还是删除前的旧列表,新列表一到 focusRequesters
 * 因 items.size 变而整表换新——PickerGrid 的定位效果把 focusRequesters 也编进 key 应对这一步,
 * 另配一个只认「有没有人持有焦点」的看门狗兜底(fix round 1,镜像已退役的两栏设置页 SettingsScreen 那一份的写法,现行对应是 SettingsShell.kt 的 CapsuleColumn;
 * 这一段实测复现过焦点漏给背后盖住的设置页,删最后一张 / 小图库删任意一张都会中招)。
 * 删空后「我的」只剩「＋ 从手机添加」一格(R63 起不再换成单独的空态),焦点由同一套定位效果 / 看门狗夹到「＋」上。
 * [builtinMenu] 非 null 时在自身之上画 [GearMenu](同长按卡片菜单:nonce 初始循环 + holder == null 看门狗,自己负责焦点);
 * 关掉后(选了 / 返回都 focusNonce++)网格按冻结的目标接回同一张内置图。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ScreensaverPoolViewer(
    nonce: Int = 0,
    refresh: Int = 0,
    onFocusedItem: (PoolFocus?) -> Unit = {},
    deleteTarget: File? = null,
    onConfirmDelete: (File) -> Unit = {},
    onCancelDelete: () -> Unit = {},
    /** 胶囊菜单开着的那张内置图(R117);null = 没开。 */
    builtinMenu: BuiltinImage? = null,
    /** 不参与轮播的内置图 ID(settings.json `excludedBuiltinScreensavers`)。 */
    excludedBuiltins: Set<String> = emptySet(),
    onToggleBuiltin: (BuiltinImage) -> Unit = {},
    onCloseBuiltinMenu: () -> Unit = {},
    onDismiss: () -> Unit,
    onAddFromPhone: () -> Unit,
    landing: List<String>? = null,
) {
    val ctx = LocalContext.current
    // IO 线程扫,走 safeScan(与播放器同一条「失败记日志、退回 null」的规则,内部调的还是
    // scanScreensaverLibrary)——这里原来是 runCatching{...}.getOrDefault(emptyList()),失败
    // 被默默吞掉、不留日志,和主线程按自己一套扩展名表列目录的老口径一样,都统一掉了。
    // 内置清单同一趟列(R115,进程内缓存,通常已预热)。
    // produceState 的值跨 key 保留:删图后 refresh+1 重扫期间仍显示旧列表,不会闪一下空态。null = 首次还没扫完。
    // 值里带上「这是按哪一版 refresh 扫的」(R63):删图后到新列表到达之前 [rescanning] 为真,网格按 covered 让路
    // ——不然定位效果会先落在旧列表那一格,新列表一到那格的节点(删的是末张时)被拆,焦点又被系统派走。
    val scannedFor by produceState<Pair<Int, Pair<List<BuiltinImage>, List<File>>>?>(null, refresh) {
        value = refresh to withContext(Dispatchers.IO) {
            BuiltinImages.list(ctx, BuiltinKind.SCREENSAVERS) to (safeScan(ctx) ?: emptyList())
        }
    }
    val scanned = scannedFor?.second
    val rescanning = scannedFor != null && scannedFor?.first != refresh
    val builtins = scanned?.first ?: emptyList()
    val files = scanned?.second ?: emptyList()
    // 全屏预览翻的是「内置 + 我的」连成的一串(不含「＋」);内置那几张是伪路径文件,ScreensaverSlot 经 decodeImagePath 读 assets
    val previewFiles = remember(builtins, files) { builtins.map { it.file } + files }
    var previewIndex by remember { mutableStateOf(-1) }
    val total = builtins.size + files.size

    val ghost = LocalPageGhost.current
    androidx.activity.compose.BackHandler(enabled = !ghost) { onDismiss() }
    PickerPage {
        when {
            // 首次扫描中(几十毫秒):只有底色。**网格要等扫完才挂**——R63 的落点种子只在挂载那一刻读一次,
            // 挂早了拿到的是空列表,新传的图永远找不到。
            scanned == null -> Unit
            else -> PickerGrid(
                builtins = builtins.map { PickerItem.Builtin(it) },
                items = files.map { PickerItem.Library(it) },
                title = if (total == 0) stringResource(R.string.picker_screensaver_title)
                else pluralStringResource(R.plurals.picker_screensaver_pool_title, total, total),
                columns = PICKER_PHOTO_COLUMNS,
                thumbWidth = PICKER_PHOTO_WIDTH,
                thumbHeight = PICKER_PHOTO_HEIGHT,
                nonce = nonce,
                onSelectFile = { file ->
                    val idx = previewFiles.indexOf(file)
                    if (idx >= 0) previewIndex = idx
                },
                onRestoreOriginal = null,
                onDismiss = onDismiss,
                covered = previewIndex >= 0 || deleteTarget != null || builtinMenu != null || rescanning,
                onFocusedItem = onFocusedItem,
                emptyHint = stringResource(R.string.picker_no_screensavers),
                backHint = R.string.picker_back_to_close,
                onAddFromPhone = onAddFromPhone,
                landing = landing,
                excludedBuiltins = excludedBuiltins,
            )
        }
    }

    // **R136:图库上叠着的三种浮层是一摞,淡入淡出**([OverlayStack]):全屏预览、删除确认页、内置图的胶囊菜单。
    // 它们的 BackHandler 比查看器的更晚注册,返回键先关它们;关掉后淡出的残影不收返回键(各页面照 LocalPageGhost 让路)。
    // 残影画的是关掉前最后那一份([PoolOverlay] 把文件名、菜单上那一颗的字都带上)。
    val galleryTitle = stringResource(R.string.picker_screensaver_title)
    val target = deleteTarget
    val menuTarget = builtinMenu
    val overlay: PoolOverlay? = when {
        target != null -> PoolOverlay.Delete(target)
        menuTarget != null -> PoolOverlay.Menu(menuTarget, off = menuTarget.id in excludedBuiltins)
        previewIndex in previewFiles.indices -> PoolOverlay.Preview(previewFiles, previewIndex)
        else -> null
    }
    OverlayStack(state = overlay, layerKey = { it.layer }) { ov ->
        when (ov) {
            is PoolOverlay.Preview -> ScreensaverPreview(
                files = ov.files,
                startIndex = ov.start,
                nonce = nonce,
                onDismiss = { previewIndex = -1 },
            )
            // 删除确认页(spec §5)
            is PoolOverlay.Delete -> ConfirmDialog(
                title = stringResource(R.string.pool_delete_title),
                body = stringResource(R.string.pool_delete_body, ov.file.name),
                okLabel = stringResource(R.string.pool_delete_ok),
                cancelLabel = stringResource(R.string.dialog_cancel),
                nonce = nonce,
                eyebrow = galleryTitle,
                onOk = { onConfirmDelete(ov.file) },
                onCancel = onCancelDelete,
            )
            // 内置图的胶囊菜单(R117):只有一颗——参与轮播时「不参与轮播」,已关的「加入轮播」。
            is PoolOverlay.Menu -> GearMenu(
                items = listOf(
                    MenuItem(
                        label = stringResource(if (ov.off) R.string.pool_builtin_include else R.string.pool_builtin_exclude),
                        hint = "",
                        action = { onToggleBuiltin(ov.image) },
                    ),
                ),
                onDismiss = onCloseBuiltinMenu,
                nonce = nonce,
                title = builtinName(ov.image),
                eyebrow = galleryTitle,
            )
        }
    }
}

/** 屏保图库上叠着的那一层(R136,给 [OverlayStack] 当状态)。 */
private sealed interface PoolOverlay {
    val layer: String
    /** 全屏预览:同一次预览里左右翻页不换层([layer] 不含页码),翻页由预览自己交叉淡化。 */
    class Preview(val files: List<File>, val start: Int) : PoolOverlay {
        override val layer get() = "preview"
    }
    class Delete(val file: File) : PoolOverlay {
        override val layer get() = "delete:${file.name}"
    }
    class Menu(val image: BuiltinImage, val off: Boolean) : PoolOverlay {
        override val layer get() = "menu:${image.id}"
    }
}

/** @param nonce 外层焦点 nonce(MainActivity.focusNonce):回到前台等时刻 +1,预览据此重新落焦点。 */
@Composable
private fun ScreensaverPreview(
    files: List<File>,
    startIndex: Int,
    nonce: Int,
    onDismiss: () -> Unit,
) {
    var index by remember { mutableStateOf(startIndex) }
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // 淡出中的残影(R136):不可聚焦、不收返回键、不再请求焦点。
    val ghost = LocalPageGhost.current

    androidx.activity.compose.BackHandler(enabled = !ghost) { onDismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(fr)
            .focusProperties { if (ghost) canFocus = false }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> { if (index > 0) index--; true }
                        Key.DirectionRight -> { if (index < files.lastIndex) index++; true }
                        else -> false
                    }
                } else false
            }
            .focusable()
    ) {
        Crossfade(
            targetState = index,
            animationSpec = tween(500),
            label = "previewCrossfade",
        ) { idx ->
            val f = files.getOrNull(idx)
            // 视频(R104):循环静音播放;淡出去的那一个 active = false,当场截帧释放(同一时刻一个播放器,R102)
            if (f != null && ScreensaverMedia.isVideo(f.name)) PreviewVideo(f, active = idx == index)
            else ScreensaverSlot(f, Theme.ScreensaverIntervalMs)
        }

        var showInfo by remember { mutableStateOf(true) }
        LaunchedEffect(index) {
            showInfo = true
            delay(3000)
            showInfo = false
        }
        if (showInfo) {
            val file = files[index]
            // 内置图(R115)显示与网格里同一个名字(R137 起按界面语言取),用户的图显示文件名
            val builtin = builtinImageOf(file)
            val name = if (builtin != null) builtinName(builtin) else file.nameWithoutExtension
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.BottomStart) {
                BasicText(
                    text = "$name  (${index + 1}/${files.size})",
                    style = Type.body.copy(color = Color.White.copy(alpha = 0.7f)),
                )
            }
        }
    }

    // 以 nonce 为 key(原来是 Unit,只落一次地):回到前台时 nonce 变,预览自己把焦点要回来。
    // 判据是自报的 focused(得失都报),不是只写 true 的 landed——已经有焦点时这里一次都不请求。
    LaunchedEffect(nonce, ghost) {
        if (ghost) return@LaunchedEffect
        var frames = 0
        while (!focused && frames < 60) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            frames++
        }
    }
}

/**
 * 屏保图库全屏预览里的一个视频(Ruling R104):循环、静音;首帧出来之前垫网格那张首帧缩略图,
 * 播不了就在画面中央写一行「无法播放这个视频」(屏保里是静默跳过,这里是在看它,得说一声)。
 * 不可聚焦——按键仍由 [ScreensaverPreview] 的 Box 收,焦点机制一行没变。
 */
@Composable
private fun PreviewVideo(file: File, active: Boolean) {
    var failed by remember(file.absolutePath) { mutableStateOf(false) }
    val controller = remember(file.absolutePath) {
        VideoController(file.absolutePath, looping = true, capMs = null).also { it.onFailed = { failed = true } }
    }
    LaunchedEffect(controller) {
        val poster = withContext(Dispatchers.IO) { VideoThumbs.load(file).frame }
        if (poster != null && controller.still == null && !controller.showingFrame) controller.still = poster.asImageBitmap()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        VideoSurface(controller, active, Modifier.fillMaxSize())
        if (failed) {
            BasicText(
                text = stringResource(R.string.preview_video_failed),
                style = Type.label.copy(color = Color.White.copy(alpha = 0.85f)),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
