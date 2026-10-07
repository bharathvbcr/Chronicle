package com.chronicle.app.widget

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.chronicle.app.CaptureDraft
import com.chronicle.app.e2ee.E2eeManager
import com.chronicle.app.e2ee.PendingCaptureQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A quick capture whose worker exhausted its retries (no SAF grant, provider
 * errors) used to end in Result.failure(), and WorkManager discarded the text
 * with the failed work — silently. It must survive and the user must be told.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickCaptureWorkerFinalFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var e2eePrefs: SharedPreferences
    private lateinit var queuePrefs: SharedPreferences

    @Before
    fun setUp() {
        e2eePrefs = context.getSharedPreferences("ff_e2ee_prefs", Context.MODE_PRIVATE)
        queuePrefs = context.getSharedPreferences("ff_queue_prefs", Context.MODE_PRIVATE)
        e2eePrefs.edit().clear().commit()
        queuePrefs.edit().clear().commit()
        E2eeManager.prefsProvider = { e2eePrefs }
        PendingCaptureQueue.prefsProvider = { queuePrefs }
        E2eeManager.disable(context) // an ordinary, unencrypted vault
    }

    @After
    fun tearDown() {
        E2eeManager.resetPrefsProvider()
        PendingCaptureQueue.resetPrefsProvider()
    }

    @Test
    fun textSurvivesPastMaxRetries_andTheUserIsNotified() {
        val draft = CaptureDraft(text = "remember the dentist", tags = listOf("health"), mood = 4)
        val noVault = { _: Context -> null } // folder grant missing on every attempt

        for (attempt in 0 until QuickCaptureWorker.MAX_RETRIES) {
            assertEquals(
                "attempt $attempt should retry",
                ListenableWorker.Result.retry(),
                QuickCaptureWorker.doWorkInternal(context, draft, attempt, noVault),
            )
            assertEquals("parked before the final attempt", 0, PendingCaptureQueue.count(context))
        }

        val last = QuickCaptureWorker.doWorkInternal(context, draft, QuickCaptureWorker.MAX_RETRIES, noVault)

        // Success ends the work only because the text is now held elsewhere.
        assertEquals(ListenableWorker.Result.success(), last)
        val parked = PendingCaptureQueue.peek(context).single()
        assertEquals("remember the dentist", parked.getString("text"))
        assertEquals("health", parked.getJSONArray("tags").getString(0))
        assertEquals(4, parked.getInt("mood"))

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(nm).getNotification(PendingCaptureQueue.NOTIFICATION_ID)
        assertTrue("user was not notified", posted != null)
    }
}
