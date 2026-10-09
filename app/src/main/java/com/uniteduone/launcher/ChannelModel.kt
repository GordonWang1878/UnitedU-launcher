package com.uniteduone.launcher

/**
 * R164 频道行的纯模型:TvProvider 两张表(`channel`、`preview_program`)的列名、行 → 对象、频道匹配、节目排序截断、
 * 卡下第二行的文案。**一行 android.* 都不碰**——读游标的那一半在 ChannelSource.kt,这里全部 JVM 单测(ChannelModelTest)。
 * 依据:spec `2026-10-08-channel-rows-design.md` §3、研究 `2026-10-07-tv-channels-recommendations.md` §6。
 */

/**
 * 我们读的列名。与 `android.media.tv.TvContract` 的常量**逐字相同**(`Channels._ID` = "_id"、
 * `Channels.COLUMN_PACKAGE_NAME` = "package_name"、`PreviewPrograms.COLUMN_POSTER_ART_URI` = "poster_art_uri" …),
 * 写成字面量是为了让纯函数与单测不依赖 android.jar。**投影一律显式**:`projection = null` 会撞 BLOB 列抛
 * `SQLiteException`(研究 §6.2)。
 */
internal object TvCols {
    const val ID = "_id"
    const val PACKAGE = "package_name"
    const val TYPE = "type"
    const val DISPLAY_NAME = "display_name"
    const val INTERNAL_ID = "internal_provider_id"
    const val TITLE = "title"
    const val EPISODE_TITLE = "episode_title"
    const val SEASON = "season_display_number"
    const val EPISODE = "episode_display_number"
    const val DURATION = "duration_millis"
    const val POSTER = "poster_art_uri"
    const val POSTER_ASPECT = "poster_art_aspect_ratio"
    const val THUMB = "thumbnail_uri"
    const val THUMB_ASPECT = "poster_thumbnail_aspect_ratio"
    const val INTENT = "intent_uri"
    const val WEIGHT = "weight"

    val CHANNEL_PROJECTION = arrayOf(ID, PACKAGE, TYPE, DISPLAY_NAME, INTERNAL_ID)
    val PROGRAM_PROJECTION = arrayOf(ID, TITLE, EPISODE_TITLE, SEASON, EPISODE, DURATION, POSTER, POSTER_ASPECT, THUMB, THUMB_ASPECT, INTENT, WEIGHT)
}

/** `TvContract.Channels.TYPE_PREVIEW`:只认这一种(spec §3.2)。 */
internal const val CHANNEL_TYPE_PREVIEW = "TYPE_PREVIEW"

/** 首页每个频道行最多几张卡(spec §4)。 */
internal const val MAX_PROGRAMS = 12

/** TvProvider `channel` 表的一行(只取我们用的列)。[internalId] 空白时为 null。 */
data class TvChannel(val id: Long, val pkg: String, val type: String, val name: String, val internalId: String?)

/**
 * 海报比例与它在 110 dp 卡高下的卡宽(dp,spec §4:16:9 → 196、3:2 → 165、4:3 → 147、1:1 → 110、2:3 → 73、3:4 → 83)。
 * TvProvider 的 `MOVIE_POSTER`(1 : 1.441)按 2:3 画(spec「电影海报 2:3 同」)。
 */
enum class PosterAspect(val widthDp: Float) {
    R16_9(196f), R3_2(165f), R4_3(147f), R1_1(110f), R2_3(73f), R3_4(83f),
}

/**
 * `poster_art_aspect_ratio` / `poster_thumbnail_aspect_ratio` 的整数码 → [PosterAspect]。框架的 `TvContract.PreviewPrograms`
 * 只定义 0–4:`ASPECT_RATIO_16_9` = 0、`3_2` = 1、`4_3` = 2、`1_1` = 3、`2_3` = 4;5(电影海报)与 6(3:4)来自
 * androidx.tvprovider / 发布方的约定,不是框架常量。缺省 / 不认识的码 → 16:9。
 */
internal fun posterAspectOf(code: Long?): PosterAspect = when (code?.toInt()) {
    0 -> PosterAspect.R16_9
    1 -> PosterAspect.R3_2
    2 -> PosterAspect.R4_3
    3 -> PosterAspect.R1_1
    4, 5 -> PosterAspect.R2_3
    6 -> PosterAspect.R3_4
    else -> PosterAspect.R16_9
}

/** 预览节目(首页一张卡)。[posterUri] = `poster_art_uri`,没有就用 `thumbnail_uri`;[aspect] 跟着取的那一个走。 */
data class Program(
    val id: Long,
    val title: String,
    val episodeTitle: String?,
    val season: String?,
    val episode: String?,
    val durationMs: Long,
    val posterUri: String?,
    val aspect: PosterAspect,
    val intentUri: String?,
    val weight: Int,
)

/** 一个频道行此刻的内容。首页只画 [Ready];编辑页三种都画(海报 / 「暂无内容」/「需要重新授权」)。 */
sealed interface ChannelContent {
    data object NeedsPermission : ChannelContent
    /** 频道找不到(被删、key 对不上)或一个节目都没有。 */
    data object Missing : ChannelContent
    /** [programs] 已排序截断、非空。 */
    data class Ready(val programs: List<Program>) : ChannelContent
}

private fun Map<String, Any?>.text(k: String): String? = when (val v = this[k]) {
    is String -> v.trim().takeIf { it.isNotEmpty() }
    is Number -> v.toLong().toString()
    else -> null
}

private fun Map<String, Any?>.num(k: String): Long? = when (val v = this[k]) {
    is Number -> v.toLong()
    is String -> v.trim().toLongOrNull()
    else -> null
}

/** 游标一行(列名 → 值)→ [TvChannel];没有 `_id` 或包名 → null。 */
internal fun channelFromRow(m: Map<String, Any?>): TvChannel? {
    val id = m.num(TvCols.ID) ?: return null
    val pkg = m.text(TvCols.PACKAGE) ?: return null
    return TvChannel(id, pkg, m.text(TvCols.TYPE).orEmpty(), m.text(TvCols.DISPLAY_NAME).orEmpty(), m.text(TvCols.INTERNAL_ID))
}

/** 游标一行 → [Program];没有 `_id` → null。 */
internal fun programFromRow(m: Map<String, Any?>): Program? {
    val id = m.num(TvCols.ID) ?: return null
    val poster = m.text(TvCols.POSTER)
    return Program(
        id = id,
        title = m.text(TvCols.TITLE).orEmpty(),
        episodeTitle = m.text(TvCols.EPISODE_TITLE),
        season = m.text(TvCols.SEASON),
        episode = m.text(TvCols.EPISODE),
        durationMs = m.num(TvCols.DURATION) ?: 0L,
        posterUri = poster ?: m.text(TvCols.THUMB),
        aspect = posterAspectOf(if (poster != null) m.num(TvCols.POSTER_ASPECT) else m.num(TvCols.THUMB_ASPECT)),
        intentUri = m.text(TvCols.INTENT),
        weight = (m.num(TvCols.WEIGHT) ?: 0L).toInt(),
    )
}

/** 加行时写进 layout.json 的标识(spec §3.3):key = `internal_provider_id`,没有就空串(按名字认)。 */
internal fun refFor(c: TvChannel): ChannelRef = ChannelRef(c.pkg, c.internalId.orEmpty(), c.name)

/**
 * 在 [channels] 里找 [ref] 指的那个频道:同包、`TYPE_PREVIEW`;key 非空按 `internal_provider_id` 认,空则按 `display_name` 认;
 * 多个命中取 `_id` 最小的。**都对不上返回 null**——不换成同包的别的频道(spec §3.3)。
 */
internal fun matchChannel(ref: ChannelRef, channels: List<TvChannel>): TvChannel? {
    val mine = channels.filter { it.pkg == ref.pkg && it.type == CHANNEL_TYPE_PREVIEW }.sortedBy { it.id }
    return if (ref.key.isNotEmpty()) mine.firstOrNull { it.internalId == ref.key }
    else mine.firstOrNull { it.name == ref.name }
}

/** `weight` 降序、同权重按 `_id` 升序(= 插入顺序),最多 [MAX_PROGRAMS] 张(spec §2.3)。 */
internal fun topPrograms(programs: List<Program>): List<Program> =
    programs.sortedWith(compareByDescending<Program> { it.weight }.thenBy { it.id }).take(MAX_PROGRAMS)

/** 卡下第二行的四种格式(调用方按界面语言从资源取)。 */
internal data class MetaFormats(val seasonEpisode: String, val episode: String, val hoursMinutes: String, val minutes: String)

/**
 * 焦点行卡下第二行(spec §4):剧集「第 n 季 · 第 n 集」,只有集号「第 n 集」,否则按时长(≥ 1 分钟)写「n 小时 n 分钟 / n 分钟」;
 * 都没有 → null(第二行留空)。**不重复「应用 · 频道」**(行头已经写了)。
 */
internal fun programSubtitle(p: Program, f: MetaFormats): String? = when {
    p.season != null && p.episode != null -> String.format(java.util.Locale.ROOT, f.seasonEpisode, p.season, p.episode)
    p.episode != null -> String.format(java.util.Locale.ROOT, f.episode, p.episode)
    p.durationMs >= 60_000L -> {
        val total = (p.durationMs / 60_000L).toInt()
        if (total >= 60) String.format(java.util.Locale.ROOT, f.hoursMinutes, total / 60, total % 60) else String.format(java.util.Locale.ROOT, f.minutes, total)
    }
    else -> null
}

/**
 * 组装每个频道行的内容(spec §3.2)。[channelsOf] 按包取频道(`?package=`),返回 null = 没有权限;
 * [programsOf] 按频道 id 取预览节目,null = 没有权限。同一个包只查一次。
 */
internal fun channelContents(
    refs: List<ChannelRef>,
    channelsOf: (pkg: String) -> List<TvChannel>?,
    programsOf: (channelId: Long) -> List<Program>?,
): Map<ChannelRef, ChannelContent> {
    // 不用 getOrPut:值为 null(没权限)时它会把键当缺失、再查一次
    val byPkg = HashMap<String, List<TvChannel>?>()
    return refs.distinct().associateWith { ref ->
        val chans = if (byPkg.containsKey(ref.pkg)) byPkg[ref.pkg] else channelsOf(ref.pkg).also { byPkg[ref.pkg] = it }
        if (chans == null) return@associateWith ChannelContent.NeedsPermission
        val ch = matchChannel(ref, chans) ?: return@associateWith ChannelContent.Missing
        val progs = programsOf(ch.id) ?: return@associateWith ChannelContent.NeedsPermission
        val top = topPrograms(progs)
        if (top.isEmpty()) ChannelContent.Missing else ChannelContent.Ready(top)
    }
}

/**
 * 进程级频道缓存([ChannelCache])里的一份快照:读的那一刻有没有授权、全部频道、每个预览频道的节目(已排序截断)、
 * 发布方应用名。data class:内容相同即相等,`StateFlow` 据此不通知。
 */
internal data class ChannelSnapshot(
    val permitted: Boolean,
    val channels: List<TvChannel> = emptyList(),
    val programs: Map<Long, List<Program>> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
)

/** 从共享快照派生每个频道行的内容(首页 / 编辑页同一个口径)。[snap] = null(还没读过)→ 空表(首页不画、编辑页按「暂无内容」)。 */
internal fun channelContentsFrom(snap: ChannelSnapshot?, refs: List<ChannelRef>): Map<ChannelRef, ChannelContent> {
    if (snap == null || refs.isEmpty()) return emptyMap()
    if (!snap.permitted) return refs.distinct().associateWith { ChannelContent.NeedsPermission }
    return channelContents(refs, { pkg -> snap.channels.filter { it.pkg == pkg } }, { id -> snap.programs[id].orEmpty() })
}
