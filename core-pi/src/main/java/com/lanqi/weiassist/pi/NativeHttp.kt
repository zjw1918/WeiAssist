package com.lanqi.weiassist.pi

import android.util.Base64
import android.util.Log
import com.lanqi.weiassist.core.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Carries out Pi's fetch() calls (see pi-host/src/native-fetch.ts) with the app's HTTP client ([Http]):
 * no CORS, the app's network security config applies, and the body streams back chunk by chunk.
 * Connections are pooled, so a provider's TLS handshake happens once, not on every model request.
 * Only scheme, host and path are logged: headers carry the API key / token, queries may carry codes.
 */
class NativeHttp(private val scope: CoroutineScope, private val toJs: (fn: String, args: String) -> Unit) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()

    fun start(m: JSONObject) {
        val id = m.getString("id")
        jobs[id] = scope.launch(Dispatchers.IO) {
            val url = m.getString("url")
            val method = m.getString("method")
            val where = runCatching { java.net.URL(url).let { "${it.protocol}://${it.host}${it.path}" } }.getOrDefault("?")
            Log.d(TAG, "$method $where")
            val phases = Phases()
            try {
                val bytes = m.optString("body").takeIf { m.has("body") && !m.isNull("body") }?.let { Base64.decode(it, Base64.NO_WRAP) }
                val headers = m.optJSONObject("headers")
                val request = Request.Builder().url(url).apply {
                    headers?.keys()?.forEach { k -> if (k.lowercase() !in DROP_REQUEST) header(k, headers.getString(k)) }
                    // The body has no type of its own: the caller's Content-Type header goes as it was.
                    method(method, Http.body(method, bytes))
                }.build()
                val call = client.newBuilder().eventListener(phases).build().newCall(request)
                calls[id] = call
                ensureActive() // aborted before the call was there to cancel
                call.execute().use { response ->
                    Log.d(TAG, "${response.code} $where (${phases.summary()})")
                    val head = JSONObject()
                    response.headers.names().forEach { name ->
                        // The body comes back un-gzipped, so the framing headers no longer apply.
                        if (name.lowercase() !in DROP_RESPONSE) head.put(name.lowercase(), response.headers.values(name).joinToString(", "))
                    }
                    toJs("fetchHead", q(JSONObject().put("id", id).put("status", response.code).put("statusText", response.message).put("headers", head).toString()))
                    response.body.byteStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            ensureActive()
                            toJs("fetchChunk", "${q(id)}, ${q(Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))}")
                        }
                    }
                }
                Log.d(TAG, "end $where")
                toJs("fetchEnd", q(id))
            } catch (e: Exception) {
                Log.w(TAG, "$where failed: ${e.javaClass.simpleName}: ${e.message}")
                if (e !is kotlinx.coroutines.CancellationException) toJs("fetchError", "${q(id)}, ${q(e.message ?: e.javaClass.simpleName)}")
            } finally {
                calls.remove(id)
                jobs.remove(id)
            }
        }
    }

    fun abort(id: String) {
        jobs.remove(id)?.cancel()
        calls.remove(id)?.cancel() // unblocks a read that is waiting on the network
    }

    private fun q(s: String) = JSONObject.quote(s)

    /** Phase timings of one call (ms since it started), to tell a slow connection (TCP/TLS) from a slow server. */
    private class Phases : EventListener() {
        private val t0 = android.os.SystemClock.elapsedRealtime()
        private fun ms() = android.os.SystemClock.elapsedRealtime() - t0
        @Volatile private var connected = -1L
        @Volatile private var reused = true
        @Volatile private var sent = -1L
        @Volatile private var headers = -1L
        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { reused = false }
        override fun connectionAcquired(call: Call, connection: Connection) { connected = ms() }
        override fun requestHeadersEnd(call: Call, request: Request) { sent = ms() }
        override fun requestBodyEnd(call: Call, byteCount: Long) { sent = ms() }
        override fun responseHeadersEnd(call: Call, response: okhttp3.Response) { headers = ms() }
        fun summary() = "${if (reused) "reused connection" else "connected"} $connected ms, sent $sent ms, headers $headers ms"
    }

    companion object {
        private const val TAG = "NativeHttp"
        /** Long model turns stream slowly, with pauses while the model thinks. */
        private val client by lazy { Http.with(connectMs = 30_000, readMs = 10 * 60_000) }
        /** Set by the client itself; a caller's Accept-Encoding would also turn off the transparent un-gzipping. */
        private val DROP_REQUEST = setOf("host", "connection", "content-length", "transfer-encoding", "accept-encoding", "keep-alive")
        private val DROP_RESPONSE = setOf("content-encoding", "content-length", "transfer-encoding")
    }
}
