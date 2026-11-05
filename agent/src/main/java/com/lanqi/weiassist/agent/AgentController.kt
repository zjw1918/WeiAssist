package com.lanqi.weiassist.agent

import android.content.Context
import com.lanqi.weiassist.phone.AgentAccessibilityService
import com.lanqi.weiassist.phone.PhoneActions
import com.lanqi.weiassist.core.CoreAccounts
import com.lanqi.weiassist.core.CoreEvent
import com.lanqi.weiassist.core.CoreRegistry
import com.lanqi.weiassist.core.ToolHost
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The agent, independent of any screen: owns the AI cores ([cores]: Pi for now), the tools they call
 * ([tools]), the account and model choice ([state]), and all conversations ([sessions]). Owned by
 * [AgentService], so tasks keep running when the user leaves the app.
 */
class AgentController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pi = PiEngine(appContext, agentName = appContext.getString(R.string.agent_name))
    val termux = Termux(appContext)
    /** DeepSeek Harness, run in Termux (a prototype: chosen for new sessions with the debug extra `--es core dsh`). */
    private val dsh = com.lanqi.weiassist.dsh.DshEngine(
        launch = { env -> termux.launchDsh(env) },
        proxy = com.lanqi.weiassist.dsh.KeyProxy(::dshRoute, log = { android.util.Log.w("DshEngine", it) }),
        // The same providers, keys and sign-ins as Pi's (Pi keeps them): dsh has no settings of its own.
        routes = { accounts.routes() },
        log = { android.util.Log.d("DshEngine", it) },
    )
    /** The installed AI cores; a session runs on the one it was made with. */
    private val cores = CoreRegistry(listOf(pi, dsh), defaultId = PiEngine.ID)
    /** Sign-in, providers and the model catalog: Pi keeps them, and every core uses the model chosen there. */
    private val accounts: CoreAccounts = pi
    /** The app's side of every core's tool calls (registered below). */
    private val tools = ToolHost()
    private val phone = PhoneActions(appContext)
    /** The phone tools' behavior and safety, for every core (see PhoneTools). */
    private val phoneTools = com.lanqi.weiassist.phone.PhoneTools(::runPhone)
    /** Sessions the supervisor started in its current task (see ToolRouter.MAX_CREATES). */
    private val creates = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /**
     * Calls before_tool allowed and "tool" hasn't run yet, as "session/call". A core runs a tool only after asking
     * (so a core in another process, or anything that reaches its socket, can't skip the user's OK).
     */
    private val allowed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
        accounts.customProviders = { CustomProvider.listToJson(_customProviders.value) }
    }

    /** Dev builds: send model calls to the local mock server (tools/mock-openai.mjs). */
    @Volatile var mockBaseUrl: String? = null

    private val shell = com.lanqi.weiassist.shell.ShellBackend.get(appContext)
    /** A virtual display per phone session while the shell helper runs; the phone's own screen otherwise. */
    private val screens = DisplayScreenPool(shell)

    val sessions = SessionManager(
        cores, SessionStore(appContext.filesDir), scope,
        object : SessionManager.Strings {
            override fun friendly(msg: String) = this@AgentController.friendly(msg)
            override fun runtimeRestarted() = appContext.getString(R.string.err_runtime_restarted)
            override fun continueTask() = appContext.getString(R.string.continue_task)
            override fun missingCore(core: String) = appContext.getString(R.string.err_missing_core, core)
        },
        settings = { with(_state.value) { SessionManager.RunSettings(providerId, modelId, thinkingLevel, mockBaseUrl) } },
        screens = screens,
    )

    init {
        screens.isActive = { id -> sessions.summary(id)?.status?.active == true }
        // Stop and Delete end the session's Termux jobs too; its files there are kept unless the user asked otherwise.
        sessions.onTaskStarted = { id -> phoneTools.taskStarted(id); creates.remove(id); allowed.removeIf { it.startsWith("$id/") } }
        sessions.onEnded = { id, deleted, files ->
            gateway.cancelTransfers(id)
            if (deleted) { phoneTools.forget(id); creates.remove(id) }
            scope.launch { termux.cleanUp(id, deleted, files) }
        }
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

    /** Talking to the assistant from Telegram: one conversation per chat (see Gateway). */
    val gateway = Gateway(appContext, sessions, termux, scope).also { g ->
        // /screen: the session's own display, or the phone's screen while that session is operating it.
        g.screenshot = { id ->
            when (val t = sessions.screenOf(id)) {
                is com.lanqi.weiassist.phone.Target.Virtual -> kotlinx.coroutines.withContext(Dispatchers.IO) { t.io.screenshot(1080, 80) }.takeIf { it.isNotEmpty() }
                com.lanqi.weiassist.phone.Target.Main -> AgentAccessibilityService.instance?.screenshot(1080)
                    ?.takeUnless { it.startsWith("error") }?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                null -> null
            }
        }
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
        // Every core's tools are answered here, per session.
        // Before every tool call: the pause gate, then for a phone session its safety checks (PhoneTools.check).
        // Answers null to go on, or { block, terminate, reason }.
        tools.register("before_tool") { m ->
            val id = m.getString("session")
            val tool = m.optString("tool")
            val summary = sessions.summary(id)
            val call = m.optString("call")
            val verdict = when {
                !sessions.gate(id) -> block(
                    // Say which: the user stopped it, or its task is over (not the user's doing).
                    if (sessions.stoppedByUser(id)) "Stopped by the user." else "This session's task has ended, so nothing may run now.",
                )
                summary?.phone == true ->
                    phoneTools.check(id, tool, m.optJSONObject("args") ?: JSONObject()) { c -> confirmTap(id, c) }?.let(::block)
                // The supervisor may start a few sessions per request; past that it is told to ask (the run goes on).
                summary?.supervisor == true && tool == "create_session" && creates.merge(id, 1, Int::plus)!! > ToolRouter.MAX_CREATES ->
                    block(ToolRouter.TOO_MANY_CREATES, terminate = false)
                else -> null
            }
            if (verdict == null && call.isNotEmpty()) allowed += "$id/$call"
            verdict
        }
        // Every tool call, by name (see ToolRouter and ToolCatalog); answers a ToolResult.
        tools.register("tool") { m ->
            val id = m.getString("session")
            val call = m.optString("call")
            // Only a call before_tool allowed, once.
            if (call.isEmpty() || !allowed.remove("$id/$call")) com.lanqi.weiassist.core.ToolResult("This call was not checked first; it did not run.", isError = true).toJson()
            else router.call(id, call, m.getString("name"), m.optJSONObject("args") ?: JSONObject()).toJson()
        }
        // The user stopped the run while a call worked: end what it started (a bash command in Termux).
        tools.register("tool_cancel") { m -> router.cancel(m.getString("session"), m.optString("call")); null }
        cores.bind(tools)
        scope.launch { cores.events.collect(::onCore) }
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
            launch { gateway.start() }
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

    /** Runs every core's tool calls (see ToolCatalog). */
    private val router by lazy {
        ToolRouter(
            summary = sessions::summary,
            gate = sessions::gate,
            phone = phoneTools::call,
            bash = termux::run,
            bashKill = termux::kill,
            sendFile = gateway::sendFile,
            supervisor = commands::handle,
        )
    }

    private fun block(reason: String, terminate: Boolean = true) =
        JSONObject().put("block", true).put("terminate", terminate).put("reason", reason)

    /** One raw action on session [id]'s screen, for [phoneTools]. Only the session holding a screen may act on it (see ScreenPool). */
    private suspend fun runPhone(id: String, action: String, args: JSONObject): String {
        val target = sessions.screenOf(id)
        return when {
            target == null -> "This task has no screen any more (the shell helper stopped, or another task took it). Stop and tell the user."
            // Paused (or stopped) since the gate let this call through: hold it here too.
            !sessions.gate(id) -> "stopped by the user"
            else -> phone.run(JSONObject().put("session", id).put("action", action).put("args", args), target) { sessions.isPaused(id) }
        }
    }

    /**
     * Ask the user to allow a tap on an irreversible button (or a blind one): on the phone's overlay card and,
     * for a conversation from Telegram, in its chat. The first answer wins.
     */
    private suspend fun confirmTap(id: String, c: com.lanqi.weiassist.phone.PhoneTools.Confirmation): Boolean {
        val detail = if (c.typed.isEmpty()) appContext.getString(R.string.confirm_nothing_typed) else c.typed.joinToString("\n")
        val title = sessions.summary(id)?.title.orEmpty()
        val head = if (c.blind) appContext.getString(R.string.confirm_title_blind)
        else appContext.getString(R.string.confirm_title, c.label)
        val question = if (title.isBlank()) head else "$head\n· $title"
        val payment = !c.blind && Confirmations.isPayment(c.label)
        val asks = listOfNotNull<suspend () -> Boolean?>(
            AgentAccessibilityService.instance?.let { card -> suspend { card.confirm(question, detail) } },
            if (gateway.canAsk(id)) suspend { gateway.confirm(id, "$question\n\n$detail", payment) } else null,
        )
        return if (asks.isEmpty()) false
        else Confirmations.first(
            // Stopped meanwhile: nobody needs to answer any more (the questions are withdrawn).
            asks + suspend { sessions.summaries.first { l -> l.none { it.id == id && it.status.active } }; false },
            timeoutMs = if (asks.size > 1) REMOTE_CONFIRM_MS else null,
        )
    }

    // ---- cores → state ----------------------------------------------------------------------------
    private fun onCore(e: CoreEvent) {
        val m = e.message
        val session = m.optString("session")
        if (session.isNotEmpty()) {
            // A new run: whether it has left 小维助手 yet starts over (see PhoneActions.runStarted).
            if (m.optString("type") == "agent_start") phone.runStarted(session)
            sessions.onCoreEvent(e.core, session, m)
            return
        }
        // App-wide events: account ones only from the accounts core; any core may restart.
        if (e.core != pi.info.id) {
            if (m.optString("type") == "runtime_restarted") sessions.onRuntimeRestarted(e.core)
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
            Effect.RuntimeRestarted -> sessions.onRuntimeRestarted(e.core)
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
        scope.launch { accounts.login(provider, method.type) }
    }

    fun submitPrompt(value: String) {
        val p = _state.value.signIn.prompt ?: return
        if (value.isBlank()) return
        _state.update { it.copy(signIn = it.signIn.copy(prompt = null)) }
        scope.launch { accounts.answer(p.id, value.trim()) }
    }

    fun reopenBrowser() { _state.value.signIn.authUrl?.let { _openInBrowser.tryEmit(it) } }
    fun cancelSignIn() { scope.launch { accounts.cancelLogin() } }

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
        scope.launch { accounts.setCustomProviders(json, p.id, k) }
    }

    /** Remove a custom provider and its saved key; if it was in use, Pi's next status moves to another. */
    fun deleteCustomProvider(id: String) {
        val list = _customProviders.value.filter { it.id != id }
        _customProviders.value = list
        val json = CustomProvider.listToJson(list)
        prefs.edit().putString("customProviders", json.toString()).remove("modelId.$id").apply()
        scope.launch { accounts.setCustomProviders(json) }
    }

    /**
     * List a server's models, with what is known of each (server figures, else Pi's catalog); [key] blank =
     * the key saved for [savedFor], if any. Returns (base URL to use, models).
     */
    suspend fun fetchCustomModels(baseUrl: String, key: String?, savedFor: String?): Pair<String, List<CustomModel>> {
        val (base, listed) = CustomProvider.fetchModels(baseUrl, key?.ifBlank { null } ?: savedFor?.let(accounts::savedApiKey))
        val catalog = lookupCatalog(listed.map { it.id })
        return base to listed.map { CustomProvider.merge(it, catalog[it.id]) }
    }

    /** Typed model ids, with what Pi's catalog knows of them. */
    suspend fun describeCustomModels(ids: List<String>): List<CustomModel> {
        val catalog = lookupCatalog(ids)
        return ids.map { CustomProvider.merge(ServerModel(it), catalog[it]) }
    }

    private suspend fun lookupCatalog(ids: List<String>): Map<String, CatalogModel> {
        val o = runCatching { accounts.lookupModels(ids) }.getOrElse { JSONObject() }
        return o.keys().asSequence().associateWith { id ->
            val m = o.getJSONObject(id)
            CatalogModel(m.optInt("contextWindow"), m.optBoolean("images", true), m.optString("match"))
        }
    }

    /** Sign out of the provider in use; Pi's next status moves to another signed-in one, if any. */
    fun signOut() {
        sessions.stopAll()
        val id = _state.value.providerId
        scope.launch { accounts.logout(id) }
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

    /** Debug builds only: simulate the cores' runtimes dying (Pi's WebView renderer), to test recovery on a device. */
    fun debugCrashRuntime() = cores.all.forEach { it.debugCrash() }

    /** The AI core new sessions use ("pi", "dsh"); unknown ids are ignored. */
    fun selectCore(id: String) { if (cores[id] != null) cores.defaultId = id }

    /**
     * Where a dsh model route really goes, with the key the app's key proxy puts in: Pi's current auth for that
     * provider (an API key, or a sign-in's token Pi refreshes), asked per request on the proxy's thread; in dev mode
     * the mock server.
     */
    private fun dshRoute(route: String): com.lanqi.weiassist.dsh.KeyProxy.Route? {
        if (route == com.lanqi.weiassist.dsh.DshEngine.MOCK_ROUTE) return mockBaseUrl?.let { com.lanqi.weiassist.dsh.KeyProxy.Route(it, "mock") }
        val auth = runCatching { kotlinx.coroutines.runBlocking { accounts.providerAuth(route) } }
            .onFailure { android.util.Log.w("DshEngine", "no auth for $route: ${it.message}") }.getOrNull() ?: return null
        return com.lanqi.weiassist.dsh.KeyProxy.Route(auth.baseUrl ?: return null, auth.apiKey ?: return null, auth.headers)
    }

    /** Debug builds only: new phone sessions use the phone's own screen, as without the shell helper. */
    fun debugMainOnly(on: Boolean) { screens.debugMainOnly = on }

    fun destroy() {
        cores.destroy()
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
        /** A question also asked in a chat goes unanswered this long: not allowed (the task doesn't hold its screen for ever). */
        const val REMOTE_CONFIRM_MS = 15 * 60_000L
        val HTTP_402 = Regex("""\b402\b""")
    }
}
