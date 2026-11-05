package com.lanqi.weiassist.dsh

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KeyProxyTest {
    private lateinit var upstream: HttpServer
    private val seen = mutableListOf<Map<String, String>>()
    /** Lets the streaming test hold the second half of an answer. */
    private val release = CountDownLatch(1)
    private lateinit var proxy: KeyProxy
    private lateinit var base: String
    private var key = "sk-real"

    @Before fun setUp() {
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/") { ex ->
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                synchronized(seen) {
                    seen += mapOf(
                        "path" to ex.requestURI.toString(), "method" to ex.requestMethod, "body" to body,
                        "authorization" to (ex.requestHeaders.getFirst("Authorization") ?: ""),
                        "x-api-key" to (ex.requestHeaders.getFirst("x-api-key") ?: ""),
                    )
                }
                if (ex.requestURI.path.endsWith("/stream")) {
                    ex.responseHeaders.add("Content-Type", "text/event-stream")
                    ex.sendResponseHeaders(200, 0)
                    ex.responseBody.write("data: one\n\n".toByteArray()); ex.responseBody.flush()
                    release.await(5, TimeUnit.SECONDS)
                    ex.responseBody.write("data: two\n\n".toByteArray())
                } else {
                    val code = if (ex.requestURI.path.endsWith("/fail")) 429 else 200
                    val out = """{"ok":true}""".toByteArray()
                    ex.sendResponseHeaders(code, out.size.toLong())
                    ex.responseBody.write(out)
                }
                ex.close()
            }
            start()
        }
        proxy = KeyProxy({ mapOf("openai" to KeyProxy.Route("http://127.0.0.1:${upstream.address.port}/v1", key)) })
        base = proxy.start()
    }

    @After fun tearDown() {
        proxy.stop()
        upstream.stop(0)
    }

    private fun request(path: String, auth: String? = "Bearer ${proxy.token}", body: String? = """{"model":"m"}""", header: Pair<String, String>? = null): Pair<Int, String> {
        val c = URL("$base$path").openConnection() as HttpURLConnection
        if (auth != null) c.setRequestProperty("Authorization", auth)
        header?.let { c.setRequestProperty(it.first, it.second) }
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = ((if (code >= 400) c.errorStream else c.inputStream)?.readBytes() ?: ByteArray(0)).toString(Charsets.UTF_8)
        return code to text
    }

    @Test fun `the real key goes upstream in place of the token, and the answer comes back`() {
        val (code, text) = request("/openai/responses")
        assertEquals(200, code)
        assertEquals("""{"ok":true}""", text)
        val s = seen.single()
        assertEquals("/v1/responses", s["path"])
        assertEquals("Bearer sk-real", s["authorization"])
        assertEquals("""{"model":"m"}""", s["body"])
    }

    @Test fun `the token is replaced in whatever header the provider uses`() {
        request("/openai/messages", auth = null, header = "x-api-key" to proxy.token)
        assertEquals("sk-real", seen.single()["x-api-key"])
    }

    @Test fun `a key change applies to the next request`() {
        request("/openai/responses")
        key = "sk-new"
        request("/openai/responses")
        assertEquals(listOf("Bearer sk-real", "Bearer sk-new"), seen.map { it["authorization"] })
    }

    @Test fun `without the token, or for another route, nothing goes upstream`() {
        assertEquals(403, request("/openai/responses", auth = "Bearer guess").first)
        assertEquals(403, request("/openai/responses", auth = null).first)
        assertEquals(404, request("/evil/responses").first)
        assertTrue(seen.isEmpty())
    }

    @Test fun `an upstream error passes through with its code`() {
        assertEquals(429, request("/openai/fail").first)
    }

    @Test fun `an answer streams through as it comes`() {
        val c = URL("$base/openai/stream").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer ${proxy.token}")
        val input = c.inputStream
        val first = ByteArray(11)
        var n = 0
        while (n < first.size) n += input.read(first, n, first.size - n).also { assertTrue(it > 0) }
        // The first event arrived while the upstream still holds the second.
        assertEquals("data: one\n\n", String(first))
        release.countDown()
        assertEquals("data: two\n\n", input.readBytes().toString(Charsets.UTF_8))
        assertFalse(seen.isEmpty())
    }
}
