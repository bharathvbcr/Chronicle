package com.chronicle.app.e2ee

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.chronicle.app.CaptureDraft
import com.chronicle.app.CaptureOutcome
import com.chronicle.app.CaptureVault
import com.chronicle.app.Entry
import com.chronicle.app.net.LanOutboxWorker
import com.chronicle.app.widget.QuickCaptureWorker
import com.chronicle.app.writeCapture
import kotlinx.coroutines.runBlocking
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
 * Cold-process fail-open regression: the share sheet, the quick-capture worker
 * and the LAN outbox can run in a process where MainActivity never started, so
 * nothing has loaded E2EE state into memory. Every capture path must read the
 * persisted configuration instead of trusting the in-memory default ("off"),
 * or the capture is written and mirrored in the clear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColdStartCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var e2eePrefs: SharedPreferences
    private lateinit var queuePrefs: SharedPreferences

    private fun appPrefs(): SharedPreferences =
        context.getSharedPreferences(LanOutboxWorker.PREFS, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        e2eePrefs = context.getSharedPreferences("cold_e2ee_prefs", Context.MODE_PRIVATE)
        queuePrefs = context.getSharedPreferences("cold_queue_prefs", Context.MODE_PRIVATE)
        e2eePrefs.edit().clear().commit()
        queuePrefs.edit().clear().commit()
        E2eeManager.prefsProvider = { e2eePrefs }
        PendingCaptureQueue.prefsProvider = { queuePrefs }

        // A fresh process: no key in memory and the in-memory flag at its default.
        E2eeManager.disable(context)
        // ...in a vault an earlier session configured for E2EE.
        e2eePrefs.edit()
            .putBoolean("e2ee_enabled", true)
            .putString(E2eeManager.PREF_KDF_SALT, E2eeCrypto.b64(E2eeCrypto.randomSalt()))
            .putInt(E2eeManager.PREF_KDF_ITER, E2eeCrypto.DEFAULT_ITERATIONS)
            .putString(E2eeManager.PREF_CHECK_JSON, JSONObject().put("nonce", "n").put("ct", "c").toString())
            .commit()

        appPrefs().edit()
            .clear()
            .putBoolean(LanOutboxWorker.KEY_OUTBOX_ENABLED, true)
            .putString("serve_base_url", "https://192.168.1.20:8765")
            .commit()
        LanOutboxWorker.queueFile(context).delete()
    }

    @After
    fun tearDown() {
        LanOutboxWorker.queueFile(context).delete()
        appPrefs().edit().clear().commit()
        E2eeManager.lock()
        E2eeManager.resetPrefsProvider()
        PendingCaptureQueue.resetPrefsProvider()
    }

    @Test
    fun lanOutbox_neverQueuesPlaintext_inAColdProcessOfAnEncryptedVault() {
        assertFalse("precondition: nothing loaded E2EE state", E2eeManager.enabled.value)
        val plaintext = JSONObject()
            .put("id", "2026-10-06_101010-an")
            .put("ts", "2026-10-06T10:10:10+00:00")
            .put("type", "log")
            .put("text", "a private thought")

        // WorkManager is not initialised under Robolectric; scheduling is not
        // what is under test, the queue file written before it is.
        val queued = runCatching { LanOutboxWorker.enqueueEntry(context, plaintext) }.getOrDefault(true)

        assertFalse("plaintext capture accepted for LAN mirroring", queued)
        assertFalse(
            "plaintext capture written to the LAN outbox",
            LanOutboxWorker.queueFile(context).exists(),
        )
    }

    /** Records what would reach the vault. */
    private class RecordingVault : CaptureVault {
        val saved = mutableListOf<Entry>()
        override fun entryExists(id: String) = saved.any { it.id == id }
        override fun save(entry: Entry, imageUris: List<Uri>, audioLocalPaths: List<String>): Entry =
            entry.also { saved.add(it) }
    }

    private fun assertNoPlaintext(vault: RecordingVault) {
        vault.saved.forEach {
            assertTrue("plaintext written to the vault: ${it.text}", it.text.isEmpty())
            assertNotNull("entry written without text_enc", it.textEnc)
        }
    }

    /** The share sheet's save path ([ShareActivity] → [writeCapture]). */
    @Test
    fun sharePath_parksInsteadOfWritingPlaintext_inAColdProcess() {
        val vault = RecordingVault()

        val outcome = writeCapture(context, vault, CaptureDraft(text = "a private thought"))

        assertEquals(CaptureOutcome.Parked, outcome)
        assertEquals("nothing may reach the vault while locked", emptyList<Entry>(), vault.saved)
        assertEquals("a private thought", PendingCaptureQueue.peek(context).single().getString("text"))
        assertFalse(LanOutboxWorker.queueFile(context).exists())
    }

    /** The quick-capture worker's attempt, with the vault reachable. */
    @Test
    fun workerPath_parksInsteadOfWritingPlaintext_inAColdProcess() {
        val vault = RecordingVault()

        val result = QuickCaptureWorker.doWorkInternal(
            context,
            CaptureDraft(text = "a private thought"),
            attempt = 0,
            openVault = { vault },
        )

        assertEquals(ListenableWorker.Result.success(), result)
        assertNoPlaintext(vault)
        assertEquals(emptyList<Entry>(), vault.saved)
        assertEquals(1, PendingCaptureQueue.count(context))
    }

    /** Unlocked: the same paths seal instead of parking. */
    @Test
    fun unlockedVault_sealsTheCapture() = runBlocking<Unit> {
        e2eePrefs.edit().clear().commit()
        assertNotNull(E2eeManager.enable(context, "correct horse battery"))
        val vault = RecordingVault()

        val outcome = writeCapture(context, vault, CaptureDraft(text = "a private thought"))

        assertTrue(outcome is CaptureOutcome.Saved)
        assertEquals(1, vault.saved.size)
        assertNoPlaintext(vault)
        assertEquals("a private thought", E2eeManager.openText(vault.saved.single().textEnc))
        assertEquals(0, PendingCaptureQueue.count(context))
    }

    /** E2EE off: captures are written as-is (the gate must not over-refuse). */
    @Test
    fun unencryptedVault_writesTheCapture() {
        E2eeManager.disable(context)
        val vault = RecordingVault()

        val outcome = writeCapture(context, vault, CaptureDraft(text = "a normal note"))

        assertTrue(outcome is CaptureOutcome.Saved)
        assertEquals("a normal note", vault.saved.single().text)
        assertNull(vault.saved.single().textEnc)
    }

    @Test
    fun quickCaptureEnqueue_parksTheCapture_insteadOfHandingPlaintextToWorkManager() {
        assertFalse("precondition: nothing loaded E2EE state", E2eeManager.enabled.value)

        runCatching { QuickCaptureWorker.enqueue(context, "a private thought") }

        assertEquals(
            "locked capture was not parked (it went to WorkManager in the clear)",
            1,
            PendingCaptureQueue.count(context),
        )
        assertEquals("a private thought", PendingCaptureQueue.peek(context).single().getString("text"))
    }
}
