package com.lanqi.weiassist.agent

import com.lanqi.weiassist.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.FileSystems

/**
 * The file tools (read, write, edit, ls, glob, grep): a session's own folder, and the phone's storage when the user
 * allows it ([Level], Settings → File access). Paths:
 * - relative (and ~/… or Termux's absolute paths, with Termux): the session's folder. For a session with Termux, while
 *   Termux works, that is its folder in Termux (~/sessions/<id>, where bash runs and send_file looks); else a folder
 *   of its own in the app (so the tools work before Termux is set up, or without it).
 * - /sdcard/… (or /storage/emulated/0/…): the phone's shared storage, only at [Level.StorageRead] or above.
 * The app's own files stay out of reach: an app-folder path may not leave the session's folder.
 */
class FileTools(
    /** The user's choice (read on every call). */
    private val level: () -> Level,
    private val summary: (String) -> SessionSummary?,
    /** The session's folder in the app (used without Termux). */
    private val workspace: (String) -> File,
    /** Shared storage's root, and whether the app may use it now (All files access). */
    private val storageRoot: File,
    private val storageGranted: () -> Boolean,
    private val termux: TermuxFiles,
    /** An image file as base64 JPEG scaled for the model, or null if it isn't one Android can decode. */
    private val image: (File) -> String? = { null },
    /** Scratch space for files copied out of Termux. */
    private val scratch: File,
) {
    enum class Level(val id: String) {
        Off("off"), Session("session"), StorageRead("storage_read"), StorageWrite("storage_write");
        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Session }
    }

    /** How Termux reaches its own files: run a script, copy a file out, put one in. */
    interface TermuxFiles {
        fun problem(): String?
        /**
         * Run [script] with bash ($1… = [args], $SESSION = the session's folder name under ~/sessions): exit code and
         * output, or null if Termux didn't answer.
         */
        suspend fun script(session: String, script: String, args: List<String>): Pair<Int, String>?
        /** Copy [path] out into [dest]; null when it arrived, else why not. */
        suspend fun pull(session: String, path: String, dest: File, maxBytes: Long): String?
        /** Put [file] at [path] (folders made, an existing file replaced); null when done, else why not. */
        suspend fun push(session: String, file: File, path: String): String?
    }

    /** Where a path leads. [shown]: how results name it. */
    private sealed interface Place {
        val shown: String
        data class Local(val root: File, val file: File, val writable: Boolean, override val shown: String, val storage: Boolean) : Place
        data class InTermux(val path: String, override val shown: String) : Place
    }

    private class Refused(message: String) : Exception(message)

    suspend fun call(session: String, name: String, args: JSONObject): ToolResult = try {
        val s = summary(session) ?: throw Refused("This session no longer exists.")
        if (level() == Level.Off) throw Refused("File access is off. The user can turn it on in Settings → File access.")
        when (name) {
            "read" -> read(session, place(s, args.optString("path")), args.optInt("offset", 1), args.optInt("limit", DEFAULT_LINES))
            "write" -> write(session, writable(place(s, args.optString("path"))), args.optString("content"))
            "edit" -> edit(session, writable(place(s, args.optString("path"))), args.optString("old_string"), args.optString("new_string"), args.optBoolean("replace_all"))
            "ls" -> ls(session, place(s, args.optString("path").ifBlank { "." }))
            "glob" -> glob(session, place(s, args.optString("path").ifBlank { "." }), args.optString("pattern"))
            "grep" -> grep(session, place(s, args.optString("path").ifBlank { "." }), args.optString("pattern"), args.optString("glob"), args.optBoolean("ignore_case"))
            else -> throw Refused("No file tool $name.")
        }
    } catch (e: Refused) {
        ToolResult(e.message.orEmpty(), isError = true)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult("$name failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    // ---- where a path leads -------------------------------------------------------------------------

    private fun place(s: SessionSummary, raw: String): Place {
        val p = raw.trim().ifEmpty { throw Refused("Give a path.") }
        val storagePrefix = STORAGE_PREFIXES.firstOrNull { p == it || p.startsWith("$it/") }
        if (storagePrefix != null) {
            val lv = level()
            if (lv < Level.StorageRead) throw Refused("The phone's storage is off for the assistant. The user can allow it in Settings → File access.")
            if (!storageGranted()) throw Refused("The app may not read the phone's storage yet: the user must allow \"All files access\" for it (Settings → File access).")
            val rel = p.removePrefix(storagePrefix).trimStart('/')
            return Place.Local(storageRoot, inside(storageRoot, rel, p), lv == Level.StorageWrite, "/sdcard/$rel".trimEnd('/'), storage = true)
        }
        if (s.termux && termux.problem() == null) {
            // DeepSeek Harness sees Termux's home as /root (Ubuntu in proot): the same files.
            val t = if (p == "/root" || p.startsWith("/root/")) "~" + p.removePrefix("/root") else p
            return Place.InTermux(t, t)
        }
        if (p.startsWith("/") || p.startsWith("~")) throw Refused(
            "$p is outside this session's folder. Use a path relative to it" +
                (if (level() >= Level.StorageRead) ", or /sdcard/… for the phone's storage." else "."),
        )
        val root = workspace(s.id).apply { mkdirs() }
        return Place.Local(root, inside(root, p, p), writable = true, shown = p, storage = false)
    }

    /** [rel] under [root], refusing anything that leaves it (.., links). */
    private fun inside(root: File, rel: String, shown: String): File {
        val base = root.canonicalFile
        val f = File(base, rel).canonicalFile
        if (f != base && !f.path.startsWith(base.path + File.separator)) throw Refused("$shown is outside the folders the assistant may use.")
        return f
    }

    private fun writable(p: Place): Place {
        if (p is Place.Local && !p.writable) throw Refused("The phone's storage is read-only for the assistant. The user can allow changes in Settings → File access.")
        return p
    }

    // ---- the tools ----------------------------------------------------------------------------------

    private suspend fun read(session: String, p: Place, offset: Int, limit: Int): ToolResult {
        val f = local(session, p) ?: return ToolResult("No such file: ${p.shown}", isError = true)
        if (f.isDirectory) return ToolResult("${p.shown} is a folder: use ls.", isError = true)
        if (!f.isFile) return ToolResult("No such file: ${p.shown}", isError = true)
        try {
            if (f.length() > MAX_READ_BYTES) return ToolResult("${p.shown} is ${f.length() / 1024 / 1024} MB, too large to read (at most ${MAX_READ_BYTES / 1024 / 1024} MB).", isError = true)
            if (f.extension.lowercase() in IMAGES) {
                image(f)?.let { return ToolResult("Image ${p.shown} (${f.length() / 1024} KB).", image = it) }
            }
            val bytes = withContext(Dispatchers.IO) { f.readBytes() }
            if (bytes.take(8_000).any { it == 0.toByte() }) return ToolResult("${p.shown} is a binary file (${bytes.size} bytes): it can't be read as text.", isError = true)
            return ToolResult(window(String(bytes, Charsets.UTF_8), offset, limit, p.shown))
        } finally {
            if (p is Place.InTermux) f.delete()
        }
    }

    private suspend fun write(session: String, p: Place, content: String): ToolResult {
        if (content.length > MAX_WRITE_CHARS) return ToolResult("Too much to write at once (${content.length} characters).", isError = true)
        put(session, p, content)
        return ToolResult("Wrote ${p.shown} (${content.removeSuffix("\n").lines().size} lines, ${content.toByteArray().size} bytes).")
    }

    private suspend fun edit(session: String, p: Place, old: String, new: String, all: Boolean): ToolResult {
        val f = local(session, p)?.takeIf { it.isFile } ?: return ToolResult("No such file: ${p.shown}", isError = true)
        val text = try { withContext(Dispatchers.IO) { f.readText() } } finally { if (p is Place.InTermux) f.delete() }
        val (changed, count) = replace(text, old, new, all).getOrElse { return ToolResult("${it.message} (${p.shown})", isError = true) }
        put(session, p, changed)
        return ToolResult("Edited ${p.shown}: replaced $count ${if (count == 1) "occurrence" else "occurrences"}.")
    }

    private suspend fun ls(session: String, p: Place): ToolResult = when (p) {
        is Place.Local -> withContext(Dispatchers.IO) {
            val d = p.file
            when {
                !d.exists() -> ToolResult("No such folder: ${p.shown}", isError = true)
                !d.isDirectory -> ToolResult("${p.shown} is a file (${d.length()} bytes).")
                else -> {
                    val entries = d.listFiles().orEmpty().sortedBy { it.name.lowercase() }
                    ToolResult(listing(p.shown, entries.map { Triple(it.name, it.isDirectory, it.length()) }))
                }
            }
        }
        is Place.InTermux -> {
            val (code, out) = termuxScript(session, LS, listOf(p.path))
            when (code) {
                0 -> ToolResult(listing(p.shown, out.lines().filter { it.isNotBlank() }.map { l ->
                    val (type, size, name) = l.split(' ', limit = 3).let { Triple(it[0], it.getOrElse(1) { "0" }, it.getOrElse(2) { "" }) }
                    Triple(name, type == "d", size.toLongOrNull() ?: 0)
                }))
                3 -> ToolResult("No such folder: ${p.shown}", isError = true)
                4 -> ToolResult("${p.shown} is a file.")
                else -> ToolResult(out.ifBlank { "ls failed ($code)" }, isError = true)
            }
        }
    }

    private suspend fun glob(session: String, p: Place, pattern: String): ToolResult {
        if (pattern.isBlank()) return ToolResult("Give a pattern, e.g. *.go or src/**/*.kt.", isError = true)
        val found = when (p) {
            is Place.Local -> withContext(Dispatchers.IO) { localGlob(p, pattern) }
            is Place.InTermux -> {
                val byPath = '/' in pattern
                val pat = if (byPath) pattern.replace("**/", "*").replace("**", "*") else pattern
                val (code, out) = termuxScript(session, GLOB, listOf(p.path, pat, if (byPath) "path" else "name"))
                if (code == 3) return ToolResult("No such folder: ${p.shown}", isError = true)
                out.lines().filter { it.isNotBlank() }
            }
        }
        if (found.isEmpty()) return ToolResult("No files match $pattern in ${p.shown}.")
        val shown = found.take(MAX_RESULTS)
        return ToolResult(shown.joinToString("\n") + if (found.size > MAX_RESULTS) "\n(more than $MAX_RESULTS: narrow the pattern or the folder)" else "")
    }

    private suspend fun grep(session: String, p: Place, pattern: String, glob: String, ignoreCase: Boolean): ToolResult {
        if (pattern.isEmpty()) return ToolResult("Give a pattern (a regular expression).", isError = true)
        val hits = when (p) {
            is Place.Local -> withContext(Dispatchers.IO) {
                val regex = runCatching { Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()) }
                    .getOrElse { return@withContext null }
                localGrep(p, regex, glob)
            } ?: return ToolResult("Not a valid regular expression: $pattern", isError = true)
            is Place.InTermux -> {
                val (code, out) = termuxScript(session, GREP, listOf(p.path, pattern, if (ignoreCase) "-i" else "", glob))
                if (code == 3) return ToolResult("No such file or folder: ${p.shown}", isError = true)
                if (code == 2) return ToolResult(out.ifBlank { "grep failed" }, isError = true)
                out.lines().filter { it.isNotBlank() }.map { it.take(MAX_LINE) }
            }
        }
        if (hits.isEmpty()) return ToolResult("No matches for $pattern in ${p.shown}.")
        return ToolResult(hits.take(MAX_RESULTS).joinToString("\n") + if (hits.size > MAX_RESULTS) "\n(more than $MAX_RESULTS matches: narrow the pattern or the folder)" else "")
    }

    // ---- files in the app or storage, and copies of Termux's ------------------------------------------

    /** The file to read: itself, or a copy out of Termux (deleted by the caller); null if there is none. */
    private suspend fun local(session: String, p: Place): File? = when (p) {
        is Place.Local -> p.file.takeIf { it.exists() }
        is Place.InTermux -> {
            termux.problem()?.let { throw Refused(it) }
            val dest = File(scratch, java.util.UUID.randomUUID().toString()).apply { parentFile?.mkdirs() }
            val why = termux.pull(session, p.path, dest, MAX_READ_BYTES)
            if (why == null) dest else { dest.delete(); if (why.startsWith("No such file")) null else throw Refused(why) }
        }
    }

    private suspend fun put(session: String, p: Place, content: String) {
        when (p) {
            is Place.Local -> withContext(Dispatchers.IO) {
                if (p.file.isDirectory) throw Refused("${p.shown} is a folder.")
                p.file.parentFile?.mkdirs()
                // Written beside it, then moved over it: a failure leaves the old file whole.
                val tmp = File(p.file.parentFile, ".${p.file.name}.writing")
                tmp.writeText(content)
                if (!tmp.renameTo(p.file)) { p.file.writeText(content); tmp.delete() }
            }
            is Place.InTermux -> {
                termux.problem()?.let { throw Refused(it) }
                val tmp = File(scratch, java.util.UUID.randomUUID().toString()).apply { parentFile?.mkdirs(); writeText(content) }
                try { termux.push(session, tmp, p.path)?.let { throw Refused(it) } } finally { tmp.delete() }
            }
        }
    }

    private suspend fun termuxScript(session: String, script: String, args: List<String>): Pair<Int, String> {
        termux.problem()?.let { throw Refused(it) }
        return termux.script(session, script, args) ?: throw Refused("No answer from Termux.")
    }

    private fun walk(p: Place.Local): Sequence<File> = p.file.walkTopDown()
        // Hidden folders (.git, caches), and apps' private storage (Android/data, unreadable anyway).
        .onEnter { d -> d == p.file || !(d.name.startsWith(".") || (p.storage && d.parentFile == p.root && d.name == "Android")) }
        .filter { it.isFile }
        .take(MAX_SCANNED)

    private fun localGlob(p: Place.Local, pattern: String): List<String> {
        val fs = FileSystems.getDefault()
        val matchers = buildList {
            add(fs.getPathMatcher("glob:$pattern"))
            if (pattern.startsWith("**/")) add(fs.getPathMatcher("glob:" + pattern.removePrefix("**/")))
        }
        val byPath = '/' in pattern
        return walk(p).filter { f ->
            val rel = f.relativeTo(p.file).invariantSeparatorsPath
            val target = java.nio.file.Paths.get(if (byPath) rel else f.name)
            matchers.any { it.matches(target) }
        }.map { shownPath(p, it) }.take(MAX_RESULTS + 1).toList()
    }

    private fun localGrep(p: Place.Local, regex: Regex, glob: String): List<String> {
        val only = glob.takeIf { it.isNotBlank() }?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val files = if (p.file.isFile) sequenceOf(p.file) else walk(p)
        val hits = mutableListOf<String>()
        for (f in files) {
            if (only != null && !only.matches(java.nio.file.Paths.get(f.name))) continue
            if (f.length() > MAX_GREP_BYTES) continue
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            if (bytes.take(8_000).any { it == 0.toByte() }) continue
            String(bytes, Charsets.UTF_8).lineSequence().forEachIndexed { i, line ->
                if (regex.containsMatchIn(line)) hits += "${shownPath(p, f)}:${i + 1}: ${line.trim().take(MAX_LINE)}"
            }
            if (hits.size > MAX_RESULTS) break
        }
        return hits
    }

    /** [f] as the model named its folder: relative to it, or under /sdcard. */
    private fun shownPath(p: Place.Local, f: File): String {
        val rel = f.relativeTo(p.file).invariantSeparatorsPath
        val base = p.shown.trimEnd('/')
        return if (base == "." || base.isEmpty()) rel else "$base/$rel"
    }

    companion object {
        const val SETTING = "fileAccess"
        const val DEFAULT_LINES = 2000
        private const val MAX_READ_BYTES = 10L * 1024 * 1024
        private const val MAX_WRITE_CHARS = 5_000_000
        private const val MAX_OUTPUT_CHARS = 50_000
        private const val MAX_LINE = 500
        private const val MAX_RESULTS = 200
        private const val MAX_SCANNED = 20_000
        private const val MAX_GREP_BYTES = 2L * 1024 * 1024
        private val STORAGE_PREFIXES = listOf("/sdcard", "/storage/emulated/0", "/storage/self/primary")
        private val IMAGES = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")

        /** The tools of the "files" set, so the catalog and the router agree. */
        val NAMES = setOf("read", "write", "edit", "ls", "glob", "grep")

        /** Lines [offset]… (1-based) of [text], at most [limit] and [MAX_OUTPUT_CHARS], saying how to read on. */
        fun window(text: String, offset: Int, limit: Int, shown: String): String {
            if (text.isEmpty()) return "($shown is empty.)"
            val lines = text.removeSuffix("\n").split('\n')
            val from = offset.coerceAtLeast(1)
            if (from > lines.size) return "($shown has ${lines.size} lines; offset $from is past its end.)"
            val out = StringBuilder()
            var last = from - 1
            for (i in from - 1 until minOf(lines.size, from - 1 + limit.coerceIn(1, 10_000))) {
                val line = lines[i].let { if (it.length > 2_000) it.take(2_000) + "… (line cut)" else it }
                if (out.length + line.length > MAX_OUTPUT_CHARS && out.isNotEmpty()) break
                out.append(line).append('\n')
                last = i + 1
            }
            if (last < lines.size) out.append("\n(Lines $from–$last of ${lines.size}. Read on with offset=${last + 1}.)")
            return out.toString().trimEnd('\n')
        }

        /** [old] → [new] in [text]: exactly once, unless [all]. Fails with why when it can't. */
        fun replace(text: String, old: String, new: String, all: Boolean): Result<Pair<String, Int>> {
            if (old.isEmpty()) return Result.failure(IllegalArgumentException("old_string is empty: give the exact text to replace"))
            if (old == new) return Result.failure(IllegalArgumentException("old_string and new_string are the same"))
            val count = countOf(text, old)
            return when {
                count == 0 -> Result.failure(IllegalArgumentException("old_string was not found: it must match the file exactly, spaces and line breaks included"))
                count > 1 && !all -> Result.failure(IllegalArgumentException("old_string occurs $count times: add surrounding lines so it is unique, or set replace_all"))
                else -> Result.success((if (all) text.replace(old, new) else text.replaceFirst(old, new)) to (if (all) count else 1))
            }
        }

        private fun countOf(text: String, s: String): Int {
            var n = 0; var i = text.indexOf(s)
            while (i >= 0) { n++; i = text.indexOf(s, i + s.length) }
            return n
        }

        private fun listing(shown: String, entries: List<Triple<String, Boolean, Long>>): String {
            if (entries.isEmpty()) return "$shown is empty."
            val shownEntries = entries.take(500)
            return shownEntries.joinToString("\n") { (name, dir, size) -> if (dir) "$name/" else "$name  ($size bytes)" } +
                if (entries.size > 500) "\n(${entries.size - 500} more)" else ""
        }

        /** Termux scripts. $1 = the path (~ for its home, else from the session's folder). */
        private const val CD = "cd \"\${HOME}/sessions/\${SESSION}\" 2> /dev/null || cd \"\${HOME}\"\nf=\"\${1/#\\~/\${HOME}}\"\n"
        /** Each entry as "<type d/f> <size> <name>". 3: no such folder, 4: a file. */
        private const val LS = CD + "[ -d \"\${f}\" ] || { [ -e \"\${f}\" ] && exit 4; exit 3; }\n" +
            "find \"\${f}\" -mindepth 1 -maxdepth 1 -printf '%y %s %f\\n' | sort -k3 | head -n 600"
        /** $2 = pattern, $3 = name or path. Hidden folders skipped. */
        private const val GLOB = CD + "[ -d \"\${f}\" ] || exit 3\n" +
            "if [ \"\${3}\" = path ]; then m=-path; p=\"\${f%/}/\${2}\"; else m=-name; p=\"\${2}\"; fi\n" +
            "find \"\${f}\" -mindepth 1 \\( -type d -name '.*' -prune \\) -o -type f \"\${m}\" \"\${p}\" -print 2> /dev/null | sed \"s|^\${f%/}/||\" | head -n 201"
        /**
         * $2 = regex, $3 = -i or empty, $4 = file-name glob or empty. grep: 1 no match, 2 error. Hidden folders are
         * skipped with .[!.]*, not .*, which would also skip the folder searched when it is "." (grep applies
         * --exclude-dir to the folders it is given too).
         */
        private const val GREP = CD + "[ -e \"\${f}\" ] || exit 3\n" +
            "set -- \"\${f}\" \"\${2}\" \"\${3}\" \"\${4}\"\n" +
            "grep -rnIE \${3:+\"\${3}\"} --exclude-dir='.[!.]*' \${4:+--include=\"\${4}\"} -e \"\${2}\" -- \"\${1}\" 2>&1 | sed 's|^\\./||' | cut -c1-500 | head -n 201\n" +
            "exit \${PIPESTATUS[0]/1/0}"
    }
}
