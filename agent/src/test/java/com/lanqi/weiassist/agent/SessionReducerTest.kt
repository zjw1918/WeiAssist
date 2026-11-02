package com.lanqi.weiassist.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReducerTest {
    private val idle = SessionState(SessionSummary("s1", "", phone = true, status = SessionStatus.Idle, createdAt = 0, updatedAt = 0))
    private val running = SessionReducer.userMessage(idle, "check mail", queued = false, now = 1)

    private fun msg(type: String, vararg pairs: Pair<String, Any?>) = JSONObject().put("type", type).put("session", "s1").apply {
        pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
    }

    private fun reduce(s: SessionState, m: JSONObject) = SessionReducer.reduce(s, m, now = 5)

    @Test fun `a message starts a task, or queues it when the screen is taken`() {
        assertEquals(SessionStatus.Running, running.status)
        assertEquals(listOf<ChatItem>(ChatItem.User("check mail")), running.items)
        assertEquals("check mail", running.pendingTask)
        assertEquals("check mail", running.summary.preview)
        assertEquals(SessionStatus.Queued, SessionReducer.userMessage(idle, "x", queued = true, now = 1).status)
    }

    @Test fun `text deltas stream, and the finished answer becomes a chat item`() {
        var s = running
        listOf("你", "好", "！").forEach { s = reduce(s, msg("text_delta", "delta" to it)) }
        assertEquals("你好！", s.streaming)
        s = reduce(s, msg("assistant_end", "text" to "你好！", "stopReason" to "stop"))
        assertEquals("", s.streaming)
        assertEquals(ChatItem.Assistant("你好！"), s.items.last())
        assertEquals("你好！", s.summary.preview)
        assertNull(s.error)
    }

    @Test fun `a tool call is a step that gets its result`() {
        var s = reduce(running, msg("tool_start", "tool" to "open_app", "args" to JSONObject().put("app", "Gmail")))
        assertEquals(ChatItem.Step("open_app", "Gmail"), s.items.last())
        assertEquals("Gmail", s.summary.app)
        s = reduce(s, msg("tool_end", "tool" to "open_app", "isError" to false, "output" to "opened Gmail (com.google.android.gm)"))
        val step = s.items.last() as ChatItem.Step
        assertEquals(true, step.ok)
        assertTrue(step.result.startsWith("opened Gmail"))
    }

    @Test fun `the app being operated is learned from read_screen`() {
        var s = reduce(running, msg("tool_start", "tool" to "read_screen", "args" to JSONObject()))
        s = reduce(s, msg("tool_end", "tool" to "read_screen", "isError" to false, "output" to "app: com.taobao.taobao  screen: 1080x2400"))
        assertEquals("com.taobao.taobao", s.summary.app)
    }

    @Test fun `errors end the task, are made friendly and shown`() {
        val s = SessionReducer.reduce(running, msg("error", "message" to "401"), now = 5, friendly = { "friendly:$it" })
        assertFalse(s.busy)
        assertEquals("friendly:401", s.error)
        assertEquals(ChatItem.Error("friendly:401"), s.items.last())
        assertTrue(s.summary.error)
    }

    @Test fun `an assistant error is reported, a normal stop is not`() {
        assertNull(reduce(running, msg("assistant_end", "stopReason" to "stop")).error)
        assertEquals("model_not_found", reduce(running, msg("assistant_end", "stopReason" to "error", "errorMessage" to "model_not_found")).error)
    }

    @Test fun `idle ends the task`() {
        val s = reduce(running, msg("idle"))
        assertEquals(SessionStatus.Idle, s.status)
        assertNull(s.pendingTask)
        assertEquals(5, s.summary.updatedAt)
    }

    @Test fun `a runtime restart ends a running task and says so, an idle session is untouched`() {
        val r = SessionReducer.runtimeLost(running, "restarted", now = 5)
        assertFalse(r.busy)
        assertEquals(ChatItem.Error("restarted"), r.items.last())
        assertEquals(idle.items, SessionReducer.runtimeLost(idle, "restarted", now = 5).items)
    }

    @Test fun `stopping ends the task`() {
        val s = SessionReducer.stopped(running, now = 5)
        assertEquals(SessionStatus.Idle, s.status)
        assertEquals("stopped by user", s.trace.last())
    }

    @Test fun `the trace and the chat keep only the latest entries`() {
        var s = running
        repeat(SessionReducer.MAX_TRACE + 50) { s = reduce(s, msg("turn_start")) }
        assertEquals(SessionReducer.MAX_TRACE, s.trace.size)
        repeat(SessionReducer.MAX_ITEMS + 10) { s = reduce(s, msg("tool_start", "tool" to "wait", "args" to JSONObject().put("ms", 10))) }
        assertEquals(SessionReducer.MAX_ITEMS, s.items.size)
    }

    @Test fun `only busy statuses keep the service working`() {
        assertTrue(SessionStatus.Running.active && SessionStatus.Queued.active && SessionStatus.Paused.active)
        assertFalse(SessionStatus.Idle.active || SessionStatus.Interrupted.active)
    }

    @Test fun `summaries and chat items survive the storage format`() {
        val s = SessionSummary("a1", "比价", phone = true, status = SessionStatus.Paused, createdAt = 1, updatedAt = 2, preview = "p", error = true, app = "com.jd")
        assertEquals(s, SessionStore.summaryFrom(SessionStore.summaryJson(s)))
        assertEquals(null, SessionStore.summaryFrom(SessionStore.summaryJson(s.copy(app = null))).app)
        listOf(ChatItem.User("u"), ChatItem.Assistant("a"), ChatItem.Error("e"), ChatItem.Step("tap", "[3]", null), ChatItem.Step("tap", "[3]", false, "no"))
            .forEach { assertEquals(it, SessionStore.itemFrom(SessionStore.itemJson(it))) }
    }
}
