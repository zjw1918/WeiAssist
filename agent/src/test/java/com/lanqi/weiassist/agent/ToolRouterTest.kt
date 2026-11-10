package com.lanqi.weiassist.agent

import com.lanqi.weiassist.core.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRouterTest {
    private fun session(id: String = "s", phone: Boolean = false, supervisor: Boolean = false, termux: Boolean = true, channel: String? = null) =
        SessionSummary(id, "t", phone, SessionStatus.Running, 0, 0, supervisor = supervisor, channel = channel, termux = termux)

    private fun names(s: SessionSummary) = ToolCatalog.forSession(s).map { it.name }

    @Test fun `the catalog loads, with unique names and object schemas`() {
        val all = ToolCatalog.entries
        assertEquals(34, all.size)
        all.forEach { e ->
            assertEquals(e.spec.name, "object", JSONObject(e.spec.parameters).getString("type"))
            assertTrue(e.spec.name, e.spec.description.isNotBlank())
        }
        assertEquals(setOf("phone", "bash", "send_file", "web", "files", "ask", "plan", "supervisor"), all.map { it.set }.toSet())
        assertTrue("supervisor tools and fetches may run side by side", all.filter { it.set == "supervisor" || it.set == "web" }.all { it.spec.parallel })
        // Reading files may run side by side; writing may not.
        assertEquals(setOf("read", "ls", "glob", "grep"), all.filter { it.set == "files" && it.spec.parallel }.map { it.spec.name }.toSet())
        assertTrue(all.filter { it.set !in setOf("supervisor", "web", "files") }.none { it.spec.parallel })
        assertEquals(setOf("ask_user_question"), all.filter { it.set == "ask" }.map { it.spec.name }.toSet())
        assertEquals(setOf("todo_write"), all.filter { it.set == "plan" }.map { it.spec.name }.toSet())
        assertEquals(FileTools.NAMES, all.filter { it.set == "files" }.map { it.spec.name }.toSet())
    }

    @Test fun `each kind of session gets its own tools`() {
        // send_file with Termux: to the messaging app's chat, or the app's.
        // web_fetch in every session but the supervisor's: it needs no Termux.
        assertEquals(listOf("bash", "web_fetch", "read", "write", "edit", "ls", "glob", "grep", "ask_user_question", "todo_write", "send_file"), names(session()))
        assertEquals(listOf("web_fetch", "read", "write", "edit", "ls", "glob", "grep", "ask_user_question", "todo_write"), names(session(termux = false)))
        assertTrue(names(session(phone = true)).containsAll(listOf("open_app", "read_screen", "tap", "drag", "screenshot", "note", "bash", "web_fetch")))
        assertEquals(listOf("bash", "web_fetch", "read", "write", "edit", "ls", "glob", "grep", "ask_user_question", "todo_write", "send_file"), names(session(channel = "telegram")))
        assertEquals(listOf("web_fetch", "read", "write", "edit", "ls", "glob", "grep", "ask_user_question", "todo_write"), names(session(channel = "telegram", termux = false)))
        // File access off: no file tools.
        ToolCatalog.files = { false }
        try { assertEquals(listOf("web_fetch", "ask_user_question", "todo_write"), names(session(termux = false))) } finally { ToolCatalog.files = { true } }
        val sup = names(session(supervisor = true, phone = true, channel = "telegram"))
        assertEquals(12, sup.size)
        assertTrue(sup.all { it.endsWith("_session") || it == "message_session" || it == "wait_for" || it == "list_sessions" || it == "ask_user_question" || it == "todo_write" })
    }

    private class Fakes(val summary: SessionSummary, var open: Boolean = true) {
        val phone = mutableListOf<Pair<String, JSONObject>>()
        val bash = mutableListOf<Triple<String, String, Int>>()
        val kills = mutableListOf<String>()
        val supervisor = mutableListOf<Pair<String, JSONObject>>()
        var phoneAnswer = "did it"
        var bashDone: CompletableDeferred<String>? = null
        val detached = mutableListOf<Triple<String, String, Int>>()
        var sendOk = true
        val fetched = mutableListOf<String>()
        var webDone: CompletableDeferred<ToolResult>? = null
        val asked = mutableListOf<String>()
        val withdrawn = mutableListOf<String>()
        var askDone: CompletableDeferred<ToolResult>? = null
        val router = ToolRouter(
            summary = { if (it == summary.id) summary else null },
            gate = { open },
            phone = { _, tool, args -> phone += tool to args; phoneAnswer },
            bash = { _, call, command, timeout -> bash += Triple(call, command, timeout); bashDone?.await() ?: "exit code 0" },
            bashKill = { _, call -> kills += call },
            bashPeek = { _, call, timeout -> "$call within $timeout" },
            bashDetached = { _, call, command, timeout -> detached += Triple(call, command, timeout) },
            sendFile = { _, path, caption -> sendOk to (if (sendOk) "sent $path ($caption)" else "No such file") },
            supervisor = { _, action, args -> supervisor += action to args; "ok $action" },
            web = { a -> fetched += a.getString("url"); webDone?.await() ?: ToolResult("page text") },
            ask = { _, call, _ -> asked += call; askDone?.await() ?: ToolResult("Q: x\nA: y") },
            askCancel = { _, call -> withdrawn += call; askDone?.complete(ToolResult("withdrawn", isError = true)) },
        )
    }

    private fun args(vararg p: Pair<String, Any>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }

    @Test fun `a session can only call its own tools`() = runBlocking {
        val chat = Fakes(session())
        val r = chat.router.call("s", "c1", "tap", args("id" to 1))
        assertTrue(r.isError)
        assertTrue(chat.phone.isEmpty())
        assertTrue(chat.router.call("s", "c1", "create_session", args("task" to "x", "phone" to false)).isError)
        assertTrue(chat.router.call("gone", "c1", "bash", args("command" to "ls")).isError)
        assertTrue(chat.router.call("s", "c1", "no_such_tool", JSONObject()).isError)
    }

    @Test fun `phone tools go to the phone, a note doesn't, and a screenshot comes back as an image`() = runBlocking {
        val f = Fakes(session(phone = true))
        assertEquals("did it", f.router.call("s", "c1", "tap", args("id" to 3)).text)
        assertEquals("noted", f.router.call("s", "c2", "note", args("text" to "Ann, Friday")).text)
        assertEquals(listOf("tap"), f.phone.map { it.first })
        assertEquals(3, f.phone[0].second.getInt("id"))
        // The phone's own screen, scaled down: the caption gives the factor for tap_at.
        f.phoneAnswer = com.lanqi.weiassist.phone.Shot("/9j/AAAA", 1080, 2400, 720, 1600).pack()
        val shot = f.router.call("s", "c3", "screenshot", JSONObject())
        assertEquals("/9j/AAAA", shot.image)
        assertEquals("Screenshot scaled to 720x1600 px; the screen is 1080x2400. For tap_at and drag, multiply x and y by 1.5.", shot.text)
        // A 720 px virtual display isn't scaled: its pixels are tap_at's (a fixed factor sent taps off this screen).
        f.phoneAnswer = com.lanqi.weiassist.phone.Shot("/9j/BBBB", 720, 1600, 720, 1600).pack()
        assertEquals("Screenshot of the whole screen, 720x1600 px: tap_at and drag take these pixels as they are.", f.router.call("s", "c5", "screenshot", JSONObject()).text)
        // A message instead of a picture stays text: as an image the provider would reject the request.
        f.phoneAnswer = "no app open yet"
        assertNull(f.router.call("s", "c4", "screenshot", JSONObject()).image)
        val json = ToolRouter.screenshot(com.lanqi.weiassist.phone.Shot("/9j/x", 1440, 3200, 720, 1600).pack()).toJson()
        assertEquals("image/jpeg", json.getJSONObject("image").getString("mimeType"))
        assertFalse(json.has("isError"))
    }

    @Test fun `bash gets its timeout clamped, doesn't start once stopped, and only a running command is cancelled`() = runBlocking {
        val f = Fakes(session())
        f.router.call("s", "c1", "bash", args("command" to "ls"))
        f.router.call("s", "c2", "bash", args("command" to "ls", "timeout" to 5000))
        f.router.call("s", "c3", "bash", args("command" to "ls", "timeout" to 0))
        assertEquals(listOf(120, 600, 1), f.bash.map { it.third })
        f.router.cancel("s", "c1") // finished: nothing to kill
        assertTrue(f.kills.isEmpty())
        f.bashDone = CompletableDeferred()
        val running = async { f.router.call("s", "c4", "bash", args("command" to "sleep 60")) }
        repeat(5) { yield() }
        f.router.cancel("s", "c4")
        assertEquals(listOf("c4"), f.kills)
        f.bashDone!!.complete("killed")
        assertEquals("killed", running.await().text)
        f.open = false
        assertEquals("stopped by the user", f.router.call("s", "c5", "bash", args("command" to "ls")).text)
        assertEquals(4, f.bash.size)
    }

    @Test fun `web_fetch works without Termux, doesn't start once stopped, and Stop ends a running fetch`() = runBlocking {
        val f = Fakes(session(termux = false))
        assertEquals("page text", f.router.call("s", "c1", "web_fetch", args("url" to "https://example.org")).text)
        f.webDone = CompletableDeferred()
        val running = async { f.router.call("s", "c2", "web_fetch", args("url" to "https://slow.example.org")) }
        repeat(5) { yield() }
        f.router.cancel("s", "c2")
        assertEquals("stopped by the user", running.await().text)
        f.open = false
        assertEquals("stopped by the user", f.router.call("s", "c3", "web_fetch", args("url" to "https://example.org")).text)
        assertEquals(listOf("https://example.org", "https://slow.example.org"), f.fetched)
        assertTrue("the supervisor has no web_fetch", Fakes(session(supervisor = true)).router.call("s", "c4", "web_fetch", args("url" to "https://example.org")).isError)
    }

    @Test fun `questions go to the user and Stop withdraws them, and the plan answers how far along it is`() = runBlocking {
        val f = Fakes(session(termux = false))
        assertEquals("Q: x\nA: y", f.router.call("s", "c1", "ask_user_question", args("questions" to JSONArray())).text)
        f.askDone = CompletableDeferred()
        val waiting = async { f.router.call("s", "c2", "ask_user_question", args("questions" to JSONArray())) }
        repeat(5) { yield() }
        f.router.cancel("s", "c2")
        assertEquals("withdrawn", waiting.await().text)
        assertEquals(listOf("c2"), f.withdrawn)
        f.router.cancel("s", "c1") // answered: nothing to withdraw
        assertEquals(listOf("c2"), f.withdrawn)
        val plan = f.router.call("s", "c3", "todo_write", JSONObject().put("todos", JSONArray().put(JSONObject().put("content", "Book it").put("status", "in_progress"))))
        assertEquals("Plan updated: 0 of 1 done. Now: Book it.", plan.text)
        assertTrue(f.router.call("s", "c4", "todo_write", JSONObject()).isError)
    }

    @Test fun `send_file reports a file that didn't go as a failed step`() = runBlocking {
        val f = Fakes(session(channel = "telegram"))
        val ok = f.router.call("s", "c1", "send_file", args("path" to "chart.png", "caption" to "The chart"))
        assertEquals("sent chart.png (The chart)", ok.text)
        assertFalse(ok.isError)
        f.sendOk = false
        assertTrue(f.router.call("s", "c2", "send_file", args("path" to "x")).isError)
    }

    @Test fun `supervisor tools become SessionCommands' actions, with their arguments shaped`() = runBlocking {
        val f = Fakes(session(supervisor = true))
        f.router.call("s", "c1", "create_session", args("task" to "Say hi", "phone" to false))
        f.router.call("s", "c2", "wait_for", args("ids" to JSONArray(listOf("a")), "timeout_seconds" to 9999))
        f.router.call("s", "c3", "wait_for", args("ids" to JSONArray(listOf("a"))))
        f.router.call("s", "c4", "rename_session", args("id" to "a", "title" to "New"))
        assertEquals(listOf("create", "wait", "wait", "rename"), f.supervisor.map { it.first })
        assertEquals("", f.supervisor[0].second.getString("title"))
        assertEquals(600L, f.supervisor[1].second.getLong("timeout"))
        assertEquals(300L, f.supervisor[2].second.getLong("timeout"))
        assertEquals("New", f.supervisor[3].second.getString("title"))
        // Every supervisor tool in the catalog has an action.
        ToolCatalog.entries.filter { it.set == "supervisor" }.forEach { ToolRouter.supervisorCall(it.spec.name, JSONObject()) }
    }

    @Test fun `only a running command can be peeked, with its timeout`() = runBlocking {
        val f = Fakes(session(termux = true))
        f.bashDone = CompletableDeferred()
        assertEquals(null, f.router.peek("s", "c9"))
        val running = async { f.router.call("s", "c9", "bash", args("command" to "sleep 500", "timeout" to 300)) }
        while (f.bash.isEmpty()) yield()
        assertEquals("c9 within 300", f.router.peek("s", "c9"))
        assertEquals(listOf(Triple("c9", "sleep 500", 300)), f.detached)
        f.bashDone!!.complete("exit code 0")
        running.await()
        assertEquals(null, f.router.peek("s", "c9"))
    }
}
