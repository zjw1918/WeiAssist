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
    /** A device-code sign-in (xAI): the code the user confirms at [authUrl]. */
    val userCode: String? = null,
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
     * [changes]: what a file tool (write, edit) changed in the file [detail] names, for its diff card.
     */
    data class Step(
        val tool: String, val detail: String, val ok: Boolean? = null, val result: String = "", val screen: Int = 0,
        val changes: List<FileChange> = emptyList(),
        /** A command's output (bash), clipped by SessionReducer.stepOutput; empty for other tools. */
        val output: String = "",
    ) : ChatItem
    data class Error(val text: String) : ChatItem
    /** A file the agent gave the user (send_file), kept in the app with the session; [caption] as the model wrote it. */
    data class File(val file: Attachment, val caption: String = "") : ChatItem
    /** The model's reasoning before an answer or a tool call: [text] is empty when the provider hides it. */
    data class Thinking(val text: String, val ms: Long) : ChatItem
    /**
     * The core woke its agent with something not from the user: DeepSeek Harness's notice that a subagent finished
     * ([source] "subagent-settled") or wrote to it ("agent-message"), a team message, a background job's end. The
     * agent's reply to it is a new part of the run, as dsh's own chat shows it ("Subtask status updated"); [text] is
     * the notice, shown on request.
     */
    data class Trigger(val source: String, val text: String, val subagent: String = "") : ChatItem
    /**
     * A subagent the agent started (DeepSeek Harness), with its own work, as dsh's web chat gives each child its own
     * conversation: [items] are its reasoning, steps and messages; [thinking] and [streaming] what it streams now.
     * [label] is the task's short description. [outcome] is null until dsh reports it settled ("done", "stopped",
     * "failed"), with its closing message in [result]; [ms] is how long it took.
     */
    data class Subagent(
        val id: String,
        val label: String = "",
        val items: List<ChatItem> = emptyList(),
        val running: Boolean = true,
        val thinking: String = "",
        val thinkingSince: Long? = null,
        val streaming: String = "",
        val startedAt: Long = 0,
        val ms: Long = 0,
        val outcome: String? = null,
        val result: String = "",
    ) : ChatItem {
        val steps get() = items.count { it is Step }
        val settled get() = outcome != null
    }
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
    /** Every prompt token sent: fresh ([input]), read from the provider's cache, and newly written to it. */
    val prompt get() = input + cacheRead + cacheWrite
    /** The share of [prompt] the provider served from its cache, 0..100; null before any call reported usage. */
    val cacheHitPercent: Int? get() = if (prompt > 0) (cacheRead * 100 / prompt).toInt() else null
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
    /**
     * How full the model's context is: the tokens the latest model call carried (its prompt plus its reply, as the
     * provider counted them), or the core's estimate after it compacted or restored the conversation. 0 = not known yet.
     */
    val contextTokens: Long = 0,
    /** The context window of the model the session last ran on (tokens); 0 = the core didn't say. */
    val contextWindow: Int = 0,
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
    /** Questions the agent waits for the user to answer, oldest first (not saved: they end with the run). */
    val asks: List<Ask> = emptyList(),
) {
    val id get() = summary.id
    val status get() = summary.status
    val busy get() = summary.status.active
}

/**
 * A change a step made to a file: [old] replaced by [new]. A file written whole has an empty [old] (what it held
 * before isn't known: it shows as all new). [cut]: the texts were too long to keep whole, and end early.
 */
data class FileChange(val old: String, val new: String, val cut: Boolean = false) {
    companion object {
        /** The most of each text a step keeps (the app keeps sessions in memory and on disk). */
        const val MAX = 24_000

        fun of(old: String, new: String): FileChange {
            fun clip(t: String) = if (t.length <= MAX) t else t.substring(0, t.lastIndexOf('\n', MAX).let { if (it > 0) it else MAX })
            return FileChange(clip(old), clip(new), old.length > MAX || new.length > MAX)
        }
    }
}
