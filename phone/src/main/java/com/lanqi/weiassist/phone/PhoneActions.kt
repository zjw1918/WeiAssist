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

    suspend fun run(m: JSONObject): String {
        val action = m.getString("action")
        android.util.Log.d(TAG, "$action start")
        return runAction(m).also { android.util.Log.d(TAG, "$action → ${it.lineSequence().firstOrNull()?.take(60)}") }
    }

    private suspend fun runAction(m: JSONObject): String {
        // Right after an update or reboot the system reconnects the service a moment later: wait briefly.
        var svc = AgentAccessibilityService.instance
        var waited = 0
        while (svc == null && waited < 3_000) { kotlinx.coroutines.delay(100); waited += 100; svc = AgentAccessibilityService.instance }
        if (svc == null) return "Accessibility is off: ask the user to turn on this assistant in Settings → Accessibility."
        // The user opened 小维助手 to look at progress: pause instead of taking the screen back.
        // (open_app is exempt: it is how a task leaves 小维助手 in the first place.)
        val gen = generation.get()
        while (m.getString("action") !in setOf("open_app", "label_at") && svc.ownAppInFront()) {
            if (generation.get() != gen) return "stopped by the user"
            kotlinx.coroutines.delay(300)
        }
        val a = m.optJSONObject("args") ?: JSONObject()
        val action = m.getString("action")
        return kotlinx.coroutines.withTimeoutOrNull(15_000) { act(svc, action, a) } ?: "$action timed out"
    }

    private suspend fun act(svc: AgentAccessibilityService, action: String, a: JSONObject): String {
        return when (action) {
            "read_screen" -> svc.readScreen()
            "tap" -> svc.tap(a.getInt("id")).settled(svc)
            "tap_at" -> svc.tapAt(a.getInt("x"), a.getInt("y")).settled(svc)
            "long_press" -> svc.longPress(a.getInt("id")).settled(svc)
            "type_text" -> svc.typeText(a.getInt("id"), a.getString("text")).settled(svc)
            "scroll" -> svc.scroll(a.optInt("id", -1), a.getString("direction") == "forward").settled(svc)
            "swipe" -> svc.swipe(a.getString("direction"), a.optDouble("fraction", 0.6).toFloat()).settled(svc)
            "press_key" -> svc.pressKey(a.getString("key")).settled(svc)
            "open_app" -> openApp(svc, a.getString("app")).settled(svc, maxMs = 5_000)
            "screenshot" -> svc.screenshot()
            "label_at" -> svc.labelAt(a.getInt("x"), a.getInt("y"))
            else -> "unknown action"
        }
    }

    /** Every action returns only once the screen has settled, so the next read sees the result. */
    private suspend fun String.settled(svc: AgentAccessibilityService, maxMs: Long = 3_000) = also { svc.awaitIdle(maxMs = maxMs) }

    private fun openApp(svc: AgentAccessibilityService, app: String): String {
        val pm = context.packageManager
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL,
        )
        val q = app.trim().lowercase()
        val hit = launchers.firstOrNull { it.activityInfo.packageName.lowercase() == q }
            ?: launchers.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: launchers.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "No installed app matches \"$app\"."
        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "\"$app\" can't be launched."
        svc.openApp(intent)
        return "opened ${hit.loadLabel(pm)} (${hit.activityInfo.packageName}); call read_screen to see it"
    }

    fun isEnabled() = AgentAccessibilityService.instance != null

    private companion object { const val TAG = "PhoneActions" }
}
