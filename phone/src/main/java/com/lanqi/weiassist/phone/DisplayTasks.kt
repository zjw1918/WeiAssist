package com.lanqi.weiassist.phone

/**
 * Which tasks (and apps) are on which display, from `dumpsys activity activities` (the shell helper runs
 * it). Handles Android 12 (`* Hist #0`, `mResumedActivity:`) and Android 14-16 (`* Hist  #0`,
 * `topResumedActivity=`). Pure, unit-tested.
 */
data class DisplayTasks(val apps: Map<Int, Set<String>>, val resumed: Map<Int, String>, val tasks: List<Task> = emptyList()) {
    /**
     * A root task. [launchedFrom]: the package that started its first activity (the app itself, when it
     * opened a new task on its own; the launcher, when the user did).
     */
    data class Task(val id: Int, val display: Int, val pkg: String, val visible: Boolean, val launchedFrom: String?)

    /** Displays where [pkg] has activities. */
    fun displaysOf(pkg: String): Set<Int> = apps.filterValues { pkg in it }.keys

    companion object {
        private val DISPLAY = Regex("""^Display #(\d+)""")
        // Root tasks are indented by two spaces in a display's section (deeper "* Task" lines are other views).
        private val TASK = Regex("""^  \* Task\{\S+ #(\d+) type=\w+ A=\d+:(\S+) .*?visible=(true|false)""")
        private val HIST = Regex("""^\s+\* Hist\s+#\d+: ActivityRecord\{\S+ u\d+ ([^/\s]+)/""")
        // Indented: inside a display. (An unindented "ResumedActivity:" at the end is a global summary.)
        private val RESUMED = Regex("""^\s+(?:mResumedActivity: |topResumedActivity=)ActivityRecord\{\S+ u\d+ ([^/\s]+)/""")
        private val LAUNCHED_FROM = Regex("""launchedFromPackage=(\S+)""")

        fun parse(dump: String): DisplayTasks {
            val apps = HashMap<Int, MutableSet<String>>()
            val resumed = HashMap<Int, String>()
            val tasks = ArrayList<Task>()
            var display = -1
            var task: Task? = null
            fun flush() { task?.let(tasks::add); task = null }
            for (line in dump.lineSequence()) {
                DISPLAY.find(line)?.let {
                    flush()
                    display = it.groupValues[1].toInt()
                    apps.getOrPut(display) { mutableSetOf() }
                    return@let
                }
                if (display < 0) continue
                TASK.find(line)?.let { m ->
                    flush()
                    task = Task(m.groupValues[1].toInt(), display, m.groupValues[2], m.groupValues[3] == "true", null)
                }
                HIST.find(line)?.let { apps.getOrPut(display) { mutableSetOf() }.add(it.groupValues[1]) }
                RESUMED.find(line)?.let { if (display !in resumed) resumed[display] = it.groupValues[1] }
                LAUNCHED_FROM.find(line)?.let { m -> task?.let { t -> if (t.launchedFrom == null) task = t.copy(launchedFrom = m.groupValues[1]) } }
            }
            flush()
            return DisplayTasks(apps, resumed, tasks)
        }
    }
}
