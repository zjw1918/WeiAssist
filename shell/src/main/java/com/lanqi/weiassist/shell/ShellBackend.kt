package com.lanqi.weiassist.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.lanqi.weiassist.shell.adb.AdbConnection
import com.lanqi.weiassist.shell.adb.AdbKeyStore
import com.lanqi.weiassist.shell.adb.AdbMdns
import com.lanqi.weiassist.shell.adb.AdbPairing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where the shell backend stands. */
sealed interface ShellState {
    /** Wireless debugging needs Android 11. (A helper started from a computer still works: see [ShellBackend.manualCommand].) */
    data object Unsupported : ShellState
    /** Never paired with this phone's Wireless debugging. */
    data object NotPaired : ShellState
    /** Paired; the helper isn't running (e.g. after a reboot). [hint] says why the last start failed. */
    data class Stopped(val hint: String? = null) : ShellState
    data object Starting : ShellState
    /** The helper runs as [uid] (2000 = shell). */
    data class Running(val uid: Int, val pid: Int) : ShellState
}

/**
 * The built-in shell backend: pairs with this phone's Wireless debugging (once), then starts the shell
 * helper ([ShellServer]) through adb and keeps its Binder. One per process ([get]).
 *
 * The helper outlives the app's process (it re-attaches by itself when the app comes back) but not a
 * reboot: then Wireless debugging must be on again for [start]. If the helper could grant the app
 * WRITE_SECURE_SETTINGS (not on every phone: ColorOS needs "Disable permission monitoring"), the app
 * turns Wireless debugging on by itself for that.
 */
class ShellBackend private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val keys = AdbKeyStore(app)
    private val prefs = app.getSharedPreferences("shell", Context.MODE_PRIVATE)
    private val mdns = AdbMdns(app)
    private val startLock = Mutex()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ShellState> = _state.asStateFlow()

    @Volatile private var service: IShellService? = null
    private var attached = CompletableDeferred<IShellService>()

    /** Lives as long as the app's process; the helper watches it to know when to hand itself over again. */
    internal val clientToken: IBinder = Binder()

    /** The helper, if it runs. */
    fun service(): IShellService? = service

    private fun initialState(): ShellState = when {
        Build.VERSION.SDK_INT < 30 -> ShellState.Unsupported
        !prefs.getBoolean(KEY_PAIRED, false) -> ShellState.NotPaired
        else -> ShellState.Stopped()
    }

    /** The APK's identity for the helper: a helper from another build is replaced. */
    private val version: Long get() = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime

    // ---- from the helper (ShellBinderProvider) --------------------------------------------------------

    internal fun onAttached(binder: IBinder, helperVersion: Long) {
        val s = IShellService.Stub.asInterface(binder)
        if (helperVersion != version) {
            Log.i(TAG, "helper is from another build ($helperVersion ≠ $version): replacing it")
            runCatching { s.exit() }
            scope.launch { start() }
            return
        }
        runCatching { binder.linkToDeath({ onHelperDied(binder) }, 0) }.onFailure { return }
        service = s
        val st = ShellState.Running(s.uid, s.pid)
        Log.i(TAG, "helper attached: uid=${st.uid} pid=${st.pid}")
        _state.value = st
        attached.complete(s)
        // Remember that pairing worked (a helper started from a computer also counts).
        prefs.edit().putBoolean(KEY_PAIRED, true).apply()
        scope.launch(Dispatchers.IO) { tryGrantSecureSettings(s) }
    }

    private fun onHelperDied(binder: IBinder) {
        if (service?.asBinder() !== binder) return
        Log.w(TAG, "helper died")
        service = null
        attached = CompletableDeferred()
        _state.value = ShellState.Stopped()
    }

    // ---- pairing --------------------------------------------------------------------------------------

    /** Ports of this phone's "Pair device with pairing code" dialog, while it is open. */
    fun pairingPorts() = mdns.ports(AdbMdns.PAIRING)

    /** Pair with the code shown in Wireless debugging's dialog. Throws [AdbPairing.WrongCodeException] etc. */
    suspend fun pair(port: Int, code: String) = withContext(Dispatchers.IO) {
        val name = "weiassist@${Build.MODEL.replace(' ', '_')}"
        AdbPairing(keys.getOrCreate(), name).pair(LOOPBACK, port, code)
        prefs.edit().putBoolean(KEY_PAIRED, true).apply()
        if (_state.value == ShellState.NotPaired) _state.value = ShellState.Stopped()
    }

    // ---- starting the helper --------------------------------------------------------------------------

    /**
     * Start the helper through Wireless debugging (it must be on). Returns the helper, or null with
     * [state] = Stopped(hint) when it couldn't. Safe to call repeatedly.
     */
    suspend fun start(): IShellService? = startLock.withLock {
        service?.let { return it }
        if (_state.value == ShellState.NotPaired || _state.value == ShellState.Unsupported) return null
        _state.value = ShellState.Starting
        try {
            val port = connectPort() ?: return fail(app.getString(R.string.shell_hint_wireless_off))
            Log.d(TAG, "connecting with key ${keys.getOrCreate().adbPublicKey("").take(40)}…")
            val adb = withContext(Dispatchers.IO) { AdbConnection.connect(LOOPBACK, port, keys.getOrCreate()) }
            try {
                val r = withContext(Dispatchers.IO) { adb.shell(startCommand()) }
                Log.d(TAG, "start command: exit ${r.code} ${r.output.trim().take(300)}")
                // Keep the adb connection open until the helper reports: closing it while app_process is
                // still starting ends the helper with it.
                withTimeoutOrNull(10_000) { attached.await() } ?: fail(app.getString(R.string.shell_hint_no_attach))
            } finally {
                withContext(Dispatchers.IO) { adb.close() }
            }
        } catch (e: AdbConnection.NotPairedException) {
            Log.w(TAG, "adbd refused our key", e)
            prefs.edit().putBoolean(KEY_PAIRED, false).apply()
            _state.value = ShellState.NotPaired
            null
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(hint: String): IShellService? {
        Log.w(TAG, "helper not started: $hint")
        _state.value = ShellState.Stopped(hint)
        return null
    }

    /** Start the helper if it should run and doesn't (app start, after a reboot). Quiet when it can't. */
    fun autoStart() {
        Log.d(TAG, "manual start (Android 8-10, or from a computer): ${manualCommand()}")
        if (_state.value !is ShellState.Stopped) return
        scope.launch {
            // A helper that survived the app's process attaches by itself within a moment.
            if (withTimeoutOrNull(2_500) { state.first { it is ShellState.Running } } != null) return@launch
            if (Build.VERSION.SDK_INT >= 30 && canWriteSecureSettings()) enableWirelessDebugging()
            start()
        }
    }

    /** Debug builds: try to start even if a previous attempt said "not paired" (for testing on a device). */
    fun debugForceStart() {
        prefs.edit().putBoolean(KEY_PAIRED, true).apply()
        if (service == null) _state.value = ShellState.Stopped()
        scope.launch { start() }
    }

    /** Stop the helper (it is started again by [start]). */
    fun stop() { runCatching { service?.exit() } }

    /** Forget the pairing (the user can also revoke it in Wireless debugging). */
    fun unpair() {
        stop()
        keys.delete()
        prefs.edit().putBoolean(KEY_PAIRED, false).apply()
        _state.value = initialState()
    }

    /**
     * Wireless debugging's connect port (it changes whenever Wireless debugging is turned on). In order:
     * the last one that worked; the system property where the app may read it; mDNS (not every ROM
     * announces it); a scan of this phone's own loopback for the port that answers like adbd.
     */
    private suspend fun connectPort(): Int? = withContext(Dispatchers.IO) {
        val ok = { p: Int -> AdbConnection.isAdbTls(LOOPBACK, p) }
        prefs.getInt(KEY_PORT, 0).takeIf { it > 0 && ok(it) }?.let { return@withContext it.also { Log.d(TAG, "connect port $it (last)") } }
        propBlocking("service.adb.tls.port")?.toIntOrNull()?.takeIf { it > 0 && ok(it) }
            ?.let { return@withContext remember(it, "property") }
        withTimeoutOrNull(3_000) { mdns.ports(AdbMdns.CONNECT).filterNotNull().first() }?.takeIf(ok)
            ?.let { return@withContext remember(it, "mDNS") }
        scanLoopback()?.let { remember(it, "scan") }
    }

    private fun remember(port: Int, how: String): Int {
        Log.d(TAG, "connect port $port ($how)")
        prefs.edit().putInt(KEY_PORT, port).apply()
        return port
    }

    /** adbd binds an ephemeral port: try them all on the loopback, in parallel. Closed ports refuse at once. */
    private suspend fun scanLoopback(): Int? = kotlinx.coroutines.coroutineScope {
        val started = System.currentTimeMillis()
        val (low, high) = runCatching {
            java.io.File("/proc/sys/net/ipv4/ip_local_port_range").readText().trim().split(Regex("\\s+")).map(String::toInt)
        }.getOrNull()?.let { it[0] to it[1] } ?: (32768 to 60999)
        val found = CompletableDeferred<Int?>()
        val next = java.util.concurrent.atomic.AtomicInteger(low)
        val workers = List(48) {
            launch(Dispatchers.IO) {
                while (!found.isCompleted) {
                    val p = next.getAndIncrement()
                    if (p > high) break
                    val open = runCatching { java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(LOOPBACK, p), 150) } }.isSuccess
                    if (open && AdbConnection.isAdbTls(LOOPBACK, p)) found.complete(p)
                }
            }
        }
        launch { workers.forEach { it.join() }; found.complete(null) }
        found.await().also {
            workers.forEach { it.cancel() }
            Log.d(TAG, "loopback scan ${low}-$high: ${it ?: "nothing"} in ${System.currentTimeMillis() - started} ms")
        }
    }

    private fun startCommand(): String {
        val apk = app.applicationInfo.sourceDir
        val main = ShellServer::class.java.name
        // setsid + background: the helper outlives this adb shell session.
        return "killall ${ShellServer.NICE_NAME} 2>/dev/null; " +
            "CLASSPATH='$apk' setsid app_process /system/bin --nice-name=${ShellServer.NICE_NAME} $main " +
            "${android.os.Process.myUid()} ${app.packageName} $version </dev/null >$LOG 2>&1 &"
    }

    /** For phones without Wireless debugging (Android 8-10): the same helper, started once from a computer. */
    fun manualCommand(): String = "adb shell \"${startCommand().replace("\"", "\\\"")}\""

    // ---- surviving reboots without the user (where the phone allows it) ---------------------------------

    private fun canWriteSecureSettings() =
        app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun tryGrantSecureSettings(s: IShellService) {
        if (canWriteSecureSettings()) return
        val r = runCatching { s.exec("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS", 5_000) }.getOrNull()
        Log.i(TAG, "grant WRITE_SECURE_SETTINGS: code=${r?.getInt("code")} ${r?.getString("out")?.trim()?.take(200)}")
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableWirelessDebugging() {
        runCatching { Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1) }
            .onSuccess { Log.i(TAG, "turned Wireless debugging on") }
            .onFailure { Log.w(TAG, "could not turn Wireless debugging on: $it") }
        kotlinx.coroutines.delay(1_500)
    }

    private fun propBlocking(name: String): String? = runCatching {
        ProcessBuilder("getprop", name).start().inputStream.bufferedReader().readText().trim().ifEmpty { null }
    }.getOrNull()

    companion object {
        private const val TAG = "ShellBackend"
        private const val LOOPBACK = "127.0.0.1"
        private const val KEY_PAIRED = "paired"
        private const val KEY_PORT = "port"
        /** The helper's output, readable with `adb shell cat`, for diagnosis. */
        const val LOG = "/data/local/tmp/weiassist_shell.log"

        @SuppressLint("StaticFieldLeak") // holds only the application context
        @Volatile private var instance: ShellBackend? = null
        fun get(context: Context): ShellBackend = instance ?: synchronized(this) {
            instance ?: ShellBackend(context).also { instance = it }
        }
        internal fun peek(): ShellBackend? = instance
    }
}
