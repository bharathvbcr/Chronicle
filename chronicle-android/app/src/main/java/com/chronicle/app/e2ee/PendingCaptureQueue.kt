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
 *
 * It also holds quick captures the background worker could not write after
 * its final retry (no SAF grant, provider errors) — the same "not in the vault
 * yet" state, for an unencrypted vault too. Those are filed, sealed when E2EE
 * is on, the next time the app has the vault open.
 */
object PendingCaptureQueue {
    const val PREF_KEY = "pending_captures_v1"
    const val NOTIFICATION_CHANNEL = "chronicle_pending_captures"
    const val NOTIFICATION_ID = 78

    /** Plenty for a locked session; refuses beyond this rather than growing without limit. */
    const val MAX_PENDING = 500

    /** Guards against a single pathological paste filling the prefs file. */
    const val MAX_TOTAL_CHARS = 2_000_000

    private const val TAG = "PendingCaptureQueue"

    /**
     * Serialises read-modify-write of the queue: a park from the share sheet
     * or quick-capture worker can race a flush's [remove] on another thread,
     * and an unguarded pair would lose one side's change. The app runs in a
     * single process, so an in-process lock covers every writer.
     */
    private val lock = Any()

    /**
     * Park a capture. Returns false when the queue is full, so the caller can
     * tell the user to unlock rather than silently dropping their text.
     */
    fun enqueue(context: Context, entry: JSONObject): Boolean = synchronized(lock) { enqueueLocked(context, entry) }

    private fun enqueueLocked(context: Context, entry: JSONObject): Boolean {
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

    /**
     * Park one capture. [images] are content URIs, copied into app-private
     * storage now because their read grant may be gone by the time it is
     * filed; [audio] is already app-private. Returns false (and cleans up the
     * staged copies) when the queue refuses it.
     */
    fun park(
        context: Context,
        id: String,
        ts: String,
        type: String,
        text: String,
        tags: List<String>,
        mood: Int?,
        images: List<android.net.Uri> = emptyList(),
        audio: List<String> = emptyList(),
    ): Boolean {
        val staged = images.mapNotNull { stageMedia(context, it) }
        val obj = JSONObject()
            .put("id", id)
            .put("ts", ts)
            .put("type", type)
            .put("text", text)
            .put("tags", JSONArray(tags))
            .put("images", JSONArray(staged))
            .put("audio", JSONArray(audio))
        mood?.takeIf { it in 1..5 }?.let { obj.put("mood", it) }
        val ok = enqueue(context, obj)
        if (!ok) clearStaged(context, staged)
        return ok
    }

    fun count(context: Context): Int = read(context).length()

    /** Everything parked, oldest first, without removing anything. */
    fun peek(context: Context): List<JSONObject> {
        val arr = read(context)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /**
     * Tell the user captures are waiting, from a background path with no UI.
     * Silently dropped by the system when notifications are not permitted;
     * the count is also shown in Settings.
     */
    fun notifyPending(context: Context, waiting: Int = count(context)) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Captures waiting to be saved",
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = android.app.PendingIntent.getActivity(
            context,
            0,
            android.content.Intent(context, com.chronicle.app.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(com.chronicle.app.R.mipmap.ic_launcher_foreground)
            .setContentTitle("Capture not saved yet")
            .setContentText(
                if (waiting > 0) {
                    "$waiting capture(s) waiting — open Chronicle to save them to your vault."
                } else {
                    "A capture could not be saved — open Chronicle to check your vault folder."
                },
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Drop one capture the caller has just written to the vault. Call only
     * after the write succeeded — the queue is the capture's only copy until
     * then, so nothing is removed up front.
     *
     * Matched on the whole record, not its id: parked ids are minted without
     * a vault to check against, so two captures from the same second share
     * one, and removing by id would delete the unwritten sibling. Exactly one
     * matching occurrence is removed. The queue is re-read under the lock, so
     * captures parked since the caller's [peek] are kept.
     *
     * Dying between the vault write and this call leaves the capture queued;
     * the next flush writes it again beside the first copy under an `_N` id —
     * a duplicate entry at worst, never a lost one.
     *
     * Returns false when the record was not found or the queue could not be
     * persisted; the capture then stays queued.
     */
    fun remove(context: Context, entry: JSONObject): Boolean = synchronized(lock) { removeLocked(context, entry) }

    private fun removeLocked(context: Context, entry: JSONObject): Boolean {
        val target = entry.toString()
        val arr = read(context)
        val kept = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!removed && obj.toString() == target) {
                removed = true
            } else {
                kept.put(obj)
            }
        }
        if (!removed) {
            Log.w(TAG, "written capture not found in the pending queue")
            return false
        }
        return write(context, kept.toString())
    }

    fun clear(context: Context) {
        synchronized(lock) { write(context, JSONArray().toString()) }
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
