package com.lanqi.weiassist.dsh

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Keeps the model keys in the app. dsh runs in Termux, where anything else running there (the model's own bash
 * commands included) can read what dsh holds. So dsh gets [token], not a key, and its model routes point here:
 * `http://127.0.0.1:<port>/<route>/…`. A request is forwarded to that route's real endpoint with the token replaced
 * by the route's key wherever it appears (Authorization, x-api-key, …: whatever the provider uses), and the answer
 * streams back as it comes.
 *
 * Not an open proxy: a request without the token is refused, and only the [routes] are reached. Keys are read on
 * every request (a sign-in's token is refreshed by its owner), so a new key or sign-in needs no dsh restart. Whoever reads the token can still spend through the proxy while
 * the app runs, but never learns a key.
 */
class KeyProxy(
    /**
     * A route's real endpoint and current key, asked per request (it may block, e.g. while a sign-in refreshes);
     * null: no such route, or no key for it.
     */
    private val resolve: (route: String) -> Route?,
    private val log: (String) -> Unit = {},
) {
    /**
     * [upstream]: the route's base URL (e.g. https://api.openai.com/v1); [key]: what replaces the token (an API key, or
     * a sign-in's access token); [headers]: any more the provider's auth needs.
     */
    data class Route(val upstream: String, val key: String, val headers: Map<String, String> = emptyMap())

    /** What dsh holds instead of keys. */
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Volatile private var server: ServerSocket? = null

    /** Listening (once), on 127.0.0.1. Returns the base URL dsh's routes start with. */
    @Synchronized
    fun start(): String {
        val srv = server ?: ServerSocket(0, 16, DshEngine.LOOPBACK).also { s ->
            server = s
            thread(isDaemon = true, name = "dsh-key-proxy") {
                while (!s.isClosed) {
                    val socket = runCatching { s.accept() }.getOrNull() ?: break
                    thread(isDaemon = true, name = "dsh-key-proxy-request") { serve(socket) }
                }
            }
        }
        return "http://127.0.0.1:${srv.localPort}"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            try {
                val request = readRequest(input) ?: return
                s.soTimeout = 0 // a model answer may take minutes
                forward(request, out)
            } catch (e: Exception) {
                log("key proxy: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { respond(out, 502, "proxy error") }
            }
        }
    }

    private class Request(val method: String, val target: String, val headers: List<Pair<String, String>>, val body: ByteArray)

    private fun forward(r: Request, out: OutputStream) {
        // Only dsh, which holds the token, may use the proxy.
        if (r.headers.none { (_, v) -> token in v } && token !in r.target) return respond(out, 403, "no token")
        val path = r.target.removePrefix("/")
        val routeName = path.substringBefore('/').substringBefore('?')
        val route = resolve(routeName) ?: return respond(out, 404, "no route $routeName (or no key for it)")
        val rest = path.removePrefix(routeName)
        val url = URL(route.upstream.trimEnd('/') + rest.replace(token, route.key))
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = r.method
        c.instanceFollowRedirects = false
        c.connectTimeout = 30_000
        c.readTimeout = 0
        for ((name, value) in r.headers) {
            if (name.lowercase() in DROPPED_REQUEST) continue
            c.setRequestProperty(name, value.replace(token, route.key))
        }
        route.headers.forEach { (name, value) -> c.setRequestProperty(name, value) }
        if (r.body.isNotEmpty()) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(r.body.size)
            c.outputStream.use { it.write(r.body) }
        }
        val code = c.responseCode
        val head = StringBuilder("HTTP/1.1 $code ${c.responseMessage ?: ""}\r\n")
        c.headerFields.forEach { (name, values) ->
            if (name == null || name.lowercase() in DROPPED_RESPONSE) return@forEach
            values.forEach { head.append(name).append(": ").append(it).append("\r\n") }
        }
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        // Streamed through as it comes (server-sent events): the connection's end ends the body.
        val body = (if (code >= 400) c.errorStream else c.inputStream) ?: return
        body.use {
            val buf = ByteArray(8192)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, text: String) {
        val body = text.toByteArray()
        out.write("HTTP/1.1 $code $text\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun readRequest(input: InputStream): Request? {
        val start = readLine(input) ?: return null
        val parts = start.split(' ')
        if (parts.size < 3) return null
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        fun header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        val body = when {
            header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            else -> header("Content-Length")?.toIntOrNull()?.let { n -> readExactly(input, n) } ?: ByteArray(0)
        }
        return Request(parts[0], parts[1], headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.ISO_8859_1.name())
            if (c == '\n'.code) return b.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            b.write(c)
            if (b.size() > 64 * 1024) throw IllegalStateException("header line too long")
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        require(n in 0..MAX_BODY) { "body too large" }
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw java.io.EOFException("body ended early")
            off += r
        }
        return buf
    }

    private fun readChunked(input: InputStream): ByteArray {
        val all = ByteArrayOutputStream()
        while (true) {
            val size = (readLine(input) ?: throw java.io.EOFException()).substringBefore(';').trim().toInt(16)
            if (size == 0) {
                while (readLine(input)?.isNotEmpty() == true) Unit // trailers
                return all.toByteArray()
            }
            all.write(readExactly(input, size))
            if (all.size() > MAX_BODY) throw IllegalStateException("body too large")
            readLine(input) // the chunk's CRLF
        }
    }

    private companion object {
        /** 64 MiB: a long conversation with screenshots. */
        const val MAX_BODY = 64 * 1024 * 1024
        /** Set by the connection itself, or hop-by-hop. Accept-Encoding: answers come back uncompressed. */
        val DROPPED_REQUEST = setOf("host", "connection", "content-length", "transfer-encoding", "accept-encoding", "keep-alive", "proxy-connection")
        /** The body goes back as it came off the connection, ended by closing it. */
        val DROPPED_RESPONSE = setOf("connection", "content-length", "transfer-encoding", "content-encoding", "keep-alive")
    }
}
