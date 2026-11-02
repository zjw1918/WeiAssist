package com.lanqi.weiassist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.agent.AuthMethod
import com.lanqi.weiassist.agent.PendingPrompt
import com.lanqi.weiassist.agent.ProviderInfo

/** Step 1: pick how to connect. The two cards are Pi's own auth methods for the provider. */
@Composable
fun WelcomeScreen(provider: ProviderInfo?, piVersion: String?, error: String?, onPick: (AuthMethod) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("你好，我是小维助手", style = MaterialTheme.typography.headlineMedium)
        Text("先给我接上一个大模型。我用 Pi ${piVersion ?: ""} 和模型对话，演示用的是 OpenAI，以后换别家也行。",
            style = MaterialTheme.typography.bodyLarge)
        if (provider == null) {
            CircularProgressIndicator()
            return@Column
        }
        // Subscription first: most people already pay for ChatGPT and have no API account.
        provider.methods.sortedByDescending { it.subscription }.forEach { m ->
            if (m.type == "oauth") MethodCard(
                title = "用 ChatGPT 订阅登录",
                body = "用你的 ChatGPT 账号登录，额度算在订阅里，不用另外充值。会打开 Chrome，登录并同意后回到这里。",
                note = m.label ?: m.name, primary = true, onClick = { onPick(m) },
            ) else MethodCard(
                title = "用 OpenAI API key",
                body = "按用量付费，和 ChatGPT 订阅分开计费：需要在 platform.openai.com 绑卡或充值，再创建一个密钥。",
                note = m.name, primary = false, onClick = { onPick(m) },
            )
        }
        error?.let { Text("上次没连上：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun MethodCard(title: String, body: String, note: String, primary: Boolean, onClick: () -> Unit) {
    val colors = if (primary) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    Card(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Text("Pi：$note", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Step 2a: ChatGPT subscription. Pi opened the sign-in page; we wait for Chrome to come back to 127.0.0.1:1455. */
@Composable
fun SubscriptionSignIn(
    authUrl: String?, progress: String?, prompt: PendingPrompt?,
    onReopen: () -> Unit, onPaste: (String) -> Unit, onCancel: () -> Unit,
) {
    var showPaste by rememberSaveable { mutableStateOf(false) }
    var pasted by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("在 Chrome 里登录 ChatGPT", style = MaterialTheme.typography.headlineSmall)
        Steps(listOf(
            "Chrome 会打开 OpenAI 的登录页，用你的 ChatGPT 账号登录",
            "OpenAI 会问你是否允许“小维助手”使用你的 ChatGPT，确认允许",
            "点了允许以后，如果页面一直在转圈，点左上角的 ✕ 回到这里，登录会自动完成",
        ))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("  " + (progress ?: if (authUrl == null) "正在准备登录页…" else "等你在 Chrome 里完成登录…"))
        }
        if (authUrl != null) OutlinedButton(onClick = onReopen) { Text("重新打开登录页") }
        // Pi races its callback server against this manual step; it only appears while Pi is asking.
        if (prompt?.type == "manual_code") {
            TextButton(onClick = { showPaste = !showPaste }) { Text(if (showPaste) "收起" else "Chrome 没跳回来？") }
            if (showPaste) {
                Text("把 Chrome 地址栏里以 ${prompt.placeholder ?: "http://127.0.0.1:1455"} 开头的完整地址复制过来：",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(pasted, { pasted = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("回调地址") })
                Button(onClick = { onPaste(pasted) }, enabled = pasted.isNotBlank()) { Text("用这个地址登录") }
            }
        }
        TextButton(onClick = onCancel) { Text("取消") }
    }
}

/** Step 2b: API key. Pi's api-key method asks for a "secret"; we answer it with what the user pastes. */
@Composable
fun ApiKeySignIn(prompt: PendingPrompt?, progress: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("填入 OpenAI API key", style = MaterialTheme.typography.headlineSmall)
        Steps(listOf(
            "在 platform.openai.com 的 Billing 里绑卡或充值（ChatGPT 订阅不包含 API 额度）",
            "在 API keys 页面点 Create new secret key，复制以 sk- 开头的密钥",
            "粘贴到下面。密钥只存在这台手机上，用系统密钥库加密",
        ))
        OutlinedButton(onClick = { uri.openUri("https://platform.openai.com/api-keys") }) { Text("打开 API keys 页面") }
        OutlinedTextField(
            key, { key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            label = { Text(prompt?.message ?: "OpenAI API key") }, placeholder = { Text("sk-…") }, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSubmit(key) }, enabled = prompt != null && key.startsWith("sk-")) { Text("连接") }
        progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onCancel) { Text("取消") }
    }
}

@Composable
private fun Steps(lines: List<String>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
        }
    }
    Spacer(Modifier.height(0.dp))
}
