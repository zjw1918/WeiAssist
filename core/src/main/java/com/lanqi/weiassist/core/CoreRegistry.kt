package com.lanqi.weiassist.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.json.JSONObject

/** An event from core [core] (see [AgentCore.events]). */
data class CoreEvent(val core: String, val message: JSONObject)

/**
 * The installed cores. New sessions use [defaultId]. A saved session keeps the core it was made with, because
 * transcripts don't carry over between cores. Adding a core = one more entry here; nothing else changes.
 */
class CoreRegistry(cores: List<AgentCore>, defaultId: String = cores.first().info.id) {
    private val byId: Map<String, AgentCore> = cores.associateBy { it.info.id }

    init {
        require(byId.size == cores.size) { "two cores share an id" }
        require(defaultId in byId) { "no core $defaultId" }
    }

    val all: List<AgentCore> = cores
    val ids: Set<String> get() = byId.keys

    /** The core new sessions use. */
    var defaultId: String = defaultId
        set(value) {
            require(value in byId) { "no core $value" }
            field = value
        }

    val default: AgentCore get() = byId.getValue(defaultId)

    /** Core [id], or null when it isn't installed (a session made by a core this build lacks). */
    operator fun get(id: String): AgentCore? = byId[id]

    /** Every core's events, each tagged with its core. */
    val events: Flow<CoreEvent> = merge(*cores.map { c -> c.events.map { CoreEvent(c.info.id, it) } }.toTypedArray())

    /** Send every core's tool calls to [host]. */
    fun bind(host: ToolHost) = all.forEach { it.bind(host) }

    fun destroy() = all.forEach { it.destroy() }
}
