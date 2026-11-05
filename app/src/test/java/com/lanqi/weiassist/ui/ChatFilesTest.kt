package com.lanqi.weiassist.ui

import com.lanqi.weiassist.agent.Attachment
import com.lanqi.weiassist.agent.ChatItem
import com.lanqi.weiassist.agent.RunStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFilesTest {
    private val file = ChatItem.File(Attachment("a.xlsx", "application/octet-stream", "/x/a.xlsx", 1))

    @Test fun `a file given mid-run is shown with the answer, not hidden in the steps`() {
        val step = ChatItem.Step("send_file", "a.xlsx", ok = true)
        val later = ChatItem.Step("bash", "ls", ok = true)
        val items = listOf(ChatItem.User("make it"), step, file, later, ChatItem.Assistant("Done."), ChatItem.Stats(RunStats(0)))
        val turn = chatBlocks(items, busy = false).last() as ChatBlock.Turn
        assertEquals(listOf(step, later), turn.process)
        assertEquals(listOf(file, ChatItem.Assistant("Done.")), turn.answer)
    }

    @Test fun `paths are file links, but links to web pages and other apps are not`() {
        listOf("./a.xlsx", "a.xlsx", "out/%E8%82%A1.xlsx", "~/sessions/x/a.pdf", "/root/sessions/x/a.pdf", "file:///root/a.pdf")
            .forEach { assertTrue(it, isFileLink(it)) }
        listOf("https://example.com/a.pdf", "http://x", "mailto:a@b.c", "tel:123", "#section", "")
            .forEach { assertFalse(it, isFileLink(it)) }
    }
}
