package com.lanqi.weiassist.agent

import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A command that went on in the background (the user asked to be read now), until its result has reached the model. */
data class BackgroundJob(val session: String, val call: String, val command: String, val timeoutSec: Int, val startedAt: Long)

/**
 * The commands in the background, kept on disk: the core that waits for one may die before it ends (the app's
 * process, or the core's runtime). Those are "orphaned": the app then collects the result from Termux itself.
 */
class BackgroundJobs(private val file: AtomicFile) {
    private val _jobs = MutableStateFlow<List<BackgroundJob>>(emptyList())
    val jobs: StateFlow<List<BackgroundJob>> = _jobs
    private val orphans = HashSet<Pair<String, String>>()

    @Synchronized fun load() {
        val text = runCatching { String(file.readFully()) }.getOrNull() ?: return
        _jobs.value = runCatching { parse(text) }.getOrDefault(emptyList())
        orphanAll()
    }

    @Synchronized fun add(job: BackgroundJob) {
        _jobs.value = _jobs.value.filterNot { it.session == job.session && it.call == job.call } + job
        save()
    }

    @Synchronized fun remove(session: String, call: String) {
        orphans -= session to call
        _jobs.value = _jobs.value.filterNot { it.session == session && it.call == call }
        save()
    }

    @Synchronized fun removeSession(session: String) {
        orphans.removeAll { it.first == session }
        _jobs.value = _jobs.value.filterNot { it.session == session }
        save()
    }

    /** What was waiting for these commands is gone. */
    @Synchronized fun orphanAll() { _jobs.value.forEach { orphans += it.session to it.call } }

    @Synchronized fun orphaned(): List<BackgroundJob> = _jobs.value.filter { (it.session to it.call) in orphans }

    private fun save() {
        val out = file.startWrite()
        try {
            out.write(json(_jobs.value).toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    companion object {
        internal fun json(jobs: List<BackgroundJob>) = JSONArray(jobs.map {
            JSONObject().put("session", it.session).put("call", it.call).put("command", it.command)
                .put("timeoutSec", it.timeoutSec).put("startedAt", it.startedAt)
        }).toString()

        internal fun parse(text: String): List<BackgroundJob> {
            val a = JSONArray(text)
            return (0 until a.length()).map { i ->
                a.getJSONObject(i).let { BackgroundJob(it.getString("session"), it.getString("call"), it.optString("command"), it.optInt("timeoutSec", 120), it.optLong("startedAt")) }
            }
        }
    }
}
