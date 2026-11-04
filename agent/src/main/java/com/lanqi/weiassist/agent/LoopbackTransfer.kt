package com.lanqi.weiassist.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * Moving one file between Termux and this app. Termux runs as another Linux user, so neither can read the other's
 * files; Termux's curl talks to a one-shot HTTP endpoint on the phone's loopback instead, at a URL with a random
 * secret (other apps can reach the port, not guess the secret). Only one request is served, then it closes.
 * Pure Java sockets, so it is unit-tested on the JVM (LoopbackTransferTest).
 */
object LoopbackTransfer {
    /** Wait this long for Termux to connect, and for a slow request to finish. */
    private const val ACCEPT_MS = 60_000
    private const val READ_MS = 120_000

    /**
     * Receive one upload (`curl --data-binary @file <url>`) into [dest] while [block] runs with the URL; at most
     * [maxBytes]. Returns [block]'s result and whether a whole file arrived.
     */
    suspend fun <T> receive(dest: File, maxBytes: Long, block: suspend (url: String) -> T): Pair<T, Boolean> =
        serve(block) { method, length, body, out ->
            if (method != "POST" || length == null || length > maxBytes) { reply(out, 413); return@serve false }
            dest.parentFile?.mkdirs()
            // Cut off mid-way (Stop closes the connection): no half file left behind.
            val got = try { dest.outputStream().use { o -> copy(body, o, length) } } catch (e: java.io.IOException) { dest.delete(); throw e }
            val ok = got == length
            if (!ok) dest.delete()
            reply(out, if (ok) 200 else 400)
            ok
        }

    /** Serve [file] to one download (`curl -o <path> <url>`) while [block] runs with the URL. */
    suspend fun <T> send(file: File, block: suspend (url: String) -> T): Pair<T, Boolean> =
        serve(block) { method, _, _, out ->
            if (method != "GET") { reply(out, 405); return@serve false }
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${file.length()}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.flush()
            true
        }

    private suspend fun <T> serve(
        block: suspend (url: String) -> T,
        handle: (method: String, length: Long?, body: InputStream, out: java.io.OutputStream) -> Boolean,
    ): Pair<T, Boolean> = coroutineScope {
        val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        // 127.0.0.1 itself: Android's getLoopbackAddress() is ::1, which Termux's curl to 127.0.0.1 never reaches.
        val server = withContext(Dispatchers.IO) { ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) }
        server.soTimeout = ACCEPT_MS
        val url = "http://127.0.0.1:${server.localPort}/$secret"
        // The connection being served: closed too if the transfer is abandoned (Stop), so a blocked read ends at once.
        var client: Socket? = null
        val served = async(Dispatchers.IO) {
            var result: Boolean? = null
            try {
                // A request with the wrong secret (another app probing) is turned away; keep waiting for ours.
                while (result == null) result = server.accept().also { client = it }.use { one(it, secret, handle) }
            } catch (e: SocketTimeoutException) {
                // Termux never came.
            } catch (e: java.net.SocketException) {
                // Closed: block ended first.
            }
            result == true
        }
        try {
            val result = block(url)
            // block (the Termux command) is over: the transfer is done or will never come.
            server.close()
            result to served.await()
        } finally {
            runCatching { server.close() }
            // Abandoned (cancelled, or block failed): don't wait for a transfer nobody will use.
            if (!served.isCompleted) runCatching { client?.close() }
        }
    }

    /** One connection: null if it isn't ours (wrong secret), else whether the transfer worked. */
    private fun one(s: Socket, secret: String, handle: (String, Long?, InputStream, java.io.OutputStream) -> Boolean): Boolean? {
        s.soTimeout = READ_MS
        val input = s.getInputStream().buffered()
        val out = s.getOutputStream()
        val request = line(input) ?: return null
        val parts = request.split(' ')
        var length: Long? = null
        while (true) {
            val h = line(input) ?: return null
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0 && h.substring(0, i).trim().equals("Content-Length", ignoreCase = true)) length = h.substring(i + 1).trim().toLongOrNull()
        }
        if (parts.size < 2 || parts[1] != "/$secret") { reply(out, 403); return null }
        return handle(parts[0], length, input, out)
    }

    /** An HTTP header line (ASCII, CRLF), at most 8 KB; null at the end of the stream. */
    private fun line(input: InputStream): String? {
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.isEmpty()) null else b.toString()
            if (c == '\n'.code) return b.toString().trimEnd('\r')
            if (b.length > 8192) return null
            b.append(c.toChar())
        }
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, length: Long): Long {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return length - left
    }

    private fun reply(out: java.io.OutputStream, code: Int) {
        runCatching {
            out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }
    }
}
