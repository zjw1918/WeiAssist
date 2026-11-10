package com.lanqi.weiassist.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A command that went on in the background (the user asked to be read now), until its result has reached the model. */
data class BackgroundJob(val session: String, val call: String, val command: String, val timeoutSec: Int, val startedAt: Long)

/**
 * The commands in the background, while the app lives (closing it ends them all). The core that waits for one may
 * die first (its runtime restarted): those are "orphaned", and the app then collects the result from Termux itself.
 */
class BackgroundJobs {
    private val _jobs = MutableStateFlow<List<BackgroundJob>>(emptyList())
    val jobs: StateFlow<List<BackgroundJob>> = _jobs
    private val orphans = HashSet<Pair<String, String>>()

    @Synchronized fun add(job: BackgroundJob) {
        _jobs.value = _jobs.value.filterNot { it.session == job.session && it.call == job.call } + job
    }

    @Synchronized fun remove(session: String, call: String) {
        orphans -= session to call
        _jobs.value = _jobs.value.filterNot { it.session == session && it.call == call }
    }

    @Synchronized fun removeSession(session: String) {
        orphans.removeAll { it.first == session }
        _jobs.value = _jobs.value.filterNot { it.session == session }
    }

    /** What was waiting for these commands is gone. */
    @Synchronized fun orphanAll() { _jobs.value.forEach { orphans += it.session to it.call } }

    @Synchronized fun orphaned(): List<BackgroundJob> = _jobs.value.filter { (it.session to it.call) in orphans }
}

/**
 * Work the model left running in its session: a tmux job ([name] its tmux session), or a process a command started in
 * the background (`cmd &`, nohup) that outlived it ([pid]; [name] is the pid). [startedAt] is when it started (ms; 0 if
 * unknown), [command] what it runs.
 */
data class SessionJob(val session: String, val name: String, val startedAt: Long, val command: String, val pid: Int? = null)
