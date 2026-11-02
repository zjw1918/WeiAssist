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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.AuthMethod
import com.lanqi.weiassist.agent.PendingPrompt
import com.lanqi.weiassist.agent.ProviderInfo

/** Step 1: pick how to connect. The two cards are Pi's own auth methods for the provider. */
@Composable
fun WelcomeScreen(provider: ProviderInfo?, piVersion: String?, error: String?, onPick: (AuthMethod) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.welcome_body, piVersion.orEmpty()),
            style = MaterialTheme.typography.bodyLarge)
        if (provider == null) {
            CircularProgressIndicator()
            return@Column
        }
        // Subscription first: most people already pay for ChatGPT and have no API account.
        provider.methods.sortedByDescending { it.subscription }.forEach { m ->
            if (m.type == "oauth") MethodCard(
                title = stringResource(R.string.method_sub_title),
                body = stringResource(R.string.method_sub_body),
                note = m.label ?: m.name, primary = true, onClick = { onPick(m) },
            ) else MethodCard(
                title = stringResource(R.string.method_key_title),
                body = stringResource(R.string.method_key_body),
                note = m.name, primary = false, onClick = { onPick(m) },
            )
        }
        error?.let { Text(stringResource(R.string.last_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun MethodCard(title: String, body: String, note: String, primary: Boolean, onClick: () -> Unit) {
    val colors = if (primary) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    Card(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.method_pi, note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(stringResource(R.string.sub_title), style = MaterialTheme.typography.headlineSmall)
        Steps(listOf(
            stringResource(R.string.sub_step1),
            stringResource(R.string.sub_step2),
            stringResource(R.string.sub_step3),
        ))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("  " + (progress ?: stringResource(if (authUrl == null) R.string.preparing_page else R.string.waiting_chrome)))
        }
        if (authUrl != null) OutlinedButton(onClick = onReopen) { Text(stringResource(R.string.reopen_page)) }
        // Pi races its callback server against this manual step; it only appears while Pi is asking.
        if (prompt?.type == "manual_code") {
            TextButton(onClick = { showPaste = !showPaste }) { Text(stringResource(if (showPaste) R.string.collapse else R.string.chrome_no_return)) }
            if (showPaste) {
                Text(stringResource(R.string.paste_hint, prompt.placeholder ?: "http://127.0.0.1:1455"),
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(pasted, { pasted = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.callback_url)) })
                Button(onClick = { onPaste(pasted) }, enabled = pasted.isNotBlank()) { Text(stringResource(R.string.use_this_url)) }
            }
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

/** Step 2b: API key. Pi's api-key method asks for a "secret"; we answer it with what the user pastes. */
@Composable
fun ApiKeySignIn(prompt: PendingPrompt?, progress: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.key_title), style = MaterialTheme.typography.headlineSmall)
        Steps(listOf(
            stringResource(R.string.key_step1),
            stringResource(R.string.key_step2),
            stringResource(R.string.key_step3),
        ))
        OutlinedButton(onClick = { uri.openUri("https://platform.openai.com/api-keys") }) { Text(stringResource(R.string.open_keys_page)) }
        OutlinedTextField(
            key, { key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            label = { Text(prompt?.message ?: "OpenAI API key") }, placeholder = { Text("sk-…") }, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSubmit(key) }, enabled = prompt != null && key.startsWith("sk-")) { Text(stringResource(R.string.connect)) }
        progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
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
