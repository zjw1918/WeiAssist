package com.lanqi.phoneagent.pi

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Carries out Pi's fetch() calls (see pi-host/src/native-fetch.ts) with the platform HTTP stack:
 * no CORS, the app's network security config applies, and the body streams back chunk by chunk.
 * Only scheme, host and path are logged: headers carry the API key / token, queries may carry codes.
 */
class NativeHttp(private val scope: CoroutineScope, private val toJs: (fn: String, args: String) -> Unit) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val conns = ConcurrentHashMap<String, HttpURLConnection>()

    fun start(m: JSONObject) {
        val id = m.getString("id")
        jobs[id] = scope.launch(Dispatchers.IO) {
            val url = URL(m.getString("url"))
            val method = m.getString("method")
            Log.d(TAG, "$method ${url.protocol}://${url.host}${url.path}")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 30_000
                    readTimeout = 10 * 60_000 // long model turns stream slowly
                    instanceFollowRedirects = true
                    m.optJSONObject("headers")?.let { h -> h.keys().forEach { setRequestProperty(it, h.getString(it)) } }
                }
                conns[id] = conn
                val body = m.optString("body").takeIf { m.has("body") && !m.isNull("body") }
                if (body != null) {
                    conn.doOutput = true
                    val bytes = Base64.decode(body, Base64.NO_WRAP)
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val status = conn.responseCode
                Log.d(TAG, "$status ${url.host}${url.path}")
                val headers = JSONObject()
                conn.headerFields.forEach { (k, v) ->
                    // HttpURLConnection already un-gzipped the body, so drop the framing headers.
                    if (k != null && k.lowercase() !in DROP) headers.put(k.lowercase(), v.joinToString(", "))
                }
                val head = JSONObject().put("id", id).put("status", status).put("statusText", conn.responseMessage ?: "").put("headers", headers)
                toJs("fetchHead", q(head.toString()))
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                stream?.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        ensureActive()
                        toJs("fetchChunk", "${q(id)}, ${q(Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))}")
                    }
                }
                Log.d(TAG, "end ${url.host}${url.path}")
                toJs("fetchEnd", q(id))
            } catch (e: Exception) {
                Log.w(TAG, "${url.host}${url.path} failed: ${e.javaClass.simpleName}")
                if (e !is kotlinx.coroutines.CancellationException) toJs("fetchError", "${q(id)}, ${q(e.message ?: e.javaClass.simpleName)}")
            } finally {
                conn?.disconnect()
                conns.remove(id)
                jobs.remove(id)
            }
        }
    }

    fun abort(id: String) {
        jobs.remove(id)?.cancel()
        conns.remove(id)?.disconnect() // unblocks a read that is waiting on the network
    }

    private fun q(s: String) = JSONObject.quote(s)

    companion object {
        private const val TAG = "NativeHttp"
        private val DROP = setOf("content-encoding", "content-length", "transfer-encoding")
    }
}
