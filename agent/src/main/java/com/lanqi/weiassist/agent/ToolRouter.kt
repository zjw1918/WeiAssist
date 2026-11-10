package com.lanqi.weiassist.agent

import com.lanqi.weiassist.core.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
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
    private val bashPeek: suspend (session: String, call: String, timeoutSec: Int) -> String?,
    private val bashDetached: (session: String, call: String, command: String, timeoutSec: Int) -> Unit,
    private val sendFile: suspend (session: String, path: String, caption: String) -> Pair<Boolean, String>,
    private val supervisor: suspend (caller: String, action: String, args: JSONObject) -> String,
    /** Ask the user ([call]: the tool call, also the question's id); [askCancel] withdraws it. */
    private val ask: suspend (session: String, call: String, args: JSONObject) -> ToolResult = { _, _, _ -> ToolResult("Nobody to ask.", isError = true) },
    private val askCancel: suspend (session: String, call: String) -> Unit = { _, _ -> },
    private val files: suspend (session: String, name: String, args: JSONObject) -> ToolResult = { _, n, _ -> ToolResult("No file tool $n.", isError = true) },
    private val web: suspend (args: JSONObject) -> ToolResult = { a -> WebFetch.run(a).let { ToolResult(it.text, isError = it.isError) } },
) {
    private data class Running(val command: String, val timeoutSec: Int)

    /** Bash commands running now, as "session/call": only these have anything to cancel. */
    private val runningBash = java.util.concurrent.ConcurrentHashMap<String, Running>()
    /** Questions waiting for the user, as "session/call": Stop withdraws them. */
    private val runningAsks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Web fetches running now, as "session/call": Stop cancels them. */
    private val runningWeb = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

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
                    val timeout = bashTimeout(args)
                    val command = args.getString("command")
                    runningBash["$session/$call"] = Running(command, timeout)
                    try { ToolResult(bash(session, call, command, timeout)) } finally { runningBash -= "$session/$call" }
                }
            ToolCatalog.WEB ->
                if (!gate(session)) ToolResult("stopped by the user")
                else kotlinx.coroutines.coroutineScope {
                    val fetch = async { web(args) }
                    runningWeb["$session/$call"] = fetch
                    try { fetch.await() }
                    catch (e: kotlinx.coroutines.CancellationException) { if (fetch.isCancelled && isActive) ToolResult("stopped by the user") else throw e }
                    finally { runningWeb -= "$session/$call" }
                }
            ToolCatalog.ASK -> if (!gate(session)) ToolResult("stopped by the user") else {
                runningAsks += "$session/$call"
                try { ask(session, call, args) } finally { runningAsks -= "$session/$call" }
            }
            // The plan itself is the call's arguments: the app shows it from there (SessionReducer, Plan).
            ToolCatalog.PLAN -> Plan.parse(args)?.let { ToolResult(Plan.answer(it)) } ?: ToolResult("Give todos: a list of {content, status}.", isError = true)
            ToolCatalog.FILES -> if (!gate(session)) ToolResult("stopped by the user") else files(session, name, args)
            ToolCatalog.SEND_FILE -> {
                val (ok, text) = if (!gate(session)) false to "Stopped by the user." else sendFile(session, args.getString("path"), args.optString("caption"))
                ToolResult(text, isError = !ok)
            }
            ToolCatalog.SUPERVISOR -> supervisorCall(name, args).let { (action, a) -> ToolResult(supervisor(session, action, a)) }
            else -> ToolResult("No way to run $name.", isError = true)
        }
    }

    /** The user stopped the run while call [call] worked: end what it started (a bash command in Termux, a fetch). */
    suspend fun cancel(session: String, call: String) {
        if (runningBash.containsKey("$session/$call")) bashKill(session, call)
        runningWeb["$session/$call"]?.cancel()
        if ("$session/$call" in runningAsks) askCancel(session, call)
    }

    /** How call [call] is doing, for the model while it goes on in the background; null if it has ended. */
    suspend fun peek(session: String, call: String): String? =
        runningBash["$session/$call"]?.let { r -> bashPeek(session, call, r.timeoutSec)?.also { bashDetached(session, call, r.command, r.timeoutSec) } }

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
            "message_session" -> "send" to JSONObject().put("id", a.optString("id")).put("text", a.optString("text"))
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
