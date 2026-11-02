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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lanqi.phoneagent.ui.ApiKeySignIn
import com.lanqi.phoneagent.ui.ModelPicker
import com.lanqi.phoneagent.ui.SubscriptionSignIn
import com.lanqi.phoneagent.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { App() } }
    }
}

@Composable
fun MainActivity.App(vm: AppViewModel = viewModel()) {
    // Pi asks for the sign-in page to be opened; show it in Chrome as a Custom Tab.
    LaunchedEffect(Unit) {
        vm.openInBrowser.collect { url -> CustomTabsIntent.Builder().build().launchUrl(this@App, url.toUri()) }
    }
    Scaffold { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = vm.screen) {
                Screen.Starting -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Text("  正在启动 Pi…") }
                Screen.Welcome -> WelcomeScreen(vm.provider, vm.piVersion, vm.signInError, vm::signIn)
                is Screen.SigningIn ->
                    if (s.method.type == "oauth") SubscriptionSignIn(vm.authUrl, vm.progress, vm.prompt, vm::reopenBrowser, vm::submitPrompt, vm::cancelSignIn)
                    else ApiKeySignIn(vm.prompt, vm.progress, vm::submitPrompt, vm::cancelSignIn)
                Screen.Connected -> ConnectedScreen(vm)
            }
        }
    }
}

/** Step 3: connected. The first 你好 is sent automatically right after sign-in. */
@Composable
fun ConnectedScreen(vm: AppViewModel) {
    val how = when (vm.provider?.credential) { "oauth" -> "ChatGPT 订阅"; "api_key" -> "API key"; else -> "?" }
    Text("已连接 OpenAI · $how", style = MaterialTheme.typography.titleLarge)
    Text("Pi ${vm.piVersion}（pi-agent-core + pi-ai，运行在 WebView 里）", style = MaterialTheme.typography.bodySmall)
    ModelPicker(
        models = vm.provider?.models.orEmpty(), selected = vm.model, thinkingLevel = vm.thinkingLevel,
        onModel = vm::selectModel, onThinking = vm::selectThinking, subscription = vm.provider?.credential == "oauth",
    )
    OutlinedTextField(vm.input, { vm.input = it }, label = { Text("发给 Pi") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = vm::send, enabled = !vm.busy) { Text(if (vm.busy) "Pi 正在回答…" else "发送") }
    if (vm.answer.isNotEmpty() || vm.error != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("回答", style = MaterialTheme.typography.labelMedium)
                if (vm.answer.isNotEmpty()) Text(vm.answer, style = MaterialTheme.typography.bodyLarge)
                vm.error?.let { Text("出错：$it", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (vm.trace.isNotEmpty()) {
        Text("Pi 事件", style = MaterialTheme.typography.labelMedium)
        Text(vm.trace.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
    if (BuildConfig.DEBUG) Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = vm.useMock, onCheckedChange = { vm.useMock = it })
        Text("  开发用：发到本地模拟服务器", style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = vm::signOut) { Text("退出登录") }
}
