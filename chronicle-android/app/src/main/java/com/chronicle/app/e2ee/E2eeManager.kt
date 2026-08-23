package com.chronicle.app.e2ee

import android.content.Context
import android.content.SharedPreferences
import com.chronicle.app.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Session-scoped E2EE state for the phone.
 *
 * KDF params + check blob persist in [SecurePrefs] (never the vault); the
 * derived key lives only in process memory and is dropped on [lock] /
 * process death. Setup is phone-first: when no PC-side e2ee block exists yet,
 * the phone generates fresh params and mirrors them create-only into vault
 * config.json so the PC pipeline can unlock with the same passphrase.
 *
 * Fail-closed everywhere: any malformed blob / wrong passphrase leaves the
 * manager locked and entries render as locked.
 */
object E2eeManager {
    const val PREF_KDF_SALT = "e2ee_kdf_salt"
    const val PREF_KDF_ITER = "e2ee_kdf_iter"
    const val PREF_CHECK_JSON = "e2ee_check_json"

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var cachedKey: ByteArray? = null

    fun refreshEnabled(context: Context) {
        _enabled.value = getPrefs(context).contains(PREF_KDF_SALT) &&
            getPrefs(context).getBoolean("e2ee_enabled", false)
    }

    // -- setup ----------------------------------------------------------------

    /**
     * Enable E2EE (phone-first).
     *
     * When [existing] is non-null (the PC already wrote an `e2ee` block into
     * vault config.json), those params are ADOPTED and the passphrase is
     * verified against the stored check blob — minting fresh params here would
     * fork the key and make every PC-sealed entry unreadable (and vice versa).
     * Returns the config.json `e2ee` block to mirror into the vault
     * (create-only; null when adopting or already configured).
     */
    fun enable(context: Context, passphrase: String, existing: JSONObject? = null): JSONObject? {
        if (passphrase.isBlank()) return null
        val prefs = getPrefs(context)
        if (prefs.contains(PREF_KDF_SALT)) {
            // Already configured — require normal unlock instead.
            return null
        }
        if (existing != null) {
            if (!adoptRemoteParams(
                    context,
                    existing.optJSONObject("kdf") ?: JSONObject(),
                    existing.optJSONObject("check") ?: JSONObject(),
                )
            ) {
                return null
            }
            if (!unlock(context, passphrase)) {
                // Wrong passphrase for the Mac's key — roll back adoption so
                // the user can retry without a half-configured state.
                prefs.edit()
                    .putBoolean("e2ee_enabled", false)
                    .remove(PREF_KDF_SALT)
                    .remove(PREF_KDF_ITER)
                    .remove(PREF_CHECK_JSON)
                    .apply()
                _enabled.value = false
                return null
            }
            return null // params already live in vault config.json — nothing to mirror
        }
        val salt = E2eeCrypto.randomSalt()
        val iter = E2eeCrypto.DEFAULT_ITERATIONS
        val key = E2eeCrypto.deriveKey(passphrase, salt, iter)
        val check = E2eeCrypto.makeCheckBlob(key)

        prefs.edit()
            .putBoolean("e2ee_enabled", true)
            .putString(PREF_KDF_SALT, E2eeCrypto.b64(salt))
            .putInt(PREF_KDF_ITER, iter)
            .putString(PREF_CHECK_JSON, check.toString())
            .apply()

        cachedKey = key
        _unlocked.value = true
        _enabled.value = true

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

    /** Adopt PC-side params verbatim (fetched from /auth/e2ee/status). */
    fun adoptRemoteParams(context: Context, kdf: JSONObject, check: JSONObject): Boolean {
        val alg = kdf.optString("alg")
        if (alg != "pbkdf2-sha256") return false
        val iter = kdf.optInt("iter", -1)
        val saltB64 = kdf.optString("salt")
        if (iter < E2eeCrypto.MIN_ITERATIONS || saltB64.isEmpty()) return false
        if (check.optString("nonce").isEmpty() || check.optString("ct").isEmpty()) return false
        getPrefs(context).edit()
            .putBoolean("e2ee_enabled", true)
            .putString(PREF_KDF_SALT, saltB64)
            .putInt(PREF_KDF_ITER, iter)
            .putString(PREF_CHECK_JSON, check.toString())
            .apply()
        _enabled.value = true
        return true
    }

    fun disable(context: Context) {
        lock()
        getPrefs(context).edit()
            .remove(PREF_KDF_SALT)
            .remove(PREF_KDF_ITER)
            .remove(PREF_CHECK_JSON)
            .putBoolean("e2ee_enabled", false)
            .apply()
        _enabled.value = false
    }

    // -- session lifecycle ----------------------------------------------------

    /** Verify [passphrase] against the stored check blob; cache the key. */
    fun unlock(context: Context, passphrase: String): Boolean {
        val prefs = getPrefs(context)
        val saltB64 = prefs.getString(PREF_KDF_SALT, null) ?: return false
        val iter = prefs.getInt(PREF_KDF_ITER, -1)
        val checkRaw = prefs.getString(PREF_CHECK_JSON, null) ?: return false
        val salt = try {
            java.util.Base64.getDecoder().decode(saltB64)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val key = try {
            E2eeCrypto.deriveKey(passphrase, salt, iter)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val ok = runCatching { JSONObject(checkRaw) }
            .map { E2eeCrypto.verifyCheckBlob(key, it) }
            .getOrDefault(false)
        if (!ok) return false
        cachedKey = key
        _unlocked.value = true
        return true
    }

    fun lock() {
        cachedKey?.fill(0)
        cachedKey = null
        _unlocked.value = false
    }

    // -- entry field sealing --------------------------------------------------

    /** Seal [plaintext]; null when locked (caller must not write plaintext). */
    fun sealText(plaintext: String): JSONObject? =
        cachedKey?.let { E2eeCrypto.sealText(it, plaintext) }

    /** Open a text_enc blob; null when locked/malformed. */
    fun openText(blob: JSONObject?): String? {
        val key = cachedKey ?: return null
        blob ?: return null
        return E2eeCrypto.openText(key, blob)
    }

    fun isConfigured(context: Context): Boolean =
        getPrefs(context).contains(PREF_KDF_SALT)

    /**
     * Test seam: production uses [SecurePrefs] (AndroidKeyStore-backed).
     * Robolectric/JVM tests cannot initialize the keystore, so they inject a
     * plain SharedPreferences provider. Reset via [resetPrefsProvider].
     */
    @Volatile
    internal var prefsProvider: ((Context) -> android.content.SharedPreferences)? = null

    fun resetPrefsProvider() {
        prefsProvider = null
    }

    private fun getPrefs(context: Context): SharedPreferences =
        prefsProvider?.invoke(context) ?: SecurePrefs.get(context)
}
