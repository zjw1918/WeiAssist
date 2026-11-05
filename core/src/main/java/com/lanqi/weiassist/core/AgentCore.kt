package com.lanqi.weiassist.core

import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * An AI core: an agent harness (Pi, and later others) that runs the app's conversations. The app works only
 * through this interface. It owns sessions, the UI, model settings and the tools, and each core keeps its own
 * agent loop, context handling and transcript format.
 *
 * **Sessions.** A core holds one agent per session id. [configure] creates or replaces it, [prompt] starts a
 * task, [steer] adds a message to a running one, [abort] stops it, [dispose] drops the agent. A core may lose
 * all its agents (its runtime died). It then emits `runtime_restarted`, and the app configures each session
 * again (with its saved transcript) before its next prompt.
 *
 * **Events** ([events]) are JSON objects with a `type`, in one vocabulary shared by all cores:
 * - About a session (they carry `session`): `configured`, `agent_start`, `text_delta` {delta},
 *   `thinking_start`, `thinking_delta` {delta}, `thinking_end` {text}, `assistant_end` {text, stopReason,
 *   errorMessage, usage, provider, model}, `tool_start` {tool, args}, `tool_end` {tool, isError, output, screen},
 *   `message_end` {role, text}, `steer_late` {text}, `turn_end`, `idle`, `error` {message},
 *   `transcript` {messages}: the core's transcript to save. It is opaque to the app and handed back through
 *   [SessionConfig.history].
 * - App-wide (no `session`): `runtime_restarted`, plus account events from a core that is also [CoreAccounts]
 *   (`status`, `auth_*`, `login_*`).
 *
 * **Tools** run in the app: a core asks [ToolHost] (`gate` before every tool call, then `phone`, `bash`,
 * `confirm`, …) and gives the answer to its model.
 */
interface AgentCore {
    val info: CoreInfo
    val events: SharedFlow<JSONObject>

    /** Where this core's tool calls go. Set once, before [start]. */
    fun bind(host: ToolHost)

    /** Boot the runtime if needed and wait until it is ready. Returns what it says about itself (e.g. its version). */
    suspend fun start(): JSONObject

    /** Create or replace session [session]'s agent. */
    suspend fun configure(session: String, config: SessionConfig)
    suspend fun prompt(session: String, input: UserInput)
    /** A message for a session that is working: read after the current step. */
    suspend fun steer(session: String, input: UserInput)
    suspend fun abort(session: String)
    /** Drop a session's agent (its saved transcript is the app's business). */
    suspend fun dispose(session: String)

    /** Debug builds: make the runtime die, to exercise recovery. A no-op where that makes no sense. */
    fun debugCrash() {}
    fun destroy()
}

/** [id] is stable and saved with each session, and names its transcript file: lowercase letters and digits. */
data class CoreInfo(val id: String, val name: String, val capabilities: CoreCapabilities = CoreCapabilities()) {
    init {
        require(ID.matches(id) && id !in RESERVED_IDS) { "bad core id: $id" }
    }

    companion object {
        private val ID = Regex("[a-z][a-z0-9]{0,15}")
        /** Taken by the session store's own files (`<id>.meta.json`, `<id>.chat.json`). */
        val RESERVED_IDS = setOf("meta", "chat")
    }
}

/** What a core can do, so the app can offer only what works with it. */
data class CoreCapabilities(
    /** Answers arrive as `text_delta`s while the model writes, not only in `assistant_end`. */
    val streaming: Boolean = true,
    /** [AgentCore.steer] reaches a running task (else the message waits for the next task). */
    val steering: Boolean = true,
    /** Honours [ModelSelection.thinking]. */
    val thinkingLevels: Boolean = true,
    /** Accepts images in [UserInput.content] and from tools. */
    val images: Boolean = true,
)

/** The model a session uses. [baseUrl] overrides the provider's endpoint (dev builds' mock server). */
data class ModelSelection(val provider: String, val model: String, val thinking: String, val baseUrl: String? = null)

/** The tools a session's agent gets, beyond talking (and bash, see [SessionConfig.bash]). */
enum class ToolSet { Chat, Phone, Supervisor }

data class SessionConfig(
    val model: ModelSelection,
    val tools: ToolSet = ToolSet.Chat,
    /** Linux commands in Termux (the bash tool). */
    val bash: Boolean = true,
    /** The messaging app the user talks from ("telegram"), if any: the prompt says so, and send_file is offered. */
    val channel: String? = null,
    val systemPrompt: String? = null,
    /** A transcript this core saved earlier (its `transcript` event), to restore the conversation; null keeps the agent's own. */
    val history: JSONArray? = null,
)

/** A user message: [text] plus content blocks (attached images, files) in the shared block format, or null. */
data class UserInput(val text: String, val content: JSONArray? = null)
