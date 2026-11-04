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
        val uploads = mutableListOf<Triple<String, String, Map<String, String>>>()
        override suspend fun upload(method: String, fields: Map<String, String>, fileField: String, file: java.io.File, name: String, mime: String): JSONObject {
            uploads += Triple(method, fileField, fields)
            return JSONObject().put("ok", true).put("result", JSONObject().put("message_id", 11))
        }
        val downloads = mutableListOf<String>()
        override suspend fun download(path: String, dest: java.io.File) { downloads += path; dest.writeText("x") }
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
        val voice = JSONObject(update(2, "").toString()).also { it.getJSONObject("message").remove("text"); it.getJSONObject("message").put("voice", JSONObject()) }
        assertTrue(TelegramBot.parseUpdate(voice)!!.unsupported)
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
        assertEquals(listOf(9L), offsets) // once per batch, before its messages are handled
        assertEquals(5L, api.calls.first { it.first == "getUpdates" }.second.getLong("offset"))
        assertEquals(Link.Off, bot.link.value)
    }

    @Test fun aNetworkFailureAtTheStartIsRetried() = runBlocking {
        val api = FakeApi(mapOf(
            "deleteWebhook" to ArrayDeque(listOf(err(502, "Bad Gateway"), ok(true))),
            "getUpdates" to ArrayDeque(listOf(ok(JSONArray().put(update(1, "hi"))))),
        ))
        val got = mutableListOf<String>()
        try { TelegramBot(token, api = api).receive { got += it.text } } catch (_: CancellationException) {}
        assertEquals(listOf("hi"), got)
        assertEquals(2, api.calls.count { it.first == "deleteWebhook" })
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

    @Test fun parsesButtonPresses() {
        val q = JSONObject().put("update_id", 4).put("callback_query", JSONObject().put("id", "cb1").put("data", "c:ABCD2345:y")
            .put("from", JSONObject().put("id", 42).put("first_name", "Ann"))
            .put("message", JSONObject().put("message_id", 77).put("chat", JSONObject().put("id", 42).put("type", "private"))))
        val m = TelegramBot.parseUpdate(q)!!
        assertEquals(Press("cb1", "c:ABCD2345:y", "77"), m.press)
        assertEquals(ChatRef("telegram", "42"), m.chat)
        assertEquals("42", m.from.id)
        assertEquals("", m.text)
    }

    @Test fun buttonsGoInOneRowAndAnEditWithoutThemRemovesThem() = runBlocking {
        val api = FakeApi(mapOf(
            "sendMessage" to ArrayDeque(listOf(ok(JSONObject().put("message_id", 5)))),
            "editMessageText" to ArrayDeque(listOf(ok(JSONObject()))),
            "answerCallbackQuery" to ArrayDeque(listOf(ok(true))),
        ))
        val bot = TelegramBot(token, api = api)
        val chat = ChatRef("telegram", "42")
        bot.send(chat, Rich("q", "q"), listOf(Button("Yes", "c:x:y"), Button("No", "c:x:n")))
        val row = api.calls[0].second.getJSONObject("reply_markup").getJSONArray("inline_keyboard").getJSONArray(0)
        assertEquals(2, row.length())
        assertEquals("c:x:n", row.getJSONObject(1).getString("callback_data"))
        bot.edit(chat, "5", Rich("done", "done"))
        assertFalse(api.calls[1].second.has("reply_markup"))
        bot.acknowledge(Press("cb1", "c:x:y", "5"), "Allowed")
        assertEquals("Allowed", api.calls[2].second.getString("text"))
    }

    private fun photoUpdate(id: Long, group: String?, caption: String?) = JSONObject().put("update_id", id).put("message", JSONObject()
        .put("message_id", id).put("from", JSONObject().put("id", 42).put("first_name", "Ann"))
        .put("chat", JSONObject().put("id", 42).put("type", "private"))
        .put("photo", JSONArray().put(JSONObject().put("file_id", "small$id")).put(JSONObject().put("file_id", "big$id").put("file_size", 1000)))
        .also { m -> if (group != null) m.put("media_group_id", group); if (caption != null) m.put("caption", caption) })

    @Test fun photosAndDocumentsAreFiles() {
        val p = TelegramBot.parseUpdate(photoUpdate(5, null, "look"))!!
        assertEquals("look", p.text)
        assertEquals(listOf(RemoteFile("big5", "photo-5.jpg", "image/jpeg", 1000)), p.files)
        assertFalse(p.unsupported)
        val doc = JSONObject(update(6, "").toString()).also {
            val m = it.getJSONObject("message"); m.remove("text")
            m.put("document", JSONObject().put("file_id", "d1").put("file_name", "data.xlsx").put("mime_type", "application/vnd.ms-excel").put("file_size", 34))
        }
        assertEquals(RemoteFile("d1", "data.xlsx", "application/vnd.ms-excel", 34), TelegramBot.parseUpdate(doc)!!.files.single())
    }

    @Test fun anAlbumIsOneMessage() {
        val batch = listOf(photoUpdate(1, "g", null), photoUpdate(2, "g", "both"), update(3, "after"), photoUpdate(4, "h", null))
        val ms = TelegramBot.parseBatch(batch)
        assertEquals(3, ms.size)
        assertEquals("both", ms[0].text)
        assertEquals(listOf("big1", "big2"), ms[0].files.map { it.id })
        assertEquals("after", ms[1].text)
        assertEquals(listOf("big4"), ms[2].files.map { it.id })
    }

    @Test fun imagesGoAsPhotosOtherFilesAsDocuments() = runBlocking {
        val api = FakeApi(emptyMap())
        val bot = TelegramBot(token, api = api)
        val chat = ChatRef("telegram", "42")
        val f = java.io.File.createTempFile("tgt", ".png").apply { writeText("png"); deleteOnExit() }
        assertEquals("11", bot.sendFile(chat, f, "chart.png", "image/png", "The chart"))
        bot.sendFile(chat, f, "report.pdf", "application/pdf")
        assertEquals(listOf("sendPhoto" to "photo", "sendDocument" to "document"), api.uploads.map { it.first to it.second })
        assertEquals("The chart", api.uploads[0].third["caption"])
        assertFalse(api.uploads[1].third.containsKey("caption"))
    }

    @Test fun downloadsGoThroughGetFile() = runBlocking {
        val api = FakeApi(mapOf("getFile" to ArrayDeque(listOf(ok(JSONObject().put("file_path", "photos/file_1.jpg"))))))
        val dest = java.io.File.createTempFile("tgd", ".jpg").apply { deleteOnExit() }
        TelegramBot(token, api = api).download(RemoteFile("big1", "p.jpg", "image/jpeg", 10), dest)
        assertEquals(listOf("photos/file_1.jpg"), api.downloads)
        try { TelegramBot(token, api = api).download(RemoteFile("x", "big.zip", "application/zip", 30L * 1024 * 1024), dest); fail() }
        catch (e: ChannelException) { assertTrue(e.message!!.contains("20 MB")) }
    }

    @Test fun tokens() {
        assertTrue(TelegramBot.looksLikeToken(token))
        assertFalse(TelegramBot.looksLikeToken("hello"))
    }
}
