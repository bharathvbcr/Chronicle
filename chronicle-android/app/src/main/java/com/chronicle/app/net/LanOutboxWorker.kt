package com.chronicle.app.net

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chronicle.app.SecurePrefs
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * LAN outbox (CONTRACT v1.11): pushes phone captures to the Mac over the LAN
 * immediately so `chronicle process` doesn't wait on Syncthing.
 *
 * Queue: append-only JSONL in app-private storage (`filesDir/lan_outbox.jsonl`)
 * — never the synced vault. Each line is `{entry:{...}}` exactly as written to
 * the SAF capture folder, so `POST /entries/mirror` dedupes idempotently and a
 * 409 (diverged content) means Syncthing already won — drop the line.
 * Network failures stop at the first undelivered line (ordering preserved),
 * keep it on disk, and back off via [Result.retry].
 */
class LanOutboxWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    internal suspend fun doWorkInternal(clientFactory: () -> ServeClient?): Result {
        val baseUrl = prefs().getString("serve_base_url", "").orEmpty()
        if (baseUrl.isBlank()) return Result.success() // no pairing → SAF/Syncthing only
        val token = SecurePrefs.get(applicationContext)
            .getString(SecurePrefs.KEY_SERVE_TOKEN, null)
        val tlsFp = SecurePrefs.get(applicationContext)
            .getString(SecurePrefs.KEY_SERVE_TLS_FP, null)

        val queueFile = queueFile(applicationContext)
        val lines = OutboxStore.readAll(queueFile)
        if (lines.isEmpty()) {
            queueFile.delete()
            return Result.success()
        }

        val client = clientFactory() ?: ServeClient(
            client = ServeClient.clientFor(baseUrl, tlsFp),
            tokenProvider = { token },
        )

        var processed = 0
        var networkFailure = false
        for (line in lines) {
            val entryJson = runCatching { JSONObject(line).optJSONObject("entry") }
                .getOrNull()
            if (entryJson == null) {
                processed += 1 // poison line — drop, don't wedge the queue
                continue
            }
            val result = client.mirrorEntry(baseUrl, entryJson)
            when {
                result.ok -> processed += 1 // mirrored or deduped server-side
                result.error.orEmpty().startsWith("HTTP 4") -> {
                    // 409 conflict (Syncthing already delivered a diverged copy)
                    // or permanent rejection — dropping beats retry loops.
                    android.util.Log.w(TAG, "mirror dropped ${entryJson.optString("id")}: $result")
                    processed += 1
                }
                else -> {
                    networkFailure = true
                    break
                }
            }
        }

        finishQueue(queueFile, lines.drop(processed))

        return when {
            !networkFailure -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure() // lines stay on disk for the next enqueue
        }
    }

    override suspend fun doWork(): Result = doWorkInternal { null }

    /** Atomically-ish replace queue contents with [remaining] (delete when empty). */
    private fun finishQueue(file: File, remaining: List<String>) = OutboxStore.drain(file, remaining)

    private fun prefs() =
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Single owner for outbox file mutations. The worker drains (read →
     * truncate) while captures append concurrently; unsynchronized access
     * lost whole captures. All operations synchronize on one monitor.
     */
    internal object OutboxStore {
        /** Growth bound for a Mac that's unreachable for a long time; oldest
         * unsynced lines are dropped first (SAF/Syncthing copies remain). */
        const val MAX_LINES = 2_000

        private val lock = Any()

        fun append(file: File, line: String) = synchronized(lock) {
            val existing = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
            val combined = (existing + line.trimEnd())
            val bounded = if (combined.size > MAX_LINES) combined.takeLast(MAX_LINES) else combined
            file.writeText(bounded.joinToString("\n", postfix = "\n"))
        }

        fun readAll(file: File): List<String> = synchronized(lock) {
            if (!file.exists()) emptyList()
            else file.readLines().filter { it.isNotBlank() }
        }

        /** Replace queue contents with [remaining]; delete when empty. */
        fun drain(file: File, remaining: List<String>) = synchronized(lock) {
            if (remaining.isEmpty()) {
                file.delete()
                return
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(remaining.joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(file)) {
                file.writeText(remaining.joinToString("\n", postfix = "\n"))
                tmp.delete()
            }
        }
    }

    companion object {
        private const val TAG = "LanOutbox"
        const val PREFS = "chronicle_prefs"
        const val KEY_OUTBOX_ENABLED = "lan_outbox_enabled"
        const val QUEUE_NAME = "lan_outbox.jsonl"
        private const val UNIQUE_WORK = "chronicle_lan_outbox"
        private const val MAX_ATTEMPTS = 5

        fun queueFile(context: Context): File =
            File(context.filesDir, QUEUE_NAME)

        /**
         * Append one entry (contract-shaped JSON object) to the outbox and
         * schedule delivery. No-op unless outbox mode is enabled AND paired.
         *
         * E2EE gate (v1.11): a plaintext capture made while the vault is
         * enabled-but-locked is NOT enqueued — the PC refuses it with 423 and
         * the worker would drop the line as permanent. Syncthing still
         * delivers the SAF file; once the vault is unlocked, new captures
         * mirror again.
         */
        fun enqueueEntry(context: Context, entryJson: JSONObject): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_OUTBOX_ENABLED, false)) return false
            if (prefs.getString("serve_base_url", "").isNullOrBlank()) return false
            if (com.chronicle.app.e2ee.E2eeManager.enabled.value &&
                !com.chronicle.app.e2ee.E2eeManager.unlocked.value &&
                entryJson.optJSONObject("text_enc") == null
            ) {
                android.util.Log.w(
                    TAG,
                    "vault locked; skipping LAN mirror of plaintext ${entryJson.opt("id")} (Syncthing will deliver)",
                )
                return false
            }
            OutboxStore.append(queueFile(context), JSONObject().put("entry", entryJson).toString() + "\n")
            schedule(context)
            return true
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_OUTBOX_ENABLED, enabled).apply()
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_OUTBOX_ENABLED, false)

        /** Schedule unique work; Wi-Fi-classed networks only (journal privacy). */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<LanOutboxWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
