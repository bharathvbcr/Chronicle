package com.chronicle.app

import android.content.Context
import android.net.Uri
import com.chronicle.app.e2ee.E2eeManager
import com.chronicle.app.e2ee.PendingCaptureQueue
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The two vault operations a background capture needs (a seam for JVM tests). */
interface CaptureVault {
    fun entryExists(id: String): Boolean
    fun save(entry: Entry, imageUris: List<Uri>, audioLocalPaths: List<String> = emptyList()): Entry?
}

fun VaultRepository.asCaptureVault(): CaptureVault = object : CaptureVault {
    override fun entryExists(id: String) = entryFileExists(id)
    override fun save(entry: Entry, imageUris: List<Uri>, audioLocalPaths: List<String>) =
        saveEntry(entry, imageUris, audioLocalPaths)
}

/** A capture taken outside MainActivity (share sheet, quick-capture worker). */
data class CaptureDraft(
    val text: String,
    val type: String = "log",
    val tags: List<String> = emptyList(),
    val mood: Int? = null,
    val imageUris: List<Uri> = emptyList(),
)

sealed interface CaptureOutcome {
    data class Saved(val entry: Entry) : CaptureOutcome

    /** E2EE is on and locked: held sealed at rest outside the vault until unlock. */
    data object Parked : CaptureOutcome

    data object Failed : CaptureOutcome
}

/**
 * Write a background capture to the vault, sealed when E2EE is on, or park it
 * when E2EE is on but this process holds no key.
 *
 * E2EE state comes from persisted prefs ([E2eeManager.sealForCapture]), never
 * the in-memory flag: these paths run in processes where MainActivity never
 * loaded it, and the default there is "off". The decision is made before the
 * vault is touched — parking needs no vault — so [vault] may be null when the
 * folder grant is missing.
 */
fun writeCapture(
    context: Context,
    vault: CaptureVault?,
    draft: CaptureDraft,
    now: ZonedDateTime = ZonedDateTime.now(),
): CaptureOutcome {
    val ts = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    val id = generateEntryId(now, exists = { vault?.entryExists(it) ?: false })
    val (text, textEnc) = when (val seal = E2eeManager.sealForCapture(context, draft.text)) {
        E2eeManager.CaptureSeal.Locked -> {
            val parked = PendingCaptureQueue.park(
                context,
                id = id,
                ts = ts,
                type = draft.type,
                text = draft.text,
                tags = draft.tags,
                mood = draft.mood,
                images = draft.imageUris,
            )
            return if (parked) CaptureOutcome.Parked else CaptureOutcome.Failed
        }
        is E2eeManager.CaptureSeal.Sealed -> "" to seal.blob
        E2eeManager.CaptureSeal.Clear -> draft.text to null
    }
    vault ?: return CaptureOutcome.Failed
    val entry = Entry(
        id = id,
        ts = ts,
        type = draft.type,
        text = text,
        textEnc = textEnc,
        tags = draft.tags,
        mood = draft.mood?.takeIf { it in 1..5 },
        processed = false,
    )
    val saved = vault.save(entry, draft.imageUris) ?: return CaptureOutcome.Failed
    // Best-effort LAN mirror (Syncthing remains the source of truth). The
    // entry is already in the vault: a mirror failure must not turn into a
    // retry that writes it a second time.
    runCatching {
        com.chronicle.app.net.LanOutboxWorker.enqueueEntry(
            context,
            org.json.JSONObject(serializeEntry(saved)),
        )
    }.onFailure { android.util.Log.w("CaptureWriter", "LAN mirror of ${saved.id} not queued", it) }
    return CaptureOutcome.Saved(saved)
}

/** What one [flushPendingCaptures] pass did: captures filed into the vault, and the queue's size afterwards. */
data class FlushResult(val written: Int, val stillQueued: Int)

/** One flush at a time: two passes over the same queue would both file every capture. */
private val flushLock = Any()

/**
 * File every parked capture into [vault] — sealed when E2EE is on, as-is when
 * it is off (quick captures the worker could not write). Blocking; call off
 * the main thread.
 *
 * Read, write, then remove: each capture leaves [PendingCaptureQueue] only
 * after its own vault write succeeded, so a failure or process death at any
 * point leaves every capture not yet written still queued. The trade-off is
 * the window between a write and its removal — dying there files that capture
 * again on the next flush, beside the first copy under an `_N` id (ids are
 * de-duplicated against the vault below). A duplicate entry, never a loss.
 */
fun flushPendingCaptures(context: Context, vault: CaptureVault): FlushResult = synchronized(flushLock) {
    flushLocked(context, vault)
}

private fun flushLocked(context: Context, vault: CaptureVault): FlushResult {
    val pending = PendingCaptureQueue.peek(context)
    var written = 0
    for (obj in pending) {
        val plaintext = obj.optString("text")
        val (text, textEnc) = when (val seal = E2eeManager.sealForCapture(context, plaintext)) {
            // Locked for one is locked for all: leave the rest queued.
            E2eeManager.CaptureSeal.Locked -> break
            is E2eeManager.CaptureSeal.Sealed -> "" to seal.blob
            E2eeManager.CaptureSeal.Clear -> plaintext to null
        }
        val stagedNames = jsonStrings(obj.optJSONArray("images"))
        // Parked ids were minted without a vault to check against; never let
        // one overwrite an entry written since (same second, or this capture's
        // own earlier copy after a crash before its removal).
        val parkedId = obj.optString("id")
        val id = if (vault.entryExists(parkedId)) {
            val base = parkedId.replace(Regex("_\\d+$"), "")
            var n = 2
            while (vault.entryExists("${base}_$n")) n++
            "${base}_$n"
        } else {
            parkedId
        }
        val entry = Entry(
            id = id,
            ts = obj.optString("ts"),
            type = obj.optString("type", "log"),
            text = text,
            textEnc = textEnc,
            tags = jsonStrings(obj.optJSONArray("tags")),
            mood = if (obj.has("mood") && !obj.isNull("mood")) obj.optInt("mood") else null,
            processed = false,
        )
        val imageUris = stagedNames.mapNotNull { PendingCaptureQueue.stagedUri(context, it) }
        val saved = try {
            vault.save(entry, imageUris, jsonStrings(obj.optJSONArray("audio")))
        } catch (e: Exception) {
            android.util.Log.w("CaptureWriter", "pending capture write failed", e)
            null
        }
        // Not written: it stays queued for the next flush.
        saved ?: continue
        written++
        if (!PendingCaptureQueue.remove(context, obj)) {
            // Written but still queued, so the next flush files it again as an
            // `_N` duplicate. Stop here: if the queue cannot be persisted, every
            // further write would leave one more duplicate behind.
            android.util.Log.e("CaptureWriter", "filed ${saved.id} but could not remove it from the pending queue")
            break
        }
        // Only after removal: a crash before it must not leave a queued
        // capture whose images are gone.
        PendingCaptureQueue.clearStaged(context, stagedNames)
    }
    return FlushResult(written, PendingCaptureQueue.count(context))
}

private fun jsonStrings(arr: org.json.JSONArray?): List<String> {
    arr ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
}
