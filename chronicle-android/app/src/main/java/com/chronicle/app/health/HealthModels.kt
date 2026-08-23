package com.chronicle.app.health

import org.json.JSONArray
import org.json.JSONObject

const val HEALTH_SCHEMA_VERSION = 1
const val HEALTH_SOURCE_CONNECT = "health_connect"

data class SleepStage(
    val stage: String,
    val start: String,
    val end: String,
)

data class SleepData(
    val start: String,
    val end: String,
    val durationMin: Int,
    val stages: List<SleepStage> = emptyList(),
)

data class HealthDay(
    val date: String,
    val sleep: SleepData? = null,
    val steps: Int? = null,
    val source: String = HEALTH_SOURCE_CONNECT,
)

data class HealthMonth(
    val version: Int = HEALTH_SCHEMA_VERSION,
    val month: String? = null,
    val days: Map<String, HealthDay> = emptyMap(),
)

fun serializeHealthMonth(month: HealthMonth): String {
    val daysObj = JSONObject()
    month.days.entries.sortedBy { it.key }.forEach { (date, day) ->
        daysObj.put(date, serializeHealthDayObject(day))
    }
    val root = JSONObject()
    root.put("version", month.version)
    if (!month.month.isNullOrBlank()) {
        root.put("month", month.month)
    }
    root.put("days", daysObj)
    return root.toString(2)
}

fun serializeHealthDayObject(day: HealthDay): JSONObject {
    val obj = JSONObject()
    day.sleep?.let { sleep ->
        val sleepObj = JSONObject()
        sleepObj.put("start", sleep.start)
        sleepObj.put("end", sleep.end)
        sleepObj.put("duration_min", sleep.durationMin)
        val stagesArr = JSONArray()
        sleep.stages.forEach { stage ->
            stagesArr.put(
                JSONObject()
                    .put("stage", stage.stage)
                    .put("start", stage.start)
                    .put("end", stage.end),
            )
        }
        sleepObj.put("stages", stagesArr)
        obj.put("sleep", sleepObj)
    }
    day.steps?.let { obj.put("steps", it) }
    obj.put("source", day.source)
    return obj
}

/** Pretty JSON string for a single day (tests / debugging). */
fun serializeHealthDay(day: HealthDay): String = serializeHealthDayObject(day).toString(2)

fun deserializeHealthMonth(jsonStr: String): HealthMonth? {
    return try {
        val obj = JSONObject(jsonStr)
        val version = obj.optInt("version", HEALTH_SCHEMA_VERSION)
        val month = if (obj.has("month") && !obj.isNull("month")) obj.getString("month") else null
        val daysObj = obj.optJSONObject("days") ?: JSONObject()
        val days = mutableMapOf<String, HealthDay>()
        val keys = daysObj.keys()
        while (keys.hasNext()) {
            val date = keys.next()
            val dayObj = daysObj.optJSONObject(date) ?: continue
            deserializeHealthDay(date, dayObj)?.let { days[date] = it }
        }
        HealthMonth(version = version, month = month, days = days)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun deserializeHealthDay(date: String, obj: JSONObject): HealthDay? {
    return try {
        val source = obj.optString("source", "").ifBlank { return null }
        val sleepObj = obj.optJSONObject("sleep")
        val sleep = if (sleepObj != null) {
            val stagesArr = sleepObj.optJSONArray("stages") ?: JSONArray()
            val stages = mutableListOf<SleepStage>()
            for (i in 0 until stagesArr.length()) {
                val s = stagesArr.optJSONObject(i) ?: continue
                stages.add(
                    SleepStage(
                        stage = s.getString("stage"),
                        start = s.getString("start"),
                        end = s.getString("end"),
                    ),
                )
            }
            SleepData(
                start = sleepObj.getString("start"),
                end = sleepObj.getString("end"),
                durationMin = sleepObj.getInt("duration_min"),
                stages = stages,
            )
        } else {
            null
        }
        val steps = if (obj.has("steps") && !obj.isNull("steps")) obj.getInt("steps") else null
        if (sleep == null && steps == null) return null
        HealthDay(date = date, sleep = sleep, steps = steps, source = source)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/** Merge [incoming] into [existing] by date; incoming wins per day. */
fun mergeHealthDays(
    existing: Map<String, HealthDay>,
    incoming: Map<String, HealthDay>,
): Map<String, HealthDay> = existing + incoming

fun formatSleepDuration(durationMin: Int): String {
    val h = durationMin / 60
    val m = durationMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

fun formatHealthChip(day: HealthDay): String? {
    val parts = mutableListOf<String>()
    day.sleep?.let { parts.add("${formatSleepDuration(it.durationMin)} sleep") }
    day.steps?.let { steps ->
        parts.add("%,d steps".format(steps))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Manual validation matching contract/health.schema.json. */
fun validateHealthMonth(month: HealthMonth): List<String> {
    val errors = mutableListOf<String>()
    if (month.version != 1) errors.add("version must be 1")
    month.month?.let { ym ->
        if (!ym.matches(Regex("""^\d{4}-\d{2}$"""))) {
            errors.add("month must be yyyy-MM: $ym")
        }
    }
    month.days.forEach { (date, day) ->
        if (!date.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))) {
            errors.add("invalid date key: $date")
        }
        if (day.source.isBlank()) errors.add("source blank for $date")
        if (day.sleep == null && day.steps == null) {
            errors.add("day $date needs sleep or steps")
        }
        day.steps?.let { if (it < 0) errors.add("steps negative for $date") }
        day.sleep?.let { sleep ->
            if (sleep.start.isBlank() || sleep.end.isBlank()) {
                errors.add("sleep start/end blank for $date")
            }
            if (sleep.durationMin < 0) errors.add("duration_min negative for $date")
        }
    }
    try {
        val obj = JSONObject(serializeHealthMonth(month))
        if (!obj.has("version") || !obj.has("days")) {
            errors.add("serialized JSON missing version/days")
        }
    } catch (e: Exception) {
        errors.add("serialize failed: ${e.message}")
    }
    return errors
}
