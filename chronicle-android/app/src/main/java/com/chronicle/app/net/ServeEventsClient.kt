package com.chronicle.app.net

import android.content.Context
import com.chronicle.app.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Consumes `GET /events/stream` (SSE, token-required — CONTRACT v1.11) and
 * emits one [Unit] per `event: vault` frame so screens can refresh live
 * instead of polling. Auto-reconnects with capped backoff; a no-op when the
 * phone is unpaired.
 */
class ServeEventsClient(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _vaultChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val vaultChanged: SharedFlow<Unit> = _vaultChanged.asSharedFlow()

    private var job: Job? = null

    /**
     * The in-flight SSE call, if any. `job.cancel()` alone cannot interrupt a
     * blocking OkHttp read (cancellation is cooperative), so [stop] cancels
     * the call itself — the socket closes immediately instead of leaking for
     * up to the server's 30-minute stream rotation.
     */
    @Volatile
    private var currentCall: okhttp3.Call? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        currentCall?.cancel()
        job?.cancel()
        job = null
    }

    private suspend fun runLoop() {
        var backoffSec = 1L
        while (scope.isActive && coroutineActive()) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val baseUrl = prefs.getString("serve_base_url", "").orEmpty()
            if (baseUrl.isBlank()) {
                delay(15_000)
                continue
            }
            val token = SecurePrefs.get(context).getString(SecurePrefs.KEY_SERVE_TOKEN, null)
            val tlsFp = SecurePrefs.get(context).getString(SecurePrefs.KEY_SERVE_TLS_FP, null)
            // Pinned + CIDR-aware client; the serve heartbeats every ~15s so the
            // default 60s read timeout only ever fires on dead connections.
            val client = ServeClient.clientFor(baseUrl, tlsFp)
            val connected = streamOnce(client, baseUrl, token)
            backoffSec = if (connected) 1L else (backoffSec * 2).coerceAtMost(MAX_BACKOFF_SEC)
            delay(backoffSec * 1000)
        }
    }

    /** One SSE connection; returns true when the server accepted the request. */
    private suspend fun streamOnce(client: OkHttpClient, baseUrl: String, token: String?): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val builder = Request.Builder()
                    .url(ServeClient.normalizeBaseUrl(baseUrl) + "/events/stream")
                    .header("Accept", "text/event-stream")
                token?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    builder.header(ServeClient.AUTH_HEADER, it)
                }
                val call = client.newCall(builder.build())
                currentCall = call
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        resp.body?.source()?.use { source ->
                            var eventName = ""
                            while (true) {
                                if (!coroutineActive()) break // stopped while blocked? call.cancel() unblocks us
                                val line = source.readUtf8Line() ?: break
                                when {
                                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                                    line.isEmpty() -> {
                                        if (eventName == "vault") {
                                            _vaultChanged.tryEmit(Unit)
                                        }
                                        eventName = ""
                                    }
                                }
                            }
                        }
                        true // connected; server closed → quick reconnect
                    }
                } finally {
                    currentCall = null
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun coroutineActive(): Boolean = job?.isActive == true

    companion object {
        private const val PREFS = "chronicle_prefs"
        private const val MAX_BACKOFF_SEC = 60L
    }
}
