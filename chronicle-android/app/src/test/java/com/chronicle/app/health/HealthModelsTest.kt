package com.chronicle.app.health

import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates HealthModels JSON round-trip against contract/health.schema.json rules.
 * Schema file is on the unit-test resources path via app/build.gradle.kts.
 */
class HealthModelsTest {

    @Test
    fun contractSchema_isOnTestClasspath() {
        val stream = javaClass.classLoader!!.getResourceAsStream("health.schema.json")
        assertNotNull("health.schema.json should be on test classpath", stream)
        val text = stream!!.bufferedReader().use(BufferedReader::readText)
        val schema = JSONObject(text)
        assertEquals("Chronicle Health Month v1", schema.getString("title"))
        assertTrue(schema.getJSONArray("required").toString().contains("version"))
        assertTrue(schema.getJSONArray("required").toString().contains("days"))
        val versionConst = schema
            .getJSONObject("properties")
            .getJSONObject("version")
            .getInt("const")
        assertEquals(1, versionConst)
    }

    @Test
    fun serializeDeserialize_roundTrip_fullDay() {
        val day = HealthDay(
            date = "2026-07-09",
            sleep = SleepData(
                start = "2026-07-08T23:10:00-07:00",
                end = "2026-07-09T06:50:00-07:00",
                durationMin = 460,
                stages = listOf(
                    SleepStage(
                        stage = "deep",
                        start = "2026-07-09T01:00:00-07:00",
                        end = "2026-07-09T02:00:00-07:00",
                    ),
                    SleepStage(
                        stage = "rem",
                        start = "2026-07-09T02:00:00-07:00",
                        end = "2026-07-09T02:45:00-07:00",
                    ),
                ),
            ),
            steps = 8432,
            source = HEALTH_SOURCE_CONNECT,
        )
        val month = HealthMonth(
            version = HEALTH_SCHEMA_VERSION,
            month = "2026-07",
            days = mapOf(day.date to day),
        )

        val json = serializeHealthMonth(month)
        val errors = validateHealthMonth(month)
        assertTrue("Expected no schema errors, got $errors", errors.isEmpty())

        val root = JSONObject(json)
        assertEquals(1, root.getInt("version"))
        assertEquals("2026-07", root.getString("month"))
        assertTrue(root.has("days"))
        val dayObj = root.getJSONObject("days").getJSONObject("2026-07-09")
        assertEquals(HEALTH_SOURCE_CONNECT, dayObj.getString("source"))
        assertEquals(8432, dayObj.getInt("steps"))
        assertEquals(460, dayObj.getJSONObject("sleep").getInt("duration_min"))
        assertEquals(2, dayObj.getJSONObject("sleep").getJSONArray("stages").length())

        val restored = deserializeHealthMonth(json)
        assertNotNull(restored)
        assertEquals(1, restored!!.version)
        assertEquals("2026-07", restored.month)
        assertEquals(1, restored.days.size)
        val restoredDay = restored.days["2026-07-09"]!!
        assertEquals(8432, restoredDay.steps)
        assertEquals(460, restoredDay.sleep!!.durationMin)
        assertEquals(2, restoredDay.sleep!!.stages.size)
        assertEquals("deep", restoredDay.sleep!!.stages[0].stage)
        assertEquals(HEALTH_SOURCE_CONNECT, restoredDay.source)
        assertTrue(validateHealthMonth(restored).isEmpty())
    }

    @Test
    fun serializeDeserialize_stepsOnlyAndSleepOnly() {
        val stepsOnly = HealthDay(date = "2026-07-01", steps = 1200, source = HEALTH_SOURCE_CONNECT)
        val sleepOnly = HealthDay(
            date = "2026-07-02",
            sleep = SleepData(
                start = "2026-07-01T22:00:00-07:00",
                end = "2026-07-02T06:00:00-07:00",
                durationMin = 480,
                stages = emptyList(),
            ),
            source = HEALTH_SOURCE_CONNECT,
        )
        val month = HealthMonth(
            month = "2026-07",
            days = mapOf(
                stepsOnly.date to stepsOnly,
                sleepOnly.date to sleepOnly,
            ),
        )
        assertTrue(validateHealthMonth(month).isEmpty())

        val restored = deserializeHealthMonth(serializeHealthMonth(month))!!
        assertEquals(1200, restored.days["2026-07-01"]!!.steps)
        assertNull(restored.days["2026-07-01"]!!.sleep)
        assertEquals(480, restored.days["2026-07-02"]!!.sleep!!.durationMin)
        assertNull(restored.days["2026-07-02"]!!.steps)
    }

    @Test
    fun validateHealthMonth_rejectsBadKeysAndEmptyDay() {
        val bad = HealthMonth(
            version = 2,
            month = "2026/07",
            days = mapOf(
                "07-09" to HealthDay(date = "07-09", steps = 1, source = ""),
                "2026-07-10" to HealthDay(date = "2026-07-10", source = HEALTH_SOURCE_CONNECT),
            ),
        )
        val errors = validateHealthMonth(bad)
        assertTrue(errors.any { it.contains("version") })
        assertTrue(errors.any { it.contains("month") })
        assertTrue(errors.any { it.contains("invalid date key") })
        assertTrue(errors.any { it.contains("source blank") })
        assertTrue(errors.any { it.contains("needs sleep or steps") })
    }

    @Test
    fun mergeHealthDays_incomingWinsPerDate() {
        val existing = mapOf(
            "2026-07-01" to HealthDay(date = "2026-07-01", steps = 100, source = HEALTH_SOURCE_CONNECT),
            "2026-07-02" to HealthDay(date = "2026-07-02", steps = 200, source = HEALTH_SOURCE_CONNECT),
        )
        val incoming = mapOf(
            "2026-07-02" to HealthDay(date = "2026-07-02", steps = 999, source = HEALTH_SOURCE_CONNECT),
            "2026-07-03" to HealthDay(date = "2026-07-03", steps = 300, source = HEALTH_SOURCE_CONNECT),
        )
        val merged = mergeHealthDays(existing, incoming)
        assertEquals(100, merged["2026-07-01"]!!.steps)
        assertEquals(999, merged["2026-07-02"]!!.steps)
        assertEquals(300, merged["2026-07-03"]!!.steps)
        assertEquals(3, merged.size)
    }

    @Test
    fun formatHealthChip_andSleepDuration() {
        assertEquals("7h 40m", formatSleepDuration(460))
        assertEquals("8h", formatSleepDuration(480))
        assertEquals("45m", formatSleepDuration(45))

        val both = HealthDay(
            date = "2026-07-09",
            sleep = SleepData(
                start = "a",
                end = "b",
                durationMin = 460,
            ),
            steps = 8432,
            source = HEALTH_SOURCE_CONNECT,
        )
        assertEquals("7h 40m sleep · 8,432 steps", formatHealthChip(both))

        val empty = HealthDay(date = "2026-07-09", source = HEALTH_SOURCE_CONNECT)
        assertNull(formatHealthChip(empty))
    }

    @Test
    fun deserialize_skipsDayWithoutSleepOrSteps() {
        val json = """
            {
              "version": 1,
              "month": "2026-07",
              "days": {
                "2026-07-09": { "source": "health_connect" },
                "2026-07-10": { "source": "health_connect", "steps": 50 }
              }
            }
        """.trimIndent()
        val month = deserializeHealthMonth(json)!!
        assertFalse(month.days.containsKey("2026-07-09"))
        assertEquals(50, month.days["2026-07-10"]!!.steps)
    }

    @Test
    fun serializedJson_matchesSchemaRequiredShape() {
        val month = HealthMonth(
            month = "2026-07",
            days = mapOf(
                "2026-07-09" to HealthDay(
                    date = "2026-07-09",
                    sleep = SleepData(
                        start = "2026-07-08T23:00:00-07:00",
                        end = "2026-07-09T07:00:00-07:00",
                        durationMin = 480,
                        stages = listOf(
                            SleepStage("light", "2026-07-08T23:00:00-07:00", "2026-07-09T01:00:00-07:00"),
                        ),
                    ),
                    steps = 0,
                    source = HEALTH_SOURCE_CONNECT,
                ),
            ),
        )
        val obj = JSONObject(serializeHealthMonth(month))
        // Mirror health.schema.json: required root keys, no extras at day level beyond sleep/steps/source
        assertTrue(obj.has("version"))
        assertTrue(obj.has("days"))
        val dayKeys = obj.getJSONObject("days").getJSONObject("2026-07-09").keys().asSequence().toSet()
        assertTrue(dayKeys.contains("source"))
        assertTrue(dayKeys.all { it in setOf("sleep", "steps", "source") })
        val sleepKeys = obj.getJSONObject("days")
            .getJSONObject("2026-07-09")
            .getJSONObject("sleep")
            .keys()
            .asSequence()
            .toSet()
        assertEquals(setOf("start", "end", "duration_min", "stages"), sleepKeys)
    }
}
