package com.lanqi.weiassist.agent

import com.lanqi.weiassist.agent.DshSetup.Step
import com.lanqi.weiassist.agent.DshSetup.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshSetupTest {
    private fun out(running: Boolean, installed: String, dsh: Boolean, log: String) =
        "running=${if (running) 1 else 0}\ninstalled=$installed\ndsh=${if (dsh) 1 else 0}\n--- log ---\n$log"

    @Test fun nothingYet() {
        val s = DshSetup.parse(out(false, "", false, ""), "abc")
        assertFalse(s.current); assertFalse(s.outdated); assertFalse(s.running); assertNull(s.failedCode)
        assertTrue(s.steps.values.all { it == StepState.Pending })
    }

    @Test fun runningMarksEarlierStepsDone() {
        val log = "\n== [materials] x\n\n== [termux] Termux packages\nok\n\n== [ubuntu] Ubuntu\n[*] Downloading…\r 40%\r 80%\n"
        val s = DshSetup.parse(out(true, "", false, log), "abc")
        assertTrue(s.running)
        assertEquals(StepState.Done, s.steps[Step.Termux])
        assertEquals(StepState.Running, s.steps[Step.Ubuntu])
        assertEquals(StepState.Pending, s.steps[Step.Node])
        // A progress bar's redraws: only what it showed last.
        assertTrue(s.log.contains(" 80%")); assertFalse(s.log.contains("40%"))
    }

    @Test fun failedStep() {
        val log = "== [termux] a\n== [ubuntu] b\n== [node] c\ncurl: (6) Could not resolve host\n\n== [failed] 6\n"
        val s = DshSetup.parse(out(false, "", false, log), "abc")
        assertEquals(6, s.failedCode)
        assertEquals(StepState.Done, s.steps[Step.Ubuntu])
        assertEquals(StepState.Failed, s.steps[Step.Node])
        assertEquals(StepState.Pending, s.steps[Step.Dsh])
    }

    @Test fun currentOnlyWithThisStamp() {
        val log = Step.entries.joinToString("\n") { "== [${it.id}] x" } + "\n== [done]\n"
        val s = DshSetup.parse(out(false, "abc", true, log), "abc")
        assertTrue(s.current); assertFalse(s.outdated)
        assertTrue(s.steps.values.all { it == StepState.Done })
        val old = DshSetup.parse(out(false, "old", true, log), "abc")
        assertFalse(old.current); assertTrue(old.outdated)
    }

    @Test fun setUpByHandBeforeIsOutdated() {
        val s = DshSetup.parse(out(false, "", true, ""), "abc")
        assertTrue(s.outdated)
    }

    @Test fun colorsRemoved() {
        assertEquals("[*] Installing", DshSetup.cleanLog("\u001B[1;34m[*]\u001B[0m Installing"))
    }

    @Test fun installedVersion() {
        assertEquals("0.2.3", DshSetup.parse("running=0\ninstalled=abc\ndsh=1\nversion=0.2.3\n--- log ---\n", "abc").version)
        assertNull(DshSetup.parse("running=0\ninstalled=\ndsh=0\nversion=\n--- log ---\n", "abc").version)
    }

    @Test fun versionOrder() {
        val sorted = listOf("0.2.1-rc.5", "0.2.2-alpha.3", "0.2.2-rc.1", "0.2.2-rc.2", "0.2.2-rc.10", "0.2.2", "0.2.10", "1.0.0")
        sorted.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", DshSetup.compareVersions(a, b) < 0); assertTrue(DshSetup.compareVersions(b, a) > 0) }
        assertEquals(0, DshSetup.compareVersions("0.2.2-rc.1", "v0.2.2-rc.1"))
    }
}
