package com.snapkeys.app.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {

    @Test
    fun `finds emoji by keyword`() {
        assertTrue("🍕" in EmojiCatalog.search("pizza"))
        assertTrue("😂" in EmojiCatalog.search("laugh"))
        assertTrue("❤️" in EmojiCatalog.search("love"))
    }

    @Test
    fun `matches partial words`() {
        assertTrue("🎂" in EmojiCatalog.search("birth"))
    }

    @Test
    fun `empty and unknown queries return nothing`() {
        assertEquals(emptyList<String>(), EmojiCatalog.search(""))
        assertEquals(emptyList<String>(), EmojiCatalog.search("qzxvjw"))
    }

    @Test
    fun `every emoji in the categories has keywords`() {
        EmojiCatalog.CATEGORIES.flatMap { it.second }.forEach { emoji ->
            // A single-character prefix of any keyword must find its emoji;
            // simplest proxy: searching the emoji's own keywords works.
            assertTrue(
                "$emoji is missing from the keyword index",
                EmojiCatalog.search(firstKeywordOf(emoji)).contains(emoji),
            )
        }
    }

    private fun firstKeywordOf(emoji: String): String =
        // Search with a keyword we can recover only via the public API:
        // probe the alphabet and pick any query that returns this emoji.
        ('a'..'z').flatMap { a -> ('a'..'z').map { b -> "$a$b" } }
            .firstOrNull { EmojiCatalog.search(it, max = 500).contains(emoji) }
            ?: ""
}
