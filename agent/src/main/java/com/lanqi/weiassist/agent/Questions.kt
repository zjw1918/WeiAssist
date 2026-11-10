package com.lanqi.weiassist.agent

import org.json.JSONArray
import org.json.JSONObject

/** A choice offered with a question: [label] is also the answer's value. */
data class QuestionOption(val label: String, val description: String = "")

/**
 * What a question is for, when its core says so: a plan to review ("plan-review": [approve] is the option that
 * approves it, the plan is the question's detail), or a permission ("approval": [approve], [reject], and [always]
 * when a lasting grant is offered). Null for a plain question.
 */
data class QuestionIntent(val kind: String, val approve: String, val reject: String = "", val always: String = "")

/** One question the agent asks the user ([id] is echoed in its answer). */
data class Question(
    val id: String,
    val text: String,
    val detail: String = "",
    val header: String = "",
    val options: List<QuestionOption> = emptyList(),
    val multi: Boolean = false,
    val intent: QuestionIntent? = null,
)

/** The user's answer to one question: the labels chosen, and words of their own. Neither = skipped. */
data class QuestionAnswer(val id: String, val selected: List<String> = emptyList(), val custom: String? = null) {
    val skipped get() = selected.isEmpty() && custom.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("selected", JSONArray(selected)).apply {
        if (!custom.isNullOrBlank()) put("custom", custom)
    }
}

/**
 * Questions an agent asks the user at once ([id]: its core's id for them), answered together. DeepSeek Harness asks
 * them with its ask_user_question tool, plan mode's review and its approvals (the ask_user host call).
 */
data class Ask(val id: String, val questions: List<Question>) {
    companion object {
        /** The ask_user host call's request; null when it holds no question. */
        fun parse(m: JSONObject): Ask? {
            val qs = m.optJSONArray("questions") ?: return null
            val questions = (0 until qs.length()).mapNotNull { i ->
                val q = qs.optJSONObject(i) ?: return@mapNotNull null
                val opts = q.optJSONArray("options") ?: JSONArray()
                val intent = q.optJSONObject("intent")?.let {
                    QuestionIntent(it.optString("kind"), it.optString("approve"), it.optString("reject"), it.optString("always"))
                }
                Question(
                    id = q.optString("id"), text = q.optString("question"), detail = q.optString("detail"), header = q.optString("header"),
                    options = (0 until opts.length()).mapNotNull { j ->
                        opts.optJSONObject(j)?.let { o -> QuestionOption(o.optString("label"), o.optString("description")) }
                    }.filter { it.label.isNotEmpty() },
                    multi = q.optBoolean("multiSelect"), intent = intent,
                )
            }
            return if (questions.isEmpty()) null else Ask(m.optString("ask"), questions)
        }

        /**
         * Pi's ask_user_question call ([call]: its id, also the ask's): the same shape as DeepSeek Harness's tool
         * (questions with an optional id, header, options and multi_select). Null when it holds no question.
         */
        fun fromTool(call: String, args: JSONObject): Ask? {
            val qs = args.optJSONArray("questions") ?: return null
            val m = JSONObject().put("ask", call).put("questions", JSONArray().apply {
                for (i in 0 until qs.length()) {
                    val q = qs.optJSONObject(i) ?: continue
                    put(JSONObject(q.toString()).put("id", q.optString("id").ifEmpty { "q${i + 1}" })
                        .put("multiSelect", q.optBoolean("multi_select", q.optBoolean("multiSelect"))))
                }
            })
            return parse(m)?.takeIf { a -> a.questions.all { it.text.isNotBlank() } }
        }

        /** The user's answers as Pi's tool result: each question with what was chosen or typed. */
        fun answerText(ask: Ask, answers: List<QuestionAnswer>): String = ask.questions.joinToString("\n\n") { q ->
            val a = answers.firstOrNull { it.id == q.id }
            val said = when {
                a == null || a.skipped -> "(skipped: no answer)"
                else -> (a.selected + listOfNotNull(a.custom?.takeIf { it.isNotBlank() }?.let { "\"$it\" (typed)" })).joinToString(", ")
            }
            "Q: ${q.text}\nA: $said"
        }

        /** The host call's answer: every question's answer, or why there is none. */
        fun reply(answers: List<QuestionAnswer>?, reason: String): JSONObject =
            if (answers == null) JSONObject().put("reason", reason)
            else JSONObject().put("answers", JSONArray(answers.map { it.toJson() }))
    }
}

/**
 * Answering an [Ask], one question at a time (the chat's card and a Telegram chat both go through it). Pure: each
 * step returns the next draft. A single choice answers its question at once; a multiple choice collects [picked]
 * until [confirm]; typed words answer the current question ([custom]), with the choices picked so far.
 */
data class AnswerDraft(
    val ask: Ask,
    val index: Int = 0,
    val answers: List<QuestionAnswer> = emptyList(),
    /** The choices picked for the current multiple-choice question. */
    val picked: List<String> = emptyList(),
) {
    val current: Question? get() = ask.questions.getOrNull(index)
    val done get() = index >= ask.questions.size

    fun choose(label: String): AnswerDraft {
        val q = current ?: return this
        if (!q.multi) return record(QuestionAnswer(q.id, listOf(label)))
        return copy(picked = if (label in picked) picked - label else picked + label)
    }

    /** A multiple-choice question: what is picked is the answer. */
    fun confirm(): AnswerDraft = current?.let { record(QuestionAnswer(it.id, picked)) } ?: this

    fun custom(text: String): AnswerDraft {
        val q = current ?: return this
        if (text.isBlank()) return this
        return record(QuestionAnswer(q.id, if (q.multi) picked else emptyList(), text.trim()))
    }

    fun skip(): AnswerDraft = current?.let { record(QuestionAnswer(it.id)) } ?: this

    private fun record(a: QuestionAnswer) = copy(index = index + 1, answers = answers + a, picked = emptyList())
}
