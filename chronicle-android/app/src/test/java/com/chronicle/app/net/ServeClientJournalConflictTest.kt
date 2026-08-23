package com.chronicle.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServeClientJournalConflictTest {
    @Test
    fun parseJournalAmendConflict_nestedDetail() {
        val raw = """
            {"detail":{"detail":"journal fence hash mismatch","on_disk_hash":"${"c".repeat(64)}","filed_content_hash":"${"d".repeat(64)}"}}
        """.trimIndent()
        val parsed = ServeClient.parseJournalAmendConflict(raw)
        assertNotNull(parsed)
        assertEquals("c".repeat(64), parsed!!.onDiskHash)
        assertEquals("d".repeat(64), parsed.filedContentHash)
        assertEquals("journal fence hash mismatch", parsed.detail)
    }

    @Test
    fun parseJournalAmendConflict_flat() {
        val raw = """
            {"detail":"mismatch","on_disk_hash":"${"a".repeat(64)}","filed_content_hash":null}
        """.trimIndent()
        val parsed = ServeClient.parseJournalAmendConflict(raw)
        assertNotNull(parsed)
        assertEquals("a".repeat(64), parsed!!.onDiskHash)
    }

    @Test
    fun parseJournalAmendConflict_garbage() {
        assertNull(ServeClient.parseJournalAmendConflict("""{"error":"nope"}"""))
    }
}
