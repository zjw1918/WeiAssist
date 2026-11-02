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
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent, independent of any screen: owns the Pi runtime (PiEngine), turns Pi's messages into
 * one immutable [AgentState], and offers the actions the UI can take. Owned by [AgentService], so a
 * task keeps running when the user leaves the app.
 */
class AgentController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pi = PiEngine(appContext, agentName = "小维助手")
    private val phone = PhoneActions(appContext)
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        AgentState(
            modelId = prefs.getString("modelId", null) ?: PI_DEFAULT_MODEL,
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
            AgentAccessibilityService.instance?.confirm(m.getString("title"), m.getString("detail")) ?: false
        }
        scope.launch { pi.messages.collect(::onPi) }
        scope.launch {
            val ready = pi.start()
            _state.update { it.copy(piVersion = ready.optString("pi")) }
        }
    }

    // ---- Pi → state ---------------------------------------------------------------------------
    private fun onPi(m: JSONObject) {
        when (m.optString("type")) {
            "status" -> onStatus(m)
            "auth_event" -> {
                val e = m.getJSONObject("event")
                when (e.optString("type")) {
                    "auth_url" -> {
                        val url = e.getString("url")
                        updateSignIn { it.copy(authUrl = url) }
                        _openInBrowser.tryEmit(url)
                    }
                    "progress", "info" -> updateSignIn { it.copy(progress = e.optString("message")) }
                    "device_code" -> updateSignIn { it.copy(progress = "${e.optString("verificationUri")}  ·  ${e.optString("userCode")}") }
                }
            }
            "auth_prompt" -> {
                val p = m.getJSONObject("prompt")
                val prompt = PendingPrompt(m.getString("id"), p.getString("type"), p.optString("message"), p.optString("placeholder").ifEmpty { null })
                updateSignIn { it.copy(prompt = prompt) }
            }
            "auth_prompt_closed" -> updateSignIn { if (it.prompt?.id == m.getString("id")) it.copy(prompt = null) else it }
            "login_done" -> {
                _state.update { it.copy(screen = Screen.Connected, signIn = SignInState()) }
                send("你好", phoneTools = false) // first thing after sign-in: prove the connection
            }
            "login_failed" -> {
                val msg = m.optString("message")
                _state.update { it.copy(screen = Screen.Welcome, signIn = SignInState(error = msg.takeIf { it != "cancelled" })) }
            }
            // agent events
            "text_delta" -> updateRun { it.copy(answer = it.answer + m.optString("delta")) }
            "assistant_end" -> updateRun {
                it.copy(error = if (m.optString("stopReason") == "error") friendly(m.optString("errorMessage")) else it.error)
                    .trace("assistant_end · ${m.optString("provider")}/${m.optString("model")} · stop=${m.optString("stopReason")} · tokens=${m.optJSONObject("usage")?.optInt("totalTokens")}")
            }
            "tool_start" -> updateRun { it.trace("→ ${m.optString("tool")} ${m.optJSONObject("args")?.toString().orEmpty()}") }
            "tool_end" -> updateRun { it.trace("  ${if (m.optBoolean("isError")) "✗" else "✓"} ${m.optString("output").lineSequence().firstOrNull().orEmpty().take(80)}") }
            "configured" -> updateRun { it.trace("configured · ${m.optString("model")} · thinking=${m.optString("thinkingLevel")} → ${m.optString("baseUrl")} · auth=${m.optString("auth")}") }
            "error" -> updateRun { it.copy(busy = false, error = friendly(m.optString("message"))).trace("error · ${m.optString("message")}") }
            "idle" -> updateRun { it.copy(busy = false).trace("idle") }
            else -> updateRun { it.trace(m.optString("type")) }
        }
    }

    private fun onStatus(m: JSONObject) {
        val provider = parseProviders(m.getJSONArray("providers")).firstOrNull { it.id == PROVIDER }
        _state.update { s ->
            val screen = if (s.screen is Screen.SigningIn) s.screen else if (provider?.credential != null) Screen.Connected else Screen.Welcome
            s.copy(provider = provider, screen = screen)
        }
        // Pi lists only the models this sign-in can use; keep the choice if it's still there,
        // else take Pi's own default for the provider, else the first one.
        val ms = provider?.models.orEmpty()
        val current = _state.value.modelId
        if (ms.isNotEmpty()) selectModel(if (ms.any { it.id == current }) current else (ms.firstOrNull { it.id == PI_DEFAULT_MODEL } ?: ms.first()).id)
    }

    private inline fun updateSignIn(crossinline f: (SignInState) -> SignInState) = _state.update { it.copy(signIn = f(it.signIn)) }
    private inline fun updateRun(crossinline f: (RunState) -> RunState) = _state.update { it.copy(run = f(it.run)) }
    private fun RunState.trace(line: String) = copy(trace = (trace + line).takeLast(MAX_TRACE))

    // ---- sign-in ----------------------------------------------------------------------------------
    fun signIn(method: AuthMethod) {
        _state.update { it.copy(screen = Screen.SigningIn(method), signIn = SignInState()) }
        scope.launch { pi.login(PROVIDER, method.type) }
    }

    fun submitPrompt(value: String) {
        val p = _state.value.signIn.prompt ?: return
        if (value.isBlank()) return
        updateSignIn { it.copy(prompt = null) }
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
        _state.update { s ->
            // Keep the thinking level if the new model supports it; otherwise the nearest one it has.
            val levels = s.provider?.models?.firstOrNull { it.id == id }?.thinkingLevels.orEmpty()
            val level = if (levels.isEmpty() || s.thinkingLevel in levels) s.thinkingLevel
            else LEVEL_ORDER.drop(LEVEL_ORDER.indexOf(s.thinkingLevel).coerceAtLeast(0)).firstOrNull { it in levels } ?: levels.last()
            s.copy(modelId = id, thinkingLevel = level)
        }
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
        updateRun { it.copy(busy = false).trace("stopped by user") }
    }

    fun destroy() {
        pi.destroy()
        scope.cancel()
    }

    // ---- helpers ----------------------------------------------------------------------------------
    private fun parseProviders(a: JSONArray) = (0 until a.length()).map { i ->
        val p = a.getJSONObject(i)
        val ms = p.getJSONArray("methods")
        ProviderInfo(
            id = p.getString("id"), name = p.getString("name"),
            methods = (0 until ms.length()).map { j ->
                val x = ms.getJSONObject(j)
                AuthMethod(x.getString("type"), x.getString("name"), x.optString("label").ifEmpty { null }, x.optBoolean("subscription"))
            },
            credential = p.optString("credential").takeIf { it.isNotEmpty() && it != "null" },
            models = p.getJSONArray("models").let { a2 ->
                (0 until a2.length()).map { k ->
                    val x = a2.getJSONObject(k)
                    val lv = x.getJSONArray("thinkingLevels")
                    ModelInfo(
                        x.getString("id"), x.optString("name", x.getString("id")),
                        (0 until lv.length()).map(lv::getString), x.optInt("contextWindow"),
                        x.optJSONArray("input")?.toString()?.contains("image") == true,
                    )
                }
            },
        )
    }

    /** Turn the provider's error text into something a user can act on; keep the original for the trace. */
    private fun friendly(msg: String): String = when {
        "insufficient_quota" in msg || "429" in msg && "quota" in msg -> "API 账户余额不足：API 和 ChatGPT 订阅分开计费，请到 platform.openai.com 充值。\n$msg"
        "401" in msg || "invalid_api_key" in msg || "Incorrect API key" in msg -> "密钥无效或已过期，请退出后重新登录。\n$msg"
        "model_not_found" in msg || "model" in msg && ("not found" in msg || "does not exist" in msg) ->
            "这个账号用不了模型 ${_state.value.modelId}，在上面换一个试试。\n$msg"
        else -> msg
    }

    companion object {
        const val PROVIDER = "openai"
        /** Pi CLI's defaultModelPerProvider["openai"] (coding-agent/src/core/model-resolver.ts). */
        const val PI_DEFAULT_MODEL = "gpt-6.1-sol"
        /** Pi's ThinkingLevel values, lowest to highest. */
        val LEVEL_ORDER = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
        private const val MAX_TRACE = 300
    }
}
