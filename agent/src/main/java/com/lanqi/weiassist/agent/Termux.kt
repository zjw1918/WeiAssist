package com.lanqi.weiassist.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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

    /** Sessions that have used bash: only they have a folder and jobs to clean up. */
    private val prefs = app.getSharedPreferences("termux", Context.MODE_PRIVATE)
    private fun used(session: String) = session in prefs.getStringSet("used", emptySet())!!

    /** Whether session [session] has a folder in Termux (~/sessions/<id>) that deleting it could remove. */
    fun hasFolder(session: String) = used(session)
    private fun markUsed(session: String, on: Boolean) {
        val set = prefs.getStringSet("used", emptySet())!!.toMutableSet()
        if (if (on) set.add(session) else set.remove(session)) prefs.edit().putStringSet("used", set).apply()
    }

    /**
     * Start DeepSeek Harness (the dsh AI core) in Ubuntu in Termux (set up by [DshSetup]), in a tmux session of its own,
     * with [env] (the bridge's port and secret, model keys) passed as arguments, never spliced into the script.
     * A dsh already running is replaced. Returns why it couldn't start, or null.
     */
    suspend fun launchDsh(env: Map<String, String>): String? {
        setupProblem()?.let { return it }
        val script = """
            d="${'$'}{HOME}/.weiassist-dsh"
            # dsh runs in Ubuntu (proot-distro, see dsh-host/termux/setup-proot.sh): there Node is linux-arm64, which dsh's
            # native modules support (Termux's own Node is "android"). Ubuntu sees this home as /root.
            [ -x "${'$'}{d}/linux/run/node_modules/.bin/dsh" ] && command -v proot-distro > /dev/null || { echo "${'$'}{0}"; exit 3; }
            export TMUX_TMPDIR="${'$'}{d}/.tmux"; mkdir -p "${'$'}{TMUX_TMPDIR}"
            # dsh's own bash and file tools run unconfined (danger-full-access; the user chose this): its sandbox backends
            # (bubblewrap, Landlock) can't work in proot on Android, and dsh refuses to run them otherwise. That is the
            # power the app's Termux bash has too; the app's before_tool still gates every call.
            # An earlier dsh survives its tmux session (proot and node are reparented): end its node, whose command
            # line is exactly this (proot exits with it).
            for p in /proc/[0-9]*; do
                [ "${'$'}(tr '\0' ' ' < "${'$'}{p}/cmdline" 2> /dev/null)" = "node ./node_modules/.bin/dsh --profile weiassist " ] && kill "${'$'}{p#/proc/}" 2> /dev/null
            done
            tmux kill-session -t dsh 2> /dev/null
            # NODE_COMPILE_CACHE: Node keeps the modules it compiled there (the bridge saves them once dsh is up), so
            # later boots skip most of that work. Node checks each entry against its file and Node's version.
            tmux new-session -d -s dsh proot-distro login ubuntu --termux-home -- env DSH_HOME=/root/.weiassist-dsh/linux/home WEIASSIST_SESSIONS_DIR=/root/sessions DSH_PERMISSION_MODE=danger-full-access NODE_COMPILE_CACHE=/root/.weiassist-dsh/linux/compile-cache "${'$'}@" sh -c 'cd /root/.weiassist-dsh/linux/run && exec node ./node_modules/.bin/dsh --profile weiassist > /root/.weiassist-dsh/dsh.log 2>&1'
            # A dsh that dies at once (a missing package, a native module): say why instead of letting the app wait.
            sleep 6
            tmux has-session -t dsh 2> /dev/null || { echo "DeepSeek Harness stopped at once:"; tail -15 "${'$'}{d}/dsh.log"; exit 4; }
        """.trimIndent()
        // $0 = what to say when dsh isn't set up; then the environment.
        val args = arrayOf("-c", script, DshSetup.NOT_SET_UP) + env.map { (k, v) -> "$k=$v" }
        val r = execute(args, 30) ?: return NO_ANSWER
        if (r.getInt("err", RESULT_OK) != RESULT_OK) return "Termux could not start DeepSeek Harness: ${r.getString("errmsg").orEmpty()}"
        val code = r.getInt("exitCode", -1)
        if (code != 0) return r.getString("stdout").orEmpty().ifBlank { r.getString("stderr").orEmpty() }.ifBlank { "exit code $code" }
        keepDshRunning()
        return null
    }

    /**
     * Keep Termux's service in the foreground while dsh runs: a task that waits as long as dsh's tmux session lasts.
     * Otherwise Termux becomes a cached background app once the launch command ends, and some phones (HyperOS) then
     * give its processes almost no CPU: dsh took minutes to boot, or never connected. A running task keeps Termux a
     * foreground service (its "1 task" notification), without a wake lock. One at a time; it ends with dsh.
     */
    private fun keepDshRunning() {
        val script = """
            # dsh-keepalive
            d="${'$'}{HOME}/.weiassist-dsh"; f="${'$'}{d}/keepalive.pid"
            export TMUX_TMPDIR="${'$'}{d}/.tmux"
            p="${'$'}(cat "${'$'}{f}" 2> /dev/null)"
            [ -n "${'$'}{p}" ] && grep -q dsh-keepalive "/proc/${'$'}{p}/cmdline" 2> /dev/null && exit 0
            echo ${'$'}${'$'} > "${'$'}{f}"
            while tmux has-session -t dsh 2> /dev/null; do sleep 15; done
            rm -f "${'$'}{f}"
        """.trimIndent()
        // No result wanted: Termux holds the task, not this app.
        start(command(arrayOf("-c", script), "dsh"))
    }

    /**
     * Run [command] with bash in session [session]'s folder, killed after [timeoutSec]. Returns what the model sees.
     * The session's tmux is its own (TMUX_TMPDIR in its folder), so its jobs can be found and stopped with it.
     */
    suspend fun run(session: String, call: String, command: String, timeoutSec: Int): String {
        setupProblem()?.let { return it }
        markUsed(session, true)
        // $1 = the command, $2 = session folder, $3 = call id, $4 = timeout. The command is an argument,
        // never spliced into the script, so it needs no quoting. timeout runs in the background so the
        // pidfile holds its pid: Stop (tool_cancel) sends it TERM, and timeout passes that on to the whole job.
        val script = PRELUDE + """
            d="${'$'}{HOME}/sessions/${'$'}{2}"; mkdir -p "${'$'}{d}/.tmux" && cd "${'$'}{d}" || exit 97
            export TMUX_TMPDIR="${'$'}{d}/.tmux"
            p="${'$'}{d}/.bash-${'$'}{3}.pid"
            timeout -k 5 "${'$'}{4}" bash -c "${'$'}{1}" < /dev/null & echo ${'$'}! > "${'$'}{p}"
            wait ${'$'}!; c=${'$'}?; rm -f "${'$'}{p}"
            sync_lock > /dev/null 2>&1
            exit ${'$'}{c}
        """.trimIndent()
        // The phone must not sleep mid-command (a build or test run with the screen off).
        val awake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "weiassist:bash")
        awake.acquire((timeoutSec + 30) * 1000L)
        val r = try {
            execute(arrayOf("-c", script, "bash", command, safe(session), safe(call), timeoutSec.toString()), timeoutSec + 30L)
        } finally {
            if (awake.isHeld) awake.release()
        } ?: return "No answer from Termux after ${timeoutSec + 30}s. Termux may be stopped by the system's battery saver: " +
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

    /**
     * The session was stopped or [deleted]: end its tmux jobs and running commands. Its files in ~/sessions/<id> are
     * kept unless the user asked, when deleting, to remove them too ([files]): they may be a project the user works on
     * for months, so deleting a conversation must not wipe them by default.
     * Then Termux's wake lock is dropped if no session has jobs left.
     */
    suspend fun cleanUp(session: String, deleted: Boolean, files: Boolean = false) {
        if (!used(session) || setupProblem() != null) return
        val id = safe(session)
        if (id.isEmpty()) return // "$HOME/sessions/" would be every session's folder
        // $1 = the session folder's name (only letters, digits, _ and -: it can't climb out of ~/sessions);
        // $2 = 1 to delete the folder after its jobs are ended.
        val script = PRELUDE + """
            [ -n "${'$'}{1}" ] || exit 0
            d="${'$'}{HOME}/sessions/${'$'}{1}"
            if [ -d "${'$'}{d}" ]; then
              TMUX_TMPDIR="${'$'}{d}/.tmux" tmux kill-server 2> /dev/null
              for p in "${'$'}{d}"/.bash-*.pid; do [ -f "${'$'}{p}" ] && kill -TERM "${'$'}(cat "${'$'}{p}")" 2> /dev/null; done
              [ "${'$'}{2}" = 1 ] && rm -rf -- "${'$'}{d}"
            fi
            sync_lock > /dev/null 2>&1
            exit 0
        """.trimIndent()
        val wipe = deleted && files
        // A big folder (node_modules, a build) can take a while to remove.
        execute(arrayOf("-c", script, "bash", id, if (wipe) "1" else "0"), if (wipe) 180 else 30)
        if (deleted) markUsed(session, false)
    }

    /**
     * Copy [path] (relative to session [session]'s folder, or absolute) out of Termux into [dest], at most [maxBytes]:
     * Termux's curl uploads it to a one-shot endpoint in this app ([LoopbackTransfer]). Returns null when it
     * arrived, else what went wrong (for the model).
     */
    suspend fun pull(session: String, path: String, dest: java.io.File, maxBytes: Long): String? {
        setupProblem()?.let { return it }
        // $1 = session folder, $2 = path, $3 = most bytes, $4 = URL. Exit codes: 3 no file, 4 too large, 5 no curl.
        val script = """
            cd "${'$'}{HOME}/sessions/${'$'}{1}" 2> /dev/null || cd "${'$'}{HOME}"
            f="${'$'}{2/#\~/${'$'}HOME}"
            [ -f "${'$'}{f}" ] || exit 3
            [ "${'$'}(stat -c %s "${'$'}{f}")" -le "${'$'}{3}" ] || exit 4
            command -v curl > /dev/null || exit 5
            curl -sS -f --max-time 110 -H "Expect:" --data-binary @"${'$'}{f}" "${'$'}{4}"
        """.trimIndent()
        val (r, ok) = LoopbackTransfer.receive(dest, maxBytes) { url ->
            execute(arrayOf("-c", script, "bash", safe(session), path, maxBytes.toString(), url), 150)
        }
        if (ok) return null
        r ?: return "No answer from Termux."
        if (r.getInt("err", RESULT_OK) != RESULT_OK) return "Termux could not run the command: ${r.getString("errmsg").orEmpty()}"
        return when (r.getInt("exitCode", -1)) {
            3 -> "No such file in Termux: $path"
            4 -> "The file is larger than ${maxBytes / (1024 * 1024)} MB."
            5 -> "curl is missing in Termux: run `pkg install -y curl` with bash, then try again."
            else -> "The file could not be copied out of Termux. ${r.getString("stderr").orEmpty().take(300)}".trim()
        }
    }

    /**
     * Put [file] into session [session]'s folder as inbox/[name] (Termux's curl downloads it from a one-shot endpoint).
     * Returns the path in Termux (relative to the session's folder), or null if it failed.
     */
    suspend fun push(session: String, file: java.io.File, name: String): String? {
        if (setupProblem() != null) return null
        markUsed(session, true)
        val clean = name.replace('/', '_').replace('\\', '_').replace("\u0000", "").trim().trimStart('.').ifEmpty { "file" }.take(120)
        // $1 = session folder, $2 = file name (no slashes), $3 = URL. An existing file of that name gets a number.
        val script = """
            d="${'$'}{HOME}/sessions/${'$'}{1}/inbox"; mkdir -p "${'$'}{d}" && cd "${'$'}{d}" || exit 97
            n="${'$'}{2}"; i=1
            while [ -e "${'$'}{n}" ]; do n="${'$'}{i}-${'$'}{2}"; i=${'$'}((i+1)); done
            command -v curl > /dev/null || exit 5
            curl -sS -f --max-time 110 -o "${'$'}{n}" "${'$'}{3}" && echo "inbox/${'$'}{n}"
        """.trimIndent()
        val (r, ok) = LoopbackTransfer.send(file) { url -> execute(arrayOf("-c", script, "bash", safe(session), clean, url), 150) }
        if (!ok || r == null || r.getInt("exitCode", -1) != 0) return null
        return r.getString("stdout").orEmpty().trim().ifEmpty { null }
    }

    /** What [inspect] found. [problem]: Termux can't run this app's commands (then the rest is unknown). */
    data class Inspection(
        val problem: String? = null,
        val bash: String? = null,
        val curl: Boolean = false,
        val tmux: Boolean = false,
        /** The HTTP status a test request got, or null if it got none. */
        val network: Int? = null,
    ) {
        val tools get() = curl && tmux
        val ready get() = problem == null && tools && network != null
    }

    private val _lastInspection = kotlinx.coroutines.flow.MutableStateFlow<Inspection?>(null)
    /** What [inspect] last found (null before it ran), so Settings can tell whether Linux commands are set up. */
    val lastInspection: kotlinx.coroutines.flow.StateFlow<Inspection?> = _lastInspection

    /** For the setup screen: can commands run, are the basic tools there, and does Termux reach the internet? */
    suspend fun inspect(): Inspection = inspectNow().also { _lastInspection.value = it }

    private suspend fun inspectNow(): Inspection {
        setupProblem()?.let { return Inspection(problem = it) }
        val script = """
            echo "bash=${'$'}{BASH_VERSION%%(*}"
            for t in curl tmux; do command -v "${'$'}{t}" > /dev/null && echo "${'$'}{t}=1"; done
            command -v curl > /dev/null && curl -s -o /dev/null --max-time 8 -w "network=%{http_code}\n" https://wttr.in 2> /dev/null
            exit 0
        """.trimIndent()
        val r = execute(arrayOf("-c", script), 30) ?: return Inspection(problem = NO_ANSWER)
        if (r.getInt("err", RESULT_OK) != RESULT_OK) return Inspection(problem = r.getString("errmsg").orEmpty().ifBlank { "Termux could not run the command." })
        return parseInspection(r.getString("stdout").orEmpty())
    }

    private val _installing = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** [installTools] is running. */
    val installing: kotlinx.coroutines.flow.StateFlow<Boolean> = _installing
    private val _installOutput = kotlinx.coroutines.flow.MutableStateFlow<Pair<Boolean, String>?>(null)
    /** How the last [installTools] went (it worked, its output), or null before one. */
    val installOutput: kotlinx.coroutines.flow.StateFlow<Pair<Boolean, String>?> = _installOutput
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * Install the basic tools (curl, tmux) after upgrading Termux's packages, in the background (it goes on when the
     * setup screen is left). Termux's pkg picks a random mirror and some are years behind ("unmet dependencies"):
     * then current mirrors are pinned and apt-get used, as in dsh-host/termux/setup-proot.sh.
     */
    fun installTools() {
        if (!_installing.compareAndSet(false, true)) return
        scope.launch {
            val awake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "weiassist:termux-tools")
            awake.acquire(INSTALL_SEC * 1000L)
            try {
                val script = """
                    export DEBIAN_FRONTEND=noninteractive
                    o=(-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
                    pkg update -y "${'$'}{o[@]}" && pkg upgrade -y "${'$'}{o[@]}" && pkg install -y "${'$'}{o[@]}" curl tmux && exit 0
                    for m in https://packages-cf.termux.dev/apt/termux-main https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main \
                        https://mirrors.ustc.edu.cn/termux/apt/termux-main https://mirrors.aliyun.com/termux/termux-main; do
                      echo "Trying the Termux mirror ${'$'}{m}"
                      echo "deb ${'$'}{m} stable main" > "${'$'}{PREFIX}/etc/apt/sources.list"
                      apt-get update "${'$'}{o[@]}" && apt-get -y "${'$'}{o[@]}" full-upgrade && apt-get -y "${'$'}{o[@]}" install curl tmux && exit 0
                    done
                    echo "No Termux mirror could install curl and tmux."
                    exit 3
                """.trimIndent()
                val r = execute(arrayOf("-c", script), INSTALL_SEC)
                _installOutput.value = when {
                    r == null -> false to NO_ANSWER
                    r.getInt("err", RESULT_OK) != RESULT_OK -> false to r.getString("errmsg").orEmpty()
                    else -> (r.getInt("exitCode", -1) == 0) to (r.getString("stdout").orEmpty() + "\n" + r.getString("stderr").orEmpty()).trim()
                }
            } finally {
                if (awake.isHeld) awake.release()
                _installing.value = false
            }
        }
    }

    /** Why bash can't run now (for the model and Settings), or null when it can. */
    fun setupProblem(): String? = when {
        !installed() -> "Termux is not installed, so bash is not available. Tell the user: install Termux (from F-Droid or GitHub), then\n$SETUP"
        !runnable() -> "The installed Termux (probably its Google Play build) cannot run commands for other apps. " +
            "Tell the user: install Termux from F-Droid or GitHub (uninstall the Google Play one first; its files are lost), then\n$SETUP"
        !permitted() -> "This app may not run commands in Termux yet. Tell the user:\n$SETUP"
        else -> null
    }

    internal suspend fun execute(args: Array<String>, timeoutSec: Long): android.os.Bundle? {
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
        val intent = command(args, "agent").putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result)
        return try {
            start(intent)?.let { e -> return android.os.Bundle().apply { putInt("err", 1); putString("errmsg", e) } }
            withTimeoutOrNull(timeoutSec * 1000) { done.await() }
        } finally {
            pending.remove(req)
        }
    }

    /** A RUN_COMMAND for bash with [args], in the background. */
    private fun command(args: Array<String>, label: String) = Intent(ACTION_RUN).setComponent(ComponentName(PACKAGE, "com.termux.app.RunCommandService"))
        .putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
        .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
        .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
        .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label)

    /** Hand [intent] to Termux; why it couldn't be, or null. */
    private fun start(intent: Intent): String? = try {
        // Termux's service goes foreground at once; the agent's own foreground service lets us start it.
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        null
    } catch (e: Exception) {
        Log.w(TAG, "RUN_COMMAND failed", e)
        e.message ?: e.javaClass.simpleName
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
        internal const val RESULT_OK = -1 // Activity.RESULT_OK: Termux's "no error"
        private const val INSTALL_SEC = 30 * 60L
        const val NO_ANSWER = "No answer from Termux. Open Termux once, and allow it to run in the background."

        /** [inspect]'s output (key=value lines). Pure, for tests. */
        internal fun parseInspection(out: String): Inspection {
            val v = out.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
            return Inspection(
                bash = v["bash"], curl = v["curl"] == "1", tmux = v["tmux"] == "1",
                network = v["network"]?.toIntOrNull()?.takeIf { it > 0 },
            )
        }

        private const val KEEP = 12_000 // characters of each stream the model sees

        val SETUP = """
            1. In Termux run: mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings
            2. Settings → Apps → this app → Permissions → Additional permissions → "Run commands in Termux environment" → Allow.
            3. For long jobs: allow Termux to autostart and run without battery restrictions.
        """.trimIndent()

        /**
         * Shell function: hold Termux's wake lock while any session has tmux jobs (servers, long builds), so
         * they keep running with the screen off; drop it when none has. Run after each command and clean-up.
         */
        private val PRELUDE = """
            sync_lock() {
              f="${'$'}{HOME}/sessions/.wakelock"; live=
              for s in "${'$'}{HOME}"/sessions/*/.tmux/tmux-*/default; do
                [ -S "${'$'}{s}" ] && tmux -S "${'$'}{s}" has-session 2> /dev/null && { live=1; break; }
              done
              if [ -n "${'$'}{live}" ]; then termux-wake-lock; touch "${'$'}{f}"
              elif [ -f "${'$'}{f}" ]; then termux-wake-unlock; rm -f "${'$'}{f}"; fi
            }

        """.trimIndent() + "\n"

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
