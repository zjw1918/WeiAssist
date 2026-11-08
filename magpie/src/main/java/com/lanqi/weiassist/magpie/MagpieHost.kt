package com.lanqi.weiassist.magpie

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.lanqi.weiassist.core.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs the AI gateway: magpie, as its own process (lib/<abi>/libmagpie.so in the APK, a program, run from
 * nativeLibraryDir because Android lets an app run programs only from there). It serves an OpenAI-, Anthropic- and
 * Gemini-compatible API on every interface ([GATEWAY_PORT]). This phone's apps get in with any key; other devices
 * on the network need a gateway key, made in its console. The console (magpie's web page: providers, sign-ins,
 * routing, usage, gateway keys) is only on this phone, at [consoleUrl], behind [webKey].
 *
 * Everything magpie keeps (settings, keys, sign-ins, usage) is under files/magpie, as it would be in a home folder.
 * While it is on, it runs again if it dies (later each time, up to a minute) and comes back at the app's start.
 */
class MagpieHost private constructor(context: Context) {
    sealed interface State {
        /** This APK has no gateway for this phone's ABI. */
        data object Unavailable : State
        data object Off : State
        data object Starting : State
        data object Running : State
        /** It stopped by itself ([message]); it starts again shortly. */
        data class Failed(val message: String) : State
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("magpie", Context.MODE_PRIVATE)
    private val binary = File(app.applicationInfo.nativeLibraryDir, "libmagpie.so")
    private val root = File(app.filesDir, "magpie")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(if (binary.isFile) State.Off else State.Unavailable)
    val state: StateFlow<State> = _state.asStateFlow()

    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    /** The console's key: made once, kept in the Keystore-sealed store, so its link stays the same. */
    val webKey: String by lazy {
        val store = SecureStore(app, "magpie-web")
        store.read() ?: ByteArray(18).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }.also(store::write)
    }

    val consoleUrl: String get() = "http://127.0.0.1:$WEB_PORT/?k=$webKey"

    /** Where this phone's apps reach the gateway (OpenAI-style APIs under /v1). */
    val localBaseUrl: String get() = "http://127.0.0.1:$GATEWAY_PORT"

    /** Where other devices reach it, while this phone has an address on a local network (Wi-Fi, hotspot, Ethernet). */
    fun lanBaseUrl(): String? = lanAddress()?.let { "http://$it:$GATEWAY_PORT" }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start() else stop()
    }

    /** At the app's start: on again if the user left it on. */
    fun autoStart() {
        if (enabled) start()
    }

    @Synchronized
    private fun start() {
        if (_state.value == State.Unavailable || job?.isActive == true) return
        job = scope.launch {
            var wait = FIRST_RETRY_MS
            while (isActive) {
                val began = SystemClock.elapsedRealtime()
                val outcome = runCatching { runOnce() }
                if (!isActive) break
                val message = outcome.fold({ "exited with code $it" }, { it.message ?: it.javaClass.simpleName })
                Log.w(TAG, "gateway stopped: $message")
                _state.value = State.Failed(message)
                // It ran a good while: a new failure, so start the backoff again.
                if (SystemClock.elapsedRealtime() - began > STABLE_MS) wait = FIRST_RETRY_MS
                delay(wait)
                wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    @Synchronized
    private fun stop() {
        job?.cancel()
        job = null
        if (_state.value != State.Unavailable) _state.value = State.Off
    }

    /** Runs magpie until it exits (its exit code) or this coroutine is cancelled (then it is stopped). */
    private suspend fun runOnce(): Int {
        _state.value = State.Starting
        if (killStale()) delay(500) // let the old one's ports go
        prepare()
        val pb = ProcessBuilder(binary.path, "serve", "--web=127.0.0.1:$WEB_PORT")
            .directory(root)
            .redirectErrorStream(true)
        env(pb.environment())
        val p = pb.start()
        val log = thread(name = "magpie-log", isDaemon = true) {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    Log.i(TAG, line.replace(webKey, "…")) // its console link carries the key
                    if (line.contains("magpie gateway on")) _state.value = State.Running
                }
            }
        }
        try {
            return runInterruptible { p.waitFor() }
        } finally {
            if (p.isAlive) {
                p.destroy() // SIGTERM: magpie closes its listeners and files
                if (!p.waitFor(STOP_GRACE_S, TimeUnit.SECONDS)) p.destroyForcibly()
            }
            log.join(1_000)
        }
    }

    private fun env(e: MutableMap<String, String>) {
        val tmp = File(app.cacheDir, "magpie-tmp").apply { mkdirs() }
        e["HOME"] = File(root, "home").path
        e["XDG_CONFIG_HOME"] = root.path // magpie's own files: files/magpie/magpie
        e["XDG_CACHE_HOME"] = File(root, "cache").path
        e["XDG_DATA_HOME"] = File(root, "data").path
        e["XDG_STATE_HOME"] = File(root, "state").path
        e["TMPDIR"] = tmp.path // Go's default on Android is /data/local/tmp, which an app can't write
        e["MAGPIE_ADDR"] = "0.0.0.0:$GATEWAY_PORT"
        e["MAGPIE_WEB_KEY"] = webKey
        // Not a desktop: nothing to put on a PATH or a menu. Subscription accounts stay on (MAGPIE_NO_ACCOUNTS would
        // refuse every sign-in, ChatGPT's too); HOME is this app's own folder, so the only sign-ins magpie can read
        // are the ones made in its console.
        e["MAGPIE_NO_INSTALL"] = "1"
        e["MAGPIE_NO_SHORTCUT"] = "1"
        e["DO_NOT_TRACK"] = "1"
    }

    /** Its folders, and its settings with self-update off: the APK is what updates it. */
    private fun prepare() {
        for (d in listOf("home", "cache", "data", "state", "magpie")) File(root, d).mkdirs()
        val file = File(root, "magpie/settings.json")
        val json = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        if (json.optBoolean("noAutoUpdate")) return
        json.put("noAutoUpdate", true)
        val tmp = File(file.parentFile, "settings.json.tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(file)
    }

    /**
     * A magpie of ours still running from before (the app's process died, magpie didn't): it holds the ports, so
     * stop it. Only this app's processes are visible in /proc. True if there was one.
     */
    private fun killStale(): Boolean {
        var found = false
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val cmd = runCatching { File(d, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (cmd.substringBefore('\u0000') == binary.path) {
                Log.w(TAG, "stopping an earlier gateway (pid $pid)")
                android.os.Process.killProcess(pid)
                found = true
            }
        }
        return found
    }

    companion object {
        private const val TAG = "Magpie"
        private const val KEY_ENABLED = "enabled"
        const val GATEWAY_PORT = 3425
        const val WEB_PORT = 3430
        private const val FIRST_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val STABLE_MS = 60_000L
        private const val STOP_GRACE_S = 3L

        /** This phone's IPv4 address on a local network, preferring Wi-Fi; null if it has none. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        @Volatile private var instance: MagpieHost? = null
        fun get(context: Context): MagpieHost = instance ?: synchronized(this) {
            instance ?: MagpieHost(context).also { instance = it }
        }
    }
}
