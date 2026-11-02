package com.lanqi.phoneagent.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject

/** Runs one Pi phone-tool request (see pi-host/src/phone-tools.ts) against the accessibility service. */
class PhoneActions(private val context: Context) {

    suspend fun run(m: JSONObject): String {
        val svc = AgentAccessibilityService.instance
            ?: return "Accessibility is off: ask the user to turn on 小维助手 in Settings → Accessibility."
        val a = m.optJSONObject("args") ?: JSONObject()
        return when (m.getString("action")) {
            "read_screen" -> svc.readScreen()
            "tap" -> svc.tap(a.getInt("id"))
            "type_text" -> svc.typeText(a.getInt("id"), a.getString("text"))
            "scroll" -> svc.scroll(a.optInt("id", -1), a.getString("direction") == "forward")
            "press_key" -> svc.pressKey(a.getString("key"))
            "open_app" -> openApp(svc, a.getString("app"))
            else -> "unknown action"
        }
    }

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
}
