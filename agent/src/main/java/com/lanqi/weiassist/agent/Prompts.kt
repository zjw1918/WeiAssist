package com.lanqi.weiassist.agent

import com.lanqi.weiassist.core.PromptSection

/**
 * The app's system prompts, for every AI core: `prompts/<id>.md` next to this class in resources, with `{name}` (the
 * assistant's name) and `{channel}` (the messaging app) filled in. A session gets its persona (chat, phone or
 * supervisor), then what its tools and its chat need ([forSession]). A core keeps the sections it can honour
 * (core's composePrompt) beside its own guidance.
 */
object Prompts {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun text(id: String): String = cache.getOrPut(id) {
        checkNotNull(Prompts::class.java.getResourceAsStream("prompts/$id.md")) { "prompts/$id.md is missing" }
            .use { String(it.readBytes(), Charsets.UTF_8) }.trim()
    }

    /** A messaging app's name as the prompt says it. */
    private fun channelName(channel: String) = if (channel == "telegram") "Telegram" else channel

    /**
     * The sections of a session's prompt, in order. The supervisor manages sessions: its persona only. Others: chat or
     * phone persona; the Termux bash guide with Termux; the messaging app's, with how files travel when it has Termux.
     * [model]: the model in use (its id), named in the persona so the model answers which one it is truthfully rather
     * than as [name], the app; null names none (the mock).
     */
    fun forSession(s: SessionSummary, name: String, model: String? = null): List<PromptSection> {
        fun section(id: String, joiner: String = "", forTool: String? = null) =
            PromptSection(id, joiner + text(id).replace("{name}", name).replace("{channel}", s.channel?.let(::channelName).orEmpty()), forTool)
        fun persona(id: String) = section(id).let { p ->
            if (model.isNullOrBlank()) p else p.copy(text = p.text + "\n\n" + text("model").replace("{name}", name).replace("{model}", model))
        }
        if (s.supervisor) return listOf(persona("supervisor"))
        return buildList {
            add(persona(if (s.phone) "phone" else "chat"))
            if (s.termux) add(section("bash", "\n\n", forTool = "bash"))
            if (s.channel != null) {
                add(section("channel", "\n\n"))
                if (s.termux) add(section("channel-files", "\n", forTool = "send_file"))
            }
        }
    }
}
