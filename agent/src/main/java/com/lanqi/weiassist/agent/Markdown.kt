package com.lanqi.weiassist.agent

/**
 * The Markdown that models write in answers, parsed for display: headings, paragraphs, lists, tables,
 * code blocks, quotes, rules; inline bold, italic, strike, code and links. Not full CommonMark: it is
 * forgiving instead (an unclosed marker stays as text), so a half-streamed answer still shows sensibly.
 * Pure Kotlin, so it is unit-tested on the JVM; the app draws the result (ui/Markdown.kt).
 */
object Markdown {
    sealed interface Block
    data class Heading(val level: Int, val text: List<Span>) : Block
    data class Paragraph(val text: List<Span>) : Block
    /** [marker] is "•" or the item's number ("1."); [depth] counts indentation levels. */
    data class Item(val marker: String, val depth: Int, val text: List<Span>)
    data class ListBlock(val items: List<Item>) : Block
    data class Code(val text: String) : Block
    data class Quote(val blocks: List<Block>) : Block
    enum class Align { Start, Center, End }
    data class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>, val align: List<Align>) : Block
    data object Rule : Block

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
    )

    // ---- blocks ------------------------------------------------------------------------------------

    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val fence = Regex("^\\s{0,3}(```|~~~)")
    private val tableDivider = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<Block> = blocks(text.replace("\r\n", "\n").split("\n"))

    private fun blocks(lines: List<String>): List<Block> {
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) out += Paragraph(inline(para.joinToString("\n") { it.trim() }))
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> { flush(); i++ }
                fence.containsMatchIn(line) -> {
                    flush()
                    val mark = fence.find(line)!!.groupValues[1]
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(mark)) code += lines[i++]
                    i++ // the closing fence (or past the end while streaming)
                    out += Code(code.joinToString("\n"))
                }
                heading.matches(line) -> {
                    flush()
                    val m = heading.find(line)!!
                    out += Heading(m.groupValues[1].length, inline(m.groupValues[2]))
                    i++
                }
                rule.matches(line) -> { flush(); out += Rule; i++ }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) quoted += lines[i++].trimStart().removePrefix(">").removePrefix(" ")
                    out += Quote(blocks(quoted))
                }
                isTableRow(line) && i + 1 < lines.size && tableDivider.matches(lines[i + 1]) && lines[i + 1].contains('-') -> {
                    flush()
                    val header = cells(line)
                    val align = cells(lines[i + 1]).map {
                        val l = it.startsWith(":"); val r = it.endsWith(":")
                        if (l && r) Align.Center else if (r) Align.End else Align.Start
                    }
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && isTableRow(lines[i])) rows += cells(lines[i++])
                    val n = header.size
                    fun fit(r: List<String>) = (r + List((n - r.size).coerceAtLeast(0)) { "" }).take(n).map(::inline)
                    out += Table(fit(header), rows.map(::fit), (align + List(n) { Align.Start }).take(n))
                }
                bullet.matches(line) || numbered.matches(line) -> {
                    flush()
                    val items = mutableListOf<Item>()
                    while (i < lines.size) {
                        val l = lines[i]
                        val b = bullet.find(l); val n = numbered.find(l)
                        val m = b ?: n
                        if (m != null && !rule.matches(l)) {
                            val depth = m.groupValues[1].replace("\t", "    ").length / 2
                            items += Item(if (b != null) "•" else "${n!!.groupValues[2]}.", depth, inline(m.groupValues[3]))
                            i++
                        } else if (l.isNotBlank() && l.startsWith(" ") && items.isNotEmpty()) {
                            // A continuation line of the item above.
                            val last = items.removeAt(items.lastIndex)
                            items += last.copy(text = last.text + Span(" ") + inline(l.trim()))
                            i++
                        } else break
                    }
                    out += ListBlock(items)
                }
                else -> { para += line; i++ }
            }
        }
        flush()
        return out
    }

    private fun isTableRow(line: String) = line.trim().let { it.startsWith("|") && it.length > 1 }

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        // Split on | that is not escaped.
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < t.length) {
            val c = t[k]
            if (c == '\\' && k + 1 < t.length && t[k + 1] == '|') { cur.append('|'); k += 2; continue }
            if (c == '|') { out += cur.toString().trim(); cur.clear() } else cur.append(c)
            k++
        }
        out += cur.toString().trim()
        return out
    }

    // ---- inline ------------------------------------------------------------------------------------

    private val autolink = Regex("https?://[^\\s<>()\\[\\]]*[^\\s<>()\\[\\].,;:!?。，；：！？）」』'\"]")

    fun inline(text: String): List<Span> = mutableListOf<Span>().also { inline(text, Span(""), it) }.let(::merge)

    private fun inline(s: String, style: Span, out: MutableList<Span>) {
        val plain = StringBuilder()
        fun emit() { if (plain.isNotEmpty()) { out += style.copy(text = plain.toString()); plain.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // Escapes: \* \_ \` \| …
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!|~>") { plain.append(s[i + 1]); i += 2; continue }
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) { emit(); out += style.copy(text = s.substring(i + 1, end), code = true); i = end + 1; continue }
            }
            if (s.startsWith("**", i) || s.startsWith("__", i)) {
                val mark = s.substring(i, i + 2)
                val end = s.indexOf(mark, i + 2)
                if (end > i + 2 && !s[i + 2].isWhitespace()) { emit(); inline(s.substring(i + 2, end), style.copy(bold = true), out); i = end + 2; continue }
            }
            if (s.startsWith("~~", i)) {
                val end = s.indexOf("~~", i + 2)
                if (end > i + 2) { emit(); inline(s.substring(i + 2, end), style.copy(strike = true), out); i = end + 2; continue }
            }
            // *italic* (not _italic_: snake_case names are common in answers).
            if (c == '*' && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != '*') {
                val end = s.indexOf('*', i + 1)
                if (end > i + 1 && !s[end - 1].isWhitespace()) { emit(); inline(s.substring(i + 1, end), style.copy(italic = true), out); i = end + 1; continue }
            }
            if (c == '[') {
                val close = s.indexOf("](", i + 1)
                val end = if (close > 0) s.indexOf(')', close + 2) else -1
                if (close > i && end > close + 2 && !s.substring(i + 1, close).contains('\n')) {
                    emit()
                    val url = s.substring(close + 2, end).trim().substringBefore(' ')
                    inline(s.substring(i + 1, close), style.copy(link = url), out)
                    i = end + 1; continue
                }
            }
            if (style.link == null && (c == 'h') && (s.startsWith("http://", i) || s.startsWith("https://", i))) {
                val m = autolink.matchAt(s, i)
                if (m != null) { emit(); out += style.copy(text = m.value, link = m.value); i += m.value.length; continue }
            }
            plain.append(c); i++
        }
        emit()
    }

    /** Neighbouring spans with the same style become one. */
    private fun merge(spans: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == s.copy(text = "")) out[out.lastIndex] = last.copy(text = last.text + s.text) else out += s
        }
        return out
    }

    // ---- plain text --------------------------------------------------------------------------------

    /** The text without Markdown, for one-line previews (session cards, notifications). */
    fun plain(text: String): String = parse(text).joinToString("\n") { plainBlock(it) }

    private fun plainBlock(b: Block): String = when (b) {
        is Heading -> spans(b.text)
        is Paragraph -> spans(b.text)
        is ListBlock -> b.items.joinToString("\n") { "${it.marker} ${spans(it.text)}" }
        is Code -> b.text
        is Quote -> b.blocks.joinToString("\n") { plainBlock(it) }
        is Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString(" · ") { spans(it) } }
        Rule -> ""
    }

    private fun spans(s: List<Span>) = s.joinToString("") { it.text }
}
