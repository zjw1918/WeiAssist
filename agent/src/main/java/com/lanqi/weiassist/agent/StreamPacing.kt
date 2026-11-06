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
 *
 * Paced text goes out on the [liveGridWait] grid, the clock the page's live indicators also step on (the swimming
 * fish, the working dots): a redraw for new text and one for the next pose then fall in the same frame, instead of
 * each costing a frame of its own.
 */
fun Flow<SessionState>.pacedStreaming(ms: Long = STREAM_FRAME_MS): Flow<SessionState> = channelFlow {
    var sent: SessionState? = null
    var latest: SessionState? = null
    var timer: Job? = null
    suspend fun emit(s: SessionState) {
        sent = s
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
        timer = launch {
            delay(liveGridWait(ms))
            timer = null
            latest?.let { emit(it) }
        }
    }
}

/** How often streamed text reaches the page (about ten times a second: still reads as live typing). */
const val STREAM_FRAME_MS = 100L

/**
 * Milliseconds to the next multiple of [period] on the monotonic clock (System.nanoTime): the grid that paced text and
 * the page's live indicators share. Periods that are multiples of each other then tick at the same moments.
 */
fun liveGridWait(period: Long): Long {
    val now = System.nanoTime() / 1_000_000
    return period - now % period
}

/** Whether [b] differs from [a] only in what streams: the text being written and reasoned, and the run's counts. */
internal fun onlyStreamed(a: SessionState, b: SessionState): Boolean =
    b.copy(streaming = a.streaming, thinking = a.thinking, run = a.run, trace = a.trace) == a
