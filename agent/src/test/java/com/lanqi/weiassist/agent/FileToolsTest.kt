package com.lanqi.weiassist.agent

import com.lanqi.weiassist.agent.FileTools.Level
import com.lanqi.weiassist.core.ToolResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileToolsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var appData: File
    private lateinit var storage: File
    private var level = Level.Session
    private var granted = true
    private var termuxSession = false
    /** A stand-in for Termux: its home is a folder here, and every call is recorded. */
    private lateinit var termuxHome: File
    private val termuxCalls = mutableListOf<String>()

    private var termuxProblem: String? = null
    private val termux = object : FileTools.TermuxFiles {
        override fun problem(): String? = termuxProblem
        override suspend fun script(session: String, script: String, args: List<String>): Pair<Int, String> {
            termuxCalls += "script ${args.joinToString(" ")}"
            return 0 to "f 3 a.txt\nd 4096 sub"
        }
        override suspend fun pull(session: String, path: String, dest: File, maxBytes: Long): String? {
            termuxCalls += "pull $path"
            val f = File(termuxHome, "sessions/$session/$path")
            if (!f.isFile) return "No such file in Termux: $path"
            f.copyTo(dest, overwrite = true); return null
        }
        override suspend fun push(session: String, file: File, path: String): String? {
            termuxCalls += "push $path"
            file.copyTo(File(termuxHome, "sessions/$session/$path").apply { parentFile?.mkdirs() }, overwrite = true); return null
        }
    }

    private lateinit var tools: FileTools

    @Before fun setUp() {
        appData = tmp.newFolder("app")
        storage = tmp.newFolder("sdcard")
        termuxHome = tmp.newFolder("termux")
        File(appData, "secrets.json").writeText("""{"key":"sk-secret"}""")
        tools = FileTools(
            level = { level },
            summary = { id -> SessionSummary(id, "t", phone = false, status = SessionStatus.Running, createdAt = 0, updatedAt = 0, termux = termuxSession) },
            workspace = { id -> File(appData, "workspaces/$id") },
            storageRoot = storage,
            storageGranted = { granted },
            termux = termux,
            scratch = tmp.newFolder("scratch"),
        )
    }

    private fun call(name: String, vararg a: Pair<String, Any>): ToolResult = runBlocking {
        tools.call("s1", name, JSONObject().apply { a.forEach { (k, v) -> put(k, v) } })
    }

    @Test fun `write, read, edit and list in the session's own folder in the app`() {
        assertFalse(call("write", "path" to "notes/plan.md", "content" to "# Plan\nstep one\nstep two\n").isError)
        assertEquals("# Plan\nstep one\nstep two\n", File(appData, "workspaces/s1/notes/plan.md").readText())
        assertEquals("# Plan\nstep one\nstep two", call("read", "path" to "notes/plan.md").text)
        assertEquals("step two", call("read", "path" to "notes/plan.md", "offset" to 3).text)
        assertTrue(call("read", "path" to "notes/plan.md", "limit" to 1).text.endsWith("(Lines 1–1 of 3. Read on with offset=2.)"))
        assertFalse(call("edit", "path" to "notes/plan.md", "old_string" to "step one", "new_string" to "step 1").isError)
        assertEquals("# Plan\nstep 1\nstep two\n", File(appData, "workspaces/s1/notes/plan.md").readText())
        assertEquals("notes/", call("ls", "path" to ".").text)
        assertEquals("plan.md  (23 bytes)", call("ls", "path" to "notes").text)
    }

    @Test fun `edit needs text that occurs exactly once, unless replace_all`() {
        call("write", "path" to "a.txt", "content" to "x = 1\nx = 1\n")
        assertTrue(call("edit", "path" to "a.txt", "old_string" to "x = 1", "new_string" to "x = 2").text.contains("occurs 2 times"))
        assertTrue(call("edit", "path" to "a.txt", "old_string" to "y", "new_string" to "z").text.contains("not found"))
        assertEquals("Edited a.txt: replaced 2 occurrences.", call("edit", "path" to "a.txt", "old_string" to "x = 1", "new_string" to "x = 2", "replace_all" to true).text)
        assertEquals("x = 2\nx = 2\n", File(appData, "workspaces/s1/a.txt").readText())
    }

    @Test fun `the app's own files and other places stay out of reach`() {
        for (path in listOf("../../secrets.json", "../s2/x", "/data/data/com.lanqi.weiassist/files/secrets.json", "~/x", "/etc/hosts")) {
            val r = call("read", "path" to path)
            assertTrue("$path: ${r.text}", r.isError)
            assertFalse(r.text, "sk-secret" in r.text)
        }
        assertTrue(call("write", "path" to "../escape.txt", "content" to "x").isError)
        assertFalse(File(appData, "workspaces/escape.txt").exists())
    }

    @Test fun `glob and grep search the session's folder`() {
        call("write", "path" to "main.go", "content" to "package main\nfunc main() {}\n")
        call("write", "path" to "pkg/util/util.go", "content" to "package util\n// TODO speed up\n")
        call("write", "path" to "README.md", "content" to "todo: docs\n")
        call("write", "path" to ".git/config", "content" to "TODO hidden\n")
        assertEquals("main.go\npkg/util/util.go", call("glob", "pattern" to "*.go").text.lines().sorted().joinToString("\n"))
        assertEquals("pkg/util/util.go", call("glob", "pattern" to "pkg/**/*.go").text)
        assertEquals("pkg/util/util.go:2: // TODO speed up", call("grep", "pattern" to "TODO").text)
        assertEquals(2, call("grep", "pattern" to "todo", "ignore_case" to true).text.lines().size)
        assertEquals("pkg/util/util.go:2: // TODO speed up", call("grep", "pattern" to "todo", "ignore_case" to true, "glob" to "*.go").text)
        assertTrue(call("grep", "pattern" to "(").isError)
    }

    @Test fun `the phone's storage follows the user's level`() {
        File(storage, "Download").mkdirs()
        File(storage, "Download/report.txt").writeText("Q3 numbers\n")
        level = Level.Session
        assertTrue(call("read", "path" to "/sdcard/Download/report.txt").text.contains("Settings → File access"))
        level = Level.StorageRead
        assertEquals("Q3 numbers", call("read", "path" to "/sdcard/Download/report.txt").text)
        assertEquals("Q3 numbers", call("read", "path" to "/storage/emulated/0/Download/report.txt").text)
        assertEquals("/sdcard/Download/report.txt:1: Q3 numbers", call("grep", "pattern" to "Q3", "path" to "/sdcard").text)
        val ro = call("write", "path" to "/sdcard/Download/new.txt", "content" to "x")
        assertTrue(ro.isError && "read-only" in ro.text)
        assertFalse(File(storage, "Download/new.txt").exists())
        assertTrue(call("read", "path" to "/sdcard/../../secrets.json").isError)
        level = Level.StorageWrite
        assertFalse(call("write", "path" to "/sdcard/Download/new.txt", "content" to "x").isError)
        assertEquals("x", File(storage, "Download/new.txt").readText())
        granted = false
        assertTrue(call("read", "path" to "/sdcard/Download/report.txt").text.contains("All files access"))
    }

    @Test fun `looking at a folder that isn't there yet is an answer, not a failure`() {
        val r = call("ls", "path" to "notes")
        assertFalse(r.text, r.isError)
        assertEquals("notes doesn't exist yet.", r.text)
        assertFalse(call("glob", "pattern" to "*.md", "path" to "notes").isError)
        assertTrue("reading a missing file still fails", call("read", "path" to "notes/a.md").isError)
    }

    @Test fun `off means no file tools at all`() {
        level = Level.Off
        assertTrue(call("ls", "path" to ".").isError)
        assertTrue(call("write", "path" to "a.txt", "content" to "x").isError)
    }

    @Test fun `with Termux, the session's folder is its Termux folder`() {
        termuxSession = true
        assertFalse(call("write", "path" to "out/data.csv", "content" to "a,b\n1,2\n").isError)
        assertEquals("a,b\n1,2\n", File(termuxHome, "sessions/s1/out/data.csv").readText())
        assertEquals("a,b\n1,2", call("read", "path" to "out/data.csv").text)
        assertFalse(call("edit", "path" to "out/data.csv", "old_string" to "1,2", "new_string" to "3,4").isError)
        assertEquals("a,b\n3,4\n", File(termuxHome, "sessions/s1/out/data.csv").readText())
        assertEquals("a.txt  (3 bytes)\nsub/", call("ls", "path" to ".").text)
        // dsh's /root is Termux's home.
        call("read", "path" to "/root/x.txt")
        assertTrue(termuxCalls.contains("pull ~/x.txt"))
        assertFalse("nothing in the app's folder", File(appData, "workspaces/s1").exists())
        assertTrue(call("read", "path" to "missing.txt").text.startsWith("No such file"))
    }

    @Test fun `a session with Termux uses the app folder while Termux isn't set up`() {
        termuxSession = true
        termuxProblem = "Termux is not installed"
        assertFalse(call("write", "path" to "a.txt", "content" to "hi").isError)
        assertEquals("hi", File(appData, "workspaces/s1/a.txt").readText())
        assertTrue("Termux was never asked", termuxCalls.isEmpty())
    }

    @Test fun `long text comes in parts`() {
        val text = (1..5).joinToString("\n") { "line $it" }
        assertEquals("line 2\nline 3\n\n(Lines 2–3 of 5. Read on with offset=4.)", FileTools.window(text, 2, 2, "f"))
        assertTrue(FileTools.window(text, 9, 2, "f").contains("past its end"))
        assertEquals("(f is empty.)", FileTools.window("", 1, 10, "f"))
    }
}
