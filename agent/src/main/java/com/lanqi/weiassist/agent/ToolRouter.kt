package com.lanqi.weiassist.agent

import com.lanqi.weiassist.core.ToolResult
import org.json.JSONObject

/**
 * Runs a tool call from any AI core (the `tool` host call): the core sends the tool's name and the model's
 * arguments, and this does the rest. It checks the session has that tool, shapes the arguments for the part of
 * the app that does the work, and shapes the result for the model. A session may only call its own tools
 * ([ToolCatalog.forSession]).
 */
class ToolRouter(
    /** The session's summary, or null if it is gone. */
    private val summary: (String) -> SessionSummary?,
    /** False once the user stopped the session (or it is gone): a command doesn't start then. */
    private val gate: suspend (String) -> Boolean,
    private val phone: suspend (session: String, tool: String, args: JSONObject) -> String,
    private val bash: suspend (session: String, call: String, command: String, timeoutSec: Int) -> String,
    private val bashKill: suspend (session: String, call: String) -> Unit,
    private val sendFile: suspend (session: String, path: String, caption: String) -> Pair<Boolean, String>,
    private val supervisor: suspend (caller: String, action: String, args: JSONObject) -> String,
) {
    /** Bash commands running now, as "session/call": only these have anything to cancel. */
    private val runningBash = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun call(session: String, call: String, name: String, args: JSONObject): ToolResult {
        val s = summary(session) ?: return ToolResult("This session no longer exists.", isError = true)
        val entry = ToolCatalog.named(name)
        if (entry == null || ToolCatalog.forSession(s).none { it.name == name }) return ToolResult("This session has no tool $name.", isError = true)
        return when (entry.set) {
            ToolCatalog.PHONE -> when (name) {
                // A scratchpad for the model: nothing happens on the phone.
                "note" -> ToolResult("noted")
                "screenshot" -> screenshot(phone(session, name, args))
                else -> ToolResult(phone(session, name, args))
            }
            ToolCatalog.BASH ->
                if (!gate(session)) ToolResult("stopped by the user")
                else {
                    runningBash += "$session/$call"
                    try { ToolResult(bash(session, call, args.getString("command"), bashTimeout(args))) } finally { runningBash -= "$session/$call" }
                }
            ToolCatalog.SEND_FILE -> {
                val (ok, text) = if (!gate(session)) false to "Stopped by the user." else sendFile(session, args.getString("path"), args.optString("caption"))
                ToolResult(text, isError = !ok)
            }
            ToolCatalog.SUPERVISOR -> supervisorCall(name, args).let { (action, a) -> ToolResult(supervisor(session, action, a)) }
            else -> ToolResult("No way to run $name.", isError = true)
        }
    }

    /** The user stopped the run while call [call] worked: end what it started (a bash command in Termux). */
    suspend fun cancel(session: String, call: String) {
        if ("$session/$call" in runningBash) bashKill(session, call)
    }

    companion object {
        const val BASH_TIMEOUT = 120
        const val BASH_MAX_TIMEOUT = 600

        fun bashTimeout(args: JSONObject): Int =
            (if (args.has("timeout") && !args.isNull("timeout")) args.optInt("timeout", BASH_TIMEOUT) else BASH_TIMEOUT).coerceIn(1, BASH_MAX_TIMEOUT)

        /** Anything but a base64 JPEG ("/9j/…") is a message for the model (an error, paused, no app yet): as an image it would make the provider reject the request. */
        fun screenshot(data: String): ToolResult =
            if (!data.startsWith("/9j/")) ToolResult(data)
            else ToolResult("Screenshot (720 px wide; for tap_at, scale x and y by screen width / 720).", image = data)

        /** A supervisor tool as SessionCommands' action and arguments. */
        fun supervisorCall(name: String, a: JSONObject): Pair<String, JSONObject> = when (name) {
            "list_sessions" -> "list" to JSONObject()
            "get_session" -> "get" to JSONObject().put("id", a.optString("id"))
            "create_session" -> "create" to JSONObject().put("task", a.optString("task")).put("phone", a.optBoolean("phone")).put("title", a.optString("title"))
            "send_message" -> "send" to JSONObject().put("id", a.optString("id")).put("text", a.optString("text"))
            "wait_for" -> "wait" to JSONObject().put("ids", a.optJSONArray("ids") ?: org.json.JSONArray())
                .put("timeout", (if (a.has("timeout_seconds") && !a.isNull("timeout_seconds")) a.optLong("timeout_seconds", 300) else 300L).coerceIn(5, 600))
            "pause_session" -> "pause" to JSONObject().put("id", a.optString("id"))
            "resume_session" -> "resume" to JSONObject().put("id", a.optString("id"))
            "stop_session" -> "stop" to JSONObject().put("id", a.optString("id"))
            "delete_session" -> "delete" to JSONObject().put("id", a.optString("id"))
            "rename_session" -> "rename" to JSONObject().put("id", a.optString("id")).put("title", a.optString("title"))
            else -> throw IllegalArgumentException("no supervisor tool $name")
        }

        /** New sessions per user message the supervisor may start: a runaway plan stops here. */
        const val MAX_CREATES = 8
        const val TOO_MANY_CREATES = "At most $MAX_CREATES new sessions per request. Tell the user what you started and ask before starting more."
    }
}
