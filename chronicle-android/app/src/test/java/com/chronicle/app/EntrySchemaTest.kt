package com.chronicle.app

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates entry id generation and JSON writer against contract/entry.schema.json rules.
 */
class EntrySchemaTest {

    @Test
    fun generateEntryId_matchesContractPattern() {
        val fixed = ZonedDateTime.of(2026, 7, 9, 21, 30, 45, 0, ZoneOffset.ofHoursMinutes(5, 30))
        val id = generateEntryId(fixed, device = "an", exists = { false })
        assertEquals("2026-07-09_213045-an", id)
        assertTrue(id.matches(Regex("""^\d{4}-\d{2}-\d{2}_\d{6}-(an|pc)(_[0-9]+)?$""")))
    }

    @Test
    fun generateEntryId_collisionSuffix() {
        val fixed = ZonedDateTime.of(2026, 7, 9, 21, 30, 45, 0, ZoneOffset.UTC)
        val existing = setOf("2026-07-09_213045-an", "2026-07-09_213045-an_2")
        val id = generateEntryId(fixed, exists = { it in existing })
        assertEquals("2026-07-09_213045-an_3", id)
    }

    @Test
    fun serializeEntry_hasRequiredFieldsAndNoLegacy() {
        val entry = Entry(
            id = "2026-07-09_213045-an",
            ts = "2026-07-09T21:30:45+05:30",
            type = "log",
            text = "Hello \"world\"\nline2",
            tags = listOf("work", "#plan"),
            images = listOf("img/2026/07/2026-07-09_213045-an_1.jpg"),
            audio = listOf("audio/2026/07/2026-07-09_213045-an_1.m4a"),
            mood = 4,
            processed = false,
        )
        val json = serializeEntry(entry)
        assertTrue(entryJsonHasNoLegacyFields(json))

        val obj = JSONObject(json)
        assertEquals(1, obj.getInt("version"))
        assertEquals(entry.id, obj.getString("id"))
        assertEquals(entry.ts, obj.getString("ts"))
        assertEquals("log", obj.getString("type"))
        assertEquals(entry.text, obj.getString("text"))
        assertEquals(2, obj.getJSONArray("tags").length())
        assertEquals(1, obj.getJSONArray("images").length())
        assertEquals(1, obj.getJSONArray("audio").length())
        assertEquals(4, obj.getInt("mood"))
        assertFalse(obj.getBoolean("processed"))
        assertFalse(obj.has("city"))
        assertFalse(obj.has("weather"))

        val errors = validateEntryAgainstSchema(entry)
        assertTrue("Expected no schema errors, got $errors", errors.isEmpty())
    }

    @Test
    fun validateEntryAgainstSchema_rejectsBadIdAndPaths() {
        val bad = Entry(
            id = "2026-07-09_2130", // old minute-precision id
            ts = "2026-07-09T21:30:00+00:00",
            type = "log",
            text = "x",
            tags = emptyList(),
            images = listOf("img/bad.jpg"),
            processed = false,
        )
        val errors = validateEntryAgainstSchema(bad)
        assertTrue(errors.any { it.contains("id") })
        assertTrue(errors.any { it.contains("image path") })
    }

    @Test
    fun deserializeRoundTrip_preservesUnknownFiledField() {
        val withFiled = """
            {
              "version": 1,
              "id": "2026-07-09_213045-an",
              "ts": "2026-07-09T21:30:45+05:30",
              "type": "dream",
              "text": "flew over the city",
              "tags": ["dream"],
              "images": [],
              "audio": [],
              "mood": 3,
              "processed": false,
              "filed": true,
              "filed_path": "40-Journal/2026-07-09.md"
            }
        """.trimIndent()
        val entry = deserializeEntry(withFiled)!!
        assertEquals("dream", entry.type)
        assertTrue(entry.filed)
        assertEquals("40-Journal/2026-07-09.md", entry.filedPath)
        val rewritten = serializeEntry(entry)
        val obj = JSONObject(rewritten)
        assertTrue(obj.getBoolean("filed"))
        assertEquals("40-Journal/2026-07-09.md", obj.getString("filed_path"))
        assertTrue(validateEntryAgainstSchema(entry).isEmpty())
    }

    @Test
    fun attachmentsPaths_areValid() {
        val entry = Entry(
            id = "2026-07-09_213045-an",
            ts = "2026-07-09T21:30:45+05:30",
            type = "log",
            text = "hi",
            tags = emptyList(),
            images = listOf("_attachments/2026/07/2026-07-09_213045-an_1.jpg"),
            audio = listOf("_attachments/2026/07/2026-07-09_213045-an_1.m4a"),
        )
        assertTrue(validateEntryAgainstSchema(entry).isEmpty())
    }

    @Test
    fun serializeEntry_freshEntryHasNoLegacyCityWeather() {
        val entry = Entry(
            id = "2026-07-09_213045-an",
            ts = "2026-07-09T21:30:45+05:30",
            type = "log",
            text = "hello",
            tags = emptyList(),
        )
        assertTrue(entryJsonHasNoLegacyFields(serializeEntry(entry)))
    }

    @Test
    fun entryYearMonth_fromId() {
        val (y, m) = entryYearMonth("2026-07-09_213045-an")
        assertEquals("2026", y)
        assertEquals("07", m)
    }

    @Test
    fun shardPath_matchesContractLayout() {
        assertEquals(
            "_capture/entries/2026/07/2026-07-09_213045-an.json",
            shardPath("_capture/entries", "2026", "07", "2026-07-09_213045-an.json"),
        )
        assertEquals(
            "_attachments/2026/07/2026-07-09_213045-an_1.m4a",
            shardPath("_attachments", "2026", "07", "2026-07-09_213045-an_1.m4a"),
        )
    }
}
