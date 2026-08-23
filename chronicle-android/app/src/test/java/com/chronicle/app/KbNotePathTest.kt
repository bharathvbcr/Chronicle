package com.chronicle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Path helpers after dual-read cutover (PARA-only). */
class KbNotePathTest {

    @Test
    fun resolveBareFileAsInbox() {
        assertEquals(
            listOf("00-Inbox") to "Skills.md",
            resolveKbNotePath("Skills.md"),
        )
    }

    @Test
    fun resolveNestedParaAndResumePoints() {
        assertNull(resolveKbNotePath("kb/notes/ResumePoints/Foo.md"))
        assertEquals(
            listOf("10-Work", "ResumePoints") to "Foo.md",
            resolveKbNotePath("ResumePoints/Foo.md"),
        )
        assertEquals(
            listOf("00-Inbox") to "Foo.md",
            resolveKbNotePath("00-Inbox/Foo.md"),
        )
    }

    @Test
    fun rejectTraversalAndNonMarkdown() {
        assertNull(resolveKbNotePath("../escape.md"))
        assertNull(resolveKbNotePath("kb/notes/../secret.md"))
        assertNull(resolveKbNotePath("readme.txt"))
        assertNull(resolveKbNotePath(""))
        assertNull(resolveKbNotePath("folder/"))
    }

    @Test
    fun kbNoteFileNameSanitizes() {
        assertEquals("My-Note.md", kbNoteFileName("My Note"))
        assertEquals("ResumePoints/Skills.md", kbNoteFileName("Skills", folder = "ResumePoints"))
        assertEquals("already.md", kbNoteFileName("already.md"))
    }
}
