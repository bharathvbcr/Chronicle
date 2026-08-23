package com.chronicle.app.e2ee

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-compatibility tests against the PC pipeline (chronicle_pipeline/e2ee.py).
 * The hex/b64 vectors below were generated with Python `cryptography` AESGCM
 * and hashlib.pbkdf2_hmac — if these fail, phone and Mac would disagree.
 */
class E2eeCryptoTest {
    private val passphrase = "chronicle-test-passphrase"
    private val salt = ByteArray(16) { it.toByte() } // bytes(range(16)) on PC

    /** Python: derive_key("chronicle-test-passphrase", bytes(range(16)), 100_000) */
    private val expectedKeyB64 = "gVJF9+9vTnE8EeChXQxU7Wedb8TAvZABEJ+2Gdi943Y="

    private fun key(): ByteArray = E2eeCrypto.deriveKey(passphrase, salt, 100_000)

    @Test
    fun deriveKey_matchesPythonVector() {
        val derived = key()
        assertEquals(expectedKeyB64, Base64.getEncoder().encodeToString(derived))
    }

    @Test
    fun opensPcSealedBlob() {
        // PC: AESGCM(key).encrypt(bytes(range(12)), "dreamt of electric sheep")
        val blob = JSONObject()
            .put("v", 1)
            .put("nonce", "AAECAwQFBgcICQoL")
            .put("ct", "2icY4SAZv9CV28KKvmtD563HpJ46lLUQ9ZrEfILBfmEgxw6BQ0TzCg==")
        assertEquals("dreamt of electric sheep", E2eeCrypto.openText(key(), blob))
    }

    @Test
    fun sealedBlob_roundTrips() {
        val blob = E2eeCrypto.sealText(key(), "captured on pixel")
        assertEquals(1, blob.getInt("v"))
        assertEquals("captured on pixel", E2eeCrypto.openText(key(), blob))
    }

    @Test
    fun openText_wrongKeyFailsClosed() {
        val otherSalt = ByteArray(16) { (it + 1).toByte() }
        val wrong = E2eeCrypto.deriveKey(passphrase, otherSalt, 100_000)
        val blob = E2eeCrypto.sealText(key(), "secret")
        assertNull(E2eeCrypto.openText(wrong, blob))
    }

    @Test
    fun openText_malformedBlobReturnsNull() {
        assertNull(E2eeCrypto.openText(key(), JSONObject().put("v", 1)))
        assertNull(E2eeCrypto.openText(key(), JSONObject().put("v", 2).put("nonce", "AAAA").put("ct", "BBBB")))
    }

    @Test
    fun checkBlob_verifiesPassphraseAndRejectsWrongOne() {
        val check = E2eeCrypto.makeCheckBlob(key())
        assertTrue(E2eeCrypto.verifyCheckBlob(key(), check))

        val otherSalt = ByteArray(16) { (it + 3).toByte() }
        val wrong = E2eeCrypto.deriveKey(passphrase, otherSalt, 100_000)
        assertFalse(E2eeCrypto.verifyCheckBlob(wrong, check))
    }

    @Test
    fun sealUsesFreshNonceEachTime() {
        val a = E2eeCrypto.sealText(key(), "same text")
        val b = E2eeCrypto.sealText(key(), "same text")
        assertNotEquals(a.getString("nonce"), b.getString("nonce"))
        assertNotEquals(a.getString("ct"), b.getString("ct"))
    }

    @Test
    fun lowIterationDerivationRefused() {
        var threw = false
        try {
            E2eeCrypto.deriveKey(passphrase, salt, 1000)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun emptyPassphraseRefused() {
        var threw = false
        try {
            E2eeCrypto.deriveKey("", salt, 100_000)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
