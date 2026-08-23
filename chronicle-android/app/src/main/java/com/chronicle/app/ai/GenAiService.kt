package com.chronicle.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import com.google.mlkit.genai.imagedescription.ImageDescriber
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** On-device GenAI feature identifiers. */
enum class AiFeature {
    PROMPT,
    PROOFREAD,
    REWRITE,
    SUMMARIZE,
    IMAGE_DESCRIPTION,
}

/** Prompt API release stage (Settings + GenerationConfig). */
enum class NanoReleaseStage {
    STABLE,
    PREVIEW,
}

/** Prompt API model preference (Settings + GenerationConfig). */
enum class NanoModelPreference {
    FULL,
    FAST,
}

/** One journal entry snippet for day digests (optional photo bitmap, mood). */
data class DigestEntrySnippet(
    val type: String,
    val text: String,
    val mood: Int? = null,
    val imageBitmap: Bitmap? = null,
)

/** Rewrite tone options exposed in the capture sparkle menu. */
enum class RewriteTone {
    ELABORATE,
    FRIENDLY,
    PROFESSIONAL,
    SHORTEN,
}

/** Per-feature availability for Settings / UI gating. */
sealed class AiAvailability {
    data object Checking : AiAvailability()
    data object Unavailable : AiAvailability()
    data object Downloadable : AiAvailability()
    data class Downloading(val bytesDownloaded: Long = 0L) : AiAvailability()
    data object Available : AiAvailability()
}

/**
 * Best-effort wrapper around ML Kit GenAI (Gemini Nano via AICore).
 * Unsupported devices and inference failures degrade to null/empty — never throw to callers.
 */
class GenAiService(context: Context) {
    private val appContext = context.applicationContext

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val availabilityFlows: Map<AiFeature, MutableStateFlow<AiAvailability>> =
        AiFeature.entries.associateWith { MutableStateFlow(AiAvailability.Checking) }

    fun availability(feature: AiFeature): StateFlow<AiAvailability> =
        availabilityFlows.getValue(feature).asStateFlow()

    val anyFeatureSupported: Boolean
        get() = availabilityFlows.values.any { flow ->
            when (flow.value) {
                is AiAvailability.Available,
                is AiAvailability.Downloadable,
                is AiAvailability.Downloading,
                -> true
                else -> false
            }
        }

    private var promptModel: GenerativeModel? = null
    private var proofreader: Proofreader? = null
    private var summarizer: Summarizer? = null
    private var imageDescriber: ImageDescriber? = null
    private val rewriters = mutableMapOf<RewriteTone, Rewriter>()

    private var requestedStage: NanoReleaseStage = NanoReleaseStage.STABLE
    private var requestedPreference: NanoModelPreference = NanoModelPreference.FULL
    private var activeStage: NanoReleaseStage = NanoReleaseStage.STABLE
    private var activePreference: NanoModelPreference = NanoModelPreference.FULL
    private var fellBackToStableFull: Boolean = false

    private val _nanoReleaseStage = MutableStateFlow(NanoReleaseStage.STABLE)
    val nanoReleaseStage: StateFlow<NanoReleaseStage> = _nanoReleaseStage.asStateFlow()

    private val _nanoModelPreference = MutableStateFlow(NanoModelPreference.FULL)
    val nanoModelPreference: StateFlow<NanoModelPreference> = _nanoModelPreference.asStateFlow()

    private val _usingStableFullFallback = MutableStateFlow(false)
    val usingStableFullFallback: StateFlow<Boolean> = _usingStableFullFallback.asStateFlow()

    private val _baseModelName = MutableStateFlow<String?>(null)
    val baseModelName: StateFlow<String?> = _baseModelName.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }

    /**
     * Apply Prompt API model selection. Rebuilds the client; falls back to STABLE+FULL
     * when the requested preview/fast variant is unavailable.
     */
    fun setModelSelection(stage: NanoReleaseStage, preference: NanoModelPreference) {
        requestedStage = stage
        requestedPreference = preference
        _nanoReleaseStage.value = stage
        _nanoModelPreference.value = preference
        closePromptModel()
        activeStage = stage
        activePreference = preference
        fellBackToStableFull = false
        _usingStableFullFallback.value = false
        _baseModelName.value = null
    }

    /** Refresh [AiAvailability] for every feature. Safe to call repeatedly. */
    suspend fun refreshAllStatuses() = withContext(Dispatchers.IO) {
        AiFeature.entries.forEach { feature ->
            refreshStatus(feature)
        }
        refreshBaseModelName()
    }

    suspend fun refreshStatus(feature: AiFeature) {
        val flow = availabilityFlows.getValue(feature)
        try {
            if (feature == AiFeature.PROMPT) {
                ensurePromptClientWithFallback()
            }
            val status = checkStatusInt(feature)
            flow.value = statusToAvailability(status)
        } catch (t: Throwable) {
            Log.d(TAG, "checkStatus failed for $feature: ${t.message}")
            flow.value = AiAvailability.Unavailable
        }
    }

    /** Download model assets when [AiAvailability.Downloadable]. Returns true on success. */
    suspend fun downloadFeature(feature: AiFeature): Boolean = withContext(Dispatchers.IO) {
        val flow = availabilityFlows.getValue(feature)
        try {
            flow.value = AiAvailability.Downloading()
            when (feature) {
                AiFeature.PROMPT -> downloadPrompt(flow)
                else -> downloadLoRaFeature(feature, flow)
            }
            refreshStatus(feature)
            flow.value is AiAvailability.Available
        } catch (t: Throwable) {
            Log.d(TAG, "download failed for $feature: ${t.message}")
            refreshStatus(feature)
            false
        }
    }

    /** Resolved base model name from the active Prompt client, if available. */
    suspend fun baseModelName(): String? = withContext(Dispatchers.IO) {
        refreshBaseModelName()
        _baseModelName.value
    }

    /**
     * Suggest journal tags via Prompt API.
     * Returns up to 6 tag strings; empty on failure / disabled / unavailable.
     * [imageDescription] (from [describeImage]) feeds capture tags when present.
     */
    suspend fun suggestTags(
        entryText: String,
        taxonomyCanonicals: List<String>,
        imageDescription: String? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        if (!_enabled.value) return@withContext emptyList()
        val text = entryText.trim()
        val image = imageDescription?.trim().orEmpty()
        if (text.length < 12 && image.isEmpty()) return@withContext emptyList()
        try {
            if (!ensureReady(AiFeature.PROMPT)) return@withContext emptyList()
            val model = promptClient() ?: return@withContext emptyList()
            val canon = taxonomyCanonicals.take(48).joinToString(", ")
            val prompt = buildString {
                appendLine("You suggest short journal tags for a private diary.")
                appendLine("Prefer existing tags from this list when they fit: [$canon]")
                appendLine("Rules:")
                appendLine("- Up to 4 existing tags + up to 2 new lowercase tags (1-3 words, no #).")
                appendLine("- Favor concrete themes (people, places, projects, feelings) over vague words.")
                appendLine("- If an image description is given, include 1-2 tags grounded in what the photo shows.")
                appendLine("- Reply with ONLY a JSON array of strings, no markdown or commentary.")
                if (image.isNotBlank()) {
                    appendLine("Image description: ${image.take(800)}")
                }
                if (text.isNotBlank()) {
                    appendLine("Entry:")
                    append(text.take(2500))
                }
            }
            val response = model.generateContent(prompt)
            val raw = response.candidates.firstOrNull()?.text.orEmpty()
            parseTagJson(raw)
        } catch (t: Throwable) {
            Log.d(TAG, "suggestTags failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * Suggest mood 1–5 from entry text (suggestion-only; never auto-applied).
     * Returns null on failure / disabled / unavailable.
     */
    suspend fun suggestMood(entryText: String): Int? = withContext(Dispatchers.IO) {
        if (!_enabled.value) return@withContext null
        val text = entryText.trim()
        if (text.length < 20) return@withContext null
        try {
            if (!ensureReady(AiFeature.PROMPT)) return@withContext null
            val model = promptClient() ?: return@withContext null
            val prompt = buildString {
                appendLine("Rate the writer's mood in this private journal entry.")
                appendLine("Scale: 1=very low, 2=low, 3=neutral, 4=good, 5=great.")
                appendLine("Reply with ONLY a single digit 1-5, nothing else.")
                appendLine("Entry:")
                append(text.take(2000))
            }
            val response = model.generateContent(prompt)
            val raw = response.candidates.firstOrNull()?.text.orEmpty().trim()
            parseMoodDigit(raw)
        } catch (t: Throwable) {
            Log.d(TAG, "suggestMood failed: ${t.message}")
            null
        }
    }

    /**
     * On-device summary of a recall answer (offline / LAN degraded).
     * Suggestion-only — not persisted.
     */
    suspend fun summarizeRecall(answer: String, citationsSnippets: List<String> = emptyList()): String? =
        withContext(Dispatchers.IO) {
            if (!_enabled.value) return@withContext null
            val body = buildString {
                append(answer.trim())
                if (citationsSnippets.isNotEmpty()) {
                    append("\n\nSources:\n")
                    citationsSnippets.take(6).forEach { append("- ").append(it.take(160)).append('\n') }
                }
            }
            if (body.length < 80) return@withContext null
            // Prefer summarizer when long enough; fall back to prompt for shorter answers.
            if (body.length >= 400) {
                summarize(body)?.let { return@withContext it }
            }
            try {
                if (!ensureReady(AiFeature.PROMPT)) return@withContext null
                val model = promptClient() ?: return@withContext null
                val prompt = buildString {
                    appendLine("Summarize this private journal recall answer in 2-3 short bullets.")
                    appendLine("Keep names and concrete facts. No preamble.")
                    append(body.take(6000))
                }
                val response = model.generateContent(prompt)
                response.candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() }
            } catch (t: Throwable) {
                Log.d(TAG, "summarizeRecall failed: ${t.message}")
                null
            }
        }

    /**
     * Daily digest from today's entries (Timeline card). Suggestion-only.
     * Streams partial text via [onPartial] when Prompt API is used.
     */
    suspend fun dailyDigest(
        dayLabel: String,
        entries: List<DigestEntrySnippet>,
        onPartial: ((String) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (!_enabled.value) return@withContext null
        val cleaned = entries.map { snippet ->
            snippet.copy(text = snippet.text.trim())
        }.filter { it.text.isNotEmpty() || it.imageBitmap != null }
        if (cleaned.isEmpty()) return@withContext null

        val photoEntries = cleaned.filter { it.imageBitmap != null }.take(MAX_DIGEST_PHOTOS)
        val combinedText = cleaned.joinToString("\n\n---\n\n") { formatDigestSnippet(it) }
        if (combinedText.length < 120 && photoEntries.isEmpty()) return@withContext null

        // Prefer Prompt API (themes / mood / photos). Summarization is text-only fallback.
        try {
            if (ensureReady(AiFeature.PROMPT)) {
                val model = promptClient()
                if (model != null) {
                    val promptBody = buildDailyDigestPrompt(dayLabel, cleaned, photoEntries)
                    val primaryBitmap = photoEntries.firstOrNull()?.imageBitmap
                    val request = if (primaryBitmap != null) {
                        generateContentRequest(ImagePart(primaryBitmap), TextPart(promptBody)) {
                            temperature = DIGEST_TEMPERATURE
                            maxOutputTokens = DIGEST_MAX_OUTPUT_TOKENS
                            candidateCount = 1
                        }
                    } else {
                        generateContentRequest(TextPart(promptBody)) {
                            temperature = DIGEST_TEMPERATURE
                            maxOutputTokens = DIGEST_MAX_OUTPUT_TOKENS
                            candidateCount = 1
                        }
                    }
                    streamOrGenerate(model, request, onPartial)?.let { return@withContext it }
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "dailyDigest failed: ${t.message}")
            // Multimodal can fail on some devices; retry text-only with descriptions.
            if (photoEntries.isNotEmpty()) {
                try {
                    if (ensureReady(AiFeature.PROMPT)) {
                        val model = promptClient()
                        if (model != null) {
                            val textOnly = buildDailyDigestPrompt(
                                dayLabel,
                                cleaned,
                                photoEntries,
                                forceDescribeAll = true,
                            )
                            val request = generateContentRequest(TextPart(textOnly)) {
                                temperature = DIGEST_TEMPERATURE
                                maxOutputTokens = DIGEST_MAX_OUTPUT_TOKENS
                                candidateCount = 1
                            }
                            streamOrGenerate(model, request, onPartial)?.let { return@withContext it }
                        }
                    }
                } catch (t2: Throwable) {
                    Log.d(TAG, "dailyDigest text fallback failed: ${t2.message}")
                }
            }
        }

        // Summarization API only when Prompt unavailable/failed, long text, and no photos.
        if (photoEntries.isEmpty() && combinedText.length >= 400) {
            summarize(combinedText)?.let { return@withContext it }
        }
        null
    }

    /**
     * Week/month rollup when Mac markdown is absent. Suggestion-only — never persisted.
     */
    suspend fun periodDigest(
        periodLabel: String,
        entrySnippets: List<String>,
        onPartial: ((String) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (!_enabled.value) return@withContext null
        val combined = samplePeriodSnippets(entrySnippets)
        if (combined.length < 120) return@withContext null
        try {
            if (!ensureReady(AiFeature.PROMPT)) return@withContext null
            val model = promptClient() ?: return@withContext null
            val prompt = buildString {
                appendLine("Write a brief $periodLabel rollup from these private journal entries.")
                appendLine("Cover themes, mood arc, and notable events in 3-6 short bullets.")
                appendLine("No preamble. Do not invent facts.")
                append(combined)
            }
            val request = generateContentRequest(TextPart(prompt)) {
                temperature = DIGEST_TEMPERATURE
                maxOutputTokens = PERIOD_DIGEST_MAX_OUTPUT_TOKENS
                candidateCount = 1
            }
            streamOrGenerate(model, request, onPartial)
        } catch (t: Throwable) {
            Log.d(TAG, "periodDigest failed: ${t.message}")
            null
        }
    }

    /**
     * Prompt-based summary with optional streaming (manual day/entry sheets).
     * Falls back to Summarization API for long text when Prompt is unavailable.
     */
    suspend fun summarizeStreaming(
        text: String,
        onPartial: ((String) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (!_enabled.value) return@withContext null
        val input = text.trim()
        if (input.isEmpty()) return@withContext null
        try {
            if (ensureReady(AiFeature.PROMPT)) {
                val model = promptClient()
                if (model != null) {
                    val prompt = buildString {
                        appendLine("Summarize this private journal text in 2-4 short bullets.")
                        appendLine("Keep names and concrete facts. No preamble.")
                        append(input.take(8_000))
                    }
                    val request = generateContentRequest(TextPart(prompt)) {
                        temperature = DIGEST_TEMPERATURE
                        maxOutputTokens = DIGEST_MAX_OUTPUT_TOKENS
                        candidateCount = 1
                    }
                    streamOrGenerate(model, request, onPartial)?.let { return@withContext it }
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "summarizeStreaming prompt failed: ${t.message}")
        }
        if (input.length >= 400) summarize(input) else null
    }

    suspend fun proofread(text: String): String? = withContext(Dispatchers.IO) {
        if (!_enabled.value) return@withContext null
        val input = text.trim()
        if (input.isEmpty()) return@withContext null
        try {
            if (!ensureReady(AiFeature.PROOFREAD)) return@withContext null
            val client = proofreaderClient() ?: return@withContext null
            val request = ProofreadingRequest.builder(input).build()
            val result = client.runInference(request).await()
            result.results.firstOrNull()?.text?.takeIf { it.isNotBlank() && it != input }
        } catch (t: Throwable) {
            Log.d(TAG, "proofread failed: ${t.message}")
            null
        }
    }

    suspend fun rewrite(text: String, tone: RewriteTone): String? = withContext(Dispatchers.IO) {
        if (!_enabled.value) return@withContext null
        val input = text.trim()
        if (input.isEmpty()) return@withContext null
        try {
            if (!ensureReady(AiFeature.REWRITE)) return@withContext null
            val client = rewriterClient(tone) ?: return@withContext null
            val request = RewritingRequest.builder(input).build()
            val result = client.runInference(request).await()
            result.results.firstOrNull()?.text?.takeIf { it.isNotBlank() && it != input }
        } catch (t: Throwable) {
            Log.d(TAG, "rewrite failed: ${t.message}")
            null
        }
    }

    suspend fun summarize(text: String): String? = withContext(Dispatchers.IO) {
        if (!_enabled.value) return@withContext null
        val input = text.trim()
        // ARTICLE input performs best above ~400 characters.
        if (input.length < 400) return@withContext null
        try {
            if (!ensureReady(AiFeature.SUMMARIZE)) return@withContext null
            val client = summarizerClient() ?: return@withContext null
            val request = SummarizationRequest.builder(input.take(12_000)).build()
            val result = client.runInference(request).await()
            result.summary.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.d(TAG, "summarize failed: ${t.message}")
            null
        }
    }

    suspend fun describeImage(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        if (!_enabled.value) return@withContext null
        try {
            if (!ensureReady(AiFeature.IMAGE_DESCRIPTION)) return@withContext null
            val client = imageDescriberClient() ?: return@withContext null
            val request = ImageDescriptionRequest.builder(bitmap).build()
            val result = client.runInference(request).await()
            result.description.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.d(TAG, "describeImage failed: ${t.message}")
            null
        }
    }

    fun close() {
        closePromptModel()
        try {
            proofreader?.close()
        } catch (_: Throwable) {
        }
        proofreader = null
        try {
            summarizer?.close()
        } catch (_: Throwable) {
        }
        summarizer = null
        try {
            imageDescriber?.close()
        } catch (_: Throwable) {
        }
        imageDescriber = null
        rewriters.values.forEach { r ->
            try {
                r.close()
            } catch (_: Throwable) {
            }
        }
        rewriters.clear()
    }

    private suspend fun buildDailyDigestPrompt(
        dayLabel: String,
        entries: List<DigestEntrySnippet>,
        photoEntries: List<DigestEntrySnippet>,
        forceDescribeAll: Boolean = false,
    ): String {
        val photoNotes = mutableListOf<String>()
        val describeFrom = when {
            forceDescribeAll -> photoEntries
            photoEntries.size > 1 -> photoEntries.drop(1)
            else -> emptyList()
        }
        for ((index, snippet) in describeFrom.withIndex()) {
            val bitmap = snippet.imageBitmap ?: continue
            val desc = describeImage(bitmap)?.take(400)
            if (!desc.isNullOrBlank()) {
                photoNotes.add("Photo ${index + 1} (${snippet.type}): $desc")
            }
        }
        return buildString {
            appendLine("Write a brief daily digest for $dayLabel from these private journal entries.")
            appendLine("Cover: themes, mood arc, and notable events (2-4 short bullets).")
            appendLine("Use mood scores (1=very low … 5=great) when present. No preamble.")
            if (photoNotes.isNotEmpty()) {
                appendLine("Photo context:")
                photoNotes.forEach { appendLine("- $it") }
            }
            if (!forceDescribeAll && photoEntries.isNotEmpty()) {
                appendLine("An attached photo from the day is included; ground 1 bullet in what it shows when relevant.")
            }
            appendLine("Entries:")
            append(entries.joinToString("\n\n---\n\n") { formatDigestSnippet(it) }.take(8_000))
        }
    }

    private fun formatDigestSnippet(snippet: DigestEntrySnippet): String = buildString {
        append(snippet.type)
        snippet.mood?.let { append(" · mood ").append(it) }
        append(": ")
        append(snippet.text.take(2_000))
    }

    private suspend fun streamOrGenerate(
        model: GenerativeModel,
        request: com.google.mlkit.genai.prompt.GenerateContentRequest,
        onPartial: ((String) -> Unit)?,
    ): String? {
        if (onPartial == null) {
            val response = model.generateContent(request)
            return response.candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() }
        }
        val accumulated = StringBuilder()
        model.generateContentStream(request).collect { chunk ->
            val piece = chunk.candidates.firstOrNull()?.text.orEmpty()
            if (piece.isNotEmpty()) {
                accumulated.append(piece)
                if (currentCoroutineContext().isActive) {
                    onPartial(accumulated.toString())
                }
            }
        }
        return accumulated.toString().takeIf { it.isNotBlank() }
    }

    private suspend fun ensureReady(feature: AiFeature): Boolean {
        refreshStatus(feature)
        return when (val avail = availabilityFlows.getValue(feature).value) {
            is AiAvailability.Available -> true
            is AiAvailability.Downloadable -> downloadFeature(feature)
            is AiAvailability.Downloading -> false
            else -> {
                Log.d(TAG, "$feature not ready: $avail")
                false
            }
        }
    }

    private suspend fun checkStatusInt(feature: AiFeature): Int = when (feature) {
        AiFeature.PROMPT -> {
            ensurePromptClientWithFallback()
            promptModel?.checkStatus() ?: FeatureStatus.UNAVAILABLE
        }
        AiFeature.PROOFREAD -> proofreaderClient()?.checkFeatureStatus()?.await()
            ?: FeatureStatus.UNAVAILABLE
        AiFeature.REWRITE -> rewriterClient(RewriteTone.FRIENDLY)?.checkFeatureStatus()?.await()
            ?: FeatureStatus.UNAVAILABLE
        AiFeature.SUMMARIZE -> summarizerClient()?.checkFeatureStatus()?.await()
            ?: FeatureStatus.UNAVAILABLE
        AiFeature.IMAGE_DESCRIPTION -> imageDescriberClient()?.checkFeatureStatus()?.await()
            ?: FeatureStatus.UNAVAILABLE
    }

    private suspend fun downloadPrompt(flow: MutableStateFlow<AiAvailability>) {
        ensurePromptClientWithFallback()
        val model = promptModel ?: return
        model.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadStarted ->
                    flow.value = AiAvailability.Downloading(0L)
                is DownloadStatus.DownloadProgress ->
                    flow.value = AiAvailability.Downloading(status.totalBytesDownloaded)
                is DownloadStatus.DownloadCompleted ->
                    flow.value = AiAvailability.Available
                is DownloadStatus.DownloadFailed ->
                    throw status.e
            }
        }
    }

    private suspend fun downloadLoRaFeature(
        feature: AiFeature,
        flow: MutableStateFlow<AiAvailability>,
    ) {
        val clientDownload: (DownloadCallback) -> com.google.common.util.concurrent.ListenableFuture<Void> =
            when (feature) {
                AiFeature.PROOFREAD -> { cb -> proofreaderClient()!!.downloadFeature(cb) }
                AiFeature.REWRITE -> { cb -> rewriterClient(RewriteTone.FRIENDLY)!!.downloadFeature(cb) }
                AiFeature.SUMMARIZE -> { cb -> summarizerClient()!!.downloadFeature(cb) }
                AiFeature.IMAGE_DESCRIPTION -> { cb -> imageDescriberClient()!!.downloadFeature(cb) }
                AiFeature.PROMPT -> error("use downloadPrompt")
            }
        val callback = object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) {
                flow.value = AiAvailability.Downloading(0L)
            }

            override fun onDownloadProgress(totalBytesDownloaded: Long) {
                flow.value = AiAvailability.Downloading(totalBytesDownloaded)
            }

            override fun onDownloadCompleted() = Unit

            override fun onDownloadFailed(e: GenAiException) = Unit
        }
        clientDownload(callback).await()
    }

    private fun promptClient(): GenerativeModel? = promptModel

    /**
     * Create Prompt client for the requested model config; fall back to STABLE+FULL
     * when preview/fast is unavailable (Google best practice).
     */
    private suspend fun ensurePromptClientWithFallback() {
        if (promptModel == null) {
            activeStage = requestedStage
            activePreference = requestedPreference
            fellBackToStableFull = false
            promptModel = createPromptClient(activeStage, activePreference)
        }
        val model = promptModel ?: return
        val status = try {
            model.checkStatus()
        } catch (t: Throwable) {
            Log.d(TAG, "prompt checkStatus failed: ${t.message}")
            FeatureStatus.UNAVAILABLE
        }
        val wantsNonDefault =
            requestedStage != NanoReleaseStage.STABLE ||
                requestedPreference != NanoModelPreference.FULL
        if (status == FeatureStatus.UNAVAILABLE && wantsNonDefault && !fellBackToStableFull) {
            Log.d(TAG, "Prompt model unavailable for $requestedStage/$requestedPreference; falling back to STABLE/FULL")
            closePromptModel()
            activeStage = NanoReleaseStage.STABLE
            activePreference = NanoModelPreference.FULL
            fellBackToStableFull = true
            _usingStableFullFallback.value = true
            promptModel = createPromptClient(activeStage, activePreference)
        } else {
            _usingStableFullFallback.value = fellBackToStableFull
        }
    }

    private fun createPromptClient(
        stage: NanoReleaseStage,
        preference: NanoModelPreference,
    ): GenerativeModel? {
        return try {
            val config = generationConfig {
                modelConfig = modelConfig {
                    releaseStage = when (stage) {
                        NanoReleaseStage.STABLE -> ModelReleaseStage.STABLE
                        NanoReleaseStage.PREVIEW -> ModelReleaseStage.PREVIEW
                    }
                    this.preference = when (preference) {
                        NanoModelPreference.FULL -> ModelPreference.FULL
                        NanoModelPreference.FAST -> ModelPreference.FAST
                    }
                }
            }
            Generation.getClient(config)
        } catch (t: Throwable) {
            Log.d(TAG, "prompt client init failed: ${t.message}")
            null
        }
    }

    private fun closePromptModel() {
        try {
            promptModel?.close()
        } catch (_: Throwable) {
        }
        promptModel = null
    }

    private suspend fun refreshBaseModelName() {
        try {
            ensurePromptClientWithFallback()
            val name = promptModel?.getBaseModelName()?.takeIf { it.isNotBlank() }
            _baseModelName.value = name
        } catch (t: Throwable) {
            Log.d(TAG, "getBaseModelName failed: ${t.message}")
            _baseModelName.value = null
        }
    }

    private fun proofreaderClient(): Proofreader? {
        if (proofreader == null) {
            try {
                val options = ProofreaderOptions.builder(appContext)
                    .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                    .setLanguage(ProofreaderOptions.Language.ENGLISH)
                    .build()
                proofreader = Proofreading.getClient(options)
            } catch (t: Throwable) {
                Log.d(TAG, "proofreader init failed: ${t.message}")
            }
        }
        return proofreader
    }

    private fun rewriterClient(tone: RewriteTone): Rewriter? {
        rewriters[tone]?.let { return it }
        return try {
            val outputType = when (tone) {
                RewriteTone.ELABORATE -> RewriterOptions.OutputType.ELABORATE
                RewriteTone.FRIENDLY -> RewriterOptions.OutputType.FRIENDLY
                RewriteTone.PROFESSIONAL -> RewriterOptions.OutputType.PROFESSIONAL
                RewriteTone.SHORTEN -> RewriterOptions.OutputType.SHORTEN
            }
            val options = RewriterOptions.builder(appContext)
                .setOutputType(outputType)
                .setLanguage(RewriterOptions.Language.ENGLISH)
                .build()
            Rewriting.getClient(options).also { rewriters[tone] = it }
        } catch (t: Throwable) {
            Log.d(TAG, "rewriter init failed: ${t.message}")
            null
        }
    }

    private fun summarizerClient(): Summarizer? {
        if (summarizer == null) {
            try {
                val options = SummarizerOptions.builder(appContext)
                    .setInputType(SummarizerOptions.InputType.ARTICLE)
                    .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                    .setLanguage(SummarizerOptions.Language.ENGLISH)
                    .setLongInputAutoTruncationEnabled(true)
                    .build()
                summarizer = Summarization.getClient(options)
            } catch (t: Throwable) {
                Log.d(TAG, "summarizer init failed: ${t.message}")
            }
        }
        return summarizer
    }

    private fun imageDescriberClient(): ImageDescriber? {
        if (imageDescriber == null) {
            try {
                val options = ImageDescriberOptions.builder(appContext).build()
                imageDescriber = ImageDescription.getClient(options)
            } catch (t: Throwable) {
                Log.d(TAG, "imageDescriber init failed: ${t.message}")
            }
        }
        return imageDescriber
    }

    companion object {
        private const val TAG = "GenAiService"
        private const val MAX_DIGEST_PHOTOS = 2
        private const val DIGEST_TEMPERATURE = 0.3f
        private const val DIGEST_MAX_OUTPUT_TOKENS = 256
        private const val PERIOD_DIGEST_MAX_OUTPUT_TOKENS = 256
        const val PERIOD_DIGEST_CHAR_BUDGET = 8_000
        const val PERIOD_DIGEST_MAX_ENTRIES = 40

        fun statusToAvailability(status: Int): AiAvailability = when (status) {
            FeatureStatus.AVAILABLE -> AiAvailability.Available
            FeatureStatus.DOWNLOADABLE -> AiAvailability.Downloadable
            FeatureStatus.DOWNLOADING -> AiAvailability.Downloading()
            else -> AiAvailability.Unavailable
        }

        /**
         * Cap period rollup input: take leading entries within a char budget (~8k).
         */
        fun samplePeriodSnippets(
            snippets: List<String>,
            charBudget: Int = PERIOD_DIGEST_CHAR_BUDGET,
            maxEntries: Int = PERIOD_DIGEST_MAX_ENTRIES,
        ): String {
            val cleaned = snippets.map { it.trim() }.filter { it.isNotEmpty() }.take(maxEntries)
            if (cleaned.isEmpty()) return ""
            val sb = StringBuilder()
            for (snippet in cleaned) {
                val piece = snippet.take(500)
                val addition = if (sb.isEmpty()) piece else "\n\n---\n\n$piece"
                if (sb.length + addition.length > charBudget) {
                    val remaining = charBudget - sb.length
                    if (remaining > 40) {
                        val prefix = if (sb.isEmpty()) "" else "\n\n---\n\n"
                        sb.append(prefix).append(piece.take(remaining - prefix.length))
                    }
                    break
                }
                sb.append(addition)
            }
            return sb.toString()
        }

        fun periodCacheKeyWeek(weekStart: java.time.LocalDate): String =
            "week:${weekStart}"

        fun periodCacheKeyMonth(yearMonth: java.time.YearMonth): String =
            "month:$yearMonth"

        fun parseTagJson(raw: String): List<String> {
            val trimmed = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val start = trimmed.indexOf('[')
            val end = trimmed.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            return try {
                val arr = JSONArray(trimmed.substring(start, end + 1))
                buildList {
                    for (i in 0 until arr.length()) {
                        val tag = arr.optString(i).trim().trimStart('#').trim()
                        if (tag.isNotEmpty()) add(tag)
                    }
                }.distinctBy { it.lowercase() }.take(6)
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseMoodDigit(raw: String): Int? {
            val digit = raw.firstOrNull { it in '1'..'5' } ?: return null
            return digit.digitToInt()
        }
    }
}
