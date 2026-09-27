package com.uniteduone.launcher

import android.content.Context
import android.util.Log

/**
 * 待机时进入无操作状态后屏幕上显示什么。
 */
enum class IdleContent { CLOCK_ONLY, BLACK, NO_FADE }

/**
 * settings.json 形如(字段顺序不重要,见 [toJson] 里的固定顺序仅为可读性):
 *   {"rowCount":3,"cardsPerRow":6,"showTitles":false, ...}
 * 任何字段缺失或非法都在 [parseSettings] 里回落到下面这份默认值,并夹到合法区间——
 * 手改坏的文件也生不出非法状态(比如 rowCount=99、cardsPerRow=7 这种)。
 */
data class Settings(
    /** 不用,行数以 layout.json 为准(M4b spec §0-5)。 */
    val rowCount: Int = 3,
    val cardsPerRow: Int = 6,
    val showTitles: Boolean = false,
    val showInputRow: Boolean = false,
    /** 主题色预设 id(R62:默认淡紫 "purple";旧 id 读盘时经 [ThemePresets.migrateId] 换成新的)。 */
    val themePresetId: String = ThemePresets.DEFAULT_ID,
    val followWallpaperColor: Boolean = false,
    // (「主题化卡片」开关 themedCards 2026-09-23 删掉,gtv spec R58:卡片的亮度 / 饱和度已由 R49 淡化统一压下来;
    //  旧文件里的键按未知键忽略,同 wallpaperThemed。)
    val clock24hFollowSystem: Boolean = true,
    val showDate: Boolean = true,
    val idleAfterMs: Long = 180_000L,
    val idleContent: IdleContent = IdleContent.CLOCK_ONLY,
    /**
     * 进入待机后再过多久进自定义屏保(M5 spec §0「屏保启动」);0 = 关。待机时长为「关」时改从最后一次按键算
     * (两个时刻的换算在 [standbyPlan])。合法值见 [VALID_SCREENSAVER_AFTER_MS];旧文件没有这个键 → 默认 5 分。
     */
    val screensaverAfterMs: Long = 300_000L,
    /** 屏保换图间隔(「屏保轮播设置」,桌面与系统屏保共用)。合法值见 [VALID_SCREENSAVER_INTERVAL_MS]。 */
    val screensaverIntervalMs: Long = 30_000L,
    // ---- M3 壁纸(spec §1)。默认全零/空 ⇒ 首页观感与 M2 逐位一致 ----
    // (「主题化壁纸」开关 wallpaperThemed 2026-09-16 整个删掉:壁纸不再染色;旧文件里的键按未知键忽略。)
    /** library/wallpapers/ 里的文件名;空 = 未指定(解析顺序见 Wallpapers.resolveSource)。 */
    val wallpaperFile: String = "",
    // (「壁纸自动切换」wallpaperRotateMs / wallpaperRotatedAt 2026-09-23 删掉,gtv spec R61;旧文件里的键按未知键忽略。)
    /** 模糊 0–100,步 10。 */
    val wallpaperBlur: Int = 0,
    /** 亮度 −50…+50,步 10:0 = 原片,负 = 压暗,正 = 提亮(2026-09-16 Gordon 定,取代原 0–100「压暗」)。 */
    val wallpaperBrightness: Int = 0,
    /**
     * 卡片饱和度 0–100(%),步 10(R70,2026-09-23 设置页改版新增)。R49 卡片淡化原来写死在
     * `GtvLayout.CARD_FADE_SATURATION`(30%),缺省值照抄——旧文件没有这个键时观感零变化。
     */
    val cardSaturation: Int = DEFAULT_CARD_SATURATION,
    /** 卡片亮度 50–100(%),步 5(R70)。缺省 75 = 原 `GtvLayout.CARD_FADE_BRIGHTNESS`。 */
    val cardBrightness: Int = DEFAULT_CARD_BRIGHTNESS,
    /**
     * 卡片不透明度 40–100(%),步 10(R86,2026-09-27 Gordon 定)。只作用于**未聚焦**的卡片,焦点卡恒 100%;
     * 缺省 100 = 加这一项之前的样子,旧文件没有这个键时观感零变化。
     */
    val cardOpacity: Int = DEFAULT_CARD_OPACITY,
    /** 上次打开「添加应用」列表的时刻(epoch ms);firstInstallTime 晚于它的应用算「新」。0 = 未初始化(首启时写成当时)。 */
    val newAppsSeenAt: Long = 0L,
    /** 界面语言;合法值见 [VALID_LANGUAGES]。`"system"` = 跟随系统语言。 */
    val language: String = "system",
    /**
     * 是否已过完引导流程——三态:`null` = 文件里压根没写这个键(老用户,§8 靠它和
     * `false`(新用户走过引导但中途没走完/明确重置)区分);`true` = 走完了。
     * 三态靠"缺省"表达,见 [toJson] 只在非 null 时写这个键。
     */
    val onboardingDone: Boolean? = null,
)

// `internal`(而非 `private`):这两张表是 cardsPerRow / idleAfterMs 的唯一合法取值集合,
// 既用来夹取(见下面 snap 系列函数),也是 [SettingsScreen] 里对应分段控件的选项顺序 ——
// 一份表两处读,才不会有人手改一处、另一处悄悄漂移(2026-09-15 复审前两处各写了一份字面量)。
internal val VALID_CARDS_PER_ROW = intArrayOf(5, 6, 8)
internal val VALID_IDLE_AFTER_MS = longArrayOf(0L, 60_000L, 180_000L, 300_000L, 600_000L)
internal val VALID_LANGUAGES = listOf("system", "zh-CN", "zh-TW", "en")

// M5(spec §3):「屏保启动」「屏保轮播设置」两行的唯一合法取值,同样一份表两处读(夹取 + 分段控件的档位顺序)。
internal val VALID_SCREENSAVER_AFTER_MS = longArrayOf(0L, 60_000L, 300_000L, 600_000L, 1_800_000L)
internal val VALID_SCREENSAVER_INTERVAL_MS = longArrayOf(30_000L, 60_000L, 300_000L)

// R70:卡片淡化两条滑块的取值范围。缺省值 = R49 原来写死的常量(×100 取整),旧文件缺键时观感零变化。
internal const val DEFAULT_CARD_SATURATION = 30
internal const val DEFAULT_CARD_BRIGHTNESS = 75
internal const val CARD_SATURATION_MIN = 0
internal const val CARD_SATURATION_STEP = 10
internal const val CARD_BRIGHTNESS_MIN = 50
internal const val CARD_BRIGHTNESS_STEP = 5
// R86:卡片不透明度 40–100 步 10(7 档),缺省 100 = 不透明。下限 40:再低卡片就和壁纸糊在一起,认不出是哪个应用。
internal const val DEFAULT_CARD_OPACITY = 100
internal const val CARD_OPACITY_MIN = 40
internal const val CARD_OPACITY_STEP = 10

/** 0..100 夹取后四舍五入到 10 的倍数(滑块 11 档);解析不出数字 → 该字段的默认值(真机调参后默认可能非零)。 */
private fun clampPercentStep10(v: Int?, default: Int): Int =
    if (v == null) default else ((v.coerceIn(0, 100) + 5) / 10) * 10

/** 卡片饱和度:0..100 夹取后四舍五入到 10 的倍数;解析不出 → 30(R70)。 */
private fun clampCardSaturation(v: Int?): Int =
    if (v == null) DEFAULT_CARD_SATURATION else ((v.coerceIn(0, 100) + 5) / 10) * 10

/** 卡片亮度:50..100 夹取后四舍五入到 5 的倍数;解析不出 → 75(R70)。 */
private fun clampCardBrightness(v: Int?): Int =
    if (v == null) DEFAULT_CARD_BRIGHTNESS else Math.round(v.coerceIn(CARD_BRIGHTNESS_MIN, 100) / 5f) * 5

/** 卡片不透明度:40..100 夹取后四舍五入到 10 的倍数;解析不出(含缺键)→ 100(R86)。 */
internal fun clampCardOpacity(v: Int?): Int =
    if (v == null) DEFAULT_CARD_OPACITY else ((v.coerceIn(CARD_OPACITY_MIN, 100) + 5) / 10) * 10

private fun clampEpoch(v: Long?): Long = (v ?: 0L).coerceAtLeast(0L)

/** −50..50 夹取后四舍五入到 10 的倍数(双向滑块 11 档);解析不出 → 默认。 */
private fun clampBrightnessStep10(v: Int?, default: Int): Int =
    if (v == null) default else Math.round(v.coerceIn(-50, 50) / 10f) * 10

/**
 * 壁纸文件名只能指向 library/wallpapers/ 里的一个条目:含路径分隔符或 `..` 的一律当没写。
 * `internal`:Wallpapers.select 写入前也走同一道清洗。
 */
internal fun sanitizeWallpaperFileName(name: String?): String {
    val n = name?.trim() ?: return ""
    if (n.isEmpty() || n.contains('/') || n.contains('\\') || n.contains("..")) return ""
    return n
}

private fun clampRowCount(v: Int?): Int = (v ?: 3).coerceIn(1, 5)

private fun snapCardsPerRow(v: Int?): Int {
    if (v == null) return 6
    return VALID_CARDS_PER_ROW.minByOrNull { kotlin.math.abs(it - v) } ?: 6
}

private fun snapIdleAfterMs(v: Long?): Long =
    if (v != null && VALID_IDLE_AFTER_MS.contains(v)) v else 180_000L

/** 不在表里(手改的 12345、解析不出数字)→ 默认 5 分(spec §3「解析时不在表里就夹回默认」)。 */
private fun snapScreensaverAfterMs(v: Long?): Long =
    if (v != null && VALID_SCREENSAVER_AFTER_MS.contains(v)) v else 300_000L

/** 不在表里 → 默认 30 秒。 */
private fun snapScreensaverIntervalMs(v: Long?): Long =
    if (v != null && VALID_SCREENSAVER_INTERVAL_MS.contains(v)) v else 30_000L

// ---- 极简、零依赖的“扁平 JSON”读写 -----------------------------------------
// org.json 在纯 JVM 单元测试里用不了:Android 的 unit-test 桩 jar 对它每个方法调用都抛
// RuntimeException("not mocked"),除非上 Robolectric(实测验证过,见任务报告)。而 Settings
// 就是个没有嵌套的 key-value 扁平对象,犯不上为此在 build.gradle 里另加一个 JSON 依赖
// (任务范围也只许改这 3 个文件)——手写一个只认这几个字段的极简 tokenizer 就够,
// 还顺带保证了 parseSettings 对任何输入都不抛。

private fun extractRaw(json: String, key: String): String? {
    // 匹配数字 / true / false 这类裸值,一路取到下一个逗号或右花括号为止。
    val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*([^,}]+)").find(json) ?: return null
    return m.groupValues[1].trim()
}

private fun extractString(json: String, key: String): String? {
    val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
        ?: return null
    return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
}

private fun extractInt(json: String, key: String): Int? = extractRaw(json, key)?.toIntOrNull()
private fun extractLong(json: String, key: String): Long? = extractRaw(json, key)?.toLongOrNull()
private fun extractBoolean(json: String, key: String): Boolean? = when (extractRaw(json, key)) {
    "true" -> true
    "false" -> false
    else -> null
}

/**
 * 纯函数,不碰 Android:任何输入(空串、乱码、半截 JSON)都不抛异常。
 * 缺失的字段回落默认值;数字类字段额外夹到合法区间——就算文件是手改出来的非法值
 * (rowCount=99、idleAfterMs=12345),读出来的 [Settings] 也一定是合法状态。
 */
fun parseSettings(json: String): Settings {
    val d = Settings()
    return try {
        Settings(
            rowCount = clampRowCount(extractInt(json, "rowCount")),
            cardsPerRow = snapCardsPerRow(extractInt(json, "cardsPerRow")),
            showTitles = extractBoolean(json, "showTitles") ?: d.showTitles,
            showInputRow = extractBoolean(json, "showInputRow") ?: d.showInputRow,
            // R62:旧预设 id(material / gold / graphite / black)在这里就换成新的,下次写盘不再带旧 id。
            themePresetId = extractString(json, "themePresetId")
                ?.takeIf { it.isNotBlank() }?.let { ThemePresets.migrateId(it) } ?: d.themePresetId,
            followWallpaperColor = extractBoolean(json, "followWallpaperColor")
                ?: d.followWallpaperColor,
            clock24hFollowSystem = extractBoolean(json, "clock24hFollowSystem")
                ?: d.clock24hFollowSystem,
            showDate = extractBoolean(json, "showDate") ?: d.showDate,
            idleAfterMs = snapIdleAfterMs(extractLong(json, "idleAfterMs")),
            idleContent = extractString(json, "idleContent")
                ?.let { name -> runCatching { IdleContent.valueOf(name) }.getOrNull() }
                ?: d.idleContent,
            screensaverAfterMs = snapScreensaverAfterMs(extractLong(json, "screensaverAfterMs")),
            screensaverIntervalMs = snapScreensaverIntervalMs(extractLong(json, "screensaverIntervalMs")),
            wallpaperFile = sanitizeWallpaperFileName(extractString(json, "wallpaperFile")),
            // 旧文件里可能还有 "wallpaperThemed"(2026-09-16 删掉的开关)、"themedCards"(R58)、
            // "wallpaperRotateMs" / "wallpaperRotatedAt"(R61):这里不读它们,扁平 tokenizer 只认列出的键,
            // 未知键自然被忽略(SettingsTest 的 legacy*KeyIsIgnored 钉住这一点)。
            wallpaperBlur = clampPercentStep10(extractInt(json, "wallpaperBlur"), d.wallpaperBlur),
            // 旧文件只有 wallpaperDim(0–100 压暗)时换算成负亮度(超过 50 的压暗夹到 −50);新键在场以新键为准。
            wallpaperBrightness = clampBrightnessStep10(
                extractInt(json, "wallpaperBrightness") ?: extractInt(json, "wallpaperDim")?.let { -it },
                d.wallpaperBrightness,
            ),
            cardSaturation = clampCardSaturation(extractInt(json, "cardSaturation")),
            cardBrightness = clampCardBrightness(extractInt(json, "cardBrightness")),
            cardOpacity = clampCardOpacity(extractInt(json, "cardOpacity")),
            newAppsSeenAt = clampEpoch(extractLong(json, "newAppsSeenAt")),
            language = extractString(json, "language")
                ?.takeIf { VALID_LANGUAGES.contains(it) } ?: d.language,
            // 三态:extractBoolean 解析不出(缺键、或值不是 true/false)时本来就是 null,
            // 直接透传即可——和其它布尔字段不同,这里"缺失"不该回落到某个默认布尔。
            onboardingDone = extractBoolean(json, "onboardingDone"),
        )
    } catch (e: Throwable) {
        // 理论上上面每一步都已经用 ?: 兜底、不会抛,这层 catch 只是和 Layout 保持同一套
        // "任何 Throwable 都不能崩" 的姿势,不依赖某一行实现细节。
        d
    }
}

/** 固定字段顺序输出,纯粹是为了 `adb pull` 下来的文件人眼好读、好 diff。 */
fun Settings.toJson(): String {
    fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    return buildString {
        append("{\n")
        append("  \"rowCount\": $rowCount,\n")
        append("  \"cardsPerRow\": $cardsPerRow,\n")
        append("  \"showTitles\": $showTitles,\n")
        append("  \"showInputRow\": $showInputRow,\n")
        append("  \"themePresetId\": \"${esc(themePresetId)}\",\n")
        append("  \"followWallpaperColor\": $followWallpaperColor,\n")
        append("  \"clock24hFollowSystem\": $clock24hFollowSystem,\n")
        append("  \"showDate\": $showDate,\n")
        append("  \"idleAfterMs\": $idleAfterMs,\n")
        append("  \"idleContent\": \"${idleContent.name}\",\n")
        append("  \"screensaverAfterMs\": $screensaverAfterMs,\n")
        append("  \"screensaverIntervalMs\": $screensaverIntervalMs,\n")
        append("  \"wallpaperFile\": \"${esc(wallpaperFile)}\",\n")
        append("  \"wallpaperBlur\": $wallpaperBlur,\n")
        append("  \"wallpaperBrightness\": $wallpaperBrightness,\n")
        append("  \"cardSaturation\": $cardSaturation,\n")
        append("  \"cardBrightness\": $cardBrightness,\n")
        append("  \"cardOpacity\": $cardOpacity,\n")
        append("  \"language\": \"${esc(language)}\",\n")
        onboardingDone?.let { append("  \"onboardingDone\": $it,\n") }
        append("  \"newAppsSeenAt\": $newAppsSeenAt\n")
        append("}\n")
    }
}

/**
 * 「恢复默认」纯函数:除了 `newAppsSeenAt`(传入 [nowMs],否则「新应用」判定会把恢复前
 * 装的所有应用瞬间打成"新")和 `onboardingDone`(引导流程不是外观设置,恢复默认不该让
 * 老用户重新走一遍引导)之外,其余字段全部回落到 [Settings] 的构造默认值。
 */
fun restoredDefaults(current: Settings, nowMs: Long): Settings =
    Settings().copy(newAppsSeenAt = nowMs, onboardingDone = current.onboardingDone)

/**
 * 粗略判断一段文本是不是"至少语法完整、且只有一个"的 JSON 对象(花括号/引号配平,
 * 且顶层对象只闭合一次、闭合点必须是整段文本的末尾)。
 * 只给 [SettingsStore.read] 用来区分「文件语法就是坏的,该当成损坏处理」
 * 和「文件语法没问题、只是没写全某些字段(这是正常用法,不是损坏)」——
 * 后者应该走 [parseSettings] 的按字段默认回落,不该被当成坏文件改名。
 *
 * "顶层只闭合一次"这条专门堵一种很现实的追加式损坏:两段本身都合法的对象首尾拼在
 * 一起,比如 `{"rowCount":5}{"cardsPerRow":8}`——花括号配平、首尾字符也对,若不额外
 * 检查闭合位置就会被误判成"合法但partial"而放行,而 [parseSettings] 用的是最左匹配
 * 的正则,同名字段会悄悄取到第一段(旧值),这份坏文件也就永远续命下去。
 *
 * `internal` 而非 `private`:方便 [SettingsTest] 直接对这个函数本身断言,
 * 而不是绕一层间接验证。
 */
internal fun isWellFormedJsonObject(text: String): Boolean {
    val t = text.trim()
    if (t.length < 2 || t.first() != '{' || t.last() != '}') return false
    var depth = 0
    var inString = false
    var escape = false
    for (i in t.indices) {
        val c = t[i]
        if (inString) {
            when {
                escape -> escape = false
                c == '\\' -> escape = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth < 0) return false
                // 顶层对象刚闭合:后面除了空白不能再有任何东西,否则就是拼接的
                // 第二个对象(或多余的垃圾字符),按损坏处理。
                if (depth == 0) return t.substring(i + 1).isBlank()
            }
        }
    }
    // 循环走完都没等到顶层闭合(引号没配对,或花括号没配平)——不是合法的单个对象。
    return false
}

/**
 * settings.json 的读写——与 [Layout] / [Titles] 同一套落盘层 [LockedFile]:
 * 外部存储没挂就用内存默认值不写盘;正式文件与 `.prev` 都不在就写默认值再返回;
 * 正式文件坏了(语法损坏、超大)改名 `.bad` 留证,先试 `.prev`,都不行才写回默认值(口径见 [LockedFile.load])。
 *
 * **M3 起这是个多写者的store**:主线程的设置页 / 选图,IO 线程 prepare 的迁移/铺入/清理
 * (壁纸轮播 R61 删掉之前也在 IO 线程写),都会写同一个文件。所以读、写、读改写全部在 `store.locked` 里——
 * **不加锁会真的丢掉全部设置**:两个写者交叠时,输的那个在 rename 兜底里删掉赢家刚放好的 settings.json,
 * 下次 [read] 读不到文件就重写一份默认值。read 也要锁:它在文件缺失时会写默认值,落在别人写盘的间隙里就会抹掉别人的结果。
 * 2026-09-23 起改走 [LockedFile](原来是自己的锁 + 固定 `settings.json.tmp`),多了 `.prev` 这道防线。
 */
object SettingsStore {
    private const val TAG = "UnitedU"
    private val store = LockedFile("settings.json")

    fun read(ctx: Context): Settings = store.locked {
        val base = Paths.baseOrNull(ctx)
        if (base == null) {
            Log.w(TAG, "外部存储没挂上,这次用内存里的默认设置,不写盘")
            return@locked Settings()
        }
        when (val got = store.load(base, log = { Log.w(TAG, it) }, parse = ::parseSettingsStrict)) {
            is LockedFile.Load.Ok -> {
                if (got.restored) write(ctx, got.value)
                got.value
            }
            else -> Settings().also { write(ctx, it) }
        }
    }

    /**
     * 原子替换(独立临时文件 → fsync → rename,旧版本先复制成 `.prev`,见 [LockedFile.write])。
     * @return 是否真的落盘了;调用方(设置页)需要知道失败,
     *   否则界面上改的值下次开机又变回去,用户只会觉得"设置没保存"。
     */
    private fun write(ctx: Context, s: Settings): Boolean = store.locked {
        val base = Paths.baseOrNull(ctx) ?: return@locked false
        try {
            store.write(base, s.toJson())
        } catch (e: Throwable) {
            Log.w(TAG, "settings.json 写不了: ${e.message}")
            false
        }
    }

    /**
     * 读-改-写一次完成、持锁:设置页、迁移/铺入/清理、选图这些写者全部走这里,
     * 既不会互相踩临时文件,也没有「读到旧值再整对象回写」的丢更新窗口。
     * @return 写成功时返回写下的 Settings;写失败(外置没挂等)返回 null。
     */
    fun update(ctx: Context, transform: (Settings) -> Settings): Settings? = store.locked {
        val next = transform(read(ctx))
        if (write(ctx, next)) next else null
    }
}

/** 语法损坏就抛(交给 [LockedFile.load] 改名 `.bad`);语法没问题、只是缺字段的走 [parseSettings] 的按字段默认。 */
internal fun parseSettingsStrict(text: String): Settings {
    if (!isWellFormedJsonObject(text)) error("settings.json 不是合法的 JSON 对象")
    return parseSettings(text)
}

/**
 * 旧的 `cardsPerRow`(5/6/8)迁移到新的三档。张数越多卡越小,所以 8→小、6→中、5→大。
 * 设置文件里仍存旧的整数键,避免动存储格式;只有渲染层改读新档位。
 */
fun cardsPerRowToGtvSize(stored: Int): GtvCardSize = when (stored) {
    8 -> GtvCardSize.SMALL
    5 -> GtvCardSize.LARGE
    6 -> GtvCardSize.MEDIUM
    else -> GtvCardSize.MEDIUM
}
