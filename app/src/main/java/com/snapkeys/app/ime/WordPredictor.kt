package com.snapkeys.app.ime

/**
 * Word completion and swipe-gesture matching, free of Android dependencies.
 *
 * Backed by a frequency-ordered dictionary (most common word first) plus
 * words learned from the user's own typing, which always outrank the
 * dictionary. Powers the suggestion bar and gesture typing.
 */
class WordPredictor(dictionary: List<String>) {

    private val words: List<String> =
        dictionary.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    // Frequency rank per word: position in the dictionary, 0 = most common.
    private val rank = HashMap<String, Int>(words.size).apply {
        words.forEachIndexed { index, word -> putIfAbsent(word, index) }
    }

    // User vocabulary: word → times seen. Checked before the dictionary.
    private val learned = LinkedHashMap<String, Int>()

    fun learn(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() }) return
        learned[w] = (learned[w] ?: 0) + 1
    }

    fun learnedSnapshot(): Map<String, Int> = LinkedHashMap(learned)

    fun importLearned(entries: Map<String, Int>) {
        entries.forEach { (word, count) -> if (count > 0) learned[word.lowercase()] = count }
    }

    /** Completions for the word being typed, best first. */
    fun complete(prefix: String, max: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val fromLearned = learned.entries
            .filter { it.key.startsWith(p) && it.key != p }
            .sortedByDescending { it.value }
            .map { it.key }
        val fromDictionary = words.asSequence()
            .filter { it.startsWith(p) && it != p }
        return (fromLearned.asSequence() + fromDictionary).distinct().take(max).toList()
    }

    /**
     * Candidate words for a swipe [path] — the sequence of letter keys the
     * finger crossed. A word matches when its letters (consecutive doubles
     * collapsed, since a swipe crosses each key once) start and end with the
     * path's endpoints and appear in order along the path.
     *
     * Ranking blends length and frequency: each extra letter is worth
     * [LENGTH_SCORE] because a longer word explains more of the path, but a
     * shorter word that is more than [LENGTH_SCORE] dictionary ranks more
     * common wins instead. The user's own words score as if most common.
     */
    fun gesture(path: String, max: Int = 4): List<String> {
        if (path.length < 2) return emptyList()
        val first = path.first()
        val last = path.last()

        fun matches(word: String): Boolean {
            val compressed = compress(word)
            return compressed.length in 2..path.length &&
                compressed.first() == first &&
                compressed.last() == last &&
                isSubsequence(compressed, path)
        }

        val scored = HashMap<String, Int>()
        learned.forEach { (word, uses) ->
            if (matches(word)) scored[word] = compress(word).length * LENGTH_SCORE + uses
        }
        words.forEach { word ->
            if (word !in scored && matches(word)) {
                scored[word] = compress(word).length * LENGTH_SCORE - (rank[word] ?: words.size)
            }
        }
        return scored.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    private companion object {
        /** Rank gap a shorter word must overcome to beat a longer one. */
        const val LENGTH_SCORE = 2500
    }

    private fun compress(word: String): String = buildString {
        word.lowercase().forEach { if (isEmpty() || last() != it) append(it) }
    }

    private fun isSubsequence(word: String, path: String): Boolean {
        var i = 0
        for (c in path) {
            if (c == word[i]) i++
            if (i == word.length) return true
        }
        return false
    }
}
