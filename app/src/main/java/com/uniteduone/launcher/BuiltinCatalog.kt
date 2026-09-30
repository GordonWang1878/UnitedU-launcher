package com.uniteduone.launcher

import java.io.File

/*
 * 内置图(Ruling R115–R118,2026-09-28 Gordon 定)里**不碰 Android 类**的部分:目录清单怎么排、
 * 内置项的身份(ID)、壁纸选中值的编码与解析、屏保轮播的计算。纯 JVM 单测在 BuiltinCatalogTest。
 * Android 侧(AssetManager 列目录、从 assets 解码)在 BuiltinImages.kt。
 *
 * 内置图随 APK 附送,**直接从 assets 读,不复制进用户图库**(永远在、删不掉)。放法与命名规则见
 * docs/design/builtin-assets.md:Gordon 往 `app/src/main/assets/builtin/<分类>/` 放文件,程序按目录自动识别,不改代码。
 */

/** 内置图的三个分类 = assets/builtin/ 下的三个目录。 */
enum class BuiltinKind(val dir: String) {
    WALLPAPERS("wallpapers"),
    SCREENSAVERS("screensavers"),
    CARDS("cards"),
}

/** assets 里内置图的根目录:`builtin/<kind.dir>/<文件名>`。 */
internal const val BUILTIN_ASSET_DIR = "builtin"

/**
 * **伪路径根**。内置图以 `File("/android_asset/builtin/<分类>/<文件名>")` 的形式混进各处本来就是 `List<File>` 的
 * 列表(屏保播放器的轮播、图库网格、全屏预览、壁纸管线),这样播放器 / 过渡 / 失败表一行不用改;
 * 真正读字节的几处(解码、复制成卡片图)都经 [builtinAssetPathOf] 认出它、改从 assets 读(BuiltinImages.kt 的
 * `decodeImagePath` / `BuiltinImages.open`)。设备上没有这个目录:`isFile` / `lastModified()` / `length()` 对它恒为
 * false / 0,调用方不能拿它当普通文件删改(删图只认用户图库的文件,见 ImagePicker 的 PoolFocus)。
 * 取 WebView `file:///android_asset/` 的同一个写法,一眼看得出是「APK 里的 assets」。
 */
internal const val BUILTIN_PSEUDO_ROOT = "/android_asset/"

/** 内置图收的扩展名(小写比较)= 照片那一套;**不收视频**(放进安装包太大,见 builtin-assets.md)。 */
internal val BUILTIN_IMAGE_EXTS = ScreensaverMedia.PHOTO_EXTS

/**
 * 一张内置图。[fileName] 是 assets 目录里的原文件名。
 * **[id] = 去掉扩展名的文件名**(如 `01-dusk-city`),是它的持久化身份:壁纸选中值(`builtin:01-dusk-city`)、
 * 屏保排除集合都记它——所以发布后不能改名。
 */
data class BuiltinImage(val kind: BuiltinKind, val fileName: String) {
    val id: String get() = builtinIdOf(fileName)
    val assetPath: String get() = "$BUILTIN_ASSET_DIR/${kind.dir}/$fileName"
    /** 伪路径文件(见 [BUILTIN_PSEUDO_ROOT])。 */
    val file: File get() = File(BUILTIN_PSEUDO_ROOT + assetPath)
    /** 网格标签:去掉 `NN-` 序号前缀(序号只管排序,用户不需要看见);不合规的名字原样显示 ID。 */
    val label: String get() = builtinLabelOf(id)
}

/** 内置项 ID = 去掉扩展名的文件名;没有扩展名的原样返回。 */
internal fun builtinIdOf(fileName: String): String = fileName.substringBeforeLast('.', fileName)

/**
 * 命名规则(builtin-assets.md):`两位序号-短名`,短名用小写字母、数字、**汉字**,可用连字符分段(不以连字符开头 / 结尾、不连用)。
 * 2026-09-28 起允许汉字:Gordon 的内置图用中文作品名(「01-雨夜巴士站」),网格里显示的就是这个名字。
 */
private val CONVENTIONAL_ID = Regex("^[0-9]{2}-[a-z0-9\\p{IsHan}]+(-[a-z0-9\\p{IsHan}]+)*$")

/** 文件名(去掉扩展名之后)合不合命名规则。不合规的**照样收**,只记一条警告(见 [builtinCatalog])。 */
internal fun isConventionalBuiltinName(fileName: String): Boolean = CONVENTIONAL_ID.matches(builtinIdOf(fileName))

/** 网格标签:`01-dusk-city` → `dusk-city`;不合规的原样返回。 */
internal fun builtinLabelOf(id: String): String = if (CONVENTIONAL_ID.matches(id)) id.substring(3) else id

/**
 * `AssetManager.list()` 列出来的名字 → 内置清单:
 * - 只收 [BUILTIN_IMAGE_EXTS](扩展名大小写不敏感);`.gitkeep`、README、子目录(没有扩展名)一律忽略;
 *   点开头的隐藏文件与去掉扩展名后为空的名字(`.jpg`)也忽略。
 * - **按文件名升序**(`String` 自然序;命名规则里的两位序号让它就是想要的显示顺序;壁纸排第一的 = 默认壁纸)。
 * - 同一个 ID 出现两次(`01-a.jpg` 与 `01-a.png`)只留排序在前的那个,另一个警告后丢掉——ID 必须唯一,
 *   否则选中值 / 排除集合认不出是哪一张。
 * - 名字不合命名规则([isConventionalBuiltinName])的照样收,[warn] 一条。
 */
internal fun builtinCatalog(kind: BuiltinKind, names: List<String>, warn: (String) -> Unit = {}): List<BuiltinImage> {
    val seen = HashSet<String>()
    val out = ArrayList<BuiltinImage>()
    for (name in names.sorted()) {
        if (name.startsWith('.')) continue
        if (extensionOf(name) !in BUILTIN_IMAGE_EXTS) continue
        val id = builtinIdOf(name)
        if (id.isEmpty()) continue
        if (!seen.add(id)) {
            warn("内置图 ${kind.dir}/$name 与同名的另一张 ID 重复($id),忽略")
            continue
        }
        if (!isConventionalBuiltinName(name)) warn("内置图 ${kind.dir}/$name 不合命名规则「NN-短名」(照样收)")
        out += BuiltinImage(kind, name)
    }
    return out
}

/**
 * 伪路径 → assets 里的路径(`builtin/<分类>/<文件名>`);不是内置图的伪路径 → null。
 * 只认 `BUILTIN_PSEUDO_ROOT + builtin/` 开头、且没有 `..` 这一**段**的(防御:伪路径只由 [BuiltinImage.file] 产生)。
 * 按段判而不是按子串:文件名里连着两个点(手工改名打错的 `05-极光..jpg`)[builtinCatalog] 照样收,
 * 按子串拒掉的话网格里有这一格、却解不出图也选不中。
 */
internal fun builtinAssetPathOf(path: String): String? {
    if (!path.startsWith(BUILTIN_PSEUDO_ROOT + BUILTIN_ASSET_DIR + "/")) return null
    val rel = path.removePrefix(BUILTIN_PSEUDO_ROOT)
    return rel.takeIf { r -> r.split('/').none { it == ".." } }
}

// ---- 三语显示名(R137)------------------------------------------------------------------------------

/**
 * **内置图的三语显示名**(R137,2026-09-30 Gordon:「内置图名字在英文和繁体界面仍是简体中文」)。名字表是
 * `assets/builtin/names.txt`,与图放在一起:一行一张,`分类/ID = 简体 | 繁體 | English`,`#` 开头是注释。
 * ID 就是 [BuiltinImage.id](文件名去掉扩展名),所以图改名时这里跟着改。
 *
 * 取哪一列**不按系统语言猜**,读字符串资源 `builtin_names_lang`(三份 strings.xml 各写 `zh-CN` / `zh-TW` / `en`):
 * Android 给界面挑了哪一份 strings.xml,名字就取哪一列,界面与名字不会一个繁体一个简体。
 * 表里没有这一张、或那一列空着:退回简体那一列,再退回去掉序号的文件名([BuiltinImage.label])——新图忘了补名字也照样显示。
 * 单测 BuiltinNamesTest 查「每张图都有三语名字、表里没有多余的行」,漏补在构建前就会被拦下。
 */
internal const val BUILTIN_NAMES_ASSET = "$BUILTIN_ASSET_DIR/names.txt"

/** 名字表的三列(顺序即 names.txt 的列序);值与 `builtin_names_lang` 字符串资源的取值一一对应。 */
internal val BUILTIN_NAME_LANGS = listOf("zh-CN", "zh-TW", "en")

/** 解析 names.txt:`分类/ID` → 各列(去掉首尾空白,缺的列就缺着)。空行、注释、没有 `=` 的行忽略。 */
internal fun parseBuiltinNames(text: String): Map<String, List<String>> {
    val out = LinkedHashMap<String, List<String>>()
    for (raw in text.removePrefix("\uFEFF").lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim()
        if (key.isNotEmpty()) out[key] = line.substring(eq + 1).split('|').map { it.trim() }
    }
    return out
}

/** 这张图在 [lang] 界面下的名字;退回规则见 [BUILTIN_NAMES_ASSET]。认不出的 [lang] 按第一列(简体)取。 */
internal fun builtinDisplayName(names: Map<String, List<String>>, image: BuiltinImage, lang: String): String {
    val cols = names["${image.kind.dir}/${image.id}"] ?: return image.label
    val i = BUILTIN_NAME_LANGS.indexOf(lang).coerceAtLeast(0)
    return cols.getOrNull(i)?.takeIf { it.isNotEmpty() }
        ?: cols.getOrNull(0)?.takeIf { it.isNotEmpty() }
        ?: image.label
}

/** 伪路径文件 → 它是哪一张内置图;不是 → null。分类目录名不认识的也返回 null。 */
internal fun builtinImageOf(file: File): BuiltinImage? {
    val rel = builtinAssetPathOf(file.path) ?: return null
    val parts = rel.split('/')
    if (parts.size != 3) return null
    val kind = BuiltinKind.entries.firstOrNull { it.dir == parts[1] } ?: return null
    return BuiltinImage(kind, parts[2]).takeIf { it.fileName.isNotEmpty() }
}

// ---- 选中值(R116)----------------------------------------------------------------------------------

/**
 * 壁纸选中值(settings.json 的 `wallpaperFile`)的前缀:`builtin:<ID>` = 内置那张;别的非空值 = 用户图库里的文件名
 * (旧 settings 原样兼容)。内置与用户图库同名(都叫 `01-a`)不冲突:一个存 `builtin:01-a`,一个存 `01-a.jpg`。
 */
internal const val BUILTIN_CHOICE_PREFIX = "builtin:"

/** 选中的是哪一张:内置(按 ID)还是用户图库(按文件名)。 */
sealed class ImageChoice {
    data class Builtin(val id: String) : ImageChoice()
    data class Mine(val fileName: String) : ImageChoice()
}

internal fun encodeBuiltinChoice(id: String): String = BUILTIN_CHOICE_PREFIX + id

/** 空 → null(没选过);`builtin:<ID>` → 内置;其余 → 用户图库的文件名。 */
internal fun decodeImageChoice(value: String): ImageChoice? = when {
    value.isEmpty() -> null
    value.startsWith(BUILTIN_CHOICE_PREFIX) && value.length > BUILTIN_CHOICE_PREFIX.length ->
        ImageChoice.Builtin(value.removePrefix(BUILTIN_CHOICE_PREFIX))
    else -> ImageChoice.Mine(value)
}

/**
 * 此刻该显示哪张壁纸(R116):
 * 1. 选中的还在 → 就是它。内置按 ID 在不在 [builtinIds] 里认;用户图库按 [mineExists]。
 *    (防御:用户自己的图恰好叫 `builtin:xxx.jpg` 而清单里没有 `xxx.jpg` 这个 ID 时,按用户图库那个文件认。)
 * 2. 没选过(空)、或选中的那张没了 → **默认 = 内置清单第一张**(不随机;[builtinIds] 已按文件名排好)。
 * 3. 内置清单为空 → 回落用户图库按名排序的第一张([firstMine],R61 以来的行为);也没有 → null(首页画纯深色)。
 * 只解析、不写盘:默认壁纸不写进 settings.json,清单第一张换了(Gordon 发新版改了顺序)默认跟着换。
 */
internal fun resolveWallpaperChoice(
    value: String,
    builtinIds: List<String>,
    mineExists: (String) -> Boolean,
    firstMine: () -> String?,
): ImageChoice? {
    when (val c = decodeImageChoice(value)) {
        is ImageChoice.Builtin -> {
            if (c.id in builtinIds) return c
            if (mineExists(value)) return ImageChoice.Mine(value)
        }
        is ImageChoice.Mine -> if (mineExists(c.fileName)) return c
        null -> Unit
    }
    builtinIds.firstOrNull()?.let { return ImageChoice.Builtin(it) }
    return firstMine()?.let { ImageChoice.Mine(it) }
}

// ---- 屏保轮播(R117)--------------------------------------------------------------------------------

/**
 * 屏保轮播 = 参与的内置(清单顺序,去掉 [excluded] 里的 ID)+ 用户图库(照片与视频,按文件名,R100–R104 规则不变)。
 * 泛型只为单测:Android 侧 T = File(内置那几张是伪路径文件)。
 */
internal fun <T> screensaverPlaylist(builtins: List<T>, idOf: (T) -> String, excluded: Set<String>, mine: List<T>): List<T> =
    builtins.filter { idOf(it) !in excluded } + mine

/** 「不参与轮播 / 加入轮播」:在集合里就拿掉,不在就加上。 */
internal fun toggleExcluded(excluded: Set<String>, id: String): Set<String> =
    if (id in excluded) excluded - id else excluded + id
