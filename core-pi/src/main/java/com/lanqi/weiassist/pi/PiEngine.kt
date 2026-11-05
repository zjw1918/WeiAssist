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
import com.lanqi.weiassist.core.AgentCore
import com.lanqi.weiassist.core.CoreAccounts
import com.lanqi.weiassist.core.CoreInfo
import com.lanqi.weiassist.core.SecureStore
import com.lanqi.weiassist.core.SessionConfig
import com.lanqi.weiassist.core.ToolHost
import com.lanqi.weiassist.core.ToolSet
import com.lanqi.weiassist.core.UserInput
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
 * encrypted storage for Pi's credentials. Tools (phone, bash, …) are the app's: pi-host's `bridgeCall`s go to
 * the [ToolHost] it is bound to. Messages from Pi are accepted only from the app's own asset origin.
 *
 * The app's first [AgentCore], and its [CoreAccounts]: Pi keeps the sign-ins and the model catalog.
 */
class PiEngine(context: Context, private val agentName: String) : AgentCore, CoreAccounts {
    override val info = CoreInfo(ID, "Pi")
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 256)
    override val events: SharedFlow<JSONObject> = _events

    private val store = SecureStore(appContext, "pi-credentials")
    /** Answers pi-host's `bridgeCall(type, …)` requests (tools, gate, confirm). */
    @Volatile private var host: ToolHost? = null
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
    override fun bind(host: ToolHost) { this.host = host }

    override suspend fun start(): JSONObject = withContext(Dispatchers.Main) {
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
    override fun debugCrash() { main.post { webView?.loadUrl("chrome://crash") } }

    /** Replace a dead runtime: tell listeners (a running task has ended), then boot Pi again. */
    private fun restart() {
        webView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
        webView = null
        loopback.closeAll()
        httpReplies.values.forEach { it.cancel() }
        httpReplies.clear()
        if (ready.isCompleted) ready = CompletableDeferred()
        _events.tryEmit(JSONObject().put("type", "runtime_restarted"))
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
            "fetch" -> http.start(m)
            "fetch_abort" -> http.abort(m.getString("id"))
            "http_close" -> loopback.close(m.getInt("port"))
            "http_response" -> {
                val headers = m.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } }.orEmpty()
                httpReplies[m.getString("id")]?.complete(LoopbackServer.HttpReply(m.getInt("status"), headers, m.optString("body")))
            }
            // A bridgeCall from pi-host (it has an id): the app's tools, gate and confirm.
            else -> if (m.has("id") && host?.handles(m.optString("type")) == true) {
                val h = host!!
                scope.launch {
                    val reply = JSONObject().put("id", m.getString("id"))
                    runCatching { h.call(m.getString("type"), m) }
                        .onSuccess { js("reply", reply.put("ok", true).put("value", it ?: JSONObject.NULL)) }
                        .onFailure { js("reply", reply.put("ok", false).put("value", JSONObject().put("message", it.message ?: it.javaClass.simpleName))) }
                }
            } else {
                if (m.optString("type") == "tool_start") Log.d(TAG, "← pi tool ${m.optString("tool")} ${m.optJSONObject("args")?.let(::redactArgs)}")
                if (m.optString("type") == "tool_end") Log.d(TAG, "← pi tool ${m.optString("tool")} done error=${m.optBoolean("isError")}")
                if (m.optString("type") !in QUIET) Log.d(TAG, "← pi ${m.optString("type")} ${m.optString("session")}")
                _events.tryEmit(m)
            }
        }
    }

    // ---- commands to Pi ------------------------------------------------------------------------
    override suspend fun login(provider: String, method: String) = call("login", JSONObject().put("provider", provider).put("type", method))
    override suspend fun answer(promptId: String, value: String) = call("answer", JSONObject().put("id", promptId).put("value", value))
    override suspend fun cancelLogin() = call("cancelLogin", null)
    override suspend fun logout(provider: String) = call("logout", JSONObject().put("provider", provider))
    /** Sent with init (also after a renderer restart). Set before [start]. */
    @Volatile override var customProviders: () -> org.json.JSONArray = { org.json.JSONArray() }
    /**
     * Pi's catalog entries for these model ids (pi-host lookupModels): { id: { contextWindow, images, match } },
     * only for ids Pi knows. Empty if Pi isn't running.
     */
    override suspend fun lookupModels(ids: List<String>): JSONObject {
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
    override fun savedApiKey(provider: String): String? = runCatching {
        JSONObject(store.read() ?: return null).optJSONObject(provider)?.takeIf { it.optString("type") == "api_key" }?.optString("key")
    }.getOrNull()?.ifEmpty { null }
    /** The custom providers changed; [key], if given, is [provider]'s new API key. */
    override suspend fun setCustomProviders(list: org.json.JSONArray, provider: String?, key: String?) =
        call("setCustomProviders", JSONObject().put("providers", list).apply { if (provider != null && key != null) put("provider", provider).put("key", key) })
    /** pi-host's `configure`: a new Pi Agent for the session, or new settings for its existing one. */
    override suspend fun configure(session: String, config: SessionConfig) {
        val m = config.model
        val cfg = JSONObject().put("session", session).put("provider", m.provider).put("modelId", m.model)
            .put("thinkingLevel", m.thinking)
        when (config.tools) {
            ToolSet.Phone -> cfg.put("tools", "phone")
            ToolSet.Supervisor -> cfg.put("tools", "supervisor")
            ToolSet.Chat -> Unit
        }
        if (!config.bash) cfg.put("bash", false)
        if (config.channel != null) cfg.put("channel", config.channel)
        if (config.systemPrompt != null) cfg.put("systemPrompt", config.systemPrompt)
        if (!m.baseUrl.isNullOrBlank()) cfg.put("baseUrl", m.baseUrl)
        // Without saved messages, an existing session keeps its conversation.
        if (config.history != null) cfg.put("messages", config.history)
        call("configure", cfg)
    }
    override suspend fun prompt(session: String, input: UserInput) = call("prompt", input.json(session))
    /** Pi reads it after the current turn (Agent.steer). */
    override suspend fun steer(session: String, input: UserInput) = call("steer", input.json(session))
    override suspend fun abort(session: String) = call("abort", JSONObject().put("session", session))
    override suspend fun dispose(session: String) = call("dispose", JSONObject().put("session", session))

    private fun UserInput.json(session: String) = JSONObject().put("session", session).put("text", text)
        .apply { content?.takeIf { it.length() > 0 }?.let { put("content", it) } }

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

    override fun destroy() {
        loopback.closeAll()
        webView?.destroy()
        webView = null
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        /** Pi's core id: saved with each session and the name of its transcript file (`<id>.pi.json`). */
        const val ID = "pi"
        private const val TAG = "PiEngine"
        private val QUIET = setOf("text_delta", "auth_prompt", "auth_event", "tool_start", "tool_end", "transcript")
    }
}
