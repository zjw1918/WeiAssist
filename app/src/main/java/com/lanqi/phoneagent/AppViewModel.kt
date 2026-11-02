package com.lanqi.phoneagent

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanqi.phoneagent.pi.PiEngine
import com.lanqi.phoneagent.pi.SignInService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One sign-in method, exactly as Pi's provider declares it. */
data class AuthMethod(val type: String, val name: String, val label: String?, val subscription: Boolean)
/** A chat model as Pi describes it, with the thinking levels Pi says it supports. */
data class ModelInfo(val id: String, val name: String, val thinkingLevels: List<String>, val contextWindow: Int, val images: Boolean)
data class ProviderInfo(val id: String, val name: String, val methods: List<AuthMethod>, val credential: String?, val models: List<ModelInfo>)
/** A question Pi's login flow is asking (Pi's AuthPrompt): "secret" = API key, "manual_code" = pasted redirect URL. */
data class PendingPrompt(val id: String, val type: String, val message: String, val placeholder: String?)

sealed interface Screen {
    data object Starting : Screen
    data object Welcome : Screen
    data class SigningIn(val method: AuthMethod) : Screen
    data object Connected : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val pi = PiEngine(app, agentName = "小维助手")
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var screen by mutableStateOf<Screen>(Screen.Starting); private set
    var piVersion by mutableStateOf<String?>(null); private set
    var provider by mutableStateOf<ProviderInfo?>(null); private set

    // sign-in
    var authUrl by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf<String?>(null); private set
    var prompt by mutableStateOf<PendingPrompt?>(null); private set
    var signInError by mutableStateOf<String?>(null); private set

    // connected
    var modelId by mutableStateOf(prefs.getString("modelId", null) ?: PI_DEFAULT_MODEL); private set
    var thinkingLevel by mutableStateOf(prefs.getString("thinkingLevel", null) ?: "medium"); private set
    val model: ModelInfo? get() = provider?.models?.firstOrNull { it.id == modelId }
    var useMock by mutableStateOf(false)
    var input by mutableStateOf("你好")
    var answer by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    val trace = mutableStateListOf<String>()

    /** One-shot: open this URL in Chrome (a Custom Tab). */
    private val browser = Channel<String>(Channel.BUFFERED)
    val openInBrowser = browser.receiveAsFlow()

    init {
        viewModelScope.launch { pi.messages.collect(::onPi) }
        viewModelScope.launch { piVersion = pi.start().optString("pi") }
    }

    private fun onPi(m: JSONObject) {
        when (m.optString("type")) {
            "status" -> {
                provider = parseProviders(m.getJSONArray("providers")).firstOrNull { it.id == PROVIDER }
                // Pi lists only the models this sign-in can use; keep the choice if it's still there,
                // else take Pi's own default for the provider, else the first one.
                val ms = provider?.models.orEmpty()
                if (ms.isNotEmpty()) selectModel(if (ms.any { it.id == modelId }) modelId else (ms.firstOrNull { it.id == PI_DEFAULT_MODEL } ?: ms.first()).id)
                if (screen !is Screen.SigningIn) screen = if (provider?.credential != null) Screen.Connected else Screen.Welcome
            }
            "auth_event" -> {
                val e = m.getJSONObject("event")
                when (e.optString("type")) {
                    "auth_url" -> { authUrl = e.getString("url"); browser.trySend(e.getString("url")) }
                    "progress", "info" -> progress = e.optString("message")
                    "device_code" -> progress = "${e.optString("verificationUri")}  ·  ${e.optString("userCode")}"
                }
            }
            "auth_prompt" -> {
                val p = m.getJSONObject("prompt")
                prompt = PendingPrompt(m.getString("id"), p.getString("type"), p.optString("message"), p.optString("placeholder").ifEmpty { null })
            }
            "auth_prompt_closed" -> if (prompt?.id == m.getString("id")) prompt = null
            "login_done" -> { resetSignIn(); screen = Screen.Connected; send() } // first thing after sign-in: say 你好
            "login_failed" -> {
                val msg = m.optString("message")
                resetSignIn()
                screen = Screen.Welcome
                if (msg != "cancelled") signInError = msg
            }
            // agent events
            "text_delta" -> answer += m.optString("delta")
            "assistant_end" -> {
                if (m.optString("stopReason") == "error") error = friendly(m.optString("errorMessage"))
                trace += "assistant_end · ${m.optString("provider")}/${m.optString("model")} · ${m.optString("api")} · " +
                    "stop=${m.optString("stopReason")} · tokens=${m.optJSONObject("usage")?.optInt("totalTokens")}"
            }
            "configured" -> trace += "configured · ${m.optString("model")} · thinking=${m.optString("thinkingLevel")} → ${m.optString("baseUrl")} · auth=${m.optString("auth")}"
            "error" -> { error = friendly(m.optString("message")); busy = false; trace += "error · ${m.optString("message")}" }
            "idle" -> { busy = false; trace += "idle" }
            else -> trace += m.optString("type")
        }
    }

    // ---- onboarding -----------------------------------------------------------------------------
    fun signIn(method: AuthMethod) {
        resetSignIn(); signInError = null
        screen = Screen.SigningIn(method)
        // Subscription sign-in happens in Chrome while we wait in the background: stay unfrozen.
        if (method.type == "oauth") SignInService.start(getApplication())
        viewModelScope.launch { pi.login(PROVIDER, method.type) }
    }
    fun submitPrompt(value: String) {
        val p = prompt ?: return
        if (value.isBlank()) return
        prompt = null
        viewModelScope.launch { pi.answer(p.id, value.trim()) }
    }
    fun reopenBrowser() { authUrl?.let { browser.trySend(it) } }
    fun cancelSignIn() { viewModelScope.launch { pi.cancelLogin() } }
    fun signOut() {
        answer = ""; error = null; trace.clear()
        viewModelScope.launch { pi.logout(PROVIDER) }
    }
    private fun resetSignIn() {
        authUrl = null; progress = null; prompt = null
        SignInService.stop(getApplication())
    }

    // ---- model and thinking level --------------------------------------------------------------
    fun selectModel(id: String) {
        modelId = id
        // Keep the thinking level if the new model supports it; otherwise the nearest one it has.
        val levels = model?.thinkingLevels.orEmpty()
        if (levels.isNotEmpty() && thinkingLevel !in levels) {
            thinkingLevel = LEVEL_ORDER.drop(LEVEL_ORDER.indexOf(thinkingLevel).coerceAtLeast(0)).firstOrNull { it in levels } ?: levels.last()
        }
        prefs.edit().putString("modelId", modelId).putString("thinkingLevel", thinkingLevel).apply()
    }
    fun selectThinking(level: String) {
        thinkingLevel = level
        prefs.edit().putString("thinkingLevel", level).apply()
    }

    // ---- talking to the model through Pi ----------------------------------------------------------
    fun send() {
        if (busy || input.isBlank()) return
        busy = true; answer = ""; error = null; trace.clear()
        viewModelScope.launch {
            pi.configure(PROVIDER, modelId, thinkingLevel, if (useMock) MOCK_BASE_URL else null)
            pi.prompt(input)
        }
    }

    override fun onCleared() = pi.destroy()

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
            "这个账号用不了模型 $modelId，在上面换一个试试。\n$msg"
        else -> msg
    }

    companion object {
        const val PROVIDER = "openai"
        /** Pi CLI's defaultModelPerProvider["openai"] (coding-agent/src/core/model-resolver.ts). */
        const val PI_DEFAULT_MODEL = "gpt-6.1-sol"
        /** Pi's ThinkingLevel values, lowest to highest. */
        val LEVEL_ORDER = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
        /** tools/mock-openai.mjs on the computer, reached through `adb reverse tcp:8787 tcp:8787`. */
        const val MOCK_BASE_URL = "http://127.0.0.1:8787/v1"
    }
}
