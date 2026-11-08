package com.lanqi.weiassist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.ChatItem
import com.lanqi.weiassist.agent.Markdown
import com.lanqi.weiassist.agent.RunStats
import com.lanqi.weiassist.agent.liveGridWait
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
     * the app counted them, and for the run in progress, see [live]). [ongoing]: the run it is part of still goes on
     * (it is the live turn, or a part of the run a message sent while it worked split off, see [chatBlocks]).
     */
    data class Turn(
        override val key: String,
        val process: List<ChatItem>,
        val answer: List<ChatItem>,
        val stats: RunStats?,
        val live: Boolean,
        val ongoing: Boolean = live,
    ) : ChatBlock {
        val steps get() = process.count { it is ChatItem.Step }
        /** Its subagents (DeepSeek Harness), each with its own section; runs saved before had only their notices. */
        val agents get() = process.filterIsInstance<ChatItem.Subagent>()
        val subagents get() = agents.size.takeIf { it > 0 } ?: process.count { it is ChatItem.Trigger && it.source == "subagent-settled" }
        /**
         * Its subagents still work: the agent's answer comes when all of them are done. A message the user sends
         * meanwhile splits the run but doesn't touch them: they go on in the part they were started in.
         */
        val waiting get() = ongoing && agents.any { !it.settled }
        val answerText get() = answer.filterIsInstance<ChatItem.Assistant>().joinToString("\n\n") { it.text }
    }
}

/**
 * Groups a session's items into blocks: each user message, then the agent's turn up to its [ChatItem.Stats].
 * The last turn is [ChatBlock.Turn.live] while the session works (it may have no items yet).
 */
/** How a subagent's step was named in runs saved before subagents had their own sections. */
private const val SUBAGENT_STEP = "↳"

/** A reply laid out as a report: a table, a heading or code. */
private fun structured(text: String) =
    text.contains("```") || text.lines().any { it.trimStart().startsWith("|") || it.trimStart().startsWith("#") }

/** A short reply without a table or code: a remark on a late result, not a report. */
private fun remark(text: String) = text.length < 300 && !text.contains("```") && text.lines().none { it.trimStart().startsWith("|") }

fun chatBlocks(items: List<ChatItem>, busy: Boolean): List<ChatBlock> {
    val out = mutableListOf<ChatBlock>()
    var start = -1
    val turn = mutableListOf<ChatItem>()
    // The run in progress began after the last one's end: its parts (split by messages sent while it worked) go on.
    val runFrom = items.indexOfLast { it is ChatItem.Stats } + 1
    fun close(stats: RunStats?, live: Boolean) {
        val ongoing = live || (busy && stats == null && start >= runFrom)
        if (turn.isEmpty() && stats == null && !live) return
        // The answer is the text after the agent's own last step, reasoning, subagent or notice. (Old runs' subagent
        // steps, ↳, went on in the background after it answered: they don't push the answer into the process.)
        val sub = { it: ChatItem -> it is ChatItem.Step && it.tool.startsWith(SUBAGENT_STEP) }
        val boundary = { it: ChatItem ->
            it is ChatItem.Trigger || it is ChatItem.Subagent || ((it is ChatItem.Step || it is ChatItem.Thinking) && !sub(it))
        }
        val last = turn.indexOfLast(boundary)
        val said = (last + 1 until turn.size).filter { turn[it] is ChatItem.Assistant }.toMutableList()
        // While its subagents work, what the agent says (dsh wakes it as each one reports back) is part of the work:
        // the answer is what it writes once all of them are done.
        val waiting = ongoing && turn.any { it is ChatItem.Subagent && !it.settled }
        // Its subagents are joined (none works any more): the answer is its report, its main reply since it started
        // them (the in-between ones are short updates), and whatever it wrote after it. Whether it wrote the report
        // before the last notice reached it or after is up to the model, so the notices' order doesn't decide.
        val started = turn.indexOfLast { it is ChatItem.Subagent }
        val joined = started >= 0 && !waiting
        if (waiting) said.clear()
        else if (joined) {
            // While it still runs, the report is being written: only what came after the latest result can be it.
            val from = if (live) maxOf(started, turn.indexOfLast { it is ChatItem.Trigger }) else started
            val replies = (from + 1 until turn.size).filter { turn[it] is ChatItem.Assistant }
            replies.maxWithOrNull(compareBy({ !remark((turn[it] as ChatItem.Assistant).text) && structured((turn[it] as ChatItem.Assistant).text) },
                { (turn[it] as ChatItem.Assistant).text.length }))?.let { report ->
                said.clear()
                said += replies.filter { it >= report }
            }
        }
        // Runs saved before subagents had sections: one its subagents woke ends with the agent's reply to the last
        // result. When that is only a remark ("the table above has it all"), the reply before it is the report.
        val woke = turn.indexOfLast { it is ChatItem.Trigger }
        if (!waiting && !joined && woke >= 0 && (said.isNotEmpty() || !live) && said.all { remark((turn[it] as ChatItem.Assistant).text) }) {
            val from = turn.subList(0, woke).indexOfLast(boundary)
            said.addAll(0, (from + 1 until woke).filter { turn[it] is ChatItem.Assistant })
        }
        // Errors and files given to the user are part of the answer, wherever they came in the run.
        val inAnswer = { i: Int, it: ChatItem -> it is ChatItem.Error || it is ChatItem.File || i in said }
        val process = turn.filterIndexed { i, it -> !inAnswer(i, it) }
        val answer = turn.filterIndexed { i, it -> inAnswer(i, it) }
        out += ChatBlock.Turn("t:${if (start < 0) items.size else start}", process, answer, stats, live, ongoing)
        turn.clear(); start = -1
    }
    items.forEachIndexed { i, item ->
        when (item) {
            // A message typed while it works (steering) splits the run's process around it, as it happened.
            is ChatItem.User, is ChatItem.Command -> {
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

// ---- the conversation as list rows ----------------------------------------------------------------

/**
 * One row of the chat list: the unit the LazyColumn composes, recycles (by [contentType]) and skips when it is
 * unchanged. A turn is several rows (header, live steps, each block of its answer, footer), so a long answer
 * is laid out a block at a time as it scrolls into view, not all at once.
 */
sealed interface ChatEntry {
    val key: String
    /** Space above it: more before a new message or turn than between the rows of one turn. */
    val gap: Dp

    data class Message(override val key: String, override val gap: Dp, val item: ChatItem) : ChatEntry
    data class Header(override val key: String, override val gap: Dp, val turn: ChatBlock.Turn) : ChatEntry
    /** The live turn's latest steps and its reasoning as it streams. */
    data class LiveSteps(override val key: String, override val gap: Dp, val turn: ChatBlock.Turn) : ChatEntry
    /** The subagents of a turn that is not the live one, each card with how it goes (they may still work). */
    data class Agents(override val key: String, override val gap: Dp, val turn: ChatBlock.Turn) : ChatEntry
    /** One block (paragraph, list, code, table…) of an answer. */
    data class Answer(override val key: String, override val gap: Dp, val block: Markdown.Block) : ChatEntry
    data class Failure(override val key: String, override val gap: Dp, val text: String) : ChatEntry
    /** A file the agent gave the user. */
    data class File(override val key: String, override val gap: Dp, val item: ChatItem.File) : ChatEntry
    data class Footer(override val key: String, override val gap: Dp, val turn: ChatBlock.Turn) : ChatEntry
    /** The files the turn changed, each with its +/− (tap one for its diff). */
    data class Changes(override val key: String, override val gap: Dp, val files: List<ChangedFile>) : ChatEntry

    /** Rows of one kind reuse each other's composition as they scroll in and out (like RecyclerView's view types). */
    val contentType: Any get() = if (this is Answer) block.javaClass else javaClass
}

val BLOCK_GAP = 20.dp
val LINE_GAP = 12.dp

/** The rows for [blocks]. Answers' Markdown is parsed once per text (see [parsedMarkdown]). */
fun chatEntries(blocks: List<ChatBlock>): List<ChatEntry> {
    val out = ArrayList<ChatEntry>(blocks.size * 3)
    for (b in blocks) {
        var gap = if (out.isEmpty()) 0.dp else BLOCK_GAP
        fun next() = gap.also { gap = LINE_GAP }
        when (b) {
            is ChatBlock.Message -> out += ChatEntry.Message(b.key, next(), b.item)
            is ChatBlock.Turn -> {
                if (b.live || b.process.isNotEmpty()) out += ChatEntry.Header("${b.key}:h", next(), b)
                if (b.live) out += ChatEntry.LiveSteps("${b.key}:p", next(), b)
                // Subagents are always on the page, not only in the run's details: the live turn shows them with its
                // steps; any other turn (a finished run, or a part of this one split off by a message) right here.
                else if (b.agents.isNotEmpty()) out += ChatEntry.Agents("${b.key}:s", next(), b)
                b.answer.forEachIndexed { i, item ->
                    when (item) {
                        is ChatItem.Assistant -> parsedMarkdown(item.text).forEachIndexed { j, block -> out += ChatEntry.Answer("${b.key}:a$i.$j", next(), block) }
                        is ChatItem.Error -> out += ChatEntry.Failure("${b.key}:e$i", next(), item.text)
                        is ChatItem.File -> out += ChatEntry.File("${b.key}:f$i", next(), item)
                        else -> Unit
                    }
                }
                if (!b.live) changedFiles(b.process).takeIf { it.isNotEmpty() }?.let { out += ChatEntry.Changes("${b.key}:c", next(), it) }
                // The live turn's streaming answer goes before its footer (see SessionScreen). A footer with nothing to
                // show (a run split by a message sent while it worked: no answer to copy, its totals come at its end)
                // is no row: an empty row still takes the list's spacing, a gap before the next message.
                if (b.live || b.stats != null || b.answerText.isNotBlank()) out += ChatEntry.Footer("${b.key}:f", next(), b)
            }
        }
    }
    return out
}

/** One row on the page. Only the live turn's rows read [session]; the others skip while it streams. */
@Composable
fun ChatEntryView(e: ChatEntry, session: SessionState, onProcess: (String) -> Unit, onStats: (String) -> Unit) {
    val m = Modifier.padding(top = e.gap)
    when (e) {
        is ChatEntry.Message -> when (e.item) {
            is ChatItem.Trigger -> TriggerCard(e.item, m)
            is ChatItem.Command -> CommandMessage(e.item, m)
            else -> UserMessage(e.item, m)
        }
        is ChatEntry.Header -> TurnHeader(e.turn, session, onProcess, m)
        is ChatEntry.LiveSteps -> LiveProcess(e.turn, session, m)
        is ChatEntry.Agents -> SubagentCards(e.turn, m)
        is ChatEntry.Answer -> MarkdownBlock(e.block, m.fillMaxWidth().padding(horizontal = 2.dp))
        is ChatEntry.Failure -> ErrorText(e.text, m)
        is ChatEntry.File -> FileCard(e.item.file, e.item.caption, m)
        is ChatEntry.Footer -> TurnFooterRow(e.turn, session, onStats, m)
        is ChatEntry.Changes -> ChangesCard(e.files, m)
    }
}

@Composable
private fun TurnHeader(turn: ChatBlock.Turn, session: SessionState, onProcess: (String) -> Unit, modifier: Modifier) {
    val onClick = { onProcess(turn.key) }
    // A part of the run split off by a message, its subagents still working: it goes on as long as the run does.
    if (turn.live || turn.waiting) ProcessHeader(turn, session.status, session.thinkingSince != null, session.run, rememberNow(true), onClick, modifier)
    else ProcessHeader(turn, SessionStatus.Idle, false, turn.stats, 0L, onClick, modifier)
}

@Composable
private fun TurnFooterRow(turn: ChatBlock.Turn, session: SessionState, onStats: (String) -> Unit, modifier: Modifier) {
    val onClick = { onStats(turn.key) }
    if (turn.live) TurnFooter(turn, session.run, onClick, modifier)
    else TurnFooter(turn, turn.stats, onClick, modifier)
}

/** A file a turn changed: its [path], and every change made to it in order (by the agent or its subagents). */
data class ChangedFile(val path: String, val changes: List<com.lanqi.weiassist.agent.FileChange>)

/** The files [process] changed, in the order first changed; changes a tool refused don't count. */
fun changedFiles(process: List<ChatItem>): List<ChangedFile> {
    val steps = process.flatMap { item ->
        when (item) {
            is ChatItem.Step -> listOf(item)
            is ChatItem.Subagent -> item.items.filterIsInstance<ChatItem.Step>()
            else -> emptyList()
        }
    }.filter { it.changes.isNotEmpty() && it.ok != false && it.detail.isNotBlank() }
    return steps.groupBy { it.detail }.map { (path, st) -> ChangedFile(path, st.flatMap { it.changes }) }
}

/**
 * "2 files changed +61 −3" and a row per file (its name, its folder, +/−) after the answer, as coding agents end a
 * turn. Tap a file for its diff.
 */
@Composable
private fun ChangesCard(files: List<ChangedFile>, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    var openFile by rememberSaveable(files.map { it.path }) { mutableStateOf<String?>(null) }
    var all by rememberSaveable(files.map { it.path }) { mutableStateOf(false) }
    val total = remember(files) { diffStat(files.flatMap { it.changes }) }
    val shown = if (all || files.size <= MAX_FILES + 1) files else files.take(MAX_FILES)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceContainer).padding(vertical = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_edit, null, Modifier.size(14.dp), tint = c.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                pluralStringResource(R.plurals.files_changed, files.size, files.size), style = MaterialTheme.typography.labelLarge,
                color = c.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            DiffStatText(total)
        }
        shown.forEach { f ->
            val open = openFile == f.path
            Row(
                Modifier.fillMaxWidth().clickable { openFile = if (open) null else f.path }.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val name = f.path.substringAfterLast('/')
                Text(name, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = c.onSurface, maxLines = 1)
                Text(
                    "  " + f.path.removeSuffix(name).removeSuffix("/"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = c.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                DiffStatText(remember(f) { diffStat(f.changes) }, Modifier.padding(start = 8.dp))
                AppIcon(R.drawable.ic_chevron_right, null, Modifier.padding(start = 4.dp).size(14.dp).rotate(if (open) 90f else 0f), tint = c.outline)
            }
            AnimatedVisibility(open) {
                DiffView(f.path, f.changes, Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp)))
            }
        }
        if (shown.size < files.size) Text(
            stringResource(R.string.files_more, files.size - shown.size), style = MaterialTheme.typography.labelMedium, color = c.primary,
            modifier = Modifier.fillMaxWidth().clickable { all = true }.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private const val MAX_FILES = 5

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

/** The wall clock, ticking each second while [ticking], on the live grid (see LiveBeat.kt: no frames of its own). */
@Composable
private fun rememberNow(ticking: Boolean): Long = produceState(System.currentTimeMillis(), ticking) {
    value = System.currentTimeMillis()
    while (ticking) {
        delay(liveGridWait(1000))
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
    "message_session", "send_message" -> stringResource(R.string.tool_message_session)
    "list_sessions" -> stringResource(R.string.tool_list_sessions)
    "get_session" -> stringResource(R.string.tool_get_session)
    "wait_for" -> stringResource(R.string.tool_wait_for)
    "subagent", "subagent_fork" -> stringResource(R.string.tool_subagent)
    // DeepSeek Harness's own tools
    "web_fetch" -> stringResource(R.string.tool_web_fetch)
    "web_search" -> stringResource(R.string.tool_web_search)
    "read", "read_image" -> stringResource(R.string.tool_read_file)
    "write" -> stringResource(R.string.tool_write_file)
    "edit", "str_replace_editor", "apply_patch" -> stringResource(R.string.tool_edit_file)
    "grep" -> stringResource(R.string.tool_grep)
    "glob" -> stringResource(R.string.tool_glob)
    "todo_write" -> stringResource(R.string.tool_todo)
    "ask_user_question" -> stringResource(R.string.tool_ask_user)
    "exit_plan_mode" -> stringResource(R.string.tool_exit_plan_mode)
    "interrupt_agent" -> stringResource(R.string.tool_interrupt_agent)
    "list_agents" -> stringResource(R.string.tool_list_agents)
    else -> tool
}

private fun toolIcon(tool: String) = when (tool) {
    "bash" -> R.drawable.ic_terminal
    "open_app" -> R.drawable.ic_open_in_new
    "tap", "tap_at", "long_press", "swipe", "scroll", "press_key" -> R.drawable.ic_touch
    "type_text", "note" -> R.drawable.ic_edit
    "read_screen", "screenshot" -> R.drawable.ic_phone
    "create_session", "message_session", "send_message", "list_sessions", "get_session", "wait_for", "pause_session", "resume_session",
    "stop_session", "delete_session", "rename_session" -> R.drawable.ic_forum
    "subagent", "subagent_fork", "list_agents" -> R.drawable.ic_sparkle
    "web_fetch", "web_search" -> R.drawable.ic_open_in_new
    "write", "edit", "str_replace_editor", "apply_patch", "todo_write" -> R.drawable.ic_edit
    "interrupt_agent" -> R.drawable.ic_stop
    "ask_user_question", "exit_plan_mode" -> R.drawable.ic_help
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
    data class Woken(val item: ChatItem.Trigger) : ProcessLine
    data class Agent(val item: ChatItem.Subagent) : ProcessLine
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
        is ChatItem.Trigger -> out += ProcessLine.Woken(item)
        is ChatItem.Subagent -> out += ProcessLine.Agent(item)
        else -> Unit
    }
    return out
}

// ---- the turn on the page ---------------------------------------------------------------------------

@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Text(
        text, color = c.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth().background(c.errorContainer, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** "Thinking 18s ›" while it runs (shimmering), "Thought for 23s ›" after. */
@Composable
private fun ProcessHeader(
    turn: ChatBlock.Turn, status: SessionStatus, thinking: Boolean, run: RunStats?, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val elapsed = run?.let { if (turn.live || turn.waiting) now - it.startedAt else it.ms }
    val label = when {
        turn.live && status == SessionStatus.Queued -> stringResource(R.string.run_waiting)
        turn.live && status == SessionStatus.Paused -> stringResource(R.string.run_paused, duration(elapsed ?: 0))
        turn.waiting -> turn.agents.let { a -> stringResource(R.string.run_waiting_subagents, duration(elapsed ?: 0), a.count { it.settled }, a.size) }
        turn.live && thinking -> stringResource(R.string.run_thinking, duration(elapsed ?: 0))
        turn.live -> stringResource(R.string.run_working, duration(elapsed ?: 0))
        run != null && run.thinkingMs > 0 && turn.steps == 0 -> stringResource(R.string.run_thought_for, duration(run.ms))
        run != null && turn.subagents > 0 -> stringResource(R.string.run_worked_for, duration(run.ms)) + " · " +
            pluralStringResource(R.plurals.run_subagents, turn.subagents, turn.subagents)
        run != null -> stringResource(R.string.run_worked_for, duration(run.ms))
        // A part of a run (split by a message or a subagent's notice): its totals come at the run's end.
        turn.steps == 0 && turn.process.any { it is ChatItem.Thinking } ->
            stringResource(R.string.run_thought_for, duration(turn.process.sumOf { (it as? ChatItem.Thinking)?.ms ?: 0L }))
        else -> pluralStringResource(R.plurals.run_steps, turn.steps, turn.steps)
    }
    val animate = (turn.live || turn.waiting) && status == SessionStatus.Running
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp),
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
    // Every other tick of the live clock (LiveBeat.kt), shared with the other live indicators.
    val phase by steppingPhase(3, beats = 2)
    val color = MaterialTheme.colorScheme.primary
    Row(Modifier.size(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i -> Box(Modifier.size(4.dp).alpha(if (i == phase) 1f else 0.35f).background(color, CircleShape)) }
    }
}

/** Text in [base] color, gently brightening and dimming while the work it names goes on. */
@Composable
private fun shimmer(base: Color): Brush {
    val phase by steppingPhase(SHIMMER_LEVELS.size)
    return SolidColor(base.copy(alpha = SHIMMER_LEVELS[phase]))
}

private val SHIMMER_LEVELS = floatArrayOf(0.45f, 0.6f, 0.75f, 0.9f, 1f, 0.9f, 0.75f, 0.6f)

/** While it runs: its reasoning as it streams, and its latest steps (the newest one shimmering while it runs). */
@Composable
private fun LiveProcess(turn: ChatBlock.Turn, session: SessionState, modifier: Modifier = Modifier) {
    val now = rememberNow(true)
    // The agent's own latest work; its subagents show below it, each in its own section.
    val lines = remember(turn.process) {
        processLines(turn.process).filterNot { it is ProcessLine.Thought || it is ProcessLine.Woken || it is ProcessLine.Agent }
    }
    val agents = remember(turn.process) { turn.agents }
    val shown = lines.takeLast(MAX_LIVE_LINES)
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                // Notices are in the subagents' sections (their results), reasoning below.
                is ProcessLine.Thought, is ProcessLine.Woken, is ProcessLine.Agent -> Unit
            }
        }
        if (agents.isNotEmpty()) Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            agents.forEach { SubagentCard(it, now, live = true, turn.process) }
        }
        AnimatedVisibility(session.thinkingSince != null || session.thinking.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            LiveThinking(session.thinking, session.thinkingSince?.let { now - it })
        }
    }
}

private const val MAX_LIVE_LINES = 4

/** The subagents of a turn other than the live one: still working while the run they are part of goes on. */
@Composable
private fun SubagentCards(turn: ChatBlock.Turn, modifier: Modifier = Modifier) {
    val now = rememberNow(turn.waiting)
    Column(modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        turn.agents.forEach { SubagentCard(it, now, live = turn.ongoing, turn.process) }
    }
}

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
        // A command reads as itself, as in a terminal: "$ go build ./...".
        val command = line.tool == "bash" && showDetail && last.detail.isNotBlank()
        val label = if (command) last.detail.lineSequence().first()
        else toolLabel(line.tool) + if (line.steps.size > 1) " ×${line.steps.size}" else ""
        Text(
            label,
            style = (if (command) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            else MaterialTheme.typography.bodyMedium).let { if (shimmering) it.copy(brush = shimmer(c.onSurface)) else it },
            color = if (shimmering) Color.Unspecified else color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (command) Modifier.weight(1f, fill = false) else Modifier,
        )
        // Several subagents started together: one task's name would read as the line's; their cards name them all.
        val grouped = line.steps.size > 1 && (line.tool == "subagent" || line.tool == "subagent_fork")
        // A file tool: the file's name (several: how many files) and what it changed in them, +12 −3.
        val changed = line.steps.filter { it.changes.isNotEmpty() }
        val detail = when {
            changed.isEmpty() -> last.detail.lineSequence().first()
            else -> changed.map { it.detail }.distinct().let { f -> if (f.size == 1) f[0].substringAfterLast('/') else pluralStringResource(R.plurals.files_changed, f.size, f.size) }
        }
        if (showDetail && !grouped && !command && detail.isNotBlank()) Text(
            "  $detail", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.outline,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (changed.isNotEmpty()) DiffStatText(remember(changed) { diffStat(changed.flatMap { it.changes }) }, Modifier.padding(start = 8.dp))
        if (line.failed > 0 && !failed) AppIcon(R.drawable.ic_cancel, null, Modifier.padding(start = 6.dp).size(12.dp), tint = c.error)
    }
}

/** Copy, then "3 steps · 21.6K tokens ›" (live while it runs; with the time first when there is no header). */
@Composable
private fun TurnFooter(turn: ChatBlock.Turn, run: RunStats?, onStats: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val text = remember(turn.answer) { turn.answerText }
    val copyable = !turn.live && text.isNotBlank()
    if (!copyable && run == null) return
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (copyable) {
            IconButton(onClick = { copyText(ctx, text) }, modifier = Modifier.size(32.dp)) {
                AppIcon(R.drawable.ic_copy, stringResource(R.string.copy), Modifier.size(16.dp), tint = c.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        if (run != null) Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onStats).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val parts = buildList {
                // The header ("Thought for 23s") already shows the time when there is one (see chatEntries).
                if (!turn.live && turn.process.isEmpty()) add(duration(run.ms))
                add(pluralStringResource(R.plurals.run_steps, run.steps, run.steps))
                if (run.tokens > 0) add(stringResource(R.string.run_tokens, compactCount(run.tokens)))
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = c.outline)
            AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(16.dp), tint = c.outline)
        }
    }
}

/** Put [text] on the clipboard and say so. */
fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
    Toast.makeText(ctx, ctx.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}

// ---- sheets ------------------------------------------------------------------------------------------

/** Everything the turn did, in order: each reasoning in full, each step with its argument and result. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessSheet(turn: ChatBlock.Turn, session: SessionState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val now = rememberNow(turn.ongoing)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Text(
            stringResource(R.string.run_process), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        val lines = remember(turn.process) { processLines(turn.process) }
        LazyColumn(
            Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (lines.isEmpty() && !turn.live) item { Text(stringResource(R.string.run_stopped_early), color = c.onSurfaceVariant) }
            items(lines.size, contentType = { lines[it].javaClass }) { i ->
                when (val line = lines[i]) {
                    is ProcessLine.Thought -> ThoughtEntry(line.item.text, line.item.ms)
                    is ProcessLine.Steps -> StepsEntry(line)
                    is ProcessLine.Said -> MarkdownText(line.text, Modifier.fillMaxWidth())
                    is ProcessLine.Woken -> TriggerCard(line.item, label = turn.agents.firstOrNull { it.id == line.item.subagent }?.label)
                    is ProcessLine.Agent -> SubagentCard(line.item, now, live = turn.ongoing, turn.process)
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
        if (line.tool == "bash") line.steps.forEach { st -> CommandRow(st) }
        else StepLine(line, shimmering = line.running && line.steps.size == 1)
        if (line.tool == "bash") Unit
        else if (line.steps.any { it.changes.isNotEmpty() }) Column(
            Modifier.fillMaxWidth().padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Each file change as its diff; a refused one in red, with why.
            line.steps.forEach { st ->
                FileDiffCard(st.detail, st.changes, failed = st.ok == false, note = if (st.ok == false) st.result else "")
            }
        } else if (line.steps.size > 1 || line.steps.any { it.result.isNotBlank() }) Column(
            Modifier.padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            line.steps.forEach { st ->
                // In full: what it worked on (an address, a search, a file) and what came back, wrapped, not cut.
                Row(verticalAlignment = Alignment.Top) {
                    val icon = Modifier.padding(top = 3.dp).size(12.dp)
                    when (st.ok) {
                        null -> Box(icon, contentAlignment = Alignment.Center) { Box(Modifier.size(5.dp).background(c.outline, CircleShape)) }
                        true -> AppIcon(R.drawable.ic_check_circle, null, icon, tint = c.outline)
                        false -> AppIcon(R.drawable.ic_cancel, null, icon, tint = c.error)
                    }
                    Text(
                        listOf(st.detail, st.result).filter { it.isNotBlank() }.joinToString("  →  "),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = if (st.ok == false) c.error else c.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
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

/**
 * A command the agent ran, plainly: how it went, the command, and its output under it (its last lines; all of it
 * on a tap). dsh calls a command a success whatever its exit code: a non-zero "[exit code: N]" marks it failed.
 */
@Composable
private fun CommandRow(st: ChatItem.Step) {
    val c = MaterialTheme.colorScheme
    val out = st.output.ifEmpty { st.result }
    val lines = remember(out) { if (out.isEmpty()) emptyList() else out.lines() }
    val failed = st.ok == false || remember(lines) {
        lines.lastOrNull()?.trim()?.let { EXIT_LINE.find(it) }?.groupValues?.get(1)?.let { it != "0" } == true
    }
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all || lines.size <= COMMAND_PEEK + 2) lines else lines.takeLast(COMMAND_PEEK)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            val icon = Modifier.padding(top = 3.dp).size(14.dp)
            when {
                st.ok == null -> Box(icon, contentAlignment = Alignment.Center) { Box(Modifier.size(5.dp).background(c.outline, CircleShape)) }
                failed -> AppIcon(R.drawable.ic_cancel, null, icon, tint = c.error)
                else -> AppIcon(R.drawable.ic_terminal, null, icon, tint = c.outline)
            }
            Text(
                st.detail, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = c.onSurface,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }
        if (shown.isNotEmpty()) Column(Modifier.padding(start = 22.dp, top = 4.dp)) {
            if (shown.size < lines.size) Text("⋯", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.outline)
            Text(
                remember(shown) {
                    shown.joinToString("\n") { l ->
                        com.lanqi.weiassist.agent.SessionReducer.OUTPUT_GAP.find(l)?.let { "⋯" } ?: l.replace("\t", "    ")
                    }
                },
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = c.onSurfaceVariant,
            )
            if (lines.size > COMMAND_PEEK + 2) Text(
                if (all) stringResource(R.string.code_show_less) else stringResource(R.string.diff_show_all, lines.size),
                style = MaterialTheme.typography.labelMedium, color = c.primary,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(4.dp)).clickable { all = !all }.padding(vertical = 2.dp),
            )
        }
    }
}

private const val COMMAND_PEEK = 8
private val EXIT_LINE = Regex("""^\[exit code: (-?\d+)]$""")

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
            // The cache, always (a 0 says this provider or run had no hit, which is worth seeing too).
            run.cacheHitPercent?.let { StatRow(stringResource(R.string.stat_cache_hit), "$it%") }
            StatRow(stringResource(R.string.stat_input), groupedCount(run.input))
            StatRow(stringResource(R.string.stat_cache_read), groupedCount(run.cacheRead))
            StatRow(stringResource(R.string.stat_cache_write), groupedCount(run.cacheWrite))
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
    // The label keeps its width; a long value (a custom provider's model, provider/model) wraps on the right.
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (sub) c.outline else c.onSurfaceVariant, maxLines = 1)
        Text(
            value, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.End),
            color = if (sub) c.outline else c.onSurface, modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
    }
}

/**
 * What woke the agent (DeepSeek Harness: a subagent finished, or wrote to it): a quiet card where the agent's reply to
 * it starts, as dsh's own chat shows it. Tap to read the notice itself.
 */
@Composable
private fun TriggerCard(item: ChatItem.Trigger, modifier: Modifier = Modifier, label: String? = null) {
    val c = MaterialTheme.colorScheme
    var open by rememberSaveable(item) { mutableStateOf(false) }
    val (icon, title) = when (item.source) {
        "subagent-settled" -> R.drawable.ic_check_circle to R.string.trigger_subagent
        "agent-message" -> R.drawable.ic_forum to R.string.trigger_agent
        "team-message" -> R.drawable.ic_forum to R.string.trigger_team
        "tool-jobs" -> R.drawable.ic_terminal to R.string.trigger_job
        else -> R.drawable.ic_sparkle to R.string.trigger_other
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer)
            .clickable(enabled = item.text.isNotBlank()) { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            val text = stringResource(title).let { t -> if (label.isNullOrBlank()) t else stringResource(R.string.subagent_settled, label, t) }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (item.text.isNotBlank()) AppIcon(R.drawable.ic_chevron_right, null,
                Modifier.size(16.dp).rotate(if (open) 90f else 0f), tint = c.onSurfaceVariant)
        }
        if (open) Text(
            item.text.trim(), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
            color = c.onSurfaceVariant, modifier = Modifier.padding(start = 26.dp, top = 8.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * A subagent's report from dsh's notice: the message it sent ("Agent … sent a message: …"), or the closing text of
 * its settlement notice ("Subagent … finished … Its closing message: …").
 */
private fun closingMessage(notice: String): String {
    MESSAGE.find(notice)?.let { return notice.substring(it.range.last + 1).trim() }
    val at = notice.indexOf(com.lanqi.weiassist.agent.SessionReducer.CLOSING)
    return if (at >= 0) notice.substring(at + com.lanqi.weiassist.agent.SessionReducer.CLOSING.length).trim() else ""
}

private val MESSAGE = Regex("""^Agent \S+ sent a message: ?""")

/**
 * One subagent's own section (DeepSeek Harness), as dsh's web chat gives each child its own conversation: what it
 * works on, how it goes (its reasoning or latest step as it streams, or how it ended), its time and steps. Tap for
 * all of its work and its report.
 */
@Composable
private fun SubagentCard(item: ChatItem.Subagent, now: Long, live: Boolean, turn: List<ChatItem>, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val working = live && !item.settled
    // Its report: what dsh's notices carried (sessions saved before kept only its last message as a notice).
    val report = remember(item.result, turn) {
        closingMessage(item.result).ifBlank {
            turn.lastOrNull { it is ChatItem.Trigger && it.subagent == item.id && it.source == "agent-message" }
                ?.let { closingMessage((it as ChatItem.Trigger).text) }.orEmpty()
        }
    }
    val lines = remember(item.items) { processLines(item.items) }
    val since = item.thinkingSince
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer)
            .clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    working -> PulsingDots()
                    item.outcome == "done" -> AppIcon(R.drawable.ic_check_circle, null, Modifier.size(16.dp), tint = c.primary)
                    item.outcome == "failed" -> AppIcon(R.drawable.ic_cancel, null, Modifier.size(16.dp), tint = c.error)
                    else -> AppIcon(R.drawable.ic_stop, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                item.label.ifBlank { stringResource(R.string.subagent_untitled) }, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            val ms = if (item.settled || !live) item.ms else now - item.startedAt
            val facts = buildList {
                if (ms > 0) add(duration(ms))
                if (item.steps > 0) add(pluralStringResource(R.plurals.run_steps, item.steps, item.steps))
            }
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = c.outline, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            AppIcon(R.drawable.ic_chevron_right, null, Modifier.padding(start = 2.dp).size(16.dp).rotate(if (open) 90f else 0f), tint = c.outline)
        }
        // Closed: one line of how it goes, or how it ended.
        if (!open) Box(Modifier.padding(start = 26.dp, top = 6.dp)) {
            val quiet = MaterialTheme.typography.bodySmall
            when {
                working && since != null -> Text(
                    item.thinking.trimEnd().takeLast(300).replace('\n', ' ').ifBlank { stringResource(R.string.run_thinking, duration(now - since)) },
                    style = quiet.copy(fontStyle = FontStyle.Italic), color = c.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                working && item.streaming.isNotBlank() -> Text(
                    stringResource(R.string.subagent_writing), style = quiet.copy(brush = shimmer(c.onSurfaceVariant)), maxLines = 1,
                )
                working -> (lines.lastOrNull() as? ProcessLine.Steps)?.let { StepLine(it, shimmering = it.running) }
                    ?: Text(stringResource(if (item.running) R.string.subagent_starting else R.string.subagent_waiting), style = quiet, color = c.onSurfaceVariant)
                report.isNotBlank() -> Text(
                    remember(report) { Markdown.plain(report).replace('\n', ' ') }, style = quiet, color = c.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                else -> Text(
                    stringResource(when (item.outcome) { "done" -> R.string.subagent_done; "failed" -> R.string.subagent_failed; else -> R.string.subagent_stopped }),
                    style = quiet, color = c.onSurfaceVariant,
                )
            }
        }
        // Open: all of its work in order, what it streams now, and its report (its text a size below the answer's).
        AnimatedVisibility(open) { MaterialTheme(MaterialTheme.colorScheme, typography = MaterialTheme.typography.let { it.copy(bodyLarge = it.bodyMedium) }) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                lines.forEach { line ->
                    when (line) {
                        is ProcessLine.Thought -> ThoughtEntry(line.item.text, line.item.ms)
                        is ProcessLine.Steps -> StepsEntry(line)
                        // Its report is below; its other messages (to its parent, along the way) here.
                        is ProcessLine.Said -> if (report.trim() != line.text.trim()) MarkdownText(line.text, Modifier.fillMaxWidth())
                        is ProcessLine.Woken, is ProcessLine.Agent -> Unit
                    }
                }
                if (working && since != null) ThoughtEntry(item.thinking, now - since, live = true)
                if (working && item.streaming.isNotBlank()) MarkdownText(item.streaming, Modifier.fillMaxWidth())
                if (report.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.subagent_result), style = MaterialTheme.typography.labelMedium, color = c.outline)
                    MarkdownText(report, Modifier.fillMaxWidth())
                }
            }
        } }
    }
}
