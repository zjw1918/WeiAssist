package com.lanqi.weiassist.agent

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Sets DeepSeek Harness up in Termux from the app: the APK carries the bridge, the profile patch and the setup script
 * (assets dsh/, built from dsh-host/), which [start] writes into ~/.weiassist-dsh/materials and runs there. The script
 * runs for many minutes (Ubuntu, Node, dsh), so it is followed through the files it leaves (see setup-proot.sh):
 * [status] reads them, and works the same after the app was closed meanwhile.
 *
 * A setup is current when it ended with this APK's [stamp] (a hash of the materials): an app with a newer bridge asks
 * for the setup again (an update: only the changed parts take long).
 */
class DshSetup(context: Context, private val termux: Termux, private val scope: CoroutineScope) {
    private val app = context.applicationContext

    /** The script's steps, in order, as its log names them ("== [id] …"). */
    enum class Step(val id: String) { Termux("termux"), Ubuntu("ubuntu"), Node("node"), Dsh("dsh"), Profile("profile"), Check("check") }

    enum class StepState { Pending, Running, Done, Failed }

    /** What Termux says about the setup. */
    data class Status(
        /** Why Termux can't run commands for this app, or null when it can (then the rest is known). */
        val termuxProblem: String? = null,
        /** The setup script is running now. */
        val running: Boolean = false,
        /** Set up with this APK's materials: dsh can run. */
        val current: Boolean = false,
        /** Set up once, but with other materials (an older app): run the setup again. */
        val outdated: Boolean = false,
        /** The last run failed, with this exit code. */
        val failedCode: Int? = null,
        val steps: Map<Step, StepState> = Step.entries.associateWith { StepState.Pending },
        /** The end of the last run's output, cleaned up for display. */
        val log: String = "",
    ) {
        val ready get() = termuxProblem == null && current
    }

    private val _launching = MutableStateFlow(false)
    /** [start] handed the script to Termux and it hasn't ended yet (it may not have written its pid yet). */
    val launching: StateFlow<Boolean> = _launching.asStateFlow()

    /** This APK's materials: the files the setup writes into Termux (assets dsh/<name>). */
    private val materials by lazy { FILES.associateWith { f -> app.assets.open("dsh/$f").use { it.readBytes().toString(Charsets.UTF_8) } } }

    /** A hash of [materials]: the setup writes it to ~/.weiassist-dsh/installed when it worked. */
    val stamp: String by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        FILES.forEach { md.update(it.toByteArray()); md.update(0); md.update(materials.getValue(it).toByteArray()); md.update(0) }
        md.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    /** Why dsh can't run now, or null when it can. For logs and the model; Settings shows its own words. */
    suspend fun problem(): String? {
        termux.setupProblem()?.let { return it }
        val s = status()
        return when {
            s.termuxProblem != null -> s.termuxProblem
            s.current -> null
            s.running -> "DeepSeek Harness is being set up in Termux. Wait for it to finish (Settings → AI engine)."
            s.outdated -> "DeepSeek Harness needs an update for this version of the app: run its setup again in Settings → AI engine."
            else -> NOT_SET_UP
        }
    }

    /** Ask Termux where the setup stands. */
    suspend fun status(): Status {
        termux.setupProblem()?.let { return Status(termuxProblem = it) }
        val script = """
            d="${'$'}{HOME}/.weiassist-dsh"
            p="${'$'}(cat "${'$'}{d}/setup.pid" 2> /dev/null)"
            if [ -n "${'$'}{p}" ] && grep -q setup-proot "/proc/${'$'}{p}/cmdline" 2> /dev/null; then echo running=1; else echo running=0; fi
            echo "installed=${'$'}(cat "${'$'}{d}/installed" 2> /dev/null)"
            [ -x "${'$'}{d}/linux/run/node_modules/.bin/dsh" ] && command -v proot-distro > /dev/null && echo dsh=1 || echo dsh=0
            echo "$LOG_MARK"
            [ -f "${'$'}{d}/setup.log" ] && tail -c 12000 "${'$'}{d}/setup.log"
            exit 0
        """.trimIndent()
        val r = termux.execute(arrayOf("-c", script), 20) ?: return Status(termuxProblem = NO_ANSWER)
        if (r.getInt("err", Termux.RESULT_OK) != Termux.RESULT_OK) return Status(termuxProblem = r.getString("errmsg").orEmpty().ifBlank { "Termux could not run the command." })
        return parse(r.getString("stdout").orEmpty(), stamp)
    }

    /**
     * Write the materials into Termux and run the setup there, in the background (it goes on if the user leaves the
     * app). [mirrors]: download Node and dsh's packages from npmmirror.com (faster in mainland China).
     * Returns false if a setup was already running.
     */
    fun start(mirrors: Boolean): Boolean {
        if (!_launching.compareAndSet(false, true)) return false
        scope.launch(Dispatchers.IO) {
            // The phone must not sleep halfway through the downloads (Termux runs it, but the CPU is the phone's).
            val awake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "weiassist:dsh-setup")
            awake.acquire(TIMEOUT_SEC * 1000L)
            try {
                // $1… are the files' contents, never spliced into the script; $5 the stamp; $6, $7 the mirrors.
                val script = """
                    d="${'$'}{HOME}/.weiassist-dsh"; m="${'$'}{d}/materials"; mkdir -p "${'$'}{m}" || exit 97
                    p="${'$'}(cat "${'$'}{d}/setup.pid" 2> /dev/null)"
                    [ -n "${'$'}{p}" ] && grep -q setup-proot "/proc/${'$'}{p}/cmdline" 2> /dev/null && { echo "A setup is already running."; exit 10; }
                    printf '%s' "${'$'}{1}" > "${'$'}{m}/setup-proot.sh"
                    printf '%s' "${'$'}{2}" > "${'$'}{m}/index.js"
                    printf '%s' "${'$'}{3}" > "${'$'}{m}/package.json"
                    printf '%s' "${'$'}{4}" > "${'$'}{m}/cordis.patch.yml"
                    rm -f "${'$'}{d}/installed"
                    [ -n "${'$'}{6}" ] && export NODE_MIRROR="${'$'}{6}"
                    [ -n "${'$'}{7}" ] && export NPM_REGISTRY="${'$'}{7}"
                    exec bash "${'$'}{m}/setup-proot.sh" "${'$'}{m}" "${'$'}{5}"
                """.trimIndent()
                val args = arrayOf(
                    "-c", script, "bash",
                    materials.getValue("setup-proot.sh"), materials.getValue("index.js"),
                    materials.getValue("package.json"), materials.getValue("cordis.patch.yml"), stamp,
                    if (mirrors) "https://npmmirror.com/mirrors/node" else "",
                    if (mirrors) "https://registry.npmmirror.com" else "",
                )
                val r = termux.execute(args, TIMEOUT_SEC)
                Log.i(TAG, "setup ended: exit ${r?.getInt("exitCode", -1)} ${r?.getString("errmsg").orEmpty()}")
            } catch (e: Exception) {
                Log.w(TAG, "setup failed to start", e)
            } finally {
                if (awake.isHeld) awake.release()
                _launching.value = false
            }
        }
        return true
    }

    companion object {
        private const val TAG = "DshSetup"
        /** assets/dsh/: written by dsh-host/build.mjs. */
        private val FILES = listOf("setup-proot.sh", "index.js", "package.json", "cordis.patch.yml")
        /** The longest a setup may run (slow networks: Ubuntu, Node and ~600 npm packages). */
        private const val TIMEOUT_SEC = 2 * 60 * 60L
        private const val LOG_MARK = "--- log ---"
        const val NO_ANSWER = "No answer from Termux. Open Termux once, and allow it to run in the background."
        const val NOT_SET_UP = "DeepSeek Harness is not set up yet: set it up in Settings → AI engine."
        private val STEP_LINE = Regex("""^== \[([a-z]+)](?: (.*))?$""")
        private val BLANK_LINES = Regex("""\n\s*\n(\s*\n)+""")
        private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]|\u001B[()][A-Z0-9]""")

        /** Read what the status script printed (key=value lines, then the log). Pure, for tests. */
        internal fun parse(out: String, stamp: String): Status {
            val head = out.substringBefore(LOG_MARK)
            val values = head.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
            val running = values["running"] == "1"
            val installed = values["installed"].orEmpty()
            val current = !running && installed == stamp && values["dsh"] == "1"
            val log = cleanLog(out.substringAfter(LOG_MARK, ""))
            val seen = mutableListOf<String>()
            var failed: Int? = null
            var done = false
            log.lines().forEach { l ->
                val m = STEP_LINE.matchEntire(l.trim()) ?: return@forEach
                when (val id = m.groupValues[1]) {
                    "done" -> done = true
                    "failed" -> failed = m.groupValues[2].trim().toIntOrNull() ?: 1
                    else -> seen += id
                }
            }
            val last = seen.lastOrNull()
            val steps = Step.entries.associateWith { step ->
                when {
                    current -> StepState.Done
                    step.id !in seen -> StepState.Pending
                    step.id != last -> StepState.Done
                    running -> StepState.Running
                    failed != null -> StepState.Failed
                    done -> StepState.Done
                    else -> StepState.Pending // ended without saying how (killed): run it again
                }
            }
            return Status(
                running = running, current = current,
                outdated = !running && !current && (installed.isNotEmpty() || values["dsh"] == "1"),
                failedCode = if (running || current) null else failed,
                steps = steps, log = log,
            )
        }

        /** Terminal output as text: colors removed, and a progress bar's redraws (\r) down to what it showed last. */
        internal fun cleanLog(s: String): String = s.replace(ANSI, "").split('\n') // lines() would split at \r too
            .map { l -> l.trimEnd('\r').substringAfterLast('\r') }
            .joinToString("\n").replace(BLANK_LINES, "\n\n").trim()
    }
}
