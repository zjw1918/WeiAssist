package com.lanqi.weiassist.agent

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class WebFetchTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun `only public addresses are public`() {
        for (a in listOf("8.8.8.8", "1.1.1.1", "104.16.0.1", "2606:4700::1111")) assertTrue(a, WebFetch.isPublic(ip(a)))
        for (a in listOf(
            "127.0.0.1", "127.8.9.10", "0.0.0.0", "10.0.0.1", "172.16.5.4", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "192.0.0.1", "198.18.0.1", "224.0.0.1", "255.255.255.255",
            "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:127.0.0.1", "::ffff:192.168.0.1", "64:ff9b::a00:1",
        )) assertFalse(a, WebFetch.isPublic(ip(a)))
    }

    @Test fun `the phone and its network are refused before anything is sent`() = runBlocking {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex -> hits.incrementAndGet(); ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("hi".toByteArray()) } }
            start()
        }
        try {
            val port = server.address.port
            for (url in listOf("http://127.0.0.1:$port/", "https://127.0.0.1:$port/", "http://localhost:$port/", "http://[::1]:$port/", "http://[::ffff:127.0.0.1]:$port/")) {
                val r = WebFetch.run(JSONObject().put("url", url))
                assertTrue("$url: ${r.text}", r.isError && "public internet" in r.text)
            }
            assertEquals("the local server never saw a request", 0, hits.get())
        } finally {
            server.stop(0)
        }
    }

    @Test fun `a bad address is an error for the model`() = runBlocking {
        assertTrue(WebFetch.run(JSONObject().put("url", "ftp://x.org/a")).isError)
        assertTrue(WebFetch.run(JSONObject().put("url", "")).isError)
    }

    @Test fun `html becomes readable text with headings, lists, links, code and tables`() {
        val html = """
            <html><head><title> Zig 0.15 release notes </title><script>var x = 1;</script><style>p{}</style></head>
            <body><nav>Home | Docs</nav>
            <main>
              <h1>Release notes</h1>
              <p>Zig has <b>new</b> async I/O. See <a href="/download/">downloads</a> or <a href="https://ziglang.org/learn">https://ziglang.org/learn</a>.</p>
              <ul><li>Threads</li><li>Atomics</li></ul>
              <ol><li>first</li><li>second</li></ol>
              <pre><code>const std = @import("std");
pub fn main() void {}</code></pre>
              <table><tr><th>Version</th><th>Date</th></tr><tr><td>0.15</td><td>2026</td></tr></table>
              <img src="a.png" alt="A chart">
              <button>Accept cookies</button>
            </main>
            <footer>© Zig</footer></body></html>
        """.trimIndent()
        val (title, text) = WebFetch.htmlToText(html, "https://ziglang.org/news/")
        assertEquals("Zig 0.15 release notes", title)
        assertTrue(text, text.startsWith("# Release notes\n\nZig has new async I/O. See [downloads](https://ziglang.org/download/) or https://ziglang.org/learn."))
        assertTrue(text, "- Threads\n- Atomics" in text)
        assertTrue(text, "1. first\n2. second" in text)
        assertTrue(text, "```\nconst std = @import(\"std\");\npub fn main() void {}\n```" in text)
        assertTrue(text, "| Version | Date |\n| 0.15 | 2026 |" in text)
        assertTrue(text, "[image: A chart]" in text)
        for (gone in listOf("var x", "Home | Docs", "Accept cookies", "© Zig")) assertFalse(gone, gone in text)
    }

    @Test fun `long text comes in parts that say how to read on`() {
        val text = "a".repeat(2_500)
        val first = WebFetch.window(text, 0, 1_000)
        assertTrue(first.startsWith("a".repeat(1_000) + "\n\n(cut: 1500 more characters; call web_fetch again with start=1000"))
        val last = WebFetch.window(text, 2_000, 1_000)
        assertTrue(last, last.startsWith("(characters 2000–2500 of 2500)\n"))
        assertFalse(last, "cut:" in last)
        assertTrue(WebFetch.window(text, 9_000, 1_000).contains("past its end"))
    }
}
