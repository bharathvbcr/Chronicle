package com.chronicle.app

/** Result of creating a knowledge note (PC 409 / path / vault parity). */
sealed class CreateKbNoteResult {
    data class Success(val path: String) : CreateKbNoteResult()
    data class AlreadyExists(val path: String) : CreateKbNoteResult()
    data object InvalidPath : CreateKbNoteResult()
    data object NoVault : CreateKbNoteResult()
    data object WriteFailed : CreateKbNoteResult()
}

/** A derived note under notes/ (Mac pipeline writes; phone reads). */
data class NoteRef(
    val path: String,
    val name: String,
    val category: String,
    val text: String,
)

/**
 * A knowledge note under PARA areas (phone + dashboard writable).
 * [path] is vault-relative (e.g. `00-Inbox/Foo.md` or `10-Work/ResumePoints/Foo.md`).
 */
data class KbNoteRef(
    val path: String,
    val name: String,
    val category: String,
    val text: String,
)

/**
 * A STAR bank under `10-Work/ResumePoints/` (phone reads).
 */
data class ResumePointRef(
    val path: String,
    val name: String,
    val text: String,
)

/**
 * Resolve a knowledge path into vault-root directory segments + filename.
 * Accepts PARA (`00-Inbox/Foo.md`) or bare relative (→ Inbox / Work ResumePoints).
 * Returns null if the path is empty, escapes the tree, is legacy kb/notes, or is not a `.md` file.
 */
fun resolveKbNotePath(relativePath: String): Pair<List<String>, String>? =
    KnowledgePathMap.splitVaultRel(relativePath)

/**
 * Normalize UI/graph path to vault-relative knowledge path for editor selection.
 */
fun normalizeKbNoteVaultPath(path: String): String? {
    val validated = KnowledgePathMap.validateKnowledgeRel(path)
    if (validated != null) return validated
    // Prefer PARA Inbox for bare creates when validating display path
    val preferred = KnowledgePathMap.preferredWriteRel(path, create = false)
    return preferred?.let { KnowledgePathMap.validateKnowledgeRel(it) }
}

/** Sanitize a display name into a relative `.md` path (optional folder prefix). */
fun kbNoteFileName(name: String, folder: String? = null): String {
    val base = name.trim()
        .replace(Regex("""[\\/]+"""), "-")
        .replace(Regex("""\s+"""), "-")
        .trim('-')
        .ifBlank { "untitled" }
    val withExt = if (base.endsWith(".md", ignoreCase = true)) base else "$base.md"
    val prefix = folder?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
    return if (prefix != null) "$prefix/$withExt" else withExt
}

/** Listing preview when note body was not loaded (metadata-only walk). */
fun kbNoteListingPreview(name: String): String {
    val stem = name.removeSuffix(".md").removeSuffix(".MD")
    return if (stem.isBlank()) "" else "# ${stem.replace('-', ' ').replace('_', ' ')}"
}

/** Resolve `[[wikilink]]` target against listed vault paths (basename / suffix match). */
fun resolveWikilinkPath(target: String, paths: List<String>): String? {
    val raw = target.trim().removePrefix("./")
    if (raw.isBlank()) return null
    val withExt = if (raw.endsWith(".md", ignoreCase = true)) raw else "$raw.md"
    val normTarget = KnowledgePathMap.norm(withExt)
    paths.firstOrNull { KnowledgePathMap.norm(it) == normTarget }?.let { return it }
    paths.firstOrNull { it.endsWith("/$withExt", ignoreCase = true) }?.let { return it }
    val base = normTarget.substringAfterLast('/')
    return paths.firstOrNull {
        it.substringAfterLast('/').equals(base, ignoreCase = true)
    }
}

/** Resolve `[[wikilink]]` target against listed knowledge notes (basename / suffix match). */
fun resolveWikilinkTarget(target: String, notes: List<KbNoteRef>): String? =
    resolveWikilinkPath(target, notes.map { it.path })

/** Resolve against knowledge + journal/derived paths for cross-section opens. */
fun resolveWikilinkTarget(
    target: String,
    kbNotes: List<KbNoteRef>,
    journalNotes: List<NoteRef>,
): String? {
    val paths = kbNotes.map { it.path } + journalNotes.map { it.path } +
        listOf(KnowledgePathMap.HOME_NOTE, "Upcoming.md")
    return resolveWikilinkPath(target, paths.distinct())
}

/** Pin Home.md then MOC-*.md hub rows ahead of the rest. */
fun sortKbNotesWithHubs(notes: List<KbNoteRef>): List<KbNoteRef> {
    val home = notes.filter { it.path == "Home.md" }
    val mocs = notes.filter { it.name.startsWith("MOC-", ignoreCase = true) && it.path != "Home.md" }
        .sortedBy { it.path.lowercase() }
    val pinned = (home + mocs).distinctBy { it.path }
    val pinnedPaths = pinned.map { it.path }.toSet()
    val rest = notes.filter { it.path !in pinnedPaths }.sortedBy { it.path.lowercase() }
    return pinned + rest
}
