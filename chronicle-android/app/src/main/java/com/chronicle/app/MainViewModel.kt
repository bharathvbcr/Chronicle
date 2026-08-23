package com.chronicle.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.ai.AiAvailability
import com.chronicle.app.ai.AiFeature
import com.chronicle.app.ai.DigestEntrySnippet
import com.chronicle.app.ai.GenAiService
import com.chronicle.app.ai.NanoModelPreference
import com.chronicle.app.ai.NanoReleaseStage
import com.chronicle.app.ai.RewriteTone
import com.chronicle.app.brain.BrainGraph
import com.chronicle.app.brain.CurationOp
import com.chronicle.app.brain.DayInsight
import com.chronicle.app.brain.Enrichment
import com.chronicle.app.brain.PromptItem
import com.chronicle.app.brain.PromptsFile
import com.chronicle.app.brain.TagsTaxonomy
import com.chronicle.app.health.HealthConnectAvailability
import com.chronicle.app.health.HealthConnectManager
import com.chronicle.app.health.HealthDay
import com.chronicle.app.health.HealthSyncWorker
import com.chronicle.app.net.CloudLlmClient
import com.chronicle.app.net.ServeClient
import com.chronicle.app.reminder.ReminderScheduler
import com.chronicle.app.ui.theme.ThemeMode
import com.chronicle.app.vm.BrainStateHolder
import com.chronicle.app.vm.CaptureStateHolder
import com.chronicle.app.vm.HealthStateHolder
import com.chronicle.app.vm.NotesStateHolder
import com.chronicle.app.vm.SettingsStateHolder
import com.chronicle.app.vm.TimelineStateHolder
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Proofread / rewrite suggestion shown as Apply/Dismiss card (never auto-applied). */
data class TextAiSuggestion(
    val label: String,
    val suggestedText: String,
)

/** Ephemeral day/entry summary sheet (not persisted). */
data class DaySummaryUi(
    val title: String,
    val summary: String? = null,
    val loading: Boolean = false,
    val streaming: Boolean = false,
    val error: String? = null,
)

/** Ephemeral week/month Nano rollup when Mac markdown is absent (not persisted). */
data class PeriodRollupUi(
    val cacheKey: String,
    val title: String,
    val body: String? = null,
    val loading: Boolean = false,
    val streaming: Boolean = false,
    val error: String? = null,
)

enum class Screen {
    FIRST_RUN,
    CAPTURE,
    TIMELINE,
    NOTES,
    BRAIN,
    PORTFOLIO,
    SETTINGS,
}

class MainViewModel : ViewModel() {

    // Domain state holders — MainViewModel remains the nav/vault shell.
    private val capture = CaptureStateHolder()
    private val timeline = TimelineStateHolder()
    private val notesHolder = NotesStateHolder()
    private val brain = BrainStateHolder()
    private val settings = SettingsStateHolder()
    private val health = HealthStateHolder()

    private val _folderUri = MutableStateFlow<String?>(null)
    val folderUri: StateFlow<String?> = _folderUri.asStateFlow()

    private val _currentScreen = MutableStateFlow(Screen.FIRST_RUN)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    /** Guards resume checks until [initFolder] has restored prefs (avoids wiping vault_uri). */
    private var folderInitialized = false

    val text = capture.text
    private val _text get() = capture.textMutable
    val entryType = capture.entryType
    private val _entryType get() = capture.entryTypeMutable
    val mood = capture.mood
    private val _mood get() = capture.moodMutable
    val attachedImages = capture.attachedImages
    private val _attachedImages get() = capture.attachedImagesMutable
    val pendingAudioPaths = capture.pendingAudioPaths
    private val _pendingAudioPaths get() = capture.pendingAudioPathsMutable
    val audioDurationsMs = capture.audioDurationsMs
    private val _audioDurationsMs get() = capture.audioDurationsMsMutable
    val selectedTags = capture.selectedTags
    private val _selectedTags get() = capture.selectedTagsMutable
    val newTagText = capture.newTagText
    private val _newTagText get() = capture.newTagTextMutable
    val isNewTagInputActive = capture.isNewTagInputActive
    private val _isNewTagInputActive get() = capture.isNewTagInputActiveMutable
    val isSaving = capture.isSaving
    private val _isSaving get() = capture.isSavingMutable
    /** Set when launched from the home-screen widget; text-only saves use WorkManager. */
    val quickCaptureSession = capture.quickCaptureSession
    private val _quickCaptureSession get() = capture.quickCaptureSessionMutable
    val showCheckmark = capture.showCheckmark
    private val _showCheckmark get() = capture.showCheckmarkMutable
    val isRecording = capture.isRecording
    private val _isRecording get() = capture.isRecordingMutable
    val editingEntryId = capture.editingEntryId
    private val _editingEntryId get() = capture.editingEntryIdMutable

    val activeTimelineTab = timeline.activeTimelineTab
    private val _activeTimelineTab get() = timeline.activeTimelineTabMutable
    val entries = timeline.entries
    private val _entries get() = timeline.entriesMutable
    val expandedEntryIds = timeline.expandedEntryIds
    private val _expandedEntryIds get() = timeline.expandedEntryIdsMutable
    val recentTags = timeline.recentTags
    private val _recentTags get() = timeline.recentTagsMutable
    val allTags = timeline.allTags
    private val _allTags get() = timeline.allTagsMutable
    val isLoadingTimeline = timeline.isLoadingTimeline
    private val _isLoadingTimeline get() = timeline.isLoadingTimelineMutable

    val biometricEnabled = settings.biometricEnabled
    private val _biometricEnabled get() = settings.biometricEnabledMutable
    val isAuthenticated = settings.isAuthenticated
    private val _isAuthenticated get() = settings.isAuthenticatedMutable
    val todayInsight = brain.todayInsight
    private val _todayInsight get() = brain.todayInsightMutable
    val todayCardDismissed = brain.todayCardDismissed
    private val _todayCardDismissed get() = brain.todayCardDismissedMutable
    val activePrompt = brain.activePrompt
    private val _activePrompt get() = brain.activePromptMutable

    private var promptsList: List<PromptItem> = emptyList()
    private var promptIndex: Int = 0

    val brainFreshness = brain.brainFreshness
    private val _brainFreshness get() = brain.brainFreshnessMutable
    val brainGraph = brain.brainGraph
    private val _brainGraph get() = brain.brainGraphMutable
    val enrichment = brain.enrichment
    private val _enrichment get() = brain.enrichmentMutable
    val tagsTaxonomy = brain.tagsTaxonomy
    private val _tagsTaxonomy get() = brain.tagsTaxonomyMutable
    val relatedCache = brain.relatedCache
    private val _relatedCache get() = brain.relatedCacheMutable

    val reminderEnabled = settings.reminderEnabled
    private val _reminderEnabled get() = settings.reminderEnabledMutable
    val reminderHour = settings.reminderHour
    private val _reminderHour get() = settings.reminderHourMutable
    val reminderMinute = settings.reminderMinute
    private val _reminderMinute get() = settings.reminderMinuteMutable
    val dynamicColorEnabled = settings.dynamicColorEnabled
    private val _dynamicColorEnabled get() = settings.dynamicColorEnabledMutable
    val themeMode = settings.themeMode
    private val _themeMode get() = settings.themeModeMutable

    private val _previousScreen = MutableStateFlow(Screen.CAPTURE)
    val previousScreen: StateFlow<Screen> = _previousScreen.asStateFlow()

    val notes = notesHolder.notes
    private val _notes get() = notesHolder.notesMutable
    val isLoadingNotes = notesHolder.isLoadingNotes
    private val _isLoadingNotes get() = notesHolder.isLoadingNotesMutable
    val selectedNotePath = notesHolder.selectedNotePath
    private val _selectedNotePath get() = notesHolder.selectedNotePathMutable
    val kbNotes = notesHolder.kbNotes
    private val _kbNotes get() = notesHolder.kbNotesMutable
    val isLoadingKbNotes = notesHolder.isLoadingKbNotes
    private val _isLoadingKbNotes get() = notesHolder.isLoadingKbNotesMutable
    val selectedKbNotePath = notesHolder.selectedKbNotePath
    private val _selectedKbNotePath get() = notesHolder.selectedKbNotePathMutable
    val isSavingKbNote = notesHolder.isSavingKbNote
    private val _isSavingKbNote get() = notesHolder.isSavingKbNoteMutable
    val notesSection = notesHolder.notesSection
    private val _notesSection get() = notesHolder.notesSectionMutable

    /** One-shot event (counter) for "new note" requests from the bottom-bar action pill. */
    private val _newNoteRequest = MutableStateFlow(0)
    val newNoteRequest: StateFlow<Int> = _newNoteRequest.asStateFlow()
    val resumePoints = notesHolder.resumePoints
    private val _resumePoints get() = notesHolder.resumePointsMutable
    val isLoadingResumePoints = notesHolder.isLoadingResumePoints
    private val _isLoadingResumePoints get() = notesHolder.isLoadingResumePointsMutable
    val selectedResumePath = notesHolder.selectedResumePath
    private val _selectedResumePath get() = notesHolder.selectedResumePathMutable

    val serveBaseUrl = settings.serveBaseUrl
    private val _serveBaseUrl get() = settings.serveBaseUrlMutable
    val serveToken = settings.serveToken
    private val _serveToken get() = settings.serveTokenMutable
    val serveTlsFp = settings.serveTlsFp
    val extraCidrs = settings.extraCidrs
    private val _serveTlsFp get() = settings.serveTlsFpMutable

    /** Live vault-change notifications from the Mac (SSE); created in [loadSettings]. */
    private var serveEventsClient: com.chronicle.app.net.ServeEventsClient? = null

    private val serveClient get() = settings.newServeClient()
    private val cloudLlmClient get() = settings.cloudLlmClient

    val llmProvider = settings.llmProvider
    private val _llmProvider get() = settings.llmProviderMutable
    val cloudConsent = settings.cloudConsent
    private val _cloudConsent get() = settings.cloudConsentMutable
    val grokApiKey = settings.grokApiKey
    private val _grokApiKey get() = settings.grokApiKeyMutable
    val ollamaLanUrl = settings.ollamaLanUrl
    private val _ollamaLanUrl get() = settings.ollamaLanUrlMutable

    /** null = checking / unknown; true = Mac /health ok; false = unreachable or blank URL. */
    val lanHealthOk = settings.lanHealthOk
    private val _lanHealthOk get() = settings.lanHealthOkMutable

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val healthByDate = health.healthByDate
    private val _healthByDate get() = health.healthByDateMutable
    val healthAutoSync = health.healthAutoSync
    private val _healthAutoSync get() = health.healthAutoSyncMutable
    val healthLastImportMs = health.healthLastImportMs
    private val _healthLastImportMs get() = health.healthLastImportMsMutable
    val healthImporting = health.healthImporting
    private val _healthImporting get() = health.healthImportingMutable
    val healthPermissionsGranted = health.healthPermissionsGranted
    private val _healthPermissionsGranted get() = health.healthPermissionsGrantedMutable
    val healthAvailability = health.healthAvailability
    private val _healthAvailability get() = health.healthAvailabilityMutable

    // --- On-device AI (Gemini Nano / ML Kit GenAI) ---
    private var genAiService: GenAiService? = null
    private var nanoTagJob: Job? = null
    private var imageDescribeJob: Job? = null

    private val _onDeviceAiEnabled = MutableStateFlow(true)
    val onDeviceAiEnabled: StateFlow<Boolean> = _onDeviceAiEnabled.asStateFlow()

    private val _aiFeatureAvailability =
        MutableStateFlow<Map<AiFeature, AiAvailability>>(emptyMap())
    val aiFeatureAvailability: StateFlow<Map<AiFeature, AiAvailability>> =
        _aiFeatureAvailability.asStateFlow()

    private val _anyAiSupported = MutableStateFlow(false)
    val anyAiSupported: StateFlow<Boolean> = _anyAiSupported.asStateFlow()

    private val _nanoTagSuggestions = MutableStateFlow<List<String>>(emptyList())
    val nanoTagSuggestions: StateFlow<List<String>> = _nanoTagSuggestions.asStateFlow()

    /** Suggestion-only mood (1–5) from Gemini Nano; never auto-applied. */
    private val _nanoMoodSuggestion = MutableStateFlow<Int?>(null)
    val nanoMoodSuggestion: StateFlow<Int?> = _nanoMoodSuggestion.asStateFlow()

    /** Auto daily digest card on Timeline (ephemeral, not vault). */
    private val _dailyDigest = MutableStateFlow<DaySummaryUi?>(null)
    val dailyDigest: StateFlow<DaySummaryUi?> = _dailyDigest.asStateFlow()

    /** Week/month on-device rollup when Mac markdown missing (ephemeral). */
    private val _periodRollup = MutableStateFlow<PeriodRollupUi?>(null)
    val periodRollup: StateFlow<PeriodRollupUi?> = _periodRollup.asStateFlow()

    private val _nanoReleaseStage = MutableStateFlow(NanoReleaseStage.STABLE)
    val nanoReleaseStage: StateFlow<NanoReleaseStage> = _nanoReleaseStage.asStateFlow()

    private val _nanoModelPreference = MutableStateFlow(NanoModelPreference.FULL)
    val nanoModelPreference: StateFlow<NanoModelPreference> = _nanoModelPreference.asStateFlow()

    private val _nanoBaseModelName = MutableStateFlow<String?>(null)
    val nanoBaseModelName: StateFlow<String?> = _nanoBaseModelName.asStateFlow()

    private val _nanoUsingStableFullFallback = MutableStateFlow(false)
    val nanoUsingStableFullFallback: StateFlow<Boolean> = _nanoUsingStableFullFallback.asStateFlow()

    private var nanoMoodJob: Job? = null
    private var dailyDigestJob: Job? = null
    private var periodDigestJob: Job? = null
    private var daySummaryJob: Job? = null

    private val _textAiSuggestion = MutableStateFlow<TextAiSuggestion?>(null)
    val textAiSuggestion: StateFlow<TextAiSuggestion?> = _textAiSuggestion.asStateFlow()

    private val _textAiBusy = MutableStateFlow(false)
    val textAiBusy: StateFlow<Boolean> = _textAiBusy.asStateFlow()

    private val _imageDescriptionGhost = MutableStateFlow<String?>(null)
    val imageDescriptionGhost: StateFlow<String?> = _imageDescriptionGhost.asStateFlow()

    private val _daySummary = MutableStateFlow<DaySummaryUi?>(null)
    val daySummary: StateFlow<DaySummaryUi?> = _daySummary.asStateFlow()

    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    var tempCameraUri: Uri? = null
    private var voiceRecorder: VoiceRecorder? = null
    private var vaultWatchJob: Job? = null
    private var lanHealthJob: Job? = null
    private var lanHealthPollTick: Int = 0
    private var lastVaultFingerprint: VaultFingerprint? = null
    private val refreshMutex = Mutex()

    /** Serializes curation-op appends: phone.jsonl uses read-modify-write, so concurrent appends drop ops. */
    private val curationOpsMutex = Mutex()

    fun initFolder(context: Context) {
        val prefs = context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
        val savedUri = prefs.getString("vault_uri", null)
        loadSettings(context)
        if (hasPersistedPermission(context, savedUri)) {
            _folderUri.value = savedUri
            _currentScreen.value = Screen.CAPTURE
            refreshAll(context)
            startVaultWatch(context)
        } else {
            // Keep vault_uri in prefs so the path is remembered across launches;
            // FIRST_RUN only means we need a fresh SAF grant (or first pick).
            _folderUri.value = null
            _currentScreen.value = Screen.FIRST_RUN
            stopVaultWatch()
        }
        folderInitialized = true
    }

    fun setFolderUri(context: Context, uri: Uri) {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            clearSafChildCache()
            context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
                .edit().putString("vault_uri", uri.toString()).apply()
            _folderUri.value = uri.toString()
            _currentScreen.value = Screen.CAPTURE
            folderInitialized = true
            refreshAll(context)
            startVaultWatch(context)
        } catch (e: Exception) {
            e.printStackTrace()
            _userMessages.tryEmit("Could not save folder access. Please choose the folder again.")
        }
    }

    fun checkFolderPermission(context: Context) {
        // Cold start: ON_RESUME often fires before initFolder restores prefs.
        // Checking null _folderUri would wipe the remembered vault_uri.
        if (!folderInitialized) return
        val uri = _folderUri.value
            ?: context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
                .getString("vault_uri", null)
        if (uri != null && hasPersistedPermission(context, uri)) {
            if (_folderUri.value == null) {
                _folderUri.value = uri
                _currentScreen.value = Screen.CAPTURE
                refreshAll(context)
                startVaultWatch(context)
            }
            return
        }
        if (uri == null && _folderUri.value == null && _currentScreen.value == Screen.FIRST_RUN) {
            return
        }
        // Permission lost or never granted — drop in-memory session but keep prefs path.
        releaseVaultSession(context, forgetSavedUri = false)
    }

    /**
     * Clear in-memory vault session. When [forgetSavedUri] is true, also remove the
     * remembered folder (e.g. user explicitly switches away); otherwise keep vault_uri
     * so the next launch / re-pick can recover the same folder.
     */
    private fun releaseVaultSession(context: Context, forgetSavedUri: Boolean) {
        _folderUri.value = null
        _currentScreen.value = Screen.FIRST_RUN
        stopVaultWatch()
        clearSafChildCache()
        if (forgetSavedUri) {
            context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
                .edit()
                .remove("vault_uri")
                .apply()
        }
        HealthSyncWorker.cancel(context)
        timeline.clear()
        brain.clear()
        notesHolder.clear()
        health.clear()
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        _biometricEnabled.value = enabled
        context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("biometric_enabled", enabled).apply()
    }

    fun setAuthenticated(auth: Boolean) {
        _isAuthenticated.value = auth
    }

    /** Surface biometric prompt errors (previously swallowed silently). */
    fun reportBiometricError(message: String) {
        android.util.Log.w("MainViewModel", "Biometric error: $message")
        _userMessages.tryEmit("Biometric error: $message")
    }

    fun setReminderEnabled(context: Context, enabled: Boolean) {
        _reminderEnabled.value = enabled
        context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("reminder_enabled", enabled).apply()
        if (enabled) {
            ReminderScheduler.schedule(context, _reminderHour.value, _reminderMinute.value)
        } else {
            ReminderScheduler.cancel(context)
        }
    }

    fun setReminderTime(context: Context, hour: Int, minute: Int) {
        _reminderHour.value = hour
        _reminderMinute.value = minute
        context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("reminder_hour", hour)
            .putInt("reminder_minute", minute)
            .apply()
        if (_reminderEnabled.value) {
            ReminderScheduler.schedule(context, hour, minute)
        }
    }

    fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
        val secure = SecurePrefs.get(context)
        _biometricEnabled.value = prefs.getBoolean("biometric_enabled", false)
        _reminderEnabled.value = prefs.getBoolean("reminder_enabled", false)
        _reminderHour.value = prefs.getInt("reminder_hour", 21)
        _reminderMinute.value = prefs.getInt("reminder_minute", 0)
        _dynamicColorEnabled.value = prefs.getBoolean("dynamic_color", false)
        _themeMode.value = when (prefs.getString("theme_mode", "system")) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
        _serveBaseUrl.value = prefs.getString("serve_base_url", "").orEmpty()
        _serveToken.value = secure.getString(SecurePrefs.KEY_SERVE_TOKEN, null)
            ?.trim()?.takeIf { it.isNotEmpty() }
        _serveTlsFp.value = secure.getString(SecurePrefs.KEY_SERVE_TLS_FP, null)
            ?.trim()?.takeIf { it.isNotEmpty() }
        settings.applyExtraCidrs(prefs.getString("serve_extra_cidrs", "").orEmpty())
        // Transparent decrypt-on-read for e2ee entries (locked → "").
        entryTextOpener = { blob -> com.chronicle.app.e2ee.E2eeManager.openText(blob) }
        entryTextSealer = { plain -> com.chronicle.app.e2ee.E2eeManager.sealText(plain) }
        com.chronicle.app.e2ee.E2eeManager.refreshEnabled(context)
        if (serveEventsClient == null) {
            serveEventsClient =
                com.chronicle.app.net.ServeEventsClient(context.applicationContext, viewModelScope)
                .also { client ->
                    viewModelScope.launch {
                        client.vaultChanged.collect {
                            if (_folderUri.value != null) refreshAll(context.applicationContext)
                        }
                    }
                }
        }
        _llmProvider.value = AndroidLlmProvider.fromStorage(
            secure.getString(SecurePrefs.KEY_LLM_PROVIDER, null),
        )
        _cloudConsent.value = secure.getBoolean(SecurePrefs.KEY_CLOUD_CONSENT, false)
        _grokApiKey.value = secure.getString(SecurePrefs.KEY_GROK_API_KEY, "").orEmpty()
        _ollamaLanUrl.value = secure.getString(SecurePrefs.KEY_OLLAMA_LAN_URL, "").orEmpty()
        val dismissedDate = prefs.getString("today_dismissed_date", null)
        _todayCardDismissed.value = dismissedDate == LocalDate.now().toString()
        _healthAutoSync.value = prefs.getBoolean(HealthSyncWorker.KEY_AUTO_SYNC, false)
        val lastImport = prefs.getLong(HealthSyncWorker.KEY_LAST_IMPORT_MS, 0L)
        _healthLastImportMs.value = lastImport.takeIf { it > 0L }
        if (_healthAutoSync.value) {
            HealthSyncWorker.enqueue(context)
        }
        refreshHealthConnectStatus(context)
        // Default on where supported; prefs may override after first status refresh.
        val aiPref = prefs.getBoolean(KEY_ON_DEVICE_AI, true)
        _onDeviceAiEnabled.value = aiPref
        _nanoReleaseStage.value = when (prefs.getString(KEY_NANO_RELEASE_STAGE, "stable")) {
            "preview" -> NanoReleaseStage.PREVIEW
            else -> NanoReleaseStage.STABLE
        }
        _nanoModelPreference.value = when (prefs.getString(KEY_NANO_MODEL_PREFERENCE, "full")) {
            "fast" -> NanoModelPreference.FAST
            else -> NanoModelPreference.FULL
        }
        ensureGenAi(context)
        genAiService?.setEnabled(aiPref)
        genAiService?.setModelSelection(_nanoReleaseStage.value, _nanoModelPreference.value)
        refreshAiAvailability(context)
    }

    fun setNanoReleaseStage(context: Context, stage: NanoReleaseStage) {
        _nanoReleaseStage.value = stage
        context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(
                KEY_NANO_RELEASE_STAGE,
                when (stage) {
                    NanoReleaseStage.STABLE -> "stable"
                    NanoReleaseStage.PREVIEW -> "preview"
                },
            )
            .apply()
        applyNanoModelSelection(context)
    }

    fun setNanoModelPreference(context: Context, preference: NanoModelPreference) {
        _nanoModelPreference.value = preference
        context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(
                KEY_NANO_MODEL_PREFERENCE,
                when (preference) {
                    NanoModelPreference.FULL -> "full"
                    NanoModelPreference.FAST -> "fast"
                },
            )
            .apply()
        applyNanoModelSelection(context)
    }

    private fun applyNanoModelSelection(context: Context) {
        ensureGenAi(context)
        genAiService?.setModelSelection(_nanoReleaseStage.value, _nanoModelPreference.value)
        refreshAiAvailability(context)
    }

    fun setLlmProvider(context: Context, provider: AndroidLlmProvider) {
        _llmProvider.value = provider
        SecurePrefs.get(context).edit()
            .putString(SecurePrefs.KEY_LLM_PROVIDER, provider.storageValue)
            .apply()
    }

    fun setCloudConsent(context: Context, consented: Boolean) {
        _cloudConsent.value = consented
        SecurePrefs.get(context).edit()
            .putBoolean(SecurePrefs.KEY_CLOUD_CONSENT, consented)
            .apply()
        if (!consented && _llmProvider.value == AndroidLlmProvider.GROK) {
            setLlmProvider(context, AndroidLlmProvider.NANO)
        }
    }

    fun setGrokApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        _grokApiKey.value = trimmed
        val editor = SecurePrefs.get(context).edit()
        if (trimmed.isEmpty()) {
            editor.remove(SecurePrefs.KEY_GROK_API_KEY)
        } else {
            editor.putString(SecurePrefs.KEY_GROK_API_KEY, trimmed)
        }
        editor.apply()
    }

    fun setOllamaLanUrl(context: Context, url: String): Boolean {
        if (!settings.applyOllamaLanUrl(url)) {
            viewModelScope.launch {
                _userMessages.emit("Ollama URL must be a private or loopback address")
            }
            return false
        }
        SecurePrefs.get(context).edit()
            .putString(SecurePrefs.KEY_OLLAMA_LAN_URL, _ollamaLanUrl.value)
            .apply()
        return true
    }

    fun setOnDeviceAiEnabled(context: Context, enabled: Boolean) {
        _onDeviceAiEnabled.value = enabled
        context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ON_DEVICE_AI, enabled).apply()
        ensureGenAi(context)
        genAiService?.setEnabled(enabled)
        if (!enabled) {
            _nanoTagSuggestions.value = emptyList()
            _nanoMoodSuggestion.value = null
            _textAiSuggestion.value = null
            _imageDescriptionGhost.value = null
            dailyDigestJob?.cancel()
            daySummaryJob?.cancel()
            periodDigestJob?.cancel()
            _dailyDigest.value = null
            _daySummary.value = null
            _periodRollup.value = null
        } else {
            scheduleNanoTagSuggestions()
            scheduleNanoMoodSuggestion()
        }
    }

    fun refreshAiAvailability(context: Context) {
        ensureGenAi(context)
        val service = genAiService ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { service.refreshAllStatuses() }
            publishAiAvailability(service)
            _nanoBaseModelName.value = service.baseModelName.value
            _nanoUsingStableFullFallback.value = service.usingStableFullFallback.value
            if (_onDeviceAiEnabled.value && _text.value.isNotBlank()) {
                scheduleNanoTagSuggestions()
            }
        }
    }

    fun downloadAiFeature(context: Context, feature: AiFeature) {
        ensureGenAi(context)
        val service = genAiService ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { service.downloadFeature(feature) }
            publishAiAvailability(service)
        }
    }

    private fun ensureGenAi(context: Context): GenAiService {
        val existing = genAiService
        if (existing != null) return existing
        return GenAiService(context.applicationContext).also { service ->
            genAiService = service
            service.setEnabled(_onDeviceAiEnabled.value)
            service.setModelSelection(_nanoReleaseStage.value, _nanoModelPreference.value)
            AiFeature.entries.forEach { feature ->
                viewModelScope.launch {
                    service.availability(feature).collect {
                        publishAiAvailability(service)
                    }
                }
            }
            viewModelScope.launch {
                service.baseModelName.collect { _nanoBaseModelName.value = it }
            }
            viewModelScope.launch {
                service.usingStableFullFallback.collect { _nanoUsingStableFullFallback.value = it }
            }
        }
    }

    private fun publishAiAvailability(service: GenAiService) {
        val map = AiFeature.entries.associateWith { feature ->
            service.availability(feature).value
        }
        _aiFeatureAvailability.value = map
        _anyAiSupported.value = map.values.any { avail ->
            avail is AiAvailability.Available ||
                avail is AiAvailability.Downloadable ||
                avail is AiAvailability.Downloading
        }
        _nanoBaseModelName.value = service.baseModelName.value
        _nanoUsingStableFullFallback.value = service.usingStableFullFallback.value
    }

    private fun isAiFeatureReady(feature: AiFeature): Boolean {
        if (!_onDeviceAiEnabled.value) return false
        // Only Available — Downloadable means the model is not on-device yet.
        return _aiFeatureAvailability.value[feature] is AiAvailability.Available
    }

    /**
     * Persist Chronicle serve base URL and optional pairing token + TLS pin.
     * Rejects public (non-LAN) hosts. Pass [token]/[tlsFp] null to leave the
     * stored value unchanged; empty string clears. Token and pin live in
     * EncryptedSharedPreferences.
     */
    fun setServeBaseUrl(context: Context, url: String, token: String? = null, tlsFp: String? = null) {
        val normalized = ServeClient.normalizeBaseUrl(url.trim())
        if (normalized.isNotBlank() &&
            !ServeClient.isAllowedLanUrl(normalized, settings.extraCidrs.value)
        ) {
            viewModelScope.launch {
                _userMessages.emit("LAN URL must be a private or loopback address")
            }
            return
        }
        if (!settings.applyServeUrl(url, token, tlsFp)) {
            viewModelScope.launch {
                _userMessages.emit("LAN URL must be a private or loopback address")
            }
            return
        }
        val prefs = context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE).edit()
        prefs.putString("serve_base_url", normalized)
        prefs.apply()
        val secure = SecurePrefs.get(context).edit()
        when {
            token == null -> { /* keep existing token */ }
            token.isBlank() -> secure.remove(SecurePrefs.KEY_SERVE_TOKEN)
            else -> secure.putString(SecurePrefs.KEY_SERVE_TOKEN, token.trim())
        }
        when {
            tlsFp == null -> { /* keep existing pin */ }
            tlsFp.isBlank() -> secure.remove(SecurePrefs.KEY_SERVE_TLS_FP)
            else -> secure.putString(SecurePrefs.KEY_SERVE_TLS_FP, tlsFp.trim())
        }
        secure.apply()
        if (normalized.isBlank()) {
            _lanHealthOk.value = false
            serveEventsClient?.stop()
        }
    }

    /** Apply a scanned connect QR payload (v2: base+token+tls_fp; v1/base URL tolerated). */
    fun applyConnectQrPayload(context: Context, raw: String): Boolean {
        val payload = ServeClient.parseConnectQrPayload(raw)
        if (payload == null) {
            viewModelScope.launch { _userMessages.emit("Invalid Chronicle QR payload") }
            return false
        }
        setServeBaseUrl(
            context,
            url = payload.baseUrl,
            token = payload.token,
            tlsFp = payload.tlsFp,
        )
        checkLanHealth(context)
        serveEventsClient?.start()
        return true
    }

    /** Store user-approved extra CIDRs (e.g. Tailscale 100.64.0.0/10). */
    fun setExtraCidrs(context: Context, raw: String): Boolean {
        if (!settings.applyExtraCidrs(raw)) {
            viewModelScope.launch { _userMessages.emit("Invalid CIDR — expected e.g. 100.64.0.0/10") }
            return false
        }
        context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE).edit()
            .putString("serve_extra_cidrs", settings.extraCidrs.value.joinToString(","))
            .apply()
        return true
    }

    // -- E2EE (CONTRACT v1.11) ------------------------------------------------

    val e2eeEnabled = com.chronicle.app.e2ee.E2eeManager.enabled
    val e2eeUnlocked = com.chronicle.app.e2ee.E2eeManager.unlocked

    /**
     * Enable E2EE. If the Mac already created an e2ee block in vault
     * config.json, its params are adopted (verified against the passphrase) —
     * minting a fresh key would fork from the PC and seal entries neither
     * side could read. Fresh params are only generated for a truly phone-first
     * setup, then mirrored create-only into vault config.json.
     */
    fun e2eeEnable(context: Context, passphrase: String): Boolean {
        val appContext = context.applicationContext
        val existing: org.json.JSONObject? = try {
            val uriStr = _folderUri.value
            if (uriStr.isNullOrBlank()) null
            else VaultRepository(appContext, Uri.parse(uriStr)).readConfigJsonObject("e2ee")
                ?.takeIf { it.optBoolean("enabled") }
        } catch (_: Exception) {
            null
        }
        val block = com.chronicle.app.e2ee.E2eeManager.enable(appContext, passphrase, existing)
        if (block == null) {
            viewModelScope.launch {
                when {
                    existing != null && !com.chronicle.app.e2ee.E2eeManager.unlocked.value ->
                        _userMessages.emit(
                            "Your Mac already has encryption set up — enter the SAME passphrase you use there",
                        )
                    existing == null ->
                        _userMessages.emit("Encryption already configured on this phone")
                    // Adopted successfully — nothing to mirror.
                    else -> _userMessages.emit("Adopted your Mac's encryption settings")
                }
            }
            return existing != null && com.chronicle.app.e2ee.E2eeManager.unlocked.value
        }
        viewModelScope.launch(Dispatchers.IO) {
            writeE2eeBlockToVaultConfig(appContext, block)
        }
        viewModelScope.launch { _userMessages.emit("Encryption on — use the same passphrase on your Mac") }
        return true
    }

    /** Unlock re-reads vault config.json first so PC-side re-setup self-heals. */
    fun e2eeUnlock(context: Context, passphrase: String): Boolean {
        val appContext = context.applicationContext
        if (!com.chronicle.app.e2ee.E2eeManager.unlocked.value) {
            try {
                val uriStr = _folderUri.value
                if (!uriStr.isNullOrBlank()) {
                    VaultRepository(appContext, Uri.parse(uriStr))
                        .readConfigJsonObject("e2ee")
                        ?.takeIf { it.optBoolean("enabled") }
                        ?.let { remote ->
                            com.chronicle.app.e2ee.E2eeManager.adoptRemoteParams(
                                appContext,
                                remote.optJSONObject("kdf") ?: org.json.JSONObject(),
                                remote.optJSONObject("check") ?: org.json.JSONObject(),
                            )
                        }
                }
            } catch (_: Exception) {
                // Offline / no SAF access → fall back to cached params.
            }
        }
        return com.chronicle.app.e2ee.E2eeManager.unlock(appContext, passphrase)
    }

    fun e2eeLock() = com.chronicle.app.e2ee.E2eeManager.lock()

    /**
     * Create-only mirror of the e2ee block into vault config.json (PC-owned
     * file — never overwrite an existing block; PC-side setup wins).
     */
    private suspend fun writeE2eeBlockToVaultConfig(context: Context, block: JSONObject) {
        try {
            val uriStr = _folderUri.value ?: return
            val repo = VaultRepository(context.applicationContext, Uri.parse(uriStr))
            repo.updateConfigJsonCreateOnly("e2ee", block)
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "could not mirror e2ee block to config.json: $e")
        }
    }

    /** Probe GET /health for the configured Chronicle serve URL. Cancels in-flight probes. */
    fun checkLanHealth(@Suppress("UNUSED_PARAMETER") context: Context) {
        lanHealthJob?.cancel()
        lanHealthJob = viewModelScope.launch {
            val base = _serveBaseUrl.value
            if (base.isBlank()) {
                _lanHealthOk.value = false
                serveEventsClient?.stop()
                return@launch
            }
            _lanHealthOk.value = null
            val ok = withContext(Dispatchers.IO) {
                serveClient.health(ServeClient.normalizeBaseUrl(base)).ok
            }
            // Ignore stale result if URL cleared while checking
            val reachable = _serveBaseUrl.value.isNotBlank() && ok
            _lanHealthOk.value = reachable
            if (reachable) serveEventsClient?.start() else serveEventsClient?.stop()
        }
    }

    fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        _dynamicColorEnabled.value = enabled
        context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dynamic_color", enabled).apply()
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        _themeMode.value = mode
        val value = when (mode) {
            ThemeMode.SYSTEM -> "system"
            ThemeMode.LIGHT -> "light"
            ThemeMode.DARK -> "dark"
        }
        context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", value).apply()
    }

    /** Cycle System → Light → Dark → System (Mac AppShell parity). */
    fun cycleThemeMode(context: Context) {
        val next = when (_themeMode.value) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
        setThemeMode(context, next)
    }

    fun dismissTodayCard(context: Context) {
        _todayCardDismissed.value = true
        context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
            .edit().putString("today_dismissed_date", LocalDate.now().toString()).apply()
    }

    fun navigateTo(screen: Screen) {
        val current = _currentScreen.value
        if ((screen == Screen.SETTINGS || screen == Screen.PORTFOLIO) && current != screen) {
            _previousScreen.value = current
        }
        _currentScreen.value = screen
        if (screen != Screen.CAPTURE) {
            _quickCaptureSession.value = false
        }
        if (screen != Screen.NOTES) {
            _selectedNotePath.value = null
            _selectedKbNotePath.value = null
        }
        if (screen != Screen.PORTFOLIO) {
            _selectedResumePath.value = null
        }
    }

    fun navigateBack(): Boolean {
        return when (_currentScreen.value) {
            Screen.SETTINGS -> {
                val prev = _previousScreen.value
                _currentScreen.value = if (prev == Screen.SETTINGS || prev == Screen.FIRST_RUN) {
                    Screen.CAPTURE
                } else {
                    prev
                }
                true
            }
            Screen.NOTES -> {
                when {
                    _selectedKbNotePath.value != null -> {
                        _selectedKbNotePath.value = null
                        true
                    }
                    _selectedNotePath.value != null -> {
                        _selectedNotePath.value = null
                        true
                    }
                    else -> {
                        _currentScreen.value = Screen.TIMELINE
                        _activeTimelineTab.value = TimelineTab.DAY
                        true
                    }
                }
            }
            Screen.PORTFOLIO -> {
                if (_selectedResumePath.value != null) {
                    _selectedResumePath.value = null
                    true
                } else {
                    val prev = _previousScreen.value
                    _currentScreen.value = if (prev == Screen.PORTFOLIO || prev == Screen.FIRST_RUN) {
                        Screen.TIMELINE
                    } else {
                        prev
                    }
                    _activeTimelineTab.value = TimelineTab.DAY
                    true
                }
            }
            Screen.BRAIN -> {
                _currentScreen.value = Screen.TIMELINE
                _activeTimelineTab.value = TimelineTab.DAY
                true
            }
            Screen.CAPTURE -> {
                if (_folderUri.value != null && !_quickCaptureSession.value) {
                    _currentScreen.value = Screen.TIMELINE
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    fun selectNote(path: String?) {
        _selectedNotePath.value = path
        if (path != null) _selectedKbNotePath.value = null
    }

    fun selectKbNote(path: String?) {
        _selectedKbNotePath.value = path
        if (path != null) _selectedNotePath.value = null
    }

    fun setNotesSection(section: String) {
        if (section == KnowledgePathMap.SECTION_KB ||
            section == KnowledgePathMap.SECTION_NOTES ||
            section == KnowledgePathMap.SECTION_JOURNAL
        ) {
            _notesSection.value = section
        }
    }

    /** Ask the Notes screen to open its create-note dialog (bottom-bar action pill). */
    fun requestNewNote() {
        _newNoteRequest.value += 1
    }

    /**
     * Open a vault path in the correct Notes section (or Timeline for entry citations).
     */
    fun openNote(context: Context, path: String, kind: String? = null) {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return

        if (kind == "entry" || trimmed.matches(Regex("""^\d{4}-\d{2}-\d{2}_"""))) {
            navigateTo(Screen.TIMELINE)
            return
        }

        if (KnowledgePathMap.isJournalFencePath(trimmed) ||
            KnowledgePathMap.isJournalDerivedPath(trimmed) ||
            trimmed.startsWith("40-Journal/") ||
            trimmed.startsWith("_system/derived/") ||
            trimmed.startsWith("notes/") ||
            trimmed == "Upcoming.md"
        ) {
            _selectedKbNotePath.value = null
            _selectedNotePath.value = KnowledgePathMap.norm(trimmed)
            _notesSection.value = KnowledgePathMap.SECTION_JOURNAL
            navigateTo(Screen.NOTES)
            loadNotes(context)
            return
        }

        openKbNote(context, trimmed)
    }

    /** Open Notes → Knowledge/Notes editor for a graph `doc` path (PARA). */
    fun openKbNote(context: Context, path: String) {
        val normalized = normalizeKbNoteVaultPath(path)
            ?: KnowledgePathMap.preferredWriteRel(path, create = false)
            ?: path.trim()
        val section = KnowledgePathMap.sectionFor(normalized) ?: KnowledgePathMap.SECTION_NOTES
        val listed = _kbNotes.value.firstOrNull { note ->
            note.path == normalized ||
                KnowledgePathMap.candidateReadRels(normalized).any { it == note.path }
        }?.path
        _selectedNotePath.value = null
        _selectedKbNotePath.value = listed ?: normalized
        _notesSection.value = section
        navigateTo(Screen.NOTES)
        loadKbNotes(context)
    }

    fun selectResumePoint(path: String?) {
        _selectedResumePath.value = path
    }

    fun loadNotes(context: Context) {
        val uriStr = _folderUri.value ?: return
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            _isLoadingNotes.value = true
            val loaded = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).loadNotes()
            }
            _notes.value = loaded
            _isLoadingNotes.value = false
        }
    }

    fun loadKbNotes(context: Context) {
        val uriStr = _folderUri.value ?: return
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            _isLoadingKbNotes.value = true
            val loaded = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).listKbNotes()
            }
            _kbNotes.value = loaded
            // Resolve dual-read selection to a listed path so the editor opens
            val sel = _selectedKbNotePath.value
            if (sel != null) {
                val match = loaded.firstOrNull { note ->
                    note.path == sel ||
                        KnowledgePathMap.candidateReadRels(sel).any { it == note.path }
                }
                if (match != null) _selectedKbNotePath.value = match.path
            }
            _isLoadingKbNotes.value = false
        }
    }

    fun saveKbNote(context: Context, path: String, text: String, onDone: (Boolean) -> Unit = {}) {
        val uriStr = _folderUri.value ?: run {
            onDone(false)
            return
        }
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            _isSavingKbNote.value = true
            val ok = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).saveKbNote(path, text)
            }
            if (ok) {
                val loaded = withContext(Dispatchers.IO) {
                    VaultRepository(appContext, treeUri).listKbNotes()
                }
                _kbNotes.value = loaded
            }
            _isSavingKbNote.value = false
            onDone(ok)
        }
    }

    fun loadKbNoteBody(context: Context, path: String, onDone: (String?) -> Unit = {}) {
        val uriStr = _folderUri.value ?: run {
            onDone(null)
            return
        }
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            val body = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).loadKbNoteBody(path)
            }
            onDone(body)
        }
    }

    fun loadNoteBody(context: Context, path: String, onDone: (String?) -> Unit = {}) {
        val uriStr = _folderUri.value ?: run {
            onDone(null)
            return
        }
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            val body = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).loadNoteBody(path)
            }
            onDone(body)
        }
    }

    fun listKbTemplates(context: Context, onDone: (List<MarkdownFileMeta>) -> Unit = {}) {
        val uriStr = _folderUri.value ?: run {
            onDone(emptyList())
            return
        }
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            val templates = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).listKbTemplates()
            }
            onDone(templates)
        }
    }

    fun loadTemplateBody(context: Context, path: String, onDone: (String?) -> Unit = {}) {
        val uriStr = _folderUri.value ?: run {
            onDone(null)
            return
        }
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            val body = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).loadTemplateBody(path)
            }
            onDone(body)
        }
    }

    fun moveKbNote(context: Context, fromPath: String, toPath: String, onDone: (Boolean) -> Unit = {}) {
        val uriStr = _folderUri.value ?: run {
            onDone(false)
            return
        }
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).moveKbNote(fromPath, toPath)
            }
            if (ok) {
                val loaded = withContext(Dispatchers.IO) {
                    VaultRepository(appContext, treeUri).listKbNotes()
                }
                _kbNotes.value = loaded
                if (_selectedKbNotePath.value == fromPath) {
                    _selectedKbNotePath.value = toPath
                }
            }
            onDone(ok)
        }
    }

    fun archiveKbNote(context: Context, path: String, onDone: (Boolean) -> Unit = {}) {
        val uriStr = _folderUri.value ?: run {
            onDone(false)
            return
        }
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).archiveKbNote(path)
            }
            if (ok) {
                _selectedKbNotePath.value = null
                val loaded = withContext(Dispatchers.IO) {
                    VaultRepository(appContext, treeUri).listKbNotes()
                }
                _kbNotes.value = loaded
            }
            onDone(ok)
        }
    }

    fun createKbNote(
        context: Context,
        name: String,
        area: String? = null,
        folder: String? = null,
        text: String? = null,
        section: String? = null,
        onDone: (CreateKbNoteResult) -> Unit = {},
    ) {
        val uriStr = _folderUri.value ?: run {
            onDone(CreateKbNoteResult.NoVault)
            return
        }
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        val effectiveSection = section ?: _notesSection.value.takeIf {
            it == KnowledgePathMap.SECTION_KB || it == KnowledgePathMap.SECTION_NOTES
        }
        viewModelScope.launch {
            _isSavingKbNote.value = true
            val result = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).createKbNote(
                    name,
                    text = text.orEmpty(),
                    area = area,
                    folder = folder,
                    section = effectiveSection,
                )
            }
            if (result is CreateKbNoteResult.Success) {
                val loaded = withContext(Dispatchers.IO) {
                    VaultRepository(appContext, treeUri).listKbNotes()
                }
                _kbNotes.value = loaded
                _selectedKbNotePath.value = result.path
                _selectedNotePath.value = null
                KnowledgePathMap.sectionFor(result.path)?.let { _notesSection.value = it }
            }
            _isSavingKbNote.value = false
            onDone(result)
        }
    }

    /**
     * Lightweight cloud/LAN-provider chat over local snippets when Mac serve is unavailable.
     * Full vault RAG still prefers LAN → Mac. Requires cloud consent for Grok.
     */
    suspend fun lightweightProviderRecall(
        context: Context,
        question: String,
        snippets: List<String>,
    ): String? {
        val provider = _llmProvider.value
        val capped = snippets.take(6).map { it.take(CLOUD_SNIPPET_CHARS) }
        val contextBlock = if (capped.isEmpty()) {
            "(no local snippets)"
        } else {
            capped.mapIndexed { i, s -> "[$i] $s" }.joinToString("\n")
        }
        val system = "You are Chronicle on-phone assist. Answer briefly using only the snippets. " +
            "If snippets are insufficient, say so. Do not invent vault facts."
        val user = "Question: $question\n\nSnippets:\n$contextBlock"
        val messages = listOf(
            CloudLlmClient.ChatMessage("system", system),
            CloudLlmClient.ChatMessage("user", user),
        )
        return withContext(Dispatchers.IO) {
            when (provider) {
                AndroidLlmProvider.GROK -> {
                    if (!_cloudConsent.value) return@withContext null
                    val key = _grokApiKey.value
                    if (key.isBlank()) return@withContext null
                    val result = cloudLlmClient.chatGrok(
                        apiKey = key,
                        messages = messages,
                        maxTokens = CLOUD_MAX_TOKENS,
                    )
                    if (result.ok) result.text else null
                }
                AndroidLlmProvider.OLLAMA_LAN -> {
                    val base = _ollamaLanUrl.value
                    if (base.isBlank()) return@withContext null
                    val result = cloudLlmClient.chatOllamaLan(
                        baseUrl = base,
                        messages = messages,
                        maxTokens = CLOUD_MAX_TOKENS,
                    )
                    if (result.ok) result.text else null
                }
                AndroidLlmProvider.NANO -> {
                    if (!_onDeviceAiEnabled.value) return@withContext null
                    ensureGenAi(context)
                    genAiService?.summarizeRecall(
                        answer = capped.joinToString("\n\n"),
                        citationsSnippets = capped.map { it.take(120) },
                    )
                }
            }
        }
    }

    fun deleteKbNote(context: Context, path: String, onDone: (Boolean) -> Unit = {}) {
        val uriStr = _folderUri.value ?: run {
            onDone(false)
            return
        }
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).deleteKbNote(path)
            }
            if (ok) {
                _selectedKbNotePath.value = null
                val loaded = withContext(Dispatchers.IO) {
                    VaultRepository(appContext, treeUri).listKbNotes()
                }
                _kbNotes.value = loaded
            }
            onDone(ok)
        }
    }

    /**
     * Load fence bodies for a journal day file (`40-Journal/YYYY-MM-DD.md`) via Mac serve.
     * Resolves entry ids from [ServeClient.journalDays], then fetches each entry.
     */
    fun loadJournalDayFences(
        dayPath: String,
        onDone: (Result<List<ServeClient.JournalEntryBody>>) -> Unit,
    ) {
        val base = ServeClient.normalizeBaseUrl(_serveBaseUrl.value)
        if (base.isBlank()) {
            onDone(Result.failure(IllegalStateException("Base URL not configured")))
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val date = Regex("""^40-Journal/(\d{4}-\d{2}-\d{2})\.md$""")
                        .matchEntire(KnowledgePathMap.norm(dayPath))
                        ?.groupValues
                        ?.get(1)
                        ?: throw IllegalArgumentException("Not a journal day path: $dayPath")
                    val days = serveClient.journalDays(base)
                    val day = days.find { it.date == date }
                        ?: throw NoSuchElementException("No journal day for $date")
                    day.entryIds.map { id -> serveClient.journalEntry(base, id) }
                }
            }
            onDone(result)
        }
    }

    /** Hash-gated fence amend via Mac serve (`PATCH /journal/entries/{id}`). */
    fun amendJournalFence(
        entryId: String,
        body: String,
        baseHash: String,
        onDone: (Result<ServeClient.JournalAmendResult>) -> Unit,
    ) {
        val base = ServeClient.normalizeBaseUrl(_serveBaseUrl.value)
        if (base.isBlank()) {
            onDone(Result.failure(IllegalStateException("Base URL not configured")))
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    serveClient.journalAmend(base, entryId, body, baseHash)
                }
            }
            onDone(result)
        }
    }

    /** Accept on-disk fence as base after external edit (`POST .../accept-disk`). */
    fun acceptJournalDisk(
        entryId: String,
        onDone: (Result<ServeClient.JournalAmendResult>) -> Unit,
    ) {
        val base = ServeClient.normalizeBaseUrl(_serveBaseUrl.value)
        if (base.isBlank()) {
            onDone(Result.failure(IllegalStateException("Base URL not configured")))
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    serveClient.journalAcceptDisk(base, entryId)
                }
            }
            onDone(result)
        }
    }

    fun loadResumePoints(context: Context) {
        val uriStr = _folderUri.value ?: return
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            _isLoadingResumePoints.value = true
            val loaded = withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).loadResumePoints()
            }
            _resumePoints.value = loaded
            _isLoadingResumePoints.value = false
        }
    }

    fun beginQuickCaptureSession() {
        _quickCaptureSession.value = true
    }

    fun updateText(newText: String) {
        _text.value = newText
        scheduleNanoTagSuggestions()
        scheduleNanoMoodSuggestion()
    }

    fun setEntryType(type: String) {
        _entryType.value = type
    }

    fun setMood(newMood: Int?) {
        _mood.value = newMood
        if (newMood != null) _nanoMoodSuggestion.value = null
    }

    fun acceptNanoMoodSuggestion() {
        val suggested = _nanoMoodSuggestion.value ?: return
        _mood.value = suggested
        _nanoMoodSuggestion.value = null
    }

    fun dismissNanoMoodSuggestion() {
        _nanoMoodSuggestion.value = null
    }

    fun toggleTag(tag: String) {
        val normalized = normalizeTagAlias(tag, _tagsTaxonomy.value)
        if (normalized.isEmpty()) return
        val current = _selectedTags.value
        val existing = current.firstOrNull { it.equals(normalized, ignoreCase = true) }
        _selectedTags.value = if (existing != null) {
            current - existing
        } else {
            current + normalized
        }
    }

    fun addNewTag(tag: String) {
        val normalized = normalizeTagAlias(tag, _tagsTaxonomy.value)
        if (normalized.isNotEmpty()) {
            val current = _selectedTags.value
            if (!tagSetContains(current, normalized)) {
                _selectedTags.value = current + normalized
            }
            if (!tagSetContains(_recentTags.value, normalized)) {
                _recentTags.value = (listOf(normalized) + _recentTags.value).distinct().take(12)
            }
        }
        _newTagText.value = ""
        // Keep the input open for rapid multi-tag entry; the close affordance
        // (or a blank-field backspace) dismisses it explicitly.
    }

    fun updateNewTagText(newText: String) {
        // Comma commits the tag so lists like "work, gym," enter quickly.
        if (newText.endsWith(",")) {
            addNewTag(newText.dropLast(1))
            return
        }
        _newTagText.value = newText
    }

    fun setNewTagInputActive(active: Boolean) {
        _isNewTagInputActive.value = active
        if (!active) _newTagText.value = ""
    }

    /** Autocomplete chips while typing in the + tag field. */
    fun tagAutocompleteSuggestions(query: String, limit: Int = 6): List<String> {
        return matchTagAutocomplete(
            query = query,
            allTags = _allTags.value,
            taxonomy = _tagsTaxonomy.value,
            exclude = _selectedTags.value,
            limit = limit,
        )
    }

    /** Live ghost tags from enrichment + Nano + entry text + today themes. */
    fun liveGhostTagSuggestions(limit: Int = 4): List<String> {
        val selected = _selectedTags.value
        val recent = _recentTags.value
        val exclude = selected + recent
        val textHits = suggestTagsFromText(
            text = _text.value,
            allTags = _allTags.value,
            taxonomy = _tagsTaxonomy.value,
            exclude = exclude,
            limit = limit,
        )
        val themes = _todayInsight.value?.themes.orEmpty()
        val enrichmentTags = _enrichment.value.values.flatMap { it.autoTags }
        return mergeGhostTagSuggestions(
            enrichmentTags = enrichmentTags,
            textSuggestions = textHits,
            themeSuggestions = themes,
            exclude = exclude,
            limit = limit,
            nanoTags = _nanoTagSuggestions.value,
        )
    }

    private fun scheduleNanoTagSuggestions() {
        nanoTagJob?.cancel()
        if (!_onDeviceAiEnabled.value || !isAiFeatureReady(AiFeature.PROMPT)) {
            _nanoTagSuggestions.value = emptyList()
            return
        }
        nanoTagJob = viewModelScope.launch {
            delay(NANO_TAG_DEBOUNCE_MS)
            if (!_onDeviceAiEnabled.value) {
                _nanoTagSuggestions.value = emptyList()
                return@launch
            }
            val service = genAiService ?: return@launch
            val text = _text.value
            val imageHint = _imageDescriptionGhost.value
            if (text.trim().length < 12 && imageHint.isNullOrBlank()) {
                _nanoTagSuggestions.value = emptyList()
                return@launch
            }
            val canonicals = _tagsTaxonomy.value?.tags
                ?.sortedByDescending { it.count }
                ?.map { it.canonical }
                ?.take(48)
                .orEmpty()
                .ifEmpty { _allTags.value.take(48) }
            val tags = withContext(Dispatchers.IO) {
                service.suggestTags(text, canonicals, imageHint)
            }
            if (isActive) _nanoTagSuggestions.value = tags
        }
    }

    private fun scheduleNanoMoodSuggestion() {
        nanoMoodJob?.cancel()
        if (!_onDeviceAiEnabled.value || !isAiFeatureReady(AiFeature.PROMPT)) {
            _nanoMoodSuggestion.value = null
            return
        }
        if (_mood.value != null) {
            _nanoMoodSuggestion.value = null
            return
        }
        nanoMoodJob = viewModelScope.launch {
            delay(NANO_TAG_DEBOUNCE_MS + 200)
            if (!_onDeviceAiEnabled.value || _mood.value != null) {
                _nanoMoodSuggestion.value = null
                return@launch
            }
            val service = genAiService ?: return@launch
            val text = _text.value
            if (text.trim().length < 20) {
                _nanoMoodSuggestion.value = null
                return@launch
            }
            val mood = withContext(Dispatchers.IO) { service.suggestMood(text) }
            if (isActive && _mood.value == null) _nanoMoodSuggestion.value = mood
        }
    }

    /** Chip row: selected pinned front, then frequency-ranked recents. */
    fun displayTagChips(): List<String> {
        return pinSelectedTagsFront(_selectedTags.value, _recentTags.value)
    }

    /** Cycle to the next writing prompt (session shuffle / long-press refresh). */
    fun shufflePrompt() {
        if (promptsList.isEmpty()) {
            _activePrompt.value = null
            return
        }
        promptIndex = (promptIndex + 1) % promptsList.size
        _activePrompt.value = promptsList[promptIndex]
    }

    private fun pickSessionPrompt(prompts: PromptsFile?) {
        val list = prompts?.prompts.orEmpty().filter { it.text.isNotBlank() }
        promptsList = list
        if (list.isEmpty()) {
            promptIndex = 0
            _activePrompt.value = null
            return
        }
        promptIndex = Random.nextInt(list.size)
        _activePrompt.value = list[promptIndex]
    }

    fun attachImages(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        _attachedImages.value = _attachedImages.value + uris
        describeAttachedImage(context, uris.last())
    }

    /** @deprecated Prefer [attachImages] with Context for on-device image description. */
    fun attachImages(uris: List<Uri>) {
        _attachedImages.value = _attachedImages.value + uris
    }

    fun removeAttachedImage(uri: Uri) {
        _attachedImages.value = _attachedImages.value - uri
        if (_attachedImages.value.isEmpty()) {
            _imageDescriptionGhost.value = null
        }
    }

    fun acceptImageDescription() {
        val desc = _imageDescriptionGhost.value?.trim().orEmpty()
        if (desc.isEmpty()) return
        val current = _text.value
        _text.value = if (current.isBlank()) desc else "$current\n\n$desc"
        _imageDescriptionGhost.value = null
        scheduleNanoTagSuggestions()
    }

    fun dismissImageDescription() {
        _imageDescriptionGhost.value = null
    }

    private fun describeAttachedImage(context: Context, uri: Uri) {
        if (!_onDeviceAiEnabled.value || !isAiFeatureReady(AiFeature.IMAGE_DESCRIPTION)) return
        ensureGenAi(context)
        val service = genAiService ?: return
        imageDescribeJob?.cancel()
        imageDescribeJob = viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } catch (_: Exception) {
                    null
                }
            } ?: return@launch
            try {
                val desc = withContext(Dispatchers.IO) { service.describeImage(bitmap) }
                if (!isActive) return@launch
                if (!desc.isNullOrBlank()) {
                    _imageDescriptionGhost.value = desc
                    scheduleNanoTagSuggestions()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun runProofread(context: Context) {
        runTextAi(context, label = "Proofread", feature = AiFeature.PROOFREAD) { service, text ->
            service.proofread(text)
        }
    }

    fun runRewrite(context: Context, tone: RewriteTone) {
        val label = when (tone) {
            RewriteTone.ELABORATE -> "Rewrite · Elaborate"
            RewriteTone.FRIENDLY -> "Rewrite · Friendly"
            RewriteTone.PROFESSIONAL -> "Rewrite · Professional"
            RewriteTone.SHORTEN -> "Rewrite · Shorten"
        }
        runTextAi(context, label = label, feature = AiFeature.REWRITE) { service, text ->
            service.rewrite(text, tone)
        }
    }

    private fun runTextAi(
        context: Context,
        label: String,
        feature: AiFeature,
        block: suspend (GenAiService, String) -> String?,
    ) {
        if (!_onDeviceAiEnabled.value || !isAiFeatureReady(feature)) {
            viewModelScope.launch { _userMessages.emit("On-device AI unavailable") }
            return
        }
        val text = _text.value
        if (text.isBlank()) return
        ensureGenAi(context)
        val service = genAiService ?: return
        viewModelScope.launch {
            _textAiBusy.value = true
            _textAiSuggestion.value = null
            val result = withContext(Dispatchers.IO) { block(service, text) }
            _textAiBusy.value = false
            if (result.isNullOrBlank()) {
                _userMessages.emit("No suggestion from on-device AI")
            } else {
                _textAiSuggestion.value = TextAiSuggestion(label = label, suggestedText = result)
            }
        }
    }

    fun applyTextAiSuggestion() {
        val suggestion = _textAiSuggestion.value ?: return
        _text.value = suggestion.suggestedText
        _textAiSuggestion.value = null
        scheduleNanoTagSuggestions()
    }

    fun dismissTextAiSuggestion() {
        _textAiSuggestion.value = null
    }

    fun canSummarizeDay(day: LocalDate, dayEntries: List<Entry>): Boolean {
        if (!_onDeviceAiEnabled.value) return false
        if (!isAiFeatureReady(AiFeature.SUMMARIZE) && !isAiFeatureReady(AiFeature.PROMPT)) return false
        val combinedLen = dayEntries.sumOf { it.text.trim().length }
        if (combinedLen < 120) return false
        // Prefer Mac insight when present (today is what we keep in memory).
        if (day == LocalDate.now() && !_todayInsight.value?.summary.isNullOrBlank()) return false
        return true
    }

    fun canSummarizeEntry(entry: Entry): Boolean {
        if (!_onDeviceAiEnabled.value) return false
        if (!isAiFeatureReady(AiFeature.SUMMARIZE) && !isAiFeatureReady(AiFeature.PROMPT)) return false
        return entry.text.trim().length >= 120
    }

    fun summarizeDay(context: Context, day: LocalDate, dayEntries: List<Entry>) {
        val combined = dayEntries.joinToString("\n\n") { e ->
            buildString {
                append(e.type.replaceFirstChar { it.uppercase() })
                append(": ")
                append(e.text.trim())
            }
        }
        val title = "Summary · ${formatDayLabel(day)}"
        runSummarize(context, title, combined)
    }

    fun summarizeEntry(context: Context, entry: Entry) {
        val title = "Summary · entry"
        runSummarize(context, title, entry.text)
    }

    fun dismissDaySummary() {
        daySummaryJob?.cancel()
        _daySummary.value = null
    }

    fun dismissDailyDigest() {
        dailyDigestJob?.cancel()
        _dailyDigest.value = null
    }

    fun clearPeriodRollup() {
        periodDigestJob?.cancel()
        _periodRollup.value = null
    }

    /**
     * On-device summary of a recall answer (Brain offline / degraded).
     * Suggestion-only — not persisted.
     */
    suspend fun summarizeRecallOffline(
        context: Context,
        answer: String,
        snippets: List<String> = emptyList(),
    ): String? {
        if (!_onDeviceAiEnabled.value) return null
        ensureGenAi(context)
        val service = genAiService ?: return null
        return withContext(Dispatchers.IO) {
            service.summarizeRecall(answer, snippets)
        }
    }

    /**
     * Auto daily-digest card for Timeline when Mac insight is absent.
     * Suggestion-only; skips if already loading/shown for today or text too short.
     */
    fun maybeRefreshDailyDigest(context: Context, todayEntries: List<Entry>) {
        if (!_onDeviceAiEnabled.value) return
        if (!_todayInsight.value?.summary.isNullOrBlank()) {
            dailyDigestJob?.cancel()
            _dailyDigest.value = null
            return
        }
        if (todayEntries.isEmpty()) {
            dailyDigestJob?.cancel()
            _dailyDigest.value = null
            return
        }
        val combinedLen = todayEntries.sumOf { it.text.trim().length }
        val hasPhotos = todayEntries.any { it.images.isNotEmpty() }
        if (combinedLen < 120 && !hasPhotos) return
        if (_dailyDigest.value?.loading == true || _dailyDigest.value?.streaming == true) return
        if (_dailyDigest.value?.summary != null && _dailyDigest.value?.error == null) return
        if (!isAiFeatureReady(AiFeature.SUMMARIZE) && !isAiFeatureReady(AiFeature.PROMPT)) return

        ensureGenAi(context)
        val service = genAiService ?: return
        dailyDigestJob?.cancel()
        dailyDigestJob = viewModelScope.launch {
            val title = "Today · digest"
            _dailyDigest.value = DaySummaryUi(title = title, loading = true)
            val snippets = withContext(Dispatchers.IO) {
                buildDigestSnippets(context, todayEntries)
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    service.dailyDigest(
                        dayLabel = formatDayLabel(LocalDate.now()),
                        entries = snippets,
                        onPartial = { partial ->
                            if (isActive) {
                                _dailyDigest.value = DaySummaryUi(
                                    title = title,
                                    summary = partial,
                                    loading = false,
                                    streaming = true,
                                )
                            }
                        },
                    )
                }
                if (!isActive) return@launch
                _dailyDigest.value = if (result.isNullOrBlank()) {
                    DaySummaryUi(title = title, loading = false, error = "Could not digest")
                } else {
                    DaySummaryUi(title = title, summary = result, loading = false, streaming = false)
                }
            } finally {
                snippets.forEach { it.imageBitmap?.recycle() }
            }
        }
    }

    /**
     * Week/month Nano rollup when Mac vault markdown is missing.
     * Suggestion-only; cached by [cacheKey] (week start / YearMonth).
     */
    fun maybeRefreshPeriodDigest(
        context: Context,
        cacheKey: String,
        title: String,
        periodLabel: String,
        periodEntries: List<Entry>,
    ) {
        if (!_onDeviceAiEnabled.value) {
            _periodRollup.value = null
            return
        }
        if (!isAiFeatureReady(AiFeature.PROMPT)) {
            _periodRollup.value = null
            return
        }
        val current = _periodRollup.value
        if (current?.cacheKey == cacheKey) {
            if (current.loading || current.streaming) return
            // Already resolved for this period (success or failure) — do not re-fire.
            if (current.body != null || current.error != null) return
        }
        val snippets = periodEntries.map { e ->
            buildString {
                append(e.type)
                e.mood?.let { append(" · mood ").append(it) }
                append(": ")
                append(e.text.trim())
            }
        }
        val sampled = GenAiService.samplePeriodSnippets(snippets)
        if (sampled.length < 120) {
            if (current?.cacheKey == cacheKey) _periodRollup.value = null
            return
        }

        ensureGenAi(context)
        val service = genAiService ?: return
        periodDigestJob?.cancel()
        periodDigestJob = viewModelScope.launch {
            _periodRollup.value = PeriodRollupUi(
                cacheKey = cacheKey,
                title = title,
                loading = true,
            )
            val result = withContext(Dispatchers.IO) {
                service.periodDigest(
                    periodLabel = periodLabel,
                    entrySnippets = snippets,
                    onPartial = { partial ->
                        if (isActive) {
                            _periodRollup.value = PeriodRollupUi(
                                cacheKey = cacheKey,
                                title = title,
                                body = partial,
                                loading = false,
                                streaming = true,
                            )
                        }
                    },
                )
            }
            if (!isActive) return@launch
            _periodRollup.value = if (result.isNullOrBlank()) {
                PeriodRollupUi(
                    cacheKey = cacheKey,
                    title = title,
                    loading = false,
                    error = "Could not summarize period",
                )
            } else {
                PeriodRollupUi(
                    cacheKey = cacheKey,
                    title = title,
                    body = result,
                    loading = false,
                    streaming = false,
                )
            }
        }
    }

    /**
     * Best-effort LAN mirror after a local vault write (creates a Mac `-pc` copy).
     * Does not replace Syncthing; safe to ignore failures.
     */
    fun maybePushEntryOverLan(
        type: String,
        text: String,
        tags: List<String>,
        mood: Int?,
        ts: String?,
    ) {
        val base = ServeClient.normalizeBaseUrl(_serveBaseUrl.value)
        if (base.isBlank() || text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!serveClient.health(base).ok) return@launch
            serveClient.createEntry(
                baseUrl = base,
                type = type,
                text = text,
                tags = tags,
                mood = mood,
                ts = ts,
            )
        }
    }

    private fun runSummarize(context: Context, title: String, text: String) {
        if (!_onDeviceAiEnabled.value) {
            viewModelScope.launch { _userMessages.emit("On-device AI unavailable") }
            return
        }
        if (!isAiFeatureReady(AiFeature.SUMMARIZE) && !isAiFeatureReady(AiFeature.PROMPT)) {
            viewModelScope.launch { _userMessages.emit("On-device AI unavailable") }
            return
        }
        ensureGenAi(context)
        val service = genAiService ?: return
        daySummaryJob?.cancel()
        daySummaryJob = viewModelScope.launch {
            _daySummary.value = DaySummaryUi(title = title, loading = true)
            val result = withContext(Dispatchers.IO) {
                service.summarizeStreaming(text) { partial ->
                    if (isActive) {
                        _daySummary.value = DaySummaryUi(
                            title = title,
                            summary = partial,
                            loading = false,
                            streaming = true,
                        )
                    }
                }
            }
            if (!isActive) return@launch
            _daySummary.value = if (result.isNullOrBlank()) {
                DaySummaryUi(title = title, loading = false, error = "Could not summarize")
            } else {
                DaySummaryUi(title = title, summary = result, loading = false, streaming = false)
            }
        }
    }

    /** Load up to 2 entry bitmaps for digest photo context. */
    private fun buildDigestSnippets(
        context: Context,
        entries: List<Entry>,
    ): List<DigestEntrySnippet> {
        val uriStr = _folderUri.value
        val treeUri = uriStr?.let { Uri.parse(it) }
        val repo = if (treeUri != null) {
            VaultRepository(context.applicationContext, treeUri)
        } else {
            null
        }
        var photosLoaded = 0
        return entries.map { entry ->
            var bitmap: android.graphics.Bitmap? = null
            if (repo != null && photosLoaded < 2 && entry.images.isNotEmpty()) {
                val path = entry.images.first()
                val mediaUri = repo.resolveMediaUri(path)
                if (mediaUri != null) {
                    bitmap = decodeSampledBitmap(context, mediaUri, maxLongEdge = 1024)
                    if (bitmap != null) photosLoaded++
                }
            }
            DigestEntrySnippet(
                type = entry.type,
                text = entry.text,
                mood = entry.mood,
                imageBitmap = bitmap,
            )
        }
    }

    private fun decodeSampledBitmap(
        context: Context,
        uri: Uri,
        maxLongEdge: Int,
    ): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            val longEdge = maxOf(w, h)
            while (longEdge / sample > maxLongEdge) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun removePendingAudio(path: String) {
        _pendingAudioPaths.value = _pendingAudioPaths.value - path
        _audioDurationsMs.value = _audioDurationsMs.value - path
        java.io.File(path).delete()
    }

    fun applyPrompt(prompt: PromptItem) {
        _entryType.value = "reflection"
        _selectedTags.value = _selectedTags.value + "prompt:${prompt.id}"
        if (_text.value.isBlank()) {
            _text.value = prompt.text + "\n\n"
        }
    }

    fun acceptGhostTag(tag: String) {
        toggleTag(tag)
    }

    /** Accept a ghost auto-tag onto an existing unprocessed entry. */
    fun acceptGhostTagOnEntry(context: Context, entry: Entry, tag: String) {
        if (entry.processed) return
        val uriStr = _folderUri.value ?: return
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        val updated = entry.copy(tags = (entry.tags + tag).distinct())
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).updateEntry(updated)
            }
            refreshAll(appContext)
        }
    }

    fun clearCapture() {
        _text.value = ""
        _entryType.value = "log"
        _mood.value = null
        _attachedImages.value = emptyList()
        _pendingAudioPaths.value = emptyList()
        _audioDurationsMs.value = emptyMap()
        _selectedTags.value = emptySet()
        _newTagText.value = ""
        _isNewTagInputActive.value = false
        _editingEntryId.value = null
        _quickCaptureSession.value = false
        _nanoTagSuggestions.value = emptyList()
        _nanoMoodSuggestion.value = null
        _imageDescriptionGhost.value = null
        _textAiSuggestion.value = null
    }

    fun setTimelineTab(tab: TimelineTab) {
        _activeTimelineTab.value = tab
    }

    /**
     * Try paths in order (derived then legacy); soft-fail with null if all missing.
     * Suspend so callers (e.g. Timeline [LaunchedEffect]) cancel prior loads and ignore stale results.
     */
    suspend fun loadFirstNoteBody(context: Context, paths: List<String>): String? {
        val uriStr = _folderUri.value ?: return null
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            val repo = VaultRepository(appContext, treeUri)
            paths.firstNotNullOfOrNull { path -> repo.loadNoteBody(path) }
        }
    }

    fun toggleExpandEntry(entryId: String) {
        val current = _expandedEntryIds.value
        _expandedEntryIds.value =
            if (current.contains(entryId)) current - entryId else current + entryId
        if (!current.contains(entryId)) {
            // Load related for this entry's day when expanding
            // relatedCache already populated from insights
        }
    }

    fun startEditEntry(entry: Entry) {
        if (entry.processed) return
        _editingEntryId.value = entry.id
        _text.value = entry.text
        _entryType.value = entry.type
        _mood.value = entry.mood
        _selectedTags.value = entry.tags.toSet()
        _attachedImages.value = emptyList()
        _pendingAudioPaths.value = emptyList()
        _currentScreen.value = Screen.CAPTURE
    }

    fun createTempCameraUri(context: Context): Uri {
        val tempFile = java.io.File.createTempFile("camera_temp_", ".jpg", context.cacheDir)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile,
        )
        tempCameraUri = uri
        return uri
    }

    fun startVoiceRecording(context: Context): Boolean {
        if (voiceRecorder == null) voiceRecorder = VoiceRecorder(context.applicationContext)
        val file = voiceRecorder?.start()
        _isRecording.value = file != null
        return file != null
    }

    fun stopVoiceRecording() {
        val path = voiceRecorder?.stop()
        _isRecording.value = false
        if (path != null) {
            _pendingAudioPaths.value = _pendingAudioPaths.value + path
            readClipDurationMs(path)?.let { duration ->
                _audioDurationsMs.value = _audioDurationsMs.value + (path to duration)
            }
        }
    }

    /** Live mic amplitude (0..32767) for the recording level meter; 0 when idle. */
    fun currentRecordingAmplitude(): Int =
        if (_isRecording.value) voiceRecorder?.pollMaxAmplitude() ?: 0 else 0

    private fun readClipDurationMs(path: String): Long? = try {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
        } finally {
            retriever.release()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    fun cancelVoiceRecording() {
        voiceRecorder?.cancel()
        _isRecording.value = false
    }

    fun refreshAll(context: Context) {
        val uriStr = _folderUri.value ?: return
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            refreshMutex.withLock {
                _isRefreshing.value = true
                _isLoadingTimeline.value = true
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        VaultRepository(appContext, treeUri).loadEntries()
                    }
                    _entries.value = loaded
                    _recentTags.value = rankTagsByFrequency(loaded, _tagsTaxonomy.value)
                    _allTags.value = loaded.flatMap { it.tags }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
                    loadBrainInternal(appContext, treeUri)
                    loadHealthInternal(appContext, treeUri, loaded)
                    // Keep Notes / KB in sync when Syncthing fills PARA (not just Timeline/Brain).
                    val notesPair = withContext(Dispatchers.IO) {
                        val repo = VaultRepository(appContext, treeUri)
                        repo.loadNotes() to repo.listKbNotes()
                    }
                    _notes.value = notesPair.first
                    _kbNotes.value = notesPair.second
                    lastVaultFingerprint = withContext(Dispatchers.IO) {
                        VaultRepository(appContext, treeUri).vaultFingerprint()
                    }
                } finally {
                    _isLoadingTimeline.value = false
                    _isRefreshing.value = false
                }
            }
        }
    }

    fun loadTimeline(context: Context) {
        refreshAll(context)
    }

    fun loadBrain(context: Context) {
        val uriStr = _folderUri.value ?: return
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            loadBrainInternal(appContext, treeUri)
        }
    }

    private suspend fun loadBrainInternal(appContext: Context, treeUri: Uri) {
        withContext(Dispatchers.IO) {
            val repo = VaultRepository(appContext, treeUri)
            val graph = repo.loadGraphWithOverlay()
            val insight = repo.loadTodayInsight()
            val prompts = repo.loadPrompts()
            val taxonomy = repo.loadTagsTaxonomy()
            val ym = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
            val enrich = repo.loadEnrichmentForMonth(ym).toMutableMap()
            // Also load previous month for recent unprocessed
            val prevYm = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"))
            enrich.putAll(repo.loadEnrichmentForMonth(prevYm))

            val related = mutableMapOf<String, List<String>>()
            insight?.relatedEntries?.let { related.putAll(it) }
            // Pull related from a few recent insight days
            for (i in 0..6) {
                val d = LocalDate.now().minusDays(i.toLong())
                repo.loadTodayInsight(d)?.relatedEntries?.let { related.putAll(it) }
            }

            Triple(
                Triple(graph, insight, prompts),
                Pair(taxonomy, enrich.toMap()),
                related.toMap(),
            )
        }.let { (a, b, related) ->
            val (graph, insight, prompts) = a
            val (taxonomy, enrich) = b
            _brainGraph.value = graph
            _todayInsight.value = insight
            pickSessionPrompt(prompts)
            _tagsTaxonomy.value = taxonomy
            _enrichment.value = enrich
            _relatedCache.value = related
            _brainFreshness.value = formatBrainFreshness(graph?.generated)
            // Re-rank recents with taxonomy counts once taxonomy is available
            if (_entries.value.isNotEmpty() || taxonomy != null) {
                _recentTags.value = rankTagsByFrequency(_entries.value, taxonomy)
            }
        }
    }

    /** Foreground poll (~20s): refreshAll only when vault fingerprint changes; recheck LAN every N ticks. */
    fun startVaultWatch(context: Context) {
        stopVaultWatch()
        val appContext = context.applicationContext
        lanHealthPollTick = 0
        vaultWatchJob = viewModelScope.launch {
            while (isActive) {
                delay(VAULT_WATCH_INTERVAL_MS)
                val uriStr = _folderUri.value
                if (uriStr != null && !refreshMutex.isLocked) {
                    val treeUri = Uri.parse(uriStr)
                    val fingerprint = withContext(Dispatchers.IO) {
                        try {
                            VaultRepository(appContext, treeUri).vaultFingerprint()
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (fingerprint != null) {
                        if (shouldRefreshForFingerprint(lastVaultFingerprint, fingerprint)) {
                            refreshAll(appContext)
                        } else if (lastVaultFingerprint == null) {
                            lastVaultFingerprint = fingerprint
                        }
                    }
                }
                lanHealthPollTick += 1
                if (_serveBaseUrl.value.isNotBlank() &&
                    lanHealthPollTick % LAN_HEALTH_POLL_EVERY_N_TICKS == 0
                ) {
                    checkLanHealth(appContext)
                }
            }
        }
    }

    fun stopVaultWatch() {
        vaultWatchJob?.cancel()
        vaultWatchJob = null
    }

    /**
     * Seal capture text when E2EE is on and the session is unlocked.
     * Returns null (plaintext path) when e2ee is off — never silently drops
     * text: locked sessions save plaintext to SAF and log a warning, matching
     * the "capture always wins" rule over silent data loss.
     */
    private fun maybeSealCapture(plaintext: String): JSONObject? {
        if (!com.chronicle.app.e2ee.E2eeManager.enabled.value) return null
        val blob = com.chronicle.app.e2ee.E2eeManager.sealText(plaintext)
        if (blob == null) {
            android.util.Log.w("MainViewModel", "e2ee enabled but vault locked; saving plaintext capture")
        }
        return blob
    }

    /** LAN outbox mirror (v1.11) — best-effort; Syncthing remains source of truth. */
    private fun enqueueLanOutbox(context: Context, entryJson: JSONObject): Boolean =
        com.chronicle.app.net.LanOutboxWorker.enqueueEntry(context, entryJson)

    fun saveEntry(
        context: Context,
        onSaveComplete: (() -> Unit)? = null,
        /** When true (widget quick-capture), persist via WorkManager so save survives process death. */
        viaWorkManager: Boolean = false,
    ) {        if (_isSaving.value) return
        val uriStr = _folderUri.value ?: return
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        if (_text.value.isBlank() && _attachedImages.value.isEmpty() && _pendingAudioPaths.value.isEmpty()) {
            return
        }
        _isSaving.value = true

        val wikiLinks = Regex("""\[\[(.*?)]]""").findAll(_text.value).map { it.groupValues[1] }.toList()
        val atLinks = Regex("""@(\w+)""").findAll(_text.value).map { it.groupValues[1] }.toList()
        val finalTags = (_selectedTags.value.toList() + wikiLinks + atLinks)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Text-only quick capture: durable worker path (no images/audio/edit).
        if (viaWorkManager &&
            _editingEntryId.value == null &&
            _attachedImages.value.isEmpty() &&
            _pendingAudioPaths.value.isEmpty() &&
            _text.value.isNotBlank()
        ) {
            com.chronicle.app.widget.QuickCaptureWorker.enqueue(
                appContext,
                _text.value,
                type = _entryType.value,
                tags = finalTags,
                mood = _mood.value,
            )
            _showCheckmark.value = true
            viewModelScope.launch {
                try {
                    kotlinx.coroutines.delay(400)
                    clearCapture()
                    _showCheckmark.value = false
                    onSaveComplete?.invoke()
                } finally {
                    _isSaving.value = false
                }
            }
            return
        }

        viewModelScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    try {
                        val repo = VaultRepository(appContext, treeUri)
                        val editingId = _editingEntryId.value
                        val now = ZonedDateTime.now()
                        val tsString = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

                        if (editingId != null) {
                            val existing = _entries.value.find { it.id == editingId } ?: return@withContext false
                            if (existing.processed) return@withContext false
                            if (existing.textEnc != null && !com.chronicle.app.e2ee.E2eeManager.unlocked.value) {
                                // Locked session cannot open the blob: saving would keep the
                                // stored ciphertext and silently discard the user's edit while
                                // the UI showed success. Refuse loudly instead.
                                _userMessages.tryEmit("Vault locked — unlock to edit encrypted entries")
                                return@withContext false
                            }
                            if (existing.processed) return@withContext false
                            // Re-seal happens inside serializeEntry when unlocked.
                            val updated = existing.copy(
                                text = _text.value,
                                textEnc = existing.textEnc ?: maybeSealCapture(_text.value),
                                type = _entryType.value,
                                tags = finalTags,
                                mood = _mood.value,
                                ts = existing.ts,
                            )
                            val images = _attachedImages.value
                            val audio = _pendingAudioPaths.value
                            val saved = repo.saveEntry(updated, images, audio)
                            saved != null
                        } else {
                            val id = generateEntryId(now, exists = { repo.entryFileExists(it) })
                            val sealedBlob = maybeSealCapture(_text.value)
                            val entry = Entry(
                                id = id,
                                ts = tsString,
                                type = _entryType.value,
                                text = if (sealedBlob != null) "" else _text.value,
                                textEnc = sealedBlob,
                                tags = finalTags,
                                images = emptyList(),
                                audio = emptyList(),
                                mood = _mood.value,
                                processed = false,
                            )
                            val saved = repo.saveEntry(entry, _attachedImages.value, _pendingAudioPaths.value)
                            if (saved != null) {
                                enqueueLanOutbox(appContext, org.json.JSONObject(serializeEntry(saved)))
                            }
                            saved != null
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }

                if (success) {
                    _showCheckmark.value = true
                    delay(700)
                    clearCapture()
                    _showCheckmark.value = false
                    refreshAll(appContext)
                    onSaveComplete?.invoke()
                } else {
                    _userMessages.tryEmit("Couldn't save entry. Check vault folder permissions and try again.")
                }
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteEntry(context: Context, entry: Entry) {
        if (entry.processed) return
        if (entry.textEnc != null && !com.chronicle.app.e2ee.E2eeManager.unlocked.value) {
            // Matches the PC API: deleting ciphertext you cannot read is
            // irreversible data destruction — require an unlocked vault.
            viewModelScope.launch {
                _userMessages.emit("Vault locked — unlock to delete encrypted entries")
            }
            return
        }
        val uriStr = _folderUri.value ?: return
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                VaultRepository(appContext, treeUri).deleteEntry(entry)
            }
            refreshAll(appContext)
        }
    }

    fun appendCurationOp(context: Context, op: CurationOp) {
        val uriStr = _folderUri.value ?: return
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            curationOpsMutex.withLock {
                withContext(Dispatchers.IO) {
                    VaultRepository(appContext, treeUri).appendCurationOp(op)
                }
            }
            // Optimistic: reload graph with overlay
            loadBrain(appContext)
        }
    }

    fun loadArchiveYear(context: Context, year: String) {
        val uriStr = _folderUri.value ?: return
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            val merged = withContext(Dispatchers.IO) {
                val repo = VaultRepository(appContext, treeUri)
                val base = repo.loadGraphWithOverlay() ?: return@withContext null
                val archive = repo.loadGraphArchive(year) ?: return@withContext base
                base.copy(
                    nodes = (base.nodes + archive.nodes).distinctBy { it.id },
                    edges = (base.edges + archive.edges).distinctBy { "${it.from}|${it.to}|${it.rel}" },
                )
            }
            if (merged != null) _brainGraph.value = merged
        }
    }

    fun refreshHealthConnectStatus(context: Context) {
        val appContext = context.applicationContext
        val manager = HealthConnectManager(appContext)
        _healthAvailability.value = manager.availability()
        viewModelScope.launch {
            _healthPermissionsGranted.value = withContext(Dispatchers.IO) {
                manager.hasAllPermissions()
            }
        }
    }

    fun setHealthAutoSync(context: Context, enabled: Boolean) {
        _healthAutoSync.value = enabled
        HealthSyncWorker.setAutoSync(context, enabled)
    }

    fun onHealthPermissionsResult(context: Context, granted: Set<String>) {
        _healthPermissionsGranted.value =
            granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)
        refreshHealthConnectStatus(context)
    }

    /** Import the last 30 local calendar days of sleep/steps into the vault. */
    fun importHealthLast30Days(context: Context, onDone: (Boolean) -> Unit = {}) {
        val uriStr = _folderUri.value ?: run {
            onDone(false)
            return
        }
        val treeUri = Uri.parse(uriStr)
        val appContext = context.applicationContext
        viewModelScope.launch {
            _healthImporting.value = true
            val ok = withContext(Dispatchers.IO) {
                val manager = HealthConnectManager(appContext)
                if (manager.availability() != HealthConnectAvailability.AVAILABLE) {
                    return@withContext false
                }
                if (!manager.hasAllPermissions()) return@withContext false
                val end = LocalDate.now().plusDays(1)
                val start = LocalDate.now().minusDays(29)
                val days = manager.importDays(start, end)
                val repo = VaultRepository(appContext, treeUri)
                val byMonth = days.groupBy { it.date.take(7) }
                var allOk = true
                for ((ym, monthDays) in byMonth) {
                    if (!repo.saveHealthMonth(ym, monthDays.associateBy { it.date })) {
                        allOk = false
                    }
                }
                if (allOk) {
                    val now = System.currentTimeMillis()
                    appContext.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putLong(HealthSyncWorker.KEY_LAST_IMPORT_MS, now)
                        .apply()
                    _healthLastImportMs.value = now
                }
                allOk
            }
            if (ok) {
                loadHealthInternal(appContext, treeUri, _entries.value)
            }
            _healthImporting.value = false
            onDone(ok)
        }
    }

    private suspend fun loadHealthInternal(
        appContext: Context,
        treeUri: Uri,
        entries: List<Entry>,
    ) {
        val months = mutableSetOf<String>()
        val ymFmt = DateTimeFormatter.ofPattern("yyyy-MM")
        months.add(LocalDate.now().format(ymFmt))
        months.add(LocalDate.now().minusMonths(1).format(ymFmt))
        entries.forEach { entry ->
            try {
                months.add(entryDayDate(entry).take(7))
            } catch (_: Exception) {
            }
        }
        val merged = withContext(Dispatchers.IO) {
            val repo = VaultRepository(appContext, treeUri)
            val map = mutableMapOf<String, HealthDay>()
            months.forEach { ym -> map.putAll(repo.loadHealthMonth(ym)) }
            map.toMap()
        }
        _healthByDate.value = merged
    }

    override fun onCleared() {
        stopVaultWatch()
        serveEventsClient?.stop() // cancels the blocking SSE read (socket leak fix)
        voiceRecorder?.cancel()
        nanoTagJob?.cancel()
        imageDescribeJob?.cancel()
        dailyDigestJob?.cancel()
        periodDigestJob?.cancel()
        daySummaryJob?.cancel()
        genAiService?.close()
        genAiService = null
        super.onCleared()
    }

    companion object {
        private const val VAULT_WATCH_INTERVAL_MS = 20_000L
        /** With 20s vault ticks, recheck LAN ~every 60s while foreground. */
        private const val LAN_HEALTH_POLL_EVERY_N_TICKS = 3
        private const val NANO_TAG_DEBOUNCE_MS = 1_500L
        private const val KEY_ON_DEVICE_AI = "on_device_ai_enabled"
        private const val KEY_NANO_RELEASE_STAGE = "nano_release_stage"
        private const val KEY_NANO_MODEL_PREFERENCE = "nano_model_preference"
        private const val CLOUD_SNIPPET_CHARS = 800
        private const val CLOUD_MAX_TOKENS = 512
    }
}
