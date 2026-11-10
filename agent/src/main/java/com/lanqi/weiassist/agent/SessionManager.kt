package com.lanqi.weiassist.agent

import android.util.Log
import com.lanqi.weiassist.core.AgentCore
import com.lanqi.weiassist.core.CoreRegistry
import com.lanqi.weiassist.core.ModelSelection
import com.lanqi.weiassist.core.SessionConfig
import com.lanqi.weiassist.core.SessionKind
import com.lanqi.weiassist.core.UserInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * All conversations. Each session is an agent in the AI core it was made with ([SessionSummary.core], see
 * [CoreRegistry]), so sessions run side by side and keep running when their screen is closed; they end when
 * their task is done or the user stops them. Main thread only (state changes), with disk work on one
 * background lane.
 *
 * Memory: every session's [SessionSummary] is kept (small). A session's chat ([SessionState]) is in
 * memory only while it is busy or open on screen ([attach]); otherwise it is on disk only. The core's
 * transcript lives inside the core while the session's agent exists, and on disk.
 */
class SessionManager(
    private val cores: CoreRegistry,
    private val store: SessionStore,
    private val scope: CoroutineScope,
    private val strings: Strings,
    /** The model and thinking level a message uses, and the mock server URL in dev mode. */
    private val settings: () -> RunSettings,
    private val screens: ScreenPool = MainScreenPool(),
) {
    /** Localized texts the manager needs (supplied by the controller, which has a Context). */
    interface Strings {
        fun friendly(msg: String): String
        fun runtimeRestarted(): String
        fun continueTask(): String
        /** The session was made by AI core [core], which this build doesn't have. */
        fun missingCore(core: String): String
        /** The assistant's name, as the prompts say it. */
        fun agentName(): String
        /** Session's core [core] has no slash command [name]; [known] are the ones it has (may be empty). */
        fun unknownCommand(core: String, name: String, known: List<String>): String
    }

    private val io = Dispatchers.IO.limitedParallelism(1)
    private val _summaries = MutableStateFlow<List<SessionSummary>>(emptyList())
    /** Every session, most recently active first. */
    val summaries: StateFlow<List<SessionSummary>> = _summaries.asStateFlow()

    /** Sessions held in memory: busy ones and those on screen. */
    private val live = HashMap<String, MutableStateFlow<SessionState>>()
    /** How many screens show each session ([attach]/[detach]). */
    private val watchers = HashMap<String, Int>()
    /** Agent settings per session in its core's current runtime; a session not in here has no agent yet. */
    private val configured = HashMap<String, SessionConfig>()
    private val paused = HashMap<String, MutableStateFlow<Boolean>>()
    /** Bumped per session when the user stops it, so a gate waiting in a paused session gives up. */
    private val stops = HashMap<String, Int>()
    private val queue = ArrayDeque<String>()
    /** The session whose page the user has open ([view]). */
    private var viewing: String? = null
    val viewingId: String? get() = viewing
    /** Sessions the user left while they worked: their apps close as soon as the task ends. */
    private val leftWhileBusy = HashSet<String>()
    /**
     * Sessions whose task their core has been given (prompt called) and not yet ended: only these can be
     * steered. Calls to a core run in order, so a steer after the prompt always finds its run.
     */
    private val prompted = HashSet<String>()
    private val pendingWrites = HashMap<String, Job>()

    private val _commands = MutableStateFlow<Map<String, List<com.lanqi.weiassist.core.CoreCommand>>>(emptyMap())
    /** Each core's slash commands, by core id, as it last listed them (saved: known before it boots again). */
    val commands: StateFlow<Map<String, List<com.lanqi.weiassist.core.CoreCommand>>> = _commands.asStateFlow()

    /** Load the saved sessions. Sessions that were working when the process ended are marked Interrupted. */
    suspend fun load() {
        _commands.value = withContext(io) { store.loadCommands() }
        val saved = withContext(io) { store.loadSummaries() }
        val now = System.currentTimeMillis()
        val fixed = saved.map { if (it.status.active) it.copy(status = SessionStatus.Interrupted, updatedAt = now) else it }
        _summaries.value = fixed
        fixed.filter { it.status == SessionStatus.Interrupted && saved.first { s -> s.id == it.id }.status.active }
            .forEach { s -> scope.launch(io) { store.saveSummary(s) } }
    }

    // ---- observing ------------------------------------------------------------------------------------

    /** A screen shows this session: keep it in memory and return its live state (loading it if needed). */
    suspend fun attach(id: String): StateFlow<SessionState>? {
        watchers[id] = (watchers[id] ?: 0) + 1
        return loadLive(id)
    }

    /** The screen closed: a session that isn't busy leaves memory (it is on disk). */
    fun detach(id: String) {
        val n = (watchers[id] ?: 1) - 1
        if (n <= 0) watchers.remove(id) else watchers[id] = n
        evictIfUnused(id)
    }

    /**
     * The user opened session [id]'s page, or went back to the list (null). Leaving an idle phone session
     * closes its apps and gives its display up (a later message opens the app afresh); leaving a working
     * one does so when its task ends. Only navigation counts: the app going to the background doesn't.
     */
    fun view(id: String?) {
        val left = viewing
        viewing = id
        if (id != null) leftWhileBusy -= id
        if (left == null || left == id) return
        if (summary(left)?.status?.active == true) leftWhileBusy += left else releaseScreen(left, now = true)
    }

    private suspend fun loadLive(id: String): MutableStateFlow<SessionState>? {
        live[id]?.let { return it }
        val summary = _summaries.value.firstOrNull { it.id == id } ?: return null
        val items = withContext(io) { store.loadItems(id) }
        // A coroutine may have loaded it meanwhile; keep that one.
        return live.getOrPut(id) { MutableStateFlow(SessionState(summary, items.takeLast(SessionReducer.MAX_ITEMS))) }
    }

    private fun evictIfUnused(id: String) {
        val s = live[id]?.value ?: return
        // A steered message still queued may come back from its core (steer_late) and start the next task.
        if (s.busy || (watchers[id] ?: 0) > 0 || s.items.any { (it as? ChatItem.User)?.delivery == Delivery.Queued }) return
        flush(s)
        live.remove(id)
    }

    fun summary(id: String) = _summaries.value.firstOrNull { it.id == id }

    /** Session [id] as it is now, if it is in memory (busy or on screen). */
    fun live(id: String): SessionState? = live[id]?.value

    /** The supervisor session (created on first use). */
    fun supervisor(title: String): String = _summaries.value.firstOrNull { it.supervisor }?.id ?: create(phone = false, title = title, supervisor = true)

    /** A session as it is now, without keeping it in memory (read from disk if it isn't there). */
    suspend fun snapshot(id: String): SessionState? {
        live[id]?.let { return it.value }
        val summary = summary(id) ?: return null
        val items = withContext(io) { store.loadItems(id) }
        return SessionState(summary, items.takeLast(SessionReducer.MAX_ITEMS))
    }

    // ---- commands -------------------------------------------------------------------------------------

    /**
     * A new, empty session. [phone] = it may operate the phone; [supervisor] = it manages the others;
     * [channel] = it is a chat in that messaging app (see Gateway); [termux] = it may run Linux commands.
     */
    fun create(
        phone: Boolean, title: String = "", supervisor: Boolean = false, channel: String? = null, termux: Boolean = true,
        /** The AI core that runs it; the user's choice for new sessions by default. */
        core: String = cores.defaultId,
    ): String {
        require(cores[core] != null) { "no core $core" }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString().substring(0, 8)
        val summary = SessionSummary(id, title, phone && !supervisor, SessionStatus.Idle, now, now, supervisor = supervisor, channel = channel, termux = termux, core = core)
        live[id] = MutableStateFlow(SessionState(summary))
        publish(summary)
        scope.launch(io) { store.saveSummary(summary) }
        return id
    }

    /**
     * The user's message to a session: start (or queue) a task. While the session works, the message
     * steers it instead (its core reads it after the current step; [urgent]: without waiting for a long command, see
     * [hurry]). False if the session is unknown.
     */
    suspend fun send(id: String, text: String, attachments: List<Attachment> = emptyList(), urgent: Boolean = false): Boolean {
        if (text.isBlank() && attachments.isEmpty()) return false
        val flow = loadLive(id) ?: return false
        // A slash command, for a core that runs them: the core runs it, the model never reads it as a message.
        if (attachments.isEmpty() && isCommand(id, text)) { runCommand(id, text.trim()); return true }
        // Staged files move into the session's folder: they live as long as it does.
        val files = withContext(io) { Attachments.keep(attachments, store.attachmentsDir(id)) }
        if (flow.value.busy) {
            val now = System.currentTimeMillis()
            // The core hasn't been given this task yet (it waits for a screen, or is being set up): the message joins it.
            if (id !in prompted) update(id) { SessionReducer.joinTask(it, text.trim(), now, files) }
            else {
                update(id) { SessionReducer.steered(it, text.trim(), now, files) }
                coreOf(id)?.steer(id, UserInput(text.trim(), MessageContext.append(Attachments.content(files))))
                if (urgent) coreOf(id)?.hurry(id)
            }
            return true
        }
        val phone = flow.value.summary.phone
        val now = System.currentTimeMillis()
        val canRun = !phone || screens.acquire(id) != null
        update(id) { s ->
            val title = text.trim().lineSequence().first().take(40).ifBlank { files.firstOrNull()?.name.orEmpty() }
            val titled = if (s.summary.title.isBlank()) s.copy(summary = s.summary.copy(title = title)) else s
            SessionReducer.userMessage(titled, text.trim(), queued = !canRun, now = now, attachments = files)
        }
        paused.getOrPut(id) { MutableStateFlow(false) }.value = false
        if (canRun) start(id) else queue.addLast(id)
        return true
    }

    /** Have what the user steered read now: a long command goes on in the background instead of holding it up. */
    fun hurry(id: String) {
        if (id !in prompted) return
        coreOf(id)?.let { c -> scope.launch { c.hurry(id) } }
    }

    /** A background command's result, when the core's own wait for it was lost: the conversation is restored if needed. */
    suspend fun deliverBackgroundResult(id: String, call: String, command: String, text: String) {
        val s = loadLive(id)?.value ?: return
        val core = coreOf(id) ?: return
        configure(id, s, core)
        core.backgroundResult(id, call, command, text)
    }

    /** Session [id]'s core can [hurry]. */
    fun canHurry(id: String) = coreOf(id)?.info?.capabilities?.hurry == true

    /** [text] is a slash command (`/name …`) and session [id]'s core runs slash commands. */
    fun isCommand(id: String, text: String): Boolean =
        COMMAND.containsMatchIn(text.trim()) && coreOf(id)?.info?.capabilities?.commands == true

    /**
     * Run the user's slash command [line] on session [id]'s core: shown in its chat as a command with its outcome.
     * Its agent is set up first if it isn't yet (dsh may have to boot). Work the command starts (plan mode's
     * message, a goal's rounds) is a run the core starts by itself (see onCoreEvent).
     */
    private fun runCommand(id: String, line: String) {
        val core = coreOf(id) ?: return
        val now = System.currentTimeMillis()
        update(id) { s ->
            val titled = if (s.summary.title.isBlank()) s.copy(summary = s.summary.copy(title = line.take(40))) else s
            titled.copy(items = titled.items + ChatItem.Command(line), summary = titled.summary.copy(updatedAt = now, preview = line))
        }
        scope.launch {
            try {
                live[id]?.value?.let { configure(id, it, core) }
                core.command(id, line)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Log.w(TAG, "${core.info.id} could not run $line for $id", e)
                val msg = if (e is kotlinx.coroutines.TimeoutCancellationException) "${core.info.name} did not start in time." else e.message ?: e.javaClass.simpleName
                val name = ChatItem.Command(line).name
                update(id) { SessionReducer.reduce(it, JSONObject().put("type", "command_result").put("name", name).put("kind", "error").put("text", strings.friendly(msg)), System.currentTimeMillis()) }
            }
        }
    }

    /** The core's run ended just before the steered [text] reached it: send it as the next task. */
    private fun lateSteer(id: String, text: String) {
        val s = live[id]?.value ?: return
        if (s.busy) { // a new task started meanwhile: steer that one
            val files = s.items.firstOrNull { it is ChatItem.User && it.delivery == Delivery.Queued && it.text == text }?.let { (it as ChatItem.User).attachments }.orEmpty()
            if (id in prompted) scope.launch { coreOf(id)?.steer(id, UserInput(text, MessageContext.append(Attachments.content(files)))) } else Log.w(TAG, "late steer for $id dropped: next task not started")
            return
        }
        val canRun = !s.summary.phone || screens.acquire(id) != null
        update(id) { SessionReducer.lateSteer(it, text, queued = !canRun, now = System.currentTimeMillis()) }
        paused.getOrPut(id) { MutableStateFlow(false) }.value = false
        if (canRun) start(id) else queue.addLast(id)
    }

    /** An interrupted session (the app's process ended mid-task) picks up where it was. */
    suspend fun continueTask(id: String) = send(id, strings.continueTask())

    /** Where session [id] keeps its files (attachments, and files the agent gave the user); deleted with it. */
    fun filesDir(id: String): java.io.File = store.attachmentsDir(id)
    fun workspaceDir(id: String): java.io.File = store.workspaceDir(id)

    // ---- questions the agent asks the user -----------------------------------------------------------

    /** Questions waiting for the user, by "<session>/<ask id>": their answers, or null when withdrawn. */
    private val askWaits = HashMap<String, kotlinx.coroutines.CompletableDeferred<List<QuestionAnswer>?>>()

    /** A question opened (true) or closed (false: answered or withdrawn) in a session: for its notification. */
    data class Asked(val id: String, val ask: Ask, val open: Boolean)

    private val _asked = kotlinx.coroutines.flow.MutableSharedFlow<Asked>(extraBufferCapacity = 16)
    val asked: kotlinx.coroutines.flow.SharedFlow<Asked> = _asked

    /**
     * Session [id]'s agent asks the user [ask] (DeepSeek Harness's ask_user): shown in its chat until answered
     * ([answer]), withdrawn by its core ([withdraw]), or the task is stopped. Returns the answers, or null.
     */
    suspend fun ask(id: String, ask: Ask): List<QuestionAnswer>? {
        loadLive(id) ?: return null
        if (live[id]?.value?.busy != true) return null
        val key = "$id/${ask.id}"
        val wait = kotlinx.coroutines.CompletableDeferred<List<QuestionAnswer>?>()
        askWaits[key] = wait
        update(id) { it.copy(asks = it.asks + ask) }
        _asked.tryEmit(Asked(id, ask, open = true))
        try {
            return wait.await()
        } finally {
            askWaits.remove(key)
            update(id) { s -> s.copy(asks = s.asks.filterNot { it.id == ask.id }) }
            _asked.tryEmit(Asked(id, ask, open = false))
        }
    }

    /** The user answered session [id]'s question [ask]. False when it is no longer asked (answered elsewhere, say). */
    fun answer(id: String, ask: String, answers: List<QuestionAnswer>): Boolean = askWaits["$id/$ask"]?.complete(answers) == true

    /** Session [id]'s core no longer waits for [ask] (its turn was stopped). */
    fun withdraw(id: String, ask: String) { askWaits["$id/$ask"]?.complete(null) }

    /** Every question session [id] asks is withdrawn (it was stopped, deleted, or its core went away). */
    private fun withdrawAll(id: String) = askWaits.filterKeys { it.startsWith("$id/") }.values.forEach { it.complete(null) }

    /** Show a file the agent gave the user (send_file, already in [filesDir]) in session [id]'s chat. False: it is gone. */
    suspend fun addFile(id: String, file: Attachment, caption: String): Boolean {
        loadLive(id) ?: return false
        val now = System.currentTimeMillis()
        update(id) { it.copy(items = it.items + ChatItem.File(file, caption), summary = it.summary.copy(updatedAt = now)) }
        return true
    }

    private fun start(id: String) {
        val s = live[id]?.value ?: return
        if (s.pendingTask == null && s.pendingAttachments.isEmpty()) return
        if (s.status == SessionStatus.Queued) update(id) { SessionReducer.withStatus(it, SessionStatus.Running, System.currentTimeMillis()) }
        val core = coreOf(id) ?: run {
            // Made by a core this build doesn't have: its transcript means nothing to another one.
            update(id) { SessionReducer.reduce(it, JSONObject().put("type", "error").put("message", strings.missingCore(s.summary.core)), System.currentTimeMillis()) }
            finished(id)
            return
        }
        scope.launch {
            // A core that can't start or take the task (dsh didn't come up in Termux) ends the task with why.
            try { runTask(id, s, core) } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Log.w(TAG, "${core.info.id} could not run $id", e)
                val msg = if (e is kotlinx.coroutines.TimeoutCancellationException) "${core.info.name} did not start in time." else e.message ?: e.javaClass.simpleName
                if (live[id]?.value?.busy == true) {
                    update(id) { SessionReducer.reduce(it, JSONObject().put("type", "error").put("message", msg), System.currentTimeMillis(), strings::friendly) }
                    finished(id)
                }
            }
        }
    }

    /** Give session [id]'s core its task: set its agent up if needed, then prompt it. */
    private suspend fun runTask(id: String, s: SessionState, core: AgentCore) {
        run {
            configure(id, s, core)
            // Read now: messages typed while the core was being set up joined the task (see send).
            val now = live[id]?.value?.takeIf { it.busy } ?: return
            val task = now.pendingTask ?: if (now.pendingAttachments.isNotEmpty()) "" else return
            onTaskStarted(id)
            stopped -= id
            core.prompt(id, UserInput(task, MessageContext.append(Attachments.content(now.pendingAttachments))))
            prompted += id
        }
    }

    /** Set session [id]'s agent up in its core, unless it already is, with the settings in use now. */
    private suspend fun configure(id: String, s: SessionState, core: AgentCore) {
        run {
            val (provider, model, thinking, mock) = settings()
            val kind = when { s.summary.supervisor -> SessionKind.Supervisor; s.summary.phone -> SessionKind.Phone; else -> SessionKind.Chat }
            val config = SessionConfig(
                ModelSelection(provider, model, thinking, mock), kind, ToolCatalog.forSession(s.summary),
                bash = s.summary.termux && !s.summary.supervisor, channel = s.summary.channel.takeUnless { s.summary.supervisor },
                prompt = Prompts.forSession(s.summary, strings.agentName()),
            )
            if (configured[id] != config) {
                // First use in this runtime: give the core the saved conversation back.
                val history = if (id !in configured) withContext(io) { store.loadTranscript(id, core.info.id) } else null
                core.configure(id, config.copy(history = history))
                configured[id] = config
            }
        }
    }

    /**
     * Told when the user stops a session ([deleted] false) or deletes it: e.g. end its jobs in Termux.
     * [files]: the user asked to delete its files in Termux too.
     */
    var onEnded: (id: String, deleted: Boolean, files: Boolean) -> Unit = { _, _, _ -> }

    /** Told just before a session's core is given a new task: per-task tool state starts over (see PhoneTools). */
    var onTaskStarted: (id: String) -> Unit = {}

    /** Sessions the user stopped, until their next task: why their calls are refused (see stoppedByUser). */
    private val stopped = HashSet<String>()

    /** The user stopped [id]'s task (and no new one started since). */
    fun stoppedByUser(id: String) = id in stopped

    /** Stop the session's task (its core aborts the run); a queued task is dropped. */
    fun stop(id: String) {
        val s = live[id]?.value ?: return
        if (!s.busy) return
        stopped += id
        Log.d(TAG, "stop $id (${s.status})")
        onEnded(id, false, false)
        stops[id] = (stops[id] ?: 0) + 1
        prompted -= id
        paused[id]?.value = false
        queue.remove(id)
        if (s.status != SessionStatus.Queued) coreOf(id)?.let { c -> scope.launch { c.abort(id) } }
        withdrawAll(id)
        update(id) { SessionReducer.stopped(it, System.currentTimeMillis()) }
        endScreen(id) // kept a while if the user is on its page: they may correct the task and go on
    }

    /** Tell session [id]'s core to end what it still has going, also when the session itself is idle. */
    suspend fun abandon(id: String) { coreOf(id)?.abort(id) }

    fun stopAll() = live.values.map { it.value }.filter { it.busy }.forEach { stop(it.id) }

    /** Hold the session before its next action (what it is doing now finishes). */
    fun pause(id: String) {
        val s = live[id]?.value ?: return
        if (s.status != SessionStatus.Running && s.status != SessionStatus.Queued) return
        paused.getOrPut(id) { MutableStateFlow(false) }.value = true
        update(id) { SessionReducer.withStatus(it, SessionStatus.Paused, System.currentTimeMillis()) }
    }

    fun resume(id: String) {
        val s = live[id]?.value ?: return
        if (s.status != SessionStatus.Paused) return
        paused[id]?.value = false
        val st = if (id in queue) SessionStatus.Queued else SessionStatus.Running
        update(id) { SessionReducer.withStatus(it, st, System.currentTimeMillis()) }
    }

    fun rename(id: String, title: String) {
        if (live[id] != null) update(id) { it.copy(summary = it.summary.copy(title = title.trim())) }
        else summary(id)?.let { s -> s.copy(title = title.trim()).also { publish(it); scope.launch(io) { store.saveSummary(it) } } }
    }

    /** Remove the session for good: stop it, drop its agent, delete its files ([termuxFiles]: its Termux folder too). */
    fun delete(id: String, termuxFiles: Boolean = false) {
        val core = coreOf(id) // read before the session is forgotten
        stop(id)
        onEnded(id, true, termuxFiles)
        screens.release(id)
        live.remove(id); watchers.remove(id); leftWhileBusy.remove(id); paused.remove(id); stops.remove(id); configured.remove(id)
        pendingWrites.remove(id)?.cancel()
        _summaries.update { l -> l.filterNot { it.id == id } }
        core?.let { c -> scope.launch { c.dispose(id) } }
        scope.launch(io) { store.delete(id) }
    }

    // ---- from the cores -------------------------------------------------------------------------------

    /** Core [core]'s event about session [id] (see AgentCore.events). */
    fun onCoreEvent(core: String, id: String, m: JSONObject) {
        val owner = live[id]?.value?.summary?.core ?: summary(id)?.core
        if (owner != core) { Log.w(TAG, "$core sent ${m.optString("type")} for $id, which runs on $owner"); return }
        if (m.optString("type") == "transcript") {
            val messages = m.optJSONArray("messages") ?: JSONArray()
            scope.launch(io) { runCatching { store.saveTranscript(id, core, messages) }.onFailure { Log.e(TAG, "transcript $id not saved", it) } }
            return
        }
        if (live[id] == null) {
            // Not in memory (nobody looks at it, and it was idle), and its core works on: e.g. DeepSeek Harness waking
            // it when its subagents report back. Load it, then apply what came meanwhile, in order.
            if (summary(id) == null) { Log.w(TAG, "message for an unknown session: $id ${m.optString("type")}"); return }
            val waiting = arrivedWhileLoading.getOrPut(id) { mutableListOf() }
            waiting += core to m
            if (waiting.size == 1) scope.launch {
                loadLive(id)
                arrivedWhileLoading.remove(id)?.forEach { (c, e) -> onCoreEvent(c, id, e) }
            }
            return
        }
        if (m.optString("type") == "steer_late") { lateSteer(id, m.optString("text")); return }
        if (m.optString("type") == "configured") m.optJSONArray("commands")?.let { onCommands(core, it) }
        if (m.optString("type") == "command_result" && m.optString("kind") == "unknown") {
            m.put("text", strings.unknownCommand(core, m.optString("name"), _commands.value[core].orEmpty().map { it.name }))
        }
        val wasBusy = live[id]!!.value.busy
        if (m.optString("type") == "agent_start" && !wasBusy && id in stopped) {
            // The user stopped this session: work its core starts by itself afterwards is stopped too.
            Log.w(TAG, "$id: its core started work after the user stopped it; stopping it again")
            coreOf(id)?.let { c -> scope.launch { c.abort(id) } }
            return
        }
        update(id) { SessionReducer.reduce(it, m, System.currentTimeMillis(), strings::friendly) }
        if (wasBusy && !live[id]!!.value.busy) finished(id)
        if (!wasBusy && live[id]!!.value.busy) {
            // A run the core started by itself: it can be steered, and a phone session needs its screen back.
            prompted += id
            if (live[id]!!.value.summary.phone) screens.acquire(id)
        }
    }

    /** Core [core] listed its slash commands (its `configured` event): kept, and saved when they changed. */
    private fun onCommands(core: String, a: JSONArray) {
        val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            com.lanqi.weiassist.core.CoreCommand(it.optString("name"), it.optString("description"), it.optString("hint"))
        }.filter { it.name.isNotEmpty() }
        if (_commands.value[core] == list) return
        _commands.update { it + (core to list) }
        val all = _commands.value
        scope.launch(io) { runCatching { store.saveCommands(all) }.onFailure { Log.w(TAG, "commands not saved", it) } }
    }

    /** A session's core events that came while it was being loaded into memory (see onCoreEvent). */
    private val arrivedWhileLoading = HashMap<String, MutableList<Pair<String, JSONObject>>>()

    /**
     * Core [core]'s runtime died: its agents are gone. Its running tasks end; the next message restores each
     * conversation. Sessions on other cores are untouched.
     */
    fun onRuntimeRestarted(core: String) {
        val ours = _summaries.value.filter { it.core == core }.map { it.id }.toSet()
        configured.keys.retainAll { it !in ours }
        prompted.removeAll(ours)
        val now = System.currentTimeMillis()
        live.keys.filter { it in ours }.forEach { id ->
            withdrawAll(id)
            val wasBusy = live[id]!!.value.busy
            update(id) { SessionReducer.runtimeLost(it, strings.runtimeRestarted(), now) }
            if (wasBusy) finished(id)
        }
        queue.removeAll(ours)
    }

    /**
     * The gate a core asks before every tool call: suspends while the session is paused. False = the user
     * stopped it (or it no longer exists), so the core ends the run.
     */
    suspend fun gate(id: String): Boolean {
        val gen = stops[id] ?: 0
        val p = paused[id] ?: return live[id]?.value?.busy == true
        if (p.value) p.first { !it || (stops[id] ?: 0) != gen }
        return (stops[id] ?: 0) == gen && live[id]?.value?.busy == true
    }

    fun isPaused(id: String) = paused[id]?.value == true

    /** The core that runs session [id], or null if it is unknown or its core isn't installed. */
    private fun coreOf(id: String): AgentCore? = (live[id]?.value?.summary ?: summary(id))?.core?.let { cores[it] }

    /** The screen session [id] operates now, if it holds one. */
    fun screenOf(id: String) = screens.target(id)

    // ---- internals ------------------------------------------------------------------------------------

    /** A session's task ended; [onMainScreen]: it was operating the phone's own screen, so another app is in front now. */
    data class Finished(val id: String, val onMainScreen: Boolean)

    private val _finished = kotlinx.coroutines.flow.MutableSharedFlow<Finished>(extraBufferCapacity = 16)
    /** A session's task ended (done or failed); the service brings the app back or reports it. */
    val finished: kotlinx.coroutines.flow.SharedFlow<Finished> = _finished

    private fun finished(id: String) {
        prompted -= id
        // The run is over: nothing waits for an answer any more.
        withdrawAll(id)
        val onMain = screens.target(id) == com.lanqi.weiassist.phone.Target.Main // read before the screen is given up
        endScreen(id)
        _finished.tryEmit(Finished(id, onMain))
        evictIfUnused(id)
    }

    /** The task ended: the screen is kept a while for a follow-up, unless the user already left the session's page. */
    private fun endScreen(id: String) = releaseScreen(id, now = leftWhileBusy.remove(id))

    /**
     * The session's task ended ([now] = false: its screen may be kept for a follow-up) or it was stopped
     * or deleted ([now] = true). Then the longest-waiting phone session gets a screen, if one is free.
     */
    private fun releaseScreen(id: String, now: Boolean) {
        if (now) screens.release(id) else screens.idle(id)
        startQueued()
    }

    /** Start queued phone sessions while screens are free. */
    fun startQueued() {
        while (queue.isNotEmpty()) {
            val next = queue.first()
            if (live[next]?.value?.status !in setOf(SessionStatus.Queued, SessionStatus.Paused)) { queue.removeFirst(); continue }
            if (screens.acquire(next) == null) return
            queue.removeFirst()
            // A paused one keeps the screen; the gate holds it before its first action until resumed.
            start(next)
        }
    }

    private fun update(id: String, f: (SessionState) -> SessionState) {
        val flow = live[id] ?: return
        val before = flow.value
        val after = f(before)
        if (after === before) return
        flow.value = after
        if (after.summary != before.summary) publish(after.summary)
        if (after.summary != before.summary || after.items !== before.items) schedulePersist(id)
    }

    private fun publish(s: SessionSummary) = _summaries.update { l ->
        (listOf(s) + l.filterNot { it.id == s.id }).sortedByDescending { it.updatedAt }
    }

    /** Write a session soon (coalesced: a burst of steps is one write). */
    private fun schedulePersist(id: String) {
        if (pendingWrites[id]?.isActive == true) return
        pendingWrites[id] = scope.launch { delay(400); write(id) }
    }

    /** Write a session now (it is leaving memory): this exact state, as it won't be in [live] any more. */
    private fun flush(s: SessionState) {
        pendingWrites.remove(s.id)?.cancel()
        scope.launch { write(s) }
    }

    private suspend fun write(id: String) { live[id]?.value?.let { write(it) } }

    private suspend fun write(s: SessionState) {
        val id = s.id
        withContext(io) {
            runCatching { store.saveSummary(s.summary); store.saveItems(id, s.items) }
                .onFailure { Log.e(TAG, "session $id not saved", it) }
        }
    }

    /** What a session's agent is set up with: the provider, model and thinking level in use, and dev builds' mock server. */
    data class RunSettings(val provider: String, val model: String, val thinking: String, val mock: String?)

    companion object {
        private const val TAG = "Sessions"
        /** A slash command: `/name` alone or followed by a space, as dsh's commands parse them. */
        private val COMMAND = Regex("""^/[a-z0-9_-]+(\s|$)""")
    }
}
