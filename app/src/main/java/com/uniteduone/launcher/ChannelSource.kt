package com.uniteduone.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log

/**
 * R164:读 TvProvider 的两张表(spec §3.2)。**全部在 IO 线程调用**。纯逻辑(列、解析、匹配、组装)在 ChannelModel.kt。
 *
 * - 一律显式投影([TvCols.CHANNEL_PROJECTION] / [TvCols.PROGRAM_PROJECTION]),不用 selection(第三方带 selection 抛
 *   `SecurityException`,研究 §6.4);频道按 `?package=` 过滤,节目按 [TvContract.buildPreviewProgramsUriForChannel]。
 * - **没授权时 TvProvider 不抛异常,只返回 0 行**(研究 §6.2),所以「需要重新授权」先看 [hasPermission];
 *   `SecurityException` 只是兜底。不看 `browsable`(研究 §6.6–6.7:国行与 Google TV 上没人审批,永远是 0)。
 * - **列不受限、行受限**(AOSP android14-release `TvProvider.java` 核对,2026-10-09):`query` 只用 `createProjectionMapForQuery`
 *   (约 1823 行)按投影映射取列,不按调用方身份屏蔽或置空任何列——`internal_provider_id`(频道 237 行、预览节目 468 行)、
 *   `display_name`、`type`、`poster_art_uri` / `_aspect_ratio`、`intent_uri`、`weight`、`duration_millis`、季 / 集字段都在映射里,
 *   非特权调用方照样读得到;映射里没有的列返回 `NULL AS 列名`、不抛。限制全在**行**上:`createSqlParams`(1887–1900 行)对没有
 *   `ACCESS_ALL_EPG_DATA` 的调用方,带 selection 抛 `SecurityException`(1890 行),持 `READ_TV_LISTINGS` 时只放开
 *   `package_name = 自己 OR searchable = 1`(1896 行)。所以发布方把频道或节目设成 `searchable = 0` 时我们读不到那一行
 *   → 「暂无内容」,不是 bug;`?package=` 由 `appendWhere` 以括号 AND 在后面(1902 行),不破坏这个条件。
 *   `sortOrder` 传 null(非特权调用方的排序列要过 `validateSortOrder`,1492 行),排序在 `topPrograms` 里做。
 */
object ChannelSource {
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"
    private const val TAG = "UnitedU"

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** [pkg] = null:全部频道(选频道页);否则只要这个包的。null = 没有权限;其它读取错误 → 空表(只 Log)。 */
    fun channels(ctx: Context, pkg: String? = null): List<TvChannel>? {
        if (!hasPermission(ctx)) return null
        val uri = if (pkg == null) TvContract.Channels.CONTENT_URI
        else TvContract.Channels.CONTENT_URI.buildUpon().appendQueryParameter("package", pkg).build()
        return try {
            query(ctx, uri, TvCols.CHANNEL_PROJECTION).mapNotNull(::channelFromRow)
        } catch (e: SecurityException) {
            Log.w(TAG, "读频道被拒: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "读频道失败: ${e.javaClass.simpleName} ${e.message}")
            emptyList()
        }
    }

    /** 一个频道的全部预览节目(未排序)。null = 没有权限;其它错误 → 空表。 */
    fun programs(ctx: Context, channelId: Long): List<Program>? = try {
        query(ctx, TvContract.buildPreviewProgramsUriForChannel(channelId), TvCols.PROGRAM_PROJECTION).mapNotNull(::programFromRow)
    } catch (e: SecurityException) {
        Log.w(TAG, "读节目被拒: ${e.message}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "读节目失败: ${e.javaClass.simpleName} ${e.message}")
        emptyList()
    }

    /**
     * [ChannelCache] 的一次完整读取:全部频道(一次查询)+ 每个**别人发的**预览频道的节目(每个频道一次查询,排序截断)+
     * 发布方应用名。没授权 / 中途 `SecurityException` → `permitted = false`、其余为空。首页、编辑页、选频道页都从这一份派生。
     */
    internal fun snapshot(ctx: Context): ChannelSnapshot {
        val denied = ChannelSnapshot(permitted = false)
        val all = channels(ctx) ?: return denied
        val progs = HashMap<Long, List<Program>>()
        for (c in all) {
            if (c.type != CHANNEL_TYPE_PREVIEW || c.pkg == ctx.packageName) continue
            progs[c.id] = topPrograms(programs(ctx, c.id) ?: return denied)
        }
        val labels = all.map { it.pkg }.distinct().associateWith { Apps.labelOf(ctx, it) }
        return ChannelSnapshot(permitted = true, channels = all, programs = progs, labels = labels)
    }

    private fun query(ctx: Context, uri: Uri, projection: Array<String>): List<Map<String, Any?>> =
        ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.rowMap(projection)) }
        } ?: emptyList()

    private fun Cursor.rowMap(cols: Array<String>): Map<String, Any?> = cols.associateWith { name ->
        val i = getColumnIndex(name)
        if (i < 0 || isNull(i)) null
        else when (getType(i)) {
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            Cursor.FIELD_TYPE_STRING -> getString(i)
            else -> null   // BLOB 一律不读
        }
    }
}
