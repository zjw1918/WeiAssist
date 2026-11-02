package com.lanqi.weiassist.phone

/**
 * Which apps have activities on which display, and which app is in front of each, from
 * `dumpsys activity activities` (the shell helper runs it). Pure, unit-tested.
 */
data class DisplayTasks(val apps: Map<Int, Set<String>>, val resumed: Map<Int, String>) {
    /** Displays where [pkg] has activities. */
    fun displaysOf(pkg: String): Set<Int> = apps.filterValues { pkg in it }.keys

    companion object {
        private val DISPLAY = Regex("""^Display #(\d+)""")
        private val HIST = Regex("""^\s+\* Hist #\d+: ActivityRecord\{\S+ u\d+ ([^/\s]+)/""")
        // Indented: inside a display. (An unindented "ResumedActivity:" at the end is a global summary.)
        private val RESUMED = Regex("""^\s+mResumedActivity: ActivityRecord\{\S+ u\d+ ([^/\s]+)/""")

        fun parse(dump: String): DisplayTasks {
            val apps = HashMap<Int, MutableSet<String>>()
            val resumed = HashMap<Int, String>()
            var display = -1
            for (line in dump.lineSequence()) {
                DISPLAY.find(line)?.let { display = it.groupValues[1].toInt(); apps.getOrPut(display) { mutableSetOf() }; return@let }
                if (display < 0) continue
                HIST.find(line)?.let { apps.getOrPut(display) { mutableSetOf() }.add(it.groupValues[1]) }
                RESUMED.find(line)?.let { resumed[display] = it.groupValues[1] }
            }
            return DisplayTasks(apps, resumed)
        }
    }
}
