package com.chronicle.app.e2ee

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.chronicle.app.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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

    /**
     * Whether E2EE is on, read from persisted prefs (and mirrored into [enabled]).
     *
     * [enabled] starts false in every new process and is only loaded when
     * MainActivity runs, so the share sheet, quick-capture worker and LAN
     * outbox — which can run in a process MainActivity never touched — must
     * ask this instead. Throws if the prefs cannot be read; callers treat
     * that as "do not write".
     */
    fun isEnabled(context: Context): Boolean {
        refreshEnabled(context)
        return _enabled.value
    }

    /** What a capture path may write for a given plaintext. */
    sealed interface CaptureSeal {
        /** E2EE is off: the text is written as-is. */
        data object Clear : CaptureSeal

        /** E2EE is on and unlocked: write `text = ""` plus this `text_enc`. */
        data class Sealed(val blob: JSONObject) : CaptureSeal

        /** E2EE is on but this process holds no key: park it, never write it. */
        data object Locked : CaptureSeal
    }

    /** The one decision every capture path makes before writing [plaintext]. */
    fun sealForCapture(context: Context, plaintext: String): CaptureSeal {
        if (!isEnabled(context)) return CaptureSeal.Clear
        return sealText(plaintext)?.let { CaptureSeal.Sealed(it) } ?: CaptureSeal.Locked
    }

    /**
     * PBKDF2 at [E2eeCrypto.DEFAULT_ITERATIONS] takes seconds on a mid-range
     * phone, so key derivation refuses to run on the main thread (an ANR is
     * the alternative). [enable] and [unlock] dispatch to [Dispatchers.Default].
     */
    internal fun deriveKeyOffMain(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "E2EE key derivation must not run on the main thread"
        }
        return E2eeCrypto.deriveKey(passphrase, salt, iterations)
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
    suspend fun enable(
        context: Context,
        passphrase: String,
        existing: JSONObject? = null,
    ): JSONObject? = withContext(Dispatchers.Default) {
        enableBlocking(context, passphrase, existing)
    }

    private fun enableBlocking(context: Context, passphrase: String, existing: JSONObject?): JSONObject? {
        if (passphrase.isBlank()) return null
        if (passphrase.length > E2eeCrypto.MAX_PASSPHRASE_CHARS) return null
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
            if (!unlockBlocking(context, passphrase)) {
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
        val key = deriveKeyOffMain(passphrase, salt, iter)
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

    /**
     * PC re-setup during unlock.
     *
     * [enableBlocking] may adopt into an empty store and then clear those
     * keys, because nothing was configured yet. Unlock already has a salt:
     * do not write SecurePrefs unless [passphrase] opens the candidate.
     * A miss leaves the previous salt and check blob and tries those instead.
     */
    internal suspend fun unlockAdoptingRemote(
        context: Context,
        passphrase: String,
        kdf: JSONObject,
        check: JSONObject,
    ): Boolean = withContext(Dispatchers.Default) {
        val opened = openCandidate(passphrase, kdf, check)
        if (opened != null) {
            persistAdoptedParams(context, opened.iterations, opened.saltB64, check)
            cachedKey = opened.key
            _unlocked.value = true
            _enabled.value = true
            return@withContext true
        }
        unlockBlocking(context, passphrase)
    }

    /** Adopt PC-side params verbatim (fetched from /auth/e2ee/status). */
    fun adoptRemoteParams(context: Context, kdf: JSONObject, check: JSONObject): Boolean {
        val alg = kdf.optString("alg")
        if (alg != "pbkdf2-sha256") return false
        val iter = iterationsFromKdf(kdf) ?: return false
        val saltB64 = kdf.optString("salt")
        if (saltB64.isEmpty()) return false
        if (check.optString("nonce").isEmpty() || check.optString("ct").isEmpty()) return false
        persistAdoptedParams(context, iter, saltB64, check)
        return true
    }

    private fun persistAdoptedParams(
        context: Context,
        iterations: Int,
        saltB64: String,
        check: JSONObject,
    ) {
        getPrefs(context).edit()
            .putBoolean("e2ee_enabled", true)
            .putString(PREF_KDF_SALT, saltB64)
            .putInt(PREF_KDF_ITER, iterations)
            .putString(PREF_CHECK_JSON, check.toString())
            .apply()
        _enabled.value = true
    }

    /**
     * Derive against the candidate without touching prefs.
     * Over-cap counts and over-long passphrases return null before PBKDF2.
     */
    private fun openCandidate(passphrase: String, kdf: JSONObject, check: JSONObject): OpenedCandidate? {
        if (kdf.optString("alg") != "pbkdf2-sha256") return null
        val iter = iterationsFromKdf(kdf) ?: return null
        val saltB64 = kdf.optString("salt")
        if (saltB64.isEmpty()) return null
        if (check.optString("nonce").isEmpty() || check.optString("ct").isEmpty()) return null
        if (passphrase.isEmpty() || passphrase.length > E2eeCrypto.MAX_PASSPHRASE_CHARS) return null
        val salt = try {
            java.util.Base64.getDecoder().decode(saltB64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val key = try {
            deriveKeyOffMain(passphrase, salt, iter)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!E2eeCrypto.verifyCheckBlob(key, check)) return null
        return OpenedCandidate(key, iter, saltB64)
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
    suspend fun unlock(context: Context, passphrase: String): Boolean =
        withContext(Dispatchers.Default) { unlockBlocking(context, passphrase) }

    private fun unlockBlocking(context: Context, passphrase: String): Boolean {
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
            deriveKeyOffMain(passphrase, salt, iter)
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

private class OpenedCandidate(val key: ByteArray, val iterations: Int, val saltB64: String)

/**
 * Iteration count only when it is a whole number inside the KDF ceiling.
 * [JSONObject.optInt] truncates longs and doubles; a hostile config must not
 * become a count that then runs.
 */
private fun iterationsFromKdf(kdf: JSONObject): Int? {
    if (!kdf.has("iter") || kdf.isNull("iter")) return null
    val iter = when (val raw = kdf.get("iter")) {
        is Int -> raw
        is Long -> if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) return null else raw.toInt()
        is Double -> {
            if (!raw.isFinite()) return null
            val asLong = raw.toLong()
            if (asLong.toDouble() != raw) return null
            if (asLong < Int.MIN_VALUE || asLong > Int.MAX_VALUE) return null
            asLong.toInt()
        }
        else -> return null
    }
    if (iter < E2eeCrypto.MIN_ITERATIONS || iter > E2eeCrypto.MAX_ITERATIONS) return null
    return iter
}
