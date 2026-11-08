package com.lanqi.weiassist.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where the AI providers come from (the Providers tab). [WeiAssist]: Pi's own providers, keys and sign-ins, the
 * default and always there. [Gateway]: the optional Gateway app (magpie) on this phone serves every model, and every
 * engine (Pi, DeepSeek Harness) goes straight to it; its providers and sign-ins are managed in its own console.
 * Changed only by the user: a gateway that stops fails the run, it never falls back silently.
 */
enum class ProviderSource(val id: String) {
    WeiAssist("weiassist"), Gateway("gateway");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: WeiAssist
    }
}

/** The Gateway app as WeiAssist reaches it: on this phone's loopback, where it asks for no key. */
object GatewayLink {
    const val PACKAGE = "com.lanqi.weiassist.gateway"
    const val PORT = 3425
    const val ORIGIN = "http://127.0.0.1:$PORT"
    const val BASE_URL = "$ORIGIN/v1"
    const val DOWNLOAD = "https://github.com/zjw1918/WeiAssist/releases/latest"

    /** The gateway as one provider in Pi (not a custom provider: never listed among them). */
    const val PROVIDER_ID = "weiassist-gateway"
    const val NAME = "Gateway"

    /** Loopback asks for no key; Pi counts a provider as usable only with one, so it gets this. */
    const val LOCAL_KEY = "local"

    enum class Status { NotInstalled, Off, Serving }

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Installed, and whether it answers now. Blocking: call off the main thread. */
    fun status(ctx: Context): Status = when {
        !installed(ctx) -> Status.NotInstalled
        serving() -> Status.Serving
        else -> Status.Off
    }

    fun serving(): Boolean = runCatching {
        val c = URL("$BASE_URL/models").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 1_000
            c.readTimeout = 2_000
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    /** The model ids in the gateway's own order (newest first), which the id-sorted model list loses. */
    suspend fun listedIds(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("$BASE_URL/models").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 2_000
                c.readTimeout = 5_000
                val data = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("data")
                (0 until data.length()).map { data.getJSONObject(it).optString("id") }
            } finally {
                c.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    /** The gateway as Pi's provider, with [models]. */
    fun provider(models: List<CustomModel>) = CustomProvider(PROVIDER_ID, NAME, BASE_URL, models)

    /**
     * A request's error that means nothing answered (with the gateway as the provider: it stopped, or its app's
     * process ended). Pi in a WebView says "Failed to fetch", dsh's Node "fetch failed" or ECONNREFUSED.
     */
    fun unreachable(msg: String) = listOf("ECONNREFUSED", "Connection refused", "Failed to connect", "fetch failed", "Failed to fetch", "Connection error")
        .any { it in msg }
}
