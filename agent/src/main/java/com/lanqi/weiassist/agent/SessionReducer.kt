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
    fun reduce(s: SessionState, m: JSONObject, now: Long, friendly: (String) -> String = { it }): SessionState = when (m.optString("type")) {
        "text_delta" -> s.copy(streaming = s.streaming + m.optString("delta"))
        "assistant_end" -> {
            val text = m.optString("text").ifEmpty { s.streaming }
            val err = if (m.optString("stopReason") == "error") friendly(m.optString("errorMessage")) else null
            var n = s.copy(streaming = "", error = err ?: s.error)
            if (text.isNotBlank()) n = n.add(ChatItem.Assistant(text), now).preview(Markdown.plain(text), now)
            if (err != null) n = n.add(ChatItem.Error(err), now).preview(err, now, error = true)
            n.trace("assistant_end · ${m.optString("provider")}/${m.optString("model")} · stop=${m.optString("stopReason")} · tokens=${m.optJSONObject("usage")?.optInt("totalTokens")}")
        }
        "tool_start" -> {
            val args = m.optJSONObject("args")
            val app = if (m.optString("tool") == "open_app") args?.optString("app") else null
            s.add(ChatItem.Step(m.optString("tool"), stepDetail(m.optString("tool"), args)), now)
                .let { if (app != null) it.copy(summary = it.summary.copy(app = app)) else it }
                .trace("→ ${m.optString("tool")} ${args?.toString().orEmpty()}")
        }
        "tool_end" -> {
            val ok = !m.optBoolean("isError")
            val out = m.optString("output").lineSequence().firstOrNull().orEmpty().take(80)
            // The open step of this tool is the last Step without a result.
            val i = s.items.indexOfLast { it is ChatItem.Step && it.ok == null && it.tool == m.optString("tool") }
            val items = if (i < 0) s.items else s.items.toMutableList().also { it[i] = (it[i] as ChatItem.Step).copy(ok = ok, result = out) }
            val app = Regex("^app: (\\S+)").find(m.optString("output"))?.groupValues?.get(1)
            s.copy(items = items, summary = if (app != null) s.summary.copy(app = app) else s.summary).trace("  ${if (ok) "✓" else "✗"} $out")
        }
        "configured" -> s.trace("configured · ${m.optString("model")} · thinking=${m.optString("thinkingLevel")} → ${m.optString("baseUrl")} · auth=${m.optString("auth")} · history=${m.optInt("messages")}")
        "error" -> {
            val err = friendly(m.optString("message"))
            s.copy(error = err, pendingTask = null).status(SessionStatus.Idle, now)
                .add(ChatItem.Error(err), now).preview(err, now, error = true).trace("error · ${m.optString("message")}")
        }
        "idle" -> s.copy(streaming = "", pendingTask = null).status(SessionStatus.Idle, now).trace("idle")
        // A transcript is persisted by the manager; it doesn't change what the user sees.
        "transcript", "turn_end", "message_end" -> s
        else -> s.trace(m.optString("type"))
    }

    /** The user's message starts a task (queued until the screen is free when it operates the phone). */
    fun userMessage(s: SessionState, text: String, queued: Boolean, now: Long) =
        s.add(ChatItem.User(text), now).preview(text, now)
            .copy(error = null, streaming = "", pendingTask = text)
            .status(if (queued) SessionStatus.Queued else SessionStatus.Running, now)

    fun stopped(s: SessionState, now: Long) =
        s.copy(streaming = "", pendingTask = null).status(SessionStatus.Idle, now).trace("stopped by user")

    /** Pi's runtime died under this session: its run is over. */
    fun runtimeLost(s: SessionState, message: String, now: Long): SessionState =
        if (!s.busy) s.trace("runtime restarted")
        else s.copy(streaming = "", pendingTask = null, error = message).status(SessionStatus.Idle, now)
            .add(ChatItem.Error(message), now).preview(message, now, error = true).trace("runtime restarted")

    fun withStatus(s: SessionState, status: SessionStatus, now: Long) = s.status(status, now)

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
        // the supervisor's tools
        "create_session" -> a?.optString("title").orEmpty().ifBlank { a?.optString("task").orEmpty().take(40) }
        "send_message" -> "${a?.optString("id")}: “${a?.optString("text").orEmpty().take(40)}”"
        "get_session", "pause_session", "resume_session", "stop_session", "delete_session" -> a?.optString("id").orEmpty()
        "rename_session" -> "${a?.optString("id")} → ${a?.optString("title")}"
        "wait_for" -> a?.optJSONArray("ids")?.let { ids -> (0 until ids.length()).joinToString(", ") { ids.getString(it) } }.orEmpty()
        else -> ""
    }

    private fun SessionState.status(st: SessionStatus, now: Long) = copy(summary = summary.copy(status = st, updatedAt = now))
    private fun SessionState.add(item: ChatItem, now: Long) = copy(items = (items + item).takeLast(MAX_ITEMS), summary = summary.copy(updatedAt = now))
    private fun SessionState.preview(text: String, now: Long, error: Boolean = false) =
        copy(summary = summary.copy(preview = text.replace('\n', ' ').take(140), error = error, updatedAt = now))
    private fun SessionState.trace(line: String) = copy(trace = (trace + line).takeLast(MAX_TRACE))
}
