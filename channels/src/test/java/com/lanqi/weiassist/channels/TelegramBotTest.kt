package com.lanqi.weiassist.channels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TelegramBotTest {
    private val token = "123456789:AAbbccddeeffgghhiijjkkllmmnnooppqq"

    /** Answers each method from [answers] in turn; records the calls. */
    private class FakeApi(val answers: Map<String, ArrayDeque<JSONObject>>) : TelegramBot.Api {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        override suspend fun call(method: String, params: JSONObject, timeoutMs: Int): JSONObject {
            calls += method to params
            return answers[method]?.removeFirstOrNull() ?: throw CancellationException("no more $method")
        }
    }

    private fun ok(result: Any) = JSONObject().put("ok", true).put("result", result)
    private fun err(code: Int, d: String, retry: Long? = null) = JSONObject().put("ok", false).put("error_code", code).put("description", d)
        .also { if (retry != null) it.put("parameters", JSONObject().put("retry_after", retry)) }

    private fun update(id: Long, text: String, chatType: String = "private") = JSONObject().put("update_id", id).put("message", JSONObject()
        .put("message_id", 1).put("text", text)
        .put("from", JSONObject().put("id", 42).put("first_name", "Ann").put("is_bot", false))
        .put("chat", JSONObject().put("id", 42).put("type", chatType)))

    @Test fun parsesMessages() {
        val m = TelegramBot.parseUpdate(update(1, "hi"))!!
        assertEquals(ChatRef("telegram", "42"), m.chat)
        assertEquals(Sender("42", "Ann"), m.from)
        assertTrue(m.private)
        assertFalse(m.unsupported)
        assertFalse(TelegramBot.parseUpdate(update(1, "hi", "group"))!!.private)
        val photo = JSONObject(update(2, "").toString()).also { it.getJSONObject("message").remove("text"); it.getJSONObject("message").put("photo", JSONArray()) }
        assertTrue(TelegramBot.parseUpdate(photo)!!.unsupported)
        assertNull(TelegramBot.parseUpdate(JSONObject().put("update_id", 3).put("edited_message", JSONObject())))
    }

    @Test fun receivesAndMovesTheOffset() = runBlocking {
        val api = FakeApi(mapOf(
            "deleteWebhook" to ArrayDeque(listOf(ok(true))),
            "getUpdates" to ArrayDeque(listOf(ok(JSONArray().put(update(7, "a")).put(update(8, "b"))))),
        ))
        val offsets = mutableListOf<Long>()
        val got = mutableListOf<String>()
        val bot = TelegramBot(token, 5, { offsets += it }, api)
        try { bot.receive { got += it.text } } catch (_: CancellationException) {}
        assertEquals(listOf("a", "b"), got)
        assertEquals(listOf(8L, 9L), offsets)
        assertEquals(5L, api.calls.first { it.first == "getUpdates" }.second.getLong("offset"))
        assertEquals(Link.Off, bot.link.value)
    }

    @Test fun rejectedTokenIsFatalAndNeverShown() = runBlocking {
        val api = FakeApi(mapOf("getMe" to ArrayDeque(listOf(err(401, "Unauthorized for $token")))))
        try { TelegramBot(token, api = api).me(); fail() } catch (e: ChannelException) {
            assertTrue(e.fatal)
            assertFalse(e.message!!.contains(token))
        }
    }

    @Test fun badMarkupFallsBackToPlainText() = runBlocking {
        val api = FakeApi(mapOf("sendMessage" to ArrayDeque(listOf(err(400, "Bad Request: can't parse entities"), ok(JSONObject().put("message_id", 9))))))
        val id = TelegramBot(token, api = api).send(ChatRef("telegram", "42"), Rich("<b>x", "x"))
        assertEquals("9", id)
        assertEquals("HTML", api.calls[0].second.getString("parse_mode"))
        assertFalse(api.calls[1].second.has("parse_mode"))
        assertEquals("x", api.calls[1].second.getString("text"))
    }

    @Test fun unchangedEditIsFine() = runBlocking {
        val api = FakeApi(mapOf("editMessageText" to ArrayDeque(listOf(err(400, "Bad Request: message is not modified")))))
        TelegramBot(token, api = api).edit(ChatRef("telegram", "42"), "9", Rich("x", "x"))
    }

    @Test fun tokens() {
        assertTrue(TelegramBot.looksLikeToken(token))
        assertFalse(TelegramBot.looksLikeToken("hello"))
    }
}
