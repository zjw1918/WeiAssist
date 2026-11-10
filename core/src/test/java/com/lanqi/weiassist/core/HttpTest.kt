package com.lanqi.weiassist.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.zip.GZIPOutputStream

class HttpTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    /** Holds /hang's answer until the test ends. */
    private val never = CountDownLatch(1)

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hang") { ex -> never.await(); ex.close() }
            createContext("/gzip") { ex ->
                ex.responseHeaders.add("Content-Encoding", "gzip")
                ex.sendResponseHeaders(200, 0)
                GZIPOutputStream(ex.responseBody).use { it.write("plain text".toByteArray()) }
            }
            createContext("/echo") { ex ->
                val text = "${ex.requestMethod} ${ex.requestHeaders.getFirst("Content-Type")} ${ex.requestBody.readBytes().size}".toByteArray()
                ex.sendResponseHeaders(200, text.size.toLong())
                ex.responseBody.use { it.write(text) }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun tearDown() {
        never.countDown()
        server.stop(0)
    }

    @Test fun `cancelling the coroutine ends a request that waits for its answer`() = runBlocking {
        val call = Http.with(connectMs = 2_000, readMs = 60_000).newCall(Http.get("$base/hang"))
        val waiting = async { call.await { it.code } }
        delay(300)
        val t0 = System.nanoTime()
        waiting.cancel()
        waiting.join()
        assertTrue("the call is cancelled", call.isCanceled())
        assertTrue("ended at once", (System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test fun `gzip is undone transparently`() = runBlocking {
        val text = Http.client.newCall(Http.get("$base/gzip")).await { it.body.string() }
        assertEquals("plain text", text)
    }

    @Test fun `a POST without bytes still has a body, and the caller's content type goes as it was`() = runBlocking {
        val request = Request.Builder().url("$base/echo").header("Content-Type", "application/json; charset=utf-8")
            .method("POST", Http.body("POST", null)).build()
        assertEquals("POST application/json; charset=utf-8 0", Http.client.newCall(request).await { it.body.string() })
        assertNull(Http.body("GET", null))
        assertNull(Http.body("GET", ByteArray(0)))
    }

    @Test fun `copies with their own timeouts share the connection pool`() {
        assertTrue(Http.with(connectMs = 1_000, readMs = 1_000).connectionPool === Http.client.connectionPool)
        assertTrue(Http.with(connectMs = 1_000, readMs = 1_000).dispatcher === Http.client.dispatcher)
    }
}
