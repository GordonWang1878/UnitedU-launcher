package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/** R164 spec §3.3 海报:来源分类、采样、失败退避、下载上限与超时。下载用本机 ServerSocket 模拟(http 与 https 走同一个 fetchBytes)。 */
class PosterLoaderTest {
    /** 任何会阻塞的测试出回归时失败而不是挂死整个套件(每测 60 s 上限)。 */
    @get:org.junit.Rule val globalTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    /** 在途计数是进程级全局量:每个测试前后都等它回零,前一个测试的迟到工作线程不会污染下一个的基线。 */
    private fun awaitNoResolverWorkers() {
        val end = System.nanoTime() + 30_000_000_000L
        while (resolverWorkersInFlight() != 0 && System.nanoTime() < end) Thread.sleep(5)
    }
    @Before fun quiesceBefore() = awaitNoResolverWorkers()
    @After fun quiesceAfter() {
        awaitNoResolverWorkers()
        assertEquals("leaked resolver workers", 0, resolverWorkersInFlight())
    }

    private fun serveOnce(response: ByteArray): Int {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.use { s ->
                s.accept().use { c ->
                    c.getInputStream().read(ByteArray(4096))
                    c.getOutputStream().write(response)
                    c.getOutputStream().flush()
                }
            }
        }
        return server.localPort
    }

    @Test fun sources() {
        assertEquals(PosterSource.RESOLVER, posterSourceOf("content://test.channels.posters/1"))
        assertEquals(PosterSource.RESOLVER, posterSourceOf("android.resource://com.x/drawable/p"))
        assertEquals(PosterSource.HTTPS, posterSourceOf("HTTPS://cdn.example.com/p.jpg"))
        assertEquals("明文 http 按网络安全配置会失败,当作没图", PosterSource.NONE, posterSourceOf("http://cdn.example.com/p.jpg"))
        assertEquals(PosterSource.NONE, posterSourceOf("file:///sdcard/p.jpg"))
        assertEquals(PosterSource.NONE, posterSourceOf("garbage"))
    }

    @Test fun sampleSizeStaysAtOrAboveTheTargetHeight() {
        assertEquals(1, posterSampleSize(640, 360, 220))
        assertEquals("2160 / 8 = 270 ≥ 220,/ 16 = 135 < 220", 8, posterSampleSize(3840, 2160, 220))
        assertEquals(1, posterSampleSize(0, 0, 220))
    }

    /** Review Focus 3:失败过的海报一分钟内不重试(否则黑洞地址每次重组都卡 5 s 一个线程)。 */
    @Test fun failedPosterIsNotRetriedWithinAMinute() {
        assertTrue(posterRetryDue(null, 5_000))
        assertFalse(posterRetryDue(1_000, 1_000 + POSTER_RETRY_MS - 1))
        assertTrue(posterRetryDue(1_000, 1_000 + POSTER_RETRY_MS))
        assertTrue("时钟倒退(不该发生)时宁可重试", posterRetryDue(10_000, 5_000))
    }

    @Test fun readCappedRefusesOversizeStreams() {
        assertArrayEquals(ByteArray(10), readCapped(ByteArrayInputStream(ByteArray(10)), 10))
        assertNull(readCapped(ByteArrayInputStream(ByteArray(11)), 10))
    }

    @Test fun fetchReadsA200Body() {
        val body = "poster".toByteArray()
        val port = serveOnce("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
        assertArrayEquals(body, fetchBytes(URL("http://127.0.0.1:$port/p.png"), 2_000, 1024))
    }

    @Test fun fetchRejectsNon2xxAndDeclaredOversize() {
        val p404 = serveOnce("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        assertNull(fetchBytes(URL("http://127.0.0.1:$p404/p.png"), 2_000, 1024))
        val pBig = serveOnce("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\nConnection: close\r\n\r\n".toByteArray() + ByteArray(4096))
        assertNull(fetchBytes(URL("http://127.0.0.1:$pBig/p.png"), 2_000, 1024))
    }

    /** Review Focus 3:服务器接了连接但一个字节都不回 → 超时后返回 null,不挂住。 */
    @Test fun fetchGivesUpAfterTheTimeoutWhenTheServerNeverAnswers() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val held = ArrayList<Socket>()
        thread(isDaemon = true) { runCatching { held += server.accept() } }
        val t0 = System.nanoTime()
        val got = fetchBytes(URL("http://127.0.0.1:${server.localPort}/p.jpg"), 300, 1024)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNull(got)
        assertTrue("超时后要放手,实际 $ms ms", ms < 2_000)
        server.close()
    }

    @Test fun sizingBoundsWidthToo() {
        assertTrue(posterAcceptable(550, 220))
        assertFalse("太宽", posterAcceptable(120000, 220))
        assertFalse("太窄", posterAcceptable(100, 400))
        assertFalse("像素超限", posterAcceptable(4097, 4096))
        assertTrue(posterSampleSize(100000, 220, 220) >= 128)
        assertEquals(1, posterSampleSize(550, 220, 220))
        assertEquals(440 to 176, posterScaledSize(550, 220, 220))
        assertEquals(147 to 220, posterScaledSize(200, 300, 220))
        assertEquals(100 to 100, posterScaledSize(100, 100, 220))
    }

    @Test fun readCappedHonorsHintAndDeadline() {
        assertArrayEquals(ByteArray(10), readCapped(ByteArrayInputStream(ByteArray(10)), 100, 10))
        assertNull("比声明长", readCapped(ByteArrayInputStream(ByteArray(11)), 100, 10))
        assertNull("期限已过", readCapped(ByteArrayInputStream(ByteArray(10)), 100, -1, System.nanoTime() - 1))
    }

    @Test fun watchdogUnblocksAStuckRead() {
        val inp = object : java.io.InputStream() {
            // JVM 上没有 libcore 的 AsynchronousCloseMonitor:假流自己守住 close() 语义——read 阻塞在 latch 上,close 放行并抛 IOException
            val closedLatch = java.util.concurrent.CountDownLatch(1)
            override fun read(): Int {
                closedLatch.await()
                throw java.io.IOException("closed")
            }
            override fun close() { closedLatch.countDown() }
        }
        val t0 = System.nanoTime()
        assertNull(readWithWatchdog(300, { inp }, 1024))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 30_000)   // 不卡死即可,上限宽松
    }

    /** open() 本身卡死:load 在期限内返回 null,且之后的读取仍拿得到线程。 */
    @Test fun blockedOpenReturnsNullAndDoesNotStarveLaterReads() = kotlinx.coroutines.runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            repeat(6) {   // 比 4 个槽位多
                val t0 = System.nanoTime()
                assertNull(readResolverBounded(200, { gate.await(); null }, 1024))
                assertTrue((System.nanoTime() - t0) / 1_000_000 < 30_000)   // 远小于「一直等 gate」
            }
            assertArrayEquals(ByteArray(3), readResolverBounded(5_000, { ByteArrayInputStream(ByteArray(3)) }, 1024))
        } finally { gate.countDown() }
    }

    /** 服务器逐字节滴水:总期限(watchdog 断开)到点放手。 */
    @Test fun fetchHasAHardTotalDeadline() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        // 全部滴完要 100000 × 20 ms ≈ 33 min;客户端一断开写入即抛异常,服务线程随之退出
        val t = thread(isDaemon = true) {
            runCatching {
                server.accept().use { c ->
                    c.getInputStream().read(ByteArray(4096))
                    c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                    repeat(100_000) { c.getOutputStream().write(1); c.getOutputStream().flush(); Thread.sleep(20) }
                }
            }
        }
        try {
            val t0 = System.nanoTime()
            assertNull(fetchBytes(URL("http://127.0.0.1:${server.localPort}/p"), 500, 200_000))
            // 只断言「远早于滴完」:期限 0.5 s,给负载留 60 s 余量,不断言下限
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 60_000)
        } finally {
            runCatching { server.close() }
            t.join(5_000)
        }
    }

    /** 调用方被取消:CancellationException 照常抛出,不进 failed、不记日志。 */
    @Test fun cancelledLoadIsNotRecordedAsFailed() = kotlinx.coroutines.runBlocking {
        val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
        var logged = false
        val gate = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        try {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                loadTracked<ByteArray>(failed, "u", { 1L }, { logged = true }) {
                    readResolverBounded(60_000, { started.countDown(); gate.await(); null }, 1024)
                }
            }
            assertTrue(started.await(30, java.util.concurrent.TimeUnit.SECONDS))   // 工作线程确已开跑再取消
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(failed.isEmpty())
            assertFalse(logged)
            // 真失败照记
            assertNull(loadTracked<ByteArray>(failed, "v", { 7L }, { logged = true }) { null })
            assertEquals(7L, failed["v"]); assertTrue(logged)
        } finally {
            gate.countDown()   // 放掉卡住的工作线程,名额归还(断言失败也不漏)
        }
        awaitNoResolverWorkers()
        assertEquals(0, resolverWorkersInFlight())
    }

    /** 工作 job 在开跑前就被取消(调用方 async 完立刻被取消):名额照样归还,不漏。 */
    @Test fun cancelBeforeWorkerStartsReleasesSlot() = kotlinx.coroutines.runBlocking {
        val queued = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val held = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + java.util.concurrent.Executor { queued.add(it) }.asCoroutineDispatcher(),
        )
        awaitNoResolverWorkers()   // 基线必须是干净的 0,别的测试的迟到线程不能掺进来
        val before = resolverWorkersInFlight()
        var opened = false
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            readResolverBounded(60_000, { opened = true; null }, 16, held)
        }
        while (queued.isEmpty()) kotlinx.coroutines.delay(5)   // 工作 job 已派发、还没跑
        assertEquals(before + 1, resolverWorkersInFlight())
        job.cancelAndJoin()
        while (true) queued.poll()?.run() ?: break   // 现在才让执行器跑:job 已取消,体不执行
        assertFalse(opened)
        assertEquals(before, resolverWorkersInFlight())
    }

    @Test fun resolverWorkersAreCapped() = kotlinx.coroutines.runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        val jobs = (1..RESOLVER_MAX_WORKERS).map { launch(kotlinx.coroutines.Dispatchers.Default) { readResolverBounded(60_000, { gate.await(); null }, 16) } }
        try {
            val end = System.nanoTime() + 30_000_000_000L
            while (resolverWorkersInFlight() < RESOLVER_MAX_WORKERS && System.nanoTime() < end) kotlinx.coroutines.delay(5)
            assertEquals(RESOLVER_MAX_WORKERS, resolverWorkersInFlight())
            val t0 = System.nanoTime()
            assertNull(readResolverBounded(60_000, { ByteArrayInputStream(ByteArray(3)) }, 16))
            assertTrue("满了要立刻返回(不等 60 s 超时)", (System.nanoTime() - t0) / 1_000_000 < 20_000)
        } finally { gate.countDown(); jobs.forEach { it.cancelAndJoin() } }
    }
}
