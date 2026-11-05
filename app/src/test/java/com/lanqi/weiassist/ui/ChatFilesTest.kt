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

    @Test fun `each notice a subagent woke the agent with starts a part of its own, so every reply stays in view`() {
        val spawn = ChatItem.Step("subagent", "Rust", ok = true)
        val fetch = ChatItem.Step("↳ web_fetch", "", ok = true)
        val running = ChatItem.Assistant("All three are running.")
        val goDone = ChatItem.Trigger("subagent-settled", "Subagent go finished.")
        val table = ChatItem.Assistant("| Rust | Go | Zig |")
        val zigDone = ChatItem.Trigger("subagent-settled", "Subagent zig finished.")
        val thought = ChatItem.Thinking("Zig is already in the table.", 900)
        val note = ChatItem.Assistant("The table above already has Zig.")
        val items = listOf(ChatItem.User("compare"), spawn, running, fetch, goDone, table, zigDone, thought, note, ChatItem.Stats(RunStats(0)))
        val blocks = chatBlocks(items, busy = false)
        assertEquals(listOf("Message", "Turn", "Message", "Turn", "Message", "Turn"), blocks.map { it.javaClass.simpleName })
        val turns = blocks.filterIsInstance<ChatBlock.Turn>()
        // The subagent's step after "running" doesn't fold that reply away; each part answers its notice.
        assertEquals(listOf(running), turns[0].answer)
        assertEquals(listOf(spawn, fetch), turns[0].process)
        assertEquals(listOf(table), turns[1].answer)
        assertEquals(listOf(note), turns[2].answer)
        assertEquals(listOf(thought), turns[2].process)
        assertEquals(goDone, (blocks[2] as ChatBlock.Message).item)
    }

    @Test fun `paths are file links, but links to web pages and other apps are not`() {
        listOf("./a.xlsx", "a.xlsx", "out/%E8%82%A1.xlsx", "~/sessions/x/a.pdf", "/root/sessions/x/a.pdf", "file:///root/a.pdf")
            .forEach { assertTrue(it, isFileLink(it)) }
        listOf("https://example.com/a.pdf", "http://x", "mailto:a@b.c", "tel:123", "#section", "")
            .forEach { assertFalse(it, isFileLink(it)) }
    }

    @Test fun `a run split by a message sent while it worked has no empty footer row before that message`() {
        val items = listOf(ChatItem.User("go"), ChatItem.Step("bash", "ls", ok = true), ChatItem.User("also this"),
            ChatItem.Step("bash", "pwd", ok = true), ChatItem.Stats(RunStats(0, steps = 2)))
        val entries = chatEntries(chatBlocks(items, busy = false))
        assertEquals(listOf("Message", "Header", "Message", "Header", "Footer"), entries.map { it.javaClass.simpleName })
    }
}
