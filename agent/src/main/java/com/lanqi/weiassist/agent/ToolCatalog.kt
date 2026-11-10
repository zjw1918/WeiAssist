package com.lanqi.weiassist.agent

import com.lanqi.weiassist.core.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's tools, for every AI core: `tools.json` (next to this class in resources) holds each tool's name,
 * label, description and JSON Schema, exactly as the model sees them. The app chooses a session's tools
 * ([forSession]), a core declares them, and [ToolRouter] runs the calls.
 */
object ToolCatalog {
    /** A catalog entry: the spec a core declares, and [set], which sessions get it. */
    data class Entry(val spec: ToolSpec, val set: String)

    val entries: List<Entry> by lazy { parse(load()) }

    fun named(name: String): Entry? = entries.firstOrNull { it.spec.name == name }

    /**
     * The tools of a session: the supervisor's own (it doesn't operate the phone or run commands); web_fetch for
     * every other session; the file tools
     * unless the user turned them off; phone tools for a phone session; bash and send_file with Termux (send_file: to the
     * messaging app's chat, or the app's). A core that has a tool of the same name keeps its own (dsh's web_fetch).
     */
    fun forSession(s: SessionSummary): List<ToolSpec> {
        val sets = when {
            s.supervisor -> setOf(SUPERVISOR)
            else -> buildSet {
                add(WEB)
                if (files()) add(FILES)
                if (s.phone) add(PHONE)
                if (s.termux) add(BASH)
                if (s.termux) add(SEND_FILE)
            }
        }
        return entries.filter { it.set in sets }.map { it.spec }
    }

    const val PHONE = "phone"
    const val BASH = "bash"
    const val SEND_FILE = "send_file"
    const val WEB = "web"
    const val FILES = "files"

    /** Whether the user lets the assistant use files at all (Settings → File access); read when a session is set up. */
    @Volatile var files: () -> Boolean = { true }
    const val SUPERVISOR = "supervisor"

    private fun load(): String = checkNotNull(ToolCatalog::class.java.getResourceAsStream("tools.json")) { "tools.json is missing" }
        .use { String(it.readBytes(), Charsets.UTF_8) }

    fun parse(json: String): List<Entry> {
        val a: JSONArray = JSONObject(json).getJSONArray("tools")
        return (0 until a.length()).map { i ->
            val t = a.getJSONObject(i)
            Entry(
                ToolSpec(
                    name = t.getString("name"), label = t.getString("label"), description = t.getString("description"),
                    parameters = t.getJSONObject("parameters").toString(), parallel = t.optBoolean("parallel"),
                ),
                set = t.getString("set"),
            )
        }.also { l -> require(l.map { it.spec.name }.toSet().size == l.size) { "two tools share a name" } }
    }
}
