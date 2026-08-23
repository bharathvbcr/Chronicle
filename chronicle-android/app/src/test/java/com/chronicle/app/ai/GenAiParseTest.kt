package com.chronicle.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenAiParseTest {
    @Test
    fun parseMoodDigit_extractsFirstValid() {
        assertEquals(4, GenAiService.parseMoodDigit("4"))
        assertEquals(2, GenAiService.parseMoodDigit("Mood: 2"))
        assertNull(GenAiService.parseMoodDigit("none"))
        assertNull(GenAiService.parseMoodDigit(""))
    }

    @Test
    fun parseTagJson_handlesFencedArray() {
        val raw = """```json
["health", "sleep", "work"]
```"""
        assertEquals(listOf("health", "sleep", "work"), GenAiService.parseTagJson(raw))
    }
}
