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

    @Test fun `an action's step keeps the first line and the size of the screen it returned`() {
        var s = reduce(running, msg("tool_start", "tool" to "tap", "args" to JSONObject().put("id", 3)))
        val output = "tapped [3]\n\nScreen now:\napp: com.google.android.gm  screen: 720x1600\n[1] ScrollView @360,800"
        s = reduce(s, msg("tool_end", "tool" to "tap", "isError" to false, "output" to output, "screen" to 2))
        val step = s.items.last() as ChatItem.Step
        assertEquals("tapped [3]", step.result)
        assertEquals(2, step.screen)
        assertEquals("com.google.android.gm", s.summary.app)
        assertEquals(step, SessionStore.itemFrom(SessionStore.itemJson(step)))
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

    @Test fun `a session keeps its core, and one saved before cores existed runs on Pi`() {
        val s = SessionSummary("a1", "t", phone = false, status = SessionStatus.Idle, createdAt = 1, updatedAt = 2, core = "dsh")
        assertEquals("dsh", SessionStore.summaryFrom(SessionStore.summaryJson(s)).core)
        val old = SessionStore.summaryJson(s).apply { remove("core") }
        assertEquals("pi", SessionStore.summaryFrom(old).core)
    }

    @Test fun `a session's files go, whichever core wrote its transcript`() {
        val root = java.nio.file.Files.createTempDirectory("store").toFile()
        try {
            val store = SessionStore(root)
            // Plain files: AtomicFile (the store's writer) is a stub in JVM tests. Its backup file counts too.
            val dir = java.io.File(root, "sessions")
            val mine = listOf("a1.meta.json", "a1.chat.json", "a1.pi.json", "a1.dsh.json", "a1.pi.json.bak").map { java.io.File(dir, it) }
            val other = java.io.File(dir, "a10.pi.json")
            (mine + other).forEach { it.writeText("[]") }
            store.delete("a1")
            mine.forEach { assertFalse(it.name, it.exists()) }
            // A session whose id starts with the deleted one's is untouched.
            assertTrue(other.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun `a message while working is queued, then delivered when Pi takes it`() {
        var s = SessionReducer.steered(running, "also the inbox", now = 2)
        assertEquals(ChatItem.User("also the inbox", Delivery.Queued), s.items.last())
        assertEquals("check mail", s.pendingTask) // the task is unchanged: Pi gets the message as steering
        assertEquals(SessionStatus.Running, s.status)
        s = reduce(s, msg("message_end", "role" to "user", "text" to "check mail")) // the task's own message: no change
        assertEquals(Delivery.Queued, (s.items.last() as ChatItem.User).delivery)
        s = reduce(s, msg("message_end", "role" to "user", "text" to "also the inbox"))
        assertEquals(ChatItem.User("also the inbox"), s.items.last())
    }

    @Test fun `stopping or failing marks queued messages as not delivered`() {
        val queued = SessionReducer.steered(running, "wait", now = 2)
        assertEquals(Delivery.NotDelivered, (SessionReducer.stopped(queued, now = 3).items.last() as ChatItem.User).delivery)
        assertEquals(Delivery.NotDelivered, (reduce(queued, msg("error", "message" to "boom")).items.first { it is ChatItem.User && it.text == "wait" } as ChatItem.User).delivery)
        assertEquals(Delivery.NotDelivered, (SessionReducer.runtimeLost(queued, "restarted", now = 3).items.first { it is ChatItem.User && it.text == "wait" } as ChatItem.User).delivery)
    }

    @Test fun `a message for a task Pi hasn't started joins the task`() {
        val waiting = SessionReducer.userMessage(idle, "check mail", queued = true, now = 1)
        val s = SessionReducer.joinTask(waiting, "and reply to Ann", now = 2)
        assertEquals("check mail\n\nand reply to Ann", s.pendingTask)
        assertEquals(ChatItem.User("and reply to Ann"), s.items.last())
    }

    @Test fun `a steer that arrived after the run ended starts the next task`() {
        val ended = reduce(SessionReducer.steered(running, "one more", now = 2), msg("idle"))
        assertEquals(Delivery.Queued, (ended.items.last() as ChatItem.User).delivery) // idle doesn't settle it: steer_late follows
        val s = SessionReducer.lateSteer(ended, "one more", queued = false, now = 3)
        assertEquals(SessionStatus.Running, s.status)
        assertEquals("one more", s.pendingTask)
        assertEquals(ChatItem.User("one more"), s.items.last())
    }

    @Test fun `delivery survives the storage format, and a still-queued message was not delivered`() {
        assertEquals(ChatItem.User("x", Delivery.NotDelivered), SessionStore.itemFrom(SessionStore.itemJson(ChatItem.User("x", Delivery.NotDelivered))))
        assertEquals(ChatItem.User("x", Delivery.NotDelivered), SessionStore.itemFrom(SessionStore.itemJson(ChatItem.User("x", Delivery.Queued))))
        assertEquals(ChatItem.User("x"), SessionStore.itemFrom(JSONObject().put("k", "user").put("text", "x"))) // files from before
    }

    @Test fun `an error from the aborted request after Stop is not shown`() {
        val stopped = SessionReducer.stopped(running, now = 2)
        val s = reduce(stopped, msg("assistant_end", "text" to "", "stopReason" to "error", "errorMessage" to "AbortError: This operation was aborted"))
        assertTrue(s.items.none { it is ChatItem.Error })
        assertNull(s.error)
    }

    @Test fun `reasoning streams live, then becomes a timed item before the answer`() {
        var s = SessionReducer.reduce(running, msg("thinking_start"), now = 1_000)
        s = SessionReducer.reduce(s, msg("thinking_delta", "delta" to "Mail first"), now = 1_500)
        assertEquals("Mail first", s.thinking)
        assertEquals(1_000L, s.thinkingSince)
        s = SessionReducer.reduce(s, msg("thinking_end", "text" to "Mail first, then reply."), now = 4_000)
        assertEquals("", s.thinking)
        assertNull(s.thinkingSince)
        assertEquals(ChatItem.Thinking("Mail first, then reply.", 3_000), s.items.last())
        assertEquals(3_000L, s.run?.thinkingMs)
    }

    @Test fun `text after reasoning ends it even when its end is missing`() {
        var s = SessionReducer.reduce(running, msg("thinking_start"), now = 1_000)
        s = SessionReducer.reduce(s, msg("thinking_delta", "delta" to "hmm"), now = 1_200)
        s = SessionReducer.reduce(s, msg("text_delta", "delta" to "Hi"), now = 2_000)
        assertEquals(ChatItem.Thinking("hmm", 1_000), s.items.last())
        assertEquals("Hi", s.streaming)
    }

    @Test fun `a run counts its steps and tokens, and ends as a stats item`() {
        val started = SessionReducer.userMessage(idle, "go", queued = false, now = 10_000)
        val usage = JSONObject().put("input", 1000).put("output", 50).put("cacheRead", 400).put("cacheWrite", 0).put("reasoning", 20)
            .put("cost", JSONObject().put("total", 0.01))
        var s = SessionReducer.reduce(started, msg("assistant_end", "text" to "", "stopReason" to "toolUse", "usage" to usage, "provider" to "p", "model" to "m"), now = 11_000)
        s = SessionReducer.reduce(s, msg("tool_start", "tool" to "tap", "args" to JSONObject().put("id", 1)), now = 11_000)
        s = SessionReducer.reduce(s, msg("tool_end", "tool" to "tap", "isError" to false, "output" to "ok"), now = 12_000)
        s = SessionReducer.reduce(s, msg("assistant_end", "text" to "Done", "stopReason" to "stop", "usage" to usage, "provider" to "p", "model" to "m"), now = 13_000)
        s = SessionReducer.reduce(s, msg("idle"), now = 18_000)
        assertNull(s.run)
        val run = (s.items.last() as ChatItem.Stats).run
        assertEquals(8_000L, run.ms)
        assertEquals(1, run.steps)
        assertEquals(2, run.calls)
        assertEquals(2_000L, run.input)
        assertEquals(800L, run.cacheRead)
        assertEquals(2_900L, run.tokens)
        assertEquals(40L, run.reasoning)
        assertEquals(0.02, run.cost, 1e-9)
        assertEquals("p/m", run.model)
        assertEquals(run, (SessionStore.itemFrom(SessionStore.itemJson(ChatItem.Stats(run))) as ChatItem.Stats).run)
        val t = ChatItem.Thinking("why", 1234)
        assertEquals(t, SessionStore.itemFrom(SessionStore.itemJson(t)))
    }

    @Test fun `a queued run starts its clock when it gets the screen, and a stop still reports it`() {
        val queued = SessionReducer.userMessage(idle, "go", queued = true, now = 1_000)
        val s = SessionReducer.withStatus(queued, SessionStatus.Running, now = 5_000)
        assertEquals(5_000L, s.run?.startedAt)
        val stepped = SessionReducer.reduce(s, msg("tool_start", "tool" to "read_screen", "args" to JSONObject()), now = 6_000)
        val stopped = SessionReducer.stopped(stepped, now = 9_000)
        assertEquals(4_000L, (stopped.items.last() as ChatItem.Stats).run.ms)
        // Nothing happened yet: no stats.
        assertTrue(SessionReducer.stopped(s, now = 6_000).items.none { it is ChatItem.Stats })
    }
}
