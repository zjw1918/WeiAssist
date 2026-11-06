package com.lanqi.weiassist.agent

import org.json.JSONObject

/**
 * Pure transitions of one session for Pi's messages about it: (session, message) → session.
 * No Android, no Pi, no I/O, so every transition is unit-tested (SessionReducerTest).
 */
object SessionReducer {
    const val MAX_TRACE = 300
    /** Items kept in memory per session; older ones stay in the saved file only. */
    const val MAX_ITEMS = 400

    /** [friendly] turns a provider error into advice for the user (localized by the caller). */
    /** A subagent's own events (DeepSeek Harness marks them with its id): they go to its section, see [subagent]. */
    private val SUBAGENT_EVENTS = setOf(
        "thinking_start", "thinking_delta", "thinking_end", "text_delta", "assistant_end", "tool_start", "tool_end", "subagent_status",
    )

    /** How dsh's settlement notice introduces the subagent's closing text. */
    const val CLOSING = "Its closing message:"

    /** What a step keeps of its tool's output: its first line, whole enough to read a fetched page's address. */
    const val RESULT_MAX = 300

    fun resultLine(output: String) = output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(RESULT_MAX)

    /** At most this many of a subagent's own items are kept. */
    const val MAX_SUBAGENT_ITEMS = 200

    fun reduce(s: SessionState, m: JSONObject, now: Long, friendly: (String) -> String = { it }): SessionState =
        m.optString("subagent").takeIf { it.isNotEmpty() && m.optString("type") in SUBAGENT_EVENTS }
            ?.let { subagent(s, it, m, now) } ?: own(s, m, now, friendly)

    private fun own(s: SessionState, m: JSONObject, now: Long, friendly: (String) -> String): SessionState = when (m.optString("type")) {
        // Text after reasoning: the reasoning is over even if its end got lost.
        "text_delta" -> s.endThinking(null, now).copy(streaming = s.streaming + m.optString("delta"))
        "thinking_start" -> s.endThinking(null, now).copy(thinking = "", thinkingSince = now)
        "thinking_delta" -> s.copy(thinking = s.thinking + m.optString("delta"), thinkingSince = s.thinkingSince ?: now)
        "thinking_end" -> s.endThinking(m.optString("text"), now)
        "assistant_end" -> {
            val text = m.optString("text").ifEmpty { s.streaming }
            // After Stop, Pi's last request fails on the aborted signal: that is the stop, not an error to show.
            val err = if (m.optString("stopReason") == "error" && s.busy) friendly(m.optString("errorMessage")) else null
            var n = s.endThinking(null, now).copy(streaming = "", error = err ?: s.error)
                .countUsage(m.optJSONObject("usage"), "${m.optString("provider")}/${m.optString("model")}")
            if (text.isNotBlank()) n = n.add(ChatItem.Assistant(text), now).preview(Markdown.plain(text), now)
            if (err != null) n = n.add(ChatItem.Error(err), now).preview(err, now, error = true)
            n.trace("assistant_end · ${m.optString("provider")}/${m.optString("model")} · stop=${m.optString("stopReason")} · tokens=${m.optJSONObject("usage")?.optInt("totalTokens")}")
        }
        "tool_start" -> {
            val args = m.optJSONObject("args")
            val app = if (m.optString("tool") == "open_app") args?.optString("app") else null
            s.endThinking(null, now).add(step(m.optString("tool"), args), now)
                .copy(run = s.run?.let { it.copy(steps = it.steps + 1) })
                .let { if (app != null) it.copy(summary = it.summary.copy(app = app)) else it }
                .trace("→ ${m.optString("tool")} ${args?.toString().orEmpty()}")
        }
        "tool_end" -> {
            val ok = !m.optBoolean("isError")
            val output = m.optString("output")
            val out = resultLine(output)
            // The open step of this tool is the last Step without a result.
            val i = s.items.indexOfLast { it is ChatItem.Step && it.ok == null && it.tool == m.optString("tool") }
            val items = if (i < 0) s.items else s.items.toMutableList().also {
                it[i] = (it[i] as ChatItem.Step).copy(ok = ok, result = out, screen = m.optInt("screen"))
            }
            // The screen's app line: at the start of read_screen, after "Screen now:" for an action.
            val app = APP_LINE.find(output)?.groupValues?.get(1)
            s.copy(items = items, summary = if (app != null) s.summary.copy(app = app) else s.summary).trace("  ${if (ok) "✓" else "✗"} $out")
        }
        "configured" -> s.trace("configured · ${m.optString("model")} · thinking=${m.optString("thinkingLevel")} → ${m.optString("baseUrl")} · auth=${m.optString("auth")} · history=${m.optInt("messages")}" +
            (m.optJSONArray("skipped")?.takeIf { it.length() > 0 }?.let { " · the core's own: ${it.join(", ")}" } ?: ""))
        "error" -> {
            val err = friendly(m.optString("message"))
            s.copy(error = err, pendingTask = null).endRun(now).undelivered().status(SessionStatus.Idle, now)
                .add(ChatItem.Error(err), now).preview(err, now, error = true).trace("error · ${m.optString("message")}")
        }
        "idle" -> s.copy(streaming = "", pendingTask = null).endRun(now).status(SessionStatus.Idle, now).trace("idle")
        // The core started work by itself, not on a message from the app: e.g. DeepSeek Harness waking the session
        // when its background subagents report back. It is a run like any other (busy: its calls pass the gate).
        "agent_start" -> if (s.busy) s else s.copy(error = null, streaming = "", run = RunStats(now))
            .status(SessionStatus.Running, now).trace("the core started work by itself")
        // The core woke its agent with a notice (DeepSeek Harness: a subagent finished or wrote): what follows answers it.
        "trigger" -> {
            val from = m.optString("subagent")
            val outcome = m.optString("outcome").ifEmpty { null }
            val text = m.optString("text")
            val n = when {
                from.isEmpty() || s.items.none { it is ChatItem.Subagent && it.id == from } -> s
                // dsh's subagents send their result as a message before they finish: that is their report.
                m.optString("source") == "agent-message" -> s.withSubagent(from, now) { it.copy(result = text) }
                // Its settlement notice: it ended, with its closing message (unless it sent its report just before).
                outcome != null -> s.withSubagent(from, now) {
                    val closing = text.contains(CLOSING) || it.result.isBlank()
                    it.endThinking(null, now).copy(running = false, outcome = outcome, result = if (closing) text else it.result, ms = (now - it.startedAt).coerceAtLeast(0))
                }
                else -> s
            }
            n.endThinking(null, now).add(ChatItem.Trigger(m.optString("source"), m.optString("text"), from), now)
                .trace("woken · ${m.optString("source")}${if (from.isEmpty()) "" else " · $from"}${outcome?.let { " · $it" }.orEmpty()}")
        }
        // The agent started a subagent (DeepSeek Harness): its section, where its work shows as it goes.
        "subagent_start" -> s.withSubagent(m.optString("subagent"), now) { it.copy(label = m.optString("label").ifEmpty { it.label }) }
            .trace("subagent · ${m.optString("subagent")} · ${m.optString("label")}")
        // Pi took a steered message into the conversation: it is delivered.
        "message_end" -> if (m.optString("role") == "user") s.delivered(m.optString("text")) else s
        // A transcript is persisted by the manager; it doesn't change what the user sees.
        "transcript", "turn_end" -> s
        else -> s.trace(m.optString("type"))
    }

    /** [m] is a subagent's own event: it changes that subagent's section only (its steps count toward the run). */
    private fun subagent(s: SessionState, id: String, m: JSONObject, now: Long): SessionState {
        val n = s.withSubagent(id, now) { k ->
            when (m.optString("type")) {
                "thinking_start" -> k.endThinking(null, now).copy(thinking = "", thinkingSince = now)
                "thinking_delta" -> k.copy(thinking = k.thinking + m.optString("delta"), thinkingSince = k.thinkingSince ?: now)
                "thinking_end" -> k.endThinking(m.optString("text"), now)
                "text_delta" -> k.endThinking(null, now).copy(streaming = k.streaming + m.optString("delta"))
                "assistant_end" -> {
                    val text = m.optString("text").ifEmpty { k.streaming }
                    val e = k.endThinking(null, now).copy(streaming = "")
                    if (text.isBlank()) e else e.addItem(ChatItem.Assistant(text))
                }
                "tool_start" -> k.endThinking(null, now).addItem(step(m.optString("tool"), m.optJSONObject("args")))
                "tool_end" -> {
                    val i = k.items.indexOfLast { it is ChatItem.Step && it.ok == null && it.tool == m.optString("tool") }
                    if (i < 0) k else k.copy(items = k.items.toMutableList().also {
                        it[i] = (it[i] as ChatItem.Step).copy(ok = !m.optBoolean("isError"), result = resultLine(m.optString("output")))
                    })
                }
                "subagent_status" -> m.optBoolean("running").let { on -> (if (on) k else k.endThinking(null, now)).copy(running = on) }
                else -> k
            }
        }
        return if (m.optString("type") == "tool_start") n.copy(run = n.run?.let { it.copy(steps = it.steps + 1) }) else n
    }

    /** The session with subagent [id]'s section changed by [f] (a new section at the end when there is none yet). */
    private fun SessionState.withSubagent(id: String, now: Long, f: (ChatItem.Subagent) -> ChatItem.Subagent): SessionState {
        val i = items.indexOfLast { it is ChatItem.Subagent && it.id == id }
        if (i < 0) return add(f(ChatItem.Subagent(id, startedAt = now)), now)
        return copy(items = items.toMutableList().also { it[i] = f(it[i] as ChatItem.Subagent) })
    }

    private fun ChatItem.Subagent.addItem(item: ChatItem) = copy(items = (items + item).takeLast(MAX_SUBAGENT_ITEMS))

    /** The subagent's reasoning streaming now is over: it becomes one of its items (see SessionState.endThinking). */
    private fun ChatItem.Subagent.endThinking(text: String?, now: Long): ChatItem.Subagent {
        val since = thinkingSince ?: return if (thinking.isEmpty()) this else copy(thinking = "")
        val ms = (now - since).coerceAtLeast(0)
        val body = text?.takeIf { it.isNotBlank() } ?: thinking
        val n = copy(thinking = "", thinkingSince = null)
        return if (body.isBlank() && ms < 1000) n else n.addItem(ChatItem.Thinking(body.trim(), ms))
    }

    /** The user's message starts a task (queued until the screen is free when it operates the phone). */
    fun userMessage(s: SessionState, text: String, queued: Boolean, now: Long, attachments: List<Attachment> = emptyList()) =
        s.add(ChatItem.User(text, attachments = attachments), now).preview(previewOf(text, attachments), now)
            .copy(error = null, streaming = "", pendingTask = text, pendingAttachments = attachments, run = RunStats(now))
            .status(if (queued) SessionStatus.Queued else SessionStatus.Running, now)

    /** A message while Pi is working: shown at once, delivered when Pi takes it after the current turn. */
    fun steered(s: SessionState, text: String, now: Long, attachments: List<Attachment> = emptyList()) =
        s.add(ChatItem.User(text, Delivery.Queued, attachments), now).preview(previewOf(text, attachments), now).trace("steer · queued")

    /** A message while the task waits for a screen (Pi hasn't started): it joins the task. */
    fun joinTask(s: SessionState, text: String, now: Long, attachments: List<Attachment> = emptyList()) =
        s.add(ChatItem.User(text, attachments = attachments), now).preview(previewOf(text, attachments), now).copy(
            pendingTask = listOfNotNull(s.pendingTask, text.ifBlank { null }).joinToString("\n\n"),
            pendingAttachments = s.pendingAttachments + attachments,
        )

    /** Pi's run ended just before a steered message reached it: that message starts the next task. */
    fun lateSteer(s: SessionState, text: String, queued: Boolean, now: Long) =
        s.delivered(text).copy(error = null, streaming = "", pendingTask = text, pendingAttachments = s.queuedAttachments(text), run = RunStats(now))
            .status(if (queued) SessionStatus.Queued else SessionStatus.Running, now).trace("steer · late, sent as a new message")

    fun stopped(s: SessionState, now: Long) =
        s.copy(streaming = "", pendingTask = null, pendingAttachments = emptyList()).endRun(now).undelivered().status(SessionStatus.Idle, now).trace("stopped by user")

    /** Pi's runtime died under this session: its run is over. */
    fun runtimeLost(s: SessionState, message: String, now: Long): SessionState =
        if (!s.busy) s.trace("runtime restarted")
        else s.copy(streaming = "", pendingTask = null, pendingAttachments = emptyList(), error = message).endRun(now).undelivered().status(SessionStatus.Idle, now)
            .add(ChatItem.Error(message), now).preview(message, now, error = true).trace("runtime restarted")

    /** A queued task that gets the screen starts its clock then: the wait isn't the run's time. */
    fun withStatus(s: SessionState, status: SessionStatus, now: Long) =
        if (s.status == SessionStatus.Queued && status == SessionStatus.Running && s.run?.let { it.calls == 0 && it.steps == 0 } == true)
            s.copy(run = RunStats(now)).status(status, now)
        else s.status(status, now)

    /** The step a tool call starts: what it works on, and what it changes in a file (a write's or an edit's diff). */
    fun step(tool: String, args: JSONObject?) = ChatItem.Step(tool, stepDetail(tool, args), changes = stepChanges(tool, args))

    /**
     * What a file tool's call changes, from its arguments: DeepSeek Harness's write (file_path, content) and edit
     * (old_string, new_string), the str_replace_editor's str_replace, create and insert, and the oldText/newText
     * spelling (one edit, or several as `edits`). Other tools change no file.
     */
    fun stepChanges(tool: String, a: JSONObject?): List<FileChange> {
        if (a == null) return emptyList()
        fun JSONObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> if (has(k) && !isNull(k)) optString(k) else null }
        fun pair(o: JSONObject): FileChange? {
            val new = o.text("new_string", "newText", "new_str") ?: return null
            return FileChange.of(o.text("old_string", "oldText", "old_str").orEmpty(), new)
        }
        return when (tool) {
            "write" -> a.text("content", "file_text")?.let { listOf(FileChange.of("", it)) }.orEmpty()
            "edit", "multi_edit" -> a.optJSONArray("edits")?.let { e -> (0 until e.length()).mapNotNull { e.optJSONObject(it)?.let(::pair) } }
                ?: listOfNotNull(pair(a))
            "str_replace_editor" -> when (a.optString("command")) {
                "create" -> a.text("file_text")?.let { listOf(FileChange.of("", it)) }.orEmpty()
                "str_replace", "insert" -> listOfNotNull(pair(a))
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    /** A short, readable account of a tool call for the step card (typed text is shown, it's the user's task). */
    fun stepDetail(tool: String, a: JSONObject?): String = when (tool) {
        "open_app" -> a?.optString("app").orEmpty()
        "tap", "long_press" -> "[${a?.optInt("id")}]"
        "tap_at" -> "${a?.optInt("x")},${a?.optInt("y")}"
        "type_text" -> "“${a?.optString("text").orEmpty().take(40)}”"
        "swipe" -> a?.optString("direction").orEmpty()
        "scroll" -> a?.optString("direction").orEmpty()
        "press_key" -> a?.optString("key").orEmpty()
        "wait" -> "${a?.optInt("ms")} ms"
        // The command, whole enough for the details sheet to show it (the step line shows its first line).
        "bash" -> a?.optString("command").orEmpty().trim().take(600)
        "note" -> a?.optString("text").orEmpty().lineSequence().first().take(40)
        // the supervisor's tools
        "create_session" -> a?.optString("title").orEmpty().ifBlank { a?.optString("task").orEmpty().take(40) }
        // send_message: its name before message_session, in saved chats.
        "message_session", "send_message" -> a?.optString("message")?.takeIf { it.isNotEmpty() }?.let { "“${it.take(60)}”" }
            ?: "${a?.optString("id")}: “${a?.optString("text").orEmpty().take(40)}”"
        "get_session", "pause_session", "resume_session", "stop_session", "delete_session" -> a?.optString("id").orEmpty()
        "rename_session" -> "${a?.optString("id")} → ${a?.optString("title")}"
        // DeepSeek Harness's subagent: the task's short description.
        "subagent", "subagent_fork" -> a?.optString("description").orEmpty().ifBlank { a?.optString("prompt").orEmpty().take(40) }
        // DeepSeek Harness's own tools: what each one works on.
        "web_fetch" -> a?.optString("url").orEmpty()
        "web_search" -> a?.optJSONArray("queries")?.let { q -> (0 until q.length()).joinToString(" · ") { q.optString(it) } }
            ?: a?.optString("query").orEmpty()
        "read", "write", "edit", "read_image", "str_replace_editor" -> a?.optString("file_path").orEmpty().ifEmpty { a?.optString("path").orEmpty() }
        "grep", "glob" -> listOfNotNull(a?.optString("pattern")?.ifEmpty { null }, a?.optString("path")?.ifEmpty { null }).joinToString("  in  ")
        "interrupt_agent" -> a?.optString("agent_id").orEmpty()
        "skill" -> a?.optString("name").orEmpty()
        "wait_for" -> a?.optJSONArray("ids")?.let { ids -> (0 until ids.length()).joinToString(", ") { ids.getString(it) } }.orEmpty()
        else -> ""
    }

    private val APP_LINE = Regex("""^app: (\S+)""", RegexOption.MULTILINE)

    /** The list shows the text, or the attached files' names for a message without text. */
    private fun previewOf(text: String, attachments: List<Attachment>) = text.ifBlank { attachments.joinToString(", ") { it.name } }

    /** The attachments of the oldest queued message with this text. */
    private fun SessionState.queuedAttachments(text: String): List<Attachment> =
        items.firstOrNull { it is ChatItem.User && it.delivery == Delivery.Queued && it.text == text }?.let { (it as ChatItem.User).attachments }.orEmpty()

    /** The oldest queued message with this text is delivered (an exact match: Pi echoes what was steered). */
    private fun SessionState.delivered(text: String): SessionState {
        val queued = items.indices.filter { (items[it] as? ChatItem.User)?.delivery == Delivery.Queued }
        val i = queued.firstOrNull { (items[it] as ChatItem.User).text == text } ?: return this
        return copy(items = items.toMutableList().also { it[i] = (it[i] as ChatItem.User).copy(delivery = Delivery.Sent) })
    }

    /** The run is over: messages still queued were never read by the model. */
    private fun SessionState.undelivered(): SessionState =
        if (items.none { (it as? ChatItem.User)?.delivery == Delivery.Queued }) this
        else copy(items = items.map { if (it is ChatItem.User && it.delivery == Delivery.Queued) it.copy(delivery = Delivery.NotDelivered) else it })

    /**
     * The reasoning streaming now is over: it becomes an item (when there was any text) and counts toward the run's
     * thinking time. [text] is Pi's whole block when it says so, else what streamed.
     */
    private fun SessionState.endThinking(text: String?, now: Long): SessionState {
        val since = thinkingSince ?: return if (thinking.isEmpty()) this else copy(thinking = "")
        val ms = (now - since).coerceAtLeast(0)
        val body = text?.takeIf { it.isNotBlank() } ?: thinking
        val n = copy(thinking = "", thinkingSince = null, run = run?.let { it.copy(thinkingMs = it.thinkingMs + ms) })
        return if (body.isBlank() && ms < 1000) n else n.add(ChatItem.Thinking(body.trim(), ms), now)
    }

    /** One model call's tokens (Pi's Usage) added to the run. */
    private fun SessionState.countUsage(u: JSONObject?, model: String): SessionState {
        val r = run ?: return this
        if (u == null) return copy(run = r.copy(calls = r.calls + 1, model = model))
        return copy(run = r.copy(
            calls = r.calls + 1, model = model,
            input = r.input + u.optLong("input"), output = r.output + u.optLong("output"),
            cacheRead = r.cacheRead + u.optLong("cacheRead"), cacheWrite = r.cacheWrite + u.optLong("cacheWrite"),
            reasoning = r.reasoning + u.optLong("reasoning"),
            cost = r.cost + (u.optJSONObject("cost")?.optDouble("total")?.takeIf { !it.isNaN() } ?: 0.0),
        ))
    }

    /** The run is over: what it took becomes the last item of its turn (when the model was called at all). */
    private fun SessionState.endRun(now: Long): SessionState {
        val n = endThinking(null, now).let { st ->
            // A subagent dsh never reported settled (Stop, an error) ended with the run.
            if (st.items.none { it is ChatItem.Subagent && !it.settled }) st
            else st.copy(items = st.items.map {
                if (it is ChatItem.Subagent && !it.settled) it.endThinking(null, now).copy(running = false, streaming = "", outcome = "stopped", ms = (now - it.startedAt).coerceAtLeast(0)) else it
            })
        }
        val r = n.run ?: return n
        val done = n.copy(run = null)
        return if (r.calls == 0 && r.steps == 0) done else done.add(ChatItem.Stats(r.copy(ms = (now - r.startedAt).coerceAtLeast(0))), now)
    }

    private fun SessionState.status(st: SessionStatus, now: Long) = copy(summary = summary.copy(status = st, updatedAt = now))
    private fun SessionState.add(item: ChatItem, now: Long) = copy(items = (items + item).takeLast(MAX_ITEMS), summary = summary.copy(updatedAt = now))
    private fun SessionState.preview(text: String, now: Long, error: Boolean = false) =
        copy(summary = summary.copy(preview = text.replace('\n', ' ').take(140), error = error, updatedAt = now))
    private fun SessionState.trace(line: String) = copy(trace = (trace + line).takeLast(MAX_TRACE))
}
