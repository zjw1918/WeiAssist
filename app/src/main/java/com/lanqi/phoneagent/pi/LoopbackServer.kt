package com.lanqi.phoneagent.pi

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The socket behind Pi's `node:http` shim. Pi's login flows ask for a callback server (e.g. "Sign in
 * with ChatGPT" uses 127.0.0.1:1455); the browser is redirected there after sign-in. Each request's URL goes to Pi (onRequest),
 * Pi's own handler checks state and returns the page, which this class writes back to Chrome.
 * Bound to loopback only, so nothing outside the phone can reach it.
 */
class LoopbackServer(
    private val scope: CoroutineScope,
    private val onRequest: suspend (port: Int, method: String, url: String) -> HttpReply,
) {
    data class HttpReply(val status: Int, val headers: Map<String, String>, val body: String)

    private val servers = mutableMapOf<Int, Pair<ServerSocket, Job>>()

    /** Throws java.net.BindException when the port is taken (e.g. another app's login). */
    fun listen(port: Int) {
        close(port)
        val socket = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        val job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch {
                    // A browser may give up before we answer (e.g. the user closed the tab while the app
                    // was frozen). The request was already read and handed to Pi; only the reply is lost.
                    try { serve(port, client) } catch (e: java.io.IOException) { Log.d(TAG, "client went away: ${e.message}") }
                }
            }
        }
        servers[port] = socket to job
    }

    fun closeAll() = servers.keys.toList().forEach(::close)

    fun close(port: Int) {
        servers.remove(port)?.let { (socket, job) -> runCatching { socket.close() }; job.cancel() }
    }

    private suspend fun serve(port: Int, client: Socket): Unit = client.use {
        val reader = client.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        while (!reader.readLine().isNullOrEmpty()) Unit // skip headers
        val (method, path) = requestLine.split(" ").let { it[0] to it.getOrElse(1) { "/" } }
        Log.d(TAG, "$method ${path.substringBefore('?')}") // never log the query: it holds the auth code
        val reply = onRequest(port, method, path)
        var body = reply.body
        if (reply.status == 200 && path.startsWith("/auth/callback")) body = body.replace("</body>", BACK_LINK + "</body>")
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${reply.status} OK\r\n")
            reply.headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        }
        client.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
        Unit
    }

    companion object {
        private const val TAG = "LoopbackServer"
        // Chrome only follows an app link on a user tap, so the success page gets a big button back.
        private const val BACK_LINK =
            """<p style="text-align:center;margin-top:32px"><a href="phoneagent://signed-in" style="font:600 20px sans-serif;padding:14px 28px;border-radius:24px;background:#1b5fa8;color:#fff;text-decoration:none">返回小维助手</a></p>"""
    }
}
