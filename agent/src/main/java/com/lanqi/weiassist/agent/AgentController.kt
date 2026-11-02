package com.lanqi.weiassist.agent

import android.content.Context
import com.lanqi.weiassist.phone.AgentAccessibilityService
import com.lanqi.weiassist.phone.PhoneActions
import com.lanqi.weiassist.pi.PiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The agent, independent of any screen: owns the Pi runtime (PiEngine), turns Pi's messages into
 * one immutable [AgentState], and offers the actions the UI can take. Owned by [AgentService], so a
 * task keeps running when the user leaves the app.
 */
class AgentController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pi = PiEngine(appContext, agentName = appContext.getString(R.string.agent_name))
    private val phone = PhoneActions(appContext)
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        AgentState(
            modelId = prefs.getString("modelId", null) ?: AgentReducer.PI_DEFAULT_MODEL,
            thinkingLevel = prefs.getString("thinkingLevel", null) ?: "medium",
        ),
    )
    val state: StateFlow<AgentState> = _state.asStateFlow()

    /** One-shot: Pi wants this URL opened in a browser (sign-in). */
    private val _openInBrowser = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val openInBrowser: SharedFlow<String> = _openInBrowser

    /** Settings of the current Pi Agent. Same settings → keep the Agent, so it remembers the conversation. */
    private var agentConfig: String? = null

    init {
        // Pi's phone tools and its confirm hook (pi-host/src/phone-tools.ts) are served by the :phone module.
        pi.registerHostCall("phone") { phone.run(it) }
        pi.registerHostCall("confirm") { m ->
            val fields = m.optJSONArray("typed")
            val detail = if (fields == null || fields.length() == 0) appContext.getString(R.string.confirm_nothing_typed)
            else (0 until fields.length()).joinToString("\n") { fields.getString(it) }
            AgentAccessibilityService.instance?.confirm(appContext.getString(R.string.confirm_title, m.getString("label")), detail) ?: false
        }
        scope.launch { pi.messages.collect(::onPi) }
        scope.launch {
            val ready = pi.start()
            _state.update { it.copy(piVersion = ready.optString("pi")) }
        }
    }

    // ---- Pi → state (pure transitions in AgentReducer) ------------------------------------------
    private fun onPi(m: JSONObject) {
        val before = _state.value
        val (next, effect) = AgentReducer.reduce(before, m, ::friendly)
        _state.value = next
        if (next.modelId != before.modelId || next.thinkingLevel != before.thinkingLevel) persistModel()
        when (effect) {
            is Effect.OpenBrowser -> _openInBrowser.tryEmit(effect.url)
            Effect.SignedIn -> send(appContext.getString(R.string.first_hello), phoneTools = false)
            is Effect.RuntimeRestarted -> {
                agentConfig = null // the new runtime has no Agent yet; the next message configures one
                if (effect.wasBusy) _state.update { it.copy(run = it.run.copy(error = appContext.getString(R.string.err_runtime_restarted))) }
            }
            null -> Unit
        }
    }

    // ---- sign-in ----------------------------------------------------------------------------------
    fun signIn(method: AuthMethod) {
        _state.update { it.copy(screen = Screen.SigningIn(method), signIn = SignInState()) }
        scope.launch { pi.login(PROVIDER, method.type) }
    }

    fun submitPrompt(value: String) {
        val p = _state.value.signIn.prompt ?: return
        if (value.isBlank()) return
        _state.update { it.copy(signIn = it.signIn.copy(prompt = null)) }
        scope.launch { pi.answer(p.id, value.trim()) }
    }

    fun reopenBrowser() { _state.value.signIn.authUrl?.let { _openInBrowser.tryEmit(it) } }
    fun cancelSignIn() { scope.launch { pi.cancelLogin() } }

    fun signOut() {
        agentConfig = null
        _state.update { it.copy(run = RunState()) }
        scope.launch { pi.logout(PROVIDER) }
    }

    // ---- model and thinking level -----------------------------------------------------------------
    fun selectModel(id: String) {
        _state.update { AgentReducer.selectModel(it, id) }
        persistModel()
    }

    fun selectThinking(level: String) {
        _state.update { it.copy(thinkingLevel = level) }
        persistModel()
    }

    private fun persistModel() = with(_state.value) {
        prefs.edit().putString("modelId", modelId).putString("thinkingLevel", thinkingLevel).apply()
    }

    // ---- tasks ------------------------------------------------------------------------------------
    /** Start over: the next message gets a fresh Pi Agent with an empty transcript. */
    fun newConversation() {
        agentConfig = null
        _state.update { it.copy(run = RunState()) }
    }

    fun send(task: String, phoneTools: Boolean, mockBaseUrl: String? = null) {
        if (_state.value.run.busy || task.isBlank()) return
        _state.update { it.copy(run = RunState(task = task, busy = true)) }
        scope.launch {
            val s = _state.value
            val config = listOf(s.modelId, s.thinkingLevel, mockBaseUrl, phoneTools).joinToString("|")
            if (config != agentConfig) {
                pi.configure(PROVIDER, s.modelId, s.thinkingLevel, mockBaseUrl, tools = if (phoneTools) "phone" else null)
                agentConfig = config
            }
            pi.prompt(task)
        }
    }

    /** Stop the running task (Pi's Agent.abort). */
    fun stop() {
        android.util.Log.d("AgentController", "stop requested (busy=${_state.value.run.busy})")
        if (!_state.value.run.busy) return
        phone.cancelWaits() // release a phone action paused while the user looks at the app
        scope.launch { pi.abort() }
        _state.update { it.copy(run = it.run.copy(busy = false, trace = (it.run.trace + "stopped by user").takeLast(AgentReducer.MAX_TRACE))) }
    }

    /** Debug builds only: simulate Pi's WebView renderer dying, to test recovery on a device. */
    fun debugCrashRuntime() = pi.debugCrashRenderer()

    fun destroy() {
        pi.destroy()
        scope.cancel()
    }

    // ---- helpers ----------------------------------------------------------------------------------
    /** Turn the provider's error text into something a user can act on; keep the original for the trace. */
    private fun friendly(msg: String): String = when {
        "insufficient_quota" in msg || "429" in msg && "quota" in msg -> appContext.getString(R.string.err_quota) + "\n" + msg
        "401" in msg || "invalid_api_key" in msg || "Incorrect API key" in msg -> appContext.getString(R.string.err_key) + "\n" + msg
        "model_not_found" in msg || "model" in msg && ("not found" in msg || "does not exist" in msg) ->
            appContext.getString(R.string.err_model, _state.value.modelId) + "\n" + msg
        else -> msg
    }

    companion object {
        const val PROVIDER = AgentReducer.PROVIDER
        const val PI_DEFAULT_MODEL = AgentReducer.PI_DEFAULT_MODEL
    }
}
