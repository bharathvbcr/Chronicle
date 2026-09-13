package com.chronicle.app.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStateHolderTest {
    @Test
    fun applyServeUrl_storesPrivateHostAndToken() {
        val holder = SettingsStateHolder()
        assertTrue(holder.applyServeUrl("http://192.168.1.20:8765/", token = "abc"))
        assertEquals("http://192.168.1.20:8765", holder.serveBaseUrl.value)
        assertEquals("abc", holder.serveToken.value)
    }

    @Test
    fun applyServeUrl_rejectsPublicHost() {
        val holder = SettingsStateHolder()
        assertFalse(holder.applyServeUrl("http://8.8.8.8:8765", token = "abc"))
        assertEquals("", holder.serveBaseUrl.value)
        assertNull(holder.serveToken.value)
    }

    @Test
    fun applyServeUrl_blankClearsToken() {
        val holder = SettingsStateHolder()
        holder.applyServeUrl("http://10.0.0.1:8765", token = "keep")
        assertTrue(holder.applyServeUrl("", token = ""))
        assertEquals("", holder.serveBaseUrl.value)
        assertNull(holder.serveToken.value)
    }

    /**
     * Previously asserted the OPPOSITE — that a token survives a change of
     * address. That encoded the defect: unauthenticated mDNS discovery applies
     * a new host with no token, so the real Mac's credential was sent to
     * whichever machine answered first. The credential belongs to one identity.
     */
    @Test
    fun applyServeUrl_changingAddressClearsTheToken() {
        val holder = SettingsStateHolder()
        holder.applyServeUrl("http://10.0.0.1:8765", token = "secret")
        assertTrue(holder.applyServeUrl("http://10.0.0.2:8765", token = null))
        assertEquals("http://10.0.0.2:8765", holder.serveBaseUrl.value)
        assertNull(holder.serveToken.value)
    }

    @Test
    fun applyServeUrl_nullTokenKeptForTheSameEndpoint() {
        val holder = SettingsStateHolder()
        holder.applyServeUrl("http://10.0.0.1:8765", token = "secret")
        // Re-applying the same endpoint (e.g. a settings save) is not re-pairing.
        assertTrue(holder.applyServeUrl("http://10.0.0.1:8765", token = null))
        assertEquals("secret", holder.serveToken.value)
    }

    @Test
    fun applyServeUrl_changingAddressAlsoDropsTheOldPin() {
        val holder = SettingsStateHolder()
        holder.applyServeUrl("https://10.0.0.1:8765", token = "secret", tlsFp = "pinA")
        holder.applyServeUrl("https://10.0.0.2:8765", token = null)
        assertNull(holder.serveTlsFp.value)
        assertNull(holder.serveToken.value)
    }

    @Test
    fun applyServeUrl_changingPinAtTheSameAddressClearsTheToken() {
        val holder = SettingsStateHolder()
        holder.applyServeUrl("https://10.0.0.1:8765", token = "secret", tlsFp = "pinA")
        // Same address, different server identity — a re-issued cert is a new
        // peer until the user re-authorises it.
        holder.applyServeUrl("https://10.0.0.1:8765", token = null, tlsFp = "pinB")
        assertEquals("pinB", holder.serveTlsFp.value)
        assertNull(holder.serveToken.value)
    }

    /** Pairing by QR supplies a token, which must be stored for the new host. */
    @Test
    fun applyServeUrl_qrPairingStoresTokenForNewHost() {
        val holder = SettingsStateHolder()
        holder.applyServeUrl("https://10.0.0.1:8765", token = "old", tlsFp = "pinA")
        assertTrue(holder.applyServeUrl("https://10.0.0.2:8765", token = "fresh", tlsFp = "pinB"))
        assertEquals("fresh", holder.serveToken.value)
        assertEquals("pinB", holder.serveTlsFp.value)
    }

    @Test
    fun applyOllamaLanUrl_rejectsPublicHost() {
        val holder = SettingsStateHolder()
        assertFalse(holder.applyOllamaLanUrl("http://1.1.1.1:11434"))
        assertEquals("", holder.ollamaLanUrl.value)
        assertTrue(holder.applyOllamaLanUrl("http://192.168.0.5:11434/"))
        assertEquals("http://192.168.0.5:11434", holder.ollamaLanUrl.value)
    }
}
