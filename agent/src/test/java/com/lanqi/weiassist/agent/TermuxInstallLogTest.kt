package com.lanqi.weiassist.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermuxInstallLogTest {
    @Test fun stageAndLastLine() {
        val p = Termux.parseInstallLog("== [update]\nHit:1 https://x stable InRelease\n== [upgrade]\nGet:3 https://x openssl 3.5.1\nUnpacking openssl (3.5.1) ...\n\n", 7)
        assertEquals(Termux.InstallStage.Upgrade, p.stage); assertEquals("Unpacking openssl (3.5.1) ...", p.line); assertEquals(7L, p.since)
    }

    @Test fun justStartedAStage() {
        // The line before the stage began says nothing about it.
        val p = Termux.parseInstallLog("== [update]\nHit:1 https://x\n== [install]\n", 0)
        assertEquals(Termux.InstallStage.Install, p.stage); assertEquals("", p.line)
    }

    @Test fun mirror() {
        val p = Termux.parseInstallLog("== [mirror] https://mirrors.ustc.edu.cn/termux/apt/termux-main\nReading package lists...\r35%\r100%\n", 0)
        assertEquals(Termux.InstallStage.Mirror, p.stage); assertEquals("https://mirrors.ustc.edu.cn/termux/apt/termux-main", p.mirror)
        assertEquals("100%", p.line)
    }

    @Test fun nothingYet() {
        val p = Termux.parseInstallLog("", 0)
        assertNull(p.stage); assertEquals("", p.line)
    }
}
