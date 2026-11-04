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
import org.json.JSONObject

/**
 * The agent, independent of any screen: owns the Pi runtime (PiEngine), the account and model choice
 * ([state]), and all conversations ([sessions]). Owned by [AgentService], so tasks keep running when
 * the user leaves the app.
 */
class AgentController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pi = PiEngine(appContext, agentName = appContext.getString(R.string.agent_name))
    private val phone = PhoneActions(appContext)
    val termux = Termux(appContext)
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        AgentState(
            providerId = prefs.getString("providerId", null) ?: AgentReducer.DEFAULT_PROVIDER,
            modelId = prefs.getString("modelId", null) ?: "",
            thinkingLevel = prefs.getString("thinkingLevel", null) ?: "medium",
        ),
    )
    val state: StateFlow<AgentState> = _state.asStateFlow()

    /** The user's own OpenAI-compatible providers, in the order they were added. */
    private val _customProviders = MutableStateFlow(CustomProvider.listFromJson(prefs.getString("customProviders", null)))
    val customProviders: StateFlow<List<CustomProvider>> = _customProviders.asStateFlow()

    init {
        // Sent with Pi's init, so a custom provider in use is there before the first status.
        pi.customProviders = { CustomProvider.listToJson(_customProviders.value) }
    }

    /** Dev builds: send model calls to the local mock server (tools/mock-openai.mjs). */
    @Volatile var mockBaseUrl: String? = null

    private val shell = com.lanqi.weiassist.shell.ShellBackend.get(appContext)
    /** A virtual display per phone session while the shell helper runs; the phone's own screen otherwise. */
    private val screens = DisplayScreenPool(shell)

    val sessions = SessionManager(
        pi, SessionStore(appContext.filesDir), scope,
        object : SessionManager.Strings {
            override fun friendly(msg: String) = this@AgentController.friendly(msg)
            override fun runtimeRestarted() = appContext.getString(R.string.err_runtime_restarted)
            override fun continueTask() = appContext.getString(R.string.continue_task)
        },
        settings = { with(_state.value) { SessionManager.RunSettings(providerId, modelId, thinkingLevel, mockBaseUrl) } },
        screens = screens,
    )

    init {
        screens.isActive = { id -> sessions.summary(id)?.status?.active == true }
        // Stop and Delete end the session's Termux jobs too; its files there are kept unless the user asked otherwise.
        sessions.onEnded = { id, deleted, files -> scope.launch { termux.cleanUp(id, deleted, files) } }
    }

    /**
     * The commands that manage sessions (the supervisor's tools; later voice). Deleting asks the user on the
     * overlay card, which shows over any app; without the accessibility service there is no asking: no deleting.
     */
    val commands = SessionCommands(
        sessions,
        confirm = { title, detail -> AgentAccessibilityService.instance?.confirm(title, detail) ?: false },
        confirmDeleteTitle = { appContext.getString(R.string.confirm_delete_session, it) },
    )

    /**
     * Say something to the supervisor (typed, or later spoken): the one entry point for managing sessions in
     * words. Returns the supervisor's session id, or null if it is busy with the previous request.
     */
    suspend fun command(text: String): String? {
        loaded.await()
        val id = sessions.supervisor(appContext.getString(R.string.supervisor_title))
        return if (sessions.send(id, text)) id else null
    }

    /** Live pictures of the sessions' displays. */
    val previews = PreviewHub(appContext, shell)
    /** Each phone session's virtual display (while it has one). */
    val displays get() = screens.displays

    /** One-shot: Pi wants this URL opened in a browser (sign-in). */
    private val _openInBrowser = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val openInBrowser: SharedFlow<String> = _openInBrowser

    /** Done once the saved sessions are loaded. */
    val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        // Pi's phone tools, its confirm hook and the pause gate (pi-host) are answered per session.
        pi.registerHostCall("gate") { m -> sessions.gate(m.getString("session")) }
        pi.registerHostCall("phone") { m ->
            val id = m.optString("session")
            // Only the session holding a screen may act on it (see ScreenPool).
            val target = sessions.screenOf(id)
            when {
                target == null -> "This task has no screen any more (the shell helper stopped, or another task took it). Stop and tell the user."
                // Paused (or stopped) since Pi's gate let this call through: hold it here too.
                !sessions.gate(id) -> "stopped by the user"
                else -> phone.run(m, target) { sessions.isPaused(id) }
            }
        }
        // The bash tool (pi-host bash-tool.ts): commands run in Termux, in the session's own folder.
        pi.registerHostCall("bash") { m ->
            val id = m.optString("session")
            if (!sessions.gate(id)) "stopped by the user"
            else termux.run(id, m.getString("call"), m.getString("command"), m.optInt("timeout", 120))
        }
        pi.registerHostCall("bash_kill") { m -> termux.kill(m.optString("session"), m.getString("call")); null }
        pi.registerHostCall("supervisor") { m ->
            val caller = m.optString("session")
            // Only a supervisor session may manage the others.
            if (sessions.summary(caller)?.supervisor != true) "Only the supervisor may manage sessions."
            else commands.handle(caller, m.getString("action"), m.optJSONObject("args") ?: JSONObject())
        }
        pi.registerHostCall("confirm") { m ->
            val fields = m.optJSONArray("typed")
            val detail = if (fields == null || fields.length() == 0) appContext.getString(R.string.confirm_nothing_typed)
            else (0 until fields.length()).joinToString("\n") { fields.getString(it) }
            val title = sessions.summary(m.optString("session"))?.title.orEmpty()
            val head = if (m.optBoolean("blind")) appContext.getString(R.string.confirm_title_blind)
            else appContext.getString(R.string.confirm_title, m.getString("label"))
            AgentAccessibilityService.instance?.confirm(if (title.isBlank()) head else "$head\n· $title", detail) ?: false
        }
        scope.launch { pi.messages.collect(::onPi) }
        // The shell helper came: queued phone sessions get displays. It went: so did its displays.
        scope.launch {
            shell.state.collect { st ->
                if (st is com.lanqi.weiassist.shell.ShellState.Running) { sessions.startQueued(); allowTermuxChildren() }
                else { screens.helperGone(); previews.reset() }
            }
        }
        // Attachments picked in an earlier process were never sent (the boxes holding them are gone).
        scope.launch(Dispatchers.IO) { Attachments.clearStaging(appContext) }
        scope.launch {
            sessions.load()
            loaded.complete(Unit)
            val ready = pi.start()
            _state.update { it.copy(piVersion = ready.optString("pi")) }
        }
    }

    /**
     * Android 12+ kills an app's child processes beyond 32 ("phantom processes"): that ends Termux builds, test
     * runs and servers. The shell helper may lift the limit (= Developer options → Disable child process restrictions).
     */
    private fun allowTermuxChildren() {
        if (android.os.Build.VERSION.SDK_INT < 31 || !termux.installed()) return
        scope.launch(Dispatchers.IO) {
            val s = shell.service() ?: return@launch
            runCatching {
                s.exec("device_config set_sync_disabled_for_tests persistent; " +
                    "device_config put activity_manager max_phantom_processes 2147483647; " +
                    "settings put global settings_enable_monitor_phantom_procs false", 10_000)
            }.onFailure { android.util.Log.w("AgentController", "could not lift the child process limit", it) }
        }
    }

    // ---- Pi → state -------------------------------------------------------------------------------
    private fun onPi(m: JSONObject) {
        val session = m.optString("session")
        if (session.isNotEmpty()) {
            // A new run: whether it has left 小维助手 yet starts over (see PhoneActions.runStarted).
            if (m.optString("type") == "agent_start") phone.runStarted(session)
            sessions.onPi(session, m)
            return
        }
        val before = _state.value
        val (next, effect) = AgentReducer.reduce(before, m)
        _state.value = next
        if (next.providerId != before.providerId || next.modelId != before.modelId || next.thinkingLevel != before.thinkingLevel) persistModel()
        when (effect) {
            is Effect.OpenBrowser -> _openInBrowser.tryEmit(effect.url)
            Effect.SignedIn -> scope.launch {
                loaded.await()
                sessions.send(sessions.create(phone = false), appContext.getString(R.string.first_hello))
            }
            Effect.RuntimeRestarted -> sessions.onRuntimeRestarted()
            null -> Unit
        }
    }

    // ---- sign-in ----------------------------------------------------------------------------------
    /** Sign in to [provider]; from the AI providers tab (already connected), cancelling goes back there. */
    fun signIn(provider: String, method: AuthMethod) {
        _state.update {
            val back = if (it.screen == Screen.Connected) Screen.Connected else Screen.Welcome
            it.copy(screen = Screen.SigningIn(provider, method, back), signIn = SignInState())
        }
        scope.launch { pi.login(provider, method.type) }
    }

    fun submitPrompt(value: String) {
        val p = _state.value.signIn.prompt ?: return
        if (value.isBlank()) return
        _state.update { it.copy(signIn = it.signIn.copy(prompt = null)) }
        scope.launch { pi.answer(p.id, value.trim()) }
    }

    fun reopenBrowser() { _state.value.signIn.authUrl?.let { _openInBrowser.tryEmit(it) } }
    fun cancelSignIn() { scope.launch { pi.cancelLogin() } }

    /** Copy a picked file in, ready to attach to a message (see Attachments). */
    suspend fun attach(uri: android.net.Uri): Attachment = Attachments.import(appContext, uri)

    // ---- custom providers --------------------------------------------------------------------------
    /**
     * Add or update a custom provider. A new [key] is saved with it and makes it the provider in use;
     * without one, an existing provider keeps its saved key.
     */
    fun saveCustomProvider(p: CustomProvider, key: String?) {
        val old = _customProviders.value
        val list = if (old.any { it.id == p.id }) old.map { if (it.id == p.id) p else it } else old + p
        _customProviders.value = list
        val json = CustomProvider.listToJson(list)
        prefs.edit().putString("customProviders", json.toString()).apply()
        val k = key?.trim()?.ifEmpty { null }
        if (k != null) {
            // Pi's next status has it signed in: the reducer keeps it as the provider in use.
            _state.update { it.copy(providerId = p.id, modelId = prefs.getString("modelId.${p.id}", null) ?: "") }
            persistModel()
        }
        scope.launch { pi.setCustomProviders(json, p.id, k) }
    }

    /** Remove a custom provider and its saved key; if it was in use, Pi's next status moves to another. */
    fun deleteCustomProvider(id: String) {
        val list = _customProviders.value.filter { it.id != id }
        _customProviders.value = list
        val json = CustomProvider.listToJson(list)
        prefs.edit().putString("customProviders", json.toString()).remove("modelId.$id").apply()
        scope.launch { pi.setCustomProviders(json) }
    }

    /**
     * List a server's models, with what is known of each (server figures, else Pi's catalog); [key] blank =
     * the key saved for [savedFor], if any. Returns (base URL to use, models).
     */
    suspend fun fetchCustomModels(baseUrl: String, key: String?, savedFor: String?): Pair<String, List<CustomModel>> {
        val (base, listed) = CustomProvider.fetchModels(baseUrl, key?.ifBlank { null } ?: savedFor?.let(pi::savedApiKey))
        val catalog = lookupCatalog(listed.map { it.id })
        return base to listed.map { CustomProvider.merge(it, catalog[it.id]) }
    }

    /** Typed model ids, with what Pi's catalog knows of them. */
    suspend fun describeCustomModels(ids: List<String>): List<CustomModel> {
        val catalog = lookupCatalog(ids)
        return ids.map { CustomProvider.merge(ServerModel(it), catalog[it]) }
    }

    private suspend fun lookupCatalog(ids: List<String>): Map<String, CatalogModel> {
        val o = runCatching { pi.lookupModels(ids) }.getOrElse { JSONObject() }
        return o.keys().asSequence().associateWith { id ->
            val m = o.getJSONObject(id)
            CatalogModel(m.optInt("contextWindow"), m.optBoolean("images", true), m.optString("match"))
        }
    }

    /** Sign out of the provider in use; Pi's next status moves to another signed-in one, if any. */
    fun signOut() {
        sessions.stopAll()
        val id = _state.value.providerId
        scope.launch { pi.logout(id) }
    }

    /** Use another signed-in provider for new messages, with the model last used with it. */
    fun selectProvider(id: String) {
        _state.update { AgentReducer.selectProvider(it, id, prefs.getString("modelId.$id", null)) }
        persistModel()
    }

    // ---- model and thinking level -----------------------------------------------------------------
    fun selectModel(id: String) {
        _state.update { AgentReducer.selectModel(it, id) }
        persistModel()
    }

    fun selectThinking(level: String) {
        _state.update { it.copy(thinkingLevel = level) }
        persistModel()
    }

    private fun persistModel() = with(_state.value) {
        prefs.edit().putString("providerId", providerId).putString("modelId", modelId).putString("modelId.$providerId", modelId)
            .putString("thinkingLevel", thinkingLevel).apply()
    }

    // ---- sessions ---------------------------------------------------------------------------------
    /** Start a task in a new session; returns its id. */
    suspend fun startTask(text: String, phoneTools: Boolean, attachments: List<Attachment> = emptyList()): String {
        loaded.await()
        val id = sessions.create(phone = phoneTools)
        sessions.send(id, text, attachments)
        return id
    }

    fun stop(id: String) {
        phone.cancelWaits() // release a phone action paused while the user looks at the app
        sessions.stop(id)
    }

    fun stopAll() {
        phone.cancelWaits()
        sessions.stopAll()
    }

    /** Debug builds only: simulate Pi's WebView renderer dying, to test recovery on a device. */
    fun debugCrashRuntime() = pi.debugCrashRenderer()

    /** Debug builds only: new phone sessions use the phone's own screen, as without the shell helper. */
    fun debugMainOnly(on: Boolean) { screens.debugMainOnly = on }

    fun destroy() {
        pi.destroy()
        scope.cancel()
    }

    // ---- helpers ----------------------------------------------------------------------------------
    /** Turn the provider's error text into something a user can act on; keep the original for the trace. */
    private fun friendly(msg: String): String = when {
        outOfCredit(msg) -> with(_state.value) {
            // OpenAI: people often expect a ChatGPT plan to cover the API, so say it doesn't.
            if (providerId == "openai") appContext.getString(R.string.err_quota)
            else appContext.getString(R.string.err_balance, provider?.name ?: providerId)
        } + "\n" + msg
        "401" in msg || "invalid_api_key" in msg || "Incorrect API key" in msg -> appContext.getString(R.string.err_key) + "\n" + msg
        "model_not_found" in msg || "model" in msg && ("not found" in msg || "does not exist" in msg) ->
            appContext.getString(R.string.err_model, _state.value.modelId) + "\n" + msg
        else -> msg
    }

    /** The provider's account has no credit left: 402, or how OpenAI, DeepSeek and Anthropic word it. */
    private fun outOfCredit(msg: String) = HTTP_402.containsMatchIn(msg) || "Insufficient Balance" in msg ||
        "insufficient_quota" in msg || "credit balance is too low" in msg || "429" in msg && "quota" in msg

    private companion object {
        val HTTP_402 = Regex("""\b402\b""")
    }
}
