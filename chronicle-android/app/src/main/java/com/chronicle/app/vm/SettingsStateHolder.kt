package com.chronicle.app.vm

import com.chronicle.app.AndroidLlmProvider
import com.chronicle.app.net.CloudLlmClient
import com.chronicle.app.net.ServeClient
import com.chronicle.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings, LAN serve pairing, theme, reminders, and LLM provider prefs. */
class SettingsStateHolder {
    private val _biometricEnabled = MutableStateFlow(false)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _reminderEnabled = MutableStateFlow(false)
    val reminderEnabled: StateFlow<Boolean> = _reminderEnabled.asStateFlow()

    private val _reminderHour = MutableStateFlow(21)
    val reminderHour: StateFlow<Int> = _reminderHour.asStateFlow()

    private val _reminderMinute = MutableStateFlow(0)
    val reminderMinute: StateFlow<Int> = _reminderMinute.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(true)
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _serveBaseUrl = MutableStateFlow("")
    val serveBaseUrl: StateFlow<String> = _serveBaseUrl.asStateFlow()

    private val _serveToken = MutableStateFlow<String?>(null)
    val serveToken: StateFlow<String?> = _serveToken.asStateFlow()

    private val _lanHealthOk = MutableStateFlow<Boolean?>(null)
    val lanHealthOk: StateFlow<Boolean?> = _lanHealthOk.asStateFlow()

    private val _llmProvider = MutableStateFlow(AndroidLlmProvider.NANO)
    val llmProvider: StateFlow<AndroidLlmProvider> = _llmProvider.asStateFlow()

    private val _cloudConsent = MutableStateFlow(false)
    val cloudConsent: StateFlow<Boolean> = _cloudConsent.asStateFlow()

    private val _grokApiKey = MutableStateFlow("")
    val grokApiKey: StateFlow<String> = _grokApiKey.asStateFlow()

    private val _ollamaLanUrl = MutableStateFlow("")
    val ollamaLanUrl: StateFlow<String> = _ollamaLanUrl.asStateFlow()

    private val _serveTlsFp = MutableStateFlow<String?>(null)
    val serveTlsFp: StateFlow<String?> = _serveTlsFp.asStateFlow()

    private val _extraCidrs = MutableStateFlow<Set<String>>(emptySet())
    val extraCidrs: StateFlow<Set<String>> = _extraCidrs.asStateFlow()

    /** Pinned/CIDR-aware client rebuilt from current pairing state. */
    fun newServeClient(): ServeClient = ServeClient(
        client = ServeClient.clientFor(_serveBaseUrl.value, _serveTlsFp.value, _extraCidrs.value),
        tokenProvider = { _serveToken.value },
        extraCidrsProvider = { _extraCidrs.value },
    )

    /** Kept for existing call sites; prefer [newServeClient] after pairing changes. */
    val serveClient = ServeClient(tokenProvider = { _serveToken.value })

    internal val serveTlsFpMutable get() = _serveTlsFp
    internal val extraCidrsMutable get() = _extraCidrs
    val cloudLlmClient = CloudLlmClient()

    internal val biometricEnabledMutable get() = _biometricEnabled
    internal val isAuthenticatedMutable get() = _isAuthenticated
    internal val reminderEnabledMutable get() = _reminderEnabled
    internal val reminderHourMutable get() = _reminderHour
    internal val reminderMinuteMutable get() = _reminderMinute
    internal val dynamicColorEnabledMutable get() = _dynamicColorEnabled
    internal val themeModeMutable get() = _themeMode
    internal val serveBaseUrlMutable get() = _serveBaseUrl
    internal val serveTokenMutable get() = _serveToken
    internal val lanHealthOkMutable get() = _lanHealthOk
    internal val llmProviderMutable get() = _llmProvider
    internal val cloudConsentMutable get() = _cloudConsent
    internal val grokApiKeyMutable get() = _grokApiKey
    internal val ollamaLanUrlMutable get() = _ollamaLanUrl

    /**
     * Apply a LAN base URL. Rejects public IP-literal hosts (hostnames are
     * enforced per-request). [token] null keeps existing; blank clears; non-blank stores.
     * [tlsFp] null keeps existing; blank clears; non-blank stores the cert pin.
     */
    fun applyServeUrl(url: String, token: String? = null, tlsFp: String? = null): Boolean {
        val normalized = ServeClient.normalizeBaseUrl(url.trim())
        if (normalized.isNotBlank() && !ServeClient.isAllowedLanUrl(normalized, _extraCidrs.value)) {
            return false
        }
        _serveBaseUrl.value = normalized
        when {
            token == null -> { /* keep */ }
            token.isBlank() -> _serveToken.value = null
            else -> _serveToken.value = token.trim()
        }
        when {
            tlsFp == null -> { /* keep */ }
            tlsFp.isBlank() -> _serveTlsFp.value = null
            else -> _serveTlsFp.value = tlsFp.trim()
        }
        if (normalized.isBlank()) {
            _serveToken.value = null
            _serveTlsFp.value = null
            _lanHealthOk.value = false
        }
        return true
    }

    /** User-approved extra CIDRs ("100.64.0.0/10"); malformed entries rejected. */
    fun applyExtraCidrs(raw: String): Boolean {
        val parsed = raw.split(',', ' ', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (parsed.any { !isValidCidr(it) }) return false
        _extraCidrs.value = parsed.toSet()
        return true
    }

    private fun isValidCidr(cidr: String): Boolean {
        val parts = cidr.split("/")
        if (parts.size != 2) return false
        val prefix = parts[1].toIntOrNull() ?: return false
        if (prefix !in 0..32) return false
        val octets = parts[0].split(".")
        if (octets.size != 4) return false
        return octets.all { o ->
            val v = o.toIntOrNull()
            v != null && v in 0..255
        }
    }

    /** Ollama LAN URL — private/loopback only (hostnames enforced per-request). */
    fun applyOllamaLanUrl(url: String): Boolean {
        val normalized = ServeClient.normalizeBaseUrl(url.trim())
        if (normalized.isNotBlank() && !ServeClient.isAllowedLanUrl(normalized)) {
            return false
        }
        _ollamaLanUrl.value = normalized
        return true
    }
}
