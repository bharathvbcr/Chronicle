package com.chronicle.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineDateGroupingTest {
    @Test
    fun groupEntriesByDay_keepsSameCalendarDateAcrossYearsSeparate() {
        val a = Entry(
            id = "2024-07-09T10-00-00",
            ts = "2024-07-09T10:00:00-07:00",
            type = "log",
            text = "last year",
            tags = emptyList(),
            processed = false,
        )
        val b = Entry(
            id = "2025-07-09T10-00-00",
            ts = "2025-07-09T10:00:00-07:00",
            type = "log",
            text = "this year",
            tags = emptyList(),
            processed = false,
        )
        val groups = groupEntriesByDay(listOf(a, b))
        assertEquals(2, groups.size)
        assertEquals(LocalDate.of(2025, 7, 9), groups[0].first)
        assertEquals(LocalDate.of(2024, 7, 9), groups[1].first)
    }

    @Test
    fun formatDayLabel_usesRelativeAndYearAwareLabels() {
        val today = LocalDate.of(2026, 7, 9)
        assertEquals("Today", formatDayLabel(today, today))
        assertEquals("Yesterday", formatDayLabel(today.minusDays(1), today))
        assertTrue(formatDayLabel(today.minusDays(3), today).isNotBlank())
        assertTrue(formatDayLabel(LocalDate.of(2026, 3, 1), today).contains("2026").not())
        assertTrue(formatDayLabel(LocalDate.of(2025, 7, 8), today).contains("2025"))
    }

    @Test
    fun groupEntriesByDay_parseFailureFallsBackToIdDate() {
        val entry = Entry(
            id = "2026-03-15_090000-an",
            ts = "not-a-valid-timestamp",
            type = "log",
            text = "fallback",
            tags = emptyList(),
            processed = false,
        )
        val groups = groupEntriesByDay(listOf(entry))
        assertEquals(1, groups.size)
        assertEquals(LocalDate.of(2026, 3, 15), groups[0].first)
        assertTrue(groups[0].first != LocalDate.MIN)
    }
}
