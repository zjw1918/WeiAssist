package com.lanqi.weiassist.agent

import com.lanqi.weiassist.agent.VoiceServer.Health
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceServerTest {
    @Test fun `a bare address gets the scheme and the server's port`() {
        assertEquals("http://10.0.0.134:8765", VoiceServer.normalizeUrl(" 10.0.0.134 "))
        assertEquals("http://mac.local:8765", VoiceServer.normalizeUrl("mac.local/"))
        assertEquals("http://10.0.0.134:8765", VoiceServer.normalizeUrl("http://10.0.0.134"))
    }

    @Test fun `a port, https or a path is kept as written`() {
        assertEquals("http://10.0.0.134:9000", VoiceServer.normalizeUrl("10.0.0.134:9000"))
        assertEquals("https://stt.example.com", VoiceServer.normalizeUrl("https://stt.example.com/"))
        assertEquals("http://proxy.lan/whisper", VoiceServer.normalizeUrl("http://proxy.lan/whisper"))
    }

    @Test fun `a pasted v1 is dropped`() {
        assertEquals("http://10.0.0.134:8765", VoiceServer.normalizeUrl("http://10.0.0.134:8765/v1/"))
    }

    @Test fun `what can't be an address is refused`() {
        for (bad in listOf("", "   ", "not an address", "ftp://10.0.0.1", "http://")) assertNull(bad, VoiceServer.normalizeUrl(bad))
    }

    @Test fun `health says ready, loading or something else`() {
        assertEquals(Health.Ready("m"), VoiceServer.parseHealth(200, """{"ready":true,"model":"m","error":null}"""))
        assertEquals(Health.Loading, VoiceServer.parseHealth(503, """{"ready":false,"model":"m","error":"no disk"}"""))
        assertEquals(Health.Other, VoiceServer.parseHealth(404, """{"detail":"Not Found"}"""))
        assertEquals(Health.Other, VoiceServer.parseHealth(200, "<html>router</html>"))
    }
}
