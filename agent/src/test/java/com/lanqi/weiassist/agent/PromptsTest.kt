package com.lanqi.weiassist.agent

import com.lanqi.weiassist.core.composePrompt
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {
    private fun session(kind: String, termux: Boolean, channel: String?) = SessionSummary(
        "s1", "t", phone = kind == "phone", status = SessionStatus.Idle, createdAt = 0, updatedAt = 0,
        supervisor = kind == "supervisor", channel = channel, termux = termux,
    )

    /** The tools a core gives the model: all of the session's, as Pi does. */
    private fun all(s: SessionSummary) = ToolCatalog.forSession(s).map { it.name }

    @Test fun `Pi's prompts are word for word what pi-host composed before, for every kind of session`() {
        // prompts-pi-before.json: the prompts pi-host's own code composed, generated from it before the move. The web
        // guide came later: it goes with web_fetch, so without that tool the prompt is still the old one.
        val before = JSONArray(checkNotNull(javaClass.getResourceAsStream("/prompts-pi-before.json")).use { String(it.readBytes()) })
        assertEquals(12, before.length())
        for (i in 0 until before.length()) {
            val c = before.getJSONObject(i)
            val s = session(c.getString("kind"), c.getBoolean("bash"), c.optString("channel").takeIf { it.isNotEmpty() && it != "null" })
            assertEquals(c.toString(), c.getString("prompt"), composePrompt(Prompts.forSession(s, "小维助手"), all(s) - "web_fetch"))
        }
    }

    @Test fun `every session but the supervisor gets the web guide, after its persona`() {
        for (s in listOf(session("chat", false, null), session("phone", true, null), session("chat", true, "telegram"))) {
            val p = composePrompt(Prompts.forSession(s, "Ada"), all(s))
            assertTrue(p, "web_fetch reads a page" in p)
            assertTrue(p.indexOf("web_fetch reads a page") > p.indexOf("Ada"))
        }
        val sup = session("supervisor", false, null)
        assertFalse(composePrompt(Prompts.forSession(sup, "Ada"), all(sup)).contains("web_fetch"))
    }

    @Test fun `a core without the app's bash gets no Termux guide, and keeps the rest`() {
        val s = session("phone", termux = true, channel = "telegram")
        val full = composePrompt(Prompts.forSession(s, "WeiAssist"), all(s))
        val ownBash = composePrompt(Prompts.forSession(s, "WeiAssist"), all(s) - "bash")
        assertTrue(full.contains("Linux (Termux)"))
        assertFalse(ownBash.contains("Linux (Termux)"))
        assertTrue(ownBash.startsWith("You are an assistant that operates the user's Android phone for them, working in the WeiAssist app."))
        assertTrue(ownBash.contains("from Telegram"))
        assertTrue(ownBash.contains("use send_file"))
    }

    @Test fun `names are filled in`() {
        val p = composePrompt(Prompts.forSession(session("chat", termux = false, channel = "telegram"), "Ada"), emptyList())
        assertTrue(p.contains("working in the Ada app") && p.contains("Ada is the app, not you"))
        assertFalse(p.contains("{name}") || p.contains("{channel}"))
    }
}
