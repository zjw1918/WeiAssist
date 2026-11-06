package com.lanqi.weiassist.ui

import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.CodeHighlight
import com.lanqi.weiassist.agent.Diff
import com.lanqi.weiassist.agent.FileChange

/**
 * Code as a coding agent shows it: on a dark editor surface in light and dark themes alike, coloured by language
 * (agent/CodeHighlight.kt), and file changes as diffs (agent/Diff.kt).
 */
object CodeColors {
    val background = Color(0xFF161B22)
    val header = Color(0xFF1F242C)
    val border = Color(0xFF30363D)
    val text = Color(0xFFE6EDF3)
    val dim = Color(0xFF8B949E)
    val keyword = Color(0xFFFF7B72)
    val string = Color(0xFFA5D6FF)
    val comment = Color(0xFF8B949E)
    val number = Color(0xFF79C0FF)
    val function = Color(0xFFD2A8FF)
    val type = Color(0xFFFFA657)
    val key = Color(0xFF7EE787)
    val meta = Color(0xFF56D4DD)
    val added = Color(0xFF3FB950)
    val removed = Color(0xFFF85149)
    val addedLine = Color(0x2E2EA043)
    val removedLine = Color(0x2EF85149)
    val fold = Color(0xFF1C2A3A)
}

private fun style(kind: CodeHighlight.Kind): SpanStyle = when (kind) {
    CodeHighlight.Kind.Keyword -> SpanStyle(color = CodeColors.keyword)
    CodeHighlight.Kind.Type -> SpanStyle(color = CodeColors.type)
    CodeHighlight.Kind.Str -> SpanStyle(color = CodeColors.string)
    CodeHighlight.Kind.Comment -> SpanStyle(color = CodeColors.comment, fontStyle = FontStyle.Italic)
    CodeHighlight.Kind.Number -> SpanStyle(color = CodeColors.number)
    CodeHighlight.Kind.Function -> SpanStyle(color = CodeColors.function)
    CodeHighlight.Kind.Key -> SpanStyle(color = CodeColors.key)
    CodeHighlight.Kind.Meta -> SpanStyle(color = CodeColors.meta)
    CodeHighlight.Kind.Added -> SpanStyle(color = CodeColors.added)
    CodeHighlight.Kind.Removed -> SpanStyle(color = CodeColors.removed)
}

/** Tabs drawn 4 wide (a tab is as wide as a space in a Text). */
private fun untab(s: String) = if (s.indexOf('\t') < 0) s else s.replace("\t", "    ")

/** [code] coloured as [lang] (plain when the language isn't one we know). */
fun highlighted(code: String, lang: String): AnnotatedString {
    val text = untab(code)
    val tokens = CodeHighlight.tokens(text, lang)
    if (tokens.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        for (t in tokens) {
            if (t.start < at) continue
            append(text, at, t.start)
            withStyle(style(t.kind)) { append(text, t.start, t.end) }
            at = t.end
        }
        append(text, at, text.length)
    }
}

/** Where line [n] of [text] starts (its length when it has fewer lines). */
private fun lineStart(text: CharSequence, n: Int): Int {
    var at = 0
    repeat(n) { at = text.indexOf('\n', at).let { i -> if (i < 0) return text.length else i + 1 } }
    return at
}

/** Code longer than this many lines shows its first [CODE_PEEK] lines, and the rest on a tap. */
private const val CODE_FOLD = 30
private const val CODE_PEEK = 20

/**
 * A fenced code block of an answer: its language, size and a copy button in a header, the code below, scrolling
 * sideways. A long one shows its start, fading out, and "Show all N lines".
 */
@Composable
fun CodeBlock(text: String, lang: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    val body = remember(text) { text.trimEnd('\n') }
    val lines = remember(body) { body.count { it == '\n' } + 1 }
    val long = lines > CODE_FOLD
    var all by rememberSaveable { mutableStateOf(false) }
    // Folded, only the lines shown are coloured: while the answer streams, they stop changing once written, and
    // the colouring is kept instead of redone for every chunk.
    val visible = if (long && !all) body.substring(0, lineStart(body, CODE_PEEK).let { if (it > 0) it - 1 else it }) else body
    val shown = remember(visible, lang) { highlighted(visible, lang) }
    Column(modifier.fillMaxWidth().clip(shape).background(CodeColors.background).border(1.dp, CodeColors.border, shape)) {
        Row(Modifier.fillMaxWidth().background(CodeColors.header).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang, style = CodeStyle.copy(fontSize = 12.sp), color = CodeColors.dim, maxLines = 1, modifier = Modifier.weight(1f))
            if (long) Text(
                pluralStringResource(R.plurals.code_lines, lines, lines), style = CodeStyle.copy(fontSize = 11.sp),
                color = CodeColors.dim.copy(alpha = 0.7f), maxLines = 1,
            )
            IconButton(onClick = { copyText(ctx, text) }, modifier = Modifier.size(34.dp)) {
                AppIcon(R.drawable.ic_copy, stringResource(R.string.copy), Modifier.size(15.dp), tint = CodeColors.dim)
            }
        }
        Box {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(shown, style = CodeStyle, color = CodeColors.text, softWrap = false)
            }
            // The cut-off code fades into the "show all" bar.
            if (long && !all) Box(
                Modifier.matchParentSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to CodeColors.background)),
            )
        }
        if (long) ExpandBar(all, lines) { all = !all }
    }
}

/** "⌄ Show all N lines" under folded code or output, "⌃ Show less" once open. */
@Composable
fun ExpandBar(open: Boolean, lines: Int, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).background(CodeColors.header).padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(13.dp).rotate(if (open) -90f else 90f), tint = CodeColors.meta)
        Text(
            if (open) stringResource(R.string.code_show_less) else stringResource(R.string.diff_show_all, lines),
            style = MaterialTheme.typography.labelMedium, color = CodeColors.meta, modifier = Modifier.padding(start = 6.dp),
        )
    }
}


// ---- diffs ---------------------------------------------------------------------------------------------

/** Each change's diff lines, worked out once per change (rows recompose as they scroll). */
private val diffs = LruCache<FileChange, List<Diff.Line>>(64)

fun diffLines(c: FileChange): List<Diff.Line> = diffs[c] ?: Diff.lines(c.old, c.new).also { diffs.put(c, it) }

fun diffStat(changes: List<FileChange>): Diff.Stat = changes.fold(Diff.Stat.NONE) { s, c -> s + Diff.stat(diffLines(c)) }

/** "+12 −3", in green and red readable on the page's own surface. */
@Composable
fun DiffStatText(stat: Diff.Stat, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val dark = onDark || MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val green = if (dark) CodeColors.added else Color(0xFF1A7F37)
    val red = if (dark) CodeColors.removed else Color(0xFFCF222E)
    val text = remember(stat, dark) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = green)) { append("+${stat.added}") }
            if (stat.removed > 0) { append(" "); withStyle(SpanStyle(color = red)) { append("−${stat.removed}") } }
        }
    }
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, modifier = modifier)
}

/**
 * A file's changes as one unified diff: removed lines on red, added ones on green, a few unchanged lines around
 * them and the rest folded. A whole-file write shows its line numbers (an edit's would be the snippet's, not the
 * file's). Past [collapsedLines] it shows the start, and the rest on a tap.
 */
@Composable
fun DiffView(path: String, changes: List<FileChange>, modifier: Modifier = Modifier, collapsedLines: Int = 40) {
    val lang = remember(path) { CodeHighlight.langOfPath(path) }
    val written = changes.size == 1 && changes[0].old.isEmpty()
    val rows = remember(changes) {
        buildList {
            changes.forEachIndexed { i, c ->
                // Separate edits of one file: a fold between them (where in the file each one is isn't known).
                if (i > 0) add(Diff.Line(Diff.Kind.Fold, "", folded = -1))
                addAll(Diff.hunks(diffLines(c)))
            }
        }
    }
    var all by rememberSaveable(path, changes.hashCode()) { mutableStateOf(false) }
    val shown = if (all || rows.size <= collapsedLines + 5) rows else rows.take(collapsedLines)
    val numbers = if (written) (rows.maxOfOrNull { it.new } ?: 0).toString().length else 0
    Column(modifier.fillMaxWidth().background(CodeColors.background)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val min = maxWidth
            Column(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = min).width(IntrinsicSize.Max).padding(vertical = 6.dp)) {
                shown.forEach { DiffRow(it, lang, numbers) }
            }
        }
        if (shown.size < rows.size) Text(
            stringResource(R.string.diff_show_all, rows.size), style = MaterialTheme.typography.labelMedium, color = CodeColors.meta,
            modifier = Modifier.fillMaxWidth().clickable { all = true }.background(CodeColors.header).padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (changes.any { it.cut }) Text(
            stringResource(R.string.diff_cut), style = MaterialTheme.typography.labelSmall, color = CodeColors.dim,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DiffRow(line: Diff.Line, lang: String, numbers: Int) {
    val small = CodeStyle.copy(fontSize = 12.sp, lineHeight = 17.sp)
    if (line.kind == Diff.Kind.Fold) {
        Text(
            if (line.folded > 0) "⋯  " + pluralStringResource(R.plurals.diff_unchanged, line.folded, line.folded) else "⋯",
            style = small, color = CodeColors.dim, maxLines = 1,
            modifier = Modifier.fillMaxWidth().background(CodeColors.fold).padding(horizontal = 12.dp, vertical = 2.dp),
        )
        return
    }
    val (bg, sign, signColor) = when (line.kind) {
        Diff.Kind.Added -> Triple(CodeColors.addedLine, "+", CodeColors.added)
        Diff.Kind.Removed -> Triple(CodeColors.removedLine, "−", CodeColors.removed)
        else -> Triple(Color.Transparent, " ", CodeColors.dim)
    }
    val text = remember(line.text, lang) { highlighted(line.text, lang) }
    Row(Modifier.fillMaxWidth().background(bg).padding(end = 12.dp)) {
        if (numbers > 0) Text(
            (if (line.new > 0) line.new.toString() else "").padStart(numbers), style = small, color = CodeColors.dim.copy(alpha = 0.6f),
            maxLines = 1, modifier = Modifier.padding(start = 8.dp),
        )
        Text(sign, style = small, color = signColor, modifier = Modifier.padding(start = 8.dp, end = 8.dp))
        Text(text, style = small, color = CodeColors.text, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/**
 * A file the agent changed: its path and +/− in a header, its diff below ([open]; tap the header to toggle).
 * [failed]: the tool refused the change ([note] says why): the diff is what it tried.
 */
@Composable
fun FileDiffCard(
    path: String, changes: List<FileChange>, modifier: Modifier = Modifier, failed: Boolean = false, note: String = "", startOpen: Boolean = true,
) {
    val shape = RoundedCornerShape(10.dp)
    var open by rememberSaveable(path, changes.hashCode()) { mutableStateOf(startOpen) }
    val stat = remember(changes) { diffStat(changes) }
    Column(modifier.fillMaxWidth().clip(shape).background(CodeColors.background).border(1.dp, CodeColors.border, shape)) {
        Row(
            Modifier.fillMaxWidth().background(CodeColors.header).clickable(enabled = changes.isNotEmpty()) { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name = path.substringAfterLast('/')
            val dir = path.removeSuffix(name)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = CodeColors.dim)) { append(dir) }
                    withStyle(SpanStyle(color = if (failed) CodeColors.removed else CodeColors.text)) { append(name) }
                },
                style = CodeStyle.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = true),
            )
            if (changes.isNotEmpty()) {
                if (changes.size == 1 && changes[0].old.isEmpty()) Text(
                    stringResource(R.string.diff_written), style = MaterialTheme.typography.labelSmall, color = CodeColors.dim,
                    modifier = Modifier.padding(start = 8.dp),
                )
                DiffStatText(stat, Modifier.padding(start = 8.dp), onDark = true)
                AppIcon(R.drawable.ic_chevron_right, null, Modifier.padding(start = 4.dp).size(14.dp).rotate(if (open) 90f else 0f), tint = CodeColors.dim)
            }
        }
        if (failed && note.isNotBlank()) Text(
            note, style = CodeStyle.copy(fontSize = 12.sp), color = CodeColors.removed, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (open && changes.isNotEmpty()) DiffView(path, changes)
    }
}
