package com.lanqi.weiassist.core

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * OkHttp (as the app uses it: [Http]) next to the platform's HttpURLConnection, on a device, for the work the app's
 * HTTP does: small JSON requests, a model's token stream, a file download, an upload, and small https requests to a
 * real host. Each client runs every scenario in alternating rounds, so warm-up and drift hit both alike.
 *
 * Not part of the normal test run: it needs the bench server (tools/http-bench-server.mjs) on the computer:
 *   node tools/http-bench-server.mjs & adb reverse tcp:8790 tcp:8790
 *   ./gradlew :core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lanqi.weiassist.core.HttpClientBenchmark
 *   adb logcat -d -s HttpBench
 * Times are wall-clock; "cpu" is the calling thread's CPU time (both clients do HTTP/1.1 work on the calling thread).
 */
@RunWith(AndroidJUnit4::class)
class HttpClientBenchmark {
    private val base = "http://127.0.0.1:8790"
    private val https = "https://registry.npmjs.org/-/ping"

    private interface Client {
        val name: String
        /** GET [url]: the status, after reading the body in [buf]-sized reads; [reads] counts the reads that returned data. */
        fun get(url: String, buf: ByteArray, reads: IntArray? = null): Long
        fun post(url: String, body: ByteArray): Int
    }

    private object Platform : Client {
        override val name = "HttpURLConnection"
        override fun get(url: String, buf: ByteArray, reads: IntArray?): Long {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            var total = 0L
            (if (c.responseCode < 400) c.inputStream else c.errorStream)?.use { s ->
                while (true) { val n = s.read(buf); if (n < 0) break; total += n; reads?.let { it[0]++ } }
            }
            return total
        }
        override fun post(url: String, body: ByteArray): Int {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            c.requestMethod = "POST"; c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            c.inputStream.use { it.readBytes() }
            return code
        }
    }

    private object Ok : Client {
        override val name = "OkHttp"
        private val client: OkHttpClient by lazy { Http.with(connectMs = 10_000, readMs = 30_000) }
        override fun get(url: String, buf: ByteArray, reads: IntArray?): Long {
            var total = 0L
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                r.body.byteStream().use { s ->
                    while (true) { val n = s.read(buf); if (n < 0) break; total += n; reads?.let { it[0]++ } }
                }
            }
            return total
        }
        override fun post(url: String, body: ByteArray): Int =
            client.newCall(Request.Builder().url(url).post(body.toRequestBody("application/octet-stream".toMediaType())).build()).execute()
                .use { it.body.bytes(); it.code }
    }

    /** One measurement: wall ms and thread-CPU ms, summed over the rounds. */
    private class Tally { var wall = 0.0; var cpu = 0.0; var n = 0; var extra = 0L
        fun add(wallNs: Long, cpuNs: Long) { wall += wallNs / 1e6; cpu += cpuNs / 1e6; n++ } }

    private inline fun measure(t: Tally, block: () -> Unit) {
        val w0 = SystemClock.elapsedRealtimeNanos(); val c0 = Debug.threadCpuTimeNanos()
        block()
        t.add(SystemClock.elapsedRealtimeNanos() - w0, Debug.threadCpuTimeNanos() - c0)
    }

    @Test fun compare() {
        val clients = listOf(Ok, Platform)
        val results = clients.associateWith { mutableMapOf<String, Tally>() }
        fun tally(c: Client, k: String) = results.getValue(c).getOrPut(k) { Tally() }
        val buf = ByteArray(16 * 1024)
        val upload = ByteArray(8 * 1024 * 1024) { it.toByte() }

        // The first request of each, in a process where neither has run (OkHttp's classes load here).
        for (c in clients) measure(tally(c, "first request (ms)")) { c.get("$base/small", buf) }
        // Warm both before the rounds.
        repeat(20) { clients.forEach { c -> c.get("$base/small", buf) } }

        val rounds = 5
        repeat(rounds) { round ->
            for (c in if (round % 2 == 0) clients else clients.reversed()) {
                measure(tally(c, "200 small GETs (ms)")) { repeat(200) { c.get("$base/small", buf) } }
                val reads = IntArray(1)
                measure(tally(c, "SSE 3000 events (ms)")) { c.get("$base/stream?n=3000", buf, reads) }
                tally(c, "SSE 3000 events (ms)").extra += reads[0]
                measure(tally(c, "32 MiB download (ms)")) { check(c.get("$base/bulk?mb=32", buf) == 32L * 1024 * 1024) }
                measure(tally(c, "8 MiB upload (ms)")) { check(c.post("$base/upload", upload) == 200) }
                runCatching { measure(tally(c, "20 https GETs (ms)")) { repeat(20) { c.get(https, buf) } } }
                    .onFailure { Log.w(TAG, "${c.name} https: $it") }
            }
        }

        val report = StringBuilder("\n%-24s %22s %22s\n".format("scenario (per round)", "OkHttp wall / cpu", "HttpURLConn wall / cpu"))
        for (k in results.getValue(Ok).keys) {
            val o = results.getValue(Ok).getValue(k); val p = results.getValue(Platform)[k] ?: continue
            fun f(t: Tally) = "%9.1f / %7.1f".format(t.wall / t.n, t.cpu / t.n)
            report.append("%-24s %22s %22s".format(k, f(o), f(p)))
            if (o.extra > 0) report.append("   reads/round: ${o.extra / o.n} vs ${p.extra / p.n}")
            report.append('\n')
        }
        report.append("process CPU ms total: ${Process.getElapsedCpuTime()}\n")
        report.lines().forEach { Log.i(TAG, it) }
    }

    private companion object { const val TAG = "HttpBench" }
}
