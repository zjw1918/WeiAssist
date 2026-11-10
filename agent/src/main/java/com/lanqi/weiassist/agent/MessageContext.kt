package com.lanqi.weiassist.agent

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the model should know with every user message but the user never typed: the current date and time. It goes
 * as a last text block, `<context>…</context>`, with each message (also steered ones), so a session that lasts
 * days still knows when "now" is. The chat shows only what the user typed ([strip]).
 */
object MessageContext {
    private val FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)
    private val BLOCK = Regex("\\s*<context>.*?</context>\\s*", RegexOption.DOT_MATCHES_ALL)

    /** "Now: Tuesday, 10 November 2026, 13:20 (Asia/Shanghai, UTC+08:00). Phone language: zh-CN." */
    fun text(now: ZonedDateTime = ZonedDateTime.now(), locale: Locale = Locale.getDefault()): String {
        val offset = now.offset.id.let { if (it == "Z") "+00:00" else it }
        return "<context>Now: ${now.format(FORMAT)} (${now.zone.id}, UTC$offset). Phone language: ${locale.toLanguageTag()}.</context>"
    }

    /** [content] (the attachments' blocks, or none) with the context block after them. */
    fun append(content: JSONArray?, now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): JSONArray =
        JSONArray().apply {
            content?.let { c -> for (i in 0 until c.length()) put(c.get(i)) }
            put(JSONObject().put("type", "text").put("text", text(now)))
        }

    /** A message's text as the user typed it: without the context block a core may echo back with it. */
    fun strip(text: String): String = text.replace(BLOCK, "").trim()
}
