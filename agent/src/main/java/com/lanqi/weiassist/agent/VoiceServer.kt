package com.lanqi.weiassist.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.UUID

/** The language speech is transcribed in; [id] is the value the server's `language` field takes. */
enum class VoiceLanguage(val id: String) {
    Auto("auto"), Chinese("zh"), English("en");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }

        /** Auto-detect can pick the wrong language on a short clip, so the app's own language comes first. */
        fun forLocale(locale: Locale) = if (locale.language == "zh") Chinese else English
    }
}

/** Where the user's speech-to-text server is (tools/voice-server). No address means voice input is off. */
class VoiceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    private val _url = MutableStateFlow(prefs.getString(KEY_URL, null))
    val url: StateFlow<String?> = _url

    private val _language = MutableStateFlow(VoiceLanguage.of(prefs.getString(KEY_LANGUAGE, null)) ?: VoiceLanguage.forLocale(Locale.getDefault()))
    val language: StateFlow<VoiceLanguage> = _language

    fun setUrl(url: String?) {
        prefs.edit().putString(KEY_URL, url).apply()
        _url.value = url
    }

    fun setLanguage(language: VoiceLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.id).apply()
        _language.value = language
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_LANGUAGE = "language"
    }
}

object VoiceServer {
    const val DEFAULT_PORT = 8765

    sealed interface Health {
        data class Ready(val model: String) : Health
        /** It answers, but its model isn't loaded (it is starting, or the disk holding the model is missing). */
        data object Loading : Health
        /** Something answers there that isn't a voice server. */
        data object Other : Health
        data object Unreachable : Health
    }

    /**
     * What the user typed as the server's origin: `10.0.0.5` becomes `http://10.0.0.5:8765`, and a pasted `/v1` is
     * dropped. Null if it can't be an address.
     */
    fun normalizeUrl(input: String): String? {
        val text = input.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')
        // Ending in a colon is a scheme with nothing after it, or a port left out: "http://" has lost its slashes here.
        if (text.isEmpty() || text.endsWith(':') || text.any { it.isWhitespace() }) return null
        val hadScheme = "://" in text
        val uri = runCatching { URI(if (hadScheme) text else "http://$text") }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
        // A plain IP or name means our server; with https or a path it is someone's proxy, and its port is its own.
        val port = when {
            uri.port != -1 -> ":${uri.port}"
            uri.scheme == "https" || !uri.path.isNullOrEmpty() -> ""
            else -> ":$DEFAULT_PORT"
        }
        return "${uri.scheme}://${uri.host}$port${uri.path.orEmpty()}"
    }

    fun parseHealth(code: Int, body: String): Health {
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json == null || !json.has("ready")) return Health.Other
        return when {
            code in 200..299 && json.optBoolean("ready") -> Health.Ready(json.optString("model"))
            code == 503 -> Health.Loading
            else -> Health.Other
        }
    }

    sealed interface Transcript {
        data class Text(val text: String) : Transcript
        data object Unreachable : Transcript
        data object Loading : Transcript
        data class Rejected(val message: String) : Transcript
    }

    fun parseTranscript(code: Int, body: String): Transcript {
        val json = runCatching { JSONObject(body) }.getOrNull()
        return when {
            code in 200..299 && json?.has("text") == true -> Transcript.Text(json.optString("text").trim())
            code == 503 -> Transcript.Loading
            else -> Transcript.Rejected(json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: "HTTP $code")
        }
    }

    /** The multipart body of a transcription request: an upload of known length, which the server requires. */
    fun transcriptionBody(boundary: String, audio: ByteArray, language: VoiceLanguage): ByteArray =
        ByteArrayOutputStream(audio.size + 512).apply {
            write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n${language.id}\r\n" +
                    "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n").toByteArray(),
            )
            write(audio)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()

    suspend fun transcribe(url: String, clip: File, language: VoiceLanguage): Transcript = withContext(Dispatchers.IO) {
        runCatching {
            val boundary = "weiassist-${UUID.randomUUID()}"
            val body = transcriptionBody(boundary, clip.readBytes(), language)
            val c = URL("$url/v1/audio/transcriptions").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 5_000
                // The server queues requests, so this one may wait for others.
                c.readTimeout = 120_000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                parseTranscript(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                c.disconnect()
            }
        }.getOrDefault(Transcript.Unreachable)
    }

    /** [spoken] after what is already in a box; a space only between words of scripts that use them. */
    fun append(existing: String, spoken: String): String {
        if (existing.isEmpty()) return spoken
        val needsSpace = !existing.last().isWhitespace() && !isCjk(existing.last()) && !isCjk(spoken.first())
        return if (needsSpace) "$existing $spoken" else existing + spoken
    }

    private fun isCjk(c: Char) = c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFFEF

    suspend fun health(url: String): Health = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("$url/health").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 3_000
                c.readTimeout = 5_000
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                parseHealth(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                c.disconnect()
            }
        }.getOrDefault(Health.Unreachable)
    }
}
