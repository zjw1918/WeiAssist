package com.lanqi.phoneagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.phoneagent.agent.AgentState
import com.lanqi.phoneagent.agent.Screen
import com.lanqi.phoneagent.ui.ApiKeySignIn
import com.lanqi.phoneagent.ui.ModelPicker
import com.lanqi.phoneagent.ui.SubscriptionSignIn
import com.lanqi.phoneagent.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    /** Debug builds: a task passed with `--es task "…"`, on first launch or to an open screen. */
    val debugTask = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) intent.getStringExtra("task")?.let { debugTask.value = it }
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
    // Debug builds: `adb shell am start -n com.lanqi.phoneagent/.MainActivity --es task "…"` fills the task box.
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
                Screen.Starting -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Text("  正在启动 Pi…") }
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
    val how = when (s.provider?.credential) { "oauth" -> "ChatGPT 订阅"; "api_key" -> "API key"; else -> "?" }
    Text("已连接 OpenAI · $how", style = MaterialTheme.typography.titleLarge)
    Text("Pi ${s.piVersion}（pi-agent-core + pi-ai，运行在后台服务里）", style = MaterialTheme.typography.bodySmall)
    ModelPicker(
        models = s.provider?.models.orEmpty(), selected = s.model, thinkingLevel = s.thinkingLevel,
        onModel = vm::selectModel, onThinking = vm::selectThinking, subscription = s.provider?.credential == "oauth",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = vm.phoneMode, onCheckedChange = { vm.phoneMode = it })
        Text("  让小维助手操作手机")
    }
    if (vm.phoneMode && !vm.accessibilityOn) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Text("需要先在“设置 → 无障碍”里打开小维助手，它才能看屏幕、点按钮。", color = MaterialTheme.colorScheme.error)
        androidx.compose.material3.OutlinedButton(onClick = {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text("打开无障碍设置") }
    }
    OutlinedTextField(vm.input, { vm.input = it }, label = { Text(if (vm.phoneMode) "要小维助手做什么" else "发给 Pi") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (run.busy) {
            Button(onClick = vm::stop) { Text("停止") }
            Text("小维助手正在工作…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick = vm::send) { Text("发送") }
            TextButton(onClick = vm::newConversation) { Text("新对话") }
        }
    }
    if (run.answer.isNotEmpty() || run.error != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("回答", style = MaterialTheme.typography.labelMedium)
                if (run.answer.isNotEmpty()) Text(run.answer, style = MaterialTheme.typography.bodyLarge)
                run.error?.let { Text("出错：$it", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (run.trace.isNotEmpty()) {
        Text("Pi 事件", style = MaterialTheme.typography.labelMedium)
        Text(run.trace.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
    if (BuildConfig.DEBUG) Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = vm.useMock, onCheckedChange = { vm.useMock = it })
        Text("  开发用：发到本地模拟服务器", style = MaterialTheme.typography.bodySmall)
    }
    // Signing out deletes the saved credential, so ask first.
    var confirmSignOut by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    TextButton(onClick = { confirmSignOut = true }) { Text("退出登录") }
    if (confirmSignOut) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("退出登录？") },
        text = { Text("退出后，这台手机上保存的登录信息会被删除，下次要重新登录。") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("退出") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("取消") } },
    )
}
