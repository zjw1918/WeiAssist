package com.lanqi.weiassist.agent

import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.lanqi.weiassist.phone.DisplayIo
import com.lanqi.weiassist.phone.DisplayTasks
import com.lanqi.weiassist.phone.SessionApps
import com.lanqi.weiassist.phone.Target
import com.lanqi.weiassist.shell.IShellService
import com.lanqi.weiassist.shell.ShellBackend

/**
 * Where phone sessions get a screen to operate. Main thread only.
 * - With the shell helper running, each phone session gets a virtual display of its own, so they run
 *   side by side and never take the user's screen.
 * - Without it, there is one screen, the phone's own: phone sessions take turns (the others queue).
 */
interface ScreenPool {
    /** A screen for [session] (the one it already has, if any), or null if none is free now. */
    fun acquire(session: String): Target?
    /** The session's task ended: its screen may be kept a while for a follow-up, then given up. */
    fun idle(session: String)
    /** Give the session's screen up now (stopped, deleted). */
    fun release(session: String)
    /** The screen [session] holds, if any. */
    fun target(session: String): Target?
}

class MainScreenPool : ScreenPool {
    private var owner: String? = null
    override fun acquire(session: String): Target? = if (owner == null || owner == session) Target.Main.also { owner = session } else null
    override fun idle(session: String) = release(session)
    @Synchronized override fun release(session: String) { if (owner == session) owner = null }
    override fun target(session: String): Target? = if (owner == session) Target.Main else null
}

/**
 * Virtual displays from the shell helper, at most [max] at once. A display stays with its session, its
 * apps open as the task left them, for [keepIdleMs] after a task ends: a follow-up message carries on
 * where it was. The session's apps are closed when the display is given up: after that time, when
 * another session needs the room, or when the session is deleted.
 * Falls back to [fallback] (the phone's own screen) while the helper isn't running.
 */
class DisplayScreenPool(
    private val shell: ShellBackend,
    private val fallback: ScreenPool = MainScreenPool(),
    private val max: Int = 3,
    private val keepIdleMs: Long = 10 * 60_000,
) : ScreenPool {
    private class Lease(val target: Target.Virtual, var idleSince: Long? = null)

    private val _displays = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Each session's display, for previews. */
    val displays: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _displays

    private fun publish() { _displays.value = leases.mapValues { it.value.target.displayId } }

    /** Debug builds: act as if there were no shell helper, so new sessions use the phone's own screen. */
    @Volatile var debugMainOnly = false

    /** Whether a session is working now (set by the owner; see [guard]). */
    var isActive: (String) -> Boolean = { true }
    private val leases = LinkedHashMap<String, Lease>()
    /** Gives up idle displays on time, even when nothing else happens meanwhile. */
    private val timer = android.os.Handler(android.os.Looper.getMainLooper())
    /** Closing apps and releasing displays: binder calls and `am`, off the caller's thread, in order. */
    private val closer = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Synchronized override fun acquire(session: String): Target? {
        sweep()
        leases[session]?.let { l ->
            if (shell.service()?.asBinder()?.isBinderAlive == true) {
                if (l.idleSince != null) setActive(l.target.displayId, true)
                l.idleSince = null
                return l.target
            }
            leases.remove(session) // the helper died with its displays
            publish()
        }
        val helper = shell.service()?.takeUnless { debugMainOnly } ?: return fallback.acquire(session)
        if (leases.size >= max) {
            val oldestIdle = leases.entries.filter { it.value.idleSince != null }.minByOrNull { it.value.idleSince!! } ?: return null
            release(oldestIdle.key)
        }
        return try {
            val id = helper.createDisplay(session, WIDTH, HEIGHT, DPI)
            check(id > 0) { "the helper returned display $id" } // never the phone's own screen
            Log.i(TAG, "display $id for $session")
            Target.Virtual(id, WIDTH, HEIGHT, HelperIo(helper, id)) { pkg, tasks -> guard(session, pkg, tasks) }
                .also { leases[session] = Lease(it); publish() }
        } catch (e: Exception) {
            Log.w(TAG, "no virtual display for $session: $e")
            fallback.acquire(session)
        }
    }

    @Synchronized override fun idle(session: String) {
        leases[session]?.let { l ->
            l.idleSince = SystemClock.uptimeMillis()
            // Nothing happens on it until the next task: stop its apps drawing (the last picture stays).
            // They stay open for a follow-up; release() closes them when the display is given up.
            setActive(l.target.displayId, false)
            timer.postDelayed(::sweep, keepIdleMs + 1_000)
        }
        fallback.idle(session)
        sweep()
    }

    @Synchronized override fun release(session: String) {
        leases.remove(session)?.let { l ->
            publish()
            // Its apps first (removing the display only finishes their activities; their processes would stay).
            closer.execute { SessionApps.close(l.target); runCatching { shell.service()?.releaseDisplay(l.target.displayId) } }
        }
        fallback.release(session)
    }

    override fun target(session: String): Target? = leases[session]?.target ?: fallback.target(session)

    /**
     * An app another *working* session has open on its display can't be opened by [session] (it would be
     * moved away mid-task). One kept for an idle session may be taken. Called off the main thread.
     */
    private fun guard(session: String, pkg: String, tasks: DisplayTasks): String? {
        val others = synchronized(this) { leases.filterKeys { it != session }.mapValues { it.value.target.displayId } }
        val busy = others.entries.firstOrNull { (s, d) -> d in tasks.displaysOf(pkg) && isActive(s) } ?: return null
        Log.i(TAG, "$session wants $pkg, which ${busy.key} is using on display ${busy.value}")
        return "Another running task is using this app right now (on its own screen). An app can't be in two tasks at once: " +
            "wait for that task to finish, or tell the user."
    }

    /** Give up displays idle longer than [keepIdleMs]. */
    @Synchronized fun sweep() {
        val now = SystemClock.uptimeMillis()
        leases.entries.filter { it.value.idleSince?.let { t -> now - t > keepIdleMs } == true }.map { it.key }.forEach(::release)
    }

    /** The helper went away: its displays are gone with it. */
    @Synchronized fun helperGone() { leases.clear(); publish() }

    private fun setActive(display: Int, active: Boolean) {
        runCatching { shell.service()?.setDisplayActive(display, active) }.onFailure { Log.w(TAG, "display $display active=$active: $it") }
    }

    /** Display ids in use, by session (for previews). */
    fun displays(): Map<String, Int> = leases.mapValues { it.value.target.displayId }

    private class HelperIo(private val h: IShellService, private val display: Int) : DisplayIo {
        private inline fun <T> call(default: T, f: () -> T): T = try { f() } catch (e: RemoteException) { default }
        override fun tap(x: Float, y: Float, durationMs: Long) = call(false) { h.tap(display, x, y, durationMs) }
        override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = call(false) { h.swipe(display, x1, y1, x2, y2, durationMs) }
        override fun drag(x1: Float, y1: Float, x2: Float, y2: Float, moveMs: Long, holdMs: Long) = call(false) { h.drag(display, x1, y1, x2, y2, moveMs, holdMs) }
        override fun key(keyCode: Int) = call(false) { h.key(display, keyCode) }
        override fun screenshot(maxWidth: Int, quality: Int): ByteArray = call(ByteArray(0)) { h.screenshot(display, maxWidth, quality) }
        override fun startActivity(component: String): String = call("Error: the shell helper is gone") { h.startActivity(display, component) }
        override fun tasks(): DisplayTasks? = call(null) { DisplayTasks.parse(h.activities()) }
        override fun moveTask(taskId: Int): Boolean = call(false) { h.moveTask(taskId, display) }
        override fun typingDisplay(): Int = try { h.typingDisplay(display) } catch (e: Exception) { Log.w(TAG, "no typing display: $e"); -1 }
        override fun moveTaskTo(taskId: Int, display: Int): Boolean = call(false) { h.moveTask(taskId, display) }
        override fun removeTask(taskId: Int): Boolean = call(false) { h.removeTask(taskId) }
        override fun killApp(pkg: String): Boolean = call(false) { h.killApp(pkg) }
        override fun isRunning(pkg: String): Boolean = call(true) { h.isRunning(pkg) } // unknown: keep it alive
    }

    companion object {
        private const val TAG = "Screens"
        /** 360 dp wide at 320 dpi, like a phone: apps lay out as on the real screen, at a third of its pixels. */
        const val WIDTH = 720
        const val HEIGHT = 1600
        const val DPI = 320
    }
}
