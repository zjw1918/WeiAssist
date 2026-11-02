package com.lanqi.weiassist.shell.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An adb client connection to this phone's adbd over Wireless debugging (the connect port):
 * CNXN → adbd answers STLS → TLS 1.3 with our paired [AdbKey] → CNXN, then `shell:` streams.
 * The protocol is adb's own (packages/modules/adb protocol.txt). Blocking: use off the main thread.
 */
class AdbConnection private constructor(private val socket: Socket, private val input: InputStream, private val output: OutputStream) : Closeable {
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val nextId = AtomicInteger(1)
    @Volatile private var closed = false
    private val reader = Thread(::readLoop, "adb-reader").apply { isDaemon = true; start() }

    /** A finished shell command: its exit code and what it printed (stdout and stderr). */
    data class Result(val code: Int, val output: String)

    /**
     * Run a command with adb's shell protocol v2 (`shell,v2,raw:`), as the adb client does: output comes in
     * framed packets (stdout, stderr, exit code). Waits at most [timeoutMs].
     */
    fun shell(command: String, timeoutMs: Long = 15_000): Result {
        val s = open("shell,v2,raw:$command")
        val buf = java.io.ByteArrayOutputStream() // the stream's raw bytes; packets may span WRTEs
        val out = ByteArrayOutputStream()
        var code = -1
        val deadline = System.currentTimeMillis() + timeoutMs
        loop@ while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) { s.close(); throw IOException("adb shell timed out: $command") }
            val chunk = s.data.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (chunk === END) break
            buf.write(chunk)
            // Packets: id (1 byte), length (4 bytes LE), data. 1 = stdout, 2 = stderr, 3 = exit code.
            var bytes = buf.toByteArray()
            var off = 0
            while (bytes.size - off >= 5) {
                val len = ByteBuffer.wrap(bytes, off + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (bytes.size - off - 5 < len) break
                when (bytes[off].toInt()) {
                    ID_STDOUT, ID_STDERR -> out.write(bytes, off + 5, len)
                    ID_EXIT -> code = if (len > 0) bytes[off + 5].toInt() and 0xff else 0
                }
                off += 5 + len
            }
            buf.reset()
            buf.write(bytes, off, bytes.size - off)
            if (code >= 0) { s.close(); break@loop }
        }
        return Result(code, out.toString(Charsets.UTF_8.name()))
    }

    private fun open(destination: String): Stream {
        if (closed) throw IOException("adb connection closed")
        val local = nextId.getAndIncrement()
        val s = Stream(local)
        streams[local] = s
        send(A_OPEN, local, 0, (destination + "\u0000").toByteArray())
        if (!s.opened.await()) { streams.remove(local); throw IOException("adbd refused $destination") }
        return s
    }

    private inner class Stream(val local: Int) {
        @Volatile var remote = 0
        val opened = Latch()
        val data = LinkedBlockingQueue<ByteArray>()
        fun close() { if (streams.remove(local) != null) runCatching { send(A_CLSE, local, remote, ByteArray(0)) } }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val m = readMessage(input)
                when (m.command) {
                    A_OKAY -> streams[m.arg1]?.let { s -> if (s.remote == 0) { s.remote = m.arg0; s.opened.set(true) } }
                    A_WRTE -> streams[m.arg1]?.let { s ->
                        s.data.add(m.data)
                        send(A_OKAY, s.local, s.remote, ByteArray(0)) // ready for more
                    }
                    A_CLSE -> streams.remove(m.arg1)?.let { s -> s.opened.set(false); s.data.add(END) }
                }
            }
        } catch (e: IOException) {
            // closed, or adbd went away (Wireless debugging switched off)
        } finally {
            closed = true
            streams.values.forEach { it.opened.set(false); it.data.add(END) }
            streams.clear()
        }
    }

    @Synchronized private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) = writeMessage(output, command, arg0, arg1, data)

    val isOpen get() = !closed

    override fun close() {
        closed = true
        runCatching { socket.close() }
        reader.interrupt()
    }

    /** A one-shot boolean result, for OPEN's OKAY or CLSE. */
    private class Latch {
        private val q = LinkedBlockingQueue<Boolean>(1)
        fun set(v: Boolean) { q.offer(v) }
        fun await(): Boolean = q.poll(10, TimeUnit.SECONDS) ?: false
    }

    internal class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    companion object {
        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_STLS = 0x534c5453
        const val A_AUTH = 0x48545541
        private const val A_VERSION = 0x01000001
        private const val A_STLS_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 1024 * 1024
        private val END = ByteArray(0)
        private const val ID_STDOUT = 1
        private const val ID_STDERR = 2
        private const val ID_EXIT = 3

        /**
         * Whether adbd's Wireless debugging (TLS) listens on [port]: it answers our CNXN with STLS.
         * Anything else on the loopback (or a closed port) is not it.
         */
        fun isAdbTls(host: String, port: Int, timeoutMs: Int = 400): Boolean = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                writeMessage(s.getOutputStream(), A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())
                readMessage(s.getInputStream()).command == A_STLS
            }
        }.getOrDefault(false)

        /** Connect and authenticate with [key], which adbd must already trust (see [AdbPairing]). */
        fun connect(host: String, port: Int, key: AdbKey): AdbConnection {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(host, port), 5_000)
                plain.tcpNoDelay = true
                plain.soTimeout = 10_000
                writeMessage(plain.getOutputStream(), A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())
                val first = readMessage(plain.getInputStream())
                when (first.command) {
                    A_STLS -> Unit
                    A_AUTH -> throw IOException("adbd wants legacy RSA auth: this port is not Wireless debugging's")
                    else -> throw IOException("unexpected adb reply ${Integer.toHexString(first.command)}")
                }
                writeMessage(plain.getOutputStream(), A_STLS, A_STLS_VERSION, 0, ByteArray(0))
                // adbd refuses the handshake if this key was never paired (or was revoked).
                val tls = try { AdbTls.wrap(plain, key) } catch (e: IOException) { throw NotPairedException(e) }
                val input = tls.inputStream
                val output = tls.outputStream
                val cnxn = try { readMessage(input) } catch (e: IOException) { throw NotPairedException(e) }
                if (cnxn.command != A_CNXN) throw IOException("no CNXN after TLS")
                tls.soTimeout = 0 // the reader thread blocks until data or close
                return AdbConnection(tls, input, output)
            } catch (e: Exception) {
                runCatching { plain.close() }
                throw e
            }
        }

        internal fun writeMessage(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
                .putInt(0) // no data checksum since A_VERSION 0x01000001
                .putInt(command.inv())
            out.write(header.array())
            out.write(data)
            out.flush()
        }

        internal fun readMessage(input: InputStream): Message {
            val header = readFully(input, 24)
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = b.int; val arg0 = b.int; val arg1 = b.int; val len = b.int; b.int; val magic = b.int
            if (magic != command.inv()) throw IOException("bad adb message")
            if (len < 0 || len > MAX_PAYLOAD) throw IOException("bad adb payload length $len")
            return Message(command, arg0, arg1, readFully(input, len))
        }

        private fun readFully(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw EOFException()
                off += r
            }
            return buf
        }
    }

    /** adbd didn't accept our key: pair again. */
    class NotPairedException(cause: Throwable) : IOException("This phone's adbd doesn't trust WeiAssist yet: pair first.", cause)
}
