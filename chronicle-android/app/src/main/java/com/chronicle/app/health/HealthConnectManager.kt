package com.chronicle.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class HealthConnectAvailability {
    AVAILABLE,
    UPDATE_REQUIRED,
    NOT_INSTALLED,
    UNAVAILABLE,
}

/**
 * Thin wrapper around Health Connect for sleep sessions + daily step totals.
 */
class HealthConnectManager(private val context: Context) {

    fun availability(): HealthConnectAvailability {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.UPDATE_REQUIRED
            HealthConnectClient.SDK_UNAVAILABLE -> HealthConnectAvailability.UNAVAILABLE
            else -> HealthConnectAvailability.UNAVAILABLE
        }
    }

    fun getOrCreateClient(): HealthConnectClient? {
        if (availability() != HealthConnectAvailability.AVAILABLE) return null
        return try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    suspend fun hasAllPermissions(): Boolean {
        val client = getOrCreateClient() ?: return false
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(REQUIRED_PERMISSIONS)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Play Store / package installer for Health Connect when the provider app is missing
     * (typically API &lt; 34). On API 34+ Health Connect is a system module.
     */
    fun installOrUpdateIntent(): Intent {
        val pkg = PROVIDER_PACKAGE
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (market.resolveActivity(context.packageManager) != null) {
            market
        } else {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun openHealthConnectSettingsIntent(): Intent {
        return Intent(ACTION_HEALTH_CONNECT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun readSleepSessions(
        start: Instant,
        end: Instant,
    ): List<SleepSessionRecord> {
        val client = getOrCreateClient() ?: return emptyList()
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            response.records
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Per-local-day step totals for [startDate] inclusive through [endDate] exclusive. */
    suspend fun aggregateSteps(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Map<LocalDate, Long> {
        val client = getOrCreateClient() ?: return emptyMap()
        if (!endDate.isAfter(startDate)) return emptyMap()
        return try {
            val startLdt = startDate.atStartOfDay()
            val endLdt = endDate.atStartOfDay()
            val results = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startLdt, endLdt),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            )
            results.mapNotNull { bucket ->
                val count = bucket.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
                bucket.startTime.toLocalDate() to count
            }.toMap()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    /**
     * Import sleep + steps for local dates in [[startDate], [endDate]) into [HealthDay]s.
     * Sleep is attributed to the local calendar date of the session end (wake day).
     */
    suspend fun importDays(
        startDate: LocalDate,
        endDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<HealthDay> {
        if (!endDate.isAfter(startDate)) return emptyList()
        val sleepStart = startDate.minusDays(1).atStartOfDay(zone).toInstant()
        val endInstant = endDate.atStartOfDay(zone).toInstant()

        val sessions = readSleepSessions(sleepStart, endInstant)
        val stepsByDay = aggregateSteps(startDate, endDate)

        val sleepByDate = mutableMapOf<LocalDate, SleepData>()
        for (session in sessions) {
            val endOffset = session.endZoneOffset ?: zone.rules.getOffset(session.endTime)
            val wakeDate = session.endTime.atOffset(endOffset).toLocalDate()
            if (wakeDate.isBefore(startDate) || !wakeDate.isBefore(endDate)) continue
            val mapped = toSleepData(session, zone)
            val existing = sleepByDate[wakeDate]
            // Prefer the longest session if multiple wake on the same day.
            if (existing == null || mapped.durationMin > existing.durationMin) {
                sleepByDate[wakeDate] = mapped
            }
        }

        val dates = mutableSetOf<LocalDate>()
        dates.addAll(sleepByDate.keys)
        dates.addAll(stepsByDay.keys)
        return dates.sorted().mapNotNull { date ->
            val sleep = sleepByDate[date]
            val steps = stepsByDay[date]?.toInt()
            if (sleep == null && steps == null) return@mapNotNull null
            HealthDay(
                date = date.toString(),
                sleep = sleep,
                steps = steps,
                source = HEALTH_SOURCE_CONNECT,
            )
        }
    }

    companion object {
        private const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val ACTION_HEALTH_CONNECT_SETTINGS =
            "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"

        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )

        fun playStoreNeededForInstall(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        private fun toSleepData(session: SleepSessionRecord, zone: ZoneId): SleepData {
            val startOffset = session.startZoneOffset ?: zone.rules.getOffset(session.startTime)
            val endOffset = session.endZoneOffset ?: zone.rules.getOffset(session.endTime)
            val durationMin = Duration.between(session.startTime, session.endTime).toMinutes()
                .coerceAtLeast(0)
                .toInt()
            val stages = session.stages.map { stage ->
                SleepStage(
                    stage = stageName(stage.stage),
                    start = formatInstant(stage.startTime, startOffset),
                    end = formatInstant(stage.endTime, endOffset),
                )
            }
            return SleepData(
                start = formatInstant(session.startTime, startOffset),
                end = formatInstant(session.endTime, endOffset),
                durationMin = durationMin,
                stages = stages,
            )
        }

        private fun formatInstant(instant: Instant, offset: ZoneOffset): String =
            instant.atOffset(offset).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        /** Public STAGE_TYPE_* constants only — avoids RestrictedApi on STAGE_TYPE_INT_TO_STRING_MAP. */
        private fun stageName(stageType: Int): String = when (stageType) {
            SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
            SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
            SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
            SleepSessionRecord.STAGE_TYPE_REM -> "rem"
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
            else -> "unknown"
        }
    }
}
