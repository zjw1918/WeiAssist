package com.lanqi.weiassist.phone

import android.util.Log

/**
 * When a session's task ends, close what it opened on its display, so apps don't stay alive in the
 * background (using memory, or frozen by the system and slowing the phone):
 * - its windows (root tasks on its display) are removed;
 * - then each app is killed, unless it was running before the session opened it (the user's, e.g. a chat
 *   app kept alive for its notifications), or it still has a window anywhere (the user's screen or another
 *   session's). `am kill` only kills background processes, never one the user sees or hears.
 */
object SessionApps {
    private const val TAG = "SessionApps"

    /** [stillIdle]: checked before each step; a follow-up that starts meanwhile keeps its apps. */
    fun close(t: Target.Virtual, stillIdle: () -> Boolean = { true }) {
        val tasks = t.io.tasks() ?: return
        val pkgs = t.apps.toSet() + tasks.apps[t.displayId].orEmpty()
        if (pkgs.isEmpty()) return
        val removed = tasks.tasks.filter { it.display == t.displayId }.map { it.id }.toSet()
        for (id in removed) {
            if (!stillIdle()) return
            t.io.removeTask(id)
        }
        // Removal finishes asynchronously: wait until the tasks are gone before judging who still has a window.
        var after = t.io.tasks() ?: return
        for (i in 0 until 8) {
            if (after.tasks.none { it.id in removed }) break
            Thread.sleep(250)
            after = t.io.tasks() ?: return
        }
        val killed = pkgs.filter { p ->
            stillIdle() && p !in t.wereRunning && after.displaysOf(p).isEmpty() && t.io.killApp(p)
        }
        val kept = pkgs.filter { it !in killed }.map { p -> "$p (${if (p in t.wereRunning) "was running" else "has a window on ${after.displaysOf(p)}"})" }
        Log.i(TAG, "display ${t.displayId}: closed ${pkgs.joinToString()}; killed ${killed.joinToString().ifEmpty { "none" }}; kept ${kept.joinToString().ifEmpty { "none" }}")
        if (stillIdle()) { t.apps.clear(); t.ownTasks.clear(); t.wereRunning.clear() }
    }
}
