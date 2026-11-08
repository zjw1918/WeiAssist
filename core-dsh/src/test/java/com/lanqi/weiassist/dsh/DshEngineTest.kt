package com.lanqi.weiassist.dsh

import com.lanqi.weiassist.core.ModelSelection
import com.lanqi.weiassist.core.SessionConfig
import com.lanqi.weiassist.core.SessionKind
import com.lanqi.weiassist.core.ToolHost
import com.lanqi.weiassist.core.ToolSpec
import com.lanqi.weiassist.core.UserInput
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DshEngineTest {
    /** A stand-in for dsh with the bridge plugin: connects to the engine and records what it is told. */
    private class FakeDsh(port: Int, secret: String) {
        val socket = Socket(DshEngine.LOOPBACK, port)
        private val out = socket.getOutputStream().writer()
        val received = LinkedBlockingQueue<JSONObject>()

        init {
            send(JSONObject().put("type", "hello").put("secret", secret).put("pid", 42))
            thread(isDaemon = true) {
                val r = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (true) received.add(JSONObject(r.readLine() ?: break))
            }
        }

        fun send(m: JSONObject) = synchronized(this) { out.write("$m\n"); out.flush() }
        fun next(): JSONObject = checkNotNull(received.poll(5, TimeUnit.SECONDS)) { "nothing received" }
    }

    private fun engine(onLaunch: (Map<String, String>) -> Unit): DshEngine =
        DshEngine(launch = { env -> onLaunch(env); null }, proxy = proxy, connectTimeoutMs = 5_000)

    private val proxy = KeyProxy({ name -> if (name == "openai") KeyProxy.Route("https://api.openai.com/v1", "sk-test") else null })

    @Test fun `launches dsh with its port, secret and the key proxy, never a key, and is ready on hello with the secret`() = runBlocking {
        var dsh: FakeDsh? = null
        var env: Map<String, String> = emptyMap()
        lateinit var e: DshEngine
        e = engine { env = it; dsh = FakeDsh(it.getValue(DshEngine.ENV_PORT).toInt(), it.getValue(DshEngine.ENV_SECRET)) }
        val hello = e.start()
        assertEquals(42, hello.getInt("pid"))
        assertEquals(e.secret, env[DshEngine.ENV_SECRET])
        // dsh gets the proxy and its token; the key stays in the app.
        assertEquals(proxy.token, env[DshEngine.ENV_PROXY_KEY])
        assertTrue(env.getValue(DshEngine.ENV_PROXY).startsWith("http://127.0.0.1:"))
        assertFalse(env.values.any { "sk-test" in it })
        assertEquals(hello, e.start()) // connected: not launched again
        dsh!!.socket.close()
        e.destroy()
    }

    @Test fun `a connection without the secret is refused`() = runBlocking {
        var port = 0
        val e = engine { port = it.getValue(DshEngine.ENV_PORT).toInt() }
        val starting = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { e.start() } }
        while (port == 0) kotlinx.coroutines.delay(10)
        val intruder = Socket(DshEngine.LOOPBACK, port)
        intruder.getOutputStream().write("""{"type":"hello","secret":"guess"}""".toByteArray() + '\n'.code.toByte())
        intruder.getOutputStream().flush()
        assertEquals(-1, intruder.getInputStream().read()) // closed on it
        val real = FakeDsh(port, e.secret)
        assertTrue(starting.await().isSuccess)
        real.socket.close()
        e.destroy()
    }

    @Test fun `commands go to dsh as JSON lines, with the session's tools`() = runBlocking {
        var dsh: FakeDsh? = null
        val e = engine { dsh = FakeDsh(it.getValue(DshEngine.ENV_PORT).toInt(), it.getValue(DshEngine.ENV_SECRET)) }
        val tap = ToolSpec("tap", "Tap", "Tap the element.", """{"type":"object","properties":{"id":{"type":"integer"}}}""")
        e.configure("s1", SessionConfig(ModelSelection("openai", "gpt", "low", "http://127.0.0.1:8787/v1"), SessionKind.Phone, listOf(tap)))
        // The model routes come first (here only the mock server's: a session with a baseUrl), then the session.
        assertTrue(dsh!!.next().getJSONObject("providers").has(DshEngine.MOCK_ROUTE))
        val cfg = dsh!!.next()
        assertEquals("configure", cfg.getString("type"))
        assertEquals("phone", cfg.getString("tools"))
        assertEquals("http://127.0.0.1:8787/v1", cfg.getString("baseUrl"))
        val spec = cfg.getJSONArray("toolSpecs").getJSONObject(0)
        assertEquals("tap", spec.getString("name"))
        assertEquals("integer", spec.getJSONObject("parameters").getJSONObject("properties").getJSONObject("id").getString("type"))
        e.prompt("s1", UserInput("hi"))
        assertEquals(listOf("prompt", "s1", "hi"), dsh!!.next().let { listOf(it.getString("type"), it.getString("session"), it.getString("text")) })
        e.abort("s1")
        assertEquals("abort", dsh!!.next().getString("type"))
        dsh!!.socket.close()
        e.destroy()
    }

    @Test fun `dsh's host calls reach the tool host, its events come out, and losing it is a runtime restart`() = runBlocking {
        var dsh: FakeDsh? = null
        val e = engine { dsh = FakeDsh(it.getValue(DshEngine.ENV_PORT).toInt(), it.getValue(DshEngine.ENV_SECRET)) }
        val host = ToolHost().apply {
            register("tool") { m -> JSONObject().put("text", "ran ${m.getString("name")}") }
            register("before_tool") { null }
        }
        e.bind(host)
        e.start()
        val idle = async { e.events.first { it.optString("type") == "idle" } }
        kotlinx.coroutines.yield()
        dsh!!.send(JSONObject().put("type", "tool").put("id", "d1").put("session", "s1").put("name", "tap"))
        val reply = withTimeout(5_000) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dsh!!.next() } }
        assertEquals(listOf("reply", "d1", true), listOf(reply.getString("type"), reply.getString("id"), reply.getBoolean("ok")))
        assertEquals("ran tap", reply.getJSONObject("value").getString("text"))
        dsh!!.send(JSONObject().put("type", "before_tool").put("id", "d2").put("session", "s1"))
        assertTrue(dsh!!.next().isNull("value"))
        dsh!!.send(JSONObject().put("type", "idle").put("session", "s1"))
        assertEquals("s1", withTimeout(5_000) { idle.await() }.getString("session"))
        val restarted = async { e.events.first { it.optString("type") == "runtime_restarted" } }
        kotlinx.coroutines.yield()
        dsh!!.socket.close()
        withTimeout(5_000) { restarted.await() }
        e.destroy()
    }

    @Test fun `abort and dispose don't launch dsh`() = runBlocking {
        var launched = false
        val e = engine { launched = true }
        e.abort("s1")
        e.dispose("s1")
        assertFalse(launched)
        e.destroy()
    }

    @Test fun `dsh gets the app's providers as routes through the proxy, before setup and only when they change`() = runBlocking {
        var dsh: FakeDsh? = null
        var models = JSONArray().put(JSONObject().put("id", "gpt-x").put("contextWindow", 400000).put("input", JSONArray().put("text")))
        val e = DshEngine(
            launch = { env -> dsh = FakeDsh(env.getValue(DshEngine.ENV_PORT).toInt(), env.getValue(DshEngine.ENV_SECRET)); null },
            proxy = proxy,
            routes = { JSONArray().put(JSONObject().put("id", "openai").put("name", "OpenAI").put("api", "openai-responses").put("models", models)) },
            connectTimeoutMs = 5_000,
        )
        val config = SessionConfig(ModelSelection("openai", "gpt-x", "low"))
        e.configure("s1", config)
        val routes = dsh!!.next()
        assertEquals("routes", routes.getString("type"))
        val openai = routes.getJSONObject("providers").getJSONObject("openai")
        assertEquals(DshEngine.ENV_PROXY_KEY, openai.getString("apiKeyEnv"))
        assertTrue(openai.getString("baseURL").matches(Regex("http://127\\.0\\.0\\.1:\\d+/openai")))
        assertEquals("gpt-x", openai.getJSONArray("models").getJSONObject(0).getString("id"))
        assertEquals("configure", dsh!!.next().getString("type"))
        e.configure("s2", config) // nothing changed: no routes again
        assertEquals("configure", dsh!!.next().getString("type"))
        models = JSONArray().put(JSONObject().put("id", "gpt-y"))
        e.configure("s3", config.copy(model = config.model.copy(baseUrl = "http://127.0.0.1:8787/v1")))
        val changed = dsh!!.next().getJSONObject("providers")
        assertEquals("gpt-y", changed.getJSONObject("openai").getJSONArray("models").getJSONObject(0).getString("id"))
        // Dev mode: the mock server stands in for the session's model, through the proxy too.
        assertTrue(changed.getJSONObject(DshEngine.MOCK_ROUTE).getString("baseURL").endsWith("/mock"))
        dsh!!.socket.close()
        e.destroy()
    }

    @Test fun `a direct route (the Gateway app) goes straight to its address with the direct key, not through the proxy`() = runBlocking {
        var dsh: FakeDsh? = null
        var env: Map<String, String> = emptyMap()
        val e = DshEngine(
            launch = { en -> env = en; dsh = FakeDsh(en.getValue(DshEngine.ENV_PORT).toInt(), en.getValue(DshEngine.ENV_SECRET)); null },
            proxy = proxy,
            routes = {
                JSONArray().put(JSONObject().put("id", "weiassist-gateway").put("name", "Gateway").put("api", "openai-completions")
                    .put("direct", "http://127.0.0.1:3425/v1").put("models", JSONArray().put(JSONObject().put("id", "codex/gpt-x"))))
            },
            directKey = "local",
            connectTimeoutMs = 5_000,
        )
        e.configure("s1", SessionConfig(ModelSelection("weiassist-gateway", "codex/gpt-x", "low")))
        val gw = dsh!!.next().getJSONObject("providers").getJSONObject("weiassist-gateway")
        assertEquals("http://127.0.0.1:3425/v1", gw.getString("baseURL"))
        assertEquals(DshEngine.ENV_DIRECT_KEY, gw.getString("apiKeyEnv"))
        assertEquals("local", env[DshEngine.ENV_DIRECT_KEY])
        assertFalse(gw.has("direct"))
        dsh!!.socket.close()
        e.destroy()
    }

    @Test fun `a session goes on the route its model is on, when a provider comes as one route per format`() = runBlocking {
        var dsh: FakeDsh? = null
        val e = DshEngine(
            launch = { en -> dsh = FakeDsh(en.getValue(DshEngine.ENV_PORT).toInt(), en.getValue(DshEngine.ENV_SECRET)); null },
            proxy = proxy,
            routes = {
                fun route(id: String, api: String, model: String) = JSONObject().put("id", id).put("name", "Gateway").put("api", api)
                    .put("direct", "http://127.0.0.1:3425/v1").put("models", JSONArray().put(JSONObject().put("id", model)))
                JSONArray().put(route("weiassist-gateway", "openai-responses", "codex/gpt-x"))
                    .put(route("weiassist-gateway--anthropic-messages", "anthropic-messages", "claude/claude-y"))
            },
            directKey = "local",
            connectTimeoutMs = 5_000,
        )
        e.configure("s1", SessionConfig(ModelSelection("weiassist-gateway", "claude/claude-y", "high")))
        assertEquals("routes", dsh!!.next().getString("type"))
        assertEquals("weiassist-gateway--anthropic-messages", dsh!!.next().getString("provider"))
        e.configure("s2", SessionConfig(ModelSelection("weiassist-gateway", "codex/gpt-x", "high")))
        assertEquals("weiassist-gateway", dsh!!.next().getString("provider"))
        dsh!!.socket.close()
        e.destroy()
    }
}
