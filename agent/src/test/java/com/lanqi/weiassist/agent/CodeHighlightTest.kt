package com.lanqi.weiassist.agent

import com.lanqi.weiassist.agent.CodeHighlight.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightTest {
    /** Each coloured piece of [code] with its kind, in order. */
    private fun pieces(code: String, lang: String) = CodeHighlight.tokens(code, lang).map { code.substring(it.start, it.end) to it.kind }

    @Test fun go() {
        val p = pieces("func main() {\n\tport := os.Getenv(\"PORT\") // env\n\tvar n int = 0x1F\n}", "go")
        assertEquals(
            listOf("func" to Kind.Keyword, "main" to Kind.Function, "Getenv" to Kind.Function, "\"PORT\"" to Kind.Str, "// env" to Kind.Comment,
                "var" to Kind.Keyword, "int" to Kind.Type, "0x1F" to Kind.Number),
            p,
        )
    }

    @Test fun `go raw strings span lines`() {
        assertEquals(listOf("`a\nb`" to Kind.Str), pieces("`a\nb`", "go"))
    }

    @Test fun `json keys differ from values`() {
        assertEquals(
            listOf("\"city\"" to Kind.Key, "\"Boston\"" to Kind.Str, "\"t\"" to Kind.Key, "-71.05" to Kind.Number, "true" to Kind.Keyword).filter { it.first != "-71.05" },
            pieces("{\"city\": \"Boston\", \"t\" : -71.05, \"ok\": true}", "json").filter { it.second != Kind.Number && it.first != "\"ok\"" },
        )
        assertTrue(pieces("{\"a\": 42.5}", "json").contains("42.5" to Kind.Number))
    }

    @Test fun `shell commands, flags, variables and comments`() {
        val p = pieces("cd /root && go run . # start\nPORT=9000 go build -o server \$HOME", "bash")
        assertEquals(
            listOf("cd" to Kind.Function, "go" to Kind.Function, "# start" to Kind.Comment, "PORT" to Kind.Type,
                "go" to Kind.Function, "-o" to Kind.Meta, "\$HOME" to Kind.Type),
            p,
        )
    }

    @Test fun `a hash inside a word is not a comment`() {
        assertEquals(emptyList<Pair<String, Kind>>(), pieces("echo a#b", "sh").filter { it.second == Kind.Comment })
    }

    @Test fun `python triple quotes and decorators`() {
        val p = pieces("@app.get\ndef f():\n    \"\"\"doc\nmore\"\"\"\n    return None", "python")
        assertEquals(listOf("@app" to Kind.Meta, "def" to Kind.Keyword, "f" to Kind.Function, "\"\"\"doc\nmore\"\"\"" to Kind.Str,
            "return" to Kind.Keyword, "None" to Kind.Keyword), p)
    }

    @Test fun `yaml keys, also in lists`() {
        val p = pieces("server:\n  port: 8080\n  - name: web # x", "yaml")
        assertEquals(listOf("server" to Kind.Key, "port" to Kind.Key, "8080" to Kind.Number, "name" to Kind.Key, "# x" to Kind.Comment), p)
    }

    @Test fun `numbers in names are not numbers`() {
        assertEquals(emptyList<Pair<String, Kind>>(), pieces("a3b35ab4 v1", "go").filter { it.second == Kind.Number })
    }

    @Test fun `diff lines`() {
        assertEquals(listOf("@@ -1 +1 @@" to Kind.Meta, "-a" to Kind.Removed, "+b" to Kind.Added), pieces("@@ -1 +1 @@\n-a\n+b\n c", "diff"))
    }

    @Test fun `unknown languages get no colours`() {
        assertEquals(emptyList<CodeHighlight.Token>(), CodeHighlight.tokens("GET / HTTP/1.1", "http"))
        assertEquals(emptyList<CodeHighlight.Token>(), CodeHighlight.tokens("x", ""))
    }

    @Test fun `languages of files`() {
        assertEquals("go", CodeHighlight.langOfPath("/root/sessions/a/main.go"))
        assertEquals("dockerfile", CodeHighlight.langOfPath("Dockerfile"))
        assertEquals("", CodeHighlight.langOfPath("go.mod"))
        assertEquals("tsx", CodeHighlight.langOfPath("App.TSX"))
    }

    @Test fun `an unclosed string or comment while streaming runs to the end`() {
        assertEquals(listOf("\"abc" to Kind.Str), pieces("\"abc", "js"))
        assertEquals(listOf("/* abc" to Kind.Comment), pieces("/* abc", "js"))
    }
}
