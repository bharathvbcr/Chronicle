package com.chronicle.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E2EE entry round-trips: text_enc blob preservation + seal/open hooks. */
class EntryE2eeTest {
    @After
    fun resetHooks() {
        entryTextOpener = null
        entryTextSealer = null
    }

    private fun blobOf(nonce: String = "QUFB", ct: String = "QkJC"): JSONObject =
        JSONObject().put("v", 1).put("nonce", nonce).put("ct", ct)

    @Test
    fun serialize_encryptedEntryNeverWritesPlaintext() {
        val entry = Entry(
            id = "2026-08-05_090000-an",
            ts = "2026-08-05T09:00:00+05:30",
            type = "log",
            text = "",
            textEnc = blobOf(),
            tags = listOf("dream"),
        )
        val obj = JSONObject(serializeEntry(entry))
        assertEquals("", obj.getString("text"))
        assertEquals(blobOf().toString(), obj.getJSONObject("text_enc").toString())
    }

    @Test
    fun serialize_lockedSessionDropsStrayPlaintextKeepsBlob() {
        // Sealer absent == locked: edits cannot be sealed, so they are dropped.
        val entry = Entry(
            id = "2026-08-05_090000-an",
            ts = "2026-08-05T09:00:00+05:30",
            type = "log",
            text = "LEAK ATTEMPT",
            textEnc = blobOf(),
            tags = emptyList(),
        )
        val obj = JSONObject(serializeEntry(entry))
        assertEquals("", obj.getString("text"))
        assertEquals(blobOf().toString(), obj.getJSONObject("text_enc").toString())
    }

    @Test
    fun serialize_unlockedSessionResealsEdits() {
        entryTextSealer = { plain -> blobOf(nonce = "TkVXTkM=", ct = plain) }
        val entry = Entry(
            id = "2026-08-05_090000-an",
            ts = "2026-08-05T09:00:00+05:30",
            type = "log",
            text = "edited while unlocked",
            textEnc = blobOf(nonce = "T0xE", ct = "T0xE"),
            tags = emptyList(),
        )
        val obj = JSONObject(serializeEntry(entry))
        assertEquals("", obj.getString("text"))
        val resealed = obj.getJSONObject("text_enc")
        assertEquals("edited while unlocked", resealed.getString("ct")) // fake sealer echoes plaintext into ct
    }

    @Test
    fun deserialize_lockedEntryYieldsBlankViaOpener() {
        entryTextOpener = { null } // locked session → opener returns null
        val json = JSONObject()
            .put("version", 1)
            .put("id", "2026-08-05_090000-an")
            .put("ts", "2026-08-05T09:00:00+05:30")
            .put("type", "log")
            .put("text", "")
            .put("text_enc", blobOf())
            .put("tags", JSONArray())
            .toString()
        val entry = deserializeEntry(json)!!
        assertEquals("", entry.text)
        assertNotNull(entry.textEnc)
    }

    @Test
    fun deserialize_unlockedEntryDecryptsTransparently() {
        entryTextOpener = { "opened plaintext" }
        val json = JSONObject()
            .put("version", 1)
            .put("id", "2026-08-05_090000-an")
            .put("ts", "2026-08-05T09:00:00+05:30")
            .put("type", "log")
            .put("text", "")
            .put("text_enc", blobOf())
            .put("tags", JSONArray())
            .toString()
        val entry = deserializeEntry(json)!!
        assertEquals("opened plaintext", entry.text)
    }

    @Test
    fun validate_blankTextRequiresBlobOrAudio() {
        val base = mapOf(
            "id" to "2026-08-05_090000-an",
            "ts" to "2026-08-05T09:00:00+05:30",
            "type" to "log",
        )
        val blank = Entry(
            id = base["id"]!!,
            ts = base["ts"]!!,
            type = "log",
            text = "",
            tags = emptyList(),
        )
        assertTrue(validateEntryAgainstSchema(blank).any { it.contains("blank text") })

        val withAudio = blank.copy(audio = listOf("_attachments/2026/08/x.m4a"))
        assertFalse(validateEntryAgainstSchema(withAudio).any { it.contains("blank text") })

        val withBlob = blank.copy(textEnc = blobOf())
        assertFalse(validateEntryAgainstSchema(withBlob).any { it.contains("blank text") })
    }

    private fun assertNotNull(any: Any?) {
        check(any != null) { "expected non-null" }
    }
}
