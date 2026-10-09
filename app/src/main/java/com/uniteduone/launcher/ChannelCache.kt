package com.uniteduone.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 读 → 发布的骨架(JVM 可测,ChannelCacheTest),照 [AppsPageCache]:读写串行(一把锁);读的时候又有人要刷新的,跑完再读一遍;
 * 更早的请求已被更晚开始的一次读覆盖的,直接跳过。[load] 返回 null(读失败)→ 留着上一份。`StateFlow` 按 `equals` 去重:
 * 内容相同的刷新不换引用、不通知任何人。
 */
internal class ChannelStore {
    private val state = MutableStateFlow<ChannelSnapshot?>(null)
    val data: StateFlow<ChannelSnapshot?> = state
    private val lock = Mutex()
    private val requested = AtomicLong(0)
    @Volatile private var covered = 0L

    suspend fun refresh(load: suspend () -> ChannelSnapshot?) {
        val ticket = requested.incrementAndGet()
        lock.withLock {
            if (covered >= ticket) return
            val start = requested.get()
            val fresh = load()
            covered = start
            if (fresh != null) state.value = fresh
        }
    }
}

/**
 * **频道数据的进程级缓存**(owner 裁定 2026-10-08):首页、编辑页、选频道页都 `collect` [data],谁也不自己查 TvProvider。
 * MainActivity 在 `channelsRevision` 每变一次(TvProvider 变化 500 ms 去抖、授权结果、onResume)时 [refresh](Task 9)。
 */
internal object ChannelCache {
    private val store = ChannelStore()
    val data: StateFlow<ChannelSnapshot?> get() = store.data

    suspend fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        store.refresh { withContext(Dispatchers.IO) { runCatching { ChannelSource.snapshot(app) }.getOrNull() } }
    }
}
