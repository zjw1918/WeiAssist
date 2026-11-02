package com.lanqi.phoneagent.agent

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

/** Where a sign-in stands: what Pi asked for and what it reported. */
data class SignInState(
    val authUrl: String? = null,
    val progress: String? = null,
    val prompt: PendingPrompt? = null,
    val error: String? = null,
)

/** The current (or last) task: Pi's streamed answer and its event trace. */
data class RunState(
    val task: String? = null,
    val busy: Boolean = false,
    val answer: String = "",
    val error: String? = null,
    val trace: List<String> = emptyList(),
)

/** Everything the UI shows about the agent. Immutable; AgentController publishes a new copy per change. */
data class AgentState(
    val screen: Screen = Screen.Starting,
    val piVersion: String? = null,
    val provider: ProviderInfo? = null,
    val signIn: SignInState = SignInState(),
    val modelId: String = "",
    val thinkingLevel: String = "medium",
    val run: RunState = RunState(),
) {
    val model: ModelInfo? get() = provider?.models?.firstOrNull { it.id == modelId }
    val signingInWithBrowser: Boolean get() = (screen as? Screen.SigningIn)?.method?.type == "oauth"
}
