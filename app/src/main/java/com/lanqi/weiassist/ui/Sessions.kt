package com.lanqi.weiassist.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.ChatItem
import com.lanqi.weiassist.agent.Delivery
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
    // Running sessions in a fixed order (newest first by creation): every step updates updatedAt, so ordering them
    // by it made the rows swap places constantly. Finished ones: most recent first.
    val groups = remember(all) { SessionGroups(all) }
    val supervisor = groups.supervisor
    val sessions = groups.sessions
    val busy = groups.busy
    val rest = groups.rest
    var newSession by rememberSaveable { mutableStateOf(false) }
    // Selection mode is on while anything is selected. Ids of sessions deleted meanwhile (by the supervisor, say) drop out.
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val selected = remember(picked, sessions) { sessions.mapTo(HashSet()) { it.id }.let { ids -> picked.filterTo(LinkedHashSet()) { it in ids } } }
    val selecting = selected.isNotEmpty()
    val toggle = { id: String -> picked = if (id in selected) selected.minus(id).toList() else selected.toList() + id }
    var deleteSelected by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = selecting) { picked = emptyList() }
    val atTop by remember { derivedStateOf { list.firstVisibleItemIndex == 0 } }
    val bar = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selecting) TopAppBar(
                title = { Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size)) }, scrollBehavior = bar,
                navigationIcon = { IconButton(onClick = { picked = emptyList() }) { AppIcon(R.drawable.ic_cancel, stringResource(R.string.exit_selection)) } },
                actions = {
                    val all = selected.size == sessions.size
                    TextButton(onClick = { picked = if (all) emptyList() else sessions.map { it.id } }) {
                        Text(stringResource(if (all) R.string.select_none else R.string.select_all))
                    }
                    IconButton(onClick = { deleteSelected = true }) {
                        AppIcon(R.drawable.ic_delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                    }
                },
            ) else TopAppBar(
                title = { Text(stringResource(R.string.tab_sessions)) }, scrollBehavior = bar,
                actions = { if (busy.size > 1) TextButton(onClick = vm::stopAll) { Text(stringResource(R.string.stop_all)) } },
            )
        },
        floatingActionButton = {
            if (!selecting) ExtendedFloatingActionButton(
                onClick = { newSession = true }, expanded = atTop,
                icon = { AppIcon(R.drawable.ic_add, null) }, text = { Text(stringResource(R.string.new_session)) },
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).imePadding(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        ) {
            item(key = "supervisor", contentType = "supervisor") { SupervisorCard(vm, supervisor) }
            item(key = "live", contentType = "live") { LiveStrip(vm, groups.byCreation) } // thumbnails never move
            if (busy.isNotEmpty()) item(key = "h-busy", contentType = "header") { SectionHeader(pluralStringResource(R.plurals.sessions_running, busy.size, busy.size)) }
            items(busy, key = { it.id }, contentType = { "session" }) { SessionRow(it, vm, if (selecting) it.id in selected else null, toggle, Modifier.animateItem()) }
            if (rest.isNotEmpty()) item(key = "h-rest", contentType = "header") { SectionHeader(stringResource(R.string.sessions_recent)) }
            items(rest, key = { it.id }, contentType = { "session" }) { SessionRow(it, vm, if (selecting) it.id in selected else null, toggle, Modifier.animateItem()) }
            if (sessions.isEmpty()) item(key = "empty", contentType = "empty") { EmptySessions() }
        }
    }
    if (newSession) NewSessionSheet(vm) { newSession = false }
    LaunchedEffect(selecting) { if (!selecting) deleteSelected = false }
    if (deleteSelected && selecting) {
        DeleteDialog(
            pluralStringResource(R.plurals.delete_many_title, selected.size, selected.size), stringResource(R.string.delete_many_text),
            termuxFiles = remember(selected) { vm.hasTermuxFiles(selected) }, many = true,
            onDismiss = { deleteSelected = false },
        ) { files -> vm.delete(selected.toList(), files); picked = emptyList() }
    }
}

/** The list's sections, worked out once per change of the sessions (not on every recomposition). */
private class SessionGroups(all: List<SessionSummary>) {
    val supervisor = all.firstOrNull { it.supervisor }
    val sessions = all.filterNot { it.supervisor }
    val busy = sessions.filter { it.status.active }.sortedByDescending { it.createdAt }
    val rest = sessions.filterNot { it.status.active }
    val byCreation = sessions.sortedByDescending { it.createdAt }
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
                s?.preview?.takeIf { it.isNotBlank() }?.let { remember(it) { plainLine(it) } }
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
            // New sessions use the AI engine chosen in Settings; the box says which.
            val engine by vm.defaultEngine.collectAsStateWithLifecycle()
            OutlinedTextField(
                vm.input, { vm.input = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                label = { Text(stringResource(R.string.chat_label, vm.engineShortName(engine))) },
                trailingIcon = if (vm.input.isEmpty()) null else { { ClearButton { vm.input = "" } } },
                shape = MaterialTheme.shapes.medium,
            )
            // Files for the first message: photos, PDFs, text files.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttachButton { vm.attach(it, toReply = false) }
                if (vm.inputAttachments.isEmpty()) Text(stringResource(R.string.attach_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else AttachmentStrip(vm.inputAttachments, { vm.removeAttachment(it, fromReply = false) })
            }
            if (vm.phoneMode && !accessibilityOn) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(stringResource(R.string.a11y_needed), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.open_a11y_settings))
                    }
                }
            }
            Button(onClick = { vm.startTask(); onDismiss() }, enabled = vm.input.isNotBlank() || vm.inputAttachments.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.start))
            }
        }
    }
}

/**
 * One conversation in the list. Tap to open; long-press to select, rename or delete.
 * While the list is selecting ([selected] not null), a tap or long-press toggles it instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: SessionSummary, vm: AppViewModel, selected: Boolean?, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    Box(modifier.padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .background(
                    when {
                        selected == true -> MaterialTheme.colorScheme.secondaryContainer
                        s.status.active -> MaterialTheme.colorScheme.surfaceContainerLow
                        else -> MaterialTheme.colorScheme.surface
                    },
                )
                .combinedClickable(
                    onClick = { if (selected != null) onToggle(s.id) else vm.open(s.id) },
                    onLongClick = { if (selected != null) onToggle(s.id) else menu = true },
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (selected != null) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Checkbox(selected, onCheckedChange = { onToggle(s.id) }) }
            else KindAvatar(s.phone)
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
                    remember(s.preview) { plainLine(s.preview) }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (s.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val where = listOfNotNull(
                    // Which AI engine runs it, once there is a choice.
                    if (vm.engines.size > 1) vm.engineShortName(s.core) else null,
                    if (s.channel == "telegram") stringResource(R.string.kind_telegram) else null, s.app,
                ).joinToString(" · ").ifEmpty { null }
                if (s.status != SessionStatus.Idle || where != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    StatusBadge(s.status)
                    // Weighted, so it gets only the room the controls leave (unweighted, a long app name took it all
                    // and squeezed Continue into a column one letter wide).
                    if (where != null) Text(
                        (if (s.status != SessionStatus.Idle) " · " else "") + where, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    ) else Spacer(Modifier.weight(1f))
                    if (selected == null) SessionControls(s, vm)
                }
            }
        }
        // Composed only once asked for: a menu per row is work every row would do while it scrolls in.
        if (menu) DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.select)) }, leadingIcon = { AppIcon(R.drawable.ic_check_circle, null) }, onClick = { menu = false; onToggle(s.id) })
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { AppIcon(R.drawable.ic_edit, null) }, onClick = { menu = false; rename = true })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { AppIcon(R.drawable.ic_delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete = true },
            )
        }
    }
    if (rename) RenameDialog(s.title, onDismiss = { rename = false }) { vm.rename(s.id, it) }
    if (delete) DeleteDialog(remember { vm.hasTermuxFiles(listOf(s.id)) }, onDismiss = { delete = false }) { vm.delete(s.id, it) }
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
            SessionStatus.Interrupted -> TextButton(onClick = { vm.continueTask(s.id) }) { Text(stringResource(R.string.continue_label), maxLines = 1, softWrap = false) }
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
private fun DeleteDialog(termuxFiles: Boolean, onDismiss: () -> Unit, onDelete: (files: Boolean) -> Unit) =
    DeleteDialog(stringResource(R.string.delete_title), stringResource(R.string.delete_text), termuxFiles, many = false, onDismiss, onDelete)

/**
 * Confirm deleting conversations. When one has a folder in Termux ([termuxFiles]), a checkbox (off by default: it may
 * be a project the user still works on) lets them remove that too; [onDelete] gets its value.
 */
@Composable
private fun DeleteDialog(
    title: String, text: String, termuxFiles: Boolean, many: Boolean,
    onDismiss: () -> Unit, onDelete: (files: Boolean) -> Unit,
) {
    var files by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text)
                if (termuxFiles) Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(MaterialTheme.shapes.small).clickable { files = !files },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(files, onCheckedChange = { files = it })
                    Column(Modifier.padding(end = 8.dp)) {
                        Text(stringResource(if (many) R.string.delete_termux_files_many else R.string.delete_termux_files), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(if (files) R.string.delete_termux_files_on else R.string.delete_termux_files_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (files) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onDelete(termuxFiles && files) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

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
    val app by vm.state.collectAsStateWithLifecycle()
    val sessionId = s?.id
    val fileLinks: ((String) -> Unit)? = remember(sessionId) { sessionId?.let { id -> { link: String -> vm.openFileLink(id, link) } } }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.open(null) }) { AppIcon(R.drawable.ic_arrow_back, stringResource(R.string.back)) } },
                title = {
                    if (s != null) Column {
                        Text(s.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                stringResource(if (s.phone) R.string.kind_phone else R.string.kind_chat),
                                if (vm.engines.size > 1) vm.engineShortName(s.core) else null,
                                if (s.channel == "telegram") stringResource(R.string.kind_telegram) else null, s.app,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (s == null) return@TopAppBar
                    StatusBadge(s.status, Modifier.padding(end = 4.dp), swim = true)
                    // The window the core reported for the session's model, else the app's model's (a core that doesn't say).
                    val window = s.contextWindow.takeIf { it > 0 } ?: app.model?.contextWindow ?: 0
                    val lastRun = remember(session?.items) { session?.items?.lastOrNull { it is ChatItem.Stats }?.let { (it as ChatItem.Stats).run } }
                    // When older messages are summarized is each core's own policy (Pi's at 70%; dsh's depends on the window).
                    ContextRing(s.contextTokens, window, session?.run ?: lastRun, hint = if (s.core == "pi") R.string.context_hint else R.string.context_hint_core)
                    Box {
                        IconButton(onClick = { menu = true }) { AppIcon(R.drawable.ic_more_vert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { AppIcon(R.drawable.ic_edit, null) }, onClick = { menu = false; rename = true })
                            DropdownMenuItem(
                                text = { Text(stringResource(if (showTrace) R.string.hide_ai_events else R.string.ai_events)) },
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
            // Opens at the end (the index is clamped to the last row), without laying out the top first.
            val list = rememberSaveable(session.id, saver = LazyListState.Saver) { LazyListState(Int.MAX_VALUE) }
            val blocks = remember(session.items, session.busy) { chatBlocks(session.items, session.busy) }
            // Rows change only when items do; the streaming answer is its own rows, re-parsed a paragraph at a time.
            val entries = remember(blocks) { chatEntries(blocks) }
            val liveFooter = entries.lastOrNull()?.takeIf { it is ChatEntry.Footer && it.turn.live }
            val body = remember(entries) { if (liveFooter != null) entries.subList(0, entries.size - 1) else entries }
            val stream = remember(session.id) { StreamingMarkdown() }
            // While its subagents work, what the agent writes is part of the work (see chatBlocks), not the answer.
            val streamBlocks = if (liveFooter != null && !(liveFooter as ChatEntry.Footer).turn.waiting && session.streaming.isNotEmpty()) stream.blocks(session.streaming) else emptyList()
            FollowEnd(list, session, rowsKey = entries.size + streamBlocks.size)
            // The turn whose details are open (by key, so a live turn's sheet follows it as it runs).
            var processOf by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
            var statsOf by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
            // No edge stretch: while a run is live (its rows redraw every second), a fling that reached the end left the
            // stretch "in progress" for good, and a list with overscroll in progress takes every touch as the start of
            // a drag: no row (run details, steps, copy) could be tapped until the page was reopened.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = list,
                // No spacing of its own: each row says how far it sits from the one above (ChatEntry.gap).
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                overscrollEffect = null,
            ) {
                if (showTrace) item(key = "trace", contentType = "trace") {
                    Text(
                        session.trace.joinToString("\n"), style = CodeStyle.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        modifier = Modifier.padding(bottom = BLOCK_GAP).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(10.dp),
                    )
                }
                val onProcess = { key: String -> processOf = key }
                val onStats = { key: String -> statsOf = key }
                // Links in answers to files in the session's Termux folder: tap to get the file and open it.
                val onFileLink = fileLinks
                // Keyed by session too: state kept per row stays with its session.
                items(body, key = { "${session.id}:${it.key}" }, contentType = { it.contentType }) { e ->
                    CompositionLocalProvider(LocalFileLinks provides onFileLink) { ChatEntryView(e, session, onProcess, onStats) }
                }
                if (liveFooter != null) {
                    items(streamBlocks.size, key = { "${session.id}:${liveFooter.key}:s$it" }, contentType = { streamBlocks[it].javaClass }) { i ->
                        CompositionLocalProvider(LocalFileLinks provides onFileLink) {
                            MarkdownBlock(streamBlocks[i], Modifier.padding(top = LINE_GAP).fillMaxWidth().padding(horizontal = 2.dp))
                        }
                    }
                    item(key = "${session.id}:${liveFooter.key}", contentType = liveFooter.contentType) { ChatEntryView(liveFooter, session, onProcess, onStats) }
                }
                item(key = "end", contentType = "end") { Spacer(Modifier.height(BLOCK_GAP + 1.dp)) }
            }
            blocks.firstOrNull { it.key == processOf }?.let { ProcessSheet(it as ChatBlock.Turn, session) { processOf = null } }
            blocks.firstOrNull { it.key == statsOf }?.let { StatsSheet(it as ChatBlock.Turn, session) { statsOf = null } }
            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            ) {
                if (s.status != SessionStatus.Idle) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) { SessionActions(s, vm) }
                // The agent waits for the user's answer: its question, above the box (what is typed answers it).
                val asking = session.asks.firstOrNull()
                if (asking != null) QuestionCard(vm.draftOf(asking), { step -> vm.answer(s.id, asking, step) }, Modifier.padding(bottom = 8.dp))
                // Typing a slash command's name: the session's core's commands that match.
                val commands by vm.coreCommands.collectAsStateWithLifecycle()
                if (asking == null) CommandSuggestions(vm.reply, commands[s.core].orEmpty(), { vm.reply = it }, Modifier.padding(bottom = 8.dp))
                // While it works, a message steers it: the model reads it after its current step.
                Composer(
                    vm.reply, { vm.reply = it },
                    stringResource(when { asking != null -> R.string.question_hint; session.busy -> R.string.steer_hint; else -> R.string.reply_hint }),
                    onSend = vm::sendReply,
                    sendEnabled = vm.reply.isNotBlank() || vm.replyAttachments.isNotEmpty(),
                    attachments = vm.replyAttachments, onAttach = { vm.attach(it, toReply = true) }, onRemoveAttachment = { vm.removeAttachment(it, fromReply = true) },
                )
            }
        }
    }
    if (s != null && rename) RenameDialog(s.title, onDismiss = { rename = false }) { vm.rename(s.id, it) }
    if (s != null && delete) DeleteDialog(remember { vm.hasTermuxFiles(listOf(s.id)) }, onDismiss = { delete = false }) { vm.delete(s.id, it) }
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

/** The user's message: a bubble on the right, with its attachments and, while steering, whether it was delivered. */
@Composable
fun UserMessage(entry: ChatItem, modifier: Modifier = Modifier) {
    val item = entry as? ChatItem.User ?: return
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
        val sent = item.delivery == Delivery.Sent
        MessageAttachments(item.attachments, Modifier.padding(bottom = if (item.text.isBlank()) 0.dp else 6.dp))
        // Selectable like the answers: long-press to select, then copy.
        if (item.text.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                item.text, style = MaterialTheme.typography.bodyLarge, color = if (sent) c.onPrimaryContainer else c.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 320.dp)
                    .background(if (sent) c.primaryContainer else c.surfaceContainerHigh, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (!sent) Text(
            stringResource(if (item.delivery == Delivery.Queued) R.string.steer_queued else R.string.steer_not_delivered),
            style = MaterialTheme.typography.labelSmall, color = if (item.delivery == Delivery.Queued) c.onSurfaceVariant else c.error,
            modifier = Modifier.padding(top = 4.dp, end = 6.dp),
        )
    }
}

/**
 * Keeps the end of the conversation in view as it grows, while the user is there: once they scroll up to
 * read, it stays put (until they come back to the end, or send a message).
 */
@Composable
private fun FollowEnd(list: LazyListState, session: SessionState, rowsKey: Int) {
    var follow by remember(session.id) { mutableStateOf(true) }
    // The user moved the list since it last followed: each time their scroll (or its fling) settles, it follows
    // again only if it ended at the bottom. Our own scrolls, even cut short by new text, don't count.
    val byUser = remember(list) { booleanArrayOf(false) }
    LaunchedEffect(list) {
        launch { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) { byUser[0] = true; follow = false } } }
        snapshotFlow { list.isScrollInProgress }.collect { scrolling -> if (!scrolling && byUser[0]) follow = !list.canScrollForward }
    }
    val last = session.items.lastOrNull()
    LaunchedEffect(last) { if (last is ChatItem.User) follow = true }
    // New rows, streamed text and reasoning (every ~80 characters).
    LaunchedEffect(rowsKey, session.streaming.length / 80, session.thinking.length / 80, session.busy, follow) {
        if (!follow || list.layoutInfo.totalItemsCount == 0) return@LaunchedEffect
        byUser[0] = false
        list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
}
