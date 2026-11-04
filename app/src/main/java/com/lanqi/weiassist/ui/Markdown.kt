package com.lanqi.weiassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.Markdown

/** An answer's Markdown (parsed by agent's Markdown), drawn with the app's theme. Text can be selected and copied. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parsedMarkdown(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { Block(it) }
        }
    }
}

/** One block of an answer, as its own row of the chat list. Its text can be selected and copied. */
@Composable
fun MarkdownBlock(block: Markdown.Block, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) { Block(block) }
}

/**
 * Parsed answers by text. The chat's rows are rebuilt whenever an item changes (each step of a run), and a
 * row scrolled back into view is composed again: neither parses an answer twice. Main thread.
 */
private val parsed = android.util.LruCache<String, List<Markdown.Block>>(512)

fun parsedMarkdown(text: String): List<Markdown.Block> = parsed[text] ?: Markdown.parse(text).also { parsed.put(text, it) }

private val plain = android.util.LruCache<String, String>(512)

/** A preview line: Markdown's text without its markers, on one line (cached, like [parsedMarkdown]). */
fun plainLine(text: String): String = plain[text] ?: Markdown.plain(text).replace('\n', ' ').also { plain.put(text, it) }

/**
 * The answer streaming now, as blocks. A blank line outside a code block ends the blocks before it for good
 * (the parser never joins blocks across one), so that settled part is parsed once and keeps its block
 * instances, which lets their rows skip; each token re-parses only the paragraph being written.
 */
class StreamingMarkdown {
    private var head = ""
    private var headBlocks = emptyList<Markdown.Block>()

    fun blocks(text: String): List<Markdown.Block> {
        val cut = settledEnd(text)
        if (cut != head.length || !text.startsWith(head)) {
            head = text.substring(0, cut)
            headBlocks = if (cut == 0) emptyList() else Markdown.parse(head)
        }
        val tail = if (cut == 0) text else text.substring(cut)
        return if (tail.isBlank()) headBlocks else headBlocks + Markdown.parse(tail)
    }

    /** Where the last blank line outside a fenced code block starts (0 if there is none). */
    private fun settledEnd(text: String): Int {
        var end = 0
        var fenced = false
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            var i = start
            while (i < nl && (text[i] == ' ' || text[i] == '\t')) i++
            when {
                text.startsWith("```", i) || text.startsWith("~~~", i) -> fenced = !fenced
                i == nl && !fenced && nl < text.length -> end = start
            }
            start = nl + 1
        }
        return end
    }
}

@Composable
private fun Block(b: Markdown.Block) {
    val type = MaterialTheme.typography
    when (b) {
        is Markdown.Heading -> Text(
            annotated(b.text),
            style = when (b.level) { 1 -> type.titleLarge; 2 -> type.titleMedium; else -> type.titleSmall }.copy(fontWeight = FontWeight.SemiBold),
        )
        is Markdown.Paragraph -> Text(annotated(b.text), style = type.bodyMedium)
        is Markdown.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.items.forEach { item ->
                Row(Modifier.padding(start = (item.depth * 16).dp)) {
                    Text(item.marker, style = type.bodyMedium, modifier = Modifier.width(if (item.marker == "•") 16.dp else 24.dp))
                    if (item.code.isEmpty()) Text(annotated(item.text), style = type.bodyMedium)
                    else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(annotated(item.text), style = type.bodyMedium)
                        item.code.forEach { Block(it) }
                    }
                }
            }
        }
        // A tinted, outlined block on the page; long lines scroll sideways instead of wrapping. Copy at its top right.
        is Markdown.Code -> {
            val c = MaterialTheme.colorScheme
            val ctx = LocalContext.current
            val shape = RoundedCornerShape(10.dp)
            Box(Modifier.fillMaxWidth().clip(shape).background(c.surfaceContainerHigh).border(1.dp, c.outlineVariant.copy(alpha = 0.6f), shape)) {
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 44.dp, top = 10.dp, bottom = 10.dp)) {
                    Text(b.text, style = CodeStyle, color = c.onSurface, softWrap = false)
                }
                IconButton(
                    onClick = { copyText(ctx, b.text) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceContainerHigh),
                ) {
                    AppIcon(R.drawable.ic_copy, stringResource(R.string.copy), Modifier.size(16.dp), tint = c.onSurfaceVariant)
                }
            }
        }
        is Markdown.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { b.blocks.forEach { Block(it) } }
        }
        is Markdown.Table -> Table(b)
        Markdown.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * A grid: each column as wide as its widest cell (a cell wraps beyond [MAX_CELL]), each row as tall as its
 * tallest cell. Wider than the bubble: it scrolls sideways.
 */
@Composable
private fun Table(t: Markdown.Table) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val style = MaterialTheme.typography.bodySmall
    val cols = t.header.size
    val rows = listOf(t.header) + t.rows
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Layout(
            modifier = Modifier.border(1.dp, line, RoundedCornerShape(6.dp)),
            content = {
                rows.forEachIndexed { r, cells ->
                    cells.forEachIndexed { c, cell ->
                        Text(
                            annotated(cell),
                            style = if (r == 0) style.copy(fontWeight = FontWeight.SemiBold) else style,
                            textAlign = when (t.align[c]) { Markdown.Align.Start -> TextAlign.Start; Markdown.Align.Center -> TextAlign.Center; Markdown.Align.End -> TextAlign.End },
                            modifier = Modifier
                                .background(if (r == 0) headerBg else Color.Transparent)
                                .border(0.5.dp, line)
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            },
        ) { measurables, _ ->
            val maxCell = MAX_CELL.roundToPx()
            val widths = IntArray(cols)
            measurables.forEachIndexed { i, m -> widths[i % cols] = maxOf(widths[i % cols], minOf(m.maxIntrinsicWidth(Constraints.Infinity), maxCell)) }
            val heights = IntArray(rows.size)
            measurables.forEachIndexed { i, m -> heights[i / cols] = maxOf(heights[i / cols], m.minIntrinsicHeight(widths[i % cols])) }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % cols], heights[i / cols])) }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in rows.indices) {
                    var x = 0
                    for (c in 0 until cols) { placeables[r * cols + c].place(x, y); x += widths[c] }
                    y += heights[r]
                }
            }
        }
    }
}

private val MAX_CELL = 220.dp

/** Monospace with its own line height: the inherited body style's 24sp would double-space code. */
val CodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)

/** Inline code is tappable: one tap copies it (a command to paste into Termux, a path, a name). */
@Composable
private fun annotated(spans: List<Markdown.Span>): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    return remember(spans, colors, ctx) {
        buildAnnotatedString {
            spans.forEach { s ->
                val style = SpanStyle(
                    fontWeight = if (s.bold) FontWeight.SemiBold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (s.code) FontFamily.Monospace else null,
                    background = if (s.code) colors.surfaceContainerHighest else Color.Unspecified,
                )
                val link = s.link
                if (link != null && (link.startsWith("http://") || link.startsWith("https://"))) {
                    withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)))) {
                        withStyle(style) { append(s.text) }
                    }
                } else if (s.code && link == null) {
                    // Styles given (empty): without them it would be drawn as a link (underlined, in the accent color).
                    withLink(LinkAnnotation.Clickable("code", TextLinkStyles(SpanStyle()), linkInteractionListener = { copyText(ctx, s.text) })) {
                        withStyle(style) { append(s.text) }
                    }
                } else withStyle(style) { append(s.text) }
            }
        }
    }
}
