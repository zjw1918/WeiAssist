package com.lanqi.weiassist.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What Termux's curl does, done with HttpURLConnection. */
class LoopbackTransferTest {
    private fun post(url: String, body: ByteArray): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000 // a bug fails the test instead of hanging it
        c.requestMethod = "POST"; c.doOutput = true; c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        return c.responseCode.also { c.disconnect() }
    }

    private fun get(url: String): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        val code = c.responseCode
        return (code to if (code == 200) c.inputStream.use { it.readBytes() } else ByteArray(0)).also { c.disconnect() }
    }

    private val data = ByteArray(300_000) { (it % 251).toByte() }

    @Test fun receivesAnUpload() = runBlocking {
        val dest = File.createTempFile("lbt-in", ".bin").apply { delete(); deleteOnExit() }
        val (code, ok) = LoopbackTransfer.receive(dest, 1_000_000) { url -> withContext(Dispatchers.IO) { post(url, data) } }
        assertEquals(200, code)
        assertTrue(ok)
        assertArrayEquals(data, dest.readBytes())
    }

    @Test fun refusesTooLargeAndWrongSecret() = runBlocking {
        val dest = File.createTempFile("lbt-in", ".bin").apply { delete(); deleteOnExit() }
        val (code, ok) = LoopbackTransfer.receive(dest, 1000) { url -> withContext(Dispatchers.IO) { post(url, data) } }
        assertEquals(413, code)
        assertFalse(ok)
        val (codes, ok2) = LoopbackTransfer.receive(dest, 1_000_000) { url ->
            // Another last hex digit: always "0" would be the right secret one time in 16.
            val wrong = url.dropLast(1) + (if (url.last() == '0') "1" else "0")
            withContext(Dispatchers.IO) { listOf(post(wrong, data), post(url, data)) }
        }
        // A wrong secret is turned away, and the right one still gets through.
        assertEquals(listOf(403, 200), codes)
        assertTrue(ok2)
    }

    @Test fun servesADownload() = runBlocking {
        val src = File.createTempFile("lbt-out", ".bin").apply { writeBytes(data); deleteOnExit() }
        val (got, ok) = LoopbackTransfer.send(src) { url -> withContext(Dispatchers.IO) { get(url) } }
        assertEquals(200, got.first)
        assertArrayEquals(data, got.second)
        assertTrue(ok)
    }

    @Test fun abandoningAStalledUploadEndsAtOnce() = runBlocking {
        val dest = File.createTempFile("lbt-in", ".bin").apply { delete(); deleteOnExit() }
        val started = System.currentTimeMillis()
        val job = launch {
            LoopbackTransfer.receive(dest, 1_000_000) { url ->
                withContext(Dispatchers.IO) {
                    // A client that sends its headers, then nothing (curl stuck): the read would wait 120 s.
                    val u = URL(url)
                    val s = java.net.Socket(u.host, u.port)
                    s.getOutputStream().write("POST ${u.path} HTTP/1.1\r\nContent-Length: 1000\r\n\r\n".toByteArray())
                    s.getOutputStream().flush()
                }
                awaitCancellation()
            }
        }
        delay(300)
        job.cancelAndJoin() // the user pressed Stop
        assertTrue(System.currentTimeMillis() - started < 5_000)
        assertFalse(dest.exists())
    }

    @Test fun nobodyComes() = runBlocking {
        val dest = File.createTempFile("lbt-in", ".bin").apply { delete(); deleteOnExit() }
        val (_, ok) = LoopbackTransfer.receive(dest, 1000) { "Termux failed" }
        assertFalse(ok)
    }
}
