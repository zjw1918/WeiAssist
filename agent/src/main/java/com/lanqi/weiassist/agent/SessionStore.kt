package com.lanqi.weiassist.agent

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sessions on disk, three files each, so nothing is read before it is needed:
 * - `<id>.meta.json`: the [SessionSummary] (all of them are read at start, for the list);
 * - `<id>.chat.json`: what the user sees ([ChatItem]s), read when the session is opened;
 * - `<id>.pi.json`: Pi's transcript, read only when Pi needs the conversation back.
 * Writes are atomic (AtomicFile): a process killed mid-write leaves the previous version. Call from a
 * background thread; [SessionManager] serialises writes per session.
 */
class SessionStore(root: File) {
    private val dir = File(root, "sessions").apply { mkdirs() }

    private fun file(id: String, kind: String) = AtomicFile(File(dir, "$id.$kind.json"))

    fun loadSummaries(): List<SessionSummary> = dir.listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty()
        .mapNotNull { f -> runCatching { summaryFrom(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun saveSummary(s: SessionSummary) = write(file(s.id, "meta"), summaryJson(s).toString())

    fun loadItems(id: String): List<ChatItem> = read(file(id, "chat"))?.let { JSONArray(it) }?.let { a ->
        (0 until a.length()).mapNotNull { itemFrom(a.getJSONObject(it)) }
    }.orEmpty()

    fun saveItems(id: String, items: List<ChatItem>) =
        write(file(id, "chat"), JSONArray().apply { items.forEach { put(itemJson(it)) } }.toString())

    fun loadTranscript(id: String): JSONArray? = read(file(id, "pi"))?.let { runCatching { JSONArray(it) }.getOrNull() }
    fun saveTranscript(id: String, messages: JSONArray) = write(file(id, "pi"), messages.toString())

    fun delete(id: String) = listOf("meta", "chat", "pi").forEach { file(id, it).delete() }

    private fun read(f: AtomicFile): String? = runCatching { String(f.readFully()) }.getOrNull()

    private fun write(f: AtomicFile, text: String) {
        val out = f.startWrite()
        try {
            out.write(text.toByteArray())
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    companion object {
        fun summaryJson(s: SessionSummary): JSONObject = JSONObject()
            .put("id", s.id).put("title", s.title).put("phone", s.phone).put("status", s.status.name)
            .put("createdAt", s.createdAt).put("updatedAt", s.updatedAt).put("preview", s.preview)
            .put("error", s.error).put("app", s.app ?: JSONObject.NULL).put("supervisor", s.supervisor)

        fun summaryFrom(o: JSONObject) = SessionSummary(
            id = o.getString("id"), title = o.optString("title"), phone = o.optBoolean("phone"),
            status = runCatching { SessionStatus.valueOf(o.getString("status")) }.getOrDefault(SessionStatus.Idle),
            createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"), preview = o.optString("preview"),
            error = o.optBoolean("error"), app = o.optString("app").takeIf { it.isNotEmpty() && it != "null" },
            supervisor = o.optBoolean("supervisor"),
        )

        fun itemJson(i: ChatItem): JSONObject = when (i) {
            is ChatItem.User -> JSONObject().put("k", "user").put("text", i.text)
            is ChatItem.Assistant -> JSONObject().put("k", "assistant").put("text", i.text)
            is ChatItem.Error -> JSONObject().put("k", "error").put("text", i.text)
            is ChatItem.Step -> JSONObject().put("k", "step").put("tool", i.tool).put("detail", i.detail)
                .put("ok", i.ok ?: JSONObject.NULL).put("result", i.result)
        }

        fun itemFrom(o: JSONObject): ChatItem? = when (o.optString("k")) {
            "user" -> ChatItem.User(o.getString("text"))
            "assistant" -> ChatItem.Assistant(o.getString("text"))
            "error" -> ChatItem.Error(o.getString("text"))
            "step" -> ChatItem.Step(o.getString("tool"), o.optString("detail"), if (o.isNull("ok")) null else o.getBoolean("ok"), o.optString("result"))
            else -> null
        }
    }
}
