package com.lanqi.phoneagent.pi

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
 * Kotlin supplies what a browser lacks: a loopback socket for Pi's OAuth callback and encrypted storage
 * for Pi's credentials. Messages from Pi are accepted only from the app's own asset origin.
 */
class PiEngine(context: Context, private val agentName: String) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 256)
    val messages: SharedFlow<JSONObject> = events

    private val store = SecureStore(appContext)
    private val prefs = appContext.getSharedPreferences("pi", Context.MODE_PRIVATE)
    private val ready = CompletableDeferred<JSONObject>()
    private val httpReplies = ConcurrentHashMap<String, CompletableDeferred<LoopbackServer.HttpReply>>()
    private val loopback = LoopbackServer(scope) { method, url ->
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<LoopbackServer.HttpReply>().also { httpReplies[id] = it }
        withContext(Dispatchers.Main) {
            js("http", JSONObject().put("port", OAUTH_PORT).put("id", id).put("url", url).put("method", method))
        }
        withTimeoutOrNull(10_000) { reply.await() }.also { httpReplies.remove(id) }
            ?: LoopbackServer.HttpReply(504, emptyMap(), "")
    }
    private lateinit var webView: WebView
    // A main-thread Handler keeps chunks in order. (Not webView.post: a WebView that is never attached
    // to a window queues posted work forever.)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val http = NativeHttp(scope) { fn, args -> main.post { webView.evaluateJavascript("PiHost.$fn($args)", null) } }

    /** Stable per-install ID; Pi sends it to OpenAI as the agent host ID during ChatGPT sign-in. */
    private val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun start(): JSONObject = withContext(Dispatchers.Main) {
        if (!::webView.isInitialized) {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                "This WebView is too old for Pi (no WebMessageListener)"
            }
            val assets = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
                .build()
            webView = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        assets.shouldInterceptRequest(request.url)
                }
            }
            WebViewCompat.addWebMessageListener(webView, "PiBridge", setOf(ORIGIN)) {
                _: WebView, message: WebMessageCompat, _: Uri, _: Boolean, _: JavaScriptReplyProxy ->
                onMessage(JSONObject(message.data ?: return@addWebMessageListener))
            }
            webView.loadUrl("$ORIGIN/assets/pi/index.html")
        }
        ready.await()
    }

    private fun onMessage(m: JSONObject) {
        when (m.optString("type")) {
            "ready" -> {
                Log.d(TAG, "Pi ${m.optString("pi")} ready")
                js("init", JSONObject().put("deviceId", deviceId).put("agentName", agentName).put("credentials", store.read()))
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
            else -> {
                if (m.optString("type") !in QUIET) Log.d(TAG, "← pi ${m.optString("type")}")
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
    suspend fun configure(provider: String, modelId: String, thinkingLevel: String, baseUrl: String?) {
        val cfg = JSONObject().put("provider", provider).put("modelId", modelId).put("thinkingLevel", thinkingLevel)
        if (!baseUrl.isNullOrBlank()) cfg.put("baseUrl", baseUrl)
        call("configure", cfg)
    }
    suspend fun prompt(text: String) = call("prompt", JSONObject().put("text", text))
    suspend fun abort() = call("abort", null)

    private suspend fun call(fn: String, arg: JSONObject?) {
        start()
        withContext(Dispatchers.Main) { js(fn, arg) }
    }

    private fun js(fn: String, arg: JSONObject?) {
        // JSONObject.quote turns the JSON into a safe JS string literal.
        webView.evaluateJavascript("PiHost.$fn(${arg?.let { JSONObject.quote(it.toString()) } ?: ""})", null)
    }

    fun destroy() {
        loopback.close(OAUTH_PORT)
        if (::webView.isInitialized) webView.destroy()
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val OAUTH_PORT = 1455
        private const val TAG = "PiEngine"
        private val QUIET = setOf("text_delta", "auth_prompt", "auth_event")
    }
}
