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

    @Test
    fun applyServeUrl_nullTokenKeepsExisting() {
        val holder = SettingsStateHolder()
        holder.applyServeUrl("http://10.0.0.1:8765", token = "secret")
        assertTrue(holder.applyServeUrl("http://10.0.0.2:8765", token = null))
        assertEquals("http://10.0.0.2:8765", holder.serveBaseUrl.value)
        assertEquals("secret", holder.serveToken.value)
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
