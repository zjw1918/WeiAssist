package com.lanqi.weiassist.agent

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sessions on disk, three files each, so nothing is read before it is needed:
 * - `<id>.meta.json`: the [SessionSummary] (all of them are read at start, for the list);
 * - `<id>.chat.json`: what the user sees ([ChatItem]s), read when the session is opened;
 * - `<id>.<core>.json`: the transcript of the core that runs it (`<id>.pi.json` for Pi), in that core's own
 *   format, read only when the core needs the conversation back.
 * Writes are atomic (AtomicFile): a process killed mid-write leaves the previous version. Call from a
 * background thread; [SessionManager] serialises writes per session.
 */
class SessionStore(root: File) {
    private val dir = File(root, "sessions").apply { mkdirs() }
    private val attachmentsRoot = File(root, "attachments")

    /** Where a session's attached files are kept (see Attachments.keep). */
    fun attachmentsDir(id: String) = File(attachmentsRoot, id)

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

    fun loadTranscript(id: String, core: String): JSONArray? = read(file(id, core))?.let { runCatching { JSONArray(it) }.getOrNull() }
    fun saveTranscript(id: String, core: String, messages: JSONArray) = write(file(id, core), messages.toString())

    fun delete(id: String) {
        // Every file of the session, whichever core wrote its transcript.
        dir.listFiles { f -> f.name.startsWith("$id.") }.orEmpty().forEach { it.delete() }
        attachmentsDir(id).deleteRecursively()
    }

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
            .also { if (s.channel != null) it.put("channel", s.channel) }
            .also { if (!s.termux) it.put("termux", false) }
            .put("core", s.core)

        fun summaryFrom(o: JSONObject) = SessionSummary(
            id = o.getString("id"), title = o.optString("title"), phone = o.optBoolean("phone"),
            status = runCatching { SessionStatus.valueOf(o.getString("status")) }.getOrDefault(SessionStatus.Idle),
            createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"), preview = o.optString("preview"),
            error = o.optBoolean("error"), app = o.optString("app").takeIf { it.isNotEmpty() && it != "null" },
            supervisor = o.optBoolean("supervisor"), channel = o.optString("channel").ifEmpty { null },
            termux = o.optBoolean("termux", true),
            core = o.optString("core").ifEmpty { SessionSummary.DEFAULT_CORE },
        )

        fun itemJson(i: ChatItem): JSONObject = when (i) {
            is ChatItem.User -> JSONObject().put("k", "user").put("text", i.text)
                .also { if (i.delivery != Delivery.Sent) it.put("delivery", i.delivery.name) }
                .also { if (i.attachments.isNotEmpty()) it.put("attachments", JSONArray(i.attachments.map(Attachment::toJson))) }
            is ChatItem.Assistant -> JSONObject().put("k", "assistant").put("text", i.text)
            is ChatItem.Error -> JSONObject().put("k", "error").put("text", i.text)
            is ChatItem.File -> JSONObject().put("k", "file").put("file", i.file.toJson()).also { if (i.caption.isNotEmpty()) it.put("caption", i.caption) }
            is ChatItem.Step -> JSONObject().put("k", "step").put("tool", i.tool).put("detail", i.detail)
                .put("ok", i.ok ?: JSONObject.NULL).put("result", i.result).also { if (i.screen > 0) it.put("screen", i.screen) }
            is ChatItem.Thinking -> JSONObject().put("k", "thinking").put("text", i.text).put("ms", i.ms)
            is ChatItem.Stats -> i.run.let { r ->
                JSONObject().put("k", "stats").put("startedAt", r.startedAt).put("ms", r.ms).put("thinkingMs", r.thinkingMs)
                    .put("steps", r.steps).put("calls", r.calls).put("input", r.input).put("output", r.output)
                    .put("cacheRead", r.cacheRead).put("cacheWrite", r.cacheWrite).put("reasoning", r.reasoning)
                    .put("cost", r.cost).put("model", r.model)
            }
        }

        fun itemFrom(o: JSONObject): ChatItem? = when (o.optString("k")) {
            // Still queued when saved: the process ended before Pi took it.
            "user" -> ChatItem.User(o.getString("text"), when (o.optString("delivery")) {
                "Queued", "NotDelivered" -> Delivery.NotDelivered
                else -> Delivery.Sent
            }, Attachment.listFrom(o.optJSONArray("attachments")))
            "assistant" -> ChatItem.Assistant(o.getString("text"))
            "error" -> ChatItem.Error(o.getString("text"))
            "file" -> ChatItem.File(Attachment.fromJson(o.getJSONObject("file")), o.optString("caption"))
            "step" -> ChatItem.Step(o.getString("tool"), o.optString("detail"), if (o.isNull("ok")) null else o.getBoolean("ok"),
                // Only the first line is shown (a few builds kept whole screens here).
                o.optString("result").lineSequence().first().take(80), o.optInt("screen"))
            "thinking" -> ChatItem.Thinking(o.optString("text"), o.optLong("ms"))
            "stats" -> ChatItem.Stats(RunStats(
                startedAt = o.optLong("startedAt"), ms = o.optLong("ms"), thinkingMs = o.optLong("thinkingMs"),
                steps = o.optInt("steps"), calls = o.optInt("calls"), input = o.optLong("input"), output = o.optLong("output"),
                cacheRead = o.optLong("cacheRead"), cacheWrite = o.optLong("cacheWrite"), reasoning = o.optLong("reasoning"),
                cost = o.optDouble("cost", 0.0), model = o.optString("model"),
            ))
            else -> null
        }
    }
}
