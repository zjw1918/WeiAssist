package com.lanqi.weiassist.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPacingTest {
    private val base = SessionState(SessionSummary("s", "t", false, SessionStatus.Running, 0, 0))

    @Test fun `streamed text is paced, the latest always arrives, and other changes go out at once`() = runBlocking {
        val out = flow {
            emit(base)
            // 30 chunks within ~60 ms: only a few of them reach the page.
            for (i in 1..30) { emit(base.copy(streaming = "x".repeat(i))); delay(2) }
            // A step: at once, with the text so far.
            emit(base.copy(streaming = "x".repeat(30), items = listOf(ChatItem.Step("bash", "ls"))))
            emit(base.copy(streaming = "x".repeat(31), items = listOf(ChatItem.Step("bash", "ls"))))
            delay(150)
        }.pacedStreaming(50).toList()
        assertEquals(base, out.first())
        assertTrue("paced: ${out.size} emissions", out.size in 3..6)
        // The step went out, then the last chunk after it (not dropped).
        assertEquals(listOf(ChatItem.Step("bash", "ls")), out[out.size - 2].items)
        assertEquals("x".repeat(31), out.last().streaming)
    }

    @Test fun `only the streamed fields count as streaming`() {
        assertTrue(onlyStreamed(base, base.copy(streaming = "a", thinking = "b")))
        assertTrue(!onlyStreamed(base, base.copy(error = "e")))
        assertTrue(!onlyStreamed(base, base.copy(thinkingSince = 5L)))
    }
}
