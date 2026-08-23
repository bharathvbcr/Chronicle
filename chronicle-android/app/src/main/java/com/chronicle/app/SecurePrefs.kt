package com.chronicle.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted prefs for secrets and consent flags (off Syncthing vault).
 * Migrates `serve_token` from legacy `chronicle_prefs` once.
 */
object SecurePrefs {
    const val LEGACY_PREFS_NAME = "chronicle_prefs"
    private const val SECURE_PREFS_NAME = "chronicle_secure_prefs"
    private const val KEY_MIGRATED = "secure_prefs_migrated_v1"

    const val KEY_SERVE_TOKEN = "serve_token"
    /** SPKI SHA-256 pin for the paired serve's self-signed TLS cert (v1.11). */
    const val KEY_SERVE_TLS_FP = "serve_tls_fp"
    const val KEY_GROK_API_KEY = "grok_api_key"
    const val KEY_CLOUD_CONSENT = "cloud_consent"
    const val KEY_LLM_PROVIDER = "llm_provider"
    const val KEY_OLLAMA_LAN_URL = "ollama_lan_url"

    @Volatile
    private var cached: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = context.applicationContext
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                app,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            migrateFromLegacyOnce(app, prefs)
            cached = prefs
            return prefs
        }
    }

    /** Test / process-death helper — clears in-memory cache. */
    fun clearCacheForTests() {
        cached = null
    }

    private fun migrateFromLegacyOnce(context: Context, secure: SharedPreferences) {
        if (secure.getBoolean(KEY_MIGRATED, false)) return
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = secure.edit()
        val token = legacy.getString(KEY_SERVE_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (token != null && secure.getString(KEY_SERVE_TOKEN, null).isNullOrBlank()) {
            editor.putString(KEY_SERVE_TOKEN, token)
        }
        editor.putBoolean(KEY_MIGRATED, true)
        editor.apply()
        if (token != null) {
            legacy.edit().remove(KEY_SERVE_TOKEN).apply()
        }
    }
}

/** Android LLM provider choices (asymmetric — no Vertex). */
enum class AndroidLlmProvider(val storageValue: String) {
    NANO("nano"),
    OLLAMA_LAN("ollama_lan"),
    GROK("grok"),
    ;

    companion object {
        fun fromStorage(raw: String?): AndroidLlmProvider =
            entries.firstOrNull { it.storageValue == raw?.trim()?.lowercase() } ?: NANO
    }
}
