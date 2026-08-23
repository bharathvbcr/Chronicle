package com.chronicle.app

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Cheap vault change signal: graph.json mtime + recent entry months + PARA presence.
 * Used by the foreground poll to decide whether to call refreshAll.
 */
data class VaultFingerprint(
    val graphLastModifiedMs: Long?,
    val recentEntryFileCount: Int,
    val newestEntryFileName: String?,
    /** Max LAST_MODIFIED among sampled entry files (detects in-place overwrites). */
    val recentEntryMaxModifiedMs: Long? = null,
    /** True when `30-Knowledge/` exists (knowledge sync arrived). */
    val knowledgeDirPresent: Boolean = false,
    /** Non-chrome `.md` count under `40-Journal/` (cheap presence signal). */
    val journalMdCount: Int = 0,
)

/**
 * LAN status labels for Settings — never bare "Connected" for a saved URL alone.
 */
enum class LanHealthUi {
    NOT_CONFIGURED,
    LAN_CONFIGURED,
    MAC_REACHABLE,
    MAC_UNREACHABLE,
}

fun lanHealthUi(hasUrl: Boolean, healthOk: Boolean?): LanHealthUi = when {
    !hasUrl -> LanHealthUi.NOT_CONFIGURED
    healthOk == null -> LanHealthUi.LAN_CONFIGURED
    healthOk -> LanHealthUi.MAC_REACHABLE
    else -> LanHealthUi.MAC_UNREACHABLE
}

fun lanHealthLabel(status: LanHealthUi): String = when (status) {
    LanHealthUi.NOT_CONFIGURED -> "LAN not configured"
    LanHealthUi.LAN_CONFIGURED -> "LAN configured"
    LanHealthUi.MAC_REACHABLE -> "Mac reachable"
    LanHealthUi.MAC_UNREACHABLE -> "Mac unreachable"
}

/**
 * Whether a foreground poll should trigger refreshAll.
 * First observation only records the fingerprint (resume already refreshed).
 */
fun shouldRefreshForFingerprint(
    previous: VaultFingerprint?,
    current: VaultFingerprint,
): Boolean = previous != null && previous != current

/** Human-readable brain freshness from graph.generated ISO timestamp. */
fun formatBrainFreshness(generated: String?, now: ZonedDateTime = ZonedDateTime.now()): String? {
    if (generated.isNullOrBlank()) return null
    return try {
        val then = ZonedDateTime.parse(generated)
        val hours = ChronoUnit.HOURS.between(then, now)
        when {
            hours < 1 -> "brain updated just now"
            hours < 48 -> "brain updated ${hours}h ago"
            else -> "brain updated ${hours / 24}d ago"
        }
    } catch (_: Exception) {
        "brain updated recently"
    }
}

/**
 * Vault status line for Settings when a folder is picked.
 * [brainFreshness] is the formatted string from [formatBrainFreshness], or null if brain missing.
 * [entryCount] / [noteCount] distinguish empty stub vs content waiting for brain process.
 */
fun vaultStatusSubtitle(
    folderPicked: Boolean,
    brainFreshness: String?,
    entryCount: Int = 0,
    noteCount: Int = 0,
): String = when {
    !folderPicked -> "No vault folder selected"
    brainFreshness != null -> brainFreshness
    entryCount > 0 || noteCount > 0 -> "Vault has data — waiting for Mac brain process"
    else -> "Vault folder empty or not synced yet"
}
