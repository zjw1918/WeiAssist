package com.lanqi.weiassist.agent

import com.lanqi.weiassist.agent.VoiceServer.Health
import com.lanqi.weiassist.agent.VoiceServer.Transcript
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

    @Test fun `a transcript, a loading server and a refusal are told apart`() {
        assertEquals(Transcript.Text("你好"), VoiceServer.parseTranscript(200, """{"text":" 你好 "}"""))
        assertEquals(Transcript.Text(""), VoiceServer.parseTranscript(200, """{"text":""}"""))
        assertEquals(Transcript.Loading, VoiceServer.parseTranscript(503, """{"error":{"message":"not loaded"}}"""))
        assertEquals(Transcript.Rejected("bad audio"), VoiceServer.parseTranscript(400, """{"error":{"message":"bad audio"}}"""))
        assertEquals(Transcript.Rejected("HTTP 502"), VoiceServer.parseTranscript(502, "<html>"))
    }

    @Test fun `the body carries the language and the audio`() {
        val body = VoiceServer.transcriptionBody("B", byteArrayOf(65, 66), VoiceLanguage.Chinese).toString(Charsets.ISO_8859_1)
        assertEquals(
            "--B\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nzh\r\n" +
                "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.m4a\"\r\nContent-Type: audio/mp4\r\n\r\nAB\r\n--B--\r\n",
            body,
        )
    }

    @Test fun `speech is appended with a space only between latin words`() {
        assertEquals("hello", VoiceServer.append("", "hello"))
        assertEquals("open maps", VoiceServer.append("open", "maps"))
        assertEquals("open maps", VoiceServer.append("open ", "maps"))
        assertEquals("打开微信", VoiceServer.append("打开", "微信"))
        assertEquals("打开WeChat", VoiceServer.append("打开", "WeChat"))
    }

    @Test fun `health says ready, loading or something else`() {
        assertEquals(Health.Ready("m"), VoiceServer.parseHealth(200, """{"ready":true,"model":"m","error":null}"""))
        assertEquals(Health.Loading, VoiceServer.parseHealth(503, """{"ready":false,"model":"m","error":"no disk"}"""))
        assertEquals(Health.Other, VoiceServer.parseHealth(404, """{"detail":"Not Found"}"""))
        assertEquals(Health.Other, VoiceServer.parseHealth(200, "<html>router</html>"))
    }
}
