package com.chronicle.app.net

import java.net.InetAddress
import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.assertNotSame
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CONTRACT v1.11: QR v2 (tls_fp), https defaults, CIDR allowlist, pin format. */
class ServeClientV2Test {
    @Test
    fun parseQr_v2_withFingerprint() {
        val raw = """{"v":2,"base":"https://192.168.1.10:8765","token":"tok","tls_fp":"abc=="}"""
        val payload = ServeClient.parseConnectQrPayload(raw)!!
        assertEquals("https://192.168.1.10:8765", payload.baseUrl)
        assertEquals("tok", payload.token)
        assertEquals("abc==", payload.tlsFp)
    }

    @Test
    fun parseQr_v2_fingerprintDroppedForHttpBase() {
        val raw = """{"v":2,"base":"http://192.168.1.10:8765","tls_fp":"abc=="}"""
        val payload = ServeClient.parseConnectQrPayload(raw)!!
        assertNull(payload.tlsFp) // pin without TLS is meaningless
    }

    @Test
    fun normalizeBaseUrl_bareHostDefaultsToHttps() {
        assertEquals("https://192.168.1.10:8765", ServeClient.normalizeBaseUrl(" 192.168.1.10:8765 "))
        // Explicit schemes preserved either way
        assertEquals("http://192.168.1.10:8765", ServeClient.normalizeBaseUrl("http://192.168.1.10:8765"))
        assertEquals("https://mac.local:8765", ServeClient.normalizeBaseUrl("https://mac.local:8765/"))
    }

    @Test
    fun pinFromFingerprint_format() {
        assertEquals("sha256/abc", ServeClient.pinFromFingerprint("sha256/abc"))
        assertEquals("sha256/xyz=", ServeClient.pinFromFingerprint("xyz="))
        var threw = false
        try {
            ServeClient.pinFromFingerprint("   ")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // -- CIDR allowlist -------------------------------------------------------

    private fun addr(ip: String): InetAddress = InetAddress.getByName(ip)

    @Test
    fun matchesCidr_tailscaleRange() {
        val cidr = "100.64.0.0/10"
        assertTrue(ServeClient.matchesCidr(addr("100.64.1.2"), cidr))
        assertTrue(ServeClient.matchesCidr(addr("100.100.0.1"), cidr))
        assertTrue(ServeClient.matchesCidr(addr("100.127.255.255"), cidr))
        assertFalse(ServeClient.matchesCidr(addr("100.128.0.0"), cidr)) // outside /10
        assertFalse(ServeClient.matchesCidr(addr("99.64.0.1"), cidr))
        assertFalse(ServeClient.matchesCidr(addr("8.8.8.8"), cidr))
    }

    @Test
    fun matchesCidr_malformedEntriesNeverMatch() {
        val ip = addr("100.64.1.2")
        assertFalse(ServeClient.matchesCidr(ip, "not-a-cidr"))
        assertFalse(ServeClient.matchesCidr(ip, "100.64.0.0"))
        assertFalse(ServeClient.matchesCidr(ip, "100.64.0.0/33"))
        assertFalse(ServeClient.matchesCidr(ip, "300.1.2.3/8"))
        assertFalse(ServeClient.matchesCidr(ip, ""))
    }

    @Test
    fun isAddressAllowed_privateAlwaysExtraSometimes() {
        // Built-ins still pass with empty extras
        assertTrue(ServeClient.isAddressAllowed(addr("192.168.1.4")))
        assertTrue(ServeClient.isAddressAllowed(addr("127.0.0.1")))
        // CGNAT blocked by default…
        assertFalse(ServeClient.isAddressAllowed(addr("100.64.1.2")))
        // …allowed once user-approved.
        assertTrue(ServeClient.isAddressAllowed(addr("100.64.1.2"), setOf("100.64.0.0/10")))
    }

    @Test
    fun isPrivateOrLoopbackUrl_honorsExtras() {
        val url = "https://100.77.2.1:8765"
        assertFalse(ServeClient.isPrivateOrLoopbackUrl(url))
        assertTrue(
            ServeClient.isPrivateOrLoopbackUrl(url, extraCidrs = setOf("100.64.0.0/10")),
        )
        // Public host stays refused even with extras
        assertFalse(
            ServeClient.isPrivateOrLoopbackUrl("https://8.8.8.8:8765", setOf("100.64.0.0/10")),
        )
    }

    // -- client construction --------------------------------------------------

    @Test
    fun clientFor_pinsHostWhenFingerprintPresent() {
        val pinned = ServeClient.clientFor(
            "https://192.168.1.10:8765",
            tlsFp = "abc=",
            extraCidrs = emptySet(),
        )
        val unpinned = ServeClient.clientFor("https://192.168.1.10:8765", tlsFp = null)

        val pins = pinned.certificatePinner.findMatchingPins("192.168.1.10")
        assertEquals(1, pins.size)
        assertEquals("sha256", pins[0].hashAlgorithm)
        // Hash equals the raw SPKI digest bytes behind "abc="
        val expectedHash = "abc=".decodeBase64()!!
        assertEquals(expectedHash, pins[0].hash)
        assertTrue(unpinned.certificatePinner.findMatchingPins("192.168.1.10").isEmpty())
    }

    @Test
    fun clientFor_cidrsSwapInterceptor() {
        val plain = ServeClient.clientFor("https://192.168.1.10:8765", null)
        val cidrClient = ServeClient.clientFor(
            "https://100.64.9.9:8765",
            null,
            extraCidrs = setOf("100.64.0.0/10"),
        )
        // Strict default client rejects the CGNAT URL via interceptor…
        var threw = false
        try {
            plain.newCall(Request.Builder().url("https://100.64.9.9:8765/health").build()).execute()
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)
        // …while the CIDR-aware client's interceptor allows it through the gate
        // (connection itself may fail in tests — no live server — but no gate throw).
        val hasCidrInterceptor = cidrClient.interceptors.any { it is ServeClient.CidrAllowlistInterceptor }
        assertTrue(hasCidrInterceptor)
    }

    @Test
    fun normalizeBaseUrl_preservesExplicitHttpForNoTlsMacs() {
        // --no-tls Macs: explicit scheme must survive (CIDR checks still apply).
        assertEquals("http://192.168.1.10:8765", ServeClient.normalizeBaseUrl("http://192.168.1.10:8765/"))
        assertEquals("http://chronicle.local:8765", ServeClient.normalizeBaseUrl(" http://chronicle.local:8765 "))
        // Bare host defaults to https (serve's default is pinned TLS).
        assertEquals("https://192.168.1.10:8765", ServeClient.normalizeBaseUrl("192.168.1.10:8765"))
        assertEquals("", ServeClient.normalizeBaseUrl("   "))
    }
    @Test
    fun clientFor_pinnedClient_replacesDefaultTrustWithPinOnlyFactory() {
        // Self-signed Mac cert: default TrustManager rejects it before the
        // pin is consulted. The pinned client must install its own factory.
        val pinned = ServeClient.clientFor("https://192.168.1.10:8765", tlsFp = "abc=")
        val unpinned = ServeClient.clientFor("https://192.168.1.10:8765", tlsFp = null)

        // Pre-fix, both clients used the platform-default factory singleton;
        // pinning must install a distinct (pin-gated trust) factory.
        assertNotSame(pinned.sslSocketFactory, unpinned.sslSocketFactory)
    }

}
