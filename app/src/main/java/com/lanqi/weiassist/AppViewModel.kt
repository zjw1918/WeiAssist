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
import com.lanqi.weiassist.agent.Attachment
import com.lanqi.weiassist.agent.Attachments
import com.lanqi.weiassist.agent.AuthMethod
import com.lanqi.weiassist.agent.SessionState
import com.lanqi.weiassist.agent.SessionSummary
import com.lanqi.weiassist.agent.pacedStreaming
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
    val voice = com.lanqi.weiassist.agent.VoiceSettings(app)
    private val controller = MutableStateFlow<AgentController?>(null)
    private val connection: ServiceConnection = AgentService.bind(app) { controller.value = it }

    val state: StateFlow<AgentState> = controller
        .flatMapLatest { it?.state ?: flowOf(AgentState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentState())

    val sessions: StateFlow<List<SessionSummary>> = controller
        .flatMapLatest { it?.sessions?.summaries ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Each AI core's slash commands, by core id (suggested as the user types `/`). */
    val coreCommands = controller
        .flatMapLatest { it?.sessions?.commands ?: flowOf(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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
                try { if (live == null) emit(null) else emitAll(live.pacedStreaming()) } finally { c.sessions.detach(id) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), null)

    /** Each phone session's virtual display, while it has one. */
    val displays: StateFlow<Map<String, Int>> = controller
        .flatMapLatest { it?.displays ?: flowOf(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** A display's live picture while collected (see PreviewHub). */
    fun frames(display: Int, purpose: com.lanqi.weiassist.agent.PreviewHub.Purpose) =
        controller.flatMapLatest { it?.previews?.frames(display, purpose) ?: flowOf(null) }

    /** One-shot browser requests from Pi's sign-in. */
    val openInBrowser = controller.flatMapLatest { it?.openInBrowser ?: emptyFlow() }

    // screen-local input
    /** The new-session box: empty each time (a started task doesn't come back as the next one's text). */
    var input by mutableStateOf("")
    /** Reply box on a session's page. */
    var reply by mutableStateOf("")
    /** Files attached to the new-session box ([inputAttachments]) and the reply box, not sent yet. */
    val inputAttachments = androidx.compose.runtime.mutableStateListOf<Attachment>()
    val replyAttachments = androidx.compose.runtime.mutableStateListOf<Attachment>()

    /** Copy picked files in and add them to the new-session box ([toReply] false) or the reply box. */
    fun attach(uris: List<android.net.Uri>, toReply: Boolean) {
        val c = controller.value ?: return
        val target = if (toReply) replyAttachments else inputAttachments
        viewModelScope.launch {
            for (u in uris) {
                runCatching { c.attach(u) }
                    .onSuccess { target += it }
                    .onFailure { e ->
                        val app = getApplication<Application>()
                        val msg = when (e) {
                            is Attachments.Unsupported -> app.getString(R.string.attach_unsupported, e.name)
                            is Attachments.TooLarge -> app.getString(R.string.attach_too_large, e.name)
                            else -> app.getString(R.string.attach_failed, e.message ?: e.javaClass.simpleName)
                        }
                        android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    /** A link in session [id]'s answer to a file in its Termux folder: copy the file out, then open it. */
    fun openFileLink(id: String, link: String) {
        val c = controller.value ?: return
        val app = getApplication<Application>()
        val name = android.net.Uri.decode(link).trimEnd('/').substringAfterLast('/')
        android.widget.Toast.makeText(app, app.getString(R.string.file_fetching, name), android.widget.Toast.LENGTH_SHORT).show()
        viewModelScope.launch {
            runCatching { c.fetchLinkedFile(id, link) }
                .onSuccess { com.lanqi.weiassist.ui.openFile(app, it) }
                .onFailure { android.widget.Toast.makeText(app, app.getString(R.string.file_fetch_failed, it.message ?: it.javaClass.simpleName), android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    fun removeAttachment(a: Attachment, fromReply: Boolean) {
        (if (fromReply) replyAttachments else inputAttachments).remove(a)
        Attachments.discard(listOf(a))
    }
    /** New tasks may operate the phone (needs the accessibility service). */
    var phoneMode by mutableStateOf(prefs.getBoolean("phoneMode", false))
    /** Show the live screens of sessions that have used theirs (see usedScreen); the user may hide them. */
    var showPreviews by mutableStateOf(prefs.getBoolean("showLivePreviews", true))
        private set
    fun showPreviews(on: Boolean) { showPreviews = on; prefs.edit().putBoolean("showLivePreviews", on).apply() }
    var useMock by mutableStateOf(false)
        private set

    /** The installed AI engines (Pi first), once the agent is up. */
    val engines: List<AgentController.Engine> get() = controller.value?.engines.orEmpty()

    /** An engine's name for a session's [SessionSummary.core]. */
    fun engineName(id: String): String = controller.value?.engineName(id) ?: id

    /** Its short name ("DSH"), for a session's row and title bar. */
    fun engineShortName(id: String): String = controller.value?.engineShortName(id) ?: id

    /** The engine new sessions use (Settings). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val defaultEngine: StateFlow<String> = controller
        .flatMapLatest { it?.defaultEngine ?: flowOf("pi") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "pi")

    /** The engine new sessions use from now on (Settings; debug builds: `--es core`). */
    fun selectCore(id: String) { controller.value?.selectCore(id) }

    /** Why [id] can't run now (DeepSeek Harness not set up in Termux…), or null. */
    suspend fun engineProblem(id: String): String? = controller.value?.engineProblem(id)

    /** Whether DeepSeek Harness can run now, and if not, why (Settings' words). */
    suspend fun dshReadiness(): com.lanqi.weiassist.agent.DshSetup.Readiness? = controller.value?.dshSetup?.readiness()

    /** Brings DeepSeek Harness's setup up to date when another version of the app made it (an app update). */
    suspend fun updateDshIfOutdated(): Boolean = controller.value?.dshSetup?.updateIfOutdated() == true

    /** Sets DeepSeek Harness up in Termux, for its setup screen. */
    val dshSetup get() = controller.value?.dshSetup

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

    /** Telegram (see Gateway), for its Settings section. */
    val telegram: StateFlow<com.lanqi.weiassist.agent.Gateway.State> = controller
        .flatMapLatest { it?.gateway?.state ?: flowOf(com.lanqi.weiassist.agent.Gateway.State()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.lanqi.weiassist.agent.Gateway.State())

    /** Check and save a bot token; the problem to show, or null when it is connected. */
    suspend fun connectTelegram(token: String): String? = controller.value?.gateway?.connect(token)
    fun telegramEnabled(on: Boolean) = withAgent { gateway.setEnabled(on) }
    fun forgetTelegram() = withAgent { gateway.forget() }
    fun pairTelegram() = withAgent { gateway.startPairing() }
    fun cancelTelegramPairing() = withAgent { gateway.cancelPairing() }
    fun removeTelegramOwner(id: String) = withAgent { gateway.removeOwner(id) }
    fun telegramAccess(a: com.lanqi.weiassist.agent.Gateway.Access) = withAgent { gateway.setAccess(a) }

    /** The bash tool's Termux bridge, for its Settings section. */
    val termux get() = controller.value?.termux

    private fun withAgent(f: AgentController.() -> Unit) { controller.value?.f() }

    fun signIn(provider: String, method: AuthMethod) = withAgent { signIn(provider, method) }
    fun selectProvider(id: String) = withAgent { selectProvider(id) }
    fun submitPrompt(value: String) = withAgent { submitPrompt(value) }
    fun reopenBrowser() = withAgent { reopenBrowser() }
    fun cancelSignIn() = withAgent { cancelSignIn() }
    fun signOut() = withAgent { signOut() }
    fun selectModel(id: String) = withAgent { selectModel(id) }
    val customProviders: StateFlow<List<com.lanqi.weiassist.agent.CustomProvider>> = controller
        .flatMapLatest { it?.customProviders ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** The problem to show if it couldn't be saved, else null. */
    suspend fun saveCustomProvider(p: com.lanqi.weiassist.agent.CustomProvider, key: String?): String? = controller.value?.saveCustomProvider(p, key)
    fun deleteCustomProvider(id: String) = withAgent { deleteCustomProvider(id) }
    suspend fun fetchCustomModels(baseUrl: String, key: String?, savedFor: String?) =
        controller.value?.fetchCustomModels(baseUrl, key, savedFor) ?: error("not ready")
    suspend fun describeCustomModels(ids: List<String>) =
        controller.value?.describeCustomModels(ids) ?: ids.map { com.lanqi.weiassist.agent.CustomModel(it) }
    fun selectThinking(level: String) = withAgent { selectThinking(level) }
    /** Take the AI providers from [source] (WeiAssist's own, or the Gateway app); the problem to show, or null. */
    suspend fun selectSource(source: com.lanqi.weiassist.agent.ProviderSource): String? = controller.value?.selectSource(source)
    /** List the gateway's models again, while it is the source. */
    suspend fun refreshGateway() { controller.value?.refreshGateway() }
    fun debugCrashRuntime() { if (BuildConfig.DEBUG) withAgent { debugCrashRuntime() } }
    fun debugMainOnly(on: Boolean) { if (BuildConfig.DEBUG) withAgent { debugMainOnly(on) } }

    // ---- sessions ---------------------------------------------------------------------------------
    fun open(id: String?) {
        openId.value = id
        reply = ""
        controller.value?.sessions?.view(id) // leaving an idle phone session closes its apps
    }

    /** Start the typed task in a new session and open it. */
    fun startTask() {
        val c = controller.value ?: return
        val text = input.trim()
        val files = inputAttachments.toList()
        if (text.isEmpty() && files.isEmpty()) return
        // "input": earlier builds kept the last task here, to fill the box with it again.
        prefs.edit().putBoolean("phoneMode", phoneMode).remove("input").apply()
        input = ""
        inputAttachments.clear()
        viewModelScope.launch { open(c.startTask(text, phoneMode, files)) }
    }

    /** A follow-up message in the open session (while it works, it steers the task). */
    /**
     * The answers given so far to the question the open session asks (its card above the message box), one question
     * at a time; null until the user starts answering. Tied to its [Ask] by id: a new question starts a new draft.
     */
    var questionDraft by mutableStateOf<com.lanqi.weiassist.agent.AnswerDraft?>(null)
        private set

    /** The draft for [ask], as far as the user got with it. */
    fun draftOf(ask: com.lanqi.weiassist.agent.Ask) = questionDraft?.takeIf { it.ask.id == ask.id } ?: com.lanqi.weiassist.agent.AnswerDraft(ask)

    /** A step in answering session [id]'s question [ask]; once every question has its answer, they go to the agent. */
    fun answer(id: String, ask: com.lanqi.weiassist.agent.Ask, step: (com.lanqi.weiassist.agent.AnswerDraft) -> com.lanqi.weiassist.agent.AnswerDraft) {
        val next = step(draftOf(ask))
        if (!next.done) { questionDraft = next; return }
        questionDraft = null
        controller.value?.sessions?.answer(id, ask.id, next.answers)
    }

    fun sendReply() {
        val c = controller.value ?: return
        val id = openId.value ?: return
        val text = reply.trim()
        val files = replyAttachments.toList()
        if (text.isEmpty() && files.isEmpty()) return
        // The agent waits for an answer: words typed now are the answer to its question (files go as a message).
        val asking = c.sessions.live(id)?.asks?.firstOrNull()
        if (asking != null && files.isEmpty()) {
            answer(id, asking) { it.custom(text) }
            reply = ""
            return
        }
        viewModelScope.launch {
            if (c.sessions.send(id, text, files)) {
                reply = ""
                replyAttachments.removeAll(files)
            }
        }
    }

    fun stop(id: String) = withAgent { stop(id) }
    fun pause(id: String) = withAgent { sessions.pause(id) }
    fun resume(id: String) = withAgent { sessions.resume(id) }
    fun continueTask(id: String) { val c = controller.value ?: return; viewModelScope.launch { c.sessions.continueTask(id) } }
    fun rename(id: String, title: String) = withAgent { sessions.rename(id, title) }
    fun delete(id: String, termuxFiles: Boolean = false) = delete(listOf(id), termuxFiles)
    fun delete(ids: Collection<String>, termuxFiles: Boolean = false) {
        if (openId.value in ids) open(null)
        withAgent { ids.forEach { sessions.delete(it, termuxFiles) } }
    }
    /**
     * Whether deleting [ids] offers to remove their Termux folders too: always when Termux is installed, whatever the
     * engine or kind of session (dsh's own tools, or the user, may have made one); the user judges.
     */
    fun hasTermuxFiles(ids: Collection<String>) = ids.isNotEmpty() && termux?.installed() == true
    fun stopAll() = withAgent { stopAll() }

    // ---- the supervisor ---------------------------------------------------------------------------
    /** What is being typed (or was spoken) for the supervisor. */
    var supervisorInput by mutableStateOf("")

    /** Tell the supervisor [text] (typed or spoken). Stays on the list, where the sessions it starts appear. */
    fun askSupervisor(text: String = supervisorInput) {
        val c = controller.value ?: return
        val t = text.trim().ifEmpty { return }
        viewModelScope.launch { if (c.command(t) != null) supervisorInput = "" }
    }

    // A running task keeps the service started (foreground); unbinding only drops this screen's link.
    override fun onCleared() = getApplication<Application>().unbindService(connection)

    companion object {
        /** tools/mock-openai.mjs on the computer, reached through `adb reverse tcp:8787 tcp:8787`. */
        const val MOCK_BASE_URL = "http://127.0.0.1:8787/v1"
    }
}
