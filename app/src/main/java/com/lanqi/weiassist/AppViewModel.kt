package com.lanqi.weiassist

import android.app.Application
import android.content.Context
import android.content.ServiceConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanqi.weiassist.agent.AgentController
import com.lanqi.weiassist.agent.AgentService
import com.lanqi.weiassist.agent.AgentState
import com.lanqi.weiassist.agent.AuthMethod
import com.lanqi.weiassist.agent.SessionState
import com.lanqi.weiassist.agent.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The screens' view of the agent. The agent and its sessions live in [AgentService]; this binds to it,
 * exposes its state, forwards actions, and keeps only what belongs to the screens (text being typed,
 * which session is open).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val controller = MutableStateFlow<AgentController?>(null)
    private val connection: ServiceConnection = AgentService.bind(app) { controller.value = it }

    val state: StateFlow<AgentState> = controller
        .flatMapLatest { it?.state ?: flowOf(AgentState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentState())

    val sessions: StateFlow<List<SessionSummary>> = controller
        .flatMapLatest { it?.sessions?.summaries ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The session on screen, or null for the session list. */
    val openId = MutableStateFlow<String?>(null)

    /**
     * The open session's live state. While collected, the session is held in memory ([SessionManager.attach]);
     * when the screen goes away (or another session opens) it is released and, if idle, leaves memory.
     */
    val session: StateFlow<SessionState?> = combine(controller, openId) { c, id -> c to id }
        .flatMapLatest { (c, id) ->
            if (c == null || id == null) flowOf<SessionState?>(null)
            else flow<SessionState?> {
                val live = c.sessions.attach(id)
                try { if (live == null) emit(null) else emitAll(live) } finally { c.sessions.detach(id) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), null)

    /** One-shot browser requests from Pi's sign-in. */
    val openInBrowser = controller.flatMapLatest { it?.openInBrowser ?: emptyFlow() }

    // screen-local input
    var input by mutableStateOf(prefs.getString("input", null) ?: app.getString(R.string.default_input))
    /** Reply box on a session's page. */
    var reply by mutableStateOf("")
    /** New tasks may operate the phone (needs the accessibility service). */
    var phoneMode by mutableStateOf(prefs.getBoolean("phoneMode", false))
    var useMock by mutableStateOf(false)
        private set

    fun setMock(on: Boolean) { useMock = on; controller.value?.mockBaseUrl = if (on) MOCK_BASE_URL else null }

    /** Enabled in system settings: true at once on launch, before the service has (re)connected. */
    private val enabledInSettings = run {
        val am = app.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == app.packageName }
    }
    val accessibilityOn: StateFlow<Boolean> = com.lanqi.weiassist.phone.AgentAccessibilityService.running
        .map { it || enabledInSettings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, enabledInSettings)

    private fun withAgent(f: AgentController.() -> Unit) { controller.value?.f() }

    fun signIn(method: AuthMethod) = withAgent { signIn(method) }
    fun submitPrompt(value: String) = withAgent { submitPrompt(value) }
    fun reopenBrowser() = withAgent { reopenBrowser() }
    fun cancelSignIn() = withAgent { cancelSignIn() }
    fun signOut() = withAgent { signOut() }
    fun selectModel(id: String) = withAgent { selectModel(id) }
    fun selectThinking(level: String) = withAgent { selectThinking(level) }
    fun debugCrashRuntime() { if (BuildConfig.DEBUG) withAgent { debugCrashRuntime() } }

    // ---- sessions ---------------------------------------------------------------------------------
    fun open(id: String?) { openId.value = id; reply = "" }

    /** Start the typed task in a new session and open it. */
    fun startTask() {
        val c = controller.value ?: return
        val text = input.trim().ifEmpty { return }
        prefs.edit().putBoolean("phoneMode", phoneMode).putString("input", input).apply()
        viewModelScope.launch { open(c.startTask(text, phoneMode)) }
    }

    /** A follow-up message in the open session. */
    fun sendReply() {
        val c = controller.value ?: return
        val id = openId.value ?: return
        val text = reply.trim().ifEmpty { return }
        viewModelScope.launch { if (c.sessions.send(id, text)) reply = "" }
    }

    fun stop(id: String) = withAgent { stop(id) }
    fun pause(id: String) = withAgent { sessions.pause(id) }
    fun resume(id: String) = withAgent { sessions.resume(id) }
    fun continueTask(id: String) { val c = controller.value ?: return; viewModelScope.launch { c.sessions.continueTask(id) } }
    fun rename(id: String, title: String) = withAgent { sessions.rename(id, title) }
    fun delete(id: String) {
        if (openId.value == id) open(null)
        withAgent { sessions.delete(id) }
    }
    fun stopAll() = withAgent { stopAll() }

    // A running task keeps the service started (foreground); unbinding only drops this screen's link.
    override fun onCleared() = getApplication<Application>().unbindService(connection)

    companion object {
        /** tools/mock-openai.mjs on the computer, reached through `adb reverse tcp:8787 tcp:8787`. */
        const val MOCK_BASE_URL = "http://127.0.0.1:8787/v1"
    }
}
