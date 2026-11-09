package com.lanqi.weiassist.agent

import android.content.Context
import android.util.Log
import com.lanqi.weiassist.channels.Button
import com.lanqi.weiassist.channels.ChannelException
import com.lanqi.weiassist.channels.ChatRef
import com.lanqi.weiassist.channels.Inbound
import com.lanqi.weiassist.channels.Link
import com.lanqi.weiassist.channels.Press
import com.lanqi.weiassist.channels.RemoteFile
import com.lanqi.weiassist.channels.Rich
import com.lanqi.weiassist.channels.TelegramBot
import com.lanqi.weiassist.phone.AgentAccessibilityService
import com.lanqi.weiassist.core.SecureStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.IdentityHashMap

/**
 * Talk to the assistant from Telegram. Each Telegram chat is a conversation of its own (a session marked
 * with its [SessionSummary.channel]), also in the app's list; what the assistant says in a run that a
 * message from Telegram started (or steered) goes back to that chat, streamed by editing messages.
 *
 * Who may talk: only Telegram accounts the user paired, by sending the bot a one-time code shown in the
 * app ([startPairing]); everyone else is ignored without an answer. Only private chats.
 *
 * What they may do: the user's [Access] choice, for every chat. With phone tasks, a risky tap (Send,
 * Delete…) is asked in the chat with ✅/❌ buttons as well as on the phone's card, first answer wins
 * ([confirm]); payments only on the phone.
 *
 * Main thread, like [SessionManager]. The bot token is kept encrypted ([SecureStore]).
 */
class Gateway(context: Context, private val sessions: SessionManager, private val termux: Termux, private val scope: CoroutineScope) {
    data class Owner(val id: String, val name: String)

    /** What a conversation from Telegram may do. */
    enum class Access {
        /** Only talk. */
        Chat,
        /** Talk and run Linux commands in Termux. */
        Termux,
        /** Also use the phone's apps (needs the accessibility service); risky taps are asked. */
        Phone,
    }

    /** A pairing code waiting to be sent to the bot: [url] opens the chat with it filled in. */
    data class Pairing(val code: String, val expiresAt: Long, val url: String?)

    data class State(
        /** A bot token is saved. */
        val configured: Boolean = false,
        val enabled: Boolean = false,
        /** The bot's @username. */
        val bot: String? = null,
        val link: Link = Link.Off,
        /** Why it stopped (the token was revoked, say); null while it works. */
        val error: String? = null,
        val owners: List<Owner> = emptyList(),
        val pairing: Pairing? = null,
        val access: Access = Access.Termux,
    ) {
        /** It should be receiving: the service stays up for it. */
        val listening get() = configured && enabled && error == null
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("gateway", Context.MODE_PRIVATE)
    private val secret = SecureStore(app, "telegram-bot")
    /** Telegram chat key ("telegram:<chat id>") → its session. */
    private val bindings = HashMap<String, String>()
    /** Sessions whose runs are being sent to a chat now. */
    private val replies = HashMap<String, Job>()
    private var job: Job? = null
    private var bot: TelegramBot? = null

    /** A question waiting for a ✅/❌ tap in [chat], by its nonce (in the buttons' data). */
    private class Ask(val chat: ChatRef, val answer: CompletableDeferred<Boolean> = CompletableDeferred())
    private val asks = HashMap<String, Ask>()
    /** The code an account just paired with, and until when: sent again (typed after tapping the link), it isn't a task. */
    private var usedCode: Pair<String, Long>? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** send_file transfers running now, by session: Stop or Delete cancels them ([cancelTransfers]). */
    private val transfers = HashMap<String, MutableSet<kotlinx.coroutines.Deferred<Pair<Boolean, String>>>>()

    /** A JPEG of the screen session [id] works on, or null when it has none (set by the controller, which knows the screens). */
    var screenshot: suspend (id: String) -> ByteArray? = { null }

    /** Read the saved setup and start receiving if it is on. Call once the sessions are loaded. */
    suspend fun start() {
        // Files on their way in or out when the process last ended: nothing will send them now.
        withContext(Dispatchers.IO) { File(app.cacheDir, "outbox").deleteRecursively(); File(app.cacheDir, "inbox").deleteRecursively() }
        val token = withContext(Dispatchers.IO) { secret.read() }
        prefs.getString("bindings", null)?.let { s -> JSONObject(s).let { o -> o.keys().forEach { bindings[it] = o.getString(it) } } }
        _state.value = State(
            configured = !token.isNullOrEmpty(), enabled = prefs.getBoolean("enabled", false),
            bot = prefs.getString("bot", null), owners = loadOwners(),
            access = prefs.getString("access", null)?.let { a -> Access.entries.firstOrNull { it.name == a } } ?: Access.Termux,
        )
        restart(token)
    }

    // ---- setup (Settings) -----------------------------------------------------------------------------

    /** Check [token] with Telegram, save it and start; returns the problem to show, or null. */
    suspend fun connect(token: String): String? {
        val t = token.trim()
        if (!TelegramBot.looksLikeToken(t)) return app.getString(R.string.tg_token_malformed)
        val me = try { TelegramBot(t).me() } catch (e: ChannelException) {
            return if (e.fatal) app.getString(R.string.tg_token_rejected) else app.getString(R.string.tg_unreachable, e.message)
        }
        withContext(Dispatchers.IO) { secret.write(t) }
        val sameBot = prefs.getLong("botId", -1) == me.id
        val edit = prefs.edit().putLong("botId", me.id).putString("bot", me.username).putBoolean("enabled", true)
        // Another bot: its updates count from zero, and accounts are paired with it afresh.
        if (!sameBot) edit.remove("offset").remove("owners")
        edit.apply()
        if (!sameBot) unbindAll()
        _state.update { State(configured = true, enabled = true, bot = me.username, owners = if (sameBot) it.owners else emptyList()) }
        restart(t)
        return null
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        _state.update { it.copy(enabled = on, error = null) }
        scope.launch { restart(withContext(Dispatchers.IO) { secret.read() }) }
    }

    /** Forget the bot: its token, the paired accounts and which chat was which conversation (the conversations stay). */
    fun forget() {
        job?.cancel(); job = null; bot = null
        secret.clear()
        prefs.edit().clear().apply()
        unbindAll()
        _state.value = State()
    }

    /** Show a one-time code; the first account to send it to the bot within [PAIRING_MS] may use the assistant. */
    fun startPairing() {
        val code = ChatCommands.newPairingCode()
        val url = _state.value.bot?.let { "https://t.me/$it?start=$code" }
        _state.update { it.copy(pairing = Pairing(code, System.currentTimeMillis() + PAIRING_MS, url)) }
    }

    fun cancelPairing() = _state.update { it.copy(pairing = null) }

    /** From the next message on, each chat's conversation may do [a] (one that may do something else is replaced). */
    fun setAccess(a: Access) {
        prefs.edit().putString("access", a.name).apply()
        _state.update { it.copy(access = a) }
    }

    fun removeOwner(id: String) {
        _state.update { s -> s.copy(owners = s.owners.filterNot { it.id == id }) }
        saveOwners()
    }

    // ---- receiving ------------------------------------------------------------------------------------

    private fun restart(token: String?) {
        job?.cancel(); job = null; bot = null
        val s = _state.value
        if (token.isNullOrEmpty() || !s.enabled) return
        val b = TelegramBot(token, prefs.getLong("offset", 0), onOffset = { prefs.edit().putLong("offset", it).apply() })
        bot = b
        job = scope.launch {
            launch { b.link.collect { l -> _state.update { it.copy(link = l) } } }
            try {
                hello(b)
                b.receive { handle(b, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChannelException) {
                Log.w(TAG, "Telegram stopped: ${e.message}")
                _state.update { it.copy(error = if (e.fatal) app.getString(R.string.tg_token_rejected) else e.message) }
            } finally {
                _state.update { it.copy(link = Link.Off) }
            }
        }
    }

    /** Who the bot is now (renamed?), and the commands in its menu. Retried while Telegram can't be reached. */
    private suspend fun hello(b: TelegramBot) {
        var wait = 2_000L
        while (true) {
            try {
                val me = b.me()
                prefs.edit().putString("bot", me.username).apply()
                _state.update { it.copy(bot = me.username) }
                b.setCommands(listOf(
                    "new" to app.getString(R.string.tg_cmd_new),
                    "stop" to app.getString(R.string.tg_cmd_stop),
                    "status" to app.getString(R.string.tg_cmd_status),
                    "screen" to app.getString(R.string.tg_cmd_screen),
                ))
                return
            } catch (e: ChannelException) {
                if (e.fatal) throw e
                _state.update { it.copy(link = Link.Retrying(e.message.orEmpty())) }
                delay(wait)
                wait = (wait * 2).coerceAtMost(60_000)
            }
        }
    }

    private suspend fun handle(b: TelegramBot, m: Inbound) {
        val s = _state.value
        m.press?.let { onPress(b, m, it); return }
        if (s.owners.none { it.id == m.from.id }) {
            // Strangers get no answer; a private chat may pair with the code shown in the app.
            val p = s.pairing ?: return
            val code = ChatCommands.pairingCode(m.text, s.bot) ?: return
            if (!m.private || System.currentTimeMillis() > p.expiresAt || !ChatCommands.sameCode(code, p.code)) return
            _state.update { it.copy(pairing = null, owners = it.owners + Owner(m.from.id, m.from.name)) }
            saveOwners()
            usedCode = p.code to p.expiresAt
            Log.i(TAG, "paired a Telegram account")
            reply(b, m.chat, app.getString(R.string.tg_paired, app.getString(R.string.agent_name)) + "\n\n" + help())
            return
        }
        if (!m.private) return // groups: not yet
        val used = usedCode?.takeIf { System.currentTimeMillis() < it.second }?.first
        if (used != null && ChatCommands.pairingCode(m.text, s.bot)?.let { ChatCommands.sameCode(it, used) } == true) {
            reply(b, m.chat, app.getString(R.string.tg_already_paired))
            return
        }
        val c = ChatCommands.parse(m.text, s.bot)
        when (c?.name) {
            "start", "help" -> reply(b, m.chat, app.getString(R.string.tg_hello, app.getString(R.string.agent_name)) + "\n\n" + help())
            "new" -> {
                newSession(m.chat, effective())
                reply(b, m.chat, listOfNotNull(app.getString(R.string.tg_new), phoneUnavailable()).joinToString("\n\n"))
            }
            "stop" -> {
                val id = bindings[m.chat.key]?.takeIf { sessions.summary(it)?.status?.active == true }
                if (id != null) sessions.stop(id)
                reply(b, m.chat, app.getString(if (id != null) R.string.tg_stopped else R.string.tg_nothing_running))
            }
            "status" -> reply(b, m.chat, status(m.chat))
            "screen" -> scope.launch { sendScreen(b, m.chat) }
            else -> {
                // A voice note counts as its words, once they are known (the chat is told if they can't be).
                val words = m.voice?.let { note -> listOf(m.text, transcribe(b, m.chat, note) ?: return).filter { it.isNotBlank() }.joinToString("\n\n") } ?: m.text
                // The agent waits for an answer here: the words are the answer to its question.
                val open = bindings[m.chat.key]?.let { questions[it] }?.takeIf { it.chat == m.chat }
                if (open != null && m.files.isEmpty() && words.isNotBlank()) { step(b, open) { it.custom(words) }; return }
                if (m.unsupported && m.files.isEmpty()) { reply(b, m.chat, app.getString(R.string.tg_text_only)); return }
                val (id, note) = session(m.chat)
                if (note != null) reply(b, m.chat, note)
                val (files, where) = receive(b, m, id)
                // Another command (the session's core's: /plan, /goal…), without the "@bot" Telegram may add to it.
                val typed = if (c != null) "/${c.name}" + if (c.args.isNotEmpty()) " ${c.args}" else "" else words
                val text = listOf(typed, where).filter { it.isNotBlank() }.joinToString("\n\n")
                if (text.isBlank() && files.isEmpty()) return // nothing usable came (the chat was told why)
                if (!sessions.send(id, text, files)) { reply(b, m.chat, app.getString(R.string.tg_not_sent)); return }
                if (replies[id]?.isActive != true) replies[id] = scope.launch { mirror(b, id, m.chat) }
            }
        }
    }

    /**
     * What a voice note says, also shown in the chat so a mishearing is seen at once; null when it can't be known, and
     * the chat is told why. Uses the server and language set for voice input in Settings.
     */
    private suspend fun transcribe(b: TelegramBot, chat: ChatRef, note: RemoteFile): String? {
        val settings = VoiceSettings(app)
        val url = settings.url.value
        if (url == null) { reply(b, chat, app.getString(R.string.tg_voice_off)); return null }
        if (note.size > MAX_VOICE) { reply(b, chat, app.getString(R.string.tg_voice_long)); return null }
        val tmp = File(app.cacheDir, "inbox/${java.util.UUID.randomUUID().toString().take(8)}-voice.ogg").apply { parentFile?.mkdirs() }
        try {
            b.download(note, tmp)
            val problem = when (val t = VoiceServer.transcribe(url, tmp, settings.language.value, note.name, note.mime)) {
                is VoiceServer.Transcript.Text -> {
                    if (t.text.isNotBlank()) {
                        reply(b, chat, app.getString(R.string.tg_voice_heard, t.text.take(MAX_DETAIL)))
                        return t.text
                    }
                    app.getString(R.string.tg_voice_no_speech)
                }
                VoiceServer.Transcript.Unreachable -> app.getString(R.string.tg_voice_unreachable)
                VoiceServer.Transcript.Loading -> app.getString(R.string.tg_voice_loading)
                is VoiceServer.Transcript.Rejected -> app.getString(R.string.tg_voice_rejected, t.message)
            }
            reply(b, chat, problem)
        } catch (e: ChannelException) {
            reply(b, chat, app.getString(R.string.tg_file_not_fetched, note.name, e.message.orEmpty()))
        } finally {
            tmp.delete()
        }
        return null
    }

    /**
     * The files of [m], fetched from Telegram: what the model can read (images, PDFs, text) as attachments; and, for a
     * conversation with Termux, every file also put into its folder's inbox/, with a note saying where (so it can work
     * with a spreadsheet, a zip, or the photo itself). A file that can't be used is reported in the chat.
     */
    private suspend fun receive(b: TelegramBot, m: Inbound, session: String): Pair<List<Attachment>, String> {
        if (m.files.isEmpty()) return emptyList<Attachment>() to ""
        val withTermux = sessions.summary(session)?.termux == true && termux.setupProblem() == null
        val attachments = mutableListOf<Attachment>()
        val saved = mutableListOf<String>()
        for (f in m.files) {
            val tmp = File(app.cacheDir, "inbox/${java.util.UUID.randomUUID().toString().take(8)}-${f.name.replace('/', '_')}")
            try {
                b.download(f, tmp)
                val readable = try { Attachments.importFile(app, tmp, f.name, f.mime) } catch (e: Attachments.Unsupported) { null }
                    catch (e: Attachments.TooLarge) { null }
                readable?.let { attachments += it }
                val path = if (withTermux) termux.push(session, tmp, f.name) else null
                if (path != null) saved += "${f.name} → $path"
                if (readable == null && path == null) reply(b, m.chat, app.getString(R.string.tg_file_unreadable, f.name))
            } catch (e: ChannelException) {
                reply(b, m.chat, app.getString(R.string.tg_file_not_fetched, f.name, e.message.orEmpty()))
            } finally {
                tmp.delete()
            }
        }
        val where = if (saved.isEmpty()) "" else "[Files from the user, saved in your Termux folder: ${saved.joinToString("; ")}]"
        return attachments to where
    }

    /** /screen: a picture of the screen this chat's conversation is working on. */
    private suspend fun sendScreen(b: TelegramBot, chat: ChatRef) {
        val id = bindings[chat.key]?.takeIf { sessions.summary(it) != null }
        val jpeg = id?.let { runCatching { screenshot(it) }.getOrNull() }
        if (jpeg == null) { reply(b, chat, app.getString(R.string.tg_no_screen)); return }
        val f = File(app.cacheDir, "outbox/screen-$id.jpg").apply { parentFile?.mkdirs(); writeBytes(jpeg) }
        try { b.sendFile(chat, f, "screen.jpg", "image/jpeg", sessions.summary(id)?.title.orEmpty()) }
        catch (e: ChannelException) { Log.w(TAG, "screen not sent: ${e.message}") }
        finally { f.delete() }
    }

    /**
     * The send_file tool: copy [path] out of session [session]'s Termux folder and send it to its chat. Returns whether
     * it went, and what happened (for the model). Stopping or deleting the session cancels it ([cancelTransfers]).
     */
    suspend fun sendFile(session: String, path: String, caption: String): Pair<Boolean, String> {
        val transfer = scope.async { transfer(session, path, caption) }
        transfers.getOrPut(session) { HashSet() } += transfer
        return try {
            transfer.await()
        } catch (e: CancellationException) {
            if (!transfer.isCancelled) throw e // our caller was cancelled, not the transfer
            false to "Stopped by the user; the file was not sent."
        } finally {
            transfers[session]?.let { it -= transfer; if (it.isEmpty()) transfers.remove(session) }
        }
    }

    /** The session was stopped or deleted: abandon its file transfers (Termux's curl then finds the endpoint closed). */
    fun cancelTransfers(session: String) { transfers.remove(session)?.forEach { it.cancel() } }

    private suspend fun transfer(session: String, path: String, caption: String): Pair<Boolean, String> {
        val b = bot ?: return false to "Telegram is not connected now, so nothing was sent."
        val chat = chatOf(session) ?: return false to "This conversation is not with a chat, so there is no one to send a file to."
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { "file" }
        val tmp = File(app.cacheDir, "outbox/${java.util.UUID.randomUUID().toString().take(8)}-$name")
        try {
            termux.pull(session, path, tmp, MAX_SEND)?.let { return false to it }
            // Stopped while it was copied: don't send it after all.
            if (sessions.summary(session)?.status?.active != true) return false to "Stopped by the user; the file was not sent."
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            b.sendFile(chat, tmp, name, mime, caption)
            return true to "Sent $name (${android.text.format.Formatter.formatShortFileSize(app, tmp.length())}) to the user's chat."
        } catch (e: ChannelException) {
            return false to "Telegram did not take the file: ${e.message}"
        } finally {
            tmp.delete()
        }
    }

    private fun help() = app.getString(R.string.tg_help, app.getString(when (effective()) {
        Access.Chat -> R.string.tg_can_chat
        Access.Termux -> R.string.tg_can_termux
        Access.Phone -> R.string.tg_can_phone
    }))

    /** What conversations may do now: phone tasks only while the accessibility service is on. */
    private fun effective(): Access = _state.value.access
        .let { if (it == Access.Phone && !AgentAccessibilityService.running.value) Access.Termux else it }

    /** Phone tasks were chosen but can't be done now: what to tell the chat. */
    private fun phoneUnavailable() = if (effective() != _state.value.access) app.getString(R.string.tg_phone_unavailable) else null

    private fun status(chat: ChatRef): String {
        val s = bindings[chat.key]?.let(sessions::summary) ?: return app.getString(R.string.tg_status_none)
        val st = app.getString(when (s.status) {
            SessionStatus.Running -> R.string.tg_state_running
            SessionStatus.Queued -> R.string.tg_state_queued
            SessionStatus.Paused -> R.string.tg_state_paused
            SessionStatus.Interrupted -> R.string.tg_state_interrupted
            SessionStatus.Idle -> R.string.tg_state_idle
        })
        return listOf("${s.title.ifBlank { "…" }} · $st", Markdown.plain(s.preview).take(300)).filter { it.isNotBlank() }.joinToString("\n")
    }

    /**
     * The chat's conversation: the one it is bound to, or a new one (the first message, the old one was deleted, or it
     * may do other things than [Access] now allows), with what to tell the chat about that, if anything.
     */
    private fun session(chat: ChatRef): Pair<String, String?> {
        val a = effective()
        val cur = bindings[chat.key]?.let(sessions::summary)
        if (cur != null && cur.phone == (a == Access.Phone) && cur.termux == (a != Access.Chat)) return cur.id to phoneUnavailable()
        val id = newSession(chat, a)
        val notes = listOfNotNull(if (cur != null) app.getString(R.string.tg_kind_changed) else null, phoneUnavailable())
        return id to notes.joinToString("\n\n").ifEmpty { null }
    }

    private fun newSession(chat: ChatRef, a: Access) =
        sessions.create(phone = a == Access.Phone, channel = TelegramBot.ID, termux = a != Access.Chat).also { bind(chat, it) }

    private fun bind(chat: ChatRef, id: String) {
        bindings[chat.key] = id
        saveBindings()
    }

    private fun unbindAll() { bindings.clear(); saveBindings() }

    private fun reply(b: TelegramBot, chat: ChatRef, text: String) {
        scope.launch {
            try { b.send(chat, Rich(TelegramHtml.esc(text), text)) } catch (e: ChannelException) { Log.w(TAG, "reply not sent: ${e.message}") }
        }
    }

    // ---- confirming from the chat ---------------------------------------------------------------------

    /** Whether a risky action of session [session] can be asked in its Telegram chat ([confirm]). */
    fun canAsk(session: String) = bot != null && _state.value.access == Access.Phone && chatOf(session) != null

    /**
     * Ask in session [session]'s chat whether it may do what [question] says: ✅/❌ buttons, true or false when
     * tapped. Null when it can't be asked there (not a chat's conversation, sending failed), and for a
     * [payment]: that is only allowed on the phone (the chat is told so). Cancelled (answered on the phone, the
     * task stopped): the buttons go and the message says so.
     */
    suspend fun confirm(session: String, question: String, payment: Boolean): Boolean? {
        val b = bot ?: return null
        val chat = chatOf(session) ?: return null
        if (payment) { reply(b, chat, question + "\n\n" + app.getString(R.string.tg_confirm_on_phone)); return null }
        val nonce = ChatCommands.newPairingCode()
        val ask = Ask(chat)
        asks[nonce] = ask
        var message: String? = null
        var outcome = R.string.tg_confirm_closed
        try {
            message = b.send(chat, plainRich(question), listOf(
                Button(app.getString(R.string.tg_confirm_allow), "c:$nonce:y"),
                Button(app.getString(R.string.tg_confirm_deny), "c:$nonce:n"),
            ))
            val ok = ask.answer.await()
            outcome = if (ok) R.string.tg_confirm_allowed else R.string.tg_confirm_denied
            return ok
        } catch (e: ChannelException) {
            Log.w(TAG, "question not sent: ${e.message}")
            return null
        } finally {
            asks.remove(nonce)
            val id = message
            // The answer (or why there won't be one) replaces the buttons.
            if (id != null) scope.launch { runCatching { b.edit(chat, id, plainRich(question + "\n\n" + app.getString(outcome))) } }
        }
    }

    /** A ✅/❌ tap: answers its question if it is still open, the tap is a paired account's, in the question's chat. */
    private fun onPress(b: TelegramBot, m: Inbound, p: Press) {
        if (p.data.startsWith("q:")) { onQuestionPress(b, m, p); return }
        val owner = _state.value.owners.any { it.id == m.from.id }
        val parts = p.data.split(':')
        val ask = if (owner && parts.size == 3 && parts[0] == "c") asks[parts[1]]?.takeIf { it.chat == m.chat } else null
        val yes = parts.getOrNull(2) == "y"
        ask?.answer?.complete(yes)
        val note = when {
            !owner -> null
            ask == null -> app.getString(R.string.tg_confirm_expired)
            else -> app.getString(if (yes) R.string.tg_confirm_allowed else R.string.tg_confirm_denied)
        }
        scope.launch { runCatching { b.acknowledge(p, note) } }
    }

    // ---- the agent's questions in the chat -------------------------------------------------------------

    /** A question of session [session] asked in [chat] ([message], edited as it is answered), by its nonce. */
    private class ChatQuestion(val session: String, val chat: ChatRef, val nonce: String, var draft: AnswerDraft, var message: String? = null)
    /** Questions open in chats, by session. */
    private val questions = HashMap<String, ChatQuestion>()

    /**
     * Session [id]'s question, as its state [s] has it, in [chat]: a new one is sent, one that went (answered in the
     * app, or the agent stopped waiting) loses its buttons.
     */
    private suspend fun syncQuestion(b: TelegramBot, id: String, chat: ChatRef, s: SessionState) {
        val ask = s.asks.firstOrNull()
        val shown = questions[id]
        if (shown != null && shown.draft.ask.id != ask?.id) {
            questions.remove(id)
            shown.message?.let { msg -> runCatching { b.edit(chat, msg, questionText(shown.draft, app.getString(R.string.tg_question_closed))) } }
        }
        if (ask == null || questions[id] != null) return
        val q = ChatQuestion(id, chat, ChatCommands.newPairingCode(), AnswerDraft(ask))
        questions[id] = q
        try {
            q.message = b.send(chat, questionText(q.draft), questionButtons(q))
        } catch (e: ChannelException) {
            Log.w(TAG, "question not sent: ${e.message}")
        }
    }

    /** A step in answering [q] from the chat; once every question has its answer, they go to the agent. */
    private fun step(b: TelegramBot, q: ChatQuestion, f: (AnswerDraft) -> AnswerDraft) {
        val next = f(q.draft)
        q.draft = next
        val msg = q.message
        if (next.done) {
            questions.remove(q.session)
            val taken = sessions.answer(q.session, next.ask.id, next.answers)
            val note = if (taken) answersText(next) else app.getString(R.string.tg_question_closed)
            if (msg != null) scope.launch { runCatching { b.edit(q.chat, msg, questionText(next, note)) } }
            return
        }
        if (msg != null) scope.launch { runCatching { b.edit(q.chat, msg, questionText(next), questionButtons(q)) } }
    }

    /** A tap on a question's button: an option, Next or Skip, from a paired account in the question's chat. */
    private fun onQuestionPress(b: TelegramBot, m: Inbound, p: Press) {
        val owner = _state.value.owners.any { it.id == m.from.id }
        val parts = p.data.split(':')
        val q = if (owner && parts.size == 3) questions.values.firstOrNull { it.nonce == parts[1] && it.chat == m.chat } else null
        if (q != null) {
            val option = parts[2].toIntOrNull()?.let { q.draft.current?.options?.getOrNull(it) }
            when {
                option != null -> step(b, q) { it.choose(option.label) }
                parts[2] == "next" -> step(b, q) { it.confirm() }
                parts[2] == "skip" -> step(b, q) { it.skip() }
            }
        }
        val note = if (owner && q == null) app.getString(R.string.tg_confirm_expired) else null
        scope.launch { runCatching { b.acknowledge(p, note) } }
    }

    /** The question being answered (or, once [closing] is given, what came of it). */
    private fun questionText(d: AnswerDraft, closing: String? = null): Rich {
        val q = d.current ?: d.ask.questions.last()
        val total = d.ask.questions.size
        val head = "❓ " + q.header.ifBlank { app.getString(R.string.tg_question) } + if (total > 1) " · ${minOf(d.index + 1, total)}/$total" else ""
        val body = buildList {
            add(q.text)
            if (q.detail.isNotBlank()) add(q.detail.take(MAX_DETAIL) + if (q.detail.length > MAX_DETAIL) "…" else "")
            if (closing == null) {
                q.options.filter { it.description.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { o -> add(o.joinToString("\n") { "• ${it.label}: ${it.description}" }) }
                add(app.getString(if (q.options.isEmpty()) R.string.tg_question_type else R.string.tg_question_or_type))
            } else add(closing)
        }
        val plain = head + "\n\n" + body.joinToString("\n\n")
        return Rich("<b>${TelegramHtml.esc(head)}</b>\n\n" + TelegramHtml.esc(body.joinToString("\n\n")), plain)
    }

    /** One row per option (✓ on those picked), then Next for a multiple choice, and Skip. */
    private fun questionButtons(q: ChatQuestion): List<Button> {
        val cur = q.draft.current ?: return emptyList()
        val options = cur.options.mapIndexed { i, o ->
            Button((if (cur.multi && o.label in q.draft.picked) "✓ " else "") + o.label, "q:${q.nonce}:$i", row = i)
        }
        val last = cur.options.size
        return options + listOfNotNull(
            if (cur.multi) Button(app.getString(R.string.tg_question_next), "q:${q.nonce}:next", row = last) else null,
            Button(app.getString(R.string.tg_question_skip), "q:${q.nonce}:skip", row = last),
        )
    }

    /** What the user answered, one line per question. */
    private fun answersText(d: AnswerDraft) = d.answers.zip(d.ask.questions).joinToString("\n") { (a, q) ->
        val said = (a.selected + listOfNotNull(a.custom?.takeIf { it.isNotBlank() })).joinToString(", ")
        "✅ " + (if (d.ask.questions.size > 1) "${q.header.ifBlank { q.text.take(30) }}: " else "") + said.ifEmpty { app.getString(R.string.tg_question_skipped) }
    }

    private fun chatOf(session: String): ChatRef? =
        bindings.entries.firstOrNull { it.value == session }?.key?.let { ChatRef(TelegramBot.ID, it.substringAfter(':')) }

    private fun plainRich(text: String) = Rich(TelegramHtml.esc(text), text)

    // ---- sending a run back ---------------------------------------------------------------------------

    /**
     * Send what session [id] says in its current run to [chat] until the run (and any message still queued
     * for it) is over: answers as they stream, the steps it takes in one status message, "typing…" meanwhile.
     */
    private suspend fun mirror(b: TelegramBot, id: String, chat: ChatRef) {
        val flow = sessions.attach(id) ?: return
        val typing = scope.launch { while (true) { runCatching { b.typing(chat) }; delay(TYPING_MS) } }
        try {
            val out = Outbox(b, chat, identitySet(flow.value.items))
            while (true) {
                val s = flow.value
                out.sync(s)
                syncQuestion(b, id, chat, s)
                // Until the run is over, a message queued for it is taken, and a command has its outcome.
                val waiting = s.items.any { (it as? ChatItem.User)?.delivery == Delivery.Queued || (it is ChatItem.Command && it.ok == null) }
                if (!s.busy && !waiting) { out.finish(s); break }
                delay(EDIT_MS)
                flow.first { it !== s }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sending session $id to Telegram failed", e)
        } finally {
            typing.cancel()
            // The run is over: a question still shown can't be answered any more.
            questions.remove(id)?.let { q -> q.message?.let { msg -> scope.launch { runCatching { b.edit(chat, msg, questionText(q.draft, app.getString(R.string.tg_question_closed))) } } } }
            replies.remove(id)
            sessions.detach(id)
        }
    }

    /**
     * What one run has sent to a chat. [before]: the session's items when it began (history, not sent).
     * Answers and errors are sent once each; the answer streaming now is one [Segment] edited as it grows,
     * which the finished answer then replaces.
     */
    private inner class Outbox(private val b: TelegramBot, private val chat: ChatRef, private val before: Set<ChatItem>) {
        private val sent = identitySet(emptyList())
        private var streaming: Segment? = null
        private var steps: Segment? = null

        suspend fun sync(s: SessionState) {
            val fresh = s.items.filter { it !in before }
            for (item in fresh) {
                if (item in sent) continue
                val chunks = when (item) {
                    is ChatItem.Assistant -> TelegramHtml.render(item.text)
                    // A command's outcome, once it has one.
                    is ChatItem.Command -> if (item.ok == null) continue
                        else listOf(((if (item.ok) "✅ " else "⚠️ ") + item.line.substringBefore(' ') + (if (item.result.isNotBlank()) "\n" + item.result else "")).let { Rich(TelegramHtml.esc(it), it) })
                    is ChatItem.Error -> listOf(("⚠️ " + item.text).let { Rich(TelegramHtml.esc(it), it) })
                    else -> continue
                }
                sent += item
                (streaming ?: Segment()).sync(chunks)
                streaming = null
            }
            if (s.streaming.isNotBlank()) (streaming ?: Segment().also { streaming = it }).sync(TelegramHtml.render(s.streaming))
            val st = fresh.filterIsInstance<ChatItem.Step>()
            // Unchanged lines are not sent again (Segment compares).
            if (st.isNotEmpty()) (steps ?: Segment().also { steps = it }).sync(listOf(stepLines(st)))
        }

        /** The run is over: the status message keeps only how many steps it took. */
        suspend fun finish(s: SessionState) {
            val n = s.items.filter { it !in before }.count { it is ChatItem.Step }
            if (n > 0) steps?.sync(listOf(app.resources.getQuantityString(R.plurals.tg_steps, n, n).let { Rich(TelegramHtml.esc(it), it) }))
        }

        /** The last few steps: ⏳ running, ✓ done, ✗ failed. */
        private fun stepLines(st: List<ChatItem.Step>): Rich {
            val shown = st.takeLast(3)
            val lines = shown.map { step ->
                val mark = when (step.ok) { null -> "⏳"; true -> "✓"; false -> "✗" }
                val detail = step.detail.lineSequence().firstOrNull().orEmpty().take(80)
                Triple(mark, step.tool, detail)
            }
            val more = if (st.size > shown.size) app.resources.getQuantityString(R.plurals.tg_steps, st.size, st.size) + "\n" else ""
            val html = TelegramHtml.esc(more) + lines.joinToString("\n") { (m, t, d) ->
                "$m <b>${TelegramHtml.esc(t)}</b>" + if (d.isNotEmpty()) " <code>${TelegramHtml.esc(d)}</code>" else ""
            }
            return Rich(html, more + lines.joinToString("\n") { (m, t, d) -> "$m $t $d".trim() })
        }

        /** Messages that show one text: edited when it changes, more sent when it outgrows them. */
        private inner class Segment {
            private val ids = mutableListOf<String>()
            private val texts = mutableListOf<String>()

            suspend fun sync(chunks: List<Rich>) {
                chunks.forEachIndexed { i, c ->
                    try {
                        if (i < ids.size) {
                            if (texts[i] != c.markup) { b.edit(chat, ids[i], c); texts[i] = c.markup }
                        } else {
                            ids += b.send(chat, c); texts += c.markup
                        }
                    } catch (e: ChannelException) {
                        Log.w(TAG, "message not sent: ${e.message}")
                    }
                }
            }
        }
    }

    // ---- saved ----------------------------------------------------------------------------------------

    private fun loadOwners(): List<Owner> = runCatching {
        val a = JSONArray(prefs.getString("owners", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Owner(o.getString("id"), o.optString("name")) } }
    }.getOrDefault(emptyList())

    private fun saveOwners() = prefs.edit().putString("owners",
        JSONArray(_state.value.owners.map { JSONObject().put("id", it.id).put("name", it.name) }).toString()).apply()

    private fun saveBindings() = prefs.edit().putString("bindings", JSONObject(bindings as Map<*, *>).toString()).apply()

    private fun identitySet(items: List<ChatItem>): MutableSet<ChatItem> =
        java.util.Collections.newSetFromMap(IdentityHashMap<ChatItem, Boolean>()).apply { addAll(items) }

    companion object {
        private const val TAG = "Gateway"
        /** How long a pairing code works. */
        const val PAIRING_MS = 10 * 60_000L
        /** At most one edit per chat about this often (Telegram allows about one a second). */
        private const val EDIT_MS = 1_200L
        /** "typing…" lasts 5 s in Telegram. */
        private const val TYPING_MS = 4_500L
        /** The most of a question's detail (a plan) a chat message shows: Telegram allows 4096 characters. */
        private const val MAX_DETAIL = 3000
        /** About 20 minutes of a voice note: longer might outlast the wait for its transcript, and hold up the chat. */
        private const val MAX_VOICE = 4L * 1024 * 1024
        /** Bots may send files up to 50 MB. */
        private const val MAX_SEND = 50L * 1024 * 1024
    }
}
