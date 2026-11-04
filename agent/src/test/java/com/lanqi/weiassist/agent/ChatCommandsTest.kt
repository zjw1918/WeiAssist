package com.lanqi.weiassist.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCommandsTest {
    @Test fun commands() {
        assertEquals(ChatCommands.Command("new", ""), ChatCommands.parse("/new", "MyBot"))
        assertEquals(ChatCommands.Command("stop", ""), ChatCommands.parse("/stop@mybot", "MyBot"))
        assertEquals(ChatCommands.Command("start", "ABC"), ChatCommands.parse(" /Start ABC ", "MyBot"))
        assertNull(ChatCommands.parse("/stop@OtherBot", "MyBot"))
        assertNull(ChatCommands.parse("hello /new", "MyBot"))
        assertNull(ChatCommands.parse("/usr/bin is a path", "MyBot"))
    }

    @Test fun pairingCodes() {
        val code = ChatCommands.newPairingCode()
        assertEquals(8, code.length)
        assertEquals(code, ChatCommands.pairingCode("/start $code", "b"))
        assertEquals(code, ChatCommands.pairingCode("/pair ${code.lowercase()}", "b"))
        assertEquals(code, ChatCommands.pairingCode(code, "b"))
        assertNull(ChatCommands.pairingCode("/new $code", "b"))
        assertNull(ChatCommands.pairingCode("/start", "b"))
        assertNull(ChatCommands.pairingCode("hello there", "b"))
        assertTrue(ChatCommands.sameCode(code, code.lowercase()))
        assertFalse(ChatCommands.sameCode(code, "ABCDEFGH".takeIf { it != code } ?: "HGFEDCBA"))
    }
}
