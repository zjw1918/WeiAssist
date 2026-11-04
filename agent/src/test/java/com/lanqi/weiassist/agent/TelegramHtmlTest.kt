package com.lanqi.weiassist.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramHtmlTest {
    private fun one(md: String) = TelegramHtml.render(md).single()

    @Test fun inlineStylesAndEscaping() {
        val r = one("**Bold** and *it* and `a<b>&c` ~~gone~~")
        assertEquals("<b>Bold</b> and <i>it</i> and <code>a&lt;b&gt;&amp;c</code> <s>gone</s>", r.markup)
        assertEquals("Bold and it and a<b>&c gone", r.plain)
    }

    @Test fun safeLinksOnly() {
        assertEquals("<a href=\"https://x.org/?a=1&amp;b=2\">site</a>", one("[site](https://x.org/?a=1&b=2)").markup)
        assertEquals("site", one("[site](javascript:void)").markup)
    }

    @Test fun headingsListsCodeQuotes() {
        val r = one("# Title\n\n- one\n- two\n\n```\nx < 1\n```\n\n> quoted")
        assertEquals("<b>Title</b>\n\n• one\n• two\n\n<pre>x &lt; 1</pre>\n\n<blockquote>quoted</blockquote>", r.markup)
    }

    @Test fun tablesAsMonospace() {
        val r = one("| a | bb |\n|---|---:|\n| ccc | 1 |")
        assertTrue(r.markup.startsWith("<pre>"))
        assertTrue(r.plain, r.plain.contains("ccc │  1"))
    }

    @Test fun longAnswersAreSplitUnderTheLimit() {
        val md = (1..60).joinToString("\n\n") { "Paragraph $it " + "word ".repeat(30) }
        val parts = TelegramHtml.render(md, limit = 1000)
        assertTrue(parts.size > 5)
        parts.forEach { assertTrue(it.markup.length <= 1000) }
        assertEquals(md.split("\n\n").size, parts.sumOf { it.plain.split("\n\n").size })
    }

    @Test fun longCodeBlockIsSplitIntoPreBlocks() {
        val code = (1..400).joinToString("\n") { "line $it" }
        val parts = TelegramHtml.render("```\n$code\n```", limit = 500)
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.markup.startsWith("<pre>") && it.markup.endsWith("</pre>")) }
        assertEquals(code, parts.joinToString("\n") { it.plain })
    }

    @Test fun emptyIsNothing() = assertEquals(0, TelegramHtml.render("  ").size)
}
