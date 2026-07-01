package com.snapkeys.app.ime

import com.snapkeys.app.data.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpansionEngineTest {

    private val engine = ExpansionEngine(
        listOf(
            Shortcut("brb", "be right back"),
            Shortcut("omw", "on my way"),
            Shortcut("off", "off", enabled = false),
            Shortcut("ty", "thank you"),
            Shortcut("@@", "your.email@example.com"),
            Shortcut("!!", "double bang"),
            Shortcut("addr.", "123 Main St"),
        )
    )

    @Test
    fun `expands a matching trigger on space`() {
        val result = engine.onDelimiter("brb", ' ')
        assertEquals(3, result?.deleteBefore)
        assertEquals("be right back ", result?.insert)
    }

    @Test
    fun `keeps the delimiter that was typed`() {
        val result = engine.onDelimiter("say brb", '.')
        assertEquals("be right back.", result?.insert)
    }

    @Test
    fun `matches case-insensitively`() {
        val result = engine.onDelimiter("BRB", ' ')
        assertEquals("be right back ", result?.insert)
    }

    @Test
    fun `only considers the trailing word`() {
        val result = engine.onDelimiter("hello omw", ' ')
        assertEquals(3, result?.deleteBefore)
        assertEquals("on my way ", result?.insert)
    }

    @Test
    fun `returns null for an unknown trigger`() {
        assertNull(engine.onDelimiter("hello", ' '))
    }

    @Test
    fun `ignores disabled shortcuts`() {
        assertNull(engine.onDelimiter("off", ' '))
    }

    @Test
    fun `enter expansion omits a delimiter`() {
        val result = engine.onDelimiter("brb", null)
        assertEquals("be right back", result?.insert)
    }

    @Test
    fun `word trigger does not fire inside a longer word`() {
        assertNull(engine.onDelimiter("pretty", ' '))
    }

    @Test
    fun `trigger may contain punctuation`() {
        val result = engine.onDelimiter("addr.", ' ')
        assertEquals(5, result?.deleteBefore)
        assertEquals("123 Main St ", result?.insert)
    }

    @Test
    fun `symbol trigger expands`() {
        val result = engine.onDelimiter("@@", ' ')
        assertEquals("your.email@example.com ", result?.insert)
    }

    @Test
    fun `symbol trigger may attach to preceding text`() {
        val result = engine.onDelimiter("hey!!", ' ')
        assertEquals(2, result?.deleteBefore)
        assertEquals("double bang ", result?.insert)
    }

    @Test
    fun `longest matching trigger wins`() {
        // "addr." ends in a period; the period alone must not shadow it.
        val result = engine.onDelimiter("my addr.", '\n')
        assertEquals(5, result?.deleteBefore)
    }
}
