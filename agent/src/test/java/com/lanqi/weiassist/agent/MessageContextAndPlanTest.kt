package com.lanqi.weiassist.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class MessageContextAndPlanTest {
    private val at = ZonedDateTime.of(2026, 11, 10, 13, 5, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test fun `every message tells the model the date, time and zone, after the attachments`() {
        assertEquals("<context>Now: Tuesday, 10 November 2026, 13:05 (Asia/Shanghai, UTC+08:00). Phone language: zh-CN.</context>",
            MessageContext.text(at, Locale.SIMPLIFIED_CHINESE))
        val attached = JSONArray().put(JSONObject().put("type", "image").put("data", "x").put("mimeType", "image/jpeg"))
        val out = MessageContext.append(attached, at)
        assertEquals(2, out.length())
        assertEquals("image", out.getJSONObject(0).getString("type"))
        assertTrue(out.getJSONObject(1).getString("text").startsWith("<context>Now: Tuesday"))
        assertEquals(1, MessageContext.append(null, at).length())
        assertTrue(MessageContext.text(at.withZoneSameInstant(ZoneId.of("UTC"))).contains("05:05 (UTC, UTC+00:00)"))
    }

    @Test fun `a steered message counts as delivered when the core echoes it with the context`() {
        val idle = SessionState(SessionSummary("s1", "", phone = false, status = SessionStatus.Idle, createdAt = 0, updatedAt = 0))
        var s = SessionReducer.steered(SessionReducer.userMessage(idle, "check mail", queued = false, now = 1), "also the inbox", now = 2)
        s = SessionReducer.reduce(s, JSONObject().put("type", "message_end").put("session", "s1").put("role", "user")
            .put("text", "also the inbox" + MessageContext.text(at)), now = 3)
        assertEquals(ChatItem.User("also the inbox"), s.items.last())
        assertEquals("hi", MessageContext.strip("hi\n<context>Now: x</context>"))
    }

    private fun todos(vararg t: Pair<String, String>) = JSONObject().put("todos", JSONArray(t.map { (c, st) -> JSONObject().put("content", c).put("status", st) }))

    @Test fun `todo_write from either core becomes the session's plan, and is saved with it`() {
        val idle = SessionState(SessionSummary("s1", "", phone = false, status = SessionStatus.Running, createdAt = 0, updatedAt = 0))
        val s = SessionReducer.reduce(idle, JSONObject().put("type", "tool_start").put("session", "s1").put("tool", "todo_write")
            .put("args", todos("Find the flight" to "completed", "Book it" to "in_progress", "Tell Ann" to "pending")), now = 2)
        assertEquals(listOf("Find the flight", "Book it", "Tell Ann"), s.summary.plan.map { it.content })
        assertEquals(PlanItem.Status.InProgress, s.summary.plan[1].status)
        assertTrue(Plan.open(s.summary.plan))
        // A subagent's list is its own: not the session's plan.
        val child = SessionReducer.reduce(idle, JSONObject().put("type", "tool_start").put("session", "s1").put("subagent", "c1")
            .put("tool", "todo_write").put("args", todos("x" to "pending")), now = 2)
        assertTrue(child.summary.plan.isEmpty())
        val saved = SessionStore.summaryFrom(SessionStore.summaryJson(s.summary))
        assertEquals(s.summary.plan, saved.plan)
        assertFalse(SessionStore.summaryJson(idle.summary).has("plan"))
    }

    @Test fun `the plan's answer says how far along it is`() {
        val plan = Plan.parse(todos("a" to "completed", "b" to "in_progress", "c" to "pending", "" to "pending", "d" to "weird"))!!
        assertEquals(4, plan.size)
        assertEquals(PlanItem.Status.Pending, plan[3].status)
        assertEquals("Plan updated: 1 of 4 done. Now: b.", Plan.answer(plan))
        assertTrue(Plan.answer(Plan.parse(todos("a" to "in_progress", "b" to "in_progress"))!!).contains("one at a time"))
        assertTrue(Plan.answer(Plan.parse(todos("a" to "completed"))!!).endsWith("Every step is done."))
        assertFalse(Plan.open(Plan.parse(todos("a" to "completed"))!!))
        assertNull(Plan.parse(JSONObject()))
    }

    @Test fun `a server that didn't answer is named with its address`() {
        assertEquals("10.0.0.144:3425", GatewayLink.unreachableAddress("Connection error.: Network error: Failed to connect to /10.0.0.144:3425"))
        assertEquals("127.0.0.1:3425", GatewayLink.unreachableAddress("fetch failed: connect ECONNREFUSED 127.0.0.1:3425"))
        assertEquals("api.example.com:443", GatewayLink.unreachableAddress("Failed to connect to api.example.com:443"))
        assertEquals(null, GatewayLink.unreachableAddress("Connection error."))
        assertTrue(GatewayLink.unreachable("Connection error.: Network error: Failed to connect to /10.0.0.144:3425"))
    }

    @Test fun `every session but the supervisor's is told to ask with choices when it must wait for the user`() {
        val chat = SessionSummary("s1", "", phone = false, status = SessionStatus.Idle, createdAt = 0, updatedAt = 0)
        val tools = ToolCatalog.forSession(chat).map { it.name }
        val p = com.lanqi.weiassist.core.composePrompt(Prompts.forSession(chat, "Ada"), tools)
        assertTrue(p, "ask with ask_user_question instead of ending your turn" in p)
        // A core with its own ask_user_question (dsh) keeps the app's guide only if it keeps the app's tool.
        assertFalse(com.lanqi.weiassist.core.composePrompt(Prompts.forSession(chat, "Ada"), tools - "ask_user_question").contains("ask_user_question"))
    }

    @Test fun `Pi's questions take dsh's shape, and the answers come back as text`() {
        fun opts(vararg labels: String) = JSONArray(labels.map { JSONObject().put("label", it) })
        val q1 = JSONObject().put("question", "Which Ann?").put("header", "Contact").put("options", opts("Ann Lee (Recommended)", "Ann Wu"))
        val q2 = JSONObject().put("question", "Which days?").put("multi_select", true).put("options", opts("Mon", "Fri"))
        val args = JSONObject().put("questions", JSONArray().put(q1).put(q2))
        val ask = Ask.fromTool("call_1", args)!!
        assertEquals("call_1", ask.id)
        assertEquals(listOf("q1", "q2"), ask.questions.map { it.id })
        assertEquals(listOf(false, true), ask.questions.map { it.multi })
        assertEquals("Contact", ask.questions[0].header)
        val text = Ask.answerText(ask, listOf(QuestionAnswer("q1", listOf("Ann Lee (Recommended)")), QuestionAnswer("q2", listOf("Mon"), "and Sat")))
        assertEquals("Q: Which Ann?\nA: Ann Lee (Recommended)\n\nQ: Which days?\nA: Mon, \"and Sat\" (typed)", text)
        assertTrue(Ask.answerText(ask, listOf(QuestionAnswer("q1"))).contains("(skipped: no answer)"))
        assertNull(Ask.fromTool("c", JSONObject()))
        assertNull(Ask.fromTool("c", JSONObject().put("questions", JSONArray().put(JSONObject().put("question", "")))))
    }
}
