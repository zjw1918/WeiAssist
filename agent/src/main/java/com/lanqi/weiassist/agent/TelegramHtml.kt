package com.lanqi.weiassist.agent

import com.lanqi.weiassist.channels.Rich

/**
 * An answer's Markdown as Telegram messages: Telegram's HTML subset (b, i, s, code, pre, a, blockquote),
 * cut into messages under Telegram's 4096-character limit at block boundaries (a long code block or
 * paragraph is cut by lines). Each message also comes as plain text, sent if Telegram refuses the markup.
 * Pure, so it is unit-tested (TelegramHtmlTest).
 */
object TelegramHtml {
    /** Under Telegram's 4096 (counted after the markup is parsed), with room to spare. */
    const val LIMIT = 3800

    fun render(markdown: String, limit: Int = LIMIT): List<Rich> {
        val parts = Markdown.parse(markdown.trim()).flatMap { piece(it, limit) }
        val out = mutableListOf<Rich>()
        val html = StringBuilder()
        val plain = StringBuilder()
        for (p in parts) {
            if (html.isNotEmpty() && html.length + 2 + p.markup.length > limit) {
                out += Rich(html.toString(), plain.toString()); html.clear(); plain.clear()
            }
            if (html.isNotEmpty()) { html.append("\n\n"); plain.append("\n\n") }
            html.append(p.markup); plain.append(p.plain)
        }
        if (html.isNotEmpty()) out += Rich(html.toString(), plain.toString())
        return out
    }

    /** One block as one or more pieces, each within [limit]. */
    private fun piece(b: Markdown.Block, limit: Int): List<Rich> {
        if (b is Markdown.Code) return lines(b.text, limit - 11).map { Rich("<pre>${esc(it)}</pre>", it) }
        val r = block(b)
        if (r.markup.length <= limit) return listOf(r)
        // Too long to keep its formatting in one message: as plain text, cut by lines.
        return lines(r.plain, limit / 2).map { Rich(esc(it), it) }
    }

    private fun block(b: Markdown.Block): Rich = when (b) {
        is Markdown.Heading -> Rich("<b>${spans(b.text)}</b>", plain(b.text))
        is Markdown.Paragraph -> Rich(spans(b.text), plain(b.text))
        is Markdown.ListBlock -> {
            val html = b.items.joinToString("\n") { item ->
                val head = "  ".repeat(item.depth) + item.marker + " " + spans(item.text)
                (listOf(head) + item.code.map { "<pre>${esc(it.text)}</pre>" }).joinToString("\n")
            }
            val plain = b.items.joinToString("\n") { item ->
                (listOf("  ".repeat(item.depth) + item.marker + " " + plain(item.text)) + item.code.map { it.text }).joinToString("\n")
            }
            Rich(html, plain)
        }
        is Markdown.Code -> Rich("<pre>${esc(b.text)}</pre>", b.text)
        is Markdown.Quote -> b.blocks.map(::block).let { inner ->
            Rich("<blockquote>${inner.joinToString("\n\n") { it.markup }}</blockquote>", inner.joinToString("\n\n") { "> " + it.plain })
        }
        is Markdown.Table -> table(b).let { Rich("<pre>${esc(it)}</pre>", it) }
        Markdown.Rule -> Rich("──────", "──────")
    }

    /** Telegram has no tables: columns padded in a monospace block. */
    private fun table(t: Markdown.Table): String {
        val rows = listOf(t.header.map(::plain)) + t.rows.map { r -> r.map(::plain) }
        val n = rows.maxOf { it.size }
        val widths = (0 until n).map { c -> rows.maxOf { it.getOrNull(c)?.length ?: 0 } }
        fun line(r: List<String>) = (0 until n).joinToString(" │ ") { c ->
            val s = r.getOrNull(c).orEmpty()
            if (t.align.getOrNull(c) == Markdown.Align.End) s.padStart(widths[c]) else s.padEnd(widths[c])
        }.trimEnd()
        return (listOf(line(rows[0]), widths.joinToString("─┼─") { "─".repeat(it) }) + rows.drop(1).map(::line)).joinToString("\n")
    }

    private fun spans(s: List<Markdown.Span>) = s.joinToString("") { span ->
        var h = esc(span.text)
        if (span.code) h = "<code>$h</code>"
        if (span.strike) h = "<s>$h</s>"
        if (span.italic) h = "<i>$h</i>"
        if (span.bold) h = "<b>$h</b>"
        val link = span.link?.trim()
        if (link != null && SAFE_LINK.containsMatchIn(link)) h = "<a href=\"${esc(link)}\">$h</a>"
        h
    }

    private fun plain(s: List<Markdown.Span>) = s.joinToString("") { span ->
        val link = span.link?.trim()
        if (link != null && link != span.text) "${span.text} ($link)" else span.text
    }

    /** [text] cut into pieces of at most [max] characters, at line ends where it can be. */
    private fun lines(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (line in text.split("\n")) {
            var l = line
            while (l.length > max) { // a single line longer than a message
                if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                out += l.substring(0, max); l = l.substring(max)
            }
            if (cur.isNotEmpty() && cur.length + 1 + l.length > max) { out += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(l)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val SAFE_LINK = Regex("^(https?://|tg://|mailto:)", RegexOption.IGNORE_CASE)
}
