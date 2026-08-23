package com.chronicle.app.net

import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTPS / LAN chat client for Android cloud & Ollama-LAN providers.
 *
 * **Never** reuse [ServeClient] for Grok — ServeClient's private-IP gate would
 * block `api.x.ai`. Ollama LAN still requires private/loopback hosts (same checks
 * as serve).
 */
class CloudLlmClient(
    private val client: OkHttpClient = defaultClient(),
) {
    data class ChatMessage(val role: String, val content: String)

    data class ChatResult(
        val ok: Boolean,
        val text: String = "",
        val error: String? = null,
    )

    /**
     * Grok (xAI) OpenAI-compatible chat. Host must be on [ALLOWED_GROK_HOSTS].
     */
    fun chatGrok(
        apiKey: String,
        messages: List<ChatMessage>,
        model: String = DEFAULT_GROK_MODEL,
        baseUrl: String = DEFAULT_GROK_BASE,
        maxTokens: Int = 1024,
        temperature: Double = 0.6,
    ): ChatResult {
        val key = apiKey.trim()
        if (key.isEmpty()) return ChatResult(false, error = "Grok API key not set")
        val normalized = normalizeHttpsBase(baseUrl)
        if (!isAllowedGrokUrl(normalized)) {
            return ChatResult(false, error = "Grok URL host not allowlisted")
        }
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", false)
            .put(
                "messages",
                JSONArray().apply {
                    messages.forEach { m ->
                        put(JSONObject().put("role", m.role).put("content", m.content))
                    }
                },
            )
        return try {
            val body = postJson(
                url = "$normalized/chat/completions",
                payload = payload,
                headers = mapOf("Authorization" to "Bearer $key"),
                timeoutSec = 120,
            )
            val choices = body.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return ChatResult(false, error = "Grok response missing choices")
            }
            val content = choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content")
                .orEmpty()
                .trim()
            ChatResult(ok = true, text = content)
        } catch (e: Exception) {
            ChatResult(false, error = e.message ?: "Grok chat failed")
        }
    }

    /**
     * Ollama chat on LAN. [baseUrl] must be private/loopback (same as serve).
     */
    fun chatOllamaLan(
        baseUrl: String,
        messages: List<ChatMessage>,
        model: String = DEFAULT_OLLAMA_MODEL,
        maxTokens: Int = 1024,
    ): ChatResult {
        val normalized = ServeClient.normalizeBaseUrl(baseUrl)
        if (normalized.isBlank()) return ChatResult(false, error = "Ollama URL not set")
        if (!ServeClient.isPrivateOrLoopbackUrl(normalized)) {
            return ChatResult(false, error = "Ollama URL must be a private or loopback address")
        }
        val payload = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray().apply {
                    messages.forEach { m ->
                        put(JSONObject().put("role", m.role).put("content", m.content))
                    }
                },
            )
            .put("options", JSONObject().put("num_predict", maxTokens))
        return try {
            val body = postJson(
                url = "$normalized/api/chat",
                payload = payload,
                headers = emptyMap(),
                timeoutSec = 120,
            )
            val content = body.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
                .trim()
            if (content.isEmpty()) {
                ChatResult(false, error = "Ollama empty response")
            } else {
                ChatResult(ok = true, text = content)
            }
        } catch (e: Exception) {
            ChatResult(false, error = e.message ?: "Ollama chat failed")
        }
    }

    private fun postJson(
        url: String,
        payload: JSONObject,
        headers: Map<String, String>,
        timeoutSec: Long,
    ): JSONObject {
        val timed = client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val reqBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        timed.newCall(reqBuilder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
            }
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    companion object {
        const val DEFAULT_GROK_BASE = "https://api.x.ai/v1"
        const val DEFAULT_GROK_MODEL = "grok-2-latest"
        const val DEFAULT_OLLAMA_MODEL = "llama3.2"

        /** HTTPS hosts allowed for Grok BYOK (exact host match). */
        val ALLOWED_GROK_HOSTS: Set<String> = setOf("api.x.ai")

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun normalizeHttpsBase(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return DEFAULT_GROK_BASE
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            return withScheme.trimEnd('/')
        }

        fun isAllowedGrokUrl(url: String): Boolean {
            if (url.isBlank()) return false
            return try {
                val uri = URI(normalizeHttpsBase(url))
                if (!uri.scheme.equals("https", ignoreCase = true)) return false
                val host = uri.host?.lowercase() ?: return false
                host in ALLOWED_GROK_HOSTS
            } catch (_: Exception) {
                false
            }
        }

        /** Same private-host gate as [ServeClient] — for Ollama LAN URL validation. */
        fun isPrivateOrLoopbackUrl(url: String): Boolean =
            ServeClient.isPrivateOrLoopbackUrl(ServeClient.normalizeBaseUrl(url))
    }
}
