package com.lanqi.weiassist.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.BuildConfig
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.AgentState
import com.lanqi.weiassist.agent.ChatItem
import com.lanqi.weiassist.agent.SessionState
import com.lanqi.weiassist.agent.SessionStatus
import com.lanqi.weiassist.agent.SessionSummary

// ---- session list ---------------------------------------------------------------------------------

/** Home: start a task, see every session (running ones first), settings at the bottom. */
@Composable
fun SessionListScreen(vm: AppViewModel, s: AgentState) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val busy = sessions.filter { it.status.active }
    val rest = sessions.filterNot { it.status.active }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { NewTaskCard(vm) }
        if (busy.size > 1) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.sessions_running, busy.size, busy.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::stopAll) { Text(stringResource(R.string.stop_all)) }
            }
        }
        items(busy, key = { it.id }) { SessionCard(it, vm) }
        if (rest.isNotEmpty()) item { Text(stringResource(R.string.sessions_history), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
        items(rest, key = { it.id }) { SessionCard(it, vm) }
        if (sessions.isEmpty()) item { Text(stringResource(R.string.sessions_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline) }
        item { SettingsCard(vm, s) }
    }
}

@Composable
private fun NewTaskCard(vm: AppViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                vm.input, { vm.input = it },
                label = { Text(stringResource(if (vm.phoneMode) R.string.task_label else R.string.chat_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.phoneMode, onCheckedChange = { vm.phoneMode = it })
                Text("  " + stringResource(R.string.phone_mode), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Button(onClick = vm::startTask, enabled = vm.input.isNotBlank()) { Text(stringResource(R.string.start)) }
            }
            val accessibilityOn by vm.accessibilityOn.collectAsStateWithLifecycle()
            if (vm.phoneMode && !accessibilityOn) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Text(stringResource(R.string.a11y_needed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text(stringResource(R.string.open_a11y_settings)) }
            }
        }
    }
}

@Composable
private fun SessionCard(s: SessionSummary, vm: AppViewModel) {
    Card(
        Modifier.fillMaxWidth().clickable { vm.open(s.id) },
        colors = if (s.status.active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                StatusChip(s.status)
            }
            if (s.preview.isNotBlank()) Text(
                s.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (s.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(if (s.phone) stringResource(R.string.kind_phone) else stringResource(R.string.kind_chat), s.app, DateUtils.getRelativeTimeSpanString(s.updatedAt).toString()).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f),
                )
                SessionControls(s, vm, compact = true)
            }
        }
    }
}

/** Pause / resume / stop / continue, as fits the session's status. */
@Composable
private fun SessionControls(s: SessionSummary, vm: AppViewModel, compact: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (s.status) {
            SessionStatus.Running, SessionStatus.Queued -> {
                TextButton(onClick = { vm.pause(s.id) }) { Text(stringResource(R.string.pause)) }
                TextButton(onClick = { vm.stop(s.id) }) { Text(stringResource(R.string.stop)) }
            }
            SessionStatus.Paused -> {
                TextButton(onClick = { vm.resume(s.id) }) { Text(stringResource(R.string.resume)) }
                TextButton(onClick = { vm.stop(s.id) }) { Text(stringResource(R.string.stop)) }
            }
            SessionStatus.Interrupted -> TextButton(onClick = { vm.continueTask(s.id) }) { Text(stringResource(R.string.continue_label)) }
            SessionStatus.Idle -> if (!compact) Unit
        }
    }
}

@Composable
private fun StatusChip(status: SessionStatus) {
    val (label, color) = when (status) {
        SessionStatus.Running -> stringResource(R.string.status_running) to Color(0xFF2E7D32)
        SessionStatus.Queued -> stringResource(R.string.status_queued) to Color(0xFF6D4C41)
        SessionStatus.Paused -> stringResource(R.string.status_paused) to Color(0xFFEF6C00)
        SessionStatus.Interrupted -> stringResource(R.string.status_interrupted) to Color(0xFFC62828)
        SessionStatus.Idle -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        if (status == SessionStatus.Running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = color)
        else Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
        Text(" $label", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun SettingsCard(vm: AppViewModel, s: AgentState) {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val how = when (s.provider?.credential) { "oauth" -> stringResource(R.string.auth_subscription); "api_key" -> stringResource(R.string.auth_api_key); else -> "?" }
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.settings_summary, how, s.model?.name ?: s.modelId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Text(if (open) "▲" else "▼")
            }
            if (open) {
                ModelPicker(
                    models = s.provider?.models.orEmpty(), selected = s.model, thinkingLevel = s.thinkingLevel,
                    onModel = vm::selectModel, onThinking = vm::selectThinking, subscription = s.provider?.credential == "oauth",
                )
                Text(stringResource(R.string.pi_runtime_info, s.piVersion.orEmpty()), style = MaterialTheme.typography.bodySmall)
                if (BuildConfig.DEBUG) Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.useMock, onCheckedChange = vm::setMock)
                    Text("  " + stringResource(R.string.dev_mock), style = MaterialTheme.typography.bodySmall)
                }
                // Signing out deletes the saved credential, so ask first.
                var confirmSignOut by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { confirmSignOut = true }) { Text(stringResource(R.string.sign_out)) }
                if (confirmSignOut) AlertDialog(
                    onDismissRequest = { confirmSignOut = false },
                    title = { Text(stringResource(R.string.sign_out_title)) },
                    text = { Text(stringResource(R.string.sign_out_text)) },
                    confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text(stringResource(R.string.sign_out_confirm)) } },
                    dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }
}

// ---- one session ----------------------------------------------------------------------------------

/** A session's page: its conversation, what it is doing, and its controls. Leaving it doesn't stop it. */
@Composable
fun SessionScreen(vm: AppViewModel, session: SessionState?) {
    BackHandler { vm.open(null) }
    if (session == null) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Text("  …") }
        return
    }
    val s = session.summary
    var menu by rememberSaveable { mutableStateOf(false) }
    var showTrace by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.open(null) }) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(s.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (s.phone) stringResource(R.string.kind_phone) else stringResource(R.string.kind_chat), s.app).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            StatusChip(s.status)
            TextButton(onClick = { menu = !menu }) { Text("⋯") }
        }
        if (menu) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showTrace = !showTrace; menu = false }) { Text(stringResource(R.string.pi_events)) }
            TextButton(onClick = { confirmDelete = true; menu = false }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        }
        val list = rememberLazyListState()
        val count = session.items.size + (if (session.streaming.isNotEmpty()) 1 else 0)
        LaunchedEffect(count, session.streaming.length / 80) { if (count > 0) list.animateScrollToItem(count - 1) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showTrace) item {
                Text(session.trace.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
            }
            items(session.items.size) { i -> ChatRow(session.items[i]) }
            if (session.streaming.isNotEmpty()) item { ChatRow(ChatItem.Assistant(session.streaming)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) { SessionControls(s, vm, compact = false) }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(vm.reply, { vm.reply = it }, placeholder = { Text(stringResource(R.string.reply_hint)) }, modifier = Modifier.weight(1f), maxLines = 4)
            Spacer(Modifier.size(8.dp))
            Button(onClick = vm::sendReply, enabled = !session.busy && vm.reply.isNotBlank()) { Text(stringResource(R.string.send)) }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(R.string.delete_text)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(s.id) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChatRow(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(item.text, Modifier.widthIn(max = 300.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)).padding(10.dp))
        }
        is ChatItem.Assistant -> Text(item.text, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(10.dp))
        is ChatItem.Error -> Text(item.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        is ChatItem.Step -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            Text(when (item.ok) { null -> "⋯"; true -> "✓"; false -> "✗" }, color = if (item.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, fontSize = 12.sp)
            Text("  ${item.tool} ${item.detail}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
