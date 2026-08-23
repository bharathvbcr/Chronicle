package com.chronicle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTextHelpersTest {

    @Test
    fun hasMarkdownSyntax_plainTextIsFalse() {
        assertFalse(hasMarkdownSyntax(""))
        assertFalse(hasMarkdownSyntax("   "))
        assertFalse(hasMarkdownSyntax("just a plain journal entry"))
        assertFalse(hasMarkdownSyntax("email me at user@example.com"))
    }

    @Test
    fun hasMarkdownSyntax_detectsInlineMarkers() {
        assertTrue(hasMarkdownSyntax("this is **bold** text"))
        assertTrue(hasMarkdownSyntax("an *italic* word"))
        assertTrue(hasMarkdownSyntax("~~struck~~ out"))
        assertTrue(hasMarkdownSyntax("inline `code` span"))
        assertTrue(hasMarkdownSyntax("a [link](https://example.com)"))
        assertTrue(hasMarkdownSyntax("wikilink to [[Project X]]"))
    }

    @Test
    fun hasMarkdownSyntax_detectsBlockMarkers() {
        assertTrue(hasMarkdownSyntax("# Heading"))
        assertTrue(hasMarkdownSyntax("## Sub heading"))
        assertTrue(hasMarkdownSyntax("> quoted line"))
        assertTrue(hasMarkdownSyntax("- bullet item"))
        assertTrue(hasMarkdownSyntax("  * nested bullet"))
        assertTrue(hasMarkdownSyntax("1. numbered item"))
        assertTrue(hasMarkdownSyntax("first line\n- second line bullet"))
    }

    @Test
    fun hasMarkdownSyntax_ignoresNonMarkerPunctuation() {
        assertFalse(hasMarkdownSyntax("5.25 is a number, 3.14 too"))
        assertFalse(hasMarkdownSyntax("use the * star symbol alone"))
        assertFalse(hasMarkdownSyntax("a-b-c dashes without spaces"))
    }

    @Test
    fun wordCount_countsWhitespaceRuns() {
        assertEquals(0, wordCount(""))
        assertEquals(0, wordCount("   \n  "))
        assertEquals(1, wordCount("hello"))
        assertEquals(3, wordCount("one two  three"))
        assertEquals(4, wordCount("line one\nline two"))
        assertEquals(2, wordCount("  padded words  "))
    }

    @Test
    fun formatClipDuration_formatsAsMinutesSeconds() {
        assertEquals("0:00", formatClipDuration(0))
        assertEquals("0:07", formatClipDuration(7_000))
        assertEquals("0:59", formatClipDuration(59_000))
        assertEquals("1:00", formatClipDuration(60_000))
        assertEquals("1:23", formatClipDuration(83_000))
        assertEquals("12:05", formatClipDuration(725_000))
    }

    @Test
    fun formatClipDuration_clampsNegativeToZero() {
        assertEquals("0:00", formatClipDuration(-500))
    }
}
