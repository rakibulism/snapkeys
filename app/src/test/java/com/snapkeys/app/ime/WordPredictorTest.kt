package com.snapkeys.app.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordPredictorTest {

    // Frequency-ordered, most common first.
    private val predictor = WordPredictor(
        listOf("the", "to", "that", "this", "thanks", "hello", "help", "here", "was", "what"),
    )

    @Test
    fun `completions are frequency ranked`() {
        assertEquals(listOf("the", "to", "that"), predictor.complete("t"))
        assertEquals(listOf("the", "that", "this"), predictor.complete("th"))
    }

    @Test
    fun `completion excludes the exact prefix`() {
        assertTrue("the" !in predictor.complete("the"))
    }

    @Test
    fun `learned words outrank the dictionary`() {
        predictor.learn("thorium")
        predictor.learn("thorium")
        assertEquals("thorium", predictor.complete("tho").first())
    }

    @Test
    fun `learning rejects non-words`() {
        predictor.learn("a1b2")
        assertTrue(predictor.learnedSnapshot().isEmpty())
    }

    @Test
    fun `gesture matches a word along the swiped path`() {
        // Swiping h→e→l→o on QWERTY crosses intermediate keys.
        val candidates = predictor.gesture("hgfdrel" + "kjlo")
        assertTrue("hello" in candidates)
    }

    @Test
    fun `gesture collapses double letters`() {
        // "hello" has "ll" but a swipe crosses the l key once.
        assertTrue("hello" in predictor.gesture("helo"))
    }

    @Test
    fun `gesture requires matching endpoints`() {
        // Path ends on p, "hello" ends on o — no match.
        assertTrue("hello" !in predictor.gesture("helop"))
    }

    @Test
    fun `gesture finds the word explaining the path`() {
        // h → e → r → e with keys crossed in between.
        val candidates = predictor.gesture("hgerte")
        assertEquals("here", candidates.first())
    }

    @Test
    fun `gesture ranks by frequency at equal length`() {
        // Both fit the c…e path at length 4; "came" is more common.
        val local = WordPredictor(listOf("came", "cake"))
        assertEquals("came", local.gesture("cakme").first())
    }

    @Test
    fun `gesture prefers the user's own words at equal length`() {
        val local = WordPredictor(listOf("came", "cake"))
        local.learn("cake")
        assertEquals("cake", local.gesture("cakme").first())
    }

    @Test
    fun `gesture still prefers the longer word over a nearby shorter one`() {
        val local = WordPredictor(listOf("he", "here"))
        assertEquals("here", local.gesture("hgerte").first())
    }

    @Test
    fun `learned words survive export and import`() {
        predictor.learn("zebra")
        val fresh = WordPredictor(listOf("the"))
        fresh.importLearned(predictor.learnedSnapshot())
        assertEquals(listOf("zebra"), fresh.complete("ze"))
    }
}
