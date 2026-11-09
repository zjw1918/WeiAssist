package com.lanqi.weiassist.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class AppLifelineTest {
    private fun connect(l: AppLifeline, token: String) = Socket(InetAddress.getByName("127.0.0.1"), l.port).also {
        it.getOutputStream().write("$token\n".toByteArray())
        it.getOutputStream().flush()
    }

    private fun waitUntil(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 3000
        while (!what() && System.currentTimeMillis() < end) Thread.sleep(5)
    }

    @Test fun `a watcher with the token counts until it goes`() = AppLifeline("secret").use { l ->
        assertFalse(l.connected)
        val s = connect(l, "secret")
        waitUntil { l.connected }
        assertTrue(l.connected)
        s.close()
        waitUntil { !l.connected }
        assertFalse(l.connected)
    }

    @Test fun `someone without the token is hung up on and doesn't count`() = AppLifeline("secret").use { l ->
        val s = connect(l, "wrong")
        assertEquals(-1, s.getInputStream().read())
        assertFalse(l.connected)
    }

    @Test fun `a watcher that is told nothing sees the end only when the app goes`() {
        val l = AppLifeline("secret")
        val s = connect(l, "secret")
        waitUntil { l.connected }
        s.soTimeout = 100
        assertTrue(runCatching { s.getInputStream().read() }.exceptionOrNull() is java.net.SocketTimeoutException)
        l.close()
        s.close()
    }
}
