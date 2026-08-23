package com.chronicle.app.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServeClientRecallParseTest {
    @Test
    fun parseStringList_readsNodeIds() {
        val arr = JSONArray().put("topic:health").put("entry:2026-07-09_090015-an")
        assertEquals(
            listOf("topic:health", "entry:2026-07-09_090015-an"),
            ServeClient.parseStringList(arr),
        )
    }

    @Test
    fun citationNodeIds_flattensDistinct() {
        val citations = listOf(
            ServeClient.Citation(
                id = "a",
                nodeIds = listOf("topic:health", "entry:1"),
            ),
            ServeClient.Citation(
                id = "b",
                nodeIds = listOf("entry:1", "concept:x"),
            ),
        )
        assertEquals(
            listOf("topic:health", "entry:1", "concept:x"),
            ServeClient.citationNodeIds(citations),
        )
    }

    @Test
    fun recallPayloadShape_includesNodeIdsKey() {
        // Document expected request shape for Phase 1 API.
        val payload = JSONObject()
            .put("message", "health?")
            .put("scope", "all")
            .put("node_ids", JSONArray().put("topic:health"))
        assertTrue(payload.has("node_ids"))
        assertEquals("topic:health", payload.getJSONArray("node_ids").getString(0))
    }
}
