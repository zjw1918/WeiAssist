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

    // Trimmed from a Xiaomi (HyperOS, Android 16): two spaces after "Hist", topResumedActivity=, and an app
    // (Pinduoduo) that opened its own new task on the phone's screen.
    private val dump16 = """
Display #0 (activities from top to bottom):

  * Task{50b94fe #7136 type=standard A=10401:com.xunmeng.pinduoduo U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    topResumedActivity=ActivityRecord{45485448 u0 com.xunmeng.pinduoduo/.ui.activity.HomeActivity t7136}
    * Hist  #0: ActivityRecord{45485448 u0 com.xunmeng.pinduoduo/.ui.activity.HomeActivity t7136}
      packageName=com.xunmeng.pinduoduo processName=com.xunmeng.pinduoduo
      launchedFromUid=10401 launchedFromPackage=com.xunmeng.pinduoduo launchedFromFeature=null userId=0
  * Task{1234 #4192 type=home A=10100:com.miui.home U=0 visible=false visibleRequested=false mode=fullscreen translucent=false sz=1}
    * Hist  #0: ActivityRecord{183456620 u0 com.miui.home/.launcher.Launcher t4192}
      launchedFromUid=1000 launchedFromPackage=android launchedFromFeature=null userId=0
  * Task{217f1ca #7134 type=standard A=10305:com.taobao.taobao U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
    * Hist  #0: ActivityRecord{191897452 u0 com.taobao.taobao/com.taobao.tao.TBMainActivity t7134}
      launchedFromUid=10302 launchedFromPackage=com.miui.home launchedFromFeature=null userId=0
Display #3 (activities from top to bottom):

  * Task{f44b5d #7130 type=standard A=10317:com.jingdong.app.mall U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    topResumedActivity=ActivityRecord{135569990 u0 com.jingdong.app.mall/.MainFrameActivity t7130}
    * Hist  #0: ActivityRecord{135569990 u0 com.jingdong.app.mall/.MainFrameActivity t7130}
      launchedFromUid=2000 launchedFromPackage=com.android.shell launchedFromFeature=null userId=0
Display #4 (activities from top to bottom):
      * Task{9bc7fa9 #7132 type=standard A=10401:com.xunmeng.pinduoduo U=0 visible=false visibleRequested=false mode=fullscreen
  ResumedActivity: ActivityRecord{183456620 u0 com.miui.home/.launcher.Launcher t4192}
""".trimIndent()

    @Test fun `Android 16's format is read too`() {
        val t = DisplayTasks.parse(dump16)
        assertEquals(setOf(3), t.displaysOf("com.jingdong.app.mall"))
        assertEquals(setOf(0), t.displaysOf("com.xunmeng.pinduoduo"))
        assertEquals("com.xunmeng.pinduoduo", t.resumed[0])
        assertEquals("com.jingdong.app.mall", t.resumed[3])
        assertEquals(null, t.resumed[4])
    }

    @Test fun `root tasks carry their id, display, visibility and who launched them`() {
        val t = DisplayTasks.parse(dump16).tasks
        assertEquals(
            listOf(
                DisplayTasks.Task(7136, 0, "com.xunmeng.pinduoduo", true, "com.xunmeng.pinduoduo"),
                DisplayTasks.Task(4192, 0, "com.miui.home", false, "android"),
                DisplayTasks.Task(7134, 0, "com.taobao.taobao", false, "com.miui.home"),
                DisplayTasks.Task(7130, 3, "com.jingdong.app.mall", true, "com.android.shell"),
            ),
            t,
        )
    }

    @Test fun `an app's displays`() {
        assertEquals(setOf(5), DisplayTasks.parse(dump).displaysOf("com.android.settings"))
        assertEquals(emptySet<Int>(), DisplayTasks.parse(dump).displaysOf("com.taobao.taobao"))
    }
}
