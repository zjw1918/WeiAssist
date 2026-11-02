package com.lanqi.weiassist.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.ChatItem
import com.lanqi.weiassist.agent.Delivery
import com.lanqi.weiassist.agent.Markdown
import com.lanqi.weiassist.agent.PreviewHub
import com.lanqi.weiassist.agent.SessionState
import com.lanqi.weiassist.agent.SessionStatus
import com.lanqi.weiassist.agent.SessionSummary

// ---- the Sessions tab -----------------------------------------------------------------------------

/** The Sessions tab: the supervisor, live screens, then every conversation (running ones first). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsTab(vm: AppViewModel, list: LazyListState) {
    val all by vm.sessions.collectAsStateWithLifecycle()
    val supervisor = all.firstOrNull { it.supervisor }
    val sessions = all.filterNot { it.supervisor }
    // Running sessions in a fixed order (newest first by creation): every step updates updatedAt, so ordering them
    // by it made the rows swap places constantly. Finished ones: most recent first.
    val busy = sessions.filter { it.status.active }.sortedByDescending { it.createdAt }
    val rest = sessions.filterNot { it.status.active }
    var newSession by rememberSaveable { mutableStateOf(false) }
    val atTop by remember { derivedStateOf { list.firstVisibleItemIndex == 0 } }
    val bar = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_sessions)) }, scrollBehavior = bar,
                actions = { if (busy.size > 1) TextButton(onClick = vm::stopAll) { Text(stringResource(R.string.stop_all)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newSession = true }, expanded = atTop,
                icon = { AppIcon(R.drawable.ic_add, null) }, text = { Text(stringResource(R.string.new_session)) },
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).imePadding(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        ) {
            item(key = "supervisor") { SupervisorCard(vm, supervisor) }
            item(key = "live") { LiveStrip(vm, sessions.sortedByDescending { it.createdAt }) } // thumbnails never move
            if (busy.isNotEmpty()) item(key = "h-busy") { SectionHeader(pluralStringResource(R.plurals.sessions_running, busy.size, busy.size)) }
            items(busy, key = { it.id }) { SessionRow(it, vm, Modifier.animateItem()) }
            if (rest.isNotEmpty()) item(key = "h-rest") { SectionHeader(stringResource(R.string.sessions_recent)) }
            items(rest, key = { it.id }) { SessionRow(it, vm, Modifier.animateItem()) }
            if (sessions.isEmpty()) item(key = "empty") { EmptySessions() }
        }
    }
    if (newSession) NewSessionSheet(vm) { newSession = false }
}

@Composable
private fun EmptySessions() {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(R.drawable.ic_forum, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.sessions_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.sessions_empty), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

/**
 * The supervisor: tell it, in words, what to start, stop or combine; it manages the sessions below.
 * Speech goes through the phone's own recognizer when there is one (voice is just words for it).
 */
@Composable
private fun SupervisorCard(vm: AppViewModel, s: SessionSummary?) {
    val ctx = LocalContext.current
    val speech = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    }
    val canSpeak = remember { speech.resolveActivity(ctx.packageManager) != null }
    val listen = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.askSupervisor(it) }
    }
    Surface(Modifier.fillMaxWidth().padding(top = 4.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .then(if (s != null) Modifier.clickable { vm.open(s.id) } else Modifier).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(R.drawable.ic_sparkle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.supervisor), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (s != null) {
                    StatusBadge(s.status)
                    AppIcon(R.drawable.ic_chevron_right, stringResource(R.string.open), Modifier.padding(start = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                s?.preview?.takeIf { it.isNotBlank() }?.let { remember(it) { Markdown.plain(it).replace('\n', ' ') } }
                    ?: stringResource(R.string.supervisor_hint),
                style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = if (s?.error == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Composer(
                vm.supervisorInput, { vm.supervisorInput = it }, stringResource(R.string.supervisor_placeholder),
                onSend = { vm.askSupervisor() }, maxLines = 3,
                extra = if (canSpeak) { { IconButton(onClick = { listen.launch(speech) }) { AppIcon(R.drawable.ic_mic, stringResource(R.string.voice_input)) } } } else null,
            )
        }
    }
}

/**
 * Whether a session has used its screen (opened an app there, or read one): until then its display is blank, and
 * most sessions only talk, so there is nothing to preview.
 */
private val SessionSummary.usedScreen get() = app != null

/** The sessions' screens side by side, live, while they have one (when shown). Tap one to open its session. */
@Composable
private fun LiveStrip(vm: AppViewModel, sessions: List<SessionSummary>) {
    val displays by vm.displays.collectAsStateWithLifecycle()
    val shown = sessions.filter { it.id in displays && it.usedScreen }
    if (shown.isEmpty()) return
    Column {
        SectionHeader(stringResource(R.string.live_screens) + if (vm.showPreviews) "" else " · ${shown.size}") {
            TextButton(onClick = { vm.showPreviews(!vm.showPreviews) }) { Text(stringResource(if (vm.showPreviews) R.string.hide else R.string.show)) }
        }
        if (!vm.showPreviews) return@Column
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shown, key = { it.id }) { s ->
                Column(Modifier.width(96.dp).clip(MaterialTheme.shapes.small).clickable { vm.open(s.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    LivePreview(vm, displays.getValue(s.id), PreviewHub.Purpose.Thumbnail, Modifier.fillMaxWidth(), s.title)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        if (s.status.active) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Text(" " + s.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Start a conversation: what to do, and whether it may operate the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSessionSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accessibilityOn by vm.accessibilityOn.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.new_session), style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to R.string.kind_chat, true to R.string.kind_phone).forEachIndexed { i, (phone, label) ->
                    SegmentedButton(
                        selected = vm.phoneMode == phone, onClick = { vm.phoneMode = phone },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = { SegmentedButtonDefaults.Icon(vm.phoneMode == phone) { AppIcon(if (phone) R.drawable.ic_phone else R.drawable.ic_chat, null, Modifier.size(18.dp)) } },
                    ) { Text(stringResource(label)) }
                }
            }
            Text(
                stringResource(if (vm.phoneMode) R.string.kind_phone_hint else R.string.kind_chat_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                vm.input, { vm.input = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                label = { Text(stringResource(if (vm.phoneMode) R.string.task_label else R.string.chat_label)) },
                trailingIcon = if (vm.input.isEmpty()) null else { { ClearButton { vm.input = "" } } },
                shape = MaterialTheme.shapes.medium,
            )
            if (vm.phoneMode && !accessibilityOn) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(stringResource(R.string.a11y_needed), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.open_a11y_settings))
                    }
                }
            }
            Button(onClick = { vm.startTask(); onDismiss() }, enabled = vm.input.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.start))
            }
        }
    }
}

/** One conversation in the list. Tap to open; long-press to rename or delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: SessionSummary, vm: AppViewModel, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    Box(modifier.padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .background(if (s.status.active) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface)
                .combinedClickable(onClick = { vm.open(s.id) }, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            KindAvatar(s.phone)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        DateUtils.getRelativeTimeSpanString(s.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                // Previews saved before answers were rendered as Markdown still have its markers.
                if (s.preview.isNotBlank()) Text(
                    remember(s.preview) { Markdown.plain(s.preview).replace('\n', ' ') }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (s.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.status != SessionStatus.Idle || s.app != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    StatusBadge(s.status)
                    s.app?.let {
                        Text(
                            (if (s.status != SessionStatus.Idle) " · " else "") + it, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    SessionControls(s, vm)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { AppIcon(R.drawable.ic_edit, null) }, onClick = { menu = false; rename = true })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { AppIcon(R.drawable.ic_delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete = true },
            )
        }
    }
    if (rename) RenameDialog(s.title, onDismiss = { rename = false }) { vm.rename(s.id, it) }
    if (delete) DeleteDialog(onDismiss = { delete = false }) { vm.delete(s.id) }
}

@Composable
private fun KindAvatar(phone: Boolean) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).background(if (phone) c.tertiaryContainer else c.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            if (phone) R.drawable.ic_phone else R.drawable.ic_chat, stringResource(if (phone) R.string.kind_phone else R.string.kind_chat),
            Modifier.size(20.dp), tint = if (phone) c.onTertiaryContainer else c.onSecondaryContainer,
        )
    }
}

/** Pause / resume / stop / continue, as fits the session's status. */
@Composable
private fun SessionControls(s: SessionSummary, vm: AppViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (s.status) {
            SessionStatus.Running, SessionStatus.Queued -> {
                SmallIconButton(R.drawable.ic_pause, stringResource(R.string.pause)) { vm.pause(s.id) }
                SmallIconButton(R.drawable.ic_stop, stringResource(R.string.stop)) { vm.stop(s.id) }
            }
            SessionStatus.Paused -> {
                SmallIconButton(R.drawable.ic_play, stringResource(R.string.resume)) { vm.resume(s.id) }
                SmallIconButton(R.drawable.ic_stop, stringResource(R.string.stop)) { vm.stop(s.id) }
            }
            SessionStatus.Interrupted -> TextButton(onClick = { vm.continueTask(s.id) }) { Text(stringResource(R.string.continue_label)) }
            SessionStatus.Idle -> Unit
        }
    }
}

@Composable
private fun SmallIconButton(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) { AppIcon(icon, description, Modifier.size(20.dp)) }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = if (title.isEmpty()) null else { { ClearButton { title = "" } } },
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave(title.trim()) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DeleteDialog(onDismiss: () -> Unit, onDelete: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.delete_title)) },
    text = { Text(stringResource(R.string.delete_text)) },
    confirmButton = { TextButton(onClick = { onDismiss(); onDelete() }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
)

// ---- one session ----------------------------------------------------------------------------------

/** A session's page: its conversation, what it is doing, and its controls. Leaving it doesn't stop it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(vm: AppViewModel, session: SessionState?) {
    BackHandler { vm.open(null) }
    var menu by remember { mutableStateOf(false) }
    var showTrace by rememberSaveable { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val s = session?.summary
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.open(null) }) { AppIcon(R.drawable.ic_arrow_back, stringResource(R.string.back)) } },
                title = {
                    if (s != null) Column {
                        Text(s.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(stringResource(if (s.phone) R.string.kind_phone else R.string.kind_chat), s.app).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (s == null) return@TopAppBar
                    StatusBadge(s.status, Modifier.padding(end = 4.dp))
                    Box {
                        IconButton(onClick = { menu = true }) { AppIcon(R.drawable.ic_more_vert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { AppIcon(R.drawable.ic_edit, null) }, onClick = { menu = false; rename = true })
                            DropdownMenuItem(
                                text = { Text(stringResource(if (showTrace) R.string.hide_pi_events else R.string.pi_events)) },
                                leadingIcon = { AppIcon(R.drawable.ic_code, null) }, onClick = { menu = false; showTrace = !showTrace },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { AppIcon(R.drawable.ic_delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { inner ->
        if (session == null || s == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(inner)) {
            ScreenPreview(vm, s)
            val list = rememberLazyListState()
            val count = session.items.size + (if (session.streaming.isNotEmpty()) 1 else 0)
            LaunchedEffect(count, session.streaming.length / 80) { if (count > 0) list.animateScrollToItem(count - 1) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (showTrace) item {
                    Text(
                        session.trace.joinToString("\n"), style = CodeStyle.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(10.dp),
                    )
                }
                items(session.items.size) { i -> ChatRow(session.items[i]) }
                if (session.streaming.isNotEmpty()) item { ChatRow(ChatItem.Assistant(session.streaming)) }
            }
            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            ) {
                if (s.status != SessionStatus.Idle) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) { SessionActions(s, vm) }
                // While it works, a message steers it: the model reads it after its current step.
                Composer(vm.reply, { vm.reply = it }, stringResource(if (session.busy) R.string.steer_hint else R.string.reply_hint), onSend = vm::sendReply)
            }
        }
    }
    if (s != null && rename) RenameDialog(s.title, onDismiss = { rename = false }) { vm.rename(s.id, it) }
    if (s != null && delete) DeleteDialog(onDismiss = { delete = false }) { vm.delete(s.id) }
}

/** The session's controls with words, above its message box. */
@Composable
private fun SessionActions(s: SessionSummary, vm: AppViewModel) {
    @Composable
    fun Action(icon: Int, label: Int, onClick: () -> Unit) = TextButton(onClick = onClick) {
        AppIcon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(label))
    }
    when (s.status) {
        SessionStatus.Running, SessionStatus.Queued -> { Action(R.drawable.ic_pause, R.string.pause) { vm.pause(s.id) }; Action(R.drawable.ic_stop, R.string.stop) { vm.stop(s.id) } }
        SessionStatus.Paused -> { Action(R.drawable.ic_play, R.string.resume) { vm.resume(s.id) }; Action(R.drawable.ic_stop, R.string.stop) { vm.stop(s.id) } }
        SessionStatus.Interrupted -> Action(R.drawable.ic_play, R.string.continue_label) { vm.continueTask(s.id) }
        SessionStatus.Idle -> Unit
    }
}

/** The session's virtual display, live, once it has used it; tap to enlarge. */
@Composable
private fun ScreenPreview(vm: AppViewModel, s: SessionSummary) {
    val displays by vm.displays.collectAsStateWithLifecycle()
    val display = displays[s.id]?.takeIf { s.usedScreen } ?: return
    if (!vm.showPreviews) {
        TextButton(onClick = { vm.showPreviews(true) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.show_live_screen)) }
        return
    }
    var large by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        LivePreview(
            vm, display, PreviewHub.Purpose.Page,
            Modifier.height(if (large) 520.dp else 240.dp).clickable { large = !large },
            stringResource(R.string.live_screen_of, s.title),
        )
        TextButton(onClick = { vm.showPreviews(false) }, modifier = Modifier.align(Alignment.TopEnd)) { Text(stringResource(R.string.hide)) }
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    val c = MaterialTheme.colorScheme
    when (item) {
        is ChatItem.User -> Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
            val sent = item.delivery == Delivery.Sent
            Text(
                item.text, style = MaterialTheme.typography.bodyLarge, color = if (sent) c.onPrimaryContainer else c.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 320.dp)
                    .background(if (sent) c.primaryContainer else c.surfaceContainerHigh, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            if (!sent) Text(
                stringResource(if (item.delivery == Delivery.Queued) R.string.steer_queued else R.string.steer_not_delivered),
                style = MaterialTheme.typography.labelSmall, color = if (item.delivery == Delivery.Queued) c.onSurfaceVariant else c.error,
                modifier = Modifier.padding(top = 4.dp, end = 6.dp),
            )
        }
        // Answers read as text on the page, not as bubbles: they are often long and formatted.
        is ChatItem.Assistant -> MarkdownText(item.text, Modifier.fillMaxWidth().padding(horizontal = 2.dp))
        is ChatItem.Error -> Text(
            item.text, color = c.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().background(c.errorContainer, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 8.dp),
        )
        is ChatItem.Step -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp)) {
            when (item.ok) {
                null -> Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(6.dp).background(c.outline, CircleShape)) }
                true -> AppIcon(R.drawable.ic_check_circle, null, Modifier.size(14.dp), tint = c.outline)
                false -> AppIcon(R.drawable.ic_cancel, null, Modifier.size(14.dp), tint = c.error)
            }
            Text(
                "  ${item.tool} ${item.detail}", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = if (item.ok == false) c.error else c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
