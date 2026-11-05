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
}
