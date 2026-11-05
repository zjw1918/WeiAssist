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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Sets DeepSeek Harness up in Termux from the app: the APK carries the bridge, the profile patch and the setup script
 * (assets dsh/, built from dsh-host/), which [start] writes into ~/.weiassist-dsh/materials and runs there. The script
 * runs for many minutes (Ubuntu, Node, dsh), so it is followed through the files it leaves (see setup-proot.sh):
 * [status] reads them, and works the same after the app was closed meanwhile.
 *
 * A setup is current when it ended with this APK's [stamp] (a hash of the materials): an app with a newer bridge asks
 * for the setup again (an update: only the changed parts take long).
 *
 * dsh's own version: by default the one this APK's bridge was tested with ([testedVersion], pinned in setup-proot.sh).
 * The user may install a newer one from npm ([latestVersion], [start] with a version); the choice is kept
 * ([chosenVersion]), so a later setup (an app update) doesn't quietly go back to the tested one.
 */
class DshSetup(context: Context, private val termux: Termux, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("dsh", Context.MODE_PRIVATE)

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
        /** The dsh version installed in Termux, or null if none. */
        val version: String? = null,
    ) {
        val ready get() = termuxProblem == null && current
    }

    private val _launching = MutableStateFlow(false)
    /** [start] handed the script to Termux and it hasn't ended yet (it may not have written its pid yet). */
    val launching: StateFlow<Boolean> = _launching.asStateFlow()

    /** This APK's materials: the files the setup writes into Termux (assets dsh/<name>). */
    private val materials by lazy { FILES.associateWith { f -> app.assets.open("dsh/$f").use { it.readBytes().toString(Charsets.UTF_8) } } }

    /** The dsh version this APK's bridge and profile patch were tested with (setup-proot.sh's DSH_TESTED). */
    val testedVersion: String by lazy { TESTED_LINE.find(materials.getValue("setup-proot.sh"))!!.groupValues[1] }

    /** The dsh version the user chose to install instead of [testedVersion], or null for the tested one. */
    val chosenVersion: String? get() = prefs.getString(KEY_VERSION, null)?.takeIf { it != testedVersion }

    /**
     * The newest dsh release on npm (the higher of its `latest` and `next` tags; never alpha), or null if the registry
     * can't be reached. [mirrors]: ask npmmirror.com.
     */
    suspend fun latestVersion(mirrors: Boolean): String? {
        val tags = registryGet(mirrors, "-/package/@deepseek-ai/dsh/dist-tags")?.second ?: return null
        return listOf("latest", "next").mapNotNull { tags.optString(it).ifBlank { null } }.maxWithOrNull(::compareVersions)
    }

    /** What npm says about a version the user typed. */
    sealed interface Lookup {
        /** It exists: this version (a tag such as `alpha` resolved to the version it names now). */
        data class Found(val version: String) : Lookup
        data object NotFound : Lookup
        /** Not a version or a tag (only letters, digits, `.`, `-`, `+`, `_`). */
        data object Invalid : Lookup
        data object Unreachable : Lookup
    }

    /** Look [spec] (a version such as 0.2.1-rc.5, or a dist-tag) up on npm. [mirrors]: ask npmmirror.com. */
    suspend fun lookUp(spec: String, mirrors: Boolean): Lookup {
        val v = spec.trim().removePrefix("v").takeIf { SPEC.matches(it) } ?: return Lookup.Invalid
        val (code, json) = registryGet(mirrors, "@deepseek-ai/dsh/$v") ?: return Lookup.Unreachable
        return when {
            code == 404 -> Lookup.NotFound
            json?.optString("version").isNullOrBlank() -> Lookup.Unreachable
            else -> Lookup.Found(json!!.getString("version"))
        }
    }

    /** GET [path] from the npm registry: the status and, on 200, the JSON; null if it can't be reached. */
    private suspend fun registryGet(mirrors: Boolean, path: String): Pair<Int, JSONObject?>? = withContext(Dispatchers.IO) {
        try {
            val registry = if (mirrors) "https://registry.npmmirror.com" else "https://registry.npmjs.org"
            val c = URL("$registry/$path").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            try {
                val code = c.responseCode
                code to if (code == 200) JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) else null
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "npm registry $path", e)
            null
        }
    }

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
            echo "version=${'$'}(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' "${'$'}{d}/linux/run/node_modules/@deepseek-ai/dsh/package.json" 2> /dev/null | head -1)"
            echo "$LOG_MARK"
            [ -f "${'$'}{d}/setup.log" ] && tail -c 12000 "${'$'}{d}/setup.log"
            exit 0
        """.trimIndent()
        val r = termux.execute(arrayOf("-c", script), 20) ?: return Status(termuxProblem = Termux.NO_ANSWER)
        if (r.getInt("err", Termux.RESULT_OK) != Termux.RESULT_OK) return Status(termuxProblem = r.getString("errmsg").orEmpty().ifBlank { "Termux could not run the command." })
        return parse(r.getString("stdout").orEmpty(), stamp)
    }

    /**
     * Write the materials into Termux and run the setup there, in the background (it goes on if the user leaves the
     * app). [mirrors]: download Node and dsh's packages from npmmirror.com (faster in mainland China).
     * [version]: the dsh version to install from now on ([testedVersion] goes back to it); by default the last choice.
     * Returns false if a setup was already running.
     */
    fun start(mirrors: Boolean, version: String? = chosenVersion): Boolean {
        if (!_launching.compareAndSet(false, true)) return false
        val dsh = version?.takeIf { it != testedVersion }
        prefs.edit().apply { if (dsh == null) remove(KEY_VERSION) else putString(KEY_VERSION, dsh) }.apply()
        scope.launch(Dispatchers.IO) {
            // The phone must not sleep halfway through the downloads (Termux runs it, but the CPU is the phone's).
            val awake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "weiassist:dsh-setup")
            awake.acquire(TIMEOUT_SEC * 1000L)
            try {
                // $1… are the files' contents, never spliced into the script; $5 the stamp; $6, $7 the mirrors; $8 the
                // dsh version (empty: the tested one).
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
                    [ -n "${'$'}{8}" ] && export DSH_VERSION="${'$'}{8}"
                    exec bash "${'$'}{m}/setup-proot.sh" "${'$'}{m}" "${'$'}{5}"
                """.trimIndent()
                val args = arrayOf(
                    "-c", script, "bash",
                    materials.getValue("setup-proot.sh"), materials.getValue("index.js"),
                    materials.getValue("package.json"), materials.getValue("cordis.patch.yml"), stamp,
                    if (mirrors) "https://npmmirror.com/mirrors/node" else "",
                    if (mirrors) "https://registry.npmmirror.com" else "",
                    dsh.orEmpty(),
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
        private const val KEY_VERSION = "version"
        private val SPEC = Regex("""[A-Za-z0-9][A-Za-z0-9._+-]{0,63}""")
        private val TESTED_LINE = Regex("""(?m)^DSH_TESTED="([^"]+)"$""")
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
                steps = steps, log = log, version = values["version"]?.ifBlank { null },
            )
        }

        /** Semver order, prereleases included (0.2.2-rc.1 < 0.2.2-rc.10 < 0.2.2 < 0.2.10). */
        fun compareVersions(a: String, b: String): Int {
            fun parts(s: String) = s.split('.').map { p -> p.toIntOrNull()?.let { Id(it, null) } ?: Id(null, p) }
            fun cmp(x: List<Id>, y: List<Id>): Int {
                for (i in 0 until minOf(x.size, y.size)) {
                    val (xn, xs) = x[i]; val (yn, ys) = y[i]
                    val c = when {
                        xn != null && yn != null -> xn.compareTo(yn)
                        xn != null -> -1 // numeric identifiers sort before others
                        yn != null -> 1
                        else -> xs!!.compareTo(ys!!)
                    }
                    if (c != 0) return c
                }
                return x.size.compareTo(y.size)
            }
            val (ar, ap) = a.trim().removePrefix("v").split('-', limit = 2).let { it[0] to it.getOrNull(1) }
            val (br, bp) = b.trim().removePrefix("v").split('-', limit = 2).let { it[0] to it.getOrNull(1) }
            cmp(parts(ar), parts(br)).let { if (it != 0) return it }
            return when {
                ap == null && bp == null -> 0
                ap == null -> 1 // a release comes after its prereleases
                bp == null -> -1
                else -> cmp(parts(ap), parts(bp))
            }
        }

        private data class Id(val n: Int?, val s: String?)

        /** Terminal output as text: colors removed, and a progress bar's redraws (\r) down to what it showed last. */
        internal fun cleanLog(s: String): String = s.replace(ANSI, "").split('\n') // lines() would split at \r too
            .map { l -> l.trimEnd('\r').substringAfterLast('\r') }
            .joinToString("\n").replace(BLANK_LINES, "\n\n").trim()
    }
}
