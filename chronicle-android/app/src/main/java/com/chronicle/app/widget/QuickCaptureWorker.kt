package com.chronicle.app.widget

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chronicle.app.Entry
import com.chronicle.app.VaultRepository
import com.chronicle.app.generateEntryId
import com.chronicle.app.hasPersistedPermission
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class QuickCaptureWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val text = inputData.getString(KEY_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return Result.failure()

        val prefs = applicationContext.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString("vault_uri", null)
        if (!hasPersistedPermission(applicationContext, uriStr)) return retryOrFail()

        return try {
            val treeUri = Uri.parse(uriStr)
            val repo = VaultRepository(applicationContext, treeUri)
            val now = ZonedDateTime.now()
            val id = generateEntryId(now, exists = { repo.entryFileExists(it) })
            // Seal when e2ee is on + unlocked; locked sessions save plaintext
            // ("capture always wins") — serializeEntry keeps the invariant.
            val manager = com.chronicle.app.e2ee.E2eeManager
            val sealedBlob =
                if (manager.enabled.value) manager.sealText(text) else null
            val entry = Entry(
                id = id,
                ts = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                type = inputData.getString(KEY_TYPE) ?: "log",
                text = if (sealedBlob != null) "" else text,
                textEnc = sealedBlob,
                tags = inputData.getStringArray(KEY_TAGS)?.toList().orEmpty(),
                mood = inputData.getInt(KEY_MOOD, 0).takeIf { it in 1..5 },
                processed = false,
            )
            val saved = repo.saveEntry(entry)
            if (saved != null) {
                com.chronicle.app.net.LanOutboxWorker.enqueueEntry(
                    applicationContext,
                    org.json.JSONObject(com.chronicle.app.serializeEntry(saved)),
                )
                Result.success()
            } else {
                retryOrFail()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            retryOrFail()
        }
    }

    /** Retry transient failures so captured text is not discarded; give up after [MAX_RETRIES]. */
    private fun retryOrFail(): Result =
        if (runAttemptCount > MAX_RETRIES) Result.failure() else Result.retry()

    companion object {
        const val KEY_TEXT = "text"
        const val KEY_TYPE = "type"
        const val KEY_TAGS = "tags"
        const val KEY_MOOD = "mood"
        private const val UNIQUE_PREFIX = "chronicle_quick_capture_"
        private const val MAX_RETRIES = 5

        /** Durable save that survives process death (widget / quick-capture path). */
        fun enqueue(
            context: Context,
            text: String,
            type: String = "log",
            tags: List<String> = emptyList(),
            mood: Int? = null,
        ) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return
            val request = OneTimeWorkRequestBuilder<QuickCaptureWorker>()
                .setInputData(
                    workDataOf(
                        KEY_TEXT to trimmed,
                        KEY_TYPE to type,
                        KEY_TAGS to tags.toTypedArray(),
                        KEY_MOOD to (mood ?: 0),
                    ),
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_PREFIX + System.currentTimeMillis(),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
