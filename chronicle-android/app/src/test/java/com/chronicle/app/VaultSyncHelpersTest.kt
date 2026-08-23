package com.chronicle.app

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSyncHelpersTest {

    @Test
    fun lanHealthUi_mapsUrlAndProbe() {
        assertEquals(LanHealthUi.NOT_CONFIGURED, lanHealthUi(hasUrl = false, healthOk = null))
        assertEquals(LanHealthUi.NOT_CONFIGURED, lanHealthUi(hasUrl = false, healthOk = true))
        assertEquals(LanHealthUi.LAN_CONFIGURED, lanHealthUi(hasUrl = true, healthOk = null))
        assertEquals(LanHealthUi.MAC_REACHABLE, lanHealthUi(hasUrl = true, healthOk = true))
        assertEquals(LanHealthUi.MAC_UNREACHABLE, lanHealthUi(hasUrl = true, healthOk = false))
    }

    @Test
    fun lanHealthLabel_neverBareConnected() {
        assertEquals("LAN not configured", lanHealthLabel(LanHealthUi.NOT_CONFIGURED))
        assertEquals("LAN configured", lanHealthLabel(LanHealthUi.LAN_CONFIGURED))
        assertEquals("Mac reachable", lanHealthLabel(LanHealthUi.MAC_REACHABLE))
        assertEquals("Mac unreachable", lanHealthLabel(LanHealthUi.MAC_UNREACHABLE))
        LanHealthUi.entries.forEach { status ->
            assertFalse(lanHealthLabel(status).equals("Connected", ignoreCase = true))
        }
    }

    @Test
    fun shouldRefreshForFingerprint_skipsFirstObservation() {
        val fp = VaultFingerprint(1L, 2, "a.json")
        assertFalse(shouldRefreshForFingerprint(previous = null, current = fp))
        assertFalse(shouldRefreshForFingerprint(previous = fp, current = fp))
        assertTrue(
            shouldRefreshForFingerprint(
                previous = fp,
                current = fp.copy(graphLastModifiedMs = 99L),
            ),
        )
        assertTrue(
            shouldRefreshForFingerprint(
                previous = fp,
                current = fp.copy(recentEntryFileCount = 3),
            ),
        )
        assertTrue(
            shouldRefreshForFingerprint(
                previous = fp,
                current = fp.copy(newestEntryFileName = "b.json"),
            ),
        )
    }

    @Test
    fun formatBrainFreshness_relativeHours() {
        val now = ZonedDateTime.of(2026, 7, 9, 12, 0, 0, 0, ZoneOffset.UTC)
        assertNull(formatBrainFreshness(null, now))
        assertNull(formatBrainFreshness("", now))
        assertEquals(
            "brain updated just now",
            formatBrainFreshness("2026-07-09T11:30:00Z", now),
        )
        assertEquals(
            "brain updated 5h ago",
            formatBrainFreshness("2026-07-09T07:00:00Z", now),
        )
        assertEquals(
            "brain updated 2d ago",
            formatBrainFreshness("2026-07-07T12:00:00Z", now),
        )
        assertEquals("brain updated recently", formatBrainFreshness("not-a-timestamp", now))
    }

    @Test
    fun vaultStatusSubtitle_folderAndBrain() {
        assertEquals("No vault folder selected", vaultStatusSubtitle(false, null))
        assertEquals("No vault folder selected", vaultStatusSubtitle(false, "brain updated 1h ago"))
        assertEquals(
            "Vault folder empty or not synced yet",
            vaultStatusSubtitle(true, null),
        )
        assertEquals(
            "Vault has data — waiting for Mac brain process",
            vaultStatusSubtitle(true, null, entryCount = 2),
        )
        assertEquals(
            "Vault has data — waiting for Mac brain process",
            vaultStatusSubtitle(true, null, noteCount = 3),
        )
        assertEquals("brain updated 1h ago", vaultStatusSubtitle(true, "brain updated 1h ago"))
    }

    @Test
    fun shouldRefreshForFingerprint_detectsKnowledgeAndJournal() {
        val fp = VaultFingerprint(1L, 2, "a.json")
        assertTrue(
            shouldRefreshForFingerprint(
                previous = fp,
                current = fp.copy(knowledgeDirPresent = true),
            ),
        )
        assertTrue(
            shouldRefreshForFingerprint(
                previous = fp,
                current = fp.copy(journalMdCount = 4),
            ),
        )
        assertTrue(
            shouldRefreshForFingerprint(
                previous = fp,
                current = fp.copy(recentEntryMaxModifiedMs = 99L),
            ),
        )
    }
}
