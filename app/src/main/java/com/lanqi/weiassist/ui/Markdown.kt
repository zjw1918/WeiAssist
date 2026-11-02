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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
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
import com.lanqi.weiassist.agent.Markdown

/** An answer's Markdown (parsed by agent's Markdown), drawn with the app's theme. Text can be selected and copied. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.parse(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { Block(it) }
        }
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
                    Text(annotated(item.text), style = type.bodyMedium)
                }
            }
        }
        is Markdown.Code -> Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState()).padding(8.dp),
        ) {
            Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
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
    val headerBg = MaterialTheme.colorScheme.surface
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

@Composable
private fun annotated(spans: List<Markdown.Span>): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(spans, colors) {
        buildAnnotatedString {
            spans.forEach { s ->
                val style = SpanStyle(
                    fontWeight = if (s.bold) FontWeight.SemiBold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (s.code) FontFamily.Monospace else null,
                    background = if (s.code) colors.surface else Color.Unspecified,
                )
                val link = s.link
                if (link != null && (link.startsWith("http://") || link.startsWith("https://"))) {
                    withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)))) {
                        withStyle(style) { append(s.text) }
                    }
                } else withStyle(style) { append(s.text) }
            }
        }
    }
}
