package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/** R164 spec §3.3 海报:来源分类、采样、失败退避、下载上限与超时。下载用本机 ServerSocket 模拟(http 与 https 走同一个 fetchBytes)。 */
class PosterLoaderTest {
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
            @Volatile var closed = false
            override fun read(): Int {
                while (!closed) Thread.sleep(10)
                throw java.io.IOException("closed")
            }
            override fun close() { closed = true }
        }
        val t0 = System.nanoTime()
        assertNull(readWithWatchdog(300, { inp }, 1024))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    /** open() 本身卡死:load 在期限内返回 null,且之后的读取仍拿得到线程。 */
    @Test fun blockedOpenReturnsNullAndDoesNotStarveLaterReads() = kotlinx.coroutines.runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        repeat(6) {   // 比 4 个槽位多
            val t0 = System.nanoTime()
            assertNull(readResolverBounded(200, { gate.await(); null }, 1024))
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_500)
        }
        assertArrayEquals(ByteArray(3), readResolverBounded(200, { ByteArrayInputStream(ByteArray(3)) }, 1024))
        gate.countDown()
    }

    /** 服务器逐字节滴水:总期限(watchdog 断开)到点放手。 */
    @Test fun fetchHasAHardTotalDeadline() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { c ->
                    c.getInputStream().read(ByteArray(4096))
                    c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".toByteArray())
                    repeat(1000) { c.getOutputStream().write(1); c.getOutputStream().flush(); Thread.sleep(100) }
                }
            }
        }
        val t0 = System.nanoTime()
        assertNull(fetchBytes(URL("http://127.0.0.1:${server.localPort}/p"), 500, 4096))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        server.close()
    }

    /** 调用方被取消:CancellationException 照常抛出,不进 failed、不记日志。 */
    @Test fun cancelledLoadIsNotRecordedAsFailed() = kotlinx.coroutines.runBlocking {
        val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
        var logged = false
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            loadTracked<ByteArray>(failed, "u", { 1L }, { logged = true }) {
                readResolverBounded(5_000, { java.util.concurrent.CountDownLatch(1).await(); null }, 1024)
            }
        }
        kotlinx.coroutines.delay(200)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(failed.isEmpty())
        assertFalse(logged)
        // 真失败照记
        assertNull(loadTracked<ByteArray>(failed, "v", { 7L }, { logged = true }) { null })
        assertEquals(7L, failed["v"]); assertTrue(logged)
    }

    @Test fun resolverWorkersAreCapped() = kotlinx.coroutines.runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        val jobs = (1..RESOLVER_MAX_WORKERS).map { launch(kotlinx.coroutines.Dispatchers.Default) { readResolverBounded(3_000, { gate.await(); null }, 16) } }
        kotlinx.coroutines.delay(300)
        val t0 = System.nanoTime()
        assertNull(readResolverBounded(3_000, { ByteArrayInputStream(ByteArray(3)) }, 16))
        assertTrue("满了要立刻返回", (System.nanoTime() - t0) / 1_000_000 < 500)
        gate.countDown(); jobs.forEach { it.cancelAndJoin() }
    }
}
