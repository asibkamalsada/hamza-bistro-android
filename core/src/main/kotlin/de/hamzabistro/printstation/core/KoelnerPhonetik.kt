package de.hamzabistro.printstation.core

/**
 * Kölner Phonetik (Postel, 1969): a word as the digits of how it sounds in
 * German, so that what was heard and what is written come out the same —
 * "Herting" and "Härting", "Meier" and "Mayer", "Tschochersche" and
 * "Zschochersche". Soundex does this for English, and gets German wrong.
 */
object KoelnerPhonetik {
    /** [word]'s code. Anything that is not a letter is skipped; umlauts and ß need no folding first. */
    fun encode(word: String): String {
        val letters = word.uppercase().filter { it.isLetter() }
        val codes = StringBuilder()
        for ((i, c) in letters.withIndex()) {
            val before = letters.getOrNull(i - 1)
            val after = letters.getOrNull(i + 1)
            when (c) {
                'A', 'E', 'I', 'J', 'O', 'U', 'Y', 'Ä', 'Ö', 'Ü' -> codes.append('0')
                'B' -> codes.append('1')
                'P' -> codes.append(if (after == 'H') '3' else '1')
                'D', 'T' -> codes.append(if (after.isOneOf("CSZ")) '8' else '2')
                'F', 'V', 'W' -> codes.append('3')
                'G', 'K', 'Q' -> codes.append('4')
                'C' ->
                    codes.append(
                        when {
                            before == null -> if (after.isOneOf("AHKLOQRUX")) '4' else '8'
                            before.isOneOf("SZ") -> '8'
                            else -> if (after.isOneOf("AHKOQUX")) '4' else '8'
                        }
                    )
                'X' -> codes.append(if (before.isOneOf("CKQ")) "8" else "48")
                'L' -> codes.append('5')
                'M', 'N' -> codes.append('6')
                'R' -> codes.append('7')
                'S', 'Z' -> codes.append('8')
                // H is not heard, and neither is a letter of another alphabet.
            }
        }
        // A sound held is one sound; then the vowels go, except one the word starts with.
        return buildString {
            for ((i, digit) in codes.withIndex()) {
                if (i > 0 && digit == codes[i - 1]) continue
                if (digit == '0' && isNotEmpty()) continue
                append(digit)
            }
        }
    }

    private fun Char?.isOneOf(letters: String): Boolean = this != null && this in letters
}
