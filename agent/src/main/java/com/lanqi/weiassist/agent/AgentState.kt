package com.lanqi.weiassist.agent

/** One sign-in method, exactly as Pi's provider declares it. */
data class AuthMethod(val type: String, val name: String, val label: String?, val subscription: Boolean)

/** A chat model as Pi describes it, with the thinking levels Pi says it supports. */
data class ModelInfo(val id: String, val name: String, val thinkingLevels: List<String>, val contextWindow: Int, val images: Boolean)

/** [custom]: one of the user's own providers ([CustomProvider]), editable in the app. */
data class ProviderInfo(
    val id: String, val name: String, val methods: List<AuthMethod>, val credential: String?, val models: List<ModelInfo>,
    val custom: Boolean = false,
)

/** A question Pi's login flow is asking (Pi's AuthPrompt): "secret" = API key, "manual_code" = pasted redirect URL. */
data class PendingPrompt(val id: String, val type: String, val message: String, val placeholder: String?)

sealed interface Screen {
    data object Starting : Screen
    data object Welcome : Screen
    /** [returnTo]: where cancelling goes (a provider added from the AI providers tab goes back there). */
    data class SigningIn(val provider: String, val method: AuthMethod, val returnTo: Screen = Welcome) : Screen
    data object Connected : Screen
}

/** Where a sign-in stands: what Pi asked for and what it reported. */
data class SignInState(
    val authUrl: String? = null,
    val progress: String? = null,
    val prompt: PendingPrompt? = null,
    val error: String? = null,
)

/**
 * The app-wide part of the agent: the runtime, the account and the model new messages use.
 * Each conversation's own state is a [SessionState] (see [SessionManager]).
 */
data class AgentState(
    val screen: Screen = Screen.Starting,
    val piVersion: String? = null,
    /** Every provider Pi offers here, with its sign-in (if any). */
    val providers: List<ProviderInfo> = emptyList(),
    /** The provider new messages use. */
    val providerId: String = AgentReducer.DEFAULT_PROVIDER,
    val signIn: SignInState = SignInState(),
    val modelId: String = "",
    val thinkingLevel: String = "medium",
) {
    val provider: ProviderInfo? get() = providers.firstOrNull { it.id == providerId }
    val model: ModelInfo? get() = provider?.models?.firstOrNull { it.id == modelId }
    val signingInWithBrowser: Boolean get() = (screen as? Screen.SigningIn)?.method?.type == "oauth"
}

// ---- sessions -------------------------------------------------------------------------------------

enum class SessionStatus {
    /** Nothing to do: waiting for the user's next message. */
    Idle,
    /** Has a task, but waits for the phone's screen (another session is operating it). */
    Queued,
    Running,
    /** The user paused it: it holds before its next action. */
    Paused,
    /** It was working when the app's process ended; its conversation is saved and it can continue. */
    Interrupted;

    /** Holds work the service must keep alive for. */
    val active get() = this == Queued || this == Running || this == Paused
}

/** One entry in a conversation, as the user sees it. */
sealed interface ChatItem {
    /** [delivery]: a message typed while the session works waits for Pi to take it (Pi's steering). */
    data class User(val text: String, val delivery: Delivery = Delivery.Sent, val attachments: List<Attachment> = emptyList()) : ChatItem
    data class Assistant(val text: String) : ChatItem
    /**
     * A tool the agent used: [ok] is null while it runs; [result] is the first line of what it returned.
     * [screen] counts the elements of the screen the model got with it (actions return the screen they
     * leave, read_screen is one), 0 if none: the page shows that a step read the screen without keeping it.
     */
    data class Step(val tool: String, val detail: String, val ok: Boolean? = null, val result: String = "", val screen: Int = 0) : ChatItem
    data class Error(val text: String) : ChatItem
    /** The model's reasoning before an answer or a tool call: [text] is empty when the provider hides it. */
    data class Thinking(val text: String, val ms: Long) : ChatItem
    /** Ends a run (one task, from the user's message to idle): what it took. */
    data class Stats(val run: RunStats) : ChatItem
}

/**
 * What one run took: wall time, the time spent reasoning, tool steps, and tokens summed over its model calls
 * ([input] counts each call's whole prompt, so it grows with the conversation; cached input is in [cacheRead]).
 */
data class RunStats(
    val startedAt: Long,
    val ms: Long = 0,
    val thinkingMs: Long = 0,
    val steps: Int = 0,
    val calls: Int = 0,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val reasoning: Long = 0,
    val cost: Double = 0.0,
    val model: String = "",
) {
    val tokens get() = input + output + cacheRead + cacheWrite
}

enum class Delivery {
    Sent,
    /** Waiting for the current turn to end; then Pi adds it to the conversation. */
    Queued,
    /** The task stopped (or failed) before Pi took it: the model never read it. */
    NotDelivered,
}

/** What the session list shows for a session: small, kept for every session. */
data class SessionSummary(
    val id: String,
    val title: String,
    val phone: Boolean,
    val status: SessionStatus,
    val createdAt: Long,
    val updatedAt: Long,
    /** The last thing said, for the list. */
    val preview: String = "",
    val error: Boolean = false,
    /** The app this session is operating, when known (from read_screen / open_app). */
    val app: String? = null,
    /** The supervisor: the one session that manages the others (see SessionCommands). */
    val supervisor: Boolean = false,
    /** The messaging app this conversation is with ("telegram", see Gateway), or null for one in the app. */
    val channel: String? = null,
    /** It may run Linux commands in Termux (the bash tool); false for a chat that may only talk. */
    val termux: Boolean = true,
    /** The AI core that runs it (see CoreRegistry); fixed for the session's life, as transcripts don't carry over. */
    val core: String = DEFAULT_CORE,
) {
    companion object {
        /** Sessions saved before there was a choice of core ran on Pi. */
        const val DEFAULT_CORE = "pi"
    }
}

/** A whole session: kept in memory while it runs or is on screen, else only on disk. */
data class SessionState(
    val summary: SessionSummary,
    val items: List<ChatItem> = emptyList(),
    /** The answer Pi is streaming now (moves into [items] when the message ends). */
    val streaming: String = "",
    val error: String? = null,
    val trace: List<String> = emptyList(),
    /** The task that is queued or running (the latest user message). */
    val pendingTask: String? = null,
    /** Files attached to [pendingTask]. */
    val pendingAttachments: List<Attachment> = emptyList(),
    /** The reasoning Pi is streaming now (moves into [items] as a [ChatItem.Thinking] when it ends). */
    val thinking: String = "",
    /** When the reasoning streaming now began; null when the model isn't reasoning. */
    val thinkingSince: Long? = null,
    /** The run in progress, counted as it goes; it ends as a [ChatItem.Stats]. */
    val run: RunStats? = null,
) {
    val id get() = summary.id
    val status get() = summary.status
    val busy get() = summary.status.active
}
