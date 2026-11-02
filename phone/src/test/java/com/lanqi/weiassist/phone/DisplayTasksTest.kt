package com.lanqi.weiassist.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTasksTest {
    // Trimmed from a realme (Android 12) with two virtual displays.
    private val dump = """
Display #0 (activities from top to bottom):
    mResumedActivity: ActivityRecord{ecce228 u0 com.lanqi.weiassist/.MainActivity t20998}
    * Task{d14ab41 #20998 type=standard A=10468:com.lanqi.weiassist U=0 visible=true mode=fullscreen translucent=false sz=1}
      Activities=[ActivityRecord{ecce228 u0 com.lanqi.weiassist/.MainActivity t20998}]
      * Hist #0: ActivityRecord{ecce228 u0 com.lanqi.weiassist/.MainActivity t20998}
    * Task{3f8fbb3 #20940 type=standard A=10365:com.google.android.gm U=0 visible=false mode=fullscreen translucent=true sz=1}
      * Hist #0: ActivityRecord{b1b4522 u0 com.google.android.gm/.ConversationListActivityGmail t20940}
Display #5 (activities from top to bottom):
    mResumedActivity: ActivityRecord{cb29519 u0 com.android.settings/.Settings t20999}
    * Task{f00 #20999 type=standard A=1000:com.android.settings U=0 visible=true mode=fullscreen translucent=false sz=1}
      * Hist #1: ActivityRecord{cf5bdc8 u0 com.android.settings/.SubSettings t20999}
      * Hist #0: ActivityRecord{cb29519 u0 com.android.settings/.Settings t20999}
Display #4 (activities from top to bottom):
  ResumedActivity: ActivityRecord{ecce228 u0 com.lanqi.weiassist/.MainActivity t20998}
""".trimIndent().replace("\n    ", "\n    ")

    @Test fun `apps and the app in front are found per display`() {
        val t = DisplayTasks.parse(dump)
        assertEquals(setOf("com.lanqi.weiassist", "com.google.android.gm"), t.apps[0])
        assertEquals(setOf("com.android.settings"), t.apps[5])
        assertEquals(emptySet<String>(), t.apps[4])
        assertEquals("com.lanqi.weiassist", t.resumed[0])
        assertEquals("com.android.settings", t.resumed[5])
    }

    @Test fun `the global summary at the end is not taken for the last display`() {
        assertEquals(null, DisplayTasks.parse(dump).resumed[4])
    }

    @Test fun `an app's displays`() {
        assertEquals(setOf(5), DisplayTasks.parse(dump).displaysOf("com.android.settings"))
        assertEquals(emptySet<Int>(), DisplayTasks.parse(dump).displaysOf("com.taobao.taobao"))
    }
}
