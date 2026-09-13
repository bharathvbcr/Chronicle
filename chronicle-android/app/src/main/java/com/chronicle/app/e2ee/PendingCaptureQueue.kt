package com.chronicle.app.e2ee

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.chronicle.app.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Captures taken while an E2EE vault is locked.
 *
 * Capture must never be refused mid-thought ("capture always wins"), but a
 * locked session has no vault key to seal with — so the old behaviour wrote the
 * text to the vault as PLAINTEXT, next to the user's ciphertext, and Syncthing
 * copied it to every peer. That silently defeated the protection the user opted
 * into, and the UI never said so.
 *
 * Instead the capture is parked here until the vault is unlocked. Storage is
 * [SecurePrefs] — EncryptedSharedPreferences under an AndroidKeyStore master
 * key — so the queue is sealed at rest with a device-bound key that needs no
 * passphrase, lives outside the synced vault, and is excluded from backups by
 * the app's data-extraction rules. On unlock the entries are re-sealed with the
 * vault key and written normally.
 *
 * The queue is bounded: it only grows while locked, and an unbounded encrypted
 * blob in SharedPreferences is its own problem.
 */
object PendingCaptureQueue {
    const val PREF_KEY = "pending_captures_v1"

    /** Plenty for a locked session; refuses beyond this rather than growing without limit. */
    const val MAX_PENDING = 500

    /** Guards against a single pathological paste filling the prefs file. */
    const val MAX_TOTAL_CHARS = 2_000_000

    private const val TAG = "PendingCaptureQueue"

    /**
     * Park a capture. Returns false when the queue is full, so the caller can
     * tell the user to unlock rather than silently dropping their text.
     */
    fun enqueue(context: Context, entry: JSONObject): Boolean {
        val current = read(context)
        if (current.length() >= MAX_PENDING) {
            Log.w(TAG, "pending capture queue full ($MAX_PENDING); refusing")
            return false
        }
        current.put(entry)
        val serialized = current.toString()
        if (serialized.length > MAX_TOTAL_CHARS) {
            Log.w(TAG, "pending capture queue byte cap reached; refusing")
            return false
        }
        return write(context, serialized)
    }

    fun count(context: Context): Int = read(context).length()

    /**
     * Remove and return everything parked. The caller is responsible for
     * writing them to the vault; on failure it should [restore] the remainder
     * so nothing is lost.
     */
    fun drain(context: Context): List<JSONObject> {
        val arr = read(context)
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(it) }
        }
        write(context, JSONArray().toString())
        return out
    }

    /** Put back entries that could not be written to the vault. */
    fun restore(context: Context, entries: List<JSONObject>) {
        if (entries.isEmpty()) return
        val arr = read(context)
        // Restored items go first: they are older than anything queued since.
        val merged = JSONArray()
        entries.forEach { merged.put(it) }
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { merged.put(it) }
        }
        write(context, merged.toString())
    }

    fun clear(context: Context) {
        write(context, JSONArray().toString())
    }

    // -- media staging --------------------------------------------------------
    //
    // A picked image arrives as a content:// URI whose read grant may be gone
    // by the time the vault is unlocked, so copy the bytes into app-private
    // storage now and reference that instead. Audio is already an app-private
    // file path and needs no staging.

    private fun stagingDir(context: Context): java.io.File =
        java.io.File(context.filesDir, "pending_media").apply { mkdirs() }

    /** Copy [src] into app-private storage; returns the staged file name. */
    fun stageMedia(context: Context, src: android.net.Uri): String? = try {
        val name = "pending_" + System.nanoTime() + "_" + (0..9999).random() + ".bin"
        val dest = java.io.File(stagingDir(context), name)
        context.contentResolver.openInputStream(src)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: throw java.io.IOException("no input stream for $src")
        name
    } catch (e: Exception) {
        Log.w(TAG, "could not stage pending media", e)
        null
    }

    /** Resolve a staged file name back to a readable URI, or null if it is gone. */
    fun stagedUri(context: Context, name: String): android.net.Uri? {
        val f = java.io.File(stagingDir(context), name)
        return if (f.isFile) android.net.Uri.fromFile(f) else null
    }

    fun clearStaged(context: Context, names: List<String>) {
        names.forEach { name ->
            runCatching { java.io.File(stagingDir(context), name).delete() }
        }
    }

    private fun read(context: Context): JSONArray {
        val raw = prefs(context).getString(PREF_KEY, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            // A corrupt queue must not wedge capture; start clean and say so.
            Log.w(TAG, "pending capture queue unreadable; discarding", e)
            JSONArray()
        }
    }

    private fun write(context: Context, serialized: String): Boolean = try {
        prefs(context).edit().putString(PREF_KEY, serialized).commit()
    } catch (e: Exception) {
        Log.e(TAG, "could not persist pending capture queue", e)
        false
    }

    /**
     * Test seam, matching [E2eeManager]: the AndroidKeyStore is unavailable in
     * JVM unit tests, so they inject plain SharedPreferences.
     */
    @Volatile
    internal var prefsProvider: ((Context) -> SharedPreferences)? = null

    fun resetPrefsProvider() {
        prefsProvider = null
    }

    private fun prefs(context: Context): SharedPreferences =
        prefsProvider?.invoke(context) ?: SecurePrefs.get(context)
}
