package com.lanqi.weiassist.agent

import com.lanqi.weiassist.agent.Markdown.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun boldAroundChinese() {
        assertEquals(
            listOf(Span("杭州："), Span("雨天较多", bold = true), Span("。")),
            Markdown.inline("杭州：**雨天较多**。"),
        )
    }

    @Test fun codeLinksAndItalic() {
        assertEquals(
            listOf(Span("run "), Span("go test", code = true), Span(" see "), Span("docs", link = "https://go.dev"), Span(" "), Span("now", italic = true)),
            Markdown.inline("run `go test` see [docs](https://go.dev) *now*"),
        )
    }

    @Test fun inlineTripleBacktickCodeDropsShellTag() {
        assertEquals(
            listOf(Span("运行： "), Span("mkdir -p ~/.termux && termux-reload-settings", code = true)),
            Markdown.inline("运行： ```sh mkdir -p ~/.termux && termux-reload-settings ```"),
        )
    }

    @Test fun doubleBacktickCodeHoldsABacktick() {
        assertEquals(listOf(Span("say "), Span("a`b", code = true)), Markdown.inline("say `` a`b ``"))
    }

    @Test fun backtickRunsOfOtherLengthsDontClose() {
        assertEquals(listOf(Span("```x` y")), Markdown.inline("```x` y"))
    }

    @Test fun codeBlockIndentedUnderAListItem() {
        val md = "1. 打开 Termux，运行：\n   ```sh\n   mkdir -p ~/.termux && termux-reload-settings\n   ```\n2. 允许权限。"
        val list = Markdown.parse(md).single() as Markdown.ListBlock
        assertEquals(listOf("1.", "2."), list.items.map { it.marker })
        assertEquals(listOf(Markdown.Code("mkdir -p ~/.termux && termux-reload-settings")), list.items[0].code)
        assertEquals(listOf(Span("打开 Termux，运行：")), list.items[0].text)
        assertEquals(listOf(Span("允许权限。")), list.items[1].text)
    }

    @Test fun autolinkStopsAtChinesePunctuation() {
        assertEquals(
            listOf(Span("来源："), Span("https://open-meteo.com/", link = "https://open-meteo.com/"), Span("。")),
            Markdown.inline("来源：https://open-meteo.com/。"),
        )
    }

    @Test fun unclosedMarkersStayText() {
        // Half-streamed answers.
        assertEquals(listOf(Span("**雨天较")), Markdown.inline("**雨天较"))
        assertEquals(listOf(Span("a * b and snake_case_name")), Markdown.inline("a * b and snake_case_name"))
    }

    @Test fun weatherTable() {
        val md = """
            杭州未来一周**雨天较多**。

            | 日期 | 天气 | 气温 | 降水概率 |
            |---|---|---:|---:|
            | 周一 11 月 2 日 | 小雨 | 13～21°C | 100% |
            | 周二 | 阵雨 | 13～19°C |
        """.trimIndent()
        val blocks = Markdown.parse(md)
        assertEquals(2, blocks.size)
        val t = blocks[1] as Markdown.Table
        assertEquals(listOf("日期", "天气", "气温", "降水概率"), t.header.map { it.single().text })
        assertEquals(listOf(Markdown.Align.Start, Markdown.Align.Start, Markdown.Align.End, Markdown.Align.End), t.align)
        assertEquals(2, t.rows.size)
        assertEquals(4, t.rows[1].size) // a short row is padded
        assertTrue(t.rows[1][3].isEmpty())
    }

    @Test fun headingsListsCodeQuoteRule() {
        val md = """
            ## Plan
            1. build
            2. test **all**
              - unit
            ---
            ```go
            func main() {}
            ```
            > note
        """.trimIndent()
        val b = Markdown.parse(md)
        assertEquals(Markdown.Heading(2, listOf(Span("Plan"))), b[0])
        val list = b[1] as Markdown.ListBlock
        assertEquals(listOf("1.", "2.", "•"), list.items.map { it.marker })
        assertEquals(listOf(0, 0, 1), list.items.map { it.depth })
        assertEquals(Markdown.Rule, b[2])
        assertEquals(Markdown.Code("func main() {}"), b[3])
        assertEquals(Markdown.Quote(listOf(Markdown.Paragraph(listOf(Span("note"))))), b[4])
    }

    @Test fun unclosedFenceWhileStreaming() {
        assertEquals(listOf(Markdown.Code("ls -la")), Markdown.parse("```\nls -la"))
    }

    @Test fun plainForPreviews() {
        assertEquals(
            "杭州雨天较多\n日期 · 天气\n周一 · 小雨\n• 带伞",
            Markdown.plain("# 杭州**雨天较多**\n\n| 日期 | 天气 |\n|---|---|\n| 周一 | 小雨 |\n\n- 带伞"),
        )
    }
}
