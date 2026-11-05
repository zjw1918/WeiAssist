package com.lanqi.weiassist.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentsReducerTest {
    private val base = SessionState(SessionSummary("s", "", phone = false, status = SessionStatus.Idle, createdAt = 0, updatedAt = 0))
    private val pic = Attachment("pic.jpg", "image/jpeg", "/x/pic.jpg", 10)
    private val doc = Attachment("doc.pdf", "application/pdf", "/x/doc.pdf", 20)

    @Test fun `a message with only files starts a task, shown by the files' names`() {
        val s = SessionReducer.userMessage(base, "", queued = false, now = 1, attachments = listOf(pic))
        assertEquals("", s.pendingTask)
        assertEquals(listOf(pic), s.pendingAttachments)
        assertEquals(listOf(pic), (s.items.last() as ChatItem.User).attachments)
        assertEquals("pic.jpg", s.summary.preview)
    }

    @Test fun `files sent while the task waits join it`() {
        val s0 = SessionReducer.userMessage(base, "look", queued = true, now = 1, attachments = listOf(pic))
        val s = SessionReducer.joinTask(s0, "", now = 2, attachments = listOf(doc))
        assertEquals("look", s.pendingTask)
        assertEquals(listOf(pic, doc), s.pendingAttachments)
    }

    @Test fun `a steered message that arrives late keeps its files for the next task`() {
        val running = SessionReducer.userMessage(base, "first", queued = false, now = 1)
        val steered = SessionReducer.steered(running, "and this", now = 2, attachments = listOf(doc))
        val idle = steered.copy(summary = steered.summary.copy(status = SessionStatus.Idle))
        val s = SessionReducer.lateSteer(idle, "and this", queued = false, now = 3)
        assertEquals("and this", s.pendingTask)
        assertEquals(listOf(doc), s.pendingAttachments)
    }

    @Test fun `stopping drops the files waiting with the task`() {
        val s = SessionReducer.stopped(SessionReducer.userMessage(base, "", queued = true, now = 1, attachments = listOf(pic)), now = 2)
        assertEquals(emptyList<Attachment>(), s.pendingAttachments)
    }

    @Test fun `a message's attachments are saved and read back`() {
        val item = ChatItem.User("hi", attachments = listOf(pic, doc))
        assertEquals(item, SessionStore.itemFrom(SessionStore.itemJson(item)))
    }

    @Test fun `a file given to the user is saved and read back, with and without a caption`() {
        val xlsx = Attachment("report.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "/data/x/report.xlsx", 4096)
        listOf(ChatItem.File(xlsx, "This week's numbers"), ChatItem.File(xlsx))
            .forEach { assertEquals(it, SessionStore.itemFrom(SessionStore.itemJson(it))) }
    }
}
