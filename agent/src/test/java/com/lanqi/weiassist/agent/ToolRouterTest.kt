package com.lanqi.weiassist.agent

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
        assertEquals(24, all.size)
        all.forEach { e ->
            assertEquals(e.spec.name, "object", JSONObject(e.spec.parameters).getString("type"))
            assertTrue(e.spec.name, e.spec.description.isNotBlank())
        }
        assertEquals(setOf("phone", "bash", "send_file", "supervisor"), all.map { it.set }.toSet())
        assertTrue("supervisor tools may run side by side", all.filter { it.set == "supervisor" }.all { it.spec.parallel })
        assertTrue(all.filter { it.set != "supervisor" }.none { it.spec.parallel })
    }

    @Test fun `each kind of session gets its own tools`() {
        // send_file with Termux: to the messaging app's chat, or the app's.
        assertEquals(listOf("bash", "send_file"), names(session()))
        assertEquals(emptyList<String>(), names(session(termux = false)))
        assertTrue(names(session(phone = true)).containsAll(listOf("open_app", "read_screen", "tap", "screenshot", "note", "bash")))
        assertEquals(listOf("bash", "send_file"), names(session(channel = "telegram")))
        assertEquals(emptyList<String>(), names(session(channel = "telegram", termux = false)))
        val sup = names(session(supervisor = true, phone = true, channel = "telegram"))
        assertEquals(10, sup.size)
        assertTrue(sup.all { it.endsWith("_session") || it == "message_session" || it == "wait_for" || it == "list_sessions" })
    }

    private class Fakes(val summary: SessionSummary, var open: Boolean = true) {
        val phone = mutableListOf<Pair<String, JSONObject>>()
        val bash = mutableListOf<Triple<String, String, Int>>()
        val kills = mutableListOf<String>()
        val supervisor = mutableListOf<Pair<String, JSONObject>>()
        var phoneAnswer = "did it"
        var bashDone: CompletableDeferred<String>? = null
        var sendOk = true
        val router = ToolRouter(
            summary = { if (it == summary.id) summary else null },
            gate = { open },
            phone = { _, tool, args -> phone += tool to args; phoneAnswer },
            bash = { _, call, command, timeout -> bash += Triple(call, command, timeout); bashDone?.await() ?: "exit code 0" },
            bashKill = { _, call -> kills += call },
            sendFile = { _, path, caption -> sendOk to (if (sendOk) "sent $path ($caption)" else "No such file") },
            supervisor = { _, action, args -> supervisor += action to args; "ok $action" },
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
        f.phoneAnswer = "/9j/AAAA"
        val shot = f.router.call("s", "c3", "screenshot", JSONObject())
        assertEquals("/9j/AAAA", shot.image)
        assertTrue(shot.text.startsWith("Screenshot (720 px wide"))
        // A message instead of a picture stays text: as an image the provider would reject the request.
        f.phoneAnswer = "no app open yet"
        assertNull(f.router.call("s", "c4", "screenshot", JSONObject()).image)
        val json = ToolRouter.screenshot("/9j/x").toJson()
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
}
