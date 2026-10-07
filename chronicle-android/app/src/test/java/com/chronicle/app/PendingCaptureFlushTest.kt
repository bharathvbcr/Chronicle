package com.chronicle.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.chronicle.app.e2ee.E2eeCrypto
import com.chronicle.app.e2ee.E2eeManager
import com.chronicle.app.e2ee.PendingCaptureQueue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parked captures must survive a flush that dies part-way. The flush used to
 * drain (persist an empty queue) before writing anything, so a process death
 * or a provider exception between the drain and the final restore lost every
 * capture not yet written — the "never destroy user data" rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingCaptureFlushTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var e2eePrefs: SharedPreferences
    private lateinit var queuePrefs: SharedPreferences

    /** Stands in for the process being killed mid-write: not an Exception, so nothing catches it. */
    private class ProcessDeath : Error("process killed")

    /** A vault that records saves and can fail on cue. */
    private class ScriptedVault(
        val saved: MutableList<Entry> = mutableListOf(),
        private val existsThrowsOnCall: Int = -1,
        private val beforeSave: (Entry) -> Unit = {},
        private val refuse: (Entry) -> Boolean = { false },
    ) : CaptureVault {
        private var existsCalls = 0

        override fun entryExists(id: String): Boolean {
            existsCalls++
            if (existsCalls == existsThrowsOnCall) throw IllegalStateException("SAF provider went away")
            return saved.any { it.id == id }
        }

        override fun save(entry: Entry, imageUris: List<Uri>, audioLocalPaths: List<String>): Entry? {
            beforeSave(entry)
            if (refuse(entry)) return null
            saved.add(entry)
            return entry
        }
    }

    @Before
    fun setUp() {
        e2eePrefs = context.getSharedPreferences("flush_e2ee_prefs", Context.MODE_PRIVATE)
        queuePrefs = context.getSharedPreferences("flush_queue_prefs", Context.MODE_PRIVATE)
        e2eePrefs.edit().clear().commit()
        queuePrefs.edit().clear().commit()
        E2eeManager.prefsProvider = { e2eePrefs }
        PendingCaptureQueue.prefsProvider = { queuePrefs }
        E2eeManager.disable(context) // an unencrypted vault unless a test says otherwise
    }

    @After
    fun tearDown() {
        E2eeManager.lock()
        E2eeManager.resetPrefsProvider()
        PendingCaptureQueue.resetPrefsProvider()
    }

    private fun park(id: String, text: String, images: List<String> = emptyList()) {
        val obj = JSONObject()
            .put("id", id)
            .put("ts", "2026-10-06T10:10:10+00:00")
            .put("type", "log")
            .put("text", text)
            .put("tags", JSONArray())
            .put("images", JSONArray(images))
            .put("audio", JSONArray())
        check(PendingCaptureQueue.enqueue(context, obj))
    }

    private fun queuedTexts() = PendingCaptureQueue.peek(context).map { it.getString("text") }

    @Test
    fun processDeathMidFlush_keepsEveryCaptureNotYetWritten() {
        park("2026-10-06_101010-an", "first")
        park("2026-10-06_101011-an", "second")
        park("2026-10-06_101012-an", "third")
        val vault = ScriptedVault(beforeSave = { if (it.text == "second") throw ProcessDeath() })

        assertThrows(ProcessDeath::class.java) { flushPendingCaptures(context, vault) }

        assertEquals(listOf("first"), vault.saved.map { it.text })
        assertEquals("unwritten captures were lost", listOf("second", "third"), queuedTexts())
    }

    @Test
    fun providerExceptionAfterPeek_keepsEveryUnwrittenCapture() {
        park("2026-10-06_101010-an", "first")
        park("2026-10-06_101011-an", "second")
        park("2026-10-06_101012-an", "third")
        // Call 1 checks "first"'s id; call 2 (for "second") throws.
        val vault = ScriptedVault(existsThrowsOnCall = 2)

        assertThrows(IllegalStateException::class.java) { flushPendingCaptures(context, vault) }

        assertEquals(listOf("first"), vault.saved.map { it.text })
        assertEquals("unwritten captures were lost", listOf("second", "third"), queuedTexts())
    }

    @Test
    fun refusedWrite_staysQueued_andWrittenOnesAreRemoved() {
        park("2026-10-06_101010-an", "first")
        park("2026-10-06_101011-an", "second")
        park("2026-10-06_101012-an", "third")
        val vault = ScriptedVault(refuse = { it.text == "second" })

        val result = flushPendingCaptures(context, vault)

        assertEquals(FlushResult(written = 2, stillQueued = 1), result)
        assertEquals(listOf("first", "third"), vault.saved.map { it.text })
        assertEquals(listOf("second"), queuedTexts())
    }

    /** Parked ids are minted without a vault to check, so two captures in one second share one. */
    @Test
    fun siblingsSharingAnId_onlyTheWrittenOneLeavesTheQueue() {
        park("2026-10-06_101010-an", "written")
        park("2026-10-06_101010-an", "refused")
        val vault = ScriptedVault(refuse = { it.text == "refused" })

        flushPendingCaptures(context, vault)

        assertEquals(listOf("written"), vault.saved.map { it.text })
        assertEquals(listOf("refused"), queuedTexts())
    }

    @Test
    fun captureParkedWhileFlushing_isKept() {
        park("2026-10-06_101010-an", "first")
        val vault = ScriptedVault(
            beforeSave = { if (it.text == "first") park("2026-10-06_101020-an", "parked mid-flush") },
        )

        flushPendingCaptures(context, vault)

        assertEquals(listOf("first"), vault.saved.map { it.text })
        assertEquals(listOf("parked mid-flush"), queuedTexts())
    }

    /**
     * The accepted trade-off: dying after a write but before its removal
     * leaves the capture queued, and the next flush files it again. It lands
     * beside the first copy under an `_N` id — a duplicate, never an overwrite.
     */
    @Test
    fun reflushOfAnAlreadyWrittenCapture_duplicatesAndNeverOverwrites() {
        park("2026-10-06_101010-an", "thought")
        val earlier = Entry(id = "2026-10-06_101010-an", ts = "2026-10-06T10:10:10+00:00", type = "log", text = "thought", tags = emptyList())
        val vault = ScriptedVault(saved = mutableListOf(earlier))

        flushPendingCaptures(context, vault)

        assertEquals(listOf("2026-10-06_101010-an", "2026-10-06_101010-an_2"), vault.saved.map { it.id })
        assertEquals(emptyList<String>(), queuedTexts())
    }

    @Test
    fun lockedVault_writesNothing_andKeepsTheQueue() {
        e2eePrefs.edit()
            .putBoolean("e2ee_enabled", true)
            .putString(E2eeManager.PREF_KDF_SALT, E2eeCrypto.b64(E2eeCrypto.randomSalt()))
            .putInt(E2eeManager.PREF_KDF_ITER, E2eeCrypto.DEFAULT_ITERATIONS)
            .putString(E2eeManager.PREF_CHECK_JSON, JSONObject().put("nonce", "n").put("ct", "c").toString())
            .commit()
        park("2026-10-06_101010-an", "private")
        val vault = ScriptedVault()

        val result = flushPendingCaptures(context, vault)

        assertEquals(FlushResult(written = 0, stillQueued = 1), result)
        assertEquals(emptyList<Entry>(), vault.saved)
        assertEquals(listOf("private"), queuedTexts())
    }

    @Test
    fun stagedMedia_isReleasedOnlyForWrittenCaptures() {
        fun stage(name: String): String {
            val src = java.io.File(context.cacheDir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            return requireNotNull(PendingCaptureQueue.stageMedia(context, Uri.fromFile(src)))
        }
        val writtenImage = stage("w.jpg")
        val refusedImage = stage("r.jpg")
        park("2026-10-06_101010-an", "written", listOf(writtenImage))
        park("2026-10-06_101011-an", "refused", listOf(refusedImage))

        flushPendingCaptures(context, ScriptedVault(refuse = { it.text == "refused" }))

        assertNull(PendingCaptureQueue.stagedUri(context, writtenImage))
        assertNotNull("a queued capture's image was deleted", PendingCaptureQueue.stagedUri(context, refusedImage))
    }
}
