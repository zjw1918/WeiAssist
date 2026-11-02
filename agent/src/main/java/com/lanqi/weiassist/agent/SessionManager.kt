package com.lanqi.weiassist.agent

import android.util.Log
import com.lanqi.weiassist.pi.PiEngine
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
 * All conversations. Each session is its own Pi Agent in the one Pi runtime, so sessions run side by
 * side and keep running when their screen is closed; they end when their task is done or the user
 * stops them. Main thread only (state changes), with disk work on one background lane.
 *
 * Memory: every session's [SessionSummary] is kept (small). A session's chat ([SessionState]) is in
 * memory only while it is busy or open on screen ([attach]); otherwise it is on disk only. Pi's
 * transcript lives inside Pi while the session's Agent exists, and on disk.
 */
class SessionManager(
    private val pi: PiEngine,
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
    }

    private val io = Dispatchers.IO.limitedParallelism(1)
    private val _summaries = MutableStateFlow<List<SessionSummary>>(emptyList())
    /** Every session, most recently active first. */
    val summaries: StateFlow<List<SessionSummary>> = _summaries.asStateFlow()

    /** Sessions held in memory: busy ones and those on screen. */
    private val live = HashMap<String, MutableStateFlow<SessionState>>()
    /** How many screens show each session ([attach]/[detach]). */
    private val watchers = HashMap<String, Int>()
    /** Pi Agent settings per session in the current runtime; a session not in here has no Agent yet. */
    private val configured = HashMap<String, String>()
    private val paused = HashMap<String, MutableStateFlow<Boolean>>()
    /** Bumped per session when the user stops it, so a gate waiting in a paused session gives up. */
    private val stops = HashMap<String, Int>()
    private val queue = ArrayDeque<String>()
    /** The session whose page the user has open ([view]). */
    private var viewing: String? = null
    /** Sessions the user left while they worked: their apps close as soon as the task ends. */
    private val leftWhileBusy = HashSet<String>()
    /**
     * Sessions whose task Pi has been given (PiHost.prompt called) and not yet ended: only these can be
     * steered. Calls to Pi run in order, so a steer after the prompt always finds Pi's run.
     */
    private val prompted = HashSet<String>()
    private val pendingWrites = HashMap<String, Job>()

    /** Load the saved sessions. Sessions that were working when the process ended are marked Interrupted. */
    suspend fun load() {
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
        // A steered message still queued may come back from Pi (steer_late) and start the next task.
        if (s.busy || (watchers[id] ?: 0) > 0 || s.items.any { (it as? ChatItem.User)?.delivery == Delivery.Queued }) return
        flush(s)
        live.remove(id)
    }

    fun summary(id: String) = _summaries.value.firstOrNull { it.id == id }

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

    /** A new, empty session. [phone] = it may operate the phone; [supervisor] = it manages the others. */
    fun create(phone: Boolean, title: String = "", supervisor: Boolean = false): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString().substring(0, 8)
        val summary = SessionSummary(id, title, phone && !supervisor, SessionStatus.Idle, now, now, supervisor = supervisor)
        live[id] = MutableStateFlow(SessionState(summary))
        publish(summary)
        scope.launch(io) { store.saveSummary(summary) }
        return id
    }

    /**
     * The user's message to a session: start (or queue) a task. While the session works, the message
     * steers it instead (Pi reads it after the current turn). False if the session is unknown.
     */
    suspend fun send(id: String, text: String, attachments: List<Attachment> = emptyList()): Boolean {
        if (text.isBlank() && attachments.isEmpty()) return false
        val flow = loadLive(id) ?: return false
        // Staged files move into the session's folder: they live as long as it does.
        val files = withContext(io) { Attachments.keep(attachments, store.attachmentsDir(id)) }
        if (flow.value.busy) {
            val now = System.currentTimeMillis()
            // Pi hasn't been given this task yet (it waits for a screen, or is being set up): the message joins it.
            if (id !in prompted) update(id) { SessionReducer.joinTask(it, text.trim(), now, files) }
            else {
                update(id) { SessionReducer.steered(it, text.trim(), now, files) }
                pi.steer(id, text.trim(), Attachments.content(files))
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

    /** Pi's run ended just before the steered [text] reached it: send it as the next task. */
    private fun lateSteer(id: String, text: String) {
        val s = live[id]?.value ?: return
        if (s.busy) { // a new task started meanwhile: steer that one
            val files = s.items.firstOrNull { it is ChatItem.User && it.delivery == Delivery.Queued && it.text == text }?.let { (it as ChatItem.User).attachments }.orEmpty()
            if (id in prompted) scope.launch { pi.steer(id, text, Attachments.content(files)) } else Log.w(TAG, "late steer for $id dropped: next task not started")
            return
        }
        val canRun = !s.summary.phone || screens.acquire(id) != null
        update(id) { SessionReducer.lateSteer(it, text, queued = !canRun, now = System.currentTimeMillis()) }
        paused.getOrPut(id) { MutableStateFlow(false) }.value = false
        if (canRun) start(id) else queue.addLast(id)
    }

    /** An interrupted session (the app's process ended mid-task) picks up where it was. */
    suspend fun continueTask(id: String) = send(id, strings.continueTask())

    private fun start(id: String) {
        val s = live[id]?.value ?: return
        if (s.pendingTask == null && s.pendingAttachments.isEmpty()) return
        if (s.status == SessionStatus.Queued) update(id) { SessionReducer.withStatus(it, SessionStatus.Running, System.currentTimeMillis()) }
        scope.launch {
            val (provider, model, thinking, mock) = settings()
            val phone = s.summary.phone
            val tools = when { s.summary.supervisor -> "supervisor"; phone -> "phone"; else -> null }
            val config = listOf(provider, model, thinking, mock, tools).joinToString("|")
            if (configured[id] != config) {
                // First use in this runtime: give Pi the saved conversation back.
                val history = if (id !in configured) withContext(io) { store.loadTranscript(id) } else null
                pi.configure(id, provider, model, thinking, mock, tools = tools, messages = history)
                configured[id] = config
            }
            // Read now: messages typed while Pi was being set up joined the task (see send).
            val now = live[id]?.value?.takeIf { it.busy } ?: return@launch
            val task = now.pendingTask ?: if (now.pendingAttachments.isNotEmpty()) "" else return@launch
            pi.prompt(id, task, Attachments.content(now.pendingAttachments))
            prompted += id
        }
    }

    /** Told when the user stops a session ([deleted] false) or deletes it: e.g. end its jobs in Termux. */
    var onEnded: (id: String, deleted: Boolean) -> Unit = { _, _ -> }

    /** Stop the session's task (Pi's Agent.abort); a queued task is dropped. */
    fun stop(id: String) {
        val s = live[id]?.value ?: return
        if (!s.busy) return
        Log.d(TAG, "stop $id (${s.status})")
        onEnded(id, false)
        stops[id] = (stops[id] ?: 0) + 1
        prompted -= id
        paused[id]?.value = false
        queue.remove(id)
        if (s.status != SessionStatus.Queued) scope.launch { pi.abort(id) }
        update(id) { SessionReducer.stopped(it, System.currentTimeMillis()) }
        endScreen(id) // kept a while if the user is on its page: they may correct the task and go on
    }

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

    /** Remove the session for good: stop it, drop its Agent, delete its files. */
    fun delete(id: String) {
        stop(id)
        onEnded(id, true)
        screens.release(id)
        live.remove(id); watchers.remove(id); leftWhileBusy.remove(id); paused.remove(id); stops.remove(id); configured.remove(id)
        pendingWrites.remove(id)?.cancel()
        _summaries.update { l -> l.filterNot { it.id == id } }
        scope.launch { pi.dispose(id) }
        scope.launch(io) { store.delete(id) }
    }

    // ---- from Pi --------------------------------------------------------------------------------------

    /** Pi's message about session [id]. */
    fun onPi(id: String, m: JSONObject) {
        if (m.optString("type") == "transcript") {
            val messages = m.optJSONArray("messages") ?: JSONArray()
            scope.launch(io) { runCatching { store.saveTranscript(id, messages) }.onFailure { Log.e(TAG, "transcript $id not saved", it) } }
            return
        }
        if (live[id] == null) { Log.w(TAG, "message for a session not in memory: $id ${m.optString("type")}"); return }
        if (m.optString("type") == "steer_late") { lateSteer(id, m.optString("text")); return }
        val wasBusy = live[id]!!.value.busy
        update(id) { SessionReducer.reduce(it, m, System.currentTimeMillis(), strings::friendly) }
        if (wasBusy && !live[id]!!.value.busy) finished(id)
    }

    /** The runtime died: every Agent is gone. Running tasks end; the next message restores each conversation. */
    fun onRuntimeRestarted() {
        configured.clear()
        prompted.clear()
        val now = System.currentTimeMillis()
        live.keys.toList().forEach { id ->
            val wasBusy = live[id]!!.value.busy
            update(id) { SessionReducer.runtimeLost(it, strings.runtimeRestarted(), now) }
            if (wasBusy) finished(id)
        }
        queue.clear()
    }

    /**
     * Pi's beforeToolCall gate: suspends while the session is paused. False = the user stopped it
     * (or it no longer exists), so Pi ends the run.
     */
    suspend fun gate(id: String): Boolean {
        val gen = stops[id] ?: 0
        val p = paused[id] ?: return live[id]?.value?.busy == true
        if (p.value) p.first { !it || (stops[id] ?: 0) != gen }
        return (stops[id] ?: 0) == gen && live[id]?.value?.busy == true
    }

    fun isPaused(id: String) = paused[id]?.value == true

    /** The screen session [id] operates now, if it holds one. */
    fun screenOf(id: String) = screens.target(id)

    // ---- internals ------------------------------------------------------------------------------------

    private val _finished = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** A session's task ended (done, failed or stopped); the service reports it if no one is watching. */
    val finished: kotlinx.coroutines.flow.SharedFlow<String> = _finished

    private fun finished(id: String) {
        prompted -= id
        endScreen(id)
        _finished.tryEmit(id)
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
            // A paused one keeps the screen; Pi's gate holds it before its first action until resumed.
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

    /** What a session's Pi Agent is set up with: the provider, model and thinking level in use, and dev builds' mock server. */
    data class RunSettings(val provider: String, val model: String, val thinking: String, val mock: String?)

    companion object { private const val TAG = "Sessions" }
}
