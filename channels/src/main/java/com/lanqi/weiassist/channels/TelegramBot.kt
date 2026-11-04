package com.lanqi.weiassist.channels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * A Telegram bot, through the Bot API (https://core.telegram.org/bots/api). It receives by long polling
 * (`getUpdates`), so the phone only makes outgoing HTTPS requests: no webhook, no server. Messages are
 * sent as Telegram HTML; if Telegram refuses the markup, as plain text.
 *
 * [offset] is the next update to ask for; [onOffset] is told each time it moves, so a restart doesn't
 * handle a message twice. The token is part of every request's URL: it never goes into a log or an error.
 */
class TelegramBot(
    private val token: String,
    private var offset: Long = 0,
    private val onOffset: (Long) -> Unit = {},
    private val api: Api = HttpApi(token),
) : Channel {
    /** One Bot API call: the decoded response (`ok`, `result`, or `error_code`, `description`, `parameters`). */
    fun interface Api {
        suspend fun call(method: String, params: JSONObject, timeoutMs: Int): JSONObject
    }

    data class Me(val id: Long, val username: String, val name: String)

    override val id = ID
    private val _link = MutableStateFlow<Link>(Link.Off)
    override val link: StateFlow<Link> = _link.asStateFlow()

    /** Who the bot is; also checks the token (a [ChannelException], fatal, if Telegram rejects it). */
    suspend fun me(): Me {
        val r = request("getMe", JSONObject()) as JSONObject
        return Me(r.getLong("id"), r.optString("username"), r.optString("first_name"))
    }

    /** The commands Telegram offers in the chat's menu: (command without the slash, description). */
    suspend fun setCommands(commands: List<Pair<String, String>>) {
        val list = JSONArray(commands.map { (c, d) -> JSONObject().put("command", c).put("description", d) })
        request("setMyCommands", JSONObject().put("commands", list))
    }

    override suspend fun receive(onMessage: suspend (Inbound) -> Unit) {
        _link.value = Link.Connecting
        var backoff = MIN_BACKOFF_MS
        try {
            // A bot with a webhook can't be polled. Pending messages are kept.
            request("deleteWebhook", JSONObject().put("drop_pending_updates", false))
            while (true) {
                val updates = try {
                    val params = JSONObject().put("offset", offset).put("timeout", POLL_SECONDS)
                        .put("allowed_updates", JSONArray(listOf("message")))
                    request("getUpdates", params, timeoutMs = (POLL_SECONDS + 15) * 1000) as? JSONArray ?: JSONArray()
                } catch (e: ChannelException) {
                    if (e.fatal) throw e
                    _link.value = Link.Retrying(e.message.orEmpty())
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }
                _link.value = Link.Online
                backoff = MIN_BACKOFF_MS
                for (i in 0 until updates.length()) {
                    val u = updates.getJSONObject(i)
                    // Moved before handling: a message that makes us fail is not delivered again and again.
                    offset = u.getLong("update_id") + 1
                    onOffset(offset)
                    parseUpdate(u)?.let { onMessage(it) }
                }
            }
        } finally {
            _link.value = Link.Off
        }
    }

    override suspend fun send(chat: ChatRef, text: Rich): String {
        val params = JSONObject().put("chat_id", chat.chat)
            .put("link_preview_options", JSONObject().put("is_disabled", true))
        val r = withMarkup(params, text) { request("sendMessage", it) }
        return (r as JSONObject).getLong("message_id").toString()
    }

    override suspend fun edit(chat: ChatRef, message: String, text: Rich) {
        val params = JSONObject().put("chat_id", chat.chat).put("message_id", message.toLong())
            .put("link_preview_options", JSONObject().put("is_disabled", true))
        try {
            withMarkup(params, text) { request("editMessageText", it) }
        } catch (e: ChannelException) {
            // The same text again: nothing to change.
            if ("message is not modified" !in e.message.orEmpty()) throw e
        }
    }

    override suspend fun typing(chat: ChatRef) {
        request("sendChatAction", JSONObject().put("chat_id", chat.chat).put("action", "typing"))
    }

    /** As HTML; as plain text if Telegram can't parse the markup. */
    private suspend fun withMarkup(params: JSONObject, text: Rich, f: suspend (JSONObject) -> Any): Any = try {
        f(JSONObject(params.toString()).put("text", text.markup).put("parse_mode", "HTML"))
    } catch (e: ChannelException) {
        if ("can't parse entities" !in e.message.orEmpty()) throw e
        f(JSONObject(params.toString()).put("text", text.plain))
    }

    /**
     * One call, its `result`. Flood control (429) is waited out once when it is short; a rejected token (401, or 404
     * for one that is malformed) is fatal. Network failures are [ChannelException]s that may be retried.
     */
    private suspend fun request(method: String, params: JSONObject, timeoutMs: Int = TIMEOUT_MS): Any {
        var waited = false
        while (true) {
            val r = try {
                api.call(method, params, timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw ChannelException(clean(e.message ?: e.javaClass.simpleName))
            }
            if (r.optBoolean("ok")) return r.opt("result") ?: JSONObject()
            val code = r.optInt("error_code")
            val description = clean(r.optString("description"))
            val retryAfter = r.optJSONObject("parameters")?.optLong("retry_after") ?: 0
            if (code == 429 && !waited && retryAfter in 1..MAX_FLOOD_WAIT_S) { waited = true; delay(retryAfter * 1000); continue }
            throw ChannelException("$code $description".trim(), fatal = code == 401 || code == 404)
        }
    }

    private fun clean(s: String) = if (token.isEmpty()) s else s.replace(token, "<token>")

    /** Requests over HttpURLConnection; cancelling the coroutine closes the connection (ends a long poll at once). */
    class HttpApi(private val token: String, private val base: String = "https://api.telegram.org") : Api {
        override suspend fun call(method: String, params: JSONObject, timeoutMs: Int): JSONObject = withContext(Dispatchers.IO) {
            val conn = URL("$base/bot$token/$method").openConnection() as HttpURLConnection
            val closing = coroutineContext[Job]?.invokeOnCompletion { conn.disconnect() }
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(params.toString().toByteArray()) }
                val code = conn.responseCode
                val body = (if (code < 400) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) }.orEmpty()
                runCatching { JSONObject(body) }.getOrElse { JSONObject().put("ok", false).put("error_code", code).put("description", "HTTP $code") }
            } finally {
                closing?.dispose()
                conn.disconnect()
            }
        }
    }

    companion object {
        const val ID = "telegram"
        private const val POLL_SECONDS = 50
        private const val TIMEOUT_MS = 30_000
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val MAX_FLOOD_WAIT_S = 30L

        /** A Bot API token as BotFather gives it: "<bot id>:<secret>". */
        fun looksLikeToken(s: String) = Regex("""^\d{5,}:[A-Za-z0-9_-]{30,}$""").matches(s.trim())

        /** An update to an [Inbound], or null when it isn't a message to answer (an edit, a service message). */
        fun parseUpdate(u: JSONObject): Inbound? {
            val m = u.optJSONObject("message") ?: return null
            val from = m.optJSONObject("from") ?: return null
            if (from.optBoolean("is_bot")) return null
            val chat = m.optJSONObject("chat") ?: return null
            val text = m.optString("text").ifEmpty { m.optString("caption") }
            val media = listOf("photo", "document", "voice", "audio", "video", "video_note", "sticker", "location", "contact")
                .any { m.has(it) }
            if (text.isEmpty() && !media) return null
            val name = listOf(from.optString("first_name"), from.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { from.optString("username") }
            return Inbound(
                chat = ChatRef(ID, chat.get("id").toString()),
                from = Sender(from.get("id").toString(), name),
                text = text,
                private = chat.optString("type") == "private",
                unsupported = media,
            )
        }
    }
}
