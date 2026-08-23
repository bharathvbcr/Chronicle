package com.chronicle.app

/**
 * Pure-Kotlin mirror of chronicle_pipeline.journal's fence regex/extract
 * (`<!-- entry:<id> --> … <!-- /entry:<id> -->`). Read-only: used to split a
 * locally-loaded 40-Journal day file into per-entry cards. Amending a fence
 * body always goes through serve `PATCH /journal/entries/{id}` — this object
 * never writes.
 */
object JournalFences {
    private val FENCE_OPEN = Regex(
        """<!--\s*entry:(\d{4}-\d{2}-\d{2}_\d{6}-(?:an|pc)(?:_\d+)?)\s*-->""",
    )

    data class FenceEntry(val id: String, val body: String)

    /** Entry ids in document order, as they appear in the day file. */
    fun listFencedIds(dayText: String): List<String> =
        FENCE_OPEN.findAll(dayText).map { it.groupValues[1] }.toList()

    /** Inner body between the open/close fence comments for [entryId], or null if missing. */
    fun extractBlock(dayText: String, entryId: String): String? {
        val escaped = Regex.escape(entryId)
        val openMatch = Regex("""<!--\s*entry:$escaped\s*-->\n?""").find(dayText) ?: return null
        val searchFrom = openMatch.range.last + 1
        if (searchFrom > dayText.length) return null
        val closeMatch = Regex("""<!--\s*/entry:$escaped\s*-->\n?""")
            .find(dayText, searchFrom) ?: return null
        return dayText.substring(openMatch.range.last + 1, closeMatch.range.first)
    }

    /** All fence entries in document order, skipping any id whose closing fence is missing. */
    fun splitEntries(dayText: String): List<FenceEntry> =
        listFencedIds(dayText).mapNotNull { id -> extractBlock(dayText, id)?.let { FenceEntry(id, it) } }
}
