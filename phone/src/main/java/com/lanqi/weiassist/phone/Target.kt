package com.lanqi.weiassist.phone

/** Where one session's phone actions go. */
sealed interface Target {
    val displayId: Int

    /** The phone's own screen (display 0), shared with the user: one session at a time. */
    data object Main : Target { override val displayId = 0 }

    /**
     * A virtual display of the session's own, driven through the shell helper ([io]). [guard] says why
     * an app can't be opened here now (another running task uses it), or null.
     */
    data class Virtual(
        override val displayId: Int, val width: Int, val height: Int, val io: DisplayIo,
        val guard: (pkg: String, tasks: DisplayTasks) -> String? = { _, _ -> null },
    ) : Target {
        /** Apps this session opened on its display. */
        val apps: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
        /** Tasks this session brought to its display once: theirs from then on, wherever Android puts them. */
        val ownTasks: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())
        /** Tasks that were on the phone's screen before the session opened anything (the user's; never moved). */
        val usersTasks: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())
        /** Apps that were already running when the session opened them: never killed when it ends (see SessionApps). */
        val wereRunning: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    }
}

/** Input, launching and screenshots on a virtual display (implemented over the shell helper). */
interface DisplayIo {
    fun tap(x: Float, y: Float, durationMs: Long): Boolean
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean
    fun key(keyCode: Int): Boolean
    /** JPEG bytes, empty if there is no frame. */
    fun screenshot(maxWidth: Int, quality: Int): ByteArray
    /** Launch "package/.Activity"; returns `am`'s output. */
    fun startActivity(component: String): String
    /** Which apps are on which display now, or null if unknown. */
    fun tasks(): DisplayTasks?
    /** Move a task to this display. */
    fun moveTask(taskId: Int): Boolean
    /**
     * The display to type on for this one (Android 14+), or -1: an app moves there while the keyboard's
     * connection puts text in (see PhoneActions.typeByKeyboard), then back with [moveTask].
     */
    fun typingDisplay(): Int
    /** Move a task to [display] (this one's typing display). */
    fun moveTaskTo(taskId: Int, display: Int): Boolean
    /** Remove a root task. */
    fun removeTask(taskId: Int): Boolean
    /** Kill an app's processes if they are all in the background. */
    fun killApp(pkg: String): Boolean
    /** Whether the app has a running process. */
    fun isRunning(pkg: String): Boolean
}
