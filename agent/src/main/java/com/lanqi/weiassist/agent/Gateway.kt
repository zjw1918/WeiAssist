package com.lanqi.weiassist.agent

import android.content.Context
import android.util.Log
import com.lanqi.weiassist.channels.ChannelException
import com.lanqi.weiassist.channels.ChatRef
import com.lanqi.weiassist.channels.Inbound
import com.lanqi.weiassist.channels.Link
import com.lanqi.weiassist.channels.Rich
import com.lanqi.weiassist.channels.TelegramBot
import com.lanqi.weiassist.pi.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.IdentityHashMap

/**
 * Talk to the assistant from Telegram. Each Telegram chat is a conversation of its own (a session marked
 * with its [SessionSummary.channel]), also in the app's list; what the assistant says in a run that a
 * message from Telegram started (or steered) goes back to that chat, streamed by editing messages.
 *
 * Who may talk: only Telegram accounts the user paired, by sending the bot a one-time code shown in the
 * app ([startPairing]); everyone else is ignored without an answer. Only private chats, and the sessions
 * are chat sessions (Termux, no phone tasks), until per-channel permissions and confirming from the chat exist.
 *
 * Main thread, like [SessionManager]. The bot token is kept encrypted ([SecureStore]).
 */
class Gateway(context: Context, private val sessions: SessionManager, private val scope: CoroutineScope) {
    data class Owner(val id: String, val name: String)

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

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Read the saved setup and start receiving if it is on. Call once the sessions are loaded. */
    suspend fun start() {
        val token = withContext(Dispatchers.IO) { secret.read() }
        prefs.getString("bindings", null)?.let { s -> JSONObject(s).let { o -> o.keys().forEach { bindings[it] = o.getString(it) } } }
        _state.value = State(
            configured = !token.isNullOrEmpty(), enabled = prefs.getBoolean("enabled", false),
            bot = prefs.getString("bot", null), owners = loadOwners(),
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
        if (s.owners.none { it.id == m.from.id }) {
            // Strangers get no answer; a private chat may pair with the code shown in the app.
            val p = s.pairing ?: return
            val code = ChatCommands.pairingCode(m.text, s.bot) ?: return
            if (!m.private || System.currentTimeMillis() > p.expiresAt || !ChatCommands.sameCode(code, p.code)) return
            _state.update { it.copy(pairing = null, owners = it.owners + Owner(m.from.id, m.from.name)) }
            saveOwners()
            Log.i(TAG, "paired a Telegram account")
            reply(b, m.chat, app.getString(R.string.tg_paired, app.getString(R.string.agent_name)) + "\n\n" + help())
            return
        }
        if (!m.private) return // groups: not yet
        val c = ChatCommands.parse(m.text, s.bot)
        when (c?.name) {
            "start", "help" -> reply(b, m.chat, app.getString(R.string.tg_hello, app.getString(R.string.agent_name)) + "\n\n" + help())
            "new" -> {
                bind(m.chat, sessions.create(phone = false, channel = TelegramBot.ID))
                reply(b, m.chat, app.getString(R.string.tg_new))
            }
            "stop" -> {
                val id = bindings[m.chat.key]?.takeIf { sessions.summary(it)?.status?.active == true }
                if (id != null) sessions.stop(id)
                reply(b, m.chat, app.getString(if (id != null) R.string.tg_stopped else R.string.tg_nothing_running))
            }
            "status" -> reply(b, m.chat, status(m.chat))
            else -> {
                if (m.unsupported) { reply(b, m.chat, app.getString(R.string.tg_text_only)); return }
                val id = session(m.chat)
                if (!sessions.send(id, m.text)) { reply(b, m.chat, app.getString(R.string.tg_not_sent)); return }
                if (replies[id]?.isActive != true) replies[id] = scope.launch { mirror(b, id, m.chat) }
            }
        }
    }

    private fun help() = app.getString(R.string.tg_help)

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

    /** The chat's conversation: the one it is bound to, or a new one (the first message, or the old one was deleted). */
    private fun session(chat: ChatRef): String =
        bindings[chat.key]?.takeIf { sessions.summary(it) != null } ?: sessions.create(phone = false, channel = TelegramBot.ID).also { bind(chat, it) }

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
                if (!s.busy && s.items.none { (it as? ChatItem.User)?.delivery == Delivery.Queued }) { out.finish(s); break }
                delay(EDIT_MS)
                flow.first { it !== s }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sending session $id to Telegram failed", e)
        } finally {
            typing.cancel()
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
    }
}
