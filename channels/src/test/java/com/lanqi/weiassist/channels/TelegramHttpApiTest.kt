package com.lanqi.weiassist.channels

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

/** [TelegramBot.HttpApi] against a local stand-in for api.telegram.org. */
class TelegramHttpApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var api: TelegramBot.HttpApi
    private val token = "123:secret"
    private var seen = ""

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/bot$token/getMe") { ex ->
                seen = "${ex.requestMethod} ${ex.requestHeaders.getFirst("Content-Type")} ${ex.requestBody.readBytes().toString(Charsets.UTF_8)}"
                reply(ex, 200, """{"ok":true,"result":{"username":"bot"}}""")
            }
            createContext("/bot$token/sendDocument") { ex ->
                seen = ex.requestHeaders.getFirst("Content-Type") + "\n" + ex.requestBody.readBytes().toString(Charsets.UTF_8)
                reply(ex, 200, """{"ok":true,"result":{"message_id":7}}""")
            }
            createContext("/bot$token/broken") { ex -> reply(ex, 502, "<html>Bad Gateway</html>") }
            createContext("/file/bot$token/photos/p.jpg") { ex -> reply(ex, 200, "JPEGDATA") }
            start()
        }
        api = TelegramBot.HttpApi(token, "http://127.0.0.1:${server.address.port}")
    }

    @After fun tearDown() = server.stop(0)

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test fun `a call posts JSON and decodes the answer`() = runBlocking {
        val r = api.call("getMe", JSONObject().put("a", 1), 5_000)
        assertTrue(r.getBoolean("ok"))
        assertEquals("bot", r.getJSONObject("result").getString("username"))
        assertEquals("""POST application/json; charset=utf-8 {"a":1}""", seen)
    }

    @Test fun `an answer that isn't JSON is a failed call with its status`() = runBlocking {
        val r = api.call("broken", JSONObject(), 5_000)
        assertFalse(r.getBoolean("ok"))
        assertEquals(502, r.getInt("error_code"))
    }

    @Test fun `an upload sends the fields and the file as multipart`() = runBlocking {
        val file = tmp.newFile("report.txt").apply { writeText("file body") }
        val r = api.upload("sendDocument", mapOf("chat_id" to "42", "caption" to "报告"), "document", file, "报告 \"v1\".txt", "text/plain")
        assertEquals(7, r.getJSONObject("result").getInt("message_id"))
        assertTrue(seen, seen.startsWith("multipart/form-data; boundary="))
        assertTrue(seen, "name=\"chat_id\"" in seen && "\r\n\r\n42\r\n" in seen)
        assertTrue(seen, "\r\n\r\n报告\r\n" in seen)
        assertTrue(seen, "name=\"document\"; filename=" in seen && "Content-Type: text/plain" in seen && "file body" in seen)
    }

    @Test fun `a download writes the file`() = runBlocking {
        val dest = java.io.File(tmp.root, "in/p.jpg")
        api.download("photos/p.jpg", dest)
        assertEquals("JPEGDATA", dest.readText())
    }
}
