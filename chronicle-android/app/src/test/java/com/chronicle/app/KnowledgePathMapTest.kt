package com.chronicle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgePathMapTest {

    @Test
    fun normalizeApiPath_paraAndBare() {
        assertEquals("00-Inbox/idea.md", KnowledgePathMap.normalizeApiPath("00-Inbox/idea.md"))
        assertEquals("kb/notes/idea.md", KnowledgePathMap.normalizeApiPath("kb/notes/idea.md"))
        assertEquals("00-Inbox/idea.md", KnowledgePathMap.normalizeApiPath("idea.md"))
        assertEquals(
            "10-Work/ResumePoints/Foo.md",
            KnowledgePathMap.normalizeApiPath("ResumePoints/Foo.md"),
        )
        assertNull(KnowledgePathMap.normalizeApiPath("kb/notes"))
    }

    @Test
    fun preferredWriteRel_legacyMapsToPara() {
        assertEquals(
            "00-Inbox/x.md",
            KnowledgePathMap.preferredWriteRel("kb/notes/x.md", create = true),
        )
        assertEquals(
            "10-Work/ResumePoints/a.md",
            KnowledgePathMap.preferredWriteRel("kb/notes/ResumePoints/a.md", create = true),
        )
        assertEquals(
            "30-Knowledge/skills.md",
            KnowledgePathMap.preferredWriteRel("30-Knowledge/skills.md", create = true),
        )
    }

    @Test
    fun candidateReadRels_paraOnlyNoLegacyPeers() {
        val cands = KnowledgePathMap.candidateReadRels("10-Work/Projects/idea.md")
        assertEquals(listOf("10-Work/Projects/idea.md"), cands)

        val legacy = KnowledgePathMap.candidateReadRels("kb/notes/Projects/idea.md")
        assertTrue(legacy.isEmpty())
    }

    @Test
    fun candidateReadRels_resumePointsBareMapsWork() {
        val cands = KnowledgePathMap.candidateReadRels("ResumePoints/Foo.md")
        assertEquals(listOf("10-Work/ResumePoints/Foo.md"), cands)
    }

    @Test
    fun validateRejectsTraversalAndLegacy() {
        assertNull(KnowledgePathMap.validateKnowledgeRel("../escape.md"))
        assertNull(KnowledgePathMap.validateKnowledgeRel("kb/notes/../secret.md"))
        assertNull(KnowledgePathMap.validateKnowledgeRel("kb/notes/alive.md"))
        assertNull(KnowledgePathMap.validateKnowledgeRel("readme.txt"))
        assertEquals(
            "00-Inbox/alive.md",
            KnowledgePathMap.validateKnowledgeRel("00-Inbox/alive.md"),
        )
    }

    @Test
    fun createPath_defaultsInbox() {
        assertEquals("00-Inbox/Skills.md", KnowledgePathMap.createPath("Skills"))
        assertEquals(
            "30-Knowledge/Skills.md",
            KnowledgePathMap.createPath("Skills", area = "30-Knowledge"),
        )
    }

    @Test
    fun sectionFor_and_defaults() {
        assertEquals(KnowledgePathMap.SECTION_KB, KnowledgePathMap.sectionFor("30-Knowledge/foo.md"))
        assertNull(KnowledgePathMap.sectionFor("kb/notes/foo.md"))
        assertEquals(KnowledgePathMap.SECTION_NOTES, KnowledgePathMap.sectionFor("00-Inbox/foo.md"))
        assertEquals(KnowledgePathMap.SECTION_NOTES, KnowledgePathMap.sectionFor("10-Work/x.md"))
        assertNull(KnowledgePathMap.sectionFor("40-Journal/2026-07-09.md"))
        assertEquals(KnowledgePathMap.KB_AREA, KnowledgePathMap.defaultCreateArea(KnowledgePathMap.SECTION_KB))
        assertEquals("00-Inbox", KnowledgePathMap.defaultCreateArea(KnowledgePathMap.SECTION_NOTES))
    }

    @Test
    fun createPath_sectionScoped() {
        assertEquals(
            "30-Knowledge/Skills.md",
            KnowledgePathMap.createPath("Skills", section = KnowledgePathMap.SECTION_KB),
        )
        assertEquals(
            "00-Inbox/Skills.md",
            KnowledgePathMap.createPath("Skills", section = KnowledgePathMap.SECTION_NOTES),
        )
        assertNull(
            KnowledgePathMap.createPath(
                "Skills",
                area = "30-Knowledge",
                section = KnowledgePathMap.SECTION_NOTES,
            ),
        )
        assertNull(
            KnowledgePathMap.createPath(
                "Skills",
                area = "00-Inbox",
                section = KnowledgePathMap.SECTION_KB,
            ),
        )
    }

    @Test
    fun chromeAndJournalHelpers() {
        assertTrue(KnowledgePathMap.isChromeBasename("CLAUDE.md"))
        assertTrue(KnowledgePathMap.isChromePath("30-Knowledge/README.md"))
        assertFalse(KnowledgePathMap.isChromeBasename("MOC-Work.md"))
        assertTrue(KnowledgePathMap.isJournalDayFile("2026-07-09.md"))
        assertFalse(KnowledgePathMap.isJournalDayFile("CLAUDE.md"))
        assertTrue(KnowledgePathMap.isJournalFencePath("40-Journal/2026-07-09.md"))
        assertTrue(KnowledgePathMap.isJournalDerivedPath("Upcoming.md"))
        assertTrue(KnowledgePathMap.isJournalDerivedPath("_system/derived/daily/x.md"))
    }

    @Test
    fun categoryFor_paraOnly() {
        assertEquals("00-Inbox", KnowledgePathMap.categoryFor("00-Inbox/a.md"))
        assertEquals("other", KnowledgePathMap.categoryFor("kb/notes/a.md"))
    }

    @Test
    fun resolveKbNotePath_vaultSegments() {
        assertEquals(
            listOf("00-Inbox") to "Skills.md",
            resolveKbNotePath("Skills.md"),
        )
        assertEquals(
            listOf("00-Inbox") to "Foo.md",
            resolveKbNotePath("00-Inbox/Foo.md"),
        )
        assertNull(resolveKbNotePath("kb/notes/ResumePoints/Foo.md"))
        assertNull(resolveKbNotePath("../escape.md"))
        assertNull(resolveKbNotePath(""))
    }

    @Test
    fun kbNoteFileNameSanitizes() {
        assertEquals("My-Note.md", kbNoteFileName("My Note"))
        assertEquals("ResumePoints/Skills.md", kbNoteFileName("Skills", folder = "ResumePoints"))
        assertEquals("already.md", kbNoteFileName("already.md"))
    }

    @Test
    fun isParaPrefix() {
        assertTrue(KnowledgePathMap.isParaPrefix("10-Work/ResumePoints/x.md"))
        assertFalse(KnowledgePathMap.isParaPrefix("kb/notes/x.md"))
        assertFalse(KnowledgePathMap.isKnowledgePath("kb/notes/x.md"))
        assertTrue(KnowledgePathMap.isKnowledgePath("30-Knowledge/x.md"))
        assertTrue(KnowledgePathMap.isLegacyKbPath("kb/notes/x.md"))
    }

    @Test
    fun parentFolder_stripsBasename() {
        assertEquals("10-Work/Projects", KnowledgePathMap.parentFolder("10-Work/Projects/note.md"))
        assertEquals("10-Work", KnowledgePathMap.parentFolder("10-Work/note.md"))
        assertEquals("", KnowledgePathMap.parentFolder("Home.md"))
        assertEquals("30-Knowledge/Topics", KnowledgePathMap.parentFolder("30-Knowledge/Topics/Skills.md"))
    }

    @Test
    fun splitCreateFolderContext_areaAndRelativeFolder() {
        assertEquals(
            CreateFolderContext("10-Work", "Projects"),
            KnowledgePathMap.splitCreateFolderContext("10-Work/Projects"),
        )
        assertEquals(
            CreateFolderContext("00-Inbox", ""),
            KnowledgePathMap.splitCreateFolderContext("00-Inbox"),
        )
        assertEquals(
            CreateFolderContext("30-Knowledge", "Topics/Active"),
            KnowledgePathMap.splitCreateFolderContext("30-Knowledge/Topics/Active"),
        )
        assertNull(KnowledgePathMap.splitCreateFolderContext(""))
        assertNull(KnowledgePathMap.splitCreateFolderContext("not-a-para/folder"))
    }

    @Test
    fun createPath_fromSplitCreateFolderContext() {
        val ctx = KnowledgePathMap.splitCreateFolderContext("10-Work/Projects")!!
        assertEquals(
            "10-Work/Projects/Skills.md",
            KnowledgePathMap.createPath(
                "Skills",
                area = ctx.area,
                folder = ctx.folder.ifBlank { null },
                section = KnowledgePathMap.SECTION_NOTES,
            ),
        )
        val areaOnly = KnowledgePathMap.splitCreateFolderContext("30-Knowledge")!!
        assertEquals(
            "30-Knowledge/Skills.md",
            KnowledgePathMap.createPath(
                "Skills",
                area = areaOnly.area,
                folder = areaOnly.folder.ifBlank { null },
                section = KnowledgePathMap.SECTION_KB,
            ),
        )
    }
}
