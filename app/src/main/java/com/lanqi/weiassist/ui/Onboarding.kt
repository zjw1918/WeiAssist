package com.lanqi.weiassist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.AuthMethod
import com.lanqi.weiassist.agent.PendingPrompt
import com.lanqi.weiassist.agent.ProviderInfo

/** Step 1: pick how to connect. The cards are Pi's own auth methods, for every provider Pi offers here. */
@Composable
fun WelcomeScreen(providers: List<ProviderInfo>, piVersion: String?, error: String?, onPick: (String, AuthMethod) -> Unit, onCustom: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.welcome_body, piVersion.orEmpty()),
            style = MaterialTheme.typography.bodyLarge)
        if (providers.isEmpty()) {
            CircularProgressIndicator()
            return@Column
        }
        providers.forEach { p ->
            // Subscription first: most people already pay for ChatGPT and have no API account.
            p.methods.sortedByDescending { it.subscription }.forEach { m ->
                val (title, body) = methodText(p, m)
                MethodCard(title, body, note = m.label ?: m.name, primary = m.subscription, onClick = { onPick(p.id, m) })
            }
        }
        // The user's own OpenAI-compatible server (CustomProviderScreen).
        MethodCard(stringResource(R.string.custom_add_title), stringResource(R.string.custom_add_row), note = null, primary = false, onClick = onCustom)
        error?.let { Text(stringResource(R.string.last_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * What the app knows about a provider beyond Pi's name and auth methods: where its API keys are made
 * and how they start. Optional: a provider Pi offers that isn't listed still gets generic sign-in text.
 * OpenAI and DeepSeek keep their own wording (billing there trips people up).
 */
private data class ProviderGuide(
    val keysPage: String,
    val keyPrefix: String? = null,
    val cardTitle: Int? = null, val cardBody: Int? = null,
    val keyTitle: Int? = null, val keySteps: List<Int>? = null,
)

private val GUIDES = mapOf(
    "openai" to ProviderGuide("https://platform.openai.com/api-keys", "sk-",
        R.string.method_key_title, R.string.method_key_body, R.string.key_title, listOf(R.string.key_step1, R.string.key_step2)),
    "deepseek" to ProviderGuide("https://platform.deepseek.com/api_keys", "sk-",
        R.string.method_deepseek_title, R.string.method_deepseek_body, R.string.key_title_deepseek, listOf(R.string.key_deepseek_step1, R.string.key_deepseek_step2)),
    // Zen: pay per use. Go: a monthly subscription; both use an API key made at opencode.ai.
    "opencode" to ProviderGuide("https://opencode.ai/auth"),
    "opencode-go" to ProviderGuide("https://opencode.ai/auth", cardBody = R.string.method_opencode_go_body),
    "anthropic" to ProviderGuide("https://console.anthropic.com/settings/keys", "sk-ant-"),
    "google" to ProviderGuide("https://aistudio.google.com/apikey", "AIza"),
    "openrouter" to ProviderGuide("https://openrouter.ai/settings/keys", "sk-or-"),
    "xai" to ProviderGuide("https://console.x.ai", "xai-"),
    "moonshotai" to ProviderGuide("https://platform.moonshot.ai/console/api-keys", "sk-"),
    "moonshotai-cn" to ProviderGuide("https://platform.moonshot.cn/console/api-keys", "sk-"),
    "mistral" to ProviderGuide("https://console.mistral.ai/api-keys"),
)

private fun siteOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url

/** What a sign-in method is called in this app, and what it involves. */
@Composable
fun methodText(p: ProviderInfo, m: AuthMethod): Pair<String, String> {
    val g = GUIDES[p.id]
    return when {
        // The OAuth sign-ins offered (Pi host's OAUTH_PROVIDERS); SubscriptionSignIn's text is about ChatGPT too.
        p.id == "openai" && m.type == "oauth" -> stringResource(R.string.method_sub_title) to stringResource(R.string.method_sub_body)
        p.id == "xai" && m.type == "oauth" -> stringResource(R.string.method_xai_title) to stringResource(R.string.method_xai_body)
        m.type == "oauth" -> (m.label ?: m.name) to ""
        else -> (g?.cardTitle?.let { stringResource(it) } ?: stringResource(R.string.method_key_generic_title, p.name)) to
            (g?.cardBody?.let { stringResource(it) } ?: g?.let { stringResource(R.string.method_key_generic_body, siteOf(it.keysPage)) } ?: "")
    }
}

@Composable
private fun MethodCard(title: String, body: String, note: String?, primary: Boolean, onClick: () -> Unit) {
    val colors = if (primary) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    Card(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyMedium)
            if (note != null) Text(stringResource(R.string.method_pi, note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * Step 2a, device-code flavor (xAI): Pi got a code from the provider and polls until the user approves it
 * on the provider's page. The page is opened by the user (the code is shown first, and copied on the way).
 */
@Composable
fun DeviceCodeSignIn(providerName: String, userCode: String?, progress: String?, onOpen: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.device_title, providerName), style = MaterialTheme.typography.headlineSmall)
        Steps(listOf(
            stringResource(R.string.device_step1),
            stringResource(R.string.device_step2),
            stringResource(R.string.device_step3),
        ))
        if (userCode == null) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("  " + (progress ?: stringResource(R.string.preparing_page)))
        } else {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.device_code), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer {
                        Text(userCode, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Button(onClick = { copyText(ctx, userCode); onOpen() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_open)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("  " + (progress ?: stringResource(R.string.device_waiting)))
            }
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

/** Step 2b: API key. Pi's api-key method asks for a "secret"; we answer it with what the user pastes. */
@Composable
fun ApiKeySignIn(provider: ProviderInfo?, prompt: PendingPrompt?, progress: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    val name = provider?.name ?: "…"
    val g = GUIDES[provider?.id]
    val prefix = g?.keyPrefix
    val wrongPrefix = prefix != null && key.isNotBlank() && !key.trim().startsWith(prefix)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(g?.keyTitle?.let { stringResource(it) } ?: stringResource(R.string.key_title_generic, name), style = MaterialTheme.typography.headlineSmall)
        Steps(
            (g?.keySteps?.map { stringResource(it) } ?: listOf(
                stringResource(R.string.key_generic_step1, g?.let { siteOf(it.keysPage) } ?: name),
                stringResource(R.string.key_generic_step2),
            )) + stringResource(R.string.key_step3),
        )
        g?.let { OutlinedButton(onClick = { uri.openUri(it.keysPage) }) { Text(stringResource(R.string.open_keys_page)) } }
        OutlinedTextField(
            key, { key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            label = { Text(prompt?.message ?: "API key") }, placeholder = { Text((prefix ?: "") + "…") }, modifier = Modifier.fillMaxWidth(),
            isError = wrongPrefix,
            supportingText = if (wrongPrefix) { { Text(stringResource(R.string.key_prefix_hint, name, prefix!!)) } } else null,
        )
        Button(onClick = { onSubmit(key.trim()) }, enabled = prompt != null && key.isNotBlank() && !wrongPrefix) { Text(stringResource(R.string.connect)) }
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
