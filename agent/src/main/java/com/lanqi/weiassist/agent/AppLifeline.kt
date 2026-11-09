package com.lanqi.weiassist.agent

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A local port a watcher in Termux stays connected to. When this process ends, however it ends (a force-stop gives
 * it no chance to run anything), the connection closes, and that is how the watcher knows. The watcher proves itself
 * with [token]: another app connecting must not count as the watcher being there.
 */
class AppLifeline(val token: String) : Closeable {
    // 127.0.0.1, not getLoopbackAddress(): that is ::1 on Android.
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val peers = AtomicInteger()
    val port get() = server.localPort
    /** A watcher is connected now. */
    val connected get() = peers.get() > 0

    init {
        thread(isDaemon = true, name = "app-lifeline") {
            while (true) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true, name = "app-lifeline-peer") { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) = socket.use {
        try {
            it.soTimeout = TOKEN_TIMEOUT_MS
            val input = it.getInputStream().bufferedReader()
            if (input.readLine() != token) return
            it.soTimeout = 0
            peers.incrementAndGet()
            try { while (input.read() != -1) Unit } finally { peers.decrementAndGet() }
        } catch (_: Exception) {
        }
    }

    override fun close() = server.close()

    private companion object {
        const val TOKEN_TIMEOUT_MS = 5_000
    }
}
