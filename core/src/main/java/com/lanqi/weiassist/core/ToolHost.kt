package com.lanqi.weiassist.core

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's side of tool calls, shared by every core: the app registers a handler per call type
 * (`before_tool`, `phone`, `bash`, `send_file`, `supervisor`, …) once, and each core sends its tool calls here. A request
 * carries the `session` it is for. The answer (String, Boolean, JSONObject…) goes back to the model, and a
 * thrown exception fails the call.
 */
class ToolHost {
    private val handlers = ConcurrentHashMap<String, suspend (JSONObject) -> Any?>()

    fun register(type: String, handler: suspend (JSONObject) -> Any?) {
        check(handlers.putIfAbsent(type, handler) == null) { "$type already has a handler" }
    }

    fun handles(type: String) = handlers.containsKey(type)

    suspend fun call(type: String, request: JSONObject): Any? =
        (handlers[type] ?: throw IllegalArgumentException("no handler for $type")).invoke(request)
}
