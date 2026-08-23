package com.chronicle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalFencesTest {

    // Matches the exact shape journal.py's wrap_entry_fence/upsert_entry_block produce.
    private val dayText = """
        # 2026-07-09

        <!-- entry:2026-07-09_213045-an -->
        ### 2026-07-09_213045-an · log

        tags: work

        First entry text.

        [[entry:2026-07-09_213045-an]]
        <!-- /entry:2026-07-09_213045-an -->

        <!-- entry:2026-07-09_220000-pc -->
        ### 2026-07-09_220000-pc · idea

        Second entry text.
        [[entry:2026-07-09_220000-pc]]
        <!-- /entry:2026-07-09_220000-pc -->
    """.trimIndent()

    @Test
    fun listFencedIds_returnsIdsInDocumentOrder() {
        assertEquals(
            listOf("2026-07-09_213045-an", "2026-07-09_220000-pc"),
            JournalFences.listFencedIds(dayText),
        )
    }

    @Test
    fun extractBlock_returnsInnerBodyForEachEntry() {
        val first = JournalFences.extractBlock(dayText, "2026-07-09_213045-an")
        assertTrue(first != null && "First entry text." in first)
        val second = JournalFences.extractBlock(dayText, "2026-07-09_220000-pc")
        assertTrue(second != null && "Second entry text." in second)
    }

    @Test
    fun extractBlock_missingFenceReturnsNull() {
        assertNull(JournalFences.extractBlock(dayText, "2026-07-09_999999-an"))
    }

    @Test
    fun extractBlock_missingCloseFenceReturnsNull() {
        val broken = "<!-- entry:2026-07-09_213045-an -->\nbody with no close"
        assertNull(JournalFences.extractBlock(broken, "2026-07-09_213045-an"))
    }

    @Test
    fun splitEntries_pairsIdsWithBodies() {
        val entries = JournalFences.splitEntries(dayText)
        assertEquals(2, entries.size)
        assertEquals("2026-07-09_213045-an", entries[0].id)
        assertTrue("First entry text." in entries[0].body)
        assertEquals("2026-07-09_220000-pc", entries[1].id)
        assertTrue("Second entry text." in entries[1].body)
    }

    @Test
    fun splitEntries_skipsIdsWithMissingCloseFence() {
        val partial = "<!-- entry:2026-07-09_213045-an -->\nno close here"
        assertEquals(emptyList<JournalFences.FenceEntry>(), JournalFences.splitEntries(partial))
    }

    @Test
    fun collisionSuffixIdsAreMatched() {
        val text = "<!-- entry:2026-07-09_213045-an_2 -->\nbody\n<!-- /entry:2026-07-09_213045-an_2 -->"
        assertEquals(listOf("2026-07-09_213045-an_2"), JournalFences.listFencedIds(text))
        // Mirrors chronicle_pipeline.journal.extract_block: body keeps the trailing newline.
        assertEquals("body\n", JournalFences.extractBlock(text, "2026-07-09_213045-an_2"))
    }

    @Test
    fun emptyDocumentHasNoEntries() {
        assertEquals(emptyList<String>(), JournalFences.listFencedIds("# 2026-07-09\n\nno entries yet\n"))
    }
}
