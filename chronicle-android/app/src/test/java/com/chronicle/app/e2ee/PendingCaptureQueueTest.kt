package com.chronicle.app.e2ee

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-09 audit, finding 3: a capture taken while an E2EE vault was locked was
 * written to the vault as PLAINTEXT and synced to every peer. It is now parked
 * here — sealed at rest by SecurePrefs, outside the vault — until unlock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingCaptureQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        // AndroidKeyStore is unavailable on the JVM — inject plain prefs via
        // the same seam E2eeManager uses.
        prefs = context.getSharedPreferences("pending_test_prefs", Context.MODE_PRIVATE)
        PendingCaptureQueue.prefsProvider = { prefs }
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        PendingCaptureQueue.resetPrefsProvider()
    }

    private fun capture(id: String, text: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("ts", "2026-08-01T10:10:10+00:00")
            .put("type", "log")
            .put("text", text)
            .put("tags", JSONArray(listOf("work")))
            .put("images", JSONArray())
            .put("audio", JSONArray())

    @Test
    fun enqueue_then_peek_round_trips_in_order() {
        assertTrue(PendingCaptureQueue.enqueue(context, capture("a", "first secret")))
        assertTrue(PendingCaptureQueue.enqueue(context, capture("b", "second secret")))
        assertEquals(2, PendingCaptureQueue.count(context))

        val peeked = PendingCaptureQueue.peek(context)
        assertEquals(listOf("a", "b"), peeked.map { it.optString("id") })
        assertEquals("first secret", peeked[0].optString("text"))
        // Peeking removes nothing — the queue is the only copy until written.
        assertEquals(2, PendingCaptureQueue.count(context))
    }

    @Test
    fun remove_drops_exactly_the_written_capture() {
        PendingCaptureQueue.enqueue(context, capture("a", "first"))
        PendingCaptureQueue.enqueue(context, capture("b", "second"))
        val (a, _) = PendingCaptureQueue.peek(context)

        assertTrue(PendingCaptureQueue.remove(context, a))

        assertEquals(listOf("b"), PendingCaptureQueue.peek(context).map { it.optString("id") })
    }

    @Test
    fun remove_matches_the_whole_record_not_just_a_shared_id() {
        PendingCaptureQueue.enqueue(context, capture("same", "written"))
        PendingCaptureQueue.enqueue(context, capture("same", "not written"))
        val written = PendingCaptureQueue.peek(context).first()

        assertTrue(PendingCaptureQueue.remove(context, written))

        assertEquals(listOf("not written"), PendingCaptureQueue.peek(context).map { it.optString("text") })
    }

    @Test
    fun remove_takes_one_of_two_identical_records() {
        PendingCaptureQueue.enqueue(context, capture("dup", "same"))
        PendingCaptureQueue.enqueue(context, capture("dup", "same"))

        assertTrue(PendingCaptureQueue.remove(context, PendingCaptureQueue.peek(context).first()))

        assertEquals(1, PendingCaptureQueue.count(context))
    }

    @Test
    fun remove_keeps_captures_parked_after_the_peek_and_preserves_order() {
        PendingCaptureQueue.enqueue(context, capture("old", "older"))
        PendingCaptureQueue.enqueue(context, capture("mid", "middle"))
        val seen = PendingCaptureQueue.peek(context)
        PendingCaptureQueue.enqueue(context, capture("new", "newer"))

        assertTrue(PendingCaptureQueue.remove(context, seen[1]))

        assertEquals(
            "older captures stay ahead of newer ones",
            listOf("old", "new"),
            PendingCaptureQueue.peek(context).map { it.optString("id") },
        )
    }

    @Test
    fun remove_of_an_absent_record_reports_false_and_changes_nothing() {
        PendingCaptureQueue.enqueue(context, capture("a", "kept"))

        assertFalse(PendingCaptureQueue.remove(context, capture("zzz", "never parked")))

        assertEquals(listOf("kept"), PendingCaptureQueue.peek(context).map { it.optString("text") })
    }

    @Test
    fun queue_is_bounded_and_refuses_rather_than_growing_without_limit() {
        repeat(PendingCaptureQueue.MAX_PENDING) { i ->
            assertTrue(PendingCaptureQueue.enqueue(context, capture("id$i", "t$i")))
        }
        assertEquals(PendingCaptureQueue.MAX_PENDING, PendingCaptureQueue.count(context))
        // Refused, not silently dropped — the caller tells the user to unlock.
        assertFalse(PendingCaptureQueue.enqueue(context, capture("overflow", "x")))
        assertEquals(PendingCaptureQueue.MAX_PENDING, PendingCaptureQueue.count(context))
    }

    @Test
    fun oversized_payload_is_refused() {
        val huge = "x".repeat(PendingCaptureQueue.MAX_TOTAL_CHARS + 10)
        assertFalse(PendingCaptureQueue.enqueue(context, capture("big", huge)))
        assertEquals(0, PendingCaptureQueue.count(context))
    }

    @Test
    fun corrupt_queue_does_not_wedge_capture() {
        prefs.edit().putString(PendingCaptureQueue.PREF_KEY, "{not a json array").commit()
        assertEquals(0, PendingCaptureQueue.count(context))
        // Capture still works after corruption.
        assertTrue(PendingCaptureQueue.enqueue(context, capture("a", "still works")))
        assertEquals(1, PendingCaptureQueue.count(context))
    }

    @Test
    fun clear_empties_the_queue() {
        PendingCaptureQueue.enqueue(context, capture("a", "secret"))
        PendingCaptureQueue.clear(context)
        assertEquals(0, PendingCaptureQueue.count(context))
    }

    @Test
    fun staged_media_round_trips_and_is_removable() {
        val src = java.io.File(context.cacheDir, "src.jpg")
        src.writeBytes(byteArrayOf(1, 2, 3, 4))
        val name = PendingCaptureQueue.stageMedia(context, android.net.Uri.fromFile(src))
        assertNotNull("staging should copy the bytes into app storage", name)

        val staged = PendingCaptureQueue.stagedUri(context, name!!)
        assertNotNull(staged)
        assertEquals(4, java.io.File(staged!!.path!!).length())

        PendingCaptureQueue.clearStaged(context, listOf(name))
        assertNull("staged file should be gone after flush", PendingCaptureQueue.stagedUri(context, name))
    }

    /** The queue must hold the text so nothing is lost — the protection is that
     *  it lives in SecurePrefs, outside the synced vault, not in the vault. */
    @Test
    fun queued_text_is_preserved_for_the_later_reseal() {
        val secret = "the combination is seven four two"
        PendingCaptureQueue.enqueue(context, capture("a", secret))
        assertEquals(secret, PendingCaptureQueue.peek(context).single().optString("text"))
    }
}
