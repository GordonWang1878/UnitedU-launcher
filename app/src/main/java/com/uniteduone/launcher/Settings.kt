package com.uniteduone.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

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
    val rowCount: Int = 3,
    val cardsPerRow: Int = 6,
    val showTitles: Boolean = false,
    val showInputRow: Boolean = false,
    val themePresetId: String = "gold",
    val followWallpaperColor: Boolean = false,
    val clock24hFollowSystem: Boolean = true,
    val showDate: Boolean = true,
    val idleAfterMs: Long = 180_000L,
    val idleContent: IdleContent = IdleContent.CLOCK_ONLY,
    // ---- M3 壁纸(spec §1)。默认全零/空 ⇒ 首页观感与 M2 逐位一致 ----
    // (「主题化壁纸」开关 wallpaperThemed 2026-09-16 整个删掉:壁纸不再染色;旧文件里的键按未知键忽略。)
    /** library/wallpapers/ 里的文件名;空 = 未指定(解析顺序见 Wallpapers.resolveSource)。 */
    val wallpaperFile: String = "",
    /** 轮播间隔 ms;0 = 关。合法值见 [VALID_WALLPAPER_ROTATE_MS]。 */
    val wallpaperRotateMs: Long = 0L,
    /** 上次轮换的 epoch ms;「每天」档靠它跨重启续等。 */
    val wallpaperRotatedAt: Long = 0L,
    /** 模糊 0–100,步 10。 */
    val wallpaperBlur: Int = 0,
    /** 亮度 −50…+50,步 10:0 = 原片,负 = 压暗,正 = 提亮(2026-09-16 Gordon 定,取代原 0–100「压暗」)。 */
    val wallpaperBrightness: Int = 0,
    /** 上次打开「添加应用」列表的时刻(epoch ms);firstInstallTime 晚于它的应用算「新」。0 = 未初始化(首启时写成当时)。 */
    val newAppsSeenAt: Long = 0L,
)

// `internal`(而非 `private`):这两张表是 cardsPerRow / idleAfterMs 的唯一合法取值集合,
// 既用来夹取(见下面 snap 系列函数),也是 [SettingsScreen] 里对应分段控件的选项顺序 ——
// 一份表两处读,才不会有人手改一处、另一处悄悄漂移(2026-09-15 复审前两处各写了一份字面量)。
internal val VALID_CARDS_PER_ROW = intArrayOf(5, 6, 8)
internal val VALID_IDLE_AFTER_MS = longArrayOf(0L, 60_000L, 180_000L, 300_000L, 600_000L)
internal val VALID_WALLPAPER_ROTATE_MS = longArrayOf(0L, 300_000L, 1_800_000L, 86_400_000L)

private fun snapRotateMs(v: Long?): Long =
    if (v != null && VALID_WALLPAPER_ROTATE_MS.contains(v)) v else 0L

/** 0..100 夹取后四舍五入到 10 的倍数(滑块 11 档);解析不出数字 → 该字段的默认值(真机调参后默认可能非零)。 */
private fun clampPercentStep10(v: Int?, default: Int): Int =
    if (v == null) default else ((v.coerceIn(0, 100) + 5) / 10) * 10

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
            themePresetId = extractString(json, "themePresetId")
                ?.takeIf { it.isNotBlank() } ?: d.themePresetId,
            followWallpaperColor = extractBoolean(json, "followWallpaperColor")
                ?: d.followWallpaperColor,
            clock24hFollowSystem = extractBoolean(json, "clock24hFollowSystem")
                ?: d.clock24hFollowSystem,
            showDate = extractBoolean(json, "showDate") ?: d.showDate,
            idleAfterMs = snapIdleAfterMs(extractLong(json, "idleAfterMs")),
            idleContent = extractString(json, "idleContent")
                ?.let { name -> runCatching { IdleContent.valueOf(name) }.getOrNull() }
                ?: d.idleContent,
            wallpaperFile = sanitizeWallpaperFileName(extractString(json, "wallpaperFile")),
            wallpaperRotateMs = snapRotateMs(extractLong(json, "wallpaperRotateMs")),
            wallpaperRotatedAt = clampEpoch(extractLong(json, "wallpaperRotatedAt")),
            // 旧文件里可能还有 "wallpaperThemed"(2026-09-16 删掉的开关):这里不读它,扁平 tokenizer 只认列出的键,
            // 未知键自然被忽略(SettingsTest.legacyWallpaperThemedKeyIsIgnored 钉住这一点)。
            wallpaperBlur = clampPercentStep10(extractInt(json, "wallpaperBlur"), d.wallpaperBlur),
            // 旧文件只有 wallpaperDim(0–100 压暗)时换算成负亮度(超过 50 的压暗夹到 −50);新键在场以新键为准。
            wallpaperBrightness = clampBrightnessStep10(
                extractInt(json, "wallpaperBrightness") ?: extractInt(json, "wallpaperDim")?.let { -it },
                d.wallpaperBrightness,
            ),
            newAppsSeenAt = clampEpoch(extractLong(json, "newAppsSeenAt")),
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
        append("  \"wallpaperFile\": \"${esc(wallpaperFile)}\",\n")
        append("  \"wallpaperRotateMs\": $wallpaperRotateMs,\n")
        append("  \"wallpaperRotatedAt\": $wallpaperRotatedAt,\n")
        append("  \"wallpaperBlur\": $wallpaperBlur,\n")
        append("  \"wallpaperBrightness\": $wallpaperBrightness,\n")
        append("  \"newAppsSeenAt\": $newAppsSeenAt\n")
        append("}\n")
    }
}

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
 * settings.json 的读写——完全照抄 [Layout] 的健壮性套路:
 * 外部存储没挂就用内存默认值不写盘;文件不存在就写默认值再返回;
 * 解析包在 `try/catch (e: Throwable)`(超大文件 OOM 是 Error 不是 Exception);
 * 语法损坏就把坏文件改名成 `.bad`、写回默认值、`Log.w` 留痕。
 *
 * **M3 起这是个多写者的store**:主线程的设置页 / 选图,IO 线程的轮播与
 * prepare 的迁移/铺入,都会写同一个文件。所以写必须串行化——见 [lock] 与 [update]。
 */
object SettingsStore {
    private const val TAG = "UnitedU"

    /**
     * 所有写盘串行化。**不加它会真的丢掉全部设置**:[write] 用同一个 `settings.json.tmp`,
     * 且 rename 失败时的兜底是 `dst.delete()` 再 rename——两个写者交叠时,输的那个可能
     * 正好删掉赢的那个刚放好的 settings.json,下次 [read] 读不到文件就重写一份默认值,
     * 用户的全部设置归零。JVM 的 monitor 是可重入的,所以 [update] 里套 [read]、
     * [read] 里再套 [write] 都不会自锁。
     */
    private val lock = Any()

    // 与 write/update 同一把锁:否则读者可能落在「删旧文件 → 改名」的间隙里看到「没文件」而写回默认值,把写者的结果抹掉
    fun read(ctx: Context): Settings = synchronized(lock) {
        if (Paths.baseOrNull(ctx) == null) {
            Log.w(TAG, "外部存储没挂上,这次用内存里的默认设置,不写盘")
            return Settings()
        }
        val f = Paths.settingsJson(ctx)
        if (!f.exists()) {
            val defaults = Settings()
            write(ctx, defaults)
            return defaults
        }
        return try {
            if (f.length() > 1_000_000) error("settings.json 大得离谱: ${f.length()} 字节")
            val text = f.readText()
            if (!isWellFormedJsonObject(text)) error("settings.json 不是合法的 JSON 对象")
            parseSettings(text)
        } catch (e: Throwable) {
            Log.w(TAG, "settings.json 读不了,改名保留并重写默认: ${e.message}")
            runCatching { f.renameTo(Paths.settingsBad(ctx)) }
            val defaults = Settings()
            write(ctx, defaults)
            defaults
        }
    }

    /**
     * 先写临时文件再改名,理由与 [Layout.write] 相同:直接覆盖写会先截断,
     * 断电或进程被杀就留下半截文件。
     * @return 是否真的落盘了;调用方(后续任务里的设置页)需要知道失败,
     *   否则界面上改的值下次开机又变回去,用户只会觉得"设置没保存"。
     */
    private fun write(ctx: Context, s: Settings): Boolean = synchronized(lock) {
        val base = Paths.baseOrNull(ctx) ?: return false
        val tmp = File(base, "settings.json.tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(s.toJson().toByteArray())
                out.flush()
                out.fd.sync()
            }
            val dst = Paths.settingsJson(ctx)
            if (tmp.renameTo(dst)) return true
            dst.delete()
            tmp.renameTo(dst)
        } catch (e: Throwable) {
            Log.w(TAG, "settings.json 写不了: ${e.message}")
            false
        }
    }

    /**
     * 读-改-写一次完成、持锁:设置页、轮播、迁移/铺入、选图这些写者全部走这里,
     * 既不会互相踩 tmp,也没有「读到旧值再整对象回写」的丢更新窗口。
     * @return 写成功时返回写下的 Settings;写失败(外置没挂等)返回 null。
     */
    fun update(ctx: Context, transform: (Settings) -> Settings): Settings? = synchronized(lock) {
        val next = transform(read(ctx))
        if (write(ctx, next)) next else null
    }
}
