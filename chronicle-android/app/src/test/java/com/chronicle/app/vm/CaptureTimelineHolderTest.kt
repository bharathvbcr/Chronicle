package com.chronicle.app.vm

import com.chronicle.app.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTimelineHolderTest {
    @Test
    fun captureClearDraft_resetsFields() {
        val capture = CaptureStateHolder()
        capture.textMutable.value = "hello"
        capture.moodMutable.value = 3
        capture.selectedTagsMutable.value = setOf("work")
        capture.pendingAudioPathsMutable.value = listOf("/cache/voice_1.m4a")
        capture.audioDurationsMsMutable.value = mapOf("/cache/voice_1.m4a" to 7_000L)
        capture.editingEntryIdMutable.value = "2026-07-11_120000-an"
        capture.clearDraft()
        assertEquals("", capture.text.value)
        assertNullMood(capture)
        assertTrue(capture.selectedTags.value.isEmpty())
        assertTrue(capture.pendingAudioPaths.value.isEmpty())
        assertTrue(capture.audioDurationsMs.value.isEmpty())
        assertEquals(null, capture.editingEntryId.value)
        assertEquals("log", capture.entryType.value)
    }

    @Test
    fun timelineToggleExpand_andClear() {
        val timeline = TimelineStateHolder()
        val entry = Entry(
            id = "2026-07-11_120000-an",
            ts = "2026-07-11T12:00:00-07:00",
            type = "log",
            text = "hi",
            tags = emptyList(),
            images = emptyList(),
            audio = emptyList(),
            mood = null,
            processed = false,
        )
        timeline.entriesMutable.value = listOf(entry)
        timeline.toggleExpand(entry.id)
        assertEquals(setOf(entry.id), timeline.expandedEntryIds.value)
        timeline.toggleExpand(entry.id)
        assertTrue(timeline.expandedEntryIds.value.isEmpty())
        timeline.clear()
        assertTrue(timeline.entries.value.isEmpty())
    }

    private fun assertNullMood(capture: CaptureStateHolder) {
        assertEquals(null, capture.mood.value)
    }
}
