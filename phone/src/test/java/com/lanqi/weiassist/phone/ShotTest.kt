package com.lanqi.weiassist.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotTest {
    @Test fun `the model's picture fits 720 on the short side and 1600 on the long, in any orientation`() {
        assertEquals(720 to 1600, Shot.forModel(1080, 2400)) // phone upright: as before
        assertEquals(1600 to 720, Shot.forModel(2400, 1080)) // phone in landscape (a game): was 720x324
        assertEquals(1200 to 720, Shot.forModel(2000, 1200)) // tablet in landscape
        assertEquals(720 to 1200, Shot.forModel(1200, 2000)) // tablet upright
        assertEquals(720 to 1600, Shot.forModel(720, 1600))  // a session's display: not scaled
        assertEquals(1600 to 720, Shot.forModel(1600, 720))
        assertEquals(480 to 800, Shot.forModel(480, 800))    // small screens are never scaled up
        assertEquals(1600 to 450, Shot.forModel(3200, 900))  // very wide: the long side limits
    }

    @Test fun `the caption gives the factor for tap_at, and pack and parse round-trip`() {
        val s = Shot("/9j/x", 2400, 1080, 1600, 720)
        assertEquals("Screenshot scaled to 1600x720 px; the screen is 2400x1080. For tap_at and drag, multiply x and y by 1.5.", s.caption())
        assertTrue(Shot("/9j/x", 2000, 1200, 1200, 720).caption().endsWith("multiply x and y by 1.667.")) // tablet
        assertEquals(s, Shot.parse(s.pack()))
        assertNull(Shot.parse("error: no picture"))
    }
}
