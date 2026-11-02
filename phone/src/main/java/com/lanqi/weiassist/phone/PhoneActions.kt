package com.lanqi.weiassist.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject

/** Runs one Pi phone-tool request (see pi-host/src/phone-tools.ts) against the accessibility service. */
class PhoneActions(private val context: Context) {
    /** Bumped by [cancelWaits] (the user pressed Stop) so a paused action gives up. */
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    fun cancelWaits() { generation.incrementAndGet() }

    /**
     * [paused]: the session was paused while this action waited (the user looking at the app); the
     * action then gives way, and Pi's gate holds the session before its next one.
     */
    suspend fun run(m: JSONObject, target: Target = Target.Main, paused: () -> Boolean = { false }): String {
        val action = m.getString("action")
        android.util.Log.d(TAG, "$action start (display ${target.displayId})")
        return runAction(m, target, paused).also { android.util.Log.d(TAG, "$action → ${it.lineSequence().firstOrNull()?.take(60)}") }
    }

    private suspend fun runAction(m: JSONObject, target: Target, paused: () -> Boolean): String {
        // Right after an update or reboot the system reconnects the service a moment later: wait briefly.
        var svc = AgentAccessibilityService.instance
        var waited = 0
        while (svc == null && waited < 3_000) { kotlinx.coroutines.delay(100); waited += 100; svc = AgentAccessibilityService.instance }
        if (svc == null) return "Accessibility is off: ask the user to turn on this assistant in Settings → Accessibility."
        // The user opened 小维助手 to look at progress: pause instead of taking the screen back.
        // (open_app is exempt: it is how a task leaves 小维助手 in the first place.)
        val gen = generation.get()
        // (A session on a virtual display has a screen of its own: the user looking at the app is no reason to wait.)
        while (target is Target.Main && m.getString("action") !in setOf("open_app", "label_at") && svc.ownAppInFront()) {
            if (generation.get() != gen) return "stopped by the user"
            if (paused()) return "paused by the user before this action; do it again when you continue"
            kotlinx.coroutines.delay(300)
        }
        val a = m.optJSONObject("args") ?: JSONObject()
        val action = m.getString("action")
        // open_app may wait for a slow app to start (see openOnDisplay), then for its screen to settle.
        return kotlinx.coroutines.withTimeoutOrNull(if (action == "open_app") 30_000 else 15_000) {
            when (target) {
                Target.Main -> act(svc, action, a)
                is Target.Virtual -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { actVirtual(svc, target, action, a) }
            }
        } ?: "$action timed out"
    }

    /** The same actions on a session's own virtual display: reading and node actions through accessibility, input through the shell helper. */
    private suspend fun actVirtual(svc: AgentAccessibilityService, t: Target.Virtual, action: String, a: JSONObject): String {
        val d = t.displayId
        // After an action that may open a window, bring back any task the session's apps opened on the
        // phone's own screen (see reclaim), then wait for this display to settle.
        suspend fun String.settled(maxMs: Long = 3_000) = also { svc.awaitIdle(d, maxMs = maxMs) }.let { r ->
            if (action in LAUNCHING && t.apps.isNotEmpty()) reclaim(t)?.let { "$r\n$it" } ?: r else r
        }
        return when (action) {
            "read_screen" -> svc.readScreen(d, t.width, t.height)
            "tap" -> svc.tap(a.getInt("id"), d) { x, y -> t.io.tap(x, y, 60) }.settled()
            "tap_at" -> (if (t.io.tap(a.getInt("x").toFloat(), a.getInt("y").toFloat(), 60)) "tapped at ${a.getInt("x")},${a.getInt("y")}" else "tap failed").settled()
            "long_press" -> svc.centerOf(a.getInt("id"), d).let { (x, y) -> if (t.io.tap(x, y, 700)) "long-pressed [${a.getInt("id")}]" else "long press failed" }.settled()
            "type_text" -> svc.typeText(a.getInt("id"), a.getString("text"), d).let { typed ->
                if (!a.optBoolean("submit") || !typed.startsWith("typed")) typed else "$typed; ${enterVirtual(svc, t, a.getInt("id"))}"
            }.settled()
            "scroll" -> svc.scroll(a.optInt("id", -1), a.getString("direction") == "forward", d).settled()
            "swipe" -> swipeVirtual(t, a.getString("direction"), a.optDouble("fraction", 0.6).toFloat()).settled()
            "press_key" -> when (a.getString("key")) {
                "back" -> (if (t.io.key(android.view.KeyEvent.KEYCODE_BACK)) "pressed back" else "could not press back").settled()
                "enter" -> enterVirtual(svc, t, null).settled()
                else -> "This task runs on a screen of its own, without a home screen or recent apps: use open_app to switch apps."
            }
            "open_app" -> openOnDisplay(svc, t, a.getString("app")).settled(maxMs = 5_000)
            "screenshot" -> t.io.screenshot(720, 70).let { if (it.isEmpty()) "error: no picture of this screen yet; try again" else android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            "label_at" -> svc.labelAt(a.getInt("x"), a.getInt("y"), d)
            else -> "unknown action"
        }
    }

    /**
     * Open an app on the session's own display. One app has one process, and single-task apps one window:
     * launching one that is elsewhere moves it here. So: never take an app another running task is using,
     * nor a single-task app the user has open in front of them; and check that it really came here.
     */
    private suspend fun openOnDisplay(svc: AgentAccessibilityService, t: Target.Virtual, app: String): String {
        val (label, component) = resolveApp(app).getOrElse { return it.message.orEmpty() }
        val pkg = component.substringBefore('/')
        val before = t.io.tasks()
        if (before != null) {
            t.guard(pkg, before)?.let { return it }
            if (before.resumed[0] == pkg && singleTask(component)) {
                return "$label is open on the user's own screen right now, and it can only have one window: opening it here would take it " +
                    "away from them. Tell the user, and ask them to switch away from $label (or to let you wait)."
            }
        }
        // The user's own tasks on the phone's screen, as they are now: never moved, unless our launch brings
        // one of them to the front of the user's screen (a single-task app reuses its task): see reclaim.
        before?.tasks?.filter { it.display == 0 }?.forEach { t.usersTasks += it.id }
        val hiddenBefore = before?.tasks?.filter { it.display == 0 && it.pkg == pkg && !it.visible }?.map { it.id }?.toSet().orEmpty()
        if (pkg !in t.apps && t.io.isRunning(pkg)) t.wereRunning += pkg
        t.apps += pkg
        val out = t.io.startActivity(component)
        if ("Error" in out) return "could not open $label: ${out.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()}"
        // Wait until the app is on this display and shows a window we can read: a cold start can take many
        // seconds, and before that the model would find an empty screen. Meanwhile, on an untrusted display
        // (Android 12), an app's splash may open its main page as a new task on the phone's screen: bring it here.
        val start = android.os.SystemClock.uptimeMillis()
        var after: DisplayTasks? = null
        var shown = false
        var i = 0
        while (true) {
            kotlinx.coroutines.delay(400)
            reclaim(t, surfaced = hiddenBefore)
            after = t.io.tasks()
            val here = after == null || t.displayId in after.displaysOf(pkg)
            shown = pkg in svc.appWindowPackages(t.displayId)
            val waited = android.os.SystemClock.uptimeMillis() - start
            // (At least ~1.2 s on the untrusted fallback, where the splash's new task shows up a moment later.)
            if (here && shown && (i >= 2 || trusted(t.displayId))) break
            if (!here && waited >= OPEN_ELSEWHERE_MS) break // it went to another screen (single-task, in use there)
            if (waited >= OPEN_WAIT_MS) break
            i++
        }
        val ms = android.os.SystemClock.uptimeMillis() - start
        android.util.Log.i(TAG, "open_app $pkg on display ${t.displayId}: shown=$shown after ${ms} ms")
        if (after != null && t.displayId !in after.displaysOf(pkg)) {
            return "$label opened, but on another screen (it can have only one window, which is in use there). Use another app or wait."
        }
        if (!shown) return "opened $label ($pkg), but it is still starting (nothing to read after ${ms / 1000} s); wait a few seconds, then call read_screen"
        return "opened $label ($pkg); call read_screen to see it"
    }

    /**
     * Tasks the session's apps opened on the phone's own screen by themselves (launched by the app, not by
     * the user's launcher, and not there before the session), and background tasks our launch brought to the
     * front of the user's screen ([surfaced]), are moved to the session's display.
     * Returns a note for the model when it moved something.
     */
    private fun reclaim(t: Target.Virtual, surfaced: Set<Int> = emptySet()): String? {
        val tasks = t.io.tasks() ?: return null
        val escaped = tasks.tasks.filter { task ->
            task.display == 0 && task.pkg in t.apps && (
                // already this session's (Android may move a task back when the app re-launches its activity)
                task.id in t.ownTasks ||
                // opened by the app itself after the session started
                (task.id !in t.usersTasks && (task.launchedFrom == task.pkg || task.launchedFrom == "com.android.shell")) ||
                    // or a background task of the user's that our launch just brought to the front of their screen
                    (task.id in surfaced && task.visible)
                )
        }
        if (escaped.isEmpty()) return null
        val moved = escaped.filter { t.io.moveTask(it.id) }
        moved.forEach { t.ownTasks += it.id }
        android.util.Log.i(TAG, "brought back to display ${t.displayId}: ${escaped.joinToString { "${it.pkg} #${it.id}" }} (moved ${moved.size})")
        return if (moved.isNotEmpty()) "(the app opened a new window; it was brought to this task's screen)" else null
    }

    /** Whether [displayId] is trusted (Display.FLAG_TRUSTED, hidden): there, apps' own launches stay on it. */
    private fun trusted(displayId: Int): Boolean =
        (context.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(displayId)?.flags ?: 0) and FLAG_TRUSTED != 0

    private fun singleTask(component: String): Boolean = runCatching {
        val info = context.packageManager.getActivityInfo(android.content.ComponentName.unflattenFromString(component)!!, 0)
        info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK || info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
            info.launchMode == 4 // LAUNCH_SINGLE_INSTANCE_PER_TASK (Android 12)
    }.getOrDefault(false)

    private fun swipeVirtual(t: Target.Virtual, direction: String, fraction: Float): String {
        val w = t.width.toFloat(); val h = t.height.toFloat(); val f = fraction.coerceIn(0.2f, 0.8f)
        val (x1, y1, x2, y2) = when (direction) {
            "up" -> listOf(w / 2, h * (0.5f + f / 2), w / 2, h * (0.5f - f / 2))
            "down" -> listOf(w / 2, h * (0.5f - f / 2), w / 2, h * (0.5f + f / 2))
            "left" -> listOf(w * (0.5f + f / 2), h / 2, w * (0.5f - f / 2), h / 2)
            "right" -> listOf(w * (0.5f - f / 2), h / 2, w * (0.5f + f / 2), h / 2)
            else -> return "direction must be up, down, left or right"
        }
        return if (t.io.swipe(x1, y1, x2, y2, 350)) "swiped $direction" else "swipe failed"
    }

    private suspend fun act(svc: AgentAccessibilityService, action: String, a: JSONObject): String {
        return when (action) {
            "read_screen" -> svc.readScreen()
            "tap" -> svc.tap(a.getInt("id")).settled(svc)
            "tap_at" -> svc.tapAt(a.getInt("x"), a.getInt("y")).settled(svc)
            "long_press" -> svc.longPress(a.getInt("id")).settled(svc)
            "type_text" -> svc.typeText(a.getInt("id"), a.getString("text")).let { typed ->
                if (!a.optBoolean("submit") || !typed.startsWith("typed")) typed
                else "$typed; " + if (svc.imeEnter(a.getInt("id"))) "pressed enter" else NO_ENTER
            }.settled(svc)
            "scroll" -> svc.scroll(a.optInt("id", -1), a.getString("direction") == "forward").settled(svc)
            "swipe" -> svc.swipe(a.getString("direction"), a.optDouble("fraction", 0.6).toFloat()).settled(svc)
            "press_key" -> (if (a.getString("key") == "enter") (if (svc.imeEnter(null)) "pressed enter" else NO_ENTER) else svc.pressKey(a.getString("key"))).settled(svc)
            "open_app" -> openApp(svc, a.getString("app")).settled(svc, maxMs = 5_000)
            "screenshot" -> svc.screenshot()
            "label_at" -> svc.labelAt(a.getInt("x"), a.getInt("y"))
            else -> "unknown action"
        }
    }

    /**
     * Enter on a session's own display: the field's own submit action (Enter / Go / Search), else an Enter key
     * on the display, which reaches the field the app has focused (the field the model tapped before typing).
     */
    private fun enterVirtual(svc: AgentAccessibilityService, t: Target.Virtual, id: Int?): String = when {
        svc.imeEnter(id, t.displayId) -> "pressed enter"
        t.io.key(android.view.KeyEvent.KEYCODE_ENTER) -> "pressed enter"
        else -> NO_ENTER
    }

    /** Every action returns only once the screen has settled, so the next read sees the result. */
    private suspend fun String.settled(svc: AgentAccessibilityService, maxMs: Long = 3_000) = also { svc.awaitIdle(maxMs = maxMs) }

    /** An installed app by name or package: its label and launcher component ("package/.Activity"). */
    private fun resolveApp(app: String): Result<Pair<String, String>> {
        val pm = context.packageManager
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL,
        )
        val q = app.trim().lowercase()
        val hit = launchers.firstOrNull { it.activityInfo.packageName.lowercase() == q }
            ?: launchers.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: launchers.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return Result.failure(IllegalArgumentException("No installed app matches \"$app\"."))
        val component = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)?.component
            ?: return Result.failure(IllegalArgumentException("\"$app\" can't be launched."))
        return Result.success(hit.loadLabel(pm).toString() to component.flattenToShortString())
    }

    private fun openApp(svc: AgentAccessibilityService, app: String): String {
        val (label, component) = resolveApp(app).getOrElse { return it.message.orEmpty() }
        val pkg = component.substringBefore('/')
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return "\"$app\" can't be launched."
        svc.openApp(intent)
        return "opened $label ($pkg); call read_screen to see it"
    }

    fun isEnabled() = AgentAccessibilityService.instance != null

    private companion object {
        const val TAG = "PhoneActions"
        const val NO_ENTER = "could not press enter: no text field has focus. Tap the field first, or tap the app's own Go / Search button or a suggestion"
        /** Actions after which an app may have opened a new window. */
        val LAUNCHING = setOf("tap", "tap_at", "long_press", "type_text", "press_key", "open_app")
        /** How long open_app waits for a starting app to show a window, and for it to arrive on the display at all. */
        const val OPEN_WAIT_MS = 15_000L
        const val OPEN_ELSEWHERE_MS = 3_000L
        const val FLAG_TRUSTED = 1 shl 7
    }
}
