package com.lanqi.weiassist.phone

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

    /** Elements from the latest read_screen of each display, by the id the model sees (one session per display). */
    private val nodesByDisplay = java.util.concurrent.ConcurrentHashMap<Int, Map<Int, AccessibilityNodeInfo>>()

    override fun onServiceConnected() { instance = this; _running.value = true; android.util.Log.d("AgentA11y", "connected") }
    /** Confirmation cards on screen now, with their answers. */
    private val openCards = java.util.concurrent.ConcurrentHashMap<android.view.View, CompletableDeferred<Boolean>>()

    override fun onDestroy() {
        // The service is going (turned off, or unbound by the system, e.g. while UI automation runs): a card
        // nobody can answer any more counts as "no", or the task waiting for it would wait forever.
        openCards.forEach { (card, answer) ->
            runCatching { getSystemService(WindowManager::class.java).removeView(card) }
            answer.complete(false)
        }
        openCards.clear()
        // The system may start a new instance before the old one is destroyed; only the current one clears.
        if (instance === this) { instance = null; _running.value = false }
        super.onDestroy()
    }
    /** Time of the last UI change event per display, for [awaitIdle]. */
    private val lastUiEvent = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    /** Which display each window is on (events only name their window). */
    private val windowDisplay = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName == packageName) return
        val display = displayOfWindow(event.windowId)
        lastUiEvent[display] = android.os.SystemClock.uptimeMillis()
    }

    private fun displayOfWindow(windowId: Int): Int {
        if (windowId < 0) return 0
        windowDisplay[windowId]?.let { return it }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val all = windowsOnAllDisplays
            for (i in 0 until all.size()) all.valueAt(i).forEach { w -> windowDisplay[w.id] = all.keyAt(i) }
            if (windowDisplay.size > 500) windowDisplay.clear() // window ids are not reused often; keep it small
        }
        return windowDisplay[windowId] ?: 0
    }

    /** After an action: wait until [display] has stopped changing (quiet for [quietMs]), at most [maxMs]. */
    suspend fun awaitIdle(display: Int = 0, quietMs: Long = 450, maxMs: Long = 3_000) {
        val start = android.os.SystemClock.uptimeMillis()
        delay(150) // let the action's first events arrive
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - (lastUiEvent[display] ?: 0L) >= quietMs || now - start >= maxMs) return
            delay(100)
        }
    }

    /** The top app window's content on [display] (a virtual one: no active window there for the system). */
    private fun rootOf(display: Int): AccessibilityNodeInfo? {
        if (display == 0) return rootInActiveWindow
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val windows = windowsOnAllDisplays.get(display) ?: return null
        // Top first; a dialog is its own application window above the activity.
        return windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }?.root
    }
    /** Packages with an application window on [display] whose content can be read (top first). */
    fun appWindowPackages(display: Int): List<String> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        val windows = windowsOnAllDisplays.get(display) ?: return emptyList()
        return windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.packageName?.toString() }
    }

    override fun onInterrupt() {}

    fun ownAppInFront() = rootInActiveWindow?.packageName == packageName

    private fun isMain(display: Int) = display == 0

    // ---- eyes -------------------------------------------------------------------------------------
    /** [width]/[height]: the display's size (the phone's own screen when null). */
    fun readScreen(display: Int = 0, width: Int? = null, height: Int? = null): String {
        val nodes = HashMap<Int, AccessibilityNodeInfo>()
        nodesByDisplay[display] = nodes
        val root = rootOf(display) ?: return if (isMain(display)) "(screen not readable right now; wait and try again)"
            else "(nothing on this task's screen yet: use open_app)"
        // Never let the agent operate 小维助手 itself (its Stop / sign-out buttons, its own settings).
        if (root.packageName == packageName) return OWN_APP
        val size = resources.displayMetrics
        val out = StringBuilder("app: ${root.packageName}  screen: ${width ?: size.widthPixels}x${height ?: size.heightPixels}\n")
        var next = 1
        var omitted = 0
        val bounds = Rect()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (!n.isVisibleToUser) return
            // Password fields never leave the phone: the model only learns that one is there.
            val text = if (n.isPassword) (if (n.text.isNullOrEmpty()) "" else "•••• (hidden password)")
            else n.text?.toString()?.replace("\n", " ")?.take(120).orEmpty()
            val desc = n.contentDescription?.toString()?.replace("\n", " ")?.take(80).orEmpty()
            val hint = n.hintText?.toString().orEmpty()
            val useful = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || text.isNotEmpty() || desc.isNotEmpty()
            if (useful && next > MAX_ELEMENTS) omitted++
            if (useful && next <= MAX_ELEMENTS) {
                val id = next++
                nodes[id] = n
                val flags = buildList {
                    if (n.isClickable) add("clickable")
                    if (n.isEditable) add("editable")
                    if (n.isScrollable) add("scrollable")
                    if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                    if (n.isFocused) add("focused")
                    if (n.isPassword) add("password")
                    n.viewIdResourceName?.substringAfter(":id/")?.let { add("id=$it") }
                }.joinToString(" ")
                out.append("[$id] ").append(n.className?.toString()?.substringAfterLast('.') ?: "View")
                if (text.isNotEmpty()) out.append(" \"").append(text).append('"')
                if (desc.isNotEmpty() && desc != text) out.append(" (").append(desc).append(')')
                if (hint.isNotEmpty() && hint != text) out.append(" hint=\"").append(hint).append('"')
                n.getBoundsInScreen(bounds)
                out.append(' ').append(flags).append(" @").append(bounds.centerX()).append(',').append(bounds.centerY()).append('\n')
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        if (omitted > 0) out.append("(… $omitted more elements not listed; scroll or use screenshot)\n")
        return out.toString()
    }

    // ---- hands ------------------------------------------------------------------------------------
    private fun node(id: Int, display: Int = 0): AccessibilityNodeInfo {
        val n = nodesByDisplay[display]?.get(id) ?: error("no element [$id]; call read_screen again")
        check(n.packageName != packageName && (!isMain(display) || rootInActiveWindow?.packageName != packageName)) { OWN_APP }
        return n
    }

    /** The centre of element [id] (from the latest read_screen of [display]). */
    fun centerOf(id: Int, display: Int): Pair<Float, Float> = Rect().also(node(id, display)::getBoundsInScreen).let { it.exactCenterX() to it.exactCenterY() }

    /** [gesture]: how to tap a point on [display] when the element can't be clicked through accessibility. */
    suspend fun tap(id: Int, display: Int = 0, gesture: (suspend (Float, Float) -> Boolean)? = null): String {
        val n = node(id, display)
        var target: AccessibilityNodeInfo? = n
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "tapped [$id]"
        // Not clickable through accessibility: tap its centre like a finger would.
        val r = Rect().also(n::getBoundsInScreen)
        if (gesture != null) return if (gesture(r.exactCenterX(), r.exactCenterY())) "tapped [$id] at ${r.centerX()},${r.centerY()}" else "tap on [$id] failed"
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

    fun typeText(id: Int, value: String, display: Int = 0): String {
        val n = node(id, display)
        if (!n.isEditable) return "[$id] is not editable"
        // On a virtual display, focusing would bring up the keyboard on the user's screen: set the text only.
        if (isMain(display)) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "typed into [$id]" else "could not type into [$id]"
    }

    /**
     * Submit a text field, like the keyboard's Enter / Go / Search key (Android 11+): element [id], or the
     * focused text field when [id] is null. False if there is none or the app ignored it.
     */
    fun imeEnter(id: Int?, display: Int = 0): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        val n = if (id != null) node(id, display) else focusedInput(display) ?: return false
        n.refresh()
        return n.isEditable && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** The text field that has input focus on [display], if any. */
    private fun focusedInput(display: Int): AccessibilityNodeInfo? =
        rootOf(display)?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && it.packageName != packageName }

    fun scroll(id: Int, forward: Boolean, display: Int = 0): String {
        val n = if (id > 0) node(id, display) else nodesByDisplay[display]?.values?.firstOrNull { it.isScrollable } ?: return "nothing scrollable"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (n.performAction(action)) "scrolled" else "could not scroll"
    }

    fun pressKey(key: String): String = when (key) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }.let { if (it) "pressed $key" else "could not press $key" }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean {
        val done = CompletableDeferred<Boolean>()
        val sent = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { done.complete(true) }
                override fun onCancelled(g: GestureDescription?) { done.complete(false) }
            }, null)
        return sent && kotlinx.coroutines.withTimeoutOrNull(durationMs + 3_000) { done.await() } == true
    }

    private fun checkNotOwnApp() = check(rootInActiveWindow?.packageName != packageName) { OWN_APP }

    /** Tap a screen point, like a finger (for things read_screen can't name; see screenshot). */
    suspend fun tapAt(x: Int, y: Int): String {
        checkNotOwnApp()
        return if (gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 60)) "tapped at $x,$y" else "tap at $x,$y failed"
    }

    suspend fun longPress(id: Int): String {
        val r = Rect().also(node(id)::getBoundsInScreen)
        return if (gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 700)) "long-pressed [$id]" else "long press failed"
    }

    /** Swipe the content: "up" shows what is below (like scrolling down a feed), "down" what is above. */
    suspend fun swipe(direction: String, fraction: Float): String {
        checkNotOwnApp()
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val f = fraction.coerceIn(0.2f, 0.8f)
        val (x1, y1, x2, y2) = when (direction) {
            "up" -> listOf(w / 2, h * (0.5f + f / 2), w / 2, h * (0.5f - f / 2))
            "down" -> listOf(w / 2, h * (0.5f - f / 2), w / 2, h * (0.5f + f / 2))
            "left" -> listOf(w * (0.5f + f / 2), h / 2, w * (0.5f - f / 2), h / 2)
            "right" -> listOf(w * (0.5f - f / 2), h / 2, w * (0.5f + f / 2), h / 2)
            else -> return "direction must be up, down, left or right"
        }
        val ok = gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 350)
        return if (ok) "swiped $direction" else "swipe failed"
    }

    /** What a tap at (x, y) on [display] would hit: the text of the clickable element there (for the confirm hook). */
    fun labelAt(x: Int, y: Int, display: Int = 0): String {
        val root = rootOf(display) ?: return ""
        val r = Rect()
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            n.getBoundsInScreen(r)
            if (!r.contains(x, y) || !n.isVisibleToUser) return null
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { c -> find(c)?.let { return it } }
            return n
        }
        var hit = find(root)
        // Collect the words on the tapped element and its clickable ancestor.
        val words = mutableListOf<String>()
        while (hit != null) {
            listOfNotNull(hit.text?.toString(), hit.contentDescription?.toString()).filter { it.isNotBlank() }.forEach(words::add)
            if (hit.isClickable) break
            hit = hit.parent
        }
        return words.firstOrNull { it.length <= 20 } ?: words.firstOrNull().orEmpty()
    }

    /** A JPEG of the screen, scaled to [maxWidth], as base64; or an explanation when not allowed. */
    suspend fun screenshot(maxWidth: Int = 720): String {
        checkNotOwnApp()
        if (android.os.Build.VERSION.SDK_INT < 30) return "error: screenshots need Android 11+"
        val result = CompletableDeferred<android.graphics.Bitmap?>()
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(s: ScreenshotResult) {
                val hw = android.graphics.Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                result.complete(hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false))
                s.hardwareBuffer.close()
            }
            // e.g. a secure window (payment, password manager): the system refuses, and that is right.
            override fun onFailure(errorCode: Int) { result.complete(null) }
        })
        val full = kotlinx.coroutines.withTimeoutOrNull(5_000) { result.await() }
            ?: return "error: the screen can't be captured (protected window, or too soon after the last screenshot)"
        val scale = minOf(1f, maxWidth.toFloat() / full.width)
        val small = android.graphics.Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
        val bytes = java.io.ByteArrayOutputStream().also { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

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
                addView(Button(context).apply { text = getString(R.string.confirm_cancel); setOnClickListener { answer.complete(false) } })
                addView(Button(context).apply { text = getString(R.string.confirm_allow); setOnClickListener { answer.complete(true) } })
            })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(card, lp)
        openCards[card] = answer
        return try { answer.await() } finally {
            if (openCards.remove(card) != null) runCatching { wm.removeView(card) }
            delay(150)
        }
    }

    companion object {
        private const val MAX_ELEMENTS = 220
        private const val OWN_APP =
            "The assistant app itself is in front (the user is looking at it). Do not operate it. " +
                "Use open_app to go back to the app you were working in, or stop and report to the user."

        /** The running service, or null when the user hasn't turned it on. */
        @Volatile var instance: AgentAccessibilityService? = null
            private set
        private val _running = kotlinx.coroutines.flow.MutableStateFlow(false)
        /** Whether the service is connected; observed by the UI so the "turn on accessibility" hint updates live. */
        val running: kotlinx.coroutines.flow.StateFlow<Boolean> = _running
    }
}
