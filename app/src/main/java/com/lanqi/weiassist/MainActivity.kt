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
import com.lanqi.weiassist.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    /** Debug builds: a task passed with `--es task "…"`, on first launch or to an open screen. */
    val debugTask = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) intent.getStringExtra("task")?.let { debugTask.value = it }
        // Debug builds: `--ez crashPi true` crashes Pi's renderer to test recovery.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("crashPi", false)) viewModels<AppViewModel>().value.debugCrashRuntime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) debugTask.value = intent.getStringExtra("task")
        enableEdgeToEdge()
        setContent { MaterialTheme { App() } }
    }
}

@Composable
fun MainActivity.App(vm: AppViewModel = viewModel()) {
    // Debug builds: `adb shell am start -n com.lanqi.weiassist/.MainActivity --es task "…"` fills the task box.
    LaunchedEffect(debugTask.value) {
        debugTask.value?.let { vm.input = it; vm.phoneMode = true; debugTask.value = null }
    }
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
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val screen = s.screen) {
                Screen.Starting -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Text("  " + stringResource(R.string.starting_pi)) }
                Screen.Welcome -> WelcomeScreen(s.provider, s.piVersion, s.signIn.error, vm::signIn)
                is Screen.SigningIn ->
                    if (screen.method.type == "oauth") SubscriptionSignIn(s.signIn.authUrl, s.signIn.progress, s.signIn.prompt, vm::reopenBrowser, vm::submitPrompt, vm::cancelSignIn)
                    else ApiKeySignIn(s.signIn.prompt, s.signIn.progress, vm::submitPrompt, vm::cancelSignIn)
                Screen.Connected -> ConnectedScreen(vm, s)
            }
        }
    }
}

/** Step 3: connected. The first 你好 is sent automatically right after sign-in. */
@Composable
fun ConnectedScreen(vm: AppViewModel, s: AgentState) {
    val run = s.run
    val how = when (s.provider?.credential) { "oauth" -> stringResource(R.string.auth_subscription); "api_key" -> stringResource(R.string.auth_api_key); else -> "?" }
    Text(stringResource(R.string.connected_title, how), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.pi_runtime_info, s.piVersion.orEmpty()), style = MaterialTheme.typography.bodySmall)
    ModelPicker(
        models = s.provider?.models.orEmpty(), selected = s.model, thinkingLevel = s.thinkingLevel,
        onModel = vm::selectModel, onThinking = vm::selectThinking, subscription = s.provider?.credential == "oauth",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = vm.phoneMode, onCheckedChange = { vm.phoneMode = it })
        Text("  " + stringResource(R.string.phone_mode))
    }
    val accessibilityOn by vm.accessibilityOn.collectAsStateWithLifecycle()
    if (vm.phoneMode && !accessibilityOn) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Text(stringResource(R.string.a11y_needed), color = MaterialTheme.colorScheme.error)
        androidx.compose.material3.OutlinedButton(onClick = {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text(stringResource(R.string.open_a11y_settings)) }
    }
    OutlinedTextField(vm.input, { vm.input = it }, label = { Text(stringResource(if (vm.phoneMode) R.string.task_label else R.string.chat_label)) }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (run.busy) {
            Button(onClick = vm::stop) { Text(stringResource(R.string.stop)) }
            Text(stringResource(R.string.working), style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick = vm::send) { Text(stringResource(R.string.send)) }
            TextButton(onClick = vm::newConversation) { Text(stringResource(R.string.new_chat)) }
        }
    }
    if (run.answer.isNotEmpty() || run.error != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.answer), style = MaterialTheme.typography.labelMedium)
                if (run.answer.isNotEmpty()) Text(run.answer, style = MaterialTheme.typography.bodyLarge)
                run.error?.let { Text(stringResource(R.string.error_prefix, it), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (run.trace.isNotEmpty()) {
        Text(stringResource(R.string.pi_events), style = MaterialTheme.typography.labelMedium)
        Text(run.trace.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
    if (BuildConfig.DEBUG) Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = vm.useMock, onCheckedChange = { vm.useMock = it })
        Text("  " + stringResource(R.string.dev_mock), style = MaterialTheme.typography.bodySmall)
    }
    // Signing out deletes the saved credential, so ask first.
    var confirmSignOut by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    TextButton(onClick = { confirmSignOut = true }) { Text(stringResource(R.string.sign_out)) }
    if (confirmSignOut) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text(stringResource(R.string.sign_out_title)) },
        text = { Text(stringResource(R.string.sign_out_text)) },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text(stringResource(R.string.sign_out_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
