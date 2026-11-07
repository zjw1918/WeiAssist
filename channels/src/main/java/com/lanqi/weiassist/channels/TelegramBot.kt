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
    /** The Bot API: calls answer with the decoded response (`ok`, `result`, or `error_code`, `description`, `parameters`). */
    interface Api {
        suspend fun call(method: String, params: JSONObject, timeoutMs: Int): JSONObject
        /** A call with a file (multipart/form-data): [fields] as text, the file as [fileField]. */
        suspend fun upload(method: String, fields: Map<String, String>, fileField: String, file: java.io.File, name: String, mime: String): JSONObject =
            throw UnsupportedOperationException()
        /** Fetch a file by its `file_path` (from getFile) into [dest]. */
        suspend fun download(path: String, dest: java.io.File): Unit = throw UnsupportedOperationException()
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
        var hooked = true
        try {
            while (true) {
                val updates = try {
                    // A bot with a webhook can't be polled. Pending messages are kept.
                    if (hooked) { request("deleteWebhook", JSONObject().put("drop_pending_updates", false)); hooked = false }
                    // Reached Telegram: online now, not only when the first long poll returns (up to 50 s later).
                    _link.value = Link.Online
                    val params = JSONObject().put("offset", offset).put("timeout", POLL_SECONDS)
                        .put("allowed_updates", JSONArray(listOf("message", "callback_query")))
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
                val batch = (0 until updates.length()).map { updates.getJSONObject(it) }
                batch.lastOrNull()?.let {
                    // Moved before handling: a message that makes us fail is not delivered again and again.
                    offset = it.getLong("update_id") + 1
                    onOffset(offset)
                }
                for (m in parseBatch(batch)) onMessage(m)
            }
        } finally {
            _link.value = Link.Off
        }
    }

    override suspend fun send(chat: ChatRef, text: Rich, buttons: List<Button>): String {
        val params = JSONObject().put("chat_id", chat.chat)
            .put("link_preview_options", JSONObject().put("is_disabled", true))
        if (buttons.isNotEmpty()) params.put("reply_markup", keyboard(buttons))
        val r = withMarkup(params, text) { request("sendMessage", it) }
        return (r as JSONObject).getLong("message_id").toString()
    }

    override suspend fun edit(chat: ChatRef, message: String, text: Rich, buttons: List<Button>) {
        val params = JSONObject().put("chat_id", chat.chat).put("message_id", message.toLong())
            .put("link_preview_options", JSONObject().put("is_disabled", true))
        // Without reply_markup an edit removes the message's buttons.
        if (buttons.isNotEmpty()) params.put("reply_markup", keyboard(buttons))
        try {
            withMarkup(params, text) { request("editMessageText", it) }
        } catch (e: ChannelException) {
            // The same text again: nothing to change.
            if ("message is not modified" !in e.message.orEmpty()) throw e
        }
    }

    override suspend fun acknowledge(press: Press, note: String?) {
        request("answerCallbackQuery", JSONObject().put("callback_query_id", press.id).apply { if (note != null) put("text", note) })
    }

    /** Buttons side by side, in one row. */
    private fun keyboard(buttons: List<Button>) = JSONObject().put("inline_keyboard", JSONArray(
        buttons.groupBy { it.row }.toSortedMap().values.map { row ->
            JSONArray(row.map { JSONObject().put("text", it.label).put("callback_data", it.data) })
        },
    ))

    override suspend fun download(file: RemoteFile, dest: java.io.File) {
        if (file.size > MAX_DOWNLOAD) throw ChannelException("too large: bots can fetch files up to 20 MB")
        val r = request("getFile", JSONObject().put("file_id", file.id)) as JSONObject
        val path = r.optString("file_path").ifEmpty { throw ChannelException("Telegram gave no path for the file") }
        try { api.download(path, dest) } catch (e: IOException) { throw ChannelException(clean(e.message ?: e.javaClass.simpleName)) }
    }

    override suspend fun sendFile(chat: ChatRef, file: java.io.File, name: String, mime: String, caption: String): String {
        val size = file.length()
        if (size > MAX_UPLOAD) throw ChannelException("too large: bots can send files up to 50 MB")
        // Telegram shows photos inline (it recompresses them); other images and large ones as files.
        val photo = mime in PHOTO_TYPES && size <= MAX_PHOTO
        val (method, field) = if (photo) "sendPhoto" to "photo" else "sendDocument" to "document"
        val fields = mapOf("chat_id" to chat.chat) + if (caption.isNotBlank()) mapOf("caption" to caption.take(1024)) else emptyMap()
        val r = try { api.upload(method, fields, field, file, name, mime) } catch (e: IOException) {
            throw ChannelException(clean(e.message ?: e.javaClass.simpleName))
        }
        if (!r.optBoolean("ok")) throw ChannelException(clean("${r.optInt("error_code")} ${r.optString("description")}"), fatal = r.optInt("error_code") == 401)
        return r.getJSONObject("result").getLong("message_id").toString()
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

        override suspend fun upload(method: String, fields: Map<String, String>, fileField: String, file: java.io.File, name: String, mime: String): JSONObject =
            withContext(Dispatchers.IO) {
                val boundary = "----weiassist" + java.util.UUID.randomUUID().toString().replace("-", "")
                val conn = URL("$base/bot$token/$method").openConnection() as HttpURLConnection
                val closing = coroutineContext[Job]?.invokeOnCompletion { conn.disconnect() }
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 120_000
                    conn.doOutput = true
                    conn.setChunkedStreamingMode(64 * 1024)
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.outputStream.buffered().use { out ->
                        fun w(s: String) = out.write(s.toByteArray())
                        for ((k, v) in fields) w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
                        val quoted = name.replace("\"", "'").replace("\r", "").replace("\n", "")
                        w("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$quoted\"\r\nContent-Type: $mime\r\n\r\n")
                        file.inputStream().use { it.copyTo(out) }
                        w("\r\n--$boundary--\r\n")
                    }
                    val code = conn.responseCode
                    val body = (if (code < 400) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) }.orEmpty()
                    runCatching { JSONObject(body) }.getOrElse { JSONObject().put("ok", false).put("error_code", code).put("description", "HTTP $code") }
                } finally {
                    closing?.dispose()
                    conn.disconnect()
                }
            }

        override suspend fun download(path: String, dest: java.io.File) = withContext(Dispatchers.IO) {
            val conn = URL("$base/file/bot$token/$path").openConnection() as HttpURLConnection
            val closing = coroutineContext[Job]?.invokeOnCompletion { conn.disconnect() }
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                dest.parentFile?.mkdirs()
                conn.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                Unit
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
        /** The Bot API's limits: bots fetch files up to 20 MB, send up to 50 MB, photos up to 10 MB. */
        private const val MAX_DOWNLOAD = 20L * 1024 * 1024
        private const val MAX_UPLOAD = 50L * 1024 * 1024
        private const val MAX_PHOTO = 10L * 1024 * 1024
        private val PHOTO_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        /** A Bot API token as BotFather gives it: "<bot id>:<secret>". */
        fun looksLikeToken(s: String) = Regex("""^\d{5,}:[A-Za-z0-9_-]{30,}$""").matches(s.trim())

        /**
         * One getUpdates batch as messages: an album's parts (one `media_group_id`, sent together) become one
         * message with all their files and the caption one of them has.
         */
        fun parseBatch(updates: List<JSONObject>): List<Inbound> {
            val out = mutableListOf<Inbound>()
            var group: String? = null
            for (u in updates) {
                val m = parseUpdate(u) ?: continue
                val g = u.optJSONObject("message")?.optString("media_group_id")?.ifEmpty { null }
                val last = out.lastOrNull()
                if (g != null && g == group && last != null && last.chat == m.chat) {
                    out[out.size - 1] = last.copy(text = last.text.ifEmpty { m.text }, files = last.files + m.files, unsupported = last.unsupported || m.unsupported)
                } else out += m
                group = g
            }
            return out
        }

        /** An update to an [Inbound], or null when it isn't a message to answer (an edit, a service message). */
        fun parseUpdate(u: JSONObject): Inbound? {
            u.optJSONObject("callback_query")?.let { return parsePress(it) }
            val m = u.optJSONObject("message") ?: return null
            val from = m.optJSONObject("from") ?: return null
            if (from.optBoolean("is_bot")) return null
            val chat = m.optJSONObject("chat") ?: return null
            val text = m.optString("text").ifEmpty { m.optString("caption") }
            val files = files(m)
            val other = listOf("voice", "audio", "video", "video_note", "sticker", "animation", "location", "contact").any { m.has(it) }
            if (text.isEmpty() && files.isEmpty() && !other) return null
            val name = listOf(from.optString("first_name"), from.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { from.optString("username") }
            return Inbound(
                chat = ChatRef(ID, chat.get("id").toString()),
                from = Sender(from.get("id").toString(), name),
                text = text,
                private = chat.optString("type") == "private",
                unsupported = other,
                files = files,
            )
        }

        /** A photo (its largest size) or a document. */
        private fun files(m: JSONObject): List<RemoteFile> {
            m.optJSONArray("photo")?.takeIf { it.length() > 0 }?.let { sizes ->
                val p = sizes.getJSONObject(sizes.length() - 1)
                return listOf(RemoteFile(p.getString("file_id"), "photo-${m.optLong("message_id")}.jpg", "image/jpeg", p.optLong("file_size", -1)))
            }
            m.optJSONObject("document")?.let { d ->
                return listOf(RemoteFile(d.getString("file_id"), d.optString("file_name").ifEmpty { "file" },
                    d.optString("mime_type").ifEmpty { "application/octet-stream" }, d.optLong("file_size", -1)))
            }
            return emptyList()
        }

        /** A button tap ("callback query") on one of the bot's messages. */
        private fun parsePress(q: JSONObject): Inbound? {
            val from = q.optJSONObject("from") ?: return null
            val m = q.optJSONObject("message") ?: return null
            val chat = m.optJSONObject("chat") ?: return null
            return Inbound(
                chat = ChatRef(ID, chat.get("id").toString()),
                from = Sender(from.get("id").toString(), from.optString("first_name")),
                text = "",
                private = chat.optString("type") == "private",
                press = Press(q.getString("id"), q.optString("data"), m.get("message_id").toString()),
            )
        }
    }
}
