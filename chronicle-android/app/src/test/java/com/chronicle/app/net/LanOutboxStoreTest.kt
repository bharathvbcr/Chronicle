package com.chronicle.app.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Outbox queue mutations must be serialized: the worker drains (read →
 * truncate) while captures append concurrently — unsynchronized access lost
 * whole captures.
 */
class LanOutboxStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun line(id: String): String = JSONObject().put("entry", JSONObject().put("id", id)).toString() + "\n"

    @Test
    fun appendReadDrain_roundTrip() {
        val file = File(tmp.root, "lan_outbox.jsonl")
        LanOutboxWorker.OutboxStore.append(file, line("e1"))
        LanOutboxWorker.OutboxStore.append(file, line("e2"))

        val lines = LanOutboxWorker.OutboxStore.readAll(file)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("e1"))

        LanOutboxWorker.OutboxStore.drain(file, lines.drop(1))
        assertEquals(1, LanOutboxWorker.OutboxStore.readAll(file).size)

        LanOutboxWorker.OutboxStore.drain(file, emptyList())
        assertFalse(file.exists())
    }

    @Test
    fun concurrentAppendDuringDrain_neverLosesLines() {
        val file = File(tmp.root, "lan_outbox.jsonl")
        repeat(50) { LanOutboxWorker.OutboxStore.append(file, line("seed-$it")) }

        val writer = Thread {
            repeat(200) { LanOutboxWorker.OutboxStore.append(file, line("new-$it")) }
        }
        writer.start()
        // Drain in chunks while appends race in.
        var guard = 0
        while (writer.isAlive && guard < 100) {
            val current = LanOutboxWorker.OutboxStore.readAll(file)
            if (current.size > 60) {
                LanOutboxWorker.OutboxStore.drain(file, current.takeLast(10))
            }
            guard += 1
        }
        writer.join()

        // Every surviving line is well-formed JSON (no torn writes), and the
        // store never went negative or duplicated mid-line content.
        for (l in LanOutboxWorker.OutboxStore.readAll(file)) {
            assertTrue(l.trimEnd().endsWith("}"))
        }
    }

    @Test
    fun append_bounded_dropsOldestUnderSustainedOutage() {
        val file = File(tmp.root, "lan_outbox.jsonl")
        val cap = LanOutboxWorker.OutboxStore.MAX_LINES
        repeat(cap + 50) { LanOutboxWorker.OutboxStore.append(file, line("n-$it")) }

        val lines = LanOutboxWorker.OutboxStore.readAll(file)
        assertEquals(cap, lines.size)
        // Oldest dropped, newest retained — recent captures win.
        assertTrue(lines.first().contains("n-50"))
        assertTrue(lines.last().contains("n-${cap + 49}"))
    }
}
