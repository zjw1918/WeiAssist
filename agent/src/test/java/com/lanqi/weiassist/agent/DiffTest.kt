package com.lanqi.weiassist.agent

import com.lanqi.weiassist.agent.Diff.Kind.Added
import com.lanqi.weiassist.agent.Diff.Kind.Fold
import com.lanqi.weiassist.agent.Diff.Kind.Removed
import com.lanqi.weiassist.agent.Diff.Kind.Same
import org.junit.Assert.assertEquals
import org.junit.Test

class DiffTest {
    private fun kinds(l: List<Diff.Line>) = l.map { it.kind }

    @Test fun `a changed line is removed then added, with line numbers in each text`() {
        val l = Diff.lines("a\nb\nc\n", "a\nB\nc\n")
        assertEquals(listOf(Same, Removed, Added, Same), kinds(l))
        assertEquals(Diff.Line(Removed, "b", old = 2), l[1])
        assertEquals(Diff.Line(Added, "B", new = 2), l[2])
        assertEquals(Diff.Line(Same, "c", 3, 3), l[3])
        assertEquals(Diff.Stat(1, 1), Diff.stat(l))
    }

    @Test fun `a new file is all added`() {
        val l = Diff.lines("", "package main\n\nfunc main() {}\n")
        assertEquals(listOf(Added, Added, Added), kinds(l))
        assertEquals(listOf(1, 2, 3), l.map { it.new })
    }

    @Test fun `lines kept in between stay unchanged`() {
        val l = Diff.lines("x\na\ny\nb\n", "a\nz\nb\nw\n")
        assertEquals(listOf("-x", " a", "-y", "+z", " b", "+w"), l.map { (if (it.kind == Same) " " else if (it.kind == Added) "+" else "-") + it.text })
    }

    @Test fun `long unchanged runs fold, keeping three lines by each change`() {
        val old = (1..20).joinToString("\n")
        val new = old.replace("\n10\n", "\nten\n")
        val h = Diff.hunks(Diff.lines(old, new))
        assertEquals(listOf(Fold, Same, Same, Same, Removed, Added, Same, Same, Same, Fold), kinds(h))
        assertEquals(6, h.first().folded)
        assertEquals(7, h.last().folded)
        assertEquals("7", h[1].text)
    }

    @Test fun `a short run between changes stays`() {
        val h = Diff.hunks(Diff.lines("a\n1\n2\n3\nb", "A\n1\n2\n3\nB"))
        assertEquals(0, h.count { it.kind == Fold })
    }
}
