package com.chronicle.app.widget

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chronicle.app.CaptureDraft
import com.chronicle.app.CaptureOutcome
import com.chronicle.app.CaptureVault
import com.chronicle.app.VaultRepository
import com.chronicle.app.asCaptureVault
import com.chronicle.app.e2ee.E2eeManager
import com.chronicle.app.e2ee.PendingCaptureQueue
import com.chronicle.app.generateEntryId
import com.chronicle.app.hasPersistedPermission
import com.chronicle.app.writeCapture
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class QuickCaptureWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = doWorkInternal(
        applicationContext,
        CaptureDraft(
            text = inputData.getString(KEY_TEXT)?.trim().orEmpty(),
            type = inputData.getString(KEY_TYPE) ?: "log",
            tags = inputData.getStringArray(KEY_TAGS)?.toList().orEmpty(),
            mood = inputData.getInt(KEY_MOOD, 0).takeIf { it in 1..5 },
        ),
        runAttemptCount,
    )

    companion object {
        const val KEY_TEXT = "text"
        const val KEY_TYPE = "type"
        const val KEY_TAGS = "tags"
        const val KEY_MOOD = "mood"
        private const val UNIQUE_PREFIX = "chronicle_quick_capture_"
        internal const val MAX_RETRIES = 5

        /**
         * One attempt at writing [draft]. [openVault] is the test seam; it
         * returns null when the folder grant is missing.
         *
         * Transient failures retry. After [MAX_RETRIES] the text is moved to
         * [PendingCaptureQueue] (filed the next time the app has the vault
         * open) and the user is notified — WorkManager would otherwise drop
         * the input with the failed work. If even parking fails, keep
         * retrying: the input stays in WorkManager's database, so nothing is
         * lost.
         */
        internal fun doWorkInternal(
            context: Context,
            draft: CaptureDraft,
            attempt: Int,
            openVault: (Context) -> CaptureVault? = ::savedVault,
        ): Result {
            if (draft.text.isEmpty()) return Result.failure() // nothing to lose
            val outcome = try {
                writeCapture(context, openVault(context), draft)
            } catch (e: Exception) {
                e.printStackTrace()
                CaptureOutcome.Failed
            }
            return when (outcome) {
                is CaptureOutcome.Saved, CaptureOutcome.Parked -> Result.success()
                CaptureOutcome.Failed ->
                    if (attempt < MAX_RETRIES) Result.retry() else parkAfterFinalFailure(context, draft)
            }
        }

        private fun savedVault(context: Context): CaptureVault? {
            val uriStr = context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
                .getString("vault_uri", null)
            if (!hasPersistedPermission(context, uriStr)) return null
            return VaultRepository(context, Uri.parse(uriStr)).asCaptureVault()
        }

        private fun parkAfterFinalFailure(context: Context, draft: CaptureDraft): Result {
            val parked = try {
                park(context, draft)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            runCatching { PendingCaptureQueue.notifyPending(context) }
                .onFailure { it.printStackTrace() }
            return if (parked) Result.success() else Result.retry()
        }

        private fun park(context: Context, draft: CaptureDraft): Boolean {
            val now = ZonedDateTime.now()
            return PendingCaptureQueue.park(
                context,
                id = generateEntryId(now),
                ts = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                type = draft.type,
                text = draft.text,
                tags = draft.tags,
                mood = draft.mood,
            )
        }

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
            val appContext = context.applicationContext
            val draft = CaptureDraft(text = trimmed, type = type, tags = tags, mood = mood)
            // WorkManager input is persisted to its own database. For an
            // encrypted vault that is a second plaintext copy outside the
            // vault, written before the worker ever gets a chance to seal —
            // so a locked capture is parked now and skips the worker entirely.
            // Persisted state, not the in-memory flag: see E2eeManager.isEnabled.
            val locked = try {
                E2eeManager.isEnabled(appContext) && !E2eeManager.unlocked.value
            } catch (e: Exception) {
                e.printStackTrace()
                true // cannot tell: do not hand plaintext to WorkManager
            }
            if (locked) {
                val parked = try {
                    park(appContext, draft)
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
                if (parked) return
                // Queue full or unreadable: dropping the text is worse than an
                // app-private WorkManager copy. The worker parks or retries it
                // until the vault is unlocked; tell the user it is waiting.
                runCatching { PendingCaptureQueue.notifyPending(appContext) }
            }
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
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                UNIQUE_PREFIX + System.currentTimeMillis(),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
