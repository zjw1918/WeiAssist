package com.lanqi.weiassist.shell

import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The shell helper: started by the app through adb (see ShellBackend.start) as
 * `app_process /system/bin --nice-name=weiassist_shell com.lanqi.weiassist.shell.ShellServer <appUid> <package> <version>`,
 * so it runs as the shell user with the shell's permissions (virtual displays, input on any display, `am`).
 *
 * It hands its Binder to the app through the app's ShellBinderProvider and serves only the app's uid.
 * When the app's process dies, it waits for it to come back and hands it over again. It never starts the
 * app itself. It ends on [exit], or when the app is uninstalled or updated (the app replaces it).
 */
@android.annotation.SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi") // runs as shell
class ShellServer private constructor(
    private val appUid: Int,
    private val pkg: String,
    private val version: Long,
    private val displays: DisplayHost,
) : IShellService.Stub() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val caller = Binder.getCallingUid()
        if (code != INTERFACE_TRANSACTION && caller != appUid && caller != Process.myUid()) {
            throw SecurityException("uid $caller may not use the WeiAssist shell helper")
        }
        // Binder carries only a few exception types back; anything else would reach the app as a silent 0/false.
        return try {
            super.onTransact(code, data, reply, flags)
        } catch (e: SecurityException) { throw e } catch (e: IllegalArgumentException) { throw e } catch (e: IllegalStateException) { throw e
        } catch (e: RuntimeException) {
            log("call $code failed: $e")
            throw IllegalStateException(((e as? java.lang.reflect.UndeclaredThrowableException)?.cause ?: e.cause ?: e).toString())
        }
    }

    override fun getUid() = Process.myUid()
    override fun getPid() = Process.myPid()
    override fun getVersion() = version

    override fun exec(command: String, timeoutMs: Long): Bundle {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } } }.apply { start() }
        val done = p.waitFor(timeoutMs.coerceIn(100, 120_000), TimeUnit.MILLISECONDS)
        if (!done) p.destroyForcibly()
        reader.join(1_000)
        return Bundle().apply { putInt("code", if (done) p.exitValue() else -1); putString("out", synchronized(out) { out.toString() }) }
    }

    // ---- virtual displays ------------------------------------------------------------------------------

    override fun createDisplay(name: String, width: Int, height: Int, densityDpi: Int): Int =
        displays.create(name, width, height, densityDpi).also { log("display $it created for $name (${width}x$height@$densityDpi)") }

    override fun releaseDisplay(displayId: Int) { displays.release(displayId); log("display $displayId released") }

    override fun startActivity(displayId: Int, component: String): String {
        require(displays.has(displayId)) { "no display $displayId" }
        require(Regex("[\\w.]+/[\\w.$]+").matches(component)) { "bad component $component" }
        // A task of its own (NEW_TASK | MULTIPLE_TASK): an app the user has open on the phone's screen stays there.
        val r = exec("am start --display $displayId --user 0 -f 0x18000000 -n $component", 10_000)
        return r.getString("out").orEmpty().trim()
    }

    override fun screenshot(displayId: Int, maxWidth: Int, quality: Int): ByteArray = displays.screenshot(displayId, maxWidth, quality)
    override fun tap(displayId: Int, x: Float, y: Float, durationMs: Long) = displays.has(displayId) && displays.tap(displayId, x, y, durationMs)
    override fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        displays.has(displayId) && displays.swipe(displayId, x1, y1, x2, y2, durationMs)
    override fun key(displayId: Int, keyCode: Int) = displays.has(displayId) && displays.key(displayId, keyCode)

    override fun activities(): String =
        exec("dumpsys activity activities | grep -E '^Display #|^  \\* Task\\{|\\* Hist +#|mResumedActivity:|topResumedActivity=|launchedFromPackage='", 5_000)
            .getString("out").orEmpty()

    override fun moveTask(taskId: Int, displayId: Int): Boolean {
        require(displays.has(displayId)) { "no display $displayId" }
        val r = exec("am display move-stack $taskId $displayId", 5_000)
        log("task $taskId → display $displayId: ${r.getInt("code")} ${r.getString("out").orEmpty().trim()}")
        return r.getInt("code") == 0
    }

    override fun removeTask(taskId: Int): Boolean {
        val r = exec("am stack remove $taskId", 5_000)
        log("task $taskId removed: ${r.getInt("code")} ${r.getString("out").orEmpty().trim()}")
        return r.getInt("code") == 0
    }

    override fun killApp(packageName: String): Boolean {
        require(PACKAGE.matches(packageName)) { "not a package name: $packageName" }
        val r = exec("am kill --user 0 $packageName", 5_000)
        log("killed $packageName: ${r.getInt("code")} ${r.getString("out").orEmpty().trim()}")
        return r.getInt("code") == 0
    }

    override fun isRunning(packageName: String): Boolean {
        require(PACKAGE.matches(packageName)) { "not a package name: $packageName" }
        // Any of its processes, including ":push"-like ones.
        return exec("ps -A -o NAME | grep -qE '^${packageName.replace(".", "\\.")}(:|$)'", 5_000).getInt("code") == 0
    }

    override fun watch(displayId: Int, listener: IFrameListener, maxFps: Int) { log("watch display $displayId at $maxFps fps"); displays.watch(displayId, listener, maxFps) }
    override fun unwatch(displayId: Int, listener: IFrameListener) { log("unwatch display $displayId"); displays.unwatch(displayId, listener) }
    override fun frameDone(displayId: Int, listener: IFrameListener, seq: Int) = displays.frameDone(displayId, listener, seq)
    override fun setDisplayActive(displayId: Int, active: Boolean) { log("display $displayId ${if (active) "on" else "off"}"); displays.setActive(displayId, active) }

    override fun exit() {
        displays.releaseAll()
        log("exit requested")
        Thread { Thread.sleep(100); System.exit(0) }.start()
    }

    // ---- reaching the app ------------------------------------------------------------------------------

    /** Give our Binder to the app (its provider); keep it until the app's process dies, then wait for it. */
    private fun serve() {
        while (true) {
            if (!installed()) { log("app uninstalled; exiting"); System.exit(0) }
            // The app was updated: our code is from the old APK. The new app starts a new helper.
            if (System.getenv("CLASSPATH")?.let { File(it).exists() } == false) { log("app updated; exiting"); System.exit(0) }
            if (appRunning()) {
                val client = runCatching { attach() }.onFailure { log("attach failed: $it") }.getOrNull()
                if (client != null) {
                    log("attached to $pkg")
                    val gone = Object()
                    runCatching { client.linkToDeath({ synchronized(gone) { gone.notifyAll() } }, 0) }
                        .onSuccess { synchronized(gone) { while (client.isBinderAlive) gone.wait(30_000) } }
                    log("app process gone; releasing its displays")
                    displays.releaseAll()
                    continue
                }
            }
            Thread.sleep(2_000)
        }
    }

    /** Hand [this] to the app's ShellBinderProvider; returns the app's token, which dies with its process. */
    private fun attach(): IBinder? {
        val authority = "$pkg.shell"
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = am.javaClass.methods.first { it.name == "getContentProviderExternal" }.let { m ->
            when (m.parameterTypes.size) {
                4 -> m.invoke(am, authority, 0, token, "weiassist-shell")
                else -> m.invoke(am, authority, 0, token)
            }
        } ?: error("no provider $authority")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)!!
            val extras = Bundle().apply { putBinder("binder", this@ShellServer); putLong("version", version) }
            val result = call(provider, authority, extras) ?: return null
            return result.getBinder("client")
        } finally {
            runCatching {
                am.javaClass.methods.firstOrNull { it.name == "removeContentProviderExternalAsUser" }?.invoke(am, authority, token, 0)
                    ?: am.javaClass.methods.first { it.name == "removeContentProviderExternal" }.invoke(am, authority, token)
            }
        }
    }

    /** IContentProvider.call, whose signature changed in Android 10, 11 and 12. */
    private fun call(provider: Any, authority: String, extras: Bundle): Bundle? {
        val args: Array<Any?> = when {
            Build.VERSION.SDK_INT >= 31 -> arrayOf(
                android.content.AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build(),
                authority, METHOD_ATTACH, null, extras,
            )
            Build.VERSION.SDK_INT == 30 -> arrayOf(SHELL_PACKAGE, null, authority, METHOD_ATTACH, null, extras)
            Build.VERSION.SDK_INT == 29 -> arrayOf(SHELL_PACKAGE, authority, METHOD_ATTACH, null, extras)
            else -> arrayOf(SHELL_PACKAGE, METHOD_ATTACH, null, extras)
        }
        // Older overloads stay around next to the current one: pick the one taking exactly these arguments.
        val m = provider.javaClass.methods.first { m ->
            m.name == "call" && m.parameterTypes.size == args.size && m.parameterTypes.last() == Bundle::class.java &&
                (args[0] == null || m.parameterTypes[0].isInstance(args[0]))
        }
        return m.invoke(provider, *args) as Bundle?
    }

    /** Whether the app has a process now (shell may read every process's cmdline). */
    private fun appRunning(): Boolean = File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().any { dir ->
        runCatching { File(dir, "cmdline").readText().substringBefore('\u0000') == pkg }.getOrDefault(false)
    }

    private fun installed(): Boolean = runCatching {
        ProcessBuilder("pm", "path", pkg).start().inputStream.bufferedReader().readText().contains("package:")
    }.getOrDefault(true)

    companion object {
        const val NICE_NAME = "weiassist_shell"
        const val SHELL_PACKAGE = "com.android.shell"
        const val METHOD_ATTACH = "attach"
        private const val TAG = "WeiAssistShell"
        /** A package name, so it is safe on a shell command line. */
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        private fun log(msg: String) { Log.i(TAG, msg); System.err.println("$TAG: $msg") }

        @JvmStatic
        fun main(args: Array<String>) {
            val appUid = args[0].toInt()
            val pkg = args[1]
            val version = args[2].toLong()
            log("started uid=${Process.myUid()} pid=${Process.myPid()} for $pkg ($appUid) v$version")
            Looper.prepareMainLooper()
            val server = ShellServer(appUid, pkg, version, DisplayHost(DisplayHost.shellContext()))
            Thread({ server.serve() }, "attach").start()
            Looper.loop()
        }
    }
}
