package com.chronicle.app.ai

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenAiPeriodDigestTest {
    @Test
    fun samplePeriodSnippets_respectsCharBudget() {
        val snippets = (1..20).map { i -> "Entry $i: " + "x".repeat(400) }
        val sampled = GenAiService.samplePeriodSnippets(snippets, charBudget = 1_000)
        assertTrue(sampled.length <= 1_000)
        assertTrue(sampled.contains("Entry 1:"))
    }

    @Test
    fun samplePeriodSnippets_capsEntryCount() {
        val snippets = (1..100).map { "note $it with enough text" }
        val sampled = GenAiService.samplePeriodSnippets(snippets, maxEntries = 5)
        assertEquals(5, sampled.split("---").size)
    }

    @Test
    fun samplePeriodSnippets_emptyWhenBlank() {
        assertEquals("", GenAiService.samplePeriodSnippets(listOf("  ", "")))
    }

    @Test
    fun periodCacheKeys_areStable() {
        assertEquals(
            "week:2026-07-13",
            GenAiService.periodCacheKeyWeek(LocalDate.of(2026, 7, 13)),
        )
        assertEquals(
            "month:2026-07",
            GenAiService.periodCacheKeyMonth(YearMonth.of(2026, 7)),
        )
    }
}
