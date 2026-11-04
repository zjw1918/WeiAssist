package com.lanqi.weiassist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.ChatItem
import com.lanqi.weiassist.agent.RunStats
import com.lanqi.weiassist.agent.SessionState
import com.lanqi.weiassist.agent.SessionStatus
import kotlinx.coroutines.delay
import java.util.Locale

// ---- the conversation as turns ---------------------------------------------------------------------

/** What the page shows: a user's message, or the agent's turn that answers it. [key] is stable for the list. */
sealed interface ChatBlock {
    val key: String

    data class Message(override val key: String, val item: ChatItem) : ChatBlock

    /**
     * One run of the agent: its [process] (reasoning, steps, what it said between steps), then its [answer]
     * (the text after its last step, and any errors), then what it took ([stats]; null for runs saved before
     * the app counted them, and for the run in progress, see [live]).
     */
    data class Turn(
        override val key: String,
        val process: List<ChatItem>,
        val answer: List<ChatItem>,
        val stats: RunStats?,
        val live: Boolean,
    ) : ChatBlock {
        val steps get() = process.count { it is ChatItem.Step }
        val answerText get() = answer.filterIsInstance<ChatItem.Assistant>().joinToString("\n\n") { it.text }
    }
}

/**
 * Groups a session's items into blocks: each user message, then the agent's turn up to its [ChatItem.Stats].
 * The last turn is [ChatBlock.Turn.live] while the session works (it may have no items yet).
 */
fun chatBlocks(items: List<ChatItem>, busy: Boolean): List<ChatBlock> {
    val out = mutableListOf<ChatBlock>()
    var start = -1
    val turn = mutableListOf<ChatItem>()
    fun close(stats: RunStats?, live: Boolean) {
        if (turn.isEmpty() && stats == null && !live) return
        val last = turn.indexOfLast { it is ChatItem.Step || it is ChatItem.Thinking }
        val process = turn.take(last + 1).filterNot { it is ChatItem.Error }
        val answer = turn.filterIndexed { i, it -> it is ChatItem.Error || i > last }
        out += ChatBlock.Turn("t:${if (start < 0) items.size else start}", process, answer, stats, live)
        turn.clear(); start = -1
    }
    items.forEachIndexed { i, item ->
        when (item) {
            is ChatItem.User -> {
                // A message typed while it works (steering) splits the run's process around it, as it happened.
                close(null, false)
                out += ChatBlock.Message("m:$i", item)
            }
            is ChatItem.Stats -> { if (start < 0) start = i; close(item.run, false) }
            else -> { if (start < 0) start = i; turn += item }
        }
    }
    close(null, busy)
    return out
}

// ---- formatting ---------------------------------------------------------------------------------------

@Composable
fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> stringResource(R.string.dur_s, s.toInt())
        s < 3600 -> stringResource(R.string.dur_ms, (s / 60).toInt(), (s % 60).toInt())
        else -> stringResource(R.string.dur_hm, (s / 3600).toInt(), (s % 3600 / 60).toInt())
    }
}

/** 850, 21.6K, 1.25M. */
fun compactCount(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> String.format(Locale.US, if (n < 10_000) "%.1fK" else "%.0fK", n / 1_000.0).replace(".0K", "K")
    else -> String.format(Locale.US, "%.2fM", n / 1_000_000.0).replace(Regex("""\.?0+M$"""), "M")
}

private fun groupedCount(n: Long) = String.format(Locale.getDefault(), "%,d", n)

/** The wall clock, ticking each second while [ticking]. */
@Composable
private fun rememberNow(ticking: Boolean): Long = produceState(System.currentTimeMillis(), ticking) {
    value = System.currentTimeMillis()
    while (ticking) {
        delay(1000 - value % 1000)
        value = System.currentTimeMillis()
    }
}.value

@Composable
private fun toolLabel(tool: String): String = when (tool) {
    "open_app" -> stringResource(R.string.tool_open_app)
    "tap", "tap_at" -> stringResource(R.string.tool_tap)
    "long_press" -> stringResource(R.string.tool_long_press)
    "type_text" -> stringResource(R.string.tool_type_text)
    "swipe" -> stringResource(R.string.tool_swipe)
    "scroll" -> stringResource(R.string.tool_scroll)
    "press_key" -> stringResource(R.string.tool_press_key)
    "wait" -> stringResource(R.string.tool_wait)
    "note" -> stringResource(R.string.tool_note)
    "read_screen" -> stringResource(R.string.tool_read_screen)
    "screenshot" -> stringResource(R.string.tool_screenshot)
    "bash" -> stringResource(R.string.tool_bash)
    "create_session" -> stringResource(R.string.tool_create_session)
    "send_message" -> stringResource(R.string.tool_send_message)
    "list_sessions" -> stringResource(R.string.tool_list_sessions)
    "get_session" -> stringResource(R.string.tool_get_session)
    "wait_for" -> stringResource(R.string.tool_wait_for)
    else -> tool
}

private fun toolIcon(tool: String) = when (tool) {
    "bash" -> R.drawable.ic_terminal
    "open_app" -> R.drawable.ic_open_in_new
    "tap", "tap_at", "long_press", "swipe", "scroll", "press_key" -> R.drawable.ic_touch
    "type_text", "note" -> R.drawable.ic_edit
    "read_screen", "screenshot" -> R.drawable.ic_phone
    "create_session", "send_message", "list_sessions", "get_session", "wait_for", "pause_session", "resume_session",
    "stop_session", "delete_session", "rename_session" -> R.drawable.ic_forum
    else -> R.drawable.ic_code
}

/** Consecutive steps of one tool read as one line ("Tap ×3"). Other process items stand alone. */
private sealed interface ProcessLine {
    data class Steps(val tool: String, val steps: List<ChatItem.Step>) : ProcessLine {
        val running get() = steps.any { it.ok == null }
        val failed get() = steps.count { it.ok == false }
    }
    data class Thought(val item: ChatItem.Thinking) : ProcessLine
    data class Said(val text: String) : ProcessLine
}

private fun processLines(process: List<ChatItem>): List<ProcessLine> {
    val out = mutableListOf<ProcessLine>()
    for (item in process) when (item) {
        is ChatItem.Step -> {
            val prev = out.lastOrNull() as? ProcessLine.Steps
            // bash commands stay one per line: each one's command is what the user wants to read.
            if (prev != null && prev.tool == item.tool && item.tool != "bash") out[out.lastIndex] = prev.copy(steps = prev.steps + item)
            else out += ProcessLine.Steps(item.tool, listOf(item))
        }
        is ChatItem.Thinking -> out += ProcessLine.Thought(item)
        is ChatItem.Assistant -> out += ProcessLine.Said(item.text)
        else -> Unit
    }
    return out
}

// ---- the turn on the page ---------------------------------------------------------------------------

/**
 * The agent's turn: a header with what it is doing now (live) or what it did (tap for the details), the steps
 * as they happen, the answer, and a footer with what the run took.
 */
@Composable
fun TurnView(turn: ChatBlock.Turn, session: SessionState, onProcess: () -> Unit, onStats: () -> Unit) {
    val live = turn.live
    val now = rememberNow(live)
    val run = if (live) session.run else turn.stats
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (live || turn.process.isNotEmpty()) ProcessHeader(turn, session, run, now, onProcess)
        if (live) LiveProcess(turn, session, now)
        turn.answer.forEach { item ->
            when (item) {
                is ChatItem.Assistant -> MarkdownText(item.text, Modifier.fillMaxWidth().padding(horizontal = 2.dp))
                is ChatItem.Error -> ErrorText(item.text)
                else -> Unit
            }
        }
        if (live && session.streaming.isNotEmpty()) MarkdownText(session.streaming, Modifier.fillMaxWidth().padding(horizontal = 2.dp))
        TurnFooter(turn, run, now, onStats)
    }
}

@Composable
fun ErrorText(text: String) {
    val c = MaterialTheme.colorScheme
    Text(
        text, color = c.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().background(c.errorContainer, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** "Thinking 18s ›" while it runs (shimmering), "Thought for 23s ›" after. */
@Composable
private fun ProcessHeader(turn: ChatBlock.Turn, session: SessionState, run: RunStats?, now: Long, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val elapsed = run?.let { if (turn.live) now - it.startedAt else it.ms }
    val label = when {
        turn.live && session.status == SessionStatus.Queued -> stringResource(R.string.run_waiting)
        turn.live && session.status == SessionStatus.Paused -> stringResource(R.string.run_paused, duration(elapsed ?: 0))
        turn.live && session.thinkingSince != null -> stringResource(R.string.run_thinking, duration(elapsed ?: 0))
        turn.live -> stringResource(R.string.run_working, duration(elapsed ?: 0))
        run != null && run.thinkingMs > 0 && turn.steps == 0 -> stringResource(R.string.run_thought_for, duration(run.ms))
        run != null -> stringResource(R.string.run_worked_for, duration(run.ms))
        else -> pluralStringResource(R.plurals.run_steps, turn.steps, turn.steps)
    }
    val animate = turn.live && session.status == SessionStatus.Running
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (animate) PulsingDots() else AppIcon(R.drawable.ic_lightbulb, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            label, style = MaterialTheme.typography.bodyMedium.let { if (animate) it.copy(brush = shimmer(c.onSurfaceVariant)) else it },
            color = if (animate) Color.Unspecified else c.onSurfaceVariant,
        )
        AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(18.dp).padding(start = 2.dp), tint = c.onSurfaceVariant)
    }
}

/** Three dots in a wave: the agent is working. */
@Composable
private fun PulsingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "phase")
    val color = MaterialTheme.colorScheme.primary
    Row(Modifier.size(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val d = ((phase - i + 3) % 3).let { if (it > 1.5f) 3 - it else it } // 0 at its peak, 1.5 farthest
            Box(Modifier.size(4.dp).alpha(1f - d / 2f).background(color, CircleShape))
        }
    }
}

/** A highlight sweeping across text in [base] color. */
@Composable
private fun shimmer(base: Color): Brush {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-300f, 900f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val dim = base.copy(alpha = 0.45f)
    return Brush.linearGradient(listOf(dim, base, dim), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + 300f, 0f))
}

/** While it runs: its reasoning as it streams, and its latest steps (the newest one shimmering while it runs). */
@Composable
private fun LiveProcess(turn: ChatBlock.Turn, session: SessionState, now: Long) {
    val lines = processLines(turn.process).filterNot { it is ProcessLine.Thought }
    val shown = lines.takeLast(MAX_LIVE_LINES)
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (lines.size > shown.size) Text(
            pluralStringResource(R.plurals.run_earlier, lines.size - shown.size, lines.size - shown.size),
            style = MaterialTheme.typography.labelMedium, color = c.outline, modifier = Modifier.padding(start = 22.dp),
        )
        shown.forEachIndexed { i, line ->
            val current = i == shown.lastIndex && session.thinkingSince == null && session.status == SessionStatus.Running
            when (line) {
                is ProcessLine.Steps -> StepLine(line, shimmering = current && line.running)
                is ProcessLine.Said -> Text(
                    remember(line.text) { com.lanqi.weiassist.agent.Markdown.plain(line.text).replace('\n', ' ') },
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 22.dp),
                )
                is ProcessLine.Thought -> Unit
            }
        }
        AnimatedVisibility(session.thinkingSince != null || session.thinking.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            LiveThinking(session.thinking, session.thinkingSince?.let { now - it })
        }
    }
}

private const val MAX_LIVE_LINES = 4

/** The tail of the reasoning streaming now, in a quiet column (providers that hide it show only the time). */
@Composable
private fun LiveThinking(text: String, ms: Long?) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp)) {
        Box(Modifier.padding(start = 7.dp, end = 13.dp).width(2.dp).heightIn(min = 20.dp).background(c.outlineVariant, RoundedCornerShape(1.dp)))
        val tail = remember(text) { text.trimEnd().takeLast(400).substringAfter('\n', text.trimEnd().takeLast(400)) }
        Text(
            tail.ifBlank { ms?.let { "…" } ?: "" }, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = c.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(0.9f),
        )
    }
}

/** One line of steps: "Tap ×3  [12]", with how it went. */
@Composable
private fun StepLine(line: ProcessLine.Steps, shimmering: Boolean, showDetail: Boolean = true) {
    val c = MaterialTheme.colorScheme
    val last = line.steps.last()
    val failed = line.failed > 0 && line.steps.none { it.ok == true }
    val color = if (failed) c.error else c.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(toolIcon(line.tool), null, Modifier.size(14.dp), tint = if (failed) c.error else c.outline)
        Spacer(Modifier.width(8.dp))
        val label = toolLabel(line.tool) + if (line.steps.size > 1) " ×${line.steps.size}" else ""
        Text(
            label, style = MaterialTheme.typography.bodyMedium.let { if (shimmering) it.copy(brush = shimmer(c.onSurface)) else it },
            color = if (shimmering) Color.Unspecified else color, maxLines = 1,
        )
        if (showDetail && last.detail.isNotBlank()) Text(
            "  " + last.detail.lineSequence().first(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.outline,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (line.failed > 0 && !failed) AppIcon(R.drawable.ic_cancel, null, Modifier.padding(start = 6.dp).size(12.dp), tint = c.error)
    }
}

/** Copy, then "8s · 3 steps · 21.6K tokens ›" (live while it runs). */
@Composable
private fun TurnFooter(turn: ChatBlock.Turn, run: RunStats?, now: Long, onStats: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val text = turn.answerText
    val copyable = !turn.live && text.isNotBlank()
    if (!copyable && run == null) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (copyable) {
            val copied = stringResource(R.string.copied)
            IconButton(onClick = { copy(ctx, text); Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(32.dp)) {
                AppIcon(R.drawable.ic_copy, stringResource(R.string.copy), Modifier.size(16.dp), tint = c.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        if (run != null) Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onStats).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ms = if (turn.live) now - run.startedAt else run.ms
            val parts = buildList {
                add(duration(ms))
                add(pluralStringResource(R.plurals.run_steps, run.steps, run.steps))
                if (run.tokens > 0) add(stringResource(R.string.run_tokens, compactCount(run.tokens)))
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = c.outline)
            AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(16.dp), tint = c.outline)
        }
    }
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("answer", text))
}

// ---- sheets ------------------------------------------------------------------------------------------

/** Everything the turn did, in order: each reasoning in full, each step with its argument and result. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessSheet(turn: ChatBlock.Turn, session: SessionState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val now = rememberNow(turn.live)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Text(
            stringResource(R.string.run_process), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        val lines = processLines(turn.process)
        LazyColumn(
            Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (lines.isEmpty() && !turn.live) item { Text(stringResource(R.string.run_stopped_early), color = c.onSurfaceVariant) }
            items(lines.size) { i ->
                when (val line = lines[i]) {
                    is ProcessLine.Thought -> ThoughtEntry(line.item.text, line.item.ms)
                    is ProcessLine.Steps -> StepsEntry(line)
                    is ProcessLine.Said -> MarkdownText(line.text, Modifier.fillMaxWidth())
                }
            }
            session.thinkingSince?.takeIf { turn.live }?.let { since -> item { ThoughtEntry(session.thinking, now - since, live = true) } }
        }
    }
}

@Composable
private fun ThoughtEntry(text: String, ms: Long, live: Boolean = false) {
    val c = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = text.isNotBlank()) { open = !open }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_lightbulb, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            val label = if (live) stringResource(R.string.run_thinking, duration(ms)) else stringResource(R.string.run_thought, duration(ms))
            Text(
                label, style = MaterialTheme.typography.bodyMedium.let { if (live) it.copy(brush = shimmer(c.onSurfaceVariant)) else it },
                color = if (live) Color.Unspecified else c.onSurfaceVariant,
            )
        }
        val body = text.ifBlank { if (live) "" else stringResource(R.string.run_thought_hidden) }
        if (open && body.isNotBlank()) Row(Modifier.padding(top = 6.dp)) {
            Box(Modifier.padding(start = 7.dp, end = 15.dp).width(2.dp).heightIn(min = 16.dp).background(c.outlineVariant))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StepsEntry(line: ProcessLine.Steps) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StepLine(line, shimmering = line.running && line.steps.size == 1, showDetail = line.tool != "bash")
        if (line.tool == "bash") line.steps.forEach { st ->
            Column(Modifier.fillMaxWidth().padding(start = 22.dp).background(c.surfaceContainerHigh, MaterialTheme.shapes.small).padding(10.dp)) {
                Text(st.detail, style = CodeStyle)
                if (st.result.isNotBlank()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = c.outlineVariant)
                    Text(st.result, style = CodeStyle, color = if (st.ok == false) c.error else c.onSurfaceVariant)
                }
            }
        } else if (line.steps.size > 1 || line.steps.any { it.result.isNotBlank() }) Column(
            Modifier.padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            line.steps.forEach { st ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (st.ok) {
                        null -> Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(5.dp).background(c.outline, CircleShape)) }
                        true -> AppIcon(R.drawable.ic_check_circle, null, Modifier.size(12.dp), tint = c.outline)
                        false -> AppIcon(R.drawable.ic_cancel, null, Modifier.size(12.dp), tint = c.error)
                    }
                    Text(
                        "  " + listOf(st.detail, st.result).filter { it.isNotBlank() }.joinToString("  →  "),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (st.ok == false) c.error else c.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (st.screen > 0) Text(
                        "  · " + stringResource(R.string.step_screen, st.screen),
                        style = MaterialTheme.typography.labelSmall, color = c.primary, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** What the run took: time, steps, model calls, and tokens in detail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsSheet(turn: ChatBlock.Turn, session: SessionState, onDismiss: () -> Unit) {
    val run = (if (turn.live) session.run else turn.stats) ?: return
    val now = rememberNow(turn.live)
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.run_details), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            // Headline tiles: the three numbers the footer shows.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(duration(if (turn.live) now - run.startedAt else run.ms), stringResource(R.string.stat_time), Modifier.weight(1f))
                StatTile(run.steps.toString(), stringResource(R.string.stat_steps), Modifier.weight(1f))
                StatTile(compactCount(run.tokens), stringResource(R.string.stat_tokens), Modifier.weight(1f))
            }
            Spacer(Modifier.size(12.dp))
            if (run.thinkingMs > 0) StatRow(stringResource(R.string.stat_thinking), duration(run.thinkingMs))
            StatRow(stringResource(R.string.stat_calls), run.calls.toString())
            if (run.model.isNotBlank() && run.model != "/") StatRow(stringResource(R.string.stat_model), run.model)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = c.outlineVariant)
            StatRow(stringResource(R.string.stat_input), groupedCount(run.input))
            if (run.cacheRead > 0) StatRow(stringResource(R.string.stat_cache_read), groupedCount(run.cacheRead))
            if (run.cacheWrite > 0) StatRow(stringResource(R.string.stat_cache_write), groupedCount(run.cacheWrite))
            StatRow(stringResource(R.string.stat_output), groupedCount(run.output))
            if (run.reasoning > 0) StatRow("    " + stringResource(R.string.stat_reasoning), groupedCount(run.reasoning), sub = true)
            if (run.cost > 0) StatRow(stringResource(R.string.stat_cost), String.format(Locale.US, "$%.4f", run.cost))
            Text(
                stringResource(R.string.stat_tokens_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier.background(c.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun StatRow(label: String, value: String, sub: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (sub) c.outline else c.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = if (sub) c.outline else c.onSurface)
    }
}
