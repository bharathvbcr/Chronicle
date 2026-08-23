package com.chronicle.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Calendar / mood helpers for Timeline Week & Month modes (Monday week start). */
object TimelinePeriod {
    fun weekStartMonday(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun weekEndSunday(weekStart: LocalDate): LocalDate = weekStart.plusDays(6)

    /** Mon–Sun grid covering [yearMonth], including out-of-month padding days. */
    fun monthGrid(yearMonth: YearMonth): List<LocalDate> {
        val first = yearMonth.atDay(1)
        val last = yearMonth.atEndOfMonth()
        val start = weekStartMonday(first)
        val end = weekStartMonday(last).plusDays(6)
        val count = ChronoUnit.DAYS.between(start, end).toInt() + 1
        return List(count) { i -> start.plusDays(i.toLong()) }
    }

    /**
     * Average of moods in 1–5 → nearest [MOOD_FACES] glyph.
     * Returns null when there are no valid moods.
     */
    fun avgMoodFace(moods: Collection<Int>): String? {
        val valid = moods.filter { it in 1..5 }
        if (valid.isEmpty()) return null
        val nearest = valid.average().roundToInt().coerceIn(1, 5)
        return MOOD_FACES[nearest - 1]
    }

    /** Mood glyph, count-dot marker, or blank for a day's entries. */
    fun dayCellMarker(entries: List<Entry>): DayCellMarker {
        if (entries.isEmpty()) return DayCellMarker.Empty
        val face = avgMoodFace(entries.mapNotNull { it.mood })
        return if (face != null) DayCellMarker.Face(face) else DayCellMarker.Dot
    }

    fun weeklyRollupPaths(weekStart: LocalDate): List<String> {
        val key = weekStart.toString()
        return listOf(
            "_system/derived/weekly/$key.md",
            "notes/weekly/$key.md",
        )
    }

    fun monthlyRollupPaths(yearMonth: YearMonth): List<String> {
        val key = yearMonth.toString()
        return listOf(
            "_system/derived/monthly/$key.md",
            "notes/monthly/$key.md",
        )
    }
}

sealed class DayCellMarker {
    data class Face(val emoji: String) : DayCellMarker()
    data object Dot : DayCellMarker()
    data object Empty : DayCellMarker()
}

/**
 * Local calendar day for an entry: offset wall-date from [Entry.ts], else id date via [entryDayDate].
 */
fun entryLocalDate(entry: Entry): LocalDate {
    return try {
        ZonedDateTime.parse(entry.ts).toLocalDate()
    } catch (_: Exception) {
        runCatching { LocalDate.parse(entryDayDate(entry)) }.getOrElse { LocalDate.now() }
    }
}
