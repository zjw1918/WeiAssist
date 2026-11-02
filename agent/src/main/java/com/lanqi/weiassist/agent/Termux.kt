package com.lanqi.weiassist.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the agent's bash commands in Termux (com.termux) with its RUN_COMMAND service, and gets the result
 * back through a PendingIntent. Termux is a separate app, so commands run as its user: they share its
 * packages and home, and cannot read this app's files. Each session works in ~/sessions/<id>.
 *
 * The user sets Termux up once: `allow-external-apps=true` in ~/.termux/termux.properties, and this app
 * holds the "Run commands in Termux environment" permission. [run] says what is missing otherwise.
 */
class Termux(context: Context) {
    private val app = context.applicationContext
    private val requests = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<android.os.Bundle?>>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val req = i.getIntExtra(EXTRA_REQUEST, -1)
            pending.remove(req)?.complete(i.getBundleExtra("result"))
        }
    }

    init {
        // Termux sends the PendingIntent as this app, so the receiver needs no export.
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun installed() = runCatching { app.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Termux's Google Play build has no RUN_COMMAND service; the F-Droid and GitHub builds do. */
    fun runnable() = app.packageManager.resolveService(Intent(ACTION_RUN).setPackage(PACKAGE), 0) != null

    fun permitted() = ContextCompat.checkSelfPermission(app, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Run [command] with bash in session [session]'s folder, killed after [timeoutSec]. Returns what the model sees. */
    suspend fun run(session: String, call: String, command: String, timeoutSec: Int): String {
        setupProblem()?.let { return it }
        // $1 = the command, $2 = session folder, $3 = call id, $4 = timeout. The command is an argument,
        // never spliced into the script, so it needs no quoting. timeout runs in the background so the
        // pidfile holds its pid: bash_kill sends it TERM, and timeout passes that on to the whole job.
        val script = """
            d="${'$'}HOME/sessions/${'$'}2"; mkdir -p "${'$'}d" && cd "${'$'}d" || exit 97
            p="${'$'}d/.bash-${'$'}3.pid"
            timeout -k 5 "${'$'}4" bash -c "${'$'}1" < /dev/null & echo ${'$'}! > "${'$'}p"
            wait ${'$'}!; c=${'$'}?; rm -f "${'$'}p"; exit ${'$'}c
        """.trimIndent()
        val r = execute(arrayOf("-c", script, "bash", command, safe(session), safe(call), timeoutSec.toString()), timeoutSec + 30L)
            ?: return "No answer from Termux after ${timeoutSec + 30}s. Termux may be stopped by the system's battery saver: " +
                "open Termux once, and allow it to run in the background (autostart, no battery restrictions)."
        val err = r.getInt("err", RESULT_OK)
        if (err != RESULT_OK) return "Termux could not run the command: ${r.getString("errmsg").orEmpty()}\n$SETUP"
        val code = r.getInt("exitCode", -1)
        val out = r.getString("stdout").orEmpty()
        val errOut = r.getString("stderr").orEmpty()
        return buildString {
            append(when (code) {
                124 -> "timed out after ${timeoutSec}s (killed)"
                143 -> "killed (stopped)"
                else -> "exit code $code"
            })
            if (out.isNotEmpty()) append("\n--- stdout ---\n").append(clip(out, length(r, "stdout_original_length") ?: out.length))
            if (errOut.isNotEmpty()) append("\n--- stderr ---\n").append(clip(errOut, length(r, "stderr_original_length") ?: errOut.length))
            if (out.isEmpty() && errOut.isEmpty()) append(" (no output)")
        }
    }

    /** Stop a running [run] (the user pressed Stop). */
    suspend fun kill(session: String, call: String) {
        if (setupProblem() != null) return
        execute(arrayOf("-c", "f=\"\$HOME/sessions/$1/.bash-$2.pid\"; [ -f \"\$f\" ] && kill -TERM \"\$(cat \"\$f\")\"", "bash", safe(session), safe(call)), 20)
    }

    private fun setupProblem(): String? = when {
        !installed() -> "Termux is not installed, so bash is not available. Tell the user: install Termux (from F-Droid or GitHub), then\n$SETUP"
        !runnable() -> "The installed Termux (probably its Google Play build) cannot run commands for other apps. " +
            "Tell the user: install Termux from F-Droid or GitHub (uninstall the Google Play one first; its files are lost), then\n$SETUP"
        !permitted() -> "This app may not run commands in Termux yet. Tell the user:\n$SETUP"
        else -> null
    }

    private suspend fun execute(args: Array<String>, timeoutSec: Long): android.os.Bundle? {
        val req = requests.incrementAndGet()
        val done = CompletableDeferred<android.os.Bundle?>()
        pending[req] = done
        val result = PendingIntent.getBroadcast(
            app, req,
            Intent(ACTION_RESULT).setPackage(app.packageName).putExtra(EXTRA_REQUEST, req),
            // Mutable: Termux adds the result to it.
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        val intent = Intent(ACTION_RUN).setComponent(ComponentName(PACKAGE, "com.termux.app.RunCommandService"))
            .putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "agent")
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result)
        return try {
            // Termux's service goes foreground at once; the agent's own foreground service lets us start it.
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
            withTimeoutOrNull(timeoutSec * 1000) { done.await() }
        } catch (e: Exception) {
            Log.w(TAG, "RUN_COMMAND failed", e)
            android.os.Bundle().apply { putInt("err", 1); putString("errmsg", e.message ?: e.javaClass.simpleName) }
        } finally {
            pending.remove(req)
        }
    }

    companion object {
        private const val TAG = "Termux"
        const val PACKAGE = "com.termux"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val ACTION_RUN = "com.termux.RUN_COMMAND"
        private const val ACTION_RESULT = "com.lanqi.weiassist.TERMUX_RESULT"
        private const val EXTRA_REQUEST = "request"
        private const val PREFIX = "/data/data/com.termux/files/usr"
        private const val HOME = "/data/data/com.termux/files/home"
        private const val RESULT_OK = -1 // Activity.RESULT_OK: Termux's "no error"
        private const val KEEP = 12_000 // characters of each stream the model sees

        val SETUP = """
            1. In Termux run: mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings
            2. Settings → Apps → this app → Permissions → Additional permissions → "Run commands in Termux environment" → Allow.
            3. For long jobs: allow Termux to autostart and run without battery restrictions.
        """.trimIndent()

        /** Termux sends the original lengths as strings. */
        private fun length(r: android.os.Bundle, key: String) = r.get(key)?.toString()?.toIntOrNull()

        /** Ids go into a path: letters, digits, - and _ only. */
        private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

        /** Long output keeps its start and its end (errors and results are usually at the end). */
        internal fun clip(s: String, originalLength: Int): String {
            val cut = if (s.length <= KEEP) s
            else s.take(KEEP / 4) + "\n…[${s.length - KEEP} characters cut]…\n" + s.takeLast(KEEP * 3 / 4)
            return if (originalLength > s.length) "[Termux shortened this output from $originalLength characters]\n$cut" else cut
        }
    }
}
