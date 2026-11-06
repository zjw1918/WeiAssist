package com.lanqi.weiassist.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * A session's state for its page, with streamed text paced: a model streams its reasoning and answer in many small
 * chunks (tens a second), and each one would lay the page out again; on a mid-range phone that alone made most
 * frames late. A change to the streamed text only ([SessionState.streaming], [SessionState.thinking], and what
 * changes with them) goes out at most every [ms], always the latest; any other change (a step, the answer ending,
 * the status) goes out at once, and carries the text so far with it.
 */
fun Flow<SessionState>.pacedStreaming(ms: Long = STREAM_FRAME_MS): Flow<SessionState> = channelFlow {
    var sent: SessionState? = null
    var sentAt = 0L
    var latest: SessionState? = null
    var timer: Job? = null
    suspend fun emit(s: SessionState) {
        sent = s
        sentAt = System.nanoTime()
        send(s)
    }
    collect { s ->
        latest = s
        val prev = sent
        if (prev == null || !onlyStreamed(prev, s)) {
            timer?.cancel()
            timer = null
            emit(s)
            return@collect
        }
        if (timer != null) return@collect
        val wait = ms - (System.nanoTime() - sentAt) / 1_000_000
        if (wait <= 0) emit(s)
        else timer = launch {
            delay(wait)
            timer = null
            latest?.let { emit(it) }
        }
    }
}

/** How often streamed text reaches the page (about ten times a second: still reads as live typing). */
const val STREAM_FRAME_MS = 100L

/** Whether [b] differs from [a] only in what streams: the text being written and reasoned, and the run's counts. */
internal fun onlyStreamed(a: SessionState, b: SessionState): Boolean =
    b.copy(streaming = a.streaming, thinking = a.thinking, run = a.run, trace = a.trace) == a
