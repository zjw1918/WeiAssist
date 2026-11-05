package com.lanqi.weiassist.dsh

import com.lanqi.weiassist.core.AgentCore
import com.lanqi.weiassist.core.CoreCapabilities
import com.lanqi.weiassist.core.CoreInfo
import com.lanqi.weiassist.core.SessionConfig
import com.lanqi.weiassist.core.SessionKind
import com.lanqi.weiassist.core.ToolHost
import com.lanqi.weiassist.core.UserInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * DeepSeek Harness (dsh) as an AI core. dsh needs Node 24, so it runs as its own process (in Termux; [launch] starts
 * it) with the app's bridge plugin (dsh-host/), which connects back to a loopback socket here and speaks the core
 * contract as JSON lines. The connection must present [secret] first, and only one is served at a time.
 *
 * - Commands (configure, prompt, steer, abort, dispose) go to dsh; its events come out of [events].
 * - Its host calls (before_tool, tool, tool_cancel) go to the [ToolHost], like Pi's.
 * - A lost connection is `runtime_restarted`: the app configures each session again (dsh resumes it from its own log),
 *   and the next command launches dsh again.
 *
 * dsh holds the model keys it is given ([keys], passed in its launch environment), so whatever else runs in Termux can
 * read them: a prototype's trade-off (a key proxy in the app would avoid it).
 */
class DshEngine(
    /** Starts dsh with this environment (the bridge's port and secret, model keys); an error to show, or null. */
    private val launch: suspend (env: Map<String, String>) -> String?,
    /** Model keys by provider route, put in dsh's launch environment (dsh reads them only at launch). */
    private val keys: () -> Map<String, String> = { emptyMap() },
    private val log: (String) -> Unit = {},
    /** How long dsh may take to boot and connect (installing nothing, but loading ~600 packages on a phone). */
    private val connectTimeoutMs: Long = 180_000,
) : AgentCore {
    override val info = CoreInfo(ID, "DeepSeek Harness", CoreCapabilities(thinkingLevels = false, images = false))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 256)
    override val events: SharedFlow<JSONObject> = _events

    @Volatile private var host: ToolHost? = null
    private val startLock = Mutex()
    private var server: ServerSocket? = null
    @Volatile private var connection: Connection? = null
    @Volatile private var connected: CompletableDeferred<JSONObject>? = null

    /** What dsh must say first; only processes the app started know it. */
    val secret: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private inner class Connection(val socket: Socket, val hello: JSONObject) {
        private val out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        fun send(m: JSONObject) = synchronized(this) {
            out.write(m.toString())
            out.write("\n")
            out.flush()
        }
        fun close() = runCatching { socket.close() }
    }

    override fun bind(host: ToolHost) { this.host = host }

    /** dsh connected (launching it if needed). Returns its hello. */
    override suspend fun start(): JSONObject = startLock.withLock {
        connection?.let { return it.hello }
        // 127.0.0.1 itself: Android's getLoopbackAddress() is ::1, which dsh's connection to 127.0.0.1 never reaches.
        val srv = server ?: ServerSocket(0, 1, LOOPBACK).also { server = it; accept(it) }
        val waiting = CompletableDeferred<JSONObject>().also { connected = it }
        val env = buildMap {
            put(ENV_PORT, srv.localPort.toString())
            put(ENV_SECRET, secret)
            keys().forEach { (route, key) -> put(keyEnv(route), key) }
        }
        launch(env)?.let { throw IllegalStateException(it) }
        log("dsh launched; waiting for it on port ${srv.localPort}")
        withTimeout(connectTimeoutMs) { waiting.await() }
    }

    /** Serve connections: the first line must be hello with the secret; anything else is closed at once. */
    private fun accept(srv: ServerSocket) = scope.launch {
        while (!srv.isClosed) {
            val socket = runCatching { srv.accept() }.getOrNull() ?: break
            launch { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val hello = runCatching {
            socket.soTimeout = 10_000
            JSONObject(reader.readLine() ?: "")
        }.getOrNull()
        if (hello?.optString("type") != "hello" || hello.optString("secret") != secret || connection != null) {
            log("dsh bridge: refused a connection")
            runCatching { socket.close() }
            return
        }
        socket.soTimeout = 0
        val c = Connection(socket, hello)
        connection = c
        log("dsh connected (pid ${hello.optInt("pid")})")
        connected?.complete(hello)
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val m = runCatching { JSONObject(line) }.getOrNull() ?: continue
                onMessage(c, m)
            }
        } catch (_: java.io.IOException) {
        } finally {
            c.close()
            if (connection === c) {
                connection = null
                log("dsh disconnected")
                // Its agents are gone with it: the app sets its sessions up again before their next task.
                _events.tryEmit(JSONObject().put("type", "runtime_restarted"))
            }
        }
    }

    private fun onMessage(c: Connection, m: JSONObject) {
        val type = m.optString("type")
        if (m.has("id") && type in HOST_CALLS) {
            val h = host
            scope.launch {
                val reply = JSONObject().put("type", "reply").put("id", m.getString("id"))
                // A null answer is an answer (before_tool's "go on"), not a missing host.
                runCatching { (h ?: throw IllegalStateException("no tool host")).call(type, m) }
                    .onSuccess { reply.put("ok", true).put("value", it ?: JSONObject.NULL) }
                    .onFailure { reply.put("ok", false).put("value", JSONObject().put("message", it.message ?: it.javaClass.simpleName)) }
                runCatching { c.send(reply) }
            }
            return
        }
        _events.tryEmit(m)
    }

    private suspend fun send(m: JSONObject) {
        start()
        val c = connection ?: throw IllegalStateException("dsh is not connected")
        kotlinx.coroutines.withContext(Dispatchers.IO) { c.send(m) }
    }

    override suspend fun configure(session: String, config: SessionConfig) {
        val m = config.model
        val cfg = JSONObject().put("type", "configure").put("session", session).put("provider", m.provider)
            .put("modelId", m.model).put("thinkingLevel", m.thinking)
        when (config.kind) {
            SessionKind.Phone -> cfg.put("tools", "phone")
            SessionKind.Supervisor -> cfg.put("tools", "supervisor")
            SessionKind.Chat -> Unit
        }
        if (!config.bash) cfg.put("bash", false)
        if (config.channel != null) cfg.put("channel", config.channel)
        if (config.systemPrompt != null) cfg.put("systemPrompt", config.systemPrompt)
        if (!m.baseUrl.isNullOrBlank()) cfg.put("baseUrl", m.baseUrl)
        // A saved transcript is dsh's marker: the bridge resumes the session from dsh's own log.
        if (config.history != null) cfg.put("messages", config.history)
        cfg.put("toolSpecs", JSONArray().apply {
            config.tools.forEach { t ->
                put(JSONObject().put("name", t.name).put("label", t.label).put("description", t.description)
                    .put("parameters", JSONObject(t.parameters)).put("parallel", t.parallel))
            }
        })
        send(cfg)
    }

    override suspend fun prompt(session: String, input: UserInput) = send(input.json("prompt", session))
    override suspend fun steer(session: String, input: UserInput) = send(input.json("steer", session))
    override suspend fun abort(session: String) {
        // Nothing to stop without a connection (and no reason to launch dsh for it).
        connection?.let { send(JSONObject().put("type", "abort").put("session", session)) }
    }
    override suspend fun dispose(session: String) {
        connection?.let { send(JSONObject().put("type", "dispose").put("session", session)) }
    }

    private fun UserInput.json(type: String, session: String) = JSONObject().put("type", type).put("session", session).put("text", text)
        .apply { content?.takeIf { it.length() > 0 }?.let { put("content", it) } }

    /** Ends dsh too: the bridge exits when its connection closes. */
    override fun destroy() {
        connection?.close()
        runCatching { server?.close() }
        scope.cancel()
    }

    companion object {
        const val ID = "dsh"
        const val ENV_PORT = "WEIASSIST_BRIDGE_PORT"
        const val ENV_SECRET = "WEIASSIST_BRIDGE_SECRET"
        private val HOST_CALLS = setOf("before_tool", "tool", "tool_cancel")
        /** Where the bridge connects (dsh-host: 127.0.0.1). */
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** The env var holding a route's key (the profile's llm-pi-ai apiKeyEnv; dsh-host keyEnv). */
        fun keyEnv(route: String) = "WEIASSIST_KEY_" + route.uppercase().replace(Regex("[^A-Z0-9]"), "_")
    }
}
