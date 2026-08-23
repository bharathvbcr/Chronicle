package com.chronicle.app.e2ee

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Field-level E2EE for entry text — byte-compatible with PC `e2ee.py`
 * (CONTRACT v1.11).
 *
 *  - KDF: PBKDF2-HmacSHA256, 600k iterations default, 32-byte key.
 *  - AEAD: AES-256-GCM, 12-byte nonce, no AAD; ciphertext includes the tag.
 *  - Entry blob: `{"v":1,"nonce":<b64>,"ct":<b64>}` stored as `text_enc` with
 *    `text` set to `""`.
 *  - Check blob: GCM of [CHECK_PLAINTEXT] under the derived key — verifies a
 *    passphrase without storing it. Params live in vault `config.json` (PC) or
 *    SecurePrefs (phone-first setup); the passphrase is never persisted.
 *
 * Uses `java.util.Base64` (minSdk 26) so JVM unit tests run without Android
 * stubs; alphabet matches Python's `base64.b64encode`.
 */
object E2eeCrypto {
    const val CHECK_PLAINTEXT = "chronicle-e2ee-check-v1"
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ITERATIONS = 100_000
    const val KEY_LEN_BITS = 256
    const val NONCE_LEN = 12
    const val SALT_LEN = 16

    private val random = SecureRandom()

    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        require(iterations >= MIN_ITERATIONS) { "kdf iterations below $MIN_ITERATIONS refused" }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_LEN_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(key: ByteArray, plaintext: String, nonce: ByteArray = randomNonce()): ByteArray =
        aesGcm(key, Cipher.ENCRYPT_MODE, nonce).doFinal(plaintext.toByteArray(Charsets.UTF_8))

    fun decrypt(key: ByteArray, nonce: ByteArray, ctAndTag: ByteArray): String =
        String(
            aesGcm(key, Cipher.DECRYPT_MODE, nonce).doFinal(ctAndTag),
            Charsets.UTF_8,
        )

    /** Seal text into the contract blob shape. */
    fun sealText(key: ByteArray, plaintext: String): JSONObject {
        val nonce = randomNonce()
        val ct = encrypt(key, plaintext, nonce)
        return JSONObject()
            .put("v", 1)
            .put("nonce", b64(nonce))
            .put("ct", b64(ct))
    }

    /** Open a contract blob; null when malformed (fail closed at caller). */
    fun openText(key: ByteArray, blob: JSONObject): String? {
        if (blob.optInt("v", -1) != 1) return null
        val nonce = unb64(blob.optString("nonce")) ?: return null
        val ct = unb64(blob.optString("ct")) ?: return null
        return try {
            decrypt(key, nonce, ct)
        } catch (_: Exception) {
            null // wrong key / tampered — never surface partial plaintext
        }
    }

    /** Build the check blob proving a passphrase without storing it. */
    fun makeCheckBlob(key: ByteArray): JSONObject {
        val nonce = randomNonce()
        val ct = encrypt(key, CHECK_PLAINTEXT, nonce)
        return JSONObject().put("nonce", b64(nonce)).put("ct", b64(ct))
    }

    /** True when [passphrase] opens [check] (GCM auth + known plaintext). */
    fun verifyCheckBlob(key: ByteArray, check: JSONObject): Boolean {
        val nonce = unb64(check.optString("nonce")) ?: return false
        val ct = unb64(check.optString("ct")) ?: return false
        return try {
            decrypt(key, nonce, ct) == CHECK_PLAINTEXT
        } catch (_: Exception) {
            false
        }
    }

    fun randomSalt(): ByteArray = ByteArray(SALT_LEN).also { random.nextBytes(it) }

    private fun randomNonce(): ByteArray =
        ByteArray(NONCE_LEN).also { random.nextBytes(it) }

    private fun aesGcm(key: ByteArray, mode: Int, nonce: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher
    }

    fun b64(data: ByteArray): String =
        Base64.getEncoder().encodeToString(data)

    private fun unb64(raw: String): ByteArray? = try {
        if (raw.isEmpty()) null else Base64.getDecoder().decode(raw)
    } catch (_: IllegalArgumentException) {
        null
    }
}
