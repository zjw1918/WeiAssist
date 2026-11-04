package com.lanqi.weiassist.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.R
import com.lanqi.weiassist.channels.Link
import kotlinx.coroutines.launch

/**
 * Telegram in Settings: connect a bot (its token from @BotFather), pair the user's own Telegram account
 * with a one-time code, and turn receiving on or off. See Gateway.
 */
@Composable
fun TelegramSection(vm: AppViewModel) {
    val s by vm.telegram.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!s.configured) Connect(vm) else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.bot?.let { "@$it" } ?: stringResource(R.string.telegram_title), style = MaterialTheme.typography.bodyLarge)
                    val (text, bad) = when {
                        s.error != null -> s.error.orEmpty() to true
                        !s.enabled -> stringResource(R.string.telegram_off) to false
                        else -> when (val l = s.link) {
                            Link.Online -> stringResource(R.string.telegram_online) to false
                            Link.Connecting, Link.Off -> stringResource(R.string.telegram_connecting) to false
                            is Link.Retrying -> stringResource(R.string.telegram_retrying, l.problem) to true
                        }
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.enabled, onCheckedChange = vm::telegramEnabled)
            }

            Text(stringResource(R.string.telegram_accounts), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            if (s.owners.isEmpty()) Text(stringResource(R.string.telegram_no_accounts), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.owners.forEach { o ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.name.ifBlank { o.id }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.removeTelegramOwner(o.id) }) { Text(stringResource(R.string.telegram_remove_account)) }
                }
            }
            val p = s.pairing
            if (p == null) FilledTonalButton(onClick = vm::pairTelegram) { Text(stringResource(R.string.telegram_pair)) }
            else Pairing(p, s.bot, vm)

            Text(stringResource(R.string.telegram_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.telegram_background), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = vm::forgetTelegram) { Text(stringResource(R.string.telegram_forget), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Not set up yet: how to make a bot, and its token. */
@Composable
private fun Connect(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var token by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun go() {
        if (busy || token.isBlank()) return
        busy = true; error = null
        scope.launch { error = vm.connectTelegram(token); busy = false; if (error == null) token = "" }
    }
    Text(stringResource(R.string.telegram_why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.telegram_steps), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        token, { token = it; error = null }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(stringResource(R.string.telegram_token)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { go() }),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
    FilledTonalButton(enabled = !busy && token.isNotBlank(), onClick = ::go) {
        Text(stringResource(if (busy) R.string.telegram_connecting_button else R.string.telegram_connect))
    }
    Text(stringResource(R.string.telegram_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A code waiting to be sent to the bot: open Telegram with it, or type it there. */
@Composable
private fun Pairing(p: com.lanqi.weiassist.agent.Gateway.Pairing, bot: String?, vm: AppViewModel) {
    val ctx = LocalContext.current
    Text(stringResource(R.string.telegram_pair_how, bot.orEmpty()), style = MaterialTheme.typography.bodyMedium)
    SelectionContainer {
        Text(p.code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        p.url?.let { url ->
            FilledTonalButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(stringResource(R.string.telegram_open)) }
        }
        TextButton(onClick = vm::cancelTelegramPairing) { Text(stringResource(R.string.cancel)) }
    }
    Text(stringResource(R.string.telegram_pair_once), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
