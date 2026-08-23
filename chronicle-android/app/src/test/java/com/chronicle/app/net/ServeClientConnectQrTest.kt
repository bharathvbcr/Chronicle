package com.chronicle.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServeClientConnectQrTest {
    @Test
    fun parseConnectQr_jsonPayload() {
        val raw = """{"v":1,"base":"http://192.168.1.10:8765/"}"""
        val payload = ServeClient.parseConnectQrPayload(raw)
        assertNotNull(payload)
        assertEquals("http://192.168.1.10:8765", payload!!.baseUrl)
        assertNull(payload.token)
    }

    @Test
    fun parseConnectQr_jsonPayloadWithToken() {
        val raw = """{"v":1,"base":"http://10.0.0.5:8765","token":"pair-secret"}"""
        val payload = ServeClient.parseConnectQrPayload(raw)!!
        assertEquals("http://10.0.0.5:8765", payload.baseUrl)
        assertEquals("pair-secret", payload.token)
    }

    @Test
    fun parseConnectQr_plainHttpUrl() {
        val payload = ServeClient.parseConnectQrPayload("http://192.168.1.10:8765/")
        assertEquals("http://192.168.1.10:8765", payload!!.baseUrl)
    }

    @Test
    fun parseConnectQr_rejectsGarbage() {
        assertNull(ServeClient.parseConnectQrPayload("not-a-url"))
        assertNull(ServeClient.parseConnectQrPayload("""{"v":1}"""))
        assertNull(ServeClient.parseConnectQrPayload(""))
    }

    @Test
    fun parseConnectQr_rejectsPublicIp() {
        assertNull(ServeClient.parseConnectQrPayload("http://8.8.8.8:8765"))
        assertNull(
            ServeClient.parseConnectQrPayload("""{"v":1,"base":"http://1.1.1.1:8765","token":"x"}"""),
        )
    }

    @Test
    fun parseConnectQr_allowsLoopback() {
        val payload = ServeClient.parseConnectQrPayload("http://127.0.0.1:8765")
        assertEquals("http://127.0.0.1:8765", payload!!.baseUrl)
    }

    @Test
    fun isPrivateOrLoopback_rfc1918() {
        assertTrue(ServeClient.isPrivateOrLoopbackUrl("http://192.168.0.1:8765"))
        assertTrue(ServeClient.isPrivateOrLoopbackUrl("http://10.1.2.3:8765"))
        assertTrue(ServeClient.isPrivateOrLoopbackUrl("http://172.16.5.5:8765"))
        assertFalse(ServeClient.isPrivateOrLoopbackUrl("http://8.8.8.8:8765"))
    }

    @Test
    fun isPrivateOrLoopback_loopbackAndLocalhost() {
        assertTrue(ServeClient.isPrivateOrLoopbackUrl("http://127.0.0.1:8765"))
        assertTrue(ServeClient.isPrivateOrLoopbackUrl("http://localhost:8765"))
        assertTrue(ServeClient.isPrivateOrLoopbackUrl("http://[::1]:8765"))
    }

    @Test
    fun requirePrivateOrLoopback_rejectsPublic() {
        try {
            ServeClient.requirePrivateOrLoopbackUrl("https://example.com/recall")
            org.junit.Assert.fail("expected UnsafeServeUrlException")
        } catch (e: ServeClient.UnsafeServeUrlException) {
            assertTrue(e.message!!.contains("non-private"))
        }
    }

    @Test
    fun defaultClient_disablesRedirects() {
        val client = ServeClient.defaultClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun authHeaderName() {
        assertEquals("X-Chronicle-Token", ServeClient.AUTH_HEADER)
    }
}
