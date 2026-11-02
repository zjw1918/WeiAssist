package com.lanqi.weiassist.agent

import org.json.JSONArray
import org.json.JSONObject

/** Something the controller must do because of a Pi message (the reducer itself only computes state). */
sealed interface Effect {
    data class OpenBrowser(val url: String) : Effect
    /** Sign-in finished: say hello, to prove the connection works. */
    data object SignedIn : Effect
    /** Pi's runtime was restarted (its WebView renderer died): its sessions are gone. */
    data object RuntimeRestarted : Effect
}

/**
 * Pure state transitions for Pi's app-wide messages (status, sign-in): (state, message) → (state, effect).
 * A session's own messages go to [SessionReducer].
 * No Android, no Pi, no I/O, so every transition is unit-tested (AgentReducerTest).
 */
object AgentReducer {
    const val PROVIDER = "openai"
    /** Pi CLI's defaultModelPerProvider["openai"] (coding-agent/src/core/model-resolver.ts). */
    const val PI_DEFAULT_MODEL = "gpt-6.1-sol"
    /** Pi's ThinkingLevel values, lowest to highest. */
    val LEVEL_ORDER = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

    data class Result(val state: AgentState, val effect: Effect? = null)

    fun reduce(s: AgentState, m: JSONObject): Result = when (m.optString("type")) {
        "status" -> Result(onStatus(s, m))
        "auth_event" -> {
            val e = m.getJSONObject("event")
            when (e.optString("type")) {
                "auth_url" -> e.getString("url").let { url -> Result(s.withSignIn { copy(authUrl = url) }, Effect.OpenBrowser(url)) }
                "progress", "info" -> Result(s.withSignIn { copy(progress = e.optString("message")) })
                "device_code" -> Result(s.withSignIn { copy(progress = "${e.optString("verificationUri")}  ·  ${e.optString("userCode")}") })
                else -> Result(s)
            }
        }
        "auth_prompt" -> {
            val p = m.getJSONObject("prompt")
            val prompt = PendingPrompt(m.getString("id"), p.getString("type"), p.optString("message"), p.optString("placeholder").ifEmpty { null })
            Result(s.withSignIn { copy(prompt = prompt) })
        }
        "auth_prompt_closed" -> Result(s.withSignIn { if (prompt?.id == m.getString("id")) copy(prompt = null) else this })
        "login_done" -> Result(s.copy(screen = Screen.Connected, signIn = SignInState()), Effect.SignedIn)
        "login_failed" -> {
            val msg = m.optString("message")
            Result(s.copy(screen = Screen.Welcome, signIn = SignInState(error = msg.takeIf { it != "cancelled" })))
        }
        // Pi's runtime restarted: the controller re-creates the sessions' Agents (see SessionManager).
        "runtime_restarted" -> Result(s, Effect.RuntimeRestarted)
        else -> Result(s)
    }

    private fun onStatus(s: AgentState, m: JSONObject): AgentState {
        val provider = parseProviders(m.getJSONArray("providers")).firstOrNull { it.id == PROVIDER }
        val screen = if (s.screen is Screen.SigningIn) s.screen else if (provider?.credential != null) Screen.Connected else Screen.Welcome
        val next = s.copy(provider = provider, screen = screen)
        // Pi lists only the models this sign-in can use; keep the choice if it's still there,
        // else take Pi's own default for the provider, else the first one.
        val ms = provider?.models.orEmpty()
        if (ms.isEmpty()) return next
        val id = if (ms.any { it.id == s.modelId }) s.modelId else (ms.firstOrNull { it.id == PI_DEFAULT_MODEL } ?: ms.first()).id
        return selectModel(next, id)
    }

    /** Choose a model; keep the thinking level if it supports it, else the nearest level at or above it. */
    fun selectModel(s: AgentState, id: String): AgentState {
        val levels = s.provider?.models?.firstOrNull { it.id == id }?.thinkingLevels.orEmpty()
        val level = if (levels.isEmpty() || s.thinkingLevel in levels) s.thinkingLevel
        else LEVEL_ORDER.drop(LEVEL_ORDER.indexOf(s.thinkingLevel).coerceAtLeast(0)).firstOrNull { it in levels } ?: levels.last()
        return s.copy(modelId = id, thinkingLevel = level)
    }

    fun parseProviders(a: JSONArray): List<ProviderInfo> = (0 until a.length()).map { i ->
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

    private inline fun AgentState.withSignIn(f: SignInState.() -> SignInState) = copy(signIn = signIn.f())
}
