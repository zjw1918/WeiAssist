package com.lanqi.weiassist.pi

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.BindException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hosts Pi (pi-agent-core + pi-ai, bundled to assets/pi/host.js) in a WebView that is never shown.
 * The WebView is only a JavaScript runtime: Pi does sign-in, model calls, streaming and the agent loop.
 * Kotlin supplies what a browser lacks: native HTTP, a loopback socket for Pi's OAuth callbacks and
 * encrypted storage for Pi's credentials. Everything else (e.g. phone tools) is plugged in by the app
 * through [registerHostCall]. Messages from Pi are accepted only from the app's own asset origin.
 */
class PiEngine(context: Context, private val agentName: String) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 256)
    val messages: SharedFlow<JSONObject> = events

    private val store = SecureStore(appContext)
    /** App-provided handlers for requests from JS (`bridgeCall(type, …)` in pi-host), e.g. phone tools. */
    private val hostCalls = ConcurrentHashMap<String, suspend (JSONObject) -> Any?>()
    private val prefs = appContext.getSharedPreferences("pi", Context.MODE_PRIVATE)
    private var ready = CompletableDeferred<JSONObject>()
    private val httpReplies = ConcurrentHashMap<String, CompletableDeferred<LoopbackServer.HttpReply>>()
    private val loopback = LoopbackServer(scope, appContext.getString(R.string.pi_back_to_app, agentName)) { port, method, url ->
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<LoopbackServer.HttpReply>().also { httpReplies[id] = it }
        withContext(Dispatchers.Main) {
            js("http", JSONObject().put("port", port).put("id", id).put("url", url).put("method", method))
        }
        withTimeoutOrNull(10_000) { reply.await() }.also { httpReplies.remove(id) }
            ?: LoopbackServer.HttpReply(504, emptyMap(), "")
    }
    private var webView: WebView? = null
    // A main-thread Handler keeps chunks in order. (Not webView.post: a WebView that is never attached
    // to a window queues posted work forever.)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val http = NativeHttp(scope) { fn, args -> main.post { webView?.evaluateJavascript("PiHost.$fn($args)", null) } }

    /** Stable per-install ID; Pi sends it to OpenAI as the agent host ID during ChatGPT sign-in. */
    private val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun start(): JSONObject = withContext(Dispatchers.Main) {
        if (webView == null) {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                "This WebView is too old for Pi (no WebMessageListener)"
            }
            val assets = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
                .build()
            val view = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        assets.shouldInterceptRequest(request.url)

                    // The renderer (where Pi runs) died: out of memory, or killed by the system. Without
                    // this the whole app would crash. Drop the dead WebView and start a fresh Pi.
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        Log.w(TAG, "Pi's renderer is gone (crashed=${detail.didCrash()}); restarting Pi")
                        restart()
                        return true
                    }
                }
            }
            webView = view
            WebViewCompat.addWebMessageListener(view, "PiBridge", setOf(ORIGIN)) {
                _: WebView, message: WebMessageCompat, _: Uri, _: Boolean, _: JavaScriptReplyProxy ->
                onMessage(JSONObject(message.data ?: return@addWebMessageListener))
            }
            view.loadUrl("$ORIGIN/assets/pi/index.html")
        }
        ready.await()
    }

    /** Debug builds only (via AgentController): crash Pi's renderer to exercise [restart]. */
    fun debugCrashRenderer() { main.post { webView?.loadUrl("chrome://crash") } }

    /** Replace a dead runtime: tell listeners (a running task has ended), then boot Pi again. */
    private fun restart() {
        webView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
        webView = null
        loopback.closeAll()
        httpReplies.values.forEach { it.cancel() }
        httpReplies.clear()
        if (ready.isCompleted) ready = CompletableDeferred()
        events.tryEmit(JSONObject().put("type", "runtime_restarted"))
        scope.launch { runCatching { start() }.onFailure { Log.e(TAG, "Pi did not restart", it) } }
    }

    private fun onMessage(m: JSONObject) {
        when (m.optString("type")) {
            "ready" -> {
                Log.d(TAG, "Pi ${m.optString("pi")} ready")
                js("init", JSONObject().put("deviceId", deviceId).put("agentName", agentName).put("credentials", store.read())
                    .put("customProviders", customProviders()))
                ready.complete(m)
            }
            // Pi wrote its credential store: persist it encrypted before anything else happens (the
            // process may be frozen or killed right after a login). A few ms on the main thread. Never logged.
            "credentials_changed" -> runCatching { store.write(m.getString("json")) }
                .onFailure { Log.e(TAG, "could not save credentials: ${it.javaClass.simpleName}") }
            "http_listen" -> {
                val result = JSONObject().put("id", m.getString("id"))
                try {
                    loopback.listen(m.getInt("port"))
                    js("reply", result.put("ok", true))
                } catch (e: BindException) {
                    js("reply", result.put("ok", false).put("value", JSONObject().put("code", "EADDRINUSE").put("message", e.message)))
                }
            }
            in hostCalls.keys -> {
                val handler = hostCalls.getValue(m.getString("type"))
                scope.launch {
                    val reply = JSONObject().put("id", m.getString("id"))
                    runCatching { handler(m) }
                        .onSuccess { js("reply", reply.put("ok", true).put("value", it ?: JSONObject.NULL)) }
                        .onFailure { js("reply", reply.put("ok", false).put("value", JSONObject().put("message", it.message ?: it.javaClass.simpleName))) }
                }
            }
            "fetch" -> http.start(m)
            "fetch_abort" -> http.abort(m.getString("id"))
            "http_close" -> loopback.close(m.getInt("port"))
            "http_response" -> {
                val headers = m.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } }.orEmpty()
                httpReplies[m.getString("id")]?.complete(LoopbackServer.HttpReply(m.getInt("status"), headers, m.optString("body")))
            }
            else -> {
                if (m.optString("type") == "tool_start") Log.d(TAG, "← pi tool ${m.optString("tool")} ${m.optJSONObject("args")?.let(::redactArgs)}")
                if (m.optString("type") == "tool_end") Log.d(TAG, "← pi tool ${m.optString("tool")} done error=${m.optBoolean("isError")}")
                if (m.optString("type") !in QUIET) Log.d(TAG, "← pi ${m.optString("type")} ${m.optString("session")}")
                events.tryEmit(m)
            }
        }
    }

    // ---- commands to Pi ------------------------------------------------------------------------
    suspend fun refreshStatus() = call("status", null)
    suspend fun login(provider: String, type: String) = call("login", JSONObject().put("provider", provider).put("type", type))
    suspend fun answer(id: String, value: String) = call("answer", JSONObject().put("id", id).put("value", value))
    suspend fun cancelPrompt(id: String) = call("answer", JSONObject().put("id", id).put("cancel", true))
    suspend fun cancelLogin() = call("cancelLogin", null)
    suspend fun logout(provider: String) = call("logout", JSONObject().put("provider", provider))
    /**
     * The user's own providers, sent with init (also after a renderer restart). Set before [start].
     * Each: { id, name, baseUrl, models: [ids], images }.
     */
    @Volatile var customProviders: () -> org.json.JSONArray = { org.json.JSONArray() }
    /**
     * Pi's catalog entries for these model ids (pi-host lookupModels): { id: { contextWindow, images, match } },
     * only for ids Pi knows. Empty if Pi isn't running.
     */
    suspend fun lookupModels(ids: List<String>): JSONObject {
        start()
        val arg = JSONObject().put("ids", org.json.JSONArray(ids))
        val raw = withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                val w = webView
                if (w == null) cont.resumeWith(Result.success(null))
                // evaluateJavascript hands back the expression's value as JSON: here a JSON string literal.
                else w.evaluateJavascript("PiHost.lookupModels(${JSONObject.quote(arg.toString())})") { cont.resumeWith(Result.success(it)) }
            }
        }
        return runCatching { JSONObject(org.json.JSONTokener(raw).nextValue() as String) }.getOrDefault(JSONObject())
    }

    /** The API key saved for [provider], if it signed in with one (e.g. to list a custom server's models). */
    fun savedApiKey(provider: String): String? = runCatching {
        JSONObject(store.read() ?: return null).optJSONObject(provider)?.takeIf { it.optString("type") == "api_key" }?.optString("key")
    }.getOrNull()?.ifEmpty { null }
    /** The custom providers changed; [key], if given, is [provider]'s new API key. */
    suspend fun setCustomProviders(list: org.json.JSONArray, provider: String? = null, key: String? = null) =
        call("setCustomProviders", JSONObject().put("providers", list).apply { if (provider != null && key != null) put("provider", provider).put("key", key) })
    /**
     * Create or replace session [session]'s Pi Agent. [tools] names a tool set pi-host knows ("phone", "supervisor");
     * null = plain chat. [messages] restores a saved transcript (pi-host's `transcript` message); without
     * it, an existing session keeps its conversation.
     */
    suspend fun configure(
        session: String, provider: String, modelId: String, thinkingLevel: String, baseUrl: String?,
        tools: String? = null, systemPrompt: String? = null, messages: org.json.JSONArray? = null,
        /** False: no bash tool (Linux commands in Termux) either. */
        bash: Boolean = true,
    ) {
        val cfg = JSONObject().put("session", session).put("provider", provider).put("modelId", modelId)
            .put("thinkingLevel", thinkingLevel)
        if (tools != null) cfg.put("tools", tools)
        if (!bash) cfg.put("bash", false)
        if (systemPrompt != null) cfg.put("systemPrompt", systemPrompt)
        if (!baseUrl.isNullOrBlank()) cfg.put("baseUrl", baseUrl)
        if (messages != null) cfg.put("messages", messages)
        call("configure", cfg)
    }
    /** [content]: Pi content blocks that go with [text] (attached images, files), or null. */
    suspend fun prompt(session: String, text: String, content: org.json.JSONArray? = null) =
        call("prompt", JSONObject().put("session", session).put("text", text).apply { if (content != null && content.length() > 0) put("content", content) })
    /** A message for a session that is working: Pi reads it after the current turn (Agent.steer). */
    suspend fun steer(session: String, text: String, content: org.json.JSONArray? = null) =
        call("steer", JSONObject().put("session", session).put("text", text).apply { if (content != null && content.length() > 0) put("content", content) })
    suspend fun abort(session: String) = call("abort", JSONObject().put("session", session))
    /** Drop a session's Agent from the runtime (its saved transcript is the app's business). */
    suspend fun dispose(session: String) = call("dispose", JSONObject().put("session", session))

    /**
     * Answer requests that pi-host makes with `bridgeCall(type, …)`. The handler's return value
     * (String, Boolean, JSONObject…) resolves the JS promise; a thrown exception rejects it.
     */
    fun registerHostCall(type: String, handler: suspend (JSONObject) -> Any?) {
        check(type !in BUILT_IN) { "$type is handled by PiEngine itself" }
        hostCalls[type] = handler
    }

    private suspend fun call(fn: String, arg: JSONObject?) {
        start()
        withContext(Dispatchers.Main) { js(fn, arg) }
    }

    private fun js(fn: String, arg: JSONObject?) {
        // JSONObject.quote turns the JSON into a safe JS string literal.
        webView?.evaluateJavascript("PiHost.$fn(${arg?.let { JSONObject.quote(it.toString()) } ?: ""})", null)
    }

    /** Tool arguments for the log, without typed text (it may be private). */
    private fun redactArgs(a: JSONObject) = JSONObject(a.toString()).apply { if (has("text")) put("text", "…${getString("text").length} chars") }

    fun destroy() {
        loopback.closeAll()
        webView?.destroy()
        webView = null
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        private const val TAG = "PiEngine"
        private val BUILT_IN = setOf("ready", "credentials_changed", "http_listen", "fetch", "fetch_abort", "http_close", "http_response")
        private val QUIET = setOf("text_delta", "auth_prompt", "auth_event", "tool_start", "tool_end", "transcript")
    }
}
