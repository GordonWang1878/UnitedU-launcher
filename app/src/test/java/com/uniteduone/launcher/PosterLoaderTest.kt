package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
}
