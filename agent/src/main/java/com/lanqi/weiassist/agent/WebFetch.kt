package com.lanqi.weiassist.agent

import com.lanqi.weiassist.core.Http
import com.lanqi.weiassist.core.await
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * The web_fetch tool: a page from the public web as text for the model, with no Termux. HTML becomes readable text
 * (headings, lists, links, code and tables kept, scripts and page furniture dropped); plain text and JSON come as
 * they are; other types are only described.
 *
 * Only the public internet: the model chooses the address, and a page may ask it to fetch something, so addresses
 * on the phone itself (the app's own servers, Termux's) and on the local network are refused, on every redirect too.
 */
object WebFetch {
    const val DEFAULT_CHARS = 20_000
    const val MAX_CHARS = 100_000
    /** Read at most this much of a body: a page's text is far smaller, and a huge download is not a page. */
    private const val MAX_BYTES = 5L * 1024 * 1024

    class Blocked(message: String) : IOException(message)

    /** The model's arguments: url, and optionally start and max_chars to read a long page in parts. */
    suspend fun run(args: org.json.JSONObject): Result {
        val raw = args.optString("url").trim()
        val url = raw.toHttpUrlOrNull() ?: ("https://$raw".takeIf { "://" !in raw }?.toHttpUrlOrNull())
            ?: return Result("Not a web address: $raw. Give a full http(s) URL.", isError = true)
        val start = args.optInt("start", 0).coerceAtLeast(0)
        val max = args.optInt("max_chars", DEFAULT_CHARS).coerceIn(1_000, MAX_CHARS)
        return try {
            fetch(url.toString(), start, max)
        } catch (e: Blocked) {
            Result(e.message ?: "blocked", isError = true)
        } catch (e: IOException) {
            Result("Could not fetch $url: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    data class Result(val text: String, val isError: Boolean = false)

    private suspend fun fetch(url: String, start: Int, max: Int): Result {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8")
            .build()
        return client.newCall(request).await { r ->
            val type = r.header("Content-Type").orEmpty()
            val mime = type.substringBefore(';').trim().lowercase()
            val head = buildString {
                append("URL: ").append(r.request.url).append('\n')
                append("Status: ").append(r.code).append(if (r.message.isNotEmpty()) " ${r.message}" else "").append('\n')
                if (mime.isNotEmpty()) append("Content-Type: ").append(mime).append('\n')
            }
            val source = r.body.source()
            source.request(MAX_BYTES + 1)
            val truncatedBytes = source.buffer.size > MAX_BYTES
            val bytes = Buffer().also { it.write(source.buffer, minOf(source.buffer.size, MAX_BYTES)) }.readByteArray()
            val charset = r.body.contentType()?.charset()
            val (title, text) = when {
                // Without a charset in the header, jsoup reads the page's own <meta charset> (else UTF-8).
                isHtml(mime, bytes) -> htmlToText(Jsoup.parse(bytes.inputStream(), charset?.name(), r.request.url.toString()))
                isText(mime) -> null to String(bytes, charset ?: Charsets.UTF_8)
                else -> return@await Result(head + "\nThis is not a page of text (${mime.ifEmpty { "unknown type" }}, " +
                    "${r.body.contentLength().takeIf { it >= 0 } ?: bytes.size} bytes), so it can't be shown here.", isError = r.code >= 400)
            }
            Result(head + (title?.let { "Title: $it\n" } ?: "") + "\n" + window(text.trim(), start, max, truncatedBytes), isError = r.code >= 400)
        }
    }

    /** [text] from [start], at most [max] characters, saying how to read on. */
    fun window(text: String, start: Int, max: Int, truncatedBytes: Boolean = false): String {
        if (start >= text.length && text.isNotEmpty()) return "(The text is ${text.length} characters; start $start is past its end.)"
        val end = minOf(text.length, start + max)
        return buildString {
            if (start > 0) append("(characters $start–$end of ${text.length})\n")
            append(text, start, end)
            if (end < text.length) append("\n\n(cut: ${text.length - end} more characters; call web_fetch again with start=$end to read on)")
            else if (truncatedBytes) append("\n\n(the page is longer than ${MAX_BYTES / 1024 / 1024} MB; only its start was read)")
        }
    }

    private fun isHtml(mime: String, bytes: ByteArray) = mime == "text/html" || mime == "application/xhtml+xml" ||
        (mime.isEmpty() && String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1).trimStart().let { it.startsWith("<!doctype html", true) || it.startsWith("<html", true) })

    private fun isText(mime: String) = mime.isEmpty() || mime.startsWith("text/") || mime.endsWith("+json") || mime.endsWith("+xml") ||
        mime in setOf("application/json", "application/xml", "application/javascript", "application/x-javascript", "application/x-yaml", "application/yaml", "application/x-ndjson")

    /** A page's title and its readable text: the main content when the page marks it, links as [text](url). */
    fun htmlToText(html: String, baseUrl: String): Pair<String?, String> = htmlToText(Jsoup.parse(html, baseUrl))

    private fun htmlToText(doc: org.jsoup.nodes.Document): Pair<String?, String> {
        val title = doc.title().trim().ifEmpty { null }
        doc.select("script, style, noscript, template, svg, canvas, iframe, form, button, select, input, [hidden], [aria-hidden=true]").remove()
        val root = doc.selectFirst("main, article, [role=main]") ?: doc.body() ?: return title to ""
        root.select("nav, header, footer, aside").remove()
        val out = StringBuilder()
        render(root, out)
        val text = out.toString()
            .replace(Regex("[ \\t\\u00A0]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
        return title to text
    }

    private fun render(node: Node, out: StringBuilder) {
        when (node) {
            is TextNode -> {
                val t = node.text().replace(Regex("\\s+"), " ")
                if (t.isNotBlank() || (t.isNotEmpty() && out.isNotEmpty() && !out.last().isWhitespace())) out.append(t)
            }
            is Element -> {
                val tag = node.normalName()
                when (tag) {
                    "br" -> out.append('\n')
                    "hr" -> block(out).append("---\n")
                    "h1", "h2", "h3", "h4", "h5", "h6" -> { block(out).append("#".repeat(tag[1] - '0')).append(' '); children(node, out); out.append("\n\n") }
                    "pre" -> { block(out).append("```\n").append(node.wholeText().trimEnd()).append("\n```\n\n") }
                    "code" -> out.append('`').append(node.text()).append('`')
                    "li" -> { line(out).append(if (node.parent()?.normalName() == "ol") "${node.elementSiblingIndex() + 1}. " else "- "); children(node, out); out.append('\n') }
                    "a" -> {
                        val href = node.absUrl("href")
                        val start = out.length
                        children(node, out)
                        val label = out.substring(start).trim()
                        if (href.startsWith("http") && label.isNotEmpty() && label != href) { out.setLength(start); out.append('[').append(label).append("](").append(href).append(')') }
                    }
                    "img" -> node.attr("alt").takeIf { it.isNotBlank() }?.let { out.append("[image: ").append(it.trim()).append(']') }
                    "tr" -> { line(out).append("| "); node.children().forEach { c -> out.append(c.text().trim()).append(" | ") }; out.append('\n') }
                    "table" -> { block(out); children(node, out); out.append('\n') }
                    "p", "div", "section", "ul", "ol", "dl", "dt", "dd", "blockquote", "figure", "figcaption", "details", "summary" -> { block(out); children(node, out); block(out) }
                    else -> children(node, out)
                }
            }
        }
    }

    private fun children(e: Element, out: StringBuilder) = e.childNodes().forEach { render(it, out) }
    /** Starts a new line, unless at one already. */
    private fun line(out: StringBuilder) = out.apply { if (isNotEmpty() && last() != '\n') append('\n') }
    /** Starts a new paragraph. */
    private fun block(out: StringBuilder) = out.apply { line(this); if (length >= 2 && !endsWith("\n\n")) append('\n') }

    /** Whether [a] is on the public internet: not this phone, not the local network, not a reserved range. */
    fun isPublic(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        return when (a) {
            is Inet4Address -> {
                val o0 = b[0].toInt() and 0xFF; val o1 = b[1].toInt() and 0xFF
                !(o0 == 0 || o0 == 100 && o1 in 64..127 || o0 == 192 && o1 == 0 && (b[2].toInt() and 0xFF) == 0 ||
                    o0 == 198 && o1 in 18..19 || o0 >= 240)
            }
            is Inet6Address -> {
                val o0 = b[0].toInt() and 0xFF
                when {
                    o0 and 0xFE == 0xFC -> false // unique local fc00::/7
                    // IPv4 inside IPv6 (::ffff:a.b.c.d, 64:ff9b::a.b.c.d): judge the IPv4 address.
                    b.sliceArray(0 until 10).all { it.toInt() == 0 } && (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF ||
                        o0 == 0x00 && (b[1].toInt() and 0xFF) == 0x64 && (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B ->
                        isPublic(InetAddress.getByAddress(b.sliceArray(12 until 16)))
                    else -> true
                }
            }
            else -> false
        }
    }

    private fun blocked(host: String) = Blocked("Refused: $host is on this phone or its local network. web_fetch only reads the public internet.")

    /** Names resolve only to public addresses: the connection never reaches this phone or the local network. */
    private val publicDns = Dns { host ->
        val all = Dns.SYSTEM.lookup(host)
        all.filter(::isPublic).ifEmpty { throw blocked(host) }
    }

    /**
     * Every connection, redirects and IP-address URLs (which skip [publicDns]) included: one to an address that isn't
     * public fails before anything is sent, TLS hello included.
     */
    private val guardedSockets = object : SocketFactory() {
        private val plain = SocketFactory.getDefault()
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val a = (endpoint as? InetSocketAddress)?.address
                if (a == null || !isPublic(a)) throw blocked((endpoint as? InetSocketAddress)?.hostString ?: endpoint.toString())
                super.connect(endpoint, timeout)
            }
        }
        override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = plain.createSocket(host, port, local, localPort).also { check(it) }
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = plain.createSocket(address, port, local, localPort).also { check(it) }
        private fun check(s: Socket) { if (!isPublic(s.inetAddress)) { s.close(); throw blocked(s.inetAddress.hostAddress ?: "?") } }
    }

    private val client: OkHttpClient by lazy {
        Http.with(connectMs = 10_000, readMs = 20_000, callMs = 45_000).newBuilder()
            .dns(publicDns)
            .socketFactory(guardedSockets)
            // Straight to the site: through a proxy, the proxy would choose where the connection goes.
            .proxy(Proxy.NO_PROXY)
            .build()
    }

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36 WeiAssist"
}
