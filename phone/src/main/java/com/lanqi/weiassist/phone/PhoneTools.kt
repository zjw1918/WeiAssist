package com.lanqi.weiassist.phone

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone tools as every AI core sees them. A core only declares the tools to its model. Their behavior
 * and safety live here, per session and per task:
 * - [call]: one tool. An action returns its result and then the screen it leaves (one model call instead of
 *   two), and the screen read is remembered so a later tap can be judged by its button's label.
 * - [check]: before every tool call of a phone session. A tap on an irreversible button (Send, Pay, Delete…)
 *   needs the user's OK. So does a blind tap or Enter after text was typed into a field read_screen can't see
 *   (WeChat hides its screen). A runaway task stops after [PhonePolicy.MAX_STEPS] actions.
 *
 * [run] does one raw action on the session's screen (the app's PhoneActions, with its screen and pause rules)
 * and returns its text.
 */
class PhoneTools(private val run: suspend (session: String, action: String, args: JSONObject) -> String) {
    /** What a confirmation card shows: the button's label (empty when the app hides it) and what was typed. */
    data class Confirmation(val label: String, val blind: Boolean, val typed: List<String>)

    /** One task's memory: the last screen read, what was typed, whether typing was blind, the actions taken. */
    private class Task {
        var screen: Map<Int, String> = emptyMap()
        val typed = mutableListOf<String>()
        var blindTyped = false
        var steps = 0
    }

    private val tasks = ConcurrentHashMap<String, Task>()

    private fun task(session: String) = tasks.getOrPut(session) { Task() }

    /** Session [session] starts a new task: step count, screen memory and typed fields start over. */
    fun taskStarted(session: String) { tasks[session] = Task() }

    /** The session is gone. */
    fun forget(session: String) { tasks.remove(session) }

    /** Run phone tool [tool] for [session]; returns what the model gets (a screenshot: base64 JPEG, or a message). */
    suspend fun call(session: String, tool: String, args: JSONObject): String = when (tool) {
        "read_screen" -> readScreen(session)
        "screenshot" -> run(session, "screenshot", JSONObject())
        "wait" -> {
            delay(args.optLong("ms").coerceIn(0, 5_000))
            withScreen(session, "waited")
        }
        in ACTIONS -> {
            if (tool == "type_text") noteTyped(task(session), args)
            withScreen(session, run(session, tool, args))
        }
        else -> throw IllegalArgumentException("no phone tool $tool")
    }

    /**
     * Before tool [tool] of a phone session: null to go on, or why it is blocked (the task then ends).
     * [confirm] asks the user and returns whether they allowed it.
     */
    suspend fun check(session: String, tool: String, args: JSONObject, confirm: suspend (Confirmation) -> Boolean): String? {
        val t = task(session)
        if (tool != "note" && ++t.steps > PhonePolicy.MAX_STEPS) return PhonePolicy.STEP_LIMIT
        var label = ""
        var blind = false
        var typed = t.typed.toList()
        when {
            tool == "tap" -> label = PhonePolicy.labelOf(args.id()?.let { t.screen[it] } ?: "")
            tool == "tap_at" || tool == "drag" && tapLike(args) -> {
                val (x, y) = if (tool == "drag") args.optInt("x1") to args.optInt("y1") else args.optInt("x") to args.optInt("y")
                label = run(session, "label_at", JSONObject().put("x", x).put("y", y))
                blind = t.blindTyped && label.isBlank()
            }
            tool == "press_key" && args.optString("key") == "enter" && t.blindTyped -> blind = true
            tool == "type_text" && args.optBoolean("submit") && (args.id() == null || t.blindTyped) -> {
                // Typing and pressing Enter in one call: the text isn't noted yet ([call] does that), so add it to the card.
                typed = typed + typedLine(t, args)
                blind = true
            }
            else -> return null
        }
        if (!blind && !PhonePolicy.risky(label)) return null
        // blindTyped stays set even when allowed: nothing can tell whether the tap hit Send (a moved button, a
        // miss), so every unreadable tap after blind typing asks until the task ends.
        return if (confirm(Confirmation(label, blind, typed))) null else PhonePolicy.DECLINED
    }

    private suspend fun readScreen(session: String): String =
        run(session, "read_screen", JSONObject()).also { task(session).screen = PhonePolicy.parseScreen(it) }

    private suspend fun withScreen(session: String, result: String) = "$result${PhonePolicy.SCREEN_NOW}${readScreen(session)}"

    private fun noteTyped(t: Task, args: JSONObject) {
        t.typed += typedLine(t, args)
        if (args.id() == null) t.blindTyped = true
    }

    private fun typedLine(t: Task, args: JSONObject): String {
        val id = args.id()
        return "${if (id == null) "field" else PhonePolicy.fieldName(t.screen[id])}: ${args.optString("text")}"
    }

    /**
     * A drag that ends where it began (within a finger's width) clicks what is there, as a tap does. A longer one
     * moves things (a joystick, a slider) and clicks nothing.
     */
    private fun tapLike(a: JSONObject): Boolean =
        kotlin.math.hypot((a.optInt("x2") - a.optInt("x1")).toDouble(), (a.optInt("y2") - a.optInt("y1")).toDouble()) <= TAP_SLOP

    /** An element id from the latest screen, if the call names one. */
    private fun JSONObject.id(): Int? = if (has("id") && !isNull("id")) optInt("id") else null

    private companion object {
        /** Tools that act on the screen: done through [run], then the screen they leave is read. */
        val ACTIONS = setOf("open_app", "tap", "tap_at", "drag", "long_press", "swipe", "type_text", "scroll", "press_key")
        /** Pixels a drag may move and still count as a tap. */
        const val TAP_SLOP = 30.0
    }
}
