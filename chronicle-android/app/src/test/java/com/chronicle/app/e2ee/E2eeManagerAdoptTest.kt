package com.chronicle.app.e2ee

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.spec.InvalidKeySpecException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Split-key regression (CONTRACT v1.11): when the Mac already wrote an e2ee
 * block into vault config.json, phone-side enable() must ADOPT those params
 * and verify the passphrase against them — never mint a divergent key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class E2eeManagerAdoptTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun reset() {
        // AndroidKeyStore (EncryptedSharedPreferences) is unavailable on the
        // JVM — inject plain prefs via the test seam.
        prefs = context.getSharedPreferences("e2ee_test_prefs", Context.MODE_PRIVATE)
        E2eeManager.prefsProvider = { prefs }
        prefs.edit().clear().commit()
        E2eeManager.disable(context)
    }

    @After
    fun tearDown() {
        E2eeManager.lock()
        E2eeManager.resetPrefsProvider()
        E2eeCrypto.resetPbkdf2ForTest()
    }

    private fun pcBlock(passphrase: String): JSONObject {
        val salt = E2eeCrypto.randomSalt()
        val iter = E2eeCrypto.DEFAULT_ITERATIONS
        val key = E2eeCrypto.deriveKey(passphrase, salt, iter)
        return JSONObject()
            .put("enabled", true)
            .put(
                "kdf",
                JSONObject().put("alg", "pbkdf2-sha256").put("iter", iter).put("salt", E2eeCrypto.b64(salt)),
            )
            .put("check", E2eeCrypto.makeCheckBlob(key))
    }

    @Test
    fun enable_adoptsExistingPcBlock_withMatchingPassphrase() = runBlocking<Unit> {
        val block = pcBlock("mac passphrase")
        val mirror = E2eeManager.enable(context, "mac passphrase", existing = block)

        assertNull(mirror) // params already live in vault config.json — nothing to write back
        assertTrue(E2eeManager.unlocked.value)
        assertTrue(E2eeManager.enabled.value)
        assertEquals(
            block.getJSONObject("kdf").getString("salt"),
            prefs.getString(E2eeManager.PREF_KDF_SALT, null),
        )

        // Entries sealed by the phone open with the PC-derived key (same key).
        val pcKey = E2eeCrypto.deriveKey(
            "mac passphrase",
            java.util.Base64.getDecoder().decode(block.getJSONObject("kdf").getString("salt")),
            block.getJSONObject("kdf").getInt("iter"),
        )
        val blob = E2eeManager.sealText("phone side")
        assertEquals("phone side", E2eeCrypto.openText(pcKey, blob!!))
        assertEquals("mac side", E2eeManager.openText(E2eeCrypto.sealText(pcKey, "mac side")))
    }

    @Test
    fun enable_wrongPassphraseForPcBlock_failsAndRollsBack() = runBlocking<Unit> {
        val before = pcBlock("mac passphrase")

        val result = E2eeManager.enable(context, "wrong passphrase", existing = before)

        assertNull(result)
        assertFalse(E2eeManager.unlocked.value)
        assertFalse(E2eeManager.enabled.value)
        assertFalse(prefs.contains(E2eeManager.PREF_KDF_SALT))
    }

    @Test
    fun enable_withoutExisting_mintsFreshParamsAndMirrorsBlock() = runBlocking<Unit> {
        val mirror = E2eeManager.enable(context, "fresh phone")
        assertNotNull(mirror)

        assertTrue(mirror!!.getBoolean("enabled"))
        assertTrue(mirror.getJSONObject("kdf").getInt("iter") >= E2eeCrypto.MIN_ITERATIONS)
        assertTrue(E2eeManager.unlocked.value)
    }

    @Test
    fun unlock_refreshesFromStaleParams_isRejectedWhenPassphraseDiffers() = runBlocking<Unit> {
        // Phone configured under old params; PC re-setup changed the block.
        E2eeManager.enable(context, "old passphrase")
        val newBlock = pcBlock("new passphrase")
        assertTrue(
            E2eeManager.adoptRemoteParams(
                context,
                newBlock.getJSONObject("kdf"),
                newBlock.getJSONObject("check"),
            ),
        )
        assertFalse(E2eeManager.unlock(context, "old passphrase"))
        assertTrue(E2eeManager.unlock(context, "new passphrase"))
    }

    private fun installedBlock(passphrase: String, saltByte: Byte): JSONObject {
        val salt = ByteArray(16) { saltByte }
        val iter = 100_000
        val key = E2eeCrypto.deriveKey(passphrase, salt, iter)
        val check = E2eeCrypto.makeCheckBlob(key)
        prefs.edit()
            .putBoolean("e2ee_enabled", true)
            .putString(E2eeManager.PREF_KDF_SALT, E2eeCrypto.b64(salt))
            .putInt(E2eeManager.PREF_KDF_ITER, iter)
            .putString(E2eeManager.PREF_CHECK_JSON, check.toString())
            .commit()
        return JSONObject()
            .put("enabled", true)
            .put(
                "kdf",
                JSONObject()
                    .put("alg", "pbkdf2-sha256")
                    .put("iter", iter)
                    .put("salt", E2eeCrypto.b64(salt)),
            )
            .put("check", check)
    }

    @Test
    fun unlock_failedCandidate_keepsPreviousSalt() = runBlocking<Unit> {
        // Phone already has a salt. PC config offers a different one. Today's
        // e2eeUnlock adopts (writes SecurePrefs) and only then unlocks, so a
        // passphrase that opens neither blob still destroys the previous salt.
        val previous = installedBlock("old passphrase", 1)
        val candidate = installedBlock("other passphrase", 2)
        // installedBlock writes prefs; put the phone back on the previous salt
        // so the candidate is only the remote blob, not the stored one.
        prefs.edit()
            .putString(E2eeManager.PREF_KDF_SALT, previous.getJSONObject("kdf").getString("salt"))
            .putInt(E2eeManager.PREF_KDF_ITER, previous.getJSONObject("kdf").getInt("iter"))
            .putString(E2eeManager.PREF_CHECK_JSON, previous.getJSONObject("check").toString())
            .commit()
        val previousSalt = prefs.getString(E2eeManager.PREF_KDF_SALT, null)
        val previousCheck = prefs.getString(E2eeManager.PREF_CHECK_JSON, null)

        val opened = E2eeManager.unlockAdoptingRemote(
            context,
            "neither passphrase",
            candidate.getJSONObject("kdf"),
            candidate.getJSONObject("check"),
        )

        assertFalse(opened)
        assertEquals(previousSalt, prefs.getString(E2eeManager.PREF_KDF_SALT, null))
        assertEquals(previousCheck, prefs.getString(E2eeManager.PREF_CHECK_JSON, null))
    }

    @Test
    fun iterationsAboveCeiling_rejectedBeforePbkdf2() {
        var calls = 0
        E2eeCrypto.pbkdf2 = {
            calls += 1
            throw InvalidKeySpecException("pbkdf2 must not run")
        }
        try {
            var sawHook = false
            try {
                E2eeCrypto.deriveKey("pw", ByteArray(16), 100_000)
            } catch (_: InvalidKeySpecException) {
                sawHook = true
            }
            assertTrue("hook did not intercept PBKDF2", sawHook)
            assertEquals(1, calls)
            calls = 0

            var rejected = false
            try {
                E2eeCrypto.deriveKey("pw", ByteArray(16), 1_000_001)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(0, calls)

            var longRejected = false
            try {
                E2eeCrypto.deriveKey("a".repeat(1025), ByteArray(16), 100_000)
            } catch (_: IllegalArgumentException) {
                longRejected = true
            }
            assertTrue(longRejected)
            assertEquals(0, calls)
        } finally {
            E2eeCrypto.resetPbkdf2ForTest()
        }
    }
}
