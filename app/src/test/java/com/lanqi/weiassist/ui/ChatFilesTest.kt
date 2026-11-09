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

    @Test fun `while subagents work, the run is one turn and the agent's replies wait in its process`() {
        val rust = ChatItem.Subagent("c1", "Rust review", outcome = "done")
        val zig = ChatItem.Subagent("c2", "Zig review")
        val items = listOf(ChatItem.User("compare"), spawn, rust, zig, running, goDone, goNote)
        val blocks = chatBlocks(items, busy = true)
        assertEquals(listOf("Message", "Turn"), blocks.map { it.javaClass.simpleName })
        val turn = blocks.last() as ChatBlock.Turn
        assertTrue(turn.live && turn.waiting)
        // Zig still works: what the agent said so far is part of the work, not the answer.
        assertEquals(emptyList<ChatItem>(), turn.answer)
        assertEquals(listOf(spawn, rust, zig, running, goDone, goNote), turn.process)
        assertEquals(listOf(rust, zig), turn.agents)
        // Once Zig is done, the agent's reply to it is the answer.
        val done = items.map { if (it == zig) zig.copy(outcome = "done") else it } + zigDone + table
        val after = chatBlocks(done, busy = true).last() as ChatBlock.Turn
        assertFalse(after.waiting)
        assertEquals(listOf(table), after.answer)
        // While that reply hasn't come yet, an earlier remark doesn't stand in for it.
        assertEquals(emptyList<ChatItem>(), (chatBlocks(done.dropLast(1), busy = true).last() as ChatBlock.Turn).answer)
    }

    @Test fun `a background command's end is a row of its own, and dsh's notices stay in the steps`() {
        val ended = ChatItem.Trigger("background-command", "ping\n\ndone")
        val blocks = chatBlocks(listOf(ChatItem.User("go"), ended, ChatItem.Assistant("It finished.")), busy = false)
        assertEquals(listOf("Message", "Message", "Turn"), blocks.map { it.javaClass.simpleName })
        assertEquals(ended, (blocks[1] as ChatBlock.Message).item)
        val dsh = ChatItem.Trigger("tool-jobs", "job")
        assertEquals(listOf("Message", "Turn"), chatBlocks(listOf(ChatItem.User("go"), dsh), busy = false).map { it.javaClass.simpleName })
    }

    @Test fun `a message sent while subagents work splits the run but leaves them working, on the page`() {
        val rust = ChatItem.Subagent("c1", "Rust review")
        val zig = ChatItem.Subagent("c2", "Zig review", outcome = "done")
        val ask = ChatItem.User("Something else: what's 2+2?")
        val reply = ChatItem.Assistant("4.")
        val items = listOf(ChatItem.User("compare"), spawn, rust, zig, running, ask, reply)
        val blocks = chatBlocks(items, busy = true)
        assertEquals(listOf("Message", "Turn", "Message", "Turn"), blocks.map { it.javaClass.simpleName })
        val first = blocks[1] as ChatBlock.Turn
        // Not the live turn, but its run goes on: Rust still works.
        assertFalse(first.live)
        assertTrue(first.ongoing && first.waiting)
        assertEquals(listOf(rust, zig), first.agents)
        // The new topic's reply is the live turn's answer, not held back for the other turn's subagents.
        val tail = blocks.last() as ChatBlock.Turn
        assertTrue(tail.live && !tail.waiting)
        // Their cards are a row of the page, under the split-off part's header.
        assertTrue(entriesOf(blocks).any { it is ChatEntry.Agents && it.turn.key == first.key })
        // Once the run is over, nothing is ongoing; the cards stay on the page.
        val done = items.map { if (it == rust) rust.copy(outcome = "done") else it } + ChatItem.Stats(RunStats(0))
        val after = chatBlocks(done, busy = false)
        assertTrue(after.filterIsInstance<ChatBlock.Turn>().none { it.ongoing || it.waiting })
        assertTrue(entriesOf(after).any { it is ChatEntry.Agents })
    }

    /** The page's rows, answers left out (their Markdown parsing needs Android). */
    private fun entriesOf(blocks: List<ChatBlock>) = chatEntries(blocks.map { if (it is ChatBlock.Turn) it.copy(answer = emptyList()) else it })

    @Test fun `the answer starts when every subagent has handed in its result, by message or by finishing`() {
        val rust = ChatItem.Subagent("c1", "Rust", outcome = "done")
        val zig = ChatItem.Subagent("c2", "Zig", outcome = "done")
        val rustIn = ChatItem.Trigger("subagent-settled", "Subagent c1 finished.", "c1")
        val zigReport = ChatItem.Trigger("agent-message", "Zig: …", "c2")
        val zigIn = ChatItem.Trigger("subagent-settled", "Subagent c2 finished.", "c2")
        val wrapUp = ChatItem.Assistant("That was Zig's completion notice, already in the table above.\n\n- Drill into one language\n- Export the comparison as a file you can keep, Markdown or PDF, whichever you prefer for later reading on the phone")
        val items = listOf(ChatItem.User("compare"), spawn, rust, zig, running, rustIn, goNote, zigReport, table, zigIn, thought, wrapUp,
            ChatItem.Stats(RunStats(0)))
        val turn = chatBlocks(items, busy = false).last() as ChatBlock.Turn
        assertEquals(listOf(table, wrapUp), turn.answer)
        assertEquals(listOf(spawn, rust, zig, running, rustIn, goNote, zigReport, zigIn, thought), turn.process)
    }

    @Test fun `once its subagents are joined, the answer is its report, even when a notice came after it`() {
        val rust = ChatItem.Subagent("c1", "Rust", outcome = "done")
        val zig = ChatItem.Subagent("c2", "Zig", outcome = "stopped")
        val rustIn = ChatItem.Trigger("subagent-settled", "Subagent c1 finished.", "c1")
        val stop = ChatItem.Step("interrupt_agent", "", ok = true)
        val zigStopped = ChatItem.Trigger("subagent-settled", "Subagent c2 was stopped before it finished.", "c2")
        val ack = ChatItem.Assistant("Zig stopped as asked.")
        val items = listOf(ChatItem.User("compare"), spawn, rust, zig, running, rustIn, goNote, stop, table, zigStopped, ack,
            ChatItem.Stats(RunStats(0)))
        val turn = chatBlocks(items, busy = false).last() as ChatBlock.Turn
        assertEquals(listOf(table, ack), turn.answer)
        assertEquals(listOf(spawn, rust, zig, running, rustIn, goNote, stop, zigStopped), turn.process)
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
