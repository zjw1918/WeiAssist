package com.lanqi.weiassist.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxInspectionTest {
    @Test fun allThere() {
        val i = Termux.parseInspection("bash=5.3.3\ncurl=1\ntmux=1\nnetwork=200\n")
        assertEquals("5.3.3", i.bash); assertTrue(i.tools); assertEquals(200, i.network); assertTrue(i.ready)
    }

    @Test fun missingTmuxAndNoNetwork() {
        // curl's -w prints 000 when it got no answer.
        val i = Termux.parseInspection("bash=5.3.3\ncurl=1\nnetwork=000\n")
        assertFalse(i.tmux); assertFalse(i.tools); assertNull(i.network); assertFalse(i.ready)
    }

    @Test fun noCurl() {
        val i = Termux.parseInspection("bash=5.3.3\ntmux=1\n")
        assertFalse(i.curl); assertNull(i.network)
    }

    @Test fun `a running command's stand-in says where it is and how to stop it`() {
        val t = Termux.standIn("123 252\ndownloading 40%\n", "warning\n", "s1", "c1", 600)!!
        assertTrue(t, "Still running after 252s" in t)
        assertTrue(t, "600s timeout" in t)
        assertTrue(t, "kill -TERM 123" in t)
        assertTrue(t, "~/sessions/s1/.bash-c1.out" in t)
        assertTrue(t, "downloading 40%" in t)
        assertTrue(t, "warning" in t)
    }

    @Test fun `a stand-in without output says so, and a broken report gives none`() {
        assertTrue("(no output yet)" in Termux.standIn("123 5\n", "", "s", "c", 120)!!)
        assertEquals(null, Termux.standIn("", "", "s", "c", 120))
        assertEquals(null, Termux.standIn("not numbers\n", "", "s", "c", 120))
    }
}
