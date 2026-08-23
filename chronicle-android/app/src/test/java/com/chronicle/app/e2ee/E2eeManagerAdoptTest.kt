package com.chronicle.app.e2ee

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chronicle.app.SecurePrefs
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
    fun enable_adoptsExistingPcBlock_withMatchingPassphrase() {
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
    fun enable_wrongPassphraseForPcBlock_failsAndRollsBack() {
        val before = pcBlock("mac passphrase")

        val result = E2eeManager.enable(context, "wrong passphrase", existing = before)

        assertNull(result)
        assertFalse(E2eeManager.unlocked.value)
        assertFalse(E2eeManager.enabled.value)
        assertFalse(prefs.contains(E2eeManager.PREF_KDF_SALT))
    }

    @Test
    fun enable_withoutExisting_mintsFreshParamsAndMirrorsBlock() {
        val mirror = E2eeManager.enable(context, "fresh phone")
        assertNotNull(mirror)

        assertTrue(mirror!!.getBoolean("enabled"))
        assertTrue(mirror.getJSONObject("kdf").getInt("iter") >= E2eeCrypto.MIN_ITERATIONS)
        assertTrue(E2eeManager.unlocked.value)
    }

    @Test
    fun unlock_refreshesFromStaleParams_isRejectedWhenPassphraseDiffers() {
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
}
