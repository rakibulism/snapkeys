package com.snapkeys.app.ime

import com.snapkeys.app.data.Shortcut

/**
 * Pure text-expansion logic, kept free of Android dependencies so it can be
 * unit-tested in isolation.
 *
 * When a delimiter is typed, the engine checks whether the text ending at the
 * cursor matches an enabled trigger (longest first) and, if so, reports how
 * many characters to delete and what to insert. Matching by suffix — rather
 * than by splitting into words — lets triggers contain punctuation and other
 * special characters (`@@`, `!!`, `addr.`). Triggers that start with a letter
 * or digit must still sit at a word boundary, so `ty` never fires inside
 * `pretty`.
 */
class ExpansionEngine(shortcuts: List<Shortcut>) {

    // Case-insensitive lookup of enabled triggers.
    private val byTrigger: Map<String, Shortcut> =
        shortcuts.filter { it.enabled && it.trigger.isNotEmpty() }
            .associateBy { it.trigger.lowercase() }

    // Candidate suffix lengths, longest first so the most specific trigger wins.
    private val lengths: List<Int> =
        byTrigger.keys.map { it.length }.distinct().sortedDescending()

    data class Expansion(
        /** Number of characters to delete before the cursor (the trigger). */
        val deleteBefore: Int,
        /** Text to insert in place of the deleted trigger. */
        val insert: String,
    )

    /**
     * @param textBeforeCursor everything to the left of the cursor
     * @param delimiter the character just typed that triggered the check,
     *        or null when checking on an explicit request
     * @return an [Expansion] to apply, or null if nothing matches
     */
    fun onDelimiter(textBeforeCursor: CharSequence, delimiter: Char?): Expansion? {
        for (length in lengths) {
            if (textBeforeCursor.length < length) continue
            val start = textBeforeCursor.length - length
            val candidate = textBeforeCursor.subSequence(start, textBeforeCursor.length).toString()
            val match = byTrigger[candidate.lowercase()] ?: continue
            // Word-like triggers must start at a word boundary; symbol
            // triggers (@@, !!) may attach directly to preceding text.
            if (match.trigger.first().isLetterOrDigit() &&
                start > 0 && !isDelimiter(textBeforeCursor[start - 1])
            ) continue
            val expansion = applyCase(candidate, match)
            val insert = if (delimiter != null) expansion + delimiter else expansion
            return Expansion(deleteBefore = length, insert = insert)
        }
        return null
    }

    /**
     * Mirror the typed trigger's capitalization onto the expansion:
     * `brb` → as stored, `Brb` → `Be right back`, `BRB` → `BE RIGHT BACK`.
     * Typing the trigger exactly as stored keeps the expansion as stored,
     * so deliberate casing (names, emails) survives.
     */
    private fun applyCase(typed: String, match: Shortcut): String {
        val letters = typed.filter { it.isLetter() }
        return when {
            typed == match.trigger || letters.isEmpty() -> match.expansion
            letters.length > 1 && letters.all { it.isUpperCase() } -> match.expansion.uppercase()
            typed.first().isUpperCase() ->
                match.expansion.replaceFirstChar { it.uppercaseChar() }
            else -> match.expansion
        }
    }

    companion object {
        fun isDelimiter(c: Char): Boolean =
            c.isWhitespace() || c in DELIMITERS

        private const val DELIMITERS = ".,!?;:\"')(]["
    }
}
