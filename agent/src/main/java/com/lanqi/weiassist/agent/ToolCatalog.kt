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
     * The tools of a session: the supervisor's own (it doesn't operate the phone or run commands); phone tools
     * for a phone session; bash with Termux; send_file with Termux in a messaging app.
     */
    fun forSession(s: SessionSummary): List<ToolSpec> {
        val sets = when {
            s.supervisor -> setOf(SUPERVISOR)
            else -> buildSet {
                if (s.phone) add(PHONE)
                if (s.termux) add(BASH)
                if (s.termux && s.channel != null) add(SEND_FILE)
            }
        }
        return entries.filter { it.set in sets }.map { it.spec }
    }

    const val PHONE = "phone"
    const val BASH = "bash"
    const val SEND_FILE = "send_file"
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
