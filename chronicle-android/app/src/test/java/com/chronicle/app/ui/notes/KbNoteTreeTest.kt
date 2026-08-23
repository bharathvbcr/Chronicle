package com.chronicle.app.ui.notes

import com.chronicle.app.KbNoteRef
import com.chronicle.app.KnowledgePathMap
import com.chronicle.app.NoteRef
import com.chronicle.app.resolveWikilinkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KbNoteTreeTest {
    @Test
    fun buildKbSectionTree_nestsFoldersUnderAreas() {
        val notes = listOf(
            KbNoteRef("Home.md", "Home.md", "Hub", ""),
            KbNoteRef("00-Inbox/a.md", "a.md", "00-Inbox", ""),
            KbNoteRef("10-Work/Projects/Acme/note.md", "note.md", "10-Work", ""),
            KbNoteRef("10-Work/MOC-Work.md", "MOC-Work.md", "10-Work", ""),
            KbNoteRef("30-Knowledge/Skills.md", "Skills.md", "30-Knowledge", ""),
        )
        val tree = buildKbSectionTree(notes, KnowledgePathMap.SECTION_NOTES)
        assertEquals(listOf("Home.md", "10-Work/MOC-Work.md"), tree.hubs.map { it.path })
        assertEquals(listOf("00-Inbox", "10-Work"), tree.areaRoots.map { it.path })
        val work = tree.areaRoots.first { it.path == "10-Work" }
        val projects = work.children.filterIsInstance<KbTreeUiNode.Folder>().first()
        assertEquals("Projects", projects.name)
        val acme = projects.children.filterIsInstance<KbTreeUiNode.Folder>().first()
        assertEquals("Acme", acme.name)
        assertEquals("10-Work/Projects/Acme/note.md", (acme.children.first() as KbTreeUiNode.File).note.path)
    }

    @Test
    fun buildKbSectionTree_kbShowsHomeHubAndKnowledgeArea() {
        val notes = listOf(
            KbNoteRef("Home.md", "Home.md", "Hub", ""),
            KbNoteRef("30-Knowledge/x.md", "x.md", "30-Knowledge", ""),
        )
        // Simulate section-filtered list (Home not included) + full vault list
        val sectionOnly = notes.filter { it.path.startsWith("30-Knowledge") }
        val tree = buildKbSectionTree(sectionOnly, KnowledgePathMap.SECTION_KB, allNotes = notes)
        assertEquals(listOf("Home.md"), tree.hubs.map { it.path })
        assertEquals(listOf("30-Knowledge"), tree.areaRoots.map { it.path })
    }

    @Test
    fun resolveWikilink_crossSectionIncludesJournal() {
        val kb = listOf(KbNoteRef("30-Knowledge/a.md", "a.md", "30-Knowledge", ""))
        val journal = listOf(NoteRef("40-Journal/2026-07-09.md", "2026-07-09.md", "journal", ""))
        assertEquals(
            "40-Journal/2026-07-09.md",
            resolveWikilinkTarget("40-Journal/2026-07-09", kb, journal),
        )
        assertEquals("30-Knowledge/a.md", resolveWikilinkTarget("a", kb, journal))
        assertEquals("Home.md", resolveWikilinkTarget("Home", kb, journal))
        assertTrue(resolveWikilinkTarget("missing", kb, journal) == null)
    }

    @Test
    fun flattenVisibleKbTree_allCollapsed_onlyAreaFolderRows() {
        val tree = buildKbSectionTree(
            listOf(
                KbNoteRef("00-Inbox/a.md", "a.md", "00-Inbox", ""),
                KbNoteRef("10-Work/Projects/Acme/note.md", "note.md", "10-Work", ""),
            ),
            KnowledgePathMap.SECTION_NOTES,
        )
        val rows = flattenVisibleKbTree(tree.areaRoots, expanded = emptySet())
        assertEquals(
            listOf("00-Inbox", "10-Work"),
            rows.filterIsInstance<KbVisibleRow.Folder>().map { it.path },
        )
        assertTrue(rows.none { it is KbVisibleRow.File })
        assertTrue(rows.filterIsInstance<KbVisibleRow.Folder>().all { !it.expanded })
    }

    @Test
    fun flattenVisibleKbTree_expandOneFolder_showsImmediateChildren() {
        val tree = buildKbSectionTree(
            listOf(
                KbNoteRef("10-Work/root.md", "root.md", "10-Work", ""),
                KbNoteRef("10-Work/Projects/Acme/note.md", "note.md", "10-Work", ""),
            ),
            KnowledgePathMap.SECTION_NOTES,
        )
        val rows = flattenVisibleKbTree(tree.areaRoots, expanded = setOf("10-Work"))
        assertEquals(
            listOf(
                KbVisibleRow.Folder("10-Work", "10-Work", 0, 2, true),
                KbVisibleRow.Folder("10-Work/Projects", "Projects", 1, 1, false),
                KbVisibleRow.File(
                    KbNoteRef("10-Work/root.md", "root.md", "10-Work", ""),
                    depth = 1,
                ),
            ),
            rows,
        )
    }

    @Test
    fun countDescendantNotes_includesNestedFiles() {
        val tree = buildKbSectionTree(
            listOf(
                KbNoteRef("10-Work/a.md", "a.md", "10-Work", ""),
                KbNoteRef("10-Work/Projects/b.md", "b.md", "10-Work", ""),
                KbNoteRef("10-Work/Projects/Acme/c.md", "c.md", "10-Work", ""),
            ),
            KnowledgePathMap.SECTION_NOTES,
        )
        val work = tree.areaRoots.first { it.path == "10-Work" }
        assertEquals(3, countDescendantNotes(work))
        val projects = work.children.filterIsInstance<KbTreeUiNode.Folder>().first()
        assertEquals(2, countDescendantNotes(projects))
        val flattened = flattenVisibleKbTree(tree.areaRoots, expanded = emptySet())
        assertEquals(3, (flattened.first() as KbVisibleRow.Folder).noteCount)
    }

    @Test
    fun defaultExpandedKbFolders_autoExpandsSoleKbRoot() {
        val tree = buildKbSectionTree(
            listOf(KbNoteRef("30-Knowledge/Skills.md", "Skills.md", "30-Knowledge", "")),
            KnowledgePathMap.SECTION_KB,
        )
        assertEquals(
            setOf(KnowledgePathMap.KB_AREA),
            defaultExpandedKbFolders(tree.areaRoots, KnowledgePathMap.SECTION_KB),
        )
        assertEquals(
            emptySet<String>(),
            defaultExpandedKbFolders(tree.areaRoots, KnowledgePathMap.SECTION_NOTES),
        )
    }

    @Test
    fun defaultExpandedKbFolders_notesAreasStartCollapsed() {
        val tree = buildKbSectionTree(
            listOf(
                KbNoteRef("00-Inbox/a.md", "a.md", "00-Inbox", ""),
                KbNoteRef("10-Work/b.md", "b.md", "10-Work", ""),
            ),
            KnowledgePathMap.SECTION_NOTES,
        )
        assertEquals(
            emptySet<String>(),
            defaultExpandedKbFolders(tree.areaRoots, KnowledgePathMap.SECTION_NOTES),
        )
    }

    @Test
    fun filterAndRankKbNotes_nameHitBeforePathOnlyHit() {
        val notes = listOf(
            KbNoteRef("10-Work/Projects/Skills/readme.md", "readme.md", "10-Work", ""),
            KbNoteRef("30-Knowledge/Skills.md", "Skills.md", "30-Knowledge", ""),
            KbNoteRef("10-Work/Other.md", "Other.md", "10-Work", ""),
        )
        val ranked = filterAndRankKbNotes(notes, "skills")
        assertEquals(
            listOf("30-Knowledge/Skills.md", "10-Work/Projects/Skills/readme.md"),
            ranked.map { it.path },
        )
    }

    @Test
    fun noteDisplayHelpers_titleAndBreadcrumb() {
        assertEquals("Skills", noteDisplayTitle("Skills.md"))
        assertEquals("10-Work/Projects", noteBreadcrumb("10-Work/Projects/note.md"))
        assertEquals("", noteBreadcrumb("Home.md"))
    }
}
