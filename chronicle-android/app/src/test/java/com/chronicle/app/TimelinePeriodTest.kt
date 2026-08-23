package com.chronicle.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelinePeriodTest {
    @Test
    fun weekStartMonday_returnsMonday() {
        // Wednesday 2026-07-15 → Monday 2026-07-13
        assertEquals(LocalDate.of(2026, 7, 13), TimelinePeriod.weekStartMonday(LocalDate.of(2026, 7, 15)))
        assertEquals(LocalDate.of(2026, 7, 13), TimelinePeriod.weekStartMonday(LocalDate.of(2026, 7, 13)))
        assertEquals(DayOfWeek.MONDAY, TimelinePeriod.weekStartMonday(LocalDate.of(2026, 7, 19)).dayOfWeek)
    }

    @Test
    fun monthGrid_mondayStartWithPadding() {
        val grid = TimelinePeriod.monthGrid(YearMonth.of(2026, 7))
        assertEquals(LocalDate.of(2026, 6, 29), grid.first()) // Mon padding
        assertEquals(DayOfWeek.MONDAY, grid.first().dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, grid.last().dayOfWeek)
        assertTrue(grid.size % 7 == 0)
        assertTrue(LocalDate.of(2026, 7, 1) in grid)
        assertTrue(LocalDate.of(2026, 7, 31) in grid)
    }

    @Test
    fun avgMoodFace_averagesToNearestFace() {
        assertNull(TimelinePeriod.avgMoodFace(emptyList()))
        assertEquals(MOOD_FACES[2], TimelinePeriod.avgMoodFace(listOf(3)))
        // (1+5)/2 = 3 → neutral
        assertEquals(MOOD_FACES[2], TimelinePeriod.avgMoodFace(listOf(1, 5)))
        // (5+5+4)/3 ≈ 4.67 → 5
        assertEquals(MOOD_FACES[4], TimelinePeriod.avgMoodFace(listOf(5, 5, 4)))
        assertNull(TimelinePeriod.avgMoodFace(listOf(0, 9)))
    }

    @Test
    fun dayCellMarker_faceDotOrEmpty() {
        assertEquals(DayCellMarker.Empty, TimelinePeriod.dayCellMarker(emptyList()))
        val withMood = Entry(
            id = "2026-07-15_120000-an",
            ts = "2026-07-15T12:00:00-07:00",
            type = "log",
            text = "hi",
            tags = emptyList(),
            mood = 4,
        )
        assertEquals(DayCellMarker.Face(MOOD_FACES[3]), TimelinePeriod.dayCellMarker(listOf(withMood)))
        val noMood = withMood.copy(mood = null)
        assertEquals(DayCellMarker.Dot, TimelinePeriod.dayCellMarker(listOf(noMood)))
    }

    @Test
    fun rollupPaths_preferDerivedThenLegacy() {
        val week = LocalDate.of(2026, 7, 13)
        assertEquals(
            listOf("_system/derived/weekly/2026-07-13.md", "notes/weekly/2026-07-13.md"),
            TimelinePeriod.weeklyRollupPaths(week),
        )
        assertEquals(
            listOf("_system/derived/monthly/2026-07.md", "notes/monthly/2026-07.md"),
            TimelinePeriod.monthlyRollupPaths(YearMonth.of(2026, 7)),
        )
    }
}
