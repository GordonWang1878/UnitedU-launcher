package com.uniteduone.launcher

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.security.MessageDigest

class GalleryHttpTest {
    private class Fake(val code: Int = 200, val body: ByteArray = "{}".toByteArray(), val headers: Map<String,String> = emptyMap(), val stream: InputStream? = null) : HttpURLConnection(URL(GALLERY_BASE_URL)) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String) = headers[name]
        override fun getInputStream() = stream ?: ByteArrayInputStream(body)
    }
    private fun http(vararg replies: Fake, clock: () -> Long = { 0L }): GalleryHttp {
        var n = 0
        return GalleryHttp(connectionFactory = { replies[n++] }, monotonicMillis = clock)
    }
    @Test fun catalog200And304() = runBlocking {
        val a = Fake(headers = mapOf("ETag" to "v1"))
        assertEquals(GalleryFetch.Text("{}", "v1"), http(a).catalog("v0"))
        assertEquals("v0", a.getRequestProperty("If-None-Match"))
        assertFalse(a.instanceFollowRedirects)
        assertTrue(a.disconnected)
        assertEquals(GalleryFetch.NotModified, http(Fake(304)).catalog("v1"))
    }
    @Test fun rejectsHttpErrorAndUnsolicited304() = runBlocking {
        for (code in listOf(404, 500, 304)) assertTrue(runCatching { http(Fake(code)).catalog(null) }.isFailure)
    }
    @Test fun redirectsStayInsideRootAndStopAfterFive() = runBlocking {
        val next = Fake()
        assertEquals(GalleryFetch.Text("{}",null), http(Fake(302, headers=mapOf("Location" to "next.json")), next).catalog(null))
        for (location in listOf("https://other.example/gallery/a", "/outside/a", "../a", "a%2fb", "a?x=1", "//other.example/a")) {
            val a = Fake(302, headers=mapOf("Location" to location))
            assertTrue(location, runCatching { http(a).catalog(null) }.isFailure)
            assertTrue(a.disconnected)
        }
        assertEquals(GalleryFetch.Text("{}",null), http(*(Array(5) { Fake(302,headers=mapOf("Location" to "next.json")) } + Fake())).catalog(null))
        val hops = Array(6) { Fake(302, headers=mapOf("Location" to "next.json")) }
        assertTrue(runCatching { http(*hops).catalog(null) }.isFailure)
        assertTrue(hops.all { it.disconnected })
    }
    @Test fun catalogActualLimit() = runBlocking {
        assertTrue(runCatching { http(Fake(body=ByteArray(GALLERY_MAX_CATALOG_BYTES+1))).catalog(null) }.isFailure)
    }
    private fun blob(bytes: ByteArray) = GalleryBlob("images/a.jpg",bytes.size.toLong(),MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    @Test fun downloadChecksActualSizeHashAndKeepsOldFile() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val body = "image".toByteArray(); val part = File(dir,"a.part")
            http(Fake(body=body)).download(blob(body),part) {}
            assertArrayEquals(body,part.readBytes()); part.delete()
            for (bad in listOf(body + 0, body.copyOf(2), "wrong".toByteArray())) {
                assertTrue(runCatching { http(Fake(body=bad)).download(blob(body),part) {} }.isFailure)
                assertFalse(part.exists())
            }
            part.writeText("old")
            assertTrue(runCatching { http(Fake(body=body)).download(blob(body),part) {} }.isFailure)
            assertEquals("old",part.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun deadlineIncludesRedirectsAndBoundsReadTimeout() = runBlocking {
        var time = 0L
        val stream = object : ByteArrayInputStream("{}".toByteArray()) { override fun read(b: ByteArray,off: Int,len: Int): Int { time=120001; return super.read(b,off,len) } }
        val a=Fake(302,headers=mapOf("Location" to "next.json")); val b=Fake(stream=stream)
        assertTrue(runCatching { http(a,b,clock={ time }).catalog(null) }.isFailure)
        assertTrue(b.readTimeout in 1..15000)
    }
    @Test fun cancellationDisconnectsBlockedReadAndRemovesOwnedPart() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val blocking = object : InputStream() {
            override fun read(): Int = error("unused")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown(); released.await(3,java.util.concurrent.TimeUnit.SECONDS)
                throw IOException("disconnected")
            }
        }
        val fake = object : HttpURLConnection(URL(GALLERY_BASE_URL)) {
            @Volatile var disconnected = false
            override fun connect() {}
            override fun usingProxy() = false
            override fun disconnect() { disconnected=true; released.countDown() }
            override fun getResponseCode() = 200
            override fun getInputStream() = blocking
        }
        val dir = kotlin.io.path.createTempDirectory().toFile(); val part=File(dir,"cancel.part")
        try {
            val transport=GalleryHttp(connectionFactory={ fake })
            val job=launch(Dispatchers.Default) { transport.download(blob("a".toByteArray()),part) {} }
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(fake.disconnected)
            withTimeout(3000) { while(part.exists()) delay(10) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun writeFailureDeletesOnlyOwnPart() = runBlocking {
        val dir=kotlin.io.path.createTempDirectory().toFile(); val part=File(dir,"write.part")
        try {
            assertTrue(runCatching { http(Fake(body="a".toByteArray())).download(blob("a".toByteArray()),part) { throw IOException("disk full") } }.isFailure)
            assertFalse(part.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun loopbackRequiresExplicitOptIn() {
        assertTrue(runCatching { GalleryHttp(baseUrl="http://127.0.0.1:1234/gallery/") }.isFailure)
        GalleryHttp(baseUrl="http://127.0.0.1:1234/gallery/",allowLoopbackHttp=true)
        assertTrue(runCatching { GalleryHttp(baseUrl="http://localhost/gallery/",allowLoopbackHttp=true) }.isFailure)
    }

    @Test fun cancellationAfterValidationRemovesPartAndAllowsRetry() = runBlocking {
        val dir=kotlin.io.path.createTempDirectory().toFile(); val part=File(dir,"handoff.part")
        val entered=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
        try {
            val transport=GalleryHttp(connectionFactory={ Fake(body="a".toByteArray()) }, beforeCompletion={ entered.countDown(); release.await() })
            val job=launch(Dispatchers.Default) { transport.download(blob("a".toByteArray()),part) {} }
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)); assertTrue(part.exists())
            job.cancelAndJoin(); release.countDown()
            withTimeout(3000) { while(part.exists()) delay(10) }
            http(Fake(body="a".toByteArray())).download(blob("a".toByteArray()),part) {}
            assertEquals("a",part.readText())
        } finally { release.countDown(); dir.deleteRecursively() }
    }
    @Test fun deadlineAfterValidationRemovesPart() = runBlocking {
        val dir=kotlin.io.path.createTempDirectory().toFile(); val part=File(dir,"deadline.part"); var time=0L
        try {
            val transport=GalleryHttp(connectionFactory={ Fake(body="a".toByteArray()) },monotonicMillis={ time },beforeCompletion={ time=120001L })
            assertTrue(runCatching { transport.download(blob("a".toByteArray()),part) {} }.isFailure)
            assertFalse(part.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun watchdogWinsValidatedCompletionRace() = runBlocking {
        val dir=kotlin.io.path.createTempDirectory().toFile(); val part=File(dir,"watchdog.part")
        var expire: (() -> Unit)? = null
        try {
            val transport=GalleryHttp(connectionFactory={ Fake(body="a".toByteArray()) },
                scheduleDeadline={ callback -> expire=callback; {} },beforeCompletion={ expire!!() })
            assertTrue(runCatching { transport.download(blob("a".toByteArray()),part) {} }.exceptionOrNull() is GalleryHttpException)
            withTimeout(3000) { while(part.exists()) delay(10) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun cancellationAfterResumeBeforeDeliveryDeletesPart() = runBlocking {
        val queue=java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher=object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val dir=kotlin.io.path.createTempDirectory().toFile(); val part=File(dir,"delivery.part")
        try {
            val transport=http(Fake(body="a".toByteArray()))
            val job=launch(dispatcher) { transport.download(blob("a".toByteArray()),part) {} }
            queue.poll(3,java.util.concurrent.TimeUnit.SECONDS)!!.run()
            val delivery=queue.poll(3,java.util.concurrent.TimeUnit.SECONDS)!!
            assertTrue(part.exists())
            job.cancel(); delivery.run(); job.join()
            assertFalse(part.exists())
        } finally { dir.deleteRecursively() }
    }

}
