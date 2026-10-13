package de.hamzabistro.printstation.core

/**
 * How many slips of the finger lie between two spellings: a letter too
 * many, one missing, a wrong one, or two next to each other swapped
 * (Damerau's addition to Levenshtein's three).
 */
internal object DamerauLevenshtein {
    /**
     * The fewest slips between [typed] and the beginning of [name] from
     * [from] on, however long that beginning is — what was typed may stop
     * mid-name. More than [max] comes back as [max] + 1: nobody asks how
     * far off a street is that is too far off.
     */
    fun toBeginning(typed: String, name: String, from: Int = 0, max: Int): Int {
        // A beginning more than max letters longer than what was typed is more than max slips away.
        val width = minOf(name.length - from, typed.length + max)
        var twoUp = IntArray(width + 1)
        var up = IntArray(width + 1) { it }
        var row = IntArray(width + 1)
        for (i in 1..typed.length) {
            row[0] = i
            var best = i
            for (j in 1..width) {
                val t = typed[i - 1]
                val n = name[from + j - 1]
                var slips = minOf(up[j] + 1, row[j - 1] + 1, up[j - 1] + if (t == n) 0 else 1)
                if (i > 1 && j > 1 && t == name[from + j - 2] && typed[i - 2] == n) slips = minOf(slips, twoUp[j - 2] + 1)
                row[j] = slips
                if (slips < best) best = slips
            }
            if (best > max) return max + 1
            val spare = twoUp
            twoUp = up
            up = row
            row = spare
        }
        return up.min()
    }
}
