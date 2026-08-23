package com.chronicle.app

/**
 * Pure helpers for Capture editor chrome: markdown-syntax detection (drives the
 * inline preview), word counting, and voice-clip duration labels.
 */

private val BLOCK_MARKER = Regex("^\\s*(#{1,6}\\s|>\\s?|[-+*]\\s|\\d+\\.\\s)")
private val INLINE_MARKER = Regex(
    "(\\*\\*[^*]+\\*\\*)|(\\*[^*\\s][^*]*\\*)|(~~[^~]+~~)|(`[^`]+`)|(\\[[^]]+]\\([^)]*\\))|(\\[\\[[^]]+]])",
)

/** True when [text] contains CommonMark markers worth rendering in the inline preview. */
fun hasMarkdownSyntax(text: String): Boolean {
    if (text.isBlank()) return false
    if (INLINE_MARKER.containsMatchIn(text)) return true
    return text.lineSequence().any { BLOCK_MARKER.containsMatchIn(it) }
}

/** Whitespace-delimited word count; blank text counts as 0. */
fun wordCount(text: String): Int {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    return trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
}

/** Format a clip duration in milliseconds as M:SS (e.g. 83_000 -> "1:23"). */
fun formatClipDuration(millis: Long): String {
    val totalSec = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
