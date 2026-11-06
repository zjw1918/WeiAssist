package com.lanqi.weiassist.agent

/**
 * A line diff of a file change the agent made, for the step's diff card: which lines it removed and added, with
 * a few unchanged lines around each change and the rest folded. Pure Kotlin, unit-tested on the JVM.
 */
object Diff {
    enum class Kind { Same, Added, Removed, Fold }

    /**
     * One line of the diff. [old] and [new]: its line numbers in each text (1-based; 0 where it isn't in that
     * text). A [Kind.Fold] stands for [folded] unchanged lines, its [text] empty.
     */
    data class Line(val kind: Kind, val text: String, val old: Int = 0, val new: Int = 0, val folded: Int = 0)

    data class Stat(val added: Int, val removed: Int) {
        operator fun plus(o: Stat) = Stat(added + o.added, removed + o.removed)
        companion object { val NONE = Stat(0, 0) }
    }

    /** Above this many line pairs to compare, a change shows as all of its old lines out and all of its new ones in. */
    private const val MAX_CELLS = 400_000

    private fun split(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')

    /** Every line of [old] and [new], in order: the ones both share, and the ones removed and added. */
    fun lines(old: String, new: String): List<Line> {
        val a = split(old)
        val b = split(new)
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val out = ArrayList<Line>(a.size + b.size)
        for (k in 0 until pre) out += Line(Kind.Same, a[k], k + 1, k + 1)
        middle(a.subList(pre, a.size - suf), b.subList(pre, b.size - suf), pre, pre, out)
        for (k in 0 until suf) {
            val i = a.size - suf + k; val j = b.size - suf + k
            out += Line(Kind.Same, a[i], i + 1, j + 1)
        }
        return out
    }

    /** The changed middle, by the longest run of lines both keep (removals before additions where they meet). */
    private fun middle(a: List<String>, b: List<String>, a0: Int, b0: Int, out: MutableList<Line>) {
        val n = a.size; val m = b.size
        if (n == 0 || m == 0 || n.toLong() * m > MAX_CELLS) {
            a.forEachIndexed { k, t -> out += Line(Kind.Removed, t, a0 + k + 1, 0) }
            b.forEachIndexed { k, t -> out += Line(Kind.Added, t, 0, b0 + k + 1) }
            return
        }
        // lcs[i][j]: the longest common run of a[i..] and b[j..].
        val w = m + 1
        val lcs = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i * w + j] = if (a[i] == b[j]) lcs[(i + 1) * w + j + 1] + 1 else maxOf(lcs[(i + 1) * w + j], lcs[i * w + j + 1])
        }
        var i = 0; var j = 0
        while (i < n || j < m) when {
            i < n && j < m && a[i] == b[j] -> { out += Line(Kind.Same, a[i], a0 + i + 1, b0 + j + 1); i++; j++ }
            j >= m || (i < n && lcs[(i + 1) * w + j] >= lcs[i * w + j + 1]) -> { out += Line(Kind.Removed, a[i], a0 + i + 1, 0); i++ }
            else -> { out += Line(Kind.Added, b[j], 0, b0 + j + 1); j++ }
        }
    }

    /** [lines] with runs of unchanged lines longer than twice [context] folded, keeping [context] lines by each change. */
    fun hunks(lines: List<Line>, context: Int = 3): List<Line> {
        val out = ArrayList<Line>()
        var k = 0
        while (k < lines.size) {
            if (lines[k].kind != Kind.Same) { out += lines[k++]; continue }
            var e = k
            while (e < lines.size && lines[e].kind == Kind.Same) e++
            val keepBefore = if (k == 0) 0 else context
            val keepAfter = if (e == lines.size) 0 else context
            if (e - k > keepBefore + keepAfter + 1) {
                for (x in k until k + keepBefore) out += lines[x]
                out += Line(Kind.Fold, "", folded = e - k - keepBefore - keepAfter)
                for (x in e - keepAfter until e) out += lines[x]
            } else for (x in k until e) out += lines[x]
            k = e
        }
        return out
    }

    fun stat(lines: List<Line>) = Stat(lines.count { it.kind == Kind.Added }, lines.count { it.kind == Kind.Removed })
}
