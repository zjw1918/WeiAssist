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

    /** The user's file access (Settings → File access): what the file guide says about the phone's storage. */
    @Volatile var fileAccess: () -> FileTools.Level = { FileTools.Level.Session }

    fun text(id: String): String = cache.getOrPut(id) {
        checkNotNull(Prompts::class.java.getResourceAsStream("prompts/$id.md")) { "prompts/$id.md is missing" }
            .use { String(it.readBytes(), Charsets.UTF_8) }.trim()
    }

    /** A messaging app's name as the prompt says it. */
    private fun channelName(channel: String) = if (channel == "telegram") "Telegram" else channel

    /**
     * The sections of a session's prompt, in order. The supervisor manages sessions: its persona only. Others: chat or
     * phone persona; the web guide; the file guide (where paths lead, the phone's storage if allowed); the Termux bash guide with Termux; the messaging app's, with how files travel when it has Termux.
     */
    fun forSession(s: SessionSummary, name: String): List<PromptSection> {
        fun section(id: String, joiner: String = "", forTool: String? = null) =
            PromptSection(id, joiner + text(id).replace("{name}", name).replace("{channel}", s.channel?.let(::channelName).orEmpty()), forTool)
        if (s.supervisor) return listOf(section("supervisor"))
        return buildList {
            add(section(if (s.phone) "phone" else "chat"))
            add(section("web", "\n\n", forTool = "web_fetch"))
            add(section("files", "\n\n", forTool = "read"))
            add(section(if (s.termux) "files-termux" else "files-app", "\n", forTool = "read"))
            when (fileAccess()) {
                FileTools.Level.StorageWrite -> add(section("files-storage", "\n", forTool = "read"))
                FileTools.Level.StorageRead -> add(section("files-storage-ro", "\n", forTool = "read"))
                else -> Unit
            }
            if (s.termux) add(section("bash", "\n\n", forTool = "bash"))
            if (s.channel != null) {
                add(section("channel", "\n\n"))
                if (s.termux) add(section("channel-files", "\n", forTool = "send_file"))
            }
        }
    }
}
