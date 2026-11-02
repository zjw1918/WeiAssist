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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.agent.AgentState
import com.lanqi.weiassist.agent.Screen
import com.lanqi.weiassist.ui.ApiKeySignIn
import com.lanqi.weiassist.ui.ModelPicker
import com.lanqi.weiassist.ui.SubscriptionSignIn
import com.lanqi.weiassist.ui.SessionListScreen
import com.lanqi.weiassist.ui.SessionScreen
import com.lanqi.weiassist.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    /**
     * Debug builds: a task passed with `--es task "…"`. `--ez run true` starts it at once in a new session,
     * `--ez phone false` makes it a chat, `--ez mock true` sends it to the local mock model.
     */
    val debugTask = androidx.compose.runtime.mutableStateOf<String?>(null)
    var debugRun = false
    var debugPhone = true
    var debugMock: Boolean? = null

    /** Debug builds: `--es supervise "…"` says it to the supervisor. */
    val debugSupervise = androidx.compose.runtime.mutableStateOf<String?>(null)

    private fun readDebug(i: android.content.Intent) {
        if (!BuildConfig.DEBUG) return
        i.getStringExtra("supervise")?.let { debugSupervise.value = it; debugMock = if (i.hasExtra("mock")) i.getBooleanExtra("mock", false) else null }
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
        setContent { MaterialTheme { App() } }
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
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val ask = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) {}
        LaunchedEffect(Unit) { ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }
    val openId by vm.openId.collectAsStateWithLifecycle()
    Scaffold { inner ->
        if (s.screen == Screen.Connected) Column(Modifier.fillMaxSize().padding(inner)) {
            // The session list and a session's page manage their own scrolling.
            if (openId == null) SessionListScreen(vm, s)
            else {
                val session by vm.session.collectAsStateWithLifecycle()
                SessionScreen(vm, session)
            }
        } else Column(
            Modifier.fillMaxSize().padding(inner).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val screen = s.screen) {
                Screen.Starting -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Text("  " + stringResource(R.string.starting_pi)) }
                Screen.Welcome -> WelcomeScreen(s.provider, s.piVersion, s.signIn.error, vm::signIn)
                is Screen.SigningIn ->
                    if (screen.method.type == "oauth") SubscriptionSignIn(s.signIn.authUrl, s.signIn.progress, s.signIn.prompt, vm::reopenBrowser, vm::submitPrompt, vm::cancelSignIn)
                    else ApiKeySignIn(s.signIn.prompt, s.signIn.progress, vm::submitPrompt, vm::cancelSignIn)
                Screen.Connected -> Unit
            }
        }
    }
}
