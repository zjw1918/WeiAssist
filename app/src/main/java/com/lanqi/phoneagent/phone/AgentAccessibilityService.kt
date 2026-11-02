package com.lanqi.phoneagent.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * The agent's eyes and hands in other apps. The user turns it on in Settings → Accessibility;
 * Android then lets it read the window content of the app in front and perform actions on it.
 * Pi's tools (pi-host/src/phone-tools.ts) reach it through PiEngine → PhoneActions.
 */
class AgentAccessibilityService : AccessibilityService() {

    /** Elements from the latest read_screen, by the id the model sees. */
    private val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() { instance = this; running.value = true; android.util.Log.d("AgentA11y", "connected") }
    override fun onDestroy() {
        // The system may start a new instance before the old one is destroyed; only the current one clears.
        if (instance === this) { instance = null; running.value = false }
        super.onDestroy()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun ownAppInFront() = rootInActiveWindow?.packageName == packageName

    // ---- eyes -------------------------------------------------------------------------------------
    fun readScreen(): String {
        nodes.clear()
        val root = rootInActiveWindow ?: return "(screen not readable right now; wait and try again)"
        // Never let the agent operate 小维助手 itself (its Stop / sign-out buttons, its own settings).
        if (root.packageName == packageName) return OWN_APP
        val out = StringBuilder("app: ${root.packageName}\n")
        var next = 1
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (!n.isVisibleToUser) return
            val text = n.text?.toString()?.replace("\n", " ")?.take(120).orEmpty()
            val desc = n.contentDescription?.toString()?.replace("\n", " ")?.take(80).orEmpty()
            val hint = n.hintText?.toString().orEmpty()
            val useful = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || text.isNotEmpty() || desc.isNotEmpty()
            if (useful) {
                val id = next++
                nodes[id] = n
                val flags = buildList {
                    if (n.isClickable) add("clickable")
                    if (n.isEditable) add("editable")
                    if (n.isScrollable) add("scrollable")
                    if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                    if (n.isFocused) add("focused")
                    n.viewIdResourceName?.substringAfter(":id/")?.let { add("id=$it") }
                }.joinToString(" ")
                out.append("[$id] ").append(n.className?.toString()?.substringAfterLast('.') ?: "View")
                if (text.isNotEmpty()) out.append(" \"").append(text).append('"')
                if (desc.isNotEmpty() && desc != text) out.append(" (").append(desc).append(')')
                if (hint.isNotEmpty() && hint != text) out.append(" hint=\"").append(hint).append('"')
                out.append(' ').append(flags).append('\n')
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out.toString()
    }

    // ---- hands ------------------------------------------------------------------------------------
    private fun node(id: Int): AccessibilityNodeInfo {
        val n = nodes[id] ?: error("no element [$id]; call read_screen again")
        check(n.packageName != packageName && rootInActiveWindow?.packageName != packageName) { OWN_APP }
        return n
    }

    suspend fun tap(id: Int): String {
        val n = node(id)
        var target: AccessibilityNodeInfo? = n
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "tapped [$id]"
        // Not clickable through accessibility: tap its centre like a finger would.
        val r = Rect().also(n::getBoundsInScreen)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val done = CompletableDeferred<Boolean>()
        val sent = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { done.complete(true) }
                override fun onCancelled(g: GestureDescription?) { done.complete(false) }
            }, null)
        if (!sent) return "tap on [$id] failed: gesture not accepted"
        return if (kotlinx.coroutines.withTimeoutOrNull(3_000) { done.await() } == true) "tapped [$id] at ${r.centerX()},${r.centerY()}" else "tap on [$id] failed"
    }

    fun typeText(id: Int, value: String): String {
        val n = node(id)
        if (!n.isEditable) return "[$id] is not editable"
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "typed into [$id]" else "could not type into [$id]"
    }

    fun scroll(id: Int, forward: Boolean): String {
        val n = if (id > 0) node(id) else nodes.values.firstOrNull { it.isScrollable } ?: return "nothing scrollable"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (n.performAction(action)) "scrolled" else "could not scroll"
    }

    fun pressKey(key: String): String = when (key) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }.let { if (it) "pressed $key" else "could not press $key" }

    fun openApp(intent: Intent) {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    // ---- asking the user --------------------------------------------------------------------------
    /**
     * A confirmation card drawn on top of whatever app is in front. Accessibility services may add
     * TYPE_ACCESSIBILITY_OVERLAY windows without the "display over other apps" permission.
     */
    suspend fun confirm(title: String, detail: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 24 * dp
            val pad = (20 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { text = title; textSize = 20f; setTextColor(Color.BLACK) })
            addView(TextView(context).apply { text = detail; textSize = 15f; setTextColor(Color.DKGRAY); setPadding(0, pad / 2, 0, pad / 2) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(Button(context).apply { text = "取消"; setOnClickListener { answer.complete(false) } })
                addView(Button(context).apply { text = "允许"; setOnClickListener { answer.complete(true) } })
            })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(card, lp)
        return try { answer.await() } finally { wm.removeView(card); delay(150) }
    }

    companion object {
        private const val OWN_APP =
            "小维助手 itself is in front (the user is looking at it). Do not operate it. " +
                "Use open_app to go back to the app you were working in, or stop and report to the user."

        /** The running service, or null when the user hasn't turned it on. */
        @Volatile var instance: AgentAccessibilityService? = null
            private set
        /** Observable by the UI (Compose state), so the "turn on accessibility" hint updates live. */
        val running = androidx.compose.runtime.mutableStateOf(false)
    }
}
