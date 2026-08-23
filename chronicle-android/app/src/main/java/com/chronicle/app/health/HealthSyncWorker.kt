package com.chronicle.app.health

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chronicle.app.VaultRepository
import com.chronicle.app.hasPersistedPermission
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Daily WorkManager job: when auto-sync is on, import yesterday's sleep/steps into the vault.
 */
class HealthSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_SYNC, false)) return Result.success()

        val uriStr = prefs.getString("vault_uri", null)
        if (!hasPersistedPermission(applicationContext, uriStr)) return Result.retry()

        val manager = HealthConnectManager(applicationContext)
        if (manager.availability() != HealthConnectAvailability.AVAILABLE) return Result.success()
        if (!manager.hasAllPermissions()) return Result.success()

        return try {
            val yesterday = LocalDate.now().minusDays(1)
            val days = manager.importDays(yesterday, yesterday.plusDays(1))
            if (days.isEmpty()) {
                prefs.edit().putLong(KEY_LAST_IMPORT_MS, System.currentTimeMillis()).apply()
                return Result.success()
            }
            val treeUri = Uri.parse(uriStr)
            val repo = VaultRepository(applicationContext, treeUri)
            val byMonth = days.groupBy { it.date.take(7) }
            var ok = true
            for ((ym, monthDays) in byMonth) {
                if (!repo.saveHealthMonth(ym, monthDays.associateBy { it.date })) {
                    ok = false
                }
            }
            if (ok) {
                prefs.edit().putLong(KEY_LAST_IMPORT_MS, System.currentTimeMillis()).apply()
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    companion object {
        const val PREFS = "chronicle_prefs"
        const val KEY_AUTO_SYNC = "health_auto_sync"
        const val KEY_LAST_IMPORT_MS = "health_last_import_ms"
        private const val UNIQUE_WORK = "chronicle_health_sync_daily"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
        }

        fun setAutoSync(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_SYNC, enabled)
                .apply()
            if (enabled) enqueue(context) else cancel(context)
        }
    }
}
