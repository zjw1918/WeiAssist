package com.lanqi.weiassist.ui

import com.lanqi.weiassist.agent.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StreamingMarkdownTest {
    private val answer = """
        # Plan

        First paragraph with **bold** and `code`.
        Still the first paragraph.

        - one
        - two
          continued

        1. first
        2. second

        ```kotlin
        val a = 1

        val b = 2
        ```

        | a | b |
        |---|:-:|
        | 1 | 2 |

        > quoted

        > again
        ~~~
        open fence never closed

        with a blank line inside
    """.trimIndent()

    @Test fun everyPrefixParsesLikeTheWholeText() {
        val stream = StreamingMarkdown()
        for (n in 1..answer.length) {
            val text = answer.substring(0, n)
            assertEquals("prefix of $n chars", Markdown.parse(text), stream.blocks(text))
        }
    }

    @Test fun settledBlocksKeepTheirInstances() {
        val stream = StreamingMarkdown()
        val first = stream.blocks("Para one.\n\nPara tw")
        val second = stream.blocks("Para one.\n\nPara two, longer")
        assertSame(first[0], second[0])
    }

    @Test fun aNewAnswerStartsOver() {
        val stream = StreamingMarkdown()
        stream.blocks("Old answer.\n\nMore")
        assertEquals(Markdown.parse("New"), stream.blocks("New"))
    }
}
