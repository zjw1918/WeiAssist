package com.lanqi.weiassist.phone

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolsTest {
    private val sendScreen = "app: com.google.android.gm\n[1] EditText \"Subject\" hint=\"Subject\" editable\n[2] ImageButton (Send) clickable\n[3] TextView \"Inbox\""

    /** A fake phone: answers read_screen with [screen], label_at with [labelAt], and records every action. */
    private class Fake(var screen: String = "app: x", var labelAt: String = "") {
        val actions = mutableListOf<Pair<String, String>>()
        val asked = mutableListOf<PhoneTools.Confirmation>()
        var allow = true
        val tools = PhoneTools { _, action, args ->
            actions += action to args.toString()
            when (action) {
                "read_screen" -> screen
                "label_at" -> labelAt
                else -> "did $action"
            }
        }
        suspend fun check(tool: String, args: JSONObject = JSONObject()) = tools.check("s", tool, args) { asked += it; allow }
    }

    private fun args(vararg p: Pair<String, Any>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }

    @Test fun `an action returns its result, then the screen it left`() = runBlocking {
        val f = Fake(screen = "app: x\n[1] TextView \"hi\"")
        assertEquals("did tap${PhonePolicy.SCREEN_NOW}app: x\n[1] TextView \"hi\"", f.tools.call("s", "tap", args("id" to 1)))
        assertEquals(listOf("tap", "read_screen"), f.actions.map { it.first })
        assertEquals("""{"id":1}""", f.actions[0].second) // arguments reach the phone as given
    }

    @Test fun `read_screen and screenshot are one call, and wait waits, then reads the screen`() = runBlocking {
        val f = Fake()
        f.tools.call("s", "read_screen", JSONObject())
        f.tools.call("s", "screenshot", JSONObject())
        assertEquals(listOf("read_screen", "screenshot"), f.actions.map { it.first })
        assertTrue(f.tools.call("s", "wait", args("ms" to -5)).startsWith("waited${PhonePolicy.SCREEN_NOW}"))
        assertTrue(runCatching { f.tools.call("s", "uninstall", JSONObject()) }.isFailure)
    }

    @Test fun `a tap on Send asks the user, with the button's label`() = runBlocking {
        val f = Fake(screen = sendScreen)
        f.tools.call("s", "read_screen", JSONObject())
        assertNull(f.check("tap", args("id" to 2)))
        assertEquals(listOf(PhoneTools.Confirmation("Send", blind = false, typed = emptyList())), f.asked)
    }

    @Test fun `declining blocks the tap`() = runBlocking {
        val f = Fake(screen = sendScreen).apply { allow = false }
        f.tools.call("s", "read_screen", JSONObject())
        assertEquals(PhonePolicy.DECLINED, f.check("tap", args("id" to 2)))
    }

    @Test fun `ordinary taps and other tools don't ask`() = runBlocking {
        val f = Fake(screen = sendScreen)
        f.tools.call("s", "read_screen", JSONObject())
        assertNull(f.check("tap", args("id" to 3)))
        assertNull(f.check("tap", args("id" to 99))) // not on the screen read
        assertNull(f.check("swipe", args("direction" to "up")))
        assertNull(f.check("press_key", args("key" to "enter"))) // nothing typed blind
        assertTrue(f.asked.isEmpty())
    }

    @Test fun `a coordinate tap on a buy button asks, by the label under the point`() = runBlocking {
        val f = Fake(labelAt = "立即购买").apply { allow = false }
        assertEquals(PhonePolicy.DECLINED, f.check("tap_at", args("x" to 900, "y" to 2200)))
        assertEquals("立即购买", f.asked.single().label)
        assertEquals("""{"x":900,"y":2200}""", f.actions.single { it.first == "label_at" }.second)
    }

    @Test fun `the card shows what was typed, by field name`() = runBlocking {
        val f = Fake(screen = sendScreen)
        f.tools.call("s", "read_screen", JSONObject())
        f.tools.call("s", "type_text", args("id" to 1, "text" to "Hello"))
        f.check("tap", args("id" to 2))
        assertEquals(listOf("Subject: Hello"), f.asked.single().typed)
    }

    @Test fun `after blind typing, an unreadable tap or Enter asks, every time`() = runBlocking {
        val f = Fake(labelAt = "")
        f.tools.call("s", "type_text", args("text" to "在吗")) // no id: the app hides its fields (WeChat)
        assertNull(f.check("tap_at", args("x" to 1, "y" to 2)))
        assertNull(f.check("tap_at", args("x" to 1, "y" to 2))) // still asks after being allowed
        assertNull(f.check("press_key", args("key" to "enter")))
        assertEquals(3, f.asked.size)
        assertTrue(f.asked.all { it.blind && it.typed == listOf("field: 在吗") })
        // A readable, harmless label is no reason to ask.
        f.labelAt = "Back"
        assertNull(f.check("tap_at", args("x" to 1, "y" to 2)))
        assertEquals(3, f.asked.size)
    }

    @Test fun `typing blind with submit asks first, with the text on the card`() = runBlocking {
        val f = Fake()
        f.check("type_text", args("text" to "hi", "submit" to true))
        assertEquals(PhoneTools.Confirmation("", blind = true, typed = listOf("field: hi")), f.asked.single())
        // Into a known field, with nothing typed blind before: no question.
        assertNull(Fake().let { g -> g.check("type_text", args("id" to 3, "text" to "example.com", "submit" to true)).also { assertTrue(g.asked.isEmpty()) } })
    }

    @Test fun `a runaway task stops at the step limit, notes don't count, and a new task starts over`() = runBlocking {
        val f = Fake()
        repeat(PhonePolicy.MAX_STEPS) { assertNull(f.check("swipe")) }
        assertNull(f.check("note"))
        assertEquals(PhonePolicy.STEP_LIMIT, f.check("swipe"))
        f.tools.taskStarted("s")
        assertNull(f.check("swipe"))
    }

    @Test fun `sessions don't share screens, typing or steps`() = runBlocking {
        val f = Fake(screen = sendScreen)
        f.tools.call("other", "read_screen", JSONObject())
        f.tools.call("other", "type_text", args("text" to "x"))
        assertNull(f.check("tap", args("id" to 2))) // "s" never read this screen
        assertTrue(f.asked.isEmpty())
    }

    @Test fun `labels, field names and risky words`() {
        assertEquals("Send", PhonePolicy.labelOf("ImageButton (Send) clickable"))
        assertEquals("Buy now", PhonePolicy.labelOf("Button \"Buy now\" (ignored)"))
        assertEquals("Subject", PhonePolicy.fieldName("EditText \"\" hint=\"Subject\""))
        assertEquals("compose", PhonePolicy.fieldName("EditText id=compose"))
        assertEquals("field", PhonePolicy.fieldName(null))
        listOf("Send", "send now", "Pay", "立即购买", "提交订单", " Delete").forEach { assertTrue(it, PhonePolicy.risky(it)) }
        listOf("", "Sender", "Inbox", "Send me the report about last quarter's numbers").forEach { assertFalse(it, PhonePolicy.risky(it)) }
        assertEquals(mapOf(2 to "ImageButton (Send) clickable", 1 to "EditText \"Subject\" hint=\"Subject\" editable", 3 to "TextView \"Inbox\""), PhonePolicy.parseScreen(sendScreen))
    }
}
