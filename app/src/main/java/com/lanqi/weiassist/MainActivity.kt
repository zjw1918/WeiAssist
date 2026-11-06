package com.lanqi.weiassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.agent.Screen
import com.lanqi.weiassist.ui.ApiKeySignIn
import com.lanqi.weiassist.ui.AppTheme
import com.lanqi.weiassist.ui.CustomProviderScreen
import com.lanqi.weiassist.ui.DeviceCodeSignIn
import com.lanqi.weiassist.ui.HomeScreen
import com.lanqi.weiassist.ui.Tab
import com.lanqi.weiassist.ui.SubscriptionSignIn
import com.lanqi.weiassist.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    /**
     * Debug builds (and -PdevTools=true release builds): a task passed with `--es task "…"`. `--ez run true` starts it at once in a new session,
     * `--ez phone false` makes it a chat, `--ez mock true` sends it to the local mock model.
     */
    val debugTask = androidx.compose.runtime.mutableStateOf<String?>(null)
    var debugRun = false
    var debugPhone = true
    var debugMock: Boolean? = null
    /** `--es core dsh|pi`: the AI core new sessions use (DeepSeek Harness is a prototype). */
    var debugCore: String? = null
    /** `--ez mainOnly true|false`: phone sessions use the phone's own screen, as without the shell helper. */
    var debugMainOnly: Boolean? = null

    /** Debug builds: `--es supervise "…"` says it to the supervisor. */
    val debugSupervise = androidx.compose.runtime.mutableStateOf<String?>(null)

    private fun readDebug(i: android.content.Intent) {
        if (!BuildConfig.DEV_TOOLS) return
        i.getStringExtra("supervise")?.let { debugSupervise.value = it; debugMock = if (i.hasExtra("mock")) i.getBooleanExtra("mock", false) else null }
        if (i.hasExtra("mainOnly")) debugMainOnly = i.getBooleanExtra("mainOnly", false)
        i.getStringExtra("core")?.let { debugCore = it }
        // `--ez noClipboard true|false`: act as if the clipboard were blocked, as on some phones (typing by keyboard).
        if (i.hasExtra("noClipboard")) com.lanqi.weiassist.phone.PhoneActions.debugNoClipboard = i.getBooleanExtra("noClipboard", false)
        val task = i.getStringExtra("task") ?: return
        debugRun = i.getBooleanExtra("run", false)
        debugPhone = i.getBooleanExtra("phone", true)
        debugMock = if (i.hasExtra("mock")) i.getBooleanExtra("mock", false) else null
        debugTask.value = task
    }

    /** A session to open (from a result notification). */
    val openSession = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(com.lanqi.weiassist.agent.AgentService.EXTRA_SESSION)?.let { openSession.value = it }
        readDebug(intent)
        // Debug builds: `--ez crashPi true` crashes Pi's renderer to test recovery.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("crashPi", false)) viewModels<AppViewModel>().value.debugCrashRuntime()
        // Debug builds: `--ez shellStart true` starts the shell helper through Wireless debugging.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("shellStart", false)) com.lanqi.weiassist.shell.ShellBackend.get(this).debugForceStart()
        // Debug builds: `--ez killProcess true` ends the process at once, as the system may, to test session recovery.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("killProcess", false)) android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readDebug(intent)
        if (savedInstanceState == null) openSession.value = intent.getStringExtra(com.lanqi.weiassist.agent.AgentService.EXTRA_SESSION)
        enableEdgeToEdge()
        setContent { AppTheme { App() } }
    }
}

@Composable
fun MainActivity.App(vm: AppViewModel = viewModel()) {
    // Debug builds: `adb shell am start -n com.lanqi.weiassist/.MainActivity --es task "…"` fills the task box;
    // with `--ez run true` it starts at once in a new session (add `--ez phone false` for a chat-only one).
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(debugTask.value, s.screen) {
        val task = debugTask.value ?: return@LaunchedEffect
        if (debugRun && s.screen != Screen.Connected) return@LaunchedEffect // wait for Pi and the account
        vm.input = task; vm.phoneMode = debugPhone; debugTask.value = null
        debugMock?.let(vm::setMock) // `--ez mock true`: the local mock model
        debugMainOnly?.let(vm::debugMainOnly)
        debugCore?.let(vm::selectCore)
        if (debugRun) { debugRun = false; vm.startTask() }
    }
    LaunchedEffect(debugSupervise.value, s.screen) {
        val text = debugSupervise.value ?: return@LaunchedEffect
        if (s.screen != Screen.Connected) return@LaunchedEffect
        debugMock?.let(vm::setMock); debugSupervise.value = null
        vm.askSupervisor(text)
    }
    LaunchedEffect(openSession.value) { openSession.value?.let { vm.open(it); openSession.value = null } }
    // Pi asks for the sign-in page to be opened; show it in Chrome as a Custom Tab.
    LaunchedEffect(Unit) {
        vm.openInBrowser.collect { url -> CustomTabsIntent.Builder().build().launchUrl(this@App, url.toUri()) }
    }
    // Android 13+: the "working" notification (with its Stop button) needs permission to show.
    // With Termux installed: permission to run the bash tool's commands in it (Termux.kt).
    val context = androidx.compose.ui.platform.LocalContext.current
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) {}
    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
            val termux = com.lanqi.weiassist.agent.Termux.PERMISSION
            val hasTermux = runCatching { context.packageManager.getPackageInfo(com.lanqi.weiassist.agent.Termux.PACKAGE, 0) }.isSuccess
            if (hasTermux) add(termux)
        }.filter { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) ask.launch(wanted.toTypedArray())
    }
    // Above the sign-in screens, so cancelling a sign-in from the AI providers tab returns to that tab.
    var homeTab by rememberSaveable { mutableStateOf(Tab.Sessions) }
    // The welcome screen's "custom provider" form is open.
    var welcomeCustom by rememberSaveable { mutableStateOf(false) }
    if (s.screen == Screen.Connected) {
        HomeScreen(vm, s, homeTab) { homeTab = it }
        return
    }
    Scaffold { inner ->
        if (s.screen == Screen.Starting) {
            Column(Modifier.fillMaxSize().padding(inner), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.starting_pi), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val screen = s.screen) {
                Screen.Welcome ->
                    if (welcomeCustom) CustomProviderScreen(vm, null) { welcomeCustom = false }
                    else WelcomeScreen(s.providers, s.piVersion, s.signIn.error, vm::signIn) { welcomeCustom = true }
                is Screen.SigningIn -> {
                    // Sign-in is a state, not a navigation destination: Back cancels it like the Cancel button,
                    // returning to where it started, instead of closing the app.
                    BackHandler { vm.cancelSignIn() }
                    if (screen.method.type == "oauth" && screen.provider == "xai")
                        DeviceCodeSignIn(s.providers.firstOrNull { it.id == screen.provider }?.name ?: "xAI", s.signIn.userCode, s.signIn.progress, vm::reopenBrowser, vm::cancelSignIn)
                    else if (screen.method.type == "oauth") SubscriptionSignIn(s.signIn.authUrl, s.signIn.progress, s.signIn.prompt, vm::reopenBrowser, vm::submitPrompt, vm::cancelSignIn)
                    else ApiKeySignIn(s.providers.firstOrNull { it.id == screen.provider }, s.signIn.prompt, s.signIn.progress, vm::submitPrompt, vm::cancelSignIn)
                }
                Screen.Starting, Screen.Connected -> Unit
            }
        }
    }
}
