package com.lanqi.weiassist.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * The app's HTTP client (OkHttp). There is one: every request shares its connection pool (keep-alive, so a
 * provider's TLS handshake happens once), its threads and its TLS sessions. A use with its own timeouts or
 * redirects takes [with], a copy that still shares all of that.
 *
 * Bodies come back as they arrive (streamed), and gzip is undone transparently, unless the request sets its own
 * Accept-Encoding: then the body is left as it came.
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** The shared client with these timeouts (ms; 0 = none). [callMs] limits the whole call, body included. */
    fun with(connectMs: Long, readMs: Long, callMs: Long = 0, followRedirects: Boolean = true): OkHttpClient =
        client.newBuilder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(maxOf(readMs, connectMs), TimeUnit.MILLISECONDS)
            .callTimeout(callMs, TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .build()

    /**
     * A request body of [bytes] for [method]: methods that must have a body get an empty one. Without [contentType]
     * the body has no type of its own, so the request's Content-Type header goes as the caller set it.
     */
    fun body(method: String, bytes: ByteArray?, contentType: String? = null): RequestBody? = when {
        bytes != null && bytes.isNotEmpty() -> bytes.toRequestBody(contentType?.toMediaType())
        method.uppercase() in NEEDS_BODY -> ByteArray(0).toRequestBody(contentType?.toMediaType())
        else -> null
    }

    private val NEEDS_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

    /** A GET of [url] with [headers]. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
}

/**
 * Runs the call on the IO threads and hands [read] the response, which is closed after it. Cancelling the coroutine
 * cancels the call at once, also while [read] waits for the body (e.g. a long poll), so the request ends with it.
 */
suspend fun <T> Call.await(read: (Response) -> T): T = withContext(Dispatchers.IO) {
    val call = this@await
    // A child that ends the call when this coroutine is cancelled; a blocked read then fails at once.
    val stopper = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        call.execute().use(read)
    } catch (e: Exception) {
        // Cancelled: the closed socket's error is only how the call ended, so report the cancellation instead.
        ensureActive()
        throw e
    } finally {
        stopper.cancel()
    }
}
