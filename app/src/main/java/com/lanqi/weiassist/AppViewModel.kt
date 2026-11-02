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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The main screen's view of the agent. The agent itself lives in [AgentService]; this binds to it,
 * exposes its state, forwards actions, and keeps only what belongs to the screen (the text being typed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val controller = MutableStateFlow<AgentController?>(null)
    private val connection: ServiceConnection = AgentService.bind(app) { controller.value = it }

    val state: StateFlow<AgentState> = controller
        .flatMapLatest { it?.state ?: flowOf(AgentState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentState())

    /** One-shot browser requests from Pi's sign-in. */
    val openInBrowser = controller.flatMapLatest { it?.openInBrowser ?: emptyFlow() }

    // screen-local input
    var input by mutableStateOf(prefs.getString("input", null) ?: "你好")
    /** Give Pi the phone tools (needs the accessibility service). */
    var phoneMode by mutableStateOf(prefs.getBoolean("phoneMode", false))
    var useMock by mutableStateOf(false)

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
    fun newConversation() = withAgent { newConversation() }
    fun stop() = withAgent { stop() }

    fun send() = withAgent {
        prefs.edit().putBoolean("phoneMode", phoneMode).putString("input", input).apply()
        send(input, phoneTools = phoneMode, mockBaseUrl = if (useMock) MOCK_BASE_URL else null)
    }

    // A running task keeps the service started (foreground); unbinding only drops this screen's link.
    override fun onCleared() = getApplication<Application>().unbindService(connection)

    companion object {
        /** tools/mock-openai.mjs on the computer, reached through `adb reverse tcp:8787 tcp:8787`. */
        const val MOCK_BASE_URL = "http://127.0.0.1:8787/v1"
    }
}
