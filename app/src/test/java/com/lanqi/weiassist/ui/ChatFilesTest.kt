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

    private val spawn = ChatItem.Step("subagent", "Rust", ok = true)
    private val fetch = ChatItem.Step("↳ web_fetch", "", ok = true)
    private val running = ChatItem.Assistant("All three are running.")
    private val goDone = ChatItem.Trigger("subagent-settled", "Subagent go finished.")
    private val goNote = ChatItem.Assistant("Go is in. Waiting on Zig.")
    private val zigMsg = ChatItem.Trigger("agent-message", "Agent zig sent a message: …")
    private val table = ChatItem.Assistant("All three are in.\n\n| Rust | Go | Zig |\n|---|---|---|\n| a | b | c |")
    private val zigDone = ChatItem.Trigger("subagent-settled", "Subagent zig finished.")
    private val thought = ChatItem.Thinking("Zig is already in the table.", 900)
    private val note = ChatItem.Assistant("The table above already has Zig.")
    private val subagentRun = listOf(ChatItem.User("compare"), spawn, running, fetch, goDone, goNote, zigMsg, table, zigDone, thought, note)

    @Test fun `while subagents work, each notice starts a part of its own, so results show as they come`() {
        val blocks = chatBlocks(subagentRun, busy = true)
        assertEquals(listOf("Message", "Turn", "Message", "Turn", "Message", "Turn", "Message", "Turn"), blocks.map { it.javaClass.simpleName })
        val turns = blocks.filterIsInstance<ChatBlock.Turn>()
        // The subagent's step after "running" doesn't fold that reply away.
        assertEquals(listOf(running), turns[0].answer)
        assertEquals(listOf(spawn, fetch), turns[0].process)
        assertEquals(listOf(table), turns[2].answer)
        assertTrue(turns.last().live)
    }

    @Test fun `a finished subagent run is one turn, ending with the report and the remark after it`() {
        val blocks = chatBlocks(subagentRun + ChatItem.Stats(RunStats(0, steps = 2)), busy = false)
        assertEquals(listOf("Message", "Turn"), blocks.map { it.javaClass.simpleName })
        val turn = blocks.last() as ChatBlock.Turn
        // The last reply is only a remark: the report before it shows with it.
        assertEquals(listOf(table, note), turn.answer)
        assertEquals(listOf(spawn, running, fetch, goDone, goNote, zigMsg, zigDone, thought), turn.process)
        assertEquals(2, turn.subagents)
        // A last reply that is a report shows alone.
        val report = ChatItem.Assistant("| Rust | Go | Zig |\n|---|---|---|\n| a | b | c |")
        val alone = chatBlocks(subagentRun.dropLast(1) + report + ChatItem.Stats(RunStats(0)), busy = false).last() as ChatBlock.Turn
        assertEquals(listOf(report), alone.answer)
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
