package com.snapkeys.app.ime

import com.snapkeys.app.data.Shortcut

/**
 * Pure text-expansion logic, kept free of Android dependencies so it can be
 * unit-tested in isolation.
 *
 * The engine watches the "word" immediately preceding the cursor. When a
 * delimiter is typed, it checks whether that word matches an enabled trigger
 * and, if so, reports how many characters to delete and what to insert.
 */
class ExpansionEngine(shortcuts: List<Shortcut>) {

    // Case-insensitive lookup of enabled triggers.
    private val byTrigger: Map<String, Shortcut> =
        shortcuts.filter { it.enabled && it.trigger.isNotEmpty() }
            .associateBy { it.trigger.lowercase() }

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
        val word = trailingWord(textBeforeCursor)
        if (word.isEmpty()) return null
        val match = byTrigger[word.lowercase()] ?: return null
        val insert = if (delimiter != null) match.expansion + delimiter else match.expansion
        return Expansion(deleteBefore = word.length, insert = insert)
    }

    /** Extracts the run of non-delimiter characters ending at the cursor. */
    private fun trailingWord(text: CharSequence): String {
        var end = text.length
        var start = end
        while (start > 0 && !isDelimiter(text[start - 1])) start--
        return text.subSequence(start, end).toString()
    }

    companion object {
        fun isDelimiter(c: Char): Boolean =
            c.isWhitespace() || c in DELIMITERS

        private const val DELIMITERS = ".,!?;:\"')(]["
    }
}
