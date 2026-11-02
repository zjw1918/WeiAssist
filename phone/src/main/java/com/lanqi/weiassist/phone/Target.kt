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
    ) : Target
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
}
