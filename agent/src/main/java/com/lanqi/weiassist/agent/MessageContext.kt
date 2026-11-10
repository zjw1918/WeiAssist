package com.lanqi.weiassist.agent

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the model should know with every user message but the user never typed: the current date and time, and what
 * the user did meanwhile outside the conversation (stopped a background command or a tmux job). It goes as a last
 * text block, `<context>…</context>`, with each message (also steered ones), so a session that lasts days still knows
 * when "now" is. The chat shows only what the user typed ([strip]).
 */
object MessageContext {
    private val FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)
    private val BLOCK = Regex("\\s*<context>.*?</context>\\s*", RegexOption.DOT_MATCHES_ALL)

    /** "Now: Tuesday, 10 November 2026, 13:20 (Asia/Shanghai, UTC+08:00). Phone language: zh-CN." */
    fun text(now: ZonedDateTime = ZonedDateTime.now(), locale: Locale = Locale.getDefault(), notes: List<String> = emptyList()): String {
        val offset = now.offset.id.let { if (it == "Z") "+00:00" else it }
        val since = if (notes.isEmpty()) "" else "\nSince your last turn: " + notes.joinToString(" ")
        return "<context>Now: ${now.format(FORMAT)} (${now.zone.id}, UTC$offset). Phone language: ${locale.toLanguageTag()}.$since</context>"
    }

    /** [content] (the attachments' blocks, or none) with the context block after them, and [notes] in it. */
    fun append(content: JSONArray?, notes: List<String> = emptyList(), now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): JSONArray =
        JSONArray().apply {
            content?.let { c -> for (i in 0 until c.length()) put(c.get(i)) }
            put(JSONObject().put("type", "text").put("text", text(now, notes = notes)))
        }

    /** What Stop ended besides the run ([commands] in the background, [tmux] jobs), for the model; null if nothing. */
    fun stopped(commands: List<String>, tmux: List<String>): String? {
        if (commands.isEmpty() && tmux.isEmpty()) return null
        fun list(things: List<String>) = things.joinToString(", ") { "`${it.lineSequence().first().take(120)}`" }
        return buildList {
            if (commands.isNotEmpty()) add("background command${if (commands.size > 1) "s" else ""} ${list(commands)}")
            if (tmux.isNotEmpty()) add("tmux job${if (tmux.size > 1) "s" else ""} ${list(tmux)}")
        }.joinToString(" and ", prefix = "The user pressed Stop, which ended your ", postfix = ". They are not running any more.")
    }

    /** A message's text as the user typed it: without the context block a core may echo back with it. */
    fun strip(text: String): String = text.replace(BLOCK, "").trim()
}
