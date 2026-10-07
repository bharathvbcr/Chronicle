package com.chronicle.app.e2ee

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.chronicle.app.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Enable/unlock run 600k-round PBKDF2 (seconds on a mid-range phone). Settings
 * used to call them straight from onClick, freezing the UI into an ANR.
 *
 * Under Robolectric the test thread IS the main looper thread, so a call that
 * completes from here proves the work was dispatched off it: key derivation
 * refuses to run on the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class E2eeOffMainThreadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = context.getSharedPreferences("offmain_e2ee_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        E2eeManager.prefsProvider = { prefs }
        E2eeManager.disable(context)
    }

    @After
    fun tearDown() {
        E2eeManager.lock()
        E2eeManager.resetPrefsProvider()
    }

    @Test
    fun keyDerivation_refusesTheMainThread() {
        assertTrue("precondition: test runs on the main looper", Looper.myLooper() == Looper.getMainLooper())
        val refused = runCatching {
            E2eeManager.deriveKeyOffMain("passphrase", E2eeCrypto.randomSalt(), E2eeCrypto.DEFAULT_ITERATIONS)
        }.exceptionOrNull()
        assertTrue("PBKDF2 ran on the main thread", refused is IllegalStateException)
    }

    @Test
    fun viewModelEnableAndUnlock_completeWhenCalledFromTheMainThread() = runBlocking<Unit> {
        val vm = MainViewModel()

        assertTrue("enable", vm.e2eeEnable(context, "correct horse battery"))
        assertTrue(E2eeManager.unlocked.value)

        vm.e2eeLock()
        assertFalse(vm.e2eeUnlock(context, "wrong passphrase"))
        assertTrue("unlock", vm.e2eeUnlock(context, "correct horse battery"))
        assertTrue(E2eeManager.unlocked.value)
    }
}
