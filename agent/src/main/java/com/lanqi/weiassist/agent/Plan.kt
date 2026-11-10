package com.lanqi.weiassist.agent

import org.json.JSONArray
import org.json.JSONObject

/** One step of the agent's plan (its todo_write list). */
data class PlanItem(val content: String, val status: Status, val activeForm: String = "") {
    enum class Status(val id: String) {
        Pending("pending"), InProgress("in_progress"), Completed("completed");
        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
    }

    fun toJson(): JSONObject = JSONObject().put("content", content).put("status", status.id)
        .apply { if (activeForm.isNotEmpty()) put("activeForm", activeForm) }
}

/**
 * The agent's plan: the list it keeps with todo_write (Pi's catalog tool, or DeepSeek Harness's own), each call the
 * whole list. Kept with the session and shown above the message box while steps remain.
 */
object Plan {
    /** A todo_write call's list; null when it isn't one (a malformed call leaves the plan as it was). */
    fun parse(args: JSONObject?): List<PlanItem>? {
        val a = args?.optJSONArray("todos") ?: return null
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val content = o.optString("content").trim().ifEmpty { return@mapNotNull null }
            PlanItem(content, PlanItem.Status.of(o.optString("status")) ?: PlanItem.Status.Pending, o.optString("activeForm").trim())
        }
    }

    fun toJson(items: List<PlanItem>) = JSONArray(items.map { it.toJson() })
    fun fromJson(a: JSONArray?): List<PlanItem> = parse(JSONObject().put("todos", a ?: JSONArray())).orEmpty()

    /** Whether there is work left to show. */
    fun open(items: List<PlanItem>) = items.any { it.status != PlanItem.Status.Completed }

    /** What Pi's todo_write answers: how far along the plan is, and what is next. */
    fun answer(items: List<PlanItem>): String {
        if (items.isEmpty()) return "The plan is cleared."
        val done = items.count { it.status == PlanItem.Status.Completed }
        val now = items.firstOrNull { it.status == PlanItem.Status.InProgress }
        val busy = items.count { it.status == PlanItem.Status.InProgress }
        return buildString {
            append("Plan updated: $done of ${items.size} done.")
            if (now != null) append(" Now: ${now.content}.")
            if (busy > 1) append(" Note: $busy steps are in progress; keep one at a time.")
            if (done == items.size) append(" Every step is done.")
        }
    }
}
